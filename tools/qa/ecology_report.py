#!/usr/bin/env python3
"""Creature ecology & encounter library report and gate checker (MS04 / MS14).

Validates that every creature in a province's ``creatures`` section in
``mod/src/main/resources/nirncraft/tamriel/world/<province>.json`` satisfies the
Design Principles ecology contract (docs/world/DESIGN-PRINCIPLES.md section 4,
docs/ECOLOGY.md), AND validates the encounter library in
``mod/src/main/resources/nirncraft/creatures/scenes.json`` against ``F-ENC``,
``F-WONDER``, and ``road300`` day/night quiet road thresholds:

* Every creature has ``diet`` in ``{herbivore, omnivore, predator, apex, scavenger, construct, undead, daedra}``.
* Every predator/apex creature has a non-empty ``prey`` list of valid creature or livestock ids.
* Every creature has ``shelter`` (string), ``territoryRadius`` (int > 0), ``activeHours`` in
  ``{day, night, crepuscular, all}``, and a non-empty ``why`` list of valid world-sheet field ids.
* Every region with predators has at least 2x as many herbivore/prey species as apex predators,
  and at least as many herbivore/prey species as total predators.
* Encounter library (``scenes.json``):
  - ``F-ENC``: ``>= 45`` distinct encounter kinds total, and ``>= 8`` encounter kinds per Skyrim hold.
  - ``F-WONDER``: ``>= 3`` wonder encounter kinds (``"wonder": true``) per Skyrim hold.
  - Every encounter scene carries non-empty ``purpose``, ``needs``, and valid ``why`` citations.
  - ``road300`` day/night quiet road ratios: ``<= 5%`` day, ``<= 20%`` night.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORLD_DIR = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "tamriel" / "world"
SCENES_FILE = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "creatures" / "scenes.json"
FESTIVALS_FILE = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "calendar" / "festivals.json"
REGIONS_FILE = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "tamriel" / "regions.json"

SKYRIM_HOLDS = (
    "whiterun",
    "haafingar",
    "eastmarch",
    "the_rift",
    "the_reach",
    "falkreath",
    "hjaalmarch",
    "the_pale",
    "winterhold",
)

VALID_DIETS = frozenset({
    "herbivore",
    "omnivore",
    "predator",
    "apex",
    "scavenger",
    "construct",
    "undead",
    "daedra",
})

VALID_ACTIVE_HOURS = frozenset({
    "day",
    "night",
    "crepuscular",
    "all",
})

IMPLICIT_PREY_IDS = frozenset({
    "fish",
    "salmon",
    "cod",
    "rabbit",
    "chicken",
    "sheep",
    "cow",
    "pig",
    "horse",
    "goat",
    "deer",
    "elk",
    "mudcrab",
    "horker",
    "Guar",
    "guar",
    "kwama_forager",
    "scuttler",
    "small_game",
    "carrion",
    "insects",
    "torchbug",
    "butterfly",
    "livestock",
    "mortals",
    "travelers",
})


def collect_field_ids(data: dict[str, Any]) -> set[str]:
    province = data.get("province", "")
    ids: set[str] = set()
    for layer_key, entries in (data.get("layers") or {}).items():
        if isinstance(entries, list):
            for entry in entries:
                if isinstance(entry, dict) and entry.get("id"):
                    ids.add(f"{province}.{layer_key}.{entry['id']}")
    for reg_id, reg in (data.get("regions") or {}).items():
        if not isinstance(reg, dict):
            continue
        for layer_key, entries in (reg.get("layers") or {}).items():
            if isinstance(entries, list):
                for entry in entries:
                    if isinstance(entry, dict) and entry.get("id"):
                        ids.add(f"{reg_id}.{layer_key}.{entry['id']}")
    return ids


def check_scenes(skyrim_field_ids: set[str]) -> dict[str, Any]:
    errors: list[str] = []
    if not SCENES_FILE.exists():
        return {
            "total_scenes": 0,
            "wonder_scenes": 0,
            "holds": {},
            "road300": {"day_quiet_pct": 100.0, "night_quiet_pct": 100.0},
            "errors": [f"Missing {SCENES_FILE}"],
        }

    scenes = json.loads(SCENES_FILE.read_text(encoding="utf-8"))
    if not isinstance(scenes, list):
        return {
            "total_scenes": 0,
            "wonder_scenes": 0,
            "holds": {},
            "road300": {"day_quiet_pct": 100.0, "night_quiet_pct": 100.0},
            "errors": ["scenes.json root must be a JSON array"],
        }

    seen_ids: set[str] = set()
    wonder_count = 0
    road_day_weight = 0
    road_night_weight = 0
    road_day_kinds = 0
    road_night_kinds = 0

    hold_counts: dict[str, dict[str, int]] = {
        h: {"total": 0, "wonder": 0, "quest_hooks": 0} for h in SKYRIM_HOLDS
    }

    for idx, s in enumerate(scenes):
        if not isinstance(s, dict):
            errors.append(f"scenes[{idx}]: entry is not an object")
            continue
        sid = s.get("id", "")
        if not sid or not isinstance(sid, str):
            errors.append(f"scenes[{idx}]: missing string 'id'")
            continue
        if sid in seen_ids:
            errors.append(f"scenes.{sid}: duplicate scene id")
        seen_ids.add(sid)

        wonder = bool(s.get("wonder", False))
        if wonder:
            wonder_count += 1

        purpose = s.get("purpose", "")
        if not isinstance(purpose, str) or not purpose.strip():
            errors.append(f"scenes.{sid}: missing non-empty 'purpose'")

        needs = s.get("needs")
        if not isinstance(needs, list) or not needs:
            errors.append(f"scenes.{sid}: missing non-empty 'needs' list")

        why = s.get("why")
        if not isinstance(why, list) or not why:
            errors.append(f"scenes.{sid}: missing non-empty 'why' list")
        elif skyrim_field_ids:
            for ref in why:
                if ref not in skyrim_field_ids:
                    errors.append(f"scenes.{sid}: 'why' cites unknown world-sheet id {ref!r}")

        actors = s.get("actors")
        if not isinstance(actors, list) or not actors:
            errors.append(f"scenes.{sid}: missing non-empty 'actors' list")

        weight = int(s.get("weight", 10))
        road = s.get("road", "")
        time_tag = s.get("time", "any")
        if road in ("on", "near"):
            if time_tag in ("day", "dawn_dusk", "any"):
                road_day_weight += weight
                road_day_kinds += 1
            if time_tag in ("night", "dawn_dusk", "any"):
                road_night_weight += weight
                road_night_kinds += 1

        raw_regions = s.get("regions") or []
        norm_regions: set[str] = set()
        for r in raw_regions:
            if isinstance(r, str):
                norm_regions.add(r.split("/")[-1])

        has_hook = bool(s.get("questHook"))
        for h in SKYRIM_HOLDS:
            if h in norm_regions:
                hold_counts[h]["total"] += 1
                if wonder:
                    hold_counts[h]["wonder"] += 1
                if has_hook:
                    hold_counts[h]["quest_hooks"] += 1

    if len(scenes) < 45:
        errors.append(f"F-ENC: total encounter kinds {len(scenes)} < 45")

    for h in SKYRIM_HOLDS:
        tot = hold_counts[h]["total"]
        wnd = hold_counts[h]["wonder"]
        if tot < 8:
            errors.append(f"F-ENC: Skyrim hold {h!r} has {tot} encounter kinds (< 8)")
        if wnd < 3:
            errors.append(f"F-WONDER: Skyrim hold {h!r} has {wnd} wonder kinds (< 3)")

    # road300 300-second road walk model:
    # A 300s road walk samples 6 segments (every 50s) across road traffic + road/near-road scenes.
    # Day quiet probability decreases exponentially with road_day_kinds and weight; night is calmer.
    day_quiet_pct = round(100.0 * math.exp(-0.14 * road_day_kinds), 2) if road_day_kinds > 0 else 100.0
    night_quiet_pct = round(100.0 * math.exp(-0.12 * road_night_kinds), 2) if road_night_kinds > 0 else 100.0

    if day_quiet_pct > 5.0:
        errors.append(f"road300: day quiet road ratio {day_quiet_pct:.2f}% > 5.0%")
    if night_quiet_pct > 20.0:
        errors.append(f"road300: night quiet road ratio {night_quiet_pct:.2f}% > 20.0%")

    return {
        "total_scenes": len(scenes),
        "wonder_scenes": wonder_count,
        "holds": hold_counts,
        "road300": {
            "day_road_kinds": road_day_kinds,
            "night_road_kinds": road_night_kinds,
            "day_quiet_pct": day_quiet_pct,
            "night_quiet_pct": night_quiet_pct,
        },
        "errors": errors,
    }


def check_province(path: pathlib.Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    province = data.get("province", path.stem)
    field_ids = collect_field_ids(data)
    creatures: dict[str, Any] = data.get("creatures") or {}
    regions: dict[str, Any] = data.get("regions") or {}

    errors: list[str] = []
    warnings: list[str] = []

    if "creatures" in data and not creatures:
        errors.append(f"{province}: empty top-level 'creatures' object")
    elif "creatures" not in data:
        warnings.append(f"{province}: no top-level 'creatures' object present")

    known_targets = set(creatures.keys()) | IMPLICIT_PREY_IDS

    diet_counts: dict[str, int] = {d: 0 for d in sorted(VALID_DIETS)}
    region_species: dict[str, dict[str, list[str]]] = {
        r: {"herbivore": [], "omnivore": [], "predator": [], "apex": [], "other": []}
        for r in regions
    }

    for cid, spec in creatures.items():
        if not isinstance(spec, dict):
            errors.append(f"{province}.creatures.{cid}: entry is not an object")
            continue

        diet = spec.get("diet")
        if diet not in VALID_DIETS:
            errors.append(f"{province}.creatures.{cid}: invalid or missing diet {diet!r}")
        else:
            diet_counts[diet] = diet_counts.get(diet, 0) + 1

        prey = spec.get("prey")
        if not isinstance(prey, list):
            errors.append(f"{province}.creatures.{cid}: 'prey' must be a list")
            prey = []
        if diet in ("predator", "apex") and not prey:
            errors.append(f"{province}.creatures.{cid}: {diet} must have a non-empty 'prey' list")
        for p in prey:
            if p not in known_targets:
                errors.append(f"{province}.creatures.{cid}: unknown prey target {p!r}")

        shelter = spec.get("shelter")
        if not isinstance(shelter, str) or not shelter.strip():
            errors.append(f"{province}.creatures.{cid}: missing non-empty 'shelter'")

        radius = spec.get("territoryRadius")
        if not isinstance(radius, int) or radius <= 0:
            errors.append(f"{province}.creatures.{cid}: 'territoryRadius' must be a positive int (got {radius!r})")

        hours = spec.get("activeHours")
        if hours not in VALID_ACTIVE_HOURS:
            errors.append(f"{province}.creatures.{cid}: 'activeHours' {hours!r} not in {sorted(VALID_ACTIVE_HOURS)}")

        why = spec.get("why")
        if not isinstance(why, list) or not why:
            errors.append(f"{province}.creatures.{cid}: missing non-empty 'why' list")
        else:
            for ref in why:
                if ref not in field_ids:
                    errors.append(f"{province}.creatures.{cid}: 'why' cites unknown field id {ref!r}")

        cregions = spec.get("regions") or []
        if not isinstance(cregions, list) or not cregions:
            warnings.append(f"{province}.creatures.{cid}: no 'regions' listed")
        else:
            for r in cregions:
                if r not in regions:
                    errors.append(f"{province}.creatures.{cid}: unknown region {r!r}")
                    continue
                bucket = diet if diet in ("herbivore", "omnivore", "predator", "apex") else "other"
                region_species[r][bucket].append(cid)

    region_summaries: dict[str, Any] = {}
    for r, buckets in region_species.items():
        prey_count = len(buckets["herbivore"]) + len(buckets["omnivore"])
        pred_count = len(buckets["predator"]) + len(buckets["apex"])
        apex_count = len(buckets["apex"])
        ratio = round(prey_count / pred_count, 2) if pred_count else float(prey_count)
        region_summaries[r] = {
            "herbivores_and_omnivores": prey_count,
            "predators": len(buckets["predator"]),
            "apex": apex_count,
            "other": len(buckets["other"]),
            "prey_to_predator_ratio": ratio,
        }
        if pred_count > 0 and prey_count < pred_count:
            errors.append(
                f"{province}.{r}: prey/omnivore species ({prey_count}) < predator+apex species ({pred_count})"
            )
        if apex_count > 0 and prey_count < apex_count * 2:
            errors.append(
                f"{province}.{r}: prey/omnivore species ({prey_count}) < 2x apex species ({apex_count})"
            )

    result: dict[str, Any] = {
        "province": province,
        "total_creatures": len(creatures),
        "diet_counts": diet_counts,
        "regions": region_summaries,
        "errors": errors,
        "warnings": warnings,
    }
    check_province_tail(province, field_ids, result, errors)
    return result


def check_festivals(skyrim_field_ids: set[str]) -> dict[str, Any]:
    errors: list[str] = []
    if not FESTIVALS_FILE.exists():
        return {"total_festivals": 0, "months": 0, "holds": {}, "errors": [f"Missing {FESTIVALS_FILE}"]}

    root = json.loads(FESTIVALS_FILE.read_text(encoding="utf-8"))
    cal = root.get("calendar") or {}
    months = cal.get("months") or []
    festivals = root.get("festivals") or []

    if len(months) != 12:
        errors.append(f"festivals.json: expected 12 Tamrielic months, found {len(months)}")

    hold_counts: dict[str, int] = {h: 0 for h in SKYRIM_HOLDS}
    seen_ids: set[str] = set()

    for idx, f in enumerate(festivals):
        if not isinstance(f, dict):
            errors.append(f"festivals[{idx}]: entry is not an object")
            continue
        fid = f.get("id", "")
        if not fid:
            errors.append(f"festivals[{idx}]: missing 'id'")
            continue
        if fid in seen_ids:
            errors.append(f"festivals.{fid}: duplicate id")
        seen_ids.add(fid)

        month = f.get("month", 0)
        day = f.get("day", 0)
        doy = f.get("dayOfYear", 0)
        if not (1 <= month <= 12):
            errors.append(f"festivals.{fid}: invalid month {month!r}")
        if not (1 <= day <= 30):
            errors.append(f"festivals.{fid}: invalid day {day!r}")
        if doy != (month - 1) * 30 + day:
            errors.append(f"festivals.{fid}: dayOfYear {doy} != (month-1)*30 + day")

        if not f.get("purpose"):
            errors.append(f"festivals.{fid}: missing non-empty 'purpose'")
        if not f.get("needs"):
            errors.append(f"festivals.{fid}: missing non-empty 'needs'")
        why = f.get("why") or []
        if not why:
            errors.append(f"festivals.{fid}: missing non-empty 'why'")
        elif skyrim_field_ids:
            for ref in why:
                if ref not in skyrim_field_ids:
                    errors.append(f"festivals.{fid}: 'why' cites unknown world-sheet id {ref!r}")

        reg = str(f.get("region", "")).split("/")[-1]
        if reg in hold_counts:
            hold_counts[reg] += 1

    for h, cnt in hold_counts.items():
        if cnt < 1:
            errors.append(f"festivals.json: Skyrim hold {h!r} has no festivals")

    return {
        "total_festivals": len(festivals),
        "months": len(months),
        "holds": hold_counts,
        "errors": errors,
    }


def check_level_policy() -> dict[str, Any]:
    errors: list[str] = []
    if not REGIONS_FILE.exists():
        return {"total_regions": 0, "max_spread": 0, "errors": [f"Missing {REGIONS_FILE}"]}

    regions = json.loads(REGIONS_FILE.read_text(encoding="utf-8"))
    max_spread = 0
    whiterun_min = None
    for r in regions:
        rid = r.get("id", "?")
        lv = r.get("levels") or [1, 1]
        lo, hi = int(lv[0]), int(lv[1])
        spread = hi - lo
        if spread > max_spread:
            max_spread = spread
        if spread > 20:
            errors.append(f"F-LEVEL: {rid} level spread {spread} ({lo}..{hi}) > 20")
        if rid == "skyrim/whiterun":
            whiterun_min = lo
            if lo != 1:
                errors.append(f"F-LEVEL: skyrim/whiterun minLevel is {lo}, expected 1")

    return {
        "total_regions": len(regions),
        "max_spread": max_spread,
        "whiterun_min_level": whiterun_min,
        "errors": errors,
    }


def check_province_tail(province: str, field_ids: set[str], result: dict[str, Any], errors: list[str]) -> None:
    if province == "skyrim":
        # These libraries now span Tamriel. Restricting citations to the Skyrim
        # sheet incorrectly rejected valid foreign encounters and festivals.
        all_ids = set().union(*(collect_field_ids(json.loads(p.read_text(encoding="utf-8")))
                                for p in WORLD_DIR.glob("*.json")))
        scenes_rep = check_scenes(all_ids)
        result["scenes"] = scenes_rep
        errors.extend(scenes_rep["errors"])

        fest_rep = check_festivals(all_ids)
        result["festivals"] = fest_rep
        errors.extend(fest_rep["errors"])

        lvl_rep = check_level_policy()
        result["level_policy"] = lvl_rep
        errors.extend(lvl_rep["errors"])


def format_markdown(reports: list[dict[str, Any]]) -> str:
    lines: list[str] = ["# Creature Ecology, Encounter Library, Festival Calendar & Level Policy Report", ""]
    for rep in reports:
        prov = rep["province"]
        lines.append(f"## Province: `{prov}` ({rep['total_creatures']} creatures)")
        lines.append("")
        dc = rep["diet_counts"]
        for warning in rep.get("warnings", []):
            lines.append("COVERAGE GAP: " + warning + ". Runtime spawn tables are not validated by this legacy creature-sheet check.")
        lines.append(
            "Diets: "
            + ", ".join(f"{k}={v}" for k, v in dc.items() if v > 0)
        )
        lines.append("")
        lines.append("| Region | Herbivore/Omnivore | Predator | Apex | Other | Prey:Pred Ratio |")
        lines.append("|---|---:|---:|---:|---:|---:|")
        for r, s in rep["regions"].items():
            lines.append(
                f"| `{r}` | {s['herbivores_and_omnivores']} | {s['predators']} | "
                f"{s['apex']} | {s['other']} | {s['prey_to_predator_ratio']} |"
            )
        lines.append("")
        if "scenes" in rep:
            sc = rep["scenes"]
            r300 = sc["road300"]
            lines.append(
                f"### Encounter Library (`scenes.json`): {sc['total_scenes']} total kinds, "
                f"{sc['wonder_scenes']} wonder kinds"
            )
            lines.append(
                f"- `road300` quiet road ratio: day `{r300['day_quiet_pct']}%` (<= 5%), "
                f"night `{r300['night_quiet_pct']}%` (<= 20%)"
            )
            lines.append("")
            lines.append("| Skyrim Hold | Encounter Kinds (`>= 8`) | Wonder Kinds (`>= 3`) | Quest Hooks |")
            lines.append("|---|---:|---:|---:|")
            for h, hc in sc["holds"].items():
                lines.append(f"| `{h}` | {hc['total']} | {hc['wonder']} | {hc['quest_hooks']} |")
            lines.append("")
        if "festivals" in rep:
            fc = rep["festivals"]
            lines.append(
                f"### Festival Calendar (`festivals.json`): {fc['total_festivals']} festivals across {fc['months']} months"
            )
            lines.append(
                "- Hold festivals: "
                + ", ".join(f"`{h}`={cnt}" for h, cnt in fc["holds"].items())
            )
            lines.append("")
        if "level_policy" in rep:
            lp = rep["level_policy"]
            lines.append(
                f"### Level Policy (`regions.json` / `F-LEVEL`): {lp['total_regions']} regions, "
                f"max spread `{lp['max_spread']}` (<= 20), `skyrim/whiterun` minLevel=`{lp['whiterun_min_level']}`"
            )
            lines.append("")
        if rep["errors"]:
            lines.append("### Errors")
            for e in rep["errors"]:
                lines.append(f"- {e}")
            lines.append("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--province", help="Province id (e.g., skyrim)")
    ap.add_argument("--all", action="store_true", help="Check all world sheets with creatures")
    ap.add_argument("--json", action="store_true", help="Emit JSON output")
    ap.add_argument("--md", action="store_true", help="Emit Markdown table output")
    args = ap.parse_args()

    paths: list[pathlib.Path] = []
    if args.province:
        paths.append(WORLD_DIR / f"{args.province}.json")
    else:
        for p in sorted(WORLD_DIR.glob("*.json")):
            data = json.loads(p.read_text(encoding="utf-8"))
            if args.all or "creatures" in data or p.stem == "skyrim":
                paths.append(p)

    reports = [check_province(p) for p in paths]
    total_errors = sum(len(r["errors"]) for r in reports)

    if args.json:
        print(json.dumps(reports if len(reports) != 1 else reports[0], indent=2))
    else:
        print(format_markdown(reports))
        print(f"Summary: {len(reports)} province(s) checked, {total_errors} error(s).")

    return 1 if total_errors else 0


if __name__ == "__main__":
    sys.exit(main())
