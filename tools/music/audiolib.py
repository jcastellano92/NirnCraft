"""
Shared helpers for the NirnCraft audio tools: locating ffmpeg and writing Ogg Vorbis.

ffmpeg is looked up in this order:
  1. the FFMPEG environment variable (path to the executable),
  2. ffmpeg on PATH,
  3. a few well-known install locations (Windows: Streamlabs OBS / OBS / Chocolatey / Scoop / C:\\ffmpeg).
It must be built with libvorbis: Minecraft only plays Ogg Vorbis.
"""
from __future__ import annotations

import os
import pathlib
import shutil
import subprocess

import numpy as np

_KNOWN = [
    r"C:\Program Files\Streamlabs OBS\resources\app.asar.unpacked\node_modules\obs-studio-node\ffmpeg.exe",
    r"C:\Program Files\obs-studio\bin\64bit\ffmpeg.exe",
    r"C:\ProgramData\chocolatey\bin\ffmpeg.exe",
    r"C:\ffmpeg\bin\ffmpeg.exe",
    os.path.expanduser(r"~\scoop\shims\ffmpeg.exe"),
    "/usr/bin/ffmpeg",
    "/usr/local/bin/ffmpeg",
    "/opt/homebrew/bin/ffmpeg",
]
_cache: dict = {}


def find_ffmpeg(required: bool = True) -> str | None:
    """Path of an ffmpeg executable with libvorbis, or None (raises SystemExit if required)."""
    if "ff" in _cache:
        ff = _cache["ff"]
    else:
        cands = []
        if os.environ.get("FFMPEG"):
            cands.append(os.environ["FFMPEG"])
        w = shutil.which("ffmpeg")
        if w:
            cands.append(w)
        cands += _KNOWN
        ff = None
        for c in cands:
            if not c or not pathlib.Path(c).is_file():
                continue
            try:
                enc = subprocess.run([c, "-hide_banner", "-encoders"], capture_output=True, text=True, timeout=30).stdout
            except (OSError, subprocess.SubprocessError):
                continue
            if "libvorbis" in enc:
                ff = c
                break
        _cache["ff"] = ff
    if ff is None and required:
        raise SystemExit("ffmpeg with libvorbis not found: install it, put it on PATH, or set FFMPEG=<path to ffmpeg>")
    return ff


def to_pcm16(x: np.ndarray, seed: int = 1) -> bytes:
    """Float samples (channels, n) or (n,) in [-1, 1] -> interleaved little-endian 16-bit PCM with TPDF dither."""
    x = np.atleast_2d(np.asarray(x, dtype=np.float64))
    rng = np.random.default_rng(seed)
    d = (rng.random(x.shape) - rng.random(x.shape)) / 32768.0
    return (np.clip(x + d, -1.0, 1.0).T * 32767.0).astype("<i2").tobytes()


def write_ogg(x: np.ndarray, out: pathlib.Path, sr: int, quality: float = 2.0, out_sr: int | None = None,
              title: str | None = None) -> pathlib.Path:
    """Encode float samples ((channels, n) or (n,)) to Ogg Vorbis at `out`. `out_sr` resamples (ffmpeg soxr/swr)."""
    x = np.atleast_2d(np.asarray(x, dtype=np.float64))
    channels = x.shape[0]
    out = pathlib.Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = [find_ffmpeg(), "-y", "-hide_banner", "-loglevel", "error", "-f", "s16le", "-ar", str(sr), "-ac", str(channels),
           "-i", "-"]
    if out_sr and out_sr != sr:
        cmd += ["-ar", str(out_sr)]
    cmd += ["-c:a", "libvorbis", "-q:a", str(quality), "-map_metadata", "-1"]
    if title:
        cmd += ["-metadata", f"title={title}", "-metadata", "artist=NirnCraft (procedural, original)"]
    cmd.append(str(out))
    subprocess.run(cmd, input=to_pcm16(x), check=True)
    return out


def loudness_normalise(x: np.ndarray, target_db: float, sr: int, peak_db: float = -1.0) -> np.ndarray:
    """Scale so the loud part (95th percentile of a 50 ms RMS envelope) sits at target_db dBFS, then limit peaks."""
    x = np.asarray(x, dtype=np.float64)
    mono = x if x.ndim == 1 else np.mean(x, axis=0)
    win = max(1, int(sr * 0.05))
    c = np.concatenate(([0.0], np.cumsum(mono ** 2)))
    env = np.sqrt(np.maximum(0.0, (c[win:] - c[:-win]) / win)) if len(mono) > win else np.sqrt(np.mean(mono ** 2) + 1e-12)[None]
    loud = float(np.percentile(env, 95)) if len(env) else 1e-9
    y = x * (10 ** (target_db / 20.0) / max(loud, 1e-9))
    lim = 10 ** (peak_db / 20.0)
    k = lim * 0.7
    a = np.abs(y)
    over = a > k
    y[over] = np.sign(y[over]) * (k + (lim - k) * np.tanh((a[over] - k) / (lim - k)))
    return y
