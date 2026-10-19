package com.nirncraft.perf;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.network.chat.Component;
import com.nirncraft.travel.TravelNetworks;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real Prewarm ticket acquisition/release against a recording public chunk-source API. */
@org.junit.jupiter.api.extension.ExtendWith(com.nirncraft.testing.BoundedServerProvider.class)
class PrewarmOwnershipTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    private static final Unsafe UNSAFE;
    static {
        try { Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); UNSAFE = (Unsafe)f.get(null); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static <T> T allocate(Class<T> type) throws InstantiationException { return type.cast(UNSAFE.allocateInstance(type)); }
    public static class RecordingChunks extends ServerChunkCache {
        Set<String> tickets;
        int removes;
        LevelChunk ready;
        @Override public LevelChunk getChunkNow(int x, int z) { return ready; }
        public RecordingChunks() { super(null, null, null, null, null, null, 0, 0, false, null); }
        @Override public void addTicketWithRadius(TicketType type, ChunkPos pos, int distance) {
            tickets.add(pos.pack() + ":" + distance);
        }
        @Override public void removeTicketWithRadius(TicketType type, ChunkPos pos, int distance) {
            removes++;
            assertTrue(tickets.remove(pos.pack() + ":" + distance), "Removing an absent or another job's ticket");
        }
    }
    public static class RecordingLevel extends ServerLevel {
        RecordingChunks chunks;
        MinecraftServer server;
        public RecordingLevel() { super(null, null, null, null, null, null, false, 0, List.of(), false); }
        @Override public ServerChunkCache getChunkSource() { return chunks; }
        @Override public MinecraftServer getServer() { return server; }
    }
    public static class RecordingPlayer extends ServerPlayer {
        UUID identity;
        RecordingLevel level;
        public RecordingPlayer() { super(null, null, null, null); }
        @Override public UUID getUUID() { return identity; }
        @Override public boolean isAlive() { return true; }
        @Override public ServerLevel level() { return level; }
    }
    private static RecordingPlayer player(RecordingLevel level) throws Exception {
        var player = allocate(RecordingPlayer.class); player.identity = UUID.randomUUID(); player.level = level;
        return player;
    }
    @SuppressWarnings("unchecked") private static List<Object> jobs() throws Exception {
        Field field = Prewarm.class.getDeclaredField("JOBS"); field.setAccessible(true);
        return (List<Object>)field.get(null);
    }
    @FunctionalInterface private interface CheckedTest { void run() throws Exception; }
    /** Native server ticks also own JOBS: synthetic setup, assertions and release must be one atomic server task. */
    private static void onServer(MinecraftServer server, CheckedTest test) throws Exception {
        var result = new java.util.concurrent.CompletableFuture<Void>();
        server.execute(() -> {
            try {
                assertTrue(server.isSameThread(), "Prewarm assertions must run between native ticks");
                assertTrue(jobs().isEmpty(), "Ephemeral data-only server unexpectedly has a pending travel job");
                try {
                    test.run();
                } finally {
                    for (Object job : List.copyOf(jobs())) release(job);
                    jobs().clear();
                }
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }
    private static void release(Object job) throws Exception {
        var method = Prewarm.class.getDeclaredMethod("release", job.getClass()); method.setAccessible(true); method.invoke(null, job);
    }
    private static RecordingLevel level() throws Exception {
        var level = allocate(RecordingLevel.class);
        level.chunks = allocate(RecordingChunks.class); level.chunks.tickets = new HashSet<>();
        level.server = allocate(DedicatedServer.class);
        return level;
    }
    @Test void overlapKeepsSecondOwnerAndReleasesEachExactlyOnce(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            var level = level();
            Prewarm.hold(level, 0, 0, 0, 20);
            Prewarm.hold(level, 0, 0, 0, 40);
            assertEquals(1, level.chunks.tickets.size());
            var first = jobs().get(0); var second = jobs().get(1);
            release(first); assertEquals(1, level.chunks.tickets.size());
            release(first); assertEquals(0, level.chunks.removes);
            release(second); assertTrue(level.chunks.tickets.isEmpty());
            assertEquals(1, level.chunks.removes);
        });
    }
    @Test void identicalCoordinatesInDifferentLevelsRemainIndependent(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            var a = level(); var b = level();
            Prewarm.hold(a, 0, 0, 0, 20); Prewarm.hold(b, 0, 0, 0, 20);
            release(jobs().get(0));
            assertEquals(0, a.chunks.tickets.size()); assertEquals(1, b.chunks.tickets.size());
            release(jobs().get(1)); assertEquals(0, b.chunks.tickets.size());
        });
    }
    @Test void aBusyJourneyCannotExecuteASecondColdDestination(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            var level = level(); var player = player(level); var arrivals = new AtomicInteger();
            assertTrue(TravelNetworks.prewarmTo(player, 16000, 16000, Component.literal("first"), arrivals::incrementAndGet));
            assertTrue(Prewarm.busy(player.getUUID()));
            assertFalse(TravelNetworks.prewarmTo(player, 32000, 32000, Component.literal("second"), arrivals::incrementAndGet));
            assertEquals(0, arrivals.get()); assertEquals(1, jobs().size());
        });
    }
    @Test void aBusyJourneyCannotBypassTheGuardWithAnAlreadyReadyDestination(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            var level = level(); var player = player(level); var arrivals = new AtomicInteger();
            assertTrue(TravelNetworks.prewarmTo(player, 16000, 16000, Component.literal("first"), arrivals::incrementAndGet));
            level.chunks.ready = allocate(LevelChunk.class);
            assertFalse(TravelNetworks.prewarmTo(player, 0, 0, Component.literal("ready second"), arrivals::incrementAndGet));
            assertEquals(0, arrivals.get()); assertEquals(1, jobs().size());
        });
    }
    @Test void aReadyJourneyExecutesOnceWithoutEnqueueing(MinecraftServer server) throws Exception {
        onServer(server, () -> {
            var level = level(); var player = player(level); var arrivals = new AtomicInteger();
            level.chunks.ready = allocate(LevelChunk.class);
            assertTrue(TravelNetworks.prewarmTo(player, 0, 0, Component.literal("ready"), arrivals::incrementAndGet));
            assertEquals(1, arrivals.get()); assertEquals(0, jobs().size());
        });
    }

}
