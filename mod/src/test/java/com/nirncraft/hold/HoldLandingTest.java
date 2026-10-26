package com.nirncraft.hold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Where a held player waits (HoldLanding): at the landing when they will fall, where they are otherwise. */
class HoldLandingTest {
    @Test
    void onlyAPlayerWhoWillFallIsHeldBelow() {
        assertTrue(HoldLanding.falls(false, false, false, false));   // survival in the air
        assertTrue(HoldLanding.falls(false, true, false, false));    // creative, not flying
        assertFalse(HoldLanding.falls(true, false, false, false));   // spectator (the owner's 10-26 login at y 522)
        assertFalse(HoldLanding.falls(false, true, true, false));    // flying
        assertFalse(HoldLanding.falls(false, false, false, true));   // no gravity
    }

    @Test
    void landsOnTheFirstBlockBelowTheFeet() {
        // ground at y 140 (its top at 141), the player at 522: lands at 141, the camera 1.62 above
        assertEquals(141.0, HoldLanding.landingY(522.0, -64, y -> y <= 140));
        // a roof between: the first block below the feet, not the column's top
        assertEquals(301.0, HoldLanding.landingY(522.0, -64, y -> y == 300 || y <= 140));
    }

    @Test
    void staysWhenStandingOrOverTheVoid() {
        assertTrue(Double.isNaN(HoldLanding.landingY(141.0, -64, y -> y <= 140)));   // standing
        assertTrue(Double.isNaN(HoldLanding.landingY(144.5, -64, y -> y <= 140)));   // a step: 3.5 blocks
        assertTrue(Double.isNaN(HoldLanding.landingY(150.0, -64, y -> y <= 150)));   // inside a block: its own
        assertTrue(Double.isNaN(HoldLanding.landingY(522.0, -64, y -> false)));      // the void
    }
}
