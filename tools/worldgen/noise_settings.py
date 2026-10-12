"""Writes the skycraft:tamriel terrain JSON: noise settings, density functions, noise parameters, the world presets that
use them, the taller overworld dimension type and the skycraft:map_water feature.

Usage: py -3 tools/worldgen/noise_settings.py

The surface rule comes from tools/worldgen/generated/surface_rule.json when it exists (written by biomes.py from the
biome catalogue); otherwise a simple default is used (bedrock floor, deepslate, grass/dirt, sand beaches, stone on
steep slopes, snow high up).

Terrain model (see TamrielHeight.java): `skycraft:tamriel/height` is the surface height of each column (map macro
shape + seeded detail). The terrain density is (height - y) / 28 plus a 3D noise that only matters on mountain ridges
(overhangs, crags); vanilla's caves are mixed in exactly like vanilla's final_density does with sloped_cheese.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
DATA = os.path.join(ROOT, "mod", "src", "main", "resources", "data")

MIN_Y, HEIGHT, SEA = -64, 704, 63
TOP = MIN_Y + HEIGHT - 1  # 639: room for Skyrim-scale mountains (the Throat of the World peaks near y 560)
# The noise (terrain) only fills up to y 575: nothing stands higher, and every block of the column the generator fills
# costs a density interpolation and an aquifer lookup even as air. Above it the world is plain air up to the top.
NOISE_HEIGHT = 640
NOISE_TOP = MIN_Y + NOISE_HEIGHT - 1  # 575
SLOPE = 1.0 / 28.0  # density per block below the surface


def write(rel, obj):
    path = os.path.join(DATA, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def add(a, b):
    return {"type": "minecraft:add", "argument1": a, "argument2": b}


def mul(a, b):
    return {"type": "minecraft:mul", "argument1": a, "argument2": b}


def ygrad(y0, y1, v0, v1):
    return {"type": "minecraft:y_clamped_gradient", "from_y": y0, "to_y": y1, "from_value": v0, "to_value": v1}


def channel(name):
    return {"type": "skycraft:tamriel_channel", "channel": name}


def noise(name, xz=1.0, y=0.0):
    return {"type": "minecraft:noise", "noise": name, "xz_scale": xz, "y_scale": y}


def fades(inner):
    """Vanilla's bottom/top fades around a terrain density (solid at the floor, air at the ceiling)."""
    return add(0.1171875, mul(ygrad(MIN_Y, MIN_Y + 24, 0.0, 1.0),
               add(-0.1171875, add(-0.078125, mul(ygrad(NOISE_TOP - 11, NOISE_TOP, 1.0, 0.0), add(0.078125, inner))))))


# --- noise parameters ---------------------------------------------------------------------------------------------
NOISES = {
    "tamriel_hills": {"firstOctave": -8, "amplitudes": [1.0, 0.6, 0.35, 0.18]},
    "tamriel_detail": {"firstOctave": -6, "amplitudes": [1.0, 0.5, 0.25]},
    "tamriel_ridges": {"firstOctave": -7, "amplitudes": [1.0, 0.45, 0.22, 0.1]},
    "tamriel_dunes": {"firstOctave": -5, "amplitudes": [1.0, 0.3]},
    "tamriel_overhang": {"firstOctave": -5, "amplitudes": [1.0, 0.5, 0.25]},
}

# --- density functions --------------------------------------------------------------------------------------------
DF = {
    "tamriel/height": {"type": "minecraft:cache_2d", "argument": {
        "type": "skycraft:tamriel_height", "hills": "skycraft:tamriel_hills", "detail": "skycraft:tamriel_detail",
        "ridges": "skycraft:tamriel_ridges", "dunes": "skycraft:tamriel_dunes"}},
    "tamriel/overhang": {"type": "minecraft:cache_2d", "argument": channel("overhang")},
    "tamriel/continents": {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:cache_2d", "argument": channel("continentalness")}},
    "tamriel/erosion": {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:cache_2d", "argument": channel("erosion")}},
    "tamriel/ridges": {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:cache_2d", "argument": channel("ridges")}},
    # road corridors and settlement pads (keeps cave mouths from opening under them)
    "tamriel/road": {"type": "minecraft:cache_2d", "argument": channel("road")},
    # blocks below the surface, as density
    "tamriel/below_surface": mul(SLOPE, add("skycraft:tamriel/height", mul(-1.0, "minecraft:y"))),
    # the terrain without caves: the height field plus crags/overhangs on mountain ridges
    "tamriel/sloped": add("skycraft:tamriel/below_surface",
                          mul("skycraft:tamriel/overhang", mul(0.55, noise("skycraft:tamriel_overhang", 1.0, 1.4)))),
    "tamriel/depth": mul(1.0 / 128.0, add("skycraft:tamriel/height", mul(-1.0, "minecraft:y"))),
}

SLOPED = "skycraft:tamriel/sloped"
CAVES = {
    "type": "minecraft:range_choice", "input": SLOPED, "min_inclusive": -1000000.0, "max_exclusive": 1.5625,
    "when_in_range": {"type": "minecraft:min", "argument1": SLOPED,
                      "argument2": mul(5.0, add("minecraft:overworld/caves/entrances",
                                                mul(2.0, "skycraft:tamriel/road")))},
    "when_out_of_range": {"type": "minecraft:max",
        "argument1": {"type": "minecraft:min",
            "argument1": {"type": "minecraft:min",
                "argument1": add(mul(4.0, {"type": "minecraft:square", "argument": noise("minecraft:cave_layer", 1.0, 8.0)}),
                                 add({"type": "minecraft:clamp", "input": add(0.27, noise("minecraft:cave_cheese", 1.0, 0.6666666666666666)), "min": -1.0, "max": 1.0},
                                     {"type": "minecraft:clamp", "input": add(1.5, mul(-0.64, SLOPED)), "min": 0.0, "max": 0.5})),
                "argument2": "minecraft:overworld/caves/entrances"},
            "argument2": add("minecraft:overworld/caves/spaghetti_2d", "minecraft:overworld/caves/spaghetti_roughness_function")},
        "argument2": {"type": "minecraft:range_choice", "input": "minecraft:overworld/caves/pillars",
                      "min_inclusive": -1000000.0, "max_exclusive": 0.03, "when_in_range": -1000000.0,
                      "when_out_of_range": "minecraft:overworld/caves/pillars"}},
}

# Well above the surface (more than ~17 blocks, past any overhang) skip the cave noises entirely: that is most of a
# 704-high column, and it is what getBaseHeight / Distant Horizons scan first.
TERRAIN = {"type": "minecraft:range_choice", "input": "skycraft:tamriel/below_surface",
           "min_inclusive": -1000000.0, "max_exclusive": -0.6, "when_in_range": SLOPED, "when_out_of_range": CAVES}

FINAL = {"type": "minecraft:min",
         "argument1": {"type": "minecraft:squeeze", "argument": mul(0.64, {"type": "minecraft:interpolated", "argument": {
             "type": "minecraft:blend_density", "argument": fades(TERRAIN)}})},
         # noodle caves are not interpolated (evaluated per block): keep them off road beds and pads (P2), where the
         # road plan sets the ground exactly (the road channel includes the planned beds and site pads)
         "argument2": {"type": "minecraft:max", "argument1": "minecraft:overworld/caves/noodle",
                       "argument2": add(-100.0, mul(120.0, "skycraft:tamriel/road"))}}

SHIFTED = lambda n: {"type": "minecraft:shifted_noise", "noise": n, "shift_x": "minecraft:shift_x", "shift_y": 0.0,
                     "shift_z": "minecraft:shift_z", "xz_scale": 0.25, "y_scale": 0.0}

VEIN_TOGGLE = {"type": "minecraft:interpolated", "argument": {
    "type": "minecraft:range_choice", "input": "minecraft:y", "min_inclusive": -60.0, "max_exclusive": 51.0,
    "when_in_range": noise("minecraft:ore_veininess", 1.5, 1.5), "when_out_of_range": 0.0}}


def vein_part(n):
    return {"type": "minecraft:abs", "argument": {"type": "minecraft:interpolated", "argument": {
        "type": "minecraft:range_choice", "input": "minecraft:y", "min_inclusive": -60.0, "max_exclusive": 51.0,
        "when_in_range": noise(n, 4.0, 4.0), "when_out_of_range": 0.0}}}


VEIN_RIDGED = add(-0.07999999821186066, {"type": "minecraft:max", "argument1": vein_part("minecraft:ore_vein_a"),
                                         "argument2": vein_part("minecraft:ore_vein_b")})

ROUTER = {
    "barrier": noise("minecraft:aquifer_barrier", 1.0, 0.5),
    "fluid_level_floodedness": noise("minecraft:aquifer_fluid_level_floodedness", 1.0, 0.67),
    "fluid_level_spread": noise("minecraft:aquifer_fluid_level_spread", 1.0, 0.7142857142857143),
    "lava": noise("minecraft:aquifer_lava", 1.0, 1.0),
    "temperature": SHIFTED("minecraft:temperature"),
    "vegetation": SHIFTED("minecraft:vegetation"),
    "continents": "skycraft:tamriel/continents",
    "erosion": "skycraft:tamriel/erosion",
    "depth": "skycraft:tamriel/depth",
    "ridges": "skycraft:tamriel/ridges",
    # preliminary surface (aquifers, surface rules, structure heights): crosses 0.390625 about 4 blocks below the
    # real surface, like vanilla
    "initial_density_without_jaggedness": fades(add(0.25, "skycraft:tamriel/below_surface")),
    "final_density": FINAL,
    "vein_toggle": VEIN_TOGGLE,
    "vein_ridged": VEIN_RIDGED,
    "vein_gap": noise("minecraft:ore_gap", 1.0, 1.0),
}


# --- surface rule -------------------------------------------------------------------------------------------------
def block(name):
    return {"type": "minecraft:block", "result_state": {"Name": name}}


def cond(c, then):
    return {"type": "minecraft:condition", "if_true": c, "then_run": then}


def seq(*rules):
    return {"type": "minecraft:sequence", "sequence": list(rules)}


def y_above(y, mult=0):
    return {"type": "minecraft:y_above", "anchor": {"absolute": y}, "surface_depth_multiplier": mult, "add_stone_depth": False}


def NOT(c):
    return {"type": "minecraft:not", "invert": c}


def stone_depth(add_surface, offset=0):
    return {"type": "minecraft:stone_depth", "offset": offset, "surface_type": "floor", "add_surface_depth": add_surface,
            "secondary_depth_range": 0}


WATER_ABOVE = {"type": "minecraft:water", "offset": -1, "surface_depth_multiplier": 0, "add_stone_depth": False}
STEEP = {"type": "minecraft:steep"}

DEFAULT_SURFACE = seq(
    cond({"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor",
          "true_at_and_below": {"above_bottom": 0}, "false_at_and_above": {"above_bottom": 5}}, block("minecraft:bedrock")),
    cond({"type": "minecraft:above_preliminary_surface"}, seq(
        cond(stone_depth(False), seq(
            cond(y_above(215, 1), block("minecraft:snow_block")),
            cond(STEEP, block("minecraft:stone")),
            cond(NOT(WATER_ABOVE), seq(cond(y_above(50), block("minecraft:sand")), block("minecraft:gravel"))),
            cond(NOT(y_above(SEA + 2)), block("minecraft:sand")),
            block("minecraft:grass_block"))),
        cond(stone_depth(True), seq(
            cond(y_above(215, 1), block("minecraft:stone")),
            cond(STEEP, block("minecraft:stone")),
            cond(NOT(y_above(SEA + 2)), block("minecraft:sand")),
            block("minecraft:dirt"))))),
    cond({"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate",
          "true_at_and_below": {"absolute": 0}, "false_at_and_above": {"absolute": 8}},
         {"type": "minecraft:block", "result_state": {"Name": "minecraft:deepslate", "Properties": {"axis": "y"}}}),
)


def surface_rule():
    gen = os.path.join(HERE, "generated", "surface_rule.json")
    if os.path.exists(gen):
        print("using", gen)
        return json.load(open(gen, encoding="utf-8"))
    print("using the default surface rule (tools/worldgen/generated/surface_rule.json not found)")
    return DEFAULT_SURFACE


def main():
    for name, params in NOISES.items():
        write(f"skycraft/worldgen/noise/{name}.json", params)
    for name, df in DF.items():
        write(f"skycraft/worldgen/density_function/{name}.json", df)
    settings = {
        "sea_level": SEA,
        "disable_mob_generation": False,
        "aquifers_enabled": True,
        "ore_veins_enabled": True,
        "legacy_random_source": False,
        "default_block": {"Name": "minecraft:stone"},
        "default_fluid": {"Name": "minecraft:water", "Properties": {"level": "0"}},
        "noise": {"min_y": MIN_Y, "height": NOISE_HEIGHT, "size_horizontal": 1, "size_vertical": 2},
        "noise_router": ROUTER,
        "spawn_target": [{"temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0], "continentalness": [-0.11, 1.0],
                          "erosion": [-1.0, 1.0], "weirdness": [-1.0, 1.0], "depth": 0.0, "offset": 0.0}],
        "surface_rule": {"type": "skycraft:fast_surface", "rule": surface_rule()},
    }
    write("skycraft/worldgen/noise_settings/tamriel.json", settings)

    overworld = {"type": "minecraft:overworld", "generator": {
        "type": "minecraft:noise", "settings": "skycraft:tamriel", "biome_source": {"type": "skycraft:tamriel"}}}
    nether = {"type": "minecraft:the_nether", "generator": {
        "type": "minecraft:noise", "settings": "minecraft:nether",
        "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}}}
    end = {"type": "minecraft:the_end", "generator": {
        "type": "minecraft:noise", "settings": "minecraft:end", "biome_source": {"type": "minecraft:the_end"}}}
    preset = {"dimensions": {"minecraft:overworld": overworld, "minecraft:the_end": end, "minecraft:the_nether": nether}}
    write("minecraft/worldgen/world_preset/normal.json", preset)
    write("minecraft/worldgen/world_preset/large_biomes.json", preset)

    write("minecraft/dimension_type/overworld.json", {
        "ambient_light": 0.0, "bed_works": True, "coordinate_scale": 1.0, "effects": "minecraft:overworld",
        "has_ceiling": False, "has_raids": True, "has_skylight": True, "height": HEIGHT,
        "infiniburn": "#minecraft:infiniburn_overworld", "logical_height": HEIGHT, "min_y": MIN_Y,
        "monster_spawn_block_light_limit": 0,
        "monster_spawn_light_level": {"type": "minecraft:uniform", "value": {"max_inclusive": 7, "min_inclusive": 0}},
        "natural": True, "piglin_safe": False, "respawn_anchor_works": False, "ultrawarm": False})

    write("skycraft/worldgen/configured_feature/map_water.json", {"type": "skycraft:map_water", "config": {}})
    write("skycraft/worldgen/placed_feature/map_water.json", {"feature": "skycraft:map_water", "placement": []})
    print("wrote the skycraft:tamriel terrain JSON")


if __name__ == "__main__":
    main()
