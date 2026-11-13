// Demo drawer: operator-only tooling, rendered only when the store runs with DEMO=1.
// DEMO probe: GET /demo/scenarios.js answers 200 only under DEMO=1 (JSON 404 otherwise), and is
// also the scenario module (contract/scenarios.js) the buttons run through the store's own routes.
// The feed reads this store's own same-origin GET /demo/log?since=N (the store fetches the mock's log server-side).
(async function demoDrawer() {
  let mod;
  try {
    const probe = await fetch("/demo/scenarios.js", { credentials: "same-origin" });
    if (!probe.ok) return;
    mod = await import("/demo/scenarios.js");
  } catch { return; }

  // Language ribbon (demo mode only): shows which backend serves this store. Driven by `backend` from /api/config.
  const LANGUAGES = {
    node: { language: "Node.js", framework: "Express", color: "#2F6B2E" },
    python: { language: "Python", framework: "Flask", color: "#2B5F8C" },
    flask: { language: "Python", framework: "Flask", color: "#2B5F8C" },
    ruby: { language: "Ruby", framework: "Rails", color: "#B52A24" },
    php: { language: "PHP", framework: "Slim", color: "#5B5F97" },
    dotnet: { language: ".NET", framework: "ASP.NET Core", color: "#512BD4" },
  };
  (async function languageRibbon() {
    try {
      const cfg = await (await fetch("/api/config", { credentials: "same-origin" })).json();
      const lang = LANGUAGES[String(cfg.backend || "").toLowerCase()];
      if (!lang) return;
      const tint = (hex) => "#" + [1, 3, 5].map((i) => Math.round(255 - (255 - parseInt(hex.substr(i, 2), 16)) * 0.12).toString(16).padStart(2, "0")).join("");
      const style = document.createElement("style");
      style.textContent = `
      #demo-language{background:${lang.color};color:#fff;font:600 12.5px/1 var(--sans,sans-serif);letter-spacing:.02em}
      #demo-language span{display:block;max-width:1080px;margin:0 auto;padding:7px 20px}
      header.shop{background:${tint(lang.color)}}`;
      document.head.appendChild(style);
      const ribbon = document.createElement("div");
      ribbon.id = "demo-language";
      ribbon.setAttribute("aria-hidden", "true");
      ribbon.innerHTML = "<span></span>";
      ribbon.firstChild.textContent = `${lang.language} \u00b7 ${lang.framework} store`;
      const shopHeader = document.querySelector("header.shop");
      if (shopHeader) shopHeader.parentNode.insertBefore(ribbon, shopHeader); else document.body.prepend(ribbon);
      // The store rewrites document.title on every view change, so keep the prefix in place.
      const prefix = `${lang.language} \u00b7 `;
      const fixTitle = () => { if (!document.title.startsWith(prefix)) document.title = prefix + document.title; };
      fixTitle();
      new MutationObserver(fixTitle).observe(document.querySelector("title"), { childList: true, characterData: true, subtree: true });
    } catch { /* the ribbon is optional */ }
  })();

  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  // The scenarios the store offers (S0, S1, ...), in numeric order, from the one scenario module.
  const scenarioIds = () => Object.keys(mod.SCENARIOS).filter((k) => /^S\d+$/.test(k)).sort((x, y) => Number(x.slice(1)) - Number(y.slice(1)));
  const NO_CALLS = "No calls yet. The clean store makes no Sift calls; launch an instrumented version from the engine.";
  const PERSONAS = { "": { label: "My browser (no persona)" }, A: { label: "A, returning Mac user", ...mod.PERSONAS.A }, H: { label: "H, Windows user", ...mod.PERSONAS.H } };
  const st = { open: false, persona: "", running: null, steps: [], cursor: 0, feed: [], mockUp: null, config: null, paid: undefined, shipNote: "" };

  const css = document.createElement("style");
  css.textContent = `
  body{padding-bottom:44px}
  #demo-drawer{position:fixed;left:0;right:0;bottom:0;z-index:70;background:#14181c;color:#dfe3e7;border-top:2px solid #f5d98a;font:13px/1.45 var(--sans,sans-serif)}
  #demo-toggle{display:flex;align-items:center;gap:10px;width:100%;background:none;border:0;color:#dfe3e7;font:600 13px var(--sans,sans-serif);padding:10px 16px;cursor:pointer;text-align:left}
  #demo-toggle b{color:#f5d98a;letter-spacing:.06em;text-transform:uppercase;font-size:12px}
  #demo-toggle span{color:#a9b0b7;font-weight:400}
  #demo-body{display:grid;grid-template-columns:320px 1fr;gap:20px;padding:4px 16px 14px;max-height:52vh;overflow:auto}
  #demo-body[hidden]{display:none}
  @media(max-width:900px){#demo-body{grid-template-columns:1fr}}
  #demo-drawer h4{margin:0 0 6px;font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:#a9b0b7}
  #demo-drawer .dsec{margin-bottom:12px}
  #demo-drawer button.dbtn{display:block;width:100%;text-align:left;background:#222a31;color:#e6eaee;border:1px solid #4a5560;border-radius:7px;padding:7px 10px;margin-bottom:5px;font:500 12.5px var(--sans,sans-serif);cursor:pointer}
  #demo-drawer button.dbtn:hover{background:#2c363f}
  #demo-drawer button.dbtn:disabled{opacity:.5;cursor:default}
  #demo-drawer button.dbtn.danger{background:#3a2224}
  #demo-drawer select{background:#222a31;color:#e6eaee;border:1px solid #4a5560;padding:6px 8px;font:13px var(--sans,sans-serif)}
  #demo-drawer :focus-visible{outline:3px solid #f5d98a;outline-offset:2px}
  #demo-steps{list-style:none;margin:6px 0 0;padding:0;font-size:12px}
  #demo-steps li{padding:2px 0}
  .dpass{color:#6fd49a}.dfail{color:#ff8f86}.dwait{color:#a9b0b7}
  #demo-feed{background:#0d1013;border:1px solid #2a3138;border-radius:8px;min-height:120px;max-height:34vh;overflow:auto}
  #demo-feed table{font-size:12px}#demo-feed th,#demo-feed td{border-color:#222a31;padding:5px 8px;color:#d6dbe0}
  #demo-feed th{position:sticky;top:0;background:#0d1013;color:#a9b0b7}
  .dnote{color:#a9b0b7;font-size:12px;padding:10px}`;
  document.head.appendChild(css);

  const box = document.createElement("section");
  box.id = "demo-drawer";
  box.setAttribute("aria-label", "Demo controls");
  document.body.appendChild(box);

  const persona = () => PERSONAS[st.persona];
  window.storeApp.headers = () => (st.persona ? { "X-Demo-Client-Ip": persona().ip, "X-Demo-User-Agent": persona().userAgent } : {});

  const TIMEOUT_MS = 10000;
  const HINT = "The store may have been replaced. Reload the page and run again.";
  // fetch with a timeout; `outer` (optional AbortSignal) is the operator's Stop. Rejects with Error.reason = "timeout" | "stopped" | "network".
  async function tfetch(url, init = {}, outer, ms = TIMEOUT_MS) {
    const ac = new AbortController();
    let why = null;
    const timer = setTimeout(() => { why = "timeout"; ac.abort(); }, ms);
    const onOuter = () => { why = "stopped"; ac.abort(); };
    if (outer) { if (outer.aborted) onOuter(); else outer.addEventListener("abort", onOuter, { once: true }); }
    try { return await fetch(url, { ...init, signal: ac.signal }); }
    catch (e) { const err = new Error(why || e.message || "network error"); err.reason = why || "network"; throw err; }
    finally { clearTimeout(timer); if (outer) outer.removeEventListener("abort", onOuter); }
  }
  async function getJson(url, ms = 3000) { const r = await tfetch(url, {}, null, ms); if (!r.ok) throw new Error(r.status); return r.json(); }
  const sameStore = (a, b) => !a || !b || (a.buildHash === b.buildHash && a.version === b.version);
  async function refreshConfig() {
    try { st.config = await getJson("/api/config"); st.configErr = false; } catch { st.configErr = true; }
    const w = document.getElementById("demo-which");
    if (w) w.innerHTML = storeLine();
  }
  refreshConfig();

  function stepsHtml() {
    return st.steps.map((s) => `<li class="${s.state === "pass" ? "dpass" : s.state === "fail" ? "dfail" : "dwait"}">${s.state === "pass" ? "&#10003; pass" : s.state === "fail" ? "&#10007; fail" : s.state === "skipped" ? "&#8211; skipped" : "&#8226; wait"}: ${esc(s.label)}${s.note ? " (" + esc(s.note) + ")" : ""}</li>`).join("");
  }
  function feedHtml() {
    if (!st.feed.length) return `<div class="dnote">${NO_CALLS}${st.mockUp === false ? " (The mock is not reachable.)" : ""}</div>`;
    return `<table><thead><tr><th>Event</th><th>HTTP</th><th>Sift status</th><th>Simulated score</th></tr></thead><tbody>${st.feed.map((r) => {
      const ok = r.httpStatus < 300;
      const sc = typeof r.score === "number" ? Math.round(r.score * 100) : r.score && typeof r.score === "object" ? esc(JSON.stringify(r.score)) : "-";
      return `<tr><td class="mono">${esc(r.event || r.path)}</td><td class="${ok ? "dpass" : "dfail"}">${esc(r.httpStatus)}</td><td class="${r.siftStatus === 0 ? "dpass" : "dfail"}">${esc(r.siftStatus)}</td><td>${sc}</td></tr>`;
    }).join("")}</tbody></table>`;
  }
  const storeLine = () => st.configErr ? "Which store is this? Not reachable right now" : st.config ? `Which store is this? ${esc(st.config.backend)} backend, version ${esc(st.config.version)}${st.config.buildHash ? ", build " + esc(st.config.buildHash.slice(0, 8)) : ""}` : "Which store is this? Unknown";

  const RESET_HINT = "Clears accounts you created (signup), their carts and orders, and the mock's log. Seeded accounts return.";

  // Ship order: lists the signed-in user's paid orders; the button plays the shipping partner (POST /demo/orders/:id/ship).
  const shipHtml = () => {
    if (st.paid === undefined) return `<div class="dnote" style="padding:0">Loading orders.</div>`;
    const note = st.shipNote ? `<div class="dnote" style="padding:6px 0 0" aria-live="polite">${esc(st.shipNote)}</div>` : "";
    if (st.paid === null) return `<div class="dnote" style="padding:0">Sign in to see your paid orders.</div>${note}`;
    if (!st.paid.length) return `<div class="dnote" style="padding:0">No paid orders to ship. Pay an order first.</div>${note}`;
    return `<label for="demo-ship-order" class="dnote" style="padding:0">Paid orders</label><br>
      <select id="demo-ship-order">${st.paid.map((o) => `<option value="${esc(o.id)}">${esc(o.id)} (${esc("$" + Number(o.total).toFixed(2))})</option>`).join("")}</select>
      <button class="dbtn" type="button" id="demo-ship" style="margin-top:6px" ${st.running ? "disabled" : ""}>Ship order</button>${note}`;
  };
  // The signed-in user's paid orders (null when nobody is signed in), read through the store's own API.
  async function refreshPaid() {
    const before = JSON.stringify(st.paid);
    try {
      const r = await tfetch("/api/account", { credentials: "same-origin" }, null, 3000);
      if (!r.ok) st.paid = null;
      else st.paid = (await getJson("/api/orders")).orders.filter((x) => x.status === "paid");
    } catch { return; /* keep the last list */ }
    const body = document.getElementById("demo-ship-body");
    if (!body || JSON.stringify(st.paid) === before) return;  // unchanged: leave an open dropdown alone
    const sel = document.getElementById("demo-ship-order");
    const keep = sel && sel.value;
    body.innerHTML = shipHtml();
    const again = document.getElementById("demo-ship-order");
    if (again && keep && st.paid.some((x) => x.id === keep)) again.value = keep;
  }
  async function ship() {
    const sel = document.getElementById("demo-ship-order");
    if (!sel || !sel.value) return;
    const id = sel.value;
    st.running = "ship"; paint();
    try {
      const r = await tfetch(`/demo/orders/${encodeURIComponent(id)}/ship`, { method: "POST", credentials: "same-origin" });
      const body = await r.json().catch(() => ({}));
      st.shipNote = r.ok ? `Order ${id} shipped. Its status is now Shipped.` : `Could not ship ${id}: ${body.error || "HTTP " + r.status}.`;
    } catch (e) { st.shipNote = `Could not ship ${id}: ${e.reason === "timeout" ? "timed out after 10 s" : "network error"}.`; }
    st.running = null;
    st.paid = undefined;  // force the section to redraw with the new note
    await refreshPaid();
    try { await window.storeApp.refresh(); window.storeApp.render(); } catch {}
  }

  function paint() {
    const busy = !!st.running;
    box.innerHTML = `<button id="demo-toggle" type="button" aria-expanded="${st.open}" aria-controls="demo-body"><b>Demo controls</b><span>operator only, DEMO=1</span><span style="flex:1"></span><span>${st.open ? "Collapse &#9662;" : "Expand &#9652;"}</span></button>
    <div id="demo-body" ${st.open ? "" : "hidden"}><div>
      <div class="dsec"><h4>Scenarios</h4>
        ${scenarioIds().map((id) => `<button class="dbtn" type="button" data-run="${esc(id)}" ${busy ? "disabled" : ""}>${esc(id)} ${esc(mod.SCENARIOS[id].title || "")}</button>`).join("")}
        <button class="dbtn danger" type="button" id="demo-reset" ${busy ? "disabled" : ""} title="${esc(RESET_HINT)}">Reset demo</button>
        <div class="dnote" style="padding:0 0 6px">${esc(RESET_HINT)}</div>
        ${st.running && st.running !== "reset" && st.running !== "ship" ? '<button class="dbtn danger" type="button" id="demo-stop">Stop</button>' : ""}
        <ol id="demo-steps" aria-live="polite">${stepsHtml()}</ol>${st.hint ? `<div class="dfail" style="font-size:12px">${esc(st.hint)}</div>` : ""}</div>
      <div class="dsec"><h4>Fulfillment</h4><div id="demo-ship-body">${shipHtml()}</div></div>
      <div class="dsec"><h4>Persona for manual shopping</h4>
        <label for="demo-persona" class="dnote" style="padding:0">Client IP and browser sent with each store call</label><br>
        <select id="demo-persona">${Object.entries(PERSONAS).map(([k, p]) => `<option value="${k}" ${k === st.persona ? "selected" : ""}>${esc(p.label)}${p.ip ? " " + p.ip : ""}</option>`).join("")}</select></div>
      <div class="dnote" style="padding:0" id="demo-which">${storeLine()}</div>
    </div><div><div class="dsec"><h4>Live feed of Sift calls</h4><div id="demo-feed" role="log" aria-live="polite">${feedHtml()}</div></div></div></div>`;
  }

  async function run(id) {
    const sc = mod.SCENARIOS[id];
    const ac = new AbortController();
    st.abort = ac; st.hint = ""; st.running = id;
    st.steps = sc.steps.map((s) => ({ id: s.id, label: s.label, state: "wait" }));
    paint();
    const fail = (note, hint) => {
      const cur = st.steps.find((x) => x.state === "wait");
      if (cur) { cur.state = "fail"; cur.note = note; }
      st.steps.forEach((x) => { if (x.state === "wait") x.state = "skipped"; });
      st.hint = hint || "";
    };
    let beforeP = Promise.resolve(st.config);
    try {
      beforeP = getJson("/api/config", 1500).catch(() => st.config);  // baseline, not awaited so a hung store cannot delay step 1
      const out = await mod.runScenario(id, {
        baseUrl: location.origin, browser: true,
        fetch: (url, init) => tfetch(url, init, ac.signal),
        onStep: (r) => {
          const s = st.steps.find((x) => x.id === r.step);
          s.state = r.ok ? "pass" : "fail";
          s.note = r.ok ? "HTTP " + r.httpStatus : "HTTP " + r.httpStatus + (r.body?.error ? ", " + r.body.error : "");
          paint();
        },
      });
      if (!out.ok) {
        const bad = st.steps.find((x) => x.state === "fail");
        const before = await beforeP;
        const now = await getJson("/api/config", 1000).catch(() => null);
        if (now && !sameStore(before, now)) { bad.note = "store was restarted"; st.hint = HINT; }
        else if (/^HTTP 401/.test(bad.note || "")) { bad.note = "store was restarted"; st.hint = HINT; }
        else if (/^HTTP \d/.test(bad.note || "")) { st.hint = "Check the store, then run again."; }
        st.steps.forEach((x) => { if (x.state === "wait") x.state = "skipped"; });
      } else {
        const now = await getJson("/api/config", 1000).catch(() => null);
        if (now) st.config = now;
      }
    } catch (e) {
      if (e.reason === "stopped") fail("stopped by operator", "");
      else if (e.reason === "timeout") fail("timed out after 10 s", HINT);
      else fail("network error", HINT);
      const lbl = st.steps.find((x) => x.state === "fail");
      if (lbl && e.reason !== "stopped") {
        const before = await beforeP;
        const now = await getJson("/api/config", 1000).catch(() => null);
        if (now && !sameStore(before, now)) lbl.note = "store was restarted";
      }
    } finally {
      st.running = null; st.abort = null;
      paint();
    }
    try { await tfetch("/api/config", {}, null, 3000).then(async (r) => { if (r.ok) { st.config = await r.json(); st.configErr = false; } }); } catch {}
    try { await window.storeApp.refresh(); window.storeApp.render(); } catch {}
    paint();
    poll();
    refreshPaid();
  }

  async function reset() {
    st.running = "reset"; paint();
    st.hint = "";
    try { await tfetch("/demo/reset", { method: "POST", credentials: "same-origin" }); } catch (e) { st.hint = "Reset failed (" + (e.reason === "timeout" ? "timed out after 10 s" : "network error") + "). " + HINT; }
    st.steps = []; st.feed = []; st.cursor = 0;
    st.running = null;
    paint();
    try { await window.storeApp.refresh(); location.hash = "#/"; window.storeApp.render(); } catch {}
    paint();
    refreshConfig();
    st.shipNote = ""; st.paid = undefined; refreshPaid();
  }

  async function poll() {
    try {
      const { records, nextSeq, mockUp } = await getJson(`/demo/log?since=${st.cursor}`);
      st.mockUp = mockUp !== false;
      if (!st.mockUp) { const f = document.getElementById("demo-feed"); if (f) f.innerHTML = feedHtml(); return; }
      if (nextSeq - 1 < st.cursor) { st.cursor = 0; st.feed = []; return poll(); }  // the mock was reset
      if (records.length) { st.cursor = nextSeq - 1; st.feed = [...records.reverse(), ...st.feed].slice(0, 100); }
    } catch { st.mockUp = false; }
    const f = document.getElementById("demo-feed");
    if (f) f.innerHTML = feedHtml();
  }

  box.addEventListener("click", (e) => {
    const t = e.target.closest("button");
    if (!t) return;
    if (t.id === "demo-toggle") { st.open = !st.open; paint(); document.getElementById("demo-toggle").focus(); if (st.open) { poll(); refreshPaid(); } }
    else if (t.dataset.run) run(t.dataset.run);
    else if (t.id === "demo-reset") reset();
    else if (t.id === "demo-ship") ship();
    else if (t.id === "demo-stop") { if (st.abort) st.abort.abort(); }
  });
  box.addEventListener("change", (e) => { if (e.target.id === "demo-persona") { st.persona = e.target.value; paint(); document.getElementById("demo-persona").focus(); } });
  setInterval(() => { if (st.open) poll(); }, 2000);
  setInterval(() => { if (st.open) { refreshConfig(); refreshPaid(); } }, 5000);
  paint();
})();
