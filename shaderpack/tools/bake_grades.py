#!/usr/bin/env python3
"""Bake the NirnCraft Atlas pack's regional colour grades: film-style 3D LUTs per climate / region and the world map
that picks them (final.fsh, lib/grade.glsl).

Tamriel's layout is fixed, so the grade of every place is baked once from the mod's own region data
(mod/src/main/resources/nirncraft/tamriel/regions.png + regions.json, the same raster TamrielMap / Regions read):

  shaders/textures/grade_lut.png   1024 x 32*G RGBA8: grade g's 32^3 LUT in rows g*32 .. g*32+31; within it texel
                                   (b*32 + r, g*32 + gr) holds the graded sRGB of input (r, gr, b) / 31
  shaders/textures/grade_map.png   W x H RGBA8 over the map, GRADE_CELL blocks per texel: r = grade index
  shaders/lib/grade_table.glsl     the constants the shader needs (generated: grade count, map origin, cell, size)
  shaderpack/build/lab/*.rgba      raw copies for pack_lab (u32 w, u32 h, then RGBA8 rows)

Grades are subtle and film-like (white balance, hue-band moves and chroma in OkLCh, split toning, a gentle S-curve in
lightness); final.fsh blends the four nearest map texels by camera position and eases the grade with weather and
night. --preview IMAGE writes every grade applied to an image (a contact sheet) next to the PNGs' build copies.

usage: py -3 -I shaderpack/tools/bake_grades.py [--preview some.png]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import struct

import numpy as np
from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PACK = ROOT / "shaderpack" / "InfiniteATLAS" / "shaders"
TAMRIEL = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft" / "tamriel"
LAB = ROOT / "shaderpack" / "build" / "lab"

# TamrielMap: block 0,0 is Whiterun; the raster's bounds (must match TamrielMap.java / tamriel_vectors.json meta)
MAP_MIN_X, MAP_MIN_Z, MAP_CELL = -15360, -5120, 32
GRADE_CELL = 256  # blocks per grade-map texel: the grade eases over about this far
N = 32            # LUT size per axis


# ---------------------------------------------------------------- the grades
# Each: white balance (temp > 0 warmer, tint > 0 greener), global chroma, hue bands (centre deg, width deg, hue shift
# deg, chroma x, lightness +), split toning (shadow a, b; highlight a, b in Oklab units), contrast (S-curve, 0 none),
# lift (raises black). Oklab hues: red ~25, orange ~60, yellow ~105, foliage ~130, cyan ~195, sky blue ~255, violet ~310.
def grade(name, temp=0.0, tint=0.0, chroma=1.0, bands=(), shadows=(0.0, 0.0), highlights=(0.0, 0.0), contrast=0.0,
          lift=0.0):
    return dict(name=name, temp=temp, tint=tint, chroma=chroma, bands=bands, shadows=shadows, highlights=highlights,
                contrast=contrast, lift=lift)


GRADES = [
    grade("neutral"),
    # Whiterun's tundra: golden grass, warm light, cool blue shadows
    grade("whiterun", temp=0.25, chroma=0.97, bands=((128, 30, -6, 0.95, 0.0), (95, 25, 0, 1.06, 0.0)),
          shadows=(-0.004, -0.010), highlights=(0.002, 0.008), contrast=0.10),
    # Falkreath's pine forest: deep cold greens towards teal, misty blue shadows
    grade("falkreath", temp=-0.25, chroma=0.92, bands=((135, 35, 8, 1.02, -0.012), (100, 25, 6, 0.9, 0.0)),
          shadows=(-0.010, -0.012), highlights=(-0.002, 0.002), contrast=0.12),
    # the Rift's autumn birches: amber and gold, warm shadows
    grade("rift", temp=0.35, chroma=1.0, bands=((75, 40, -4, 1.12, 0.0), (128, 25, -10, 0.92, 0.0)),
          shadows=(0.004, 0.002), highlights=(0.004, 0.012), contrast=0.08),
    # the cold northern holds: blue-grey, washed
    grade("cold_holds", temp=-0.45, chroma=0.82, bands=((130, 35, 6, 0.88, 0.0),),
          shadows=(-0.008, -0.016), highlights=(-0.002, -0.004), contrast=0.10, lift=0.004),
    # frozen wastes: icy, near monochrome, lifted
    grade("frozen", temp=-0.6, chroma=0.70, bands=((255, 40, 0, 1.08, 0.0),),
          shadows=(-0.010, -0.020), highlights=(-0.004, -0.006), contrast=0.04, lift=0.008),
    # the Reach: red rock, olive grass, harder light
    grade("reach", temp=0.15, chroma=0.95, bands=((30, 30, 0, 1.10, 0.0), (125, 30, -8, 0.82, -0.01)),
          shadows=(0.004, -0.002), highlights=(0.004, 0.006), contrast=0.16),
    # High Rock: soft maritime greys and greens
    grade("high_rock", temp=-0.08, chroma=0.90, bands=((130, 30, 4, 0.95, 0.0),),
          shadows=(-0.006, -0.004), highlights=(0.0, 0.004), contrast=-0.04, lift=0.004),
    # Hammerfell's deserts: golden, sun-baked, deep sky
    grade("hammerfell_desert", temp=0.6, chroma=1.04, bands=((70, 45, -4, 1.14, 0.0), (255, 30, -6, 0.92, 0.0),
                                                            (128, 25, -14, 0.8, 0.0)),
          shadows=(0.002, -0.006), highlights=(0.006, 0.016), contrast=0.16),
    # Hammerfell's green coasts and hills: warm and lush
    grade("hammerfell_green", temp=0.3, chroma=1.04, bands=((128, 30, -4, 1.04, 0.0),),
          shadows=(0.0, -0.004), highlights=(0.004, 0.010), contrast=0.08),
    # Cyrodiil's heartland (the Oblivion look): saturated meadow greens, warm, bright
    grade("cyrodiil", temp=0.18, chroma=1.08, bands=((130, 30, -3, 1.12, 0.006), (255, 30, 0, 1.05, 0.0)),
          shadows=(0.0, -0.004), highlights=(0.003, 0.008), contrast=0.06, lift=0.002),
    # the Gold Coast: warm sea light
    grade("gold_coast", temp=0.32, chroma=1.04, bands=((240, 40, -4, 1.06, 0.0), (90, 30, 0, 1.06, 0.0)),
          shadows=(-0.004, -0.006), highlights=(0.004, 0.012), contrast=0.06),
    # Morrowind's ashlands: ash grey-brown, drained, faint red
    grade("ashlands", temp=0.12, chroma=0.70, bands=((130, 40, -16, 0.6, -0.01), (40, 35, 0, 1.10, 0.0)),
          shadows=(0.006, 0.002), highlights=(0.004, 0.010), contrast=0.12),
    # Red Mountain: smouldering, crushed, ember-orange highlights
    grade("red_mountain", temp=0.35, chroma=0.78, bands=((40, 35, -4, 1.25, 0.0), (130, 40, -20, 0.5, -0.02)),
          shadows=(0.010, 0.002), highlights=(0.010, 0.014), contrast=0.20),
    # Morrowind's green districts: alien teal shadows, mauve, warm light
    grade("morrowind_green", temp=0.08, chroma=1.0, bands=((130, 30, -6, 0.95, 0.0), (330, 40, 0, 1.12, 0.0)),
          shadows=(0.006, -0.010), highlights=(0.002, 0.008), contrast=0.08),
    # Black Marsh and the swamps: humid, olive-yellow murk, lifted haze
    grade("swamp", temp=0.1, tint=0.35, chroma=0.90, bands=((125, 35, -12, 0.95, -0.01), (255, 35, -14, 0.85, 0.0)),
          shadows=(-0.008, 0.004), highlights=(-0.002, 0.012), contrast=-0.06, lift=0.010),
    # jungles: deep saturated greens
    grade("jungle", temp=0.05, tint=0.15, chroma=1.06, bands=((135, 35, 2, 1.18, -0.01),),
          shadows=(-0.008, 0.0), highlights=(0.002, 0.008), contrast=0.10),
    # Elsweyr's savanna: amber grass, red earth
    grade("elsweyr", temp=0.5, chroma=1.02, bands=((85, 40, -6, 1.12, 0.0), (125, 25, -12, 0.85, 0.0)),
          shadows=(0.004, -0.004), highlights=(0.006, 0.014), contrast=0.12),
    # Summerset and the southern isles: clean, bright, soft gold
    grade("summerset", temp=0.15, chroma=1.04, bands=((255, 40, 0, 1.06, 0.0), (330, 40, 0, 1.08, 0.0)),
          shadows=(-0.002, -0.008), highlights=(0.004, 0.010), contrast=0.0, lift=0.004),
    # interiors (the place the player is in, from the mod: lib/grade.glsl interiorGrade). Nordic barrows and tombs:
    # cold blue-grey stone, deep shadows, the torches' warmth only in the highlights
    grade("interior_barrow", temp=-0.35, chroma=0.75, shadows=(-0.006, -0.014), highlights=(0.004, 0.008),
          contrast=0.14),
    # Dwemer ruins: brass and amber, hard contrast
    grade("interior_dwemer", temp=0.4, chroma=0.95, bands=((40, 40, -4, 1.15, 0.0),), shadows=(0.004, -0.004),
          highlights=(0.008, 0.014), contrast=0.16),
    # caves and mines: earthy, damp teal shadows
    grade("interior_cave", temp=0.05, chroma=0.85, shadows=(-0.006, -0.006), highlights=(0.004, 0.010), contrast=0.10),
    # forts, towers, bandit holds: neutral stone, warm torchlight
    grade("interior_fort", temp=0.15, chroma=0.88, shadows=(-0.004, -0.008), highlights=(0.004, 0.012), contrast=0.12),
    # Oblivion and Daedric places: smouldering red, crushed greens
    grade("interior_oblivion", temp=0.3, chroma=0.9, bands=((20, 40, 0, 1.2, 0.0), (130, 40, -20, 0.5, 0.0)),
          shadows=(0.012, 0.002), highlights=(0.008, 0.010), contrast=0.22),
    # temples, shrines, Sovngarde's hall: soft gold, lifted
    grade("interior_sacred", temp=0.2, chroma=0.95, highlights=(0.004, 0.012), contrast=0.0, lift=0.006),
    # homes, manors and inns: the hearth's warmth
    grade("interior_home", temp=0.3, chroma=1.0, shadows=(0.004, 0.0), highlights=(0.006, 0.012), contrast=0.06),
]
GRADE_INDEX = {g["name"]: i for i, g in enumerate(GRADES)}

# LocationKind.icon (mod/src/main/java/com/nirncraft/world/LocationKind.java) -> interior grade
INTERIOR_GRADE = {1: "interior_barrow", 2: "interior_cave", 3: "interior_cave", 4: "interior_fort", 5: "interior_fort",
                  7: "interior_barrow", 8: "interior_dwemer", 9: "interior_sacred", 11: "interior_oblivion",
                  12: "interior_oblivion", 14: "interior_home", 15: "interior_sacred", 16: "interior_home",
                  18: "interior_sacred", 19: "interior_barrow", 20: "interior_barrow", 21: "interior_fort",
                  22: "interior_oblivion", 30: "interior_oblivion"}

# region id -> grade (regions.json ids); anything unlisted falls back by its climate ("weather")
REGION_GRADE = {
    "skyrim/whiterun": "whiterun", "skyrim/falkreath": "falkreath", "skyrim/the_rift": "rift",
    "skyrim/eastmarch": "cold_holds", "skyrim/the_pale": "cold_holds", "skyrim/hjaalmarch": "cold_holds",
    "skyrim/haafingar": "cold_holds", "skyrim/winterhold": "frozen", "skyrim/the_reach": "reach",
    "high_rock/wrothgar": "cold_holds", "cyrodiil/jerall_mountains": "cold_holds",
    "hammerfell/sentinel": "hammerfell_desert", "hammerfell/alikr_desert": "hammerfell_desert",
    "hammerfell/dragontail": "hammerfell_desert", "hammerfell/craglorn": "hammerfell_desert",
    "yokuda/yokudan_desert": "hammerfell_desert",
    "hammerfell/bangkorai": "hammerfell_green", "hammerfell/hews_bane": "hammerfell_green",
    "hammerfell/stros_mkai": "hammerfell_green",
    "cyrodiil/gold_coast": "gold_coast", "sea/abecean_sea": "gold_coast",
    "cyrodiil/blackwood": "swamp", "morrowind/bitter_coast": "swamp",
    "morrowind/red_mountain": "red_mountain", "sea/inner_sea": "ashlands", "sea/sea_of_ghosts": "frozen",
    "sea/iliac_bay": "high_rock", "sea/topal_bay": "summerset", "sea/eltheric_ocean": "summerset",
    "valenwood/reapers_march": "cyrodiil",
}
PROVINCE_GRADE = {"high_rock": "high_rock", "cyrodiil": "cyrodiil", "black_marsh": "swamp", "elsweyr": "elsweyr",
                  "summerset": "summerset", "atmora": "frozen", "pyandonea": "summerset", "thras": "summerset"}
CLIMATE_GRADE = {"ash": "ashlands", "temperate": "morrowind_green", "maritime": "morrowind_green", "swamp": "swamp",
                 "jungle": "jungle", "tropical": "summerset", "desert": "hammerfell_desert", "savanna": "elsweyr",
                 "frozen": "frozen", "skyrim_cold": "cold_holds", "skyrim_temperate": "whiterun"}


def region_grade(r: dict) -> str:
    rid, prov, climate = r["id"], r["province"], r["weather"]
    if rid in REGION_GRADE:
        return REGION_GRADE[rid]
    if prov == "sea":
        return "neutral"
    if prov == "morrowind":
        return CLIMATE_GRADE.get(climate, "morrowind_green")
    if prov == "elsweyr" and climate == "jungle":
        return "jungle"
    if prov in ("valenwood", "akavir") and climate == "jungle":
        return "jungle"
    if prov in PROVINCE_GRADE:
        return PROVINCE_GRADE[prov]
    return CLIMATE_GRADE.get(climate, "neutral")


# ---------------------------------------------------------------- colour maths (numpy, vectorised)
def srgb_to_lin(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])
M1i, M2i = np.linalg.inv(M1), np.linalg.inv(M2)


def lin_to_oklab(c):
    return np.cbrt(c @ M1.T) @ M2.T


def oklab_to_lin(l):
    return ((l @ M2i.T) ** 3) @ M1i.T


def apply_grade(srgb: np.ndarray, g: dict) -> np.ndarray:
    """srgb: (..., 3) in 0..1 (display-referred, after the tone curve) -> graded sRGB"""
    lin = srgb_to_lin(srgb)
    y0 = lin @ np.array([0.2126, 0.7152, 0.0722])
    wb = np.array([1.0 + 0.10 * g["temp"], 1.0 + 0.06 * g["tint"], 1.0 - 0.10 * g["temp"]])
    lin = lin * wb
    y1 = lin @ np.array([0.2126, 0.7152, 0.0722])
    lin = lin * (y0 / np.maximum(y1, 1e-9))[..., None]  # white balance keeps the luminance
    lab = lin_to_oklab(np.maximum(lin, 0.0))
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.hypot(a, b)
    h = np.degrees(np.arctan2(b, a))
    neutral = np.clip((C - 0.015) / 0.04, 0.0, 1.0)  # greys are left alone by the hue bands
    for centre, width, shift, cs, ls in g["bands"]:
        dh = (h - centre + 180.0) % 360.0 - 180.0
        w = np.exp(-(dh / width) ** 2) * neutral
        h = h + shift * w
        C = C * (1.0 + (cs - 1.0) * w)
        L = L + ls * w
    C = C * g["chroma"]
    a, b = C * np.cos(np.radians(h)), C * np.sin(np.radians(h))
    ws = (1.0 - np.clip(L / 0.6, 0.0, 1.0)) ** 2
    wh = np.clip((L - 0.55) / 0.45, 0.0, 1.0) ** 2
    a = a + g["shadows"][0] * ws + g["highlights"][0] * wh
    b = b + g["shadows"][1] * ws + g["highlights"][1] * wh
    k = g["contrast"]
    L = L + k * (L - 0.5) * L * (1.0 - L) * 4.0
    L = g["lift"] + L * (1.0 - g["lift"])
    out = oklab_to_lin(np.stack([L, a, b], axis=-1))
    return lin_to_srgb(out)


def bake_luts() -> np.ndarray:
    v = np.arange(N) / (N - 1)
    b, gg, r = np.meshgrid(v, v, v, indexing="ij")  # [b][g][r]
    grid = np.stack([r, gg, b], axis=-1)
    img = np.zeros((N * len(GRADES), N * N, 4), np.uint8)
    for gi, g in enumerate(GRADES):
        out = apply_grade(grid, g)  # [b][g][r][3]
        block = np.transpose(out, (1, 0, 2, 3)).reshape(N, N * N, 3)  # row = g, column = b*32 + r
        img[gi * N:(gi + 1) * N, :, :3] = np.clip(np.round(block * 255.0), 0, 255).astype(np.uint8)
        img[gi * N:(gi + 1) * N, :, 3] = 255
    return img


def bake_map() -> tuple[np.ndarray, dict]:
    regions = json.loads((TAMRIEL / "regions.json").read_text(encoding="utf-8"))
    raster = np.array(Image.open(TAMRIEL / "regions.png"))
    if raster.ndim == 3:
        raster = raster[..., 0]
    grade_of_region = np.array([GRADE_INDEX[region_grade(r)] for r in regions], np.int32)
    is_sea = np.array([r["province"] == "sea" for r in regions], bool)
    f = GRADE_CELL // MAP_CELL
    H0, W0 = raster.shape
    H, W = (H0 + f - 1) // f, (W0 + f - 1) // f
    pad = np.zeros((H * f, W * f), raster.dtype)
    pad[:H0, :W0] = raster
    blocks = pad.reshape(H, f, W, f).transpose(0, 2, 1, 3).reshape(H, W, f * f)
    out = np.zeros((H, W), np.int32)
    land = np.zeros((H, W), bool)
    for y in range(H):
        for x in range(W):
            ids = blocks[y, x]
            ids = ids[ids < len(regions)]
            land_ids = ids[~is_sea[ids]]
            if land_ids.size >= ids.size * 0.25 and land_ids.size > 0:
                vals, counts = np.unique(grade_of_region[land_ids], return_counts=True)
                out[y, x] = vals[np.argmax(counts)]
                land[y, x] = True
            else:
                vals, counts = np.unique(grade_of_region[ids] if ids.size else np.array([0]), return_counts=True)
                out[y, x] = vals[np.argmax(counts)]
    # the sea near a coast takes the coast's grade (sailing off Skyrim stays Skyrim), out to 6 texels
    from collections import deque
    dist = np.full((H, W), 1 << 30, np.int32)
    near = np.zeros((H, W), np.int32)
    q = deque()
    for y, x in zip(*np.nonzero(land)):
        dist[y, x] = 0
        near[y, x] = out[y, x]
        q.append((y, x))
    while q:
        y, x = q.popleft()
        if dist[y, x] >= 6:
            continue
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < H and 0 <= nx < W and dist[ny, nx] > dist[y, x] + 1:
                dist[ny, nx] = dist[y, x] + 1
                near[ny, nx] = near[y, x]
                q.append((ny, nx))
    sea_near = (~land) & (dist <= 6)
    out[sea_near] = near[sea_near]
    img = np.zeros((H, W, 4), np.uint8)
    img[..., 0] = out
    img[..., 3] = 255
    stats = {GRADES[i]["name"]: int((out == i).sum()) for i in range(len(GRADES))}
    return img, stats


def write_raw(path: pathlib.Path, img: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(struct.pack("<II", img.shape[1], img.shape[0]))
        f.write(np.ascontiguousarray(img).tobytes())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=pathlib.Path, help="an sRGB image to grade with every LUT (contact sheet)")
    a = ap.parse_args()
    lut = bake_luts()
    gmap, stats = bake_map()
    tex = PACK / "textures"
    tex.mkdir(exist_ok=True)
    Image.fromarray(lut, "RGBA").save(tex / "grade_lut.png", optimize=True)
    Image.fromarray(gmap, "RGBA").save(tex / "grade_map.png", optimize=True)
    write_raw(LAB / "grade_lut.rgba", lut)
    write_raw(LAB / "grade_map.rgba", gmap)
    names = "\n".join(f"//   {i:2d} {g['name']}" for i, g in enumerate(GRADES))
    (PACK / "lib" / "grade_table.glsl").write_text(
        "// Generated by shaderpack/tools/bake_grades.py: do not edit. The regional grades (textures/grade_lut.png, one\n"
        "// 32^3 LUT per grade) and the map that picks them (textures/grade_map.png, r = grade, over Tamriel's raster).\n"
        f"{names}\n"
        "#ifndef NIRNCRAFT_GRADE_TABLE\n#define NIRNCRAFT_GRADE_TABLE\n"
        f"const int GRADE_COUNT = {len(GRADES)};\n"
        f"const vec2 GRADE_MAP_ORIGIN = vec2({MAP_MIN_X}.0, {MAP_MIN_Z}.0);  // world x, z of texel (0, 0)'s corner\n"
        f"const float GRADE_MAP_CELL = {GRADE_CELL}.0;  // blocks per texel\n"
        f"const ivec2 GRADE_MAP_SIZE = ivec2({gmap.shape[1]}, {gmap.shape[0]});\n"
        "// LocationKind.icon -> interior grade (-1: none)\n"
        "const int INTERIOR_GRADE[32] = int[](" + ", ".join(
            str(GRADE_INDEX[INTERIOR_GRADE[k]]) if k in INTERIOR_GRADE else "-1" for k in range(32)) + ");\n"
        "#endif\n", encoding="utf-8", newline="\n")
    print(f"bake_grades: {len(GRADES)} grades -> {tex / 'grade_lut.png'} ({lut.shape[1]}x{lut.shape[0]}), "
          f"map {gmap.shape[1]}x{gmap.shape[0]} texels of {GRADE_CELL} blocks")
    print("  texels per grade: " + ", ".join(f"{k} {v}" for k, v in stats.items()))
    if a.preview:
        src = np.asarray(Image.open(a.preview).convert("RGB"), np.float64) / 255.0
        h, w = src.shape[:2]
        cols = 4
        rows = (len(GRADES) + cols - 1) // cols
        sheet = np.zeros((rows * h, cols * w, 3), np.uint8)
        for i, g in enumerate(GRADES):
            out = np.clip(np.round(apply_grade(src, g) * 255.0), 0, 255).astype(np.uint8)
            sheet[(i // cols) * h:(i // cols + 1) * h, (i % cols) * w:(i % cols + 1) * w] = out
        dst = LAB / "grade_preview.png"
        Image.fromarray(sheet).save(dst)
        print(f"  preview {dst}")


if __name__ == "__main__":
    main()
