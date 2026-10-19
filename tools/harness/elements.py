"""Element tests (suite:elements): tier 1 of renderer verification, offline, seconds per element.

Every element found in the exports (element_lab.elements(): each export's centre chunk and its biggest tree, more
kinds as they are added) gets a check scene that renders it with the exact reference (intended mode) and with our
renderer from 8 m to 64 km and along two fly-ins (element_lab.py), and records per distance silhouette IoU and dE and
the fly-ins' pop. Elements come from the export directory as it is: a new export adds its elements automatically.

Order (owner, 2026-10-19): elements offline-to-offline (this) -> the same elements against Minecraft's own rendering
(the pinned suite, live, final) -> full environments offline -> full environments live last.
"""


def scenes():
    try:
        import element_lab
        els = element_lab.elements()
    except Exception:  # noqa: BLE001  no exports yet (a fresh machine): no element scenes, never a broken catalog
        return []
    out = []
    for e in els:
        out.append({"_file": "(generated elements)", "id": f"element-{e['slug']}",
                    "title": f"Element {e['slug']} ({e['kind']}): the reference vs our renderer, 8 m to 64 km, fly-ins",
                    "tags": ["suite:elements", "feature:farfield", f"element:{e['kind']}"], "tier": "check",
                    "group": "-", "variants": ["check"],
                    "run": ["py", "-3", "tools/harness/element_lab.py", e["slug"], "--out", ".work/harness/elements"],
                    "inputs": ["tools/harness/element_lab.py", "native/tools/atlas_truth.cpp", "native/tools/atlas_lab.cpp",
                               "native/tools/atlas_lab_scene.hpp"],
                    "metrics": {"worst_dE": r'"worst": \{"dist": \d+, "dE": ([\d.]+)',
                                "worst_iou": r'"iou": ([\d.]+)\}\}?$',
                                "pop_fast": r'"flyin_fast": \{"pop": ([\d.]+)',
                                "pop_slow": r'"flyin_slow": \{"pop": ([\d.]+)'},
                    "timeout": 900, "source": "tools/harness/element_lab.py"})
    return out
