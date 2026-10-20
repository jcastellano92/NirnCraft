"""The moons' proof sheets, offline (no game): through the game's own shaders (tools/dev/sky_offline.py: atlas_sky.fsh,
atlas_moons.glsl's moonDisc via its probe) and its own orbits (MoonMath, javac'd from the mod's source).

    py -3 tools/dev/moon_sheets.py [--out .work/moon_sheets] [--only phases,khajiit,crops,tiling,poles,events,pass,
                                    night,eclipses,place,farfield]

  phases     each moon through a month (new, waxing crescent, first quarter, waxing gibbous, full, the waning side)
  khajiit    the two moons' phase pairs (Masser x Secunda: new, waxing, full, waning: 16)
  crops      1:1 and 4x crops of several regions per moon, side by side (the detail layer at 4x)
  tiling     a crop spanning several of the detail layer's periods and its autocorrelation (no repeat)
  poles      the north and south poles close up (before/after: --before DIR keeps an earlier run's crops beside)
  events     a sheet per kind of event (MoonMath.events): lunar eclipses, occultations, transit shadows,
             conjunctions, double-full nights, solar and dark eclipses
  pass       a time-lapse of Secunda passing in front of Masser (a frame every few minutes of game time)
  night      one night's motion of both moons against the stars (a frame an hour, one fixed view)
  eclipses   each moon's solar eclipse in sequence (first contact, partial, the diamond ring, totality), the horizon
             ring in totality
  place      one moment of totality seen from inside the umbra's footprint and from 300 km and the footprint's edge away
  farfield   the far field with an umbra's edge on the land (atlas_render, the core's atlas_set_eclipse)
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/dev"))
import sky_offline as so  # noqa: E402

DAY = 24000.0


def label(img, text, xy=(6, 4)):
    d = ImageDraw.Draw(img)
    d.rectangle([xy[0] - 2, xy[1] - 2, xy[0] + 7 * len(text) + 4, xy[1] + 13], fill=(0, 0, 0))
    d.text(xy, text, fill=(235, 235, 235))
    return img


def grid(tiles, cols, pad=4, bg=(12, 12, 12)):
    w, h = tiles[0].size
    rows = (len(tiles) + cols - 1) // cols
    g = Image.new("RGB", (cols * (w + pad) + pad, rows * (h + pad) + pad), bg)
    for i, t in enumerate(tiles):
        g.paste(t, (pad + (i % cols) * (w + pad), pad + (i // cols) * (h + pad)))
    return g


def java(*args):
    jdk = so.find_up("toolchains/jdk-25.0.4.1+1/bin")
    out = ROOT / ".work/sky_offline/classes"
    so.compile_math(jdk, out)
    r = subprocess.run([str(jdk / "java.exe"), "-cp", str(out), "com.nirncraft.atmosphere.MoonMath", *map(str, args)],
                       capture_output=True, text=True, check=True)
    return [json.loads(line) for line in r.stdout.splitlines() if line.strip()]


def events(frm, to):
    return java("events", frm, to)


def track(frm, to, step, x=0, z=0):
    return java("track", frm, to, step, x, z)


def toward(d):
    return math.degrees(math.atan2(d[0], -d[2])), math.degrees(math.asin(max(-1.0, min(1.0, d[1]))))


def scene(day, x=0, z=3500):
    return {"dayTime": day * DAY, "x": x, "z": z, "kp": 0, "brightness": 0}


def elong_sun(e):
    """The sun in the disc's frame at elongation e (0 new, pi/2 first quarter lit from the right, pi full)."""
    return (math.sin(e), 0.0, -math.cos(e))


# ------------------------------------------------------------------------------------------------ the sheets
def phases(sky, out):
    sky.scene(scene(33.0))
    names = ["new", "waxing crescent", "", "first quarter", "", "waxing gibbous", "full", "waning gibbous", "",
             "last quarter", "", "waning crescent"]
    for which, moon in ((0, "masser"), (1, "secunda")):
        tiles = []
        for k in range(12):
            e = k / 12 * 2 * math.pi
            img = sky.moon(which, elong_sun(e), 320)
            tiles.append(label(img, f"{int(round(math.degrees(e)))} deg {names[k]}"))
        grid(tiles, 6).save(out / f"phases_month_{moon}.png")


def khajiit(sky, out):
    sky.scene(scene(33.0))
    ph = [("new", 0.0), ("waxing", math.pi / 2), ("full", math.pi), ("waning", 1.5 * math.pi)]
    tiles = []
    for mn, me in ph:
        for sn, se in ph:
            a = sky.moon(0, elong_sun(me), 240)
            b = sky.moon(1, elong_sun(se), 240).resize((110, 110))
            t = Image.new("RGB", (360, 240), (6, 6, 8))
            t.paste(a, (0, 0))
            t.paste(b, (245, 65))
            tiles.append(label(t, f"Masser {mn} x Secunda {sn}"))
    grid(tiles, 4).save(out / "khajiit_phase_pairs.png")


CROPS = {
    "masser": [("the Eye and Mara's Tear", (-0.27, 0.22)), ("the Great Rift", (0.02, 0.10)), ("chaos", (0.68, -0.08)),
               ("a basalt sea's edge", (0.20, -0.42)), ("old white skin", (-0.55, -0.30)), ("north pole", (0.0, 0.9))],
    "secunda": [("the Parliament of Craters", (0.43, 0.17)), ("the Weeping Star", (-0.26, -0.35)),
                ("a dark basin's edge", (-0.40, -0.25)), ("saturated highlands", (-0.2, 0.45)),
                ("old white skin", (0.1, -0.6)), ("north pole", (0.0, 0.9))],
}


def low_sun(c, elev=0.2):
    """A sun low over the disc point c (x, y in disc radii) from the east: the terminator just west of it."""
    x, y = c
    z = math.sqrt(max(1 - x * x - y * y, 0.0))
    lon = math.atan2(x, z)
    e = (math.cos(lon), 0.0, -math.sin(lon))
    v = [elev * x + e[0], elev * y + e[1], elev * z + e[2]]
    n = math.sqrt(sum(a * a for a in v))
    return tuple(a / n for a in v)


def crops(sky, out):
    sky.scene(scene(33.0))
    for which, moon in ((0, "masser"), (1, "secunda")):
        W = 4096 if which == 0 else 2048
        one = 256 * 2 * math.pi / W  # (a 512 px window at 1 px a map texel at the disc's centre)
        tiles = []
        for name, c in CROPS[moon]:
            sun = low_sun(c, 0.25)
            a = label(sky.moon(which, sun, 512, center=c, scale=one), f"{name} 1:1")
            b = label(sky.moon(which, sun, 512, center=c, scale=one / 4), f"{name} 4x")
            pair = Image.new("RGB", (1028, 512), (0, 0, 0))
            pair.paste(a, (0, 0))
            pair.paste(b, (516, 0))
            tiles.append(pair)
        grid(tiles, 2).save(out / f"crops_{moon}.png")


def tiling(sky, out):
    """A crop 8 x magnified (the detail layer in full) spanning ~5 of its periods, and the luminance's
    autocorrelation: a repeating texture shows bright peaks at its period; this shows only the central one."""
    sky.scene(scene(33.0))
    c = (0.35, -0.2)
    sun = low_sun(c, 0.5)
    period = 16 * 2 * math.pi / 4096  # MASSER_DETAIL_TILE (radii)
    img = sky.moon(0, sun, 1024, center=c, scale=period * 2.6)
    a = np.asarray(img.convert("L"), np.float64)
    a -= a.mean()
    f = np.fft.fft2(a)
    ac = np.real(np.fft.ifft2(f * np.conj(f)))
    ac = np.fft.fftshift(ac) / ac.max()
    c = 512
    px = 1024 / (2 * 2.6)  # (the period in pixels)
    peaks = {}
    for k in (1, 2):
        ring = []
        for dx, dy in ((k, 0), (0, k), (k, k)):
            ring.append(ac[c + int(round(dy * px)), c + int(round(dx * px))])
        peaks[k] = max(ring)
    away = ac.copy()
    yy, xx = np.mgrid[:1024, :1024]
    away[(yy - c) ** 2 + (xx - c) ** 2 < 40 ** 2] = 0
    vis = Image.fromarray((np.clip(ac * 0.5 + 0.5, 0, 1) ** 2 * 255).astype(np.uint8)).convert("RGB")
    d = ImageDraw.Draw(vis)
    for k in (1, 2):
        r = k * px
        d.ellipse([c - r, c - r, c + r, c + r], outline=(255, 60, 60))
    sheet = Image.new("RGB", (2048 + 12, 1024 + 40), (10, 10, 10))
    sheet.paste(img, (0, 0))
    sheet.paste(vis, (1036, 0))
    ImageDraw.Draw(sheet).text((8, 1030), f"Masser, 8x a texel: ~5 detail periods across. Autocorrelation at the period "
                               f"(red rings): {peaks[1]:.3f}, at twice it {peaks[2]:.3f}; strongest away from the centre "
                               f"{away.max():.3f} (a tiled texture: ~1)", fill=(230, 230, 230))
    sheet.save(out / "tiling_check.png")
    return peaks, float(away.max())


def poles(sky, out, before):
    sky.scene(scene(33.0))
    half = (1.0, 0.0, 0.05)
    tiles = []
    for which, moon in ((0, "masser"), (1, "secunda")):
        for name, c, sun in (("north pole", (0.0, 0.9), half), ("south pole", (0.0, -0.9), half),
                             ("north pole lit", (0.0, 0.9), (0.5, 0.3, 0.8))):
            tiles.append(label(sky.moon(which, sun, 480, center=c, scale=0.12), f"{moon} {name}"))
    grid(tiles, 3).save(out / "poles_after.png")
    # the comparison the owner asked for: the north pole, a terminator, a boundary, before and after
    pairs = []
    for name, c, sun, sc in (("masser_npole", (0.0, 0.9), half, 0.12), ("masser_terminator", (0.05, 0.2), half, 0.15),
                             ("masser_boundary", (-0.35, 0.05), (math.sin(0.5), 0, math.cos(0.5)), 0.18),
                             ("secunda_npole", (0.0, 0.9), half, 0.12)):
        which = 1 if name.startswith("secunda") else 0
        after = sky.moon(which, sun, 480, center=c, scale=sc)
        b = before / f"before_{name}.png" if before else None
        pair = Image.new("RGB", (968, 480), (0, 0, 0))
        if b and b.exists():
            pair.paste(label(Image.open(b).convert("RGB").resize((480, 480)), f"{name} before"), (0, 0))
        pair.paste(label(after, f"{name} after"), (488, 0))
        pairs.append(pair)
    grid(pairs, 1).save(out / "before_after.png")


def pick(evs, kind, night=None, deep=0.0):
    for e in evs:
        if e["kind"] != kind or e["depth"] < deep:
            continue
        if night is True and e["sunY"] > -0.1:
            continue
        if night is False and e["sunY"] < 0.2:
            continue
        return e
    return None


def view_at(sky, day, aim, fov, w=720, h=720, x=0, z=3500):
    sky.scene(scene(day, x, z))
    yaw, pitch = toward(aim)
    return sky.view(w, h, yaw, pitch, fov, sky.pre)


def moon_dirs(sky):
    n = sky.m["night"]
    return n[40:43], n[52:55], n[43], n[55]


def event_sheets(sky, out):
    evs = events(0, 730)
    sheets = {}
    for kind, night, deep, which in (("LUNAR_ECLIPSE_MASSER", True, 0.2, 0), ("LUNAR_ECLIPSE_SECUNDA", True, 0.9, 1),
                                     ("SECUNDA_OCCULTS_MASSER", True, 0.5, 2), ("SECUNDA_SHADOW_ON_MASSER", True, 0.3, 0),
                                     ("MASSER_SHADOW_ON_SECUNDA", True, 0.3, 1), ("CONJUNCTION", True, 0.3, 2),
                                     ("DOUBLE_FULL", True, 0.0, 2)):
        e = None
        for cand in evs:  # (the first one seen: its moon, or both, well up at its peak in the night)
            if cand["kind"] != kind or cand["depth"] < deep or (night and cand["sunY"] > -0.1):
                continue
            r = track(cand["peak"], cand["peak"], 1.0, 0, 3500)[0]
            ys = [r["masser"][1], r["secunda"][1]]
            up = ys[which] if which < 2 else min(ys)
            if up > 0.2:
                e = cand
                break
        if e is None:
            print("  (none:", kind, ")")
            continue
        tiles = []
        half = min((e["end"] - e["start"]) * 0.5, 0.125)  # (+- 3 h round the peak at most)
        for k, f in enumerate((-1.0, -0.5, 0.0, 0.5, 1.0)):
            day = e["peak"] + half * f
            sky.scene(scene(day))
            m, s, rm, rs = moon_dirs(sky)
            if which == 0:
                aim, fov = m, math.degrees(rm) * 2.6
            elif which == 1:
                aim, fov = s, math.degrees(rs) * 3.0
            else:
                mid = [m[i] + s[i] for i in range(3)]
                ln = math.sqrt(sum(v * v for v in mid))
                sep = math.degrees(math.acos(max(-1.0, min(1.0, sum(m[i] * s[i] for i in range(3))))))
                aim, fov = [v / ln for v in mid], min(max(sep * 1.6 + 25.0, 40.0), 110.0)
            img = view_at(sky, day, aim, fov, 480, 480)
            tiles.append(label(img, f"{kind.lower()} day {day:.3f} ({f * half * 24:+.1f} h)"))
        grid(tiles, 5).save(out / f"event_{kind.lower()}.png")
        sheets[kind] = e
    return sheets


def pass_sheet(sky, out):
    """Secunda in front of Masser, start to end: a frame every few minutes of game time, the view following them."""
    evs = events(0, 730)
    e = next((e for e in evs if e["kind"] == "SECUNDA_OCCULTS_MASSER" and e["depth"] > 0.8 and e["sunY"] < -0.2), None)
    if e is None:
        return None
    # the night's dark part of it, both moons up
    frames = []
    day = e["start"]
    step = 12.0 / 1440.0  # 12 minutes of game time (a game day is 1440 minutes)
    while day < e["end"] and len(frames) < 48:
        sky.scene(scene(day))
        m, s, rm, rs = moon_dirs(sky)
        sun = sky.sun
        if sun[1] < -0.08 and m[1] > 0.0:
            mid = [(m[i] + s[i]) / 2 for i in range(3)]
            ln = math.sqrt(sum(v * v for v in mid))
            img = view_at(sky, day, [v / ln for v in mid], math.degrees(rm) * 3.2, 300, 300)
            frames.append(label(img, f"{(day % 1) * 24:05.2f} h"))
        day += step
    if not frames:
        return None
    pick_n = max(1, len(frames) // 24)
    grid(frames[::pick_n][:24], 6).save(out / "pass_secunda_over_masser.png")
    return e


def night_sheet(sky, out):
    """One night: both moons against the stars, a frame an hour, the view fixed on the sky."""
    day0 = 40.0
    for r in java("moons", 30.75, 120.75, 1.0):  # (a night with both moons well up at midnight)
        if r["sunY"] < -0.3 and r["masser"][1] > 0.25 and r["secunda"][1] > 0.25:
            day0 = math.floor(r["day"])
            break
    sky.scene(scene(day0 + 0.75))
    m, s, _rm, _rs = moon_dirs(sky)
    mid = [m[i] + s[i] for i in range(3)]
    ln = math.sqrt(sum(v * v for v in mid))
    yaw, pitch = toward([v / ln for v in mid])
    frames = []
    for h in range(0, 10):
        day = day0 + 0.58 + h / 24
        sky.scene(scene(day))
        if sky.sun[1] > -0.05:
            continue
        img = sky.view(640, 400, yaw, max(pitch, 25), 110, sky.pre)
        frames.append(label(img, f"day {day:.3f}, {(day % 1) * 24:.1f} h"))
    grid(frames, 3).save(out / "night_motion.png")


def eclipse_sheets(sky, out):
    ecl = so.eclipse_scan(0, 1500)
    for kind, moon, fovk in (("SOLAR_ECLIPSE_SECUNDA", "secunda", 3.5), ("SOLAR_ECLIPSE_MASSER", "masser", 2.8)):
        e = next((e for e in ecl if e.get("kind") == kind and e["hidden"] > 0.999 and e["sunY"] > 0.3), None)
        if e is None:
            print("  (no", kind, ")")
            continue
        tr = track(e["day"] - 0.12, e["day"] + 0.12, 1.0 / 1440.0)
        hid = [r["hidden"] for r in tr]
        i1 = next(i for i, h in enumerate(hid) if h > 0.0)
        i2 = next(i for i, h in enumerate(hid) if h > 0.999)
        i3 = max(i for i, h in enumerate(hid) if h > 0.999)
        i4 = max(i for i, h in enumerate(hid) if h > 0.0)
        im = (i2 + i3) // 2
        moments = [("first contact", i1 + 2), ("partial", (i1 + i2) // 2), ("diamond ring", max(i2 - 1, 0)),
                   ("totality", im), ("diamond ring (third contact)", min(i3 + 1, len(tr) - 1)),
                   ("partial, ending", (i3 + i4) // 2)]
        tiles = []
        r0 = tr[im][moon][3]
        for name, i in moments:
            day = tr[i]["day"]
            img = view_at(sky, day, tr[i]["sun"], math.degrees(r0) * fovk, 480, 480)
            tiles.append(label(img, f"{name} {(day % 1) * 24:05.2f} h hidden {tr[i]['hidden']:.4f}"))
        grid(tiles, 3).save(out / f"eclipse_sequence_{moon}.png")
        # the horizon ring in totality: all round, low
        day = tr[im]["day"]
        sky.scene(scene(day))
        yaw0, _p = toward(tr[im]["sun"])
        ring = [label(sky.view(640, 360, yaw0 + a, 4, 90, sky.pre), f"totality, looking {a:+d} deg from the sun")
                for a in (0, 90, 180, 270)]
        grid(ring, 2).save(out / f"eclipse_horizon_{moon}.png")


def place_sheet(sky, out):
    """One moment of Secunda's totality from three places: inside its umbra's footprint, 300 km away, and just
    outside the footprint's edge (the eclipse partial there)."""
    ecl = so.eclipse_scan(0, 1500)
    e = next((e for e in ecl if e.get("kind") == "SOLAR_ECLIPSE_SECUNDA" and e["hidden"] > 0.999 and e["sunY"] > 0.3),
             None)
    if e is None:
        return
    day = e["day"]
    # the footprint's edge along +x from the world's origin (where the scan saw totality): a binary search on the
    # hidden share there (MoonMath's own geometry)
    hid = lambda km: track(day, day, 1.0, km / 0.1, 0)[0]["hidden"]
    lo, hi = 0.0, 8000.0
    for _ in range(14):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if hid(mid) > 0.999 else (lo, mid)
    edge = lo
    tiles = []
    for name, dist in (("inside the umbra's footprint", 0.0), ("300 km away", 300.0),
                       (f"{edge:.0f} km away: its edge", edge + 2.0), (f"{edge + 300:.0f} km away: beyond it", edge + 300.0)):
        x, z = dist / 0.1, 0.0
        sky.scene(scene(day, x, z))
        yaw, pitch = toward(sky.sun)
        hidden = sky.m["moons"][0]
        tiles.append(label(sky.view(640, 400, yaw, max(pitch - 15, 5), 90, sky.pre),
                           f"{name}: hidden {hidden:.4f}"))
    grid(tiles, 2).save(out / "eclipse_by_place.png")
    return edge


def farfield(out):
    """The far field (atlas_render, the core) under an umbra's edge: the land near the camera lit, beyond the edge in
    shadow; and the same without the eclipse."""
    exe = ROOT / "native/build/Release/atlas_render.exe"
    raster = ROOT / "native/data/tamriel_raster.bin"
    if not exe.exists() or not raster.exists():
        print("  (no atlas_render build)")
        return
    base = [str(exe), f"raster={raster}", "x=1200", "z=-1800", "above=60", "yaw=110", "pitch=-3", "fov=70", "w=1280",
            "h=640", "backend=vulkan", "sun=30,20", "flags=shadows,atmos", "shade=pack", "exposure=auto"]
    shots = []
    for name, extra in (("no eclipse", []), ("an umbra's edge 120 km ahead", ["umbra=2633,41340,2385,110"]),
                        ("the umbra's edge 40 km ahead", ["umbra=2633,41340,2305,110"])):
        o = out / f"farfield_{len(shots)}.png"
        r = subprocess.run(base + [f"out={o}"] + extra, capture_output=True, text=True)
        if r.returncode != 0 or not o.exists():
            print("  atlas_render failed:", r.stderr[-400:])
            return
        shots.append(label(Image.open(o).convert("RGB"), name))
    grid(shots, 1).save(out / "farfield_umbra_edge.png")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / ".work/moon_sheets"))
    ap.add_argument("--only", default="")
    ap.add_argument("--before", default=str(ROOT / ".work/compare"))
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    want = set(a.only.split(",")) if a.only else None
    on = lambda k: want is None or k in want
    sky = so.Sky(so.find_up("native/build/Release/atlas_core.dll"))
    if on("phases"):
        phases(sky, out)
    if on("khajiit"):
        khajiit(sky, out)
    if on("crops"):
        crops(sky, out)
    if on("tiling"):
        print("  tiling:", tiling(sky, out))
    if on("poles"):
        poles(sky, out, Path(a.before))
    if on("events"):
        for k, e in event_sheets(sky, out).items():
            print(f"  {k}: day {e['start']:.3f} .. {e['end']:.3f}, depth {e['depth']:.2f}")
    if on("pass"):
        print("  pass:", pass_sheet(sky, out))
    if on("night"):
        night_sheet(sky, out)
    if on("eclipses"):
        eclipse_sheets(sky, out)
    if on("place"):
        print("  umbra radius on the ground (km):", place_sheet(sky, out))
    if on("farfield"):
        farfield(out)
    print("sheets in", out)


if __name__ == "__main__":
    main()
