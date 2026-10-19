"""Preserve authored legacy MobType memberships through native 26.3 entity tags."""
from pathlib import Path
import argparse
import json

# Explicit legacy undead implementations plus the four registered RealmCreatures
# specs with undead=true. Daedra and other spirits do not implicitly become undead.
UNDEAD = ("draugr", "draugr_deathlord", "ash_spawn", "death_hound", "dragon_priest", "gargoyle",
          "keeper", "boneman", "mistman", "wrathman")
ARTHROPOD = ("frostbite_spider",)
AQUATIC = ("sea_serpent", "slaughterfish")
MEMBERS = {"undead": UNDEAD, "inverted_healing_and_harm": UNDEAD, "sensitive_to_smite": UNDEAD,
           "arthropod": ARTHROPOD, "sensitive_to_bane_of_arthropods": ARTHROPOD,
           "aquatic": AQUATIC, "sensitive_to_impaling": AQUATIC}

def upgrade(root):
    changed = 0
    for tag, members in MEMBERS.items():
        path = Path(root)/"minecraft/tags/entity_type"/(tag+".json")
        original = json.loads(path.read_text(encoding="utf-8")) if path.exists() else None
        converted = dict(original or {"values": []})
        converted["replace"] = False
        converted["values"] = list(converted.get("values", []))
        for member in members:
            identifier = "nirncraft:"+member
            if identifier not in converted["values"]: converted["values"].append(identifier)
        if converted != original:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(converted,indent=2)+"\n",encoding="utf-8")
            changed += 1
    return changed

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources",type=Path,default=Path("mod/src/main/resources/data"))
    print(upgrade(parser.parse_args().resources))
