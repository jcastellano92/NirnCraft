#!/usr/bin/env python3
"""
Build a personal resource pack that plays YOUR OWN copy of the Skyrim soundtrack in Skycraft, from a folder of music
files you already exported from your installation.

This is the music-only front end of tools/music/import_skyrim_audio.py, kept for people who already have the
soundtrack as loose files. If Skyrim is installed, import_skyrim_audio.py can read the game archives directly and also
imports creature, magic, UI, ambience and shout sounds.

  * It never downloads anything and only reads the input folder; the zip is written outside the Skycraft repository
    (an output path inside the repository is refused).
  * The resulting pack contains copyrighted music: it is for your personal use only. Do not share or upload it.

Skyrim names its music like mus_explore_day_01, mus_explore_night_02, mus_town_day_01, mus_dungeon_01, mus_combat_03,
mus_combat_boss, mus_discover_dungeon_01, mus_levelup_01, mus_sovngarde_chant_lp, mus_explore_dlc2solstheim_01 ...
(.xwm, or .wav/.mp3/.flac/.ogg after conversion). Files are matched by name (first matching rule wins): the game's own
names map precisely (e.g. Solstheim -> music.explore.morrowind, Apocrypha/Soul Cairn -> music.oblivion), other names
by keyword (town, tavern, night, dungeon, combat, boss ...). Use --map 'regex=music.event' to add your own rules
(tried first, matched against the file name) and --dry-run to see the mapping without converting anything.

Usage:
    python3 tools/music/import_skyrim_ost.py ~/SkyrimMusic
    python3 tools/music/import_skyrim_ost.py ~/SkyrimMusic -o ~/.minecraft/resourcepacks/skycraft_personal_ost.zip
    python3 tools/music/import_skyrim_ost.py ~/SkyrimMusic --dry-run --map 'mus_special_.*=music.sovngarde'
"""
from __future__ import annotations

import argparse
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import import_skyrim_audio  # noqa: E402
import sound_catalog  # noqa: E402

PACK_NAME = "Skycraft – Personal Skyrim OST"
ZIP_NAME = "skycraft_personal_skyrim_ost.zip"

# Skycraft music events (see tools/music/sound_catalog.py).
EVENTS = [e.name for e in sound_catalog.EVENTS if e.category == "music"]


def default_out() -> pathlib.Path:
    rp = import_skyrim_audio.default_resourcepacks()
    return (rp or pathlib.Path.home()) / ZIP_NAME


def stem_rule(pattern: str) -> str:
    """Old-style --map patterns were matched against the file stem; turn one into a rule on 'extra/<path>'."""
    p = pattern.lower()
    head = r"(?:^|/)" if p.startswith("^") else ""
    if p.startswith("^"):
        p = p[1:]
    if p.endswith("$") and not p.endswith(r"\$"):
        p = p[:-1] + r"\.[a-z0-9]+$"
    return head + "(?:" + p + ")"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", type=pathlib.Path, help="folder with music files exported from YOUR copy of Skyrim")
    ap.add_argument("-o", "--out", type=pathlib.Path, default=None,
                    help=f"output resource pack zip (default: <.minecraft>/resourcepacks/{ZIP_NAME}, else ~/{ZIP_NAME})")
    ap.add_argument("--map", action="append", default=[], metavar="REGEX=EVENT",
                    help="extra rule tried before the built-in ones, e.g. 'mus_special_.*=music.sovngarde'")
    ap.add_argument("--quality", type=float, default=5.0, help="Vorbis quality for conversion (default 5)")
    ap.add_argument("--dry-run", action="store_true", help="only print which file goes to which event")
    args = ap.parse_args()

    if not args.folder.is_dir():
        print(f"error: {args.folder} is not a folder", file=sys.stderr)
        return 2
    maps = []
    for m in args.map:
        if "=" not in m:
            print(f"error: --map needs REGEX=EVENT, got {m!r}", file=sys.stderr)
            return 2
        pattern, event = m.rsplit("=", 1)
        if event not in EVENTS:
            print(f"error: unknown event {event!r}; choose from {', '.join(EVENTS)}", file=sys.stderr)
            return 2
        maps += ["--map", f"{stem_rule(pattern)}={event}"]

    out = args.out if args.out is not None else default_out()
    if out.suffix.lower() != ".zip":
        out = out / ZIP_NAME
    argv = ["--no-skyrim", "--extra", str(args.folder), "--extra-is-music", "--only", "music",
            "--quality", str(args.quality), "--out", str(out)] + maps
    if args.dry_run:
        argv.append("--dry-run")
    print(f"{PACK_NAME}: converting the music in {args.folder}")
    return import_skyrim_audio.main(argv)


if __name__ == "__main__":
    sys.exit(main())
