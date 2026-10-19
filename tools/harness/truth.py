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
SUBJECTS = [
    ("whiterun-day", "Whiterun from its gate road at noon: walls, plains, far mountains",
     {"place": "whiterun", "height": 3, "yaw": 0, "pitch": 6}, 6000, ["place:whiterun", "town:whiterun"], (48, 12, 40), False),
    ("whiterun-night", "Whiterun at night: lanterns, windows, the dark plains beyond",
     {"place": "whiterun", "height": 3, "yaw": 0, "pitch": 6}, 18000, ["place:whiterun", "town:whiterun", "time:night",
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
     {"place": "high_hrothgar", "height": 6, "yaw": 0, "pitch": -2}, 6000, ["place:high_hrothgar", "feature:mountains",
                                                                            "feature:horizon"], (48, 24, 40), False),
    ("falkreath-forest", "Deep in the Falkreath pine forest: trunks, crowns, light through the canopy",
     {"place": "falkreath", "height": 3, "yaw": 135, "pitch": 4, "offset": [120, 120]}, 6000, ["place:falkreath",
                                                                                            "feature:forest"], (40, 12, 48), False),
    ("cave-mouth-near", "A cave mouth up close: the opening, the dark inside, the slope around it",
     {"place": "greywater_grotto", "height": 3, "yaw": 0, "pitch": 4, "offset": [0, 18]}, 6000,
     ["feature:cave", "place:greywater_grotto"], (32, 24, 24), False),
    ("cave-mouth-far", "The same cave mouth from 200 m: does the far field keep the opening",
     {"place": "greywater_grotto", "height": 20, "yaw": 0, "pitch": 2, "offset": [0, 200]}, 6000,
     ["feature:cave", "place:greywater_grotto"], (32, 24, 24), False),
    ("dungeon-mouth", "Bleak Falls Barrow's mouth by day: a custom dungeon's entrance and its ruins",
     {"dungeon": "bleak_falls_barrow", "view": "mouth"}, 6000, ["feature:dungeon", "place:bleak_falls_barrow"], (40, 16, 32), False),
    ("dungeon-hall", "Inside Bleak Falls Barrow: a custom dungeon's hall, torch-lit, no sky",
     {"dungeon": "bleak_falls_barrow", "view": "hall"}, 6000, ["feature:dungeon", "feature:interior",
                                                             "place:bleak_falls_barrow"], (32, 16, 16), True),
]


def _export(slug, title, pose, time, tags, box, underground):
    r, down, up = box
    setup = ["fog-off on", "atlaslab blocks", "atlaslab quads",
             f"atlaslab here {slug} {r} {down} {up}", f"atlaslab here {slug} {r} {down} {up} light"]
    cleanup = [f"atlaslab view {slug}-view 1024 light", "fog-off off"]
    if underground:  # no view far out underground: the box is the whole truth there
        cleanup = ["fog-off off"]
    return {"_file": "(generated truth)", "id": f"truth-{slug}", "title": f"Ground truth capture: {title}",
            "tags": ["suite:truth", "feature:farfield"] + tags, "tier": "live", "group": "live", "variants": ["vanilla"],
            "weather": "off", "time": time, "settle": "static", "pose": pose, "wait": 100,
            "setup": setup, "cleanup": cleanup, "measure": 0, "checks": {}, "capture": "world", "timeout": 1800,
            "source": "tools/harness/truth.py",
            "note": f"exports {slug}(.alv, .alv2, .json){'' if underground else f', {slug}-view(.als2, .json)'} beside the "
                    "block library and Minecraft's mesh data (quads.bin, sprites.bin); the capture is fog-free vanilla"}


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


def custom_blocks():
    """Every NirnCraft block in a 16 x 16 grid (2 m apart) on the sky stage, seen from above at 24 m, its exact box
    exported: the exact reference must reproduce our own models and textures, not only vanilla's."""
    import catalog
    sx, sy, sz = catalog.STAGE
    blocks = sorted(catalog._lang_ids("block"))
    x0, z0, y = sx - 16, sz - 16, sy
    setup = ["fog-off on", f"try fill {x0 - 2} {y} {z0 - 2} {x0 + 33} {y} {z0 + 33} minecraft:smooth_stone",
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
              "cleanup": ["fog-off off"], "measure": 0, "checks": {}, "capture": "world", "timeout": 1800,
              "source": "tools/harness/truth.py"}
    return [export, _lab("custom-blocks", "every NirnCraft block on the sky stage", ["feature:custom-blocks"], "near",
                         "custom-blocks.alv2")]


def scenes():
    out = custom_blocks()
    for slug, title, pose, time, tags, box, underground in SUBJECTS:
        out.append(_export(slug, title, pose, time, tags, box, underground))
        out.append(_lab(slug, title, tags, "near", f"{slug}.alv2"))
        if not underground:
            out.append(_lab(slug, title, tags, "view", f"{slug}-view.als2"))
    return out
