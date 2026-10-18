package com.skycraft.worldplan.tile;

import com.skycraft.bake.Bake;
import com.skycraft.worldplan.PlanGround;
import com.skycraft.worldplan.RoadPlan;
import com.skycraft.worldplan.scene.ScenePlan;
import com.skycraft.worldplan.street.StreetPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

class TileGeometryOwnershipTest {
    private TilePlanner.ToolPreview preview;
    @org.junit.jupiter.api.BeforeEach void previewDormantPlanner() { preview=TilePlanner.previewForTools(); }
    @org.junit.jupiter.api.AfterEach void closeDormantPreview() { preview.close(); }

    @BeforeAll static void pinBake() throws Exception {
        try (DataInputStream in = new DataInputStream(Bake.class.getResourceAsStream("/skycraft/baked/roadplan.bin"))) {
            Field f = Bake.class.getDeclaredField("inputs"); f.setAccessible(true);
            if (f.get(null) == null) f.set(null, in.readUTF());
        }
    }
    @AfterEach void detach() { TilePlanner.detach(); }

    @Test void delegatedRoadIndicesBelongToTileGeometry() {
        var tile = TilePlanner.planSync(0x534B594352414654L, 4, 0);
        var local = tile.roadPlan();
        int checked = 0;
        for (int vertex = 0; vertex < local.vertices(); vertex += 127) {
            if (TilePlan.tileCoord((int)local.vx[vertex]) != 4 || TilePlan.tileCoord((int)local.vz[vertex]) != 0) continue;
            var hit = new RoadPlan.Hit();
            assertTrue(RoadPlan.get().at(local.vx[vertex], local.vz[vertex], hit));
            if (RoadPlan.get().atAuthored(local.vx[vertex], local.vz[vertex], new RoadPlan.Hit())) {
                assertSame(RoadPlan.get(), hit.owner); // authored roads keep their existing seam priority
                assertSame(PlanGround.get(), PlanGround.getAt((int)local.vx[vertex], (int)local.vz[vertex]));
                continue;
            }
            assertSame(local, hit.owner);
            float expected = local.vh[hit.vertex] + (local.vh[hit.vertex + 1] - local.vh[hit.vertex]) * hit.t;
            assertEquals(expected, hit.height);
            var copied = new RoadPlan.Hit(); copied.set(hit);
            assertSame(local, copied.owner);
            var bed = new PlanGround.Bed();
            assertTrue(PlanGround.getAt((int)local.vx[vertex], (int)local.vz[vertex]).bed((int)local.vx[vertex], (int)local.vz[vertex], bed));
            var corner = new RoadPlan.Hit();
            assertTrue(local.at((int)local.vx[vertex], (int)local.vz[vertex], corner));
            assertEquals(corner.height, bed.profile, 0.001);
            checked++;
        }
        assertTrue(checked > 50);
    }

    @Test void alternatingTilesReuseTheirOwnTerrainIndices() {
        var first = TilePlanner.planSync(5L, 4, 0);
        var second = TilePlanner.planSync(5L, 5, 0);
        var a = PlanGround.getAt(17000, 500);
        var b = PlanGround.getAt(21000, 500);
        assertNotSame(a, b);
        assertSame(a, PlanGround.getAt(17000, 500));
        assertSame(b, PlanGround.getAt(21000, 500));
    }

    @Test void realizerResolversUseTileStreetAndSceneBuckets() {
        var tile = TilePlanner.planSync(0x534B594352414654L, 4, 0);
        var scenes = tile.scenePlan();
        assertTrue(scenes.scenes() > 0);
        int sx = scenes.x[0], sz = scenes.z[0];
        assertSame(scenes, ScenePlan.planFor(sx, sz));
        assertTrue(ScenePlan.planFor(sx, sz).inChunk(sx >> 4, sz >> 4).length > 0);
        var streets = tile.streetPlan();
        assertTrue(streets.settlements() > 0);
        assertSame(streets, StreetPlan.planFor(17000, 500));
        assertSame(StreetPlan.get(), StreetPlan.planFor(0, 0));
    }
}
