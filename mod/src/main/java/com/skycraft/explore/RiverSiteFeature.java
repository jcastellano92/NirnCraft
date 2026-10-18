package com.skycraft.explore;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import com.skycraft.dungeons.world.Cultures;
import com.skycraft.explore.build.Palette;
import com.skycraft.explore.build.RiverBuilds;
import com.skycraft.explore.build.SiteBuilder;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.Hydrology;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;

/**
 * Builds the river site (fishing hut, mill, ford, ferry, toll post, overlook, the cave behind a waterfall, sunken
 * cache, bank cache, hidden fishing spot, spring basin) whose centre lies in the decorated chunk. The sites are baked
 * into {@code hydrology.json} by {@code tools/worldgen/tamriel_map.py} (rule-checked against the rasters: dry bank,
 * gentle slope, clear of places, spaced apart), so this is a table lookup per chunk. Like the minor sites it only
 * writes inside the 3x3 chunks of the feature, and it gives way to the minor-site grid and to surface structures.
 */
public class RiverSiteFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public RiverSiteFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /** Footprint radius (blocks) per kind; all stay inside one chunk ring. */
    static int radius(String kind) {
        return switch (kind) {
            case "watermill" -> 7;
            case "ford" -> 10;
            case "ferry", "fishing_hut", "fall_secret" -> 8;
            case "sunken_cache" -> 3;
            default -> 5;
        };
    }

    /** Kinds that may write over water (docks, fords, sunken chests, falling water). */
    static boolean wet(String kind) {
        return switch (kind) {
            case "ford", "ferry", "fishing_hut", "sunken_cache", "hidden_fishing", "fall_secret" -> true;
            default -> false;
        };
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos chunk = ChunkPos.containing(ctx.origin());
        Hydrology.Site s = Hydrology.get().siteInChunk(chunk.x(), chunk.z());
        if (s == null) return false;
        long seed = level.getSeed();
        boolean secret = s.kind().equals("fall_secret");
        if (!secret && !MinorSites.within(seed, s.x(), s.z(), 36).isEmpty()) return false;
        if (!secret && MinorSiteFeature.surfaceStructureHere(level, chunk)) return false;
        Region region = TamrielMap.region(s.x(), s.z());
        String province = Cultures.province(region, s.x(), s.z());
        if (province.equals("sea")) return false;
        long h = MinorSites.mix(seed ^ MinorSites.mix(s.x() * 0x9E3779B97F4A7C15L + s.z() * 0xC2B2AE3D27D4EB4FL + 0x7B1E5L));
        // SiteBuilder wants a minor-site record: the kind only selects whether it may write over water
        SiteKind carrier = wet(s.kind()) ? SiteKind.FISHING_DOCK : SiteKind.TREASURE_CACHE;
        MinorSites.Site site = new MinorSites.Site(Math.floorDiv(s.x(), MinorSites.CELL), Math.floorDiv(s.z(), MinorSites.CELL),
                s.x(), s.z(), carrier, h, s.facing() & 3, null, region.id(), province);
        RandomSource random = RandomSource.create(h);
        SiteBuilder b = new SiteBuilder(level, random, site, Palette.of(province, ""));
        boolean dryOnly = !wet(s.kind()) && !s.kind().equals("spring_shrine");
        if (dryOnly && b.footprintWet(Math.min(4, radius(s.kind())))) return false;
        try {
            return build(s.kind(), b);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Skycraft rivers: failed to build {} at {} {}", s.kind(), s.x(), s.z(), e);
            return false;
        }
    }

    private static boolean build(String kind, SiteBuilder b) {
        return switch (kind) {
            case "fishing_hut" -> RiverBuilds.fishingHut(b);
            case "watermill" -> RiverBuilds.watermill(b);
            case "ford" -> RiverBuilds.ford(b);
            case "ferry" -> RiverBuilds.ferry(b);
            case "toll_post" -> RiverBuilds.tollPost(b);
            case "fall_overlook" -> RiverBuilds.overlook(b);
            case "fall_secret" -> RiverBuilds.fallSecret(b);
            case "sunken_cache" -> RiverBuilds.sunkenCache(b);
            case "bank_cache" -> RiverBuilds.bankCache(b);
            case "hidden_fishing" -> RiverBuilds.hiddenFishing(b);
            case "spring_shrine" -> RiverBuilds.springShrine(b);
            default -> false;
        };
    }
}
