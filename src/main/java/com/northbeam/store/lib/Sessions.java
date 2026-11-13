package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Minimal cookie session: each browser gets a session id, and a logged-in user id once known.
 * The id travels in the nb_sid cookie. The middleware looks it up (or makes a new one) before any route runs and
 * puts the session and its live id on the request, so the id is known on every request, the first one included.
 */
public final class Sessions {
    private Sessions() {}

    public static final String COOKIE_NAME = "nb_sid";
    private static final String SESSION_KEY = "nb.session";
    private static final String SID_KEY = "nb.sid";
    private static final Map<String, Session> sessions = new HashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static Session of(HttpServletRequest req) { return (Session) req.getAttribute(SESSION_KEY); }

    /** The session id of this request (the nb_sid cookie's value, or the one just made). */
    public static String idOf(HttpServletRequest req) { return (String) req.getAttribute(SID_KEY); }

    public static void attach(HttpServletRequest req, String sid, Session session) {
        req.setAttribute(SESSION_KEY, session);
        req.setAttribute(SID_KEY, sid);
    }

    public record Found(String id, Session session, boolean created) {}

    /** Returns the session for this cookie value, or a new one (with a new id) when there is none. */
    public static Found find(String cookieValue) {
        return Db.sync(() -> {
            if (cookieValue != null && !cookieValue.isEmpty() && sessions.containsKey(cookieValue)) return new Found(cookieValue, sessions.get(cookieValue), false);
            byte[] b = new byte[8];
            RANDOM.nextBytes(b);
            String sid = "sess_" + HexFormat.of().formatHex(b);
            Session made = new Session();
            sessions.put(sid, made);
            return new Found(sid, made, true);
        });
    }

    public static void reset() { Db.sync(sessions::clear); }
}
