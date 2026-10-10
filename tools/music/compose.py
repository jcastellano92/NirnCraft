#!/usr/bin/env python3
"""
Skycraft original soundtrack: a small procedural composer + synthesiser.

Every piece is written by a seeded composition engine (modal chord progressions, voice-led pads, motif
development for the melodies, pattern libraries for drums and ostinati) and synthesised from scratch:

  * strings / drones .... layered sections (violins, violas, cellos, basses) of detuned polyBLEP saw ensembles with
                          bow noise, per-desk timing spread, velocity-dependent brightness, zero-phase filtering
  * pads ................ triangle + saw, dark filter
  * choir ............... glottal sources (tilted saws with jitter/shimmer) through five-formant vowel filters, each
                          singer with its own vocal-tract length; slow vowel morphing on long notes
  * winds / brass ....... additive flute, recorder, whistle, ney/shakuhachi (breathy, bending), reed (duduk-like),
                          horn, low brass and fiddle with delayed vibrato, portamento, breath noise, brightness envelopes
  * plucked ............. block-vectorised Karplus-Strong harp, lute, oud (slide + tremolo), koto (bright, bent),
                          pizzicato strings, with body resonances
  * drums ............... modal timpani, taiko, frame drum, bodhran, darbuka (dum/tek/ka), water drum, rims,
                          metallic hits, clockwork ticks, cymbal swells, sub booms
  * colour .............. crystalline bells, throat-singing drones (overtone formant sweeps), steam, wind
  * space ............... early reflections + Freeverb tail (evaluated in closed form in the frequency domain and
                          FFT-convolved), decorrelated stereo return, gentle M/S widening

Performance: phrases breathe (velocity arcs over each four-chord phrase), timing drifts with a smooth humanising
curve, short notes are articulated shorter, accents fall on strong beats.

Every Tamriel province has its own sound (docs/MUSIC.md): Cyrodiil's Lydian/Ionian strings, harp and choir; Morrowind's
Phrygian-dominant drones, ney and hand drums; Black Marsh's low drones, water drums and reed; Elsweyr's hijaz oud and
frame drums; Valenwood's woodwinds; Summerset's crystalline bells, harp and choir; Hammerfell's maqam (with a
half-flat second) oud and darbuka; High Rock's lute, recorder and Celtic modes; Atmora's throat drones and horns;
Akavir's pentatonic koto, shakuhachi and taiko; and the open sea.

Nothing is sampled or transcribed: all melodies come from the random motif generator, so the music is original
to Skycraft and ships with the mod.  It is *not* Skyrim's soundtrack; players who own Skyrim can map their own
copy of it onto the same events with tools/music/import_skyrim_audio.py (see docs/MUSIC.md).

Usage:
    py -3 tools/music/compose.py                 # render every track to assets/skycraft/sounds/music/*.ogg
    py -3 tools/music/compose.py town_1 combat_2 # only these
    py -3 tools/music/compose.py -j 4            # four tracks in parallel
    py -3 tools/music/compose.py --wav out/      # also keep 16-bit WAVs
    py -3 tools/music/compose.py --list

Requires Python 3.9+, numpy, and ffmpeg built with libvorbis (found on PATH, via the FFMPEG environment variable or
in a known install location; see audiolib.py). Minecraft only plays Ogg Vorbis.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import math
import pathlib
import sys
import time
import wave

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import audiolib  # noqa: E402

# Synthesis rate. The jar budget (docs/MUSIC.md) is met by encoding long pieces at 24 kHz, Vorbis q1.
SR = 32000
REPO = pathlib.Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "mod/src/main/resources/assets/skycraft/sounds/music"
ENCODE_SR = 24000
ENCODE_Q = 1.0


# =====================================================================================================  basics

def ns(t: float) -> int:
    return max(0, int(round(t * SR)))


def hz(m):
    return 440.0 * 2.0 ** ((np.asarray(m, dtype=np.float64) - 69.0) / 12.0)


def pow2(n: int) -> int:
    return 1 << int(math.ceil(math.log2(max(2, n))))


def box(x: np.ndarray, length: int, causal: bool = False) -> np.ndarray:
    """Moving average (O(n) via cumulative sums); centred unless causal."""
    length = int(length)
    if length <= 1 or len(x) == 0:
        return x.copy()
    c = np.concatenate(([0.0], np.cumsum(x)))
    n = len(x)
    idx = np.arange(n)
    if causal:
        lo = np.maximum(0, idx + 1 - length)
        hi = idx + 1
    else:
        lo = np.clip(idx - length // 2, 0, n)
        hi = np.clip(idx - length // 2 + length, 0, n)
    return (c[hi] - c[lo]) / np.maximum(1, hi - lo)


def smooth_noise(n: int, rate: float, rng) -> np.ndarray:
    """Smooth random curve, roughly in [-1, 1], changing about `rate` times per second."""
    if n <= 0:
        return np.zeros(0)
    step = max(2, int(SR / rate))
    k = n // step + 3
    pts = rng.uniform(-1.0, 1.0, k)
    y = np.interp(np.arange(n), np.arange(k) * step, pts)
    return box(y, step) * 1.6


# ------------------------------------------------------------------------------------------------  filters

def ffilt(x: np.ndarray, H) -> np.ndarray:
    """Zero-phase FFT filter along the last axis. H(freqs_hz) -> magnitude response."""
    n = x.shape[-1]
    if n == 0:
        return x
    nfft = pow2(n + 8192)
    f = np.fft.rfftfreq(nfft, 1.0 / SR)
    X = np.fft.rfft(x, nfft, axis=-1)
    X *= H(f)
    return np.fft.irfft(X, nfft, axis=-1)[..., :n]


def lp(fc, order=2):
    return lambda f: 1.0 / np.sqrt(1.0 + (f / fc) ** (2 * order))


def hp(fc, order=2):
    return lambda f: 1.0 / np.sqrt(1.0 + (fc / np.maximum(f, 1e-3)) ** (2 * order))


def bp(lo, hi, order=2):
    a, b = hp(lo, order), lp(hi, order)
    return lambda f: a(f) * b(f)


def chain(*hs):
    def h(f):
        out = np.ones_like(f)
        for g in hs:
            out = out * g(f)
        return out
    return h


def peaks(spec, floor=0.0):
    """Sum of Lorentzian resonances: spec = [(freq, bandwidth, gain), ...]."""
    def h(f):
        out = np.full_like(f, floor)
        for fc, bw, g in spec:
            out += g / (1.0 + ((f - fc) / (bw * 0.5)) ** 2)
        return out
    return h


def shelf(fc, gain):
    """Smooth high shelf: 1 below fc, `gain` above."""
    return lambda f: 1.0 + (gain - 1.0) / (1.0 + (fc / np.maximum(f, 1e-3)) ** 2)


# ------------------------------------------------------------------------------------------------  oscillators

def saw(freq: np.ndarray, phase0: float = 0.0) -> np.ndarray:
    """Band-limited (polyBLEP) sawtooth for a per-sample frequency array."""
    dt = np.minimum(np.asarray(freq, dtype=np.float64) / SR, 0.45)
    ph = (np.cumsum(dt) + phase0) % 1.0
    y = 2.0 * ph - 1.0
    m = ph < dt
    t = ph[m] / dt[m]
    y[m] -= t + t - t * t - 1.0
    m = ph > 1.0 - dt
    t = (ph[m] - 1.0) / dt[m]
    y[m] -= t * t + t + t + 1.0
    return y


def tri(freq: np.ndarray, phase0: float = 0.0) -> np.ndarray:
    ph = (np.cumsum(np.asarray(freq) / SR) + phase0) % 1.0
    return 2.0 * np.abs(2.0 * ph - 1.0) - 1.0


def env_note(dur: float, attack: float, release: float, decay_to: float = 1.0, decay_time: float = 1.0) -> np.ndarray:
    """Raised-cosine attack, optional exponential settle to `decay_to`, raised-cosine release after `dur`."""
    n = ns(dur + release)
    d = min(n, max(1, ns(dur)))
    e = np.ones(n)
    a = min(n, max(1, ns(attack)))
    e[:a] = 0.5 - 0.5 * np.cos(np.linspace(0.0, np.pi, a))
    if decay_to != 1.0 and a < n:
        t = np.arange(n - a) / SR
        e[a:] = decay_to + (1.0 - decay_to) * np.exp(-t / max(1e-3, decay_time))
    r = n - d
    if r > 0:
        level = e[d - 1]
        e[d:] = level * (0.5 + 0.5 * np.cos(np.linspace(0.0, np.pi, r)))
    return e


def pan2(x: np.ndarray, p: float) -> np.ndarray:
    a = (max(-1.0, min(1.0, p)) + 1.0) * math.pi / 4.0
    return np.vstack((x * math.cos(a), x * math.sin(a)))


def softclip(x, drive=1.0):
    return np.tanh(x * drive) / math.tanh(drive)


# =====================================================================================================  mixing

class Mix:
    """Stereo dry bus + reverb send bus."""

    def __init__(self, seconds: float):
        self.n = ns(seconds)
        self.dry = np.zeros((2, self.n))
        self.send = np.zeros((2, self.n))

    def add(self, sig: np.ndarray, t: float, gain: float = 1.0, send: float = 0.3, pan: float = 0.0):
        if sig.ndim == 1:
            sig = pan2(sig, pan)
        s = ns(t)
        if s >= self.n or gain == 0.0:
            return
        e = min(self.n, s + sig.shape[1])
        seg = sig[:, : e - s] * gain
        self.dry[:, s:e] += seg
        if send > 0:
            self.send[:, s:e] += seg * send


COMBS = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617]
ALLPASSES = [556, 441, 341, 225]
_IR_CACHE: dict = {}


def freeverb_ir(room: float, damp: float, seconds: float = 6.0, predelay: float = 0.02, tone: float = 9000.0):
    """Stereo impulse response of Jezar's Freeverb (8 damped combs + 4 allpasses per side, stereo spread 23),
    computed exactly from its transfer function in the frequency domain, energy-normalised."""
    key = (room, damp, seconds, predelay, tone)
    if key in _IR_CACHE:
        return _IR_CACHE[key]
    length = ns(seconds)
    nfft = pow2(length * 2)
    w = 2.0 * np.pi * np.arange(nfft // 2 + 1) / nfft
    zi = np.exp(-1j * w)
    fb = room * 0.28 + 0.7
    d = damp * 0.4
    irs = []
    for ch in range(2):
        H = np.zeros_like(zi)
        for c in COMBS:
            D = int(round((c + 23 * ch) * SR / 44100))
            zD = np.exp(-1j * w * D)
            H += zD / (1.0 - fb * (1.0 - d) * zD / (1.0 - d * zi))
        for a in ALLPASSES:
            D = int(round((a + 23 * ch) * SR / 44100))
            zD = np.exp(-1j * w * D)
            H *= (-1.0 + 1.5 * zD) / (1.0 - 0.5 * zD)
        ir = np.fft.irfft(H * 0.015, nfft)[:length]
        irs.append(ir)
    ir = np.vstack(irs)
    ir = ffilt(ir, chain(lp(tone, 1), hp(120, 1)))
    fade = ns(seconds * 0.25)
    ir[:, -fade:] *= np.linspace(1.0, 0.0, fade) ** 2
    # the diffuse tail starts after the pre-delay; early reflections (a sparse, decorrelated pattern of taps whose
    # density and darkness grow with the room) fill the gap so instruments sit *in* a hall rather than in front of it
    tail = np.concatenate((np.zeros((2, ns(predelay + 0.012))), ir), axis=1)
    tail /= math.sqrt(np.sum(tail ** 2) / 2.0)
    er = np.zeros_like(tail)
    rng = np.random.default_rng(int(room * 1000) + 7)
    span = 0.018 + 0.05 * room
    for ch in range(2):
        for _ in range(14):
            t = rng.uniform(0.004, span)
            g = rng.uniform(0.4, 1.0) * math.exp(-t / (span * 0.6)) * (1 if rng.random() < 0.8 else -1)
            i = ns(t)
            if i < er.shape[1]:
                er[ch, i] += g
    er = ffilt(er, chain(lp(tone * 0.8, 1), hp(150, 1)))
    er /= math.sqrt(np.sum(er ** 2) / 2.0) + 1e-12
    ir = tail + er * (0.55 - 0.25 * room)
    ir /= math.sqrt(np.sum(ir ** 2) / 2.0)
    _IR_CACHE[key] = ir
    return ir


def fft_convolve(x: np.ndarray, ir: np.ndarray) -> np.ndarray:
    nfft = pow2(len(x) + len(ir))
    return np.fft.irfft(np.fft.rfft(x, nfft) * np.fft.rfft(ir, nfft), nfft)[: len(x)]


def render_mix(mix: Mix, room=0.85, damp=0.45, wet=0.35, width=1.0, tone=9000.0, predelay=0.025) -> np.ndarray:
    ir = freeverb_ir(room, damp, predelay=predelay, tone=tone)
    wl = fft_convolve(mix.send[0], ir[0])
    wr = fft_convolve(mix.send[1], ir[1])
    w1 = 0.5 + width / 2.0
    w2 = (1.0 - width) / 2.0
    wet_l = wl * w1 + wr * w2
    wet_r = wr * w1 + wl * w2
    # gentle M/S widening of the reverb return
    mid = (wet_l + wet_r) * 0.5
    side = (wet_l - wet_r) * 0.5 * 1.25
    out = mix.dry + wet * np.vstack((mid + side, mid - side))
    # widen the whole image a little above ~300 Hz (keeps the bass mono and centred)
    mid = (out[0] + out[1]) * 0.5
    side = (out[0] - out[1]) * 0.5
    side = side + 0.18 * ffilt(side, hp(300, 1))
    return np.vstack((mid + side, mid - side))


def master(x: np.ndarray, target_db: float = -20.0, max_len: float = 0.0) -> np.ndarray:
    """High-pass, loudness normalisation (loud passages at target RMS), slow compression, soft peak limiting."""
    x = ffilt(x, hp(28.0, 2))
    mono = np.mean(x, axis=0)
    env = np.sqrt(box(mono ** 2, ns(0.4)) + 1e-12)
    loud = np.percentile(env, 92)
    x = x * (10 ** (target_db / 20.0) / max(loud, 1e-9))
    env = env * (10 ** (target_db / 20.0) / max(loud, 1e-9))
    thr = 10 ** ((target_db + 2.0) / 20.0)
    gain = np.where(env > thr, (env / thr) ** (1.0 / 2.2 - 1.0), 1.0)
    gain = box(gain, ns(0.25))
    x = x * gain
    k, c = 0.55, 0.94
    a = np.abs(x)
    over = a > k
    x[over] = np.sign(x[over]) * (k + (c - k) * np.tanh((a[over] - k) / (c - k)))
    # trim the reverb tail once it is inaudible, fade both ends
    level = box(np.max(np.abs(x), axis=0), ns(0.05))
    alive = np.nonzero(level > 10 ** (-66 / 20.0))[0]
    end = min(x.shape[1], (alive[-1] if len(alive) else x.shape[1]) + ns(0.3))
    if max_len > 0:
        end = min(end, ns(max_len))
    x = x[:, :end]
    f_in, f_out = ns(0.01), min(ns(1.5), end // 4)
    x[:, :f_in] *= np.linspace(0, 1, f_in)
    x[:, -f_out:] *= np.linspace(1, 0, f_out) ** 1.5
    return x


# =====================================================================================================  instruments

VOWELS_HIGH = {
    "a": [(800, 80, 1.0), (1150, 90, 0.5), (2900, 120, 0.25), (3900, 130, 0.1), (4950, 140, 0.05)],
    "o": [(450, 70, 1.0), (800, 80, 0.3), (2830, 100, 0.08), (3800, 130, 0.06), (4950, 135, 0.02)],
    "u": [(350, 50, 1.0), (600, 60, 0.12), (2700, 170, 0.04), (2900, 180, 0.03), (3300, 200, 0.01)],
    "e": [(400, 60, 1.0), (1600, 80, 0.25), (2700, 120, 0.18), (3300, 150, 0.1), (4950, 200, 0.02)],
}
VOWELS_LOW = {
    "a": [(600, 60, 1.0), (1040, 70, 0.45), (2250, 110, 0.35), (2450, 120, 0.35), (2750, 130, 0.1)],
    "o": [(400, 40, 1.0), (750, 80, 0.28), (2400, 100, 0.09), (2600, 120, 0.1), (2900, 120, 0.01)],
    "u": [(250, 60, 1.0), (595, 40, 0.12), (2400, 100, 0.03), (2675, 120, 0.02), (2950, 120, 0.01)],
    "e": [(400, 40, 1.0), (1620, 80, 0.25), (2400, 100, 0.35), (2800, 120, 0.25), (3100, 120, 0.1)],
}


def strings_note(midi, dur, rng, attack=1.2, release=2.0, bright=1.0, voices=4, detune=9.0, vib=0.09,
                 spread=0.8, short=False, bow=0.0, vel=1.0):
    """Ensemble of detuned band-limited saws, low-passed: bowed strings / drones. Every player starts a few
    milliseconds apart; `bow` adds rosin noise to the attack; louder (`vel`) notes are brighter."""
    n = ns(dur + release)
    t = np.arange(n) / SR
    f0 = float(hz(midi))
    out = np.zeros((2, n))
    for v in range(voices):
        cents = rng.uniform(-detune, detune)
        rate = rng.uniform(4.4, 5.6)
        vibenv = np.clip((t - 0.35) / 1.2, 0.0, 1.0) * vib if not short else 0.0
        drift = smooth_noise(n, 0.6, rng) * 0.035
        m = midi + cents / 100.0 + vibenv * np.sin(2 * np.pi * rate * t + rng.uniform(0, 6.28)) + drift
        y = saw(hz(m), rng.random())
        if voices > 1 and not short:
            lag = ns(rng.uniform(0.0, 0.035))
            y = np.concatenate((np.zeros(lag), y[: n - lag]))
        p = (-1.0 + 2.0 * v / max(1, voices - 1)) * spread if voices > 1 else 0.0
        out += pan2(y, p)
    bright = bright * (0.75 + 0.35 * vel)
    cutoff = min(6500.0, f0 * (2.5 + 5.0 * bright) + 300.0 * bright)
    out = ffilt(out, chain(lp(cutoff, 2), hp(max(30.0, f0 * 0.6), 1), peaks([(450, 500, 0.35), (1400, 900, 0.25)], 1.0)))
    if bow > 0:
        k = min(n, ns(0.25 + attack * 0.3))
        nb = ffilt(rng.standard_normal((2, k)), bp(f0 * 2.0, min(9000.0, f0 * 14.0), 1))
        nb *= (np.exp(-np.arange(k) / (SR * 0.06)) * (1 - np.exp(-np.arange(k) / (SR * 0.004))))[None, :]
        out[:, :k] += nb * bow * 0.25
    out *= env_note(dur, attack, release) / math.sqrt(voices)
    return out


# Orchestral string sections: (name, low midi, high midi, players, detune cents, vibrato, brightness, pan)
SECTIONS = [("violins", 64, 96, 5, 7.0, 0.11, 1.0, -0.45), ("violas", 55, 76, 4, 8.0, 0.09, 0.8, 0.2),
            ("cellos", 43, 64, 4, 8.0, 0.08, 0.6, 0.45), ("basses", 28, 48, 3, 6.0, 0.05, 0.35, 0.1)]


def section_for(midi):
    for s in SECTIONS:
        if s[1] <= midi <= s[2]:
            return s
    return SECTIONS[0] if midi > 64 else SECTIONS[-1]


def pad_note(midi, dur, rng, attack=2.0, release=3.0, cutoff=1100.0):
    n = ns(dur + release)
    t = np.arange(n) / SR
    out = np.zeros((2, n))
    for v, p in enumerate((-0.6, 0.6)):
        drift = smooth_noise(n, 0.4, rng) * 0.05 + (0.06 if v else -0.06)
        f = hz(midi + drift + 0.03 * np.sin(2 * np.pi * 0.3 * t + v))
        y = tri(f, rng.random()) + 0.35 * saw(f * 1.003, rng.random())
        out += pan2(y, p)
    out = ffilt(out, chain(lp(cutoff, 2), hp(60, 1)))
    return out * env_note(dur, attack, release) * 0.6


def choir_note(midi, dur, rng, vowel="a", voices=4, attack=1.0, release=1.8, bright=1.0, breath=0.05,
               vib=0.16, low=None):
    """Ensemble 'ahh'/'ooh': saw sources with independent vibrato & jitter through vowel formants."""
    n = ns(dur + release)
    t = np.arange(n) / SR
    low = midi < 58 if low is None else low
    tables = VOWELS_LOW if low else VOWELS_HIGH
    f0 = float(hz(midi))
    # two groups of singers with different vocal-tract lengths (formants scaled), each a sum of tilted glottal sources
    groups = [np.zeros((2, n)), np.zeros((2, n))]
    for v in range(voices):
        cents = rng.uniform(-11, 11)
        rate = rng.uniform(4.8, 6.0)
        depth = vib * rng.uniform(0.7, 1.3)
        vibenv = np.clip((t - rng.uniform(0.15, 0.4)) / 0.8, 0.0, 1.0) * depth
        jitter = smooth_noise(n, 3.0, rng) * 0.05 + smooth_noise(n, 0.5, rng) * 0.04
        m = midi + cents / 100.0 + vibenv * np.sin(2 * np.pi * rate * t + rng.uniform(0, 6.28)) + jitter
        y = saw(hz(m), rng.random())
        amp = 1.0 + 0.15 * smooth_noise(n, 1.5, rng) + 0.04 * smooth_noise(n, 12.0, rng)  # shimmer
        lag = ns(rng.uniform(0.0, 0.05))
        y = np.concatenate((np.zeros(lag), (y * amp)[: n - lag]))
        groups[v % 2] += pan2(y, rng.uniform(-0.8, 0.8))
    tilt = lp(max(300.0, f0 * 2.5), 1)  # glottal roll-off on top of the saw's own -6 dB/oct
    morph = dur > 3.5 and vowel in "ao"
    out = np.zeros((2, n))
    for gi, g in enumerate(groups):
        g = g + breath * pan2(rng.standard_normal(n), 0.0) * 0.35
        scale = 0.96 if gi == 0 else 1.05
        def vowel_filter(vw):
            table = tables[vw]
            return peaks([(f * scale, bw * 1.6, a) for f, bw, a in table], 0.012)
        common = chain(tilt, lp(2600 + 2600 * bright, 1), hp(max(80.0, f0 * 0.7), 2))
        a = ffilt(g, chain(vowel_filter(vowel), common))
        if morph:
            # long notes drift slowly towards a neighbouring vowel, as a real choir's vowels never stand still
            b = ffilt(g, chain(vowel_filter("o" if vowel == "a" else "u"), common))
            x = 0.5 - 0.5 * np.cos(np.clip(t / max(1.0, dur), 0, 1) * np.pi)
            a = a * (1 - 0.6 * x) + b * (0.6 * x)
        out += a
    return out * env_note(dur, attack, release) / math.sqrt(voices) * 2.0


def ks_pluck(midi, dur, rng, decay=2.0, bright=0.6, pick=0.14):
    """Karplus-Strong string, vectorised one period at a time; linear fractional delay keeps it in tune."""
    f = float(hz(midi))
    n = ns(dur)
    period = SR / f
    P = int(period - 0.5)
    frac = period - 0.5 - P
    if P < 4:
        P, frac = 4, 0.0
    y = np.zeros(n + P + 2)
    exc = rng.uniform(-1.0, 1.0, P)
    exc = box(exc, 1 + int((1.0 - bright) * 7))
    k = max(1, int(pick * P))
    exc = exc - np.roll(exc, k)
    exc -= exc.mean()
    y[2:2 + P] = exc
    g = 10 ** (-3.0 * period / (decay * SR))
    a0, a1, a2 = 0.5 * (1.0 - frac), 0.5, 0.5 * frac
    s = 2 + P
    total = len(y)
    while s < total:
        e = min(s + P, total)
        y[s:e] = g * (a0 * y[s - P:e - P] + a1 * y[s - P - 1:e - P - 1] + a2 * y[s - P - 2:e - P - 2])
        s = e
    out = y[2:2 + n]
    fade = min(n, ns(0.05))
    out[-fade:] *= np.linspace(1, 0, fade)
    return out


def harp(midi, dur, rng, bright=0.7):
    y = ks_pluck(midi, dur, rng, decay=2.5 + max(0.0, (70 - midi) * 0.06), bright=bright, pick=0.18)
    return ffilt(y, chain(hp(90, 1), lp(5000, 1))) * 0.5


def lute(midi, dur, rng, bright=0.55):
    y = ks_pluck(midi, dur, rng, decay=1.1 + max(0.0, (64 - midi) * 0.04), bright=bright, pick=0.11)
    return ffilt(y, chain(hp(85, 1), lp(4200, 1), peaks([(230, 120, 0.8), (480, 200, 0.4), (2600, 900, 0.25)], 0.7))) * 0.6


def oud(midi, dur, rng, bright=0.5):
    """Fretless short-necked lute: warm, woody, quick decay, with a soft plectrum thump and a tiny slide into pitch."""
    y = ks_pluck(midi - 0.0, dur, rng, decay=0.9 + max(0.0, (60 - midi) * 0.05), bright=bright, pick=0.2)
    n = len(y)
    thump = noise_burst(min(dur, 0.08), rng, (80, 900), 0.008) * 0.25
    y[: len(thump)] += thump[: n]
    return ffilt(y, chain(hp(70, 1), lp(3800, 1), peaks([(140, 80, 1.0), (290, 140, 0.7), (650, 300, 0.35),
                                                         (2200, 900, 0.2)], 0.45))) * 0.7


def koto(midi, dur, rng, bright=0.85):
    """Long zither with movable bridges: bright, twangy attack, singing decay."""
    y = ks_pluck(midi, dur, rng, decay=1.8 + max(0.0, (66 - midi) * 0.05), bright=bright, pick=0.07)
    return ffilt(y, chain(hp(110, 1), lp(6500, 1), peaks([(380, 200, 0.6), (1300, 500, 0.5), (3100, 1200, 0.45)], 0.6))) * 0.55


def pizz(midi, dur, rng, bright=0.45):
    """Pizzicato string: short, round."""
    y = ks_pluck(midi, min(dur, 1.2), rng, decay=0.35 + max(0.0, (60 - midi) * 0.02), bright=bright, pick=0.3)
    return ffilt(y, chain(hp(60, 1), lp(3000, 1), peaks([(300, 250, 0.7), (900, 500, 0.3)], 0.7))) * 0.8


def crystal(midi, dur, rng, bright=0.7):
    """Crystalline glass bell: inharmonic partials, two slightly detuned strikes (slow shimmering beats)."""
    n = ns(dur)
    t = np.arange(n) / SR
    f = float(hz(midi))
    y = np.zeros(n)
    for det in (0.0, rng.uniform(0.6, 1.6)):
        for r, a, d in ((1.0, 1.0, 3.2), (2.756, 0.45 * bright, 1.6), (5.404, 0.22 * bright, 0.8), (8.933, 0.1 * bright, 0.4),
                        (13.34, 0.05 * bright, 0.25)):
            fr = f * r + det * r
            if fr < SR / 2.2:
                y += a * np.sin(2 * np.pi * fr * t + rng.random() * 6.28) * np.exp(-t / d)
    y *= np.minimum(1.0, t / 0.002)
    return y * 0.22


def darbuka(kind, vel, rng):
    """Goblet drum strokes: 'D' dum (deep centre), 'T' tek (sharp rim), 'K' ka (other hand, softer rim)."""
    if kind == "D":
        y = modal_hit(95, [1.0, 1.59, 2.13], [1.0, 0.25, 0.1], [0.28, 0.1, 0.06], 0.7, rng, drop=0.2, drop_time=0.02)
        y += noise_burst(0.7, rng, (100, 1500), 0.015) * 0.4
        return y * vel * 0.7
    y = modal_hit(rng.uniform(560, 620), [1.0, 1.48, 2.3], [0.7, 0.4, 0.25], [0.05, 0.03, 0.02], 0.25, rng)
    y += noise_burst(0.25, rng, (1500, 9000), 0.012) * (0.9 if kind == "T" else 0.5)
    return y * vel * (0.45 if kind == "T" else 0.3)


def water_drum(vel, rng, f=70.0):
    """Argonian water drum: a low, wobbling membrane over water, with a bubbling tail."""
    dur = 1.6
    n = ns(dur)
    t = np.arange(n) / SR
    bend = 1.0 + 0.18 * np.exp(-t / 0.05) + 0.03 * np.sin(2 * np.pi * 5.5 * t) * np.exp(-t / 0.4)
    y = np.zeros(n)
    for r, a, d in ((1.0, 1.0, 0.6), (1.52, 0.35, 0.3), (2.1, 0.15, 0.15)):
        y += a * np.sin(2 * np.pi * np.cumsum(f * r * bend) / SR + rng.random() * 6) * np.exp(-t / d)
    bub = np.zeros(n)
    for _ in range(int(rng.integers(3, 7))):
        s = ns(rng.uniform(0.02, 0.5))
        k = min(n - s, ns(0.04))
        tt = np.arange(k) / SR
        fb = rng.uniform(400, 1100)
        bub[s:s + k] += np.sin(2 * np.pi * fb * (1 + 3 * tt) * tt) * np.exp(-tt / 0.012) * rng.uniform(0.05, 0.15)
    return softclip((y + bub) * vel * 0.7, 1.3)


def clock_tick(vel, rng, f=None):
    """Dwemer clockwork: a small, dry metallic click."""
    f = f or rng.uniform(1800, 2600)
    y = modal_hit(f, [1.0, 1.73, 2.61], [1.0, 0.5, 0.3], [0.025, 0.015, 0.01], 0.12, rng)
    y += noise_burst(0.12, rng, (3000, 9000), 0.004) * 0.4
    return y * vel * 0.4


def steam(dur, rng):
    n = ns(dur)
    t = np.arange(n) / SR
    y = ffilt(rng.standard_normal(n), bp(1800, 9000, 2))
    e = np.minimum(1.0, t / 0.05) * np.exp(-t / (dur * 0.45))
    return y * e * 0.25


def throat_note(midi, dur, rng, attack=2.0, release=3.0, sweep=0.12):
    """Throat singing: a low growling drone whose sharp formant glides between overtones (6th-12th harmonic)."""
    n = ns(dur + release)
    t = np.arange(n) / SR
    f0 = float(hz(midi))
    m = midi + smooth_noise(n, 0.4, rng) * 0.03
    y = saw(hz(m), rng.random()) + 0.5 * saw(hz(m - 12.0), rng.random())  # sub-harmonic growl (kargyraa)
    y = ffilt(y, lp(max(300.0, f0 * 3.0), 1))
    body = ffilt(y, peaks([(450, 120, 1.0), (900, 160, 0.4)], 0.05))
    # the singing overtone: a narrow resonance whose centre wanders slowly over the harmonic series
    k = 9.0 + 3.0 * smooth_noise(n, sweep, rng)
    centre = f0 * np.round(k * 2) / 2
    out = np.zeros(n)
    hop = ns(0.25)
    for s in range(0, n, hop):  # piecewise filtering: the formant only moves a little per 250 ms block
        e = min(n, s + hop * 2)
        fc = float(np.mean(centre[s:e]))
        seg = ffilt(y[s:e], peaks([(fc, 35, 3.0)], 0.0))
        w = np.hanning(e - s) if e - s > 8 else np.ones(e - s)
        out[s:e] += seg * w
    sig = body * 0.8 + out * 0.6
    sig = sig * env_note(dur, attack, release)
    return pan2(sig, 0.0) * 0.5


def bell(midi, dur, rng, bright=0.5):
    """Glassy celesta/bell: few inharmonic partials with exponential decays."""
    n = ns(dur)
    t = np.arange(n) / SR
    f = float(hz(midi))
    y = np.zeros(n)
    for r, a, d in ((1.0, 1.0, 2.2), (2.0, 0.35 * bright, 1.2), (3.01, 0.12 * bright, 0.6), (4.17, 0.08 * bright, 0.4)):
        if f * r < SR / 2.2:
            y += a * np.sin(2 * np.pi * f * r * t + rng.random() * 6.28) * np.exp(-t / d)
    y *= np.minimum(1.0, t / 0.003)
    return y * 0.4


def modal_hit(f0, ratios, amps, decays, dur, rng, drop=0.0, drop_time=0.05):
    n = ns(dur)
    t = np.arange(n) / SR
    bend = 1.0 + drop * np.exp(-t / drop_time)
    y = np.zeros(n)
    for r, a, d in zip(ratios, amps, decays):
        if f0 * r * (1 + drop) >= SR / 2.2:
            continue
        ph = 2 * np.pi * np.cumsum(f0 * r * bend) / SR + rng.random() * 6.28
        y += a * np.sin(ph) * np.exp(-t / d)
    return y


def noise_burst(dur, rng, band, decay):
    n = ns(dur)
    t = np.arange(n) / SR
    return ffilt(rng.standard_normal(n), bp(*band)) * np.exp(-t / decay)


def timpani(midi, vel, rng, dur=3.0):
    f = float(hz(midi))
    y = modal_hit(f, [1.0, 1.504, 1.742, 2.0, 2.245, 2.494], [1.0, 0.55, 0.3, 0.35, 0.18, 0.12],
                  [1.8, 1.1, 0.7, 0.8, 0.5, 0.4], dur, rng, drop=0.04, drop_time=0.06)
    y += noise_burst(dur, rng, (60, 900), 0.03) * 0.5
    return softclip(y * vel * 0.6, 1.2)


def taiko(vel, rng, f=56.0, dur=1.6):
    y = modal_hit(f, [1.0, 1.58, 2.14], [1.0, 0.35, 0.15], [0.55, 0.25, 0.15], dur, rng, drop=0.6, drop_time=0.035)
    y += noise_burst(dur, rng, (90, 1400), 0.05) * 0.7
    return softclip(y * vel, 1.8) * 0.8


def small_drum(vel, rng, f=150.0):
    y = modal_hit(f, [1.0, 1.6, 2.3], [1.0, 0.4, 0.2], [0.22, 0.12, 0.08], 0.6, rng, drop=0.25, drop_time=0.02)
    y += noise_burst(0.6, rng, (300, 4000), 0.025) * 0.6
    return y * vel * 0.6


def frame_drum(vel, rng, f=105.0, slap=False):
    y = modal_hit(f, [1.0, 1.59, 2.28], [1.0, 0.3, 0.12], [0.35, 0.15, 0.1], 0.8, rng, drop=0.12, drop_time=0.02)
    y += noise_burst(0.8, rng, (800, 6000) if slap else (200, 2500), 0.02) * (0.9 if slap else 0.4)
    return y * vel * 0.55


def rim(vel, rng):
    return noise_burst(0.15, rng, (1800, 7000), 0.012) * vel * 0.5 + modal_hit(
        820, [1, 1.7], [0.3, 0.15], [0.03, 0.02], 0.15, rng) * vel


def metal_hit(rng, f0=None, dur=6.0, bright=1.0):
    f0 = f0 or rng.uniform(170, 420)
    ratios = [1.0, 2.32, 4.25, 6.63, 9.38, 12.1]
    ratios = [r * rng.uniform(0.985, 1.015) for r in ratios]
    amps = [1.0, 0.6 * bright, 0.4 * bright, 0.25 * bright, 0.15 * bright, 0.08 * bright]
    decays = [dur * 0.55, dur * 0.35, dur * 0.22, dur * 0.14, dur * 0.08, dur * 0.05]
    y = modal_hit(f0, ratios, amps, decays, dur, rng)
    nb = noise_burst(0.3, rng, (2000, 9000), 0.01) * 0.3 * bright
    y[: len(nb)] += nb[: len(y)]
    return y * 0.35


def boom(rng, f=38.0, dur=4.0):
    y = modal_hit(f, [1.0, 2.01], [1.0, 0.2], [1.4, 0.5], dur, rng, drop=1.0, drop_time=0.08)
    y += noise_burst(dur, rng, (40, 500), 0.12) * 0.6
    return softclip(y, 1.5) * 0.9


def cymbal_swell(dur, rng):
    n = ns(dur)
    t = np.arange(n) / SR
    y = ffilt(rng.standard_normal((2, n)), chain(hp(3500, 2), lp(14000, 1)))
    return y * ((t / dur) ** 3)[None, :] * 0.18


def wind(dur, rng):
    n = ns(dur)
    a = ffilt(rng.standard_normal(n), bp(250, 900, 2)) * (0.55 + 0.45 * smooth_noise(n, 0.15, rng))
    b = ffilt(rng.standard_normal(n), bp(700, 2600, 2)) * (0.5 + 0.5 * smooth_noise(n, 0.25, rng)) * 0.5
    sig = np.vstack((a + 0.6 * b, 0.6 * a + b))
    return sig * 0.25


# ------------------------------------------------------------------------------------------------  lead voices

LEAD_TIMBRES = {
    # harmonics, attack, release, vibrato depth (semitones), vibrato rate, breath, glide (s), brightness spread
    "flute": dict(harm=[1.0, 0.42, 0.18, 0.1, 0.05, 0.025], attack=0.09, release=0.25, vib=0.22, rate=5.0,
                  breath=0.04, glide=0.05, add=False),
    "whistle": dict(harm=[1.0, 0.05, 0.02], attack=0.12, release=0.4, vib=0.35, rate=4.2, breath=0.03,
                    glide=0.18, add=False),
    "horn": dict(harm=None, attack=0.12, release=0.35, vib=0.14, rate=4.8, breath=0.0, glide=0.06, add=True,
                 bright=(2.2, 4.5)),
    "brass_low": dict(harm=None, attack=0.03, release=0.12, vib=0.0, rate=5.0, breath=0.0, glide=0.0, add=True,
                      bright=(2.0, 7.0)),
    "fiddle": dict(harm=None, attack=0.06, release=0.2, vib=0.18, rate=6.0, breath=0.0, glide=0.04, add=True,
                   bright=(6.0, 6.0), fiddle=True),
    "recorder": dict(harm=[1.0, 0.2, 0.12, 0.05, 0.02], attack=0.05, release=0.15, vib=0.06, rate=5.5, breath=0.025,
                     glide=0.03, add=False),
    # end-blown reed flute: very breathy, scoops up into notes, wide slow vibrato
    "ney": dict(harm=[1.0, 0.3, 0.12, 0.06], attack=0.14, release=0.35, vib=0.28, rate=4.6, breath=0.055, glide=0.14,
                add=False, scoop=0.7),
    "shakuhachi": dict(harm=[1.0, 0.22, 0.09, 0.03], attack=0.1, release=0.45, vib=0.32, rate=4.2, breath=0.075,
                       glide=0.16, add=False, scoop=1.0),
    # double reed (duduk-like): dark, nasal, intimate
    "reed": dict(harm=None, attack=0.1, release=0.3, vib=0.16, rate=5.2, breath=0.0, glide=0.09, add=True,
                 bright=(3.0, 3.5), reed=True),
}


def render_lead(notes, timbre: str, rng) -> tuple[float, np.ndarray]:
    """One continuous monophonic line (notes = [(t, dur, midi, vel)], contiguous) with portamento & vibrato."""
    spec = LEAD_TIMBRES[timbre]
    t0 = notes[0][0]
    end = notes[-1][0] + notes[-1][1]
    n = ns(end - t0 + spec["release"] + 0.05)
    pitch = np.zeros(n)
    vib_env = np.zeros(n)
    amp = np.zeros(n)
    onsets = np.zeros(n)
    bright = np.zeros(n)
    for i, (t, d, m, v) in enumerate(notes):
        s = ns(t - t0)
        e = ns(notes[i + 1][0] - t0) if i + 1 < len(notes) else n
        pitch[s:e] = m
        if i == 0:
            pitch[:s] = m
        ln = e - s
        if d > 0.35 and ln > 0:
            tt = np.arange(ln) / SR
            vib_env[s:e] = np.clip((tt - 0.22) / 0.5, 0.0, 1.0)
        en = env_note(d, spec["attack"], spec["release"], decay_to=0.8 if spec["add"] else 0.92, decay_time=0.8)
        en = en * v
        seg = amp[s:s + len(en)]
        amp[s:s + len(en)] = np.maximum(seg, en[: len(seg)])
        k = min(n - s, ns(0.06))
        onsets[s:s + k] += v * np.exp(-np.arange(k) / (SR * 0.015))
        b = env_note(d, 0.02, spec["release"], decay_to=0.55, decay_time=0.35) * v
        seg = bright[s:s + len(b)]
        bright[s:s + len(b)] = np.maximum(seg, b[: len(seg)])
    if spec["glide"] > 0:
        g = ns(spec["glide"])
        pitch = box(box(pitch, g, causal=True), g, causal=True)
    t = np.arange(n) / SR
    rate = spec["rate"] * (1.0 + 0.06 * smooth_noise(n, 0.5, rng))
    vib = spec["vib"] * vib_env * np.sin(2 * np.pi * np.cumsum(rate) / SR)
    drift = smooth_noise(n, 0.8, rng) * 0.04
    m = pitch + vib + drift
    if spec["add"]:
        # lip scoop at onsets for brass
        m = m - 0.25 * onsets / max(1e-6, onsets.max() or 1.0) * (timbre != "fiddle")
    if spec.get("scoop"):
        # breath bends: start a little flat and lift into the note (meri/kari on the shakuhachi, the ney's slide)
        m = m - spec["scoop"] * box(onsets / max(1e-6, onsets.max() or 1.0), ns(0.06), causal=True)
    f = hz(m)
    phase = 2 * np.pi * np.cumsum(f) / SR
    y = np.zeros(n)
    if not spec["add"]:
        for k, a in enumerate(spec["harm"], start=1):
            y += a * np.sin(k * phase + 0.3 * k)
        y *= amp
        nb = rng.standard_normal(n)
        band = bp(1500, 7000, 1) if not spec.get("scoop") else chain(bp(900, 4500, 1), peaks([(float(np.median(f)) * 2.0, 400, 1.0)], 0.5))
        breath = ffilt(nb, band) * (spec["breath"] * amp + 0.18 * spec["breath"] * 8 * onsets)
        y += breath
    else:
        lo, span = spec["bright"]
        fmax = float(np.max(f))
        kmax = int(min(40, (SR * 0.45) / max(fmax, 20.0)))
        fc = f * (lo + span * bright)
        for k in range(1, kmax + 1):
            a = (k ** -0.9) / np.sqrt(1.0 + (k * f / fc) ** 4)
            y += a * np.sin(k * phase + 0.5 * k)
        y *= amp
        if spec.get("fiddle"):
            y = ffilt(y, chain(peaks([(290, 150, 0.8), (1000, 400, 0.4), (2800, 900, 0.7)], 0.6), lp(6000, 1)))
            y += ffilt(rng.standard_normal(n), bp(2000, 6000, 1)) * amp * 0.015
        elif spec.get("reed"):
            y = ffilt(y, chain(peaks([(520, 220, 1.0), (1250, 420, 0.55), (2500, 800, 0.25)], 0.2), lp(4200, 1)))
            y += ffilt(rng.standard_normal(n), bp(1200, 4000, 1)) * amp * 0.02
        else:
            y = ffilt(y, lp(5000, 1))
    return t0, y


def lead_groups(notes, max_gap=0.12):
    groups, cur = [], []
    for nt in notes:
        if cur and nt[0] - (cur[-1][0] + cur[-1][1]) > max_gap:
            groups.append(cur)
            cur = []
        cur.append(nt)
    if cur:
        groups.append(cur)
    return groups


# =====================================================================================================  theory

MODES = {
    "ionian": [0, 2, 4, 5, 7, 9, 11],
    "dorian": [0, 2, 3, 5, 7, 9, 10],
    "phrygian": [0, 1, 3, 5, 7, 8, 10],
    "lydian": [0, 2, 4, 6, 7, 9, 11],
    "mixolydian": [0, 2, 4, 5, 7, 9, 10],
    "aeolian": [0, 2, 3, 5, 7, 8, 10],
    "harmonic": [0, 2, 3, 5, 7, 8, 11],
    # Phrygian dominant = maqam hijaz on the tonic: the augmented second between b2 and 3 is its colour
    "hijaz": [0, 1, 4, 5, 7, 8, 10],
    "double_harmonic": [0, 1, 4, 5, 7, 8, 11],
    "locrian": [0, 1, 3, 5, 6, 8, 10],
}

# Pentatonic subsets (scale degrees of the 7-note mode) for melodies: Akavir's yo (on dorian) and in (on phrygian)
YO = [0, 1, 3, 4, 5]
IN = [0, 1, 3, 4, 5]
MINOR_PENT = [0, 2, 3, 4, 6]


def snap_degree(d, allowed):
    """Nearest scale degree whose degree mod 7 is in `allowed`."""
    for off in (0, 1, -1, 2, -2, 3, -3):
        if (d + off) % 7 in allowed:
            return d + off
    return d

# chord-to-chord preferences (scale degree -> [(next, weight)])
TRANS_MINOR = {0: [(5, 3), (6, 3), (3, 2), (2, 2), (4, 1)], 1: [(4, 2), (6, 2), (0, 1)],
               2: [(6, 3), (5, 2), (3, 2), (0, 1)], 3: [(0, 3), (5, 2), (6, 2), (4, 1)],
               4: [(0, 3), (5, 2), (3, 1)], 5: [(6, 3), (2, 2), (3, 2), (0, 2)], 6: [(0, 3), (2, 3), (5, 2), (3, 1)]}
TRANS_MAJOR = {0: [(3, 3), (4, 2), (5, 3), (1, 1), (6, 1)], 1: [(4, 3), (6, 1), (3, 1)],
               2: [(5, 2), (3, 2)], 3: [(0, 3), (4, 2), (1, 1), (5, 1)], 4: [(0, 3), (5, 2), (3, 1)],
               5: [(3, 3), (1, 2), (4, 1), (2, 1)], 6: [(0, 2), (3, 2)]}


class Key:
    def __init__(self, tonic: int, mode: str):
        self.tonic = tonic
        self.mode = mode
        self.steps = MODES[mode]

    def m(self, d) -> int:
        o, i = divmod(int(d), 7)
        return self.tonic + 12 * o + self.steps[i]

    def dim(self, d) -> bool:
        return (self.m(d + 4) - self.m(d)) != 7

    def triad(self, d, kind="triad"):
        if kind == "sus2":
            return [d, d + 1, d + 4]
        if kind == "sus4":
            return [d, d + 3, d + 4]
        if kind == "add9":
            return [d, d + 2, d + 4, d + 8]
        if kind == "power":
            return [d, d + 4]
        if kind == "seventh":
            return [d, d + 2, d + 4, d + 6]
        return [d, d + 2, d + 4]


def weighted(rng, options):
    total = sum(w for _, w in options)
    r = rng.uniform(0, total)
    for v, w in options:
        r -= w
        if r <= 0:
            return v
    return options[-1][0]


def progression(rng, key: Key, n: int, start=0, end=None):
    trans = TRANS_MAJOR if key.mode in ("ionian", "lydian", "mixolydian") else TRANS_MINOR
    seq = [start]
    while len(seq) < n:
        opts = [(d, w) for d, w in trans[seq[-1] % 7] if not key.dim(d) and d != seq[-1]]
        if key.mode == "dorian":
            opts = [(d, w * (2 if d == 3 else 1)) for d, w in opts]
        if key.mode in ("phrygian", "hijaz", "double_harmonic"):
            opts = [(d, w * (3 if d == 1 else 1)) for d, w in opts]
        if key.mode == "lydian":
            opts = [(d, w * (3 if d == 1 else 1)) for d, w in opts]  # the bright II chord
        if not opts:
            opts = [(0, 1)]
        seq.append(weighted(rng, opts))
    if end is not None and n > 1:
        seq[-1] = end
        if seq[-2] == end:
            seq[-2] = 6 if end == 0 and not key.dim(6) else (3 if not key.dim(3) else 5)
    return seq


def voice_chord(key: Key, tones, prev, nvoices, lo, hi):
    pcs = sorted({key.m(t) % 12 for t in tones})
    cands = [m for m in range(lo, hi + 1) if m % 12 in pcs]
    if not cands:
        return []
    if not prev:
        root_pc = key.m(tones[0]) % 12
        bottom = next((m for m in cands if m % 12 == root_pc), cands[0])
        out = [bottom]
        for pc in pcs * 3:
            if len(out) >= nvoices:
                break
            nxt = [m for m in cands if m > out[-1] + 2 and m % 12 == pc]
            if nxt:
                out.append(nxt[0])
        while len(out) < nvoices:
            out.append(cands[min(len(cands) - 1, len(out) * 2)])
        return sorted(set(out))[:nvoices]
    out = []
    for p in prev[:nvoices]:
        best = min((m for m in cands if m not in out), key=lambda m: abs(m - p), default=None)
        if best is not None:
            out.append(best)
    missing = [pc for pc in pcs if pc not in {m % 12 for m in out}]
    for pc in missing[: max(0, len(out) - 1)]:
        counts = {}
        for m in out:
            counts[m % 12] = counts.get(m % 12, 0) + 1
        dup = [i for i, m in enumerate(out) if counts[m % 12] > 1]
        if not dup:
            break
        i = dup[-1]
        opts = [m for m in cands if m % 12 == pc]
        out[i] = min(opts, key=lambda m: abs(m - out[i]))
    while len(out) < nvoices and len(out) < len(cands):
        out.append(next(m for m in cands if m not in out))
    return sorted(out)


# ------------------------------------------------------------------------------------------------  melody

RHYTHMS = {
    4: [[2, 1, 1], [1.5, 0.5, 2], [1, 1, 2], [3, 1], [1, 0.5, 0.5, 2], [0.5, 0.5, 1, 2], [1.5, 0.5, 1, 1],
        [1, 1, 1, 1], [2, 0.5, 0.5, 1]],
    3: [[2, 1], [1, 1, 1], [1.5, 0.5, 1], [1, 2], [0.5, 0.5, 2], [1, 0.5, 0.5, 1]],
}
STEPS = [(-1, 28), (1, 28), (-2, 12), (2, 12), (3, 6), (-3, 5), (4, 4), (-4, 3), (0, 2)]


class Motif:
    def __init__(self, rng, meter: int, bars: int = 1):
        for _ in range(40):  # retry until the motif has a real contour (spans at least a third)
            self._make(rng, meter, bars)
            walk = np.cumsum(self.intervals)
            if walk.max() - walk.min() >= 2 and len(set(walk.tolist())) >= 3:
                break

    def _make(self, rng, meter, bars):
        self.rhythm = []
        for _ in range(bars):
            self.rhythm += list(RHYTHMS[meter][rng.integers(len(RHYTHMS[meter]))])
        self.intervals = [0]
        for _ in range(len(self.rhythm) - 1):
            step = weighted(rng, STEPS)
            if abs(self.intervals[-1]) >= 3:  # recover from a leap by step in the other direction
                step = -int(np.sign(self.intervals[-1]))
            self.intervals.append(step)
        if all(i == 0 for i in self.intervals[1:]):
            self.intervals[-1] = 1

    def variant(self, rng, kind):
        r, iv = list(self.rhythm), list(self.intervals)
        if kind == "invert":
            iv = [0] + [-i for i in iv[1:]]
        elif kind == "rhythm" and len(r) > 2:
            i = int(rng.integers(len(r) - 1))
            r[i], r[i + 1] = r[i + 1], r[i]
        elif kind == "fragment":
            h = max(2, len(r) // 2)
            r, iv = r[:h] * 2, iv[:h] + [weighted(rng, STEPS[:4])] + iv[1:h]
        elif kind == "ornament":
            r2, iv2 = [], []
            for d, i in zip(r, iv):
                if d >= 1 and rng.random() < 0.4:
                    r2 += [d - 0.5, 0.5]
                    iv2 += [i, int(rng.choice([-1, 1]))]
                else:
                    r2.append(d)
                    iv2.append(i)
            r, iv = r2, iv2
        return r, iv


def nearest_tone(deg, tones, lo, hi):
    pcs = {t % 7 for t in tones}
    best = None
    for d in range(lo, hi + 1):
        if d % 7 in pcs and (best is None or abs(d - deg) < abs(best - deg)):
            best = d
    return best if best is not None else deg


def section_melody(rng, motif: Motif, chords, bpc, meter, cur, lo, hi, density=1.0, ornament=False, long_end=True):
    """chords = [(beat, tones)]. Returns ([(beat, dur_beats, degree, vel)], last_degree)."""
    notes = []
    nch = len(chords)
    for ci, (b0, tones) in enumerate(chords):
        role = ci % 4
        if ci == nch - 1 and long_end:
            role = 3
        if role != 0 and rng.random() > density:
            continue
        shift = 0
        if role == 0:
            r, iv = motif.variant(rng, "ornament" if ornament else "none")
        elif role == 1:
            r, iv = motif.variant(rng, "rhythm" if rng.random() < 0.5 else "none")
            shift = int(rng.choice([-1, 1, 2]))
        elif role == 2:
            r, iv = motif.variant(rng, "invert" if rng.random() < 0.5 else "fragment")
        else:
            # cadence: approach a chord tone by step, then hold
            r = [1.0, 1.0, max(2.0, bpc - 3.0)] if bpc >= 6 else [1.0, max(1.0, bpc - 2.0)]
            iv = [0] + [int(rng.choice([-1, 1]))] * (len(r) - 1)
        offset = 0.0 if role in (0, 3) or rng.random() < 0.6 else 0.5 * int(rng.integers(1, 3))
        start = nearest_tone(cur + shift, tones, lo, hi)
        p = start
        beat = b0 + offset
        limit = b0 + bpc - (0.0 if role == 3 else min(1.0, bpc * 0.15))
        for j, (d, i) in enumerate(zip(r, iv)):
            if beat >= limit - 0.25:
                break
            if j > 0:
                p += i
                if p > hi:
                    p -= 2 * abs(i) if abs(i) > 0 else 1
                if p < lo:
                    p += 2 * abs(i) if abs(i) > 0 else 1
                p = min(hi, max(lo, p))
            d = min(d, limit - beat)
            strong = (beat - b0) % meter == 0 or d >= 2
            if strong:
                p = nearest_tone(p, tones, lo, hi)
            vel = 0.75 + 0.2 * (1.0 - abs((beat - b0) / max(1, bpc) - 0.45)) + rng.uniform(-0.05, 0.05)
            notes.append([beat, d, p, vel])
            beat += d
        if notes and role == 3:
            notes[-1][2] = nearest_tone(notes[-1][2], [tones[0], tones[0] + 4], lo, hi)
            notes[-1][1] = max(notes[-1][1], b0 + bpc - notes[-1][0] - 0.25)
        elif notes and notes[-1][0] + notes[-1][1] < b0 + bpc - 1.5 and rng.random() < 0.5:
            # let the phrase ring rather than leave a gap
            notes[-1][1] += min(2.0, b0 + bpc - (notes[-1][0] + notes[-1][1]) - 0.5)
        if notes:
            cur = notes[-1][2]
    return [tuple(x) for x in notes], cur


# =====================================================================================================  songs

class Song:
    def __init__(self, name, seed, tonic, mode, bpm, meter, room=0.86, damp=0.45, wet=0.38, target=-20.0, tone=9000.0):
        self.name = name
        self.rng = np.random.default_rng(seed)
        self.key = Key(tonic, mode)
        self.bpm = bpm
        self.spb = 60.0 / bpm
        self.meter = meter
        self.lead_in = 0.4
        self.room, self.damp, self.wet, self.target, self.tone = room, damp, wet, target, tone
        self.sections = []
        self.motif = Motif(self.rng, meter, bars=2 if meter == 3 else 1)
        self.motif2 = Motif(self.rng, meter, bars=1)
        self.mel_cur = 4
        self.pad_prev = None
        self.str_prev = None
        self.choir_prev = None
        self.rubato = 1.0          # 0 = metronomic (combat), 1 = breathing (exploration)
        self._tmap = None
        self.encode = (ENCODE_SR, ENCODE_Q)

    def t(self, beat):
        """Seconds at `beat`, following the tempo map: a slow humanising drift plus a ritardando into every section's
        final cadence (built in render(); before that, strict tempo)."""
        if self._tmap is None:
            return self.lead_in + beat * self.spb
        grid, secs = self._tmap
        return float(np.interp(beat, grid, secs, right=secs[-1] + (beat - grid[-1]) * self.spb))

    def _build_tempo(self, beats):
        res = 8
        grid = np.arange(int(beats * res) + 2 * res) / res
        drift = np.interp(grid, np.arange(int(beats) + 4) * 1.0,
                          box(self.rng.uniform(-1, 1, int(beats) + 4), 6) * 1.6)
        factor = 1.0 + 0.025 * self.rubato * drift
        b = 0.0
        for s in self.sections:
            end = b + len(s["prog"]) * s["bpc"]
            span = min(3.0, s["bpc"] * 0.5)
            x = np.clip((grid - (end - span)) / span, 0.0, 1.0) * (grid < end)
            factor = factor + self.rubato * (0.16 if s is self.sections[-1] else 0.07) * x ** 2
            b = end
        secs = self.lead_in + np.concatenate(([0.0], np.cumsum(factor[:-1] * self.spb / res)))
        self._tmap = (grid, secs)

    def arc(self, beat, sec, span_chords=4):
        """Phrase dynamics: notes swell towards the middle of each four-chord phrase and relax at its end."""
        pos = ((beat - sec["beat"]) / (span_chords * sec["bpc"])) % 1.0
        return 0.84 + 0.22 * math.sin(math.pi * pos)

    def section(self, name, nchords, bpc, layers, start=0, end=None, prog=None, opts=None, plain=False):
        """Adds a section of `nchords` chords, `bpc` beats each. `plain` keeps every chord a triad (no sus2/add9
        colour), for modes whose melody bends a scale degree the harmony must not sound (Hammerfell's maqam)."""
        opts = opts or {}
        prog = prog or progression(self.rng, self.key, nchords, start, end)
        kinds = []
        for d in prog:
            r = self.rng.random()
            if plain:
                kinds.append("triad")
                continue
            kinds.append("sus2" if r < 0.12 and not self.key.dim(d) else ("add9" if r < 0.25 else "triad"))
        self.sections.append(dict(name=name, prog=prog, bpc=bpc, layers=layers, kinds=kinds, opts=opts))

    def render(self):
        beats = sum(len(s["prog"]) * s["bpc"] for s in self.sections)
        self._build_tempo(beats)
        mix = Mix(self.t(beats) + 9.0)
        b = 0.0
        for si, sec in enumerate(self.sections):
            sec["beat"] = b
            sec["chords"] = [(b + i * sec["bpc"], self.key.triad(d, k)) for i, (d, k) in enumerate(zip(sec["prog"], sec["kinds"]))]
            sec["last"] = si == len(self.sections) - 1
            for layer, lvl in sec["layers"].items():
                fn = LAYERS[layer.split("#")[0]]
                fn(self, mix, sec, lvl, **sec["opts"].get(layer, {}))
            b += len(sec["prog"]) * sec["bpc"]
        out = render_mix(mix, self.room, self.damp, self.wet, tone=self.tone)
        return master(out, self.target)


def sec_len(song, sec):
    return len(sec["prog"]) * sec["bpc"] * song.spb


# ------------------------------------------------------------------------------------------------  layers

def L_drone(song, mix, sec, lvl, octave=-2, fifth=True, bright=0.35, choir=False):
    k = song.key
    dur = sec_len(song, sec)
    t = song.t(sec["beat"])
    root = k.tonic + 12 * octave
    rel = 4.0 if sec["last"] else 3.0
    sig = strings_note(root, dur, song.rng, attack=min(4.0, dur * 0.3), release=rel, bright=bright, voices=7, vib=0.03, detune=3.0)
    mix.add(sig, t, lvl * 0.42, send=0.35)
    if fifth:
        sig = strings_note(root + 7, dur, song.rng, attack=min(5.0, dur * 0.35), release=rel, bright=bright * 0.8, voices=3, vib=0.03,
                           detune=5.0)
        mix.add(sig, t, lvl * 0.3, send=0.35)
    if choir:
        sig = choir_note(root + 12, dur, song.rng, vowel="u", attack=min(5.0, dur * 0.3), release=rel, low=True)
        mix.add(sig, t, lvl * 0.25, send=0.5)


def _chord_iter(song, sec):
    for i, (b, tones) in enumerate(sec["chords"]):
        dur = sec["bpc"] * song.spb
        last = sec["last"] and i == len(sec["chords"]) - 1
        yield i, b, tones, dur, last


def L_pad(song, mix, sec, lvl, lo=50, hi=69, voices=4, cutoff=1100.0):
    for i, b, tones, dur, last in _chord_iter(song, sec):
        v = voice_chord(song.key, tones, song.pad_prev, voices, lo, hi)
        song.pad_prev = v
        for m in v:
            sig = pad_note(m, dur + 0.3, song.rng, attack=min(2.5, dur * 0.4), release=5.0 if last else 2.5, cutoff=cutoff)
            mix.add(sig, song.t(b), lvl * 0.3, send=0.45)


def balance(sig, p):
    """Shift a stereo signal's image towards `p` (-1 left .. 1 right) without collapsing it."""
    a = (max(-1.0, min(1.0, p)) + 1.0) * math.pi / 4.0
    return np.vstack((sig[0] * math.cos(a) * 1.414, sig[1] * math.sin(a) * 1.414))


def L_strings(song, mix, sec, lvl, lo=52, hi=76, voices=4, bass=True, bright=0.8, attack=1.2, octave_bass=True):
    """Layered string orchestra: each chord tone goes to the section that owns its register (violins left, violas and
    cellos right of centre), the bass line is doubled by the basses an octave below."""
    for i, b, tones, dur, last in _chord_iter(song, sec):
        v = voice_chord(song.key, tones, song.str_prev, voices, lo, hi)
        song.str_prev = v
        vel = song.arc(b, sec)
        for j, m in enumerate(v):
            name, _, _, players, det, vib, br, pan = section_for(m)
            top = j == len(v) - 1
            sig = strings_note(m, dur + 0.2, song.rng, attack=min(attack, dur * 0.4), release=4.0 if last else 1.6,
                               bright=bright * br * (1.15 if top else 1.0), voices=players, detune=det, vib=vib,
                               bow=0.35 if attack < 0.6 else 0.12, vel=vel)
            mix.add(balance(sig, pan), song.t(b) + song.rng.uniform(-0.01, 0.015), lvl * 0.2 * vel * (1.1 if top else 1.0),
                    send=0.4)
        if bass:
            root = song.key.m(tones[0]) % 12
            bm = 38 + (root - 38) % 12
            sig = strings_note(bm, dur + 0.2, song.rng, attack=min(attack, dur * 0.4), release=4.0 if last else 1.6, bright=0.4,
                               voices=4, detune=7.0, vib=0.07, bow=0.1, vel=vel)
            mix.add(balance(sig, 0.35), song.t(b), lvl * 0.28 * vel, send=0.3)
            if octave_bass and bm - 12 >= 26:
                sig = strings_note(bm - 12, dur + 0.2, song.rng, attack=min(attack * 1.2, dur * 0.45), release=4.0 if last else 1.6,
                                   bright=0.3, voices=3, detune=6.0, vib=0.04, vel=vel)
                mix.add(sig, song.t(b) + 0.01, lvl * 0.16 * vel, send=0.25)


def L_choir(song, mix, sec, lvl, lo=55, hi=74, voices=4, vowel="a", low=None, attack=1.4):
    for i, b, tones, dur, last in _chord_iter(song, sec):
        v = voice_chord(song.key, tones, song.choir_prev, voices, lo, hi)
        song.choir_prev = v
        for m in v:
            sig = choir_note(m, dur + 0.2, song.rng, vowel=vowel, attack=min(attack, dur * 0.4), release=4.0 if last else 1.8, low=low)
            mix.add(sig, song.t(b), lvl * 0.3, send=0.55)


PLUCKED = {"harp": lambda: harp, "lute": lambda: lute, "bell": lambda: bell, "oud": lambda: oud, "koto": lambda: koto,
           "pizz": lambda: pizz, "crystal": lambda: crystal}


def scale_pcs(key, scale):
    return {key.m(d) % 12 for d in scale}


def L_harp(song, mix, sec, lvl, lo=55, hi=81, step=0.5, pattern="up", instr="harp", density=1.0, scale=None):
    """Arpeggiated chords on a plucked instrument. `scale` (degrees) limits the notes, e.g. to a pentatonic."""
    play = PLUCKED[instr]()
    ring = 4.5 if instr == "crystal" else 3.0
    for i, b, tones, dur, last in _chord_iter(song, sec):
        pcs = sorted({song.key.m(t) % 12 for t in tones})
        if scale is not None:
            allowed = scale_pcs(song.key, scale)
            keep = [p for p in pcs if p in allowed]
            pcs = keep if len(keep) >= 2 else sorted(allowed)
        notes = [m for m in range(lo, hi + 1) if m % 12 in pcs]
        if not notes:
            continue
        count = int(sec["bpc"] / step)
        seq = notes[: max(3, min(len(notes), 6))]
        if pattern == "updown":
            seq = seq + seq[-2:0:-1]
        elif pattern == "wave":
            seq = seq[::2] + seq[1::2][::-1]
        offset = int(song.rng.integers(len(seq)))
        for j in range(count):
            if song.rng.random() > density:
                continue
            m = seq[(j + offset * (i % 2)) % len(seq)]
            vel = 0.8 if (j * step) % song.meter == 0 else 0.55 + 0.15 * song.rng.random()
            vel *= song.arc(b + j * step, sec)
            sig = play(m, ring, song.rng)
            mix.add(sig, song.t(b + j * step) + song.rng.uniform(-0.008, 0.008), lvl * vel * 0.55, send=0.4,
                    pan=-0.5 + (m - lo) / max(1, hi - lo))


def L_lute(song, mix, sec, lvl, patterns=None, instr="lute"):
    """Folk accompaniment: bass on the downbeat, broken chord on the off-beats."""
    meter = song.meter
    play = PLUCKED[instr]()
    pats = patterns or ([[0, 2, 1, 2, 3, 2]] if meter == 3 else [[0, 2, 1, 2, 3, 2, 1, 2]])
    pat = pats[int(song.rng.integers(len(pats)))]
    for i, b, tones, dur, last in _chord_iter(song, sec):
        k = song.key
        root = k.m(tones[0])
        bass = 43 + (root - 43) % 12
        chord = sorted({55 + (k.m(t) - 55) % 12 for t in tones})
        voices = [bass] + chord
        bars = int(sec["bpc"] // meter)
        for bar in range(bars):
            for j, idx in enumerate(pat):
                beat = b + bar * meter + j * 0.5
                m = voices[min(idx, len(voices) - 1)]
                vel = 0.85 if j == 0 else 0.5 + 0.12 * song.rng.random()
                vel *= song.arc(beat, sec)
                sig = play(m, 1.6, song.rng)
                mix.add(sig, song.t(beat) + song.rng.uniform(-0.01, 0.01), lvl * vel * 1.0, send=0.3,
                        pan=-0.25 + 0.5 * (idx / 3.0))


def lead_notes(song, sec, lo, hi, density, motif, ornament, scale=None, micro=None, octave=0, legato=False):
    """The section's melody as [(seconds, duration s, midi (may be fractional), velocity)]: phrase-shaped dynamics,
    accents on strong beats, short notes detached, long ones held, optional scale filter and microtonal degrees."""
    m = song.motif if motif == 1 else song.motif2
    notes, song.mel_cur = section_melody(song.rng, m, sec["chords"], sec["bpc"], song.meter, min(hi, max(lo, song.mel_cur)), lo, hi,
                                         density=density, ornament=ornament)
    timed = []
    for beat, d, deg, vel in notes:
        if scale is not None:
            deg = snap_degree(deg, scale)
        midi = song.key.m(deg) + 12 * octave
        if micro:
            midi += micro.get(deg % 7, 0.0)
        tt = song.t(beat) + song.rng.uniform(-0.012, 0.012)
        artic = 0.98 if legato or d >= 1.0 else (0.9 if d >= 0.75 else 0.72)
        strong = (beat - sec["beat"]) % song.meter == 0
        vel = vel * song.arc(beat, sec) * (1.08 if strong else 0.96)
        timed.append((tt, max(0.08, (song.t(beat + d) - song.t(beat)) * artic), midi, min(1.15, vel)))
    return timed


def L_lead(song, mix, sec, lvl, instr="flute", lo=0, hi=11, octave=0, density=1.0, motif=1, ornament=False, pan=0.1,
           send=0.45, double=None, scale=None, micro=None, legato=False):
    timed = lead_notes(song, sec, lo, hi, density, motif, ornament, scale, micro, octave, legato)
    for grp in lead_groups(timed):
        t0, y = render_lead(grp, instr, song.rng)
        mix.add(y, t0, lvl * 0.32, send=send, pan=pan)
        if double is not None:
            g2 = [(a, b, c + double, v) for a, b, c, v in grp]
            t1, y2 = render_lead(g2, instr, song.rng)
            mix.add(y2, t1, lvl * 0.2, send=send, pan=-pan)


def L_chant(song, mix, sec, lvl, lo=-3, hi=5, octave=-1, vowels="aoae", density=1.0):
    """Male choir singing the melody (unison + octave), syllable by syllable."""
    m = song.motif2
    notes, song.mel_cur = section_melody(song.rng, m, sec["chords"], sec["bpc"], song.meter, min(hi, max(lo, song.mel_cur)), lo, hi,
                                         density=density)
    for idx, (beat, d, deg, vel) in enumerate(notes):
        midi = song.key.m(deg) + 12 * octave
        dur = d * song.spb * 0.92
        v = vowels[idx % len(vowels)]
        sig = choir_note(midi, dur, song.rng, vowel=v, voices=5, attack=0.08, release=0.5, low=True, vib=0.1)
        sig += choir_note(midi - 12, dur, song.rng, vowel=v, voices=3, attack=0.08, release=0.5, low=True, vib=0.06) * 0.7
        mix.add(sig, song.t(beat), lvl * vel * 0.5, send=0.5)


def L_timp(song, mix, sec, lvl, every=1, roll_in=True):
    k = song.key
    for i, b, tones, dur, last in _chord_iter(song, sec):
        if i % every:
            continue
        root = k.m(tones[0])
        midi = 38 + (root - 38) % 12 if (root - 38) % 12 <= 7 else 26 + (root - 26) % 12
        mix.add(timpani(midi, 0.9 if i == 0 else 0.6, song.rng), song.t(b), lvl * 0.7, send=0.35)
    if roll_in and sec["beat"] > 0:
        # crescendo roll into the section
        root = 38 + (k.tonic - 38) % 12
        start = sec["beat"] - song.meter
        steps = int(song.meter / 0.125)
        for j in range(steps):
            vel = 0.12 + 0.6 * (j / steps) ** 2
            mix.add(timpani(root, vel, song.rng, dur=1.5), song.t(start + j * 0.125) + song.rng.uniform(-0.006, 0.006),
                    lvl * 0.5, send=0.3, pan=0.15 * (1 if j % 2 else -1))


def L_swell(song, mix, sec, lvl):
    if sec["beat"] <= 0:
        return
    d = song.meter * song.spb * 2
    mix.add(cymbal_swell(d, song.rng), song.t(sec["beat"]) - d, lvl, send=0.4)


TAIKO_PATTERNS = [
    {0: 1.0, 6: 0.55, 8: 0.9, 11: 0.5, 12: 0.8, 14: 0.6},
    {0: 1.0, 3: 0.5, 4: 0.7, 8: 1.0, 10: 0.5, 12: 0.7, 13: 0.5, 14: 0.8},
    {0: 1.0, 8: 0.8, 10: 0.45, 12: 0.6, 14: 0.6, 15: 0.5},
    {0: 1.0, 2: 0.4, 4: 0.8, 7: 0.5, 8: 0.9, 12: 0.8, 14: 0.5},
    {0: 1.0, 5: 0.6, 6: 0.6, 8: 0.9, 12: 0.7, 13: 0.4, 15: 0.6},
]
TAIKO_FILL = {0: 1.0, 2: 0.6, 4: 0.8, 6: 0.6, 8: 0.9, 10: 0.7, 11: 0.7, 12: 0.8, 13: 0.8, 14: 0.9, 15: 1.0}


def L_taiko(song, mix, sec, lvl, patterns=(0, 1), half=False, rims=False, fill_every=4):
    bars_total = int(len(sec["prog"]) * sec["bpc"] / 4)
    pats = [TAIKO_PATTERNS[p] for p in patterns]
    for bar in range(bars_total):
        is_fill = (bar + 1) % fill_every == 0 and not half
        pat = TAIKO_FILL if is_fill else pats[bar % len(pats)]
        if half:
            pat = {0: 1.0, 8: 0.7} if bar % 2 == 0 else {0: 0.8, 12: 0.5}
        for step, vel in pat.items():
            beat = sec["beat"] + bar * 4 + step * 0.25
            t = song.t(beat) + song.rng.uniform(-0.006, 0.006)
            if vel >= 0.75:
                sig = taiko(vel, song.rng, f=song.rng.uniform(52, 60))
                mix.add(sig, t, lvl * 0.9, send=0.25, pan=song.rng.uniform(-0.15, 0.15))
            else:
                sig = small_drum(vel, song.rng, f=song.rng.uniform(120, 170))
                mix.add(sig, t, lvl * 0.8, send=0.25, pan=song.rng.uniform(-0.5, 0.5))
        if rims:
            for s in range(16):
                if s % 4 == 2 or (s % 2 == 1 and song.rng.random() < 0.3):
                    mix.add(rim(0.5 + 0.3 * (s % 4 == 2), song.rng), song.t(sec["beat"] + bar * 4 + s * 0.25), lvl * 0.5,
                            send=0.2, pan=0.4)


OSTINATI = [[1, 0, 1, 1, 0, 1, 1, 0], [1, 1, 0, 1, 1, 0, 1, 0], [1, 0, 0, 1, 0, 0, 1, 0], [1, 1, 1, 0, 1, 1, 1, 0]]


def L_brass_ost(song, mix, sec, lvl, pattern=0, lo=38):
    pat = OSTINATI[pattern]
    k = song.key
    for i, b, tones, dur, last in _chord_iter(song, sec):
        root = lo + (k.m(tones[0]) - lo) % 12
        fifth = root + (k.m(tones[0] + 4) - k.m(tones[0]))
        notes = []
        for bar in range(int(sec["bpc"] // 4)):
            for j, on in enumerate(pat):
                if not on:
                    continue
                m = root if j not in (3, 6) else (fifth if j == 3 else root + 12)
                vel = 1.0 if j == 0 else 0.7
                notes.append((song.t(b + bar * 4 + j * 0.5), 0.5 * song.spb * 0.8, m, vel))
        for grp in lead_groups(notes, max_gap=0.0):
            t0, y = render_lead(grp, "brass_low", song.rng)
            mix.add(y, t0, lvl * 0.3, send=0.25, pan=-0.1)


def L_string_ost(song, mix, sec, lvl, lo=50):
    k = song.key
    for i, b, tones, dur, last in _chord_iter(song, sec):
        root = lo + (k.m(tones[0]) - lo) % 12
        third = k.m(tones[0] + 2) - k.m(tones[0])
        fig = [0, 12, 7, 12, 0, 12, third + 12, 12]
        for j in range(int(sec["bpc"] / 0.25)):
            m = root + fig[j % len(fig)]
            vel = 1.0 if j % 4 == 0 else 0.65
            sig = strings_note(m, 0.12, song.rng, attack=0.008, release=0.09, bright=0.9, voices=2, short=True)
            mix.add(sig, song.t(b + j * 0.25), lvl * vel * 0.25, send=0.25)


def L_horns(song, mix, sec, lvl, lo=50, hi=67):
    """Sustained brass chords."""
    prev = None
    for i, b, tones, dur, last in _chord_iter(song, sec):
        v = voice_chord(song.key, tones, prev, 3, lo, hi)
        prev = v
        for j, m in enumerate(v):
            t0, y = render_lead([(song.t(b), dur * 0.95, m, 0.7)], "horn", song.rng)
            mix.add(y, t0, lvl * 0.18, send=0.45, pan=-0.4 + 0.4 * j)


def L_metal(song, mix, sec, lvl, rate=0.15, bright=0.8):
    dur = sec_len(song, sec)
    t = 0.0
    while True:
        t += song.rng.exponential(1.0 / rate)
        if t > dur:
            break
        sig = metal_hit(song.rng, dur=song.rng.uniform(4, 8), bright=bright)
        if song.rng.random() < 0.35:
            sig = sig[::-1].copy()
            mix.add(sig, song.t(sec["beat"]) + t - len(sig) / SR, lvl * 0.6, send=0.6, pan=song.rng.uniform(-0.8, 0.8))
        else:
            mix.add(sig, song.t(sec["beat"]) + t, lvl * 0.5, send=0.6, pan=song.rng.uniform(-0.8, 0.8))


def L_wind(song, mix, sec, lvl):
    dur = sec_len(song, sec) + 3.0
    sig = wind(dur, song.rng)
    n = sig.shape[1]
    fade = ns(2.5)
    sig[:, :fade] *= np.linspace(0, 1, fade)
    sig[:, -fade:] *= np.linspace(1, 0, fade)
    mix.add(sig, song.t(sec["beat"]), lvl, send=0.3)


def L_bells(song, mix, sec, lvl, rate=0.25, lo=72, hi=91, voice="bell", scale=None):
    k = song.key
    pent = [0, 1, 2, 4, 5] if k.mode in ("ionian", "lydian", "mixolydian") else [0, 2, 3, 4, 6]
    if scale is not None:
        pent = list(scale)
    play = crystal if voice == "crystal" else bell
    for i, b, tones, dur, last in _chord_iter(song, sec):
        t = 0.0
        while True:
            t += song.rng.exponential(1.0 / rate)
            if t > dur:
                break
            pool = pent + ([d % 7 for d in tones] if scale is None else [])
            deg = int(song.rng.choice(pool)) + 7 * int(song.rng.integers(1, 3))
            m = k.m(deg)
            while m > hi:
                m -= 12
            while m < lo:
                m += 12
            mix.add(play(m, 5.0 if voice == "crystal" else 4.0, song.rng), song.t(b) + t, lvl * 0.35, send=0.7,
                    pan=song.rng.uniform(-0.7, 0.7))


def L_frame(song, mix, sec, lvl):
    meter = song.meter
    pats = {3: [(0, 1.0, False), (1.5, 0.4, True), (2, 0.6, False)],
            4: [(0, 1.0, False), (1.5, 0.4, True), (2, 0.7, False), (3, 0.5, True), (3.5, 0.35, True)]}[meter]
    bars = int(len(sec["prog"]) * sec["bpc"] / meter)
    for bar in range(bars):
        for off, vel, slap in pats:
            t = song.t(sec["beat"] + bar * meter + off) + song.rng.uniform(-0.01, 0.01)
            mix.add(frame_drum(vel * song.rng.uniform(0.85, 1.0), song.rng, slap=slap), t, lvl * 0.6, send=0.25, pan=0.2)


def L_boom(song, mix, sec, lvl, every=2):
    for i, b, tones, dur, last in _chord_iter(song, sec):
        if i % every == 0:
            mix.add(boom(song.rng), song.t(b), lvl * 0.7, send=0.3)


def L_dist_drone(song, mix, sec, lvl, cluster=(0, 1, 6), octave=-2, ring=31.0, drive=3.0):
    """Oblivion: detuned cluster drone, ring-modulated and overdriven, slowly breathing."""
    k = song.key
    dur = sec_len(song, sec)
    root = k.tonic + 12 * octave
    acc = None
    for c in cluster:
        sig = strings_note(root + c, dur, song.rng, attack=min(4.0, dur * 0.3), release=3.0, bright=0.6, voices=4, detune=22)
        acc = sig if acc is None else acc + sig
    n = acc.shape[1]
    t = np.arange(n) / SR
    rm = np.sin(2 * np.pi * ring * t + 2.0 * smooth_noise(n, 0.2, song.rng))
    acc = acc * (0.6 + 0.4 * rm)[None, :]
    acc = softclip(acc * drive, 1.0)
    breathe = 0.6 + 0.4 * smooth_noise(n, 0.12, song.rng)
    acc = ffilt(acc * breathe[None, :], chain(lp(1800, 2), hp(40, 1)))
    mix.add(acc, song.t(sec["beat"]), lvl * 0.35, send=0.45)


def L_cluster_choir(song, mix, sec, lvl, lo=55):
    """Detuned, bending choir clusters."""
    dur = sec_len(song, sec)
    k = song.key
    base = lo + (k.tonic - lo) % 12
    for c in (0, 1, 6, 11):
        sig = choir_note(base + c, dur, song.rng, vowel="u" if c % 2 else "o", attack=dur * 0.4, release=3.0, vib=0.35)
        n = sig.shape[1]
        sig = sig * (0.5 + 0.5 * smooth_noise(n, 0.2, song.rng))[None, :]
        mix.add(sig, song.t(sec["beat"]), lvl * 0.28, send=0.7)


def L_dist_hits(song, mix, sec, lvl, rate=0.3):
    dur = sec_len(song, sec)
    t = 0.0
    while True:
        t += song.rng.exponential(1.0 / rate)
        if t > dur:
            break
        sig = softclip(taiko(1.0, song.rng, f=song.rng.uniform(40, 50), dur=2.5) * 2.5, 2.0)
        sig = ffilt(sig, lp(3000, 1))
        mix.add(sig, song.t(sec["beat"]) + t, lvl * 0.5, send=0.45, pan=song.rng.uniform(-0.3, 0.3))


def L_stabs(song, mix, sec, lvl, every=1):
    """Dissonant overdriven brass stabs on chord changes."""
    k = song.key
    for i, b, tones, dur, last in _chord_iter(song, sec):
        if i % every:
            continue
        root = 46 + (k.m(tones[0]) - 46) % 12
        acc = None
        for c in (0, 1, 6):
            t0, y = render_lead([(0.0, 0.9, root + c, 1.0)], "brass_low", song.rng)
            acc = y if acc is None else acc + y
        acc = softclip(acc * 2.5, 1.5)
        mix.add(acc, song.t(b), lvl * 0.3, send=0.5, pan=song.rng.uniform(-0.3, 0.3))


def L_riser(song, mix, sec, lvl):
    if sec["beat"] <= 0:
        return
    d = sec["bpc"] * song.spb
    n = ns(d)
    t = np.arange(n) / SR
    y = ffilt(song.rng.standard_normal(n), bp(400, 3000, 2)) * (t / d) ** 2
    f = 80 * 2 ** (3 * t / d)
    y = y + 0.4 * softclip(saw(f) * (t / d) ** 2, 2.0)
    mix.add(ffilt(y, lp(4000, 1)), song.t(sec["beat"]) - d, lvl * 0.35, send=0.5)


def L_pluck_lead(song, mix, sec, lvl, instr="oud", lo=0, hi=11, octave=0, density=1.0, motif=1, ornament=True, pan=0.15,
                 send=0.35, scale=None, micro=None, tremolo=True, grace=0.3):
    """Melody on a plucked instrument. Long notes are sustained by tremolo (oud, mandolin style) or left to ring
    (koto); some notes get a quick grace note from the scale step below."""
    play = PLUCKED[instr]()
    for tt, d, midi, vel in lead_notes(song, sec, lo, hi, density, motif, ornament, scale, micro, octave):
        if song.rng.random() < grace and d > 0.25:
            mix.add(play(midi - (1 if instr == "koto" else 2), 0.4, song.rng), tt - 0.06, lvl * vel * 0.25, send=send, pan=pan)
        if tremolo and d > 0.7:
            k = int(d / 0.085)
            for j in range(k):
                v = vel * (0.75 + 0.25 * (j % 2 == 0)) * (1.0 - 0.3 * j / max(1, k))
                mix.add(play(midi, 0.5, song.rng), tt + j * 0.085 + song.rng.uniform(-0.004, 0.004), lvl * v * 0.42, send=send,
                        pan=pan)
        else:
            mix.add(play(midi, max(1.2, d + 1.0), song.rng), tt, lvl * vel * 0.6, send=send, pan=pan)


# Darbuka rhythms in eighths: maqsum, saidi (4/4); and a 6/8 pattern for meter 3
DARBUKA = {4: [[("D", 0, 1.0), ("T", 1, 0.6), ("T", 3, 0.7), ("D", 4, 0.9), ("T", 6, 0.7), ("K", 7, 0.4)],
               [("D", 0, 1.0), ("T", 1, 0.6), ("D", 3, 0.8), ("D", 4, 0.9), ("T", 6, 0.7), ("K", 5, 0.35), ("K", 7, 0.35)]],
           3: [[("D", 0, 1.0), ("K", 1, 0.4), ("T", 2, 0.6), ("D", 3, 0.8), ("T", 4, 0.6), ("K", 5, 0.4)]]}


def L_darbuka(song, mix, sec, lvl, rolls=True):
    meter = song.meter
    pats = DARBUKA[meter]
    bars = int(len(sec["prog"]) * sec["bpc"] / meter)
    for bar in range(bars):
        pat = pats[bar % len(pats)]
        for kind, eighth, vel in pat:
            beat = sec["beat"] + bar * meter + eighth * 0.5
            mix.add(darbuka(kind, vel * song.rng.uniform(0.85, 1.0), song.rng), song.t(beat) + song.rng.uniform(-0.006, 0.006),
                    lvl * 0.7, send=0.22, pan=0.25 if kind == "T" else (-0.1 if kind == "D" else 0.4))
        if rolls and bar % 4 == 3:
            for j in range(4):  # a quick tek roll into the next phrase
                beat = sec["beat"] + bar * meter + meter - 0.5 + j * 0.125
                mix.add(darbuka("T", 0.35 + 0.1 * j, song.rng), song.t(beat), lvl * 0.6, send=0.22, pan=0.3)


def L_water(song, mix, sec, lvl, sparse=False):
    """Water drums: a slow, heavy heartbeat with answering lighter strokes."""
    meter = song.meter
    bars = int(len(sec["prog"]) * sec["bpc"] / meter)
    for bar in range(bars):
        hits = [(0.0, 1.0, 62.0)] if sparse else [(0.0, 1.0, 62.0), (meter - 1.5, 0.55, 88.0), (meter - 0.5, 0.7, 75.0)]
        if not sparse and song.rng.random() < 0.3:
            hits.append((1.0, 0.4, 110.0))
        for off, vel, f in hits:
            mix.add(water_drum(vel, song.rng, f=f * song.rng.uniform(0.97, 1.03)),
                    song.t(sec["beat"] + bar * meter + off) + song.rng.uniform(-0.01, 0.01), lvl * 0.6, send=0.35,
                    pan=song.rng.uniform(-0.3, 0.3))


def L_bodhran(song, mix, sec, lvl):
    """Celtic frame drum: lower, rolling triplet figures in 6/8 (meter 3) or a reel pulse in 4."""
    meter = song.meter
    pats = {3: [(0, 1.0), (0.5, 0.45), (1.0, 0.55), (1.5, 0.75), (2.0, 0.45), (2.5, 0.55)],
            4: [(0, 1.0), (0.5, 0.4), (1.0, 0.6), (1.5, 0.4), (2.0, 0.85), (2.5, 0.4), (3.0, 0.6), (3.5, 0.5)]}[meter]
    bars = int(len(sec["prog"]) * sec["bpc"] / meter)
    for bar in range(bars):
        for off, vel in pats:
            t = song.t(sec["beat"] + bar * meter + off) + song.rng.uniform(-0.008, 0.008)
            mix.add(frame_drum(vel * song.rng.uniform(0.85, 1.0), song.rng, f=78.0, slap=vel < 0.5), t, lvl * 0.55, send=0.2,
                    pan=-0.15)


def L_throat(song, mix, sec, lvl, octave=-2):
    dur = sec_len(song, sec)
    sig = throat_note(song.key.tonic + 12 * octave, dur, song.rng, attack=min(4.0, dur * 0.3), release=3.0)
    mix.add(sig, song.t(sec["beat"]), lvl * 0.5, send=0.45)


def L_clock(song, mix, sec, lvl, density=0.8):
    """Dwemer machinery: interlocking clockwork ticks with accents and the odd piston thump."""
    bars = int(len(sec["prog"]) * sec["bpc"] / song.meter)
    fa, fb = song.rng.uniform(1700, 2100), song.rng.uniform(2500, 3100)
    for bar in range(bars):
        for s in range(song.meter * 4):
            if song.rng.random() > density and s % 4:
                continue
            vel = 1.0 if s % 4 == 0 else (0.6 if s % 2 == 0 else 0.35)
            beat = sec["beat"] + bar * song.meter + s * 0.25
            mix.add(clock_tick(vel, song.rng, fa if s % 3 else fb), song.t(beat), lvl * 0.45, send=0.4,
                    pan=-0.4 if s % 2 else 0.4)
        if bar % 2 == 1:
            mix.add(ffilt(boom(song.rng, f=46.0, dur=1.2), lp(600, 1)), song.t(sec["beat"] + bar * song.meter), lvl * 0.35, send=0.3)


def L_steam(song, mix, sec, lvl, rate=0.15):
    dur = sec_len(song, sec)
    t = 0.0
    while True:
        t += song.rng.exponential(1.0 / rate)
        if t > dur:
            break
        mix.add(steam(song.rng.uniform(0.8, 2.5), song.rng), song.t(sec["beat"]) + t, lvl * 0.5, send=0.5,
                pan=song.rng.uniform(-0.8, 0.8))


LAYERS = {name[2:]: fn for name, fn in globals().items() if name.startswith("L_")}


# =====================================================================================================  tracks

def explore_day_1():
    s = Song("explore_day_1", 1101, 62, "dorian", 66, 4, room=0.88, wet=0.4)
    s.section("intro", 2, 8, {"drone": 0.8, "pad": 0.6}, start=0)
    s.section("a", 4, 8, {"drone": 0.6, "pad": 0.7, "harp": 0.6, "lead": 0.9},
              opts={"lead": dict(instr="flute", lo=0, hi=10), "harp": dict(pattern="up")})
    s.section("b", 4, 8, {"strings": 0.8, "choir": 0.6, "lead": 0.9, "timp": 0.6},
              opts={"lead": dict(instr="horn", lo=-5, hi=5, octave=0), "choir": dict(vowel="a")})
    s.section("a2", 4, 8, {"pad": 0.7, "harp": 0.6, "lead": 0.9, "choir": 0.35},
              opts={"lead": dict(instr="flute", lo=2, hi=12, ornament=True), "choir": dict(vowel="o"), "harp": dict(pattern="updown")})
    s.section("outro", 2, 8, {"drone": 0.8, "pad": 0.6, "lead": 0.5}, end=0,
              opts={"lead": dict(instr="flute", lo=0, hi=7, density=0.6)})
    return s


def explore_day_2():
    s = Song("explore_day_2", 1202, 57, "aeolian", 72, 3, room=0.9, wet=0.42)
    s.section("intro", 2, 9, {"drone": 0.8, "choir": 0.4}, opts={"choir": dict(vowel="u")})
    s.section("a", 4, 9, {"drone": 0.5, "strings": 0.6, "harp": 0.6, "lead": 0.9},
              opts={"lead": dict(instr="horn", lo=0, hi=8, octave=-1), "harp": dict(pattern="wave", step=0.5)})
    s.section("b", 4, 9, {"strings": 0.8, "choir": 0.6, "lead": 0.9, "timp": 0.5},
              opts={"lead": dict(instr="flute", lo=2, hi=11, motif=2), "choir": dict(vowel="a")})
    s.section("c", 4, 9, {"pad": 0.6, "harp": 0.5, "bells": 0.4, "lead": 0.8},
              opts={"lead": dict(instr="flute", lo=0, hi=9, density=0.7)})
    s.section("outro", 2, 9, {"drone": 0.8, "choir": 0.4, "pad": 0.5}, end=0, opts={"choir": dict(vowel="u")})
    return s


def explore_day_3():
    s = Song("explore_day_3", 1303, 55, "mixolydian", 63, 4, room=0.9, wet=0.42)
    s.section("intro", 2, 8, {"drone": 0.7, "pad": 0.6, "bells": 0.3})
    s.section("a", 4, 8, {"pad": 0.6, "harp": 0.6, "lead": 0.9, "drone": 0.4},
              opts={"lead": dict(instr="fiddle", lo=0, hi=9), "harp": dict(pattern="updown", step=0.5)})
    s.section("b", 4, 8, {"strings": 0.8, "choir": 0.7, "lead": 0.85, "timp": 0.5},
              opts={"lead": dict(instr="horn", lo=-4, hi=5), "choir": dict(vowel="a")})
    s.section("a2", 4, 8, {"pad": 0.6, "harp": 0.6, "lead": 0.85, "choir": 0.3},
              opts={"lead": dict(instr="flute", lo=3, hi=12, motif=1, ornament=True), "choir": dict(vowel="o")})
    s.section("outro", 2, 8, {"drone": 0.8, "pad": 0.6}, end=0)
    return s


def explore_night_1():
    s = Song("explore_night_1", 2101, 62, "aeolian", 54, 3, room=0.92, damp=0.55, wet=0.48, target=-22.0, tone=7000)
    s.section("intro", 2, 9, {"drone": 0.8, "wind": 0.35})
    s.section("a", 4, 9, {"drone": 0.5, "pad": 0.6, "bells": 0.4, "lead": 0.75},
              opts={"lead": dict(instr="flute", lo=-3, hi=6, density=0.7), "pad": dict(cutoff=800)})
    s.section("b", 4, 9, {"pad": 0.5, "choir": 0.55, "harp": 0.35, "lead": 0.7},
              opts={"choir": dict(vowel="u"), "harp": dict(step=1.0, density=0.6), "lead": dict(instr="horn", lo=-5, hi=3, density=0.6)})
    s.section("outro", 3, 9, {"drone": 0.7, "wind": 0.3, "bells": 0.3}, end=0)
    return s


def explore_night_2():
    s = Song("explore_night_2", 2202, 64, "dorian", 52, 4, room=0.93, damp=0.55, wet=0.5, target=-22.0, tone=7000)
    s.section("intro", 2, 8, {"drone": 0.8, "choir": 0.35}, opts={"choir": dict(vowel="u")})
    s.section("a", 4, 8, {"pad": 0.6, "harp": 0.4, "lead": 0.7},
              opts={"harp": dict(step=1.0, density=0.7, lo=52, hi=76), "lead": dict(instr="whistle", lo=0, hi=7, density=0.7)})
    s.section("b", 4, 8, {"strings": 0.55, "choir": 0.5, "bells": 0.35, "lead": 0.65},
              opts={"strings": dict(bright=0.4), "choir": dict(vowel="o"), "lead": dict(instr="flute", lo=-2, hi=7, density=0.6, motif=2)})
    s.section("outro", 3, 8, {"drone": 0.7, "pad": 0.5, "wind": 0.25}, end=0)
    return s


def town_1():
    s = Song("town_1", 3101, 62, "dorian", 96, 3, room=0.6, damp=0.5, wet=0.28, target=-19.5)
    s.section("intro", 2, 6, {"lute": 0.8})
    s.section("a", 8, 6, {"lute": 0.8, "pad": 0.4, "lead": 0.9, "frame": 0.5},
              opts={"lead": dict(instr="flute", lo=0, hi=10, ornament=True)})
    s.section("b", 8, 6, {"lute": 0.8, "pad": 0.4, "lead": 0.85, "frame": 0.6},
              opts={"lead": dict(instr="fiddle", lo=0, hi=9, motif=2, ornament=True)})
    s.section("a2", 8, 6, {"lute": 0.8, "strings": 0.35, "lead": 0.9, "frame": 0.5},
              opts={"lead": dict(instr="flute", lo=2, hi=11, ornament=True, double=-3), "strings": dict(bright=0.5)})
    s.section("outro", 2, 6, {"lute": 0.7, "pad": 0.4}, end=0)
    return s


def town_2():
    s = Song("town_2", 3202, 57, "mixolydian", 88, 4, room=0.6, damp=0.5, wet=0.28, target=-19.5)
    s.section("intro", 2, 8, {"lute": 0.8, "pad": 0.3})
    s.section("a", 6, 8, {"lute": 0.8, "pad": 0.35, "lead": 0.9, "frame": 0.45},
              opts={"lead": dict(instr="fiddle", lo=0, hi=9, ornament=True)})
    s.section("b", 6, 8, {"lute": 0.7, "harp": 0.4, "lead": 0.85, "frame": 0.5, "strings": 0.3},
              opts={"lead": dict(instr="flute", lo=2, hi=11, motif=2, ornament=True), "strings": dict(bright=0.5)})
    s.section("a2", 6, 8, {"lute": 0.8, "pad": 0.35, "lead": 0.9, "frame": 0.5},
              opts={"lead": dict(instr="fiddle", lo=0, hi=9, ornament=True)})
    s.section("outro", 2, 8, {"lute": 0.7, "pad": 0.4}, end=0)
    return s


def dungeon_1():
    s = Song("dungeon_1", 4101, 62, "phrygian", 50, 4, room=0.95, damp=0.65, wet=0.5, target=-23.0, tone=5000)
    s.section("intro", 1, 16, {"drone": 0.9, "wind": 0.3}, opts={"drone": dict(choir=True, bright=0.25)})
    s.section("a", 3, 12, {"drone": 0.7, "metal": 0.5, "pad": 0.4}, opts={"pad": dict(lo=45, hi=60, cutoff=600), "metal": dict(rate=0.12)})
    s.section("b", 3, 12, {"drone": 0.6, "choir": 0.4, "metal": 0.45, "lead": 0.55, "boom": 0.5},
              opts={"choir": dict(vowel="u", lo=48, hi=62, low=True), "lead": dict(instr="horn", lo=-7, hi=0, density=0.5),
                    "boom": dict(every=3)})
    s.section("c", 3, 12, {"drone": 0.8, "metal": 0.5, "wind": 0.3}, end=0, opts={"drone": dict(choir=True, bright=0.2)})
    return s


def dungeon_2():
    s = Song("dungeon_2", 4202, 57, "harmonic", 46, 3, room=0.95, damp=0.6, wet=0.52, target=-23.0, tone=5500)
    s.section("intro", 1, 15, {"drone": 0.9}, opts={"drone": dict(bright=0.2, fifth=False)})
    s.section("a", 3, 12, {"drone": 0.6, "strings": 0.35, "metal": 0.45},
              opts={"strings": dict(lo=45, hi=62, bright=0.3, attack=3.0), "metal": dict(rate=0.1, bright=0.5)})
    s.section("b", 3, 12, {"drone": 0.6, "choir": 0.35, "bells": 0.25, "lead": 0.5, "boom": 0.45},
              opts={"choir": dict(vowel="o", lo=50, hi=64, low=True), "lead": dict(instr="whistle", lo=-2, hi=5, density=0.4),
                    "bells": dict(rate=0.1, lo=60, hi=79)})
    s.section("c", 3, 12, {"drone": 0.8, "metal": 0.4, "wind": 0.35}, end=0, opts={"drone": dict(choir=True)})
    return s


def combat_1():
    s = Song("combat_1", 5101, 50, "aeolian", 112, 4, room=0.75, damp=0.5, wet=0.25, target=-17.0)
    s.rubato = 0.25
    loop = progression(s.rng, s.key, 4, 0)
    s.section("intro", 4, 4, {"taiko": 0.8, "brass_ost": 0.7, "swell": 0.0}, prog=loop, opts={"taiko": dict(half=True)})
    s.section("a", 8, 4, {"taiko": 0.9, "brass_ost": 0.8, "string_ost": 0.7, "timp": 0.6, "swell": 0.8}, prog=loop * 2,
              opts={"timp": dict(every=4)})
    s.section("b", 8, 4, {"taiko": 0.9, "brass_ost": 0.7, "strings": 0.6, "lead": 1.0, "swell": 0.8}, prog=loop * 2,
              opts={"lead": dict(instr="horn", lo=0, hi=9, octave=0, motif=2), "taiko": dict(patterns=(2, 3))})
    s.section("c", 8, 4, {"taiko": 0.7, "choir": 0.7, "string_ost": 0.7, "boom": 0.7, "swell": 0.8},
              opts={"taiko": dict(half=True), "choir": dict(vowel="a", attack=0.6)})
    s.section("d", 8, 4, {"taiko": 0.9, "brass_ost": 0.7, "horns": 0.6, "string_ost": 0.6, "swell": 0.8},
              opts={"brass_ost": dict(pattern=3), "taiko": dict(patterns=(4, 2))})
    s.section("a2", 8, 4, {"taiko": 1.0, "brass_ost": 0.8, "string_ost": 0.6, "lead": 1.0, "choir": 0.5, "timp": 0.6, "swell": 0.8},
              prog=loop * 2, opts={"lead": dict(instr="horn", lo=2, hi=11), "taiko": dict(rims=True), "timp": dict(every=4)})
    s.section("outro", 2, 4, {"timp": 0.9, "strings": 0.7, "boom": 0.8, "choir": 0.5}, prog=[5, 0],
              opts={"boom": dict(every=1)})
    return s


def combat_2():
    s = Song("combat_2", 5202, 52, "phrygian", 104, 4, room=0.78, damp=0.5, wet=0.25, target=-17.0)
    s.rubato = 0.25
    loop = progression(s.rng, s.key, 4, 0)
    s.section("intro", 4, 4, {"string_ost": 0.7, "taiko": 0.6, "swell": 0.0}, prog=loop, opts={"taiko": dict(half=True)})
    s.section("a", 8, 4, {"taiko": 0.9, "string_ost": 0.7, "brass_ost": 0.7, "swell": 0.8, "boom": 0.5}, prog=loop * 2,
              opts={"brass_ost": dict(pattern=1), "taiko": dict(patterns=(4, 0)), "boom": dict(every=4)})
    s.section("b", 8, 4, {"taiko": 0.9, "string_ost": 0.6, "horns": 0.6, "lead": 1.0, "timp": 0.6, "swell": 0.8}, prog=loop * 2,
              opts={"lead": dict(instr="horn", lo=0, hi=9), "timp": dict(every=2)})
    s.section("c", 6, 4, {"taiko": 0.7, "choir": 0.6, "brass_ost": 0.6, "swell": 0.7},
              opts={"taiko": dict(half=True), "brass_ost": dict(pattern=2), "choir": dict(vowel="o", attack=0.5)})
    s.section("d", 8, 4, {"taiko": 0.9, "string_ost": 0.7, "lead": 0.9, "timp": 0.6, "swell": 0.8}, prog=loop * 2,
              opts={"lead": dict(instr="horn", lo=-2, hi=7, motif=1), "taiko": dict(patterns=(0, 3)), "timp": dict(every=4)})
    s.section("a2", 8, 4, {"taiko": 1.0, "string_ost": 0.6, "brass_ost": 0.8, "lead": 1.0, "choir": 0.5, "swell": 0.8},
              prog=loop * 2, opts={"lead": dict(instr="horn", lo=2, hi=11, motif=2), "brass_ost": dict(pattern=1),
                                   "taiko": dict(patterns=(1, 4), rims=True)})
    s.section("outro", 2, 4, {"timp": 0.9, "horns": 0.7, "boom": 0.8}, prog=[5, 0], opts={"boom": dict(every=1)})
    return s


def boss_dragon():
    s = Song("boss_dragon", 6101, 50, "aeolian", 96, 4, room=0.85, damp=0.45, wet=0.3, target=-16.5)
    s.rubato = 0.25
    loop = progression(s.rng, s.key, 4, 0)
    s.section("intro", 4, 4, {"boom": 0.8, "drone": 0.7, "taiko": 0.6, "swell": 0.0}, prog=loop,
              opts={"taiko": dict(half=True), "boom": dict(every=2), "drone": dict(choir=True)})
    s.section("a", 8, 4, {"taiko": 0.9, "brass_ost": 0.7, "string_ost": 0.6, "chant": 0.9, "swell": 0.8}, prog=loop * 2,
              opts={"taiko": dict(patterns=(3, 0)), "chant": dict(lo=-3, hi=5, octave=0)})
    s.section("b", 8, 4, {"taiko": 1.0, "horns": 0.7, "choir": 0.6, "lead": 1.0, "timp": 0.7, "boom": 0.6, "swell": 0.8},
              prog=loop * 2, opts={"lead": dict(instr="horn", lo=2, hi=11), "choir": dict(vowel="a", attack=0.5), "boom": dict(every=4)})
    s.section("c", 8, 4, {"taiko": 0.7, "chant": 0.9, "strings": 0.6, "swell": 0.8},
              opts={"taiko": dict(half=True), "chant": dict(lo=0, hi=7, octave=0, vowels="oaoe")})
    s.section("a2", 8, 4, {"taiko": 1.0, "brass_ost": 0.8, "string_ost": 0.6, "chant": 0.9, "horns": 0.6, "timp": 0.6, "swell": 0.8},
              prog=loop * 2, opts={"taiko": dict(rims=True), "timp": dict(every=2)})
    s.section("outro", 4, 4, {"choir": 0.8, "horns": 0.7, "boom": 0.9, "timp": 0.8}, end=0,
              opts={"boom": dict(every=1), "choir": dict(vowel="o", lo=45, hi=64, low=True)})
    return s


def oblivion_1():
    s = Song("oblivion_1", 7101, 50, "harmonic", 60, 4, room=0.93, damp=0.35, wet=0.45, target=-20.0, tone=7000)
    s.section("intro", 1, 12, {"dist_drone": 0.7, "metal": 0.5}, opts={"metal": dict(rate=0.3, bright=1.0)})
    s.section("a", 3, 8, {"dist_drone": 0.8, "dist_hits": 0.7, "cluster_choir": 0.5, "riser": 0.6},
              opts={"dist_hits": dict(rate=0.35)})
    s.section("b", 4, 8, {"dist_drone": 0.6, "stabs": 0.8, "dist_hits": 0.7, "metal": 0.5, "lead": 0.6, "riser": 0.6},
              opts={"lead": dict(instr="whistle", lo=-2, hi=8, density=0.6), "dist_drone": dict(cluster=(0, 6), ring=47.0)})
    s.section("c", 3, 8, {"cluster_choir": 0.7, "dist_drone": 0.5, "metal": 0.6, "wind": 0.4},
              opts={"metal": dict(rate=0.4, bright=1.0)})
    s.section("d", 4, 8, {"dist_drone": 0.8, "stabs": 0.9, "dist_hits": 0.9, "taiko": 0.6, "riser": 0.6},
              opts={"taiko": dict(half=True), "dist_drone": dict(drive=5.0)})
    s.section("outro", 2, 8, {"dist_drone": 0.6, "cluster_choir": 0.6, "metal": 0.4}, end=0)
    return s


def sovngarde_1():
    s = Song("sovngarde_1", 8101, 62, "lydian", 60, 4, room=0.94, damp=0.3, wet=0.5, target=-19.5, tone=11000)
    s.section("intro", 2, 8, {"choir": 0.7, "drone": 0.5, "bells": 0.3}, opts={"choir": dict(vowel="a", attack=3.0)})
    s.section("a", 4, 8, {"choir": 0.6, "strings": 0.6, "harp": 0.6, "lead": 0.9},
              opts={"lead": dict(instr="horn", lo=-2, hi=7), "harp": dict(pattern="updown", lo=57, hi=86)})
    s.section("b", 4, 8, {"choir": 0.8, "strings": 0.7, "horns": 0.6, "lead": 0.9, "timp": 0.6, "bells": 0.3},
              opts={"lead": dict(instr="flute", lo=4, hi=13, motif=2), "choir": dict(lo=57, hi=79, voices=5)})
    s.section("a2", 4, 8, {"choir": 0.8, "strings": 0.6, "harp": 0.6, "chant": 0.6, "timp": 0.5},
              opts={"chant": dict(lo=-2, hi=5, octave=0, vowels="a"), "harp": dict(pattern="wave", lo=57, hi=86)})
    s.section("outro", 2, 8, {"choir": 0.8, "drone": 0.6, "bells": 0.3}, end=0, opts={"choir": dict(vowel="a", attack=2.0)})
    return s


# ------------------------------------------------------------------------------------------------  provinces
# Two exploration pieces per province family: a day piece and a sparser night piece.

def cyrodiil_day():
    """Heroic and pastoral: Lydian strings, harp, choir pads, horn and flute."""
    s = Song("cyrodiil_day", 11101, 65, "lydian", 72, 4, room=0.88, wet=0.4)
    s.section("intro", 2, 8, {"strings": 0.6, "harp": 0.5, "choir": 0.3},
              opts={"strings": dict(attack=2.0, bright=0.6), "choir": dict(vowel="a", attack=2.5)})
    s.section("a", 4, 8, {"strings": 0.7, "harp": 0.6, "lead": 0.9, "timp": 0.4},
              opts={"harp": dict(pattern="updown"), "lead": dict(instr="horn", lo=-2, hi=7), "timp": dict(every=2)})
    s.section("b", 6, 8, {"strings": 0.8, "choir": 0.6, "lead": 0.9, "harp": 0.4, "horns": 0.45},
              opts={"lead": dict(instr="flute", lo=2, hi=11, motif=2), "choir": dict(vowel="a")})
    s.section("a2", 4, 8, {"strings": 0.85, "choir": 0.5, "harp": 0.6, "lead": 1.0, "timp": 0.6, "swell": 0.5},
              opts={"lead": dict(instr="horn", lo=0, hi=9, ornament=True), "choir": dict(vowel="o"), "harp": dict(pattern="wave")})
    s.section("outro", 2, 8, {"strings": 0.7, "choir": 0.6, "harp": 0.4}, end=0,
              opts={"choir": dict(vowel="a", attack=2.0), "strings": dict(attack=2.0)})
    return s


def cyrodiil_night():
    s = Song("cyrodiil_night", 11202, 62, "lydian", 56, 3, room=0.92, damp=0.5, wet=0.46, target=-22.0, tone=7500)
    s.section("intro", 2, 9, {"pad": 0.6, "harp": 0.4}, opts={"harp": dict(step=1.0, density=0.7)})
    s.section("a", 4, 9, {"strings": 0.5, "harp": 0.5, "lead": 0.75},
              opts={"strings": dict(bright=0.4, attack=2.0), "harp": dict(step=1.0), "lead": dict(instr="flute", lo=0, hi=9, density=0.7)})
    s.section("b", 4, 9, {"choir": 0.5, "strings": 0.4, "bells": 0.25, "lead": 0.6},
              opts={"choir": dict(vowel="o"), "strings": dict(bright=0.35), "lead": dict(instr="horn", lo=-5, hi=3, density=0.6, motif=2)})
    s.section("outro", 3, 9, {"pad": 0.6, "harp": 0.4, "choir": 0.3}, end=0,
              opts={"choir": dict(vowel="u"), "harp": dict(step=1.5, density=0.6)})
    return s


def morrowind_day():
    """Alien and ancient: Phrygian-dominant drones, ney and reed, hand drums, metal and bells."""
    s = Song("morrowind_day", 12101, 62, "hijaz", 70, 4, room=0.9, damp=0.5, wet=0.42, tone=8000)
    s.section("intro", 2, 8, {"drone": 0.8, "metal": 0.3}, opts={"drone": dict(choir=True), "metal": dict(rate=0.1)})
    s.section("a", 4, 8, {"drone": 0.6, "frame": 0.45, "lead": 0.9, "bells": 0.25},
              opts={"lead": dict(instr="ney", lo=0, hi=9), "bells": dict(rate=0.15)})
    s.section("b", 4, 8, {"drone": 0.5, "strings": 0.4, "choir": 0.45, "lead": 0.8, "darbuka": 0.35},
              opts={"strings": dict(bright=0.5, lo=50, hi=70), "choir": dict(vowel="o", lo=48, hi=64, low=True),
                    "lead": dict(instr="reed", lo=-3, hi=6, motif=2)})
    s.section("a2", 4, 8, {"drone": 0.6, "frame": 0.5, "darbuka": 0.3, "lead": 0.9, "metal": 0.3},
              opts={"lead": dict(instr="ney", lo=1, hi=10, ornament=True), "metal": dict(rate=0.08)})
    s.section("outro", 2, 8, {"drone": 0.8, "bells": 0.3}, end=0, opts={"drone": dict(choir=True)})
    return s


def morrowind_night():
    s = Song("morrowind_night", 12202, 59, "phrygian", 52, 3, room=0.93, damp=0.55, wet=0.5, target=-22.0, tone=6500)
    s.section("intro", 2, 9, {"drone": 0.8, "metal": 0.35}, opts={"metal": dict(rate=0.1, bright=0.6)})
    s.section("a", 4, 9, {"drone": 0.6, "lead": 0.7, "bells": 0.3},
              opts={"lead": dict(instr="ney", lo=-2, hi=6, density=0.6), "bells": dict(voice="crystal", rate=0.12)})
    s.section("b", 3, 9, {"drone": 0.5, "choir": 0.4, "metal": 0.3, "lead": 0.55},
              opts={"choir": dict(vowel="u", lo=48, hi=62, low=True), "lead": dict(instr="reed", lo=-4, hi=3, density=0.5, motif=2)})
    s.section("outro", 2, 9, {"drone": 0.8, "wind": 0.3}, end=0, opts={"drone": dict(choir=True, bright=0.25)})
    return s


def black_marsh_day():
    """Humid and primeval: low drones, water drums, a dark reed."""
    s = Song("black_marsh_day", 13101, 57, "aeolian", 64, 4, room=0.88, damp=0.6, wet=0.42, tone=6500, target=-21.0)
    s.section("intro", 2, 8, {"drone": 0.85, "water": 0.5}, opts={"drone": dict(choir=True, bright=0.25), "water": dict(sparse=True)})
    s.section("a", 4, 8, {"drone": 0.6, "water": 0.6, "lead": 0.85, "pad": 0.4},
              opts={"lead": dict(instr="reed", lo=-3, hi=5), "pad": dict(cutoff=700, lo=45, hi=62)})
    s.section("b", 4, 8, {"drone": 0.55, "water": 0.55, "choir": 0.4, "lead": 0.75, "bells": 0.2},
              opts={"choir": dict(vowel="u", lo=45, hi=60, low=True), "lead": dict(instr="ney", lo=-1, hi=7, motif=2),
                    "bells": dict(rate=0.1, lo=64, hi=84)})
    s.section("a2", 4, 8, {"drone": 0.6, "water": 0.6, "lead": 0.85, "strings": 0.35},
              opts={"lead": dict(instr="reed", lo=-2, hi=6, ornament=True), "strings": dict(lo=45, hi=62, bright=0.3, attack=2.5)})
    s.section("outro", 2, 8, {"drone": 0.8, "water": 0.4}, end=0, opts={"water": dict(sparse=True)})
    return s


def black_marsh_night():
    s = Song("black_marsh_night", 13202, 55, "phrygian", 50, 4, room=0.92, damp=0.65, wet=0.5, target=-23.0, tone=5500)
    s.section("intro", 2, 8, {"drone": 0.8, "wind": 0.3}, opts={"drone": dict(choir=True, bright=0.2)})
    s.section("a", 4, 8, {"drone": 0.6, "water": 0.45, "lead": 0.65},
              opts={"water": dict(sparse=True), "lead": dict(instr="reed", lo=-4, hi=3, density=0.5)})
    s.section("b", 3, 8, {"drone": 0.55, "metal": 0.25, "choir": 0.35, "water": 0.4},
              opts={"metal": dict(rate=0.08, bright=0.5), "choir": dict(vowel="u", lo=45, hi=58, low=True), "water": dict(sparse=True)})
    s.section("outro", 2, 8, {"drone": 0.8, "wind": 0.3}, end=0)
    return s


def elsweyr_day():
    """Warm and sun-baked: hijaz oud, frame drums, ney over a drone."""
    s = Song("elsweyr_day", 14101, 64, "hijaz", 92, 4, room=0.8, damp=0.45, wet=0.33, target=-20.0)
    s.section("intro", 2, 8, {"drone": 0.6, "pluck_lead": 0.7}, opts={"pluck_lead": dict(instr="oud", lo=0, hi=7, density=0.6)})
    s.section("a", 6, 8, {"lute": 0.7, "frame": 0.5, "drone": 0.4, "lead": 0.9},
              opts={"lute": dict(instr="oud"), "lead": dict(instr="ney", lo=0, hi=9, ornament=True)})
    s.section("b", 6, 8, {"pluck_lead": 0.9, "frame": 0.55, "strings": 0.3, "drone": 0.4},
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=9, motif=2), "strings": dict(lo=50, hi=70, bright=0.5)})
    s.section("a2", 6, 8, {"lute": 0.7, "frame": 0.5, "darbuka": 0.3, "lead": 0.9},
              opts={"lute": dict(instr="oud"), "lead": dict(instr="ney", lo=2, hi=11, ornament=True)})
    s.section("outro", 2, 8, {"drone": 0.6, "pluck_lead": 0.6}, end=0, opts={"pluck_lead": dict(instr="oud", lo=0, hi=7, density=0.5)})
    return s


def elsweyr_night():
    s = Song("elsweyr_night", 14202, 62, "hijaz", 60, 3, room=0.9, damp=0.5, wet=0.44, target=-22.0, tone=7000)
    s.section("intro", 2, 9, {"drone": 0.7, "pluck_lead": 0.6}, opts={"pluck_lead": dict(instr="oud", lo=-2, hi=5, density=0.5)})
    s.section("a", 4, 9, {"drone": 0.55, "pluck_lead": 0.75, "bells": 0.2},
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=8, density=0.7), "bells": dict(rate=0.12)})
    s.section("b", 4, 9, {"drone": 0.55, "lead": 0.7, "frame": 0.25},
              opts={"lead": dict(instr="ney", lo=-1, hi=7, density=0.6, motif=2)})
    s.section("outro", 2, 9, {"drone": 0.7, "wind": 0.25}, end=0)
    return s


def valenwood_day():
    """Green and alive: woodwinds over harp and pizzicato in a lilting 6/8."""
    s = Song("valenwood_day", 15101, 67, "mixolydian", 84, 3, room=0.82, damp=0.5, wet=0.36, target=-20.0)
    s.section("intro", 2, 6, {"harp": 0.5, "lead": 0.6}, opts={"lead": dict(instr="recorder", lo=2, hi=9, density=0.6)})
    s.section("a", 8, 6, {"harp": 0.5, "lute": 0.45, "lead": 0.9, "frame": 0.3},
              opts={"lute": dict(instr="pizz"), "lead": dict(instr="flute", lo=0, hi=10, ornament=True)})
    s.section("b", 8, 6, {"lead": 0.8, "harp": 0.45, "strings": 0.3, "bells": 0.3},
              opts={"lead": dict(instr="reed", lo=-2, hi=6, motif=2), "strings": dict(bright=0.5), "bells": dict(rate=0.2)})
    s.section("a2", 8, 6, {"harp": 0.5, "lute": 0.45, "lead": 0.9, "frame": 0.35},
              opts={"lute": dict(instr="pizz"), "lead": dict(instr="recorder", lo=2, hi=11, ornament=True, double=-12)})
    s.section("outro", 2, 6, {"harp": 0.5, "pad": 0.4}, end=0)
    return s


def valenwood_night():
    s = Song("valenwood_night", 15202, 64, "dorian", 56, 4, room=0.9, damp=0.55, wet=0.46, target=-22.0, tone=7000)
    s.section("intro", 2, 8, {"pad": 0.55, "bells": 0.3})
    s.section("a", 4, 8, {"harp": 0.45, "pad": 0.4, "lead": 0.7},
              opts={"harp": dict(step=1.0, density=0.7), "lead": dict(instr="whistle", lo=0, hi=7, density=0.7)})
    s.section("b", 4, 8, {"strings": 0.4, "lead": 0.6, "bells": 0.3},
              opts={"strings": dict(bright=0.35, attack=2.0), "lead": dict(instr="reed", lo=-3, hi=4, density=0.6, motif=2)})
    s.section("outro", 2, 8, {"pad": 0.55, "harp": 0.35}, end=0, opts={"harp": dict(step=1.5, density=0.6)})
    return s


def summerset_day():
    """Luminous and serene: crystalline bells, harp and a high choir."""
    s = Song("summerset_day", 16101, 64, "lydian", 66, 4, room=0.92, damp=0.3, wet=0.46, tone=11000)
    s.section("intro", 2, 8, {"choir": 0.5, "bells": 0.4}, opts={"choir": dict(vowel="a", attack=2.5, lo=60, hi=79),
                                                               "bells": dict(voice="crystal", rate=0.3)})
    s.section("a", 4, 8, {"harp": 0.6, "choir": 0.5, "lead": 0.8},
              opts={"harp": dict(pattern="updown", lo=60, hi=88), "lead": dict(instr="flute", lo=4, hi=12)})
    s.section("b", 4, 8, {"strings": 0.6, "choir": 0.7, "bells": 0.4, "lead": 0.7},
              opts={"choir": dict(voices=5, lo=60, hi=81), "bells": dict(voice="crystal"), "lead": dict(instr="horn", lo=0, hi=7, motif=2)})
    s.section("a2", 4, 8, {"harp": 0.6, "choir": 0.6, "harp#2": 0.35, "lead": 0.85},
              opts={"harp#2": dict(instr="crystal", step=1.0, lo=72, hi=96, density=0.7), "lead": dict(instr="flute", lo=4, hi=13, ornament=True)})
    s.section("outro", 2, 8, {"choir": 0.6, "bells": 0.35, "strings": 0.4}, end=0,
              opts={"bells": dict(voice="crystal", rate=0.25), "strings": dict(attack=2.0, bright=0.5)})
    return s


def summerset_night():
    s = Song("summerset_night", 16202, 61, "lydian", 52, 3, room=0.94, damp=0.35, wet=0.52, target=-22.0, tone=10000)
    s.section("intro", 2, 9, {"choir": 0.45, "bells": 0.35}, opts={"choir": dict(vowel="u"), "bells": dict(voice="crystal", rate=0.2)})
    s.section("a", 4, 9, {"harp": 0.45, "bells": 0.3, "lead": 0.65},
              opts={"harp": dict(step=1.0, density=0.7, lo=60, hi=86), "bells": dict(voice="crystal", rate=0.15),
                    "lead": dict(instr="whistle", lo=2, hi=9, density=0.6)})
    s.section("b", 3, 9, {"choir": 0.5, "strings": 0.4, "bells": 0.3},
              opts={"choir": dict(vowel="o"), "strings": dict(bright=0.4, attack=2.5)})
    s.section("outro", 2, 9, {"choir": 0.5, "bells": 0.3}, end=0, opts={"choir": dict(vowel="u"), "bells": dict(voice="crystal")})
    return s


# Bayati-like maqam on the tonic: Phrygian with a half-flat second degree in the melody (harmony avoids that degree)
BAYATI = {1: 0.5}
SAFE_PROG = [0, 3, 5, 0]


def hammerfell_day():
    """Proud desert kingdoms: maqam melody on oud and ney over a drone, darbuka rhythms."""
    s = Song("hammerfell_day", 17101, 62, "phrygian", 96, 4, room=0.78, damp=0.45, wet=0.32, target=-20.0)
    s.section("intro", 2, 8, {"drone": 0.6, "pluck_lead": 0.7}, prog=[0, 0], plain=True,
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=7, density=0.6, micro=BAYATI)})
    s.section("a", 6, 8, {"drone": 0.4, "darbuka": 0.6, "pluck_lead": 0.9}, prog=SAFE_PROG + SAFE_PROG[:2], plain=True,
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=9, micro=BAYATI)})
    s.section("b", 6, 8, {"drone": 0.4, "darbuka": 0.6, "lead": 0.9, "lute": 0.4}, prog=SAFE_PROG + SAFE_PROG[:2], plain=True,
              opts={"lead": dict(instr="ney", lo=1, hi=10, micro=BAYATI, motif=2, ornament=True), "lute": dict(instr="oud")})
    s.section("a2", 6, 8, {"darbuka": 0.7, "pluck_lead": 0.9, "lead": 0.45, "drone": 0.45}, prog=SAFE_PROG + SAFE_PROG[:2], plain=True,
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=9, micro=BAYATI), "lead": dict(instr="reed", lo=-3, hi=5, micro=BAYATI, density=0.6)})
    s.section("outro", 2, 8, {"drone": 0.6, "pluck_lead": 0.6}, prog=[3, 0], plain=True,
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=7, density=0.5, micro=BAYATI)})
    return s


def hammerfell_night():
    s = Song("hammerfell_night", 17202, 60, "phrygian", 58, 4, room=0.9, damp=0.5, wet=0.45, target=-22.0, tone=7000)
    s.section("intro", 2, 8, {"drone": 0.7, "pluck_lead": 0.55}, prog=[0, 0], plain=True,
              opts={"pluck_lead": dict(instr="oud", lo=-2, hi=5, density=0.5, micro=BAYATI)})
    s.section("a", 4, 8, {"drone": 0.55, "lead": 0.75, "darbuka": 0.2}, prog=SAFE_PROG, plain=True,
              opts={"lead": dict(instr="ney", lo=0, hi=8, micro=BAYATI, density=0.7), "darbuka": dict(rolls=False)})
    s.section("b", 4, 8, {"drone": 0.55, "pluck_lead": 0.75, "bells": 0.2}, prog=SAFE_PROG, plain=True,
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=8, micro=BAYATI, motif=2), "bells": dict(rate=0.12, scale=[0, 2, 3, 4])})
    s.section("outro", 2, 8, {"drone": 0.7, "wind": 0.25}, prog=[0, 0])
    return s


def high_rock_day():
    """Breton highlands: a lute-and-bodhran jig, recorder and fiddle, Dorian."""
    s = Song("high_rock_day", 18101, 62, "dorian", 92, 3, room=0.72, damp=0.5, wet=0.3, target=-19.5)
    s.section("intro", 2, 6, {"lute": 0.7})
    s.section("a", 8, 6, {"lute": 0.8, "bodhran": 0.5, "lead": 0.9},
              opts={"lead": dict(instr="recorder", lo=0, hi=10, ornament=True)})
    s.section("b", 8, 6, {"lute": 0.7, "lead": 0.9, "bodhran": 0.5, "pad": 0.3},
              opts={"lead": dict(instr="fiddle", lo=0, hi=9, motif=2, ornament=True)})
    s.section("a2", 8, 6, {"lute": 0.8, "lead": 0.9, "bodhran": 0.55, "strings": 0.3},
              opts={"lead": dict(instr="recorder", lo=2, hi=11, ornament=True, double=-12), "strings": dict(bright=0.5)})
    s.section("outro", 2, 6, {"lute": 0.7, "pad": 0.4}, end=0)
    return s


def high_rock_night():
    s = Song("high_rock_night", 18202, 57, "dorian", 54, 4, room=0.9, damp=0.55, wet=0.46, target=-22.0, tone=7500)
    s.section("intro", 2, 8, {"harp": 0.45, "drone": 0.5}, opts={"harp": dict(step=1.0, density=0.7)})
    s.section("a", 4, 8, {"harp": 0.45, "lead": 0.75, "pad": 0.35},
              opts={"harp": dict(step=1.0), "lead": dict(instr="whistle", lo=0, hi=8, density=0.7)})
    s.section("b", 4, 8, {"strings": 0.5, "lead": 0.65, "harp": 0.3},
              opts={"strings": dict(bright=0.4, attack=2.0), "lead": dict(instr="recorder", lo=0, hi=8, density=0.7, motif=2),
                    "harp": dict(step=1.5, density=0.6)})
    s.section("outro", 2, 8, {"harp": 0.4, "drone": 0.6}, end=0)
    return s


def atmora_day():
    """The frozen homeland: throat-singing drones, horns, a male chant, wind."""
    s = Song("atmora_day", 19101, 50, "aeolian", 60, 4, room=0.93, damp=0.45, wet=0.45, target=-20.5, tone=8000)
    s.section("intro", 2, 8, {"throat": 0.8, "wind": 0.4})
    s.section("a", 4, 8, {"throat": 0.6, "horns": 0.5, "lead": 0.8, "timp": 0.4},
              opts={"lead": dict(instr="horn", lo=-5, hi=4, octave=1), "horns": dict(lo=45, hi=62), "timp": dict(every=2)})
    s.section("b", 4, 8, {"throat": 0.5, "choir": 0.5, "strings": 0.45, "lead": 0.75, "boom": 0.4},
              opts={"choir": dict(vowel="u", lo=43, hi=60, low=True), "strings": dict(lo=40, hi=62, bright=0.35),
                    "lead": dict(instr="horn", lo=-3, hi=5, motif=2, octave=1), "boom": dict(every=2)})
    s.section("a2", 4, 8, {"throat": 0.6, "horns": 0.6, "chant": 0.6, "timp": 0.5},
              opts={"chant": dict(lo=-3, hi=4, octave=0, vowels="oaou"), "horns": dict(lo=45, hi=62)})
    s.section("outro", 2, 8, {"throat": 0.8, "wind": 0.45}, end=0)
    return s


def atmora_night():
    s = Song("atmora_night", 19202, 47, "phrygian", 48, 4, room=0.95, damp=0.5, wet=0.52, target=-22.5, tone=6500)
    s.section("intro", 2, 8, {"throat": 0.8, "wind": 0.45})
    s.section("a", 4, 8, {"throat": 0.6, "lead": 0.6, "bells": 0.15},
              opts={"lead": dict(instr="horn", lo=-3, hi=4, density=0.5, octave=1), "bells": dict(rate=0.08, lo=67, hi=86)})
    s.section("b", 3, 8, {"throat": 0.55, "choir": 0.45, "wind": 0.35}, opts={"choir": dict(vowel="u", lo=43, hi=59, low=True)})
    s.section("outro", 2, 8, {"throat": 0.8, "wind": 0.45}, end=0)
    return s


def akavir_day():
    """Akavir: yo-scale koto and shakuhachi over a drone, measured taiko."""
    s = Song("akavir_day", 20101, 62, "dorian", 70, 4, room=0.86, damp=0.45, wet=0.38, target=-20.5, tone=9000)
    s.section("intro", 2, 8, {"harp": 0.55, "drone": 0.35}, opts={"harp": dict(instr="koto", scale=YO, step=1.0, lo=57, hi=81)})
    s.section("a", 4, 8, {"harp": 0.55, "lead": 0.9, "drone": 0.3},
              opts={"harp": dict(instr="koto", scale=YO, step=0.5, pattern="wave", lo=55, hi=79),
                    "lead": dict(instr="shakuhachi", lo=0, hi=9, scale=YO)})
    s.section("b", 4, 8, {"taiko": 0.5, "pluck_lead": 0.8, "drone": 0.4, "bells": 0.2},
              opts={"taiko": dict(half=True), "pluck_lead": dict(instr="koto", lo=2, hi=11, scale=YO, tremolo=False, motif=2),
                    "bells": dict(scale=YO, rate=0.15)})
    s.section("a2", 4, 8, {"harp": 0.55, "lead": 0.9, "taiko": 0.55},
              opts={"harp": dict(instr="koto", scale=YO, step=0.5, pattern="updown", lo=55, hi=79),
                    "lead": dict(instr="shakuhachi", lo=2, hi=11, scale=YO, ornament=True), "taiko": dict(patterns=(0, 2))})
    s.section("outro", 2, 8, {"harp": 0.5, "drone": 0.4}, end=0, opts={"harp": dict(instr="koto", scale=YO, step=1.5)})
    return s


def akavir_night():
    s = Song("akavir_night", 20202, 64, "phrygian", 50, 4, room=0.92, damp=0.5, wet=0.48, target=-22.0, tone=8000)
    s.section("intro", 2, 8, {"drone": 0.6, "bells": 0.3}, opts={"bells": dict(scale=IN, rate=0.15)})
    s.section("a", 4, 8, {"harp": 0.5, "lead": 0.8, "drone": 0.35},
              opts={"harp": dict(instr="koto", scale=IN, step=1.0, density=0.6, lo=52, hi=76),
                    "lead": dict(instr="shakuhachi", lo=-2, hi=7, scale=IN, density=0.6)})
    s.section("b", 3, 8, {"pluck_lead": 0.7, "drone": 0.45, "wind": 0.2},
              opts={"pluck_lead": dict(instr="koto", lo=0, hi=8, scale=IN, tremolo=False, density=0.7, motif=2)})
    s.section("outro", 2, 8, {"drone": 0.6, "bells": 0.3}, end=0, opts={"bells": dict(scale=IN, rate=0.12)})
    return s


def sea_day():
    """Under sail: a rolling 6/8 of strings and harp, horn calls, the choir like wind in the rigging."""
    s = Song("sea_day", 21101, 62, "mixolydian", 88, 3, room=0.88, damp=0.45, wet=0.4)
    s.section("intro", 2, 6, {"strings": 0.6, "harp": 0.45}, opts={"strings": dict(attack=2.0), "harp": dict(pattern="wave")})
    s.section("a", 8, 6, {"strings": 0.7, "harp": 0.5, "lead": 0.9, "timp": 0.35},
              opts={"harp": dict(pattern="wave"), "lead": dict(instr="horn", lo=-2, hi=7), "timp": dict(every=4)})
    s.section("b", 8, 6, {"choir": 0.6, "strings": 0.7, "lead": 0.85, "bells": 0.2},
              opts={"choir": dict(vowel="a"), "lead": dict(instr="flute", lo=2, hi=11, motif=2)})
    s.section("a2", 8, 6, {"strings": 0.8, "horns": 0.5, "lead": 1.0, "timp": 0.5, "swell": 0.5, "harp": 0.4},
              opts={"lead": dict(instr="horn", lo=0, hi=9, ornament=True), "timp": dict(every=2)})
    s.section("outro", 2, 6, {"strings": 0.7, "choir": 0.5}, end=0, opts={"choir": dict(vowel="o")})
    return s


def sea_night():
    s = Song("sea_night", 21202, 57, "dorian", 52, 3, room=0.94, damp=0.5, wet=0.5, target=-22.5, tone=7000)
    s.section("intro", 2, 9, {"pad": 0.6, "wind": 0.3})
    s.section("a", 4, 9, {"pad": 0.5, "harp": 0.4, "lead": 0.7},
              opts={"harp": dict(step=1.0, pattern="wave", density=0.7), "lead": dict(instr="whistle", lo=0, hi=7, density=0.7)})
    s.section("b", 3, 9, {"choir": 0.5, "strings": 0.4, "bells": 0.3},
              opts={"choir": dict(vowel="u"), "strings": dict(bright=0.35, attack=2.5)})
    s.section("outro", 2, 9, {"pad": 0.6, "wind": 0.3}, end=0)
    return s


# ------------------------------------------------------------------------------------------------  towns by culture

def town_imperial():
    """A courtly minuet: lute and pizzicato strings, recorder and fiddle."""
    s = Song("town_imperial", 31101, 67, "ionian", 100, 3, room=0.62, damp=0.5, wet=0.28, target=-19.5)
    s.section("intro", 2, 6, {"lute": 0.6}, opts={"lute": dict(instr="pizz")})
    s.section("a", 8, 6, {"lute": 0.7, "lute#2": 0.4, "lead": 0.9, "frame": 0.3},
              opts={"lute#2": dict(instr="pizz"), "lead": dict(instr="recorder", lo=0, hi=10, ornament=True)})
    s.section("b", 8, 6, {"strings": 0.4, "lead": 0.85, "lute": 0.7, "harp": 0.3},
              opts={"strings": dict(bright=0.5), "lead": dict(instr="fiddle", lo=0, hi=9, motif=2, ornament=True)})
    s.section("a2", 8, 6, {"lute": 0.7, "lute#2": 0.4, "lead": 0.9, "frame": 0.35},
              opts={"lute#2": dict(instr="pizz"), "lead": dict(instr="recorder", lo=2, hi=11, ornament=True, double=-12)})
    s.section("outro", 2, 6, {"lute": 0.6, "pad": 0.35}, end=0)
    return s


def town_dunmer():
    s = Song("town_dunmer", 31202, 62, "hijaz", 84, 4, room=0.7, damp=0.5, wet=0.3, target=-19.5)
    s.section("intro", 2, 8, {"drone": 0.5, "lute": 0.6}, opts={"lute": dict(instr="oud")})
    s.section("a", 6, 8, {"lute": 0.7, "darbuka": 0.45, "lead": 0.85},
              opts={"lute": dict(instr="oud"), "lead": dict(instr="reed", lo=-2, hi=7)})
    s.section("b", 6, 8, {"pluck_lead": 0.85, "frame": 0.4, "drone": 0.4, "bells": 0.2},
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=9, motif=2)})
    s.section("a2", 6, 8, {"lute": 0.7, "darbuka": 0.5, "lead": 0.9},
              opts={"lute": dict(instr="oud"), "lead": dict(instr="ney", lo=0, hi=9, ornament=True)})
    s.section("outro", 2, 8, {"drone": 0.5, "lute": 0.6}, end=0, opts={"lute": dict(instr="oud")})
    return s


def town_desert():
    """Bazaar music for Khajiit and Redguard towns: double-harmonic dance, oud, darbuka."""
    s = Song("town_desert", 31303, 64, "double_harmonic", 108, 4, room=0.68, damp=0.45, wet=0.27, target=-19.0)
    s.section("intro", 2, 8, {"darbuka": 0.5, "drone": 0.4})
    s.section("a", 6, 8, {"darbuka": 0.65, "lute": 0.7, "lead": 0.9},
              opts={"lute": dict(instr="oud"), "lead": dict(instr="ney", lo=0, hi=9, ornament=True)})
    s.section("b", 6, 8, {"darbuka": 0.6, "pluck_lead": 0.9, "drone": 0.4, "frame": 0.3},
              opts={"pluck_lead": dict(instr="oud", lo=0, hi=10, motif=2)})
    s.section("a2", 6, 8, {"darbuka": 0.7, "lute": 0.6, "lead": 0.8, "bells": 0.2},
              opts={"lute": dict(instr="oud"), "lead": dict(instr="reed", lo=-2, hi=7, ornament=True)})
    s.section("outro", 2, 8, {"darbuka": 0.5, "drone": 0.5}, end=0)
    return s


def town_elven():
    """Altmer and Bosmer towns: harp and crystal over gentle strings, flute."""
    s = Song("town_elven", 31404, 64, "lydian", 76, 3, room=0.85, damp=0.35, wet=0.38, target=-20.0, tone=10500)
    s.section("intro", 2, 6, {"harp": 0.6, "bells": 0.3}, opts={"bells": dict(voice="crystal", rate=0.25)})
    s.section("a", 8, 6, {"harp": 0.6, "lead": 0.9, "strings": 0.35},
              opts={"lead": dict(instr="flute", lo=2, hi=11), "strings": dict(bright=0.5)})
    s.section("b", 8, 6, {"harp": 0.4, "harp#2": 0.35, "choir": 0.5, "lead": 0.85, "pad": 0.3},
              opts={"harp#2": dict(instr="crystal", step=1.0, lo=72, hi=96, density=0.7), "choir": dict(vowel="a", lo=60, hi=79),
                    "lead": dict(instr="recorder", lo=0, hi=9, motif=2)})
    s.section("a2", 8, 6, {"harp": 0.6, "strings": 0.45, "lead": 0.9, "bells": 0.3},
              opts={"lead": dict(instr="flute", lo=3, hi=12, ornament=True), "bells": dict(voice="crystal")})
    s.section("outro", 2, 6, {"harp": 0.5, "choir": 0.4}, end=0, opts={"choir": dict(vowel="o")})
    return s


# ------------------------------------------------------------------------------------------------  dungeons by culture

def dungeon_dwemer():
    """Dwemer ruins: clockwork, steam, metal, low drones; the machines are still running."""
    s = Song("dungeon_dwemer", 41101, 50, "harmonic", 60, 4, room=0.94, damp=0.55, wet=0.48, target=-22.5, tone=6500)
    s.section("intro", 1, 16, {"drone": 0.8, "steam": 0.5}, opts={"drone": dict(bright=0.3)})
    s.section("a", 3, 8, {"drone": 0.6, "clock": 0.5, "metal": 0.4, "steam": 0.4}, opts={"metal": dict(rate=0.12, bright=0.9)})
    s.section("b", 3, 8, {"drone": 0.5, "clock": 0.55, "horns": 0.4, "boom": 0.4},
              opts={"horns": dict(lo=43, hi=58), "boom": dict(every=2)})
    s.section("c", 3, 8, {"drone": 0.6, "choir": 0.4, "clock": 0.35, "steam": 0.4},
              opts={"choir": dict(vowel="u", lo=45, hi=60, low=True), "clock": dict(density=0.5)})
    s.section("d", 3, 8, {"drone": 0.7, "metal": 0.5, "steam": 0.5, "clock": 0.3}, end=0, opts={"clock": dict(density=0.4)})
    return s


def dungeon_ayleid():
    """Ayleid ruins: welkynd-stone glow, glassy bells, a distant choir; beautiful and wrong."""
    s = Song("dungeon_ayleid", 41202, 59, "harmonic", 48, 4, room=0.96, damp=0.4, wet=0.55, target=-23.0, tone=8500)
    s.section("intro", 1, 16, {"drone": 0.7, "bells": 0.3}, opts={"drone": dict(choir=True, bright=0.25), "bells": dict(voice="crystal", rate=0.12)})
    s.section("a", 3, 10, {"pad": 0.5, "bells": 0.4, "choir": 0.35},
              opts={"pad": dict(cutoff=900), "bells": dict(voice="crystal", rate=0.15), "choir": dict(vowel="u")})
    s.section("b", 3, 10, {"drone": 0.5, "harp": 0.35, "lead": 0.5, "choir": 0.4},
              opts={"harp": dict(instr="crystal", step=1.0, density=0.5, lo=67, hi=91),
                    "lead": dict(instr="whistle", lo=0, hi=7, density=0.4), "choir": dict(vowel="o")})
    s.section("c", 3, 10, {"drone": 0.8, "bells": 0.3, "wind": 0.3}, end=0, opts={"bells": dict(voice="crystal", rate=0.1)})
    return s


def blackreach():
    """A vast lightless cavern lit by glowing fungus: slow, wide, quietly awestruck."""
    s = Song("blackreach", 42101, 61, "dorian", 50, 3, room=0.97, damp=0.4, wet=0.56, target=-22.5, tone=8500)
    s.section("intro", 1, 12, {"drone": 0.7, "bells": 0.3}, opts={"bells": dict(voice="crystal", rate=0.15)})
    s.section("a", 3, 12, {"pad": 0.5, "choir": 0.45, "harp": 0.3},
              opts={"choir": dict(vowel="u"), "harp": dict(instr="crystal", step=1.5, density=0.5, lo=64, hi=88)})
    s.section("b", 3, 12, {"choir": 0.5, "strings": 0.4, "bells": 0.35, "lead": 0.45},
              opts={"choir": dict(vowel="a", lo=57, hi=76), "strings": dict(bright=0.3, attack=3.0),
                    "bells": dict(voice="crystal", rate=0.18), "lead": dict(instr="whistle", lo=0, hi=7, density=0.4)})
    s.section("c", 2, 12, {"drone": 0.8, "bells": 0.3, "wind": 0.25}, end=0, opts={"bells": dict(voice="crystal", rate=0.1)})
    return s


def boss_battle():
    """For the named horrors that are not dragons: dragon priests, deathlords, giants, chieftains."""
    s = Song("boss_battle", 61202, 48, "harmonic", 116, 4, room=0.82, damp=0.45, wet=0.28, target=-16.5)
    s.rubato = 0.2
    loop = progression(s.rng, s.key, 4, 0)
    s.section("intro", 4, 4, {"boom": 0.8, "taiko": 0.6, "string_ost": 0.6, "swell": 0.0}, prog=loop,
              opts={"taiko": dict(half=True), "boom": dict(every=2)})
    s.section("a", 8, 4, {"taiko": 0.9, "brass_ost": 0.8, "string_ost": 0.7, "chant": 0.7, "swell": 0.8}, prog=loop * 2,
              opts={"chant": dict(lo=-3, hi=4, octave=0, vowels="oaoe"), "brass_ost": dict(pattern=1)})
    s.section("b", 8, 4, {"taiko": 1.0, "horns": 0.7, "choir": 0.6, "lead": 1.0, "timp": 0.7, "swell": 0.8}, prog=loop * 2,
              opts={"lead": dict(instr="horn", lo=0, hi=9), "choir": dict(vowel="a", attack=0.5), "taiko": dict(patterns=(3, 4))})
    s.section("c", 8, 4, {"taiko": 0.7, "chant": 0.9, "strings": 0.6, "stabs": 0.35, "swell": 0.8},
              opts={"taiko": dict(half=True), "chant": dict(lo=0, hi=7, octave=0, vowels="aoau"), "stabs": dict(every=2)})
    s.section("d", 8, 4, {"taiko": 0.9, "string_ost": 0.7, "brass_ost": 0.7, "lead": 0.9, "boom": 0.5, "swell": 0.8}, prog=loop * 2,
              opts={"lead": dict(instr="horn", lo=-2, hi=7, motif=2), "brass_ost": dict(pattern=3), "boom": dict(every=4)})
    s.section("a2", 8, 4, {"taiko": 1.0, "brass_ost": 0.8, "string_ost": 0.6, "lead": 1.0, "choir": 0.5, "timp": 0.6, "swell": 0.8},
              prog=loop * 2, opts={"lead": dict(instr="horn", lo=2, hi=11), "taiko": dict(rims=True), "timp": dict(every=2)})
    s.section("outro", 2, 4, {"timp": 0.9, "boom": 0.8, "choir": 0.5}, prog=[5, 0], opts={"boom": dict(every=1)})
    return s


def discovery():
    """6-10 s sting: timpani, low swell, rising horn call, choir bloom, harp run."""
    rng = np.random.default_rng(9101)
    k = Key(62, "dorian")
    mix = Mix(14.0)
    mix.add(timpani(38, 1.0, rng), 0.05, 0.8, send=0.4)
    mix.add(boom(rng, f=36.0), 0.05, 0.4, send=0.3)
    for m in (38, 45, 50):
        mix.add(strings_note(m, 3.5, rng, attack=1.2, release=3.0, bright=0.6), 0.05, 0.22, send=0.45)
    call = [(0.35, 0.45, k.m(-7 + 0), 0.8), (0.82, 0.4, k.m(-7 + 4), 0.85), (1.24, 0.35, k.m(-7 + 3), 0.8),
            (1.6, 0.4, k.m(-7 + 4), 0.85), (2.02, 0.5, k.m(-7 + 6), 0.9), (2.55, 2.4, k.m(-7 + 7), 1.0)]
    t0, y = render_lead(call, "horn", rng)
    mix.add(y, t0, 0.42, send=0.5, pan=-0.1)
    for j, m in enumerate((62, 66, 69, 74)):  # bright D major bloom (Picardy lift)
        mix.add(choir_note(m, 4.0, rng, vowel="a", attack=1.4, release=3.0), 2.4, 0.25, send=0.6)
        mix.add(strings_note(m - 12, 4.0, rng, attack=1.0, release=3.0, bright=0.8), 2.4, 0.12, send=0.5)
    for j, m in enumerate((62, 66, 69, 74, 78, 81, 86)):
        mix.add(harp(m, 4.0, rng), 2.5 + j * 0.11, 0.35, send=0.5, pan=-0.6 + 0.2 * j)
    mix.add(cymbal_swell(2.4, rng), 0.05, 0.6, send=0.4)
    out = render_mix(mix, 0.9, 0.35, 0.45, tone=10000)
    return master(out, -18.0, max_len=9.0)


def level_up():
    """3 s sting: bright harp arpeggio, choir swell, chimes."""
    rng = np.random.default_rng(9202)
    mix = Mix(7.0)
    for j, m in enumerate((62, 66, 69, 74, 78, 81)):
        mix.add(harp(m, 3.5, rng, bright=0.8), 0.03 + j * 0.07, 0.4, send=0.45, pan=-0.5 + 0.2 * j)
    for m in (62, 69, 74, 78):
        mix.add(choir_note(m, 1.6, rng, vowel="a", attack=0.35, release=1.4), 0.15, 0.25, send=0.6)
    mix.add(strings_note(50, 1.6, rng, attack=0.3, release=1.4, bright=0.5), 0.1, 0.25, send=0.4)
    for j, m in enumerate((86, 90, 93)):
        mix.add(bell(m, 3.0, rng, bright=0.8), 0.5 + j * 0.09, 0.3, send=0.6, pan=0.3 - 0.3 * j)
    mix.add(timpani(38, 0.7, rng, dur=2.0), 0.03, 0.5, send=0.3)
    out = render_mix(mix, 0.82, 0.35, 0.4, tone=11000)
    return master(out, -18.0, max_len=3.4)


TRACKS = {
    # Skyrim (the historical events music.explore / music.explore_night / music.town / music.dungeon)
    "explore_day_1": explore_day_1, "explore_day_2": explore_day_2, "explore_day_3": explore_day_3,
    "explore_night_1": explore_night_1, "explore_night_2": explore_night_2,
    "town_1": town_1, "town_2": town_2,
    "dungeon_1": dungeon_1, "dungeon_2": dungeon_2,
    # the provinces and beyond
    "cyrodiil_day": cyrodiil_day, "cyrodiil_night": cyrodiil_night,
    "morrowind_day": morrowind_day, "morrowind_night": morrowind_night,
    "black_marsh_day": black_marsh_day, "black_marsh_night": black_marsh_night,
    "elsweyr_day": elsweyr_day, "elsweyr_night": elsweyr_night,
    "valenwood_day": valenwood_day, "valenwood_night": valenwood_night,
    "summerset_day": summerset_day, "summerset_night": summerset_night,
    "hammerfell_day": hammerfell_day, "hammerfell_night": hammerfell_night,
    "high_rock_day": high_rock_day, "high_rock_night": high_rock_night,
    "atmora_day": atmora_day, "atmora_night": atmora_night,
    "akavir_day": akavir_day, "akavir_night": akavir_night,
    "sea_day": sea_day, "sea_night": sea_night,
    # towns and dungeons by culture
    "town_imperial": town_imperial, "town_dunmer": town_dunmer, "town_desert": town_desert, "town_elven": town_elven,
    "dungeon_dwemer": dungeon_dwemer, "dungeon_ayleid": dungeon_ayleid, "blackreach": blackreach,
    # danger and the other realms
    "combat_1": combat_1, "combat_2": combat_2, "boss_dragon": boss_dragon, "boss_battle": boss_battle,
    "oblivion_1": oblivion_1, "sovngarde_1": sovngarde_1,
    "discovery": discovery, "level_up": level_up,
}

# Encoding per track: (sample rate, Vorbis quality). Long pieces 24 kHz q1 (about 40 kb/s stereo) keep the whole
# score inside the jar budget; percussive combat pieces get a little more; the short stings full quality.
ENCODING = {"combat_1": (24000, 1.5), "combat_2": (24000, 1.5), "boss_dragon": (24000, 1.5), "boss_battle": (24000, 1.5),
            "discovery": (32000, 3.0), "level_up": (32000, 3.0)}


# =====================================================================================================  output

def write_wav(path, x: np.ndarray):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(audiolib.to_pcm16(x))


def render_track(name: str, wav_dir=None, quality_override=None):
    """Renders and encodes one track (runs in a worker process). Returns a printable summary line and the size."""
    t0 = time.time()
    res = TRACKS[name]()
    x = res.render() if isinstance(res, Song) else res
    if not np.all(np.isfinite(x)):
        raise SystemExit(f"{name}: non-finite samples")
    if wav_dir:
        pathlib.Path(wav_dir).mkdir(parents=True, exist_ok=True)
        write_wav(pathlib.Path(wav_dir) / f"{name}.wav", x)
    out_sr, q = ENCODING.get(name, (ENCODE_SR, ENCODE_Q))
    if quality_override is not None:
        q = quality_override
    out = audiolib.write_ogg(x, OUT_DIR / f"{name}.ogg", SR, quality=q, out_sr=out_sr,
                             title=f"Skycraft - {name.replace('_', ' ').title()}")
    size = out.stat().st_size
    peak = 20 * math.log10(max(1e-9, float(np.max(np.abs(x)))))
    rms = 20 * math.log10(max(1e-9, float(np.sqrt(np.mean(x ** 2)))))
    return (f"{name:18s} {x.shape[1] / SR:6.1f}s  peak {peak:5.1f} dB  rms {rms:5.1f} dB  {size / 1e6:5.2f} MB  "
            f"({time.time() - t0:5.1f}s)"), size


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tracks", nargs="*", help="track names (default: all)")
    ap.add_argument("--quality", type=float, default=None, help="override the Vorbis quality of every track")
    ap.add_argument("--wav", metavar="DIR", help="also write 16-bit WAVs to DIR")
    ap.add_argument("-j", "--jobs", type=int, default=1, help="render this many tracks in parallel")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()
    if args.list:
        print("\n".join(TRACKS))
        return 0
    names = args.tracks or list(TRACKS)
    for n in names:
        if n not in TRACKS:
            raise SystemExit(f"unknown track {n!r}; use --list")
    audiolib.find_ffmpeg()
    total = 0
    if args.jobs > 1:
        with concurrent.futures.ProcessPoolExecutor(max_workers=args.jobs) as pool:
            futs = {pool.submit(render_track, n, args.wav, args.quality): n for n in names}
            for f in concurrent.futures.as_completed(futs):
                line, size = f.result()
                total += size
                print(line, flush=True)
    else:
        for n in names:
            line, size = render_track(n, args.wav, args.quality)
            total += size
            print(line, flush=True)
    all_size = sum(p.stat().st_size for p in OUT_DIR.glob("*.ogg"))
    print(f"rendered {total / 1e6:.2f} MB; music folder now {all_size / 1e6:.2f} MB -> {OUT_DIR.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
