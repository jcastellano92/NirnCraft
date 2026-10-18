#!/usr/bin/env bash
# Resolves tools/modlist.txt into the packwiz pack in pack/ (one .pw.toml per mod), then re-hashes the index with
# tools/refresh_packwiz.py. Exits non-zero if any entry can't be resolved.
# Usage: tools/build-pack.sh
# Requires packwiz on PATH, python3, and network access to Modrinth/CurseForge.
# Skycraft Core is NOT added here: the committed pack never contains it. Builds inject it with
# tools/prepare-build-pack.sh <jar>.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACK="$ROOT/pack"
LIST="$ROOT/tools/modlist.txt"
REPORT="$ROOT/pack/RESOLVE_REPORT.md"
PY="$(command -v python3 || command -v python || { command -v py >/dev/null && echo "py -3"; })"
command -v packwiz >/dev/null || { echo "packwiz is not on PATH" >&2; exit 2; }
$PY "$ROOT/tools/validate_pack.py"
cd "$PACK"

MARK="$(mktemp)"
trap 'rm -f "$MARK"' EXIT

echo "# Mod resolution report" > "$REPORT"
echo "" >> "$REPORT"
ok=0; failed=0
while IFS= read -r raw || [ -n "$raw" ]; do
  line="${raw%%#*}"
  line="$(echo "$line" | xargs)"
  [ -z "$line" ] && continue
  resolved=""
  IFS='|' read -ra options <<< "$line"
  # Already resolved earlier (committed .pw.toml)? Keep the pinned version.
  for opt in "${options[@]}"; do
    slug="$(echo "${opt#*:}" | xargs)"
    for dir in mods resourcepacks shaderpacks; do
      if [ -f "$PACK/$dir/$slug.pw.toml" ]; then resolved="$(echo "$opt" | xargs)"; fi
    done
  done
  if [ -n "$resolved" ]; then
    ok=$((ok+1)); echo "- [x] \`$resolved\` (pinned)" >> "$REPORT"; continue
  fi
  for opt in "${options[@]}"; do
    opt="$(echo "$opt" | xargs)"
    src="${opt%%:*}"; slug="${opt#*:}"
    case "$src" in
      mr) cmd=(packwiz modrinth add "$slug" -y) ;;
      cf) cmd=(packwiz curseforge add "$slug" -y) ;;
      *) echo "unknown source in: $opt" >&2; continue ;;
    esac
    echo ">>> ${cmd[*]}"
    touch "$MARK"
    sleep 1   # make sure files written by packwiz are strictly newer than the marker
    # Success = packwiz exits 0 AND it wrote a metadata file. Its messages are not parsed.
    if "${cmd[@]}" && [ -n "$(find mods resourcepacks shaderpacks -name '*.pw.toml' -newer "$MARK" 2>/dev/null | head -n1)" ]; then
      resolved="$opt"
      break
    fi
    echo "!!! could not resolve $opt" >&2
  done
  if [ -n "$resolved" ]; then
    ok=$((ok+1)); echo "- [x] \`$resolved\`" >> "$REPORT"
  else
    failed=$((failed+1)); echo "- [ ] **unresolved:** \`$line\`" >> "$REPORT"
  fi
done < "$LIST"

$PY "$ROOT/tools/refresh_packwiz.py" --pack "$PACK"
echo "" >> "$REPORT"
echo "Resolved: $ok, unresolved: $failed" >> "$REPORT"
cat "$REPORT"
if [ "$failed" -gt 0 ]; then
  echo "::error::$failed mod list entries could not be resolved (see pack/RESOLVE_REPORT.md)"
  exit 1
fi
