#!/usr/bin/env bash
# Exports the packed UV layouts of the realm creatures' rigs (tools/textures/rigs/<name>.json) for bestiary_realms.py,
# and their animations (mod/build/rigdump/anims/<name>.json) for the rigview.py previews.
# Compiles the plain-Java rig kit plus com.nirncraft.realms.client.rig with javac (no Gradle needed).
#
#   tools/textures/dump_realm_rigs.sh
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
REALM=mod/src/main/java/com/nirncraft/realms/client/rig
mkdir -p "$ROOT/mod/build"
WORK="$(mktemp -d "$ROOT/mod/build/realm-rigdump-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
ANIMS="$ROOT/mod/build/rigdump/anims"
mkdir -p "$ANIMS"
"$JAVAC" -encoding UTF-8 -d "$WORK/classes" $(find $RIG -name '*.java') $(find $REALM -name '*.java')
"$JAVA" -cp "$WORK/classes" com.nirncraft.realms.client.rig.RealmRigDump tools/textures/rigs "$ANIMS"
