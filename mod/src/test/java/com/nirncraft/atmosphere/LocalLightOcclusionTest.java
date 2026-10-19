package com.nirncraft.atmosphere;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalLightOcclusionTest {
    private static LocalLightOcclusion empty() {
        var grid=new LocalLightOcclusion();
        java.util.Arrays.fill(grid.cells(),(byte)0);return grid;
    }

    @Test void hiddenSourceCanStillIlluminateReceiverAroundCameraCorner() {
        var grid=empty();
        for(int x=0;x<4;x++) for(int y=0;y<4;y++) grid.set(x,y,2,1);
        assertFalse(grid.visible(1.5,1.5,0.5,1.5,1.5,4.5),"Camera cannot see source");
        assertTrue(grid.visible(5.5,1.5,4.5,1.5,1.5,4.5),"Receiver still sees hidden source");
    }

    @Test void cameraVisibleSourceDoesNotLightReceiverThroughWall() {
        var grid=empty();
        for(int z=0;z<6;z++) for(int y=0;y<4;y++) grid.set(2,y,z,1);
        assertTrue(grid.visible(0.5,1.5,0.5,0.5,1.5,4.5));
        assertFalse(grid.visible(4.5,1.5,4.5,0.5,1.5,4.5));
        grid.set(2,1,4,0);
        assertTrue(grid.visible(4.5,1.5,4.5,0.5,1.5,4.5),"Opening immediately restores receiver visibility");
    }

    @Test void partialSlabsKeepTheirActualCollisionBounds() {
        var grid=empty();
        int slab=grid.shape(List.of(new LocalLightOcclusion.Box(0,0,0,1,0.5,1)));
        grid.set(-2,-1,-3,slab);
        assertFalse(grid.visible(-4,-0.75,-2.5,0,-0.75,-2.5));
        assertTrue(grid.visible(-4,-0.25,-2.5,0,-0.25,-2.5));
        assertEquals(slab,grid.shape(List.of(new LocalLightOcclusion.Box(0,0,0,1,0.5,1))));
    }

    @Test void packetSizeAndUnsupportedGeometryAreExplicitlyBounded() {
        var grid=empty();
        assertEquals(262144,grid.cells().length);
        assertEquals(4096,grid.macro().length);
        assertEquals(1,grid.shape(java.util.Collections.nCopies(9,new LocalLightOcclusion.Box(0,0,0,1,1,1))));
        assertEquals(1,grid.overflow());
    }
}
