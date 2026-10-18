#!/usr/bin/env bash
# Native hash-locked client + complete installed server. Does not start Minecraft.
# Usage: SKYCRAFT_JAVA=/path/to/java25 tools/make-instances.sh <native-core.jar>
# SKYCRAFT_PACKAGE_DIR may select a fresh candidate directory on the NVMe.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ $# -ne 1 ] || [ ! -f "$1" ]; then echo "usage: $0 <native-skycraft-core.jar>" >&2; exit 2; fi
if command -v py >/dev/null 2>&1; then PY=(py -3); else PY=(python3); fi
JAVA="${SKYCRAFT_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA="${JAVA:-$(command -v java || true)}"
if [ -z "$JAVA" ]; then echo 'Set SKYCRAFT_JAVA to a Java 25 executable.' >&2; exit 2; fi
OUT="${SKYCRAFT_PACKAGE_DIR:-$ROOT/dist}"
CLIENT="$OUT/Skycraft-26.3-client-instance.zip"
SERVER="$OUT/Skycraft-26.3-server.zip"
if [ -e "$CLIENT" ] || [ -e "$SERVER" ]; then
    echo 'Existing packages are preserved. Set SKYCRAFT_PACKAGE_DIR to a fresh directory.' >&2; exit 2
fi
"${PY[@]}" "$ROOT/tools/build_server_instance.py" --core-jar "$1" --java "$JAVA" --output-zip "$SERVER" --work-root "${SKYCRAFT_SERVER_BUILD_ROOT:-$ROOT/dist/server-build-263}"
"${PY[@]}" "$ROOT/tools/build_prism_instance.py" --core-jar "$1" --output-zip "$CLIENT"
