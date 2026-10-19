#!/usr/bin/env python3
"""Alchemy ingredient x effect matrix (offline, reads the Java tables).

Ingredients: every add("id", A, B, C, D) of com.nirncraft.crafting.arcane.alchemy.Ingredients plus every fish of
com.nirncraft.survival.fishing.FishSpecies (FishingRegistry registers each as nirncraft:<id> through
Ingredients.register, which skips ids the table already has). Effects: the AlchemyEffect enum.
  ingredients  exactly four effects, four distinct, each an AlchemyEffect constant; no id defined twice (a fish that
               repeats a table id is silently ignored); the item exists (nirncraft item definitions / lang, vanilla
               26.3 items when the reference jar is present): a missing item is never matched
  effects      every effect is carried by >= 2 ingredients, or no potion of it can be brewed (Skyrim rule: two
               ingredients sharing the effect)
Prints the matrix summary (rarest effects first), the problems and `problems N`; exit 1 when N > 0.

    py -3 tools/qa/ingredient_matrix.py [--limit 25] [--matrix]
"""
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import refs  # noqa: E402

ALCH = refs.JAVA / "com" / "nirncraft" / "crafting" / "arcane" / "alchemy"
FISH = refs.JAVA / "com" / "nirncraft" / "survival" / "fishing" / "FishSpecies.java"


def effects_enum():
    src = refs.load_text(ALCH / "AlchemyEffect.java")
    body = src[src.find("enum AlchemyEffect"):]
    body = body[:body.find(";\n")] if ";\n" in body else body
    return re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\s*\(", body, re.M)


def ingredients():
    """[(id, [effects], source)] in registration order."""
    out = []
    src = refs.load_text(ALCH / "Ingredients.java")
    static = src[src.find("static {"):]
    for m in re.finditer(r'\badd\(\s*"([a-z0-9_]+:[a-z0-9_/]+)"\s*,([^;]*?)\)\s*;', static, re.S):
        out.append((m.group(1), re.findall(r"\b([A-Z][A-Z0-9_]+)\b", m.group(2)), f"Ingredients.java:{src.count(chr(10), 0, src.find('static {') + m.start()) + 1}"))
    fsrc = refs.load_text(FISH)
    for m in re.finditer(r'\bfish\(\s*"([a-z0-9_]+)"\s*,(.*?)\)\s*;', fsrc, re.S):
        if "String id" in m.group(0):
            continue  # the helper's own declaration
        effs = re.findall(r"(?<![.\w])([A-Z][A-Z0-9_]{2,})(?![.\w(])", m.group(2))
        effs = [e for e in effs if e not in ("ANY", "DAY", "NIGHT", "DUSK", "DAWN")][-4:]
        out.append(("nirncraft:" + m.group(1), effs, f"FishSpecies.java:{fsrc.count(chr(10), 0, m.start()) + 1}"))
    return out


def audit(limit, show_matrix):
    pr = refs.Problems()
    effects = effects_enum()
    ings = ingredients()
    if not effects or not ings:
        pr.add("setup", f"parsed {len(effects)} effects and {len(ings)} ingredients: the Java layout changed")
    known = set(effects)
    carriers = {e: [] for e in effects}
    seen = {}
    for iid, effs, where in ings:
        if iid in seen:
            pr.add("ingredients", f"{iid} ({where}) is already defined at {seen[iid]} (this one is ignored)")
            continue
        seen[iid] = where
        if len(effs) != 4:
            pr.add("ingredients", f"{iid} ({where}): {len(effs)} effects {effs}, Skyrim ingredients have four")
        if len(set(effs)) != len(effs):
            pr.add("ingredients", f"{iid} ({where}): repeated effect {effs}")
        for e in effs:
            if e not in known:
                pr.add("ingredients", f"{iid} ({where}): {e} is no AlchemyEffect")
            else:
                carriers[e].append(iid)
        if refs.item_ok(iid) is False:
            pr.add("items", f"{iid} ({where}): no such item (the ingredient is never matched)")
    for e in effects:
        n = len(set(carriers[e]))
        if n < 2:
            pr.add("effects", f"{e}: carried by {n} ingredient(s) {sorted(set(carriers[e]))}: not brewable")
    counts = sorted(((len(set(v)), e) for e, v in carriers.items()))
    print(f"alchemy: {len(seen)} ingredients ({sum(1 for _, _, w in ings if w.startswith('Fish'))} fish), "
          f"{len(effects)} effects; {refs.jar_note()}")
    print("rarest effects: " + ", ".join(f"{e} {n}" for n, e in counts[:8]))
    print("commonest effects: " + ", ".join(f"{e} {n}" for n, e in counts[::-1][:5]))
    if show_matrix:
        for n, e in counts:
            print(f"  {e:28s} {n:3d}  {' '.join(sorted(set(carriers[e])))}")
    return pr.report(limit)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=25)
    ap.add_argument("--matrix", action="store_true")
    a = ap.parse_args()
    return audit(a.limit, a.matrix)


if __name__ == "__main__":
    raise SystemExit(main())
