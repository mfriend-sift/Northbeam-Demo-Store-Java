package com.northbeam.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.northbeam.store.lib.Db;
import com.northbeam.store.lib.Gate;
import com.northbeam.store.lib.Js;
import com.northbeam.store.lib.JsObject;
import com.northbeam.store.lib.JsonText;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Lock probe (in process: the store's real servlet pipeline on a random loopback port, real HTTP requests).
 * Run it with tools/lock-probe/run.sh. It plays the check-then-change sections in parallel: three order saves against
 * a promo code that allows two uses, and ten wrong verification codes at once on one account.
 * It runs twice: with the store's real lock the answers must be right every round, and with the lock swapped for one
 * that does nothing some round must give wrong answers; if none does, the probe cannot see a missing lock and fails itself.
 */
@SpringBootTest(classes = StoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "DEMO=1")
class LockProbeTest {
    static { System.setProperty("DEMO", "1"); }

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String ADDR = "{\"name\":\"A\",\"address1\":\"1 St\",\"city\":\"C\",\"region\":\"R\",\"country\":\"US\",\"zipcode\":\"1\"}";

    private record Answer(int status, String body, String setCookie) {}

    private Answer call(String method, String path, String cookie, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (cookie != null) b.header("Cookie", cookie);
        if (json != null) b.header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(r.statusCode(), r.body(), r.headers().firstValue("Set-Cookie").orElse(""));
    }

    private String login(String email) throws Exception {
        Answer a = call("POST", "/api/login", null, "{\"email\":\"" + email + "\",\"password\":\"Demo!2026\"}");
        if (a.status() != 200) throw new IllegalStateException("login failed: " + a.status());
        return a.setCookie().split(";")[0];
    }

    private record Round(int carried, double uses, List<Integer> statuses, long fails) {
        boolean right() {
            return carried == 2 && uses == 2 && fails == 5
                && statuses.stream().filter(s -> s == 400).count() == 5 && statuses.stream().filter(s -> s == 429).count() == 5;
        }
    }

    private List<Answer> parallel(ExecutorService pool, List<java.util.concurrent.Callable<Answer>> jobs) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Answer>> futures = new ArrayList<>();
        for (var job : jobs) futures.add(pool.submit(() -> { go.await(); return job.call(); }));
        go.countDown();
        List<Answer> out = new ArrayList<>();
        for (Future<Answer> f : futures) {
            try { out.add(f.get(40, TimeUnit.SECONDS)); } catch (Exception e) { out.add(new Answer(-1, String.valueOf(e), "")); }
        }
        return out;
    }

    private Round round(ExecutorService pool) throws Exception {
        call("POST", "/demo/reset", null, null);
        String productId = Db.sync(() -> Db.products.get(0).id);
        List<String> cookies = new ArrayList<>();
        for (String email : List.of("avery.alder@example.com", "blake.brook@example.com", "casey.cedar@example.com")) {
            String c = login(email);
            cookies.add(c);
            if (call("POST", "/api/cart/items", c, "{\"productId\":\"" + productId + "\"}").status() != 200) throw new IllegalStateException("add failed");
            if (call("POST", "/api/cart/promo", c, "{\"code\":\"LIMITED25\"}").status() != 200) throw new IllegalStateException("promo failed");
        }
        // Three order saves at once: the code allows two uses.
        List<java.util.concurrent.Callable<Answer>> saves = new ArrayList<>();
        for (String c : cookies) saves.add(() -> call("POST", "/api/orders", c, "{\"shippingAddress\":" + ADDR + ",\"billingAddress\":" + ADDR + "}"));
        int carried = 0;
        for (Answer a : parallel(pool, saves)) {
            if (a.status() == 201 && JsonText.parse(a.body()) instanceof JsObject o && o.get("order") instanceof JsObject order && order.containsKey("promotion")) carried++;
        }
        double uses = Db.sync(() -> {
            Double v = Db.promoUses.get("LIMITED25");
            return v == null ? 0.0 : v;
        });
        // Ten wrong codes at once on one account: five are counted (400) and the lock shuts the rest out (429).
        String devon = login("devon.dale@example.com");
        if (call("POST", "/api/verification/start", devon, "{\"channel\":\"email\"}").status() != 200) throw new IllegalStateException("start failed");
        List<java.util.concurrent.Callable<Answer>> confirms = new ArrayList<>();
        for (int i = 0; i < 10; i++) confirms.add(() -> call("POST", "/api/verification/confirm", devon, "{\"code\":\"000000\"}"));
        List<Integer> statuses = new ArrayList<>();
        for (Answer a : parallel(pool, confirms)) statuses.add(a.status());
        Collections.sort(statuses);
        long fails = Db.sync(() -> Db.audit.stream().filter(a -> "verification".equals(a.get("kind")) && "failure".equals(a.get("status"))).count());
        return new Round(carried, uses, statuses, fails);
    }

    /** A lock that does nothing: what the store would have without its lock. */
    private static final class NoLock implements Lock {
        public void lock() {}
        public void lockInterruptibly() {}
        public boolean tryLock() { return true; }
        public boolean tryLock(long time, TimeUnit unit) { return true; }
        public void unlock() {}
        public Condition newCondition() { throw new UnsupportedOperationException(); }
    }

    @Test
    void realLockIsRightAndNoLockIsWrong() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            round(pool); // warm up on one pass (class loading, first requests), then measure
            boolean realOk = true;
            for (int i = 0; i < 5; i++) {
                Round r = round(pool);
                System.out.println("[real lock] round " + (i + 1) + ": LIMITED25 orders " + r.carried() + " (want 2), uses " + r.uses() + " (want 2), verification statuses " + r.statuses()
                    + " (want 5x400 5x429), failure records " + r.fails() + " (want 5): " + (r.right() ? "PASS" : "FAIL"));
                realOk &= r.right();
            }
            boolean detected = false;
            Gate.useLock(new NoLock());
            try {
                int setupFailures = 0;
                for (int i = 0; i < 40 && !detected; i++) {
                    Round r;
                    // Only a wrong answer counts as detection. A round that throws (a setup step failed) says nothing about the lock:
                    // print the cause and play the round again.
                    try { r = round(pool); }
                    catch (Exception e) {
                        setupFailures++;
                        System.out.println("[no lock] round " + (i + 1) + ": setup failed, not a detection: " + e);
                        for (Throwable c = e.getCause(); c != null; c = c.getCause()) System.out.println("[no lock]   caused by " + c);
                        continue;
                    }
                    detected = !r.right();
                    System.out.println("[no lock] round " + (i + 1) + ": LIMITED25 orders " + r.carried() + ", uses " + r.uses() + ", statuses " + r.statuses() + ", failure records " + r.fails()
                        + (detected ? ": race detected (good)" : ": no difference yet"));
                }
                if (!detected) System.out.println("[no lock] no wrong answer in 40 rounds (" + setupFailures + " rounds failed in setup)");
            } finally {
                Gate.useLock(new java.util.concurrent.locks.ReentrantLock());
            }
            assertTrue(realOk, "with the store's lock the answers must be right every round");
            assertTrue(detected, "with no lock some round must go wrong; otherwise the probe cannot see a missing lock");
        } finally {
            pool.shutdownNow();
        }
    }
}
