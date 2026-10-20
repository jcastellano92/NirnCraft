#!/usr/bin/env python3
"""Generates the Skyrim sky textures of the atmosphere module.

  assets/minecraft/textures/environment/celestial/moon/*.png   transparent native26.3 vanilla moon overrides
  assets/minecraft/textures/environment/moon_phases.png   preserved legacy transparent moon override
  assets/nirncraft/textures/environment/{masser,secunda}_*.png  the moons: tools/dev/moon_gen.py bakes them
  assets/nirncraft/textures/environment/masser_halo.png    faint shader-off lunar scattering fallback
  assets/nirncraft/textures/environment/galaxy.png         horizontally tiling galaxy band
  assets/nirncraft/textures/environment/clouds.png         cloud sprite atlas (4x4) for atmosphere.client.CloudRenderer
                                                          (`atmosphere.py clouds` rewrites only this one)
  assets/nirncraft/textures/particle/*.png + particles/*.json  atmospheric particle sprites (visual package):
      snowflake, ash_flake, ember, sand_dust, falling_leaf, firefly, frost_breath, marsh_wisp, dust_mote

The moons' surfaces are simulated from nothing (tools/dev/moon_gen.py: albedo, relief and the 16-phase sheets).
Requires numpy and Pillow.
"""
import json
import pathlib

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2] / "mod/src/main/resources/assets"
SKY = ROOT / "nirncraft/textures/environment"
VANILLA = ROOT / "minecraft/textures/environment"
PARTICLE_TEX = ROOT / "nirncraft/textures/particle"
PARTICLE_DEF = ROOT / "nirncraft/particles"


def periodic_noise(h, w, rng, falloff=1.8, low=1.0):
    """Tileable fractal noise in [0, 1] (FFT-filtered white noise is periodic in both axes)."""
    f = np.fft.fft2(rng.standard_normal((h, w)))
    fy = np.fft.fftfreq(h)[:, None] * h
    fx = np.fft.fftfreq(w)[None, :] * w
    k = np.sqrt(fx ** 2 + fy ** 2)
    k[0, 0] = 1.0
    f *= 1.0 / np.maximum(k, low) ** falloff
    f[0, 0] = 0
    n = np.real(np.fft.ifft2(f))
    n -= n.min()
    return n / max(n.max(), 1e-9)


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def surface(size, rng, base, dark, bright, craters, maria=0.5, strength=1.0):
    """Albedo map of a moon (size x size, unit disc mapped to the square)."""
    n1 = periodic_noise(size, size, rng, 2.2)
    n2 = periodic_noise(size, size, rng, 1.4)
    mare = smoothstep(0.45, 0.7, n1) * maria
    alb = np.ones((size, size, 3)) * np.array(base, float)
    alb = alb * (1 - mare[..., None]) + np.array(dark, float) * mare[..., None]
    n3 = periodic_noise(size, size, rng, 1.0, low=6.0)   # fine grain: regolith, not a smooth ball
    alb *= (0.85 + 0.3 * n2)[..., None] * (0.92 + 0.16 * n3)[..., None]
    yy, xx = np.mgrid[0:size, 0:size] / size * 2 - 1
    relief = (n2 - 0.5) * 0.0008
    for _ in range(craters):
        cx, cy = rng.uniform(-0.9, 0.9, 2)
        # many small craters, a few big ones, the rims worn and uneven
        r = (0.012 + rng.random() ** 3 * 0.16) * rng.uniform(0.8, 1.2)
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
        floor = np.clip(1 - d, 0, 1) ** 0.5 * 0.22 * strength
        rim = np.exp(-((d - 1.0) / 0.22) ** 2) * 0.18 * strength * (0.5 + 0.5 * n3)
        alb *= (1 - floor)[..., None]
        alb += (rim[..., None] * np.array(bright, float) * 0.6)
        relief += -r * 0.065 * np.clip(1 - d * d, 0, 1) + r * 0.025 * np.exp(-((d - 1.0) / 0.13) ** 2)
    return np.clip(alb, 0, 255), relief


def moon_phases(cell, radius, albedo, dark_side, rays=False, phases=16):
    """4x2 grid of phases; returns RGBA uint8 array."""
    img = np.zeros((cell * (phases // 4), cell * 4, 4))
    albedo, relief = albedo
    size = albedo.shape[0]
    yy, xx = (np.mgrid[0:cell, 0:cell] + 0.5 - cell / 2) / radius
    rr = np.sqrt(xx ** 2 + yy ** 2)
    mask = np.clip((1.0 - rr) * radius, 0, 1)  # 1-pixel antialiased edge
    z = np.sqrt(np.clip(1 - rr ** 2, 0, 1))
    # sample the albedo map by disc coordinates
    ix = np.clip(((xx + 1) / 2 * (size - 1)).astype(int), 0, size - 1)
    iy = np.clip(((yy + 1) / 2 * (size - 1)).astype(int), 0, size - 1)
    alb = albedo[iy, ix]
    gy, gx = np.gradient(relief, 2.0 / size)
    normal_x = xx - gx[iy, ix]
    normal_y = yy - gy[iy, ix]
    normal_z = z
    normal_length = np.sqrt(normal_x**2 + normal_y**2 + normal_z**2)
    for p in range(phases):
        phi = p * np.pi * 2 / phases
        lx, lz = np.sin(phi), np.cos(phi)
        lit = (normal_x * lx + normal_z * lz) / np.maximum(0.001, normal_length)
        light = smoothstep(-0.025, 0.055, lit) * (0.24 + 0.76 * np.clip(lit, 0, 1) ** 0.42)
        # Baked local horizon tests give crater walls real cast shadows near the terminator.
        base_z = z + relief[iy, ix]
        for step in (0.002, 0.005, 0.011, 0.024, 0.05):
            sx = xx + lx * step
            six = np.clip(((sx + 1) / 2 * (size - 1)).astype(int), 0, size - 1)
            sz = np.sqrt(np.maximum(0, 1 - sx*sx - yy*yy)) + relief[iy, six]
            shadow = smoothstep(0.0002, 0.0015, sz - (base_z + lz * step))
            shadow *= (sx*sx + yy*yy < 1)
            light *= 1 - shadow * 0.88
        limb = 0.7 + 0.3 * z
        col = alb * (light * limb)[..., None] + np.array(dark_side, float) * (1 - light)[..., None]
        a = mask * 255
        r, c = divmod(p, 4)
        img[r * cell:(r + 1) * cell, c * cell:(c + 1) * cell, :3] = col
        img[r * cell:(r + 1) * cell, c * cell:(c + 1) * cell, 3] = a
    img[..., :3] *= (img[..., 3:4] > 0)
    return np.clip(img, 0, 255).astype(np.uint8)


def masser(rng):
    # Masser is huge in Skyrim's sky: the disc fills almost the whole vanilla moon quad
    cell, radius = 512, 238.0
    alb = surface(1024, rng, base=(207, 153, 139), dark=(100, 77, 83), bright=(244, 216, 201), craters=390, maria=0.7, strength=0.8)
    return moon_phases(cell, radius, alb, dark_side=(2, 2, 3))


def secunda(rng):
    cell, radius = 256, 117.0
    alb = surface(512, rng, base=(224, 230, 238), dark=(135, 143, 161), bright=(255, 255, 255), craters=220, maria=0.4, strength=0.75)
    return moon_phases(cell, radius, alb, dark_side=(1, 2, 3))


def halo():
    s = 512
    yy, xx = (np.mgrid[0:s, 0:s] + 0.5 - s / 2) / (s / 2)
    r = np.sqrt(xx ** 2 + yy ** 2)
    inner = 0.50  # Masser's rim: its disc fills ~90% of the vanilla moon quad (20 units), the halo quad is 36
    d = np.clip(r - inner, 0, None)
    g = (0.50 * np.exp(-d / 0.06) + 0.50 * np.exp(-d / 0.28)) * smoothstep(inner - 0.10, inner + 0.01, r) * (1 - smoothstep(0.62, 1.0, r))
    img = np.zeros((s, s, 4))
    img[..., 0] = 255
    img[..., 1] = 120
    img[..., 2] = 90
    img[..., 3] = np.clip(g * 170, 0, 255)
    img[..., :3] *= (img[..., 3:4] > 0)
    return img.astype(np.uint8)


def star_dot():
    """A soft round star sprite (white; the vertex colour tints it). The centre texel is fully opaque, so thin
    constellation lines can sample it too. Drawn linearly filtered, never as a square."""
    s = 64
    yy, xx = (np.mgrid[0:s, 0:s] + 0.5 - s / 2) / (s / 2)
    r = np.sqrt(xx ** 2 + yy ** 2)
    a = np.exp(-(r / 0.20) ** 2) * 1.0 + np.exp(-(r / 0.55) ** 2) * 0.22
    a *= 1 - smoothstep(0.80, 1.0, r)
    a[(np.abs(xx) < 0.05) & (np.abs(yy) < 0.05)] = 1.0
    img = np.zeros((s, s, 4))
    img[..., :3] = 255
    img[..., 3] = np.clip(a, 0, 1) * 255
    return img.astype(np.uint8)


def galaxy(rng):
    """Original unresolved stellar disk, asymmetric bulge and absorbing dust lanes.

    Noise frequencies use angular/pixel scale rather than stretching square noise
    around the whole sky. Brightness is stellar radiance in RGB, not a cloud veil.
    """
    h, w = 512, 4096
    v = (np.arange(h)[:, None] + 0.5) / h
    u = (np.arange(w)[None, :] + 0.5) / w
    def angular_noise(falloff, low):
        f = np.fft.rfft2(rng.standard_normal((h, w)))
        fy = np.fft.fftfreq(h)[:, None] * h
        fx = np.fft.rfftfreq(w)[None, :] * h
        k = np.sqrt(fx*fx + fy*fy)
        f *= 1 / np.maximum(k, low)**falloff
        f[0, 0] = 0
        n = np.fft.irfft2(f, s=(h,w))
        n = (n-n.mean()) / max(n.std(), 1e-6)
        return np.clip(n * 0.17 + 0.5, 0, 1)
    coarse = angular_noise(2.2, 2)
    clouds = angular_noise(1.7, 7)
    fine = angular_noise(0.85, 28)
    longitude = np.arctan2(np.sin((u-0.32)*2*np.pi), np.cos((u-0.32)*2*np.pi))
    bulge = np.exp(-(longitude/0.44)**2)
    centre = 0.50 + np.sin(u*2*np.pi)*0.025 + np.sin(u*6*np.pi+1.3)*0.018
    latitude = v-centre
    thin_disk = np.exp(-(latitude/(0.11+bulge*0.10))**2)
    halo = np.exp(-(latitude/0.32)**2) * (0.30+bulge*0.65)
    stars = (thin_disk*(0.28+coarse*0.65)+halo) * (0.45+clouds*0.9)
    # Warped, branching absorption lanes split the stellar populations irregularly.
    lane_centre = 0.012 + (clouds-0.5)*0.08 + np.sin(u*14*np.pi)*0.012
    dust = np.exp(-((latitude-lane_centre)/0.028)**2) * (0.4+coarse*1.3)
    dust += np.exp(-((latitude+0.06+(coarse-0.5)*0.09)/0.018)**2) * clouds*0.9
    # The Great Rift occupies part of the sky; a dark seam around the entire
    # celestial circle reads as an artificial ink stroke. Foreground stars remain.
    dust *= 0.15 + 0.85*np.exp(-((longitude+0.20)/1.7)**2)
    transmission = np.exp(-dust*2.4)
    radiance = stars * transmission * (0.55+fine*0.75) + thin_disk * (0.03+fine*0.06)
    probability = 0.002 + 0.025*thin_disk*transmission
    resolved = (rng.random((h,w)) < probability) * rng.random((h,w))**4
    radiance = np.clip(radiance*0.55 + resolved*0.65, 0, 1)
    warm = bulge * thin_disk
    rgb = np.stack(np.broadcast_arrays(0.77+warm*0.23, 0.83+warm*0.12, 1.0-warm*0.22),axis=-1)
    rgb *= radiance[...,None]
    edge = smoothstep(0.0,0.15,v) * (1-smoothstep(0.85,1.0,v))
    img = np.zeros((h,w,4),np.uint8)
    img[...,:3] = np.uint8(np.clip(rgb,0,1)**(1/2.2)*255+0.5)
    img[...,3] = np.uint8(np.broadcast_to(edge,(h,w))*255+0.5)
    return img


# ---------------------------------------------------------------- particles
# Sprites are drawn light grey/white: the game tints them per particle (ash grey, ember orange, leaf colour).

def _grid(n):
    yy, xx = (np.mgrid[0:n, 0:n] + 0.5 - n / 2) / (n / 2)
    return xx, yy, np.sqrt(xx ** 2 + yy ** 2)


def _rgba(alpha, rgb=(255, 255, 255)):
    n = alpha.shape[0]
    img = np.zeros((n, n, 4))
    img[..., 0], img[..., 1], img[..., 2] = rgb
    img[..., 3] = np.clip(alpha, 0, 1) * 255
    img[..., :3] *= (img[..., 3:4] > 0)
    return img.astype(np.uint8)


def soft_dot(n, core=0.35, edge=1.0, rgb=(255, 255, 255), power=1.0):
    _, _, r = _grid(n)
    a = (1 - smoothstep(core, edge, r)) ** power
    return _rgba(a, rgb)


def snowflake(variant):
    n = 8
    xx, yy, r = _grid(n)
    if variant == 3:
        # a tiny six-armed crystal
        ang = np.arctan2(yy, xx)
        arms = np.abs(np.cos(3 * ang)) ** 6
        a = np.clip((1 - r) * 1.6, 0, 1) * (0.35 + 0.65 * arms)
        a[r < 0.3] = 1
        return _rgba(a)
    core = [0.15, 0.3, 0.45][variant]
    return soft_dot(n, core, 0.95)


def ash_flake(rng):
    n = 8
    xx, yy, r = _grid(n)
    ang = np.arctan2(yy, xx)
    wob = 0.65 + 0.25 * np.sin(ang * rng.integers(2, 5) + rng.uniform(0, 6))
    a = (r < wob).astype(float) * (0.75 + 0.25 * rng.random((n, n)))
    return _rgba(a, (235, 232, 228))


def leaf(variant):
    n = 16
    xx, yy, _ = _grid(n)
    t = variant * np.pi / 4
    u = xx * np.cos(t) + yy * np.sin(t)
    v = -xx * np.sin(t) + yy * np.cos(t)
    shape = (u / 0.95) ** 2 + (v / 0.5) ** 2 < 1
    vein = np.abs(v) < 0.09
    a = shape.astype(float)
    img = _rgba(a, (235, 235, 235))
    img[vein & shape, :3] = (200, 200, 200)
    return img


def breath(rng, variant):
    n = 32
    xx, yy, r = _grid(n)
    noise = periodic_noise(n, n, rng, 1.6)
    a = (1 - smoothstep(0.2, 1.0, r * (0.85 + 0.3 * noise))) * (0.6 + 0.4 * noise)
    return _rgba(a * 0.9)


def wisp(rng, variant):
    n = 32
    xx, yy, _ = _grid(n)
    r = np.sqrt((xx / 1.0) ** 2 + (yy / 0.55) ** 2)
    noise = periodic_noise(n, n, rng, 1.5)
    a = (1 - smoothstep(0.1, 1.0, r * (0.8 + 0.4 * noise))) * (0.5 + 0.5 * noise)
    return _rgba(a * 0.8)


def particles():
    rng = np.random.default_rng(4242)
    PARTICLE_TEX.mkdir(parents=True, exist_ok=True)
    PARTICLE_DEF.mkdir(parents=True, exist_ok=True)
    sprites = {
        "snowflake": [snowflake(i) for i in range(4)],
        "ash_flake": [ash_flake(rng) for _ in range(3)],
        "ember": [soft_dot(8, 0.15, 0.9, (255, 255, 230), 1.5), soft_dot(8, 0.3, 1.0, (255, 250, 220), 1.2)],
        "sand_dust": [soft_dot(4, 0.2, 1.0, (250, 245, 235)), soft_dot(8, 0.1, 0.9, (250, 245, 235), 2.0)],
        "falling_leaf": [leaf(i) for i in range(4)],
        "firefly": [soft_dot(8, 0.12, 1.0, (255, 255, 240), 2.2)],
        "frost_breath": [breath(rng, i) for i in range(3)],
        "marsh_wisp": [wisp(rng, i) for i in range(2)],
        "dust_mote": [soft_dot(4, 0.25, 1.0, (255, 250, 235))],
    }
    for name, imgs in sprites.items():
        names = []
        for i, img in enumerate(imgs):
            tex = f"{name}_{i}"
            Image.fromarray(img, "RGBA").save(PARTICLE_TEX / f"{tex}.png")
            names.append(f"nirncraft:{tex}")
        (PARTICLE_DEF / f"{name}.json").write_text(json.dumps({"textures": names}, indent=2) + "\n")
    print("wrote", len(sprites), "particle sprite sets to", PARTICLE_TEX)


# ---------------------------------------------------------------- clouds
# One 1024x1024 atlas of 4x4 cells (256 px), drawn by atmosphere.client.CloudRenderer as billboards and sheets.
# Sprites are near-white with soft alpha and a little baked top light; the renderer colours them per vertex
# (sun, shadowed bases, dawn tint, haze). RGB is filled everywhere so filtering and mipmaps never fringe dark.
# Cells: 0-3 cumulus puffs, 4-5 stratus sheets, 6-7 cirrus, 8 fog bank, 9 rain curtain, 10 snow squall,
# 11 dust/ash billow, 12 lenticular cap, 13 glow, 14 storm wall, 15 flat-based puff.

CLOUD_CELL = 256


def _cgrid(n=CLOUD_CELL):
    yy, xx = (np.mgrid[0:n, 0:n] + 0.5 - n / 2) / (n / 2)
    return xx, yy


def _fbm(n, rng, base_exp=1.8):
    return (0.50 * periodic_noise(n, n, rng, base_exp, low=2.0)
            + 0.30 * periodic_noise(n, n, rng, base_exp + 0.35, low=4.0)
            + 0.20 * periodic_noise(n, n, rng, base_exp + 0.7, low=8.0))


def _lobes(rng, n_lobes, spread, rmin, rmax, flat_bottom=None, n=CLOUD_CELL):
    """Volumetric multi-octave FBM cloud puff with soft self-shadowed lobes."""
    xx, yy = _cgrid(n)
    d = np.zeros((n, n))
    wsum = np.full((n, n), 1e-6)
    bsum = np.zeros((n, n))
    lobes = []
    for i in range(n_lobes):
        ang = rng.uniform(0, np.pi * 2)
        rad = spread * np.sqrt(rng.uniform(0, 1))
        cx = np.cos(ang) * rad
        cy = np.sin(ang) * rad * 0.62 + 0.04
        r = rng.uniform(rmin, rmax) * (1.18 - 0.38 * (rad / max(1e-3, spread)))
        lobes.append((cx, cy, r))
    for i in range(n_lobes + 4):
        ang = rng.uniform(np.pi * 1.02, np.pi * 1.98)
        lobes.append((np.cos(ang) * spread * 1.02, np.sin(ang) * spread * 0.72, rng.uniform(rmin * 0.52, rmin * 0.88)))
    warp_x = (_fbm(n, rng, 1.9) - 0.5) * 0.16
    warp_y = (_fbm(n, rng, 1.9) - 0.5) * 0.16
    wx = xx + warp_x
    wy = yy + warp_y
    for cx, cy, r in lobes:
        q = ((wx - cx) ** 2 + (wy - cy) ** 2) / (r * r)
        g = np.exp(-q * 1.35)
        d += g
        w = g ** 3
        lit = np.clip(0.56 - (wy - cy) / r * 0.52 - (wx - cx) / r * 0.15, 0, 1)
        bsum += w * lit
        wsum += w
    shade = bsum / wsum
    if flat_bottom is not None:
        base_ripple = (_fbm(n, rng, 1.7) - 0.5) * 0.08
        cut = 1 - smoothstep(flat_bottom - 0.22, flat_bottom + 0.06, yy + base_ripple)
        d *= cut
    return d, shade


def _puff(rng, flat=False, billow=False):
    n = CLOUD_CELL
    d, lit = _lobes(rng, 11 if not billow else 15, 0.40, 0.22, 0.34, flat_bottom=0.46 if flat else None)
    edge = _fbm(n, rng, 1.85)
    fine = _fbm(n, rng, 1.55)
    raw = smoothstep(0.06, 0.92, d * (0.70 + 0.55 * edge))
    a = (raw ** 1.15) * (0.84 + 0.16 * fine)
    xx, yy = _cgrid(n)
    r_env = np.sqrt(xx ** 2 + yy ** 2)
    a *= 1 - smoothstep(0.76, 0.96, np.maximum(r_env * 0.92, np.maximum(np.abs(xx), np.abs(yy))))
    vert = 1 - (yy + 1) / 2
    bottom = 0.86 if billow else 0.78
    b = bottom + (1 - bottom) * np.clip(0.38 * vert + 0.62 * lit, 0, 1)
    b *= 0.95 + 0.07 * fine
    return np.clip(b, 0, 1), a * (0.85 if billow else 1.0)


def _sheet(rng):
    n = CLOUD_CELL
    xx, yy = _cgrid(n)
    r = np.sqrt(xx ** 2 + yy ** 2)
    n1 = periodic_noise(n, n, rng, 2.4)
    n2 = periodic_noise(n, n, rng, 2.0)
    a = (1 - smoothstep(0.1, 0.95, r * (0.85 + 0.3 * n1))) ** 1.3 * (0.6 + 0.4 * smoothstep(0.2, 0.8, n2))
    b = 0.88 + 0.12 * n2
    return b, a


def _streaks(rng, n, stretch):
    """Noise stretched along x (bicubic upscale of a squashed field)."""
    small = periodic_noise(max(4, n // stretch), n, rng, 1.6, low=2.0)
    img = Image.fromarray((small * 255).astype(np.uint8), "L").resize((n, n), Image.BICUBIC)
    return np.asarray(img, dtype=float) / 255


def _cirrus(rng):
    n = CLOUD_CELL
    xx, yy = _cgrid(n)
    fib = _streaks(rng, n, 24)
    # bend the fibres: shift rows by a smooth curve so the streaks hook like mares' tails
    curve = (np.sin(np.linspace(0, np.pi * rng.uniform(1.0, 2.0), n)) * rng.uniform(10, 30)).astype(int)
    fib = np.stack([np.roll(fib[:, i], curve[i]) for i in range(n)], axis=1)
    clump = periodic_noise(n, n, rng, 2.2)
    env = (1 - smoothstep(0.5, 0.98, np.abs(xx))) * (1 - smoothstep(0.2, 0.85, np.abs(yy)))
    a = smoothstep(0.42, 0.8, fib) * env * (0.45 + 0.55 * smoothstep(0.3, 0.7, clump))
    return np.full((n, n), 1.0), np.clip(a * 1.15, 0, 1)


def _fog(rng):
    n = CLOUD_CELL
    xx, yy = _cgrid(n)
    n1 = periodic_noise(n, n, rng, 1.6)
    r = np.sqrt((xx / 0.98) ** 2 + (yy / 0.45) ** 2)
    a = (1 - smoothstep(0.2, 1.0, r * (0.8 + 0.4 * n1))) * (0.7 + 0.3 * n1)
    return np.full((n, n), 0.97), a * 0.9


def _curtain(rng, snow=False):
    n = CLOUD_CELL
    xx, yy = _cgrid(n)
    v = (yy + 1) / 2                                   # 0 top (cloud base) .. 1 ground
    if snow:
        streak = periodic_noise(n, n, rng, 1.5)
        dens = 0.55 + 0.45 * streak
    else:
        cols = periodic_noise(1, n, rng, 0.9, low=6.0)[0]
        streak = np.repeat(cols[None, :], n, axis=0)
        drift = periodic_noise(n, n, rng, 1.8)
        dens = 0.45 + 0.55 * smoothstep(0.3, 0.8, np.roll(streak, 0, axis=1) * (0.7 + 0.6 * drift))
    shafts = periodic_noise(1, n, rng, 2.2)[0][None, :]
    side = 1 - smoothstep(0.55, 0.98, np.abs(xx))
    fall = smoothstep(0.0, 0.08, v) * (1 - smoothstep(0.55, 1.0, v) * 0.85)
    a = dens * side * fall * (0.55 + 0.45 * shafts)
    return np.full((n, n), 0.95 if snow else 0.9), np.clip(a, 0, 1)


def _lens(rng):
    n = CLOUD_CELL
    xx, yy = _cgrid(n)
    r = np.sqrt((xx / 0.95) ** 2 + (yy / 0.32) ** 2)
    bands = 0.8 + 0.2 * np.sin(yy * 30 + periodic_noise(n, n, rng, 2.0) * 3)
    a = (1 - smoothstep(0.55, 1.0, r)) * bands
    b = 0.8 + 0.2 * smoothstep(0.3, -0.3, yy)
    return b, a


def _glow():
    xx, yy = _cgrid()
    r = np.sqrt(xx ** 2 + yy ** 2)
    a = np.exp(-(r / 0.38) ** 2) * (1 - smoothstep(0.8, 0.98, r))
    return np.full(r.shape, 1.0), a


def _wall(rng):
    n = CLOUD_CELL
    xx, yy = _cgrid(n)
    v = (yy + 1) / 2
    top = 0.25 + 0.25 * periodic_noise(1, n, rng, 1.6)[0][None, :]
    billows = periodic_noise(n, n, rng, 1.7)
    a = smoothstep(top - 0.12, top + 0.2, v + 0.1 * billows) * (0.75 + 0.25 * billows)
    a *= 1 - smoothstep(0.6, 0.98, np.abs(xx))
    a *= 1 - smoothstep(0.92, 1.0, v)
    b = 0.8 + 0.2 * (1 - v)
    return b, a


def clouds():
    rng = np.random.default_rng(3303)
    n = CLOUD_CELL
    cells = [_puff(rng) for _ in range(4)]
    cells += [_sheet(rng) for _ in range(2)]
    cells += [_cirrus(rng) for _ in range(2)]
    cells += [_fog(rng), _curtain(rng), _curtain(rng, snow=True), _puff(rng, billow=True), _lens(rng), _glow(), _wall(rng)]
    cells.append(_puff(rng, flat=True))
    img = np.zeros((n * 4, n * 4, 4))
    for i, (b, a) in enumerate(cells):
        r, c = divmod(i, 4)
        img[r * n:(r + 1) * n, c * n:(c + 1) * n, :3] = (b * 255)[..., None]
        img[r * n:(r + 1) * n, c * n:(c + 1) * n, 3] = np.clip(a, 0, 1) * 255
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(SKY / "clouds.png")
    print("wrote", SKY / "clouds.png")

    # Initial 128x128 weather_field.png resource (updated dynamically at runtime by WeatherFieldTexture)
    wf = np.zeros((128, 128, 4), dtype=np.uint8)
    wf[..., 0] = 82   # R: fair-weather baseline coverage (~0.32)
    wf[..., 1] = 20   # G: low convective height (~0.08)
    wf[..., 2] = 0    # B: no precipitation curtain
    wf[..., 3] = 5    # A: band 0 (water cloud), 0 flash
    Image.fromarray(wf, "RGBA").save(SKY / "weather_field.png")
    print("wrote", SKY / "weather_field.png")


def write_blur_meta(png, clamp=False):
    """Linear filtering for a sky texture (Minecraft samples textures with NEAREST unless told to blur)."""
    meta = {"texture": {"blur": True, "clamp": clamp}}
    png.with_name(png.name + ".mcmeta").write_text(json.dumps(meta, indent=2) + "\n")


def vanilla_moon_overrides():
    """Native celestials use eight atlas sprites; suppress every phase alongside Masser and Secunda."""
    import shutil
    VANILLA.mkdir(parents=True, exist_ok=True)
    legacy=VANILLA / "moon_phases.png"
    Image.new("RGBA", (4, 2), (0, 0, 0, 0)).save(legacy)
    native=VANILLA / "celestial/moon"
    native.mkdir(parents=True,exist_ok=True)
    for phase in ("first_quarter","full_moon","new_moon","third_quarter","waning_crescent","waning_gibbous","waxing_crescent","waxing_gibbous"):
        shutil.copyfile(legacy,native/(phase+".png"))


def sky():
    rng = np.random.default_rng(1771)
    SKY.mkdir(parents=True, exist_ok=True)
    VANILLA.mkdir(parents=True, exist_ok=True)
    out = [
        (SKY / "masser_halo.png", halo(), True),
        (SKY / "galaxy.png", galaxy(rng), False),
        (SKY / "star.png", star_dot(), True),
    ]
    for path, arr, clamp in out:
        Image.fromarray(arr, "RGBA").save(path)
        write_blur_meta(path, clamp)
    # Both renderer paths draw the same smooth-phased celestial bodies. Suppress the duplicate vanilla moon.
    vanilla_moon_overrides()
    print("wrote the sky textures:", ", ".join(o[0].name for o in out))


def main():
    import sys
    if "vanilla-moons" in sys.argv[1:]:
        vanilla_moon_overrides()
        return
    if "sky" in sys.argv[1:]:
        sky()
        return
    if "clouds" in sys.argv[1:]:
        SKY.mkdir(parents=True, exist_ok=True)
        clouds()
        return
    particles()
    sky()
    clouds()
    print("wrote", VANILLA / "moon_phases.png", "and", SKY)


if __name__ == "__main__":
    main()
