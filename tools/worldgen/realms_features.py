"""The realms' configured and placed features, in one canonical order per generation step (see realms.py).

Every realm biome's feature lists are filtered from ORDER, so no "feature order cycle" can ever happen. Procedural
decorations are the Java feature nirncraft:realm_decor (com.nirncraft.realms.worldgen.decor) with a "kind".
"""
from realms import SK, st

STEPS = ["raw_generation", "lakes", "local_modifications", "underground_structures", "surface_structures",
         "strongholds", "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration",
         "top_layer_modification"]

CF = {}   # name -> configured feature
PF = {}   # name -> placed feature
ORDER = {s: [] for s in STEPS}
STEP_OF = {}


def feat(name, step, configured, placement, cf_name=None):
    """A placed feature {name}; its configured feature is {configured} (or the existing one named cf_name)."""
    cf = cf_name or name
    if configured is not None:
        CF[cf] = configured
    PF[name] = {"feature": SK + ":realm/" + cf, "placement": placement}
    ORDER[step].append(SK + ":realm/" + name)
    STEP_OF[name] = step


def count(n):
    return {"type": "minecraft:count", "count": n}


def rarity(n):
    return {"type": "minecraft:rarity_filter", "chance": n}


SQUARE = {"type": "minecraft:in_square"}
BIOME = {"type": "minecraft:biome"}


def hm(kind="WORLD_SURFACE_WG"):
    return {"type": "minecraft:heightmap", "heightmap": kind}


def height_range(lo, hi):
    return {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": {"absolute": lo},
                                                         "max_inclusive": {"absolute": hi}}}


def decor(kind, size=1):
    return {"type": "nirncraft:realm_decor", "config": {"kind": kind, "size": size}}


def simple(name, **props):
    return {"type": "minecraft:simple_state_provider", "state": st(name, **props)}


def weighted(*pairs):
    return {"type": "minecraft:weighted_state_provider",
            "entries": [{"data": s if isinstance(s, dict) else st(s), "weight": w} for s, w in pairs]}


def patch_of(provider, tries=48, xz=7, y=3, on=None, water=False):
    preds = [{"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}]
    if on:
        preds.append({"type": "minecraft:matching_blocks", "offset": [0, -1, 0], "blocks": on})
    if water:
        preds.append({"type": "minecraft:matching_fluids", "offset": [0, -1, 0], "fluids": "minecraft:water"})
    predicate = preds[0] if len(preds) == 1 else {"type": "minecraft:all_of", "predicates": preds}
    return {"type": "minecraft:random_patch", "config": {"tries": tries, "xz_spread": xz, "y_spread": y, "feature": {
        "feature": {"type": "minecraft:simple_block", "config": {"to_place": provider}},
        "placement": [{"type": "minecraft:block_predicate_filter", "predicate": predicate}]}}}


def plant_patch(provider, survive_state, tries=48, xz=7, y=3):
    """Plants placed only where they can grow."""
    return {"type": "minecraft:random_patch", "config": {"tries": tries, "xz_spread": xz, "y_spread": y, "feature": {
        "feature": {"type": "minecraft:simple_block", "config": {"to_place": provider}},
        "placement": [{"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:all_of", "predicates": [
            {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"},
            {"type": "minecraft:would_survive", "state": survive_state}]}}]}}}


def ore(target, state, size, discard=0.0):
    return {"type": "minecraft:ore", "config": {"size": size, "discard_chance_on_air_exposure": discard, "targets": [
        {"target": {"predicate_type": "minecraft:block_match", "block": target}, "state": st(state)}]}}


def spring(fluid, valid):
    return {"type": "minecraft:spring_feature", "config": {
        "state": {"Name": "minecraft:" + fluid, "Properties": {"falling": "true"}},
        "requires_block_below": True, "rock_count": 4, "hole_count": 1, "valid_blocks": valid}}


def surface_place(n, rare=None):
    mods = [count(n)] if n > 1 else []
    if rare:
        mods.append(rarity(rare))
    return mods + [SQUARE, hm(), BIOME]


S = SK + ":"
# ------------------------------------------------------------------------------------------------ the Deadlands
feat("dl_magma_vein", "underground_ores", ore(S + "deadlands_rock", S + "deadlands_magma_rock", 22),
     [count(7), SQUARE, height_range(8, 190), BIOME])
feat("dl_blackstone_blob", "underground_ores", ore(S + "deadlands_rock", "blackstone", 33),
     [count(4), SQUARE, height_range(8, 190), BIOME])
feat("dl_spike", "surface_structures", decor("deadlands_spike", 1), surface_place(2))
feat("dl_spike_dense", "surface_structures", decor("deadlands_spike", 2), surface_place(5))
feat("dl_lavafall", "fluid_springs", spring("lava", [S + "deadlands_rock", S + "deadlands_ash", "minecraft:blackstone"]),
     [count(12), SQUARE, height_range(44, 170), BIOME])
feat("dl_flames", "vegetal_decoration", decor("deadlands_flames", 1), surface_place(1, 2))
feat("dl_hanging_vines", "vegetal_decoration", decor("hanging_vines", 1), [count(6), SQUARE, height_range(44, 170), BIOME])
feat("dl_bloodgrass", "vegetal_decoration", plant_patch(simple(S + "bloodgrass"), st(S + "bloodgrass"), 64), surface_place(7))
feat("dl_bloodgrass_sparse", "vegetal_decoration", None, surface_place(1, 2), cf_name="dl_bloodgrass")
feat("dl_harrada", "vegetal_decoration", plant_patch(simple(S + "harrada"), st(S + "harrada"), 40, 6, 2), surface_place(2))
feat("dl_harrada_dense", "vegetal_decoration", None, surface_place(10), cf_name="dl_harrada")

# ------------------------------------------------------------------------------------------------ Sovngarde
GOLDEN_TREE = {"type": "minecraft:tree", "config": {
    "decorators": [], "ignore_vines": True, "force_dirt": False, "dirt_provider": simple("dirt"),
    "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 1, "lower_size": 0, "upper_size": 2},
    "foliage_provider": simple(S + "golden_aspen_leaves", distance=7, persistent=False, waterlogged=False),
    "foliage_placer": {"type": "minecraft:blob_foliage_placer", "radius": 3, "offset": 0, "height": 4},
    "trunk_provider": simple("birch_log", axis="y"),
    "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 7, "height_rand_a": 4, "height_rand_b": 2}}}
TREE_OK = {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:would_survive", "state": st("birch_sapling", stage=0)}}
DRY = {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 0}
feat("sg_golden_tree", "vegetal_decoration", GOLDEN_TREE, [rarity(3), SQUARE, DRY, hm("OCEAN_FLOOR"), BIOME, TREE_OK])
feat("sg_golden_tree_dense", "vegetal_decoration", None, [count(6), SQUARE, DRY, hm("OCEAN_FLOOR"), BIOME, TREE_OK],
     cf_name="sg_golden_tree")
SG_FLOWERS = weighted(("cornflower", 4), ("blue_orchid", 3), ("oxeye_daisy", 3), ("lily_of_the_valley", 2), ("azure_bluet", 2))
feat("sg_flowers", "vegetal_decoration", plant_patch(SG_FLOWERS, st("cornflower"), 40), surface_place(3))
feat("sg_flowers_dense", "vegetal_decoration", None, surface_place(9), cf_name="sg_flowers")
feat("sg_grass", "vegetal_decoration", plant_patch(weighted(("grass", 8), ("fern", 1)), st("grass"), 48), surface_place(8))
feat("sg_boulder", "local_modifications", {"type": "minecraft:forest_rock", "config": {"state": st("mossy_cobblestone")}},
     [rarity(4), SQUARE, hm("MOTION_BLOCKING"), BIOME])
feat("sg_runestone", "surface_structures", decor("sovngarde_runestone", 1), [rarity(7), SQUARE, hm(), BIOME])
feat("sg_cairn", "surface_structures", decor("sovngarde_cairn", 1), [rarity(10), SQUARE, hm(), BIOME])

# ------------------------------------------------------------------------------------------------ the Shivering Isles
feat("si_giant_mushroom", "surface_structures", decor("giant_mushroom", 2), surface_place(3))
feat("si_giant_mushroom_sparse", "surface_structures", decor("giant_mushroom", 1), surface_place(1, 3))
feat("si_crystal_tree", "surface_structures", decor("crystal_tree", 1), surface_place(2))
feat("si_crystal_tree_sparse", "surface_structures", None, surface_place(1, 6), cf_name="si_crystal_tree")
feat("si_gnarled_tree", "surface_structures", decor("gnarled_tree", 1), surface_place(4))
feat("si_gnarled_tree_sparse", "surface_structures", None, surface_place(1, 2), cf_name="si_gnarled_tree")
feat("si_mania_spire", "surface_structures", decor("mania_spire", 1), surface_place(1, 5))
SI_BLOOMS = weighted((S + "mania_bloom", 4), ("allium", 2), ("pink_tulip", 2), ("cornflower", 2), ("orange_tulip", 2), ("blue_orchid", 1))
feat("si_blooms", "vegetal_decoration", plant_patch(SI_BLOOMS, st("allium"), 48), surface_place(5))
feat("si_mania_grass", "vegetal_decoration", plant_patch(weighted(("grass", 6), ("fern", 1)), st("grass"), 48), surface_place(6))
feat("si_dementia_bulbs", "vegetal_decoration", plant_patch(simple(S + "dementia_bulb"), st(S + "dementia_bulb"), 24), surface_place(2))
feat("si_dementia_grass", "vegetal_decoration", plant_patch(weighted(("fern", 4), ("grass", 3)), st("fern"), 48), surface_place(7))
feat("si_lilypads", "vegetal_decoration", patch_of(simple("lily_pad"), 24, 7, 3, water=True), [count(4), SQUARE, hm(), BIOME])
feat("si_vines", "vegetal_decoration", {"type": "minecraft:vines", "config": {}}, [count(48), SQUARE, height_range(63, 130), BIOME])

# ------------------------------------------------------------------------------------------------ Apocrypha
feat("ap_glyph_vein", "underground_ores", ore(S + "apocrypha_stone", S + "apocrypha_glyph_stone", 14),
     [count(8), SQUARE, height_range(4, 200), BIOME])
feat("ap_book_stack", "surface_structures", decor("book_stack", 1), surface_place(3))
feat("ap_book_stack_sparse", "surface_structures", None, surface_place(1, 2), cf_name="ap_book_stack")
feat("ap_ink_tentacle", "surface_structures", decor("ink_tentacle", 1), [count(3), SQUARE, hm("OCEAN_FLOOR_WG"), BIOME])
feat("ap_ink_tentacle_sparse", "surface_structures", None, [rarity(3), SQUARE, hm("OCEAN_FLOOR_WG"), BIOME], cf_name="ap_ink_tentacle")
feat("ap_glyph_obelisk", "surface_structures", decor("glyph_obelisk", 1), surface_place(1, 4))

# ------------------------------------------------------------------------------------------------ the Soul Cairn
feat("sc_bone_pillar", "surface_structures", decor("bone_pillar", 1), surface_place(1, 5))
feat("sc_bone_pillar_dense", "surface_structures", None, surface_place(1, 2), cf_name="sc_bone_pillar")
feat("sc_soul_gem_spire", "surface_structures", decor("soul_gem_spire", 1), surface_place(2))
feat("sc_soul_gem_spire_sparse", "surface_structures", None, surface_place(1, 4), cf_name="sc_soul_gem_spire")
feat("sc_dead_tree", "surface_structures", decor("dead_tree", 1), surface_place(1, 2))
feat("sc_bone_ribs", "surface_structures", decor("bone_ribs", 1), surface_place(1, 6))
feat("sc_bone_ribs_dense", "surface_structures", None, surface_place(1, 2), cf_name="sc_bone_ribs")
feat("sc_soul_clusters", "vegetal_decoration",
     patch_of(simple(S + "soul_gem_cluster", facing="up", waterlogged=False), 24, 6, 2, on=S + "soul_stone"), surface_place(2))
feat("sc_soul_fire", "vegetal_decoration", patch_of(simple("soul_fire"), 16, 5, 2, on="minecraft:soul_soil"), surface_place(2))

# ------------------------------------------------------------------------------------------------ Coldharbour
feat("ch_lavafall", "fluid_springs", spring("lava", [S + "coldharbour_stone", "minecraft:blackstone", "minecraft:basalt"]),
     [count(8), SQUARE, height_range(58, 160), BIOME])
feat("ch_chain_pylon", "surface_structures", decor("chain_pylon", 1), surface_place(1, 3))
feat("ch_chain_pylon_dense", "surface_structures", None, surface_place(1), cf_name="ch_chain_pylon")
feat("ch_broken_pillar", "surface_structures", decor("broken_pillar", 1), surface_place(1, 2))
feat("ch_iron_spikes", "surface_structures", decor("iron_spikes", 1), surface_place(1, 2))
feat("ch_soul_fire_pit", "surface_structures", decor("soul_fire_pit", 1), surface_place(1, 3))
feat("ch_fire", "vegetal_decoration", patch_of(simple("fire"), 16, 6, 2, on="minecraft:blackstone"), surface_place(2))
feat("ch_soul_fire", "vegetal_decoration", patch_of(simple("soul_fire"), 16, 5, 2, on="minecraft:soul_soil"), surface_place(2))


def lists(names):
    """The biome JSON's "features": per step, the given placed features in canonical order."""
    want = {SK + ":realm/" + n for n in names}
    for n in names:
        if n not in PF:
            raise KeyError("unknown realm feature " + n)
    return [[f for f in ORDER[s] if f in want] for s in STEPS]
