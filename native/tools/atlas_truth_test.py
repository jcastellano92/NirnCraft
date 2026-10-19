"""atlas_truth on synthetic inputs: a hand-built quads library (procedural sprites, no game assets) and a small scene,
traced ray by ray and checked against hand-computed expectations (shapes, culling, cutout and translucency, tints,
smooth lighting, light falloff, the lightmap).

    py -3 native/tools/atlas_truth_test.py [native/build/Release/atlas_truth.exe] [--keep DIR]   exit 1 on a failure
"""
import json
import math
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOWN, UP, NORTH, SOUTH, WEST, EAST, NONE = range(7)
SOLID, CUTOUT, TRANSLUCENT = 0, 1, 2
F_SOLID_RENDER, F_LIGHT_PERMEABLE, F_COLLISION_FULL, F_EMISSIVE, F_LEGACY_SOLID, F_CAN_OCCLUDE, F_LEAVES, F_MODEL, F_OVERLAY, F_AIR = (
    1, 2, 4, 8, 16, 32, 64, 128, 256, 512)
# the corners of each face in the game's order (FaceInfo), counter-clockwise seen from outside
CORNERS = [[(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)], [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
           [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)], [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],
           [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)], [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)]]
NORMAL = [(0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0)]
ORIGIN = (100, 60, 200)
CARD = [0.5, 1.0, 0.8, 0.8, 0.6, 0.6]


# ---- sprites (procedural) -------------------------------------------------------------------------------------------

def solid(argb):
    return [argb] * 256


def sprites():
    s = {}
    s["stone"] = [0xFF7F7F7F if (x + y) % 2 else 0xFF707070 for y in range(16) for x in range(16)]
    s["grass_top"] = solid(0xFFA0A0A0)  # grey: the tint gives it its colour
    s["dirt"] = solid(0xFF866043)
    s["path_top"] = solid(0xFF948A5C)
    s["planks"] = solid(0xFFA2834F)
    s["wool"] = solid(0xFFB02E26)
    s["lantern"] = solid(0xFFE0B040)
    s["leaves"] = [0xFF909090 if x < 8 else 0x00000000 for y in range(16) for x in range(16)]  # left half leaf, right hole
    s["pane"] = solid(0x803060E0)  # half transparent blue
    s["glass"] = [0xFFDDEEFF if x in (0, 15) or y in (0, 15) else 0x00FFFFFF for y in range(16) for x in range(16)]
    s["water_still"] = solid(0xB4C0C0C0)
    s["water_flow"] = solid(0xB4C0C0C0)
    s["water_overlay"] = solid(0xB4C0C0C0)
    return s


# ---- the library ----------------------------------------------------------------------------------------------------

def pack_normal(n):
    return (round(n[0] * 127) & 255) | (round(n[1] * 127) & 255) << 8 | (round(n[2] * 127) & 255) << 16


def box_quads(lo, hi, sprite, layer=SOLID, tint=-1, emit=0, ao=2, faces=range(6)):
    """A model element: one quad per face, culled against the neighbour where it lies on the block's face."""
    out = []
    for f in faces:
        verts = []
        for c in CORNERS[f]:
            p = tuple(hi[a] if c[a] else lo[a] for a in range(3))
            u = p[0] if f in (DOWN, UP, NORTH, SOUTH) else p[2]
            v = p[2] if f in (DOWN, UP) else 1 - p[1]
            verts.append((p, u, v))
        axis = 1 if f <= UP else 2 if f <= SOUTH else 0
        on_face = (hi[axis] == 1) if f in (UP, SOUTH, EAST) else (lo[axis] == 0)
        out.append(dict(verts=verts, normal=NORMAL[f], sprite=sprite, dir=f, cull=f if on_face else NONE, layer=layer,
                        tint=tint, emit=emit, ao=1 | ao << 1))
    return out


def model_bytes(quads, first_ao=2):
    b = struct.pack("<BI", first_ao, len(quads))
    for q in quads:
        for p, u, v in q["verts"]:
            b += struct.pack("<5f", p[0], p[1], p[2], u, v)
        b += struct.pack("<4I", *([pack_normal(q["normal"])] * 4))
        b += struct.pack("<iBBBBhBB", q["sprite"], q["dir"], q["cull"], NONE, q["layer"], q["tint"], q["emit"], q["ao"])
    return b


def rows(fill_rows):
    return [0xFFFF if r in fill_rows else 0 for r in range(16)]


def state_bytes(sid, name, block, flags, emit=0, damp=0, shade=1.0, fluid=-1, full=0, partial=None, skip=0, tints=(),
                variants=()):
    nb = name.encode()
    b = struct.pack("<IH", sid, len(nb)) + nb + struct.pack("<IHBBf", block, flags, emit, damp, shade)
    b += struct.pack("<B", 0)  # no offset
    b += struct.pack("<b", fluid)
    if fluid >= 0:
        b += struct.pack("<fBB", 8 / 9, 8, 1)
    partial = partial or {}
    pm = 0
    for d in partial:
        pm |= 1 << d
    b += struct.pack("<BB", full, pm)
    for d in range(6):
        if d in partial:
            b += struct.pack("<16H", *partial[d])
    b += struct.pack("<B", skip)
    b += struct.pack("<B", len(tints))
    for kind, c in tints:
        b += struct.pack("<BI", kind, c)
    b += struct.pack("<B", len(variants))
    for v in variants:
        b += struct.pack("<i", v)
    return b


def build(dirp):
    sp = sprites()
    names = list(sp)
    idx = {n: i for i, n in enumerate(names)}
    with open(dirp / "sprites.bin", "wb") as f:
        f.write(b"ALT1" + struct.pack("<I", len(names)))
        for n in names:
            nb = n.encode()
            f.write(struct.pack("<H", len(nb)) + nb + struct.pack("<HHB", 16, 16, 0) + struct.pack("<256I", *sp[n]))
    models = []

    def model(quads, first_ao=2):
        models.append(model_bytes(quads, first_ao))
        return len(models) - 1

    cube = ((0, 0, 0), (1, 1, 1))
    m_stone = model(box_quads(*cube, idx["stone"]))
    grass = box_quads(*cube, idx["dirt"], faces=[DOWN, NORTH, SOUTH, WEST, EAST]) + box_quads(*cube, idx["grass_top"], tint=0, faces=[UP])
    m_grass = model(grass)
    path = box_quads((0, 0, 0), (1, 15 / 16, 1), idx["dirt"], faces=[DOWN, NORTH, SOUTH, WEST, EAST]) + \
        box_quads((0, 0, 0), (1, 15 / 16, 1), idx["path_top"], faces=[UP])
    m_path = model(path)
    m_slab = model(box_quads((0, 0, 0), (1, 0.5, 1), idx["planks"]))
    m_fence = model(box_quads((6 / 16, 0, 6 / 16), (10 / 16, 1, 10 / 16), idx["planks"]))
    m_pane = model(box_quads((0, 0, 7 / 16), (1, 1, 9 / 16), idx["pane"], layer=TRANSLUCENT))
    m_lantern = model(box_quads((5 / 16, 0, 5 / 16), (11 / 16, 7 / 16, 11 / 16), idx["lantern"], layer=CUTOUT))
    m_leaves = model(box_quads(*cube, idx["leaves"], layer=CUTOUT, tint=0))
    m_wool = model(box_quads(*cube, idx["wool"]))
    m_glass = model(box_quads(*cube, idx["glass"], layer=CUTOUT))

    cube_flags = F_MODEL | F_SOLID_RENDER | F_COLLISION_FULL | F_CAN_OCCLUDE | F_LEGACY_SOLID
    st = [
        state_bytes(0, "Block{minecraft:air}", 0, F_AIR | F_LIGHT_PERMEABLE),
        state_bytes(1, "Block{test:stone}", 1, cube_flags, damp=15, shade=0.2, full=63, variants=[m_stone]),
        state_bytes(2, "Block{test:grass_block}", 2, cube_flags, damp=15, shade=0.2, full=63, tints=[(1, 0xFFFFFFFF)],
                    variants=[m_grass]),
        state_bytes(3, "Block{test:dirt_path}", 3, F_MODEL | F_LIGHT_PERMEABLE | F_LEGACY_SOLID, full=1,
                    partial={d: rows(range(15)) for d in (NORTH, SOUTH, WEST, EAST)}, variants=[m_path]),
        state_bytes(4, "Block{test:slab}[type=bottom]", 4, F_MODEL | F_LIGHT_PERMEABLE | F_LEGACY_SOLID, full=1,
                    partial={d: rows(range(8)) for d in (NORTH, SOUTH, WEST, EAST)}, variants=[m_slab]),
        state_bytes(5, "Block{test:fence}", 5, F_MODEL | F_LIGHT_PERMEABLE | F_LEGACY_SOLID, variants=[m_fence]),
        state_bytes(6, "Block{test:stained_pane}", 6, F_MODEL | F_LIGHT_PERMEABLE, variants=[m_pane]),
        state_bytes(7, "Block{test:lantern}", 7, F_MODEL | F_LIGHT_PERMEABLE, emit=15, variants=[m_lantern]),
        state_bytes(8, "Block{test:leaves}", 8, F_MODEL | F_LIGHT_PERMEABLE | F_COLLISION_FULL | F_LEAVES | F_LEGACY_SOLID, damp=1,
                    shade=0.2, tints=[(2, 0xFFFFFFFF)], variants=[m_leaves]),
        state_bytes(9, "Block{test:red_wool}", 9, cube_flags, damp=15, shade=0.2, full=63, variants=[m_wool]),
        state_bytes(10, "Block{test:water}[level=0]", 10, F_LIGHT_PERMEABLE, damp=1, fluid=0),
        state_bytes(11, "Block{test:glass}", 11, F_MODEL | F_LIGHT_PERMEABLE | F_COLLISION_FULL | F_LEGACY_SOLID, skip=63,
                    variants=[m_glass]),
    ]
    with open(dirp / "quads.bin", "wb") as f:
        f.write(b"ALQ1" + struct.pack("<I", len(models)) + b"".join(models))
        f.write(struct.pack("<I", 1) + struct.pack("<iiiBBI", idx["water_still"], idx["water_flow"], idx["water_overlay"],
                                                   TRANSLUCENT, 3, 0xFFFFFFFF))
        f.write(struct.pack("<I", len(st)) + b"".join(st))
    (dirp / "quads.json").write_text('{"ao": true, "cutoutLeaves": true, "perPartAo": false, "enhancedLighting": true,'
                                     ' "biomeBlend": 2, "gamma": 0.5, "mipmaps": 4}\n')


# ---- the scene ------------------------------------------------------------------------------------------------------

SX, SY, SZ = 24, 8, 22
GRASS_W, GRASS_E = 0x7FB238, 0xC0C040  # the grass tint west of x = 12 and east of it (per column)
FOLIAGE, WATER = 0x48B518, 0x3F76E4


def scene():
    ids = [[[0] * SX for _ in range(SZ)] for _ in range(SY)]  # [y][z][x]

    def put(x, y, z, s):
        ids[y][z][x] = s

    for z in range(SZ):
        for x in range(SX):
            put(x, 0, z, 1)  # a stone floor
    # band A (z = 2): shapes
    put(1, 1, 2, 2), put(2, 1, 2, 3), put(3, 1, 2, 2)  # grass, path, grass
    put(5, 1, 2, 4)  # slab
    put(7, 1, 2, 5)  # fence post
    put(14, 1, 2, 8)  # leaves over the floor
    put(16, 1, 2, 10)  # water in a stone basin
    for x, z in ((15, 2), (17, 2), (16, 1), (16, 3)):
        put(x, 1, z, 1)
    put(9, 1, 2, 2), put(13, 1, 2, 2)  # grass either side of the tint change
    # band B (z = 5..7): a closed tunnel lit by a lantern at its west end
    for x in range(0, 12):
        for y in (1, 2):
            put(x, y, 5, 1), put(x, y, 7, 1)
        put(x, 3, 6, 1), put(x, 3, 5, 1), put(x, 3, 7, 1)
    for y in (1, 2):
        put(0, y, 6, 1), put(11, y, 6, 1)
    put(1, 1, 6, 7)
    # band C (z = 10..14): smooth lighting beside a wall, a floating block, glass against glass
    for z in range(10, 15):
        put(4, 1, z, 1)
    put(8, 3, 13, 1)
    put(18, 2, 12, 11), put(19, 2, 12, 11)
    for y in (1, 2, 3):
        for z in (11, 12, 13):
            put(21, y, z, 9)
    # band D (z = 16..20): a stained pane in front of a wool wall
    put(11, 2, 17, 6)
    for x in (10, 11, 12):
        for y in (1, 2, 3, 4):
            put(x, y, 19, 9)
    return ids


def write_volume(path, ids, light=None):
    flags = 4 | (1 if light else 0)
    b = b"ALV2" + struct.pack("<6iI", *ORIGIN, SX, SY, SZ, flags)
    b += struct.pack(f"<{SX * SY * SZ}I", *[ids[y][z][x] for y in range(SY) for z in range(SZ) for x in range(SX)])
    if light:
        b += bytes(light[y][z][x] for y in range(SY) for z in range(SZ) for x in range(SX))
    for z in range(SZ):
        for x in range(SX):
            b += struct.pack("<4I", GRASS_W if x < 12 else GRASS_E, FOLIAGE, WATER, 0xA0A069)
    path.write_bytes(b)
    # noon, the default gamma: the lightmap the checks compute below
    path.with_suffix(".json").write_text('{"grass": "7FB238", "foliage": "48B518", "water": "3F76E4"}\n')


# ---- expectations ---------------------------------------------------------------------------------------------------

def lightmap(block, sky, gamma=0.5):
    """lightmap.fsh at noon (sky factor 1, white sky light, ambient 0A0A0A, block tint FFD88C, block factor 1.4)."""
    br = lambda l: l / (4 - 3 * l)
    bl, sl = block / 15, sky / 15
    tint = (1, 216 / 255, 140 / 255)
    mixf = 0.9 * (2 * bl - 1) ** 2
    c = [10 / 255 + br(sl) + (t + (1 - t) * mixf) * br(bl) * 1.4 for t in tint]
    c = [min(max(v, 0), 1) for v in c]
    m = max(c)
    k = (1 - (1 - m) ** 4) / m
    return [round((v + (v * k - v) * gamma) * 255) / 255 for v in c]


def W(x, y, z):
    return (x + ORIGIN[0], y + ORIGIN[1], z + ORIGIN[2])


def run(exe, dirp, rays, extra=()):
    spec = ";".join(",".join(f"{v:g}" for v in (*W(*o), *d)) for o, d in rays)
    r = subprocess.run([str(exe), f"quads={dirp / 'quads.bin'}", f"volume={dirp / 'scene.alv2'}", f"ray={spec}", *extra],
                       capture_output=True, text=True, timeout=120)
    if r.returncode:
        raise SystemExit(f"atlas_truth failed: {r.stderr}")
    return [json.loads(l) for l in r.stdout.splitlines() if l.startswith("{")]


def main():
    argv = sys.argv[1:]
    keep = None
    if "--keep" in argv:
        i = argv.index("--keep")
        keep = argv[i + 1]
        del argv[i:i + 2]
    exe = Path(argv[0]) if argv else ROOT / "native/build/Release/atlas_truth.exe"
    tmp = Path(keep) if keep else Path(tempfile.mkdtemp(prefix="atlas_truth_test_"))
    tmp.mkdir(parents=True, exist_ok=True)
    build(tmp)
    ids = scene()
    write_volume(tmp / "scene.alv2", ids)
    down = (0, -1, 0)
    cases = [  # (name, origin (local), direction)
        ("path top at 15/16", (2.5, 5, 2.5), down),
        ("grass top at 1", (3.5, 5, 2.5), down),
        ("slab top at 1/2", (5.5, 5, 2.5), down),
        ("fence post top", (7.5, 5, 2.5), down),
        ("beside the fence post: the floor", (7.2, 5, 2.5), down),
        ("leaf texel", (14.25, 5, 2.5), down),
        ("leaf hole: the floor under it", (14.75, 5, 2.5), down),
        ("water surface", (16.5, 5, 2.5), down),
        ("pane over wool", (11.5, 2.5, 15), (0, 0, 1)),
        ("glass against glass (from inside)", (18.5, 2.97, 12.5), (1, 0, 0)),
        ("open floor", (2.5, 5, 12.5), down),
        ("floor beside a wall", (3.9, 5, 12.5), down),
        ("north face of a floating block", (8.5, 3.5, 11), (0, 0, 1)),
        ("lantern", (1.5, 2.9, 6.5), down),
        ("grass west (tint 7FB238)", (9.5, 5, 2.5), down),
        ("grass east (tint C0C040)", (13.5, 5, 2.5), down),
    ] + [(f"tunnel floor {x}", (x + 0.5, 2.5, 6.5), down) for x in range(2, 11)]
    res = run(exe, tmp, [(o, d) for _, o, d in cases])
    by = {name: r for (name, _, _), r in zip(cases, res)}
    fails = []

    def check(name, ok, detail):
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")
        if not ok:
            fails.append(name)

    def first(name):
        return by[name]["layers"][0] if by[name]["layers"] else {}

    def y_of(name):
        return first(name).get("point", [0, 0, 0])[1] - ORIGIN[1]

    check("path top at 15/16", abs(y_of("path top at 15/16") - (1 + 15 / 16)) < 1e-3, f"y = {y_of('path top at 15/16'):.4f} (1.9375)")
    check("grass top at 1", abs(y_of("grass top at 1") - 2) < 1e-3, f"y = {y_of('grass top at 1'):.4f} (2)")
    check("slab top at 1/2", abs(y_of("slab top at 1/2") - 1.5) < 1e-3, f"y = {y_of('slab top at 1/2'):.4f} (1.5)")
    check("fence post top", abs(y_of("fence post top") - 2) < 1e-3 and "fence" in first("fence post top")["state"],
          f"y = {y_of('fence post top'):.4f} on {first('fence post top').get('state')}")
    check("beside the fence post", abs(y_of("beside the fence post: the floor") - 1) < 1e-3,
          f"y = {y_of('beside the fence post: the floor'):.4f} (1: the floor, the post is 4/16 wide)")
    lt = first("leaf texel")
    leaf = [0x90 / 255 * c for c in (0x48 / 255, 0xB5 / 255, 0x18 / 255)]
    check("leaves: an opaque texel, foliage tinted", abs(y_of("leaf texel") - 2) < 1e-3 and lt["layer"] == "cutout",
          f"y = {y_of('leaf texel'):.3f}, texel {lt['texel'][:3]}, rgb {lt['rgb']}")
    check("leaves: a cutout hole shows the floor", abs(y_of("leaf hole: the floor under it") - 1) < 1e-3,
          f"y = {y_of('leaf hole: the floor under it'):.3f} (1)")
    w = by["water surface"]
    check("water: the surface at 8/9 (- 0.001), translucent, then the basin floor",
          len(w["layers"]) == 2 and abs(w["layers"][0]["point"][1] - ORIGIN[1] - (1 + 8 / 9 - 0.001)) < 1e-3
          and w["layers"][0]["layer"] == "translucent" and abs(w["layers"][1]["point"][1] - ORIGIN[1] - 1) < 1e-3,
          f"surface y = {w['layers'][0]['point'][1] - ORIGIN[1]:.4f}, then {w['layers'][1]['point'][1] - ORIGIN[1] if len(w['layers']) > 1 else None}")
    a = 0xB4 / 255
    blend = [a * w["layers"][0]["rgb"][i] + (1 - a) * w["layers"][1]["rgb"][i] for i in range(3)]
    check("water: blended front to back", all(abs(blend[i] - w["rgb"][i]) < 0.6 for i in range(3)), f"{w['rgb']} vs {blend}")
    wt = w["layers"][0]["rgb"]
    exp_w = [0xC0 / 255 * 255 * c / 255 * 1.0 for c in (0x3F, 0x76, 0xE4)]  # texel x water tint x up shade (1) x full light
    check("water: tinted by the column's water colour", all(abs(wt[i] - exp_w[i]) < 1.5 for i in range(3)), f"{wt} vs {exp_w}")
    p = by["pane over wool"]
    ok = len(p["layers"]) == 2 and p["layers"][0]["layer"] == "translucent" and "wool" in p["layers"][1]["state"] and \
        abs(p["layers"][0]["point"][2] - ORIGIN[2] - (17 + 7 / 16)) < 1e-3 and abs(p["layers"][1]["point"][2] - ORIGIN[2] - 19) < 1e-3
    a = 0x80 / 255
    blend = [a * p["layers"][0]["rgb"][i] + (1 - a) * p["layers"][1]["rgb"][i] for i in range(3)] if len(p["layers"]) == 2 else [0, 0, 0]
    check("pane: hit at z = 17 + 7/16, then the wool at 19", ok, f"layers {[l['state'] for l in p['layers']]}")
    check("pane: alpha 0x80 over the wool", all(abs(blend[i] - p["rgb"][i]) < 0.6 for i in range(3)), f"{p['rgb']} vs {blend}")
    g = by["glass against glass (from inside)"]
    check("glass: the face between two glass blocks is culled (skipRendering), the wool behind shows",
          bool(g["layers"]) and "wool" in g["layers"][0]["state"], f"first hit {g['layers'][0]['state'] if g['layers'] else 'sky'}")
    of = first("open floor")
    check("smooth lighting: an open floor is fully bright", abs(of["shade"] - 1.0) < 0.01 and abs(of["sky"] - 15) < 0.01,
          f"shade {of['shade']:.4f}, sky {of['sky']:.2f}")
    fw = first("floor beside a wall")
    # the east vertices see the wall (0.2) at a side and a corner: (0.2 + 1 + 0.2 + 1) / 4 = 0.6; west 1.0; at x = 0.9
    # the triangles interpolate floor(0.6 * 255) and 255
    exp = (0.9 * math.floor(0.6 * 255) + 0.1 * 255) / 255
    tx = fw["texel"][0] * 255
    check("smooth lighting: the floor 0.1 from a wall", abs(fw["shade"] - 0.64) < 0.005 and abs(fw["rgb"][0] - tx * exp) < 0.6,
          f"shade {fw['shade']:.4f} (0.64), rgb {fw['rgb'][0]:.2f} ({tx * exp:.2f})")
    nf = first("north face of a floating block")
    tx = nf["texel"][0] * 255
    check("face shade: north 0.8", abs(nf["shade"] - 0.8) < 1e-3 and nf["dir"] == NORTH and abs(nf["rgb"][0] - math.floor(0.8 * 255) / 255 * tx) < 0.6,
          f"shade {nf['shade']:.4f}, rgb {nf['rgb'][0]:.2f} ({tx:.0f} x 204/255 = {tx * 204 / 255:.2f})")
    ln = first("lantern")
    lm = lightmap(15, 0)
    check("lantern: lit by its own emission (block 15, sky 0), flat", abs(ln["blockLight"] - 15) < 1e-3 and ln["lighting"] == 0
          and abs(ln["rgb"][0] - 0xE0 * lm[0]) < 0.6, f"block {ln['blockLight']:.2f}, sky {ln['sky']:.2f}, rgb {ln['rgb']} (lightmap {lm})")
    gw, ge = first("grass west (tint 7FB238)"), first("grass east (tint C0C040)")
    ew = [0xA0 * c / 255 for c in (0x7F, 0xB2, 0x38)]
    ee = [0xA0 * c / 255 for c in (0xC0, 0xC0, 0x40)]
    check("tint per column", all(abs(gw["rgb"][i] - ew[i]) < 1.5 and abs(ge["rgb"][i] - ee[i]) < 1.5 for i in range(3)),
          f"west {gw['rgb']} ({ew}), east {ge['rgb']} ({ee})")
    # the tunnel: block light 15 at the lantern, one less a block (the air above floor block x holds L = 16 - x); the
    # floor's face corners blend that air with the air east (L - 1) or west (L + 1) of it, the walls raised to the open
    # minimum: east vertices L - 3/4, west L + 1/4, so L - 1/4 at the face centre; beside the end wall (x = 10) the
    # east corners have only the inside open: L there, so L + 1/8
    ok, vals = True, []
    for x in range(2, 11):
        f = first(f"tunnel floor {x}")
        vals.append(round(f["blockLight"], 3))
        ok &= abs(f["blockLight"] - (16 - x + (0.125 if x == 10 else -0.25))) < 0.01 and f["sky"] < 0.01
    check("block light falls off a level a block from the lantern (smooth: L - 1/4 at face centres), no sky in the tunnel",
          ok, f"block light {vals} (13.75 .. 6.75, then 6.125 beside the end wall)")
    # a camera frame of the whole scene, for a look
    r = subprocess.run([str(exe), f"quads={tmp / 'quads.bin'}", f"volume={tmp / 'scene.alv2'}", f"out={tmp / 'frame.png'}",
                        "eye=112,66.5,190", "yaw=0", "pitch=35", "fov=70", "w=640", "h=360"], capture_output=True, text=True,
                       timeout=120)
    check("camera frame", r.returncode == 0 and (tmp / "frame.png").exists() and (tmp / "frame_mask.png").exists(),
          (r.stdout.strip().splitlines() or [""])[-1][:160])
    print(f"{len(fails)} failure(s); inputs and frame in {tmp}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
