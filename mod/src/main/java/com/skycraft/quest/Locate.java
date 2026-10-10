package com.skycraft.quest;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

/** Finding places for quests: villages, dungeons and random wilderness spots. Never loads chunks for spots. */
public final class Locate {
    /** Dungeons for tome retrievals and Draugr bounties (vanilla structures + optional modded ones). */
    public static final TagKey<Structure> DUNGEONS = TagKey.create(Registries.STRUCTURE, new ResourceLocation(Skycraft.MODID, "dungeons"));

    /** Structure searches never look further than this (each step may compute structure starts synchronously). */
    public static final int MAX_SEARCH_CHUNKS = 32;

    private Locate() {}

    @Nullable
    public static BlockPos structure(ServerLevel level, TagKey<Structure> tag, BlockPos from, int radiusChunks) {
        try {
            return level.findNearestMapStructure(tag, from, Math.min(radiusChunks, MAX_SEARCH_CHUNKS), false);
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Quest structure search failed for {}", tag.location(), e);
            return null;
        }
    }

    @Nullable
    public static BlockPos nearestVillage(ServerLevel level, BlockPos from) {
        BlockPos s = structure(level, com.skycraft.roads.RoadNetwork.SETTLEMENTS, from, MAX_SEARCH_CHUNKS);
        if (s != null && (level.getBiome(s).is(net.minecraft.tags.BiomeTags.IS_OCEAN) || level.getBiome(s).is(net.minecraft.tags.BiomeTags.IS_DEEP_OCEAN))) {
            s = null;
        }
        if (s == null) {
            s = structure(level, StructureTags.VILLAGE, from, MAX_SEARCH_CHUNKS);
            if (s != null && (level.getBiome(s).is(net.minecraft.tags.BiomeTags.IS_OCEAN) || level.getBiome(s).is(net.minecraft.tags.BiomeTags.IS_DEEP_OCEAN))) {
                s = null;
            }
        }
        return s;
    }

    /** A village at least {@code minDist} blocks away (for couriers), or null. */
    @Nullable
    public static BlockPos farVillage(ServerLevel level, BlockPos from, int minDist, RandomSource r) {
        for (int attempt = 0; attempt < 4; attempt++) {
            BlockPos probe = randomSpot(level, r, from, minDist + 100, minDist + 500);
            BlockPos v = structure(level, com.skycraft.roads.RoadNetwork.SETTLEMENTS, probe, 24);
            if (v == null) v = structure(level, StructureTags.VILLAGE, probe, 24);
            if (v != null && horizontalDist(v, from) >= minDist) return v;
        }
        return null;
    }

    @Nullable
    public static BlockPos dungeon(ServerLevel level, BlockPos from, RandomSource r) {
        // look a little away from the player so the dungeon isn't always the one under their feet
        BlockPos probe = randomSpot(level, r, from, 100, 300);
        BlockPos d = structure(level, DUNGEONS, probe, 24);
        if (d == null) d = structure(level, DUNGEONS, from, MAX_SEARCH_CHUNKS);
        return d;
    }

    /**
     * Surface height at x/z without loading or generating the chunk: the real heightmap when the chunk is already
     * loaded, otherwise the world generator's noise estimate (cheap, no chunk I/O).
     */
    public static int surfaceY(ServerLevel level, int x, int z) {
        if (level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        var source = level.getChunkSource();
        return source.getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, source.randomState());
    }

    /** Finds an elevated mountain barrow/dungeon structure or mountain summit. */
    @Nullable
    public static BlockPos mountainDungeon(ServerLevel level, BlockPos from, RandomSource r) {
        BlockPos best = null;
        int highestY = 75;
        // two small structure searches at most: a quest offer must never stall the server
        for (int i = 0; i < 2; i++) {
            BlockPos probe = randomSpot(level, r, from, 150 + i * 100, 350 + i * 100);
            BlockPos d = structure(level, DUNGEONS, probe, 16);
            if (d != null) {
                int surfaceY = surfaceY(level, d.getX(), d.getZ());
                if (surfaceY > highestY) {
                    highestY = surfaceY;
                    best = new BlockPos(d.getX(), surfaceY, d.getZ());
                }
            }
        }
        if (best != null && highestY >= 90) return best;
        for (int i = 0; i < 8; i++) {
            BlockPos probe = randomSpot(level, r, from, 220, 420);
            int y = surfaceY(level, probe.getX(), probe.getZ());
            if (y > highestY) {
                highestY = y;
                best = new BlockPos(probe.getX(), y, probe.getZ());
            }
        }
        if (best != null) return best;
        BlockPos d = dungeon(level, from, r);
        // never null, so callers don't run the same searches again
        return d != null ? d : randomSpot(level, r, from, 200, 450);
    }

    /** A spot {@code min..max} blocks away in a random direction, inside the world border. Y is a guess (64). */
    public static BlockPos randomSpot(ServerLevel level, RandomSource r, BlockPos origin, int min, int max) {
        BlockPos result = origin;
        for (int i = 0; i < 10; i++) {
            double angle = r.nextDouble() * Math.PI * 2;
            int dist = min + r.nextInt(Math.max(1, max - min + 1));
            result = new BlockPos(origin.getX() + (int) (Math.cos(angle) * dist), Math.max(64, origin.getY()),
                    origin.getZ() + (int) (Math.sin(angle) * dist));
            if (level.getWorldBorder().isWithinBounds(result)) return result;
        }
        return result;
    }

    public static double horizontalDist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
