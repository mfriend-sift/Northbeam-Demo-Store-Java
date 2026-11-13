#!/usr/bin/env bash
# Usage: tools/preflight.sh [--port 3000]     (an optional language word is accepted and ignored)
#   One line per check: "OK <check> <detail>" or "FAIL <check> <detail>"; exit 1 if any FAIL.
#   Checks: node >= 18 (the contract test and the engine tools); a JDK (java 21 or newer; JAVA_HOME, else java on PATH, else the Homebrew openjdk@21); the store port (default 3000) and the mock port (MOCK_PORT, default 4010)
#           free (or the mock already up there); ENGINE_DIR exists and contains app/tools/tree-hash.mjs;
#           how localhost resolves.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LANG_ID=java
case "${1:-}" in node|python|ruby|php|dotnet|java) shift ;; esac
PORT=3000
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="${2:?--port needs a number}"; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
[ -n "${ENGINE_DIR:-}" ] || for _c in "$ROOT/../Sift-Integration-Engine" "$ROOT/../../Sift-Integration-Engine" "$ROOT/../../../Sift-Integration-Engine"; do [ -f "$_c/app/tools/tree-hash.mjs" ] && { ENGINE_DIR="$(cd "$_c" && pwd)"; break; }; done
ENGINE_DIR="${ENGINE_DIR:-$ROOT/../Sift-Integration-Engine}"
bad=0
ok()   { echo "OK $1 $2"; }
fail() { echo "FAIL $1 $2"; bad=1; }

if command -v node >/dev/null; then
  major=$(node -p 'process.versions.node.split(".")[0]')
  if [ "$major" -ge 18 ]; then ok node "$(node -v)"; else fail node "$(node -v) is older than 18"; fi
else fail node "not found"; fi

JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then JAVA_BIN=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java
elif /usr/libexec/java_home >/dev/null 2>&1; then JAVA_BIN="$(/usr/libexec/java_home)/bin/java"
elif command -v java >/dev/null; then JAVA_BIN="$(command -v java)"; fi
if [ -n "$JAVA_BIN" ]; then
  jmajor=$("$JAVA_BIN" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
  if [ "${jmajor:-0}" -ge 21 ] 2>/dev/null; then ok java "$jmajor ($JAVA_BIN)"; else fail java "${jmajor:-unknown} is older than 21 (the store targets Java 21)"; fi
else fail java "not found (install a JDK 21 or newer; see README)"; fi
if [ -f "$ROOT/pom.xml" ]; then ok backend "pom.xml"; else fail backend "pom.xml missing"; fi
port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
if port_busy "$PORT"; then fail "port-$PORT" "in use"; else ok "port-$PORT" free; fi
MP="${MOCK_PORT:-4010}"   # the mock's port; run.sh passes MOCK_PORT (default 4010)
if port_busy "$MP"; then
  if curl -fsS -m 2 "http://127.0.0.1:$MP/__log" >/dev/null 2>&1; then ok "port-$MP" "mock already running"
  else fail "port-$MP" "in use by something that is not the mock"; fi
else ok "port-$MP" free; fi

if [ -f "$ENGINE_DIR/app/tools/tree-hash.mjs" ]; then ok engine "$ENGINE_DIR"
else fail engine "ENGINE_DIR=$ENGINE_DIR has no app/tools/tree-hash.mjs"; fi

if command -v node >/dev/null; then
  ok localhost "$(node -e 'require("dns").lookup("localhost",{all:true},(e,a)=>console.log(e?e.code:a.map(x=>x.address).join(" ")))')"
fi
exit $bad
