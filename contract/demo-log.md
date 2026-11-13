# `GET /demo/log?since=<n>` same-origin mock log for the Demo drawer (additive, DEMO only)

The Demo drawer's live feed needs the mock's call log, but the mock allows that cross-origin read only from one store port. So each store reads it server-side and serves it on its own origin.

- Route: `GET /demo/log?since=<n>`. It exists only under DEMO=1, like the other `/demo/*` routes. With DEMO off it is the same JSON 404 as every other `/demo/*` path.
- `since` must be a non-negative integer; anything else (missing, negative, decimal, text) becomes `0`.
- Fixed upstream: the store calls `${SIFT_API_URL}/__log?since=<int>` (default base `http://localhost:4010`). No other part of the URL comes from the request.
- Timeout: 2 seconds.
- Success: HTTP 200 with the mock's JSON body unchanged (`{ "records": [...], "nextSeq": <n> }`).
- Failure (mock down, timeout, non-2xx, bad JSON): HTTP 200 with `{ "records": [], "nextSeq": <since>, "mockUp": false, "error": "mock unreachable" }`. The drawer shows "(The mock is not reachable.)" only when `mockUp` is `false`.
- The drawer reads `/demo/log` on its own origin; the browser makes no request to the mock. All five backends behave the same. The engine scanner ignores demo routes.
