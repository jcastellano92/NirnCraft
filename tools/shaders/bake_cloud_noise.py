"""Bake an original periodic 64-cubed cloud-noise volume into an 8x8 slice atlas.

Each slice has wrapping gutters for hardware bilinear XY filtering. The shader
interpolates adjacent slices for true trilinear XYZ filtering (two texture reads).
R: cellular billows; G: fine gradient noise; B: gradient fBm; A: opaque.
Run: py -3 -I tools/shaders/bake_cloud_noise.py
"""
from pathlib import Path
import numpy as np
from PIL import Image

SIZE = 64
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "mod/src/main/resources/assets/skycraft/textures/environment/cloud_noise_volume.png"


def gradient_noise(frequency, seed):
    rng = np.random.default_rng(seed)
    gradients = rng.normal(size=(frequency, frequency, frequency, 3)).astype(np.float32)
    gradients /= np.linalg.norm(gradients, axis=3, keepdims=True)
    coords = np.moveaxis(np.indices((SIZE, SIZE, SIZE), dtype=np.float32), 0, -1) * (frequency / SIZE)
    cell = np.floor(coords).astype(np.int32)
    frac = coords - cell
    fade = frac * frac * frac * (frac * (frac * 6 - 15) + 10)
    result = np.zeros((SIZE, SIZE, SIZE), dtype=np.float32)
    for z in range(2):
        for y in range(2):
            for x in range(2):
                corner = np.array([z, y, x])
                at = (cell + corner) % frequency
                g = gradients[at[..., 0], at[..., 1], at[..., 2]]
                weight = np.prod(np.where(corner, fade, 1 - fade), axis=3)
                result += weight * np.sum(g * (frac - corner), axis=3)
    return np.clip(0.5 + result * 0.8, 0, 1)


def cellular_noise():
    frequency = 8
    jitter = np.random.default_rng(6283).uniform(0.1, 0.9, (frequency, frequency, frequency, 3)).astype(np.float32)
    coords = np.moveaxis(np.indices((SIZE, SIZE, SIZE), dtype=np.float32), 0, -1) * (frequency / SIZE)
    cell = np.floor(coords).astype(np.int32)
    frac = coords - cell
    nearest = np.full((SIZE, SIZE, SIZE), 10, dtype=np.float32)
    for z in range(-1, 2):
        for y in range(-1, 2):
            for x in range(-1, 2):
                corner = np.array([z, y, x])
                at = (cell + corner) % frequency
                delta = corner + jitter[at[..., 0], at[..., 1], at[..., 2]] - frac
                nearest = np.minimum(nearest, np.sum(delta * delta, axis=3))
    return np.clip(1 - np.sqrt(nearest) / 1.25, 0, 1)


def main():
    coarse, fine = gradient_noise(16, 9183), gradient_noise(32, 2367)
    volume = np.stack((cellular_noise(), fine, coarse * 0.7 + fine * 0.3, np.ones_like(coarse)), axis=3)
    volume = np.rint(volume * 255).astype(np.uint8)
    tile = SIZE + 2
    atlas = np.empty((tile * 8, tile * 8, 4), dtype=np.uint8)
    for z in range(SIZE):
        # Volume axes are Z,Y,X; gutters wrap each XY slice, never its neighbours.
        part = np.pad(volume[z], ((1, 1), (1, 1), (0, 0)), mode="wrap")
        x, y = z % 8 * tile, z // 8 * tile
        atlas[y:y + tile, x:x + tile] = part
    OUT.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(atlas).save(OUT)
    OUT.with_name(OUT.name + ".mcmeta").write_text('{"texture":{"blur":true,"clamp":true}}\n')
    print(f"Baked {OUT}: {OUT.stat().st_size} bytes, {atlas.shape[1]}x{atlas.shape[0]}, original periodic volume")


if __name__ == "__main__":
    main()
