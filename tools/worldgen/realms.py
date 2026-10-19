#!/usr/bin/env python3
"""The realms of Oblivion and the afterlife: every data file of the realms workstream, from one declarative table.

Writes (all generated, never hand-edit the outputs):
  * data/minecraft/dimension/{the_nether,the_end}.json and data/minecraft/dimension_type/{the_nether,the_end}.json
    (the Deadlands replace the Nether's worldgen, Sovngarde the End's; vanilla portals and the dragon fight keep working)
  * data/nirncraft/dimension/<realm>.json and data/nirncraft/dimension_type/<realm>.json (the four new realms)
  * data/nirncraft/worldgen/noise/realm/*.json, density_function/realm/<realm>/*.json, noise_settings/realm/<realm>.json
  * data/nirncraft/worldgen/biome/<realm>_<role>.json (each realm's 4-7 biomes; feature lists filtered from ONE
    canonical ordered list per generation step, so no feature-order cycle can happen)
  * data/nirncraft/worldgen/{configured,placed}_feature/realm/*.json
  * data/nirncraft/worldgen/structure/realm/**.json and structure_set/realm/**.json
  * data/nirncraft/tags/worldgen/biome/realm/<realm>.json, block tags (infiniburn, mineable, tool tiers)
  * data/nirncraft/region_spawns/realms.json (the region spawner's tables for the realms)
  * data/nirncraft/loot_tables/chests/realms/*.json
  * nirncraft/tamriel/places/realms.json (+ index.json entry): the ways into the realms that stand in Nirn
  * assets/nirncraft/lore/books/<id>.json: the realms' lore books (original text)
  * mod/src/main/lang/nirncraft/realms.json

Terrain model (see RealmTerrain.java): nirncraft:realm_terrain gives each realm's 2D surface height and three masks;
the density is (height - y) / 8 plus a 3D crag noise scaled by the "jag" mask, and for the Deadlands and Apocrypha a
band of floating rock / page platforms high above.

Run from anywhere:  py -3 tools/worldgen/realms.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
DATA = os.path.join(RES, "data")
SK = "nirncraft"


def write(path, obj):
    from format_263 import prepare
    path, obj = prepare(path, obj)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def data(rel, obj):
    write(os.path.join(DATA, rel), obj)


def st(name, **props):
    s = {"Name": name if ":" in name else "minecraft:" + name}
    if props:
        s["Properties"] = {k: str(v).lower() if isinstance(v, bool) else str(v) for k, v in props.items()}
    return s


# ============================================================================================ density functions
def add(a, b):
    return {"type": "minecraft:add", "argument1": a, "argument2": b}


def mul(a, b):
    return {"type": "minecraft:mul", "argument1": a, "argument2": b}


def dmin(a, b):
    return {"type": "minecraft:min", "argument1": a, "argument2": b}


def dmax(a, b):
    return {"type": "minecraft:max", "argument1": a, "argument2": b}


def ygrad(y0, y1, v0, v1):
    return {"type": "minecraft:y_clamped_gradient", "from_y": y0, "to_y": y1, "from_value": v0, "to_value": v1}


def noise(name, xz=1.0, y=0.0):
    return {"type": "minecraft:noise", "noise": name, "xz_scale": xz, "y_scale": y}


def shifted(n, xz):
    return {"type": "minecraft:shifted_noise", "noise": n, "shift_x": "minecraft:shift_x", "shift_y": 0.0,
            "shift_z": "minecraft:shift_z", "xz_scale": xz, "y_scale": 0.0}


NOISES = {
    "realm/large": {"firstOctave": -9, "amplitudes": [1.0, 0.55, 0.3]},
    "realm/mid": {"firstOctave": -7, "amplitudes": [1.0, 0.5, 0.25]},
    "realm/detail": {"firstOctave": -5, "amplitudes": [1.0, 0.5]},
    "realm/jag": {"firstOctave": -5, "amplitudes": [1.0, 0.6, 0.3]},
    "realm/float": {"firstOctave": -5, "amplitudes": [1.0, 0.5, 0.2]},
}


def terrain(realm, channel):
    return {"type": "minecraft:cache_2d", "argument": {
        "type": "nirncraft:realm_terrain", "realm": realm, "channel": channel,
        "large": "nirncraft:realm/large", "mid": "nirncraft:realm/mid", "detail": "nirncraft:realm/detail"}}


# ============================================================================================ the realms
# per realm: dimension, dimension type, min_y/height, sea level, fluid, default block, crag amplitude, floating band
REALMS = {
    "deadlands": dict(dim="minecraft:the_nether", dtype="minecraft:the_nether", effects="nirncraft:deadlands",
                      min_y=0, height=256, sea=40, fluid="lava", stone="nirncraft:deadlands_rock", jag=1.3,
                      floating=dict(y0=112, y1=176, scale=1.0, yscale=1.6, threshold=0.62),
                      fixed_time=13200, ambient=0.12, ultrawarm=True, scale=8.0, skylight=True,
                      infiniburn="#nirncraft:infiniburn_realms", spawn_light=11),
    "sovngarde": dict(dim="minecraft:the_end", dtype="minecraft:the_end", effects="nirncraft:sovngarde",
                      min_y=0, height=256, sea=0, fluid="water", stone="minecraft:stone", jag=0.9, floating=None,
                      fixed_time=7000, ambient=0.08, ultrawarm=False, scale=1.0, skylight=True,
                      infiniburn="#nirncraft:infiniburn_realms", spawn_light=0),
    "shivering_isles": dict(dim="nirncraft:shivering_isles", dtype="nirncraft:shivering_isles", effects="nirncraft:shivering_isles",
                            min_y=0, height=256, sea=63, fluid="water", stone="minecraft:stone", jag=0.8, floating=None,
                            fixed_time=None, ambient=0.05, ultrawarm=False, scale=1.0, skylight=True,
                            infiniburn="#minecraft:infiniburn_overworld", spawn_light=7),
    "apocrypha": dict(dim="nirncraft:apocrypha", dtype="nirncraft:apocrypha", effects="nirncraft:apocrypha",
                      min_y=0, height=256, sea=52, fluid="water", stone="nirncraft:apocrypha_stone", jag=0.0,
                      floating=dict(y0=86, y1=150, scale=0.9, yscale=4.0, threshold=0.58),
                      fixed_time=18000, ambient=0.18, ultrawarm=False, scale=1.0, skylight=True,
                      infiniburn="#nirncraft:infiniburn_realms", spawn_light=15),
    "soul_cairn": dict(dim="nirncraft:soul_cairn", dtype="nirncraft:soul_cairn", effects="nirncraft:soul_cairn",
                       min_y=0, height=256, sea=0, fluid="water", stone="nirncraft:soul_stone", jag=0.7, floating=None,
                       fixed_time=15500, ambient=0.15, ultrawarm=False, scale=1.0, skylight=True,
                       infiniburn="#nirncraft:infiniburn_realms", spawn_light=15),
    "coldharbour": dict(dim="nirncraft:coldharbour", dtype="nirncraft:coldharbour", effects="nirncraft:coldharbour",
                        min_y=0, height=256, sea=56, fluid="lava", stone="nirncraft:coldharbour_stone", jag=1.0, floating=None,
                        fixed_time=17000, ambient=0.1, ultrawarm=True, scale=1.0, skylight=True,
                        infiniburn="#nirncraft:infiniburn_realms", spawn_light=15),
}


def fluid_state(f):
    return {"Name": "minecraft:" + f, "Properties": {"level": "0"}}


def noise_settings(rid, r):
    base = "nirncraft:realm/" + rid + "/"
    top = r["min_y"] + r["height"]
    below = mul(0.125, add(base + "height", mul(-1.0, "minecraft:y")))
    density = below
    if r["jag"] > 0:
        density = add(density, mul(base + "jag", mul(r["jag"], noise("nirncraft:realm/jag", 1.0, 1.4))))
    fl = r["floating"]
    if fl:
        mid = (fl["y0"] + fl["y1"]) / 2.0
        tent = dmin(ygrad(fl["y0"], mid, 0.0, 1.0), ygrad(mid, fl["y1"], 1.0, 0.0))
        floating = add(mul(tent, noise("nirncraft:realm/float", fl["scale"], fl["yscale"])), -fl["threshold"])
        density = dmax(density, mul(4.0, floating))
    # solid floor at the bottom, open sky at the top
    density = dmin(dmax(density, ygrad(r["min_y"], r["min_y"] + 4, 1.0, -1.0)), ygrad(top - 24, top - 4, 1.0, -1.0))
    final = {"type": "minecraft:squeeze", "argument": mul(0.64, {"type": "minecraft:interpolated", "argument": density})}
    zero = 0.0
    router = {
        "barrier": zero, "fluid_level_floodedness": zero, "fluid_level_spread": zero, "lava": zero,
        "temperature": shifted("minecraft:temperature", 0.5),
        "vegetation": shifted("minecraft:vegetation", 0.5),
        "continents": base + "shape",
        "erosion": base + "feature",
        "depth": mul(1.0 / 128.0, add(base + "height", mul(-1.0, "minecraft:y"))),
        "ridges": base + "jag",
        "initial_density_without_jaggedness": add(0.25, below),
        "final_density": final,
        "vein_toggle": zero, "vein_ridged": zero, "vein_gap": zero,
    }
    return {
        "sea_level": r["sea"], "disable_mob_generation": True, "aquifers_enabled": False, "ore_veins_enabled": False,
        "legacy_random_source": False,
        "default_block": {"Name": r["stone"]},
        "default_fluid": fluid_state(r["fluid"]),
        "noise": {"min_y": r["min_y"], "height": r["height"], "size_horizontal": 1, "size_vertical": 2},
        "noise_router": router,
        "spawn_target": [],
        "surface_rule": surface_rule(rid),
    }


def dimension_type(r):
    t = {"ambient_light": r["ambient"], "bed_works": False, "coordinate_scale": r["scale"], "effects": r["effects"],
         "has_ceiling": False, "has_raids": False, "has_skylight": r["skylight"], "height": r["height"],
         "infiniburn": r["infiniburn"], "logical_height": r["height"], "min_y": r["min_y"],
         "monster_spawn_block_light_limit": 0,
         "monster_spawn_light_level": {"type": "minecraft:uniform", "value": {"min_inclusive": 0, "max_inclusive": r["spawn_light"]}},
         "natural": False, "piglin_safe": False, "respawn_anchor_works": False, "ultrawarm": r["ultrawarm"]}
    if r["fixed_time"] is not None:
        t["fixed_time"] = r["fixed_time"]
    return t


# ============================================================================================ surface rules
def block(name, **props):
    return {"type": "minecraft:block", "result_state": st(name, **props)}


def cond(c, then):
    return {"type": "minecraft:condition", "if_true": c, "then_run": then}


def seq(*rules):
    return {"type": "minecraft:sequence", "sequence": list(rules)}


def biome_is(*ids):
    return {"type": "minecraft:biome", "biome_is": [SK + ":" + i for i in ids]}


def y_above(y, mult=0):
    return {"type": "minecraft:y_above", "anchor": {"absolute": y}, "surface_depth_multiplier": mult, "add_stone_depth": False}


def NOT(c):
    return {"type": "minecraft:not", "invert": c}


def floor_depth(add_surface=False, offset=0):
    return {"type": "minecraft:stone_depth", "offset": offset, "surface_type": "floor", "add_surface_depth": add_surface,
            "secondary_depth_range": 0}


def patch(lo, hi, n="minecraft:surface"):
    return {"type": "minecraft:noise_threshold", "noise": n, "min_threshold": lo, "max_threshold": hi}


STEEP = {"type": "minecraft:steep"}
BEDROCK = cond({"type": "minecraft:vertical_gradient", "random_name": "nirncraft:realm_bedrock",
                "true_at_and_below": {"above_bottom": 0}, "false_at_and_above": {"above_bottom": 4}}, block("bedrock"))


def surface(top, sub, steep=None, extra_top=()):
    """Top block (with optional noise patches before it), a few blocks of subsoil, rock on steep faces."""
    tops = list(extra_top) + [top]
    rules = []
    if steep:
        rules.append(cond(STEEP, steep))
    rules.append(cond(floor_depth(False), seq(*tops) if len(tops) > 1 else tops[0]))
    rules.append(cond(floor_depth(True), sub))
    return seq(*rules)


def surface_rule(rid):
    S = SK + ":"
    if rid == "deadlands":
        rock, ash, magma = block(S + "deadlands_rock"), block(S + "deadlands_ash"), block(S + "deadlands_magma_rock")
        rules = [
            cond(biome_is("deadlands_lava_sea"), surface(rock, rock, extra_top=[cond(patch(0.25, 2), magma)])),
            cond(biome_is("deadlands_ashen_wastes"), surface(ash, ash, steep=rock, extra_top=[cond(patch(0.35, 2), rock)])),
            cond(biome_is("deadlands_blood_fields"), surface(rock, rock, steep=rock,
                                                             extra_top=[cond(patch(0.42, 2), magma), cond(patch(-2, -0.4), ash)])),
            cond(biome_is("deadlands_spires"), surface(rock, rock, extra_top=[cond(patch(0.5, 2), magma),
                                                                              cond(patch(-0.2, 0.1), block("blackstone"))])),
            cond(biome_is("deadlands_harrada_thicket"), surface(ash, rock, steep=rock, extra_top=[cond(patch(0.2, 2), rock)])),
        ]
    elif rid == "sovngarde":
        grass, dirt, stone = block("grass_block", snowy=False), block("dirt"), block("stone")
        rules = [
            cond(y_above(172, 2), cond(floor_depth(False), block("snow_block"))),
            cond(biome_is("sovngarde_chasm"), surface(block("gravel"), stone, steep=stone,
                                                      extra_top=[cond(patch(0.1, 2), block("calcite")), cond(NOT(y_above(70)), block("tuff"))])),
            cond(biome_is("sovngarde_mountains"), surface(grass, dirt, steep=stone, extra_top=[cond(y_above(140, 3), stone),
                                                                                              cond(patch(0.3, 2), block("coarse_dirt"))])),
            cond(biome_is("sovngarde_mistvale"), surface(grass, dirt, steep=stone, extra_top=[cond(patch(0.45, 2), block("podzol", snowy=False))])),
            cond(biome_is("sovngarde_hall_heights"), surface(grass, dirt, steep=stone, extra_top=[cond(patch(0.5, 2), block("coarse_dirt"))])),
            cond(biome_is("sovngarde_golden_grove"), surface(grass, dirt, steep=stone, extra_top=[cond(patch(0.3, 2), block("podzol", snowy=False))])),
            surface(grass, dirt, steep=stone),
        ]
    elif rid == "shivering_isles":
        grass, dirt, stone, sand = block("grass_block", snowy=False), block("dirt"), block("stone"), block("sand")
        shore = cond(NOT(y_above(64)), sand)
        rules = [
            cond(biome_is("shivering_isles_sea"), surface(sand, sand, extra_top=[cond(patch(0.2, 2), block("gravel"))])),
            cond(biome_is("shivering_isles_fringe"), surface(grass, dirt, steep=block("calcite"),
                                                             extra_top=[shore, cond(patch(0.3, 2), block("calcite")), cond(patch(-2, -0.45), block("gravel"))])),
            cond(biome_is("shivering_isles_mania_fields"), surface(grass, dirt, steep=block("calcite"),
                                                                   extra_top=[shore, cond(patch(0.55, 2), block("moss_block"))])),
            cond(biome_is("shivering_isles_mania_mushroom_forest"), surface(grass, dirt, steep=stone,
                                                                            extra_top=[shore, cond(patch(0.2, 2), block("mycelium", snowy=False))])),
            cond(biome_is("shivering_isles_mania_crystal_grove"), surface(grass, dirt, steep=block("calcite"),
                                                                          extra_top=[shore, cond(patch(0.3, 2), block("calcite")), cond(patch(-2, -0.5), block("amethyst_block"))])),
            cond(biome_is("shivering_isles_dementia_swamp"), surface(grass, block("mud"), steep=block("tuff"),
                                                                     extra_top=[cond(NOT(y_above(63)), block("mud")), cond(patch(0.1, 2), block("mud")), cond(patch(-2, -0.4), block("coarse_dirt"))])),
            cond(biome_is("shivering_isles_dementia_forest"), surface(grass, dirt, steep=block("tuff"),
                                                                      extra_top=[shore, cond(patch(0.25, 2), block("podzol", snowy=False)), cond(patch(-2, -0.35), block("rooted_dirt"))])),
            surface(grass, dirt, steep=stone),
        ]
    elif rid == "apocrypha":
        page, black, stone = block(S + "page_block"), block(S + "black_page_block"), block(S + "apocrypha_stone")
        rules = [
            cond(biome_is("apocrypha_stacks", "apocrypha_floating_library"),
                 surface(page, black, extra_top=[cond(patch(0.3, 2), black), cond(patch(-2, -0.55), block(S + "apocrypha_glyph_stone"))])),
            cond(biome_is("apocrypha_tentacle_depths"), surface(stone, stone, extra_top=[cond(patch(0.4, 2), block(S + "apocrypha_tentacle"))])),
            surface(stone, stone, extra_top=[cond(patch(0.35, 2), black), cond(patch(-2, -0.5), block("mud"))]),
        ]
    elif rid == "soul_cairn":
        ash, stone = block(S + "soul_ash"), block(S + "soul_stone")
        rules = [
            cond(biome_is("soul_cairn_soul_mesas"), surface(ash, stone, steep=stone, extra_top=[cond(patch(0.4, 2), block("soul_soil"))])),
            cond(biome_is("soul_cairn_bone_fields"), surface(ash, ash, steep=stone, extra_top=[cond(patch(0.55, 2), block("bone_block", axis="y")),
                                                                                              cond(patch(-2, -0.4), block("soul_soil"))])),
            cond(biome_is("soul_cairn_soul_gem_fields"), surface(ash, ash, steep=stone, extra_top=[cond(patch(0.3, 2), stone)])),
            cond(biome_is("soul_cairn_boneyard"), surface(ash, ash, steep=stone, extra_top=[cond(patch(0.35, 2), block("gravel")),
                                                                                           cond(patch(-2, -0.5), block("bone_block", axis="y"))])),
            surface(ash, ash, steep=stone, extra_top=[cond(patch(0.45, 2), block("soul_soil")), cond(patch(-2, -0.55), stone)]),
        ]
    else:  # coldharbour
        stone, black = block(S + "coldharbour_stone"), block("blackstone")
        rules = [
            cond(biome_is("coldharbour_lava_rift"), surface(block("basalt", axis="y"), black, extra_top=[cond(patch(0.3, 2), block("magma_block"))])),
            cond(biome_is("coldharbour_black_crags"), surface(black, black, steep=block("basalt", axis="y"), extra_top=[cond(patch(0.35, 2), stone)])),
            cond(biome_is("coldharbour_burning_ruins"), surface(black, stone, steep=stone, extra_top=[cond(patch(0.4, 2), block("soul_soil")),
                                                                                                    cond(patch(-2, -0.45), block("magma_block"))])),
            cond(biome_is("coldharbour_hollow_city"), surface(block("gravel"), stone, steep=stone, extra_top=[cond(patch(0.2, 2), block("coarse_dirt")),
                                                                                                             cond(patch(-2, -0.3), stone)])),
            surface(stone, stone, steep=stone, extra_top=[cond(patch(0.4, 2), block("gravel")), cond(patch(-2, -0.5), black)]),
        ]
    return seq(BEDROCK, *rules)


# ============================================================================================ blocks, names, messages
BLOCK_NAMES = {
    "deadlands_rock": "Deadlands Rock", "deadlands_magma_rock": "Burning Deadlands Rock", "deadlands_ash": "Deadlands Ash",
    "bloodgrass": "Bloodgrass", "harrada": "Harrada", "daedric_bricks": "Daedric Bricks",
    "daedric_glyph_bricks": "Daedric Glyph Bricks", "daedric_pillar": "Daedric Pillar", "daedric_spike": "Daedric Spike",
    "sigil_stone": "Sigil Stone", "sigil_flame": "Sigil Updraft", "oblivion_portal": "Oblivion Gate",
    "valor_portal": "Way to Nirn", "madness_door": "Door to the Shivering Isles", "mania_stem": "Mania Mushroom Stem",
    "mania_cap_violet": "Violet Mania Cap", "mania_cap_teal": "Teal Mania Cap", "mania_cap_amber": "Amber Mania Cap",
    "mania_bloom": "Mania Bloom", "dementia_bulb": "Dementia Bulb", "page_block": "Stack of Pages",
    "black_page_block": "Stack of Black Pages", "apocrypha_stone": "Apocrypha Stone", "apocrypha_glyph_stone": "Apocrypha Glyph Stone",
    "apocrypha_tentacle": "Tentacle of Hermaeus Mora", "black_book": "Black Book", "soul_ash": "Soul Ash",
    "soul_stone": "Soul Stone", "soul_gem_block": "Soul Gem Crystal", "soul_gem_cluster": "Soul Gem Cluster",
    "soul_portal": "Soul Cairn Portal", "coldharbour_stone": "Coldharbour Stone", "coldharbour_bricks": "Coldharbour Bricks",
    "coldharbour_portal": "Dark Anchor Portal",
}

REALM_NAMES = {
    "deadlands": ("The Deadlands", "Mehrunes Dagon's realm of fire"),
    "sovngarde": ("Sovngarde", "The Hall of Valor awaits the worthy"),
    "shivering_isles": ("The Shivering Isles", "Sheogorath's realm of madness"),
    "apocrypha": ("Apocrypha", "Hermaeus Mora's endless library"),
    "soul_cairn": ("The Soul Cairn", "Where the Ideal Masters keep the souls they take"),
    "coldharbour": ("Coldharbour", "Molag Bal's dark mirror of Nirn"),
}

MESSAGES = {
    "realm.nirncraft.entering": "Entering %s",
    "realm.nirncraft.missing": "%s cannot be reached from this world.",
    "realm.nirncraft.gate.closed": "The gate is dead. Its fire has gone out.",
    "realm.nirncraft.sigil.hint": "Hold the item to enchant in your other hand, then use the Sigil Stone.",
    "realm.nirncraft.sigil.cannot": "The Sigil Stone's power will not bind to %s.",
    "realm.nirncraft.sigil.bound": "%s bound to %s.",
    "realm.nirncraft.sigil.tooltip": "Use with an item in your off hand to bind its enchantment.",
    "realm.nirncraft.sigil.taken": "Sigil Stone Taken",
    "realm.nirncraft.sigil.taken.sub": "The gate collapses. The Deadlands cast you out.",
    "realm.nirncraft.sigil.taken.sub_nogate": "The tower shakes. The Deadlands cast you out.",
    "realm.nirncraft.soul_cairn.toll": "The portal hungers for a soul. Carry a filled soul gem to pass the first time.",
    "realm.nirncraft.soul_cairn.toll_paid": "The Ideal Masters take their due: a soul gem's light goes out.",
    "realm.nirncraft.book.wait": "The pages won't settle yet.",
    "realm.nirncraft.tsun.greet.0": "Tsun: I am Tsun, shield-thane to Ysgramor. None pass the Whalebone Bridge unless I judge them worthy.",
    "realm.nirncraft.tsun.greet.1": "Tsun: Your sword-arm is still warm. You are not yet one of us, traveller.",
    "realm.nirncraft.tsun.greet.2": "Tsun: The heroes in the Hall drink to your deeds. When you would go back to Nirn, ask it of me (sneak and speak).",
    "realm.nirncraft.tsun.return": "Tsun: It is not yet your time. Go back to the world of the living, and earn your seat here.",
    "realm.nirncraft.tsun.victory": "Tsun: Alduin is gone, and Sovngarde sings your name. Return to Nirn, hero, until the day you return for good.",
    "item.nirncraft.sigil_stone": "Sigil Stone",
    "item.nirncraft.black_book": "Black Book",
    "item.nirncraft.black_book.titled": "Black Book: %s",
    "item.nirncraft.black_book.tooltip": "Reading it opens a way into Apocrypha.",
    "book.nirncraft.black_book.waking_dreams": "Waking Dreams",
    "book.nirncraft.black_book.epistolary_acumen": "Epistolary Acumen",
    "book.nirncraft.black_book.the_sallow_regent": "The Sallow Regent",
    "book.nirncraft.black_book.filament_and_filigree": "Filament and Filigree",
    "book.nirncraft.black_book.untold_legends": "Untold Legends",
    "book.nirncraft.black_book.the_winds_of_change": "The Winds of Change",
    "place.nirncraft.kvatch_gate": "The Burning Gate of Kvatch",
    "place.nirncraft.shivering_isles_door": "The Door to the Shivering Isles",
    "place.nirncraft.black_book_epistolary": "Altar of Epistolary Acumen",
    "place.nirncraft.black_book_sallow_regent": "Altar of the Sallow Regent",
    "place.nirncraft.black_book_filament": "Altar of Filament and Filigree",
    "place.nirncraft.soul_cairn_portal": "Soul Cairn Portal",
    "place.nirncraft.dark_anchor": "The Dark Anchor",
}


def lang(biomes, books):
    out = {}
    for rid, (name, sub) in REALM_NAMES.items():
        out["realm.nirncraft." + rid] = name
        out["realm.nirncraft." + rid + ".sub"] = sub
        dim = REALMS[rid]["dim"].split(":")[1]
        out["region.nirncraft.dimension." + dim] = name
    for (_realm, _role, path, name, _j) in biomes:
        out["biome.nirncraft." + path] = name
    for bid, name in BLOCK_NAMES.items():
        out["block.nirncraft." + bid] = name
    for bid, b in books.items():
        out["book.nirncraft." + bid] = b[4]
    out.update(MESSAGES)
    return dict(sorted(out.items()))


# ============================================================================================ tags
def merge_tag(kind, tag, values):
    ns, path = tag.split(":")
    p = os.path.join(DATA, ns, "tags", kind, *path.split("/")) + ".json"
    existing = {"replace": False, "values": []}
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            existing = json.load(f)
    vals = existing.get("values", [])
    for v in values:
        if v not in vals:
            vals.append(v)
    write(p, {"replace": existing.get("replace", False), "values": vals})


def write_tags(biomes):
    by_realm = {}
    for (realm, _role, path, _n, _j) in biomes:
        by_realm.setdefault(realm, []).append(SK + ":" + path)
    for realm, ids in by_realm.items():
        data("nirncraft/tags/worldgen/biome/realm/%s.json" % realm, {"replace": False, "values": ids})
    s = lambda *n: [SK + ":" + x for x in n]
    data("nirncraft/tags/blocks/infiniburn_realms.json", {"replace": False, "values": [
        "#minecraft:infiniburn_overworld", "minecraft:blackstone", "minecraft:basalt"] + s(
        "deadlands_rock", "deadlands_magma_rock", "deadlands_ash", "coldharbour_stone", "soul_stone")})
    merge_tag("blocks", "minecraft:infiniburn_overworld", s("deadlands_magma_rock"))
    merge_tag("blocks", "minecraft:mineable/pickaxe", s(
        "deadlands_rock", "deadlands_magma_rock", "daedric_bricks", "daedric_glyph_bricks", "daedric_pillar", "daedric_spike",
        "apocrypha_stone", "apocrypha_glyph_stone", "soul_stone", "soul_gem_block", "soul_gem_cluster", "coldharbour_stone",
        "coldharbour_bricks"))
    merge_tag("blocks", "minecraft:mineable/shovel", s("deadlands_ash", "soul_ash"))
    merge_tag("blocks", "minecraft:mineable/axe", s("mania_stem", "mania_cap_violet", "mania_cap_teal", "mania_cap_amber",
                                                    "page_block", "black_page_block"))
    merge_tag("blocks", "minecraft:mineable/hoe", s("apocrypha_tentacle"))
    merge_tag("blocks", "minecraft:needs_iron_tool", s("daedric_bricks", "daedric_glyph_bricks", "daedric_pillar", "coldharbour_bricks"))
    merge_tag("blocks", "minecraft:replaceable_by_trees", s("bloodgrass", "harrada", "mania_bloom", "dementia_bulb"))
    merge_tag("blocks", "minecraft:sword_efficient", s("bloodgrass", "harrada", "mania_bloom", "dementia_bulb"))
    merge_tag("blocks", "minecraft:dragon_immune", s("sigil_stone", "oblivion_portal", "valor_portal", "madness_door",
                                                     "black_book", "soul_portal", "coldharbour_portal", "sigil_flame"))
    merge_tag("blocks", "minecraft:wither_immune", s("sigil_stone", "oblivion_portal", "valor_portal", "madness_door",
                                                     "black_book", "soul_portal", "coldharbour_portal", "sigil_flame"))
    merge_tag("blocks", "minecraft:features_cannot_replace", s("sigil_stone", "oblivion_portal", "black_book", "madness_door"))
    # the realms' creatures in the shared creature tags (monster hunting perks, undead weaknesses, bosses unleveled)
    merge_tag("entity_types", "nirncraft:monsters", s(
        "clannfear", "daedroth", "spider_daedra", "grummite", "baliwog", "gnarl", "elytra", "flesh_atronach", "seeker",
        "lurker", "keeper", "boneman", "mistman", "wrathman", "harvester"))
    merge_tag("entity_types", "nirncraft:undead", s("keeper", "boneman", "mistman", "wrathman"))
    merge_tag("entity_types", "forge:bosses", s("miraak"))
    data("nirncraft/tags/worldgen/structure/realms.json", {"replace": False,
                                                          "values": [SK + ":realm/" + k for k in STRUCTURES_KEYS()]})


def STRUCTURES_KEYS():
    from realms_content import STRUCTURES
    return list(STRUCTURES.keys())


# ============================================================================================ main
def main():
    import realms_biomes
    import realms_content
    import realms_features

    for name, params in NOISES.items():
        data("nirncraft/worldgen/noise/%s.json" % name, params)
    biomes = realms_biomes.all_biomes()
    roles = {}
    for (realm, role, path, _n, j) in biomes:
        data("nirncraft/worldgen/biome/%s.json" % path, j)
        roles.setdefault(realm, {})[role] = SK + ":" + path
    for rid, r in REALMS.items():
        for ch in ("height", "jag", "shape", "feature"):
            data("nirncraft/worldgen/density_function/realm/%s/%s.json" % (rid, ch), terrain(rid, ch))
        data("nirncraft/worldgen/noise_settings/realm/%s.json" % rid, noise_settings(rid, r))
        ns, path = r["dtype"].split(":")
        data("%s/dimension_type/%s.json" % (ns, path), dimension_type(r))
        dns, dpath = r["dim"].split(":")
        data("%s/dimension/%s.json" % (dns, dpath), {"type": r["dtype"], "generator": {
            "type": "minecraft:noise", "settings": SK + ":realm/" + rid,
            "biome_source": {"type": SK + ":realm", "realm": rid, "biomes": roles[rid]}}})
    for name, cf in realms_features.CF.items():
        data("nirncraft/worldgen/configured_feature/realm/%s.json" % name, cf)
    for name, pf in realms_features.PF.items():
        data("nirncraft/worldgen/placed_feature/realm/%s.json" % name, pf)
    for site, (biome_tag, placement) in realms_content.STRUCTURES.items():
        data("nirncraft/worldgen/structure/realm/%s.json" % site, realms_content.structure_json(site, biome_tag))
        data("nirncraft/worldgen/structure_set/realm/%s.json" % site, realms_content.structure_set_json(site, placement))
    data("nirncraft/region_spawns/realms.json", realms_content.SPAWNS)
    for name, pools in realms_content.LOOT.items():
        data("nirncraft/loot_tables/chests/realms/%s.json" % name, {"type": "minecraft:chest", "pools": pools,
                                                                   "random_sequence": "nirncraft:chests/realms/" + name})
    # the ways in, as named places of Tamriel
    places_dir = os.path.join(RES, "nirncraft", "tamriel", "places")
    write(os.path.join(places_dir, "realms.json"), realms_content.PLACES)
    idx_path = os.path.join(places_dir, "index.json")
    with open(idx_path, encoding="utf-8") as f:
        idx = json.load(f)
    if "realms.json" not in idx:
        idx.append("realms.json")
        write(idx_path, idx)
    # lore books
    for bid, b in realms_content.BOOKS.items():
        write(os.path.join(RES, "assets", SK, "lore", "books", bid + ".json"), {"author": b[5], "pages": b[6]})
    write_tags(biomes)
    write(os.path.join(ROOT, "mod", "src", "main", "lang", SK, "realms.json"), lang(biomes, realms_content.BOOKS))
    # the Java side registers the books and names the sites from these tables
    write(os.path.join(RES, "nirncraft", "realms", "books.json"),
          [{"id": k, "category": v[0], "cover": v[1], "weight": v[2], "value": v[3],
            **({"why": realms_content.BOOK_WHY[k]} if k in getattr(realms_content, "BOOK_WHY", {}) else {})}
           for k, v in realms_content.BOOKS.items()])
    write(os.path.join(RES, "nirncraft", "realms", "site_names.json"), realms_content.SITE_NAMES)
    print("realms: %d biomes, %d placed features, %d structures written" % (len(biomes), len(realms_features.PF),
                                                                          len(realms_content.STRUCTURES)))


if __name__ == "__main__":
    main()
