package com.skycraft.scatter;

import com.skycraft.Skycraft;
import com.skycraft.scatter.ScatterRules.Kind;
import com.skycraft.scatter.ScatterRules.Wayside;
import com.skycraft.scatter.ZoneModel.Info;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/**
 * Leavings beside the roads that really exist. Roads are planned and paved after chunks are generated, so worldgen can
 * only guess where they run ({@link ZoneModel}); this pass asks the road module's read-only {@code RoadCells} (through
 * {@link RoadProbe}) on the server thread, for the chunks around each player, once per chunk, and drops a cart wreck,
 * crates, a cold fire or a cairn a few blocks off the paved band. The road module keeps its own lanterns, signposts,
 * milestones and shrines; this adds only human debris. Without {@code RoadCells} the pass does nothing.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Waysides {
    private static final int INTERVAL = 40, RADIUS = 4, PER_PASS = 3;
    private static final Set<Long> CHECKED = new HashSet<>();
    private static long tick;

    private Waysides() {}

    /** Chunks that have had their wayside leavings (persisted). */
    public static final class Data extends SavedData {
        private static final String NAME = "skycraft_scatter";
        private final LongSet dressed = new LongOpenHashSet();

        static Data get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(Data::load, Data::new, NAME);
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            for (long k : tag.getLongArray("dressed")) d.dressed.add(k);
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putLongArray("dressed", dressed.toLongArray());
            return tag;
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD || !RoadProbe.live()) return;
        if (++tick % INTERVAL != 7) return;
        if (ScatterConfig.density() <= 0 || level.players().isEmpty()) return;
        try {
            if (CHECKED.size() > 200_000) CHECKED.clear();
            int done = 0;
            for (ServerPlayer p : level.players()) {
                ChunkPos pc = p.chunkPosition();
                for (int dx = -RADIUS; dx <= RADIUS && done < PER_PASS; dx++) {
                    for (int dz = -RADIUS; dz <= RADIUS && done < PER_PASS; dz++) {
                        int cx = pc.x + dx, cz = pc.z + dz;
                        long key = ChunkPos.asLong(cx, cz);
                        if (CHECKED.contains(key)) continue;
                        if (!neighbourhoodLoaded(level, cx, cz)) continue;
                        CHECKED.add(key);
                        if (dress(level, cx, cz)) done++;
                    }
                }
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft scatter wayside pass failed", e);
        }
    }

    private static boolean neighbourhoodLoaded(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (c == null) return false;
            }
        }
        return true;
    }

    /** Drops this chunk's wayside leavings if a real road runs through it. Returns true when something was placed. */
    static boolean dress(ServerLevel level, int cx, int cz) {
        Data data = Data.get(level);
        long key = ChunkPos.asLong(cx, cz);
        if (data.dressed.contains(key)) return false;
        // road columns on a 4x4 lattice
        int[][] road = new int[16][];
        int n = 0;
        for (int ix = 0; ix < 4; ix++) {
            for (int iz = 0; iz < 4; iz++) {
                int x = cx * 16 + 2 + ix * 4, z = cz * 16 + 2 + iz * 4;
                if (RoadProbe.nearRoad(level, x, z, 2, true)) road[n++] = new int[]{x, z};
            }
        }
        if (n == 0) return false;
        ScatterRules rules = ScatterRules.get();
        Wayside w = rules.wayside;
        if (w.total <= 0) return false;
        long seed = level.getSeed();
        RandomSource rnd = RandomSource.create(Scatter.mix(seed ^ Scatter.mix(key ^ 0x3A55CAFEL)));
        Info info = ZoneModel.chunk(seed, cx, cz);
        if (info.zone() == ScatterZone.URBAN_FRINGE && info.settleEdge() < 6) return false; // inside a town's gate yard
        data.dressed.add(key);
        data.setDirty();
        // a road crossing a chunk corner gives a fraction of a chunk's worth: scale by how much of it is road
        double expect = w.density * Math.min(1.0, n / 4.0) * (0.6 + 0.8 * info.humanity()) * ScatterConfig.density();
        int count = (int) expect + (rnd.nextDouble() < expect - (int) expect ? 1 : 0);
        boolean any = false;
        for (int i = 0; i < count; i++) {
            Kind k = pick(w, rnd);
            if (k == null) continue;
            int[] base = road[rnd.nextInt(n)];
            // 5-9 blocks off the road, either side, then check it is clear of the paved band
            for (int attempt = 0; attempt < 6; attempt++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                int off = 5 + rnd.nextInt(5);
                int x = base[0] + (int) Math.round(Math.cos(a) * off), z = base[1] + (int) Math.round(Math.sin(a) * off);
                if (RoadProbe.nearRoad(level, x, z, 4, true)) continue;
                if (Scatter.dress(level, k.id, x, z, 0x57A51DEL + i)) {
                    any = true;
                    break;
                }
            }
        }
        return any;
    }

    private static Kind pick(Wayside w, RandomSource rnd) {
        double roll = rnd.nextDouble() * w.total;
        for (int i = 0; i < w.kinds.size(); i++) {
            roll -= w.weights.get(i);
            if (roll < 0) return w.kinds.get(i);
        }
        return null;
    }
}
