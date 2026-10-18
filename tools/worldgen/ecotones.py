"""Ecotone / variety additions for region palettes -> tools/worldgen/ecotones.json (read by biomes.py).

Usage: py -3 -I tools/worldgen/ecotones.py

Each SPEC row says: region palette R gets extra surface biomes (relative weights) that make its land grade towards
its neighbours instead of jumping. `linear` rows point the palette's geographic bias (Palettes.bias, "linear") from
R's centroid towards the border it shares with the listed neighbours, so the extra biomes only grow in the last
~40% of the way to that border; `radial` rows grow them in a ring round the region's rim (for regions bordered on
every side); `none` rows just add variety everywhere. `share` is the fraction of the lowland/upland mix the extras
take where they are strongest.

The geometry (centroids, border points) comes from the baked rasters, so re-run this after tamriel_map.py moves
region borders, then biomes.py. The output only holds numbers; biomes.py applies them (apply_ecotones).
"""
import json
import math
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
TAM = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft", "tamriel")
META = json.load(open(os.path.join(HERE, "tamriel_vectors.json"), encoding="utf-8"))["meta"]
CELL, MIN_X, MIN_Z = META["cell"], META["min_x"], META["min_z"]

# (region, mode, neighbours, share, [(biome, relative weight)], optional [(biome, weight)] for the peak context)
SPEC = [
    # --- Hammerfell & High Rock: desert -> scrub/steppe -> highland/woodland
    ("hammerfell/alikr_desert", "linear", ["hammerfell/bangkorai", "hammerfell/sentinel", "hammerfell/dragontail"], 0.35,
     [("warm_steppe", 3), ("anequina_badlands", 1)]),
    ("hammerfell/sentinel", "linear", ["hammerfell/alikr_desert"], 0.3,
     [("warm_steppe", 2), ("alikr_desert", 1)]),
    ("hammerfell/bangkorai", "linear", ["hammerfell/alikr_desert", "hammerfell/dragontail", "hammerfell/craglorn"],
     0.3, [("warm_steppe", 1)]),
    ("hammerfell/dragontail", "linear", ["hammerfell/bangkorai", "cyrodiil/colovian_highlands"], 0.35,
     [("warm_steppe", 2), ("bangkorai", 1)]),
    ("hammerfell/craglorn", "radial", [], 0.4,
     [("warm_steppe", 3), ("bangkorai", 1), ("anequina_badlands", 1)]),
    ("hammerfell/stros_mkai", "none", [], 0.3, [("pellitine_jungle", 1), ("elsweyr_savanna", 1)]),
    ("high_rock/wrothgar", "linear", ["high_rock/bjoulsae", "high_rock/wayrest", "hammerfell/bangkorai"], 0.3,
     [("bangkorai", 1)]),
    ("high_rock/wayrest", "linear", ["high_rock/wrothgar", "hammerfell/bangkorai"], 0.25, [("warm_steppe", 1)]),
    ("high_rock/daggerfall", "linear", ["high_rock/anticlere", "high_rock/glenumbra"], 0.25, [("stormhaven", 1)]),
    ("high_rock/anticlere", "linear", ["high_rock/daggerfall", "high_rock/glenumbra"], 0.25, [("glenumbra_moors", 1)]),
    ("high_rock/dwynnen", "linear", ["high_rock/wayrest", "high_rock/anticlere"], 0.25, [("wayrest_farmland", 1)]),
    ("cyrodiil/colovian_highlands", "linear", ["hammerfell/craglorn", "hammerfell/dragontail"], 0.3,
     [("warm_steppe", 1), ("bangkorai", 1)]),
    ("cyrodiil/west_weald", "linear", ["hammerfell/dragontail"], 0.25, [("warm_steppe", 1)]),
    # --- Morrowind <-> Black Marsh: ash waste -> grassy scrub -> wooded fen -> swamp
    ("morrowind/molag_amur", "radial", [], 0.4, [("grazelands", 1), ("ash_heath", 1)]),
    ("morrowind/telvannis", "linear", ["morrowind/molag_amur"], 0.3, [("grazelands", 1), ("ash_heath", 1)]),
    ("morrowind/sheogorad", "linear", ["morrowind/vvardenfell_ashlands"], 0.3,
     [("ash_heath", 1), ("solstheim_ashlands", 1)]),
    ("morrowind/vvardenfell_ashlands", "linear", ["morrowind/sheogorad"], 0.3, [("ash_heath", 1)]),
    ("morrowind/deshaan", "linear", ["black_marsh/shadowfen"], 0.35, [("fen_edge", 1), ("grazelands", 1)]),
    ("black_marsh/shadowfen", "linear", ["morrowind/molag_amur", "morrowind/deshaan"], 0.4,
     [("fen_edge", 3), ("reedlands", 1)]),
    ("black_marsh/stormhold", "linear", ["cyrodiil/nibenay_basin"], 0.35, [("fen_edge", 2), ("reedlands", 1)]),
    ("cyrodiil/nibenay_basin", "linear", ["black_marsh/stormhold"], 0.3, [("fen_edge", 1)]),
    ("black_marsh/lilmoth", "none", [], 0.3, [("reedlands", 1), ("fen_edge", 1)]),
    ("black_marsh/gideon", "none", [], 0.3, [("reedlands", 1), ("fen_edge", 1)]),
    ("black_marsh/murkmire", "none", [], 0.25, [("reedlands", 1)]),
    # --- Skyrim <-> Morrowind: tundra -> ash heath -> ashlands
    ("skyrim/eastmarch", "linear", ["morrowind/stonefalls"], 0.4, [("ash_heath", 3), ("solstheim_ashlands", 1)],
     [("red_mountain", 1)]),
    ("skyrim/the_rift", "linear", ["morrowind/stonefalls"], 0.3, [("ash_heath", 1)]),
    ("morrowind/stonefalls", "linear", ["skyrim/eastmarch", "skyrim/the_rift", "cyrodiil/jerall_mountains",
                                         "cyrodiil/nibenay_basin"], 0.4, [("ash_heath", 3), ("grazelands", 1)],
     [("snowy_slopes", 1)]),
    # --- Cyrodiil <-> Elsweyr: lush valley -> dry steppe -> savanna -> badlands
    ("cyrodiil/nibenay_valley", "linear", ["elsweyr/anequina", "elsweyr/rimmen"], 0.35, [("warm_steppe", 2)]),
    ("cyrodiil/blackwood", "linear", ["elsweyr/rimmen"], 0.3, [("warm_steppe", 1), ("fen_edge", 1)]),
    ("cyrodiil/gold_coast", "linear", ["elsweyr/anequina"], 0.3, [("warm_steppe", 1)]),
    ("elsweyr/rimmen", "linear", ["cyrodiil/nibenay_valley", "cyrodiil/blackwood"], 0.35,
     [("warm_steppe", 2), ("grazelands", 1)]),
    ("elsweyr/anequina", "radial", [], 0.35, [("elsweyr_savanna", 2), ("warm_steppe", 1)]),
    ("elsweyr/pellitine", "linear", ["elsweyr/anequina"], 0.3, [("elsweyr_savanna", 2)]),
    ("elsweyr/senchal", "none", [], 0.25, [("elsweyr_savanna", 1)]),
    ("valenwood/reapers_march", "linear", ["elsweyr/anequina"], 0.35, [("warm_steppe", 1), ("elsweyr_savanna", 1)]),
    ("valenwood/greenshade", "none", [], 0.25, [("pellitine_jungle", 1)]),
    # --- Skyrim's snow country: tundra -> heath -> snowfield
    ("skyrim/the_pale", "linear", ["skyrim/whiterun"], 0.4, [("pale_heath", 2), ("whiterun_tundra", 1),
                                                              ("alpine_meadow", 1)]),
    # (no snowy heath on Whiterun's own side: the Pale's tundra mix already reaches down to the border, and the
    # heath put snow patches inside the plains and the city itself)
    ("skyrim/winterhold", "radial", [], 0.3, [("pale_heath", 2), ("snowy_pine_forest", 1)]),
    # --- variety only
    ("summerset/artaeum", "none", [], 0.3, [("summerset_meadow", 1), ("auridon_vineyards", 1)]),
    ("summerset/auridon", "none", [], 0.25, [("crystal_coast", 1)]),
]


def geometry():
    reg = np.array(Image.open(os.path.join(TAM, "regions.png")))
    regions = json.load(open(os.path.join(TAM, "regions.json"), encoding="utf-8"))
    idx = {r["id"]: r["index"] for r in regions}
    H, W = reg.shape
    return reg, idx, H, W


def xy(col, row):
    return MIN_X + (col + 0.5) * CELL, MIN_Z + (row + 0.5) * CELL


def border_points(reg, ia, ib):
    """Border edges between ia and ib as (col, row, step_c, step_r): the edge midpoint and the unit step from the
    ia side to the ib side."""
    pts = []
    for axis in (0, 1):
        a, b = (reg[:, :-1], reg[:, 1:]) if axis == 0 else (reg[:-1, :], reg[1:, :])
        for first, sign in ((ia, 1), (ib, -1)):
            second = ib if first == ia else ia
            rr, cc = np.nonzero((a == first) & (b == second))
            for r, c in zip(rr, cc):
                if axis == 0:
                    pts.append((c + 0.5, r, sign, 0))
                else:
                    pts.append((c, r + 0.5, 0, sign))
    return pts


def main():
    reg, idx, H, W = geometry()
    out = {}
    for region, mode, neighbours, share, add, *rest in SPEC:
        i = idx[region]
        rr, cc = np.nonzero(reg == i)
        cx, cz = xy(cc.mean(), rr.mean())
        radius = math.sqrt(len(rr) / math.pi) * CELL
        entry = {"share": share, "add": [[b, w] for b, w in add]}
        if rest:
            entry["peak_add"] = [[b, w] for b, w in rest[0]]
        if mode == "linear":
            pts = []
            for n in neighbours:
                pts += border_points(reg, i, idx[n])
            assert pts, (region, neighbours)
            nx = float(np.mean([p[2] for p in pts]))
            nz = float(np.mean([p[3] for p in pts]))
            nl = math.hypot(nx, nz)
            assert nl > 0.2, (region, "border wraps round the region; use radial")
            dx, dz = nx / nl, nz / nl
            # distance along the outward normal: centroid -> mean border position
            bx = float(np.mean([xy(p[0], p[1])[0] for p in pts]))
            bz = float(np.mean([xy(p[0], p[1])[1] for p in pts]))
            L = max((bx - cx) * dx + (bz - cz) * dz, 300.0)
            # t = 0 at 60% of the way from the centroid to the border, +1 at the border (span = 40% of the way)
            span = max(L * 0.4, 250.0)
            ox, oz = cx + dx * L * 0.6, cz + dz * L * 0.6
            entry["bias"] = {"linear": [round(ox), round(oz), round(dx, 3), round(dz, 3), round(span), 0.5]}
        elif mode == "radial":
            entry["bias"] = {"radial": [round(cx), round(cz), round(radius * 0.45), round(radius * 0.95), 0.5]}
        out[region] = entry
    path = os.path.join(HERE, "ecotones.json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print("wrote %d ecotone rows -> %s" % (len(out), path))


if __name__ == "__main__":
    main()
