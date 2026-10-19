package com.nirncraft.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DimensionMediaPolicyTest {
    @Test void realmsAllowLocalMediaWithoutBorrowingTerrestrialWeatherOrMoons() {
        for(String dimension:new String[]{"minecraft:the_nether","minecraft:the_end","nirncraft:shivering_isles",
                "nirncraft:apocrypha","nirncraft:soul_cairn","nirncraft:coldharbour"}) {
            var policy=DimensionMediaPolicy.of(dimension);
            assertTrue(policy.localMedia(),dimension);
            assertFalse(policy.terrestrialWeather(),dimension);
            assertFalse(policy.celestial(),dimension);
        }
    }

    @Test void unknownDimensionsAreNotSilentlyAssignedOverworldEffects() {
        assertEquals(new DimensionMediaPolicy(false,false,false),DimensionMediaPolicy.of("other:dimension"));
        assertEquals(new DimensionMediaPolicy(true,true,true),DimensionMediaPolicy.of("minecraft:overworld"));
    }
}
