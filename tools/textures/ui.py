#!/usr/bin/env python3
"""Generates NirnCraft's HUD and menu art (visual workstream). Everything is original and procedural.

  assets/nirncraft/textures/gui/hud/sneak_eye.png      5 frames (64x32 each, stacked vertically): closed .. wide open
  assets/nirncraft/textures/gui/hud/crosshair.png      2 frames (32x32 each, side by side): idle, on a target
  assets/nirncraft/textures/gui/hud/compass.png        440x28 compass band (drawn at 220x14)
  assets/nirncraft/textures/gui/hud/bar_caps.png       ornate bar end caps, 2 rows (target / boss), 48x16 each half
  assets/nirncraft/textures/gui/hud/ornament.png       128x16 banner divider with a central knot
  assets/nirncraft/textures/gui/loading/vignette_N.png 640x360 painted loading-screen scenes (monochrome, Skyrim-like)
  assets/nirncraft/textures/gui/loading/smoke.png      256x256 tiling smoke for the loading screen background

All sprites are white/grey so the game can tint them. Requires numpy and Pillow.
"""
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parents[2] / "mod/src/main/resources/assets/nirncraft/textures/gui"
HUD = ROOT / "hud"
LOADING = ROOT / "loading"


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def periodic_noise(h, w, rng, falloff=1.8, low=1.0):
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


def to_img(alpha, rgb=(255, 255, 255)):
    h, w = alpha.shape
    img = np.zeros((h, w, 4))
    img[..., 0], img[..., 1], img[..., 2] = rgb
    img[..., 3] = np.clip(alpha, 0, 1) * 255
    img[..., :3] *= (img[..., 3:4] > 0)
    return Image.fromarray(img.astype(np.uint8), "RGBA")


def supersample(draw_fn, w, h, s=4):
    """Draw with PIL at s-times resolution (white on transparent) and downsample for antialiasing."""
    big = Image.new("L", (w * s, h * s), 0)
    draw_fn(ImageDraw.Draw(big), s)
    small = big.resize((w, h), Image.LANCZOS)
    return np.asarray(small, float) / 255.0


# ---------------------------------------------------------------- HUD sprites

def sneak_eye():
    w, h = 64, 32
    frames = []
    for i, open_ in enumerate([0.0, 0.25, 0.5, 0.78, 1.0]):
        def draw(d, s, open_=open_):
            cx, cy = w * s / 2, h * s / 2
            half = 22 * s
            lid = (2 + 9 * open_) * s
            # upper and lower lids as arcs (quadratic curves sampled)
            pts_top, pts_bot = [], []
            for k in range(41):
                t = k / 40
                x = cx - half + 2 * half * t
                bulge = 4 * t * (1 - t)
                pts_top.append((x, cy - lid * bulge - 1.2 * s * bulge))
                pts_bot.append((x, cy + lid * bulge * 0.8 + 1.2 * s * bulge))
            lw = int(2.2 * s)
            d.line(pts_top, fill=255, width=lw, joint="curve")
            d.line(pts_bot, fill=255, width=lw, joint="curve")
            # pointed corners extend a little (Skyrim's eye has long tapered corners)
            d.line([(cx - half - 4 * s, cy), (cx - half, cy)], fill=255, width=int(1.4 * s))
            d.line([(cx + half, cy), (cx + half + 4 * s, cy)], fill=255, width=int(1.4 * s))
            if open_ > 0.2:
                r_iris = (3.5 + 3.5 * open_) * s
                d.ellipse([cx - r_iris, cy - r_iris * min(1, open_ * 1.1), cx + r_iris, cy + r_iris * min(1, open_ * 1.1)],
                          outline=255, width=int(1.6 * s))
                rp = 2.2 * s * open_
                d.ellipse([cx - rp, cy - rp, cx + rp, cy + rp], fill=255)
            # lashes over the closed eye
            if open_ < 0.3:
                for k in (-2, -1, 0, 1, 2):
                    x = cx + k * 7 * s
                    d.line([(x, cy - 1 * s), (x + k * 1.2 * s, cy - 5 * s)], fill=255, width=int(1.2 * s))
        frames.append(supersample(draw, w, h))
    sheet = np.concatenate(frames, axis=0)
    to_img(sheet).save(HUD / "sneak_eye.png")


def crosshair():
    w = h = 32
    out = []
    for active in (False, True):
        def draw(d, s, active=active):
            c = 16 * s
            gap = (4 if active else 6) * s
            arm = 5 * s
            lw = int(1.6 * s)
            # inward-pointing angle brackets left and right, like < > turned toward the centre
            for sgn in (-1, 1):
                x0 = c + sgn * gap
                d.line([(x0 + sgn * arm, c - arm * 0.8), (x0, c), (x0 + sgn * arm, c + arm * 0.8)], fill=255, width=lw, joint="curve")
            r = 1.1 * s
            d.ellipse([c - r, c - r, c + r, c + r], fill=255 if active else 200)
        out.append(supersample(draw, w, h))
    to_img(np.concatenate(out, axis=1)).save(HUD / "crosshair.png")


def compass():
    w, h = 440, 28
    x = (np.arange(w) + 0.5) / w
    y = (np.arange(h) + 0.5) / h
    xx, yy = np.meshgrid(x, y)
    rng = np.random.default_rng(31)
    smoke = periodic_noise(h, w, rng, 1.6)
    ends = smoothstep(0.0, 0.14, xx) * smoothstep(0.0, 0.14, 1 - xx)
    band = smoothstep(0.08, 0.3, yy) * smoothstep(0.08, 0.3, 1 - yy)
    body = band * ends * (0.75 + 0.2 * smoke)
    img = np.zeros((h, w, 4))
    img[..., :3] = 0
    img[..., 3] = body * 0.9
    # gold-ish (drawn white, tinted in game) edge lines that fade at the ends
    edge = np.zeros((h, w))
    edge[2, :] = 1
    edge[h - 3, :] = 1
    edge *= ends ** 1.5
    # end ornaments: small diamonds with a dash
    orn = np.zeros((h, w))
    for cx in (12, w - 13):
        for k in range(-5, 6):
            for j in range(-5 + abs(k), 6 - abs(k)):
                yy0, xx0 = h // 2 + j, cx + k
                if 0 <= yy0 < h and 0 <= xx0 < w:
                    orn[yy0, xx0] = 1 if abs(k) + abs(j) >= 4 or (k == 0 and j == 0) else orn[yy0, xx0]
    light = np.clip(edge * 0.8 + orn, 0, 1)
    img[..., :3] = light[..., None] * np.array([232, 214, 160])
    img[..., 3] = np.clip(img[..., 3] + light, 0, 1)
    img[..., 3] *= 255
    Image.fromarray(img.astype(np.uint8), "RGBA").save(HUD / "compass.png")


def bar_caps():
    """Two rows (target bar, boss bar). Each row: left cap (48x16) then right cap (mirrored)."""
    rows = []
    for boss in (False, True):
        def draw(d, s, boss=boss):
            h = 16 * s
            cy = h / 2
            lw = int(1.4 * s)
            # long tapered spike pointing outward with a hooked curl, wing-like for the boss bar
            tip = 2 * s
            base = 46 * s
            d.polygon([(tip, cy), (base, cy - 3 * s), (base, cy + 3 * s)], outline=255, fill=90)
            d.line([(base - 6 * s, cy - 3 * s), (base - 14 * s, cy - (7 if boss else 5) * s), (base - 22 * s, cy - (5 if boss else 3) * s)],
                   fill=255, width=lw)
            d.line([(base - 6 * s, cy + 3 * s), (base - 14 * s, cy + (7 if boss else 5) * s), (base - 22 * s, cy + (5 if boss else 3) * s)],
                   fill=255, width=lw)
            if boss:
                for k in range(3):
                    x = base - (28 + k * 6) * s
                    d.line([(x, cy - (4 - k) * s), (x - 4 * s, cy - (7 - k) * s)], fill=255, width=lw)
                    d.line([(x, cy + (4 - k) * s), (x - 4 * s, cy + (7 - k) * s)], fill=255, width=lw)
        left = supersample(draw, 48, 16)
        rows.append(np.concatenate([left, left[:, ::-1]], axis=1))
    to_img(np.concatenate(rows, axis=0), (232, 214, 160)).save(HUD / "bar_caps.png")


def ornament():
    w, h = 128, 16

    def draw(d, s):
        cy = h * s / 2
        cx = w * s / 2
        lw = int(1.2 * s)
        d.line([(4 * s, cy), (cx - 12 * s, cy)], fill=200, width=lw)
        d.line([(cx + 12 * s, cy), (w * s - 4 * s, cy)], fill=200, width=lw)
        # central knot: a diamond inside two curls
        r = 5 * s
        d.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], outline=255, width=lw)
        d.polygon([(cx, cy - 2 * s), (cx + 2 * s, cy), (cx, cy + 2 * s), (cx - 2 * s, cy)], fill=255)
        for sgn in (-1, 1):
            d.arc([cx + sgn * 8 * s - 4 * s, cy - 4 * s, cx + sgn * 8 * s + 4 * s, cy + 4 * s],
                  0 if sgn > 0 else 180, 180 if sgn > 0 else 360, fill=255, width=lw)
    a = supersample(draw, w, h)
    x = (np.arange(w) + 0.5) / w
    a *= (smoothstep(0.0, 0.25, x) * smoothstep(0.0, 0.25, 1 - x))[None, :] * 0.6 + 0.4 * (np.abs(x - 0.5) < 0.12)[None, :]
    to_img(a).save(HUD / "ornament.png")


# ---------------------------------------------------------------- loading vignettes

W, H = 640, 360


def ridge(rng, base, amp, rough=0.45, octaves=5, w=W):
    """A mountain skyline: ridged noise (sharp peaks, rounded valleys), y of the ridge per column."""
    x = np.zeros(w)
    freq = 1.2
    a = 1.0
    for _ in range(octaves):
        ph = rng.uniform(0, 2 * np.pi)
        t = np.arange(w) / w * np.pi * freq
        x += a * (1 - np.abs(np.sin(t + ph))) ** 1.5
        a *= rough
        freq *= 2.2
    x = (x - x.min()) / max(1e-9, x.max() - x.min())
    return base - amp * x


class Scene:
    def __init__(self, seed, sky_top=(18, 22, 30), sky_horizon=(92, 100, 112), fog=(120, 126, 134)):
        self.rng = np.random.default_rng(seed)
        yy = (np.arange(H) + 0.5)[:, None] / H
        t = smoothstep(0.0, 0.62, yy) ** 0.8
        col = np.array(sky_top, float) * (1 - t) + np.array(sky_horizon, float) * t  # (H, 3)
        self.img = col[:, None, :] * np.ones((H, W, 1))
        self.fog = np.array(fog, float)

    def stars(self, n=260, top=0.6):
        for _ in range(n):
            x = self.rng.integers(0, W)
            y = int(self.rng.random() ** 1.6 * H * top)
            b = self.rng.uniform(0.3, 1.0)
            self.img[y, x] = self.img[y, x] * (1 - b) + 235 * b

    def moon(self, cx, cy, r, color, glow=0.5):
        yy, xx = np.mgrid[0:H, 0:W]
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
        disc = smoothstep(r + 1, r - 1, d)
        noise = periodic_noise(H, W, self.rng, 2.0)
        col = np.array(color, float)[None, None, :] * (0.8 + 0.3 * noise[..., None])
        halo = np.exp(-np.clip(d - r, 0, None) / (r * 1.4)) * glow * (d > r)
        self.img = self.img * (1 - disc[..., None]) + col * disc[..., None]
        self.img += halo[..., None] * np.array(color, float)[None, None, :] * 0.35

    def aurora(self, y0=0.18):
        yy, xx = np.mgrid[0:H, 0:W] / np.array([H, W])[:, None, None]
        wave = y0 + 0.06 * np.sin(xx * 9 + 1.2) + 0.03 * np.sin(xx * 23)
        band = np.exp(-((yy - wave) / 0.05) ** 2) * (0.5 + 0.5 * np.sin(xx * 60) ** 2)
        curtain = np.clip((wave - yy + 0.15) / 0.15, 0, 1) * np.exp(-((yy - wave) / 0.12) ** 2)
        a = np.clip(band * 0.5 + curtain * 0.35, 0, 1) * smoothstep(0.0, 0.3, xx) * smoothstep(0.0, 0.3, 1 - xx)
        self.img += a[..., None] * np.array([60, 150, 110])[None, None, :]

    def layer(self, heights, color, fog_amount, mist=0.0):
        yy = np.arange(H)[:, None]
        mask = (yy >= heights[None, :]).astype(float)
        col = np.array(color, float) * (1 - fog_amount) + self.fog * fog_amount
        # slight texture on the slopes
        tex = periodic_noise(H, W, self.rng, 1.4)
        shade = col[None, None, :] * (0.85 + 0.3 * tex[..., None])
        self.img = self.img * (1 - mask[..., None]) + shade * mask[..., None]
        if mist > 0:
            top = heights.min()
            band = np.exp(-((yy - (heights[None, :] + 14)) / 18.0) ** 2) * mist
            self.img = self.img * (1 - band[..., None]) + self.fog[None, None, :] * band[..., None]

    def silhouette(self, draw_fn, color=(14, 14, 18), blur=0.0):
        mask = Image.new("L", (W * 3, H * 3), 0)
        draw_fn(ImageDraw.Draw(mask), 3)
        mask = mask.resize((W, H), Image.LANCZOS)
        if blur:
            mask = mask.filter(ImageFilter.GaussianBlur(blur))
        m = np.asarray(mask, float)[..., None] / 255.0
        self.img = self.img * (1 - m) + np.array(color, float)[None, None, :] * m

    def ground_fog(self, y, amount=0.6, height=40):
        yy = np.arange(H)[:, None, None]
        band = np.exp(-((yy - y) / height) ** 2) * amount
        noise = periodic_noise(H, W, self.rng, 1.3)[..., None]
        band = band * (0.6 + 0.6 * noise)
        self.img = self.img * (1 - band) + self.fog[None, None, :] * band

    def finish(self, path, tint=(1.0, 1.0, 1.0)):
        img = self.img * np.array(tint)[None, None, :]
        # grain and vignette
        img += self.rng.normal(0, 3.0, img.shape)
        yy, xx = np.mgrid[0:H, 0:W]
        d = np.sqrt(((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2)
        img *= (1 - 0.55 * smoothstep(0.55, 1.35, d))[..., None]
        Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").save(path, optimize=True)


def pine(d, s, x, base, h, w=None):
    w = w or h * 0.32
    tiers = 5
    for i in range(tiers):
        top = base - h + i * h / tiers * 0.85
        bot = top + h / tiers * 1.6
        half = w * (0.35 + 0.65 * (i + 1) / tiers) / 2
        d.polygon([(x * s, top * s), ((x - half) * s, bot * s), ((x + half) * s, bot * s)], fill=255)
    d.rectangle([(x - w * 0.05) * s, (base - h * 0.15) * s, (x + w * 0.05) * s, base * s], fill=255)


def forest(d, s, rng, y0, y1, n, hmin, hmax):
    for _ in range(n):
        x = rng.uniform(-10, W + 10)
        base = rng.uniform(y0, y1)
        pine(d, s, x, base, rng.uniform(hmin, hmax))


def dragon(d, s, cx, cy, size):
    k = size / 100.0

    def P(x, y):
        return ((cx + x * k) * s, (cy + y * k) * s)
    # body and neck
    d.polygon([P(-40, 6), P(-10, -2), P(20, -4), P(42, -14), P(52, -16), P(48, -10), P(30, 2), P(0, 8), P(-30, 12), P(-70, 16), P(-95, 10), P(-70, 8)], fill=255)
    # head
    d.polygon([P(46, -16), P(60, -20), P(64, -16), P(56, -12)], fill=255)
    # wings (raised), membranes with scalloped edges
    d.polygon([P(-6, -2), P(-30, -60), P(-20, -48), P(-12, -58), P(-4, -40), P(6, -52), P(10, -30), P(16, -36), P(14, -4)], fill=255)
    d.polygon([P(-2, 4), P(30, 56), P(26, 40), P(40, 46), P(30, 26), P(46, 30), P(24, 6)], fill=255)


def barrow_arch(d, s, cx, base, w, h):
    # stepped stone arch with spiky Nordic finials
    d.polygon([(cx - w * 0.75, base), (cx - w * 0.75, base - h * 0.75), (cx - w * 0.35, base - h), (cx + w * 0.35, base - h),
               (cx + w * 0.75, base - h * 0.75), (cx + w * 0.75, base)], fill=255)
    for i in range(-2, 3):
        x = cx + i * w * 0.3
        d.polygon([(x - 4, base - h * (0.98 - abs(i) * 0.1)), (x + 4, base - h * (0.98 - abs(i) * 0.1)), (x, base - h * (1.25 - abs(i) * 0.1))], fill=255)


def vignettes():
    LOADING.mkdir(parents=True, exist_ok=True)
    out = []

    # 1. dragon over the mountains
    sc = Scene(11, (20, 24, 32), (110, 116, 126), (130, 134, 140))
    sc.layer(ridge(sc.rng, 200, 95), (62, 68, 78), 0.45, mist=0.5)
    sc.layer(ridge(sc.rng, 250, 90), (46, 50, 58), 0.3, mist=0.4)
    sc.layer(ridge(sc.rng, 330, 60, 0.6), (26, 28, 32), 0.05)
    sc.silhouette(lambda d, s: dragon(d, s, 400, 95, 120), (16, 16, 20))
    sc.finish(LOADING / "vignette_0.png", (1.0, 1.0, 1.04))

    # 2. barrow entrance among pines
    sc = Scene(12, (24, 26, 30), (96, 100, 104), (112, 114, 118))
    sc.layer(ridge(sc.rng, 200, 70), (72, 74, 80), 0.6, mist=0.6)
    rng = sc.rng
    sc.silhouette(lambda d, s: forest(d, s, rng, 230, 260, 45, 40, 80), (44, 46, 50))
    sc.ground_fog(262, 0.5, 25)

    def hill(d, s):
        pts = [(0, 360 * s)] + [(x * s, (300 - 60 * math.exp(-((x - 330) / 170) ** 2)) * s) for x in range(0, W + 1, 8)] + [(W * s, 360 * s)]
        d.polygon(pts, fill=255)
        barrow_arch(d, 1, 330 * s, 248 * s, 46 * s, 70 * s)
    sc.silhouette(hill, (20, 20, 22))
    sc.silhouette(lambda d, s: forest(d, s, np.random.default_rng(5), 300, 360, 14, 70, 130), (10, 10, 12))
    sc.finish(LOADING / "vignette_1.png", (1.02, 1.0, 0.97))

    # 3. standing stones under Masser and Secunda, aurora
    sc = Scene(13, (10, 14, 26), (44, 54, 72), (60, 70, 84))
    sc.stars(320)
    sc.aurora(0.2)
    sc.moon(480, 80, 42, (200, 120, 96), 0.6)
    sc.moon(560, 132, 13, (220, 224, 232), 0.5)
    sc.layer(ridge(sc.rng, 250, 50), (34, 40, 52), 0.3, mist=0.3)

    def stones(d, s):
        pts = [(0, 360 * s)] + [(x * s, (300 - 30 * math.exp(-((x - 260) / 140) ** 2)) * s) for x in range(0, W + 1, 8)] + [(W * s, 360 * s)]
        d.polygon(pts, fill=255)
        for x, h, w in ((200, 90, 22), (262, 120, 26), (322, 84, 20)):
            d.polygon([((x - w / 2) * s, 290 * s), ((x - w / 2 + 3) * s, (290 - h) * s), ((x + 2) * s, (282 - h) * s),
                       ((x + w / 2) * s, (292 - h) * s), ((x + w / 2) * s, 290 * s)], fill=255)
    sc.silhouette(stones, (12, 14, 18))
    sc.finish(LOADING / "vignette_2.png")

    # 4. longship on a cold sea with icebergs
    sc = Scene(14, (26, 30, 38), (120, 128, 138), (140, 146, 152))
    sc.layer(ridge(sc.rng, 210, 40), (90, 96, 106), 0.6, mist=0.5)
    yy = np.arange(H)[:, None, None]
    sea = (yy > 232).astype(float)
    waves = periodic_noise(H, W, sc.rng, 1.2)[..., None]
    sc.img = sc.img * (1 - sea) + (np.array([52, 60, 70]) * (0.8 + 0.4 * waves)) * sea

    def ships(d, s):
        for x, w, h in ((90, 70, 40), (520, 110, 60), (600, 40, 22)):
            d.polygon([((x - w / 2) * s, 236 * s), ((x - w / 3) * s, (236 - h) * s), ((x + w / 5) * s, (236 - h * 0.8) * s),
                       ((x + w / 2) * s, 236 * s)], fill=255)
        # longship hull with a curled prow, mast and square sail
        hull = [(230, 262), (250, 274), (370, 274), (392, 262), (400, 238), (392, 232), (386, 250), (240, 252), (226, 238), (220, 244)]
        d.polygon([(x * s, y * s) for x, y in hull], fill=255)
        d.rectangle([308 * s, 168 * s, 312 * s, 254 * s], fill=255)
        d.polygon([(276 * s, 176 * s), (344 * s, 172 * s), (348 * s, 236 * s), (272 * s, 238 * s)], fill=255)
        for i in range(9):
            d.ellipse([(246 + i * 15) * s, 250 * s, (256 + i * 15) * s, 260 * s], fill=255)
    sc.silhouette(ships, (24, 26, 30))
    sc.ground_fog(238, 0.35, 16)
    sc.finish(LOADING / "vignette_3.png", (0.98, 1.0, 1.05))

    # 5. a hold capital on its rock
    sc = Scene(15, (30, 30, 34), (140, 128, 112), (150, 140, 126))
    sc.layer(ridge(sc.rng, 200, 90), (80, 76, 74), 0.5, mist=0.4)

    def city(d, s):
        pts = [(0, 360 * s), (0, 300 * s), (150 * s, 284 * s), (220 * s, 230 * s), (420 * s, 222 * s), (480 * s, 260 * s), (W * s, 300 * s), (W * s, 360 * s)]
        d.polygon(pts, fill=255)
        # walls and towers
        d.rectangle([200 * s, 214 * s, 470 * s, 232 * s], fill=255)
        for x in range(206, 470, 14):
            d.rectangle([x * s, 208 * s, (x + 7) * s, 216 * s], fill=255)
        for x in (210, 460):
            d.rectangle([(x - 9) * s, 186 * s, (x + 9) * s, 232 * s], fill=255)
            d.polygon([((x - 12) * s, 188 * s), ((x + 12) * s, 188 * s), (x * s, 168 * s)], fill=255)
        # the great hall with a steep roof and crossed gable beams
        d.rectangle([300 * s, 150 * s, 380 * s, 214 * s], fill=255)
        d.polygon([(292 * s, 154 * s), (388 * s, 154 * s), (340 * s, 98 * s)], fill=255)
        d.line([(330 * s, 106 * s), (352 * s, 84 * s)], fill=255, width=3 * s)
        d.line([(350 * s, 106 * s), (328 * s, 84 * s)], fill=255, width=3 * s)
        for x in (240, 262, 410, 432):
            d.polygon([((x - 12) * s, 214 * s), ((x + 12) * s, 214 * s), (x * s, 192 * s)], fill=255)
    sc.silhouette(city, (26, 24, 24))
    sc.ground_fog(300, 0.35, 30)
    sc.finish(LOADING / "vignette_4.png", (1.04, 1.0, 0.94))

    # 6. elk in a misty pine forest
    sc = Scene(16, (22, 26, 26), (100, 110, 104), (116, 124, 118))
    rng = sc.rng
    sc.silhouette(lambda d, s: forest(d, s, rng, 200, 230, 60, 50, 90), (70, 78, 74))
    sc.ground_fog(232, 0.6, 30)
    sc.silhouette(lambda d, s: forest(d, s, rng, 250, 280, 40, 70, 120), (40, 44, 42))
    sc.ground_fog(285, 0.45, 25)

    def elk(d, s):
        d.polygon([(0, 360 * s), (0, 318 * s), (W * s, 312 * s), (W * s, 360 * s)], fill=255)
        bx, by = 380, 290
        body = [(bx - 40, by - 30), (bx + 20, by - 34), (bx + 30, by - 50), (bx + 44, by - 62), (bx + 52, by - 58), (bx + 46, by - 44),
                (bx + 34, by - 26), (bx + 28, by - 8), (bx - 36, by - 6), (bx - 44, by - 18)]
        d.polygon([(x * s, y * s) for x, y in body], fill=255)
        for lx in (bx - 34, bx - 24, bx + 14, bx + 24):
            d.line([(lx * s, (by - 10) * s), (lx * s, (by + 26) * s)], fill=255, width=3 * s)
        # antlers
        for sgn in (-1, 1):
            base = (bx + 46, by - 62)
            pts = [base, (base[0] + 6 * sgn, base[1] - 16), (base[0] + 14 * sgn, base[1] - 28), (base[0] + 22 * sgn, base[1] - 34)]
            d.line([(x * s, y * s) for x, y in pts], fill=255, width=2 * s)
            for k in range(1, 4):
                px, py = pts[k]
                d.line([(px * s, py * s), ((px + 8 * sgn) * s, (py - 6) * s)], fill=255, width=2 * s)
    sc.silhouette(elk, (14, 16, 16))
    sc.silhouette(lambda d, s: forest(d, s, np.random.default_rng(9), 340, 380, 6, 120, 170), (8, 9, 9))
    sc.finish(LOADING / "vignette_5.png", (0.98, 1.02, 0.98))

    # 7. a word wall on a windswept peak
    sc = Scene(17, (28, 30, 36), (150, 154, 160), (168, 170, 174))
    sc.layer(ridge(sc.rng, 225, 75), (84, 88, 96), 0.5, mist=0.6)

    def peak(d, s):
        pts = [(0, 360 * s), (0, 320 * s), (180 * s, 250 * s), (300 * s, 210 * s), (420 * s, 240 * s), (W * s, 330 * s), (W * s, 360 * s)]
        d.polygon(pts, fill=255)
        # curved wall: a thick arc of stone with a ragged top
        for i in range(60):
            t = i / 59
            x = 230 + 150 * t
            y = 214 - 26 * math.sin(math.pi * t)
            top = y - 38 - 6 * math.sin(t * 17)
            d.rectangle([x * s, top * s, (x + 3) * s, (y + 6) * s], fill=255)
    sc.silhouette(peak, (30, 30, 34))
    # carved runes catch the light
    def runes(d, s):
        r = np.random.default_rng(3)
        for row in range(3):
            for i in range(14):
                t = (i + 0.5) / 14
                x = 236 + 140 * t
                y = 214 - 26 * math.sin(math.pi * t) - 30 + row * 9
                a = r.uniform(0, math.pi)
                d.line([(x * s, y * s), ((x + 3 * math.cos(a)) * s, (y + 4) * s)], fill=255, width=s)
                d.line([((x + 2) * s, y * s), ((x + 2) * s, (y + 5) * s)], fill=255, width=s)
    sc.silhouette(runes, (110, 150, 190), blur=0.3)
    sc.ground_fog(250, 0.4, 40)
    sc.finish(LOADING / "vignette_6.png", (0.98, 1.0, 1.04))
    print("wrote 7 vignettes to", LOADING)


def smoke():
    rng = np.random.default_rng(77)
    n = periodic_noise(256, 256, rng, 1.7)
    n2 = periodic_noise(256, 256, rng, 1.3)
    a = np.clip(smoothstep(0.35, 0.85, n) * (0.5 + 0.5 * n2), 0, 1)
    to_img(a * 0.9, (255, 255, 255)).save(LOADING / "smoke.png")


def main():
    HUD.mkdir(parents=True, exist_ok=True)
    LOADING.mkdir(parents=True, exist_ok=True)
    sneak_eye()
    crosshair()
    compass()
    bar_caps()
    ornament()
    smoke()
    vignettes()
    print("wrote HUD sprites to", HUD)


if __name__ == "__main__":
    main()
