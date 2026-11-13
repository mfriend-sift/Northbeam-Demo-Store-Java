// contract/scenarios.js: the one scenario module (Plan 1 6.2; Plan 3 freeze items 2 and 13).
// Frozen 2026-10-08. Changed 2026-10-10: S0 and S1 save then pay (the one-request checkout route was removed); S2 added. 2026-10-12: S3 to S9 added (simulate buttons only), per-step headers, requires.events.
// Run by the engine in the browser (embedded by build.mjs) and by
// tools/contract-test.mjs in Node 18+. ES module, no dependencies, no per-language orchestration.
//
// Semantics (also in the engine repo, docs/freeze/FREEZE.md section 13):
// - Steps drive only public store routes plus the demo routes /demo/reset and /demo/dispute.
// - Every request sends Content-Type: application/json (when it has a body) and the scenario's
//   persona headers X-Demo-Client-Ip and X-Demo-User-Agent. X-Demo-Now is sent only when the
//   caller passes `now`. In the browser, fetch uses credentials: "include" so nb_sid travels;
//   in Node, the runner keeps nb_sid itself and sends it as a Cookie header.
// - Pacing: at least PACING_MS (400 ms) between the starts of two steps for one user, so one
//   $user_id never exceeds 3 events per second (documented Events API limit).
// - Log waits: when a `logSource` is passed, after each step the runner waits until every event
//   in `expectEvents` appears in the log (records newer than the step's start), up to
//   LOG_WAIT_MS. Without a logSource (clean store on main) it checks HTTP statuses only.
// - Per-step headers (added 2026-10-12): a step may carry `headers` (an object, or a function of ctx). They are
//   sent after the persona headers and win over them, so a step can change the browser (X-Demo-User-Agent) or
//   the address (X-Demo-Client-Ip) for that one request. The store already honors both headers (lib/demo.js).
// - `requires: { events: [...] }` on a scenario (added 2026-10-12) names events beyond the base profile that
//   its expectEvents mention. With a logSource, those are asserted only when the caller passes
//   `opts.events` (the profile's enabled events) and it includes them; otherwise they are skipped and listed
//   in the step result as `skippedEvents`. HTTP statuses are always asserted.
// - `notes` on a scenario is informational text (the expected simulated score), never an assertion.
// - S0 to S2 list only the base six events (the sprint scope). Plan 1's S1 also expects
//   $link_session_to_user and $add_item_to_cart; those arrive with P3-REF-10.

export const PACING_MS = 400;
export const LOG_WAIT_MS = 5000;

// Documentation IP ranges only (Plan 1 3.1.7). Letters follow Plan 1 section 6.
export const PERSONAS = {
  A: { ip: "203.0.113.11", userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15" },
  D: { ip: "203.0.113.44", userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15" },
  F: { ip: "198.51.100.51", userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36" },
  G: { ip: "192.0.2.77", userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15" },
  H: { ip: "203.0.113.18", userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36" },
};

const ADDRESS = (name) => ({ name, address1: "100 Market St", city: "San Francisco", region: "CA", country: "US", zipcode: "94105" });
const NEW_ADDRESS = (name) => ({ name, address1: "1 Ferry Building", city: "San Francisco", region: "CA", country: "US", zipcode: "94111" });
const ABROAD_ADDRESS = (name) => ({ name, address1: "221 Baker St", city: "London", region: "England", country: "GB", zipcode: "NW1 6XE" });
const VISA = { token: "tok_visa", cardBin: "411111", cardLast4: "1111" };

// Step shape (frozen):
//   { id, label, method, path, body?, expectStatus, expectEvents[], save? }
//   path and body may be functions of ctx (ctx.runId plus values saved by earlier steps).
//   save(ctx, responseBody) copies values into ctx for later steps.
// Shared step builders for S3 to S9 (same shapes as the S0 to S2 steps above).
const RESET = { id: "reset", label: "Reset the store", method: "POST", path: "/demo/reset", expectStatus: 200, expectEvents: [] };
const NEW_BROWSER = { "X-Demo-User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0" };
const signup = (tag, name, phone, extra = {}) => ({ id: `signup${extra.suffix || ""}`, label: `Create account${extra.suffix ? " " + extra.suffix.toUpperCase() : ""}`, method: "POST", path: "/api/signup",
  body: (ctx) => ({ name, email: `${tag}${extra.suffix || ""}+${ctx.runId}@example.com`, phone, password: "Demo!2026" }),
  expectStatus: 201, expectEvents: ["$create_account"], save: (ctx, b) => { ctx.userId = b.user.id; } });
const login = (id, label, email, password, ok, extra = {}) => ({ id, label, method: "POST", path: "/api/login", body: { email, password },
  expectStatus: ok ? 200 : 401, expectEvents: ["$login"], ...extra });
const addItem = (id, label, productId, qty = 1) => ({ id, label, method: "POST", path: "/api/cart/items", body: { productId, qty }, expectStatus: 200, expectEvents: [] });
const saveOrder = (shipping, billing, extra = {}) => ({ id: "save-order", label: "Save order (not paid yet)", method: "POST", path: "/api/orders",
  body: { shippingAddress: shipping, billingAddress: billing }, expectStatus: 201, expectEvents: ["$create_order"], save: (ctx, b) => { ctx.orderId = b.order.id; }, ...extra });
const pay = (extra = {}) => ({ id: "pay", label: "Pay with Visa ending 1111", method: "POST", path: (ctx) => `/api/orders/${ctx.orderId}/pay`,
  body: { payment: VISA }, expectStatus: 200, expectEvents: ["$transaction"], ...extra });
// Reads the signed-in user's order history and keeps the first order id (so the order the scenario acts on is one the shopper sees).
const firstOrder = (extra = {}) => ({ id: "order-history", label: "Open order history", method: "GET", path: "/api/orders", expectStatus: 200, expectEvents: [],
  save: (ctx, b) => { ctx.orderId = b.orders[0].id; }, ...extra });
const dispute = (id, label, status, reason = "fraudulent") => ({ id, label, method: "POST", path: "/demo/dispute",
  body: (ctx) => ({ orderId: ctx.orderId, reason, status }), expectStatus: 200, expectEvents: ["$chargeback"] });

export const SCENARIOS = {
  S0: {
    id: "S0",
    title: "Base six",
    persona: "H",
    description: "New signup that triggers each base-six event once. Used by Prove it with traffic and Verified (live).",
    steps: [
      { id: "reset", label: "Reset the store", method: "POST", path: "/demo/reset", expectStatus: 200, expectEvents: [] },
      { id: "signup", label: "Create account", method: "POST", path: "/api/signup",
        body: (ctx) => ({ name: "Harper Heath", email: `s0+${ctx.runId}@example.com`, phone: "415-555-0181", password: "Demo!2026" }),
        expectStatus: 201, expectEvents: ["$create_account"], save: (ctx, b) => { ctx.userId = b.user.id; } },
      { id: "update-account", label: "Change phone", method: "PUT", path: "/api/account",
        body: { phone: "415-555-0182" }, expectStatus: 200, expectEvents: ["$update_account"] },
      { id: "add-to-cart", label: "Add merino sweater", method: "POST", path: "/api/cart/items",
        body: { productId: "p1", qty: 1 }, expectStatus: 200, expectEvents: [] },
      { id: "save-order", label: "Save order (not paid yet)", method: "POST", path: "/api/orders",
        body: { shippingAddress: ADDRESS("Harper Heath"), billingAddress: ADDRESS("Harper Heath") },
        expectStatus: 201, expectEvents: ["$create_order"], save: (ctx, b) => { ctx.orderId = b.order.id; } },
      { id: "pay", label: "Pay with Visa ending 1111", method: "POST", path: (ctx) => `/api/orders/${ctx.orderId}/pay`,
        body: { payment: VISA }, expectStatus: 200, expectEvents: ["$transaction"] },
      { id: "update-shipping", label: "Change shipping address", method: "PUT", path: (ctx) => `/api/orders/${ctx.orderId}/shipping`,
        body: { shippingAddress: NEW_ADDRESS("Harper Heath") }, expectStatus: 200, expectEvents: ["$update_order"] },
      { id: "dispute", label: "Dispute lost (duplicate)", method: "POST", path: "/demo/dispute",
        body: (ctx) => ({ orderId: ctx.orderId, reason: "duplicate", status: "lost" }),
        expectStatus: 200, expectEvents: ["$chargeback"] },
    ],
  },
  S1: {
    id: "S1",
    title: "Good customer",
    persona: "A",
    description: "New signup, two items, paid with tok_visa. The mock's simulated score is 25.",
    steps: [
      { id: "reset", label: "Reset the store", method: "POST", path: "/demo/reset", expectStatus: 200, expectEvents: [] },
      { id: "signup", label: "Create account", method: "POST", path: "/api/signup",
        body: (ctx) => ({ name: "Avery Brook", email: `s1+${ctx.runId}@example.com`, phone: "415-555-0171", password: "Demo!2026" }),
        expectStatus: 201, expectEvents: ["$create_account"], save: (ctx, b) => { ctx.userId = b.user.id; } },
      { id: "add-1", label: "Add merino sweater", method: "POST", path: "/api/cart/items",
        body: { productId: "p1", qty: 1 }, expectStatus: 200, expectEvents: [] },
      { id: "add-2", label: "Add canvas daypack", method: "POST", path: "/api/cart/items",
        body: { productId: "p4", qty: 1 }, expectStatus: 200, expectEvents: [] },
      { id: "save-order", label: "Save order (not paid yet)", method: "POST", path: "/api/orders",
        body: { shippingAddress: ADDRESS("Avery Brook"), billingAddress: ADDRESS("Avery Brook") },
        expectStatus: 201, expectEvents: ["$create_order"], save: (ctx, b) => { ctx.orderId = b.order.id; } },
      { id: "pay", label: "Pay with Visa ending 1111", method: "POST", path: (ctx) => `/api/orders/${ctx.orderId}/pay`,
        body: { payment: VISA }, expectStatus: 200, expectEvents: ["$transaction"] },
    ],
  },
  S2: {
    id: "S2",
    title: "Save, change shipping, pay",
    persona: "A",
    description: "Saves an order without paying, changes the shipping address and method before paying ($update_order resends the full order), then pays.",
    steps: [
      { id: "reset", label: "Reset the store", method: "POST", path: "/demo/reset", expectStatus: 200, expectEvents: [] },
      { id: "signup", label: "Create account", method: "POST", path: "/api/signup",
        body: (ctx) => ({ name: "Avery Brook", email: `s2+${ctx.runId}@example.com`, phone: "415-555-0172", password: "Demo!2026" }),
        expectStatus: 201, expectEvents: ["$create_account"], save: (ctx, b) => { ctx.userId = b.user.id; } },
      { id: "add-1", label: "Add trail running shoes", method: "POST", path: "/api/cart/items",
        body: { productId: "p2", qty: 1 }, expectStatus: 200, expectEvents: [] },
      { id: "save-order", label: "Save order (not paid yet)", method: "POST", path: "/api/orders",
        body: { shippingAddress: ADDRESS("Avery Brook"), billingAddress: ADDRESS("Avery Brook") },
        expectStatus: 201, expectEvents: ["$create_order"], save: (ctx, b) => { ctx.orderId = b.order.id; } },
      { id: "change-shipping", label: "Change shipping before paying (new address, expedited)", method: "PUT",
        path: (ctx) => `/api/orders/${ctx.orderId}/shipping`,
        body: { shippingAddress: NEW_ADDRESS("Avery Brook"), shippingMethod: "expedited" },
        expectStatus: 200, expectEvents: ["$update_order"] },
      { id: "pay", label: "Pay with Visa ending 1111", method: "POST", path: (ctx) => `/api/orders/${ctx.orderId}/pay`,
        body: { payment: VISA }, expectStatus: 200, expectEvents: ["$transaction"] },
    ],
  },

  S3: {
    id: "S3",
    title: "Large order, country mismatch",
    persona: "A",
    description: "New account, a cart of about $900, billing in the US and shipping to the UK, then pay.",
    notes: "Expected simulated score on the order: 65 (review). Informational, not an assertion.",
    steps: [
      RESET,
      signup("s3", "Avery Brook", "415-555-0173"),
      addItem("add-1", "Add storm shell jacket x4", "p5", 4),
      addItem("add-2", "Add wool trail beanie", "p6"),
      saveOrder(ABROAD_ADDRESS("Avery Brook"), ADDRESS("Avery Brook")),
      pay(),
    ],
  },
  S4: {
    id: "S4",
    title: "Account takeover",
    persona: "D",
    requires: { events: ["$login", "$update_password"] },
    description: "A long-standing account (seeded shopper_1003) gets 6 failed logins, then a login from a different browser, an email and phone change, a password change, and an order to a new shipping address. The account is older than 10 minutes because it is seeded.",
    notes: "Planned simulated score on the order: 90 (block); the mock does not build the takeover rules yet, so today it shows lower. Informational, not an assertion.",
    steps: [
      RESET,
      ...[1, 2, 3, 4, 5, 6].map((n) => login(`fail-${n}`, `Failed login ${n} of 6`, "casey.cedar@example.com", `wrong-guess-${n}`, false)),
      login("login-new-browser", "Login from a different browser", "casey.cedar@example.com", "Demo!2026", true, { headers: NEW_BROWSER }),
      { id: "change-contact", label: "Change email and phone", method: "PUT", path: "/api/account", headers: NEW_BROWSER,
        body: (ctx) => ({ email: `casey.new+${ctx.runId}@example.com`, phone: "415-555-0199" }), expectStatus: 200, expectEvents: ["$update_account"] },
      { id: "change-password", label: "Change password", method: "PUT", path: "/api/account/password", headers: NEW_BROWSER,
        body: { currentPassword: "Demo!2026", newPassword: "Changed!2026" }, expectStatus: 200, expectEvents: ["$update_password"] },
      { ...addItem("add-1", "Add trail running shoes", "p2"), headers: NEW_BROWSER },
      saveOrder(NEW_ADDRESS("Casey Cedar"), ADDRESS("Casey Cedar"), { headers: NEW_BROWSER }),
      pay({ headers: NEW_BROWSER }),
    ],
  },
  S5: {
    id: "S5",
    title: "Promo abuse",
    persona: "F",
    description: "Three new accounts sign up from one client address, each applies WELCOME10 and orders.",
    notes: "Planned simulated scores on the orders: 25 (accept), 50 (watch), 65 (review); the mock does not build the promo and shared-address rules yet, so today each shows 25. Informational, not an assertion.",
    steps: [
      RESET,
      ...["a", "b", "c"].flatMap((n) => [
        { ...signup("s5", `Promo Shopper ${n.toUpperCase()}`, "415-555-0174", { suffix: n }) },
        addItem(`add-${n}`, `Add canvas daypack (account ${n.toUpperCase()})`, "p4"),
        { id: `promo-${n}`, label: `Apply WELCOME10 (account ${n.toUpperCase()})`, method: "POST", path: "/api/cart/promo", body: { code: "WELCOME10" }, expectStatus: 200, expectEvents: [] },
        { ...saveOrder(ADDRESS(`Promo Shopper ${n.toUpperCase()}`), ADDRESS(`Promo Shopper ${n.toUpperCase()}`)), id: `save-order-${n}`, label: `Save order (account ${n.toUpperCase()})` },
        { ...pay(), id: `pay-${n}`, label: `Pay (account ${n.toUpperCase()})` },
      ]),
    ],
  },
  S6: {
    id: "S6",
    title: "Chargeback",
    persona: "G",
    description: "A seeded shopper (shopper_1017, the S6 user in seed.json) signs in, opens order history, and the gateway reports a dispute on the seeded paid order.",
    notes: "Expected simulated score on the chargeback: 95 or more (block). Informational, not an assertion.",
    steps: [
      RESET,
      login("login", "Sign in (quinn.quarry@example.com)", "quinn.quarry@example.com", "Demo!2026", true),
      firstOrder(),
      dispute("dispute", "Dispute received (fraudulent)", "needs_response"),
    ],
    requires: { events: ["$login"] },
  },
  S7: {
    id: "S7",
    title: "Dispute won",
    persona: "G",
    description: "A seeded shopper (shopper_1018, the S7 user in seed.json) signs in; a dispute on the seeded paid order is received, then won and closed.",
    notes: "Expected simulated score after the win: 55 (watch), 45 after an analyst accepts. Informational, not an assertion.",
    steps: [
      RESET,
      login("login", "Sign in (reese.ridge@example.com)", "reese.ridge@example.com", "Demo!2026", true),
      firstOrder(),
      dispute("dispute", "Dispute received (product not received)", "needs_response", "product_not_received"),
      dispute("dispute-won", "Dispute won and closed", "won", "product_not_received"),
    ],
    requires: { events: ["$login"] },
  },
  S8: {
    id: "S8",
    title: "Shipping change after order",
    persona: "A",
    description: "New account, order paid, then the shipping address is changed to a different country.",
    notes: "Simulated score on the order: 25 (accept). The planned +20 when the country changes after the order is not built in the mock yet. Informational, not an assertion.",
    steps: [
      RESET,
      signup("s8", "Avery Brook", "415-555-0175"),
      addItem("add-1", "Add merino sweater", "p1"),
      saveOrder(ADDRESS("Avery Brook"), ADDRESS("Avery Brook")),
      pay(),
      { id: "update-shipping", label: "Change shipping to the UK", method: "PUT", path: (ctx) => `/api/orders/${ctx.orderId}/shipping`,
        body: { shippingAddress: ABROAD_ADDRESS("Avery Brook") }, expectStatus: 200, expectEvents: ["$update_order"] },
    ],
  },
  S9: {
    id: "S9",
    title: "Log out, then log in again",
    persona: "A",
    description: "A seeded shopper (shopper_1002, blake.brook@example.com, password Demo!2026; no other scenario uses it) logs in, logs out, logs in again with the same credentials; the final account read proves the session. No reset and no new account, so accounts made by hand are never wiped. Repeatable: nothing here changes the user, and the store has no failed-login lockout.",
    notes: "Simulated score on the login: low (accept). Informational, not an assertion.",
    steps: [
      login("login", "Log in (blake.brook@example.com)", "blake.brook@example.com", "Demo!2026", true, { expectEvents: ["$login", "$link_session_to_user"] }),
      { id: "logout", label: "Log out", method: "POST", path: "/api/logout", expectStatus: 200, expectEvents: ["$logout"] },
      login("login-again", "Log in again (same credentials)", "blake.brook@example.com", "Demo!2026", true, { expectEvents: ["$login", "$link_session_to_user"] }),
      { id: "account", label: "Open account (signed in)", method: "GET", path: "/api/account", expectStatus: 200, expectEvents: [] },
    ],
    requires: { events: ["$logout", "$login", "$link_session_to_user"] },
  },
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const resolve = (v, ctx) => (typeof v === "function" ? v(ctx) : v);

/**
 * Run one scenario.
 * @param {string} id                    "S0" to "S9"
 * @param {object} opts
 * @param {string} opts.baseUrl          store origin, for example "http://localhost:3000"
 * @param {typeof fetch} [opts.fetch]    defaults to globalThis.fetch
 * @param {boolean} [opts.browser]       true in the browser: credentials "include", no Cookie header
 * @param {{ since(seq:number): Promise<{records:object[], nextSeq:number}>, latestSeq(): Promise<number> }} [opts.logSource]
 *                                       reads the mock's /__log (engine) or nothing (clean store)
 * @param {string[]} [opts.events]       events the profile sends; events a scenario lists in requires.events are asserted only if present here
 * @param {number} [opts.now]            epoch ms sent as X-Demo-Now
 * @param {(event:object) => void} [opts.onStep]
 * @returns {Promise<{ ok: boolean, ctx: object, results: object[] }>}
 */
export async function runScenario(id, opts) {
  const sc = SCENARIOS[id];
  if (!sc) throw new Error(`Unknown scenario ${id}`);
  const doFetch = opts.fetch || globalThis.fetch;
  const persona = PERSONAS[sc.persona];
  const ctx = { runId: Date.now().toString(36) };
  let cookie = null;
  const results = [];
  let lastStart = 0;
  for (const step of sc.steps) {
    const wait = lastStart + PACING_MS - Date.now();
    if (wait > 0) await sleep(wait);
    lastStart = Date.now();
    const since = opts.logSource ? await opts.logSource.latestSeq() : 0;
    const body = resolve(step.body, ctx);
    const headers = { "X-Demo-Client-Ip": persona.ip, "X-Demo-User-Agent": persona.userAgent, ...resolve(step.headers, ctx) };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (opts.now !== undefined) headers["X-Demo-Now"] = String(opts.now);
    if (!opts.browser && cookie) headers.Cookie = cookie;
    const res = await doFetch(opts.baseUrl + resolve(step.path, ctx), {
      method: step.method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      ...(opts.browser ? { credentials: "include" } : {}),
    });
    if (!opts.browser) {
      const set = res.headers.get("set-cookie");
      const m = set && /nb_sid=([^;]+)/.exec(set);
      if (m) cookie = `nb_sid=${m[1]}`;
    }
    const json = await res.json().catch(() => null);
    const result = { step: step.id, httpStatus: res.status, ok: res.status === step.expectStatus, body: json, events: [] };
    if (result.ok && step.save) step.save(ctx, json);
    // Events the scenario lists under requires.events are asserted only when the caller says the profile has them.
    const extra = new Set((sc.requires && sc.requires.events) || []);
    const asserted = step.expectEvents.filter((e) => !extra.has(e) || (opts.events && opts.events.includes(e)));
    if (asserted.length < step.expectEvents.length) result.skippedEvents = step.expectEvents.filter((e) => !asserted.includes(e));
    if (result.ok && opts.logSource && asserted.length) {
      const deadline = Date.now() + LOG_WAIT_MS;
      const missing = new Set(asserted);
      while (missing.size && Date.now() < deadline) {
        const { records } = await opts.logSource.since(since);
        for (const r of records) if (missing.delete(r.event)) result.events.push(r.seq);
        if (missing.size) await sleep(150);
      }
      if (missing.size) { result.ok = false; result.missingEvents = [...missing]; }
    }
    results.push(result);
    if (opts.onStep) opts.onStep(result);
    if (!result.ok) return { ok: false, ctx, results };
  }
  return { ok: true, ctx, results };
}
