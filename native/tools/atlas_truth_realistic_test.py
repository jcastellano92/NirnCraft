"""atlas_truth lighting=realistic on synthetic scenes with known answers (the library of atlas_truth_test.py: procedural
sprites, no game assets): a box casting a shadow on a plane (hard and soft), the sky hemisphere's visibility beside a
wall (against a numerical integral), leaves casting dappled shade (also from inside a leaf block), a lantern in a closed
room (no sky, no sun, the inverse square, a pillar's shadow), one bounce, emission, the pack's tonemap and sun path.

    py -3 native/tools/atlas_truth_realistic_test.py [native/build/Release/atlas_truth.exe] [--keep DIR]   exit 1 on a failure
"""
import json
import math
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import atlas_truth_test as T  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SX, SY, SZ = 40, 10, 40
STONE, LANTERN, LEAVES, WOOL = 1, 7, 8, 9

# the scene: a stone floor (top at y 1); band A a 3^3 stone box; band B a long wall; band C leaves in the air and on
# the floor; band D a closed room with a lantern and a pillar; band E a white wall beside open floor (bounce)
BOX = (10, 1, 10, 13, 4, 13)       # [lo, hi) blocks
WALL = (24, 1, 2, 25, 7, 30)
ROOM = (2, 1, 30, 11, 6, 39)       # outer shell, walls one block thick; interior [3, 10) x [1, 5) x [31, 38)
LANTERN_AT = (6, 1, 34)
PILLAR = (6, 1, 36)                # two blocks high, between the lantern and the floor at z 37
LEAF_AIR = (16, 4, 4)
LEAF_FLOOR = (18, 1, 4)


def scene():
    ids = [[[0] * SX for _ in range(SZ)] for _ in range(SY)]

    def put(x, y, z, s):
        ids[y][z][x] = s

    def fill(b, s):
        for y in range(b[1], b[4]):
            for z in range(b[2], b[5]):
                for x in range(b[0], b[3]):
                    put(x, y, z, s)
    for z in range(SZ):
        for x in range(SX):
            put(x, 0, z, STONE)
    fill(BOX, STONE)
    fill(WALL, STONE)
    fill(ROOM, STONE)
    fill((ROOM[0] + 1, ROOM[1], ROOM[2] + 1, ROOM[3] - 1, ROOM[4] - 1, ROOM[5] - 1), 0)  # hollow it
    put(*LANTERN_AT, LANTERN)
    put(PILLAR[0], 1, PILLAR[2], STONE), put(PILLAR[0], 2, PILLAR[2], STONE)
    put(*LEAF_AIR, LEAVES)
    put(*LEAF_FLOOR, LEAVES)
    for y in range(1, 6):
        for z in range(12, 20):
            put(32, y, z, WOOL)  # band E: a red wall
    return ids


def run(exe, d, rays, extra):
    spec = ";".join(",".join(f"{v:g}" for v in (*T.W(*o), *dd)) for o, dd in rays)
    r = subprocess.run([str(exe), f"quads={d / 'quads.bin'}", f"volume={d / 'scene.alv2'}", f"ray={spec}", "lighting=realistic",
                        *extra], capture_output=True, text=True, timeout=600)
    if r.returncode:
        raise SystemExit(f"atlas_truth failed: {r.stderr[-600:]}")
    return [json.loads(line) for line in r.stdout.splitlines() if line.startswith("{")]


def slab(o, d, lo, hi):
    """Rays (N x 3) against box [lo, hi): does each meet it at t > 0."""
    with np.errstate(divide="ignore", invalid="ignore"):
        inv = 1.0 / d
        t0, t1 = (np.array(lo) - o) * inv, (np.array(hi) - o) * inv
    tn = np.nanmax(np.minimum(t0, t1), axis=1)
    tf = np.nanmin(np.maximum(t0, t1), axis=1)
    return (tf >= np.maximum(tn, 0)) & (tf > 0)


def hemisphere(p, nrm, boxes, up=1.0, ground=0.0, n=400000, seed=1):
    """The cosine-weighted mean over the hemisphere of nrm at p of what each direction sees: 0 where a box blocks it,
    else `up` above the horizon and `ground` under it (the pack's sky / ground radiance)."""
    rng = np.random.default_rng(seed)
    u, v = rng.random(n), rng.random(n)
    r, ph = np.sqrt(u), 2 * np.pi * v
    nz = np.array(nrm, dtype=float)
    t = np.cross(nz, [0, 1, 0] if abs(nz[1]) < 0.9 else [1, 0, 0])
    t /= np.linalg.norm(t)
    b2 = np.cross(nz, t)
    d = (r * np.cos(ph))[:, None] * t + (r * np.sin(ph))[:, None] * b2 + np.sqrt(1 - u)[:, None] * nz
    o = np.tile(np.array(p, dtype=float), (n, 1)) + d * 1e-4
    blocked = np.zeros(n, dtype=bool)
    for b in boxes:
        blocked |= slab(o, d, b[:3], b[3:])
    return float(np.where(blocked, 0.0, np.where(d[:, 1] >= 0, up, ground)).mean())


def sky_visibility(p, boxes):
    return hemisphere(p, (0, 1, 0), boxes)


def shadowed(p, L, boxes):
    o = np.array([p], dtype=float)
    d = np.array([L], dtype=float)
    return any(bool(slab(o + d * 1e-4, d, b[:3], b[3:])[0]) for b in boxes)


def tonemap(hdr, exposure, nv):
    """pack_look.hpp / final.fsh, in numpy: night vision, exposure, the ACES fit, sRGB."""
    c = np.array(hdr, dtype=float)
    lum = c @ [0.2126, 0.7152, 0.0722]
    if nv > 0:
        m = np.clip((math.log2(0.08) - math.log2(max(lum, 1e-6))) / 3.6, 0, 1) * nv
        rod = c @ [0.04, 0.5, 0.46]
        c = c + (rod * np.array([0.6, 0.8, 1.2]) / 0.787 - c) * m
    c = c * 2 ** exposure
    A = np.array([[0.59719, 0.35458, 0.04823], [0.07600, 0.90834, 0.01566], [0.02840, 0.13383, 0.83777]])
    B = np.array([[1.60475, -0.53108, -0.07367], [-0.10208, 1.10813, -0.00605], [-0.00327, -0.07276, 1.07602]])
    i = A @ c
    r = (i * (i + 0.0245786) - 0.000090537) / (i * (0.983729 * i + 0.4329510) + 0.238081)
    o = np.clip(B @ r, 0, 1)
    return np.where(o <= 0.0031308, o * 12.92, 1.055 * o ** (1 / 2.4) - 0.055) * 255


def main():
    argv = sys.argv[1:]
    keep = None
    if "--keep" in argv:
        i = argv.index("--keep")
        keep = argv[i + 1]
        del argv[i:i + 2]
    exe = Path(argv[0]) if argv else ROOT / "native/build/Release/atlas_truth.exe"
    tmp = Path(keep) if keep else Path(tempfile.mkdtemp(prefix="atlas_truth_real_"))
    tmp.mkdir(parents=True, exist_ok=True)
    T.build(tmp)
    T.SX, T.SY, T.SZ = SX, SY, SZ
    T.write_volume(tmp / "scene.alv2", scene())
    fails = []

    def check(name, ok, detail):
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")
        if not ok:
            fails.append(name)

    down = (0, -1, 0)
    # isolate the terms: a white key light of radiance 1, no sky (or a white one of 1), no floor, no tonemap tricks
    base = ["keyrad=1,1,1", "floor=0", "nv=0", "exposure=0"]

    # ---- 1. a box's shadow on the floor, hard (a point sun) -------------------------------------------------------
    L = np.array([0.45, 0.8, 0.3])
    L = L / np.linalg.norm(L)
    sun = ["sun=" + ",".join(f"{v:.6f}" for v in L), "spread=0", "skyrad=0,0,0", "groundrad=0,0,0", "lights=0"]
    pts = []
    for x in np.arange(4.1, 14.0, 0.53):
        for z in np.arange(4.3, 14.0, 0.61):
            if BOX[0] <= x < BOX[3] and BOX[2] <= z < BOX[5]:
                continue
            pts.append((x, z))
    res = run(exe, tmp, [((x, 3.0, z), down) for x, z in pts if not (BOX[0] - 0.01 < x < BOX[3] + 0.01 and BOX[2] - 0.01 < z < BOX[5] + 0.01)],
              base + sun + ["samples=4", "ao=1"])
    wrong, n_sh = 0, 0
    pts = [(x, z) for x, z in pts if not (BOX[0] - 0.01 < x < BOX[3] + 0.01 and BOX[2] - 0.01 < z < BOX[5] + 0.01)]
    for (x, z), r in zip(pts, res):
        sh = shadowed((x, 1.0, z), L, [BOX])
        n_sh += sh
        want = 0.0 if sh else L[1]
        got = r["real"]["direct"][0]
        # within a hair of the shadow's edge the sampled point may fall either side
        if abs(got - want) > 1e-3:
            wrong += 1
    check("hard sun shadow of a box on the floor (analytic slab test)", wrong == 0 and n_sh > 10,
          f"{len(pts)} floor points, {n_sh} in shadow, {wrong} wrong (direct = L.y = {L[1]:.4f} lit, 0 shadowed)")

    # ---- 2. the same, soft: a sun of 0.05 rad: half lit on the hard edge, fully lit / dark well away from it -------
    # the box's top edge x = 13 casts the edge at floor x = 13 + 3 * Lx / Ly, z inside the box's shadow band
    # the box's shadow falls away from the sun: its far edge (cast by the top edge x = 10) at floor x = 10 - 3 Lx / Ly
    xe = BOX[0] - (BOX[4] - 1) * L[0] / L[1]
    zc = 11.5 - (BOX[4] - 1) * L[2] / L[1]
    soft = ["sun=" + ",".join(f"{v:.6f}" for v in L), "spread=0.05", "skyrad=0,0,0", "groundrad=0,0,0", "lights=0"]
    res = run(exe, tmp, [((xe - 0.5, 3.0, zc), down), ((xe, 3.0, zc), down), ((xe + 0.5, 3.0, zc), down)], base + soft + ["samples=512", "ao=1"])
    v = [r["real"]["sunVis"] for r in res]
    # the penumbra's half width there: the gap (the edge's height over the floor along the ray, 3 / Ly) x tan(0.05),
    # projected on the floor (/ Ly): about 0.2 blocks, so 0.5 blocks either side is out of it
    check("soft sun shadow: lit outside, half on the hard edge, dark inside", v[0] > 0.98 and 0.35 < v[1] < 0.65 and v[2] < 0.02,
          f"sun visibility {v[0]:.3f} / {v[1]:.3f} / {v[2]:.3f} at x = {xe - 0.5:.2f} / {xe:.2f} / {xe + 0.5:.2f}")

    # ---- 3. the sky hemisphere beside a long wall and the box, against a numerical integral --------------------------
    sky = ["sun=0,1,0", "keyrad=0,0,0", "skyrad=1,1,1", "groundrad=0,0,0", "lights=0", "floor=0", "nv=0", "exposure=0", "ao=8"]
    probes = [(WALL[0] - 0.5, 16.5), (WALL[0] - 1.5, 16.5), (WALL[0] - 4.5, 16.5), (13.5, 11.5), (30.5, 34.5)]
    res = run(exe, tmp, [((x, 3.0, z), down) for x, z in probes], sky + ["samples=1024"])
    walls = [WALL, BOX, (32, 1, 12, 33, 6, 20), ROOM]
    ok, det = True, []
    for (x, z), r in zip(probes, res):
        want = sky_visibility((x, 1.0, z), walls)
        got = r["real"]["skyVis"]
        amb = r["real"]["ambient"][0]
        ok &= abs(got - want) < 0.015 and abs(amb - got) < 1e-4
        det.append(f"({x:g}, {z:g}) {got:.3f} vs {want:.3f}")
    check("sky visibility = the cosine-weighted unblocked hemisphere (sky radiance 1: ambient = visibility)", ok, ", ".join(det))
    r = run(exe, tmp, [((36.5, 3.0, 36.5), down)], sky + ["samples=1024", "skyrad=0.33,0.45,0.7", "groundrad=0.2,0.18,0.14"])[0]
    a = r["real"]["ambient"]
    vis = sky_visibility((36.5, 1.0, 36.5), walls)
    check("a floor in the open: ambient = the pack's skyAmbient(up) x the sky's visibility",
          all(abs(a[i] - (0.33, 0.45, 0.7)[i] * vis) < 0.01 for i in range(3)) and vis > 0.95,
          f"{[round(v, 4) for v in a]} vs skyUp x {vis:.3f}")
    # the long wall's west face (normal -x) at 2.5 blocks over the floor: sky over the horizon (the box and the room
    # in the way), the floor under it, ground radiance only where a downward ray leaves the volume past the floor's edge
    r = run(exe, tmp, [((WALL[0] - 4.0, 3.5, 16.5), (1, 0, 0))], sky + ["samples=1024", "skyrad=0.33,0.45,0.7", "groundrad=0.2,0.18,0.14"])[0]
    a = r["real"]["ambient"]
    solids = walls + [(0, 0, 0, SX, 1, SZ)]
    exp = [hemisphere((WALL[0], 3.5, 16.5), (-1, 0, 0), solids, up=(0.33, 0.45, 0.7)[i], ground=(0.2, 0.18, 0.14)[i]) for i in range(3)]
    check("a vertical face: the sky over its horizon, the floor's shade under it (numerical integral)",
          all(abs(a[i] - exp[i]) < 0.01 for i in range(3)), f"{[round(v, 4) for v in a]} vs {[round(v, 4) for v in exp]}")

    # ---- 4. leaves: dappled shade (the sprite's left half is leaf, the right half a hole) -----------------------------
    leaf = ["sun=0,1,0", "spread=0", "skyrad=0,0,0", "groundrad=0,0,0", "lights=0", "sss=0"]
    xs = [LEAF_AIR[0] + 0.25, LEAF_AIR[0] + 0.75, LEAF_FLOOR[0] + 0.25, LEAF_FLOOR[0] + 0.75]
    zs = [LEAF_AIR[2] + 0.5] * 2 + [LEAF_FLOOR[2] + 0.5] * 2
    # the floor under the floating leaves; inside the leaf block on the floor the ray must start under it: from below
    rays = [((xs[0], 3.5, zs[0]), down), ((xs[1], 3.5, zs[1]), down), ((xs[2], 1.5, zs[2]), down), ((xs[3], 1.5, zs[3]), down)]
    res = run(exe, tmp, rays, base + leaf + ["samples=4", "ao=1"])
    v = [r["real"]["sunVis"] for r in res]
    ys = [r["layers"][0]["point"][1] - T.ORIGIN[1] if r["layers"] else -1 for r in res]
    check("leaves in the air: shade under a leaf texel, sun through a hole", v[0] == 0 and v[1] == 1 and abs(ys[0] - 1) < 1e-3,
          f"sun visibility {v[0]:.2f} / {v[1]:.2f}, floor y {ys[0]:.3f}")
    check("a leaf block on the floor: the floor inside it is shaded by its leaves from behind too",
          v[2] == 0 and v[3] == 1 and abs(ys[2] - 1) < 1e-3, f"sun visibility {v[2]:.2f} / {v[3]:.2f} (two-sided occlusion)")
    r = run(exe, tmp, [((xs[0], 3.5, zs[0]), down)], base + leaf[:-1] + ["sss=1", "samples=16", "ao=1"])[0]["real"]
    # the floor is stone (not foliage): no light through; the leaf's own top under the sun gets it
    rr = run(exe, tmp, [((xs[0], 8, zs[0]), down)], base + leaf[:-1] + ["sss=1", "samples=16", "ao=1"])[0]["real"]
    check("light through leaves only on foliage", r["sss"][0] == 0 and rr["sss"][0] > 0 and rr["sunVis"] == 1,
          f"floor sss {r['sss'][0]:.4f}, leaf top sss {rr['sss'][0]:.4f}")

    # ---- 5. a lantern in a closed room: no sky, no sun; the inverse square; a pillar's shadow -----------------------
    room = ["time=6000", "lighti=9.4", "lightr0=0.5", "nv=0", "exposure=0", "floor=0", "ao=4"]
    ip = [(4.5, 33.5), (8.5, 34.5), (3.5, 31.5), (6.5, 37.5)]
    res = run(exe, tmp, [((x, 3.5, z), down) for x, z in ip], room + ["samples=1024"])
    # the lantern model (atlas_truth_test: 5/16 .. 11/16 wide, 7/16 high) sampled uniformly, as the tool samples it
    lo = np.array([LANTERN_AT[0] + 5 / 16, 1.0, LANTERN_AT[2] + 5 / 16])
    hi = np.array([LANTERN_AT[0] + 11 / 16, 7 / 16 + 1.0, LANTERN_AT[2] + 11 / 16])
    rng = np.random.default_rng(3)
    smp = lo + (hi - lo) * rng.random((200000, 3))
    hue = np.array([1.0, 0.62, 0.32])
    ok, det = True, []
    for (x, z), r in zip(ip[:3], res[:3]):
        p = np.array([x, 1.0, z])
        l = smp - p
        dist = np.linalg.norm(l, axis=1)
        c = np.clip(l[:, 1] / dist, 0, None)
        xr = dist / 16.0
        E = 9.4 * c / (dist ** 2 + 0.25) * (1 - xr ** 4) ** 2 * (dist < 16)
        want = hue * E.mean()
        got = np.array(r["real"]["block"])
        ok &= bool(np.all(np.abs(got - want) < 0.03 * want + 1e-4)) and r["real"]["skyVis"] == 0 and r["real"]["sunVis"] == 0
        det.append(f"({x:g}, {z:g}) {got[0]:.4f} vs {want[0]:.4f}")
    check("lantern in a closed room: sky 0, sun 0, block light = I cos / (d^2 + r0^2) x window (3 %)", ok, ", ".join(det))
    r = res[3]["real"]
    check("a pillar between the lantern and the floor: its shadow", r["block"][0] < 1e-3, f"block light behind the pillar {r['block'][0]:.5f}")
    lr = run(exe, tmp, [((LANTERN_AT[0] + 0.5, 3.5, LANTERN_AT[2] + 0.5), down)], room + ["samples=8"])[0]["real"]
    check("the lantern glows (lib/materials.glsl MAT_LIGHT: albedo x 4 x smoothstep of its brightness)",
          lr["emit"][0] > 0.3 and lr["block"][0] == 0, f"emission {lr['emit']}, own light {lr['block'][0]}")

    # ---- 6. one bounce: open floor beside a lit red wall gains red ambient -------------------------------------------
    b0 = run(exe, tmp, [((31.5, 3.0, 16.0), down)], ["sun=-0.6,0.6,0.1", "nv=0", "exposure=0", "lights=0", "samples=512", "ao=4", "bounce=0"])[0]["real"]
    b1 = run(exe, tmp, [((31.5, 3.0, 16.0), down)], ["sun=-0.6,0.6,0.1", "nv=0", "exposure=0", "lights=0", "samples=512", "ao=4", "bounce=1"])[0]["real"]
    gain = [b1["ambient"][i] - b0["ambient"][i] for i in range(3)]
    check("one bounce: the floor at a sunlit red wall gains ambient, most of it red", gain[0] > 0.02 and gain[0] > 2 * gain[2],
          f"ambient {[round(v, 4) for v in b0['ambient']]} -> {[round(v, 4) for v in b1['ambient']]}")

    # ---- 7. the tonemap and the sun's path ---------------------------------------------------------------------------
    r = run(exe, tmp, [((30.5, 3.0, 4.5), down)], ["time=6000", "samples=64"])[0]["real"]
    info = json.loads(subprocess.run([str(exe), "info=1", "time=6000"], capture_output=True, text=True).stdout)
    want = tonemap(r["pixelHdr"], info["exposure"], 0.7)
    check("display = final.fsh (night vision, exposure, ACES fit, sRGB) of the HDR", np.all(np.abs(want - np.array(r["display"])) < 0.6),
          f"{r['display']} vs {[round(v, 2) for v in want]}")
    ok, det = True, []
    for t in (0, 6000, 12000, 18000):
        inf = json.loads(subprocess.run([str(exe), "info=1", f"time={t}"], capture_output=True, text=True).stdout)
        f = ((t / 24000 - 0.25) % 1.0)
        ang = (2 * f + 0.5 - math.cos(f * math.pi) / 2) / 3 * 2 * math.pi
        ph = math.radians(-25)
        s = (-math.sin(ang), math.cos(ang) * math.cos(ph), -math.cos(ang) * math.sin(ph))
        ok &= all(abs(inf["sun"][k] - s[k]) < 1e-4 for k in range(3))
        det.append(f"{t}: {[round(v, 3) for v in inf['sun']]}")
    check("the sun's path (sky angle, Iris' turn by sunPathRotation -25): noon high in the south, sunset west", ok and
          json.loads(subprocess.run([str(exe), "info=1", "time=12000"], capture_output=True, text=True).stdout)["sun"][0] < -0.9,
          "; ".join(det))
    print(f"{len(fails)} failure(s); inputs in {tmp}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
