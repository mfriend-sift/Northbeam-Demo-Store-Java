package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.Order;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

public final class OrderStatus {
    private OrderStatus() {}

    private record Move(List<String> from, String error) {}

    // Statuses an order may move from, per new status. saved, pending, payment_failed and chargeback are set elsewhere (they have no status event).
    private static final Map<String, Move> MOVES = Map.of(
        "paid", new Move(List.of("pending"), "Order cannot be paid"),
        "canceled", new Move(List.of("saved", "payment_failed", "paid"), "Order cannot be canceled"),
        "fulfilled", new Move(List.of("paid"), "Order cannot be fulfilled"));

    public record StatusResult(boolean ok, String error) {}

    /**
     * The ONE place an order is paid, canceled or fulfilled: checks the move, sets order.status, records the change.
     * Routes and webhooks call it and answer 409 on a refusal.
     */
    public static StatusResult setOrderStatus(Order order, String status, HttpServletRequest req) {
        Move rule = MOVES.get(status);
        if (rule == null || !rule.from().contains(order.status)) return new StatusResult(false, rule != null ? rule.error() : "Unknown order status");
        order.status = status;
        Db.audit.add(new JsObject().set("kind", "order_status").set("orderId", order.id).set("userId", order.userId).set("status", status).set("at", Clock.nowIso(req)));
        return new StatusResult(true, null);
    }

    /**
     * The ONE "was this order paid" test: a charge exists and it did not decline. It does not depend on the current
     * status, so an order canceled after payment still counts as paid. Used by the dispute handler.
     */
    public static boolean wasPaid(Order order) {
        return order.chargeId != null && !order.chargeId.isEmpty() && (order.declineCode == null || order.declineCode.isEmpty());
    }
}
