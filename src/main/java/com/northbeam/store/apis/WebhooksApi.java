package com.northbeam.store.apis;

import com.northbeam.store.lib.Api;
import com.northbeam.store.lib.GatewayEvents;
import com.northbeam.store.lib.Http;
import com.northbeam.store.lib.Reply;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhooksApi {
    // The payment gateway calls this when something happens to a charge, such as a dispute.
    @PostMapping("/webhooks/gateway")
    public ResponseEntity<byte[]> gateway(HttpServletRequest http) {
        return Api.run(http, req -> {
            GatewayEvents.Handled handled = GatewayEvents.handleGatewayEvent(Http.jsonBody(req), req);
            return Reply.json(handled.status(), handled.body());
        });
    }

    // The shipping partner calls this when something happens to a shipment.
    @PostMapping("/webhooks/fulfillment")
    public ResponseEntity<byte[]> fulfillment(HttpServletRequest http) {
        return Api.run(http, req -> {
            GatewayEvents.Handled handled = GatewayEvents.handleFulfillmentEvent(Http.jsonBody(req), req);
            return Reply.json(handled.status(), handled.body());
        });
    }
}
