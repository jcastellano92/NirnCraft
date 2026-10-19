#!/usr/bin/env bash
# Exports the packed UV layout of the seas module's creature rigs (tools/textures/rigs/<name>.json) for seas.py.
# The rig kit and SeaDefs are plain Java, so this needs only a JDK 17.
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
SEA=mod/src/main/java/com/nirncraft/seas/client/rig
mkdir -p mod/build
WORK="$(mktemp -d "$ROOT/mod/build/seadump-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
"$JAVAC" -encoding UTF-8 -d "$WORK" $(ls $RIG/*.java | grep -v RigDump.java) "$SEA/SeaDefs.java" "$SEA/SeaRigDump.java"
"$JAVA" -cp "$WORK" com.nirncraft.seas.client.rig.SeaRigDump tools/textures/rigs
