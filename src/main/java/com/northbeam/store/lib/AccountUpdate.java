package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.User;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.servlet.HandlerMapping;

public final class AccountUpdate {
    private AccountUpdate() {}

    private static final String[] PROFILE_FIELDS = { "name", "email", "phone", "billingAddress", "shippingAddress" };

    /**
     * The ONE place an account change is recorded.
     * Call it as the last step, after the change is saved and just before the response.
     * Records an audit entry of kind account_update with the kind of change: "profile" (with the fields sent),
     * "payment_method_saved", or "payment_method_removed".
     */
    public static void updateAccount(User user, HttpServletRequest req) {
        String change = "profile";
        List<String> fields = new ArrayList<>();
        // The route the request matched (letter case and a trailing slash already settled), not the path as spelled.
        if ("/api/account/payment-method".equals(req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))) {
            change = req.getMethod().equals("DELETE") ? "payment_method_removed" : "payment_method_saved";
            fields.add("paymentMethod");
        } else {
            JsObject body = Http.jsonBody(req);
            for (String k : PROFILE_FIELDS) {
                Object v = Js.get(body, k);
                if (Js.isPresent(v) && !(v instanceof String s && s.isEmpty())) fields.add(k);
            }
        }
        Db.audit.add(new JsObject().set("kind", "account_update").set("userId", user.id).set("change", change).set("fields", fields).set("at", Clock.nowIso(req)));
    }
}
