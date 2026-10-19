package com.nirncraft.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLunarLightingTest {
    @Test void fullAndNewMasserRespondToActualElevation() {
        var full=AtmosphereMath.moonLight(Math.PI,18000,false);
        assertEquals(1,full.y(),1e-6);
        assertEquals(1,full.illumination(),1e-6);
        var set=AtmosphereMath.moonLight(0,18000,false);
        assertEquals(0,set.illumination(),1e-6);
        var newMoon=AtmosphereMath.moonLight(Math.PI,18000+4*24000,false);
        assertEquals(0,newMoon.illumination(),1e-6);
    }

    @Test void separateMoonDirectionsIlluminateDifferentSides() {
        var left=new AtmosphereMath.MoonLight(-1,0,0,1);
        var right=new AtmosphereMath.MoonLight(1,0,0,1);
        assertTrue(left.diffuse(-1,0,0)>right.diffuse(-1,0,0));
        assertTrue(right.diffuse(1,0,0)>left.diffuse(1,0,0));
        assertEquals(0,AtmosphereMath.MoonLight.DARK.diffuse(0,1,0));
    }

    @Test void bothOrbitalSnapshotsMatchSharedVisibilityContract() {
        for(int ticks=0;ticks<72*24000;ticks+=137) for(boolean secunda:new boolean[]{false,true}) {
            double angle=(ticks%24000)/24000.0*Math.PI*2;
            var moon=AtmosphereMath.moonLight(angle,ticks,secunda);
            assertEquals(1,moon.x()*moon.x()+moon.y()*moon.y()+moon.z()*moon.z(),1e-6);
            assertEquals(AtmosphereMath.moonElevation(angle,AtmosphereMath.moonCycle(ticks,secunda),secunda),moon.y(),1e-6);
            assertEquals(AtmosphereMath.visibleMoonIllumination(angle,AtmosphereMath.moonCycle(ticks,secunda),secunda),moon.illumination());
            if(moon.y()<=-0.035f) assertEquals(0,moon.illumination());
        }
    }
}
