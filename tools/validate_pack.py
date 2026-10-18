"""Validate the exact platform/dependency lock before producing a Skycraft package.

This check is local and does not launch Minecraft or certify runtime integration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TARGET = {"minecraft": "26.3", "neoforge": "26.3.0.90", "java": 25,
          "instanceName": "Skycraft-26.3-autotest"}
# Owner-approved fix for the native NeoForge render-overload removal, bounded to these exact artifacts.
IRIS_SODIUM_OVERRIDE = {
    "id": "iris-1.11.7-sodium-0.9.3-beta.1-neoforge26.3",
    "requiringProjectId": "YL57xq9U", "requiringVersionId": "Ag5S66CF",
    "dependencyProjectId": "AANobbMI", "declaredVersionId": "VHtnT9Q4", "resolvedVersionId": "SS023RVV",
    "requiringSha512": "302659f09e50d02a6cd96075be93aade2a2f9d709afe21d56465228fb7d7f4717566e3702a7872479df2fff9b7a5ff5b1b6047cc95e09ee6120fad5ea5857eec",
    "resolvedSha512": "92cdb344cb8049993685e3d81498411d4e6965a8f054c912a297bfe550937ed4792b7c1cb4efea5b5ac9c0e6c32aea777389207e481f008ce6f176b5ae6aab93",
    "nativeDependencyRange": "[0.6,)", "ownerApproved": True,
    "reason": "Official Sodium PR3948 fixes NeoForge26.3.0.65+ LevelExtractor compatibility; the unchanged Iris jar accepts Sodium[0.6,).",
    "source": "https://github.com/CaffeineMC/sodium/pull/3948"
}


def approved_dependency_override(lock, requiring, dependency, found):
    override = IRIS_SODIUM_OVERRIDE
    return (lock.get("dependencyOverrides") == [override] and found is not None
            and requiring["key"] == "iris" and requiring.get("projectId") == override["requiringProjectId"]
            and requiring.get("versionId") == override["requiringVersionId"]
            and requiring.get("hashFormat") == "sha512" and requiring.get("hash") == override["requiringSha512"]
            and dependency.get("project_id") == override["dependencyProjectId"]
            and dependency.get("version_id") == override["declaredVersionId"]
            and dependency.get("dependency_type") == "required"
            and found["key"] == "sodium" and found.get("versionId") == override["resolvedVersionId"]
            and found.get("hashFormat") == "sha512" and found.get("hash") == override["resolvedSha512"])


def read_lock(pack_dir=ROOT / "pack"):
    pack_dir = Path(pack_dir)
    lock = json.loads((pack_dir / "migration/platform-lock.json").read_text(encoding="utf8"))
    if lock.get("target") != TARGET:
        raise ValueError(f"pack target must be exactly {TARGET}")
    return lock


def validate_pack(pack_dir=ROOT / "pack"):
    pack_dir = Path(pack_dir)
    lock = read_lock(pack_dir)
    pack = tomllib.loads((pack_dir / "pack.toml").read_text(encoding="utf8"))
    if pack["versions"] != {"minecraft": TARGET["minecraft"], "neoforge": TARGET["neoforge"]}:
        raise ValueError("pack.toml disagrees with the exact platform lock")
    expected = {row["metadata"] for row in lock["active"]}
    actual = {str(p.relative_to(pack_dir)).replace("\\", "/") for p in (pack_dir / "mods").glob("*.pw.toml")}
    if actual != expected:
        raise ValueError(f"active metadata disagrees with lock: missing={expected - actual}, extra={actual - expected}")
    projects = {r["projectId"]: r for r in lock["active"] if r.get("projectId")}
    keys = {r["key"] for r in lock["active"]}
    if lock.get("dependencyOverrides", []) not in ([], [IRIS_SODIUM_OVERRIDE]):
        raise ValueError("Unknown or changed dependency override; only the exact owner-approved Iris/Sodium pair is permitted")
    if lock.get("dependencyOverrides"):
        iris_row = projects.get(IRIS_SODIUM_OVERRIDE["requiringProjectId"])
        sodium_row = projects.get(IRIS_SODIUM_OVERRIDE["dependencyProjectId"])
        if iris_row is None or not any(approved_dependency_override(lock, iris_row, dependency, sodium_row)
                                      for dependency in iris_row["requiredDependencies"]):
            raise ValueError("Dependency override does not match the exact approved Iris/Sodium artifacts and original declaration")
    for row in lock["active"]:
        meta = tomllib.loads((pack_dir / row["metadata"]).read_text(encoding="utf8"))
        if meta.get("skycraft") != {"minecraft": TARGET["minecraft"], "loader": "neoforge"}:
            raise ValueError(f"{row['key']}: missing exact game/loader declaration")
        download = meta["download"]
        if (meta["filename"], meta.get("side", "both"), download["hash-format"], download["hash"]) != (
                row["filename"], row["side"], row["hashFormat"], row["hash"]):
            raise ValueError(f"{row['key']}: artifact identity disagrees with lock")
        if row.get("versionId") and meta.get("update", {}).get("modrinth") != {
                "mod-id": row["projectId"], "version": row["versionId"]}:
            raise ValueError(f"{row['key']}: Modrinth identity disagrees with lock")
        for dep in row["requiredDependencies"]:
            if dep.get("key") and dep["key"] not in keys:
                raise ValueError(f"{row['key']}: missing required {dep['key']}")
            if dep.get("project_id"):
                found = projects.get(dep["project_id"])
                if found is None or (dep.get("version_id") and found["versionId"] != dep["version_id"]
                                     and not approved_dependency_override(lock, row, dep, found)):
                    raise ValueError(f"{row['key']}: missing required exact dependency {dep}")
    iris = dict(line.split("=", 1) for line in (pack_dir / "config/iris.properties").read_text().splitlines()
                if line and not line.startswith("#") and "=" in line)
    if iris.get("enableShaders") != "false":
        raise ValueError("customized shader integration must remain disabled until accepted")
    if keys & {"essential", "e4mc", "distanthorizons", "c2me-neoforge", "oculus", "embeddium"}:
        raise ValueError("unapproved hosting, legacy renderers or experimental integrations are active")
    index_path = pack_dir / pack["index"]["file"]
    if hashlib.sha256(index_path.read_bytes()).hexdigest() != pack["index"]["hash"]:
        raise ValueError("stale pack index hash; run tools/refresh_packwiz.py")
    index = tomllib.loads(index_path.read_text(encoding="utf8"))
    indexed = set()
    for entry in index.get("files", []):
        rel = Path(entry["file"])
        if rel.is_absolute() or ".." in rel.parts or rel.parts[0] == "disabled":
            raise ValueError(f"unsafe or preserved-only index path: {entry['file']}")
        if hashlib.new(entry.get("hash-format", index["hash-format"]), (pack_dir / rel).read_bytes()).hexdigest() != entry["hash"]:
            raise ValueError(f"stale indexed artifact: {entry['file']}")
        indexed.add(entry["file"])
    if not expected.issubset(indexed) or "migration/platform-lock.json" not in indexed:
        raise ValueError("active artifacts or platform lock are missing from index")
    return lock


def validate_core_jar(path):
    """Fail closed on old Forge cores instead of making a mixed-platform release."""
    with zipfile.ZipFile(path) as jar:
        try:
            meta = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode("utf8"))
            entry = jar.read("com/skycraft/Skycraft.class")
        except KeyError as error:
            raise ValueError("core is not a NeoForge Skycraft jar") from error
        if not any(m.get("modId") == "skycraft" for m in meta.get("mods", [])):
            raise ValueError("core does not declare the Skycraft mod")
        deps = meta.get("dependencies", {}).get("skycraft", [])
        if not any(d.get("modId") == "minecraft" and d.get("versionRange") == "[26.3]" for d in deps):
            raise ValueError("core does not require exactly Minecraft 26.3")
        if not any(d.get("modId") == "neoforge" for d in deps):
            raise ValueError("core does not require NeoForge")
        if entry[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(entry[6:8], "big") != 69:
            raise ValueError("Skycraft entry class must be compiled for Java 25 (class version 69)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, default=ROOT / "pack")
    parser.add_argument("--core-jar", type=Path)
    args = parser.parse_args()
    lock = validate_pack(args.pack)
    if args.core_jar:
        validate_core_jar(args.core_jar)
    print(f"Exact platform/dependency lock verified: {len(lock['active'])} active artifacts; runtime acceptance remains separate.")
