"""Registration contracts: every kind of thing the mod registers must be wired into every place that kind needs, so a
new weapon / armour piece / block / entity added by anyone (or any agent) cannot silently miss a step.

    py -3 tools/harness/audits/contracts.py [--kind weapon] [--id daedric_sword] [--update-baseline]

Contracts (mandatory; "advisory" ones are reported, never fail):
  item     lang name, item definition (assets/.../items/ID.json), its model, the model's textures, a value, a weight
  weapon   + weapon_attributes/ID.json, an item tag (weapon class), obtainable (a recipe or a loot table names it)
  armor    + the material's equipment asset and humanoid / humanoid_leggings textures, an item tag, obtainable
  block    lang name, blockstate, the models it uses, an item definition, a block loot table; advisory: material id
  entity   lang name; advisory: a texture under textures/entity whose name mentions it, a spawn egg
Existing gaps live in tools/harness/audits/contracts-baseline.json: only NEW gaps fail (exit 1). Fixing a gap shrinks
the baseline with --update-baseline; adding to it needs --accept-new (a deliberate, reviewable change).
The visual side needs no wiring: every registered entity, armour set, weapon and block gets gallery scenes by itself
(tools/harness/catalog.py gallery), so `harness.py run --tag gallery:material:<m>` shows a new set at once.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RES = ROOT / "mod/src/main/resources"
A = RES / "assets/nirncraft"
D = RES / "data/nirncraft"
LANG = ROOT / "mod/src/main/lang/nirncraft"
BASELINE = Path(__file__).with_name("contracts-baseline.json")
WEAPON = re.compile(r"_(sword|dagger|greatsword|battleaxe|war_axe|mace|warhammer|bow|crossbow|staff|spear|glaive|"
                    r"halberd|claw|katana|scimitar)$")
ARMOR = re.compile(r"_(helmet|chestplate|leggings|boots)$")


def lang_ids(kind):
    ids = set()
    for f in LANG.glob("*.json"):
        for k in json.loads(f.read_text(encoding="utf-8")):
            p = k.split(".")
            if len(p) == 3 and p[0] == kind and p[1] == "nirncraft":
                ids.add(p[2])
    return ids


def mentions(folder):
    """Every nirncraft:<id> named anywhere under a data folder (recipes, loot tables, tags)."""
    out = set()
    for f in folder.rglob("*.json"):
        out.update(re.findall(r"nirncraft:([a-z0-9_/]+)", f.read_text(encoding="utf-8", errors="replace")))
    return out


def json_ids(folder):
    out = set()
    for f in folder.rglob("*.json"):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except ValueError:
            continue
        for v in (data.get("values") or data.get("weights") or data).keys() if isinstance(data, dict) else []:
            if isinstance(v, str) and v.startswith("nirncraft:"):
                out.add(v[10:])
    return out


def model_textures(model_ref, seen=None):
    """(missing models, missing textures) for a nirncraft model and its nirncraft parents."""
    seen = seen or set()
    if not model_ref.startswith("nirncraft:") or model_ref in seen:
        return [], []
    seen.add(model_ref)
    path = A / "models" / (model_ref[10:] + ".json")
    if not path.exists():
        return [model_ref], []
    m = json.loads(path.read_text(encoding="utf-8"))
    miss_t = [t for t in (m.get("textures") or {}).values()
              if isinstance(t, str) and t.startswith("nirncraft:") and not (A / "textures" / (t[10:] + ".png")).exists()]
    pm, pt = model_textures(m.get("parent", ""), seen) if m.get("parent") else ([], [])
    return pm, miss_t + pt


def item_model_refs(defn):
    refs = []
    if isinstance(defn, dict):
        if isinstance(defn.get("model"), str):
            refs.append(defn["model"])
        for v in defn.values():
            refs += item_model_refs(v)
    elif isinstance(defn, list):
        for v in defn:
            refs += item_model_refs(v)
    return refs


FAMILY = {"d": "dagger", "s": "sword", "a": "war_axe", "m": "mace", "g": "greatsword", "b": "battleaxe",
          "h": "warhammer", "w": "bow"}


def code_provided():
    """Ids whose value, weight, recipe and tags come from Java tables (SmithingTier weapon families by its letter code,
    SkyArmorMaterial armour sets), so JSON lookups would report false gaps for them."""
    ids = set()
    java = ROOT / "mod/src/main/java/com/nirncraft/crafting"
    for line in (java / "SmithingTier.java").read_text(encoding="utf-8").splitlines():
        m = re.match(r'\s+[A-Z_]+\("([a-z_]+)",.*"([a-z]+)", \d+\),?$', line)
        if m:
            ids.update(f"{m.group(1)}_{FAMILY[c]}" for c in m.group(2) if c in FAMILY)
    for line in (java / "SkyArmorMaterial.java").read_text(encoding="utf-8").splitlines():
        m = re.match(r'\s+[A-Z_]+\("([a-z_]+)",', line)
        if m:
            ids.update(f"{m.group(1)}_{p}" for p in ("helmet", "chestplate", "leggings", "boots"))
    return ids


def audit():
    gaps, advisory = {}, {}

    def gap(kind, i, what):
        gaps.setdefault(kind, {}).setdefault(i, []).append(what)

    def adv(kind, i, what):
        advisory.setdefault(kind, {}).setdefault(i, []).append(what)

    items, blocks, entities = lang_ids("item"), lang_ids("block"), lang_ids("entity")
    values, weights = json_ids(D / "nirncraft_values"), json_ids(D / "nirncraft_weights")
    tagged = mentions(D / "tags")
    obtainable = mentions(D / "recipe") | mentions(D / "loot_table")
    coded = code_provided()
    for i in sorted(items):
        kind = "weapon" if WEAPON.search(i) else "armor" if ARMOR.search(i) else "item"
        if i in coded:  # value / weight / smithing recipe / tags from the Java tables
            values.add(i)
            weights.add(i)
            tagged.add(i)
            obtainable.add(i)
        if i.endswith("_spawn_egg"):  # creative-only: no price or weight
            values.add(i)
            weights.add(i)
        defn = A / "items" / f"{i}.json"
        if not defn.exists():
            gap(kind, i, "item definition")
        else:
            for ref in item_model_refs(json.loads(defn.read_text(encoding="utf-8"))):
                mm, mt = model_textures(ref)
                for x in mm:
                    gap(kind, i, f"model {x}")
                for x in mt:
                    gap(kind, i, f"texture {x}")
        if i not in values:
            gap(kind, i, "value")
        if i not in weights:
            gap(kind, i, "weight")
        if kind in ("weapon", "armor"):
            if i not in tagged:
                gap(kind, i, "item tag")
            if i not in obtainable:
                gap(kind, i, "obtainable (recipe or loot)")
        if kind == "weapon" and not (D / "weapon_attributes" / f"{i}.json").exists():
            gap(kind, i, "weapon_attributes")
        if kind == "armor":
            mat = ARMOR.sub("", i)
            if not (A / "equipment" / f"{mat}.json").exists():
                gap(kind, i, f"equipment/{mat}.json")
            for layer in ("humanoid", "humanoid_leggings"):
                if not (A / "textures/entity/equipment" / layer / f"{mat}.png").exists():
                    gap(kind, i, f"texture entity/equipment/{layer}/{mat}.png")
    props = (ROOT / "shaderpack/InfiniteATLAS/shaders/block.properties").read_text(encoding="utf-8")
    for b in sorted(blocks):
        bs = A / "blockstates" / f"{b}.json"
        if not bs.exists():
            gap("block", b, "blockstate")
        else:
            for ref in set(re.findall(r'"model"\s*:\s*"([^"]+)"', bs.read_text(encoding="utf-8"))):
                mm, mt = model_textures(ref)
                for x in mm:
                    gap("block", b, f"model {x}")
                for x in mt:
                    gap("block", b, f"texture {x}")
        if not (A / "items" / f"{b}.json").exists():
            gap("block", b, "item definition")
        if not (D / "loot_table/blocks" / f"{b}.json").exists():
            gap("block", b, "loot_table/blocks")
        if f"nirncraft:{b}" not in props:
            adv("block", b, "no Infinite ATLAS material id (block.properties)")
    tex = [p.stem for p in (A / "textures/entity").rglob("*.png")] if (A / "textures/entity").exists() else []
    for e in sorted(entities):
        if not any(e in t for t in tex):
            adv("entity", e, "no texture whose name mentions it (may be layered / shared)")
        if f"{e}_spawn_egg" not in items:
            adv("entity", e, "no spawn egg")
    return gaps, advisory


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind")
    ap.add_argument("--id")
    ap.add_argument("--update-baseline", action="store_true", help="drop fixed gaps from the baseline")
    ap.add_argument("--accept-new", action="store_true", help="with --update-baseline: also accept the new gaps")
    a = ap.parse_args()
    gaps, advisory = audit()
    base = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    new, fixed, total = {}, 0, 0
    for kind, ids in gaps.items():
        for i, what in ids.items():
            total += len(what)
            known = set(base.get(kind, {}).get(i, []))
            for w in what:
                if w not in known:
                    new.setdefault(f"{kind}:{i}", []).append(w)
    for kind, ids in base.items():
        for i, what in ids.items():
            fixed += sum(1 for w in what if w not in gaps.get(kind, {}).get(i, []))
    if a.kind or a.id:
        for kind, ids in gaps.items():
            for i, what in ids.items():
                if (not a.kind or kind == a.kind) and (not a.id or i == a.id):
                    print(f"{kind}:{i}: missing " + "; ".join(what))
    counts = {k: len(v) for k, v in gaps.items()}
    print(f"contracts: {total} gap(s) in " + ", ".join(f"{k} {n}" for k, n in sorted(counts.items()))
          + f"; problems {sum(len(v) for v in new.values())} new, {fixed} fixed since the baseline")
    for k, v in list(new.items())[:40]:
        print(f"  NEW {k}: missing " + "; ".join(v))
    if len(new) > 40:
        print(f"  ... {len(new) - 40} more")
    adv_n = sum(len(v) for ids in advisory.values() for v in ids.values())
    print(f"advisory: {adv_n} note(s) (" + ", ".join(f"{k} {len(v)}" for k, v in advisory.items()) + ")")
    if a.update_baseline:
        keep = {}
        for kind, ids in gaps.items():
            for i, what in ids.items():
                known = set(base.get(kind, {}).get(i, []))
                w = what if a.accept_new else [x for x in what if x in known]
                if w:
                    keep.setdefault(kind, {})[i] = w
        BASELINE.write_text(json.dumps(keep, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        print(f"baseline updated: {BASELINE}")
        return 0
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
