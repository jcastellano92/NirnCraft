#!/usr/bin/env bash
# Builds ready-to-use downloads from the packwiz pack:
#   dist/Skycraft-client-instance.zip  Prism Launcher / MultiMC instance (import the zip)
#   dist/Skycraft-server.zip           Forge server with all server-side mods
# Usage: tools/make-instances.sh <skycraft-core.jar>
# The jar is injected into a build copy of the pack (tools/prepare-build-pack.sh -> dist/pack-build), which is also
# what the mrpack/CurseForge exports use.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
WORK="$(mktemp -d)"
MC=1.20.1
FORGE=47.4.10
PORT=8765
if [ $# -lt 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 <skycraft-core.jar>" >&2
  exit 2
fi
mkdir -p "$DIST"
PACK_BUILD="$DIST/pack-build"
bash "$ROOT/tools/prepare-build-pack.sh" "$1" "$PACK_BUILD"

# Serve the build pack over HTTP for packwiz-installer.
(cd "$PACK_BUILD" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1) &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true; rm -rf "$WORK"' EXIT
for _ in $(seq 1 30); do
  curl -fs "http://127.0.0.1:$PORT/pack.toml" >/dev/null && break
  sleep 1
done

# packwiz-installer is pinned (bootstrap + installer, both checksummed) so builds are reproducible: the bootstrap
# would otherwise fetch whatever packwiz-installer release is newest.
BOOT="$WORK/packwiz-installer-bootstrap.jar"
INSTALLER="$WORK/packwiz-installer.jar"
curl -fsSL -o "$BOOT" https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar
curl -fsSL -o "$INSTALLER" https://github.com/packwiz/packwiz-installer/releases/download/v0.5.14/packwiz-installer.jar
echo "a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c  $BOOT" | sha256sum -c -
echo "c9f646908d340d84773948a9a7d98bc1dae250d35e1016dc6e2b8459760b5598  $INSTALLER" | sha256sum -c -
install_pack() { # <side> <dir>
  (cd "$2" && java -jar "$BOOT" --bootstrap-no-update --bootstrap-main-jar "$INSTALLER" -g -s "$1" \
    "http://127.0.0.1:$PORT/pack.toml")
}

# ---------------------------------------------------------------- client instance (Prism/MultiMC format)
INST="$WORK/Skycraft"
mkdir -p "$INST/.minecraft"
install_pack client "$INST/.minecraft"
test -f "$INST/.minecraft/mods/skycraft-core.jar" || { echo "Skycraft Core missing from the client instance" >&2; exit 1; }
# Heap size comes from Min/MaxMemAlloc (Prism adds -Xms/-Xmx itself); JvmArgs only carries GC flags and
# -Dessential.autoUpdate=false, which stops the Essential loader from replacing the pinned Essential version.
cat > "$INST/instance.cfg" <<CFG
InstanceType=OneSix
name=Skycraft
iconKey=default
OverrideJavaArgs=true
JvmArgs=-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -Dessential.autoUpdate=false
OverrideMemory=true
MinMemAlloc=2048
MaxMemAlloc=6144
CFG
cat > "$INST/mmc-pack.json" <<JSON
{
  "components": [
    {"uid": "net.minecraft", "version": "$MC", "important": true},
    {"uid": "net.minecraftforge", "version": "$FORGE"}
  ],
  "formatVersion": 1
}
JSON
rm -f "$DIST/Skycraft-client-instance.zip"
(cd "$WORK" && zip -qr "$DIST/Skycraft-client-instance.zip" Skycraft)

# ---------------------------------------------------------------- dedicated server
SRV="$WORK/server"
mkdir -p "$SRV"
curl -fsSL -o "$SRV/forge-installer.jar" "https://maven.minecraftforge.net/net/minecraftforge/forge/$MC-$FORGE/forge-$MC-$FORGE-installer.jar"
(cd "$SRV" && java -jar forge-installer.jar --installServer >/dev/null && rm -f forge-installer.jar forge-installer.jar.log)
install_pack server "$SRV"
test -f "$SRV/mods/skycraft-core.jar" || { echo "Skycraft Core missing from the server" >&2; exit 1; }
cat > "$SRV/server.properties" <<PROPS
# Skycraft defaults
motd=Skycraft - The Elder Scrolls in Minecraft
difficulty=normal
# giants launch players and shouts fling them: don't kick for "flying"
allow-flight=true
# heavy modded worldgen can stall a tick: give the watchdog two minutes before it stops the server
max-tick-time=120000
view-distance=10
simulation-distance=8
spawn-protection=0
enable-command-block=false
sync-chunk-writes=true
online-mode=true
pvp=true
PROPS
cat > "$SRV/user_jvm_args.txt" <<ARGS
-Xms4G
-Xmx8G
-XX:+UseG1GC
-XX:+ParallelRefProcEnabled
-XX:MaxGCPauseMillis=200
-XX:+UnlockExperimentalVMOptions
-XX:+DisableExplicitGC
-XX:G1NewSizePercent=30
-XX:G1MaxNewSizePercent=40
-XX:G1HeapRegionSize=8M
-XX:G1ReservePercent=20
ARGS
cat > "$SRV/README.txt" <<TXT
Skycraft dedicated server (Minecraft $MC, Forge $FORGE)

1. Read and accept the Minecraft EULA (https://aka.ms/MinecraftEULA) by setting eula=true in eula.txt.
2. Start with ./run.sh (Linux/macOS) or run.bat (Windows).
3. Give the server 6-10 GB of RAM in user_jvm_args.txt. Distant Horizons builds distant terrain in the background.
TXT
rm -f "$DIST/Skycraft-server.zip"
(cd "$WORK" && zip -qr "$DIST/Skycraft-server.zip" server)
ls -la "$DIST"
