"""Generates Skycraft's landmark and dungeon data: the named sites of Skyrim and the shrines of the Daedric Princes
across Tamriel (places files), their names (lang), and the structure / structure set JSON that builds every named
site and every random dungeon of the wilds.

Usage: py -3 tools/landmarks.py <path to markers.json from the Skyrim reference extraction>

The reference file (map markers, Word Walls, Standing Stones and dragon mounds of the owner's copy of Skyrim, already
scaled to blocks with Whiterun at 0,0) is not part of the repo; only facts derived from it are written: names, block
positions and the shout each Word Wall teaches. Re-run after changing the tables below and commit the outputs.

Outputs (relative to the repo root):
  mod/src/main/resources/skycraft/tamriel/places/skyrim_landmarks.json   Skyrim's (and Solstheim's) named sites
  mod/src/main/resources/skycraft/tamriel/places/tamriel_shrines.json    a shrine of every Prince in every province
  mod/src/main/resources/skycraft/tamriel/places/index.json              (the shrines file appended if missing)
  mod/src/main/lang/skycraft/landmarks.json                              place names
  mod/src/main/resources/data/skycraft/worldgen/structure/**.json        dungeon / landmark structures
  mod/src/main/resources/data/skycraft/worldgen/structure_set/**.json    their placements
  mod/src/main/resources/data/skycraft/tags/worldgen/structure/{dungeons,road_destinations}.json
"""
import json
import math
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
PLACES = os.path.join(RES, "skycraft", "tamriel", "places")
DATA = os.path.join(RES, "data", "skycraft")
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft", "landmarks.json")

# Solstheim's markers are in their own worldspace: placed relative to Raven Rock, which sits at (4300, -2900).
RAVEN_ROCK = (4300, -2900)

HOLDS = {
    "Haafingar": "skyrim/haafingar", "Hjaalmarch": "skyrim/hjaalmarch", "The Pale": "skyrim/the_pale",
    "Winterhold": "skyrim/winterhold", "The Reach": "skyrim/the_reach", "Whiterun Hold": "skyrim/whiterun",
    "Eastmarch": "skyrim/eastmarch", "Falkreath Hold": "skyrim/falkreath", "The Rift": "skyrim/the_rift",
    "Solstheim": "morrowind/solstheim",
}

# Marker types that are settlements (workstream S1's places) or not places at all.
SKIP_TYPES = {"City", "Town", "Settlement", "Farm", "Wood Mill", "Wheat Mill", "Stable", "Docks", "Orc Stronghold",
              "None", "DLC2 Raven Rock", "DLC2 Tel Mithryn", "DLC2 To Skyrim", "DLC2 To Solstheim", "Smelter"}
SKIP_NAMES = {
    # towns in mine/cave markers (S1), siege camps outside the capitals, secondary entrances
    "Stonehills", "Darkwater Crossing", "Soljund's Sinkhole", "Reachcliff Secret Entrance", "Castle Volkihar Balcony",
    "Solitude Military Camp", "Windhelm Military Camp", "Markarth Military Camp", "Whiterun Military Camp",
    "Riften Military Camp", "Dawnstar Docks", "Windhelm Docks", "Castle Karstaag Caverns",
}

# Default variant per marker type; names below override.
TYPE_VARIANT = {
    "Nordic Ruins": "barrow", "Cave": "cave", "Dwemer Ruin": "dwemer_ruin", "Mine": "mine", "Fort": "fort",
    "Giant Camp": "giant_camp", "Dragon Lair": "dragon_lair", "Daedric Shrine": "daedric", "Imperial Tower": "watchtower",
    "Nordic Tower": "nordic_tower", "Shipwreck": "shipwreck", "Grove": "grove", "Camp": "bandit_camp",
    "Military Camp (Imperial)": "imperial_camp", "Military Camp (Stormcloak)": "stormcloak_camp", "Shack": "cabin",
    "Nordic Dwelling": "nordic_ruin", "Lighthouse": "lighthouse", "Landmark": "ruin", "Pass": "pass",
    "Clearing": "clearing", "Rock": "rock", "Altar": "altar", "Doomstone (Standing Stone)": "stone",
    "DLC2 Beast Stone": "stone", "DLC2 Temple of Miraak": "barrow",
}

# Hand-curated: who lives where. Cave rosters follow the game's inhabitants as remembered (and the place names).
NAME_VARIANT = {
    # caves
    "Broken Oar Grotto": "smuggler_cave", "Brinewater Grotto": "smuggler_cave", "Ravenscar Hollow": "hagraven_lair",
    "Shadowgreen Cavern": "spriggan_cave", "Lower Steepfall Burrow": "troll_cave", "Steepfall Burrow": "den",
    "Wolfskull Cave": "necromancer_lair", "Rimerock Burrow": "necromancer_lair", "Lost Echo Cave": "falmer_cave",
    "Hob's Fall Cave": "necromancer_lair", "Pinemoon Cave": "bandit_cave", "Bleakcoast Cave": "den",
    "Sightless Pit": "falmer_cave", "Movarth's Lair": "vampire_lair", "Darkfall Cave": "falmer_cave",
    "Forebears' Holdout": "den", "Dimhollow Crypt": "barrow", "Stillborn Cave": "ice_cave",
    "Bruca's Leap Redoubt": "forsworn_cave", "Duskglow Crevice": "falmer_cave", "Chillwind Depths": "falmer_cave",
    "Liar's Retreat": "bandit_cave", "Forsaken Cave": "barrow", "Brood Cavern": "spider_cave", "Bronze Water Cave": "den",
    "Orotheim": "bandit_cave", "Blind Cliff Cave": "hagraven_lair", "Rebel's Cairn": "barrow", "Uttering Hills Cave": "bandit_cave",
    "Red Eagle Redoubt": "forsworn_cave", "Shimmermist Cave": "falmer_cave", "Redoran's Retreat": "bandit_cave",
    "Swindler's Den": "bandit_cave", "Darkshade": "spriggan_cave", "Cragwallow Slope": "necromancer_lair",
    "Graywinter Watch": "troll_cave", "Reachwater Rock": "barrow", "Cronvangr Cave": "vampire_lair", "Reachcliff Cave": "barrow",
    "White River Watch": "bandit_cave", "Broken Fang Cave": "vampire_lair", "Purewater Run": "den",
    "Eldergleam Sanctuary": "grove", "Stony Creek Cave": "bandit_cave", "Gloomreach": "falmer_cave",
    "Lost Knife Hideout": "bandit_cave", "Cragslane Cavern": "bandit_cave", "North Brittleshin Pass": "skeever_cave",
    "South Brittleshin Pass": "skeever_cave", "Bloated Man's Grotto": "grove", "Sunderstone Gorge": "barrow",
    "Darkwater Pass": "falmer_cave", "Tolvald's Cave": "falmer_cave", "Moss Mother Cavern": "spriggan_cave",
    "Pinepeak Cavern": "den", "Glenmoril Coven": "hagraven_lair", "Snapleg Cave": "hagraven_lair",
    "Ruunvald Excavation": "necromancer_lair", "Boulderfall Cave": "necromancer_lair", "Honeystrand Cave": "den",
    "Haemar's Shame": "vampire_lair", "Fallowstone Cave": "den", "Greywater Grotto": "spider_cave",
    "Bonechill Passage": "ice_cave", "Halldir's Cairn": "barrow", "Southfringe Sanctum": "necromancer_lair",
    "Nightingale Hall": "cave", "Broken Helm Hollow": "bandit_cave", "Crystaldrift Cave": "ice_cave",
    "Septimus Signus's Outpost": "ice_cave",
    # Solstheim caves
    "Glacial Cave": "ice_cave", "Castle Karstaag Ruins": "ice_cave", "Frossel": "den", "Bristleback Cave": "den",
    "Benkongerike": "barrow", "Hrothmund's Barrow": "barrow", "Frostmoon Crag": "den",
    # Nordic ruins that aren't plain barrows
    "Silent Moons Camp": "bandit_camp", "Hag Rock Redoubt": "forsworn_camp", "Deepwood Redoubt": "barrow",
    "Lost Valley Redoubt": "forsworn_camp", "Arcwind Point": "dragon_lair",
    # camps
    "Druadach Redoubt": "forsworn_cave", "Karthspire Camp": "forsworn_camp", "Karthspire": "forsworn_camp",
    "Serpent's Bluff Redoubt": "forsworn_camp", "Haknir's Shoal": "shipwreck", "Last Vigil": "bandit_camp",
    # forts
    "Castle Volkihar": "fort_vampire", "Nightcaller Temple": "fort_mage", "Driftshade Refuge": "fort",
    "Harmugstahl": "fort_mage", "Broken Tower Redoubt": "fort_forsworn", "Morvunskar": "fort_mage",
    "Fellglow Keep": "fort_mage", "Gallows Rock": "fort", "Western Watchtower": "watchtower", "Mistwatch": "fort",
    "Ilinalta's Deep": "fort_mage", "Bloodlet Throne": "fort_vampire", "Darklight Tower": "forsworn_tower",
    "Highpoint Tower": "mage_tower", "Ashfallow Citadel": "fort_mage", "Fort Frostmoth": "fort",
    # towers
    "Sundered Towers": "forsworn_tower", "Bleakwind Bluff": "forsworn_tower", "Dead Crone Rock": "forsworn_tower",
    "Cradle Stone Tower": "forsworn_tower", "Abandoned Prison": "watchtower", "Stendarr's Beacon": "watchtower",
    # shacks and ruins
    "Hall of the Vigilant": "cabin", "Ironback Hideout": "cabin", "Redwater Den": "cabin", "Pinewatch": "cabin",
    "Lost Valkygg": "nordic_ruin", "Four Skull Lookout": "nordic_ruin", "Moldering Ruins": "nordic_ruin",
    "Skytemple Ruins": "nordic_ruin", "Windward Ruins": "nordic_ruin", "Kjenstag Ruins": "nordic_ruin",
    "Yorgrim Overlook": "nordic_ruin", "Hamvir's Rest": "nordic_ruin", "Roadside Ruins": "nordic_ruin",
    "North Skybound Watch": "nordic_ruin", "Snowclad Ruins": "nordic_ruin",
    # landmarks (style)
    "Deep Folk Crossing": "bridge", "Weynon Stones": "stones", "Gjukar's Monument": "monument",
    "Reachwind Eyrie": "eyrie", "Bard's Leap Summit": "waterfall", "Orphan Rock": "rock", "Ruins of Bthalft": "dwemer",
    "Ruins of Rkund": "ruin", "Frykte Peak": "peak", "Mortrag Peak": "peak", "Stalhrim Source": "source",
    "Horker Island": "island", "Mount Moesring": "peak", "Altar of Thrond": "altar", "Hvitkald Peak": "peak",
    "Brodir Grove": "grove", "Headwaters of Harstrad": "clearing", "Autumnshade Clearing": "clearing",
    # Blackreach
    "Great Lift at Mzinchaleft": "great_lift", "Great Lift at Alftand": "great_lift", "Great Lift at Raldbthar": "great_lift", "Tower of Mzark": "mzark",
    "Irkngthand": "dwemer_ruin",
    # Daedric shrines (prince)
    "Statue to Meridia": "meridia_great", "Shrine of Azura": "azura_great", "Shrine of Mehrunes Dagon": "mehrunes_dagon",
    "Shrine to Peryite": "peryite", "Sacellum of Boethiah": "boethiah",
    # named sites with layouts of their own
    "Bleak Falls Barrow": "bleak_falls_barrow", "Ustengrav": "ustengrav", "Saarthal": "saarthal", "Forelhost": "forelhost",
    "Labyrinthian": "labyrinthian", "Kolskeggr Mine": "kolskeggr_mine", "Ysgramor's Tomb": "ysgramors_tomb",
    "High Hrothgar": "high_hrothgar", "Throat of the World": "throat_of_the_world",
}

STYLES = ["monument", "stones", "ruin", "peak", "pass", "clearing", "dwemer", "bridge", "altar", "rock", "waterfall",
          "eyrie", "island", "source", "campsite"]
PRINCES = ["azura", "boethiah", "clavicus_vile", "hermaeus_mora", "hircine", "malacath", "mehrunes_dagon", "mephala",
           "meridia", "molag_bal", "namira", "nocturnal", "peryite", "sanguine", "sheogorath", "vaermina"]
PRINCE_NAMES = {
    "azura": "Shrine of Azura", "boethiah": "Shrine of Boethiah", "clavicus_vile": "Shrine of Clavicus Vile",
    "hermaeus_mora": "Shrine of Hermaeus Mora", "hircine": "Shrine of Hircine", "malacath": "Shrine of Malacath",
    "mehrunes_dagon": "Shrine of Mehrunes Dagon", "mephala": "Shrine of Mephala", "meridia": "Shrine of Meridia",
    "molag_bal": "Shrine of Molag Bal", "namira": "Shrine of Namira", "nocturnal": "Shrine of Nocturnal",
    "peryite": "Shrine of Peryite", "sanguine": "Shrine of Sanguine", "sheogorath": "Shrine of Sheogorath",
    "vaermina": "Shrine of Vaermina",
}

# variant -> (place type, radius, culture)
VARIANT_INFO = {
    "barrow": ("dungeon_fixed", 26, "nord"), "cave": ("dungeon_fixed", 18, "nord"), "den": ("dungeon_fixed", 18, "nord"),
    "bandit_cave": ("dungeon_fixed", 18, "nord"), "spider_cave": ("dungeon_fixed", 18, "nord"),
    "troll_cave": ("dungeon_fixed", 18, "nord"), "falmer_cave": ("dungeon_fixed", 20, "falmer"),
    "forsworn_cave": ("dungeon_fixed", 18, "forsworn"), "spriggan_cave": ("dungeon_fixed", 18, "nord"),
    "ice_cave": ("dungeon_fixed", 18, "nord"), "skeever_cave": ("dungeon_fixed", 18, "nord"),
    "smuggler_cave": ("dungeon_fixed", 18, "nord"), "vampire_lair": ("dungeon_fixed", 18, "nord"),
    "necromancer_lair": ("dungeon_fixed", 18, "nord"), "hagraven_lair": ("dungeon_fixed", 18, "forsworn"),
    "dwemer_ruin": ("dungeon_fixed", 32, "dwemer"), "mine": ("dungeon_fixed", 20, "nord"),
    "giant_camp": ("dungeon_fixed", 16, "giant"), "dragon_lair": ("dungeon_fixed", 16, "nord"),
    "shipwreck": ("dungeon_fixed", 14, "nord"), "grove": ("dungeon_fixed", 16, "nord"),
    "bandit_camp": ("dungeon_fixed", 14, "nord"), "forsworn_camp": ("dungeon_fixed", 14, "forsworn"),
    "imperial_camp": ("dungeon_fixed", 18, "imperial"), "stormcloak_camp": ("dungeon_fixed", 18, "nord"),
    "cabin": ("dungeon_fixed", 10, "nord"), "lighthouse": ("dungeon_fixed", 10, "nord"),
    "nordic_ruin": ("dungeon_fixed", 12, "nord"), "watchtower": ("dungeon_fixed", 12, "imperial"),
    "nordic_tower": ("dungeon_fixed", 12, "nord"), "forsworn_tower": ("dungeon_fixed", 12, "forsworn"),
    "great_lift": ("dungeon_fixed", 10, "dwemer"), "mage_tower": ("dungeon_fixed", 10, "dunmer"),
    "mzark": ("dungeon_fixed", 12, "dwemer"),
    "fort": ("fort", 22, "imperial"), "fort_forsworn": ("fort", 22, "forsworn"), "fort_vampire": ("fort", 22, "imperial"),
    "fort_mage": ("fort", 22, "imperial"),
    "bleak_falls_barrow": ("dungeon_fixed", 30, "nord"), "ustengrav": ("dungeon_fixed", 28, "nord"),
    "saarthal": ("dungeon_fixed", 28, "nord"), "forelhost": ("dungeon_fixed", 30, "nord"),
    "labyrinthian": ("dungeon_fixed", 60, "nord"), "kolskeggr_mine": ("dungeon_fixed", 26, "nord"),
    "ysgramors_tomb": ("dungeon_fixed", 28, "nord"),
    "high_hrothgar": ("landmark", 42, "nord"), "throat_of_the_world": ("landmark", 16, "nord"),
    "sky_haven_temple": ("landmark", 30, "akaviri"),
}
for s in STYLES:
    VARIANT_INFO[s] = ("landmark", 12, "nord")
for pr in PRINCES:
    VARIANT_INFO[pr] = ("daedric_shrine", 12, "daedric")
VARIANT_INFO["azura_great"] = ("daedric_shrine", 26, "daedric")
VARIANT_INFO["meridia_great"] = ("daedric_shrine", 26, "daedric")

# Word Wall words (as extracted) -> shout id. Shouts the magic module lacks keep their own id; Skycraft maps them.
WORDS = [
    ({"fus", "ro", "dah"}, "unrelenting_force"), ({"yol", "toor", "shul"}, "fire_breath"),
    ({"fo", "krah", "diin"}, "frost_breath"), ({"wuld", "nah", "kest"}, "whirlwind_sprint"),
    ({"feim", "zii", "gron"}, "become_ethereal"), ({"lok", "vah", "koor"}, "clear_skies"),
    ({"laas", "yah", "nir"}, "aura_whisper"), ({"tiid", "klo", "ul"}, "slow_time"),
    ({"krii", "lun", "aus"}, "marked_for_death"), ({"zun", "haal", "viik"}, "disarm"),
    ({"su", "grah", "dun"}, "elemental_fury"), ({"kaan", "drem", "ov"}, "kynes_peace"),
    ({"raan", "mir", "tah"}, "animal_allegiance"), ({"strun", "bah", "qo"}, "storm_call"),
    ({"joor", "zah", "frul"}, "dragonrend"), ({"iiz", "slen", "nus"}, "ice_form"), ({"ru", "maar", "faas"}, "dismay"),
    ({"haas", "lah", "gaan"}, "drain_vitality"), ({"ven", "gaar", "nos"}, "cyclone"),
    ({"mul", "qah", "diiv"}, "dragon_aspect"), ({"mid", "vur", "shaan"}, "battle_fury"), ({"gol"}, "bend_will"),
    ({"gutmeyzul", "zul", "mey", "gut"}, "throw_voice"),
]

# Interior walls whose cell names differ from their site's marker.
WALL_ALIASES = {"Hag's End": "Deepwood Redoubt"}

# Variants whose builders have a Word Wall of their own (a boss chamber, a lair, Forelhost's terrace).
HAS_WALL = {"barrow", "dragon_lair", "bleak_falls_barrow", "ustengrav", "saarthal", "forelhost", "labyrinthian",
            "ysgramors_tomb", "throat_of_the_world"}


def slug(name):
    s = name.lower().replace("'", "").replace("’", "")
    s = re.sub(r"[^a-z0-9]+", "_", s).strip("_")
    return s


def shout_for(words):
    ws = {w.lower() for w in words}
    for keys, shout in WORDS:
        if ws & keys:
            return shout
    return ""


def write_json(path, obj, compact=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        if compact:
            json.dump(obj, f, ensure_ascii=False, indent=1)
        else:
            json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return path


def main(markers_path):
    ref = json.load(open(markers_path, encoding="utf-8"))
    scale = ref["scale_blocks_per_unit"]
    markers = ref["markers"]
    rr = next(m for m in markers if m["name"] == "Raven Rock" and m["worldspace"] == "DLC2SolstheimWorld")

    def pos(m):
        if m.get("worldspace") == "DLC2SolstheimWorld":
            return (round(RAVEN_ROCK[0] + (m["x"] - rr["x"]) * scale), round(RAVEN_ROCK[1] - (m["y"] - rr["y"]) * scale))
        return (m.get("mcX"), m.get("mcZ"))

    places = []
    lang = {}
    by_name = {}

    def add(pid, name, ptype, culture, region, x, z, radius, variant, **extra):
        if any(p["id"] == pid for p in places):
            pid = pid + "_" + str(len(places))
        p = {"id": pid, "name": "place.skycraft." + pid, "type": ptype, "culture": culture, "region": region,
             "x": int(x), "z": int(z), "radius": radius, "variant": variant}
        p.update({k: v for k, v in extra.items() if v})
        places.append(p)
        lang["place.skycraft." + pid] = name
        return p

    # ---- map markers
    for m in markers:
        ws = m["worldspace"]
        if ws not in ("Tamriel", "DLC2SolstheimWorld"):
            continue
        t = m["type_name"]
        name = m["name"]
        if t in SKIP_TYPES or name in SKIP_NAMES or t.endswith("Capitol") or t.endswith("Castle"):
            continue
        x, z = pos(m)
        if x is None:
            continue
        region = HOLDS.get(m["hold"] or "", "skyrim/whiterun")
        if t in ("Doomstone (Standing Stone)", "DLC2 Beast Stone"):
            continue  # from the standing stone features below (exact positions)
        variant = NAME_VARIANT.get(name, TYPE_VARIANT.get(t))
        if variant is None:
            print("unmapped marker", name, t)
            continue
        if variant == "daedric":
            print("unmapped shrine", name)
            continue
        ptype, radius, culture = VARIANT_INFO[variant]
        pid = slug(name)
        p = add(pid, name, ptype, culture, region, x, z, radius, variant)
        by_name[name] = p

    # ---- the sites that aren't map markers: Sky Haven Temple above Karthspire
    k = by_name["Karthspire"]
    add("sky_haven_temple", "Sky Haven Temple", "landmark", "akaviri", "skyrim/the_reach", k["x"] - 60, k["z"] + 30, 30, "sky_haven_temple")

    # ---- Standing Stones (exact positions); the Guardian Stones are one site
    stones = [f for f in ref["features"] if f["kind"] == "standing_stone"]
    guardian = []
    for f in stones:
        if f.get("worldspace") == "DLC2SolstheimWorld":
            x, z = pos(f)
            sign = f["base_name"].split()[0].lower()  # "Water Stone" -> water
            add("allmaker_" + sign + "_stone", f["base_name"], "standing_stone", "skaal", "morrowind/solstheim", x, z, 10, sign)
            continue
        sign = f["base_edid"].replace("Doomstone", "").lower()
        if sign in ("warrior", "mage", "thief"):
            guardian.append(f)
            continue
        add("the_" + sign + "_stone", "The " + sign.capitalize() + " Stone", "standing_stone", "nord",
            HOLDS.get(holds_of(by_name, f), "skyrim/whiterun"), f["mcX"], f["mcZ"], 10, sign)
    if guardian:
        gx = round(sum(f["mcX"] for f in guardian) / len(guardian))
        gz = round(sum(f["mcZ"] for f in guardian) / len(guardian))
        add("the_guardian_stones", "The Guardian Stones", "standing_stone", "nord", "skyrim/falkreath", gx, gz, 12, "guardian")

    # ---- Word Walls: inside a dungeon (its boss chamber teaches it), at a lair, or standing alone
    walls = 0
    for f in ref["features"]:
        if f["kind"] != "word_wall":
            continue
        shout = shout_for(f.get("words", []))
        if not shout:
            continue
        anchor = f.get("position_from_marker") or f.get("location")
        anchor = WALL_ALIASES.get(anchor, anchor)
        near = f.get("nearest_marker")
        target = None
        if anchor in by_name:
            target = by_name[anchor]
        elif anchor == "Raven Rock Mine":
            target = by_name.get("Bloodskal Barrow")
        elif f.get("worldspace") == "FrostmereCryptWorld":
            target = by_name.get("Frostmere Crypt")
        elif f.get("exterior") and near in by_name and f.get("nearest_marker_dist", 1e9) <= 4000 \
                and by_name[near]["variant"] in HAS_WALL:
            target = by_name[near]
        if target is not None and target["variant"] in HAS_WALL:
            if not target.get("shout"):
                target["shout"] = shout
                walls += 1
            continue
        if f.get("exterior") and f.get("mcX") is not None:
            base = (near or anchor or "Word Wall")
            add("word_wall_" + slug(base), "Word Wall (" + base + ")", "word_wall", "nord",
                HOLDS.get(holds_of(by_name, f), "skyrim/whiterun"), f["mcX"], f["mcZ"], 10, shout)
            walls += 1
        elif f.get("exterior") and f.get("worldspace") == "DLC2SolstheimWorld" and near in by_name:
            by_name[near]["shout"] = shout
            walls += 1
        else:
            print("word wall without a site:", anchor, near, f.get("worldspace"), shout)
    # the summit's wall (taught by the Greybeards' master)
    if "Throat of the World" in by_name:
        by_name["Throat of the World"]["shout"] = "fire_breath"
        walls += 1

    # ---- dragon burial mounds (exterior)
    for f in ref["features"]:
        if f["kind"] != "dragon_mound" or not f["exterior"]:
            continue
        x, z = pos(f) if f.get("worldspace") == "DLC2SolstheimWorld" else (f["mcX"], f["mcZ"])
        near = f.get("nearest_marker") or "the wilds"
        region = "morrowind/solstheim" if f.get("worldspace") == "DLC2SolstheimWorld" else HOLDS.get(holds_of(by_name, f), "skyrim/whiterun")
        add("dragon_mound_" + slug(near), "Dragon Burial Mound (" + near + ")", "dragon_mound", "nord", region, x, z, 9, "mound")

    # ---- chunk collisions among our own places of the same structure: nudge (one start per structure per chunk)
    seen = {}
    for p in places:
        key = (p["type"], p["variant"], p["x"] >> 4, p["z"] >> 4)
        while key in seen:
            p["x"] += 16
            key = (p["type"], p["variant"], p["x"] >> 4, p["z"] >> 4)
        seen[key] = p

    write_json(os.path.join(PLACES, "skyrim_landmarks.json"), places, compact=True)

    # ---- the shrines of the Princes in the other provinces
    shrines = province_shrines(lang)
    write_json(os.path.join(PLACES, "tamriel_shrines.json"), shrines, compact=True)
    index_path = os.path.join(PLACES, "index.json")
    index = json.load(open(index_path, encoding="utf-8"))
    if "tamriel_shrines.json" not in index:
        index.append("tamriel_shrines.json")
        write_json(index_path, index)

    lang_sorted = dict(sorted(lang.items()))
    write_json(LANG, lang_sorted)
    structures()
    counts = {}
    for p in places + shrines:
        counts[(p["type"], p["variant"])] = counts.get((p["type"], p["variant"]), 0) + 1
    by_type = {}
    for (t, v), n in sorted(counts.items()):
        by_type.setdefault(t, []).append(f"{v}={n}")
    print(f"{len(places)} Skyrim places, {len(shrines)} province shrines, {walls} word walls placed")
    for t, vs in by_type.items():
        print(f"  {t}: {sum(int(v.split('=')[1]) for v in vs)}  ({', '.join(vs)})")


def holds_of(by_name, f):
    """Hold of a feature: the nearest named marker's hold if we know it, else by the nearest capital."""
    near = f.get("nearest_marker")
    cap = {"Markarth": "The Reach", "Solitude": "Haafingar", "Morthal": "Hjaalmarch", "Dawnstar": "The Pale",
           "Winterhold": "Winterhold", "Windhelm": "Eastmarch", "Whiterun": "Whiterun Hold", "Falkreath": "Falkreath Hold",
           "Riften": "The Rift"}
    return cap.get(f.get("nearest_capital"), "Whiterun Hold")


# ---------------------------------------------------------------------- province shrines

PROVINCE_ANCHORS = {
    "cyrodiil": [("cyrodiil/heartlands", 900, 5900), ("cyrodiil/jerall_mountains", 1500, 3000), ("cyrodiil/colovian_highlands", -1300, 4400),
                 ("cyrodiil/nibenay_basin", 3700, 5000), ("cyrodiil/west_weald", -1700, 6700), ("cyrodiil/gold_coast", -2700, 8000),
                 ("cyrodiil/nibenay_valley", 1800, 8700), ("cyrodiil/blackwood", 3600, 9900)],
    "morrowind": [("morrowind/deshaan", 6300, 3300), ("morrowind/ascadian_isles", 7400, 1300), ("morrowind/west_gash", 6300, 500),
                  ("morrowind/vvardenfell_ashlands", 6100, -500), ("morrowind/telvannis", 8900, 100), ("morrowind/stonefalls", 4700, 2400),
                  ("morrowind/molag_amur", 8900, 3600), ("morrowind/red_mountain", 7100, -400)],
    "high_rock": [("high_rock/wayrest", -5300, 3300), ("high_rock/glenumbra", -9300, 2600), ("high_rock/glenumbra", -9000, -200),
                  ("high_rock/rivenspire", -7600, -1300), ("high_rock/bjoulsae", -6600, 200), ("high_rock/wrothgar", -4300, 300)],
    "hammerfell": [("hammerfell/alikr_desert", -8200, 4900), ("hammerfell/craglorn", -4200, 3700), ("hammerfell/alikr_desert", -6400, 5700),
                   ("hammerfell/dragontail", -4800, 7200), ("hammerfell/hews_bane", -6800, 8000)],
    "black_marsh": [("black_marsh/stormhold", 6400, 6300), ("black_marsh/gideon", 5800, 8300), ("black_marsh/helstrom", 8900, 6400),
                    ("black_marsh/lilmoth", 7700, 10000), ("black_marsh/murkmire", 6800, 11000)],
    "elsweyr": [("elsweyr/rimmen", 1200, 10400), ("elsweyr/anequina", -600, 10800), ("elsweyr/torval", 1200, 12600),
                ("elsweyr/senchal", 2800, 13700)],
    "valenwood": [("valenwood/grahtwood", -4000, 11300), ("valenwood/malabal_tor", -5600, 10200),
                  ("valenwood/greenshade", -6700, 12600), ("valenwood/reapers_march", -2300, 10500)],
    "summerset": [("summerset/summerset_isle", -11200, 13000), ("summerset/auridon", -9300, 11000)],
}
CULTURE = {"cyrodiil": "imperial", "morrowind": "dunmer", "high_rock": "breton", "hammerfell": "redguard",
           "black_marsh": "argonian", "elsweyr": "khajiit", "valenwood": "bosmer", "summerset": "altmer"}


class MapCells:
    """The Tamriel map rasters (mod resources, see TamrielMap/MapRaster): lets the shrines avoid the sea, lakes and
    rivers and take their region from the map. Missing rasters (or no PIL) disable the check."""
    CELL, MIN_X, MIN_Z = 32, -15360, -5120

    def __init__(self):
        self.ok = False
        try:
            from PIL import Image
            d = os.path.join(RES, "skycraft", "tamriel")
            self.reg = Image.open(os.path.join(d, "regions.png")).load()
            self.elev = Image.open(os.path.join(d, "elevation.png")).load()
            self.coast = Image.open(os.path.join(d, "coast.png")).load()
            self.feat = Image.open(os.path.join(d, "features.png")).convert("RGBA").load()
            self.size = Image.open(os.path.join(d, "regions.png")).size
            regions = json.load(open(os.path.join(d, "regions.json"), encoding="utf-8"))
            self.regions = {r["index"]: r for r in regions}
            self.ok = True
        except Exception as e:  # noqa: BLE001 - optional check
            print("map rasters unavailable, shrines are not checked against the terrain:", e)

    def region(self, x, z):
        """The land region (dict) at (x, z) if it is dry, inland land well away from water, else None."""
        cx, cz = (x - self.MIN_X) // self.CELL, (z - self.MIN_Z) // self.CELL
        if not (2 <= cx < self.size[0] - 2 and 2 <= cz < self.size[1] - 2):
            return None
        best = None
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                px = (cx + dx, cz + dz)
                r, _, b, _ = self.feat[px]
                if self.coast[px] - 32768 < 64 or self.elev[px] / 32 - 64 < 68 or (b - 128) * 0.5 > -24 or (255 - r) * 0.5 < 24:
                    return None
                if dx == 0 and dz == 0:
                    best = self.regions.get(self.reg[px])
        return best


def province_shrines(lang):
    """Sixteen shrines per province, spread inland from the province's anchors (towards its middle), well apart, on
    dry land of the province (checked against the map rasters when they are available)."""
    out = []
    cells = MapCells()
    for prov, anchors in PROVINCE_ANCHORS.items():
        cx = sum(a[1] for a in anchors) / len(anchors)
        cz = sum(a[2] for a in anchors) / len(anchors)
        spots = []
        for i, prince in enumerate(PRINCES):
            region, ax, az = anchors[i % len(anchors)]
            # head inland: towards the province's centre, swung by the Prince
            base_ang = math.atan2(cz - az, cx - ax) if (cx, cz) != (ax, az) else 0.0
            h = (hash_str(prov + prince) % 1000) / 1000.0
            land = None
            for attempt in range(96):
                ang = base_ang + (h - 0.5) * 2.2 + attempt * 0.9
                dist = 420 + ((hash_str(prince + prov + str(attempt)) % 1000) / 1000.0) * 520
                if len(anchors) <= 2:
                    dist *= 0.8
                if attempt >= 24:
                    # still at sea or in a lake: try closer to the anchor, then further out
                    dist *= (0.35, 0.6, 1.3)[attempt % 3]
                x = round(ax + math.cos(ang) * dist)
                z = round(az + math.sin(ang) * dist)
                if not all(math.hypot(x - a[1], z - a[2]) > 300 for a in anchors) or not all(math.hypot(x - s[0], z - s[1]) > 380 for s in spots):
                    continue
                land = cells.region(x, z) if cells.ok else None
                if not cells.ok or (land is not None and land["province"] == prov):
                    break
            else:
                if cells.ok:
                    # small islands (Summerset, Auridon): the nearest free dry land of the province around any anchor
                    found = None
                    for rad in range(128, 2400, 64):
                        for a in anchors:
                            for k in range(max(8, rad // 48)):
                                ang = k * 2 * math.pi / max(8, rad // 48) + h
                                tx, tz = round(a[1] + math.cos(ang) * rad), round(a[2] + math.sin(ang) * rad)
                                tl = cells.region(tx, tz)
                                if tl is not None and tl["province"] == prov and all(math.hypot(tx - b[1], tz - b[2]) > 200 for b in anchors) \
                                        and all(math.hypot(tx - s[0], tz - s[1]) > 250 for s in spots):
                                    found = (tx, tz, tl)
                                    break
                            if found:
                                break
                        if found:
                            break
                    if found:
                        x, z, land = found
            spots.append((x, z))
            pid = "shrine_" + prince + "_" + prov
            near = min(anchors, key=lambda a: math.hypot(a[1] - x, a[2] - z))
            region = land["id"] if land is not None and land["province"] == prov else near[0]
            if cells.ok and region == near[0] and land is None:
                print("shrine still off land:", pid, x, z)
            out.append({"id": pid, "name": "place.skycraft." + pid, "type": "daedric_shrine", "culture": CULTURE[prov],
                        "region": region, "x": x, "z": z, "radius": 12, "variant": prince})
            lang["place.skycraft." + pid] = PRINCE_NAMES[prince]
    return out


def hash_str(s):
    h = 2166136261
    for ch in s.encode("utf-8"):
        h = ((h ^ ch) * 16777619) & 0xFFFFFFFF
    return h


# ---------------------------------------------------------------------- structures

ANYWHERE = "#skycraft:dungeons/anywhere"
DWEMER_SPAWNS = {"monster": {"bounding_box": "piece", "spawns": [
    {"type": "skycraft:dwarven_spider", "weight": 10, "minCount": 1, "maxCount": 2},
    {"type": "skycraft:dwarven_sphere", "weight": 3, "minCount": 1, "maxCount": 1}]}}

UNDERGROUND = {"barrow", "cave", "dwemer", "mine", "vampire_lair", "necromancer_lair", "hagraven_lair", "ruin", "scorpion_den"}

# Named sites of the provinces beyond Skyrim (places from tools/provinces.py): sid, place types, variants, structure.
# The variant names a kind of site; culture ruins wear their culture's kit, keeps and towers the region's own.
PROVINCE_SITES = [
    ("site/ayleid_ruin", ["dungeon_fixed"], ["ayleid_ruin"], dict(theme="ruin", kit="ayleid")),
    ("site/velothi_tomb", ["dungeon_fixed"], ["velothi_tomb"], dict(theme="ruin", kit="velothi")),
    ("site/daedric_ruin", ["dungeon_fixed"], ["daedric_ruin"], dict(theme="ruin", kit="daedric")),
    ("site/xanmeer", ["dungeon_fixed"], ["xanmeer"], dict(theme="ruin", kit="xanmeer")),
    ("site/moon_temple", ["dungeon_fixed"], ["moon_temple"], dict(theme="ruin", kit="khajiit")),
    ("site/tree_tomb", ["dungeon_fixed"], ["tree_tomb"], dict(theme="ruin", kit="bosmer")),
    ("site/crystal_vault", ["dungeon_fixed"], ["crystal_vault"], dict(theme="ruin", kit="altmer")),
    ("site/yokudan_tomb", ["dungeon_fixed"], ["yokudan_tomb"], dict(theme="ruin", kit="redguard")),
    ("site/akaviri_ruin", ["dungeon_fixed"], ["akaviri_ruin"], dict(theme="ruin", kit="akaviri")),
    ("site/atmoran_tomb", ["dungeon_fixed"], ["atmoran_tomb"], dict(theme="ruin", kit="atmoran")),
    ("site/sea_vault", ["dungeon_fixed"], ["sea_vault"], dict(theme="ruin", kit="maormer")),
    ("site/coral_pit", ["dungeon_fixed"], ["coral_pit"], dict(theme="ruin", kit="sload")),
    ("site/breton_crypt", ["dungeon_fixed"], ["breton_crypt"], dict(theme="ruin", kit="breton")),
    ("site/orc_ruin", ["dungeon_fixed"], ["orc_ruin"], dict(theme="ruin", kit="orc")),
    ("site/imperial_crypt", ["dungeon_fixed"], ["imperial_crypt"], dict(theme="ruin", kit="imperial")),
    ("site/tower", ["dungeon_fixed"], ["tower"], dict(theme="watchtower", flavor=0, regional=True)),
    ("site/fort_keep", ["fort"], ["keep"], dict(theme="fort", regional=True)),
    ("site/fort_akaviri", ["fort", "dungeon_fixed"], ["akaviri_fort"], dict(theme="fort", flavor=0, kit="akaviri")),
    ("site/egg_mine", ["dungeon_fixed"], ["egg_mine"], dict(theme="mine", regional=True)),
    ("site/sixth_house_base", ["dungeon_fixed"], ["sixth_house_base"], dict(theme="cave", kit="daedric")),
]


# Added after the original salt sequence. Only variants whose existing builders preserve the declared
# architecture/inhabitants are included. Coastal/submerged sites, living camps, Hist/fungal towers and
# material-specific mines need their own contracts and must not silently become generic caves/bandits.
ADDITIONAL_PROVINCE_SITES = [
    ("site/bosmer_ossuary", ["dungeon_fixed"], ["bosmer_ossuary"], dict(theme="ruin", kit="bosmer")),
    ("site/cultist_lair", ["dungeon_fixed"], ["cultist_lair"], dict(theme="necromancer_lair", kit="daedric")),
    ("site/dunmer_stronghold", ["dungeon_fixed"], ["dunmer_stronghold"], dict(theme="fort", kit="velothi")),
    ("site/elven_ruin", ["dungeon_fixed"], ["elven_ruin"], dict(theme="ruin", kit="altmer")),
    ("site/imperial_prison_fort", ["dungeon_fixed"], ["imperial_prison_fort"], dict(theme="fort", kit="imperial")),
    ("site/orichalc_ruin", ["dungeon_fixed"], ["orichalc_ruin"], dict(theme="ruin", kit="redguard")),
    ("site/sandstone_tomb", ["dungeon_fixed"], ["sandstone_tomb"], dict(theme="ruin", kit="khajiit")),
    ("site/tsaesci_fortress", ["dungeon_fixed"], ["tsaesci_fortress"], dict(theme="fort", kit="akaviri")),
    ("site/altmer_sky_spire", ["dungeon_fixed"], ["altmer_sky_spire"], dict(theme="mage_tower", kit="altmer")),
    ("site/velothi_tower", ["dungeon_fixed"], ["velothi_tower"], dict(theme="watchtower", kit="velothi")),
    ("site/hound_den", ["dungeon_fixed"], ["hound_den"], dict(theme="cave", kit="bosmer", flavor=1)),
    ("site/root_hollow", ["dungeon_fixed"], ["root_hollow"], dict(theme="cave", kit="bosmer", flavor=6)),
    ("site/khajiit_shrine", ["dungeon_fixed"], ["khajiit_shrine"], dict(theme="ruin", kit="khajiit")),
    ("site/kamal_ice_vault", ["dungeon_fixed"], ["kamal_ice_vault"], dict(theme="ruin", kit="ice")),
]


def structure(theme, kit=None, flavor=None, special=None, place=None, where=None, regional=False, sites=None,
              pad=False, spawns=None):
    s = {"type": "skycraft:dungeon", "theme": theme}
    if kit:
        s["kit"] = kit
    if regional:
        s["regional_kit"] = True
    if special:
        s["special"] = special
    if flavor is not None:
        s["flavor"] = flavor
    if place:
        s["place"] = place
    if where:
        s["where"] = where
    if sites:
        s["sites"] = sites
    s["biomes"] = ANYWHERE
    s["step"] = "underground_structures" if theme in UNDERGROUND else "surface_structures"
    s["spawn_overrides"] = spawns or {}
    s["terrain_adaptation"] = "beard_thin" if pad else "none"
    return s


def structures():
    sdir = os.path.join(DATA, "worldgen", "structure")
    setdir = os.path.join(DATA, "worldgen", "structure_set")
    written = []
    dungeon_ids = []
    road_ids = []
    salt = [718274000]

    def place_set(sid, types, variants, st):
        salt[0] += 1
        written.append(write_json(os.path.join(sdir, sid + ".json"), st))
        written.append(write_json(os.path.join(setdir, sid + ".json"), {
            "structures": [{"structure": "skycraft:" + sid, "weight": 1}],
            "placement": {"type": "skycraft:places", "salt": salt[0], "types": types, "variants": variants}}))
        dungeon_ids.append("skycraft:" + sid)

    def pf(types, variants):
        return {"types": types, "variants": variants}

    D = ["dungeon_fixed"]
    # ---- Skyrim's named sites by kind
    fixed = [
        ("site/barrow", D, ["barrow"], dict(theme="barrow")),
        ("site/cave", D, ["cave"], dict(theme="cave")),
        ("site/den", D, ["den"], dict(theme="cave", flavor=1)),
        ("site/bandit_cave", D, ["bandit_cave"], dict(theme="cave", flavor=0)),
        ("site/spider_cave", D, ["spider_cave"], dict(theme="cave", flavor=2)),
        ("site/troll_cave", D, ["troll_cave"], dict(theme="cave", flavor=3)),
        ("site/falmer_cave", D, ["falmer_cave"], dict(theme="cave", flavor=4, kit="falmer")),
        ("site/forsworn_cave", D, ["forsworn_cave"], dict(theme="cave", flavor=5, kit="forsworn")),
        ("site/spriggan_cave", D, ["spriggan_cave"], dict(theme="cave", flavor=6)),
        ("site/ice_cave", D, ["ice_cave"], dict(theme="cave", flavor=7, kit="ice")),
        ("site/skeever_cave", D, ["skeever_cave"], dict(theme="cave", flavor=8)),
        ("site/smuggler_cave", D, ["smuggler_cave"], dict(theme="cave", flavor=9)),
        ("site/vampire_lair", D, ["vampire_lair"], dict(theme="vampire_lair")),
        ("site/necromancer_lair", D, ["necromancer_lair"], dict(theme="necromancer_lair")),
        ("site/hagraven_lair", D, ["hagraven_lair"], dict(theme="hagraven_lair")),
        ("site/dwemer", D, ["dwemer_ruin"], dict(theme="dwemer", spawns=DWEMER_SPAWNS)),
        ("site/mine", D, ["mine"], dict(theme="mine")),
        ("site/giant_camp", D, ["giant_camp"], dict(theme="giant_camp")),
        ("site/dragon_lair", D, ["dragon_lair"], dict(theme="dragon_lair")),
        ("site/shipwreck", D, ["shipwreck"], dict(theme="shipwreck")),
        ("site/grove", D, ["grove"], dict(theme="grove", pad=True)),
        ("site/bandit_camp", D, ["bandit_camp"], dict(theme="bandit_camp", pad=True)),
        ("site/forsworn_camp", D, ["forsworn_camp"], dict(theme="forsworn_camp", kit="forsworn", pad=True)),
        ("site/imperial_camp", D, ["imperial_camp"], dict(theme="military_camp", flavor=0, pad=True)),
        ("site/stormcloak_camp", D, ["stormcloak_camp"], dict(theme="military_camp", flavor=1, pad=True)),
        ("site/cabin", D, ["cabin"], dict(theme="cabin", pad=True)),
        ("site/lighthouse", D, ["lighthouse"], dict(theme="lighthouse")),
        ("site/nordic_ruin", D, ["nordic_ruin"], dict(theme="nordic_ruin", pad=True)),
        ("site/watchtower", D, ["watchtower"], dict(theme="watchtower", flavor=0)),
        ("site/nordic_tower", D, ["nordic_tower"], dict(theme="watchtower", flavor=1)),
        ("site/forsworn_tower", D, ["forsworn_tower"], dict(theme="watchtower", flavor=1, kit="forsworn")),
        ("site/great_lift", D, ["great_lift", "mzark"], dict(theme="great_lift")),
        ("site/mage_tower", D, ["mage_tower"], dict(theme="mage_tower", kit="daedric")),
        ("site/fort", ["fort", "dungeon_fixed"], ["fort"], dict(theme="fort", flavor=0)),
        ("site/fort_forsworn", ["fort"], ["fort_forsworn"], dict(theme="fort", flavor=1, kit="forsworn")),
        ("site/fort_vampire", ["fort"], ["fort_vampire"], dict(theme="fort", flavor=2)),
        ("site/fort_mage", ["fort"], ["fort_mage"], dict(theme="fort", flavor=3)),
        ("site/standing_stone", ["standing_stone"], [], dict(theme="standing_stone", pad=True)),
        ("site/word_wall", ["word_wall"], [], dict(theme="word_wall", pad=True)),
        ("site/dragon_mound", ["dragon_mound"], [], dict(theme="dragon_mound", pad=True)),
        ("site/landmark", ["landmark"], STYLES, dict(theme="landmark", pad=True)),
        ("site/daedric_shrine", ["daedric_shrine"], PRINCES, dict(theme="prince_shrine", pad=True)),
        # the named sites with layouts of their own
        ("unique/azuras_shrine", ["daedric_shrine"], ["azura_great"], dict(theme="prince_shrine", special="azura")),
        ("unique/statue_to_meridia", ["daedric_shrine"], ["meridia_great"], dict(theme="prince_shrine", special="meridia")),
        ("unique/bleak_falls_barrow", D, ["bleak_falls_barrow"], dict(theme="barrow", special="bleak_falls")),
        ("unique/ustengrav", D, ["ustengrav"], dict(theme="barrow", special="ustengrav")),
        ("unique/saarthal", D, ["saarthal"], dict(theme="barrow", special="saarthal", kit="atmoran")),
        ("unique/forelhost", D, ["forelhost"], dict(theme="barrow", special="forelhost")),
        ("unique/ysgramors_tomb", D, ["ysgramors_tomb"], dict(theme="barrow", special="ysgramor")),
        ("unique/labyrinthian", D, ["labyrinthian"], dict(theme="barrow", special="labyrinthian")),
        ("unique/labyrinthian_ruins", D, ["labyrinthian"], dict(theme="labyrinthian")),
        ("unique/kolskeggr_mine", D, ["kolskeggr_mine"], dict(theme="mine", special="kolskeggr")),
        ("unique/high_hrothgar", ["landmark"], ["high_hrothgar"], dict(theme="high_hrothgar")),
        ("unique/throat_of_the_world", ["landmark"], ["throat_of_the_world"], dict(theme="throat_summit")),
        ("unique/sky_haven_temple", ["landmark"], ["sky_haven_temple"], dict(theme="sky_haven")),
    ]
    for sid, types, variants, kw in fixed:
        theme = kw.pop("theme")
        place_set(sid, types, variants, structure(theme, place=pf(types, variants), **kw))
        if any(v in variants for v in ("barrow", "dwemer_ruin", "fort", "bleak_falls_barrow", "labyrinthian", "high_hrothgar",
                                       "ustengrav", "saarthal", "forelhost", "sky_haven_temple", "mine", "giant_camp")):
            road_ids.append("skycraft:" + sid)

    # ---- site lists: the 7,000 Steps, the summit path, Blackreach
    for sid, theme, sites in [("unique/seven_thousand_steps", "steps", "seven_thousand_steps"),
                              ("unique/throat_path", "steps", "throat_path"),
                              ("unique/blackreach", "blackreach", "blackreach")]:
        salt[0] += 1
        st = structure(theme, sites=sites)
        st["step"] = "underground_structures" if theme == "blackreach" else "surface_structures"
        written.append(write_json(os.path.join(sdir, sid + ".json"), st))
        written.append(write_json(os.path.join(setdir, sid + ".json"), {
            "structures": [{"structure": "skycraft:" + sid, "weight": 1}],
            "placement": {"type": "skycraft:sites", "salt": salt[0], "sites": sites}}))
        if theme == "blackreach":
            dungeon_ids.append("skycraft:" + sid)

    # ---- the named sites of the other provinces (tools/provinces.py): culture ruins, regional keeps and towers
    salt[0] = 718274500
    for sid, types, variants, kw in PROVINCE_SITES:
        kw = dict(kw)
        theme = kw.pop("theme")
        place_set(sid, types, variants, structure(theme, place=pf(types, variants), **kw))
        if theme in ("ruin", "fort"):
            road_ids.append("skycraft:" + sid)

    # Added after the historical sequence: preserve every existing placement salt.
    for sid, types, variants, kw in [
        ("site/scorpion_den", D, ["scorpion_den"], dict(theme="scorpion_den", kit="scorpion_den", flavor=10)),
        ("site/breton_keep_ruin", ["fort", "dungeon_fixed"], ["breton_keep_ruin"],
         dict(theme="breton_keep_ruin", kit="breton_keep_ruin")),
    ]:
        theme = kw.pop("theme")
        place_set(sid, types, variants, structure(theme, place=pf(types, variants), **kw))

    for sid, types, variants, options in ADDITIONAL_PROVINCE_SITES:
        kw = dict(options)
        theme = kw.pop("theme")
        place_set(sid, types, variants, structure(theme, place=pf(types, variants), **kw))
        if theme in ("ruin", "fort"):
            road_ids.append("skycraft:" + sid)

    # ---- the wilds beyond Skyrim: random sites by culture (old ids kept for the Skyrim-era dungeon types)
    everywhere_but_skyrim = []
    underground = [
        ("cave", structure("cave", regional=True), 7),
        ("mine", structure("mine", regional=True), 2),
        ("vampire_lair", structure("vampire_lair"), 1),
        ("necromancer_lair", structure("necromancer_lair"), 2),
        ("hagraven_lair", structure("hagraven_lair", where=["high_rock", "realm"]), 1),
        ("dwemer_ruin", structure("dwemer", where=["morrowind", "hammerfell", "high_rock", "realm"], spawns=DWEMER_SPAWNS), 3),
        ("nordic_barrow", structure("barrow", kit="nord", where=["atmora", "realm"]), 3),
        ("ruin/ayleid", structure("ruin", kit="ayleid", where=["cyrodiil", "valenwood", "high_rock", "realm"]), 5),
        ("ruin/velothi_tomb", structure("ruin", kit="velothi", where=["morrowind"]), 5),
        ("ruin/daedric", structure("ruin", kit="daedric", where=["morrowind", "realm"]), 3),
        ("ruin/xanmeer", structure("ruin", kit="xanmeer", where=["black_marsh", "realm"]), 6),
        ("ruin/moon_temple", structure("ruin", kit="khajiit", where=["elsweyr", "realm"]), 6),
        ("ruin/tree_tomb", structure("ruin", kit="bosmer", where=["valenwood", "realm"]), 5),
        ("ruin/crystal_vault", structure("ruin", kit="altmer", where=["summerset", "realm"]), 5),
        ("ruin/yokudan_tomb", structure("ruin", kit="redguard", where=["hammerfell", "yokuda", "realm"]), 6),
        ("ruin/atmoran_tomb", structure("ruin", kit="atmoran", where=["atmora"]), 6),
        ("ruin/sea_vault", structure("ruin", kit="maormer", where=["pyandonea", "summerset"]), 5),
        ("ruin/coral_pit", structure("ruin", kit="sload", where=["thras"]), 6),
        ("ruin/akaviri", structure("ruin", kit="akaviri", where=["akavir"]), 6),
        ("ice_cave", structure("cave", kit="ice", flavor=7, where=["atmora", "realm"]), 2),
    ]
    surface = [
        ("fort", structure("fort", regional=True), 4),
        ("bandit_camp", structure("bandit_camp", pad=True), 4),
        ("watchtower", structure("watchtower", regional=True), 3),
        ("mage_tower", structure("mage_tower", regional=True), 2),
        ("giant_camp", structure("giant_camp", where=["atmora", "realm"]), 1),
        ("dragon_lair", structure("dragon_lair", where=["atmora", "akavir", "realm"]), 1),
        ("nordic_ruin", structure("nordic_ruin", where=["atmora", "realm"], pad=True), 1),
        ("grove", structure("grove", where=["valenwood", "realm"], pad=True), 1),
        ("cabin", structure("cabin", regional=True, pad=True), 2),
        ("daedric_shrine", structure("prince_shrine", where=["realm", "atmora", "akavir", "yokuda", "pyandonea", "thras"], pad=True), 1),
        ("forsworn_camp", structure("forsworn_camp", kit="forsworn", where=["high_rock"], pad=True), 1),
    ]
    for sid, st, w in underground + surface:
        written.append(write_json(os.path.join(sdir, sid + ".json"), st))
        dungeon_ids.append("skycraft:" + sid)
    for setid, entries, spacing, sep, s in [("wilds_underground", underground, 30, 10, 718273501),
                                           ("wilds_surface", surface, 36, 12, 718273502)]:
        written.append(write_json(os.path.join(setdir, setid + ".json"), {
            "structures": [{"structure": "skycraft:" + sid, "weight": w} for sid, st, w in entries],
            "placement": {"type": "minecraft:random_spread", "spacing": spacing, "separation": sep, "salt": s}}))
    road_ids += ["skycraft:nordic_barrow", "skycraft:dwemer_ruin", "skycraft:fort", "skycraft:mine", "skycraft:giant_camp",
                 "skycraft:ruin/ayleid", "skycraft:ruin/velothi_tomb", "skycraft:ruin/xanmeer", "skycraft:ruin/moon_temple",
                 "skycraft:ruin/yokudan_tomb", "skycraft:ruin/crystal_vault", "skycraft:ruin/tree_tomb"]

    # old per-theme sets are folded into the two wilds sets
    for old in ["cave", "daedric_shrine", "dragon_lair", "dwemer_ruin", "fort", "giant_camp", "hagraven_lair", "mine",
                "necromancer_lair", "nordic_barrow", "vampire_lair"]:
        path = os.path.join(setdir, old + ".json")
        if os.path.exists(path):
            os.remove(path)

    tags = os.path.join(DATA, "tags", "worldgen", "structure")
    write_json(os.path.join(tags, "dungeons.json"), {"replace": False, "values": [
        "minecraft:mineshaft", "minecraft:mineshaft_mesa", "minecraft:stronghold", "minecraft:ancient_city",
        "minecraft:desert_pyramid", "minecraft:jungle_pyramid", "minecraft:trail_ruins"] + sorted(set(dungeon_ids))})
    write_json(os.path.join(tags, "road_destinations.json"), {"replace": False, "values": ["#skycraft:towns"] + sorted(set(road_ids))})
    write_json(os.path.join(DATA, "tags", "worldgen", "biome", "dungeons", "anywhere.json"), {"replace": False, "values": [
        "#minecraft:is_overworld", {"id": "#skycraft:dungeon_land", "required": False}]})
    print(f"wrote {len(written)} structure files, {len(set(dungeon_ids))} dungeon structures")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    if sys.argv[1] == "--structures":
        structures()  # only the structure / structure set JSON (no reference file needed)
    else:
        main(sys.argv[1])
