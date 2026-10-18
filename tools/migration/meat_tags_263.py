"""Preserve authored FoodSpec.properties(meat=true) through native wolf-food tags."""
from pathlib import Path
import json
import re
import sys

sys.path.insert(0, str(Path(__file__).parent))
from nbt_263 import calls


def authored_meat_ids(java):
    java = Path(java)
    result = set()
    source = (java / "SurvivalRegistry.java").read_text(encoding="utf-8")

    def collect(args, original):
        if len(args) == 4 and args[2].strip() == "true" and re.fullmatch(r'"[a-z0-9_]+"', args[0].strip()):
            result.add("skycraft:" + args[0].strip()[1:-1])
        return original

    calls(source, "food", collect)
    fishing = (java / "fishing/FishingRegistry.java").read_text(encoding="utf-8")
    if "new SkyFoodItem(spec, true," not in fishing:
        raise ValueError("Review FishingRegistry's authored meat flag before generating wolf food")

    def collect_fishing(args, original):
        if len(args) == 3 and re.fullmatch(r'"[a-z0-9_]+"', args[0].strip()):
            result.add("skycraft:" + args[0].strip()[1:-1])
        return original

    calls(fishing, "food", collect_fishing)
    fish_item = (java / "fishing/FishItem.java").read_text(encoding="utf-8")
    if "true, props)" not in fish_item:
        raise ValueError("Review FishItem's authored meat flag before generating wolf food")
    species = (java / "fishing/FishSpecies.java").read_text(encoding="utf-8")
    result.update("skycraft:" + identifier for identifier in re.findall(r'\bfish\("([a-z0-9_]+)"\s*,', species))
    return sorted(result)


def upgrade(resources):
    resources = Path(resources)
    java = resources.parents[1] / "java/com/skycraft/survival"
    if not (java / "SurvivalRegistry.java").exists():
        return 0
    target = resources / "skycraft/tags/item/legacy_wolf_food.json"
    value = {"replace": False, "values": authored_meat_ids(java)}
    files = {target: value}
    wolf = resources / "minecraft/tags/item/wolf_food.json"
    native = json.loads(wolf.read_text(encoding="utf-8")) if wolf.exists() else {"replace": False, "values": []}
    if "#skycraft:legacy_wolf_food" not in native.get("values", []):
        native.setdefault("values", []).append("#skycraft:legacy_wolf_food")
    files[wolf] = native
    changed = 0
    for path, value in files.items():
        if path.exists() and json.loads(path.read_text(encoding="utf-8")) == value:
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        changed += 1
    return changed


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2] / "mod/src/main/resources/data"
    print("Updated native meat classification tags:", upgrade(root))
