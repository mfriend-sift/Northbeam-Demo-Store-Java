#!/usr/bin/env bash
# Usage: tools/instrument.sh [--style library|raw] [--versions-dir DIR] [--engine heuristic|agent] [--model ID] [--budget USD] [--changes FILE]
#   Makes a full copy of this repo as Store_<short-name>_v<N> (its folder name minus the engine's optional stripPrefix) in DIR (default: Store-Versions beside the store folders, or env STORE_VERSIONS_DIR), instruments it with the Sift Integration
#   Engine (or, with --changes FILE, writes the page's exact files), installs dependencies and prints the command that runs it.
#   (The engine has no java entry yet: until it does, this script reports that the language is not registered.)
#   The implementation is the engine's: $ENGINE_DIR/app/tools/save-copy.mjs (the same for every language; run it with --help). This file only names
#   the language, so there is one copy of the logic instead of one per store.
# Env read: ENGINE_DIR   engine repo path (default: found in ../, ../../ or ../../../ of this repo)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -n "${ENGINE_DIR:-}" ] || for _c in "$ROOT/../Sift-Integration-Engine" "$ROOT/../../Sift-Integration-Engine" "$ROOT/../../../Sift-Integration-Engine"; do [ -f "$_c/app/tools/tree-hash.mjs" ] && { ENGINE_DIR="$(cd "$_c" && pwd)"; break; }; done
ENGINE_DIR="${ENGINE_DIR:-$ROOT/../Sift-Integration-Engine}"
TOOL="$ENGINE_DIR/app/tools/save-copy.mjs"
[ -f "$TOOL" ] || { echo "FAIL instrument: the engine's save-copy tool is not at $TOOL (set ENGINE_DIR)" >&2; exit 1; }
exec node --no-warnings "$TOOL" --store "$ROOT" --lang java "$@"
