#!/usr/bin/env python3
"""Spell lint: every fixed spell of com.nirncraft.magic.spell.Spells is complete (offline, reads the Java and data).

Per `add(s("id", SCHOOL, TIER)...)` statement of Spells.java:
  schema   the id is unique and snake_case; the school variable names a School constant; the tier a Tier constant;
           the cost is set (`ff(cost)` fire-and-forget or `conc(costPerSecond)`) and > 0; an `element(Element.X)`
           names an Element constant
  lang     spell.nirncraft.<id> and spell.nirncraft.<id>.desc (Spell.name() / description() have no fallback)
  tome     the codebase maps every spell to a tome (MagicRegistry registers spell_tome_<id>): the tome has its item
           definition (assets/nirncraft/items) and model, an item.nirncraft.spell_tome_<id> lang name, a value in
           data/nirncraft/nirncraft_values, and is in the #nirncraft:spell_tomes tag (shops, inventory category)
  orphans  tome items, values or tag entries for spells that do not exist
There are no spell scrolls in the codebase (no scroll items), so no scroll mapping is checked.
Prints sections and `problems N`; exit 1 when N > 0.

    py -3 tools/qa/spell_lint.py [--limit 25]
"""
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import refs  # noqa: E402

SPELL = refs.JAVA / "com" / "nirncraft" / "magic" / "spell"
# spells whose zero magicka cost is the design, not a missing number (reviewed; keep this list short)
FREE = {"equilibrium": "Skyrim's Equilibrium pays in health: AlterationSpells.equilibrium trades health for magicka"}


def enum_constants(path, name):
    src = refs.load_text(path)
    m = re.search(rf"enum\s+{name}\s*\{{(.*?)(?:;|\}})", src, re.S)
    return set(re.findall(r"\b([A-Z][A-Z0-9_]*)\s*(?:\(|,|$)", m.group(1), re.M)) if m else set()


def spells():
    """[(id, school, tier, body, line)] of the fixed spells; plus the variable -> constant maps."""
    src = refs.load_text(SPELL / "Spells.java")
    static = src[src.find("static {"):]
    alias = dict(re.findall(r"\b([A-Z]{1,3})\s*=\s*(?:School|Tier)\.([A-Z_]+)", static))
    out = []
    starts = [m for m in re.finditer(r'add\(s\(\s*"([^"]*)"\s*,\s*(\w+)\s*,\s*(\w+)\s*\)', static)]
    for i, m in enumerate(starts):
        end = starts[i + 1].start() if i + 1 < len(starts) else len(static)
        body = static[m.end():end]
        line = src.count("\n", 0, src.find("static {") + m.start()) + 1
        out.append((m.group(1), alias.get(m.group(2), m.group(2)), alias.get(m.group(3), m.group(3)), body, line))
    return out


def audit(limit):
    pr = refs.Problems()
    lang = refs.lang()
    schools = enum_constants(SPELL / "School.java", "School")
    tiers = enum_constants(SPELL / "Tier.java", "Tier")
    elements = enum_constants(SPELL / "Element.java", "Element")
    found = spells()
    if not found:
        pr.add("setup", "no add(s(...)) statements parsed from Spells.java")
    values = {}
    for f in (refs.DATA / "nirncraft_values").glob("*.json"):
        values.update((refs.load(f, {}) or {}).get("values", {}))
    tag = refs.load(refs.DATA / "tags" / "item" / "spell_tomes.json", {}) or {}
    tagged = {v if isinstance(v, str) else v.get("id", "") for v in tag.get("values", [])}
    ids, by_school = set(), {}
    for sid, school, tier, body, line in found:
        w = f"{sid} (Spells.java:{line})"
        if sid in ids:
            pr.add("schema", f"{w}: defined twice")
        ids.add(sid)
        if not re.fullmatch(r"[a-z0-9_]+", sid):
            pr.add("schema", f"{w}: id is not snake_case (it is saved in player data and names the tome item)")
        if school not in schools:
            pr.add("schema", f"{w}: school {school} is no School constant")
        if tier not in tiers:
            pr.add("schema", f"{w}: tier {tier} is no Tier constant")
        by_school[school] = by_school.get(school, 0) + 1
        cost = re.search(r"\.(ff|conc)\(\s*([0-9.]+)f?\s*\)", body)
        if not cost:
            pr.add("cost", f"{w}: no cost (neither ff(cost) nor conc(costPerSecond))")
        elif float(cost.group(2)) <= 0 and sid not in FREE:
            pr.add("cost", f"{w}: cost {cost.group(2)} is not > 0")
        elif float(cost.group(2)) <= 0:
            print(f"note: {sid} costs no magicka by design ({FREE[sid]})")
        for e in re.findall(r"Element\.([A-Z_]+)", body):
            if elements and e not in elements:
                pr.add("schema", f"{w}: element {e} is no Element constant")
        for k in (f"spell.nirncraft.{sid}", f"spell.nirncraft.{sid}.desc"):
            if k not in lang:
                pr.add("lang", f"{w}: no lang {k}")
        tome = f"spell_tome_{sid}"
        idef = refs.ASSETS / "items" / f"{tome}.json"
        if not idef.is_file():
            pr.add("tome", f"{w}: no item definition assets/nirncraft/items/{tome}.json")
        else:
            for model in re.findall(r'"model":\s*"nirncraft:([a-z0-9_/]+)"', refs.load_text(idef)):
                if not (refs.ASSETS / "models" / f"{model}.json").is_file():
                    pr.add("tome", f"{w}: tome model nirncraft:{model} has no models/{model}.json")
        if f"item.nirncraft.{tome}" not in lang:
            pr.add("lang", f"{w}: no lang item.nirncraft.{tome}")
        if f"nirncraft:{tome}" not in values:
            pr.add("tome", f"{w}: tome nirncraft:{tome} has no value (nirncraft_values)")
        if tagged and f"nirncraft:{tome}" not in tagged:
            pr.add("tome", f"{w}: tome nirncraft:{tome} is not in #nirncraft:spell_tomes")
    if not tagged:
        pr.add("tome", "data/nirncraft/tags/item/spell_tomes.json is missing or empty")
    tomes = {f.stem[len("spell_tome_"):] for f in (refs.ASSETS / "items").glob("spell_tome_*.json")}
    for t in sorted(tomes - ids):
        pr.add("orphans", f"assets/nirncraft/items/spell_tome_{t}.json: no spell {t}")
    for v in sorted(k for k in values if k.startswith("nirncraft:spell_tome_") and k[len("nirncraft:spell_tome_"):] not in ids):
        pr.add("orphans", f"value for {v}: no such spell")
    for v in sorted(t for t in tagged if t.startswith("nirncraft:spell_tome_") and t[len("nirncraft:spell_tome_"):] not in ids):
        pr.add("orphans", f"#nirncraft:spell_tomes lists {v}: no such spell")
    print(f"spells: {len(found)} fixed spells (" + ", ".join(f"{k.lower()} {v}" for k, v in sorted(by_school.items()))
          + f"); {len(schools)} schools, {len(tiers)} tiers; {len(tomes)} tome items, {len(tagged)} tagged")
    for s in sorted(schools - set(by_school)):
        pr.add("schema", f"school {s} has no spell")
    return pr.report(limit)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=25)
    return audit(ap.parse_args().limit)


if __name__ == "__main__":
    raise SystemExit(main())
