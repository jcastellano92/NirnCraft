#!/usr/bin/env bash
# Makes a throwaway build copy of the packwiz pack with Skycraft Core injected.
# Usage: tools/prepare-build-pack.sh <skycraft-core.jar> [out-dir]   (out-dir defaults to dist/pack-build)
#
# The committed pack (pack/) never contains Skycraft Core: the jar is a build output and git doesn't track it. Every
# build (CI, tools/make-instances.sh, the mrpack/CurseForge exports) works on this copy instead, where the jar sits at
# mods/skycraft-core.jar and index.toml/pack.toml are re-hashed to include it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ $# -lt 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 <skycraft-core.jar> [out-dir]" >&2
  exit 2
fi
JAR="$1"
OUT="${2:-$ROOT/dist/pack-build}"
PY="$(command -v python3 || command -v python || { command -v py >/dev/null && echo "py -3"; })"

$PY "$ROOT/tools/validate_pack.py" --core-jar "$JAR"
# Resolve before cleanup: an incorrect caller path must not remove anything outside dist/.
$PY -c 'import pathlib,sys; p=pathlib.Path(sys.argv[1]).resolve(); d=pathlib.Path(sys.argv[2]).resolve(); sys.exit(0 if p.is_relative_to(d) and p!=d else "Build pack output must be a child of dist/")' "$OUT" "$ROOT/dist"
rm -rf "$OUT"
mkdir -p "$OUT"
cp -R "$ROOT/pack/." "$OUT/"
rm -f "$OUT/RESOLVE_REPORT.md"
# The committed .packwizignore hides the jar from `packwiz refresh`; in the build copy packwiz (e.g. the mrpack
# export, which refreshes the index first) must see it.
sed -i.bak '/^mods\/skycraft-core\.jar$/d' "$OUT/.packwizignore" && rm -f "$OUT/.packwizignore.bak"
cp "$JAR" "$OUT/mods/skycraft-core.jar"
$PY "$ROOT/tools/refresh_packwiz.py" --pack "$OUT" --with-core
echo "Build pack ready: $OUT"
