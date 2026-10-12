"""Cavern library generator: writes the shape table, the profile files and the themed loot tables of the grand caverns.

  py -3 -I tools/worldgen/caverns.py            (from the repo root)

Output (all read by mod/.../dungeons/world/cavern/CavernLibrary.java; nothing in Java names a profile):
  mod/src/main/resources/skycraft/dungeons/caverns/index.json
  mod/src/main/resources/skycraft/dungeons/caverns/shapes.json
  mod/src/main/resources/skycraft/dungeons/caverns/profiles/<group>.json
  mod/src/main/resources/data/skycraft/loot_tables/chests/cavern/<kind>.json

A block is "namespace:id[props]@weight" ("rock" = the dungeon's own rock). The profile files can also be edited by hand;
this script only keeps the ~60 hand-written looks in one readable place. Every block id is checked against
tools/worldgen/generated/vanilla_blocks.txt when that file exists (see --check).
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft", "dungeons", "caverns")
LOOT = os.path.join(ROOT, "mod", "src", "main", "resources", "data", "skycraft", "loot_tables", "chests", "cavern")

M = "minecraft:"


def b(*names):
    """Block list from 'name' or 'name@w' (minecraft: prefix implied for bare names)."""
    out = []
    for n in names:
        if not n.startswith("rock") and ":" not in n.split("@")[0].split("[")[0]:
            n = M + n
        base = n.split("@")[0]
        if base.endswith("_leaves") and "[" not in base:
            # leaves placed by worldgen must not decay
            n = base + "[persistent=true]" + n[len(base):]
        out.append(n)
    return out


# ---- flora helpers
def plant(block, d):
    return {"type": "plant", "block": b(*([block] if isinstance(block, str) else block)), "density": d}


def ceil(block, d):
    return {"type": "ceil", "block": b(*([block] if isinstance(block, str) else block)), "density": d}


def hang(block, tip, d, ln=(2, 6)):
    r = {"type": "hang", "block": b(block), "density": d, "len": list(ln)}
    if tip:
        r["tip"] = b(tip)
    return r


def column(block, tip, d, ln=(2, 4)):
    r = {"type": "column", "block": b(*([block] if isinstance(block, str) else block)), "density": d, "len": list(ln)}
    if tip:
        r["tip"] = b(tip)
    return r


def mush(stem, cap, glow, d, r=2):
    return {"type": "mushroom", "block": b(stem), "cap": b(*cap) if isinstance(cap, (list, tuple)) else b(cap),
            "tip": b(glow), "density": d, "radius": r}


def spire(body, core, tip, d, ln=(4, 8)):
    r = {"type": "spire", "block": b(*([body] if isinstance(body, str) else body)), "density": d, "len": list(ln)}
    if core:
        r["cap"] = b(core)
    if tip:
        r["tip"] = b(tip)
    return r


def bed(block, d):
    return {"type": "bed", "block": b(*([block] if isinstance(block, str) else block)), "density": d}


def floatp(block, d):
    return {"type": "float", "block": b(block), "density": d}


def tree(log, leaves, fruit, d, ln=(8, 14), r=3):
    t = {"type": "tree", "block": b(log), "cap": b(leaves), "density": d, "len": list(ln), "radius": r}
    if fruit:
        t["tip"] = b(fruit)
    return t


# ---- feature helpers
def vein(block, scale=6.0, amount=0.05):
    return {"type": "vein", "block": b(*([block] if isinstance(block, str) else block)), "scale": scale, "amount": amount}


def pool(fluid="water", count=(2, 4), size=(2, 3), accent=None, rim=None):
    f = {"type": "pool", "fluid": fluid, "count": list(count), "size": list(size)}
    if accent:
        f["accent"] = b(accent)
    if rim:
        f["block"] = b(*([rim] if isinstance(rim, str) else rim))
    return f


def bubbles(amount=0.03):
    return {"type": "bubbles", "amount": amount}


def bedlight(block, amount=0.05):
    return {"type": "bedlight", "block": b(block), "amount": amount}


def smoke(count=(2, 4), rim="basalt"):
    return {"type": "smoke", "count": list(count), "block": b(rim)}


def ruins(block, count=(4, 8)):
    return {"type": "ruin", "block": b(*([block] if isinstance(block, str) else block)), "count": list(count)}


def statues(block, accent=None, count=(1, 2)):
    f = {"type": "statue", "block": b(*([block] if isinstance(block, str) else block)), "count": list(count)}
    if accent:
        f["accent"] = b(accent)
    return f


def boulders(block, count=(3, 6), size=(2, 3)):
    return {"type": "boulders", "block": b(*([block] if isinstance(block, str) else block)), "count": list(count), "size": list(size)}


def ribs(block, density=6):
    return {"type": "ribs", "block": b(*([block] if isinstance(block, str) else block)), "count": [density, density]}


def lights(roof, density=44, cluster=0, post=None):
    d = {"roof": b(*([roof] if isinstance(roof, str) else roof)), "density": density, "cluster": cluster}
    if post:
        d["post"] = b(post)
    return d


def P(id, name, lore, match=None, shapes=None, floor=("rock",), wall=("rock",), spike=("rock",), platform=("stone_bricks",),
      fluid="none", flora=(), features=(), light=None, setpiece=("camp",), foes=(), elite=None, loot=None, ambient=(),
      pillar=None, terrace=None, bed_=None, ruin=None, patch=None, patch_at=0.2, weight=1.0, particle="", sound="",
      color="", params=None):
    pal = {"floor": b(*floor), "wall": b(*wall), "spike": b(*spike), "platform": b(*platform)}
    if pillar:
        pal["pillar"] = b(*pillar)
    if terrace:
        pal["terrace"] = b(*terrace)
    if bed_:
        pal["bed"] = b(*bed_)
    if ruin:
        pal["ruin"] = b(*ruin)
    if patch:
        pal["patch"] = b(*patch)
        pal["patchAt"] = patch_at
    p = {"id": id, "name": name, "lore": lore, "weight": weight, "match": match or {}, "shapes": shapes or {"bowl_bridges": 1},
         "palette": pal, "fluid": fluid, "flora": list(flora), "features": list(features),
         "lights": light or lights("glowstone"), "setpiece": {"type": setpiece[0]}, "foes": list(foes)}
    if len(setpiece) > 1 and setpiece[1]:
        p["setpiece"]["block"] = b(*([setpiece[1]] if isinstance(setpiece[1], str) else setpiece[1]))
    if len(setpiece) > 2 and setpiece[2]:
        p["setpiece"]["accent"] = b(setpiece[2])
    if elite:
        p["elite"] = elite
    if loot:
        p["loot"] = loot
    if ambient:
        p["ambient"] = [dict(entity=e, count=list(c), where=w) for e, c, w in ambient]
    if particle:
        p["particle"] = particle
    if sound:
        p["sound"] = sound
    if color:
        p["lightColor"] = color
    if params:
        p["shapeParams"] = params
    return p


def fl(kind, id_):
    return {"relic": "skycraft:chests/relic_" + kind, "platform": "skycraft:chests/cavern/" + id_, "minor": "skycraft:chests/cavern/" + id_}


UNDERGROUND = ["barrow", "cave", "dwemer", "vampire_lair", "necromancer_lair", "hagraven_lair", "mine", "fort", "ruin"]
PROFILES = {}


def group(name, *profiles):
    PROFILES[name] = list(profiles)


# ======================================================================================================== generic
# Four of these are the original five looks (fungal, crystal, frozen, ember, flooded); the others are themed fallbacks
# and climate / depth variants, so a region without its own looks still varies by what it is.
group("generic",
      P("generic_fungal_grotto", "Fungal Grotto",
        "Pale caps and glowing spores fatten on a thousand years of drip. Nothing here has seen the sun.",
        match={"themes": ["cave", "hagraven_lair", "barrow"]}, weight=0.8,
        shapes={"bowl_bridges": 3, "karst": 2, "root_cavern": 1, "canyon": 1, "pillar_maze": 1},
        floor=["podzol@14", "mud@10", "rooted_dirt@8", "rock@68"], patch=["moss_block"], patch_at=0.25,
        wall=["moss_block@40", "mud@20", "shroomlight@6", "dripstone_block@34"], spike=["dripstone_block@1", "rock@2"],
        platform=["mossy_stone_bricks@1", "stone_bricks@5"], fluid="water",
        flora=[hang("cave_vines_plant[berries=true]", "cave_vines[berries=true]", 30, (2, 8)),
               mush("mushroom_stem", ["red_mushroom_block", "brown_mushroom_block"], "shroomlight", 14),
               plant(["red_mushroom", "brown_mushroom"], 76),
               plant("glow_lichen[down=true]", 80), plant("moss_carpet", 60)],
        features=[bedlight("sea_lantern", 0.05)], light=lights("shroomlight", 44),
        foes=[], color="orange", particle="spore"),
      P("generic_crystal_cavern", "Crystal Cavern",
        "Amethyst grew in the dark before there was a name for the dark. It still sings when struck.",
        match={"themes": ["cave", "mine", "necromancer_lair"]}, weight=0.8,
        shapes={"bowl_bridges": 3, "amphitheatre": 1, "karst": 2, "shaft": 1, "floating_islands": 1},
        floor=["calcite@10", "amethyst_block@4", "tuff@20", "smooth_basalt@10", "rock@56"],
        wall=["calcite@34", "amethyst_block@26", "budding_amethyst@6", "pearlescent_froglight@6", "tuff@28"],
        spike=["calcite@1", "dripstone_block@3"], platform=["calcite@1", "polished_diorite@5"],
        fluid={"kind": "water", "chance": 0.5},
        flora=[spire("amethyst_block", "pearlescent_froglight", "amethyst_cluster[facing=up]", 16),
               plant("amethyst_cluster[facing=up]", 74), plant("large_amethyst_bud[facing=up]", 40)],
        light=lights(["pearlescent_froglight", "verdant_froglight"], 44, 3), color="violet"),
      P("generic_frozen_cavern", "Frozen Cavern",
        "Meltwater from a thousand winters froze in the shape of the room it filled.",
        match={"temps": ["cold"]}, weight=0.9,
        shapes={"bowl_bridges": 2, "crevasse": 2, "canyon": 2, "amphitheatre": 1, "cathedral": 1},
        floor=["packed_ice@25", "blue_ice@11", "rock@64"], patch=["snow_block"], patch_at=0.15,
        wall=["packed_ice@45", "blue_ice@35", "sea_lantern@8", "snow_block@12"], spike=["packed_ice@2", "blue_ice@1"],
        platform=["blue_ice@1", "polished_diorite@5"], fluid={"kind": "ice"},
        flora=[spire("blue_ice", "sea_lantern", "packed_ice", 14), plant("snow", 96)],
        features=[bedlight("sea_lantern", 0.05)], light=lights("sea_lantern", 44), color="blue",
        foes=["skycraft:ice_wraith"]),
      P("generic_ember_cavern", "Ember Cavern",
        "The stone is still warm. Somewhere below, the world has not finished cooling.",
        match={"themes": ["mine", "vampire_lair", "dwemer", "cave"], "temps": ["mild", "hot", "volcanic"]}, weight=0.7,
        shapes={"bowl_bridges": 3, "lava_river": 2, "amphitheatre": 1, "lava_tube": 1, "pillar_maze": 1},
        floor=["basalt@26", "blackstone@18", "smooth_basalt@16", "tuff@40"],
        wall=["basalt@40", "blackstone@30", "magma_block@14", "glowstone@6", "smooth_basalt@10"],
        spike=["basalt@1", "blackstone@2"], platform=["gilded_blackstone@1", "polished_blackstone_bricks@5"],
        fluid="lava", flora=[column("basalt", "shroomlight", 22, (2, 5))],
        features=[bedlight("magma_block", 0.2)], light=lights("glowstone", 44, 3), color="red"),
      P("generic_flooded_cavern", "Flooded Cavern",
        "Water found every crack. The ceiling weeps, and the floor remembers the lake it used to be.",
        weight=0.6, shapes={"bowl_bridges": 3, "pillar_maze": 2, "canyon": 1, "karst": 1, "sea_cave": 1, "amphitheatre": 1},
        floor=["gravel@14", "clay@12", "andesite@14", "rock@60"], patch=["moss_block"], patch_at=0.3,
        wall=["moss_block@38", "dripstone_block@24", "clay@8", "sea_lantern@6", "andesite@24"],
        spike=["dripstone_block@1", "rock@2"], platform=["mossy_stone_bricks@1", "stone_bricks@5"], fluid="water",
        flora=[hang("cave_vines_plant[berries=true]", "cave_vines[berries=true]", 30, (2, 8)),
               plant(["glow_lichen[down=true]", "moss_carpet"], 80), plant("fern", 20)],
        features=[bedlight("sea_lantern", 0.05)], light=lights("ochre_froglight", 44), color="warm"),
      P("generic_deepslate_depths", "Deepslate Depths",
        "Far below the roots of the mountains, the stone goes dark and quiet and strangely clean.",
        match={"maxY": -4}, weight=1.0,
        shapes={"shaft": 2, "canyon": 2, "crevasse": 2, "bowl_bridges": 2, "pillar_maze": 1, "amphitheatre": 1},
        floor=["deepslate@30", "cobbled_deepslate@20", "tuff@10", "rock@40"], patch=["sculk"], patch_at=0.45,
        wall=["deepslate@40", "cobbled_deepslate@30", "polished_deepslate@8", "tuff@12", "calcite@4"],
        spike=["deepslate@2", "tuff@1"], platform=["deepslate_bricks@4", "chiseled_deepslate@1"], fluid="water",
        flora=[plant("sculk_vein[down=true]", 40), plant("glow_lichen[down=true]", 50), column("deepslate", None, 16, (2, 4))],
        features=[vein("amethyst_block", 6.0, 0.035)], light=lights(["sea_lantern", "verdant_froglight"], 52, 2),
        ambient=[("minecraft:bat", (2, 4), "air")], color="cyan"),
      P("generic_mine_vault", "Mine Vault",
        "The seam ran out here, into a hollow the miners never meant to find. Their lanterns still hang from the beams.",
        match={"themes": ["mine"]}, weight=1.4,
        shapes={"canyon": 3, "shaft": 2, "bowl_bridges": 2, "pillar_maze": 1, "floating_islands": 1, "lava_river": 1},
        floor=["cobblestone@8", "andesite@12", "gravel@10", "rock@70"],
        wall=["andesite@26", "cobblestone@18", "tuff@12", "gravel@10", "coal_ore@4", "iron_ore@4", "copper_ore@3", "rock@23"],
        spike=["andesite@1", "rock@2"], platform=["stone_bricks@1", "oak_planks@2"], pillar=["oak_log@3", "andesite@1"],
        fluid={"kind": "water", "chance": 0.6},
        flora=[plant(["coal_ore", "iron_ore"], 14), hang("chain", "lantern[hanging=true]", 8, (3, 5))],
        features=[vein(["iron_ore", "copper_ore", "gold_ore"], 5.0, 0.04), ruins("oak_planks", (3, 6))],
        light=lights("glowstone", 60), setpiece=("camp",), color="warm",
        loot={"relic": "skycraft:chests/relic_mine", "platform": "skycraft:chests/mine_ore", "minor": "skycraft:chests/mine_ore"}),
      P("generic_barrow_vault", "Barrow Vault",
        "The old dead built downward, and when the mountain gave them no more room they dug into the mountain's own bones.",
        match={"themes": ["barrow"]}, weight=1.2,
        shapes={"cathedral": 3, "amphitheatre": 2, "collapsed_city": 2, "pillar_maze": 2, "bowl_bridges": 1, "bone_cavern": 1},
        floor=["stone_bricks@20", "cracked_stone_bricks@12", "cobblestone@10", "rock@58"], patch=["gravel"], patch_at=0.35,
        wall=["stone_bricks@28", "mossy_stone_bricks@14", "cobblestone@10", "andesite@20", "rock@28"],
        spike=["andesite@1", "rock@2"], platform=["stone_bricks@4", "chiseled_stone_bricks@1"],
        pillar=["stone_bricks@4", "cracked_stone_bricks@1", "mossy_stone_bricks@1"], ruin=["cracked_stone_bricks@2", "mossy_stone_bricks@2", "cobblestone@1"],
        fluid={"kind": "water", "chance": 0.4},
        flora=[plant("cobweb", 20), ceil("cobweb", 24), plant("bone_block", 8)],
        features=[ruins(["cracked_stone_bricks", "mossy_cobblestone"], (4, 8)), statues("stone_bricks", None, (1, 2))],
        light=lights("shroomlight", 70, 1), setpiece=("altar", "stone_bricks", "soul_lantern"), foes=["skycraft:draugr"],
        color="cyan", loot={"relic": "skycraft:chests/relic_barrow", "platform": "skycraft:chests/dungeon_common", "minor": "skycraft:chests/dungeon_minor"}),
      P("generic_ruin_hall", "Sunken Hall",
        "A hall of a people long gone, the roof fallen in and the floor settled below its first level.",
        match={"themes": ["ruin", "fort"]}, weight=1.2,
        shapes={"collapsed_city": 3, "cathedral": 2, "amphitheatre": 2, "pillar_maze": 1, "bowl_bridges": 1},
        floor=["stone_bricks@18", "mossy_stone_bricks@10", "gravel@10", "rock@62"], patch=["moss_block"], patch_at=0.3,
        wall=["stone_bricks@30", "mossy_stone_bricks@20", "cobblestone@10", "rock@40"], spike=["andesite@1", "rock@2"],
        platform=["chiseled_stone_bricks@1", "stone_bricks@5"], ruin=["stone_bricks@2", "cracked_stone_bricks@2", "mossy_stone_bricks@1"],
        fluid={"kind": "water", "chance": 0.5},
        flora=[plant(["moss_carpet", "fern"], 70), ceil("glow_lichen[down=true]", 60)],
        features=[ruins(["stone_bricks", "mossy_stone_bricks"], (5, 9)), statues("stone_bricks", "lantern", (1, 3))],
        light=lights("ochre_froglight", 60), setpiece=("statue", "stone_bricks", None),
        color="warm", loot={"relic": "skycraft:chests/relic_ruin", "platform": "skycraft:chests/dungeon_common", "minor": "skycraft:chests/dungeon_minor"}),
      P("generic_dwemer_foundry", "Dwemer Foundry",
        "Green copper and cold brass, and under it all the long patient hum of a machine nobody switched off.",
        match={"themes": ["dwemer"]}, weight=1.6,
        shapes={"cathedral": 3, "pillar_maze": 2, "amphitheatre": 2, "collapsed_city": 1, "bowl_bridges": 1, "lava_river": 1, "honeycomb": 1},
        floor=["cut_copper@10", "exposed_cut_copper@8", "weathered_cut_copper@6", "rock@50"],
        wall=["cut_copper@12", "exposed_cut_copper@12", "weathered_cut_copper@10", "oxidized_cut_copper@6", "smooth_stone@8", "rock@30"],
        spike=["cut_copper@1", "rock@3"], platform=["cut_copper@3", "chiseled_copper@1" if False else "copper_block@1"],
        pillar=["cut_copper@3", "exposed_cut_copper@2", "weathered_cut_copper@1"],
        ruin=["weathered_cut_copper@2", "oxidized_cut_copper@2", "copper_block@1"], fluid={"kind": "water", "chance": 0.5},
        flora=[plant(["copper_block", "raw_copper_block"], 8), column("copper_block", "lantern", 10, (3, 5))],
        features=[smoke((3, 5), "cut_copper"), vein("copper_ore", 5.0, 0.04), ruins("oxidized_cut_copper", (3, 6))],
        light=lights(["ochre_froglight", "glowstone"], 56), setpiece=("obelisk", "weathered_cut_copper", "lantern"),
        foes=["skycraft:dwarven_spider"], color="warm", particle="steam",
        loot={"relic": "skycraft:chests/relic_dwemer", "platform": "skycraft:chests/dwemer_scrap", "minor": "skycraft:chests/dwemer_scrap"}),
      P("generic_vampire_undercroft", "Vampire Undercroft",
        "Black stone and red candle-wax; a place built by people who wanted to be watched while they were not seen.",
        match={"themes": ["vampire_lair"]}, weight=1.6,
        shapes={"cathedral": 3, "amphitheatre": 2, "pillar_maze": 1, "collapsed_city": 1, "bowl_bridges": 1},
        floor=["blackstone@20", "polished_blackstone_bricks@10", "red_terracotta@6", "rock@50"],
        wall=["blackstone@35", "cracked_deepslate_bricks@20", "red_terracotta@8", "bone_block@6", "rock@30"], spike=["blackstone@3", "bone_block@1"],
        platform=["polished_blackstone_bricks@4", "gilded_blackstone@1"], pillar=["polished_blackstone_bricks@3", "blackstone@2"],
        ruin=["cracked_deepslate_bricks@2", "blackstone@2"], fluid={"kind": "pool", "block": ["red_concrete_powder"], "chance": 0.35} if False else "none",
        flora=[plant("candle[lit=true]", 18), ceil("hanging_roots", 20), plant("bone_block", 10)],
        features=[statues("polished_blackstone_bricks", "soul_lantern", (2, 3)), vein("red_nether_bricks", 5.0, 0.035)],
        light=lights(["shroomlight", "crying_obsidian"], 60, 1, "soul_lantern"), setpiece=("altar", "polished_blackstone_bricks", "soul_lantern"),
        foes=["skycraft:vampire"], color="red", ambient=[("minecraft:bat", (4, 7), "air")]),
      P("generic_necromancer_ossuary", "Ossuary Cavern",
        "Every wall is stacked with the bones of the people who were never allowed to rest.",
        match={"themes": ["necromancer_lair"]}, weight=1.6,
        shapes={"bone_cavern": 3, "cathedral": 2, "pillar_maze": 1, "amphitheatre": 1, "bowl_bridges": 1},
        floor=["soul_soil@18", "tuff@14", "bone_block@6", "rock@50"], patch=["soul_sand"], patch_at=0.35,
        wall=["tuff@30", "bone_block@22", "soul_soil@16", "cobbled_deepslate@20", "amethyst_block@4", "rock@8"], spike=["bone_block@2", "tuff@2"],
        platform=["deepslate_bricks@3", "chiseled_deepslate@1"], pillar=["bone_block@4", "tuff@1"],
        fluid="none", flora=[plant("bone_block", 24), plant("skeleton_skull", 6), plant("soul_fire", 5), ceil("cobweb", 30)],
        features=[ribs(["bone_block"], 5), boulders("bone_block", (2, 4), (1, 2))],
        light=lights("soul_lantern" if False else "sea_lantern", 70, 2), setpiece=("altar", "deepslate_bricks", "soul_lantern"),
        foes=["minecraft:skeleton"], elite="Bone-Reader", color="cyan"),
      P("generic_hagraven_nest", "Hagraven Nest",
        "Antlers, feathers and knotted roots: a nest of something that was a woman once, and wishes you would forget it.",
        match={"themes": ["hagraven_lair"]}, weight=1.6,
        shapes={"root_cavern": 3, "karst": 2, "bowl_bridges": 1, "pillar_maze": 1},
        floor=["mud@16", "rooted_dirt@14", "moss_block@10", "rock@50"], wall=["mud@24", "mangrove_roots@12", "rooted_dirt@18", "moss_block@14", "rock@20"],
        spike=["mangrove_roots@1", "dripstone_block@2"], platform=["mud_bricks@3", "stone_bricks@1"], pillar=["mangrove_log@3", "mangrove_roots@2"],
        fluid={"kind": "water", "chance": 0.6},
        flora=[hang("hanging_roots", None, 40, (2, 7)), plant(["bone_block", "mangrove_roots"], 30), ceil("cobweb", 20), plant("brown_mushroom", 30)],
        features=[boulders("bone_block", (2, 4), (1, 2)), vein("mangrove_roots", 5.0, 0.03)],
        light=lights("shroomlight", 52), setpiece=("shrine", "mud_bricks", "soul_lantern"), foes=["skycraft:hagraven", "skycraft:forsworn"],
        color="green", ambient=[("minecraft:bat", (2, 4), "air")]),
      P("generic_highland_ice", "High Ice Hollow",
        "Cold air pools in the high places and the rock never thaws. Snow falls in here from no sky at all.",
        match={"minY": 100, "temps": ["cold"]}, weight=1.3,
        shapes={"crevasse": 3, "canyon": 2, "bowl_bridges": 1, "amphitheatre": 1, "karst": 1},
        floor=["snow_block@28", "packed_ice@18", "rock@54"], wall=["packed_ice@40", "blue_ice@24", "snow_block@20", "rock@16"],
        spike=["packed_ice@2", "blue_ice@1"], platform=["polished_diorite@3", "blue_ice@1"], fluid={"kind": "ice", "chance": 0.7},
        flora=[plant("snow", 130), spire("packed_ice", None, "blue_ice", 12, (4, 9))],
        features=[pool("powder_snow", (2, 3), (2, 3))], light=lights(["sea_lantern", "blue_ice"], 60), color="blue",
        foes=["skycraft:ice_wraith", "skycraft:frost_atronach"]),
      P("generic_jungle_overgrowth", "Overgrown Hollow",
        "The roof is a green ceiling of its own, the roots of things that grow outside, working down to the water.",
        match={"temps": ["wet"]}, weight=1.0,
        shapes={"root_cavern": 3, "karst": 2, "bowl_bridges": 2, "pillar_maze": 1, "canyon": 1},
        floor=["moss_block@20", "rooted_dirt@10", "mud@8", "rock@40"], patch=["moss_block"], patch_at=0.2,
        wall=["moss_block@36", "rooted_dirt@18", "mud@10", "jungle_log@6", "rock@30"], spike=["dripstone_block@1", "rock@2"],
        platform=["mossy_stone_bricks@3", "stone_bricks@2"], pillar=["jungle_log@3", "mangrove_roots@1"], fluid="water",
        flora=[hang("vine", None, 50, (3, 8)), plant(["fern", "large_fern", "azalea"], 80), plant("moss_carpet", 60),
               mush("mushroom_stem", ["brown_mushroom_block"], "shroomlight", 8), bed("seagrass", 40), floatp("lily_pad", 30)],
        features=[bedlight("sea_lantern", 0.04)], light=lights(["verdant_froglight", "shroomlight"], 52), color="green",
        ambient=[("minecraft:bat", (2, 4), "air"), ("minecraft:axolotl", (2, 4), "water")]),
      P("generic_desert_hollow", "Dry Hollow",
        "Sand sifts down from cracks above and the air tastes of old salt. Whatever water there was, was a long time ago.",
        match={"temps": ["hot"]}, weight=1.0,
        shapes={"canyon": 3, "bowl_bridges": 2, "karst": 2, "collapsed_city": 1, "shaft": 1},
        floor=["sandstone@24", "sand@14", "cut_sandstone@6", "rock@30"], wall=["sandstone@40", "smooth_sandstone@20", "red_sandstone@10", "rock@20"],
        spike=["sandstone@2", "cut_sandstone@1"], platform=["cut_sandstone@4", "chiseled_sandstone@1"], fluid={"kind": "water", "chance": 0.25},
        flora=[plant(["dead_bush", "sand"], 40), column("cut_sandstone", None, 12, (2, 4))],
        features=[ruins(["cut_sandstone", "sandstone"], (3, 6)), boulders("sandstone", (3, 5), (2, 3))],
        light=lights(["glowstone", "ochre_froglight"], 56), color="warm"),
      P("generic_volcanic_vent", "Volcanic Vent",
        "Ash drifts through the dark and the rock ticks as it cools. Heat comes up through every crack.",
        match={"temps": ["volcanic"]}, weight=1.2,
        shapes={"lava_river": 3, "lava_tube": 2, "bowl_bridges": 2, "crevasse": 1, "amphitheatre": 1},
        floor=["basalt@24", "blackstone@20", "magma_block@3", "rock@53"], wall=["basalt@36", "blackstone@30", "magma_block@12", "obsidian@8", "glowstone@4"],
        spike=["basalt@2", "blackstone@2"], platform=["polished_blackstone_bricks@4", "gilded_blackstone@1"], fluid="lava",
        flora=[column("basalt", "shroomlight", 20, (2, 5)), plant("fire", 4)],
        features=[smoke((3, 6), "basalt"), bedlight("magma_block", 0.25), vein("magma_block", 5.0, 0.03)],
        light=lights("glowstone", 48, 3), color="red", particle="ash",
        foes=["skycraft:ash_spawn", "skycraft:flame_atronach"]),
      )

# ======================================================================================================== skyrim
group("skyrim",
      P("skyrim_blackreach_forest", "Glowing Mushroom Forest",
        "Beneath the holds, a second forest: caps the height of towers, lit from inside, and the quiet of a place that has never had weather.",
        match={"provinces": ["skyrim"], "themes": ["cave", "ruin", "hagraven_lair"]}, weight=1.3,
        shapes={"bowl_bridges": 3, "root_cavern": 2, "karst": 2, "canyon": 1},
        floor=["moss_block@20", "mycelium@10", "podzol@8", "rock@40"], patch=["mycelium"], patch_at=0.3,
        wall=["moss_block@30", "mud@14", "shroomlight@8", "dripstone_block@22", "tuff@10"],
        spike=["dripstone_block@1", "rock@2"], platform=["mossy_stone_bricks@2", "stone_bricks@3"], fluid="water",
        flora=[mush("mushroom_stem", ["red_mushroom_block", "brown_mushroom_block"], "shroomlight", 26, 3),
               mush("mushroom_stem", ["brown_mushroom_block"], "glowstone", 14, 2),
               plant(["red_mushroom", "brown_mushroom"], 80), plant("glow_lichen[down=true]", 70), plant("moss_carpet", 60),
               hang("cave_vines_plant[berries=true]", "cave_vines[berries=true]", 20, (3, 8)), floatp("lily_pad", 30)],
        features=[bedlight("sea_lantern", 0.06), pool("water", (2, 3), (2, 3), "sea_lantern", "moss_block")],
        light=lights(["shroomlight", "verdant_froglight"], 36, 2), foes=["skycraft:falmer", "skycraft:chaurus"],
        color="cyan", particle="spore", ambient=[("minecraft:bat", (2, 5), "air"), ("minecraft:glow_squid", (2, 4), "water")],
        loot=fl("cave", "fungal")),
      P("skyrim_falmer_hive", "Falmer Hive",
        "Chitin plate and tallow-wax, cell upon cell. The Snow Elves did not go blind by accident.",
        match={"provinces": ["skyrim"], "rosters": ["falmer", "spider"], "kits": ["falmer"]} if False else {"rosters": ["falmer", "spider"], "provinces": ["skyrim"]},
        weight=1.6, shapes={"honeycomb": 4, "pillar_maze": 2, "bowl_bridges": 1, "collapsed_city": 1},
        floor=["dripstone_block@16", "tuff@16", "mud@8", "rock@60"], wall=["dripstone_block@30", "tuff@20", "honeycomb_block@14", "brown_terracotta@10", "rock@26"],
        spike=["dripstone_block@2", "tuff@1"], platform=["brown_terracotta@2", "dripstone_block@2"],
        ruin=["brown_terracotta@2", "dripstone_block@2", "honeycomb_block@1"], pillar=["dripstone_block@3", "brown_terracotta@1"],
        fluid={"kind": "pool", "block": ["mud"], "chance": 0.55},
        flora=[ceil("cobweb", 40), plant("cobweb", 16), plant("turtle_egg[eggs=4]", 8), plant("brown_mushroom", 40), plant("bone_block", 10)],
        features=[ruins(["dripstone_block", "brown_terracotta"], (3, 6)), vein("honeycomb_block", 5.0, 0.04)],
        light=lights(["shroomlight", "ochre_froglight"], 60), foes=["skycraft:falmer", "skycraft:chaurus", "skycraft:frostbite_spider"],
        elite="Falmer Warmonger", color="orange", setpiece=("shrine", "dripstone_block", "lantern"), loot=fl("cave", "hive")),
      P("skyrim_frost_cavern", "Frost Cavern",
        "Meltwater ran down these walls once and stopped, mid-fall. The blue in the ice is old.",
        match={"provinces": ["skyrim"], "weather": ["frozen", "skyrim_cold"]}, weight=1.3,
        shapes={"crevasse": 3, "bowl_bridges": 2, "canyon": 2, "amphitheatre": 1, "cathedral": 1, "karst": 1},
        floor=["packed_ice@20", "blue_ice@8", "snow_block@14", "rock@58"], patch=["snow_block"], patch_at=0.1,
        wall=["packed_ice@40", "blue_ice@30", "sea_lantern@6", "snow_block@10", "stone@14"], spike=["packed_ice@3", "blue_ice@2"],
        platform=["polished_diorite@3", "blue_ice@1", "stone_bricks@1"], fluid={"kind": "ice"},
        flora=[spire("blue_ice", "sea_lantern", "packed_ice", 14, (4, 9)), plant("snow", 100), plant("powder_snow" if False else "snow", 40)],
        features=[bedlight("sea_lantern", 0.05), pool("powder_snow", (1, 2), (2, 2))],
        light=lights(["sea_lantern", "blue_ice"], 50), foes=["skycraft:ice_wraith", "skycraft:frost_atronach"], color="blue",
        loot=fl("cave", "frost"), ambient=[("minecraft:bat", (1, 3), "air")]),
      P("skyrim_nordic_vault", "Nordic Burial Vault",
        "Stone ribs, iron-bound doors and the carved prayers of a people who meant to keep their dead where they put them.",
        match={"provinces": ["skyrim", "atmora"], "themes": ["barrow", "ruin", "fort"]}, weight=1.5,
        shapes={"cathedral": 4, "amphitheatre": 2, "collapsed_city": 2, "pillar_maze": 1, "bone_cavern": 1},
        floor=["stone_bricks@20", "cracked_stone_bricks@10", "gravel@8", "rock@62"], patch=["snow_block"] if False else None,
        wall=["stone_bricks@26", "mossy_stone_bricks@10", "cracked_stone_bricks@10", "andesite@16", "rock@38"],
        spike=["andesite@1", "rock@2"], platform=["chiseled_stone_bricks@1", "stone_bricks@5"],
        pillar=["stone_bricks@4", "chiseled_stone_bricks@1", "cracked_stone_bricks@1"],
        ruin=["cracked_stone_bricks@2", "stone_bricks@2", "mossy_stone_bricks@1"], fluid={"kind": "water", "chance": 0.35},
        flora=[ceil("cobweb", 22), plant(["bone_block", "skeleton_skull"], 8), plant("candle[lit=true]", 8), plant("moss_carpet", 30)],
        features=[statues("stone_bricks", "soul_lantern", (2, 3)), ruins(["cracked_stone_bricks", "stone_bricks"], (4, 8))],
        light=lights("shroomlight", 70, 0, "soul_lantern"), setpiece=("altar", "stone_bricks", "soul_lantern"),
        foes=["skycraft:draugr"], elite="Draugr Overlord", color="cyan", loot=fl("barrow", "ancient")),
      P("skyrim_reach_crystal_mine", "Reach Crystal Mine",
        "The Reachmen cut here with bone picks. The crystal veins are thick, and the bones of the miners are thicker.",
        match={"regions": ["skyrim/the_reach"], "themes": ["mine", "cave", "ruin", "hagraven_lair"]}, weight=1.6,
        shapes={"shaft": 3, "canyon": 2, "bowl_bridges": 2, "karst": 1, "floating_islands": 1},
        floor=["tuff@18", "calcite@8", "andesite@12", "rock@62"], wall=["tuff@28", "calcite@18", "amethyst_block@8", "iron_ore@5", "gold_ore@3", "rock@38"],
        spike=["calcite@1", "tuff@2"], platform=["tuff_bricks@1" if False else "polished_andesite@3", "calcite@1"],
        pillar=["oak_log@2", "calcite@1"], fluid={"kind": "water", "chance": 0.5},
        flora=[spire("amethyst_block", "glowstone", "amethyst_cluster[facing=up]", 12), plant("amethyst_cluster[facing=up]", 50),
               plant(["bone_block", "skeleton_skull"], 6), hang("chain", "lantern[hanging=true]", 6, (3, 5))],
        features=[vein(["gold_ore", "iron_ore"], 5.0, 0.04), ruins("oak_planks", (3, 5))],
        light=lights(["glowstone", "pearlescent_froglight"], 56, 2), foes=["skycraft:forsworn"], color="violet", loot=fl("mine", "crystal"),
        setpiece=("shrine", "polished_andesite", "lantern")),
      P("skyrim_glacial_sea_cave", "Glacier Sea Cave",
        "Where the northern sea eats the glacier from underneath, the ice goes hollow and blue-black and the water tastes of iron.",
        match={"regions": ["skyrim/winterhold", "skyrim/haafingar", "skyrim/the_pale"]}, weight=1.6,
        shapes={"sea_cave": 4, "crevasse": 2, "floating_islands": 1, "bowl_bridges": 1},
        floor=["gravel@14", "snow_block@12", "packed_ice@12", "rock@62"], wall=["packed_ice@34", "blue_ice@30", "stone@20", "snow_block@10", "sea_lantern@4"],
        spike=["packed_ice@2", "blue_ice@1"], platform=["blue_ice@1", "stone_bricks@3"], terrace=["gravel@2", "snow_block@1"],
        fluid={"kind": "ice", "chance": 1.0},
        flora=[spire("blue_ice", "sea_lantern", "packed_ice", 10), plant("snow", 90), bed("kelp", 20)],
        features=[pool("water", (2, 3), (2, 3)), bedlight("sea_lantern", 0.04), boulders("packed_ice", (3, 5), (2, 3))],
        light=lights(["sea_lantern", "blue_ice"], 48), foes=["skycraft:ice_wraith", "skycraft:horker"], color="blue", loot=fl("cave", "sea"),
        ambient=[("minecraft:glow_squid", (2, 4), "water"), ("minecraft:cod", (3, 6), "water")]),
      P("skyrim_geothermal_cave", "Geothermal Cave",
        "Hot springs steam under the tundra, and where the steam meets cold stone, the walls drip and stain red and gold.",
        match={"regions": ["skyrim/eastmarch", "skyrim/winterhold"], "provinces": ["skyrim"]}, weight=1.8,
        shapes={"karst": 3, "bowl_bridges": 2, "lava_river": 1, "amphitheatre": 1, "canyon": 1},
        floor=["tuff@18", "calcite@10", "terracotta@6", "rock@50"], wall=["tuff@28", "calcite@16", "orange_terracotta@10", "yellow_terracotta@6", "rock@30"],
        spike=["calcite@2", "dripstone_block@2"], platform=["polished_andesite@3", "calcite@1"], fluid="water",
        flora=[plant("fern", 40), plant("moss_carpet", 30), column("calcite", None, 10, (1, 3))],
        features=[pool("water", (3, 5), (2, 3), "glowstone", "calcite"), smoke((2, 4), "calcite"), bubbles(0.05)],
        light=lights(["ochre_froglight", "glowstone"], 56), color="orange", particle="steam", loot=fl("cave", "ember"),
        setpiece=("well", "calcite", "lantern"), ambient=[("minecraft:bat", (2, 4), "air")]),
      P("skyrim_giant_bone_hall", "Hall of Old Bones",
        "The giants and the mammoths came here to die, or were brought. Their ribs make a roof no mason could.",
        match={"regions": ["skyrim/whiterun", "skyrim/the_pale", "skyrim/hjaalmarch", "skyrim/the_rift"], "themes": ["cave", "ruin", "barrow"]},
        weight=1.5, shapes={"bone_cavern": 4, "canyon": 1, "cathedral": 1, "karst": 1},
        floor=["gravel@12", "coarse_dirt@10", "bone_block@4", "rock@60"], wall=["stone@30", "tuff@16", "bone_block@10", "andesite@20", "gravel@6"],
        spike=["bone_block@1", "andesite@2"], platform=["stone_bricks@3", "bone_block@1"], pillar=["bone_block@4", "stone@1"],
        fluid={"kind": "water", "chance": 0.3},
        flora=[plant(["bone_block", "skeleton_skull"], 20), ceil("cobweb", 14)],
        features=[ribs(["bone_block"], 6), boulders("bone_block", (3, 5), (1, 2)), boulders("stone", (2, 4), (2, 3))],
        light=lights(["glowstone", "shroomlight"], 60), foes=["skycraft:troll", "skycraft:giant", "skycraft:sabre_cat"],
        elite="Bonecrusher", color="warm", loot=fl("cave", "bone")),
      P("skyrim_bog_grotto", "Fen Grotto",
        "The marsh gets in everywhere: mud to the knee, mist at the roof, and the lazy glow of rot.",
        match={"regions": ["skyrim/hjaalmarch", "skyrim/falkreath"], "provinces": ["skyrim"]}, weight=1.5,
        shapes={"pillar_maze": 3, "root_cavern": 2, "bowl_bridges": 1, "karst": 1},
        floor=["mud@28", "muddy_mangrove_roots@4", "clay@8", "rock@40"], patch=["moss_block"], patch_at=0.3,
        wall=["mud@26", "moss_block@22", "mangrove_roots@10", "clay@8", "rock@34"], spike=["mud@1", "mangrove_roots@1", "rock@2"],
        platform=["mud_bricks@3", "stone_bricks@2"], pillar=["mangrove_log@3", "mangrove_roots@2"], fluid="water",
        flora=[hang("hanging_roots", None, 30, (2, 6)), plant(["brown_mushroom", "fern"], 70), floatp("lily_pad", 60), bed("seagrass", 60),
               plant("moss_carpet", 50)],
        features=[bedlight("sea_lantern", 0.04), bubbles(0.03)], light=lights(["verdant_froglight", "shroomlight"], 56),
        color="green", particle="mist", ambient=[("minecraft:frog", (2, 4), "floor"), ("minecraft:bat", (1, 3), "air")], loot=fl("cave", "root")),
      P("skyrim_autumn_roots", "Autumn Root Cave",
        "The trees up top turn gold and rust every year; down here, their roots go on without noticing, pale and wet and thick as a man.",
        match={"regions": ["skyrim/the_rift", "skyrim/falkreath"]}, weight=1.6,
        shapes={"root_cavern": 4, "karst": 2, "bowl_bridges": 1, "cathedral": 1},
        floor=["rooted_dirt@18", "podzol@14", "coarse_dirt@8", "rock@40"], patch=["moss_block"], patch_at=0.25,
        wall=["rooted_dirt@24", "podzol@16", "mangrove_roots@8", "moss_block@12", "rock@40"], spike=["mangrove_roots@1", "dripstone_block@1", "rock@2"],
        platform=["mossy_stone_bricks@2", "stone_bricks@3"], pillar=["oak_log@3", "dark_oak_log@1", "mangrove_roots@1"], fluid={"kind": "water", "chance": 0.7},
        flora=[hang("hanging_roots", None, 50, (3, 8)), plant(["fern", "brown_mushroom", "orange_tulip"], 70), plant("moss_carpet", 50),
               tree("oak_log", "azalea_leaves", "shroomlight", 6, (9, 14), 3)],
        features=[vein("mangrove_roots", 5.0, 0.03)], light=lights(["ochre_froglight", "shroomlight"], 52), color="orange",
        setpiece=("tree", "oak_log", "azalea_leaves"), foes=["skycraft:spriggan"], loot=fl("cave", "root")),
      )

# ======================================================================================================== morrowind
group("morrowind",
      P("morrowind_ash_glass_tube", "Ash-Glass Tube",
        "A vent the width of a street, cooled to black glass. The drifts of ash in the floor were built by the wind.",
        match={"provinces": ["morrowind"], "weather": ["ash"]}, weight=1.8,
        shapes={"lava_tube": 4, "lava_river": 3, "crevasse": 1, "bowl_bridges": 1},
        floor=["basalt@24", "smooth_basalt@16", "blackstone@14", "gray_concrete_powder@0" if False else "tuff@10", "rock@36"],
        wall=["basalt@34", "blackstone@22", "obsidian@14", "black_stained_glass@6", "magma_block@8", "smooth_basalt@16"],
        spike=["basalt@2", "obsidian@1"], platform=["polished_blackstone_bricks@4", "gilded_blackstone@1"], fluid="lava",
        flora=[column("basalt", "shroomlight", 18, (2, 5)), plant("gray_carpet", 60), spire("obsidian", None, "crying_obsidian", 8, (3, 6))],
        features=[bedlight("magma_block", 0.2), smoke((2, 4), "basalt")],
        light=lights(["glowstone", "shroomlight"], 54, 3), foes=["skycraft:ash_spawn", "skycraft:flame_atronach"], color="red", particle="ash",
        loot=fl("cave", "ember")),
      P("morrowind_kwama_warren", "Kwama Warren",
        "Sulphur-yellow combs and the dull amber of buried eggs. The warren is older than any city above it.",
        match={"provinces": ["morrowind"], "themes": ["cave", "mine", "ruin"]}, weight=1.5,
        shapes={"honeycomb": 4, "pillar_maze": 2, "karst": 1, "collapsed_city": 1},
        floor=["terracotta@14", "yellow_terracotta@12", "sandstone@8", "rock@50"], wall=["yellow_terracotta@26", "orange_terracotta@18", "terracotta@14", "honeycomb_block@10", "sandstone@12", "rock@20"],
        spike=["yellow_terracotta@2", "terracotta@1"], platform=["sandstone@2", "cut_sandstone@2"], ruin=["yellow_terracotta@2", "terracotta@2", "honeycomb_block@1"],
        fluid={"kind": "pool", "block": ["mud@3", "packed_mud@1"], "chance": 0.5},
        flora=[plant("turtle_egg[eggs=3]", 18), plant("sniffer_egg" if False else "turtle_egg[eggs=2]", 10), plant("brown_mushroom", 30), ceil("cobweb", 14)],
        features=[ruins(["yellow_terracotta", "terracotta"], (3, 6)), vein("honeycomb_block", 5.0, 0.04)],
        light=lights(["shroomlight", "ochre_froglight"], 56), foes=["minecraft:silverfish", "minecraft:endermite", "skycraft:mudcrab"],
        elite="Kwama Warrior", color="orange", setpiece=("shrine", "yellow_terracotta", "lantern"), loot=fl("cave", "hive")),
      P("morrowind_netch_cavern", "Netch Cavern",
        "The netch drift up through the vents on the warm air, trailing their pale bags of gas; the ribs of the dead hang from the roof.",
        match={"provinces": ["morrowind"], "weather": ["ash", "maritime", "swamp"]}, weight=1.3,
        shapes={"bone_cavern": 3, "bowl_bridges": 2, "canyon": 1, "karst": 1, "floating_islands": 1},
        floor=["smooth_basalt@12", "tuff@12", "gravel@8", "rock@60"], wall=["tuff@26", "smooth_basalt@22", "bone_block@8", "calcite@10", "rock@34"],
        spike=["calcite@1", "tuff@2"], platform=["polished_andesite@3", "calcite@1"], pillar=["bone_block@3", "calcite@1"],
        fluid={"kind": "water", "chance": 0.5},
        flora=[hang("hanging_roots", None, 20, (3, 7)), plant(["bone_block", "skeleton_skull"], 16), plant("glow_lichen[down=true]", 60), plant("moss_carpet", 30)],
        features=[ribs(["bone_block"], 6), bedlight("sea_lantern", 0.04)],
        light=lights(["pearlescent_froglight", "sea_lantern"], 54, 2), foes=["skycraft:ash_spawn", "skycraft:kagouti"], color="violet",
        ambient=[("skycraft:netch", (1, 2), "air"), ("minecraft:bat", (2, 4), "air")], loot=fl("cave", "bone")),
      P("morrowind_ancestral_vault", "Ancestral Vault",
        "Ash-grey stone, bone-white and iron: the Great Houses lay their dead in rooms like this and visit them by night.",
        match={"provinces": ["morrowind"], "themes": ["barrow", "ruin", "fort"], "kits": ["velothi", "none"]}, weight=1.6,
        shapes={"cathedral": 3, "collapsed_city": 2, "amphitheatre": 2, "pillar_maze": 1, "bone_cavern": 1},
        floor=["smooth_basalt@12", "basalt@10", "stone_bricks@10", "rock@50"], patch=["gray_concrete_powder"] if False else None,
        wall=["basalt@24", "smooth_basalt@18", "stone_bricks@14", "cracked_stone_bricks@8", "bone_block@4", "rock@30"], spike=["basalt@2", "bone_block@1"],
        platform=["polished_basalt@2", "stone_bricks@3"], pillar=["polished_basalt@3", "basalt@1", "bone_block@1"],
        ruin=["stone_bricks@2", "cracked_stone_bricks@2", "basalt@2"], fluid={"kind": "water", "chance": 0.3},
        flora=[plant("candle[lit=true]", 14), plant(["bone_block", "skeleton_skull"], 8), ceil("cobweb", 16)],
        features=[statues(["polished_basalt", "stone_bricks"], "lantern", (2, 3)), ruins(["basalt", "stone_bricks"], (4, 7))],
        light=lights(["shroomlight", "glowstone"], 64, 0, "lantern"), setpiece=("shrine", "polished_basalt", "soul_lantern"),
        foes=["skycraft:draugr"], elite="Ancestor Guardian", color="orange", loot=fl("barrow", "ancient")),
      P("morrowind_hot_spring_grotto", "Hot Spring Grotto",
        "Sulphur on the tongue and a pale haze on the water. The pools are warmer than a bath and the colour of old jade.",
        match={"provinces": ["morrowind"], "weather": ["temperate", "maritime", "ash"]}, weight=1.3,
        shapes={"karst": 3, "bowl_bridges": 2, "sea_cave": 1, "amphitheatre": 1},
        floor=["calcite@12", "terracotta@8", "sandstone@8", "rock@56"], wall=["calcite@22", "orange_terracotta@12", "yellow_terracotta@8", "terracotta@10", "rock@42"],
        spike=["calcite@2", "dripstone_block@1"], platform=["smooth_sandstone@3", "calcite@1"], fluid="water",
        flora=[plant(["fern", "moss_carpet"], 70), bed("seagrass", 40), column("calcite", None, 8, (1, 3))],
        features=[pool("water", (3, 5), (2, 3), "glowstone", "calcite"), smoke((2, 4), "calcite"), bubbles(0.06)],
        light=lights(["ochre_froglight", "shroomlight"], 56), color="orange", particle="steam", loot=fl("cave", "ember"),
        ambient=[("minecraft:axolotl", (1, 3), "water")], setpiece=("well", "calcite", "lantern")),
      P("morrowind_red_mountain_fire", "Red Mountain Fire Cave",
        "The mountain's heart is molten and restless. The walls are glazed with ash, and the air is a hand on your face.",
        match={"regions": ["morrowind/red_mountain", "morrowind/vvardenfell_ashlands", "morrowind/molag_amur", "morrowind/stonefalls"]},
        weight=1.8, shapes={"lava_river": 4, "bowl_bridges": 2, "amphitheatre": 2, "crevasse": 1, "lava_tube": 1},
        floor=["blackstone@22", "basalt@18", "magma_block@4", "netherrack@6", "rock@50"], wall=["blackstone@30", "basalt@24", "magma_block@12", "obsidian@6", "glowstone@6", "netherrack@8"],
        spike=["basalt@2", "blackstone@2", "obsidian@1"], platform=["polished_blackstone_bricks@4", "gilded_blackstone@1"], pillar=["basalt@3", "obsidian@1"],
        fluid="lava", flora=[column("basalt", "shroomlight", 24, (2, 6)), plant("fire", 6), spire("obsidian", None, "crying_obsidian", 8, (3, 6))],
        features=[bedlight("magma_block", 0.25), smoke((3, 6), "basalt"), vein("magma_block", 5.0, 0.035)],
        light=lights("glowstone", 44, 4), foes=["skycraft:flame_atronach", "skycraft:ash_spawn"], elite="Fire Warden",
        color="red", particle="ash", loot=fl("mine", "ember"), setpiece=("obelisk", "obsidian", "shroomlight")),
      P("morrowind_bitter_grotto", "Bitter Coast Grotto",
        "Giant fungi grow in the damp dark, and the spore clouds drift like fog. The coast above is all mud; down here it is all water.",
        match={"regions": ["morrowind/bitter_coast", "morrowind/azuras_coast", "morrowind/sheogorad", "morrowind/ascadian_isles"]},
        weight=1.7, shapes={"root_cavern": 3, "pillar_maze": 2, "bowl_bridges": 2, "karst": 1},
        floor=["mud@20", "moss_block@12", "muddy_mangrove_roots@4", "rock@44"], patch=["moss_block"], patch_at=0.3,
        wall=["mud@24", "moss_block@20", "mangrove_roots@10", "mushroom_stem@4", "rock@38"], spike=["mud@1", "mangrove_roots@1", "rock@2"],
        platform=["mud_bricks@3", "stone_bricks@1"], pillar=["mangrove_log@3", "mushroom_stem@2"], fluid="water",
        flora=[mush("mushroom_stem", ["brown_mushroom_block", "red_mushroom_block"], "shroomlight", 22, 3), plant(["brown_mushroom", "fern"], 70),
               hang("hanging_roots", None, 26, (2, 6)), floatp("lily_pad", 40), bed("seagrass", 40)],
        features=[bedlight("sea_lantern", 0.05), bubbles(0.03)], light=lights(["shroomlight", "verdant_froglight"], 40),
        foes=["skycraft:mudcrab", "skycraft:slaughterfish"], color="green", particle="spore",
        ambient=[("minecraft:frog", (2, 3), "floor"), ("minecraft:glow_squid", (2, 3), "water")], loot=fl("cave", "fungal")),
      P("morrowind_telvanni_spore_hall", "Spore Hall",
        "A wizard's cellar grown over by his own garden: caps like banquet tables, and a purple glow that never quite goes out.",
        match={"regions": ["morrowind/telvannis", "morrowind/grazelands", "morrowind/deshaan", "morrowind/west_gash"]},
        weight=1.6, shapes={"karst": 2, "root_cavern": 2, "cathedral": 2, "bowl_bridges": 2, "amphitheatre": 1},
        floor=["mycelium@18", "moss_block@12", "tuff@10", "rock@40"], patch=["mycelium"], patch_at=0.2,
        wall=["mycelium@14", "purple_terracotta@10" if False else "tuff@20", "moss_block@16", "shroomlight@6", "amethyst_block@4", "rock@30"],
        spike=["dripstone_block@1", "tuff@2"], platform=["polished_andesite@3", "amethyst_block@1"], fluid="water",
        flora=[mush("mushroom_stem", ["red_mushroom_block", "mushroom_stem"], "shroomlight", 24, 3), mush("mushroom_stem", ["brown_mushroom_block"], "sea_lantern", 10, 2),
               plant(["red_mushroom", "brown_mushroom", "warped_fungus"], 70), plant("moss_carpet", 40)],
        features=[bedlight("sea_lantern", 0.04)], light=lights(["pearlescent_froglight", "shroomlight"], 40, 1), color="violet", particle="spore",
        setpiece=("altar", "tuff", "soul_lantern"), foes=["skycraft:imp" if False else "skycraft:scamp"], loot=fl("cave", "arcane")),
      )

# ======================================================================================================== cyrodiil
group("cyrodiil",
      P("cyrodiil_ayleid_welkynd", "Welkynd Cavern",
        "The Ayleids cut their cities into the white bedrock and lit them with caught starlight. The stones are still burning.",
        match={"provinces": ["cyrodiil"], "kits": ["ayleid", "none"], "themes": ["ruin", "cave", "barrow", "fort"]}, weight=1.6,
        shapes={"cathedral": 3, "collapsed_city": 2, "amphitheatre": 2, "karst": 1, "bowl_bridges": 1, "floating_islands": 1},
        floor=["quartz_block@10", "smooth_quartz@8", "calcite@10", "rock@50"], wall=["calcite@30", "quartz_block@16", "smooth_quartz@8", "amethyst_block@6", "rock@40"],
        spike=["calcite@2", "quartz_block@1"], platform=["polished_diorite@2", "quartz_bricks@2", "chiseled_quartz_block@1"],
        pillar=["quartz_pillar@3", "calcite@1"], ruin=["quartz_bricks@2", "calcite@2", "smooth_quartz@1"], fluid={"kind": "water", "chance": 0.6},
        flora=[spire(["calcite", "quartz_block"], "sea_lantern", "light_blue_stained_glass", 10, (4, 8)), plant("amethyst_cluster[facing=up]", 30), plant("moss_carpet", 20)],
        features=[statues(["quartz_bricks", "smooth_quartz"], "sea_lantern", (2, 3)), ruins(["quartz_bricks", "calcite"], (3, 6))],
        light=lights(["sea_lantern", "pearlescent_froglight"], 44, 2), setpiece=("obelisk", "quartz_pillar", "sea_lantern"),
        foes=["minecraft:stray", "minecraft:skeleton"], color="cyan", loot=fl("ruin", "arcane"), elite="Ayleid Lich-Guard"),
      P("cyrodiil_great_forest_roots", "Great Forest Root Cave",
        "The oaks of the forest go down as far as they go up, and this is where their roots finally find water.",
        match={"regions": ["cyrodiil/great_forest", "cyrodiil/west_weald", "cyrodiil/heartlands", "cyrodiil/gold_coast"]}, weight=1.6,
        shapes={"root_cavern": 4, "karst": 2, "bowl_bridges": 1, "cathedral": 1},
        floor=["rooted_dirt@18", "moss_block@14", "podzol@8", "rock@40"], patch=["moss_block"], patch_at=0.25,
        wall=["rooted_dirt@22", "moss_block@16", "mangrove_roots@8", "oak_log@4", "rock@40"], spike=["mangrove_roots@1", "dripstone_block@1", "rock@2"],
        platform=["mossy_stone_bricks@3", "stone_bricks@2"], pillar=["oak_log@4", "mangrove_roots@1"], fluid={"kind": "water", "chance": 0.7},
        flora=[hang("hanging_roots", None, 50, (3, 9)), plant(["fern", "large_fern", "azalea", "flowering_azalea"], 80), plant("moss_carpet", 50),
               ceil("glow_lichen[down=true]", 40), tree("oak_log", "azalea_leaves", "shroomlight", 6, (9, 14), 3)],
        features=[vein("mangrove_roots", 5.0, 0.03)], light=lights(["shroomlight", "verdant_froglight"], 52), color="green",
        setpiece=("tree", "oak_log", "flowering_azalea_leaves"), foes=["skycraft:spriggan", "skycraft:wispmother"], loot=fl("cave", "root"),
        ambient=[("minecraft:bat", (1, 3), "air")]),
      P("cyrodiil_jerall_ice_cave", "Jerall Ice Cave",
        "The Jerall peaks keep their snow all year; here, the snow compacted and went blue and never came back out.",
        match={"regions": ["cyrodiil/jerall_mountains", "cyrodiil/colovian_highlands"]}, weight=1.6,
        shapes={"crevasse": 3, "canyon": 2, "amphitheatre": 2, "bowl_bridges": 1, "cathedral": 1},
        floor=["packed_ice@18", "snow_block@14", "blue_ice@6", "rock@62"], wall=["packed_ice@36", "blue_ice@28", "snow_block@14", "stone@18", "sea_lantern@4"],
        spike=["packed_ice@2", "blue_ice@1"], platform=["polished_diorite@3", "blue_ice@1"], fluid={"kind": "ice"},
        flora=[spire("blue_ice", "sea_lantern", "packed_ice", 14), plant("snow", 100)], features=[bedlight("sea_lantern", 0.05)],
        light=lights(["sea_lantern", "blue_ice"], 50), foes=["skycraft:ice_wraith", "skycraft:wolf"], color="blue", loot=fl("cave", "frost")),
      P("cyrodiil_nibenay_river_cave", "Nibenay River Cave",
        "A river that went underground a long time ago and kept going. The banks are clay, and the fish are pale.",
        match={"regions": ["cyrodiil/nibenay_basin", "cyrodiil/nibenay_valley", "cyrodiil/heartlands"]}, weight=1.7,
        shapes={"canyon": 3, "sea_cave": 2, "bowl_bridges": 2, "karst": 1},
        floor=["clay@14", "gravel@14", "sand@8", "rock@50"], wall=["clay@16", "andesite@24", "gravel@12", "dripstone_block@14", "moss_block@8", "rock@26"],
        spike=["dripstone_block@1", "andesite@1", "rock@2"], platform=["stone_bricks@3", "mossy_stone_bricks@1"], fluid="water",
        flora=[bed(["seagrass", "kelp"], 80), plant(["fern", "grass"], 40), floatp("lily_pad", 20)],
        features=[bedlight("sea_lantern", 0.04), bubbles(0.03)], light=lights(["ochre_froglight", "sea_lantern"], 54), color="warm",
        ambient=[("minecraft:cod", (4, 8), "water"), ("minecraft:salmon", (3, 5), "water")], foes=["skycraft:slaughterfish"], loot=fl("cave", "sea")),
      P("cyrodiil_blackwood_sinkhole", "Blackwood Sinkhole",
        "The jungle floor collapsed in a night, taking trees and all. The trunks lie at angles and the vines have already moved in.",
        match={"regions": ["cyrodiil/blackwood"]}, weight=2.0,
        shapes={"karst": 3, "shaft": 2, "root_cavern": 2, "bowl_bridges": 1},
        floor=["moss_block@18", "mud@10", "rooted_dirt@8", "rock@40"], patch=["moss_block"], patch_at=0.2,
        wall=["moss_block@24", "mud@12", "jungle_log@8", "rooted_dirt@14", "rock@36"], spike=["dripstone_block@1", "rock@2"],
        platform=["mossy_stone_bricks@3", "stone_bricks@1"], pillar=["jungle_log@4", "mangrove_roots@1"], fluid="water",
        flora=[hang("vine", None, 60, (3, 9)), plant(["fern", "large_fern", "azalea"], 80), plant("moss_carpet", 40), floatp("lily_pad", 40),
               tree("jungle_log", "jungle_leaves", None, 6, (9, 14), 3)],
        features=[bedlight("sea_lantern", 0.04)], light=lights(["verdant_froglight", "shroomlight"], 48), color="green",
        ambient=[("minecraft:bat", (2, 4), "air"), ("minecraft:frog", (2, 3), "floor")], loot=fl("cave", "root")),
      P("cyrodiil_colovian_mine", "Colovian Iron Works",
        "Iron and a little silver, the empire's grudging wealth. The timber is sound still; the miners aren't.",
        match={"regions": ["cyrodiil/colovian_highlands", "cyrodiil/gold_coast", "cyrodiil/west_weald", "cyrodiil/jerall_mountains"], "themes": ["mine", "cave"]},
        weight=1.8, shapes={"shaft": 3, "canyon": 3, "bowl_bridges": 1, "floating_islands": 1, "pillar_maze": 1},
        floor=["andesite@14", "cobblestone@10", "gravel@10", "rock@62"], wall=["andesite@24", "iron_ore@8", "gold_ore@4", "coal_ore@6", "cobblestone@12", "rock@40"],
        spike=["andesite@1", "rock@2"], platform=["spruce_planks@2", "stone_bricks@2"], pillar=["spruce_log@3", "andesite@1"],
        fluid={"kind": "water", "chance": 0.5}, flora=[plant(["iron_ore", "coal_ore"], 14), hang("chain", "lantern[hanging=true]", 8, (3, 5))],
        features=[vein(["iron_ore", "gold_ore", "coal_ore"], 5.0, 0.045), ruins("spruce_planks", (3, 6))],
        light=lights("glowstone", 64), foes=["skycraft:bandit"], color="warm", loot=fl("mine", "mine"), setpiece=("camp",)),
      P("cyrodiil_imperial_catacomb", "Imperial Catacomb Hall",
        "The Empire builds even its graves to a standard: plain arches, tidy niches, and the faint smell of lamp oil.",
        match={"provinces": ["cyrodiil"], "themes": ["barrow", "ruin", "fort"], "kits": ["imperial", "none"]}, weight=1.4,
        shapes={"cathedral": 3, "amphitheatre": 2, "collapsed_city": 2, "pillar_maze": 1},
        floor=["stone_bricks@20", "polished_andesite@8", "gravel@6", "rock@50"], wall=["stone_bricks@28", "polished_andesite@10", "andesite@16", "rock@40"],
        spike=["andesite@1", "rock@2"], platform=["polished_andesite@3", "chiseled_stone_bricks@1"], pillar=["polished_andesite@3", "stone_bricks@2"],
        ruin=["stone_bricks@2", "cracked_stone_bricks@1"], fluid={"kind": "water", "chance": 0.35},
        flora=[plant("candle[lit=true]", 16), ceil("cobweb", 14), plant("moss_carpet", 20)],
        features=[statues(["polished_andesite", "stone_bricks"], "lantern", (2, 3)), ruins(["stone_bricks", "cracked_stone_bricks"], (3, 6))],
        light=lights("ochre_froglight", 60, 0, "lantern"), setpiece=("gate", "polished_andesite", "lantern"), foes=["skycraft:draugr"],
        color="warm", loot=fl("barrow", "ancient")),
      )

# ======================================================================================================== elsweyr
group("elsweyr",
      P("elsweyr_salt_cave", "Salt Cave",
        "The sea dried here ten thousand years ago and left its bones: white columns, white floors, and a glare that makes you squint.",
        match={"provinces": ["elsweyr", "hammerfell"], "weather": ["desert", "savanna"]}, weight=1.6,
        shapes={"karst": 3, "bowl_bridges": 2, "canyon": 2, "cathedral": 1},
        floor=["calcite@20", "diorite@10", "white_terracotta@6", "sandstone@8", "rock@40"], wall=["calcite@36", "diorite@14", "quartz_block@8", "white_terracotta@10", "rock@26"],
        spike=["calcite@2", "quartz_block@1"], platform=["smooth_quartz@2", "calcite@2"], fluid={"kind": "pool", "block": ["calcite"], "chance": 0.3},
        flora=[spire(["calcite", "quartz_block"], None, "small_amethyst_bud[facing=up]", 20, (3, 7)), plant("quartz_slab" if False else "calcite", 20),
               column("calcite", None, 20, (1, 3))],
        features=[boulders("calcite", (3, 6), (2, 3)), vein("quartz_block", 5.0, 0.04)],
        light=lights(["sea_lantern", "ochre_froglight"], 56, 1), color="white", loot=fl("cave", "crystal"), foes=["skycraft:sand_serpent" if False else "skycraft:giant_scorpion"]),
      P("elsweyr_moon_sugar_grotto", "Moon-Sugar Grotto",
        "Pale crystals grow like frost on the walls, pink and white in the lamplight. The Khajiit say it hums; the Khajiit would know.",
        match={"provinces": ["elsweyr"], "weather": ["savanna", "tropical", "jungle", "desert"]}, weight=1.8,
        shapes={"karst": 3, "bowl_bridges": 2, "amphitheatre": 1, "floating_islands": 1, "cathedral": 1},
        floor=["white_terracotta@12", "pink_terracotta@10", "calcite@10", "sandstone@8", "rock@40"], wall=["calcite@24", "white_terracotta@16", "pink_terracotta@16", "amethyst_block@6", "rock@28"],
        spike=["calcite@2", "pink_terracotta@1"], platform=["smooth_sandstone@2", "calcite@2"], fluid={"kind": "water", "chance": 0.4},
        flora=[spire(["calcite", "pink_terracotta", "white_terracotta"], "pearlescent_froglight", "amethyst_cluster[facing=up]", 14, (3, 7)),
               plant(["amethyst_cluster[facing=up]", "small_amethyst_bud[facing=up]"], 50)],
        features=[bedlight("sea_lantern", 0.03)], light=lights(["pearlescent_froglight", "sea_lantern"], 44, 3), color="violet",
        foes=["skycraft:alfiq" if False else "skycraft:sabre_cat"], loot=fl("cave", "crystal")),
      P("elsweyr_sandstone_canyon", "Sandstone Canyon",
        "Sun never reaches the bottom, which is why the Khajiit trade there. The walls are banded like a slice of cake.",
        match={"provinces": ["elsweyr", "hammerfell"], "weather": ["desert", "savanna", "tropical"]}, weight=1.6,
        shapes={"canyon": 4, "crevasse": 2, "bowl_bridges": 1, "sea_cave": 1, "floating_islands": 1},
        floor=["sandstone@22", "sand@12", "red_sandstone@8", "rock@30"], wall=["sandstone@30", "red_sandstone@20", "orange_terracotta@8", "smooth_sandstone@14", "rock@16"],
        spike=["sandstone@2", "red_sandstone@1"], platform=["cut_sandstone@3", "chiseled_sandstone@1"], fluid={"kind": "water", "chance": 0.35},
        flora=[plant(["dead_bush", "sand"], 40), column("cut_sandstone", None, 10, (2, 4)), floatp("lily_pad", 20)],
        features=[ruins(["cut_sandstone", "sandstone"], (3, 6)), boulders("sandstone", (3, 5), (2, 3))],
        light=lights(["glowstone", "ochre_froglight"], 52), color="orange", foes=["skycraft:sabre_cat", "skycraft:giant_scorpion"],
        loot=fl("cave", "desert")),
      P("elsweyr_overgrown_temple", "Overgrown Temple",
        "A temple of the cats, swallowed by the jungle's patient green. Sunlight comes in through the roots.",
        match={"regions": ["elsweyr/pellitine", "elsweyr/senchal", "elsweyr/torval", "elsweyr/rimmen"], "themes": ["ruin", "barrow", "cave"]},
        weight=1.7, shapes={"collapsed_city": 3, "root_cavern": 2, "cathedral": 1, "karst": 1},
        floor=["mossy_stone_bricks@14", "stone_bricks@8", "moss_block@12", "rock@40"], wall=["mossy_stone_bricks@22", "moss_block@16", "jungle_log@6", "stone_bricks@12", "rock@30"],
        spike=["mossy_cobblestone@1", "rock@2"], platform=["chiseled_stone_bricks@1", "mossy_stone_bricks@4"], ruin=["mossy_stone_bricks@3", "cracked_stone_bricks@1", "moss_block@1"],
        pillar=["mossy_stone_bricks@3", "jungle_log@1"], fluid={"kind": "water", "chance": 0.6},
        flora=[hang("vine", None, 60, (3, 9)), plant(["fern", "large_fern"], 60), plant("moss_carpet", 50), floatp("lily_pad", 30)],
        features=[statues("mossy_stone_bricks", "lantern", (2, 3)), ruins("mossy_stone_bricks", (5, 8))],
        light=lights(["verdant_froglight", "ochre_froglight"], 54), color="green", foes=["skycraft:sabre_cat"],
        setpiece=("statue", "mossy_stone_bricks", "lantern"), loot=fl("ruin", "ancient")),
      )

# ======================================================================================================== valenwood, summerset
group("valenwood_summerset",
      P("valenwood_root_cathedral", "Root Cathedral",
        "The Bosmer do not build; they ask. The roots here arch in ribs and aisles, and every pillar is still alive.",
        match={"provinces": ["valenwood"]}, weight=2.0,
        shapes={"cathedral": 4, "root_cavern": 3, "karst": 1, "bowl_bridges": 1},
        floor=["moss_block@18", "rooted_dirt@10", "mud@6", "rock@40"], patch=["moss_block"], patch_at=0.2,
        wall=["mangrove_log@12", "moss_block@18", "rooted_dirt@12", "mangrove_roots@10", "rock@38"], spike=["mangrove_roots@2", "dripstone_block@1"],
        platform=["mossy_stone_bricks@2", "mangrove_planks@3"], pillar=["mangrove_log@4", "mangrove_roots@2", "jungle_log@1"],
        fluid={"kind": "water", "chance": 0.5},
        flora=[hang("hanging_roots", None, 50, (3, 9)), hang("vine", None, 30, (3, 8)), plant(["fern", "large_fern", "flowering_azalea"], 70), plant("moss_carpet", 50),
               ceil("glow_lichen[down=true]", 40)],
        features=[ribs(["mangrove_log", "mangrove_roots"], 6), vein("mangrove_roots", 5.0, 0.035)],
        light=lights(["verdant_froglight", "shroomlight"], 44, 1), color="green", setpiece=("tree", "mangrove_log", "flowering_azalea_leaves"),
        foes=["skycraft:spriggan", "skycraft:wispmother"], loot=fl("cave", "root"), ambient=[("minecraft:bat", (1, 3), "air")]),
      P("valenwood_spore_grotto", "Spore Grotto",
        "Pollen hangs in clouds and the blossoms nod in a wind that doesn't exist. Do not sleep here.",
        match={"provinces": ["valenwood", "elsweyr"], "weather": ["jungle", "tropical", "temperate"]}, weight=1.6,
        shapes={"karst": 3, "bowl_bridges": 2, "root_cavern": 2, "pillar_maze": 1},
        floor=["moss_block@20", "rooted_dirt@8", "podzol@6", "rock@40"], patch=["moss_block"], patch_at=0.2,
        wall=["moss_block@24", "rooted_dirt@12", "dripstone_block@10", "azalea_leaves@6", "rock@40"], spike=["dripstone_block@1", "rock@2"],
        platform=["mossy_stone_bricks@3", "stone_bricks@1"], fluid="water",
        flora=[ceil("spore_blossom", 14), plant(["azalea", "flowering_azalea", "fern"], 70), mush("mushroom_stem", ["red_mushroom_block"], "shroomlight", 12, 2),
               plant("moss_carpet", 60), hang("cave_vines_plant[berries=true]", "cave_vines[berries=true]", 24, (3, 7)), floatp("lily_pad", 40)],
        features=[bedlight("sea_lantern", 0.04)], light=lights(["verdant_froglight", "shroomlight"], 44, 2), color="green",
        particle="spore", loot=fl("cave", "fungal"), ambient=[("minecraft:axolotl", (2, 4), "water"), ("minecraft:bat", (1, 3), "air")]),
      P("summerset_crystal_sea_cave", "Crystal Sea Cave",
        "The sea comes in on a clear tide, through a mouth of white rock. The water is lit from below and nothing is ever dark.",
        match={"regions": ["summerset/summerset_isle", "summerset/auridon", "summerset/artaeum"]}, weight=1.9,
        shapes={"sea_cave": 4, "floating_islands": 2, "bowl_bridges": 1, "karst": 1},
        floor=["quartz_block@6", "calcite@12", "sand@10", "rock@50"], wall=["calcite@28", "quartz_block@12", "prismarine@10", "amethyst_block@6", "rock@30"],
        spike=["calcite@2", "prismarine@1", "quartz_block@1"], platform=["smooth_quartz@2", "quartz_bricks@2", "prismarine_bricks@1"], terrace=["sand@3", "calcite@1"],
        fluid="water", flora=[spire("prismarine", "sea_lantern", "light_blue_stained_glass", 8, (3, 7)), bed(["seagrass", "sea_pickle[pickles=3]"], 80),
                              plant("amethyst_cluster[facing=up]", 20)],
        features=[bedlight("sea_lantern", 0.08), pool("water", (2, 4), (2, 3), "sea_lantern", "calcite"), bubbles(0.04)],
        light=lights(["sea_lantern", "pearlescent_froglight"], 44, 2), color="cyan",
        ambient=[("minecraft:glow_squid", (3, 6), "water"), ("minecraft:tropical_fish", (4, 8), "water"), ("minecraft:dolphin", (1, 1), "water")],
        foes=["minecraft:drowned"], loot=fl("cave", "sea")),
      P("summerset_quartz_cavern", "Quartz Cavern",
        "Altmer masons took the quartz out in great clean sheets and left the hollows to be polished by the damp.",
        match={"provinces": ["summerset"], "themes": ["cave", "mine", "ruin", "barrow", "fort"]}, weight=1.7,
        shapes={"cathedral": 3, "amphitheatre": 3, "karst": 1, "bowl_bridges": 1, "collapsed_city": 1},
        floor=["smooth_quartz@14", "quartz_block@8", "calcite@8", "rock@40"], wall=["quartz_block@24", "calcite@20", "smooth_quartz@10", "gold_block@2", "rock@30"],
        spike=["quartz_block@2", "calcite@1"], platform=["smooth_quartz@3", "chiseled_quartz_block@1", "gold_block@1"],
        pillar=["quartz_pillar@4", "chiseled_quartz_block@1"], ruin=["quartz_bricks@2", "smooth_quartz@2"], fluid={"kind": "water", "chance": 0.4},
        flora=[plant(["amethyst_cluster[facing=up]", "calcite"], 20), spire("quartz_block", "sea_lantern", "amethyst_cluster[facing=up]", 8, (4, 8))],
        features=[statues(["smooth_quartz", "quartz_bricks"], "sea_lantern", (2, 3))],
        light=lights(["sea_lantern", "glowstone"], 44, 0, "lantern"), setpiece=("statue", "smooth_quartz", "sea_lantern"),
        color="white", foes=["minecraft:skeleton", "skycraft:wisp"], loot=fl("ruin", "arcane")),
      P("summerset_psijic_time_cave", "Time Cave",
        "A hollow the Psijics keep out of step with the rest of the day: stones hang in the air above their own reflections.",
        match={"regions": ["summerset/artaeum", "summerset/summerset_isle"]}, weight=1.6,
        shapes={"floating_islands": 4, "shaft": 2, "cathedral": 1, "amphitheatre": 1},
        floor=["end_stone@12", "calcite@10", "smooth_quartz@6", "rock@40"], wall=["end_stone@18", "calcite@16", "purpur_block@6", "amethyst_block@10", "obsidian@4", "rock@30"],
        spike=["end_stone@2", "amethyst_block@1"], platform=["purpur_pillar@2", "end_stone_bricks@2", "amethyst_block@1"],
        pillar=["purpur_pillar@3", "end_stone_bricks@1"], fluid={"kind": "water", "chance": 0.5},
        flora=[spire("amethyst_block", "end_rod", "amethyst_cluster[facing=up]", 12), plant(["amethyst_cluster[facing=up]", "end_rod"], 40),
               hang("chain", "lantern[hanging=true]", 4, (3, 6))],
        features=[bedlight("sea_lantern", 0.05), pool("water", (1, 2), (2, 2), "sea_lantern")],
        light=lights(["pearlescent_froglight", "end_rod"], 40, 2), color="violet", particle="portal",
        setpiece=("obelisk", "purpur_pillar", "end_rod"), foes=["minecraft:stray", "skycraft:wisp"], loot=fl("cave", "arcane")),
      )

# ======================================================================================================== high rock, hammerfell
group("highrock_hammerfell",
      P("highrock_breton_barrow_cave", "Breton Barrow-Cave",
        "A hill-fort's barrow dug into a cave: low roofs, mossy lintels, and the peat smell of a great many winters.",
        match={"provinces": ["high_rock"], "themes": ["barrow", "cave", "ruin", "fort"]}, weight=1.7,
        shapes={"amphitheatre": 2, "cathedral": 2, "collapsed_city": 2, "bowl_bridges": 2, "pillar_maze": 1},
        floor=["mossy_cobblestone@10", "cobblestone@10", "moss_block@12", "rock@50"], patch=["moss_block"], patch_at=0.3,
        wall=["mossy_cobblestone@22", "cobblestone@16", "moss_block@16", "stone_bricks@10", "rock@34"], spike=["cobblestone@1", "rock@2"],
        platform=["mossy_stone_bricks@3", "stone_bricks@2"], pillar=["mossy_stone_bricks@3", "stone_bricks@1", "cobblestone@1"],
        ruin=["mossy_cobblestone@3", "cobblestone@2", "mossy_stone_bricks@2"], fluid={"kind": "water", "chance": 0.5},
        flora=[plant(["fern", "moss_carpet"], 70), ceil("cobweb", 14), ceil("glow_lichen[down=true]", 40), plant("candle[lit=true]", 8)],
        features=[ruins(["mossy_cobblestone", "cobblestone"], (4, 8)), statues("mossy_stone_bricks", "lantern", (1, 2))],
        light=lights(["shroomlight", "ochre_froglight"], 62, 0, "lantern"), setpiece=("altar", "mossy_stone_bricks", "lantern"),
        foes=["skycraft:draugr"], color="warm", loot=fl("barrow", "ancient")),
      P("highrock_moor_sinkhole", "Moor Sinkhole",
        "The moor swallowed a farmstead once, roof and all. Rain still falls into the hole and nothing climbs out.",
        match={"regions": ["high_rock/glenumbra", "high_rock/stormhaven", "high_rock/bjoulsae", "high_rock/wayrest", "high_rock/rivenspire"]}, weight=1.9,
        shapes={"karst": 3, "shaft": 3, "bowl_bridges": 1, "canyon": 1},
        floor=["moss_block@16", "coarse_dirt@8", "gravel@8", "rock@50"], patch=["moss_block"], patch_at=0.25,
        wall=["moss_block@20", "stone@22", "mossy_cobblestone@12", "rooted_dirt@10", "rock@34"], spike=["dripstone_block@1", "rock@2"],
        platform=["mossy_stone_bricks@3", "stone_bricks@2"], fluid="water",
        flora=[plant(["fern", "grass", "brown_mushroom"], 90), hang("hanging_roots", None, 20, (2, 6)), floatp("lily_pad", 30), bed("seagrass", 40)],
        features=[bedlight("sea_lantern", 0.04), boulders("mossy_cobblestone", (3, 5), (2, 3))],
        light=lights(["ochre_froglight", "shroomlight"], 54), color="warm", particle="mist", loot=fl("cave", "root"),
        ambient=[("minecraft:bat", (1, 3), "air")]),
      P("highrock_orc_forge_mine", "Orcish Forge-Mine",
        "Orichalcum and the roar of bellows. The Orcs dug until they hit fire, and then built a forge on it.",
        match={"regions": ["high_rock/wrothgar", "high_rock/rivenspire"], "provinces": ["high_rock"]}, weight=1.9,
        shapes={"lava_river": 3, "shaft": 2, "canyon": 2, "bowl_bridges": 2, "amphitheatre": 1},
        floor=["blackstone@16", "andesite@12", "cobblestone@8", "rock@50"], wall=["blackstone@22", "andesite@18", "iron_ore@8", "gold_ore@4", "magma_block@6", "rock@42"],
        spike=["blackstone@2", "andesite@1"], platform=["polished_blackstone_bricks@3", "stone_bricks@2"], pillar=["dark_oak_log@3", "blackstone@1"],
        fluid="lava", flora=[plant(["iron_ore", "anvil"], 6), hang("chain", "lantern[hanging=true]", 8, (3, 6))],
        features=[vein(["iron_ore", "gold_ore"], 5.0, 0.04), smoke((2, 4), "blackstone"), ruins("dark_oak_planks", (3, 6))],
        light=lights(["glowstone", "shroomlight"], 52, 2), foes=["skycraft:bandit"], elite="Forge-Warden", color="red", loot=fl("mine", "mine"),
        setpiece=("obelisk", "blackstone", "lantern")),
      P("hammerfell_red_sandstone_canyon", "Red Sandstone Canyon",
        "Wind and a flash-flood carved this, and the dry heat has kept every edge sharp. The walls are blood-red at dawn.",
        match={"provinces": ["hammerfell"], "weather": ["desert", "temperate", "tropical"]}, weight=1.8,
        shapes={"canyon": 4, "crevasse": 2, "bowl_bridges": 1, "floating_islands": 1, "sea_cave": 1},
        floor=["red_sand@20", "red_sandstone@16", "sand@8", "rock@30"], wall=["red_sandstone@34", "cut_red_sandstone@10", "orange_terracotta@10", "red_terracotta@6", "rock@16"],
        spike=["red_sandstone@2", "cut_red_sandstone@1"], platform=["cut_red_sandstone@3", "chiseled_red_sandstone@1"], fluid={"kind": "water", "chance": 0.3},
        flora=[plant(["dead_bush"], 40), column("cut_red_sandstone", None, 12, (2, 4)), floatp("lily_pad", 20)],
        features=[ruins(["cut_red_sandstone", "red_sandstone"], (3, 6)), boulders("red_sandstone", (3, 5), (2, 3))],
        light=lights(["glowstone", "shroomlight"], 52), color="orange", foes=["skycraft:giant_scorpion", "skycraft:bandit"], loot=fl("cave", "desert")),
      P("hammerfell_desert_catacomb", "Desert Catacomb",
        "Sand drifts in the corridors and has buried half the dead. What the sand didn't take, the salt did.",
        match={"provinces": ["hammerfell"], "themes": ["barrow", "ruin", "fort", "cave"], "kits": ["redguard", "none"]}, weight=1.6,
        shapes={"collapsed_city": 3, "cathedral": 2, "pillar_maze": 2, "amphitheatre": 1},
        floor=["sandstone@16", "cut_sandstone@10", "sand@14", "rock@34"], wall=["sandstone@28", "cut_sandstone@16", "smooth_sandstone@12", "rock@24"],
        spike=["sandstone@2", "cut_sandstone@1"], platform=["cut_sandstone@3", "chiseled_sandstone@1"], pillar=["cut_sandstone@3", "sandstone@2"],
        ruin=["cut_sandstone@3", "sandstone@2", "smooth_sandstone@1"], fluid={"kind": "water", "chance": 0.25},
        flora=[plant(["dead_bush", "bone_block", "skeleton_skull"], 24), ceil("cobweb", 12)],
        features=[statues(["cut_sandstone", "sandstone"], "lantern", (2, 3)), ruins(["cut_sandstone", "sandstone"], (5, 8))],
        light=lights(["glowstone", "ochre_froglight"], 62, 0, "lantern"), setpiece=("statue", "cut_sandstone", "lantern"),
        foes=["skycraft:draugr", "minecraft:husk"], color="warm", loot=fl("barrow", "desert")),
      P("hammerfell_oasis_well", "Oasis Well Cavern",
        "A well the width of a house; the walls are green to the waterline, and the coolness is a gift after the desert above.",
        match={"provinces": ["hammerfell", "elsweyr"]}, weight=1.3,
        shapes={"shaft": 4, "karst": 2, "sea_cave": 1, "bowl_bridges": 1},
        floor=["sand@12", "sandstone@12", "moss_block@10", "rock@40"], wall=["sandstone@26", "moss_block@14", "smooth_sandstone@10", "cut_sandstone@8", "rock@30"],
        spike=["sandstone@2", "dripstone_block@1"], platform=["cut_sandstone@3", "mossy_stone_bricks@1"], fluid="water",
        flora=[hang("vine", None, 40, (3, 8)), plant(["fern", "grass", "dead_bush"], 50), bed("seagrass", 60), floatp("lily_pad", 40),
               tree("jungle_log", "jungle_leaves", None, 5, (8, 12), 3)],
        features=[bedlight("sea_lantern", 0.04)], light=lights(["ochre_froglight", "glowstone"], 54), color="green",
        loot=fl("cave", "root"), ambient=[("minecraft:tropical_fish", (3, 6), "water"), ("minecraft:bat", (1, 3), "air")]),
      )

# ======================================================================================================== black marsh, foreign
group("blackmarsh_foreign",
      P("blackmarsh_hist_root_cave", "Hist Root Cave",
        "The Hist send roots down through the marsh into the dark, and the roots weep a sap that glows. The Argonians come to be near it.",
        match={"provinces": ["black_marsh"]}, weight=2.0,
        shapes={"root_cavern": 4, "cathedral": 2, "karst": 1, "pillar_maze": 1, "bowl_bridges": 1},
        floor=["mud@16", "muddy_mangrove_roots@6", "moss_block@10", "rock@40"], patch=["moss_block"], patch_at=0.3,
        wall=["mangrove_log@10", "mud@16", "mangrove_roots@12", "moss_block@12", "honeycomb_block@2", "rock@30"], spike=["mangrove_roots@2", "mud@1"],
        platform=["mud_bricks@3", "mossy_stone_bricks@1"], pillar=["mangrove_log@4", "mangrove_roots@2"], fluid="water",
        flora=[hang("hanging_roots", None, 50, (3, 9)), plant(["fern", "brown_mushroom"], 60), floatp("lily_pad", 40), bed("seagrass", 50),
               plant("moss_carpet", 40)],
        features=[vein(["shroomlight", "honeycomb_block"], 5.0, 0.04), bedlight("sea_lantern", 0.04), ribs(["mangrove_log", "mangrove_roots"], 6)],
        light=lights(["shroomlight", "ochre_froglight"], 40, 3), color="amber", particle="spore", setpiece=("tree", "mangrove_log", "mangrove_leaves"),
        foes=["skycraft:wisp", "skycraft:spriggan"], ambient=[("minecraft:glow_squid", (3, 5), "water"), ("minecraft:frog", (2, 4), "floor")],
        loot=fl("cave", "sap")),
      P("blackmarsh_bog_cavern", "Bog Cavern",
        "A drowned room: black water to the knee, the stumps of pillars, and a stillness that has learned to wait.",
        match={"provinces": ["black_marsh"], "weather": ["swamp", "jungle"]}, weight=1.7,
        shapes={"pillar_maze": 4, "root_cavern": 2, "karst": 1, "collapsed_city": 1},
        floor=["mud@24", "clay@8", "muddy_mangrove_roots@4", "rock@30"], wall=["mud@26", "mangrove_roots@12", "moss_block@16", "clay@8", "rock@30"],
        spike=["mud@1", "mangrove_roots@1", "rock@2"], platform=["mud_bricks@3", "stone_bricks@1"], pillar=["mangrove_log@3", "mud_bricks@1"],
        ruin=["mud_bricks@3", "mossy_stone_bricks@1"], fluid="water",
        flora=[hang("hanging_roots", None, 30, (2, 7)), plant(["brown_mushroom", "fern"], 40), floatp("lily_pad", 60), bed("seagrass", 60)],
        features=[ruins("mud_bricks", (3, 6)), bubbles(0.04), bedlight("sea_lantern", 0.03)],
        light=lights(["verdant_froglight", "shroomlight"], 56), color="green", particle="mist",
        foes=["skycraft:mudcrab", "skycraft:slaughterfish", "skycraft:wisp"], ambient=[("minecraft:frog", (2, 4), "floor")], loot=fl("cave", "root")),
      P("blackmarsh_sap_vault", "Sap Vault",
        "The Hist's amber blood pools in the old temple basements, thick as honey and just as slow. It remembers what it was fed.",
        match={"provinces": ["black_marsh"], "themes": ["ruin", "barrow", "fort", "cave"], "kits": ["xanmeer", "none"]}, weight=1.6,
        shapes={"amphitheatre": 3, "cathedral": 2, "collapsed_city": 2, "bowl_bridges": 1},
        floor=["mud_bricks@14", "mossy_stone_bricks@8", "mud@10", "rock@40"], wall=["mud_bricks@20", "mossy_stone_bricks@12", "orange_terracotta@8", "honeycomb_block@6", "rock@38"],
        spike=["mud_bricks@1", "rock@2"], platform=["mud_bricks@3", "chiseled_stone_bricks@1"], pillar=["mud_bricks@3", "orange_terracotta@1"],
        ruin=["mud_bricks@3", "orange_terracotta@1"], fluid={"kind": "pool", "block": ["honey_block"], "chance": 1.0},
        flora=[plant(["orange_terracotta"], 10), hang("hanging_roots", None, 20, (2, 6)), plant("moss_carpet", 30)],
        features=[vein(["honeycomb_block", "orange_terracotta"], 5.0, 0.045), statues("mud_bricks", "shroomlight", (1, 2))],
        light=lights(["shroomlight", "ochre_froglight"], 44, 2), color="amber", setpiece=("altar", "mud_bricks", "shroomlight"),
        foes=["skycraft:wisp"], loot=fl("ruin", "sap")),
      P("atmora_glacier_hall", "Glacier Hall",
        "Atmora's ice is older than any language. The Ancient Nords carved halls here and left them to the cold, and the cold kept them.",
        match={"provinces": ["atmora", "akavir"], "weather": ["frozen"]}, weight=2.0,
        shapes={"crevasse": 3, "amphitheatre": 2, "cathedral": 2, "canyon": 2, "bowl_bridges": 1},
        floor=["packed_ice@20", "blue_ice@10", "snow_block@12", "rock@58"], wall=["packed_ice@36", "blue_ice@34", "snow_block@10", "sea_lantern@4", "rock@16"],
        spike=["blue_ice@2", "packed_ice@1"], platform=["polished_diorite@2", "blue_ice@2", "stone_bricks@1"], pillar=["blue_ice@3", "packed_ice@2"],
        fluid={"kind": "ice"}, flora=[spire("blue_ice", "sea_lantern", "packed_ice", 14, (5, 10)), plant("snow", 110)],
        features=[statues(["blue_ice", "packed_ice"], "sea_lantern", (2, 3)), pool("powder_snow", (1, 2), (2, 3)), bedlight("sea_lantern", 0.05)],
        light=lights(["sea_lantern", "blue_ice"], 46, 2), color="blue", setpiece=("statue", "blue_ice", "sea_lantern"),
        foes=["skycraft:ice_wraith", "skycraft:frost_atronach", "skycraft:frost_giant"], elite="Frost Warden", loot=fl("barrow", "frost")),
      P("akavir_serpent_hall", "Serpent Hall",
        "Lacquer-red pillars and scale-patterned floors: the snake-folk of Akavir built their halls to be coiled through.",
        match={"regions": ["akavir/tsaesci", "akavir/tang_mo", "akavir/ka_po_tun"], "themes": ["ruin", "barrow", "fort", "cave"]}, weight=2.0,
        shapes={"cathedral": 3, "collapsed_city": 2, "amphitheatre": 2, "pillar_maze": 1, "bone_cavern": 1},
        floor=["red_nether_bricks@10", "polished_blackstone_bricks@8", "stone_bricks@6", "rock@40"], wall=["red_nether_bricks@16", "polished_blackstone@14", "blackstone@16", "rock@40"],
        spike=["blackstone@2", "red_nether_bricks@1"], platform=["red_nether_bricks@2", "gilded_blackstone@1", "polished_blackstone_bricks@2"],
        pillar=["red_nether_bricks@3", "crimson_stem@2"], ruin=["red_nether_bricks@2", "cracked_nether_bricks@1"], fluid={"kind": "water", "chance": 0.3},
        flora=[plant("candle[lit=true]", 14), ceil("hanging_roots", 16), plant("bone_block", 8)],
        features=[statues(["red_nether_bricks", "polished_blackstone_bricks"], "lantern", (2, 3)), ribs(["crimson_stem", "red_nether_bricks"], 6)],
        light=lights(["shroomlight", "glowstone"], 54, 0, "lantern"), setpiece=("statue", "red_nether_bricks", "lantern"),
        foes=["skycraft:tsaesci", "skycraft:draugr"], elite="Serpent Guard", color="red", loot=fl("ruin", "ancient")),
      P("akavir_bamboo_grotto", "Bamboo Grotto",
        "Bamboo grows in the dark under a skylight no one has ever found, and the sound of it is like quiet rain.",
        match={"regions": ["akavir/tang_mo", "akavir/ka_po_tun", "akavir/tsaesci", "valenwood/malabal_tor"]}, weight=1.6,
        shapes={"karst": 3, "root_cavern": 2, "bowl_bridges": 2, "shaft": 1},
        floor=["moss_block@16", "podzol@10", "rooted_dirt@8", "rock@40"], patch=["moss_block"], patch_at=0.25,
        wall=["moss_block@20", "mud@10", "rooted_dirt@12", "bamboo_block@6", "rock@42"], spike=["dripstone_block@1", "rock@2"],
        platform=["bamboo_planks@2", "stone_bricks@2"], pillar=["bamboo_block@4", "dripstone_block@1"], fluid={"kind": "water", "chance": 0.7},
        flora=[column("bamboo[age=1]", None, 60, (3, 6)), plant(["fern", "grass"], 50), plant("moss_carpet", 40), floatp("lily_pad", 30)],
        features=[bedlight("sea_lantern", 0.04)], light=lights(["verdant_froglight", "shroomlight"], 48), color="green",
        loot=fl("cave", "root"), ambient=[("minecraft:bat", (1, 3), "air"), ("minecraft:frog", (1, 3), "floor")]),
      P("yokuda_coral_cave", "Coral Cave",
        "The Sunken Isles never quite stopped being sea. Coral crusts the ceiling, and the fish go about their business above your head.",
        match={"regions": ["yokuda/sunken_isles", "thras/coral_kingdom", "pyandonea/maormer_isles", "yokuda/yokudan_desert"]}, weight=2.0,
        shapes={"sea_cave": 3, "floating_islands": 2, "pillar_maze": 2, "bowl_bridges": 1, "karst": 1},
        floor=["sand@16", "gravel@8", "tube_coral_block@4", "brain_coral_block@3", "rock@40"], wall=["tube_coral_block@10", "brain_coral_block@8", "bubble_coral_block@6", "horn_coral_block@6", "prismarine@8", "rock@40"],
        spike=["tube_coral_block@1", "brain_coral_block@1", "prismarine@1"], platform=["prismarine_bricks@3", "dark_prismarine@1"],
        pillar=["tube_coral_block@2", "brain_coral_block@2", "prismarine@2"], terrace=["sand@3", "gravel@1"], fluid="water",
        flora=[bed(["tube_coral", "brain_coral", "bubble_coral", "fire_coral", "horn_coral", "tube_coral_fan", "brain_coral_fan", "sea_pickle[pickles=4]", "seagrass", "kelp"], 120)],
        features=[bedlight("sea_lantern", 0.08), bubbles(0.04), boulders(["tube_coral_block", "brain_coral_block"], (2, 4), (1, 2))],
        light=lights(["sea_lantern", "pearlescent_froglight"], 40, 2), color="cyan",
        ambient=[("minecraft:tropical_fish", (6, 10), "water"), ("minecraft:glow_squid", (3, 5), "water"), ("minecraft:dolphin", (1, 2), "water")],
        foes=["minecraft:drowned", "skycraft:land_dreugh" if False else "skycraft:slaughterfish"], loot=fl("cave", "sea")),
      P("yokuda_sunken_vault", "Sunken Vault",
        "The Yokudans left in ships and the sea took the rest. Their sand-coloured vaults lie under it, level on level, and still full.",
        match={"regions": ["yokuda/yokudan_desert", "yokuda/sunken_isles", "pyandonea/maormer_isles"], "themes": ["ruin", "barrow", "fort", "cave"]}, weight=1.9,
        shapes={"collapsed_city": 3, "cathedral": 2, "amphitheatre": 2, "pillar_maze": 1},
        floor=["cut_sandstone@12", "sand@12", "sandstone@8", "rock@38"], wall=["sandstone@22", "cut_sandstone@16", "prismarine@8", "smooth_sandstone@10", "rock@30"],
        spike=["sandstone@2", "prismarine@1"], platform=["cut_sandstone@3", "chiseled_sandstone@1", "gold_block@1"], pillar=["cut_sandstone@3", "prismarine@1"],
        ruin=["cut_sandstone@3", "sandstone@2", "prismarine@1"], fluid="water",
        flora=[bed(["seagrass", "tube_coral", "sea_pickle[pickles=3]"], 60), plant("dead_bush", 20)],
        features=[statues(["cut_sandstone", "sandstone"], "sea_lantern", (2, 3)), ruins(["cut_sandstone", "prismarine_bricks"], (4, 7)), bedlight("sea_lantern", 0.05)],
        light=lights(["sea_lantern", "ochre_froglight"], 48), color="cyan", setpiece=("statue", "cut_sandstone", "sea_lantern"),
        foes=["minecraft:drowned", "skycraft:draugr"], loot=fl("ruin", "sea")),
      )

# ======================================================================================================== shapes and loot
SHAPES = {
    "bowl_bridges": {"weight": 1.0, "minHeight": 15},
    "canyon": {"weight": 1.0, "minHeight": 16, "long": True, "params": {"aspect": [1.5, 1.7], "meander": [0, 4]}},
    "shaft": {"weight": 0.9, "minHeight": 18, "tierBias": 0.15},
    "amphitheatre": {"weight": 1.0, "minHeight": 17, "tierBias": 0.05},
    "pillar_maze": {"weight": 1.0, "minHeight": 15},
    "lava_river": {"weight": 1.0, "minHeight": 16, "long": True, "tierBias": 0.1, "params": {"width": [3.2, 4.4], "meander": [1, 3]}},
    "root_cavern": {"weight": 1.0, "minHeight": 17},
    "lava_tube": {"weight": 0.9, "minHeight": 15, "long": True, "params": {"aspect": [1.55, 1.7], "meander": [0, 3]}},
    "honeycomb": {"weight": 0.9, "minHeight": 16, "params": {"cell": [5.5, 7.0]}},
    "collapsed_city": {"weight": 1.0, "minHeight": 16},
    "crevasse": {"weight": 0.9, "minHeight": 19, "long": True, "tierBias": 0.1, "params": {"aspect": [1.6, 1.7], "meander": [3, 6]}},
    "cathedral": {"weight": 1.0, "minHeight": 20, "long": True, "params": {"pitch": [6, 7]}},
    "karst": {"weight": 1.0, "minHeight": 18},
    "sea_cave": {"weight": 1.0, "minHeight": 16, "long": True},
    "floating_islands": {"weight": 0.9, "minHeight": 19, "tierBias": 0.1},
    "bone_cavern": {"weight": 0.9, "minHeight": 18, "long": True},
}


def loot_table(entries, extra_rolls=(1, 2)):
    """A chest table: gold, then {extra_rolls} rolls over the weighted entries (a name 'skycraft:chests/x' is a nested table)."""
    pools = [{"rolls": 1, "entries": [{"type": "minecraft:loot_table", "name": "skycraft:chests/gold", "weight": 1}]}]
    ents = []
    for name, w, cnt in entries:
        if name.startswith("skycraft:chests/"):
            ents.append({"type": "minecraft:loot_table", "name": name, "weight": w})
        else:
            e = {"type": "minecraft:item", "name": name, "weight": w}
            if cnt:
                e["functions"] = [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": cnt[0], "max": cnt[1]}}]
            ents.append(e)
    pools.append({"rolls": {"type": "minecraft:uniform", "min": extra_rolls[0], "max": extra_rolls[1]}, "entries": ents})
    return {"type": "minecraft:chest", "pools": pools}


LOOT_TABLES = {
    "crystal": [("skycraft:chests/soul_gems", 3, None), ("minecraft:amethyst_shard", 3, (2, 6)), ("minecraft:quartz", 2, (2, 5)), ("minecraft:diamond", 1, (1, 1)),
                ("skycraft:chests/spell_tomes", 1, None), ("minecraft:lapis_lazuli", 2, (3, 8))],
    "fungal": [("skycraft:chests/alchemy_ingredients", 4, None), ("minecraft:glow_berries", 2, (3, 8)), ("minecraft:red_mushroom", 2, (2, 5)),
               ("minecraft:brown_mushroom", 2, (2, 5)), ("skycraft:chests/soul_gems", 1, None), ("minecraft:spore_blossom", 1, (1, 2))],
    "frost": [("skycraft:chests/smithing_materials", 3, None), ("skycraft:chests/skyrim_food", 2, None), ("minecraft:packed_ice", 1, (2, 5)),
              ("minecraft:snowball", 2, (4, 12)), ("skycraft:chests/soul_gems", 1, None), ("minecraft:iron_ingot", 2, (2, 5))],
    "ember": [("skycraft:chests/smithing_materials", 4, None), ("minecraft:blaze_powder", 2, (2, 5)), ("minecraft:magma_cream", 1, (1, 3)),
              ("minecraft:gold_ingot", 2, (2, 5)), ("minecraft:obsidian", 1, (1, 3)), ("skycraft:chests/soul_gems", 1, None)],
    "sea": [("skycraft:chests/flotsam", 3, None), ("skycraft:chests/sea_wreck", 2, None), ("minecraft:nautilus_shell", 1, (1, 1)), ("minecraft:prismarine_shard", 3, (2, 6)),
            ("minecraft:prismarine_crystals", 2, (1, 4)), ("skycraft:chests/soul_gems", 1, None), ("minecraft:heart_of_the_sea", 1, (1, 1))],
    "hive": [("skycraft:chests/alchemy_ingredients", 3, None), ("minecraft:honey_bottle", 3, (1, 3)), ("minecraft:honeycomb", 3, (2, 6)),
             ("minecraft:string", 2, (3, 8)), ("minecraft:spider_eye", 2, (2, 5)), ("skycraft:chests/soul_gems", 1, None)],
    "root": [("skycraft:chests/alchemy_ingredients", 4, None), ("skycraft:chests/lore_books", 1, None), ("minecraft:glow_berries", 2, (2, 6)),
             ("minecraft:oak_sapling", 1, (1, 3)), ("minecraft:moss_block", 1, (2, 5)), ("minecraft:azalea", 1, (1, 2))],
    "ancient": [("skycraft:chests/lore_books", 3, None), ("skycraft:chests/spell_tomes", 2, None), ("skycraft:chests/soul_gems", 2, None),
                ("minecraft:gold_ingot", 2, (3, 8)), ("minecraft:emerald", 1, (2, 6)), ("skycraft:chests/smithing_materials", 2, None)],
    "bone": [("minecraft:bone", 4, (3, 9)), ("minecraft:bone_block", 1, (1, 3)), ("skycraft:chests/smithing_materials", 3, None),
             ("skycraft:chests/skyrim_gear", 2, None), ("skycraft:chests/soul_gems", 1, None), ("minecraft:iron_ingot", 2, (2, 5))],
    "mine": [("skycraft:chests/mine_ore", 5, None), ("skycraft:chests/smithing_materials", 3, None), ("minecraft:raw_iron", 2, (2, 6)),
             ("minecraft:raw_gold", 1, (1, 4)), ("minecraft:coal", 2, (4, 10)), ("skycraft:chests/soul_gems", 1, None)],
    "desert": [("skycraft:chests/skyrim_food", 2, None), ("minecraft:gold_ingot", 2, (2, 6)), ("minecraft:emerald", 1, (1, 4)),
               ("skycraft:chests/smithing_materials", 2, None), ("minecraft:sand", 1, (4, 10)), ("skycraft:chests/alchemy_ingredients", 2, None)],
    "arcane": [("skycraft:chests/spell_tomes", 4, None), ("skycraft:chests/soul_gems", 4, None), ("minecraft:amethyst_shard", 2, (2, 6)),
               ("minecraft:ender_pearl", 1, (1, 2)), ("skycraft:chests/lore_books", 2, None), ("minecraft:experience_bottle", 1, (1, 3))],
    "sap": [("skycraft:chests/alchemy_ingredients", 4, None), ("minecraft:honey_bottle", 3, (1, 3)), ("minecraft:slime_ball", 2, (2, 5)),
            ("skycraft:chests/soul_gems", 2, None), ("skycraft:chests/lore_books", 1, None), ("minecraft:honeycomb", 2, (2, 5))],
}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write("\n")


def known_blocks():
    f = os.path.join(os.path.dirname(__file__), "generated", "vanilla_blocks.txt")
    if os.path.exists(f):
        return set(x.strip() for x in open(f, encoding="utf-8") if x.strip())
    return None


def check(profiles, known):
    bad = 0
    ids = set()
    for p in profiles:
        if p["id"] in ids:
            print("DUPLICATE id", p["id"])
            bad += 1
        ids.add(p["id"])
        texts = []
        for k, v in p["palette"].items():
            if isinstance(v, list):
                texts += v
        for sec in ("flora", "features"):
            for e in p[sec]:
                for k in ("block", "cap", "tip", "accent"):
                    if k in e:
                        texts += e[k]
        texts += p["lights"]["roof"] + p["lights"].get("post", [])
        sp = p["setpiece"]
        for k in ("block", "accent"):
            if k in sp:
                texts += sp[k]
        fl_ = p["fluid"]
        if isinstance(fl_, dict):
            texts += fl_.get("block", []) + fl_.get("cap", [])
        for t in texts:
            if t.startswith("rock"):
                continue
            name = t.split("@")[0].split("[")[0]
            short = name.split(":")[-1]
            if known is not None and name.startswith("minecraft:") and short not in known:
                print("UNKNOWN BLOCK", short, "in", p["id"])
                bad += 1
    return bad


def main():
    allp = []
    for name, ps in PROFILES.items():
        for p in ps:
            # shorthand fluid: dict with "block" given bare names
            if isinstance(p["fluid"], dict) and "block" in p["fluid"]:
                p["fluid"]["block"] = b(*p["fluid"]["block"])
            allp.append(p)
    known = known_blocks()
    bad = check(allp, known)
    if "--check" in sys.argv:
        print(len(allp), "profiles,", bad, "problems")
        return 1 if bad else 0
    files = []
    for name, ps in PROFILES.items():
        write(os.path.join(OUT, "profiles", name + ".json"), ps)
        files.append("profiles/" + name + ".json")
    write(os.path.join(OUT, "shapes.json"), SHAPES)
    write(os.path.join(OUT, "index.json"), {"_comment": "Cavern library: shapes + profile files; see tools/worldgen/caverns.py",
                                            "shapes": "shapes.json", "profiles": files})
    for kind, entries in LOOT_TABLES.items():
        t = loot_table(entries)
        t["random_sequence"] = "skycraft:chests/cavern/" + kind
        write(os.path.join(LOOT, kind + ".json"), t)
    print(len(allp), "profiles in", len(PROFILES), "files;", len(LOOT_TABLES), "loot tables;", bad, "problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
