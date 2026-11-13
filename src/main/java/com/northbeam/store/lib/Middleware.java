package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.Session;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The steps every request passes before a route runs, in this order: errors, demo mode (DEMO=1 only), the request
 * body, the session. One servlet filter, so they run before Spring's handler lookup and nothing of Spring's own
 * request handling (body conversion, error pages, a session cookie) comes first.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class Middleware extends OncePerRequestFilter {
    private static final String DEFAULT_ENGINE_ORIGIN = "http://localhost:4010";
    private static final Pattern ORIGIN_SHAPE = Pattern.compile("\\Ahttps?://[A-Za-z0-9.-]+(:[0-9]{1,5})?\\z");

    // The one origin allowed to call the store from a browser: ENGINE_ORIGIN when it is a plain http(s) origin
    // (host and optional port, after trimming whitespace and one trailing slash), else the default. Resolved once at startup.
    private static final String ENGINE_ORIGIN = resolveEngineOrigin(Config.env("ENGINE_ORIGIN"));

    private static String resolveEngineOrigin(String raw) {
        String v = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        if (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return ORIGIN_SHAPE.matcher(v).matches() ? v : DEFAULT_ENGINE_ORIGIN;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            HttpServletRequest req = request;
            if (Config.demo()) {
                req = demo(request, response);
                if (req == null) return; // the preflight was answered
            }
            if (!body(req, response)) return; // refused as too large
            session(req, response);
            if (!Config.demo() && "OPTIONS".equals(req.getMethod())) { Reply.error(404, "Not found").writeTo(response); return; }
            chain.doFilter(req, response);
        } catch (ApiException e) {
            if (!response.isCommitted()) Reply.error(e.status, e.getMessage()).writeTo(response);
        } catch (Throwable e) {
            if (Reply.isClientGone(e)) return;
            System.err.println(e);
            e.printStackTrace();
            if (!response.isCommitted()) { response.reset(); Reply.error(500, "Something went wrong").writeTo(response); }
        }
    }

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    // Demo-mode step, only when DEMO=1 (set by tools/run.sh).
    // CORS for the engine origin, plus the persona headers (contract: demo-static.md and the engine's FREEZE.md section 11).
    // Returns the request the rest sees (with the persona), or null when the request was answered here.
    private HttpServletRequest demo(HttpServletRequest req, HttpServletResponse res) {
        String origin = header(req, "Origin");
        res.setHeader("Vary", "Origin");
        if (origin != null && origin.equals(ENGINE_ORIGIN)) {
            res.setHeader("Access-Control-Allow-Origin", ENGINE_ORIGIN);
            res.setHeader("Access-Control-Allow-Credentials", "true");
            res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            res.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Demo-Client-Ip, X-Demo-User-Agent, X-Demo-Now");
        }
        if ("OPTIONS".equals(req.getMethod())) { res.setStatus(204); return null; }

        // The persona: the client address the routes see is the persona's (behind loopback it comes from X-Forwarded-For);
        // the browser string is the persona's too.
        String ip = header(req, "X-Demo-Client-Ip");
        String agent = header(req, "X-Demo-User-Agent");
        double now = Js.toNumber(header(req, "X-Demo-Now"));
        if (!Double.isNaN(now) && !Double.isInfinite(now) && now > 0) {
            // A time past year 9999 has no four-digit year, so any route that reads the clock answers 500 (as the reference store's).
            // A request that changes something is refused here, before any change; a read keeps the answer of its route.
            if (!Clock.inRange(now) && !SAFE_METHODS.contains(req.getMethod())) { Reply.error(500, "Something went wrong").writeTo(res); return null; }
            req.setAttribute(Clock.DEMO_NOW_KEY, now);
        }
        return new Persona(req, ip == null || ip.isEmpty() ? null : ip, agent == null || agent.isEmpty() ? null : agent);
    }

    // Repeated headers are joined with ", ".
    private static String header(HttpServletRequest req, String name) {
        Enumeration<String> values = req.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        return String.join(", ", Collections.list(values));
    }

    /** The request as the persona sent it: X-Forwarded-For, User-Agent and the remote address replaced. */
    private static final class Persona extends HttpServletRequestWrapper {
        private final String ip, agent;

        Persona(HttpServletRequest req, String ip, String agent) { super(req); this.ip = ip; this.agent = agent; }

        @Override public String getHeader(String name) {
            if (ip != null && name.equalsIgnoreCase("X-Forwarded-For")) return ip;
            if (agent != null && name.equalsIgnoreCase("User-Agent")) return agent;
            return super.getHeader(name);
        }

        @Override public Enumeration<String> getHeaders(String name) {
            if (ip != null && name.equalsIgnoreCase("X-Forwarded-For")) return Collections.enumeration(List.of(ip));
            if (agent != null && name.equalsIgnoreCase("User-Agent")) return Collections.enumeration(List.of(agent));
            return super.getHeaders(name);
        }

        @Override public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            if (ip != null) names.add("X-Forwarded-For");
            if (agent != null) names.add("User-Agent");
            return Collections.enumeration(names);
        }

        @Override public String getRemoteAddr() {
            if (ip == null) return super.getRemoteAddr();
            String[] parts = ip.split(",");
            return parts[parts.length - 1].trim(); // the right-most entry, as a forwarded-headers step with a limit of one takes it
        }
    }

    /*
     * Keeps the raw bytes of a JSON body (and only a JSON body), at most 100 KB, before any route or lock runs: a client
     * that sends a body slowly or never finishes it holds nothing but its own connection. A body over the limit is 413 on
     * any route. Handlers read the bytes with Http.jsonBody after their auth and resource checks.
     */
    private boolean body(HttpServletRequest req, HttpServletResponse res) throws IOException {
        // A request has a body when it says so: a Transfer-Encoding header, or a Content-Length that is a number.
        String length = header(req, "Content-Length");
        boolean hasBody = req.getHeader("Transfer-Encoding") != null || (length != null && !Double.isNaN(Js.toNumber(length)));
        boolean isJson = hasBody && isJsonType(header(req, "Content-Type") == null ? "" : header(req, "Content-Type"));
        req.setAttribute(Http.HAS_BODY_KEY, hasBody);
        req.setAttribute(Http.IS_JSON_KEY, isJson);
        if (hasBody && isJson) {
            if (req.getContentLengthLong() > Http.MAX_BODY) { tooLarge(res); return false; }
            try (InputStream in = req.getInputStream()) {
                byte[] raw = in.readNBytes(Http.MAX_BODY + 1);
                if (raw.length > Http.MAX_BODY) { tooLarge(res); return false; }
                req.setAttribute(Http.RAW_KEY, raw);
            }
        }
        return true;
    }

    // application/json with no parameters or only well-formed name=value ones (any case, spaces around ";").
    // A ";" with no parameter after it, or a parameter without a value, is not a JSON type.
    private static final Pattern MEDIA_PARAMETER = Pattern.compile(
        "\\A[!#$%&'*+.^_`|~0-9A-Za-z-]+=(?:[!#$%&'*+.^_`|~0-9A-Za-z-]+|\"(?:[^\"\\\\]|\\\\.)*\")\\z");

    private static boolean isJsonType(String contentType) {
        String[] parts = contentType.split(";", -1);
        if (!Js.trim(parts[0]).equalsIgnoreCase("application/json")) return false;
        for (int i = 1; i < parts.length; i++) if (!MEDIA_PARAMETER.matcher(Js.trim(parts[i])).matches()) return false;
        return true;
    }

    private static void tooLarge(HttpServletResponse res) throws IOException {
        res.setHeader("Connection", "close");
        Reply.error(413, "Request body too large").writeTo(res);
    }

    /*
     * Each browser gets a session id (the nb_sid cookie). It is an analytics id, readable by page scripts, not an
     * authentication credential: who is signed in is kept server-side in the session.
     */
    private void session(HttpServletRequest req, HttpServletResponse res) {
        List<String> pairs = new ArrayList<>();
        Enumeration<String> cookies = req.getHeaders("Cookie");
        if (cookies != null) for (String h : Collections.list(cookies)) for (String piece : h.split(";", -1)) pairs.add(Js.trim(piece));
        String prefix = Sessions.COOKIE_NAME + "=";
        String found = null;
        for (String c : pairs) if (c.startsWith(prefix)) { found = c; break; }
        Sessions.Found f = Sessions.find(found == null ? null : found.substring(prefix.length()));
        if (f.created()) res.addHeader("Set-Cookie", Sessions.COOKIE_NAME + "=" + f.id() + "; Path=/; SameSite=Lax");
        Session s = f.session();
        Sessions.attach(req, f.id(), s);
    }
}
