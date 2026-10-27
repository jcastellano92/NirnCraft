#!/usr/bin/env bash
# Actual permission tests need a nonroot UID and a writable native filesystem, independent of build/repo ownership.
set -euo pipefail
[ "$#" = 1 ] && [[ "$1" = /* ]] && [ -x "$1" ] || { echo 'FAIL cache lifetime runner requires an absolute executable'; exit 2; }
cache_lifetime_exe="$1"
cache_lifetime_tmp="$(mktemp -d /tmp/nirncraft-cache-life-run.XXXXXX)"
cache_lifetime_task=''
cleanup() {
  trap - EXIT INT TERM
  if [ -n "$cache_lifetime_task" ]; then
    # timeout owns this process group; signal only the job we started, never process names or other clients.
    kill -TERM -- "-$cache_lifetime_task" 2>/dev/null || true
    kill -KILL -- "-$cache_lifetime_task" 2>/dev/null || true
    wait "$cache_lifetime_task" 2>/dev/null || true
  fi
  case "$cache_lifetime_tmp" in /tmp/nirncraft-cache-life-run.*) rm -rf -- "$cache_lifetime_tmp" ;; *) echo 'FAIL unsafe cache lifetime temporary path' >&2; exit 2 ;; esac
  [ ! -e "$cache_lifetime_tmp" ] || { echo 'FAIL cache lifetime temporary cleanup incomplete' >&2; exit 2; }
  echo "TRACE cache lifetime runner cleaned $cache_lifetime_tmp"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cp -- "$cache_lifetime_exe" "$cache_lifetime_tmp/test"
cmp -s -- "$cache_lifetime_exe" "$cache_lifetime_tmp/test"
chmod 755 "$cache_lifetime_tmp/test"
mkdir "$cache_lifetime_tmp/work"
cache_lifetime_uid="$(id -u)"
cache_lifetime_cmd=(bash -c 'ulimit -v 2097152; exec "$1"' cache-lifetime "$cache_lifetime_tmp/test")
if [ "$cache_lifetime_uid" = 0 ]; then
  command -v runuser >/dev/null || { echo 'FAIL root cache lifetime test requires runuser'; exit 2; }
  cache_lifetime_uid="$(id -u nobody)"; cache_lifetime_gid="$(id -g nobody)"
  [ "$cache_lifetime_uid" != 0 ] || { echo 'FAIL cache lifetime unprivileged account is root'; exit 2; }
  chmod 755 "$cache_lifetime_tmp"
  chown "$cache_lifetime_uid:$cache_lifetime_gid" "$cache_lifetime_tmp/work"
  cache_lifetime_cmd=(runuser -u nobody -- "${cache_lifetime_cmd[@]}")
fi
chmod 700 "$cache_lifetime_tmp/work"
cd "$cache_lifetime_tmp/work"
echo "TRACE cache lifetime standard runner: uid=$cache_lifetime_uid fs=$(stat -f -c %T "$cache_lifetime_tmp") cwd=$PWD"
timeout --kill-after=5s 45s "${cache_lifetime_cmd[@]}" &
cache_lifetime_task=$!
set +e
wait "$cache_lifetime_task"
cache_lifetime_status=$?
set -e
# Retain the owned group through cleanup so even an unexpectedly exiting test cannot leave its descendants behind.
exit "$cache_lifetime_status"
