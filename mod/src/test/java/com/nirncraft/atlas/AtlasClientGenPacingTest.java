package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasClientGenPacingTest {
    @Test
    void generatorReadyRequiresSpawnAreaCoarseAndFinerHorizon() {
        AtlasClientGen.Progress notDoneNear = new AtlasClientGen.Progress(true, 100, 441, false, 16, 16, 100, 16, 0);
        AtlasClientGen.Progress doneNearWaitFar = new AtlasClientGen.Progress(true, 441, 441, true, 12, 16, 441, 12, 0);
        AtlasClientGen.Progress allDone = new AtlasClientGen.Progress(true, 441, 441, true, 16, 16, 441, 16, 0);
        AtlasCoarseBake.Progress coarseLoading = new AtlasCoarseBake.Progress(
                1, 289, 100, 10, false, 1.0, new int[8], new int[8], 90L);
        AtlasCoarseBake.Progress coarseLoaded = new AtlasCoarseBake.Progress(
                2, 289, 289, 0, true, 2.0, new int[8], new int[8], 289L);

        assertFalse(AtlasClientGen.isGeneratorReady(notDoneNear, coarseLoaded));
        assertFalse(AtlasClientGen.isGeneratorReady(allDone, coarseLoading));
        assertFalse(AtlasClientGen.isGeneratorReady(doneNearWaitFar, coarseLoaded));
        assertTrue(AtlasClientGen.isGeneratorReady(allDone, coarseLoaded));
    }

    @Test
    void unpacesWhileLoadingScreenHoldsOrPreReady() {
        // Loading screen holding always unpaces.
        assertTrue(AtlasClientGen.shouldUnpaceBurst(
                true, true, false, false, false, 0.0, true, 1_000L, 0L, true));

        // Even if loading screen is dismissed early (holding=false), pre-ready generator work stays unpaced.
        assertTrue(AtlasClientGen.shouldUnpaceBurst(
                false, true, false, false, true, 5.0, true, 1_000L, 0L, true));

        // Stationary reload / teleport settle before spawn area is done stays unpaced.
        assertTrue(AtlasClientGen.shouldUnpaceBurst(
                false, true, true, true, false, 0.0, true, 1_000L, 0L, true));
    }

    @Test
    void respectsFramePacerOnceReadyInPlay() {
        // Once initial readiness is latched and spawn area is done, in-play motion is paced.
        assertFalse(AtlasClientGen.shouldUnpaceBurst(
                false, true, true, true, true, 4.3, true, 2_000L, 1_000L, true));

        // Headless relaunch benchmark (clientStarted=false) only bursts within burstUntilNs before 3x3 is up.
        assertTrue(AtlasClientGen.shouldUnpaceBurst(
                false, false, false, false, false, 0.0, true, 500L, 1_000L, false));
        assertFalse(AtlasClientGen.shouldUnpaceBurst(
                false, false, false, false, false, 0.0, true, 500L, 1_000L, true));
        assertFalse(AtlasClientGen.shouldUnpaceBurst(
                false, false, false, false, false, 30.0, true, 1_500L, 1_000L, false));
    }

    @Test
    void defersInterleavedFarTurnUntilSpawnAreaDone() {
        // Never interrupt spawn-area cache scan / batches with interleaved farStep before spawnAreaDone.
        assertFalse(AtlasClientGen.shouldAllowInterleavedFarTurn(true, true, false, 30.0));
        assertFalse(AtlasClientGen.shouldAllowInterleavedFarTurn(false, false, false, -1.0));

        // Once spawnAreaDone is true, allow during hold/burst or when near bucket has >= 20 ms headroom.
        assertTrue(AtlasClientGen.shouldAllowInterleavedFarTurn(true, false, true, 5.0));
        assertTrue(AtlasClientGen.shouldAllowInterleavedFarTurn(false, true, true, 5.0));
        assertTrue(AtlasClientGen.shouldAllowInterleavedFarTurn(false, false, true, 22.0));
        assertFalse(AtlasClientGen.shouldAllowInterleavedFarTurn(false, false, true, 12.0));
    }
}

