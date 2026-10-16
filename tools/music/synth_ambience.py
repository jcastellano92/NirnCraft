#!/usr/bin/env python3
"""
Skycraft procedural ambience synthesiser: every file of the sound-catalogue categories "bed" and "ambient".

Everything here is ORIGINAL procedural synthesis (numpy, plus scipy.signal for a few causal filters). No game audio
is read, sampled or analysed.

  * Beds   (ambient/bed/<name>.ogg): stereo, 24 kHz, Vorbis q1, 24-40 s, seamless loops, loud-part RMS ~ -22 dBFS.
             Every layer of a bed is built *circularly* over the loop length L: noise is shaped by FFT filtering of an
             L-sample buffer (circular convolution), time-varying spectra come from a circular overlap-add STFT
             synthesis, control signals are sums of harmonics of 1/L, oscillator phases are nudged to close an integer
             number of cycles over L, grains that run past the end wrap around into the start, and reverbs are circular
             convolutions. The result is exactly periodic: sample L-1 flows into sample 0 exactly as any sample flows
             into the next (the ideal limit of "render L + overlap and crossfade the tail into the head"). `loopify`
             (equal-power overlap crossfade) is kept for any future non-periodic layer.
  * One-shots (ambient/<name>N.ogg): mono, 32 kHz, Vorbis q2, 0.3-6 s, trimmed, faded, loud-part RMS ~ -18 dBFS.

Every file gets its own numpy Generator seeded by zlib.crc32(stem), so renders are reproducible.

Usage:
    py -3 tools/music/synth_ambience.py                 # render every bed + one-shot
    py -3 tools/music/synth_ambience.py wind cave       # only stems containing "wind" or "cave"
    py -3 tools/music/synth_ambience.py --wav out/      # also write WAVs to out/
    py -3 tools/music/synth_ambience.py --list          # list catalogued stems and their generators
    py -3 tools/music/synth_ambience.py --verify        # after rendering, decode the beds and re-check the loop seams
"""
from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
import time
import wave
import zlib

import numpy as np
from scipy import signal as sps

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import audiolib  # noqa: E402
import sound_catalog  # noqa: E402

SOUNDS = sound_catalog.SOUNDS
BED_SR, SHOT_SR = 24000, 32000
BED_Q, SHOT_Q = 1, 2
BED_DB, SHOT_DB = -22.0, -18.0
# Soft-limit ceilings. Vorbis overshoots sharp peaks by up to ~3 dB at these qualities, so leave headroom.
BED_PEAK, SHOT_PEAK = -6.0, -3.0
BED_WIDTH = 0.8          # side-channel gain applied to a bed (BEDS entries may override it)
TAU = 2.0 * np.pi


# =====================================================================================================  basics

def seeded(stem: str) -> np.random.Generator:
    return np.random.default_rng(zlib.crc32(stem.encode("utf-8")))


def db(v):
    return 10.0 ** (np.asarray(v, dtype=float) / 20.0)


def rms(x) -> float:
    return float(np.sqrt(np.mean(np.square(x)) + 1e-20))


def unit(x):
    return x / rms(x)


def g_lp(f, fc, order=2):
    return 1.0 / np.sqrt(1.0 + (f / fc) ** (2 * order))


def g_hp(f, fc, order=2):
    return 1.0 / np.sqrt(1.0 + (fc / np.maximum(f, 1e-6)) ** (2 * order))


def g_bp(f, fc, q):
    ff = np.maximum(f, 1e-6)
    return 1.0 / np.sqrt(1.0 + (q * (ff / fc - fc / ff)) ** 2)


def g_col(f, color):
    """Amplitude slope for noise colour: 0 white, 1 pink (-3 dB/oct), 2 brown (-6 dB/oct); floored at 20 Hz."""
    return (np.maximum(f, 20.0) / 1000.0) ** (-color / 2.0)


def shape(x, sr, gain, pad=0):
    """Zero-phase FFT filter along the last axis by magnitude response gain(f). Circular unless pad > 0."""
    x = np.asarray(x, dtype=float)
    n = x.shape[-1]
    m = n + pad
    y = np.fft.irfft(np.fft.rfft(x, n=m, axis=-1) * gain(np.fft.rfftfreq(m, 1.0 / sr)), n=m, axis=-1)
    return y[..., :n]


def noise(rng, n, sr, color=0.0, lo=None, hi=None, chans=None, order=2):
    """Coloured, band-limited Gaussian noise, unit RMS, exactly periodic in n."""
    w = rng.standard_normal(n if chans is None else (chans, n))

    def g(f):
        r = g_col(f, color)
        if lo:
            r = r * g_hp(f, lo, order)
        if hi:
            r = r * g_lp(f, hi, order)
        return r
    return unit(shape(w, sr, g))


def wide(x3, corr=0.3):
    """Three independent rows -> stereo pair sharing `corr` of their power (row 2 is the shared part)."""
    a, b = np.sqrt(1.0 - corr), np.sqrt(corr)
    return np.stack([a * x3[0] + b * x3[2], a * x3[1] + b * x3[2]])


def tv_noise(rng, n, sr, gain, nfft=1920, chans=1):
    """Noise with a time-varying magnitude spectrum gain(tc[T], f[F]) -> (T, F), by circular overlap-add of
    random-phase frames (Hann, 75 % overlap). Exactly periodic in n; unit RMS."""
    hop = nfft // 4
    if n % hop:
        raise ValueError("tv_noise: n must be a multiple of nfft/4")
    T, F = n // hop, nfft // 2 + 1
    f = np.fft.rfftfreq(nfft, 1.0 / sr)
    tc = ((np.arange(T) * hop + nfft / 2) % n) / sr
    G = gain(tc, f)
    w = 0.5 - 0.5 * np.cos(TAU * np.arange(nfft) / nfft)
    out = []
    for _ in range(chans):
        spec = (rng.standard_normal((T, F)) + 1j * rng.standard_normal((T, F))) * G
        fr = np.fft.irfft(spec, n=nfft, axis=1) * w
        acc = np.zeros((T, hop))
        for k in range(4):
            acc += np.roll(fr[:, k * hop:(k + 1) * hop], k, axis=0)
        out.append(acc.reshape(-1))
    y = np.array(out) if chans > 1 else out[0]
    return unit(y)


class Lfo:
    """Smooth random control signal in [-1, 1], exactly periodic in `period` seconds (sum of harmonics of 1/period)."""

    def __init__(self, rng, period, fmax, alpha=1.0, fmin=None):
        kmin = max(1, int(round((fmin or 0.0) * period)))
        kmax = max(kmin, int(round(fmax * period)))
        k = np.arange(kmin, kmax + 1)
        self.w = TAU * k / period
        self.a = rng.uniform(0.35, 1.0, len(k)) * (k / kmin) ** (-alpha)
        self.p = rng.uniform(0, TAU, len(k))
        grid = np.linspace(0, period, 4096, endpoint=False)
        self.norm = 1.0 / max(1e-9, float(np.max(np.abs(self._raw(grid)))))

    def _raw(self, t):
        t = np.asarray(t, dtype=float)
        out = np.zeros(t.shape)
        for w, a, p in zip(self.w, self.a, self.p):
            out += a * np.cos(w * t + p)
        return out

    def __call__(self, t):
        return self._raw(t) * self.norm

    def u(self, t, sharp=1.0):
        """Mapped to [0, 1] (optionally sharpened by a power)."""
        return np.clip(0.5 + 0.5 * self(t), 0.0, 1.0) ** sharp


def loop_phase(f, sr):
    """Oscillator phase (radians) for instantaneous frequency f over one loop, nudged to close a whole number of cycles."""
    f = np.asarray(f, dtype=float)
    n = len(f)
    cycles = float(np.sum(f)) / sr
    f = f + (max(1, round(cycles)) - cycles) * sr / n
    return TAU * np.cumsum(f) / sr


def saw_blep(phase, f, sr):
    """Band-limited (PolyBLEP) sawtooth from a phase in radians and its instantaneous frequency."""
    t = (phase / TAU) % 1.0
    dt = np.clip(np.broadcast_to(f, t.shape) / sr, 1e-6, 0.5)
    y = 2.0 * t - 1.0
    m = t < dt
    x = t[m] / dt[m]
    y[m] -= x + x - x * x - 1.0
    m = t > 1.0 - dt
    x = (t[m] - 1.0) / dt[m]
    y[m] -= x * x + x + x + 1.0
    return y


def harmonics(ph, f, sr, nh=20, tilt=1.0, half=0.0, amps=None):
    """Additive harmonic tone from phase ph; harmonics fade out below Nyquist. half>0 adds half-integer partials (rasp)."""
    f = np.broadcast_to(np.asarray(f, dtype=float), ph.shape)
    nyq = 0.45 * sr
    out = np.zeros(ph.shape)
    fmin = float(np.min(f))
    for k in range(1, nh + 1):
        if k * fmin > nyq:
            break
        a = amps[k - 1] if amps is not None and k <= len(amps) else k ** (-tilt)
        out += a * np.clip((nyq - k * f) / (0.04 * sr), 0, 1) * np.sin(k * ph)
        if half and (k - 0.5) * fmin < nyq:
            out += half * a * np.clip((nyq - (k - 0.5) * f) / (0.04 * sr), 0, 1) * np.sin((k - 0.5) * ph)
    return out


def win_grain(m, att=0.1):
    """Asymmetric raised-cosine window: rises over att*m samples, falls over the rest."""
    m = max(int(m), 2)
    a = min(max(1, int(m * att)), m - 1)
    r = m - a
    w = np.empty(m)
    w[:a] = 0.5 - 0.5 * np.cos(np.pi * np.arange(a) / a)
    w[a:] = 0.5 + 0.5 * np.cos(np.pi * np.arange(r) / r)
    return w


def curve(t, pts):
    """Cosine-interpolated breakpoint curve through (time, value) points, evaluated at t (held outside the range)."""
    ts = np.array([p[0] for p in pts], dtype=float)
    vs = np.array([p[1] for p in pts], dtype=float)
    t = np.asarray(t, dtype=float)
    if len(ts) == 1:
        return np.full(t.shape, vs[0])
    i = np.clip(np.searchsorted(ts, t, side="right") - 1, 0, len(ts) - 2)
    u = np.clip((t - ts[i]) / np.maximum(ts[i + 1] - ts[i], 1e-9), 0, 1)
    u = 0.5 - 0.5 * np.cos(np.pi * u)
    return vs[i] + (vs[i + 1] - vs[i]) * u


def loopify(x, n, xf):
    """x holds n + xf samples: crossfade the overlap tail x[n:n+xf] into the head with an equal-power curve and
    return n samples whose last sample flows into the first. (For non-periodic layers; periodic ones need nothing.)"""
    y = np.array(x[..., :n], dtype=float)
    u = (np.arange(xf) + 0.5) / xf
    y[..., :xf] = x[..., :xf] * np.sin(u * np.pi / 2) + x[..., n:n + xf] * np.cos(u * np.pi / 2)
    return y


# ---------------------------------------------------------------------------- causal filters (one-shots)

def filt(x, sr, kind, fc, order=2):
    return sps.sosfilt(sps.butter(order, fc, btype=kind, fs=sr, output="sos"), x)


def bandnoise(rng, n, sr, lo, hi, order=2):
    return unit(filt(rng.standard_normal(n), sr, "bandpass", [lo, min(hi, 0.45 * sr)], order))


def smooth(rng, n, sr, fc):
    """Low-passed Gaussian noise, unit RMS (causal; for jitter / roughness)."""
    return unit(filt(rng.standard_normal(n + int(sr / fc)), sr, "lowpass", fc, 2)[-n:])


def reson(x, sr, fc, bw):
    """Two-pole resonator with ~unity peak gain."""
    r = np.exp(-np.pi * bw / sr)
    th = TAU * fc / sr
    b0 = (1.0 - r) * np.sqrt(1.0 - 2.0 * r * np.cos(2 * th) + r * r)
    return sps.lfilter([b0], [1.0, -2.0 * r * np.cos(th), r * r], x)


def formant(x, sr, bank):
    return sum(g * reson(x, sr, fc, bw) for fc, bw, g in bank)


def place(out, g, pos):
    """Add grain g into mono out at sample pos (clipped, non-circular)."""
    pos = int(pos)
    if pos >= len(out) or pos + len(g) <= 0:
        return
    s, e = max(pos, 0), min(len(out), pos + len(g))
    out[s:e] += g[s - pos:e - pos]


def place_circ(out, g, pos):
    """Add grain g into out (last axis length n) at pos, wrapping past the end into the start."""
    n = out.shape[-1]
    idx = (int(pos) + np.arange(len(g))) % n
    out[..., idx] += g


def make_ir(rng, sr, t60, chans=1, predelay=0.0, damp=0.5, bright=7000.0, echoes=()):
    """Synthetic reverb IR: band-split decaying Gaussian noise (high bands decay faster), optional discrete echoes,
    unit energy per channel. Independent noise per channel -> decorrelated stereo."""
    m = int(sr * t60 * 1.05) + 1
    t = np.arange(m) / sr
    W = np.fft.rfft(rng.standard_normal((chans, m)), axis=-1)
    f = np.fft.rfftfreq(m, 1.0 / sr)
    ir = np.zeros((chans, m))
    for lo, hi, k in ((None, 300, 1.2), (300, 1500, 1.0), (1500, 5000, 1 - 0.45 * damp), (5000, None, 1 - 0.75 * damp)):
        g = np.ones_like(f)
        if lo:
            g = g * g_hp(f, lo, 4)
        if hi:
            g = g * g_lp(f, hi, 4)
        ir += np.fft.irfft(W * g, n=m, axis=-1) * np.exp(-6.91 * t / (t60 * k))
    ir = shape(ir, sr, lambda ff: g_lp(ff, bright, 2))
    ir *= 1.0 - np.exp(-t / 0.004)
    if echoes:
        seg = ir[:, :int(0.08 * sr)] * win_grain(int(0.08 * sr), 0.1)
        seg = seg / np.sqrt(np.mean(seg ** 2) + 1e-20) * np.sqrt(np.mean(ir[:, :int(0.08 * sr)] ** 2))
        for d, gg in echoes:
            i = int(d * sr)
            if i < m:
                L = min(seg.shape[1], m - i)
                ir[:, i:i + L] += gg * seg[:, :L]
    if predelay:
        ir = np.concatenate([np.zeros((chans, int(predelay * sr))), ir], axis=1)
    ir /= np.sqrt(np.sum(ir ** 2, axis=-1, keepdims=True))
    return ir if chans > 1 else ir[0]


def conv(x, ir):
    """Linear convolution (FFT)."""
    m = x.shape[-1] + ir.shape[-1] - 1
    nf = 1 << (m - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, nf) * np.fft.rfft(ir, nf), nf)[..., :m]


def conv_circ(x, ir):
    """Circular convolution over x's length (x: (n,) or (c, n); ir shorter than n)."""
    n = x.shape[-1]
    return np.fft.irfft(np.fft.rfft(x, n=n, axis=-1) * np.fft.rfft(ir, n=n, axis=-1), n=n, axis=-1)


def verb(x, sr, rng, t60, wet, dry=1.0, **kw):
    """Mono one-shot through a synthetic room (output is longer by the tail)."""
    y = wet * conv(x, make_ir(rng, sr, t60, 1, **kw))
    y[:len(x)] += dry * x
    return y


# ---------------------------------------------------------------------------- sound building blocks

def tone(sr, dur, f, harm=(1.0, 0.12, 0.04), att=0.15, am=None, ph0=0.0):
    """Short whistled syllable: frequency contour f(u), u in [0,1] (or constant), few harmonics, smooth envelope."""
    n = max(int(sr * dur), 4)
    u = np.arange(n) / (n - 1)
    fr = f(u) if callable(f) else np.full(n, float(f))
    ph = ph0 + TAU * np.cumsum(fr) / sr
    y = np.zeros(n)
    for k, h in enumerate(harm, 1):
        if h and k * fr.max() < 0.45 * sr:
            y += h * np.sin(k * ph)
    e = win_grain(n, att)
    if am is not None:
        e = e * (am(u) if callable(am) else am)
    return y * e


def voice(rng, sr, dur, f0, env, nh=20, tilt=1.0, half=0.0, rasp=0.0, rasp_fc=200.0, jitter=0.0, breath=0.0,
          breath_band=(500.0, 4000.0), formants=None, vib=(0.0, 5.0), amps=None):
    """Generic voiced call: contour f0 (breakpoints over u in [0,1] or callable), harmonic source with jitter /
    vibrato / roughness (noise AM) / half-integer partials, breath noise, optional formant bank, envelope env."""
    n = int(sr * dur)
    tt = np.arange(n) / sr
    u = tt / dur
    f = f0(u) if callable(f0) else curve(u, f0)
    if jitter:
        f = f * (1.0 + jitter * smooth(rng, n, sr, 30.0))
    if vib[0]:
        f = f * (1.0 + vib[0] * np.sin(TAU * vib[1] * tt + rng.uniform(0, TAU)))
    ph = TAU * np.cumsum(f) / sr
    s = harmonics(ph, f, sr, nh, tilt, half, amps)
    s = s / (rms(s) + 1e-12)
    if rasp:
        s = s * (1.0 + rasp * smooth(rng, n, sr, rasp_fc))
    if breath:
        s = s + breath * bandnoise(rng, n, sr, *breath_band)
    if formants:
        s = formant(s, sr, formants)
    e = env(u) if callable(env) else curve(u, env)
    return s * e


def bubble(sr, f0, amp=1.0, xi=0.1, damp=1.0):
    """Minnaert-style bubble / drop: exponentially decaying sine whose pitch rises as the bubble closes."""
    d = (0.13 * f0 + 0.0072 * f0 ** 1.5) * damp
    m = max(int(sr * min(0.4, 5.0 / d)), 8)
    t = np.arange(m) / sr
    ph = TAU * np.cumsum(f0 * (1.0 + xi * d * t)) / sr
    return amp * np.sin(ph) * np.exp(-d * t) * (1.0 - np.exp(-t * sr / 6.0))


def pulse_voice(rng, sr, dur, rate, bank, env=None, jitter=0.02, amp_jit=0.15, soft=0.25):
    """Pulse train (frog croaks, insect rasps, wood creaks): pulse rate contour rate(u) exciting a resonator bank.
    Each excitation is a short Hann pulse of `soft` ms (rounder than a bare impulse, and far cheaper to encode)."""
    n = int(sr * dur)
    u = np.arange(n) / n
    r = rate(u) if callable(rate) else curve(u, rate) if isinstance(rate, (list, tuple)) else np.full(n, float(rate))
    if jitter:
        r = r * (1.0 + jitter * smooth(rng, n, sr, 40.0))
    cyc = np.cumsum(r) / sr
    idx = np.nonzero(np.diff(np.floor(cyc)))[0] + 1
    exc = np.zeros(n)
    exc[idx] = 1.0 + amp_jit * rng.standard_normal(len(idx))
    if soft:
        exc = np.convolve(exc, np.hanning(max(3, int(sr * soft / 1000)) + 2)[1:-1], mode="same")
    y = formant(exc, sr, bank)
    e = win_grain(n, 0.15) if env is None else (env(u) if callable(env) else curve(u, env))
    return y * e


def modal(rng, sr, dur, modes, beat=0.0, att=0.001):
    """Struck resonator: sum of exponentially decaying partials (f, amp, tau); beat>0 adds a detuned twin (shimmer)."""
    n = int(sr * dur)
    t = np.arange(n) / sr
    y = np.zeros(n)
    for f, a, tau in modes:
        if f >= 0.45 * sr:
            continue
        dec = a * np.exp(-t / tau)
        y += dec * np.sin(TAU * f * t + rng.uniform(0, TAU))
        if beat:
            y += 0.6 * dec * np.sin(TAU * (f + beat * rng.uniform(0.5, 1.5)) * t + rng.uniform(0, TAU))
    return y * (1.0 - np.exp(-t / att))


def frog_call(rng, sr, kind, pitch=1.0):
    """One call of a frog 'species' (used by the one-shots and the marsh/jungle beds)."""
    if kind == "peep":      # high up-slurred whistle (spring-peeper-like)
        return tone(sr, rng.uniform(0.06, 0.1), lambda u: pitch * 2900 * (0.86 + 0.16 * u), harm=(1, 0.08), att=0.35)
    if kind == "tink":      # short metallic tink
        return tone(sr, rng.uniform(0.025, 0.04), pitch * rng.uniform(3300, 4200), harm=(1, 0.2, 0.05), att=0.2)
    if kind == "krr":       # pulsed tree-frog rasp
        return pulse_voice(rng, sr, rng.uniform(0.12, 0.22), 95 * pitch,
                           [(1500 * pitch, 160, 1.0), (3000 * pitch, 300, 0.4)])
    if kind == "creek":     # chorus frog: rising pulse rate, like a thumb along a comb
        return pulse_voice(rng, sr, rng.uniform(0.4, 0.6), [(0, 13), (1, 30)], [(2300 * pitch, 260, 1.0),
                                                                              (4400 * pitch, 500, 0.3)], jitter=0.05)
    if kind == "croak":     # deep bullfrog-like croak
        y = pulse_voice(rng, sr, rng.uniform(0.3, 0.45), 105 * pitch, [(230 * pitch, 60, 1.0), (520 * pitch, 120, 0.7),
                                                                       (1150 * pitch, 250, 0.25)], jitter=0.05)
        return y * (1.0 + 0.5 * np.sin(TAU * 21 * np.arange(len(y)) / sr))
    if kind == "kruk":      # medium croak
        return pulse_voice(rng, sr, rng.uniform(0.06, 0.09), 75 * pitch, [(900 * pitch, 110, 1.0), (1900 * pitch, 240, 0.5)],
                           env=[(0, 0), (0.15, 1), (0.7, 0.8), (1, 0)])
    raise KeyError(kind)


# =====================================================================================================  beds

class Bed:
    """Circular (exactly periodic) stereo bed of `secs` seconds."""

    def __init__(self, rng, secs, sr=BED_SR):
        self.rng, self.secs, self.sr = rng, int(secs), sr
        self.n = self.secs * sr
        self.t = np.arange(self.n) / sr
        self.mix = np.zeros((2, self.n))

    # control
    def lfo(self, fmax, alpha=1.0, fmin=None):
        return Lfo(self.rng, self.secs, fmax, alpha, fmin)

    def smooth(self, fc, order=2):
        """Periodic low-passed noise, zero mean, unit RMS (fast random modulation)."""
        x = shape(self.rng.standard_normal(self.n), self.sr, lambda f: g_lp(f, fc, order) * (f > 0))
        return unit(x)

    # sources
    def noise(self, color=1.0, lo=None, hi=None, corr=0.3, order=2):
        return wide(noise(self.rng, self.n, self.sr, color, lo, hi, chans=3, order=order), corr)

    def mono_noise(self, color=0.0, lo=None, hi=None, order=2):
        return noise(self.rng, self.n, self.sr, color, lo, hi, order=order)

    def tv(self, gain, nfft=1920, corr=0.3):
        return wide(tv_noise(self.rng, self.n, self.sr, gain, nfft, chans=3), corr)

    def crackle(self, density, lo, hi, heavy=0.0, chans=2):
        """Sparse random impulses (leaves, sand, snow, grit) at a time-varying density (events/s), band-passed."""
        p = np.clip(np.broadcast_to(density, (self.n,)) / self.sr, 0, 1)
        rows = []
        for _ in range(chans):
            a = self.rng.standard_normal(self.n)
            if heavy:
                a = a * (1.0 + heavy * self.rng.pareto(2.5, self.n))
            rows.append(np.where(self.rng.random(self.n) < p, a, 0.0))
        x = shape(np.array(rows), self.sr, lambda f: g_hp(f, lo, 2) * g_lp(f, hi, 3))
        return unit(x)

    def osc_saw(self, f):
        return saw_blep(loop_phase(f, self.sr), f, self.sr)

    def tone_loop(self, f, h=(1.0,)):
        ph = loop_phase(np.broadcast_to(np.asarray(f, dtype=float), (self.n,)), self.sr)
        ph = ph + self.rng.uniform(0, TAU)
        return sum(a * np.sin(k * ph) for k, a in enumerate(h, 1))

    # space / placement
    def verb(self, x, t60, wet, dry=1.0, **kw):
        ir = make_ir(self.rng, self.sr, t60, chans=2, **kw)
        x2 = x if x.ndim == 2 else np.stack([x, x])
        return dry * x2 + wet * conv_circ(x2, ir)

    def pan(self, x, pan, itd=0.0005):
        th = (pan + 1.0) * np.pi / 4
        L, R = x * np.cos(th) * np.sqrt(2), x * np.sin(th) * np.sqrt(2)
        d = int(round(abs(pan) * itd * self.sr))
        if pan > 0:
            L = np.roll(L, d)
        elif pan < 0:
            R = np.roll(R, d)
        return np.stack([L, R])

    def put(self, track, g, t_sec, pan=0.0, gain=1.0, itd=0.0005):
        th = (pan + 1.0) * np.pi / 4
        pos = int(t_sec * self.sr)
        d = int(round(abs(pan) * itd * self.sr))
        place_circ(track[0], g * gain * np.cos(th) * np.sqrt(2), pos + (d if pan > 0 else 0))
        place_circ(track[1], g * gain * np.sin(th) * np.sqrt(2), pos + (d if pan < 0 else 0))

    def track(self):
        return np.zeros((2, self.n))

    def add(self, x, level_db=0.0):
        """Mix a layer in at `level_db` RMS (over the loop) relative to a unit-RMS reference."""
        x = np.asarray(x, dtype=float)
        if x.ndim == 1:
            x = np.stack([x, x])
        self.mix += unit(x) * db(level_db)


def _st(x):
    return x if x.ndim == 2 else np.stack([x, x])


def wind_layer(B, gust, color, lo, cut_lo, cut_hi, whistles=(), nfft=1920, corr=0.3, gust_db=10.0):
    """Wind body: coloured noise whose level and low-pass cutoff follow the gust control (0..1)."""
    def gain(tc, f):
        g = gust(tc)[:, None]
        G = db(-gust_db * (1 - g)) * g_col(f[None, :], color) * g_hp(f[None, :], lo, 2) * \
            g_lp(f[None, :], cut_lo + (cut_hi - cut_lo) * g, 2)
        return G
    body = B.tv(gain, nfft, corr)
    if not whistles:
        return body, None

    def wgain(tc, f):
        G = np.zeros((len(tc), len(f)))
        for fc_fn, q, amp_fn in whistles:
            G += amp_fn(tc)[:, None] * g_bp(f[None, :], fc_fn(tc)[:, None], q)
        return G
    return body, B.tv(wgain, 3840, corr * 0.5)


def whistle_set(B, gust, bases, q, spread=0.12, gain_track=0.5, amp_floor=0.1):
    out = []
    for b in bases:
        drift, am = B.lfo(0.08), B.lfo(0.1)
        out.append((lambda tc, b=b, d=drift: b * (1 + spread * d(tc)) * (1 - gain_track * 0.5 + gain_track * gust(tc)),
                    q, lambda tc, a=am: (amp_floor + (1 - amp_floor) * a.u(tc, 2.0)) * gust(tc) ** 1.5))
    return out


def bed_wind_light(B):
    gl = B.lfo(0.12, 1.0)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, wh = wind_layer(B, gust, 1.2, 60, 450, 1500, whistle_set(B, gust, [620, 1050, 1650], 28), gust_db=9)
    g = gust(B.t)
    rustle = B.crackle(600 + 3500 * g ** 2, 2500, 8000)
    hiss = B.noise(0.3, 2500, 7000, corr=0.1) * (0.3 + 0.7 * g)
    B.add(body, 0)
    B.add(wh, -15)
    B.add(rustle * (0.2 + 0.8 * g), -16)
    B.add(hiss, -23)


def bed_wind_strong(B):
    gl = B.lfo(0.18, 0.9)
    gust = lambda t: gl.u(t, 1.4)  # noqa: E731
    body, wh = wind_layer(B, gust, 1.5, 35, 250, 1700,
                          whistle_set(B, gust, [330, 560, 900, 1400], 30, spread=0.08, gain_track=0.6, amp_floor=0.25),
                          gust_db=14)
    g = gust(B.t)
    B.add(body, 0)
    B.add(wh, -7)
    B.add(B.noise(0.5, 3000, 9000, corr=0.1) * g ** 2, -22)
    B.add(B.noise(2.0, 25, 130, corr=0.6) * (0.5 + 0.5 * g), -8)


def bed_blizzard(B):
    gl = B.lfo(0.25, 0.8)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, wh = wind_layer(B, gust, 1.3, 45, 900, 1800, whistle_set(B, gust, [460, 760], 16, amp_floor=0.4), gust_db=6)
    g = gust(B.t)
    B.add(body, 0)
    B.add(wh, -12)
    B.add(B.noise(0.0, 2500, 10000, corr=0.15) * (0.6 + 0.4 * g), -6)
    B.add(B.crackle(5000 + 6000 * g, 3000, 9500) * (0.5 + 0.5 * g), -11)
    B.add(B.noise(2.0, 30, 160, corr=0.5), -8)


def reed_chorus(B, gust, lo=180.0, hi=640.0, color=1.5):
    """Gentle leaf/reed chorus modulation over warm low-passed pink/brown breeze (180-650 Hz, zero white-noise static)."""
    base = B.noise(color, lo, hi, corr=0.45, order=3)
    mod_a = 0.55 + 0.45 * B.lfo(0.14, 0.9).u(B.t, 1.3)
    mod_b = 0.65 + 0.35 * np.sin(loop_phase(0.35 + 0.12 * B.lfo(0.07)(B.t), B.sr))
    g = gust(B.t) if callable(gust) else gust
    return unit(base * mod_a * mod_b * (0.4 + 0.6 * g))


def distant_chirps(B, count, fband=(1650, 3200), dur=(0.045, 0.11)):
    """Warm melodic distant bird calls using pure sine/triangle harmonics with smooth cosine fade-in/out envelopes."""
    tr = B.track()
    rng = B.rng
    placed = 0
    while placed < count:
        c0 = rng.uniform(0, B.secs)
        pan = rng.uniform(-0.85, 0.85)
        base = rng.uniform(*fband)
        lvl = db(rng.uniform(-14, -2))
        t0 = c0
        for _ in range(rng.integers(2, 6)):
            d = rng.uniform(*dur)
            a = base * rng.uniform(0.9, 1.12)
            b = a * rng.uniform(0.88, 1.18)
            g = tone(B.sr, d, lambda u, a=a, b=b: a + (b - a) * (0.5 - 0.5 * np.cos(np.pi * u)),
                     harm=(1.0, 0.11, 0.03), att=0.45)
            B.put(tr, g, t0, pan + rng.uniform(-0.08, 0.08), lvl * db(rng.uniform(-6, 0)))
            t0 += d + rng.uniform(0.06, 0.18)
            placed += 1
    tr = shape(tr, B.sr, lambda f: g_lp(f, 3600, 3) * g_hp(f, 900, 2))
    return B.verb(tr, 1.5, 0.85, 0.45, damp=0.75)


def bed_forest_day(B):
    gl = B.lfo(0.09, 1.0)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.6, 55, 180, 620, gust_db=7)
    leaves = reed_chorus(B, gust, 210.0, 640.0, color=1.5)
    B.add(body, -1)
    B.add(leaves, -7)
    B.add(distant_chirps(B, 4 * B.secs, (1600, 3100)), -9)


def cricket_track(B, fc, pulses, pdur, pint, period, jitter=0.05, level=1.0):
    """Pure sine/triangle harmonic cricket chirps with smooth raised-cosine fade-in/out envelopes."""
    env = np.zeros(B.n)
    w = win_grain(int(pdur * B.sr), 0.48)
    tt = B.rng.uniform(0, period)
    while tt < B.secs:
        for k in range(pulses):
            place_circ(env, w * level * (1 - 0.1 * k / pulses), int((tt + k * pint) * B.sr))
        tt += period * (1 + B.rng.uniform(-jitter, jitter))
    f = fc * (1 + 0.003 * B.smooth(2.5))
    ph = loop_phase(f, B.sr) + B.rng.uniform(0, TAU)
    return env * (np.sin(ph) + 0.09 * np.sin(2 * ph) + 0.025 * np.sin(3 * ph))


def trill_track(B, fc, rate, level=1.0, swell=True):
    """Warm tree-cricket harmonic trill: sine/triangle carrier gated by a smooth cosine pulse envelope."""
    rph = loop_phase(np.full(B.n, rate), B.sr) + B.rng.uniform(0, TAU)
    gate = 0.5 - 0.5 * np.cos(rph)
    ph = loop_phase(fc * (1 + 0.0025 * B.smooth(2.0)), B.sr) + B.rng.uniform(0, TAU)
    env = B.lfo(0.06).u(B.t, 1.5) if swell else 1.0
    return level * gate * env * (np.sin(ph) + 0.08 * np.sin(2 * ph) + 0.02 * np.sin(3 * ph))


def katydid_track(B, fc, period, pulses=(2, 3), level=1.0, active=None):
    """Harmonic night-insect syllable pulse (pure sine/triangle harmonics with smooth cosine fade-in/out)."""
    env = np.zeros(B.n)
    tt = B.rng.uniform(0, period)
    while tt < B.secs:
        on = 1.0 if active is None else float(active(np.array([tt % B.secs]))[0])
        for k in range(B.rng.integers(pulses[0], pulses[1] + 1)):
            d = B.rng.uniform(0.028, 0.045)
            place_circ(env, win_grain(int(d * B.sr), 0.48) * level * on, int((tt + k * B.rng.uniform(0.075, 0.11)) * B.sr))
        tt += period * B.rng.uniform(0.88, 1.12)
    f0 = np.clip(fc * 0.42, 1850.0, 3200.0) * (1 + 0.003 * B.smooth(2.5))
    ph = loop_phase(f0, B.sr) + B.rng.uniform(0, TAU)
    carrier = np.sin(ph) + 0.14 * np.sin(2 * ph) + 0.04 * np.sin(3 * ph)
    return env * carrier


def chorus(B, tracks_and_pans, t60=0.9, wet=0.35):
    tr = B.track()
    for x, p in tracks_and_pans:
        tr += B.pan(x, p)
    tr = shape(tr, B.sr, lambda f: g_lp(f, 3800, 3) * g_hp(f, 160, 2))
    return B.verb(tr, t60, wet, 1.0, damp=0.7)


def crickets(B, count, fband=(2400, 3400), lvl=(-14, -1)):
    items = []
    for _ in range(count):
        items.append((cricket_track(B, B.rng.uniform(*fband), int(B.rng.integers(2, 5)), B.rng.uniform(0.014, 0.022),
                                    B.rng.uniform(0.028, 0.038), B.rng.uniform(0.38, 0.90),
                                    level=float(db(B.rng.uniform(*lvl)))), B.rng.uniform(-0.9, 0.9)))
    return items


def bed_forest_night(B):
    items = crickets(B, 11, (2350, 3300), (-14, -2))
    for _ in range(2):
        items.append((trill_track(B, B.rng.uniform(1950, 2450), B.rng.uniform(34, 46), float(db(-7))),
                      B.rng.uniform(-0.8, 0.8)))
    B.add(unit(chorus(B, items)), -1)
    gl = B.lfo(0.08)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.6, 55, 180, 580, gust_db=7)
    B.add(body, -6)
    B.add(reed_chorus(B, gust, 190.0, 560.0, color=1.6), -11)


def insect_hums(B, count, fband=(190, 380), cut=620):
    """Warm low-passed harmonic bee/meadow hum (sine + triangle partials, low-passed at 180-650 Hz)."""
    tr = B.track()
    for _ in range(count):
        f0 = B.rng.uniform(*fband) * (1 + 0.018 * B.smooth(3.0))
        ph = loop_phase(f0, B.sr) + B.rng.uniform(0, TAU)
        x = np.sin(ph) + 0.25 * np.sin(2 * ph) + 0.08 * np.sin(3 * ph)
        presence = (0.5 - 0.5 * np.cos(np.pi * np.clip(B.lfo(0.12, 0.9)(B.t), 0, 1))) ** 1.5
        tr += B.pan(x * presence, B.rng.uniform(-0.85, 0.85))
    return unit(shape(tr, B.sr, lambda f: g_lp(f, cut, 3) * g_hp(f, 150, 2)))


def bed_plains_day(B):
    gl = B.lfo(0.10)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.5, 55, 180, 640, gust_db=8)
    B.add(body, 0)
    B.add(reed_chorus(B, gust, 190.0, 620.0, color=1.5), -6)
    B.add(insect_hums(B, 4, (190, 360), 620), -13)
    B.add(distant_chirps(B, 3 * B.secs, (1650, 2950)), -12)


def bed_plains_night(B):
    items = crickets(B, 8, (2300, 3200), (-15, -2))
    for _ in range(3):
        items.append((katydid_track(B, B.rng.uniform(4200, 5800), B.rng.uniform(0.95, 1.65),
                                    level=float(db(B.rng.uniform(-14, -5))),
                                    active=lambda t, a=B.lfo(0.08): (a(t) > -0.2).astype(float)), B.rng.uniform(-0.85, 0.85)))
    B.add(unit(chorus(B, items, 0.85, 0.3)), 0)
    gl = B.lfo(0.08)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.5, 55, 180, 580, gust_db=7)
    B.add(body, -8)
    B.add(reed_chorus(B, gust, 180.0, 540.0, color=1.6), -12)


def lapping(B, rate, lvl=0.0):
    tr = B.track()
    tt = 0.0
    while tt < B.secs:
        dur = B.rng.uniform(0.22, 0.46)
        m = int(B.sr * dur)
        y = filt(B.rng.standard_normal(m), B.sr, "bandpass", [180, B.rng.uniform(420, 640)], 2) * win_grain(m, 0.45)
        for _ in range(B.rng.integers(1, 3)):
            place(y, 0.28 * bubble(B.sr, B.rng.uniform(360, 980)) * rms(y) / 0.1, int(B.rng.integers(0, m // 2)))
        B.put(tr, y, tt, B.rng.uniform(-0.7, 0.7), db(B.rng.uniform(-8, 0)))
        tt += B.rng.exponential(1.0 / rate) + 0.06
    return unit(B.verb(tr, 0.65, 0.3))


def frog_texture(B, calls, kinds, lvl=(-12, 0), pitch=(0.85, 1.15), bout=(1, 5), gap=(0.18, 0.55)):
    """Many individual frogs calling in bouts at random positions with smooth cosine envelopes."""
    tr = B.track()
    placed = 0
    while placed < calls:
        kind = kinds[B.rng.integers(len(kinds))]
        pitch_i = B.rng.uniform(*pitch)
        pan = B.rng.uniform(-0.9, 0.9)
        lv = db(B.rng.uniform(*lvl))
        tt = B.rng.uniform(0, B.secs)
        for _ in range(B.rng.integers(bout[0], bout[1] + 1)):
            g = frog_call(B.rng, B.sr, kind, pitch_i)
            g = g * win_grain(len(g), 0.35)
            g = g / (np.max(np.abs(g)) + 1e-9)
            B.put(tr, g, tt, pan, lv)
            tt += len(g) / B.sr + B.rng.uniform(*gap)
            placed += 1
    return shape(tr, B.sr, lambda f: g_lp(f, 2400, 3) * g_hp(f, 140, 2))


def midge_drone(B, count=8):
    """Warm harmonic wetland hum in 200-520 Hz (pure sine/triangle harmonics, zero saw buzz)."""
    tr = B.track()
    for _ in range(count):
        f0 = B.rng.uniform(210, 420) * (1 + 0.015 * B.smooth(2.5))
        ph = loop_phase(f0, B.sr) + B.rng.uniform(0, TAU)
        tone_wave = np.sin(ph) + 0.22 * np.sin(2 * ph) + 0.06 * np.sin(3 * ph)
        tr += B.pan(tone_wave * B.lfo(0.10).u(B.t, 1.8), B.rng.uniform(-0.85, 0.85))
    return unit(shape(tr, B.sr, lambda f: g_lp(f, 640, 3) * g_hp(f, 180, 2)))


def bed_marsh_day(B):
    gl = B.lfo(0.08)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.5, 55, 180, 620, gust_db=7)
    B.add(body, -4)
    B.add(reed_chorus(B, gust, 185.0, 610.0, color=1.5), -7)
    B.add(lapping(B, 1.4), -1)
    B.add(midge_drone(B, 6), -12)
    fr = frog_texture(B, 3 * B.secs // 2, ["croak", "kruk"], (-14, -4), bout=(1, 4), gap=(0.35, 0.85))
    fr = shape(fr, B.sr, lambda f: g_lp(f, 1650, 3))
    B.add(unit(B.verb(fr, 1.2, 0.75, 0.5)), -8)


def bed_marsh_night(B):
    fr = frog_texture(B, 9 * B.secs, ["croak", "kruk", "krr", "creek", "peep"], (-15, -1))
    items = crickets(B, 6, (2350, 3250), (-15, -4))
    cr = chorus(B, items)
    B.add(unit(B.verb(fr, 1.2, 0.35)), 0)
    B.add(unit(cr), -6)
    B.add(lapping(B, 0.9), -8)
    gl = B.lfo(0.07)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.5, 55, 180, 580, gust_db=6)
    B.add(body, -10)
    B.add(reed_chorus(B, gust, 180.0, 540.0, color=1.6), -13)


def cicada_layer(B, fc, rate, q=3.5, swell_f=0.10, depth=0.75):
    """Warm harmonic cicada chorus: pure sine/triangle harmonic chord modulated by a smooth cosine pulse and slow swell."""
    f0 = np.clip(fc * 0.38, 1450.0, 2550.0) * (1 + 0.003 * B.smooth(2.2))
    ph = loop_phase(f0, B.sr) + B.rng.uniform(0, TAU)
    carrier = np.sin(ph) + 0.18 * np.sin(2 * ph) + 0.05 * np.sin(3 * ph)
    mod_rate = np.clip(rate * 0.35, 32.0, 72.0)
    am = 1.0 - depth * (0.5 - 0.5 * np.cos(loop_phase(np.full(B.n, mod_rate), B.sr)))
    env = B.lfo(swell_f, 0.8).u(B.t, 1.8)
    x = shape(carrier * am * env, B.sr, lambda f: g_lp(f, 3200, 3) * g_hp(f, 900, 2))
    return unit(x)


def leaf_drips(B, rate):
    """Pure sine/triangle water droplets with smooth cosine fade-in/out envelopes (zero click static)."""
    tr = B.track()
    count = int(rate * B.secs)
    for _ in range(count):
        f = B.rng.uniform(950, 2400)
        dur = B.rng.uniform(0.035, 0.075)
        m = int(B.sr * dur)
        t = np.arange(m) / B.sr
        env = win_grain(m, 0.28) * np.exp(-t / (dur * 0.45))
        ph = TAU * f * t * (1 + 0.18 * t / dur)
        g = (np.sin(ph) + 0.1 * np.sin(2 * ph)) * env
        B.put(tr, g, B.rng.uniform(0, B.secs), B.rng.uniform(-0.9, 0.9), db(B.rng.uniform(-14, -2)))
    return unit(B.verb(tr, 1.1, 0.5))


def bed_jungle_day(B):
    gl = B.lfo(0.09)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.5, 55, 180, 630, gust_db=7)
    B.add(body, -3)
    B.add(reed_chorus(B, gust, 195.0, 640.0, color=1.5), -6)
    for fc, rate, lv in ((4400, 140, -5), (5200, 115, -7), (3800, 165, -8)):
        x = cicada_layer(B, fc * B.rng.uniform(0.96, 1.04), rate * B.rng.uniform(0.95, 1.05))
        B.add(B.pan(x, B.rng.uniform(-0.7, 0.7)), lv)
    B.add(leaf_drips(B, 3.5), -8)
    B.add(distant_chirps(B, 5 * B.secs, (1400, 2850), (0.05, 0.13)), -8)
    B.add(insect_hums(B, 3, (190, 320), 600), -14)


def bed_jungle_night(B):
    fr = frog_texture(B, 6 * B.secs, ["peep", "tink", "krr", "peep"], (-14, -1), pitch=(0.88, 1.22),
                      bout=(2, 7), gap=(0.12, 0.36))
    B.add(unit(B.verb(fr, 1.3, 0.45)), 0)
    for fc, rate, lv in ((3600, 110, -7), (4300, 90, -10)):
        x = cicada_layer(B, fc * B.rng.uniform(0.96, 1.04), rate, q=4.0, swell_f=0.09)
        B.add(B.pan(x, B.rng.uniform(-0.7, 0.7)), lv)
    kat = [(katydid_track(B, B.rng.uniform(4400, 6200), B.rng.uniform(0.6, 1.2), (2, 4),
                          level=float(db(B.rng.uniform(-9, -1)))), B.rng.uniform(-0.85, 0.85)) for _ in range(4)]
    tri = [(trill_track(B, B.rng.uniform(2100, 2750), B.rng.uniform(36, 54), float(db(-6))), B.rng.uniform(-0.8, 0.8))
           for _ in range(2)]
    B.add(unit(chorus(B, kat + tri, 1.2, 0.4)), -4)
    gl = B.lfo(0.08)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.6, 50, 180, 580, gust_db=6)
    B.add(body, -8)
    B.add(reed_chorus(B, gust, 185.0, 550.0, color=1.6), -11)


def bed_desert_wind(B):
    gl = B.lfo(0.14, 0.9)
    gust = lambda t: gl.u(t, 1.2)  # noqa: E731
    g = gust(B.t)
    body, _ = wind_layer(B, gust, 1.0, 120, 1500, 3200, gust_db=10)
    hollow = [(lambda tc, b=b, d=B.lfo(0.05): b * (1 + 0.03 * d(tc)), 40, lambda tc: gust(tc) ** 2) for b in (190, 287)]
    _, hol = wind_layer(B, gust, 1.0, 120, 1500, 3200, hollow)
    B.add(body, 0)
    B.add(hol, -12)
    B.add(B.crackle(1500 + 9000 * g ** 2, 3500, 10000) * (0.15 + 0.85 * g ** 2), -11)
    B.add(B.noise(0.0, 4000, 10000, corr=0.1) * g ** 2, -20)


def drone(B, freqs, h=(1.0, 0.3, 0.1), det=0.25):
    """Slow beating drone: each partial a pair of slightly detuned loop-closing sines."""
    x = np.zeros(B.n)
    for f in freqs:
        for d in (-det / 2, det / 2):
            x += B.tone_loop(f + d, h) * (0.7 + 0.3 * B.lfo(0.05).u(B.t))
    return unit(x)


def bed_ash_storm(B):
    gl = B.lfo(0.16, 0.9)
    gust = lambda t: gl.u(t)  # noqa: E731
    g = gust(B.t)
    body, wh = wind_layer(B, gust, 1.8, 30, 220, 700, whistle_set(B, gust, [270, 410], 20, amp_floor=0.3), gust_db=8)
    grit = B.crackle(3000 + 6000 * g, 1200, 5000, heavy=1.0) * (0.4 + 0.6 * g)
    B.add(body, 0)
    B.add(wh, -14)
    B.add(grit, -10)
    B.add(B.noise(0.3, 2000, 6000, corr=0.15) * g, -18)
    B.add(drone(B, [41.2, 58.3], (1.0, 0.4, 0.15)), -16)
    B.mix = B.verb(B.mix, 2.0, 0.3, 1.0, damp=0.8)


def bed_coast_waves(B):
    rng, sr, n = B.rng, B.sr, B.n
    k = int(round(B.secs / 7.5))
    iv = rng.uniform(6.2, 9.5, k)
    iv *= B.secs / iv.sum()
    starts = np.cumsum(iv) - iv[0] + rng.uniform(0, 2)
    env = {c: B.track() for c in ("rumble", "crash", "wash", "retreat")}
    fizz = B.track()
    m = int(11.5 * sr)
    tau = np.arange(m) / sr - 2.5
    for s, inten in zip(starts, rng.uniform(0.6, 1.0, k)):
        pan = rng.uniform(-0.6, 0.6)
        travel = rng.choice([-1, 1]) * rng.uniform(0.08, 0.3)
        dw = rng.uniform(1.6, 2.6)
        shapes = {
            "rumble": np.where(tau < 0, np.clip(1 + tau / 2.5, 0, 1) ** 2, np.exp(-tau / 1.2)),
            "crash": np.where(tau < 0, np.exp(np.minimum(tau, 0) / 0.07), np.exp(-tau / rng.uniform(0.4, 0.7))),
            "wash": np.where(tau < 0.1, 0.0, (1 - np.exp(-(tau - 0.1) / 0.35)) * np.exp(-(tau - 0.1) / dw)),
            "retreat": 0.6 * np.exp(-((tau - 3.2 - dw) / 1.3) ** 2),
        }
        for ch in (0, 1):
            gch = inten * np.sqrt(2) * (np.cos if ch == 0 else np.sin)((pan + 1) * np.pi / 4)
            pos = int((s - 2.5 + (travel if ch == 1 else 0.0)) * sr)
            for c, e in shapes.items():
                place_circ(env[c][ch], e * gch, pos)
            place_circ(fizz[ch], shapes["wash"] * gch, pos)
    nl = B.noise(1.6, 30, 260, corr=0.4)
    nm = B.noise(0.6, 150, 4500, corr=0.2)
    nh = B.noise(0.2, 1500, 9000, corr=0.1)
    nr = B.noise(0.5, 400, 3500, corr=0.2)
    dens = np.mean(fizz, axis=0)
    bub = B.track()
    cnt = 0
    p = dens / dens.sum()
    times = rng.choice(n, size=int(110 * B.secs), p=p)
    for ti in times:
        f0 = np.exp(rng.uniform(np.log(1500), np.log(5000)))
        B.put(bub, bubble(sr, f0), ti / sr, rng.uniform(-0.6, 0.6), db(rng.uniform(-14, 0)))
        cnt += 1
    B.add(env["rumble"] * nl, -3)
    B.add(env["crash"] * nm, 0)
    B.add(env["wash"] * nh, -3)
    B.add(env["retreat"] * nr, -10)
    B.add(bub, -19)
    B.add(B.noise(1.2, 60, 700, corr=0.3), -17)
    B.mix = B.verb(B.mix, 1.2, 0.2, 1.0, damp=0.7)


def bed_open_ocean(B):
    rng, sr = B.rng, B.sr
    k = max(2, int(round(B.secs / 8.0)))
    wob = B.lfo(0.1)
    sw = 0.5 + 0.5 * np.cos(TAU * k * B.t / B.secs + 0.8 * wob(B.t))
    sw = sw * (0.75 + 0.25 * B.lfo(0.06).u(B.t))
    gl = B.lfo(0.12)
    gust = lambda t: gl.u(t)  # noqa: E731
    B.add(B.noise(1.8, 25, 200, corr=0.5) * (0.35 + 0.65 * sw), 0)
    B.add(B.noise(0.8, 150, 1000, corr=0.25) * (0.2 + 0.8 * sw ** 2), -5)
    body, _ = wind_layer(B, gust, 0.8, 300, 2500, 5000, gust_db=8)
    B.add(body, -10)
    B.add(B.noise(0.0, 3000, 9000, corr=0.1) * sw ** 3, -20)
    slaps = B.track()
    tt = 0.0
    while tt < B.secs:
        m = int(sr * rng.uniform(0.12, 0.3))
        y = filt(rng.standard_normal(m), sr, "lowpass", rng.uniform(350, 1100), 2) * win_grain(m, 0.2) ** 2
        B.put(slaps, y, tt, rng.uniform(-0.9, 0.9), db(rng.uniform(-10, 0)) * (0.3 + float(sw[int(tt * sr) % B.n])))
        tt += rng.exponential(0.35)
    B.add(unit(B.verb(slaps, 0.8, 0.3)), -11)


def bed_river(B):
    rng, sr = B.rng, B.sr
    B.add(B.noise(0.9, 120, 3500, corr=0.25) * (1 + 0.12 * B.lfo(0.3)(B.t)), 0)
    bub = B.track()
    nb = int(380 * B.secs)
    ts = rng.uniform(0, B.secs, nb)
    f0s = np.exp(rng.uniform(np.log(260), np.log(2600), nb))
    for t0, f0 in zip(ts, f0s):
        B.put(bub, bubble(sr, f0, xi=rng.uniform(0.05, 0.2)), t0, rng.uniform(-0.85, 0.85),
              db(rng.uniform(-16, 0)) * (600 / f0) ** 0.3)
    B.add(unit(B.verb(bub, 0.5, 0.2)), -3)
    gurg = B.noise(1.2, 60, 450, corr=0.3) * np.clip(0.5 + B.smooth(5.0), 0, None)
    B.add(gurg, -8)


def cave_drips(B, count, fband=(700, 2300), lvl=(-12, 0)):
    tr = B.track()
    for _ in range(count):
        g = bubble(B.sr, np.exp(B.rng.uniform(np.log(fband[0]), np.log(fband[1]))), xi=B.rng.uniform(0.15, 0.35))
        g[:10] += B.rng.standard_normal(10) * 0.3 * np.linspace(1, 0, 10)
        B.put(tr, g, B.rng.uniform(0, B.secs), B.rng.uniform(-0.9, 0.9), db(B.rng.uniform(*lvl)))
    return tr


def bed_cave(B):
    B.add(B.noise(2.0, 22, 75, corr=0.6) * (0.8 + 0.2 * B.lfo(0.06).u(B.t)), 0)
    air = B.noise(1.0, 150, 700, corr=0.2) * B.lfo(0.08).u(B.t, 2.0)
    air = B.verb(air, 2.2, 0.6, 0.6, damp=0.8)
    B.add(unit(air), -3)
    B.add(B.noise(0.5, 1800, 5000, corr=0.1) * B.lfo(0.1).u(B.t, 3.0), -27)
    dr = cave_drips(B, 22, lvl=(-14, 0))
    dr = shape(dr, B.sr, lambda f: g_lp(f, 3500, 2))
    B.add(unit(B.verb(dr, 2.6, 1.0, 0.15, predelay=0.02, damp=0.7)), -12)


def bed_cave_deep(B):
    B.add(B.noise(2.0, 16, 48, corr=0.7) * (0.75 + 0.25 * B.lfo(0.05).u(B.t)), -4)
    B.add(drone(B, [32.7, 65.4], (1.0, 0.2), det=0.18), -14)
    surge = np.clip(B.lfo(0.1, 0.8)(B.t), 0, None) ** 2.5
    rum = B.noise(1.6, 40, 260, corr=0.5) * (0.15 + surge)
    B.add(unit(B.verb(rum, 3.0, 0.7, 0.7, damp=0.9)), 0)
    B.add(B.noise(1.0, 100, 450, corr=0.2) * B.lfo(0.06).u(B.t, 2.0), -11)
    dr = shape(cave_drips(B, 9, (500, 1400), (-14, 0)), B.sr, lambda f: g_lp(f, 2200, 2))
    B.add(unit(B.verb(dr, 3.5, 1.0, 0.1, predelay=0.04, damp=0.8)), -18)


def bed_glow_cave(B):
    rng = B.rng
    hum = np.zeros(B.n)
    for f, a in ((55.0, 1.0), (82.4, 0.7), (110.3, 0.5), (164.6, 0.35), (165.15, 0.3), (246.9, 0.15)):
        hum += a * B.tone_loop(f) * B.lfo(0.07).u(B.t, 1.5)
    B.add(B.verb(unit(hum), 4.0, 0.5), -6)

    def airy(tc, f):
        G = np.zeros((len(tc), len(f)))
        for fc in (440.0, 659.3, 880.0, 1318.5, 1975.0):
            G += B.lfo(0.08).u(tc, 2.0)[:, None] * g_bp(f[None, :], fc, 45)
        return G
    B.add(B.tv(airy, 3840, 0.2), -6)
    glass = B.track()
    for _ in range(26):
        f0 = rng.uniform(1200, 3600)
        g = modal(rng, B.sr, 2.5, [(f0, 1.0, rng.uniform(0.6, 1.6)), (f0 * 2.76, 0.4, 0.5), (f0 * 5.4, 0.15, 0.25)],
                  beat=2.0, att=0.003)
        B.put(glass, g, rng.uniform(0, B.secs), rng.uniform(-0.9, 0.9), db(rng.uniform(-16, 0)))
    B.add(unit(B.verb(glass, 4.5, 1.0, 0.2, damp=0.3, bright=9000)), -12)
    B.add(B.noise(1.0, 200, 3000, corr=0.2) * B.lfo(0.08).u(B.t, 2.0), -18)
    B.add(B.noise(2.0, 20, 70, corr=0.6), -10)
    B.mix = B.verb(B.mix, 4.5, 0.45, 1.0, damp=0.5)


def bed_oblivion(B):
    rng, sr = B.rng, B.sr
    lava = B.track()
    for _ in range(int(26 * B.secs)):
        f0 = np.exp(rng.uniform(np.log(45), np.log(190)))
        B.put(lava, bubble(sr, f0, xi=rng.uniform(0.2, 0.6), damp=0.6), rng.uniform(0, B.secs),
              rng.uniform(-0.8, 0.8), db(rng.uniform(-12, 0)))
    for _ in range(int(4 * B.secs)):
        m = int(sr * rng.uniform(0.08, 0.25))
        y = filt(rng.standard_normal(m), sr, "lowpass", rng.uniform(180, 480), 2) * win_grain(m, 0.3) ** 2
        B.put(lava, y * 0.1, rng.uniform(0, B.secs), rng.uniform(-0.8, 0.8), db(rng.uniform(-10, 0)))
    B.add(unit(B.verb(lava, 1.5, 0.4)), -3)
    surge = B.lfo(0.1).u(B.t, 1.5)
    B.add(B.noise(2.0, 20, 110, corr=0.5) * (0.4 + 0.6 * surge), 0)
    gl = B.lfo(0.15)
    gust = lambda t: gl.u(t, 1.5)  # noqa: E731
    _, howl = wind_layer(B, gust, 1.5, 100, 400, 900, whistle_set(B, gust, [380, 520, 770], 26, spread=0.18, amp_floor=0.2))
    B.add(B.verb(howl, 3.0, 0.6), -9)
    B.add(drone(B, [49.0, 69.3, 98.6], (1.0, 0.5, 0.25, 0.1), det=0.4), -12)
    embers = shape(B.crackle(400 + 900 * surge, 1500, 5000), B.sr, lambda f: g_lp(f, 4000, 2))
    B.add(embers * (0.3 + 0.7 * surge), -22)
    B.mix = B.verb(B.mix, 3.0, 0.3, 1.0, damp=0.8)


def bed_sovngarde(B):
    rng = B.rng
    gl = B.lfo(0.08)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, wh = wind_layer(B, gust, 1.3, 60, 450, 900, whistle_set(B, gust, [700, 1150], 30), gust_db=6)
    B.add(body, 0)
    B.add(wh, -16)
    # very faint distant choir: two open chords crossfading twice per loop
    chords = ([146.83, 220.0, 293.66, 440.0], [116.54, 174.61, 293.66, 349.23])
    xfade = 0.5 + 0.5 * np.cos(TAU * 2 * B.t / B.secs)
    pad = np.zeros(B.n)
    vib = np.sin(loop_phase(np.full(B.n, 5.2), B.sr))
    for ci, chord in enumerate(chords):
        w = xfade if ci == 0 else 1 - xfade
        for f in chord:
            for det in (-0.004, 0.0, 0.0045):
                fv = f * (1 + det) * (1 + 0.004 * vib * rng.uniform(0.6, 1.0) + 0.002 * B.smooth(1.5))
                pad += w * B.osc_saw(fv) * (0.8 + 0.2 * B.lfo(0.1).u(B.t))
    pad = shape(pad, B.sr, lambda f: (g_bp(f, 700, 3) + 0.7 * g_bp(f, 1150, 5) + 0.25 * g_bp(f, 2700, 7)) * g_lp(f, 3500, 2)
                * g_hp(f, 90, 2))
    pad = B.verb(unit(pad), 5.5, 0.9, 0.2, damp=0.5, predelay=0.05)
    B.add(unit(pad), -15)
    B.add(B.noise(0.6, 2500, 7000, corr=0.1) * B.lfo(0.1).u(B.t, 2.0), -27)


def bed_swamp_night(B):
    """Warm swamps (Black Marsh, the Bitter Coast, Blackwood) after dark: a warm frog chorus over gentle reeds and insects."""
    fr = frog_texture(B, 11 * B.secs, ["croak", "croak", "kruk", "krr", "creek", "peep"], (-15, -1), pitch=(0.82, 1.12),
                      bout=(2, 6), gap=(0.14, 0.42))
    B.add(unit(B.verb(fr, 1.4, 0.4)), 0)
    x = cicada_layer(B, 3600 * B.rng.uniform(0.96, 1.04), 95, q=4.0, swell_f=0.08)
    B.add(B.pan(x, B.rng.uniform(-0.6, 0.6)), -9)
    kat = [(katydid_track(B, B.rng.uniform(4200, 6000), B.rng.uniform(0.65, 1.25), (2, 4),
                          level=float(db(B.rng.uniform(-9, -1)))), B.rng.uniform(-0.85, 0.85)) for _ in range(3)]
    B.add(unit(chorus(B, kat, 1.0, 0.3)), -8)
    B.add(midge_drone(B, 6), -14)
    B.add(lapping(B, 0.8), -9)
    gl = B.lfo(0.07)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.6, 48, 180, 560, gust_db=6)
    B.add(body, -9)
    B.add(reed_chorus(B, gust, 180.0, 540.0, color=1.6), -11)


def bed_savanna_day(B):
    """Hot grassland by day: warm low-passed breeze, gentle dry-reed chorus modulation, and harmonic cicada/bird calls."""
    gl = B.lfo(0.09)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, _ = wind_layer(B, gust, 1.5, 55, 180, 650, gust_db=8)
    B.add(body, -1)
    B.add(reed_chorus(B, gust, 195.0, 645.0, color=1.4), -6)
    for fc, rate, lv in ((4600, 130, -6), (5400, 160, -9)):
        x = cicada_layer(B, fc * B.rng.uniform(0.96, 1.04), rate * B.rng.uniform(0.96, 1.04), q=3.8, swell_f=0.09, depth=0.75)
        B.add(B.pan(x, B.rng.uniform(-0.7, 0.7)), lv)
    B.add(insect_hums(B, 3, (190, 350), 620), -14)
    B.add(distant_chirps(B, 3 * B.secs, (1650, 3050)), -11)


def bed_hot_springs(B):
    """Eastmarch's hot springs: bubbling pools, steam and a light wind."""
    rng, sr = B.rng, B.sr
    bub = B.track()
    for _ in range(int(60 * B.secs)):
        f0 = np.exp(rng.uniform(np.log(150), np.log(900)))
        B.put(bub, bubble(sr, f0, xi=rng.uniform(0.1, 0.4), damp=0.8), rng.uniform(0, B.secs), rng.uniform(-0.8, 0.8),
              db(rng.uniform(-14, 0)))
    B.add(unit(B.verb(bub, 0.6, 0.3)), 0)
    blub = B.track()
    for _ in range(int(3 * B.secs)):
        B.put(blub, bubble(sr, np.exp(rng.uniform(np.log(60), np.log(160))), xi=0.4, damp=0.5), rng.uniform(0, B.secs),
              rng.uniform(-0.6, 0.6), db(rng.uniform(-8, 0)))
    B.add(unit(B.verb(blub, 0.8, 0.3)), -6)
    B.add(B.noise(0.2, 2500, 9000, corr=0.2) * B.lfo(0.1).u(B.t, 2.0), -12)
    gl = B.lfo(0.1)
    body, _ = wind_layer(B, lambda t: gl.u(t), 1.4, 60, 400, 1100, gust_db=8)
    B.add(body, -12)


def bed_volcanic_rumble(B):
    """Red Mountain, Molag Amur, lava caves: the ground's slow surging rumble, distant booms and venting gas."""
    rng, sr = B.rng, B.sr
    surge = np.clip(B.lfo(0.08, 0.8)(B.t), 0, None) ** 2
    B.add(B.noise(2.0, 16, 90, corr=0.7) * (0.4 + 0.6 * B.lfo(0.05).u(B.t)), 0)
    rum = B.noise(1.6, 30, 220, corr=0.5) * (0.2 + surge)
    B.add(unit(B.verb(rum, 2.5, 0.6, 0.7, damp=0.9)), -2)
    booms = B.track()
    for _ in range(int(B.secs / 9) + 1):
        m = int(sr * 1.6)
        t = np.arange(m) / sr
        y = np.sin(TAU * np.cumsum(36 * (1 - 0.3 * t / 1.6)) / sr) * np.exp(-t / 0.45) * (1 - np.exp(-t / 0.02))
        y = y + 0.6 * filt(rng.standard_normal(m), sr, "lowpass", 180, 2) * np.exp(-t / 0.3)
        B.put(booms, y, rng.uniform(0, B.secs), rng.uniform(-0.7, 0.7), db(rng.uniform(-6, 0)))
    B.add(unit(B.verb(booms, 2.5, 0.7, damp=0.9)), -6)
    B.add(B.noise(0.3, 1500, 7000, corr=0.2) * B.lfo(0.12).u(B.t, 3.0), -17)
    B.add(drone(B, [29.1, 43.6], (1.0, 0.4)), -13)
    B.add(shape(B.crackle(150 + 500 * surge, 1500, 5000), B.sr, lambda f: g_lp(f, 4000, 2)), -22)


def bed_crystal_shimmer(B):
    """Crystal land (Summerset's crystal coast and spires, glassland realms): glassy chimes and singing air."""
    rng = B.rng
    glass = B.track()
    for _ in range(34):
        f0 = np.exp(rng.uniform(np.log(1500), np.log(5000)))
        g = modal(rng, B.sr, 2.5, [(f0, 1.0, rng.uniform(0.8, 1.8)), (f0 * 2.76, 0.35, 0.6), (f0 * 5.4, 0.12, 0.3)],
                  beat=2.0, att=0.003)
        B.put(glass, g, rng.uniform(0, B.secs), rng.uniform(-0.9, 0.9), db(rng.uniform(-16, 0)))
    B.add(unit(B.verb(glass, 4.0, 1.0, 0.25, damp=0.3, bright=9000)), -4)

    def airy(tc, f):
        G = np.zeros((len(tc), len(f)))
        for fc in (1760.0, 2637.0, 3520.0, 5274.0):
            G += B.lfo(0.08).u(tc, 2.0)[:, None] * g_bp(f[None, :], fc, 60)
        return G
    B.add(B.tv(airy, 3840, 0.2), -6)
    gl = B.lfo(0.1)
    body, _ = wind_layer(B, lambda t: gl.u(t), 1.2, 80, 300, 900, gust_db=8)
    B.add(body, -10)
    pad_ = sum(B.tone_loop(f) * B.lfo(0.06).u(B.t, 1.5) for f in (440.0, 660.5, 880.7))
    B.add(unit(pad_), -18)
    B.mix = B.verb(B.mix, 3.0, 0.3, 1.0, damp=0.3)


def bed_dwemer_machinery(B):
    """Dwemer caverns and ruins: the deep hum of the old machines, pistons, ratchets and venting steam."""
    rng, sr = B.rng, B.sr
    B.add(drone(B, [49.0, 98.2, 147.1], (1.0, 0.5, 0.3, 0.2), det=0.3), -4)
    k = max(1, B.secs // 2)
    period = B.secs / k
    pist = B.track()
    clank = B.track()
    for i in range(k):
        m = int(sr * 0.6)
        t = np.arange(m) / sr
        th = np.sin(TAU * np.cumsum(55 * (1 - 0.3 * t / 0.6)) / sr) * np.exp(-t / 0.12) * (1 - np.exp(-t / 0.005))
        th = th + 0.5 * filt(rng.standard_normal(m), sr, "lowpass", 400, 2) * np.exp(-t / 0.05)
        B.put(pist, th, i * period + 0.37 * period, -0.3, 1.0)   # (off the loop seam)
        if i % 3 != 2:
            f0 = rng.uniform(300, 700)
            hit = modal(rng, sr, 0.8, [(f0, 1.0, 0.25), (f0 * 1.594, 0.6, 0.18), (f0 * 2.136, 0.4, 0.12), (f0 * 2.653, 0.3, 0.08)],
                        att=0.0008)
            B.put(clank, hit, i * period + period * 0.82, 0.4, db(rng.uniform(-6, 0)))
    B.add(unit(B.verb(pist, 1.5, 0.5)), -3)
    B.add(unit(B.verb(clank, 2.2, 0.6)), -12)
    steam = B.track()
    for _ in range(int(B.secs / 6) + 1):
        m = int(sr * rng.uniform(0.5, 1.3))
        y = filt(rng.standard_normal(m), sr, "highpass", 1500, 2) * win_grain(m, 0.08)
        B.put(steam, y, rng.uniform(0, B.secs), rng.uniform(-0.9, 0.9), db(rng.uniform(-6, 0)))
    B.add(unit(B.verb(steam, 1.8, 0.4)), -14)
    tick = B.track()
    tt = 0.05
    while tt < B.secs:
        f0 = rng.uniform(2200, 3200)
        B.put(tick, modal(rng, sr, 0.05, [(f0, 1.0, 0.008), (f0 * 1.6, 0.5, 0.005)], att=0.0004), tt, 0.6, db(rng.uniform(-6, 0)))
        tt += 1 / 6.0
    B.add(unit(tick), -22)
    B.add(B.noise(2.0, 20, 70, corr=0.6), -12)
    B.mix = B.verb(B.mix, 2.5, 0.35, 1.0, damp=0.7)


def bed_lava_cave(B):
    """Lava caves: slow, heavy bubbling, hissing rock and the cave's hot air."""
    rng, sr = B.rng, B.sr
    lava = B.track()
    for _ in range(int(14 * B.secs)):
        f0 = np.exp(rng.uniform(np.log(40), np.log(170)))
        B.put(lava, bubble(sr, f0, xi=rng.uniform(0.2, 0.6), damp=0.6), rng.uniform(0, B.secs), rng.uniform(-0.8, 0.8),
              db(rng.uniform(-12, 0)))
    B.add(unit(B.verb(lava, 2.0, 0.5)), 0)
    B.add(shape(B.crackle(300 + 400 * B.lfo(0.1).u(B.t), 1500, 6000), B.sr, lambda f: g_lp(f, 5000, 2)), -16)
    B.add(B.noise(0.4, 1800, 6500, corr=0.2) * B.lfo(0.1).u(B.t, 3.0), -18)
    B.add(B.noise(2.0, 18, 80, corr=0.6) * (0.7 + 0.3 * B.lfo(0.05).u(B.t)), -6)
    B.mix = B.verb(B.mix, 2.6, 0.3, 1.0, damp=0.8)


def bed_ice_cave(B):
    """Ice caves (and, faintly, glaciers): hollow wind moaning through the tunnels, groaning ice, cold drips."""
    rng = B.rng
    gl = B.lfo(0.08)
    gust = lambda t: gl.u(t)  # noqa: E731
    body, wh = wind_layer(B, gust, 1.6, 40, 200, 600, whistle_set(B, gust, [220, 330, 495], 40, spread=0.06, amp_floor=0.35),
                          gust_db=6)
    B.add(body, -4)
    B.add(B.verb(wh, 3.0, 0.6), -6)
    groans = B.track()
    for _ in range(int(B.secs / 7) + 1):
        bank = [(f, 40, a) for f, a in ((310, 1.0), (720, 0.7), (1340, 0.45), (2150, 0.25))]
        y = pulse_voice(rng, B.sr, rng.uniform(1.0, 1.8), [(0, rng.uniform(15, 25)), (0.5, rng.uniform(40, 70)), (1, 20)], bank,
                        env=[(0, 0), (0.2, 1), (0.8, 0.7), (1, 0)], jitter=0.15, amp_jit=0.5)
        B.put(groans, y, rng.uniform(0, B.secs), rng.uniform(-0.8, 0.8), db(rng.uniform(-8, 0)))
    B.add(unit(B.verb(groans, 3.0, 0.8, 0.4, damp=0.5)), -8)
    dr = cave_drips(B, 16, (1200, 3200), (-14, 0))
    B.add(unit(B.verb(dr, 3.0, 1.0, 0.2, predelay=0.02, damp=0.4, bright=9000)), -12)
    tink = B.track()
    for _ in range(18):
        f0 = rng.uniform(3000, 7000)
        B.put(tink, modal(rng, B.sr, 0.6, [(f0, 1.0, 0.15), (f0 * 2.76, 0.3, 0.06)], att=0.001), rng.uniform(0, B.secs),
              rng.uniform(-0.9, 0.9), db(rng.uniform(-14, 0)))
    B.add(unit(B.verb(tink, 2.5, 0.8)), -20)


def bed_mushroom_grotto(B):
    """Mushroom grottos and Telvanni fungus: damp, softly glowing caverns - wet drips, spore puffs, a gentle hum."""
    rng, sr = B.rng, B.sr
    hum = np.zeros(B.n)
    for f, a in ((73.4, 1.0), (110.0, 0.6), (146.8, 0.4), (220.2, 0.2)):
        hum += a * B.tone_loop(f) * B.lfo(0.06).u(B.t, 1.5)
    B.add(B.verb(unit(hum), 3.0, 0.4), -10)
    dr = cave_drips(B, 30, (600, 2000), (-14, 0))
    B.add(unit(B.verb(dr, 2.2, 0.9, 0.3, predelay=0.015, damp=0.6)), -4)
    puffs = B.track()
    for _ in range(int(1.2 * B.secs)):
        m = int(sr * rng.uniform(0.08, 0.2))
        y = filt(rng.standard_normal(m), sr, "bandpass", [rng.uniform(300, 600), rng.uniform(1500, 3000)], 2) * win_grain(m, 0.2) ** 2
        B.put(puffs, y, rng.uniform(0, B.secs), rng.uniform(-0.9, 0.9), db(rng.uniform(-12, 0)))
    B.add(unit(B.verb(puffs, 1.6, 0.6)), -12)
    B.add(B.noise(1.0, 150, 900, corr=0.2) * B.lfo(0.08).u(B.t, 2.0), -12)
    B.add(B.noise(2.0, 20, 70, corr=0.6), -10)
    B.add(unit(B.verb(lapping(B, 0.4), 1.5, 0.5)), -16)
    B.mix = B.verb(B.mix, 2.4, 0.3, 1.0, damp=0.6)


# name: (generator, loop seconds[, stereo width]). Lengths are chosen so each q1 file stays under ~170 KB: the dense
# insect / frog choruses cost ~55 kb/s in Vorbis, smooth wind and water textures ~40 kb/s.
BEDS = {
    "wind_light": (bed_wind_light, 32), "wind_strong": (bed_wind_strong, 32), "blizzard": (bed_blizzard, 28),
    "forest_day": (bed_forest_day, 32), "forest_night": (bed_forest_night, 25, 0.7), "plains_day": (bed_plains_day, 32),
    "plains_night": (bed_plains_night, 25, 0.7), "marsh_day": (bed_marsh_day, 32),
    "marsh_night": (bed_marsh_night, 25, 0.7), "jungle_day": (bed_jungle_day, 28),
    "jungle_night": (bed_jungle_night, 24, 0.6), "desert_wind": (bed_desert_wind, 32), "ash_storm": (bed_ash_storm, 30),
    "coast_waves": (bed_coast_waves, 32), "open_ocean": (bed_open_ocean, 32), "river": (bed_river, 28),
    "cave": (bed_cave, 34), "cave_deep": (bed_cave_deep, 36), "glow_cave": (bed_glow_cave, 32),
    "oblivion": (bed_oblivion, 32), "sovngarde": (bed_sovngarde, 34),
    "swamp_night": (bed_swamp_night, 24, 0.7), "savanna_day": (bed_savanna_day, 26), "hot_springs": (bed_hot_springs, 24),
    "volcanic_rumble": (bed_volcanic_rumble, 30), "crystal_shimmer": (bed_crystal_shimmer, 28),
    "dwemer_machinery": (bed_dwemer_machinery, 30), "lava_cave": (bed_lava_cave, 26), "ice_cave": (bed_ice_cave, 30),
    "mushroom_grotto": (bed_mushroom_grotto, 28),
}


def render_bed(name, rng):
    fn, secs, *opt = BEDS[name]
    B = Bed(rng, secs)
    fn(B)
    mid, side = 0.5 * (B.mix[0] + B.mix[1]), 0.5 * (B.mix[0] - B.mix[1]) * (opt[0] if opt else BED_WIDTH)
    x = np.stack([mid + side, mid - side])   # slightly narrowed stereo image: cheaper to encode, still wide
    x = shape(x, B.sr, lambda f: g_hp(f, 18, 2) * (f > 0))   # circular high-pass: no DC, no sub-rumble
    x = x - x.mean(axis=1, keepdims=True)
    return audiolib.loudness_normalise(x, BED_DB, B.sr, peak_db=BED_PEAK), B.sr


# =====================================================================================================  one-shots

def sh_bird_song(rng, v, sr):
    out = np.zeros(int(sr * 3.6))

    def put(s, t0, g=1.0):
        place(out, s * g, int(t0 * sr))
    if v == 1:    # carolling: short phrases of whistled slurs
        t0 = 0.05
        for _ in range(6):
            base = rng.uniform(1900, 2900)
            for _ in range(rng.integers(2, 5)):
                d = rng.uniform(0.09, 0.17)
                a, b = base * rng.uniform(0.85, 1.15), base * rng.uniform(0.8, 1.25)
                kind = rng.integers(3)
                if kind == 0:
                    f = lambda u, a=a, b=b: a + (b - a) * u  # noqa: E731
                elif kind == 1:
                    f = lambda u, a=a, b=b: a + (b - a) * np.sin(np.pi * u)  # noqa: E731
                else:
                    f = lambda u, a=a, d=d: a * (1 + 0.05 * np.sin(TAU * 75 * u * d))  # noqa: E731
                put(tone(sr, d, f, harm=(1, 0.15, 0.04), att=0.25), t0, rng.uniform(0.6, 1.0))
                t0 += d + rng.uniform(0.03, 0.06)
            t0 += rng.uniform(0.22, 0.36)
            if t0 > 2.7:
                break
    elif v == 2:  # accelerating descending trill with a terminal flourish
        t0 = 0.05
        for k in range(7):
            put(tone(sr, 0.032, lambda u: 5600 - 1500 * u, harm=(1, 0.1), att=0.15), t0, 0.55 + 0.06 * k)
            t0 += 0.115 - 0.004 * k
        for k in range(6):
            put(tone(sr, 0.028, lambda u: 4300 - 1250 * u, harm=(1, 0.1), att=0.15), t0, 0.9)
            t0 += 0.082
        t0 += 0.03
        put(tone(sr, 0.34, lambda u: 2700 + 2400 * u + 700 * np.sin(TAU * 6 * u) - 9000 * np.maximum(0, u - 0.8) ** 1.5,
                 harm=(1, 0.12), att=0.1), t0)
    elif v == 3:  # slow pure whistles then triplets
        f1 = rng.uniform(2950, 3200)
        put(tone(sr, 0.75, lambda u: f1 * (1 + 0.015 * u), harm=(1, 0.03), att=0.12), 0.05)
        put(tone(sr, 0.55, f1 * 1.19, harm=(1, 0.03), att=0.12), 0.88)
        t0 = 1.5
        for _ in range(3):
            for k in range(3):
                put(tone(sr, 0.13, lambda u: f1 * 1.31 * (1.01 - 0.02 * u), harm=(1, 0.03), att=0.2), t0, 0.85 - 0.08 * k)
                t0 += 0.165
            t0 += 0.03
    else:         # fast jumbled warble: trills, buzzes and wide FM warbles
        t0 = 0.05
        while t0 < 2.2:
            typ = rng.integers(3)
            fb = rng.uniform(3000, 7000)
            if typ == 0:
                d = rng.uniform(0.015, 0.025)
                up = rng.choice([-1, 1]) * rng.uniform(0.15, 0.4)
                for _ in range(rng.integers(8, 14)):
                    put(tone(sr, d, lambda u, fb=fb, up=up: fb * (1 + up * u), harm=(1, 0.1), att=0.2), t0)
                    t0 += 1.0 / rng.uniform(18, 24)
            elif typ == 1:
                d = rng.uniform(0.12, 0.2)
                put(tone(sr, d, lambda u, fb=fb: 0.6 * fb * (1 + 0.04 * np.sin(TAU * 40 * u)), harm=(1, 0.3, 0.15),
                         am=lambda u: 0.55 + 0.45 * np.sin(TAU * 110 * u * 0.15)), t0, 0.7)
                t0 += d
            else:
                d = rng.uniform(0.18, 0.26)
                put(tone(sr, d, lambda u, fb=fb, d=d: fb * 0.8 + 900 * np.sin(TAU * 32 * u * d), harm=(1, 0.1)), t0)
                t0 += d
            t0 += rng.uniform(0.02, 0.07)
    out = filt(out, sr, "highpass", 1100, 2)
    return verb(out, sr, rng, 0.8, 0.14, damp=0.6)


def caw(rng, sr, dur, f0, rasp, form, drop=0.25):
    return voice(rng, sr, dur, [(0, f0 * 0.95), (0.25, f0 * 1.1), (1, f0 * (1.05 - drop))],
                 [(0, 0), (0.08, 1), (0.7, 0.85), (1, 0)], nh=24, tilt=0.6, half=0.45, rasp=rasp, rasp_fc=260,
                 jitter=0.03, breath=0.35, breath_band=(900, 3500),
                 formants=[(form[0], 320, 1.0), (form[1], 450, 0.8), (form[2], 800, 0.35)])


def sh_crow(rng, v, sr):
    out = np.zeros(int(sr * 2.6))
    if v == 1:   # three hoarse caws, each a little lower
        for k in range(3):
            place(out, caw(rng, sr, rng.uniform(0.32, 0.4), 560 - 25 * k, 0.6, (1150, 1850, 3000)), int((0.05 + 0.5 * k) * sr))
    else:        # deep raven-like "kraaah" pair and a knock
        place(out, caw(rng, sr, 0.55, 330, 0.9, (900, 1500, 2600), drop=0.1), int(0.05 * sr))
        place(out, caw(rng, sr, 0.7, 360, 1.0, (950, 1600, 2700), drop=0.3), int(0.85 * sr))
        knock = pulse_voice(rng, sr, 0.08, 40, [(700, 120, 1.0), (1400, 200, 0.5)])
        place(out, knock * 3, int(1.75 * sr))
    out = filt(out, sr, "highpass", 250, 2)
    return verb(out, sr, rng, 1.0, 0.2)


def sh_hawk(rng, v, sr):
    def scree(dur, pts):
        return voice(rng, sr, dur, pts, [(0, 0), (0.05, 0.8), (0.25, 1), (0.65, 0.75), (1, 0)], nh=5, tilt=0.9,
                     rasp=0.7, rasp_fc=500, breath=0.3, breath_band=(2000, 6500))
    if v == 1:   # one long descending kee-eeee-arr
        out = scree(1.9, [(0, 2500), (0.06, 3050), (0.35, 2700), (0.75, 2000), (1, 1450)])
    else:        # two short screes, rising onset then a quick drop, second lower
        out = np.zeros(int(sr * 2.2))
        place(out, scree(0.85, [(0, 2200), (0.2, 3300), (0.55, 3100), (1, 1900)]), 0)
        place(out, scree(0.95, [(0, 2000), (0.2, 3000), (0.5, 2800), (1, 1600)]) * 0.85, int(1.15 * sr))
    out = filt(out, sr, "lowpass", 7000, 2)
    return verb(out, sr, rng, 1.8, 0.3, damp=0.6)


def gull_note(rng, sr, dur, pts, rasp=0.35):
    return voice(rng, sr, dur, pts, [(0, 0), (0.1, 1), (0.6, 0.85), (1, 0)], nh=10, tilt=0.7, rasp=rasp, rasp_fc=300,
                 jitter=0.015, breath=0.15, breath_band=(1500, 5000),
                 formants=[(1600, 700, 1.0), (3000, 900, 0.6), (900, 400, 0.4)])


def sh_gull(rng, v, sr):
    out = np.zeros(int(sr * 3.2))
    if v == 1:   # long call: drawn-out kyaaow then laughing ha-ha series, accelerating
        place(out, gull_note(rng, sr, 0.5, [(0, 850), (0.2, 1500), (1, 1000)]), int(0.05 * sr))
        t0 = 0.75
        for k in range(7):
            d = 0.16 - 0.006 * k
            place(out, gull_note(rng, sr, d, [(0, 950), (0.35, 1350 - 20 * k), (1, 1050)]) * (1 - 0.06 * k), int(t0 * sr))
            t0 += 0.28 - 0.013 * k
    else:        # two mournful mews, descending
        place(out, gull_note(rng, sr, 0.85, [(0, 1250), (0.12, 1650), (1, 900)], rasp=0.25), int(0.05 * sr))
        place(out, gull_note(rng, sr, 0.6, [(0, 1200), (0.15, 1550), (1, 950)], rasp=0.3) * 0.8, int(1.3 * sr))
    return verb(filt(out, sr, "highpass", 400, 2), sr, rng, 1.2, 0.2)


def sh_jungle_bird(rng, v, sr):
    out = np.zeros(int(sr * 3.4))
    if v == 1:   # toucan-like croak series "rrek rrek"
        t0 = 0.05
        for k in range(7):
            p = 1.0 + (0.06 if k % 2 else 0.0)
            c = pulse_voice(rng, sr, 0.13, [(0, 48), (1, 58)], [(1100 * p, 180, 1.0), (2300 * p, 350, 0.5), (650, 150, 0.3)],
                            env=[(0, 0), (0.1, 1), (0.8, 0.8), (1, 0)], jitter=0.05)
            place(out, c, int(t0 * sr))
            t0 += 0.29 + rng.uniform(-0.02, 0.02)
        for k in range(2):
            place(out, pulse_voice(rng, sr, 0.05, 70, [(1500, 250, 1.0)]) * 0.8, int((t0 + 0.12 * k) * sr))
    elif v == 2:  # three rising whoops, then a hollow descending hoo-hoo coda
        t0 = 0.05
        for k in range(3):
            fs = 520 + 120 * k
            w = voice(rng, sr, 0.4, lambda u, fs=fs: fs * (1 + 1.6 * u ** 1.6), [(0, 0), (0.3, 1), (0.85, 0.9), (1, 0)],
                      nh=4, tilt=1.6, breath=0.05)
            place(out, w, int(t0 * sr))
            t0 += 0.55
        t0 += 0.15
        for k in range(4):
            h = voice(rng, sr, 0.16, [(0, 420 - 30 * k), (1, 380 - 30 * k)], [(0, 0), (0.25, 1), (1, 0)], nh=3, tilt=1.4,
                      breath=0.1, breath_band=(300, 1200))
            place(out, h * 0.8, int(t0 * sr))
            t0 += 0.21
    else:         # liquid gurgle bubbling into a big upward "plooop"
        t0 = 0.05
        for _ in range(16):
            place(out, bubble(sr, rng.uniform(600, 2500), xi=0.4, damp=0.7) * rng.uniform(0.4, 1.0), int(t0 * sr))
            t0 += rng.uniform(0.025, 0.045)
        t0 += 0.08
        n = int(0.55 * sr)
        u = np.arange(n) / n
        f = 380 * (2800 / 380) ** (u ** 1.3) * (1 + 0.08 * np.sin(TAU * 18 * u))
        ph = TAU * np.cumsum(f) / sr
        sweep = (np.sin(ph) + 0.3 * np.sin(2 * ph) + 0.1 * np.sin(3 * ph)) * curve(u, [(0, 0), (0.1, 1), (0.85, 0.8), (1, 0)])
        place(out, sweep, int(t0 * sr))
        place(out, pulse_voice(rng, sr, 0.04, 60, [(2600, 300, 1.0)]) * 1.2, int((t0 + 0.6) * sr))
    out = filt(out, sr, "highpass", 200, 2)
    return verb(out, sr, rng, 1.4, 0.25)


def hoot(rng, sr, dur, f, glide=-0.06, trem=0.0):
    return voice(rng, sr, dur, [(0, f), (0.3, f * 1.01), (1, f * (1 + glide))],
                 lambda u: curve(u, [(0, 0), (0.18, 1), (0.7, 0.85), (1, 0)]) * (1 - trem + trem * np.sin(TAU * 11 * u * dur) ** 2),
                 nh=3, amps=[1.0, 0.22, 0.07], breath=0.12, breath_band=(200, 900), vib=(0.006, 7.0))


def sh_owl(rng, v, sr):
    out = np.zeros(int(sr * 3.6))
    if v == 1:   # hoo ... hu-hoooooo (wavering)
        place(out, hoot(rng, sr, 0.5, 390), int(0.05 * sr))
        place(out, hoot(rng, sr, 0.12, 410, 0.0) * 0.7, int(1.45 * sr))
        place(out, hoot(rng, sr, 1.1, 400, -0.08, trem=0.35), int(1.65 * sr))
    else:        # deep rhythmic hoo h'hoo -- hoo -- hoo
        t0 = 0.05
        for d, g in ((0.35, 0.9), (0.11, 0.6), (0.35, 1.0), (0.42, 0.85), (0.45, 0.8)):
            place(out, hoot(rng, sr, d, 285, -0.05) * g, int(t0 * sr))
            t0 += d + (0.07 if d < 0.2 else 0.28)
    out = filt(out, sr, "highpass", 150, 2)
    return verb(out, sr, rng, 1.6, 0.3, damp=0.7)


def sh_loon(rng, v, sr):
    if v == 1:   # the wail: rises, holds, breaks upward, sinks
        out = voice(rng, sr, 3.6, [(0, 620), (0.17, 900), (0.38, 905), (0.42, 1250), (0.75, 1240), (1, 1050)],
                    [(0, 0), (0.08, 0.9), (0.38, 1.0), (0.4, 0.7), (0.45, 1.0), (0.85, 0.8), (1, 0)], nh=6, tilt=1.4,
                    vib=(0.008, 5.5), breath=0.05, breath_band=(600, 3000))
    else:        # the tremolo "laugh": two warbling bursts, the second higher
        out = np.zeros(int(sr * 3.0))
        for k, (t0, fb) in enumerate(((0.05, 880), (1.2, 1000))):
            n = int(sr * (1.0 + 0.2 * k))
            u = np.arange(n) / n
            sq = np.tanh(3 * np.sin(TAU * 9.5 * np.arange(n) / sr))
            f = fb * (1 + 0.14 * sq) * (1 + 0.05 * u)
            ph = TAU * np.cumsum(f) / sr
            y = (np.sin(ph) + 0.35 * np.sin(2 * ph) + 0.12 * np.sin(3 * ph)) * (0.65 + 0.35 * sq)
            place(out, y * curve(u, [(0, 0), (0.06, 1), (0.85, 0.9), (1, 0)]), int(t0 * sr))
    return verb(out, sr, rng, 3.0, 0.45, damp=0.5)


def sh_frog(rng, v, sr):
    out = np.zeros(int(sr * 1.9))
    if v == 1:   # bullfrog-like jug-o-rum
        for k in range(3):
            place(out, frog_call(rng, sr, "croak", 1.0 - 0.04 * k) / 1.0, int((0.03 + 0.55 * k) * sr))
    elif v == 2:  # rib-bit, rib-bit
        for t0 in (0.03, 0.55):
            place(out, pulse_voice(rng, sr, 0.08, 160, [(1800, 200, 1.0), (3600, 400, 0.4)]), int(t0 * sr))
            place(out, pulse_voice(rng, sr, 0.11, [(0, 150), (1, 185)], [(1900, 200, 1.0), (3700, 400, 0.4)]),
                  int((t0 + 0.15) * sr))
        out = out[:int(sr * 0.95)]
    else:        # rattling kruk-kruk-kruk series
        for k in range(5):
            place(out, frog_call(rng, sr, "kruk", 1.0 + 0.02 * (k % 2)), int((0.03 + 0.17 * k) * sr))
        out = out[:int(sr * 1.1)]
    out = filt(out, sr, "highpass", 80, 2)
    return verb(out, sr, rng, 0.8, 0.15)


def sh_cricket(rng, v, sr):
    out = np.zeros(int(sr * 1.8))
    if v == 1:   # warm harmonic chirps of 3-4 pulses with raised-cosine fade-in/out
        fc = rng.uniform(2650, 3150)
        for c in range(4):
            for k in range(3 + (c % 2)):
                place(out, tone(sr, 0.022, lambda u: fc * (1.015 - 0.03 * (0.5 - 0.5 * np.cos(np.pi * u))),
                                harm=(1.0, 0.09, 0.025), att=0.48),
                      int((0.04 + 0.38 * c + 0.034 * k) * sr))
        out = out[:int(sr * 1.5)]
    else:        # continuous harmonic tree-cricket trill burst, soft and warm
        n = int(sr * 1.4)
        tt = np.arange(n) / sr
        u = tt / 1.4
        gate = (0.5 - 0.5 * np.cos(TAU * 38 * tt)) ** 1.5
        ph = TAU * rng.uniform(2250, 2550) * tt
        env = 0.5 - 0.5 * np.cos(TAU * u)
        out[:n] = (np.sin(ph) + 0.08 * np.sin(2 * ph) + 0.02 * np.sin(3 * ph)) * gate * env
        out = out[:n]
    out = filt(out, sr, "lowpass", 3600, 2)
    return verb(out, sr, rng, 0.65, 0.14)


def insect_pass(rng, sr, dur, f0, t_close, d_close, speed, erratic, tilt, hp):
    n = int(sr * dur)
    tt = np.arange(n) / sr
    u = tt / dur
    x = speed * (tt - t_close)
    y = d_close + (erratic * smooth(rng, n, sr, 1.5) if erratic else 0.0)
    if erratic:
        x = x + erratic * smooth(rng, n, sr, 1.0)
    r = np.sqrt(x ** 2 + y ** 2)
    vr = np.gradient(r) * sr
    f = f0 * (1 - 0.04 * np.tanh(vr / max(speed, 0.1))) * (1 + 0.012 * smooth(rng, n, sr, 5.0))
    ph = TAU * np.cumsum(f) / sr
    src = np.sin(ph) + 0.32 * np.sin(2 * ph) + 0.12 * np.sin(3 * ph) + 0.04 * np.sin(4 * ph)
    src = filt(src, sr, "bandpass", [max(hp, 150), 640], 2)
    amp = 1.0 / np.maximum(r, 0.22)
    env = 0.5 - 0.5 * np.cos(TAU * u)
    return src * amp * env


def sh_insect(rng, v, sr):
    if v == 1:   # gentle meadow bumblebee drifting past
        y = insect_pass(rng, sr, 2.3, 195, 1.1, 0.38, 1.0, 0.15, 1.6, 160)
    else:        # steady deeper honeybee hum drifting by
        y = insect_pass(rng, sr, 2.7, 224, 1.35, 0.45, 0.85, 0.05, 1.8, 150)
        y = y + 0.25 * formant(y, sr, [(420, 140, 1.0)])
    return filt(y, sr, "lowpass", 650, 2)


def drop(rng, sr, f0, xi=0.25, damp=1.0):
    g = bubble(sr, f0, xi=xi, damp=damp)
    g[:16] += rng.standard_normal(16) * 0.25 * np.linspace(1, 0, 16)
    return g


def sh_cave_drip(rng, v, sr):
    out = np.zeros(int(sr * 0.8))
    if v == 1:   # single clean plink
        place(out, drop(rng, sr, 1250), 100)
    elif v == 2:  # two drips from different heights
        place(out, drop(rng, sr, 900, 0.3), 100)
        place(out, drop(rng, sr, 1450, 0.2) * 0.7, int(0.36 * sr))
    elif v == 3:  # deep plunk into a pool with a small splash and a follow-up bubble
        place(out, drop(rng, sr, 430, 0.4, damp=0.7), 100)
        m = int(0.12 * sr)
        place(out, filt(rng.standard_normal(m), sr, "bandpass", [1500, 6000], 2) * win_grain(m, 0.05) ** 2 * 0.25, 100)
        place(out, drop(rng, sr, 1100, 0.3) * 0.35, int(0.13 * sr))
    else:        # tick on rock then a high plink
        m = int(0.03 * sr)
        tick = formant(rng.standard_normal(m) * np.exp(-np.arange(m) / (0.003 * sr)), sr, [(2800, 300, 1.0), (5200, 500, 0.6)])
        place(out, tick * 0.8, 100)
        place(out, drop(rng, sr, 2350, 0.2), int(0.21 * sr))
    return verb(out, sr, rng, 2.0, 0.5, dry=0.7, predelay=0.015, damp=0.7, bright=5000)


def sh_cave_rumble(rng, v, sr):
    n = int(sr * 4.5)
    tt = np.arange(n) / sr
    u = tt / 4.5
    if v == 1:   # earth groan: rumble swell with a gliding sub tone
        rum = filt(rng.standard_normal(n), sr, "lowpass", 240, 3) * curve(u, [(0, 0), (0.25, 1), (0.5, 0.8), (1, 0)])
        f = curve(u, [(0, 40), (1, 29)])
        groan = harmonics(TAU * np.cumsum(f) / sr, f, sr, 6, 1.2) * (1 + 0.5 * smooth(rng, n, sr, 6.0))
        y = unit(rum) + 0.5 * unit(groan) * curve(u, [(0, 0), (0.3, 1), (0.6, 0.7), (1, 0)])
    else:        # irregular surging rumble with crumbling debris
        surge = curve(u, [(0, 0), (0.12, 0.8), (0.25, 0.5), (0.42, 1.0), (0.6, 0.55), (0.75, 0.7), (1, 0)])
        y = unit(filt(rng.standard_normal(n), sr, "lowpass", 170, 4)) * surge
        deb = np.zeros(n)
        for _ in range(40):
            m = int(0.015 * sr)
            g = formant(rng.standard_normal(m) * np.exp(-np.arange(m) / (0.002 * sr)), sr, [(rng.uniform(900, 2800), 300, 1.0)])
            place(deb, g * rng.uniform(0.1, 1.0), int(rng.uniform(0.35, 0.85) * n))
        y = y + 0.06 * unit(deb)
    return verb(y, sr, rng, 2.2, 0.45, damp=0.8, bright=3000)


def rock_hit(rng, sr, size):
    m = int(sr * (0.06 + 0.1 * size))
    ex = rng.standard_normal(m) * np.exp(-np.arange(m) / (0.002 * sr))
    bank = [(rng.uniform(600, 2600) / (0.6 + size), rng.uniform(60, 160), rng.uniform(0.4, 1.0)) for _ in range(4)]
    y = formant(ex, sr, bank) * 3
    if size > 0.4:
        th = filt(rng.standard_normal(m), sr, "lowpass", 160, 2) * np.exp(-np.arange(m) / (0.02 * sr))
        y = y + size * unit(th) * rms(y) * 1.5
    return y


def sh_rock_fall(rng, v, sr):
    out = np.zeros(int(sr * 3.0))
    if v == 1:   # a few rocks bouncing down, pebbles trailing
        for _ in range(2):
            t0, gap, size = rng.uniform(0.02, 0.2), rng.uniform(0.3, 0.4), rng.uniform(0.6, 1.0)
            for _ in range(rng.integers(4, 7)):
                place(out, rock_hit(rng, sr, size), int(t0 * sr))
                t0 += gap * rng.uniform(0.85, 1.15)
                gap *= 0.78
                size *= 0.8
        for _ in range(35):
            place(out, rock_hit(rng, sr, rng.uniform(0.0, 0.25)) * rng.uniform(0.1, 0.5), int(rng.uniform(0.2, 2.1) * sr))
    else:        # pebble trickle cascade
        for _ in range(110):
            t0 = 0.05 + 2.0 * rng.beta(2.0, 3.0)
            place(out, rock_hit(rng, sr, rng.uniform(0.0, 0.2)) * rng.uniform(0.15, 0.8), int(t0 * sr))
        for t0 in (0.4, 0.95):
            place(out, rock_hit(rng, sr, 0.5) * 0.7, int(t0 * sr))
    return verb(filt(out, sr, "highpass", 60, 2), sr, rng, 1.0, 0.25)


def gust_shot(rng, sr, dur, gain_fn, nfft=2048):
    hop = nfft // 4
    n = int(np.ceil(sr * dur / hop) * hop)
    return tv_noise(rng, n, sr, gain_fn, nfft)


def sh_wind_gust(rng, v, sr):
    if v == 1:   # single swell with a rising whistle
        D = 3.0
        e = lambda tc: curve(tc / D, [(0, 0), (0.45, 1), (1, 0)]) ** 1.5  # noqa: E731

        def g(tc, f):
            a = e(tc)[:, None]
            return a * (g_col(f, 1.2) * g_hp(f, 80) * g_lp(f, 400 + 1200 * a, 2)
                        + 2.5 * a * g_bp(f, 1050 + 400 * a, 30))
        y = gust_shot(rng, sr, D, g)
    else:        # broader, lower double-peaked whoosh with leafy rustle on the peaks
        D = 3.2
        e = lambda tc: curve(tc / D, [(0, 0), (0.3, 1), (0.5, 0.55), (0.72, 0.9), (1, 0)])  # noqa: E731

        def g(tc, f):
            a = e(tc)[:, None]
            return a * g_col(f, 1.5) * g_hp(f, 50) * g_lp(f, 250 + 700 * a, 2)
        y = gust_shot(rng, sr, D, g)
        n = len(y)
        dens = e(np.arange(n) / sr) ** 2 * 3000 / sr
        cr = np.where(rng.random(n) < dens, rng.standard_normal(n), 0.0)
        y = y + 0.35 * unit(filt(cr, sr, "bandpass", [1500, 7000], 2)) * e(np.arange(n) / sr)
    return y


def sh_tree_creak(rng, v, sr):
    wood = [(280, 40, 1.0), (520, 60, 0.8), (870, 80, 0.6), (1350, 110, 0.35)]
    if v == 1:   # one slow groan
        y = pulse_voice(rng, sr, 1.7, [(0, 25), (0.45, 70), (0.8, 55), (1, 35)], wood,
                        env=[(0, 0), (0.15, 0.8), (0.5, 1.0), (0.85, 0.7), (1, 0)], jitter=0.12, amp_jit=0.4)
    else:        # two shorter, higher creaks
        hi = [(f * 2.1, bw * 1.6, g) for f, bw, g in wood]
        y = np.zeros(int(sr * 1.6))
        place(y, pulse_voice(rng, sr, 0.5, [(0, 80), (1, 150)], hi, jitter=0.1, amp_jit=0.4), int(0.03 * sr))
        place(y, pulse_voice(rng, sr, 0.65, [(0, 120), (1, 60)], hi, jitter=0.15, amp_jit=0.4) * 0.8, int(0.8 * sr))
    return verb(y, sr, rng, 0.7, 0.12)


def sh_volcano(rng, v, sr):
    n = int(sr * 5.0)
    tt = np.arange(n) / sr
    y = np.zeros(n)
    booms = [0.05] if v == 1 else sorted(rng.uniform(0.8, 3.2, 4))
    for k, t0 in enumerate(booms):
        m = int(sr * 2.0)
        t = np.arange(m) / sr
        f = 38 * (1 - 0.3 * t / 2.0)
        thump = np.sin(TAU * np.cumsum(f) / sr) * np.exp(-t / 0.5) * (1 - np.exp(-t / 0.01))
        burst = filt(rng.standard_normal(m), sr, "lowpass", 220, 2) * np.exp(-t / 0.35)
        g = (1.0 if v == 1 else rng.uniform(0.5, 1.0))
        place(y, g * (unit(thump) + 0.8 * unit(burst)) * np.exp(-t / 1.0), int(t0 * sr))
    if v == 1:   # single boom, rolling rumble after
        roll = curve(tt / 5, [(0, 0), (0.06, 1.0), (0.3, 0.55), (1, 0)])
    else:        # building eruption rumble with patter
        roll = curve(tt / 5, [(0, 0), (0.35, 0.8), (0.7, 1.0), (1, 0)])
        pat = np.where(rng.random(n) < 80 / sr * roll, rng.standard_normal(n), 0.0)
        y = y + 0.08 * unit(filt(pat, sr, "bandpass", [300, 1500], 2))
    y = y + 0.6 * unit(filt(rng.standard_normal(n), sr, "lowpass", 110, 4)) * roll * (1 + 0.4 * smooth(rng, n, sr, 3.0))
    y = y + 0.35 * unit(filt(rng.standard_normal(n), sr, "bandpass", [90, 380], 2)) * roll ** 1.5 * \
        (1 + 0.5 * smooth(rng, n, sr, 5.0))       # rolling 'body' so it still reads on small speakers
    y = filt(y, sr, "lowpass", 450, 4)
    return verb(y, sr, rng, 3.0, 0.5, damp=0.9, bright=1500, echoes=((0.7, 0.4),))


def roar(rng, sr, dur, f0pts, envpts, rough_fc, half, vowel):
    return voice(rng, sr, dur, f0pts, envpts, nh=90, tilt=0.75, half=half, rasp=0.7, rasp_fc=rough_fc, jitter=0.05,
                 breath=0.6, breath_band=(200, 2500), formants=vowel)


def sh_distant_dragon(rng, v, sr):
    if v == 1:   # one long roar
        y = roar(rng, sr, 2.6, [(0, 68), (0.25, 96), (0.55, 90), (1, 58)],
                 [(0, 0), (0.12, 0.9), (0.5, 1.0), (0.85, 0.6), (1, 0)], 32, 0.5,
                 [(380, 160, 1.0), (820, 260, 0.8), (1650, 420, 0.5), (2600, 600, 0.2)])
        cut = 900
    else:        # low growl leading into a shorter roar
        g1 = roar(rng, sr, 1.1, [(0, 44), (1, 50)], [(0, 0), (0.3, 0.6), (0.9, 0.7), (1, 0.6)], 18, 0.8,
                  [(300, 140, 1.0), (650, 220, 0.6), (1300, 400, 0.25)])
        g2 = roar(rng, sr, 1.4, [(0, 70), (0.3, 84), (1, 60)], [(0, 0.6), (0.15, 1.0), (0.7, 0.8), (1, 0)], 28, 0.5,
                  [(420, 180, 1.0), (880, 280, 0.8), (1700, 420, 0.4)])
        y = np.concatenate([g1, g2])
        cut = 700
    y = filt(y, sr, "lowpass", cut, 4)
    y = filt(y, sr, "highpass", 35, 2)
    return verb(y, sr, rng, 3.4, 0.75, dry=0.45, predelay=0.06, damp=0.85, bright=1800,
                echoes=((0.48, 0.45), (0.95, 0.25)))


def sh_wave_crash(rng, v, sr):
    n = int(sr * 4.4)
    tt = np.arange(n) / sr

    def crash(t0, scale, wash_tau):
        loc = tt - t0
        on = loc >= -0.08
        att = np.where(loc < 0, np.exp(np.minimum(loc, 0) / 0.025), 1.0) * on
        body = unit(filt(rng.standard_normal(n), sr, "bandpass", [150, 5000], 2)) * att * np.exp(-np.maximum(loc, 0) / 0.45)
        thump = unit(filt(rng.standard_normal(n), sr, "lowpass", 140, 2)) * att * np.exp(-np.maximum(loc, 0) / 0.3)
        spray = unit(filt(rng.standard_normal(n), sr, "highpass", 2800, 2)) * att * \
            (1 - np.exp(-np.maximum(loc, 0) / 0.08)) * np.exp(-np.maximum(loc, 0) / 1.2)
        wash = unit(filt(rng.standard_normal(n), sr, "lowpass", 1500, 2)) * (loc > 0) * \
            (1 - np.exp(-np.maximum(loc, 0) / 0.3)) * np.exp(-np.maximum(loc, 0) / wash_tau)
        fizz = np.zeros(n)
        for _ in range(int(220 * scale)):
            ti = t0 + 0.1 + rng.exponential(0.8)
            if ti < tt[-1]:
                place(fizz, bubble(sr, rng.uniform(1800, 6500)) * rng.uniform(0.1, 1.0), int(ti * sr))
        return scale * (body + 0.8 * thump + 0.5 * spray + 0.45 * wash + 0.12 * unit(fizz))
    if v == 1:
        y = crash(0.12, 1.0, 1.4)
    else:        # a small slap then the big crash with a long seething retreat
        y = crash(0.1, 0.45, 0.8) + crash(0.75, 1.0, 2.2)
    return verb(y, sr, rng, 1.1, 0.3, damp=0.7)


def moan(rng, sr, dur, pts, env, nh=8, tilt=1.3, rasp=0.1):
    return voice(rng, sr, dur, pts, env, nh=nh, tilt=tilt, rasp=rasp, rasp_fc=20, vib=(0.01, 3.0), jitter=0.01)


def sh_whale(rng, v, sr):
    out = np.zeros(int(sr * 4.6))
    if v == 1:   # rising-falling moan, an upsweep whoop, a rough low groan
        place(out, moan(rng, sr, 1.8, [(0, 180), (0.45, 265), (1, 150)], [(0, 0), (0.2, 1), (0.8, 0.8), (1, 0)]), 0)
        place(out, moan(rng, sr, 0.55, [(0, 300), (1, 720)], [(0, 0), (0.3, 1), (0.8, 0.9), (1, 0)], tilt=1.6) * 0.7,
              int(1.95 * sr))
        place(out, moan(rng, sr, 1.3, [(0, 95), (1, 85)], [(0, 0), (0.2, 1), (1, 0)], nh=12, tilt=1.0, rasp=0.6),
              int(2.7 * sr))
    else:        # long deep moan overlapped by a higher cry
        place(out, moan(rng, sr, 3.0, [(0, 52), (0.45, 86), (1, 60)], [(0, 0), (0.25, 1), (0.75, 0.9), (1, 0)], nh=14,
                        tilt=1.0, rasp=0.25), 0)
        place(out, moan(rng, sr, 1.5, [(0, 410), (0.3, 440), (1, 320)], [(0, 0), (0.3, 1), (1, 0)], nh=5, tilt=1.5) * 0.5,
              int(1.6 * sr))
    out = filt(out, sr, "lowpass", 2500, 2)
    return verb(out, sr, rng, 2.6, 0.5, damp=0.9, bright=2500, predelay=0.03)


def sh_sand_hiss(rng, v, sr):
    D = 2.4 if v == 1 else 2.6
    n = int(sr * D)
    u = np.arange(n) / n
    if v == 1:   # dense hiss swelling across the dune
        e = curve(u, [(0, 0), (0.45, 1), (1, 0)])
        band, dens, low = (3000, 9500), 9000, 0.35
    else:        # gritty slither with two swells
        e = curve(u, [(0, 0), (0.25, 0.9), (0.45, 0.45), (0.7, 1.0), (1, 0)])
        band, dens, low = (2000, 6000), 3500, 0.2
    cr = np.where(rng.random(n) < dens * e / sr, rng.standard_normal(n) * (1 + rng.pareto(2.5, n) * (v - 1)), 0.0)
    y = unit(filt(cr, sr, "bandpass", list(band), 2)) * e
    y = y + 0.5 * bandnoise(rng, n, sr, band[0] * 1.2, min(band[1] * 1.1, 14000)) * e ** 1.5
    y = y + low * bandnoise(rng, n, sr, 250, 900) * e
    return y


def sh_oblivion_wail(rng, v, sr):
    n_d = 3.2
    if v == 1:   # inharmonic voices gliding up into a ring-modulated scream
        ratios, pts = (1.0, 1.414, 1.89), [(0, 300), (0.6, 640), (1, 600)]
        env = [(0, 0), (0.25, 0.6), (0.65, 1.0), (1, 0)]
        vib = (0.02, 6.5)
    else:        # descending beating moan with tremolo
        ratios, pts = (1.0, 1.012, 0.988, 1.5), [(0, 520), (1, 185)]
        env = lambda u: curve(u, [(0, 0), (0.15, 1.0), (0.7, 0.8), (1, 0)]) * (0.7 + 0.3 * np.sin(TAU * 6 * u * n_d))  # noqa: E731
        vib = (0.012, 4.0)
    y = sum(voice(rng, sr, n_d, [(t, f * r) for t, f in pts], env, nh=10, tilt=1.1, vib=vib, jitter=0.01,
                  formants=[(700, 300, 1.0), (1100, 300, 0.6), (2500, 500, 0.3)]) / len(ratios) for r in ratios)
    tt = np.arange(len(y)) / sr
    if v == 1:
        y = 0.6 * y + 0.4 * y * np.sin(TAU * 37 * tt)
    else:
        y = y + 0.4 * unit(voice(rng, sr, n_d, [(0, 65), (1, 50)], env, nh=30, tilt=0.8, rasp=0.8, rasp_fc=25)) * rms(y)
    y = filt(y, sr, "lowpass", 3500, 2)
    return verb(y, sr, rng, 3.8, 0.65, dry=0.5, predelay=0.05, damp=0.6)


def sh_sovngarde_horn(rng, v, sr):
    # long low call, rise a fifth, long sustain: brass-like harmonics whose brightness follows loudness
    notes = [(0.05, 1.5, 146.83), (1.65, 0.9, 220.0), (2.6, 1.3, 220.0 * 4 / 3)]
    D = 4.1
    n = int(sr * D)
    y = np.zeros(n)
    for t0, d, f in notes:
        m = int(sr * d)
        tt = np.arange(m) / sr
        u = tt / d
        fr = f * (1 - 0.035 * np.exp(-tt / 0.06)) * (1 + 0.003 * np.sin(TAU * 4.5 * tt))
        ph = TAU * np.cumsum(fr) / sr
        a = curve(u, [(0, 0), (0.08, 0.85), (0.2, 1.0), (0.85, 0.9), (1, 0)])
        tone_ = np.zeros(m)
        for k in range(1, 30):
            if k * f > 0.45 * sr:
                break
            tone_ += (k ** -1.0) * a ** (1.0 + 0.25 * k) * np.sin(k * ph)
        tone_ += 0.03 * bandnoise(rng, m, sr, 400, 3000) * a
        place(y, tone_, int(t0 * sr))
    y = filt(y, sr, "lowpass", 2200, 2)
    return verb(y, sr, rng, 3.5, 0.6, dry=0.6, predelay=0.04, damp=0.6, bright=3500, echoes=((0.7, 0.45), (1.35, 0.25)))


def sh_glow_chime(rng, v, sr):
    glass = (1.0, 2.76, 5.40, 8.93)
    if v == 1:   # an ascending cluster of tinkles
        out = np.zeros(int(sr * 3.3))
        scale = [1046.5, 1174.7, 1396.9, 1568.0, 1760.0, 2093.0]
        t0 = 0.03
        for k in range(5):
            f0 = scale[min(len(scale) - 1, k + int(rng.integers(0, 2)))]
            modes = [(f0 * r, a, tau) for r, a, tau in zip(glass, (1.0, 0.45, 0.2, 0.08), (1.8, 0.8, 0.35, 0.18))]
            place(out, modal(rng, sr, 2.4, modes, beat=1.5, att=0.002) * rng.uniform(0.6, 1.0), int(t0 * sr))
            t0 += rng.uniform(0.12, 0.3)
    else:        # one deeper bowl-like ring with slow beating and a faint glint
        bowl = (1.0, 2.71, 5.15, 8.5)
        modes = [(430 * r, a, tau) for r, a, tau in zip(bowl, (1.0, 0.5, 0.25, 0.1), (3.0, 1.6, 0.8, 0.4))]
        out = modal(rng, sr, 4.0, modes, beat=2.5, att=0.006)
        place(out, modal(rng, sr, 2.0, [(3150, 0.25, 0.8), (3150 * 2.76, 0.08, 0.3)], beat=1.0, att=0.002), int(0.8 * sr))
    return verb(out, sr, rng, 3.0, 0.5, damp=0.3, bright=9000)


def sh_distant_screech(rng, v, sr):
    """Something screeching far down the tunnels (Falmer glow caves)."""
    if v == 1:
        y = voice(rng, sr, 1.0, [(0, 700), (0.2, 1150), (0.6, 1000), (1, 650)], [(0, 0), (0.1, 1), (0.6, 0.7), (1, 0)], nh=12,
                  tilt=1.0, rasp=0.6, rasp_fc=60, jitter=0.03, breath=0.4, formants=[(2300, 300, 1.0), (3400, 400, 0.5)])
    else:
        y = np.zeros(int(sr * 1.4))
        for k, t0 in enumerate((0.0, 0.32, 0.55)):
            g = voice(rng, sr, 0.28, [(0, 900 - 80 * k), (0.3, 1300 - 80 * k), (1, 800)], [(0, 0), (0.1, 1), (1, 0)], nh=10,
                      tilt=1.0, rasp=0.7, rasp_fc=70, jitter=0.03, breath=0.5, formants=[(2100, 300, 1.0), (3200, 400, 0.5)])
            place(y, g * (1 - 0.25 * k), int(t0 * sr))
    y = filt(y, sr, "lowpass", 2600, 2)
    return verb(y, sr, rng, 3.5, 0.9, dry=0.35, predelay=0.06, damp=0.7, bright=3000, echoes=((0.45, 0.4), (0.9, 0.2)))


def sh_machinery(rng, v, sr):
    """Dwemer machinery somewhere in the dark: a clanking run, a steam vent, a piston and grinding gears."""
    def metal(f0, dur=0.9):
        return modal(rng, sr, dur, [(f0, 1.0, 0.3), (f0 * 1.594, 0.6, 0.2), (f0 * 2.136, 0.45, 0.14), (f0 * 2.653, 0.3, 0.1),
                                    (f0 * 3.156, 0.2, 0.07)], beat=1.0, att=0.0008)
    if v == 1:
        y = np.zeros(int(sr * 2.0))
        t0 = 0.02
        for _ in range(rng.integers(4, 7)):
            place(y, metal(rng.uniform(250, 650)) * rng.uniform(0.5, 1.0), int(t0 * sr))
            t0 += rng.uniform(0.12, 0.3)
    elif v == 2:
        n = int(sr * 2.2)
        u = np.arange(n) / n
        y = bandnoise(rng, n, sr, 1500, 9000) * curve(u, [(0, 0), (0.04, 1), (0.6, 0.6), (1, 0)])
        y = y + 0.3 * bandnoise(rng, n, sr, 300, 1200) * curve(u, [(0, 0), (0.05, 1), (1, 0)])
        y = y + 0.15 * tone(sr, 2.2, rng.uniform(1800, 2400), harm=(1, 0.2), att=0.05) * curve(u, [(0, 0), (0.1, 1), (0.5, 0.3), (1, 0)])
    else:
        y = np.zeros(int(sr * 2.0))
        m = int(sr * 0.7)
        t = np.arange(m) / sr
        th = np.sin(TAU * np.cumsum(60 * (1 - 0.35 * t / 0.7)) / sr) * np.exp(-t / 0.15) * (1 - np.exp(-t / 0.004))
        place(y, th, int(0.02 * sr))
        place(y, metal(rng.uniform(380, 520), 1.2) * 0.6, int(0.03 * sr))
        gear = pulse_voice(rng, sr, 1.1, [(0, 20), (1, 34)], [(900, 120, 1.0), (1900, 200, 0.6), (3100, 300, 0.3)], jitter=0.05)
        place(y, gear * 0.5, int(0.6 * sr))
    return verb(filt(y, sr, "lowpass", 5000, 2), sr, rng, 2.5, 0.6, dry=0.6, predelay=0.03, damp=0.6)


def sh_lava_bubble(rng, v, sr):
    if v == 1:   # one big, slow blorp and a hiss
        y = np.zeros(int(sr * 1.6))
        place(y, bubble(sr, rng.uniform(55, 75), xi=0.5, damp=0.4), 0)
        place(y, 0.6 * bubble(sr, rng.uniform(110, 150), xi=0.4, damp=0.5), int(0.25 * sr))
        m = int(0.9 * sr)
        place(y, 0.08 * bandnoise(rng, m, sr, 2500, 8000) * win_grain(m, 0.1), int(0.3 * sr))
    else:        # a cluster of pops and a sizzle
        y = np.zeros(int(sr * 1.8))
        for _ in range(rng.integers(6, 11)):
            place(y, bubble(sr, np.exp(rng.uniform(np.log(70), np.log(260))), xi=0.4, damp=0.6) * rng.uniform(0.3, 1.0),
                  int(rng.uniform(0, 1.2) * sr))
        m = int(1.6 * sr)
        cr = np.where(rng.random(m) < 300 / sr, rng.standard_normal(m), 0.0)
        place(y, 0.3 * unit(filt(cr, sr, "bandpass", [2000, 7000], 2)) * curve(np.arange(m) / m, [(0, 0), (0.3, 1), (1, 0)]) * 0.05, 0)
    return verb(filt(y, sr, "highpass", 35, 2), sr, rng, 1.5, 0.35, damp=0.8)


def sh_ice_creak(rng, v, sr):
    bank = [(310, 30, 1.0), (720, 45, 0.7), (1340, 60, 0.45), (2150, 80, 0.3), (3400, 120, 0.15)]
    if v == 1:   # a long, slow groan of moving ice
        y = pulse_voice(rng, sr, 2.2, [(0, 12), (0.4, 45), (0.7, 30), (1, 14)], bank,
                        env=[(0, 0), (0.15, 0.8), (0.5, 1.0), (0.85, 0.6), (1, 0)], jitter=0.2, amp_jit=0.5)
    else:        # a sharp crack, then a creak
        y = np.zeros(int(sr * 2.0))
        m = int(0.08 * sr)
        crack = rng.standard_normal(m) * np.exp(-np.arange(m) / (0.006 * sr))
        place(y, filt(crack, sr, "highpass", 1200, 2) * 2.0, 0)
        place(y, modal(rng, sr, 1.0, [(rng.uniform(3000, 6000), 0.5, 0.2), (rng.uniform(1500, 2500), 0.4, 0.3)], att=0.001), 0)
        place(y, pulse_voice(rng, sr, 1.2, [(0, 60), (1, 20)], [(f * 1.4, bw, g) for f, bw, g in bank], jitter=0.2, amp_jit=0.5) * 0.7,
              int(0.35 * sr))
    return verb(y, sr, rng, 2.6, 0.5, dry=0.7, predelay=0.03, damp=0.5, bright=7000, echoes=((0.5, 0.25),))


def sh_cicada(rng, v, sr):
    D = 2.6 if v == 1 else 2.2
    n = int(sr * D)
    tt = np.arange(n) / sr
    u = tt / D
    fc = rng.uniform(1950, 2450) if v == 1 else rng.uniform(1650, 2100)
    rate = rng.uniform(46, 64) if v == 1 else rng.uniform(34, 48)
    f = fc * (1 + 0.008 * np.sin(TAU * 2.5 * tt) + 0.004 * smooth(rng, n, sr, 4.0))
    ph = TAU * np.cumsum(f) / sr
    carrier = np.sin(ph) + 0.16 * np.sin(2 * ph) + 0.04 * np.sin(3 * ph)
    am = 0.25 + 0.75 * (0.5 - 0.5 * np.cos(TAU * rate * tt))
    breeze = 0.12 * bandnoise(rng, n, sr, 180, 620, 2)
    e = (0.5 - 0.5 * np.cos(TAU * u)) if v == 1 else curve(u, [(0, 0), (0.18, 1), (0.5, 0.55), (0.72, 0.95), (1, 0)])
    y = filt((carrier * am + breeze) * e, sr, "lowpass", 3400, 2)
    return verb(y, sr, rng, 0.75, 0.16)


# ---------------------------------------------------------------------------- fire and aftermath (com.skycraft.fire)

def sh_fire_crackle(rng, v, sr):
    """A burning house heard from a few blocks: a soft low rush with sparse wood pops."""
    D = [1.6, 2.0, 1.4][v - 1]
    n = int(sr * D)
    u = np.arange(n) / n
    rush = bandnoise(rng, n, sr, 90, 700, 2) * 0.35
    rush = rush * (0.6 + 0.4 * smooth(rng, n, sr, 6.0))
    hiss = bandnoise(rng, n, sr, 1800, 5200, 2) * 0.05 * (0.5 + 0.5 * smooth(rng, n, sr, 12.0))
    y = rush + hiss
    pops = [6, 9, 5][v - 1]
    for _ in range(pops):
        t0 = rng.uniform(0.05, D - 0.1)
        m = int(rng.uniform(0.004, 0.012) * sr)
        burst = rng.standard_normal(m) * np.exp(-np.arange(m) / (m * 0.3))
        burst = filt(burst, sr, "bandpass", [rng.uniform(900, 2200), rng.uniform(3500, 6500)], 2)
        place(y, burst * rng.uniform(0.5, 1.4), int(t0 * sr))
        if rng.random() < 0.35:
            place(y, modal(rng, sr, 0.12, [(rng.uniform(300, 700), 0.4, 0.02)], att=0.0005) * 0.4, int(t0 * sr))
    y = y * curve(u, [(0, 0), (0.12, 1), (0.8, 0.9), (1, 0)])
    return verb(y, sr, rng, 0.5, 0.12)


def sh_fire_roar(rng, v, sr):
    """The low, breathing roar of a big fire: filtered noise that swells and falls."""
    D = 3.2 if v == 1 else 2.6
    n = int(sr * D)
    u = np.arange(n) / n
    low = bandnoise(rng, n, sr, 45, 380, 3) * 1.0
    mid = bandnoise(rng, n, sr, 350, 1700, 2) * 0.28
    swell = curve(u, [(0, 0.1), (0.3, 1.0), (0.55, 0.65), (0.8, 0.95), (1, 0.05)])
    y = (low + mid * (0.5 + 0.5 * smooth(rng, n, sr, 4.0))) * swell
    return verb(filt(y, sr, "lowpass", 2400, 2), sr, rng, 0.9, 0.2)


def sh_timber_creak(rng, v, sr):
    """Burnt beams settling: a slow low groan, two short strained creaks, or a groan ending in a snap."""
    wood = [(150, 30, 1.0), (310, 45, 0.9), (540, 70, 0.55), (900, 100, 0.3)]
    if v == 1:
        y = pulse_voice(rng, sr, 2.1, [(0, 18), (0.4, 52), (0.8, 40), (1, 22)], wood,
                        env=[(0, 0), (0.2, 0.8), (0.55, 1.0), (0.9, 0.5), (1, 0)], jitter=0.15, amp_jit=0.45)
    elif v == 2:
        hi = [(f * 1.6, bw * 1.3, g) for f, bw, g in wood]
        y = np.zeros(int(sr * 1.8))
        place(y, pulse_voice(rng, sr, 0.6, [(0, 70), (1, 120)], hi, jitter=0.12, amp_jit=0.4), int(0.05 * sr))
        place(y, pulse_voice(rng, sr, 0.8, [(0, 100), (1, 45)], hi, jitter=0.15, amp_jit=0.4) * 0.85, int(0.85 * sr))
    else:
        y = np.zeros(int(sr * 2.2))
        place(y, pulse_voice(rng, sr, 1.3, [(0, 20), (1, 60)], wood, env=[(0, 0), (0.3, 0.8), (1, 1.0)], jitter=0.2, amp_jit=0.5), 0)
        m = int(0.07 * sr)
        place(y, filt(rng.standard_normal(m) * np.exp(-np.arange(m) / (0.008 * sr)), sr, "highpass", 600, 2) * 1.6, int(1.3 * sr))
        place(y, modal(rng, sr, 0.5, [(rng.uniform(900, 1700), 0.5, 0.06), (rng.uniform(350, 600), 0.5, 0.1)], att=0.0008), int(1.3 * sr))
    return verb(y, sr, rng, 1.0, 0.2, damp=0.6, bright=5000)


def sh_timber_collapse(rng, v, sr):
    """A burnt roof or wall giving way: a crack, a low tumble of beams, a rain of small debris, dust settling."""
    D = 3.6
    n = int(sr * D)
    y = np.zeros(n)
    m = int(0.09 * sr)
    place(y, filt(rng.standard_normal(m) * np.exp(-np.arange(m) / (0.012 * sr)), sr, "highpass", 500, 2) * 1.8, int(0.05 * sr))
    for k in range(5 if v == 1 else 7):
        t0 = 0.12 + 0.12 * k + rng.uniform(0, 0.15)
        f = rng.uniform(70, 190)
        place(y, modal(rng, sr, 0.5, [(f, 0.7, 0.09), (f * 2.3, 0.35, 0.05), (f * 4.1, 0.15, 0.03)], att=0.001) * rng.uniform(0.5, 1.0),
              int(t0 * sr))
    for _ in range(60):
        t0 = rng.uniform(0.4, 2.4)
        mm = int(rng.uniform(0.003, 0.01) * sr)
        tick = filt(rng.standard_normal(mm) * np.exp(-np.arange(mm) / (mm * 0.25)), sr, "bandpass",
                    [rng.uniform(700, 2000), rng.uniform(3000, 6000)], 2)
        place(y, tick * rng.uniform(0.05, 0.35), int(t0 * sr))
    dust = bandnoise(rng, n, sr, 200, 2500, 2) * 0.2 * curve(np.arange(n) / n, [(0, 0), (0.1, 0.8), (0.5, 0.3), (1, 0)])
    return verb(y + dust, sr, rng, 1.6, 0.35, damp=0.5, bright=4500)


def sob(rng, sr, f0, dur, soft):
    """One sob: a short voiced burst with a falling pitch, breath noise and a vowel colour."""
    bank = [(560, 90, 1.0), (1050, 110, 0.55), (2600, 160, 0.18)]
    s = voice(rng, sr, dur, [(0, f0 * 1.06), (0.5, f0), (1, f0 * 0.82)], [(0, 0), (0.18, 1), (0.55, 0.7), (1, 0)], nh=14, tilt=1.4,
              jitter=0.02, breath=0.55, breath_band=(900, 3800), formants=bank, vib=(0.012, 7.0))
    return s * soft


def sh_sobbing(rng, v, sr):
    """Quiet crying: a few catching sobs with breaths between, a little shaky."""
    f0 = [250, 215, 290, 180][v - 1]
    D = [2.8, 3.2, 2.4, 3.4][v - 1]
    n = int(sr * D)
    y = np.zeros(n)
    t = 0.1
    k = 0
    while t < D - 0.5:
        dur = rng.uniform(0.2, 0.34)
        place(y, sob(rng, sr, f0 * rng.uniform(0.92, 1.08), dur, max(0.3, 0.8 - 0.05 * k)), int(t * sr))
        t += dur + 0.02
        m = int(rng.uniform(0.12, 0.22) * sr)
        breath = bandnoise(rng, m, sr, 1100, 3600, 2) * win_grain(m, 0.5) * 0.18
        place(y, breath, int(t * sr))
        t += m / sr + rng.uniform(0.05, 0.25)
        k += 1
    return verb(y, sr, rng, 0.8, 0.18, damp=0.7, bright=3500)


def sh_mourning(rng, v, sr):
    """A long keening cry, far off: one voice rising and falling, breathy, with a slow vibrato."""
    D = 3.6 if v == 1 else 4.2
    base = 330.0 if v == 1 else 270.0
    bank = [(620, 100, 1.0), (1150, 130, 0.45), (2500, 200, 0.12)]
    s = voice(rng, sr, D, [(0, base * 0.9), (0.25, base * 1.1), (0.6, base * 1.18), (0.85, base * 0.98), (1, base * 0.8)],
              [(0, 0), (0.18, 0.9), (0.55, 1.0), (0.85, 0.5), (1, 0)], nh=12, tilt=1.6, jitter=0.012, breath=0.25,
              breath_band=(700, 3000), formants=bank, vib=(0.02, 5.0))
    return verb(s, sr, rng, 2.4, 0.5, damp=0.9, bright=3000, predelay=0.04)


SHOTS = {
    "fire_crackle": (sh_fire_crackle, 3), "fire_roar": (sh_fire_roar, 2), "timber_creak": (sh_timber_creak, 3),
    "timber_collapse": (sh_timber_collapse, 2), "sobbing": (sh_sobbing, 4), "mourning": (sh_mourning, 2),
    "bird_song": (sh_bird_song, 4), "crow": (sh_crow, 2), "hawk": (sh_hawk, 2), "gull": (sh_gull, 2),
    "jungle_bird": (sh_jungle_bird, 3), "owl": (sh_owl, 2), "loon": (sh_loon, 2), "frog": (sh_frog, 3),
    "cricket": (sh_cricket, 2), "insect": (sh_insect, 2), "cave_drip": (sh_cave_drip, 4),
    "cave_rumble": (sh_cave_rumble, 2), "rock_fall": (sh_rock_fall, 2), "wind_gust": (sh_wind_gust, 2),
    "tree_creak": (sh_tree_creak, 2), "volcano": (sh_volcano, 2), "distant_dragon": (sh_distant_dragon, 2),
    "wave_crash": (sh_wave_crash, 2), "whale": (sh_whale, 2), "sand_hiss": (sh_sand_hiss, 2),
    "oblivion_wail": (sh_oblivion_wail, 2), "sovngarde_horn": (sh_sovngarde_horn, 1), "glow_chime": (sh_glow_chime, 2),
    "distant_screech": (sh_distant_screech, 2), "machinery": (sh_machinery, 3), "lava_bubble": (sh_lava_bubble, 2),
    "ice_creak": (sh_ice_creak, 2), "cicada": (sh_cicada, 2),
}


def finish_shot(y, sr, target_db=SHOT_DB, max_s=6.0, min_s=0.3):
    y = np.nan_to_num(np.asarray(y, dtype=float))
    y = filt(y, sr, "highpass", 22, 2)
    win = int(0.01 * sr)
    env = np.sqrt(np.convolve(y ** 2, np.ones(win) / win, mode="same"))
    thr = env.max() * db(-46)
    on = np.nonzero(env > thr)[0]
    s = max(0, on[0] - int(0.005 * sr))
    e = min(len(y), on[-1] + int(0.02 * sr))
    y = y[s:e]
    fo = min(int(0.12 * sr), len(y) // 4)
    if len(y) > int(max_s * sr):
        y = y[:int(max_s * sr)]
        fo = int(0.8 * sr)          # cut inside a reverb tail: fade it out gently
    fi = int(0.004 * sr)
    y[:fi] *= np.linspace(0, 1, fi)
    y[-fo:] *= np.cos(np.linspace(0, np.pi / 2, fo)) ** 2
    if len(y) < int(min_s * sr):
        y = np.concatenate([y, np.zeros(int(min_s * sr) - len(y))])
    y = y - np.mean(y) * win_grain(len(y), 0.5)
    return audiolib.loudness_normalise(y, target_db, sr, peak_db=SHOT_PEAK)


def render_shot(base, v, rng):
    fn, _ = SHOTS[base]
    return finish_shot(fn(rng, v, SHOT_SR), SHOT_SR), SHOT_SR


# =====================================================================================================  driver

def generator_for(stem):
    m = re.fullmatch(r"ambient/bed/([a-z_]+)", stem)
    if m:
        return ("bed", m.group(1)) if m.group(1) in BEDS else None
    m = re.fullmatch(r"ambient/([a-z_]+?)(\d+)", stem)
    if m and m.group(1) in SHOTS and 1 <= int(m.group(2)) <= SHOTS[m.group(1)][1]:
        return ("shot", m.group(1), int(m.group(2)))
    return None


def seam_stats(x, sr, ms=20):
    """Loop-seam check.
    ratio: |x[0] - x[-1]| (the step across the wrap) / largest adjacent-sample step within `ms` either side (<= 1: the
           wrap is just another sample step).
    drms:  change of short-term RMS (`ms` windows) across the wrap, in dB, worst channel.
    rank:  percentile of the across-wrap RMS change among all adjacent-window RMS changes in the file, taking the
           smallest over 10/20/50 ms windows (a real seam is an outlier, near 100, at every scale)."""
    x = np.atleast_2d(x)
    w = int(sr * ms / 1000)
    ratio, drms, rank = 0.0, 0.0, 0.0
    for ch in x:
        wrap = abs(ch[0] - ch[-1])
        inner = np.concatenate([np.abs(np.diff(ch[-w:])), np.abs(np.diff(ch[:w]))])
        ratio = max(ratio, wrap / (inner.max() + 1e-12))
        drms = max(drms, abs(20 * np.log10((rms(ch[-w:]) + 1e-12) / (rms(ch[:w]) + 1e-12))))
        ranks = []
        for wm in (10, 20, 50):
            ww = int(sr * wm / 1000)
            k = len(ch) // ww
            r = 20 * np.log10(np.sqrt(np.mean(ch[:k * ww].reshape(k, ww) ** 2, axis=1)) + 1e-12)
            d_seam = abs(20 * np.log10(rms(ch[-ww:]) + 1e-12) - r[0])
            ranks.append(100.0 * float(np.mean(np.abs(np.diff(r)) < d_seam)))
        rank = max(rank, min(ranks))
    return ratio, drms, rank


def write_wav(x, path, sr):
    x = np.atleast_2d(x)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(x.shape[0])
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(audiolib.to_pcm16(x))


def decode(path, sr, ch):
    raw = subprocess.run([audiolib.find_ffmpeg(), "-hide_banner", "-loglevel", "error", "-i", str(path), "-f", "f32le",
                          "-ac", str(ch), "-ar", str(sr), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, ch).T.astype(float)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("filters", nargs="*", help="only render stems containing any of these substrings")
    ap.add_argument("--wav", metavar="DIR", help="also write 16-bit WAVs into DIR")
    ap.add_argument("--list", action="store_true", help="list catalogued stems and their generators")
    ap.add_argument("--verify", action="store_true", help="decode the written beds and re-check loop seams")
    args = ap.parse_args()

    stems = sound_catalog.files("bed") + sound_catalog.files("ambient")
    missing = [s for s in stems if generator_for(s) is None]
    if args.list:
        for s in stems:
            g = generator_for(s)
            print(f"{s:40s} {'MISSING' if g is None else ' '.join(map(str, g))}")
    if missing:
        for s in missing:
            print(f"error: no generator for catalogued stem {s}", file=sys.stderr)
        return 2
    if args.list:
        return 0
    known = {f"ambient/bed/{b}" for b in BEDS} | {f"ambient/{k}{i}" for k, (_, c) in SHOTS.items() for i in range(1, c + 1)}
    for s in sorted(known - set(stems)):
        print(f"warning: generator {s} is not in the catalogue (skipped)")

    todo = [s for s in stems if not args.filters or any(f in s for f in args.filters)]
    sizes = {"bed": 0, "shot": 0}
    counts = {"bed": 0, "shot": 0}
    bad_seams = []
    t_all = time.time()
    for stem in todo:
        g = generator_for(stem)
        t0 = time.time()
        rng = seeded(stem)
        if g[0] == "bed":
            x, sr = render_bed(g[1], rng)
            q = BED_Q
        else:
            x, sr = render_shot(g[1], g[2], rng)
            q = SHOT_Q
        if not np.all(np.isfinite(x)):
            raise SystemExit(f"{stem}: non-finite samples")
        out = SOUNDS / (stem + ".ogg")
        audiolib.write_ogg(x, out, sr, quality=q)
        if args.wav:
            write_wav(x, pathlib.Path(args.wav) / (stem + ".wav"), sr)
        size = out.stat().st_size
        sizes[g[0]] += size
        counts[g[0]] += 1
        peak = 20 * np.log10(np.max(np.abs(x)) + 1e-12)
        info = f"{stem:34s} {x.shape[-1] / sr:5.1f}s {size / 1024:6.1f} KB  peak {peak:5.1f} dBFS"
        if g[0] == "bed":
            ratio, drms, rank = seam_stats(x, sr)
            ok = ratio <= 1.0 and rank <= 99.0
            if not ok:
                bad_seams.append(stem)
            info += f"  seam: jump/maxstep {ratio:4.2f}, rms step {drms:4.2f} dB (pct {rank:4.1f}) {'ok' if ok else 'SEAM?'}"
        print(f"{info}  [{time.time() - t0:4.1f}s]", flush=True)

    if args.verify:
        print("decoded-ogg seam check:")
        for stem in todo:
            g = generator_for(stem)
            if g[0] != "bed":
                continue
            y = decode(SOUNDS / (stem + ".ogg"), BED_SR, 2)
            ratio, drms, rank = seam_stats(y, BED_SR)
            fs = BEDS[g[1]][1] * BED_SR
            print(f"  {stem:34s} samples {y.shape[1]} (expected {fs})  jump/maxstep {ratio:4.2f}  rms step {drms:4.2f} dB "
                  f"(pct {rank:4.1f})  dc {np.abs(y.mean(axis=1)).max():.1e}  peak {np.abs(y).max():.3f}")

    tot = sizes["bed"] + sizes["shot"]
    print(f"beds: {counts['bed']} files, {sizes['bed'] / 1e6:.2f} MB | one-shots: {counts['shot']} files, "
          f"{sizes['shot'] / 1e6:.2f} MB | total {tot / 1e6:.2f} MB  ({time.time() - t_all:.0f}s)")
    if bad_seams:
        print("seam warnings: " + ", ".join(bad_seams))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
