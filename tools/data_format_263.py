"""Native 26.3 data-pack boundary for authored recipes, loot and registry tags.

The authoring generators may retain their concise old descriptions. Conversion is
idempotent, preserves registry IDs, order, weights, counts and probabilities, and
changes only native schema/directory names verified against the shipped codecs.
"""
from __future__ import annotations

import copy
import importlib.util
import json
import re
from pathlib import Path


PATHS = {"recipes": "recipe", "loot_tables": "loot_table", "advancements": "advancement",
         "functions": "function", "predicates": "predicate", "item_modifiers": "item_modifier",
         "structures": "structure"}
TAGS = {"blocks": "block", "items": "item", "entity_types": "entity_type",
        "fluids": "fluid", "game_events": "game_event", "functions": "function"}
COMMON_TAGS = json.loads((Path(__file__).parent / "native_common_tags_263.json").read_text(encoding="utf-8"))
VANILLA_IDS = {"minecraft:chain": "minecraft:iron_chain", "minecraft:grass": "minecraft:short_grass",
               "minecraft:scute": "minecraft:turtle_scute"}
# NeoForge supplies its own enchanting-table recipe after NirnCraft's normal
# mod-data pack. Preserve the explicit authored removal above that loader pack
# with the native resource-filter mechanism rather than a lower override.
DISABLED_RECIPES = {"minecraft:enchanting_table"}
# Native advancement criteria/rewards now hold recipe registry references. Remove
# the unavailable recipe's own unlock too, so its holder is never left unbound.
DISABLED_RECIPE_UNLOCKS = {"minecraft:enchanting_table": "recipes/decorations/enchanting_table"}


def identifiers(value):
    if isinstance(value, str):
        # Custom dungeon palettes also encode state properties and weights in
        # one string. Keep those authored suffixes intact when the base ID moves.
        match = re.match(r"^(minecraft:[a-z0-9_/.-]+)(?=$|[\[@*])", value)
        if match and match[1] in VANILLA_IDS:
            return VANILLA_IDS[match[1]] + value[match.end():]
        return value
    if isinstance(value, list):
        return [identifiers(item) for item in value]
    if isinstance(value, dict):
        return {key: identifiers(item) for key, item in value.items()}
    return value


def unique(values):
    """Tag values may be optional-entry objects, not just hashable ID strings."""
    result, seen = [], set()
    for value in values:
        identity = json.dumps(value, sort_keys=True)
        if identity not in seen:
            result.append(value)
            seen.add(identity)
    return result


def common_tag(path, value):
    normalized = Path(path).as_posix()
    marker = "/data/forge/tags/"
    if marker not in normalized:
        return value
    rule = COMMON_TAGS.get(normalized.split(marker, 1)[1])
    if not rule:
        return value
    result = copy.deepcopy(value)
    result["values"] = unique(result.get("values", []) + rule["native_defaults"])
    return result


def sync_common_tag_bridges(root):
    """Native tags include authored legacy members; old queries retain native defaults.

    Only native tags point back to legacy IDs. The legacy side includes the native
    default MEMBERS, never a reference to the entire native tag, avoiding a cycle.
    This preserves common-tag membership when any generator updates its legacy tag.
    """
    changed = 0
    root = Path(root)
    for relative, rule in COMMON_TAGS.items():
        legacy = root / "forge/tags" / relative
        if not legacy.exists():
            # The shipped Forge crops tag came from its loader, rather than NirnCraft.
            if relative != "item/crops.json":
                continue
            legacy.parent.mkdir(parents=True, exist_ok=True)
            legacy.write_text(json.dumps({"replace": False, "values": rule["native_defaults"]}, indent=2) + "\n", encoding="utf-8")
            changed += 1
        namespace, name = rule["native_id"].split(":", 1)
        target = root / namespace / "tags" / rule["registry"] / (name + ".json")
        original = json.loads(target.read_text(encoding="utf-8")) if target.exists() else {"replace": False, "values": []}
        converted = copy.deepcopy(original)
        converted["values"] = unique(converted.get("values", []) + ["#" + rule["legacy_id"]])
        if converted != original:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(converted, indent=2) + "\n", encoding="utf-8")
            changed += 1
    return changed


def native_path(path):
    path = Path(path)
    parts = list(path.parts)
    if "data" not in parts:
        return path
    start = len(parts) - 1 - parts[::-1].index("data")
    if start + 2 >= len(parts):
        return path
    kind = start + 2
    parts[kind] = PATHS.get(parts[kind], parts[kind])
    if parts[kind] == "recipe":
        identifier = parts[start + 1] + ":" + "/".join(parts[kind + 1:]).removesuffix(".json")
        if identifier in DISABLED_RECIPES:
            parts[kind] = "nirncraft_legacy"
            parts.insert(kind + 1, "disabled_recipes")
    if parts[kind] == "tags" and kind + 1 < len(parts):
        parts[kind + 1] = TAGS.get(parts[kind + 1], parts[kind + 1])
    if parts[kind] == "forge" and kind + 1 < len(parts) and parts[kind + 1] in {"biome_modifier", "structure_modifier"}:
        parts[kind] = "neoforge"
    # Native 26.3 auto-discovers modifiers and replaces the old ordered global
    # list with explicit priorities. Keep its authored order as migration data,
    # outside the active loot_modifiers directory (which requires a codec type).
    if parts[start + 1] in {"forge", "neoforge"} and parts[kind] == "loot_modifiers" and path.name == "global_loot_modifiers.json":
        parts[start + 1] = "neoforge"
        parts[kind] = "nirncraft_legacy"
    return Path(*parts)


def sync_disabled_recipe_filters(root):
    root = Path(root)
    path = root.parent / "pack.mcmeta"
    if not path.exists():
        return 0
    original = json.loads(path.read_text(encoding="utf-8"))
    converted = copy.deepcopy(original)
    removal_filters = []
    for identifier in sorted(DISABLED_RECIPES):
        namespace, relative = identifier.split(":", 1)
        if not (root / namespace / "nirncraft_legacy/disabled_recipes" / (relative + ".json")).exists():
            continue
        # Gradle's existing template expansion also consumes backslash escapes.
        # Identifier paths only use /._-a-z0-9, so [.] quotes the sole regexp
        # metacharacter while remaining valid JSON before and after expansion.
        entry = {"namespace": namespace, "path": "recipe/" + relative.replace(".", "[.]") + "[.]json"}
        removal_filters.append(entry)
        entries = converted.setdefault("filter", {}).setdefault("block", [])
        previous = {"namespace": namespace, "path": "recipe/" + re.escape(relative) + r"\.json"}
        entries[:] = [entry if existing == previous else existing for existing in entries]
        if entry not in entries:
            entries.append(entry)
        unlock = DISABLED_RECIPE_UNLOCKS.get(identifier)
        if unlock is not None:
            unlock_filter = {"namespace": namespace, "path": "advancement/" + unlock.replace(".", "[.]") + "[.]json"}
            removal_filters.append(unlock_filter)
            if unlock_filter not in entries:
                entries.append(unlock_filter)
    if converted == original:
        changed = 0
    else:
        path.write_text(json.dumps(converted, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        changed = 1
    if converted.get("filter", {}).get("block"):
        # NeoForge's own mod-data pack overrides enchanting_table after the main
        # NirnCraft pack. A required TOP pack applies the authored removal last.
        rules = root.parent / "resourcepacks/recipe_rules/pack.mcmeta"
        rule_metadata = {"pack": {"description": "NirnCraft recipe rules", "min_format": [121, 0], "max_format": [121, 0]},
                         "filter": {"block": removal_filters}}
        current = json.loads(rules.read_text(encoding="utf-8")) if rules.exists() else None
        if current != rule_metadata:
            rules.parent.mkdir(parents=True, exist_ok=True)
            rules.write_text(json.dumps(rule_metadata, indent=2) + "\n", encoding="utf-8")
            changed += 1
    return changed


def ingredient(value):
    if isinstance(value, dict) and set(value) == {"item"}:
        return value["item"]
    if isinstance(value, dict) and set(value) == {"tag"}:
        return "#" + value["tag"]
    if isinstance(value, list):
        return [ingredient(item) for item in value]
    return value


def recipe(value):
    result = copy.deepcopy(value)
    if "conditions" in result:
        # NeoForge's conditional recipe decoder reads this namespaced key.
        # Leaving the old key silently re-enables intentionally blocked recipes.
        conditions = result.pop("conditions")
        def native_condition(condition):
            if isinstance(condition, list):
                return [native_condition(item) for item in condition]
            if isinstance(condition, dict):
                converted = {key: native_condition(item) for key, item in condition.items()}
                kind = converted.get("type", "")
                if kind.startswith("forge:"):
                    converted["type"] = "neoforge:" + kind.split(":", 1)[1]
                return converted
            return condition
        result["neoforge:conditions"] = native_condition(conditions)
    if not result.get("type", "").startswith("minecraft:"):
        return result
    for field in ("ingredient", "base", "addition", "template"):
        if field in result:
            result[field] = ingredient(result[field])
    if "ingredients" in result:
        result["ingredients"] = [ingredient(item) for item in result["ingredients"]]
    if "key" in result:
        result["key"] = {key: ingredient(item) for key, item in result["key"].items()}
    output = result.get("result")
    if isinstance(output, str):
        result["result"] = {"id": output}
    elif isinstance(output, dict) and "item" in output:
        output["id"] = output.pop("item")
    return result


def item_predicate(value):
    result = copy.deepcopy(value)
    if "tag" in result:
        result["items"] = "#" + result.pop("tag")
    for field, native in (("enchantments", "minecraft:enchantments"), ("stored_enchantments", "minecraft:stored_enchantments")):
        if field in result:
            entries = result.pop(field)
            for entry in entries:
                if "enchantment" in entry:
                    entry["enchantments"] = entry.pop("enchantment")
            result.setdefault("predicates", {})[native] = entries
    if "nbt" in result:
        result.setdefault("predicates", {})["minecraft:custom_data"] = result.pop("nbt")
    return result


def entity_predicate(value):
    # 26.3's entity predicate is a map of namespaced sub-predicate codecs.
    result = {}
    for field, argument in value.items():
        native = "minecraft:entity_type" if field == "type" else field if ":" in field else "minecraft:" + field
        if field in {"vehicle", "passenger", "targeted_entity"}:
            argument = entity_predicate(argument)
        elif field == "equipment":
            argument = {slot: item_predicate(item) for slot, item in argument.items()}
        result[native] = argument
    return result


def loot(value):
    if isinstance(value, list):
        return [loot(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {key: loot(item) for key, item in value.items()}
    function = result.pop("function", None)
    condition = result.get("condition")
    if function:
        result["type"] = function
    elif isinstance(condition, str):
        result["type"] = result.pop("condition")
    kind = result.get("type")
    if kind == "minecraft:block_state_property":
        result["type"] = "minecraft:match_block"
        if "block" in result:
            result["blocks"] = result.pop("block")
        if "properties" in result:
            result["state"] = result.pop("properties")
    if "conditions" in result:
        conditions = result.pop("conditions")
        if conditions:
            result["condition"] = conditions[0] if len(conditions) == 1 else {"type": "minecraft:all_of", "terms": conditions}
    if "functions" in result:
        functions = result.pop("functions")
        if functions:
            result["modifier"] = functions[0] if len(functions) == 1 else functions
    if kind == "minecraft:loot_table" and "name" in result:
        result["value"] = result.pop("name")
    if kind == "minecraft:tag" and "name" in result:
        result["items"] = "#" + result.pop("name")
    if kind == "minecraft:set_nbt":
        result["type"] = "minecraft:set_custom_data"
    if kind == "minecraft:looting_enchant":
        result["type"] = "minecraft:enchanted_count_increase"
        result["enchantment"] = "minecraft:looting"
    if kind == "minecraft:random_chance_with_looting":
        result["type"] = "minecraft:random_chance_with_enchanted_bonus"
        chance, multiplier = result.pop("chance"), result.pop("looting_multiplier")
        result.update({"unenchanted_chance": chance, "enchantment": "minecraft:looting",
                       "enchanted_chance": {"type": "minecraft:linear", "base": chance + multiplier,
                                            "per_level_above_first": multiplier}})
    if kind == "minecraft:match_tool" and "predicate" in result:
        result["predicate"] = item_predicate(result["predicate"])
    if kind == "minecraft:entity_properties" and "predicate" in result:
        result["predicate"] = entity_predicate(result["predicate"])
    if kind == "minecraft:enchant_randomly" and "options" not in result:
        result["options"] = result.pop("enchantments", "#minecraft:on_random_loot")
    return result


def prepare(path, value):
    """Return a native destination and converted value; do not write or mutate input."""
    target = native_path(path)
    normalized = target.as_posix()
    value = identifiers(value)
    if "/data/" not in normalized:
        return str(target), value
    if "/tags/" in normalized:
        value = common_tag(target, value)
    if "/recipe/" in normalized:
        value = recipe(value)
    elif "/loot_table/" in normalized or "/item_modifier/" in normalized or "/predicate/" in normalized:
        value = loot(value)
    elif "/loot_modifiers/" in normalized and target.name != "global_loot_modifiers.json":
        helper = Path(__file__).parent / "migration" / "loot_modifier_data_263.py"
        spec = importlib.util.spec_from_file_location("nirncraft_loot_modifier_data_263", helper)
        native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(native)
        value = native.convert(value)
        resource_root, relative = normalized.split("/data/", 1)
        namespace, name = relative.split("/loot_modifiers/", 1)
        manifest = Path(resource_root) / "data/neoforge/nirncraft_legacy/global_loot_modifiers.json"
        if manifest.exists():
            order = json.loads(manifest.read_text(encoding="utf-8")).get("entries", [])
            identifier = namespace + ":" + name.removesuffix(".json")
            if identifier in order:
                value["priority"] = len(order) - order.index(identifier)
    elif "/neoforge/biome_modifier/" in normalized or "/neoforge/structure_modifier/" in normalized:
        value = copy.deepcopy(value)
        if value.get("type", "").startswith("forge:"):
            value["type"] = "neoforge:" + value["type"].split(":", 1)[1]
    return str(target), value


def upgrade_resources(root):
    changed = moved = 0
    for source in sorted(Path(root).glob("*/**/*.json")):
        original = json.loads(source.read_text(encoding="utf-8"))
        target, converted = prepare(source.resolve(), original)
        target = Path(target)
        if target == source.resolve() and converted == original:
            continue
        if target != source.resolve() and target.exists():
            existing = json.loads(target.read_text(encoding="utf-8"))
            if "/tags/" not in target.as_posix() or set(existing) - {"replace", "values"}:
                if existing != converted:
                    raise ValueError(f"Conflicting native resource {source} -> {target}")
            else:
                # Preserve both sides of a partially migrated tag directory.
                converted["values"] = unique(existing.get("values", []) + converted.get("values", []))
                converted["replace"] = existing.get("replace", False) or converted.get("replace", False)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(converted, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        changed += 1
        if target != source.resolve():
            source.unlink()
            moved += 1
    changed += sync_common_tag_bridges(root)
    changed += sync_disabled_recipe_filters(root)
    # Caverns/blueprints are classpath-authored JSON rather than datapack
    # registries. They use the same native block IDs and must survive regeneration.
    for path in sorted((Path(root).parent / "nirncraft").rglob("*.json")):
        original = json.loads(path.read_text(encoding="utf-8"))
        converted = identifiers(original)
        if converted != original:
            path.write_text(json.dumps(converted, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
            changed += 1
    meat_spec = importlib.util.spec_from_file_location("nirncraft_meat_tags_263", Path(__file__).parent / "migration/meat_tags_263.py")
    meat = importlib.util.module_from_spec(meat_spec)
    meat_spec.loader.exec_module(meat)
    changed += meat.upgrade(root)
    # Classification generation belongs to a complete NirnCraft source tree;
    # arbitrary conversion fixtures must not acquire unrelated content.
    if (Path(root).parents[1] / "java/com/nirncraft/realms/RealmCreatures.java").exists():
        entity_spec = importlib.util.spec_from_file_location("nirncraft_entity_tags_263", Path(__file__).parent / "migration/entity_tags_263.py")
        entity_tags = importlib.util.module_from_spec(entity_spec)
        entity_spec.loader.exec_module(entity_tags)
        changed += entity_tags.upgrade(root)
    return {"converted": changed, "moved": moved}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", default="mod/src/main/resources/data")
    args = parser.parse_args()
    print(json.dumps(upgrade_resources(args.resources), sort_keys=True))
