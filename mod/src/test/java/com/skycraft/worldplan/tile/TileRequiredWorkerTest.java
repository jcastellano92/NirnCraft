package com.skycraft.worldplan.tile;

import com.skycraft.bake.Bake;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Required worker API is intentionally not a default consumer: proves its safety before integrating the entire pipeline. */
class TileRequiredWorkerTest {
    private static TilePlan fixture;
    @BeforeAll static void fixture()throws Exception{
        try(DataInputStream in=new DataInputStream(Bake.class.getResourceAsStream("/skycraft/baked/roadplan.bin"))){
            Field field=Bake.class.getDeclaredField("inputs");field.setAccessible(true);if(field.get(null)==null)field.set(null,in.readUTF());
        }
        fixture=TilePlanner.buildTilePlan(17L,4,0);
    }
    @AfterEach void detach(){TilePlanner.detach();}
    @Test void owningThreadAndUnboundSessionsCannotAccidentallySolveColdTiles(){
        Thread owner=Thread.currentThread();var calls=new AtomicInteger();
        TilePlanner.attachQueued(17L,new Object(),(seed,x,z)->{calls.incrementAndGet();return null;},null,null,()->Thread.currentThread()==owner);
        assertThrows(IllegalStateException.class,()->TilePlanner.requireTileForWorker(17L,4,0));
        assertEquals(0,calls.get());assertFalse(TilePlanner.isCached(4,0));
        assertThrows(CancellationException.class,()->TilePlanner.requireTileForWorker(18L,4,0));
        assertEquals(17L,TilePlanner.getWorldSeed(),"A stale prerequisite never changes the active world's seed");
        TilePlanner.attach(17L,new Object(),null,null);
        assertThrows(IllegalStateException.class,()->TilePlanner.requireTileForWorker(17L,4,0));
    }
    @Test void saturatedBackgroundPoolCannotPreventIndependentPureWorkerResolution()throws Exception{
        Thread owner=Thread.currentThread();var occupied=new CountDownLatch(2);var release=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor(r->new Thread(r,"required-plan-test-worker"));
        TilePlanner.attachQueued(17L,new Object(),(seed,x,z)->{
            if(Thread.currentThread().getName().startsWith("skycraft-tile-planner-")){
                occupied.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Pool not released");}
                catch(InterruptedException e){throw new AssertionError(e);}
            }
            return null;
        },null,null,()->Thread.currentThread()==owner);
        var queued=TilePlanner.ensurePlannedAsync(17L,4,0);
        try{
            assertTrue(occupied.await(10,TimeUnit.SECONDS));assertFalse(queued.isDone());
            var required=worker.submit(()->TilePlanner.requireTileForWorker(17L,4,0)).get(5,TimeUnit.SECONDS);
            assertArrayEquals(fixture.write(),required.tile().write());assertTrue(required.isCurrent());
            assertFalse(queued.isDone(),"Required worker must not join the still-blocked planner job");
            assertTrue(TilePlanner.isCached(4,0));
        }finally{release.countDown();worker.shutdownNow();}
        queued.get(10,TimeUnit.SECONDS);
    }
    @Test void serializedSavedTileIsReusedWithoutDirtyingAndSnapshotsExpireAcrossReloadAndClear()throws Exception{
        Thread owner=Thread.currentThread();var tasks=new ConcurrentLinkedQueue<Runnable>();
        var data=new TilePlanData();data.bind(17L,()->Thread.currentThread()==owner,tasks::add);
        data.putTile(fixture);data.flush();tasks.clear();CompoundTag saved=data.save(new CompoundTag());data.setDirty(false);
        var worker=Executors.newSingleThreadExecutor();
        try{
            var first=worker.submit(()->TilePlanner.requireTileForWorker(17L,4,0)).get(5,TimeUnit.SECONDS);
            assertArrayEquals(fixture.write(),first.tile().write());assertFalse(data.isDirty());assertTrue(tasks.isEmpty());
            var reloaded=TilePlanData.load(saved);reloaded.bind(17L,()->Thread.currentThread()==owner,tasks::add);
            assertFalse(first.isCurrent());assertThrows(CancellationException.class,first::tile);
            var second=worker.submit(()->TilePlanner.requireTileForWorker(17L,4,0)).get(5,TimeUnit.SECONDS);
            assertArrayEquals(fixture.write(),second.tile().write());assertFalse(reloaded.isDirty());assertTrue(tasks.isEmpty());
            TilePlanner.clearCache();assertFalse(second.isCurrent());assertThrows(CancellationException.class,second::tile);
            var third=worker.submit(()->TilePlanner.requireTileForWorker(17L,4,0)).get(5,TimeUnit.SECONDS);
            assertArrayEquals(fixture.write(),third.tile().write());
            assertThrows(IllegalStateException.class,()->TilePlanner.requireTileForWorker(17L,4,0),"Warm cache does not permit owner-thread misuse");
        }finally{worker.shutdownNow();}
    }
    @Test void staleWorkerCompletionCannotWriteIntoSameSeedReplacement()throws Exception{
        Thread owner=Thread.currentThread();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var writes=new AtomicInteger();var worker=Executors.newSingleThreadExecutor();
        TilePlanner.attachQueued(17L,new Object(),(seed,x,z)->{
            entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Not released");}
            catch(InterruptedException e){throw new AssertionError(e);}return fixture;
        },(token,key,plan)->writes.incrementAndGet(),null,()->Thread.currentThread()==owner);
        var pending=worker.submit(()->TilePlanner.requireTileForWorker(17L,4,0));
        try{
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            TilePlanner.attachQueued(17L,new Object(),null,(token,key,plan)->writes.incrementAndGet(),null,()->Thread.currentThread()==owner);
            release.countDown();var failure=assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class,failure.getCause());assertEquals(0,writes.get());assertFalse(TilePlanner.isCached(4,0));
        }finally{release.countDown();worker.shutdownNow();}
    }
}
