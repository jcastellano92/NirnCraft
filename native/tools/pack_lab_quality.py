#!/usr/bin/env python3
"""Visual weights for adaptive quality v2 (docs/ADAPTIVE-QUALITY.md): how much each of the pack's runtime knobs shows.

For a few lab scenes, pack_lab renders the scene once and the pack's passes again for every knob setting of a sweep
(each knob at 0 / 0.25 / 0.5 / 0.75 with the others at 1, plus all-1 and all-0); each image is compared with the
all-1 reference by a simple perceptual metric:

    Oklab (perceptually uniform lightness / chroma), both images blurred by a 1-pixel Gaussian (the eye does not
    resolve single-pixel sampling noise at play distance: noise from fewer samples counts less than lost structure),
    dE = 100 * |Oklab difference| per pixel; loss = mean dE + 0.25 * 95th percentile dE (a lost reflection on one lake
    counts even when the lake is a small part of the frame).

Per knob, averaged over the scenes: weight = loss with the knob off (q = 0), and the shape alpha of its value curve
V(q) = (1 - exp(-alpha q)) / (1 - exp(-alpha)) fitted to 1 - loss(q) / loss(0) (alpha near 0: linear, large: most of
the look comes from the first samples). The GPU time of the passes per setting (GL timestamps, median of 5) is
reported too (the lab machine, small frame: shapes, not budgets).

usage (from native/, after native\\build.ps1 or a pack_lab build):
    py -3 tools/pack_lab_quality.py [--out build/quality-lab] [--w 640 --h 360] [--scenes land,water,golden,lamps,volcano]
    (more scenes: cave, window, helgen, night)
    py -3 tools/pack_lab_quality.py --smooth [--scenes water,golden,lamps]
--smooth: every knob together from 0 to 1 in tenths per scene (loss against all-1 and the passes' GPU time per step,
plus a contact sheet smooth.png): the look and the cost should both change in small, even steps, with no jump.

Runs pack_lab at below-normal priority, one at a time, aether on its CPU backend (gpu=0); skips while the GPU is busy
(nvidia-smi utilization >= 40%, e.g. a game running).
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
NATIVE = HERE.parent
LAB = NATIVE / "build" / "Release" / "pack_lab.exe"

SCENES = {
    "land": "time=0.25",
    "water": "x=5000 z=0 above=6 pitch=-12 yaw=90 time=0.42",
    "golden": "time=0.46 yaw=270 fog=0.15",
    "lamps": "time=0.75 lamps=1 fog=0.3 above=3 pitch=-6",
    "volcano": "time=0.3 volcano=1 pitch=6",
    # the look pass's scenes (not in the default set the weights were fitted on): shafts through a cave mouth and a
    # window (real shadow map), a burning town's smoke columns, a moonlit night
    "cave": "scene=cave time=0.08 yaw=90",
    "window": "scene=window time=0.1 yaw=38 look=55 pitch=-8",
    "helgen": "time=0.3 x=-24 z=1143 above=6 pitch=8 fires=8",
    "night": "time=0.75 moons=1",
}
DEFAULT_SCENES = ["land", "water", "golden", "lamps", "volcano"]
# knob -> the lab keys it drives (lib/quality.glsl texels; the controller writes the wave LOD and the far fraction
# from the same knob as the count / start)
KNOBS = {
    "shadow_taps": ["qs"],
    "ssao": ["qa"],
    "ssr": ["qr"],
    "light_shafts": ["qv"],
    "waves": ["qw", "ql"],
    "water_extras": ["qk", "qy"],
    "air_march": ["qm"],
    "halos": ["qh"],
    "smoke_columns": ["qp"],
    "far_detail": ["qd", "qf"],
    "bloom": ["qb"],
}
LEVELS = [0.0, 0.25, 0.5, 0.75]


def gpu_busy() -> int:
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=10).stdout
        return max(int(x) for x in out.split())
    except (OSError, ValueError, subprocess.SubprocessError):
        return 0


def srgb_to_oklab(img: np.ndarray) -> np.ndarray:
    c = img.astype(np.float64) / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
                   [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    lms = np.cbrt(lin @ m1.T)
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                   [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    return lms @ m2.T


def blur(a: np.ndarray, sigma: float = 1.0) -> np.ndarray:
    r = int(math.ceil(3 * sigma))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    p = np.pad(a, ((r, r), (r, r), (0, 0)), mode="edge")
    h = sum(k[i] * p[:, i:i + a.shape[1], :] for i in range(2 * r + 1))
    return sum(k[i] * h[i:i + a.shape[0], :, :] for i in range(2 * r + 1))


def loss(ref: np.ndarray, img: np.ndarray) -> float:
    d = np.linalg.norm(blur(srgb_to_oklab(ref)) - blur(srgb_to_oklab(img)), axis=2) * 100.0
    return float(d.mean() + 0.25 * np.percentile(d, 95))


def shape_alpha(qs: list[float], vs: list[float]) -> float:
    best, err = 0.01, 1e30
    for a in np.concatenate([np.linspace(0.01, 1, 20), np.linspace(1.1, 12, 60)]):
        e = sum(((1 - math.exp(-a * q)) / (1 - math.exp(-a)) - v) ** 2 for q, v in zip(qs, vs))
        if e < err:
            best, err = float(a), e
    return best


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(NATIVE / "build" / "quality-lab"))
    ap.add_argument("--w", type=int, default=640)
    ap.add_argument("--h", type=int, default=360)
    ap.add_argument("--scenes", default=",".join(DEFAULT_SCENES))
    ap.add_argument("--smooth", action="store_true")
    args = ap.parse_args()
    if args.smooth:
        return smooth(args)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    if not LAB.exists():
        print(f"no {LAB}: build pack_lab first")
        return 2
    results: dict[str, dict] = {}
    for scene in args.scenes.split(","):
        busy = gpu_busy()
        if busy >= 40:
            print(f"{scene}: GPU {busy}% busy, skipped")
            continue
        d = out / scene
        d.mkdir(exist_ok=True)
        lines = [f"{d / 'all0.png'} q=0"]
        for knob, keys in KNOBS.items():
            for q in LEVELS:
                lines.append(f"{d / f'{knob}_{q:.2f}.png'} q=1 " + " ".join(f"{k}={q}" for k in keys))
        (d / "sweep.txt").write_text("\n".join(lines) + "\n")
        cmd = [str(LAB), str(d / "ref.png"), f"w={args.w}", f"h={args.h}", "gpu=0", "gputime=5", "q=1",
               f"sweep={d / 'sweep.txt'}"] + SCENES[scene].split()
        flags = 0x00004000 if sys.platform == "win32" else 0  # BELOW_NORMAL_PRIORITY_CLASS
        run = subprocess.run(cmd, cwd=NATIVE, capture_output=True, text=True, creationflags=flags)
        times = {}
        for line in run.stdout.splitlines():
            if line.startswith("pack_lab: sweep ") and line.endswith(" ms"):
                parts = line.split()
                times[pathlib.Path(parts[2]).stem] = float(parts[3])
            elif line.startswith("pack_lab: passes "):
                times["ref"] = float(line.split()[2])
        if run.returncode != 0:
            print(run.stdout[-2000:])
            return 1
        ref = np.asarray(Image.open(d / "ref.png").convert("RGB"))
        sc = {"all0": loss(ref, np.asarray(Image.open(d / "all0.png").convert("RGB"))), "ms": times, "knobs": {}}
        for knob in KNOBS:
            sc["knobs"][knob] = {f"{q:.2f}": loss(ref, np.asarray(Image.open(d / f"{knob}_{q:.2f}.png").convert("RGB")))
                                 for q in LEVELS}
        results[scene] = sc
        print(f"{scene}: all-off loss {sc['all0']:.2f}, ref {times.get('ref', -1):.3f} ms, all-off {times.get('all0', -1):.3f} ms")
    if not results:
        return 1
    summary = {}
    for knob in KNOBS:
        per_q = {q: float(np.mean([results[s]["knobs"][knob][f"{q:.2f}"] for s in results])) for q in LEVELS}
        w = per_q[0.0]
        vs = [1 - per_q[q] / w if w > 1e-6 else 1.0 for q in LEVELS[1:]] + [1.0]
        alpha = shape_alpha(LEVELS[1:] + [1.0], vs)
        ms0 = np.mean([results[s]["ms"].get(f"{knob}_0.00", np.nan) for s in results])
        ref_ms = np.mean([results[s]["ms"].get("ref", np.nan) for s in results])
        summary[knob] = {"weight": w, "alpha": alpha, "loss": per_q, "per_scene_off": {s: results[s]["knobs"][knob]["0.00"] for s in results},
                         "saved_ms_lab": float(ref_ms - ms0) if not math.isnan(ms0) else None}
    (out / "weights.json").write_text(json.dumps({"scenes": results, "summary": summary}, indent=1))
    print(f"\n{'knob':15s} {'weight':>7s} {'alpha':>6s}  loss at 0/.25/.5/.75          saved ms (lab)  off per scene")
    for knob, s in sorted(summary.items(), key=lambda kv: -kv[1]["weight"]):
        lq = "/".join(f"{s['loss'][q]:.2f}" for q in LEVELS)
        ps = " ".join(f"{k}={v:.2f}" for k, v in s["per_scene_off"].items())
        sv = f"{s['saved_ms_lab']:.3f}" if s["saved_ms_lab"] is not None else "-"
        print(f"{knob:15s} {s['weight']:7.2f} {s['alpha']:6.2f}  {lq:28s} {sv:>8s}  {ps}")
    print(f"\nwrote {out / 'weights.json'}")
    return 0


def smooth(args) -> int:
    out = pathlib.Path(args.out) / "smooth"
    out.mkdir(parents=True, exist_ok=True)
    steps = [i / 10 for i in range(11)]
    rows = []
    worst = 0.0
    for scene in args.scenes.split(","):
        busy = gpu_busy()
        if busy >= 40:
            print(f"{scene}: GPU {busy}% busy, skipped")
            continue
        d = out / scene
        d.mkdir(exist_ok=True)
        (d / "sweep.txt").write_text("".join(f"{d / f'q{q:.1f}.png'} q={q}\n" for q in steps))
        cmd = [str(LAB), str(d / "ref.png"), f"w={args.w}", f"h={args.h}", "gpu=0", "gputime=5", "q=1",
               f"sweep={d / 'sweep.txt'}"] + SCENES[scene].split()
        flags = 0x00004000 if sys.platform == "win32" else 0  # BELOW_NORMAL_PRIORITY_CLASS
        run = subprocess.run(cmd, cwd=NATIVE, capture_output=True, text=True, creationflags=flags)
        if run.returncode != 0:
            print(run.stdout[-2000:])
            return 1
        ms = {}
        for line in run.stdout.splitlines():
            if line.startswith("pack_lab: sweep ") and line.endswith(" ms"):
                parts = line.split()
                ms[pathlib.Path(parts[2]).stem] = float(parts[3])
        ref = np.asarray(Image.open(d / "ref.png").convert("RGB"))
        losses = [loss(ref, np.asarray(Image.open(d / f"q{q:.1f}.png").convert("RGB"))) for q in steps]
        jumps = [abs(losses[i + 1] - losses[i]) for i in range(10)]
        worst = max(worst, max(jumps) / max(losses[0], 1e-6))
        print(f"{scene:8s} loss " + " ".join(f"{v:5.2f}" for v in losses))
        print(f"{'':8s} ms   " + " ".join(f"{ms.get(f'q{q:.1f}', -1):5.2f}" for q in steps))
        rows.append([Image.open(d / f"q{q:.1f}.png").convert("RGB").resize((args.w // 4, args.h // 4)) for q in steps[::2]])
    if rows:
        w, h = rows[0][0].size
        sheet = Image.new("RGB", (w * len(rows[0]), h * len(rows)))
        for r, row in enumerate(rows):
            for c, im in enumerate(row):
                sheet.paste(im, (c * w, r * h))
        sheet.save(out / "smooth.png")
        print(f"largest single tenth-step change: {worst:.0%} of the all-off loss; sheet {out / 'smooth.png'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
