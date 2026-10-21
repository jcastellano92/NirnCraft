"""Masser and Secunda, baked: their surfaces simulated from nothing but this code and its seeds.

No photograph, scan, game asset or traced shape goes in. Both moons were once pure white, featureless spheres; they
died and their skin withered. Each is built up through time on the sphere (every field from its texels' 3D directions:
noise in 3D, craters by angular distance, blurs and slopes pole-safe), in geological regions laid out by hand-placed
seeds and rules (moon_gen.py --preview writes the labelled map: .work/moon_gen/<moon>_regions.png):

  1. a crust: long-wave regional relief; ancient basins, the largest with rings of mountains;
  2. epochs of bombardment from a power-law size-frequency distribution (N(>r) ~ r^-2): simple bowls, complex craters
     (flat floors, terraced walls, central peaks, peak rings), irregular outlines, oblique impacts (ellipses, their
     ejecta thrown downrange), doublets and chains; each excavates what was there and buries its surroundings,
     throwing out deeper material; between epochs topographic diffusion wears the older ones;
  3. the withered skin: plates of the old white left, raised a little, lipped and peeling at their edges, over the
     darker strata they bared (redder on Masser, greyer on Secunda), all cracked in polygons like dried hide at several
     scales; lobate scarps where the dead crust shrank;
  4. the seas: lava filling a region's low ground to a level (its edge a contour, embaying the highlands, the rims of
     half-buried craters standing out of it), wrinkle ridges, rilles;
  5. Masser: Mara's Tear (a basalt teardrop falling from the Eye, a bone-pale salt flat), the Great Rift across both
     (a faulted graben, branches, side cracks, vents with ash and oxide), chaos where it dies out, pale salt flats, dust
     fields streaked by old winds; rose and rust highlands, umber, near-black basalt.
     Secunda: the giant basin's radial valleys and crater chains, dark basins, the Parliament of Craters (a ring of great
     craters round a central one, their floors drowned dark), the Weeping Star and other young rayed craters whose rays
     cross the face; warm cream and cool grey highlands;
  6. a young epoch: fresh craters with bright ejecta, rays, secondaries, boulder fields; texel-scale micro detail.

Outputs (textures/environment), each an equirectangular map, longitude 0 / latitude 0 the face towards Nirn (both
moons are tidally locked: the shader keeps that face to the planet), north up, east to the right, row 0 the north:

  <moon>_albedo.png  rgb albedo (gamma 2.2), its mean over the face Nirn sees (projected) the albedo NightSkyMath
                     keeps for the moon (MASSER_ALBEDO / SECUNDA_ALBEDO), so the sky's photometry is unchanged; a the
                     detail layer's strength there (its region's roughness)
  <moon>_relief.png  rg the surface normal's east and north components (up follows), ba the local height in 16 bits
                     (the height less its 0.12 rad blur: h = (b * 256 + a) / 65535 * 2 - 1, x RELIEF[moon] moon radii;
                     atlas_moons.glsl MASSER_RELIEF / SECUNDA_RELIEF): the cast shadows' height, without 8-bit steps
  <moon>_detail.png  the detail layer (seamless; the shader warps and blends it so it never repeats)
  <moon>_phases.png  the 16-phase 4 x 4 sheet the shader pack and the mesh fallback sky draw, rendered from the maps

    py -3 tools/dev/moon_gen.py                 # both moons at full size, into the mod's textures
    py -3 tools/dev/moon_gen.py --only masser --scale 0.5 --out .work/moon_gen/half   # a quick look
    py -3 tools/dev/moon_gen.py --preview       # also the region maps, discs and full-zoom crops into .work/moon_gen

Deterministic: fixed seeds, numpy and scipy only (threads only split pure interpolation work).
"""
from __future__ import annotations

import argparse
import json
import math
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import fft, ndimage
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "mod/src/main/resources/assets/nirncraft/textures/environment"
WORK = ROOT / ".work/moon_gen"
TAU = 2.0 * math.pi
POOL = ThreadPoolExecutor(16)

# the height the relief map's alpha spans (moon radii): atlas_night.glsl's MASSER_RELIEF / SECUNDA_RELIEF
RELIEF = {"masser": 0.015, "secunda": 0.016}
# NightSkyMath.MASSER_ALBEDO / SECUNDA_ALBEDO: the linear mean the face towards Nirn keeps
TARGET = {"masser": (0.1708, 0.1304, 0.1494), "secunda": (0.1077, 0.1110, 0.1063)}
# NightSkyMath.MASSER_COLOUR / SECUNDA_COLOUR (the previews show the discs in the sky's colour)
COLOUR = {"masser": (1.0, 0.70, 0.62), "secunda": (0.975, 0.95, 0.895)}


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# ===================================================================================================== the sphere grid
class Grid:
    def __init__(self, width: int):
        self.W, self.H = width, width // 2
        self.lat = ((0.5 - (np.arange(self.H) + 0.5) / self.H) * math.pi).astype(np.float32)
        self.lon = (((np.arange(self.W) + 0.5) / self.W - 0.5) * TAU).astype(np.float32)
        self.slat, self.clat = np.sin(self.lat), np.cos(self.lat)
        self.texel = math.pi / self.H

    def xyz(self, rows=slice(None)):
        cl, sl = self.clat[rows], self.slat[rows]
        out = np.empty((len(cl), self.W, 3), np.float32)
        out[..., 0] = cl[:, None] * np.sin(self.lon)[None]
        out[..., 1] = sl[:, None]
        out[..., 2] = cl[:, None] * np.cos(self.lon)[None]
        return out

    def patch(self, lat0, lon0, ext):
        """The rows and columns within ext (rad) of (lat0, lon0), their distances and azimuths (from north, east +)."""
        r0 = max(0, int((0.5 - (lat0 + ext) / math.pi) * self.H))
        r1 = min(self.H, int(math.ceil((0.5 - (lat0 - ext) / math.pi) * self.H)) + 1)
        if r1 <= r0:
            return None
        lat = self.lat[r0:r1]
        cmin = float(np.min(np.cos(lat)))
        if lat0 + ext >= math.pi / 2 or lat0 - ext <= -math.pi / 2 or ext / max(cmin, 1e-4) >= math.pi * 0.98:
            cols = slice(None)
            lon = self.lon
        else:
            half = ext / cmin
            c0 = int(math.floor(((lon0 - half) / TAU + 0.5) * self.W))
            c1 = int(math.ceil(((lon0 + half) / TAU + 0.5) * self.W)) + 1
            if c0 >= 0 and c1 <= self.W:
                cols = slice(c0, c1)
            else:
                cols = np.arange(c0, c1) % self.W
            lon = self.lon[cols]
        dl = (lon - lon0)[None, :]
        s0, c0_ = math.sin(lat0), math.cos(lat0)
        sl, cl = self.slat[r0:r1, None], self.clat[r0:r1, None]
        cdl = np.cos(dl)
        cosd = sl * s0 + cl * c0_ * cdl
        d = np.arccos(np.clip(cosd, -1.0, 1.0))
        az = np.arctan2(np.sin(dl) * cl, c0_ * sl - s0 * cl * cdl)
        return (slice(r0, r1), cols), d.astype(np.float32), az.astype(np.float32)

    def blur(self, a, sigma):
        """A Gaussian of sigma (rad) on the sphere: per row in longitude (FFT, the row's own width), then in latitude."""
        if sigma <= 0:
            return a
        spec = fft.rfft(a, axis=1, workers=-1)
        k = np.arange(spec.shape[1], dtype=np.float32)
        sx = sigma / (np.maximum(self.clat, 0.02) * (TAU / self.W))  # sigma in this row's pixels
        spec *= np.exp(-2.0 * (math.pi * k[None, :] * sx[:, None] / self.W) ** 2)
        out = fft.irfft(spec, n=self.W, axis=1, workers=-1).astype(np.float32)
        out = ndimage.gaussian_filter1d(out, sigma / self.texel, axis=0, mode="nearest", truncate=3.0)
        # the polar caps: the rows' blur pinches there (a whole parallel averaged, rings); each cap blurred on its
        # own azimuthal grid instead and blended in
        for north in (True, False):
            self.cap_blur(a, out, sigma, north)
        return out

    CAP = 0.62  # (rad of colatitude the caps' own grids cover)

    def sample(self, a, lat, lon, order=1):
        """a (rows north to south, columns west to east) at (lat, lon) arrays: bilinear, longitude wrapping."""
        r = np.clip((0.5 - np.asarray(lat, np.float64) / math.pi) * self.H - 0.5, 0.0, self.H - 1.0)
        c = np.mod((np.asarray(lon, np.float64) / TAU + 0.5) * self.W - 0.5, self.W)
        c = np.where(c >= self.W, 0.0, c)  # (scipy's grid-wrap can spin forever on a coordinate at the edge)
        out = np.empty(np.shape(lat), np.float32)
        flat_r, flat_c, flat_o = np.ravel(r), np.ravel(c), out.reshape(-1)

        def job(i):
            sl = slice(i, min(i + 524288, len(flat_r)))
            flat_o[sl] = ndimage.map_coordinates(a, np.vstack([flat_r[sl], flat_c[sl]]), order=order, mode="grid-wrap",
                                                 prefilter=False)
        list(POOL.map(job, range(0, len(flat_r), 524288)))
        return out

    def cap_blur(self, a, out, sigma, north):
        cap = self.CAP
        n = int(2 * cap / self.texel) // 2 * 2
        c = (np.arange(n) + 0.5) / n * 2 - 1
        X, Y = np.meshgrid(c * cap, c * cap)
        colat = np.sqrt(X * X + Y * Y)
        lon = np.arctan2(Y, X)
        lat = (math.pi / 2 - colat) * (1 if north else -1)
        grid = self.sample(a, lat, lon)
        grid = ndimage.gaussian_filter(grid, sigma / (2 * cap / n), mode="nearest", truncate=3.0)
        rows = np.where(self.lat > math.pi / 2 - cap)[0] if north else np.where(self.lat < -(math.pi / 2 - cap))[0]
        latr = self.lat[rows][:, None] * np.ones((1, self.W), np.float32)
        lonr = self.lon[None, :] * np.ones((len(rows), 1), np.float32)
        cr = math.pi / 2 - np.abs(latr)
        gx = (cr * np.cos(lonr) / cap + 1) * 0.5 * n - 0.5
        gy = (cr * np.sin(lonr) / cap + 1) * 0.5 * n - 0.5
        back = ndimage.map_coordinates(grid, np.vstack([gy.ravel(), gx.ravel()]), order=1, mode="nearest").reshape(cr.shape)
        w = smoothstep(cap, cap * 0.7, cr)
        out[rows] = out[rows] * (1 - w) + back * w

    def point(self, lat0, lon0, az, dist):
        """The point dist (rad) from (lat0, lon0) along azimuth az."""
        lat = math.asin(math.sin(lat0) * math.cos(dist) + math.cos(lat0) * math.sin(dist) * math.cos(az))
        lon = lon0 + math.atan2(math.sin(az) * math.sin(dist) * math.cos(lat0),
                                math.cos(dist) - math.sin(lat0) * math.sin(lat))
        return lat, (lon + math.pi) % TAU - math.pi


def vec(lat, lon):
    return np.array([math.cos(lat) * math.sin(lon), math.sin(lat), math.cos(lat) * math.cos(lon)])


# ===================================================================================================== noise
class Noise:
    """Smooth value noise on the sphere: a tricubic B-spline through a periodic random lattice, each octave rotated."""

    def __init__(self, seed, n=128):
        rng = np.random.default_rng(seed)
        lattice = rng.standard_normal((n, n, n)).astype(np.float32)
        self.g = ndimage.spline_filter(lattice, order=3, mode="grid-wrap").astype(np.float32)
        self.g /= 0.55  # (the spline's spread: about unit deviation)
        self.n = n
        self.rot = []
        for _ in range(24):
            q, _r = np.linalg.qr(rng.standard_normal((3, 3)))
            self.rot.append((q.astype(np.float32), rng.uniform(0, n, 3).astype(np.float32)))

    def at(self, P, freq, octave=0):
        rot, off = self.rot[octave % len(self.rot)]
        flat = P.reshape(-1, 3)
        out = np.empty(len(flat), np.float32)

        def job(i):
            s = slice(i, min(i + 262144, len(flat)))
            q = np.mod((flat[s].astype(np.float64) @ rot.T) * freq + off, self.n)
            q = np.where(q >= self.n, 0.0, q)  # (scipy's grid-wrap can spin forever on a coordinate at the edge)
            out[s] = ndimage.map_coordinates(self.g, np.ascontiguousarray(q.T), order=3, mode="grid-wrap",
                                             prefilter=False)
        list(POOL.map(job, range(0, len(flat), 262144)))
        return out.reshape(P.shape[:-1])

    def fbm(self, P, freq, octaves, gain=0.5, lac=2.03, first=0):
        total = np.zeros(P.shape[:-1], np.float32)
        amp, norm = 1.0, 0.0
        for o in range(octaves):
            total += amp * self.at(P, freq * lac ** o, first + o)
            norm += amp * amp
            amp *= gain
        return total / math.sqrt(norm)

    def ridged(self, P, freq, octaves, first=0):
        total = np.zeros(P.shape[:-1], np.float32)
        amp = 1.0
        for o in range(octaves):
            total += amp * (1.0 - np.abs(self.at(P, freq * 2.07 ** o, first + o)))
            amp *= 0.5
        return total


# ===================================================================================================== the moon
class Moon:
    def __init__(self, name, spec, width, seed):
        self.name, self.s = name, spec
        self.g = Grid(width)
        self.rng = np.random.default_rng(seed)
        self.noise = Noise(seed + 1)
        g = self.g
        self.H = np.zeros((g.H, g.W), np.float32)   # height (moon radii)
        self.M = np.zeros_like(self.H)              # plains / dark lowland material 0..1
        self.F = np.zeros_like(self.H)              # freshness (immature, bright) 0..1
        self.D = np.zeros_like(self.H)              # the rift's dark mantle 0..1
        self.Rv = np.zeros_like(self.H)             # ray material (bright, in the young epoch)
        self.Ox = np.zeros_like(self.H)             # the rift's red oxide deposits (Masser) 0..1
        self.Bo = np.zeros_like(self.H)             # boulder fields round fresh craters 0..1
        self.Ej = np.zeros_like(self.H)             # deep material a large crater threw out 0..1
        self.P = g.xyz()
        self.rmin = spec["rmin_texels"] * g.texel
        self.stats = {"craters": 0}

    # ------------------------------------------------------------------------------------------- one crater
    def profile(self, x, az, r, rng):
        s = self.s
        rt = s["rt"]
        ph = rng.uniform(0, TAU, 8)
        if r < rt:
            small = 0.4 + 0.6 * float(smoothstep(1.0, 3.0, r / self.g.texel))
            depth = 0.30 * r * small
            rim = 0.065 * r * small
            inner = -depth + (depth + rim) * x ** 2
        else:
            q = r / rt
            depth = 0.30 * rt * q ** 0.32
            rim = 0.065 * rt * q ** 0.5
            if r > s["basin_r"]:
                depth *= (s["basin_r"] / r) ** 0.6
                rim *= (s["basin_r"] / r) ** 0.4
            xf = float(np.clip(0.22 + 0.13 * math.log2(q), 0.22, 0.62))
            jit = 0.05 * np.cos(3 * az + ph[0]) + 0.03 * np.cos(5 * az + ph[1]) + 0.02 * np.cos(9 * az + ph[2])
            t = np.clip((x * (1 + jit * 0.5) - xf) / (1 - xf), 0.0, 1.0)
            wall = t ** 1.6
            n = int(np.clip(1 + math.log2(q), 1, 5))
            if n >= 2:
                tt = np.clip(wall + jit * t * (1 - t) * 2.0, 0.0, 1.0)
                f = tt * n
                stair = (np.floor(f) + smoothstep(0.45, 0.95, f - np.floor(f))) / n
                wall = 0.45 * wall + 0.55 * stair
            inner = -depth + (depth + rim) * wall
            # hummocky floor
            inner += depth * 0.06 * np.cos(7 * az + ph[3]) * np.sin(x * 23 + ph[4]) * (x < xf)
            if r < s["ring_r"]:
                hp = 0.42 * depth * min(1.0, (q - 1.0) / 1.5)
                lobes = 0.7 + 0.3 * np.cos(2 * az + ph[5]) * np.cos(3 * az + ph[6])
                inner = inner + hp * lobes * np.exp(-(x / (0.10 + 0.04 * lobes)) ** 2)
            else:
                hp = 0.22 * depth
                inner = inner + hp * np.exp(-((x - 0.48) / 0.045) ** 2) * (0.65 + 0.35 * np.cos(5 * az + ph[7]))
        # ejecta: the rim's fall-off, radial texture, gone by ~3 radii
        k = np.arange(11, 41, 3, dtype=np.float32)
        radial = (np.cos(k[:, None, None] * az[None] + rng.uniform(0, TAU, len(k))[:, None, None])
                  * rng.uniform(0.5, 1.0, len(k))[:, None, None]).sum(0) / math.sqrt(len(k))
        xo = np.maximum(x, 1.0)
        outer = rim * xo ** -3.0 * (1.0 + 0.35 * radial * smoothstep(1.0, 1.4, x)) * (1.0 - smoothstep(2.4, 3.2, x))
        if r > s["basin_r"]:  # an outer ring scarp
            outer = outer + rim * 0.6 * smoothstep(1.55, 1.45, x) * smoothstep(1.2, 1.45, x)
        return np.where(x < 1.0, inner, outer).astype(np.float32), rim, depth

    def crater(self, lat0, lon0, r, fresh=1.0, inherit=0.9):
        rng = self.rng
        ext = min(r * 3.3, math.pi * 0.75)
        p = self.g.patch(lat0, lon0, ext)
        if p is None:
            return
        idx, d, az = p
        x = d / r
        # an irregular, slightly polygonal outline (larger craters more so)
        kk = np.arange(2, 8, dtype=np.float32)
        amp = rng.normal(0, 0.03 + 0.02 * min(1.0, r / self.s["rt"] / 8), len(kk)).astype(np.float32) / (kk - 1) ** 0.7
        wob = (amp[:, None, None] * np.cos(kk[:, None, None] * az[None]
                                           + rng.uniform(0, TAU, len(kk)).astype(np.float32)[:, None, None])).sum(0)
        x = x / (1.0 + wob)
        oblique = rng.uniform() < self.s.get("oblique", 0.0)
        if oblique:
            e, phi = rng.uniform(1.15, 1.8), rng.uniform(0, TAU)
            dd = az - phi
            x = x * np.sqrt(np.cos(dd) ** 2 / (e * e) + np.sin(dd) ** 2)
        prof, rim, depth = self.profile(x, az, r, rng)
        if oblique and e > 1.4:
            prof = np.where(x > 1.0, prof * (1.0 - 0.85 * smoothstep(0.5, 0.95, -np.cos(dd))), prof)
        old = self.H[idx]
        inside = x < 0.8
        ref = float(old[inside].mean()) if inside.any() else float(old.mean())
        a = inherit * (1.0 - smoothstep(0.8, 1.05, x))
        rimband = np.where(x < 1.0, rim, prof)
        self.H[idx] = (1 - a) * (old + rimband) + a * (ref + prof)
        # material: fresh rock inside and on the blanket; deep craters dig through the plains and the rift's mantle
        ej = np.where(x < 1.0, 1.0, np.exp(-(x - 1.0) * 1.7) * (x < 3.2))
        if fresh > 0:
            self.F[idx] = np.maximum(self.F[idx], (ej * fresh).astype(np.float32))
        if fresh >= 0.9 and r > self.rmin * 2.5:  # a young crater's blocks: on its rim and walls, thinning outward
            bo = np.where(x < 0.75, 0.25, np.exp(-np.maximum(x - 0.95, 0.0) * 2.2)) * (x < 3.0)
            self.Bo[idx] = np.maximum(self.Bo[idx], bo.astype(np.float32))
        deep = float(smoothstep(self.s["rt"] * 1.5, self.s["rt"] * 6.0, r))
        if deep > 0:
            ph = rng.uniform(0, TAU, 3)
            xr = x * (1.0 + 0.10 * np.sin(5 * az + ph[0]) + 0.06 * np.sin(13 * az + ph[1]) + 0.03 * np.sin(31 * az + ph[2]))
            thrown = deep * np.exp(-np.maximum(xr - 1.0, 0.0) * 1.7) * smoothstep(0.75, 1.2, xr) * (xr < 3.2)
            self.Ej[idx] = np.maximum(self.Ej[idx] * (1 - 0.5 * deep * ej), thrown.astype(np.float32))
        dig = float(smoothstep(self.s["dig_r"] * 0.6, self.s["dig_r"] * 1.6, r))
        if dig > 0:
            self.M[idx] *= (1.0 - dig * ej * 0.9).astype(np.float32)
        # (small craters punch through the rift's thin dark mantle: bright-haloed pits in it)
        self.D[idx] *= (1.0 - 0.3 * (1.0 - smoothstep(0.7, 1.3, x))).astype(np.float32)
        self.stats["craters"] += 1

    def halo(self, lat0, lon0, r, strength, field):
        """A soft deposit round a point (a vent's ash): field 'D' (dark mantle) or 'Ox' (red oxide)."""
        p = self.g.patch(lat0, lon0, r * 2.5)
        if p is None:
            return
        idx, d, az = p
        f = getattr(self, field)
        wob = 1.0 + 0.25 * np.cos(3 * az + self.rng.uniform(0, TAU)) + 0.15 * np.cos(5 * az + self.rng.uniform(0, TAU))
        f[idx] = np.maximum(f[idx], (strength * np.exp(-(d / (r * wob)) ** 2)).astype(np.float32))

    def rays(self, lat0, lon0, r, strength, n_rays, secondaries=True, long=1.0):
        """Bright rays of fine ejecta (and chains of secondary craters along them) from a young crater; long: how far
        they reach (x its usual ~7 radii: the Weeping Star's cross the face)."""
        rng = self.rng
        L = r * rng.lognormal(math.log(7.0 * long), 0.45, n_rays)
        L = np.minimum(L, r * 16 * long)
        p = self.g.patch(lat0, lon0, float(L.max()))
        if p is None:
            return []
        idx, d, az = p
        x = d / r
        ang = rng.uniform(-math.pi, math.pi, n_rays)
        width = rng.uniform(0.025, 0.07, n_rays)
        inten = rng.uniform(0.35, 1.0, n_rays)
        total = np.zeros_like(x)
        for i in range(n_rays):
            da = (az - ang[i] + math.pi) % TAU - math.pi
            w = width[i] / np.sqrt(np.maximum(x, 1.0)) * 1.8
            along = (1.0 - smoothstep(0.35 * L[i] / r, L[i] / r, x)) * smoothstep(1.0, 1.6, x)
            streak = 0.65 + 0.35 * np.sin(x * rng.uniform(1.5, 4.0) + rng.uniform(0, TAU))
            total += inten[i] * np.exp(-(da / w) ** 2) * along * streak
        # the continuous bright halo
        total += np.exp(-np.maximum(x - 1.0, 0.0) * 1.2) * (x < 4.0) * 0.8
        self.Rv[idx] = np.maximum(self.Rv[idx], (np.clip(total, 0, 1.5) * strength).astype(np.float32))
        out = []
        if secondaries:
            for i in range(n_rays):
                for _ in range(int(rng.integers(3, 12))):
                    dist = r * rng.uniform(2.2, max(2.3, 0.8 * L[i] / r))
                    rs = r * rng.uniform(0.025, 0.07)
                    if rs < self.rmin * 0.8:
                        continue
                    la, lo = self.g.point(lat0, lon0, ang[i] + rng.normal(0, width[i] * 0.5), dist)
                    out.append((la, lo, rs))
        return out

    # ------------------------------------------------------------------------------------------- populations
    def epoch(self, coverage, rmax, label, fresh=1.0, rmin_scale=1.0, mask=None, sfd=2.0):
        """A population: N(>r) per steradian = K r^-sfd, coverage = 2 pi K (craters' area per e-fold of size)."""
        rmin = self.rmin * rmin_scale
        if rmax <= rmin * 1.05:
            return
        K = coverage / TAU
        n = int(4 * math.pi * K * (rmin ** -sfd - rmax ** -sfd))
        rng = self.rng
        u = rng.uniform(0, 1, n)
        r = rmin * (1 - u * (1 - (rmin / rmax) ** sfd)) ** (-1 / sfd)
        z = rng.uniform(-1, 1, n)
        lat = np.arcsin(z)
        lon = rng.uniform(-math.pi, math.pi, n)
        t0 = time.time()
        for i in range(n):
            self.crater(float(lat[i]), float(lon[i]), float(r[i]), fresh=fresh)
            u2 = rng.uniform()
            if u2 < 0.03:  # a doublet: a binary impactor's twin
                la, lo = self.g.point(float(lat[i]), float(lon[i]), rng.uniform(0, TAU), float(r[i]) * rng.uniform(1.6, 2.4))
                self.crater(la, lo, float(r[i]) * rng.uniform(0.5, 1.0), fresh=fresh)
            elif u2 < 0.033 and r[i] > 3 * rmin:  # a chain: a broken impactor's train
                head, k = rng.uniform(0, TAU), int(rng.integers(5, 13))
                rc = float(r[i]) * rng.uniform(0.3, 0.6)
                for j in range(k):
                    la, lo = self.g.point(float(lat[i]), float(lon[i]), head + rng.normal(0, 0.05), rc * 1.7 * (j + 1))
                    self.crater(la, lo, rc * rng.uniform(0.7, 1.2), fresh=fresh)
        print(f"  {self.name} {label}: {n} craters ({r.max():.3f} rad largest) {time.time() - t0:.1f}s")

    def withered(self, amp, freq, levels, first):
        """The withered skin: lobate scarps, long sinuous one-sided cliffs where the crust shrank as the body cooled and
        died (a steep front, a long gentle back), in broken segments over the whole sphere."""
        n = self.noise.fbm(self.P, freq, 4, first=first)
        seg = self.noise.fbm(self.P, freq * 1.7, 3, first=first + 5)
        out = np.zeros_like(self.H)
        for c in levels:
            d = n - c
            out += smoothstep(-0.05, 0.0, d) * np.exp(-np.maximum(d, 0.0) / 0.22) * smoothstep(-0.1, 0.45, seg)
            seg = -seg  # (the next contour's segments where this one has none)
        self.H += amp * out
        self.stats["scarps"] = len(levels)

    def degrade(self, sigma_texels, fresh_keep):
        self.H = self.g.blur(self.H, sigma_texels * self.g.texel)
        self.F *= fresh_keep

    # ------------------------------------------------------------------------------------------- linear features
    def walk(self, lat0, lon0, heading, length, curv, rng, step=None, corr=0.2):
        """A wandering path on the sphere: points (n, 3), from (lat0, lon0) along azimuth heading; its curvature
        an Ornstein-Uhlenbeck process (deviation curv rad per rad, correlation length corr rad)."""
        step = step or 0.0008
        p = vec(lat0, lon0)
        north = np.array([0.0, 1.0, 0.0]) - p * p[1]
        north /= np.linalg.norm(north)
        east = np.cross(north, p)
        h = math.cos(heading) * north + math.sin(heading) * east
        pts = []
        turn = 0.0
        for _ in range(int(length / step)):
            pts.append(p.copy())
            decay = math.exp(-step / corr)
            turn = decay * turn + curv * math.sqrt(1 - decay * decay) * rng.normal()
            side = np.cross(p, h)
            h = h * math.cos(turn * step) + side * math.sin(turn * step)
            p = p + h * step
            p /= np.linalg.norm(p)
            h = h - p * np.dot(h, p)
            h /= np.linalg.norm(h)
        return np.array(pts)

    def trench(self, paths, kind):
        """Carves paths: each (points, half-width per point, depth per point[, the mantle's tone per point]). kind
        'rift' (a graben: a rough floor with tilted blocks, walls stepped down two faults, raised shoulders, the dark
        mantle and red deposits along it) or 'valley' (a V-to-U sculpted valley, low levees)."""
        g = self.g
        carve = np.zeros_like(self.H)
        lift = np.zeros_like(self.H)
        dark = np.zeros_like(self.H)
        oxide = np.zeros_like(self.H)
        warp = self.noise.fbm(self.P, 9.0, 5, first=17)
        blocks = self.noise.fbm(self.P, 22.0, 3, first=11)
        rough = self.noise.fbm(self.P, 60.0, 4, first=3)
        fine = self.noise.fbm(self.P, 160.0, 3, first=23)
        band = 64
        total = 0
        # each path on its own (its nearest point's width varies smoothly along it): the nearest point of all the
        # paths together switched paths, and widths, along straight lines between them
        for path in paths:
            pts = np.asarray(path[0], np.float64)
            w = np.asarray(path[1], np.float32)
            dep = np.asarray(path[2], np.float32)
            tone = (np.asarray(path[3]) if len(path) > 3 else np.ones(len(pts))).astype(np.float32)
            total += len(pts)
            tree = cKDTree(pts)
            reach = float(w.max()) * (6.0 if kind == "rift" else 3.0)
            plat = np.arcsin(np.clip(pts[:, 1], -1, 1))
            near_rows = np.nonzero((g.lat >= plat.min() - reach * 1.05) & (g.lat <= plat.max() + reach * 1.05))[0]
            if len(near_rows) == 0:
                continue
            for r0 in range(int(near_rows[0]), int(near_rows[-1]) + 1, band):
                rows = slice(r0, min(int(near_rows[-1]) + 1, r0 + band))
                P = self.P[rows].reshape(-1, 3)
                dist, i = tree.query(P, distance_upper_bound=reach, workers=-1)
                ok = np.isfinite(dist)
                if not ok.any():
                    continue
                ii = np.where(ok, i, 0)
                ww, dd, tn = w[ii], dep[ii], tone[ii]
                dist = np.where(ok, dist, 1e3).astype(np.float32)
                shape = carve[rows].shape
                wv, rv, bv = warp[rows].reshape(-1), rough[rows].reshape(-1), blocks[rows].reshape(-1)
                fv = fine[rows].reshape(-1)
                # (a rift's walls run straighter than a valley's banks: faults)
                ragged = (0.12, 0.035) if kind == "rift" else (0.22, 0.08)
                u = dist / ww * (1.0 + ragged[0] * wv) + ragged[1] * rv
                # (all of it gone before the reach: no circle where the search stopped)
                fade = smoothstep(reach, reach * 0.75, dist)
                if kind == "rift":
                    # two faults step the walls down (steep scarps, a bench between), the floor broken into tilted
                    # blocks and talus, the shoulders lifted by the rifting
                    step = 0.55 * smoothstep(0.70, 0.78, u) + 0.45 * smoothstep(0.88, 0.95, u)
                    tilt = 0.22 * np.clip(bv, -1, 1) * (1.0 - smoothstep(0.45, 0.7, u))
                    floor = -dd * (1.0 - step) * (1.0 + 0.10 * rv + tilt)
                    shoulder = dd * 0.28 * smoothstep(0.9, 1.05, u) * (1.0 - smoothstep(1.1, 2.6, u)) * fade
                    # the ash mantle: its edge ragged at several scales (blown, settled, embayed), not a smooth halo
                    dm = dist / (4.5 * ww) * np.exp(0.30 * np.clip(wv, -2, 2) + 0.18 * rv + 0.10 * fv)
                    mantle = np.maximum(1.0 - smoothstep(0.55, 0.9, u + 0.10 * fv + 0.06 * rv),
                                        0.9 * np.exp(-dm * dm) * (0.55 + 0.45 * wv) * fade)
                    mantle *= np.clip(ww / 0.03, 0.25, 1.0) * tn
                    ox = np.exp(-((dist / (2.2 * ww)) ** 2)) * smoothstep(0.1, 0.7, bv * 0.6 + wv * 0.6) * tn * fade
                    oxide[rows] = np.maximum(oxide[rows], ox.reshape(shape))
                else:
                    floor = -dd * (1.0 - smoothstep(0.0, 1.0, u)) ** 1.5
                    shoulder = dd * 0.12 * smoothstep(0.85, 1.1, u) * (1.0 - smoothstep(1.1, 1.8, u))
                    mantle = np.zeros_like(u)
                carve[rows] = np.minimum(carve[rows], floor.reshape(shape))
                lift[rows] = np.maximum(lift[rows], shoulder.reshape(shape))
                dark[rows] = np.maximum(dark[rows], mantle.reshape(shape))
        self.H += carve + lift
        self.Tr = np.maximum(getattr(self, "Tr", np.zeros_like(self.H)), smoothstep(0.0, -2e-4, carve).astype(np.float32))
        self.D = np.maximum(self.D, dark)
        self.Ox = np.maximum(self.Ox, oxide)
        self.stats[kind] = total

    # ------------------------------------------------------------------------------------------- the bake
    def micro(self):
        """The finest scale, down to the texel: fine wrinkles and slumps on steep walls, the boulders' bumps, the
        regolith's grain (height); returns the texel-scale albedo factor (the boulders' bright and dark blocks, the grain)."""
        g = self.g
        rng = np.random.default_rng(self.s.get("micro_seed", 7))
        gy, gx = self.gradients(self.H)
        slope = np.sqrt(gx * gx + gy * gy)
        wr = self.noise.ridged(self.P, 380.0, 2, first=48) - 1.0
        self.H += g.texel * 0.18 * smoothstep(0.15, 0.6, slope) * wr
        white = rng.standard_normal(self.H.shape).astype(np.float32)
        blocks = ndimage.gaussian_filter(white, 0.6)
        self.H += g.texel * 0.12 * self.Bo * np.maximum(ndimage.gaussian_filter(white, 1.2), 0.0)
        return np.exp(0.22 * self.Bo * np.clip(blocks * 1.5, -1.5, 1.5) + 0.035 * white)

    # ------------------------------------------------------------------------------------------- regions, skin
    def cap(self, lat0, lon0, r, edge=0.25, wobble=0.25, first=60):
        """A soft region round a point: 1 inside r (rad), out to 0 over edge x r, its outline wobbling (noise)."""
        c = vec(lat0, lon0).astype(np.float32)
        d = np.arccos(np.clip(self.P @ c, -1, 1))
        w = self.noise.fbm(self.P, 6.0 / max(r, 0.02), 3, first=first)
        return smoothstep(1.0 + edge, 1.0, d / r * np.exp(wobble * np.clip(w, -2.5, 2.5))).astype(np.float32)

    def tube(self, pts, radius, edge=0.3, first=62):
        """A soft region along a path of points (n, 3) with a radius per point."""
        tree = cKDTree(pts)
        out = np.zeros(self.H.shape, np.float32)
        reach = float(np.max(radius)) * (1.0 + edge) * 1.3
        w = self.noise.fbm(self.P, 18.0, 3, first=first)
        band = 128
        for r0 in range(0, self.g.H, band):
            rows = slice(r0, min(self.g.H, r0 + band))
            P = self.P[rows].reshape(-1, 3)
            dist, i = tree.query(P, distance_upper_bound=reach, workers=-1)
            ok = np.isfinite(dist)
            rr = np.asarray(radius)[np.where(ok, i, 0)]
            x = np.where(ok, dist, 1e3) / rr * np.exp(0.2 * np.clip(w[rows].reshape(-1), -2.5, 2.5))
            out[rows] = smoothstep(1.0 + edge, 1.0, x).reshape(out[rows].shape)
        return out

    def voronoi(self, n, seed, warp=0.0):
        """Cells on the sphere: F1, F2 (distances to the nearest two of n random points, rad) and the nearest's index."""
        rng = np.random.default_rng(seed)
        pts = rng.standard_normal((n, 3))
        pts /= np.linalg.norm(pts, axis=1, keepdims=True)
        tree = cKDTree(pts)
        f1 = np.empty(self.H.shape, np.float32)
        f2 = np.empty_like(f1)
        idx = np.empty(self.H.shape, np.int32)
        band = 128
        for r0 in range(0, self.g.H, band):
            rows = slice(r0, min(self.g.H, r0 + band))
            q = self.P[rows].reshape(-1, 3)
            if warp > 0:
                q = q + self._warp[rows].reshape(-1, 3) * warp
            d, i = tree.query(q, k=2, workers=-1)
            f1[rows] = d[:, 0].reshape(f1[rows].shape)
            f2[rows] = d[:, 1].reshape(f1[rows].shape)
            idx[rows] = i[:, 0].reshape(f1[rows].shape)
        return f1, f2, idx

    def cracks(self, strength, scales, depth, first=70):
        """Polygonal crack networks like dried skin, at several scales: narrow troughs along the cells' edges (where the
        two nearest cells are equally near), warped, broken; strength a map (0..1) of where they show."""
        warp = self.noise.fbm(self.P, 30.0, 3, first=first)
        total = np.zeros(self.H.shape, np.float32)
        for k, (n, width) in enumerate(scales):
            spacing = math.sqrt(4 * math.pi / n)
            fq = 1.2 / spacing
            self._warp = np.stack([self.noise.fbm(self.P, fq, 3, first=first + 20 + 3 * k + j)
                                   + 0.35 * self.noise.fbm(self.P, fq * 4.0, 2, first=first + 40 + 3 * k + j)
                                   for j in range(3)], -1)
            f1, f2, _ = self.voronoi(n, 0xC0 + k + first, warp=1.0 * spacing)
            edge = (f2 - f1) * (1.0 + 0.35 * warp)
            spacing = math.sqrt(4 * math.pi / n)
            w = width * spacing
            gap = self.noise.fbm(self.P, 1.5 / spacing, 2, first=first + 3 + k)
            line = (1.0 - smoothstep(0.0, w, edge)) * smoothstep(-0.6, 0.0, gap)
            total = np.maximum(total, line * (0.6 + 0.4 * (k == 0)))
            self.H -= depth * spacing * line * strength
        self.Cr = np.maximum(self.Cr, total * strength)

    def skin(self, cover, amp, first=80, bias=None):
        """The withered skin: the old white outer layer, peeled away in flakes. S: where a remnant plate still lies
        (raised a little, a crisp scarp and an upturned lip at its edge where it lifts); elsewhere the darker strata it
        exposed, in bands. cover: the share of the surface still skinned."""
        n = self.noise.fbm(self.P, 2.1, 5, first=first) + 0.22 * self.noise.fbm(self.P, 9.0, 4, first=first + 6)
        if bias is not None:
            n = n + bias
        t = np.quantile(n[::7, ::7], 1.0 - cover)
        wq = self.P + 0.012 * np.stack([self.noise.fbm(self.P, 60.0, 2, first=first + 11 + j) for j in range(3)], -1)
        edge = (n + 0.07 * self.noise.fbm(wq, 30.0, 3, first=first + 14) + 0.035 * self.noise.fbm(wq, 120.0, 3, first=first + 17)
                + 0.015 * self.noise.fbm(wq, 420.0, 2, first=first + 20))
        S = smoothstep(t - 0.012, t + 0.012, edge).astype(np.float32)  # crisp, ragged plates
        lip = (S * smoothstep(t + 0.09, t + 0.012, edge)).astype(np.float32)  # (their edges curled up)
        self.H += amp * S + 0.4 * amp * lip
        self.Sk = np.maximum(self.Sk, S)
        self.Lip = np.maximum(self.Lip, lip)
        # the strata the peeling bared: bands of the deeper layers (their height levels)
        self.Str = (self.noise.fbm(self.P, 9.0, 3, first=first + 9) * 3.0 + n * 6.0).astype(np.float32)

    def flood(self, mask, fill=0.6, keep=0.12, ridges=0.0004, first=90):
        """Lava over a region's low ground, to a level: it fills the region's lower share (fill) and stops at that
        contour, embaying the higher ground (its edge follows the relief, sharp; the rims of craters it half buried
        stand out of it as islands and arcs, ghosts of the drowned ones show through), flat, with wrinkle ridges."""
        core = mask > 0.5
        base = self.g.blur(self.H, 0.004)
        level = float(np.quantile(base[core], fill)) if core.any() else float(base.mean())
        eps = 6e-5
        rough = 4e-5 * self.noise.fbm(self.P, 160.0, 3, first=first + 3) + 2e-5 * self.noise.fbm(self.P, 600.0, 2, first=first + 6)
        lava = (mask * smoothstep(level + eps, level - eps, base + rough)).astype(np.float32)
        a = (1.0 - keep) * lava
        self.H = self.H * (1 - a) + (level + 0.15 * (base - level)) * a
        self.H += lava * ridges * np.maximum(self.noise.ridged(self.P, 26.0, 3, first=first) - 1.2, 0)
        mask = lava
        self.M = np.maximum(self.M, mask)
        self.F *= 1 - mask
        self.Sk *= 1 - mask
        self.Lip *= 1 - mask

    def ring_mountains(self, lat0, lon0, r, height, first=95):
        """A basin's ring of mountains: a broken ridge of peaks round it."""
        c = vec(lat0, lon0).astype(np.float32)
        d = np.arccos(np.clip(self.P @ c, -1, 1)) / r
        peaks = np.maximum(self.noise.ridged(self.P, 1.0 / (0.05 * r), 3, first=first) - 0.9, 0.0)
        broken = smoothstep(-0.3, 0.3, self.noise.fbm(self.P, 0.6 / r, 2, first=first + 2))
        self.H += height * np.exp(-((d - 1.0) / 0.06) ** 2) * (0.35 + peaks) * broken

    def chaos(self, mask, n, amp, seed=0xC4):
        """Chaotic terrain: the ground broken into blocks, each lifted, sunk or tilted on its own, slumped gaps."""
        f1, f2, idx = self.voronoi(n, seed)
        rng = np.random.default_rng(seed)
        lift = rng.normal(0, 1, n).astype(np.float32)
        tilt = rng.normal(0, 1, (n, 3)).astype(np.float32)
        block = lift[idx] + 4.0 * np.einsum("ijk,ijk->ij", self.P, tilt[idx]) * 0.15
        gap = 1.0 - smoothstep(0.0, 0.25 * math.sqrt(4 * math.pi / n), f2 - f1)
        self.H += mask * amp * (block * (1.0 - gap) - 1.5 * gap)
        self.Ch = np.maximum(self.Ch, mask)
        self.F = np.maximum(self.F, mask * gap * 0.5)

    def salt(self, mask, first=100, fill=0.9):
        """Pale salt-like flats: the floor of a closed low ground levelled smooth (nearly free of craters), polygonal
        desiccation cracks in it."""
        core = mask > 0.5
        base = self.g.blur(self.H, 0.004)
        level = float(np.quantile(base[core], fill)) if core.any() else float(base.mean())
        eps = 1.5e-4
        rough = 8e-5 * self.noise.fbm(self.P, 120.0, 3, first=first + 3) + 3e-5 * self.noise.fbm(self.P, 500.0, 2, first=first + 6)
        mask = (mask * smoothstep(level + eps, level - eps, self.g.blur(base, 0.003) + rough)).astype(np.float32)
        self.H = self.H * (1 - 0.95 * mask) + level * 0.95 * mask
        self.Sa = np.maximum(self.Sa, mask)
        self.F *= 1 - mask
        self.Rv *= 1 - mask
        self.cracks(mask, ((120000, 0.06), (12000, 0.04)), 0.0002, first=first)

    def streaks(self, mask, first=110):
        """Dust fields: a soft mantle, its albedo drawn out into streaks by the old winds (east-west, wavering)."""
        g = self.g
        lat = np.asarray(g.lat)[:, None] * np.ones((1, g.W), np.float32)
        lon = np.asarray(g.lon)[None, :] * np.ones((g.H, 1), np.float32)
        wav = self.noise.fbm(self.P, 4.0, 3, first=first)
        q = np.stack([np.cos(lat) * np.sin(lon) * 2.0, (lat + 0.08 * wav) * 60.0, np.cos(lat) * np.cos(lon) * 2.0], -1)
        st = self.noise.fbm(q.astype(np.float32), 1.0, 4, first=first + 2)
        self.Du = np.maximum(self.Du, mask)
        self.St = self.St * (1 - mask) + st * mask
        self.H = self.H * (1 - 0.5 * mask) + self.g.blur(self.H, 0.004) * 0.5 * mask

    def stamp_fields(self):
        for f in ("Sk", "Lip", "Cr", "Sa", "Ch", "Du", "St", "Str"):
            if not hasattr(self, f):
                setattr(self, f, np.zeros_like(self.H))

    def gradients(self, H):
        """dH/d(north), dH/d(east) per radian of surface: from the neighbours a texel away along the great circles
        north and east of each texel, sampled where they are (no pinch at the poles)."""
        g = self.g
        d = g.texel
        lat = g.lat[:, None] * np.ones((1, g.W), np.float32)
        lon = g.lon[None, :] * np.ones((g.H, 1), np.float32)
        sl, cl = np.sin(lat), np.cos(lat)

        def toward(az):
            la = np.arcsin(np.clip(sl * math.cos(d) + cl * math.sin(d) * math.cos(az), -1, 1))
            lo = lon + np.arctan2(math.sin(az) * math.sin(d) * cl, math.cos(d) - sl * np.sin(la))
            return g.sample(H, la, lo)
        gy = (toward(0.0) - toward(math.pi)) / (2 * d)
        gx = (toward(math.pi / 2) - toward(-math.pi / 2)) / (2 * d)
        return gy, gx

    def normals(self, exaggerate):
        gy, gx = self.gradients(self.H * exaggerate)
        n = np.stack([-gx, -gy, np.ones_like(gx)], -1)
        return n / np.linalg.norm(n, axis=-1, keepdims=True)


# ===================================================================================================== the two moons
# Both were once pure white, featureless spheres; they died and their skin withered: plates of the old white still lie
# here and there, lifted and peeling at their edges, over darker strata (redder on Masser, greyer on Secunda), the
# whole cracked in polygons like dried hide. Then came the impacts, the seas, the rifts.
MASSER = dict(rmin_texels=1.0, rt=0.010, basin_r=0.09, ring_r=0.07, dig_r=0.02, exaggerate=1.0, oblique=0.12)
SECUNDA = dict(rmin_texels=1.0, rt=0.012, basin_r=0.10, ring_r=0.08, dig_r=0.03, exaggerate=1.0, oblique=0.14)

# the regions each moon's label map shows (moon_gen.py --preview: <moon>_regions.png), their colours there
REGIONS = {
    "masser": [("rust highlands", (176, 92, 70)), ("old white skin", (236, 214, 206)), ("bared red strata", (130, 40, 30)),
               ("basalt seas", (40, 26, 24)), ("Mara's Tear", (70, 20, 60)), ("the Eye (salt flat)", (250, 240, 215)),
               ("salt flats", (220, 210, 180)), ("the Great Rift", (10, 10, 10)), ("chaos", (120, 80, 140)),
               ("dust fields", (210, 150, 110)), ("old ring basins", (90, 120, 160))],
    "secunda": [("cream highlands", (225, 215, 190)), ("old white skin", (250, 250, 248)), ("bared grey strata", (120, 120, 125)),
                ("dark basins", (50, 52, 60)), ("the Parliament of Craters", (120, 160, 210)),
                ("young rays", (255, 255, 160)), ("sculpted valleys", (160, 120, 90)), ("ring mountains", (90, 150, 110))],
}


def along(n, scale, rng):
    """A smooth random factor along a path of n points (variations every ~scale of its length)."""
    k = max(3, int(1.0 / scale))
    knots = rng.normal(0.0, 1.0, k + 3)
    x = np.linspace(0, k, n)
    i = np.floor(x).astype(int)
    f = x - i
    f = f * f * (3 - 2 * f)
    return knots[i] * (1 - f) + knots[i + 1] * f


def masser_rift(m, rng):
    """The Great Rift: a faulted graben across the face, its width swelling and pinching, branches and side cracks,
    en-echelon fractures beside it, vents with ash and oxide."""
    rng = np.random.default_rng(0x8209)  # (its own: the rift's course is designed: under the Eye, through the Tear's
    # foot, sweeping south then north-east to the chaos)
    paths = []
    main = m.walk(0.42, -1.0, 2.2, 2.2, 1.3, rng, corr=0.25)
    s = np.linspace(0, 1, len(main))
    w = (0.075 * np.sin(np.pi * np.clip(s * 1.05, 0, 1)) ** 0.55 * np.exp(0.35 * along(len(main), 0.08, rng)) + 0.004)
    tone = np.clip(0.9 + 0.25 * along(len(main), 0.12, rng), 0.35, 1.0)
    paths.append((main, w, 0.013 * (w / w.max()) ** 0.5, tone))
    for _ in range(14):
        k = int(rng.uniform(0.12, 0.88) * len(main))
        p0 = main[k]
        lat0, lon0 = math.asin(p0[1]), math.atan2(p0[0], p0[2])
        q = main[min(k + 5, len(main) - 1)] - main[max(k - 5, 0)]
        north = np.array([0.0, 1.0, 0.0]) - p0 * p0[1]
        north /= np.linalg.norm(north)
        head = math.atan2(np.dot(q, np.cross(north, p0)), np.dot(q, north))
        head += rng.choice([-1, 1]) * rng.uniform(0.35, 0.95)
        br = m.walk(lat0, lon0, head, rng.uniform(0.12, 0.6), 1.6, rng)
        sb = np.linspace(0, 1, len(br))
        wb = (w[k] * rng.uniform(0.25, 0.55) * (1 - sb) ** 0.7 * np.exp(0.3 * along(len(br), 0.2, rng)) + 0.0018)
        paths.append((br, wb, 0.011 * (wb / w.max()) ** 0.5, np.clip(0.6 + 0.3 * along(len(br), 0.3, rng), 0.2, 1)))
        if rng.uniform() < 0.6 and len(br) > 40:  # a side crack off the branch
            j = int(rng.uniform(0.3, 0.8) * len(br))
            pj = br[j]
            tw = m.walk(math.asin(pj[1]), math.atan2(pj[0], pj[2]), head + rng.choice([-1, 1]) * rng.uniform(0.5, 1.1),
                        rng.uniform(0.06, 0.25), 2.0, rng)
            st = np.linspace(0, 1, len(tw))
            wt = wb[j] * 0.6 * (1 - st) ** 0.8 + 0.0015
            paths.append((tw, wt, 0.008 * (wt / w.max()) ** 0.5, np.full(len(tw), 0.5)))
    for off in (-2.8, 3.4):  # en-echelon fractures beside it
        side = np.cross(main, np.gradient(main, axis=0))
        side /= np.linalg.norm(side, axis=1, keepdims=True)
        par = main + side * (w[:, None] * off)
        par /= np.linalg.norm(par, axis=1, keepdims=True)
        keep = np.convolve(rng.uniform(0, 1, len(par)) > 0.5, np.ones(90) / 90, "same") > 0.58
        keep &= (s > 0.1) & (s < 0.9)
        if keep.sum() > 10:
            paths.append((par[keep], np.full(keep.sum(), 0.004), np.full(keep.sum(), 0.0010)))
    m.trench(paths, "rift")
    for _ in range(14):  # vents along it: pits ringed by dark ash, some by red
        k = int(rng.uniform(0.05, 0.95) * len(main))
        side = np.cross(main[k], main[min(k + 3, len(main) - 1)] - main[max(k - 3, 0)])
        side /= np.linalg.norm(side) + 1e-12
        q = main[k] + side * w[k] * rng.uniform(-1.6, 1.6)
        q /= np.linalg.norm(q)
        la, lo, r = math.asin(q[1]), math.atan2(q[0], q[2]), w[k] * rng.uniform(0.12, 0.35)
        m.crater(la, lo, r, fresh=0.4)
        m.halo(la, lo, r * rng.uniform(2.5, 4.5), rng.uniform(0.5, 0.9), "D" if rng.uniform() < 0.7 else "Ox")
    return main, w


def build_masser(width, seed=0x4D41):
    m = Moon("masser", MASSER, width, seed)
    m.stamp_fields()
    rng, n, P = m.rng, m.noise, m.P
    t = time.time()
    m.H += 0.0030 * n.fbm(P, 1.6, 6, first=0) + 0.0008 * n.fbm(P, 12.0, 5, first=6)
    # ancient basins; two vast ones on the face with rings of mountains (the old ring basins)
    old_rings = [(-0.48, 0.32, 0.34), (0.66, 0.85, 0.28)]
    for lat, lon, r in old_rings + [(0.35, -0.55, 0.30), (-0.70, -0.45, 0.25), (0.05, 1.25, 0.20),
                                    (-0.05, -1.30, 0.24), (0.95, -1.9, 0.30), (-0.4, 2.4, 0.33), (0.2, 2.9, 0.20)]:
        m.crater(lat + rng.normal(0, 0.02), lon + rng.normal(0, 0.02), r * rng.uniform(0.97, 1.03), fresh=0.0)
    m.degrade(6.0, 0.0)
    m.epoch(0.55, 0.14, "heavy bombardment I", fresh=0.0)
    m.degrade(3.0, 0.0)
    for lat, lon, r in old_rings:
        m.ring_mountains(lat, lon, r * 1.35, 0.0016)
        m.ring_mountains(lat, lon, r * 0.62, 0.0011, first=97)
    # the skin withers: plates of the old white left, peeling, over red strata; cracked like dried hide
    m.skin(0.30, 0.00045, bias=0.6 * smoothstep(-0.2, 0.7, P[..., 2]) - 0.3)
    m.cracks(0.35 + 0.65 * m.Sk, ((9000, 0.05), (60000, 0.05), (300000, 0.07)), 0.00018)
    m.epoch(0.40, 0.09, "heavy bombardment II", fresh=0.6)
    m.degrade(2.2, 0.2)
    m.withered(0.0011, 3.2, (-0.9, -0.3, 0.35, 1.0), 40)
    # the seas: dark basalt over two basins' floors and in Mara's Tear, a teardrop falling from the Eye
    eye = (0.32, -0.30)
    tear_pts, tear_r = [], []
    for k in range(60):
        f = k / 59.0
        la = eye[0] - 0.12 - 0.42 * f
        lo = eye[1] - 0.10 * f - 0.06 * math.sin(f * 3.0)
        tear_pts.append(vec(la, lo))
        tear_r.append(0.025 + 0.16 * f ** 2.2)
    tear = m.tube(np.array(tear_pts), np.array(tear_r) * 1.15, edge=0.15)
    seas = np.maximum(m.cap(-0.48, 0.32, 0.22, edge=0.4, wobble=0.35), m.cap(0.05, 1.25, 0.15, edge=0.5, wobble=0.4))
    m.flood(seas, fill=0.6)
    m.flood(tear, fill=0.88)  # (Mara's Tear nearly brim-full: the teardrop, its edge embaying the relief)
    seas = np.maximum(seas, tear)
    m.R_tear, m.R_seas = tear, seas
    # the Great Rift, its chaos at the east end, ring mountains already up
    main, w = masser_rift(m, rng)
    end = main[-1]  # (the chaos where the rift dies out in the east)
    chaos = m.cap(math.asin(end[1]) - 0.03, math.atan2(end[0], end[2]) - 0.05, 0.17, edge=0.5, wobble=0.5, first=64)
    m.chaos(chaos, 30000, 0.0007)
    m.R_chaos = chaos
    m.degrade(1.4, 0.25)
    m.epoch(0.07, 0.05, "after the seas", fresh=0.5)
    m.degrade(1.0, 0.3)
    m.epoch(0.05, 0.025, "late", fresh=0.6)
    m.degrade(0.7, 0.35)
    # the salt flats, young: the Eye above the Tear, two playas at the rift's ends
    end0, end1 = main[0], main[-1]
    flats = np.maximum(m.cap(eye[0], eye[1], 0.085, edge=0.3, wobble=0.2, first=66),
                       np.maximum(m.cap(math.asin(end0[1]) + 0.05, math.atan2(end0[0], end0[2]) - 0.08, 0.06, edge=0.5,
                                        first=67),
                                  m.cap(math.asin(end1[1]) - 0.06, math.atan2(end1[0], end1[2]) + 0.05, 0.05, edge=0.5,
                                        first=68)))
    m.salt(flats)
    m.R_eye = m.cap(eye[0], eye[1], 0.085, edge=0.3, wobble=0.2, first=66)
    # dust fields in the north, streaked
    dust = smoothstep(0.35, 0.7, P[..., 1] + 0.25 * n.fbm(P, 3.0, 3, first=112)) * (1 - seas) * (1 - flats)
    m.streaks(dust)
    # the young epoch: a few fresh craters, dim rays (Masser's dust), their secondaries
    sec = []
    for _ in range(6):
        r = rng.uniform(0.010, 0.022)
        la, lo = math.asin(rng.uniform(-0.9, 0.9)), rng.uniform(-1.5, 1.5)
        m.crater(la, lo, r, fresh=1.0)
        sec += m.rays(la, lo, r, 0.6, int(rng.integers(10, 22)))
    m.epoch(0.03, 0.01, "young", fresh=1.0)
    for la, lo, r in sec:
        m.crater(la, lo, r, fresh=0.7)
    m.degrade(0.45, 1.0)
    m.R_rings = np.maximum(*[m.cap(la, lo, r * 1.5, edge=0.15) * (1 - m.cap(la, lo, r * 1.15, edge=0.1))
                             for la, lo, r in old_rings])
    print(f"  masser built in {time.time() - t:.0f}s, {m.stats}")
    return m


def paint_masser(m):
    """Masser's colour: rust highlands from rose to umber, the old white skin (bone-pale rose, palest at its peeling
    edges), the red strata it bared, near-black basalt seas, bone-pale salt flats, the rift's dark mantle and red
    oxide, chaos mixed, dust fields soft and streaked; fresh ejecta, steep walls, fine mottling."""
    P, n, H = m.P, m.noise, m.H
    gy, gx = m.gradients(H)
    slope = np.sqrt(gx * gx + gy * gy)
    low = H - m.g.blur(H, 4 * m.g.texel)
    c = lambda *v: np.array(v, np.float32)
    hue = smoothstep(-0.8, 0.8, n.fbm(P, 2.5, 4, first=5))[..., None]
    umber = smoothstep(0.2, 1.0, n.fbm(P, 6.0, 4, first=7))[..., None]
    A = c(0.50, 0.265, 0.27) * (1 - 0.3 * hue) + c(0.47, 0.29, 0.22) * 0.3 * hue  # rose, a little rust
    ej = m.Ej[..., None]
    A = A * (1 - 0.45 * ej) + c(0.40, 0.17, 0.11) * 0.45 * ej                  # thrown-out red strata (rust)
    A = A * (1 - 0.2 * umber) + c(0.30, 0.17, 0.11) * 0.2 * umber              # umber
    strata = (0.5 + 0.5 * np.sin(m.Str))[..., None]
    bared = c(0.36, 0.11, 0.075) * (1 - strata) + c(0.25, 0.085, 0.07) * strata  # red strata in bands
    Sk = m.Sk[..., None]
    A = A * (0.55 + 0.45 * Sk) + bared * (0.45 * (1 - Sk))
    A = A * (1 - 0.6 * Sk) + c(0.66, 0.52, 0.50) * 0.6 * Sk                    # the old white skin (bone-rose)
    A = A * (1 - m.Lip[..., None]) + c(0.80, 0.70, 0.68) * m.Lip[..., None]    # palest at its peeling edges
    A *= (1.0 - 0.18 * m.Cr)[..., None]                                         # the cracks (shadowed, fines)
    sea = np.clip(m.M, 0, 1)[..., None]
    mott = smoothstep(-0.7, 0.7, n.fbm(P, 14.0, 5, first=9))[..., None]
    basalt = c(0.075, 0.042, 0.036) * (1 - mott) + c(0.12, 0.062, 0.048) * mott  # near-black basalt
    A = A * (1 - sea) + basalt * sea
    D = np.clip(m.D, 0, 1)[..., None] * 0.92
    A = A * (1 - D) + c(0.075, 0.03, 0.028) * D
    Ox = np.clip(m.Ox, 0, 1)[..., None] * 0.55
    A = A * (1 - Ox) + c(0.30, 0.075, 0.040) * Ox
    ch = m.Ch[..., None] * 0.5
    A = A * (1 - ch) + (c(0.22, 0.10, 0.08) * mott + c(0.42, 0.22, 0.16) * (1 - mott)) * ch
    du = m.Du[..., None]
    streak = (0.5 + 0.5 * np.tanh(m.St * 1.5))[..., None]
    A = A * (1 - 0.6 * du) + (c(0.55, 0.36, 0.28) * streak + c(0.42, 0.25, 0.19) * (1 - streak)) * 0.6 * du
    sa = m.Sa[..., None]
    A = A * (1 - sa) + c(0.80, 0.74, 0.66) * sa                                 # bone-pale salt
    F = np.clip(m.F * 0.22 + m.Rv * 0.35, 0, 1)[..., None]
    A = A * (1 - F) + c(0.60, 0.42, 0.34) * F
    A *= (1.0 + 0.07 * smoothstep(0.08, 0.6, slope))[..., None]
    A *= (1.0 + 0.06 * np.clip(low / (8 * m.g.texel * 0.05), -1, 1))[..., None]
    A *= np.exp(0.10 * n.fbm(P, 14.0, 5, first=9) + 0.06 * n.fbm(P, 120.0, 4, first=13))[..., None]
    A *= m.grain[..., None]
    soft = np.stack([m.g.blur(A[..., k], 1.2 * m.g.texel) for k in range(3)], -1)  # a dust mantle: soft fine scales
    A = A * 0.7 + soft * 0.3
    # the detail layer's strength by region (albedo alpha): rough chaos, cracked skin and rift strong, seas and salt
    # smooth, dust soft
    rough = (0.55 + 0.35 * m.Ch + 0.25 * m.Sk + 0.3 * np.clip(m.D, 0, 1) - 0.35 * np.clip(m.M, 0, 1) - 0.4 * m.Sa
             - 0.25 * m.Du + 0.3 * m.Bo)
    labels = [1 - np.maximum.reduce([m.Sk, sea[..., 0], m.Sa, m.Ch, m.Du, np.clip(m.D, 0, 1)]),
              m.Sk, (1 - m.Sk) * 0.6, sea[..., 0], m.R_tear, m.R_eye, m.Sa * (1 - m.R_eye),
              np.clip(m.D, 0, 1), m.Ch, m.Du, m.R_rings]
    return A, np.clip(rough, 0, 1), labels


def build_secunda(width, seed=0x5345):
    m = Moon("secunda", SECUNDA, width, seed)
    m.stamp_fields()
    rng, n, P = m.rng, m.noise, m.P
    t = time.time()
    m.H += 0.0035 * n.fbm(P, 1.4, 6, first=0) + 0.001 * n.fbm(P, 10.0, 5, first=6)
    G = (0.42, 0.62, 0.44)  # the giant basin (its sculpture's valleys), off the face's centre
    basins = [(-0.45, -0.55, 0.20), (-0.05, -0.15, 0.17), (0.1, -1.6, 0.26), (-0.3, 2.2, 0.3), (0.9, 2.8, 0.22)]
    for lat, lon, r in basins:
        m.crater(lat, lon, r, fresh=0.0)
    m.degrade(5.0, 0.0)
    m.epoch(0.8, 0.14, "heavy bombardment I", fresh=0.0)
    m.degrade(2.5, 0.0)
    m.skin(0.22, 0.0005, bias=-0.2 * smoothstep(0.3, 0.9, P[..., 2]))
    m.cracks(0.3 + 0.7 * m.Sk, ((6000, 0.05), (30000, 0.05), (110000, 0.07)), 0.0002)
    m.epoch(0.55, 0.09, "heavy bombardment II", fresh=0.6)
    m.withered(0.0014, 3.6, (-1.0, -0.4, 0.2, 0.8, 1.4), 40)
    m.degrade(1.6, 0.25)
    m.crater(*G, fresh=0.3)
    m.ring_mountains(G[0], G[1], G[2] * 1.3, 0.0018)
    for lat, lon, r in basins[:2]:
        m.ring_mountains(lat, lon, r * 1.25, 0.0013, first=96)
    # the giant basin's sculpture: long radial valleys and crater chains across the face
    paths = []
    for i in range(11):
        az = TAU * i / 11 + rng.normal(0, 0.18)
        start = m.g.point(G[0], G[1], az, G[2] * rng.uniform(1.15, 1.4))
        v = m.walk(start[0], start[1], az_at(m.g, G, start, az), rng.uniform(0.35, 0.95), 0.25, rng)
        sv = np.linspace(0, 1, len(v))
        wv = rng.uniform(0.014, 0.028) * (1 - 0.7 * sv) + 0.003
        if i % 3 == 2:
            for j in range(0, len(v), max(1, int(len(v) / rng.uniform(10, 20)))):
                pj = v[j]
                m.crater(math.asin(pj[1]), math.atan2(pj[0], pj[2]), float(wv[j] * rng.uniform(0.9, 1.4)), fresh=0.2)
            continue
        paths.append((v, wv, 0.008 * (wv / 0.02)))
    m.trench(paths, "valley")
    m.R_valleys = m.Tr.copy()
    m.degrade(1.0, 0.3)
    # the dark basins: smooth basalt in the two near-side basins and the giant basin's inner floor, sinuous rilles
    gv = vec(G[0], G[1]).astype(np.float32)
    inG = (1.0 - smoothstep(0.35, 0.7, np.arccos(np.clip(P @ gv, -1, 1)) / G[2])).astype(np.float32)
    dark = np.maximum(np.maximum(m.cap(basins[0][0], basins[0][1], basins[0][2] * 0.8, edge=0.3, wobble=0.3),
                                 m.cap(basins[1][0], basins[1][1], basins[1][2] * 0.75, edge=0.35, wobble=0.35)),
                      inG * smoothstep(-0.4, 0.5, n.fbm(P, 4.0, 4, first=21)))
    m.flood(dark, keep=0.18, ridges=0.0005)
    rilles = []
    for lat, lon, r in basins[:2] + [(G[0], G[1], G[2] * 0.5)]:
        for _ in range(2):
            a = rng.uniform(0, TAU)
            st = m.g.point(lat, lon, a, r * rng.uniform(0.2, 0.6))
            v = m.walk(st[0], st[1], rng.uniform(0, TAU), r * rng.uniform(0.6, 1.2), 6.0, rng, corr=0.03)
            rilles.append((v, np.full(len(v), 0.0035), np.full(len(v), 0.0012)))
    m.trench(rilles, "valley")
    m.R_dark = dark
    m.epoch(0.2, 0.05, "after the seas", fresh=0.7)
    m.degrade(1.0, 0.35)
    m.epoch(0.1, 0.025, "late", fresh=0.9)
    m.degrade(0.6, 0.45)
    # the Parliament of Craters, the grave ghosts' seat (a landmark): a ring of great overlapping craters, young
    # enough to be crisp, their floors drowned in dark cold basalt, their rims bright, round a central one
    prng = np.random.default_rng(0x9A41)
    pc = (0.18, 0.52)
    seats = [(pc[0], pc[1], 0.085)]
    for k in range(8):
        a = TAU * k / 8 + prng.normal(0, 0.15)
        la, lo = m.g.point(pc[0], pc[1], a, prng.uniform(0.11, 0.17))
        seats.append((la, lo, prng.uniform(0.05, 0.085)))
    for la, lo, r in sorted(seats, key=lambda q: -q[2]):
        m.crater(la, lo, r, fresh=0.9, inherit=0.95)
    floors = np.maximum.reduce([m.cap(la, lo, r * 0.62, edge=0.25, wobble=0.15, first=70 + k)
                                for k, (la, lo, r) in enumerate(seats)])
    m.flood(floors, fill=0.92, keep=0.3, ridges=0.0002)
    m.M = np.maximum(m.M - floors, 0)  # (their floors are the Parliament's own dark, not the basins')
    m.R_parl = m.cap(pc[0], pc[1], 0.27, edge=0.3, wobble=0.2, first=69)
    m.R_parl_floor = floors
    m.degrade(0.3, 0.9)
    # young bright craters with long ray systems crossing the face; the greatest, the Weeping Star
    sec = []
    stars = [(-0.36, -0.28, 0.032, 1.3, 34)] + [(math.asin(rng.uniform(-0.85, 0.85)), rng.uniform(-1.4, 1.4),
                                                  rng.uniform(0.012, 0.026), 0.8, int(rng.integers(14, 28))) for _ in range(5)]
    for la, lo, r, k, nr in stars:
        m.crater(la, lo, r, fresh=1.0)
        sec += m.rays(la, lo, r, k, nr, long=2.0 if k > 1 else 1.0)
    m.epoch(0.05, 0.012, "young", fresh=1.0)
    for la, lo, r in sec:
        m.crater(la, lo, r, fresh=0.8)
    m.degrade(0.35, 1.0)
    m.R_rings = np.maximum(m.cap(G[0], G[1], G[2] * 1.45, edge=0.12) * (1 - m.cap(G[0], G[1], G[2] * 1.15, edge=0.1)),
                           np.maximum.reduce([m.cap(la, lo, r * 1.4, edge=0.15) * (1 - m.cap(la, lo, r * 1.1, edge=0.1))
                                              for la, lo, r in basins[:2]]))
    print(f"  secunda built in {time.time() - t:.0f}s, {m.stats}")
    return m


def paint_secunda(m):
    """Secunda's colour: warm cream highlands and cooler grey ones, the old white skin, the grey strata it bared, dark
    basalt basins, near-white young rays, the Parliament's cool grey floors and bright rims."""
    P, n, H = m.P, m.noise, m.H
    gy, gx = m.gradients(H)
    slope = np.sqrt(gx * gx + gy * gy)
    low = H - m.g.blur(H, 4 * m.g.texel)
    c = lambda *v: np.array(v, np.float32)
    warm = smoothstep(-0.8, 0.8, n.fbm(P, 2.2, 4, first=5))[..., None]
    A = c(0.53, 0.50, 0.43) * (0.65 + 0.35 * warm) + c(0.46, 0.46, 0.45) * 0.35 * (1 - warm)  # cream .. grey
    ej = m.Ej[..., None]
    A = A * (1 - 0.3 * ej) + c(0.40, 0.40, 0.41) * 0.3 * ej                    # thrown-out grey strata
    strata = (0.5 + 0.5 * np.sin(m.Str))[..., None]
    bared = c(0.33, 0.33, 0.34) * (1 - strata) + c(0.27, 0.27, 0.29) * strata
    Sk = m.Sk[..., None]
    A = A * (0.6 + 0.4 * Sk) + bared * (0.4 * (1 - Sk))
    A = A * (1 - 0.6 * Sk) + c(0.68, 0.67, 0.64) * 0.6 * Sk
    A = A * (1 - m.Lip[..., None]) + c(0.80, 0.80, 0.78) * m.Lip[..., None]
    A *= (1.0 - 0.15 * m.Cr)[..., None]
    sea = np.clip(m.M, 0, 1)[..., None]
    mott = smoothstep(-0.7, 0.7, n.fbm(P, 12.0, 5, first=9))[..., None]
    A = A * (1 - sea) + (c(0.17, 0.17, 0.19) * (1 - mott) + c(0.22, 0.22, 0.235) * mott) * sea
    par = m.R_parl[..., None] * 0.35
    A = A * (1 - par) + c(0.44, 0.46, 0.50) * par                                 # the Parliament: cool grey
    pf = m.R_parl_floor[..., None]
    A = A * (1 - pf) + c(0.19, 0.20, 0.23) * pf                                   # its drowned floors, cold dark
    F = np.clip(m.F * 0.6 + m.Rv * 0.8, 0, 1.2)[..., None]
    fr = c(0.98, 0.97, 0.93)
    A = A * (1 - np.minimum(F, 1)) + fr * np.minimum(F, 1) + (F > 1) * (F - 1) * fr
    A *= (1.0 + 0.22 * smoothstep(0.08, 0.6, slope) * (1 + 0.6 * m.R_parl))[..., None]
    A *= (1.0 + 0.06 * np.clip(low / (8 * m.g.texel * 0.05), -1, 1))[..., None]
    A *= np.exp(0.07 * n.fbm(P, 14.0, 5, first=9) + 0.07 * n.fbm(P, 120.0, 4, first=13))[..., None]
    A *= m.grain[..., None]
    rough = 0.6 + 0.25 * m.Sk + 0.2 * m.R_parl - 0.4 * np.clip(m.M, 0, 1) + 0.3 * m.Bo
    labels = [1 - np.maximum.reduce([m.Sk, sea[..., 0], m.R_parl, np.clip(m.Rv, 0, 1)]), m.Sk, (1 - m.Sk) * 0.55,
              sea[..., 0], m.R_parl, np.clip(m.Rv, 0, 1), m.R_valleys, m.R_rings]
    return A, np.clip(rough, 0, 1), labels


def az_at(g, G, start, az0):
    """The azimuth pointing away from the basin's centre at start."""
    a = vec(*start)
    c = vec(G[0], G[1])
    away = a - c
    north = np.array([0.0, 1.0, 0.0]) - a * a[1]
    north /= np.linalg.norm(north)
    east = np.cross(north, a)
    return math.atan2(np.dot(away, east), np.dot(away, north))


# ===================================================================================================== shading
def face_weight(g: Grid):
    """The projected weight of each texel on the face towards Nirn (the disc's mean at full)."""
    P = g.xyz()
    return np.maximum(P[..., 2], 0) * g.clat[:, None]


def bilinear(img, u, v):
    """img (h, w, c) sampled at u (wraps) and v (clamps) in [0, 1], texel centres at (i + 0.5) / n."""
    h, w = img.shape[:2]
    x = u * w - 0.5
    y = np.clip(v * h - 0.5, 0, h - 1)
    x0 = np.floor(x).astype(np.int64)
    y0 = np.floor(y).astype(np.int64)
    fx = (x - x0)[..., None]
    fy = (y - y0)[..., None]
    y1 = np.minimum(y0 + 1, h - 1)
    a = img[y0, x0 % w] * (1 - fx) + img[y0, (x0 + 1) % w] * fx
    b = img[y1, x0 % w] * (1 - fx) + img[y1, (x0 + 1) % w] * fx
    return a * (1 - fy) + b * fy


def mips(img, levels):
    out = [img.astype(np.float32)]
    for _ in range(levels):
        a = out[-1]
        out.append(0.25 * (a[0::2, 0::2] + a[1::2, 0::2] + a[0::2, 1::2] + a[1::2, 1::2]))
    return out


# the moons' photometry (atlas_night.glsl nightMoon): lunar-Lambert limb weight, opposition surge, phase darkening
PHOTO = {"masser": dict(L=0.88, B0=0.45, h=0.08, k=0.35), "secunda": dict(L=0.92, B0=0.75, h=0.05, k=0.30)}
SUN_RAD = 0.0087  # the sun's angular radius from the moons (penumbra)
NIRNSHINE = 0.0015


def shade_disc(albedo_mips, relief_mips, relief_scale, size, sun, photo, pix_scale=1.0, shadows=True):
    """The disc as nightMoon shades it: rgb linear (radiance per unit albedo at full: 1), alpha. sun: body frame
    (x right, y north, z towards the eye). size: the disc's diameter in pixels."""
    W = albedo_mips[0].shape[1]
    texel = TAU / W
    c = (np.arange(size) + 0.5) / size * 2 - 1
    X, Y = np.meshgrid(c, -c)
    rho2 = X * X + Y * Y
    inside = rho2 < 1.0
    x, y = X[inside], Y[inside]
    mu = np.sqrt(np.maximum(1 - x * x - y * y, 0))
    lat = np.arcsin(np.clip(y, -1, 1))
    lon = np.arctan2(x, mu)
    u = lon / TAU + 0.5
    v = 0.5 - lat / math.pi
    f = 2.0 / size * pix_scale  # the footprint (radii a pixel)
    clat = np.cos(lat)
    lod = np.log2(np.maximum(f / texel * np.maximum(1 / np.maximum(mu, 0.15), 1 / np.maximum(clat, 0.1)), 1e-6))
    lod = np.clip(lod, 0, len(albedo_mips) - 1)

    def sample(stack, uu, vv, ll):
        l0 = np.floor(ll).astype(int)
        fl = (ll - l0)[..., None]
        out = np.zeros(uu.shape + (stack[0].shape[2],), np.float32)
        for L in np.unique(l0):
            sel = l0 == L
            a = bilinear(stack[L], uu[sel], vv[sel])
            b = bilinear(stack[min(L + 1, len(stack) - 1)], uu[sel], vv[sel])
            out[sel] = a * (1 - fl[sel]) + b * fl[sel]
        return out

    alb = sample(albedo_mips, u, v, lod)
    rel = sample(relief_mips, u, v, lod)
    nxy = rel[:, :2] * 2 - 1
    tn = np.concatenate([nxy, np.sqrt(np.maximum(1 - (nxy ** 2).sum(1, keepdims=True), 0.02))], 1)
    tn /= np.linalg.norm(tn, axis=1, keepdims=True)
    sl, cl, so, co = np.sin(lat), clat, np.sin(lon), np.cos(lon)
    e = np.stack([co, np.zeros_like(co), -so], 1)
    nn = np.stack([-sl * so, cl, -sl * co], 1)
    p = np.stack([cl * so, sl, cl * co], 1)
    nb = tn[:, :1] * e + tn[:, 1:2] * nn + tn[:, 2:3] * p
    # (towards the limb the relief fades into the sphere: its facets there face away, unseen)
    fade = smoothstep(0.0, 0.3, mu)[:, None]
    nb = p + (nb - p) * fade
    nb[:, 2] += np.maximum(0.15 - nb[:, 2], 0)  # (no facet turned from the eye: what hides it is seen instead)
    nb /= np.linalg.norm(nb, axis=1, keepdims=True)
    sun = np.asarray(sun, np.float64)
    mu0g = p @ sun
    mu0 = np.maximum(nb @ sun, 0)
    mud = np.maximum(nb[:, 2], 0.5 * mu + 0.02)
    alpha = math.acos(np.clip(sun[2], -1, 1))
    L = photo["L"]
    B = (1 + photo["B0"] / (1 + math.tan(alpha / 2) / photo["h"])) / (1 + photo["B0"])
    phase = B * math.exp(-photo["k"] * alpha)
    norm = 1.0 / (L + (1 - L) * 2.0 / 3.0)
    shade = (2 * L * mu0 / np.maximum(mu0 + mud, 1e-4) + (1 - L) * mu0) * phase * norm
    shade *= smoothstep(-0.03, 0.01, mu0g)
    if shadows:
        se, sn, su = (e @ sun), (nn @ sun), (p @ sun)
        sl2 = np.sqrt(se * se + sn * sn) + 1e-6
        de, dn = se / sl2, sn / sl2
        tanE = su / sl2
        h0 = ((rel[:, 2] * 65280 + rel[:, 3] * 255) / 65535 * 2 - 1) * relief_scale
        lit = np.ones_like(h0)
        active = (mu0g > -0.03) & (mu0g < 0.45)
        t = max(texel * 1.5, f * 0.75)
        for k in range(12):
            uu = u + de * t / (TAU * np.maximum(clat, 0.05))
            vv = v - dn * t / math.pi
            lk = np.clip(np.log2(max(t * 0.25, f) / texel) * np.ones_like(u), 0, len(relief_mips) - 1)
            rk = sample(relief_mips, uu[active], vv[active], lk[active])
            hk = ((rk[:, 2] * 65280 + rk[:, 3] * 255) / 65535 * 2 - 1) * relief_scale
            z = h0[active] + t * tanE[active] + 0.5 * t * t
            lit[active] = np.minimum(lit[active], np.clip((z - hk) / (t * SUN_RAD * 2) + 0.5, 0, 1))
            t *= 1.55
        # (shadows show only off the sun's line: none at full, where each hides behind what casts it; and they fade
        # into the limb with the relief)
        # (the eye sees past the caster only off the sun's side: none at full, little towards the limb)
        vde, vdn = -so, -sl * co
        vl = np.sqrt(vde * vde + vdn * vdn) + 1e-6
        hidden = np.clip(tanE * vl / np.maximum(mu, 1e-4) * np.maximum((de * vde + dn * vdn) / vl, 0), 0, 1)
        seen = (1.0 - hidden) * smoothstep(0.0, 0.3, mu)
        shade *= 1.0 - (1.0 - lit) * seen
    fill = NIRNSHINE * (0.5 - 0.5 * math.cos(alpha)) ** 1.5 * (L + (1 - L) * mud) * norm
    rgb = np.zeros((size, size, 3), np.float32)
    rgb[inside] = alb * (shade + fill)[:, None]
    a = np.zeros((size, size), np.float32)
    a[inside] = 1
    return rgb, a


def sun_for(phase_angle, pa=0.0):
    """A sun direction in the disc's frame: phase angle (0 full), lit limb towards position angle pa (0: right)."""
    return np.array([math.sin(phase_angle) * math.cos(pa), math.sin(phase_angle) * math.sin(pa), math.cos(phase_angle)])


def to_srgb8(lin):
    return (np.clip(lin, 0, 1) ** (1 / 2.2) * 255 + 0.5).astype(np.uint8)


# ===================================================================================================== outputs
def bake(m: Moon, out: Path, preview: bool):
    name, s = m.name, m.s
    t = time.time()
    m.grain = m.micro()
    A, rough, labels = (paint_masser if name == "masser" else paint_secunda)(m)
    region_map(name, labels, out if preview else None)
    w = face_weight(m.g)[..., None]
    mean = (A * w).sum((0, 1)) / w.sum()
    A *= np.array(TARGET[name], np.float32) / mean
    print(f"  {name} albedo mean {mean.round(4).tolist()} -> {TARGET[name]}, max {A.max():.3f}")
    alb8 = np.concatenate([to_srgb8(A), (rough[..., None] * 255 + 0.5).astype(np.uint8)], -1)
    n = m.normals(s["exaggerate"])
    hp = m.H - m.g.blur(m.H, 0.12)
    scale = RELIEF[name]
    clip = float(np.mean(np.abs(hp) > scale))
    print(f"  {name} local relief: p99.9 {np.percentile(np.abs(hp), 99.9):.4f}, scale {scale}, clipped {clip:.2%}")
    rel = np.empty(n.shape[:2] + (4,), np.uint8)
    rel[..., :2] = np.clip(n[..., :2] * 127.5 + 127.5, 0, 255).astype(np.uint8)
    h16 = np.clip((hp / scale * 0.5 + 0.5) * 65535 + 0.5, 0, 65535).astype(np.uint32)
    rel[..., 2] = (h16 >> 8).astype(np.uint8)
    rel[..., 3] = (h16 & 255).astype(np.uint8)
    out.mkdir(parents=True, exist_ok=True)
    Image.fromarray(alb8, "RGBA").save(out / f"{name}_albedo.png", optimize=True)
    Image.fromarray(rel, "RGBA").save(out / f"{name}_relief.png", optimize=True)
    for f in ("albedo", "relief"):
        (out / f"{name}_{f}.png.mcmeta").write_text(json.dumps({"texture": {"blur": True}}) + "\n")
    # the decoded maps as the shader sees them (8 bit, gamma 2.2), their mips
    am = mips((alb8[..., :3].astype(np.float32) / 255) ** 2.2, 7)
    rm = mips(rel.astype(np.float32) / 255, 7)
    phases(name, am, rm, out)
    detail(name, out)
    print(f"  {name} written in {time.time() - t:.0f}s")
    if preview:
        previews(name, am, rm, WORK)


# the tiling detail layers: a tile's size on the sphere (moon radii: 16 of the map's texels; atlas_moons.glsl
# the detail layers: a tile's size on the sphere (moon radii: 16 of the map's texels; atlas_moons.glsl
# MASSER_DETAIL_TILE / SECUNDA_DETAIL_TILE) and its texels
DETAIL_TILE = {"masser": 16 * 2 * math.pi / 4096, "secunda": 16 * 2 * math.pi / 2048}
DETAIL_SIZE = 1024


def detail(name, out, size=DETAIL_SIZE):
    """The moon's detail layer, below the map's texels (for a disc magnified past its map): seamless (periodic noise,
    craters and cells wrapped round the tile), sampled in the shader warped and at two scales, its strength by the
    region (the albedo's alpha), so it never shows a repeat. rg the slope (the detail normal's x, y: 0.5 flat), b the
    albedo factor (0.5: x 1), a the height. Generations of small craters by a power law (round, oblique ellipses with
    their ejecta thrown downrange, doublets, chains, secondaries round a larger one; the older worn and some drowned to
    ghosts), flakes of the withered skin with polygonal cracks and lifted lips, boulders, the regolith's grain."""
    rng = np.random.default_rng(0xD7 if name == "masser" else 0xD9)
    k = np.fft.fftfreq(size) * size
    kk = np.sqrt(k[:, None] ** 2 + k[None, :] ** 2)
    kk[0, 0] = 1.0

    def spectral(beta):
        z = rng.standard_normal((size, size)) + 1j * rng.standard_normal((size, size))
        f = np.real(np.fft.ifft2(z * kk ** (-beta / 2.0) * (kk > 0.5)))
        return (f / f.std()).astype(np.float32)

    def wear(a, sigma):  # (periodic diffusion: the older generations softened)
        return ndimage.gaussian_filter(a, sigma, mode="wrap")

    h = 2.0 * spectral(2.6) + 0.3 * spectral(1.6)  # (height in texels of the tile)
    alb = np.zeros((size, size), np.float32)

    def crater(cx, cy, ri, e=1.0, phi=0.0, ghost=0.0, fresh=0.0):
        nonlocal h
        ext = int(ri * e * 2.8) + 2
        x0, y0 = int(cx) - ext, int(cy) - ext
        ys = np.arange(y0, y0 + 2 * ext + 1) % size
        xs = np.arange(x0, x0 + 2 * ext + 1) % size
        dx = (np.arange(x0, x0 + 2 * ext + 1) - cx)[None, :]
        dy = (np.arange(y0, y0 + 2 * ext + 1) - cy)[:, None]
        ang = np.arctan2(dy, dx)
        d = np.sqrt(dx * dx + dy * dy) / ri
        x = d * np.sqrt(np.cos(ang - phi) ** 2 / (e * e) + np.sin(ang - phi) ** 2)
        depth, rim = 0.32 * ri * (1 - ghost), 0.07 * ri
        prof = np.where(x < 1.0, -depth + (depth + rim) * x ** 2, rim * np.maximum(x, 1.0) ** -3 * (x < 2.6))
        if e > 1.4:  # (the ejecta thrown downrange)
            prof = np.where(x > 1.0, prof * (1 - 0.85 * smoothstep(0.5, 0.95, -np.cos(ang - phi))), prof)
        a = 0.9 * (1 - smoothstep(0.8, 1.05, x))
        patch = h[np.ix_(ys, xs)]
        ref = patch[x < 0.8].mean() if (x < 0.8).any() else patch.mean()
        h[np.ix_(ys, xs)] = (1 - a) * (patch + np.where(x < 1, rim, prof)) + a * (ref + prof)
        if fresh > 0:
            alb[np.ix_(ys, xs)] += fresh * 0.25 * np.exp(-np.maximum(x - 0.9, 0) * 1.5) * (x < 2.5)

    def population(nc, rmin, rmax, fresh, ghosts):
        u = rng.uniform(0, 1, nc)
        r = rmin * (1 - u * (1 - (rmin / rmax) ** 2)) ** -0.5
        for i in np.argsort(-r):
            cx, cy = rng.uniform(0, size, 2)
            q = rng.uniform()
            if q < 0.12:  # oblique
                crater(cx, cy, r[i], rng.uniform(1.2, 1.9), rng.uniform(0, TAU), fresh=fresh)
            elif q < 0.15:  # a doublet
                a = rng.uniform(0, TAU)
                crater(cx, cy, r[i], fresh=fresh)
                crater(cx + math.cos(a) * r[i] * 2.0, cy + math.sin(a) * r[i] * 2.0, r[i] * rng.uniform(0.5, 1.0), fresh=fresh)
            elif q < 0.16 and r[i] > 3:  # a chain
                a, rc = rng.uniform(0, TAU), r[i] * 0.4
                for j in range(int(rng.integers(5, 12))):
                    crater(cx + math.cos(a) * rc * 1.8 * j, cy + math.sin(a) * rc * 1.8 * j, rc * rng.uniform(0.7, 1.2), fresh=fresh)
            else:
                crater(cx, cy, r[i], ghost=ghosts * float(rng.uniform() < 0.25), fresh=fresh)
    # three generations, the older worn and some drowned to ghosts
    population(500, 2.0, size / 6.0, 0.0, 0.85)
    h = wear(h, 2.2)
    # the withered skin's flakes: cells (periodic), each lifted a little or not, cracked at the edges, lips lifted
    cells = rng.uniform(0, size, (90, 2))
    yy, xx = np.mgrid[:size, :size].astype(np.float32)
    d1 = np.full((size, size), 1e9, np.float32)
    d2 = np.full((size, size), 1e9, np.float32)
    owner = np.zeros((size, size), np.int32)
    for i, (cx, cy) in enumerate(cells):
        ddx = np.abs(xx - cx)
        ddy = np.abs(yy - cy)
        dd = np.sqrt(np.minimum(ddx, size - ddx) ** 2 + np.minimum(ddy, size - ddy) ** 2)
        closer = dd < d1
        d2 = np.where(closer, d1, np.minimum(d2, dd))
        owner = np.where(closer, i, owner)
        d1 = np.where(closer, dd, d1)
    lifted = (rng.uniform(0, 1, len(cells)) < 0.45)[owner]
    edge = d2 - d1
    crack = 1.0 - smoothstep(0.0, 2.5, edge)
    lip = smoothstep(9.0, 2.0, edge) * lifted
    h += 3.0 * lifted * smoothstep(1.5, 4.0, edge) + 1.2 * lip - 2.0 * crack
    alb += 0.12 * lifted + 0.10 * lip - 0.18 * crack
    population(1400, 1.4, size / 9.0, 0.3, 0.4)
    h = wear(h, 1.0)
    population(1800, 1.1, size / 14.0, 0.8, 0.0)
    # secondaries round one larger young crater
    cx, cy, rr = size * 0.3, size * 0.6, size / 16.0
    crater(cx, cy, rr, fresh=1.0)
    for _ in range(140):
        a, dd = rng.uniform(0, TAU), rr * rng.uniform(1.8, 5.0)
        crater(cx + math.cos(a) * dd, cy + math.sin(a) * dd, rr * rng.uniform(0.04, 0.12), rng.uniform(1.0, 1.6), a,
               fresh=0.6)
    # boulders: sparse blocks, bright and dark, each a few texels; the grain
    white = rng.standard_normal((size, size)).astype(np.float32)
    bould = np.maximum(np.real(np.fft.ifft2(np.fft.fft2(white) * np.exp(-(kk / (size / 5.0)) ** 2))), 0) ** 3
    bould = (bould / (bould.max() + 1e-6)).astype(np.float32)
    h += 2.0 * bould
    gx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * 0.5
    gy = (np.roll(h, 1, 0) - np.roll(h, -1, 0)) * 0.5  # (north up: row 0 the top)
    slope = 0.45
    albf = np.exp(0.10 * spectral(1.4) + 0.05 * white + alb - 0.25 * bould * np.sign(rng.standard_normal((size, size))))
    albf /= albf.mean()
    img = np.empty((size, size, 4), np.uint8)
    img[..., 0] = np.clip(0.5 - gx * slope * 0.5, 0, 1) * 255
    img[..., 1] = np.clip(0.5 - gy * slope * 0.5, 0, 1) * 255
    img[..., 2] = np.clip(albf * 0.5, 0, 1) * 255
    img[..., 3] = np.clip((h - h.min()) / (h.max() - h.min() + 1e-6), 0, 1) * 255
    Image.fromarray(img, "RGBA").save(out / f"{name}_detail.png", optimize=True)
    (out / f"{name}_detail.png.mcmeta").write_text(json.dumps({"texture": {"blur": True}}) + "\n")


def region_map(name, labels, out):
    """The moon's regions, labelled: each texel the colour of its strongest region (REGIONS), a legend; into WORK."""
    WORK.mkdir(parents=True, exist_ok=True)
    names = REGIONS[name]
    stack = np.stack([np.asarray(l, np.float32) for l in labels], 0)
    k = np.argmax(stack, 0)
    cols = np.array([c for _n, c in names], np.uint8)
    img = cols[k]
    h, w = img.shape[:2]
    sc = 1024 / w
    im = Image.fromarray(img).resize((1024, int(h * sc)), Image.NEAREST)
    from PIL import ImageDraw
    sheet = Image.new("RGB", (1024 + 260, max(im.height, 24 * len(names) + 20)), (16, 16, 16))
    sheet.paste(im, (0, 0))
    d = ImageDraw.Draw(sheet)
    for i, (label, c) in enumerate(names):
        d.rectangle([1034, 10 + 24 * i, 1052, 28 + 24 * i], fill=tuple(int(v) for v in c))
        d.text((1060, 12 + 24 * i), label, fill=(230, 230, 230))
    # the face towards Nirn: longitude -90 .. 90
    d.rectangle([256, 0, 768, im.height - 1], outline=(255, 255, 255))
    d.text((262, 4), "face towards Nirn", fill=(255, 255, 255))
    sheet.save(WORK / f"{name}_regions.png")


def phases(name, am, rm, out):
    """The 4 x 4 phase sheet (0 full, 4 a quarter lit from the right, 8 new) for the pack and the mesh sky."""
    cell = 512 if name == "masser" else 256
    disc = int(cell * 0.92) // 2 * 2
    sheet = np.zeros((cell * 4, cell * 4, 4), np.uint8)
    for k in range(16):
        sun = sun_for(k * math.pi / 8)
        rgb, a = shade_disc(am, rm, RELIEF[name], disc, sun, PHOTO[name], shadows=True)
        rgb = rgb * 0.85
        o = (cell - disc) // 2
        r, c = divmod(k, 4)
        block = sheet[r * cell:(r + 1) * cell, c * cell:(c + 1) * cell]
        block[o:o + disc, o:o + disc, :3] = to_srgb8(rgb)
        block[o:o + disc, o:o + disc, 3] = (a * 255).astype(np.uint8)
    Image.fromarray(sheet, "RGBA").save(out / f"{name}_phases.png", optimize=True)
    (out / f"{name}_phases.png.mcmeta").write_text(json.dumps({"texture": {"blur": True, "clamp": True}}) + "\n")


def previews(name, am, rm, work):
    work.mkdir(parents=True, exist_ok=True)
    W = am[0].shape[1]
    full = int(W / math.pi) // 2 * 2  # the disc at 1:1 at its centre
    expo = {"masser": 2.4, "secunda": 2.6}[name]
    col = np.array(COLOUR[name])
    alb = np.array(TARGET[name])
    lum = np.array([0.2126, 0.7152, 0.0722])
    tint = (col / (col @ lum)) / (alb / (alb @ lum))

    def disp(rgb, e=expo):
        return Image.fromarray(to_srgb8(rgb * e * tint))
    tiles = []
    for label, ang, pa in [("full", 0.0, 0.0), ("gibbous", 0.9, 0.3), ("half", math.pi / 2, 0.3),
                           ("crescent", 2.3, 0.3), ("new", math.pi, 0.0)]:
        rgb, _a = shade_disc(am, rm, RELIEF[name], 480, sun_for(ang, pa), PHOTO[name], pix_scale=1.0)
        tiles.append(disp(rgb, expo * (40 if label == "new" else 1)))
    sheet = Image.new("RGB", (480 * len(tiles), 480))
    for i, t in enumerate(tiles):
        sheet.paste(t, (480 * i, 0))
    sheet.save(work / f"{name}_discs.png")
    # 1:1 discs: full and near the terminator (a half moon), crops of each
    for label, ang in [("full", 0.0), ("half", math.pi / 2 - 0.05), ("crescent", 2.2)]:
        rgb, _a = shade_disc(am, rm, RELIEF[name], full, sun_for(ang, 0.25), PHOTO[name])
        img = disp(rgb)
        img.save(work / f"{name}_{label}_1to1.png")
    Image.fromarray(to_srgb8(am[1] * 2.0 * tint)).save(work / f"{name}_albedo_half.png")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--out", default=str(DEST))
    ap.add_argument("--preview", action="store_true")
    a = ap.parse_args()
    out = Path(a.out)
    for name, build, width in (("masser", build_masser, 4096), ("secunda", build_secunda, 2048)):
        if a.only and a.only != name:
            continue
        print(f"{name}: {int(width * a.scale)} x {int(width * a.scale) // 2}")
        m = build(int(width * a.scale))
        bake(m, out, a.preview)


if __name__ == "__main__":
    main()
