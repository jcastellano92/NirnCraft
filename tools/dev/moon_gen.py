"""Masser and Secunda, baked: their surfaces simulated from nothing but this code and its seeds.

No photograph, scan, game asset or traced shape goes in. Each moon is a sphere of rock built up through time:

  1. a crust: long-wave regional relief (fBm on the sphere);
  2. ancient basins: huge shallow impacts with peak rings and outer scarps;
  3. epochs of bombardment, each a population drawn from a power-law size-frequency distribution (cumulative
     N(>r) ~ r^-2): simple bowls below the transition radius, complex craters above it (flat floors, terraced walls,
     central peaks, peak rings in the largest), irregular polygonal outlines, raised rims, ejecta blankets with radial
     texture. A new crater excavates what was there (a trace of the old relief survives) and buries its surroundings;
  4. between epochs, topographic diffusion (creep, micro-impacts, dust) softens everything already there: the older a
     crater, the more it has been worn, the smaller it is, the sooner it is gone;
  5. the withered skin: lobate scarps, long sinuous one-sided cliffs where the dead crust shrank, in broken segments;
     Masser: lava plains flood its low ground in broad mottled patches (ghost craters, wrinkle ridges), then its rift
     opens: a long branching graben system with ragged walls, raised shoulders and a dark mantle round it;
     Secunda: a giant basin whose ejecta carved long radial valleys and crater chains across its face;
  6. a last, young epoch: fresh craters with bright ejecta, rays and chains of secondaries.

Albedo follows the material: highland crust, plains, the rift's dark mantle, fresh (immature) ejecta that weathers
darker with every epoch, steep walls brighter (fresh exposures), crater floors a touch darker. Masser's palette is
rose to rust-brown, dusty and low-contrast; Secunda's grey-cream, bright and crisp.

Outputs (textures/environment), each an equirectangular map, longitude 0 / latitude 0 the face towards Nirn (both
moons are tidally locked: the shader keeps that face to the planet), north up, east to the right, row 0 the north:

  <moon>_albedo.png  rgb albedo (gamma 2.2), its mean over the face Nirn sees (projected) the albedo NightSkyMath
                     keeps for the moon (MASSER_ALBEDO / SECUNDA_ALBEDO), so the sky's photometry is unchanged
  <moon>_relief.png  rgb the surface normal in the local (east, north, up) frame, from the height map; a the local
                     height (the height less its 0.06 rad blur): h = (a * 2 - 1) * RELIEF[moon] in moon radii, the
                     shader's cast-shadow march (atlas_night.glsl MASSER_RELIEF / SECUNDA_RELIEF)
  <moon>_phases.png  the 16-phase 4 x 4 sheet the shader pack and the mesh fallback sky draw, rendered from the maps

    py -3 tools/dev/moon_gen.py                 # both moons at full size, into the mod's textures
    py -3 tools/dev/moon_gen.py --only masser --scale 0.5 --out .work/moon_gen/half   # a quick look
    py -3 tools/dev/moon_gen.py --preview       # also renders discs and full-zoom crops into .work/moon_gen

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
RELIEF = {"masser": 0.010, "secunda": 0.012}
# NightSkyMath.MASSER_ALBEDO / SECUNDA_ALBEDO: the linear mean the face towards Nirn keeps
TARGET = {"masser": (0.1708, 0.1304, 0.1494), "secunda": (0.1077, 0.1110, 0.1063)}
# NightSkyMath.MASSER_COLOUR / SECUNDA_COLOUR (the previews show the discs in the sky's colour)
COLOUR = {"masser": (1.0, 0.70, 0.62), "secunda": (0.93, 0.96, 1.0)}


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
        return ndimage.gaussian_filter1d(out, sigma / self.texel, axis=0, mode="nearest", truncate=3.0)

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
        self.P = g.xyz()
        self.rmin = spec["rmin_texels"] * g.texel
        self.stats = {"craters": 0}

    # ------------------------------------------------------------------------------------------- one crater
    def profile(self, x, az, r, rng):
        s = self.s
        rt = s["rt"]
        ph = rng.uniform(0, TAU, 8)
        if r < rt:
            depth = 0.30 * r
            rim = 0.065 * r
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
        ext = r * (3.3 if r <= self.s["basin_r"] else 1.8)
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
        prof, rim, depth = self.profile(x, az, r, rng)
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
        dig = float(smoothstep(self.s["dig_r"] * 0.6, self.s["dig_r"] * 1.6, r))
        if dig > 0:
            self.M[idx] *= (1.0 - dig * ej * 0.9).astype(np.float32)
        # (small craters punch through the rift's thin dark mantle: bright-haloed pits in it)
        self.D[idx] *= (1.0 - 0.3 * (1.0 - smoothstep(0.7, 1.3, x))).astype(np.float32)
        self.stats["craters"] += 1

    def rays(self, lat0, lon0, r, strength, n_rays, secondaries=True):
        """Bright rays of fine ejecta (and chains of secondary craters along them) from a young crater."""
        rng = self.rng
        L = r * rng.lognormal(math.log(7.0), 0.45, n_rays)
        L = np.minimum(L, r * 16)
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
        """Carves paths: each (points, half-width per point, depth per point). kind 'rift' (graben: flat floor,
        steep walls, raised shoulders, the dark mantle) or 'valley' (a V-to-U sculpted valley, low levees)."""
        pts = np.concatenate([p[0] for p in paths])
        w = np.concatenate([p[1] for p in paths]).astype(np.float32)
        dep = np.concatenate([p[2] for p in paths]).astype(np.float32)
        tree = cKDTree(pts)
        reach = float(w.max()) * (6.0 if kind == "rift" else 3.0)
        g = self.g
        carve = np.zeros_like(self.H)
        lift = np.zeros_like(self.H)
        dark = np.zeros_like(self.H)
        warp = self.noise.fbm(self.P, 9.0, 5, first=17)
        rough = self.noise.fbm(self.P, 60.0, 4, first=3)
        band = 64
        for r0 in range(0, g.H, band):
            rows = slice(r0, min(g.H, r0 + band))
            P = self.P[rows].reshape(-1, 3)
            dist, i = tree.query(P, distance_upper_bound=reach, workers=-1)
            ok = np.isfinite(dist)
            ii = np.where(ok, i, 0)
            ww, dd = w[ii], dep[ii]
            dist = np.where(ok, dist, 1e3).astype(np.float32)
            shape = carve[rows].shape
            wv, rv = warp[rows].reshape(-1), rough[rows].reshape(-1)
            u = dist / ww * (1.0 + 0.22 * wv) + 0.08 * rv
            if kind == "rift":
                floor = -dd * (1.0 - smoothstep(0.62, 1.0, u)) * (1.0 + 0.10 * rv)
                shoulder = dd * 0.20 * smoothstep(0.8, 1.15, u) * (1.0 - smoothstep(1.2, 2.6, u))
                mantle = np.maximum(1.0 - smoothstep(0.5, 1.05, u),
                                    0.8 * np.exp(-((dist / (4.0 * ww)) ** 2)) * (0.6 + 0.4 * wv))
                mantle *= np.clip(ww / 0.03, 0.25, 1.0)
            else:
                floor = -dd * (1.0 - smoothstep(0.0, 1.0, u)) ** 1.5
                shoulder = dd * 0.12 * smoothstep(0.85, 1.1, u) * (1.0 - smoothstep(1.1, 1.8, u))
                mantle = np.zeros_like(u)
            carve[rows] = np.minimum(carve[rows], floor.reshape(shape))
            lift[rows] = np.maximum(lift[rows], shoulder.reshape(shape))
            dark[rows] = np.maximum(dark[rows], mantle.reshape(shape))
        self.H += carve + lift
        self.D = np.maximum(self.D, dark)
        self.stats[kind] = len(pts)

    # ------------------------------------------------------------------------------------------- the bake
    def albedo(self):
        s = self.s
        P = self.P
        n = self.noise
        H = self.H
        # slopes (fresh exposures on steep walls) and local concavity (fines ponded in floors)
        gy, gx = self.gradients(H)
        slope = np.sqrt(gx * gx + gy * gy)
        low = H - self.g.blur(H, 4 * self.g.texel)
        hue = n.fbm(P, 2.5, 4, first=5)
        mott = n.fbm(P, 14.0, 5, first=9)
        fine = n.fbm(P, 120.0, 4, first=13)
        hi = np.array(s["highland"], np.float32)
        hi2 = np.array(s["highland2"], np.float32)
        pl = np.array(s["plains"], np.float32)
        pl2 = np.array(s["plains2"], np.float32)
        dk = np.array(s["dark"], np.float32)
        fr = np.array(s["fresh"], np.float32)
        tH = smoothstep(-0.8, 0.8, hue)[..., None]
        high = hi * (1 - tH) + hi2 * tH
        tP = smoothstep(-0.7, 0.7, mott)[..., None]
        plains = pl * (1 - tP) + pl2 * tP
        M = np.clip(self.M, 0, 1)[..., None]
        A = high * (1 - M) + plains * M
        D = np.clip(self.D, 0, 1)[..., None] * s["dark_k"]
        A = A * (1 - D) + dk * D
        F = np.clip(self.F * s["fresh_k"] + self.Rv * s["ray_k"], 0, 1.2)[..., None]
        A = A * (1 - np.minimum(F, 1)) + fr * np.minimum(F, 1) + (F > 1) * (F - 1) * fr
        A *= (1.0 + s["slope_k"] * smoothstep(0.08, 0.6, slope))[..., None]
        A *= (1.0 + s["floor_k"] * np.clip(low / (8 * self.g.texel * 0.05), -1, 1))[..., None]
        A *= np.exp(s["mottle_k"] * mott + s["fine_k"] * fine)[..., None]
        if s["dust"] > 0:  # a dust mantle: low contrast at the finest scales
            soft = np.stack([self.g.blur(A[..., c], s["dust"] * self.g.texel) for c in range(3)], -1)
            A = A * (1 - s["dust_mix"]) + soft * s["dust_mix"]
        return A

    def gradients(self, H):
        """dH/d(north), dH/d(east) per radian of surface."""
        g = self.g
        gy = np.empty_like(H)
        gy[1:-1] = (H[:-2] - H[2:]) / (2 * g.texel)
        gy[0], gy[-1] = gy[1], gy[-2]
        gx = (np.roll(H, -1, 1) - np.roll(H, 1, 1)) / (2 * (TAU / g.W) * np.maximum(g.clat, 0.02)[:, None])
        return gy, gx

    def normals(self, exaggerate):
        gy, gx = self.gradients(self.H * exaggerate)
        n = np.stack([-gx, -gy, np.ones_like(gx)], -1)
        return n / np.linalg.norm(n, axis=-1, keepdims=True)


# ===================================================================================================== the two moons
MASSER = dict(
    rmin_texels=1.6, rt=0.010, basin_r=0.09, ring_r=0.07, dig_r=0.02,
    highland=(0.50, 0.265, 0.27), highland2=(0.49, 0.29, 0.25),
    plains=(0.24, 0.105, 0.066), plains2=(0.18, 0.085, 0.060),
    dark=(0.085, 0.032, 0.028), fresh=(0.60, 0.42, 0.34),
    dark_k=0.92, fresh_k=0.35, ray_k=0.35, slope_k=0.07, floor_k=0.06, mottle_k=0.10, fine_k=0.06,
    dust=1.2, dust_mix=0.35, exaggerate=1.0,
)
SECUNDA = dict(
    rmin_texels=1.5, rt=0.012, basin_r=0.10, ring_r=0.08, dig_r=0.03,
    highland=(0.52, 0.50, 0.44), highland2=(0.47, 0.46, 0.43),
    plains=(0.24, 0.236, 0.228), plains2=(0.29, 0.282, 0.27),
    dark=(0.30, 0.29, 0.28), fresh=(1.0, 0.98, 0.92),
    dark_k=0.0, fresh_k=0.6, ray_k=0.8, slope_k=0.22, floor_k=0.06, mottle_k=0.07, fine_k=0.07,
    dust=0.0, dust_mix=0.0, exaggerate=1.0,
)


def build_masser(width, seed=0x4D41):
    m = Moon("masser", MASSER, width, seed)
    rng, n, P = m.rng, m.noise, m.P
    t = time.time()
    # the crust: long-wave relief
    m.H += 0.0030 * n.fbm(P, 1.6, 6, first=0) + 0.0008 * n.fbm(P, 12.0, 5, first=6)
    # ancient basins, mostly on the face towards Nirn (it is their floors the plains flood)
    for lat, lon, r in [(0.35, -0.55, 0.36), (-0.30, 0.40, 0.30), (0.62, 0.70, 0.22), (-0.70, -0.45, 0.25),
                        (0.05, 1.25, 0.20), (-0.05, -1.30, 0.24), (0.95, -1.9, 0.30), (-0.4, 2.4, 0.33),
                        (0.2, 2.9, 0.20)]:
        m.crater(lat + rng.normal(0, 0.03), lon + rng.normal(0, 0.03), r * rng.uniform(0.95, 1.05), fresh=0.0)
    m.degrade(6.0, 0.0)
    m.epoch(0.55, 0.14, "heavy bombardment I", fresh=0.0)
    m.degrade(3.0, 0.0)
    m.epoch(0.45, 0.09, "heavy bombardment II", fresh=0.6)
    m.degrade(2.2, 0.2)
    m.withered(0.0011, 3.2, (-0.9, -0.3, 0.35, 1.0), 40)
    # the plains: lava floods the low ground in broad, mottled, irregular sheets (not round maria)
    base = m.g.blur(m.H, 0.05)
    lowness = -(base - base.mean()) / base.std()
    patch = n.fbm(P, 1.3, 4, first=20)
    blot = n.fbm(P, 9.0, 4, first=24)
    near = smoothstep(-0.2, 0.6, P[..., 2])  # (more of it on the face towards Nirn)
    field = 0.7 * lowness + 0.9 * patch + 0.18 * blot + 0.55 * near - 0.75
    M = smoothstep(-0.1, 0.55, field)
    level = m.g.blur(m.H, 0.03) - 0.0002
    m.H = m.H * (1 - 0.88 * M) + level * (0.88 * M)
    m.H += M * 0.00035 * np.maximum(n.ridged(P, 26.0, 3, first=30) - 1.2, 0)  # wrinkle ridges
    m.M = M.astype(np.float32)
    m.F *= 1 - M
    # the rift: a long branching graben across the face, ragged, its shoulders raised, a dark mantle round it
    paths = []
    main = m.walk(0.42, -0.95, 2.05, 1.95, 0.9, rng)
    s = np.linspace(0, 1, len(main))
    w = 0.055 * np.sin(np.pi * np.clip(s * 1.05, 0, 1)) ** 0.55 + 0.004
    paths.append((main, w, 0.0055 * (w / w.max()) ** 0.6))
    for _ in range(6):
        k = int(rng.uniform(0.15, 0.85) * len(main))
        p0 = main[k]
        lat0, lon0 = math.asin(p0[1]), math.atan2(p0[0], p0[2])
        q = main[min(k + 5, len(main) - 1)] - main[max(k - 5, 0)]
        north = np.array([0.0, 1.0, 0.0]) - p0 * p0[1]
        north /= np.linalg.norm(north)
        head = math.atan2(np.dot(q, np.cross(north, p0)), np.dot(q, north))
        head += rng.choice([-1, 1]) * rng.uniform(0.35, 0.95)
        br = m.walk(lat0, lon0, head, rng.uniform(0.25, 0.7), 1.3, rng)
        sb = np.linspace(0, 1, len(br))
        wb = w[k] * rng.uniform(0.35, 0.6) * (1 - sb) ** 0.7 + 0.002
        paths.append((br, wb, 0.0045 * (wb / w.max()) ** 0.5))
    # en-echelon fractures beside the main rift
    for off in (-2.8, 3.4):
        side = np.cross(main, np.gradient(main, axis=0))
        side /= np.linalg.norm(side, axis=1, keepdims=True)
        par = main + side * (w[:, None] * off)
        par /= np.linalg.norm(par, axis=1, keepdims=True)
        keep = np.convolve(rng.uniform(0, 1, len(par)) > 0.5, np.ones(400) / 400, "same") > 0.5
        keep &= (s > 0.1) & (s < 0.9)
        if keep.sum() > 10:
            paths.append((par[keep], np.full(keep.sum(), 0.004), np.full(keep.sum(), 0.0010)))
    m.trench(paths, "rift")
    m.M = np.maximum(m.M, 0.6 * m.D * smoothstep(-0.3, 0.5, blot))  # lava welled out along the rift
    m.degrade(1.4, 0.25)
    m.epoch(0.07, 0.05, "after the plains", fresh=0.5)
    m.degrade(1.0, 0.3)
    m.epoch(0.05, 0.025, "late", fresh=0.6)
    m.degrade(0.7, 0.35)
    # the young epoch: a few fresh craters, dim rays (Masser's dust), their secondaries
    sec = []
    for _ in range(5):
        r = rng.uniform(0.010, 0.022)
        la, lo = math.asin(rng.uniform(-0.9, 0.9)), rng.uniform(-1.5, 1.5)
        m.crater(la, lo, r, fresh=1.0)
        sec += m.rays(la, lo, r, 0.6, int(rng.integers(10, 22)))
    m.epoch(0.03, 0.01, "young", fresh=1.0)
    for la, lo, r in sec:
        m.crater(la, lo, r, fresh=0.7)
    m.degrade(0.45, 1.0)
    print(f"  masser built in {time.time() - t:.0f}s, {m.stats}")
    return m


def build_secunda(width, seed=0x5345):
    m = Moon("secunda", SECUNDA, width, seed)
    rng, n, P = m.rng, m.noise, m.P
    t = time.time()
    m.H += 0.0035 * n.fbm(P, 1.4, 6, first=0) + 0.001 * n.fbm(P, 10.0, 5, first=6)
    # the giant basin, off the face's centre, and a few older ones
    G = (0.42, 0.62, 0.44)
    for lat, lon, r in [(-0.55, -0.6, 0.20), (0.1, -1.6, 0.26), (-0.3, 2.2, 0.3), (0.9, 2.8, 0.22)]:
        m.crater(lat, lon, r, fresh=0.0)
    m.degrade(5.0, 0.0)
    m.epoch(0.8, 0.14, "heavy bombardment I", fresh=0.0)
    m.degrade(2.5, 0.0)
    m.epoch(0.55, 0.09, "heavy bombardment II", fresh=0.6)
    m.withered(0.0014, 3.6, (-1.0, -0.4, 0.2, 0.8, 1.4), 40)
    m.degrade(1.6, 0.25)
    m.crater(*G, fresh=0.3)
    # its sculpture: long radial valleys and crater chains thrown out across the face
    paths = []
    for i in range(11):
        az = TAU * i / 11 + rng.normal(0, 0.18)
        start = m.g.point(G[0], G[1], az, G[2] * rng.uniform(1.15, 1.4))
        length = rng.uniform(0.35, 0.95)
        v = m.walk(start[0], start[1], az_at(m.g, G, start, az), length, 0.25, rng)
        sv = np.linspace(0, 1, len(v))
        wv = rng.uniform(0.014, 0.028) * (1 - 0.7 * sv) + 0.003
        if i % 3 == 2:  # a chain of craters instead of a valley
            for j in range(0, len(v), max(1, int(len(v) / rng.uniform(10, 20)))):
                pj = v[j]
                m.crater(math.asin(pj[1]), math.atan2(pj[0], pj[2]), float(wv[j] * rng.uniform(0.9, 1.4)), fresh=0.2)
            continue
        paths.append((v, wv, 0.008 * (wv / 0.02)))
    m.trench(paths, "valley")
    m.degrade(1.0, 0.3)
    # a little grey plain in the old basins' floors
    base = m.g.blur(m.H, 0.04)
    lowness = -(base - base.mean()) / base.std()
    gv = vec(G[0], G[1]).astype(np.float32)
    inG = 1.0 - smoothstep(0.35, 0.7, np.arccos(np.clip(P @ gv, -1, 1)) / G[2])  # the giant basin's inner floor
    M = np.maximum(smoothstep(1.3, 2.0, lowness + 0.5 * n.fbm(P, 5.0, 4, first=20)),
                   inG * smoothstep(-0.4, 0.5, n.fbm(P, 4.0, 4, first=21)))
    level = m.g.blur(m.H, 0.02)
    m.H = m.H * (1 - 0.7 * M) + level * 0.7 * M
    m.M = M.astype(np.float32)
    m.epoch(0.2, 0.05, "after the plains", fresh=0.7)
    m.degrade(1.0, 0.35)
    m.epoch(0.1, 0.025, "late", fresh=0.9)
    m.degrade(0.6, 0.45)
    sec = []
    for k in range(6):
        r = rng.uniform(0.012, 0.03) if k else 0.034
        la, lo = math.asin(rng.uniform(-0.85, 0.85)), rng.uniform(-1.4, 1.4)
        m.crater(la, lo, r, fresh=1.0)
        sec += m.rays(la, lo, r, 1.0 if k < 3 else 0.6, int(rng.integers(14, 30)))
    m.epoch(0.05, 0.012, "young", fresh=1.0)
    for la, lo, r in sec:
        m.crater(la, lo, r, fresh=0.8)
    m.degrade(0.35, 1.0)
    print(f"  secunda built in {time.time() - t:.0f}s, {m.stats}")
    return m


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
    tn = rel[:, :3] * 2 - 1
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
        h0 = (rel[:, 3] * 2 - 1) * relief_scale
        lit = np.ones_like(h0)
        active = (mu0g > -0.03) & (mu0g < 0.45)
        t = max(texel * 1.5, f * 0.75)
        for k in range(12):
            uu = u + de * t / (TAU * np.maximum(clat, 0.05))
            vv = v - dn * t / math.pi
            lk = np.clip(np.log2(max(t * 0.25, f) / texel) * np.ones_like(u), 0, len(relief_mips) - 1)
            hk = (sample(relief_mips, uu[active], vv[active], lk[active])[:, 3] * 2 - 1) * relief_scale
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
    A = m.albedo()
    w = face_weight(m.g)[..., None]
    mean = (A * w).sum((0, 1)) / w.sum()
    A *= np.array(TARGET[name], np.float32) / mean
    print(f"  {name} albedo mean {mean.round(4).tolist()} -> {TARGET[name]}, max {A.max():.3f}")
    alb8 = to_srgb8(A)
    n = m.normals(s["exaggerate"])
    hp = m.H - m.g.blur(m.H, 0.06)
    scale = RELIEF[name]
    clip = float(np.mean(np.abs(hp) > scale))
    print(f"  {name} local relief: p99.9 {np.percentile(np.abs(hp), 99.9):.4f}, scale {scale}, clipped {clip:.2%}")
    rel = np.empty(n.shape[:2] + (4,), np.uint8)
    rel[..., :3] = np.clip(n * 127.5 + 127.5, 0, 255).astype(np.uint8)
    rel[..., 3] = np.clip((hp / scale * 0.5 + 0.5) * 255 + 0.5, 0, 255).astype(np.uint8)
    out.mkdir(parents=True, exist_ok=True)
    Image.fromarray(alb8, "RGB").save(out / f"{name}_albedo.png", optimize=True)
    Image.fromarray(rel, "RGBA").save(out / f"{name}_relief.png", optimize=True)
    for f in ("albedo", "relief"):
        (out / f"{name}_{f}.png.mcmeta").write_text(json.dumps({"texture": {"blur": True}}) + "\n")
    # the decoded maps as the shader sees them (8 bit, gamma 2.2), their mips
    am = mips((alb8.astype(np.float32) / 255) ** 2.2, 7)
    rm = mips(rel.astype(np.float32) / 255, 7)
    phases(name, am, rm, out)
    print(f"  {name} written in {time.time() - t:.0f}s")
    if preview:
        previews(name, am, rm, WORK)


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
