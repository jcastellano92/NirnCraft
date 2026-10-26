package com.nirncraft.atlas;

import com.nirncraft.render.quality.HardwareTier;
import com.nirncraft.render.quality.Knob;
import com.nirncraft.render.quality.QualityController;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise the renderer's consumer of quality output, not just the controller in isolation. */
class AtlasQualityTest {
    private static double[] knobs(double scale) {
        double[] q = new double[Knob.COUNT];
        Arrays.fill(q, 1);
        q[Knob.ATLAS_SCALE.ordinal()] = scale;
        return q;
    }

    @Test
    void changingFallbackTierDoesNotOverrideTheSlewedScale() {
        AtlasQuality quality = new AtlasQuality();
        double[] q = knobs(0.75);
        quality.begin(q, 0);
        int width = quality.width(2560);
        for (int tier : new int[] {1, 2, 1, 0}) {
            quality.begin(q, tier);
            assertEquals(width, quality.width(2560), "a tier must not instantly reallocate the render target");
            assertEquals(Knob.atlasScale(0.75), quality.scale(), 1e-6);
        }
        quality.begin(knobs(0), 1);
        assertEquals(0.4, quality.scale(), 1e-6, "the renderer uses the controller's defined floor");
    }

    @Test
    void unifiedTerrainStaysEnabledWhenTheRealControllerReachesTierTwo() {
        QualityController controller = new QualityController(
                new HardwareTier.Guess(HardwareTier.DECK, 9, "overloaded test GPU"), 40, true);
        controller.context.atlasActive = true;
        controller.context.megapixels = 1.02;
        AtlasQuality quality = new AtlasQuality();
        boolean tierTwo = false;
        for (long now = 1; now < 60_000_000_000L; now += 16_666_667L) {
            double[] q = controller.frame(now).clone();
            quality.begin(q, controller.fallbackTier());
            assertTrue(quality.enabled(true), "unified mode has suppressed vanilla terrain and cannot turn off");
            assertEquals(Knob.atlasScale(q[Knob.ATLAS_SCALE.ordinal()]), quality.scale(), 1e-6);
            if (controller.fallbackTier() == 2) {
                tierTwo = true;
                assertFalse(quality.enabled(false), "an optional far field can still use the final fallback");
            }
            controller.observeFrame(q, 80, 80, now);
            controller.observeAtlas(q, 60);
        }
        assertTrue(tierTwo, "the regression must exercise the real tier-2 transition");
    }

    @Test
    void requiredTerrainStillRespectsImmediateMemoryCeilingsAndLightingFloors() {
        AtlasQuality quality = new AtlasQuality();
        quality.setMemory(new AtlasMemory.Plan(false, 0.55, 256, true, 6, 1, 512 * AtlasMemory.MB, "test"));
        quality.begin(knobs(1), 2);
        assertTrue(quality.enabled(true));
        assertEquals(0.55, quality.scale(), 1e-6, "memory limits still apply without waiting for a slew");
        assertEquals(256, quality.sunMapSize());
        quality.begin(new double[Knob.COUNT], 2);
        assertEquals(256, quality.sunMapSize(), "the last quality tier must not remove sun lighting");
        assertNotEquals(0, quality.flags(AtlasNative.FRAME_SHADOWS) & AtlasNative.FRAME_SHADOWS);
    }
}
