package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.User;
import jakarta.servlet.http.HttpServletRequest;

public final class Login {
    private Login() {}

    /** The outcome in the store's own words: status "success" or "failure". user is null on failure. */
    public record Outcome(String status, User user) {}

    /**
     * The ONE place a login is decided and recorded.
     * Checks the credentials, signs the user in on success, and records the attempt after the result is known.
     */
    public static Outcome attemptLogin(Object email, Object password, HttpServletRequest req) {
        User found = Db.findUserByEmail(email);
        boolean matches = found != null && Db.verify(Js.truthy(password) ? Js.str(password) : "", found.passwordHash);
        Outcome outcome = new Outcome(matches ? "success" : "failure", matches ? found : null);
        if (matches) Signing.signIn(req, found);
        Db.audit.add(new JsObject().set("kind", "login").set("userId", found == null ? null : found.id).set("status", outcome.status()).set("at", Clock.nowIso(req)));
        return outcome;
    }
}
