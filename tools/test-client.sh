#!/usr/bin/env bash
# CI client smoke test (Linux, needs xvfb-run and Mesa software OpenGL).
#
# What it tests: the NirnCraft Core dev client (`./gradlew runClient`, Forge + NirnCraft only, not the full pack) boots
# under Xvfb, loads every NirnCraft resource (models, blockstates, textures, sounds, lang) and reaches the main menu
# without a crash. -Dnirncraft.autotest=true is passed to the game JVM (through JAVA_TOOL_OPTIONS, because Gradle's own
# -D flags never reach the forked game); if a world is loaded, ClientAutotest then walks through the Skyrim screens,
# saves screenshots to mod/run/build/autotest-screenshots and closes the game.
#
# It fails on: a crash report, the game exiting with an error, never finishing resource loading within the time
# limit, any exception stack trace through com.nirncraft, a client class loaded on the wrong side, or a missing
# nirncraft model/texture/blockstate.
# It does NOT test the full modpack's client (mod conflicts): the server smoke test covers the pack's common side.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist/client-test"
LOAD_LIMIT=900      # seconds until resources must have finished loading
SETTLE=60           # seconds to keep the client running at the main menu afterwards
rm -rf "$OUT"
mkdir -p "$OUT"
LOG="$OUT/client.log"
cd "$ROOT/mod"
rm -rf run/crash-reports run/build/autotest-screenshots

echo "=== NirnCraft client smoke test (Xvfb + Mesa software OpenGL) ==="
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dnirncraft.autotest=true"
export LIBGL_ALWAYS_SOFTWARE=1
setsid xvfb-run -a -s "-screen 0 1280x800x24" ./gradlew runClient --no-daemon --console=plain > "$LOG" 2>&1 &
GAME=$!
stop_game() {
  kill -TERM -- "-$GAME" 2>/dev/null || true
  for _ in $(seq 1 20); do kill -0 "$GAME" 2>/dev/null || break; sleep 1; done
  kill -KILL -- "-$GAME" 2>/dev/null || true
}
trap stop_game EXIT

loaded=0; exited=0; elapsed=0
while [ "$elapsed" -lt "$LOAD_LIMIT" ]; do
  sleep 5; elapsed=$((elapsed+5))
  if ! kill -0 "$GAME" 2>/dev/null; then exited=1; break; fi
  # The last texture atlases are stitched at the end of the resource reload that precedes the main menu.
  if grep -qE "Created: .*minecraft:textures/atlas/" "$LOG"; then loaded=1; break; fi
done
if [ "$loaded" = 1 ]; then
  echo "Resources loaded after ~${elapsed}s; letting the client run for ${SETTLE}s"
  for _ in $(seq 1 $((SETTLE / 5))); do
    sleep 5
    if ! kill -0 "$GAME" 2>/dev/null; then exited=1; break; fi
  done
fi
status=0
if [ "$exited" = 1 ]; then wait "$GAME"; status=$?; else stop_game; fi
trap - EXIT

cp -r run/build/autotest-screenshots "$OUT/" 2>/dev/null || true
cp -r run/crash-reports "$OUT/" 2>/dev/null || true
cp run/logs/latest.log "$OUT/latest.log" 2>/dev/null || true
cp run/logs/debug.log "$OUT/debug.log" 2>/dev/null || true

fail=0
autotest_done=0
grep -q "NirnCraft Autotest: Completed successfully" "$LOG" && autotest_done=1
if [ "$loaded" != 1 ] && [ "$autotest_done" != 1 ]; then
  echo "::error::Client never finished loading resources"; tail -120 "$LOG"; fail=1
fi
if [ "$exited" = 1 ] && [ "$status" != 0 ] && [ "$autotest_done" != 1 ]; then
  echo "::error::Client exited with status $status"; tail -80 "$LOG"; fail=1
fi
if ls "$OUT"/crash-reports/*.txt >/dev/null 2>&1 || grep -q -- "---- Minecraft Crash Report ----" "$LOG"; then
  echo "::error::Crash report produced"; head -80 "$OUT"/crash-reports/*.txt 2>/dev/null; fail=1
fi
if grep -nE "Attempted to load class .* for invalid dist|NoClassDefFoundError" "$LOG" | head -20 | grep .; then
  echo "::error::Class loading failure"; fail=1
fi
if grep -nE "^\s+at com\.nirncraft\.|(Exception|Error)[^ ]*:.*com\.nirncraft\." "$LOG" | head -40 | grep .; then
  grep -n -B12 -m5 -E "^\s+at com\.nirncraft\." "$LOG" | head -120
  echo "::error::Exception stack trace from NirnCraft code"; fail=1
fi
if grep -nE "(Missing textures in model|Unable to load model|Exception loading blockstate definition|Unable to resolve texture|Using missing texture).*nirncraft:" "$LOG" | head -40 | grep .; then
  echo "::error::NirnCraft model/texture errors"; fail=1
fi
if [ "$fail" != 0 ]; then exit 1; fi
if [ "$autotest_done" = 1 ]; then echo "Client smoke test passed (screen autotest completed)."
else echo "Client smoke test passed (booted to the main menu; no world was loaded, so the screen autotest did not run)."; fi
