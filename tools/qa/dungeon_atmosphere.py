#!/usr/bin/env python3
"""Build a repeatable actual-dungeon capture matrix from the in-game air-survey.

This selects existing geometry, never creates demonstration rooms. Frames require visual review.
Run live_world.py actions on the emitted file after reviewing the manifest.
"""
import argparse
import json
from pathlib import Path


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("survey", type=Path)
    ap.add_argument("--out", type=Path, default=Path("dist/weather-review/dungeons/matrix"))
    args = ap.parse_args()
    rows = json.loads(args.survey.read_text())
    selected = []
    # Stable minimum controls, complemented by surveyed genuine set pieces.
    for place, view, tag in [("embershard_mine", "hall", "mine_corridor"),
                             ("bleak_falls_barrow", "hall", "barrow_corridor"),
                             ("embershard_mine", "descent", "mine_stairs"),
                             ("pinemoon_cave", "entrance", "entrance_outside"),
                             ("pinemoon_cave", "mouth", "entrance_inside"),
                             ("hobs_fall_cave", "boss", "cold_cave")]:
        selected.append({"place": place, "view": view, "tag": tag, "review": "pending"})
    for feature, tag in [("setpiece:surface_skylight", "real_skylight"),
                         ("setpiece:grand_waterfall", "waterfall"),
                         ("camp:grand", "camp")]:
        row = next((r for r in rows if feature in r.get("features", [])), None)
        if row:
            selected.append({"place": row["place"], "view": "room " + str(row["room"]), "tag": tag,
                             "evidence": row, "review": "pending"})
    actions = ["command time set 6000"]
    for entry in selected:
        place, view, tag = entry["place"], entry["view"], "matrix_" + entry["tag"]
        evidence = entry.get("evidence") or next((r for r in rows if r["place"] == place), None)
        if evidence:
            b = evidence["box"]
            actions.append(f"front {(b[0]+b[3])//2} {(b[2]+b[5])//2} 2200 clear")
        actions += [f"command execute as @p at @p run skycraft-dungeon-qa view {place} {view}",
                    "settle 1200", "command effect clear @p minecraft:night_vision", "settle 1200", "capture " + tag]
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "capture-actions.txt").write_text("\n".join(actions) + "\n")
    (args.out / "manifest.json").write_text(json.dumps({"source": str(args.survey), "scenes": selected,
        "acceptance": "All frames pending visual review. No actors/props added by this matrix.",
        "additionalRequired": ["campfire smoke", "enemy spell and staff packets", "glowing eyes",
                               "shaders off", "house roof exclusion", "day/night opening adaptation"]}, indent=2))
    print(f"{len(selected)} actual dungeon views -> {args.out}")


if __name__ == "__main__":
    main()
