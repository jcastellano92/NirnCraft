package com.skycraft.dev;

import com.skycraft.Skycraft;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Dev-only {@code /skycraft-dev regen}: regenerates a bounded area of a running server IN PLACE with the current code,
 * so measurement scripts read freshly generated (and, in the overworld, freshly paved) chunks without a new world.
 *
 * <p>Why in place and not a scratch dimension: the road network, the paver, settlement population, landmarks and lore
 * placement only run in the overworld ({@code Level.OVERWORLD} checks), so a scratch dimension with the same generator
 * produces terrain + structures but no roads. A {@code skycraft:scratch} dimension (same generator, added by
 * {@code tools/dev/devserver.ps1} as a world datapack, never shipped) is still accepted as the target for terrain-only
 * checks such as {@code p2_corridors.py measure}, which wants the unpaved terrain.</p>
 *
 * <p>Steps (one per tick, server thread): read which chunks exist on disk; load the full ones (with their entities) and
 * discard their non-player entities and POIs; drop every ticket on the area and wait until no chunk holder of it is
 * left (the unload save has run); delete the chunks from the region files through the level's own IO worker (so no
 * pending write can resurrect them); load the area again (fresh generation: structures, features, the post-load
 * placers); in the overworld clear the road paving state of the area and wait until its interior is paved; save.
 * The area stays loaded (ticketed) until the next regen or {@code /skycraft-dev release}.</p>
 *
 * <p>Limits: the outer chunk ring misses decoration that neighbouring (not regenerated) chunks would have placed into
 * it and is not paved (the paver needs 3x3 full chunks), so measure the interior (quick_corridor.ps1 regenerates the
 * tile plus one chunk). Saved data keyed by place (quest state, settlement NPC "already spawned" flags, the road
 * network itself) is kept, not regenerated. No other loaded chunk may be within ~11 chunks (their neighbour holders
 * pin the area); the dev server drops the spawn-chunk ticket for that reason.</p>
 */
final class DevRegen {
    private static final java.util.function.Supplier<TicketType> TICKET = com.skycraft.perf.PerfTickets.REGEN;
    private static final long UNLOAD_MS = 180_000, LOAD_MS = 900_000, PAVE_MS = 300_000;

    enum Phase { PROBE, LOAD_OLD, UNLOAD, DELETE, REGEN, PAVE, DONE }

    /** The running regen, or null. */
    static DevRegen active;
    /** The last finished area (still ticketed), or null. */
    static DevRegen last;

    final ServerLevel level;
    final int cx1, cz1, cx2, cz2;
    final LongOpenHashSet set = new LongOpenHashSet();
    final CommandSourceStack src;
    Phase phase = Phase.PROBE;
    long phaseStart = System.currentTimeMillis(), started = phaseStart;
    final List<CompletableFuture<Optional<CompoundTag>>> reads = new ArrayList<>();
    final LongArrayList all = new LongArrayList(), existing = new LongArrayList(), full = new LongArrayList();
    final LongArrayList forced = new LongArrayList();
    boolean ticketed;
    CompletableFuture<Void> deletion;
    int entities, pois;
    long genMs, paveMs;
    String result;
    boolean failed;

    DevRegen(ServerLevel level, LongArrayList chunks, CommandSourceStack src) {
        this.level = level;
        this.src = src;
        int a = Integer.MAX_VALUE, b = Integer.MAX_VALUE, c = Integer.MIN_VALUE, d = Integer.MIN_VALUE;
        for (long ch : chunks) {
            if (!set.add(ch)) continue;
            all.add(ch);
            a = Math.min(a, ChunkPos.getX(ch));
            b = Math.min(b, ChunkPos.getZ(ch));
            c = Math.max(c, ChunkPos.getX(ch));
            d = Math.max(d, ChunkPos.getZ(ch));
        }
        cx1 = a;
        cz1 = b;
        cx2 = c;
        cz2 = d;
    }

    /** The chunks of a block rectangle. */
    static LongArrayList rect(int x1, int z1, int x2, int z2) {
        LongArrayList l = new LongArrayList();
        for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++) {
            for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) l.add(ChunkPos.pack(cx, cz));
        }
        return l;
    }

    /** Chunk runs {@code cz:cx1-cx2,cx1-cx2;cz:...} (tools/qa/corridor_tile.py --region). */
    static LongArrayList runs(String spec) {
        LongArrayList l = new LongArrayList();
        for (String row : spec.trim().split(";")) {
            if (row.isBlank()) continue;
            int colon = row.indexOf(':');
            int cz = Integer.parseInt(row.substring(0, colon));
            for (String run : row.substring(colon + 1).split(",")) {
                int dash = run.indexOf('-', 1);
                int a = Integer.parseInt(dash < 0 ? run : run.substring(0, dash));
                int b = dash < 0 ? a : Integer.parseInt(run.substring(dash + 1));
                for (int cx = a; cx <= b; cx++) l.add(ChunkPos.pack(cx, cz));
            }
        }
        return l;
    }

    static String start(ServerLevel level, LongArrayList chunks, CommandSourceStack src) {
        if (active != null) return "A regen is already running: " + active.describe();
        if (!Holders.available()) return "Cannot observe chunk unloading in this runtime; regeneration was not started.";
        DevRegen r = new DevRegen(level, chunks, src);
        if (r.all.isEmpty()) return "No chunks given";
        if (r.all.size() > 4096) return "Area too large: " + r.all.size() + " chunks (max 4096)";
        release();
        active = r;
        Skycraft.LOGGER.info("[dev] regen {} started", r.describe());
        return "Regenerating " + r.describe();
    }

    /** Drops the tickets of the last regen area so it can unload. */
    static void release() {
        if (last != null) {
            last.untick();
            last = null;
        }
    }

    String describe() {
        return String.format("%s chunks %d,%d..%d,%d (%d chunks, blocks %d %d %d %d) phase %s", level.dimension().identifier(),
                cx1, cz1, cx2, cz2, all.size(), cx1 * 16, cz1 * 16, cx2 * 16 + 15, cz2 * 16 + 15, phase);
    }

    /** All 8 neighbours are regenerated too, so the paver can pave the chunk. */
    private boolean interior(long c) {
        int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!set.contains(ChunkPos.pack(x + dx, z + dz))) return false;
        return true;
    }

    private void ticket(LongArrayList chunks) {
        for (long c : chunks) level.getChunkSource().addTicketWithRadius(TICKET.get(),  ChunkPos.unpack(c),  0);
        ticketed = true;
    }

    void untick() {
        if (!ticketed) return;
        for (long c : all) level.getChunkSource().removeTicketWithRadius(TICKET.get(),  ChunkPos.unpack(c),  0);
        ticketed = false;
    }

    private void next(Phase p) {
        Skycraft.LOGGER.info("[dev] regen: {} -> {} after {} ms", phase, p, System.currentTimeMillis() - phaseStart);
        phase = p;
        phaseStart = System.currentTimeMillis();
    }

    private void fail(String why) {
        untick();
        restoreForced();
        failed = true;
        result = "Regen FAILED in " + phase + ": " + why;
        phase = Phase.DONE;
        Skycraft.LOGGER.error("[dev] {}", result);
    }

    private void restoreForced() {
        for (long c : forced) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), true);
        forced.clear();
    }

    /** Aborts (timeout of the inbox job). */
    void abort(String why) {
        if (phase != Phase.DONE) fail(why);
        active = null;
    }

    /** One step; called every server tick while active. */
    void tick() {
        try {
            step();
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("[dev] regen failed", e);
            fail(e.toString());
        }
        if (phase == Phase.DONE) {
            active = null;
            if (!failed) last = this;
            Skycraft.LOGGER.info("[dev] {}", result);
            String msg = result;
            src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(msg), false);
        }
    }

    private void step() {
        long spent = System.currentTimeMillis() - phaseStart;
        ChunkMap map = level.getChunkSource().chunkMap;
        switch (phase) {
            case PROBE -> {
                if (reads.isEmpty()) {
                    int released=com.skycraft.quest.story.StartCottage.releaseCompletedPreparation(level);
                    if(released>0) Skycraft.LOGGER.info("[dev] Released {} completed cottage preload tickets before regeneration",released);
                    // /setworldspawn re-adds the spawn-chunk ticket (radius 11) around the new spawn: drop it again
                    level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_SPAWN,  ChunkPos.containing(level.getRespawnData().pos()), 3);
                    for (long c : level.getForceLoadedChunks()) if (inside(c)) forced.add(c);
                    for (long c : forced) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
                    for (long c : all) reads.add(map.read(ChunkPos.unpack(c)));
                    return;
                }
                for (CompletableFuture<Optional<CompoundTag>> f : reads) if (!f.isDone()) return;
                for (int i = 0; i < all.size(); i++) {
                    Optional<CompoundTag> tag = reads.get(i).join();
                    if (tag.isEmpty()) continue;
                    existing.add(all.getLong(i));
                    if (tag.get().getStringOr("Status","").endsWith("full")) full.add(all.getLong(i));
                }
                if (existing.isEmpty()) {
                    ticket(all);
                    next(Phase.REGEN);
                } else if (!full.isEmpty()) {
                    ticket(full);
                    next(Phase.LOAD_OLD);
                } else {
                    next(Phase.UNLOAD);
                }
            }
            case LOAD_OLD -> {
                if (spent > LOAD_MS) {
                    fail("old chunks did not load");
                    return;
                }
                for (long c : full) {
                    if (level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) == null || !level.areEntitiesLoaded(c)) return;
                }
                AABB box = new AABB(cx1 * 16, level.getMinY(), cz1 * 16, cx2 * 16 + 16, (level.getMaxY()+1), cz2 * 16 + 16);
                for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player))) {
                    if (e.getVehicle() instanceof Player || e.isRemoved()) continue;
                    e.discard();
                    entities++;
                }
                PoiManager poi = level.getPoiManager();
                for (long c : full) {
                    for (var pos : poi.getInChunk(t -> true, ChunkPos.unpack(c), PoiManager.Occupancy.ANY).map(PoiRecord::getPos).toList()) {
                        poi.remove(pos);
                        pois++;
                    }
                }
                untick();
                next(Phase.UNLOAD);
            }
            case UNLOAD -> {
                // Preparation may finish after PROBE; release only its completed, owned tickets.
                com.skycraft.quest.story.StartCottage.releaseCompletedPreparation(level);
                LongArrayList pinned = new LongArrayList();
                for (long c : all) if (Holders.held(map, c)) pinned.add(c);
                if (!pinned.isEmpty()) {
                    if (spent > UNLOAD_MS) {
                        fail(pinned.size() + " chunks stay loaded (e.g. " + ChunkPos.unpack(pinned.getLong(0))
                                + "): another loaded area, a player or a ticket within ~11 chunks pins them");
                    }
                    return;
                }
                List<CompletableFuture<Void>> writes = new ArrayList<>();
                for (long c : existing) writes.add(map.write(ChunkPos.unpack(c), (CompoundTag) null));
                deletion = CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                        .thenCompose(ignored -> map.synchronize(true));
                next(Phase.DELETE);
            }
            case DELETE -> {
                if (!deletion.isDone()) {
                    if (spent > UNLOAD_MS) fail("region deletion/flush did not finish");
                    return;
                }
                deletion.join(); // already completed; propagates every delete/flush failure before fresh generation
                for (long c : all) {
                    if (Holders.held(map, c)) {
                        fail("chunk " + ChunkPos.unpack(c) + " was loaded again while deleting");
                        return;
                    }
                }
                Skycraft.LOGGER.info("[dev] regen: deleted {} chunks ({} full; {} entities, {} POIs discarded)", existing.size(),
                        full.size(), entities, pois);
                ticket(all);
                next(Phase.REGEN);
            }
            case REGEN -> {
                if (spent > LOAD_MS) {
                    fail("generation did not finish");
                    return;
                }
                for (long c : all) if (level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) == null) return;
                genMs = spent;
                restoreForced();
                if (level.dimension() == Level.OVERWORLD && Roads.requeue(level, all)) next(Phase.PAVE);
                else finish();
            }
            case PAVE -> {
                if (spent < 500) return;
                if (spent > PAVE_MS) {
                    fail("paving did not finish (paver queue " + Roads.queued() + ")");
                    return;
                }
                for (long c : all) if (interior(c) && Roads.needsPaving(level, c)) return;
                paveMs = spent;
                finish();
            }
            case DONE -> {
            }
        }
    }

    private boolean inside(long c) {
        return set.contains(c);
    }

    private void finish() {
        long t0 = System.currentTimeMillis();
        level.getServer().saveAllChunks(true, true, false);
        result = String.format("Regen done: %s; deleted %d old chunks (%d full, %d entities, %d POIs), generated in %.1f s, "
                        + "paved in %.1f s, saved in %.1f s, total %.1f s", describe().replace(" phase " + phase, ""),
                existing.size(), full.size(), entities, pois, genMs / 1000.0, paveMs / 1000.0,
                (System.currentTimeMillis() - t0) / 1000.0, (System.currentTimeMillis() - started) / 1000.0);
        phase = Phase.DONE;
    }

    /** Dev-only lookups in both the official-mapped dev JVM and Forge's SRG packaged JVM. */
    static final class Holders {
        private static final Method VISIBLE = method("getVisibleChunkIfPresent", "m_140327_"),
                UPDATING = method("getUpdatingChunkIfPresent", "m_140174_");
        private static final Field PENDING = field();

        private static Method method(String official, String runtime) {
            for (String name : new String[]{official, runtime}) {
                try {
                    Method m = ChunkMap.class.getDeclaredMethod(name, long.class);
                    m.setAccessible(true);
                    return m;
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
            Skycraft.LOGGER.warn("[dev] no ChunkMap.{}: regeneration disabled", official);
            return null;
        }

        private static Field field() {
            for (String name : new String[]{"pendingUnloads", "f_140131_"}) {
                try {
                    Field f = ChunkMap.class.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
            Skycraft.LOGGER.warn("[dev] no ChunkMap.pendingUnloads: regeneration disabled");
            return null;
        }

        static boolean available() {
            return VISIBLE != null && UPDATING != null && PENDING != null;
        }

        /** Never infer completion from elapsed time: saves and all holders must finish. */
        static boolean held(ChunkMap map, long c) {
            if (!available()) throw new IllegalStateException("Chunk unload observation unavailable");
            try {
                if ((ChunkHolder) VISIBLE.invoke(map, c) != null || (ChunkHolder) UPDATING.invoke(map, c) != null) return true;
                return ((java.util.Map<?, ?>) PENDING.get(map)).containsKey(c);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot observe pending chunk saves; refusing to delete chunks", e);
            }
        }
    }

    /**
     * The road package's paving state (package-private there; owned by another workstream, so read by reflection
     * instead of widening its API). Without it the regen falls back to {@code /skycraft-roads repave} + a fixed wait.
     */
    static final class Roads {
        private static Method needs, queueLoaded, queued, get;

        static {
            try {
                Class<?> data = Class.forName("com.skycraft.roads.RoadsData");
                get = data.getMethod("get", ServerLevel.class);
                needs = data.getDeclaredMethod("needsPaving", long.class);
                needs.setAccessible(true);
                Class<?> net = Class.forName("com.skycraft.roads.RoadNetwork");
                queueLoaded = net.getDeclaredMethod("queueLoaded", ServerLevel.class, data, LongOpenHashSet.class);
                queueLoaded.setAccessible(true);
                queued = Class.forName("com.skycraft.roads.Paver").getDeclaredMethod("queued");
                queued.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Skycraft.LOGGER.warn("[dev] road paving hooks unavailable ({}); regen uses /skycraft-roads repave", e.toString());
                needs = null;
            }
        }

        /** Clears the paved flag of the chunks and queues them; true if paving can be awaited per chunk. */
        static boolean requeue(ServerLevel level, LongArrayList chunks) {
            if (needs == null) {
                level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(), "skycraft-roads repave");
                return false;
            }
            try {
                queueLoaded.invoke(null, level, get.invoke(null, level), new LongOpenHashSet(chunks));
                return true;
            } catch (ReflectiveOperationException e) {
                Skycraft.LOGGER.warn("[dev] requeue failed", e);
                return false;
            }
        }

        static boolean needsPaving(ServerLevel level, long chunk) {
            try {
                return (boolean) needs.invoke(get.invoke(null, level), chunk);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        static int queued() {
            try {
                return queued == null ? -1 : (int) queued.invoke(null);
            } catch (ReflectiveOperationException e) {
                return -1;
            }
        }
    }
}
