package com.northbeam.store.apis;

import com.northbeam.store.lib.Api;
import com.northbeam.store.lib.Config;
import com.northbeam.store.lib.Db;
import com.northbeam.store.lib.GatewayEvents;
import com.northbeam.store.lib.Http;
import com.northbeam.store.lib.Js;
import com.northbeam.store.lib.JsObject;
import com.northbeam.store.lib.Mailer;
import com.northbeam.store.lib.Models.Order;
import com.northbeam.store.lib.Json;
import com.northbeam.store.lib.Reply;
import com.northbeam.store.lib.Sessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo-only routes, present only under DEMO=1. The scanner ignores them. */
@RestController
@Conditional(DemoOn.class)
public class DemoApi {
    private static final List<String> REASONS = List.of("duplicate", "fraudulent", "product_not_received", "product_unacceptable", "subscription_canceled", "general");
    private static final List<String> STATUSES = List.of("needs_response", "won", "lost");
    private static final HttpClient UPSTREAM = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String DEFAULT_MOCK = "http://localhost:4010";

    // Serves the one scenario module to the Demo drawer (DEMO=1 only: these routes are not present otherwise).
    @GetMapping("/demo/scenarios.js")
    public void scenarios(HttpServletResponse res) throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of("contract", "scenarios.js"));
        res.setContentType("application/javascript; charset=utf-8");
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
    }

    // Same-origin read of the mock's call log for the Demo drawer (the mock only allows its own CORS origin).
    // The upstream URL is built only from the fixed base plus an integer cursor. Failure is a normal 200 reply.
    @GetMapping("/demo/log")
    public void log(HttpServletRequest http, HttpServletResponse res) {
        String[] wanted = http.getParameterValues("since");
        double n = wanted != null && wanted.length == 1 ? Js.toNumber(wanted[0]) : Double.NaN;
        double since = !Double.isNaN(n) && Math.floor(n) == n && Math.abs(n) <= 9007199254740991.0 && n >= 0 ? n : 0;
        String set = Config.env("SIFT_API_URL");
        String baseUrl = stripSlashes(set != null && !set.isEmpty() ? set : DEFAULT_MOCK);
        Reply reply;
        try {
            HttpResponse<String> r = UPSTREAM.send(HttpRequest.newBuilder(URI.create(baseUrl + "/__log?since=" + Js.num(since))).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() > 299) throw new IllegalStateException("status " + r.statusCode());
            reply = Reply.json(200, Json.parse(r.body()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            reply = Reply.json(200, new JsObject().set("records", new ArrayList<>()).set("nextSeq", since).set("mockUp", false).set("error", "mock unreachable"));
        }
        reply.writeTo(res);
    }

    // Also resets the mock server-side (its POST /__reset has no browser CORS). Best effort: failure never changes the reply.
    @PostMapping("/demo/reset")
    public void reset(HttpServletResponse res) {
        Db.sync(() -> { Db.reset(); Sessions.reset(); Mailer.reset(); });
        Boolean mockReset = null;
        String baseUrl = Config.env("SIFT_API_URL");
        if (baseUrl != null && !baseUrl.isEmpty()) {
            try {
                HttpResponse<Void> r = UPSTREAM.send(HttpRequest.newBuilder(URI.create(stripSlashes(baseUrl) + "/__reset")).timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                mockReset = r.statusCode() >= 200 && r.statusCode() <= 299;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                mockReset = false;
            }
        }
        Reply.json(200, new JsObject().set("ok", true).set("mockReset", mockReset)).writeTo(res);
    }

    private static String stripSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    // Plays the payment gateway: builds the dispute event and hands it to the webhook handler (no dispute logic here).
    @PostMapping("/demo/dispute")
    public void dispute(HttpServletRequest http, HttpServletResponse res) {
        Api.run(http, res, req -> {
            JsObject body = Http.jsonBody(req);
            Object orderId = Js.get(body, "orderId");
            Object reason = Js.isUndefined(Js.get(body, "reason")) ? "duplicate" : Js.get(body, "reason");
            Object status = Js.isUndefined(Js.get(body, "status")) ? "needs_response" : Js.get(body, "status");
            if (!Js.truthy(orderId)) return Reply.error(400, "orderId is required");
            if (!(reason instanceof String r && REASONS.contains(r)) || !(status instanceof String s && STATUSES.contains(s))) return Reply.error(400, "Unknown reason or status");
            Order order = Db.findOrder(orderId);
            if (order == null || order.chargeId == null || order.chargeId.isEmpty()) return Reply.error(404, "Order not found or never charged");

            JsObject webhook = new JsObject()
                .set("type", status.equals("needs_response") ? "charge.dispute.created" : "charge.dispute.closed")
                .set("data", new JsObject().set("chargeId", order.chargeId).set("reason", reason).set("status", status).set("amount", order.total));
            // Same function the real webhook route uses: one dispute code path.
            GatewayEvents.Handled sent = GatewayEvents.handleGatewayEvent(webhook, req);
            // The handler's 409 (never paid) is passed through; no second paid test lives here.
            if (sent.status() == 409) return Reply.json(409, sent.body());
            return Reply.json(200, new JsObject().set("ok", sent.status() < 300).set("webhook", webhook).set("webhookStatus", sent.status()));
        });
    }

    // Plays the shipping partner: builds the shipment event and hands it to the fulfillment handler (no status logic here).
    @PostMapping("/demo/orders/{id}/ship")
    public void ship(HttpServletRequest http, HttpServletResponse res, @PathVariable String id) {
        Api.run(http, res, req -> {
            JsObject webhook = new JsObject().set("orderId", id).set("event", "shipment.created");
            // Same function the real webhook route uses: one fulfillment code path.
            GatewayEvents.Handled sent = GatewayEvents.handleFulfillmentEvent(webhook, req);
            if (sent.status() >= 300) return Reply.json(sent.status(), sent.body());
            return Reply.json(200, new JsObject().set("ok", true).set("webhook", webhook).set("webhookStatus", sent.status()));
        });
    }
}
