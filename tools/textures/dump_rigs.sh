#!/usr/bin/env bash
# Exports the packed UV layout of every creature rig (tools/textures/rigs/<name>.json) for rigpaint.py, and their
# animations (mod/build/rigdump/anims/<name>.json) for the rigview.py previews.
# The rig kit (com.nirncraft.creatures.client.rig) is plain Java, so this needs only a JDK 17.
#
#   tools/textures/dump_rigs.sh              all creature definition groups
#   tools/textures/dump_rigs.sh MorrowindDefs   one group only (compiles just the kit and that group)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
if ! command -v "$JAVAC" >/dev/null 2>&1 && [ -x "/c/Program Files/Java/jdk-17/bin/javac.exe" ]; then
  JAVAC="/c/Program Files/Java/jdk-17/bin/javac.exe"
  JAVA="/c/Program Files/Java/jdk-17/bin/java.exe"
fi
cd "$ROOT"
RIG=mod/src/main/java/com/nirncraft/creatures/client/rig
WORK="$(mktemp -d "$ROOT/mod/build/rigdump-XXXXXX" 2>/dev/null || (mkdir -p "$ROOT/mod/build" && mktemp -d "$ROOT/mod/build/rigdump-XXXXXX"))"
trap 'rm -rf "$WORK"' EXIT
ANIMS="$ROOT/mod/build/rigdump/anims"
mkdir -p "$ANIMS"
if [ $# -ge 1 ]; then
  GROUP="$1"
  mkdir -p "$WORK/src"
  # a stand-in registry that only registers the requested group
  sed -e "/^    static {/,/^    }/c\\    static {\\n        $GROUP.register();\\n    }" "$RIG/defs/Defs.java" > "$WORK/src/Defs.java"
  "$JAVAC" -encoding UTF-8 -d "$WORK/classes" $(ls $RIG/*.java) "$WORK/src/Defs.java" "$RIG/defs/$GROUP.java"
else
  "$JAVAC" -encoding UTF-8 -d "$WORK/classes" $(find $RIG -name '*.java')
fi
"$JAVA" -cp "$WORK/classes" com.nirncraft.creatures.client.rig.RigDump tools/textures/rigs "$ANIMS"
