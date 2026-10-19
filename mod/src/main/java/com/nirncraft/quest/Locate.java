package com.nirncraft.quest;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Holds;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Finding places for quests: villages, dungeons and random wilderness spots. Never loads chunks for spots. */
public final class Locate {
    /** Dungeons for tome retrievals and Draugr bounties (vanilla structures + optional modded ones). */
    public static final TagKey<Structure> DUNGEONS = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "dungeons"));

    /** Structure searches never look further than this (each step may compute structure starts synchronously). */
    public static final int MAX_SEARCH_CHUNKS = 32;

    private Locate() {}

    @Nullable
    public static BlockPos structure(ServerLevel level, TagKey<Structure> tag, BlockPos from, int radiusChunks) {
        BlockPos found;
        try {
            found = level.findNearestMapStructure(tag, from, Math.min(radiusChunks, MAX_SEARCH_CHUNKS), false);
        } catch (Exception e) {
            NirnCraft.LOGGER.warn("Quest structure search failed for {}", tag.location(), e);
            found = null;
        }
        if (tag == DUNGEONS && level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            // Skyrim's dungeons are named places (their placement isn't searchable by structure): take the nearer
            BlockPos named = namedDungeon(from, Math.min(radiusChunks, MAX_SEARCH_CHUNKS) * 16);
            if (named != null && (found == null || horizontalDist(named, from) < horizontalDist(found, from))) found = named;
        }
        return found;
    }

    /** The nearest named dungeon or fort ({@link com.nirncraft.tamriel.Places}) within {@code maxDist} blocks. */
    @Nullable
    private static BlockPos namedDungeon(BlockPos from, int maxDist) {
        var dungeon = com.nirncraft.tamriel.Places.nearest(com.nirncraft.tamriel.PlaceType.DUNGEON_FIXED, from.getX(), from.getZ(), maxDist);
        var fort = com.nirncraft.tamriel.Places.nearest(com.nirncraft.tamriel.PlaceType.FORT, from.getX(), from.getZ(), maxDist);
        com.nirncraft.tamriel.Place best = dungeon.orElse(null);
        if (fort.isPresent() && (best == null || fort.get().distSqr(from.getX(), from.getZ()) < best.distSqr(from.getX(), from.getZ()))) {
            best = fort.get();
        }
        return best == null ? null : new BlockPos(best.x(), 64, best.z());
    }

    @Nullable
    public static BlockPos nearestVillage(ServerLevel level, BlockPos from) {
        BlockPos s = structure(level, com.nirncraft.roads.RoadNetwork.SETTLEMENTS, from, MAX_SEARCH_CHUNKS);
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
            BlockPos v = structure(level, com.nirncraft.roads.RoadNetwork.SETTLEMENTS, probe, 24);
            if (v == null) v = structure(level, StructureTags.VILLAGE, probe, 24);
            if (v != null && horizontalDist(v, from) >= minDist) return v;
        }
        return null;
    }

    /** Named places that quests send players into: the barrows, ruins and forts of {@link Places}. */
    public static final Set<PlaceType> QUEST_DUNGEONS = EnumSet.of(PlaceType.DUNGEON_FIXED, PlaceType.FORT);

    /**
     * A random named place of {@code types} between {@code minDist} and {@code maxDist} blocks away, preferring the
     * region (hold) the player is in; null if there is none (or outside the overworld).
     */
    @Nullable
    public static Place namedPlace(ServerLevel level, BlockPos from, RandomSource r, int minDist, int maxDist, Set<PlaceType> types) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
        String region = Holds.holdAt(level, from);
        List<Place> same = new ArrayList<>();
        List<Place> other = new ArrayList<>();
        long min2 = (long) minDist * minDist;
        long max2 = (long) maxDist * maxDist;
        for (Place p : Places.overlapping(from.getX() - maxDist, from.getZ() - maxDist, from.getX() + maxDist, from.getZ() + maxDist)) {
            if (!types.contains(p.type())) continue;
            long d = p.distSqr(from.getX(), from.getZ());
            if (d < min2 || d > max2) continue;
            (region.equals(p.regionId()) ? same : other).add(p);
        }
        List<Place> pool = !same.isEmpty() ? same : other;
        return pool.isEmpty() ? null : pool.get(r.nextInt(pool.size()));
    }

    /** The named quest dungeon whose footprint contains {@code pos}, if any. */
    @Nullable
    public static Place placeAt(BlockPos pos) {
        for (Place p : Places.overlapping(pos.getX(), pos.getZ(), pos.getX(), pos.getZ())) {
            if (QUEST_DUNGEONS.contains(p.type()) && p.distSqr(pos.getX(), pos.getZ()) <= (long) p.radius() * p.radius()) return p;
        }
        return null;
    }

    /** If a quest target is a named place, the player hears of it: it appears on their map and compass. */
    public static void rumor(ServerPlayer player, BlockPos target) {
        Place p = placeAt(target);
        if (p != null) com.nirncraft.world.Discovery.revealPlace(player, p);
    }

    @Nullable
    public static BlockPos dungeon(ServerLevel level, BlockPos from, RandomSource r) {
        // named barrows and ruins of the region first (two times in three), then any generated dungeon
        Place named = namedPlace(level, from, r, 100, 900, QUEST_DUNGEONS);
        if (named != null && r.nextInt(3) > 0) return new BlockPos(named.x(), surfaceY(level, named.x(), named.z()), named.z());
        // look a little away from the player so the dungeon isn't always the one under their feet
        BlockPos probe = randomSpot(level, r, from, 100, 300);
        BlockPos d = structure(level, DUNGEONS, probe, 24);
        if (d == null) d = structure(level, DUNGEONS, from, MAX_SEARCH_CHUNKS);
        if (d == null && named != null) d = new BlockPos(named.x(), surfaceY(level, named.x(), named.z()), named.z());
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
