package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;

import static com.nirncraft.atlas.AtlasMemory.MB;
import static org.junit.jupiter.api.Assertions.*;

/** atlas's memory plan (AtlasMemory) on the machines the mod targets, and its shrinking after running out. */
class AtlasMemoryTest {
    static final long GB = 1024 * MB;

    static AtlasNative.MemoryInfo discrete(long heap, long budget, long usage) {
        return new AtlasNative.MemoryInfo(heap, budget, usage, 32 * GB, 0, false, budget > 0);
    }

    @Test
    void rtx3090GetsEverything() {
        AtlasMemory.Plan p = AtlasMemory.plan(discrete(24 * GB, 22 * GB, 2 * GB), 2560, 1440, 20 * GB, 64 * GB);
        assertFalse(p.off());
        assertEquals(1.0, p.maxScale());
        assertEquals(1024, p.maxSunMap());
        assertFalse(p.quarterClouds());
        assertEquals(7, p.lods());
        assertEquals(0, p.initialTier());
        assertEquals(1.0, p.scaleKnobCeiling(), 1e-9);
        assertEquals(1.0, p.shadowKnobCeiling(), 1e-9);
    }

    @Test
    void gtx1080AtFullResolution() {
        AtlasMemory.Plan p = AtlasMemory.plan(discrete(8 * GB, 7 * GB + 200 * MB, 1500 * MB), 2560, 1440, 8 * GB, 16 * GB);
        assertEquals(1.0, p.maxScale());
        assertEquals(1024, p.maxSunMap());
        assertEquals(7, p.lods());
    }

    @Test
    void fourGigabyteCardKeepsItsScaleWithASmallerSunMap() {
        AtlasMemory.Plan p = AtlasMemory.plan(discrete(4 * GB, 3500 * MB, 1500 * MB), 1920, 1080, 6 * GB, 16 * GB);
        assertFalse(p.off());
        assertEquals(1.0, p.maxScale());
        assertEquals(512, p.maxSunMap());
        // and without the budget extension it guesses conservatively but still runs
        AtlasMemory.Plan q = AtlasMemory.plan(discrete(4 * GB, 0, 0), 1920, 1080, 6 * GB, 16 * GB);
        assertFalse(q.off());
        assertTrue(q.maxScale() >= 0.7, q.describe());
    }

    @Test
    void aTwoGigabyteCardAt1440pIsTight() {
        AtlasMemory.Plan p = AtlasMemory.plan(discrete(2 * GB, 1800 * MB, 900 * MB), 2560, 1440, 6 * GB, 16 * GB);
        assertFalse(p.off(), p.describe());
        assertTrue(p.maxScale() <= 0.55, p.describe());
        assertEquals(1, p.initialTier());  // starts with the far field at its lowest
        assertEquals(6, p.lods());
        assertTrue(p.quarterClouds());
        assertEquals(256, p.maxSunMap());
    }

    @Test
    void noRoomAtAllTurnsItOffWithAReason() {
        AtlasMemory.Plan p = AtlasMemory.plan(discrete(1 * GB, 900 * MB, 850 * MB), 1920, 1080, 4 * GB, 8 * GB);
        assertTrue(p.off());
        assertTrue(p.reason().contains("not enough video memory"), p.reason());
        assertNull(AtlasMemory.shrink(p));
    }

    @Test
    void steamDeckCountsItsSharedHeap() {
        // RADV on Van Gogh: a 1 GB carve-out marked device-local, the rest of the 16 GB as a host heap
        AtlasNative.MemoryInfo deck = new AtlasNative.MemoryInfo(1 * GB, 900 * MB, 600 * MB, 7 * GB, 0, true, true);
        AtlasMemory.Plan p = AtlasMemory.plan(deck, 1280, 800, 6 * GB, 16 * GB);
        assertFalse(p.off(), p.describe());
        assertEquals(1.0, p.maxScale());
        assertTrue(p.allowance() <= 16 * GB / 10);
        assertTrue(p.describe().contains("integrated"), p.describe());
    }

    @Test
    void intelIgpuUsesAFifthOfWhatIsFree() {
        AtlasNative.MemoryInfo uhd = new AtlasNative.MemoryInfo(8 * GB, 7 * GB, 1 * GB, 0, 0, true, true);
        AtlasMemory.Plan p = AtlasMemory.plan(uhd, 1920, 1080, 3 * GB, 16 * GB);
        assertFalse(p.off());
        assertEquals((long) (6 * GB * 0.2), p.allowance(), 2 * MB);
    }

    @Test
    void littleFreeRamDropsAClipmapLevel() {
        AtlasMemory.Plan p = AtlasMemory.plan(discrete(24 * GB, 22 * GB, 2 * GB), 1920, 1080, 1 * GB, 8 * GB);
        assertEquals(6, p.lods());
    }

    @Test
    void unknownMemoryIsUnlimited() {
        AtlasMemory.Plan p = AtlasMemory.plan(null, 1920, 1080, -1, -1);
        assertFalse(p.off());
        assertEquals(1.0, p.maxScale());
        assertEquals(7, p.lods());
        assertEquals(1.0, AtlasMemory.unlimited("x").maxScale());
    }

    @Test
    void atlasOwnUsageIsNotCountedAgainstIt() {
        // the same machine measured before and after atlas allocated 400 MB of its own
        AtlasNative.MemoryInfo before = new AtlasNative.MemoryInfo(4 * GB, 3500 * MB, 1500 * MB, 0, 0, false, true);
        AtlasNative.MemoryInfo after = new AtlasNative.MemoryInfo(4 * GB, 3500 * MB, 1900 * MB, 0, 400 * MB, false, true);
        assertEquals(AtlasMemory.plan(before, 1920, 1080, 4 * GB, 16 * GB).allowance(),
                AtlasMemory.plan(after, 1920, 1080, 4 * GB, 16 * GB).allowance());
    }

    @Test
    void shrinkingAlwaysEnds() {
        AtlasMemory.Plan p = AtlasMemory.plan(discrete(24 * GB, 22 * GB, 2 * GB), 2560, 1440, 20 * GB, 64 * GB);
        int steps = 0;
        double lastScale = p.maxScale();
        int lastSun = p.maxSunMap();
        while ((p = AtlasMemory.shrink(p)) != null) {
            assertFalse(p.off());
            assertTrue(p.maxScale() <= lastScale && p.maxSunMap() <= lastSun);
            assertTrue(p.maxScale() < lastScale || p.maxSunMap() < lastSun || p.lods() == 6, p.describe());
            lastScale = p.maxScale();
            lastSun = p.maxSunMap();
            assertTrue(++steps < 12, "shrink must terminate");
        }
        assertTrue(steps >= 4, "a 3090 plan has several steps to give up: " + steps);
    }
}
