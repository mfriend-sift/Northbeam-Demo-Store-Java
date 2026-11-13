#!/usr/bin/env node
// Runs contract/fixtures/*.json, then every scenario in contract/scenarios.js (S0, S1, S2 ...), against a running store.
// Usage: node tools/contract-test.mjs --base http://127.0.0.1:<port>   (or set STORE_URL)
// The address is required so the test never resets or writes to a store by accident.
// Fixtures marked demo run only when the store answers POST /demo/reset (DEMO=1);
// onlyWithoutDemo fixtures run only when it does not. Needs Node 18+, no dependencies.
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseYaml } from "./mini-yaml.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const argBase = process.argv.indexOf("--base");
const baseArg = argBase > 0 ? process.argv[argBase + 1] : undefined;
if (process.argv.slice(2).some((a) => a === "-h" || a === "--help") || !(baseArg || process.env.STORE_URL) || (argBase > 0 && !baseArg)) {
  console.error("usage: node tools/contract-test.mjs --base http://127.0.0.1:<port>   (or STORE_URL=http://127.0.0.1:<port>)\n(resets and writes to that store; the address is required so it never touches a store by accident)");
  process.exit(process.argv.slice(2).some((a) => a === "-h" || a === "--help") ? 0 : 2);
}
const BASE = (baseArg || process.env.STORE_URL).replace(/\/$/, "");
const spec = parseYaml(readFileSync(join(root, "contract", "openapi.yaml"), "utf8"));
const schemas = spec.components.schemas;

// ---- schema check (the subset openapi.yaml uses) ----
function checkSchema(schema, v, at = "$") {
  if (schema.$ref) return checkSchema(schemas[schema.$ref.split("/").pop()], v, at);
  const errs = [];
  for (const sub of schema.allOf || []) errs.push(...checkSchema(sub, v, at));
  if (v === null && schema.nullable) return errs;
  const t = schema.type;
  const is = { object: v && typeof v === "object" && !Array.isArray(v), array: Array.isArray(v), string: typeof v === "string",
    number: typeof v === "number", integer: Number.isInteger(v), boolean: typeof v === "boolean" };
  if (t && !is[t]) return [...errs, `${at}: expected ${t}, got ${JSON.stringify(v)?.slice(0, 40)}`];
  if (schema.enum && !schema.enum.includes(v)) errs.push(`${at}: ${JSON.stringify(v)} not in [${schema.enum}]`);
  if (schema.pattern && typeof v === "string" && !new RegExp(schema.pattern).test(v)) errs.push(`${at}: does not match ${schema.pattern}`);
  if (schema.minimum !== undefined && v < schema.minimum) errs.push(`${at}: below ${schema.minimum}`);
  if (t === "object") {
    for (const k of schema.required || []) if (!(k in v)) errs.push(`${at}: missing ${k}`);
    for (const [k, sub] of Object.entries(schema.properties || {})) if (k in v) errs.push(...checkSchema(sub, v[k], `${at}.${k}`));
  }
  if (t === "array" && schema.items) v.forEach((x, i) => errs.push(...checkSchema(schema.items, x, `${at}[${i}]`)));
  return errs;
}

function schemaErrors(ref, body) {
  const m = /^(\w+)(?:@(\w+)|(\[\]))?$/.exec(ref);
  const s = { $ref: `#/components/schemas/${m[1]}` };
  if (m[3]) return checkSchema({ type: "array", items: s }, body);
  return checkSchema(s, m[2] ? body?.[m[2]] : body, m[2] ? `$.${m[2]}` : "$");
}

// ---- partial deep match with matchers ----
function matchValue(want, got, at = "$") {
  if (typeof want === "string" && /^<.*>$/.test(want)) return matcher(want, got, at);
  if (Array.isArray(want)) {
    if (!Array.isArray(got)) return [`${at}: expected array`];
    const open = want[want.length - 1] === "<...>";
    const items = open ? want.slice(0, -1) : want;
    if (open ? got.length < items.length : got.length !== items.length) return [`${at}: array length ${got.length}, wanted ${open ? ">=" : ""}${items.length}`];
    return items.flatMap((w, i) => matchValue(w, got[i], `${at}[${i}]`));
  }
  if (want && typeof want === "object") {
    if (!got || typeof got !== "object") return [`${at}: expected object`];
    return Object.entries(want).flatMap(([k, w]) => matchValue(w, got[k], `${at}.${k}`));
  }
  return want === got ? [] : [`${at}: expected ${JSON.stringify(want)}, got ${JSON.stringify(got)}`];
}

function matcher(m, got, at) {
  const [, name, arg = ""] = /^<([\w|-]+?)(?::(.*))?>$/.exec(m) || [];
  const ok = {
    any: got !== undefined,
    string: typeof got === "string",
    number: typeof got === "number",
    "sha256|null": got === null || /^[0-9a-f]{64}$/.test(got),
    absent: got === undefined || got === null,
    contains: typeof got === "string" && got.toLowerCase().includes(arg.toLowerCase()),
    "contains-all": typeof got === "string" && arg.split(",").every((p) => got.toLowerCase().includes(p.toLowerCase())),
  }[name];
  return ok ? [] : [`${at}: ${m} not satisfied by ${JSON.stringify(got)}`];
}

const fill = (v, vars) => typeof v === "string" ? v.replace(/\{\{(\w+)\}\}/g, (_, k) => vars[k])
  : Array.isArray(v) ? v.map((x) => fill(x, vars)) : v && typeof v === "object" ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, fill(x, vars)])) : v;

const dig = (obj, path) => path.split(".").reduce((o, k) => o?.[k], obj);

// ---- run one fixture file ----
async function runFixture(fx, vars) {
  let cookie = null;
  const failures = [];
  for (const [i, step] of fx.steps.entries()) {
    let req = fill(step.request, vars);
    const headers = { ...(req.headers || {}) };
    if ((req.body !== undefined || req.rawBody !== undefined) && !Object.keys(headers).some((h) => h.toLowerCase() === "content-type")) headers["Content-Type"] = "application/json";
    // rawBodyPadTo: pad rawBody with trailing spaces to this many characters (an oversized body without a huge fixture file).
    if (req.rawBodyPadTo) req = { ...req, rawBody: (req.rawBody || "").padEnd(req.rawBodyPadTo, " ") };
    if (cookie) headers.Cookie = cookie;
    let res, text;
    try {
      res = await fetch(BASE + req.path, { method: req.method, headers, body: req.rawBody !== undefined ? req.rawBody : req.body === undefined ? undefined : JSON.stringify(req.body), signal: AbortSignal.timeout(10000) });
      text = await res.text();
    } catch (e) {
      const why = e && (e.name === "TimeoutError" || e.name === "AbortError") ? "timed out after 10 s with no answer" : `request failed: ${e.cause?.code || e.message}`;
      failures.push(`step ${i + 1} ${req.method} ${req.path}: ${why}`);
      break;
    }
    const set = res.headers.get("set-cookie") || "";
    const sid = /nb_sid=([^;]+)/.exec(set);
    if (sid) cookie = `nb_sid=${sid[1]}`;
    let body; try { body = JSON.parse(text); } catch { body = undefined; }
    const exp = fill(step.expect, vars);
    const errs = [];
    if (res.status !== exp.status) errs.push(`status ${res.status}, wanted ${exp.status}`);
    if (exp.schema) errs.push(...schemaErrors(exp.schema, body));
    if (exp.match !== undefined) errs.push(...matchValue(exp.match, body));
    if (exp.cookie && !new RegExp(`${exp.cookie}=`).test(set)) errs.push(`cookie ${exp.cookie} not set`);
    for (const [h, want] of Object.entries(exp.headers || {})) errs.push(...matchValue(want, res.headers.get(h) ?? undefined, `header ${h}`));
    if (errs.length) failures.push(`step ${i + 1} ${req.method} ${req.path}: ${errs.join("; ")}`);
    else for (const [k, p] of Object.entries(step.save || {})) vars[k] = dig(body, p);
    if (errs.length) break;
  }
  return failures;
}

// The scenarios file is an ES module in a folder with no package.json; load it as a data URL so
// this works on every Node 18+ regardless of module detection.
async function loadScenarios() {
  const code = readFileSync(join(root, "contract", "scenarios.js"), "utf8");
  return import("data:text/javascript;base64," + Buffer.from(code).toString("base64"));
}

const reasonOf = (e) => e && (e.name === "TimeoutError" || e.name === "AbortError") ? "timed out after 10 s with no answer" : `request failed: ${e?.cause?.code || e?.message}`;

async function main() {
  const reset = await fetch(BASE + "/demo/reset", { method: "POST", signal: AbortSignal.timeout(10000) }).catch((e) => { console.error(`FAIL cannot reach ${BASE}: ${e.cause?.code || e.message}`); process.exit(2); });
  const demo = reset.status === 200;
  console.log(`store ${BASE}  demo ${demo ? "on" : "off"}`);
  let pass = 0, fail = 0, skip = 0;
  const run = Date.now().toString(36);
  const files = readdirSync(join(root, "contract", "fixtures")).filter((f) => f.endsWith(".json")).sort();
  // runId is unique per fixture file too: several files sign up the same email, and without DEMO=1 there is no reset between files.
  for (const f of files) {
    const fx = JSON.parse(readFileSync(join(root, "contract", "fixtures", f), "utf8"));
    if ((fx.demo && !demo) || (fx.onlyWithoutDemo && demo)) { skip++; console.log(`SKIP ${fx.name}`); continue; }
    let failures;
    try {
      if (demo) await fetch(BASE + "/demo/reset", { method: "POST", signal: AbortSignal.timeout(10000) });
      failures = await runFixture(fx, { runId: run + files.indexOf(f) });
    } catch (e) { failures = [`reset or run failed: ${reasonOf(e)}`]; }
    if (failures.length) { fail++; console.log(`FAIL ${fx.name}\n  ${failures.join("\n  ")}`); } else { pass++; console.log(`PASS ${fx.name} (${fx.steps.length} steps)`); }
  }
  const { runScenario, SCENARIOS } = await loadScenarios();
  // Same rule as the engine page (scenarioIds in app/src/state/scenarioRun.js): the S<number> keys, in numeric order.
  const ids = Object.keys(SCENARIOS).filter((k) => /^S\d+$/.test(k)).sort((a, b) => Number(a.slice(1)) - Number(b.slice(1)));
  for (const id of ids) {
    if (!demo) { skip++; console.log(`SKIP scenario ${id} (needs DEMO=1)`); continue; }
    let r;
    try { r = await runScenario(id, { baseUrl: BASE, fetch: (u, o = {}) => fetch(u, { ...o, signal: AbortSignal.timeout(10000) }) }); } catch (e) { r = { ok: false, results: [{ ok: false, step: "(request)", httpStatus: 0, body: reasonOf(e) }] }; }
    const bad = r.results.find((x) => !x.ok);
    if (r.ok) { pass++; console.log(`PASS scenario ${id} (${r.results.length} steps)`); }
    else { fail++; console.log(`FAIL scenario ${id}: step ${bad.step} got HTTP ${bad.httpStatus} ${JSON.stringify(bad.body)}`); }
  }
  console.log(`\n${pass} passed, ${fail} failed, ${skip} skipped (fixtures + scenarios)`);
  process.exit(fail ? 1 : 0);
}
main();
