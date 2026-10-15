"""Skyrim-parity scorecard: how full is every region compared with Skyrim (Skyrim = 100).

Usage:
  py -3 -I tools/qa/parity.py                 # print the province + sub-region scorecard and the 10 lowest cells
  py -3 -I tools/qa/parity.py --lowest 10     # only the N lowest cells (what qa.ps1 -Parity prints)
  py -3 -I tools/qa/parity.py --json PATH     # also dump every raw number (default tools/qa/out/parity.json)
  py -3 -I tools/qa/parity.py --raw           # print the raw-number tables too
  py -3 -I tools/qa/parity.py --write-doc docs/PARITY.md   # refresh the generated block between the PARITY markers

Standard library only; reads repository data and greps Java. About 2 seconds. Never writes outside tools/qa/out and
the document passed to --write-doc.

How a score is made
-------------------
Every category (SET, NPC, QST, DUN, CRE, FLO, STY, LND, LOR, MUS, ITM) has a few sub-metrics. A sub-metric value for
a region is divided by Skyrim's value for the same thing and multiplied by 100, then capped at 150 so one huge number
cannot hide a hole. The category score is the mean of its sub-metrics. Three kinds of sub-metric:

  density  count per km2 of land (settlements, quests, dungeons, landmarks, lore); Skyrim ref = Skyrim province
           total / Skyrim area.
  unit     count per named town (capital + city + town) (NPCs, quests, dialogue, named buildings); Skyrim ref =
           Skyrim total / Skyrim named towns.
  div      a variety count per REGION (distinct themes, looks, creature kinds, tree species...); Skyrim ref = mean
           of the nine holds.
  prov     the number is shared by every region of a province (music, gear tags, legendary uniques); Skyrim ref =
           Skyrim's own number. Flagged "P" in the raw tables.

For s_npcs, s_bldgs, q_total and q_per_town the reference is the larger of our Skyrim's value and the value measured
from the real game (SKYRIM_MEASURED below), so Skycraft's own Skyrim can score under 100 too.

Anything that is the same everywhere (spells, perks, recipes, NPC role list, rare creature variants) is global: it is
listed under GLOBAL and not scored per region.
"""
import argparse
import glob
import json
import os
import re
import struct
import sys
import zlib
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.environ.get("PARITY_ROOT") or os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
SKY = os.path.join(RES, "skycraft")
TAM = os.path.join(SKY, "tamriel")
DATA = os.path.join(RES, "data", "skycraft")
JAVA = os.path.join(ROOT, "mod", "src", "main", "java", "com", "skycraft")
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft")
SOUNDS = os.path.join(RES, "assets", "skycraft", "sounds")
OUT = os.path.join(HERE, "out")
CELL_KM2 = (32 / 1000.0) ** 2  # raster cell is 32 blocks (1 block = 1 m): 0.001024 km2
CAP = 150.0
INCLUDE_REALMS = False
BASE = "skyrim"

CATS = [
    ("SET", "Settlements"), ("NPC", "NPCs + dialogue"), ("QST", "Quests"), ("DUN", "Dungeons"),
    ("CRE", "Creatures"), ("FLO", "Flora + biomes"), ("STY", "Building styles"), ("LND", "Landmarks + sites"),
    ("LOR", "Lore"), ("MUS", "Music + ambience"), ("ITM", "Items + loot"),
]

# (id, category, label, kind, fixed_ref): fixed_ref is used when Skyrim's own value is 0 (we want to exceed, not copy)
METRICS = [
    ("s_towns", "SET", "named towns /km2", "density", None),
    ("s_small", "SET", "villages+farms /km2", "density", None),
    ("s_bespoke", "SET", "bespoke share of named towns", "unit", None),
    ("s_bldgs", "SET", "named buildings per named town", "unit", None),
    ("s_npcs", "SET", "named NPCs per named town", "unit", None),
    ("n_lines", "NPC", "dialogue + scene lines per named town", "unit", None),
    ("n_scenes", "NPC", "NPC scenes per named town", "unit", None),
    ("n_roles", "NPC", "distinct NPC roles among named NPCs", "div", None),
    ("n_say", "NPC", "region-keyed chat/say lines", "div", None),
    ("q_total", "QST", "quests /km2", "density", None),
    ("q_per_town", "QST", "quests per named town", "unit", None),
    ("q_kinds", "QST", "quest kinds (main/side/faction/hidden/radiant)", "div", None),
    ("q_placed", "QST", "share of quests on registered places", "unit", None),
    ("d_density", "DUN", "dungeons+forts /km2", "density", None),
    ("d_themes", "DUN", "distinct dungeon themes", "div", None),
    ("d_caverns", "DUN", "distinct carved looks (theme x kit palette)", "div", None),
    ("d_rosters", "DUN", "distinct inhabitant rosters in carved dungeons", "div", None),
    ("d_built", "DUN", "distinct built-dungeon looks", "div", None),
    ("d_rooms", "DUN", "room templates reachable", "div", None),
    ("d_marquee", "DUN", "hand-authored dungeons", "div", None),
    ("d_bosses", "DUN", "named quest bosses", "div", None),
    ("d_identity", "DUN", "share of dungeons with a lore hook, quest or unique", "unit", None),
    ("c_hostile", "CRE", "hostile kinds per biome family", "div", None),
    ("c_wild", "CRE", "wildlife kinds per biome family", "div", None),
    ("c_cave", "CRE", "cave creature kinds", "div", None),
    ("c_total", "CRE", "distinct creature kinds in region", "div", None),
    ("c_bosses", "CRE", "bosses+dragons+shrines /km2", "density", None),
    ("f_biomes", "FLO", "biomes in palette", "div", None),
    ("f_trees", "FLO", "tree species", "div", None),
    ("f_plants", "FLO", "flora species", "div", None),
    ("y_looks", "STY", "distinct settlement looks", "div", None),
    ("y_sheet", "STY", "style-sheet richness", "div", None),
    ("l_density", "LND", "landmarks+shrines+stones /km2", "density", None),
    ("l_kinds", "LND", "distinct landmark kinds", "div", None),
    ("l_minor", "LND", "minor-site kinds available", "prov", None),
    ("l_unique", "LND", "explore uniques /km2", "density", None),
    ("r_places", "LOR", "places with lore /km2", "density", None),
    ("r_notes", "LOR", "readables+books /km2", "density", None),
    ("r_flavor", "LOR", "rumours+legends+songs", "div", None),
    ("m_explore", "MUS", "exploration tracks", "prov", None),
    ("m_town", "MUS", "town tracks", "prov", None),
    ("m_amb", "MUS", "ambience beds", "div", None),
    ("i_gear", "ITM", "regional gear list size", "prov", None),
    ("i_sets", "ITM", "signature gear families (non-universal sets)", "prov", None),
    ("i_ingr", "ITM", "regional ingredients", "prov", None),
    ("i_trink", "ITM", "regional trinkets", "prov", None),
    ("i_unique", "ITM", "legendary uniques", "prov", None),
]
M_BY_ID = {m[0]: m for m in METRICS}

# Measured from the real game (the owner's Skyrim SE install, nine holds, counts only; see
# docs/world/DESIGN-PRINCIPLES.md 4.1). Where Skycraft's own Skyrim is thinner than the real thing, the real number is
# the reference, so the bar is Skyrim itself and not our copy of it. Areas are Skycraft km2 of the same holds (26.49).
#   s_npcs      845 unique named NPCs placed in the nine holds / 18 named towns          = 46.9 (our Skyrim: 10.4)
#   s_bldgs     210 named building locations (houses, shops, inns, temples, halls) / 18 = 11.7 (our Skyrim: 5.3)
#   q_total     371 journal quests (main, faction, Daedric, side, misc, radiant) / 26.49 km2 = 14.0 (ours: 12.6)
#   q_per_town  371 / 18 named towns                                                     = 20.6 (ours: 18.6)
# Metrics not listed (dialogue lines, scenes, variety counts, dungeon density...) keep our Skyrim as the reference:
# either the real game cannot be counted the same way, or our Skyrim already meets or beats it.
SKYRIM_MEASURED = {
    "s_towns": 0.55,
    "s_small": 1.43,
    "s_npcs": 46.9,
    "s_bldgs": 11.7,
    "q_total": 14.0,
    "q_per_town": 20.6,
    "n_lines": 42.0,
    "n_scenes": 8.0,
    "n_roles": 33.4,
    "n_say": 18.0,
    "y_looks": 4.56,
}


def jload(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


# ------------------------------------------------------------------------------------------------ raster (stdlib PNG)
def decode_indexed_png(path):
    """8-bit palette/greyscale PNG -> (w, h, list of bytes rows). Standard library only."""
    data = open(path, "rb").read()
    pos, idat, w, h, ct, bpp = 8, [], 0, 0, 0, 1
    while pos < len(data):
        n, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        pos += 12 + n
        if typ == b"IHDR":
            w, h, depth, ct = struct.unpack(">IIBB", body[:10])
            if depth != 8 or ct not in (0, 3):
                raise ValueError("expected an 8-bit indexed/greyscale png")
        elif typ == b"IDAT":
            idat.append(body)
    raw = zlib.decompress(b"".join(idat))
    stride = w
    rows, prev, p = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[p]
        cur = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        if f == 1:
            for i in range(1, stride):
                cur[i] = (cur[i] + cur[i - 1]) & 255
        elif f == 2:
            for i in range(stride):
                cur[i] = (cur[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                cur[i] = (cur[i] + (((cur[i - 1] if i else 0) + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = cur[i - 1] if i else 0
                b = prev[i]
                c = prev[i - 1] if i else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 255
        rows.append(bytes(cur))
        prev = cur
    return w, h, rows


class Raster:
    def __init__(self):
        meta = jload(os.path.join(ROOT, "tools", "worldgen", "tamriel_vectors.json"), {}).get("meta", {})
        self.cell = meta.get("cell", 32)
        self.min_x = meta.get("min_x", -15360)
        self.min_z = meta.get("min_z", -5120)
        png = os.path.join(TAM, "regions.png")
        self.w, self.h, self.rows = decode_indexed_png(png)
        self.counts = Counter()
        for r in self.rows:
            self.counts.update(r)

    def index_at(self, x, z):
        c = int((x - self.min_x) // self.cell)
        r = int((z - self.min_z) // self.cell)
        if 0 <= c < self.w and 0 <= r < self.h:
            return self.rows[r][c]
        return None


# ------------------------------------------------------------------------------------------------ loaders
def load_regions():
    regs = jload(os.path.join(TAM, "regions.json"), [])
    return [r for r in regs]


def load_places():
    out = []
    for f in sorted(glob.glob(os.path.join(TAM, "places", "*.json"))):
        if os.path.basename(f) == "index.json":
            continue
        j = jload(f, [])
        if isinstance(j, list):
            out += [p for p in j if isinstance(p, dict) and "x" in p and "region" in p]
    return out


def theme_styles():
    """Theme id -> BUILT / NATURAL / SURFACE parsed from Theme.java."""
    out = {}
    for m in re.finditer(r'^\s+[A-Z_]+\("([a-z_]+)",\s*Style\.([A-Z]+)', read(os.path.join(JAVA, "dungeons", "world", "Theme.java")), re.M):
        out[m.group(1)] = m.group(2)
    return out


KIT_BY_CULTURE = {
    "imperial": "imperial", "colovian": "imperial", "nibenese": "imperial", "dunmer": "velothi", "velothi": "velothi",
    "hlaalu": "velothi", "redoran": "velothi", "telvanni": "velothi", "argonian": "xanmeer", "khajiit": "khajiit",
    "bosmer": "bosmer", "altmer": "altmer", "redguard": "redguard", "yokudan": "redguard", "breton": "breton",
    "orc": "orc", "orsimer": "orc", "akaviri": "akaviri", "atmoran": "atmoran", "maormer": "maormer",
    "sload": "sload", "reachmen": "forsworn", "forsworn": "forsworn",
}
KIT_BY_PROVINCE = {
    "skyrim": "nord", "cyrodiil": "imperial", "morrowind": "velothi", "black_marsh": "xanmeer", "elsweyr": "khajiit",
    "valenwood": "bosmer", "summerset": "altmer", "hammerfell": "redguard", "yokuda": "redguard", "high_rock": "breton",
    "atmora": "atmoran", "akavir": "akaviri", "pyandonea": "maormer", "thras": "sload",
}


def kit_for(region):
    k = KIT_BY_CULTURE.get((region.get("culture") or "").lower())
    return k or KIT_BY_PROVINCE.get(region["province"], "nord")


# ------------------------------------------------------------------------------------------------ biomes / flora
PLANT_WORDS = ("flower", "tulip", "poppy", "orchid", "fern", "grass", "bush", "mushroom", "cactus", "bamboo", "lily",
               "berry", "dandelion", "allium", "bluet", "cornflower", "oxeye", "peony", "lilac", "rose", "sunflower",
               "torchflower", "sugar_cane", "pumpkin", "reeds", "nirnroot", "nightshade", "deathbell", "mirriam",
               "scathecraw", "harrada", "bloodgrass", "dementia_bulb", "mania_bloom", "wither_rose", "moss_carpet",
               "azalea", "crystal_cluster", "cap", "dead_bush", "glow_lichen", "kelp", "seagrass", "coral")
TREE_FEATS = {"oak", "fancy_oak", "birch", "dark_oak", "spruce", "pine", "mega_spruce", "mega_pine", "acacia",
              "jungle_tree", "mega_jungle_tree", "jungle_bush", "mangrove", "tall_mangrove", "cherry"}
WG = os.path.join(DATA, "worldgen")
_fcache = {}


def feature_names(fid, depth=0):
    if fid in _fcache:
        return _fcache[fid]
    trees, plants = set(), set()
    _fcache[fid] = (trees, plants)
    ns, _, path = fid.partition(":")
    if ns == "minecraft":
        base = re.sub(r"(_checked|_tree|_bees.*)$", "", path)
        if base in TREE_FEATS or path in TREE_FEATS:
            trees.add("vanilla:" + base)
        elif any(w in path for w in PLANT_WORDS) and "ore" not in path.split("_"):
            plants.add("vanilla:" + path)
        return trees, plants
    for kind in ("placed_feature", "configured_feature"):
        p = os.path.join(WG, kind, path + ".json")
        if os.path.exists(p):
            s = read(p)
            for n in re.findall(r'"Name":\s*"([^"]+)"', s):
                short = n.split(":")[-1]
                if short.endswith("_leaves"):
                    trees.add(n)
                elif any(w in short for w in PLANT_WORDS) and "ore" not in short.split("_"):
                    plants.add(n)
            for sp in re.findall(r'"species":\s*"([a-z_0-9]+)"', s):
                trees.add("skycraft:" + sp)
            for ref in set(re.findall(r'"([a-z_]+:[a-z0-9_]+/[a-z0-9_/]+)"', s)):
                if ref != fid and depth < 6 and ref.split(":")[0] in ("skycraft", "minecraft"):
                    t, pl = feature_names(ref, depth + 1)
                    trees |= t
                    plants |= pl
    return trees, plants


_bcache = {}


def biome_flora(bid):
    if bid not in _bcache:
        trees, plants = set(), set()
        j = jload(os.path.join(WG, "biome", bid.split(":")[1] + ".json"), {})
        for step in j.get("features", []):
            for f in step:
                if f.startswith("skycraft:biome/") or f.split(":")[0] == "minecraft":
                    t, p = feature_names(f)
                    trees |= t
                    plants |= p
        _bcache[bid] = (trees, plants)
    return _bcache[bid]


_spawn_tags = None


def biome_family(bid):
    """Port of BiomeFamily.resolve for datapack biomes: the spawns/* tag lists first, then the id keywords."""
    global _spawn_tags
    if _spawn_tags is None:
        _spawn_tags = {}
        for f in glob.glob(os.path.join(DATA, "tags", "worldgen", "biome", "spawns", "*.json")):
            name = os.path.basename(f)[:-5]
            for v in jload(f, {}).get("values", []):
                if isinstance(v, str):
                    _spawn_tags.setdefault(v, set()).add(name)
    name = bid.split(":")[1]
    tags = _spawn_tags.get(bid, set())

    def has(*w):
        return any(x in name for x in w)
    if has("ocean", "sea_of", "deep_sea"):
        return "ocean"
    if "river" in tags or has("river"):
        return "river"
    if "coast" in tags or has("beach", "coast", "shore"):
        return "beach"
    if has("ash", "volcan", "red_mountain", "molag", "caldera", "lava"):
        return "ash"
    if "swamp" in tags or has("swamp", "marsh", "mire", "bog", "mangrove", "fen"):
        return "swamp"
    if "desert" in tags or has("desert", "dune", "badland", "canyon", "alikr", "sand"):
        return "desert"
    if "jungle" in tags or has("jungle", "rainforest", "hist", "tropical", "pellitine"):
        return "jungle"
    if "cold" in tags or "frozen_peaks" in tags or "tundra" in tags or "snowy_forest" in tags \
            or has("snow", "glacier", "ice", "frozen", "tundra", "frost", "pale"):
        return "frozen"
    if "mountain" in tags or has("peak", "mountain", "crag", "highland", "cliff"):
        return "mountain"
    if "forest" in tags or "pine_forest" in tags or "dark_forest" in tags or has("forest", "wood", "grove", "taiga", "pine", "birch", "weald", "graht"):
        return "forest"
    return "plains"


def region_biomes(region, pj):
    pals = pj["palettes"]
    key = None
    for k in (region["id"], region.get("palette"), "province/" + region["province"], "default"):
        if k in pals:
            key = k
            break
    out = set()
    k = key
    seen = 0
    while k and seen < 8:
        p = pals[k]
        for lst in p.get("contexts", {}).values():
            for e in lst:
                out.add(e["biome"])
        for m in p.get("micro", []):
            if isinstance(m, dict) and m.get("biome"):
                out.add(m["biome"])
        k = p.get("fallback")
        seen += 1
    return {b for b in out if b.startswith("skycraft:")
            and os.path.exists(os.path.join(WG, "biome", b.split(":")[1] + ".json"))}


# ------------------------------------------------------------------------------------------------ main collection
def collect():
    rast = Raster()
    regs = load_regions()
    by_index = {r["index"]: r for r in regs}
    land = [r for r in regs if r["kind"] != "sea"]
    area = {r["id"]: rast.counts.get(r["index"], 0) * CELL_KM2 for r in land}
    places = load_places()
    pid = {p["id"]: p for p in places}
    reg_by_id = {r["id"]: r for r in regs}
    prov_of = {r["id"]: r["province"] for r in regs}
    R = {r["id"]: defaultdict(float) for r in land}
    sets = {r["id"]: defaultdict(set) for r in land}

    def rid_at(x, z):
        i = rast.index_at(x, z)
        r = by_index.get(i)
        return r["id"] if r and r["id"] in R else None

    # ---- places by region
    pl_by = defaultdict(list)
    for p in places:
        if p["region"] in R:
            pl_by[p["region"]].append(p)
    styles = theme_styles()

    # ---- structure JSON (themes, kits, where)
    sites = {}      # variant -> dict(site, theme, kit, flavor)
    randoms = []    # (name, dict)
    uniques = {}    # variant -> unique id
    for f in glob.glob(os.path.join(WG, "structure", "site", "*.json")):
        j = jload(f, {})
        for v in (j.get("place") or {}).get("variants", []):
            sites[v] = dict(site=os.path.basename(f)[:-5], theme=j.get("theme"), kit=j.get("kit"), flavor=j.get("flavor"))
    for f in glob.glob(os.path.join(WG, "structure", "unique", "*.json")):
        j = jload(f, {})
        for v in (j.get("place") or {}).get("variants", []):
            uniques[v] = os.path.basename(f)[:-5]
            sites.setdefault(v, dict(site="unique/" + os.path.basename(f)[:-5], theme=j.get("theme"), kit=j.get("kit"), flavor=None))
    for f in glob.glob(os.path.join(WG, "structure", "*.json")):
        j = jload(f, {})
        if j.get("type") == "skycraft:dungeon":
            randoms.append((os.path.basename(f)[:-5], j))
    for f in glob.glob(os.path.join(WG, "structure", "ruin", "*.json")):
        randoms.append(("ruin/" + os.path.basename(f)[:-5], jload(f, {})))

    # hand-authored marquee plans (PA13 / MARQUEE-PLAN I4): dungeons/marquee/<placeId>.json
    marquee_plans = {os.path.basename(f)[:-5] for f in glob.glob(os.path.join(SKY, "dungeons", "marquee", "*.json"))
                     if os.path.basename(f) != "index.json"}

    # ---- room templates per theme (grep)
    dw = os.path.join(JAVA, "dungeons", "world")

    def feats(*names):
        s = set()
        for n in names:
            s |= set(re.findall(r'feature\("((?:room|setpiece):[a-z_]+)"\)', read(os.path.join(dw, n + ".java"))))
        return s
    shared = feats("SetPieces")
    cave_variants = set(re.findall(r"Kind\.([A-Z]+)", read(os.path.join(dw, "CaveVariants.java")))) - {"NONE"}
    rooms_by_theme = {
        "barrow": feats("BarrowRooms") | shared, "dwemer": feats("DwemerRooms") | shared, "fort": feats("FortRooms"),
        "ruin": feats("CultureRooms") | shared,
    }
    nat = feats("NaturalRooms", "CaveRooms") | {"variant:" + k.lower() for k in cave_variants}
    for t in ("cave", "mine", "vampire_lair", "necromancer_lair", "hagraven_lair"):
        rooms_by_theme[t] = nat

    # ---- spawn rules
    rules = []
    for f in glob.glob(os.path.join(DATA, "region_spawns", "*.json")):
        j = jload(f, [])
        for blk in (j if isinstance(j, list) else [j]):
            rules.append(blk)
    FAMS = ["ocean", "beach", "river", "swamp", "desert", "jungle", "ash", "frozen", "mountain", "forest", "plains"]

    def spawn_kinds(rid, prov, fam=None):
        hostile, wild, cave, anyk = set(), set(), set(), set()
        for r in rules:
            if r.get("regions") and rid not in r["regions"]:
                continue
            if r.get("provinces") and prov not in r["provinces"]:
                continue
            if fam is not None and r.get("families") and fam not in r["families"]:
                continue
            if fam is not None and not r.get("families") and r.get("biomes"):
                continue
            for g in r.get("groups", []):
                ents = [m["entity"] for m in g.get("members", [g]) if "entity" in m]
                for e in ents:
                    anyk.add(e)
                    if g.get("place") == "cave":
                        cave.add(e)
                    elif g.get("kind") == "wildlife":
                        wild.add(e)
                    else:
                        hostile.add(e)
        return hostile, wild, cave, anyk

    # ---- quests (top-level quests/*.json + quests/story/*.json)
    qfiles = ([f for f in sorted(glob.glob(os.path.join(SKY, "quests", "*.json")))
               if os.path.basename(f) not in ("index.json", "places.json", "radiant_templates.json")]
              + [f for f in sorted(glob.glob(os.path.join(SKY, "quests", "story", "*.json")))
                 if os.path.basename(f) != "index.json"])
    qplaces_global = jload(os.path.join(SKY, "quests", "places.json"), {})
    radiant = jload(os.path.join(SKY, "quests", "radiant_templates.json"), {}).get("templates", [])
    settle_region = {p["id"]: p["region"] for p in places}

    def resolve_place(pidx, file_places):
        if pidx in settle_region:
            return settle_region[pidx] if settle_region[pidx] in R else None
        for src in (file_places, qplaces_global):
            if pidx in src and isinstance(src[pidx], dict) and "x" in src[pidx]:
                return rid_at(src[pidx]["x"], src[pidx]["z"])
        return None
    quest_rows = []
    bosses_n, scenes_n = Counter(), Counter()
    quest_places = set()
    for f in qfiles:
        j = jload(f, {})
        fp = j.get("places", {}) if isinstance(j.get("places"), dict) else {}
        file_region = j.get("region") if j.get("region") in R else None
        cat = j.get("category") or "side"
        qs = j.get("quests", [])
        modal = Counter()
        regs_of = []
        for q in qs:
            rg = q.get("region") if q.get("region") in R else None
            if rg is None:
                st = str(q.get("start", ""))
                if st.startswith("npc:"):
                    nid = st[4:]
                    for n in j.get("npcs", []):
                        if n.get("id") == nid and n.get("place"):
                            rg = resolve_place(n["place"], fp)
            if rg is None:
                for o in q.get("objectives", []):
                    if o.get("place"):
                        rg = resolve_place(o["place"], fp)
                        if rg:
                            break
            if rg is None:
                rg = file_region
            regs_of.append(rg)
            if rg:
                modal[rg] += 1
        file_modal = modal.most_common(1)[0][0] if modal else file_region
        for q, rg in zip(qs, regs_of):
            rg = rg or file_modal
            if not rg:
                continue
            st = str(q.get("start", "")).split(":")[0]
            registered = any(o.get("place") in pid for o in q.get("objectives", []))
            quest_rows.append((rg, q.get("category") or cat, st, registered, q))
        # bosses and scenes: assign to the file's modal region
        dumps = [(rg or file_modal, json.dumps(q)) for q, rg in zip(qs, regs_of)]

        def region_of_ref(token):
            for rg, d in dumps:
                if token in d and rg:
                    return rg
            return file_modal
        for bid in (j.get("bosses") or {}):
            rg = region_of_ref('"%s"' % bid)
            if rg:
                bosses_n[rg] += 1
        for sid, lines in (j.get("scenes") or {}).items():
            rg = region_of_ref("scene:" + sid)
            if rg:
                scenes_n[rg] += 1
                R[rg]["scene_lines"] += len(lines)
        for q in qs:
            for o in q.get("objectives", []):
                if o.get("place") in pid:
                    quest_places.add(o["place"])
        # named NPCs and their roles / greeting lines
        for n in j.get("npcs", []):
            rg = resolve_place(n.get("place"), fp) if n.get("place") else None
            rg = rg or file_modal
            if not rg:
                continue
            R[rg]["npcs"] += 1
            sets[rg]["roles"].add(n.get("role") or "citizen")
            R[rg]["npc_lines"] += sum(1 for k, v in n.items() if isinstance(v, str) and k in ("greet", "talk", "bark")) \
                + sum(len(v) for v in n.values() if isinstance(v, list) and v and isinstance(v[0], str))
    for rg, cat, st, registered, q in quest_rows:
        R[rg]["quests"] += 1
        R[rg]["q_placed"] += 1 if registered else 0
        key = "main" if cat == "main" else ("faction" if cat == "faction" else "side")
        sets[rg]["qkinds"].add(key)
        R[rg]["q_" + key] += 1
        if st in ("find", "manual"):
            sets[rg]["qkinds"].add("hidden")
            R[rg]["q_hidden"] += 1
    for rg, n in bosses_n.items():
        R[rg]["bosses"] += n
    for rg, n in scenes_n.items():
        R[rg]["scenes"] += n

    # ---- society data files (skycraft/society/*.json: courts, trainers, province residents)
    for tf in sorted(glob.glob(os.path.join(SKY, "society", "*.json"))):
        tj = jload(tf, {})
        if not isinstance(tj, dict):
            continue
        smap = tj.get("settlements") if isinstance(tj.get("settlements"), dict) else {}
        for sid, v in smap.items():
            rg = settle_region.get(sid)
            if not rg and isinstance(v, dict):
                rg = v.get("region")
                if not rg and v.get("hold"):
                    h = v["hold"]
                    rg = "morrowind/solstheim" if h == "solstheim" else f"skyrim/{h}"
            if rg not in R:
                continue
            if isinstance(v, list):
                for item in v:
                    if isinstance(item, dict) and item.get("name"):
                        R[rg]["npcs"] += 1
                        sets[rg]["roles"].add(item.get("workplace") or "trainer")
                        if item.get("skill"):
                            sets[rg]["roles"].add("trainer_" + str(item["skill"]))
                        R[rg]["npc_lines"] += 1
            elif isinstance(v, dict):
                if isinstance(v.get("residents"), list):
                    for res in v["residents"]:
                        if isinstance(res, dict) and res.get("name"):
                            R[rg]["npcs"] += 1
                            if res.get("role"):
                                sets[rg]["roles"].add(str(res["role"]).lower())
                            if res.get("court_role"):
                                sets[rg]["roles"].add(str(res["court_role"]).lower())
                            R[rg]["npc_lines"] += sum(
                                1 for lk in ("greet", "talk", "bark") if isinstance(res.get(lk), str) and res.get(lk)
                            )
                else:
                    for rk in ("jarl", "ruler", "steward", "court_wizard", "priest", "housecarl"):
                        if v.get(rk):
                            R[rg]["npcs"] += 1
                            sets[rg]["roles"].add(rk)
                            R[rg]["npc_lines"] += 1
        omap = tj.get("outside_settlements") if isinstance(tj.get("outside_settlements"), dict) else {}
        for hkey, olist in omap.items():
            rg = hkey if hkey in R else f"skyrim/{hkey}"
            if rg in R and isinstance(olist, list):
                for res in olist:
                    if isinstance(res, dict) and res.get("name"):
                        R[rg]["npcs"] += 1
                        if res.get("role"):
                            sets[rg]["roles"].add(str(res["role"]).lower())
                        R[rg]["npc_lines"] += sum(
                            1 for lk in ("greet", "talk", "bark") if isinstance(res.get(lk), str) and res.get(lk)
                        )

    # ---- lore + dungeon identity
    lore_places = {}
    lore_regions = {}
    for f in glob.glob(os.path.join(SKY, "lore", "places", "*.json")):
        j = jload(f, {})
        if not isinstance(j, dict):
            continue
        for k, v in (j.get("places") or {}).items():
            if not isinstance(v, dict):
                continue
            if k not in lore_places or not isinstance(lore_places[k], dict):
                lore_places[k] = dict(v)
            else:
                cur = lore_places[k]
                for lk, lv in v.items():
                    if isinstance(lv, list) and isinstance(cur.get(lk), list):
                        if len(lv) > len(cur[lk]):
                            cur[lk] = lv
                    elif lk not in cur or not cur[lk]:
                        cur[lk] = lv
        for k, v in (j.get("regions") or {}).items():
            if not isinstance(v, dict):
                continue
            if k not in lore_regions or not isinstance(lore_regions[k], dict):
                lore_regions[k] = {
                    rk: list(rv) if isinstance(rv, list) else rv for rk, rv in v.items()
                }
            else:
                cur = lore_regions[k]
                for rk, rv in v.items():
                    if isinstance(rv, list) and isinstance(cur.get(rk), list):
                        for x in rv:
                            if x not in cur[rk]:
                                cur[rk].append(x)
                    elif rk not in cur or not cur[rk]:
                        cur[rk] = list(rv) if isinstance(rv, list) else rv
    dun_identity = {}
    for f in glob.glob(os.path.join(SKY, "dungeons", "identity", "*.json")):
        if os.path.basename(f) == "index.json":
            continue
        j = jload(f, {})
        if isinstance(j, dict):
            dmap = j.get("dungeons") or j.get("places") or {}
            if isinstance(dmap, dict):
                for k, v in dmap.items():
                    if isinstance(v, dict):
                        dun_identity[k] = v
            elif isinstance(dmap, list):
                for v in dmap:
                    if isinstance(v, dict) and v.get("id"):
                        dun_identity[v["id"]] = v
    place_lang = jload(os.path.join(LANG, "place_lore.json"), {})
    book_keys = [k for k in place_lang if k.startswith("book.skycraft.place.")]
    books_by_place = Counter()
    for k in book_keys:
        parts = k.split(".")
        # book.skycraft.place.<id>.<n...>; ids may contain no dots
        if len(parts) >= 4:
            books_by_place[parts[3]] += 1
    prov_regions = defaultdict(list)
    for r in land:
        prov_regions[r["province"]].append(r["id"])
    for pidk, v in lore_places.items():
        p = pid.get(pidk)
        if not p or p["region"] not in R:
            continue
        rg = p["region"]
        R[rg]["lore_places"] += 1
        notes = v.get("notes") or []
        R[rg]["lore_notes"] += len(notes) + (1 if v.get("quest") else 0)
    for pidk, v in dun_identity.items():
        p = pid.get(pidk)
        if not p or p["region"] not in R:
            continue
        rg = p["region"]
        if pidk not in lore_places:
            R[rg]["lore_places"] += 1
        R[rg]["lore_notes"] += len(v.get("notes") or []) + (1 if v.get("quest_hook") else 0)
        if v.get("boss"):
            R[rg]["bosses"] += 1
    for pidk, n in books_by_place.items():
        p = pid.get(pidk)
        if p and p["region"] in R:
            R[p["region"]]["books"] += n
    for k, v in lore_regions.items():
        n = sum(len(x) for x in v.values() if isinstance(x, list))
        if "/" in k and k in R:
            R[k]["flavor"] += n
        elif k in prov_regions:
            for rg in prov_regions[k]:
                R[rg]["flavor"] += n / float(len(prov_regions[k]))

    # ---- society (region-keyed say/chat lines across society.json + society_*.json)
    soc = {}
    for sf in sorted(glob.glob(os.path.join(LANG, "society*.json"))):
        sd = jload(sf, {})
        if isinstance(sd, dict):
            soc.update(sd)
    for k in soc:
        parts = k.split(".")
        if len(parts) < 4 or parts[2] not in ("say", "chat", "bark"):
            continue
        seg = set(parts[3:])
        for r in land:
            short = r["id"].split("/")[1]
            if short in seg or ("gossip_" + short) in seg:
                R[r["id"]]["say"] += 1
            elif r["province"] in seg and parts[3] == "province":
                R[r["id"]]["say"] += 1.0 / len(prov_regions[r["province"]])

    # ---- city plans / named buildings
    plans = (jload(os.path.join(TAM, "cityplans", "index.json"), {}) or {}).get("plans", [])
    plan_named = {}
    plan_overlay = {}
    for pn in plans:
        j = jload(os.path.join(TAM, "cityplans", pn + ".json"), {})
        named_set = {v for v in (j.get("names") or {}).values() if v}
        for sec in ("landmarks", "plazas", "buildings", "queue"):
            for l in (j.get(sec) or []):
                if isinstance(l, dict) and (l.get("name") or l.get("id")):
                    named_set.add(l.get("name") or l.get("id"))
        n = len([v for v in (j.get("names") or {}).values() if v])
        n += sum(1 for l in j.get("landmarks", []) if l.get("name") or l.get("id"))
        n += sum(1 for l in j.get("plazas", []) if l.get("name"))
        n += sum(1 for l in j.get("buildings", []) if l.get("name"))
        plan_named[pn] = max(n, len(named_set))
        plan_overlay[pn] = j.get("overlay") or pn
    cities_src = read(os.path.join(JAVA, "tamriel", "settlement", "plan", "Cities.java"))
    java_cases = {}
    for m in re.finditer(r'case "([a-z_]+)"((?:,\s*"[a-z_]+")*)\s*->\s*(\w+)\(plan', cities_src):
        java_cases[m.group(1)] = m.group(3)
    java_cases.update({"dragon_bridge": "generic"})
    # named() calls per method body
    method_named = {}
    for m in re.finditer(r"static void (\w+)\(Plan plan[^)]*\)\s*\{(.*?)\n    \}", cities_src, re.S):
        method_named[m.group(1)] = len(re.findall(r"named\(", m.group(2)))
    for pidk in re.findall(r'case "([a-z_]+)" -> new Layouts\.Names\(', cities_src):
        pass
    names_pc = {}
    for m in re.finditer(r'case "([a-z_]+)" -> new Layouts\.Names\(([^)]*)\)', cities_src):
        names_pc[m.group(1)] = sum(1 for a in re.findall(r'"([^"]*)"', m.group(2)) if a)
    bespoke = {}
    for pn in plans:
        bespoke[pn] = plan_named[pn]
    for pidk, meth in java_cases.items():
        if pidk in bespoke or meth == "generic":
            continue
        bespoke[pidk] = method_named.get(meth, 3) + names_pc.get(pidk, 0) + 2
    for pidk, n in names_pc.items():
        if pidk in bespoke:
            bespoke[pidk] = max(bespoke[pidk], n)
    # ---- style sheets
    def leaves(o):
        if isinstance(o, dict):
            return sum(leaves(v) for k, v in o.items() if not k.startswith("_"))
        if isinstance(o, list):
            return sum(leaves(v) for v in o) if o else 1
        return 1

    style_rich = {}
    for f in glob.glob(os.path.join(TAM, "styles", "*.json")):
        style_rich[os.path.basename(f)[:-5]] = leaves(jload(f, {}))
    overlay_rich = {}
    for f in glob.glob(os.path.join(TAM, "styles", "overlays", "*.json")):
        overlay_rich[os.path.basename(f)[:-5]] = leaves(jload(f, {}))
    # ---- explore uniques (uniques.json + uniques_index.json + uniques_*.json)
    exp_dir = os.path.join(SKY, "explore")
    u_files = []
    p_u0 = os.path.join(exp_dir, "uniques.json")
    if os.path.exists(p_u0):
        u_files.append(p_u0)
    u_idx = jload(os.path.join(exp_dir, "uniques_index.json"), [])
    if isinstance(u_idx, dict):
        u_idx = u_idx.get("files", [])
    for fn in (u_idx if isinstance(u_idx, list) else []):
        fp = os.path.join(exp_dir, fn)
        if os.path.exists(fp) and fp not in u_files:
            u_files.append(fp)
    for fp in sorted(glob.glob(os.path.join(exp_dir, "uniques_*.json"))):
        if os.path.basename(fp) != "uniques_index.json" and fp not in u_files:
            u_files.append(fp)
    all_uniques = []
    seen_u = set()
    for fp in u_files:
        ud = jload(fp, [])
        if isinstance(ud, dict):
            ud = ud.get("uniques", [])
        for u in (ud if isinstance(ud, list) else []):
            if not isinstance(u, dict):
                continue
            uk = (u.get("id") or u.get("item"), u.get("x"), u.get("z"), u.get("place"))
            if uk not in seen_u:
                seen_u.add(uk)
                all_uniques.append(u)
    ex_unique = Counter()
    ex_kinds = defaultdict(set)
    for u in all_uniques:
        rg = rid_at(u.get("x", 0), u.get("z", 0)) or settle_region.get(u.get("place")) or u.get("region")
        if rg:
            ex_unique[rg] += 1
            if u.get("kind"):
                ex_kinds[rg].add(("unique", str(u.get("kind"))))
    # ---- minor site kinds by province (parse MinorSites.weights)
    ms = read(os.path.join(JAVA, "explore", "MinorSites.java"))
    wbody = ms[ms.find("static double[] weights"):]
    wbody = wbody[:wbody.find("private static boolean nineLands")]
    base_kinds = set()
    prov_kinds = defaultdict(set)
    cur = None
    for line in wbody.splitlines():
        m = re.search(r'case ((?:"[a-z_]+"(?:,\s*)?)+)\s*->', line)
        if m and "switch" not in line:
            cur = re.findall(r'"([a-z_]+)"', m.group(1))
        km = re.search(r"SiteKind\.([A-Z_]+)\.ordinal\(\)\]\s*(?:=|\+=)", line)
        if km:
            kind = km.group(1)
            if cur is None:
                base_kinds.add(kind)
            else:
                for p in cur:
                    prov_kinds[p].add(kind)
        if "p.equals(\"skyrim\")" in line and "||" in line:
            cur_dw = re.findall(r'p\.equals\("([a-z_]+)"\)', line)
            for p in cur_dw:
                prov_kinds[p].add("DWEMER_VENT")
    base_kinds.discard("DWEMER_VENT")
    nine = {"skyrim", "cyrodiil", "high_rock", "hammerfell", "summerset", "valenwood", "elsweyr", "black_marsh", "atmora", "realm"}

    # ---- music
    music_files = {os.path.basename(f)[:-4] for f in glob.glob(os.path.join(SOUNDS, "music", "*.ogg"))}
    town_tracks = {"nord": ["town_1", "town_2"], "orc": ["town_1", "town_2"], "imperial": ["town_imperial", "town_1"],
                   "breton": ["town_imperial", "town_1"], "dunmer": ["town_dunmer", "town_2"], "khajiit": ["town_desert", "town_2"],
                   "redguard": ["town_desert", "town_1"], "yokudan": ["town_desert", "town_1"], "altmer": ["town_elven", "town_1"],
                   "bosmer": ["town_elven", "town_2"], "argonian": ["town_desert", "town_2"], "maormer": ["town_elven", "town_2"],
                   "atmoran": ["town_1", "town_2"], "akaviri": ["town_imperial", "town_2"], "sload": ["town_dunmer", "town_2"]}
    amb_by_family = {
        "ocean": ["open_ocean", "coast_waves", "whale"], "beach": ["coast_waves", "gull"], "river": ["river", "frog"],
        "swamp": ["marsh_day", "marsh_night", "swamp_night", "frog"], "desert": ["desert_wind", "sand_hiss"],
        "jungle": ["jungle_day", "jungle_night", "jungle_bird", "cicada"], "ash": ["ash_storm", "volcanic_rumble", "volcano"],
        "frozen": ["wind_strong", "blizzard", "ice_creak"], "mountain": ["wind_strong", "wind_light", "hawk", "rock_fall"],
        "forest": ["forest_day", "forest_night", "owl", "bird_song"], "plains": ["plains_day", "plains_night", "cricket", "crow"],
    }

    # ---- items (province-level tag lists)
    def tag_len(group, key):
        j = jload(os.path.join(DATA, "tags", "items", *group, key + ".json"), {})
        return len(j.get("values", []))
    def gear_families(key):
        j = jload(os.path.join(DATA, "tags", "items", "gear", key + ".json"), {})
        fams = set()
        for v in j.get("values", []):
            n = (v if isinstance(v, str) else v.get("id", "")).split(":")[-1]
            fams.add(re.sub(r"_(sword|dagger|bow|axe|war_axe|battleaxe|greatsword|mace|warhammer|helmet|chestplate|"
                            r"leggings|boots|shield|spear|crossbow|staff|katana|longsword|club|gauntlets|hood|cuirass)$", "", n))
        # the tiers every province shares (they fall back to Skyrim's leveled lists anyway) are not signature gear
        universal = {"iron", "steel", "steel_plate", "banded_iron", "leather", "hide", "studded", "scaled", "fur",
                     "orcish", "dwarven", "elven", "elven_gilded", "glass", "ebony", "daedric", "dragonbone", "silver"}
        return len(fams - universal)
    legend =read(os.path.join(JAVA, "loot", "LegendaryUniques.java"))
    uniq_by_prov = Counter(re.findall(r'def\("[a-z_]+",\s*"[a-z_:]+",\s*"([a-z_]+)"', legend))
    identity_ids = set(lore_places) | set(dun_identity) | quest_places
    identity_ids |= set(re.findall(r'def\("[a-z_]+",\s*"[a-z_:]+",\s*"[a-z_]+",\s*"([a-z_]+)"', legend))
    for u in all_uniques:
        if isinstance(u, dict) and u.get("place"):
            identity_ids.add(u["place"])

    def gear_key(r):
        if r["id"] == "morrowind/solstheim":
            return "solstheim"
        if r["id"] == "high_rock/wrothgar":
            return "orsinium"
        return r["province"]

    pj = jload(os.path.join(TAM, "palettes.json"), {"palettes": {}})
    radiant_cultures = [set(t.get("cultures", [])) for t in radiant]
    sp_stats = load_streetplan_stats()

    # ---- per-region raw
    for r in land:
        rid, prov, cult = r["id"], r["province"], (r.get("culture") or "").lower()
        a = max(area[rid], 1e-6)
        R[rid]["km2"] = area[rid]
        ps = pl_by.get(rid, [])
        types = Counter(p["type"] for p in ps)
        towns = [p for p in ps if p["type"] in ("capital", "city", "town")]
        R[rid]["towns"] = len(towns)
        R[rid]["small"] = types["village"] + types["farm"] + types["stables"] + types["homestead"]
        R[rid]["bespoke"] = sum(1 for p in towns if p["id"] in bespoke or p["id"] in java_cases)
        R[rid]["bespoke_all"] = sum(1 for p in ps if p["id"] in bespoke)
        R[rid]["named_bldgs"] = sum(
            max(bespoke.get(p["id"], 0), sp_stats.get(p["id"], {}).get("named_bldgs", 0)) for p in ps
        )
        # dungeons
        dun = [p for p in ps if p["type"] in ("dungeon_fixed", "fort")]
        R[rid]["dungeons"] = len(dun)
        R[rid]["identified"] = sum(1 for p in dun if p["id"] in identity_ids or p.get("variant") in uniques
                                   or p["id"] in marquee_plans)
        themes, natural, built, rooms, rosters = set(), set(), set(), set(), set()
        kit = kit_for(r)
        for p in dun:
            s = sites.get(p.get("variant"))
            if not s:
                continue
            th = s["theme"]
            k = s["kit"] or kit
            look = (s["site"], k, s["flavor"])
            themes.add(th)
            st = styles.get(th, "SURFACE")
            if st == "NATURAL":
                natural.add((th, k))  # the carved look = theme x kit palette; the roster is who lives there
                rosters.add((th, s["flavor"]))
            elif st == "BUILT":
                built.add(look)
            rooms |= rooms_by_theme.get(th, set())
            if p.get("variant") in uniques or p["id"] in marquee_plans:
                R[rid]["marquee"] += 1
        # random sites that may generate here
        if prov != "skyrim" and rid != "morrowind/solstheim":
            for name, j in randoms:
                where = j.get("where")
                ok = (not where) or "*" in where or prov in where or ("realm" in where and prov in ("atmora",) and False)
                if not ok:
                    continue
                th = j.get("theme")
                st = styles.get(th, "SURFACE")
                k = j.get("kit") or kit
                if th == "cave" and not j.get("kit"):
                    looks = {("cave", k, "roster%d" % i) for i in range(6)}
                else:
                    looks = {(name, k, j.get("flavor"))}
                themes.add(th)
                if st == "NATURAL":
                    natural.add((th, k))
                    rosters |= {(th, l[2]) for l in looks}
                elif st == "BUILT":
                    built |= looks
                rooms |= rooms_by_theme.get(th, set())
        sets[rid]["themes"] = {t for t in themes if t}
        sets[rid]["natural"] = natural
        sets[rid]["rosters"] = rosters
        sets[rid]["built"] = built
        sets[rid]["rooms"] = rooms
        # landmarks
        lm = [p for p in ps if p["type"] in ("landmark", "standing_stone", "word_wall", "dragon_mound", "daedric_shrine")]
        R[rid]["landmarks"] = len(lm) + (ex_unique.get(rid, 0) if prov != "skyrim" else 0)
        sets[rid]["lm_kinds"] = {(p["type"], p.get("variant") or "") for p in lm} | {("dungeon_" + (p.get("variant") or "")) for p in ps if p["type"] == "dungeon_fixed" and styles.get(sites.get(p.get("variant"), {}).get("theme"), "") == "SURFACE"} | (ex_kinds.get(rid, set()) if prov != "skyrim" else set())
        R[rid]["dragons"] = types["dragon_mound"] + sum(1 for p in ps if p.get("variant") == "dragon_lair")
        R[rid]["shrines"] = types["daedric_shrine"]
        # creatures
        biomes = region_biomes(r, pj)
        fams = {biome_family(b) for b in biomes}
        h_all, w_all, c_all, any_all = spawn_kinds(rid, prov)
        per_h, per_w = [], []
        for fam in fams:
            h, w, c, _ = spawn_kinds(rid, prov, fam)
            per_h.append(len(h))
            per_w.append(len(w))
        R[rid]["c_hostile"] = sum(per_h) / len(per_h) if per_h else 0
        R[rid]["c_wild"] = sum(per_w) / len(per_w) if per_w else 0
        R[rid]["c_cave"] = len(c_all)
        R[rid]["c_total"] = len(any_all)
        sets[rid]["families"] = fams
        # flora
        trees, plants = set(), set()
        for b in biomes:
            t, p_ = biome_flora(b)
            trees |= t
            plants |= p_
        pal_blk = pj.get("palettes", {}).get(rid, {})
        micro_ids = set()
        for m in pal_blk.get("micro", []):
            if isinstance(m, dict):
                if m.get("id"):
                    micro_ids.add("micro:" + str(m["id"]))
                trees |= {"micro:" + str(t) for t in m.get("trees", [])}
                trees |= {"prop:" + str(pr) for pr in m.get("props", [])}
                plants |= {"micro:" + str(g) for g in m.get("groundcover", [])}
                if m.get("surface"):
                    plants.add("surf:" + str(m["surface"]))
        R[rid]["biomes"] = len(biomes | micro_ids)
        R[rid]["trees"] = len(trees)
        R[rid]["plants"] = len(plants)
        # style
        looks = set()
        tv = towns + [x for x in ps if x["type"] in ("village",)]
        for p in tv:
            if p["id"] in bespoke or p["id"] in java_cases:
                looks.add("plan:" + p["id"])
            elif prov == "morrowind" and cult == "dunmer":
                looks.add("kit:velothi:%d" % (sum(map(ord, p["id"])) % 4))
            else:
                looks.add("kit:" + (p.get("culture") or cult))
        for p in ps:
            if p["type"] in ("farm", "stables", "homestead"):
                looks.add("kit:" + (p.get("culture") or cult) + ":" + (p.get("variant") or p["type"]))
        if tv and all(p["id"] in bespoke or p["id"] in java_cases for p in tv):
            looks.add("kit:" + cult)
        R[rid]["looks"] = len(looks)
        rshort = rid.split("/")[-1]
        R[rid]["sheet"] = style_rich.get(cult, style_rich.get(r.get("culture", ""), 0)) + overlay_rich.get(rshort, 0)
        # lore
        R[rid]["notes_all"] = R[rid]["lore_notes"] + R[rid]["books"]
        # music / ambience
        mk = {"yokuda": "hammerfell", "pyandonea": "summerset", "thras": "sea"}.get(r.get("music") or prov, r.get("music") or prov)
        shared_ex = [t for t in music_files if t.startswith("explore_")]
        prov_ex = [t for t in music_files if t.startswith(mk + "_day") or t.startswith(mk + "_night") or t.startswith(prov + "_")]
        ex = sorted(set(shared_ex) | set(prov_ex)) if mk != "skyrim" else shared_ex
        R[rid]["m_explore"] = len(ex)
        R[rid]["m_town"] = len([t for t in town_tracks.get(cult, ["town_1"]) if t in music_files])
        beds = set()
        for fam in fams:
            beds |= set(amb_by_family.get(fam, []))
        pal_blk = pj.get("palettes", {}).get(rid) or pj.get(rid) or pj.get("palettes", {}).get("province/" + prov, {})
        for m in pal_blk.get("micro", []):
            if isinstance(m, dict) and m.get("ambience"):
                beds.add(m["ambience"])
        R[rid]["m_amb"] = len(beds)
        # items
        gk = gear_key(r)
        R[rid]["i_gear"] = tag_len(["gear"], gk)
        R[rid]["i_sets"] = gear_families(gk)
        R[rid]["i_ingr"] = tag_len(["loot", "ingredients"], gk)
        R[rid]["i_trink"] = tag_len(["loot", "trinkets"], gk)
        R[rid]["i_unique"] = max(uniq_by_prov.get(prov, 0), uniq_by_prov.get(gk, 0))
        kinds = set(base_kinds) | prov_kinds.get(prov, set())
        if prov in nine:
            kinds.add("WAYSHRINE")
        R[rid]["l_minor"] = len(kinds)
        R[rid]["ex_unique"] = ex_unique.get(rid, 0)
        # radiant pool
        cl = cult if cult not in ("none",) else ""
        R[rid]["radiant"] = sum(1 for c in radiant_cultures if "*" in c or cl in c or (prov != "skyrim" and "foreign" in c and prov in ("atmora", "akavir", "yokuda", "pyandonea", "thras")))
        if prov == "skyrim" and rid != "morrowind/solstheim":
            R[rid]["radiant"] = 0

    glob_stats = {
        "npc_roles": len(re.findall(r"^\s+[A-Z_]+\((?:true|false)", read(os.path.join(JAVA, "society", "NpcRole.java")), re.M)),
        "radiant_templates": len(radiant),
        "creature_kinds_total": len({m["entity"] for rr in rules for g in rr.get("groups", []) for m in g.get("members", [g]) if "entity" in m}),
        "recipes": len(glob.glob(os.path.join(DATA, "recipes", "**", "*.json"), recursive=True)),
        "room_templates_all": len(set().union(*rooms_by_theme.values())),
        "cave_variant_kinds": len(cave_variants),
        "places_total": len(places),
        "bespoke_settlements": sorted(bespoke),
        "quests_total": len(quest_rows),
    }
    return dict(regions=land, R=R, sets=sets, area=area, glob=glob_stats, prov_of=prov_of,
                places=places, bespoke=bespoke, quest_rows=quest_rows, qfiles=qfiles, qplaces_global=qplaces_global)


# ------------------------------------------------------------------------------------------------ scoring
def metric_values(rid_list, R, sets, mid):
    """(numerator, denominator) summed over regions; or list of per-region values for div/prov."""
    kind = M_BY_ID[mid][3]
    ks = {
        "s_towns": ("towns", "km2"), "s_small": ("small", "km2"), "q_total": ("quests", "km2"),
        "d_density": ("dungeons", "km2"), "l_density": ("landmarks", "km2"), "l_unique": ("ex_unique", "km2"),
        "r_places": ("lore_places", "km2"), "r_notes": ("notes_all", "km2"),
    }
    if mid in ks:
        n, d = ks[mid]
        num = sum(R[r][n] for r in rid_list)
        den = sum(R[r][d] for r in rid_list)
        return num, den
    if mid == "c_bosses":
        num = sum(R[r]["dragons"] + R[r]["shrines"] + R[r]["bosses"] for r in rid_list)
        return num, sum(R[r]["km2"] for r in rid_list)
    if mid == "s_bespoke":
        return sum(R[r]["bespoke"] for r in rid_list), sum(R[r]["towns"] for r in rid_list)
    if mid == "s_bldgs":
        return sum(R[r]["named_bldgs"] for r in rid_list), sum(R[r]["towns"] for r in rid_list)
    if mid == "s_npcs":
        return sum(R[r]["npcs"] for r in rid_list), sum(R[r]["towns"] for r in rid_list)
    if mid == "n_lines":
        return sum(R[r]["npc_lines"] + R[r]["scene_lines"] for r in rid_list), sum(R[r]["towns"] for r in rid_list)
    if mid == "n_scenes":
        return sum(R[r]["scenes"] for r in rid_list), sum(R[r]["towns"] for r in rid_list)
    if mid == "q_per_town":
        return sum(R[r]["quests"] for r in rid_list), sum(R[r]["towns"] for r in rid_list)
    if mid == "d_identity":
        return sum(R[r]["identified"] for r in rid_list), sum(R[r]["dungeons"] for r in rid_list)
    if mid == "q_placed":
        return sum(R[r]["q_placed"] for r in rid_list), sum(R[r]["quests"] for r in rid_list)
    # per-region varieties
    def one(r):
        s, x = sets[r], R[r]
        return {
            "n_roles": len(s["roles"]), "n_say": x["say"], "q_kinds": len(s["qkinds"]),
            "d_themes": len(s["themes"]), "d_caverns": len(s["natural"]), "d_rosters": len(s["rosters"]),
            "d_built": len(s["built"]), "d_rooms": len(s["rooms"]), "d_marquee": x["marquee"], "d_bosses": x["bosses"],
            "c_hostile": x["c_hostile"], "c_wild": x["c_wild"], "c_cave": x["c_cave"], "c_total": x["c_total"],
            "f_biomes": x["biomes"], "f_trees": x["trees"], "f_plants": x["plants"], "y_looks": x["looks"],
            "y_sheet": x["sheet"], "l_kinds": len(s["lm_kinds"]), "l_minor": x["l_minor"], "r_flavor": x["flavor"],
            "m_explore": x["m_explore"], "m_town": x["m_town"], "m_amb": x["m_amb"], "i_gear": x["i_gear"], "i_sets": x["i_sets"],
            "i_ingr": x["i_ingr"], "i_trink": x["i_trink"], "i_unique": x["i_unique"],
        }[mid]
    vals = [one(r) for r in rid_list]
    return (sum(vals) / len(vals)) if vals else 0.0, None


def value_of(rid_list, R, sets, mid):
    a, b = metric_values(rid_list, R, sets, mid)
    if b is None:
        return a
    if b:
        return a / b
    # nothing to divide by: no area / no town. A region that has none of the thing scores 0; one that has some is n/a.
    return 0.0 if not a else None


def build_scores(data):
    R, sets, regions = data["R"], data["sets"], data["regions"]
    ids = [r["id"] for r in regions]
    prov_ids = defaultdict(list)
    for r in regions:
        prov_ids[r["province"]].append(r["id"])
    sky = [i for i in prov_ids[BASE] ]
    refs = {}
    for m in METRICS:
        mid = m[0]
        v = value_of(sky, R, sets, mid)
        if not v and m[4] is not None:
            v = m[4]
        if mid in SKYRIM_MEASURED:
            v = SKYRIM_MEASURED[mid]
        refs[mid] = v
    rows = {}

    def score_row(rid_list):
        raw, sc = {}, {}
        for m in METRICS:
            mid = m[0]
            v = value_of(rid_list, R, sets, mid)
            raw[mid] = v
            ref = refs[mid]
            if v is None or not ref:
                sc[mid] = None
            else:
                sc[mid] = min(CAP, 100.0 * v / ref)
        cats = {}
        for c, _ in CATS:
            vs = [sc[m[0]] for m in METRICS if m[1] == c and sc[m[0]] is not None]
            # 60% mean + 40% weakest sub-metric: one empty column (no lore, no hand-built dungeons) must show
            cats[c] = (0.6 * sum(vs) / len(vs) + 0.4 * min(vs)) if vs else None
        return dict(raw=raw, sc=sc, cats=cats, km2=sum(R[r]["km2"] for r in rid_list), n=len(rid_list))
    for prov, lst in prov_ids.items():
        rows["P:" + prov] = score_row(lst)
    for rid in ids:
        rows["R:" + rid] = score_row([rid])
        kind = next(r["kind"] for r in regions if r["id"] == rid)
        rows["R:" + rid]["sliver"] = rows["R:" + rid]["km2"] < 1.0 and kind != "realm"
    return rows, refs, prov_ids


# ------------------------------------------------------------------------------------------------ output
HEAT_U = [(100, "■"), (80, "▣"), (50, "▤"), (25, "▥"), (0, "□")]
HEAT_A = [(100, "#"), (80, "+"), (50, "="), (25, "-"), (0, ".")]


def heat(v, ascii_only):
    if v is None:
        return "?"
    for t, s in (HEAT_A if ascii_only else HEAT_U):
        if v >= t - 1e-9:
            return s
    return "."


def fmt_cell(v, ascii_only):
    return "%s%3d" % (heat(v, ascii_only), round(v)) if v is not None else "  ? "


def province_order(prov_ids):
    first = ["skyrim", "cyrodiil", "morrowind", "high_rock", "hammerfell", "elsweyr", "valenwood", "summerset",
             "black_marsh", "atmora", "akavir", "yokuda", "pyandonea", "thras"]
    return [p for p in first if p in prov_ids] + [p for p in prov_ids if p not in first]


def table(rows, keys, label_of, ascii_only, width=26):
    head = "%-*s %7s " % (width, "", "km2") + " ".join("%4s" % c for c, _ in CATS) + "  mean"
    out = [head]
    for k in keys:
        row = rows[k]
        cs = [row["cats"][c] for c, _ in CATS]
        vs = [c for c in cs if c is not None]
        mean = sum(vs) / len(vs) if vs else None
        out.append("%-*s %7.1f " % (width, label_of(k)[:width], row["km2"]) + " ".join(fmt_cell(c, ascii_only) for c in cs)
                   + "  " + fmt_cell(mean, ascii_only))
    return out


def lowest(rows, n, prefix="R:"):
    cells = []
    for k, row in rows.items():
        if not k.startswith(prefix):
            continue
        if k.startswith("R:" + BASE + "/"):
            continue
        if row.get("sliver"):  # land slivers under 1 km2 (Artaeum, Bitter Coast) are too small to judge
            continue
        if row["km2"] <= 0 and not INCLUDE_REALMS:  # beyond-the-map realms have no raster area; see --realms
            continue
        for c, _ in CATS:
            v = row["cats"][c]
            if v is not None:
                cells.append((v, k[2:], c))
    cells.sort()
    return cells[:n]


FLOOR = 0.8


def floor_rows(data, refs, prov_ids):
    """Per province and sub-metric: (province, metric id, kind, current numerator, current den, floor value,
    items missing to reach FLOOR x Skyrim). Density/unit metrics give a count; div/prov give the per-region value."""
    R, sets = data["R"], data["sets"]
    out = []
    for prov, lst in prov_ids.items():
        if prov == BASE:
            continue
        km2 = sum(R[r]["km2"] for r in lst)
        if km2 <= 0:
            continue
        for m in METRICS:
            mid, kind = m[0], m[3]
            ref = refs[mid]
            if not ref:
                continue
            a, b = metric_values(lst, R, sets, mid)
            if kind in ("density", "unit"):
                if b is None:
                    continue
                need = FLOOR * ref * b
                out.append((prov, mid, kind, a, b, need, max(0.0, need - a)))
            else:
                need = FLOOR * ref
                out.append((prov, mid, kind, a, None, need, max(0.0, need - a)))
    return out


def deficits(rows, refs, n, include_realms=False):
    """Lowest province-level sub-metric scores (Skyrim excluded), as (score, province, metric, value, ref)."""
    out = []
    for k, row in rows.items():
        if not k.startswith("P:") or k == "P:" + BASE:
            continue
        if row["km2"] <= 0 and not include_realms:
            continue
        for m in METRICS:
            s = row["sc"][m[0]]
            if s is not None:
                out.append((s, k[2:], m[0], row["raw"][m[0]], refs[m[0]]))
    out.sort(key=lambda t: (t[0], t[1]))
    return out[:n]


def raw_tables(rows, prov_order):
    out = []
    ids = [m[0] for m in METRICS]
    for c, cname in CATS:
        ms = [m for m in METRICS if m[1] == c]
        out.append("")
        out.append("%s  %s   (raw value; Skyrim ref in the last column)" % (c, cname))
        out.append("%-34s %s" % ("", " ".join("%8s" % p[:8] for p in prov_order)))
        for m in ms:
            cells = []
            for p in prov_order:
                v = rows["P:" + p]["raw"][m[0]]
                cells.append("%8s" % ("-" if v is None else ("%.2f" % v if abs(v) < 100 and v != int(v) else "%d" % round(v))))
            out.append("%-34s %s" % ((m[2] + (" [P]" if m[3] == "prov" else ""))[:34], " ".join(cells)))
    return out


MINOR_HOLD_SEATS = {"winterhold", "falkreath", "morthal", "dawnstar", "raven_rock", "college_of_winterhold"}


def settlement_tier(p):
    t = p.get("type", "")
    pid = p.get("id", "")
    if pid in MINOR_HOLD_SEATS or t == "city":
        return "city"
    if t == "capital":
        return "capital"
    if t == "town":
        return "town"
    if t == "village":
        return "village"
    if t in ("farm", "stables", "homestead"):
        return "hamlet"
    return None


def load_streetplan_stats():
    """Return dict: settlement_id -> dict(interiors, inns, merchants, named_bldgs, trainers)."""
    out = {}
    sp_path = os.path.join(SKY, "baked", "streetplan.bin")
    if os.path.exists(sp_path):
        try:
            import struct
            with open(sp_path, "rb") as f:
                raw = f.read()
            hlen = struct.unpack_from(">H", raw, 0)[0]
            raw = zlib.decompress(raw[2 + hlen:])
            pos = 0

            def ri():
                nonlocal pos
                v = struct.unpack_from(">i", raw, pos)[0]
                pos += 4
                return v

            def rutf():
                nonlocal pos
                ln = struct.unpack_from(">H", raw, pos)[0]
                pos += 2
                s = raw[pos:pos + ln].decode("utf-8")
                pos += ln
                return s

            def rints():
                nonlocal pos
                n = ri()
                a = struct.unpack_from(f">{n}i", raw, pos)
                pos += 4 * n
                return a

            def rfloats():
                nonlocal pos
                n = ri()
                pos += 4 * n

            def rbytes():
                nonlocal pos
                n = ri()
                pos += n

            _magic, _ver, ns = ri(), ri(), ri()
            s_ids = []
            for _ in range(ns):
                s_ids.append(rutf())
                ri()
                ri()
                ri()
            _s_node, _s_edge, s_door, _s_plot, s_spec = rints(), rints(), rints(), rints(), rints()
            rfloats()
            rfloats()
            rfloats()
            rbytes()
            rints()
            rints()
            rints()
            rints()
            rfloats()
            rbytes()
            rbytes()
            rints()
            rints()
            rints()
            rints()
            rints()
            rbytes()
            nr = ri()
            pos += nr
            rints()
            rints()
            rints()
            rints()
            rints()
            rints()
            rbytes()
            spec_ints = rints()
            nn = ri()
            spec_names = [rutf() for _ in range(nn)]

            BLDG_ARCH = set(range(16)) | {32, 33, 34}
            TRADE_USES = {6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 21, 22, 24}
            TRADE_ARCH = {3, 4, 5, 6, 10, 12, 19}
            for idx, sid in enumerate(s_ids):
                i0, i1 = s_spec[idx], s_spec[idx + 1]
                specs = [spec_ints[i * 20:(i + 1) * 20] for i in range(i0, i1)]
                names = spec_names[i0:i1]
                bldgs = [s for s in specs if s[0] in BLDG_ARCH]
                inns = [s for s in bldgs if s[0] == 3 or (s[0] == 34 and s[16] == 6)]
                merch = [s for s in specs if s[0] in TRADE_ARCH or (s[0] == 34 and s[16] in TRADE_USES)]
                named = sum(1 for n in names if n and "," not in n)
                out[sid] = dict(interiors=len(bldgs), inns=len(inns), merchants=len(merch), named_bldgs=named)
        except Exception:
            pass

    # Merge cityplans/*.json so newly authored/enriched plans are reflected even before streetplan.bin is re-baked
    NON_INTERIOR_KINDS = {"field", "garden", "lumber", "pen", "dock", "yard", "camp", "graveyard", "pond", "orchard"}
    TRADE_KINDS = {"inn", "store", "smithy", "alchemist", "stables", "mill", "meadery", "sawmill", "bakery", "butcher", "fishery"}
    for pf in sorted(glob.glob(os.path.join(TAM, "cityplans", "*.json"))):
        if os.path.basename(pf) == "index.json":
            continue
        sid = os.path.basename(pf)[:-5]
        pj = jload(pf, {})
        if not isinstance(pj, dict):
            continue
        named_set = {v for v in (pj.get("names") or {}).values() if v}
        int_cnt = 0
        inn_cnt = 1 if (pj.get("innKey") or (pj.get("names") or {}).get("inn")) else 0
        merch_cnt = len([v for v in (pj.get("names") or {}).values() if v])
        for sec in ("buildings", "landmarks", "plazas", "queue"):
            for b in (pj.get(sec) or []):
                if not isinstance(b, dict):
                    continue
                if b.get("name") or b.get("id"):
                    named_set.add(b.get("name") or b.get("id"))
                if sec in ("buildings", "queue"):
                    k = (b.get("kind") or "").lower()
                    u = (b.get("use") or "").lower()
                    cnt = int(b.get("count", 1) or 1)
                    if k and k not in NON_INTERIOR_KINDS:
                        int_cnt += cnt
                    if k == "inn" or u == "inn":
                        inn_cnt = max(inn_cnt, 1)
                    if k in TRADE_KINDS or u in TRADE_KINDS:
                        merch_cnt += cnt
        prev = out.get(sid, dict(interiors=0, inns=0, merchants=0, named_bldgs=0))
        out[sid] = dict(
            interiors=max(prev["interiors"], int_cnt),
            inns=max(prev["inns"], inn_cnt),
            merchants=max(prev["merchants"], merch_cnt),
            named_bldgs=max(prev["named_bldgs"], len(named_set)),
        )
    return out


def print_tiers(data, f_prov=None, f_reg=None):
    floors_path = os.path.join(HERE, "floors.json")
    tier_floors = jload(floors_path, {}).get("settlement_tiers", {})
    places = data["places"]
    bespoke = data["bespoke"]
    prov_of = data["prov_of"]
    sp_stats = load_streetplan_stats()

    settlements = [p for p in places if settlement_tier(p) is not None]
    by_id = {p["id"]: p for p in settlements}

    # Gather per-settlement NPCs, roles, merchants, trainers, quests
    npcs_by_s = defaultdict(list)
    roles_by_s = defaultdict(set)
    merch_by_s = Counter()
    train_by_s = Counter()
    quests_by_s = Counter()

    MERCH_ROLES = {"merchant", "blacksmith", "alchemist", "innkeeper", "general_trader", "jeweller",
                   "tailor", "bookseller", "butcher", "fletcher", "fence", "apothecary", "trader",
                   "court_wizard", "carriage_driver", "ferryman", "stablemaster"}

    qplaces_global = data["qplaces_global"]

    def resolve_settlement(pl_key, fp):
        if pl_key in by_id:
            return pl_key
        coords = None
        for src in (fp, qplaces_global):
            if pl_key in src and isinstance(src[pl_key], dict) and "x" in src[pl_key]:
                coords = (src[pl_key]["x"], src[pl_key]["z"])
                break
        if coords:
            px, pz = coords
            best_s, best_d2 = None, 220 * 220
            for s in settlements:
                d2 = (s["x"] - px) ** 2 + (s["z"] - pz) ** 2
                if d2 < best_d2:
                    best_s, best_d2 = s["id"], d2
            return best_s
        return None

    npc_settlement = {}
    for f in data["qfiles"]:
        j = jload(f, {})
        fp = j.get("places", {}) if isinstance(j.get("places"), dict) else {}
        for n in j.get("npcs", []):
            sid = resolve_settlement(n.get("place"), fp) if n.get("place") else None
            if sid:
                npc_settlement[n.get("id", "")] = sid
                npcs_by_s[sid].append(n)
                role = (n.get("role") or "citizen").lower()
                roles_by_s[sid].add(role)
                if role in MERCH_ROLES or n.get("shop") or n.get("merchant"):
                    merch_by_s[sid] += 1
                if n.get("trainer") or n.get("skill") or "trainer" in role:
                    train_by_s[sid] += 1
        for q in j.get("quests", []):
            touched = set()
            st = str(q.get("start", ""))
            if st.startswith("npc:"):
                ns = npc_settlement.get(st.split(":", 1)[1])
                if ns:
                    touched.add(ns)
            if q.get("place") in by_id:
                touched.add(q["place"])
            for o in q.get("objectives", []):
                osid = resolve_settlement(o.get("place"), fp) if o.get("place") else None
                if osid:
                    touched.add(osid)
            for sid in touched:
                quests_by_s[sid] += 1

    # Also check society/*.json (trainers.json, courts.json, skyrim_residents.json, etc.)
    for tf in sorted(glob.glob(os.path.join(SKY, "society", "*.json"))):
        tj = jload(tf, {})
        if isinstance(tj, dict):
            smap = tj.get("settlements") if isinstance(tj.get("settlements"), dict) else tj
            for k, v in smap.items():
                if k not in by_id:
                    continue
                if isinstance(v, list):
                    train_by_s[k] = max(train_by_s[k], len(v))
                    for item in v:
                        if isinstance(item, dict) and item.get("name"):
                            npcs_by_s[k].append({"id": "trainer:" + item["name"]})
                            roles_by_s[k].add(item.get("workplace") or "trainer")
                elif isinstance(v, dict):
                    if "trainers" in v and isinstance(v["trainers"], list):
                        train_by_s[k] = max(train_by_s[k], len(v["trainers"]))
                    if isinstance(v.get("residents"), list):
                        res_trainers = 0
                        for res in v["residents"]:
                            if not isinstance(res, dict) or not res.get("name"):
                                continue
                            npcs_by_s[k].append(res)
                            role = (res.get("role") or "citizen").lower()
                            roles_by_s[k].add(role)
                            if res.get("court_role"):
                                roles_by_s[k].add(str(res["court_role"]).lower())
                            if role in MERCH_ROLES or res.get("shop") or res.get("merchant"):
                                merch_by_s[k] += 1
                            if res.get("trainer") or res.get("skill") or "trainer" in role:
                                res_trainers += 1
                        train_by_s[k] = max(train_by_s[k], res_trainers)
                    else:
                        for rk in ("jarl", "ruler", "steward", "court_wizard", "priest", "housecarl"):
                            if v.get(rk):
                                npcs_by_s[k].append({"id": f"court:{rk}:{v[rk]}"})
                                roles_by_s[k].add(rk)

    def match_s(s):
        rid = s.get("region", "")
        prov = prov_of.get(rid, rid.split("/")[0] if "/" in rid else "")
        if f_prov and prov != f_prov:
            return False
        if f_reg and rid != f_reg and rid.split("/")[-1] != f_reg:
            return False
        return True

    sel = [s for s in settlements if match_s(s)]
    tier_order = {"capital": 0, "city": 1, "town": 2, "village": 3, "hamlet": 4}
    sel.sort(key=lambda s: (tier_order.get(settlement_tier(s), 9), s.get("region", ""), s["id"]))

    print("=== Settlement Tier Audit (DESIGN-PRINCIPLES Section 4.4) ===")
    hdr = f"{'Settlement':<24} {'Region':<24} {'Tier':<8} | {'NPCs':>9} {'Int':>9} {'NamedB':>9} {'Merch':>9} {'Train':>9} {'Inns':>8} {'Roles':>9} {'Quests':>9}"
    print(hdr)
    print("-" * len(hdr))
    for s in sel:
        sid = s["id"]
        rid = s.get("region", "")
        tier = settlement_tier(s)
        tf = tier_floors.get(tier, {})
        sp = sp_stats.get(sid, {})
        n_npcs = len(npcs_by_s[sid])
        n_int = sp.get("interiors", 1 if tier in ("village", "hamlet") else 0)
        n_named = max(bespoke.get(sid, 0), sp.get("named_bldgs", 0))
        n_merch = max(merch_by_s[sid], sp.get("merchants", 0))
        n_train = train_by_s[sid]
        n_inns = sp.get("inns", 1 if s.get("variant") == "inn" else 0)
        n_roles = len(roles_by_s[sid])
        n_quests = quests_by_s[sid]

        def cell(val, col_key):
            fl = tf.get(col_key, {}).get("abs", 0)
            fl_s = f"{fl:g}"
            return f"{val}/{fl_s}"

        print(f"{sid[:24]:<24} {rid[:24]:<24} {tier:<8} | "
              f"{cell(n_npcs, 'named_npcs'):>9} {cell(n_int, 'interiors'):>9} {cell(n_named, 'named_buildings'):>9} "
              f"{cell(n_merch, 'merchants'):>9} {cell(n_train, 'trainers'):>9} {cell(n_inns, 'inns'):>8} "
              f"{cell(n_roles, 'roles'):>9} {cell(n_quests, 'quests'):>9}")
    print(f"\nTotal settlements shown: {len(sel)} (format: actual/abs_floor)")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", default=None)
    ap.add_argument("--lowest", type=int, default=0)
    ap.add_argument("--raw", action="store_true")
    ap.add_argument("--ascii", action="store_true")
    ap.add_argument("--write-doc", default=None)
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--deficits", type=int, default=0, help="print the N lowest province-level sub-metrics")
    ap.add_argument("--assert-metric", default=None, metavar="IDS",
                    help="exit 1 if any region is under the 80%% floor on these sub-metrics, e.g. d_identity,r_places")
    ap.add_argument("--assert-floor", default=None, metavar="CATS",
                    help="exit 1 if any region scores under 80 in these categories, e.g. NPC,LOR (batch 'done' check)")
    ap.add_argument("--floors", action="store_true", help="print how many items each province is short of the 80%% floor")
    ap.add_argument("--realms", action="store_true", help="include the beyond-the-map realms (no raster area) in the lowest lists")
    ap.add_argument("--province", default=None, help="filter output/checks to a single province (e.g. skyrim)")
    ap.add_argument("--region", default=None, help="filter output/checks to a single region (e.g. whiterun or skyrim/whiterun)")
    ap.add_argument("--tiers", action="store_true", help="print each settlement against the Section 4.4 tier table")
    args = ap.parse_args()
    global INCLUDE_REALMS
    INCLUDE_REALMS = args.realms
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    data = collect()
    if args.tiers:
        return print_tiers(data, args.province, args.region)
    rows, refs, prov_ids = build_scores(data)
    order = province_order(prov_ids)

    def match_reg_id(rid):
        prov = data["prov_of"].get(rid, rid.split("/")[0] if "/" in rid else "")
        if args.province and prov != args.province:
            return False
        if args.region and rid != args.region and rid.split("/")[-1] != args.region:
            return False
        return True

    if args.province:
        order = [p for p in order if p == args.province]
    elif args.region:
        matched_provs = {data["prov_of"].get(r) for p in order for r in prov_ids[p] if match_reg_id(r)}
        order = [p for p in order if p in matched_provs]

    reg_label = lambda k: k[2:]
    prov_label = lambda k: k[2:]
    ascii_only = args.ascii or (sys.stdout.encoding or "").lower().replace("-", "") not in ("utf8", "utf8sig")
    if args.json is not False:
        path = args.json or os.path.join(OUT, "parity.json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"refs": refs, "rows": rows, "global": data["glob"]}, f, indent=1, default=float)
    low = [t for t in lowest(rows, (args.lowest or 10) * 10) if match_reg_id(t[1])][: (args.lowest or 10)]
    if args.assert_metric:
        mids = [c.strip() for c in args.assert_metric.split(",") if c.strip()]
        unknown = [m for m in mids if m not in M_BY_ID]
        if unknown:
            print("unknown metric id(s): %s; known: %s" % (",".join(unknown), ",".join(M_BY_ID)))
            return 2
        bad = []
        skip_base = not (args.province or args.region)
        for k, row in rows.items():
            if not k.startswith("R:") or row.get("sliver") or (skip_base and k.startswith("R:" + BASE + "/")):
                continue
            if not match_reg_id(k[2:]):
                continue
            if row["km2"] <= 0 and not INCLUDE_REALMS:
                continue
            for m in mids:
                v = row["sc"].get(m)
                if v is not None and v < FLOOR * 100:
                    bad.append((v, k[2:], m, row["raw"][m], refs[m]))
        for v, rid, m, raw, ref in sorted(bad):
            print("BELOW FLOOR  %3d  %-30s %-11s value %.3g (Skyrim %.3g, floor %.3g)" % (round(v), rid, m, raw, ref, FLOOR * ref))
        print("%d region x metric cells below the %d%% floor (metrics: %s)" % (len(bad), FLOOR * 100, ",".join(mids)))
        return 1 if bad else 0
    if args.assert_floor:
        cats = [c.strip().upper() for c in args.assert_floor.split(",") if c.strip()]
        bad = []
        skip_base = not (args.province or args.region)
        for k, row in rows.items():
            if not k.startswith("R:") or row.get("sliver") or (skip_base and k.startswith("R:" + BASE + "/")):
                continue
            if not match_reg_id(k[2:]):
                continue
            if row["km2"] <= 0 and not INCLUDE_REALMS:
                continue
            for c in cats:
                v = row["cats"].get(c)
                if v is not None and v < FLOOR * 100:
                    bad.append((v, k[2:], c))
        for v, rid, c in sorted(bad):
            print("BELOW FLOOR  %3d  %-30s %s" % (round(v), rid, c))
        print("%d region x category cells below %d (categories: %s)" % (len(bad), FLOOR * 100, ",".join(cats)))
        return 1 if bad else 0
    if args.floors:
        print("%-12s %-11s %-46s %9s %9s %9s" % ("province", "metric", "label", "now", "floor80", "missing"))
        for prov, mid, kind, a, b, need, miss in floor_rows(data, refs, prov_ids):
            if args.province and prov != args.province:
                continue
            if miss > 0:
                print("%-12s %-11s %-46s %9.2f %9.2f %9.2f" % (prov, mid, M_BY_ID[mid][2][:46],
                      a if b is None else a, need if b is None else need, miss))
        return 0
    if args.deficits:
        for s, p, mid, v, ref in deficits(rows, refs, args.deficits):
            if args.province and p != args.province:
                continue
            print("%5.1f  %-12s %-11s %-52s value %-8.3g skyrim %.3g" % (s, p, mid, M_BY_ID[mid][2], v, ref))
        return 0
    if args.lowest and not args.raw:
        print("PARITY lowest %d cells (score vs Skyrim = 100; land slivers under 1 km2 and beyond-map realms skipped; --realms to include):" % len(low))
        for v, rid, c in low:
            print("  %3d  %-30s %s" % (round(v), rid, dict(CATS)[c]))
        return 0
    if not args.quiet:
        print("PARITY SCORECARD (Skyrim = 100; cells capped at %d). %s" % (CAP, "  ".join("%s %s" % (s, t) for t, s in (HEAT_A if ascii_only else HEAT_U))))
        print("legend: " + ", ".join("%s=%s" % (c, n) for c, n in CATS))
        print()
        print("PROVINCES")
        for line in table(rows, ["P:" + p for p in order], prov_label, ascii_only):
            print(line)
        print()
        print("SUB-REGIONS")
        keys = []
        for p in order:
            keys += ["R:" + r for r in sorted(prov_ids[p]) if match_reg_id(r)]
        for line in table(rows, keys, reg_label, ascii_only, 30):
            print(line)
        print()
        print("10 lowest cells:")
        for v, rid, c in low:
            print("  %3d  %-30s %s" % (round(v), rid, dict(CATS)[c]))
        if args.raw:
            for line in raw_tables(rows, order):
                print(line)
    if args.write_doc:
        write_doc(args.write_doc, rows, refs, order, prov_ids, data)
        print("wrote generated block into", args.write_doc)
    return 0


def md_scorecard(rows, order, prov_ids, data):
    L = []
    L.append("Symbols: %s" % "  ".join("%s %s" % (s, ("%d+" % t) if t else "<25") for t, s in HEAT_U))
    L.append("")
    L.append("Columns: " + ", ".join("**%s** %s" % (c, n) for c, n in CATS) + ".")
    L.append("")
    L.append("### By province (area-weighted for densities, mean of regions for variety)")
    L.append("")
    L.append("| province | km2 | " + " | ".join(c for c, _ in CATS) + " | mean |")
    L.append("|---|---:|" + "---:|" * (len(CATS) + 1))

    def line(k, label):
        row = rows[k]
        cs = [row["cats"][c] for c, _ in CATS]
        vs = [c for c in cs if c is not None]
        mean = sum(vs) / len(vs) if vs else None
        cell = lambda v: "-" if v is None else "%s %d" % (heat(v, False), round(v))
        return "| %s | %.1f | %s | %s |" % (label, row["km2"]," | ".join(cell(c) for c in cs), cell(mean))
    for p in order:
        L.append(line("P:" + p, "**%s**" % p))
    L.append("")
    L.append("### By sub-region")
    L.append("")
    L.append("| region | km2 | " + " | ".join(c for c, _ in CATS) + " | mean |")
    L.append("|---|---:|" + "---:|" * (len(CATS) + 1))
    for p in order:
        for r in sorted(prov_ids[p]):
            L.append(line("R:" + r, r))
    return L


def md_raw(rows, order):
    L = []
    L.append("Province-level raw numbers (density metrics are per km2 of land; unit metrics per named town; "
             "`[P]` metrics are shared by every region of a province). The last column is the Skyrim reference used as 100.")
    L.append("")
    for c, cname in CATS:
        ms = [m for m in METRICS if m[1] == c]
        L.append("**%s: %s**" % (c, cname))
        L.append("")
        L.append("| metric | " + " | ".join(order) + " |")
        L.append("|---|" + "---:|" * len(order))
        for m in ms:
            cells = []
            for p in order:
                v = rows["P:" + p]["raw"][m[0]]
                cells.append("-" if v is None else ("%.2f" % v if (abs(v) < 10 and v != int(v)) else "%.0f" % v))
            L.append("| %s%s | %s |" % (m[2], " [P]" if m[3] == "prov" else "", " | ".join(cells)))
        L.append("")
    return L


def write_doc(path, rows, refs, order, prov_ids, data):
    p = path if os.path.isabs(path) else os.path.join(ROOT, path)
    txt = read(p)
    block = ["<!-- PARITY:BEGIN (generated by tools/qa/parity.py --write-doc; do not hand-edit) -->", ""]
    block += md_scorecard(rows, order, prov_ids, data)
    block += ["", "### Raw numbers", ""] + md_raw(rows, order)
    block += ["", "### Lowest 25 cells (sub-regions of 5 km2 or more, Skyrim holds excluded)", ""]
    block += ["| score | region | category |", "|---:|---|---|"]
    for v, rid, c in lowest(rows, 25):
        block.append("| %d | %s | %s |" % (round(v), rid, dict(CATS)[c]))
    g = data["glob"]
    block += ["", "### Global (not per region)", ""]
    block += ["* NPC roles in the engine: %d; radiant templates: %d; creature kinds in spawn tables: %d; recipes: %d."
              % (g["npc_roles"], g["radiant_templates"], g["creature_kinds_total"], g["recipes"])]
    block += ["* Room templates in code (distinct `feature(\"room:*\"|\"setpiece:*\")` names): %d; cave variant kinds: %d."
              % (g["room_templates_all"], g["cave_variant_kinds"])]
    block += ["* Places: %d. Bespoke settlement layouts (plan file or Java case): %d. Quests counted: %d."
              % (g["places_total"], len(g["bespoke_settlements"]), g["quests_total"])]
    block += ["", "<!-- PARITY:END -->"]
    new = "\n".join(block)
    if "<!-- PARITY:BEGIN" in txt and "<!-- PARITY:END -->" in txt:
        a = txt.index("<!-- PARITY:BEGIN")
        b = txt.index("<!-- PARITY:END -->") + len("<!-- PARITY:END -->")
        txt = txt[:a] + new + txt[b:]
    else:
        txt = txt.rstrip("\n") + "\n\n" + new + "\n"
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)


if __name__ == "__main__":
    sys.exit(main())
