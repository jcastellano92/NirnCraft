"""PBR / material audit for every NirnCraft block: which blocks the Infinite ATLAS pack classifies (block.properties
material ids: leaves, water, fire, glass, metal ...), which textures carry labPBR maps (_n normal/AO/height, _s
smoothness/F0-metal/emission), whether those maps are valid, and blocks whose names suggest a material they are not
given (metal without the metal id, glass/ice without refraction, glowing things without the light id).

    py -3 tools/harness/audits/pbr_audit.py      exit 1 only for broken maps; coverage and suspects are reported
Details: .work/harness/audits/pbr.json
"""
import json
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
PROPS = ROOT / "shaderpack/InfiniteATLAS/shaders/block.properties"
TEX = ROOT / "mod/src/main/resources/assets/nirncraft/textures/block"
STATES = ROOT / "mod/src/main/resources/assets/nirncraft/blockstates"
OUT = ROOT / ".work/harness/audits/pbr.json"
SUSPECT = {  # name pattern -> material ids that would fit it (EXCLUDE: names that only sound like it)
    "metal": (re.compile(r"(^|_)(iron|steel|bronze|copper|dwarven|dwemer|metal|chain|anvil)(_|$)"), {"10014"}),
    "glass/ice": (re.compile(r"(^|_)(glass|ice|crystal_block)(_|$)"), {"10009", "10013"}),
    "light": (re.compile(r"(lantern|lamp|torch|candle|sconce|brazier|sigil|glowing)"), {"10008", "10007"}),
    "plant": (re.compile(r"(leaves|grass|fern|flower|shrub|bush|vine|moss|reed|sapling|crop)"),
              {"10001", "10002", "10003", "10004", "10008"}),
}
EXCLUDE = re.compile(r"(_ore$|^ore_|_oil$|_ingot|_nugget|_dust$)")


def material_ids():
    text = PROPS.read_text(encoding="utf-8").replace("\\\n", " ")
    ids = {}
    for line in text.splitlines():
        m = re.match(r"^block\.(\d+)\s*=\s*(.*)$", line.strip())
        if m:
            for b in m.group(2).split():
                ids.setdefault(b.split(":")[0] + ":" + b.split(":")[1] if b.count(":") >= 1 else b, m.group(1))
    return ids


def check_map(base, path, kind):
    problems = []
    try:
        img = Image.open(path)
    except OSError as e:
        return [f"unreadable: {e}"]
    if base is not None:
        b = Image.open(base)
        if img.width % b.width or img.height % b.height or img.width // b.width != img.height // b.height:
            problems.append(f"size {img.size} does not match base {b.size}")
    a = np.asarray(img.convert("RGBA"), dtype=np.float32)
    if kind == "n":
        x, y = a[..., 0] / 127.5 - 1, a[..., 1] / 127.5 - 1
        if float((x * x + y * y > 1.05).mean()) > 0.02:
            problems.append("normal XY outside the unit disc (not a labPBR normal map?)")
        if float(a[..., 3].std()) < 0.5 and float(a[..., 3].mean()) < 1:
            problems.append("height channel empty (alpha 0): POM would sink the whole surface")
    else:
        g = a[..., 1]
        bad = ((g > 229) & (g < 230)).mean()
        if float(bad) > 0:
            problems.append("F0 channel between dielectric and metal ids")
    return problems


def main():
    ids = material_ids()
    blocks = sorted(p.stem for p in STATES.glob("*.json"))
    textures = sorted(p for p in TEX.glob("*.png") if not re.search(r"_(n|s)$", p.stem))
    with_s = [t for t in textures if (t.with_name(t.stem + "_s.png")).exists()]
    with_n = [t for t in textures if (t.with_name(t.stem + "_n.png")).exists()]
    broken = {}
    for t in TEX.glob("*_[ns].png"):
        base = t.with_name(t.stem[:-2] + ".png")
        p = check_map(base if base.exists() else None, t, t.stem[-1])
        if p:
            broken[t.name] = p
    classified = [b for b in blocks if f"nirncraft:{b}" in ids]
    suspects = {}
    for b in blocks:
        mid = ids.get(f"nirncraft:{b}")
        for what, (rx, fits) in SUSPECT.items():
            if rx.search(b) and mid not in fits and not EXCLUDE.search(b):
                suspects.setdefault(what, []).append(b)
    report = {"blocks": len(blocks), "classified": len(classified), "textures": len(textures),
              "withSpecular": len(with_s), "withNormal": len(with_n), "broken": broken,
              "suspects": {k: v for k, v in suspects.items()}, "unclassified": [b for b in blocks if b not in classified]}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, indent=1), encoding="utf-8")
    pct = lambda n, d: f"{100 * n / max(1, d):.0f}%"  # noqa: E731
    print(f"blocks {len(blocks)}, material id {len(classified)} ({pct(len(classified), len(blocks))})")
    print(f"textures {len(textures)}, labPBR specular {len(with_s)} ({pct(len(with_s), len(textures))}), "
          f"normal {len(with_n)} ({pct(len(with_n), len(textures))})")
    print("suspects: " + (", ".join(f"{k} {len(v)}" for k, v in suspects.items()) or "none"))
    for k, v in suspects.items():
        print(f"  {k}: {' '.join(v[:12])}{' ...' if len(v) > 12 else ''}")
    print(f"broken maps: {len(broken)}")
    for k, v in list(broken.items())[:20]:
        print(f"  {k}: {'; '.join(v)}")
    print(f"details: {OUT}")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
