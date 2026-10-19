package com.skycraft.render.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The controller under aether's memory ceilings (QualityController.limitAether): held in every phase and tier. */
class QualityMemoryCeilingTest {
    static final long FRAME = 16_666_667L;

    private static QualityController fastMachine() {
        return new QualityController(new HardwareTier.Guess(HardwareTier.HIGH, 0.45, "test"), 60, true);
    }

    /** Runs n frames of a machine with lots of headroom (every frame 4 ms), returning the last knobs. */
    private static double[] run(QualityController c, long[] now, int n) {
        double[] q = null;
        c.context.megapixels = 2.07;
        c.context.packActive = true;
        c.context.aetherActive = true;
        for (int i = 0; i < n; i++) {
            now[0] += FRAME;
            q = c.frame(now[0]).clone();
            c.observeFrame(q, 4.0, 16.0, now[0]);
            c.observeAether(q, 1.0);
        }
        return q;
    }

    @Test
    void ceilingsHoldFromTheFirstFrame() {
        QualityController c = fastMachine();
        c.limitAether(0.25, 0.34, true, 0);
        long[] now = {1_000_000_000L};
        for (int k = 0; k < 40; k++) {
            double[] q = run(c, now, 30);
            assertTrue(q[Knob.AETHER_SCALE.ordinal()] <= 0.25 + 1e-9, "scale " + q[Knob.AETHER_SCALE.ordinal()]);
            assertTrue(q[Knob.AETHER_SHADOWS.ordinal()] <= 0.34 + 1e-9, "shadows " + q[Knob.AETHER_SHADOWS.ordinal()]);
            assertEquals(0, q[Knob.AETHER_CLOUDS.ordinal()], 1e-9);
        }
        // the pack's knobs are not limited by aether's memory
        assertEquals(1.0, c.ceiling(Knob.SSR), 1e-9);
        assertEquals(0.25, c.ceiling(Knob.AETHER_SCALE), 1e-9);
        assertEquals(0, c.fallbackTier());
    }

    @Test
    void calibrationStaysUnderTheCeilings() {
        QualityController c = fastMachine();
        c.limitAether(0.5, 0.67, false, 0);
        c.setCovered(true);  // a loading cover: the calibration plan (which includes "all max")
        long[] now = {1_000_000_000L};
        c.context.megapixels = 2.07;
        for (int i = 0; i < 400; i++) {
            now[0] += FRAME;
            double[] q = c.frame(now[0]);
            assertTrue(q[Knob.AETHER_SCALE.ordinal()] <= 0.5 + 1e-9, "frame " + i);
            assertTrue(q[Knob.AETHER_SHADOWS.ordinal()] <= 0.67 + 1e-9, "frame " + i);
            c.observeFrame(q.clone(), 4.0, 16.0, now[0]);
        }
    }

    @Test
    void anInitialTierStartsWithTheFarFieldAtItsLowest() {
        QualityController c = fastMachine();
        c.limitAether(0.25, 0.34, true, 1);
        assertEquals(1, c.fallbackTier());
        long[] now = {1_000_000_000L};
        double[] q = run(c, now, 5);
        assertEquals(0, q[Knob.AETHER_SCALE.ordinal()], 1e-9);
        // with room to spare it steps back up to tier 0, still under the memory ceilings
        q = run(c, now, 60 * 40);
        assertEquals(0, c.fallbackTier());
        assertTrue(q[Knob.AETHER_SCALE.ordinal()] <= 0.25 + 1e-9);
    }

    @Test
    void aLaterLimitTightensALiveController() {
        QualityController c = fastMachine();
        long[] now = {1_000_000_000L};
        double[] q = run(c, now, 60 * 30);
        assertTrue(q[Knob.AETHER_SCALE.ordinal()] > 0.5, "a fast machine runs aether near full scale");
        c.limitAether(0.25, 1, false, 0);  // after running out of memory
        q = run(c, now, 60 * 5);
        assertTrue(q[Knob.AETHER_SCALE.ordinal()] <= 0.25 + 1e-9, "scale " + q[Knob.AETHER_SCALE.ordinal()]);
    }
}
