"""Prepare the isolated exact-26.3 Prism preview. Never starts a game.

The old Forge preview and the owner's normal instances are never selected. An existing
26.3 preview is renamed to a timestamped backup only after the new package validates.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import time
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from validate_pack import TARGET, validate_core_jar, validate_pack

INSTANCE_NAME = TARGET["instanceName"]


def digest(path, algorithm="sha256"):
    result = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def exact_processes(prism_root, instance_name=INSTANCE_NAME):
    """Return process IDs only; launcher command lines contain credentials."""
    instance = Path(prism_root) / "instances" / instance_name
    pattern = re.escape(instance.as_posix()).replace("/", r"[/\\]") + r"[/\\]"
    script = r"""@(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and
        $_.CommandLine -match 'INSTANCE_PATTERN'
    } | ForEach-Object { $_.ProcessId }) | ConvertTo-Json -Compress""".replace("INSTANCE_PATTERN", pattern.replace("'", "''"))
    output = subprocess.run(["powershell", "-NoProfile", "-Command", script],
                            check=True, capture_output=True, text=True).stdout.strip()
    value = json.loads(output) if output else []
    return value if isinstance(value, list) else [value]


def java_25(path=None):
    candidates = [path] if path else [
        os.environ.get("SKYCRAFT_JAVA_25"),
        str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if os.environ.get("JAVA_HOME") else None,
        "E:/Skycraft-scratch/toolchains/jdk-25.0.4.1+1/bin/java.exe",
        shutil.which("java"),
    ]
    for candidate in candidates:
        if not candidate or not Path(candidate).is_file():
            continue
        executable = Path(candidate).resolve()
        result = subprocess.run([str(executable), "-XshowSettings:properties", "-version"],
                                capture_output=True, text=True, timeout=20)
        if result.returncode == 0 and re.search(r"java\.specification\.version\s*=\s*25(?:\s|$)", result.stderr):
            return executable
    raise ValueError("A working Java 25 executable is required; pass --java PATH_TO_JAVA_EXE")


def loopback_server(value):
    if not re.fullmatch(r"127\.0\.0\.1:[0-9]{1,5}", value):
        raise ValueError("QA server must be an explicit 127.0.0.1:PORT loopback address")
    if not 1 <= int(value.split(":")[1]) <= 65535:
        raise ValueError("QA server port is outside 1..65535")
    return value


def extract(archive, staging):
    """Accept one instance root, rejecting paths that could escape the staging area."""
    roots = set()
    with zipfile.ZipFile(archive) as source:
        for info in source.infolist():
            path = PurePosixPath(info.filename.replace("\\", "/"))
            if path.is_absolute() or ".." in path.parts or any(":" in part for part in path.parts):
                raise ValueError("Unsafe path in client archive")
            if not path.parts:
                continue
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Client archive must not contain symbolic links")
            roots.add(path.parts[0])
            target = staging.joinpath(*path.parts)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(info) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
    if len(roots) != 1:
        raise ValueError("Client archive must contain exactly one instance directory")
    return staging / roots.pop()


def validate_instance(instance, lock):
    components = json.loads((instance / "mmc-pack.json").read_text(encoding="utf-8-sig"))["components"]
    versions = {row["uid"]: row["version"] for row in components}
    if versions != {"net.minecraft": TARGET["minecraft"], "net.neoforged": TARGET["neoforge"]}:
        raise ValueError("Archive does not select exactly Minecraft 26.3 / NeoForge 26.3.0.90")
    game = instance / ".minecraft"
    expected = {row["filename"]: row for row in lock["active"] if row["side"] != "server"}
    actual = {path.name for path in (game / "mods").glob("*") if path.is_file()}
    if actual != set(expected) | {"skycraft-core.jar"}:
        raise ValueError("Archive contains missing, unpinned, or legacy active mods")
    for name, row in expected.items():
        if digest(game / "mods" / name, row["hashFormat"]) != row["hash"].lower():
            raise ValueError(f"Client dependency hash mismatch: {name}")
    validate_core_jar(game / "mods/skycraft-core.jar")
    packs = sorted(p.name for p in (game / "shaderpacks").iterdir()) if (game / "shaderpacks").exists() else []
    if [p for p in packs if p != "SkycraftAether.zip"]:
        raise ValueError(f"Only Skycraft's own shader pack may ship in the preview, found {packs}")
    iris = (game / "config/iris.properties").read_text(encoding="utf-8-sig")
    if not (re.search(r"^enableShaders=false$", iris, re.M)
            or (re.search(r"^enableShaders=true$", iris, re.M) and re.search(r"^shaderPack=SkycraftAether\.zip$", iris, re.M))):
        raise ValueError("Preview shaders must be off or Skycraft's own SkycraftAether.zip")
    embedded = json.loads((game / "migration/platform-lock.json").read_text(encoding="utf-8-sig"))
    if embedded != lock:
        raise ValueError("Archive was built with a different platform/dependency lock")


def configure(instance, java, *, live=False, tour="", extra="", width=1280, height=800,
              save_source=None, up=60, pitch=40, tour_wait=500,
              instance_name=INSTANCE_NAME, world="qa_world_263", server=""):
    if server:
        server = loopback_server(server)
        if save_source or tour:
            raise ValueError("Remote QA cannot combine --server with integrated save copying or tour generation")
    cfg = instance / "instance.cfg"
    fields = dict(line.split("=", 1) for line in cfg.read_text(encoding="utf-8-sig").splitlines()
                  if "=" in line and not line.startswith("#"))
    fields.update(name=instance_name, OverrideJavaLocation="true", JavaPath=java.as_posix(),
                  JavaVersion="25", OverrideWindow="true", MinecraftWinWidth=str(width),
                  MinecraftWinHeight=str(height), LaunchMaximized="false")
    # A properties file keeps complex arguments out of Prism/Windows shell parsing.
    properties = {"skycraft.autotest": "true", "skycraft.autotest.world": "" if server else world,
                  "skycraft.autotest.tour": tour.replace(" ", "+"), "skycraft.autotest.tourUp": str(up),
                  "skycraft.autotest.tourPitch": str(pitch), "skycraft.autotest.tourWait": str(tour_wait)}
    boot_flags = ["-Dskycraft.autotest=true"]
    if server:
        properties["skycraft.autotest.server"] = server
        boot_flags.append("-Dskycraft.autotest.server=" + server)
    if live:
        properties.update({"skycraft.autotest.live": "true", "skycraft.dev": "world-preview",
                           "skycraft.preview.resources": "world-preview/resources"})
        # Common-side preview subscribers may initialize before ClientAutotest reads the file.
        boot_flags += ["-Dskycraft.autotest.live=true", "-Dskycraft.dev=world-preview",
                       "-Dskycraft.preview.resources=world-preview/resources"]
    if save_source:
        save_source = Path(save_source).resolve()
        if not (save_source / "level.dat").is_file():
            raise ValueError("--save-source must be an existing save directory containing level.dat")
        shutil.copytree(save_source, instance / ".minecraft/saves" / save_source.name)
        properties.update({"skycraft.autotest.createflow": "true", "skycraft.autotest.load": save_source.name})
    fields["JvmArgs"] = fields.get("JvmArgs", "").strip('"') + " " + " ".join(boot_flags)
    if extra:
        # Extra JVM flags retain the existing tour API, while rejecting newlines/config injection.
        if "\n" in extra or "\r" in extra:
            raise ValueError("Extra JVM arguments must fit on one line")
        fields["JvmArgs"] += " " + extra
    cfg.write_text("\n".join(f"{key}={value}" for key, value in fields.items()) + "\n", encoding="utf-8")
    game = instance / ".minecraft"
    # java.util.Properties reads ISO-8859-1 and interprets backslashes; emit Unicode escapes.
    def escape(value):
        value = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
        encoded = value.encode("utf-16-be", "surrogatepass")
        return "".join(chr(int.from_bytes(encoded[i:i+2], "big")) if int.from_bytes(encoded[i:i+2], "big") < 128
                       else "\\u" + encoded[i:i+2].hex() for i in range(0, len(encoded), 2))
    (game / "skycraft_autotest.properties").write_text(
        "\n".join(f"{key}={escape(value)}" for key, value in properties.items()) + "\n", encoding="ascii")
    (game / "options.txt").write_text("\n".join([
        "onboardAccessibility:false", "pauseOnLostFocus:false", "skipMultiplayerWarning:true", "tutorialStep:none",
        "renderDistance:12", "simulationDistance:8", "maxFps:60", "fullscreen:false", "lastServer:", "",
        ]).replace("lastServer:\n", 'lastServer:\ninactivityFpsLimit:"minimized"\n'), encoding="ascii")
    # The harness drives an unfocused window: Dynamic FPS's default (1 fps when unfocused, 10 when idle) starves
    # chunk meshing and lighting so readiness never settles. Disabled in disposable test instances only.
    (game / "config").mkdir(parents=True, exist_ok=True)
    (game / "config" / "dynamic_fps.json").write_text('{\n    "enabled": false\n}\n', encoding="ascii")


def prepare(archive, prism_root, java, **options):
    lock = validate_pack()
    archive, prism_root = Path(archive).resolve(), Path(prism_root).resolve()
    if not archive.is_file():
        raise ValueError(f"Build the full native client zip first: {archive}")
    java = java_25(java)
    if exact_processes(prism_root):
        raise ValueError("The exact 26.3 preview is running; close it cleanly before replacing its files")
    if exact_processes(prism_root, "Skycraft-26.3-core-diagnostic"):
        raise ValueError("The core diagnostic is running; close it cleanly before preparing the full native preview")
    prism_root.mkdir(parents=True, exist_ok=True)
    instances = prism_root / "instances"
    instances.mkdir(exist_ok=True)
    destination = instances / INSTANCE_NAME
    with tempfile.TemporaryDirectory(prefix="skycraft-263-staging-", dir=prism_root) as temporary:
        source = extract(archive, Path(temporary))
        validate_instance(source, lock)
        configure(source, java, **options)
        receipt = {"target": TARGET, "archive": str(archive), "archiveSha256": digest(archive),
                   "coreSha256": digest(source / ".minecraft/mods/skycraft-core.jar"),
                   "java": str(java), "preparedAt": time.time(), "runtimeVerified": False,
                   "server": options.get("server") or None,
                   "expectedTransport": "tcp" if options.get("server") else "memory"}
        (source / ".minecraft/migration/preview-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        backup = None
        # Prism notices a complete instance on rename. Keep previous saves/screenshots in its backup.
        if destination.exists():
            backups = prism_root / "skycraft-263-backups"
            backups.mkdir(exist_ok=True)
            backup = backups / str(time.time_ns())
            destination.rename(backup)
        try:
            source.rename(destination)
        except Exception:
            if backup is not None:
                backup.rename(destination)
            raise
    return {"instance": str(destination), "backup": str(backup) if backup else None,
            "java": str(java), "launched": False, "coreSha256": receipt["coreSha256"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=ROOT / "dist/Skycraft-26.3-client-instance.zip")
    parser.add_argument("--prism-root", type=Path, default=Path(os.environ.get("APPDATA", "")) / "PrismLauncher")
    parser.add_argument("--java", type=Path)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--server", type=loopback_server, default=None, metavar="127.0.0.1:PORT")
    parser.add_argument("--tour", default="")
    parser.add_argument("--extra", default="")
    parser.add_argument("--save-source", type=Path)
    parser.add_argument("--width", type=int, choices=range(320, 7681), default=1280, metavar="320..7680")
    parser.add_argument("--height", type=int, choices=range(240, 4321), default=800, metavar="240..4320")
    parser.add_argument("--up", type=int, default=60)
    parser.add_argument("--pitch", type=int, default=40)
    parser.add_argument("--tour-wait", type=int, default=500)
    args = vars(parser.parse_args())
    archive, prism_root, java = (args.pop(key) for key in ("archive", "prism_root", "java"))
    print(json.dumps(prepare(archive, prism_root, java, **args), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        sys.exit("ERROR: " + str(error))
