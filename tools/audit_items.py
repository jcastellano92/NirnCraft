#!/usr/bin/env python3
"""Audit every registered NirnCraft item for its place in the economy.

Checks, per item:
  * a lang name (item.nirncraft.<id> or block.nirncraft.<id>) and an item model whose textures exist;
  * a gold value and a weight from the data tables (nirncraft_values / nirncraft_weights, by id or tag), not the
    heuristic fallback;
  * the inventory category its kind calls for (weapons, apparel, ingredients, books, spell tomes...);
  * ingredients: four distinct alchemy effects (every creature/fauna/arcane ingredient; trophies in
    #nirncraft:trophies are exempt);
  * gear (weapons, armour, shields, jewellery): enchantable at the Arcane Enchanter with at least one enchantment,
    and temperable (jewellery is not tempered; artifacts, bound weapons and staves are exempt from enchanting);
  * books and spell tomes: some merchant stocks them;
  * leveled lists (Skyrim's and every province's gear, spell tomes): nothing above the character's level drops.

Item ids come from the Java registries (string literals and the generated lists: tomes, skill books, gear, masks,
ingredients...), the lang files and the item models; the game-side facts (value source, categories, enchantments,
tempering, merchants) come from the dump the server writes when mod/run/nirncraft-item-audit.request exists:

    echo > mod/run/nirncraft-item-audit.request   then   ./gradlew runServer   (or tools/test-server.sh)
    py -3 tools/audit_items.py [--dump mod/run/nirncraft-item-audit.json] [--out report.txt] [--verbose]

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
JAVA = MAIN / "java" / "com" / "nirncraft"
ASSETS = MAIN / "resources" / "assets" / "nirncraft"
DATA = MAIN / "resources" / "data"
LANG = MAIN / "lang" / "nirncraft"

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
    vals = {}
    for f in sorted(LANG.glob("*.json")):
        data = json.loads(f.read_text(encoding="utf-8"))
        for k, v in data.items():
            keys[k] = f.name
            vals[k] = v
    return keys, vals


def audit_affixes(keys, vals, gap):
    """Audit R14 item affixes: qualities, makers, enchant tiers, storied items, worldsheet cites, and novelty."""
    affix_dir = MAIN / "resources" / "nirncraft" / "loot" / "affixes"
    for req in ("qualities.json", "makers.json", "enchant_tiers.json", "storied.json"):
        p = affix_dir / req
        if not p.exists():
            gap(f"affixes/{req}", "missing affix data file")
            return 0

    qualities_doc = json.loads((affix_dir / "qualities.json").read_text(encoding="utf-8"))
    makers_doc = json.loads((affix_dir / "makers.json").read_text(encoding="utf-8"))
    enchant_doc = json.loads((affix_dir / "enchant_tiers.json").read_text(encoding="utf-8"))
    storied_doc = json.loads((affix_dir / "storied.json").read_text(encoding="utf-8"))

    # 1. Qualities & cultural synonyms
    q_ids = set()
    for q in qualities_doc.get("qualities", []):
        qid = q.get("id", "")
        q_ids.add(qid)
        lkey = q.get("lang_key", "")
        if lkey not in keys:
            gap(f"affix/quality/{qid}", f"missing lang key {lkey}")
        for mult_field in ("damage_mult", "armor_mult", "durability_mult", "value_mult"):
            val = float(q.get(mult_field, 0))
            if not (0.5 <= val <= 2.0):
                gap(f"affix/quality/{qid}", f"{mult_field} out of bounds: {val}")
        weights = q.get("weights", {})
        for src in ("corpse", "container", "boss", "merchant"):
            if src not in weights:
                gap(f"affix/quality/{qid}", f"missing source weight '{src}'")
        for cult, syn in q.get("synonyms", {}).items():
            skey = syn.get("lang_key", "")
            if skey not in keys:
                gap(f"affix/quality/{qid}/{cult}", f"missing synonym lang key {skey}")
    for req_q in ("rusted", "worn", "notched", "standard", "well_made", "masterwork"):
        if req_q not in q_ids:
            gap("affix/qualities", f"missing required quality tier '{req_q}'")

    # 2. Worldsheet citation resolution for makers and storied entries
    sys.path.insert(0, str(ROOT / "tools" / "qa"))
    import worldsheet_lint  # noqa: E402
    import novelty_lint  # noqa: E402

    ws_errors = []
    sheets, all_fields = worldsheet_lint.validate_sheets(None, ws_errors)
    forbidden_pairs = []
    for s in sheets.values():
        forbidden_pairs.extend(s.get("forbidden", []))

    # 3. Makers
    makers = makers_doc.get("makers", [])
    if len(makers) < 12:
        gap("affix/makers", f"too few makers ({len(makers)}, expected >= 12)")
    for m in makers:
        mid = m.get("id", "")
        for lk in (m.get("lang_key", ""), m.get("desc_key", "")):
            if lk not in keys:
                gap(f"affix/maker/{mid}", f"missing lang key {lk}")
        why = m.get("why", [])
        cite_errs = []
        worldsheet_lint.check_why_list(f"affix/maker/{mid}", why, all_fields, forbidden_pairs, cite_errs)
        for ce in cite_errs:
            gap(f"affix/maker/{mid}", ce)
        has_l5 = any(".L5." in worldsheet_lint.canon_field_id(w) for w in why)
        if not has_l5:
            gap(f"affix/maker/{mid}", f"maker why {why} must cite at least one L5 craft field")

    # 4. Enchantment tier ladders
    fams = enchant_doc.get("families", [])
    if len(fams) < 8:
        gap("affix/enchant_tiers", f"too few enchantment families ({len(fams)})")
    for fam in fams:
        fid = fam.get("id", "")
        tiers = fam.get("tiers", [])
        lkeys = fam.get("lang_keys", [])
        if len(tiers) != 6 or len(lkeys) != 6:
            gap(f"affix/enchant/{fid}", f"expected 6 tiers and 6 lang_keys, got {len(tiers)} and {len(lkeys)}")
        for lk in lkeys:
            if lk not in keys:
                gap(f"affix/enchant/{fid}", f"missing lang key {lk}")

    # 5. Storied items & epithets
    epithets = storied_doc.get("epithets", [])
    entries = storied_doc.get("entries", [])
    if len(epithets) < 8 or len(entries) < 20:
        gap("affix/storied", f"expected >=8 epithets and >=20 storied entries, got {len(epithets)} and {len(entries)}")
    for ep in epithets:
        if ep.get("lang_key", "") not in keys:
            gap(f"affix/epithet/{ep.get('id', '?')}", f"missing lang key {ep.get('lang_key')}")
    seen_prefixes = set()
    for st in entries:
        sid = st.get("id", "")
        pfx = st.get("prefix", "")
        if pfx in seen_prefixes:
            gap(f"affix/storied/{sid}", f"duplicate storied prefix '{pfx}'")
        seen_prefixes.add(pfx)
        for lk in (st.get("lang_prefix_key", ""), st.get("lang_prov_key", "")):
            if lk not in keys:
                gap(f"affix/storied/{sid}", f"missing lang key {lk}")
        why = st.get("why", [])
        cite_errs = []
        worldsheet_lint.check_why_list(f"affix/storied/{sid}", why, all_fields, forbidden_pairs, cite_errs)
        for ce in cite_errs:
            gap(f"affix/storied/{sid}", ce)
        has_l11 = any(".L11." in worldsheet_lint.canon_field_id(w) for w in why)
        if not has_l11:
            gap(f"affix/storied/{sid}", f"storied why {why} must cite at least one L11 history field")

    # 6. Novelty check on affix descriptions and provenances (4-gram Jaccard <= 0.55, skeleton <= 3)
    affix_texts = []
    for m in makers:
        affix_texts.append((f"maker:{m['id']}", m.get("desc", "")))
    for st in entries:
        affix_texts.append((f"storied:{st['id']}", st.get("provenance", "")))
    shingles_list = [novelty_lint.shingles(novelty_lint.words(t)) for _, t in affix_texts]
    for i in range(len(affix_texts)):
        for j in range(i + 1, len(affix_texts)):
            u = len(shingles_list[i] | shingles_list[j])
            jac = (len(shingles_list[i] & shingles_list[j]) / u) if u else 0.0
            if jac > 0.55:
                gap("affix/novelty", f"near-duplicate text ({jac:.2f}) between {affix_texts[i][0]} and {affix_texts[j][0]}")
    skels = {}
    for label, text in affix_texts:
        sk = novelty_lint.skeleton(text)
        if sk:
            skels.setdefault(sk, []).append(label)
    for sk, labels in skels.items():
        if len(labels) > 3:
            gap("affix/novelty", f"shared skeleton '{sk}' used {len(labels)} times: {labels}")

    return len(qualities_doc.get("qualities", [])) + len(makers) + len(fams) + len(entries)


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
            if ref.startswith("nirncraft:item/"):
                out.add(ref[len("nirncraft:item/"):])
    return out


def resolve_tag(tag, seen=None):
    """Item ids of an item tag defined in this mod's data (nested #tags expanded, optional entries kept)."""
    seen = seen or set()
    if tag in seen:
        return set()
    seen.add(tag)
    ns, _, path = tag.partition(":")
    out = set()
    for f in DATA.glob(f"{ns}/tags/item/{path}.json"):
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
    ap.add_argument("--dump", default=str(ROOT / "mod" / "run" / "nirncraft-item-audit.json"))
    ap.add_argument("--out")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    keys, vals = lang_keys()
    literal, lists = java_literal_ids()
    variants = variant_models()
    dump = {}
    if os.path.exists(args.dump):
        dump = json.load(open(args.dump, encoding="utf-8"))
    items = dump.get("items", {})

    ids = set(items)
    if not ids:
        model_stems = {f.stem for f in (ASSETS / "models" / "item").glob("*.json")} - variants
        ids = (
            {
                k.split(".", 2)[2]
                for k in keys
                if k.startswith("item.nirncraft.") and k.count(".") == 2 and "%s" not in str(vals.get(k, ""))
            }
            & (literal | model_stems)
        ) | literal
    gaps = []

    def gap(item, what):
        gaps.append((item, what))

    affix_count = audit_affixes(keys, vals, gap)

    trophies = {i.split(":", 1)[1] for i in resolve_tag("nirncraft:trophies")}
    expected_ingredients = set(lists.get("CreatureItems.INGREDIENTS", [])) | set(lists.get("ArcaneRegistry.MORE_INGREDIENTS", []))
    expected_ingredients -= trophies

    if items:
        for lit in sorted(literal - ids):
            if lit not in variants and not lit.endswith("_crop"):
                gap(lit, "registered in Java source but missing from the running game (or a typo)")
        for missing in dump.get("unregistered_ingredients", []):
            if missing.startswith("nirncraft:"):
                gap(missing, "alchemy ingredient table names an item that is not registered")

    for i in sorted(ids):
        info = items.get(i, {})
        cls = info.get("class", "").rsplit(".", 1)[-1].split("$")[-1]
        egg = i.endswith("_spawn_egg")
        # names and art
        if f"item.nirncraft.{i}" not in keys and f"block.nirncraft.{i}" not in keys:
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
        jewelry = "nirncraft:jewelry" in tags
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

    lines = []
    by_item = {}
    for item, what in gaps:
        by_item.setdefault(item, []).append(what)
    for item in sorted(by_item):
        for what in by_item[item]:
            lines.append(f"GAP {item}: {what}")
    summary = f"audited {len(ids)} items ({len(items)} from the runtime dump) and {affix_count} affix definitions, {len(gaps)} gaps"
    if args.verbose and items:
        kinds = {}
        for i, info in items.items():
            kinds[info["inv_category"]] = kinds.get(info["inv_category"], 0) + 1
        lines.append("categories: " + ", ".join(f"{k} {v}" for k, v in sorted(kinds.items())))
        sold = sum(1 for info in items.values() if info.get("merchants"))
        lines.append(f"items some merchant stocks: {sold}")
        ing = sum(1 for info in items.values() if "effects" in info)
        lines.append(f"NirnCraft alchemy ingredients: {ing}")
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
