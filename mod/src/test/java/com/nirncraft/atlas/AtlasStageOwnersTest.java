package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AtlasStageOwnersTest {
    @Test
    void constructionFailureKeepsAllocatedStageForExactlyOneDetach() {
        var stages = new AtlasStageOwners<Object, Object, Object>();
        Object key = new Object(), resource = new Object();
        assertThrows(IllegalStateException.class, () -> stages.prepare(key, resource, ignored -> {
            throw new IllegalStateException("injected owner construction failure");
        }));
        var closed = new AtomicInteger();
        stages.release(key, ignored -> fail("owner was not constructed"), value -> {
            assertSame(resource, value); closed.incrementAndGet();
        });
        stages.release(key, ignored -> fail("already released"), ignored -> fail("already released"));
        assertEquals(1, closed.get());
    }

    @Test
    void successfulOwnerAndFailedCloseRemainOwned() {
        var stages = new AtlasStageOwners<Object, Object, Object>();
        Object key = new Object(), resource = new Object(), owner = new Object();
        assertSame(owner, stages.prepare(key, resource, ignored -> owner));
        assertThrows(IllegalStateException.class, () -> stages.release(key, ignored -> {
            throw new IllegalStateException("injected close failure");
        }, ignored -> fail("prepared owner must own the resource")));
        assertSame(owner, stages.get(key));
        var calledAgain = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> stages.release(key,
                ignored -> calledAgain.incrementAndGet(), ignored -> calledAgain.incrementAndGet()));
        assertEquals(0, calledAgain.get());
    }

    @Test
    void concurrentReleaseClosesOnceAndDuplicatePrepareIsRejected() throws Exception {
        var stages = new AtlasStageOwners<Object, Object, Object>();
        Object key = new Object(), owner = new Object();
        stages.prepare(key, new Object(), ignored -> owner);
        assertThrows(IllegalStateException.class, () -> stages.prepare(key, new Object(), ignored -> new Object()));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var allow = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var rejectedCloses = new AtomicInteger();
        java.util.function.Consumer<Object> close = ignored -> {
            calls.incrementAndGet(); entered.countDown();
            try { allow.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        };
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            try {
            var first = pool.submit(() -> stages.release(key, close, ignored -> rejectedCloses.incrementAndGet()));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var second = pool.submit(() -> stages.release(key, close, ignored -> rejectedCloses.incrementAndGet()));
            allow.countDown();
            first.get(2, java.util.concurrent.TimeUnit.SECONDS);
            second.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally { allow.countDown(); }
        }
        assertEquals(1, calls.get());
        assertEquals(1, rejectedCloses.get());
    }
}
