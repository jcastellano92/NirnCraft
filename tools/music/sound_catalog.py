#!/usr/bin/env python3
"""
The NirnCraft sound catalogue: every sound event, the files behind it, its subtitle and how it is played.

This file is the single source of truth shared by
  * tools/music/compose.py ............ music/*.ogg           (category "music")
  * tools/music/synth_ambience.py ..... ambient/**.ogg        (categories "bed", "ambient")
  * tools/music/synth_skyrim_sfx.py ... creature/, shout/, magic/, ui/, wordwall/, world/ (the other categories)
  * mod/src/main/resources/assets/nirncraft/sounds.json        (written by this script)
  * mod/src/main/lang/nirncraft/audio.json (subtitles)          (written by this script)
  * com.nirncraft.audio.AudioSounds / com.nirncraft.world.WorldSounds (Java registration; --check verifies it)
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
SOUNDS = REPO / "mod/src/main/resources/assets/nirncraft/sounds"
SOUNDS_JSON = REPO / "mod/src/main/resources/assets/nirncraft/sounds.json"
LANG = REPO / "mod/src/main/lang/nirncraft/audio.json"
JAVA = REPO / "mod/src/main/java/com/nirncraft"


@dataclass
class Event:
    name: str                      # event id in the nirncraft namespace, e.g. "entity.dragon.roar"
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
                sounds.append({"name": "nirncraft:" + f[1:], "type": "event"})
                continue
            if not (self.stream or self.distance or self.volume or self.weights):
                sounds.append("nirncraft:" + f)
                continue
            s = {"name": "nirncraft:" + f}
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
        return "subtitles.nirncraft." + self.name


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
        "open_ocean", "river", "cave", "cave_deep", "glow_cave", "oblivion", "sovngarde",
        # added with the real biomes: warm swamps at night, savanna, Eastmarch's hot springs, volcanic land, crystal
        # land, and the cave biomes (Dwemer caverns, lava caves, ice caves, mushroom grottos)
        "swamp_night", "savanna_day", "hot_springs", "volcanic_rumble", "crystal_shimmer", "dwemer_machinery", "lava_cave",
        "ice_cave", "mushroom_grotto"]
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
    ("distant_screech", "distant_screech", 2, "Distant screech", 96),
    ("machinery", "machinery", 3, "Machinery clanks", 48),
    ("lava_bubble", "lava_bubble", 2, "Lava bubbles", 24),
    ("ice_creak", "ice_creak", 2, "Ice creaks", 48),
    ("cicada", "cicada", 2, "Cicada buzzes", 32),
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

# The bestiary of Nirn: voices for the creatures added with it (and the extra kinds of a few older ones). Kinds besides
# ambient / hurt / death are cries the attack goal plays when a move starts: "attack" (bites, blows), "roar" (roars,
# war cries, charges), "cast" (spells, bolts), "breath"; and specials ("wings", "step", "wake", "emerge", "rise").
# These files are encoded smaller than the older ones (24 kHz, Vorbis q1.5) to keep the jar lean: see LOFI below.
BESTIARY = {
    "wolf": [("attack", 2, "Wolf snarls", 16), ("hurt", 1, "Wolf yelps", 16), ("death", 1, "Wolf dies", 16)],
    "chaurus": [("ambient", 2, "Chaurus clicks", 16), ("attack", 1, "Chaurus hisses", 16), ("cast", 1, "Chaurus spits", 16),
                ("wings", 1, "Chaurus hunter buzzes", 24), ("hurt", 1, "Chaurus hurts", 16), ("death", 1, "Chaurus dies", 16)],
    "riekling": [("ambient", 2, "Riekling chitters", 16), ("attack", 1, "Riekling yelps", 16), ("roar", 1, "Riekling war cry", 32),
                 ("hurt", 1, "Riekling hurts", 16), ("death", 1, "Riekling dies", 16)],
    "ash_spawn": [("ambient", 2, "Ash spawn rasps", 16), ("attack", 1, "Ash spawn hisses", 16), ("emerge", 1, "Ash spawn rises", 24),
                  ("hurt", 1, "Ash spawn hurts", 16), ("death", 1, "Ash spawn crumbles", 16)],
    "gargoyle": [("ambient", 2, "Gargoyle growls", 16), ("roar", 1, "Gargoyle roars", 32), ("wake", 1, "Stone grinds", 24),
                 ("hurt", 1, "Stone cracks", 16), ("death", 1, "Gargoyle crumbles", 24)],
    "wisp": [("hurt", 1, "Wisp flickers", 16), ("death", 1, "Wisp fades", 16)],
    "will_o_wisp": [("ambient", 2, "Will-o'-the-wisp chimes", 24), ("hurt", 1, "Will-o'-the-wisp flickers", 16),
                    ("death", 1, "Will-o'-the-wisp fades", 16)],
    "death_hound": [("ambient", 2, "Death hound snarls", 24), ("attack", 1, "Death hound snaps", 16), ("roar", 1, "Death hound howls", 48),
                    ("hurt", 1, "Death hound hurts", 16), ("death", 1, "Death hound dies", 16)],
    "dwarven_ballista": [("cast", 1, "Ballista fires", 32)],
    "dragon_priest": [("ambient", 2, "Dragon priest whispers", 24), ("cast", 2, "Dragon priest chants", 32),
                      ("hurt", 1, "Dragon priest hisses", 24), ("death", 1, "Dragon priest wails", 48)],
    "dremora": [("ambient", 2, "Dremora growls", 16), ("attack", 2, "Dremora grunts", 16), ("roar", 1, "Dremora bellows", 32),
                ("hurt", 1, "Dremora hurts", 16), ("death", 1, "Dremora dies", 16)],
    "flame_atronach": [("ambient", 2, "Flame atronach crackles", 16), ("hurt", 1, "Flame atronach hisses", 16),
                       ("death", 1, "Flame atronach bursts", 24)],
    "frost_atronach": [("ambient", 2, "Frost atronach grinds", 16), ("attack", 1, "Frost atronach groans", 16),
                       ("step", 2, None, 16), ("hurt", 1, "Ice cracks", 16), ("death", 1, "Frost atronach shatters", 24)],
    "storm_atronach": [("ambient", 2, "Storm atronach crackles", 32), ("hurt", 1, "Storm atronach sparks", 16),
                       ("death", 1, "Storm atronach collapses", 48)],
    "scamp": [("ambient", 2, "Scamp cackles", 16), ("roar", 1, "Scamp shrieks", 24), ("hurt", 1, "Scamp hurts", 16),
              ("death", 1, "Scamp dies", 16)],
    "imp": [("ambient", 2, "Imp chitters", 16), ("hurt", 1, "Imp hurts", 16), ("death", 1, "Imp dies", 16)],
    "cliff_racer": [("ambient", 2, "Cliff racer screeches", 64), ("attack", 1, "Cliff racer dives", 24),
                    ("roar", 1, "Cliff racer shrieks", 48), ("hurt", 1, "Cliff racer hurts", 16), ("death", 1, "Cliff racer dies", 24)],
    "guar": [("ambient", 2, "Guar grunts", 16), ("hurt", 1, "Guar hurts", 16), ("death", 1, "Guar dies", 16)],
    "kagouti": [("ambient", 2, "Kagouti snorts", 16), ("roar", 1, "Kagouti bellows", 32), ("hurt", 1, "Kagouti hurts", 16),
                ("death", 1, "Kagouti dies", 16)],
    "alit": [("ambient", 2, "Alit growls", 16), ("attack", 1, "Alit snaps", 16), ("roar", 1, "Alit roars", 32),
             ("hurt", 1, "Alit hurts", 16), ("death", 1, "Alit dies", 16)],
    "nix_hound": [("ambient", 2, "Nix-hound chirrs", 16), ("roar", 1, "Nix-hound shrieks", 32), ("hurt", 1, "Nix-hound hurts", 16),
                  ("death", 1, "Nix-hound dies", 16)],
    "netch": [("ambient", 2, "Netch hums", 64), ("hurt", 1, "Netch groans", 24), ("death", 1, "Netch deflates", 32)],
    "silt_strider": [("ambient", 2, "Silt strider calls", 160), ("hurt", 1, "Silt strider groans", 48),
                     ("death", 1, "Silt strider dies", 96)],
    "minotaur": [("ambient", 2, "Minotaur snorts", 16), ("attack", 1, "Minotaur grunts", 16), ("roar", 1, "Minotaur bellows", 48),
                 ("hurt", 1, "Minotaur hurts", 16), ("death", 1, "Minotaur dies", 24)],
    "ogre": [("ambient", 2, "Ogre grunts", 16), ("roar", 1, "Ogre roars", 48), ("hurt", 1, "Ogre hurts", 16),
             ("death", 1, "Ogre dies", 24)],
    "land_dreugh": [("ambient", 2, "Dreugh clicks", 16), ("roar", 1, "Dreugh chitters", 24), ("hurt", 1, "Dreugh hurts", 16),
                    ("death", 1, "Dreugh dies", 16)],
    "wamasu": [("ambient", 2, "Wamasu hums", 24), ("roar", 1, "Wamasu roars", 48), ("breath", 1, "Wamasu breathes lightning", 32),
               ("hurt", 1, "Wamasu hurts", 16), ("death", 1, "Wamasu dies", 24)],
    "swamp_leviathan": [("ambient", 2, "Leviathan rumbles", 32), ("roar", 1, "Leviathan bellows", 64),
                        ("hurt", 1, "Leviathan hurts", 24), ("death", 1, "Leviathan dies", 48)],
    "hackwing": [("ambient", 2, "Hackwing screeches", 48), ("hurt", 1, "Hackwing hurts", 16), ("death", 1, "Hackwing dies", 16)],
    "senche_tiger": [("ambient", 2, "Senche-tiger growls", 24), ("roar", 1, "Senche-tiger roars", 48),
                     ("hurt", 1, "Senche-tiger hurts", 16), ("death", 1, "Senche-tiger dies", 16)],
    "alfiq": [("ambient", 2, "Alfiq trills", 16), ("hurt", 1, "Alfiq hisses", 16), ("death", 1, "Alfiq dies", 16)],
    "sand_serpent": [("ambient", 2, "Sand serpent hisses", 24), ("roar", 1, "Sand serpent erupts", 48),
                     ("hurt", 1, "Sand serpent hurts", 16), ("death", 1, "Sand serpent dies", 24)],
    "giant_scorpion": [("ambient", 2, "Scorpion clicks", 16), ("attack", 1, "Scorpion stings", 16),
                       ("hurt", 1, "Scorpion hurts", 16), ("death", 1, "Scorpion dies", 16)],
    "harpy": [("ambient", 2, "Harpy shrieks", 32), ("roar", 1, "Harpy screams", 48), ("hurt", 1, "Harpy hurts", 16),
              ("death", 1, "Harpy dies", 16)],
    "indrik": [("ambient", 2, "Indrik trills", 24), ("hurt", 1, "Indrik hurts", 16), ("death", 1, "Indrik dies", 16)],
    "sea_serpent": [("ambient", 2, "Sea serpent moans", 64), ("hurt", 1, "Sea serpent hurts", 24),
                    ("death", 1, "Sea serpent dies", 48)],
    "echatere": [("ambient", 2, "Echatere lows", 24), ("hurt", 1, "Echatere hurts", 16), ("death", 1, "Echatere dies", 16)],
    "durzog": [("ambient", 2, "Durzog snarls", 16), ("roar", 1, "Durzog howls", 32), ("hurt", 1, "Durzog hurts", 16),
               ("death", 1, "Durzog dies", 16)],
    "frost_giant": [("ambient", 2, "Frost giant rumbles", 32), ("roar", 1, "Frost giant roars", 64),
                    ("hurt", 1, "Frost giant hurts", 32), ("death", 1, "Frost giant dies", 48)],
    "kamal": [("ambient", 2, "Kamal hisses", 16), ("hurt", 1, "Kamal hurts", 16), ("death", 1, "Kamal dies", 16)],
    "tsaesci": [("ambient", 2, "Tsaesci hisses", 16), ("roar", 1, "Tsaesci war cry", 32), ("hurt", 1, "Tsaesci hurts", 16),
                ("death", 1, "Tsaesci dies", 16)],
    "realm_stalker": [("ambient", 2, "Stalker growls", 16), ("roar", 1, "Stalker howls", 32), ("hurt", 1, "Stalker hurts", 16),
                      ("death", 1, "Stalker dies", 16)],
    "realm_grazer": [("ambient", 2, "Grazer lows", 24), ("hurt", 1, "Grazer hurts", 16), ("death", 1, "Grazer dies", 16)],
    "realm_wyrm": [("ambient", 2, "Wyrm hisses", 16), ("hurt", 1, "Wyrm hurts", 16), ("death", 1, "Wyrm dies", 16)],
    # dragons: the World-Eater and the old master have their own voices; any dragon rising from its mound
    "dragon": [("rise", 1, "Dragon rises from its mound", 128)],
    "alduin": [("roar", 2, "Alduin roars", 256), ("growl", 1, "Alduin growls", 64), ("hurt", 1, "Alduin hurts", 64),
               ("death", 1, "Alduin dies", 256)],
    "paarthurnax": [("roar", 1, "Paarthurnax roars", 192), ("growl", 2, "Paarthurnax rumbles", 48),
                    ("hurt", 1, "Paarthurnax hurts", 48), ("death", 1, "Paarthurnax dies", 192)],
}
LOFI = set()   # file stems encoded at 24 kHz, Vorbis q1.5 (see synth_skyrim_sfx.encoding)
for creature, kinds in BESTIARY.items():
    for kind, count, sub, dist in kinds:
        stems = n(f"creature/{creature}/{kind}", count)
        LOFI.update(stems)
        ev(f"entity.{creature}.{kind}", stems, "creature", sub, distance=dist if dist != 16 else None)
# the wolf's idle sound is its growl
ev("entity.wolf.ambient", ["@entity.wolf.growl"], "creature", "Wolf growls")

# =====================================================================================================  shouts
# Every shout has a chant for one, two and three words (the words are lore names; the voice is synthesised).
SHOUTS = {
    "unrelenting_force": ["FUS", "RO", "DAH"], "fire_breath": ["YOL", "TOOR", "SHUL"], "frost_breath": ["FO", "KRAH", "DIIN"],
    "whirlwind_sprint": ["WULD", "NAH", "KEST"], "become_ethereal": ["FEIM", "ZII", "GRON"], "clear_skies": ["LOK", "VAH", "KOOR"],
    "aura_whisper": ["LAAS", "YAH", "NIR"], "slow_time": ["TIID", "KLO", "UL"], "marked_for_death": ["KRII", "LUN", "AUS"],
    "disarm": ["ZUN", "HAAL", "VIIK"], "elemental_fury": ["SU", "GRAH", "DUN"], "kynes_peace": ["KAAN", "DREM", "OV"],
    "animal_allegiance": ["RAAN", "MIR", "TAH"], "storm_call": ["STRUN", "BAH", "QO"], "dragonrend": ["JOOR", "ZAH", "FRUL"],
}
# shouts added later (Dawnguard / Dragonborn and the rest of com.nirncraft.magic.shout.Shout): encoded smaller (LOFI)
NEW_SHOUTS = {
    "ice_form": ["IIZ", "SLEN", "NUS"], "throw_voice": ["ZUL", "MEY", "GUT"], "dismay": ["FAAS", "RU", "MAAR"],
    "call_dragon": ["OD", "AH", "VIING"], "call_of_valor": ["HUN", "KAAL", "ZOOR"], "cyclone": ["VEN", "GAAR", "NOS"],
    "drain_vitality": ["GAAN", "LAH", "HAAS"], "soul_tear": ["RII", "VAAZ", "ZOL"], "bend_will": ["GOL", "HAH", "DOV"],
    "dragon_aspect": ["MUL", "QAH", "DIIV"], "battle_fury": ["MID", "VUR", "SHAAN"],
}
SHOUTS.update(NEW_SHOUTS)
for shout, words in SHOUTS.items():
    for k in (1, 2, 3):
        ev(f"shout.{shout}.{k}", [f"shout/{shout}_{k}"], "shout", "Thu'um: " + " ".join(w.title() for w in words[:k]), distance=64)
        if shout in NEW_SHOUTS:
            LOFI.add(f"shout/{shout}_{k}")
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

# =====================================================================================================  fire and aftermath
# Registered by com.nirncraft.fire.FireSounds. The aftermath piece plays inside the footprint of a place that was just
# attacked (Helgen: fire/client/MusicZones); the ambience one-shots are positional, quiet, and played with cooldowns by
# the fire module (fire sites, mourners), never by the random ambience engine.
ev("music.helgen_aftermath", ["music/helgen_aftermath"], "music", stream=True)
FIRE_ONESHOTS = [
    ("fire_crackle", "fire_crackle", 3, "Fire crackles", 24),
    ("fire_roar", "fire_roar", 2, "Fire roars", 40),
    ("timber_creak", "timber_creak", 3, "Timber creaks", 28),
    ("timber_collapse", "timber_collapse", 2, "Timber collapses", 48),
    ("sobbing", "sobbing", 4, "Someone sobs", 14),
    ("mourning", "mourning", 2, "Someone keens", 32),
]
for name, stem, count, sub, dist in FIRE_ONESHOTS:
    ev(f"ambient.{name}", n(f"ambient/{stem}", count), "ambient", sub, distance=dist)


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
    # ...and the other way round: every event Java registers needs a catalogue entry, or Minecraft logs "Not having sound
    # event for" and the event plays nothing. Rebuild AudioSounds' computed names from its arrays and the Shout enum.
    audio = (JAVA / "audio/AudioSounds.java").read_text(encoding="utf-8")

    def arr(name):
        m = re.search(r"\b" + name + r"\s*=\s*\{(.*?)\};", audio, re.S)
        return m.group(1) if m else ""
    java_names = set(registered)
    for s in re.findall(r'"([a-z0-9_]+)"', arr("MUSIC_SETS")):
        java_names |= {f"music.explore.{s}", f"music.explore_night.{s}"}
    java_names |= {f"music.town.{s}" for s in re.findall(r'"([a-z0-9_]+)"', arr("TOWN_SETS"))}
    java_names |= {f"ambient.bed.{s}" for s in re.findall(r'"([a-z0-9_]+)"', arr("BEDS"))}
    java_names |= {f"ambient.{s}" for s in re.findall(r'"([a-z0-9_]+)"', arr("ONESHOTS"))}
    java_names |= {f"spell.{s}" for s in re.findall(r'"([a-z0-9_]+)"', arr("SPELLS"))}
    java_names |= {f"ui.{s}" for s in re.findall(r'"([a-z0-9_]+)"', arr("UI"))}
    for table in ("CREATURES", "BESTIARY"):
        for row in re.findall(r"\{([^{}]*)\}", arr(table)):
            words = re.findall(r'"([a-z0-9_]+)"', row)
            java_names |= {f"entity.{words[0]}.{k}" for k in words[1:]}
    if shout_java.exists():
        for c in re.findall(r"^\s*([A-Z_]+)\(new String", shout_java.read_text(encoding="utf-8"), re.M):
            java_names |= {f"shout.{c.lower()}.{k}" for k in (1, 2, 3)}
    catalogued = {e.name for e in EVENTS}
    for name in sorted(java_names - catalogued):
        print(f"  registered in Java but not catalogued (no sounds.json entry): {name}")
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
