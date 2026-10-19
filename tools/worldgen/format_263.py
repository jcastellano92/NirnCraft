"""Convert authored world-generation descriptions to the native 26.3 data format.

Generators may keep their declarative authoring format; every resource writer passes
through this idempotent boundary. This also upgrades checked-in generated resources.
No biome, feature, placement or authored block is discarded by the conversion.
"""
from __future__ import annotations

import copy
import json
import math
import importlib.util
from pathlib import Path

_DATA_SPEC = importlib.util.spec_from_file_location("skycraft_data_format_263", Path(__file__).resolve().parents[1] / "data_format_263.py")
_DATA = importlib.util.module_from_spec(_DATA_SPEC)
_DATA_SPEC.loader.exec_module(_DATA)


def noise_parameters(value):
    """NormalNoise.createParity's amplitude conversion, validated against 26.3's codec."""
    if "firstOctave" not in value:
        return value
    amplitudes = value["amplitudes"]
    count = len(amplitudes)
    factor = 2.0 ** (count - 1) / (2.0 ** count - 1.0)
    layers = [factor * 0.5 ** i * a for i, a in enumerate(amplitudes)]
    total = sum(abs(a) for a in layers)
    variance = sum((0.2702247831245211 * abs(a)) ** 2 for a in layers)
    normal = total / 3.0 / (math.sqrt(variance) * math.sqrt(2.0)) if variance else 0.0
    active = [i for i, a in enumerate(amplitudes) if a]
    span = active[-1] - active[0] if active else 0
    old_normal = 0.5 / 3.0 / (0.1 * (1.0 + 1.0 / (span + 1)))
    result = {"base_amplitude": old_normal / normal if normal else 1.0,
              "base_octave": value["firstOctave"], "octave_count": count}
    if any(a != 1.0 for a in amplitudes):
        result["amplitude_modifiers"] = amplitudes
    return result


def color(value):
    return f"#{value & 0xffffff:06x}" if isinstance(value, int) else value


def tree(value):
    """Native codec changes shared by feature, density and material-rule trees."""
    if isinstance(value, list):
        return [tree(v) for v in value]
    if not isinstance(value, dict):
        return value
    if value == {"type": "minecraft:soil_beneath_tree"}:
        # This is a registered provider holder, not a provider-type codec.
        return "minecraft:soil_beneath_tree"
    if "Name" in value:
        name = {"minecraft:chain": "minecraft:iron_chain", "minecraft:grass": "minecraft:short_grass"}.get(value["Name"], value["Name"])
        return {"id": name, "properties": value["Properties"]} if value.get("Properties") else name
    result = {k: tree(v) for k, v in value.items()}
    kind = result.get("type", "")
    if kind == "minecraft:forest_rock":
        result["type"] = "minecraft:block_blob"
        result["can_place_on"] = {"type": "minecraft:matching_block_tag", "tag": "minecraft:forest_rock_can_place_on"}
    if kind in ("minecraft:add", "minecraft:mul", "minecraft:min", "minecraft:max", "minecraft:sub", "minecraft:div"):
        if "argument1" in result:
            result["left"] = result.pop("argument1")
            result["right"] = result.pop("argument2")
    if kind in ("minecraft:abs", "minecraft:square", "minecraft:cube", "minecraft:half_negative", "minecraft:quarter_negative", "minecraft:squeeze", "minecraft:interpolated", "minecraft:blend_density") and "argument" in result:
        result["input"] = result.pop("argument")
    if kind == "minecraft:random_offset":
        result["type"] = "minecraft:offset"
        result["x"] = result["z"] = result.pop("xz_spread")
        result["y"] = result.pop("y_spread")
    if "minCount" in result and "maxCount" in result and "weight" in result and "type" in result:
        low, high = result.pop("minCount"), result.pop("maxCount")
        result["count"] = low if low == high else {"type": "minecraft:uniform", "min_inclusive": low, "max_inclusive": high}
    if kind in ("minecraft:cave", "minecraft:nether_cave") and "count" not in result:
        # Previously implicit CaveWorldCarver defaults are explicit in 26.3.
        # Authored disabled carvers retain their zero probability and all
        # authored radius/floor values, including the old room yScale.
        result["count"] = {"type": "minecraft:very_biased_to_bottom", "min_inclusive": 0, "max_inclusive": 14}
        result["thickness"] = {"type": "minecraft:trapezoid", "min": 0.0, "max": 3.0, "plateau": 1.0}
        result["weird_thickness_bias"] = True
        result["room_vertical_radius_multiplier"] = result.pop("yScale", 0.5)
    if kind in ("minecraft:cache_2d", "minecraft:flat_cache", "minecraft:cache_once", "minecraft:cache_all_in_cell"):
        result["type"] = "minecraft:cache"
        result["input"] = result.pop("argument")
    if kind == "minecraft:shifted_noise":
        result["type"] = "minecraft:noise"
    if kind == "minecraft:y_clamped_gradient":
        result["type"] = "minecraft:gradient"
        result["axis"] = "y"
        result["from_coordinate"] = result.pop("from_y")
        result["to_coordinate"] = result.pop("to_y")
    if kind in ("minecraft:simple_state_provider", "minecraft:weighted_state_provider"):
        result["type"] = {"minecraft:simple_state_provider": "minecraft:simple", "minecraft:weighted_state_provider": "minecraft:weighted"}[kind]
    if kind in ("minecraft:uniform", "minecraft:biased_to_bottom", "minecraft:clamped", "minecraft:clamped_normal", "minecraft:weighted_list") and isinstance(result.get("value"), dict):
        result.update(result.pop("value"))
    # 26.3 registers configured features directly. Skycraft's bound codec keeps
    # its existing config object; native feature codecs have their fields inline.
    if "config" in result and kind.startswith("minecraft:"):
        config = result.pop("config")
        if kind == "minecraft:random_patch":
            result["type"] = "skycraft:random_patch"
            result["config"] = config
        else:
            if kind == "minecraft:tree":
                config.pop("dirt_provider", None)
                config.pop("force_dirt", None)
                config.setdefault("below_trunk_provider", "minecraft:soil_beneath_tree")
            result.update(config)
    return result


def interpolation_cells(value, xz, y):
    if isinstance(value, list):
        for item in value:
            interpolation_cells(item, xz, y)
    elif isinstance(value, dict):
        if value.get("type") == "minecraft:interpolated":
            value.setdefault("cell_size_xz", xz)
            value.setdefault("cell_size_y", y)
        for item in value.values():
            interpolation_cells(item, xz, y)


def biome(value):
    result = copy.deepcopy(value)
    effects = result["effects"]
    attributes = result.setdefault("attributes", {})
    for key in ("sky_color", "fog_color", "water_fog_color"):
        if key in effects:
            attributes["minecraft:visual/" + key] = color(effects.pop(key))
    for key in ("water_color", "foliage_color", "dry_foliage_color", "grass_color"):
        if key in effects:
            effects[key] = color(effects[key])
    sounds = {}
    for old, new in (("ambient_sound", "loop"), ("mood_sound", "mood"), ("additions_sound", "additions")):
        if old in effects:
            sounds[new] = effects.pop(old)
    if sounds:
        attributes["minecraft:audio/ambient_sounds"] = sounds
    if "music" in effects:
        music = effects.pop("music")
        music.pop("replace_current_music", None)
        attributes["minecraft:audio/background_music"] = {"default": music}
    if "particle" in effects:
        particle = effects.pop("particle")
        if "options" in particle:
            particle["particle"] = particle.pop("options")
        attributes["minecraft:visual/ambient_particles"] = {"modifier": "append", "argument": [particle]}
    if "spawners" in result:
        spawners = result.pop("spawners")
        for entries in spawners.values():
            for entry in entries:
                if "minCount" in entry:
                    low, high = entry.pop("minCount"), entry.pop("maxCount")
                    entry["count"] = low if low == high else {"type": "minecraft:uniform", "min_inclusive": low, "max_inclusive": high}
        attributes["minecraft:gameplay/natural_mob_spawns"] = {"modifier": "overlay", "argument": {
            "spawns_by_category": spawners, "spawn_costs": result.pop("spawn_costs", {})}}
    if "creature_spawn_probability" in result:
        attributes["minecraft:gameplay/creature_world_gen_spawn_probability"] = result.pop("creature_spawn_probability")
    if isinstance(result.get("carvers"), dict):
        result["carvers"] = list(dict.fromkeys(item for group in result["carvers"].values() for item in group))
    return result


def noise_settings(value, path):
    result = copy.deepcopy(value)
    if "surface_rule" not in result:
        return tree(result), {}
    router = result["noise_router"]
    cells = result["noise"]
    xz, y = cells.pop("size_horizontal", 1) * 4, cells.pop("size_vertical", 2) * 4
    height = "skycraft:tamriel/height" if path.stem == "tamriel" else f"skycraft:realm/{path.stem}/height"
    router["chunk_surface_level"] = height
    if result.pop("aquifers_enabled", False):
        result["aquifers"] = {k: router.pop(k) for k in ("barrier", "fluid_level_floodedness", "fluid_level_spread", "lava")}
        result["aquifers"]["surface_level"] = height
        result["aquifers"]["exclusion"] = {"type": "minecraft:min", "left": {
            "type": "minecraft:sub", "left": -0.225, "right": router["erosion"]}, "right": {
            "type": "minecraft:max", "left": {"type": "minecraft:sub", "left": router["depth"], "right": 0.9}, "right": 0.0}}
    for key in ("barrier", "fluid_level_floodedness", "fluid_level_spread", "lava", "initial_density_without_jaggedness", "vein_toggle", "vein_ridged", "vein_gap"):
        router.pop(key, None)
    material = result.pop("surface_rule")
    if result.pop("ore_veins_enabled", False):
        material = {"type": "minecraft:sequence", "sequence": ["minecraft:overworld/copper_ore_vein", "minecraft:overworld/iron_ore_vein", material]}
    result["material_rule"] = material
    # Spawn targets now use density-function registry holders as map keys. The
    # new named functions below retain exactly the router's authored expressions.
    extra = {}
    parameters = {"temperature": "temperature", "humidity": "vegetation", "continentalness": "continents", "erosion": "erosion", "weirdness": "ridges", "depth": "depth"}
    targets = result.get("spawn_target", [])
    if targets and any(k in parameters for k in targets[0]):
        names = {}
        for old, new in parameters.items():
            expression = router[new]
            if isinstance(expression, str):
                names[old] = expression
            else:
                relative = f"spawn/{path.stem}/{new}"
                names[old] = "skycraft:" + relative
                extra[relative + ".json"] = tree(expression)
        result["spawn_target"] = [{names[k]: v for k, v in target.items() if k in names} for target in targets]
    result = tree(result)
    interpolation_cells(result, xz, y)
    return result, extra


def legacy_ambient_light_color(ambient, fixed_time=None):
    """26.3's lightmap shader no longer reads a dimension's ambient_light: its floor is visual/ambient_light_color.

    1.20.1 lifted every lightmap cell with ambient_light (LightTexture: brightness = lerp(ambient, curve, 1)); this is
    that old cell at sky and block light 0 before gamma (block flicker 1.5, the sky term at the fixed time, night when
    the time runs, then the old 4 % pull towards 0.75). Mojang's own nether (ambient 0.1) became #302821; this gives
    #2f241d for it. An ambient of 0 keeps vanilla's overworld floor, #0a0a0a.
    """
    if not ambient:
        return "#0a0a0a"
    t = 18000 if fixed_time is None else fixed_time
    d = (t / 24000.0 - 0.25) % 1.0
    tod = (d * 2 + (0.5 - math.cos(d * math.pi) / 2)) / 3
    g = 1 - (math.cos(tod * 2 * math.pi) * 2 + 0.2)
    sky = (1 - min(max(g, 0.0), 1.0)) * 0.8 + 0.2
    f9 = ambient * 1.5
    block = (f9, f9 * ((f9 * 0.6 + 0.4) * 0.6 + 0.4), f9 * (f9 * f9 * 0.6 + 0.4))
    sky_vec = [c + (1 - c) * 0.35 for c in (sky, sky, 1.0)]
    f8 = ambient * (sky * 0.95 + 0.05)
    rgb = [(b + s * f8) * 0.96 + 0.03 for b, s in zip(block, sky_vec)]
    return "#%02x%02x%02x" % tuple(round(min(1.0, c) * 255) for c in rgb)


# Musics.GAME / CREATIVE / END: 1.20.1 picked them for biomes without music (the only kind Skycraft has)
_MUSIC_GAME = {"max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.game"}
_MUSIC_CREATIVE = {"max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.creative"}
_MUSIC_END = {"max_delay": 24000, "min_delay": 6000, "replace_current_music": True, "sound": "minecraft:music.end"}


def dimension_type(value, path):
    result = copy.deepcopy(value)
    attributes = result.setdefault("attributes", {})
    attributes.setdefault("minecraft:visual/ambient_light_color",
                          legacy_ambient_light_color(result.get("ambient_light", 0.0), result.get("fixed_time")))
    if path.stem == "the_end":
        attributes.setdefault("minecraft:audio/background_music", {"default": dict(_MUSIC_END)})
    elif path.stem == "the_nether":
        attributes.setdefault("minecraft:audio/background_music", {"default": dict(_MUSIC_GAME)})
    else:
        attributes.setdefault("minecraft:audio/background_music", {"creative": dict(_MUSIC_CREATIVE), "default": dict(_MUSIC_GAME)})
    natural = result.get("natural", attributes.get("minecraft:gameplay/nether_portal_spawns_piglin", path.stem == "overworld"))
    hot = result.get("ultrawarm", attributes.get("minecraft:gameplay/water_evaporates", False))
    hot_attributes = {
        "minecraft:gameplay/fast_lava": hot,
        "minecraft:visual/default_dripstone_particle": {
            "type": "minecraft:dripping_dripstone_lava" if hot else "minecraft:dripping_dripstone_water"},
    }
    for key, mapped in hot_attributes.items():
        if "ultrawarm" in result:
            attributes[key] = mapped
        else:
            attributes.setdefault(key, mapped)
    if "natural" in result:
        attributes["minecraft:gameplay/nether_portal_spawns_piglin"] = natural
    else:
        attributes.setdefault("minecraft:gameplay/nether_portal_spawns_piglin", natural)
    # A paused clock preserves the visual time, while this independent native
    # flag preserves the old exclusion from day/night gameplay classification.
    result.setdefault("has_fixed_time", False)
    if result.get("fixed_time") is not None or str(result.get("default_clock", "")).startswith("skycraft:fixed/"):
        result["has_fixed_time"] = True
    realm = {"the_nether": "deadlands", "the_end": "sovngarde"}.get(path.stem, path.stem)
    if path.stem == "overworld":
        attributes = result.setdefault("attributes", {})
        attributes["neoforge:custom_clouds"] = "skycraft:tamriel_clouds"
        attributes.setdefault("minecraft:visual/cloud_height", 330.0)
    if realm in {"deadlands", "sovngarde", "coldharbour", "soul_cairn", "apocrypha"}:
        renderer = "skycraft:" + realm
        result.setdefault("attributes", {}).update({"neoforge:custom_skybox": renderer,
            "neoforge:custom_clouds": renderer, "neoforge:custom_weather_effects": renderer})
    if "effects" not in result:
        return tree(result)
    effects = result.pop("effects")
    attributes = result.setdefault("attributes", {})
    beds = result.pop("bed_works", False)
    if not beds:
        bed_rule = {"can_sleep": "never", "can_set_spawn": "never", "destroy_on_use": True}
    elif natural:
        bed_rule = {"can_sleep": "when_dark", "can_set_spawn": "always", "error_message": {"translate": "block.minecraft.bed.no_sleep"}}
    else:
        # Old ServerPlayer rejected unnatural dimensions before changing spawn,
        # even when bed_works allowed their beds to remain without exploding.
        bed_rule = {"can_sleep": "never", "can_set_spawn": "never"}
    attributes["minecraft:gameplay/bed_rule"] = bed_rule
    for old, new, inverse in (("has_raids", "can_start_raid", False), ("piglin_safe", "piglins_zombify", True), ("respawn_anchor_works", "respawn_anchor_works", False), ("ultrawarm", "water_evaporates", False)):
        if old in result:
            state = result.pop(old)
            attributes["minecraft:gameplay/" + new] = not state if inverse else state
    result.pop("natural", None)
    fixed = result.pop("fixed_time", None)
    result["has_ender_dragon_fight"] = effects == "skycraft:sovngarde" or path.stem == "the_end"
    if fixed is not None:
        clock = "skycraft:fixed/" + path.stem
        result["default_clock"] = clock
        result["timelines"] = ["skycraft:fixed/" + path.stem + "/day", "skycraft:fixed/" + path.stem + "/moon"]
    else:
        result["default_clock"] = "minecraft:overworld"
        result["timelines"] = "#minecraft:in_overworld"
    return tree(result)


def fixed_timeline(clock, timeline):
    template = Path(__file__).parent / "templates_263" / (timeline + ".json")
    definition = json.loads(template.read_text(encoding="utf-8"))
    definition["clock"] = clock
    if timeline == "day" and clock in {"skycraft:fixed/the_nether", "skycraft:fixed/the_end",
            "skycraft:fixed/apocrypha", "skycraft:fixed/soul_cairn", "skycraft:fixed/coldharbour"}:
        # Original RealmEffects kept the biome fog unscaled and returned no
        # sunrise color. Native timeline layers run after biome attributes.
        definition["tracks"].pop("minecraft:visual/fog_color", None)
        definition["tracks"].pop("minecraft:visual/sunrise_sunset_color", None)
    return definition


def prepare(path, value):
    """Return native path/data and write any generated dependent density files."""
    path, value = _DATA.prepare(path, value)
    path = Path(path)
    normalized = path.as_posix()
    if "/data/" not in normalized:
        return str(path), value
    if "/worldgen/" not in normalized and "/dimension_type/" not in normalized:
        return str(path), value
    if "/worldgen/configured_feature/" in normalized:
        path = Path(normalized.replace("/worldgen/configured_feature/", "/worldgen/feature/"))
    elif "/worldgen/configured_carver/" in normalized:
        path = Path(normalized.replace("/worldgen/configured_carver/", "/worldgen/carver/"))
    if "/worldgen/noise/" in normalized:
        value = noise_parameters(value)
    elif "/worldgen/biome/" in normalized:
        value = biome(value)
    elif "/worldgen/noise_settings/" in normalized:
        value, extra = noise_settings(value, path)
        root = Path(normalized.split("/worldgen/", 1)[0]) / "worldgen/density_function"
        for name, data in extra.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    elif "/dimension_type/" in normalized:
        value = dimension_type(value, path)
        clock = value.get("default_clock", "")
        if clock.startswith("skycraft:fixed/"):
            data = Path(normalized.split("/data/", 1)[0]) / "data/skycraft"
            name = clock.split(":", 1)[1]
            clock_file = data / "world_clock" / (name + ".json")
            clock_file.parent.mkdir(parents=True, exist_ok=True)
            clock_file.write_text("{}\n", encoding="utf-8")
            for timeline in ("day", "moon"):
                definition = fixed_timeline(clock, timeline)
                destination = data / "timeline" / name / (timeline + ".json")
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(json.dumps(definition, indent=2) + "\n", encoding="utf-8")
    else:
        value = tree(value)
    return str(path), value


def upgrade_resources(root):
    root = Path(root)
    changed = moved = 0
    files = sorted(root.glob("*/worldgen/**/*.json")) + sorted(root.glob("*/dimension_type/*.json"))
    for source in files:
        original = json.loads(source.read_text(encoding="utf-8"))
        target, converted = prepare(source.resolve(), original)
        target = Path(target)
        if target != source.resolve() or converted != original:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(converted, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            changed += 1
            if target != source.resolve():
                source.unlink()
                moved += 1
    return {"converted": changed, "moved": moved}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", default="mod/src/main/resources/data")
    args = parser.parse_args()
    print(json.dumps(upgrade_resources(args.resources), sort_keys=True))
