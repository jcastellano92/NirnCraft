#!/usr/bin/env bash
# CI smoke test: boots the packaged Skycraft server (dist/Skycraft-server.zip), lets it generate the spawn area, runs
# a few commands, stops it, and fails if:
#   * it never finished starting, didn't stop within the time limit, or wrote a crash report;
#   * the log has a client class loaded on the server ("Attempted to load class ... for invalid dist"),
#     a NoClassDefFoundError / ClassNotFoundException, or any exception stack trace that goes through com.skycraft.
# Logs are copied to dist/server-test/.
# NOTE: this sets eula=true inside the throwaway CI directory only, to run the automated test.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist/server-test"
WORK="$(mktemp -d)"
START_LIMIT=900     # seconds to reach "Done ("
TOTAL_LIMIT=1500    # hard limit for the whole server process
rm -rf "$OUT"
mkdir -p "$OUT"
unzip -q "$ROOT/dist/Skycraft-server.zip" -d "$WORK"
SRV="$WORK/server"
cd "$SRV"
echo "eula=true" > eula.txt
cat > server.properties <<PROPS
online-mode=false
level-seed=Nirn
view-distance=6
simulation-distance=6
spawn-protection=0
max-tick-time=120000
PROPS
cat > user_jvm_args.txt <<ARGS
-Xms2G
-Xmx5G
ARGS
mkfifo console
( timeout --kill-after=30 "$TOTAL_LIMIT" ./run.sh nogui < console > console.log 2>&1; echo "EXIT $?" >> console.log ) &
RUNNER=$!
exec 3> console
started=0
for i in $(seq 1 $((START_LIMIT / 5))); do
  sleep 5
  if grep -q "Done (" console.log; then started=1; break; fi
  if grep -q "^EXIT" console.log; then break; fi
done
stopped=0
if [ "$started" = 1 ]; then
  echo "Server started after ~$((i*5))s"
  for cmd in "forceload add -64 -64 64 64" "locate structure #minecraft:village" "locate structure #skycraft:dungeons" \
             "skycraft-roads info" "list" "save-all"; do
    echo "$cmd" >&3; sleep 8
  done
  sleep 30
  echo "stop" >&3
  for _ in $(seq 1 60); do sleep 3; grep -q "^EXIT" console.log && { stopped=1; break; }; done
fi
exec 3>&-
if [ "$stopped" != 1 ]; then
  kill "$RUNNER" 2>/dev/null; pkill -f "minecraftforge/forge/.*/unix_args.txt" 2>/dev/null || true
  sleep 5
fi
cp console.log "$OUT/console.log"
cp logs/latest.log "$OUT/latest.log" 2>/dev/null || true
cp logs/debug.log "$OUT/debug.log" 2>/dev/null || true
cp -r crash-reports "$OUT/" 2>/dev/null || true

LOG="$OUT/debug.log"; [ -f "$LOG" ] || LOG="$OUT/latest.log"
echo "=== All ERROR lines (first 150) ==="
grep -E "/ERROR\]|\[ERROR\]" "$OUT/latest.log" 2>/dev/null | head -150

fail=0
if [ "$started" != 1 ]; then echo "::error::Server did not finish starting within ${START_LIMIT}s"; tail -150 console.log; fail=1; fi
if [ "$started" = 1 ] && [ "$stopped" != 1 ]; then echo "::error::Server did not stop cleanly"; fail=1; fi
if ls "$OUT"/crash-reports/*.txt >/dev/null 2>&1; then
  echo "::error::Crash report produced"; head -80 "$OUT"/crash-reports/*.txt; fail=1
fi
if grep -nE "Attempted to load class .* for invalid dist|NoClassDefFoundError|ClassNotFoundException: com\.skycraft" \
     "$LOG" "$OUT/console.log" 2>/dev/null | head -40 | grep .; then
  echo "::error::Class loading failure on the dedicated server (client class in common code?)"; fail=1
fi
# Any stack trace with a com.skycraft frame, or an exception message naming a com.skycraft class.
if grep -nE "^\s+at com\.skycraft\.|(Exception|Error)[^ ]*:.*com\.skycraft\." "$LOG" "$OUT/console.log" 2>/dev/null \
     | head -60 | grep .; then
  echo "=== context ==="
  grep -n -B12 -m5 -E "^\s+at com\.skycraft\." "$LOG" 2>/dev/null | head -120
  echo "::error::Exception stack trace from Skycraft code"; fail=1
fi
if [ "$fail" != 0 ]; then exit 1; fi
echo "Server smoke test passed"
