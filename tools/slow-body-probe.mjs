#!/usr/bin/env node
// Slow-body probe (needs the store running with DEMO=1). Usage: node tools/slow-body-probe.mjs --base http://127.0.0.1:<port>
// A signed-in client sends a JSON request head and part of its body, then stalls. Other requests must still be
// answered while that client stays connected (a body read inside the store's lock would block them all).
// Adapted from the Opus review script slowbody.py (2026-10-12).
import net from "node:net";
const i = process.argv.indexOf("--base");
const base = (i > 0 && process.argv[i + 1]) || process.env.STORE_URL;
if (!base) { console.error("usage: node tools/slow-body-probe.mjs --base http://127.0.0.1:<port>"); process.exit(2); }
const url = new URL(base);

await fetch(base + "/demo/reset", { method: "POST" });
const login = await fetch(base + "/api/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "avery.alder@example.com", password: "Demo!2026" }) });
const sid = /nb_sid=([^;]+)/.exec(login.headers.get("set-cookie") || "")?.[1];
if (login.status !== 200 || !sid) { console.error("login failed: is the store running with DEMO=1?"); process.exit(2); }

let failed = 0;
const check = (name, ok, detail = "") => { console.log(`${ok ? "PASS" : "FAIL"} ${name}${ok ? "" : "  " + detail}`); if (!ok) failed++; };
const answers = async (path) => { try { return (await fetch(base + path, { signal: AbortSignal.timeout(3000), headers: { Cookie: `nb_sid=${sid}` } })).status; } catch (e) { return String(e.name); } };

const stalls = [];
for (const [method, path] of [["POST", "/api/cart/items"], ["PUT", "/api/account"], ["POST", "/api/orders"], ["POST", "/api/verification/start"]]) {
  const s = net.connect(Number(url.port), url.hostname);
  s.on("error", () => {});
  s.write(`${method} ${path} HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nCookie: nb_sid=${sid}\r\nContent-Length: 40\r\n\r\n{"productId":`);
  stalls.push(s);
}
await new Promise((r) => setTimeout(r, 500));
check("GET /api/products answers while 4 half-sent bodies stall", (await answers("/api/products")) === 200);
check("GET /api/cart answers", (await answers("/api/cart")) === 200);
const add = await fetch(base + "/api/cart/items", { method: "POST", signal: AbortSignal.timeout(3000), headers: { "Content-Type": "application/json", Cookie: `nb_sid=${sid}` }, body: JSON.stringify({ productId: "p1" }) }).then((r) => r.status, (e) => String(e.name));
check("a normal POST /api/cart/items answers (not 5xx, no timeout)", typeof add === "number" && add < 500, String(add));
stalls.forEach((s) => s.destroy());
console.log(failed ? `\n${failed} check(s) failed` : "\nall checks passed");
process.exit(failed ? 1 : 0);
