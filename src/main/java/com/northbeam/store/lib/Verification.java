package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.PendingCode;
import com.northbeam.store.lib.Models.User;
import com.northbeam.store.lib.Models.VerificationGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;

public final class Verification {
    private Verification() {}

    private static final List<String> CHANNELS = List.of("email", "sms");
    private static final double MAX_FAILURES = 5;
    private static final double LOCK_MS = 15 * 60 * 1000; // lockout after MAX_FAILURES wrong codes
    private static final double RESEND_MS = 30 * 1000; // wait between two codes
    private static final double CODE_LIFETIME_MS = 10 * 60 * 1000;
    private static final String DEMO_CODE = "123456"; // the fixed code under DEMO=1 only
    private static final SecureRandom RANDOM = new SecureRandom();

    /** An answer for the route to send: the HTTP status and the JSON body. */
    public record Result(int http, JsObject body) {}

    /**
     * The ONE place a verification outcome is recorded, in the store's words: pending (a code was sent), success, or failure
     * (with a reason: wrong_code, expired). Called by startVerification (pending) and confirmVerification (success, failure).
     */
    public static void recordVerification(User user, String channel, String status, HttpServletRequest req, String reason) {
        JsObject entry = new JsObject().set("kind", "verification").set("userId", user.id).set("channel", channel).set("status", status);
        if (reason != null && !reason.isEmpty()) entry.set("reason", reason);
        entry.set("at", Clock.nowIso(req));
        Db.audit.add(entry);
    }

    /**
     * Wrong-code counter and lockout, kept per user across new codes. The count is kept per destination (the address or phone a code
     * went to), so a success on one destination (say the shopper's own inbox) never clears the count of another (a phone being guessed).
     * Cleared by: a success on that destination, the lock running out, or /demo/reset. The lock itself belongs to the account.
     */
    private static VerificationGuard guard(String userId, double now) {
        VerificationGuard g = Db.verificationGuards.get(userId);
        if (g == null) { g = new VerificationGuard(); Db.verificationGuards.put(userId, g); }
        if (g.lockedUntil != 0 && now >= g.lockedUntil) { g.failures = new HashMap<>(); g.lockedUntil = 0; } // the lock ran out: a fresh set of tries
        return g;
    }

    private static String destination(User user, String channel) { return channel.equals("sms") ? user.phone : user.email; }
    private static double seconds(double ms) { return Math.ceil(ms / 1000); }

    /**
     * Sends a code and records the outcome "pending". Returns 200 {status: "pending", channel} or an error body.
     * Under DEMO=1 the code is the fixed demo code and the notice shows it; otherwise it is random and never leaves the server.
     */
    public static Result startVerification(User user, Object channelValue, HttpServletRequest req) {
        if (!(channelValue instanceof String channel) || !CHANNELS.contains(channel)) return new Result(400, new JsObject().set("error", "channel must be email or sms"));
        if (user.verified) return new Result(409, new JsObject().set("error", "Your account is already verified"));
        double now = Clock.nowMs(req);
        VerificationGuard g = guard(user.id, now);
        if (g.lockedUntil != 0) return new Result(429, new JsObject().set("error", "Too many wrong codes. Try again later").set("retryAfterSeconds", seconds(g.lockedUntil - now)));
        if (now - g.lastSentAt < RESEND_MS) return new Result(429, new JsObject().set("error", "Wait before sending a new code").set("retryAfterSeconds", seconds(g.lastSentAt + RESEND_MS - now)));
        boolean demo = Config.demo();
        String code = demo ? DEMO_CODE : String.format("%06d", RANDOM.nextInt(1000000));
        PendingCode pending = new PendingCode();
        pending.channel = channel; pending.to = destination(user, channel); pending.code = code; pending.sentAt = now;
        Db.verifications.put(user.id, pending);
        g.lastSentAt = now;
        Mailer.sendVerificationCode(user, channel, demo ? code : null);
        recordVerification(user, channel, "pending", req, null);
        return new Result(200, new JsObject().set("status", "pending").set("channel", channel));
    }

    /**
     * Decides a confirm. Returns the answer for the route to send: 200 success, 400 wrong or expired code (with the attempts counter),
     * 429 locked (not counted or recorded as a failure), 409 nothing started.
     */
    public static Result confirmVerification(User user, String code, HttpServletRequest req) {
        PendingCode v = Db.verifications.get(user.id);
        if (v == null || user.verified || !v.to.equals(destination(user, v.channel))) return new Result(409, new JsObject().set("error", "No verification is in progress"));
        double now = Clock.nowMs(req);
        VerificationGuard g = guard(user.id, now);
        if (g.lockedUntil != 0) return new Result(429, new JsObject().set("error", "Too many wrong codes. Try again later").set("attempts", MAX_FAILURES).set("attemptsLeft", 0.0).set("retryAfterSeconds", seconds(g.lockedUntil - now)));
        if (now - v.sentAt >= CODE_LIFETIME_MS) return fail(user, v, g, now, req, "expired", "That code has expired. Send a new code");
        if (Codes.same(Js.trim(code), v.code)) {
            user.verified = true;
            Db.verifications.remove(user.id);
            g.failures.remove(v.to); // only this destination's count; the others keep theirs
            recordVerification(user, v.channel, "success", req, null);
            return new Result(200, new JsObject().set("status", "success").set("verified", true));
        }
        return fail(user, v, g, now, req, "wrong_code", "That code is not correct");
    }

    private static Result fail(User user, PendingCode v, VerificationGuard g, double now, HttpServletRequest req, String reason, String error) {
        double failed = g.failures.getOrDefault(v.to, 0.0) + 1;
        g.failures.put(v.to, failed);
        if (failed >= MAX_FAILURES) g.lockedUntil = now + LOCK_MS;
        recordVerification(user, v.channel, "failure", req, reason);
        return new Result(400, new JsObject().set("error", error).set("attempts", failed).set("attemptsLeft", MAX_FAILURES - failed));
    }

    /** The email or phone changed: the account is no longer verified and any code in progress is void (called by the account update). */
    public static void resetVerification(User user) {
        user.verified = false;
        Db.verifications.remove(user.id);
    }
}
