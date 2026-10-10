#!/usr/bin/env python3
"""
Procedural (100 % original, numpy-synthesised) sound effects for Skycraft.

Renders every catalogued file of the categories "creature", "shout", "magic", "ui" and "world"
(see tools/music/sound_catalog.py) to mod/src/main/resources/assets/skycraft/sounds/<stem>.ogg.
Nothing here reads, samples or copies any existing recording: every sound is built from oscillators,
noise, filters and resonators. Each file has its own numpy Generator seeded from crc32(stem), so a
re-run reproduces the same audio.

Toolkit (top of the file):
  * filters ........ Butterworth lp/hp/bp, time-varying RBJ biquads (tvf), modal resonator banks
  * voice .......... source-filter model: Rosenberg glottal pulses with jitter / shimmer / period-doubling
                     sub-harmonics / throat rattle, pulsed aspiration noise, a 5-formant parallel vocal tract
  * speech ......... a tiny Dovahzul "phonemiser" (plosive bursts, fricatives, trills, nasals, vowels)
                     used for the Thu'um chants
  * textures ....... noise sweeps, granular crackle, electric arcs, debris / tinkle scatter, booms, bells
  * space .......... short convolution reverb (exponentially decaying noise IR), discrete echoes
  * finishing ...... DC block, silence trim, fades, per-category loudness (audiolib.loudness_normalise)

Usage:
    py -3 tools/music/synth_skyrim_sfx.py                  # render everything
    py -3 tools/music/synth_skyrim_sfx.py dragon shout     # only stems containing these substrings
    py -3 tools/music/synth_skyrim_sfx.py --wav out/sfx    # also write 16-bit WAVs for auditioning
    py -3 tools/music/synth_skyrim_sfx.py --list           # stems, generators and loudness targets
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
import time
import wave
import zlib
from concurrent.futures import ProcessPoolExecutor

import numpy as np
from scipy import signal as sps

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import audiolib  # noqa: E402
import sound_catalog as catalog  # noqa: E402

SR = 32000
TAU = 2.0 * np.pi
CATEGORIES = ("creature", "shout", "magic", "ui", "world")
# Files written by earlier versions of this script that the catalogue no longer references.
OBSOLETE = ["shout/fus_ro_dah", "shout/wuld_nah_kest", "shout/yol_toor_shul"]


# =====================================================================================================  basics

def ns(d: float) -> int:
    """Seconds -> samples (at least 1)."""
    return max(1, int(round(d * SR)))


def tline(n: int) -> np.ndarray:
    return np.arange(n) / SR


def rms(x) -> float:
    return float(np.sqrt(np.mean(np.square(x)) + 1e-20))


def unit(x: np.ndarray) -> np.ndarray:
    """Scale to unit peak (silence stays silence)."""
    m = float(np.max(np.abs(x))) if len(x) else 0.0
    return x / m if m > 1e-12 else x


def pad(x: np.ndarray, n: int) -> np.ndarray:
    return x[:n] if len(x) >= n else np.concatenate([x, np.zeros(n - len(x))])


def place(dst: np.ndarray, src: np.ndarray, t0: float, g: float = 1.0) -> np.ndarray:
    """Add src into dst starting at t0 seconds (clipped to dst's length)."""
    i = int(round(t0 * SR))
    if i >= len(dst) or i < 0:
        return dst
    m = min(len(src), len(dst) - i)
    dst[i:i + m] += g * src[:m]
    return dst


def smooth(x: np.ndarray, w: int) -> np.ndarray:
    """Centred moving average of width w samples (cumsum, edge-held)."""
    w = int(w)
    if w <= 1 or len(x) < 2:
        return x
    h = w // 2
    xp = np.concatenate([np.full(h, x[0]), x, np.full(w - h, x[-1])])
    c = np.concatenate([[0.0], np.cumsum(xp)])
    return (c[w:w + len(x)] - c[:len(x)]) / w


def curve(n: int, points, log: bool = False) -> np.ndarray:
    """Piecewise curve over n samples; points = [(fraction 0..1, value)]. log=True interpolates log-values."""
    t = np.linspace(0.0, 1.0, n)
    ts = [p[0] for p in points]
    vs = np.array([p[1] for p in points], dtype=np.float64)
    if log:
        return np.exp(np.interp(t, ts, np.log(vs)))
    return np.interp(t, ts, vs)


def env_ad(n: int, att: float, tau: float, hold: float = 0.0) -> np.ndarray:
    """Raised-cosine attack, optional hold, exponential decay (tau seconds)."""
    t = tline(n)
    a = np.clip(t / max(att, 1e-4), 0.0, 1.0)
    a = 0.5 - 0.5 * np.cos(np.pi * a)
    return a * np.exp(-np.maximum(0.0, t - att - hold) / max(tau, 1e-4))


def fades(x: np.ndarray, fi: float = 0.003, fo: float = 0.04) -> np.ndarray:
    x = x.copy()
    a, b = min(ns(fi), len(x) // 2), min(ns(fo), len(x) // 2)
    if a > 1:
        x[:a] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, a))
    if b > 1:
        x[-b:] *= 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, b))
    return x


def wander(rng, n: int, rate: float) -> np.ndarray:
    """Smooth random motion (~unit std) changing about `rate` times per second."""
    step = max(1.0, SR / rate)
    k = int(n / step) + 3
    v = rng.standard_normal(k)
    x = np.interp(np.arange(n) / step, np.arange(k), v)
    return smooth(x, int(step)) * 1.5


def sat(x: np.ndarray, drive: float = 2.0) -> np.ndarray:
    """tanh saturation of a unit-peak signal (keeps unit peak)."""
    return np.tanh(drive * unit(x)) / np.tanh(drive)


# =====================================================================================================  noise

def white(rng, n):
    return rng.standard_normal(n)


def pink(rng, n):
    X = np.fft.rfft(rng.standard_normal(n))
    f = np.arange(len(X), dtype=np.float64)
    f[0] = 1.0
    y = np.fft.irfft(X / np.sqrt(f), n)
    return y / rms(y)


def brown(rng, n):
    X = np.fft.rfft(rng.standard_normal(n))
    f = np.arange(len(X), dtype=np.float64) * SR / max(n, 1)
    X = X / np.maximum(f, 15.0)
    X[0] = 0
    y = np.fft.irfft(X, n)
    return y / rms(y)


# =====================================================================================================  filters

def _fc(f):
    return float(np.clip(f, 10.0, 0.45 * SR))


def lp(x, f, order=2):
    return sps.sosfilt(sps.butter(order, _fc(f), "low", fs=SR, output="sos"), x)


def hp(x, f, order=2):
    return sps.sosfilt(sps.butter(order, _fc(f), "high", fs=SR, output="sos"), x)


def bp(x, lo, hi, order=2):
    lo, hi = _fc(lo), _fc(hi)
    return sps.sosfilt(sps.butter(order, [lo, max(hi, lo * 1.05)], "band", fs=SR, output="sos"), x)


def _biquads(kind: str, f: np.ndarray, q: np.ndarray):
    """RBJ cookbook biquads (vectorised over coefficient sets). bp = constant 0 dB peak gain."""
    w = TAU * np.clip(f, 5.0, 0.45 * SR) / SR
    cw, al = np.cos(w), np.sin(w) / (2.0 * np.maximum(q, 0.05))
    if kind == "bp":
        b = np.stack([al, np.zeros_like(al), -al])
    elif kind == "lp":
        b = np.stack([(1 - cw) / 2, 1 - cw, (1 - cw) / 2])
    elif kind == "hp":
        b = np.stack([(1 + cw) / 2, -(1 + cw), (1 + cw) / 2])
    else:
        raise ValueError(kind)
    a = np.stack([1 + al, -2 * cw, 1 - al])
    return b / a[0], a / a[0]


def tvf(x, f, q=0.707, kind="lp", block=64) -> np.ndarray:
    """Time-varying biquad: f and q may be scalars or per-sample arrays (coefficients update every block)."""
    x = np.asarray(x, dtype=np.float64)
    n = len(x)
    f, q = np.asarray(f, dtype=np.float64), np.asarray(q, dtype=np.float64)
    if f.ndim == 0 and q.ndim == 0:
        b, a = _biquads(kind, f[None], q[None])
        return sps.lfilter(b[:, 0], a[:, 0], x)
    f, q = np.broadcast_to(f, (n,)), np.broadcast_to(q, (n,))
    idx = np.arange(0, n, block)
    b, a = _biquads(kind, f[idx], q[idx])
    y = np.empty(n)
    zi = np.zeros(2)
    for j, s in enumerate(idx):
        y[s:s + block], zi = sps.lfilter(b[:, j], a[:, j], x[s:s + block], zi=zi)
    return y


def modal(exc, freqs, decays, amps) -> np.ndarray:
    """Bank of two-pole resonators (impulse response = unit-amplitude damped sine per mode)."""
    y = np.zeros(len(exc))
    for f, d, g in zip(freqs, decays, amps):
        if f >= 0.45 * SR or f <= 0:
            continue
        r = np.exp(-1.0 / (max(d, 1e-4) * SR))
        w = TAU * f / SR
        y += g * sps.lfilter([np.sin(w)], [1.0, -2 * r * np.cos(w), r * r], exc)
    return y


# =====================================================================================================  oscillators & hits

def osc(f, harm=(1.0,), phase=0.0) -> np.ndarray:
    """Additive oscillator following frequency curve f (array); harmonics above Nyquist are dropped."""
    f = np.asarray(f, dtype=np.float64)
    ph = TAU * np.cumsum(f) / SR + phase
    top = float(np.max(f))
    y = np.zeros(len(f))
    for k, a in enumerate(harm, start=1):
        if a and k * top < 0.45 * SR:
            y += a * np.sin(k * ph)
    return y


def ping(f, decay, n=None, att=0.0015, phase=0.0) -> np.ndarray:
    """Damped sine with a tiny attack."""
    n = n or ns(min(decay * 7, 4.0))
    t = tline(n)
    return np.sin(TAU * f * t + phase) * np.exp(-t / decay) * np.minimum(1.0, t / att)


def boom(n, f0, f1, tau_f, tau_a, att=0.003) -> np.ndarray:
    """Pitch-dropping sine thump (f0 -> f1)."""
    t = tline(n)
    f = f1 + (f0 - f1) * np.exp(-t / tau_f)
    return np.sin(TAU * np.cumsum(f) / SR) * np.exp(-t / tau_a) * np.minimum(1.0, t / att)


def burst(rng, n, lo, hi, att, tau, hold=0.0) -> np.ndarray:
    return bp(white(rng, n), lo, hi) * env_ad(n, att, tau, hold)


def strike(rng, n, dur=0.002, lo=200, hi=9000):
    """Short noise excitation for modal objects."""
    e = np.zeros(n)
    m = min(n, ns(dur))
    e[:m] = rng.standard_normal(m) * np.hanning(m + 2)[1:-1]
    return bp(e, lo, hi, 1) if hi < 0.45 * SR else hp(e, lo, 1)


METAL = [1.0, 1.594, 2.136, 2.296, 2.653, 3.156, 3.501, 4.06, 5.40]   # thick plate / mechanism
GLASS = [1.0, 2.32, 4.25, 6.63, 9.38]                                    # bar-like, glassy
WOOD = [1.0, 2.31, 3.89, 5.61]
STONE = [1.0, 1.73, 2.61, 3.52, 4.7]
BELL = [(0.5, 0.55, 1.0), (1.0, 1.0, 0.75), (1.19, 0.45, 0.55), (1.5, 0.4, 0.45), (2.0, 0.5, 0.35),
        (2.51, 0.22, 0.25), (2.98, 0.15, 0.2), (4.07, 0.08, 0.12)]       # (ratio, amp, decay factor)


def modal_hit(rng, f, dur, decay, ratios=METAL, bright=0.8, exc_dur=0.002, spread=0.012) -> np.ndarray:
    n = ns(dur)
    r = np.array(ratios) * (1 + rng.uniform(-spread, spread, len(ratios)))
    r[0] = 1.0
    freqs = f * r
    decays = decay * r ** -0.7
    amps = bright ** np.arange(len(r)) * rng.uniform(0.5, 1.0, len(r))
    return modal(strike(rng, n, exc_dur), freqs, decays, amps)


def bell(rng, f, dur, decay=1.2, bright=1.0, warble=0.5) -> np.ndarray:
    """Church-bell-like partials, each a slightly detuned pair (slow beating)."""
    n = ns(dur)
    y = np.zeros(n)
    for r, a, d in BELL:
        ff = f * r
        if ff > 0.42 * SR:
            continue
        g = a * (bright ** max(0.0, np.log2(r)))
        y += g * ping(ff, decay * d, n, phase=rng.uniform(0, TAU))
        y += g * warble * ping(ff * (1 + rng.uniform(0.0008, 0.003)), decay * d, n, phase=rng.uniform(0, TAU))
    y += 0.08 * hp(white(rng, n), 2500) * np.exp(-tline(n) / 0.004)
    return y


def drum(rng, n, f=70.0, decay=0.35, skin=0.25) -> np.ndarray:
    t = tline(n)
    body = boom(n, f * 1.8, f, 0.03, decay)
    return body + skin * bp(white(rng, n), 120, 1500) * np.exp(-t / 0.03)


# =====================================================================================================  textures

def crackle(rng, n, rate, lo=1500.0, hi=8000.0, sharp=0.0006, env=None, alpha=1.6) -> np.ndarray:
    """Granular crackle: Poisson clicks with heavy-tailed (Pareto) amplitudes, band-limited."""
    count = int(rng.poisson(rate * n / SR))
    imp = np.zeros(n)
    if count:
        pos = rng.integers(0, n, count)
        amp = (rng.pareto(alpha, count) + 0.2) * rng.choice([-1.0, 1.0], count)
        if env is not None:
            amp *= env[pos]
        np.add.at(imp, pos, amp)
    k = ns(sharp * 6)
    kern = rng.standard_normal(k) * np.exp(-tline(k) / sharp)
    return bp(sps.fftconvolve(imp, kern)[:n], lo, hi)


def scatter(rng, n, count, t0, spread, lo, hi, dec=(0.03, 0.15), ratios=(1.0,), amp_tau=0.5, glass=True):
    """Scatter of small resonant pings (ice tinkles, debris clicks): exponential timing, decaying amplitude."""
    y = np.zeros(n)
    for _ in range(count):
        t = t0 + rng.exponential(spread)
        f = np.exp(rng.uniform(np.log(lo), np.log(hi)))
        d = rng.uniform(*dec)
        c = np.zeros(ns(d * 6))
        for j, r in enumerate(ratios):
            if f * r < 0.44 * SR:
                c += ping(f * r, d / (1 + j), len(c), phase=rng.uniform(0, TAU)) / (1 + j)
        if not glass:
            c *= 0.5 + 0.5 * rng.standard_normal(len(c))
        place(y, c, t, rng.uniform(0.3, 1.0) * np.exp(-(t - t0) / amp_tau))
    return y


def arc(rng, n, density=2500.0, buzz_hz=110.0, buzz=0.5) -> np.ndarray:
    """Electric arc: random-walk crackle + heavy-tailed spikes, chopped by a mains-like buzz."""
    steps = rng.standard_normal(n) * (rng.random(n) < 0.2)
    walk = sps.lfilter([1.0], [1.0, -0.96], steps)
    spikes = np.zeros(n)
    c = int(rng.poisson(density * n / SR))
    if c:
        np.add.at(spikes, rng.integers(0, n, c), (rng.pareto(1.4, c) + 0.3) * rng.choice([-1.0, 1.0], c))
    y = hp(unit(walk) + 0.7 * unit(spikes), 600)
    f = buzz_hz * (1 + 0.08 * wander(rng, n, 6))
    chop = 1 - buzz * (0.5 + 0.5 * np.sign(np.sin(TAU * np.cumsum(f) / SR)))
    flick = np.clip(1 + 0.6 * wander(rng, n, 25), 0.1, None)
    return sat(y * chop * flick, 1.8)


def whoosh(rng, n, f_points, q=0.9, env=None, src=None) -> np.ndarray:
    """Band-pass swept noise. f_points = [(fraction, Hz)] (log interpolated)."""
    x = pink(rng, n) if src is None else src
    y = tvf(x, curve(n, f_points, log=True), q, "bp")
    return y * (env if env is not None else 1.0)


def flame(rng, n, cutoff=1800.0, turb=0.35, crk=60.0) -> np.ndarray:
    """Roaring flame: pink/brown noise with a wandering low-pass, turbulent AM and crackle."""
    fc = cutoff * np.exp(0.35 * wander(rng, n, 5))
    body = tvf(0.7 * pink(rng, n) + 0.5 * brown(rng, n), fc, 0.6, "lp")
    am = np.clip(1 + turb * wander(rng, n, 14), 0.2, None)
    rumble = lp(brown(rng, n), 220) * 0.6
    return unit(body * am) + 0.6 * unit(rumble) + 0.35 * unit(crackle(rng, n, crk, 1500, 7000))


def debris(rng, n, t0, count=30, spread=0.25, amp_tau=0.5):
    """Small stones and grit: noisy stone clicks after an impact."""
    return scatter(rng, n, count, t0, spread, 700, 4500, (0.004, 0.02), STONE[:3], amp_tau, glass=False)


# =====================================================================================================  space

def reverb(rng, x, rt60=1.2, mix=0.25, pre=0.012, damp=3500.0) -> np.ndarray:
    """Convolution with an exponentially decaying noise IR (highs decay faster). Output is longer by the IR."""
    x = fades(np.asarray(x, dtype=np.float64), 0.0, 0.03)   # a dry signal cut mid-decay would click
    n_ir = ns(rt60)
    t = tline(n_ir)
    w = rng.standard_normal(n_ir)
    lo = lp(w, damp, 1)
    ir = lo * np.exp(-6.91 * t / rt60) + (w - lo) * np.exp(-6.91 * t / (rt60 * 0.35))
    ir *= np.minimum(1.0, t / 0.005)
    ir = np.concatenate([np.zeros(ns(pre)), ir])
    ir /= np.sqrt(np.sum(ir ** 2))
    y = np.concatenate([x, np.zeros(len(ir))])
    return y + mix * pad(sps.fftconvolve(x, ir), len(y))


def echo(x, taps, damp=2500.0) -> np.ndarray:
    """Discrete (mountain) echoes: taps = [(delay s, gain)]."""
    n = len(x) + ns(max(d for d, _ in taps))
    y = pad(x, n)
    soft = lp(x, damp)
    for d, g in taps:
        place(y, soft, d, g)
    return y


# =====================================================================================================  voice

VOWELS = {  # F1..F5 (Hz) of an adult male tract
    "a": (720, 1150, 2450, 3350, 3850), "aa": (760, 1100, 2400, 3300, 3850), "ah": (700, 1080, 2450, 3300, 3850),
    "o": (480, 830, 2400, 3300, 3800), "u": (330, 760, 2250, 3200, 3750), "uh": (600, 1200, 2450, 3300, 3800),
    "e": (520, 1700, 2450, 3350, 3850), "ae": (680, 1650, 2450, 3300, 3850), "i": (320, 1950, 2600, 3400, 3900),
    "ii": (270, 2200, 2900, 3450, 3950), "er": (480, 1350, 1650, 3200, 3800),
    # consonantal loci
    "r": (440, 1200, 1600, 3200, 3700), "l": (360, 1050, 2700, 3300, 3800), "w": (300, 650, 2200, 3200, 3700),
    "y": (260, 2200, 3000, 3500, 3950), "m": (260, 1000, 2200, 3200, 3700), "n": (260, 1500, 2500, 3300, 3800),
    "v": (300, 1300, 2300, 3300, 3800), "z": (300, 1500, 2500, 3300, 3800),
}
FBW = np.array([90.0, 110.0, 150.0, 220.0, 300.0])


def vowel(v):
    return np.array(VOWELS[v] if isinstance(v, str) else v, dtype=np.float64)


def tract(n, path, scale=1.0) -> np.ndarray:
    """(5, n) formant tracks. path: vowel name, or [(fraction, vowel)] morphed in log-frequency."""
    pts = [(0.0, path)] if isinstance(path, str) else list(path)
    if len(pts) == 1:
        return np.repeat(vowel(pts[0][1])[:, None] * scale, n, axis=1)
    t = np.linspace(0, 1, n)
    ts = [p[0] for p in pts]
    logs = np.log(np.stack([vowel(p[1]) for p in pts]))
    return np.stack([np.exp(np.interp(t, ts, logs[:, k])) for k in range(5)]) * scale


def glottal(rng, f0, oq=0.6, sk=0.75, jitter=0.008, shimmer=0.06, sub=0.0, sub3=0.0, flutter=0.0):
    """Rosenberg glottal flow g and its derivative (the radiated source). Per-period shimmer, jittered f0,
    period-doubling (sub) / tripling (sub3) for roughness, slow flutter for growls. Returns (dg, g)."""
    f0 = np.asarray(f0, dtype=np.float64)
    n = len(f0)
    f = f0 * (1 + jitter * wander(rng, n, 70) + flutter * wander(rng, n, 9))
    f = np.maximum(f, 8.0)
    ph = np.cumsum(f) / SR
    k = ph.astype(np.int64)
    fr = ph - k
    tp = oq * sk
    tn = max(oq - tp, 0.02)
    g = np.zeros(n)
    m1 = fr < tp
    g[m1] = 0.5 * (1 - np.cos(np.pi * fr[m1] / tp))
    m2 = (~m1) & (fr < tp + tn)
    g[m2] = np.cos(0.5 * np.pi * (fr[m2] - tp) / tn)
    idx = np.arange(int(k[-1]) + 2)
    amps = np.clip(1 + shimmer * rng.standard_normal(len(idx)), 0.1, None)
    if sub:
        amps *= 1 - sub * (idx % 2)
    if sub3:
        amps *= 1 - sub3 * ((idx % 3) != 0)
    g *= amps[k]
    dg = np.diff(g, prepend=0.0) / (np.pi / (2 * tn) * f / SR)
    return dg, g


def resonate(x, f, bw, block=128) -> np.ndarray:
    """Time-varying all-pole resonator with unity DC gain (one Klatt cascade section)."""
    x = np.asarray(x, dtype=np.float64)
    n = len(x)
    f = np.broadcast_to(np.asarray(f, dtype=np.float64), (n,))
    bw = np.broadcast_to(np.asarray(bw, dtype=np.float64), (n,))
    idx = np.arange(0, n, block)
    r = np.exp(-np.pi * np.maximum(bw[idx], 20.0) / SR)
    c1 = 2 * r * np.cos(TAU * np.clip(f[idx], 20.0, 0.45 * SR) / SR)
    c2 = r * r
    g = 1 - c1 + c2
    y = np.empty(n)
    zi = np.zeros(2)
    for j, s in enumerate(idx):
        y[s:s + block], zi = sps.lfilter([g[j]], [1.0, -c1[j], c2[j]], x[s:s + block], zi=zi)
    return y


def formant_bank(x, F, bw=1.0, tilt=0.0, block=128) -> np.ndarray:
    """Cascade vocal tract: one resonator per formant track F (k, n), bandwidths FBW*bw. The cascade keeps
    the fundamental (unity DC gain) and gives natural relative formant levels. tilt > 0 brightens
    (pressed / brassy), tilt < 0 darkens (soft / pure)."""
    y = np.asarray(x, dtype=np.float64)
    for k in range(F.shape[0]):
        y = resonate(y, F[k], FBW[k] * bw, block)
    # an all-pole cascade falls far too steeply above F3; restore F3/F4 presence and the source's own
    # >4 kHz energy (real voices sit ~-40 dB up there, the bare cascade at ~-90 dB)
    y = y + 2.5 * hp(y, 1800) + 0.4 * hp(x, 4000)
    if tilt > 0:
        y = y + tilt * hp(y, 1500, 1)
    elif tilt < 0:
        y = (1 + tilt) * y + (-tilt) * lp(y, 1200, 1)
    return y


def voice(rng, f0, F, amp=1.0, *, breath=0.1, bw=1.0, tilt=0.0, rattle=0.0, rattle_hz=28.0, src_lp=None,
          **glot) -> np.ndarray:
    """Source-filter voice. breath = aspiration RMS relative to the glottal source (scalar or array)."""
    n = len(f0)
    src, flow = glottal(rng, f0, **glot)
    x = src + np.asarray(breath) * rms(src) * white(rng, n) * (0.35 + 0.65 * flow)
    if rattle:
        rh = rattle_hz * (1 + 0.25 * wander(rng, n, 4))
        m = 0.5 + 0.5 * np.sin(TAU * np.cumsum(rh) / SR)
        x = x * (1 - rattle * m ** 2)
    if src_lp:
        x = lp(x, src_lp)
    return formant_bank(x * amp, F, bw, tilt)


def beast(rng, dur, f0_pts, path, scale=1.0, amp_pts=None, **kw) -> np.ndarray:
    """Convenience: voice with an f0 contour [(fraction, Hz)], vowel path and amplitude contour."""
    n = ns(dur)
    f0 = curve(n, f0_pts, log=True) if isinstance(f0_pts, list) else np.full(n, float(f0_pts))
    amp = curve(n, amp_pts or [(0, 0), (0.08, 1), (0.6, 0.8), (1, 0)])
    bw = kw.pop("bw", 1.0) * np.sqrt(max(scale, 0.4))
    return voice(rng, f0, tract(n, path, scale), amp, bw=bw, **kw)


def choir(rng, n, notes, paths, scale=0.95, per_note=2, amp=None, breath=0.3, vib=0.006, spread=0.006):
    """Several soft voices per note (f0 scalars or arrays), each with its own vowel path / vibrato / jitter."""
    t = tline(n)
    y = np.zeros(n)
    amp = np.ones(n) if amp is None else amp
    for i, note in enumerate(notes):
        for j in range(per_note):
            f0 = np.broadcast_to(np.asarray(note, dtype=np.float64), (n,)) * (1 + rng.uniform(-spread, spread))
            rate = rng.uniform(4.2, 5.6)
            f0 = f0 * (1 + vib * np.sin(TAU * rate * t + rng.uniform(0, TAU)) + 0.003 * wander(rng, n, 2))
            path = paths[(i + j) % len(paths)]
            a = amp * np.clip(1 + 0.25 * wander(rng, n, 0.7), 0.3, None)
            y += voice(rng, f0, tract(n, path, scale * rng.uniform(0.96, 1.04)), a, breath=breath,
                       oq=0.7, sk=0.7, jitter=0.004, shimmer=0.03, src_lp=6000)
    return y


# =====================================================================================================  speech (Thu'um)

VOWEL_TOKENS = {"A": "a", "AA": "aa", "AH": "ah", "O": "o", "OO": "u", "U": "u", "E": "e", "EI": ("e", "ii"),
                "I": "i", "II": "ii", "AU": ("a", "u")}
PLOSIVES = {"D": (2500, 6500, True), "T": (2800, 7500, False), "K": (1300, 3800, False), "Q": (1300, 3800, False),
            "C": (1300, 3800, False), "G": (1200, 3400, True), "B": (400, 2200, True), "P": (500, 2600, False)}
FRICATIVES = {"F": (1400, 7500, 0.22, False, 0.10, None), "V": (1200, 6000, 0.12, True, 0.08, "v"),
              "S": (4200, 10500, 0.55, False, 0.12, None), "SH": (2000, 6500, 0.5, False, 0.12, None),
              "Z": (3800, 9500, 0.30, True, 0.09, "z"), "X": (3800, 9500, 0.4, False, 0.10, None)}
SONORANTS = {"R": ("r", 0.08, 0.75, 1.0), "L": ("l", 0.07, 0.75, 0.0), "W": ("w", 0.08, 0.7, 0.0),
             "Y": ("y", 0.07, 0.7, 0.0), "M": ("m", 0.08, 0.45, 0.0), "N": ("n", 0.08, 0.45, 0.0)}


def tokenize(word: str) -> list:
    w, out, i = word.upper(), [], 0
    while i < len(w):
        for m in ("SH", "AA", "AH", "OO", "II", "EI", "AU"):
            if w.startswith(m, i):
                out.append(m)
                i += 2
                break
        else:
            if w[i].isalpha():
                out.append(w[i])
            i += 1
    return out


def _seg(d, vamp, F, asp=0.0, trill=0.0, fric=None):
    return dict(d=d, vamp=vamp, F=np.asarray(F, dtype=np.float64), asp=asp, trill=trill, fric=fric)


def word_segments(tokens, vdur) -> list:
    """Phone segments of one (monosyllabic) word: voicing, formant targets, aspiration, trill and noise events."""
    vts = [VOWEL_TOKENS[t] for t in tokens if t in VOWEL_TOKENS] or ["uh"]
    first = vowel(vts[0][0] if isinstance(vts[0], tuple) else vts[0])
    last = vowel(vts[-1][-1] if isinstance(vts[-1], tuple) else vts[-1])
    segs, seen = [], False
    for i, tok in enumerate(tokens):
        initial, final = i == 0, i == len(tokens) - 1
        nb = last if seen else first
        if tok in VOWEL_TOKENS:
            seen = True
            v = VOWEL_TOKENS[tok]
            d = vdur / len(vts)
            if isinstance(v, tuple):
                segs += [_seg(d * 0.55, 1.0, vowel(v[0]), 0.03), _seg(d * 0.45, 1.0, vowel(v[1]), 0.03)]
            else:
                segs.append(_seg(d, 1.0, vowel(v), 0.03))
        elif tok in PLOSIVES:
            lo, hi, voiced = PLOSIVES[tok]
            if not initial:
                segs.append(_seg(0.035, 0.18 if voiced else 0.0, nb))
            segs.append(_seg(0.015, 0.25 if voiced else 0.0, nb, fric=(lo, hi, 0.6 if voiced else 0.9, 0.002)))
            if voiced:
                segs.append(_seg(0.014, 0.55, nb))
            else:
                segs.append(_seg(0.05 if not final else 0.04, 0.0, nb, asp=0.7))
        elif tok == "J":
            if not initial:
                segs.append(_seg(0.03, 0.18, nb))
            segs.append(_seg(0.012, 0.25, vowel("y"), fric=(2500, 6500, 0.5, 0.002)))
            segs.append(_seg(0.07, 0.45, vowel("y"), fric=(1800, 6000, 0.45, 0.012)))
        elif tok in FRICATIVES:
            lo, hi, lev, voiced, d, locus = FRICATIVES[tok]
            segs.append(_seg(d * (1.25 if final else 1.0), 0.4 if voiced else 0.0, vowel(locus) if locus else nb,
                             fric=(lo, hi, lev, 0.015)))
        elif tok == "H":
            segs.append(_seg(0.08 if not final else 0.14, 0.0, nb, asp=0.9 if not final else 0.55))
        elif tok in SONORANTS:
            loc, d, va, tr = SONORANTS[tok]
            segs.append(_seg(d * (1.5 if final and tok in "MN" else 1.0), va, vowel(loc), trill=tr))
    return segs


def thuum_words(rng, words, base_f0):
    """Render spoken Dovahzul words with layered voices. Returns (signal, time the last vowel ends)."""
    segs, word_spans = [], []
    t = 0.0
    for wi, word in enumerate(words):
        last = wi == len(words) - 1
        vdur = (0.40 if last else 0.27) * rng.uniform(0.95, 1.08)
        ws = word_segments(tokenize(word), vdur)
        d = sum(s["d"] for s in ws)
        word_spans.append((t, t + d, last))
        segs += ws
        t += d
        if not last:
            segs.append(_seg(0.12, 0.0, ws[-1]["F"]))
            t += 0.12
    n = sum(ns(s["d"]) for s in segs) + ns(0.05)
    vamp, asp, trill = np.zeros(n), np.zeros(n), np.zeros(n)
    F = np.repeat(segs[0]["F"][:, None], n, axis=1)
    fric = np.zeros(n)
    pos = 0
    last_voiced_end = 0
    for s in segs:
        m = ns(s["d"])
        vamp[pos:pos + m], asp[pos:pos + m], trill[pos:pos + m] = s["vamp"], s["asp"], s["trill"]
        F[:, pos:pos + m] = s["F"][:, None]
        if s["vamp"] >= 0.99:
            last_voiced_end = pos + m
        if s["fric"]:
            lo, hi, lev, att = s["fric"]
            e = env_ad(m + ns(0.01), att, s["d"] * 0.6 + 0.005, hold=s["d"] * 0.4)
            place(fric, bp(white(rng, len(e)), lo, hi) * e * lev, pos / SR)
        pos += m
    F[:, pos:] = F[:, pos - 1:pos]
    vamp = smooth(vamp, ns(0.012))
    asp = smooth(asp, ns(0.008))
    trill = smooth(trill, ns(0.01))
    F = np.exp(np.stack([smooth(np.log(F[k]), ns(0.03)) for k in range(5)]))
    # f0: each word rises then falls; the last word is pushed harder and drops into the blast
    pts_t, pts_f = [], []
    for (a, b, last) in word_spans:
        pk = 1.22 if last else 1.12
        pts_t += [a, a + 0.3 * (b - a), b]
        pts_f += [base_f0 * 1.02, base_f0 * pk * rng.uniform(0.97, 1.03), base_f0 * (0.84 if last else 0.95)]
    f0 = np.exp(np.interp(tline(n), pts_t, np.log(pts_f)))
    # trill (R): ~26 Hz tongue flaps
    vt = vamp * (1 - trill * (0.5 + 0.5 * np.cos(TAU * 26 * tline(n))))
    # layered Thu'um voices: main, octave-down sub voice, three detuned doubles
    src = np.zeros(n)
    layers = [(1.0, 1.0, 0.0, 0.12), (0.5, 0.6, 0.0, 0.3)]
    for d in rng.uniform(0.006, 0.016, 3) * np.array([-1, 1, 1]):
        layers.append((1 + d, 0.42, rng.uniform(0.008, 0.03), 0.1))
    for ratio, g, delay, sub in layers:
        s, _ = glottal(rng, f0 * ratio * (1 + 0.004 * wander(rng, n, 5)), oq=0.5, sk=0.8, jitter=0.012,
                       shimmer=0.08, sub=sub, flutter=0.01)
        k = ns(delay) if delay else 0
        src += g * (np.concatenate([np.zeros(k), s[:n - k]]) if k else s)
    src_r = rms(src[vamp > 0.5]) if np.any(vamp > 0.5) else rms(src)
    noise = white(rng, n) * src_r
    x = src * vt + noise * (2.0 * asp + 0.12 * vt)
    y = formant_bank(x, F, bw=1.05, tilt=0.8)
    y = y + 0.35 * lp(src * vt, 220) * rms(y) / max(rms(lp(src * vt, 220)), 1e-9)   # chest weight
    y = sat(y, 2.2)
    yr = rms(y[vamp > 0.5]) if np.any(vamp > 0.5) else rms(y)
    y = y + fric * yr * 2.2
    return y, last_voiced_end / SR


SHOUT_FLAVOUR = {
    "unrelenting_force": "force", "fire_breath": "fire", "frost_breath": "frost", "whirlwind_sprint": "wind",
    "become_ethereal": "shimmer", "clear_skies": "wind", "aura_whisper": "shimmer", "slow_time": "time",
    "marked_for_death": "dark", "disarm": "metal", "elemental_fury": "wind", "kynes_peace": "shimmer",
    "animal_allegiance": "wind", "storm_call": "storm", "dragonrend": "dark",
}


def flavour(rng, kind, n) -> np.ndarray:
    """Elemental colour layered onto a shout's blast."""
    t = tline(n)
    if kind == "fire":
        return flame(rng, n, 2200, 0.4, 90) * env_ad(n, 0.05, 0.45, 0.25)
    if kind == "frost":
        return (unit(hp(white(rng, n), 2500)) * env_ad(n, 0.03, 0.4, 0.2)
                + 0.8 * unit(scatter(rng, n, 40, 0.05, 0.35, 2500, 8000, (0.03, 0.15), GLASS[:2])))
    if kind == "storm":
        r = lp(brown(rng, n), 180) * env_ad(n, 0.25, 0.7)
        return unit(r) + 0.5 * unit(arc(rng, n) * env_ad(n, 0.002, 0.08))
    if kind == "wind":
        return whoosh(rng, n, [(0, 300), (0.25, 2200), (1, 400)], 1.2, env_ad(n, 0.12, 0.5))
    if kind == "shimmer":
        return unit(scatter(rng, n, 25, 0.0, 0.4, 1500, 5000, (0.2, 0.6), GLASS[:2], 1.0)) * 0.8
    if kind == "time":
        f = curve(n, [(0, 220), (1, 70)], log=True)
        return osc(f, (1, 0.5, 0.3, 0.2)) * env_ad(n, 0.1, 0.6) * (1 + 0.3 * np.sin(TAU * curve(n, [(0, 9), (1, 2)]) * t))
    if kind == "dark":
        return (osc(np.full(n, 41.0), (1, 0.6, 0.3)) + osc(np.full(n, 43.1), (1, 0.4))) * env_ad(n, 0.1, 0.8)
    if kind == "metal":
        return modal_hit(rng, 420, n / SR, 0.5, METAL, 0.9)
    # "force": an extra heavy air blast
    return tvf(white(rng, n), curve(n, [(0, 3000), (1, 150)], log=True), 0.7, "lp") * env_ad(n, 0.004, 0.35)


def shout(rng, shout_id: str, k: int) -> np.ndarray:
    words = catalog.SHOUTS[shout_id][:k] if shout_id != "generic" else ["HAAH"]
    base = rng.uniform(92.0, 104.0)
    y, t_end = thuum_words(rng, words, base)
    total = len(y) + ns(1.0 + 0.4 * k)
    out = pad(y, total)
    m = total - ns(t_end) + ns(0.1)
    # concussive impact: low boom + air blast, scaled by word count
    t0 = max(0.0, t_end - 0.07)
    bm = boom(m, 110, 42, 0.1, 0.18 + 0.12 * k)
    air = tvf(white(rng, m), curve(m, [(0, 6500), (min(1, (0.35 + 0.15 * k) * SR / m), 220), (1, 150)], log=True),
              0.7, "lp") * env_ad(m, 0.006, 0.1 + 0.1 * k)
    place(out, unit(bm), t0, 0.35 + 0.22 * k)
    place(out, unit(air), t0, 0.25 + 0.15 * k)
    if k >= 2 and shout_id in SHOUT_FLAVOUR:
        place(out, unit(flavour(rng, SHOUT_FLAVOUR[shout_id], m)), t0, 0.12 * k)
    out = echo(out, [(0.21, 0.06 + 0.05 * k), (0.43, 0.03 + 0.03 * k)])
    return reverb(rng, out, rt60=1.0 + 0.45 * k, mix=0.22 + 0.05 * k, damp=2300)


# =====================================================================================================  generators

GENERATORS: dict = {}


def sfx(*bases):
    """Register a generator fn(rng, v) for stems '<base>' or '<base><v>'."""
    def deco(fn):
        for b in bases:
            GENERATORS[b] = fn
        return fn
    return deco


def resolve(stem: str):
    m = re.fullmatch(r"shout/(\w+)_([123])", stem)
    if m and m.group(1) in catalog.SHOUTS:
        sid, k = m.group(1), int(m.group(2))
        return lambda rng: shout(rng, sid, k)
    base = re.sub(r"\d+$", "", stem)
    v = int(stem[len(base):] or 1)
    fn = GENERATORS.get(base)
    return (lambda rng: fn(rng, v)) if fn else None


# ---------------------------------------------------------------------------------------------  dragon

@sfx("creature/dragon/roar")
def dragon_roar(rng, v):
    dur = rng.uniform(2.6, 3.1)
    n = ns(dur)
    pk = rng.uniform(0.15, 0.3)
    b = rng.uniform(60, 76)
    if v % 2:   # single long bellow
        f0p = [(0, b * 0.8), (pk, b * 1.5), (pk + 0.25, b * 1.3 * rng.uniform(0.9, 1.1)), (0.8, b * 1.05), (1, b * 0.7)]
        amp = [(0, 0.05), (0.06, 0.6), (pk, 1.0), (0.7, 0.85), (1, 0.0)]
    else:       # snarling bark that swells into the roar
        f0p = [(0, b * 1.1), (0.08, b * 1.6), (0.16, b * 0.9), (0.3, b * 1.2), (0.5, b * 1.55), (0.85, b), (1, b * 0.65)]
        amp = [(0, 0), (0.04, 0.8), (0.14, 0.3), (0.22, 0.7), (0.5, 1.0), (0.8, 0.7), (1, 0)]
    path = [(0, "o"), (pk * 0.8, "a"), (0.55, str(rng.choice(["ah", "ae", "a"]))), (0.85, "o"), (1, "u")]
    kw = dict(scale=0.55, amp_pts=amp, oq=0.5, jitter=0.02, shimmer=0.15)
    main = beast(rng, dur, f0p, path, breath=0.6, sub=0.45, sub3=0.15, flutter=0.06, rattle=0.35,
                 rattle_hz=rng.uniform(22, 32), **kw)
    low = beast(rng, dur, [(p, f * 0.5) for p, f in f0p], path, breath=0.2, rattle=0.5, rattle_hz=17, **kw)
    scream = beast(rng, dur, [(p, f * rng.uniform(2.6, 3.2)) for p, f in f0p], [(0, "a"), (0.5, "ae"), (1, "o")],
                   0.8, amp_pts=[(0, 0), (pk, 1), (0.6, 0.5), (1, 0)], breath=1.0, sub=0.3, flutter=0.05)
    br = whoosh(rng, n, [(0, 500), (pk, 1800), (1, 400)], 0.8, curve(n, amp))
    x = unit(main) + 0.7 * unit(low) + 0.22 * unit(scream) + 0.25 * unit(br)
    x = sat(x, 2.2) + 0.5 * unit(lp(low, 160))
    return reverb(rng, x, 2.0, 0.28, damp=2500)


@sfx("creature/dragon/growl")
def dragon_growl(rng, v):
    dur = rng.uniform(1.3, 1.8)
    b = rng.uniform(42, 55)
    f0p = [(0, b * 0.9), (0.35, b * 1.15), (0.7, b * rng.uniform(0.95, 1.2)), (1, b * 0.85)]
    path = [(0, "u"), (0.4, str(rng.choice(["o", "uh"]))), (1, "u")]
    amp = [(0, 0), (0.2, 0.9), (0.5, 1), (0.8, 0.7), (1, 0)]
    x = beast(rng, dur, f0p, path, 0.5, amp, breath=0.5, sub=0.3, rattle=0.75, rattle_hz=rng.uniform(17, 24),
              oq=0.55, jitter=0.03, shimmer=0.2)
    n = len(x)
    x = unit(x) + 0.4 * unit(lp(brown(rng, n), 120) * curve(n, amp))
    return reverb(rng, sat(x, 1.6), 0.9, 0.18, damp=2000)


@sfx("creature/dragon/wings")
def dragon_wings(rng, v):
    n = ns(rng.uniform(0.55, 0.8))
    y = np.zeros(n)
    flaps = [(0.0, 1.0)] if v != 3 else [(0.0, 0.7), (rng.uniform(0.2, 0.26), 1.0)]
    for t0, g in flaps:
        m = n - ns(t0)
        tp = rng.uniform(0.12, 0.22)
        e = curve(m, [(0, 0), (tp * SR / m, 1), (min(1, (tp + 0.35) * SR / m), 0.12), (1, 0)]) ** 1.5
        sw = whoosh(rng, m, [(0, 220), (tp * SR / m, rng.uniform(650, 1100)), (1, 200)], 0.7, e)
        th = boom(m, 80, 34, 0.05, 0.1)
        th = np.concatenate([np.zeros(ns(tp * 0.9)), th])[:m]
        flutter = bp(white(rng, m), 400, 1600) * (0.5 + 0.5 * np.sin(TAU * rng.uniform(16, 26) * tline(m))) ** 2
        flutter *= np.clip(curve(m, [(0, 0), (tp * SR / m, 0), (min(1, (tp + 0.12) * SR / m), 0.8), (1, 0)]), 0, 1)
        place(y, unit(sw) + 0.75 * unit(th) + 0.18 * unit(flutter), t0, g)
    return reverb(rng, y, 0.6, 0.15, damp=2000)


@sfx("creature/dragon/breath_fire")
def dragon_breath_fire(rng, v):
    n = ns(2.6)
    e = curve(n, [(0, 0), (0.06, 1), (0.6, 0.9), (1, 0)]) ** 1.2
    x = flame(rng, n, 2000, 0.4, 80) * e
    ign = whoosh(rng, n, [(0, 400), (0.08, 2600), (0.2, 900), (1, 600)], 0.8, env_ad(n, 0.04, 0.25))
    growl = beast(rng, 2.6, [(0, 55), (0.5, 62), (1, 48)], "o", 0.5, [(0, 0), (0.1, 1), (0.7, 0.8), (1, 0)],
                  rattle=0.6, rattle_hz=20, breath=0.6)
    x = unit(x) + 0.5 * unit(ign) + 0.18 * unit(growl)
    return reverb(rng, sat(x, 1.5), 1.2, 0.2, damp=3000)


@sfx("creature/dragon/breath_frost")
def dragon_breath_frost(rng, v):
    n = ns(2.4)
    e = curve(n, [(0, 0), (0.07, 1), (0.6, 0.85), (1, 0)])
    hiss = tvf(hp(white(rng, n), 1800), 4500 * np.exp(0.25 * wander(rng, n, 4)), 1.2, "bp")
    air = hp(white(rng, n), 2500)
    body = bp(pink(rng, n), 500, 1600) * np.clip(1 + 0.4 * wander(rng, n, 10), 0.2, None)
    tink = scatter(rng, n, 70, 0.1, 0.9, 3000, 9000, (0.03, 0.12), GLASS[:2], 1.5)
    crk = crackle(rng, n, 150, 4000, 12000, env=e)
    x = (unit(hiss) * 0.8 + unit(air) * 0.5 + unit(body) * 0.6) * e + 0.35 * unit(tink) + 0.2 * unit(crk)
    return reverb(rng, x, 1.0, 0.25, damp=5000)


def ground_impact(rng, n, t0=0.03, size=1.0, debris_n=35):
    y = np.zeros(n)
    m = n - ns(t0)
    place(y, boom(m, 60, 26, 0.15 * size, 0.45 * size), t0, 1.0)
    place(y, lp(white(rng, m), 350) * np.exp(-tline(m) / (0.12 * size)), t0, 0.6)
    place(y, bp(white(rng, m), 300, 2500) * np.exp(-tline(m) / 0.04), t0, 0.35)
    place(y, lp(brown(rng, m), 120) * env_ad(m, 0.05, 0.6 * size), t0, 0.5)
    y += 0.25 * unit(debris(rng, n, t0 + 0.02, debris_n, 0.25 * size))
    return y


@sfx("creature/dragon/land")
def dragon_land(rng, v):
    n = ns(2.0)
    x = ground_impact(rng, n, 0.05, 1.0, 45)
    x += 0.15 * unit(whoosh(rng, n, [(0, 300), (0.03, 900), (0.2, 200), (1, 150)], 0.7, env_ad(n, 0.04, 0.15)))
    return reverb(rng, sat(x, 1.4), 1.6, 0.3, damp=2500)


@sfx("creature/dragon/hurt")
def dragon_hurt(rng, v):
    dur = rng.uniform(0.9, 1.3)
    b = rng.uniform(120, 150)
    f0p = [(0, b), (rng.uniform(0.1, 0.25), b * rng.uniform(1.7, 2.0)), (0.5, b * 1.4), (1, b * 0.75)]
    path = [(0, "ae"), (0.3, "a"), (1, "o")]
    main = beast(rng, dur, f0p, path, 0.6, [(0, 0), (0.06, 1), (0.5, 0.8), (1, 0)], breath=0.7, sub=0.35,
                 rattle=0.3, rattle_hz=30, flutter=0.05, jitter=0.025, shimmer=0.15, oq=0.45)
    shriek = beast(rng, dur, [(p, f * 2.5) for p, f in f0p], [(0, "ae"), (1, "e")], 0.8,
                   [(0, 0), (0.1, 1), (0.4, 0.4), (1, 0)], breath=1.2, sub=0.2, flutter=0.06)
    x = unit(main) + 0.3 * unit(shriek)
    return reverb(rng, sat(x, 2.0), 1.0, 0.2, damp=2800)


@sfx("creature/dragon/death")
def dragon_death(rng, v):
    n = ns(3.4)
    rd = 2.6
    f0p = [(0, 95), (0.1, 150), (0.35, 128), (0.7, 80), (1, 45)]
    amp = [(0, 0), (0.06, 1), (0.4, 0.9), (0.8, 0.45), (1, 0)]
    path = [(0, "a"), (0.4, "ah"), (0.7, "o"), (1, "u")]
    main = beast(rng, rd, f0p, path, 0.56, amp, breath=curve(ns(rd), [(0, 0.5), (1, 2.5)]), sub=0.45, sub3=0.1,
                 rattle=0.45, rattle_hz=24, flutter=0.06, jitter=0.03, shimmer=0.18, oq=0.5)
    low = beast(rng, rd, [(p, f * 0.5) for p, f in f0p], path, 0.5, amp, breath=0.2, rattle=0.5, rattle_hz=15)
    x = np.zeros(n)
    place(x, sat(unit(main) + 0.6 * unit(low), 2.0), 0.0)
    x += 0.8 * unit(ground_impact(rng, n, 2.35, 0.9, 40))
    return reverb(rng, x, 1.8, 0.28, damp=2500)


# ---------------------------------------------------------------------------------------------  vocal beasts

BEASTS = {  # tract scale, base f0, voice character
    "troll": dict(scale=0.72, f0=80, breath=0.7, sub=0.35, rattle=0.35, rattle_hz=24, oq=0.5, jitter=0.02, shimmer=0.12, flutter=0.04),
    "bear": dict(scale=0.75, f0=100, breath=0.8, sub=0.3, rattle=0.4, rattle_hz=30, oq=0.5, jitter=0.02, shimmer=0.12, flutter=0.04),
    "giant": dict(scale=0.62, f0=60, breath=0.45, sub=0.2, rattle=0.2, rattle_hz=16, oq=0.6, jitter=0.015, shimmer=0.08, flutter=0.02),
    "horker": dict(scale=0.85, f0=150, breath=0.5, sub=0.4, rattle=0.3, rattle_hz=35, oq=0.45, jitter=0.02, shimmer=0.12, flutter=0.03),
    "sabre_cat": dict(scale=0.85, f0=150, breath=1.2, sub=0.3, rattle=0.35, rattle_hz=28, oq=0.45, jitter=0.03, shimmer=0.15, flutter=0.05),
    "draugr": dict(scale=0.95, f0=95, breath=1.0, sub=0.35, rattle=0.25, rattle_hz=22, oq=0.4, jitter=0.04, shimmer=0.25, flutter=0.03),
    "mammoth": dict(scale=0.95, f0=320, breath=0.4, sub=0.15, rattle=0.15, rattle_hz=40, oq=0.35, sk=0.85, jitter=0.012, shimmer=0.08, flutter=0.05, src_lp=7000),
    "hagraven": dict(scale=1.2, f0=430, breath=1.0, sub=0.35, rattle=0.2, rattle_hz=45, oq=0.4, jitter=0.03, shimmer=0.15, flutter=0.06, src_lp=7000),
    "falmer": dict(scale=1.05, f0=600, breath=1.3, sub=0.25, rattle=0.25, rattle_hz=50, oq=0.4, jitter=0.03, shimmer=0.15, flutter=0.08, src_lp=7000),
}
CALLS = {  # f0 contour (x base), vowel path, amplitude contour, duration range
    "grunt": ([(0, 1.0), (0.25, 1.2), (1, 0.85)], [(0, "uh"), (1, "o")], [(0, 0), (0.12, 1), (0.6, 0.6), (1, 0)], (0.35, 0.6)),
    "roar": ([(0, 1.1), (0.2, 1.8), (0.55, 1.6), (1, 0.9)], [(0, "o"), (0.25, "a"), (0.6, "ae"), (1, "o")],
             [(0, 0), (0.08, 0.8), (0.3, 1), (0.75, 0.8), (1, 0)], (1.3, 1.8)),
    "hurt": ([(0, 1.5), (0.2, 2.1), (1, 1.2)], [(0, "ae"), (0.4, "a"), (1, "o")], [(0, 0), (0.05, 1), (0.5, 0.7), (1, 0)], (0.5, 0.75)),
    "death": ([(0, 1.6), (0.2, 1.5), (0.7, 0.9), (1, 0.55)], [(0, "a"), (0.5, "o"), (1, "u")],
              [(0, 0), (0.05, 1), (0.5, 0.7), (0.85, 0.3), (1, 0)], (1.5, 2.1)),
}


def call(rng, who, kind, dur=None, f_mul=1.0, path=None, **over):
    p = dict(BEASTS[who])
    p.update(over)
    scale, f0 = p.pop("scale"), p.pop("f0") * f_mul * rng.uniform(0.92, 1.08)
    fpts, vpath, apts, dr = CALLS[kind]
    jig = lambda v: v * rng.uniform(0.93, 1.07)  # noqa: E731
    fpts = [(t, f0 * jig(m)) for t, m in fpts]
    return beast(rng, dur or rng.uniform(*dr), fpts, path or vpath, scale, apts, **p)


def seq(parts, gap=(0.08, 0.2), rng=None):
    """Concatenate clips with random gaps."""
    out = []
    for i, p in enumerate(parts):
        out.append(p)
        if i < len(parts) - 1:
            out.append(np.zeros(ns(rng.uniform(*gap))))
    return np.concatenate(out)


# draugr --------------------------------------------------------------------------------------
@sfx("creature/draugr/ambient")
def draugr_ambient(rng, v):
    dur = rng.uniform(1.3, 2.0)
    paths = [[(0, "o"), (1, "u")], [(0, "a"), (0.6, "o"), (1, "uh")], [(0, "uh"), (0.5, "er"), (1, "u")]]
    b = rng.uniform(70, 90)
    f0p = [(0, b), (rng.uniform(0.3, 0.5), b * rng.uniform(1.05, 1.2)), (1, b * 0.7)]
    x = beast(rng, dur, f0p, paths[(v - 1) % 3], 0.95, [(0, 0), (0.15, 1), (0.6, 0.8), (1, 0)],
              breath=2.2, sub=0.45, jitter=0.05, shimmer=0.3, oq=0.35, rattle=0.3, rattle_hz=rng.uniform(14, 22))
    return reverb(rng, lp(x, 5000), 0.9, 0.25, damp=2500)


@sfx("creature/draugr/attack")
def draugr_attack(rng, v):
    n_h = ns(0.05)
    h = bp(white(rng, n_h), 600, 3000) * np.linspace(0, 1, n_h) ** 2
    g = call(rng, "draugr", "hurt", rng.uniform(0.45, 0.65), f_mul=1.35, path=[(0, "a"), (0.5, "ah"), (1, "uh")],
             breath=0.6, oq=0.45)
    x = np.concatenate([h * 0.3 * np.max(np.abs(g)), g])
    return reverb(rng, sat(x, 2.5), 0.7, 0.2)


@sfx("creature/draugr/hurt")
def draugr_hurt(rng, v):
    ng = ns(0.16)
    gasp = formant_bank(white(rng, ng) * curve(ng, [(0, 0), (0.7, 1), (1, 0)]), tract(ng, "a"))
    sn = call(rng, "draugr", "hurt", rng.uniform(0.4, 0.55), rattle=0.45)
    return reverb(rng, np.concatenate([0.5 * unit(gasp), unit(sn)]), 0.7, 0.2)


@sfx("creature/draugr/death")
def draugr_death(rng, v):
    dur = rng.uniform(1.6, 2.0)
    n = ns(dur)
    x = beast(rng, dur, [(0, 110), (0.3, 92), (1, 55)], [(0, "a"), (0.5, "o"), (1, "u")], 0.95,
              [(0, 0), (0.05, 1), (0.6, 0.6), (1, 0)], breath=curve(n, [(0, 0.8), (1, 4.0)]), sub=0.5,
              rattle=0.65, rattle_hz=18, jitter=0.05, shimmer=0.3, oq=0.35)
    return reverb(rng, x, 0.9, 0.25)


# skeever -------------------------------------------------------------------------------------
def squeak(rng, dur, f_pts, rough=0.0):
    n = ns(dur)
    f = curve(n, f_pts, log=True) * (1 + 0.01 * wander(rng, n, 40))
    x = osc(f, (1, 0.35, 0.12)) * curve(n, [(0, 0), (0.15, 1), (0.7, 0.7), (1, 0)])
    if rough:
        x *= 1 - rough * (0.5 + 0.5 * np.sin(TAU * rng.uniform(50, 80) * tline(n)))
    return x


def chitter(rng, count, rate, lo=2500, hi=6000, dec=(0.002, 0.006)):
    """Rapid series of tiny resonant clicks (teeth, mandibles, tongue)."""
    n = ns(count / rate + 0.05)
    y = np.zeros(n)
    for i in range(count):
        c = modal(strike(rng, ns(0.02), 0.001), [rng.uniform(lo, hi)], [rng.uniform(*dec)], [1.0])
        place(y, c, i / rate * rng.uniform(0.85, 1.15), rng.uniform(0.4, 1.0))
    return y


@sfx("creature/skeever/ambient")
def skeever_ambient(rng, v):
    parts = []
    for _ in range(rng.integers(2, 5)):
        a, b = rng.uniform(2200, 3600), rng.uniform(2000, 4200)
        parts.append(squeak(rng, rng.uniform(0.05, 0.13), [(0, a), (0.5, max(a, b) * 1.1), (1, b)], 0.2))
        if rng.random() < 0.5:
            parts.append(0.4 * unit(chitter(rng, rng.integers(3, 7), 28)))
    return reverb(rng, seq(parts, (0.03, 0.12), rng), 0.4, 0.12)


@sfx("creature/skeever/hurt")
def skeever_hurt(rng, v):
    return reverb(rng, squeak(rng, 0.32, [(0, 2600), (0.3, 4300), (1, 2400)], 0.5), 0.4, 0.12)


@sfx("creature/skeever/death")
def skeever_death(rng, v):
    x = seq([squeak(rng, 0.5, [(0, 3800), (0.2, 4200), (1, 1600)], 0.45),
             0.4 * squeak(rng, 0.08, [(0, 2000), (1, 1500)])], (0.1, 0.15), rng)
    return reverb(rng, x, 0.4, 0.12)


# troll / bear / giant / horker ---------------------------------------------------------------
@sfx("creature/troll/ambient")
def troll_ambient(rng, v):
    parts = [call(rng, "troll", "grunt") for _ in range(rng.integers(1, 3))]
    return reverb(rng, sat(seq(parts, (0.1, 0.25), rng), 1.8), 0.6, 0.15)


@sfx("creature/troll/roar")
def troll_roar(rng, v):
    return reverb(rng, sat(call(rng, "troll", "roar", sub=0.45, rattle=0.45), 2.3), 0.9, 0.2)


@sfx("creature/troll/hurt")
def troll_hurt(rng, v):
    return reverb(rng, sat(call(rng, "troll", "hurt"), 2.0), 0.6, 0.15)


@sfx("creature/troll/death")
def troll_death(rng, v):
    return reverb(rng, sat(call(rng, "troll", "death"), 1.8), 0.8, 0.2)


def huff(rng, dur=0.18):
    n = ns(dur)
    return formant_bank(white(rng, n), tract(n, "uh", 0.8)) * env_ad(n, 0.02, dur / 3)


@sfx("creature/bear/ambient")
def bear_ambient(rng, v):
    parts = [0.5 * unit(huff(rng))] if v == 1 else []
    parts += [unit(call(rng, "bear", "grunt", rng.uniform(0.3, 0.45))) for _ in range(rng.integers(1, 3))]
    if v == 2:
        parts.append(0.5 * unit(huff(rng, 0.25)))
    return reverb(rng, sat(seq(parts, (0.06, 0.18), rng), 1.8), 0.5, 0.12)


@sfx("creature/bear/roar")
def bear_roar(rng, v):
    return reverb(rng, sat(call(rng, "bear", "roar", sub=0.4, rattle=0.5), 2.4), 0.8, 0.18)


@sfx("creature/bear/hurt")
def bear_hurt(rng, v):
    return reverb(rng, sat(call(rng, "bear", "hurt"), 2.0), 0.6, 0.15)


@sfx("creature/bear/death")
def bear_death(rng, v):
    return reverb(rng, sat(call(rng, "bear", "death"), 1.8), 0.7, 0.18)


@sfx("creature/giant/ambient")
def giant_ambient(rng, v):
    a = call(rng, "giant", "grunt", rng.uniform(0.5, 0.8), path=[(0, "uh"), (0.5, "er"), (1, "m")])
    b = call(rng, "giant", "grunt", rng.uniform(0.6, 0.9), f_mul=rng.uniform(0.85, 1.1), path=[(0, "o"), (1, "u")])
    return reverb(rng, sat(seq([a, b] if v == 1 else [b, a], (0.15, 0.3), rng), 1.6), 0.9, 0.18)


@sfx("creature/giant/stomp")
def giant_stomp(rng, v):
    n = ns(1.5)
    x = np.zeros(n)
    place(x, boom(n, 50, 22, 0.12, 0.35), 0.02)
    place(x, lp(white(rng, n), 200) * np.exp(-tline(n) / 0.08), 0.02, 0.5)
    place(x, lp(brown(rng, n), 100) * env_ad(n, 0.06, 0.5), 0.02, 0.6)
    x += 0.12 * unit(debris(rng, n, 0.05, 15, 0.2, 0.3))
    return reverb(rng, sat(x, 1.3), 1.0, 0.2, damp=2000)


@sfx("creature/giant/hurt")
def giant_hurt(rng, v):
    return reverb(rng, sat(call(rng, "giant", "hurt", rng.uniform(0.7, 0.95)), 1.8), 0.9, 0.18)


@sfx("creature/giant/death")
def giant_death(rng, v):
    n = ns(2.8)
    x = np.zeros(n)
    place(x, unit(call(rng, "giant", "death", 2.0)), 0.0)
    x += 0.8 * unit(ground_impact(rng, n, 1.85, 0.7, 20))
    return reverb(rng, sat(x, 1.5), 1.0, 0.2)


@sfx("creature/horker/ambient")
def horker_ambient(rng, v):
    parts = [call(rng, "horker", "grunt", rng.uniform(0.18, 0.28), f_mul=rng.uniform(0.9, 1.15),
                  path=[(0, "o"), (1, "uh")]) for _ in range(rng.integers(2, 4))]
    snort = bp(white(rng, ns(0.15)), 300, 1500) * env_ad(ns(0.15), 0.02, 0.04)
    parts.append(0.25 * snort * np.max(np.abs(parts[0])) / max(np.max(np.abs(snort)), 1e-9))
    return reverb(rng, sat(seq(parts, (0.06, 0.14), rng), 2.0), 0.6, 0.15)


@sfx("creature/horker/hurt")
def horker_hurt(rng, v):
    return reverb(rng, sat(call(rng, "horker", "hurt", path=[(0, "o"), (1, "uh")]), 2.0), 0.5, 0.15)


@sfx("creature/horker/death")
def horker_death(rng, v):
    return reverb(rng, sat(call(rng, "horker", "death", 1.4), 1.8), 0.6, 0.15)


# mammoth -------------------------------------------------------------------------------------
def trumpet(rng, dur, pts, rough=0.15):
    """Brassy, nasal trunk call: pressed (short open phase) rich source, bright formants, saturation."""
    n = ns(dur)
    f0 = curve(n, pts, log=True) * (1 + 0.012 * wander(rng, n, 7))
    p = dict(BEASTS["mammoth"])
    p.pop("scale"), p.pop("f0")
    p["sub"] = rough
    v = voice(rng, f0, tract(n, [(0, "er"), (0.3, "ae"), (0.7, "a"), (1, "er")], 1.0),
              curve(n, [(0, 0), (0.08, 0.9), (0.4, 1), (0.85, 0.8), (1, 0)]),
              tilt=0.8, bw=0.8, **p)
    return sat(v, 3.0)


@sfx("creature/mammoth/ambient")
def mammoth_ambient(rng, v):
    b = rng.uniform(260, 340)
    pk = rng.uniform(1.5, 1.8)
    pts = [(0, b), (0.3, b * pk), (0.6, b * pk * 0.95), (1, b * 1.05)] if v == 1 else \
          [(0, b * 1.1), (0.15, b * pk), (0.4, b * 1.2), (0.55, b * pk * 1.05), (1, b * 0.9)]
    x = trumpet(rng, rng.uniform(1.2, 1.6), pts)
    n = len(x)
    rumble = beast(rng, n / SR, 45, "u", 0.6, [(0, 0), (0.2, 1), (1, 0)], rattle=0.5, rattle_hz=12, breath=0.3)
    return reverb(rng, unit(x) + 0.25 * unit(rumble), 1.4, 0.25)


@sfx("creature/mammoth/hurt")
def mammoth_hurt(rng, v):
    b = rng.uniform(330, 380)
    return reverb(rng, trumpet(rng, 0.7, [(0, b * 1.2), (0.25, b * 1.8), (1, b * 1.1)], 0.35), 0.9, 0.2)


@sfx("creature/mammoth/death")
def mammoth_death(rng, v):
    b = rng.uniform(280, 320)
    x = trumpet(rng, 2.0, [(0, b * 1.4), (0.2, b * 1.7), (0.6, b), (1, b * 0.45)], 0.4)
    n = ns(2.4)
    y = pad(x, n)
    y += 0.35 * unit(beast(rng, 2.4, [(0, 55), (1, 38)], "u", 0.6, [(0, 0), (0.5, 0.6), (0.8, 1), (1, 0)],
                           rattle=0.6, rattle_hz=12, breath=0.4))
    return reverb(rng, y, 1.3, 0.22)


# frostbite spider ----------------------------------------------------------------------------
def legs(rng, n, t0, count, rate, accel=1.0):
    y = np.zeros(n)
    t = t0
    for i in range(count):
        c = modal(strike(rng, ns(0.03), 0.0008), [rng.uniform(2200, 5500), rng.uniform(5000, 8000)],
                  [rng.uniform(0.003, 0.008), 0.003], [1.0, 0.5])
        place(y, c, t, rng.uniform(0.4, 1.0))
        t += rng.uniform(0.7, 1.3) / rate * accel ** i
    return y


@sfx("creature/frostbite_spider/ambient")
def spider_ambient(rng, v):
    n = ns(rng.uniform(0.7, 1.0))
    hiss = tvf(hp(white(rng, n), 2500), 5000 * np.exp(0.2 * wander(rng, n, 8)), 1.5, "bp")
    hiss = hiss * curve(n, [(0, 0), (0.08, 1), (0.5, 0.8), (1, 0)]) * np.clip(1 + 0.4 * wander(rng, n, 20), 0, None)
    ch = legs(rng, n, rng.uniform(0.0, 0.3), rng.integers(6, 14), rng.uniform(25, 40))
    return reverb(rng, unit(hiss) + 0.6 * unit(ch), 0.4, 0.1)


@sfx("creature/frostbite_spider/hurt")
def spider_hurt(rng, v):
    n = ns(0.45)
    sq = tvf(white(rng, n), curve(n, [(0, 2500), (0.2, 4200), (1, 2000)], log=True), 6.0, "bp")
    hiss = hp(white(rng, n), 3000)
    e = env_ad(n, 0.01, 0.14)
    return reverb(rng, (unit(sq) + 0.6 * unit(hiss)) * e + 0.4 * unit(legs(rng, n, 0.02, 6, 45)), 0.4, 0.1)


@sfx("creature/frostbite_spider/death")
def spider_death(rng, v):
    n = ns(1.1)
    hiss = tvf(hp(white(rng, n), 2000), curve(n, [(0, 5000), (1, 2200)], log=True), 1.5, "bp") * env_ad(n, 0.01, 0.3)
    return reverb(rng, unit(hiss) + 0.7 * unit(legs(rng, n, 0.05, 14, 40, 1.12)), 0.5, 0.12)


# wolf / sabre cat ----------------------------------------------------------------------------
@sfx("creature/wolf/howl")
def wolf_howl(rng, v):
    dur = rng.uniform(2.2, 2.8)
    n = ns(dur)
    b = rng.uniform(380, 440)
    if v == 1:
        pts = [(0, b), (0.12, b * 1.4), (0.3, b * 1.55), (0.75, b * 1.48), (1, b * 1.05)]
    else:
        pts = [(0, b * 0.95), (0.1, b * 1.25), (0.3, b * 1.32), (0.4, b * 1.6), (0.8, b * 1.5), (1, b * 0.95)]
    f0 = curve(n, pts, log=True)
    vib = np.clip((tline(n) - 0.35) / 0.4, 0, 1) * 0.012 * np.sin(TAU * rng.uniform(4.5, 5.5) * tline(n))
    f0 = f0 * (1 + vib + 0.004 * wander(rng, n, 3))
    amp = curve(n, [(0, 0), (0.1, 0.8), (0.3, 1), (0.8, 0.9), (1, 0)])
    x = voice(rng, f0, tract(n, [(0, "u"), (0.3, "o"), (0.8, "o"), (1, "u")], 1.2), amp, breath=0.15,
              tilt=-0.6, oq=0.8, sk=0.6, jitter=0.004, shimmer=0.03, src_lp=5000)
    return reverb(rng, x, 2.2, 0.35, damp=3000)


@sfx("creature/wolf/growl")
def wolf_growl(rng, v):
    x = beast(rng, rng.uniform(1.0, 1.3), [(0, 95), (0.4, 110), (1, 88)], [(0, "uh"), (0.5, "a"), (1, "uh")], 1.1,
              [(0, 0), (0.15, 1), (0.8, 0.8), (1, 0)], breath=0.9, sub=0.3, rattle=0.6, rattle_hz=30, oq=0.45,
              jitter=0.03, shimmer=0.15)
    return reverb(rng, sat(x, 1.8), 0.5, 0.12)


@sfx("creature/sabre_cat/ambient")
def sabre_ambient(rng, v):
    dur = rng.uniform(0.9, 1.3)
    n = ns(dur)
    amp = [(0, 0), (0.03, 1), (0.25, 0.9), (1, 0)]
    hiss = formant_bank(white(rng, n), tract(n, [(0, "ae"), (1, "a")], 0.85)) * curve(n, amp)
    growl = beast(rng, dur, [(0, 70), (0.3, 82), (1, 65)], "a", 0.85, amp, breath=0.8, rattle=0.7,
                  rattle_hz=rng.uniform(22, 28), sub=0.3, oq=0.45)
    return reverb(rng, sat(unit(hiss) * 0.7 + unit(growl), 1.8), 0.5, 0.12)


@sfx("creature/sabre_cat/hurt")
def sabre_hurt(rng, v):
    return reverb(rng, sat(call(rng, "sabre_cat", "hurt", 0.6, f_mul=1.4, path=[(0, "ae"), (1, "o")]), 2.0), 0.5, 0.12)


@sfx("creature/sabre_cat/death")
def sabre_death(rng, v):
    return reverb(rng, sat(call(rng, "sabre_cat", "death", 1.5, path=[(0, "ae"), (0.5, "a"), (1, "u")]), 1.8), 0.6, 0.15)


# falmer / hagraven ---------------------------------------------------------------------------
@sfx("creature/falmer/ambient")
def falmer_ambient(rng, v):
    scr = call(rng, "falmer", "hurt", rng.uniform(0.5, 0.8), path=[(0, "ii"), (0.5, "e"), (1, "ae")])
    ch = chitter(rng, rng.integers(5, 10), rng.uniform(16, 24), 1500, 3500, (0.004, 0.008))
    parts = [unit(scr), 0.5 * unit(ch)] if v == 1 else [0.5 * unit(ch), unit(scr)]
    return reverb(rng, seq(parts, (0.04, 0.1), rng), 0.9, 0.25)


@sfx("creature/falmer/hurt")
def falmer_hurt(rng, v):
    return reverb(rng, call(rng, "falmer", "hurt", 0.45, f_mul=1.15, path=[(0, "e"), (1, "ii")]), 0.8, 0.22)


@sfx("creature/falmer/death")
def falmer_death(rng, v):
    a = call(rng, "falmer", "death", 0.8, path=[(0, "ii"), (1, "e")])
    g = beast(rng, 0.5, [(0, 160), (1, 90)], "uh", 1.0, [(0, 0), (0.1, 1), (1, 0)], rattle=0.7, rattle_hz=16, breath=2)
    return reverb(rng, np.concatenate([unit(a), 0.5 * unit(g)]), 0.8, 0.22)


def caw(rng, dur, b):
    return call(rng, "hagraven", "hurt", dur, path=[(0, "a"), (0.3, "ae"), (1, "a")],
                f_mul=b / BEASTS["hagraven"]["f0"])


@sfx("creature/hagraven/ambient")
def hagraven_ambient(rng, v):
    b = rng.uniform(380, 460)
    parts = [unit(caw(rng, rng.uniform(0.22, 0.32), b * (0.94 ** i))) for i in range(rng.integers(2, 4))]
    return reverb(rng, seq(parts, (0.1, 0.2), rng), 0.7, 0.2)


@sfx("creature/hagraven/hurt")
def hagraven_hurt(rng, v):
    return reverb(rng, caw(rng, 0.5, 620), 0.7, 0.2)


@sfx("creature/hagraven/death")
def hagraven_death(rng, v):
    x = call(rng, "hagraven", "death", 1.3, path=[(0, "a"), (0.6, "o"), (1, "u")], rattle=0.5, rattle_hz=25)
    return reverb(rng, x, 0.8, 0.22)


# dwarven automatons --------------------------------------------------------------------------
@sfx("creature/dwarven/steam")
def dwarven_steam(rng, v):
    n = ns(rng.uniform(0.8, 1.2))
    y = np.zeros(n)
    t = 0.0
    for i in range(1 if v == 1 else 2):
        m = n - ns(t)
        d = rng.uniform(0.25, 0.5)
        h = tvf(hp(white(rng, m), 1200), 4500 * np.exp(0.15 * wander(rng, m, 6)), 0.9, "bp")
        e = env_ad(m, 0.006, d * 0.6, d * 0.5)
        whistle = osc(rng.uniform(1900, 2600) * (1 + 0.01 * wander(rng, m, 3))) * e
        thump = bp(white(rng, m), 80, 300) * np.exp(-tline(m) / 0.03)
        place(y, unit(h * e) + 0.06 * whistle + 0.3 * unit(thump), t)
        t += rng.uniform(0.3, 0.45)
    return reverb(rng, y, 0.7, 0.18)


@sfx("creature/dwarven/clank")
def dwarven_clank(rng, v):
    n = ns(rng.uniform(0.6, 0.8))
    y = np.zeros(n)
    t = 0.0
    for _ in range(rng.integers(2, 5)):
        place(y, modal_hit(rng, rng.uniform(300, 700), n / SR, rng.uniform(0.06, 0.25), METAL, 0.8), t,
              rng.uniform(0.5, 1.0))
        t += rng.uniform(0.07, 0.2)
    tick = legs(rng, n, 0.02, rng.integers(4, 9), rng.uniform(25, 40))
    return reverb(rng, unit(y) + 0.3 * unit(tick), 0.8, 0.22)


@sfx("creature/dwarven/hurt")
def dwarven_hurt(rng, v):
    n = ns(1.0)
    x = modal_hit(rng, rng.uniform(220, 320), 1.0, 0.6, METAL, 0.85, 0.003)
    x = unit(x) + 0.3 * unit(bp(white(rng, n), 1500, 8000) * np.exp(-tline(n) / 0.02))
    return reverb(rng, x, 0.9, 0.22)


@sfx("creature/dwarven/death")
def dwarven_death(rng, v):
    n = ns(2.3)
    y = np.zeros(n)
    place(y, unit(modal_hit(rng, 260, 1.5, 0.5, METAL)), 0.0)
    whine = osc(curve(ns(1.0), [(0, 130), (1, 38)], log=True), (1, 0.5, 0.33, 0.25)) * curve(ns(1.0), [(0, 0.6), (1, 0)])
    place(y, lp(whine, 1200), 0.05, 0.35)
    t, gap = 0.25, 0.22
    for i in range(rng.integers(8, 14)):
        place(y, unit(modal_hit(rng, rng.uniform(250, 1200), 0.6, rng.uniform(0.05, 0.2))), t, rng.uniform(0.25, 0.8))
        t += gap * rng.uniform(0.6, 1.3)
        gap *= 0.85
    place(y, unit(ground_impact(rng, ns(1.0), 0.0, 0.5, 10)), t + 0.05, 0.9)
    m = ns(0.8)
    place(y, unit(hp(white(rng, m), 1500) * env_ad(m, 0.01, 0.25)), t + 0.1, 0.3)
    return reverb(rng, y, 1.1, 0.25)


# wisp / spriggan -----------------------------------------------------------------------------
@sfx("creature/wisp/ambient")
def wisp_ambient(rng, v):
    n = ns(1.8)
    scale = np.array([1, 9 / 8, 5 / 4, 3 / 2, 5 / 3, 2]) * rng.uniform(1100, 1500)
    y = np.zeros(n)
    for _ in range(rng.integers(5, 9)):
        f = float(rng.choice(scale)) * float(rng.choice([1, 2]))
        d = rng.uniform(0.3, 0.8)
        c = ping(f, d, ns(d * 5)) + 0.25 * ping(f * 2.76, d * 0.4, ns(d * 5)) + 0.7 * ping(f * 1.002, d, ns(d * 5))
        place(y, c, rng.uniform(0.0, 1.0), rng.uniform(0.4, 1.0))
    air = bp(white(rng, n), 2000, 6000) * curve(n, [(0, 0), (0.3, 1), (1, 0)]) * 0.04
    return reverb(rng, unit(y) + air, 2.0, 0.5, damp=6000)


def creak(rng, dur, f_pts, wood=(220, 470, 890, 1430)):
    """Stick-slip friction pulses through wooden modes."""
    n = ns(dur)
    f = curve(n, f_pts, log=True) * np.exp(0.25 * wander(rng, n, 12))
    ph = np.cumsum(f) / SR
    k = ph.astype(np.int64)
    imp = np.zeros(n)
    edges = np.nonzero(np.diff(k, prepend=k[0]))[0]
    imp[edges] = rng.uniform(0.4, 1.0, len(edges))
    sc = rng.uniform(0.85, 1.15)
    return modal(imp, [w * sc for w in wood], [0.03, 0.02, 0.012, 0.008], [1, 0.8, 0.6, 0.4]) * curve(n, [(0, 0), (0.1, 1), (0.8, 0.8), (1, 0)])


def rustle(rng, n, rate=120, env=None):
    return crackle(rng, n, rate, 2000, 8000, 0.004, env, alpha=3.0)


@sfx("creature/spriggan/ambient")
def spriggan_ambient(rng, v):
    dur = rng.uniform(1.2, 1.6)
    n = ns(dur)
    c = creak(rng, dur, [(0, rng.uniform(40, 70)), (0.5, rng.uniform(80, 140)), (1, rng.uniform(40, 80))])
    r = rustle(rng, n, 150, curve(n, [(0, 0.2), (0.5, 1), (1, 0.1)]))
    hum = beast(rng, dur, [(0, 180), (1, 165)], "o", 1.1, [(0, 0), (0.4, 1), (1, 0)], breath=3.0, oq=0.7)
    return reverb(rng, unit(c) + 0.5 * unit(r) + 0.12 * unit(hum), 0.8, 0.22)


@sfx("creature/spriggan/hurt")
def spriggan_hurt(rng, v):
    n = ns(0.6)
    crack = modal_hit(rng, rng.uniform(300, 500), 0.6, 0.04, WOOD, 0.9, 0.004)
    return reverb(rng, unit(crack) + 0.5 * unit(rustle(rng, n, 400, env_ad(n, 0.005, 0.15))), 0.6, 0.18)


@sfx("creature/spriggan/death")
def spriggan_death(rng, v):
    n = ns(2.0)
    y = pad(unit(creak(rng, 1.4, [(0, 120), (1, 30)])), n)
    for t in (0.5, 1.15):
        place(y, unit(modal_hit(rng, rng.uniform(250, 450), 0.5, 0.05, WOOD, 0.9, 0.004)), t, 0.8)
    y += 0.5 * unit(rustle(rng, n, 300, curve(n, [(0, 0.3), (0.6, 1), (1, 0)])))
    return reverb(rng, y, 0.9, 0.22)


# mudcrab / ice wraith ------------------------------------------------------------------------
def clicks(rng, n, t0, count, rate):
    y = np.zeros(n)
    t = t0
    for _ in range(count):
        c = modal(strike(rng, ns(0.03), 0.0008, 500, 9000), [rng.uniform(1800, 4500), rng.uniform(900, 1500)],
                  [rng.uniform(0.003, 0.01), 0.006], [1.0, 0.4])
        place(y, c, t, rng.uniform(0.5, 1.0))
        t += rng.uniform(0.7, 1.3) / rate
    return y


@sfx("creature/mudcrab/ambient")
def mudcrab_ambient(rng, v):
    n = ns(rng.uniform(0.6, 0.9))
    y = clicks(rng, n, 0.0, rng.integers(3, 8), rng.uniform(20, 35))
    y += clicks(rng, n, rng.uniform(0.3, 0.45), rng.integers(3, 6), rng.uniform(20, 35))
    sc = bp(white(rng, n), 500, 2000) * np.clip(wander(rng, n, 30), 0, None) * 0.05
    return reverb(rng, unit(y) + sc, 0.3, 0.1)


@sfx("creature/mudcrab/hurt")
def mudcrab_hurt(rng, v):
    n = ns(0.45)
    crunch = bp(white(rng, n), 800, 6000) * env_ad(n, 0.002, 0.025)
    return reverb(rng, unit(crunch) + 0.7 * unit(clicks(rng, n, 0.04, 8, 45)), 0.3, 0.1)


@sfx("creature/mudcrab/death")
def mudcrab_death(rng, v):
    n = ns(1.0)
    crunch = bp(white(rng, n), 600, 5000) * env_ad(n, 0.002, 0.05)
    y = np.zeros(n)
    t, gap = 0.08, 0.04
    for _ in range(10):
        place(y, clicks(rng, ns(0.05), 0, 1, 1), t, rng.uniform(0.3, 0.9))
        t += gap
        gap *= 1.25
    return reverb(rng, unit(crunch) + 0.7 * unit(y), 0.3, 0.1)


@sfx("creature/ice_wraith/ambient")
def ice_wraith_ambient(rng, v):
    dur = rng.uniform(1.3, 1.7)
    n = ns(dur)
    a, pk, z = rng.uniform(800, 1100), rng.uniform(1900, 2600), rng.uniform(1000, 1400)
    fc = curve(n, [(0, a), (rng.uniform(0.25, 0.45), pk), (1, z)], log=True) * (1 + 0.03 * wander(rng, n, 6))
    src = white(rng, n)
    shriek = tvf(src, fc, 12.0, "bp") + 0.5 * tvf(src, fc * 1.5, 10.0, "bp")
    tone = osc(fc, (1, 0.2)) * 0.15
    wind = bp(pink(rng, n), 300, 1500) * np.clip(1 + 0.5 * wander(rng, n, 3), 0, None)
    e = curve(n, [(0, 0), (0.2, 1), (0.6, 0.8), (1, 0)])
    tink = scatter(rng, n, 12, 0.1, 0.6, 3500, 9000, (0.03, 0.1), GLASS[:2], 1.0)
    return reverb(rng, (unit(shriek) + 0.3 * unit(tone) + 0.35 * unit(wind)) * e + 0.2 * unit(tink), 1.5, 0.35, damp=5000)


def ice_crack(rng, n):
    c = hp(white(rng, n), 1500) * np.exp(-tline(n) / 0.006)
    m = modal(strike(rng, n, 0.001, 1000), rng.uniform(3000, 9000, 6), rng.uniform(0.02, 0.08, 6), rng.uniform(0.3, 1, 6))
    return unit(c) + 0.6 * unit(m) + 0.3 * unit(crackle(rng, n, 300, 3000, 10000, env=np.exp(-tline(n) / 0.08)))


@sfx("creature/ice_wraith/hurt")
def ice_wraith_hurt(rng, v):
    return reverb(rng, ice_crack(rng, ns(0.5)), 0.8, 0.25, damp=6000)


@sfx("creature/ice_wraith/death")
def ice_wraith_death(rng, v):
    n = ns(1.6)
    y = ice_crack(rng, n)
    y += 0.7 * unit(scatter(rng, n, 60, 0.02, 0.25, 2500, 9500, (0.02, 0.15), GLASS[:3], 0.6))
    y += 0.25 * unit(whoosh(rng, n, [(0, 2500), (1, 600)], 1.5, env_ad(n, 0.02, 0.5)))
    return reverb(rng, y, 1.2, 0.3, damp=6000)


# ---------------------------------------------------------------------------------------------  shouts, word walls

@sfx("shout/generic")
def shout_generic(rng, v):
    return shout(rng, "generic", 2)


@sfx("wordwall/chant")
def wordwall_chant(rng, v):
    n = ns(6.0)
    half = n // 2
    # open fifths, the middle voice sinks a semitone half way: eerie, unresolved
    notes = [np.full(n, 98.0), np.concatenate([np.full(half, 146.8), np.full(n - half, 138.6)]), np.full(n, 196.0)]
    notes = [smooth(nt, ns(0.25)) for nt in notes]
    paths = [[(0, "o"), (0.35, "a"), (0.7, "u"), (1, "o")], [(0, "a"), (0.4, "o"), (0.75, "aa"), (1, "u")],
             [(0, "u"), (0.3, "o"), (0.65, "a"), (1, "o")]]
    amp = curve(n, [(0, 0), (0.22, 1), (0.5, 0.8), (0.7, 1), (1, 0)]) ** 1.3
    ch = choir(rng, n, notes, paths, 0.95, 2, amp, breath=0.35)
    drone = beast(rng, 6.0, 73.4, "o", 0.9, [(0, 0), (0.3, 1), (0.7, 1), (1, 0)], breath=0.3, oq=0.7)
    wind = bp(pink(rng, n), 400, 1300) * np.clip(1 + 0.6 * wander(rng, n, 0.5), 0, None) * curve(n, [(0, 0), (0.3, 1), (1, 0)])
    x = lp(unit(ch) + 0.4 * unit(drone) + 0.12 * unit(wind), 3500)
    y = reverb(rng, x, 2.4, 0.45, damp=2500)[:n]
    return fades(y, 0.4, 0.8)


@sfx("wordwall/learn")
def wordwall_learn(rng, v):
    n = ns(3.0)
    rise = whoosh(rng, n, [(0, 200), (0.72, 3500), (1, 800)], 1.2, curve(n, [(0, 0), (0.72, 1), (1, 0)]) ** 2)
    amp = curve(n, [(0, 0), (0.7, 1), (1, 0.3)]) ** 1.5
    ch = choir(rng, n, [98.0, 147.0, 196.0, 247.0], [[(0, "o"), (1, "a")], [(0, "u"), (1, "aa")]], 0.95, 1, amp, 0.25)
    sh = scatter(rng, n, 50, 0.9, 0.6, 2000, 7000, (0.1, 0.4), GLASS[:2], 2.0)
    y = unit(rise) * 0.7 + unit(ch) + 0.35 * unit(sh)
    m = n - ns(2.15)
    place(y, boom(m, 90, 40, 0.1, 0.4), 2.15, 0.6)
    return reverb(rng, y, 2.0, 0.35)


# ---------------------------------------------------------------------------------------------  magic

def stream_env(n):
    return np.clip(curve(n, [(0, 0), (0.1, 1), (0.72, 1), (1, 0)]), 0, 1) ** 1.2


@sfx("magic/spell/fire")
def spell_fire(rng, v):
    n = ns(1.0)
    ign = whoosh(rng, n, [(0, 250), (0.12, 2600), (0.4, 900), (1, 500)], 0.8, env_ad(n, 0.05, 0.25))
    body = flame(rng, n, 1500, 0.3, 70) * env_ad(n, 0.06, 0.3)
    thump = boom(n, 90, 45, 0.05, 0.12)
    return reverb(rng, sat(unit(ign) + 0.7 * unit(body) + 0.5 * unit(thump), 1.4), 0.8, 0.15)


@sfx("magic/spell/fire_stream")
def spell_fire_stream(rng, v):
    n = ns(rng.uniform(0.7, 0.8))
    return sat(flame(rng, n, rng.uniform(1600, 2200), 0.35, 60), 1.3) * stream_env(n)


@sfx("magic/spell/frost")
def spell_frost(rng, v):
    n = ns(0.9)
    e = env_ad(n, 0.01, 0.25)
    crk = crackle(rng, n, 400, 3000, 11000, env=e)
    sh = scatter(rng, n, 25, 0.0, 0.2, 2500, 8000, (0.08, 0.25), GLASS[:2], 0.5)
    air = hp(white(rng, n), 3000) * env_ad(n, 0.02, 0.18)
    return reverb(rng, 0.7 * unit(crk) + unit(sh) + 0.4 * unit(air), 1.0, 0.25, damp=6000)


@sfx("magic/spell/frost_stream")
def spell_frost_stream(rng, v):
    n = ns(rng.uniform(0.7, 0.8))
    hiss = tvf(hp(white(rng, n), 2500), 5000 * np.exp(0.2 * wander(rng, n, 6)), 1.0, "bp")
    hiss *= np.clip(1 + 0.3 * wander(rng, n, 12), 0.2, None)
    x = unit(hiss) + 0.25 * unit(crackle(rng, n, 200, 4000, 12000)) + 0.2 * unit(
        scatter(rng, n, 8, 0.05, 0.3, 3000, 8000, (0.03, 0.1), GLASS[:2], 1.0))
    return x * stream_env(n)


@sfx("magic/spell/shock")
def spell_shock(rng, v):
    n = ns(0.55)
    crack = hp(white(rng, n), 1500) * np.exp(-tline(n) / 0.004)
    a = arc(rng, n, 3500) * env_ad(n, 0.003, 0.12, 0.05)
    siz = bp(white(rng, n), 3000, 9000) * env_ad(n, 0.002, 0.08) * np.clip(wander(rng, n, 80), 0, None)
    return reverb(rng, unit(crack) * 0.8 + unit(a) + 0.4 * unit(siz), 0.5, 0.12)


@sfx("magic/spell/shock_stream")
def spell_shock_stream(rng, v):
    n = ns(rng.uniform(0.7, 0.8))
    a = arc(rng, n, rng.uniform(2000, 3500), rng.uniform(95, 125), 0.6)
    hum = osc(np.full(n, rng.uniform(95, 125)), (1, 0.5, 0.4, 0.3, 0.2)) * 0.15
    return (unit(a) + hum) * stream_env(n)


@sfx("magic/spell/heal")
def spell_heal(rng, v):
    n = ns(1.6)
    y = np.zeros(n)
    for f in (220.0, 277.18, 329.63, 440.0, 554.37):
        for d in (0.0, rng.uniform(0.6, 1.8)):
            y += osc(np.full(n, f + d), (1, 0.25, 0.08), rng.uniform(0, TAU)) / (1 + f / 600)
    y *= curve(n, [(0, 0), (0.28, 1), (0.6, 0.85), (1, 0)]) ** 1.3
    sh = scatter(rng, n, 10, 0.2, 0.5, 2000, 4000, (0.2, 0.5), (1.0,), 2.0)
    air = bp(white(rng, n), 3000, 7000) * curve(n, [(0, 0), (0.4, 1), (1, 0)])
    return reverb(rng, unit(y) + 0.15 * unit(sh) + 0.04 * unit(air), 1.6, 0.35)


@sfx("magic/spell/conjure")
def spell_conjure(rng, v):
    n = ns(1.8)
    swell = curve(n, [(0, 0), (0.72, 1), (0.76, 0.5), (1, 0)])
    rum = tvf(brown(rng, n), curve(n, [(0, 80), (0.72, 500), (1, 120)], log=True), 0.9, "lp") * swell
    sub = osc(curve(n, [(0, 38), (0.72, 75), (1, 45)], log=True), (1, 0.3)) * swell
    m = ns(1.3)
    rev = reverb(rng, burst(rng, ns(0.05), 300, 3000, 0.002, 0.02), 1.25, 1.0)[:m][::-1]
    dark = whoosh(rng, n, [(0, 150), (0.7, 1200), (1, 300)], 1.5, swell)
    y = unit(rum) + 0.6 * unit(sub) + 0.4 * unit(dark)
    place(y, unit(rev), 0.0, 0.5)
    place(y, boom(n - ns(1.3), 120, 50, 0.05, 0.15), 1.3, 0.6)
    return reverb(rng, y, 1.5, 0.3)


@sfx("magic/spell/illusion")
def spell_illusion(rng, v):
    n = ns(1.2)
    t = tline(n)
    y = np.zeros(n)
    for i, f in enumerate((740.0, 988.0, 1245.0, 1480.0, 1976.0)):
        sw = 1 + 0.004 * np.sin(TAU * rng.uniform(0.6, 1.4) * t + rng.uniform(0, TAU))
        trem = 0.5 + 0.5 * np.sin(TAU * 3.0 * t - i * TAU / 5)
        y += (osc(np.full(n, f)) + osc(f * sw, phase=1.0)) * trem
    y *= curve(n, [(0, 0), (0.25, 1), (0.6, 0.8), (1, 0)])
    air = whoosh(rng, n, [(0, 1500), (0.5, 4500), (1, 2000)], 2.0, curve(n, [(0, 0), (0.3, 1), (1, 0)]))
    return reverb(rng, unit(y) + 0.3 * unit(air), 1.8, 0.4, damp=6000)


@sfx("magic/spell/alteration")
def spell_alteration(rng, v):
    n = ns(1.2)
    chime = modal(strike(rng, n, 0.002, 500), [620, 1710, 3350, 5540], [0.9, 0.5, 0.3, 0.15], [1, 0.6, 0.4, 0.2])
    hum = osc(np.full(n, 110.0), (1, 0.5, 0.25, 0.12)) * curve(n, [(0, 0), (0.2, 1), (1, 0)])
    hum *= 1 + 0.15 * np.sin(TAU * 5 * tline(n))
    return reverb(rng, unit(chime) + 0.45 * unit(hum), 1.2, 0.3)


@sfx("magic/spell/charge")
def spell_charge(rng, v):
    n = ns(1.0)
    f = curve(n, [(0, 110), (1, 230)], log=True)
    saw = osc(f, tuple(1 / k for k in range(1, 30)))
    tone = tvf(saw, curve(n, [(0, 300), (1, 3200)], log=True), 1.5, "lp")
    trem = 1 + 0.3 * np.sin(TAU * np.cumsum(curve(n, [(0, 6), (1, 18)])) / SR)
    siz = hp(white(rng, n), 4000) * curve(n, [(0, 0), (1, 1)]) ** 2
    e = curve(n, [(0, 0), (0.85, 1), (1, 0.6)]) ** 1.5
    return (unit(tone * trem) + 0.2 * unit(siz)) * e


@sfx("magic/spell/fizzle")
def spell_fizzle(rng, v):
    n = ns(0.5)
    puff = burst(rng, n, 600, 3000, 0.005, 0.06)
    sput = crackle(rng, n, 120, 1000, 6000, 0.001, env=np.exp(-tline(n) / 0.15))
    drop = osc(curve(n, [(0, 900), (1, 200)], log=True)) * env_ad(n, 0.005, 0.12)
    return reverb(rng, unit(puff) + 0.6 * unit(sput) + 0.25 * unit(drop), 0.4, 0.12)


@sfx("magic/spell/impact_fire")
def spell_impact_fire(rng, v):
    n = ns(1.1)
    blast = tvf(white(rng, n), curve(n, [(0, 7000), (0.4, 400), (1, 250)], log=True), 0.7, "lp") * env_ad(n, 0.003, 0.2)
    bm = boom(n, 85, 35, 0.06, 0.25)
    tail = flame(rng, n, 1500, 0.4, 120) * env_ad(n, 0.02, 0.3)
    return reverb(rng, sat(unit(blast) + 0.8 * unit(bm) + 0.5 * unit(tail), 1.8), 1.2, 0.25)


@sfx("magic/spell/impact_frost")
def spell_impact_frost(rng, v):
    n = ns(1.0)
    y = ice_crack(rng, n)
    y += 0.8 * unit(scatter(rng, n, 50, 0.01, 0.15, 2000, 9000, (0.03, 0.2), GLASS[:2], 0.4))
    y += 0.4 * unit(boom(n, 110, 60, 0.03, 0.08))
    return reverb(rng, y, 1.0, 0.25, damp=6000)


@sfx("magic/spell/impact_shock")
def spell_impact_shock(rng, v):
    n = ns(1.4)
    crack = hp(white(rng, n), 1500) * np.exp(-tline(n) / 0.003)
    a = arc(rng, n, 5000) * env_ad(n, 0.001, 0.06)
    rum = lp(brown(rng, n), 150) * env_ad(n, 0.02, 0.5)
    mid = bp(white(rng, n), 200, 1200) * env_ad(n, 0.005, 0.15)
    return reverb(rng, sat(unit(crack) + 0.6 * unit(a) + 0.7 * unit(rum) + 0.4 * unit(mid), 1.6), 1.6, 0.3)


@sfx("magic/dragon_soul")
def dragon_soul(rng, v):
    n = ns(4.0)
    t = tline(n)
    env = curve(n, [(0, 0), (0.78, 1), (1, 0)]) ** 1.4
    wind = np.zeros(n)
    for i in range(3):
        fc = (500 + 400 * i) * (1 + 0.6 * np.sin(TAU * rng.uniform(0.5, 0.9) * t + i)) * curve(n, [(0, 0.6), (0.8, 1.6), (1, 1)])
        wind += tvf(white(rng, n), fc, 2.0, "bp")
    notes = [curve(n, [(0, f), (0.8, f * 1.122), (1, f * 1.122)], log=True) for f in (98.0, 147.0, 196.0)]
    ch = choir(rng, n, notes, [[(0, "o"), (0.8, "a"), (1, "aa")], [(0, "u"), (1, "a")]], 0.95, 2,
               curve(n, [(0, 0), (0.8, 1), (1, 0)]) ** 2, 0.3)
    thrum = osc(np.full(n, 45.0), (1, 0.5, 0.2)) * (0.6 + 0.4 * np.sin(TAU * curve(n, [(0, 4), (1, 8)]) * t))
    crk = crackle(rng, n, 80, 1500, 6000, env=env)
    y = (unit(wind) * 0.6 + unit(thrum) * 0.6) * env + unit(ch) * 0.8 + 0.2 * unit(crk)
    return reverb(rng, y, 1.8, 0.3)


@sfx("magic/shrine_pray")
def shrine_pray(rng, v):
    n = ns(2.5)
    amp = curve(n, [(0, 0), (0.2, 1), (0.6, 0.8), (1, 0)])
    ch = choir(rng, n, [146.8, 220.0, 293.7, 370.0], [[(0, "o"), (1, "a")], [(0, "a"), (1, "o")]], 0.95, 1, amp, 0.3)
    b = bell(rng, 587.3, 2.5, 1.5, 0.8)
    sh = scatter(rng, n, 12, 0.2, 0.6, 2500, 5000, (0.2, 0.5), (1.0,), 2.0)
    return reverb(rng, unit(ch) + 0.6 * unit(b) + 0.12 * unit(sh), 2.0, 0.4)


@sfx("magic/standing_stone")
def standing_stone(rng, v):
    n = ns(3.0)
    exc = lp(white(rng, n), 400) * env_ad(n, 0.05, 0.3)
    stone = modal(exc, [52, 87, 131, 188, 262], [2.0, 1.5, 1.0, 0.7, 0.5], [1, 0.8, 0.6, 0.4, 0.3])
    rum = lp(brown(rng, n), 120) * curve(n, [(0, 0), (0.2, 1), (1, 0)])
    sh = np.zeros(n)
    for h in range(4, 13):
        a = np.clip(0.5 + 0.5 * wander(rng, n, 1.5), 0, None) / h ** 0.5
        sh += a * (osc(np.full(n, 220.0 * h)) + osc(np.full(n, 220.0 * h + rng.uniform(0.5, 2.0))))
    sh *= curve(n, [(0, 0), (0.25, 1), (0.7, 0.7), (1, 0)])
    return reverb(rng, unit(stone) + 0.4 * unit(rum) + 0.35 * unit(sh), 2.0, 0.35)


@sfx("world/nirnroot_hum")
def nirnroot_hum(rng, v):
    n = ns(2.0)
    y = np.zeros(n)
    for i, f in enumerate((1568.0, 2349.0, 3136.0, 3951.0, 4699.0)):
        g = 0.75 ** i
        y += g * (osc(np.full(n, f), phase=rng.uniform(0, TAU)) + osc(np.full(n, f + rng.uniform(1, 3)), phase=rng.uniform(0, TAU)))
    y *= curve(n, [(0, 0), (0.15, 1), (0.6, 0.85), (1, 0)]) * (1 + 0.1 * np.sin(TAU * 5.5 * tline(n)))
    air = bp(white(rng, n), 4000, 9000) * 0.01
    return reverb(rng, unit(y) + air, 1.5, 0.35, damp=7000)


# ---------------------------------------------------------------------------------------------  UI

@sfx("ui/menu_open")
def ui_menu_open(rng, v):
    n = ns(0.55)
    wh = whoosh(rng, n, [(0, 180), (0.35, 650), (1, 250)], 0.9, env_ad(n, 0.12, 0.12))
    th = np.zeros(n)
    place(th, boom(n, 110, 60, 0.04, 0.07) + 0.3 * lp(white(rng, n), 600) * np.exp(-tline(n) / 0.012), 0.08)
    return reverb(rng, 0.6 * unit(wh) + 0.5 * unit(th), 0.5, 0.15, damp=2500)


@sfx("ui/menu_close")
def ui_menu_close(rng, v):
    n = ns(0.35)
    e = curve(n, [(0, 0), (0.7, 1), (1, 0)]) ** 1.5
    return reverb(rng, whoosh(rng, n, [(0, 900), (1, 260)], 0.9, e), 0.4, 0.12, damp=2500)


@sfx("ui/menu_click")
def ui_menu_click(rng, v):
    n = ns(0.09)
    c = modal(strike(rng, n, 0.0015, 300, 6000), [1150, 2650, 4100], [0.012, 0.006, 0.004], [1, 0.5, 0.25])
    body = ping(180, 0.01, n)
    return unit(c) + 0.3 * body


@sfx("ui/menu_back")
def ui_menu_back(rng, v):
    n = ns(0.22)
    c = modal(strike(rng, n, 0.0015, 200, 4000), [760, 1700], [0.015, 0.008], [1, 0.4])
    wh = whoosh(rng, n, [(0, 500), (1, 200)], 0.9, env_ad(n, 0.03, 0.06))
    return unit(c) + 0.4 * unit(wh)


@sfx("ui/quest_update")
def ui_quest_update(rng, v):
    n = ns(1.8)
    gong = modal(strike(rng, n, 0.004, 60, 3000), 110 * np.array([1, 1.52, 2.0, 2.41, 2.98, 3.6, 4.2]),
                 [1.6, 1.2, 1.0, 0.7, 0.5, 0.4, 0.3], [1, 0.6, 0.5, 0.35, 0.25, 0.15, 0.1])
    d = drum(rng, n, 72, 0.3)
    return reverb(rng, unit(gong) + 0.6 * unit(d), 1.4, 0.3)


@sfx("ui/quest_complete")
def ui_quest_complete(rng, v):
    n = ns(2.2)
    y = np.zeros(n)
    for i, f in enumerate((392.0, 493.9, 587.3, 784.0)):
        place(y, bell(rng, f, 2.0, 1.1, 0.8), 0.045 * i, 0.8)
    gong = modal(strike(rng, n, 0.004, 60, 3000), 98 * np.array([1, 1.52, 2.0, 2.41]), [1.5, 1.0, 0.8, 0.5], [1, .5, .4, .3])
    return reverb(rng, unit(y) + 0.5 * unit(gong) + 0.4 * unit(drum(rng, n, 68, 0.3)), 1.6, 0.3)


@sfx("ui/skill_up")
def ui_skill_up(rng, v):
    n = ns(1.4)
    y = np.zeros(n)
    for i, f in enumerate((659.3, 880.0, 1108.7, 1318.5)):
        place(y, bell(rng, f, 1.2, 0.8, 1.0), 0.085 * i, 0.7 + 0.1 * i)
    sw = whoosh(rng, n, [(0, 1500), (0.3, 6000), (1, 3000)], 1.5, env_ad(n, 0.15, 0.2))
    return reverb(rng, unit(y) + 0.08 * unit(sw), 1.3, 0.3)


@sfx("ui/level_up")
def ui_level_up(rng, v):
    n = ns(2.1)
    y = np.zeros(n)
    for f in (146.8, 220.0, 293.7, 370.0, 440.0):
        saw = osc(np.full(n, f * (1 + rng.uniform(-0.002, 0.002))), tuple(1 / k for k in range(1, 25)))
        y += saw
    y = tvf(y, curve(n, [(0, 500), (0.2, 2500), (1, 700)], log=True), 0.8, "lp")
    y *= curve(n, [(0, 0), (0.18, 1), (0.5, 0.7), (1, 0)]) ** 1.2
    b = pad(bell(rng, 587.3, 2.0, 1.2, 0.8) + bell(rng, 880.0, 2.0, 1.0, 0.8), n)
    return reverb(rng, unit(y) + 0.4 * unit(b) + 0.5 * unit(drum(rng, n, 60, 0.4)), 1.8, 0.3)


@sfx("ui/discover")
def ui_discover(rng, v):
    n = ns(1.3)
    b = bell(rng, 147.0, 1.3, 1.0, 0.8)
    sh = scatter(rng, n, 14, 0.02, 0.15, 2000, 5000, (0.1, 0.35), (1.0,), 0.6)
    return reverb(rng, unit(b) + 0.6 * unit(drum(rng, n, 58, 0.35)) + 0.2 * unit(sh), 1.4, 0.3)


@sfx("ui/lockpick_turn")
def ui_lockpick_turn(rng, v):
    n = ns(0.16)
    y = np.zeros(n)
    for i in range(rng.integers(2, 4)):
        c = modal(strike(rng, ns(0.03), 0.0006, 1500), rng.uniform(0.9, 1.1, 3) * [3100, 5300, 7400],
                  [0.005, 0.004, 0.003], [1, 0.6, 0.4])
        place(y, c, 0.01 + 0.04 * i + rng.uniform(0, 0.015), rng.uniform(0.5, 1.0))
    sc = bp(white(rng, n), 2500, 6000) * env_ad(n, 0.01, 0.04) * np.clip(1 + wander(rng, n, 200), 0, None)
    return unit(y) + 0.3 * unit(sc)


@sfx("ui/lockpick_strain")
def ui_lockpick_strain(rng, v):
    n = ns(0.45)
    rate = 45 * np.exp(0.3 * wander(rng, n, 10))
    ph = np.cumsum(rate) / SR
    k = ph.astype(np.int64)
    imp = np.zeros(n)
    edges = np.nonzero(np.diff(k, prepend=0))[0]
    imp[edges] = rng.uniform(0.5, 1.0, len(edges))
    exc = imp + 0.05 * white(rng, n)
    f = curve(n, [(0, 1800), (1, 2050)], log=True)
    y = tvf(exc, f, 30, "bp") + 0.5 * tvf(exc, f * 2.17, 30, "bp")
    return y * curve(n, [(0, 0), (0.2, 0.7), (0.85, 1), (1, 0)])


@sfx("ui/lockpick_break")
def ui_lockpick_break(rng, v):
    n = ns(0.6)
    snap = hp(white(rng, n), 2000) * np.exp(-tline(n) / 0.0015)
    ring = modal(strike(rng, n, 0.0008, 1500), [2900, 4700, 6800, 9100], [0.12, 0.08, 0.06, 0.04], [1, 0.7, 0.5, 0.3])
    y = unit(snap) + 0.7 * unit(ring)
    for t, g in ((0.12, 0.5), (0.21, 0.35), (0.27, 0.25), (0.31, 0.15)):
        c = modal(strike(rng, ns(0.1), 0.0005, 2000), rng.uniform(5000, 8000, 2), [0.03, 0.02], [1, 0.6])
        place(y, unit(c), t, g)
    return reverb(rng, y, 0.4, 0.1, damp=6000)


@sfx("ui/lockpick_unlock")
def ui_lockpick_unlock(rng, v):
    n = ns(0.45)
    y = np.zeros(n)
    latch = modal(strike(rng, n, 0.0008, 1500), [2500, 4200], [0.01, 0.006], [1, 0.5])
    place(y, unit(latch), 0.0, 0.4)
    clunk = modal(strike(rng, n, 0.004, 80, 3000), [170, 410, 930, 1650], [0.06, 0.04, 0.025, 0.015], [1, 0.7, 0.5, 0.3])
    place(y, unit(clunk) + 0.5 * unit(boom(n, 120, 80, 0.02, 0.04)), 0.05)
    for t in (0.15, 0.19):
        place(y, unit(modal(strike(rng, ns(0.05), 0.0006, 1500), [rng.uniform(3000, 5000)], [0.006], [1])), t, 0.2)
    return reverb(rng, y, 0.3, 0.1)


# =====================================================================================================  rendering

def target_db(stem: str) -> float:
    if stem.startswith("ui/lockpick"):
        return -22.0
    if stem.startswith("ui/"):
        return -24.0
    if stem in ("creature/dragon/death1",) or stem.startswith("creature/dragon/roar"):
        return -11.0
    if stem.startswith("creature/giant/stomp") or stem == "creature/dragon/land1":
        return -12.0
    if stem.startswith("creature/"):
        return -16.0
    if stem == "wordwall/chant":
        return -18.0
    if stem == "wordwall/learn":
        return -15.0
    if stem.startswith("shout/"):
        return -10.0
    if stem.startswith("magic/spell/impact_"):
        return -14.0
    if stem == "magic/dragon_soul":
        return -14.0
    if stem.startswith("magic/"):
        return -17.0
    if stem.startswith("world/"):
        return -22.0
    return -16.0


def max_dur(stem: str) -> float:
    if stem == "wordwall/chant":
        return 6.2
    if stem.startswith("shout/"):
        return 4.5
    if stem == "magic/dragon_soul":
        return 4.4
    if stem.startswith("creature/dragon/") or stem.startswith("creature/giant/death"):
        return 3.5
    if stem.startswith("ui/") and "level_up" not in stem and "quest" not in stem:
        return 1.5
    return 3.0


def finish(x: np.ndarray, stem: str) -> np.ndarray:
    """DC block, trim silence, cap length, fade, loudness-normalise."""
    x = np.asarray(x, dtype=np.float64)
    if not np.all(np.isfinite(x)):
        raise RuntimeError(f"{stem}: non-finite samples")
    x = hp(x, 22.0)
    env = smooth(np.abs(x), ns(0.01))
    top = float(env.max())
    if top < 1e-9:
        raise RuntimeError(f"{stem}: silent")
    idx = np.nonzero(env > top * 10 ** (-45 / 20))[0]   # tails below -45 dB are inaudible in game
    x = x[max(0, idx[0] - ns(0.002)):idx[-1] + 1]
    cap = max_dur(stem)
    fo = 0.04
    if len(x) > ns(cap):
        x = x[:ns(cap)]
        fo = 0.35
    x = fades(x, 0.003, min(fo, len(x) / SR * 0.25))
    return audiolib.loudness_normalise(x, target_db(stem), SR, -1.0)


def stats(x: np.ndarray) -> dict:
    spec = np.abs(np.fft.rfft(x)) ** 2
    fr = np.fft.rfftfreq(len(x), 1 / SR)
    tot = spec.sum() + 1e-20
    env = np.sqrt(smooth(x ** 2, ns(0.05)))
    return dict(dur=len(x) / SR, peak=20 * np.log10(np.max(np.abs(x)) + 1e-12),
                loud=20 * np.log10(np.percentile(env, 95) + 1e-12),
                low=float(spec[fr < 300].sum() / tot), high=float(spec[fr > 4000].sum() / tot))


def render(stem: str, wav_dir: str | None) -> dict:
    try:
        return _render(stem, wav_dir)
    except Exception:  # report which stem failed (worker tracebacks are otherwise anonymous)
        import traceback
        return dict(stem=stem, error=traceback.format_exc())


def _render(stem: str, wav_dir: str | None) -> dict:
    gen = resolve(stem)
    rng = np.random.default_rng(zlib.crc32(stem.encode("utf-8")))
    x = finish(gen(rng), stem)
    out = catalog.SOUNDS / (stem + ".ogg")
    q = 2 if stem.startswith("ui/") else 3
    audiolib.write_ogg(x, out, SR, q)
    if wav_dir:
        wp = pathlib.Path(wav_dir) / (stem.replace("/", "__") + ".wav")
        wp.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(wp), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(audiolib.to_pcm16(x))
    st = stats(x)
    st.update(stem=stem, size=out.stat().st_size)
    return st


def all_stems() -> dict:
    """stem -> catalogue category, for the categories this script owns."""
    out = {}
    for c in CATEGORIES:
        for s in catalog.files(c):
            out[s] = c
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("only", nargs="*", help="render only stems containing any of these substrings")
    ap.add_argument("--wav", metavar="DIR", help="also write 16-bit WAVs here")
    ap.add_argument("--list", action="store_true", help="list stems, generators and loudness targets")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    ap.add_argument("-v", "--verbose", action="store_true", help="print per-file statistics")
    args = ap.parse_args()

    stems = all_stems()
    missing = [s for s in stems if resolve(s) is None]
    if missing:
        print("ERROR: catalogued stems without a generator:\n  " + "\n  ".join(missing), file=sys.stderr)
        return 2
    if args.list:
        for s, c in stems.items():
            print(f"{s:44s} {c:9s} {target_db(s):6.1f} dB")
        return 0
    sel = [s for s in stems if not args.only or any(o in s for o in args.only)]
    if not sel:
        print("nothing matches " + " ".join(args.only), file=sys.stderr)
        return 1
    audiolib.find_ffmpeg()
    t0 = time.time()
    results, failed = [], []
    if args.jobs > 1 and len(sel) > 1:
        with ProcessPoolExecutor(max_workers=args.jobs) as ex:
            it = list(ex.map(render, sel, [args.wav] * len(sel)))
    else:
        it = (render(s, args.wav) for s in sel)
    for r in it:
        if "error" in r:
            failed.append(r)
            print(f"  {r['stem']:44s} FAILED", file=sys.stderr)
            print(r["error"], file=sys.stderr)
            continue
        results.append(r)
        print(f"  {r['stem']:44s} {r['dur']:5.2f}s {r['size'] / 1024:6.1f} KB")
    for s in OBSOLETE:
        p = catalog.SOUNDS / (s + ".ogg")
        if s not in stems and p.exists():
            p.unlink()
            print(f"  removed obsolete {p.relative_to(catalog.REPO)}")
    if args.verbose:
        print(f"\n  {'stem':44s} {'dur':>5s} {'peak':>6s} {'loud':>6s} {'<300':>5s} {'>4k':>5s}")
        for r in results:
            print(f"  {r['stem']:44s} {r['dur']:5.2f} {r['peak']:6.1f} {r['loud']:6.1f} {r['low']:5.2f} {r['high']:5.2f}")
    # size summary over everything this script owns (not only what was rendered now)
    print("\n  category   files   seconds      size")
    total = 0
    for c in CATEGORIES:
        files = [catalog.SOUNDS / (s + ".ogg") for s, cc in stems.items() if cc == c]
        size = sum(p.stat().st_size for p in files if p.exists())
        secs = sum(r["dur"] for r in results if stems[r["stem"]] == c)
        total += size
        print(f"  {c:9s} {len(files):6d} {secs:9.1f} {size / 1e6:8.2f} MB")
    print(f"  {'total':9s} {len(stems):6d} {'':9s} {total / 1e6:8.2f} MB   ({len(results)} rendered in {time.time() - t0:.1f}s)")
    if total > 3.2e6:
        print("  WARNING: over the ~3 MB budget")
    if failed:
        print(f"ERROR: {len(failed)} file(s) failed: " + ", ".join(r["stem"] for r in failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
