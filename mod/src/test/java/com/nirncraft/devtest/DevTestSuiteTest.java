package com.nirncraft.devtest;

import com.nirncraft.render.quality.Knob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated unit and regression tests for the NirnCraft Dev / Creative Testing Suite.
 */
class DevTestSuiteTest {

    @BeforeEach
    void resetState() {
        DevTestState.godMode = true;
        DevTestState.flyMode = true;
        DevTestState.noHandsMode = true;
        DevTestState.questsEnabled = true;
        DevTestState.unlockAllMap = true;
        DevTestState.noFogOfWar = true;
        DevTestState.tracedLighting = true;
        DevTestState.qualityCeiling = DevTestState.QualityCeiling.ULTRA_MAX;
        DevTestState.fpsOverlayMode = DevTestState.FpsOverlayMode.BASIC;
        DevTestState.hudMaster = true;
        DevTestState.hudCrosshair = true;
        DevTestState.hudCompass = true;
        DevTestState.hudVitals = false;
        DevTestState.hudHotbar = false;
        DevTestState.hudNotifications = true;
        DevTestState.hudHands = false;
        DevTestState.activePlacement = null;
        DevTestState.fadingTicksRemaining = 0;
        DevTestState.targetDayTime = -1L;
    }

    @Test
    void numberKeysMapNoonMidnightAndIntermediateHoursSmoothly() {
        // 6 = Noon (6000 ticks), 0 = Midnight (18000 ticks)
        assertEquals(6000L, DevTestState.dayTimeForDigit(6), "Digit 6 must map to Noon (6000 ticks)");
        assertEquals(18000L, DevTestState.dayTimeForDigit(0), "Digit 0 must map to Midnight (18000 ticks)");

        // 1..5 fade smoothly between Midnight (18000) and Noon (6000)
        assertEquals(20000L, DevTestState.dayTimeForDigit(1));
        assertEquals(22000L, DevTestState.dayTimeForDigit(2));
        assertEquals(0L, DevTestState.dayTimeForDigit(3));
        assertEquals(2000L, DevTestState.dayTimeForDigit(4));
        assertEquals(4000L, DevTestState.dayTimeForDigit(5));

        // 7..9 fade smoothly from Noon (6000) through Sunset (11500) to Dusk (14250)
        assertEquals(8500L, DevTestState.dayTimeForDigit(7));
        assertEquals(11500L, DevTestState.dayTimeForDigit(8));
        assertEquals(14250L, DevTestState.dayTimeForDigit(9));

        // Verify startTimeFade preserves the current day number and sets smooth interpolation
        DevTestState.startTimeFade(48_000L + 1000L, 6);
        assertEquals(48_000L + 6000L, DevTestState.targetDayTime);
        assertTrue(DevTestState.fadingTicksRemaining > 0);

        assertEquals("12:00 PM (Noon)", DevTestState.formatDayTime(6000L));
        assertEquals("12:00 AM (Midnight)", DevTestState.formatDayTime(18000L));
    }

    @Test
    void qualityCeilingCyclesAndClampsAllHardwareProfiles() {
        double[] knobs = new double[Knob.COUNT];
        Arrays.fill(knobs, 1.0);

        // Steam Deck ceiling clamps knobs to Steam Deck limits
        DevTestState.qualityCeiling = DevTestState.QualityCeiling.STEAM_DECK;
        DevTestState.tracedLighting = true;
        DevTestState.clampKnobs(knobs);
        assertEquals(
                DevTestState.QualityCeiling.STEAM_DECK.knobCaps[Knob.ATLAS_SCALE.ordinal()],
                knobs[Knob.ATLAS_SCALE.ordinal()],
                1e-6);
        assertEquals(
                DevTestState.QualityCeiling.STEAM_DECK.knobCaps[Knob.SHADOW_TAPS.ordinal()],
                knobs[Knob.SHADOW_TAPS.ordinal()],
                1e-6);

        // Disabling Traced Lighting clamps SHADOW_TAPS, AIR_MARCH, and ATLAS_SHADOWS even on ULTRA_MAX
        Arrays.fill(knobs, 1.0);
        DevTestState.qualityCeiling = DevTestState.QualityCeiling.ULTRA_MAX;
        DevTestState.tracedLighting = false;
        DevTestState.clampKnobs(knobs);
        assertEquals(1.0, knobs[Knob.ATLAS_SCALE.ordinal()], 1e-6);
        assertEquals(0.20, knobs[Knob.SHADOW_TAPS.ordinal()], 1e-6);
        assertEquals(0.0, knobs[Knob.AIR_MARCH.ordinal()], 1e-6);
        assertEquals(0.34, knobs[Knob.ATLAS_SHADOWS.ordinal()], 1e-6);

        // Cycling through all 6 profiles wraps around cleanly
        DevTestState.qualityCeiling = DevTestState.QualityCeiling.STEAM_DECK;
        DevTestState.cycleQualityCeiling(1);
        assertEquals(DevTestState.QualityCeiling.IGPU, DevTestState.qualityCeiling);
        DevTestState.cycleQualityCeiling(-1);
        assertEquals(DevTestState.QualityCeiling.STEAM_DECK, DevTestState.qualityCeiling);
    }

    @Test
    void nonDevPlayerHasZeroOverheadAndDefaultVisibility() {
        // Null player (or non-creative survival player) must never trigger dev mode overrides
        assertFalse(DevTestState.isActive(null));
        assertFalse(DevTestState.shouldHideHands(null));
        assertTrue(DevTestState.shouldShowCompass(null));
        assertTrue(DevTestState.shouldShowVitals(null));
        assertTrue(DevTestState.shouldShowCrosshair(null));
        assertTrue(DevTestState.shouldShowHotbar(null));
        assertTrue(DevTestState.shouldShowNotifications(null));
    }

    @Test
    void frameStatisticsAndEventRingBufferRemainBounded() {
        for (int i = 0; i < 400; i++) {
            DevTestState.recordEvent("Event #" + i);
        }
        assertTrue(DevTestState.recentEventsSnapshot().size() <= 80);

        double[] stats = DevTestState.computeFrameStats();
        assertEquals(7, stats.length);
        assertTrue(stats[0] > 0.0, "FPS should be positive");
    }
}
