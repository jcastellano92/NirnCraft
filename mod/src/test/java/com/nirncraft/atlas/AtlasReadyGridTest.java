package com.nirncraft.atlas;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Readiness's grid part (AtlasReady.gridPending) and the drain guard (AtlasWaitGuard). */
class AtlasReadyGridTest {
    static final long S = 1_000_000_000L, STALL = AtlasWaitGuard.STALL_NS;

    private static JsonObject grid(String fields) {
        return JsonParser.parseString("{\"settled\":true,\"pending\":0,\"waiting\":0,\"coarseWaiting\":0,\"pendingSections\":0,"
                + "\"levels\":[{\"level\":1,\"pending\":0}]," + fields + "}").getAsJsonObject();
    }

    @Test
    void sectionsPastLevelZerosReachDoNotHoldReadiness() {
        // the owner's hold (atlas-profile.log 09:30:35): a camera at y 522, level 0 reaching 152 blocks, none of the
        // generator's 10280 waiting sections within it (nearestMissing 0); 3083 of 180224 slots used
        assertEquals("", AtlasReady.gridPending(grid("\"resident\":3083,\"slotsTotal\":180224,\"gpuWaiting\":10280,"
                + "\"nearestMissing\":0,\"cutoff\":152,\"reach\":152")));
        // a full pool: the nearest waiting one is past its cut-off (it takes it when the camera comes near)
        assertEquals("", AtlasReady.gridPending(grid("\"gpuWaiting\":500,\"nearestMissing\":200,\"cutoff\":120,\"reach\":400")));
    }

    @Test
    void sectionsLevelZeroWouldTakeHoldIt() {
        assertEquals("grid gpuWaiting 7 (the nearest 40 blocks away)",
                AtlasReady.gridPending(grid("\"gpuWaiting\":7,\"nearestMissing\":40,\"cutoff\":152,\"reach\":152")));
        assertEquals("grid pending 3", AtlasReady.gridPending(withPending()));
        // (an older library without nearestMissing: as before)
        assertTrue(AtlasReady.gridPending(grid("\"waiting\":4")).startsWith("grid waiting 4"));
    }

    private static JsonObject withPending() {
        JsonObject v = grid("\"gpuWaiting\":0,\"nearestMissing\":0,\"cutoff\":152");
        v.addProperty("pending", 3);
        return v;
    }

    @Test
    void guardReleasesAWaitThatDoesNotDrain() {
        AtlasWaitGuard g = new AtlasWaitGuard();
        long t = 1000 * S;
        assertEquals(1, g.blocks(100, t));
        assertEquals(1, g.blocks(90, t + S));                      // (drains: the clock starts again)
        for (long s = 2; s * S < STALL + S; s++) assertEquals(1, g.blocks(95 + s, t + s * S));  // (grows: no drain)
        assertEquals(-1, g.blocks(200, t + STALL + S + S / 2));    // (released by this call)
        assertEquals(0, g.blocks(200, t + STALL + 2 * S));
        assertEquals(0, g.blocks(0, t + STALL + 3 * S));           // (empty: re-armed)
        assertEquals(1, g.blocks(5, t + STALL + 4 * S));
    }

    @Test
    void aGuardWithItsOwnStall() {  // (the near field's boost: released after 10 s without a chunk coming up)
        AtlasWaitGuard g = new AtlasWaitGuard(10 * S);
        long t = 1000 * S;
        assertEquals(1, g.blocks(40, t));
        for (long s = 1; s < 10; s++) assertEquals(1, g.blocks(40, t + s * S));
        assertEquals(-1, g.blocks(40, t + 10 * S));
        assertTrue(AtlasClientGen.nearCircle(3, 4, 0, 0, 5) && !AtlasClientGen.nearCircle(4, 4, 0, 0, 5));
    }

    @Test
    void guardClockRunsOnlyWhileWaitedOn() {
        AtlasWaitGuard g = new AtlasWaitGuard();
        long t = 1000 * S;
        assertEquals(1, g.blocks(10, t));
        // (an earlier condition held readiness for a minute: not this wait's stall)
        assertEquals(1, g.blocks(10, t + 2 * STALL));
        assertEquals(1, g.blocks(10, t + 2 * STALL + S));
    }
}
