package com.northbeam.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.northbeam.store.apis.ErrorHandlers;
import com.northbeam.store.lib.Db;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Review fixes, run against the store's real pipeline on a random loopback port with DEMO off.
 * "DEMO=1" is given to Spring's own environment (as `java -jar store.jar --DEMO=1` would) but not to the process
 * environment: the store has one reading of DEMO (Config.demo), and that one says off.
 */
@SpringBootTest(classes = StoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "DEMO=1")
class ReviewFixesTest {
    private static String savedDemo;

    @BeforeAll static void demoOff() { savedDemo = System.getProperty("DEMO"); System.clearProperty("DEMO"); }
    @AfterAll static void restore() { if (savedDemo != null) System.setProperty("DEMO", savedDemo); }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> call(String method, String path, String cookie, String type, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (cookie != null) b.header("Cookie", cookie);
        if (type != null) b.header("Content-Type", type);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login() throws Exception {
        HttpResponse<String> r = call("POST", "/api/login", null, "application/json", "{\"email\":\"avery.alder@example.com\",\"password\":\"Demo!2026\"}");
        assertEquals(200, r.statusCode());
        return r.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    // Finding 2: one reading of DEMO.
    @Test void springPropertyAloneDoesNotEnableDemoRoutes() throws Exception {
        HttpResponse<String> r = call("GET", "/demo/log", null, null, null);
        assertEquals(404, r.statusCode());
        HttpResponse<String> cors = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/products")).header("Origin", "http://localhost:4010").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(cors.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "no CORS without DEMO");
    }

    // Finding 3: a request that changes state never answers 415.
    @Test void removingTheCardWithOddPathSpellingNeverAnswers415() throws Exception {
        String cookie = login();
        HttpResponse<String> put = call("PUT", "/api/account/payment-method", cookie, "application/json", "{\"token\":\"tok_x\",\"cardBin\":\"424242\",\"cardLast4\":\"4242\"}");
        assertTrue(put.statusCode() == 200 || put.statusCode() == 400, "save answered " + put.statusCode());
        for (String path : new String[] { "/api/Account/payment-method/", "/API/ACCOUNT/PAYMENT-METHOD", "/api/account/payment-method/" }) {
            HttpResponse<String> r = call("DELETE", path, cookie, "text/plain", "x");
            assertEquals(200, r.statusCode(), path + " -> " + r.body());
        }
    }

    // Finding 4: GET /error is an ordinary unknown path.
    @Test void errorPathIsNotFound() throws Exception {
        for (String p : new String[] { "/error", "/ERROR", "/error/" }) {
            HttpResponse<String> r = call("GET", p, null, null, null);
            assertEquals(404, r.statusCode(), p);
            assertEquals("{\"error\":\"Not found\"}", r.body());
        }
    }

    // Finding 5: files whose name starts with a dot are not served.
    @Test void dotfilesAreNotServed() throws Exception {
        Path dir = Path.of("public"), dot = dir.resolve(".nb-review-test"), inner = dir.resolve("img").resolve(".nb-review-test2");
        Files.writeString(dot, "x");
        Files.writeString(inner, "x");
        try {
            for (String p : new String[] { "/.nb-review-test", "/img/.nb-review-test2", "/%2Enb-review-test" }) {
                HttpResponse<String> r = call("GET", p, null, null, null);
                assertEquals(404, r.statusCode(), p);
            }
        } finally { Files.deleteIfExists(dot); Files.deleteIfExists(inner); }
    }

    // Finding 6: characters Tomcat refuses by default are read as the other stores read them.
    @Test void rawBracketsAndPipesAreAccepted() throws Exception {
        for (String p : new String[] { "/?q={a}", "/?x=[1]", "/api/products?a=|b", "/api/products?a=`b", "/api/products?a=^b" })
            assertEquals(200, rawStatus(p), p);
        assertEquals(404, rawStatus("/a|b"));
        assertEquals(404, rawStatus("/a{b}"));
    }

    // The JDK client escapes these characters, so send them as the raw request line.
    private int rawStatus(String target) throws Exception {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            s.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            String line = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.ISO_8859_1)).readLine();
            return Integer.parseInt(line.split(" ")[1]);
        }
    }

    // Finding 7: an X-Demo-Now outside years 1 to 9999 is ignored (DEMO off here, so also ignored by design); under DEMO it is checked before any state changes.
    // (See DemoClockTest for the DEMO case.)

    // Finding 11: a broken connection is not a 500 with a stack trace.
    @Test void brokenConnectionIsQuiet() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured));
        try { new ErrorHandlers().other(new UncheckedIOException(new IOException("Broken pipe")), res); }
        finally { System.setErr(err); }
        assertEquals("", captured.toString());
        assertEquals("", res.getContentAsString());
        assertEquals(200, res.getStatus());
        assertFalse(Db.users.isEmpty());
        assertNull(res.getErrorMessage());
    }
}
