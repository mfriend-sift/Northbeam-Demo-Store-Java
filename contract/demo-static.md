# Demo-only static route: /demo/scenarios.js (additive)

`GET /demo/scenarios.js` serves `contract/scenarios.js` (the one scenario module, unchanged) as
`application/javascript`, so the Demo drawer in the browser can `import()` it from the store's own origin.

- DEMO=1 only. Without DEMO=1 it is a `/demo/*` route like the others: JSON 404 `{ "error": "Not found" }`.
  The drawer uses this as its DEMO probe.
- Both backends (Express and Flask) serve it. No auth, no persona headers needed, read-only.
- Not part of `openapi.yaml`, not a Sift event site, and the engine scanner ignores it (it lives in the demo routes).
