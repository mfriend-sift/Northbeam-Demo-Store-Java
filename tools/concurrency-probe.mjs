#!/usr/bin/env node
// Concurrency probe (needs the store running with DEMO=1: it resets the store first).
// Usage: node tools/concurrency-probe.mjs --base http://127.0.0.1:<port>
// Checks the check-then-change paths under parallel requests:
//   1. 10 parallel POST /api/cart/promo with LIMITED25 over 3 accounts: each account accepts once, the rest are 422.
//   2. Then 3 parallel order saves: exactly 2 orders carry LIMITED25 (the code allows 2 uses); the third save is 409.
//   3. 8 parallel order saves from one cart: exactly one order is made.
//   4. 8 parallel pays of one order: exactly one 200, the rest 409, one charge.
//   5. 12 parallel signups with one email: exactly one 201, the rest 409.
// Exit 0 when every check holds.
const i = process.argv.indexOf("--base");
const base = (i > 0 && process.argv[i + 1]) || process.env.STORE_URL;
if (!base) { console.error("usage: node tools/concurrency-probe.mjs --base http://127.0.0.1:<port>"); process.exit(2); }

const ADDR = { name: "Probe", address1: "1 Main St", city: "Seattle", region: "WA", country: "US", zipcode: "98101" };
const CARD = { token: "tok_probe", cardBin: "411111", cardLast4: "1111" };

function client() {
  let cookie = "";
  return async (method, path, body) => {
    const headers = { "Content-Type": "application/json", ...(cookie && { Cookie: cookie }) };
    const res = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const set = res.headers.get("set-cookie");
    if (set) cookie = set.split(";")[0];
    let json = null;
    try { json = await res.json(); } catch { /* no body */ }
    return { status: res.status, json };
  };
}

let failed = 0;
const check = (name, ok, detail) => { console.log(`${ok ? "PASS" : "FAIL"} ${name}${ok ? "" : "  " + JSON.stringify(detail)}`); if (!ok) failed++; };
const count = (rs, s) => rs.filter((r) => r.status === s).length;

const reset = await fetch(base + "/demo/reset", { method: "POST" });
if (!reset.ok) { console.error("POST /demo/reset failed: start the store with DEMO=1"); process.exit(2); }
const productId = (await (await fetch(base + "/api/products")).json())[0].id;
const run = Date.now().toString(36);

async function shopper(tag) {
  const c = client();
  const r = await c("POST", "/api/signup", { name: "Probe " + tag, email: `probe-${tag}-${run}@example.com`, phone: "415-555-0150", password: "Demo!2026" });
  if (r.status !== 201) throw new Error("signup failed " + r.status);
  await c("POST", "/api/cart/items", { productId, qty: 1 });
  return c;
}

// 1 and 2: the limited-use promo
const shoppers = await Promise.all(["a", "b", "c"].map(shopper));
const promoCalls = Array.from({ length: 10 }, (_, k) => shoppers[k % 3]("POST", "/api/cart/promo", { code: "LIMITED25" }));
const promoRes = await Promise.all(promoCalls);
check("10 parallel promo applies: 3 accepted (one per account), 7 rejected", count(promoRes, 200) === 3 && count(promoRes, 422) === 7, promoRes.map((r) => r.status));
const saveRes = await Promise.all(shoppers.map((c) => c("POST", "/api/orders", { shippingAddress: ADDR, billingAddress: ADDR })));
const withPromo = saveRes.filter((r) => r.status === 201 && r.json.order.promotion && r.json.order.promotion.code === "LIMITED25").length;
check("3 parallel saves: exactly 2 orders carry LIMITED25, the third is 409", withPromo === 2 && count(saveRes, 409) === 1, saveRes.map((r) => r.status));

// 3: one cart, many saves
const solo = await shopper("d");
const saves = await Promise.all(Array.from({ length: 8 }, () => solo("POST", "/api/orders", { shippingAddress: ADDR, billingAddress: ADDR })));
check("8 parallel saves of one cart: exactly one order", count(saves, 201) === 1 && count(saves, 400) === 7, saves.map((r) => r.status));

// 4: one order, many pays
const orderId = saves.find((r) => r.status === 201).json.order.id;
const pays = await Promise.all(Array.from({ length: 8 }, () => solo("POST", `/api/orders/${orderId}/pay`, { payment: CARD })));
check("8 parallel pays of one order: one 200, the rest 409", count(pays, 200) === 1 && count(pays, 409) === 7, pays.map((r) => r.status));

// 5: one email, many signups
const email = `race-${run}@example.com`;
const signups = await Promise.all(Array.from({ length: 12 }, () => client()("POST", "/api/signup", { name: "Race", email, phone: "415-555-0151", password: "Demo!2026" })));
check("12 parallel signups with one email: one 201, the rest 409", count(signups, 201) === 1 && count(signups, 409) === 11, signups.map((r) => r.status));

console.log(failed ? `\n${failed} check(s) failed` : "\nall checks passed");
process.exit(failed ? 1 : 0);
