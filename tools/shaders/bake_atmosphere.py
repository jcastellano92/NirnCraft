"""Bake original RGB Rayleigh/Mie source transmittance, not a full multiple-scattering sky solver.

256 solar elevation samples x 64 heights. Curved ground blocks rays below its horizon.
All path integration is offline; the runtime decodes one filtered texture lookup.
"""
from pathlib import Path
import json
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]


def transmittance():
    radius, top = 6_360_000.0, 6_440_000.0
    height = np.linspace(0, 24_000, 64)[:, None]
    mu = np.linspace(-0.32, 1.0, 256)[None, :]
    r = radius + height
    exit_distance = -r * mu + np.sqrt((r * mu)**2 + top**2 - r**2)
    blocked = (mu < 0) & (r*r * (1 - mu*mu) < radius*radius)
    ray, mie = np.zeros((64, 256)), np.zeros((64, 256))
    for step in range(96):
        a, b = (step / 96)**2, ((step + 1) / 96)**2
        distance = exit_distance * (a + b) * 0.5
        altitude = np.maximum(0, np.sqrt(r*r + distance*distance + 2*r*mu*distance) - radius)
        width = exit_distance * (b - a)
        ray += np.exp(-altitude / 8000) * width
        mie += np.exp(-altitude / 1200) * width
    optical = ray[..., None] * np.array([5.8e-6, 13.5e-6, 33.1e-6]) + mie[..., None] * 3.9e-6
    result = np.exp(-optical)
    result[blocked] = 0
    return result


if __name__ == "__main__":
    values = transmittance()
    assert np.isfinite(values).all() and (values >= 0).all() and (values <= 1).all()
    assert values[0, -1, 0] > values[0, -1, 2]
    assert values[0, 0].max() == 0
    assert values[-1, -1].mean() > values[0, -1].mean()
    path = ROOT / "mod/src/main/resources/assets/skycraft/textures/environment/atmosphere_transmittance.png"
    Image.fromarray(np.uint8(np.clip(values**0.25, 0, 1) * 255 + 0.5), "RGB").save(path)
    path.with_name(path.name + ".mcmeta").write_text(json.dumps({"texture": {"blur": True, "clamp": True}}))
    print("Wrote", path)
