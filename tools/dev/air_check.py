"""The game's GLSL sky against atlas's own (the air: native/src/air.hpp is the reference).

Evaluates the formulas of mod/.../shaders/include/atlas_air.glsl (airSkyRadiance) and atlas_sky.glsl (the sky-view
LUT's mapping, looked up bilinearly as AtlasSky's 192 x 108 target is) in numpy, with the inputs AtlasSky gives them
(atlas_air_coeffs, atlas_air_multiscatter), and compares with atlas_air_sky from the built core, for a set of suns
and airs. Two numbers each: the march itself (the GLSL formulas at the LUT's texel directions) and what the game
shows (the LUT looked up between texels). atlas_pack_test runs the same GLSL file on a GL driver.

    py -3 tools/dev/air_check.py [native/build/Release/atlas_core.dll]
"""
import ctypes
import math
import sys

import numpy as np

DLL = sys.argv[1] if len(sys.argv) > 1 else "native/build/Release/atlas_core.dll"
lib = ctypes.CDLL(DLL)
F = ctypes.c_float


class Air(ctypes.Structure):
    _fields_ = [("v", F * 11)]


class Climate(ctypes.Structure):
    _fields_ = [("v", F * 13)]


lib.atlas_air_from_climate.argtypes = [ctypes.POINTER(Climate), ctypes.POINTER(Air)]
lib.atlas_air_coeffs.argtypes = [ctypes.POINTER(Air), ctypes.POINTER(F)]
lib.atlas_air_multiscatter.argtypes = [ctypes.POINTER(Air), ctypes.POINTER(F)]
lib.atlas_air_sky.argtypes = [ctypes.POINTER(Air), ctypes.POINTER(F), ctypes.c_void_p, ctypes.c_void_p, F,
                              ctypes.POINTER(F), ctypes.c_uint32, ctypes.POINTER(F)]

# atlas_air.glsl's constants
R_GROUND, R_TOP, H_RAYLEIGH, OZONE_PEAK, OZONE_HALF = 6360.0, 6460.0, 8.0, 25.0, 15.0
SUN_HALF_ANGLE = 0.00465
SUN_IRRADIANCE = np.array([11.181, 11.807, 12.854])
NIGHT_GLOW = np.array([0.0, 0.0, 0.0])  # (air.hpp: the night's glow is the sky's emitters')
MS_SIZE, SKY_STEPS, VIEW_W, VIEW_H = 32, 24, 192, 108
EYE_Y = 120.0


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


class Glsl:
    """atlas_air.glsl, line for line, vectorised over directions (arrays of shape (n,))."""

    def __init__(self, coeffs, ms):
        c = np.array(coeffs).reshape(4, 4)
        self.ray, self.inv_hm = c[0, :3], c[0, 3]
        self.mie_s, self.g = c[1, :3], c[1, 3]
        self.mie_e, self.scale = c[2, :3], c[2, 3]
        self.ozone, self.ground = c[3, :3], c[3, 3]
        self.ms = np.array(ms).reshape(MS_SIZE, MS_SIZE, 4)[:, :, :3]

    @staticmethod
    def erfcx(y):
        return 1.1283792 / (y + np.sqrt(y * y + 0.393 * y + 1.2732395))

    def chapman(self, X, h, mu):
        x = X + h
        up = np.sqrt(1.5707963 * x) * self.erfcx(np.sqrt(0.5 * x) * np.abs(mu)) * np.exp(-h)
        x0 = np.sqrt(np.maximum(1.0 - mu * mu, 0.0)) * x
        with np.errstate(over="ignore", invalid="ignore"):
            down = 2.0 * np.sqrt(1.5707963 * x0) * np.exp(X - x0) - up
        return np.where(mu >= 0.0, up, down)

    @staticmethod
    def horizon_mu(r):
        return -np.sqrt(np.maximum(1.0 - (R_GROUND / r) ** 2, 0.0))

    def light_t(self, r, mu):
        vis = smoothstep(-SUN_HALF_ANGLE, SUN_HALF_ANGLE, mu - self.horizon_mu(r))
        h = np.maximum(r - R_GROUND, 0.0)
        mu = np.maximum(mu, self.horizon_mu(r))
        dR = H_RAYLEIGH * self.chapman(R_GROUND / H_RAYLEIGH, h / H_RAYLEIGH, mu)
        dM = self.chapman(R_GROUND * self.inv_hm, h * self.inv_hm, mu) / self.inv_hm
        s = np.minimum(r / (R_GROUND + OZONE_PEAK), 1.0)
        slant = 1.0 / np.sqrt(np.maximum(1.0 - s * s * (1.0 - mu * mu), 1e-4))
        u = np.clip((h - (OZONE_PEAK - OZONE_HALF)) / (2.0 * OZONE_HALF), 0.0, 1.0)
        above = np.where(u < 0.5, 1.0 - 2.0 * u * u, 2.0 * (1.0 - u) ** 2)
        dO = 2.0 * OZONE_HALF * 0.5 * above * slant
        od = self.ray * dR[:, None] + self.mie_e * dM[:, None] + self.ozone * dO[:, None]
        with np.errstate(invalid="ignore"):
            t = np.exp(-od) * vis[:, None]
        return np.where(vis[:, None] > 0.0, t, 0.0)

    @staticmethod
    def phase_r(nu):
        return 3.0 / (16.0 * math.pi) * (1.0 + nu * nu)

    def phase_m(self, nu):
        g2 = self.g * self.g
        return 3.0 / (8.0 * math.pi) * (1.0 - g2) * (1.0 + nu * nu) / (
            (2.0 + g2) * np.maximum(1.0 + g2 - 2.0 * self.g * nu, 1e-4) ** 1.5)

    def ms_at(self, h, mus):
        # the texture fetch at (u * (N - 1) + 0.5) / N: bilinear between the grid's nodes
        u = np.clip(mus * 0.5 + 0.5, 0, 1) * (MS_SIZE - 1)
        v = np.sqrt(np.clip(h / (R_TOP - R_GROUND), 0, 1)) * (MS_SIZE - 1)
        x0 = np.minimum(u.astype(int), MS_SIZE - 2)
        y0 = np.minimum(v.astype(int), MS_SIZE - 2)
        fx, fy = (u - x0)[:, None], (v - y0)[:, None]
        m = self.ms
        a = m[y0, x0] * (1 - fx) + m[y0, x0 + 1] * fx
        b = m[y0 + 1, x0] * (1 - fx) + m[y0 + 1, x0 + 1] * fx
        return a * (1 - fy) + b * fy

    def sky(self, d, sun, moon, moon_e):
        """airSkyRadiance for unit directions d (n, 3)."""
        r = R_GROUND + max(EYE_Y - 63.0, 0.0) * self.scale
        mu = d[:, 1]
        disc = r * r * (mu * mu - 1.0) + R_GROUND ** 2
        tg = np.where((mu < 0) & (disc >= 0), np.maximum(-r * mu - np.sqrt(np.maximum(disc, 0)), 0), -1.0)
        tt = np.maximum(-r * mu + np.sqrt(np.maximum(r * r * (mu * mu - 1.0) + R_TOP ** 2, 0)), 0)
        t_end = np.where(tg >= 0, tg, tt)
        lights = [(d @ sun, sun[1], SUN_IRRADIANCE)]
        if moon_e.sum() > 0:
            lights.append((d @ moon, moon[1], moon_e))
        T = np.ones((len(d), 3))
        L = np.zeros((len(d), 3))
        prev = np.zeros(len(d))
        for s in range(SKY_STEPS):
            a, m = (s + 1) / SKY_STEPS, (s + 0.5) / SKY_STEPS
            t1, t = t_end * a * a, t_end * m * m
            dt = t1 - prev
            prev = t1
            rt = np.sqrt(np.maximum(r * r + 2 * r * mu * t + t * t, 1.0))
            ht = np.maximum(rt - R_GROUND, 0.0)
            sR = self.ray * np.exp(-ht / H_RAYLEIGH)[:, None]
            dM = np.exp(-ht * self.inv_hm)[:, None]
            dO = np.maximum(0.0, 1.0 - np.abs(ht - OZONE_PEAK) / OZONE_HALF)[:, None]
            sM = self.mie_s * dM
            ext = sR + self.mie_e * dM + self.ozone * dO
            stepT = np.exp(-ext * dt[:, None])
            w = (1.0 - stepT) / np.maximum(ext, 1e-7)
            S = np.zeros_like(L)
            for nu, mul, E in lights:
                mut = (r * mul + t * nu) / rt
                S += ((sR * self.phase_r(nu)[:, None] + sM * self.phase_m(nu)[:, None]) * self.light_t(rt, mut)
                      + (sR + sM) * self.ms_at(ht, mut)) * E
            L += T * S * w
            T *= stepT
        hit = tg >= 0
        rg = np.sqrt(np.maximum(r * r + 2 * r * mu * t_end + t_end * t_end, 1.0))
        lit = np.zeros_like(L)
        for nu, mul, E in lights:
            mug = (r * mul + t_end * nu) / rg
            lit += self.light_t(rg, mug) * E * np.maximum(mug, 0)[:, None]
        L += np.where(hit[:, None], T * lit * (self.ground / math.pi), 0.0)
        return L + NIGHT_GLOW


def view_dir(u, v, sun):  # atlas_sky.glsl atlasSkyViewDir
    s = v * 2 - 1
    l = np.sign(s) * s * s * 1.5707963
    az = u * 6.2831853 + math.atan2(sun[2], sun[0] + 1e-9)
    return np.stack([np.cos(l) * np.cos(az), np.sin(l), np.cos(l) * np.sin(az)], -1)


def view_uv(d, sun):  # atlas_sky.glsl atlasSkyViewUv
    l = np.arcsin(np.clip(d[:, 1], -1, 1))
    v = 0.5 + 0.5 * np.sign(l) * np.sqrt(np.abs(l) / 1.5707963)
    az = np.arctan2(d[:, 2], d[:, 0]) - math.atan2(sun[2], sun[0] + 1e-9)
    return np.mod(az / 6.2831853 + 1.0, 1.0), v


def native_sky(air, sun, dirs):
    n = len(dirs)
    out = (F * (3 * n))()
    s = (F * 3)(*sun)
    dd = (F * (3 * n))(*dirs.astype(np.float32).ravel())
    assert lib.atlas_air_sky(ctypes.byref(air), s, None, None, EYE_Y, dd, n, out) == 0
    return np.frombuffer(out, dtype=np.float32).reshape(n, 3).astype(np.float64)


def check(name, climate, sun):
    k = Climate((F * 13)(*climate))
    air = Air()
    lib.atlas_air_from_climate(ctypes.byref(k), ctypes.byref(air))
    co = (F * 16)()
    ms = (F * 4096)()
    lib.atlas_air_coeffs(ctypes.byref(air), co)
    lib.atlas_air_multiscatter(ctypes.byref(air), ms)
    g = Glsl(list(co), list(ms))
    sun = np.array(sun) / np.linalg.norm(sun)
    moon = -sun
    fade = math.pi * float(smoothstep(0.1, 0.0, sun[1]))
    moon_e = np.array([0.035, 0.05, 0.09]) * fade
    # 1. the march: the LUT's texel directions
    uu, vv = np.meshgrid((np.arange(VIEW_W) + 0.5) / VIEW_W, (np.arange(VIEW_H) + 0.5) / VIEW_H)
    d = view_dir(uu.ravel(), vv.ravel(), sun)
    lut = g.sky(d, sun, moon, moon_e)
    ref = native_sky(air, sun, d)
    rel = np.abs(lut - ref) / np.maximum(np.abs(ref), 1e-3)
    # 2. what the game shows: random directions looked up in that LUT (bilinear, repeat in u)
    rng = np.random.default_rng(1)
    q = rng.normal(size=(4000, 3))
    q /= np.linalg.norm(q, axis=1, keepdims=True)
    u, v = view_uv(q, sun)
    img = lut.reshape(VIEW_H, VIEW_W, 3)
    x = u * VIEW_W - 0.5
    y = np.clip(v * VIEW_H - 0.5, 0, VIEW_H - 1)
    x0 = np.floor(x).astype(int)
    y0 = np.minimum(np.floor(y).astype(int), VIEW_H - 2)
    fx, fy = (x - x0)[:, None], (y - y0)[:, None]
    x0m, x1m = x0 % VIEW_W, (x0 + 1) % VIEW_W
    shown = (img[y0, x0m] * (1 - fx) + img[y0, x1m] * fx) * (1 - fy) + (img[y0 + 1, x0m] * (1 - fx) + img[y0 + 1, x1m] * fx) * fy
    qref = native_sky(air, sun, q)
    qrel = np.abs(shown - qref) / np.maximum(np.abs(qref), 1e-3)
    zen = native_sky(air, sun, np.array([[0.0, 1.0, 0.0]]))[0]
    print(f"{name:6s} sun el {math.degrees(math.asin(sun[1])):+5.1f}: march vs atlas_air_sky max {rel.max():.2e} "
          f"median {np.median(rel):.2e}; through the LUT median {np.median(qrel):.2e} p99 {np.percentile(qrel, 99):.2e}"
          f" (zenith {zen[0]:.4f} {zen[1]:.4f} {zen[2]:.4f})")
    return rel.max()


CLEAN = [15, 0.5, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0]
HUMID = [20, 0.9, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0.1, 0.6]
DUSTY = [32, 0.2, 1, 0, 0, 1, 0, 0.25, 0, 0, 0, 0, 0]
worst = 0.0
for nm, cl in (("clean", CLEAN), ("humid", HUMID), ("dusty", DUSTY)):
    for el in (-6, -2, 2, 10, 35):
        e = math.radians(el)
        worst = max(worst, check(nm, cl, [math.cos(e), math.sin(e), 0.3]))
# (the core marches in float32, this in float64: 0.5 % covers a low sun's optical depths, as atlas_pack_test does)
print("PASS" if worst < 5e-3 else "FAIL", f"(worst {worst:.2e})")
sys.exit(0 if worst < 5e-3 else 1)
