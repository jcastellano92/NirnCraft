package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AtlasReleaseTicketTest {
    @Test
    void startupBuildAndReadersMustDrainBeforeRenderAndCompletion() throws Exception {
        var startGate = new CountDownLatch(1);
        Thread starter = new Thread(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        starter.start();
        var build = new CompletableFuture<Object[]>();
        var readers = new AtlasReleaseGate();
        var lease = readers.enter();
        readers.closeAdmission();
        var ticket = new AtlasClient.ReleaseTicket(null, starter, new AtomicReference<>(),
                null, null, new CompletableFuture<>(), build, readers);
        ticket.launch();
        try {
            // The old release path stopped waiting after five seconds; this startup is deliberately held past it.
            assertThrows(TimeoutException.class, () -> ticket.done.get(5200, TimeUnit.MILLISECONDS));
            assertFalse(ticket.ready);
            startGate.countDown();
            starter.join(2000);
            assertFalse(starter.isAlive());
            assertFalse(ticket.ready);
            lease.close();
            assertFalse(ticket.ready); // the context build is still pending
            build.complete(new Object[]{null});
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!ticket.ready && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(ticket.ready);
            assertThrows(TimeoutException.class, () -> ticket.done.get(50, TimeUnit.MILLISECONDS));
            ticket.pumpRender();
            ticket.done.get(2, TimeUnit.SECONDS);
            assertNull(ticket.failure);
        } finally {
            startGate.countDown();
            lease.close();
            build.complete(new Object[]{null});
            ticket.renderDone.complete(null);
        }
    }

    @Test
    void failedStartupCleanupRemainsVisibleAndNeverBecomesReady() throws Exception {
        var failure = new IllegalStateException("injected cleanup failure");
        var ticket = new AtlasClient.ReleaseTicket(null, null, new AtomicReference<>(failure),
                null, null, new CompletableFuture<>(), null, null);
        ticket.launch();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> ticket.done.get(2, TimeUnit.SECONDS));
        assertNotNull(ticket.failure);
        assertFalse(ticket.ready);
        assertFalse(ticket.renderDone.isDone());
    }

    @Test
    void farAndGeneratorDrainPrecedeDetachAndRenderCompletionPrecedesClose() throws Exception {
        var farDone = new CompletableFuture<Void>();
        var farCallback = new AtomicReference<java.util.function.Consumer<Throwable>>();
        var drainEntered = new CountDownLatch(1);
        var allowDrain = new CountDownLatch(1);
        var detached = new CountDownLatch(1);
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var ticket = new AtlasClient.ReleaseTicket(null, farDone, null, farCallback::set, () -> {
            drainEntered.countDown();
            try { allowDrain.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }, detached::countDown, closed::incrementAndGet);
        ticket.launch();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (farCallback.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
            assertNotNull(farCallback.get());
            assertEquals(1, drainEntered.getCount());
            farCallback.get().accept(null);
            assertTrue(drainEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1, detached.getCount());
            allowDrain.countDown();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!ticket.ready && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(ticket.ready);
            assertEquals(1, detached.getCount());
            assertEquals(0, closed.get());
            ticket.pumpRender();
            assertTrue(detached.await(2, TimeUnit.SECONDS));
            ticket.done.get(2, TimeUnit.SECONDS);
            assertEquals(1, closed.get());
        } finally {
            var callback = farCallback.get();
            if (callback != null) callback.accept(null);
            allowDrain.countDown();
            ticket.renderDone.complete(null);
        }
    }

    @Test
    void farSourceFailurePreventsGeneratorDetachAndContextClose() throws Exception {
        var touched = new java.util.concurrent.atomic.AtomicInteger();
        var ticket = new AtlasClient.ReleaseTicket(null, new CompletableFuture<>(), null,
                callback -> callback.accept(new IllegalStateException("injected far source close failure")),
                touched::incrementAndGet, touched::incrementAndGet, touched::incrementAndGet);
        ticket.launch();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> ticket.done.get(2, TimeUnit.SECONDS));
        assertEquals(0, touched.get());
        assertFalse(ticket.ready);
    }

    @Test
    void starterWaitingForFarExitDoesNotDeadlockShutdownRelease() throws Exception {
        var farDone = new CompletableFuture<Void>();
        var farCallback = new AtomicReference<java.util.function.Consumer<Throwable>>();
        var starterEntered = new CountDownLatch(1);
        Thread starter = new Thread(() -> { starterEntered.countDown(); farDone.join(); });
        starter.setDaemon(true);
        var ticket = new AtlasClient.ReleaseTicket(starter, farDone, null, farCallback::set,
                () -> {}, () -> {}, () -> {});
        try {
            starter.start();
            assertTrue(starterEntered.await(2, TimeUnit.SECONDS));
            ticket.launch();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (farCallback.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
            assertNotNull(farCallback.get());
            assertTrue(starter.isAlive());
            farCallback.get().accept(null);
            starter.join(2000);
            assertFalse(starter.isAlive());
            ticket.renderDone.complete(null);
            ticket.done.get(2, TimeUnit.SECONDS);
        } finally {
            farDone.complete(null);
            ticket.renderDone.complete(null);
        }
    }
    @Test
    void noFarCompletesAndRenderDetachFailureRetainsContext() throws Exception {
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var ticket = new AtlasClient.ReleaseTicket(null, new CompletableFuture<>(), null,
                callback -> callback.accept(null), () -> {},
                () -> { throw new IllegalStateException("injected GL detach failure"); }, closed::incrementAndGet);
        ticket.launch();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!ticket.ready && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(ticket.ready);
            ticket.pumpRender();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> ticket.done.get(2, TimeUnit.SECONDS));
            assertEquals(0, closed.get());
            assertFalse(ticket.renderDone.isDone());
            assertNotNull(ticket.failure);
        } finally {
            ticket.renderDone.completeExceptionally(new IllegalStateException("test cleanup"));
        }
    }

    @Test
    void noFarProductionTicketCompletesAfterRenderPump() throws Exception {
        var ticket = new AtlasClient.ReleaseTicket(null, null, new AtomicReference<>(),
                null, null, new CompletableFuture<>(), null, null);
        ticket.launch();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!ticket.ready && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(ticket.ready);
            ticket.pumpRender();
            ticket.done.get(2, TimeUnit.SECONDS);
        } finally {
            ticket.renderDone.complete(null);
        }
    }
}
