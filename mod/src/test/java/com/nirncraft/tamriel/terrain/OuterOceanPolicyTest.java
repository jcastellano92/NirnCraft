package com.nirncraft.tamriel.terrain;

import com.nirncraft.tamriel.MapSample;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OuterOceanPolicyTest {
    @Test void allFarOceanDirectionsAndWrapDestinationsStaySea() {
        assertFalse(Beyond.ENDLESS_LANDS_ENABLED);
        for(int x:new int[]{-29_360_128,-100_000,-65_536,65_535,100_000,29_360_127})
            for(int z:new int[]{-29_360_128,-100_000,0,100_000,29_360_127}) {
                MapSample s=new MapSample();Beyond.sample(x,z,s);
                assertTrue(s.coastDistance<0,x+","+z);assertTrue(s.elevation<63);assertTrue(s.region>=0);
                assertNull(OceanIsles.cellIsle(Math.floorDiv(x,512),Math.floorDiv(z,512)));
                assertFalse(Beyond.fixedWorldFootprint(x,z));
            }
    }
    @Test void dormantPreviewStillExistsButItsLandCannotAppearInRuntime() {
        MapSample old=new MapSample(),now=new MapSample();int land=0;
        for(int x=60_000;x<=100_000;x+=1024)for(int z=60_000;z<=100_000;z+=1024) {
            Beyond.sampleEndlessForTools(x,z,old);
            if(old.coastDistance>0) {land++;Beyond.sample(x,z,now);assertTrue(now.coastDistance<0);assertTrue(now.region>=0);}
        }
        assertTrue(land>100,"fixture must exercise actual dormant continents");
    }
    @Test void everyFixedForeignLandKeepsItsOriginalSamplerResult() throws Exception {
        int[][] centres={{0,-11500},{17600,2800},{22200,5200},{-19800,6300},{-18300,11300},{-19300,20600},{-15200,17000}};
        for(int[] c:centres)for(int dx=-512;dx<=512;dx+=256)for(int dz=-512;dz<=512;dz+=256) {
            int x=c[0]+dx,z=c[1]+dz;assertTrue(Beyond.fixedWorldFootprint(x,z));
            MapSample old=new MapSample(),now=new MapSample();Beyond.sampleEndlessForTools(x,z,old);Beyond.sample(x,z,now);
            for(var f:MapSample.class.getFields())assertEquals(f.get(old),f.get(now),f.getName()+" at "+x+","+z);
        }
    }
}
