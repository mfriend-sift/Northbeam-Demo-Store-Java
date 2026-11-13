# Build notes (Java port)

Judgement calls and findings from the unattended build of 2026-11-13 (plan: the engine repo, `docs/plan/2026-11-12/2026-11-12-java-demo-store-plan_v2.md`).

## Spring error-order spike (the plan's first step)

Result: Spring Boot 3.5 can be made exact; no switch to another framework was needed. Fixtures 17 (bad JSON after 404, 401, resource 404), 25 (415, 413, non-text fields) and 15 (demo routes without DEMO) pass, as do all 45 fixtures and 10 scenarios.

What it took:

- Every route takes `HttpServletRequest` and reads the raw body bytes itself (`Http.jsonBody`), after its own auth and resource checks. No `@RequestBody`, so Spring's `HttpMessageNotReadableException` never exists.
- The body, session, demo and error steps are one servlet filter (`lib/Middleware`) that runs before Spring's handler lookup; it reads a JSON body (at most 100 KB, else 413) before any lock.
- Answers are written straight to `HttpServletResponse` (`Reply.writeTo`), not returned as `ResponseEntity`. Reason: Spring's message-converter step reads the request's `Content-Type` charset; a request with `Content-Type: application/json; charset` (empty charset) turned into a 500 there. Found by the parity check.
- A catch-all `@RequestMapping("/**")` (`FallbackApi`) serves `public/` and the JSON 404, so a wrong method or an unknown path never reaches Spring's own 404/405 pages. `/error` and a `@RestControllerAdvice` give the same JSON shape for what the container or a route throws.
- Off: whitelabel page, resource mappings, the form-content and hidden-method filters, the servlet encoding filter, banner, JMX. No `HttpSession` is ever created (no `JSESSIONID`); the one `nb_sid` cookie is set by the filter.

## Where Tomcat is stricter, and what was done (`TomcatTuning`)

Tomcat rejects a path with a bad percent escape (`%zz`, or bytes that are not UTF-8) and an encoded slash (`%2F`) with an HTML 400 before any filter runs. The contract (as the .NET store reads it) wants a 400 JSON only for a bad escape inside a route parameter, and an ordinary unknown path elsewhere. Fix: the connector decodes `%2F`; a small `org.apache.coyote.Adapter` wrapper re-spells a path with bad escapes (every `%` as `%25`) so it matches no route, and keeps the original path on the request for `Http.checkParams` and the static-file lookup. This reaches into Tomcat's internals (`MessageBytes`); it is the one place that does, and it is isolated in one file.

## Deviations from the plan's text, with the reason

- **Trailing slash matching is ON**, not off. Plan section 4 rule 3 said off; the .NET store (the parity reference) and Express match `/api/products/`, and parity-check has a script for it. Matching the other stores is the goal.
- **Route matching is case-insensitive** (`/API/Products` works), also as in the .NET store.
- **Money is dollars in a `double`**, rounded to cents with JavaScript's `Math.round` rule (`Js.round`), not `long` cents. The contract's numbers are dollars (`89`, `24.5`) and the other stores compute the same way; a `long` would add a conversion that could print a different last digit. All rounding goes through `Js.round`, and JSON output through `Js.num` (JavaScript's number printing).
- **Contract counts are 52 / 0 / 3 (DEMO=1) and 32 / 0 / 23 (no DEMO)**, not the 51 / 31 in the brief and in the .NET README. The contract folder is byte-identical to the .NET store's (45 fixtures + 10 scenarios = 55 tests, 3 or 23 skipped); the .NET store also gives 52 passed when run (checked with `ENGINE_ORIGIN=http://localhost:4010`). The README's 51 predates a fixture.
- `tools/run.sh` (unchanged from A0) sets `ENGINE_ORIGIN` to the mock's address; with the build mock on 4070 the CORS fixture 14 (which pins 4010) fails unless the store is started with `ENGINE_ORIGIN=http://localhost:4010`. This is a test-setup effect, not a store difference.
- **Lock probe is a JUnit test** (`src/test/java/.../LockProbeTest`, run by `tools/lock-probe/run.sh`), not a separate project: it starts the real application on a random port. The lock is swappable through `Gate.useLock` (a seam used only by that test). Without the real lock the race is rare per round, so the probe runs up to 40 rounds with no lock and needs one wrong round.
- Password hashing is PBKDF2-HMAC-SHA256 written over `javax.crypto.Mac` (10,000 iterations, 8-byte salt, `salt:digest` hex), because the JDK's `PBEKeySpec` path refuses an empty password and takes `char[]`; the hash format matches the .NET store's.
- `X-Demo-Now` outside years 0001 to 9999 gives 500 as the .NET store does for a route that reads the clock, but a request that changes something (not GET, HEAD or OPTIONS) is refused in `Middleware.demo` before any change is made (the .NET store has already made it). The contract does not cover the case; parity-check does, and the answers match.

## For the engine's Java hints

- The session id is held on the request as an attribute (`Sessions.idOf`, `Sessions.of`), not read from `request.getCookies()`. A scan that looks for the session in `getCookies()` finds nothing in this store: the hint should say to read the store's session accessor, and to take the session id and the user id from it for `_session_id` and `$user_id`.

## Known gaps

- The Java `LockProbeTest` prints a Mockito/ByteBuddy agent warning on JDK 21 (from the Spring test starter, harmless).
- `tools/instrument.sh` stops at the engine's usage line until the engine has a `java` entry (Part B).
- Spring Boot 3.5 is out of open-source support (see the README); the version is pinned on purpose.
