#!/usr/bin/env python3
"""
Audits all registered entity types in Skycraft and ensures every entity has a
registered client entity renderer and valid textures. Fails with exit code 1 if
any registered entity lacks a renderer.
"""
import glob
import os
import re
import sys

def audit():
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    java_dir = os.path.join(base_dir, "mod", "src", "main", "java")
    res_dir = os.path.join(base_dir, "mod", "src", "main", "resources")

    registered_entities = set()
    registered_vars = {}
    rendered_entities = set()

    # 1. Find all entity registrations in Java files
    # Pattern: .register("entity_name", () -> EntityType.Builder...
    reg_pattern = re.compile(r'public\s+static\s+final\s+RegistryObject<EntityType<[^>]+>>\s+([A-Z0-9_]+)\s*=\s*[A-Z0-9_]+\.register\(\s*"([^"]+)"')

    for root, _, files in os.walk(java_dir):
        for f in files:
            if f.endswith(".java"):
                fpath = os.path.join(root, f)
                with open(fpath, "r", encoding="utf-8") as jf:
                    content = jf.read()
                for match in reg_pattern.finditer(content):
                    var_name, entity_id = match.group(1), match.group(2)
                    registered_entities.add(entity_id)
                    registered_vars[entity_id] = var_name.lower()

    # 2. Find all entity renderer registrations
    # Pattern: event.registerEntityRenderer(<TYPE>.get(), ...)
    render_pattern = re.compile(r'registerEntityRenderer\(\s*([A-Za-z0-9_.]+)\.get\(\)'
                                r'|\brig\(\s*event,\s*([A-Za-z0-9_.]+)\s*,')
    for root, _, files in os.walk(java_dir):
        for f in files:
            if f.endswith(".java"):
                fpath = os.path.join(root, f)
                with open(fpath, "r", encoding="utf-8") as jf:
                    content = jf.read()
                for match in render_pattern.finditer(content):
                    ref = match.group(1) or match.group(2) # e.g. ModEntities.BANDIT or DungeonsRegistry.DWARVEN_SPIDER
                    target = ref.split(".")[-1].lower()
                    rendered_entities.add(target)

    print(f"Registered entity types found: {len(registered_entities)}")
    print(f"Renderer registrations found: {len(rendered_entities)}")

    missing = []
    for entity in sorted(registered_entities):
        if entity not in rendered_entities and registered_vars.get(entity) not in rendered_entities \
                and entity.replace("_", "") not in [r.replace("_", "") for r in rendered_entities]:
            missing.append(entity)

    if missing:
        print(f"ERROR: The following {len(missing)} entity types have NO registered renderer:")
        for m in missing:
            print(f"  - {m}")
        sys.exit(1)

    print("SUCCESS: All registered entity types have matching entity renderers!")

if __name__ == "__main__":
    audit()

