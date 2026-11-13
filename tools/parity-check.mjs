#!/usr/bin/env node
// Differential check: sends the same requests to two running stores and compares the answers.
// Usage: node tools/parity-check.mjs --a http://127.0.0.1:<port> --b http://127.0.0.1:<port> [--only <text>] [--verbose] [--fuzz N [--seed S]] [--full]
//   --full prints whole bodies for a difference. --fuzz N adds a script of N seeded random requests (DEMO=1 on both stores); --only fuzz runs just that script.
//   --a is this store, --b the reference (the reference store). Both must run the same way
//   (both with DEMO=1 or both without). Every script starts from POST /demo/reset when the stores answer it.
//   The comparison is the status, the body (parsed, keys sorted) and a few headers, with session ids and timestamps masked.
//   It resets and writes to both stores, so the addresses are required. Needs Node 18+, no dependencies.
import http from "node:http";
import fs from "node:fs";

const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const A = opt("--a"), B = opt("--b"), ONLY = opt("--only"), VERBOSE = args.includes("--verbose"), FULL = args.includes("--full");
if (!A || !B) { console.error("usage: node tools/parity-check.mjs --a http://127.0.0.1:<port> --b http://127.0.0.1:<port> [--only <text>] [--verbose] [--fuzz N [--seed S]]\n(resets and writes to both stores)"); process.exit(2); }

const HEADERS = ["content-type", "set-cookie", "access-control-allow-origin", "access-control-allow-credentials", "access-control-allow-methods", "access-control-allow-headers", "vary", "location", "allow"];

function send(base, req, jar) {
  return new Promise((resolve) => {
    const u = new URL(base);
    const headers = { ...(req.headers || {}) };
    let body = req.body;
    if (body !== undefined && typeof body !== "string" && !Buffer.isBuffer(body)) body = JSON.stringify(body);
    if (body !== undefined && !Object.keys(headers).some((h) => h.toLowerCase() === "content-type") && !req.noType) headers["Content-Type"] = "application/json";
    if (jar.cookie && !Object.keys(headers).some((h) => h.toLowerCase() === "cookie")) headers.Cookie = jar.cookie;
    if (body !== undefined && !req.chunked && !Object.keys(headers).some((h) => h.toLowerCase() === "content-length")) headers["Content-Length"] = Buffer.byteLength(body);
    if (req.chunked) headers["Transfer-Encoding"] = "chunked";
    const r = http.request({ host: u.hostname, port: u.port, method: req.method || "GET", path: req.path, headers, agent: false, setHost: true }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        const set = res.headers["set-cookie"];
        const sid = set && /nb_sid=([^;]+)/.exec(set.join(";"));
        if (sid) jar.cookie = `nb_sid=${sid[1]}`;
        resolve({ status: res.statusCode, headers: res.headers, text });
      });
    });
    r.on("error", (e) => resolve({ status: 0, headers: {}, text: "ERR " + e.code }));
    r.setTimeout(15000, () => { r.destroy(); });
    if (req.chunked && body !== undefined) { const b = Buffer.from(body); for (let i = 0; i < b.length; i += 7) r.write(b.subarray(i, i + 7)); r.end(); }
    else if (body !== undefined) r.end(body);
    else r.end();
  });
}

const mask = (s) => s.replace(/"backend":"(dotnet|node|java)"/g, '"backend":"X"').replace(/sess_[0-9a-f]{16}/g, "sess_X").replace(/\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z/g, (m) => (m.startsWith("2026-10-0") || m.startsWith("2025") || m.startsWith("2026-0") || m.startsWith("2026-09") ? m : "<ts>"));
const sortKeys = (v) => Array.isArray(v) ? v.map(sortKeys) : v && typeof v === "object" ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, sortKeys(v[k])])) : v;

function normalize(res) {
  let body = res.text;
  try { body = JSON.stringify(sortKeys(JSON.parse(res.text))); } catch { /* not JSON: compared as text */ }
  const headers = {};
  for (const h of HEADERS) if (res.headers[h] !== undefined) headers[h] = mask([].concat(res.headers[h]).join("; ")).replace(/(; )?(path=\/|samesite=lax)/gi, (m) => m.toLowerCase());
  if (headers["content-type"]) headers["content-type"] = headers["content-type"].toLowerCase().replace(/;\s*charset=utf-8/, "; charset=utf-8");
  return { status: res.status, headers, body: mask(!FULL && body.length > 600 ? body.slice(0, 600) + `...(${body.length})` : body) };
}

const scripts = [];
const script = (name, steps, opts = {}) => scripts.push({ name, steps, ...opts });
const addr = (name = "Parity Shopper") => ({ name, address1: "100 Market St", city: "San Francisco", region: "CA", country: "US", zipcode: "94105" });
const visa = { token: "tok_visa", cardBin: "411111", cardLast4: "1111" };
const signupBody = (tag, extra = {}) => ({ name: "Parity Shopper", email: `${tag}@example.com`, phone: "415-555-0199", password: "Demo!2026", ...extra });
const get = (path, extra = {}) => ({ method: "GET", path, ...extra });
const post = (path, body, extra = {}) => ({ method: "POST", path, body, ...extra });
const put = (path, body, extra = {}) => ({ method: "PUT", path, body, ...extra });
const del = (path, extra = {}) => ({ method: "DELETE", path, ...extra });
const login = (email = "avery.alder@example.com", password = "Demo!2026") => post("/api/login", { email, password });
const bigNest = (n) => "[".repeat(n) + "]".repeat(n);

// ---- routing, paths and query strings ----
script("routes: reads and path forms", [
  get("/api/products"), get("/api/products/"), get("/API/PRODUCTS"), { method: "HEAD", path: "/api/products" }, get("/api/products.json"),
  get("/api/products?q=%"), get("/api/products?x=%zz&y=%E0%A4%A"), get("/api/products?since=1&since=2"), get("/api/config"), get("/api/config/"),
  get("/api/nothing"), post("/api/products", {}), put("/api/products", {}), { method: "PATCH", path: "/api/cart", body: {} }, get("//api/products"), get("/api//products"),
  get("/api/pro%zzducts"), get("/%zz"), get("/api/products", { headers: { Accept: "a/b/c" } }), get("/api/products", { headers: { Accept: "text/html" } }),
  get("/api/cart"), get("/api/cart/"), get("/api/account"), get("/api/orders"), get("/api/signup"), get("/api/login"), get("/webhooks/gateway"), get("/webhooks"), get("/api"), get("/api/"),
  // raw characters some servers refuse before routing (| [ ] ^ { } and the backtick), a path and a query, and the plain /error path
  get("/?q={a}"), get("/?x=[1]"), get("/api/products?a=|b"), get("/api/products?a=`b"), get("/api/products?a=^b"), get("/a|b"), get("/a{b}"), get("/error"), get("/ERROR"), get("/error/"),
  get("/.DS_Store"), get("/.git/config"), get("/img/.x"), get("/%2eDS_Store"),
]);
script("static files and the page", [
  get("/"), get("/index.html"), get("/app.js"), get("/styles.css"), get("/demo-drawer.js"), get("/img/wool-beanie.jpg"), get("/img/CREDITS.md"), get("/img/"), get("/index.html/"), get("/app.js/"), get("/APP.JS"), get("/img/%2e%2e/app.js"), get("/img/../app.js"), get("/img/%zz.jpg"), { method: "HEAD", path: "/app.js" }, { method: "POST", path: "/app.js", body: {} }, get("/app.js", { headers: { Range: "bytes=0-9" } }), get("/img/missing.jpg"), get("/nonexistent.png"), get("/.env"), get("/contract/seed.json"), get("/tools/run.sh"), get("/public/app.js"),
  get("/", { headers: { Cookie: "nb_sid=bogus" } }), get("/", { headers: { Cookie: "nb_sid=" } }), get("/", { headers: { Cookie: "other=1; nb_sid=sess_0000000000000000" } }),
]);
script("param routes: bad escapes and odd ids", [
  del("/api/cart/items/%E0%A4%A"), del("/api/cart/items/%zz"), del("/api/cart/items/p%201"), del("/api/cart/items/p1.json"), del("/api/cart/items/a.b"), del("/api/cart/items/"),
  put("/api/orders/%zz/shipping", {}), post("/api/orders/%E0%A4%A/pay", {}), post("/api/orders/ORD.1/pay", {}), post("/api/orders/ORD-1/cancel", {}), post("/api/orders/%zz/cancel", {}),
  post("/api/orders/A.B/pay", {}), post("/api/orders//pay", {}), post("/demo/orders/%zz/ship", {}), post("/demo/orders/ORD-20041/ship", {}),
  login(), del("/api/cart/items/%E0%A4%A"), del("/api/cart/items/p%2F1"), del("/api/cart/items/p1%25"), put("/api/orders/%E0%A4%A/shipping", { shippingMethod: "standard" }),
  post("/api/orders/ORD-20001/pay", {}), post("/api/orders/%4FRD-20001/pay", {}),
]);
script("methods and trailing slashes", [
  { method: "OPTIONS", path: "/api/products", headers: { Origin: "http://localhost:4010", "Access-Control-Request-Method": "POST" } },
  get("/api/products", { headers: { Origin: "http://localhost:4010" } }), get("/api/products", { headers: { Origin: "http://evil.example" } }),
  post("/api/signup/", signupBody("slash1")), post("/api/login/", { email: "avery.alder@example.com", password: "Demo!2026" }), post("/api/logout/", {}),
  put("/api/account/payment-method/", visa), del("/api/account/payment-method/"), get("/api/account/"), get("/ApI/Account"),
]);

// ---- request bodies ----
script("body: types, size and shape", [
  post("/api/login", "email=a&password=b", { headers: { "Content-Type": "application/x-www-form-urlencoded" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": "text/plain" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": "application/json; charset=utf-8" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": "APPLICATION/JSON" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": "application/vnd.api+json" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": "application/json;" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": "application/json; charset" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": "application/json ; charset=latin1" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": 'application/json; charset="utf-8"' } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { headers: { "Content-Type": "application/json; a=b; c" } }),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"}', { noType: true }),
  { method: "POST", path: "/api/login", headers: {} }, { method: "POST", path: "/api/logout", headers: { "Content-Length": "0" } },
  post("/api/login", "{ not json"), post("/api/login", ""), post("/api/login", "[]"), post("/api/login", "null"), post("/api/login", '"text"'), post("/api/login", "123"),
  post("/api/login", "\ufeff" + '{"email":"avery.alder@example.com","password":"Demo!2026"}'),
  post("/api/login", '{"email":"nobody@example.com","email":"avery.alder@example.com","password":"Demo!2026"}'),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026","x":' + bigNest(50000) + "}"),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026","x":' + bigNest(2000) + "}"),
  post("/api/login", '{"email":"avery.alder@example.com","password":' + bigNest(3000) + "}"), post("/api/login", '{"email":"avery.alder@example.com","password":' + bigNest(2500) + "}"),
  post("/api/login", '{"email":' + bigNest(20000) + ',"password":"x"}'), post("/api/signup", '{"name":' + bigNest(30000) + "}"), post("/api/cart/promo", '{"code":' + bigNest(30000) + "}"),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026"} trailing'),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026",}'),
  post("/api/login", "{'email':'a'}"), post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026","n":1e400}'),
  post("/api/login", '{"email":"avery.alder@example.com","password":"Demo!2026","s":"\\ud800"}'),
  post("/api/login", { email: "avery.alder@example.com", password: "Demo!2026" }, { chunked: true }),
  post("/api/login", " ".repeat(102400 - 2) + "{}"), post("/api/login", " ".repeat(102400 - 1) + "{}"), post("/api/login", " ".repeat(102400) + "{}"),
  get("/api/products", { body: " ".repeat(102401), headers: { "Content-Type": "application/json" } }),
  get("/api/products", { body: "x".repeat(200000), headers: { "Content-Type": "text/plain" } }),
  post("/api/no-such-route", " ".repeat(120000), {}), post("/api/no-such-route", "{ bad"), post("/api/orders", "{ bad"),
  post("/webhooks/gateway", "{ bad"), post("/webhooks/gateway", "[]"), post("/webhooks/gateway", "x", { headers: { "Content-Type": "text/plain" } }),
  post("/api/login", Buffer.from([0x7b, 0x22, 0x65, 0x6d, 0x22, 0x3a, 0x22, 0xff, 0xfe, 0x22, 0x7d])),
]);

// ---- accounts ----
script("signup: every rule", [
  post("/api/signup", {}), post("/api/signup", signupBody("s1", { name: "" })), post("/api/signup", signupBody("s1", { name: "\u00a0" })), post("/api/signup", signupBody("s1", { name: "\u200b" })),
  post("/api/signup", signupBody("s1", { name: "x".repeat(201) })), post("/api/signup", signupBody("s1", { name: "x".repeat(200) })),
  post("/api/signup", signupBody("s2", { name: "\ud83d\ude00".repeat(200) })), post("/api/signup", signupBody("s3", { name: "\ud83d\ude00".repeat(201) })),
  post("/api/signup", signupBody("s4", { password: 12345 })), post("/api/signup", signupBody("s4", { email: ["a@example.com"] })), post("/api/signup", signupBody("s4", { phone: 4155550199 })),
  post("/api/signup", signupBody("s4", { email: "not-an-email" })), post("/api/signup", signupBody("s4", { email: "a b@example.com" })), post("/api/signup", signupBody("s5", { email: "a\u0085b@example.com" })),
  post("/api/signup", signupBody("s6", { email: "a\u2003b@example.com" })), post("/api/signup", signupBody("s7", { email: "a@b@example.com" })), post("/api/signup", signupBody("s8", { email: "a@b.c\n" })),
  post("/api/signup", signupBody("s9", { phone: "123" })), post("/api/signup", signupBody("s9", { phone: "+1 (415) 555-0123" })), post("/api/signup", signupBody("s10", { phone: "1234567890123456" })),
  post("/api/signup", signupBody("s11", { phone: "123456789012345" })), post("/api/signup", signupBody("s12", { phone: "\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660" })),
  post("/api/signup", signupBody("s13", { billingAddress: addr() })), post("/api/signup", signupBody("s14", { shippingAddress: { ...addr(), zipcode: "" } })), post("/api/signup", signupBody("s15", { billingAddress: null })),
  post("/api/signup", signupBody("s16", { billingAddress: { ...addr(), extra: { n: 1.0 } } })), get("/api/account"), post("/api/signup", signupBody("s13")), post("/api/signup", signupBody("s17")),
  post("/api/logout", {}), post("/api/logout", {}), login("s13@example.com"), get("/api/account"),
]);
script("passwords: odd characters and lengths", [
  post("/api/signup", signupBody("pw1", { password: "pass\u0000word" })), post("/api/logout", {}), login("pw1@example.com", "pass\u0000word"), post("/api/logout", {}), login("pw1@example.com", "pass"), login("pw1@example.com", "pass\u0000"),
  post("/api/signup", signupBody("pw2", { password: "a".repeat(200) + "X" })), post("/api/logout", {}), login("pw2@example.com", "a".repeat(200) + "Y"), login("pw2@example.com", "a".repeat(200) + "X"), post("/api/logout", {}),
  post("/api/signup", signupBody("pw3", { password: "a".repeat(80) })), post("/api/logout", {}), login("pw3@example.com", "a".repeat(72) + "zzz"), login("pw3@example.com", "a".repeat(80)), post("/api/logout", {}),
  post("/api/signup", signupBody("pw4", { password: "\ud83d\ude00\u00e9" })), post("/api/logout", {}), login("pw4@example.com", "\ud83d\ude00\u00e9"), post("/api/logout", {}),
  login("pw1@example.com", 0), login("pw1@example.com", false), login("pw1@example.com", null), login("pw1@example.com", ["x"]), login(["avery.alder@example.com"], "Demo!2026"), login({ a: 1 }, "x"), login(undefined, undefined),
  post("/api/signup", signupBody("pw5", { password: "12345" })), post("/api/logout", {}), login("pw5@example.com", 12345), post("/api/logout", {}), login("pw5@example.com", [12345]),
]);
script("login, logout, account", [
  get("/api/account"), login("nobody@example.com", "x"), login("avery.alder@example.com", "wrong"), login("AVERY.ALDER@example.com"), login(), get("/api/account"), login("blake.brook@example.com"), get("/api/account"),
  post("/api/logout", {}), post("/api/logout", {}), get("/api/account"), get("/api/cart"), get("/api/orders"),
  login(), put("/api/account", {}), put("/api/account", { name: "" }), put("/api/account", { name: 0 }), put("/api/account", { name: false }), put("/api/account", { name: null }), put("/api/account", { name: null, email: "" }),
  put("/api/account", { name: "\u200b" }), put("/api/account", { name: ["x"] }), put("/api/account", { email: 5 }), put("/api/account", { phone: [] }), put("/api/account", { email: "bad" }), put("/api/account", { phone: "12" }),
  put("/api/account", { email: "blake.brook@example.com" }), put("/api/account", { email: "avery.alder@example.com" }), put("/api/account", { phone: "206-555-0100" }), put("/api/account", { email: "new.avery@example.com", phone: "206-555-0101", name: "Avery Renamed" }),
  put("/api/account", { billingAddress: addr() }), put("/api/account", { shippingAddress: { ...addr(), extra: 1 } }), put("/api/account", { billingAddress: null }), put("/api/account", { billingAddress: "x" }), put("/api/account", { billingAddress: [] }),
  put("/api/account", { shippingAddress: { ...addr(), city: "\u3000" } }), get("/api/account"), put("/api/account", "[1]"), put("/api/account", "null"), put("/api/account", "{ bad"),
  put("/api/account", { name: "Typed", email: null, phone: null }), get("/api/account"),
]);
script("card on file and password change", [
  put("/api/account/payment-method", visa), login(), put("/api/account/payment-method", {}), put("/api/account/payment-method", visa), get("/api/account"),
  put("/api/account/payment-method", { ...visa, type: "debit" }), put("/api/account/payment-method", { ...visa, type: null }), put("/api/account/payment-method", { ...visa, type: "" }), put("/api/account/payment-method", { ...visa, type: 5 }),
  put("/api/account/payment-method", { ...visa, cardBin: "41111" }), put("/api/account/payment-method", { ...visa, cardBin: "41111111" }), put("/api/account/payment-method", { ...visa, cardBin: 411111 }), put("/api/account/payment-method", { ...visa, cardLast4: "111" }),
  put("/api/account/payment-method", { ...visa, token: "\u00a0" }), put("/api/account/payment-method", { ...visa, token: "t".repeat(201) }), put("/api/account/payment-method", { ...visa, extra: 1 }), put("/api/account/payment-method", "[]"), put("/api/account/payment-method", "null"),
  put("/api/account/payment-method", visa, { headers: { "Content-Type": "text/plain" } }), get("/api/account"), del("/api/account/payment-method"), del("/api/account/payment-method"), get("/api/account"),
  del("/api/account/payment-method", { body: "{}", headers: { "Content-Type": "text/plain" } }),
  put("/api/account/password", {}), put("/api/account/password", { currentPassword: "x" }), put("/api/account/password", { currentPassword: "x", newPassword: 5 }), put("/api/account/password", { currentPassword: "x", newPassword: "New!2026" }),
  put("/api/account/password", { currentPassword: "Demo!2026", newPassword: "New!2026" }), get("/api/account"), post("/api/logout", {}), login("avery.alder@example.com", "Demo!2026"), login("avery.alder@example.com", "New!2026"),
  put("/api/account/password", { currentPassword: "New!2026", newPassword: "pass\u0000word" }), post("/api/logout", {}), login("avery.alder@example.com", "pass\u0000word"),
]);

// ---- cart ----
script("cart: quantities and removal", [
  post("/api/cart/items", { productId: "p1" }), login(), get("/api/cart"), post("/api/cart/items", { productId: "p1" }), post("/api/cart/items", { productId: "p1", qty: 2 }), post("/api/cart/items", { productId: "nope" }),
  post("/api/cart/items", {}), post("/api/cart/items", { productId: 1 }), post("/api/cart/items", { productId: ["p1"] }), post("/api/cart/items", { productId: "p2", qty: 0 }), post("/api/cart/items", { productId: "p2", qty: -1 }),
  post("/api/cart/items", { productId: "p2", qty: 1.5 }), post("/api/cart/items", { productId: "p2", qty: "2" }), post("/api/cart/items", { productId: "p2", qty: true }), post("/api/cart/items", { productId: "p2", qty: null }),
  post("/api/cart/items", { productId: "p2", qty: 1e21 }), post("/api/cart/items", '{"productId":"p3","qty":12345678901234567890}'), post("/api/cart/items", '{"productId":"p3","qty":1e300}'), post("/api/cart/items", '{"productId":"p3","qty":1e400}'),
  post("/api/cart/items", '{"productId":"p3","qty":1.0}'), post("/api/cart/items", '{"productId":"p4","qty":9007199254740993}'), get("/api/cart"),
  post("/api/orders", { shippingAddress: addr(), billingAddress: addr() }), get("/api/orders"),
  del("/api/cart/items/p1"), del("/api/cart/items/p1"), del("/api/cart/items/zzz"), get("/api/cart"), del("/api/cart/items/p2"), del("/api/cart/items/p3"), del("/api/cart/items/p4"), get("/api/cart"),
  post("/api/orders", { shippingAddress: addr(), billingAddress: addr() }),
]);
script("promo: codes, whitespace and case", [
  post("/api/cart/promo", { code: "SAVE5" }), login(), post("/api/cart/promo", { code: "SAVE5" }), post("/api/cart/items", { productId: "p1" }), post("/api/cart/promo", {}), post("/api/cart/promo", { code: "" }), post("/api/cart/promo", { code: "   " }),
  post("/api/cart/promo", { code: 5 }), post("/api/cart/promo", { code: null }), post("/api/cart/promo", { code: ["WELCOME10"] }), post("/api/cart/promo", { code: "nope" }), post("/api/cart/promo", { code: "EXPIRED20" }),
  post("/api/cart/promo", { code: "SAVE5" }), post("/api/cart/promo", { code: "WELCOME10\u0085" }), post("/api/cart/promo", { code: "\u00a0welcome10\u00a0" }), post("/api/cart/promo", { code: "\ufeffWELCOME10" }), post("/api/cart/promo", { code: "\u200bWELCOME10" }),
  post("/api/cart/promo", { code: "wel\u0000come10" }), post("/api/cart/promo", { code: "\ud800" }), post("/api/cart/promo", { code: "W".repeat(65) }), post("/api/cart/promo", { code: "W".repeat(64) }), post("/api/cart/promo", { code: "\u00dfx" }),
  post("/api/cart/promo", { code: "\u017fave5" }), post("/api/cart/promo", { code: " welcome10 " }), post("/api/cart/promo", { code: "WELCOME10" }), post("/api/cart/promo", { code: "SAVE5" }), get("/api/cart"),
  post("/api/cart/items", { productId: "p5", qty: 2 }), get("/api/cart"), del("/api/cart/promo"), del("/api/cart/promo"), del("/api/cart/promo", { body: "{}", headers: { "Content-Type": "text/plain" } }), del("/api/cart/promo", { body: "{ bad" }),
  post("/api/cart/promo", { code: "SAVE5" }), post("/api/cart/promo", { code: "LIMITED25" }), del("/api/cart/promo"), post("/api/cart/promo", { code: "limited25" }),
  post("/api/orders", { shippingAddress: addr(), billingAddress: addr() }), get("/api/cart"), post("/api/cart/items", { productId: "p1" }), post("/api/cart/promo", { code: "LIMITED25" }),
  post("/api/cart/promo", { code: "{\"toString\":1}" }), post("/api/cart/promo", { code: "constructor" }), post("/api/cart/promo", { code: "__proto__" }),
]);

// ---- orders ----
script("orders: save, shipping, pay, cancel", [
  post("/api/orders", { shippingAddress: addr(), billingAddress: addr() }), login("devon.dale@example.com"), post("/api/orders", { shippingAddress: addr(), billingAddress: addr() }), post("/api/cart/items", { productId: "p1", qty: 2 }),
  post("/api/orders", {}), post("/api/orders", { shippingAddress: addr() }), post("/api/orders", { shippingAddress: addr(), billingAddress: { ...addr(), city: " " } }), post("/api/orders", { shippingAddress: addr(), billingAddress: addr(), shippingMethod: "fast" }),
  post("/api/orders", { shippingAddress: addr(), billingAddress: addr(), shippingMethod: null }), post("/api/orders", { shippingAddress: addr(), billingAddress: addr(), shippingMethod: ["standard"] }),
  post("/api/orders", { shippingAddress: [], billingAddress: addr() }), post("/api/orders", { shippingAddress: "x", billingAddress: addr() }),
  post("/api/orders", '{"shippingAddress":{"name":"A","address1":"B","city":"C","region":"D","country":"E","zipcode":"F","extra":{"n":1.0,"big":12345678901234567890,"arr":[1,2.50,null,true],"nested":{"a":{"b":{}}}},"extra2":1e21,"extra3":1e-7},"billingAddress":{"name":"A","address1":"B","city":"C","region":"D","country":"E","zipcode":"F"},"shippingMethod":"expedited"}'),
  get("/api/orders"), put("/api/orders/ORD-20041/shipping", {}), put("/api/orders/ORD-20041/shipping", { shippingMethod: "overnight" }), put("/api/orders/ORD-20041/shipping", { shippingAddress: { ...addr(), zipcode: "" } }),
  put("/api/orders/ORD-20041/shipping", { shippingMethod: "standard" }), put("/api/orders/ORD-20041/shipping", { shippingAddress: { ...addr("Moved"), more: [1] } }), put("/api/orders/ORD-99999/shipping", {}), put("/api/orders/ORD-20001/shipping", { shippingMethod: "standard" }),
  put("/api/orders/ORD-20041/shipping", "{ bad"), put("/api/orders/ORD-20041/shipping", "[1]"), get("/api/orders"),
  post("/api/orders/ORD-20041/pay", {}), post("/api/orders/ORD-20041/pay", { payment: null }), post("/api/orders/ORD-20041/pay", { payment: [] }), post("/api/orders/ORD-20041/pay", { payment: { ...visa, cardBin: "1" } }),
  post("/api/orders/ORD-20041/pay", "null"), post("/api/orders/ORD-20041/pay", "[]"), post("/api/orders/ORD-20041/pay", '"x"'), post("/api/orders/ORD-20041/pay", "{ bad"), post("/api/orders/ORD-20041/pay", "x", { headers: { "Content-Type": "text/plain" } }),
  post("/api/orders/ORD-20041/pay", { payment: { token: "tok_declined", cardBin: "411111", cardLast4: "1111" } }), get("/api/orders"), post("/api/orders/ORD-20041/pay", { payment: visa }), post("/api/orders/ORD-20041/pay", { payment: visa }),
  put("/api/orders/ORD-20041/shipping", { shippingMethod: "expedited" }), post("/api/orders/ORD-20041/cancel", {}), post("/api/orders/ORD-20041/cancel", {}), put("/api/orders/ORD-20041/shipping", { shippingMethod: "expedited" }), get("/api/orders"),
  post("/api/orders/ORD-20001/cancel", {}), post("/api/orders/ORD-20041/pay", { payment: visa }), post("/api/orders/ORD-20042/pay", { payment: visa }),
]);
script("pay with the saved card; ids and orders of other shoppers", [
  login("sage.stone@example.com"), post("/api/cart/items", { productId: "p6" }), post("/api/orders", { shippingAddress: addr(), billingAddress: addr() }), post("/api/orders/ORD-20041/pay", {}), put("/api/account/payment-method", { token: "tok_declined", cardBin: "555555", cardLast4: "4444" }),
  post("/api/orders/ORD-20041/pay", {}), get("/api/orders"), put("/api/account/payment-method", visa), post("/api/orders/ORD-20041/pay", {}), get("/api/orders"), post("/api/logout", {}), login("taylor.thorne@example.com"),
  post("/api/orders/ORD-20041/pay", {}), post("/api/orders/ORD-20041/cancel", {}), put("/api/orders/ORD-20041/shipping", { shippingMethod: "standard" }), get("/api/orders"),
  post("/api/orders/ORD-20001/pay", {}), post("/api/orders/ORD-20001/cancel", {}), get("/api/orders"),
]);
script("webhooks: shipment and dispute notices", [
  post("/webhooks/fulfillment", {}), post("/webhooks/fulfillment", { event: "shipment.created" }), post("/webhooks/fulfillment", { event: "shipment.created", orderId: "ORD-1" }), post("/webhooks/fulfillment", { event: "other", orderId: "ORD-1" }),
  post("/webhooks/fulfillment", { event: 5 }), post("/webhooks/fulfillment", "[]"), post("/webhooks/fulfillment", "null"),
  post("/webhooks/gateway", {}), post("/webhooks/gateway", { type: "other" }), post("/webhooks/gateway", { type: 5 }), post("/webhooks/gateway", { type: "charge.dispute.created" }), post("/webhooks/gateway", { type: "charge.dispute.created", data: null }),
  post("/webhooks/gateway", { type: "charge.dispute.created", data: "x" }), post("/webhooks/gateway", { type: "charge.dispute.created", data: [] }), post("/webhooks/gateway", { type: "charge.dispute.created", data: { chargeId: "ch_1" } }),
  post("/webhooks/gateway", { type: "charge.dispute.created", data: { chargeId: null } }), post("/webhooks/gateway", { type: "charge.dispute.created", data: { chargeId: 50001 } }),
  login("umi.upton@example.com"), post("/api/cart/items", { productId: "p1" }), post("/api/orders", { shippingAddress: addr(), billingAddress: addr() }),
  post("/webhooks/gateway", { type: "charge.dispute.created", data: {} }), post("/webhooks/gateway", { type: "charge.dispute.created", data: { reason: "x" } }),
  post("/api/orders/ORD-20041/pay", { payment: { token: "tok_declined", cardBin: "411111", cardLast4: "1111" } }), post("/webhooks/gateway", { type: "charge.dispute.created", data: { chargeId: "ch_50041" } }),
  post("/api/orders/ORD-20041/pay", { payment: visa }), post("/webhooks/gateway", { type: "charge.dispute.created", data: { chargeId: "ch_50042", reason: { a: 1 }, status: null } }), get("/api/orders"),
  post("/webhooks/gateway", { type: "charge.dispute.closed", data: { chargeId: "ch_50042", reason: "duplicate", status: "won" } }), get("/api/orders"),
  post("/webhooks/gateway", { type: "charge.dispute.", data: { chargeId: "ch_50002" } }), post("/webhooks/gateway", { type: "charge.dispute.created", data: { chargeId: "ch_50002", reason: 7 } }), get("/api/orders"),
  post("/webhooks/fulfillment", { event: "shipment.created", orderId: "ORD-20041" }), post("/webhooks/fulfillment", { event: "shipment.created", orderId: "ORD-20002" }), post("/api/orders/ORD-20041/cancel", {}),
]);

// ---- verification ----
const T0 = 1790000000000; // a fixed demo clock
const at = (ms) => ({ headers: { "X-Demo-Now": String(ms) } });
script("verification: rules, lockout and clock", [
  post("/api/verification/start", { channel: "email" }), login("harper.heath@example.com"), post("/api/verification/start", {}), post("/api/verification/start", { channel: "fax" }), post("/api/verification/start", { channel: ["email"] }),
  post("/api/verification/confirm", { code: "123456" }), post("/api/verification/confirm", {}), post("/api/verification/confirm", { code: "  " }), post("/api/verification/confirm", { code: 123456 }),
  post("/api/verification/start", { channel: "email" }, at(T0)), post("/api/verification/start", { channel: "email" }, at(T0 + 1000)), post("/api/verification/start", { channel: "sms" }, at(T0 + 31000)),
  post("/api/verification/confirm", { code: "000000" }, at(T0 + 32000)), post("/api/verification/confirm", { code: "00000" }, at(T0 + 33000)), 
  post("/api/verification/confirm", { code: "000000" }, at(T0 + 35000)), post("/api/verification/confirm", { code: "000000" }, at(T0 + 36000)), post("/api/verification/confirm", { code: "123456" }, at(T0 + 37000)),
  post("/api/verification/start", { channel: "sms" }, at(T0 + 38000)), get("/api/account"), post("/api/verification/confirm", { code: "123456" }, at(T0 + 16 * 60 * 1000 + 38000)),
  post("/api/verification/start", { channel: "sms" }, at(T0 + 16 * 60 * 1000 + 70000)), post("/api/verification/confirm", { code: " 123456 " }, at(T0 + 16 * 60 * 1000 + 71000)), get("/api/account"),
  post("/api/verification/start", { channel: "email" }), post("/api/verification/confirm", { code: "123456" }), put("/api/account", { phone: "206-555-0177" }), get("/api/account"),
  post("/api/verification/start", { channel: "email" }, at(T0 + 3600000)), post("/api/verification/confirm", { code: "123456" }, at(T0 + 3600000 + 11 * 60 * 1000)), post("/api/verification/confirm", { code: "123456" }, at(T0 + 3600000 + 12 * 60 * 1000)),
  post("/api/verification/start", { channel: "email" }, { headers: { "X-Demo-Now": "1.7e12" } }), post("/api/verification/start", { channel: "email" }, { headers: { "X-Demo-Now": "0x10" } }), post("/api/verification/start", { channel: "email" }, { headers: { "X-Demo-Now": "abc" } }),
  post("/api/verification/start", { channel: "email" }, { headers: { "X-Demo-Now": "-5" } }), post("/api/verification/start", { channel: "email" }, { headers: { "X-Demo-Now": "  1790000000000.7  " } }), post("/api/verification/start", { channel: "email" }, { headers: { "X-Demo-Now": "9e15" } }),
]);

// Known difference: the reference answers 500 for a six-character code with a non-ASCII letter (its timing-safe compare throws on unequal
// byte lengths). The ports answer 400 and count a failure, which is the intended behavior. Reported, not counted as a failure.
script("known deviation: non-ASCII verification code", [
  login("kai.knoll@example.com"), post("/api/verification/start", { channel: "email" }), post("/api/verification/confirm", { code: "\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9" }),
], { known: true });

// Known difference: without DEMO=1 the reference answers OPTIONS on a known path itself (200, the Allow list as text); this store has no
// such route and answers its JSON 404. Nothing in the storefront or the contract sends OPTIONS (with DEMO=1 both answer 204).
script("known deviation: OPTIONS without DEMO", [
  { method: "OPTIONS", path: "/api/signup" }, { method: "OPTIONS", path: "/api/products" }, { method: "OPTIONS", path: "/api/nothing" },
], { known: true });

// Known difference: the reference redirects a directory path without its trailing slash (301 to /img/); this store answers its JSON 404.
// Known difference: the reference removes the card and then answers 415 when the path is spelled in another letter case or with a trailing slash
// and the body is not JSON (it reads the body for a profile change after the change was made); this store answers 200 and never a 415 for a change it made.
script("known deviation: card removal with a text body and an odd path spelling", [
  login(), put("/api/account/payment-method", visa), del("/api/Account/payment-method/", { body: "x", headers: { "Content-Type": "text/plain" } }),
  put("/api/account/payment-method", visa), del("/API/ACCOUNT/PAYMENT-METHOD", { body: "x", headers: { "Content-Type": "text/plain" } }),
], { known: true });
script("known deviation: directory path without a slash", [get("/img")], { known: true });

// ---- cookies and the session ----
script("cookies and sessions", [
  get("/api/account", { headers: { Cookie: "nb_sid=sess_0000000000000000" } }), get("/api/account", { headers: { Cookie: "nb_sid=" } }), get("/api/account", { headers: { Cookie: "a=1; nb_sid=zzz; b=2" } }),
  get("/api/account", { headers: { Cookie: "nb_sid=a; nb_sid=b" } }), get("/api/account", { headers: { Cookie: "xnb_sid=1" } }), get("/api/account", { headers: { Cookie: " nb_sid=1" } }), get("/api/account", { headers: { Cookie: "nb_sid=1;nb_sid=2" } }),
  login(), get("/api/account"), post("/api/logout", {}), get("/api/account"),
]);

// ---- demo routes (DEMO=1 on both) ----
script("demo routes", [
  { method: "HEAD", path: "/demo/scenarios.js" }, { method: "HEAD", path: "/demo/log" }, get("/demo/scenarios.js"), get("/demo/log"), get("/demo/log?since=3"), get("/demo/log?since=-1"), get("/demo/log?since=1.5"), get("/demo/log?since=abc"), get("/demo/log?since=0x10"), get("/demo/log?since=1e3"), get("/demo/log?since=1&since=2"), get("/demo/log?since="),
  get("/demo/nothing"), post("/demo/reset", {}), post("/demo/reset", "{ bad"), post("/demo/reset", "x", { headers: { "Content-Type": "text/plain" } }), post("/demo/dispute", {}), post("/demo/dispute", { orderId: "ORD-20001" }),
  post("/demo/dispute", { orderId: "ORD-20001", reason: "nope" }), post("/demo/dispute", { orderId: "ORD-20001", status: "nope" }), post("/demo/dispute", { orderId: "ORD-20001", reason: null }), post("/demo/dispute", { orderId: 20001 }), post("/demo/dispute", { orderId: "ORD-1" }),
  post("/demo/dispute", { orderId: "ORD-20001", reason: "fraudulent", status: "lost" }), post("/demo/dispute", { orderId: "ORD-20002", reason: "general", status: "needs_response" }), post("/demo/dispute", "{ bad"), post("/demo/dispute", "[]"),
  post("/demo/orders/ORD-1/ship", {}), post("/demo/orders/ORD-20001/ship", {}), post("/demo/orders/ORD-20001/ship", {}), post("/demo/orders/ORD-20001/ship", "{ bad"), get("/demo/orders/ORD-20001/ship"),
  { method: "OPTIONS", path: "/demo/reset", headers: { Origin: "http://localhost:4010" } }, get("/api/products", { headers: { "X-Demo-User-Agent": "Parity/1", "X-Demo-Client-Ip": "203.0.113.9" } }),
  post("/api/signup", signupBody("demo1"), { headers: { "X-Demo-Now": String(T0) } }), get("/api/account", at(T0)),
]);

// ---- seeded random requests (--fuzz N [--seed S]) ----
// Random bodies for every route (valid JSON of every type, odd text, odd numbers), with the shopper signed in or not, from a fixed seed so a
// difference can be replayed. Needs DEMO=1 on both stores (it resets between batches).
function fuzzScript(count, seed) {
  let a = seed >>> 0;
  const rnd = () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const pick = (xs) => xs[Math.floor(rnd() * xs.length)];
  const WS = ["", " ", "\u00a0", "\u0085", "\u200b", "\ufeff", "\u3000", "\u2028", "\t", "\n"];
  const texts = ["x", "WELCOME10", "SAVE5", "LIMITED25", "EXPIRED20", "welcome10", "p1", "p2", "p9", "123456", "000000", "email", "sms", "standard", "expedited", "tok_visa", "tok_declined", "411111", "1111", "ORD-20041", "ORD-20042", "ch_50041", "ch_50042", "charge.dispute.created", "charge.dispute.closed", "shipment.created", "duplicate", "won", "lost", "\u00df", "\u017f", "a@b.co", "avery.alder@example.com", "blake.brook@example.com", "+14155550100", "415-555-0100", "Demo!2026", "New!2026", "x".repeat(201), "\ud83d\ude00"];
  const numbers = [0, 1, 2, -1, 1.5, 2.5, 1e21, 1e-7, 1e300, 9007199254740993, 12345678901234567890, 100, 3];
  const text = () => pick(WS) + pick(texts) + pick(WS);
  const value = (d = 0) => { const r = rnd(); if (r < 0.45) return text(); if (r < 0.6) return pick(numbers); if (r < 0.66) return null; if (r < 0.72) return rnd() < 0.5; if (r < 0.8 && d < 2) return [value(d + 1)]; if (r < 0.88 && d < 2) return { a: value(d + 1) }; return text(); };
  const addrBody = () => rnd() < 0.7 ? { name: pick(["A", "Pat", "x".repeat(200), ""]), address1: "1 St", city: "C", region: "R", country: "US", zipcode: pick(["1", "94105", " ", 5]), ...(rnd() < 0.2 ? { extra: value() } : {}) } : value();
  const cardBody = () => rnd() < 0.7 ? { token: pick(["tok_visa", "tok_declined", "t", " ", 5]), cardBin: pick(["411111", "41111111", "4111", 411111]), cardLast4: pick(["1111", "111", 1111]), ...(rnd() < 0.2 ? { type: value() } : {}) } : value();
  const orderId = () => pick(["ORD-20041", "ORD-20042", "ORD-20043", "ORD-20001", "ORD-20002", "ORD-99999", "x"]);
  const ops = [
    () => post("/api/cart/items", { productId: rnd() < 0.8 ? pick(["p1", "p2", "p3", "p4", "p5", "p6"]) : value(), ...(rnd() < 0.7 ? { qty: rnd() < 0.7 ? pick([1, 2, 3, 1, 1]) : value() } : {}) }),
    () => post("/api/cart/promo", rnd() < 0.8 ? { code: rnd() < 0.7 ? pick(["WELCOME10", "SAVE5", "LIMITED25", "EXPIRED20", "welcome10"]) : value() } : value()),
    () => del("/api/cart/promo"),
    () => del("/api/cart/items/" + pick(["p1", "p2", "p3", "p9", "%zz"])),
    () => post("/api/orders", rnd() < 0.8 ? { shippingAddress: addrBody(), billingAddress: addrBody(), ...(rnd() < 0.3 ? { shippingMethod: value() } : {}) } : value()),
    () => put(`/api/orders/${orderId()}/shipping`, rnd() < 0.8 ? { ...(rnd() < 0.6 ? { shippingAddress: addrBody() } : {}), ...(rnd() < 0.6 ? { shippingMethod: rnd() < 0.7 ? pick(["standard", "expedited"]) : value() } : {}) } : value()),
    () => post(`/api/orders/${orderId()}/pay`, rnd() < 0.5 ? {} : rnd() < 0.8 ? { payment: cardBody() } : value()),
    () => post(`/api/orders/${orderId()}/cancel`, {}),
    () => put("/api/account", rnd() < 0.85 ? Object.fromEntries(["name", "email", "phone", "billingAddress", "shippingAddress"].filter(() => rnd() < 0.4).map((k) => [k, k.endsWith("Address") ? addrBody() : value()])) : value()),
    () => put("/api/account/payment-method", cardBody()), () => del("/api/account/payment-method"),
    () => put("/api/account/password", { currentPassword: rnd() < 0.5 ? "Demo!2026" : value(), newPassword: rnd() < 0.7 ? "New!2026" : value() }),
    () => post("/api/verification/start", rnd() < 0.8 ? { channel: rnd() < 0.7 ? pick(["email", "sms"]) : value() } : value()),
    () => post("/api/verification/confirm", rnd() < 0.8 ? { code: rnd() < 0.5 ? "123456" : rnd() < 0.5 ? "000000" : value() } : value()),
    () => post("/webhooks/gateway", rnd() < 0.8 ? { type: rnd() < 0.7 ? pick(["charge.dispute.created", "charge.dispute.closed", "other"]) : value(), data: rnd() < 0.8 ? { chargeId: rnd() < 0.7 ? pick(["ch_50041", "ch_50042", "ch_50001", "ch_50002"]) : value(), reason: value(), status: value() } : value() } : value()),
    () => post("/webhooks/fulfillment", rnd() < 0.8 ? { event: rnd() < 0.8 ? "shipment.created" : value(), orderId: rnd() < 0.8 ? orderId() : value() } : value()),
    () => post("/demo/dispute", rnd() < 0.8 ? { orderId: rnd() < 0.8 ? orderId() : value(), reason: rnd() < 0.7 ? pick(["duplicate", "fraudulent", "general", "bad"]) : value(), status: rnd() < 0.7 ? pick(["needs_response", "won", "lost"]) : value() } : value()),
    () => post(`/demo/orders/${orderId()}/ship`, {}),
    () => get("/api/orders"), () => get("/api/cart"), () => get("/api/account"),
    () => login(pick(["avery.alder@example.com", "blake.brook@example.com", "casey.cedar@example.com", "nobody@example.com", value()]), rnd() < 0.8 ? "Demo!2026" : value()),
    () => post("/api/logout", {}),
    () => post("/api/signup", { name: rnd() < 0.8 ? "Fuzz" : value(), email: rnd() < 0.7 ? `fz${Math.floor(rnd() * 5)}@example.com` : value(), phone: rnd() < 0.8 ? "415-555-0111" : value(), password: rnd() < 0.8 ? "Demo!2026" : value(), ...(rnd() < 0.2 ? { billingAddress: addrBody() } : {}) }),
  ];
  const steps = [];
  for (let i = 0; i < count; i++) {
    if (i % 120 === 0) { steps.push(post("/demo/reset", {})); steps.push(login(pick(["avery.alder@example.com", "blake.brook@example.com", "casey.cedar@example.com"]))); }
    const step = pick(ops)();
    if (rnd() < 0.2) step.headers = { ...(step.headers || {}), "X-Demo-Now": String(1790000000000 + Math.floor(rnd() * 4) * 700000) };
    steps.push(step);
  }
  return steps;
}
if (opt("--fuzz")) script(`fuzz seed ${opt("--seed") || 1}`, fuzzScript(Number(opt("--fuzz")), Number(opt("--seed") || 1)));

async function main() {
  const reset = async (base) => (await send(base, { method: "POST", path: "/demo/reset", body: {} }, {})).status;
  const demoA = await reset(A), demoB = await reset(B);
  if (demoA !== demoB) { console.error(`the stores differ on POST /demo/reset: ${demoA} against ${demoB}. Start both the same way.`); process.exit(2); }
  const demo = demoA === 200;
  console.log(`a ${A}  b ${B}  demo ${demo ? "on" : "off"}`);
  let total = 0, differ = 0, knownDiffs = 0, savedFirst = false;
  for (const sc of scripts) {
    if (ONLY && !sc.name.includes(ONLY)) continue;
    if (!demo && sc.name === "demo routes") continue;
    if (demo) { await reset(A); await reset(B); }
    const ja = {}, jb = {};
    let n = 0, bad = 0;
    for (const [i, step] of sc.steps.entries()) {
      // Without DEMO there is no reset: ids and accounts carry over, so the scripts that sign up use their own emails.
      const [ra, rb] = [await send(A, step, ja), await send(B, step, jb)];
      const [na, nb] = [normalize(ra), normalize(rb)];
      n++; total++;
      if (JSON.stringify(na) !== JSON.stringify(nb)) {
        bad++; differ++;
        // The first difference of a run is always saved whole (unmasked bodies too), so it can be read after the fact.
        if (!sc.known && !savedFirst) { savedFirst = true; const file = `parity-first-diff-${Date.now()}.json`; fs.writeFileSync(file, JSON.stringify({ script: sc.name, step: i + 1, request: { method: step.method || "GET", path: step.path, headers: step.headers, body: typeof step.body === "string" ? step.body.slice(0, 5000) : step.body }, a: { status: ra.status, headers: ra.headers, body: ra.text }, b: { status: rb.status, headers: rb.headers, body: rb.text } }, null, 2)); console.log(`   (first difference saved whole to ${file})`); }
        console.log(`DIFF [${sc.name} #${i + 1}] ${step.method || "GET"} ${step.path} ${typeof step.body === "string" ? step.body.slice(0, 60) : JSON.stringify(step.body || "").slice(0, 100)}`);
        console.log(`   a: ${na.status} ${JSON.stringify(na.headers)} ${FULL ? na.body : na.body.slice(0, 300)}`);
        console.log(`   b: ${nb.status} ${JSON.stringify(nb.headers)} ${FULL ? nb.body : nb.body.slice(0, 300)}`);
      } else if (VERBOSE) console.log(`same [${sc.name} #${i + 1}] ${step.method || "GET"} ${step.path} -> ${na.status}`);
    }
    if (sc.known) { differ -= bad; knownDiffs += bad; total -= n; }
    console.log(`${bad ? (sc.known ? "KNOWN" : "DIFF") : "ok  "} ${sc.name}: ${n - bad} of ${n} identical`);
  }
  console.log(`\n${total - differ} of ${total} requests identical, ${differ} differ (${knownDiffs} more in known-deviation scripts, not counted)`);
  process.exit(differ ? 1 : 0);
}
main();
