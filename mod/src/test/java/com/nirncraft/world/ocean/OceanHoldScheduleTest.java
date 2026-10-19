package com.nirncraft.world.ocean;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OceanHoldScheduleTest {
    @Test void stationaryTargetRenewsBeforeExpiryWithoutPerFrameJobs() {
        var s=new OceanWrap.HoldSchedule();int jobs=0;
        for(int tick=0;tick<1000;tick++)if(s.acquire(-65536,80,tick))jobs++;
        assertEquals(10,jobs);
    }
    @Test void movingTargetIsRateLimitedToSixLiveHolds() {
        var s=new OceanWrap.HoldSchedule();java.util.ArrayDeque<Integer> live=new java.util.ArrayDeque<>();
        for(int tick=0;tick<2000;tick++) {
            while(!live.isEmpty() && live.peekFirst()+120<=tick)live.removeFirst();
            if(s.acquire(-65536,tick*32,tick))live.addLast(tick);
            assertTrue(live.size()<=6,"live holds="+live.size()+" at "+tick);
        }
        assertEquals(6,live.size());
    }
    @Test void retargetUsesNewestChunkAndDoesNotResetCadenceAcrossSeam() {
        var s=new OceanWrap.HoldSchedule();assertTrue(s.acquire(-65536,0,0));
        assertFalse(s.acquire(65535,32,1));assertFalse(s.acquire(65535,64,19));
        assertTrue(s.acquire(65535,96,20));assertFalse(s.acquire(65535,100,40));
        assertTrue(s.acquire(65535,96,120));
    }
}
