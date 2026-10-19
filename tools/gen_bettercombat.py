"""Generates Better Combat weapon attribute files for NirnCraft weapons (ignored when Better Combat is absent).

Every weapon of tools/textures/gear_catalogue.py gets its family's preset (daggers, swords, axes, maces, claymores,
double axes, hammers), with per-material overrides (Akaviri katanas, Redguard and Khajiiti scimitars...).
Bows are left to Better Combat's defaults. Run from anywhere: python tools/gen_bettercombat.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "textures"))

import gear_catalogue as cat  # noqa: E402

root = os.path.join(HERE, "..", "mod", "src", "main", "resources", "data", "nirncraft", "weapon_attributes")


def main():
    os.makedirs(root, exist_ok=True)
    n = 0
    for item_id, t, w in cat.weapon_items():
        if w == "bow":
            continue
        parent = t.bc.get(w, cat.BC_PRESET[w])
        with open(os.path.join(root, f"{item_id}.json"), "w", newline="\n") as f:
            json.dump({"parent": parent}, f, indent=2)
        n += 1
    # polearms (arsenal, Polearms skill): Better Combat's own spear / halberd / glaive movesets
    for t in ["iron", "steel", "orcish", "dwarven", "elven", "glass", "ebony", "daedric", "dragonbone"]:
        for kind in ("spear", "halberd", "glaive"):
            with open(os.path.join(root, f"{t}_{kind}.json"), "w", newline="
") as f:
                json.dump({"parent": f"bettercombat:{kind}"}, f, indent=2)
            n += 1
    for item, parent in {"bound_sword": "bettercombat:sword", "bound_battleaxe": "bettercombat:double_axe",
                         "bound_dagger": "bettercombat:dagger"}.items():
        with open(os.path.join(root, f"{item}.json"), "w", newline="
") as f:
            json.dump({"parent": parent}, f, indent=2)
        n += 1
    print(n, "weapon attribute files")


if __name__ == "__main__":
    main()
