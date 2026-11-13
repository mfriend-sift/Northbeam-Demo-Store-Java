package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.Session;
import com.northbeam.store.lib.Models.User;
import jakarta.servlet.http.HttpServletRequest;

public final class Signing {
    private Signing() {}

    /** The ONE place a user is bound to a session. Signup and login both call it. */
    public static void signIn(HttpServletRequest req, User user) { Sessions.of(req).userId = user.id; }

    public record SignOutResult(boolean ok, String userId) {}

    /**
     * The ONE place a sign-out is decided and recorded.
     * Returns not ok at once when no user is signed in (nothing to sign out, nothing recorded).
     * Otherwise captures the user id, clears the session user, records the sign-out, and returns ok with the user id.
     */
    public static SignOutResult signOut(HttpServletRequest req) {
        Session session = Sessions.of(req);
        if (session.userId == null || session.userId.isEmpty()) return new SignOutResult(false, null);
        String userId = session.userId;
        session.userId = null;
        Db.audit.add(new JsObject().set("kind", "logout").set("userId", userId).set("at", Clock.nowIso(req)));
        return new SignOutResult(true, userId);
    }
}
