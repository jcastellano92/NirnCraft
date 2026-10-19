#!/usr/bin/env bash
# Actual full-mod native server gate. Only controls its own child Java process.
# Coordinate runtime access before running beside the Minecraft preview.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if command -v py >/dev/null 2>&1; then PY=(py -3); else PY=(python3); fi
JAVA="${NIRNCRAFT_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA="${JAVA:-$(command -v java || true)}"
if [ -z "$JAVA" ]; then echo 'Set NIRNCRAFT_JAVA to a Java 25 executable.' >&2; exit 2; fi
exec "${PY[@]}" "$ROOT/tools/qa/test_server_package_263.py" --java "$JAVA" "$@"
