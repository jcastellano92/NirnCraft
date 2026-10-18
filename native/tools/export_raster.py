"""Decode the bundled Tamriel map rasters into one raw file for aether_core's preview terrain source.

Mirrors MapRaster.load (mod/src/main/java/com/skycraft/tamriel/terrain/MapRaster.java) channel for channel, so the
native preview reads the same macro values the Java generator starts from. The output is a cache, not source:
native/data/ is gitignored; rerun after tools/worldgen/tamriel_map.py rewrites the PNGs.

Layout (little endian): magic 'AETR', u32 version=2, u32 width, u32 height, u32 channels, i32 minX, i32 minZ,
u32 cell, then float32[height][width][channels], then u8 regions[height][width], then u32 regionCount and
u8 climate[regionCount] (CLIMATES index of each region's "weather" in regions.json).

usage: py -3 -I native/tools/export_raster.py [repo_root] [out_file]
"""
import json
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image

W, H, CELL, MIN_X, MIN_Z = 880, 672, 32, -15360, -5120
# channel order is part of the file contract (native/src/preview_source.cpp)
CHANNELS = ["elev", "coast", "riv_d", "riv_hw", "lake_sd", "water", "ridge", "rough", "crag", "dunes", "wet", "road",
            "rainshadow", "heat"]
NO_WATER = -1000.0
# climate codes, part of the file contract (native/src/preview_source.cpp)
CLIMATES = ["maritime", "skyrim_temperate", "skyrim_cold", "frozen", "desert", "tropical", "temperate", "ash", "swamp",
            "jungle", "savanna"]


def read(root, name, optional=False):
    p = root / "mod/src/main/resources/skycraft/tamriel" / name
    if not p.exists():
        if optional:
            return None
        raise SystemExit(f"missing {p}")
    im = Image.open(p)
    if im.size != (W, H):
        raise SystemExit(f"{name} is {im.size}, expected {(W, H)}")
    return np.asarray(im)


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2])
    out = Path(sys.argv[2] if len(sys.argv) > 2 else root / "native/data/tamriel_raster.bin")
    regions = read(root, "regions.png").astype(np.uint8)
    elev = read(root, "elevation.png").astype(np.float32)
    feat = read(root, "features.png").astype(np.float32)
    ter = read(root, "terrain.png").astype(np.float32)
    wat = read(root, "water.png").astype(np.int32)
    coast = read(root, "coast.png").astype(np.float32)
    rain = read(root, "rainshadow.png", True)
    heat = read(root, "heat.png", True)

    d = np.zeros((H, W, len(CHANNELS)), np.float32)
    c = {n: i for i, n in enumerate(CHANNELS)}
    d[..., c["elev"]] = elev / 32.0 - 64.0
    d[..., c["coast"]] = coast - 32768.0
    riv = (255.0 - feat[..., 0]) * 0.5
    riv[riv >= 127.5] = 400.0
    d[..., c["riv_d"]] = riv
    d[..., c["ridge"]] = feat[..., 1] / 255.0
    lake = (feat[..., 2] - 128.0) * 0.5
    lake[lake <= -64.0] = -400.0
    d[..., c["lake_sd"]] = lake
    d[..., c["road"]] = feat[..., 3] / 255.0
    d[..., c["rough"]] = ter[..., 0] / 255.0
    d[..., c["crag"]] = ter[..., 1] / 255.0
    d[..., c["dunes"]] = ter[..., 2] / 255.0
    d[..., c["wet"]] = ter[..., 3] / 255.0
    code = (wat[..., 0] << 8) | wat[..., 1]
    d[..., c["water"]] = np.where(code == 0, NO_WATER, code / 64.0 - 64.0)
    d[..., c["riv_hw"]] = wat[..., 2]
    d[..., c["rainshadow"]] = 0.0 if rain is None else rain.astype(np.float32) / 255.0
    d[..., c["heat"]] = 0.0 if heat is None else heat.astype(np.float32) / 255.0

    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as f:
        f.write(b"AETR")
        f.write(struct.pack("<IIIIiiI", 2, W, H, len(CHANNELS), MIN_X, MIN_Z, CELL))
        f.write(d.astype("<f4").tobytes())
        f.write(regions.tobytes())
        table = json.loads((root / "mod/src/main/resources/skycraft/tamriel/regions.json").read_text(encoding="utf-8"))
        climate = [0] * (max(r["index"] for r in table) + 1)
        for r in table:
            w = r.get("weather", "temperate")
            climate[r["index"]] = CLIMATES.index(w) if w in CLIMATES else CLIMATES.index("temperate")
        f.write(struct.pack("<I", len(climate)))
        f.write(bytes(climate))
    print(f"wrote {out} ({out.stat().st_size} bytes); elevation {d[..., 0].min():.1f}..{d[..., 0].max():.1f}")


if __name__ == "__main__":
    main()
