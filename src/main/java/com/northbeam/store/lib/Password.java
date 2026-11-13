package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.User;
import jakarta.servlet.http.HttpServletRequest;

public final class Password {
    private Password() {}

    /** The outcome in the store's own words: status "success" or "failure", reason "user_update". */
    public record Outcome(String status, String reason) {}

    /** The ONE place a password change is decided and recorded. */
    public static Outcome changePassword(User user, String current, String next, HttpServletRequest req) {
        boolean matches = Db.verify(current, user.passwordHash);
        if (matches) user.passwordHash = Db.hash(next);
        Outcome outcome = new Outcome(matches ? "success" : "failure", "user_update");
        Db.audit.add(new JsObject().set("kind", "password_change").set("userId", user.id).set("status", outcome.status()).set("reason", outcome.reason()).set("at", Clock.nowIso(req)));
        return outcome;
    }
}
