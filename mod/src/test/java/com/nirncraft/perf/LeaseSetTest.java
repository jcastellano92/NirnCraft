package com.nirncraft.perf;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LeaseSetTest {
    @Test void overlappingOwnersActivateOnceAndOnlyLastReleaseDeactivates() {
        LeaseSet<Long, String> leases = new LeaseSet<>(); AtomicInteger nativeTickets = new AtomicInteger();
        assertTrue(leases.acquire(7L, "trip-a", nativeTickets::incrementAndGet));
        assertTrue(leases.acquire(7L, "trip-b", nativeTickets::incrementAndGet));
        assertFalse(leases.acquire(7L, "trip-a", nativeTickets::incrementAndGet));
        assertEquals(1, nativeTickets.get());
        assertTrue(leases.release(7L, "trip-a", nativeTickets::decrementAndGet));
        assertEquals(1, nativeTickets.get()); assertFalse(leases.isEmpty());
        assertFalse(leases.release(7L, "trip-a", nativeTickets::decrementAndGet));
        assertTrue(leases.release(7L, "trip-b", nativeTickets::decrementAndGet));
        assertEquals(0, nativeTickets.get()); assertTrue(leases.isEmpty());
    }
    @Test void nativeAcquisitionFailureNeverLeavesPhantomLease() {
        LeaseSet<Integer, Integer> leases = new LeaseSet<>();
        assertThrows(IllegalStateException.class, () -> leases.acquire(1, 1, () -> { throw new IllegalStateException("closed level"); }));
        assertTrue(leases.isEmpty());
        assertTrue(leases.acquire(1, 2, () -> {}));
        assertTrue(leases.release(1, 2, () -> {})); assertTrue(leases.isEmpty());
    }
}
