package com.nirncraft.atmosphere;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VolumeBlendTest {
    private static DungeonAir.Volume room(int x) { return new DungeonAir.Volume(x,0,0,x+8,8,8,0,1); }

    @Test void replacementCrossfadesInsteadOfSwitchingOff() {
        VolumeBlend blend=new VolumeBlend();
        var a=room(0); var b=room(20);
        for(int i=0;i<10;i++) blend.update(List.of(a));
        assertTrue(blend.settled());
        var transition=blend.update(List.of(b));
        assertEquals(2,transition.size());
        assertEquals(1f,transition.stream().map(VolumeBlend.Entry::weight).reduce(0f,Float::sum),0.00001f);
        assertFalse(blend.settled());
        for(int i=0;i<11;i++) blend.update(List.of(b));
        assertTrue(blend.settled());
        assertEquals(List.of(new VolumeBlend.Entry(b,1f)),blend.update(List.of(b)));
    }

    @Test void fastMovementKeepsTheTextureBudgetBounded() {
        VolumeBlend blend=new VolumeBlend();
        for(int i=0;i<100;i++) {
            int x=i*100;
            var entries=blend.update(List.of(room(x),room(x+10),room(x+20),room(x+30)));
            assertTrue(entries.size()<=8);
            assertTrue(entries.stream().allMatch(e->e.weight()>0f && e.weight()<=1f));
        }
    }

    @Test void adjoiningTilesMergeWithoutInventingRoomsAcrossAGapOrDifferentMedium() {
        var a=new DungeonAir.Volume(0,0,0,64,64,64,0,3);
        var b=new DungeonAir.Volume(64,0,0,128,64,64,0,3);
        var c=new DungeonAir.Volume(0,0,64,64,64,128,0,3);
        var d=new DungeonAir.Volume(64,0,64,128,64,128,0,3);
        assertEquals(List.of(new DungeonAir.Volume(0,0,0,128,64,128,0,3)),VolumeBlend.mergeAligned(List.of(a,b,c,d)));
        var l=VolumeBlend.mergeAligned(List.of(a,b,c));
        assertEquals(2,l.size());
        assertTrue(l.stream().allMatch(v->v.distanceSquared(96,32,96)>0),"Missing quadrant stays outside the medium");
        assertEquals(2,VolumeBlend.mergeAligned(List.of(a,new DungeonAir.Volume(64,0,0,128,64,64,0,2))).size());
        assertEquals(2,VolumeBlend.mergeAligned(List.of(a,new DungeonAir.Volume(65,0,0,128,64,64,0,3))).size());
    }
}
