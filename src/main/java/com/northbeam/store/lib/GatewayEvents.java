package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.Chargeback;
import com.northbeam.store.lib.Models.Order;
import jakarta.servlet.http.HttpServletRequest;

public final class GatewayEvents {
    private GatewayEvents() {}

    public record Handled(int status, JsObject body) {}

    /**
     * The ONE place dispute notices are handled. Returns the status and body; callers send it.
     * POST /webhooks/gateway and the DEMO=1 /demo/dispute route both go through here.
     */
    public static Handled handleGatewayEvent(JsObject event, HttpServletRequest req) {
        if (Js.get(event, "type") instanceof String type && type.startsWith("charge.dispute.")) {
            JsObject data = Js.get(event, "data") instanceof JsObject d ? d : null; // anything else has no chargeId (JavaScript: (event.data || {}).chargeId)
            Order order = Db.findOrderByCharge(data == null ? Js.UNDEFINED : Js.get(data, "chargeId"));
            if (order == null) return new Handled(404, new JsObject().set("error", "Unknown charge"));
            // A dispute needs a real payment: a declined order was never paid. A paid order that was later canceled or fulfilled still was.
            if (!OrderStatus.wasPaid(order)) return new Handled(409, new JsObject().set("error", "This order was never paid"));
            order.status = "chargeback";
            Chargeback cb = new Chargeback();
            cb.reason = data == null ? Js.UNDEFINED : Js.get(data, "reason");
            cb.status = data == null ? Js.UNDEFINED : Js.get(data, "status");
            cb.receivedAt = Clock.nowIso(req);
            order.chargeback = cb;
        }
        return new Handled(200, new JsObject().set("received", true));
    }

    /**
     * The ONE place fulfillment notices are handled. Returns the status and body; callers send it.
     * POST /webhooks/fulfillment and the DEMO=1 /demo/orders/{id}/ship route both go through here.
     */
    public static Handled handleFulfillmentEvent(JsObject event, HttpServletRequest req) {
        if (Js.get(event, "event") instanceof String name && name.equals("shipment.created")) {
            Order order = Db.findOrder(Js.get(event, "orderId"));
            if (order == null) return new Handled(404, new JsObject().set("error", "Order not found"));
            OrderStatus.StatusResult result = OrderStatus.setOrderStatus(order, "fulfilled", req);
            if (!result.ok()) return new Handled(409, new JsObject().set("error", result.error()));
        }
        return new Handled(200, new JsObject().set("received", true));
    }
}
