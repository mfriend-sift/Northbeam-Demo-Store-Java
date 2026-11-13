#!/usr/bin/env bash
# Usage: tools/run.sh [--no-mock] [--port 3000]     (an optional language word is accepted and ignored)
#   Starts the mock (unless --no-mock or MOCK_PORT already answers GET /__log), computes the tree hash,
#   and starts this repo's backend on 127.0.0.1:<port> serving public/. Ctrl-C stops both.
#   --no-mock   use a mock that is already running on MOCK_PORT
# Env read:  ENGINE_DIR   engine repo path (default: found in ../, ../../ or ../../../ of this repo)
#            SIFT_API_KEY default sandbox_demo_key
# Env set for the backend: PORT=3000  DEMO=1  BUILD_HASH=<tree hash>
#            SIFT_API_URL=http://localhost:4010  SIFT_API_KEY=<as above>
# This script is the only place that sets DEMO=1.
# Passed through unchanged (the backend reads it): ENGINE_ORIGIN, the engine page origin CORS answers for under DEMO=1
#   (default http://localhost:4010; only a plain http(s)://host[:port] value is accepted, anything else falls back to the default).
# Also read (defaults keep the behavior above): MOCK_PORT (4010), STORE_VERSION (the saved-version folder name,
#   else 'clean'; passed to the backend for /api/config). When this script starts the mock, the mock's store
#   launcher may replace the store: the backend pid is kept in a mode-600 file in a private temp dir
#   (STORE_PIDFILE for the mock), the script lives as long as the mock, and Ctrl-C or the mock exiting stops
#   the mock, this store, and any store the launcher started.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LANG_ID=java
case "${1:-}" in node|python|ruby|php|dotnet|java) shift ;; esac    # compatibility: the language word is ignored
PORT=3000; START_MOCK=1
while [ $# -gt 0 ]; do
  case "$1" in
    --no-mock) START_MOCK=0 ;;
    --port) PORT="${2:?--port needs a number}"; shift ;;
    -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

[ -n "${ENGINE_DIR:-}" ] || for _c in "$ROOT/../Sift-Integration-Engine" "$ROOT/../../Sift-Integration-Engine" "$ROOT/../../../Sift-Integration-Engine"; do [ -f "$_c/app/tools/tree-hash.mjs" ] && { ENGINE_DIR="$(cd "$_c" && pwd)"; break; }; done
export ENGINE_DIR="${ENGINE_DIR:-$ROOT/../Sift-Integration-Engine}"
KEY="${SIFT_API_KEY:-sandbox_demo_key}"
MOCK_PORT="${MOCK_PORT:-4010}"
case "$MOCK_PORT" in ''|*[!0-9]*) echo "MOCK_PORT must be a number" >&2; exit 2;; esac
if [ -z "${STORE_VERSION:-}" ]; then
  case "$(basename "$ROOT")" in Store_*_v[0-9]*) STORE_VERSION="$(basename "$ROOT")" ;; *) STORE_VERSION=clean ;; esac
fi


# Preflight checks the chosen port and the mock port (the mock port may already be the running mock).
MOCK_PORT="$MOCK_PORT" "$ROOT/tools/preflight.sh" --port "$PORT" || { echo "preflight failed" >&2; exit 1; }

PIDS=(); MOCK_PID=""; STORE_PID=""; RUN_TMP=""
cleanup() {
  [ ${#PIDS[@]} -gt 0 ] && kill "${PIDS[@]}" 2>/dev/null
  # Backstop for a mock that died without stopping its launcher store: the pidfile then holds the
  # launcher's run.sh (a process-group leader). Stop that group only if it is still a store run.sh.
  if [ -n "$RUN_TMP" ]; then
    lp="$(cat "$RUN_TMP/store.pid" 2>/dev/null)"
    case "$lp" in ''|*[!0-9]*) ;; *)
      if [ "$lp" != "$STORE_PID" ] && ps -o command= -p "$lp" 2>/dev/null | grep -q 'tools/run\.sh'; then kill -TERM -- "-$lp" 2>/dev/null; fi ;;
    esac
    rm -rf "$RUN_TMP"
  fi
}
# The EXIT trap only cleans up, so a failed preflight or build keeps its own exit status; a stop (TERM, or Ctrl-C) is a normal end.
trap cleanup EXIT
trap 'exit 0' TERM
trap 'exit 130' INT

if [ "$START_MOCK" = 1 ] && ! curl -fsS -m 2 "http://127.0.0.1:$MOCK_PORT/__log" >/dev/null 2>&1; then
  RUN_TMP="$(mktemp -d "${TMPDIR:-/tmp}/store-run.XXXXXX")" || { echo "mktemp failed" >&2; exit 1; }
  chmod 700 "$RUN_TMP"
  (umask 077; : > "$RUN_TMP/store.pid")
  STORE_PIDFILE="$RUN_TMP/store.pid" STORE_PORT="$PORT" STORE_LIVE_NAME="$STORE_VERSION" STORE_LIVE_BACKEND="$LANG_ID" \
    node "$ENGINE_DIR/app/tools/mock-sift/server.mjs" --port "$MOCK_PORT" &
  MOCK_PID=$!
  PIDS+=($MOCK_PID)
  for _ in $(seq 1 25); do curl -fsS -m 1 "http://127.0.0.1:$MOCK_PORT/__log" >/dev/null 2>&1 && break; sleep 0.2; done
fi

# Flat layout: the engine's tree-hash detects it (no backends/ folder). If the engine cannot hash this
# layout yet, still start the store with an empty BUILD_HASH.
BUILD_HASH="$(node "$ENGINE_DIR/app/tools/tree-hash.mjs" "$ROOT" "$LANG_ID" 2>/dev/null)" || BUILD_HASH=""
[ -n "$BUILD_HASH" ] || echo "WARN tree hash unavailable (is the engine's flat-layout tree-hash.mjs in place?); BUILD_HASH is empty" >&2
echo "tree hash $BUILD_HASH"

export PORT DEMO=1 BUILD_HASH STORE_VERSION SIFT_API_URL="http://localhost:$MOCK_PORT" SIFT_API_KEY="$KEY"
# The engine page is served by the mock, so a store started by hand on another pair answers CORS for that origin (the launcher sets ENGINE_ORIGIN itself).
export ENGINE_ORIGIN="${ENGINE_ORIGIN:-http://localhost:$MOCK_PORT}"
# Build the jar (the first build downloads Spring Boot from Maven Central into ~/.m2), then run it with exec so STORE_PID is the JVM itself.
. "$ROOT/tools/jdk.sh"; find_jdk
[ -n "$JDK_HOME" ] && export JAVA_HOME="$JDK_HOME"
[ -n "$JAVA_BIN" ] || { echo "no JDK found (see README)" >&2; exit 1; }
(cd "$ROOT" && ./mvnw -q -B -DskipTests package) || { echo "build failed" >&2; exit 1; }
(cd "$ROOT" && exec "$JAVA_BIN" -jar target/store.jar) &
STORE_PID=$!
PIDS+=($STORE_PID)
if [ -n "$MOCK_PID" ]; then
  (umask 077; echo "$STORE_PID" > "$RUN_TMP/store.pid")
  # The launcher may replace this store, so live as long as the mock does (bash 3.2: no wait -n).
  while kill -0 "$MOCK_PID" 2>/dev/null; do sleep 1 & wait $!; done
else
  wait "$STORE_PID"
fi
