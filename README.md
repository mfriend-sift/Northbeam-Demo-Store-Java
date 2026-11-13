# Northbeam Goods demo store (Java)

A small storefront used by the Sift Integration Engine prototype. Dummy products and in-memory data only. It contains no Sift code: the Integration Engine scans this repo and proposes where Sift calls belong.

This is the **Java** product repo: one language, flat layout, its own storefront, contract tests, and run and instrument scripts. Sibling repos: Northbeam-Demo-Store-Node, -Python, -Ruby, -PHP, -DotNet. The shared pieces (`public/`, `contract/`, shared tools) are duplicated across them; see "Shared pieces" below.

The backend is a **Spring Boot 3.5 web application** (`StoreApplication`, `apis/*.java`, `lib/*.java`; Java 21, Maven with the wrapper, embedded Tomcat, in-memory data, no database, no dependency beyond `spring-boot-starter-web`). The store is built for raw REST use of the Events API only; the engine has no `java` entry yet, so the instrument script below stops at the engine's usage line until one exists. Spring Boot 3.5 is the last 3.x line (the open-source support for it has ended; the 4.x line is not used here on purpose, because most real repositories still run 3.x).

## Requirements

A JDK 21 or newer (tested: OpenJDK 21 from Homebrew, `/opt/homebrew/opt/openjdk@21`; `tools/run.sh` and `tools/preflight.sh` use `JAVA_HOME`, else `java` on the PATH, else that Homebrew path) and Node 18+ (for the tests and the mock). Maven is not needed: the wrapper `./mvnw` is in the repo. **The first build downloads Spring Boot and its libraries from Maven Central into `~/.m2`**, so it needs the network (the engine's host run needs the same); later builds are offline.

## Run

```bash
./tools/run.sh              # mock on 4010 plus the store on http://localhost:3000 (DEMO=1)
./tools/run.sh --no-mock --port 3056    # use a mock that is already running (MOCK_PORT)
./mvnw -q -DskipTests package && PORT=3000 java -jar target/store.jar    # store only, without the demo routes (run from this folder)
```

Started by hand, the store uses port 3000 (`PORT` changes it) and listens on 127.0.0.1 only. The Sift Integration Engine runs saved copies on port 3010, so both can run side by side. Run it from the repo folder: it reads `contract/seed.json` and serves `public/` relative to the current directory.

`tools/run.sh` is the only thing that sets `DEMO=1` (`/demo/*` routes, persona headers, CORS for `http://localhost:4010`). Flags: `--no-mock` (use a mock that is already running), `--port N`; env: `MOCK_PORT` (default 4010), `ENGINE_DIR` (default `../Sift-Integration-Engine`), `ENGINE_ORIGIN` (the one origin CORS answers for under DEMO=1; run.sh sets it to the mock's address, so with a `MOCK_PORT` other than 4010 the CORS fixture 14 expects another origin and fails: start the store with `ENGINE_ORIGIN=http://localhost:4010` to run the contract test against a mock on another port). It builds the jar with `./mvnw -q -DskipTests package` and starts `java -jar target/store.jar` from the repo folder. `BUILD_HASH` comes from the engine's tree-hash; if the engine cannot hash this layout yet (it has no `java` entry), run.sh warns and starts with an empty hash (`/api/config` then reports `buildHash: null`). `tools/preflight.sh` checks the machine first (java, Node, ports). Spring Boot takes two to four seconds to start; it prints `Northbeam Goods listening on http://localhost:<port>` when it listens.

## Test

```bash
node tools/contract-test.mjs --base http://127.0.0.1:3000   # the store running (tools/run.sh; use its port) ; contract/fixtures plus every scenario in contract/scenarios.js (S0 to S9 today; expect 52 passed, 0 failed, 3 skipped with a running store under DEMO=1: 15-demo-off, 22c-config-no-demo-defaults and 35-verification-no-demo-code are the skipped ones; without DEMO=1, 32 passed, 0 failed, 23 skipped)
bash tools/grep-test.sh                  # no Sift-named code outside the allowed files
tools/lock-probe/run.sh                  # in process on a random port: detects a missing lock (see "State and concurrency")
node tools/concurrency-probe.mjs --base http://127.0.0.1:3000   # needs DEMO=1: parallel promo applies, order saves, pays and signups
node tools/slow-body-probe.mjs --base http://127.0.0.1:3000     # needs DEMO=1: a stalled body must not block other requests
node tools/parity-check.mjs --a http://127.0.0.1:3000 --b http://127.0.0.1:3058   # about 500 requests plus optional seeded random ones against a second store (a second store implementing the same contract), answers compared; both stores run the same way (both DEMO=1 or both without)
```

The 52 and 32 are the totals for the contract as it is now (45 fixtures and 10 scenarios, 3 or 23 skipped); the .NET repo's README still says 51 and 31, which is its text from before the last fixture, and its store gives 52 and 32 too.

## Instrument a copy (engine patch)

```bash
./tools/instrument.sh [--style library|raw] [--versions-dir DIR]
```

Makes a full copy of the repo as `Store_<short-name>_v<N>` (the repo folder name minus the engine's optional prefix) in `DIR` (default: `Store-Versions` beside the store folders, or env `STORE_VERSIONS_DIR`), commits a base, asks the engine (`ENGINE_DIR`) for the one patch `sift-integration.patch`, applies it at the copy root, commits "Instrumented by the Sift Integration Engine", installs dependencies, and prints the run command. Run a version from its own folder: `cd <copy> && ./tools/run.sh`. The engine has no `java` language entry yet (a later step): until it does, the script stops at once with the engine's usage line (`--lang` must be one of its registered ids), before any scan or copy.

## Demo scenarios (DEMO=1)

The demo drawer and the engine's Run screen list every scenario in `contract/scenarios.js` as a button. S3 to S9 are simulate buttons only: they drive the store's real API as a user (so the orders show in Orders and the events reach the Traffic Inspector), and the store has no screen of its own for them. A step may carry its own `headers` (for example `X-Demo-User-Agent` to look like a different browser); they win over the scenario's persona headers. `notes` on a scenario is the expected simulated score, for information only.

| Id | Title | What it does |
|---|---|---|
| S0 | Base six | New signup, one of each base event, dispute lost |
| S1 | Good customer | New signup, two items, paid |
| S2 | Save, change shipping, pay | Saves, changes shipping and method, then pays |
| S3 | Large order, country mismatch | New account, about $900 cart, billing US and shipping UK, pay |
| S4 | Account takeover | Seeded shopper_1003 (older than 10 minutes): 6 failed logins, a login from a different browser, email and phone change, password change, order to a new address |
| S5 | Promo abuse | Three new accounts from one client address each apply WELCOME10 and order |
| S6 | Chargeback | Seeded shopper_1017 opens order history; a dispute is received on the seeded paid order |
| S7 | Dispute won | Seeded shopper_1018; a dispute is received, then won |
| S8 | Shipping change after order | New account, order paid, shipping changed to the UK |
| S9 | Log out, then log in again | Seeded shopper_1002 (no reset, no new account): log in, log out, log in again, then read the account |

Events beyond the base six (`$login`, `$logout`, `$link_session_to_user`, `$update_password`) are listed under `requires.events` and asserted against the log only when the caller passes `events` that include them.

## Account

| Route | What it does |
|---|---|
| `GET /api/account` | The signed-in user, with `billingAddress` and `shippingAddress` (the default shipping address) when set (keys absent otherwise; a seeded user's one address is both), and the user's security `notices` |
| `PUT /api/account` | Any of `name`, `email`, `phone`, `billingAddress`, `shippingAddress`. An address needs name, address1, city, region, country and zipcode (the order-address rule, in `lib/Address.java`); an incomplete one is 400 and nothing changes |
| `POST /api/signup` | Also accepts `billingAddress` and `shippingAddress` (optional, same rule) |
| `PUT /api/account/payment-method` | Saves the card on file: `token`, `cardBin` (string, 6 or 8 digits), `cardLast4` (string, 4 digits), optional `type` (free text, at most 200 code points, default `card`); a missing or invalid field is 400 and nothing changes, and unknown extra keys are ignored. The reply and `GET /api/account` never return the token (only `cardBin`, `cardLast4`, `type`): it stays server-side and the pay route reads it there. The card rule is `validCard` and the save is `savePaymentMethod`, both in `lib/PaymentMethods.java` (the pay route uses the same rule). `DELETE /api/account/payment-method` removes it (200 also when none). `GET /api/account` returns `paymentMethod` (without the token) when saved, absent otherwise |
| `PUT /api/account/password` | See below |
| `POST /api/verification/start` | `{ channel: "email" \| "sms" }` (session required; 400 for another channel; 409 when already verified). The stand-in sender (`lib/Mailer.java`, nothing leaves the process) records a `verification_code` notice and the reply is `{ status: "pending", channel }`, never the code. Under DEMO=1 the code is the fixed `123456` and the notice lists it; otherwise it is random, server-side only, and no notice shows it. Starting again replaces the code but does not clear the wrong-code count or a lockout. A code lives 10 minutes; a new one can be sent 30 seconds after the last (429 `Wait before sending a new code`, with `retryAfterSeconds`); times use the demo clock (`X-Demo-Now`) under DEMO=1 |
| `POST /api/verification/confirm` | `{ code }` (400 for a missing or blank code; 409 `No verification is in progress`). A wrong code is 400 with `attempts` and `attemptsLeft`; spaces around the code are ignored; an expired code is 400 and counts as a failure (so the attempts count can rise on a correct but expired code); the count belongs to the account and is kept per destination (the address or phone a code went to): a success clears only that destination's count, so verifying your own email never resets the guesses against a phone; the lock running out or `/demo/reset` clears all; the 5th wrong code for a destination locks the account for 15 minutes (confirm and start are 429; a 429 is not counted as a failure); the right code is 200 `{ status: "success", verified: true }` and `GET /api/account` then has `verified: true` (`false` before). `recordVerification` in `lib/Verification.java` is the one place every outcome (pending, success, failure) is recorded; start and confirm both call it. Changing the email or phone clears `verified` and any code in progress |

The checkout and Account forms start from the account's addresses; the order API still requires its own `shippingAddress` and `billingAddress` (no defaults are filled in on the server). Accounts made by signup stay until the store stops: logout then login with the same email and password gives the same user id. Only `POST /demo/reset` (DEMO=1) or a restart clears them (fixture 23).

Demo defaults: `contract/seed.json` `_meta.demoDefaults` (a phone, an address and a card `tok_demo_visa`, 411111, 1111) is returned as `demoDefaults` by `GET /api/config` only under DEMO=1. The signup, Account (including the "Card on file" form), checkout and billing forms prefill from it when the user has no value; without DEMO=1 nothing is prefilled from it.

`PUT /api/account/password` with `{ currentPassword, newPassword }` (session required; 400 `Current password is incorrect` on a wrong current password; 200 `{ ok: true }`). On success `lib/Mailer.java` (a stub: nothing is sent) records a security notice, returned as `notices` by `GET /api/account`. Signup and login bind the session through `signIn` in `lib/signIn.java`; the password change is decided in `changePassword` in `lib/Password.java`. Logout is decided and recorded in `signOut` in `lib/signOut.java`: it returns `not ok` at once when nobody is signed in, otherwise clears the session user, records an audit entry, and returns `{ ok: true, userId }`; the route answers `200 { ok: true }` either way. Every account change (the profile edit, saving a card, removing a card) is recorded in `updateAccount` in `lib/AccountUpdate.java`, called as the last step before the response: one `account_update` audit entry with the kind of change and the fields.

## Cart

| Route | What it does |
|---|---|
| `GET /api/cart` | The user's cart lines (empty array for a guest) |
| `POST /api/cart/items` | Adds `qty` (default 1) of a product |
| `POST /api/cart/promo` | `{ code }` (session required; a missing or blank code is 400). Accepted: 200 with the cart plus `promotion` `{ code, description, type, value, minimum, applies: true }` (the code's own terms: `type` is `percent` or `amount`, `value` is percent points or dollars, `minimum` is dollars, 0 when none) and `discount` (dollars). When the code stops applying (cart below the minimum, expired, used up) the cart still returns it as `promotion` `{ ...terms, applies: false, reason, message }` with `discount` 0, so it can be removed (`reason` is a status word: `unknown`, `expired`, `not_applicable`, `already_applied` or `used_up`, the words the audit record uses; `message` is the sentence to display). Rejected: 422 `{ error }` (unknown or longer than 64 characters, expired, already applied, or not applicable: empty cart, below the minimum, or a limited-use code that is used up). One code at a time. Codes are in `contract/seed.json` `promotions`: `WELCOME10` (10 percent), `SAVE5` (5 dollars off a cart of 100 or more), `EXPIRED20` (expired), `LIMITED25` (25 percent, the first 2 orders only). `applyPromo` in `lib/Promo.java` is the one function that evaluates a code and records the outcome (accepted or rejected) |
| `DELETE /api/cart/promo` | Removes the code; returns the cart without `promotion` and `discount` (200 also when none was applied; a body of another Content-Type is 415) |
| `DELETE /api/cart/items/{productId}` | Removes the product's whole line and returns the updated cart (401 no session; 404 `That product is not in your cart`). The work is `removeCartLine` in `lib/Cart.java`; the Cart screen has a Remove button per line |

`/demo/reset` (DEMO=1) also clears applied promo codes, promo use counts and any verification in progress.

## Order flow

| Route | What it does |
|---|---|
| `POST /api/orders` | Saves the cart as an order (`status: "saved"`, not paid) and empties the cart. A promo applied to the cart comes off: the order carries `promotion` `{ code, description, type, value, minimum }` and `discount`, `total` is after the discount, and the use is counted (a promo applies to the cart only, never to a saved order). A code on the cart that no longer applies is 409 `{ error }` and no order is made, by design even though the cart already shows the full price (the shopper removes the code first); a use is counted when the order is made and is not returned on cancel. Checkout sends a billing address that is the shipping address unless "Billing address same as shipping" is unchecked |
| `PUT /api/orders/{id}/shipping` | Changes the shipping address and/or method of a `saved`, `payment_failed` or `paid` order (409 otherwise); returns the full order |
| `POST /api/orders/{id}/pay` | `payment` is optional. Without it the account's saved card is used (400 `No payment method on file` when none; checked before the order's state). With it, it needs `payment.token`, `cardBin` (string, 6 or 8 digits) and `cardLast4` (string, 4 digits), else 400 (a null or invalid `payment` never falls back to the saved card; unknown extra keys are ignored). A body that is valid JSON but not an object (`null`, `[]`, a string, a number) is 400 `Request body must be a JSON object`. The saved-card path needs a JSON request: send `{}`. The order snapshots the card (`card.bin`, `card.last4`) once payment is attempted. Text fields must be non-blank after trimming Unicode whitespace and at most 200 code points. Charges the card token for a `saved` or `payment_failed` order: 200 `paid`, 402 declined (can be paid again), 409 if already paid or in progress |
| `POST /api/orders/{id}/cancel` | Cancels the user's order from `saved`, `payment_failed` or `paid` (409 `Order cannot be canceled` otherwise) |
| `GET /api/orders` | The user's orders, newest first |
| Gateway errors | The pay route logs the exception message only, never the error object (a real gateway error can carry the request, and so the token) |
| Request bodies | Must be `Content-Type: application/json`; any other type is 415 `Content-Type must be application/json` (answered after 404, 401 and the resource 404, like bad JSON 400); a request with no body and no Content-Type (a bare POST) is also 415, so every POST and PUT sends a JSON body (`{}` when there is nothing to send). A body over 100 KB is 413 `Request body too large`. Every error is JSON (a final error handler; no stack traces) |
| `POST /webhooks/fulfillment` | The carrier's `shipment.created` notice sets a `paid` order to `fulfilled` (404 unknown order, 409 `Order cannot be fulfilled` otherwise). `POST /demo/orders/{id}/ship` (DEMO=1) calls the same handler |

States: `saved` -> `pending` (during the charge) -> `paid` or `payment_failed` (payable again); `paid` -> `fulfilled` (shipment notice); `saved`, `payment_failed` or `paid` -> `canceled`; `paid` or `fulfilled` -> `chargeback` (dispute webhook; a dispute is accepted when the order was ever paid, tested by `wasPaid` in `lib/OrderStatus.java`, so a paid-then-canceled order passes and a declined one is 409 `This order was never paid`). Paid, canceled and fulfilled all go through `setOrderStatus` in `lib/OrderStatus.java` (paid from `pending` only; it records the audit entry). `saved` and `payment_failed` are NOT reported through it: a saved or declined order has no status event. If the gateway itself fails, the order's status and card are restored to what they were before the attempt (it never stays `pending`) and the reply is 502 `{"error":"The payment gateway is unavailable. Try again."}`.

`POST /api/checkout` was removed on 2026-10-10.

## Event functions (one code location per event)

Each of these is defined once, in its own file under `lib/`, and every route that needs it calls it. A route never decides an outcome itself: it calls the function and maps the result to HTTP. Outcomes are plain records in the store's own words (`status "success"`, `reason "user_update"`, `status "accepted"`). The functions that take a request take the `HttpServletRequest`.

| Function | File | Called from | Returns |
|---|---|---|---|
| `Signing.signIn(req, user)` | `Signing.java` | signup, `attemptLogin` | nothing (binds the session to the user) |
| `Login.attemptLogin(email, password, req)` | `Login.java` | `POST /api/login` | `Outcome(status "success" \| "failure", user)` |
| `Signing.signOut(req)` | `Signing.java` | `POST /api/logout` | `SignOutResult(ok false)` when nobody is signed in, else `(ok true, userId)` |
| `OrderStatus.setOrderStatus(order, status, req)` | `OrderStatus.java` | pay, cancel, `handleFulfillmentEvent` | `StatusResult(ok true)` or `(ok false, error)` |
| `Password.changePassword(user, current, next, req)` | `Password.java` | `PUT /api/account/password` | `Outcome(status, reason "user_update")` |
| `Mailer.sendSecurityNotice(user, kind)` | `Mailer.java` | the password route | `Sent(status "sent")` |
| `Promo.applyPromo(user, input, req)` | `Promo.java` | `POST /api/cart/promo` | `Outcome(status "accepted", code, discount)` or `(status "rejected", code, reason, error)` |
| `Verification.recordVerification(user, channel, status, req, reason)` | `Verification.java` | start (pending), confirm (success, failure) | nothing |
| `AccountUpdate.updateAccount(user, req)` | `AccountUpdate.java` | the profile, card save and card remove routes | nothing (last step before the response) |
| `Cart.removeCartLine(userId, productId)` | `Cart.java` | `DELETE /api/cart/items/{productId}` | `Removal(ok true, cart, removed(product, qty))` or `(ok false)` |
| `GatewayEvents.handleGatewayEvent(event, req)` | `GatewayEvents.java` | `POST /webhooks/gateway`, `/demo/dispute` | `Handled(status, body)` |
| `GatewayEvents.handleFulfillmentEvent(event, req)` | `GatewayEvents.java` | `POST /webhooks/fulfillment`, `/demo/orders/{id}/ship` | `Handled(status, body)` |

`Promo.redeemPromo` is not an event place: it runs inside the order save.

## Layout

| Path | What it does |
|---|---|
| `public/` | Static storefront (Shop, Cart with a promo code box (Apply, Remove) and the discount, Checkout with "Place order and pay" or "Save order, pay later", Orders with "Change shipping", "Pay" and "Cancel order", Account with addresses, "Verification", "Card on file", "Change password" and security notices, signup, login, confirmation) and product photos (`public/img/CREDITS.md`). `demo-drawer.js` adds the operator-only Demo controls bar when the store runs with DEMO=1 |
| `src/main/java/com/northbeam/store/` | `StoreApplication` (the entry), `Boot` (loads the seed before the first request, prints the port line), `WebConfig` (route matching: any letter case, optional trailing slash), `TomcatTuning` (the two places Tomcat is stricter than the other stores' servers: bad percent escapes and encoded slashes in a path) |
| `.../apis/` | The routes as `@RestController` classes: `AuthApi`, `AccountApi`, `CartApi`, `VerificationApi`, `OrdersApi`, `WebhooksApi`, `StoreApi` (products and `/api/config`), `DemoApi` (present only under DEMO=1), `FallbackApi` (the storefront's files and the JSON 404 for everything else), `ErrorApi` and `ErrorHandlers` (every error is the JSON shape) |
| `.../lib/` | The event functions above, plus `Db` (in-memory data, seed loading, password hashing with PBKDF2-SHA256), `Gate` (the one lock), `Models`, `Sessions`, `Middleware` (one servlet filter: errors, demo mode, the request body, the session), `Http` and `Api` (the lazy JSON body, route parameter checks, how a route runs), `Reply` (the answer, written straight to the servlet response), `Json` (a JSON reader and writer with JavaScript's rules), `Js` (JavaScript-shaped rounding, truthiness, trim, number printing, so answers match the contract), `Text`, `Address`, `PaymentMethods` (the one text, address and card rules), `Clock`, `Gateway` (the stand-in payment gateway), `Cents` and `Codes` (two small helpers), `Statics`, `Config` (DEMO and the environment) |
| `src/test/java/` | `LockProbeTest`, run by `tools/lock-probe/run.sh` |
| `pom.xml`, `mvnw`, `.mvn/` | The one build file (Spring Boot 3.5 parent, Java 21, `spring-boot-starter-web` and the test starter), the Maven wrapper; the jar is `target/store.jar` |
| `contract/` | Shared and frozen: `openapi.yaml`, `fixtures/`, `seed.json`, `scenarios.js`, and the `.md` notes |
| `tools/` | `run.sh`, `preflight.sh`, `instrument.sh`, `contract-test.mjs`, `grep-test.sh`, `lock-probe/run.sh`, `concurrency-probe.mjs`, `slow-body-probe.mjs`, `parity-check.mjs`, `webhook-check.mjs`, `mini-yaml.mjs` |

## State and concurrency

Tomcat runs requests on many threads in one process. All state (users, orders, carts, promos, promo use counts, verifications and their guards, the audit list, the id counters, the sessions, the mailer outbox) is guarded by one reentrant lock, `Gate` in `lib/Gate.java` (a `ReentrantLock`), used through `Db.sync`. `Api.run` (`lib/Api.java`) holds it around each route handler, from the auth check to the answer (the JSON text is built inside it), so every check-then-change (the promo use limit, the wrong-code count, id counters, saving a cart as one order, signup's email check, `/demo/reset`, `/demo/dispute`'s lookup and handler) is one step. The pay route runs in three steps instead: it claims the order (`pending`, card snapshot) inside the lock, calls the payment gateway outside it, and finishes inside it again on the **claimed order object**, never by looking the order up again by id (a `/demo/reset` during the gateway call builds new orders, so a reused id would otherwise reach another shopper's order). `/demo/reset` clears the state inside the lock and resets the mock outside it; `/demo/log` only reads the mock. Request bodies are read before any lock is taken: `Middleware` reads a JSON body (bounded to 100 KB, 413 beyond that, on any route) in its own servlet filter, so a slow client holds nothing but its own connection; handlers parse the bytes lazily with `Http.jsonBody` after their auth and resource checks (they never take Spring's `@RequestBody`, so Spring's body conversion and its error mapping cannot change the order of answers: unknown route 404, then 401, then the resource 404, then bad JSON 400, with the 415 rule). Password hashing (PBKDF2-SHA256, a few milliseconds) runs inside the lock, as the contract's other stores hash inside their own critical sections.

Probes: `tools/lock-probe/run.sh` runs `LockProbeTest`, which starts the store in process on a random port and sends real HTTP requests: with the real lock LIMITED25 is carried by exactly 2 orders and ten wrong verification codes give 5 x 400 then 5 x 429, in every one of five rounds; with the lock swapped (through `Gate.useLock`) for one that does nothing, some round must give a wrong answer, or the probe reports itself broken. `tools/slow-body-probe.mjs --base URL` (DEMO=1 store running) stalls half-sent JSON bodies and checks that other requests still answer. `tools/concurrency-probe.mjs` runs parallel requests over HTTP (promo limit, order saves, pays, signups).

## Accepted differences from the shared contract

Checked with `tools/parity-check.mjs` against the .NET store (statuses, bodies, content types and cookie flags identical, ids and timestamps masked): zero differences over the 842-request script and 500 seeded random requests, apart from the known-deviation scripts that the checker does not count. Trailing slashes and upper-case paths match their routes, a bad `%` escape is a 400 only in a route parameter, and one `Range` request on a static file is answered with 206. How this store gets there on Spring Boot:

- Spring is kept out of the answers: routes write the JSON straight to the servlet response (Spring's message converters read the request's `Content-Type` and would turn an odd one into a 500), the whitelabel error page and Spring's own error mapping are off, and `/error` (where Tomcat sends the errors it raises) answers the same JSON shape.
- Tomcat rejects a path with a bad percent escape or an encoded slash before any filter runs. `TomcatTuning` makes the connector decode `%2F` and re-spells a path with a bad escape (every `%` as `%25`) so it matches no route; the original path is kept for the 400 rule in route parameters.
- No `HttpSession`: the one `nb_sid` cookie is set by the filter, so there is no `JSESSIONID`.
- Without DEMO, `OPTIONS` on any path is the JSON 404, as in the .NET store. With DEMO both answer 204.
- `GET /img` (a directory without its trailing slash) is the JSON 404 here; the other implementation redirects (301) to `/img/`.
- `X-Demo-Now` beyond the year 9999 is a 500 here, as in the .NET store (the other stores print an extended year).
- Money is kept as dollars in a `double` and rounded to whole cents with JavaScript's `Math.round` rule (`Js.round`), like the contract's other stores, not as cents in a `long`: the contract's numbers are dollars with the same rounding, and a `long` would only add a conversion that could differ in the last digit.
- The saved card's token is never returned (fixture 27 pins `<absent>`).

## Shared pieces

This repo is independent: it needs only its own files. The shared pieces (`public/`, `contract/`, `tools/contract-test.mjs`, `tools/mini-yaml.mjs`, `tools/webhook-check.mjs`) were copied verbatim from the shared contract source (tag `contract-freeze-2026-10-12`); `node <engine>/tools/contract-drift.mjs` proves they stay identical (that script does not know this store yet, so pass it as `--store Northbeam-Demo-Store-PHP=<this folder>` to compare it in the PHP slot). When you run it, the engine's mock server and hash tool are used through `ENGINE_DIR` (default `../Sift-Integration-Engine`); `--no-mock` skips the mock.

## Troubleshooting

- Open everything as `http://localhost:...`, not `127.0.0.1`: the session cookie is host-scoped and CORS allows only `http://localhost:4010`.
- Port busy: `tools/preflight.sh --port N` names the port; `lsof -nP -iTCP:N -sTCP:LISTEN` finds the process. A running mock on the mock port is fine.
- The first `./mvnw` build downloads Spring Boot into `~/.m2` (needs the network, a minute or two); `target/` is not committed. `mvnw` needs `JAVA_HOME` or `java` 21 on the PATH; `tools/run.sh` finds the Homebrew `openjdk@21` itself.

## Licensing

The six product photos are licensed iStock images (see `public/img/CREDITS.md`). Keep this repo private; do not redistribute the photos outside that license.

Card data: the browser sends only a token, the card BIN, and the last four digits. No raw card numbers reach the server.
