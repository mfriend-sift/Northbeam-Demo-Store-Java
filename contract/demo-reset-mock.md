# `/demo/reset` also resets the mock (additive, DEMO only)

`POST /demo/reset` (DEMO=1 only) keeps its frozen behavior and response `{ "ok": true }`, and now also:

- After resetting its own state, POSTs `${SIFT_API_URL}/__reset` with a 2 s timeout. Failure is ignored; the store still answers normally.
- Adds one field to the response: `mockReset: true | false | null` (`true` the mock answered 2xx, `false` it failed or timed out, `null` no `SIFT_API_URL`).
- Why: the browser cannot call the mock's `/__reset` (it has no CORS), and the mock's chargeback history raises later scores, so the Demo drawer's "Reset demo" must clear it. The mock also clears its own log, so the engine Inspector clears too.
- Both backends (Express and Flask). Existing callers that read only `ok` are unaffected. The engine scanner ignores demo routes.
