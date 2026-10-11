#!/usr/bin/env bash
# usage: merge_wt.sh <worktree path> <name>
set -euo pipefail
WT="$1"; NAME="$2"
SP="${SKYCRAFT_PATCH_DIR:-$(mktemp -d)}"
MAIN="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$WT"
git add -A -- . ':!mod/build' ':!mod/run' ':!mod/bin' ':!mod/.gradle' 2>/dev/null || git add -A
git add -f mod/src 2>/dev/null || true
git diff --cached --binary HEAD > "$SP/$NAME.patch"
git diff --cached --stat HEAD | tail -3
cd "$MAIN"
git apply --3way --whitespace=nowarn "$SP/$NAME.patch" && echo "APPLIED $NAME" || { echo "CONFLICT $NAME"; git diff --name-only --diff-filter=U; }
