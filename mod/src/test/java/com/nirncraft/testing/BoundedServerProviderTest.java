package com.nirncraft.testing;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedServerProviderTest {
    @Test void completedWorkDoesNotWaitForAPollOrStartupDeadline() throws Exception {
        assertEquals("ready", BoundedServerProvider.await(CompletableFuture.completedFuture("ready"), Duration.ZERO, () -> null));
    }

    @Test void aRealStartupFailureDoesNotWaitForTheFullTimeout() {
        var failure = assertThrows(IllegalStateException.class,
                () -> BoundedServerProvider.await(new CompletableFuture<>(), Duration.ofSeconds(90), () -> "server thread stopped"));
        assertEquals("server thread stopped",failure.getMessage());
    }

    @Test void neverCompletedStartupHasABoundedDeadline() {
        assertThrows(TimeoutException.class,
                () -> BoundedServerProvider.await(new CompletableFuture<>(), Duration.ofMillis(20), () -> null));
    }

    @Test void workerInterruptionStopsTheWait() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> BoundedServerProvider.await(new CompletableFuture<>(), Duration.ofSeconds(90), () -> null));
        } finally { Thread.interrupted(); }
    }
}
