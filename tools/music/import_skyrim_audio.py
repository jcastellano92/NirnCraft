#!/usr/bin/env python3
"""
Build a PRIVATE resource pack that plays the audio of YOUR OWN copy of The Elder Scrolls V: Skyrim in Skycraft.

Skycraft ships its own original music and synthesised sound effects. If you own Skyrim you may prefer the real thing:
this tool reads the game's archives on your disk (Data/*.bsa plus loose Data/Music and Data/Sound files), decodes the
audio with ffmpeg (.xwm, .wav and .fuz voice files), re-encodes it to Ogg Vorbis and writes a resource pack zip whose
assets/skycraft/sounds.json overrides Skycraft's sound events with "replace": true.

  PERSONAL USE ONLY. The pack contains Bethesda's copyrighted audio. Never share or upload it.
  * Nothing is downloaded or uploaded; the game install is only read.
  * The pack is written OUTSIDE the Skycraft repository (an output path inside the repo is refused), so the
    extracted audio can never end up in the mod's sources.

How the mapping works
  * Music, creature, magic, UI, ambience and word-wall sounds are matched with a RULE TABLE: regular expressions on
    the lower-case archive path with forward slashes (e.g. "sound/fx/npc/dragon/injured/npc_dragon_injured_01.wav",
    "music/explore/mus_explore_day_01.xwm"). The first matching rule wins (a rule may feed several events).
    --map 'REGEX=EVENT' adds your own rules, tried first. --list-archive [REGEX] shows the real paths.
  * Shout voices (shout.<shout>.<1|2|3>) are the player's Thu'um lines from the voice archive
    (sound/voice/skyrim.esm/<voicetype>/voicepowers_shout01a|01b|02|03_<info>_1.fuz). Which file says which word is
    read from the dialogue records in Skyrim.esm + the localized strings: Shout01a = word 1 alone ("Fus!"),
    Shout01b = word 1 leading on ("Fus..."), Shout02 = word 2 alone ("Ro!"), Shout03 = words 2+3 ("Ro...Da!").
    One word = 01a, two words = 01b + 02, three words = 01b + 03 (concatenated with ffmpeg), and the matching
    shout effect (sound/fx/voc/shout/fx/...) is mixed in at the last word unless --no-shout-fx.
  * Events with no Skyrim equivalent keep Skycraft's own sounds (the dry run lists them).

Usage (Windows: py -3, elsewhere python3):
    py -3 tools/music/import_skyrim_audio.py --dry-run                         # what would be mapped
    py -3 tools/music/import_skyrim_audio.py --out "%APPDATA%/.minecraft/resourcepacks"
    py -3 tools/music/import_skyrim_audio.py --skyrim "D:/Games/Skyrim Special Edition" --out pack.zip --only music
    py -3 tools/music/import_skyrim_audio.py --list-archive "npc/dragon/"
    py -3 tools/music/import_skyrim_audio.py --no-skyrim --extra ~/SkyrimMusic --only music --out ~/packs
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import glob
import hashlib
import json
import mmap
import os
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import wave
import zipfile
import zlib

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
import sound_catalog  # noqa: E402  (the list of Skycraft sound events)

PACK_FORMAT = [97, 1]  # Minecraft 26.3 client resources
PACK_DESCRIPTION = "Skycraft \u2013 Personal Skyrim audio (personal use only, do not share)"
ZIP_NAME = "skycraft_personal_skyrim_audio.zip"
AUDIO_EXT = (".xwm", ".wav", ".fuz")
EXTRA_EXT = (".xwm", ".wav", ".fuz", ".mp3", ".flac", ".ogg", ".wma", ".m4a", ".opus")
SKIP_BSA = re.compile(r"textures|meshes|animations|shaders", re.I)

README = """Skycraft - Personal Skyrim audio
=================================

This resource pack was generated on the owner's computer by Skycraft's tools/music/import_skyrim_audio.py from
the owner's own installation of The Elder Scrolls V: Skyrim. The music, sound effects and voices it contains are
(c) Bethesda Softworks LLC / ZeniMax Media and their composers and voice actors.

PERSONAL USE ONLY. Do not share, upload, re-host or redistribute this pack or any file in it.

Enable it above the Skycraft resources in Options > Resource Packs. Events it does not override keep Skycraft's
own original sounds.
"""


# =====================================================================================================  archives

def _lz4_block(src: bytes, pos: int, end: int, out: bytearray) -> None:
    while pos < end:
        token = src[pos]
        pos += 1
        lit = token >> 4
        if lit == 15:
            while True:
                b = src[pos]
                pos += 1
                lit += b
                if b != 255:
                    break
        if lit:
            out += src[pos:pos + lit]
            pos += lit
        if pos >= end:
            break
        off = src[pos] | (src[pos + 1] << 8)
        pos += 2
        ml = token & 15
        if ml == 15:
            while True:
                b = src[pos]
                pos += 1
                ml += b
                if b != 255:
                    break
        ml += 4
        start = len(out) - off
        if off >= ml:
            out += out[start:start + ml]
        else:  # overlapping copy: the output is periodic with period `off`
            pat = bytes(out[start:])
            out += (pat * (ml // off + 1))[:ml]


def lz4_frame_decompress(data: bytes) -> bytes:
    try:
        import lz4.frame  # optional, much faster
        return lz4.frame.decompress(data)
    except ImportError:
        pass
    pos, out = 0, bytearray()
    while pos < len(data):
        magic = struct.unpack_from("<I", data, pos)[0]
        pos += 4
        if 0x184D2A50 <= magic <= 0x184D2A5F:  # skippable frame
            pos += 4 + struct.unpack_from("<I", data, pos)[0]
            continue
        if magic != 0x184D2204:
            raise ValueError("bad LZ4 frame magic %x" % magic)
        flg = data[pos]
        pos += 2
        if flg & 0x08:
            pos += 8
        if flg & 0x01:
            pos += 4
        pos += 1
        while True:
            bsz = struct.unpack_from("<I", data, pos)[0]
            pos += 4
            if bsz == 0:
                break
            raw = bsz & 0x80000000
            bsz &= 0x7FFFFFFF
            if raw:
                out += data[pos:pos + bsz]
            else:
                _lz4_block(data, pos, pos + bsz, out)
            pos += bsz
            if flg & 0x10:
                pos += 4
        if flg & 0x04:
            pos += 4
    return bytes(out)


class BSA:
    """Minimal reader for Skyrim BSA archives: v104 (Oldrim, zlib) and v105 (Special Edition, LZ4 frames)."""

    def __init__(self, path: pathlib.Path):
        self.path = pathlib.Path(path)
        self.name = self.path.name
        with open(self.path, "rb") as f:
            magic, ver, off, aflags, nfold, nfile, _tfold, tfile, _ff, _ = struct.unpack("<4sIIIIIIIHH", f.read(36))
            if magic != b"BSA\0" or ver not in (104, 105):
                raise ValueError(f"{self.path.name}: not a Skyrim BSA (magic {magic!r}, version {ver})")
            self.ver, self.aflags = ver, aflags
            f.seek(off)
            counts = []
            for _ in range(nfold):
                rec = f.read(24 if ver == 105 else 16)
                counts.append(struct.unpack_from("<QI", rec)[1])
            entries = []
            for cnt in counts:
                folder = ""
                if aflags & 1:
                    ln = f.read(1)[0]
                    folder = f.read(ln).rstrip(b"\0").decode("cp1252")
                for _ in range(cnt):
                    _h, size, foff = struct.unpack("<QII", f.read(16))
                    entries.append((folder, size, foff))
            names = f.read(tfile).split(b"\0")[:nfile] if aflags & 2 else []
        self.files: dict[str, tuple[int, int]] = {}
        for (folder, size, foff), n in zip(entries, names):
            path = (folder + "\\" + n.decode("cp1252")).lower().replace("\\", "/")
            self.files[path] = (size, foff)

    def _compressed(self, size: int) -> bool:
        c = bool(self.aflags & 4)
        return (not c) if size & 0x40000000 else c

    def _span(self, f, path: str, want: int | None):
        size, off = self.files[path]
        comp = self._compressed(size)
        size &= 0x3FFFFFFF
        f.seek(off)
        skip = 0
        if self.aflags & 0x100:  # embedded file name (bstring) before the data
            skip = 1 + f.read(1)[0]
            f.seek(off + skip)
        n = size - skip if (comp or want is None) else min(want, size - skip)
        return f.read(n), comp

    def read(self, path: str) -> bytes:
        with open(self.path, "rb") as f:
            data, comp = self._span(f, path, None)
        if not comp:
            return data
        orig = struct.unpack_from("<I", data, 0)[0]
        out = lz4_frame_decompress(data[4:]) if self.ver == 105 else zlib.decompress(data[4:])
        if len(out) != orig:
            raise ValueError(f"{self.name}:{path}: decompressed {len(out)} bytes, expected {orig}")
        return out

    def head(self, path: str, n: int) -> bytes:
        with open(self.path, "rb") as f:
            data, comp = self._span(f, path, n)
        return data if not comp else self.read(path)[:n]

    def real_size(self, path: str) -> int:
        size, _ = self.files[path]
        if not self._compressed(size):
            return size & 0x3FFFFFFF
        with open(self.path, "rb") as f:
            data, _ = self._span(f, path, 8)
        return struct.unpack_from("<I", data, 0)[0]


class Src:
    """One audio file: inside a BSA, a loose file under Data/, or a file in an --extra folder."""
    __slots__ = ("origin", "path", "bsa", "file", "_size")

    def __init__(self, origin: str, path: str, bsa: BSA | None = None, file: pathlib.Path | None = None):
        self.origin, self.path, self.bsa, self.file, self._size = origin, path, bsa, file, None

    def read(self) -> bytes:
        return self.bsa.read(self.path) if self.bsa else self.file.read_bytes()

    def head(self, n: int) -> bytes:
        if self.bsa:
            return self.bsa.head(self.path, n)
        with open(self.file, "rb") as f:
            return f.read(n)

    @property
    def size(self) -> int:
        if self._size is None:
            self._size = self.bsa.real_size(self.path) if self.bsa else self.file.stat().st_size
        return self._size

    @property
    def ext(self) -> str:
        return os.path.splitext(self.path)[1]

    @property
    def stem(self) -> str:
        return os.path.splitext(os.path.basename(self.path))[0]

    def label(self) -> str:
        return f"{self.origin}:{self.path}"


def strip_fuz(data: bytes) -> bytes:
    """A .fuz voice file is 'FUZE', version, lip-sync size, lip data, then the xWMA audio."""
    if data[:4] == b"FUZE":
        lip = struct.unpack_from("<I", data, 8)[0]
        return data[12 + lip:]
    return data


def audio_bytes(src: Src) -> tuple[bytes, str]:
    data = src.read()
    if data[:4] == b"FUZE":
        data = strip_fuz(data)
    if data[:4] == b"RIFF" and data[8:12] == b"XWMA":
        return data, ".xwm"
    if data[:4] == b"RIFF":
        return data, ".wav"
    return data, src.ext if src.ext != ".fuz" else ".xwm"


def duration(src: Src) -> float | None:
    """Length in seconds from the RIFF header. PCM wav: data size / byte rate. xWMA: the last entry of the 'dpds'
    table is the total decoded PCM byte count (exact); the header's average byte rate is only a rough fallback."""
    try:
        n = 4096
        while True:
            h = src.head(n)
            total = src.size
            if h[:4] == b"FUZE":
                lip = struct.unpack_from("<I", h, 8)[0]
                h = src.head(12 + lip + n)[12 + lip:]
                total -= 12 + lip
            if h[:4] != b"RIFF":
                return None
            p, rate, data_size, sr, ch, dpds, truncated = 12, None, None, 0, 0, None, False
            while p + 8 <= len(h):
                cid, csz = h[p:p + 4], struct.unpack_from("<I", h, p + 4)[0]
                if cid == b"fmt ":
                    ch, sr, rate = struct.unpack_from("<HII", h, p + 10)
                elif cid == b"dpds":
                    if p + 8 + csz > len(h):
                        truncated = True
                        break
                    dpds = struct.unpack_from("<I", h, p + 8 + csz - 4)[0] if csz >= 4 else None
                elif cid == b"data":
                    data_size = csz
                    break
                p += 8 + csz + (csz & 1)
            if truncated and n < (1 << 20):
                n *= 8
                continue
            break
        if h[8:12] == b"XWMA" and dpds and sr and ch:
            return dpds / (sr * ch * 2)
        if not rate:
            return None
        return (data_size if data_size else max(0, total - 44)) / rate
    except (OSError, struct.error, ValueError, KeyError):
        return None


def find_skyrim() -> pathlib.Path | None:
    names = ["Skyrim Special Edition", "Skyrim Anniversary Edition", "Skyrim", "SkyrimSE", "Skyrim VR"]
    roots = []
    if os.environ.get("SKYRIM_DIR"):
        return pathlib.Path(os.environ["SKYRIM_DIR"])
    steam_roots = [r"C:\Program Files (x86)\Steam", r"C:\Program Files\Steam", os.path.expanduser("~/.steam/steam"),
                   os.path.expanduser("~/.local/share/Steam"),
                   os.path.expanduser("~/Library/Application Support/Steam")]
    for d in "CDEFGHIJ":
        steam_roots += [rf"{d}:\Steam", rf"{d}:\SteamLibrary", rf"{d}:\Games\Steam", rf"{d}:\Games\SteamLibrary",
                        rf"{d}:\Program Files (x86)\Steam"]
    libs = []
    for r in steam_roots:
        vdf = pathlib.Path(r) / "steamapps" / "libraryfolders.vdf"
        if vdf.is_file():
            try:
                libs += [m.replace("\\\\", "\\") for m in re.findall(r'"path"\s+"([^"]+)"', vdf.read_text("utf-8", "ignore"))]
            except OSError:
                pass
        libs.append(r)
    for lib in dict.fromkeys(libs):
        for n in names:
            roots.append(pathlib.Path(lib) / "steamapps" / "common" / n)
    for d in "CDEFGH":
        roots += [pathlib.Path(rf"{d}:\GOG Games\Skyrim Anniversary Edition"), pathlib.Path(rf"{d}:\GOG Games\Skyrim Special Edition")]
    for r in roots:
        if has_game_audio(r):
            return r
    return None


def has_game_audio(root: pathlib.Path) -> bool:
    """A usable install has the master file and the sound archive (a stripped/modded folder may lack them)."""
    data = root / "Data"
    return (data / "Skyrim.esm").is_file() and any("sounds" in p.name.lower() for p in data.glob("*.bsa"))


def load_sources(skyrim: pathlib.Path | None, extras: list[pathlib.Path], log=print):
    """All audio files: BSAs (base game, then DLC, then Creation Club), loose Data/Music + Data/Sound, --extra folders.
    Later sources override earlier ones with the same path, like the game's own load order."""
    srcs: dict[str, Src] = {}
    archives: dict[str, BSA] = {}
    if skyrim:
        data = skyrim / "Data"
        bsas = [pathlib.Path(p) for p in glob.glob(str(data / "*.bsa")) if not SKIP_BSA.search(pathlib.Path(p).name)]

        def order(p: pathlib.Path):
            n = p.name.lower()
            return (0 if n.startswith("skyrim") else 1 if n.startswith("update") else
                    2 if n.startswith(("dawnguard", "hearthfires", "dragonborn")) else 3, n)

        for p in sorted(bsas, key=order):
            try:
                b = BSA(p)
            except (OSError, ValueError, struct.error) as e:
                log(f"  warning: skipping {p.name}: {e}")
                continue
            archives[b.name] = b
            for path in b.files:
                if path.endswith(AUDIO_EXT):
                    srcs[path] = Src(b.name, path, bsa=b)
        for sub in ("Music", "Sound", "music", "sound"):
            d = data / sub
            if d.is_dir():
                for f in d.rglob("*"):
                    if f.is_file() and f.suffix.lower() in AUDIO_EXT:
                        rel = f.relative_to(data).as_posix().lower()
                        srcs[rel] = Src("loose", rel, file=f)
    for root in extras:
        for f in sorted(root.rglob("*")):
            if f.is_file() and f.suffix.lower() in EXTRA_EXT:
                rel = f.relative_to(root).as_posix().lower()
                srcs["extra/" + rel] = Src("extra", rel, file=f)
    return srcs, archives


# =====================================================================================================  rule table
# (regex on the lower-case path with forward slashes, event or tuple of events, options). The first matching rule
# wins. An event of None drops the file. Options: min/max = duration limits in seconds, palette = only with --palettes,
# loose = only with --extra-is-music. Files from --extra folders are matched as "extra/<relative path>".

FX = r"^sound/fx/(?:dlc0\d/)?"
NPC = FX + r"npc/"
MAG = FX + r"(?:mag|mgk)/"
MUS = r"(?:^|/)mus_"


def R(pattern, events, **opts):
    return (re.compile(pattern), events if isinstance(events, tuple) or events is None else (events,), opts)


RULES = [
    # ------------------------------------------------------------------------------------------------- music
    R(r"_finale\.|_cg\.|(?:^|/)mus_(?:stinger|failure|dread_)", None),       # endings, duplicates, short stings
    R(MUS + r"levelup_?\d*\.", "music.level_up"),
    R(MUS + r"discover_genericlocation_\d+\.", ("music.discovery", "ui.discover")),
    R(MUS + r"(?:discover_\w+|reveal_\d+)\.", "music.discovery"),
    R(MUS + r"reward_\d+\.", "ui.quest_complete"),
    R(MUS + r"special_wordofpower_\d+\.", "wordwall.learn"),
    R(MUS + r"combat_boss(?:_02)?\.", "music.boss"),
    R(r"(?:^|/)(?:mus_combat_boss_03|bgssse\d+_mus_combat_boss_\d+)\.", "music.boss.battle"),
    R(MUS + r"combat_\d+\.", "music.combat"),
    R(MUS + r"sovngarde", "music.sovngarde"),
    R(MUS + r"palette_sovngarde_", "music.sovngarde"),
    R(MUS + r"(?:dungeon_dlc2apocrypha|dungeon_dlc_soulcairn)_\d+\.", "music.oblivion"),
    R(MUS + r"soulcairn_", "music.oblivion", palette=True),
    R(MUS + r"explore_dlc2solstheim_\d+\.", ("music.explore.morrowind", "music.explore_night.morrowind")),
    R(MUS + r"explore_dlc_falmervalley_\d+\.", "music.blackreach"),   # the glowing Forgotten Vale caverns
    R(MUS + r"dlc2boatarrival_\d+\.", ("music.explore.sea", "music.explore_night.sea")),  # the voyage to Solstheim
    R(MUS + r"castle_imperial", "music.town.imperial"),
    R(MUS + r"(?:town_day|village|tavern)_\d+\.|(?:^|/)mus_castle_stormcloaks", "music.town"),
    R(MUS + r"bard(?:lute|flute)_\d+", "music.town"),                    # the bards' instrumental tunes
    R(MUS + r"dungeon_(?:dlc_vampirecastle_|cave_|fort_|ice_)?\d+\.", "music.dungeon"),
    R(MUS + r"explore_night_\d+\.", "music.explore_night"),
    R(MUS + r"explore_(?:day|morning|dusk)_\d+\.", "music.explore"),
    # regional "palette" fragments (10-60 s pieces the game layers); only with --palettes
    R(MUS + r"palette_reach_day_", "music.explore.high_rock", palette=True),
    R(MUS + r"palette_reach_night_", "music.explore_night.high_rock", palette=True),
    R(MUS + r"palette_snowy_day_", "music.explore.atmora", palette=True),
    R(MUS + r"palette_snowy_night_", "music.explore_night.atmora", palette=True),
    R(MUS + r"palette_forestfall_day_", "music.explore.valenwood", palette=True),
    R(MUS + r"palette_forest_night_", "music.explore_night.valenwood", palette=True),
    # loose keyword rules for --extra folders that hold only music with free-form names (import_skyrim_ost.py)
    *[R(r"^extra/(?:[^/]*/)*[^/]*(?:" + kw + r")[^/]*$", ev, loose=True) for kw, ev in [
        (r"level_?up", "music.level_up"),
        (r"discover|reveal", "music.discovery"),
        (r"combat.*boss|boss|dragon|alduin", "music.boss"),
        (r"combat|battle|fight", "music.combat"),
        (r"sovngarde", "music.sovngarde"),
        (r"oblivion|apocrypha|soul_?cairn|daedric", "music.oblivion"),
        (r"solstheim", ("music.explore.morrowind", "music.explore_night.morrowind")),
        (r"blackreach|falmervalley", "music.blackreach"),
        (r"town|tavern|(?<![a-z])inn(?![a-z])|city|village|bard", "music.town"),
        (r"dwemer|dwarven", "music.dungeon.dwemer"),
        (r"dungeon|dread|cave|ruin|crypt|barrow", "music.dungeon"),
        (r"explore.*night|night", "music.explore_night"),
        (r"explore|dawn|dusk|morning|day|wilderness|travel", "music.explore"),
    ]],

    # ------------------------------------------------------------------------------------------------- ambient beds
    R(FX + r"amb/amb_windlight_lp|ambr_wind_(?:forestpine|reach|forestfall)_bed_lp|amb/wind/hillloops/",
      "ambient.bed.wind_light", min=3),
    R(r"amb_windloop_mountainsheavy_lp|ambr_wind_mountainheavy_lp|amb/wind/mountainloops/|ambr_wind_mountains_bed_lp",
      "ambient.bed.wind_strong", min=3),
    R(r"amb/wind/snowblowing/|amb_weather_snow_heavy_2dlp", "ambient.bed.blizzard", min=3),
    R(r"ambr_birds_forest_dayloop_|insects_cicadas_bed_lp|ambr_forestfall_insects_cicadas_(?:1morning|2day)_lp",
      "ambient.bed.forest_day", min=3),
    R(r"ambr_crickets_forest_night_\w_lp|ambr_crickets_forestpine_[ade]_\d+_lp|ambr_forest_crickets_\d+_lp",
      "ambient.bed.forest_night", min=3),
    R(r"ambr_tundra_wind_bed_lp|ambr_insects_forestpine_cicadas_2day_", "ambient.bed.plains_day", min=3),
    R(r"ambr_plains_crickets_\d+_lp|ambr_crickets_tundra_night_\d+_lp|ambr_cricket_reach_a_lp|ambr_crickets_solstheim_",
      "ambient.bed.plains_night", min=3),
    R(r"ambr_insects_marsh_[abd](?:_\d+)?_lp|ambr_wind_marsh_bed_lp", "ambient.bed.marsh_day", min=3),
    R(r"ambr_frogs_\w+_lp", ("ambient.bed.marsh_night", "ambient.frog"), min=3),
    R(r"amb_weather_ashstorm|ambr_wind_ashlands_bed_lp|amb/ashblowing/", "ambient.bed.ash_storm", min=3),
    R(r"coast/waves/amb_water_(?:exterior_coast_waves|coast_waves_distant)_\d+_lp|amb_water_coastrocks_\d+_2d_lp",
      "ambient.bed.coast_waves", min=3),
    R(r"ambt_shipwaves_interior_03_exterior_lp|ambr_wind_coast_bed_lp", "ambient.bed.open_ocean", min=3),
    R(r"amb_water_river_lp|amb_water_rapids_|amb_water_stream_01_lp|whiterunbrook/", "ambient.bed.river", min=3),
    R(r"ambt_dungeon_cave_generic|amb_water_dungeon_cave(?:small|large)|amb_dungeon_cave_drips_[abc]_2dlp",
      "ambient.bed.cave", min=3),
    R(r"amb_dungeon_cave_wind_2d_lp|amb_dungeon_howl_a_2dlp|ambt_dungeon_catacombs_generic|amb_dungeon_mine_windradius"
      r"|ambt_dungeon_caveice_generic", "ambient.bed.cave_deep", min=3),
    R(r"amb_water_dungeon_blackreach", "ambient.bed.glow_cave", min=3),
    R(r"ambt_windbed_apocrypha_lp|ambr_wind_soulcairn_bed_lp|amb_soulcairnwall_", "ambient.bed.oblivion", min=3),

    # ------------------------------------------------------------------------------------------------- ambient one-shots
    R(r"ambr_bird_owl_", "ambient.owl"),
    R(FX + r"ambr/birds/forestfall/3night/", "ambient.loon"),
    R(FX + r"ambr/birds/snowy/", "ambient.hawk"),
    R(FX + r"ambr/birds/marsh/(?:1morning|2day)/", "ambient.jungle_bird"),
    R(FX + r"ambr/birds/(?:forestpine|forestfall|riverwood/day)/(?!dayloop)", "ambient.bird_song", min=1.5),
    R(FX + r"amb/bonehawkflock/", "ambient.crow"),
    R(r"amb_harbor_seagulls_", "ambient.gull"),
    R(r"ambr_plains_cricket_\d|ambr_cricket_tundra_|ambr_crickets_forestpine_c_|katydid_single_", "ambient.cricket"),
    R(FX + r"ambr/insects/(?:tundra|marsh/c|reach)/|ambr_insect_solstheim_|ambr_mosquito_", "ambient.insect"),
    R(r"ambt_dungeon_cave_drip_|amb_water_drips_splash_lp", "ambient.cave_drip"),
    R(r"amb_rumbleshake_\d|ambr_dwemer_dungeon_rumble_|ambr_dungeon_catacombs_shaking_|amb_evernight_crypt_rumble_",
      "ambient.cave_rumble"),
    R(r"ambr_dungeon_cave_crumble_|amb_dustdrop_debris_|dark_dungeon_cave_crumble_|ambr_dungeon_ice_crumble_",
      "ambient.rock_fall"),
    R(FX + r"ambr/wind/.*gust|ambr_wind_generic_|ambr_dungeon_cave_windgust_", "ambient.wind_gust"),
    R(r"ambr_dungeon_fort_creaking_|amb_ropebridgecreak_|ambr_interior_woodrattle_", "ambient.tree_creak"),
    R(r"ambr_tundravolcanic_rumble|ambr_ashlands_rumble_|amb/volcano/", "ambient.volcano"),
    R(r"coastwaves/amb_water_exterior_coast_waves_hi_|coastwavesdocks/", "ambient.wave_crash"),
    R(r"ambr_apocrypharandom|amb_steam_soulcrack_whispers", "ambient.oblivion_wail"),
    R(r"blackreach_chimes_|templeofkynareth_chimes_", "ambient.glow_chime"),
    R(NPC + r"dragon/flightroar/(?:distant/|npc_dragon_flightroar_distant|obstructed/)", "ambient.distant_dragon"),

    # ------------------------------------------------------------------------------------------------- creatures
    R(NPC + r"dragon/(?:flightroar/npc_dragon_flightroar_0\d\.|killroar/)", "entity.dragon.roar"),
    R(NPC + r"dragon/(?:idle|attack|attackbite|killmove)/", "entity.dragon.growl"),
    R(NPC + r"dragon/(?:wing/flap|takeofffx|flyby|attackwing)/", "entity.dragon.wings"),
    R(MAG + r"flamesdragon/mag_flamesdragon_fire_|voc/shout/fx/firebreath/", "entity.dragon.breath_fire"),
    R(MAG + r"destruction/frostbitedragon/mag_destruction_frostbitedragon_fire|voc/shout/fx/frostbreath/",
      "entity.dragon.breath_frost"),
    R(NPC + r"dragon/(?:land|landcrash)/(?!.*_2d\.)", "entity.dragon.land"),
    R(NPC + r"dragon/injured/", "entity.dragon.hurt"),
    R(NPC + r"dragon/(?:death/npc_dragon_death_\d|npc_dragon_alduin_death_)", "entity.dragon.death"),
    R(r"npc_dragon_deathsequence_wind", "magic.dragon_soul"),
    R(NPC + r"draugr/(?:consciouslp|aware)/", "entity.draugr.ambient"),
    R(NPC + r"draugr/attack/", "entity.draugr.attack"),
    R(NPC + r"draugr/injured/", "entity.draugr.hurt"),
    R(NPC + r"draugr/death/", "entity.draugr.death"),
    R(NPC + r"skeever/(?:breathelp|attack)/", "entity.skeever.ambient"),
    R(NPC + r"skeever/injured/", "entity.skeever.hurt"),
    R(NPC + r"skeever/death/", "entity.skeever.death"),
    R(NPC + r"troll/(?:idle/|npc_troll_breathe|npc_troll_idlecombat_flail)", "entity.troll.ambient"),
    R(NPC + r"troll/npc_troll_idlecombat_(?:roar|intimidate)", "entity.troll.roar"),
    R(NPC + r"troll/injured/", "entity.troll.hurt"),
    R(NPC + r"troll/death/", "entity.troll.death"),
    R(NPC + r"giant/(?:npc_giant_conscious|npc_giant_idle|attackvocal/)", "entity.giant.ambient"),
    R(NPC + r"giant/(?:attackstomp|foot)/", "entity.giant.stomp"),
    R(NPC + r"giant/injured/", "entity.giant.hurt"),
    R(NPC + r"giant/death/", "entity.giant.death"),
    R(NPC + r"mammoth/npc_mammoth_(?:aggrowarning|conscious)", "entity.mammoth.ambient"),
    R(NPC + r"mammoth/injured/", "entity.mammoth.hurt"),
    R(NPC + r"mammoth/death/", "entity.mammoth.death"),
    R(NPC + r"spiderfrostbite/(?:npc_spiderfrostbite_breathe|npc_spiderfrostbite_holeexit|spit/)",
      "entity.frostbite_spider.ambient"),
    R(NPC + r"spiderfrostbite/injured/", "entity.frostbite_spider.hurt"),
    R(NPC + r"spiderfrostbite/death/", "entity.frostbite_spider.death"),
    R(NPC + r"wolf/howl/", "entity.wolf.howl"),
    R(NPC + r"wolf/(?:aggrowarning|bark)/", "entity.wolf.growl"),
    R(NPC + r"bear/npc_bear_(?:conscious|idlerooting)", "entity.bear.ambient"),
    R(NPC + r"bear/npc_bear_aggrowarning", "entity.bear.roar"),
    R(NPC + r"bear/injured/", "entity.bear.hurt"),
    R(NPC + r"bear/death/", "entity.bear.death"),
    R(NPC + r"sabrecat/npc_sabrecat_(?:aggrowarning|idlecombat|breathe)", "entity.sabre_cat.ambient"),
    R(NPC + r"sabrecat/injured/", "entity.sabre_cat.hurt"),
    R(NPC + r"sabrecat/death/", "entity.sabre_cat.death"),
    R(NPC + r"falmer/(?:aggrowarning/|npc_falmer_idle|npc_falmer_breathe)", "entity.falmer.ambient"),
    R(NPC + r"falmer/injured/", "entity.falmer.hurt"),
    R(NPC + r"falmer/death/", "entity.falmer.death"),
    R(MAG + r"dwarvensteam/|amb/dungeon/dwemer/steam/|dwarvencenturion/npc_dwarvencenturion_attackbreath",
      "entity.dwarven.steam"),
    R(NPC + r"(?:dwarvenspider/npc_dwarvenspider_idle|dwarvensphere/(?:npc_dwarvensphere_(?:equip|scan)|open/|close/)"
      r"|dwarvencenturion/(?:walk/|npc_dwarvencenturion_(?:fullscan|stall)))", "entity.dwarven.clank"),
    R(NPC + r"dwarven(?:spider|sphere|centurion)/injured/", "entity.dwarven.hurt"),
    R(NPC + r"dwarven(?:spider|sphere|centurion)/death/", "entity.dwarven.death"),
    R(NPC + r"(?:wisp/npc_wisp_(?:conscious|ambush\.)|witchlight/npc_witchlight_conscious)", "entity.wisp.ambient"),
    R(NPC + r"spriggan/(?:npc_spriggan_conscious|attack/)", "entity.spriggan.ambient"),
    R(NPC + r"spriggan/injured/", "entity.spriggan.hurt"),
    R(NPC + r"spriggan/death/", "entity.spriggan.death"),
    R(NPC + r"hagraven/npc_hagraven_(?:shriek|warn|conscious)", "entity.hagraven.ambient"),
    R(NPC + r"hagraven/injured/", "entity.hagraven.hurt"),
    R(NPC + r"hagraven/death/", "entity.hagraven.death"),
    R(NPC + r"horker/(?:npc_horker_aggrowarning|attackpowerstanding/)", "entity.horker.ambient"),
    R(NPC + r"horker/injured/", "entity.horker.hurt"),
    R(NPC + r"horker/death/", "entity.horker.death"),
    R(NPC + r"mudcrab/(?:idle/|npc_mudcrab_(?:breathe|dig))", "entity.mudcrab.ambient"),
    R(NPC + r"mudcrab/injured/", "entity.mudcrab.hurt"),
    R(NPC + r"mudcrab/death/", "entity.mudcrab.death"),
    R(NPC + r"icewraith/(?:npc_icewraith_breathe|attackwindup/)", "entity.ice_wraith.ambient"),
    R(NPC + r"icewraith/npc_icewraith_injured", "entity.ice_wraith.hurt"),
    R(NPC + r"icewraith/death/", "entity.ice_wraith.death"),

    # ------------------------------------------------------------------------------------------------- magic
    R(MAG + r".*(?:mgk_firebolt_03_fire|mag_firebolt_fire_2d|mgk_firebolt_projectile_)", "spell.fire"),
    R(MAG + r".*(?:mag_flames_fire_lpm|mag_cloakfire_lp)", "spell.fire_stream"),
    R(MAG + r".*(?:mag_icicle_fire|mag_frostbite_fire_2d)", "spell.frost"),
    R(MAG + r".*(?:mag_frostbite_fire_lpm|mag_cloakfrost_lp)", "spell.frost_stream"),
    R(MAG + r".*mag_shock_ff_fire_", "spell.shock"),
    R(MAG + r".*mag_shock_concentration_(?:fire_lpm|bolt_)", "spell.shock_stream"),
    R(MAG + r".*mag_restoration_(?:ff_fire|concentration_fire_2d|fire_potion)", "spell.heal"),
    R(MAG + r".*mag_conjure_(?:portal_open_2d|fire|portal\.|boundweapon_appear)", "spell.conjure"),
    R(MAG + r".*mag_illusion_(?:release|charm_on|invisibility_in_mono|nighteye_on)", "spell.illusion"),
    R(MAG + r".*(?:mag_alteration_(?:release|ironflesh_fire|waterbreathing_fire)|mag_light_fire)", "spell.alteration"),
    R(MAG + r".*(?:mag_firebolt_fail|mag_failshout_)", "spell.fizzle"),
    R(MAG + r".*(?:mag_flames_impact_|mgk_firebolt_impact_|mag_destruction_fireball_explosion_0)", "spell.impact_fire"),
    R(MAG + r".*(?:mag_frostbite_impact_|mag_icicle_impact)", "spell.impact_frost"),
    R(MAG + r".*mag_shock_impact_", "spell.impact_shock"),
    R(MAG + r"(?!.*(?:dragon|stendarr|whirlwind)).*_charge(?:_\d+)?\.", "spell.charge"),
    R(MAG + r".*mag_altars_blessing_", "magic.shrine_pray"),
    R(r"mag_standingstone(?:activate|blessing)_|obj_guardianstoneglow", "magic.standing_stone"),
    R(r"itm_nirnroot_", "world.nirnroot_hum"),

    # ------------------------------------------------------------------------------------------------- shout / word wall
    R(FX + r"voc/shout/(?:projectile/push/voc_shout_projectile_push|impact/push/voc_shoutpower_impact_push_)",
      "shout.generic"),
    R(r"qst_wordwall_whisper", "wordwall.chant"),
    R(FX + r"ui/ui_shoutlearn_single_", "wordwall.learn"),

    # ------------------------------------------------------------------------------------------------- UI
    R(FX + r"ui/(?:journal/open|blade/open)/", "ui.menu_open"),
    R(FX + r"ui/(?:journal/close|blade/close)/", "ui.menu_close"),
    R(FX + r"ui/(?:ui_menu_ok|ui_menu_focus|ui_menu_prevnext|focus/ui_menu_focus_)", "ui.menu_click"),
    R(FX + r"ui/ui_menu_cancel", "ui.menu_back"),
    R(FX + r"ui/ui_(?:quest_update|quest_new|objective_new_)", "ui.quest_update"),
    R(FX + r"ui/ui_skill_increase", "ui.skill_up"),
    R(FX + r"ui/ui_levelup", "ui.level_up"),
    R(FX + r"ui/lockpicking/(?:cylinderturn|pickmovement)/", "ui.lockpick_turn"),
    R(FX + r"ui/lockpicking/(?:cylindersqueak/|picktensionlpm/|cylinderstop/)", "ui.lockpick_strain"),
    R(FX + r"ui/lockpicking/pickbreak/", "ui.lockpick_break"),
    R(FX + r"ui/lockpicking/unlock/", "ui.lockpick_unlock"),
]

# Shout id -> regex of its effect sound (voc/shout/fx/...). "{l}" is replaced by a/b/c for one/two/three words.
SHOUT_FX = {
    "unrelenting_force": r"voc/shout/fx/unrelentingforce/thunder/voc_shout_fx_unrelentingforce_{l}_01\.",
    "fire_breath": r"voc/shout/fx/firebreath/voc_shout_fx_firebreath_{l}_01\.",
    "frost_breath": r"voc/shout/fx/frostbreath/voc_shout_fx_frostbreath_{l}_01\.",
    "disarm": r"voc/shout/fx/disarm/voc_shout_fx_disarm_{l}_01\.",
    "aura_whisper": r"voc/shout/fx/aurawhisper/voc_shout_fx_aurawhisper_a\.",
    "animal_allegiance": r"voc/shout/fx/animalallegiance/voc_shout_fx_animalallegiance_01\.",
    "become_ethereal": r"voc/shout/fx/becomeethereal/in/mag_shout_becomeethereal_in_01\.",
    "dragonrend": r"voc/shout/fx/dragonrend/release/mag_shout_dragonrend_release_01\.",
    "elemental_fury": r"voc/shout/fx/elementalfury/mag_shout_elementalfury_on\.",
    "slow_time": r"voc/shout/fx/slowtime/mag_shout_slowtime_in\.",
    "whirlwind_sprint": r"voc/shout/fx/sprint/mag_shout_sprint_fire\.",
    "storm_call": r"voc/shout/impact/stormcall/voc_shout_impact_stormcall_far_01\.",
}


# =====================================================================================================  shout voices

def _subrecords(data: bytes):
    p, n, big = 0, len(data), None
    while p + 6 <= n:
        t, sz = struct.unpack_from("<4sH", data, p)
        p += 6
        if big is not None:
            sz, big = big, None
        if t == b"XXXX":
            big = struct.unpack_from("<I", data, p)[0]
            p += sz
            continue
        yield t, data[p:p + sz]
        p += sz


def _parse_lstrings(data: bytes, length_prefixed: bool) -> dict[int, str]:
    count, _ = struct.unpack_from("<II", data, 0)
    base, out = 8 + count * 8, {}
    for i in range(count):
        sid, off = struct.unpack_from("<II", data, 8 + i * 8)
        p = base + off
        if length_prefixed:
            ln = struct.unpack_from("<I", data, p)[0]
            s = data[p + 4:p + 4 + ln].rstrip(b"\0")
        else:
            s = data[p:data.index(b"\0", p)]
        try:
            out[sid] = s.decode("utf-8")
        except UnicodeDecodeError:
            out[sid] = s.decode("cp1252")
    return out


def load_ilstrings(data_dir: pathlib.Path, plugin: str, archives: dict[str, BSA]) -> dict[int, str]:
    """Dialogue strings of a localized plugin (SSE: in Skyrim - Interface.bsa; Oldrim: loose Data/Strings)."""
    stem = plugin.rsplit(".", 1)[0].lower()
    pref = [f"strings/{stem}_english.ilstrings"]
    for b in archives.values():
        names = pref + sorted(n for n in b.files if n.startswith(f"strings/{stem}_") and n.endswith(".ilstrings"))
        for n in names:
            if n in b.files:
                return _parse_lstrings(b.read(n), True)
    loose = sorted((data_dir / "Strings").glob(f"{stem}_*.ilstrings"), key=lambda p: "english" not in p.name.lower()) \
        if (data_dir / "Strings").is_dir() else []
    if not loose and (data_dir / "Strings").is_dir():
        loose = [p for p in (data_dir / "Strings").iterdir() if p.name.lower().startswith(stem + "_")
                 and p.name.lower().endswith(".ilstrings")]
    return _parse_lstrings(loose[0].read_bytes(), True) if loose else {}


def shout_infos(data_dir: pathlib.Path, archives: dict[str, BSA], plugin: str = "Skyrim.esm"):
    """{info form id (low 24 bits): (topic, text)} for the player's Thu'um topics Shout01a/01b/02/03 in `plugin`."""
    path = data_dir / plugin
    if not path.is_file():
        return {}
    out = {}
    with open(path, "rb") as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as mm:
        t, size, flags = struct.unpack_from("<4sII", mm, 0)
        if t != b"TES4":
            return {}
        localized = bool(flags & 0x80)
        strings = load_ilstrings(data_dir, plugin, archives) if localized else {}
        p, n = 24 + size, len(mm)
        while p < n:
            _, gsize, label = struct.unpack_from("<4sI4s", mm, p)
            if label != b"DIAL":
                p += gsize
                continue
            q, end, topic = p + 24, p + gsize, None
            while q < end:
                if mm[q:q + 4] == b"GRUP":
                    gs = struct.unpack_from("<I", mm, q + 4)[0]
                    if topic in ("shout01a", "shout01b", "shout02", "shout03"):
                        r = q + 24
                        while r < q + gs:
                            rt, rs, rflags, fid = struct.unpack_from("<4sIII", mm, r)
                            data = mm[r + 24:r + 24 + rs]
                            if rflags & 0x00040000:
                                data = zlib.decompress(data[4:])
                            if rt == b"INFO":
                                for st, sd in _subrecords(data):
                                    if st == b"NAM1":
                                        text = strings.get(struct.unpack_from("<I", sd)[0], "") if localized \
                                            else sd.rstrip(b"\0").decode("cp1252", "ignore")
                                        out[fid & 0xFFFFFF] = (topic, text)
                                        break
                            r += 24 + rs
                    q += gs
                else:
                    rt, rs, rflags = struct.unpack_from("<4sII", mm, q)
                    data = mm[q + 24:q + 24 + rs]
                    if rflags & 0x00040000:
                        data = zlib.decompress(data[4:])
                    topic = None
                    for st, sd in _subrecords(data):
                        if st == b"EDID":
                            topic = sd.rstrip(b"\0").decode("cp1252").lower()
                            break
                    q += 24 + rs
            p += gsize
    return out


def _norm(word: str) -> str:
    w = re.sub(r"[^a-z]", "", word.lower())
    return w[:-1] if len(w) > 2 and w.endswith("h") else w   # "Dah" is spelled "Da" in the game's subtitles


def plan_shouts(srcs: dict[str, Src], data_dir: pathlib.Path | None, archives: dict[str, BSA], voice: str,
                with_fx: bool, log=print):
    """[(event, [segment Src...], overlay Src | None)] for shout.<id>.<k>, plus notes for the report."""
    notes = []
    if data_dir is None:
        return [], ["shout voices: no Skyrim install (needs Skyrim.esm and the voice archive)"]
    try:
        infos = shout_infos(data_dir, archives)
    except (OSError, ValueError, struct.error, zlib.error) as e:
        return [], [f"shout voices: could not read Skyrim.esm ({e})"]
    if not infos:
        return [], ["shout voices: no Shout01a/01b/02/03 dialogue found in Skyrim.esm"]
    rx = re.compile(r"^sound/voice/skyrim\.esm/" + re.escape(voice.lower()) +
                    r"/voicepowers_(shout0[123][ab]?)_([0-9a-f]{8})_\d+\.(?:fuz|xwm|wav)$")
    # topic -> normalized text -> Src
    by_topic: dict[str, dict[str, Src]] = {}
    for path, s in sorted(srcs.items()):
        m = rx.match(path)
        if not m:
            continue
        info = infos.get(int(m.group(2), 16) & 0xFFFFFF)
        if not info:
            continue
        topic, text = info
        key = " ".join(_norm(w) for w in re.split(r"[^A-Za-z']+", text) if w)
        by_topic.setdefault(topic, {}).setdefault(key, s)
    if not by_topic:
        voices = sorted({p.split("/")[3] for p in srcs if p.startswith("sound/voice/skyrim.esm/")
                         and "/voicepowers_shout" in p})
        return [], [f"shout voices: no Thu'um files for voice type '{voice}'"
                    + (f"; available: {', '.join(voices)}" if voices else "")]
    fx_paths = {}
    if with_fx:
        cands = sorted(p for p in srcs if "voc/shout" in p and p.startswith("sound/fx/"))
        for sid, pat in SHOUT_FX.items():
            for k, letter in ((1, "a"), (2, "b"), (3, "c")):
                for lt in (letter, "a"):
                    r = re.compile(FX + pat.replace("{l}", lt))
                    hit = next((p for p in cands if r.search(p)), None)
                    if hit:
                        fx_paths[(sid, k)] = srcs[hit]
                        break
    plan = []
    for sid, words in sound_catalog.SHOUTS.items():
        w = [_norm(x) for x in words]
        t1a, t1b, t2, t3 = (by_topic.get(t, {}) for t in ("shout01a", "shout01b", "shout02", "shout03"))
        one = t1a.get(w[0])
        lead = t1b.get(w[0]) or one
        two = t2.get(w[1])
        three = t3.get(f"{w[1]} {w[2]}")
        recipes = {1: [one] if one else None,
                   2: [lead, two] if lead and two else None,
                   3: [lead, three] if lead and three else None}
        for k, segs in recipes.items():
            if segs:
                plan.append((f"shout.{sid}.{k}", segs, fx_paths.get((sid, k))))
            else:
                notes.append(f"shout.{sid}.{k}: no '{' '.join(words[:k])}' voice line for {voice}")
    return plan, notes


# =====================================================================================================  planning

class Item:
    """One output .ogg: segments concatenated (+ an overlay mixed in at the start of the last segment)."""
    __slots__ = ("segments", "overlay", "stereo", "music", "events", "name", "durs")

    def __init__(self, segments, overlay, stereo, music):
        self.segments, self.overlay, self.stereo, self.music = list(segments), overlay, stereo, music
        self.events, self.name, self.durs = [], None, None

    def key(self):
        return (tuple(s.label() for s in self.segments), self.overlay.label() if self.overlay else None, self.stereo)

    def describe(self) -> str:
        s = " + ".join(seg.label() for seg in self.segments)
        return s + (f"  (+fx {self.overlay.path.rsplit('/', 1)[-1]})" if self.overlay else "")


def group_of(event: str, cat: str) -> str:
    if cat == "music":
        return "music"
    if cat in ("bed", "ambient"):
        return "ambient"
    if re.match(r"shout\.[a-z_]+\.[123]$", event):
        return "voice"
    return "sfx"


def spread(items: list, n: int) -> list:
    """Pick n items spread evenly over the (sorted) list, so a capped event still gets variety."""
    if n <= 0 or len(items) <= n:
        return items
    step = len(items) / n
    return [items[int(i * step)] for i in range(n)]


def build_plan(srcs, data_dir, archives, args, log=print):
    events = {e.name: e for e in sound_catalog.EVENTS}
    only = set(args.only) if args.only else None
    user_rules = []
    for m in args.map:
        if "=" not in m:
            raise SystemExit(f"error: --map needs REGEX=EVENT, got {m!r}")
        pattern, ev = m.rsplit("=", 1)
        if ev not in events and ev != "none":
            raise SystemExit(f"error: --map: unknown event {ev!r} (see: py -3 tools/music/sound_catalog.py --list)")
        user_rules.append(R(pattern.lower(), None if ev == "none" else ev))
    rules = user_rules + [r for r in RULES if (args.palettes or not r[2].get("palette"))
                          and (args.extra_is_music or not r[2].get("loose"))]
    for _, evs, _ in rules:
        for ev in evs or ():
            if ev not in events:
                raise SystemExit(f"internal error: rule event {ev!r} is not in sound_catalog.py")

    matched: dict[str, list[Src]] = {}
    unmatched_music = []
    dur_cache: dict[str, float | None] = {}
    for path in sorted(srcs):
        if path.startswith("sound/voice/"):
            continue
        for rx, evs, opts in rules:
            if not rx.search(path):
                continue
            if evs is None:
                break
            if "min" in opts or "max" in opts:
                d = dur_cache.setdefault(path, duration(srcs[path]))
                if d is not None and (d < opts.get("min", 0) or d > opts.get("max", 1e9)):
                    continue
            for ev in evs:
                matched.setdefault(ev, []).append(srcs[path])
            break
        else:
            if path.startswith("extra/") or (re.search(r"(?:^|/)mus_", path) and "/palette/" not in path):
                unmatched_music.append(path)

    items: dict[tuple, Item] = {}
    per_event: dict[str, list[Item]] = {}

    def add(ev, segs, overlay=None):
        e = events[ev]
        stereo = e.category in ("music", "bed")
        it = Item(segs, overlay, stereo, e.category == "music")
        it = items.setdefault(it.key(), it)
        it.events.append(ev)
        per_event.setdefault(ev, []).append(it)

    for ev, lst in matched.items():
        g = group_of(ev, events[ev].category)
        if only and g not in only:
            continue
        cap = 0 if events[ev].category == "music" else args.max_per_event
        for s in spread(lst, cap):
            add(ev, [s])

    notes = []
    if not only or "voice" in only:
        plan, notes = plan_shouts(srcs, data_dir, archives, args.voice, not args.no_shout_fx, log)
        for ev, segs, ov in plan:
            add(ev, segs, ov)
    return events, per_event, list(items.values()), unmatched_music, notes


def print_plan(events, per_event, unmatched_music, notes, only):
    print("\n=== mapping (event <- source files) ===")
    for e in sound_catalog.EVENTS:
        its = per_event.get(e.name)
        if not its:
            continue
        print(f"{e.name}  [{len(its)}]")
        for it in its:
            print(f"    {it.describe()}")
    print("\n=== Skycraft events that keep Skycraft's own sounds (no Skyrim equivalent found / filtered) ===")
    cats: dict[str, list[int]] = {}
    for e in sound_catalog.EVENTS:
        g = group_of(e.name, e.category)
        nested = all(f.startswith("@") for f in e.files)
        if only and g not in only:
            continue
        c = cats.setdefault(e.category, [0, 0])
        if nested:
            continue  # pure aliases (e.g. shout.fus_ro_dah -> shout.unrelenting_force.3) follow their target
        c[1] += 1
        if per_event.get(e.name):
            c[0] += 1
        else:
            print(f"    {e.name}")
    for n in notes:
        print(f"  note: {n}")
    if unmatched_music and (not only or "music" in only):
        print("\n=== music / --extra files not used (add --map 'REGEX=EVENT' to assign) ===")
        for p in unmatched_music:
            print(f"    {p}")
    print("\n=== coverage (events overridden / events in the catalogue, aliases excluded) ===")
    tm = tt = 0
    for c, (m, t) in cats.items():
        print(f"    {c:9s} {m:4d} / {t}")
        tm += m
        tt += t
    print(f"    {'total':9s} {tm:4d} / {tt}")


# =====================================================================================================  conversion

def inside(path: pathlib.Path, root: pathlib.Path) -> bool:
    p = os.path.normcase(os.path.realpath(path))
    r = os.path.normcase(os.path.realpath(root))
    return p == r or p.startswith(r.rstrip("\\/") + os.sep)


def protected_roots() -> list[pathlib.Path]:
    """The repository this script lives in, plus any enclosing checkout (e.g. when run from a worktree)."""
    return [REPO] + [a for a in REPO.parents if (a / ".git").exists()]


def refuse_repo(path: pathlib.Path, what: str):
    hit = next((r for r in protected_roots() if inside(path, r)), None)
    if hit is not None:
        raise SystemExit(f"error: refusing to write {what} inside the Skycraft repository ({hit}).\n"
                         "The pack contains copyrighted game audio and must never end up in the mod's sources: "
                         "write it to your Minecraft resourcepacks folder or anywhere outside the repository.")


def find_ffmpeg() -> str:
    try:
        import audiolib
        return audiolib.find_ffmpeg()
    except ImportError:
        ff = os.environ.get("FFMPEG") or shutil.which("ffmpeg")
        if not ff:
            raise SystemExit("ffmpeg with libvorbis not found: install it, put it on PATH, or set FFMPEG=<path>")
        return ff


def convert(ffmpeg: str, it: Item, tmp: pathlib.Path, out: pathlib.Path, q_music: float, q_sfx: float):
    """Decode the item's game files with ffmpeg and encode one Ogg Vorbis file."""
    tag = hashlib.sha1(repr(it.key()).encode()).hexdigest()[:12]
    inputs = []
    try:
        for i, s in enumerate(it.segments + ([it.overlay] if it.overlay else [])):
            data, ext = audio_bytes(s)
            f = tmp / f"{tag}_{i}{ext}"
            f.write_bytes(data)
            inputs.append(f)
        ch = "2" if it.stereo else "1"
        layout = "stereo" if it.stereo else "mono"
        q = str(q_music if it.music else q_sfx)
        enc = ["-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", q, str(out)]
        base = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-nostdin"]
        if len(inputs) == 1:
            # asetpts: xWMA decodes with a non-zero first timestamp; start the Ogg stream at 0
            cmd = base + ["-i", str(inputs[0]), "-vn", "-af", "asetpts=PTS-STARTPTS", "-ac", ch]
            cmd += (["-ar", "44100"] if it.music else []) + enc
            subprocess.run(cmd, check=True, capture_output=True)
            return
        # several inputs: decode each to PCM first (exact lengths, clean timestamps), then concatenate / mix
        pcm, lengths = [], []
        for f in inputs:
            w = f.with_suffix(".pcm.wav")
            subprocess.run(base + ["-i", str(f), "-vn", "-af", "asetpts=PTS-STARTPTS", "-ar", "44100", "-ac", ch,
                                   "-c:a", "pcm_s16le", str(w)], check=True, capture_output=True)
            pcm.append(w)
            with wave.open(str(w), "rb") as wf:
                lengths.append(wf.getnframes() / wf.getframerate())
        inputs += pcm
        nseg = len(it.segments)
        fmt = f"aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts={layout}"
        graph = [f"[{i}:a]{fmt}[s{i}]" for i in range(nseg)]
        graph.append("".join(f"[s{i}]" for i in range(nseg)) + f"concat=n={nseg}:v=0:a=1[cat]")
        cmd_in = []
        for f in pcm:
            cmd_in += ["-i", str(f)]
        if it.overlay:
            delay = int(sum(lengths[:nseg - 1]) * 1000)   # the effect starts with the last word
            graph.append(f"[{nseg}:a]{fmt},adelay={delay}[ov]")
            mixed = graph + ["[cat][ov]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0,"
                             "alimiter=limit=0.97:level=false[out]"]
            try:
                subprocess.run(base + cmd_in + ["-filter_complex", ";".join(mixed), "-map", "[out]"] + enc,
                               check=True, capture_output=True)
                return
            except subprocess.CalledProcessError:
                pass  # older ffmpeg (no amix normalize / alimiter): fall back to the voice alone
            cmd_in = cmd_in[:-2]
        subprocess.run(base + cmd_in + ["-filter_complex", ";".join(graph), "-map", "[cat]"] + enc,
                       check=True, capture_output=True)
    finally:
        for f in inputs:
            try:
                f.unlink()
            except OSError:
                pass


def output_zip(path: pathlib.Path) -> pathlib.Path:
    if path.suffix.lower() == ".zip":
        return path
    return path / ZIP_NAME


def default_resourcepacks() -> pathlib.Path | None:
    cands = []
    if os.environ.get("APPDATA"):
        cands.append(pathlib.Path(os.environ["APPDATA"]) / ".minecraft" / "resourcepacks")
    cands += [pathlib.Path.home() / ".minecraft" / "resourcepacks",
              pathlib.Path.home() / "Library" / "Application Support" / "minecraft" / "resourcepacks"]
    return next((c for c in cands if c.is_dir()), None)


def run(args) -> int:
    t0 = time.time()
    skyrim = None
    if not args.no_skyrim:
        skyrim = pathlib.Path(args.skyrim) if args.skyrim else find_skyrim()
        if skyrim is None or not (skyrim / "Data").is_dir():
            print("error: Skyrim not found" + (f" at {args.skyrim}" if args.skyrim else "") +
                  ". Pass --skyrim <install folder> (the one containing Data/), or --no-skyrim with --extra <folder>.",
                  file=sys.stderr)
            return 2
        if not has_game_audio(skyrim):
            print(f"warning: {skyrim / 'Data'} has no Skyrim.esm or no '* - Sounds.bsa': the mapping will be incomplete")
    extras =[pathlib.Path(e) for e in args.extra]
    for e in extras:
        if not e.is_dir():
            print(f"error: --extra {e} is not a folder", file=sys.stderr)
            return 2
    if skyrim is None and not extras:
        print("error: nothing to read (give --skyrim or --extra)", file=sys.stderr)
        return 2
    print(f"reading {skyrim or ''}{' + ' if skyrim and extras else ''}{', '.join(map(str, extras))}")
    srcs, archives = load_sources(skyrim, extras)
    print(f"  {len(srcs)} audio files in {len(archives)} archives{' + loose/extra files' if extras else ''}")

    if args.list_archive is not None:
        rx = re.compile(args.list_archive or ".", re.I)
        n = 0
        for p in sorted(srcs):
            if rx.search(p):
                s = srcs[p]
                print(f"{s.origin:32s} {p}  ({s.size // 1024} KB)")
                n += 1
        print(f"{n} files")
        return 0

    out_zip = None
    if not args.dry_run:
        out = args.out or args.out_pos
        if not out:
            rp = default_resourcepacks()
            if rp is None:
                print("error: give --out <resourcepacks folder or .zip path>", file=sys.stderr)
                return 2
            out = str(rp)
        out_zip = output_zip(pathlib.Path(out).expanduser())
        refuse_repo(out_zip, "the resource pack")
    elif args.out or args.out_pos:
        refuse_repo(output_zip(pathlib.Path(args.out or args.out_pos).expanduser()), "the resource pack")

    events, per_event, items, unmatched_music, notes = build_plan(srcs, skyrim / "Data" if skyrim else None,
                                                                   archives, args)
    print_plan(events, per_event, unmatched_music, notes, set(args.only) if args.only else None)
    print(f"\n{len(items)} files to convert for {len(per_event)} events (planned in {time.time() - t0:.1f} s)")
    if args.dry_run:
        print("dry run: nothing converted or written.")
        return 0
    if not items:
        print("error: nothing to put in the pack", file=sys.stderr)
        return 1
    if args.limit:
        # quick test: keep N files, round-robin over the groups, most complex recipe (concat + fx) first
        cat = {e.name: e.category for e in sound_catalog.EVENTS}
        groups: dict[str, list[Item]] = {}
        for it in sorted(items, key=lambda i: (-len(i.segments), i.overlay is None)):
            groups.setdefault(group_of(it.events[0], cat[it.events[0]]), []).append(it)
        keep = []
        while len(keep) < args.limit and any(groups.values()):
            for g in list(groups):
                if groups[g] and len(keep) < args.limit:
                    keep.append(groups[g].pop(0))
        items = keep
        per_event = {ev: [i for i in lst if i in keep] for ev, lst in per_event.items()}
        per_event = {ev: lst for ev, lst in per_event.items() if lst}
        print(f"--limit {args.limit}: converting only {len(items)} file(s) for {len(per_event)} event(s)")

    ffmpeg = find_ffmpeg()
    used: set = set()
    for ev in [e.name for e in sound_catalog.EVENTS]:
        for i, it in enumerate(per_event.get(ev, []), 1):
            if it.name is None:
                base = "personal/" + ev.replace(".", "/")
                name, n = f"{base}/{i}", i
                while name in used:
                    n += 1
                    name = f"{base}/{n}"
                used.add(name)
                it.name = name

    tmp_root = pathlib.Path(tempfile.mkdtemp(prefix="skycraft_skyrim_audio_"))
    refuse_repo(tmp_root, "temporary files")
    lock = threading.Lock()
    done, failed = [0], []
    ok: set = set()
    try:
        work = tmp_root / "work"
        oggs = tmp_root / "ogg"
        work.mkdir()

        def job(it: Item):
            dst = oggs / (it.name + ".ogg")
            dst.parent.mkdir(parents=True, exist_ok=True)
            err = None
            try:
                convert(ffmpeg, it, work, dst, args.quality, args.sfx_quality)
                if not dst.is_file() or dst.stat().st_size < 64:
                    err = "empty output"
            except subprocess.CalledProcessError as e:
                err = (e.stderr or b"").decode("utf-8", "ignore").strip().splitlines()[-1:] or ["ffmpeg failed"]
                err = err[0]
            except Exception as e:  # noqa: BLE001 - a broken file must not stop the whole pack
                err = f"{type(e).__name__}: {e}"
            with lock:
                done[0] += 1
                if err:
                    failed.append((it, err))
                    print(f"[{done[0]}/{len(items)}] FAILED {it.describe()}: {err}")
                else:
                    ok.add(id(it))
                    if done[0] % 10 == 0 or done[0] == len(items) or args.verbose:
                        print(f"[{done[0]}/{len(items)}] {it.name}.ogg  <- {it.segments[0].stem}"
                              + (" ..." if len(it.segments) > 1 else ""))

        with cf.ThreadPoolExecutor(max_workers=max(1, args.workers)) as ex:
            list(ex.map(job, items))

        sounds = {}
        for e in sound_catalog.EVENTS:
            entries = []
            for it in per_event.get(e.name, []):
                if id(it) not in ok:
                    continue
                s = {"name": "skycraft:" + it.name}
                if e.category in ("music", "bed"):
                    s["stream"] = True
                if e.distance:
                    s["attenuation_distance"] = e.distance
                entries.append(s)
            if entries:
                d = {"replace": True, "sounds": entries}
                if e.subtitle:
                    d["subtitle"] = e.subtitle_key()
                sounds[e.name] = d
        if not sounds:
            print("error: no file could be converted", file=sys.stderr)
            return 1

        mcmeta = {"pack": {"min_format": PACK_FORMAT, "max_format": PACK_FORMAT, "description": PACK_DESCRIPTION}}
        out_zip.parent.mkdir(parents=True, exist_ok=True)
        part = out_zip.with_name(out_zip.name + ".part")
        with zipfile.ZipFile(part, "w", compression=zipfile.ZIP_DEFLATED) as z:
            z.writestr("pack.mcmeta", json.dumps(mcmeta, indent=2, ensure_ascii=False))
            z.writestr("README.txt", README)
            z.writestr("assets/skycraft/sounds.json", json.dumps(sounds, indent=2))
            for it in items:
                if id(it) in ok:
                    z.write(oggs / (it.name + ".ogg"), f"assets/skycraft/sounds/{it.name}.ogg",
                            compress_type=zipfile.ZIP_STORED)
        os.replace(part, out_zip)
    finally:
        shutil.rmtree(tmp_root, ignore_errors=True)

    print(f"\nwrote {out_zip}  ({out_zip.stat().st_size / 1e6:.1f} MB, {len(sounds)} events, {len(ok)} files"
          f"{f', {len(failed)} failed' if failed else ''}, {time.time() - t0:.0f} s)")
    print("Enable it above the Skycraft resources in Options > Resource Packs.")
    print("Reminder: the pack contains copyrighted audio from your copy of Skyrim - personal use only, never share it.")
    return 0


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out_pos", nargs="?", metavar="OUT", help="same as --out")
    ap.add_argument("--out", "-o", help=f"resourcepacks folder (writes {ZIP_NAME} there) or a .zip path; must be outside "
                                        "the repository (default: your .minecraft/resourcepacks if it exists)")
    ap.add_argument("--skyrim", help="Skyrim install folder (contains Data/); default: auto-detect Steam/GOG installs")
    ap.add_argument("--no-skyrim", action="store_true", help="do not read a game install (use --extra folders only)")
    ap.add_argument("--extra", action="append", default=[], metavar="FOLDER",
                    help="folder of loose, already-extracted files (e.g. music) to include; can repeat")
    ap.add_argument("--dry-run", action="store_true", help="print the mapping and coverage, convert nothing")
    ap.add_argument("--list-archive", nargs="?", const="", metavar="REGEX",
                    help="list the audio paths found (optionally filtered) and exit")
    ap.add_argument("--map", action="append", default=[], metavar="REGEX=EVENT",
                    help="extra rule tried before the built-in ones (EVENT 'none' drops matching files)")
    ap.add_argument("--only", action="append", choices=["music", "sfx", "voice", "ambient"],
                    help="only these groups (repeatable)")
    ap.add_argument("--max-per-event", type=int, default=8, help="cap of files per SFX/ambient event (music: all)")
    ap.add_argument("--extra-is-music", action="store_true",
                    help="the --extra folders hold only music: also match free-form names by keyword (town, night...)")
    ap.add_argument("--palettes", action="store_true",
                    help="also map the short regional music 'palette' fragments to province exploration sets")
    ap.add_argument("--voice", default="maleeventoned",
                    help="voice type for the shouts (maleeventoned, femaleeventoned, maleargonian, ...)")
    ap.add_argument("--no-shout-fx", action="store_true", help="shouts: voice only, no Thu'um effect layered in")
    ap.add_argument("--quality", type=float, default=5.0, help="Vorbis quality for music (default 5)")
    ap.add_argument("--sfx-quality", type=float, default=4.0, help="Vorbis quality for SFX/voices (default 4)")
    ap.add_argument("--workers", type=int, default=max(1, min(6, (os.cpu_count() or 2) - 1)), help="parallel ffmpeg jobs")
    ap.add_argument("--limit", type=int, default=0, help="quick test: convert only N files (one per group first)")
    ap.add_argument("--verbose", "-v", action="store_true")
    return ap.parse_args(argv)


def main(argv=None) -> int:
    return run(parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
