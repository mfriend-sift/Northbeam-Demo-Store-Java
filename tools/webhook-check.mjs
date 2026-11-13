// Fixture-free check: a charge.dispute.closed (lost) webhook sets the order's dispute status to lost.
// Run against a running store: BASE=http://127.0.0.1:3000 node tools/webhook-check.mjs
// It creates a user, a cart and an order in that store, so the address is never guessed: BASE must be set (a flag such as --help only prints this).
if (!process.env.BASE || process.argv.slice(2).length) {
  console.error("usage: BASE=http://127.0.0.1:<port> node tools/webhook-check.mjs\n(creates a test user and order in that store; BASE is required so it never touches a store by accident)");
  process.exit(process.argv.slice(2).some((a) => a === "-h" || a === "--help") ? 0 : 2);
}
const BASE = process.env.BASE;
let cookie = "";
async function call(method, path, body) {
  const r = await fetch(BASE + path, { method, headers: { "Content-Type": "application/json", ...(cookie ? { Cookie: cookie } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const sc = r.headers.get("set-cookie");
  if (sc) cookie = sc.split(";")[0];
  return { status: r.status, body: await r.json().catch(() => ({})) };
}
const addr = { name: "Check Shopper", address1: "100 Market St", city: "San Francisco", region: "CA", country: "US", zipcode: "94105" };
const run = Date.now().toString(36);
await call("POST", "/api/signup", { name: "Check Shopper", email: `check+${run}@example.com`, phone: "415-555-0190", password: "Demo!2026" });
await call("POST", "/api/cart/items", { productId: "p1", qty: 1 });
const saved = await call("POST", "/api/orders", { shippingAddress: addr, billingAddress: addr });
const orderId = saved.body.order?.id;
await call("POST", `/api/orders/${orderId}/pay`, { payment: { token: "tok_visa", cardBin: "411111", cardLast4: "1111" } });
const chargeId = (await call("GET", "/api/orders")).body.orders?.[0]?.chargeId;
const hook = await call("POST", "/webhooks/gateway", { type: "charge.dispute.closed", data: { chargeId, reason: "duplicate", status: "lost" } });
const order = (await call("GET", "/api/orders")).body.orders?.find((o) => o.id === orderId);
const ok = hook.status === 200 && order?.chargeback?.status === "lost" && order?.chargeback?.reason === "duplicate";
console.log(ok ? "PASS webhook charge.dispute.closed lost -> order dispute status lost" : `FAIL hook ${hook.status}, order ${JSON.stringify(order)}`);
process.exit(ok ? 0 : 1);
