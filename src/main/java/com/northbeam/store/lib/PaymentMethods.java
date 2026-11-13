package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.PaymentMethod;
import com.northbeam.store.lib.Models.User;
import java.util.regex.Pattern;

/**
 * One card rule, shared by the pay route and the account's saved card. The card is only a token, the BIN and the
 * last four digits (never a card number).
 */
public final class PaymentMethods {
    private PaymentMethods() {}

    private static final Pattern CARD_BIN = Pattern.compile("\\A[0-9]{6}([0-9]{2})?\\z");
    private static final Pattern CARD_LAST4 = Pattern.compile("\\A[0-9]{4}\\z");
    public static final String CARD_MESSAGE = "needs token (non-empty text, at most 200 characters), cardBin (6 or 8 digits), and cardLast4 (4 digits)";

    public static boolean validCard(Object p) {
        return p instanceof JsObject o && Text.validText(Js.get(o, "token"))
            && Js.get(o, "cardBin") instanceof String bin && CARD_BIN.matcher(bin).matches()
            && Js.get(o, "cardLast4") instanceof String last4 && CARD_LAST4.matcher(last4).matches();
    }

    public record SaveResult(boolean ok, PaymentMethod paymentMethod, String error) {}

    /** The ONE place the account's saved card is validated and stored. */
    public static SaveResult savePaymentMethod(User user, JsObject input) {
        if (!validCard(input) || (!Js.isUndefined(Js.get(input, "type")) && !Text.validText(Js.get(input, "type"))))
            return new SaveResult(false, null, "Payment method " + CARD_MESSAGE);
        PaymentMethod pm = new PaymentMethod();
        pm.token = (String) input.get("token");
        pm.cardBin = (String) input.get("cardBin");
        pm.cardLast4 = (String) input.get("cardLast4");
        pm.type = Js.get(input, "type") instanceof String t ? t : "card";
        user.paymentMethod = pm;
        return new SaveResult(true, pm, null);
    }

    /** What the API shows: never the token (a reusable credential). The pay route reads the token server-side. */
    public static JsObject publicPaymentMethod(PaymentMethod pm) {
        return new JsObject().set("cardBin", pm.cardBin).set("cardLast4", pm.cardLast4).set("type", pm.type);
    }

    public static void removePaymentMethod(User user) { user.paymentMethod = null; }
}
