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

    private HttpResponse<String> login(String now) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login")).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").header("X-Demo-Now", now)
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"avery.alder@example.com\",\"password\":\"Demo!2026\"}"));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void outOfRangeDemoClockIsIgnored() throws Exception {
        for (String now : new String[] { "1e20", "300000000000000", "253402300800000" })
            assertEquals(200, login(now).statusCode(), now);
        assertEquals(200, login("4102444800000").statusCode());
    }
}
