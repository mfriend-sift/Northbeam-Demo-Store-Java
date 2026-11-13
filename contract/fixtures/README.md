# Contract fixtures

Scenarios (2026-10-12): `contract/scenarios.js` now holds S0 to S9; `tools/contract-test.mjs` runs each after the fixtures under DEMO=1 and skips them without it. A scenario step may carry `headers` (object or function of ctx), sent after the persona headers and winning over them (for example `X-Demo-User-Agent` for a different browser, `X-Demo-Client-Ip`); the store already honors both through `lib/demo.js`. A scenario may declare `requires: { events: [...] }` (events beyond the base six; asserted only when the caller passes `opts.events` including them) and `notes` (expected simulated score, informational). S4 uses seeded shopper_1003, S6 shopper_1017, S7 shopper_1018 (`seed.json`), so reset between runs restores them. S9 uses seeded shopper_1002 with no reset and no signup (it changes nothing about the user, so it can run repeatedly and never wipes hand-made accounts).

Added 2026-10-12 (slice 2 fix round 2): 40 (the verify-own-email loop cannot dodge the lockout: the wrong-code count is per destination, so 4 wrong sms codes, an email success, an email change and one more wrong sms code is attempts 5 and a lock, `demo: true`), 41 (PUT /api/account rejects a non-text email, phone or name with 400). 29 and 38 now pin the promotion terms (`description`, `type`, `value`, `minimum`), and 38 pins `reason` as the status word `not_applicable` with a separate `message`.

Added 2026-10-12 (slice 2: promo on the cart, verification): 29 (cart promo: accepted percent and fixed codes, case and spaces, the discount follows the cart, remove, already applied, the order carries `promotion` and `discount`), 30 (rejected promo codes: unknown, expired, below the minimum, bad bodies 400 and 415, no promo route on an order 404), 31 (a promo never applies to a saved order), 32 (limited-use code and the reset, `demo: true`), 33 (verification rules: 401, 415, 400, 409, resend cooldown 429, attempts counter, 429 lockout that a restart does not clear; both modes), 34 (verification success with the demo code 123456, `verified: true`, reset clears it, `demo: true`; also trims spaces around the code and a restart during the lockout is 429), 35 (no code in the reply or the notice without DEMO=1, `onlyWithoutDemo`), 36 (lockout window and the count surviving a new code, on the X-Demo-Now clock, `demo: true`), 37 (resend cooldown and code expiry, `demo: true`), 38 (a promo that no longer applies: `applies: false` and `reason` on the cart, order 409, DELETE with text/plain 415, a 65-character code), 39 (changing the email or phone clears `verified` and a pending code, `demo: true`). `request.rawBody` with a `Content-Type` header is used for the 415 cases; a step with neither body nor `rawBody` is a bare request.

Added 2026-10-12 (card on file): 27 (account payment method: save, replace, remove, validation including a non-text `type`, 401, 415; GET never returns the token), 28 (pay with the saved card sent as `{}`, a bare or `null` or `[]` body is 415 or 400, no card on file is 400 before the 409 order-state check, declined saved card, a body payment still works); 22b now also pins `demoDefaults.paymentMethod`.

Added 2026-10-12 (Opus review fixes): 25 (body Content-Type 415, non-string signup fields 400, oversized body 413), 26 (`demo: true`, a paid-then-canceled order accepts a dispute). `request.headers` now overrides the default `Content-Type`; `request.rawBodyPadTo` pads `rawBody` with spaces to a length (fixture 25).

Added 2026-10-12 (step A2f): 21b (the `/demo/orders/{id}/ship` cases, `demo: true`, split out of 21 so that 21 passes without DEMO=1).

Added 2026-10-12 (steps A2d, A2e): 22 (account addresses), 22b (config `demoDefaults` present under DEMO=1, `demo: true`), 22c (absent without DEMO=1, `onlyWithoutDemo`), 23 (account persistence), 24 (cart remove).

Added 2026-10-13 (contract bump): 42 (pay with `tok_unavailable` is 502 with the gateway error, the order is back to saved with no `chargeId` or card, and a retry with `tok_visa` is paid; runs in both modes).

Changed 2026-10-10 for the split order flow (08, 09, 10, 11, 12 changed; 16 added). Changed 2026-10-12 (step A1): 17, 18 and 19 added; 2026-10-12 (step 0c spike): 20 (password change) and 21 (order cancel and fulfill) added; 16 pins the three order-rule messages; `request.rawBody` added.

Golden request and response files that `tools/contract-test.mjs` runs against the store at the address given by `--base` (or `STORE_URL`). Frozen 2026-10-08 (Plan 3 freeze item 2). Each backend must pass all of them.

## File format

One JSON file per case: `{ name, description, demo, onlyWithoutDemo?, steps[] }`.

- `demo: true` runs only when the store runs with `DEMO=1`. `onlyWithoutDemo: true` runs only without it. Others run in both modes.
- Each file runs with a fresh cookie jar (a new `nb_sid`). Under `DEMO=1` the runner calls `POST /demo/reset` before each file.
- A step is `{ request, expect, save? }`.
  - `request`: `{ method, path, headers?, body?, rawBody? }`. A body is sent as JSON with `Content-Type: application/json`. `rawBody` (a string) is sent as written, for bytes that are not valid JSON (fixture 17).
  - `expect.status`: exact HTTP status.
  - `expect.schema`: a schema name in `openapi.yaml` `components.schemas`. `Name@key` validates `body[key]`; `Name[]` validates each element of an array body.
  - `expect.match`: partial deep match. Objects match when every listed key matches (extra keys are allowed). Arrays match element by element with equal length, unless the expected array ends with `"<...>"` (then the actual array may be longer).
  - `expect.headers`: response headers, lower-case names.
  - `expect.cookie`: a cookie name that must be set (`Set-Cookie`).
  - `save`: `{ var: "dotted.path" }` reads a value from the response body (`orders.0.chargeId`) for later steps.
- Placeholders in strings: `{{runId}}` (unique per run, for example `Date.now().toString(36)`; without `DEMO=1` the runner needs a new `runId` per fixture file, because several fixtures sign up the same email) and any saved `{{var}}`.
- Matchers in `match` and `headers` values: `<any>`, `<string>`, `<number>`, `<sha256|null>` (64 lower-case hex or null), `<absent>` (header not present), `<contains:X>` (case-insensitive substring), `<contains-all:a,b,c>` (comma list, case-insensitive, all present).
