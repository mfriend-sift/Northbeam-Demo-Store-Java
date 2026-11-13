# Mock Sift server: HTTP shape

Frozen 2026-10-08 (Plan 3 freeze item 9). The mock lives in the engine repo at `app/tools/mock-sift/` (owned by instance C). This file fixes what the store, the engine UI, and the tests may rely on. Additive changes (new endpoints, new optional fields) do not break the freeze; anything else is a contract PR.

## Process

- Start: `node "$ENGINE_DIR/app/tools/mock-sift/server.mjs" [--port 4010]`. Binds `127.0.0.1`. Default port 4010. `tools/run.sh` starts it (see FREEZE.md section 10 in the engine repo).
- In-memory only. Deterministic: the same requests in the same order give the same statuses, scores, and simulated latencies.
- Status codes, messages, and HTTP statuses come only from the engine's `data/api-catalog.json`. Required fields come only from `data/events-catalog.json`.

## Endpoints in the sprint

| Method and path | Purpose |
|---|---|
| `GET /` | Serves the engine page (`sift-integration-engine.html` at the engine repo root), `text/html`. The engine and the mock share one origin. |
| `POST /v205/events` | Events API: validate, log, respond. |
| `GET /__log?since=<seq>` | Log records with `seq` greater than `since` (default 0), ascending. |
| `POST /__reset` | Clears the log (the next `seq` is 1) and all simulated per-user state. Returns `{ "ok": true }`. |

Not in the sprint (additive later): Score API, Decisions, Labels, `/__token`, `/__clock`, `/s.js`, webhooks.

## `POST /v205/events`

Request:
- Auth: `Authorization: Basic base64("<api key>:")` or `$api_key` in the JSON body. A key is valid when it starts with `sandbox_` (seeded `sandbox_demo_key`). The store's patched helper reads `SIFT_API_KEY` and `SIFT_API_URL` (`run.sh` sets `http://localhost:4010`).
- Body: one JSON event; `$type` names it.
- Query: `return_score=true` adds `score_response` to the response. Other query flags (`return_workflow_status`, `fields`, `abuse_types`) are accepted and ignored in the sprint.

Checks, first failure wins:
1. Body is not valid JSON: status 56.
2. No valid key: status 51.
3. `$type` missing, or a reserved type (`$...`) not in the events catalog: status 50 (tag `documented` in the catalog).
4. A required group in the events catalog has no member present (for example `$user_id` or `$session_id`): status 55, `error_message` names the group.

Response body (HTTP status from the catalog: 200 for status 0, 400 for the rest except 60):

```json
{ "status": 0, "error_message": "OK", "time": 1760000000, "request": "<raw request body>" }
```

With `return_score=true` and status 0, the body also carries (shape marked [verify] in Plan 1 5.3):

```json
"score_response": {
  "status": 0, "error_message": "OK", "user_id": "shopper_1026",
  "scores": { "payment_abuse": { "score": 0.25, "reasons": [ { "name": "Simulated: new account", "value": "10" } ] } },
  "latest_labels": {}, "workflow_statuses": []
}
```

**The `return_score` rule.** A score appears in the response only when the call asks for it with `return_score=true`. The generated patch does not ask, so a patched store's responses carry no score. The log always carries the mock's score for every scored event (field `score`), and the engine shows it as a badge labeled Simulated.

Response headers: `Content-Type: application/json`, `X-Simulated: true`. Warnings (dollars for micros, raw card data, private IP) go to the log's `validation[]` only; the response does not carry them in the sprint.

## `GET /__log?since=<seq>`

```json
{ "records": [ <LogRecord>, ... ], "nextSeq": 7 }
```

- `nextSeq` is the `seq` the next record will get; poll with `since=nextSeq-1`.
- No token in the sprint (token handoff is P3-REF-09). CORS: `Access-Control-Allow-Origin: *`, no credentials, `GET` and `OPTIONS` only (the engine is same-origin; `*` lets a file:// engine read it).
- `LogRecord` is frozen in the engine repo, `docs/freeze/FREEZE.md` section 4, with an example in `docs/freeze/mock-log.example.json`:
  `{ seq, time, method, path, event, httpStatus, siftStatus, latencyMs, request{headers,body}, response{headers,body}, validation[], score? }`. Keys are masked at write time.
