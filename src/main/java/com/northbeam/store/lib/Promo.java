package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.CartLine;
import com.northbeam.store.lib.Models.Product;
import com.northbeam.store.lib.Models.PromoRule;
import com.northbeam.store.lib.Models.User;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public final class Promo {
    private Promo() {}

    private static List<CartLine> cartOf(String userId) {
        List<CartLine> c = Db.carts.get(userId);
        return c == null ? new ArrayList<>() : c;
    }

    private static Product product(String id) {
        for (Product p : Db.products) if (p.id.equals(id)) return p;
        return null;
    }

    // Promo codes live on the cart only. A code is never applied to a saved order.
    private static double cartCents(String userId) {
        double sum = 0;
        for (CartLine l : cartOf(userId)) sum += Cents.of(product(l.productId).price) * l.qty;
        return sum;
    }

    private static final int MAX_CODE = 64; // longer text is never a code; the audit keeps its first 64 characters

    private static String normalize(String code) { return Js.toUpper(Js.trim(code)); }

    // What a code takes off a cart worth `cents`, in cents.
    private static double discountCents(PromoRule rule, double cents) {
        return rule.type.equals("percent") ? Js.round(cents * rule.value / 100) : Math.min(Js.round(rule.value * 100), cents);
    }

    // One decision about one code: ok with the discount in cents, or the reason and the sentence to show.
    private record Check(boolean ok, String reason, String error, double cents) {
        static Check no(String reason, String error) { return new Check(false, reason, error, 0); }
    }

    private static PromoRule rule(String code) {
        for (PromoRule p : Db.promotions) if (p.code.equals(code)) return p;
        return null;
    }

    // Decides one code against one cart. Pure: it records nothing.
    // Order of the checks: unknown, expired, already applied, used up, cart empty, below the minimum.
    private static Check evaluate(String code, String userId, HttpServletRequest req) {
        PromoRule rule = rule(code);
        if (rule == null) return Check.no("unknown", "That promo code is not valid");
        if (rule.expiresAt != null && expired(rule.expiresAt, req)) return Check.no("expired", "That promo code has expired");
        String applied = Db.promos.get(userId);
        if (applied != null) return Check.no("already_applied", applied.equals(code) ? "That promo code is already applied" : "A promo code is already applied. Remove it first");
        return evaluateApplicable(rule, userId);
    }

    private static boolean expired(String expiresAt, HttpServletRequest req) {
        Long t = parseTime(expiresAt);
        return t != null && t < Clock.nowMs(req);
    }

    // A time as the seed writes it (ISO 8601, with or without an offset; no offset means UTC); null when it is not a time.
    private static Long parseTime(String text) {
        String s = text.trim();
        try { return Instant.parse(s).toEpochMilli(); } catch (RuntimeException e) { /* next form */ }
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (RuntimeException e) { /* next form */ }
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (RuntimeException e) { /* next form */ }
        try { return java.time.LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (RuntimeException e) { return null; }
    }

    // The part of the decision that depends on the cart and on use counts (also used to price a cart that already holds a code).
    private static Check evaluateApplicable(PromoRule rule, String userId) {
        if (rule.maxUses != null && Db.promoUses.getOrDefault(rule.code, 0.0) >= rule.maxUses) return Check.no("used_up", "That promo code has been used up");
        double cents = cartCents(userId);
        if (cents == 0) return Check.no("not_applicable", "Add something to your cart to use a promo code");
        if (rule.minimum > 0 && cents < Js.round(rule.minimum * 100)) return Check.no("not_applicable", "That promo code needs a cart of " + Js.num(rule.minimum) + " dollars or more");
        return new Check(true, null, null, discountCents(rule, cents));
    }

    /** The outcome in the store's own words: status "accepted" (code, discount) or "rejected" (code, reason, error). */
    public record Outcome(String status, String code, double discount, String reason, String error) {}

    /** The ONE place a promo code is evaluated and its outcome recorded, accepted or rejected alike. */
    public static Outcome applyPromo(User user, String input, HttpServletRequest req) {
        String text = normalize(input);
        String code = text.length() > MAX_CODE ? text.substring(0, MAX_CODE) : text;
        Check result = text.length() > MAX_CODE ? Check.no("unknown", "That promo code is not valid") : evaluate(code, user.id, req);
        Outcome outcome = result.ok() ? new Outcome("accepted", code, result.cents() / 100, null, null) : new Outcome("rejected", code, 0, result.reason(), result.error());
        if (result.ok()) Db.promos.put(user.id, code);
        JsObject entry = new JsObject().set("kind", "promo").set("userId", user.id).set("code", code).set("status", outcome.status());
        if (outcome.reason() != null && !outcome.reason().isEmpty()) entry.set("reason", outcome.reason());
        if (result.ok()) entry.set("discount", outcome.discount());
        entry.set("at", Clock.nowIso(req));
        Db.audit.add(entry);
        return outcome;
    }

    public static void removePromo(User user, HttpServletRequest req) {
        String code = Db.promos.get(user.id);
        if (code == null) return;
        Db.promos.remove(user.id);
        Db.audit.add(new JsObject().set("kind", "promo_removed").set("userId", user.id).set("code", code).set("status", "removed").set("at", Clock.nowIso(req)));
    }

    /** The promotion a cart carries and what it takes off. applies is false when the code no longer applies. */
    public static final class Held implements IJson {
        public String code = "", description = "", type = "";
        public double value, minimum, discount;
        public boolean applies;
        public String reason, message;

        /** A code's own terms, in the store's plain words: shown on the cart and kept on the order. */
        public JsObject terms() {
            return new JsObject().set("code", code).set("description", description).set("type", type).set("value", value).set("minimum", minimum);
        }

        @Override public JsObject toJson() {
            JsObject o = terms();
            o.set("applies", applies);
            if (reason != null) { o.set("reason", reason).set("message", message); }
            return o;
        }
    }

    private static Held terms(PromoRule rule) {
        Held h = new Held();
        h.code = rule.code; h.description = rule.description; h.type = rule.type; h.value = rule.value; h.minimum = rule.minimum;
        return h;
    }

    /**
     * The promotion and discount a cart carries now: the applied code, worked out again against what is in the cart.
     * Null when no code is applied. A code that no longer applies (the cart changed, it expired, it ran out) is still
     * returned, with applies false, a reason and a message and a discount of 0, so the shopper can see and remove it.
     * `reason` is the status word (the same words the audit record uses); `message` is the sentence for display.
     */
    public static Held cartPromotion(String userId, HttpServletRequest req) {
        if (userId == null) return null;
        String code = Db.promos.get(userId);
        if (code == null) return null;
        PromoRule rule = rule(code);
        if (rule == null) {
            Held h = new Held();
            h.code = code; h.description = ""; h.type = "amount"; h.value = 0; h.minimum = 0; h.applies = false;
            h.reason = "unknown"; h.message = "That promo code is not valid"; h.discount = 0;
            return h;
        }
        Check check = rule.expiresAt != null && expired(rule.expiresAt, req) ? Check.no("expired", "That promo code has expired") : evaluateApplicable(rule, userId);
        Held held = terms(rule);
        if (check.ok()) { held.applies = true; held.discount = check.cents() / 100; }
        else { held.applies = false; held.reason = check.reason(); held.message = check.error(); held.discount = 0; }
        return held;
    }

    /** The cart as the API shows it: the lines plus the promotion and discount when one is applied. */
    public static JsObject cartView(String userId, HttpServletRequest req) {
        JsObject view = new JsObject().set("cart", userId == null ? new ArrayList<CartLine>() : cartOf(userId));
        Held held = cartPromotion(userId, req);
        if (held != null) { view.set("promotion", held).set("discount", held.discount); }
        return view;
    }

    public record Redeemed(JsObject promotion, double discount) {}

    /**
     * Called when an order is made from the cart (the route has refused the order if the code no longer applies): returns
     * the promotion and discount to put on the order (or null), counts the use, and clears the code from the cart.
     */
    public static Redeemed redeemPromo(String userId, HttpServletRequest req) {
        Held carried = cartPromotion(userId, req);
        Db.promos.remove(userId);
        if (carried == null || !carried.applies) return null;
        Db.promoUses.put(carried.code, Db.promoUses.getOrDefault(carried.code, 0.0) + 1);
        return new Redeemed(carried.terms(), carried.discount);
    }
}
