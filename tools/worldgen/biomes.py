#!/usr/bin/env python3
"""The biomes of Nirn: one declarative catalogue that generates every Skycraft biome and what hangs off it.

Writes (all generated, never hand-edit the outputs):
  * mod/src/main/resources/data/skycraft/worldgen/biome/*.json
  * mod/src/main/resources/data/skycraft/worldgen/{configured,placed}_feature/biome/*.json (the biome features)
  * mod/src/main/resources/data/{skycraft,minecraft,forge,c}/tags/worldgen/biome/** (biome tags)
  * mod/src/main/resources/skycraft/tamriel/palettes.json (region -> terrain context -> weighted biomes)
  * mod/src/main/lang/skycraft/tamriel_biomes.json (biome names; block names come from tools/textures/tamriel.py)
  * tools/worldgen/generated/surface_rule.json (the complete overworld surface rule; the terrain workstream
    inlines it into the skycraft:tamriel noise settings)

Feature order: every biome's feature lists are filtered from ONE canonical ordered list per generation step
(vanilla's own order with Skycraft's features slotted in), so a "feature order cycle" cannot happen.

Run from anywhere:  python3 tools/worldgen/biomes.py
"""
import json
import os
import shutil
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
DATA = os.path.join(RES, "data")
SK = "skycraft"

STEPS = ["raw_generation", "lakes", "local_modifications", "underground_structures", "surface_structures",
         "strongholds", "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration",
         "top_layer_modification"]

# Vanilla 1.20.1's feature order per step: a linear extension of every vanilla overworld biome's lists (dumped
# from the game's registries). Skycraft features are slotted into these lists below; vanilla features keep
# their relative order, so vanilla cave biomes (lush/dripstone/deep dark) can share a world with ours.
VANILLA_ORDER = {
    "raw_generation": [],
    "lakes": ["minecraft:lake_lava_underground", "minecraft:lake_lava_surface"],
    "local_modifications": ["minecraft:iceberg_packed", "minecraft:iceberg_blue", "minecraft:amethyst_geode",
                            "minecraft:large_dripstone", "minecraft:forest_rock"],
    "underground_structures": ["minecraft:fossil_upper", "minecraft:fossil_lower", "minecraft:monster_room",
                               "minecraft:monster_room_deep"],
    "surface_structures": ["minecraft:blue_ice", "minecraft:desert_well", "minecraft:ice_spike", "minecraft:ice_patch"],
    "strongholds": [],
    "underground_ores": ["minecraft:ore_dirt", "minecraft:ore_gravel", "minecraft:ore_granite_upper",
                         "minecraft:ore_granite_lower", "minecraft:ore_diorite_upper", "minecraft:ore_diorite_lower",
                         "minecraft:ore_andesite_upper", "minecraft:ore_andesite_lower", "minecraft:ore_tuff",
                         "minecraft:ore_coal_upper", "minecraft:ore_coal_lower", "minecraft:ore_iron_upper",
                         "minecraft:ore_iron_middle", "minecraft:ore_iron_small", "minecraft:ore_gold",
                         "minecraft:ore_gold_lower", "minecraft:ore_redstone", "minecraft:ore_redstone_lower",
                         "minecraft:ore_diamond", "minecraft:ore_diamond_large", "minecraft:ore_diamond_buried",
                         "minecraft:ore_lapis", "minecraft:ore_lapis_buried", "minecraft:ore_copper",
                         "minecraft:ore_copper_large", "minecraft:underwater_magma", "minecraft:ore_gold_extra",
                         "minecraft:ore_clay", "minecraft:disk_sand", "minecraft:disk_grass", "minecraft:disk_clay",
                         "minecraft:disk_gravel", "minecraft:ore_emerald"],
    "underground_decoration": ["minecraft:ore_infested", "minecraft:sculk_vein", "minecraft:sculk_patch_deep_dark",
                               "minecraft:dripstone_cluster", "minecraft:pointed_dripstone"],
    "fluid_springs": ["minecraft:spring_water", "minecraft:spring_lava", "minecraft:spring_lava_frozen"],
    "vegetal_decoration": [
        "minecraft:glow_lichen", "minecraft:bamboo", "minecraft:bamboo_vegetation", "minecraft:patch_tall_grass_2",
        "minecraft:trees_water", "minecraft:dark_forest_vegetation", "minecraft:forest_flowers", "minecraft:trees_birch",
        "minecraft:flower_forest_flowers", "minecraft:trees_flower_forest", "minecraft:flower_flower_forest",
        "minecraft:trees_birch_and_oak", "minecraft:trees_grove", "minecraft:trees_snowy", "minecraft:bamboo_light",
        "minecraft:trees_jungle", "minecraft:lush_caves_ceiling_vegetation", "minecraft:cave_vines",
        "minecraft:lush_caves_clay", "minecraft:lush_caves_vegetation", "minecraft:rooted_azalea_tree",
        "minecraft:spore_blossom", "minecraft:classic_vines_cave_feature", "minecraft:trees_mangrove",
        "minecraft:mushroom_island_vegetation", "minecraft:birch_tall", "minecraft:patch_large_fern",
        "minecraft:trees_old_growth_pine_taiga", "minecraft:trees_old_growth_spruce_taiga", "minecraft:patch_tall_grass",
        "minecraft:trees_savanna", "minecraft:trees_taiga", "minecraft:trees_sparse_jungle", "minecraft:flower_warm",
        "minecraft:patch_grass_jungle", "minecraft:patch_grass_savanna", "minecraft:patch_sunflower",
        "minecraft:trees_plains", "minecraft:flower_plains", "minecraft:patch_grass_plain", "minecraft:flower_cherry",
        "minecraft:trees_cherry", "minecraft:flower_meadow", "minecraft:trees_meadow", "minecraft:trees_swamp",
        "minecraft:flower_swamp", "minecraft:trees_windswept_forest", "minecraft:trees_windswept_hills",
        "minecraft:trees_windswept_savanna", "minecraft:flower_default", "minecraft:patch_grass_forest",
        "minecraft:patch_grass_normal", "minecraft:patch_grass_taiga", "minecraft:patch_dead_bush",
        "minecraft:patch_waterlily", "minecraft:brown_mushroom_old_growth", "minecraft:red_mushroom_old_growth",
        "minecraft:patch_grass_taiga_2", "minecraft:brown_mushroom_taiga", "minecraft:red_mushroom_taiga",
        "minecraft:brown_mushroom_swamp", "minecraft:red_mushroom_swamp", "minecraft:trees_badlands",
        "minecraft:patch_grass_badlands", "minecraft:patch_dead_bush_badlands", "minecraft:patch_dead_bush_2",
        "minecraft:brown_mushroom_normal", "minecraft:red_mushroom_normal", "minecraft:patch_sugar_cane_badlands",
        "minecraft:patch_sugar_cane", "minecraft:patch_sugar_cane_desert", "minecraft:patch_sugar_cane_swamp",
        "minecraft:patch_pumpkin", "minecraft:patch_cactus_decorated", "minecraft:vines", "minecraft:patch_melon",
        "minecraft:seagrass_cold", "minecraft:seagrass_deep_cold", "minecraft:seagrass_deep_warm",
        "minecraft:seagrass_deep", "minecraft:patch_cactus_desert", "minecraft:seagrass_swamp",
        "minecraft:seagrass_normal", "minecraft:seagrass_simple", "minecraft:kelp_cold", "minecraft:patch_berry_common",
        "minecraft:seagrass_river", "minecraft:patch_berry_rare", "minecraft:patch_melon_sparse",
        "minecraft:warm_ocean_vegetation", "minecraft:seagrass_warm", "minecraft:kelp_warm", "minecraft:sea_pickle"],
    "top_layer_modification": ["minecraft:freeze_top_layer"],
}

# Skycraft features already added by the Phase 1 biome modifiers (data/skycraft/forge/biome_modifier). They must
# never appear in a biome's own lists too, or the feature would be listed twice in one biome.
MODIFIER_FEATURES = {"skycraft:nirnroot_patch", "skycraft:deathbell_patch", "skycraft:nightshade_patch",
                     "skycraft:frost_mirriam_patch", "skycraft:bandit_camp", "skycraft:wayside_shrine",
                     "skycraft:standing_stone_shrine", "skycraft:word_wall_shrine",
                     "skycraft:word_wall_shrine_highlands"}

MAX = 1.7976931348623157e308


# ====================================================================================================== helpers
def st(name, **props):
    s = {"Name": name if ":" in name else "minecraft:" + name}
    if props:
        s["Properties"] = {k: str(v).lower() if isinstance(v, bool) else str(v) for k, v in props.items()}
    return s


def simple(name, **props):
    return {"type": "minecraft:simple_state_provider", "state": st(name, **props)}


def weighted(*pairs):
    """pairs: (state_dict_or_name, weight)"""
    return {"type": "minecraft:weighted_state_provider",
            "entries": [{"data": s if isinstance(s, dict) else st(s), "weight": w} for s, w in pairs]}


def uniform(lo, hi):
    return {"type": "minecraft:uniform", "value": {"min_inclusive": lo, "max_inclusive": hi}}


def leaves(name):
    return st(name, distance=7, persistent=False, waterlogged=False)


def log(name, axis="y"):
    return st(name, axis=axis)


# placement modifiers
def count(n):
    return {"type": "minecraft:count", "count": n}


def count_extra(base, extra=1, weight=9):
    return {"type": "minecraft:count", "count": {"type": "minecraft:weighted_list", "distribution": [
        {"data": base, "weight": weight}, {"data": base + extra, "weight": 1}]}}


def rarity(n):
    return {"type": "minecraft:rarity_filter", "chance": n}


SQUARE = {"type": "minecraft:in_square"}
BIOME = {"type": "minecraft:biome"}


def hm(kind):
    return {"type": "minecraft:heightmap", "heightmap": kind}


def water_depth(n):
    return {"type": "minecraft:surface_water_depth_filter", "max_water_depth": n}


def would_survive(state):
    return {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:would_survive", "state": state}}


def height_range(lo, hi):
    def anchor(v):
        if isinstance(v, dict):
            return v
        return {"absolute": v}
    return {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": anchor(lo),
                                                         "max_inclusive": anchor(hi)}}


def scan(direction="down", steps=16):
    return {"type": "minecraft:environment_scan", "direction_of_search": direction, "max_steps": steps,
            "target_condition": {"type": "minecraft:solid"},
            "allowed_search_condition": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}}


def offset_y(n):
    return {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": n}


def surface_rel_below(n):
    """Only positions at least n blocks below the surface (keeps cave features out of the open air)."""
    return {"type": "minecraft:surface_relative_threshold_filter", "heightmap": "OCEAN_FLOOR_WG", "max_inclusive": -n}


AIR = {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}


def near_water():
    offs = [(1, -1, 0), (-1, -1, 0), (0, -1, 1), (0, -1, -1), (2, -1, 0), (-2, -1, 0), (0, -1, 2), (0, -1, -2)]
    return {"type": "minecraft:all_of", "predicates": [AIR, {"type": "minecraft:any_of", "predicates": [
        {"type": "minecraft:matching_fluids", "offset": list(o), "fluids": ["minecraft:water"]} for o in offs]}]}


# ============================================================================================ feature library
CF = {}           # "biome/x" -> configured feature json
PF = {}           # "biome/x" -> placed feature json
MINE = {s: [] for s in STEPS}   # Skycraft placed features per step, in canonical order


def ref(name):
    return name if ":" in name else SK + ":biome/" + name


def configured(name, ftype, config):
    CF[name] = {"type": ftype if ":" in ftype else "minecraft:" + ftype, "config": config}
    return ref(name)


def placed(name, feature, placement, step=None):
    """A placed feature; with a step it joins that step's canonical list (it can be listed in biomes)."""
    PF[name] = {"feature": ref(feature), "placement": placement}
    if step:
        assert name not in MINE[step]
        MINE[step].append(ref(name))
    return ref(name)


def checked(name, cf, sapling):
    return placed(name + "_checked", cf, [would_survive(st(sapling, stage=0))])


def two_layers(limit=1, lower=0, upper=1):
    return {"type": "minecraft:two_layers_feature_size", "limit": limit, "lower_size": lower, "upper_size": upper}


def tree(name, trunk, foliage, log_state, leaves_state, size=None, decorators=None, ignore_vines=True,
         dirt="minecraft:dirt"):
    return configured(name, "tree", {
        "decorators": decorators or [], "ignore_vines": ignore_vines, "force_dirt": False,
        "dirt_provider": simple(dirt), "minimum_size": size or two_layers(),
        "foliage_provider": {"type": "minecraft:simple_state_provider", "state": leaves_state},
        "foliage_placer": foliage,
        "trunk_provider": {"type": "minecraft:simple_state_provider", "state": log_state},
        "trunk_placer": trunk})


def straight(base, a, b):
    return {"type": "minecraft:straight_trunk_placer", "base_height": base, "height_rand_a": a, "height_rand_b": b}


def trunk(kind, base, a, b):
    return {"type": "minecraft:" + kind, "base_height": base, "height_rand_a": a, "height_rand_b": b}


def foliage(kind, radius, offset, **extra):
    d = {"type": "minecraft:" + kind, "radius": radius, "offset": offset}
    d.update(extra)
    return d


def selector(name, default, *choices):
    """Random tree mix: choices are (checked_placed_ref, chance)."""
    return configured(name, "random_selector", {
        "features": [{"feature": c, "chance": ch} for c, ch in choices], "default": default})


def trees_placed(name, cf, base, extra=1, step="vegetal_decoration", chance=None):
    mods = [count_extra(base, extra)] if base > 0 else [rarity(chance)]
    return placed(name, cf, mods + [SQUARE, water_depth(0), hm("OCEAN_FLOOR"), BIOME], step)


def patch(name, provider, tries=32, xz=7, y=3, predicate=None):
    return configured(name, "random_patch", {
        "tries": tries, "xz_spread": xz, "y_spread": y,
        "feature": {"feature": {"type": "minecraft:simple_block", "config": {"to_place": provider}},
                    "placement": [{"type": "minecraft:block_predicate_filter", "predicate": predicate or AIR}]}})


def patch_placed(name, cf, n=None, chance=None, heightmap="WORLD_SURFACE_WG", step="vegetal_decoration"):
    mods = []
    if n is not None:
        mods.append(count(n))
    if chance is not None:
        mods.append(rarity(chance))
    return placed(name, cf, mods + [SQUARE, hm(heightmap), BIOME], step)


def ore(name, targets, size, air_discard=0.0):
    """targets: list of (tag_or_block, state). A '#tag' becomes a tag_match rule, a block a block_match rule."""
    rules = []
    for t, s in targets:
        if t.startswith("#"):
            rules.append({"target": {"predicate_type": "minecraft:tag_match", "tag": t[1:]}, "state": s})
        else:
            rules.append({"target": {"predicate_type": "minecraft:block_match", "block": t}, "state": s})
    return configured(name, "ore", {"size": size, "discard_chance_on_air_exposure": air_discard, "targets": rules})


def ore_placed(name, cf, n, lo, hi, step="underground_ores"):
    return placed(name, cf, [count(n), SQUARE, height_range(lo, hi), BIOME], step)


STONE_ORE = "#minecraft:stone_ore_replaceables"
DEEP_ORE = "#minecraft:deepslate_ore_replaceables"
BASE_STONE = "#minecraft:base_stone_overworld"


def cave_floor_placed(name, cf, n, lo=-56, hi=60, step="vegetal_decoration", steps=12):
    return placed(name, cf, [count(n), SQUARE, height_range(lo, hi), scan("down", steps), offset_y(1),
                             surface_rel_below(12), BIOME], step)


# ---------------------------------------------------------------------------------------- the lakes step
# skycraft:map_water belongs to the terrain workstream (lakes and rivers from the Tamriel map); every land biome
# lists it first in the lakes step.
MAP_WATER = "skycraft:map_water"
MINE["lakes"].append(MAP_WATER)

configured("hot_spring", SK + ":hot_spring", {})
placed("hot_springs_dense", "hot_spring", [rarity(2), SQUARE, hm("WORLD_SURFACE_WG"), BIOME], "lakes")
placed("hot_springs_sparse", "hot_spring", [rarity(14), SQUARE, hm("WORLD_SURFACE_WG"), BIOME], "lakes")

# --------------------------------------------------------------------------------- local modifications
configured("lava_vent", SK + ":lava_vent", {})
placed("lava_vents_dense", "lava_vent", [rarity(3), SQUARE, hm("WORLD_SURFACE_WG"), BIOME], "local_modifications")
placed("lava_vents_sparse", "lava_vent", [rarity(18), SQUARE, hm("WORLD_SURFACE_WG"), BIOME], "local_modifications")


def boulder(name, provider, radius, chance=None, n=None):
    cf = configured("boulder_" + name, SK + ":boulder", {"state": provider, "radius": radius})
    mods = ([count(n)] if n else []) + ([rarity(chance)] if chance else [])
    return placed("boulders_" + name + ("_%d" % (chance or n)), cf, mods + [SQUARE, hm("MOTION_BLOCKING"), BIOME],
                  "local_modifications")


ROCK_MOSSY = weighted(("mossy_cobblestone", 5), ("cobblestone", 2), ("stone", 2), ("moss_block", 1))
ROCK_GREY = weighted(("stone", 6), ("cobblestone", 3), ("andesite", 2), ("gravel", 1))
ROCK_TUNDRA = weighted(("stone", 5), ("andesite", 3), ("cobblestone", 2), ("mossy_cobblestone", 1), ("tuff", 1))
ROCK_REACH = weighted(("stone", 4), ("granite", 3), ("cobblestone", 2), ("mossy_cobblestone", 1))
ROCK_VOLCANIC = weighted((st(SK + ":volcanic_rock"), 6), (st(SK + ":cracked_volcanic_rock"), 2), ("basalt", 1), ("blackstone", 1))
ROCK_SAND = weighted(("sandstone", 5), ("smooth_sandstone", 1), ("terracotta", 1))
ROCK_RED = weighted(("red_sandstone", 4), ("terracotta", 3), ("orange_terracotta", 2), ("granite", 1))
ROCK_ICE = weighted(("packed_ice", 5), ("blue_ice", 1), ("snow_block", 2), (st(SK + ":frosted_stone"), 3))
ROCK_CORAL = weighted((st(SK + ":coral_stone"), 6), ("dead_brain_coral_block", 1), ("dead_tube_coral_block", 1))
ROCK_CALCITE = weighted(("calcite", 5), ("diorite", 2), ("smooth_basalt", 1))
ROCK_DARK = weighted(("deepslate", 3), ("cobbled_deepslate", 2), ("mossy_cobblestone", 3), ("tuff", 2))
ROCK_FROST = weighted(("stone", 4), (st(SK + ":frosted_stone"), 4), ("snow_block", 2), ("andesite", 1))
boulder("mossy", ROCK_MOSSY, uniform(1, 2), chance=3)
boulder("mossy", ROCK_MOSSY, uniform(1, 2), chance=8)
boulder("grey", ROCK_GREY, uniform(1, 3), chance=4)
boulder("tundra", ROCK_TUNDRA, uniform(1, 3), chance=3)
boulder("tundra", ROCK_TUNDRA, uniform(1, 2), chance=9)
boulder("reach", ROCK_REACH, uniform(1, 3), n=1)
boulder("volcanic", ROCK_VOLCANIC, uniform(1, 3), chance=3)
boulder("sand", ROCK_SAND, uniform(1, 2), chance=8)
boulder("red", ROCK_RED, uniform(1, 3), chance=4)
boulder("ice", ROCK_ICE, uniform(1, 3), chance=4)
boulder("coral", ROCK_CORAL, uniform(1, 2), chance=4)
boulder("calcite", ROCK_CALCITE, uniform(1, 2), chance=3)
boulder("dark", ROCK_DARK, uniform(1, 2), chance=4)
boulder("frost", ROCK_FROST, uniform(1, 3), chance=4)


def spires(name, body, tip, n, height, spread, lean, chance):
    cf = configured("spires_" + name, SK + ":spire_cluster",
                    {"body": body, "tip": tip, "count": n, "height": height, "spread": spread, "lean": lean})
    return placed("spires_" + name, cf, [rarity(chance), SQUARE, hm("WORLD_SURFACE_WG"), BIOME], "local_modifications")


CRYSTAL_TIP = simple(SK + ":crystal_cluster", facing="up", waterlogged=False)
spires("crystal_small", simple(SK + ":crystal_block"), CRYSTAL_TIP, uniform(2, 5), uniform(2, 6), 4, 0.25, 6)
spires("crystal_large", weighted((st(SK + ":crystal_block"), 5), ("calcite", 1)), CRYSTAL_TIP, uniform(3, 7),
       uniform(5, 14), 6, 0.3, 2)
spires("coral", weighted((st(SK + ":coral_stone"), 5), ("dead_brain_coral_block", 1), ("dead_fire_coral_block", 1),
                         ("dead_horn_coral_block", 1)),
       weighted(("dead_brain_coral", 1), ("dead_fire_coral", 1), ("dead_tube_coral_fan", 1), ("dead_horn_coral", 1)),
       uniform(2, 6), uniform(2, 7), 5, 0.2, 3)
spires("basalt", weighted(("basalt", 3), (st(SK + ":volcanic_rock"), 2)), simple(SK + ":magma_volcanic_rock"),
       uniform(2, 5), uniform(3, 9), 4, 0.1, 5)
spires("ice", weighted(("packed_ice", 4), ("blue_ice", 1)), simple("packed_ice"), uniform(2, 4), uniform(4, 12), 4, 0.15, 4)

# ------------------------------------------------------------------------------------------ underground ores
ore("packed_ice_cave", [(BASE_STONE, st("packed_ice"))], 48)
ore_placed("cave_packed_ice", "packed_ice_cave", 24, -40, 90)
ore("blue_ice_cave", [(BASE_STONE, st("blue_ice"))], 24)
ore_placed("cave_blue_ice", "blue_ice_cave", 6, -40, 90)
ore("snow_cave", [(BASE_STONE, st("snow_block"))], 32)
ore_placed("cave_snow", "snow_cave", 10, -40, 90)
ore("magma_cave", [(BASE_STONE, st(SK + ":magma_volcanic_rock"))], 24)
ore_placed("cave_magma", "magma_cave", 12, -60, 80)
ore("volcanic_cave", [(BASE_STONE, st(SK + ":volcanic_rock"))], 56)
ore_placed("cave_volcanic_rock", "volcanic_cave", 20, -60, 120)
ore("basalt_cave", [(BASE_STONE, st("basalt", axis="y"))], 40)
ore_placed("cave_basalt", "basalt_cave", 10, -60, 80)
ore("blackstone_cave", [(BASE_STONE, st("blackstone"))], 40)
ore_placed("cave_blackstone", "blackstone_cave", 8, -60, 80)
ore("dwemer_copper", [(STONE_ORE, st("copper_ore")), (DEEP_ORE, st("deepslate_copper_ore"))], 16)
ore_placed("cave_dwemer_copper", "dwemer_copper", 24, -60, 80)
ore("dwemer_tuff", [(BASE_STONE, st("tuff"))], 48)
ore_placed("cave_tuff", "dwemer_tuff", 12, -60, 80)
ore("moss_cave", [(BASE_STONE, st("moss_block"))], 32)
ore_placed("cave_moss", "moss_cave", 10, -60, 80)
ore("calcite_cave", [(BASE_STONE, st("calcite"))], 40)
ore_placed("cave_calcite", "calcite_cave", 8, -60, 120)
# Vvardenfell and Red Mountain are volcanic all the way down: big blobs of volcanic rock near the surface
ore("volcanic_ground", [(BASE_STONE, st(SK + ":volcanic_rock"))], 64)
ore_placed("ground_volcanic_rock", "volcanic_ground", 14, 20, 200)
ore("frosted_ground", [(BASE_STONE, st(SK + ":frosted_stone"))], 48)
ore_placed("ground_frosted_stone", "frosted_ground", 10, 60, 320)
ore("coral_ground", [(BASE_STONE, st(SK + ":coral_stone"))], 56)
ore_placed("ground_coral_stone", "coral_ground", 16, 0, 120)

# -------------------------------------------------------------------------------- underground decoration
configured("ice_formation", SK + ":ice_formation", {})
cave_floor_placed("ice_formations", "ice_formation", 40, step="underground_decoration")
configured("dwemer_pipes", SK + ":dwemer_pipes", {})
cave_floor_placed("dwemer_pipes", "dwemer_pipes", 10, step="underground_decoration")

# ------------------------------------------------------------------------------------------- fluid springs
placed("spring_lava_dense", "minecraft:spring_lava_overworld",
       [count(40), SQUARE, height_range(-60, 100), BIOME], "fluid_springs")
placed("spring_water_dense", "minecraft:spring_water",
       [count(40), SQUARE, height_range(40, 300), BIOME], "fluid_springs")

# ================================================================================================ vegetation
# Order inside vegetal_decoration: giants, trees, dead trees, logs, bushes, tall plants, flowers, grass, ground
# cover, water plants, cave flora.
V = "vegetal_decoration"

# --- giants (Java features)
configured("graht_oak", SK + ":graht_oak", {})
placed("graht_oaks_dense", "graht_oak", [rarity(2), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)
placed("graht_oaks_sparse", "graht_oak", [rarity(7), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)
configured("hist_tree", SK + ":hist_tree", {})
placed("hist_trees_dense", "hist_tree", [rarity(2), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)
placed("hist_trees_sparse", "hist_tree", [rarity(9), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)


def giant_mushroom(name, stem, cap, style, height):
    return configured(name, SK + ":giant_mushroom", {"stem": st(stem, axis="y"), "cap": st(cap), "style": style,
                                                     "height": height})


giant_mushroom("emperor_parasol", SK + ":parasol_stem", SK + ":parasol_cap", "parasol", uniform(10, 20))
giant_mushroom("telvanni_tower", SK + ":parasol_stem", SK + ":telvanni_cap", "tower", uniform(18, 32))
giant_mushroom("glowing_mushroom", SK + ":parasol_stem", SK + ":glowing_mushroom_cap", "cave", uniform(5, 16))
giant_mushroom("grotto_mushroom", SK + ":parasol_stem", SK + ":parasol_cap", "cave", uniform(4, 12))
placed("telvanni_towers", "telvanni_tower", [rarity(2), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)
placed("emperor_parasols_dense", "emperor_parasol", [count(2), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)
placed("emperor_parasols_sparse", "emperor_parasol", [rarity(4), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)
placed("emperor_parasols_rare", "emperor_parasol", [rarity(12), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)
configured("palm_tree", SK + ":palm_tree", {})
placed("palms_dense", "palm_tree", [count_extra(2, 1), SQUARE, water_depth(0), hm("OCEAN_FLOOR"), BIOME], V)
placed("palms_sparse", "palm_tree", [rarity(3), SQUARE, water_depth(0), hm("OCEAN_FLOOR"), BIOME], V)
placed("palms_rare", "palm_tree", [rarity(10), SQUARE, water_depth(0), hm("OCEAN_FLOOR"), BIOME], V)

# --- trees (vanilla tree feature with Skycraft and vanilla blocks)
SPRUCE_LOG = log("spruce_log")
tree("tall_spruce", straight(11, 4, 3),
     foliage("spruce_foliage_placer", uniform(2, 3), uniform(0, 1), trunk_height=uniform(4, 7)),
     SPRUCE_LOG, leaves("spruce_leaves"), two_layers(2, 0, 2))
tree("tall_pine", straight(14, 4, 3), foliage("pine_foliage_placer", uniform(1, 2), 1, height=uniform(5, 8)),
     SPRUCE_LOG, leaves("spruce_leaves"), two_layers(2, 0, 2))
tree("giant_spruce", trunk("giant_trunk_placer", 18, 4, 10),
     foliage("mega_pine_foliage_placer", 0, 0, crown_height=uniform(10, 16)),
     SPRUCE_LOG, leaves("spruce_leaves"), two_layers(1, 1, 2),
     decorators=[{"type": "minecraft:alter_ground", "provider": simple("podzol", snowy=False)}], ignore_vines=False)
tree("snowy_spruce", straight(8, 3, 2),
     foliage("spruce_foliage_placer", uniform(2, 3), uniform(0, 1), trunk_height=uniform(2, 4)),
     SPRUCE_LOG, leaves(SK + ":snowy_spruce_leaves"), two_layers(2, 0, 2))
tree("snowy_pine", straight(12, 4, 2), foliage("pine_foliage_placer", uniform(1, 2), 1, height=uniform(4, 7)),
     SPRUCE_LOG, leaves(SK + ":snowy_spruce_leaves"), two_layers(2, 0, 2))
tree("giant_snowy_spruce", trunk("giant_trunk_placer", 16, 4, 8),
     foliage("mega_pine_foliage_placer", 0, 0, crown_height=uniform(8, 14)),
     SPRUCE_LOG, leaves(SK + ":snowy_spruce_leaves"), two_layers(1, 1, 2), ignore_vines=False)
for color in ("golden", "orange"):
    tree(color + "_aspen", straight(8, 3, 2), foliage("blob_foliage_placer", 2, 0, height=3),
         log("birch_log"), leaves(SK + ":%s_aspen_leaves" % color))
    tree("tall_%s_aspen" % color, straight(12, 4, 2), foliage("blob_foliage_placer", uniform(2, 3), 0, height=4),
         log("birch_log"), leaves(SK + ":%s_aspen_leaves" % color))
tree("reach_shrub", straight(1, 0, 0), foliage("bush_foliage_placer", 2, 1, height=2),
     log("spruce_log"), leaves("oak_leaves"), two_layers(0, 0, 0))
tree("juniper", straight(2, 1, 0), foliage("bush_foliage_placer", uniform(1, 2), 1, height=2),
     log("spruce_log"), leaves("spruce_leaves"), two_layers(0, 0, 0))
tree("crooked_oak", trunk("forking_trunk_placer", 4, 2, 2), foliage("blob_foliage_placer", 2, 0, height=2),
     log("oak_log"), leaves("oak_leaves"), two_layers(1, 0, 2))
tree("cypress", straight(9, 4, 2), foliage("spruce_foliage_placer", 1, 0, trunk_height=uniform(1, 2)),
     log("oak_log"), leaves("dark_oak_leaves"), two_layers(2, 0, 2))
tree("olive", trunk("bending_trunk_placer", 3, 2, 1) | {"min_height_for_leaves": 3, "bend_length": uniform(1, 2)},
     foliage("random_spread_foliage_placer", 3, 0, foliage_height=2, leaf_placement_attempts=60),
     log("oak_log"), leaves("azalea_leaves"), two_layers(1, 0, 1))
tree("young_graht_oak", trunk("dark_oak_trunk_placer", 8, 3, 1), foliage("dark_oak_foliage_placer", 0, 0),
     log(SK + ":graht_oak_log"), leaves(SK + ":graht_oak_leaves"),
     {"type": "minecraft:three_layers_feature_size", "limit": 1, "upper_limit": 1, "lower_size": 0,
      "middle_size": 1, "upper_size": 2})
tree("swamp_hist", trunk("bending_trunk_placer", 5, 2, 1) | {"min_height_for_leaves": 4, "bend_length": uniform(1, 3)},
     foliage("random_spread_foliage_placer", 3, 0, foliage_height=3, leaf_placement_attempts=90),
     log(SK + ":hist_log"), leaves(SK + ":hist_leaves"), two_layers(1, 0, 1),
     decorators=[{"type": "minecraft:leave_vine", "probability": 0.35}], ignore_vines=False)
tree("ash_scrub", straight(2, 1, 0), foliage("bush_foliage_placer", 1, 0, height=1),
     log("dark_oak_log"), leaves(SK + ":orange_aspen_leaves"), two_layers(0, 0, 0))
tree("west_gash_pine", trunk("bending_trunk_placer", 6, 3, 1) | {"min_height_for_leaves": 4, "bend_length": uniform(1, 2)},
     foliage("pine_foliage_placer", 1, 1, height=uniform(3, 4)), SPRUCE_LOG, leaves("spruce_leaves"), two_layers(1, 0, 1))
tree("summerset_gold", trunk("fancy_trunk_placer", 6, 5, 0),
     foliage("fancy_foliage_placer", 2, 4, height=4), log("birch_log"), leaves(SK + ":golden_aspen_leaves"),
     {"type": "minecraft:two_layers_feature_size", "limit": 0, "lower_size": 0, "upper_size": 0,
      "min_clipped_height": 4})
tree("baobab", trunk("forking_trunk_placer", 6, 2, 3), foliage("acacia_foliage_placer", 3, 0),
     log("acacia_log"), leaves("acacia_leaves"), two_layers(1, 0, 2))
tree("akaviri_pine", trunk("cherry_trunk_placer", 7, 1, 0) | {
    "branch_count": {"type": "minecraft:weighted_list", "distribution": [{"data": 1, "weight": 1}, {"data": 2, "weight": 1}, {"data": 3, "weight": 1}]},
    "branch_horizontal_length": uniform(2, 4), "branch_start_offset_from_top": {"min_inclusive": -4, "max_inclusive": -3},
    "branch_end_offset_from_top": uniform(-1, 0)},
     foliage("random_spread_foliage_placer", 3, 0, foliage_height=2, leaf_placement_attempts=60),
     SPRUCE_LOG, leaves("spruce_leaves"), two_layers(1, 0, 2))

# checked variants (sapling survival) and the mixes
OAKS = "oak_sapling"
c_tall_spruce = checked("tall_spruce", "tall_spruce", "spruce_sapling")
c_tall_pine = checked("tall_pine", "tall_pine", "spruce_sapling")
c_giant_spruce = checked("giant_spruce", "giant_spruce", "spruce_sapling")
c_snowy_spruce = checked("snowy_spruce", "snowy_spruce", "spruce_sapling")
c_snowy_pine = checked("snowy_pine", "snowy_pine", "spruce_sapling")
c_giant_snowy = checked("giant_snowy_spruce", "giant_snowy_spruce", "spruce_sapling")
c_golden = checked("golden_aspen", "golden_aspen", "birch_sapling")
c_orange = checked("orange_aspen", "orange_aspen", "birch_sapling")
c_tall_golden = checked("tall_golden_aspen", "tall_golden_aspen", "birch_sapling")
c_tall_orange = checked("tall_orange_aspen", "tall_orange_aspen", "birch_sapling")
c_reach_shrub = checked("reach_shrub", "reach_shrub", OAKS)
c_juniper = checked("juniper", "juniper", OAKS)
c_crooked = checked("crooked_oak", "crooked_oak", OAKS)
c_cypress = checked("cypress", "cypress", OAKS)
c_olive = checked("olive", "olive", OAKS)
c_young_graht = checked("young_graht_oak", "young_graht_oak", OAKS)
c_swamp_hist = checked("swamp_hist", "swamp_hist", "mangrove_propagule")
c_ash_scrub = checked("ash_scrub", "ash_scrub", "dead_bush")
c_gash_pine = checked("west_gash_pine", "west_gash_pine", "spruce_sapling")
c_summerset_gold = checked("summerset_gold", "summerset_gold", OAKS)
c_baobab = checked("baobab", "baobab", "acacia_sapling")
c_akaviri_pine = checked("akaviri_pine", "akaviri_pine", "spruce_sapling")
OAK = "minecraft:oak_checked"
FANCY = "minecraft:fancy_oak_checked"
BIRCH = "minecraft:birch_checked"
DARK_OAK = "minecraft:dark_oak_checked"
SPRUCE = "minecraft:spruce_checked"
PINE = "minecraft:pine_checked"
MEGA_SPRUCE = "minecraft:mega_spruce_checked"
MEGA_PINE = "minecraft:mega_pine_checked"
ACACIA = "minecraft:acacia_checked"
JUNGLE = "minecraft:jungle_tree"
MEGA_JUNGLE = "minecraft:mega_jungle_tree_checked"
JUNGLE_BUSH = "minecraft:jungle_bush"
MANGROVE = "minecraft:mangrove_checked"
TALL_MANGROVE = "minecraft:tall_mangrove_checked"
CHERRY = "minecraft:cherry_checked"
c_swamp_oak = placed("swamp_oak_checked", "minecraft:swamp_oak", [would_survive(st(OAKS, stage=0))])
c_red_mushroom = placed("huge_red_mushroom_any", "minecraft:huge_red_mushroom", [])
c_brown_mushroom = placed("huge_brown_mushroom_any", "minecraft:huge_brown_mushroom", [])

MIXES = {}


def mix(name, default, choices, per_chunk):
    """per_chunk > 0: that many trees per chunk (+1 one time in ten); per_chunk < 0: one tree in 1/-per_chunk chunks."""
    cf = selector("trees_" + name, default, *choices)
    if per_chunk > 0:
        MIXES[name] = trees_placed("trees_" + name, cf, per_chunk, 1)
    else:
        MIXES[name] = trees_placed("trees_" + name, cf, 0, chance=-per_chunk)
    return MIXES[name]


mix("skyrim_pine", c_tall_spruce, [(c_tall_pine, 0.3), (c_giant_spruce, 0.08), (SPRUCE, 0.15)], 9)
mix("skyrim_pine_sparse", c_tall_spruce, [(c_tall_pine, 0.35), (SPRUCE, 0.2)], 2)
mix("skyrim_snowy_pine", c_snowy_spruce, [(c_snowy_pine, 0.35), (c_giant_snowy, 0.06)], 7)
mix("skyrim_snowy_sparse", c_snowy_spruce, [(c_snowy_pine, 0.4)], 1)
mix("falkreath", c_tall_spruce, [(c_giant_spruce, 0.22), (MEGA_SPRUCE, 0.12), (c_tall_pine, 0.18), (FANCY, 0.05)], 14)
mix("rift", c_golden, [(c_orange, 0.3), (c_tall_golden, 0.2), (c_tall_orange, 0.1), (c_tall_spruce, 0.1)], 7)
mix("tundra_scattered", c_juniper, [(c_tall_spruce, 0.25), (c_reach_shrub, 0.2)], -2)
mix("whiterun_scattered", c_tall_spruce, [(c_juniper, 0.4), (OAK, 0.1)], -2)
mix("reach", c_reach_shrub, [(c_crooked, 0.25), (c_juniper, 0.2), (c_tall_pine, 0.1)], 2)
mix("reach_valley", c_crooked, [(c_reach_shrub, 0.3), (c_tall_spruce, 0.2), (FANCY, 0.1), (BIRCH, 0.1)], 4)
mix("solstheim_pine", c_snowy_spruce, [(c_tall_spruce, 0.4), (c_snowy_pine, 0.2)], 6)
mix("highland_scattered", c_tall_pine, [(c_juniper, 0.4), (c_tall_spruce, 0.3)], 1)
mix("alpine", c_tall_spruce, [(c_juniper, 0.3), (c_snowy_spruce, 0.2)], -2)
mix("heartlands", OAK, [(FANCY, 0.3), (BIRCH, 0.2), (c_cypress, 0.1)], 1)
mix("great_forest", DARK_OAK, [(FANCY, 0.3), (OAK, 0.2), (BIRCH, 0.1)], 14)
mix("colovian", c_tall_spruce, [(OAK, 0.25), (c_cypress, 0.15), (FANCY, 0.1)], 2)
mix("gold_coast", c_olive, [(c_cypress, 0.3), (OAK, 0.2)], 1)
mix("west_weald", c_cypress, [(c_olive, 0.3), (OAK, 0.2)], -2)
mix("nibenay", OAK, [(BIRCH, 0.25), (c_swamp_oak, 0.2), (FANCY, 0.15), (c_cypress, 0.1)], 3)
mix("blackwood", c_swamp_oak, [(MANGROVE, 0.3), (JUNGLE, 0.2), (DARK_OAK, 0.1)], 6)
mix("jerall", c_tall_spruce, [(SPRUCE, 0.3), (c_tall_pine, 0.2), (OAK, 0.05)], 6)
mix("rumare", OAK, [(BIRCH, 0.3), (FANCY, 0.2)], -2)
mix("bitter_coast", c_swamp_oak, [(MANGROVE, 0.3), (c_swamp_hist, 0.1)], 4)
mix("grazelands", c_ash_scrub, [(c_crooked, 0.2)], -2)
mix("west_gash", c_gash_pine, [(c_juniper, 0.3), (c_crooked, 0.2)], 2)
mix("ascadian", OAK, [(CHERRY, 0.25), (FANCY, 0.15), (c_orange, 0.1)], 2)
mix("deshaan", c_crooked, [(c_orange, 0.3), (ACACIA, 0.2)], -2)
mix("black_marsh", c_swamp_hist, [(MANGROVE, 0.3), (c_swamp_oak, 0.2), (JUNGLE, 0.1)], 6)
mix("mangrove", MANGROVE, [(TALL_MANGROVE, 0.4)], 8)
mix("shadowfen", c_swamp_oak, [(c_swamp_hist, 0.3), (DARK_OAK, 0.2)], 5)
mix("savanna", ACACIA, [(c_baobab, 0.15)], -2)
mix("pellitine", JUNGLE, [(MEGA_JUNGLE, 0.2), (JUNGLE_BUSH, 0.3), (ACACIA, 0.05)], 12)
mix("valenwood", c_young_graht, [(MEGA_JUNGLE, 0.25), (JUNGLE, 0.25), (JUNGLE_BUSH, 0.15)], 10)
mix("rainforest", JUNGLE, [(MEGA_JUNGLE, 0.35), (JUNGLE_BUSH, 0.25), (c_young_graht, 0.1)], 18)
mix("reapers_march", OAK, [(FANCY, 0.3), (JUNGLE, 0.2), (c_young_graht, 0.1), (DARK_OAK, 0.1)], 4)
mix("summerset", c_summerset_gold, [(CHERRY, 0.35), (FANCY, 0.15), (BIRCH, 0.1)], 1)
mix("artaeum", CHERRY, [(c_summerset_gold, 0.4), (BIRCH, 0.1)], 3)
mix("auridon", c_olive, [(CHERRY, 0.2), (c_cypress, 0.2)], -2)
mix("bangkorai", OAK, [(SPRUCE, 0.3), (DARK_OAK, 0.1)], 2)
mix("glenumbra", OAK, [(BIRCH, 0.3), (FANCY, 0.2)], 1)
mix("stormhaven", OAK, [(FANCY, 0.3), (BIRCH, 0.2), (DARK_OAK, 0.1)], 4)
mix("rivenspire", DARK_OAK, [(SPRUCE, 0.3), (c_tall_spruce, 0.15)], 5)
mix("wrothgar", c_tall_spruce, [(SPRUCE, 0.3), (c_snowy_spruce, 0.2)], 2)
mix("wayrest", OAK, [(BIRCH, 0.2), (FANCY, 0.2)], -2)
mix("atmora", c_giant_snowy, [(c_snowy_spruce, 0.5), (c_snowy_pine, 0.2)], 8)
mix("tsaesci", JUNGLE, [(MEGA_JUNGLE, 0.3), (JUNGLE_BUSH, 0.3)], 10)
mix("tang_mo", CHERRY, [(JUNGLE_BUSH, 0.3), (c_akaviri_pine, 0.2)], 2)
mix("ka_po_tun", c_akaviri_pine, [(ACACIA, 0.2)], -2)
mix("pyandonea", JUNGLE, [(MEGA_JUNGLE, 0.2), (JUNGLE_BUSH, 0.3)], 7)
mix("mushroom_grove", c_red_mushroom, [(c_brown_mushroom, 0.5)], 1)
mix("sheogorad", c_brown_mushroom, [(c_juniper, 0.4)], -2)
mix("jungle_coast", JUNGLE_BUSH, [(JUNGLE, 0.3)], 1)
mix("dragontail", c_juniper, [(ACACIA, 0.2)], -2)
mix("craglorn", c_juniper, [(c_baobab, 0.1)], -2)
mix("lake_shore", OAK, [(BIRCH, 0.3), (c_swamp_oak, 0.2)], -2)

# --- dead trees and fallen logs (Java)
def dead_tree(name, log_name, chance=None, n=None):
    cf = configured("dead_tree_" + name, SK + ":dead_tree", {"state": st(log_name, axis="y")})
    mods = ([count(n)] if n else []) + ([rarity(chance)] if chance else [])
    return placed("dead_trees_" + name + "_%d" % (chance or n), cf,
                  mods + [SQUARE, water_depth(1), hm("OCEAN_FLOOR"), BIOME], V)


dead_tree("oak", "oak_log", chance=2)
dead_tree("oak", "oak_log", chance=6)
dead_tree("spruce", "spruce_log", chance=3)
dead_tree("charred", "dark_oak_log", n=2)
dead_tree("charred", "dark_oak_log", chance=4)
dead_tree("acacia", "acacia_log", chance=8)
dead_tree("birch", "birch_log", chance=8)


def fallen_log(name, log_name, chance):
    cf = configured("fallen_log_" + name, SK + ":fallen_log", {"state": st(log_name, axis="x")})
    return placed("fallen_logs_" + name + "_%d" % chance, cf, [rarity(chance), SQUARE, hm("OCEAN_FLOOR"), BIOME], V)


fallen_log("spruce", "spruce_log", 3)
fallen_log("spruce", "spruce_log", 8)
fallen_log("birch", "birch_log", 4)
fallen_log("oak", "oak_log", 5)
fallen_log("dark_oak", "dark_oak_log", 4)
fallen_log("jungle", "jungle_log", 4)
fallen_log("graht_oak", SK + ":graht_oak_log", 3)
fallen_log("hist", SK + ":hist_log", 5)
fallen_log("charred", "dark_oak_log", 6)

# --- bushes, berries, tall plants
patch("snowberries", simple("sweet_berry_bush", age=3), tries=24, xz=5)
patch_placed("snowberries", "snowberries", chance=6)
patch_placed("snowberries_rare", "snowberries", chance=24)
patch("tall_ferns", weighted(("large_fern", 2), ("fern", 3)), tries=48)
patch_placed("tall_ferns", "tall_ferns", n=4)
patch("lavender", weighted(("lilac", 2), ("allium", 5)), tries=48, xz=6)
patch_placed("lavender_fields", "lavender", n=2)
patch_placed("lavender_rare", "lavender", chance=8)
patch("roses", weighted(("rose_bush", 2), ("peony", 2), ("poppy", 3)), tries=32)
patch_placed("roses", "roses", chance=4)
patch("swamp_reeds", simple(SK + ":swamp_reeds", half="lower"), tries=64, xz=6, y=2, predicate=near_water())
patch_placed("reeds_dense", "swamp_reeds", n=8)
patch_placed("reeds_sparse", "swamp_reeds", n=2)
patch("tall_grass", simple("tall_grass", half="lower"), tries=48)
patch_placed("tall_grass_dense", "tall_grass", n=5)
patch_placed("tall_grass_sparse", "tall_grass", chance=3)
patch("sugar_cane_field", simple("sugar_cane"), tries=64, xz=6, y=2, predicate=near_water())
patch_placed("moon_sugar", "sugar_cane_field", n=8)
patch("crystal_clusters", simple(SK + ":crystal_cluster", facing="up", waterlogged=False), tries=16, xz=5)
patch_placed("crystal_clusters", "crystal_clusters", n=2)
patch_placed("crystal_clusters_rare", "crystal_clusters", chance=6)
patch("dead_corals", weighted(("dead_brain_coral", 1), ("dead_fire_coral", 1), ("dead_horn_coral", 1),
                              ("dead_tube_coral_fan", 1), ("dead_bubble_coral_fan", 1)), tries=32)
patch_placed("dead_corals", "dead_corals", n=3)
patch("sweet_berries_wild", simple("sweet_berry_bush", age=3), tries=16, xz=4)
patch_placed("grape_vines", "sweet_berries_wild", n=2)
patch("pumpkins_wild", simple("pumpkin"), tries=12, xz=5)
patch_placed("pumpkins_rare", "pumpkins_wild", chance=48)
patch("cactus_short", simple("cactus"), tries=10, xz=5)
patch_placed("cacti_rare", "cactus_short", chance=6)
patch("bamboo_grove", simple("bamboo", age=1, leaves="large", stage=0), tries=32, xz=5)
patch_placed("bamboo_groves", "bamboo_grove", chance=3)

# --- flowers (all vanilla flowers double as Skycraft alchemy ingredients)
def flowers(name, *pairs, tries=48, n=None, chance=None):
    cf = patch("flowers_" + name, weighted(*pairs), tries=tries)
    return patch_placed("flowers_" + name, cf, n=n, chance=chance, heightmap="MOTION_BLOCKING")


flowers("tundra", ("azure_bluet", 3), ("oxeye_daisy", 2), ("dandelion", 1), n=2)              # tundra cotton
flowers("mountain", ("poppy", 3), ("cornflower", 3), ("allium", 2), n=2)                      # mountain flowers
flowers("mountain_rare", ("poppy", 3), ("cornflower", 3), ("allium", 2), chance=4)
flowers("rift", ("poppy", 2), ("cornflower", 2), ("orange_tulip", 2), ("dandelion", 1), n=2)
flowers("meadow", ("cornflower", 3), ("allium", 2), ("azure_bluet", 2), ("oxeye_daisy", 2), ("poppy", 2), n=5)
flowers("forest", ("lily_of_the_valley", 2), ("poppy", 1), ("cornflower", 1), chance=3)
flowers("swamp", ("blue_orchid", 4), chance=2)
flowers("swamp_dense", ("blue_orchid", 4), ("lily_of_the_valley", 1), n=3)
flowers("heartlands", ("poppy", 2), ("dandelion", 2), ("oxeye_daisy", 2), ("red_tulip", 1), ("white_tulip", 1), n=3)
flowers("weald", ("allium", 3), ("pink_tulip", 2), ("lilac", 1), ("dandelion", 1), n=4)
flowers("gold_coast", ("dandelion", 3), ("orange_tulip", 2), ("oxeye_daisy", 2), n=2)
flowers("ascadian", ("pink_tulip", 3), ("allium", 3), ("dandelion", 2), ("azure_bluet", 1), n=4)  # heather, stoneflower, gold kanet
flowers("ash", ("dead_bush", 3), ("wither_rose", 1), chance=6)                                  # ash yam / fire petal stand-in
flowers("desert", ("dandelion", 2), ("orange_tulip", 1), chance=8)
flowers("savanna", ("orange_tulip", 2), ("dandelion", 3), ("red_tulip", 1), n=1)
flowers("jungle", ("blue_orchid", 2), ("allium", 1), ("red_tulip", 1), n=2)
flowers("summerset", ("lily_of_the_valley", 3), ("white_tulip", 2), ("azure_bluet", 2), ("allium", 1), n=5)
flowers("artaeum", ("lily_of_the_valley", 3), ("allium", 2), ("pink_tulip", 2), ("torchflower", 1), n=5)
flowers("high_rock", ("poppy", 3), ("cornflower", 2), ("oxeye_daisy", 2), ("red_tulip", 1), n=2)
flowers("moor", ("allium", 3), ("azure_bluet", 2), ("lilac", 1), n=2)                            # heather moors
flowers("akavir", ("pink_tulip", 3), ("white_tulip", 2), ("lily_of_the_valley", 2), n=3)
flowers("steppe", ("dandelion", 3), ("cornflower", 2), ("poppy", 1), n=1)
flowers("cold", ("azure_bluet", 2), ("lily_of_the_valley", 1), chance=6)

# --- grass and ground cover
def grass(name, provider, n, tries=32):
    cf = patch("grass_" + name, provider, tries=tries)
    return patch_placed("grass_" + name, cf, n=n)


grass("tundra", weighted(("grass", 6), ("fern", 1), ("dead_bush", 1)), 6)
grass("tundra_sparse", weighted(("grass", 4), ("dead_bush", 2)), 2)
grass("plains", weighted(("grass", 8), ("fern", 1)), 10)
grass("forest", weighted(("grass", 3), ("fern", 4)), 6)
grass("taiga", weighted(("grass", 1), ("fern", 4)), 5)
grass("lush", weighted(("grass", 5), ("fern", 2)), 16, tries=48)
grass("dry", weighted(("grass", 3), ("dead_bush", 1)), 4)
grass("savanna", weighted(("grass", 6), (st("tall_grass", half="lower"), 2)), 12)
grass("ash", weighted((st(SK + ":ash_grass"), 6), (st(SK + ":scathecraw"), 2), ("dead_bush", 1)), 6)
grass("ash_sparse", weighted((st(SK + ":ash_grass"), 4), (st(SK + ":scathecraw"), 1), ("dead_bush", 2)), 2)
grass("grazelands", weighted(("grass", 5), (st(SK + ":ash_grass"), 3), (st("tall_grass", half="lower"), 2)), 14)
grass("swamp", weighted(("grass", 4), ("fern", 2)), 6)
grass("jungle", weighted(("grass", 3), ("fern", 3)), 20, tries=48)
grass("desert", weighted(("dead_bush", 3), ("grass", 1)), 2)
grass("snow", weighted(("grass", 2), ("fern", 1)), 1)

patch("ash_drifts", weighted((st(SK + ":ash_layer", layers=1), 6), (st(SK + ":ash_layer", layers=2), 3),
                             (st(SK + ":ash_layer", layers=3), 1)), tries=48, xz=6)
patch_placed("ash_drifts", "ash_drifts", n=4)
patch_placed("ash_drifts_sparse", "ash_drifts", chance=2)
patch("moss_carpets", simple("moss_carpet"), tries=48, xz=6)
patch_placed("moss_carpets", "moss_carpets", n=2)
patch("forest_mushrooms", weighted(("brown_mushroom", 2), ("red_mushroom", 1)), tries=24, xz=6)
patch_placed("forest_mushrooms", "forest_mushrooms", chance=3, heightmap="MOTION_BLOCKING")
patch("nightshade_wild", simple(SK + ":nightshade", harvested=False), tries=24, xz=5)
patch_placed("nightshade_shade", "nightshade_wild", chance=10, heightmap="MOTION_BLOCKING")

# --- water plants
patch("lily_pads", simple("lily_pad"), tries=12, xz=7, y=3)
placed("lily_pads", "lily_pads", [count(4), SQUARE, hm("WORLD_SURFACE_WG"), BIOME], V)
placed("lily_pads_dense", "lily_pads", [count(10), SQUARE, hm("WORLD_SURFACE_WG"), BIOME], V)

# --- cave flora (Skycraft cave biomes)
cave_floor_placed("glowing_mushrooms", "glowing_mushroom", 18)
cave_floor_placed("grotto_mushrooms", "grotto_mushroom", 14)
patch("cave_mushrooms", weighted(("brown_mushroom", 2), ("red_mushroom", 1)), tries=24, xz=5, y=2)
cave_floor_placed("cave_mushroom_patches", "cave_mushrooms", 20)
patch("cave_moss", weighted(("moss_carpet", 4), ("brown_mushroom", 1)), tries=32, xz=5, y=2)
cave_floor_placed("cave_moss_patches", "cave_moss", 16)
patch("cave_crystals", simple(SK + ":crystal_cluster", facing="up", waterlogged=False), tries=12, xz=4, y=2)
cave_floor_placed("cave_crystals", "cave_crystals", 10)

# ===================================================================================================== flora
# Nirn's vegetation, region by region (owned by the flora workstream; see docs/FLORA.md). The Java side is
# com.skycraft.tamriel.biome.flora: skycraft:flora_tree grows procedural trees of a named species, spaced by
# skycraft:flora_scatter (blue-noise points thinned by a density field: groves, clearings, forest edges);
# skycraft:ground_cover places tufts whose kind follows slow per-plant noise (drifts), thinned by skycraft:flora_density;
# skycraft:flora_rock makes rocks of varied shapes with moss and lichen.
#
# FLORA[biome] lists each land biome's vegetation. apply_flora() swaps it in for the biome's older vegetation
# entries (everything this file defines in vegetal_decoration, except cave flora, plus the old boulders), so the
# catalogue below keeps its terrain features and the flora table owns the plants. Old vegetation features stay
# defined for anything else that names them.
OLD_VEG = set(MINE[V]) - {ref(n) for n in ("glowing_mushrooms", "grotto_mushrooms", "cave_mushroom_patches",
                                             "cave_moss_patches", "cave_crystals")}
OLD_VEG |= {f for f in MINE["local_modifications"] if f.startswith(SK + ":biome/boulders_")}
# vanilla vegetation the flora table manages too (it re-lists what it keeps)
OLD_VEG |= {"minecraft:vines", "minecraft:patch_melon", "minecraft:bamboo_vegetation", "minecraft:patch_sugar_cane",
            "minecraft:brown_mushroom_swamp", "minecraft:red_mushroom_swamp", "minecraft:patch_dead_bush_2",
            "minecraft:patch_dead_bush_badlands"}
FLORA = {}


def field(salt, scale, coverage=0.5, softness=0.12, kind="fbm", mode="inside"):
    return {"salt": salt, "scale": scale, "coverage": coverage, "softness": softness, "kind": kind, "mode": mode}


def mode(f, m):
    g = dict(f)
    g["mode"] = m
    return g


def forest(name, species, spacing, density, fld, floor=0.0, stands=0.6, stand_scale=48, min_distance=0.8):
    """A layer of trees: species [(name, weight)], one per blue-noise point `spacing` apart where the field lets it."""
    assert all(isinstance(w, int) for _, w in species), name
    cf = configured("flora/trees_" + name, SK + ":flora_tree", {
        "species": [{"species": s, "weight": w} for s, w in species], "stands": stands, "stand_scale": stand_scale})
    return placed("flora/trees_" + name, cf, [
        {"type": SK + ":flora_scatter", "spacing": spacing, "min_distance": min_distance, "density": density,
         "floor": floor, "field": fld}, hm("OCEAN_FLOOR"), BIOME], V)


def plant(name, **props):
    s = st(name, **props)
    if s["Name"] in DOUBLE:
        s.setdefault("Properties", {})["half"] = "lower"
    return s


DOUBLE = {"minecraft:tall_grass", "minecraft:large_fern", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony",
          "minecraft:sunflower", SK + ":swamp_reeds"}


def cover(name, entries, n, fld, density=1.0, floor=0.0, scale=24, patchiness=0.6, cluster=(1, 3), spread=2,
          near_water=False, salt=None):
    """A layer of ground cover: n tries per chunk, kept where the field allows, each a tuft of `cluster` plants."""
    cfg = {"entries": [{"state": plant(e) if isinstance(e, str) else e, "weight": w} for e, w in entries],
           "scale": scale, "patchiness": patchiness, "cluster": uniform(*cluster), "spread": spread,
           "salt": salt if salt is not None else zlib.crc32(name.encode()) % 9973, "near_water": near_water}
    cf = configured("flora/cover_" + name, SK + ":ground_cover", cfg)
    return placed("flora/cover_" + name, cf, [count(n), SQUARE,
                                               {"type": SK + ":flora_density", "density": density, "floor": floor,
                                                "field": fld}, hm("MOTION_BLOCKING_NO_LEAVES"), BIOME], V)


def rocks(name, stone, size, n=1, moss=None, moss_chance=0.0, style="mixed", fld=None, floor=0.15, chance=None):
    cfg = {"stone": stone, "moss_chance": moss_chance, "size": size if isinstance(size, dict) else uniform(*size),
           "style": style}
    if moss:
        cfg["moss"] = moss
    cf = configured("flora/rock_" + name, SK + ":flora_rock", cfg)
    mods = [count(n)] if chance is None else [rarity(chance)]
    mods.append(SQUARE)
    if fld:
        mods.append({"type": SK + ":flora_density", "density": 1.0, "floor": floor, "field": fld})
    return placed("flora/rocks_" + name, cf, mods + [hm("MOTION_BLOCKING"), BIOME], "local_modifications")


# --- fields: the same salt and shape is the same forest for trees, edge shrubs and clearing flowers
F_FOREST_DENSE = field(101, 72, 0.82, 0.16)       # closed forest, a few glades
F_FOREST = field(102, 60, 0.62, 0.15)             # forest with clearings
F_WOODLAND = field(103, 52, 0.42, 0.16)           # groves in open country
F_SCATTER = field(104, 90, 0.22, 0.2)             # lone trees and copses
F_MARSH = field(105, 40, 0.5, 0.18)
F_OASIS = field(106, 140, 0.12, 0.08, "worley")   # palm oases in the desert
F_TUFT = field(110, 11, 0.55, 0.2, "worley")      # grass tufts and bare ground
F_MEADOW = field(111, 30, 0.45, 0.2)              # flower meadows
F_DRIFT = field(112, 16, 0.4, 0.2)                # small drifts (moss, ferns)
F_RING = field(113, 13, 0.18, 0.08, "worley")     # mushroom rings, berry clumps
F_ROCKS = field(114, 44, 0.22, 0.15, "worley")    # rock fields
F_EVERY = field(115, 64, 0.5, 0.1, mode="any")

# --- rocks (local_modifications: before any plant)
MOSS = weighted(("moss_block", 3), ("mossy_cobblestone", 3))
LICHEN = weighted(("mossy_cobblestone", 4), ("moss_block", 1), ("tuff", 1))
R_TUNDRA = rocks("tundra", ROCK_TUNDRA, (1, 3), 2, LICHEN, 0.25, fld=F_ROCKS)
R_TUNDRA_FEW = rocks("tundra_few", ROCK_TUNDRA, (1, 2), 1, LICHEN, 0.2, fld=F_ROCKS, floor=0.05)
R_MOSSY = rocks("mossy", weighted(("stone", 4), ("cobblestone", 2), ("andesite", 2), ("mossy_cobblestone", 2)), (1, 3), 2,
                MOSS, 0.6, fld=F_ROCKS)
R_GREY = rocks("grey", ROCK_GREY, (1, 3), 1, LICHEN, 0.15, fld=F_ROCKS)
R_REACH = rocks("reach", ROCK_REACH, (2, 4), 2, LICHEN, 0.2, fld=F_ROCKS, floor=0.3)
R_REACH_STANDING = rocks("reach_standing", ROCK_REACH, (2, 4), style="standing", chance=9)
R_VOLCANIC = rocks("volcanic", ROCK_VOLCANIC, (1, 3), 2, fld=F_ROCKS, floor=0.2)
R_SAND = rocks("sand", ROCK_SAND, (1, 3), 1, fld=F_ROCKS, floor=0.05)
R_RED = rocks("red", ROCK_RED, (1, 4), 1, fld=F_ROCKS, floor=0.2)
R_ICE = rocks("ice", ROCK_ICE, (1, 3), 1, fld=F_ROCKS, floor=0.2)
R_CORAL = rocks("coral", ROCK_CORAL, (1, 2), 1, fld=F_ROCKS, floor=0.2)
R_CALCITE = rocks("calcite", ROCK_CALCITE, (1, 3), 1, fld=F_ROCKS, floor=0.2)
R_DARK = rocks("dark", ROCK_DARK, (1, 3), 1, MOSS, 0.4, fld=F_ROCKS, floor=0.2)
R_MOOR = rocks("moor", ROCK_GREY, (1, 3), 2, MOSS, 0.5, style="mixed", fld=F_ROCKS, floor=0.25)
R_RUBBLE = rocks("rubble", ROCK_GREY, (1, 2), 1, style="rubble", chance=3)

# --- tree layers. Spacing is the blue-noise distance in blocks; density the chance a point grows inside the field.
T = forest
SKYRIM_PINES = [("skyrim_pine", 6), ("skyrim_spruce", 3), ("giant_pine", 1)]
T_PINE = T("skyrim_pine", SKYRIM_PINES, 5, 0.85, F_FOREST, floor=0.05)
T_PINE_UNDER = T("skyrim_pine_under", [("pine_sapling", 4), ("conifer_bush", 2), ("berry_bush", 1)], 4, 0.5,
                 mode(F_FOREST, "edge"), floor=0.03)
T_PINE_DEAD = T("skyrim_pine_dead", [("fallen_spruce", 4), ("stump_spruce", 2), ("dead_pine", 2)], 12, 0.45, F_FOREST)
T_SNOWY = T("snowy_pine", [("snowy_pine", 6), ("snowy_spruce", 4), ("snowy_giant_pine", 1)], 5, 0.8, F_FOREST, floor=0.05)
T_SNOWY_UNDER = T("snowy_under", [("snowy_sapling", 3), ("conifer_bush", 1)], 5, 0.4, mode(F_FOREST, "edge"))
T_SNOWY_DEAD = T("snowy_dead", [("dead_pine", 2), ("fallen_spruce", 3), ("frozen_pine", 1)], 13, 0.4, F_FOREST)
T_SNOWY_SPARSE = T("snowy_sparse", [("snowy_pine", 3), ("snowy_spruce", 4), ("snowy_sapling", 2), ("frozen_pine", 1)],
                   9, 0.6, F_SCATTER, floor=0.03)
T_FALKREATH = T("falkreath", [("skyrim_pine", 10), ("giant_pine", 4), ("skyrim_spruce", 6), ("dark_oak", 2), ("birch", 1)],
                5, 0.85, F_FOREST_DENSE, floor=0.1, min_distance=0.85)
T_FALKREATH_UNDER = T("falkreath_under", [("pine_sapling", 3), ("forest_bush", 2), ("conifer_bush", 2), ("sapling_oak", 1)],
                      4, 0.55, mode(F_FOREST_DENSE, "edge"), floor=0.12)
T_FALKREATH_DEAD = T("falkreath_dead", [("fallen_spruce", 5), ("stump_spruce", 3), ("dead_pine", 2), ("fallen_dark_oak", 1)],
                     9, 0.55, F_FOREST_DENSE)
T_RIFT = T("rift", [("aspen_gold", 8), ("aspen_orange", 6), ("skyrim_pine", 3), ("maple", 2), ("aspen_young_gold", 2),
                    ("aspen_young_orange", 2)], 5, 0.85, F_FOREST, floor=0.05, stands=0.8)
T_RIFT_UNDER = T("rift_under", [("autumn_bush", 3), ("berry_bush", 1), ("pine_sapling", 1)], 5, 0.5,
                 mode(F_FOREST, "edge"), floor=0.03)
T_RIFT_DEAD = T("rift_dead", [("fallen_birch", 4), ("stump_birch", 2), ("dead_birch", 1)], 13, 0.4, F_FOREST)
T_TUNDRA = T("tundra", [("tundra_shrub", 6), ("juniper", 4), ("tundra_tree", 3), ("skyrim_pine", 1)], 8, 0.55,
             F_SCATTER, floor=0.04, stands=0.5)
T_WHITERUN = T("whiterun", [("tundra_shrub", 4), ("juniper", 3), ("tundra_tree", 2), ("oak", 1), ("skyrim_pine", 1)],
               10, 0.5, F_SCATTER, floor=0.02)
T_REACH = T("reach", [("crag_tree", 5), ("juniper", 3), ("highland_pine", 1), ("tundra_shrub", 2)], 7, 0.6,
            F_WOODLAND, floor=0.08)
T_REACH_VALLEY = T("reach_valley", [("crag_tree", 3), ("oak", 2), ("birch", 2), ("skyrim_pine", 2), ("juniper", 1),
                                    ("forest_bush", 2)], 6, 0.65, F_WOODLAND, floor=0.05)
T_HJAAL = T("hjaalmarch", [("swamp_pine", 7), ("marsh_snag", 2), ("dead_oak", 1), ("conifer_bush", 2)], 7, 0.55,
            F_MARSH, floor=0.06)
T_HJAAL_DEAD = T("hjaalmarch_dead", [("fallen_spruce", 2), ("stump_spruce", 1)], 16, 0.4, F_MARSH)
T_VOLC_TUNDRA = T("volcanic_tundra", [("tundra_shrub", 4), ("juniper", 3), ("tundra_tree", 2), ("dead_pine", 1)], 9, 0.5,
                  F_SCATTER, floor=0.03)
T_HOT_SPRINGS = T("hot_springs", [("juniper", 3), ("tundra_shrub", 3), ("dead_pine", 1)], 11, 0.4, F_SCATTER, floor=0.03)
T_SLOPES = T("slopes", [("snowy_spruce", 3), ("frozen_pine", 2), ("snowy_sapling", 2)], 11, 0.35, F_SCATTER)
T_ALPINE = T("alpine", [("highland_pine", 3), ("juniper", 3), ("snowy_spruce", 1), ("tundra_shrub", 2)], 9, 0.45,
             F_SCATTER, floor=0.03)
T_HIGHLAND = T("highland", [("highland_pine", 4), ("juniper", 3), ("tundra_tree", 2), ("dead_pine", 1)], 8, 0.5,
               F_WOODLAND, floor=0.03)
T_SOLS_ASH = T("solstheim_ash", [("charred_tree", 3), ("fallen_charred", 2)], 12, 0.4, F_SCATTER, floor=0.05)
T_SOLS_PINE = T("solstheim_pine", [("solstheim_pine", 5), ("snowy_spruce", 2), ("snowy_pine", 2)], 5, 0.8, F_FOREST,
                floor=0.05)
# Cyrodiil
T_HEARTLANDS = T("heartlands", [("oak", 4), ("elm", 3), ("fruit_tree", 1), ("birch", 1), ("cypress", 1)], 8, 0.6,
                 F_WOODLAND, floor=0.02)
T_GREAT_FOREST = T("great_forest", [("great_oak", 3), ("oak", 4), ("dark_oak", 4), ("beech", 3), ("elm", 2)], 7, 0.75,
                   F_FOREST, floor=0.08, min_distance=0.9)
T_GREAT_UNDER = T("great_forest_under", [("sapling_oak", 3), ("forest_bush", 4), ("berry_bush", 1)], 4, 0.6,
                  mode(F_FOREST, "edge"), floor=0.15)
T_GREAT_DEAD = T("great_forest_dead", [("fallen_oak", 3), ("fallen_dark_oak", 3), ("stump_oak", 2), ("dead_oak", 1)],
                 10, 0.5, F_FOREST)
T_COLOVIAN = T("colovian", [("skyrim_pine", 3), ("oak", 3), ("cypress", 2), ("juniper", 2), ("highland_pine", 1)], 8,
               0.55, F_WOODLAND, floor=0.03)
T_GOLD_COAST = T("gold_coast", [("olive", 4), ("cypress", 4), ("fruit_tree", 1), ("palm", 1)], 8, 0.55, F_WOODLAND,
                 floor=0.03, stands=0.8)
T_WEALD = T("west_weald", [("fruit_tree", 5), ("cypress", 3), ("olive", 2)], 7, 0.5, field(120, 40, 0.4, 0.1),
            floor=0.02, stands=0.9)
T_NIBENAY = T("nibenay", [("oak", 3), ("willow", 2), ("elm", 2), ("birch", 1), ("swamp_oak", 1)], 7, 0.65, F_WOODLAND,
              floor=0.03)
T_BLACKWOOD = T("blackwood", [("swamp_oak", 4), ("bald_cypress", 3), ("mangrove", 2), ("swamp_hist", 1)], 6, 0.75,
                F_MARSH, floor=0.1)
T_JERALL = T("jerall", [("skyrim_pine", 12), ("skyrim_spruce", 12), ("snowy_spruce", 3), ("oak", 1)], 5, 0.75,
             F_FOREST, floor=0.05)
T_RUMARE = T("rumare", [("willow", 4), ("oak", 1), ("birch", 1)], 9, 0.45, F_WOODLAND, floor=0.03)
# Morrowind
T_ASHLANDS = T("ashlands", [("charred_tree", 3), ("ash_tree", 2), ("fallen_charred", 1)], 12, 0.35, F_SCATTER, floor=0.04)
T_BITTER = T("bitter_coast", [("bitter_coast_tree", 6), ("marsh_snag", 1), ("swamp_hist", 1)], 6, 0.7, F_MARSH,
             floor=0.08)
T_GRAZE = T("grazelands", [("graze_tree", 3), ("tundra_shrub", 2), ("ash_tree", 1)], 13, 0.35, F_SCATTER, floor=0.03)
T_GASH = T("west_gash", [("west_gash_pine", 5), ("juniper", 2), ("crag_tree", 1)], 7, 0.6, F_WOODLAND, floor=0.04)
T_ASCADIAN = T("ascadian", [("ascadian_bloom", 3), ("ascadian_oak", 3), ("oak", 1), ("fruit_tree", 1)], 7, 0.6,
               F_WOODLAND, floor=0.04, stands=0.7)
T_AZURA = T("azura", [("juniper", 2), ("ascadian_bloom", 1), ("crag_tree", 1)], 12, 0.4, F_SCATTER)
T_SHEOGORAD = T("sheogorad", [("juniper", 3), ("dead_birch", 1), ("tundra_shrub", 2)], 11, 0.4, F_SCATTER)
T_MOLAG = T("molag_amur", [("charred_tree", 1)], 16, 0.3, F_SCATTER)
T_DESHAAN = T("deshaan", [("ash_tree", 2), ("graze_tree", 2), ("maple", 1)], 11, 0.45, F_SCATTER, floor=0.03)
T_STONEFALLS = T("stonefalls", [("charred_tree", 2), ("graze_tree", 1), ("ash_tree", 1)], 12, 0.35, F_SCATTER)
# Black Marsh
T_BLACK_MARSH = T("black_marsh", [("bald_cypress", 4), ("mangrove", 3), ("swamp_hist", 2), ("swamp_oak", 1),
                                  ("jungle_tree", 1)], 6, 0.8, F_MARSH, floor=0.12)
T_MARSH_DEAD = T("marsh_dead", [("marsh_snag", 2), ("fallen_mangrove", 2), ("fallen_oak", 1)], 12, 0.45, F_MARSH)
T_DEEP_MIRE = T("deep_mire", [("bald_cypress", 4), ("mangrove", 3), ("marsh_snag", 2)], 5, 0.75, F_MARSH, floor=0.15)
T_HIST_GROVE = T("hist_grove", [("swamp_hist", 4), ("bald_cypress", 1), ("jungle_bush", 1)], 7, 0.6, F_MARSH, floor=0.1)
T_MANGROVE = T("mangrove", [("mangrove", 6), ("bald_cypress", 1)], 5, 0.8, F_MARSH, floor=0.2)
T_SHADOWFEN = T("shadowfen", [("bald_cypress", 3), ("dark_oak", 2), ("marsh_snag", 3), ("swamp_hist", 1)], 6, 0.7,
                F_MARSH, floor=0.1)
T_MURKMIRE = T("murkmire", [("jungle_tree", 3), ("bald_cypress", 2), ("mangrove", 2), ("swamp_hist", 1),
                            ("jungle_bush", 2)], 5, 0.8, F_MARSH, floor=0.15)
# Elsweyr and Hammerfell
T_SAVANNA = T("savanna", [("acacia", 5), ("baobab", 1), ("desert_shrub", 3)], 14, 0.4, F_SCATTER, floor=0.08,
              min_distance=1.0)
T_BADLANDS = T("badlands", [("bleached_tree", 2), ("desert_shrub", 3)], 14, 0.3, F_SCATTER, floor=0.05)
T_PELLITINE = T("pellitine", [("jungle_tree", 5), ("jungle_giant", 2), ("palm", 1)], 5, 0.85, F_FOREST_DENSE, floor=0.1)
T_JUNGLE_UNDER = T("jungle_under", [("jungle_bush", 5), ("bamboo", 1)], 4, 0.55, F_DRIFT, floor=0.15)
T_SUGAR = T("sugar_fields", [("palm", 2), ("acacia", 1), ("desert_shrub", 1)], 14, 0.35, F_SCATTER, floor=0.04)
T_CANYONS = T("canyons", [("bleached_tree", 2), ("desert_shrub", 3), ("acacia", 1)], 14, 0.3, F_SCATTER, floor=0.04)
T_DESERT = T("desert", [("date_palm", 4), ("palm", 1), ("desert_shrub", 2)], 6, 0.75, F_OASIS)
T_DESERT_SCRUB = T("desert_scrub", [("desert_shrub", 3), ("bleached_tree", 1)], 16, 0.25, F_SCATTER, floor=0.03)
T_DRAGONTAIL = T("dragontail", [("juniper", 3), ("bleached_tree", 1), ("desert_shrub", 3)], 12, 0.35, F_SCATTER, floor=0.03)
T_CRAGLORN = T("craglorn", [("juniper", 3), ("baobab", 1), ("desert_shrub", 3), ("acacia", 1)], 12, 0.35, F_SCATTER,
               floor=0.03)
T_BANGKORAI = T("bangkorai", [("oak", 3), ("skyrim_pine", 3), ("dark_oak", 1), ("juniper", 1)], 7, 0.6, F_WOODLAND,
                floor=0.04)
T_STROS = T("stros_mkai", [("palm", 4), ("date_palm", 1)], 6, 0.6, F_WOODLAND, floor=0.15)
# Valenwood
T_VALENWOOD = T("valenwood", [("young_graht_oak", 3), ("jungle_tree", 3), ("jungle_giant", 1)], 6, 0.85,
                F_FOREST_DENSE, floor=0.1)
T_VALEN_DEAD = T("valenwood_dead", [("fallen_graht_oak", 2), ("fallen_jungle", 2), ("stump_jungle", 1)], 12, 0.45,
                 F_FOREST_DENSE)
T_RAINFOREST = T("rainforest", [("jungle_giant", 3), ("jungle_tree", 5), ("young_graht_oak", 1)], 5, 0.9,
                 F_FOREST_DENSE, floor=0.15)
T_VALEN_MANGROVE = T("valenwood_mangrove", [("mangrove", 5), ("palm", 1)], 5, 0.75, F_MARSH, floor=0.15)
T_REAPERS = T("reapers_march", [("oak", 3), ("maple", 2), ("jungle_tree", 1), ("young_graht_oak", 1), ("acacia", 1)],
              7, 0.6, F_WOODLAND, floor=0.03)
# Summerset
T_CRYSTAL = T("crystal_coast", [("crystal_tree", 2), ("summerset_gold", 2), ("palm", 1)], 13, 0.4, F_SCATTER, floor=0.03)
T_SUMMERSET = T("summerset", [("summerset_bloom", 4), ("summerset_gold", 4), ("cherry", 1)], 8, 0.6, F_WOODLAND,
                floor=0.03, stands=0.8)
T_AURIDON = T("auridon", [("olive", 2), ("fruit_tree", 3), ("cypress", 2), ("palm", 1)], 8, 0.5, F_WOODLAND, floor=0.03)
T_ARTAEUM = T("artaeum", [("summerset_bloom", 2), ("cherry", 3), ("crystal_tree", 1)], 7, 0.65, F_WOODLAND, floor=0.05)
T_SHORE_PALMS = T("shore_palms", [("palm", 3), ("date_palm", 1)], 10, 0.4, F_SCATTER, floor=0.06)
T_TROPICAL = T("tropical_palms", [("palm", 4), ("date_palm", 1)], 6, 0.6, F_WOODLAND, floor=0.2)
# High Rock
T_MOORS = T("glenumbra", [("hawthorn", 6), ("oak", 1), ("birch", 1)], 10, 0.45, F_SCATTER, floor=0.05)
T_STORMHAVEN = T("stormhaven", [("oak", 4), ("beech", 3), ("birch", 1), ("elm", 2), ("dark_oak", 1)], 6, 0.8, F_FOREST,
                 floor=0.05)
T_TEMPERATE_UNDER = T("temperate_under", [("sapling_oak", 2), ("forest_bush", 3), ("berry_bush", 1)], 5, 0.45,
                      mode(F_FOREST, "edge"), floor=0.03)
T_TEMPERATE_DEAD = T("temperate_dead", [("fallen_oak", 3), ("stump_oak", 2), ("dead_oak", 1)], 13, 0.4, F_FOREST)
T_RIVENSPIRE = T("rivenspire", [("dark_oak", 4), ("skyrim_spruce", 3), ("dead_oak", 2), ("marsh_snag", 1)], 5, 0.8,
                 F_FOREST_DENSE, floor=0.08)
T_WROTHGAR = T("wrothgar", [("snowy_spruce", 3), ("skyrim_spruce", 3), ("skyrim_pine", 2), ("dead_pine", 1)], 6, 0.65,
               F_FOREST, floor=0.04)
T_WAYREST = T("wayrest", [("oak", 3), ("fruit_tree", 3), ("elm", 1)], 11, 0.45, F_SCATTER, floor=0.03)
# foreign lands
T_ATMORA = T("atmora", [("frozen_pine", 4), ("snowy_giant_pine", 2), ("snowy_spruce", 2)], 6, 0.7, F_FOREST, floor=0.05)
T_ATMORA_WASTE = T("atmora_waste", [("frozen_pine", 1)], 20, 0.2, F_SCATTER)
T_TSAESCI = T("tsaesci", [("jungle_tree", 4), ("jungle_giant", 1), ("bamboo", 3)], 5, 0.85, F_FOREST_DENSE, floor=0.1,
              stands=0.8)
T_KAMAL = T("kamal", [("snowy_spruce", 3), ("frozen_pine", 2)], 12, 0.35, F_SCATTER)
T_TANG_MO = T("tang_mo", [("cherry", 3), ("plum", 2), ("akaviri_pine", 3), ("bamboo", 2), ("palm", 1)], 7, 0.6,
              F_WOODLAND, floor=0.04, stands=0.8)
T_KA_PO_TUN = T("ka_po_tun", [("akaviri_pine", 3), ("acacia", 1), ("tundra_shrub", 2)], 15, 0.35, F_SCATTER, floor=0.03)
T_SUNKEN = T("sunken_isles", [("palm", 3), ("mangrove", 1)], 8, 0.5, F_WOODLAND, floor=0.05)
T_PYANDONEA = T("pyandonea", [("jungle_tree", 4), ("jungle_giant", 1), ("palm", 2)], 5, 0.8, F_FOREST_DENSE, floor=0.1)
T_THRAS = T("thras", [("palm", 2), ("date_palm", 1)], 12, 0.35, F_SCATTER, floor=0.04)
T_CRYSTAL_SPIRES = T("crystal_spires", [("crystal_tree", 1)], 16, 0.3, F_SCATTER, floor=0.05)

# --- ground cover
G = "grass"
TALL = "tall_grass"
C_TUNDRA_GRASS = cover("tundra_grass", [(G, 9), ("fern", 1), ("dead_bush", 1), (TALL, 2)], 30, F_TUFT, floor=0.2,
                       cluster=(2, 5), patchiness=0.3)
C_PLAINS_GRASS = cover("plains_grass", [(G, 10), (TALL, 3), ("fern", 1)], 40, F_TUFT, floor=0.45, cluster=(2, 6),
                       patchiness=0.4)
C_LUSH_GRASS = cover("lush_grass", [(G, 8), (TALL, 4), ("fern", 2)], 48, F_TUFT, floor=0.55, cluster=(3, 6))
C_FOREST_FLOOR = cover("forest_floor", [(G, 3), ("fern", 4), ("large_fern", 2)], 30, mode(F_FOREST, "inside"),
                       floor=0.35, cluster=(2, 5), patchiness=0.7, scale=18)
C_TAIGA_FLOOR = cover("taiga_floor", [("fern", 5), (G, 2), ("large_fern", 2)], 26, F_DRIFT, floor=0.3, cluster=(2, 5),
                      patchiness=0.7, scale=16)
C_DRY_GRASS = cover("dry_grass", [(G, 4), ("dead_bush", 1), (TALL, 1)], 20, F_TUFT, floor=0.1, cluster=(1, 4))
C_SAVANNA_GRASS = cover("savanna_grass", [(G, 6), (TALL, 5)], 46, field(116, 26, 0.6, 0.2), floor=0.25, cluster=(3, 7),
                        patchiness=0.5)
C_GRAZE_GRASS = cover("graze_grass", [(G, 5), (st(SK + ":ash_grass"), 2), (TALL, 4)], 46, field(116, 26, 0.6, 0.2),
                      floor=0.3, cluster=(3, 7))
C_ASH_GRASS = cover("ash_grass", [(st(SK + ":ash_grass"), 6), (st(SK + ":scathecraw"), 2), ("dead_bush", 1)], 20,
                    F_TUFT, floor=0.1, cluster=(1, 4), patchiness=0.6)
C_ASH_SPARSE = cover("ash_sparse", [(st(SK + ":ash_grass"), 4), (st(SK + ":scathecraw"), 1), ("dead_bush", 2)], 7,
                     F_TUFT, cluster=(1, 3))
C_SWAMP_GRASS = cover("swamp_grass", [(G, 5), ("fern", 2), (TALL, 3), ("large_fern", 1)], 42, F_DRIFT, floor=0.5,
                      cluster=(2, 5))
C_JUNGLE_FLOOR = cover("jungle_floor", [(G, 3), ("fern", 3), ("large_fern", 3), (TALL, 1)], 46, F_DRIFT, floor=0.5,
                       cluster=(3, 6), patchiness=0.6)
C_SNOW_GRASS = cover("snow_grass", [(G, 2), ("fern", 1)], 7, F_TUFT, cluster=(1, 3))
C_DESERT_SCRUB = cover("desert_scrub", [("dead_bush", 4), (G, 1)], 5, F_TUFT, floor=0.2, cluster=(1, 2))
C_MOSS = cover("moss", [("moss_carpet", 1)], 10, F_DRIFT, cluster=(3, 8), spread=3)
C_MOSS_DENSE = cover("moss_dense", [("moss_carpet", 1)], 18, F_DRIFT, floor=0.1, cluster=(4, 9), spread=3)
C_MUSHROOMS = cover("mushrooms", [("brown_mushroom", 2), ("red_mushroom", 1)], 4, F_RING, floor=0.02, cluster=(2, 6),
                    spread=2, patchiness=0.8)
C_NIGHTSHADE = cover("nightshade", [(st(SK + ":nightshade", harvested=False), 1)], 2, F_RING, cluster=(1, 3))
C_SNOWBERRIES = cover("snowberries", [(st("sweet_berry_bush", age=3), 1)], 3, F_RING, floor=0.02, cluster=(2, 5))
C_SNOWBERRIES_FEW = cover("snowberries_few", [(st("sweet_berry_bush", age=3), 1)], 1, F_RING, cluster=(1, 4))
C_GRAPES = cover("grapes", [(st("sweet_berry_bush", age=3), 1)], 8, field(117, 18, 0.35, 0.1), cluster=(3, 6), spread=3)
C_PUMPKINS = cover("pumpkins", [("pumpkin", 1)], 1, F_RING, density=0.1, cluster=(1, 3))
C_REEDS = cover("reeds", [(SK + ":swamp_reeds", 1)], 24, F_EVERY, cluster=(3, 7), spread=3, near_water=True)
C_REEDS_FEW = cover("reeds_few", [(SK + ":swamp_reeds", 1)], 8, F_EVERY, cluster=(2, 5), spread=2, near_water=True)
C_LILIES = cover("lilies", [("lily_pad", 1)], 8, F_DRIFT, floor=0.15, cluster=(2, 6), spread=3)
C_LILIES_DENSE = cover("lilies_dense", [("lily_pad", 1)], 18, F_DRIFT, floor=0.25, cluster=(3, 8), spread=3)
C_CRYSTALS = cover("crystals", [(st(SK + ":crystal_cluster", facing="up", waterlogged=False), 1)], 3, F_RING,
                   floor=0.05, cluster=(1, 4))
C_CACTI = cover("cacti", [("cactus", 1)], 1, F_RING, density=0.3, cluster=(1, 2))


def flowers(name, entries, n=10, fld=None, floor=0.04, cluster=(3, 8), spread=3, salt=None):
    return cover("flowers_" + name, entries, n, fld or F_MEADOW, floor=floor, cluster=cluster, spread=spread,
                 patchiness=0.85, scale=28, salt=salt)


FL_TUNDRA = flowers("tundra", [("azure_bluet", 3), ("oxeye_daisy", 2), ("cornflower", 2), ("dandelion", 1)], 8)
FL_MOUNTAIN = flowers("mountain", [("poppy", 3), ("cornflower", 3), ("allium", 2), ("azure_bluet", 1)], 8)
FL_MOUNTAIN_FEW = flowers("mountain_few", [("poppy", 3), ("cornflower", 3), ("allium", 2)], 3)
FL_RIFT = flowers("rift", [("poppy", 2), ("cornflower", 2), ("orange_tulip", 3), ("dandelion", 1), ("lilac", 1)], 9,
                  mode(F_FOREST, "outside"))
FL_MEADOW = flowers("meadow", [("cornflower", 3), ("allium", 2), ("azure_bluet", 2), ("oxeye_daisy", 2), ("poppy", 2),
                               ("lilac", 1)], 16)
FL_FOREST = flowers("forest", [("lily_of_the_valley", 3), ("poppy", 1), ("cornflower", 1)], 5, mode(F_FOREST, "outside"))
FL_SWAMP = flowers("swamp", [("blue_orchid", 4), ("lily_of_the_valley", 1)], 8, F_DRIFT)
FL_COLD_MARSH = flowers("cold_marsh", [("azure_bluet", 2), ("lily_of_the_valley", 2), ("blue_orchid", 1)], 5, F_DRIFT)
FL_HEARTLANDS = flowers("heartlands", [("poppy", 2), ("dandelion", 2), ("oxeye_daisy", 2), ("red_tulip", 1),
                                       ("white_tulip", 1), ("sunflower", 1)], 12)
FL_WEALD = flowers("weald", [("allium", 3), ("pink_tulip", 2), ("lilac", 2), ("dandelion", 1)], 12)
FL_LAVENDER = flowers("lavender", [("lilac", 2), ("allium", 5)], 10, field(118, 22, 0.3, 0.1), floor=0.0,
                      cluster=(5, 10), spread=4)
FL_GOLD_COAST = flowers("gold_coast", [("dandelion", 3), ("orange_tulip", 2), ("oxeye_daisy", 2), ("sunflower", 1)], 9)
FL_ASCADIAN = flowers("ascadian", [("pink_tulip", 3), ("allium", 3), ("dandelion", 2), ("azure_bluet", 1),
                                   ("peony", 1)], 14)
FL_ASH = flowers("ash", [("dead_bush", 3), ("wither_rose", 1)], 2, F_RING)
FL_SAVANNA = flowers("savanna", [("orange_tulip", 2), ("dandelion", 3), ("red_tulip", 1)], 5)
FL_JUNGLE = flowers("jungle", [("blue_orchid", 2), ("allium", 1), ("red_tulip", 1), ("peony", 1)], 7)
FL_SUMMERSET = flowers("summerset", [("lily_of_the_valley", 3), ("white_tulip", 2), ("azure_bluet", 2), ("allium", 1),
                                     ("peony", 1)], 16)
FL_ROSES = flowers("roses", [("rose_bush", 2), ("peony", 2), ("poppy", 3)], 4, F_RING, cluster=(2, 6))
FL_ARTAEUM = flowers("artaeum", [("lily_of_the_valley", 3), ("allium", 2), ("pink_tulip", 2), ("torchflower", 1)], 14)
FL_HIGH_ROCK = flowers("high_rock", [("poppy", 3), ("cornflower", 2), ("oxeye_daisy", 2), ("red_tulip", 1)], 9)
FL_MOOR = flowers("moor", [("allium", 4), ("azure_bluet", 2), ("lilac", 1)], 14, field(119, 34, 0.5, 0.25),
                  cluster=(4, 9))      # heather
FL_AKAVIR = flowers("akavir", [("pink_tulip", 3), ("white_tulip", 2), ("lily_of_the_valley", 2)], 10)
FL_STEPPE = flowers("steppe", [("dandelion", 3), ("cornflower", 2), ("poppy", 1)], 6)
FL_COLD = flowers("cold", [("azure_bluet", 2), ("lily_of_the_valley", 1)], 3)

# --- the table. Giants (Graht-oaks, Hist, Telvanni and emperor parasol mushrooms) keep their Java features.
FOREST_FLOOR = [C_FOREST_FLOOR, C_MOSS, C_MUSHROOMS]
FLORA.update({
    # Skyrim
    "whiterun_tundra": [R_TUNDRA, T_WHITERUN, C_TUNDRA_GRASS, FL_TUNDRA, C_SNOWBERRIES_FEW],
    "whiterun_plains": [R_TUNDRA_FEW, T_WHITERUN, C_PLAINS_GRASS, FL_TUNDRA, FL_MOUNTAIN_FEW, C_PUMPKINS],
    "pine_forest": [R_MOSSY, T_PINE, T_PINE_UNDER, T_PINE_DEAD, C_TAIGA_FLOOR, FL_MOUNTAIN_FEW, C_MOSS, C_MUSHROOMS,
                    C_SNOWBERRIES_FEW],
    "snowy_pine_forest": [R_TUNDRA_FEW, T_SNOWY, T_SNOWY_UNDER, T_SNOWY_DEAD, C_SNOW_GRASS, C_SNOWBERRIES, FL_COLD],
    "falkreath_forest": [R_MOSSY, T_FALKREATH, T_FALKREATH_UNDER, T_FALKREATH_DEAD, C_TAIGA_FLOOR, C_MOSS_DENSE,
                         C_MUSHROOMS, C_NIGHTSHADE, FL_FOREST],
    "rift_autumn_forest": [R_TUNDRA_FEW, T_RIFT, T_RIFT_UNDER, T_RIFT_DEAD, C_FOREST_FLOOR, FL_RIFT, FL_LAVENDER,
                           C_MUSHROOMS],
    "reach_crags": [R_REACH, R_REACH_STANDING, T_REACH, C_DRY_GRASS, FL_MOUNTAIN_FEW],
    "reach_valley": [R_MOSSY, T_REACH_VALLEY, C_PLAINS_GRASS, FL_MOUNTAIN, C_REEDS_FEW],
    "hjaalmarch_marsh": [T_HJAAL, T_HJAAL_DEAD, C_REEDS, C_SWAMP_GRASS, FL_COLD_MARSH, C_LILIES,
                         "minecraft:seagrass_swamp"],
    "eastmarch_volcanic_tundra": [R_TUNDRA, T_VOLC_TUNDRA, C_TUNDRA_GRASS, FL_TUNDRA, C_SNOWBERRIES_FEW],
    "eastmarch_hot_springs": [R_CALCITE, T_HOT_SPRINGS, C_REEDS_FEW, C_DRY_GRASS, FL_TUNDRA],
    "pale_snowfield": [R_ICE, T_SNOWY_SPARSE, C_SNOW_GRASS, C_SNOWBERRIES_FEW, FL_COLD],
    "winterhold_glacier": [R_ICE],
    "throat_of_the_world": [R_ICE],
    "snowy_slopes": [R_ICE, T_SLOPES],
    "alpine_meadow": [R_TUNDRA_FEW, T_ALPINE, C_PLAINS_GRASS, FL_MEADOW],
    "skyrim_highlands": [R_TUNDRA, T_HIGHLAND, C_TUNDRA_GRASS, FL_MOUNTAIN_FEW, C_SNOWBERRIES_FEW],
    "skyrim_coast": [R_GREY, C_DRY_GRASS],
    "frozen_shore": [R_ICE],
    "skyrim_river": [C_REEDS_FEW, "minecraft:seagrass_river"],
    "skyrim_lake": [C_REEDS_FEW, C_LILIES, "minecraft:seagrass_river"],
    "solstheim_ashlands": [R_VOLCANIC, T_SOLS_ASH, FL_ASH, C_ASH_SPARSE, "ash_drifts"],
    "solstheim_pine_forest": [R_TUNDRA_FEW, T_SOLS_PINE, T_SNOWY_DEAD, C_TAIGA_FLOOR, C_SNOWBERRIES, FL_COLD,
                              "ash_drifts_sparse"],
    # Cyrodiil
    "heartlands": [T_HEARTLANDS, C_LUSH_GRASS, FL_HEARTLANDS, C_PUMPKINS, "minecraft:patch_sugar_cane"],
    "great_forest": [R_MOSSY, T_GREAT_FOREST, T_GREAT_UNDER, T_GREAT_DEAD, C_NIGHTSHADE, FL_FOREST] + FOREST_FLOOR,
    "colovian_highlands": [R_GREY, T_COLOVIAN, C_TUNDRA_GRASS, FL_MOUNTAIN_FEW],
    "gold_coast": [T_GOLD_COAST, C_GRAPES, C_PLAINS_GRASS, FL_GOLD_COAST],
    "west_weald_vineyards": [T_WEALD, C_GRAPES, FL_LAVENDER, FL_WEALD, C_PLAINS_GRASS],
    "nibenay_valley": [T_NIBENAY, C_LUSH_GRASS, FL_HEARTLANDS, C_REEDS_FEW, C_LILIES, "minecraft:patch_sugar_cane"],
    "blackwood_swamp": ["hist_trees_sparse", T_BLACKWOOD, T_MARSH_DEAD, C_REEDS, C_SWAMP_GRASS, FL_SWAMP,
                        C_LILIES_DENSE, "minecraft:seagrass_swamp"],
    "jerall_foothills": [R_TUNDRA_FEW, T_JERALL, T_PINE_UNDER, T_PINE_DEAD, C_TAIGA_FLOOR, C_SNOWBERRIES_FEW,
                         FL_MOUNTAIN_FEW],
    "lake_rumare_shore": [T_RUMARE, C_REEDS, C_LUSH_GRASS, FL_HEARTLANDS, C_LILIES, "minecraft:patch_sugar_cane",
                          "minecraft:seagrass_river"],
    "cyrodiil_river": [C_REEDS_FEW, "minecraft:seagrass_river"],
    # Morrowind
    "vvardenfell_ashlands": [R_VOLCANIC, T_ASHLANDS, FL_ASH, C_ASH_GRASS, "ash_drifts"],
    "red_mountain": [R_VOLCANIC, C_ASH_SPARSE, "ash_drifts_sparse"],
    "bitter_coast_swamp": ["emperor_parasols_rare", T_BITTER, T_MARSH_DEAD, C_REEDS, C_SWAMP_GRASS, FL_SWAMP,
                           C_MUSHROOMS, C_LILIES, "minecraft:seagrass_swamp"],
    "grazelands": [R_TUNDRA_FEW, T_GRAZE, C_GRAZE_GRASS, FL_SAVANNA],
    "west_gash": [R_GREY, T_GASH, C_DRY_GRASS, FL_MOUNTAIN_FEW],
    "ascadian_isles": [T_ASCADIAN, C_LUSH_GRASS, FL_ASCADIAN, FL_LAVENDER, C_REEDS_FEW, C_LILIES,
                       "minecraft:patch_sugar_cane"],
    "azuras_coast": [R_GREY, "emperor_parasols_rare", T_AZURA, C_FOREST_FLOOR, FL_ASCADIAN],
    "sheogorad": [R_GREY, "emperor_parasols_sparse", T_SHEOGORAD, C_FOREST_FLOOR, C_MUSHROOMS, FL_MOUNTAIN_FEW],
    "molag_amur": [R_VOLCANIC, T_MOLAG, C_ASH_SPARSE, "ash_drifts"],
    "telvanni_mushroom_forest": ["telvanni_towers", "emperor_parasols_dense", "trees_mushroom_grove", C_FOREST_FLOOR,
                                 C_MOSS, C_MUSHROOMS, FL_ASCADIAN],
    "deshaan_plains": [T_DESHAAN, C_GRAZE_GRASS, FL_ASCADIAN, "ash_drifts_sparse"],
    "stonefalls": [R_VOLCANIC, T_STONEFALLS, C_ASH_GRASS, C_DRY_GRASS, "ash_drifts_sparse"],
    "ash_shore": [R_VOLCANIC],
    # Black Marsh
    "black_marsh_swamp": ["hist_trees_sparse", T_BLACK_MARSH, T_MARSH_DEAD, C_REEDS, FL_SWAMP, C_SWAMP_GRASS,
                          C_LILIES_DENSE, C_MUSHROOMS, "minecraft:vines", "minecraft:seagrass_swamp"],
    "deep_mire": ["hist_trees_sparse", T_DEEP_MIRE, T_MARSH_DEAD, C_REEDS, C_SWAMP_GRASS, C_LILIES_DENSE,
                  "minecraft:vines", "minecraft:seagrass_swamp"],
    "hist_grove": ["hist_trees_dense", T_HIST_GROVE, C_REEDS_FEW, FL_SWAMP, C_JUNGLE_FLOOR, C_MOSS, C_LILIES,
                   "minecraft:seagrass_swamp"],
    "mangrove_delta": [T_MANGROVE, C_REEDS, C_SWAMP_GRASS, C_LILIES, "minecraft:seagrass_swamp"],
    "shadowfen": [T_SHADOWFEN, T_MARSH_DEAD, C_REEDS, C_SWAMP_GRASS, C_MUSHROOMS, C_NIGHTSHADE, C_LILIES,
                  "minecraft:seagrass_swamp"],
    "murkmire": ["hist_trees_sparse", T_MURKMIRE, C_REEDS, FL_JUNGLE, C_JUNGLE_FLOOR, C_LILIES_DENSE, "minecraft:vines",
                 "minecraft:seagrass_swamp"],
    "murky_river": [C_REEDS_FEW, C_LILIES, "minecraft:seagrass_swamp"],
    # Elsweyr
    "anequina_badlands": [R_RED, T_BADLANDS, C_DESERT_SCRUB],
    "elsweyr_dunes": [C_DESERT_SCRUB],
    "elsweyr_savanna": [R_RED, T_SAVANNA, C_SAVANNA_GRASS, FL_SAVANNA],
    "pellitine_jungle": [T_PELLITINE, T_JUNGLE_UNDER, C_JUNGLE_FLOOR, FL_JUNGLE, "minecraft:vines",
                         "minecraft:patch_melon"],
    "sugar_fields": [T_SUGAR, "moon_sugar", C_SAVANNA_GRASS, FL_SAVANNA, "minecraft:patch_sugar_cane"],
    "elsweyr_canyons": [R_RED, T_CANYONS, C_DESERT_SCRUB],
    # Valenwood
    "graht_oak_forest": ["graht_oaks_dense", T_VALENWOOD, T_JUNGLE_UNDER, T_VALEN_DEAD, C_JUNGLE_FLOOR, FL_JUNGLE, C_MOSS,
                         C_MUSHROOMS, "minecraft:vines"],
    "valenwood_rainforest": ["graht_oaks_sparse", T_RAINFOREST, T_JUNGLE_UNDER, T_VALEN_DEAD, C_JUNGLE_FLOOR, FL_JUNGLE,
                             "minecraft:vines", "minecraft:patch_melon"],
    "valenwood_mangrove_coast": [T_VALEN_MANGROVE, C_REEDS, C_SWAMP_GRASS, C_LILIES, "minecraft:seagrass_swamp"],
    "reapers_march": ["graht_oaks_sparse", T_REAPERS, T_TEMPERATE_DEAD, C_SAVANNA_GRASS, FL_SAVANNA],
    "jungle_river": [C_REEDS_FEW, C_LILIES, "minecraft:seagrass_river"],
    # Summerset
    "crystal_coast": [R_CALCITE, T_CRYSTAL, C_CRYSTALS, FL_SUMMERSET, C_PLAINS_GRASS],
    "summerset_meadow": [T_SUMMERSET, FL_ROSES, FL_LAVENDER, FL_SUMMERSET, C_CRYSTALS, C_PLAINS_GRASS],
    "auridon_vineyards": [T_AURIDON, C_GRAPES, FL_LAVENDER, FL_WEALD, C_PLAINS_GRASS],
    "artaeum": [R_CALCITE, T_ARTAEUM, C_CRYSTALS, FL_ARTAEUM, C_LUSH_GRASS, C_MOSS],
    "summerset_shore": [T_SHORE_PALMS, C_CRYSTALS],
    # Hammerfell
    "alikr_desert": [R_SAND, T_DESERT, T_DESERT_SCRUB, C_DESERT_SCRUB],
    "alikr_dunes": [C_DESERT_SCRUB],
    "dragontail_mountains": [R_RED, T_DRAGONTAIL, C_DESERT_SCRUB],
    "craglorn": [R_RED, R_SAND, T_CRAGLORN, C_DRY_GRASS],
    "bangkorai": [R_GREY, T_BANGKORAI, C_PLAINS_GRASS, FL_HIGH_ROCK],
    "stros_mkai_coast": [T_STROS, FL_JUNGLE, C_SAVANNA_GRASS, "minecraft:patch_sugar_cane"],
    # High Rock
    "glenumbra_moors": [R_MOOR, T_MOORS, C_SNOWBERRIES_FEW, FL_MOOR, C_PLAINS_GRASS],
    "stormhaven": [R_MOSSY, T_STORMHAVEN, T_TEMPERATE_UNDER, T_TEMPERATE_DEAD, C_PUMPKINS, FL_HIGH_ROCK, C_FOREST_FLOOR],
    "rivenspire": [R_DARK, T_RIVENSPIRE, T_TEMPERATE_DEAD, C_TAIGA_FLOOR, C_MUSHROOMS, C_NIGHTSHADE, C_MOSS],
    "wrothgarian_mountains": [R_TUNDRA, T_WROTHGAR, T_SNOWY_DEAD, C_SNOWBERRIES, FL_COLD, C_TAIGA_FLOOR],
    "wayrest_farmland": [T_WAYREST, C_PUMPKINS, FL_HIGH_ROCK, C_LUSH_GRASS, "minecraft:patch_sugar_cane"],
    "bjoulsae_river": [C_REEDS_FEW, "minecraft:seagrass_river"],
    # foreign lands
    "atmoran_ice_waste": [R_ICE, T_ATMORA_WASTE],
    "atmoran_frozen_forest": [R_ICE, T_ATMORA, T_SNOWY_DEAD, C_SNOWBERRIES, C_SNOW_GRASS],
    "tsaesci_jungle": [T_TSAESCI, T_JUNGLE_UNDER, FL_JUNGLE, C_JUNGLE_FLOOR, "minecraft:vines", "minecraft:patch_melon"],
    "kamal_snowfield": [R_ICE, T_KAMAL, C_SNOW_GRASS],
    "tang_mo_islands": [T_TANG_MO, FL_AKAVIR, C_LUSH_GRASS],
    "ka_po_tun_steppe": [R_GREY, T_KA_PO_TUN, FL_STEPPE, C_SAVANNA_GRASS],
    "yokudan_desert": [R_SAND, T_DESERT, T_DESERT_SCRUB, C_CACTI, C_DESERT_SCRUB],
    "sunken_isles": [R_GREY, T_SUNKEN, C_REEDS_FEW, FL_JUNGLE, C_SAVANNA_GRASS],
    "pyandonean_jungle_isles": [T_PYANDONEA, T_JUNGLE_UNDER, FL_JUNGLE, C_JUNGLE_FLOOR, "minecraft:vines"],
    "thras_coral": [R_CORAL, T_THRAS, "dead_corals", C_SAVANNA_GRASS],
    "crystal_spires": [R_CALCITE, T_CRYSTAL_SPIRES, C_CRYSTALS, C_DRY_GRASS],
    # generic coasts and rivers
    "warm_beach": [T_SHORE_PALMS],
    "tropical_beach": [T_TROPICAL, "minecraft:patch_sugar_cane"],
    "rocky_shore": [R_GREY, R_RUBBLE],
    "warm_river": [C_REEDS_FEW, "minecraft:patch_sugar_cane", "minecraft:seagrass_river"],
})


def apply_flora():
    """Gives every biome in FLORA its flora: the old vegetation entries out, the table's in (in canonical order)."""
    for bid, plants in FLORA.items():
        assert bid in BIOMES, "flora for unknown biome " + bid
        b = BIOMES[bid]
        keep = [f for f in b["features"] if resolve_feature(f) not in OLD_VEG]
        b["features"] = keep + [f for f in plants if f not in keep]


# map_water placeholder ordering: lakes = [map_water, hot springs] then vanilla lava lakes (see canonical()).


def canonical(step):
    """The one ordered list for a step: Skycraft's lake features first (map water before lava lakes), vanilla in
    vanilla order, then Skycraft's own features."""
    van = VANILLA_ORDER[step]
    mine = MINE[step]
    if step == "lakes":
        return mine + van
    if step == "vegetal_decoration":
        return van + mine
    return van + mine



# ================================================================================================ spawns
def sp(t, w, lo=1, hi=None):
    return {"type": t if ":" in t else "minecraft:" + t, "weight": w, "minCount": lo, "maxCount": hi or lo}


def monsters(kind="std", extra=()):
    """Vanilla night monsters at reduced weights (Skycraft's own creatures come from the biome modifiers and from
    each biome's extras). No creepers: Skycraft removes them."""
    zombie = {"desert": "husk"}.get(kind, "zombie")
    skeleton = {"frozen": "stray"}.get(kind, "skeleton")
    out = [sp("spider", 60, 4), sp(zombie, 60, 4), sp(skeleton, 60, 4), sp("slime", 40, 4),
           sp("enderman", 6, 1, 4), sp("witch", 8 if kind == "swamp" else 3, 1)]
    return out + list(extra)


BAT = [sp("bat", 10, 8)]
GLOW_SQUID = [sp("glow_squid", 10, 4, 6)]

# creature profiles by region feel
C_SKYRIM_PLAINS = [sp("rabbit", 4, 2, 3), sp("fox", 3, 2, 3), sp("horse", 1, 2, 4), sp("wolf", 2, 2, 4)]
C_SKYRIM_FOREST = [sp("rabbit", 4, 2, 3), sp("fox", 6, 2, 4), sp("wolf", 6, 2, 4)]
C_SKYRIM_SNOW = [sp("rabbit", 4, 2, 3), sp("fox", 6, 2, 4), sp("wolf", 4, 2, 4)]
C_PEAK = [sp("goat", 5, 1, 3), sp("rabbit", 2, 1, 2)]
C_GLACIER = [sp("polar_bear", 1, 1, 2), sp("rabbit", 3, 1, 2), sp("fox", 2, 1, 2)]
C_FARM = [sp("sheep", 8, 2, 4), sp("cow", 6, 2, 4), sp("pig", 6, 2, 4), sp("chicken", 6, 2, 4), sp("horse", 2, 2, 4),
          sp("rabbit", 3, 2, 3)]
C_TEMPERATE_FOREST = [sp("rabbit", 4, 2, 3), sp("fox", 3, 2, 3), sp("wolf", 4, 2, 4), sp("pig", 4, 2, 4),
                      sp("chicken", 3, 2, 4)]
C_HIGHLAND = [sp("goat", 3, 1, 3), sp("sheep", 5, 2, 4), sp("rabbit", 3, 2, 3), sp("llama", 2, 2, 4)]
C_MORROWIND = [sp("rabbit", 2, 1, 2)]
C_GRAZE = [sp("llama", 3, 2, 4), sp("rabbit", 3, 1, 3)]        # wild guar herds stand-in
C_ASCADIAN = [sp("chicken", 4, 2, 4), sp("sheep", 3, 2, 4), sp("rabbit", 3, 1, 3)]
C_SWAMP = [sp("frog", 10, 2, 5), sp("chicken", 2, 2, 3)]
C_DESERT = [sp("rabbit", 4, 2, 3)]
C_SAVANNA = [sp("horse", 1, 2, 6), sp("donkey", 1, 1, 1), sp("llama", 2, 2, 4), sp("rabbit", 3, 2, 3),
             sp("chicken", 2, 2, 3)]
C_JUNGLE = [sp("parrot", 40, 1, 2), sp("ocelot", 2, 1, 3), sp("chicken", 6, 2, 4), sp("pig", 4, 2, 4),
            sp("frog", 4, 2, 4)]
C_VALENWOOD = [sp("parrot", 20, 1, 2), sp("ocelot", 2, 1, 2), sp("pig", 6, 2, 4), sp("fox", 3, 2, 3),
               sp("chicken", 4, 2, 4)]
C_SUMMERSET = [sp("sheep", 6, 2, 4), sp("rabbit", 4, 2, 3), sp("horse", 2, 2, 4), sp("chicken", 3, 2, 4)]
C_HAMMERFELL = [sp("horse", 2, 2, 5), sp("donkey", 2, 1, 2), sp("goat", 2, 1, 2), sp("rabbit", 3, 2, 3)]
C_TSAESCI = [sp("panda", 2, 1, 2), sp("parrot", 30, 1, 2), sp("ocelot", 2, 1, 2), sp("chicken", 4, 2, 4)]
C_STEPPE = [sp("horse", 5, 2, 6), sp("llama", 3, 2, 4), sp("rabbit", 4, 2, 3), sp("donkey", 1, 1, 2)]
C_COAST_WARM = [sp("turtle", 5, 2, 5)]

W_RIVER = [sp("squid", 2, 1, 4)]
WA_RIVER = [sp("salmon", 5, 1, 5)]
W_COLD = [sp("squid", 3, 1, 4)]
WA_COLD = [sp("cod", 15, 3, 6), sp("salmon", 15, 1, 5)]
W_TEMPERATE = [sp("squid", 4, 1, 4), sp("dolphin", 1, 1, 2)]
WA_TEMPERATE = [sp("cod", 10, 3, 6)]
W_WARM = [sp("squid", 10, 1, 4), sp("dolphin", 2, 1, 2)]
WA_WARM = [sp("tropical_fish", 25, 8), sp("pufferfish", 15, 1, 3)]
WA_SWAMP = [sp("tropical_fish", 25, 8)]

# Skycraft creatures that no Phase 1 biome modifier covers
X_FROSTBITE = [sp("skycraft:frostbite_spider", 6, 1, 2)]
X_FROSTBITE_CAVE = [sp("skycraft:frostbite_spider", 15, 1, 3)]
X_FALMER = [sp("skycraft:falmer", 30, 1, 3), sp("skycraft:frostbite_spider", 10, 1, 2)]
X_DWEMER = [sp("skycraft:falmer", 15, 1, 2)]
X_HAGRAVEN = [sp("skycraft:hagraven", 2, 1, 1)]
X_VAMPIRE = [sp("skycraft:vampire", 2, 1, 1)]
X_NECRO = [sp("skycraft:necromancer", 2, 1, 1)]
X_ICE_CAVE = [sp("skycraft:ice_wraith", 6, 1, 1), sp("skycraft:frostbite_spider", 10, 1, 2)]


# ======================================================================================== surface rules
def SB(name, **props):
    if isinstance(name, dict):
        return {"type": "minecraft:block", "result_state": name}
    return {"type": "minecraft:block", "result_state": st(name, **props)}


def seq(*rules):
    rules = [r for r in rules if r is not None]
    return rules[0] if len(rules) == 1 else {"type": "minecraft:sequence", "sequence": rules}


def cond(c, then):
    return {"type": "minecraft:condition", "if_true": c, "then_run": then}


def stone_depth(surface="floor", add=False, secondary=0, offset=0):
    return {"type": "minecraft:stone_depth", "offset": offset, "add_surface_depth": add,
            "secondary_depth_range": secondary, "surface_type": surface}


ON_FLOOR = stone_depth()
UNDER_FLOOR = stone_depth(add=True)
ON_CEILING = stone_depth("ceiling")


def deep_under(rng):
    return stone_depth(add=True, secondary=rng)


def water_c(offset, mul=0, add=False):
    return {"type": "minecraft:water", "offset": offset, "surface_depth_multiplier": mul, "add_stone_depth": add}


def y_above(y, mul=0, add=False):
    return {"type": "minecraft:y_above", "anchor": {"absolute": y}, "surface_depth_multiplier": mul,
            "add_stone_depth": add}


def noise(name, lo, hi=MAX):
    return {"type": "minecraft:noise_threshold", "noise": name if ":" in name else "minecraft:" + name,
            "min_threshold": lo, "max_threshold": hi}


def NOT(c):
    return {"type": "minecraft:not", "invert": c}


def biome_is(ids):
    return {"type": "minecraft:biome", "biome_is": ids}


STEEP = {"type": "minecraft:steep"}
HOLE = {"type": "minecraft:hole"}
ABOVE_PRELIM = {"type": "minecraft:above_preliminary_surface"}
BANDLANDS = {"type": "minecraft:bandlands"}


def block_of(b):
    return SB(b)


def surface(top="grass_block", under="dirt", patches=(), steep=None, steep_under=None, snow=None, rock=None,
            uw="gravel", uw_patches=(), uw_under=None, deep=None, ceiling=None, swamp=None):
    """A biome's surface preset as plain data; build_surface turns it into a rule. patches are
    (noise, min, max, block) and apply to the dry top layer; snow is (y, depth multiplier) for a snowline;
    rock is (y, block, patches) for bare rock above a height; uw is the floor under water."""
    return {"top": top, "under": under, "patches": [list(p) for p in patches], "steep": steep,
            "steep_under": steep_under, "snow": snow, "rock": rock, "uw": uw,
            "uw_patches": [list(p) for p in uw_patches], "uw_under": uw_under, "deep": deep, "ceiling": ceiling,
            "swamp": swamp}


def tweak(preset, **kw):
    p = json.loads(json.dumps(preset))
    p.update(kw)
    return p


def natural(lo, hi):
    """Turns a noise band into a one-sided threshold. A band between two thresholds follows the noise's contour lines,
    so it draws thin rings and worm-like stripes repeated across the land; one-sided thresholds give blobs and drifts.
    Wide bands round zero (organic mazes of mud and moss) stay as they are."""
    if hi >= MAX or lo <= -MAX or (lo < 0 < hi and hi - lo >= 0.3):
        return lo, hi
    if hi - lo <= 0.15 and lo <= 0.0 <= hi:      # a hairline round zero: a few scattered blobs instead
        return 0.62, MAX
    if lo >= 0:
        return lo + 0.6 * (hi - lo), MAX
    return -MAX, hi - 0.6 * (hi - lo)


def patch_rules(patches):
    return [cond(noise(n, *natural(lo, hi)), block_of(b)) for n, lo, hi, b in patches]


def steep_rule(blk):
    """Cliffs and steep slopes of rock: bare rock with scree and darker bands, not one flat colour. "snowy_stone" (the
    high peaks) is the same rock with snow clinging to about half of it."""
    if blk == "snowy_stone":
        return seq(cond(noise("surface", -0.05, MAX), SB("snow_block")), steep_rule("stone"))
    if blk not in ("stone", "mossy_cobblestone"):
        return block_of(blk)
    return seq(cond(noise("gravel", 0.52, MAX), SB("gravel")),
               cond(noise("surface", -MAX, -0.55), SB("andesite")),
               cond(noise("calcite", 0.6, MAX), SB("tuff")),
               block_of(blk))


def build_surface(p):
    if p.get("badlands"):
        return badlands_rule(p)
    out = []
    if p["swamp"] is not None:
        out.append(cond(ON_FLOOR, cond(y_above(p["swamp"]), cond(NOT(y_above(63)), cond(
            noise("surface_swamp", 0.0), SB("water", level=0))))))
    if p["ceiling"]:
        out.append(cond(ON_CEILING, block_of(p["ceiling"])))
    dry = []
    if p["snow"]:
        y, mul = p["snow"]
        dry.append(cond(y_above(y, mul), seq(
            cond(STEEP, steep_rule("stone")),
            cond(noise("powder_snow", *natural(0.45, 0.58)), SB("powder_snow")),
            cond(noise("packed_ice", 0.72, MAX), SB("packed_ice")),
            SB("snow_block"))))
    if p["rock"]:
        y, blk, rp = p["rock"]
        dry.append(cond(y_above(y, 2), seq(*patch_rules(rp), block_of(blk))))
    if p["steep"]:
        dry.append(cond(STEEP, steep_rule(p["steep"])))
    dry += patch_rules(p["patches"])
    dry.append(block_of(p["top"]))
    out.append(cond(ON_FLOOR, cond(water_c(-1), seq(*dry))))
    sub = []
    if p["snow"]:
        y, mul = p["snow"]
        sub.append(cond(y_above(y, mul), SB("snow_block")))
    if p["rock"]:
        sub.append(cond(y_above(p["rock"][0], 2), block_of(p["rock"][1])))
    if p["steep_under"] or p["steep"]:
        sub.append(cond(STEEP, block_of(p["steep_under"] or p["steep"])))
    sub.append(block_of(p["under"]))
    out.append(cond(water_c(-6, -1, True), cond(UNDER_FLOOR, seq(*sub))))
    out.append(cond(ON_FLOOR, seq(*patch_rules(p["uw_patches"]), block_of(p["uw"]))))
    if p["uw_under"]:
        out.append(cond(UNDER_FLOOR, block_of(p["uw_under"])))
    if p["deep"]:
        rng, blk = p["deep"]
        out.append(cond(deep_under(rng), block_of(blk)))
    return seq(*out)


def badlands(sand="red_sand", band_y=74):
    return {"badlands": True, "sand": sand, "band_y": band_y}


def badlands_rule(p):
    sand, band_y = p["sand"], p["band_y"]
    terr = [cond(noise("surface", -0.909, -0.5454), SB("terracotta")),
            cond(noise("surface", -0.1818, 0.1818), SB("terracotta")),
            cond(noise("surface", 0.5454, 0.909), SB("terracotta")), BANDLANDS]
    return seq(
        cond(ON_FLOOR, seq(
            cond(y_above(256), SB("orange_terracotta")),
            cond(y_above(band_y, 1, True), seq(*terr)),
            cond(water_c(-1), seq(cond(ON_CEILING, SB("red_sandstone")), SB(sand))),
            cond(NOT(HOLE), SB("orange_terracotta")),
            cond(water_c(-6, -1, True), SB("white_terracotta")),
            seq(cond(ON_CEILING, SB("stone")), SB("gravel")))),
        cond(y_above(63, -1, True), seq(
            cond(y_above(63), cond(NOT(y_above(band_y, 1, True)), SB("orange_terracotta"))),
            BANDLANDS)),
        cond(UNDER_FLOOR, cond(water_c(-6, -1, True), SB("white_terracotta"))))


def cave_surface(floor, floor_patches=(), ceiling=None, ceiling_patches=(), under=None):
    """Cave biomes: the floors (and ceilings) of their caves, anywhere below the preliminary surface."""
    return {"cave": True, "floor": floor, "floor_patches": [list(p) for p in floor_patches], "ceiling": ceiling,
            "ceiling_patches": [list(p) for p in ceiling_patches], "under": under}


def build_cave_floor(p):
    """Applied inside UNDER_FLOOR (the floor layer of a cave): the floor block and what lies under it."""
    floor = cond(ON_FLOOR, seq(*patch_rules(p["floor_patches"]), block_of(p["floor"])))
    return seq(floor, block_of(p["under"])) if p["under"] else floor


def build_cave_ceiling(p):
    return seq(*patch_rules(p["ceiling_patches"]), block_of(p["ceiling"]))


ASH = st(SK + ":ash_block")
VOLCANIC = st(SK + ":volcanic_rock")
CRACKED = st(SK + ":cracked_volcanic_rock")
MAGMA_ROCK = st(SK + ":magma_volcanic_rock")
DUNE = st(SK + ":dune_sand")
CORAL_STONE = st(SK + ":coral_stone")
FROSTED = st(SK + ":frosted_stone")
CRYSTAL = st(SK + ":crystal_block")
GRASS = st("grass_block", snowy=False)
PODZOL = st("podzol", snowy=False)
MYCELIUM = st("mycelium", snowy=False)

# Surface presets by terrain family
S_TUNDRA = surface(GRASS, patches=[("surface", 0.55, MAX, "coarse_dirt"), ("gravel", 0.6, MAX, "gravel"),
                                   ("calcite", -0.02, 0.02, "stone")], steep="stone", snow=(310, 4),
                   rock=(238, "stone", [("gravel", 0.3, MAX, "gravel"), ("surface", -0.3, 0.1, "andesite")]))
S_PLAINS_SKYRIM = surface(GRASS, patches=[("surface", 0.75, MAX, "coarse_dirt")], steep="stone", snow=(310, 4))
S_PINE = surface(GRASS, patches=[("surface", 0.35, 0.7, PODZOL), ("surface", 0.85, MAX, "coarse_dirt"),
                                 ("patch", 0.6, MAX, "mossy_cobblestone")], steep="stone", snow=(262, 4),
                 rock=(230, "stone", [("gravel", 0.4, MAX, "gravel")]))
S_SNOW_FOREST = surface(GRASS, patches=[("surface", 0.4, MAX, PODZOL)], steep="stone", snow=(182, 4))
S_FALKREATH = surface(GRASS, patches=[("surface", 0.2, 0.6, PODZOL), ("surface", -0.8, -0.45, "moss_block"),
                                      ("surface", 0.85, MAX, "coarse_dirt")], steep="mossy_cobblestone",
                      steep_under="stone", snow=(294, 4))
S_RIFT = surface(GRASS, patches=[("surface", 0.6, MAX, PODZOL), ("patch", 0.7, MAX, "coarse_dirt")], steep="stone",
                 snow=(286, 4))
S_REACH = surface(GRASS, patches=[("surface", 0.3, 0.6, "coarse_dirt"), ("gravel", 0.45, MAX, "gravel"),
                                  ("surface", -0.55, -0.3, "granite")], steep="stone",
                  rock=(235, "stone", [("surface", 0.2, MAX, "granite"), ("gravel", 0.3, MAX, "gravel")]),
                  snow=(310, 4))
S_REACH_VALLEY = surface(GRASS, patches=[("surface", 0.6, MAX, "coarse_dirt"), ("surface", -0.7, -0.5, "granite")],
                         steep="stone", uw="gravel", uw_patches=[("surface", 0.2, MAX, "sand")])
S_MARSH = surface(GRASS, patches=[("surface", -0.3, 0.1, "mud"), ("surface", 0.6, MAX, "coarse_dirt")],
                  uw="mud", uw_patches=[("surface", 0.2, MAX, "clay")], swamp=62)
S_VOLCANIC_TUNDRA = surface(GRASS, patches=[("surface", 0.45, MAX, "coarse_dirt"), ("gravel", 0.35, MAX, "gravel"),
                                            ("calcite", -0.03, 0.03, "calcite"), ("patch", 0.65, MAX, "tuff")],
                            steep="stone", snow=(278, 4))
S_HOT_SPRINGS = surface(GRASS, patches=[("calcite", -0.05, 0.05, "calcite"), ("gravel", 0.3, MAX, "gravel"),
                                        ("surface", 0.5, MAX, "coarse_dirt"), ("patch", 0.6, MAX, "diorite")],
                        steep="stone", uw="calcite", snow=(278, 4))
S_SNOWFIELD = surface("snow_block", "snow_block", patches=[("powder_snow", 0.45, 0.55, "powder_snow"),
                                                          ("packed_ice", 0.0, 0.1, "packed_ice"),
                                                          ("surface", 0.7, MAX, GRASS)], steep="stone",
                      steep_under="stone", uw="gravel")
S_GLACIER = surface("snow_block", "packed_ice", patches=[("packed_ice", -0.25, 0.25, "packed_ice"),
                                                         ("ice", 0.4, MAX, "blue_ice"),
                                                         ("powder_snow", 0.45, 0.6, "powder_snow")],
                    steep="packed_ice", steep_under="packed_ice", uw="gravel", uw_under="packed_ice")
# The Throat's summit and the high slopes share one look (the biome border between them runs along 4-block cells, so
# any difference shows as rectangles): wind-packed snow and powder drifts and bare rock on the crags and ribs
# (no ice: the old packed-ice patches showed as blue rectangles).
S_PEAK = surface("snow_block", "snow_block", patches=[("powder_snow", 0.35, 0.6, "powder_snow"),
                                                     ("surface", 0.55, MAX, "stone"),
                                                     ("surface", -0.7, -0.55, FROSTED)], steep="snowy_stone",
                 steep_under="stone", uw="stone")
S_SLOPES = surface("snow_block", "snow_block", patches=[("powder_snow", 0.45, 0.58, "powder_snow"),
                                                       ("surface", 0.6, MAX, "stone"),
                                                       ("calcite", -0.0125, 0.0125, "stone")],
                   steep="snowy_stone", steep_under="stone", uw="stone")
S_ALPINE = surface(GRASS, patches=[("surface", 0.7, MAX, "coarse_dirt"), ("calcite", -0.0125, 0.0125, "stone")],
                   steep="stone", snow=(294, 3))
S_HIGHLANDS = surface(GRASS, patches=[("surface", 0.35, MAX, "coarse_dirt"), ("gravel", 0.25, MAX, "gravel"),
                                      ("surface", -0.6, -0.35, "stone")], steep="stone", snow=(254, 4),
                      rock=(198, "stone", [("gravel", 0.2, MAX, "gravel"), ("surface", 0.5, MAX, "andesite")]))
S_GRAVEL_COAST = surface("gravel", "gravel", patches=[("surface", 0.3, MAX, "stone"),
                                                     ("patch", 0.65, MAX, "cobblestone")], uw="gravel", steep="stone")
S_FROZEN_SHORE = surface("snow_block", "gravel", patches=[("surface", 0.2, MAX, "gravel"),
                                                         ("packed_ice", 0.0, 0.15, "packed_ice")],
                         uw="gravel", steep="stone")
S_RIVER_COLD = surface(GRASS, uw="gravel", uw_patches=[("surface", 0.3, MAX, "sand"), ("surface", -0.6, -0.3, "clay")],
                       steep="stone")
S_ASH = surface(ASH, ASH, patches=[("surface", 0.5, MAX, VOLCANIC), ("gravel", 0.55, MAX, "gravel"),
                                    ("patch", 0.7, MAX, CRACKED)], steep=VOLCANIC, uw=ASH, deep=(6, VOLCANIC),
                ceiling=VOLCANIC)
S_SOLSTHEIM_ASH = surface(ASH, ASH, patches=[("surface", 0.55, MAX, VOLCANIC), ("surface", -0.75, -0.45, "snow_block"),
                                              ("gravel", 0.6, MAX, "gravel")], steep=VOLCANIC, uw="gravel",
                          deep=(5, VOLCANIC), snow=(230, 3), ceiling=VOLCANIC)
S_RED_MOUNTAIN = surface(VOLCANIC, VOLCANIC, patches=[("netherrack", 0.55, MAX, MAGMA_ROCK),
                                                      ("surface", 0.4, MAX, CRACKED),
                                                      ("surface", -0.7, -0.3, ASH), ("patch", 0.7, MAX, "blackstone")],
                         steep=VOLCANIC, uw=VOLCANIC, deep=(20, VOLCANIC))
S_MOLAG_AMUR = surface(ASH, VOLCANIC, patches=[("surface", 0.25, MAX, VOLCANIC), ("netherrack", 0.7, MAX, MAGMA_ROCK),
                                               ("patch", 0.6, MAX, CRACKED), ("gravel", 0.6, MAX, "blackstone")],
                       steep=VOLCANIC, uw=VOLCANIC, deep=(10, VOLCANIC), ceiling=VOLCANIC)
S_STONEFALLS = surface(GRASS, patches=[("surface", 0.3, MAX, ASH), ("surface", -0.7, -0.4, VOLCANIC),
                                       ("gravel", 0.5, MAX, "coarse_dirt")], steep=VOLCANIC, deep=(4, VOLCANIC))
S_ASH_SHORE = surface(ASH, ASH, patches=[("surface", 0.3, MAX, "gravel"), ("patch", 0.6, MAX, VOLCANIC)],
                      uw="gravel", steep=VOLCANIC, deep=(4, VOLCANIC), ceiling=VOLCANIC)
S_BITTER = surface(GRASS, patches=[("surface", -0.2, 0.25, "mud"), ("surface", 0.6, MAX, PODZOL),
                                   ("patch", 0.6, MAX, "moss_block")], uw="mud",
                   uw_patches=[("surface", 0.3, MAX, "clay")], swamp=62, steep="stone")
S_GRAZE = surface(GRASS, patches=[("surface", 0.6, MAX, "coarse_dirt"), ("surface", -0.8, -0.55, ASH)], steep=VOLCANIC)
S_WEST_GASH = surface(GRASS, patches=[("surface", 0.3, MAX, "coarse_dirt"), ("gravel", 0.4, MAX, "gravel"),
                                      ("surface", -0.6, -0.3, VOLCANIC)], steep="stone",
                      rock=(205, "stone", [("surface", 0.3, MAX, VOLCANIC)]))
S_ASCADIAN = surface(GRASS, patches=[("surface", 0.8, MAX, "coarse_dirt")], uw="sand",
                     uw_patches=[("surface", 0.3, MAX, "clay")])
S_AZURA = surface(GRASS, patches=[("surface", 0.0, MAX, "stone"), ("gravel", 0.4, MAX, "gravel"),
                                  ("patch", 0.6, MAX, "moss_block")], steep="stone", uw="gravel")
S_SHEOGORAD = surface(GRASS, patches=[("surface", 0.1, MAX, "stone"), ("surface", -0.5, -0.2, "moss_block"),
                                      ("gravel", 0.5, MAX, "gravel")], steep="stone", uw="gravel")
S_TELVANNI = surface(GRASS, patches=[("surface", 0.3, MAX, MYCELIUM), ("surface", -0.5, -0.1, "moss_block"),
                                     ("patch", 0.65, MAX, PODZOL)], steep="stone", uw="sand")
S_DESHAAN = surface(GRASS, patches=[("surface", 0.5, MAX, "coarse_dirt"), ("surface", -0.8, -0.6, ASH),
                                    ("patch", 0.7, MAX, "red_sand")], steep=VOLCANIC)
S_SWAMP = surface(GRASS, patches=[("surface", -0.15, 0.25, "mud"), ("surface", 0.5, MAX, "moss_block")],
                  uw="mud", uw_patches=[("surface", 0.4, MAX, "clay")], swamp=62)
S_DEEP_MIRE = surface("mud", "mud", patches=[("surface", 0.3, MAX, "moss_block"), ("patch", 0.6, MAX, GRASS)],
                      uw="mud", swamp=60)
S_MANGROVE = surface("mud", "mud", patches=[("surface", 0.6, MAX, GRASS)], uw="mud",
                     uw_patches=[("surface", 0.4, MAX, "clay")], swamp=60)
S_HIST = surface("moss_block", "dirt", patches=[("surface", -0.2, 0.2, "mud"), ("surface", 0.6, MAX, PODZOL)],
                 uw="mud", swamp=62)
S_DUNES = surface(DUNE, DUNE, patches=[("surface", 0.7, MAX, "sand")], ceiling="sandstone", uw="sand",
                  deep=(30, "sandstone"))
S_ALIKR = surface("sand", "sand", patches=[("surface", 0.45, MAX, DUNE), ("surface", -0.7, -0.45, "sandstone"),
                                           ("gravel", 0.65, MAX, "red_sand")], ceiling="sandstone", uw="sand",
                  deep=(30, "sandstone"), steep="sandstone")
S_YOKUDA = surface("sand", "sand", patches=[("surface", 0.3, MAX, DUNE), ("gravel", 0.55, MAX, "red_sand"),
                                            ("surface", -0.8, -0.6, "sandstone")], ceiling="sandstone", uw="sand",
                   deep=(30, "sandstone"), steep="sandstone")
S_SAVANNA = surface(GRASS, patches=[("surface", 0.5, MAX, "coarse_dirt"), ("surface", -0.75, -0.5, "red_sand")],
                    steep="terracotta")
S_JUNGLE = surface(GRASS, patches=[("surface", 0.5, MAX, PODZOL), ("surface", -0.5, -0.3, "moss_block")],
                   uw="mud", steep="mossy_cobblestone", steep_under="stone")
S_SUGAR = surface(GRASS, patches=[("surface", 0.5, MAX, "coarse_dirt")], uw="sand")
S_GRAHT = surface(GRASS, patches=[("surface", 0.15, 0.55, PODZOL), ("surface", -0.6, -0.25, "moss_block"),
                                  ("patch", 0.65, MAX, "rooted_dirt")], uw="mud", steep="mossy_cobblestone",
                  steep_under="stone")
S_TEMPERATE = surface(GRASS, steep="stone", uw="gravel",
                      uw_patches=[("surface", 0.3, MAX, "sand"), ("surface", -0.5, -0.2, "clay")])
S_FOREST = surface(GRASS, patches=[("surface", 0.6, MAX, PODZOL), ("surface", -0.85, -0.6, "coarse_dirt")],
                   steep="stone")
S_DARK_FOREST = surface(GRASS, patches=[("surface", 0.3, 0.7, PODZOL), ("surface", -0.7, -0.4, "moss_block"),
                                        ("surface", 0.85, MAX, "coarse_dirt")], steep="mossy_cobblestone",
                        steep_under="stone")
S_HIGHLANDS_TEMPERATE = surface(GRASS, patches=[("surface", 0.45, MAX, "coarse_dirt"), ("gravel", 0.4, MAX, "gravel")],
                                steep="stone", snow=(278, 4),
                                rock=(214, "stone", [("gravel", 0.3, MAX, "gravel"), ("surface", 0.5, MAX, "andesite")]))
S_WEALD = surface(GRASS, patches=[("surface", 0.65, MAX, "coarse_dirt"), ("patch", 0.7, MAX, "rooted_dirt")],
                  steep="stone")
S_GOLD_COAST = surface(GRASS, patches=[("surface", 0.55, MAX, "coarse_dirt"), ("surface", -0.8, -0.5, "sand")],
                       steep="stone", uw="sand")
S_BEACH = surface("sand", "sand", ceiling="sandstone", uw="sand", deep=(6, "sandstone"))
S_WHITE_BEACH = surface("sand", "sand", patches=[("calcite", -0.04, 0.04, "calcite"), ("patch", 0.7, MAX, "diorite")],
                        ceiling="sandstone", uw="sand", deep=(6, "sandstone"))
S_ROCKY_SHORE = surface("stone", "stone", patches=[("surface", -0.2, 0.2, "gravel"), ("patch", 0.6, MAX, "andesite")],
                        uw="gravel")
S_CRYSTAL = surface(GRASS, patches=[("calcite", -0.06, 0.06, "calcite"), ("surface", 0.65, MAX, "calcite"),
                                    ("patch", 0.8, MAX, CRYSTAL)], steep="calcite", steep_under="diorite", uw="sand")
S_CRYSTAL_SPIRES = surface("calcite", "calcite", patches=[("patch", 0.6, MAX, CRYSTAL),
                                                          ("surface", -0.6, -0.3, "diorite"),
                                                          ("surface", 0.6, MAX, GRASS)], steep="calcite", uw="sand")
S_SUMMERSET = surface(GRASS, patches=[("calcite", -0.02, 0.02, "calcite")], steep="calcite", steep_under="stone",
                      uw="sand")
S_ARTAEUM = surface(GRASS, patches=[("calcite", -0.04, 0.04, "calcite"), ("surface", 0.6, MAX, "moss_block")],
                    steep="calcite", uw="sand")
S_DRAGONTAIL = surface("red_sand", "red_sand", patches=[("surface", 0.2, MAX, "terracotta"),
                                                       ("gravel", 0.5, MAX, "coarse_dirt"),
                                                       ("surface", -0.6, -0.3, "sandstone")],
                       steep="terracotta", steep_under="terracotta", ceiling="red_sandstone", deep=(10, "red_sandstone"))
S_BANGKORAI = surface(GRASS, patches=[("surface", 0.3, MAX, "coarse_dirt"), ("gravel", 0.5, MAX, "gravel"),
                                      ("surface", -0.6, -0.35, "granite")], steep="stone",
                      rock=(182, "stone", [("surface", 0.3, MAX, "granite")]), snow=(294, 4))
S_STROS = surface("sand", "sand", patches=[("surface", 0.5, MAX, GRASS), ("patch", 0.65, MAX, "sandstone")],
                  ceiling="sandstone", uw="sand", deep=(6, "sandstone"))
S_MOOR = surface(GRASS, patches=[("surface", 0.4, MAX, "coarse_dirt"), ("surface", -0.6, -0.3, PODZOL),
                                 ("gravel", 0.6, MAX, "mossy_cobblestone")], steep="stone")
S_RIVENSPIRE = surface(GRASS, patches=[("surface", 0.3, MAX, PODZOL), ("surface", -0.6, -0.3, "coarse_dirt"),
                                       ("gravel", 0.55, MAX, "gravel")], steep="stone", snow=(262, 4))
S_WROTHGAR = surface(GRASS, patches=[("surface", 0.3, MAX, "gravel"), ("surface", -0.5, -0.25, "coarse_dirt")],
                     steep="stone", snow=(265, 4), rock=(235, "stone", [("gravel", 0.2, MAX, "gravel")]))
S_FARMLAND = surface(GRASS, patches=[("surface", 0.8, MAX, "coarse_dirt")], steep="stone", uw="gravel",
                     uw_patches=[("surface", 0.0, MAX, "sand"), ("surface", -0.6, -0.3, "clay")])
S_ICE_WASTE = surface("snow_block", "packed_ice", patches=[("packed_ice", -0.3, 0.3, "packed_ice"),
                                                           ("ice", 0.35, MAX, "blue_ice"),
                                                           ("surface", 0.6, MAX, FROSTED)],
                      steep="packed_ice", steep_under=FROSTED, uw="gravel", uw_under="packed_ice")
S_KAMAL = surface("snow_block", "snow_block", patches=[("surface", 0.45, MAX, FROSTED),
                                                      ("packed_ice", 0.0, 0.15, "packed_ice"),
                                                      ("powder_snow", 0.45, 0.55, "powder_snow")],
                  steep=FROSTED, steep_under="stone", uw="gravel")
S_STEPPE = surface(GRASS, patches=[("surface", 0.55, MAX, "coarse_dirt"), ("gravel", 0.6, MAX, "gravel")],
                   steep="stone")
S_TANG_MO = surface(GRASS, patches=[("surface", 0.4, MAX, "moss_block"), ("surface", -0.7, -0.4, "sand")],
                    steep="stone", uw="sand")
S_SUNKEN = surface("sand", "sand", patches=[("surface", 0.3, MAX, GRASS), ("patch", 0.6, MAX, "mossy_cobblestone")],
                   uw="sand", ceiling="sandstone", deep=(6, "sandstone"))
S_CORAL = surface("sand", "sand", patches=[("surface", 0.35, MAX, CORAL_STONE),
                                           ("patch", 0.7, MAX, "dead_brain_coral_block"),
                                           ("surface", -0.7, -0.45, GRASS)], uw="sand",
                  uw_patches=[("surface", 0.3, MAX, CORAL_STONE)], deep=(8, CORAL_STONE), steep=CORAL_STONE,
                  ceiling=CORAL_STONE)
S_OCEAN_COLD = surface("gravel", "gravel", uw="gravel", uw_patches=[("surface", 0.4, MAX, "sand")])
S_OCEAN = surface("sand", "sand", uw="sand", uw_patches=[("surface", 0.5, MAX, "gravel"),
                                                         ("surface", -0.6, -0.4, "clay")], deep=(6, "sandstone"))
S_OCEAN_WARM = surface("sand", "sand", uw="sand", deep=(6, "sandstone"))
S_OCEAN_CORAL = surface("sand", "sand", uw="sand", uw_patches=[("surface", 0.4, MAX, CORAL_STONE)],
                        deep=(6, CORAL_STONE))
S_INNER_SEA = surface("sand", "sand", uw="sand", uw_patches=[("surface", 0.3, MAX, "gravel"),
                                                             ("surface", -0.5, -0.25, ASH)])
S_RIVER_MURKY = surface("mud", "mud", uw="mud", uw_patches=[("surface", 0.3, MAX, "clay")])
S_RIVER_WARM = surface("sand", "sand", uw="sand", uw_patches=[("surface", 0.4, MAX, "clay"),
                                                              ("surface", -0.5, -0.3, "gravel")])

C_DWEMER = cave_surface("stone", [("gravel", 0.4, MAX, "gravel"), ("surface", 0.5, MAX, "tuff"),
                                  ("patch", 0.7, MAX, "smooth_stone")])
C_FALMER = cave_surface(PODZOL, [("surface", 0.3, MAX, MYCELIUM), ("surface", -0.5, -0.1, "moss_block"),
                                 ("gravel", 0.5, MAX, "rooted_dirt")], under="dirt")
C_ICE = cave_surface("snow_block", [("packed_ice", -0.3, 0.3, "packed_ice"), ("ice", 0.4, MAX, "blue_ice"),
                                    ("powder_snow", 0.5, 0.6, "powder_snow")],
                     ceiling="packed_ice", ceiling_patches=[("surface", 0.3, MAX, "ice"),
                                                            ("surface", -0.4, -0.1, FROSTED)],
                     under="packed_ice")
C_LAVA = cave_surface(VOLCANIC, [("netherrack", 0.4, MAX, MAGMA_ROCK), ("surface", 0.3, MAX, CRACKED),
                                 ("surface", -0.6, -0.3, "blackstone")],
                      ceiling=st("basalt", axis="y"), ceiling_patches=[("surface", 0.0, MAX, VOLCANIC)], under=VOLCANIC)
C_GROTTO = cave_surface("moss_block", [("surface", 0.2, MAX, MYCELIUM), ("surface", -0.6, -0.3, PODZOL)], under="dirt")


# =============================================================================================== catalogue
BIOMES = {}       # id -> spec
LANG = {}

LAND = [MAP_WATER, "minecraft:lake_lava_underground", "minecraft:amethyst_geode", "minecraft:monster_room",
        "minecraft:monster_room_deep", "minecraft:ore_dirt", "minecraft:ore_gravel", "minecraft:ore_granite_upper",
        "minecraft:ore_granite_lower", "minecraft:ore_diorite_upper", "minecraft:ore_diorite_lower",
        "minecraft:ore_andesite_upper", "minecraft:ore_andesite_lower", "minecraft:ore_tuff",
        "minecraft:ore_coal_upper", "minecraft:ore_coal_lower", "minecraft:ore_iron_upper",
        "minecraft:ore_iron_middle", "minecraft:ore_iron_small", "minecraft:ore_gold", "minecraft:ore_gold_lower",
        "minecraft:ore_copper", "minecraft:disk_sand", "minecraft:disk_clay", "minecraft:disk_gravel",
        "minecraft:spring_water", "minecraft:spring_lava", "minecraft:glow_lichen", "minecraft:freeze_top_layer"]
SEA = [f for f in LAND if f not in ("minecraft:disk_sand", "minecraft:disk_clay", "minecraft:disk_gravel")] + \
      ["minecraft:underwater_magma"]

# What each tag token adds (besides #minecraft:is_overworld and the Skycraft land/sea/underground tags)
TOKENS = {
    "plains": ["skycraft:spawns/plains", "forge:is_plains", "c:plains"],
    "tundra": ["skycraft:spawns/tundra", "c:tundra"],
    "forest": ["minecraft:is_forest", "c:forest"],
    "dark_forest": ["skycraft:spawns/dark_forest", "forge:is_dense", "forge:is_dense/overworld"],
    "taiga": ["minecraft:is_taiga", "forge:is_coniferous", "c:taiga"],
    "snowy_forest": ["skycraft:spawns/snowy_forest"],
    "snowy": ["forge:is_snowy", "c:snowy", "minecraft:spawns_snow_foxes", "minecraft:spawns_white_rabbits"],
    "cold": ["skycraft:spawns/cold", "forge:is_cold", "forge:is_cold/overworld", "minecraft:spawns_cold_variant_frogs",
             "c:climate_cold"],
    "hot": ["forge:is_hot", "forge:is_hot/overworld", "minecraft:snow_golem_melts", "minecraft:spawns_warm_variant_frogs",
            "c:climate_hot"],
    "dry": ["forge:is_dry", "forge:is_dry/overworld", "c:climate_dry"],
    "wet": ["forge:is_wet", "forge:is_wet/overworld", "c:climate_wet"],
    "mountain": ["minecraft:is_mountain", "forge:is_mountain", "c:mountain"],
    "peak": ["forge:is_peak", "skycraft:spawns/frozen_peaks", "c:mountain_peak"],
    "slope": ["forge:is_slope", "c:mountain_slope"],
    "hill": ["minecraft:is_hill", "c:hill"],
    "reach": ["skycraft:spawns/reach"],
    "swamp": ["skycraft:spawns/swamp", "forge:is_swamp", "c:swamp", "minecraft:has_closer_water_fog",
              "minecraft:allows_surface_slime_spawns"],
    "river": ["minecraft:is_river", "c:river", "minecraft:more_frequent_drowned_spawns",
              "minecraft:reduce_water_ambient_spawns", "forge:is_water"],
    "beach": ["minecraft:is_beach", "c:beach"],
    # warm beaches are beaches but not #skycraft:spawns/coast (horkers live on cold shores only)
    "warm_beach": ["minecraft:is_beach", "c:beach"],
    "coast": ["skycraft:spawns/coast"],
    "ocean": ["minecraft:is_ocean", "c:ocean", "minecraft:water_on_map_outlines", "forge:is_water",
              "minecraft:has_structure/shipwreck", "minecraft:has_structure/ruined_portal_ocean"],
    "deep_ocean": ["minecraft:is_deep_ocean", "c:deep_ocean"],
    "frozen_ocean": ["minecraft:polar_bears_spawn_on_alternate_blocks", "minecraft:has_structure/ocean_ruin_cold",
                     "c:snowy"],
    "cold_ocean": ["minecraft:has_structure/ocean_ruin_cold"],
    "warm_ocean": ["minecraft:has_structure/ocean_ruin_warm"],
    "coral": ["minecraft:produces_corals_from_bonemeal"],
    "jungle": ["minecraft:is_jungle", "c:jungle", "forge:is_dense", "forge:is_dense/overworld",
               "minecraft:has_structure/ruined_portal_jungle"],
    "savanna": ["minecraft:is_savanna", "c:savanna"],
    "desert": ["forge:is_desert", "c:desert", "forge:is_sandy", "minecraft:spawns_gold_rabbits",
               "minecraft:has_structure/ruined_portal_desert"],
    "badlands": ["minecraft:is_badlands", "c:badlands", "forge:is_sandy"],
    "mushroom": ["forge:is_mushroom", "c:mushroom"],
    "magical": ["forge:is_magical", "c:magical"],
    "spooky": ["forge:is_spooky"],
    "wasteland": ["forge:is_wasteland", "forge:is_dead", "c:wasteland"],
    "lush": ["forge:is_lush"],
    "sparse": ["forge:is_sparse", "forge:is_sparse/overworld"],
    "plateau": ["forge:is_plateau"],
    "underground": ["forge:is_underground", "forge:is_cave", "c:underground", "skycraft:spawns/caves"],
}
LAND_STRUCTURE_TAGS = ["minecraft:has_structure/mineshaft", "minecraft:stronghold_biased_to"]


def biome(bid, name, *, temp, downfall, sky, fog, water, water_fog="#050533", features=(), surface, province,
          kind="land", grass=None, foliage=None, precip=True, frozen=False, particle=None, creature=(),
          monster=None, extra=(), ambient=None, water_creature=(), water_ambient=(), underground=None,
          tags=(), base=None):
    assert bid not in BIOMES, bid
    if monster is None:
        monster = monsters("frozen" if temp < 0.0 else "desert" if temp >= 1.5 and not precip else "std")
    if base is None:
        base = SEA if kind == "ocean" else LAND
    BIOMES[bid] = dict(id=bid, temp=temp, downfall=downfall, sky=sky, fog=fog, water=water, water_fog=water_fog,
                       features=list(base) + list(features), surface=surface, province=province, kind=kind,
                       grass=grass, foliage=foliage, precip=precip, frozen=frozen, particle=particle,
                       creature=list(creature), monster=list(monster) + list(extra),
                       ambient=list(BAT if ambient is None else ambient), water_creature=list(water_creature),
                       water_ambient=list(water_ambient),
                       underground=list(GLOW_SQUID if underground is None else underground), tags=set(tags))
    LANG["biome.skycraft." + bid] = name


def particle(kind, prob):
    return {"options": {"type": "minecraft:" + kind}, "probability": prob}


DEER = [sp("skycraft:deer", 8, 2, 4)]
OUTLAWS = [sp("skycraft:bandit", 8, 1, 3)]
DROWNED = [sp("drowned", 100, 1)]
MAGMA_CUBES = [sp("magma_cube", 10, 2, 4)]

# ------------------------------------------------------------------------------------------------- Skyrim
biome("whiterun_tundra", "Whiterun Tundra", temp=0.45, downfall=0.3, sky="#8EB4E8", fog="#C8D8EC", water="#3D57D6",
      grass="#A8A657", foliage="#8E9A4A", surface=S_TUNDRA, province="skyrim", creature=C_SKYRIM_PLAINS,
      features=["boulders_tundra_3", "trees_whiterun_scattered", "flowers_tundra", "grass_tundra", "tall_grass_sparse"],
      tags=["plains", "tundra", "sparse"])
biome("whiterun_plains", "Whiterun Plains", temp=0.55, downfall=0.4, sky="#88B2EA", fog="#CCDCEE", water="#3D62D8",
      grass="#8FAE52", foliage="#77A040", surface=S_PLAINS_SKYRIM, province="skyrim", creature=C_SKYRIM_PLAINS,
      features=["boulders_tundra_9", "trees_whiterun_scattered", "pumpkins_rare", "flowers_tundra",
                "flowers_mountain_rare", "grass_plains", "tall_grass_dense"],
      tags=["plains"])
biome("pine_forest", "Pine Forest", temp=0.3, downfall=0.6, sky="#86A6DA", fog="#BFD0E6", water="#3D57D6",
      grass="#6E9A5A", foliage="#5A7F4A", surface=S_PINE, province="skyrim", creature=C_SKYRIM_FOREST,
      extra=X_FROSTBITE, features=["boulders_mossy_3", "trees_skyrim_pine", "fallen_logs_spruce_3", "snowberries_rare",
                                   "tall_ferns", "flowers_mountain_rare", "grass_taiga", "forest_mushrooms"],
      tags=["taiga"])
biome("snowy_pine_forest", "Snowy Pine Forest", temp=-0.3, downfall=0.6, sky="#9CB2D6", fog="#D5DEEA",
      water="#3938C9", grass="#80A08A", foliage="#6C8C78", surface=S_SNOW_FOREST, province="skyrim",
      creature=C_SKYRIM_SNOW, extra=X_FROSTBITE, particle=particle("white_ash", 0.002),
      features=["boulders_tundra_9", "trees_skyrim_snowy_pine", "fallen_logs_spruce_8", "snowberries", "flowers_cold",
                "grass_snow"],
      tags=["taiga", "snowy_forest", "snowy", "cold"])
biome("falkreath_forest", "Falkreath Forest", temp=0.35, downfall=0.85, sky="#7E98C0", fog="#A9B8C8", water="#3A5CC8",
      grass="#5E8A48", foliage="#4A7038", surface=S_FALKREATH, province="skyrim", creature=C_SKYRIM_FOREST,
      extra=X_FROSTBITE, features=["boulders_mossy_3", "trees_falkreath", "fallen_logs_spruce_3", "tall_ferns",
                                   "flowers_forest", "grass_forest", "moss_carpets", "forest_mushrooms",
                                   "nightshade_shade"],
      tags=["taiga", "dark_forest", "wet"])
biome("rift_autumn_forest", "Rift Autumn Forest", temp=0.5, downfall=0.6, sky="#8AAEE0", fog="#D8D0B8",
      water="#3F6CDB", grass="#B5A040", foliage="#D89A2E", surface=S_RIFT, province="skyrim",
      creature=C_SKYRIM_FOREST, particle=particle("spore_blossom_air", 0.0015),
      features=["boulders_tundra_9", "trees_rift", "fallen_logs_birch_4", "lavender_rare", "flowers_rift",
                "grass_forest", "tall_grass_sparse", "forest_mushrooms"],
      tags=["forest"])
biome("reach_crags", "Reach Crags", temp=0.4, downfall=0.35, sky="#8FAAD0", fog="#C8C0B0", water="#3B5ACC",
      grass="#9C8A50", foliage="#A0703A", surface=S_REACH, province="skyrim", creature=C_PEAK, extra=X_HAGRAVEN,
      features=["boulders_reach_1", "trees_reach", "flowers_mountain_rare", "grass_dry"],
      tags=["reach", "mountain", "dry"])
biome("reach_valley", "Reach Valley", temp=0.5, downfall=0.5, sky="#8AACD8", fog="#C8C8B8", water="#3B62D0",
      grass="#7F9A4C", foliage="#8A8A3C", surface=S_REACH_VALLEY, province="skyrim", creature=C_SKYRIM_PLAINS,
      features=["boulders_mossy_8", "trees_reach_valley", "flowers_mountain", "grass_plains", "tall_grass_sparse",
                "reeds_sparse"],
      tags=["reach", "hill"])
biome("hjaalmarch_marsh", "Hjaalmarch Marsh", temp=0.25, downfall=0.9, sky="#8E9AAA", fog="#A8B0B0", water="#4A6058",
      water_fog="#0E1A14", grass="#6E7E58", foliage="#5E6A48", surface=S_MARSH, province="skyrim",
      creature=[sp("frog", 6, 2, 4), sp("rabbit", 2, 1, 2)], monster=monsters("swamp"),
      features=["dead_trees_oak_2", "trees_skyrim_pine_sparse", "reeds_dense", "flowers_cold", "grass_swamp",
                "lily_pads", "minecraft:seagrass_swamp"],
      tags=["swamp", "cold", "wet"])
biome("eastmarch_volcanic_tundra", "Eastmarch Volcanic Tundra", temp=0.4, downfall=0.3, sky="#96AEC8", fog="#D0D4D0",
      water="#3E8E8C", water_fog="#0A2A28", grass="#A5A060", foliage="#8F8E50", surface=S_VOLCANIC_TUNDRA,
      province="skyrim", creature=C_SKYRIM_PLAINS,
      features=["hot_springs_sparse", "boulders_tundra_3", "trees_tundra_scattered", "snowberries_rare",
                "flowers_tundra", "grass_tundra"],
      tags=["plains", "tundra", "sparse"])
biome("eastmarch_hot_springs", "Eastmarch Hot Springs", temp=0.5, downfall=0.5, sky="#A0B4C8", fog="#DADCD8",
      water="#4AA59C", water_fog="#0E3A34", grass="#94A060", foliage="#869050", surface=S_HOT_SPRINGS,
      province="skyrim", creature=C_SKYRIM_PLAINS, particle=particle("white_ash", 0.004),
      features=["hot_springs_dense", "boulders_calcite_3", "trees_tundra_scattered", "reeds_sparse", "flowers_tundra",
                "grass_tundra_sparse"],
      tags=["tundra", "wet"])
biome("pale_snowfield", "Pale Snowfield", temp=-0.5, downfall=0.5, sky="#A6B6D4", fog="#E0E6EE", water="#3938C9",
      grass="#80A090", foliage="#6C8C80", surface=S_SNOWFIELD, province="skyrim", creature=C_SKYRIM_SNOW,
      particle=particle("white_ash", 0.002),
      features=["boulders_ice_4", "trees_skyrim_snowy_sparse", "snowberries_rare", "flowers_cold", "grass_snow"],
      tags=["tundra", "snowy", "cold"])
biome("winterhold_glacier", "Winterhold Glacier", temp=-0.8, downfall=0.5, sky="#A8B8DA", fog="#E6ECF4",
      water="#3938C9", grass="#80A090", foliage="#6C8C80", surface=S_GLACIER, province="skyrim", creature=C_GLACIER,
      particle=particle("white_ash", 0.004),
      features=["spires_ice", "boulders_ice_4", "minecraft:blue_ice", "minecraft:ice_patch"],
      tags=["tundra", "snowy", "cold", "wasteland"])
biome("throat_of_the_world", "Throat of the World", temp=-0.9, downfall=0.6, sky="#B2C6F0", fog="#E8EEF8",
      water="#3938C9", surface=S_PEAK, province="skyrim", creature=C_PEAK, particle=particle("white_ash", 0.01),
      features=["boulders_frost_4"], tags=["peak", "mountain", "snowy", "cold"])
biome("snowy_slopes", "Snowy Slopes", temp=-0.4, downfall=0.6, sky="#A0B8E0", fog="#DCE6F2", water="#3938C9",
      grass="#80A090", foliage="#6C8C80", surface=S_SLOPES, province="skyrim", creature=C_PEAK,
      particle=particle("white_ash", 0.003), features=["boulders_frost_4", "trees_skyrim_snowy_sparse"],
      tags=["peak", "slope", "mountain", "snowy", "cold"])
biome("alpine_meadow", "Alpine Meadow", temp=0.25, downfall=0.6, sky="#8CB0EC", fog="#CCDCF0", water="#3D57D6",
      grass="#7DB060", foliage="#64904C", surface=S_ALPINE, province="skyrim", creature=C_PEAK + C_SKYRIM_PLAINS,
      features=["boulders_tundra_9", "trees_alpine", "flowers_meadow", "grass_plains", "tall_grass_sparse"],
      tags=["mountain", "slope"])
biome("skyrim_highlands", "Skyrim Highlands", temp=0.3, downfall=0.4, sky="#8EAEDC", fog="#C8D2DE", water="#3D57D6",
      grass="#8E9A5A", foliage="#6A8048", surface=S_HIGHLANDS, province="skyrim", creature=C_PEAK + C_SKYRIM_PLAINS,
      features=["boulders_tundra_3", "trees_highland_scattered", "snowberries_rare", "flowers_mountain_rare",
                "grass_tundra"],
      tags=["mountain", "tundra", "hill"])
biome("skyrim_coast", "Skyrim Coast", temp=0.2, downfall=0.5, sky="#90A8D0", fog="#C8D4E0", water="#3D57D6",
      grass="#80A070", foliage="#6A8A60", surface=S_GRAVEL_COAST, province="skyrim", kind="beach",
      features=["boulders_grey_4", "grass_tundra_sparse"], tags=["beach", "coast", "cold"])
biome("frozen_shore", "Frozen Shore", temp=-0.4, downfall=0.5, sky="#A0B4D8", fog="#DCE4EE", water="#3938C9",
      grass="#80A090", foliage="#6C8C80", surface=S_FROZEN_SHORE, province="skyrim", kind="beach",
      features=["boulders_ice_4"], tags=["beach", "coast", "cold", "snowy"])
biome("skyrim_river", "Skyrim River", temp=0.3, downfall=0.5, sky="#8AAAE0", fog="#C8D6E8", water="#3A60C8",
      grass="#7A9A5A", foliage="#647E4C", surface=S_RIVER_COLD, province="skyrim", kind="river", extra=DROWNED,
      water_creature=W_RIVER, water_ambient=WA_RIVER, features=["reeds_sparse", "minecraft:seagrass_river"],
      tags=["river"])
biome("frozen_river", "Frozen River", temp=-0.4, downfall=0.5, sky="#A0B4D8", fog="#DCE4EE", water="#3938C9",
      grass="#80A090", foliage="#6C8C80", surface=S_RIVER_COLD, province="skyrim", kind="river", extra=DROWNED,
      water_creature=W_RIVER, water_ambient=WA_RIVER, features=["minecraft:seagrass_river"],
      tags=["river", "cold", "snowy"])
biome("skyrim_lake", "Skyrim Lake", temp=0.3, downfall=0.6, sky="#8AAAE0", fog="#C8D6E8", water="#3B62CE",
      grass="#7A9A5A", foliage="#647E4C", surface=S_RIVER_COLD, province="skyrim", kind="lake",
      water_creature=W_RIVER, water_ambient=WA_RIVER,
      features=["reeds_sparse", "lily_pads", "minecraft:seagrass_river"], tags=["river"])
biome("sea_of_ghosts", "Sea of Ghosts", temp=0.0, downfall=0.5, frozen=True, sky="#9AAED0", fog="#D8E0EA",
      water="#3938C9", surface=S_OCEAN_COLD, province="skyrim", kind="ocean", particle=particle("white_ash", 0.002),
      creature=[sp("polar_bear", 1, 1, 2)], extra=DROWNED[:0] + [sp("drowned", 5, 1)], water_creature=W_COLD,
      water_ambient=WA_COLD,
      features=["minecraft:iceberg_packed", "minecraft:iceberg_blue", "minecraft:blue_ice", "minecraft:seagrass_cold",
                "minecraft:kelp_cold"],
      tags=["ocean", "frozen_ocean", "cold", "snowy"])
biome("deep_sea_of_ghosts", "Deep Sea of Ghosts", temp=0.0, downfall=0.5, frozen=True, sky="#9AAED0", fog="#D8E0EA",
      water="#3938C9", surface=S_OCEAN_COLD, province="skyrim", kind="ocean", particle=particle("white_ash", 0.002),
      creature=[sp("polar_bear", 1, 1, 2)], extra=[sp("drowned", 5, 1)], water_creature=W_COLD,
      water_ambient=WA_COLD,
      features=["minecraft:iceberg_packed", "minecraft:iceberg_blue", "minecraft:blue_ice",
                "minecraft:seagrass_deep_cold", "minecraft:kelp_cold"],
      tags=["ocean", "deep_ocean", "frozen_ocean", "cold", "snowy"])
biome("solstheim_ashlands", "Solstheim Ashlands", temp=0.3, downfall=0.2, sky="#8A8478", fog="#9C9488",
      water="#4E5C5A", water_fog="#121816", grass="#8A8A6A", foliage="#7A7458", surface=S_SOLSTHEIM_ASH,
      province="morrowind", creature=C_MORROWIND, particle=particle("ash", 0.02),
      features=["boulders_volcanic_3", "ground_volcanic_rock", "dead_trees_charred_4", "fallen_logs_charred_6",
                "flowers_ash", "grass_ash_sparse", "ash_drifts"],
      tags=["wasteland", "dry"])
biome("solstheim_pine_forest", "Solstheim Pine Forest", temp=0.0, downfall=0.6, sky="#98AECC", fog="#CCD6E0",
      water="#3D57D6", grass="#7E9A70", foliage="#6A8A60",
      surface=tweak(S_SNOW_FOREST, patches=[["surface", 0.4, MAX, PODZOL], ["surface", -0.7, -0.45, ASH]]),
      province="morrowind", creature=C_SKYRIM_SNOW, extra=X_FROSTBITE, particle=particle("white_ash", 0.003),
      features=["boulders_tundra_9", "trees_solstheim_pine", "fallen_logs_spruce_8", "snowberries", "flowers_cold",
                "grass_taiga", "ash_drifts_sparse"],
      tags=["taiga", "snowy_forest", "cold", "snowy"])

# ------------------------------------------------------------------------------------------------ Cyrodiil
biome("heartlands", "Heartlands", temp=0.8, downfall=0.5, sky="#78A7FF", fog="#C0D8FF", water="#3F76E4",
      grass="#86B85A", foliage="#6BA53C", surface=S_FARMLAND, province="cyrodiil", creature=C_FARM + DEER,
      extra=OUTLAWS, features=["trees_heartlands", "pumpkins_rare", "flowers_heartlands", "grass_plains",
                               "tall_grass_dense", "minecraft:patch_sugar_cane"],
      tags=[])
biome("great_forest", "Great Forest", temp=0.7, downfall=0.8, sky="#7AA0E0", fog="#B8CCE0", water="#3F70DC",
      grass="#5E9A3E", foliage="#4A8A30", surface=S_DARK_FOREST, province="cyrodiil", creature=C_TEMPERATE_FOREST,
      extra=OUTLAWS, features=["boulders_mossy_3", "trees_great_forest", "fallen_logs_dark_oak_4", "tall_ferns",
                               "flowers_forest", "grass_forest", "moss_carpets", "forest_mushrooms",
                               "nightshade_shade"],
      tags=["forest", "dark_forest", "wet"])
biome("colovian_highlands", "Colovian Highlands", temp=0.55, downfall=0.4, sky="#84ACF0", fog="#CCD8EA",
      water="#3F70DC", grass="#94A858", foliage="#7A9848", surface=S_HIGHLANDS_TEMPERATE, province="cyrodiil",
      creature=C_HIGHLAND + DEER, extra=OUTLAWS,
      features=["boulders_grey_4", "trees_colovian", "flowers_mountain_rare", "grass_tundra", "tall_grass_sparse"],
      tags=["hill", "plateau"])
biome("gold_coast", "Gold Coast", temp=0.9, downfall=0.4, sky="#6FAEFF", fog="#CCE0FF", water="#44AEE8",
      water_fog="#062A44", grass="#A3B850", foliage="#8AA840", surface=S_GOLD_COAST, province="cyrodiil",
      creature=C_FARM, extra=OUTLAWS,
      features=["palms_rare", "trees_gold_coast", "grape_vines", "flowers_gold_coast", "grass_plains"], tags=[])
biome("west_weald_vineyards", "West Weald Vineyards", temp=0.85, downfall=0.45, sky="#74ACFF", fog="#D4DCEC",
      water="#3F7AE4", grass="#9DBA4E", foliage="#84A83A", surface=S_WEALD, province="cyrodiil", creature=C_FARM,
      extra=OUTLAWS, features=["trees_west_weald", "grape_vines", "lavender_fields", "flowers_weald", "grass_plains",
                               "tall_grass_sparse"], tags=[])
biome("nibenay_valley", "Nibenay Valley", temp=0.85, downfall=0.7, sky="#76A8FF", fog="#C4D8F4", water="#3F80E4",
      grass="#74B050", foliage="#5FA040", surface=S_TEMPERATE, province="cyrodiil", creature=C_FARM + DEER,
      extra=OUTLAWS, features=["trees_nibenay", "flowers_heartlands", "grass_plains", "tall_grass_sparse",
                               "reeds_sparse", "lily_pads", "minecraft:patch_sugar_cane"],
      tags=["wet"])
biome("blackwood_swamp", "Blackwood Swamp", temp=0.95, downfall=0.95, sky="#7E9EC0", fog="#A8B8B0", water="#4C6E58",
      water_fog="#142A20", grass="#5E7A3A", foliage="#4E6E2E", surface=S_SWAMP, province="cyrodiil",
      creature=C_SWAMP, monster=monsters("swamp"), extra=OUTLAWS,
      features=["hist_trees_sparse", "trees_blackwood", "reeds_dense", "flowers_swamp", "grass_swamp",
                "lily_pads_dense", "minecraft:seagrass_swamp"],
      tags=["swamp", "wet", "hot"])
biome("jerall_foothills", "Jerall Foothills", temp=0.2, downfall=0.6, sky="#88A8E0", fog="#CCD6E4", water="#3D57D6",
      grass="#7C9C66", foliage="#5E8452", surface=tweak(S_HIGHLANDS_TEMPERATE, snow=[190, 4]), province="cyrodiil",
      creature=C_SKYRIM_FOREST + C_PEAK, extra=X_FROSTBITE,
      features=["boulders_tundra_9", "trees_jerall", "snowberries_rare", "flowers_mountain_rare", "grass_taiga"],
      tags=["taiga", "mountain"])
biome("lake_rumare_shore", "Lake Rumare Shore", temp=0.8, downfall=0.6, sky="#78A8FF", fog="#C4DAFF", water="#3F8AE4",
      grass="#80BC5C", foliage="#66A844", surface=S_TEMPERATE, province="cyrodiil", kind="lake", creature=C_FARM,
      water_creature=W_RIVER, water_ambient=WA_RIVER,
      features=["trees_lake_shore", "reeds_dense", "flowers_heartlands", "grass_plains", "lily_pads",
                "minecraft:patch_sugar_cane", "minecraft:seagrass_river"],
      tags=["river"])
biome("cyrodiil_river", "Cyrodiil River", temp=0.75, downfall=0.6, sky="#78A8FF", fog="#C0D8FF", water="#3F76E4",
      grass="#80B85A", foliage="#68A440", surface=S_TEMPERATE, province="cyrodiil", kind="river", extra=DROWNED,
      water_creature=W_RIVER, water_ambient=WA_RIVER, features=["reeds_sparse", "minecraft:seagrass_river"],
      tags=["river"])
biome("temperate_beach", "Temperate Beach", temp=0.8, downfall=0.4, sky="#78A7FF", fog="#C0D8FF", water="#3F76E4",
      surface=S_BEACH, province="cyrodiil", kind="beach", creature=C_COAST_WARM, features=[],
      tags=["warm_beach"])

# ---------------------------------------------------------------------------------------------- Morrowind
biome("vvardenfell_ashlands", "Vvardenfell Ashlands", temp=1.0, downfall=0.1, precip=False, sky="#9C8070",
      fog="#A08878", water="#5A6460", water_fog="#1A1A18", grass="#9A8E6A", foliage="#8A7A58", surface=S_ASH,
      province="morrowind", creature=C_MORROWIND, particle=particle("ash", 0.03),
      features=["lava_vents_sparse", "boulders_volcanic_3", "spires_basalt", "ground_volcanic_rock",
                "dead_trees_charred_4", "flowers_ash", "grass_ash", "ash_drifts"],
      tags=["wasteland", "hot", "dry", "sparse"])
biome("red_mountain", "Red Mountain", temp=1.4, downfall=0.0, precip=False, sky="#A06048", fog="#7A3A2A",
      water="#6A4A3A", water_fog="#2A0A00", grass="#8A6A4A", foliage="#7A5A3A", surface=S_RED_MOUNTAIN,
      province="morrowind", particle=particle("crimson_spore", 0.03), extra=MAGMA_CUBES,
      features=["minecraft:lake_lava_surface", "lava_vents_dense", "boulders_volcanic_3", "spires_basalt",
                "ground_volcanic_rock", "spring_lava_dense", "grass_ash_sparse", "ash_drifts_sparse"],
      tags=["mountain", "wasteland", "hot", "dry"])
biome("bitter_coast_swamp", "Bitter Coast Swamp", temp=0.9, downfall=0.9, sky="#8AA090", fog="#9CAA98",
      water="#4E6A52", water_fog="#102418", grass="#6A8048", foliage="#5A7040", surface=S_BITTER,
      province="morrowind", creature=C_SWAMP, monster=monsters("swamp"), particle=particle("spore_blossom_air", 0.004),
      features=["emperor_parasols_rare", "trees_bitter_coast", "reeds_dense", "flowers_swamp", "grass_swamp",
                "forest_mushrooms", "lily_pads", "minecraft:seagrass_swamp"],
      tags=["swamp", "wet", "hot"])
biome("grazelands", "Grazelands", temp=0.9, downfall=0.3, sky="#88AADC", fog="#D0D8C8", water="#3F76E4",
      grass="#B4B058", foliage="#A09A48", surface=S_GRAZE, province="morrowind", creature=C_GRAZE,
      features=["boulders_tundra_9", "trees_grazelands", "flowers_savanna", "grass_grazelands", "tall_grass_dense"],
      tags=["savanna", "dry"])
biome("west_gash", "West Gash", temp=0.7, downfall=0.4, sky="#86A8E0", fog="#CCD0D0", water="#3F70DC",
      grass="#8C9E5C", foliage="#74884A", surface=S_WEST_GASH, province="morrowind", creature=C_MORROWIND,
      extra=OUTLAWS, features=["boulders_grey_4", "trees_west_gash", "flowers_mountain_rare", "grass_dry",
                               "tall_grass_sparse"],
      tags=["hill"])
biome("ascadian_isles", "Ascadian Isles", temp=0.95, downfall=0.7, sky="#74A6F0", fog="#C4D8F0", water="#3E8CD8",
      grass="#7CC050", foliage="#6AB040", surface=S_ASCADIAN, province="morrowind", creature=C_ASCADIAN,
      features=["trees_ascadian", "lavender_rare", "flowers_ascadian", "grass_plains", "tall_grass_dense",
                "reeds_sparse", "lily_pads", "minecraft:patch_sugar_cane"],
      tags=["lush"])
biome("azuras_coast", "Azura's Coast", temp=0.8, downfall=0.6, sky="#7E9EE0", fog="#C8C0E0", water="#3E7EDC",
      grass="#6E9A6A", foliage="#5E8A5E", surface=S_AZURA, province="morrowind", creature=C_MORROWIND,
      features=["boulders_grey_4", "emperor_parasols_rare", "trees_sheogorad", "flowers_ascadian", "grass_forest"],
      tags=["coast", "magical"])
biome("sheogorad", "Sheogorad", temp=0.6, downfall=0.6, sky="#8898C8", fog="#B8B8D0", water="#3E70D0",
      grass="#6E8A6A", foliage="#5E7A5A", surface=S_SHEOGORAD, province="morrowind", creature=C_MORROWIND,
      features=["boulders_grey_4", "emperor_parasols_sparse", "trees_sheogorad", "flowers_mountain_rare",
                "grass_forest", "forest_mushrooms"],
      tags=["mushroom", "coast"])
biome("molag_amur", "Molag Amur", temp=1.3, downfall=0.05, precip=False, sky="#8A6A5A", fog="#8C7466",
      water="#5A4A40", water_fog="#1A0E08", grass="#8A7A5A", foliage="#7A6A4A", surface=S_MOLAG_AMUR,
      province="morrowind", particle=particle("ash", 0.06), extra=MAGMA_CUBES,
      features=["minecraft:lake_lava_surface", "lava_vents_sparse", "boulders_volcanic_3", "spires_basalt",
                "ground_volcanic_rock", "dead_trees_charred_4", "grass_ash_sparse", "ash_drifts"],
      tags=["wasteland", "hot", "dry", "sparse"])
biome("telvanni_mushroom_forest", "Telvanni Mushroom Forest", temp=0.85, downfall=0.8, sky="#8C9CE0", fog="#B8A8D8",
      water="#4A70B0", grass="#6A9A70", foliage="#7A8A9A", surface=S_TELVANNI, province="morrowind",
      creature=C_MORROWIND, particle=particle("warped_spore", 0.008),
      features=["telvanni_towers", "emperor_parasols_dense", "trees_mushroom_grove", "flowers_ascadian",
                "grass_forest", "moss_carpets", "forest_mushrooms"],
      tags=["mushroom", "magical", "wet"])
biome("deshaan_plains", "Deshaan Plains", temp=1.0, downfall=0.4, sky="#8AA4E0", fog="#D8C8B8", water="#3F76E4",
      grass="#A8A050", foliage="#B87A3A", surface=S_DESHAAN, province="morrowind", creature=C_ASCADIAN,
      features=["trees_deshaan", "flowers_ascadian", "grass_grazelands", "tall_grass_sparse", "ash_drifts_sparse"],
      tags=["savanna"])
biome("stonefalls", "Stonefalls", temp=1.0, downfall=0.25, sky="#9AA0B8", fog="#B8A898", water="#4E6A70",
      grass="#9A9460", foliage="#8A8048", surface=S_STONEFALLS, province="morrowind", creature=C_MORROWIND,
      particle=particle("ash", 0.008), extra=OUTLAWS,
      features=["lava_vents_sparse", "boulders_volcanic_3", "ground_volcanic_rock", "trees_grazelands",
                "dead_trees_charred_4", "grass_ash", "grass_dry", "ash_drifts_sparse"],
      tags=["dry", "hot", "sparse"])
biome("ash_shore", "Ash Shore", temp=0.9, downfall=0.3, sky="#9A9088", fog="#B0A698", water="#4E6A70",
      surface=S_ASH_SHORE, province="morrowind", kind="beach", particle=particle("ash", 0.005),
      features=["boulders_volcanic_3"], tags=["beach", "coast"])
biome("inner_sea", "Inner Sea", temp=0.9, downfall=0.5, sky="#8AA0D0", fog="#C8D0D8", water="#3E7A8A",
      water_fog="#0A2028", surface=S_INNER_SEA, province="morrowind", kind="ocean", water_creature=W_TEMPERATE,
      water_ambient=WA_TEMPERATE, extra=[sp("drowned", 5, 1)],
      features=["minecraft:seagrass_warm", "minecraft:kelp_warm", "minecraft:sea_pickle"],
      tags=["ocean", "warm_ocean"])

# -------------------------------------------------------------------------------------------- Black Marsh
SWAMP_WATER = ["minecraft:seagrass_swamp"]
biome("black_marsh_swamp", "Black Marsh Swamp", temp=0.95, downfall=0.95, sky="#7A9878", fog="#8AA088",
      water="#3E5A3A", water_fog="#0A1A0A", grass="#5A7A2E", foliage="#4A6A26", surface=S_SWAMP,
      province="black_marsh", creature=C_SWAMP, monster=monsters("swamp"), particle=particle("mycelium", 0.003),
      water_ambient=WA_SWAMP,
      features=["hist_trees_sparse", "trees_black_marsh", "fallen_logs_hist_5", "reeds_dense", "flowers_swamp_dense",
                "grass_swamp", "lily_pads_dense", "minecraft:vines", "minecraft:brown_mushroom_swamp",
                "minecraft:red_mushroom_swamp"] + SWAMP_WATER,
      tags=["swamp", "wet", "hot", "dense"])
biome("deep_mire", "Deep Mire", temp=0.9, downfall=1.0, sky="#6E8070", fog="#6E7E6A", water="#364A30",
      water_fog="#081008", grass="#4E6A2A", foliage="#3E5A22", surface=S_DEEP_MIRE, province="black_marsh",
      creature=C_SWAMP, monster=monsters("swamp"), particle=particle("mycelium", 0.008), water_ambient=WA_SWAMP,
      features=["hist_trees_sparse", "trees_mangrove", "dead_trees_oak_6", "reeds_dense", "grass_swamp",
                "lily_pads_dense", "minecraft:vines"] + SWAMP_WATER,
      tags=["swamp", "wet", "hot", "spooky"])
biome("hist_grove", "Hist Grove", temp=0.95, downfall=0.95, sky="#84A08A", fog="#A0B898", water="#3A6A48",
      water_fog="#0A2014", grass="#4E8A3A", foliage="#3E7A2E", surface=S_HIST, province="black_marsh",
      creature=C_SWAMP, monster=monsters("swamp"), particle=particle("spore_blossom_air", 0.01), water_ambient=WA_SWAMP,
      features=["hist_trees_dense", "trees_black_marsh", "reeds_sparse", "flowers_swamp_dense", "grass_lush",
                "moss_carpets", "lily_pads"] + SWAMP_WATER,
      tags=["swamp", "wet", "hot", "magical", "lush"])
biome("mangrove_delta", "Mangrove Delta", temp=0.95, downfall=0.9, sky="#7EA0B8", fog="#A8B8A8", water="#3A7A6A",
      water_fog="#0A2A22", grass="#6A8A3A", foliage="#5A8A2E", surface=S_MANGROVE, province="black_marsh",
      creature=C_SWAMP + C_COAST_WARM, monster=monsters("swamp"), water_ambient=WA_SWAMP,
      features=["trees_mangrove", "reeds_dense", "grass_swamp", "lily_pads"] + SWAMP_WATER,
      tags=["swamp", "wet", "hot"])
biome("shadowfen", "Shadowfen", temp=0.85, downfall=0.9, sky="#707E8A", fog="#6A7078", water="#2E3E38",
      water_fog="#060C0A", grass="#4A5E3A", foliage="#3A4E2E", surface=S_SWAMP, province="black_marsh",
      creature=C_SWAMP, monster=monsters("swamp"), extra=X_VAMPIRE + X_NECRO, particle=particle("ash", 0.002),
      features=["trees_shadowfen", "dead_trees_oak_2", "fallen_logs_dark_oak_4", "reeds_dense", "grass_swamp",
                "forest_mushrooms", "nightshade_shade", "lily_pads"] + SWAMP_WATER,
      tags=["swamp", "wet", "spooky"])
biome("murkmire", "Murkmire", temp=1.0, downfall=1.0, sky="#7A9A70", fog="#90A880", water="#405C34",
      water_fog="#0C1808", grass="#5E8030", foliage="#4E7028", surface=tweak(S_SWAMP, swamp=60), province="black_marsh",
      creature=C_SWAMP + [sp("parrot", 10, 1, 2)], monster=monsters("swamp"), water_ambient=WA_SWAMP,
      features=["hist_trees_sparse", "trees_black_marsh", "reeds_dense", "flowers_jungle", "grass_jungle",
                "lily_pads_dense", "minecraft:vines"] + SWAMP_WATER,
      tags=["swamp", "wet", "hot", "dense"])
biome("murky_river", "Murky River", temp=0.95, downfall=0.95, sky="#7A9878", fog="#8AA088", water="#3E5A3A",
      water_fog="#0A1A0A", grass="#5A7A2E", foliage="#4A6A26", surface=S_RIVER_MURKY, province="black_marsh",
      kind="river", extra=DROWNED, water_creature=W_RIVER, water_ambient=WA_SWAMP,
      features=["reeds_sparse", "lily_pads"] + SWAMP_WATER, tags=["swamp", "wet"])

# ------------------------------------------------------------------------------------------------ Elsweyr
DESERT_MON = monsters("desert")
biome("anequina_badlands", "Anequina Badlands", temp=1.8, downfall=0.0, precip=False, sky="#7AB0F8", fog="#E8D0A8",
      water="#44AEE8", grass="#BFAA60", foliage="#A89048", surface=badlands(), province="elsweyr",
      creature=C_SAVANNA[:3], extra=OUTLAWS,
      features=["minecraft:ore_gold_extra", "boulders_red_4", "dead_trees_acacia_8", "grass_desert",
                "minecraft:patch_dead_bush_badlands"],
      tags=["badlands", "hot", "dry", "sparse"])
biome("elsweyr_dunes", "Elsweyr Dunes", temp=2.0, downfall=0.0, precip=False, sky="#6EB0FF", fog="#F0DCB0",
      water="#44AEE8", grass="#BFB55A", foliage="#AEA74A", surface=S_DUNES, province="elsweyr", creature=C_DESERT,
      features=["minecraft:fossil_upper", "minecraft:fossil_lower", "minecraft:patch_dead_bush_2"],
      tags=["desert", "hot", "dry", "sparse"])
biome("elsweyr_savanna", "Elsweyr Savanna", temp=1.5, downfall=0.2, precip=False, sky="#74AEF8", fog="#E0D8B8",
      water="#44AEE8", grass="#BFB55A", foliage="#AEA74A", surface=S_SAVANNA, province="elsweyr",
      creature=C_SAVANNA, extra=OUTLAWS,
      features=["boulders_red_4", "trees_savanna", "flowers_savanna", "grass_savanna", "tall_grass_dense"],
      tags=["savanna", "hot", "dry"])
biome("pellitine_jungle", "Pellitine Jungle", temp=1.0, downfall=0.95, sky="#77A8FF", fog="#B8D8C0", water="#3EA8C8",
      grass="#59C93C", foliage="#30BB0B", surface=S_JUNGLE, province="elsweyr", creature=C_JUNGLE,
      features=["palms_sparse", "trees_pellitine", "fallen_logs_jungle_4", "bamboo_groves", "flowers_jungle",
                "grass_jungle", "minecraft:vines", "minecraft:patch_melon"],
      tags=["jungle", "hot", "wet", "lush"])
biome("sugar_fields", "Moon Sugar Fields", temp=1.2, downfall=0.6, sky="#74ACFA", fog="#E0E0C0", water="#3EA8D0",
      grass="#9CC854", foliage="#80B040", surface=S_SUGAR, province="elsweyr", creature=C_SAVANNA,
      features=["palms_rare", "trees_savanna", "moon_sugar", "flowers_savanna", "grass_savanna",
                "minecraft:patch_sugar_cane"],
      tags=["savanna", "hot"])
biome("elsweyr_canyons", "Elsweyr Canyons", temp=1.8, downfall=0.0, precip=False, sky="#78AEF8", fog="#E8C8A0",
      water="#44AEE8", grass="#BFAA60", foliage="#A89048", surface=badlands(band_y=68), province="elsweyr",
      creature=C_SAVANNA[:3], extra=OUTLAWS,
      features=["minecraft:ore_gold_extra", "boulders_red_4", "trees_dragontail", "dead_trees_acacia_8",
                "grass_desert"],
      tags=["badlands", "hot", "dry", "sparse"])

# ---------------------------------------------------------------------------------------------- Valenwood
biome("graht_oak_forest", "Graht-oak Forest", temp=0.95, downfall=0.9, sky="#7AA8F0", fog="#A8D0B0", water="#3E9AB8",
      grass="#4EA83A", foliage="#3E9A2E", surface=S_GRAHT, province="valenwood", creature=C_VALENWOOD + DEER,
      particle=particle("spore_blossom_air", 0.002),
      features=["graht_oaks_dense", "trees_valenwood", "fallen_logs_graht_oak_3", "tall_ferns", "flowers_jungle",
                "grass_jungle", "moss_carpets", "forest_mushrooms", "minecraft:vines"],
      tags=["forest", "jungle", "hot", "wet", "magical"])
biome("valenwood_rainforest", "Valenwood Rainforest", temp=1.0, downfall=1.0, sky="#78A8F0", fog="#A8D4B8",
      water="#3E9AB8", grass="#50B83A", foliage="#2EA82A", surface=S_JUNGLE, province="valenwood",
      creature=C_VALENWOOD,
      features=["graht_oaks_sparse", "trees_rainforest", "fallen_logs_jungle_4", "tall_ferns", "flowers_jungle",
                "grass_jungle", "minecraft:vines", "minecraft:patch_melon"],
      tags=["jungle", "forest", "hot", "wet", "lush"])
biome("valenwood_mangrove_coast", "Valenwood Mangrove Coast", temp=0.95, downfall=0.9, sky="#7AACF0", fog="#B0D0C0",
      water="#3A8A7A", water_fog="#0A2A22", grass="#5E9A3A", foliage="#4E8A2E", surface=S_MANGROVE,
      province="valenwood", creature=C_VALENWOOD[:2] + C_COAST_WARM, monster=monsters("swamp"),
      water_ambient=WA_WARM,
      features=["palms_sparse", "trees_mangrove", "reeds_dense", "grass_swamp", "lily_pads"] + SWAMP_WATER,
      tags=["swamp", "hot", "wet"])
biome("reapers_march", "Reaper's March", temp=0.9, downfall=0.6, sky="#7EA8F0", fog="#D8D0B0", water="#3F8AE4",
      grass="#8AB84A", foliage="#A8A038", surface=S_FOREST, province="valenwood",
      creature=C_VALENWOOD[2:] + C_SAVANNA[:2], extra=OUTLAWS,
      features=["graht_oaks_sparse", "trees_reapers_march", "fallen_logs_oak_5", "flowers_savanna", "grass_plains",
                "tall_grass_dense"],
      tags=["forest", "hot"])
biome("jungle_river", "Jungle River", temp=0.95, downfall=0.9, sky="#78A8F0", fog="#B0D4C0", water="#3EA0C0",
      grass="#50B83A", foliage="#2EA82A", surface=S_RIVER_WARM, province="valenwood", kind="river", extra=DROWNED,
      water_creature=W_RIVER, water_ambient=WA_WARM[:1],
      features=["reeds_sparse", "lily_pads", "minecraft:seagrass_river"], tags=["river", "hot"])

# ---------------------------------------------------------------------------------------------- Summerset
biome("crystal_coast", "Crystal Coast", temp=0.8, downfall=0.5, sky="#8AC0FF", fog="#E0ECFF", water="#48C0E8",
      water_fog="#0A3048", grass="#78C878", foliage="#68B868", surface=S_CRYSTAL, province="summerset",
      creature=C_SUMMERSET, particle=particle("end_rod", 0.0005),
      features=["boulders_calcite_3", "spires_crystal_small", "palms_rare", "crystal_clusters", "flowers_summerset",
                "grass_plains"],
      tags=["magical"])
biome("summerset_meadow", "Summerset Meadow", temp=0.8, downfall=0.6, sky="#84BCFF", fog="#E4F0FF", water="#44B8E8",
      grass="#7CD064", foliage="#6AC050", surface=S_SUMMERSET, province="summerset", creature=C_SUMMERSET,
      particle=particle("cherry_leaves", 0.002),
      features=["trees_summerset", "roses", "lavender_rare", "crystal_clusters_rare", "flowers_summerset",
                "grass_plains", "tall_grass_sparse"],
      tags=["lush"])
biome("auridon_vineyards", "Auridon Vineyards", temp=0.85, downfall=0.5, sky="#80B8FF", fog="#E0ECFC",
      water="#44B8E8", grass="#9CC85A", foliage="#88B848", surface=S_SUMMERSET, province="summerset",
      creature=C_SUMMERSET,
      features=["palms_rare", "trees_auridon", "grape_vines", "lavender_fields", "flowers_weald", "grass_plains"],
      tags=[])
biome("artaeum", "Artaeum", temp=0.7, downfall=0.7, sky="#9CB8FF", fog="#E8DCFF", water="#5AC0E8", water_fog="#103048",
      grass="#6ED0A0", foliage="#60C0A0", surface=S_ARTAEUM, province="summerset", creature=C_SUMMERSET,
      particle=particle("end_rod", 0.002),
      features=["boulders_calcite_3", "spires_crystal_small", "trees_artaeum", "crystal_clusters", "flowers_artaeum",
                "grass_lush", "moss_carpets"],
      tags=["magical", "lush", "rare"])
biome("summerset_shore", "Summerset Shore", temp=0.8, downfall=0.5, sky="#8AC0FF", fog="#E0ECFF", water="#48C0E8",
      water_fog="#0A3048", surface=S_WHITE_BEACH, province="summerset", kind="beach", creature=C_COAST_WARM,
      features=["palms_rare", "crystal_clusters_rare"], tags=["warm_beach"])

# --------------------------------------------------------------------------------------------- Hammerfell
biome("alikr_desert", "Alik'r Desert", temp=2.0, downfall=0.0, precip=False, sky="#6EAEFF", fog="#F0E0C0",
      water="#44AEE8", grass="#BFB55A", foliage="#AEA74A", surface=S_ALIKR, province="hammerfell", creature=C_DESERT,
      extra=OUTLAWS,
      features=["boulders_sand_8", "minecraft:fossil_upper", "minecraft:fossil_lower", "minecraft:desert_well",
                "palms_rare", "dead_trees_acacia_8", "grass_desert"],
      tags=["desert", "hot", "dry", "sparse"])
biome("alikr_dunes", "Alik'r Dunes", temp=2.0, downfall=0.0, precip=False, sky="#6EAEFF", fog="#F4E4C4",
      water="#44AEE8", grass="#BFB55A", foliage="#AEA74A", surface=S_DUNES, province="hammerfell", creature=C_DESERT,
      features=["minecraft:fossil_upper", "minecraft:fossil_lower", "minecraft:patch_dead_bush_2"],
      tags=["desert", "hot", "dry", "sparse"])
biome("dragontail_mountains", "Dragontail Mountains", temp=1.5, downfall=0.1, precip=False, sky="#74ACF8",
      fog="#E8D0B0", water="#44AEE8", grass="#B8A050", foliage="#A08840", surface=S_DRAGONTAIL,
      province="hammerfell", creature=C_HAMMERFELL,
      features=["boulders_red_4", "trees_dragontail", "dead_trees_acacia_8", "grass_desert"],
      tags=["mountain", "hot", "dry"])
biome("craglorn", "Craglorn", temp=1.6, downfall=0.1, precip=False, sky="#78A8F0", fog="#E0C8A8", water="#44AEE8",
      grass="#B0A458", foliage="#9A8A44", surface=badlands(band_y=90), province="hammerfell", creature=C_HAMMERFELL,
      extra=OUTLAWS, features=["boulders_red_4", "boulders_sand_8", "trees_craglorn", "grass_dry"],
      tags=["badlands", "hot", "dry", "hill"])
biome("bangkorai", "Bangkorai", temp=0.6, downfall=0.5, sky="#84A8E8", fog="#C8D0D8", water="#3F70DC",
      grass="#88A858", foliage="#6E9448", surface=S_BANGKORAI, province="hammerfell", creature=C_HIGHLAND + DEER,
      extra=OUTLAWS,
      features=["boulders_grey_4", "trees_bangkorai", "flowers_high_rock", "grass_plains", "tall_grass_sparse"],
      tags=["hill"])
biome("stros_mkai_coast", "Stros M'Kai Coast", temp=1.4, downfall=0.5, sky="#6EB0FF", fog="#D8ECFF", water="#44C4E8",
      water_fog="#062E40", grass="#8EC050", foliage="#70B030", surface=S_STROS, province="hammerfell", kind="beach",
      creature=C_COAST_WARM + [sp("parrot", 10, 1, 2)],
      features=["palms_dense", "flowers_jungle", "grass_savanna", "minecraft:patch_sugar_cane"],
      tags=["warm_beach", "hot"])

# ---------------------------------------------------------------------------------------------- High Rock
biome("glenumbra_moors", "Glenumbra Moors", temp=0.6, downfall=0.7, sky="#7E9CD0", fog="#B8C4D0", water="#3F6ADC",
      grass="#7E9A58", foliage="#6A8848", surface=S_MOOR, province="high_rock", creature=C_FARM + DEER,
      extra=OUTLAWS, features=["boulders_mossy_8", "trees_glenumbra", "snowberries_rare", "flowers_moor",
                               "grass_plains", "tall_grass_sparse"],
      tags=["wet"])
biome("stormhaven", "Stormhaven", temp=0.7, downfall=0.8, sky="#7AA0E0", fog="#BCCCE0", water="#3F70E0",
      grass="#6EAA4E", foliage="#5A9A3E", surface=S_FOREST, province="high_rock", creature=C_FARM + DEER,
      extra=OUTLAWS, features=["boulders_mossy_8", "trees_stormhaven", "fallen_logs_oak_5", "pumpkins_rare",
                               "flowers_high_rock", "grass_forest"],
      tags=["forest", "wet"])
biome("rivenspire", "Rivenspire", temp=0.45, downfall=0.8, sky="#6E7C98", fog="#8A90A0", water="#3A5070",
      water_fog="#0A1020", grass="#5E7A5A", foliage="#4A6448", surface=S_RIVENSPIRE, province="high_rock",
      creature=C_TEMPERATE_FOREST, extra=X_VAMPIRE + X_NECRO + OUTLAWS,
      features=["boulders_dark_4", "trees_rivenspire", "dead_trees_oak_6", "fallen_logs_dark_oak_4", "grass_taiga",
                "forest_mushrooms", "nightshade_shade"],
      tags=["forest", "dark_forest", "spooky", "wet"])
biome("wrothgarian_mountains", "Wrothgarian Mountains", temp=0.0, downfall=0.6, sky="#90A8D8", fog="#D0D8E4",
      water="#3D57D6", grass="#7A9470", foliage="#627E5C", surface=S_WROTHGAR, province="high_rock",
      creature=C_PEAK + C_SKYRIM_SNOW, extra=X_FROSTBITE,
      features=["boulders_tundra_3", "trees_wrothgar", "snowberries", "flowers_cold", "grass_taiga"],
      tags=["mountain", "taiga", "cold"])
biome("wayrest_farmland", "Wayrest Farmland", temp=0.75, downfall=0.6, sky="#7AA6F8", fog="#C4D6F0", water="#3F76E4",
      grass="#84B656", foliage="#6CA444", surface=S_FARMLAND, province="high_rock", creature=C_FARM, extra=OUTLAWS,
      features=["trees_wayrest", "pumpkins_rare", "flowers_high_rock", "grass_plains", "tall_grass_dense",
                "minecraft:patch_sugar_cane"],
      tags=[])
biome("bjoulsae_river", "Bjoulsae River", temp=0.7, downfall=0.6, sky="#7AA6F8", fog="#C4D6F0", water="#4A78D0",
      grass="#80B058", foliage="#68A044", surface=S_TEMPERATE, province="high_rock", kind="river", extra=DROWNED,
      water_creature=W_RIVER, water_ambient=WA_RIVER, features=["reeds_sparse", "minecraft:seagrass_river"],
      tags=["river"])

# ------------------------------------------------------------------------------------------- foreign lands
biome("atmoran_ice_waste", "Atmoran Ice Waste", temp=-1.0, downfall=0.4, sky="#B8C8E8", fog="#F0F4FA",
      water="#3938C9", surface=S_ICE_WASTE, province="atmora", creature=C_GLACIER,
      particle=particle("white_ash", 0.01),
      features=["spires_ice", "boulders_ice_4", "ground_frosted_stone", "minecraft:blue_ice", "minecraft:ice_patch"],
      tags=["snowy", "cold", "wasteland", "tundra"])
biome("atmoran_frozen_forest", "Atmoran Frozen Forest", temp=-0.7, downfall=0.5, sky="#A8BCE0", fog="#E0E8F2",
      water="#3938C9", grass="#80A094", foliage="#6C8C84", surface=tweak(S_SNOW_FOREST, snow=[90, 4]),
      province="atmora", creature=C_SKYRIM_SNOW, extra=X_FROSTBITE, particle=particle("white_ash", 0.004),
      features=["boulders_ice_4", "trees_atmora", "fallen_logs_spruce_8", "snowberries", "grass_snow"],
      tags=["taiga", "snowy_forest", "snowy", "cold"])
biome("tsaesci_jungle", "Tsaesci Jungle", temp=1.1, downfall=0.95, sky="#6EA8F8", fog="#A8D8B8", water="#3AB0C0",
      grass="#48C030", foliage="#2EB020", surface=S_JUNGLE, province="akavir", creature=C_TSAESCI,
      features=["minecraft:bamboo_vegetation", "trees_tsaesci", "bamboo_groves", "flowers_jungle", "grass_jungle",
                "minecraft:vines", "minecraft:patch_melon"],
      tags=["jungle", "hot", "wet", "lush"])
biome("kamal_snowfield", "Kamal Snowfield", temp=-0.6, downfall=0.5, sky="#A4B4D8", fog="#DCE2EC", water="#3938C9",
      grass="#80A090", foliage="#6C8C80", surface=S_KAMAL, province="akavir", creature=C_GLACIER + C_SKYRIM_SNOW,
      particle=particle("white_ash", 0.004),
      features=["boulders_ice_4", "ground_frosted_stone", "trees_skyrim_snowy_sparse", "grass_snow"],
      tags=["snowy", "cold", "tundra"])
biome("tang_mo_islands", "Tang Mo Islands", temp=0.95, downfall=0.8, sky="#7EB4FF", fog="#E0ECF8", water="#3EC0E0",
      grass="#6CC860", foliage="#5AB84A", surface=S_TANG_MO, province="akavir", creature=C_JUNGLE[:2] + C_COAST_WARM,
      particle=particle("cherry_leaves", 0.002),
      features=["palms_sparse", "trees_tang_mo", "bamboo_groves", "flowers_akavir", "grass_lush"],
      tags=["lush", "hot"])
biome("ka_po_tun_steppe", "Ka Po' Tun Steppe", temp=0.7, downfall=0.3, sky="#84AEF0", fog="#E0D8C0", water="#3F76E4",
      grass="#A8B058", foliage="#96A048", surface=S_STEPPE, province="akavir", creature=C_STEPPE,
      features=["boulders_grey_4", "trees_ka_po_tun", "flowers_steppe", "grass_savanna", "tall_grass_dense"],
      tags=["savanna", "dry"])
biome("yokudan_desert", "Yokudan Desert", temp=2.0, downfall=0.0, precip=False, sky="#6CAEFF", fog="#F4E0B8",
      water="#44AEE8", grass="#BFB55A", foliage="#AEA74A", surface=S_YOKUDA, province="yokuda", creature=C_DESERT,
      features=["boulders_sand_8", "minecraft:fossil_upper", "minecraft:fossil_lower", "minecraft:desert_well",
                "dead_trees_acacia_8", "cacti_rare", "grass_desert"],
      tags=["desert", "hot", "dry", "sparse"])
biome("sunken_isles", "Sunken Isles", temp=1.2, downfall=0.7, sky="#74B0FF", fog="#D8ECF4", water="#3AC4D8",
      water_fog="#08303A", grass="#7EC050", foliage="#6AB040", surface=S_SUNKEN, province="yokuda",
      creature=C_COAST_WARM,
      features=["boulders_grey_4", "palms_sparse", "reeds_sparse", "flowers_jungle", "grass_savanna"],
      tags=["hot", "wet"])
biome("pyandonean_jungle_isles", "Pyandonean Jungle Isles", temp=1.1, downfall=0.95, sky="#6CA6F8", fog="#B0D8D0",
      water="#2EB8C8", grass="#3EC038", foliage="#28B030", surface=S_JUNGLE, province="pyandonea", creature=C_JUNGLE,
      features=["palms_sparse", "trees_pyandonea", "bamboo_groves", "flowers_jungle", "grass_jungle",
                "minecraft:vines"],
      tags=["jungle", "hot", "wet", "lush"])
biome("thras_coral", "Thras Coral Isles", temp=1.2, downfall=0.8, sky="#80B8FF", fog="#F0D8E0", water="#3ED0E0",
      water_fog="#0A3A40", grass="#7ECC80", foliage="#6ABA70", surface=S_CORAL, province="thras",
      creature=C_COAST_WARM,
      features=["boulders_coral_4", "spires_coral", "ground_coral_stone", "palms_sparse", "dead_corals",
                "grass_savanna"],
      tags=["hot", "magical"])
biome("crystal_spires", "Crystal Spires", temp=0.6, downfall=0.5, sky="#9CC8FF", fog="#E8F0FF", water="#5AC0E8",
      surface=S_CRYSTAL_SPIRES, province="realm", creature=[sp("rabbit", 4, 2, 3)],
      particle=particle("end_rod", 0.002),
      features=["boulders_calcite_3", "spires_crystal_large", "spires_crystal_small", "crystal_clusters",
                "grass_tundra_sparse"],
      tags=["magical", "rare", "sparse"])

# --------------------------------------------------------------------------------- generic coasts and rivers
biome("warm_beach", "Warm Beach", temp=1.0, downfall=0.4, sky="#70ACFF", fog="#D0E4FF", water="#45ADF2",
      surface=S_BEACH, province="realm", kind="beach", creature=C_COAST_WARM, features=["palms_sparse"],
      tags=["warm_beach", "hot"])
biome("tropical_beach", "Tropical Beach", temp=1.2, downfall=0.8, sky="#6EB0FF", fog="#D8ECFF", water="#43D5EE",
      water_fog="#052F3A", surface=S_BEACH, province="realm", kind="beach",
      creature=C_COAST_WARM + [sp("parrot", 10, 1, 2)], features=["palms_dense", "minecraft:patch_sugar_cane"],
      tags=["warm_beach", "hot", "wet"])
biome("rocky_shore", "Rocky Shore", temp=0.5, downfall=0.4, sky="#80A8E8", fog="#C8D4E4", water="#3F70DC",
      surface=S_ROCKY_SHORE, province="realm", kind="beach", features=["boulders_grey_4"],
      tags=["beach", "coast"])
biome("warm_river", "Warm River", temp=1.2, downfall=0.4, sky="#70ACFF", fog="#D0E4FF", water="#44AEE8",
      surface=S_RIVER_WARM, province="realm", kind="river", extra=DROWNED, water_creature=W_RIVER,
      water_ambient=WA_RIVER, features=["reeds_sparse", "minecraft:patch_sugar_cane", "minecraft:seagrass_river"],
      tags=["river", "hot"])

# ----------------------------------------------------------------------------------------------- oceans
OCEAN_EXTRA = [sp("drowned", 5, 1)]
biome("cold_ocean", "Cold Ocean", temp=0.5, downfall=0.5, sky="#80A4E0", fog="#C8D6EA", water="#3D57D6",
      surface=S_OCEAN_COLD, province="sea", kind="ocean", extra=OCEAN_EXTRA, water_creature=W_COLD,
      water_ambient=WA_COLD, features=["minecraft:seagrass_cold", "minecraft:kelp_cold"],
      tags=["ocean", "cold_ocean"])
biome("deep_cold_ocean", "Deep Cold Ocean", temp=0.5, downfall=0.5, sky="#80A4E0", fog="#C8D6EA", water="#3D57D6",
      surface=S_OCEAN_COLD, province="sea", kind="ocean", extra=OCEAN_EXTRA, water_creature=W_COLD,
      water_ambient=WA_COLD, features=["minecraft:seagrass_deep_cold", "minecraft:kelp_cold"],
      tags=["ocean", "deep_ocean", "cold_ocean"])
biome("temperate_ocean", "Temperate Ocean", temp=0.5, downfall=0.5, sky="#78A7FF", fog="#C0D8FF", water="#3F76E4",
      surface=S_OCEAN, province="sea", kind="ocean", extra=OCEAN_EXTRA, water_creature=W_TEMPERATE,
      water_ambient=WA_TEMPERATE, features=["minecraft:seagrass_normal", "minecraft:kelp_cold"],
      tags=["ocean", "cold_ocean"])
biome("deep_temperate_ocean", "Deep Ocean", temp=0.5, downfall=0.5, sky="#78A7FF", fog="#C0D8FF", water="#3F76E4",
      surface=S_OCEAN, province="sea", kind="ocean", extra=OCEAN_EXTRA, water_creature=W_TEMPERATE,
      water_ambient=WA_TEMPERATE, features=["minecraft:seagrass_deep", "minecraft:kelp_cold"],
      tags=["ocean", "deep_ocean", "cold_ocean"])
biome("warm_ocean", "Warm Ocean", temp=0.5, downfall=0.5, sky="#72ACFF", fog="#C8E0FF", water="#45ADF2",
      water_fog="#041F33", surface=S_OCEAN_WARM, province="sea", kind="ocean", extra=OCEAN_EXTRA,
      water_creature=W_WARM, water_ambient=WA_WARM,
      features=["minecraft:seagrass_warm", "minecraft:kelp_warm"], tags=["ocean", "warm_ocean"])
biome("deep_warm_ocean", "Deep Warm Ocean", temp=0.5, downfall=0.5, sky="#72ACFF", fog="#C8E0FF", water="#45ADF2",
      water_fog="#041F33", surface=S_OCEAN_WARM, province="sea", kind="ocean", extra=OCEAN_EXTRA,
      water_creature=W_WARM, water_ambient=WA_WARM,
      features=["minecraft:seagrass_deep_warm", "minecraft:kelp_warm"], tags=["ocean", "deep_ocean", "warm_ocean"])
biome("coral_sea", "Coral Sea", temp=0.5, downfall=0.5, sky="#70B0FF", fog="#D0E8FF", water="#43D5EE",
      water_fog="#052F3A", surface=S_OCEAN_CORAL, province="sea", kind="ocean", extra=OCEAN_EXTRA,
      water_creature=W_WARM, water_ambient=WA_WARM,
      features=["minecraft:warm_ocean_vegetation", "minecraft:seagrass_warm", "minecraft:sea_pickle"],
      tags=["ocean", "warm_ocean", "coral"])

# -------------------------------------------------------------------------------------------- underground
CAVE = LAND
biome("dwemer_caverns", "Dwemer Caverns", temp=0.6, downfall=0.3, sky="#78A7FF", fog="#A08C6A", water="#3F76E4",
      surface=C_DWEMER, province="underground", kind="cave", particle=particle("white_ash", 0.002), extra=X_DWEMER,
      features=["cave_tuff", "cave_dwemer_copper", "dwemer_pipes", "minecraft:dripstone_cluster",
                "minecraft:pointed_dripstone"],
      tags=["underground"])
biome("falmer_glow_caves", "Falmer Glow Caves", temp=0.5, downfall=0.6, sky="#78A7FF", fog="#4A6A8A", water="#3A8AB0",
      surface=C_FALMER, province="underground", kind="cave", particle=particle("warped_spore", 0.01), extra=X_FALMER,
      features=["cave_moss", "glowing_mushrooms", "cave_mushroom_patches", "cave_moss_patches", "cave_crystals"],
      tags=["underground", "magical"])
biome("ice_caves", "Ice Caves", temp=-0.5, downfall=0.5, sky="#A0B8E0", fog="#A8C8E8", water="#3938C9",
      surface=C_ICE, province="underground", kind="cave", monster=monsters("frozen"), extra=X_ICE_CAVE,
      features=["cave_packed_ice", "cave_blue_ice", "cave_snow", "ice_formations"],
      tags=["underground", "cold", "snowy"])
biome("lava_caves", "Lava Caves", temp=1.5, downfall=0.0, precip=False, sky="#78A7FF", fog="#8A4A2A",
      water="#5A4A40", surface=C_LAVA, province="underground", kind="cave", particle=particle("ash", 0.01),
      extra=MAGMA_CUBES,
      features=["cave_volcanic_rock", "cave_magma", "cave_basalt", "cave_blackstone", "spring_lava_dense"],
      tags=["underground", "hot", "dry"])
biome("mushroom_grottos", "Mushroom Grottos", temp=0.7, downfall=0.8, sky="#78A7FF", fog="#8A7AA8", water="#3F76E4",
      surface=C_GROTTO, province="underground", kind="cave", particle=particle("spore_blossom_air", 0.01),
      features=["cave_moss", "minecraft:spore_blossom", "grotto_mushrooms", "cave_mushroom_patches",
                "cave_moss_patches"],
      tags=["underground", "mushroom", "lush"])


# ================================================================================================ palettes
# A palette maps a terrain context to weighted biome choices. Contexts: ocean, deep_ocean, beach, river, lake,
# marsh, lowland, upland, peak, summit, cave, deep_cave. A missing context comes from the palette's fallback chain
# (region -> province -> default). Inside a context the biome source walks the list with a smooth climate value
# (temperature/humidity + low-frequency noise), so NEIGHBOURING ENTRIES BORDER EACH OTHER: order them from dry/cold
# to wet/warm. "@surface" (caves only) means "the biome of the surface above".
CONTEXTS = ["ocean", "deep_ocean", "beach", "river", "lake", "marsh", "lowland", "upland", "peak", "summit", "cave",
            "deep_cave"]
PALETTES = {}
SURFACE = "@surface"
VANILLA_CAVES = {"minecraft:dripstone_caves", "minecraft:lush_caves", "minecraft:deep_dark"}


def pal(key, fallback=None, upland_y=None, peak_y=None, summit_y=None, bias=None, **contexts):
    """bias shifts the climate value by position, so a palette's choices follow the land's geography:
    {"linear": [x, z, dx, dz, span, shift]} adds shift * clamp(((x, z) - centre) . (dx, dz) / span, -1, 1) (later
    entries towards (dx, dz)); {"radial": [x, z, inner, outer, scale]} scales the value by `scale` inside `inner`,
    rising to 1 at `outer` (only the first entries near the centre)."""
    assert key not in PALETTES, key
    entry = {}
    if fallback:
        entry["fallback"] = fallback
    for k, v in (("upland_y", upland_y), ("peak_y", peak_y), ("summit_y", summit_y), ("bias", bias)):
        if v is not None:
            entry[k] = v
    ctx = {}
    for c, choices in contexts.items():
        assert c in CONTEXTS, (key, c)
        if isinstance(choices, str):
            choices = [choices]
        out = []
        for ch in choices:
            b, w = (ch, 1) if isinstance(ch, str) else ch
            if b != SURFACE and ":" not in b:
                b = SK + ":" + b
            out.append({"biome": b, "weight": w})
        ctx[c] = out
    entry["contexts"] = ctx
    PALETTES[key] = entry


DC = "minecraft:dripstone_caves"
LC = "minecraft:lush_caves"
DD = "minecraft:deep_dark"
CAVE_SKYRIM = [("ice_caves", 1), (SURFACE, 4), (DC, 1), ("mushroom_grottos", 1)]
DEEP_SKYRIM = [("falmer_glow_caves", 2), (SURFACE, 2), ("dwemer_caverns", 2), (DD, 1)]
CAVE_TEMPERATE = [(DC, 1), (SURFACE, 5), ("mushroom_grottos", 1), (LC, 1)]
DEEP_TEMPERATE = [("dwemer_caverns", 1), (SURFACE, 3), ("falmer_glow_caves", 1), (DD, 1)]
CAVE_VOLCANIC = [("lava_caves", 2), (SURFACE, 4), ("mushroom_grottos", 2), (DC, 1)]
DEEP_VOLCANIC = [("lava_caves", 2), (SURFACE, 2), ("dwemer_caverns", 3)]
CAVE_HOT = [(DC, 2), (SURFACE, 5), ("lava_caves", 1)]
DEEP_HOT = [("lava_caves", 1), (SURFACE, 3), ("dwemer_caverns", 1)]
CAVE_WET = [(LC, 2), (SURFACE, 4), ("mushroom_grottos", 1)]
DEEP_WET = [(LC, 1), (SURFACE, 3), ("mushroom_grottos", 1), (DD, 1)]
CAVE_FROZEN = [("ice_caves", 3), (SURFACE, 4)]
DEEP_FROZEN = [("ice_caves", 2), (SURFACE, 3), (DD, 1)]
CAVE_MAGIC = [("falmer_glow_caves", 1), (SURFACE, 4), ("mushroom_grottos", 1)]
DEEP_MAGIC = [("falmer_glow_caves", 2), (SURFACE, 2), (LC, 1)]

# --- the default and the province fallbacks
pal("default", upland_y=115, peak_y=240, summit_y=420,
    ocean="temperate_ocean", deep_ocean="deep_temperate_ocean", beach="temperate_beach", river="cyrodiil_river",
    lake="cyrodiil_river", marsh="nibenay_valley", lowland="heartlands", upland="colovian_highlands",
    peak="snowy_slopes", summit="throat_of_the_world", cave=CAVE_TEMPERATE, deep_cave=DEEP_TEMPERATE)
pal("province/skyrim", fallback="default", peak_y=255, summit_y=470,
    ocean="sea_of_ghosts", deep_ocean="deep_sea_of_ghosts", beach=[("frozen_shore", 1), ("skyrim_coast", 2)],
    river="skyrim_river", lake="skyrim_lake", marsh="hjaalmarch_marsh",
    lowland=[("whiterun_tundra", 2), ("pine_forest", 2)], upland=[("skyrim_highlands", 2), ("pine_forest", 1)],
    peak=[("snowy_slopes", 3)], summit="throat_of_the_world", cave=CAVE_SKYRIM, deep_cave=DEEP_SKYRIM)
pal("province/cyrodiil", fallback="default",
    ocean="temperate_ocean", deep_ocean="deep_temperate_ocean", beach="temperate_beach", river="cyrodiil_river",
    lake="lake_rumare_shore", marsh="nibenay_valley", lowland=[("heartlands", 2), ("great_forest", 1)],
    upland=[("colovian_highlands", 2), ("jerall_foothills", 1)], peak="snowy_slopes", cave=CAVE_TEMPERATE,
    deep_cave=DEEP_TEMPERATE)
pal("province/morrowind", fallback="default",
    ocean="inner_sea", deep_ocean="deep_temperate_ocean", beach="ash_shore", river="cyrodiil_river",
    lake="cyrodiil_river", marsh="bitter_coast_swamp", lowland=[("vvardenfell_ashlands", 1), ("grazelands", 1)],
    upland=[("west_gash", 1), ("vvardenfell_ashlands", 1)], peak="red_mountain", summit="red_mountain",
    cave=CAVE_VOLCANIC, deep_cave=DEEP_VOLCANIC)
pal("province/black_marsh", fallback="default",
    ocean="warm_ocean", deep_ocean="deep_warm_ocean", beach="mangrove_delta", river="murky_river", lake="murky_river",
    marsh="deep_mire", lowland=[("black_marsh_swamp", 3), ("hist_grove", 1)], upland="black_marsh_swamp",
    peak="black_marsh_swamp", summit="black_marsh_swamp", cave=CAVE_WET, deep_cave=DEEP_WET)
pal("province/elsweyr", fallback="default",
    ocean="warm_ocean", deep_ocean="deep_warm_ocean", beach="warm_beach", river="warm_river", lake="warm_river",
    marsh="sugar_fields", lowland=[("elsweyr_dunes", 1), ("anequina_badlands", 1), ("elsweyr_savanna", 2)],
    upland=[("elsweyr_canyons", 2), ("anequina_badlands", 1)], peak="elsweyr_canyons", summit="elsweyr_canyons",
    cave=CAVE_HOT, deep_cave=DEEP_HOT)
pal("province/valenwood", fallback="default",
    ocean="warm_ocean", deep_ocean="deep_warm_ocean", beach=[("tropical_beach", 1), ("valenwood_mangrove_coast", 1)],
    river="jungle_river", lake="jungle_river", marsh="valenwood_mangrove_coast",
    lowland=[("graht_oak_forest", 2), ("valenwood_rainforest", 2)], upland="valenwood_rainforest",
    peak="valenwood_rainforest", summit="valenwood_rainforest", cave=CAVE_WET, deep_cave=DEEP_WET)
pal("province/summerset", fallback="default",
    ocean="warm_ocean", deep_ocean="deep_warm_ocean", beach="summerset_shore", river="cyrodiil_river",
    lake="cyrodiil_river", marsh="summerset_meadow", lowland=[("summerset_meadow", 3), ("crystal_coast", 1)],
    upland="summerset_meadow", peak="crystal_spires", summit="crystal_spires", cave=CAVE_MAGIC, deep_cave=DEEP_MAGIC)
pal("province/hammerfell", fallback="default",
    ocean="warm_ocean", deep_ocean="deep_warm_ocean", beach="warm_beach", river="warm_river", lake="warm_river",
    marsh="warm_river", lowland=[("alikr_dunes", 1), ("alikr_desert", 3)], upland=[("dragontail_mountains", 1)],
    peak="dragontail_mountains", summit="dragontail_mountains", cave=CAVE_HOT, deep_cave=DEEP_HOT)
pal("province/high_rock", fallback="default",
    ocean="cold_ocean", deep_ocean="deep_cold_ocean", beach=[("rocky_shore", 1), ("temperate_beach", 1)],
    river="bjoulsae_river", lake="bjoulsae_river", marsh="glenumbra_moors",
    lowland=[("glenumbra_moors", 1), ("wayrest_farmland", 1), ("stormhaven", 1)], upland="bangkorai",
    peak="wrothgarian_mountains", summit="snowy_slopes", cave=CAVE_TEMPERATE, deep_cave=DEEP_TEMPERATE)
pal("province/atmora", fallback="default",
    ocean="sea_of_ghosts", deep_ocean="deep_sea_of_ghosts", beach="frozen_shore", river="frozen_river",
    lake="frozen_river", marsh="atmoran_ice_waste", lowland=[("atmoran_ice_waste", 1), ("atmoran_frozen_forest", 1)],
    upland="atmoran_ice_waste", peak="snowy_slopes", summit="throat_of_the_world", cave=CAVE_FROZEN,
    deep_cave=DEEP_FROZEN)
pal("province/akavir", fallback="default",
    ocean="warm_ocean", deep_ocean="deep_warm_ocean", beach="tropical_beach", river="warm_river", lake="warm_river",
    marsh="tsaesci_jungle", lowland=[("tsaesci_jungle", 1), ("tang_mo_islands", 1)], upland="ka_po_tun_steppe",
    peak="snowy_slopes", cave=CAVE_WET, deep_cave=DEEP_WET)
pal("province/yokuda", fallback="default",
    ocean="warm_ocean", deep_ocean="deep_warm_ocean", beach="warm_beach", river="warm_river", lake="warm_river",
    marsh="sunken_isles", lowland="yokudan_desert", upland="dragontail_mountains", peak="dragontail_mountains",
    summit="dragontail_mountains", cave=CAVE_HOT, deep_cave=DEEP_HOT)
pal("province/pyandonea", fallback="default",
    ocean="coral_sea", deep_ocean="deep_warm_ocean", beach="tropical_beach", river="jungle_river", lake="jungle_river",
    marsh="valenwood_mangrove_coast", lowland="pyandonean_jungle_isles", upland="pyandonean_jungle_isles",
    peak="pyandonean_jungle_isles", summit="pyandonean_jungle_isles", cave=CAVE_WET, deep_cave=DEEP_WET)
pal("province/thras", fallback="default",
    ocean="coral_sea", deep_ocean="deep_warm_ocean", beach="tropical_beach", river="warm_river", lake="warm_river",
    marsh="thras_coral", lowland="thras_coral", upland="thras_coral", peak="crystal_spires", summit="crystal_spires",
    cave=CAVE_MAGIC, deep_cave=DEEP_MAGIC)
pal("province/sea", fallback="default", ocean="temperate_ocean", deep_ocean="deep_temperate_ocean",
    beach="temperate_beach", lowland="temperate_beach", upland="rocky_shore")
pal("province/realm", fallback="default")

# --- Skyrim's holds
# The upland/peak heights follow each hold's ground (TERRAIN2 raised the south: the Rift and Falkreath are highlands
# at y 150-190 whose lowland is their forest, not the province's upland mix).
pal("skyrim/whiterun", fallback="province/skyrim", upland_y=130, bias={"radial": [62, -68, 450, 1400, 0.55]},
    # the golden tundra plain right up to the city walls; pines only on the foothills round its edge (upland)
    lowland=[("whiterun_tundra", 4), ("whiterun_plains", 3)],
    upland=[("whiterun_tundra", 1), ("skyrim_highlands", 2), ("pine_forest", 2), ("alpine_meadow", 1)],
    marsh="whiterun_plains")
pal("skyrim/falkreath", fallback="province/skyrim", upland_y=200, peak_y=280,
    lowland=[("pine_forest", 2), ("falkreath_forest", 4)], upland=[("falkreath_forest", 1), ("pine_forest", 2),
                                                                     ("skyrim_highlands", 1)],
    marsh="falkreath_forest")
pal("skyrim/the_rift", fallback="province/skyrim", upland_y=212, peak_y=290,
    bias={"radial": [2250, 1420, 450, 1300, 0.8]},
    # golden and orange aspens round Riften and Lake Honrich; pines climb the valley sides
    lowland=[("rift_autumn_forest", 7), ("alpine_meadow", 1)],
    upland=[("rift_autumn_forest", 3), ("pine_forest", 2), ("alpine_meadow", 1)], marsh="rift_autumn_forest")
pal("skyrim/the_reach", fallback="province/skyrim", upland_y=150, peak_y=270,
    lowland=[("reach_crags", 2), ("reach_valley", 3)], upland=[("reach_crags", 4), ("skyrim_highlands", 1)],
    peak=[("reach_crags", 1), ("snowy_slopes", 2)], marsh="reach_valley")
pal("skyrim/hjaalmarch", fallback="province/skyrim", upland_y=112,
    lowland=[("hjaalmarch_marsh", 3), ("whiterun_tundra", 1), ("snowy_pine_forest", 1)],
    upland=[("skyrim_highlands", 1), ("snowy_pine_forest", 2)], marsh="hjaalmarch_marsh",
    beach=[("skyrim_coast", 2), ("frozen_shore", 1)])
pal("skyrim/haafingar", fallback="province/skyrim", upland_y=145,
    lowland=[("skyrim_highlands", 1), ("pine_forest", 2), ("snowy_pine_forest", 1)],
    upland=[("skyrim_highlands", 2), ("snowy_pine_forest", 2)], beach=[("rocky_shore", 1), ("skyrim_coast", 2)])
pal("skyrim/the_pale", fallback="province/skyrim", upland_y=145, peak_y=205,
    lowland=[("pale_snowfield", 3), ("snowy_pine_forest", 2)], upland=[("pale_snowfield", 2), ("snowy_slopes", 1)],
    marsh="pale_snowfield", river="frozen_river", lake="frozen_river", beach="frozen_shore")
pal("skyrim/winterhold", fallback="province/skyrim", upland_y=145, peak_y=200,
    lowland=[("winterhold_glacier", 2), ("pale_snowfield", 2)], upland=[("winterhold_glacier", 2), ("snowy_slopes", 1)],
    marsh="winterhold_glacier", river="frozen_river", lake="frozen_river", beach="frozen_shore",
    cave=[("ice_caves", 3), (SURFACE, 3)])
pal("skyrim/eastmarch", fallback="province/skyrim",
    lowland=[("eastmarch_volcanic_tundra", 3), ("eastmarch_hot_springs", 2), ("snowy_pine_forest", 1)],
    upland=[("eastmarch_volcanic_tundra", 1), ("skyrim_highlands", 2), ("snowy_pine_forest", 1)],
    marsh="eastmarch_hot_springs", lake="eastmarch_hot_springs", beach="frozen_shore")

# --- Morrowind
# Solstheim: Skaal pine forests and snow in the north, the ash wastes of Red Mountain's fall-out in the south
pal("morrowind/solstheim", fallback="province/morrowind", upland_y=128, peak_y=205,
    bias={"linear": [4600, -3250, 0, 1, 330, 0.65]},
    lowland=[("solstheim_pine_forest", 3), ("pale_snowfield", 1), ("solstheim_ashlands", 3)],
    upland=[("snowy_slopes", 1), ("solstheim_pine_forest", 2), ("solstheim_ashlands", 2)], peak="snowy_slopes",
    summit="snowy_slopes", ocean="sea_of_ghosts", deep_ocean="deep_sea_of_ghosts",
    beach=[("ash_shore", 1), ("frozen_shore", 1)], river="skyrim_river", lake="skyrim_lake",
    marsh="solstheim_ashlands", cave=CAVE_SKYRIM, deep_cave=DEEP_SKYRIM)
pal("morrowind/vvardenfell_ashlands", fallback="province/morrowind",
    lowland=[("vvardenfell_ashlands", 4), ("stonefalls", 1)], upland="vvardenfell_ashlands")
pal("morrowind/red_mountain", fallback="province/morrowind", upland_y=95, peak_y=190,
    lowland=[("vvardenfell_ashlands", 1), ("red_mountain", 2)], upland="red_mountain", peak="red_mountain",
    cave=[("lava_caves", 4), (SURFACE, 2)], deep_cave=[("lava_caves", 3), ("dwemer_caverns", 2)])
pal("morrowind/bitter_coast", fallback="province/morrowind",
    lowland=[("bitter_coast_swamp", 4), ("ascadian_isles", 1)], upland="west_gash", marsh="bitter_coast_swamp",
    beach=[("bitter_coast_swamp", 1), ("ash_shore", 1)], ocean="inner_sea")
pal("morrowind/west_gash", fallback="province/morrowind",
    lowland=[("west_gash", 3), ("grazelands", 1)], upland=[("west_gash", 2), ("vvardenfell_ashlands", 1)])
pal("morrowind/ascadian_isles", fallback="province/morrowind",
    lowland=[("ascadian_isles", 4), ("bitter_coast_swamp", 1)], upland=[("ascadian_isles", 2), ("west_gash", 1)],
    marsh="bitter_coast_swamp", beach="temperate_beach", cave=CAVE_TEMPERATE)
pal("morrowind/grazelands", fallback="province/morrowind",
    lowland=[("grazelands", 5), ("vvardenfell_ashlands", 1)], upland=[("grazelands", 2), ("west_gash", 1)])
pal("morrowind/molag_amur", fallback="province/morrowind",
    lowland=[("molag_amur", 3), ("vvardenfell_ashlands", 1)], upland="molag_amur", peak="red_mountain",
    cave=[("lava_caves", 3), (SURFACE, 3)])
pal("morrowind/azuras_coast", fallback="province/morrowind",
    lowland=[("azuras_coast", 4), ("telvanni_mushroom_forest", 1)], upland="azuras_coast",
    beach=[("rocky_shore", 1), ("ash_shore", 1)], ocean="temperate_ocean")
pal("morrowind/sheogorad", fallback="province/morrowind",
    lowland=[("sheogorad", 4), ("azuras_coast", 1)], upland="sheogorad", beach="rocky_shore", ocean="cold_ocean",
    deep_ocean="deep_cold_ocean")
pal("morrowind/telvannis", fallback="province/morrowind",
    lowland=[("telvanni_mushroom_forest", 4), ("azuras_coast", 1)], upland="telvanni_mushroom_forest",
    beach="rocky_shore", ocean="temperate_ocean", cave=[("mushroom_grottos", 3), (SURFACE, 3)])
pal("morrowind/deshaan", fallback="province/morrowind",
    lowland=[("deshaan_plains", 4), ("stonefalls", 1)], upland=[("deshaan_plains", 1), ("west_gash", 1)],
    marsh="deshaan_plains", cave=CAVE_TEMPERATE)
pal("morrowind/stonefalls", fallback="province/morrowind",
    lowland=[("stonefalls", 4), ("deshaan_plains", 1)], upland=[("stonefalls", 1), ("vvardenfell_ashlands", 1)],
    peak="red_mountain")

# --- Cyrodiil
pal("cyrodiil/jerall_mountains", fallback="province/cyrodiil", upland_y=120, peak_y=250,
    lowland=[("jerall_foothills", 3), ("great_forest", 1)], upland="jerall_foothills", peak="snowy_slopes",
    river="skyrim_river", lake="skyrim_lake", cave=CAVE_SKYRIM)
pal("cyrodiil/colovian_highlands", fallback="province/cyrodiil",
    lowland=[("colovian_highlands", 3), ("heartlands", 1)], upland=[("colovian_highlands", 2),
                                                                 ("jerall_foothills", 1)])
pal("cyrodiil/gold_coast", fallback="province/cyrodiil",
    lowland=[("gold_coast", 4), ("west_weald_vineyards", 1)], upland="colovian_highlands",
    beach=[("temperate_beach", 2), ("rocky_shore", 1)], ocean="temperate_ocean")
pal("cyrodiil/west_weald", fallback="province/cyrodiil",
    lowland=[("west_weald_vineyards", 4), ("heartlands", 1)], upland=[("colovian_highlands", 1)])
pal("cyrodiil/heartlands", fallback="province/cyrodiil",
    lowland=[("heartlands", 4), ("nibenay_valley", 1), ("great_forest", 1)], lake="lake_rumare_shore",
    beach="lake_rumare_shore", marsh="lake_rumare_shore")
pal("cyrodiil/great_forest", fallback="province/cyrodiil",
    lowland=[("great_forest", 5), ("heartlands", 1)], upland="great_forest")
pal("cyrodiil/nibenay_basin", fallback="province/cyrodiil",
    lowland=[("heartlands", 2), ("nibenay_valley", 3), ("great_forest", 1)], upland="colovian_highlands")
pal("cyrodiil/nibenay_valley", fallback="province/cyrodiil",
    lowland=[("nibenay_valley", 4), ("blackwood_swamp", 1)], marsh="blackwood_swamp")
pal("cyrodiil/blackwood", fallback="province/cyrodiil",
    lowland=[("nibenay_valley", 1), ("blackwood_swamp", 4)], upland="great_forest", marsh="blackwood_swamp",
    beach="mangrove_delta", ocean="warm_ocean", deep_ocean="deep_warm_ocean", river="murky_river",
    cave=CAVE_WET, deep_cave=DEEP_WET)

# --- High Rock
pal("high_rock/glenumbra", fallback="province/high_rock",
    lowland=[("glenumbra_moors", 4), ("stormhaven", 1)], upland="glenumbra_moors")
pal("high_rock/stormhaven", fallback="province/high_rock",
    lowland=[("wayrest_farmland", 1), ("stormhaven", 4)], upland="bangkorai")
pal("high_rock/rivenspire", fallback="province/high_rock", peak_y=200,
    lowland=[("rivenspire", 4), ("glenumbra_moors", 1)], upland="rivenspire", peak="wrothgarian_mountains",
    cave=CAVE_MAGIC)
pal("high_rock/wrothgar", fallback="province/high_rock", upland_y=110, peak_y=250,
    lowland=[("wrothgarian_mountains", 2), ("bangkorai", 1)], upland="wrothgarian_mountains",
    peak=[("wrothgarian_mountains", 1), ("snowy_slopes", 2)], cave=CAVE_SKYRIM, deep_cave=DEEP_SKYRIM)
pal("high_rock/wayrest", fallback="province/high_rock",
    lowland=[("wayrest_farmland", 4), ("stormhaven", 1)], beach="temperate_beach", ocean="temperate_ocean")
pal("high_rock/bjoulsae", fallback="province/high_rock",
    lowland=[("wayrest_farmland", 2), ("glenumbra_moors", 2)], marsh="glenumbra_moors")

# --- Hammerfell
pal("hammerfell/alikr_desert", fallback="province/hammerfell",
    lowland=[("alikr_dunes", 2), ("alikr_desert", 4)], upland=[("alikr_desert", 1), ("dragontail_mountains", 1)])
pal("hammerfell/bangkorai", fallback="province/hammerfell",
    lowland="bangkorai", upland="bangkorai", peak="wrothgarian_mountains", summit="snowy_slopes",
    river="bjoulsae_river", lake="bjoulsae_river", beach="rocky_shore", cave=CAVE_TEMPERATE,
    deep_cave=DEEP_TEMPERATE)
pal("hammerfell/craglorn", fallback="province/hammerfell",
    lowland=[("craglorn", 4), ("alikr_desert", 1)], upland="craglorn", peak="dragontail_mountains")
pal("hammerfell/stros_mkai", fallback="province/hammerfell",
    lowland=[("stros_mkai_coast", 2), ("sunken_isles", 1)], upland="dragontail_mountains",
    beach="stros_mkai_coast", ocean="coral_sea")
pal("hammerfell/hews_bane", fallback="province/hammerfell",
    lowland=[("alikr_desert", 2), ("stros_mkai_coast", 1), ("elsweyr_savanna", 1)], beach="tropical_beach")
pal("hammerfell/dragontail", fallback="province/hammerfell", upland_y=95,
    lowland=[("alikr_desert", 1), ("dragontail_mountains", 3)], upland="dragontail_mountains")

# --- Black Marsh
pal("black_marsh/shadowfen", fallback="province/black_marsh",
    lowland=[("shadowfen", 4), ("deep_mire", 1)], upland="shadowfen", marsh="deep_mire")
pal("black_marsh/murkmire", fallback="province/black_marsh",
    lowland=[("murkmire", 4), ("hist_grove", 1)], upland="murkmire", marsh="deep_mire")
pal("black_marsh/gideon", fallback="province/black_marsh",
    lowland=[("black_marsh_swamp", 4), ("deep_mire", 1)], marsh="deep_mire")
pal("black_marsh/stormhold", fallback="province/black_marsh",
    lowland=[("black_marsh_swamp", 3), ("hist_grove", 2)], upland="black_marsh_swamp")
pal("black_marsh/helstrom", fallback="province/black_marsh",
    lowland=[("hist_grove", 1), ("black_marsh_swamp", 2), ("shadowfen", 1)])
pal("black_marsh/lilmoth", fallback="province/black_marsh",
    lowland=[("mangrove_delta", 3), ("black_marsh_swamp", 2)], beach="mangrove_delta", ocean="coral_sea")

# --- Elsweyr
pal("elsweyr/anequina", fallback="province/elsweyr",
    lowland=[("elsweyr_dunes", 2), ("anequina_badlands", 3), ("elsweyr_savanna", 1)],
    upland=[("anequina_badlands", 2), ("elsweyr_canyons", 2)])
pal("elsweyr/rimmen", fallback="province/elsweyr",
    lowland=[("anequina_badlands", 1), ("elsweyr_savanna", 3), ("sugar_fields", 1)], upland="elsweyr_canyons")
pal("elsweyr/torval", fallback="province/elsweyr",
    lowland=[("elsweyr_savanna", 2), ("sugar_fields", 2), ("pellitine_jungle", 1)], upland="elsweyr_savanna")
pal("elsweyr/pellitine", fallback="province/elsweyr",
    lowland=[("sugar_fields", 1), ("pellitine_jungle", 4)], upland="pellitine_jungle", river="jungle_river",
    lake="jungle_river", beach="tropical_beach", cave=CAVE_WET, deep_cave=DEEP_WET)
pal("elsweyr/senchal", fallback="province/elsweyr",
    lowland=[("sugar_fields", 2), ("pellitine_jungle", 2)], beach="tropical_beach", ocean="coral_sea")

# --- Valenwood
pal("valenwood/grahtwood", fallback="province/valenwood",
    lowland=[("valenwood_rainforest", 1), ("graht_oak_forest", 4)], upland="graht_oak_forest")
pal("valenwood/greenshade", fallback="province/valenwood",
    lowland=[("graht_oak_forest", 2), ("valenwood_rainforest", 3)], beach="valenwood_mangrove_coast")
pal("valenwood/malabal_tor", fallback="province/valenwood",
    lowland=[("graht_oak_forest", 3), ("valenwood_rainforest", 2)])
pal("valenwood/reapers_march", fallback="province/valenwood",
    lowland=[("reapers_march", 4), ("graht_oak_forest", 1)], upland="reapers_march", river="warm_river")

# --- Summerset
pal("summerset/summerset_isle", fallback="province/summerset",
    lowland=[("crystal_coast", 1), ("summerset_meadow", 4), ("auridon_vineyards", 1)])
pal("summerset/auridon", fallback="province/summerset",
    lowland=[("auridon_vineyards", 3), ("summerset_meadow", 2)], upland="summerset_meadow")
pal("summerset/artaeum", fallback="province/summerset",
    lowland=[("artaeum", 5), ("crystal_coast", 1)], upland="artaeum", peak="crystal_spires", beach="summerset_shore")

# --- foreign lands
pal("atmora/ice_waste", fallback="province/atmora", lowland="atmoran_ice_waste", upland="atmoran_ice_waste")
pal("atmora/frozen_forest", fallback="province/atmora",
    lowland=[("atmoran_ice_waste", 1), ("atmoran_frozen_forest", 4)], upland="atmoran_frozen_forest")
pal("akavir/tsaesci", fallback="province/akavir", lowland="tsaesci_jungle", upland="tsaesci_jungle",
    marsh="tsaesci_jungle")
pal("akavir/kamal", fallback="province/akavir", lowland="kamal_snowfield", upland="kamal_snowfield",
    peak="snowy_slopes", ocean="sea_of_ghosts", deep_ocean="deep_sea_of_ghosts", beach="frozen_shore",
    river="frozen_river", lake="frozen_river", marsh="kamal_snowfield", cave=CAVE_FROZEN, deep_cave=DEEP_FROZEN)
pal("akavir/tang_mo", fallback="province/akavir", lowland="tang_mo_islands", upland="tang_mo_islands",
    beach="tropical_beach", ocean="coral_sea")
pal("akavir/ka_po_tun", fallback="province/akavir", lowland="ka_po_tun_steppe", upland="ka_po_tun_steppe",
    peak="snowy_slopes", beach="rocky_shore", ocean="temperate_ocean", cave=CAVE_TEMPERATE)
pal("yokuda/yokudan_desert", fallback="province/yokuda", lowland="yokudan_desert")
pal("yokuda/sunken_isles", fallback="province/yokuda", lowland=[("sunken_isles", 3), ("yokudan_desert", 1)],
    upland="sunken_isles", beach="tropical_beach", ocean="coral_sea")
pal("pyandonea/maormer_isles", fallback="province/pyandonea",
    lowland=[("pyandonean_jungle_isles", 4), ("tang_mo_islands", 1)])
pal("thras/coral_kingdom", fallback="province/thras", lowland="thras_coral")

# --- named seas (the region at open sea)
pal("sea/sea_of_ghosts", fallback="province/skyrim", lowland="frozen_shore", upland="frozen_shore")
pal("sea/abecean_sea", fallback="province/sea", ocean="temperate_ocean", deep_ocean="deep_temperate_ocean")
pal("sea/iliac_bay", fallback="province/sea", ocean="temperate_ocean", deep_ocean="deep_temperate_ocean",
    beach="temperate_beach")
pal("sea/topal_bay", fallback="province/sea", ocean="warm_ocean", deep_ocean="deep_warm_ocean",
    beach="tropical_beach", lowland="tropical_beach")
pal("sea/inner_sea", fallback="province/morrowind", ocean="inner_sea", deep_ocean="inner_sea", beach="ash_shore")
pal("sea/padomaic_ocean", fallback="province/sea", ocean=[("warm_ocean", 3), ("coral_sea", 1)],
    deep_ocean="deep_warm_ocean", beach="tropical_beach", lowland="tropical_beach")
pal("sea/eltheric_ocean", fallback="province/sea", ocean="cold_ocean", deep_ocean="deep_cold_ocean",
    beach="rocky_shore", lowland="rocky_shore")

# --- realm archetypes (the endless lands); generated regions name one of these as their palette
pal("realm/frozen", fallback="province/atmora",
    lowland=[("atmoran_ice_waste", 2), ("kamal_snowfield", 1), ("atmoran_frozen_forest", 2), ("pale_snowfield", 1)],
    upland=[("snowy_slopes", 1), ("atmoran_ice_waste", 1)], marsh="pale_snowfield")
pal("realm/temperate", fallback="default",
    lowland=[("heartlands", 2), ("stormhaven", 2), ("great_forest", 2), ("glenumbra_moors", 1), ("pine_forest", 1)],
    upland=[("colovian_highlands", 2), ("bangkorai", 1), ("skyrim_highlands", 1)], beach=[("rocky_shore", 1),
                                                                                        ("temperate_beach", 2)])
pal("realm/ash", fallback="province/morrowind",
    lowland=[("solstheim_ashlands", 1), ("vvardenfell_ashlands", 3), ("stonefalls", 1), ("molag_amur", 1)],
    upland=[("molag_amur", 1), ("vvardenfell_ashlands", 1)], peak="red_mountain")
pal("realm/swamp", fallback="province/black_marsh",
    lowland=[("hjaalmarch_marsh", 1), ("black_marsh_swamp", 2), ("shadowfen", 1), ("deep_mire", 1),
             ("hist_grove", 1), ("bitter_coast_swamp", 1)])
pal("realm/desert", fallback="province/hammerfell",
    lowland=[("alikr_dunes", 2), ("alikr_desert", 2), ("yokudan_desert", 2), ("elsweyr_dunes", 1)],
    upland=[("craglorn", 1), ("anequina_badlands", 1), ("dragontail_mountains", 1)])
pal("realm/jungle", fallback="province/valenwood",
    lowland=[("pellitine_jungle", 1), ("valenwood_rainforest", 2), ("tsaesci_jungle", 2), ("graht_oak_forest", 1),
             ("pyandonean_jungle_isles", 1)])
pal("realm/crystal", fallback="province/summerset",
    lowland=[("crystal_coast", 2), ("crystal_spires", 3), ("artaeum", 1)], upland="crystal_spires",
    peak="crystal_spires")
pal("realm/savanna", fallback="province/elsweyr",
    lowland=[("ka_po_tun_steppe", 2), ("elsweyr_savanna", 3), ("grazelands", 1), ("sugar_fields", 1)],
    upland=[("elsweyr_canyons", 1), ("ka_po_tun_steppe", 1)])
pal("realm/mushroom", fallback="province/morrowind",
    lowland=[("sheogorad", 1), ("telvanni_mushroom_forest", 4)], upland="telvanni_mushroom_forest",
    beach="rocky_shore", ocean="temperate_ocean", cave=[("mushroom_grottos", 3), (SURFACE, 3)],
    deep_cave=[("falmer_glow_caves", 2), (SURFACE, 2)])
pal("realm/alpine", fallback="province/skyrim", upland_y=100, peak_y=200,
    lowland=[("alpine_meadow", 2), ("pine_forest", 2), ("rift_autumn_forest", 1)],
    upland=[("alpine_meadow", 1), ("skyrim_highlands", 2), ("wrothgarian_mountains", 1)],
    peak="snowy_slopes", summit="throat_of_the_world")


# ================================================================================================= writers
def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def step_of_all():
    where = {}
    for step in STEPS:
        for f in canonical(step):
            assert f not in where, "feature in two steps: " + f
            where[f] = step
    return where


def resolve_feature(name):
    if ":" in name:
        return name
    return ref(name)


def biome_json(b, where, order):
    steps = {s: [] for s in STEPS}
    for f in b["features"]:
        fid = resolve_feature(f)
        assert fid not in MODIFIER_FEATURES, (b["id"], fid)
        assert fid in where, "unknown feature %s in %s" % (fid, b["id"])
        if fid not in steps[where[fid]]:
            steps[where[fid]].append(fid)
    feats = [sorted(steps[s], key=lambda f: order[s][f]) for s in STEPS]
    eff = {"sky_color": rgb(b["sky"]), "fog_color": rgb(b["fog"]), "water_color": rgb(b["water"]),
           "water_fog_color": rgb(b["water_fog"])}
    if b["grass"]:
        eff["grass_color"] = rgb(b["grass"])
    if b["foliage"]:
        eff["foliage_color"] = rgb(b["foliage"])
    if b["particle"]:
        eff["particle"] = b["particle"]
    eff["mood_sound"] = {"sound": "minecraft:ambient.cave", "tick_delay": 6000, "block_search_extent": 8,
                         "offset": 2.0}
    out = {"has_precipitation": b["precip"], "temperature": b["temp"]}
    if b["frozen"]:
        out["temperature_modifier"] = "frozen"
    out["downfall"] = b["downfall"]
    out["effects"] = eff
    # Skycraft creatures come from the region spawner (data/skycraft/region_spawns), never from biome spawners,
    # so they don't spawn twice; biomes keep only vanilla wildlife.
    def vanilla(lst):
        return [e for e in lst if not e["type"].startswith("skycraft:")]
    out["spawners"] = {
        "monster": vanilla(b["monster"]), "creature": vanilla(b["creature"]), "ambient": vanilla(b["ambient"]),
        "axolotls": [], "underground_water_creature": vanilla(b["underground"]),
        "water_creature": vanilla(b["water_creature"]), "water_ambient": vanilla(b["water_ambient"]), "misc": []}
    out["spawn_costs"] = {}
    out["carvers"] = {"air": ["minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"]}
    out["features"] = feats
    return out


def rgb(h):
    return int(h.lstrip("#"), 16)


def build_tags():
    tags = {}

    def add(tag, bid):
        tags.setdefault(tag, [])
        if bid not in tags[tag]:
            tags[tag].append(bid)

    TOKENS["rare"] = ["forge:is_rare"]
    TOKENS["dense"] = ["forge:is_dense", "forge:is_dense/overworld"]
    for bid, b in BIOMES.items():
        full = SK + ":" + bid
        add("minecraft:is_overworld", full)
        add("c:in_overworld", full)
        kind = b["kind"]
        add(SK + ":tamriel/" + {"land": "land", "beach": "coast", "river": "water", "lake": "water",
                                 "ocean": "sea", "cave": "underground"}[kind], full)
        add(SK + ":tamriel/all", full)
        if b["province"] not in ("underground",):
            add(SK + ":province/" + b["province"], full)
        if kind in ("land", "beach", "river", "lake"):
            for t in LAND_STRUCTURE_TAGS:
                add(t, full)
        if kind == "beach":
            add("minecraft:has_structure/shipwreck_beached", full)
            add("minecraft:has_structure/buried_treasure", full)
        if kind == "land":
            add("minecraft:has_structure/ruined_portal_standard", full)
            if "mountain" in b["tags"]:
                add("minecraft:has_structure/ruined_portal_mountain", full)
            if "swamp" in b["tags"]:
                add("minecraft:has_structure/ruined_portal_swamp", full)
        if kind == "cave":
            add("minecraft:has_structure/mineshaft", full)
        for tok in b["tags"]:
            assert tok in TOKENS, (bid, tok)
            for t in TOKENS[tok]:
                add(t, full)
        if kind == "ocean":
            add("minecraft:plays_underwater_music", full)
    # Skycraft dungeons may use every land biome: the dungeon workstreams filter by region culture
    add(SK + ":dungeon_land", "#" + SK + ":tamriel/land")
    return tags


def tag_path(tag):
    ns, path = tag.split(":")
    return os.path.join(DATA, ns, "tags", "worldgen", "biome", *path.split("/")) + ".json"


def write_tags(tags):
    """Writes every tag; plain skycraft:<biome> entries are always regenerated (Phase 1 entries are kept), and
    stale tag files that only held generated entries are removed."""
    existing_files = set()
    for ns in ("minecraft", "forge", "c", SK):
        base = os.path.join(DATA, ns, "tags", "worldgen", "biome")
        for dirpath, _, files in os.walk(base):
            for fn in files:
                if fn.endswith(".json"):
                    rel = os.path.relpath(os.path.join(dirpath, fn), base)[:-5].replace(os.sep, "/")
                    existing_files.add(ns + ":" + rel)
    for tag in sorted(existing_files | set(tags)):
        values = tags.get(tag, [])
        path = tag_path(tag)
        keep = []
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                old = json.load(f).get("values", [])
            for v in old:
                if isinstance(v, str) and v.startswith(SK + ":") and v.split(":", 1)[1] in BIOMES:
                    continue   # generated biome entries (other modules' biomes, e.g. the realms, are kept)
                if (v if isinstance(v, str) else v.get("id")) in values:
                    continue
                keep.append(v)
            if not keep and not values:
                os.remove(path)
                continue
        new = keep + sorted(values)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                if json.load(f).get("values", []) == new:
                    continue   # unchanged: leave the file (and its formatting) alone
        if new:
            dump(path, {"replace": False, "values": new})


def check_tags(tags):
    river = set(tags.get("minecraft:is_river", []))
    swamp = set(tags.get(SK + ":spawns/swamp", []))
    both = river & swamp
    assert not both, "biomes in both #is_river and #spawns/swamp get skycraft:nirnroot_patch twice: %s" % both


def check_palettes():
    known = {SK + ":" + b for b in BIOMES} | VANILLA_CAVES
    for key, p in PALETTES.items():
        for ctx, choices in p["contexts"].items():
            for c in choices:
                b = c["biome"]
                assert b == SURFACE and ctx in ("cave", "deep_cave") or b in known, (key, ctx, b)
        # every palette must resolve ocean, beach and river through its fallback chain
        for ctx in CONTEXTS:
            k = key
            while k and ctx not in PALETTES[k]["contexts"]:
                k = PALETTES[k].get("fallback")
                assert k is None or k in PALETTES, (key, k)
            assert k, "palette %s cannot resolve %s" % (key, ctx)


def surface_rule():
    groups = {}
    caves = {}
    for bid, b in BIOMES.items():
        key = json.dumps(b["surface"], sort_keys=True)
        (caves if b["surface"].get("cave") else groups).setdefault(key, []).append(SK + ":" + bid)
    land_rules = [cond(biome_is(ids), build_surface(json.loads(k))) for k, ids in groups.items()]
    # Cave biomes only dress cave floors and ceilings. The cheap stone-depth test comes first so the biome (a costly
    # lookup) is only asked for those few blocks, never for the solid rock in between.
    floor_rules = [cond(biome_is(ids), build_cave_floor(json.loads(k))) for k, ids in caves.items()]
    ceiling_rules = [cond(biome_is(ids), build_cave_ceiling(json.loads(k))) for k, ids in caves.items()
                     if json.loads(k)["ceiling"]]
    bedrock = cond({"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor",
                    "true_at_and_below": {"above_bottom": 0}, "false_at_and_above": {"above_bottom": 5}},
                   SB("bedrock"))
    deepslate = cond({"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate",
                      "true_at_and_below": {"absolute": 0}, "false_at_and_above": {"absolute": 8}},
                     SB("deepslate", axis="y"))
    default = build_surface(S_TEMPERATE)
    # No preset reaches deeper than 30 blocks under a floor: skip the biome tests for the rock below that.
    land = cond(ABOVE_PRELIM, cond(deep_under(30), seq(*land_rules, default)))
    return seq(bedrock, land, cond(UNDER_FLOOR, seq(*floor_rules)),
               cond(ON_CEILING, seq(*ceiling_rules)), deepslate)


def check_feature_order(biome_lists):
    """FeatureSorter's rule: the per-step lists of all biomes must be consistent (no cycle)."""
    for si, step in enumerate(STEPS):
        edges = {}
        for lists in biome_lists:
            lst = lists[si]
            for a, b in zip(lst, lst[1:]):
                edges.setdefault(a, set()).add(b)
        seen, stack = {}, []

        def visit(n):
            if seen.get(n) == 1:
                raise AssertionError("feature order cycle at %s in %s" % (n, step))
            if seen.get(n) == 2:
                return
            seen[n] = 1
            for m in edges.get(n, ()):
                visit(m)
            seen[n] = 2
        for n in list(edges):
            visit(n)


BLOCK_NAMES = {
    "ash_block": "Ash", "ash_layer": "Ash Layer", "volcanic_rock": "Volcanic Rock",
    "cracked_volcanic_rock": "Cracked Volcanic Rock", "magma_volcanic_rock": "Glowing Volcanic Rock",
    "golden_aspen_leaves": "Golden Aspen Leaves", "orange_aspen_leaves": "Autumn Aspen Leaves",
    "parasol_stem": "Emperor Parasol Stem", "parasol_cap": "Emperor Parasol Cap", "telvanni_cap": "Telvanni Cap",
    "glowing_mushroom_cap": "Glowing Mushroom Cap", "graht_oak_log": "Graht-oak Log", "graht_oak_wood": "Graht-oak Wood",
    "graht_oak_planks": "Graht-oak Planks", "graht_oak_leaves": "Graht-oak Leaves", "palm_log": "Palm Log",
    "palm_planks": "Palm Planks", "palm_fronds": "Palm Fronds", "hist_log": "Hist Log", "hist_leaves": "Hist Leaves",
    "hist_sap_block": "Hist Sap", "crystal_block": "Crystal", "crystal_cluster": "Crystal Cluster",
    "coral_stone": "Coral Stone", "dune_sand": "Dune Sand", "swamp_reeds": "Swamp Reeds", "scathecraw": "Scathecraw",
    "ash_grass": "Ash Grass", "frosted_stone": "Frost-covered Stone", "snowy_spruce_leaves": "Snowy Pine Needles",
}


def main():
    apply_flora()
    where = step_of_all()
    order = {s: {f: i for i, f in enumerate(canonical(s))} for s in STEPS}
    # placed features referenced by placed features must exist
    for name, pf in PF.items():
        assert pf["feature"] in [ref(n) for n in CF] or pf["feature"].startswith("minecraft:") or \
            pf["feature"] == MAP_WATER, (name, pf["feature"])
    for name, cf in CF.items():
        if cf["type"] == "minecraft:random_selector":
            for f in [c["feature"] for c in cf["config"]["features"]] + [cf["config"]["default"]]:
                assert f.startswith("minecraft:") or f in [ref(n) for n in PF], (name, f)

    # clean what this script generates
    # only this catalogue's own biomes: other modules (the realms) keep their biome files in the same folder
    bdir = os.path.join(DATA, SK, "worldgen", "biome")
    if os.path.isdir(bdir):
        for fn in os.listdir(bdir):
            if fn.endswith(".json") and fn[:-5] in BIOMES:
                os.remove(os.path.join(bdir, fn))
    for d in (os.path.join(DATA, SK, "worldgen", "configured_feature", "biome"),
              os.path.join(DATA, SK, "worldgen", "placed_feature", "biome")):
        if os.path.isdir(d):
            shutil.rmtree(d)
    for name, cf in CF.items():
        dump(os.path.join(DATA, SK, "worldgen", "configured_feature", "biome", *name.split("/")) + ".json", cf)
    for name, pf in PF.items():
        dump(os.path.join(DATA, SK, "worldgen", "placed_feature", "biome", *name.split("/")) + ".json", pf)
    # the map_water placeholder (the terrain workstream ships the real one)
    mw_cf = os.path.join(DATA, SK, "worldgen", "configured_feature", "map_water.json")
    mw_pf = os.path.join(DATA, SK, "worldgen", "placed_feature", "map_water.json")
    if not os.path.exists(mw_cf):
        dump(mw_cf, {"type": "minecraft:no_op", "config": {}})
    if not os.path.exists(mw_pf):
        dump(mw_pf, {"feature": MAP_WATER, "placement": []})

    lists = []
    for bid, b in BIOMES.items():
        js = biome_json(b, where, order)
        lists.append(js["features"])
        dump(os.path.join(DATA, SK, "worldgen", "biome", bid + ".json"), js)
    check_feature_order(lists)

    tags = build_tags()
    check_tags(tags)
    write_tags(tags)

    check_palettes()
    catalogue = sorted(SK + ":" + b for b in BIOMES) + sorted(VANILLA_CAVES)
    dump(os.path.join(RES, SK, "tamriel", "palettes.json"), {
        "_comment": "Generated by tools/worldgen/biomes.py. Region/palette -> terrain context -> weighted biomes.",
        "contexts": CONTEXTS, "catalogue": catalogue, "palettes": PALETTES})

    lang = dict(sorted(LANG.items()))
    for k, v in BLOCK_NAMES.items():
        lang["block.skycraft." + k] = v
    dump(os.path.join(ROOT, "mod", "src", "main", "lang", SK, "tamriel_biomes.json"), lang)

    rule = surface_rule()
    dump(os.path.join(ROOT, "tools", "worldgen", "generated", "surface_rule.json"), rule)
    print("biomes: %d, configured features: %d, placed features: %d, palettes: %d, tags: %d" % (
        len(BIOMES), len(CF), len(PF), len(PALETTES), len(tags)))


if __name__ == "__main__":
    main()
