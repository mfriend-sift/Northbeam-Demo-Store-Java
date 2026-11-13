package com.northbeam.store.apis;

import com.northbeam.store.lib.Address;
import com.northbeam.store.lib.ApiException;
import com.northbeam.store.lib.Api;
import com.northbeam.store.lib.Clock;
import com.northbeam.store.lib.Db;
import com.northbeam.store.lib.Gateway;
import com.northbeam.store.lib.Http;
import com.northbeam.store.lib.Js;
import com.northbeam.store.lib.JsObject;
import com.northbeam.store.lib.Models.CartLine;
import com.northbeam.store.lib.Models.Charge;
import com.northbeam.store.lib.Models.Order;
import com.northbeam.store.lib.Models.OrderCard;
import com.northbeam.store.lib.Models.OrderItem;
import com.northbeam.store.lib.Models.Product;
import com.northbeam.store.lib.Models.User;
import com.northbeam.store.lib.OrderStatus;
import com.northbeam.store.lib.PaymentMethods;
import com.northbeam.store.lib.Promo;
import com.northbeam.store.lib.Reply;
import com.northbeam.store.lib.Sessions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrdersApi {
    private static final List<String> SHIPPING_METHODS = List.of("standard", "expedited");
    private static final List<String> PAYABLE = List.of("saved", "payment_failed");
    private static final List<String> EDITABLE = List.of("saved", "payment_failed", "paid");
    // Text rule: Text.validText; address rule: Address.validAddress; card rule: PaymentMethods.validCard (one definition each).

    private static String cannotPay(String status) {
        return status.equals("paid") ? "This order is already paid"
            : status.equals("pending") ? "A payment for this order is already in progress" : "This order cannot be paid";
    }

    private static boolean isShippingMethod(Object m) { return m instanceof String s && SHIPPING_METHODS.contains(s); }

    private static Reply signInFirst() { return Reply.error(401, "Sign in first"); }

    // What the pay route keeps from its first step: the order it claimed, held as an object (never looked up again by id),
    // what it must put back if the gateway fails, and the card token and amount to charge.
    private record Claim(Order order, String beforeStatus, OrderCard beforeCard, String token, double total, String currency) {}

    // Saves the cart as an order. Not paid yet: payment is a separate step (POST /api/orders/{id}/pay).
    @PostMapping("/api/orders")
    public ResponseEntity<byte[]> save(HttpServletRequest http) {
        return Api.run(http, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();

            JsObject body = Http.jsonBody(req);
            Object shippingAddress = Js.get(body, "shippingAddress");
            Object billingAddress = Js.get(body, "billingAddress");
            Object shippingMethod = Js.isUndefined(Js.get(body, "shippingMethod")) ? "standard" : Js.get(body, "shippingMethod");
            // Critical section: cart check, order creation and cart reset below run under the one store lock,
            // so two parallel saves cannot both succeed from one cart.
            List<CartLine> cart = Db.carts.get(user.id);
            if (cart == null) cart = new ArrayList<>();
            if (cart.isEmpty()) return Reply.error(400, "Your cart is empty");
            if (!Address.validAddress(shippingAddress) || !Address.validAddress(billingAddress))
                return Reply.error(400, "Shipping and billing addresses need name, address1, city, region, country, and zipcode (non-empty text, at most 200 characters each)");
            if (!isShippingMethod(shippingMethod)) return Reply.error(400, "shippingMethod must be standard or expedited");
            // A promo on the cart that no longer applies is never dropped silently: the shopper saw a lower total.
            Promo.Held held = Promo.cartPromotion(user.id, req);
            if (held != null && !held.applies) return Reply.error(409, held.message + ". Review your cart");
            List<OrderItem> items = new ArrayList<>();
            for (CartLine line : cart) {
                Product p = null;
                for (Product x : Db.products) if (x.id.equals(line.productId)) { p = x; break; }
                OrderItem item = new OrderItem();
                item.productId = p.id;
                item.sku = (String) p.raw.get("sku");
                item.name = (String) p.raw.get("name");
                item.price = p.price;
                item.brand = (String) p.raw.get("brand");
                item.category = (String) p.raw.get("category");
                item.qty = line.qty;
                items.add(item);
            }
            // The cart's promo (if one applies) comes off the total; it is counted as used and cleared from the cart here. Never applied to a saved order afterwards.
            Promo.Redeemed redeemed = Promo.redeemPromo(user.id, req);
            double discount = redeemed == null ? 0 : redeemed.discount();
            double sum = 0;
            for (OrderItem item : items) sum += item.price * item.qty;
            double total = Math.max(0, Js.round(sum * 100 - discount * 100)) / 100;

            Order order = new Order();
            order.id = Db.nextOrderId();
            order.userId = user.id;
            order.items = items;
            order.total = total;
            order.currency = "USD";
            order.shippingAddress = (JsObject) shippingAddress;
            order.billingAddress = (JsObject) billingAddress;
            order.shippingMethod = (String) shippingMethod;
            order.status = "saved";
            order.sessionId = Sessions.idOf(req);
            order.createdAt = Clock.nowIso(req);
            if (redeemed != null) { order.promotion = redeemed.promotion(); order.discount = discount; }
            Db.orders.add(order);
            Db.carts.put(user.id, new ArrayList<>());

            return Reply.json(201, new JsObject().set("order", order));
        });
    }

    // Changes the shipping address or method of an order, before or after payment.
    @PutMapping("/api/orders/{id}/shipping")
    public ResponseEntity<byte[]> shipping(HttpServletRequest http, @PathVariable String id) {
        return Api.run(http, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();
            Order order = Db.findOrder(id);
            if (order == null || !order.userId.equals(user.id)) return Reply.error(404, "Order not found");

            JsObject body = Http.jsonBody(req);
            Object shippingAddress = Js.get(body, "shippingAddress");
            Object shippingMethod = Js.get(body, "shippingMethod");
            if (Js.isUndefined(shippingAddress) && Js.isUndefined(shippingMethod)) return Reply.error(400, "Send shippingAddress, shippingMethod, or both");
            if (!Js.isUndefined(shippingAddress) && !Address.validAddress(shippingAddress)) return Reply.error(400, "shippingAddress" + AuthApi.INCOMPLETE_ADDRESS);
            if (!Js.isUndefined(shippingMethod) && !isShippingMethod(shippingMethod)) return Reply.error(400, "shippingMethod must be standard or expedited");
            if (!EDITABLE.contains(order.status)) return Reply.error(409, "Shipping can no longer be changed for this order");

            if (!Js.isUndefined(shippingAddress)) order.shippingAddress = (JsObject) shippingAddress;
            if (!Js.isUndefined(shippingMethod)) order.shippingMethod = (String) shippingMethod;

            return Reply.json(200, new JsObject().set("order", order));
        });
    }

    /*
     * Pays a saved order: charges the card token through the payment gateway. Three steps, so the slow part never holds the lock:
     * claim the order (under the lock), call the gateway (no lock), finish or put back (under the lock).
     */
    @PostMapping("/api/orders/{id}/pay")
    public ResponseEntity<byte[]> pay(HttpServletRequest req, @PathVariable String id) {
        Claim[] claimed = new Claim[1];
        Reply early;
        try {
            com.northbeam.store.lib.Http.checkParams(req);
            early = Db.sync(() -> takeClaim(req, id, claimed));
        } catch (ApiException e) { early = Reply.error(e.status, e.getMessage()); }
        if (early != null) return early.entity();
        Claim claim = claimed[0];

        // One gateway call per attempt. If it throws, undo the claim (the order must never stay pending) and answer 502.
        Charge charge;
        try { charge = Gateway.chargeCard(claim.total(), claim.currency(), claim.token()); }
        catch (RuntimeException err) {
            System.err.println("Gateway error: " + err.getMessage()); // message only: an error object can carry the request, and so the token
            charge = null;
        }

        Charge result = charge;
        return Db.sync(() -> finishPay(req, claim, result)).entity();
    }

    // Cancels one of the user's orders: not yet shipped, not in dispute.
    @PostMapping("/api/orders/{id}/cancel")
    public ResponseEntity<byte[]> cancel(HttpServletRequest http, @PathVariable String id) {
        return Api.run(http, req -> {
            User user = Db.findUser(Sessions.of(req).userId);
            if (user == null) return signInFirst();
            Order order = Db.findOrder(id);
            if (order == null || !order.userId.equals(user.id)) return Reply.error(404, "Order not found");

            OrderStatus.StatusResult result = OrderStatus.setOrderStatus(order, "canceled", req);
            if (!result.ok()) return Reply.error(409, result.error());
            return Reply.json(200, new JsObject().set("order", order));
        });
    }

    @GetMapping("/api/orders")
    public ResponseEntity<byte[]> list(HttpServletRequest http) {
        return Api.run(http, req -> {
            String userId = Sessions.of(req).userId;
            List<Order> mine = new ArrayList<>();
            for (Order o : Db.orders) if (o.userId.equals(userId)) mine.add(o);
            mine.sort(Comparator.comparing((Order o) -> o.createdAt).reversed()); // newest first; equal times keep their order (the sort is stable)
            return Reply.json(200, new JsObject().set("orders", mine));
        });
    }

    // Step 1 (under the lock): the checks, then claim the order as pending. Returns an answer when the request ends here.
    private static Reply takeClaim(HttpServletRequest req, String id, Claim[] out) {
        User user = Db.findUser(Sessions.of(req).userId);
        if (user == null) return signInFirst();
        Order order = Db.findOrder(id);
        if (order == null || !order.userId.equals(user.id)) return Reply.error(404, "Order not found");

        Object payment = Js.get(Http.jsonBody(req, true), "payment");
        String token, cardBin, cardLast4;
        // No payment in the body: the account's saved card is used.
        if (Js.isUndefined(payment)) {
            if (user.paymentMethod == null) return Reply.error(400, "No payment method on file");
            token = user.paymentMethod.token; cardBin = user.paymentMethod.cardBin; cardLast4 = user.paymentMethod.cardLast4;
        } else if (!PaymentMethods.validCard(payment)) return Reply.error(400, "Payment " + PaymentMethods.CARD_MESSAGE);
        else {
            JsObject card = (JsObject) payment;
            token = (String) card.get("token"); cardBin = (String) card.get("cardBin"); cardLast4 = (String) card.get("cardLast4");
        }
        if (!PAYABLE.contains(order.status)) return Reply.error(409, cannotPay(order.status));
        out[0] = new Claim(order, order.status, order.card, token, order.total, order.currency);
        order.status = "pending"; // claimed before the gateway call: a second pay request now gets 409
        OrderCard held = new OrderCard();
        held.bin = cardBin; held.last4 = cardLast4;
        order.card = held;
        return null;
    }

    // Step 3 (under the lock): works on the order object that step 1 claimed. A /demo/reset in between has already built
    // new orders, so a reused id can never point this payment at another shopper's order.
    private static Reply finishPay(HttpServletRequest req, Claim claim, Charge charge) {
        Order order = claim.order();
        if (charge == null) {
            order.status = claim.beforeStatus();
            order.card = claim.beforeCard();
            return Reply.error(502, "The payment gateway is unavailable. Try again.");
        }

        // A real charge exists from here on (the transaction point).
        order.chargeId = charge.id;
        order.declineCode = charge.declineCode == null || charge.declineCode.isEmpty() ? null : charge.declineCode;
        // paid goes through setOrderStatus (the one place with canceled and fulfilled). A decline is not reported
        // through it: payment_failed, like saved, has no status event.
        if (charge.status.equals("succeeded")) {
            OrderStatus.StatusResult result = OrderStatus.setOrderStatus(order, "paid", req);
            if (!result.ok()) return Reply.error(409, result.error());
            return Reply.json(200, new JsObject().set("order", order));
        }
        order.status = "payment_failed";
        return Reply.json(402, new JsObject().set("order", order).set("error", "Your card was declined"));
    }
}
