#!/usr/bin/env bash
# usage: merge_wt.sh <worktree path> <name>
set -euo pipefail
WT="$1"; NAME="$2"
SP="${NIRNCRAFT_PATCH_DIR:-$(mktemp -d)}"
MAIN="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$WT"
git add -A -- . ':!mod/build' ':!mod/run' ':!mod/bin' ':!mod/.gradle' ':!*__pycache__*' ':!*.pyc' 2>/dev/null || git add -A
git add -f mod/src 2>/dev/null || true
git diff --cached --binary HEAD -- . ":!*__pycache__*" ":!*.pyc" > "$SP/$NAME.patch"
git diff --cached --stat HEAD | tail -3
cd "$MAIN"
git apply --3way --whitespace=nowarn "$SP/$NAME.patch" && echo "APPLIED $NAME" || { echo "CONFLICT $NAME"; git diff --name-only --diff-filter=U; }

# --- what to run next (keeps every gate, one boot) ---------------------------------------------------------------
echo
echo "NEXT (from $MAIN):"
echo "  powershell -File tools/qa/qa.ps1 -Server -Rebake     # build + static checks + ONE server boot; re-bakes in the same run if stale (PASS 'rebaked')"
if grep -qE '^\+\+\+ b/(mod/src/main/java/com/nirncraft/(roads|worldplan)/|tools/qa/road)' "$SP/$NAME.patch" 2>/dev/null; then
  echo "  road/worldplan code changed: iterate with  tools/qa/quick_corridor.ps1 whiterun:riverwood  (~15 s);"
  echo "  gate once per phase with                   tools/qa/full_corridors.ps1"
fi
echo "  then commit. Release builds stay strict: tools/build_release.ps1 (bakes explicitly, then qa.ps1 -Server -Bake)."
