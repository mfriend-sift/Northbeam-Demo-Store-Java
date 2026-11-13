#!/usr/bin/env bash
# Lock probe: runs LockProbeTest (src/test/java) against the store's own servlet pipeline on a random loopback port.
# With the real lock the answers must be right; with the lock swapped for one that does nothing, they must go wrong
# (otherwise the probe is blind). Exit 0 when both hold. Uses no fixed port. Run it from anywhere:
#     tools/lock-probe/run.sh
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/tools/jdk.sh"; find_jdk; [ -n "$JDK_HOME" ] && export JAVA_HOME="$JDK_HOME"
cd "$ROOT" && exec ./mvnw -q -B test -Dtest=LockProbeTest -Dsurefire.failIfNoSpecifiedTests=true
