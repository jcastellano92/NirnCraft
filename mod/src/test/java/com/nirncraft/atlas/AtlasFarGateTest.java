package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The finer levels' horizon gate (AtlasFarGate): the count farStep can serve, and the stall guard. */
class AtlasFarGateTest {
    static final long S = 1_000_000_000L, STALL = AtlasFarGate.STALL_NS;

    @Test
    void windowOnTheTileGridHasSixteenTiles() {
        // the coarse window: 64 chunks from the camera's chunk - 32; a camera on the tile grid (the spawn near the origin)
        assertEquals(16, AtlasFarGate.tilesCovering(-32, -32, 64));
        assertEquals(20, AtlasFarGate.tilesCovering(-28, -32, 64));
        assertEquals(25, AtlasFarGate.tilesCovering(-30, -30, 64));
        // the hang: two levels on the grid serve 32, the gate waited for a fixed 2 x 5 x 5 = 50
        assertTrue(2 * AtlasFarGate.tilesCovering(-32, -32, 64) < 50);
    }

    @Test
    void wholeWhenEveryServedTileIsUp() {
        AtlasFarGate g = new AtlasFarGate();
        assertFalse(g.known());
        g.scanned(32, 32, 0, 0, 0, 7, 0);
        assertTrue(g.known());
        assertEquals(32, g.total());
        assertEquals(32, g.up(true, 1));
        assertFalse(g.released());
    }

    @Test
    void stalledGateRemainsIncompleteAfterThirtySecondsOfBlocking() {
        AtlasFarGate g = new AtlasFarGate();
        g.scanned(50, 32, 0, 18, 0, 7, 0);
        assertEquals(32, g.up(false, 100 * S));            // (not what readiness waits on: no clock)
        assertEquals(32, g.up(true, 100 * S));             // (blocking from here)
        assertEquals(32, g.up(true, 100 * S + STALL - 1));
        assertEquals(-1 - 32, g.up(true, 100 * S + STALL)); // warning carries observed count
        assertEquals(32, g.up(true, 100 * S + STALL + 1));
        assertTrue(g.why().contains("18 the coarse bake looks after"), g.why());
        g.scanned(50, 32, 0, 18, 0, 7, 200 * S);           // (the same set and count remain incomplete)
        assertEquals(32, g.up(true, 200 * S));
        g.scanned(25, 3, 22, 0, 0, 8, 210 * S);            // (a window placed afresh: counted afresh)
        assertFalse(g.released());
        assertEquals(3, g.up(true, 210 * S));
    }

    @Test
    void progressRestartsTheClock() {
        AtlasFarGate g = new AtlasFarGate();
        g.scanned(25, 10, 15, 0, 0, 1, 0);
        long t = 1000 * S;
        assertEquals(10, g.up(true, t));
        g.scanned(25, 11, 14, 0, 0, 1, t + STALL - S);
        assertEquals(11, g.up(true, t + STALL + S));
        assertEquals(-1 - 11, g.up(true, t + 2 * STALL - S));
    }
    @Test
    void reloadTurnsAlternateAndResetWhenPrerequisitesChange() {
        AtlasFarGate g = new AtlasFarGate();
        assertFalse(g.nextReloadTurn(false));
        for (int i = 0; i < 20; i++) {
            assertTrue(g.nextReloadTurn(true));
            assertFalse(g.nextReloadTurn(true));
        }
        assertTrue(g.nextReloadTurn(true));
        assertFalse(g.nextReloadTurn(false));
        assertTrue(g.nextReloadTurn(true));
        g.scanned(50, 29, 21, 0, 0, 1, 1);
        assertEquals(29, g.up(true, 2));
        g.scanned(50, 50, 0, 0, 0, 1, 3);
        assertEquals(50, g.up(true, 4));
    }
}
