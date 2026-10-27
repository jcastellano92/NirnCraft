package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AtlasReleaseGateTest {
    @Test
    void closeRejectsNewNativeUsersAndWaitsForExistingCalls() throws Exception {
        var gate = new AtlasReleaseGate();
        var first = gate.enter(); var second = gate.enter();
        assertNotNull(first); assertNotNull(second);
        gate.closeAdmission(); assertNull(gate.enter());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var done = pool.submit(gate::awaitDrained);
            assertThrows(java.util.concurrent.TimeoutException.class, () -> done.get(50, TimeUnit.MILLISECONDS));
            first.close(); first.close();
            assertThrows(java.util.concurrent.TimeoutException.class, () -> done.get(50, TimeUnit.MILLISECONDS));
            second.close(); assertFalse(done.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedDrainStillWaitsUntilLeaseLeaves() throws Exception {
        var gate = new AtlasReleaseGate(); var lease = gate.enter(); gate.closeAdmission();
        var ready = new CountDownLatch(1); var result = new AtomicBoolean();
        Thread thread = new Thread(() -> { ready.countDown(); result.set(gate.awaitDrained()); });
        thread.start(); assertTrue(ready.await(2, TimeUnit.SECONDS)); thread.interrupt();
        thread.join(50); assertTrue(thread.isAlive());
        lease.close(); thread.join(2000); assertFalse(thread.isAlive()); assertTrue(result.get());
    }

    @Test
    void acquisitionRacingCancellationIsEitherRejectedOrDrained() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 200; i++) {
                var gate = new AtlasReleaseGate();
                var go = new CountDownLatch(1);
                var acquired = pool.submit(() -> { go.await(); return gate.enter(); });
                var closed = pool.submit(() -> { go.await(); gate.closeAdmission(); return null; });
                go.countDown();
                var lease = acquired.get(2, TimeUnit.SECONDS);
                closed.get(2, TimeUnit.SECONDS);
                assertNull(gate.enter());
                var drained = pool.submit(gate::awaitDrained);
                if (lease != null) {
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> drained.get(1, TimeUnit.MILLISECONDS));
                    lease.close();
                }
                assertFalse(drained.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void copyWaitRunsOffCallerAndRetainsFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var done = AtlasReleaseGate.waitCopyAsync(() -> {
            calls.incrementAndGet(); entered.countDown();
            try { unblock.await(); return true; }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(done.isDone());
            assertEquals(1, calls.get());
        } finally { unblock.countDown(); }
        done.get(2, TimeUnit.SECONDS);
        var failed = AtlasReleaseGate.waitCopyAsync(() -> { throw new IllegalStateException("injected native error"); });
        assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));
    }
}
