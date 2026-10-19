"""Texture audit for every NirnCraft texture (assets/nirncraft/textures/**.png): size a multiple of 16 (or the vanilla
GUI / painting exceptions), animation strips consistent with their .mcmeta, not empty (fully transparent), not a flat
placeholder (one colour), no magenta missing-texture pixels, readable PNG; labPBR _n/_s maps the same size as their
base. Prints `problems N` and the first offenders; details in .work/harness/audits/textures.json.

    py -3 tools/harness/audits/texture_audit.py
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
TEX = ROOT / "mod/src/main/resources/assets/nirncraft/textures"
OUT = ROOT / ".work/harness/audits/textures.json"
FREE_SIZE = ("gui", "painting", "font", "map", "environment", "misc", "colormap", "effect", "mob_effect", "title")


def audit(f):
    rel = f.relative_to(TEX).as_posix()
    probs = []
    try:
        img = Image.open(f)
        img.load()
    except OSError as e:
        return [f"unreadable: {e}"]
    w, h = img.size
    meta = f.with_name(f.name + ".mcmeta")
    free = rel.split("/")[0] in FREE_SIZE
    if meta.exists():
        try:
            anim = json.loads(meta.read_text(encoding="utf-8")).get("animation")
        except ValueError:
            anim, probs = None, probs + ["unreadable .mcmeta"]
        if anim is not None:
            fw = anim.get("width", w)
            fh = anim.get("height", fw if "width" in anim or "height" not in anim else anim["height"])
            if h % fh or w % fw:
                probs.append(f"animation frames {fw}x{fh} do not tile {w}x{h}")
    elif not free and (w % 16 or h % 16) and not (w in (8, 4) and h in (8, 4)):
        probs.append(f"size {w}x{h} not a multiple of 16")
    elif not free and h != w and not meta.exists() and h % w and w % h:
        probs.append(f"odd aspect {w}x{h} without animation")
    a = np.asarray(img.convert("RGBA"))
    if rel.startswith("models/armor/"):  # the 1.20.1 armour location; 26.3 reads textures/entity/equipment
        probs.append("legacy armour layer (26.3 uses textures/entity/equipment/<layer>/<material>.png): unused?")
    elif a[..., 3].max() == 0 and not rel.startswith(("environment/", "entity/society/npc/")):  # filled at runtime / "none" layers  # environment/: filled by the renderer
        probs.append("fully transparent")
    else:
        vis = a[a[..., 3] > 0][:, :3]
        if len(vis) > 16 and (vis.max(axis=0) - vis.min(axis=0)).max() == 0 and not free                 and not rel.startswith("particle/")                 and not f.stem.endswith(("_n", "_s")):  # tinted particles / uniform PBR maps are fine flat
            probs.append("a single flat colour (placeholder?)")
        magenta = ((vis[:, 0] > 240) & (vis[:, 1] < 16) & (vis[:, 2] > 240)).mean() if len(vis) else 0
        if magenta > 0.05:
            probs.append(f"{magenta:.0%} missing-texture magenta")
    if f.stem.endswith(("_n", "_s")):
        base = f.with_name(f.stem[:-2] + ".png")
        if base.exists():
            bw, bh = Image.open(base).size
            if (w % bw or h % bh) or w // bw != h // bh:
                probs.append(f"PBR map {w}x{h} does not match base {bw}x{bh}")
    return probs


def main():
    files = sorted(TEX.rglob("*.png"))
    bad = {}
    for f in files:
        p = audit(f)
        if p:
            bad[f.relative_to(TEX).as_posix()] = p
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"textures": len(files), "problems": bad}, indent=1), encoding="utf-8")
    n = sum(len(v) for v in bad.values())
    print(f"textures {len(files)}, with problems {len(bad)}")
    for k, v in list(bad.items())[:30]:
        print(f"  {k}: {'; '.join(v)}")
    print(f"problems {n}")
    return 1 if n else 0


if __name__ == "__main__":
    sys.exit(main())
