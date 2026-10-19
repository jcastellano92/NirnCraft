"""The realms' biomes: per realm, the roles the biome source picks (RealmBiomeSource.java) and each biome's look.

Colours: sky/fog/water per biome (the realms' sky renderer reads the fog colour; vanilla uses the rest); grass and
foliage tints; one ambient particle. Spawning is the region spawner's (data/nirncraft/region_spawns/realms.json), so
biome spawners stay empty.
"""
from realms_features import lists

# role: (name, temperature, downfall, precipitation, sky, fog, water, water_fog, grass, foliage, particle, features)
B = {
    "deadlands": [
        ("lava_sea", "Lava Sea", 2.0, 0.0, False, 0xB0281A, 0x5A1208, 0x6B2A1A, 0x330A05, None, None, ("minecraft:ash", 0.006),
         ["dl_magma_vein", "dl_blackstone_blob", "dl_lavafall", "dl_spike", "dl_flames"]),
        ("ashen_wastes", "Ashen Wastes", 2.0, 0.0, False, 0xA02818, 0x4E1A12, 0x6B2A1A, 0x330A05, 0x6A3A2A, 0x5A2A20, ("minecraft:ash", 0.02),
         ["dl_magma_vein", "dl_blackstone_blob", "dl_lavafall", "dl_spike", "dl_flames", "dl_hanging_vines",
          "dl_bloodgrass_sparse", "dl_harrada"]),
        ("blood_fields", "Blood Fields", 2.0, 0.0, False, 0xC02A16, 0x641208, 0x6B2A1A, 0x330A05, 0x8A1A10, 0x7A1A12, ("minecraft:crimson_spore", 0.018),
         ["dl_magma_vein", "dl_blackstone_blob", "dl_lavafall", "dl_spike", "dl_flames", "dl_hanging_vines",
          "dl_bloodgrass", "dl_harrada"]),
        ("spires", "Burning Spires", 2.0, 0.0, False, 0x9A2010, 0x441008, 0x6B2A1A, 0x330A05, None, None, ("minecraft:white_ash", 0.01),
         ["dl_magma_vein", "dl_blackstone_blob", "dl_lavafall", "dl_spike_dense", "dl_flames", "dl_hanging_vines",
          "dl_bloodgrass_sparse"]),
        ("harrada_thicket", "Harrada Thicket", 2.0, 0.0, False, 0xA82A14, 0x56160C, 0x6B2A1A, 0x330A05, 0x7A2018, 0x6A1A12, ("minecraft:crimson_spore", 0.01),
         ["dl_magma_vein", "dl_blackstone_blob", "dl_lavafall", "dl_spike", "dl_hanging_vines", "dl_harrada_dense",
          "dl_bloodgrass_sparse"]),
    ],
    "sovngarde": [
        ("fields", "Fields of Sovngarde", 0.7, 0.4, True, 0x8FB4DC, 0xC8D4E2, 0x5AA0D0, 0x2A5A8A, 0xC8B860, 0xD8B040, ("minecraft:white_ash", 0.004),
         ["sg_boulder", "sg_runestone", "sg_cairn", "sg_golden_tree", "sg_flowers", "sg_grass"]),
        ("mistvale", "The Misty Vale", 0.6, 0.6, True, 0x9AAAC4, 0xB4C0D2, 0x5A90C0, 0x2A4A7A, 0xB8B070, 0xC8A850, ("minecraft:white_ash", 0.008),
         ["sg_boulder", "sg_runestone", "sg_golden_tree", "sg_flowers_dense", "sg_grass"]),
        ("chasm", "The Misty Chasm", 0.5, 0.6, True, 0x8A9AB4, 0xAAB6CA, 0x5A90C0, 0x2A4A7A, 0xA8A870, 0xB8A050, ("minecraft:white_ash", 0.015),
         ["sg_boulder", "sg_grass"]),
        ("hall_heights", "Heights of the Hall", 0.7, 0.4, True, 0x9CC0E8, 0xD8DCE6, 0x5AA0D0, 0x2A5A8A, 0xD0BC58, 0xE0B83A, ("minecraft:end_rod", 0.0015),
         ["sg_boulder", "sg_golden_tree", "sg_flowers", "sg_grass"]),
        ("golden_grove", "Golden Grove", 0.7, 0.5, True, 0x8FB4DC, 0xC8D4E2, 0x5AA0D0, 0x2A5A8A, 0xC0B050, 0xE0B030, ("minecraft:white_ash", 0.006),
         ["sg_boulder", "sg_runestone", "sg_golden_tree_dense", "sg_flowers", "sg_grass"]),
        ("mountains", "Mountains of Sovngarde", 0.1, 0.4, True, 0x9AB8DE, 0xD0D8E4, 0x5AA0D0, 0x2A5A8A, 0x9AA070, 0xA09050, ("minecraft:white_ash", 0.006),
         ["sg_boulder", "sg_cairn", "sg_grass"]),
    ],
    "shivering_isles": [
        ("mania_fields", "Fields of Mania", 0.9, 0.7, True, 0x7CC8FF, 0xBCE6FF, 0x40A8F0, 0x1A5AA0, 0x52E06A, 0x30D6A8, ("minecraft:cherry_leaves", 0.004),
         ["si_giant_mushroom_sparse", "si_crystal_tree_sparse", "si_mania_spire", "si_blooms", "si_mania_grass"]),
        ("mania_mushroom_forest", "Mania's Mushroom Forest", 0.9, 0.8, True, 0x8CB8FF, 0xC8D8FF, 0x40A8F0, 0x1A5AA0, 0x48D890, 0x40E0C0, ("minecraft:spore_blossom_air", 0.01),
         ["si_giant_mushroom", "si_blooms", "si_mania_grass"]),
        ("mania_crystal_grove", "Crystal Grove", 0.8, 0.6, True, 0x9CD0FF, 0xD8EEFF, 0x50B8F8, 0x1A5AA0, 0x70E0B0, 0x60D8E0, ("minecraft:end_rod", 0.002),
         ["si_crystal_tree", "si_giant_mushroom_sparse", "si_blooms", "si_mania_grass"]),
        ("dementia_swamp", "Dementia's Mire", 0.7, 0.9, True, 0x4A5A58, 0x34423A, 0x2E4A34, 0x0E1A10, 0x3D5A32, 0x2F4A2A, ("minecraft:mycelium", 0.02),
         ["si_gnarled_tree_sparse", "si_dementia_bulbs", "si_dementia_grass", "si_lilypads"]),
        ("dementia_forest", "Gnarled Woods of Dementia", 0.7, 0.9, True, 0x485656, 0x2E3A32, 0x2E4A34, 0x0E1A10, 0x37502E, 0x2A4426, ("minecraft:mycelium", 0.012),
         ["si_gnarled_tree", "si_dementia_bulbs", "si_dementia_grass", "si_vines"]),
        ("fringe", "The Fringe", 0.8, 0.6, True, 0xA8B4C8, 0xC8CAD4, 0x6A98C8, 0x2A4A7A, 0x8AA090, 0x7A9888, ("minecraft:white_ash", 0.004),
         ["si_crystal_tree_sparse", "si_blooms", "si_mania_grass"]),
        ("sea", "Sea of Madness", 0.8, 0.6, True, 0x88B8F0, 0xC0D8F0, 0x3A7AD0, 0x1A3A7A, 0x52E06A, 0x30D6A8, None,
         []),
    ],
    "apocrypha": [
        ("ink_sea", "Sea of Ink", 0.5, 0.0, False, 0x0C1A10, 0x0A1A12, 0x0A120C, 0x040806, None, None, ("minecraft:enchant", 0.004),
         ["ap_glyph_vein", "ap_book_stack_sparse", "ap_ink_tentacle_sparse"]),
        ("stacks", "Endless Stacks", 0.5, 0.0, False, 0x0E2014, 0x0C1E14, 0x0A120C, 0x040806, None, None, ("minecraft:enchant", 0.01),
         ["ap_glyph_vein", "ap_book_stack", "ap_glyph_obelisk"]),
        ("tentacle_depths", "Tentacle Depths", 0.5, 0.0, False, 0x0A160C, 0x08140C, 0x081008, 0x030604, None, None, ("minecraft:squid_ink", 0.004),
         ["ap_glyph_vein", "ap_ink_tentacle"]),
        ("floating_library", "Floating Library", 0.5, 0.0, False, 0x102418, 0x0E2216, 0x0A120C, 0x040806, None, None, ("minecraft:enchant", 0.012),
         ["ap_book_stack"]),
    ],
    "soul_cairn": [
        ("wastes", "Soul Wastes", 0.4, 0.0, False, 0x3A2850, 0x4A3A66, 0x5A4A80, 0x2A1A40, 0x6A5A80, 0x5A4A70, ("minecraft:white_ash", 0.012),
         ["sc_dead_tree", "sc_bone_ribs", "sc_soul_gem_spire_sparse", "sc_soul_clusters", "sc_soul_fire"]),
        ("bone_fields", "Bone Fields", 0.4, 0.0, False, 0x3E2A50, 0x504066, 0x5A4A80, 0x2A1A40, 0x6A5A80, 0x5A4A70, ("minecraft:white_ash", 0.012),
         ["sc_bone_pillar_dense", "sc_bone_ribs", "sc_dead_tree", "sc_soul_fire"]),
        ("soul_gem_fields", "Soul Gem Fields", 0.4, 0.0, False, 0x402C5C, 0x54447A, 0x5A4A80, 0x2A1A40, 0x6A5A80, 0x5A4A70, ("minecraft:soul", 0.003),
         ["sc_soul_gem_spire", "sc_soul_clusters", "sc_dead_tree"]),
        ("soul_mesas", "Soul Mesas", 0.4, 0.0, False, 0x38264E, 0x483862, 0x5A4A80, 0x2A1A40, 0x6A5A80, 0x5A4A70, ("minecraft:white_ash", 0.008),
         ["sc_bone_pillar", "sc_soul_gem_spire_sparse", "sc_soul_clusters"]),
        ("boneyard", "The Boneyard", 0.4, 0.0, False, 0x342248, 0x44345C, 0x5A4A80, 0x2A1A40, 0x6A5A80, 0x5A4A70, ("minecraft:soul", 0.004),
         ["sc_bone_ribs_dense", "sc_bone_pillar", "sc_soul_fire"]),
    ],
    "coldharbour": [
        ("chained_wastes", "Chained Wastes", 1.0, 0.0, False, 0x161C2A, 0x1E2434, 0x2A3450, 0x10141E, 0x4A5060, 0x3A4050, ("minecraft:ash", 0.02),
         ["ch_lavafall", "ch_chain_pylon_dense", "ch_broken_pillar", "ch_iron_spikes", "ch_soul_fire"]),
        ("burning_ruins", "Burning Ruins", 1.2, 0.0, False, 0x2A1A1E, 0x382226, 0x2A3450, 0x10141E, 0x5A4040, 0x4A3030, ("minecraft:smoke", 0.01),
         ["ch_lavafall", "ch_chain_pylon", "ch_broken_pillar", "ch_soul_fire_pit", "ch_fire", "ch_soul_fire"]),
        ("hollow_city", "The Hollow City", 1.0, 0.0, False, 0x1A1E2C, 0x262A38, 0x2A3450, 0x10141E, 0x4A5060, 0x3A4050, ("minecraft:ash", 0.03),
         ["ch_fire", "ch_soul_fire"]),
        ("lava_rift", "Rift of Fire", 1.4, 0.0, False, 0x3A1612, 0x4A1A12, 0x2A3450, 0x10141E, None, None, ("minecraft:lava", 0.0),
         ["ch_lavafall", "ch_iron_spikes"]),
        ("black_crags", "Black Crags", 1.0, 0.0, False, 0x121620, 0x1A1E2A, 0x2A3450, 0x10141E, None, None, ("minecraft:ash", 0.015),
         ["ch_lavafall", "ch_chain_pylon", "ch_iron_spikes", "ch_soul_fire_pit"]),
    ],
}


def biome_json(entry):
    role, _name, temp, down, precip, sky, fog, water, wfog, grass, foliage, particle, features = entry
    eff = {"sky_color": sky, "fog_color": fog, "water_color": water, "water_fog_color": wfog,
           "mood_sound": {"sound": "minecraft:ambient.cave", "tick_delay": 6000, "block_search_extent": 8, "offset": 2.0}}
    if grass is not None:
        eff["grass_color"] = grass
    if foliage is not None:
        eff["foliage_color"] = foliage
    if particle and particle[1] > 0:
        eff["particle"] = {"options": {"type": particle[0]}, "probability": particle[1]}
    return {"has_precipitation": precip, "temperature": temp, "downfall": down, "effects": eff,
            "spawners": {}, "spawn_costs": {}, "carvers": {}, "features": lists(features)}


def all_biomes():
    """(realm, role, biome id path, display name, json)."""
    out = []
    for realm, entries in B.items():
        for e in entries:
            out.append((realm, e[0], realm + "_" + e[0], e[1], biome_json(e)))
    return out
