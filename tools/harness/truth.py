"""The ground-truth suite (suite:truth): one subject per situation the far field must get right, each a fog-free
vanilla capture of the live game and offline renders of the same view from the real blocks.

Per subject:
* `truth-<slug>` (live, vanilla): goes to the subject, settles with the world frozen, captures without fog or haze,
  and exports its real blocks (a box around the camera with light, the camera's view to 1 km with light) plus the
  block library and Minecraft's own mesh data (skipped when they are current).
* `truth-<slug>-near` / `truth-<slug>-view` (lab, atlas_truth): Minecraft's own mesh traced offline from those blocks,
  in the capture's own camera (camera_from), compared with the capture: dE by distance band, silhouette, missing and
  flat patches. Near is the box (what the near field draws), view is everything to 1 km (what the far field must draw).

Subjects cover the situations the owner listed (2026-10-19): day / dusk / night lighting, lit towns, caves (mouths near
and far, the inside of large ones), custom dungeons near and far, mountains and long valleys, long ocean views,
forests, our own blocks and models in towns. Add a subject here, never by hand in a scene file.
"""

EXPORT_DIR = "@game/nirncraft-atlaslab/"

# slug, title, pose, time, tags, box (radius, down, up), underground
# Minecraft yaw: 0 faces south (+z), 90 west (-x), 180 north (-z), -90 east (+x). A pose offset from its place looks
# back at it (yaw from the offset), unless the subject is what lies beyond (LOOKS_AWAY; checked in the self-test).
LOOKS_AWAY = {"high-hrothgar-mountains", "falkreath-forest", "marsh-morthal", "swamp-leyawiin"}
SUBJECTS = [
    ("whiterun-day", "Whiterun from the plains 120 m south at noon: walls, the keep, far mountains",
     {"place": "whiterun", "height": 4, "yaw": 180, "pitch": 3, "offset": [0, 120]}, 6000, ["place:whiterun", "town:whiterun"], (48, 12, 40), False),
    ("whiterun-night", "Whiterun at night: lanterns, windows, the dark plains beyond",
     {"place": "whiterun", "height": 4, "yaw": 180, "pitch": 3, "offset": [0, 120]}, 18000, ["place:whiterun", "town:whiterun", "time:night",
                                                                       "feature:lights"], (48, 12, 40), False),
    ("falkreath-dusk", "Falkreath at dusk: long shadows through the pines",
     {"place": "falkreath", "height": 3, "yaw": 0, "pitch": 10}, 12500, ["place:falkreath", "time:dusk",
                                                                         "feature:shadows"], (48, 12, 40), False),
    ("riften-day", "Riften and Lake Honrich at noon: water, docks, the far shore",
     {"place": "riften", "height": 4, "yaw": 90, "pitch": 4}, 6000, ["place:riften", "feature:water"], (48, 16, 40), False),
    ("solitude-sea", "Solitude's coast looking out to sea: a long ocean view to the horizon",
     {"place": "solitude", "height": 24, "yaw": 180, "pitch": 4}, 6000, ["place:solitude", "feature:ocean",
                                                                         "feature:horizon"], (48, 40, 24), False),
    ("dawnstar-sea", "Dawnstar's harbour and the Sea of Ghosts: shore, ice, open water",
     {"place": "dawnstar", "height": 8, "yaw": 180, "pitch": 3}, 6000, ["place:dawnstar", "feature:ocean"], (48, 24, 32), False),
    ("high-hrothgar-mountains", "Up the Throat of the World: massive mountains and the valleys below",
     {"place": "high_hrothgar", "height": 30, "yaw": 0, "pitch": 10, "offset": [0, 80]}, 6000, ["place:high_hrothgar", "feature:mountains",
                                                                            "feature:horizon"], (48, 24, 40), False),
    ("falkreath-forest", "Deep in the Falkreath pine forest: trunks, crowns, light through the canopy",
     {"place": "falkreath", "height": 3, "yaw": 135, "pitch": 4, "offset": [120, 120]}, 6000, ["place:falkreath",
                                                                                            "feature:forest"], (40, 12, 48), False),
    ("cave-mouth-near", "A cave mouth up close: the opening, the dark inside, the slope around it",
     {"place": "greywater_grotto", "height": 3, "yaw": 180, "pitch": 4, "offset": [0, 18]}, 6000,
     ["feature:cave", "place:greywater_grotto"], (32, 24, 24), False),
    ("cave-mouth-far", "The same cave mouth from 200 m: does the far field keep the opening",
     {"place": "greywater_grotto", "height": 20, "yaw": 180, "pitch": 2, "offset": [0, 200]}, 6000,
     ["feature:cave", "place:greywater_grotto"], (32, 24, 24), False),
    ("dungeon-mouth", "Bleak Falls Barrow's mouth by day: a custom dungeon's entrance and its ruins",
     {"dungeon": "bleak_falls_barrow", "view": "mouth"}, 6000, ["feature:dungeon", "place:bleak_falls_barrow"], (40, 16, 32), False),
    ("volcano-day", "Red Mountain on the approach at noon: ash slopes, the cone, lava channels",
     {"place": "red_mountain", "shot": "approach"}, 6000, ["place:red_mountain", "feature:volcano", "feature:mountains"],
     (48, 24, 48), False),
    ("volcano-night", "Red Mountain's plume at night: lava glow on the ash, the dark slopes",
     {"visit": [6100, -500, 50, -84, -8]}, 18000, ["place:red_mountain", "feature:volcano", "time:night", "feature:lights"],
     (48, 24, 48), False),
    ("marsh-morthal", "Morthal's marsh: shallow water, reeds, dead trees",
     {"place": "morthal", "height": 3, "yaw": 0, "pitch": 3, "offset": [0, 60]}, 6000,
     ["place:morthal", "feature:marsh", "feature:water"], (48, 16, 32), False),
    ("reach-markarth", "Markarth in the Reach: red rock cliffs, the Dwemer city, scrub",
     {"place": "markarth", "height": 6, "yaw": -90, "pitch": 2, "offset": [-120, 0]}, 6000,
     ["place:markarth", "feature:cliffs"], (48, 24, 48), False),
    ("snow-winterhold", "Winterhold in snow above the Sea of Ghosts: snow cover, ice, the open sea",
     {"place": "winterhold", "height": 4, "yaw": 180, "pitch": 3}, 6000,
     ["place:winterhold", "feature:snow", "feature:ocean"], (48, 24, 32), False),
    ("water-vivec", "Vivec on the water: cantons, open sea, distant shore (Morrowind)",
     {"place": "vivec", "height": 10, "yaw": 180, "pitch": 2, "offset": [0, 160]}, 6000,
     ["place:vivec", "feature:ocean", "feature:city"], (48, 32, 48), False),
    ("mountains-bruma", "Bruma under the Jerall Mountains: snowy peaks, pines, the city walls (Cyrodiil)",
     {"place": "bruma", "height": 4, "yaw": 180, "pitch": 4, "offset": [0, 140]}, 6000,
     ["place:bruma", "feature:mountains", "feature:snow"], (48, 16, 48), False),
    ("swamp-leyawiin", "Leyawiin's Blackwood: swamp forest, broad-leaf trees, water (Cyrodiil)",
     {"place": "leyawiin", "height": 3, "yaw": 90, "pitch": 3, "offset": [-100, 0]}, 6000,
     ["place:leyawiin", "feature:forest", "feature:water"], (48, 16, 40), False),
    ("cave-hall-ravenscar", "Inside Ravenscar Hollow: a cave's chamber, no sky, torches and dark rock",
     {"dungeon": "ravenscar_hollow", "view": "hall"}, 6000, ["feature:cave", "feature:interior", "place:ravenscar_hollow"],
     (40, 20, 24), True),
    ("mine-hall-embershard", "Inside Embershard Mine: a mine's hall, timber supports, lanterns",
     {"dungeon": "embershard_mine", "view": "hall"}, 6000, ["feature:cave", "feature:interior", "place:embershard_mine"],
     (40, 20, 24), True),
    ("dungeon-hall", "Inside Bleak Falls Barrow: a custom dungeon's hall, torch-lit, no sky",
     {"dungeon": "bleak_falls_barrow", "view": "hall"}, 6000, ["feature:dungeon", "feature:interior",
                                                             "place:bleak_falls_barrow"], (32, 16, 16), True),
]


def _export(slug, title, pose, time, tags, box, underground):
    r, down, up = box
    setup = ["fog-off on", "truth-quiet on", "try tick freeze", "atlaslab blocks", "atlaslab quads",
             f"atlaslab here {slug} {r} {down} {up}", f"atlaslab here {slug} {r} {down} {up} light"]
    cleanup = ["try tick unfreeze", "truth-quiet off", f"atlaslab view {slug}-view 1024 light", "fog-off off"]
    if underground:  # no view far out underground: the box is the whole truth there
        cleanup = ["try tick unfreeze", "truth-quiet off", "fog-off off"]
    return {"_file": "(generated truth)", "id": f"truth-{slug}", "title": f"Ground truth capture: {title}",
            "tags": ["suite:truth", "feature:farfield"] + tags, "tier": "live", "group": "live", "variants": ["vanilla"],
            "weather": "off", "time": time, "settle": "static", "pose": pose, "wait": 100,
            "setup": setup, "cleanup": cleanup, "measure": 0, "checks": {}, "capture": "world", "timeout": 1800,
            "source": "tools/harness/truth.py", "vet_pose": title,
            "note": f"exports {slug}(.alv, .alv2, .json){'' if underground else f', {slug}-view(.als2, .json)'} beside the "
                    "block library and Minecraft's mesh data (quads.bin, sprites.bin); the capture is fog-free vanilla"}


def _element_export(slug, title, pose, time, tags, box, underground):
    """The element tier's inputs from one subject (element_lab.elements() finds its chunks, trees, water, lights,
    buildings and custom blocks): the block library, Minecraft's mesh data and the box around the pose, without the
    1 km view (150-200 MB the element tier never reads) and without a pose review (no capture is compared)."""
    r, down, up = box
    return {"_file": "(generated truth)", "id": f"element-export-{slug}", "title": f"Element tier inputs: {title}",
            "tags": ["suite:element-exports", "feature:farfield"] + tags, "tier": "live", "group": "live",
            "variants": ["vanilla"], "weather": "off", "time": time, "settle": "static", "pose": pose, "wait": 60,
            "setup": ["fog-off on", "truth-quiet on", "try tick freeze", "atlaslab blocks", "atlaslab quads",
                      f"atlaslab here {slug} {r} {down} {up}", f"atlaslab here {slug} {r} {down} {up} light"],
            "cleanup": ["try tick unfreeze", "truth-quiet off", "fog-off off"], "measure": 0, "checks": {},
            "capture": "world", "timeout": 1200, "source": "tools/harness/truth.py",
            "note": f"exports {slug}(.alv, .alv2, .json) beside blocks.bin, quads.bin and sprites.bin (rewritten only when "
                    "AtlasLabExport.LIBRARY_VERSION or the content changed)"}


def _lab(slug, title, tags, part, volume):
    return {"_file": "(generated truth)", "id": f"truth-{slug}-{part}",
            "title": f"Ground truth {part}: {title} (Minecraft's own mesh offline vs the live capture)",
            "tags": ["suite:truth", "feature:farfield", f"truth:{part}"] + tags, "tier": "lab", "group": "lab",
            "variants": ["lab"],
            "lab": {"tool": "atlas_truth", "quads": EXPORT_DIR + "quads.bin", "sprites": EXPORT_DIR + "sprites.bin",
                    "volume": EXPORT_DIR + volume},
            "camera_from": {"scene": f"truth-{slug}", "variant": "vanilla"}, "after": [f"truth-{slug}"],
            "wait": 0, "setup": [], "cleanup": [], "measure": 0, "checks": {}, "capture": "world",
            "source": "native/tools/atlas_truth.cpp"}


def staged(slug, title, states, time=6000, tags=(), spacing=2, per_row=16):
    """Block states laid out on the sky stage (per_row a row, `spacing` apart, on a stone floor), seen from above at
    about 24 m, their exact box exported: content the reference must reproduce (our models, lid states, lights)."""
    import catalog
    sx, sy, sz = catalog.STAGE
    w = per_row * spacing
    x0, z0, y = sx - w // 2, sz - w // 2, sy
    rows = (len(states) + per_row - 1) // per_row
    x1, z1 = x0 + w + 1, z0 + max(rows * spacing, 4) + 1
    setup = ["fog-off on", "truth-quiet on", f"try fill {x0 - 2} {y} {z0 - 2} {x1} {y} {z1} minecraft:smooth_stone",
             f"try fill {x0 - 2} {y + 1} {z0 - 2} {x1} {y + 8} {z1} minecraft:air"]
    setup += [f"try setblock {x0 + (i % per_row) * spacing} {y + 1} {z0 + (i // per_row) * spacing} {st}"
              for i, st in enumerate(states)]
    setup += ["wait 40", "atlaslab blocks", "atlaslab quads",
              f"atlaslab volume {slug} {x0 - 2} {y} {z0 - 2} {x1} {y + 8} {z1}",
              f"atlaslab volume {slug} {x0 - 2} {y} {z0 - 2} {x1} {y + 8} {z1} light"]
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    export = {"_file": "(generated truth)", "id": f"truth-{slug}", "title": f"Ground truth capture: {title}",
              "tags": ["suite:truth", "feature:farfield"] + list(tags), "tier": "live", "group": "live",
              "variants": ["vanilla"], "weather": "off", "time": time, "settle": "static",
              "pose": {"camera": [cx, y + 22, cz - 26, cx, y + 1, cz]}, "seed": 1234, "wait": 100, "setup": setup,
              "cleanup": ["truth-quiet off", "fog-off off"], "measure": 0, "checks": {}, "capture": "world",
              "timeout": 1800, "source": "tools/harness/truth.py", "vet_pose": title}
    return [export, _lab(slug, title, list(tags), "near", f"{slug}.alv2")]


def _container_states():
    """Every NirnCraft container kind, every style its blockstates define, closed / ajar / open (shelves by book count)."""
    import json as _json
    from pathlib import Path as _P
    root = _P(__file__).resolve().parents[2] / "mod/src/main/resources/assets/nirncraft/blockstates"
    out = []
    for kind in ("loot_chest", "grand_chest", "strongbox", "loot_barrel", "loot_sack", "loot_urn", "loot_bookshelf"):
        f = root / f"{kind}.json"
        if not f.exists():
            continue
        variants = _json.loads(f.read_text(encoding="utf-8")).get("variants", {})
        keys = [dict(p.split("=") for p in k.split(",") if "=" in p) for k in variants]
        styles = sorted({k.get("style") for k in keys if k.get("style")}) or [None]
        if any("books" in k for k in keys):
            for b in sorted({k["books"] for k in keys if "books" in k}, key=int):
                props = ["facing=north", f"books={b}"] + ([f"style={styles[0]}"] if styles[0] else [])
                out.append(f"nirncraft:{kind}[{','.join(props)}]")
            continue
        for st in styles:
            for lid in ("closed", "ajar", "open"):
                props = ["facing=north", f"lid={lid}"] + ([f"style={st}"] if st else [])
                out.append(f"nirncraft:{kind}[{','.join(props)}]")
    return out


LIGHTS = ["minecraft:campfire[lit=true]", "minecraft:soul_campfire[lit=true]", "minecraft:torch", "minecraft:soul_torch",
          "minecraft:lantern", "minecraft:soul_lantern", "minecraft:sea_lantern", "minecraft:glowstone",
          "minecraft:shroomlight", "minecraft:jack_o_lantern", "minecraft:redstone_lamp[lit=true]", "minecraft:magma_block",
          "minecraft:end_rod", "minecraft:candle[candles=4,lit=true]", "nirncraft:road_lantern", "nirncraft:road_soul_lantern",
          "nirncraft:trail_torch", "nirncraft:big_fire", "nirncraft:rubble_fire"]


def custom_blocks():
    """Every NirnCraft block in a 16 x 16 grid (2 m apart) on the sky stage, seen from above at 24 m, its exact box
    exported: the exact reference must reproduce our own models and textures, not only vanilla's."""
    import catalog
    sx, sy, sz = catalog.STAGE
    blocks = sorted(catalog._lang_ids("block"))
    x0, z0, y = sx - 16, sz - 16, sy
    setup = ["fog-off on", "truth-quiet on", f"try fill {x0 - 2} {y} {z0 - 2} {x0 + 33} {y} {z0 + 33} minecraft:smooth_stone",
             f"try fill {x0 - 2} {y + 1} {z0 - 2} {x0 + 33} {y + 8} {z0 + 33} minecraft:air"]
    setup += [f"try setblock {x0 + (i % 16) * 2} {y + 1} {z0 + (i // 16) * 2} nirncraft:{b}" for i, b in enumerate(blocks[:256])]
    setup += ["wait 40", "atlaslab blocks", "atlaslab quads",
              f"atlaslab volume custom-blocks {x0 - 2} {y} {z0 - 2} {x0 + 33} {y + 8} {z0 + 33}",
              f"atlaslab volume custom-blocks {x0 - 2} {y} {z0 - 2} {x0 + 33} {y + 8} {z0 + 33} light"]
    cx, cz = x0 + 15, z0 + 15
    export = {"_file": "(generated truth)", "id": "truth-custom-blocks",
              "title": f"Ground truth capture: every NirnCraft block ({min(len(blocks), 256)}) on the sky stage",
              "tags": ["suite:truth", "feature:farfield", "feature:custom-blocks"], "tier": "live", "group": "live",
              "variants": ["vanilla"], "weather": "off", "time": 6000, "settle": "static",
              "pose": {"camera": [cx, y + 22, cz - 26, cx, y + 1, cz]}, "seed": 1234, "wait": 100, "setup": setup,
              "cleanup": ["truth-quiet off", "fog-off off"], "measure": 0, "checks": {}, "capture": "world", "timeout": 1800,
              "source": "tools/harness/truth.py"}
    return [export, _lab("custom-blocks", "every NirnCraft block on the sky stage", ["feature:custom-blocks"], "near",
                         "custom-blocks.alv2")]


def scenes():
    out = custom_blocks()
    out += staged("containers", "every NirnCraft container: each style, lid closed, ajar and open; shelves by book count",
                  _container_states(), tags=["feature:custom-blocks", "feature:containers"], spacing=3)
    out += staged("lights-night", "light blocks at night: campfires, torches, lanterns, NirnCraft fires and lanterns",
                  LIGHTS, time=18000, tags=["feature:lights", "time:night"], spacing=4, per_row=5)
    for slug, title, pose, time, tags, box, underground in SUBJECTS:
        out.append(_export(slug, title, pose, time, tags, box, underground))
        out.append(_element_export(slug, title, pose, time, tags, box, underground))
        out.append(_lab(slug, title, tags, "near", f"{slug}.alv2"))
        if not underground:
            out.append(_lab(slug, title, tags, "view", f"{slug}-view.als2"))
    return out
