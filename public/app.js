// Northbeam Goods storefront: one page, hash routes (#/ shop, #/cart, #/signup, #/login,
// #/checkout, #/thanks, #/orders, #/account). Talks only to the shared /api contract.
// window.storeApp lets the optional demo drawer (demo-drawer.js) add persona headers and refresh the page.
const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const money = (n) => `$${Number(n).toFixed(2)}`;
const TINTS = { Apparel: "#6f8f7a", Footwear: "#c2762f", Gear: "#3f6f8f" };

window.storeApp = { headers: null, refresh: null, render: null };
const state = { user: null, products: [], cart: [], promotion: null, discount: 0, orders: null, lastOrder: null, flash: null, notices: [], demo: null };
const STATUS = { saved: "Saved, not paid", pending: "Processing", paid: "Paid", payment_failed: "Payment failed", canceled: "Canceled", fulfilled: "Shipped", chargeback: "Under dispute" };
const NOTICES = { password_changed: "Your password was changed", verification_code: "A verification code was sent" };
const CARDS = { ok: { token: "tok_visa", cardBin: "411111", cardLast4: "1111" }, declined: { token: "tok_declined", cardBin: "411111", cardLast4: "0002" } };
// The pay form defaults to the card on file ("saved": no payment is sent, the store uses the account's card).
const savedCard = () => (state.user && state.user.paymentMethod) || null;
const cardSelect = () => { const sc = savedCard(); return `<label>Card<select name="card" data-testid="card-select">${sc ? `<option value="saved">Using card ending ${esc(sc.cardLast4)}</option>` : ""}<option value="ok">${sc ? "Enter another card: " : ""}Visa ending 1111</option><option value="declined">${sc ? "Enter another card: " : ""}Visa ending 0002 (declined)</option></select></label>`; };
const cardOk = (f) => f.card === "saved" ? !!savedCard() : !!CARDS[f.card];
// The body of the pay request: the chosen test card, or nothing for the saved card.
const payBody = (f) => (f.card === "saved" ? {} : { payment: CARDS[f.card] });
const REQUEST_TIMEOUT_MS = 15000;

// Every request has a 15 second timeout and always ends in either data or an Error with a plain message.
async function api(method, url, body) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), REQUEST_TIMEOUT_MS);
  try {
    let res, text;
    try {
      res = await fetch(url, { method, headers: { "Content-Type": "application/json", ...(window.storeApp.headers?.() || {}) }, body: body ? JSON.stringify(body) : undefined, credentials: "same-origin", signal: ac.signal });
      text = await res.text();
    } catch (err) {
      throw new Error(err && err.name === "AbortError" ? "The store took too long to answer (15 seconds). Please check Orders and try again." : "Could not reach the store. Check your connection and try again.");
    }
    let data = {};
    if (text) {
      try { data = JSON.parse(text); }
      catch { throw new Error(res.ok ? "The store sent an unreadable answer. Please try again." : `Request failed (${res.status}).`); }
    }
    if (!res.ok) throw Object.assign(new Error(data.error || `Request failed (${res.status}).`), { data });
    return data;
  } finally { clearTimeout(timer); }
}

const banner = (kind, msg) => (msg ? `<div class="banner ${kind}" role="${kind === "bad" ? "alert" : "status"}">${esc(msg)}</div>` : "");
const product = (id) => state.products.find((p) => p.id === id) || { name: id, price: 0 };
// Address forms. Fields are named `<prefix>name`, `<prefix>address1` ... so one page can hold several.
// A field the account does not have is filled from the demo defaults (config.demoDefaults, DEMO=1 only) when present, else left empty.
const addressInputs = (prefix, a, { required = false, nameFallback = false } = {}) => {
  const dd = (state.demo && state.demo.address) || {};
  const req = required ? " required" : "";
  const v = (k) => esc(a[k] ?? (k === "name" && nameFallback ? state.user && state.user.name : undefined) ?? dd[k] ?? "");
  return `<input type="hidden" name="${prefix}country" value="${esc(a.country ?? dd.country ?? "US")}" />
    <label>Name<input name="${prefix}name" value="${v("name")}" autocomplete="name"${req} /></label>
    <label>Address<input name="${prefix}address1" value="${v("address1")}"${req} /></label>
    <label>City<input name="${prefix}city" value="${v("city")}"${req} /></label>
    <label>State<input name="${prefix}region" value="${v("region")}"${req} /></label>
    <label>ZIP<input name="${prefix}zipcode" value="${v("zipcode")}"${req} /></label>`;
};
// The fields of one address out of a form's values (undefined when the section is empty).
const addressPart = (f, prefix) => Object.fromEntries(["country", "name", "address1", "city", "region", "zipcode"].map((k) => [k, f[prefix + k]]));
const addressEmpty = (f, prefix) => ["name", "address1", "city", "region", "zipcode"].every((k) => !String(f[prefix + k] ?? "").trim());
const subtotal = () => state.cart.reduce((sum, l) => sum + product(l.productId).price * l.qty, 0);
// The cart replies carry the lines, and the promotion and discount while a promo code applies.
// A promo that no longer applies (applies: false) is still shown, with its reason and Remove, but takes nothing off.
const promoApplies = () => !!state.promotion && state.promotion.applies !== false;
const setCart = (r) => { state.cart = r.cart; state.promotion = r.promotion || null; state.discount = promoApplies() ? Number(r.discount) || 0 : 0; };
const total = () => Math.max(0, Math.round(subtotal() * 100 - state.discount * 100) / 100);
const discountRow = (cols = "") => (promoApplies() ? `<tr data-testid="discount-row"><td${cols}>Promo ${esc(state.promotion.code)}</td><td>&minus;${money(state.discount)}</td></tr>` : "");

function renderHeader() {
  const u = state.user;
  $("#whoami").textContent = u ? `${u.name} · ${u.email}` : "Guest";
  $("#authlinks").innerHTML = u
    ? `<button class="btn ghost sm" id="logout" type="button">Log out</button>`
    : `<a class="btn ghost sm" href="#/login">Log in</a> <a class="btn sm" href="#/signup">Sign up</a>`;
  $("#cartcount").textContent = state.cart.reduce((n, l) => n + l.qty, 0);
}

async function refresh() {
  const [cartReply, account] = await Promise.all([api("GET", "/api/cart"), api("GET", "/api/account").catch(() => null)]);
  setCart(cartReply);
  state.user = account ? account.user : null;
  state.notices = account ? account.notices || [] : [];
  renderHeader();
}

// ---- views: each returns HTML; wire(root) attaches handlers ----
const views = {
  shop: () => `<h1>Shop</h1><div class="grid">${state.products.map((p) => `
    <div class="pcard"><div class="img" style="background:${TINTS[p.category] || "#8a6a43"}">${esc(p.name[0])}${p.image ? `<img src="/${esc(p.image)}" alt="${esc(p.name)}" loading="lazy" onerror="this.remove()">` : ""}</div>
    <div class="pbody"><span class="cat">${esc(p.category)}</span><span class="pname">${esc(p.name)}</span>
    <p>${esc(p.description || "")}</p><div class="row sp"><span>${money(p.price)}</span>
    <button class="btn sm" data-add="${esc(p.id)}">Add to cart</button></div></div></div>`).join("")}</div>
    <div id="msg"></div>`,

  cart: () => {
    if (!state.cart.length) return `<h1>Your cart</h1><div id="msg"></div><div class="panel"><p>Your cart is empty. <a href="#/">Keep shopping</a>.</p></div>`;
    const rows = state.cart.map((l) => `<tr><td>${esc(product(l.productId).name)}</td>
      <td>${l.qty} <button class="btn ghost sm" type="button" data-add="${esc(l.productId)}" aria-label="Add one more ${esc(product(l.productId).name)}">+1</button></td>
      <td>${money(product(l.productId).price * l.qty)}</td>
      <td><button class="btn ghost sm" type="button" data-remove="${esc(l.productId)}" aria-label="Remove ${esc(product(l.productId).name)} from the cart">Remove</button></td></tr>`).join("");
    return `<h1>Your cart</h1><div id="msg"></div><div class="panel"><table><tr><th>Item</th><th>Qty</th><th>Price</th><th></th></tr>${rows}
      <tr><td colspan="2">Subtotal</td><td colspan="2">${money(subtotal())}</td></tr>
      ${promoApplies() ? `<tr data-testid="discount-row"><td colspan="2">Promo ${esc(state.promotion.code)}</td><td colspan="2">&minus;${money(state.discount)}</td></tr>` : ""}
      <tr><td colspan="2"><b>Total</b></td><td colspan="2"><b data-testid="cart-total">${money(total())}</b></td></tr></table>
      ${state.user ? `<form id="promo-form" class="row" style="gap:8px;align-items:flex-end;margin:12px 0"><div id="msg-promo" style="flex-basis:100%"></div>
      ${state.promotion && !promoApplies() ? `<span data-testid="promo-stale">Promo code <b>${esc(state.promotion.code)}</b> does not apply: ${esc(sentence(state.promotion.message || "it can no longer be used"))} Remove it to try another code.</span> <button class="btn ghost sm" type="button" id="remove-promo" data-testid="remove-promo">Remove</button>`
        : state.promotion ? `<span data-testid="promo-applied">Promo code <b>${esc(state.promotion.code)}</b> applied${state.promotion.description ? `: ${esc(state.promotion.description)}` : ""}.</span> <button class="btn ghost sm" type="button" id="remove-promo" data-testid="remove-promo">Remove</button>`
        : `<label style="margin:0">Promo code<input name="code" autocomplete="off" required data-testid="promo-input" /></label> <button class="btn sm" type="submit" data-testid="apply-promo">Apply</button>`}</form>` : ""}
      <p><a class="btn" href="#/checkout">Checkout</a> <a class="btn ghost" href="#/">Keep shopping</a></p></div>`;
  },

  signup: () => `<div class="panel" style="max-width:460px;margin:auto"><h1>Create account</h1><div id="msg"></div>
    <form id="signup-form"><label>Full name<input name="name" autocomplete="name" required /></label>
    <label>Email<input name="email" type="email" autocomplete="email" required /></label>
    <label>Phone<input name="phone" type="tel" autocomplete="tel" placeholder="415-555-0123" value="${esc((state.demo && state.demo.phone) || "")}" required /></label>
    <div class="hint">Required. Used for delivery updates.</div>
    <h3 style="margin-top:14px">Address (optional)</h3>
    <div class="hint">Saved as your billing and default shipping address. Leave blank to add it later under Account.</div>
    ${addressInputs("a_", {})}
    <label>Password<input name="password" type="password" autocomplete="new-password" required /></label>
    <p><button class="btn" type="submit">Create account</button></p></form>
    <p class="hint">Have an account? <a href="#/login">Log in</a></p></div>`,

  login: () => `<div class="panel" style="max-width:460px;margin:auto"><h1>Log in</h1><div id="msg"></div>
    <form id="login-form"><label>Email<input name="email" type="email" autocomplete="email" required /></label>
    <label>Password<input name="password" type="password" autocomplete="current-password" required /></label>
    <p><button class="btn" type="submit">Log in</button></p></form>
    <p class="hint">New here? <a href="#/signup">Create an account</a></p></div>`,

  checkout: () => {
    if (!state.user) return `<h1>Checkout</h1><p>Please <a href="#/login">log in</a> or <a href="#/signup">create an account</a> to check out.</p>`;
    if (!state.cart.length) return `<h1>Checkout</h1><p>Your cart is empty. <a href="#/">Keep shopping</a>.</p>`;
    const u = state.user;
    const ship = state.lastAddress || u.shippingAddress || {};
    const bill = u.billingAddress || {};
    return `<h1>Checkout</h1><div class="two"><div class="panel"><div id="msg"></div>
      <form id="checkout-form"><h3>Shipping address</h3>
      ${addressInputs("", ship, { required: true, nameFallback: true })}
      <label>Shipping<select name="shippingMethod"><option value="standard">Standard</option><option value="expedited">Expedited</option></select></label>
      <label style="display:flex;gap:8px;align-items:center;margin-top:10px"><input type="checkbox" name="billingSame" id="billing-same" checked style="width:auto" /> Billing address same as shipping</label>
      <fieldset id="billing-fields" disabled hidden style="border:0;padding:0;margin:0"><h3 style="margin-top:12px">Billing address</h3>
      ${addressInputs("b_", bill, { required: true })}</fieldset>
      <h3 style="margin-top:18px">Payment</h3>
      ${savedCard() ? `<p data-testid="saved-card-line">Using card ending ${esc(savedCard().cardLast4)}. Pick another card below to use a different one.</p>` : ""}
      ${cardSelect()}
      <div class="hint">Demo store: no real card is used. Only a token is sent.</div>
      <p><button class="btn" type="submit" value="pay" data-testid="place-order-pay">Place order and pay ${money(total())}</button>
      <button class="btn ghost" type="submit" value="save" data-testid="save-order">Save order, pay later</button></p>
      <div class="confirm-slot"></div>
      <div class="hint">Saving keeps the order under Orders, where you can change shipping and pay when you are ready.</div></form></div>
      <div class="panel"><h3>Order summary</h3><table>${state.cart.map((l) => `<tr><td>${esc(product(l.productId).name)} × ${l.qty}</td><td>${money(product(l.productId).price * l.qty)}</td></tr>`).join("")}
      ${discountRow()}<tr><td><b>Total</b></td><td><b>${money(total())}</b></td></tr></table></div></div>`;
  },

  thanks: () => {
    const o = state.lastOrder;
    if (!o) return `<h1>Order confirmation</h1><div class="panel"><p>No recent order. See <a href="#/orders">your orders</a>.</p></div>`;
    return `<div class="panel" style="max-width:640px;margin:auto"><div class="success-mark">&#10003;</div>
    <h1>Thank you${state.user ? ", " + esc(state.user.name.split(" ")[0]) : ""}.</h1>
    <p>Your order <b class="mono">${esc(o.id)}</b> is confirmed. Total ${money(o.total ?? 0)}.</p>
    <p><a class="btn" href="#/orders">View your orders</a> <a class="btn ghost" href="#/">Back to the shop</a></p></div>`;
  },

  orders: () => {
    if (!state.user) return `<h1>Orders</h1><div class="panel"><p><a href="#/login">Log in</a> to see your orders.</p></div>`;
    if (state.orders === null) return `<h1>Orders</h1><p>Loading your orders.</p>`;
    if (!state.orders.length) return `<h1>Orders</h1><div id="msg"></div><div class="panel"><p>You have no orders yet. <a href="#/">Start shopping</a>.</p></div>`;
    return `<h1>Orders</h1><div id="msg"></div>${state.orders.map((o) => {
      const a = o.shippingAddress || {};
      return `<div class="panel order" style="margin-bottom:16px" data-order="${esc(o.id)}"><div class="row sp"><h3 style="margin:0" class="mono">${esc(o.id)}</h3>
        <span class="pill ${esc(o.status)}">${esc(STATUS[o.status] || o.status)}</span></div>
        <p class="hint">${esc((o.createdAt || "").slice(0, 10))} · ${o.card ? "Card ending " + esc(o.card.last4) : "Not paid yet"} · ${esc(o.shippingMethod || "standard")} shipping</p>
        <table>${o.items.map((i) => `<tr><td>${esc(i.name)} × ${i.qty}</td><td>${money(i.price * i.qty)}</td></tr>`).join("")}
        ${o.promotion ? `<tr><td>Promo ${esc(o.promotion.code)}</td><td>&minus;${money(o.discount)}</td></tr>` : ""}
        <tr><td><b>Total</b></td><td><b>${money(o.total)}</b></td></tr></table>
        <p>Ship to: ${esc(a.name)}, ${esc(a.address1)}, ${esc(a.city)}, ${esc(a.region)} ${esc(a.zipcode)}</p>
        ${o.status === "saved" || o.status === "payment_failed" ? `<form class="pay-form" data-id="${esc(o.id)}">
        ${o.status === "payment_failed" ? `<p class="hint">The last payment was declined${o.declineCode ? " (" + esc(o.declineCode) + ")" : ""}. Try again.</p>` : ""}
        ${cardSelect()}<p><button class="btn sm" type="submit" data-testid="pay-order">Pay ${money(o.total)}</button></p><div class="confirm-slot"></div></form>` : ""}
        ${["saved", "payment_failed", "paid"].includes(o.status) ? `<form class="cancel-form" data-id="${esc(o.id)}"><p><button class="btn ghost sm" type="submit" data-testid="cancel-order">Cancel order</button></p><div class="confirm-slot"></div></form>` : ""}
        ${["saved", "payment_failed", "paid"].includes(o.status) ? `<details><summary>Change shipping</summary>
        <form class="ship-form" data-id="${esc(o.id)}"><label>Name<input name="name" value="${esc(a.name)}" required /></label>
        <label>Address<input name="address1" value="${esc(a.address1)}" required /></label>
        <label>City<input name="city" value="${esc(a.city)}" required /></label>
        <label>State<input name="region" value="${esc(a.region)}" required /></label>
        <label>ZIP<input name="zipcode" value="${esc(a.zipcode)}" required /></label>
        <label>Shipping<select name="shippingMethod"><option value="standard"${o.shippingMethod !== "expedited" ? " selected" : ""}>Standard</option><option value="expedited"${o.shippingMethod === "expedited" ? " selected" : ""}>Expedited</option></select></label>
        <p><button class="btn sm" type="submit" data-testid="save-shipping">Save shipping</button></p><div class="confirm-slot"></div></form></details>` : ""}</div>`;
    }).join("")}`;
  },

  account: () => !state.user ? `<h1>Account</h1><div class="panel"><p><a href="#/login">Log in</a> to see your account.</p></div>`
    : `<h1>Account</h1><div class="panel" style="max-width:520px"><div id="msg"></div>
       <form id="account-form"><label>Name<input name="name" value="${esc(state.user.name)}" autocomplete="name" required /></label>
       <label>Email<input name="email" type="email" value="${esc(state.user.email)}" autocomplete="email" required /></label>
       <label>Phone<input name="phone" type="tel" value="${esc(state.user.phone || (state.demo && state.demo.phone) || "")}" autocomplete="tel" required /></label>
       <h3 style="margin-top:14px">Billing address</h3>${addressInputs("b_", state.user.billingAddress || {})}
       <h3 style="margin-top:14px">Default shipping address</h3>${addressInputs("s_", state.user.shippingAddress || {})}
       <div class="hint">Checkout starts from these. Leave a section blank to keep it unset.</div>
       <p class="hint">Member since ${esc((state.user.createdAt || "").slice(0, 10))}</p>
       <p><button class="btn" type="submit">Save changes</button></p></form></div>
       <div class="panel" style="max-width:520px;margin-top:16px"><h3 style="margin-top:0">Card on file</h3><div id="msg-card"></div>
       ${cardFormHtml()}</div>
       <div class="panel" style="max-width:520px;margin-top:16px"><h3 style="margin-top:0">Verification</h3><div id="msg-ver"></div>
       ${verificationHtml()}</div>
       <div class="panel" style="max-width:520px;margin-top:16px"><h3 style="margin-top:0">Change password</h3><div id="msg-pw"></div>
       <form id="password-form"><label>Current password<input name="currentPassword" type="password" autocomplete="current-password" required /></label>
       <label>New password<input name="newPassword" type="password" autocomplete="new-password" required /></label>
       <p><button class="btn" type="submit" data-testid="change-password">Change password</button></p></form></div>
       <div class="panel" style="max-width:520px;margin-top:16px"><h3 style="margin-top:0">Security notices</h3><div id="notices">${noticesHtml()}</div></div>`,
};

// Card on file: the browser holds only a token, the BIN and the last four digits (never a card number).
// Starts from the saved card, else the demo default when the store runs in demo mode.
const cardFormHtml = () => {
  // The API never returns the saved token, so the token field starts from the demo default (or blank).
  const c = savedCard() || (state.demo && state.demo.paymentMethod) || {};
  // After a save the token the shopper entered is kept for this page session (the API never returns it); a removed card goes back to the default.
  const tok = state.cardToken ?? ((state.demo && state.demo.paymentMethod && state.demo.paymentMethod.token) || "");
  return `<form id="card-form"><div class="hint">${savedCard() ? `Saved: card ending ${esc(savedCard().cardLast4)}.` : "No card on file."} Checkout uses it unless you pick another card.</div>
    <label>Card token<input name="token" value="${esc(tok)}" required /></label>
    <label>Card BIN (first 6 or 8 digits)<input name="cardBin" value="${esc(c.cardBin)}" inputmode="numeric" required /></label>
    <label>Last four digits<input name="cardLast4" value="${esc(c.cardLast4)}" inputmode="numeric" required /></label>
    <p><button class="btn" type="submit" data-testid="save-card">Save card</button>${savedCard() ? ` <button class="btn ghost" type="button" id="remove-card" data-testid="remove-card">Remove</button>` : ""}</p></form>`;
};

// Account verification: a status line, then (until verified) send a code by email or text message and enter it.
const verificationHtml = () => state.user.verified
  ? `<p data-testid="verified-status"><b>Your account is verified.</b></p>`
  : `<p class="hint" data-testid="verified-status">Your account is not verified yet.</p>
    <form id="verify-start-form"><label>Send a code by<select name="channel" data-testid="verify-channel"><option value="email">Email (${esc(state.user.email)})</option><option value="sms">Text message (${esc(state.user.phone || "")})</option></select></label>
    <p><button class="btn" type="submit" data-testid="send-code">Send code</button></p></form>
    <form id="verify-confirm-form"><label>Code<input name="code" inputmode="numeric" autocomplete="one-time-code" required data-testid="verify-code" /></label>
    <p><button class="btn" type="submit" data-testid="confirm-code">Confirm</button></p></form>`;

const noticesHtml = () => state.notices.length
  ? `<ul>${state.notices.slice().reverse().map((n) => `<li>${esc(NOTICES[n.kind] || String(n.kind).replace(/_/g, " "))} <span class="hint">· ${esc((n.at || "").slice(0, 16).replace("T", " "))} · sent to ${esc(n.to)}${n.code ? ` · demo code ${esc(n.code)}` : ""}</span></li>`).join("")}</ul>`
  : `<p class="hint">No security notices yet.</p>`;
const say = (kind, msg, slot = "#msg") => { const el = $(slot); if (el) el.innerHTML = banner(kind, msg); };

async function submitForm(form, run) {
  try { await run(Object.fromEntries(new FormData(form))); }
  catch (err) { say("bad", err.message); }
}

// Inline confirm row (no browser dialogs): shows "Confirm" and "Cancel" in the form's .confirm-slot.
// Cancel closes the row and changes nothing. Confirm disables both buttons (and the form's submit buttons)
// while run() works, ignores a second click, shows any failure as a plain message, and always re-enables in finally.
function askConfirm(form, message, run) {
  const slot = form.querySelector(".confirm-slot");
  if (!slot || slot.dataset.busy) return;
  slot.innerHTML = `<div class="confirm-row" role="group" aria-label="Confirm" data-testid="confirm-row"><span class="confirm-msg">${esc(message)}</span>
    <button class="btn sm" type="button" data-testid="confirm-yes">Confirm</button><button class="btn ghost sm" type="button" data-testid="confirm-no">Cancel</button></div>`;
  const yes = slot.querySelector('[data-testid="confirm-yes"]'), no = slot.querySelector('[data-testid="confirm-no"]');
  const submits = [...form.querySelectorAll('button[type="submit"]')];
  yes.focus();
  // Any edit to the form's inputs closes the row, so Confirm can never send older values than the shopper sees.
  const close = () => { if (!slot.dataset.busy) { slot.innerHTML = ""; form.removeEventListener("input", close); } };
  form.addEventListener("input", close);
  no.addEventListener("click", close);
  yes.addEventListener("click", async () => {
    if (slot.dataset.busy) return;
    slot.dataset.busy = "1";
    yes.disabled = no.disabled = true; submits.forEach((b) => { b.disabled = true; });
    try { await run(); }
    catch (err) { say("bad", (err && err.message) || "Something went wrong. Please try again."); }
    finally { delete slot.dataset.busy; yes.disabled = no.disabled = false; submits.forEach((b) => { b.disabled = false; }); }
  });
}

// Browser-side checks only; the server stays the authority.
const ADDRESS_FIELDS = { name: "Name", address1: "Address", city: "City", region: "State", zipcode: "ZIP" };
function addressProblem(f) {
  for (const [k, label] of Object.entries(ADDRESS_FIELDS)) if (typeof f[k] !== "string" || !f[k].trim()) return `${label} is required.`;
  if (!/^\d{5}(-\d{4})?$/.test(String(f.zipcode).trim())) return "ZIP must be 5 digits (for example 94105).";
  if (f.shippingMethod !== undefined && !["standard", "expedited"].includes(f.shippingMethod)) return "Choose Standard or Expedited shipping.";
  return null;
}
const addressFrom = (f, base = {}) => ({ ...base, country: f.country || base.country || "US", name: f.name.trim(), address1: f.address1.trim(), city: f.city.trim(), region: f.region.trim(), zipcode: f.zipcode.trim() });
// " Try again in 15 minutes." / " Try again in 20 seconds." from a 429 reply's retryAfterSeconds.
const waitText = (d) => { const n = d && d.retryAfterSeconds; return typeof n === "number" ? ` Try again in ${n >= 120 ? `${Math.ceil(n / 60)} minutes` : `${n} ${n === 1 ? "second" : "seconds"}`}.` : ""; };
const sentence = (m) => `${String(m).replace(/[.!\s]+$/, "")}.`;

async function loadOrdersQuietly() { try { await loadOrders(); } catch { /* keep the old list */ } }

async function placeOrder(f, payNow) {
  const from = location.hash;  // where the shopper was when they confirmed
  const go = (hash) => { if (location.hash === from) location.hash = hash; };  // never yank them off a page they moved to
  const address = addressFrom(f);
  state.lastAddress = address;
  const billing = f.billingSame ? address : addressFrom(addressPart(f, "b_"));
  let saved;
  try { saved = await api("POST", "/api/orders", { shippingAddress: address, billingAddress: billing, shippingMethod: f.shippingMethod }); }
  catch (err) {
    if (!/Review your cart/.test(err.message)) throw err;
    // The promo no longer applies: no order was made. Show the cart again, where the code can be removed.
    await refresh().catch(() => {});
    state.flash = ["bad", `${sentence(err.message)} No order was placed.`];
    go("#/cart");
    return;
  }
  const order = saved.order;
  if (!order || !order.id) throw new Error("The store sent an unreadable answer. Please check Orders.");
  state.orders = null;
  await refresh().catch(() => {});  // the cart is empty now
  if (!payNow) {
    state.flash = ["ok", `Order ${order.id} is saved. You can change shipping or pay below.`];
    go("#/orders");
    return;
  }
  try {
    const paid = await api("POST", `/api/orders/${encodeURIComponent(order.id)}/pay`, payBody(f));
    state.lastOrder = paid.order || order;
    await refresh().catch(() => {});
    if (location.hash === from) location.hash = "#/thanks";
    else state.flash = ["ok", `Order ${order.id} is paid.`];
  } catch (err) {
    state.flash = ["bad", `${sentence(err.message)} Order ${order.id} is saved; you can pay again from Orders.`];
    go("#/orders");
  }
}

const wire = {
  shop: (root) => wire.cart(root),
  cart: (root) => {
    root.addEventListener("submit", async (e) => {
      if (e.target.id !== "promo-form") return;
      e.preventDefault();
      const code = String(new FormData(e.target).get("code") || "").trim();
      try {
        setCart(await api("POST", "/api/cart/promo", { code }));
        render();
        say("ok", `Promo code ${code.toUpperCase()} applied.`, "#msg-promo");
      } catch (err) { say("bad", sentence(err.message), "#msg-promo"); }
    });
    root.addEventListener("click", async (e) => {
    if (e.target.id === "remove-promo") {
      try {
        setCart(await api("DELETE", "/api/cart/promo"));
        render();
        say("ok", "Promo code removed.", "#msg-promo");
      } catch (err) { say("bad", sentence(err.message), "#msg-promo"); }
      return;
    }
    const rid = e.target.dataset.remove;
    if (rid) {
      try {
        setCart(await api("DELETE", `/api/cart/items/${encodeURIComponent(rid)}`));
        renderHeader();
        state.flash = ["ok", `Removed ${product(rid).name} from your cart.`];
        render();
      } catch (err) { say("bad", err.message); }
      return;
    }
    const id = e.target.dataset.add;
    if (!id) return;
    if (!state.user) { location.hash = "#/login"; return; }
    try {
      setCart(await api("POST", "/api/cart/items", { productId: id, qty: 1 }));
      renderHeader();
      if (location.hash === "#/cart") { render(); say("ok", "Quantity updated."); } else say("ok", "Added to your cart.");
    } catch (err) { say("bad", err.message); }
    });
  },
  orders: (root) => root.addEventListener("submit", (e) => {
    e.preventDefault();
    const form = e.target;
    const id = form.dataset.id;
    const o = (state.orders || []).find((x) => x.id === id);
    if (!o) return;
    const f = Object.fromEntries(new FormData(form));
    if (form.classList.contains("pay-form")) {
      if (!cardOk(f)) return say("bad", "Choose a card.");
      askConfirm(form, `Pay ${money(o.total)} for ${o.id}?`, async () => {
        let result;
        try {
          await api("POST", `/api/orders/${encodeURIComponent(o.id)}/pay`, payBody(f));
          result = ["ok", `Order ${o.id} is paid.`];
        } catch (err) { result = ["bad", sentence(err.message)]; }
        finally { await loadOrdersQuietly(); }
        if (location.hash === "#/orders") { render(); say(...result); } else state.flash = result;
      });
    } else if (form.classList.contains("cancel-form")) {
      askConfirm(form, `Cancel order ${o.id}?${o.status === "paid" ? " It has been paid." : ""}`, async () => {
        let result;
        try {
          await api("POST", `/api/orders/${encodeURIComponent(o.id)}/cancel`);
          result = ["ok", `Order ${o.id} is canceled.`];
        } catch (err) { result = ["bad", sentence(err.message)]; }
        finally { await loadOrdersQuietly(); }
        if (location.hash === "#/orders") { render(); say(...result); } else state.flash = result;
      });
    } else if (form.classList.contains("ship-form")) {
      const problem = addressProblem(f);
      if (problem) { form.querySelector(".confirm-slot").innerHTML = ""; return say("bad", problem); }
      askConfirm(form, `Save shipping change for ${o.id}?`, async () => {
        let result;
        try {
          await api("PUT", `/api/orders/${encodeURIComponent(o.id)}/shipping`, { shippingAddress: addressFrom(f, o.shippingAddress || {}), shippingMethod: f.shippingMethod });
          result = ["ok", `Shipping updated for ${o.id}.`];
        } catch (err) { result = ["bad", sentence(err.message)]; }
        finally { await loadOrdersQuietly(); }
        if (location.hash === "#/orders") { render(); say(...result); } else state.flash = result;
      });
    }
  }),
  account: (root) => {
    const f = $("#account-form");
    if (f) f.addEventListener("submit", (e) => {
      e.preventDefault();
      submitForm(e.target, async (v) => {
        const body = { name: v.name, email: v.email, phone: v.phone };
        for (const [key, prefix, label] of [["billingAddress", "b_", "Billing address"], ["shippingAddress", "s_", "Default shipping address"]]) {
          if (addressEmpty(v, prefix)) continue;
          const part = addressPart(v, prefix);
          const problem = addressProblem(part);
          if (problem) throw new Error(`${label}: ${problem}`);
          body[key] = addressFrom(part);
        }
        state.user = { ...state.user, ...(await api("PUT", "/api/account", body)).user };
        renderHeader();
        say("ok", "Your details were saved.");
      });
    });
    // render() scrolls to the top and the card section sits below the fold: show the message where it is.
    const cardSay = (kind, msg) => { say(kind, msg, "#msg-card"); const el = $("#msg-card"); if (el && el.scrollIntoView) el.scrollIntoView({ block: "center" }); };
    const cf = $("#card-form");
    if (cf) {
      cf.addEventListener("submit", async (e) => {
        e.preventDefault();
        const v = Object.fromEntries(new FormData(cf));
        try {
          const { paymentMethod } = await api("PUT", "/api/account/payment-method", { token: v.token.trim(), cardBin: v.cardBin.trim(), cardLast4: v.cardLast4.trim() });
          state.user = { ...state.user, paymentMethod };
          state.cardToken = v.token.trim();
          render();
          cardSay("ok", "Your card was saved.");
        } catch (err) { cardSay("bad", sentence(err.message)); }
      });
      const rm = $("#remove-card");
      if (rm) rm.addEventListener("click", async () => {
        try {
          await api("DELETE", "/api/account/payment-method");
          const { paymentMethod, ...rest } = state.user;
          state.user = rest;
          state.cardToken = undefined;
          render();
          cardSay("ok", "Your card was removed.");
        } catch (err) { cardSay("bad", sentence(err.message)); }
      });
    }
    // Verification: send a code, then confirm it. Wrong codes show how many tries are left.
    const vs = $("#verify-start-form");
    if (vs) vs.addEventListener("submit", async (e) => {
      e.preventDefault();
      const channel = new FormData(vs).get("channel");
      try {
        await api("POST", "/api/verification/start", { channel });
        say("ok", channel === "sms" ? "A code was sent by text message. Enter it below." : "A code was sent by email. Enter it below.", "#msg-ver");
        try { await refresh(); const box = $("#notices"); if (box) box.innerHTML = noticesHtml(); } catch { /* the list updates next visit */ }
      } catch (err) { say("bad", `${sentence(err.message)}${waitText(err.data)}`, "#msg-ver"); }
    });
    const vc = $("#verify-confirm-form");
    if (vc) vc.addEventListener("submit", async (e) => {
      e.preventDefault();
      const code = String(new FormData(vc).get("code") || "").trim();
      try {
        await api("POST", "/api/verification/confirm", { code });
        state.user = { ...state.user, verified: true };
        render();
        say("ok", "Your account is verified.", "#msg-ver");
      } catch (err) {
        const d = err.data || {};
        say("bad", `${sentence(err.message)}${waitText(d)}${typeof d.attemptsLeft === "number" && d.attemptsLeft > 0 ? ` ${d.attemptsLeft} ${d.attemptsLeft === 1 ? "try" : "tries"} left.` : ""}`, "#msg-ver");
      }
    });
    const pw = $("#password-form");
    if (pw) pw.addEventListener("submit", async (e) => {
      e.preventDefault();
      const form = e.target;
      const v = Object.fromEntries(new FormData(form));
      try {
        await api("PUT", "/api/account/password", { currentPassword: v.currentPassword, newPassword: v.newPassword });
        form.reset();
        say("ok", "Your password was changed.", "#msg-pw");
        try { await refresh(); const box = $("#notices"); if (box) box.innerHTML = noticesHtml(); } catch { /* the list updates next visit */ }
      } catch (err) { say("bad", sentence(err.message), "#msg-pw"); }
    });
  },
  signup: (root) => $("#signup-form").addEventListener("submit", (e) => {
    e.preventDefault();
    submitForm(e.target, async (f) => {
      const { name, email, phone, password } = f;
      const body = { name, email, phone, password };
      if (!addressEmpty(f, "a_")) {
        const part = addressPart(f, "a_");
        const problem = addressProblem(part);
        if (problem) throw new Error(`Address: ${problem} Leave the address blank to add it later.`);
        body.billingAddress = body.shippingAddress = addressFrom(part);
      }
      state.user = (await api("POST", "/api/signup", body)).user; await refresh(); location.hash = "#/";
    });
  }),
  login: (root) => $("#login-form").addEventListener("submit", (e) => {
    e.preventDefault();
    submitForm(e.target, async (f) => { state.user = (await api("POST", "/api/login", f)).user; await refresh(); location.hash = "#/"; });
  }),
  checkout: (root) => {
    const same = $("#billing-same"), box = $("#billing-fields");
    // Unchecked shows the billing form. A disabled fieldset is skipped by validation and by FormData.
    same.addEventListener("change", () => { box.disabled = box.hidden = same.checked; });
    $("#checkout-form").addEventListener("submit", (e) => {
      e.preventDefault();
      const form = e.target;
      const f = Object.fromEntries(new FormData(form));
      const payNow = (e.submitter && e.submitter.value) !== "save";
      const billingProblem = f.billingSame ? null : addressProblem(addressPart(f, "b_"));
      const problem = addressProblem(f) || (billingProblem && `Billing address: ${billingProblem}`) || (cardOk(f) ? null : "Choose a card.");
      if (problem) { form.querySelector(".confirm-slot").innerHTML = ""; return say("bad", problem); }
      askConfirm(form, payNow ? `Place order and pay ${money(total())}?` : "Save this order without paying?", () => placeOrder(f, payNow));
    });
  },
};

async function loadOrders() { state.orders = state.user ? (await api("GET", "/api/orders")).orders : []; }

function render() {
  const name = (location.hash.replace(/^#\//, "") || "shop").split("?")[0];
  const view = views[name] ? name : "shop";
  const root = $("#app");
  if (view === "orders" && state.user && state.orders === null) loadOrders().then(() => location.hash === "#/orders" && render()).catch(() => { state.orders = []; render(); });
  root.innerHTML = `<div id="view">${views[view]()}</div>`;  // fresh node each render, so handlers never pile up
  if (wire[view]) wire[view]($("#view"));
  document.querySelectorAll("nav.main a").forEach((a) => a.toggleAttribute("aria-current", a.getAttribute("href") === (view === "shop" ? "#/" : "#/" + view)));
  document.title = (view === "shop" ? "" : view[0].toUpperCase() + view.slice(1) + " · ") + "Northbeam Goods";
  window.scrollTo(0, 0);
  if (state.flash && $("#msg")) { say(...state.flash); state.flash = null; }
}

document.addEventListener("click", async (e) => {
  if (e.target.id !== "logout") return;
  try {
    await api("POST", "/api/logout");
    state.orders = null; state.lastOrder = null; state.cardToken = undefined;
    await refresh();
    location.hash = "#/";
    render();
  } catch (err) { say("bad", (err && err.message) || "Could not log out. Please try again."); }
});
window.addEventListener("hashchange", render);
Object.assign(window.storeApp, { refresh: async () => { state.orders = null; await refresh(); }, render });

(async function start() {
  state.products = await api("GET", "/api/products");
  // Present only when the store runs in demo mode: forms prefill from it when the user has no value.
  state.demo = (await api("GET", "/api/config").catch(() => ({}))).demoDefaults || null;
  await refresh();
  render();
})();
