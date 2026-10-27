package com.nirncraft.atlas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AtlasShutdownResourcesTest {
    @Test
    void successfulTerminalCloseIsIdempotent() {
        var calls = new AtomicInteger();
        var resources = new AtlasClient.ShutdownResources(calls::incrementAndGet, calls::incrementAndGet);
        resources.close();
        resources.close();
        assertEquals(2, calls.get());
    }

    @Test
    void failureStillAttemptsOtherOwnersAndCannotBecomeSuccessfulOnRetry() {
        var calls = new AtomicInteger();
        var first = new IllegalStateException("first owner refused close");
        var second = new IllegalStateException("second owner refused close");
        var resources = new AtlasClient.ShutdownResources(
                () -> { calls.incrementAndGet(); throw first; },
                calls::incrementAndGet,
                () -> { calls.incrementAndGet(); throw second; });
        assertSame(first, assertThrows(IllegalStateException.class, resources::close).getCause());
        assertSame(first, assertThrows(IllegalStateException.class, resources::close).getCause());
        assertEquals(3, calls.get());
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
    }
}
