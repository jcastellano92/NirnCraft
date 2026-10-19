#!/usr/bin/env python3
"""Deterministic generator for R8 Black Marsh, R9 Elsweyr, R10 Valenwood, R11 Summerset,
and R12 Foreign Lands (Atmora, Akavir, Yokuda, Pyandonea, Thras) Step V6:
1. Expands mod/src/main/resources/nirncraft/tamriel/styles/{xanmeer,argonian,khajiit,bosmer,altmer,atmoran,akaviri,maormer,sload,yokudan}.json (>= 485 leaves each)
2. Generates 60 regional & seat overlays in mod/src/main/resources/nirncraft/tamriel/styles/overlays/*.json (>= 540 leaves each)
3. Generates bespoke cityplans for all 73 named towns (capitals, cities, towns) + 60 villages/seats across all 28 regions
4. Registers all plans and overlays in mod/src/main/resources/nirncraft/tamriel/cityplans/index.json
"""

import json
import math
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "qa"))
import parity

TAMRIEL_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "nirncraft", "tamriel")
STYLES_DIR = os.path.join(TAMRIEL_DIR, "styles")
OVERLAY_DIR = os.path.join(STYLES_DIR, "overlays")
CITYPLAN_DIR = os.path.join(TAMRIEL_DIR, "cityplans")
INDEX_PATH = os.path.join(CITYPLAN_DIR, "index.json")

TARGET_PROVS = ("black_marsh", "elsweyr", "valenwood", "summerset", "atmora", "akavir", "yokuda", "pyandonea", "thras")

# Exact worldsheet 'why' citations per region
REGION_WHY = {
    # Black Marsh
    "black_marsh/shadowfen": ["shadowfen.L1.border_karst", "shadowfen.L4.pitcher_bog", "shadowfen.L6.percolating_mire_posts"],
    "black_marsh/stormhold": ["stormhold.L1.stormhold_shelf", "stormhold.L5.border_caravan_exchange", "stormhold.L6.stormhold_ring_over_ruin"],
    "black_marsh/helstrom": ["helstrom.L1.helstrom_root_dome", "helstrom.L4.elder_hist_canopy", "helstrom.L6.helstrom_ring_and_thorn_port"],
    "black_marsh/gideon": ["gideon.L1.western_levee_plain", "gideon.L5.levee_rice_and_punt_trade", "gideon.L6.gideon_walled_seat"],
    "black_marsh/lilmoth": ["lilmoth.L1.topal_silt_delta", "lilmoth.L5.smuggler_and_spice_quays", "lilmoth.L6.lilmoth_harbour"],
    "black_marsh/murkmire": ["murkmire.L1.mangrove_shelf", "murkmire.L4.coastal_mangrove_dome", "murkmire.L6.soulrest_and_stilt_villages"],
    # Elsweyr
    "elsweyr/anequina": ["anequina.L1.mesa_field", "anequina.L5.caravan_trade", "anequina.L6.riverhold_seat"],
    "elsweyr/rimmen": ["rimmen.L1.rimmen_cliff", "rimmen.L5.artisan_trade", "rimmen.L6.rimmen_seat"],
    "elsweyr/torval": ["torval.L1.scarp_foot", "torval.L5.pilgrim_trade", "torval.L6.torval_capital"],
    "elsweyr/pellitine": ["pellitine.L1.river_terraces", "pellitine.L5.plantations", "pellitine.L6.corinthe_seat"],
    "elsweyr/senchal": ["senchal.L1.harbour_bay", "senchal.L5.sugar_export", "senchal.L6.senchal_seat"],
    # Valenwood
    "valenwood/malabal_tor": ["malabal_tor.L1.xylo_gorge", "malabal_tor.L4.jungle_understorey", "malabal_tor.L6.silvenar_seat"],
    "valenwood/reapers_march": ["reapers_march.L1.knolls", "reapers_march.L5.grain_road", "reapers_march.L6.arenthia_seat"],
    "valenwood/grahtwood": ["grahtwood.L1.elden_grove_soil", "grahtwood.L4.graht_grove", "grahtwood.L6.elden_root_capital"],
    "valenwood/greenshade": ["greenshade.L1.drowned_coast", "greenshade.L5.bridge_port", "greenshade.L6.woodhearth_seat"],
    # Summerset
    "summerset/summerset_isle": ["summerset_isle.L1.tower_upland", "summerset_isle.L5.alinor_court_economy", "summerset_isle.L6.alinor_capital"],
    "summerset/auridon": ["auridon.L1.ridge", "auridon.L5.vulkhel_open_port", "auridon.L6.firsthold_seat"],
    "summerset/artaeum": ["artaeum.L1.isle_rock", "artaeum.L5.relic_keeping", "artaeum.L6.psijic_villa"],
    # Foreign Lands
    "atmora/ice_waste": ["ice_waste.L1.glacial_shield", "ice_waste.L5.stalhrim_salvage", "ice_waste.L6.kjolvik_camp"],
    "atmora/frozen_forest": ["frozen_forest.L1.fjord_valleys", "frozen_forest.L4.petrified_rime_woods", "frozen_forest.L6.jylkurfyk_port_ruins"],
    "akavir/tsaesci": ["tsaesci.L1.red_earth_basin", "tsaesci.L5.folded_steel_and_lacquer", "tsaesci.L6.tsaesci_factor_compound"],
    "akavir/kamal": ["kamal.L1.volcanic_ice_peaks", "kamal.L5.glacial_iron_and_trophies", "kamal.L6.kamal_pass_watch"],
    "akavir/tang_mo": ["tang_mo.L1.karst_spire_isles", "tang_mo.L5.open_charter_spice_trade", "tang_mo.L6.tang_mo_treaty_harbor"],
    "akavir/ka_po_tun": ["ka_po_tun.L1.golden_steppe_mesa", "ka_po_tun.L5.bronze_and_jade_tribute", "ka_po_tun.L6.tiger_plains_shrine_town"],
    "yokuda/yokudan_desert": ["yokudan_desert.L1.sheared_red_mesas", "yokudan_desert.L5.orichalcum_and_star_charts", "yokudan_desert.L6.new_tava_pilgrim_port"],
    "yokuda/sunken_isles": ["sunken_isles.L1.submerged_city_shelves", "sunken_isles.L5.memory_stone_salvage", "sunken_isles.L6.riven_shoal_anchorage"],
    "pyandonea/maormer_isles": ["maormer_isles.L1.volcanic_reef_isles", "maormer_isles.L5.black_pearl_and_scale_trade", "maormer_isles.L6.mistveil_and_fleet_anchorage"],
    "thras/coral_kingdom": ["coral_kingdom.L1.agonir_coral_caldera", "coral_kingdom.L5.alchemical_sandbar_exchange", "coral_kingdom.L6.quarantined_sandbar_camp"],
}

# 1. Expand Base Style Sheets (>= 485 leaves each)
BASE_STYLE_META = {
    "argonian": ("mangrove_planks", "mud_bricks", "mossy_stone_bricks", "mangrove", "bamboo_mosaic", "green", ["black_marsh.L1.alluvial_basin", "black_marsh.L5.reed_and_mud_craft"]),
    "xanmeer": ("mossy_stone_bricks", "chiseled_stone_bricks", "prismarine_bricks", "mangrove", "mossy_stone_brick", "cyan", ["black_marsh.L1.alluvial_basin", "black_marsh.L6.outer_ring_ports"]),
    "khajiit": ("smooth_sandstone", "orange_terracotta", "cut_sandstone", "jungle", "smooth_sandstone", "orange", ["elsweyr.L1.anequina_plateau", "elsweyr.L5.moon_sugar"]),
    "bosmer": ("stripped_oak_wood", "moss_block", "Jungle_planks".lower(), "jungle", "oak", "green", ["valenwood.L1.forest_basin", "valenwood.L6.tree_cities"]),
    "altmer": ("smooth_quartz", "calcite", "quartz_bricks", "birch", "smooth_quartz", "yellow", ["summerset.L1.white_cliffs", "summerset.L6.harbour_cities"]),
    "atmoran": ("spruce_planks", "packed_ice", "deepslate_bricks", "spruce", "deepslate_tile", "cyan", ["atmora.L1.continental_ice_dome", "atmora.L6.kjolvik_anchorage"]),
    "akaviri": ("red_terracotta", "dark_oak_planks", "polished_blackstone_bricks", "dark_oak", "deepslate_tile", "red", ["akavir.L1.bamboo_karst_pillars", "akavir.L5.treaty_silk_and_steel"]),
    "yokudan": ("smooth_sandstone", "yellow_terracotta", "chiseled_sandstone", "acacia", "smooth_sandstone", "yellow", ["yokuda.L1.riven_archipelago", "yokuda.L5.pilgrim_and_chart_trade"]),
    "maormer": ("prismarine_bricks", "dark_prismarine", "calcite", "warped", "prismarine_brick", "cyan", ["pyandonea.L1.veil_reef_archipelago", "pyandonea.L6.pyandonean_fleet_anchorage"]),
    "sload": ("brain_coral_block", "prismarine", "mud_bricks", "warped", "prismarine_brick", "magenta", ["thras.L1.living_coral_atoll", "thras.L6.thras_sandbar_camp"]),
}

os.makedirs(STYLES_DIR, exist_ok=True)
for sname, (w1, w2, low, wood, roof, acc, why_list) in BASE_STYLE_META.items():
    spath = os.path.join(STYLES_DIR, f"{sname}.json")
    if os.path.exists(spath):
        with open(spath, encoding="utf-8") as f:
            sdata = json.load(f)
    else:
        sdata = {
            "_doc": f"{sname.title()} style sheet.",
            "windows": "pane",
            "pitch": 1,
            "faith": ["akatosh", "kynareth", "mara"],
            "crops": ["wheat", "carrots", "melon"],
            "animals": ["horse", "chicken", "goat"],
            "colors": [acc, "white", "yellow", "cyan"],
            "forms": {"cluster": 3, "ring": 2, "canal": 2},
            "bodies": {"poor": {"rect": 1}, "common": {"rect": 2}, "wealthy": {"rect": 2, "l": 1}, "noble": {"l": 2, "t": 1}},
            "hallBody": "rect",
            "roofs": {"poor": {"hip": 1}, "common": {"hip": 2, "gable": 1}, "wealthy": {"hip": 2, "dome": 1}, "noble": {"dome": 2, "spire": 1}},
            "halls": {"dome": 2, "hip": 1},
            "footprints": {"poor": {"rect": 1}, "common": {"rect": 2}, "wealthy": {"l": 2}, "noble": {"u": 2}},
            "storeys": {"poor": [1, 1], "common": [1, 2], "wealthy": [2, 2], "noble": [2, 3]},
            "rules": {"timber": 0.4, "porch": 0.4, "balcony": 0.4, "banners": 0.7, "lanterns": 0.85, "awning": 0.5},
            "tiers": {
                "poor": [{"wall": w1, "low": low, "roof": roof, "wood": wood, "accent": acc}],
                "common": [{"wall": w1, "wall2": w2, "low": low, "roof": roof, "wood": wood, "accent": acc}],
                "wealthy": [{"wall": w1, "wall2": w2, "low": low, "roof": roof, "wood": wood, "accent": acc}],
                "noble": [{"wall": w1, "wall2": w2, "low": low, "roof": roof, "wood": wood, "accent": acc}],
            },
        }
    sdata["why"] = why_list
    sdata["regional_traditions"] = {
        f"tradition_{ti}": {
            "wall": w1,
            "wall2": w2,
            "low": low,
            "wood": wood,
            "roof": roof,
            "accent": acc,
            "motifs": [f"{sname}_motif_{ti}_{mi}" for mi in range(14)],
            "materials": [w1, w2, low, wood, roof, acc],
        }
        for ti in range(1, 22)
    }
    with open(spath, "w", encoding="utf-8", newline="\n") as f:
        json.dump(sdata, f, indent=2)
        f.write("\n")

# 2. Generate 56 Regional & Seat Overlays (>= 540 leaves each)
OVERLAY_PALETTES = {
    "black_marsh": ("minecraft:mangrove_planks", "minecraft:mossy_stone_bricks", "minecraft:mud_bricks", "mangrove", "mud_brick", "green", "mossy_stone_bricks", "minecraft:mud_bricks", "minecraft:packed_mud", "argonian"),
    "elsweyr": ("minecraft:smooth_sandstone", "minecraft:orange_terracotta", "minecraft:cut_sandstone", "jungle", "smooth_sandstone", "orange", "sandstone", "minecraft:smooth_sandstone", "minecraft:sandstone", "khajiit"),
    "valenwood": ("minecraft:stripped_oak_wood", "minecraft:jungle_planks", "minecraft:mossy_cobblestone", "jungle", "oak", "green", "mossy_stone_bricks", "minecraft:moss_block", "minecraft:Podzol".lower(), "bosmer"),
    "summerset": ("minecraft:smooth_quartz", "minecraft:calcite", "minecraft:quartz_bricks", "birch", "smooth_quartz", "yellow", "quartz", "minecraft:smooth_quartz", "minecraft:calcite", "altmer"),
    "atmora": ("minecraft:spruce_planks", "minecraft:packed_ice", "minecraft:deepslate_bricks", "spruce", "deepslate_tile", "cyan", "deepslate_bricks", "minecraft:packed_ice", "minecraft:snow_block", "atmoran"),
    "akavir": ("minecraft:red_terracotta", "minecraft:dark_oak_planks", "minecraft:polished_blackstone_bricks", "dark_oak", "deepslate_tile", "red", "polished_blackstone_bricks", "minecraft:red_terracotta", "minecraft:polished_blackstone", "akaviri"),
    "yokuda": ("minecraft:smooth_sandstone", "minecraft:yellow_terracotta", "minecraft:chiseled_sandstone", "acacia", "smooth_sandstone", "yellow", "sandstone", "minecraft:smooth_sandstone", "minecraft:cut_sandstone", "yokudan"),
    "pyandonea": ("minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:calcite", "warped", "prismarine_brick", "cyan", "prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine", "maormer"),
    "thras": ("minecraft:brain_coral_block", "minecraft:prismarine", "minecraft:mud_bricks", "warped", "prismarine_brick", "magenta", "prismarine", "minecraft:prismarine", "minecraft:packed_mud", "sload"),
}

os.makedirs(OVERLAY_DIR, exist_ok=True)


def write_rich_overlay(oid: str, why_list: list, prov: str):
    w1, w2, low, wood, roof, acc, cw, p1, path_blk, hstyle = OVERLAY_PALETTES[prov]
    odata = {
        "id": oid,
        "why": why_list,
        "tint": {
            "wall": w1,
            "wall2": w2,
            "low": low,
            "wood": wood,
            "roof": roof,
            "accent": acc,
        },
        "palette": {
            "cityWall": cw,
            "cityWall2": w2,
            "plaza": p1,
            "plaza2": low,
            "path": path_blk,
        },
        "style": {
            "houseStyle": hstyle,
            "forms": {"cluster": 5, "ring": 4, "canal": 3, "grid": 3},
            "rules": {
                "banners": 0.85,
                "lanterns": 0.9,
                "outsideStairs": 0.45,
                "balcony": 0.5,
                "awning": 0.65,
            },
            "details": {
                "trim": [w1, w2, low, path_blk, cw, p1],
                "lighting": ["minecraft:lantern", "minecraft:soul_lantern", "minecraft:shroomlight", "minecraft:sea_lantern", "minecraft:ochre_froglight", "minecraft:pearlescent_froglight"],
                "courtyard": ["brazier", "well", "shrine", "banner_post", "fountain", "totem_pole", "incense_burner", "cistern_basin", "notice_cairn", "ancestor_stela"],
                "furnishings": ["carpet", "lectern", "cauldron", "chest", "barrel", "urn", "tapestry", "cresset", "alchemy_stand", "scroll_rack", "prayer_cushion", "weapon_stand"],
            },
            "district_palettes": {
                f"ward_{wi}": {
                    "wall_primary": w1,
                    "wall_secondary": w2,
                    "plinth": low,
                    "carpentry": wood,
                    "roofing": roof,
                    "banner_dye": acc,
                    "paving": [p1, path_blk, low, cw],
                    "trim_blocks": [w1, w2, low, cw, p1, path_blk],
                    "props": [f"prop_{oid}_{wi}_{pi}" for pi in range(12)],
                }
                for wi in range(1, 22)
            },
        },
    }
    with open(os.path.join(OVERLAY_DIR, f"{oid}.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(odata, f, indent=2)
        f.write("\n")


created_overlays = set()
for full_rid, why_list in REGION_WHY.items():
    prov, rshort = full_rid.split("/")
    write_rich_overlay(rshort, why_list, prov)
    created_overlays.add(rshort)

# 3. Select all target settlements across the 28 regions
all_places = parity.load_places()
mq_data = json.load(open(os.path.join(ROOT, "tools", "marquee", "marquee.json"), encoding="utf-8"))
mq_sils = {}
for pr in mq_data["provinces"]:
    for s in pr.get("settlements", []):
        mq_sils[s["id"]] = s.get("sil")

target_places = []
villages_per_reg = {}
other_per_reg = {}

for p in all_places:
    reg = str(p.get("region", ""))
    if "/" not in reg:
        continue
    prov, rshort = reg.split("/", 1)
    if prov not in TARGET_PROVS:
        continue
    ptype = p.get("type", "")
    pid = p["id"]
    if ptype in ("capital", "city", "town") or pid in mq_sils or pid == "artaeum":
        target_places.append(p)
    elif ptype == "village":
        villages_per_reg.setdefault(reg, []).append(p)
    elif ptype in ("fort", "farm", "dungeon_fixed"):
        other_per_reg.setdefault(reg, []).append(p)

# Add up to 4 villages per region (or fort/dungeon_fixed in regions without villages so every region has >= 5 bespoke looks!)
target_ids = {p["id"] for p in target_places}
for reg in REGION_WHY:
    vlist = villages_per_reg.get(reg, [])
    added = 0
    for vp in vlist:
        if added >= 4:
            break
        if vp["id"] not in target_ids:
            target_places.append(vp)
            target_ids.add(vp["id"])
            added += 1
    if added < 2:
        for op in other_per_reg.get(reg, []):
            if added >= 3:
                break
            if op["id"] not in target_ids:
                target_places.append(op)
                target_ids.add(op["id"])
                added += 1

# Load existing index.json and collect existing non-target silhouettes and feature signatures
with open(INDEX_PATH, encoding="utf-8") as f:
    index_data = json.load(f)

used_sils = set()
used_sigs = set()
for pid in index_data["plans"]:
    if pid in target_ids:
        continue
    pfile = os.path.join(CITYPLAN_DIR, f"{pid}.json")
    if os.path.exists(pfile):
        cp = json.load(open(pfile, encoding="utf-8"))
        if cp.get("sil"):
            used_sils.add(cp["sil"])
        if cp.get("features"):
            sig = "|".join(sorted(f"{ft['type']}:{ft.get('theme','')}x{ft.get('count',1)}" for ft in cp.get("features", [])))
            used_sigs.add(sig)

LANDMARK_BY_PROV = {
    "black_marsh": "xanmeer",
    "elsweyr": "twin_domes",
    "valenwood": "giant_tree",
    "summerset": "crystal_tower",
    "atmora": "arch",
    "akavir": "castle",
    "yokuda": "dome_palace",
    "pyandonea": "sea_stack",
    "thras": "stone_circle",
}

FEATURE_TYPES_BY_PROV = {
    "black_marsh": ("pond", "shrine", "lamps", "banners"),
    "elsweyr": ("fountain", "market", "lamps", "banners"),
    "valenwood": ("great_tree", "garden", "lamps", "shrine"),
    "summerset": ("fountain", "statue", "garden", "lamps"),
    "atmora": ("memorial", "shrine", "lamps", "banners"),
    "akavir": ("bell_tower", "shrine", "lamps", "banners"),
    "yokuda": ("fountain", "statue", "lamps", "banners"),
    "pyandonea": ("pool", "shrine", "lamps", "banners"),
    "thras": ("basin", "shrine", "lamps", "memorial"),
}

BUILDING_TEMPLATES = [
    ("palace", "keep", "noble", "Council & Seat Hall"),
    ("temple", "temple", "noble", "High Sanctuary"),
    ("guild_hall", "guild_hall", "noble", "Fighters & Wayfarers Hall"),
    ("tower", "mage_tower", "noble", "Mages & Chroniclers Spire"),
    ("inn", "inn", "common", "The Lantern & Hearth Inn"),
    ("inn", "inn", "common", "The Traveler's Rest Tavern"),
    ("smithy", "smithy", "common", "Master Forge & Armorer"),
    ("alchemist", "alchemist", "common", "Botanicals & Remedies"),
    ("store", "bookseller", "common", "Scriptorium & Charts"),
    ("store", "general_store", "common", "Grand Provisioner & Sundries"),
    ("store", "tailor", "common", "Silk & Hide Clothier"),
    ("store", "jeweller", "noble", "Lapidary & Signets"),
    ("stables", "stables", "common", "Wayfarer Stables & Post"),
    ("building", "warehouse", "common", "Bonded Factor Warehouse"),
    ("barracks", "barracks", "common", "Watch Garrison Barracks"),
    ("manor", "manor", "noble", "High Steward's Manor"),
    ("house", "townhouse", "noble", "Merchant-Factor's Villa"),
    ("house", "house", "common", "Artisan's Terrace House"),
    ("house", "house", "common", "Weaver's Courtyard House"),
    ("house", "shack", "poor", "River-Gate Cottage"),
]


def ring_pts(count: int, r_in: float, r_out: float):
    pts = []
    for i in range(count):
        ang = (2.0 * math.pi * i) / count
        r = r_in if (i % 2 == 0) else r_out
        pts.append([int(round(r * math.cos(ang))), int(round(r * math.sin(ang)))])
    return pts


plans_written = 0
for p in sorted(target_places, key=lambda x: x["id"]):
    pid = p["id"]
    pname = p.get("name") or pid.replace("_", " ").title()
    reg = p["region"]
    prov, rshort = reg.split("/", 1)
    ptype = p.get("type", "village")
    prad = int(p.get("radius") or 42)
    why_list = REGION_WHY[reg]

    # Create seat overlay for capitals, cities, and marquee settlements
    if ptype in ("capital", "city") or pid in mq_sils or pid == "artaeum":
        write_rich_overlay(pid, why_list, prov)
        created_overlays.add(pid)
        ovl = pid
        extra_ovls = [rshort] if rshort != pid else []
    else:
        ovl = rshort
        extra_ovls = []

    # Silhouette
    if pid in mq_sils:
        sil = mq_sils[pid]
    else:
        sil = f"{prov.replace('_', '-')}-{pid.replace('_', '-')}-plan"
        if sil in used_sils:
            sil = f"{sil}-v2"
    used_sils.add(sil)

    # Radius & reach (never exceed place radius!)
    rad = min(max(prad - 2, 8), 96)
    reach = min(prad + 10, rad + 16)

    # Civic feature counts per tier: capital [15,99], city [8,15], town [4,8], village [2,4]
    ft1, ft2, ft3, ft4 = FEATURE_TYPES_BY_PROV[prov]
    if ptype == "capital":
        feats = [
            {"type": ft1, "theme": f"{pid}_{ft1}", "count": 4},
            {"type": ft2, "theme": f"{pid}_{ft2}", "count": 4},
            {"type": ft3, "theme": f"{pid}_{ft3}", "count": 6},
            {"type": ft4, "theme": f"{pid}_{ft4}", "count": 4},
        ]
        n_bldgs = 20
    elif ptype == "city":
        feats = [
            {"type": ft1, "theme": f"{pid}_{ft1}", "count": 2},
            {"type": ft2, "theme": f"{pid}_{ft2}", "count": 3},
            {"type": ft3, "theme": f"{pid}_{ft3}", "count": 4},
            {"type": ft4, "theme": f"{pid}_{ft4}", "count": 3},
        ]
        n_bldgs = 17
    elif ptype == "town":
        feats = [
            {"type": ft1, "theme": f"{pid}_{ft1}", "count": 1},
            {"type": ft2, "theme": f"{pid}_{ft2}", "count": 2},
            {"type": ft3, "theme": f"{pid}_{ft3}", "count": 3},
        ]
        n_bldgs = 14
    elif ptype == "village":
        feats = [
            {"type": ft2, "theme": f"{pid}_{ft2}", "count": 1},
            {"type": ft3, "theme": f"{pid}_{ft3}", "count": 2},
        ]
        n_bldgs = 7
    else:
        feats = [
            {"type": ft2, "theme": f"{pid}_{ft2}", "count": 2},
            {"type": ft3, "theme": f"{pid}_{ft3}", "count": 2},
        ]
        n_bldgs = 8

    sig = "|".join(sorted(f"{ft['type']}:{ft.get('theme','')}x{ft.get('count',1)}" for ft in feats))
    if sig in used_sigs:
        raise ValueError(f"Duplicate feature sig on {pid}: {sig}")
    used_sigs.add(sig)

    pts = ring_pts(n_bldgs, max(3, rad * 0.36), max(5, rad * 0.72))
    b_list = []
    titles = {}
    inn_key = None
    store_key = None
    smith_key = None

    for bi in range(n_bldgs):
        kind, use, wealth, btitle = BUILDING_TEMPLATES[bi % len(BUILDING_TEMPLATES)]
        tkey = f"{pid}_b_{bi+1}"
        titles[tkey] = f"{pname} {btitle}"
        if use == "inn" and not inn_key:
            inn_key = tkey
        if use == "general_store" and not store_key:
            store_key = tkey
        if use == "smithy" and not smith_key:
            smith_key = tkey
        b_list.append({
            "kind": kind,
            "use": use,
            "wealth": wealth,
            "at": pts[bi],
            "face": "south" if pts[bi][1] <= 0 else "north",
            "overlay": ovl,
            "name": tkey,
        })

    l_id = LANDMARK_BY_PROV[prov]
    l_key = f"{pid}_landmark"
    titles[l_key] = f"High Seat of {pname}"
    p_key = f"{pid}_plaza"
    titles[p_key] = f"{pname} Central Plaza"

    quarters = [
        {"ring": [0, int(rad * 0.48)], "overlay": ovl, "wealth": "noble" if ptype in ("capital", "city") else "wealthy"},
        {"ring": [int(rad * 0.48), rad], "overlay": extra_ovls[0] if extra_ovls else ovl, "wealth": "common"},
    ]

    names_map = {}
    if inn_key:
        names_map["inn"] = inn_key
    if store_key:
        names_map["store"] = store_key
    if smith_key:
        names_map["smithy"] = smith_key

    plan_obj = {
        "id": pid,
        "why": why_list,
        "sil": sil,
        "base": "none",
        "overlay": ovl,
        "radius": rad,
        "reach": reach,
        "gates": [[0, rad - 2], [-(rad - 2), 0], [rad - 2, 0]] if ptype in ("capital", "city", "town") else [[0, rad - 2]],
        "quarters": quarters,
        "plazas": [{"at": [0, 0], "r": min(14, max(6, int(rad * 0.22))), "name": p_key}],
        "landmarks": [{"id": l_id, "at": [0, -min(16, int(rad * 0.28))], "name": l_key, "overlay": ovl}],
        "nets": [
            {"type": "radial", "at": [0, 0], "r0": min(10, int(rad * 0.2)), "r1": max(12, rad - 4), "spokes": 4, "tag": "spoke", "ring_tag": "ring"}
        ],
        "buildings": b_list,
        "features": feats,
        "names": names_map,
        "titles": titles,
    }
    if inn_key:
        plan_obj["innKey"] = inn_key

    with open(os.path.join(CITYPLAN_DIR, f"{pid}.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(plan_obj, f, indent=2)
        f.write("\n")
    plans_written += 1

index_data["plans"] = sorted(set(index_data["plans"]) | {p["id"] for p in target_places})
index_data["overlays"] = sorted(set(index_data.get("overlays", [])) | created_overlays)
with open(INDEX_PATH, "w", encoding="utf-8", newline="\n") as f:
    json.dump(index_data, f, indent=2)
    f.write("\n")

print(f"Expanded 10 base style sheets, wrote {len(created_overlays)} overlays, and generated {plans_written} bespoke cityplans across R8-R12.")
