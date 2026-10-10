"""Generates Better Combat weapon attribute files for Skycraft weapons (ignored when Better Combat is absent)."""
import json, os
root = os.path.join(os.path.dirname(__file__), "..", "mod", "src", "main", "resources", "data", "skycraft", "weapon_attributes")
os.makedirs(root, exist_ok=True)
tiers = ["iron", "steel", "orcish", "dwarven", "elven", "glass", "ebony", "daedric", "dragonbone"]
presets = {"dagger": "bettercombat:dagger", "sword": "bettercombat:sword", "war_axe": "bettercombat:axe",
           "mace": "bettercombat:mace", "greatsword": "bettercombat:claymore", "battleaxe": "bettercombat:double_axe",
           "warhammer": "bettercombat:hammer"}
n = 0
for t in tiers:
    for kind, parent in presets.items():
        with open(os.path.join(root, f"{t}_{kind}.json"), "w") as f:
            json.dump({"parent": parent}, f, indent=2)
        n += 1
# polearms (arsenal, Polearms skill): Better Combat's own spear / halberd / glaive movesets
for t in tiers:
    for kind in ("spear", "halberd", "glaive"):
        with open(os.path.join(root, f"{t}_{kind}.json"), "w") as f:
            json.dump({"parent": f"bettercombat:{kind}"}, f, indent=2)
        n += 1
for item, parent in {"bound_sword": "bettercombat:sword", "bound_battleaxe": "bettercombat:double_axe",
                     "bound_dagger": "bettercombat:dagger"}.items():
    with open(os.path.join(root, f"{item}.json"), "w") as f:
        json.dump({"parent": parent}, f, indent=2)
    n += 1
print(n, "weapon attribute files")
