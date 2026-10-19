package com.nirncraft.worldplan.tile;

import com.nirncraft.bake.Bake;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TilePlanPublicationTest {
    private static final long SEED = 771L;
    private static TilePlan reference;

    @BeforeAll static void prepare() throws Exception {
        try (DataInputStream in = new DataInputStream(Bake.class.getResourceAsStream("/nirncraft/baked/roadplan.bin"))) {
            Field f = Bake.class.getDeclaredField("inputs"); f.setAccessible(true);
            if (f.get(null) == null) f.set(null, in.readUTF());
        }
        reference = TilePlanner.buildTilePlan(SEED, 4, 0);
    }
    @AfterEach void detach() { TilePlanner.detach(); }

    private static final class Save {
        final Thread thread = Thread.currentThread();
        final TilePlanData data = new TilePlanData();
        final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        Save(long seed) { data.bind(seed, () -> Thread.currentThread() == thread, tasks::add); }
        void runTasks() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
    }

    @Test void completedWorkerOnlyQueuesBytesAndOwningThreadPublishesDirtyState() {
        var save = new Save(SEED);
        TilePlanner.planSync(SEED, 4, 0);
        assertEquals(0, save.data.size()); assertFalse(save.data.isDirty());
        assertFalse(save.tasks.isEmpty());
        save.runTasks();
        assertEquals(1, save.data.size()); assertTrue(save.data.isDirty());
        assertEquals(SEED, save.data.readTile(SEED, 4, 0).worldSeed());
    }

    @Test void autosaveDrainsCompletedBytesAndAnEmptyScheduledDrainDoesNotRedirtyIt() {
        var save = new Save(SEED);
        TilePlanner.planSync(SEED, 4, 0);
        CompoundTag snapshot = save.data.save(new CompoundTag());
        assertEquals(1, snapshot.getListOrEmpty("Tiles").size());
        save.data.setDirty(false); // DimensionDataStorage clears this after the actual save.
        save.runTasks();
        assertFalse(save.data.isDirty());
        TilePlanner.planSync(SEED, 5, 0);
        assertEquals(1, save.data.size()); assertFalse(save.data.isDirty());
        save.runTasks();
        assertEquals(2, save.data.size()); assertTrue(save.data.isDirty());
    }

    @Test void serializationCompletingAcrossAnAutosaveCannotLoseItsDirtyNotification() throws Exception {
        var save = new Save(SEED);
        Object token = TilePlanner.tokenFor(save.data);
        CountDownLatch serialized = new CountDownLatch(1), release = new CountDownLatch(1);
        var worker = CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = reference.write(); serialized.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for autosave");
                return save.data.enqueueSerialized(token, TilePlan.key(4, 0), bytes);
            } catch (Exception e) { throw new CompletionException(e); }
        });
        try {
            assertTrue(serialized.await(10, TimeUnit.SECONDS));
            assertTrue(save.data.save(new CompoundTag()).getListOrEmpty("Tiles").isEmpty());
            save.data.setDirty(false);
        } finally { release.countDown(); }
        assertTrue(worker.get(10, TimeUnit.SECONDS));
        assertEquals(0, save.data.size()); assertFalse(save.data.isDirty());
        save.runTasks();
        assertEquals(1, save.data.size()); assertTrue(save.data.isDirty());
    }

    @Test void queuedSameSeedWorldCompletionCannotWriteEitherSaveAfterReplacement() { replacement(SEED); }
    @Test void queuedDifferentSeedWorldCompletionCannotWriteEitherSaveAfterReplacement() { replacement(SEED + 1); }
    private void replacement(long nextSeed) {
        var old = new Save(SEED); TilePlanner.planSync(SEED, 4, 0);
        Object oldToken = TilePlanner.tokenFor(old.data);
        var current = new Save(nextSeed);
        old.runTasks();
        assertEquals(0, old.data.size()); assertEquals(0, current.data.size());
        assertFalse(old.data.isDirty()); assertFalse(current.data.isDirty());
        assertFalse(old.data.enqueueSerialized(oldToken, TilePlan.key(4, 0), new byte[]{1, 2, 3}));
        TilePlanner.planSync(nextSeed, 4, 0); current.runTasks();
        assertEquals(1, current.data.size()); assertNotNull(current.data.readTile(nextSeed, 4, 0));
        assertEquals(0, old.data.size());
    }

    @Test void clearingCacheRejectsQueuedPreviousEpochEvenWhenSaveOwnerIsUnchanged() {
        var save = new Save(SEED); TilePlanner.planSync(SEED, 4, 0);
        TilePlanner.clearCache(); save.runTasks();
        assertEquals(0, save.data.size()); assertFalse(save.data.isDirty());
        TilePlanner.planSync(SEED, 4, 0); save.runTasks();
        assertEquals(1, save.data.size()); assertTrue(save.data.isDirty());
    }

    @Test void wrongThreadCannotPublishSaveMutations() throws Exception {
        var save = new Save(SEED); TilePlanner.planSync(SEED, 4, 0);
        var wrongThread = CompletableFuture.runAsync(save.data::flush);
        CompletionException error = assertThrows(CompletionException.class, wrongThread::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals(0, save.data.size()); assertFalse(save.data.isDirty());
        save.runTasks(); assertEquals(1, save.data.size());
    }

    @Test void orderlyDetachDrainsCompletedBytesWithoutWaitingForAStillBlockedPlanner() throws Exception {
        var save = new Save(SEED);
        TilePlanner.detach(); // Rebind the same recorder with a deterministic loader barrier.
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        TilePlanner.attachQueued(SEED, save.data, (seed, tx, tz) -> {
            if (tx == 5 && tz == 0) {
                started.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Blocked loader timed out"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                finally { exited.countDown(); }
            }
            return save.data.readTile(seed, tx, tz);
        }, (token, key, plan) -> {
            try { save.data.enqueueSerialized(token, key, plan.write()); }
            catch (java.io.IOException e) { throw new CompletionException(e); }
        }, save.data::flush);
        TilePlanner.planSync(SEED, 4, 0);
        var blocked = CompletableFuture.supplyAsync(() -> TilePlanner.planSync(SEED, 5, 0));
        var watchdog = Executors.newSingleThreadScheduledExecutor();
        var safetyRelease = watchdog.schedule(release::countDown, 5, TimeUnit.SECONDS);
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            long begin = System.nanoTime();
            TilePlanner.detach();
            assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(2), "Detach must not wait for the blocked worker");
            assertEquals(1, save.data.size()); assertTrue(save.data.isDirty());
            assertNull(TilePlanner.tokenFor(save.data));
            assertThrows(CompletionException.class, blocked::join);
        } finally {
            release.countDown(); safetyRelease.cancel(false); watchdog.shutdownNow();
        }
        assertTrue(exited.await(10, TimeUnit.SECONDS));
        save.runTasks();
        assertEquals(1, save.data.size()); assertNull(save.data.readTile(SEED, 5, 0));
    }
}
