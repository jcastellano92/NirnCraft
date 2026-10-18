"""Preserve global loot predicates in native NeoForge 26.3 optional-condition format."""
from pathlib import Path
import json
ROOT = Path(__file__).resolve().parents[2]
def convert(data):
    if isinstance(data, list): return [convert(x) for x in data]
    if not isinstance(data, dict): return data
    out = {key: convert(value) for key, value in data.items()}
    if isinstance(out.get("condition"), str):
        kind = out.pop("condition")
        out["type"] = "neoforge:" + kind[6:] if kind.startswith("forge:") else kind
    if out.get("type") == "minecraft:block_state_property":
        out["type"] = "minecraft:match_block"
        if "block" in out: out["blocks"] = out.pop("block")
        if "properties" in out: out["state"] = out.pop("properties")
    if "conditions" in out and isinstance(out.get("type"), str):
        terms = out.pop("conditions")
        if terms: out["condition"] = terms[0] if len(terms) == 1 else {"type": "minecraft:all_of", "terms": terms}
    return out
if __name__ == "__main__":
    count = 0
    for path in (ROOT / "mod/src/main/resources/data").glob("*/loot_modifiers/*.json"):
        raw = json.loads(path.read_text(encoding="utf-8")); result = convert(raw)
        if result != raw:
            path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8"); count += 1
    print("Converted global loot modifier data:", count)
