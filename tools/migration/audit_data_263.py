"""Audit authored ID retention and references against the exact native 26.3 data catalog.

This is a fast static gate, not a substitute for FML registry decoding and real
world generation. No server or Minecraft bootstrap is faked by this audit.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import importlib.util
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("skycraft_data_format_263", ROOT / "tools/data_format_263.py")
data_format = importlib.util.module_from_spec(spec)
spec.loader.exec_module(data_format)
PREFIX = "mod/src/main/resources/data/"


def native_name(name):
    path = data_format.native_path(Path("/repo/data") / name)
    result = path.as_posix().split("/data/", 1)[1]
    return result.replace("/worldgen/configured_feature/", "/worldgen/feature/").replace(
        "/worldgen/configured_carver/", "/worldgen/carver/")


def catalog_name(name):
    parts = name[:-5].split("/")
    namespace, fields = parts[0], parts[1:]
    if not fields:
        return None
    depth = 2 if fields[0] in {"worldgen", "tags", "neoforge"} else 1
    if fields[0] == "tags" and len(fields) > 2 and fields[1] == "worldgen":
        depth = 3
    return "/".join(fields[:depth]), namespace + ":" + "/".join(fields[depth:])


def audit_tag_graph(resources, native_jar, neo_sources):
    tags = {}

    def add(name, value):
        category, identifier = catalog_name(name)
        key = (category, identifier)
        if value.get("replace"):
            tags[key] = []
        tags.setdefault(key, []).extend(value.get("values", []))

    for artifact in (native_jar, neo_sources):
        with zipfile.ZipFile(artifact) as archive:
            for name in archive.namelist():
                if name.startswith("data/") and "/tags/" in name and name.endswith(".json"):
                    add(name[5:], json.loads(archive.read(name)))
    for path in sorted(resources.glob("*/tags/**/*.json")):
        add(path.relative_to(resources).as_posix(), json.loads(path.read_text(encoding="utf-8")))
    active, completed, cycles, missing = set(), set(), [], []

    def visit(key, trail):
        if key in active:
            cycles.append(trail + [key])
            return
        if key in completed:
            return
        active.add(key)
        for value in tags[key]:
            optional = isinstance(value, dict) and not value.get("required", True)
            identifier = value.get("id") if isinstance(value, dict) else value
            if isinstance(identifier, str) and identifier.startswith("#"):
                target = (key[0], identifier[1:])
                if target not in tags:
                    if not optional:
                        missing.append({"source": key, "target": target})
                else:
                    visit(target, trail + [key])
        active.remove(key)
        completed.add(key)

    for key in tags:
        visit(key, [])
    return {"nodes": len(tags), "cycles": cycles, "missing_required_tags": missing,
            "passed": not (cycles or missing)}


def audit(resources, native_jar, baseline=None):
    files = {p.relative_to(resources).as_posix(): json.loads(p.read_text(encoding="utf-8"))
             for p in sorted(resources.glob("*/**/*.json"))}
    catalog = defaultdict(set)
    with zipfile.ZipFile(native_jar) as archive:
        native_items = {name[len("assets/minecraft/items/"):-5] for name in archive.namelist()
                        if name.startswith("assets/minecraft/items/") and name.endswith(".json")}
        native_blocks = {name[len("assets/minecraft/blockstates/"):-5] for name in archive.namelist()
                         if name.startswith("assets/minecraft/blockstates/") and name.endswith(".json")}
        for name in archive.namelist():
            if name.startswith("data/") and name.endswith(".json"):
                entry = catalog_name(name[5:])
                if entry:
                    catalog[entry[0]].add(entry[1])
    for name in files:
        entry = catalog_name(name)
        if entry:
            catalog[entry[0]].add(entry[1])
    missing_refs = []
    missing_vanilla_ids = []

    def vanilla_id(value, registry, source):
        if isinstance(value, str) and value.startswith("minecraft:"):
            native = native_items if registry == "item" else native_blocks
            if value.split(":", 1)[1] not in native:
                missing_vanilla_ids.append({"source": source, "registry": registry, "id": value})
        elif isinstance(value, list):
            for item in value:
                vanilla_id(item, registry, source)
        elif isinstance(value, dict) and "id" in value:
            vanilla_id(value["id"], registry, source)

    def loot_items(value, source):
        if isinstance(value, list):
            for item in value:
                loot_items(item, source)
        elif isinstance(value, dict):
            if value.get("type") == "minecraft:item":
                vanilla_id(value.get("name"), "item", source)
            for child in value.values():
                loot_items(child, source)

    def reference(registry, value, source):
        if isinstance(value, str):
            target = "tags/" + registry if value.startswith("#") else registry
            identifier = value.removeprefix("#")
            if ":" not in identifier:
                identifier = "minecraft:" + identifier
            if identifier not in catalog[target]:
                missing_refs.append({"source": source, "registry": target, "id": identifier})
        elif isinstance(value, list):
            for item in value:
                reference(registry, item, source)

    def density(value, source):
        if isinstance(value, str):
            reference("worldgen/density_function", value, source)
        elif isinstance(value, dict):
            for key, child in value.items():
                if key == "noise":
                    reference("worldgen/noise", child, source)
                elif key in {"input", "left", "right", "shift_x", "shift_y", "shift_z", "when_in_range", "when_out_of_range", "argument"}:
                    density(child, source)

    for name, value in files.items():
        if "/loot_table/" in name:
            loot_items(value, name)
        if "/recipe/" in name:
            for field in ("result", "ingredient", "ingredients", "base", "addition", "template"):
                vanilla_id(value.get(field), "item", name)
            for ingredient in value.get("key", {}).values():
                vanilla_id(ingredient, "item", name)
        for registry in ("item", "block"):
            if "/tags/" + registry + "/" in name:
                vanilla_id(value.get("values", []), registry, name)
        if "/worldgen/biome/" in name:
            reference("worldgen/carver", value.get("carvers", []), name)
            for group in value.get("features", []):
                reference("worldgen/placed_feature", group, name)
        elif "/worldgen/placed_feature/" in name:
            reference("worldgen/feature", value.get("feature"), name)
        elif "/worldgen/density_function/" in name:
            density(value, name)
        elif "/worldgen/noise_settings/" in name:
            for child in value.get("noise_router", {}).values():
                density(child, name)
            for child in value.get("aquifers", {}).values():
                density(child, name)
        elif "/worldgen/structure_set/" in name:
            for entry in value.get("structures", []):
                reference("worldgen/structure", entry.get("structure"), name)
        elif "/neoforge/biome_modifier/" in name:
            reference("worldgen/biome", value.get("biomes"), name)
            reference("worldgen/placed_feature", value.get("features"), name)
        elif "/dimension/" in name:
            reference("dimension_type", value.get("type"), name)
            generator = value.get("generator", {})
            if generator.get("type") == "minecraft:noise":
                reference("worldgen/noise_settings", generator.get("settings"), name)
        if "/dimension_type/" in name:
            reference("world_clock", value.get("default_clock"), name)
            reference("timeline", value.get("timelines"), name)

    baseline_files = []
    missing_content = []
    if baseline:
        with zipfile.ZipFile(baseline) as archive:
            baseline_files = [name[len(PREFIX):] for name in archive.namelist()
                              if name.startswith(PREFIX) and name.endswith(".json")]
        missing_content = [{"before": name, "expected": native_name(name)}
                           for name in baseline_files if native_name(name) not in files]
    non_native = []
    for name, value in files.items():
        if native_name(name) != name:
            non_native.append(name)
    result = {"gate": "static data inventory and native catalog references; FML runtime decoding still required",
              "native_jar": str(native_jar), "resources": len(files),
              "resource_counts": dict(sorted(Counter(catalog_name(name)[0] for name in files).items())),
              "baseline_resources": len(baseline_files), "missing_authored_resources": missing_content,
              "legacy_resource_paths": non_native,
              "native_vanilla_item_definitions": len(native_items),
              "native_vanilla_block_definitions": len(native_blocks),
              "missing_vanilla_item_block_ids": missing_vanilla_ids,
              "missing_registry_references": sorted(missing_refs, key=lambda item: (item["source"], item["registry"], item["id"]))}
    result["passed"] = not (missing_content or non_native or missing_refs or missing_vanilla_ids)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", type=Path, default=ROOT / "mod/src/main/resources/data")
    parser.add_argument("--native-jar", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--neo-sources", type=Path, help="Exact NeoForge source jar, including its native common-tag data")
    parser.add_argument("--out", type=Path, default=ROOT / "dist/code-review/neoforge-migration/native-data-audit.json")
    args = parser.parse_args()
    result = audit(args.resources, args.native_jar, args.baseline)
    if args.neo_sources:
        result["tag_graph"] = audit_tag_graph(args.resources, args.native_jar, args.neo_sources)
        result["passed"] = result["passed"] and result["tag_graph"]["passed"]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key in {
        "resources", "baseline_resources", "passed"}}))
    print("Missing authored resources:", len(result["missing_authored_resources"]),
          "; missing registry references:", len(result["missing_registry_references"]))
    raise SystemExit(0 if result["passed"] else 1)
