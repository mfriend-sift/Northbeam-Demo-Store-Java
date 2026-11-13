package com.northbeam.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Finding 7: under DEMO=1 an X-Demo-Now outside years 1 to 9999 is ignored before any state changes, never a 500 after one. */
@SpringBootTest(classes = StoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoClockTest {
    private static String savedDemo;

    @BeforeAll static void demoOn() { savedDemo = System.getProperty("DEMO"); System.setProperty("DEMO", "1"); }
    @AfterAll static void restore() { if (savedDemo == null) System.clearProperty("DEMO"); else System.setProperty("DEMO", savedDemo); }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> send(String method, String path, String cookie, String now, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (now != null) b.header("X-Demo-Now", now);
        if (cookie != null) b.header("Cookie", cookie);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void outOfRangeDemoClockOnAChangeIsRefusedBeforeAnyStateChanges() throws Exception {
        String cookie = send("GET", "/api/products", null, null, null).headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        for (String now : new String[] { "1e20", "300000000000000", "253402300800000" }) {
            HttpResponse<String> r = send("POST", "/api/login", cookie, now, "{\"email\":\"avery.alder@example.com\",\"password\":\"Demo!2026\"}");
            assertEquals(500, r.statusCode(), now);
            // the failed request signed nobody in
            assertEquals(401, send("GET", "/api/account", cookie, null, null).statusCode(), now);
        }
        HttpResponse<String> ok = send("POST", "/api/login", cookie, "4102444800000", "{\"email\":\"avery.alder@example.com\",\"password\":\"Demo!2026\"}");
        assertEquals(200, ok.statusCode());
    }
}
