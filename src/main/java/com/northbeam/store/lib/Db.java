package com.northbeam.store.lib;

import com.northbeam.store.lib.Models.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * In-memory data store, loaded from the shared contract/seed.json. Resets on restart or /demo/reset.
 *
 * One lock (Gate) guards all of it. The servlet container runs requests on many threads, so every route takes it (Db.sync)
 * around its whole decision: the check and the change that follows it are one step. Nothing slow runs inside it except the
 * password hash (a few milliseconds). The payment gateway call and the mock reset never do.
 * The lock is reentrant, so helpers may call sync again; the id counters and the session map take it too.
 */
public final class Db {
    private Db() {}

    public static <T> T sync(Supplier<T> work) { return Gate.run(work); }
    public static void sync(Runnable work) { Gate.run(work); }

    private static final class SeedUser {
        String id = "", name = "", email = "", phone = "", passwordHash = "", createdAt = "";
        JsObject address;
    }

    private static JsObject seed = new JsObject();
    private static final List<SeedUser> seedUsers = new ArrayList<>();

    public static final List<Product> products = new ArrayList<>();
    public static final List<User> users = new ArrayList<>();
    public static final List<Order> orders = new ArrayList<>();
    public static final List<JsObject> audit = new ArrayList<>(); // records of password changes, order status changes, promo and verification outcomes
    public static final Map<String, List<CartLine>> carts = new HashMap<>(); // userId -> lines
    public static final Map<String, String> promos = new HashMap<>(); // userId -> the promo code applied to that user's cart
    public static final Map<String, Double> promoUses = new HashMap<>(); // promo code -> orders made with it (for limited-use codes)
    public static final Map<String, PendingCode> verifications = new HashMap<>(); // userId -> the code in progress
    public static final Map<String, VerificationGuard> verificationGuards = new HashMap<>(); // userId -> failures and lockout, kept across new codes
    public static final List<PromoRule> promotions = new ArrayList<>();
    public static JsObject demoDefaults = new JsObject();
    private static long nextUser, nextOrder, nextCharge;

    private static final SecureRandom RANDOM = new SecureRandom();

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return (List<Object>) o; }
    private static JsObject obj(Object o) { return (JsObject) o; }
    private static double num(Object o) { return (Double) o; }

    /** Reads the seed once and hashes the seeded passwords once; /demo/reset reuses these hashes. */
    public static void load(Path contentRoot) {
        String text;
        try { text = Files.readString(contentRoot.resolve("contract").resolve("seed.json"), StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("cannot read contract/seed.json: run the store from the repo folder", e); }
        sync(() -> {
            seed = obj(JsonText.parse(text));
            seedUsers.clear();
            for (Object raw : list(seed.get("users"))) {
                JsObject u = obj(raw);
                SeedUser s = new SeedUser();
                s.id = (String) u.get("id");
                s.name = (String) u.get("name");
                s.email = (String) u.get("email");
                s.phone = (String) u.get("phone");
                s.passwordHash = hash((String) u.get("password"));
                s.createdAt = (String) u.get("createdAt");
                s.address = u.containsKey("address") ? obj(u.get("address")) : null;
                seedUsers.add(s);
            }
            promotions.clear();
            for (Object raw : list(seed.get("promotions"))) {
                JsObject p = obj(raw);
                PromoRule r = new PromoRule();
                r.code = (String) p.get("code");
                r.description = (String) p.get("description");
                r.type = (String) p.get("type");
                r.value = num(p.get("value"));
                r.minimum = p.containsKey("minimum") ? num(p.get("minimum")) : 0;
                r.expiresAt = p.containsKey("expiresAt") ? (String) p.get("expiresAt") : null;
                r.maxUses = p.containsKey("maxUses") ? (Double) p.get("maxUses") : null;
                promotions.add(r);
            }
            demoDefaults = obj(obj(seed.get("_meta")).get("demoDefaults"));
            reset();
        });
    }

    /**
     * Builds every collection again from the seed: new objects, never the old ones, so a request that still holds an
     * order from before a reset (a payment waiting on the gateway) cannot reach a new shopper's order through it.
     */
    public static void reset() {
        sync(() -> {
            products.clear();
            for (Object raw : list(seed.get("products"))) {
                JsObject p = obj(raw);
                Product product = new Product();
                product.id = (String) p.get("id");
                product.price = num(p.get("price"));
                product.raw = cloneObject(p);
                products.add(product);
            }
            users.clear();
            for (SeedUser s : seedUsers) {
                User u = new User();
                u.id = s.id; u.name = s.name; u.email = s.email; u.phone = s.phone; u.passwordHash = s.passwordHash; u.createdAt = s.createdAt;
                // A seeded user's one `address` is both the billing and the default shipping address.
                if (s.address != null) { u.billingAddress = cloneObject(s.address); u.shippingAddress = cloneObject(s.address); }
                users.add(u);
            }
            orders.clear();
            for (Object raw : list(seed.get("orders"))) orders.add(orderFromSeed(obj(raw)));
            carts.clear();
            audit.clear();
            promos.clear();
            promoUses.clear();
            verifications.clear();
            verificationGuards.clear();
            JsObject ids = obj(obj(seed.get("_meta")).get("nextIds"));
            nextUser = (long) num(ids.get("user"));
            nextOrder = (long) num(ids.get("order"));
            nextCharge = (long) num(ids.get("charge"));
        });
    }

    private static JsObject cloneObject(JsObject o) { return obj(JsonText.parse(JsonText.stringify(o))); }

    private static Order orderFromSeed(JsObject s) {
        Order o = new Order();
        o.id = (String) s.get("id");
        o.userId = (String) s.get("userId");
        o.total = num(s.get("total"));
        o.currency = (String) s.get("currency");
        o.shippingAddress = cloneObject(obj(s.get("shippingAddress")));
        o.billingAddress = cloneObject(obj(s.get("billingAddress")));
        o.status = (String) s.get("status");
        o.sessionId = (String) s.get("sessionId");
        o.createdAt = (String) s.get("createdAt");
        for (Object raw : list(s.get("items"))) {
            JsObject i = obj(raw);
            OrderItem item = new OrderItem();
            item.productId = (String) i.get("productId");
            item.sku = (String) i.get("sku");
            item.name = (String) i.get("name");
            item.price = num(i.get("price"));
            item.brand = (String) i.get("brand");
            item.category = (String) i.get("category");
            item.qty = num(i.get("qty"));
            o.items.add(item);
        }
        if (s.containsKey("card")) {
            OrderCard c = new OrderCard();
            c.bin = (String) obj(s.get("card")).get("bin");
            c.last4 = (String) obj(s.get("card")).get("last4");
            o.card = c;
        }
        if (s.containsKey("chargeId")) o.chargeId = (String) s.get("chargeId");
        if (s.containsKey("chargeback")) {
            JsObject b = obj(s.get("chargeback"));
            Chargeback cb = new Chargeback();
            cb.reason = Js.get(b, "reason");
            cb.status = Js.get(b, "status");
            cb.receivedAt = (String) b.get("receivedAt");
            o.chargeback = cb;
        }
        return o;
    }

    // Password hashing: PBKDF2-SHA256 with a random salt, kept as "salt:digest" in hex. It takes any text, including NUL
    // and very long passwords (nothing is cut off), and the compare takes the same time wherever the two differ.
    // Written out over HMAC so an empty password and raw bytes work (the JDK's key spec refuses an empty one).
    private static final int ITERATIONS = 10_000;

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            byte[] key = password.getBytes(StandardCharsets.UTF_8);
            if (key.length == 0) key = new byte[] { 0 }; // HMAC pads a key with zeros: one zero byte is the empty key
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] block = new byte[salt.length + 4];
            System.arraycopy(salt, 0, block, 0, salt.length);
            block[salt.length + 3] = 1; // the one 32-byte block
            byte[] u = mac.doFinal(block);
            byte[] t = u.clone();
            for (int n = 1; n < ITERATIONS; n++) {
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            return t;
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public static String hash(String password) {
        byte[] salt = new byte[8];
        RANDOM.nextBytes(salt);
        HexFormat hex = HexFormat.of();
        return hex.formatHex(salt) + ":" + hex.formatHex(pbkdf2(password, salt));
    }

    public static boolean verify(String password, String stored) {
        String[] parts = stored.split(":", -1);
        if (parts.length != 2) return false;
        HexFormat hex = HexFormat.of();
        byte[] salt = hex.parseHex(parts[0]);
        byte[] want = hex.parseHex(parts[1]);
        byte[] attempt = pbkdf2(password, salt);
        return want.length == 32 && MessageDigest.isEqual(attempt, want);
    }

    public static String nextUserId() { return sync(() -> "shopper_" + nextUser++); }
    public static String nextOrderId() { return sync(() -> "ORD-" + nextOrder++); }
    public static String nextChargeId() { return sync(() -> "ch_" + nextCharge++); }

    public static User findUserByEmail(Object email) {
        if (!(email instanceof String e)) return null;
        for (User u : users) if (u.email.equals(e)) return u;
        return null;
    }

    public static User findUser(String id) {
        if (id == null) return null;
        for (User u : users) if (u.id.equals(id)) return u;
        return null;
    }

    public static Order findOrder(Object id) {
        if (!(id instanceof String s)) return null;
        for (Order o : orders) if (o.id.equals(s)) return o;
        return null;
    }

    /**
     * chargeId arrives from a webhook body as any JSON value. `o.chargeId === chargeId` in JavaScript is also true when both
     * are undefined, so a notice with no chargeId finds the first order that has no charge yet.
     */
    public static Order findOrderByCharge(Object chargeId) {
        if (Js.isUndefined(chargeId)) { for (Order o : orders) if (o.chargeId == null) return o; return null; }
        if (chargeId instanceof String s) { for (Order o : orders) if (s.equals(o.chargeId)) return o; }
        return null;
    }
}
