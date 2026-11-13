package com.northbeam.store.lib;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The records the store keeps, in memory. Money is in dollars as a double, as in the contract (the card BIN and last
 * four digits are text). Address objects are kept as sent: an order keeps whatever the shopper sent, an account keeps
 * the six known fields.
 */
public final class Models {
    private Models() {}

    public static final class Product implements IJson {
        public String id = "";
        public double price;
        public JsObject raw = new JsObject(); // the product as the contract seed lists it
        @Override public JsObject toJson() { return raw; }
    }

    public static final class PaymentMethod {
        public String token = "";
        public String cardBin = "";
        public String cardLast4 = "";
        public String type = "card";
    }

    public static final class User {
        public String id = "";
        public String name = "";
        public String email = "";
        public String phone = "";
        public String passwordHash = "";
        public String createdAt = "";
        public boolean verified;
        public JsObject billingAddress;
        public JsObject shippingAddress;
        public PaymentMethod paymentMethod;
    }

    public static final class CartLine implements IJson {
        public String productId = "";
        public double qty;
        @Override public JsObject toJson() { return new JsObject().set("productId", productId).set("qty", qty); }
    }

    public static final class OrderItem implements IJson {
        public String productId = "", sku = "", name = "", brand = "", category = "";
        public double price, qty;
        @Override public JsObject toJson() {
            return new JsObject().set("productId", productId).set("sku", sku).set("name", name).set("price", price)
                .set("brand", brand).set("category", category).set("qty", qty);
        }
    }

    public static final class OrderCard implements IJson {
        public String bin = "";
        public String last4 = "";
        @Override public JsObject toJson() { return new JsObject().set("bin", bin).set("last4", last4); }
    }

    public static final class Chargeback implements IJson {
        public Object reason = Js.UNDEFINED; // as the gateway sent it; left out of the JSON when it sent none
        public Object status = Js.UNDEFINED;
        public String receivedAt = "";
        @Override public JsObject toJson() { return new JsObject().set("reason", reason).set("status", status).set("receivedAt", receivedAt); }
    }

    public static final class Order implements IJson {
        public String id = "";
        public String userId = "";
        public List<OrderItem> items = new ArrayList<>();
        public double total;
        public JsObject promotion;   // the code's terms, set only when a code came off the total
        public double discount;
        public String currency = "USD";
        public JsObject shippingAddress = new JsObject();
        public JsObject billingAddress = new JsObject();
        public String shippingMethod;
        public String status = "saved";
        public String sessionId;
        public String createdAt = "";
        public OrderCard card;
        public String chargeId;
        public String declineCode;
        public Chargeback chargeback;

        @Override public JsObject toJson() {
            JsObject o = new JsObject().set("id", id).set("userId", userId).set("items", items).set("total", total);
            if (promotion != null) { o.set("promotion", promotion).set("discount", discount); }
            o.set("currency", currency);
            o.set("shippingAddress", shippingAddress);
            o.set("billingAddress", billingAddress);
            if (shippingMethod != null) o.set("shippingMethod", shippingMethod);
            o.set("status", status);
            if (sessionId != null) o.set("sessionId", sessionId);
            o.set("createdAt", createdAt);
            if (card != null) o.set("card", card);
            if (chargeId != null) o.set("chargeId", chargeId);
            if (declineCode != null) o.set("declineCode", declineCode);
            if (chargeback != null) o.set("chargeback", chargeback);
            return o;
        }
    }

    public static final class PromoRule {
        public String code = "", description = "", type = "";
        public double value, minimum;
        public String expiresAt;
        public Double maxUses;
    }

    /** A verification in progress: the code, where it went, and when. */
    public static final class PendingCode {
        public String channel = "", to = "", code = "";
        public double sentAt;
    }

    /** Wrong-code counter and lockout, kept per user across new codes. */
    public static final class VerificationGuard {
        public Map<String, Double> failures = new HashMap<>();
        public double lockedUntil;
        public double lastSentAt;
    }

    public static final class Session {
        public volatile String userId;
    }

    public static final class Notice implements IJson {
        public String userId = "", to = "", kind = "", channel = "", at = "";
        public String code;
        @Override public JsObject toJson() {
            JsObject o = new JsObject().set("userId", userId).set("to", to).set("kind", kind).set("channel", channel).set("at", at);
            if (code != null) o.set("code", code);
            return o;
        }
    }

    public static final class Charge {
        public String id = "", status = "";
        public String declineCode;
    }
}
