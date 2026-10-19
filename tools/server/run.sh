#!/usr/bin/env sh
# Exact-platform launcher; run from any directory. Edit user_jvm_args.txt for RAM.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -n "${NIRNCRAFT_JAVA:-}" ]; then
    runtime_java=$NIRNCRAFT_JAVA
elif [ -n "${JAVA_HOME:-}" ]; then
    runtime_java=$JAVA_HOME/bin/java
else
    runtime_java=$(command -v java || true)
fi
if [ -z "$runtime_java" ] || [ ! -x "$runtime_java" ]; then
    echo 'Java 25 is required. Set NIRNCRAFT_JAVA to its java executable or JAVA_HOME to its installation.' >&2
    exit 2
fi
runtime_version=$("$runtime_java" -XshowSettings:properties -version 2>&1) || exit 2
runtime_major=$(printf '%s\n' "$runtime_version" | awk '/^[[:space:]]*java.specification.version =/ {print $3}')
if [ "$runtime_major" != 25 ]; then
    echo "NirnCraft requires Java 25; selected Java reports $runtime_major: $runtime_java" >&2
    exit 2
fi
native_args='libraries/net/neoforged/neoforge/@NEOFORGE@/unix_args.txt'
if [ ! -f "$native_args" ] || [ ! -f mods/nirncraft-core.jar ]; then
    echo 'The native server installation is incomplete. Extract the full NirnCraft server ZIP.' >&2
    exit 2
fi
exec "$runtime_java" @user_jvm_args.txt "@$native_args" "$@"
