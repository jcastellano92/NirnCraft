package com.skycraft.creatures.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.Tags;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coarse biome families used by the region spawn tables: works for Skycraft's generated Tamriel biomes (by their
 * tags and descriptive ids, e.g. {@code skycraft:hjaalmarch_marsh}), vanilla and other mods' biomes alike.
 */
public enum BiomeFamily {
    OCEAN, BEACH, RIVER, SWAMP, DESERT, JUNGLE, ASH, FROZEN, MOUNTAIN, FOREST, PLAINS;

    private static final TagKey<Biome> SPAWNS_SWAMP = tag("skycraft", "spawns/swamp");
    private static final TagKey<Biome> SPAWNS_COAST = tag("skycraft", "spawns/coast");
    private static final TagKey<Biome> SPAWNS_RIVER = tag("skycraft", "spawns/river");
    private static final TagKey<Biome> SPAWNS_COLD = tag("skycraft", "spawns/cold");
    private static final TagKey<Biome> SPAWNS_MOUNTAIN = tag("skycraft", "spawns/mountain");
    private static final TagKey<Biome> SPAWNS_FOREST = tag("skycraft", "spawns/forest");
    private static final TagKey<Biome> SPAWNS_DESERT = tag("skycraft", "spawns/desert");
    private static final TagKey<Biome> SPAWNS_ASH = tag("skycraft", "spawns/ash");
    private static final TagKey<Biome> SPAWNS_JUNGLE = tag("skycraft", "spawns/jungle");

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BiomeFamily byId(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }

    private static TagKey<Biome> tag(String ns, String path) {
        return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(ns, path));
    }

    public static BiomeFamily of(Holder<Biome> biome, BlockPos pos, int seaLevel) {
        // Temperature depends on altitude; never reuse the first sampled position across an entire biome/world.
        var key = biome.unwrapKey().orElse(null);
        return resolve(biome, key == null ? "" : key.identifier().getPath(), pos, seaLevel);
    }

    private static boolean has(String name, String... words) {
        for (String w : words) {
            if (name.contains(w)) return true;
        }
        return false;
    }

    private static BiomeFamily resolve(Holder<Biome> b, String name, BlockPos pos, int seaLevel) {
        if (b.is(BiomeTags.IS_OCEAN) || has(name, "ocean", "sea_of", "deep_sea")) return OCEAN;
        if (b.is(BiomeTags.IS_RIVER) || b.is(SPAWNS_RIVER) || has(name, "river")) return RIVER;
        if (b.is(BiomeTags.IS_BEACH) || b.is(SPAWNS_COAST) || has(name, "beach", "coast", "shore")) return BEACH;
        if (b.is(SPAWNS_ASH) || has(name, "ash", "volcan", "red_mountain", "molag", "caldera", "lava")) return ASH;
        if (b.is(Tags.Biomes.IS_SWAMP) || b.is(SPAWNS_SWAMP) || has(name, "swamp", "marsh", "mire", "bog", "mangrove", "fen")) return SWAMP;
        if (b.is(SPAWNS_DESERT) || b.is(Tags.Biomes.IS_DESERT) || b.is(BiomeTags.IS_BADLANDS)
                || has(name, "desert", "dune", "badland", "canyon", "alikr", "sand")) return DESERT;
        if (b.is(BiomeTags.IS_JUNGLE) || b.is(SPAWNS_JUNGLE) || has(name, "jungle", "rainforest", "hist", "tropical", "pellitine")) return JUNGLE;
        if (b.is(Tags.Biomes.IS_SNOWY) || b.is(SPAWNS_COLD) || has(name, "snow", "glacier", "ice", "frozen", "tundra", "frost", "pale")
                || b.value().coldEnoughToSnow(pos, seaLevel)) return FROZEN;
        if (b.is(BiomeTags.IS_MOUNTAIN) || b.is(SPAWNS_MOUNTAIN) || has(name, "peak", "mountain", "crag", "highland", "cliff")) return MOUNTAIN;
        if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA) || b.is(SPAWNS_FOREST)
                || has(name, "forest", "wood", "grove", "taiga", "pine", "birch", "weald", "graht")) return FOREST;
        return PLAINS;
    }
}
