#!/usr/bin/env python3
"""Audit every registered Skycraft item for its place in the economy.

Checks, per item:
  * a lang name (item.skycraft.<id> or block.skycraft.<id>) and an item model whose textures exist;
  * a gold value and a weight from the data tables (skycraft_values / skycraft_weights, by id or tag), not the
    heuristic fallback;
  * the inventory category its kind calls for (weapons, apparel, ingredients, books, spell tomes...);
  * ingredients: four distinct alchemy effects (every creature/fauna/arcane ingredient; trophies in
    #skycraft:trophies are exempt);
  * gear (weapons, armour, shields, jewellery): enchantable at the Arcane Enchanter with at least one enchantment,
    and temperable (jewellery is not tempered; artifacts, bound weapons and staves are exempt from enchanting);
  * books and spell tomes: some merchant stocks them;
  * leveled lists (Skyrim's and every province's gear, spell tomes): nothing above the character's level drops.

Item ids come from the Java registries (string literals and the generated lists: tomes, skill books, gear, masks,
ingredients...), the lang files and the item models; the game-side facts (value source, categories, enchantments,
tempering, merchants) come from the dump the server writes when mod/run/skycraft-item-audit.request exists:

    echo > mod/run/skycraft-item-audit.request   then   ./gradlew runServer   (or tools/test-server.sh)
    py -3 tools/audit_items.py [--dump mod/run/skycraft-item-audit.json] [--out report.txt] [--verbose]

Exit code 1 when there are gaps.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "mod" / "src" / "main"
JAVA = MAIN / "java" / "com" / "skycraft"
ASSETS = MAIN / "resources" / "assets" / "skycraft"
DATA = MAIN / "resources" / "data"
LANG = MAIN / "lang" / "skycraft"

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_assets  # noqa: E402

GEAR_CLASSES = ("SkyArmorItem", "SkyShieldItem", "SkyMeleeItem", "SkyAxeItem", "SkyBowItem", "PolearmItem",
                "SkyCrossbowItem", "MaskItem", "ArtifactMeleeItem", "ArtifactAxeItem", "ArtifactBowItem",
                "SpellbreakerItem")
ARTIFACT_CLASSES = ("ArtifactMeleeItem", "ArtifactAxeItem", "ArtifactBowItem", "ArtifactStaffItem", "SpellbreakerItem")
NO_ENCHANT_CLASSES = ARTIFACT_CLASSES + ("BoundSwordItem", "BoundBattleaxeItem", "BoundBowItem", "BoundDaggerItem",
                                        "StaffItem", "EnchantedStaffItem")
WEAPON_CLASSES = ("SkyMeleeItem", "SkyAxeItem", "SkyBowItem", "PolearmItem", "SkyCrossbowItem", "ArtifactMeleeItem",
                  "ArtifactAxeItem", "ArtifactBowItem", "BoundSwordItem", "BoundBattleaxeItem", "BoundBowItem",
                  "BoundDaggerItem", "SkyArrowItem")
APPAREL_CLASSES = ("SkyArmorItem", "SkyShieldItem", "MaskItem", "SpellbreakerItem")


# --------------------------------------------------------------------------------------------- static discovery

def lang_keys():
    keys = {}
    for f in sorted(LANG.glob("*.json")):
        for k in json.loads(f.read_text(encoding="utf-8")):
            keys[k] = f.name
    return keys


def java_literal_ids():
    """ITEMS.register("id", ...) literals plus the generated id lists the registries loop over."""
    ids = set()
    lists = {}
    for f in JAVA.rglob("*.java"):
        text = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'ITEMS\.register\(\s*"([a-z0-9_]+)"\s*,', text):
            ids.add(m.group(1))
        for m in re.finditer(r'(?:ingredient|plantItem|gem|simple|jewelry|item)\(\s*"([a-z0-9_]+)"\s*[,)]', text):
            if "crafting" in str(f) or "fauna" in str(f) or "arcane" in str(f):
                ids.add(m.group(1))
        for m in re.finditer(r'List<String>\s+(\w+)\s*=\s*List\.of\(([^;]*)\);', text, re.S):
            lists[f.stem + "." + m.group(1)] = re.findall(r'"([a-z0-9_]+)"', m.group(2))
    ids.update(lists.get("CreatureItems.INGREDIENTS", []))
    ids.update(lists.get("ArcaneRegistry.MORE_INGREDIENTS", []))
    return ids, lists


def variant_models():
    """Models that are only overrides of another model (bow pulling stages, blocking shields, filled gems...)."""
    out = set()
    for f in (ASSETS / "models" / "item").glob("*.json"):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        for o in data.get("overrides", []):
            ref = o.get("model", "")
            if ref.startswith("skycraft:item/"):
                out.add(ref[len("skycraft:item/"):])
    return out


def resolve_tag(tag, seen=None):
    """Item ids of an item tag defined in this mod's data (nested #tags expanded, optional entries kept)."""
    seen = seen or set()
    if tag in seen:
        return set()
    seen.add(tag)
    ns, _, path = tag.partition(":")
    out = set()
    for f in DATA.glob(f"{ns}/tags/items/{path}.json"):
        for v in json.loads(f.read_text(encoding="utf-8")).get("values", []):
            v = v if isinstance(v, str) else v.get("id")
            if v.startswith("#"):
                out |= resolve_tag(v[1:], seen)
            else:
                out.add(v)
    return out


# --------------------------------------------------------------------------------------------- audit

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", default=str(ROOT / "mod" / "run" / "skycraft-item-audit.json"))
    ap.add_argument("--out")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    keys = lang_keys()
    literal, lists = java_literal_ids()
    variants = variant_models()
    dump = {}
    if os.path.exists(args.dump):
        dump = json.load(open(args.dump, encoding="utf-8"))
    items = dump.get("items", {})

    ids = set(items)
    if not ids:
        ids = {k.split(".", 2)[2] for k in keys if k.startswith("item.skycraft.") and k.count(".") == 2}
        ids |= literal
    gaps = []

    def gap(item, what):
        gaps.append((item, what))

    trophies = {i.split(":", 1)[1] for i in resolve_tag("skycraft:trophies")}
    expected_ingredients = set(lists.get("CreatureItems.INGREDIENTS", [])) | set(lists.get("ArcaneRegistry.MORE_INGREDIENTS", []))
    expected_ingredients -= trophies

    if items:
        for lit in sorted(literal - ids):
            if lit not in variants and not lit.endswith("_crop"):
                gap(lit, "registered in Java source but missing from the running game (or a typo)")
        for missing in dump.get("unregistered_ingredients", []):
            if missing.startswith("skycraft:"):
                gap(missing, "alchemy ingredient table names an item that is not registered")

    for i in sorted(ids):
        info = items.get(i, {})
        cls = info.get("class", "").rsplit(".", 1)[-1].split("$")[-1]
        egg = i.endswith("_spawn_egg")
        # names and art
        if f"item.skycraft.{i}" not in keys and f"block.skycraft.{i}" not in keys:
            gap(i, "no lang name")
        errs = []
        check_assets.check_model(ASSETS / "models" / "item" / f"{i}.json", set(), errs, f"item {i}")
        for e in errs:
            gap(i, e.split(": ", 1)[1])
        if not info:
            continue
        if egg:
            continue  # creative-only spawn eggs are not traded or carried
        if info["value_source"] == "heuristic":
            gap(i, f"value only from the heuristic ({info['value']})")
        if info["weight_source"] == "heuristic":
            gap(i, f"weight only from the heuristic ({info['weight']})")
        inv = info["inv_category"]
        tags = set(info.get("tags", []))
        jewelry = "skycraft:jewelry" in tags
        want = None
        if "effects" in info or i in expected_ingredients:
            want = "INGREDIENTS"
        elif cls == "SpellTomeItem":
            want = "SPELL_TOMES"
        elif cls in ("SkillBookItem", "LoreBookItem"):
            want = "BOOKS"
        elif cls in APPAREL_CLASSES or jewelry:
            want = "APPAREL"
        elif cls in WEAPON_CLASSES or cls in ("StaffItem", "ArtifactStaffItem", "EnchantedStaffItem"):
            want = "WEAPONS"
        if want and inv != want:
            gap(i, f"inventory category {inv}, expected {want}")
        # alchemy
        if i in expected_ingredients and "effects" not in info:
            gap(i, "ingredient without alchemy effects")
        if "effects" in info and (len(info["effects"]) != 4 or len(set(info["effects"])) != 4):
            gap(i, f"ingredient effects not four distinct: {info['effects']}")
        # gear
        gear = cls in GEAR_CLASSES or jewelry
        if gear and cls not in NO_ENCHANT_CLASSES and info["enchantments"] <= 0:
            gap(i, "gear that the Arcane Enchanter cannot enchant")
        if (cls in GEAR_CLASSES) and info["temper"] == "NONE":
            gap(i, "gear that cannot be tempered")
        # books
        if cls in ("SpellTomeItem", "SkillBookItem", "LoreBookItem") and not info.get("merchants"):
            gap(i, "book or tome that no merchant sells")

    # leveled lists: nothing above the character's level may drop (gear material level, spell tome level)
    for key, by_level in dump.get("leveled", {}).items():
        for level, res in by_level.items():
            if res["max_level"] > int(level):
                gap(f"leveled/{key}", f"at level {level} rolled {res['item']} (leveled-list level {res['max_level']})")

    if not items:
        gap("(all)", f"no runtime dump at {args.dump}: value/weight/category/enchanting/merchant checks skipped")

    lines = []
    by_item = {}
    for item, what in gaps:
        by_item.setdefault(item, []).append(what)
    for item in sorted(by_item):
        for what in by_item[item]:
            lines.append(f"GAP {item}: {what}")
    summary = f"audited {len(ids)} items ({len(items)} from the runtime dump), {len(gaps)} gaps"
    if args.verbose and items:
        kinds = {}
        for i, info in items.items():
            kinds[info["inv_category"]] = kinds.get(info["inv_category"], 0) + 1
        lines.append("categories: " + ", ".join(f"{k} {v}" for k, v in sorted(kinds.items())))
        sold = sum(1 for info in items.values() if info.get("merchants"))
        lines.append(f"items some merchant stocks: {sold}")
        ing = sum(1 for info in items.values() if "effects" in info)
        lines.append(f"Skycraft alchemy ingredients: {ing}")
        for key, by_level in dump.get("leveled", {}).items():
            lines.append(f"leveled {key}: " + ", ".join(f"L{lv}->{r['max_level']}" for lv, r in by_level.items()))
        for town, by_type in dump.get("shops", {}).items():
            lines.append(f"shops in {town}:")
            for t, stock in by_type.items():
                lines.append(f"    {t}: " + ", ".join(stock))
    lines.append(summary)
    text = "\n".join(lines)
    print(text)
    if args.out:
        Path(args.out).write_text(text + "\n", encoding="utf-8")
    return 1 if gaps else 0


if __name__ == "__main__":
    sys.exit(main())
