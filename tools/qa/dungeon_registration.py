"""Trace indexed named sites through paired placement and structure filters (no game required).

This proves registration eligibility only. Emitted blocks, access, terrain and visuals require the in-game gate.
"""
from __future__ import annotations
import argparse
import collections
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "mod/src/main/resources"


def matches(rule: dict, place: dict) -> bool:
    return place["type"] in rule.get("types", []) and (
        not rule.get("variants") or place.get("variant", "") in rule["variants"])


def read_places(resources: Path) -> tuple[list[dict], list[dict]]:
    folder = resources / "nirncraft/tamriel/places"
    by_id: dict[str, dict] = {}
    duplicates = []
    for filename in json.loads((folder / "index.json").read_text(encoding="utf-8")):
        for offset, item in enumerate(json.loads((folder / filename).read_text(encoding="utf-8"))):
            record = dict(item, source=filename, source_record=offset)
            if item["id"] in by_id:
                duplicates.append(record)
            else:
                by_id[item["id"]] = record
    return list(by_id.values()), duplicates


def registration_rules(resources: Path) -> list[dict]:
    # Parse each registry file once; thousands of site queries reuse this immutable snapshot.
    worldgen = resources / "data/nirncraft/worldgen"
    result = []
    for path in sorted((worldgen / "structure_set").rglob("*.json")):
        structure_set = json.loads(path.read_text(encoding="utf-8"))
        placement = structure_set.get("placement", {})
        if placement.get("type") != "nirncraft:places":
            continue
        for entry in structure_set.get("structures", []):
            name = entry["structure"]
            if not name.startswith("nirncraft:"):
                continue
            definition = worldgen / "structure" / (name.split(":", 1)[1] + ".json")
            if not definition.exists():
                continue
            structure = json.loads(definition.read_text(encoding="utf-8"))
            if structure.get("type") != "nirncraft:dungeon" or "place" not in structure:
                continue
            metadata = {"structure": name, "theme": structure["theme"], "kit": structure.get("kit", "none"),
                        "regional_kit": structure.get("regional_kit", False), "flavor": structure.get("flavor"),
                        "placement": path.relative_to(resources).as_posix(), "salt": placement.get("salt")}
            result.append({"structure_filter": structure["place"], "placement_filter": placement, "metadata": metadata})
    return result


def paired_emitters(resources: Path, place: dict, rules: list[dict] | None = None) -> list[dict]:
    rules = registration_rules(resources) if rules is None else rules
    return [dict(rule["metadata"]) for rule in rules
            if matches(rule["placement_filter"], place) and matches(rule["structure_filter"], place)]


def marquee_town_conflicts(resources: Path, places: list[dict]) -> list[dict]:
    marquee = resources / "nirncraft/dungeons/marquee"
    cities = resources / "nirncraft/tamriel/cityplans"
    if not (marquee / "index.json").exists() or not (cities / "index.json").exists():
        return []
    marquee_ids = {name.removesuffix(".json") for name in json.loads((marquee / "index.json").read_text(encoding="utf-8"))}
    city_ids = set(json.loads((cities / "index.json").read_text(encoding="utf-8")).get("plans", []))
    conflicts = []
    for place in places:
        if place["type"] != "town" or place["id"] not in marquee_ids:
            continue
        plan = json.loads((marquee / (place["id"] + ".json")).read_text(encoding="utf-8"))
        city = json.loads((cities / (place["id"] + ".json")).read_text(encoding="utf-8")) if place["id"] in city_ids else {}
        conflicts.append({"id": place["id"], "authored_city_plan": bool(city), "city_reach": city.get("reach"),
                          "marquee_open_top": plan.get("open_top", False), "room_count": len(plan["rooms"]),
                          "entry_depth": plan.get("entry_depth", 20), "authored_buildings": len(city.get("buildings", [])),
                          "status": "requires_authored_cellar_entrance_and_collision_review" if city else "bespoke_fallback_candidate"})
    return conflicts


def audit(resources: Path = RESOURCES) -> dict:
    places, duplicates = read_places(resources)
    records = []
    rules = registration_rules(resources)
    for place in places:
        if place["type"] not in ("dungeon_fixed", "fort"):
            continue
        emitters = paired_emitters(resources, place, rules)
        records.append(dict(place, emitters=emitters,
                            status="paired_registration_runtime_pending" if emitters else "no_paired_named_emitter"))
    missing = [r for r in records if not r["emitters"]]
    return {"scope": "Indexed named dungeon_fixed/fort places; first duplicate wins exactly as Places.java",
            "limits": "A matching pair is eligibility, not emitted/access/visual proof. Absence requires alternate-route source audit.",
            "summary": {"records": len(records), "paired": len(records) - len(missing), "missing_pairs": len(missing),
                        "missing_variants": dict(sorted(collections.Counter(p.get("variant", "") for p in missing).items())),
                        "duplicate_place_records": len(duplicates)},
            "records": records, "duplicates": duplicates, "marquee_towns": marquee_town_conflicts(resources, places)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", type=Path, default=RESOURCES)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = audit(args.resources)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["summary"], indent=2))


if __name__ == "__main__":
    main()
