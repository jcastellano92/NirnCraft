package com.skycraft.client.autotest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainMeshReadinessTest {
    private static List<String> lines(int scheduled,int busy,int threads,int uploads,int important,int rebuild,int initial) {
        return List.of("Geometry Pool: 23/48 MiB (9 buffers)",
                "Chunk Builder: Permits=%02d | Busy=%02d | Total=%02d".formatted(scheduled,busy,threads),
                "Chunk Queues: U=%02d (P0=%03d | P1=%03d | P2=%03d)".formatted(uploads,important,rebuild,initial));
    }

    @Test void nativeSodium263CountersBlockEveryPendingStage(){
        var idle=List.of("Chunk Builder: Schd=00 | Busy=00 (0000%) | Total=10","Tasks: N0=000 | N1=000 | Def=000, Recv=000");
        assertTrue(TerrainMeshReadiness.parse(idle).idle(true));
        assertFalse(TerrainMeshReadiness.parse(idle).idle(false));
        assertFalse(TerrainMeshReadiness.parse(List.of(idle.get(0),"Tasks: N0=000 | N1=000 | Def=000, Recv=001")).idle(true));
        assertFalse(TerrainMeshReadiness.parse(List.of(idle.get(0),"Tasks: N0=000 | N1=000 | Def=001, Recv=000")).idle(true));
        assertFalse(TerrainMeshReadiness.parse(List.of("Chunk Builder: Schd=00 | Busy=01 (0010%) | Total=10",idle.get(1))).idle(true));
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(List.of(idle.get(0),"Tasks: N0=000 | N1=000 | Def=000, Recv=-1")));
    }

    @Test void idleRendererWithTenExistingWorkersIsReady() {
        var counts=TerrainMeshReadiness.parse(lines(0,0,10,0,0,0,0));
        assertTrue(counts.idle(true));assertEquals(10,counts.threads());
        assertFalse(counts.idle(false),"Existing renderer completion predicate is still required");
    }

    @Test void emptyWorkerQueueDoesNotHideActiveWorkersOrPendingUploadsAndRebuilds() {
        assertFalse(TerrainMeshReadiness.parse(lines(0,1,10,0,0,0,0)).idle(true),"Worker already dequeued its job");
        assertFalse(TerrainMeshReadiness.parse(lines(0,0,10,1,0,0,0)).idle(true),"Completed mesh awaiting GPU upload");
        assertFalse(TerrainMeshReadiness.parse(lines(0,0,10,0,1,0,0)).idle(true),"Important rebuild not yet submitted");
        assertFalse(TerrainMeshReadiness.parse(lines(0,0,10,0,0,1,0)).idle(true),"Ordinary rebuild not yet submitted");
        assertFalse(TerrainMeshReadiness.parse(lines(0,0,10,0,0,0,1)).idle(true),"New visible section awaiting initial build");
        assertFalse(TerrainMeshReadiness.parse(lines(1,0,10,0,0,0,0)).idle(true),"Public queue snapshot disagrees with completion bit");
    }

    @Test void unsupportedMalformedAndMissingKnownDiagnosticsFailClosed() {
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(null));
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(List.of("some future renderer format")));
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(List.of(lines(0,0,10,0,0,0,0).get(1))));
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(List.of(
                "Chunk Builder: Permits=00 | Busy=-1 | Total=10",lines(0,0,10,0,0,0,0).get(2))));
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(List.of(
                "Chunk Builder: Permits=999999999999999 | Busy=00 | Total=10",lines(0,0,10,0,0,0,0).get(2))));
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(lines(0,11,10,0,0,0,0)));
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(List.of(42)));
    }

    @Test void duplicateDiagnosticsCannotMaskAContradictoryBusyCounter() {
        var idle=lines(0,0,10,0,0,0,0);
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(List.of(idle.get(1),idle.get(2),
                "Chunk Builder: Permits=00 | Busy=01 | Total=10")));
        assertThrows(IllegalStateException.class,()->TerrainMeshReadiness.parse(List.of(idle.get(1),idle.get(2),idle.get(2))));
    }

    @Test void catchupClientTicksCannotMasqueradeAsRenderedFrameProgress() {
        var frames=new TerrainMeshReadiness.Frames();
        for(int tick=0;tick<1000;tick++) assertFalse(frames.observe(30,57,true));
        assertFalse(frames.observe(31,57,true));
        assertTrue(frames.observe(32,57,true));
        assertTrue(frames.observe(32,57,true));
    }

    @Test void newlyDiscoveredVisibleGeometryResetsStability() {
        var frames=new TerrainMeshReadiness.Frames();
        assertFalse(frames.observe(30,57,true));assertFalse(frames.observe(31,57,true));assertTrue(frames.observe(32,57,true));
        assertFalse(frames.observe(33,91,true));assertFalse(frames.observe(34,91,true));assertTrue(frames.observe(35,91,true));
    }

    @Test void renewedBuildActivityAndNewSettleRequestResetFrames() {
        var frames=new TerrainMeshReadiness.Frames();
        frames.observe(30,57,true);frames.observe(31,57,true);assertTrue(frames.observe(32,57,true));
        assertFalse(frames.observe(33,57,false));assertFalse(frames.observe(34,57,true));assertTrue(frames.observe(35,57,true));
        frames.reset();assertFalse(frames.observe(35,57,true));assertFalse(frames.observe(36,57,true));assertTrue(frames.observe(37,57,true));
    }

    @Test void publicFrameTimerRingWrapStillCountsDistinctFrames() {
        var frames=new TerrainMeshReadiness.Frames();
        assertFalse(frames.observe(239,57,true));assertFalse(frames.observe(0,57,true));assertTrue(frames.observe(1,57,true));
        assertThrows(IllegalStateException.class,()->frames.observe(2,-1,true));
    }

    @Test void actualClientLightEngineReportsQueuedSectionDataBeforeTerrainCanSettle() {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var blocks=new net.minecraft.world.level.BlockGetter(){
            @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos){return null;}
            @Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos){return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();}
            @Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos){return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();}
            @Override public int getHeight(){return 704;}
            @Override public int getMinY(){return -64;}
        };
        var chunks=new net.minecraft.world.level.chunk.LightChunkGetter(){
            @Override public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x,int z){return null;}
            @Override public net.minecraft.world.level.BlockGetter getLevel(){return blocks;}
        };
        var light=new net.minecraft.world.level.lighting.LevelLightEngine(chunks,true,false);
        assertFalse(light.hasLightWork());
        light.queueSectionData(net.minecraft.world.level.LightLayer.BLOCK,
                net.minecraft.core.SectionPos.of(26,-2,-61),new net.minecraft.world.level.chunk.DataLayer(15));
        assertTrue(light.hasLightWork(),"Received section light data is pending even before its propagation pass");
        var frames=new TerrainMeshReadiness.Frames();
        assertFalse(frames.observe(30,57,true));assertFalse(frames.observe(31,57,true));assertTrue(frames.observe(32,57,true));
        assertFalse(frames.observe(33,57,!light.hasLightWork()),"Light work invalidates previous mesh stabilization");
    }

    @Test void preRenderQueuesCannotHideThreeStrictlyIdleCompletedMainFrames() {
        var frames=new TerrainMeshReadiness.MainFrames();
        var before=TerrainMeshReadiness.parse(lines(0,0,10,20,0,0,27));
        var after=TerrainMeshReadiness.parse(lines(0,0,10,0,0,0,0));
        assertFalse(before.idle(true));
        for(int frame=1;frame<=3;frame++) {
            assertEquals(frame==3,frames.complete(frame,349,after.idle(true),false));
            // Several client polls never change or reset completed-world evidence.
            for(int tick=0;tick<20;tick++)assertEquals(frame==3,frames.readyAt(frame));
        }
        assertFalse(frames.readyAt(4),"An unseen/new render frame has no completion witness");
    }

    @Test void pendingUploadsOrInitialGeometryAfterMainDrawStillBlockCapture() {
        var frames=new TerrainMeshReadiness.MainFrames();
        var upload=TerrainMeshReadiness.parse(lines(0,0,10,20,0,0,0));
        var initial=TerrainMeshReadiness.parse(lines(0,0,10,0,0,0,27));
        for(int frame=0;frame<100;frame++) {
            assertFalse(frames.complete(frame,349,(frame%2==0?upload:initial).idle(true),false));
            assertFalse(frames.readyAt(frame));
        }
        assertFalse(frames.complete(100,349,true,false));
        assertTrue(frames.complete(101,349,true,false));
    }

    @Test void shadowPassesDuplicateStagesAndTicksCannotAdvanceMainFrameEvidence() {
        var frames=new TerrainMeshReadiness.MainFrames();
        for(int frame=0;frame<100;frame++)assertFalse(frames.complete(frame,900,true,true));
        assertFalse(frames.readyAt(99));
        assertFalse(frames.complete(100,349,true,false));
        for(int duplicate=0;duplicate<100;duplicate++)assertFalse(frames.complete(100,349,true,false));
        assertFalse(frames.complete(101,349,true,false));
        assertTrue(frames.complete(102,349,true,false));
        assertFalse(frames.complete(102,900,false,true));
        assertTrue(frames.readyAt(102),"A shadow pass cannot replace the completed main-camera witness");
        assertFalse(frames.complete(103,350,true,false),"New main-camera geometry resets stability");
        frames.reset();assertFalse(frames.readyAt(103));
    }

    @Test void contradictoryDuplicateMainEventInvalidatesWithoutAddingAnotherFrame() {
        var frames=new TerrainMeshReadiness.MainFrames();
        frames.complete(1,349,true,false);frames.complete(2,349,true,false);
        assertTrue(frames.complete(3,349,true,false));
        assertFalse(frames.complete(3,349,false,false));assertFalse(frames.readyAt(3));
        for(int n=0;n<100;n++)assertFalse(frames.complete(3,349,true,false));
        assertFalse(frames.complete(4,349,true,false));assertTrue(frames.complete(5,349,true,false));
        assertFalse(frames.complete(5,350,true,false),"Contradictory visible geometry in the same pass invalidates");
    }

    @Test void immediatePriorCompletionIsUsableOnlyWhileNextFrameIsInProgress() {
        var frames=new TerrainMeshReadiness.MainFrames();
        frames.complete(10,349,true,false);frames.complete(11,349,true,false);frames.complete(12,349,true,false);
        assertTrue(frames.readyAt(12,false));assertTrue(frames.readyAt(13,true));
        assertFalse(frames.readyAt(13,false),"A finished render missing MAIN completion must block");
        assertFalse(frames.readyAt(14,true),"Cannot reuse an older completed render");
        assertFalse(frames.complete(13,349,false,false));assertFalse(frames.readyAt(13,true));
    }

    @Test void viewMatchingHandlesEquivalentYawAndFloatRoundoffButRejectsMotion() {
        assertTrue(TerrainMeshReadiness.sameView(0,-180,20,180,20));
        assertTrue(TerrainMeshReadiness.sameView(0,179.12345f,20.12345f,Math.nextUp(179.12345f),Math.nextDown(20.12345f)));
        assertFalse(TerrainMeshReadiness.sameView(0,179,20,179.1f,20));
        assertFalse(TerrainMeshReadiness.sameView(0,179,20,179,20.1f));
        assertFalse(TerrainMeshReadiness.sameView(0.0001,179,20,179,20),"One centimetre of actual camera movement is rejected");
        assertFalse(TerrainMeshReadiness.sameView(Double.NaN,0,0,0,0));
        assertFalse(TerrainMeshReadiness.sameView(0,Float.NaN,0,0,0));
    }
}
