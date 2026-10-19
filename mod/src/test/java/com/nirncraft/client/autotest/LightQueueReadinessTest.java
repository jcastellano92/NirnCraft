package com.nirncraft.client.autotest;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightQueueReadinessTest {
    @Test void nativePostBeforeRenderUpdateCanReachConsecutiveReadyObservations() {
        var probe=new LightQueueReadiness();var level=new Object();var queue=new ArrayDeque<Runnable>();
        int consecutive=0;
        for(int tick=0;tick<24;tick++) {
            probe.queueIfAbsent(level,queue::add); // ClientTick.Pre
            boolean complete=probe.isComplete(level); // ClientTick.Post, before ClientLevel.update
            if(tick==0)assertFalse(complete);else assertTrue(complete,"A completed render probe must survive the next tick Pre");
            consecutive=complete?consecutive+1:0;
            probe.renewIfComplete(level,queue::add);
            assertEquals(1,queue.size(),"Capture probes must not accumulate in the real FIFO");
            while(!queue.isEmpty())queue.remove().run(); // native render/update drains the FIFO
        }
        assertTrue(consecutive>=20,"The strict settle window must be reachable without bypassing the FIFO");
    }

    @Test void budgetLimitedFifoCannotPassBeforeEarlierLightUpdates() {
        var probe=new LightQueueReadiness();var level=new Object();var queue=new ArrayDeque<Runnable>();
        var applied=new AtomicInteger();
        for(int i=0;i<15;i++)queue.add(applied::incrementAndGet);
        probe.queueIfAbsent(level,queue::add);
        for(int i=0;i<10;i++)queue.remove().run(); // native small-backlog frame budget
        assertFalse(probe.isComplete(level));
        probe.queueIfAbsent(level,queue::add);
        assertEquals(6,queue.size(),"Starting another tick must retain the pending probe");
        while(!queue.isEmpty())queue.remove().run();
        assertEquals(15,applied.get());assertTrue(probe.isComplete(level));
        probe.renewIfComplete(level,queue::add);assertFalse(probe.isComplete(level));
    }

    @Test void lateCompletionCannotValidateAReplacementLevel() {
        var probe=new LightQueueReadiness();var first=new Object();var second=new Object();
        var firstQueue=new ArrayDeque<Runnable>();var secondQueue=new ArrayDeque<Runnable>();
        probe.queueIfAbsent(first,firstQueue::add);
        probe.queueIfAbsent(second,secondQueue::add);
        firstQueue.remove().run();
        assertFalse(probe.isComplete(first));assertFalse(probe.isComplete(second));
        secondQueue.remove().run();assertTrue(probe.isComplete(second));
        probe.reset();assertFalse(probe.isComplete(second));
    }
}
