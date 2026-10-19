"""Generates the named places of every province outside Skyrim (and a few in the foreign lands): the towns, forts,
ruins, caves, tombs and wonders of Cyrodiil, Morrowind, Black Marsh, Elsweyr, Valenwood, Summerset, Hammerfell,
High Rock, Atmora, Akavir, Yokuda, Pyandonea and Thras.

Usage: py -3 tools/provinces.py [--preview] [--grid] [--probe X Z] [--check PROVINCE] [--ingest DIR]... [--no-fill]
                                [--dry]

  (no flag)       validate and write the places files and the lang file
  --preview       also render tools/worldgen/preview_places*.png (every named place over the map) to check by eye
  --grid          render tools/worldgen/grid_<province>.png: a coordinate grid over each province, for authoring
  --probe X Z     print what the map says at block (X, Z) (region, height, coast, water) and exit
  --check PROV    place one province's authored rows and report moves and drops; writes nothing
  --ingest DIR    also take reference map markers from DIR/markers.json (the owner's game data, already in block
                  coordinates; see ingest_rows): authored rows move to their reference positions and the other
                  markers become places, up to the density target. Repeatable (oblivion_ref, morrowind_ref, ...).
  --no-fill       only the authored places (skip the generated minor sites that bring each province to Skyrim's density)
  --dry           run everything but write nothing

Sources (all hand-authored facts: names, kinds, rough relative positions; the descriptions are original text):
  tools/provinces_data/<province>.py   PLACES = [(name, kind, x, z, region, description[, options]), ...]

How a place is placed: the authored point is checked against the map rasters (mod resources, the same data the game
reads): it must be dry land of the place's province, clear of the sea, lakes and rivers, and at least 64 blocks
(more for settlements: their radii plus a margin) from every other named place, including Skyrim's, the province
capitals and the Daedric shrines. A point that fails is moved to the nearest spot that passes, preferring the region
it was authored for; the region written is always the one the map has there. Places of the foreign lands are checked
against a port of the game's Beyond noise (terrain/Beyond.java, MapNoise.java).

Then each province is topped up towards Skyrim's density (one named place per ~60,000 square blocks of land) with
minor sites -- caves, camps, farms, towers, tombs -- named in the province's own idiom, as Daggerfall did for the
Iliac Bay. They are seeded and stable: re-running gives the same places.

Outputs (relative to the repo root):
  mod/src/main/resources/nirncraft/tamriel/places/<province>.json  (cyrodiil, morrowind, ..., foreign)
  mod/src/main/resources/nirncraft/tamriel/places/index.json       (the files appended if missing)
  mod/src/main/lang/nirncraft/provinces.json                       place names and one-line descriptions
The structures that build the new kinds (culture ruins, regional keeps and towers) are written by
`py -3 tools/landmarks.py --structures` (its PROVINCE_SITES table); check_builders() warns about any kind nothing builds.
"""
import importlib.util
import json
import math
import os
import random
import re
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
MAPDIR = os.path.join(RES, "nirncraft", "tamriel")
PLACES_DIR = os.path.join(MAPDIR, "places")
QUEST_PLACES = os.path.join(RES, "nirncraft", "quests", "places.json")
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "nirncraft", "provinces.json")
DATA_DIR = os.path.join(ROOT, "tools", "provinces_data")
PREVIEW_DIR = os.path.join(ROOT, "tools", "worldgen")

PROVINCES = ["cyrodiil", "morrowind", "black_marsh", "elsweyr", "valenwood", "summerset", "hammerfell", "high_rock",
             "foreign"]
FOREIGN = {"atmora", "akavir", "yokuda", "pyandonea", "thras"}
GATES_FILE = "oblivion_gates.json"  # Oblivion's gates (from reference data): pins for the realms workstream to build
OUR_FILES = [p + ".json" for p in PROVINCES] + [GATES_FILE]

CULTURE = {"cyrodiil": "imperial", "morrowind": "dunmer", "high_rock": "breton", "hammerfell": "redguard",
           "black_marsh": "argonian", "elsweyr": "khajiit", "valenwood": "bosmer", "summerset": "altmer",
           "atmora": "atmoran", "akavir": "akaviri", "yokuda": "yokudan", "pyandonea": "maormer", "thras": "sload"}

# Skyrim's density: about 430 named places on 26 million square blocks of land
DENSITY = 60000

# ---------------------------------------------------------------------------------------------------------------------
# kinds: what an authored "kind" builds -> (place type, variant, radius, flags)
#   flags: C = may stand at the water's edge (sea, lake or river shore), S = settlement (flat, not on peaks),
#          W = may stand by water (bridges, wrecks; same check as C)
#          P = pin only (lies inside another place, e.g. a city district: nothing is built for it)

KINDS = {
    # settlements (nirncraft:settlement with the culture kit; variants pick special layouts)
    "city": ("city", "", 110, "S"),
    "town": ("town", "", 60, "S"),
    "village": ("village", "", 42, "S"),
    "hamlet": ("village", "", 28, "S"),
    "farm": ("farm", "", 28, "S"),
    "homestead": ("farm", "homestead", 30, "S"),
    "stables": ("farm", "stables", 26, "S"),
    "estate": ("farm", "estate", 34, "S"),        # manors, priories, plantations, chapterhouses
    "lodge": ("farm", "lodge", 26, "S"),
    "inn": ("village", "inn", 24, "S"),
    "mill": ("village", "mill", 32, "S"),
    "mining_town": ("town", "mining", 50, "S"),
    "mining_village": ("village", "mining", 40, "S"),
    "stronghold": ("village", "stronghold", 38, "S"),   # Orc strongholds
    "telvanni": ("village", "telvanni", 40, "S"),       # a Telvanni wizard's tower town
    "port": ("town", "", 60, "SC"),                     # harbour towns on the shore
    "fishing_village": ("village", "", 40, "SC"),
    # forts (theme fort; keep = the region's own masonry)
    "fort": ("fort", "fort", 22, ""),
    "fort_vampire": ("fort", "fort_vampire", 22, ""),
    "fort_mage": ("fort", "fort_mage", 22, ""),
    "fort_forsworn": ("fort", "fort_forsworn", 22, ""),
    "keep": ("fort", "keep", 24, ""),
    "akaviri_fort": ("fort", "akaviri_fort", 26, ""),
    # caves, lairs, camps
    "cave": ("dungeon_fixed", "cave", 18, ""),
    "den": ("dungeon_fixed", "den", 18, ""),
    "bandit_cave": ("dungeon_fixed", "bandit_cave", 18, ""),
    "spider_cave": ("dungeon_fixed", "spider_cave", 18, ""),
    "troll_cave": ("dungeon_fixed", "troll_cave", 18, ""),
    "smuggler_cave": ("dungeon_fixed", "smuggler_cave", 18, "C"),
    "skeever_cave": ("dungeon_fixed", "skeever_cave", 18, ""),
    "spriggan_cave": ("dungeon_fixed", "spriggan_cave", 18, ""),
    "ice_cave": ("dungeon_fixed", "ice_cave", 18, ""),
    "vampire_lair": ("dungeon_fixed", "vampire_lair", 18, ""),
    "necromancer_lair": ("dungeon_fixed", "necromancer_lair", 18, ""),
    "hagraven_lair": ("dungeon_fixed", "hagraven_lair", 18, ""),
    "mine": ("dungeon_fixed", "mine", 20, ""),
    "egg_mine": ("dungeon_fixed", "egg_mine", 20, ""),         # kwama egg mines (Morrowind's mine kit)
    "sixth_house": ("dungeon_fixed", "sixth_house_base", 18, ""),  # Dagoth caves of the Sixth House
    "dwemer": ("dungeon_fixed", "dwemer_ruin", 32, ""),
    "shipwreck": ("dungeon_fixed", "shipwreck", 14, "CW"),
    "grove": ("dungeon_fixed", "grove", 16, ""),
    "bandit_camp": ("dungeon_fixed", "bandit_camp", 14, ""),
    "legion_camp": ("dungeon_fixed", "imperial_camp", 18, ""),
    "cabin": ("dungeon_fixed", "cabin", 10, ""),
    "lighthouse": ("dungeon_fixed", "lighthouse", 10, "C"),
    "watchtower": ("dungeon_fixed", "watchtower", 12, ""),
    "tower": ("dungeon_fixed", "tower", 12, ""),            # the region's own tower (Velothi, Breton, Redguard...)
    "mage_tower": ("dungeon_fixed", "mage_tower", 10, ""),
    "giant_camp": ("dungeon_fixed", "giant_camp", 16, ""),
    "dragon_lair": ("dungeon_fixed", "dragon_lair", 16, ""),
    "nordic_ruin": ("dungeon_fixed", "nordic_ruin", 12, ""),
    "barrow": ("dungeon_fixed", "barrow", 26, ""),
    # culture ruins (theme ruin, dressed by the culture's kit)
    "ayleid": ("dungeon_fixed", "ayleid_ruin", 26, ""),
    "velothi_tomb": ("dungeon_fixed", "velothi_tomb", 22, ""),
    "daedric_ruin": ("dungeon_fixed", "daedric_ruin", 26, ""),
    "xanmeer": ("dungeon_fixed", "xanmeer", 26, ""),
    "moon_temple": ("dungeon_fixed", "moon_temple", 24, ""),
    "tree_tomb": ("dungeon_fixed", "tree_tomb", 22, ""),
    "crystal_vault": ("dungeon_fixed", "crystal_vault", 24, ""),
    "yokudan_tomb": ("dungeon_fixed", "yokudan_tomb", 24, ""),
    "akaviri_ruin": ("dungeon_fixed", "akaviri_ruin", 24, ""),
    "atmoran_tomb": ("dungeon_fixed", "atmoran_tomb", 24, ""),
    "sea_vault": ("dungeon_fixed", "sea_vault", 24, "C"),
    "coral_pit": ("dungeon_fixed", "coral_pit", 24, "C"),
    "breton_crypt": ("dungeon_fixed", "breton_crypt", 22, ""),
    "orc_ruin": ("dungeon_fixed", "orc_ruin", 22, ""),
    "imperial_crypt": ("dungeon_fixed", "imperial_crypt", 22, ""),
    # landmarks (surface wonders) and pins
    "monument": ("landmark", "monument", 12, ""),
    "stones": ("landmark", "stones", 12, ""),
    "ruin": ("landmark", "ruin", 12, ""),
    "peak": ("landmark", "peak", 12, ""),
    "pass": ("landmark", "pass", 12, ""),
    "clearing": ("landmark", "clearing", 12, ""),
    "dwemer_landmark": ("landmark", "dwemer", 12, ""),
    "bridge": ("landmark", "bridge", 12, "W"),
    "altar": ("landmark", "altar", 12, ""),
    "rock": ("landmark", "rock", 12, ""),
    "waterfall": ("landmark", "waterfall", 12, ""),
    "eyrie": ("landmark", "eyrie", 12, ""),
    "island": ("landmark", "island", 12, "C"),
    "source": ("landmark", "source", 12, ""),
    "campsite": ("landmark", "campsite", 12, ""),
    "district": ("landmark", "district", 8, "P"),
}
for _prince in ["azura", "boethiah", "clavicus_vile", "hermaeus_mora", "hircine", "malacath", "mehrunes_dagon",
                "mephala", "meridia", "molag_bal", "namira", "nocturnal", "peryite", "sanguine", "sheogorath", "vaermina"]:
    KINDS["shrine_" + _prince] = ("daedric_shrine", _prince, 12, "")

# Every kind above must be built by a nirncraft:places structure set (tools/landmarks.py writes the dungeon ones,
# including the culture ruins, keeps and towers these provinces need: PROVINCE_SITES); check_builders() verifies it.


def check_builders():
    """Warns about kinds whose (type, variant) no nirncraft:places structure set builds (district pins excepted)."""
    sets = os.path.join(RES, "data", "nirncraft", "worldgen", "structure_set")
    rules = []
    for dirpath, _, names in os.walk(sets):
        for n in names:
            if n.endswith(".json"):
                pl = json.load(open(os.path.join(dirpath, n), encoding="utf-8")).get("placement", {})
                if pl.get("type") == "nirncraft:places":
                    rules.append((set(pl.get("types", [])), set(pl.get("variants", []))))
    for kind, (ptype, variant, _, flags) in sorted(KINDS.items()):
        if "P" in flags:
            continue
        if not any(ptype in t and (not v or variant in v) for t, v in rules):
            print(f"  WARNING kind {kind}: nothing builds {ptype}/{variant}")


def slug(name):
    s = name.lower().replace("'", "").replace("’", "")
    for a, b in (("á", "a"), ("é", "e"), ("í", "i"), ("ó", "o"), ("ú", "u"), ("ä", "a"),
                 ("ö", "o"), ("ü", "u"), ("ë", "e"), ("ï", "i")):
        s = s.replace(a, b)
    return re.sub(r"[^a-z0-9]+", "_", s).strip("_")


def write_json(path, obj, indent=1):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=indent)
        f.write("\n")


# ---------------------------------------------------------------------------------------------------------------------
# the map: the Tamriel rasters (TamrielMap / MapRaster)

class TamrielRasters:
    CELL, MIN_X, MIN_Z = 32, -15360, -5120

    def __init__(self):
        self.reg = np.array(Image.open(os.path.join(MAPDIR, "regions.png")))
        self.elev = np.array(Image.open(os.path.join(MAPDIR, "elevation.png"))).astype(np.float64) / 32 - 64
        self.coast = np.array(Image.open(os.path.join(MAPDIR, "coast.png"))).astype(np.float64) - 32768
        feat = np.array(Image.open(os.path.join(MAPDIR, "features.png")).convert("RGBA")).astype(np.float64)
        self.river = (255 - feat[..., 0]) * 0.5   # blocks to the nearest river centre line (127 = far)
        self.lake = (feat[..., 2] - 128) * 0.5     # signed: > 0 inside a lake
        self.H, self.W = self.reg.shape
        self.regions = json.load(open(os.path.join(MAPDIR, "regions.json"), encoding="utf-8"))
        self.by_index = {r["index"]: r for r in self.regions}
        self.by_id = {r["id"]: r for r in self.regions}
        self.max_x = self.MIN_X + self.W * self.CELL
        self.max_z = self.MIN_Z + self.H * self.CELL
        # dry, inland cells: no sea within 64 blocks, above y 67, no lake or river close by
        land = (self.coast >= 64) & (self.elev >= 68) & (self.lake <= -24) & (self.river >= 24)
        dry = land.copy()
        for dz in (-1, 0, 1):
            for dx in (-1, 0, 1):
                dry &= np.roll(np.roll(land, dz, 0), dx, 1)
        # marsh regions (Black Marsh, Blackwood, the Bitter Coast) lie barely above the water among pools: there any
        # firm ground that is not itself a pool or a channel will do
        wet = [r["index"] for r in self.regions if (r.get("terrain") or {}).get("wet", 0) >= 0.4]
        marsh = np.isin(self.reg, wet) & (self.coast >= 48) & (self.elev >= 64.2) & (self.lake <= -10) & (self.river >= 14)
        self.dry = dry | marsh
        # shore cells: land at the water's edge -- the sea, a lake or a river (harbours, lighthouses, lakeside hamlets)
        self.shore = (self.coast > 12) & (self.elev >= 64.5) & (self.lake <= -8) & (self.river >= 12)
        # steepness: the largest height step to a neighbour cell
        e = self.elev
        st = np.zeros_like(e)
        for dz, dx in ((0, 1), (1, 0), (1, 1), (1, -1)):
            st = np.maximum(st, np.abs(e - np.roll(np.roll(e, dz, 0), dx, 1)))
            st = np.maximum(st, np.abs(e - np.roll(np.roll(e, -dz, 0), -dx, 1)))
        self.steep = st

    def inside(self, x, z):
        return self.MIN_X <= x < self.max_x and self.MIN_Z <= z < self.max_z

    def cell(self, x, z):
        return int((x - self.MIN_X) // self.CELL), int((z - self.MIN_Z) // self.CELL)

    def region(self, x, z):
        if not self.inside(x, z):
            return None
        cx, cz = self.cell(x, z)
        return self.by_index.get(int(self.reg[cz, cx]))

    def ok(self, x, z, flags):
        """Whether a place with these kind flags may stand at (x, z); returns the region dict or None."""
        if not self.inside(x, z):
            return None
        cx, cz = self.cell(x, z)
        if not (2 <= cx < self.W - 2 and 2 <= cz < self.H - 2):
            return None
        r = self.by_index.get(int(self.reg[cz, cx]))
        if r is None or r["kind"] == "sea" or r["province"] == "sea":
            return None
        if "P" in flags:
            return r if self.coast[cz, cx] > 0 else None
        if "C" in flags or "W" in flags:
            good = self.shore[cz, cx] or self.dry[cz, cx]
        else:
            good = self.dry[cz, cx]
        if not good:
            return None
        if "S" in flags and (self.elev[cz, cx] > 215 or self.steep[cz, cx] > 18):
            return None
        return r

    def describe(self, x, z):
        if not self.inside(x, z):
            f = Beyond.land_at(x, z)
            return f"outside the raster: foreign land {f}" if f else "outside the raster: open sea"
        cx, cz = self.cell(x, z)
        r = self.by_index.get(int(self.reg[cz, cx]))
        return (f"region {r['id'] if r else '?'}  y {self.elev[cz, cx]:.0f}  coast {self.coast[cz, cx]:.0f}  "
                f"river {self.river[cz, cx]:.0f}  lake {self.lake[cz, cx]:.0f}  steep {self.steep[cz, cx]:.0f}  "
                f"dry {bool(self.dry[cz, cx])}  shore {bool(self.shore[cz, cx])}")


# ---------------------------------------------------------------------------------------------------------------------
# the foreign lands: a port of terrain/MapNoise.java and the fixed lands of terrain/Beyond.java

def _i32(v):
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v & 0x80000000 else v


def _hash(x, z, seed):
    h = _i32(x * 374761393 + z * 668265263 + seed * 1442695041)
    h = _i32((h ^ ((h & 0xFFFFFFFF) >> 13)) * 1274126177)
    return _i32(h ^ ((h & 0xFFFFFFFF) >> 16))


def _lattice(x, z, seed):
    return (_hash(x, z, seed) & 0xFFFFFF) / float(0x7FFFFF) - 1.0


def _value(x, z, scale, seed):
    fx, fz = x / scale, z / scale
    ix, iz = math.floor(fx), math.floor(fz)
    tx, tz = fx - ix, fz - iz
    tx = tx * tx * (3 - 2 * tx)
    tz = tz * tz * (3 - 2 * tz)
    a, b = _lattice(ix, iz, seed), _lattice(ix + 1, iz, seed)
    c, d = _lattice(ix, iz + 1, seed), _lattice(ix + 1, iz + 1, seed)
    ab = a + (b - a) * tx
    return ab + (c + (d - c) * tx - ab) * tz


def _fbm(x, z, scale, octaves, seed):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        tot += _value(x, z, scale, seed + o * 31) * amp
        norm += amp
        amp *= 0.5
        scale *= 0.5
    return tot / norm


class Beyond:
    # name, shape, cx, cz, rx, rz, seed, island scale
    LANDS = [("atmora", "C", 0, -11500, 4300, 2600, 11, 0), ("akavir", "C", 17600, 2800, 3300, 4900, 12, 0),
             ("tang_mo", "A", 22200, 5200, 2000, 2000, 13, 520), ("yokuda", "C", -19800, 6300, 3200, 4300, 14, 0),
             ("sunken_isles", "A", -18300, 11300, 2100, 2100, 15, 600),
             ("pyandonea", "A", -19300, 20600, 3000, 3000, 16, 1100), ("thras", "A", -15200, 17000, 1500, 1500, 17, 420)]

    @staticmethod
    def field(land, x, z):
        name, shape, cx, cz, rx, rz, seed, isl = land
        dx, dz = x - cx, z - cz
        if shape == "C":
            e = math.sqrt((dx / rx) ** 2 + (dz / rz) ** 2)
            r = math.sqrt(rx * rz)
            return (1 - e) * r + _fbm(x, z, 1700, 3, seed) * 700 + _fbm(x, z, 320, 2, seed + 5) * 130
        d2 = (dx * dx + dz * dz) / (rx * rz)
        g = _fbm(x, z, isl, 3, seed) + 0.5 * (1 - d2) - 0.36
        return g * isl * 0.6

    @staticmethod
    def region(land, x, z):
        name, shape, cx, cz = land[:4]
        if name == "atmora":
            return "atmora/ice_waste" if (z - cz) + _fbm(x, z, 900, 2, 61) * 700 < -250 else "atmora/frozen_forest"
        if name == "akavir":
            u = x - cx + _fbm(x, z, 900, 2, 62) * 600
            v = z - cz + _fbm(x, z, 900, 2, 63) * 600
            if v < -1500:
                return "akavir/kamal"
            if u < -200 and v > -300:
                return "akavir/tsaesci"
            return "akavir/ka_po_tun"
        return {"tang_mo": "akavir/tang_mo", "yokuda": "yokuda/yokudan_desert", "sunken_isles": "yokuda/sunken_isles",
                "pyandonea": "pyandonea/maormer_isles", "thras": "thras/coral_kingdom"}[name]

    @classmethod
    def land_at(cls, x, z, margin=0.0):
        """(region id, coast distance) of the foreign land at (x, z) when it is land by more than margin blocks."""
        best = None
        for land in cls.LANDS:
            reach = max(land[4], land[5]) * 1.5 + 1200
            if (x - land[2]) ** 2 + (z - land[3]) ** 2 > reach * reach:
                continue
            f = cls.field(land, x, z)
            if best is None or f > best[1]:
                best = (land, f)
        if best is None or best[1] <= margin:
            return None
        return cls.region(best[0], x, z), best[1]


# ---------------------------------------------------------------------------------------------------------------------
# existing places (Skyrim, capitals, shrines, quests): ids to avoid, footprints to keep clear of

def existing_places():
    index = json.load(open(os.path.join(PLACES_DIR, "index.json"), encoding="utf-8"))
    out = []
    for f in index:
        if f in OUR_FILES:
            continue
        for p in json.load(open(os.path.join(PLACES_DIR, f), encoding="utf-8")):
            out.append(p)
    return out


def quest_ids():
    try:
        d = json.load(open(QUEST_PLACES, encoding="utf-8"))
        return set(d.keys()) if isinstance(d, dict) else set()
    except OSError:
        return set()


def load_data(prov):
    path = os.path.join(DATA_DIR, prov + ".py")
    if not os.path.exists(path):
        return []
    spec = importlib.util.spec_from_file_location("provinces_data_" + prov, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return list(mod.PLACES)


# ---------------------------------------------------------------------------------------------------------------------
# placing

class Placer:
    def __init__(self, cells, verbose=False):
        self.cells = cells
        self.verbose = verbose
        self.placed = []      # (x, z, radius)
        self.grid = {}        # 256-block buckets of placed indices
        self.ids = set()
        self.moves = []
        self.dropped = []
        self.ingest_skipped = 0
        self.region_names = json.load(open(os.path.join(ROOT, "mod", "src", "main", "lang", "nirncraft",
                                                        "tamriel_regions.json"), encoding="utf-8"))

    def add(self, x, z, r):
        i = len(self.placed)
        self.placed.append((x, z, r))
        self.grid.setdefault((x >> 8, z >> 8), []).append(i)

    def clear(self, x, z, r, extra=0):
        """True when a place of radius r at (x, z) keeps its distance from every placed one."""
        reach = 2
        for gx in range((x >> 8) - reach, (x >> 8) + reach + 1):
            for gz in range((z >> 8) - reach, (z >> 8) + reach + 1):
                for i in self.grid.get((gx, gz), ()):
                    px, pz, pr = self.placed[i]
                    need = max(64, r + pr + 24) + extra
                    if (px - x) ** 2 + (pz - z) ** 2 < need * need:
                        return False
        return True

    def valid(self, x, z, flags, prov, want_region):
        if prov in FOREIGN or not self.cells.inside(x, z):
            got = Beyond.land_at(x, z, margin=24 if ("C" in flags or "W" in flags) else 96)
            if got is None or got[0].split("/")[0] != prov:
                return None
            if want_region and got[0] != want_region:
                return None
            return got[0]
        r = self.cells.ok(x, z, flags)
        if r is None or r["province"] != prov:
            return None
        if want_region and r["id"] != want_region:
            return None
        return r["id"]

    def find(self, x, z, radius, flags, prov, region, max_move):
        """The nearest valid spot to (x, z) within max_move (intended region first): (x, z, region id) or None."""
        for want in ([region, None] if region else [None]):
            for d in range(0, max_move + 1, 16):
                n = 1 if d == 0 else max(8, int(2 * math.pi * d / 16))
                start = (hash((x, z)) % 360) * math.pi / 180
                for k in range(n):
                    a = start + k * 2 * math.pi / n
                    tx, tz = int(round(x + math.cos(a) * d)), int(round(z + math.sin(a) * d))
                    reg = self.valid(tx, tz, flags, prov, want)
                    if reg is None:
                        continue
                    if "P" not in flags and not self.clear(tx, tz, radius):
                        continue
                    return tx, tz, reg
        return None


def build_entry(prov, row, placer, used_ids, lang, out, default_culture):
    name, kind, x, z, region, desc = row[:6]
    opts = dict(row[6]) if len(row) > 6 else {}
    if kind not in KINDS:
        raise SystemExit(f"{prov}: unknown kind {kind!r} for {name}")
    ptype, variant, radius, flags = KINDS[kind]
    variant = opts.get("variant", variant)
    radius = opts.get("radius", radius)
    flags = flags + opts.get("flags", "")
    pid = opts.get("id") or slug(name)
    if pid in used_ids:
        # same name elsewhere (e.g. a Skyrim cave of the same name): keep it unique with the province
        alt = pid + "_" + (region.split("/")[0] if "/" in region else prov)
        if alt in used_ids:
            print(f"  DUP {prov}: {name} ({pid}) skipped")
            return None
        pid = alt
    land_prov = region.split("/")[0] if "/" in region else prov
    # a bare province (ingested markers) means "whichever region the map has there"
    region_id = region if "/" in region else (None if region == prov else f"{land_prov}/{region}")
    ingested = opts.get("ingested", False)
    max_move = opts.get("move", 640 if land_prov in FOREIGN else 480)
    spot = placer.find(int(x), int(z), radius, flags, land_prov, region_id, max_move)
    if spot is None and not ingested:
        spot = placer.find(int(x), int(z), radius, flags, land_prov, None, max_move * 2)
    if spot is None:
        if ingested:
            placer.ingest_skipped += 1
        else:
            placer.dropped.append((prov, name, x, z, region_id))
        return None
    tx, tz, got = spot
    moved = math.hypot(tx - x, tz - z)
    if not ingested and (moved > 200 or (region_id and got != region_id)):
        placer.moves.append((prov, name, x, z, tx, tz, region_id, got, moved))
    if "P" not in flags:
        placer.add(tx, tz, radius)
    used_ids.add(pid)
    reg = placer.cells.by_id.get(got)
    culture = opts.get("culture") or (reg["culture"] if reg and reg.get("culture") else default_culture)
    e = {"id": pid, "name": "place.nirncraft." + pid, "type": ptype, "culture": culture, "region": got,
         "x": tx, "z": tz, "radius": radius}
    if variant:
        e["variant"] = variant
    out.append(e)
    lang["place.nirncraft." + pid] = name
    if not desc and ingested:
        rname = placer.region_names.get(reg["name"], got.split("/")[1].replace("_", " ").title()) if reg else ""
        desc = minor_module().describe(culture, kind, rname, random.Random(pid))
    if desc:
        lang["place.nirncraft." + pid + ".desc"] = desc
    return e


# ---------------------------------------------------------------------------------------------------------------------
# pictures: authoring grids and previews

def _font(size):
    for f in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def _relief(cells, x0, z0, x1, z1, bpp):
    xs = np.arange(x0, x1, bpp) + bpp / 2
    zs = np.arange(z0, z1, bpp) + bpp / 2
    ix = np.clip(((xs - cells.MIN_X) // cells.CELL).astype(int), 0, cells.W - 1)
    iz = np.clip(((zs - cells.MIN_Z) // cells.CELL).astype(int), 0, cells.H - 1)
    IX, IZ = np.meshgrid(ix, iz)
    gx, gz = np.meshgrid(xs, zs)
    inside = (gx >= cells.MIN_X) & (gx < cells.max_x) & (gz >= cells.MIN_Z) & (gz < cells.max_z)
    h = cells.elev[IZ, IX]
    sea = (cells.coast[IZ, IX] <= 0) | ~inside
    lake = cells.lake[IZ, IX] > 0
    river = cells.river[IZ, IX] < 7
    stops = [(60, (95, 150, 80)), (90, (110, 160, 85)), (130, (90, 130, 70)), (170, (140, 125, 90)),
             (220, (150, 140, 125)), (280, (200, 200, 200)), (400, (250, 250, 255))]
    rgb = np.zeros(h.shape + (3,))
    rgb[:] = stops[0][1]
    for (h0, c0), (h1, c1) in zip(stops[:-1], stops[1:]):
        m = (h >= h0) & (h < h1)
        t = ((h - h0) / (h1 - h0))[m][:, None]
        rgb[m] = np.array(c0) * (1 - t) + np.array(c1) * t
    rgb[h >= stops[-1][0]] = stops[-1][1]
    gy, gxx = np.gradient(h)
    rgb *= np.clip(0.85 + (-gxx - gy) * 0.05 * (32 / bpp) ** 0.3, 0.55, 1.2)[..., None]
    rgb[sea] = (70, 110, 170)
    rgb[lake | (river & ~sea)] = (80, 130, 200)
    reg = cells.reg[IZ, IX]
    e = np.zeros(reg.shape, bool)
    e[:, 1:] |= reg[:, 1:] != reg[:, :-1]
    e[1:, :] |= reg[1:, :] != reg[:-1, :]
    rgb[e & ~sea] = rgb[e & ~sea] * 0.45
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)), reg


def province_bounds(cells, prov):
    idx = [r["index"] for r in cells.regions if r["province"] == prov]
    m = np.isin(cells.reg, idx) & (cells.coast > 0)
    zz, xx = np.nonzero(m)
    return (cells.MIN_X + xx.min() * cells.CELL - 300, cells.MIN_Z + zz.min() * cells.CELL - 300,
            cells.MIN_X + (xx.max() + 1) * cells.CELL + 300, cells.MIN_Z + (zz.max() + 1) * cells.CELL + 300)


COLORS = {"capital": (255, 40, 40), "city": (255, 90, 60), "town": (255, 150, 60), "village": (255, 210, 80),
          "farm": (230, 230, 140), "fort": (120, 120, 255), "dungeon_fixed": (60, 30, 30), "landmark": (40, 220, 220),
          "daedric_shrine": (200, 40, 200), "standing_stone": (255, 255, 255), "word_wall": (255, 255, 255),
          "dragon_mound": (255, 255, 255)}


def draw_places(img, places, x0, z0, bpp, label_types, lang=None, small=False):
    dr = ImageDraw.Draw(img)
    f = _font(11 if small else 12)
    for p in places:
        px, pz = (p["x"] - x0) / bpp, (p["z"] - z0) / bpp
        if not (0 <= px < img.width and 0 <= pz < img.height):
            continue
        c = COLORS.get(p["type"], (255, 255, 255))
        s = 4 if p["type"] in ("capital", "city") else (3 if p["type"] in ("town", "village", "fort") else 2)
        dr.ellipse([px - s, pz - s, px + s, pz + s], fill=c, outline=(0, 0, 0))
        if p["type"] in label_types:
            name = (lang or {}).get(p["name"], p["id"].replace("_", " ").title())
            dr.text((px + s + 2, pz - 7), name, fill=(255, 255, 255), font=f, stroke_width=2, stroke_fill=(0, 0, 0))


def grid_image(cells, prov, places, bpp=8):
    x0, z0, x1, z1 = province_bounds(cells, prov)
    x0, z0 = int(x0 // 250 * 250), int(z0 // 250 * 250)
    img, reg = _relief(cells, x0, z0, x1, z1, bpp)
    dr = ImageDraw.Draw(img)
    f = _font(11)
    for gx in range(x0, int(x1) + 1, 250):
        px = (gx - x0) / bpp
        dr.line([(px, 0), (px, img.height)], fill=(0, 0, 0) if gx % 1000 == 0 else (60, 60, 60), width=1)
        if gx % 500 == 0:
            for py in range(0, img.height, 300):
                dr.text((px + 2, py + 2), f"x{gx}", fill=(255, 255, 200), font=f, stroke_width=2, stroke_fill=(0, 0, 0))
    for gz in range(z0, int(z1) + 1, 250):
        pz = (gz - z0) / bpp
        dr.line([(0, pz), (img.width, pz)], fill=(0, 0, 0) if gz % 1000 == 0 else (60, 60, 60), width=1)
        if gz % 500 == 0:
            for px in range(0, img.width, 300):
                dr.text((px + 2, pz + 2), f"z{gz}", fill=(200, 255, 255), font=f, stroke_width=2, stroke_fill=(0, 0, 0))
    fr = _font(14)
    for r in cells.regions:
        if r["kind"] == "sea":
            continue
        m = reg == r["index"]
        if m.sum() < 30:
            continue
        zz, xx = np.nonzero(m)
        dr.text((xx.mean() - 40, zz.mean() + 10), r["id"], fill=(255, 255, 0), font=fr, stroke_width=2, stroke_fill=(0, 0, 0))
    draw_places(img, places, x0, z0, bpp, {"capital", "city", "town", "landmark"})
    path = os.path.join(PREVIEW_DIR, f"grid_{prov}.png")
    img.save(path)
    print("wrote", path, img.size, "bounds", (x0, z0, int(x1), int(z1)))


def foreign_image(land, places, bpp=48, prefix="grid_foreign_"):
    """The fixed foreign land (Beyond noise) with a coordinate grid: green = land (dark = interior), labels = places."""
    name, shape, cx, cz, rx, rz = land[:6]
    reach = int(max(rx, rz) * 1.5 + 1200)
    x0, z0 = int((cx - reach) // 500 * 500), int((cz - reach) // 500 * 500)
    x1, z1 = cx + reach, cz + reach
    w, h = int((x1 - x0) / bpp), int((z1 - z0) / bpp)
    img = Image.new("RGB", (w, h), (70, 110, 170))
    px = img.load()
    regions = {}
    for j in range(h):
        for i in range(w):
            x, z = x0 + (i + 0.5) * bpp, z0 + (j + 0.5) * bpp
            f = Beyond.field(land, x, z)
            if f > 0:
                reg = Beyond.region(land, x, z)
                tint = {"atmora/ice_waste": (225, 230, 240), "atmora/frozen_forest": (150, 175, 160),
                        "akavir/kamal": (230, 235, 245), "akavir/tsaesci": (60, 140, 60),
                        "akavir/ka_po_tun": (170, 160, 90)}.get(reg, (110, 160, 85))
                k = 0.75 if f > 96 else 1.0
                px[i, j] = tuple(int(c * k) for c in tint)
                regions.setdefault(reg, []).append((i, j))
    dr = ImageDraw.Draw(img)
    f = _font(11)
    for gx in range(x0, int(x1), 500):
        dr.line([((gx - x0) / bpp, 0), ((gx - x0) / bpp, h)], fill=(40, 40, 40))
        if gx % 1000 == 0:
            dr.text(((gx - x0) / bpp + 2, 2), f"x{gx}", fill=(255, 255, 200), font=f, stroke_width=2, stroke_fill=(0, 0, 0))
    for gz in range(z0, int(z1), 500):
        dr.line([(0, (gz - z0) / bpp), (w, (gz - z0) / bpp)], fill=(40, 40, 40))
        if gz % 1000 == 0:
            dr.text((2, (gz - z0) / bpp + 2), f"z{gz}", fill=(200, 255, 255), font=f, stroke_width=2, stroke_fill=(0, 0, 0))
    for reg, pts in regions.items():
        mi = sum(p[0] for p in pts) / len(pts)
        mj = sum(p[1] for p in pts) / len(pts)
        dr.text((mi - 30, mj), reg, fill=(255, 255, 0), font=_font(13), stroke_width=2, stroke_fill=(0, 0, 0))
    draw_places(img, places, x0, z0, bpp, {"capital", "city", "town", "village", "fort", "landmark", "dungeon_fixed"}, small=True)
    path = os.path.join(PREVIEW_DIR, f"{prefix}{name}.png")
    img.save(path)
    print("wrote", path, img.size, "bounds", (x0, z0, int(x1), int(z1)))


def probe(cells, x, z):
    print(f"({x}, {z}): {cells.describe(x, z)}")


# ---------------------------------------------------------------------------------------------------------------------

PRIORITY = {"capital": 0, "city": 0, "town": 1, "village": 2, "farm": 3, "fort": 4, "landmark": 5, "daedric_shrine": 5,
            "dungeon_fixed": 6}


# ---------------------------------------------------------------------------------------------------------------------
# ingesting reference extractions (--ingest <dir>): the owner's game data, already transformed to block coordinates.
# <dir>/markers.json holds map markers: a list (or {"markers": [...]}) of {"name", "type", "mcX", "mcZ"} (x/z also
# accepted). Only facts are taken: a marker's name, kind and position. A marker whose name matches an authored row moves
# that row to the marker's position; any other marker becomes a place of its own (with a generic original description),
# up to the province's density target. Positions still pass the map's land/region/spacing checks.

CULTURE_RUIN = {"cyrodiil": "ayleid", "morrowind": "velothi_tomb", "high_rock": "breton_crypt", "hammerfell": "yokudan_tomb",
                "black_marsh": "xanmeer", "elsweyr": "moon_temple", "valenwood": "tree_tomb", "summerset": "crystal_vault"}
CULTURE_CRYPT = {"cyrodiil": "imperial_crypt", "morrowind": "velothi_tomb", "high_rock": "breton_crypt",
                 "hammerfell": "yokudan_tomb", "black_marsh": "xanmeer", "elsweyr": "moon_temple", "valenwood": "tree_tomb",
                 "summerset": "crystal_vault"}
# (keyword in the marker's type or name, kind); the first match wins; None skips the marker
INGEST_RULES = [
    ("oblivion gate", None), ("daedric shrine", None), ("wayshrine", None), ("door", None), ("city", None),
    ("ayleid", "ayleid"), ("dwemer", "dwemer"), ("dwarven", "dwemer"), ("ancestral tomb", "velothi_tomb"),
    ("daedric", "daedric_ruin"), ("stronghold", "keep"), ("castle", "keep"), ("palace", "keep"), ("keep", "keep"),
    ("fort", "fort"), ("lighthouse", "lighthouse"), ("shipwreck", "shipwreck"), ("wreck", "shipwreck"),
    ("tower", "tower"), ("inn", "inn"), ("tavern", "inn"), ("plantation", "estate"), ("manor", "estate"),
    ("wealthy", "estate"), ("priory", "estate"), ("farm", "farm"), ("stable", "stables"), ("mill", "mill"),
    ("eggmine", "mine"), ("egg mine", "mine"), ("mine", "mine"), ("cave", "cave"), ("grotto", "cave"),
    ("cavern", "cave"), ("coven", "hagraven_lair"), ("crypt", "crypt"), ("graveyard", "crypt"), ("cemetery", "crypt"),
    ("tomb", "crypt"), ("barrow", "crypt"), ("labyrinth", "ruin_culture"), ("dungeon", "ruin_culture"),
    ("ruin", "ruin_culture"), ("camp", "bandit_camp"), ("grove", "grove"), ("temple", "estate"),
    ("large town", "town"), ("town", "village"), ("village", "hamlet"), ("settlement", "hamlet"), ("hamlet", "hamlet"),
    ("landmark", "monument"),
]


# marker types too broad to trust over the name (a "settlement" may be a farm, a "tavern" marker a stable)
VAGUE_TYPES = {"", "none", "settlement", "landmark", "tavern", "camp"}


# exact marker types (any game's extraction) -> kind; None skips; per-province entries override the shared ones
TYPE_KINDS = {
    "*": {"imperial fort": "fort", "district": "district", "house": None, "ship": None, "velothi tower": "tower",
          "telvanni tower": "telvanni", "sixth house base": "cave", "egg mine": "mine", "ancestral tomb": "velothi_tomb",
          "dwemer ruin": "dwemer", "ayleid ruin": "ayleid", "fort ruin": "fort", "plantation": "estate",
          "manor": "estate", "stronghold": "keep", "shipwreck": "shipwreck"},
    "morrowind": {"daedric shrine": "daedric_ruin", "camp": "hamlet", "town": "village", "barrow": "velothi_tomb",
                  "egg mine": "egg_mine", "sixth house base": "sixth_house", "telvanni tower": "mage_tower",
                  "district": None},  # Vivec's cantons belong to Vivec's own layout
}
# cultures of ingested kinds that differ from the region's (Ashlander camps)
TYPE_CULTURES = {("morrowind", "camp"): "ashlander"}


def classify(mtype, name, prov):
    mtype = str(mtype).replace("_", " ").lower()
    for table in (TYPE_KINDS.get(prov, {}), TYPE_KINDS["*"]):
        if mtype in table:
            return table[mtype]
    texts = [str(name).lower(), mtype] if mtype in VAGUE_TYPES else [mtype, str(name).lower()]
    for text in texts:
        for key, kind in INGEST_RULES:
            if key in text:
                if kind == "fort":
                    return "fort" if prov == "cyrodiil" else "keep"
                if kind == "crypt":
                    return CULTURE_CRYPT.get(prov)
                if kind == "ruin_culture":
                    return CULTURE_RUIN.get(prov)
                return kind
    return None


class Warp:
    """A thin-plate spline from game coordinates to block coordinates, passing EXACTLY through its anchors (the
    province's cities: their game positions -> their positions in docs/TAMRIEL.md section 9) and bending smoothly in
    between; beyond the anchors it continues as their best-fit affine map. Reusable for any game's reference data."""

    def __init__(self, src, dst):
        self.src = np.asarray(src, float)
        dst = np.asarray(dst, float)
        self.c = self.src.mean(axis=0)
        self.s = float(np.abs(self.src - self.c).max()) or 1.0
        p = (self.src - self.c) / self.s
        n = len(p)
        k = self._u(np.linalg.norm(p[:, None, :] - p[None, :, :], axis=2))
        P = np.hstack([np.ones((n, 1)), p])
        A = np.zeros((n + 3, n + 3))
        A[:n, :n] = k
        A[:n, n:] = P
        A[n:, :n] = P.T
        b = np.zeros((n + 3, 2))
        b[:n] = dst
        self.coef = np.linalg.solve(A, b)
        self.p = p

    @staticmethod
    def _u(r):
        with np.errstate(divide="ignore", invalid="ignore"):
            return np.where(r > 0, r * r * np.log(r), 0.0)

    def __call__(self, x, y):
        q = (np.array([x, y], float) - self.c) / self.s
        u = self._u(np.linalg.norm(self.p - q, axis=1))
        n = len(self.p)
        out = u @ self.coef[:n] + self.coef[n] + q[0] * self.coef[n + 1] + q[1] * self.coef[n + 2]
        return float(out[0]), float(out[1])


def ref_warp(d):
    """The warp of a reference dir, from transform.json's anchors (game xy of each city -> its anchor), or None."""
    path = os.path.join(d, "transform.json")
    if not os.path.exists(path):
        return None
    t = json.load(open(path, encoding="utf-8"))
    src, dst = [], []
    for a in t.get("anchors", []):
        g = a.get("game_xy") or a.get("obl_xy") or a.get("mw_xy") or a.get("df_xy")
        if g and a.get("target_mc"):
            src.append(g[:2])
            dst.append(a["target_mc"][:2])
    if len(src) < 3:
        return None
    w = Warp(src, dst)
    err = max(math.hypot(*(np.subtract(w(*s), t_))) for s, t_ in zip(src, dst))
    print(f"  ingest: thin-plate warp through {len(src)} anchors of {d} (max anchor error {err:.2f} blocks)")
    return w


# POI categories of the reference data that become small landmarks (category -> kind)
POI_KINDS = {"wayshrine": "altar", "ayleid_well": "source", "doomstone": "stones"}


def load_refs(dirs):
    out = []
    for d in dirs:
        warp = ref_warp(d)
        path = os.path.join(d, "markers.json")
        if not os.path.exists(path):
            print(f"  ingest: no markers.json in {d}, skipped")
            continue
        raw = json.load(open(path, encoding="utf-8"))
        data = raw
        if isinstance(raw, dict):
            data = raw.get("markers") or raw.get("locations") or []
        # keep the main exterior frame only (not Oblivion's planes, Shivering Isles or separate interior worlds)
        frames = [m.get("frame") for m in data if m.get("frame")]
        main_frame = max(set(frames), key=frames.count) if frames else None
        pois = raw.get("pois", []) if isinstance(raw, dict) else []
        n = 0
        counts = {}
        for m in data + [dict(p, type="poi:" + p.get("category", "")) for p in pois]:
            if main_frame and m.get("frame", main_frame) != main_frame:
                continue
            name = (m.get("name") or m.get("full") or "").strip()
            mtype = str(m.get("type_name", m.get("type", "")))
            if mtype.startswith("poi:"):
                cat = mtype[4:]
                if cat not in POI_KINDS or not name or name.lower() == "altar":
                    continue
            alt = (m.get("mc_alt") or {}).get("norot_centre_pinned")
            if alt:
                x, z = alt  # Solstheim: north-up, pinned at the island's centre (coordinator's choice)
            elif warp is not None and m.get("x") is not None and m.get("y") is not None:
                x, z = warp(m["x"], m["y"])        # game coordinates through the anchored warp
            elif m.get("mcX") is not None and m.get("mcZ") is not None:
                x, z = m["mcX"], m["mcZ"]
            else:
                continue
            if not name:
                continue
            e = {"name": name, "type": mtype, "x": int(round(x)), "z": int(round(z))}
            if m.get("our_region_id"):
                e["prov"] = m["our_region_id"].split("/")[0]  # the game's own province for it
            if mtype in ("poi:ayleid_well", "poi:wayshrine") or "oblivion_gate" in mtype:
                # many of a kind share a name (Ayleid Well, Wayshrine of Akatosh): number their ids
                base = "oblivion_gate" if "oblivion_gate" in mtype else slug(name)
                counts[base] = counts.get(base, 0) + 1
                e["id"] = f"{base}_{counts[base]}"
            out.append(e)
            n += 1
        # markers without a province of their own (POIs) belong to the data's main province
        provs = [e["prov"] for e in out[len(out) - n:] if e.get("prov")]
        if provs:
            main_prov = max(set(provs), key=provs.count)
            for e in out[len(out) - n:]:
                e.setdefault("prov", main_prov)
        print(f"  ingest: {n} markers from {path}")
    return out


def poi_description(m):
    """Original one-liners for the reference POIs (wayshrines, Ayleid wells, doomstones); empty for the rest."""
    name, t = m["name"], m["type"]
    if t == "poi:wayshrine" and " of " in name:
        return f"A roadside wayshrine to {name.split(' of ', 1)[1]}, where travellers stop to pray for a blessing."
    if t == "poi:ayleid_well":
        return "A carved Ayleid well-stone that gathers starlight and gives magicka to those who touch it."
    if t == "poi:doomstone":
        return f"An ancient doomstone; under the right sign, the {name} lends its power to those who call on it."
    return ""


def ingest_rows(cells, refs, prov, rows, used_ids, budget):
    """Moves authored rows to their reference positions and adds the other reference markers of this province."""
    by_slug = {(r[6].get("id") if len(r) > 6 and r[6].get("id") else slug(r[0])): i for i, r in enumerate(rows)}
    added, moved = [], 0
    for m in refs:
        r = cells.region(m["x"], m["z"])
        # the game data's own province wins (a warped point near a border may fall just across it)
        mprov = m.get("prov") or (r["province"] if r else None)
        if mprov != prov or (r is not None and r["id"] == "morrowind/solstheim"):
            continue
        if "oblivion_gate" in m["type"]:
            continue  # gates: see ingest_gates
        s = m.get("id") or slug(m["name"])
        if s in by_slug:
            i = by_slug[s]
            row = list(rows[i])
            ref_kind = classify(m["type"], m["name"], prov)
            if ref_kind in ("egg_mine", "sixth_house"):
                row[1] = ref_kind  # the reference knows what the authors wrote as a plain mine or cave
            authored_region = row[4] if "/" in row[4] else f"{prov}/{row[4]}"
            # the authored spot stays where the warp overshoots the province's border, or lands in another region
            # far away (e.g. across the Inner Sea)
            if r is not None and r["province"] == prov and (
                    r["id"] == authored_region or math.hypot(row[2] - m["x"], row[3] - m["z"]) <= 300):
                row[2], row[3], row[4] = m["x"], m["z"], prov
                moved += 1
            rows[i] = tuple(row)
            continue
        if s in used_ids or len(added) >= budget:
            continue
        if m["type"].startswith("poi:"):
            kind = POI_KINDS.get(m["type"][4:])
        else:
            kind = classify(m["type"], m["name"], prov)
        if kind is None or kind not in KINDS:
            continue
        by_slug[s] = len(rows) + len(added)
        near = r is not None and r["province"] == prov
        opts = {"move": 160 if near else 480, "ingested": True, "id": s}
        culture = TYPE_CULTURES.get((prov, str(m["type"]).replace("_", " ").lower().replace(" ", "_")))
        if culture:
            opts["culture"] = culture
        added.append((m["name"], kind, m["x"], m["z"], prov, poi_description(m), opts))
    if moved or added:
        print(f"  ingest {prov}: {moved} authored rows moved to reference positions, {len(added)} markers added")
    return rows + added


def curated(cells, placer, used_ids, lang, only=None, refs=()):
    """Places every authored row (and ingested reference markers); returns {file: [entries]}."""
    files = {p: [] for p in PROVINCES}
    for prov in PROVINCES:
        if only and prov != only:
            continue
        rows = load_data(prov)
        if refs and prov != "foreign":
            idx = [r["index"] for r in cells.regions if r["province"] == prov and r["id"] != "morrowind/solstheim"]
            area = int((np.isin(cells.reg, idx) & (cells.coast > 0)).sum()) * cells.CELL * cells.CELL
            rows = ingest_rows(cells, refs, prov, rows, used_ids, max(0, area // DENSITY - len(rows)))
        # kwama egg mines get the egg-mine site (authors wrote them as plain mines)
        rows = [(r[0], "egg_mine") + tuple(r[2:]) if r[1] == "mine" and any(k in r[0].lower() for k in
                ("egg mine", "eggmine", "kwama")) else r for r in rows]
        rows.sort(key=lambda r: PRIORITY.get(KINDS.get(r[1], ("dungeon_fixed",))[0], 9))
        for row in rows:
            region = row[4]
            land_prov = region.split("/")[0] if "/" in region else prov
            build_entry(prov, row, placer, used_ids, lang, files[prov], CULTURE.get(land_prov, "nord"))
    return files


FLAGS = {"--preview", "--grid", "--probe", "--check", "--ingest", "--no-fill", "--dry", "-v"}


def main(argv):
    unknown = [a for a in argv if a.startswith("-") and a not in FLAGS and not a.lstrip("-").isdigit()]
    if unknown:
        print(__doc__)
        print("unknown option(s):", " ".join(unknown))
        return 2
    cells = TamrielRasters()
    if "--probe" in argv:
        i = argv.index("--probe")
        probe(cells, int(argv[i + 1]), int(argv[i + 2]))
        return 0
    existing = existing_places()
    if "--grid" in argv:
        for prov in PROVINCES[:-1]:
            grid_image(cells, prov, existing)
        return 0
    check_builders()
    placer = Placer(cells, "-v" in argv)
    used_ids = {p["id"] for p in existing} | quest_ids()
    for p in existing:
        placer.add(p["x"], p["z"], p.get("radius", 32))
    lang = {}
    only = None
    if "--check" in argv:
        only = argv[argv.index("--check") + 1]
    refs = reference_markers([argv[i + 1] for i, a in enumerate(argv) if a == "--ingest" and i + 1 < len(argv)],
                             "--dry" not in argv and not only)
    files = curated(cells, placer, used_ids, lang, only, refs)
    if not only:
        files[GATES_FILE[:-5]] = ingest_gates(placer, refs, used_ids, lang)
    authored = {e["id"] for v in files.values() for e in v}
    for m in placer.moves:
        prov, name, x, z, tx, tz, want, got, d = m
        print(f"  moved {prov:10s} {name:32s} ({x},{z}) -> ({tx},{tz}) {d:5.0f} blocks" + ("" if want == got else f"  region {want} -> {got}"))
    for d in placer.dropped:
        print(f"  DROPPED {d[0]:10s} {d[1]:32s} ({d[2]},{d[3]}) {d[4]}: no valid spot nearby")
    if placer.ingest_skipped:
        print(f"  ingest: {placer.ingest_skipped} reference markers found no valid spot (water, crowded) and were skipped")
    counts = {p: len(v) for p, v in files.items()}
    print("authored places:", counts, "total", sum(counts.values()))
    if only:
        return 0
    if "--no-fill" not in argv:
        fill(cells, placer, used_ids, lang, files, existing)
    if "--dry" in argv:
        return 0
    write_outputs(files, lang)
    for d in [argv[i + 1] for i, a in enumerate(argv) if a == "--ingest" and i + 1 < len(argv)]:
        export_travel(d, existing + [e for v in files.values() for e in v])
    if "--preview" in argv:
        previews(cells, existing, files, lang, authored)
    return 0


_MINOR = []


def minor_module():
    """tools/provinces_data/minor.py: the word stock and kind tables of the minor sites."""
    if not _MINOR:
        spec = importlib.util.spec_from_file_location("provinces_minor", os.path.join(DATA_DIR, "minor.py"))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        _MINOR.append(mod)
    return _MINOR[0]


def fill(cells, placer, used_ids, lang, files, existing):
    """Tops each province up towards Skyrim's density with minor sites (tools/provinces_data/minor.py)."""
    minor = minor_module()
    region_lang = placer.region_names
    report = {}
    for prov in PROVINCES:
        if prov == "foreign":
            continue
        # Solstheim is politically Morrowind but its places are Skyrim's (skyrim_landmarks.json)
        idx = [r["index"] for r in cells.regions if r["province"] == prov and r["id"] != "morrowind/solstheim"]
        inprov = np.isin(cells.reg, idx)
        area = int((inprov & (cells.coast > 0)).sum()) * cells.CELL * cells.CELL
        target = area // DENSITY
        have = len(files[prov]) + sum(1 for p in existing if p.get("region", "").split("/")[0] == prov
                                      and p.get("region") != "morrowind/solstheim")
        have += sum(1 for p in files.get(GATES_FILE[:-5], []) if p["region"].split("/")[0] == prov)
        need = target - have
        rng = random.Random("nirncraft-provinces-" + prov)
        added = 0
        if need > 0:
            dry = np.argwhere(inprov & cells.dry)
            shore = np.argwhere(inprov & cells.shore & ~cells.dry & (cells.coast < 200))
            cand = [(int(c[1]), int(c[0]), False) for c in dry] + [(int(c[1]), int(c[0]), True) for c in shore]
            rng.shuffle(cand)
            for gap in (110, 60, 20):
                for cx, cz, on_shore in cand:
                    if added >= need:
                        break
                    x = cells.MIN_X + cx * cells.CELL + 16 + rng.randrange(-10, 11)
                    z = cells.MIN_Z + cz * cells.CELL + 16 + rng.randrange(-10, 11)
                    r = cells.by_index[int(cells.reg[cz, cx])]
                    culture = r.get("culture") or CULTURE[prov]
                    table = minor.WEIGHTS.get(culture, minor.WEIGHTS["imperial"])
                    table = [(k, w) for k, w in table if ("C" in KINDS[k][3]) == on_shore]
                    if not table:
                        continue
                    kind = _weighted(rng, table)
                    ptype, variant, radius, flags = KINDS[kind]
                    if not placer.clear(x, z, radius, extra=gap):
                        continue
                    if placer.valid(x, z, flags, prov, None) != r["id"]:
                        continue
                    for _ in range(16):
                        nm = minor.name(culture, kind, rng)
                        pid = slug(nm)
                        if pid and pid not in used_ids:
                            break
                    else:
                        continue
                    placer.add(x, z, radius)
                    used_ids.add(pid)
                    e = {"id": pid, "name": "place.nirncraft." + pid, "type": ptype, "culture": culture,
                         "region": r["id"], "x": x, "z": z, "radius": radius}
                    if variant:
                        e["variant"] = variant
                    files[prov].append(e)
                    lang["place.nirncraft." + pid] = nm
                    rname = region_lang.get(r["name"], r["id"].split("/")[1].replace("_", " ").title())
                    lang["place.nirncraft." + pid + ".desc"] = minor.describe(culture, kind, rname, rng)
                    added += 1
        report[prov] = (area // 1000000, target, have, added)
    for prov, (area, target, have, added) in report.items():
        print(f"  {prov:12s} land {area:3d}M blocks^2  target {target:5d}  authored+existing {have:5d}  minor sites {added:5d}")


def _weighted(rng, table):
    total = sum(w for _, w in table)
    t = rng.random() * total
    for k, w in table:
        t -= w
        if t <= 0:
            return k
    return table[-1][0]


def reference_markers(dirs, save):
    """Reference markers: freshly read from --ingest dirs (and cached in tools/provinces_data/ref_<dir>.json -- facts
    only: names, kinds, warped positions), or, without --ingest, every cache from an earlier ingest, so a plain run
    keeps the reference places."""
    out = []
    if dirs:
        for d in dirs:
            ms = load_refs([d])
            if save:
                warp_roads(d)
            if save and ms:
                write_json(os.path.join(DATA_DIR, "ref_" + os.path.basename(os.path.normpath(d)) + ".json"), ms, indent=0)
            out += ms
        return out
    for n in sorted(os.listdir(DATA_DIR)):
        if n.startswith("ref_") and n.endswith(".json"):
            ms = json.load(open(os.path.join(DATA_DIR, n), encoding="utf-8"))
            print(f"  reference cache {n}: {len(ms)} markers")
            out += ms
    return out


def warp_roads(d):
    """<dir>/roads.json (game-coordinate road polylines) -> <dir>/roads_mc.json through the same anchored warp, for the
    roads workstream: {"routes": [{"name", "pts": [[x, z], ...]}], "polylines": [[[x, z], ...], ...]} per world."""
    src = os.path.join(d, "roads.json")
    warp = ref_warp(d)
    if warp is None or not os.path.exists(src):
        return
    roads = json.load(open(src, encoding="utf-8"))
    out = {"note": "block coordinates (x east, z south) via a thin-plate warp pinned at the province's city anchors "
                   "(docs/TAMRIEL.md section 9); tools/provinces.py warp_roads"}
    for world, w in roads.items():
        if not isinstance(w, dict) or w.get("frame") not in (None, "tamriel"):
            continue
        out[world] = {
            "routes": [{"name": r.get("name", ""), "pts": [[round(c, 1) for c in warp(p[0], p[1])] for p in r["pts"]]}
                       for r in w.get("routes", [])],
            "polylines": [[[round(c, 1) for c in warp(p[0], p[1])] for p in pl["pts"]] for pl in w.get("polylines", [])],
        }
    write_json(os.path.join(d, "roads_mc.json"), out, indent=0)
    n = sum(len(v["routes"]) for k, v in out.items() if k != "note")
    print(f"  wrote {os.path.join(d, 'roads_mc.json')} ({n} named routes)")


# travel node names of the reference data that are not place names here
TRAVEL_ALIASES = {"ebonheart": "castle_ebonheart", "ald_ruhn": "aldruhn", "wolverine_hall": "wolverine_hall"}


def export_travel(d, places):
    """<dir>/travel.json (Morrowind's silt strider, boat, guild guide, propylon... services) -> <dir>/travel_mc.json:
    every node at the named place it is here (our coordinates) or at its warped position, and the routes by service."""
    src = os.path.join(d, "travel.json")
    if not os.path.exists(src):
        return
    t = json.load(open(src, encoding="utf-8"))
    by_id = {p["id"]: p for p in places}
    nodes = {}
    for name, n in t.get("nodes", {}).items():
        s = slug(name)
        p = by_id.get(TRAVEL_ALIASES.get(s, s))
        if p is not None:
            nodes[name] = {"x": p["x"], "z": p["z"], "place": p["id"], "region": p.get("region", "")}
        elif n.get("mcX") is not None:
            nodes[name] = {"x": round(n["mcX"]), "z": round(n["mcZ"]), "place": None, "region": n.get("region", "")}
    routes = {}
    for service, sv in t.get("services", {}).items():
        edges = sv.get("edges", []) if isinstance(sv, dict) else sv
        routes[service] = sorted({(e.get("from"), e.get("to")) for e in edges if e.get("from") and e.get("to")})
    out = {"note": "block coordinates; nodes snap to the named places of this map where one exists "
                   "(tools/provinces.py export_travel); routes are directed [from, to] pairs per service",
           "nodes": nodes, "routes": {k: [list(e) for e in v] for k, v in routes.items()}}
    write_json(os.path.join(d, "travel_mc.json"), out, indent=1)
    snapped = sum(1 for n in nodes.values() if n["place"])
    print(f"  wrote {os.path.join(d, 'travel_mc.json')} ({len(nodes)} nodes, {snapped} at named places; "
          f"{sum(len(v) for v in routes.values())} routes)")


def ingest_gates(placer, refs, used_ids, lang):
    """Oblivion's gates as landmark pins (variant oblivion_gate): nothing builds them yet; the realms workstream adds
    the gate structure. On dry land, clear of other places, near their reference spots."""
    out = []
    for m in refs:
        if "oblivion_gate" not in m["type"]:
            continue
        r = placer.cells.region(m["x"], m["z"])
        if r is None or r["province"] in ("sea", "skyrim"):
            continue
        spot = placer.find(m["x"], m["z"], 16, "", r["province"], None, 160)
        if spot is None:
            placer.ingest_skipped += 1
            continue
        pid = m.get("id") or "oblivion_gate"
        if pid in used_ids:
            continue
        x, z, got = spot
        placer.add(x, z, 16)
        used_ids.add(pid)
        out.append({"id": pid, "name": "place.nirncraft.oblivion_gate", "type": "landmark", "culture": "daedric",
                    "region": got, "x": x, "z": z, "radius": 16, "variant": "oblivion_gate"})
    if out:
        lang["place.nirncraft.oblivion_gate"] = "Oblivion Gate"
        lang["place.nirncraft.oblivion_gate.desc"] = "A burning gate to the Deadlands, torn open in the Oblivion Crisis."
        print(f"  ingest: {len(out)} Oblivion gates")
    return out


def write_outputs(files, lang):
    for prov, entries in files.items():
        if prov == GATES_FILE[:-5] and not entries:
            continue
        write_json(os.path.join(PLACES_DIR, prov + ".json"), entries)
    index_path = os.path.join(PLACES_DIR, "index.json")
    index = json.load(open(index_path, encoding="utf-8"))
    for f in OUR_FILES:
        if f not in index and os.path.exists(os.path.join(PLACES_DIR, f)):
            index.append(f)
    write_json(index_path, index, indent=2)
    write_json(LANG, dict(sorted(lang.items())), indent=2)
    print("wrote", sum(len(v) for v in files.values()), "places,", len(lang), "lang keys")


def previews(cells, existing, files, lang, authored):
    """tools/worldgen/preview_places.png (all of Tamriel, every named place as a dot, settlements labelled) and
    preview_places_<province>.png close-ups (every authored place labelled; minor sites as small dots)."""
    ours = [e for v in files.values() for e in v]
    allp = existing + ours
    img, _ = _relief(cells, cells.MIN_X, cells.MIN_Z, cells.max_x, cells.max_z, 16)
    draw_places(img, allp, cells.MIN_X, cells.MIN_Z, 16, {"capital", "city"}, lang)
    img.save(os.path.join(PREVIEW_DIR, "preview_places.png"))
    print("wrote preview_places.png", img.size)
    tamriel_lang = {}
    for f in ("tamriel_places.json", "landmarks.json"):
        try:
            tamriel_lang.update(json.load(open(os.path.join(os.path.dirname(LANG), f), encoding="utf-8")))
        except OSError:
            pass
    tamriel_lang.update(lang)
    for prov in PROVINCES[:-1]:
        x0, z0, x1, z1 = province_bounds(cells, prov)
        bpp = 6
        img, _ = _relief(cells, x0, z0, x1, z1, bpp)
        old = {e["id"] for e in existing}
        minor = [e for e in allp if e["id"] not in authored and e["id"] not in old]
        draw_places(img, minor, x0, z0, bpp, set(), tamriel_lang, small=True)
        named = [e for e in allp if e["id"] in authored or e["id"] in old]
        draw_places(img, named, x0, z0, bpp, set(COLORS), tamriel_lang, small=True)
        img.save(os.path.join(PREVIEW_DIR, f"preview_places_{prov}.png"))
        print(f"wrote preview_places_{prov}.png", img.size)
    for land in Beyond.LANDS:
        foreign_image(land, files["foreign"], 32 if land[6] in (11, 12, 14, 16) else 16, "preview_places_foreign_")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
