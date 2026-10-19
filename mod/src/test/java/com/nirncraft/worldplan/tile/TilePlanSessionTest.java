package com.nirncraft.worldplan.tile;

import com.nirncraft.bake.Bake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TilePlanSessionTest {
    private TilePlanner.ToolPreview preview;
    @org.junit.jupiter.api.BeforeEach void previewDormantPlanner() { preview=TilePlanner.previewForTools(); }
    @org.junit.jupiter.api.AfterEach void closeDormantPreview() { preview.close(); }

    @BeforeAll static void pinBake() throws Exception {
        try (DataInputStream in = new DataInputStream(Bake.class.getResourceAsStream("/nirncraft/baked/roadplan.bin"))) {
            Field field = Bake.class.getDeclaredField("inputs"); field.setAccessible(true);
            if (field.get(null) == null) field.set(null, in.readUTF());
        }
    }
    @AfterEach void detach() { TilePlanner.detach(); }

    @Test void zeroIsAValidRuntimeSeed() {
        TilePlan plan = TilePlanner.planSync(0L, 4, 0);
        assertSame(plan, TilePlanner.getIfReady(17000, 500));
    }

    @Test void differentSeedSavesRejectOldCompletion() throws Exception { switchWhileLoading(17L, 18L); }
    @Test void sameSeedSavesRejectOldCompletion() throws Exception { switchWhileLoading(17L, 17L); }

    private void switchWhileLoading(long beforeSeed, long afterSeed) throws Exception {
        TilePlan oldPlan = TilePlanner.buildTilePlan(beforeSeed, 4, 0);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicInteger oldWrites = new AtomicInteger(), newWrites = new AtomicInteger();
        Object oldSave = new Object();
        TilePlanner.attach(beforeSeed, oldSave, (seed, x, z) -> {
            if (x != 4 || z != 0) return null;
            started.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            finally { exited.countDown(); }
            return oldPlan;
        }, (key, plan) -> oldWrites.incrementAndGet());
        var pending = TilePlanner.ensurePlannedAsync(beforeSeed, 4, 0);
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            TilePlanner.attach(afterSeed, new Object(), null, (key, plan) -> newWrites.incrementAndGet());
            assertTrue(pending.isCancelled());
        } finally { release.countDown(); }
        assertTrue(exited.await(10, TimeUnit.SECONDS));
        // A synchronous new-save plan waits behind old workers, so all rejected publication has finished.
        TilePlan current = TilePlanner.planSync(afterSeed, 4, 0);
        assertNotSame(oldPlan, current);
        assertEquals(afterSeed, current.worldSeed());
        assertEquals(0, oldWrites.get());
        assertEquals(1, newWrites.get());
    }

    @Test void repeatedAttachmentPreservesResidentPlansButDetachReleasesThem() {
        Object owner = new Object();
        TilePlanner.attach(3L, owner, null, null);
        TilePlan plan = TilePlanner.planSync(3L, 4, 0);
        TilePlanner.attach(3L, owner, null, null);
        assertSame(plan, TilePlanner.getIfReady(17000, 500));
        TilePlanner.detach();
        assertNull(TilePlanner.getIfReady(17000, 500));
        assertFalse(TilePlanner.isCached(4, 0));
    }
}
