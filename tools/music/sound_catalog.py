#!/usr/bin/env python3
"""
The Skycraft sound catalogue: every sound event, the files behind it, its subtitle and how it is played.

This file is the single source of truth shared by
  * tools/music/compose.py ............ music/*.ogg           (category "music")
  * tools/music/synth_ambience.py ..... ambient/**.ogg        (categories "bed", "ambient")
  * tools/music/synth_skyrim_sfx.py ... creature/, shout/, magic/, ui/, wordwall/, world/ (the other categories)
  * mod/src/main/resources/assets/skycraft/sounds.json        (written by this script)
  * mod/src/main/lang/skycraft/audio.json (subtitles)          (written by this script)
  * com.skycraft.audio.AudioSounds / com.skycraft.world.WorldSounds (Java registration; --check verifies it)
  * tools/music/import_skyrim_audio.py  (the personal importer overrides these events)

Usage:
    py -3 tools/music/sound_catalog.py --write     # regenerate sounds.json + the subtitle lang file
    py -3 tools/music/sound_catalog.py --check     # report missing / unreferenced .ogg files, sizes per category
    py -3 tools/music/sound_catalog.py --list [category]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from dataclasses import dataclass, field

REPO = pathlib.Path(__file__).resolve().parents[2]
SOUNDS = REPO / "mod/src/main/resources/assets/skycraft/sounds"
SOUNDS_JSON = REPO / "mod/src/main/resources/assets/skycraft/sounds.json"
LANG = REPO / "mod/src/main/lang/skycraft/audio.json"
JAVA = REPO / "mod/src/main/java/com/skycraft"


@dataclass
class Event:
    name: str                      # event id in the skycraft namespace, e.g. "entity.dragon.roar"
    files: list                    # file stems relative to sounds/, e.g. ["creature/dragon/roar1"]; "@event" = nested event
    category: str                  # music | bed | ambient | creature | shout | magic | ui | world
    subtitle: str | None = None    # English subtitle (None = no subtitle)
    stream: bool = False
    distance: int | None = None    # attenuation_distance (blocks at volume 1)
    volume: float | None = None    # per-file volume
    weights: list | None = None    # per-file weights
    replace: bool = False

    def json(self):
        out = {}
        if self.replace:
            out["replace"] = True
        sounds = []
        for i, f in enumerate(self.files):
            if f.startswith("@"):
                sounds.append({"name": "skycraft:" + f[1:], "type": "event"})
                continue
            if not (self.stream or self.distance or self.volume or self.weights):
                sounds.append("skycraft:" + f)
                continue
            s = {"name": "skycraft:" + f}
            if self.stream:
                s["stream"] = True
            if self.distance:
                s["attenuation_distance"] = self.distance
            if self.volume is not None:
                s["volume"] = self.volume
            if self.weights:
                s["weight"] = self.weights[i]
            sounds.append(s)
        out["sounds"] = sounds
        if self.subtitle:
            out["subtitle"] = self.subtitle_key()
        return out

    def subtitle_key(self):
        return "subtitles.skycraft." + self.name


EVENTS: list[Event] = []


def ev(name, files, category, subtitle=None, **kw):
    EVENTS.append(Event(name, list(files), category, subtitle, **kw))


def n(stem, count):
    return [f"{stem}{i}" for i in range(1, count + 1)]


# =====================================================================================================  music
# Exploration sets. "skyrim" keeps the historical event names music.explore / music.explore_night.
MUSIC_SETS = ["cyrodiil", "morrowind", "black_marsh", "elsweyr", "valenwood", "summerset", "hammerfell", "high_rock",
              "atmora", "akavir", "sea"]
TOWN_SETS = ["imperial", "dunmer", "desert", "elven"]

ev("music.explore", ["music/explore_day_1", "music/explore_day_2", "music/explore_day_3"], "music", stream=True)
ev("music.explore_night", ["music/explore_night_1", "music/explore_night_2"], "music", stream=True)
for s in MUSIC_SETS:
    ev(f"music.explore.{s}", [f"music/{s}_day"], "music", stream=True)
    ev(f"music.explore_night.{s}", [f"music/{s}_night"], "music", stream=True)
ev("music.town", ["music/town_1", "music/town_2"], "music", stream=True)
for s in TOWN_SETS:
    ev(f"music.town.{s}", [f"music/town_{s}"], "music", stream=True)
ev("music.dungeon", ["music/dungeon_1", "music/dungeon_2"], "music", stream=True)
ev("music.dungeon.dwemer", ["music/dungeon_dwemer"], "music", stream=True)
ev("music.dungeon.ayleid", ["music/dungeon_ayleid"], "music", stream=True)
ev("music.blackreach", ["music/blackreach"], "music", stream=True)
ev("music.combat", ["music/combat_1", "music/combat_2", "@music.boss"], "music", stream=True, weights=[2, 2, 1])
ev("music.boss", ["music/boss_dragon"], "music", stream=True)
ev("music.boss.battle", ["music/boss_battle"], "music", stream=True)
ev("music.oblivion", ["music/oblivion_1"], "music", stream=True)
ev("music.sovngarde", ["music/sovngarde_1"], "music", stream=True)
ev("music.discovery", ["music/discovery"], "music", stream=True)
ev("music.level_up", ["music/level_up"], "music", stream=True)

# =====================================================================================================  ambience
# Looping beds: stereo, streamed (so they don't sit decoded in memory), played non-positionally by the ambience engine.
BEDS = ["wind_light", "wind_strong", "blizzard", "forest_day", "forest_night", "plains_day", "plains_night",
        "marsh_day", "marsh_night", "jungle_day", "jungle_night", "desert_wind", "ash_storm", "coast_waves",
        "open_ocean", "river", "cave", "cave_deep", "glow_cave", "oblivion", "sovngarde"]
for b in BEDS:
    ev(f"ambient.bed.{b}", [f"ambient/bed/{b}"], "bed", stream=True)

# Positional one-shots (mono), placed around the listener by the ambience engine.
ONESHOTS = [
    # event, stem, count, subtitle, distance
    ("bird_song", "bird_song", 4, "Bird sings", 48),
    ("crow", "crow", 2, "Crow caws", 64),
    ("hawk", "hawk", 2, "Hawk cries", 96),
    ("gull", "gull", 2, "Gull cries", 64),
    ("jungle_bird", "jungle_bird", 3, "Exotic bird calls", 64),
    ("owl", "owl", 2, "Owl hoots", 48),
    ("loon", "loon", 2, "Loon wails", 96),
    ("frog", "frog", 3, "Frog croaks", 32),
    ("cricket", "cricket", 2, "Cricket chirps", 24),
    ("insect", "insect", 2, "Insect buzzes", 16),
    ("cave_drip", "cave_drip", 4, "Water drips", 24),
    ("cave_rumble", "cave_rumble", 2, "Cave rumbles", 48),
    ("rock_fall", "rock_fall", 2, "Pebbles fall", 32),
    ("wind_gust", "wind_gust", 2, "Wind gusts", 32),
    ("tree_creak", "tree_creak", 2, "Tree creaks", 24),
    ("volcano", "volcano", 2, "Distant rumble", 160),
    ("distant_dragon", "distant_dragon", 2, "Distant dragon roars", 256),
    ("wave_crash", "wave_crash", 2, "Waves crash", 48),
    ("whale", "whale", 2, "Whale sings", 160),
    ("sand_hiss", "sand_hiss", 2, "Sand hisses", 24),
    ("oblivion_wail", "oblivion_wail", 2, "Daedric wail", 96),
    ("sovngarde_horn", "sovngarde_horn", 1, "Distant horn", 192),
    ("glow_chime", "glow_chime", 2, "Crystals chime", 32),
]
for name, stem, count, sub, dist in ONESHOTS:
    ev(f"ambient.{name}", n(f"ambient/{stem}", count), "ambient", sub, distance=dist)

# =====================================================================================================  creatures
CREATURES = {
    # creature: [(kind, count, subtitle, distance)]
    "dragon": [("roar", 2, "Dragon roars", 192), ("growl", 2, "Dragon growls", 48), ("wings", 3, "Dragon wings beat", 64),
               ("breath_fire", 1, "Dragon breathes fire", 64), ("breath_frost", 1, "Dragon breathes frost", 64),
               ("land", 1, "Dragon lands", 96), ("hurt", 2, "Dragon hurts", 48), ("death", 1, "Dragon dies", 192)],
    "draugr": [("ambient", 3, "Draugr groans", 16), ("attack", 2, "Draugr attacks", 16), ("hurt", 2, "Draugr hurts", 16),
               ("death", 1, "Draugr dies", 16)],
    "skeever": [("ambient", 2, "Skeever squeaks", 16), ("hurt", 1, "Skeever hurts", 16), ("death", 1, "Skeever dies", 16)],
    "troll": [("ambient", 2, "Troll grunts", 24), ("roar", 1, "Troll roars", 48), ("hurt", 1, "Troll hurts", 24),
              ("death", 1, "Troll dies", 24)],
    "giant": [("ambient", 2, "Giant grunts", 32), ("stomp", 2, "Giant stomps", 48), ("hurt", 1, "Giant hurts", 32),
              ("death", 1, "Giant dies", 48)],
    "mammoth": [("ambient", 2, "Mammoth trumpets", 64), ("hurt", 1, "Mammoth hurts", 32), ("death", 1, "Mammoth dies", 48)],
    "frostbite_spider": [("ambient", 2, "Frostbite spider hisses", 16), ("hurt", 1, "Frostbite spider hurts", 16),
                         ("death", 1, "Frostbite spider dies", 16)],
    "wolf": [("howl", 2, "Wolf howls", 96), ("growl", 1, "Wolf growls", 16)],
    "bear": [("ambient", 2, "Bear grunts", 16), ("roar", 1, "Bear roars", 32), ("hurt", 1, "Bear hurts", 16),
             ("death", 1, "Bear dies", 16)],
    "sabre_cat": [("ambient", 2, "Sabre cat snarls", 24), ("hurt", 1, "Sabre cat hurts", 16), ("death", 1, "Sabre cat dies", 16)],
    "falmer": [("ambient", 2, "Falmer screeches", 24), ("hurt", 1, "Falmer hurts", 16), ("death", 1, "Falmer dies", 16)],
    "dwarven": [("steam", 2, "Dwarven machine hisses", 24), ("clank", 2, "Dwarven machine clanks", 16),
                ("hurt", 1, "Metal clangs", 16), ("death", 1, "Dwarven machine collapses", 24)],
    "wisp": [("ambient", 2, "Wisp chimes", 24)],
    "spriggan": [("ambient", 2, "Spriggan creaks", 16), ("hurt", 1, "Spriggan hurts", 16), ("death", 1, "Spriggan dies", 16)],
    "hagraven": [("ambient", 2, "Hagraven caws", 24), ("hurt", 1, "Hagraven hurts", 16), ("death", 1, "Hagraven dies", 16)],
    "horker": [("ambient", 2, "Horker barks", 24), ("hurt", 1, "Horker hurts", 16), ("death", 1, "Horker dies", 16)],
    "mudcrab": [("ambient", 2, "Mudcrab clicks", 12), ("hurt", 1, "Mudcrab hurts", 12), ("death", 1, "Mudcrab dies", 12)],
    "ice_wraith": [("ambient", 2, "Ice wraith shrieks", 24), ("hurt", 1, "Ice cracks", 16), ("death", 1, "Ice wraith shatters", 16)],
}
for creature, kinds in CREATURES.items():
    for kind, count, sub, dist in kinds:
        ev(f"entity.{creature}.{kind}", n(f"creature/{creature}/{kind}", count), "creature", sub,
           distance=dist if dist != 16 else None)

# =====================================================================================================  shouts
# Every shout has a chant for one, two and three words (the words are lore names; the voice is synthesised).
SHOUTS = {
    "unrelenting_force": ["FUS", "RO", "DAH"], "fire_breath": ["YOL", "TOOR", "SHUL"], "frost_breath": ["FO", "KRAH", "DIIN"],
    "whirlwind_sprint": ["WULD", "NAH", "KEST"], "become_ethereal": ["FEIM", "ZII", "GRON"], "clear_skies": ["LOK", "VAH", "KOOR"],
    "aura_whisper": ["LAAS", "YAH", "NIR"], "slow_time": ["TIID", "KLO", "UL"], "marked_for_death": ["KRII", "LUN", "AUS"],
    "disarm": ["ZUN", "HAAL", "VIIK"], "elemental_fury": ["SU", "GRAH", "DUN"], "kynes_peace": ["KAAN", "DREM", "OV"],
    "animal_allegiance": ["RAAN", "MIR", "TAH"], "storm_call": ["STRUN", "BAH", "QO"], "dragonrend": ["JOOR", "ZAH", "FRUL"],
}
for shout, words in SHOUTS.items():
    for k in (1, 2, 3):
        ev(f"shout.{shout}.{k}", [f"shout/{shout}_{k}"], "shout", "Thu'um: " + " ".join(w.title() for w in words[:k]), distance=64)
# historical names (still referenced by resource packs); they point at the three-word chants
ev("shout.fus_ro_dah", ["@shout.unrelenting_force.3"], "shout", "Thu'um: Fus Ro Dah")
ev("shout.wuld_nah_kest", ["@shout.whirlwind_sprint.3"], "shout", "Thu'um: Wuld Nah Kest")
ev("shout.yol_toor_shul", ["@shout.fire_breath.3"], "shout", "Thu'um: Yol Toor Shul")
ev("shout.generic", ["shout/generic"], "shout", "Thu'um", distance=64)
ev("wordwall.chant", ["wordwall/chant"], "shout", "Word Wall chants", distance=32)
ev("wordwall.learn", ["wordwall/learn"], "shout", "Word of Power learned", distance=24)

# =====================================================================================================  magic
SPELLS = [
    ("fire", 1, "Flames whoosh"), ("fire_stream", 2, "Flames roar"), ("frost", 1, "Frost crackles"),
    ("frost_stream", 2, "Frost hisses"), ("shock", 1, "Lightning zaps"), ("shock_stream", 2, "Lightning crackles"),
    ("heal", 1, "Healing hums"), ("conjure", 1, "Portal opens"), ("illusion", 1, "Illusion shimmers"),
    ("alteration", 1, "Spell chimes"), ("charge", 1, "Spell charges"), ("fizzle", 1, "Spell fizzles"),
    ("impact_fire", 1, "Fire bursts"), ("impact_frost", 1, "Ice shatters"), ("impact_shock", 1, "Lightning strikes"),
]
for name, count, sub in SPELLS:
    ev(f"spell.{name}", n(f"magic/spell/{name}", count) if count > 1 else [f"magic/spell/{name}"], "magic", sub)
ev("magic.dragon_soul", ["magic/dragon_soul"], "magic", "Dragon soul absorbed", distance=48)
ev("magic.shrine_pray", ["magic/shrine_pray"], "magic", "Shrine blesses")
ev("magic.standing_stone", ["magic/standing_stone"], "magic", "Standing Stone resonates", distance=24)
ev("world.nirnroot_hum", ["world/nirnroot_hum"], "world", "Nirnroot chimes")

# =====================================================================================================  UI
UI = [("menu_open", None), ("menu_close", None), ("menu_click", None), ("menu_back", None), ("quest_update", "Quest updated"),
      ("quest_complete", "Quest completed"), ("skill_up", "Skill increased"), ("level_up", "Level up"),
      ("discover", "Location discovered"), ("lockpick_turn", "Lockpick turns"), ("lockpick_strain", "Lockpick strains"),
      ("lockpick_break", "Lockpick breaks"), ("lockpick_unlock", "Lock opens")]
for name, sub in UI:
    ev(f"ui.{name}", [f"ui/{name}"], "ui", sub)


# =====================================================================================================  tools

def by_category(cat: str | None = None):
    return [e for e in EVENTS if cat is None or e.category == cat]


def files(cat: str | None = None):
    out = []
    for e in by_category(cat):
        out += [f for f in e.files if not f.startswith("@")]
    return out


def write():
    data = {e.name: e.json() for e in EVENTS}
    SOUNDS_JSON.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    lang = {e.subtitle_key(): e.subtitle for e in EVENTS if e.subtitle}
    LANG.parent.mkdir(parents=True, exist_ok=True)
    LANG.write_text(json.dumps(lang, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {len(data)} events -> {SOUNDS_JSON.relative_to(REPO)}, {len(lang)} subtitles -> {LANG.relative_to(REPO)}")


def check() -> int:
    bad = 0
    wanted = set()
    sizes = {}
    for e in EVENTS:
        for f in e.files:
            if f.startswith("@"):
                if not any(x.name == f[1:] for x in EVENTS):
                    print(f"  {e.name}: nested event {f[1:]} is not in the catalogue")
                    bad += 1
                continue
            p = SOUNDS / (f + ".ogg")
            wanted.add(p.resolve())
            if not p.exists():
                print(f"  missing: {p.relative_to(REPO)}  ({e.name})")
                bad += 1
            else:
                sizes[e.category] = sizes.get(e.category, 0) + p.stat().st_size
    for p in SOUNDS.rglob("*.ogg"):
        if p.resolve() not in wanted:
            print(f"  unreferenced: {p.relative_to(REPO)}")
    # every event must be registered in Java (AudioSounds / WorldSounds / AtmosphereSounds)
    # AudioSounds builds most names from string arrays (and the Shout enum), so accept a name when it is registered
    # literally or when every part after the first is a literal / an enum constant / a word count.
    java = "\n".join(p.read_text(encoding="utf-8") for p in JAVA.rglob("*Sounds.java"))
    registered = set(re.findall(r'register\("([^"]+)"\)', java))
    literals = set()
    for lit in re.findall(r'"([a-z0-9_.]+)"', java):
        literals |= {t for t in lit.split(".") if t}
    shout_java = JAVA / "magic/shout/Shout.java"
    if shout_java.exists():
        literals |= {c.lower() for c in re.findall(r"^\s*([A-Z_]+)\(new String", shout_java.read_text(encoding="utf-8"), re.M)}
    for e in EVENTS:
        if e.name in registered:
            continue
        parts = e.name.split(".")[1:]
        if parts and all(p in literals or p.isdigit() for p in parts):
            continue
        print(f"  not registered in Java: {e.name}")
        bad += 1
    total = sum(sizes.values())
    for c, s in sorted(sizes.items()):
        print(f"  {c:9s} {s / 1e6:7.2f} MB")
    print(f"  {'total':9s} {total / 1e6:7.2f} MB, {len(EVENTS)} events, {len(wanted)} files")
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--list", nargs="?", const="", metavar="CATEGORY")
    args = ap.parse_args()
    if args.list is not None:
        for e in by_category(args.list or None):
            print(f"{e.name:40s} {e.category:9s} {', '.join(e.files)}")
    if args.write:
        write()
    if args.check:
        return 1 if check() else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
