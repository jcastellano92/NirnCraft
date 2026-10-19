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
    # per element: the CPU bench (lab-groups: the representation alone) and the far field itself (far-groups:
    # atlas_core through atlas_render, what the game draws)
    renderers = [("", "lab-groups", "the bench's groups", ["native/tools/atlas_lab.cpp", "native/tools/atlas_lab_scene.hpp"]),
                 ("-far", "far-groups", "the far field's groups",
                  ["native/src", "native/shaders", "native/include", "native/tools/atlas_render.cpp", "native/tools/atlas_export.hpp"])]
    for e in els:
        for suffix, renderer, what, ins in renderers:
            out.append({"_file": "(generated elements)", "id": f"element-{e['slug']}{suffix}",
                        "title": f"Element {e['slug']} ({e['kind']}): the reference vs {what}, 32 m to 64 km, fly-ins",
                        "tags": ["suite:elements", "feature:farfield", f"element:{e['kind']}", f"renderer:{renderer}"], "tier": "check",
                        "group": "-", "variants": ["check"],
                        "run": ["py", "-3", "tools/harness/element_lab.py", e["slug"], "--renderer", renderer,
                                "--out", f".work/harness/elements/{renderer}"],
                        "inputs": ["tools/harness/element_lab.py", "native/tools/atlas_truth.cpp"] + ins,
                        "metrics": METRICS, "timeout": 900, "source": "tools/harness/element_lab.py"})
    # suite:elements-lit (opt-in): the same elements against the realistic reference (the shader pack's light ray traced
    # on the real mesh, docs/ATLAS-LOD.md "A realistic reference") at day, dusk and night: the bench and the far field
    # sampling cards baked in that light (the representation under it), and the far field's own lighting (far-lit)
    lit = renderers + [("-farlit", "far-lit", "the far field's own lighting", renderers[1][3])]
    for e in els:
        for tod, ticks in (("day", 6000), ("dusk", 12500), ("night", 18000)):
            for suffix, renderer, what, ins in lit:
                out.append({"_file": "(generated elements)", "id": f"element-{e['slug']}{suffix}-lit-{tod}",
                            "title": f"Element {e['slug']} ({e['kind']}) under realistic light at {tod}: the reference vs {what}",
                            "tags": ["suite:elements-lit", "feature:farfield", "feature:lighting", f"element:{e['kind']}",
                                     f"renderer:{renderer}", f"time:{tod}"], "tier": "check", "group": "-", "variants": ["check"],
                            "run": ["py", "-3", "tools/harness/element_lab.py", e["slug"], "--renderer", renderer, "--truth",
                                    "realistic", "--time", str(ticks), "--out", f".work/harness/elements-lit/{renderer}"],
                            "inputs": ["tools/harness/element_lab.py", "native/tools/atlas_truth.cpp", "native/tools/pack_look.hpp"] + ins,
                            "metrics": METRICS, "timeout": 1200, "source": "tools/harness/element_lab.py"})
    return out


METRICS = {"worst_dE": r'"worst": \{"dist": \d+, "dE": ([\d.]+)',
           "worst_iou": r'"worst": \{"dist": \d+, "dE": [\d.]+, "iou": ([\d.]+)\}',
           "pop_fast": r'"flyin_fast": \{"pop": ([\d.]+)',
           "pop_slow": r'"flyin_slow": \{"pop": ([\d.]+)'}
