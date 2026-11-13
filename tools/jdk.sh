# Sourced by tools/run.sh and tools/preflight.sh: the one way this store finds a JDK.
# Order: JAVA_HOME, then the Homebrew openjdk@21, then /usr/libexec/java_home, then java on PATH.
# Sets JAVA_BIN (the java to run, or empty) and JDK_HOME (the home it came from, empty when it came from PATH).
find_jdk() {
  JAVA_BIN=""; JDK_HOME=""
  _brew=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JDK_HOME="$JAVA_HOME"
  elif [ -x "$_brew/bin/java" ]; then JDK_HOME="$_brew"
  elif _jh="$(/usr/libexec/java_home 2>/dev/null)" && [ -x "$_jh/bin/java" ]; then JDK_HOME="$_jh"
  fi
  if [ -n "$JDK_HOME" ]; then JAVA_BIN="$JDK_HOME/bin/java"
  elif command -v java >/dev/null 2>&1; then JAVA_BIN="$(command -v java)"; fi
}
