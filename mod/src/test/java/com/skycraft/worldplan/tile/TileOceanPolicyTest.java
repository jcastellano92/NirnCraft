package com.skycraft.worldplan.tile;

import com.skycraft.bake.Bake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TileOceanPolicyTest {
    @BeforeAll static void pinBake() throws Exception {
        try(var in=new DataInputStream(Bake.class.getResourceAsStream("/skycraft/baked/roadplan.bin"))) {
            var f=Bake.class.getDeclaredField("inputs");f.setAccessible(true);if(f.get(null)==null)f.set(null,in.readUTF());
        }
    }
    @AfterEach void detach(){TilePlanner.detach();}
    @Test void runtimeColdQueriesNeverScheduleReadOrPublishDormantExpansion() {
        AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();
        TilePlanner.attach(0,new Object(),(s,x,z)->{reads.incrementAndGet();return null;},(key,p)->writes.incrementAndGet());
        for(int x=70_000;x<90_000;x+=500)assertNull(TilePlanner.getIfReady(x,80_000));
        assertEquals(0,reads.get());assertEquals(0,writes.get());
        assertFalse(TilePlanner.isCached(18,19));
    }
    @Test void savedOrToolCachedPlansRemainInvisibleToRuntimeAndOtherThreads() throws Exception {
        TilePlan p=TilePlanner.planSync(7,4,0);
        assertTrue(TilePlanner.isCached(4,0));assertNull(TilePlanner.getIfReady(17000,500));
        try(var tool=TilePlanner.previewForTools()) {
            assertSame(p,TilePlanner.getIfReady(17000,500));
            AtomicReference<TilePlan> other=new AtomicReference<>(p);
            Thread thread=new Thread(()->other.set(TilePlanner.getIfReady(17000,500)));thread.start();thread.join();
            assertNull(other.get());
            try(var nested=TilePlanner.previewForTools()){assertSame(p,TilePlanner.getIfReady(17000,500));}
            assertSame(p,TilePlanner.getIfReady(17000,500));
        }
        assertNull(TilePlanner.getIfReady(17000,500));
    }
}
