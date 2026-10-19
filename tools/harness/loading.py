"""Loading scenes (suite:loading): how fast the far field shows a place the camera has just arrived at, and how it
resolves on the way to the settled view (owner, 2026-10-19: "loading in the world is super slow, we want fast,
instead I'm seeing pop-in").

Each scene settles at a start place, marks the far field's timeline (far-timeline start), teleports across the map,
then captures every second for 16 s and once more when the view has settled; the worker's pass timeline (what each pass
spent on tiles, edits, sections, builds, overlays) is dumped beside the captures. results.pop_metrics scores the
strip: each second's difference from the settled view, when it came within reach of it, and the biggest jump between
seconds (pop-in).
"""

# (scene slug, title, from place, to place, the arrival pose's yaw / pitch)
TRIPS = [
    ("whiterun-riften", "Whiterun to Riften: a fresh place across the map", "whiterun", "riften", 90, 4),
    ("falkreath-solitude", "Falkreath to Solitude: south-west to the far north coast", "falkreath", "solitude", 180, 4),
    ("whiterun-hop", "A short hop out of Whiterun: most of the far field is already there", "whiterun", "whiterun", 0, 4),
]
FRAMES, EVERY = 16, 20  # 16 captures a second apart (20 ticks)


def scenes():
    import catalog
    out = []
    for slug, title, start, to, yaw, pitch in TRIPS:
        arrive = {"place": to, "height": 4, "yaw": yaw, "pitch": pitch}
        if start == to:
            arrive["offset"] = [0, -3000]  # 3 km north of the start
        move = catalog.pose_actions({"id": f"load-{slug}", "pose": arrive, "time": 6000, "weather": "clear", "tags": []})
        out.append({"_file": "(generated loading)", "id": f"load-{slug}", "title": f"Loading: {title}",
                    "tags": ["suite:loading", "feature:farfield", f"place:{to}"], "tier": "live", "group": "live",
                    "variants": ["vanilla"], "weather": "clear", "time": 6000,
                    "pose": {"place": start, "height": 4, "yaw": yaw, "pitch": pitch},
                    "setup": ["far-timeline start"] + move,
                    "frames": [FRAMES, EVERY], "loading": True, "measure": 0, "checks": {}, "capture": "world",
                    "timeout": 1800, "source": "tools/harness/loading.py",
                    "note": "writes the strip (1..16 s, settled) and <tag>-timeline.json (the far field's passes)"})
    return out
