"""Unpack the mod's baked cloud-noise atlas (8x8 slices of 66x66 with wrap gutters) into a raw 64^3 RGBA8 volume.

The atlas is Skycraft's own asset (tools/shaders/bake_cloud_noise.py); aether_core samples the same values the GLSL
clouds do. Output: native/data/cloud_noise.bin, 64*64*64*4 bytes, index ((z * 64 + y) * 64 + x) * 4, RGBA.
In game, Java passes the same atlas pixels through aether_set_cloud_noise instead.

usage: py -3 -I native/tools/export_cloud_noise.py [repo_root] [out_file]
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

SIZE, TILE = 64, 66


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2])
    out = Path(sys.argv[2] if len(sys.argv) > 2 else root / "native/data/cloud_noise.bin")
    atlas = np.asarray(Image.open(root / "mod/src/main/resources/assets/skycraft/textures/environment/cloud_noise_volume.png")
                       .convert("RGBA"))
    vol = np.empty((SIZE, SIZE, SIZE, 4), np.uint8)
    for z in range(SIZE):
        x0, y0 = z % 8 * TILE + 1, z // 8 * TILE + 1
        vol[z] = atlas[y0:y0 + SIZE, x0:x0 + SIZE]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(vol.tobytes())
    print(f"wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
