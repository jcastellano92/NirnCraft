package com.skycraft.world.ocean;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OceanWrapMathTest {
    private final OceanWrapMath.Bounds b=new OceanWrapMath.Bounds(65_536,1024);
    @Test void oppositeEdgesPreserveFractionalOvershootAndIndependentAxes() {
        assertEquals(new OceanWrapMath.Point(-65_532.75,14.5),OceanWrapMath.crossing(65_539.25,14.5,b).orElseThrow());
        assertEquals(new OceanWrapMath.Point(65_535.875,-65_534.5),OceanWrapMath.crossing(-65_536.125,65_537.5,b).orElseThrow());
    }
    @Test void boundaryOwnershipIsHalfOpenWithoutImmediateBounce() {
        assertEquals(-65_536,OceanWrapMath.axis(65_536,b));
        assertTrue(OceanWrapMath.crossing(-65_536,0,b).isEmpty());
        assertTrue(OceanWrapMath.crossing(Math.nextDown(65_536d),0,b).isEmpty());
    }
    @Test void multiplePeriodsAndHugeFiniteValuesRemainBoundedAndIdempotent() {
        for(double x:new double[]{-1e12,-3*131072d-65536.75,3*131072d+65536.25,Double.MAX_VALUE,-Double.MAX_VALUE}) {
            double wrapped=OceanWrapMath.axis(x,b);
            assertTrue(wrapped>=-65536 && wrapped<65536,"outside: "+wrapped);
            assertEquals(wrapped,OceanWrapMath.axis(wrapped,b));
        }
        assertEquals(-65535.75,OceanWrapMath.axis(3*131072d+65536.25,b));
    }
    @Test void prefetchWarmsOppositeEntryBeforeCrossingAndNeverMovesInterior() {
        assertTrue(OceanWrapMath.prefetch(0,0,b).isEmpty());
        assertEquals(new OceanWrapMath.Point(-65536,51.25),OceanWrapMath.prefetch(65000,51.25,b).orElseThrow());
        assertEquals(new OceanWrapMath.Point(Math.nextDown(65536d),-65536),OceanWrapMath.prefetch(-65000,65000,b).orElseThrow());
        assertEquals(OceanWrapMath.crossing(65540,-65541,b),OceanWrapMath.prefetch(65540,-65541,b));
    }
    @Test void boundsPreserveFixedWorldAndWeatherPeriodAndHardBoundaryMargin() {
        assertEquals(0,OceanWrapMath.DEFAULT_HALF_WIDTH%8192);
        assertEquals(OceanWrapMath.MAX_HALF_WIDTH,OceanWrapMath.DEFAULT_HALF_WIDTH);
        assertTrue(30_000_000-OceanWrapMath.DEFAULT_HALF_WIDTH>600_000);
        assertTrue(OceanWrapMath.MIN_HALF_WIDTH>27_450+4096);
        assertThrows(IllegalArgumentException.class,()->new OceanWrapMath.Bounds(65535,1024));
        assertThrows(IllegalArgumentException.class,()->new OceanWrapMath.Bounds(30_000_000,1024));
        assertThrows(IllegalArgumentException.class,()->new OceanWrapMath.Bounds(65536,0));
    }
    @Test void nonfiniteCoordinatesFailExplicitlyBeforeChunkArithmetic() {
        for(double x:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->OceanWrapMath.prefetch(x,0,b));
    }
}
