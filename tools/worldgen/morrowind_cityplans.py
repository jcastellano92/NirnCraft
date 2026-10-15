#!/usr/bin/env python3
"""Deterministic generator for Morrowind & Solstheim style sheet (velothi.json),
29 regional & Great House style overlays, and 43 bespoke cityplans.
"""
import json
import math
import os

ROOT = "mod/src/main/resources/skycraft/tamriel"
STYLES_DIR = os.path.join(ROOT, "styles")
OVERLAY_DIR = os.path.join(STYLES_DIR, "overlays")
CITYPLAN_DIR = os.path.join(ROOT, "cityplans")
INDEX_PATH = os.path.join(CITYPLAN_DIR, "index.json")

# 1. Generate mod/src/main/resources/skycraft/tamriel/styles/velothi.json (>= 480 leaves)
VELOTHI_STYLE = {
    "_doc": "Velothi & Great House style sheet for Morrowind and Solstheim (>= 450 leaves). Sub-palettes: hlaalu, redoran, telvanni, indoril, dres, temple, ashlander.",
    "why": [
        "morrowind.L6.great_house_seats",
        "morrowind.L7.five_great_houses",
        "morrowind.L8.tribunal_temple",
    ],
    "windows": "fence",
    "hearth": False,
    "faith": ["almalexia", "sotha_sil", "vivec", "azura", "boethiah", "mephala", "reclamations"],
    "crops": ["saltrice", "ash_yam", "wickwheat", "marshmerrow", "comberry", "hackle_lo", "scathecraw", "coda_flower"],
    "animals": ["guar", "netch", "kwama_forager", "nix_hound", "silt_strider", "chicken", "goat"],
    "colors": ["orange", "red", "brown", "yellow", "purple", "cyan", "blue", "black"],
    "forms": {"cluster": 4, "linear": 3, "canal": 3, "crossroads": 2, "ring": 2, "terraced": 3},
    "roofs": {
        "poor": {"flat": 3, "dome": 1, "gabled": 1},
        "common": {"flat": 4, "dome": 2, "carapace": 2},
        "wealthy": {"flat": 3, "dome": 3, "carapace": 2, "canton": 2},
        "noble": {"dome": 4, "flat": 2, "carapace": 3, "canton": 3, "mushroom_cap": 2},
    },
    "halls": {"dome": 3, "flat": 2, "carapace": 2, "canton": 2},
    "footprints": {
        "poor": {"rect": 3, "oval": 2},
        "common": {"rect": 3, "l": 2, "oval": 2},
        "wealthy": {"l": 3, "rect": 2, "t": 2, "courtyard": 2},
        "noble": {"t": 3, "u": 2, "l": 2, "courtyard": 3, "octagonal": 2},
    },
    "storeys": {"poor": [1, 1], "common": [1, 2], "wealthy": [2, 3], "noble": [2, 4]},
    "rules": {
        "timber": 0.35,
        "porch": 0.25,
        "balcony": 0.45,
        "outsideStairs": 0.55,
        "shutters": 0.15,
        "flowerBoxes": 0.2,
        "chimney": 0.1,
        "annex": 0.4,
        "banners": 0.85,
        "lanterns": 0.9,
        "awning": 0.65,
        "tower": 0.45,
        "ashBreakWalls": 0.7,
        "ancestorAlcoves": 0.8,
        "paperLanternStrings": 0.75,
        "siltStriderRamp": 0.35,
    },
    "trim_motifs": {
        "hlaalu": ["chiseled_sandstone", "cut_sandstone_slab", "jungle_trapdoor", "orange_banner", "copper_grate"],
        "redoran": ["polished_andesite", "mud_brick_wall", "dark_oak_fence", "red_banner", "bone_block"],
        "telvanni": ["crimson_hyphae", "warped_hyphae", "shroomlight", "purple_banner", "amethyst_cluster"],
        "indoril": ["smooth_quartz", "chiseled_quartz_block", "gold_block", "cyan_banner", "sea_lantern"],
        "dres": ["mud_bricks", "mossy_stone_bricks", "mangrove_fence", "green_banner", "chain"],
        "temple": ["polished_basalt", "chiseled_deepslate", "yellow_terracotta", "blue_banner", "soul_lantern"],
        "ashlander": ["bone_block", "stripped_spruce_log", "brown_wool", "orange_carpet", "campfire"],
    },
    "interiors": {
        "hlaalu": ["jungle_planks", "orange_carpet", "lantern", "lectern", "barrel", "chest", "cartography_table"],
        "redoran": ["dark_oak_planks", "red_carpet", "soul_lantern", "smithing_table", "armor_stand", "cauldron", "smoker"],
        "telvanni": ["crimson_planks", "purple_carpet", "shroomlight", "enchanting_table", "brewing_stand", "bookshelf", "end_rod"],
        "indoril": ["smooth_quartz", "cyan_carpet", "sea_lantern", "bell", "lectern", "chiseled_bookshelf", "Gold_block" if False else "gold_block"],
        "dres": ["mangrove_planks", "green_carpet", "lantern", "composter", "barrel", "chain", "loom"],
        "temple": ["polished_andesite", "yellow_carpet", "soul_lantern", "respawn_anchor", "candle", "lectern", "decorated_pot"],
        "ashlander": ["brown_carpet", "campfire", "cauldron", "loom", "bundle" if False else "chest", "skull", "lantern"],
    },
    "courtyards": {
        "hlaalu": ["smooth_sandstone", "cut_sandstone", "water_basin", "merchant_awning", "guar_trough"],
        "redoran": ["packed_mud", "polished_andesite", "ash_brazier", "dueling_circle", "chitin_windbreak"],
        "telvanni": ["mycelium", "moss_block", "giant_mushroom", "spore_lantern", "levitation_shaft"],
        "indoril": ["smooth_quartz", "lapis_block", "ordinal_fountain", "incense_burner", "tribunal_obelisk"],
        "dres": ["mud_bricks", "packed_mud", "saltrice_paddy", "irrigation_sluice", "stockade_post"],
    },
    "tiers": {
        "poor": [
            {"wall": "packed_mud", "low": "mud_bricks", "roof": "mud_brick", "wood": "jungle", "timber": False, "accent": "brown"},
            {"wall": "sandstone", "low": "sandstone", "roof": "sandstone", "wood": "jungle", "timber": False, "accent": "orange"},
            {"wall": "smooth_sandstone", "wall2": "sandstone", "low": "mud_bricks", "roof": "smooth_sandstone", "wood": "dark_oak", "timber": False},
            {"wall": "andesite", "low": "tuff", "roof": "andesite", "wood": "dark_oak", "timber": False, "accent": "gray"},
        ],
        "common": [
            {"wall": "smooth_sandstone", "low": "cut_sandstone", "roof": "smooth_sandstone", "wood": "jungle", "accent": "orange"},
            {"wall": "sandstone", "wall2": "chiseled_sandstone", "low": "cut_sandstone", "roof": "sandstone", "wood": "jungle", "timber": False, "accent": "red"},
            {"wall": "mud_bricks", "wall2": "packed_mud", "low": "sandstone", "roof": "mud_brick", "wood": "dark_oak", "timber": False, "accent": "yellow"},
            {"wall": "polished_andesite", "low": "tuff", "roof": "polished_andesite", "wood": "dark_oak", "timber": False, "accent": "cyan"},
        ],
        "wealthy": [
            {"wall": "smooth_sandstone", "low": "cut_sandstone", "roof": "smooth_sandstone", "trim": "sandstone", "wood": "jungle", "window": "orange_stained_glass_pane", "accent": "orange"},
            {"wall": "cut_sandstone", "wall2": "chiseled_sandstone", "low": "smooth_sandstone", "roof": "sandstone", "wood": "dark_oak", "timber": False, "accent": "red"},
            {"wall": "white_terracotta", "low": "cut_sandstone", "roof": "smooth_sandstone", "wood": "jungle", "timber": False, "accent": "yellow"},
            {"wall": "polished_andesite", "low": "polished_basalt", "roof": "polished_andesite", "wood": "dark_oak", "timber": False, "accent": "cyan"},
        ],
        "noble": [
            {"wall": "smooth_sandstone", "wall2": "cut_sandstone", "low": "chiseled_sandstone", "roof": "smooth_sandstone", "wood": "jungle", "window": "orange_stained_glass_pane", "timber": False, "accent": "orange"},
            {"wall": "cut_sandstone", "low": "smooth_sandstone", "roof": "smooth_sandstone", "roofFull": "orange_terracotta", "wood": "dark_oak", "timber": False, "accent": "red"},
            {"wall": "chiseled_sandstone", "wall2": "smooth_sandstone", "low": "cut_sandstone", "roof": "sandstone", "wood": "jungle", "timber": False, "accent": "yellow"},
            {"wall": "smooth_quartz", "wall2": "calcite", "low": "chiseled_quartz_block", "roof": "smooth_quartz", "wood": "dark_oak", "timber": False, "accent": "cyan"},
        ],
    },
    "styles": {
        "hlaalu": {
            "bodies": {"poor": {"rect": 1}, "common": {"rect": 3, "l": 1}, "wealthy": {"l": 2, "t": 1}, "noble": {"t": 2, "u": 1}},
            "hallBody": "rect",
            "forms": {"canal": 3, "linear": 3, "crossroads": 2},
            "tiers": {
                "poor": [
                    {"wall": "sandstone", "low": "mud_bricks", "roof": "sandstone", "wood": "jungle", "timber": False, "accent": "yellow"},
                    {"wall": "packed_mud", "low": "sandstone", "roof": "mud_brick", "wood": "jungle", "timber": False, "accent": "orange"},
                ],
                "common": [
                    {"wall": "smooth_sandstone", "low": "cut_sandstone", "roof": "smooth_sandstone", "wood": "jungle", "timber": False, "accent": "yellow"},
                    {"wall": "cut_sandstone", "low": "mud_bricks", "roof": "sandstone", "wood": "dark_oak", "timber": False, "accent": "orange"},
                ],
                "wealthy": [
                    {"wall": "smooth_sandstone", "wall2": "chiseled_sandstone", "low": "cut_sandstone", "roof": "smooth_sandstone", "wood": "jungle", "timber": False, "accent": "yellow"},
                    {"wall": "yellow_terracotta", "low": "smooth_sandstone", "roof": "smooth_sandstone", "wood": "jungle", "timber": False, "accent": "orange"},
                ],
                "noble": [
                    {"wall": "smooth_sandstone", "wall2": "chiseled_sandstone", "low": "cut_sandstone", "roof": "smooth_sandstone", "roofFull": "yellow_terracotta", "wood": "jungle", "timber": False, "accent": "yellow"},
                    {"wall": "white_terracotta", "wall2": "smooth_sandstone", "low": "chiseled_sandstone", "roof": "smooth_sandstone", "wood": "dark_oak", "timber": False, "accent": "orange"},
                ],
            },
        },
        "redoran": {
            "bodies": {"poor": {"chitin": 1}, "common": {"chitin": 3, "rect": 1}, "wealthy": {"chitin": 2, "rect": 1}, "noble": {"chitin": 2, "rect": 1}},
            "hallBody": "chitin",
            "forms": {"linear": 3, "cluster": 3, "crossroads": 1},
            "tiers": {
                "poor": [
                    {"wall": "packed_mud", "low": "mud_bricks", "roof": "mud_brick", "roofFull": "terracotta", "wood": "dark_oak", "timber": False},
                    {"wall": "mud_bricks", "low": "packed_mud", "roof": "mud_brick", "roofFull": "brown_terracotta", "wood": "dark_oak", "timber": False},
                    {"wall": "brown_terracotta", "low": "mud_bricks", "roof": "mud_brick", "roofFull": "terracotta", "wood": "spruce", "timber": False},
                ],
                "common": [
                    {"wall": "packed_mud", "low": "mud_bricks", "roof": "mud_brick", "roofFull": "terracotta", "wood": "dark_oak", "timber": False, "accent": "red"},
                    {"wall": "terracotta", "low": "mud_bricks", "roof": "mud_brick", "roofFull": "red_terracotta", "wood": "dark_oak", "timber": False},
                    {"wall": "mud_bricks", "low": "polished_andesite", "roof": "mud_brick", "roofFull": "brown_terracotta", "wood": "dark_oak", "timber": False},
                ],
                "wealthy": [
                    {"wall": "terracotta", "low": "polished_andesite", "roof": "mud_brick", "roofFull": "red_terracotta", "wood": "dark_oak", "timber": False},
                    {"wall": "packed_mud", "low": "andesite", "roof": "mud_brick", "roofFull": "orange_terracotta", "wood": "dark_oak", "timber": False},
                    {"wall": "red_terracotta", "low": "mud_bricks", "roof": "mud_brick", "roofFull": "terracotta", "wood": "dark_oak", "timber": False},
                ],
                "noble": [
                    {"wall": "polished_andesite", "low": "andesite", "roof": "polished_andesite", "wood": "dark_oak", "timber": False},
                    {"wall": "terracotta", "low": "polished_andesite", "roof": "mud_brick", "wood": "dark_oak", "timber": False},
                    {"wall": "mud_bricks", "low": "polished_andesite", "roof": "polished_andesite", "wood": "dark_oak", "timber": False},
                ],
            },
        },
        "telvanni": {
            "bodies": {"poor": {"mushroom": 1}, "common": {"mushroom": 1}, "wealthy": {"mushroom": 1}, "noble": {"mushroom": 1}},
            "hallBody": "mushroom",
            "forms": {"ring": 3, "cluster": 3},
            "tiers": {
                "poor": [
                    {"wall": "mushroom_stem", "roofFull": "brown_mushroom_block", "wood": "mangrove", "timber": False},
                    {"wall": "mushroom_stem", "roofFull": "red_mushroom_block", "wood": "mangrove", "timber": False},
                    {"wall": "skycraft:parasol_stem", "roofFull": "skycraft:parasol_cap", "wood": "mangrove", "timber": False},
                ],
                "common": [
                    {"wall": "mushroom_stem", "roofFull": "red_mushroom_block", "wood": "mangrove", "timber": False, "accent": "purple"},
                    {"wall": "mushroom_stem", "roofFull": "skycraft:telvanni_cap", "wood": "mangrove", "timber": False, "accent": "magenta"},
                    {"wall": "skycraft:parasol_stem", "roofFull": "brown_mushroom_block", "wood": "crimson", "timber": False, "accent": "purple"},
                ],
                "wealthy": [
                    {"wall": "mushroom_stem", "roofFull": "skycraft:telvanni_cap", "wood": "crimson", "timber": False, "accent": "purple"},
                    {"wall": "mushroom_stem", "roofFull": "crimson_hyphae", "wood": "crimson", "timber": False, "accent": "magenta"},
                    {"wall": "skycraft:parasol_stem", "roofFull": "red_mushroom_block", "wood": "mangrove", "timber": False},
                ],
                "noble": [
                    {"wall": "mushroom_stem", "roofFull": "skycraft:telvanni_cap", "wood": "crimson", "timber": False, "light": "shroomlight"},
                    {"wall": "mushroom_stem", "roofFull": "warped_wart_block", "wood": "warped", "timber": False, "light": "shroomlight"},
                    {"wall": "skycraft:parasol_stem", "roofFull": "crimson_hyphae", "wood": "crimson", "timber": False, "light": "shroomlight"},
                ],
            },
        },
        "indoril": {
            "bodies": {"poor": {"rect": 1}, "common": {"rect": 2, "l": 1}, "wealthy": {"l": 2, "t": 1}, "noble": {"t": 2, "u": 1}},
            "hallBody": "rect",
            "forms": {"canal": 3, "grid": 3, "ring": 2},
            "tiers": {
                "poor": [
                    {"wall": "calcite", "low": "polished_andesite", "roof": "polished_andesite", "wood": "dark_oak", "timber": False, "accent": "cyan"},
                    {"wall": "white_terracotta", "low": "stone_bricks", "roof": "stone_brick", "wood": "dark_oak", "timber": False, "accent": "blue"},
                ],
                "common": [
                    {"wall": "smooth_quartz", "low": "polished_andesite", "roof": "polished_andesite", "wood": "dark_oak", "timber": False, "accent": "cyan"},
                    {"wall": "calcite", "wall2": "smooth_quartz", "low": "stone_bricks", "roof": "warped", "wood": "warped", "timber": False, "accent": "blue"},
                ],
                "wealthy": [
                    {"wall": "smooth_quartz", "wall2": "chiseled_quartz_block", "low": "polished_basalt", "roof": "warped", "wood": "warped", "timber": False, "accent": "cyan"},
                    {"wall": "calcite", "wall2": "smooth_quartz", "low": "chiseled_stone_bricks", "roof": "deepslate_tile", "wood": "dark_oak", "timber": False, "accent": "yellow"},
                ],
                "noble": [
                    {"wall": "smooth_quartz", "wall2": "chiseled_quartz_block", "low": "gold_block", "roof": "warped", "wood": "warped", "timber": False, "accent": "cyan"},
                    {"wall": "quartz_bricks", "wall2": "smooth_quartz", "low": "polished_basalt", "roof": "deepslate_tile", "wood": "dark_oak", "timber": False, "accent": "yellow"},
                ],
            },
        },
        "dres": {
            "bodies": {"poor": {"rect": 1}, "common": {"rect": 2, "l": 1}, "wealthy": {"l": 2, "t": 1}, "noble": {"t": 2, "u": 1}},
            "hallBody": "rect",
            "forms": {"canal": 3, "cluster": 3},
            "tiers": {
                "poor": [
                    {"wall": "mud_bricks", "low": "packed_mud", "roof": "mud_brick", "wood": "mangrove", "timber": False, "accent": "green"},
                    {"wall": "mossy_stone_bricks", "low": "mud_bricks", "roof": "mud_brick", "wood": "mangrove", "timber": False, "accent": "brown"},
                ],
                "common": [
                    {"wall": "mud_bricks", "wall2": "mossy_stone_bricks", "low": "polished_basalt", "roof": "mud_brick", "wood": "mangrove", "timber": False, "accent": "green"},
                    {"wall": "packed_mud", "wall2": "mud_bricks", "low": "mossy_cobblestone", "roof": "deepslate_tile", "wood": "dark_oak", "timber": False, "accent": "lime"},
                ],
                "wealthy": [
                    {"wall": "polished_andesite", "wall2": "mossy_stone_bricks", "low": "polished_basalt", "roof": "deepslate_tile", "wood": "mangrove", "timber": False, "accent": "green"},
                    {"wall": "mud_bricks", "wall2": "chiseled_stone_bricks", "low": "polished_andesite", "roof": "mud_brick", "wood": "dark_oak", "timber": False, "accent": "lime"},
                ],
                "noble": [
                    {"wall": "polished_andesite", "wall2": "chiseled_stone_bricks", "low": "polished_basalt", "roof": "deepslate_tile", "wood": "mangrove", "timber": False, "accent": "green"},
                    {"wall": "smooth_stone", "wall2": "mossy_stone_bricks", "low": "chiseled_deepslate", "roof": "deepslate_tile", "wood": "dark_oak", "timber": False, "accent": "lime"},
                ],
            },
        },
        "temple": {
            "bodies": {"poor": {"rect": 1}, "common": {"rect": 2}, "wealthy": {"rect": 2, "t": 1}, "noble": {"t": 2, "u": 1}},
            "hallBody": "rect",
            "forms": {"canal": 4, "grid": 3},
            "tiers": {
                "poor": [
                    {"wall": "andesite", "low": "tuff", "roof": "andesite", "wood": "dark_oak", "timber": False, "accent": "yellow"},
                ],
                "common": [
                    {"wall": "polished_andesite", "low": "tuff", "roof": "polished_andesite", "wood": "dark_oak", "timber": False, "accent": "yellow"},
                    {"wall": "yellow_terracotta", "low": "polished_andesite", "roof": "polished_andesite", "wood": "dark_oak", "timber": False, "accent": "blue"},
                ],
                "wealthy": [
                    {"wall": "polished_andesite", "wall2": "yellow_terracotta", "low": "polished_basalt", "roof": "polished_andesite", "wood": "dark_oak", "timber": False, "accent": "yellow"},
                ],
                "noble": [
                    {"wall": "polished_andesite", "wall2": "chiseled_stone_bricks", "low": "gold_block", "roof": "polished_andesite", "wood": "dark_oak", "timber": False, "accent": "yellow"},
                ],
            },
        },
        "ashlander": {
            "bodies": {"poor": {"chitin": 1}, "common": {"chitin": 1}, "wealthy": {"chitin": 1}, "noble": {"chitin": 1}},
            "hallBody": "chitin",
            "forms": {"ring": 4, "cluster": 2},
            "tiers": {
                "poor": [
                    {"wall": "brown_wool", "low": "packed_mud", "roof": "mud_brick", "roofFull": "brown_terracotta", "wood": "spruce", "timber": False, "accent": "orange"},
                ],
                "common": [
                    {"wall": "brown_terracotta", "low": "bone_block", "roof": "mud_brick", "roofFull": "orange_terracotta", "wood": "spruce", "timber": False, "accent": "red"},
                ],
                "wealthy": [
                    {"wall": "orange_terracotta", "low": "bone_block", "roof": "mud_brick", "roofFull": "red_terracotta", "wood": "dark_oak", "timber": False, "accent": "red"},
                ],
                "noble": [
                    {"wall": "red_terracotta", "low": "bone_block", "roof": "mud_brick", "roofFull": "yellow_terracotta", "wood": "dark_oak", "timber": False, "accent": "yellow"},
                ],
            },
        },
    },
}

os.makedirs(STYLES_DIR, exist_ok=True)
with open(os.path.join(STYLES_DIR, "velothi.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(VELOTHI_STYLE, f, indent=2)
    f.write("\n")

# 2. Generate 29 Regional & Great House Overlays in mod/src/main/resources/skycraft/tamriel/styles/overlays/*.json
OVERLAY_SPECS = {
    # 13 Regions
    "vvardenfell_ashlands": (["vvardenfell_ashlands.L1.ash_plain", "vvardenfell_ashlands.L1.crab_shell_rock"], "minecraft:terracotta", "minecraft:packed_mud", "minecraft:polished_andesite", "dark_oak", "mud_brick", "red", "mud_bricks", "minecraft:polished_andesite", "minecraft:packed_mud", "redoran"),
    "west_gash": (["west_gash.L1.gash_ridges", "west_gash.L1.odai_gorge"], "minecraft:smooth_sandstone", "minecraft:cut_sandstone", "minecraft:mud_bricks", "jungle", "smooth_sandstone", "orange", "sandstone", "minecraft:smooth_sandstone", "minecraft:gravel", "hlaalu"),
    "bitter_coast": (["bitter_coast.L1.mud_coast", "bitter_coast.L4.bitter_swamp"], "minecraft:mangrove_planks", "minecraft:mud_bricks", "minecraft:mossy_cobblestone", "mangrove", "spruce", "green", "mud_bricks", "minecraft:mossy_cobblestone", "minecraft:packed_mud", "hlaalu"),
    "ascadian_isles": (["ascadian_isles.L1.fertile_lowland", "ascadian_isles.L4.saltrice_paddy"], "minecraft:smooth_sandstone", "minecraft:white_terracotta", "minecraft:cut_sandstone", "jungle", "smooth_sandstone", "yellow", "sandstone", "minecraft:smooth_sandstone", "minecraft:stone_bricks", "hlaalu"),
    "azuras_coast": (["azuras_coast.L1.islet_maze", "azuras_coast.L1.sea_cliffs"], "minecraft:mushroom_stem", "minecraft:polished_andesite", "minecraft:tuff", "crimson", "warped", "purple", "stone_bricks", "minecraft:polished_andesite", "minecraft:gravel", "telvanni"),
    "molag_amur": (["molag_amur.L1.lava_field", "molag_amur.L1.obsidian_flats"], "minecraft:polished_basalt", "minecraft:blackstone", "minecraft:deepslate_bricks", "dark_oak", "deepslate_tile", "orange", "deepslate_bricks", "minecraft:blackstone", "minecraft:basalt", "temple"),
    "grazelands": (["grazelands.L1.grass_slopes", "grazelands.L4.wickwheat_steppe"], "minecraft:mushroom_stem", "minecraft:smooth_sandstone", "minecraft:stone_bricks", "crimson", "warped", "magenta", "stone_bricks", "minecraft:smooth_sandstone", "minecraft:gravel", "telvanni"),
    "sheogorad": (["sheogorad.L1.wave_cut_islets", "sheogorad.L4.islet_heath"], "minecraft:cobblestone", "minecraft:spruce_planks", "minecraft:stone_bricks", "spruce", "deepslate_tile", "blue", "stone_bricks", "minecraft:cobblestone", "minecraft:gravel", "velothi"),
    "red_mountain": (["red_mountain.L1.caldera", "red_mountain.L1.foyadas"], "minecraft:polished_blackstone_bricks", "minecraft:basalt", "minecraft:deepslate_bricks", "crimson", "deepslate_tile", "red", "deepslate_bricks", "minecraft:polished_blackstone_bricks", "minecraft:basalt", "temple"),
    "telvannis": (["telvannis.L1.peninsula_cliffs", "telvannis.L4.coastal_mushroom_forest"], "minecraft:mushroom_stem", "minecraft:crimson_hyphae", "minecraft:polished_basalt", "crimson", "warped", "purple", "stone_bricks", "minecraft:polished_andesite", "minecraft:moss_block", "telvanni"),
    "stonefalls": (["stonefalls.L1.lava_shields", "stonefalls.L4.basalt_field"], "minecraft:polished_andesite", "minecraft:deepslate_bricks", "minecraft:polished_basalt", "dark_oak", "deepslate_tile", "red", "deepslate_bricks", "minecraft:polished_andesite", "minecraft:basalt", "indoril"),
    "deshaan": (["deshaan.L1.fungal_plains", "deshaan.L4.glowing_fungal_plain"], "minecraft:smooth_quartz", "minecraft:calcite", "minecraft:polished_andesite", "dark_oak", "warped", "cyan", "stone_bricks", "minecraft:smooth_quartz", "minecraft:polished_andesite", "indoril"),
    "solstheim": (["solstheim.L1.moesring_massif", "solstheim.L1.ash_plain"], "minecraft:spruce_planks", "minecraft:terracotta", "minecraft:stone_bricks", "spruce", "spruce", "orange", "stone_bricks", "minecraft:cobblestone", "minecraft:gravel", "redoran"),
    # 16 Seats
    "mournhold": (["deshaan.L1.fungal_plains", "deshaan.L5.temple_economy"], "minecraft:smooth_quartz", "minecraft:calcite", "minecraft:chiseled_quartz_block", "warped", "warped", "cyan", "quartz", "minecraft:smooth_quartz", "minecraft:smooth_quartz", "indoril"),
    "vivec": (["ascadian_isles.L1.lake_basins", "morrowind.L8.tribunal_temple"], "minecraft:smooth_sandstone", "minecraft:yellow_terracotta", "minecraft:polished_andesite", "jungle", "smooth_sandstone", "yellow", "sandstone", "minecraft:smooth_sandstone", "minecraft:cut_sandstone", "temple"),
    "balmora": (["west_gash.L1.odai_gorge", "west_gash.L3.odai_river"], "minecraft:smooth_sandstone", "minecraft:cut_sandstone", "minecraft:sandstone", "jungle", "smooth_sandstone", "orange", "sandstone", "minecraft:smooth_sandstone", "minecraft:sandstone", "hlaalu"),
    "aldruhn": (["vvardenfell_ashlands.L1.ash_plain", "vvardenfell_ashlands.L1.crab_shell_rock"], "minecraft:terracotta", "minecraft:packed_mud", "minecraft:mud_bricks", "dark_oak", "mud_brick", "red", "mud_bricks", "minecraft:packed_mud", "minecraft:coarse_dirt", "redoran"),
    "sadrith_mora": (["zafirbel_bay.L1.bay_islands", "zafirbel_bay.L4.telvanni_mushroom_forest"], "minecraft:mushroom_stem", "minecraft:red_mushroom_block", "minecraft:brown_mushroom_block", "crimson", "warped", "purple", "stone_bricks", "minecraft:polished_andesite", "minecraft:mycelium", "telvanni"),
    "necrom": (["necrom.L1.tomb_hills", "necrom.L4.tomb_terraces"], "minecraft:smooth_quartz", "minecraft:calcite", "minecraft:polished_basalt", "dark_oak", "deepslate_tile", "white", "quartz", "minecraft:smooth_quartz", "minecraft:calcite", "indoril"),
    "ebonheart": (["stonefalls.L3.inner_sea_shore", "morrowind.L6.great_house_seats"], "minecraft:stone_bricks", "minecraft:deepslate_bricks", "minecraft:polished_andesite", "dark_oak", "deepslate_tile", "red", "stone_bricks", "minecraft:deepslate_bricks", "minecraft:stone_bricks", "indoril"),
    "tear": (["deshaan.L3.lagoon_fens", "deshaan.L4.paddy_round_tear"], "minecraft:mud_bricks", "minecraft:mossy_stone_bricks", "minecraft:polished_basalt", "mangrove", "mud_brick", "green", "mossy_stone_bricks", "minecraft:mud_bricks", "minecraft:packed_mud", "dres"),
    "blacklight": (["stonefalls.L1.velothi_foothills", "morrowind.L6.great_house_seats"], "minecraft:terracotta", "minecraft:polished_andesite", "minecraft:mud_bricks", "dark_oak", "mud_brick", "red", "stone_bricks", "minecraft:polished_andesite", "minecraft:polished_andesite", "redoran"),
    "narsis": (["deshaan.L1.fungal_plains", "morrowind.L6.great_house_seats"], "minecraft:smooth_sandstone", "minecraft:yellow_terracotta", "minecraft:cut_sandstone", "jungle", "smooth_sandstone", "yellow", "sandstone", "minecraft:smooth_sandstone", "minecraft:smooth_sandstone", "hlaalu"),
    "firewatch": (["telvannis.L1.peninsula_cliffs", "telvannis.L5.sea_trade"], "minecraft:stone_bricks", "minecraft:calcite", "minecraft:polished_andesite", "spruce", "brick", "red", "stone_bricks", "minecraft:polished_andesite", "minecraft:stone_bricks", "velothi"),
    "raven_rock": (["raven_rock.L1.bay_headland", "raven_rock.L1.mine_shaft"], "minecraft:terracotta", "minecraft:packed_mud", "minecraft:polished_andesite", "dark_oak", "mud_brick", "orange", "mud_bricks", "minecraft:polished_andesite", "minecraft:gravel", "redoran"),
    "skaal": (["skaal.L1.east_shore", "skaal.L1.forest_uplands"], "minecraft:spruce_planks", "minecraft:stripped_spruce_log", "minecraft:cobblestone", "spruce", "spruce", "white", "cobblestone", "minecraft:spruce_planks", "minecraft:snow_block", "ashlander"),
    "skaal_village": (["skaal.L1.east_shore", "skaal.L3.lake_fjalding_shore"], "minecraft:spruce_planks", "minecraft:stripped_spruce_log", "minecraft:cobblestone", "spruce", "spruce", "cyan", "cobblestone", "minecraft:spruce_planks", "minecraft:gravel", "ashlander"),
    "tel_mithryn": (["isinfier.L1.ash_shelf_plain", "solstheim.L1.ash_plain"], "minecraft:mushroom_stem", "minecraft:brown_mushroom_block", "minecraft:tuff", "crimson", "warped", "purple", "stone_bricks", "minecraft:tuff", "minecraft:coarse_dirt", "telvanni"),
    "thirsk": (["felsaad.L1.mortrag_glacier", "skaal.L2.lake_ice"], "minecraft:spruce_planks", "minecraft:dark_oak_planks", "minecraft:cobblestone", "spruce", "spruce", "yellow", "cobblestone", "minecraft:spruce_planks", "minecraft:gravel", "redoran"),
}

os.makedirs(OVERLAY_DIR, exist_ok=True)
for oid, (why, w1, w2, low, wood, roof, acc, cw, p1, path_blk, house_sub) in OVERLAY_SPECS.items():
    odata = {
        "id": oid,
        "why": why,
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
            "houseStyle": house_sub,
            "forms": {"cluster": 6, "linear": 4, "canal": 3, "ring": 2},
            "rules": {
                "banners": 0.85,
                "lanterns": 0.9,
                "outsideStairs": 0.5,
                "balcony": 0.45,
                "awning": 0.65,
            },
            "details": {
                "trim": [w1, w2, low, path_blk, cw, p1],
                "lighting": ["minecraft:lantern", "minecraft:soul_lantern", "minecraft:shroomlight", "minecraft:sea_lantern", "minecraft:ochre_froglight", "minecraft:pearlescent_froglight"],
                "courtyard": ["brazier", "well", "shrine", "banner_post", "silt_strider_dock", "ancestor_alcove", "incense_burner", "cistern_basin", "notice_cairn", "velothi_obelisk"],
                "furnishings": ["carpet", "lectern", "cauldron", "chest", "barrel", "urn", "tapestry", "cresset", "alchemy_stand", "scroll_rack", "prayer_cushion", "bonemold_rack"],
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

# 3. Load cityplans/index.json and collect existing non-Morrowind feature signatures
with open(INDEX_PATH, "r", encoding="utf-8") as f:
    index_data = json.load(f)

MW_PLAN_IDS = [
    "mournhold", "vivec", "balmora", "aldruhn", "sadrith_mora", "ebonheart", "necrom", "tear", "blacklight", "firewatch",
    "raven_rock", "molag_mar", "gnisis", "caldera", "suran", "port_telvannis", "gorne", "silgrad_tower", "davons_watch",
    "kragenmoor", "narsis", "skaal_village", "thirsk_mead_hall", "tel_mithryn", "maar_gan", "pelagiad", "seyda_neen",
    "tel_branora", "tel_aruhn", "tel_mora", "vos", "tel_vos", "dagon_fel", "gnaar_mok", "hla_oad", "khuul", "ald_velothi",
    "tel_fyr", "ghostgate", "urshilaku_camp", "ahemmusa_camp", "zainab_camp", "erabenimsun_camp",
]

used_sigs = set()
for pid in index_data["plans"]:
    if pid in MW_PLAN_IDS:
        continue
    pfile = os.path.join(CITYPLAN_DIR, f"{pid}.json")
    if os.path.exists(pfile):
        cp = json.load(open(pfile, encoding="utf-8"))
        sig = "|".join(sorted(f"{ft['type']}:{ft.get('theme','')}x{ft.get('count',1)}" for ft in cp.get("features", [])))
        used_sigs.add(sig)


def verify_unique_sig(pid: str, features: list):
    sig = "|".join(sorted(f"{ft['type']}:{ft.get('theme','')}x{ft.get('count',1)}" for ft in features))
    if sig in used_sigs:
        raise ValueError(f"Duplicate feature signature for {pid}: {sig}")
    used_sigs.add(sig)


def ring_positions(count: int, r_inner: float, r_outer: float):
    pts = []
    for i in range(count):
        ang = (2 * math.pi * i) / count
        r = r_inner if (i % 2 == 0) else r_outer
        pts.append([int(round(r * math.cos(ang))), int(round(r * math.sin(ang)))])
    return pts


USE_MAP = {
    "temple": ("temple", "temple", "noble"),
    "guild": ("guild_hall", "guild_hall", "noble"),
    "mage_tower": ("tower", "mage_tower", "noble"),
    "inn": ("inn", "inn", "common"),
    "market": ("store", "general_store", "common"),
    "bookseller": ("store", "bookseller", "common"),
    "tailor": ("store", "tailor", "common"),
    "jeweller": ("store", "jeweller", "noble"),
    "smith": ("smithy", "smithy", "common"),
    "alchemist": ("alchemist", "alchemist", "common"),
    "manor": ("manor", "manor", "noble"),
    "townhouse": ("house", "townhouse", "noble"),
    "house": ("house", "house", "common"),
    "shack": ("house", "shack", "poor"),
    "tent": ("tent", "hovel", "poor"),
    "barracks": ("barracks", "barracks", "common"),
    "guardhouse": ("barracks", "guardhouse", "common"),
    "warehouse": ("building", "warehouse", "common"),
    "stable": ("stables", "stables", "common"),
    "barn": ("farmhouse", "barn", "common"),
    "mill": ("mill", "mill", "common"),
    "smelter": ("smithy", "smelter", "common"),
    "tower": ("tower", "watchtower", "common"),
    "hall": ("palace", "keep", "noble"),
    "longhouse": ("longhouse", "longhouse", "noble"),
}

# 4. Capital + 9 Cities
MAJOR_CITIES = [
    {
        "id": "mournhold",
        "why": ["deshaan.L1.fungal_plains", "deshaan.L3.mournhold_aqueducts", "deshaan.L5.temple_economy"],
        "sil": "dome-citadel-plaza",
        "overlay": "mournhold",
        "extra_overlays": ["deshaan"],
        "radius": 140,
        "reach": 164,
        "landmark": ("dome_temple", [0, 0], "mournhold_high_chapel", "High Chapel of Almalexia"),
        "extra_landmarks": [
            ("dome_palace", [0, -68], "mournhold_royal_palace", "Royal Palace of King Helseth"),
            ("orrery", [48, 22], "mournhold_imperial_shrine", "Mournhold Clockwork Shrine"),
        ],
        "gates": [[0, -132], [132, 0], [0, 132], [-132, 0]],
        "buildings": [
            ("temple", "Temple Courtyard Chancery", "mh_temple_chancery"),
            ("temple", "Hall of Ministry & Mercy", "mh_hall_of_ministry"),
            ("hall", "Royal Throne Hall", "mh_throne_hall"),
            ("barracks", "Royal Guard Barracks", "mh_royal_guard_barracks"),
            ("barracks", "High Ordinator Garrison", "mh_ordinator_garrison"),
            ("inn", "The Winged Guar", "mh_winged_guar"),
            ("market", "Great Bazaar Pavilion", "mh_great_bazaar"),
            ("smith", "Bazaar Armorer's Hall", "mh_bazaar_armorer"),
            ("alchemist", "Golden Flower Apothecary", "mh_golden_flower_apothecary"),
            ("bookseller", "Temple Canon Scriptorium", "mh_canon_scriptorium"),
            ("tailor", "Godsreach Silk Clothiers", "mh_godsreach_clothiers"),
            ("jeweller", "Mournhold Lapidary & Gold", "mh_lapidary"),
            ("guild", "Mournhold Craftsmen's Hall", "mh_craftsmens_hall"),
            ("guild", "Museum of Artifacts", "mh_museum_of_artifacts"),
            ("manor", "Velas Manor", "mh_velas_manor"),
            ("manor", "Sadri Manor", "mh_sadri_manor"),
            ("manor", "Andoren Estate", "mh_andoren_estate"),
            ("manor", "Llethan Townhouse", "mh_llethan_townhouse"),
            ("manor", "Indoril Magistracy Hall", "mh_indoril_magistracy"),
            ("manor", "Hlaalu Embassy Manor", "mh_hlaalu_embassy"),
            ("townhouse", "Geon Auline's House", "mh_geon_auline_house"),
            ("townhouse", "Ignatius Flaccus's House", "mh_flaccus_house"),
            ("house", "Tervur Braven's House", "mh_braven_house"),
            ("house", "Suns-In-Shadow Tenement", "mh_bazaar_tenement"),
            ("warehouse", "Aqueduct Cistern Store", "mh_aqueduct_store"),
        ],
        "features": [
            {"type": "fountain", "theme": "almalexia_plaza", "count": 4},
            {"type": "statue", "theme": "tribunal:almalexia", "count": 2},
            {"type": "garden", "theme": "palace_courtyard", "count": 4},
            {"type": "banners", "theme": "house_indoril", "count": 4},
            {"type": "lamps", "theme": "mournhold_brazier", "count": 4},
        ],
    },
    {
        "id": "vivec",
        "why": ["ascadian_isles.L1.lake_basins", "morrowind.L6.great_house_seats", "morrowind.L8.tribunal_temple"],
        "sil": "canton-water",
        "overlay": "vivec",
        "extra_overlays": ["ascadian_isles"],
        "radius": 118,
        "reach": 138,
        "landmark": ("canton", [0, 0], "vivec_palace_canton", "Palace of Vivec"),
        "extra_landmarks": [
            ("dome_temple", [0, 38], "vivec_high_fane", "High Fane of the Temple"),
            ("rock", [0, 68], "vivec_ministry_of_truth", "Ministry of Truth (Baar Dau)"),
            ("arena", [42, -12], "vivec_arena_canton", "Vivec Arena Canton"),
        ],
        "gates": [[0, -110], [-110, 0], [110, 0]],
        "buildings": [
            ("hall", "Foreign Quarter Canton Plaza", "vv_foreign_quarter"),
            ("manor", "Hlaalu Canton Treasury", "vv_hlaalu_canton"),
            ("manor", "Redoran Canton Council", "vv_redoran_canton"),
            ("mage_tower", "Telvanni Canton Tower", "vv_telvanni_canton"),
            ("temple", "Hall of Justice & Wisdom", "vv_hall_of_justice"),
            ("guild", "Vivec Mages Guild", "vv_mages_guild"),
            ("guild", "Vivec Fighters Guild", "vv_fighters_guild"),
            ("inn", "The Flowers of Gold", "vv_flowers_of_gold"),
            ("inn", "The Black Shalk Cornerclub", "vv_black_shalk"),
            ("inn", "Elven Nations Cornerclub", "vv_elven_nations"),
            ("inn", "No Name Club", "vv_no_name_club"),
            ("bookseller", "Jobasha's Rare Books", "vv_jobashas_books"),
            ("bookseller", "Simine Fralinie's Bookstore", "vv_simine_books"),
            ("alchemist", "Aurane Frernis Apothecary", "vv_aurane_apothecary"),
            ("smith", "Sirollus Saccus's Smithy", "vv_sirollus_smithy"),
            ("market", "Lucretinaus Olcinius's Trader", "vv_lucretinaus_trader"),
            ("house", "St. Olms Canton Plaza", "vv_st_olms_canton"),
            ("house", "St. Delyn Canton Plaza", "vv_st_delyn_canton"),
        ],
        "features": [
            {"type": "banners", "theme": "tribunal_cantons", "count": 4},
            {"type": "lamps", "theme": "canton_paper_lantern", "count": 4},
            {"type": "fountain", "theme": "high_fane_basin", "count": 2},
            {"type": "statue", "theme": "tribunal:vivec", "count": 2},
        ],
    },
    {
        "id": "balmora",
        "why": ["west_gash.L1.odai_gorge", "west_gash.L3.odai_river", "morrowind.L6.great_house_seats"],
        "sil": "twin-bank-river-terrace",
        "overlay": "balmora",
        "extra_overlays": ["west_gash"],
        "radius": 96,
        "reach": 114,
        "landmark": ("awning_row", [-18, 0], "balmora_commercial_plaza", "Balmora Commercial Plaza"),
        "gates": [[0, -90], [0, 90], [90, 0]],
        "buildings": [
            ("manor", "Hlaalu Council Manor", "bm_hlaalu_council_manor"),
            ("temple", "Balmora Tribunal Temple", "bm_tribunal_temple"),
            ("guild", "Balmora Fighters Guild", "bm_fighters_guild"),
            ("guild", "Balmora Mages Guild", "bm_mages_guild"),
            ("inn", "Eight Plates", "bm_eight_plates"),
            ("inn", "South Wall Cornerclub", "bm_south_wall_cornerclub"),
            ("inn", "Council Club", "bm_council_club"),
            ("inn", "Lucky Lockup", "bm_lucky_lockup"),
            ("alchemist", "Nalcarya of White Haven", "bm_nalcarya_alchemist"),
            ("smith", "Meldor's Armor & Forge", "bm_meldors_forge"),
            ("market", "Ra'Virr's Trader", "bm_ravirrs_trader"),
            ("market", "Clagius Clanler's Outfitter", "bm_clagius_outfitter"),
            ("bookseller", "Dorisa Darvel's Bookseller", "bm_dorisa_books"),
            ("manor", "Nerano Manor", "bm_nerano_manor"),
            ("manor", "Hlaalo Manor", "bm_hlaalo_manor"),
            ("house", "Caius Cosades's House", "bm_caius_cosades_house"),
            ("house", "Dura gra-Bol's Tower House", "bm_dura_gra_bol_house"),
        ],
        "features": [
            {"type": "lamps", "theme": "odai_paper_lanterns", "count": 4},
            {"type": "banners", "theme": "house_hlaalu_scales", "count": 4},
            {"type": "market", "theme": "balmora_awning_stalls", "count": 2},
            {"type": "well", "theme": "odai_cistern", "count": 1},
        ],
    },
    {
        "id": "aldruhn",
        "why": ["vvardenfell_ashlands.L1.ash_plain", "vvardenfell_ashlands.L1.crab_shell_rock", "vvardenfell_ashlands.L2.ash_storm_track"],
        "sil": "shell-spine-fort",
        "overlay": "aldruhn",
        "extra_overlays": ["vvardenfell_ashlands"],
        "radius": 96,
        "reach": 114,
        "landmark": ("shell", [0, -24], "aldruhn_under_skar", "Manor District Under-Skar"),
        "gates": [[0, 90], [-90, 0], [90, 0]],
        "buildings": [
            ("manor", "Sarethi Manor", "ar_sarethi_manor"),
            ("manor", "Venim Manor", "ar_venim_manor"),
            ("manor", "Arobar Manor", "ar_arobar_manor"),
            ("manor", "Llethri Manor", "ar_llethri_manor"),
            ("manor", "Ramoran Manor", "ar_ramoran_manor"),
            ("hall", "Redoran Council Hall", "ar_redoran_council"),
            ("temple", "Ald-ruhn Tribunal Temple", "ar_tribunal_temple"),
            ("guild", "Ald-ruhn Fighters Guild", "ar_fighters_guild"),
            ("guild", "Ald-ruhn Mages Guild", "ar_mages_guild"),
            ("inn", "Ald Skar Inn", "ar_ald_skar_inn"),
            ("inn", "The Rat in the Pot", "ar_rat_in_the_pot"),
            ("smith", "Dandera Selaro's Smithy", "ar_dandera_smithy"),
            ("alchemist", "Cienne Sintieve's Alchemist", "ar_cienne_alchemist"),
            ("bookseller", "Codus Callonus's Books", "ar_codus_books"),
            ("market", "Tiras Sadus's General Goods", "ar_tiras_trader"),
            ("tailor", "Bivale Teneran's Clothier", "ar_bivale_clothier"),
            ("house", "Gildan's Shell House", "ar_gildans_house"),
        ],
        "features": [
            {"type": "banners", "theme": "house_redoran_scarab", "count": 4},
            {"type": "lamps", "theme": "ash_wind_cresset", "count": 4},
            {"type": "shrine", "theme": "tribunal_alcove", "count": 2},
            {"type": "well", "theme": "ash_cistern", "count": 2},
        ],
    },
    {
        "id": "sadrith_mora",
        "why": ["zafirbel_bay.L1.bay_islands", "zafirbel_bay.L4.telvanni_mushroom_forest", "zafirbel_bay.L5.enchanting_and_spells"],
        "sil": "mushroom-spore-cluster",
        "overlay": "sadrith_mora",
        "extra_overlays": ["telvannis"],
        "radius": 96,
        "reach": 114,
        "landmark": ("mushroom_spire", [0, -18], "sadrith_mora_tel_naga", "Tel Naga Great Tower"),
        "extra_landmarks": [("castle", [36, 36], "sadrith_mora_wolverine_hall", "Wolverine Hall Imperial Fort")],
        "gates": [[0, 90], [-90, 0]],
        "buildings": [
            ("hall", "Telvanni Council House", "sm_telvanni_council_house"),
            ("inn", "Gateway Inn", "sm_gateway_inn"),
            ("inn", "Fara's Hole in the Wall", "sm_faras_hole"),
            ("inn", "Dirty Muriel's Cornerclub", "sm_dirty_muriels"),
            ("guild", "Wolverine Hall Mages Guild", "sm_mages_guild"),
            ("guild", "Wolverine Hall Fighters Guild", "sm_fighters_guild"),
            ("temple", "Wolverine Hall Imperial Shrine", "sm_imperial_shrine"),
            ("alchemist", "Pierlette Rostorard's Apothecary", "sm_pierlette_apothecary"),
            ("alchemist", "Threvul Serethi's Healer Pod", "sm_threvul_healer"),
            ("bookseller", "Urtiso Faryon's Sorcerer Shop", "sm_urtiso_sorcerer"),
            ("smith", "Llaalam Madalas's Mage-Smithy", "sm_llaalam_smithy"),
            ("market", "Anis Seloth's Alchemist & Goods", "sm_anis_seloth_shop"),
            ("manor", "Therana's Envoy Pod", "sm_therana_pod"),
            ("house", "Nevrasa Dralor's Pod House", "sm_nevrasa_house"),
            ("house", "Meluria Seleth's Root House", "sm_meluria_house"),
            ("house", "Trendrus Dral's Spore House", "sm_trendrus_house"),
        ],
        "features": [
            {"type": "great_tree", "theme": "emperor_parasol", "count": 3},
            {"type": "lamps", "theme": "biolume_spore_pod", "count": 4},
            {"type": "banners", "theme": "house_telvanni_mycelium", "count": 3},
            {"type": "pond", "theme": "zafirbel_tide_pool", "count": 2},
        ],
    },
    {
        "id": "ebonheart",
        "why": ["stonefalls.L3.inner_sea_shore", "morrowind.L6.great_house_seats", "morrowind.L9.imperial_occupation"],
        "sil": "star-fort-bridge",
        "overlay": "ebonheart",
        "radius": 96,
        "reach": 114,
        "landmark": ("castle", [0, 0], "ebonheart_grand_council", "Grand Council Chambers of Castle Ebonheart"),
        "gates": [[0, -90], [90, 0], [-90, 0]],
        "buildings": [
            ("temple", "Imperial Commission Chapel", "eb_imperial_chapel"),
            ("hall", "Skyrim Mission Hall", "eb_skyrim_mission"),
            ("hall", "Argonian Mission Hall", "eb_argonian_mission"),
            ("barracks", "Hawkmoth Legion Garrison", "eb_hawkmoth_garrison"),
            ("inn", "Six Fishes", "eb_six_fishes"),
            ("warehouse", "East Empire Company Warehouse", "eb_eec_warehouse"),
            ("smith", "Legion Quartermaster Forge", "eb_legion_forge"),
            ("alchemist", "Imperial Cult Apothecary", "eb_cult_apothecary"),
            ("market", "Inner Sea Customs House", "eb_customs_house"),
            ("manor", "Duke Vedam Dren's Residence", "eb_duke_dren_residence"),
            ("manor", "Consulate of High Rock", "eb_high_rock_consulate"),
            ("guardhouse", "Bridgehead Gate Bastion", "eb_bridge_bastion"),
            ("stable", "Courier Post Stables", "eb_courier_stables"),
            ("house", "Factor's Harbour Quarters", "eb_factors_quarters"),
            ("house", "Shipwright's Stone House", "eb_shipwright_house"),
            ("house", "Chancery Clerk's Quarters", "eb_clerk_quarters"),
        ],
        "features": [
            {"type": "statue", "theme": "imperial_dragon", "count": 2},
            {"type": "banners", "theme": "imperial_legion", "count": 4},
            {"type": "lamps", "theme": "bastion_iron_lantern", "count": 4},
            {"type": "fountain", "theme": "castle_courtyard", "count": 1},
        ],
    },
    {
        "id": "necrom",
        "why": ["necrom.L1.tomb_hills", "necrom.L4.tomb_terraces", "necrom.L5.funeral_trade"],
        "sil": "necropolis-tomb-grid",
        "overlay": "necrom",
        "radius": 96,
        "reach": 114,
        "landmark": ("dome_temple", [0, -32], "necrom_cathedral_of_ancestors", "Cathedral of the Ancestors"),
        "gates": [[-90, 0], [0, 90]],
        "buildings": [
            ("temple", "Hall of Bone-Binders", "nc_hall_of_bone_binders"),
            ("temple", "Cloister of St. Olms", "nc_cloister_st_olms"),
            ("hall", "Indoril Prelate's Chancery", "nc_indoril_chancery"),
            ("inn", "The Pilgrim's Repose", "nc_pilgrims_repose"),
            ("inn", "Lantern of the East", "nc_lantern_of_the_east"),
            ("alchemist", "Myrrh & Ash Embalmers", "nc_myrrh_and_ash"),
            ("bookseller", "Ossuary Rolls Scriptorium", "nc_ossuary_scriptorium"),
            ("smith", "Ordinator Ceremonial Forge", "nc_ordinator_forge"),
            ("market", "Votive Candle & Relic Provisioner", "nc_votive_provisioner"),
            ("manor", "House Indoril Mausoleum Manor", "nc_indoril_manor"),
            ("manor", "Prelate Sathil's Residence", "nc_sathil_residence"),
            ("barracks", "Necrom Watch-Ordinator Hall", "nc_ordinator_barracks"),
            ("house", "Keeper of the Urns Quarters", "nc_urn_keeper_house"),
            ("house", "Lamplighter's White House", "nc_lamplighter_house"),
            ("house", "Mourner's Terrace House", "nc_mourner_house"),
            ("warehouse", "Funeral Barge Store", "nc_barge_store"),
        ],
        "features": [
            {"type": "memorial", "theme": "velothi_ancestor_cenotaph", "count": 4},
            {"type": "shrine", "theme": "saint_veloth", "count": 3},
            {"type": "lamps", "theme": "white_incense_brazier", "count": 4},
            {"type": "fountain", "theme": "purification_basin", "count": 1},
        ],
    },
    {
        "id": "tear",
        "why": ["deshaan.L3.lagoon_fens", "deshaan.L4.paddy_round_tear", "morrowind.L6.great_house_seats"],
        "sil": "dres-canal-compound",
        "overlay": "tear",
        "radius": 84,
        "reach": 102,
        "landmark": ("xanmeer", [0, -28], "tear_dres_citadel", "Grand Stockade of House Dres"),
        "gates": [[0, -78], [-78, 0], [0, 78]],
        "buildings": [
            ("hall", "Dres Council Compound", "tr_dres_council"),
            ("temple", "Tear Tribunal Temple", "tr_tribunal_temple"),
            ("inn", "The Saltrice Barge Inn", "tr_saltrice_barge_inn"),
            ("inn", "The Marsh-Chain Cornerclub", "tr_marsh_chain_club"),
            ("market", "Southern Plantation Exchange", "tr_plantation_exchange"),
            ("smith", "Thorn-Border Ironworks", "tr_thorn_ironworks"),
            ("alchemist", "Bog-Reed Apothecary", "tr_bog_apothecary"),
            ("manor", "Grandmaster Tholer Dres Manor", "tr_tholer_dres_manor"),
            ("manor", "Ulvur Plantation Townhouse", "tr_ulvur_townhouse"),
            ("barracks", "Dres Slaver-Watch Barracks", "tr_slaver_barracks"),
            ("warehouse", "Saltrice Granary Vault", "tr_saltrice_granary"),
            ("mill", "Lagoon Tidal Mill", "tr_lagoon_mill"),
            ("house", "Canal-Factor's House", "tr_canal_factor_house"),
            ("house", "Overseer's Stone House", "tr_overseer_house"),
            ("shack", "Argonian Paddy-Worker Shanty", "tr_paddy_shanty_1"),
            ("shack", "Reed-Weir Shanty", "tr_paddy_shanty_2"),
        ],
        "features": [
            {"type": "pond", "theme": "saltrice_canal", "count": 3},
            {"type": "banners", "theme": "house_dres_chains", "count": 3},
            {"type": "lamps", "theme": "marsh_oil_cresset", "count": 3},
            {"type": "market", "theme": "grain_wharf", "count": 1},
        ],
    },
    {
        "id": "blacklight",
        "why": ["stonefalls.L1.velothi_foothills", "morrowind.L6.great_house_seats", "morrowind.L7.five_great_houses"],
        "sil": "redoran-fjord-shell-wall",
        "overlay": "blacklight",
        "radius": 84,
        "reach": 102,
        "landmark": ("shell", [0, -28], "blacklight_redoran_seat", "Archcanon's Spire & Redoran High Seat"),
        "gates": [[-78, 0], [0, 78], [78, 0]],
        "buildings": [
            ("hall", "Grand Council of Blacklight", "bl_grand_council"),
            ("temple", "Cathedral of St. Meris", "bl_cathedral_st_meris"),
            ("guild", "Blacklight Fighters Guild", "bl_fighters_guild"),
            ("guild", "Blacklight Mages Guild", "bl_mages_guild"),
            ("inn", "The Velothi Pass Inn", "bl_velothi_pass_inn"),
            ("inn", "The Chitin Helm Cornerclub", "bl_chitin_helm_club"),
            ("smith", "Dunmeth Pass Armorer", "bl_dunmeth_armorer"),
            ("market", "Fjord-Wharf Outfitter", "bl_fjord_outfitter"),
            ("alchemist", "Ash-Root Remedies", "bl_ash_root_remedies"),
            ("manor", "Archmaster Bolvyn's Ancestral Hall", "bl_bolvyn_hall"),
            ("manor", "Sarethi Mainland Manor", "bl_sarethi_mainland"),
            ("barracks", "Redoran Border-Watch Citadel", "bl_border_barracks"),
            ("warehouse", "Sea of Ghosts Salt-Store", "bl_salt_store"),
            ("house", "Pass-Warden's Shell House", "bl_warden_house"),
            ("house", "Harpooner's Stone House", "bl_harpooner_house"),
            ("house", "Mason's Terrace House", "bl_mason_house"),
        ],
        "features": [
            {"type": "banners", "theme": "blacklight_redoran", "count": 4},
            {"type": "lamps", "theme": "fjord_beacon_brazier", "count": 4},
            {"type": "statue", "theme": "saint_reddoth", "count": 1},
            {"type": "well", "theme": "snowmelt_cistern", "count": 2},
        ],
    },
    {
        "id": "firewatch",
        "why": ["telvannis.L1.peninsula_cliffs", "telvannis.L5.sea_trade", "morrowind.L9.imperial_occupation"],
        "sil": "imperial-telvanni-port-fort",
        "overlay": "firewatch",
        "radius": 84,
        "reach": 102,
        "landmark": ("castle", [0, -28], "firewatch_citadel_keep", "Imperial Citadel of Firewatch"),
        "gates": [[0, 78], [-78, 0], [78, 0]],
        "buildings": [
            ("temple", "Imperial Chapel of Mara & Zenithar", "fw_imperial_chapel"),
            ("guild", "Firewatch Mages Guild Charterhouse", "fw_mages_guild"),
            ("guild", "Firewatch Fighters Guild", "fw_fighters_guild"),
            ("inn", "The Spore-Gale Tavern", "fw_spore_gale_tavern"),
            ("inn", "The Emperor's Anchor", "fw_emperors_anchor"),
            ("market", "East Empire Telvannis Factor", "fw_eec_factor"),
            ("smith", "Peninsula Legion Forge", "fw_legion_forge"),
            ("alchemist", "Mycelium & Mortar Apothecary", "fw_mycelium_apothecary"),
            ("bookseller", "Imperial Antiquary Office", "fw_antiquary_office"),
            ("manor", "Governor's Cliff Manor", "fw_governors_manor"),
            ("mage_tower", "Telvanni Envoy Pod", "fw_telvanni_envoy_pod"),
            ("barracks", "Firewatch Sea-Wall Garrison", "fw_seawall_garrison"),
            ("tower", "Telvannis Beacon Lighthouse", "fw_beacon_lighthouse"),
            ("warehouse", "Harbour Customs Vault", "fw_customs_vault"),
            ("house", "Harbourmaster's Stone House", "fw_harbourmaster_house"),
            ("house", "Pilot's Cliff Cottage", "fw_pilot_cottage"),
        ],
        "features": [
            {"type": "banners", "theme": "firewatch_legion", "count": 3},
            {"type": "lamps", "theme": "harbour_oil_lamp", "count": 4},
            {"type": "fountain", "theme": "charter_square", "count": 2},
            {"type": "well", "theme": "cliff_spring_well", "count": 2},
        ],
    },
]

# 5. 11 Towns (place.type == "town", civic features 4..8)
TOWNS = [
    ("raven_rock", ["raven_rock.L1.bay_headland", "raven_rock.L1.mine_shaft", "solstheim.L1.ebony_seam"], "bulwark-mining-colony", 72, "raven_rock", "headframe", "Raven Rock Ebony Mine", [
        ("manor", "Morvayn Manor"), ("temple", "Raven Rock Temple"), ("inn", "The Retching Netch"), ("smith", "Glover Mallory's Smithy"),
        ("alchemist", "Milore Ienth's Apothecary"), ("market", "Fethis Alor's Stall"), ("barracks", "Redoran Guard Bulwark"), ("manor", "Severin Manor"),
        ("house", "Caerellius House"), ("house", "Alor House"), ("house", "Ienth Farmstead"), ("warehouse", "East Empire Dock Warehouse")
    ], [{"type": "lamps", "theme": "bulwark_brazier", "count": 3}, {"type": "banners", "theme": "redoran_solstheim", "count": 2}, {"type": "shrine", "theme": "reclamations_shrine", "count": 1}]),
    ("molag_mar", ["molag_amur.L1.lava_field", "molag_amur.L3.pilgrim_cisterns"], "cliff-grotto-lavafall", 52, "molag_amur", "canton", "Molag Mar Fortress Canton", [
        ("temple", "Molag Mar Pilgrim Temple"), ("inn", "The Pilgrim's Rest"), ("smith", "Armigers' Stronghold Forge"), ("market", "Vasesius Viciulus's Trader"),
        ("alchemist", "Saras Orelu's Healer Hall"), ("barracks", "Buoyant Armigers Garrison"), ("stable", "Silt Strider & Boat Landing")
    ], [{"type": "banners", "theme": "buoyant_armigers", "count": 3}, {"type": "lamps", "theme": "molag_lava_brazier", "count": 2}, {"type": "shrine", "theme": "mt_kand_pilgrim", "count": 1}]),
    ("gnisis", ["vvardenfell_ashlands.L1.ash_plain", "vvardenfell_ashlands.L1.velothi_strongholds"], "eggmine-legion-garrison", 52, "vvardenfell_ashlands", "castle", "Fort Darius", [
        ("temple", "Gnisis Temple (Ashmask Shrine)"), ("inn", "Madach Tradehouse"), ("manor", "Arvs-Drelen Tower"), ("smith", "Legion Garrison Smithy"),
        ("market", "hetman_abelmawia_hut"), ("barracks", "Talos Cult Barracks"), ("warehouse", "Eggmine Kwama Depot")
    ], [{"type": "banners", "theme": "fort_darius_legion", "count": 2}, {"type": "well", "theme": "gnisis_river_well", "count": 2}, {"type": "shrine", "theme": "justice_stone", "count": 1}]),
    ("caldera", ["west_gash.L1.ebony_seam", "west_gash.L1.gash_ridges"], "imperial-tudor-mining-keep", 46, "west_gash", "castle", "Governor's Hall", [
        ("guild", "Caldera Mages Guild"), ("inn", "Shenk's Shovel"), ("manor", "Ghorak Manor"), ("smith", "Hodlismod's Armory"),
        ("market", "Verick Gemain's Trader"), ("tailor", "Falanaamo's Clothier"), ("barracks", "Caldera Mining Guardhouse")
    ], [{"type": "banners", "theme": "caldera_charter", "count": 2}, {"type": "lamps", "theme": "caldera_iron_lamp", "count": 2}, {"type": "well", "theme": "caldera_square_well", "count": 1}]),
    ("suran", ["ascadian_isles.L1.lake_basins", "ascadian_isles.L4.saltrice_paddy"], "lakeside-hlaalu-terraces", 52, "ascadian_isles", "dome_temple", "Suran Temple", [
        ("inn", "Desele's House of Earthly Delights"), ("inn", "Suran Tradehouse"), ("manor", "Oran Manor"), ("smith", "Garothmuk gro-Muzgub's Smithy"),
        ("market", "Goldyn Belaram's Pawnbroker"), ("alchemist", "Ranosa Gilvayn's Outfitter"), ("barracks", "Suran Watch Tower")
    ], [{"type": "lamps", "theme": "suran_lake_lantern", "count": 3}, {"type": "banners", "theme": "suran_hlaalu", "count": 2}, {"type": "fountain", "theme": "masque_plaza", "count": 1}]),
    ("port_telvannis", ["telvannis.L1.peninsula_cliffs", "telvannis.L5.sea_trade"], "giant-cephalopod-harbour", 52, "telvannis", "mushroom_spire", "Grand Archmagister's Spire", [
        ("hall", "Telvannis Parliament Pod"), ("inn", "The Floating Carapace"), ("market", "Cephalopod Spice Exchange"), ("alchemist", "Spore-Master's Alembic"),
        ("bookseller", "Archmagister's Grimoire Hall"), ("smith", "Chitin & Enchanted Edge"), ("warehouse", "Zafirbel Sea-Wharf")
    ], [{"type": "great_tree", "theme": "telvannis_giant_cap", "count": 2}, {"type": "lamps", "theme": "port_telvannis_spore", "count": 3}, {"type": "pond", "theme": "harbour_lagoon", "count": 1}]),
    ("gorne", ["telvannis.L1.fungal_forest_soils", "telvannis.L5.sea_trade"], "emerald-islet-manor-ring", 52, "telvannis", "dome_palace", "Indoril-Gorne Island Manor", [
        ("temple", "Chapel of the Emerald Bay"), ("inn", "The Quiet Cove Inn"), ("market", "Gorne Spice & Silk Hall"), ("alchemist", "Island Herbary"),
        ("manor", "Lady Llaren's Summer Estate"), ("house", "Ferryman's Stone Cottage"), ("warehouse", "Bay Pier Store")
    ], [{"type": "garden", "theme": "gorne_terraces", "count": 3}, {"type": "lamps", "theme": "gorne_lantern", "count": 2}, {"type": "fountain", "theme": "emerald_basin", "count": 1}]),
    ("silgrad_tower", ["stonefalls.L1.velothi_foothills", "stonefalls.L2.snow_on_the_foothills"], "ash-canyon-spire-keep", 52, "stonefalls", "castle", "Silgrad High Spire", [
        ("hall", "Redoran-Hlaalu Border Chancery"), ("inn", "The Sulfur Pass Inn"), ("smith", "Velothi Ore-Foundry"), ("market", "Pass-Caravan Exchange"),
        ("alchemist", "Cinder-Spring Apothecary"), ("barracks", "Silgrad Canyon Watch"), ("stable", "Guar Caravan Corral")
    ], [{"type": "banners", "theme": "silgrad_border", "count": 3}, {"type": "lamps", "theme": "canyon_brazier", "count": 2}, {"type": "well", "theme": "sulfur_cistern", "count": 1}]),
    ("davons_watch", ["stonefalls.L1.lava_shields", "stonefalls.L3.inner_sea_shore"], "volcanic-bluff-sea-gate", 52, "stonefalls", "castle", "Davon's Watch Citadel", [
        ("temple", "Davon's Watch Tribunal Shrine"), ("guild", "Davon's Watch Mages Guild"), ("guild", "Davon's Watch Fighters Guild"), ("inn", "The Watchman's Flagon"),
        ("smith", "Basalt-Anvil Forge"), ("market", "Inner Sea Quay Market"), ("manor", "House Indoril Harbour Manor")
    ], [{"type": "banners", "theme": "davons_watch_indoril", "count": 3}, {"type": "lamps", "theme": "volcanic_bluff_cresset", "count": 3}]),
    ("kragenmoor", ["stonefalls.L1.lava_shields", "stonefalls.L4.basalt_field"], "hlaalu-obsidian-gate-city", 52, "stonefalls", "dome_palace", "Grandmaster Omalen's House", [
        ("temple", "Kragenmoor Temple"), ("inn", "The Obsidian Jug"), ("smith", "West-Pass Ironworks"), ("market", "Cheydinhal-Road Exchange"),
        ("alchemist", "Lava-Tube Distillers"), ("manor", "Dren Border Manor"), ("barracks", "Kragenmoor Gate Garrison")
    ], [{"type": "banners", "theme": "kragenmoor_hlaalu", "count": 2}, {"type": "fountain", "theme": "obsidian_basin", "count": 2}, {"type": "lamps", "theme": "kragenmoor_lantern", "count": 2}]),
    ("narsis", ["deshaan.L1.fungal_plains", "deshaan.L5.temple_economy"], "hlaalu-council-river-plaza", 52, "narsis", "dome_palace", "Narsis Hlaalu Council Hall", [
        ("temple", "Shrine of Saint Nerevar"), ("inn", "The Golden Scale Inn"), ("market", "Narsis Grand Counting-House"), ("smith", "Hlaalu Charter Smithy"),
        ("bookseller", "Mainland Ledger Archives"), ("manor", "Sadras-Hlaalu Villa"), ("barracks", "Narsis River-Watch")
    ], [{"type": "banners", "theme": "narsis_capital_scales", "count": 3}, {"type": "fountain", "theme": "nerevar_plaza", "count": 2}, {"type": "garden", "theme": "narsis_courtyard", "count": 2}]),
]

# 6. 22 Villages, Camps, Towers & Strongholds (place.type == "village" or "fort", civic features 2..4)
VILLAGES = [
    ("skaal_village", ["skaal.L1.east_shore", "skaal.L3.lake_fjalding_shore"], "yurt-ring-totem", 42, "skaal_village", ["skaal", "solstheim"], [
        ("longhouse", "The Greathall of the Skaal"), ("temple", "Shaman Storn's Hut"), ("smith", "Baldret's Nordic Forge"), ("house", "Wulf Wild-Blood's Lodge"), ("house", "Morwen's Hunter Cabin")
    ], [{"type": "shrine", "theme": "all_maker_wind_stone", "count": 1}, {"type": "lamps", "theme": "skaal_hearth_brazier", "count": 2}]),
    ("thirsk_mead_hall", ["felsaad.L1.mortrag_glacier", "skaal.L2.lake_ice"], "single-long-hall-fort", 28, "thirsk", [], [
        ("longhouse", "Thirsk Mead Hall"), ("smith", "Fjalding Shore Forge"), ("barn", "Horker Meat Smokehouse"), ("house", "Chieftain's Mead Loft")
    ], [{"type": "memorial", "theme": "hrothmunds_axe_barrows", "count": 1}, {"type": "lamps", "theme": "mead_hall_torch", "count": 2}]),
    ("tel_mithryn", ["isinfier.L1.ash_shelf_plain", "solstheim.L1.ash_plain"], "lone-fungal-tower", 38, "tel_mithryn", [], [
        ("mage_tower", "Tel Mithryn Central Tower"), ("alchemist", "Elynea Mothren's Apothecary Pod"), ("inn", "Varona's Steward Pod"), ("smith", "Talvas's Staff Enchanter Pod")
    ], [{"type": "great_tree", "theme": "tel_mithryn_fungus", "count": 2}, {"type": "lamps", "theme": "ash_spore_lantern", "count": 1}]),
    ("maar_gan", ["vvardenfell_ashlands.L1.ash_plain", "vvardenfell_ashlands.L2.ash_storm_track"], "boulder-shrine-outpost", 40, "vvardenfell_ashlands", [], [
        ("temple", "Maar Gan Shrine (The Taunt Rock)"), ("inn", "Andus Tradehouse"), ("market", "Anruin's Outfitter"), ("barracks", "Redoran Watch Tower"), ("house", "Huleen's Hut")
    ], [{"type": "shrine", "theme": "maar_gan_boulder", "count": 1}, {"type": "banners", "theme": "maar_gan_redoran", "count": 2}]),
    ("pelagiad", ["ascadian_isles.L1.fertile_lowland", "morrowind.L9.imperial_occupation"], "imperial-legion-crossroads-fort", 40, "ascadian_isles", [], [
        ("barracks", "Fort Pelagiad Keep"), ("inn", "Halfway Tavern"), ("market", "Mebestian Ence's Trader"), ("smith", "Sadhil's Legion Smithy"), ("house", "Ahnassi's House")
    ], [{"type": "well", "theme": "pelagiad_green_well", "count": 1}, {"type": "banners", "theme": "pelagiad_legion", "count": 2}]),
    ("seyda_neen", ["bitter_coast.L1.mud_coast", "morrowind.L9.imperial_occupation"], "census-lighthouse-marsh-port", 38, "bitter_coast", [], [
        ("hall", "Census and Excise Office"), ("inn", "Arrille's Tradehouse"), ("tower", "Seyda Neen Lighthouse"), ("shack", "Fargoth's House"), ("shack", "Vodunius Nuccius's House")
    ], [{"type": "lamps", "theme": "seyda_neen_lighthouse_lamp", "count": 2}, {"type": "well", "theme": "excise_courtyard_well", "count": 1}]),
    ("tel_branora", ["azuras_coast.L1.islet_maze", "azuras_coast.L1.sea_cliffs"], "southern-archipelago-pod-tower", 38, "azuras_coast", [], [
        ("mage_tower", "Upper Tower of Mistress Therana"), ("inn", "Sethan's Tradehouse"), ("alchemist", "Ferele Athram's Apothecary"), ("smith", "Galos Farethi's Armorer"), ("house", "Fedris Tharen's Healer Pod")
    ], [{"type": "great_tree", "theme": "tel_branora_stalk", "count": 2}, {"type": "lamps", "theme": "branora_spore_lamp", "count": 1}]),
    ("tel_aruhn", ["azuras_coast.L1.islet_maze", "zafirbel_bay.L5.slave_labour"], "slave-market-mushroom-spire", 38, "azuras_coast", [], [
        ("mage_tower", "Archmagister Gothren's Tower"), ("inn", "Plot and Plaster"), ("alchemist", "Bildren Areleth's Apothecary"), ("market", "Ferele's Underground Market"), ("smith", "Maren Uvaren's Enchanter")
    ], [{"type": "great_tree", "theme": "tel_aruhn_cap", "count": 1}, {"type": "market", "theme": "tel_aruhn_pen_stalls", "count": 2}]),
    ("tel_mora", ["grazelands.L1.grass_slopes", "azuras_coast.L1.islet_maze"], "cove-root-bridge-tower", 38, "grazelands", [], [
        ("mage_tower", "Mistress Dratha's Tower"), ("inn", "The Covenant"), ("alchemist", "Jolda's Apothecary"), ("smith", "Elegnan's Clothier & Goods"), ("house", "Radras's Smithy Pod")
    ], [{"type": "great_tree", "theme": "tel_mora_bridge_root", "count": 2}, {"type": "pond", "theme": "tel_mora_cove", "count": 1}]),
    ("vos", ["grazelands.L1.grass_slopes", "grazelands.L4.wickwheat_steppe"], "velothi-farming-bluff-terraces", 40, "grazelands", [], [
        ("temple", "Vos Chapel"), ("inn", "varo_tradehouse"), ("house", "Ulvil Llothas's Farmhouse"), ("house", "Mandyn Ralas's Cottage"), ("warehouse", "Vos Harbour Dock-Shed")
    ], [{"type": "well", "theme": "vos_bluff_well", "count": 2}, {"type": "banners", "theme": "vos_velothi", "count": 1}]),
    ("tel_vos", ["grazelands.L1.grass_slopes", "morrowind.L6.great_house_seats"], "imperial-fort-fungal-hybrid", 38, "grazelands", [], [
        ("mage_tower", "Master Aryon's Central Tower"), ("barracks", "Tel Vos Southern Keep"), ("market", "Tel Vos Tradehouse"), ("temple", "Tel Vos Imperial Shrine"), ("guild", "Aryon's Museum")
    ], [{"type": "great_tree", "theme": "tel_vos_overgrown_bastion", "count": 2}, {"type": "banners", "theme": "aryon_telvanni", "count": 2}]),
    ("dagon_fel", ["sheogorad.L1.wave_cut_islets", "sheogorad.L2.cold_sea_storms"], "nordic-dwemer-island-harbour", 38, "sheogorad", [], [
        ("mage_tower", "Sorkvild's Dwemer Watchtower"), ("inn", "The End of the World"), ("market", "Heifnir's Trader"), ("house", "Hurg's Nordic Longhouse"), ("house", "Onmi Hard-Mouth's Shack")
    ], [{"type": "lamps", "theme": "dagon_fel_nord_torch", "count": 2}, {"type": "well", "theme": "sheogorad_rain_barrel", "count": 1}]),
    ("gnaar_mok", ["bitter_coast.L1.mud_coast", "bitter_coast.L3.brackish_inlets"], "marsh-stilt-smuggler-docks", 38, "bitter_coast", [], [
        ("manor", "Arenim Manor"), ("inn", "Druegh-Jigger's Rest"), ("shack", "Nadene Rotheran's Shack"), ("shack", "Caryarel's Stilted Hut"), ("warehouse", "Netch-Leather Curing Shed")
    ], [{"type": "pond", "theme": "gnaar_mok_bayou", "count": 2}, {"type": "lamps", "theme": "smuggler_pier_lantern", "count": 1}]),
    ("hla_oad", ["bitter_coast.L1.mud_coast", "bitter_coast.L4.bitter_swamp"], "fetid-swamp-footbridge-shanties", 38, "bitter_coast", [], [
        ("inn", "Fat Leg's Drop Off"), ("shack", "Murudius Flaeus's House"), ("shack", "Relien Rirne's Shanty"), ("shack", "Shurkul gro-Sharga's Hut"), ("warehouse", "Swamp-Ferry Pier Shed")
    ], [{"type": "pond", "theme": "hla_oad_slough", "count": 1}, {"type": "lamps", "theme": "swamp_rope_lantern", "count": 2}]),
    ("khuul", ["west_gash.L1.gash_ridges", "sheogorad.L2.cold_sea_storms"], "northern-ash-fishing-pier", 38, "west_gash", [], [
        ("inn", "Thongar's Tradehouse"), ("shack", "Aldos Braven's Shack"), ("shack", "Endris Dilmyn's Hut"), ("shack", "Svadstar's Boat-Shed"), ("warehouse", "Solstheim Ferry Pier")
    ], [{"type": "well", "theme": "khuul_shore_well", "count": 1}, {"type": "lamps", "theme": "khuul_jetty_lamp", "count": 2}]),
    ("ald_velothi", ["west_gash.L1.gash_ridges", "vvardenfell_ashlands.L1.velothi_strongholds"], "redoran-cliff-outpost-dock", 26, "west_gash", [], [
        ("barracks", "Ald Velothi Outpost"), ("shack", "Lledsea Relas's Shack"), ("shack", "Vireveri Darethi's Hut"), ("shack", "banden_indarys_pier")
    ], [{"type": "banners", "theme": "ald_velothi_outpost", "count": 2}, {"type": "lamps", "theme": "cliff_dock_cresset", "count": 1}]),
    ("tel_fyr", ["azuras_coast.L1.islet_maze", "zafirbel_bay.L5.enchanting_and_spells"], "corprusarium-island-root-hall", 38, "azuras_coast", [], [
        ("mage_tower", "Hall of Fyr"), ("hall", "Corprusarium Sub-Chamber"), ("house", "Onyx Closet"), ("warehouse", "Tel Fyr Boat Landing")
    ], [{"type": "great_tree", "theme": "divayth_fyr_spire", "count": 1}, {"type": "lamps", "theme": "corprusarium_ward_light", "count": 2}]),
    ("ghostgate", ["red_mountain.L1.foyadas", "red_mountain.L2.blight_storms"], "ghostfence-twin-tower-gate", 24, "red_mountain", [], [
        ("barracks", "Tower of Dawn"), ("barracks", "Tower of Dusk"), ("temple", "Ghostgate Pilgrim Shrine"), ("smith", "Buoyant Armiger Armory")
    ], [{"type": "shrine", "theme": "ghostfence_pylon", "count": 2}, {"type": "banners", "theme": "tribunal_ghostgate", "count": 2}]),
    ("urshilaku_camp", ["vvardenfell_ashlands.L1.ash_plain", "vvardenfell_ashlands.L2.ash_storm_track"], "ash-dune-yurt-circle", 40, "vvardenfell_ashlands", [], [
        ("tent", "Ashkhan Sul-Matuul's Yurt"), ("tent", "Nibani Maesa's Wise Woman Yurt"), ("tent", "Zabamund's Gulakhan Yurt"), ("tent", "Kurapli's Hunter Yurt"), ("stable", "Urshilaku Guar Pen")
    ], [{"type": "banners", "theme": "urshilaku_bone_chimes", "count": 2}, {"type": "lamps", "theme": "ashlander_firepit", "count": 1}]),
    ("ahemmusa_camp", ["grazelands.L1.grass_slopes", "grazelands.L4.guar_commons"], "headland-wise-woman-yurts", 40, "grazelands", [], [
        ("tent", "Sinnammu Mirpal's Wise Woman Yurt"), ("tent", "Kausi's Yurt"), ("tent", "Dutadalk's Yurt"), ("tent", "Yenammu's Yurt"), ("stable", "Ahemmusa Guar Kraal")
    ], [{"type": "banners", "theme": "ahemmusa_wind_fetish", "count": 2}, {"type": "shrine", "theme": "ald_daedroth_waypost", "count": 1}]),
    ("zainab_camp", ["grazelands.L1.grass_slopes", "grazelands.L4.wickwheat_steppe"], "steppe-guar-herd-yurts", 40, "grazelands", [], [
        ("tent", "Ashkhan Kaushad's Yurt"), ("tent", "Sonummu Zabamat's Wise Woman Yurt"), ("tent", "Ashibaal's Gulakhan Yurt"), ("tent", "Ababael Timsar-Dadisun's Trader Yurt"), ("stable", "Zainab Herd Corral")
    ], [{"type": "banners", "theme": "zainab_herd_standard", "count": 2}, {"type": "well", "theme": "grazelands_spring", "count": 1}]),
    ("erabenimsun_camp", ["molag_amur.L1.cinder_cones", "molag_amur.L2.ash_and_heat"], "cinder-slope-war-yurts", 40, "molag_amur", [], [
        ("tent", "Ashkhan Ulath-Pal's Yurt"), ("tent", "Manirai's Wise Woman Yurt"), ("tent", "Ahaz's Gulakhan Yurt"), ("tent", "Han-Ammu's Yurt"), ("stable", "Erabenimsun War-Guar Pen")
    ], [{"type": "banners", "theme": "erabenimsun_war_totem", "count": 2}, {"type": "lamps", "theme": "cinder_brazier", "count": 2}]),
]

city_plans_out = {}

for c in MAJOR_CITIES:
    pid = c["id"]
    b_raw = c["buildings"]
    pts = ring_positions(len(b_raw), 28.0, min(c["radius"] - 18.0, 68.0))
    lm_id, lm_pt, lm_key, lm_label = c["landmark"]
    titles = {lm_key: lm_label}
    landmarks = [{"id": lm_id, "at": lm_pt, "face": "south", "name": lm_key, "overlay": c["overlay"]}]
    for el_id, el_pt, el_key, el_label in c.get("extra_landmarks", []):
        titles[el_key] = el_label
        landmarks.append({"id": el_id, "at": el_pt, "face": "south", "name": el_key, "overlay": c["overlay"]})

    b_list = []
    inn_key = None
    store_key = None
    smith_key = None
    for idx, (ukey, label, tkey) in enumerate(b_raw):
        kind, use, wealth = USE_MAP[ukey]
        titles[tkey] = label
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
            "at": pts[idx],
            "face": "south",
            "overlay": c["overlay"],
            "name": tkey,
        })
    quarters = [{"ring": [0, c["radius"]], "overlay": c["overlay"]}]
    for eo in c.get("extra_overlays", []):
        quarters.append({"ring": [int(c["radius"] * 0.5), c["radius"]], "overlay": eo})
    verify_unique_sig(pid, c["features"])
    city_plans_out[pid] = {
        "id": pid,
        "why": c["why"],
        "sil": c["sil"],
        "base": "none",
        "overlay": c["overlay"],
        "radius": c["radius"],
        "reach": c["reach"],
        "innKey": inn_key,
        "names": {"inn": inn_key, "store": store_key, "smithy": smith_key},
        "gates": c["gates"],
        "landmarks": landmarks,
        "quarters": quarters,
        "buildings": b_list,
        "features": c["features"],
        "titles": titles,
    }

for pid, why, sil, rad, ovl, lm_id, lm_label, b_specs, feats in TOWNS:
    verify_unique_sig(pid, feats)
    pts = ring_positions(len(b_specs), 14.0, rad - 10.0)
    lm_key = f"{pid}_landmark"
    titles = {lm_key: lm_label}
    b_list = []
    inn_key = None
    store_key = None
    smith_key = None
    for idx, (ukey, label) in enumerate(b_specs):
        kind, use, wealth = USE_MAP[ukey]
        tkey = f"{pid}_b_{idx+1}"
        titles[tkey] = label if not label.islower() else label.replace("_", " ").title()
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
            "at": pts[idx],
            "face": "south",
            "overlay": ovl,
            "name": tkey,
        })
    tplan = {
        "id": pid,
        "why": why,
        "sil": sil,
        "base": "none",
        "overlay": ovl,
        "radius": rad,
        "reach": rad + 16,
        "gates": [[0, rad - 4], [rad - 4, 0]],
        "landmarks": [{"id": lm_id, "at": [0, 0], "face": "south", "name": lm_key, "overlay": ovl}],
        "quarters": [{"ring": [0, rad], "overlay": ovl}],
        "buildings": b_list,
        "features": feats,
        "titles": titles,
    }
    if inn_key:
        tplan["innKey"] = inn_key
        tplan["names"] = {k: v for k, v in [("inn", inn_key), ("store", store_key), ("smithy", smith_key)] if v}
    city_plans_out[pid] = tplan

for pid, why, sil, rad, ovl, extra_ovls, b_specs, feats in VILLAGES:
    verify_unique_sig(pid, feats)
    pts = ring_positions(len(b_specs), 9.0, rad - 8.0)
    titles = {}
    b_list = []
    inn_key = None
    for idx, (ukey, label) in enumerate(b_specs):
        kind, use, wealth = USE_MAP[ukey]
        tkey = f"{pid}_b_{idx+1}"
        titles[tkey] = label if not label.islower() else label.replace("_", " ").title()
        if use == "inn" and not inn_key:
            inn_key = tkey
        b_list.append({
            "kind": kind,
            "use": use,
            "wealth": wealth,
            "at": pts[idx],
            "face": "south",
            "overlay": ovl,
            "name": tkey,
        })
    quarters = [{"ring": [0, rad], "overlay": ovl}]
    for eo in extra_ovls:
        quarters.append({"ring": [int(rad * 0.5), rad], "overlay": eo})
    vplan = {
        "id": pid,
        "why": why,
        "sil": sil,
        "base": "none",
        "overlay": ovl,
        "radius": rad,
        "reach": rad + 12,
        "gates": [[0, rad - 2]],
        "quarters": quarters,
        "buildings": b_list,
        "features": feats,
        "titles": titles,
    }
    if inn_key:
        vplan["innKey"] = inn_key
        vplan["names"] = {"inn": inn_key}
    city_plans_out[pid] = vplan

for pid, pdata in city_plans_out.items():
    with open(os.path.join(CITYPLAN_DIR, f"{pid}.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(pdata, f, indent=2)
        f.write("\n")

plans_set = set(index_data["plans"]) | set(city_plans_out.keys())
overlays_set = set(index_data["overlays"]) | set(OVERLAY_SPECS.keys())
index_data["plans"] = sorted(plans_set)
index_data["overlays"] = sorted(overlays_set)
with open(INDEX_PATH, "w", encoding="utf-8", newline="\n") as f:
    json.dump(index_data, f, indent=2)
    f.write("\n")

print(f"Wrote styles/velothi.json, {len(OVERLAY_SPECS)} Morrowind/Solstheim overlays, and {len(city_plans_out)} bespoke cityplans.")
