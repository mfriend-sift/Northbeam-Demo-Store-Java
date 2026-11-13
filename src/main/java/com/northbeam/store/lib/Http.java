package com.northbeam.store.lib;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Request body helper: a bad JSON body is a client error, answered only after routing and auth.
 * Middleware keeps the raw bytes (JSON requests only, 100 KB at most, read before any lock); each handler calls
 * jsonBody once it has passed its auth and resource checks, so the order of answers is: unknown route 404, 401,
 * resource 404, bad JSON 400.
 */
public final class Http {
    private Http() {}

    public static final int MAX_BODY = 100 * 1024; // the shared contract's bound: 100 KB
    public static final String RAW_KEY = "nb.rawBody";
    public static final String HAS_BODY_KEY = "nb.hasBody";
    public static final String IS_JSON_KEY = "nb.isJson";
    public static final String RAW_PATH_KEY = "nb.rawPath";

    public static JsObject jsonBody(HttpServletRequest req) { return jsonBody(req, false); }

    /**
     * A body sent with any other Content-Type is refused. Returns {} for an empty body or one that is not an object.
     * With objectOnly, a body that parses to anything but an object (null, an array, a string, a number) is a 400.
     */
    public static JsObject jsonBody(HttpServletRequest req, boolean objectOnly) {
        boolean has = Boolean.TRUE.equals(req.getAttribute(HAS_BODY_KEY));
        boolean isJson = Boolean.TRUE.equals(req.getAttribute(IS_JSON_KEY));
        if (has && !isJson) throw new ApiException(415, "Content-Type must be application/json");
        if (!(req.getAttribute(RAW_KEY) instanceof byte[] raw) || raw.length == 0) return new JsObject();
        Object data;
        try { data = Json.parse(new String(raw, StandardCharsets.UTF_8)); }
        catch (Json.FormatException e) { throw new ApiException(400, "Invalid JSON"); }
        if (data instanceof JsObject obj) return obj;
        if (objectOnly) throw new ApiException(400, "Request body must be a JSON object");
        return new JsObject();
    }

    /**
     * A route parameter is percent-decoded as UTF-8 and answered 400 {"error":"Bad request"} when the text is
     * not valid percent-encoded UTF-8. The raw target is checked here, for the parameter segments of the matched
     * route only (a bad escape anywhere else is not an error, in the path or in the query).
     */
    public static void checkParams(HttpServletRequest req) {
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String p) || p.indexOf('{') < 0) return;
        String target = rawPath(req);
        String[] segments = p.split("/", -1);
        String[] raw = target.split("/", -1);
        for (int i = 0; i < segments.length && i < raw.length; i++) {
            if (segments[i].indexOf('{') < 0) continue;
            if (!decodesAsUtf8(raw[i])) throw new ApiException(400, "Bad request");
        }
    }

    /** The request path exactly as the client sent it (before the container's lenient re-spelling of bad escapes, see TomcatTuning). */
    public static String rawPath(HttpServletRequest req) {
        Object original = req.getAttribute(RAW_PATH_KEY);
        String target = original instanceof String s ? s : req.getRequestURI();
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(0, q) : target;
    }

    private static boolean decodesAsUtf8(String segment) {
        if (segment.indexOf('%') < 0) return true;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != '%') { byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8); bytes.write(b, 0, b.length); continue; }
            if (i + 2 >= segment.length()) return false;
            int hi = hex(segment.charAt(i + 1)), lo = hex(segment.charAt(i + 2));
            if (hi < 0 || lo < 0) return false;
            bytes.write(hi * 16 + lo);
            i += 2;
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()));
            return true;
        } catch (CharacterCodingException e) { return false; }
    }

    private static int hex(char h) {
        return h >= '0' && h <= '9' ? h - '0' : h >= 'a' && h <= 'f' ? h - 'a' + 10 : h >= 'A' && h <= 'F' ? h - 'A' + 10 : -1;
    }
}
