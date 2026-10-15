package com.skycraft.worldplan.tile;

import com.skycraft.bake.Bake;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.worldplan.PlanGround;
import com.skycraft.worldplan.RoadPlan;
import com.skycraft.worldplan.scene.ScenePlan;
import com.skycraft.worldplan.street.StreetPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for P7 Endless Lands tile planning ({@link TilePlan}, {@link TilePlanner},
 * {@link TileSheetGenerator}). Runs without booting a Minecraft server.
 */
class TilePlanTest {

    private static final long TEST_SEED = 0x534B594352414654L;

    @BeforeAll
    static void pinShippedBakeHash() throws Exception {
        try (InputStream raw = Bake.class.getResourceAsStream("/skycraft/baked/roadplan.bin")) {
            assertNotNull(raw, "Missing /skycraft/baked/roadplan.bin on classpath");
            try (DataInputStream in = new DataInputStream(raw)) {
                String recordedHash = in.readUTF();
                Field f = Bake.class.getDeclaredField("inputs");
                f.setAccessible(true);
                if (f.get(null) == null) {
                    f.set(null, recordedHash);
                }
            }
        }
    }

    @AfterEach
    void resetPlannerState() {
        TilePlanner.setWorldSeed(0L);
        TilePlanner.clearCache();
    }

    @Test
    void tilePlanDeterminismAndBinaryRoundTrip() throws Exception {
        TilePlanner.clearCache();
        TilePlan run1 = TilePlanner.planSync(TEST_SEED, 4, 0);
        byte[] bytes1 = run1.write();

        TilePlanner.clearCache();
        TilePlan run2 = TilePlanner.planSync(TEST_SEED, 4, 0);
        byte[] bytes2 = run2.write();

        assertArrayEquals(bytes1, bytes2, "Two runs with the same worldSeed and tile coordinates must produce byte-identical TilePlans");

        TilePlan reloaded = TilePlan.read(bytes1);
        assertArrayEquals(bytes1, reloaded.write(), "Reloading a TilePlan from saved bytes must equal recomputed bytes");
        assertEquals(run1.places().size(), reloaded.places().size());
        assertEquals(run1.roadPlan().segments(), reloaded.roadPlan().segments());
        assertEquals(run1.streetPlan().settlements(), reloaded.streetPlan().settlements());
        assertEquals(run1.scenePlan().scenes(), reloaded.scenePlan().scenes());

        // Different seed produces distinct plan
        TilePlanner.clearCache();
        TilePlan differentSeedPlan = TilePlanner.planSync(TEST_SEED ^ 0xCAFEBABEL, 4, 0);
        assertFalse(Arrays.equals(bytes1, differentSeedPlan.write()), "Different world seeds must produce distinct TilePlans");
    }

    @Test
    void sharedBoundaryEdgePointAgreementAndZeroSeamGaps() throws Exception {
        TilePlanner.clearCache();
        // Plan (5, 0) BEFORE (4, 0) to verify order independence
        TilePlan eastTile = TilePlanner.planSync(TEST_SEED, 5, 0);
        TilePlan westTile = TilePlanner.planSync(TEST_SEED, 4, 0);
        TilePlan southTile = TilePlanner.planSync(TEST_SEED, 4, 1);

        List<TilePlan.EdgePoint> westEastBorder = westTile.edgePoints(TilePlan.Edge.EAST);
        List<TilePlan.EdgePoint> eastWestBorder = eastTile.edgePoints(TilePlan.Edge.WEST);
        assertFalse(westEastBorder.isEmpty(), "Shared border between dry tiles (4,0) and (5,0) must have crossing points");
        assertEquals(westEastBorder, eastWestBorder, "Adjacent tiles (4,0) and (5,0) must agree 100% on shared vertical border EdgePoints");

        List<TilePlan.EdgePoint> northSouthBorder = westTile.edgePoints(TilePlan.Edge.SOUTH);
        List<TilePlan.EdgePoint> southNorthBorder = southTile.edgePoints(TilePlan.Edge.NORTH);
        assertFalse(northSouthBorder.isEmpty(), "Shared border between tiles (4,0) and (4,1) must have crossing points");
        assertEquals(northSouthBorder, southNorthBorder, "Adjacent tiles (4,0) and (4,1) must agree 100% on shared horizontal border EdgePoints");

        // Verify that every road EdgePoint on the shared border is reached by BOTH tiles' RoadPlans at the exact same height (0 seam gap)
        RoadPlan.Hit hitWest = new RoadPlan.Hit();
        RoadPlan.Hit hitEast = new RoadPlan.Hit();
        int roadCrossingsChecked = 0;
        for (TilePlan.EdgePoint ep : westEastBorder) {
            if (!ep.river()) {
                assertTrue(westTile.roadPlan().at(ep.x(), ep.z(), hitWest),
                        "West tile RoadPlan must reach shared border point " + ep);
                assertTrue(eastTile.roadPlan().at(ep.x(), ep.z(), hitEast),
                        "East tile RoadPlan must reach shared border point " + ep);
                assertTrue(hitWest.distance <= 1.0f, "West road must terminate at border point (dist=" + hitWest.distance + ")");
                assertTrue(hitEast.distance <= 1.0f, "East road must terminate at border point (dist=" + hitEast.distance + ")");
                assertEquals(hitWest.height, hitEast.height, 0.07f,
                        "Road height across tile seam must match without a vertical step");
                roadCrossingsChecked++;
            }
        }
        assertTrue(roadCrossingsChecked > 0, "At least one road crossing must be verified across the (4,0)<->(5,0) seam");

        // Verify order independence: planning (4,0) first gives byte-identical output
        byte[] westPlannedSecond = westTile.write();
        TilePlanner.clearCache();
        TilePlan westFirst = TilePlanner.planSync(TEST_SEED, 4, 0);
        assertArrayEquals(westPlannedSecond, westFirst.write(), "TilePlan must be 100% independent of neighbour planning order");
    }

    @Test
    void proceduralWorldSheetLintPassesOnThreeDistinctTiles() {
        // 1. North polar tile (0, -3): z = -12288..-8192
        // 2. West arid/ocean tile (-5, 1): x = -20480..-16384
        // 3. East continent tile (4, 1): x = 16384..20480
        int[][] testTiles = {{0, -3}, {-5, 1}, {4, 1}};

        for (int[] coords : testTiles) {
            int tx = coords[0], tz = coords[1];
            TilePlan plan = TilePlanner.planSync(TEST_SEED, tx, tz);
            TileSheetGenerator.TileSheet sheet = plan.sheet();
            assertNotNull(sheet);
            assertEquals(11, sheet.layers().size(), "Tile sheet must have all 11 layers L1..L11");

            List<String> errors = TileSheetGenerator.validate(sheet);
            assertTrue(errors.isEmpty(), "Procedural world sheet for tile (" + tx + "," + tz + ") failed lint: " + errors);

            String json = TileSheetGenerator.toJson(sheet);
            assertTrue(json.contains("\"L1\""), "JSON export must contain L1");
            assertTrue(json.contains("\"L10\""), "JSON export must contain L10");
            assertTrue(json.contains("\"L11\""), "JSON export must contain L11");

            // Every place's 'why' citations must resolve in the tile sheet
            for (TilePlan.TilePlace place : plan.places()) {
                assertFalse(place.why().isEmpty(), "Place " + place.id() + " must have non-empty why citations");
                for (String cite : place.why()) {
                    assertTrue(sheet.hasField(cite),
                            "Place " + place.id() + " why citation '" + cite + "' must resolve in " + sheet.id());
                }
            }

            // Every scene's 'reason' citation must resolve in the tile sheet
            ScenePlan sp = plan.scenePlan();
            for (int s = 0; s < sp.scenes(); s++) {
                String reason = sp.reason[s];
                assertFalse(reason.isBlank(), "Scene #" + s + " must have a non-empty reason");
                assertTrue(sheet.hasField(reason),
                        "Scene #" + s + " reason '" + reason + "' must resolve in " + sheet.id());
            }
        }
    }

    @Test
    void tileRoadProfileAndStreetInvariantsHold() {
        int[][] tiles = {{4, 0}, {4, 1}, {0, -3}};
        for (int[] c : tiles) {
            TilePlan plan = TilePlanner.planSync(TEST_SEED, c[0], c[1]);
            RoadPlan rp = plan.roadPlan();
            assertTrue(rp.segments() > 0, "Dry tile (" + c[0] + "," + c[1] + ") must have planned road segments");

            for (int s = 0; s < rp.segments(); s++) {
                int a = rp.segStart[s];
                int b = rp.segEnd[s];
                assertTrue(b - a >= 2, "Segment " + s + " must have at least 2 vertices");
                for (int v = a; v < b; v++) {
                    if (!Float.isNaN(rp.vw[v])) {
                        assertEquals(RoadPlan.BRIDGE, rp.vspan[v],
                                "Wet vertex " + v + " on segment " + s + " must be bridged");
                        assertTrue(rp.vh[v] >= rp.vw[v] + 2.0f,
                                "Bridge deck at vertex " + v + " must clear water surface");
                    }
                    if (v + 1 < b) {
                        float stepDist = (float) Math.hypot(rp.vx[v + 1] - rp.vx[v], rp.vz[v + 1] - rp.vz[v]);
                        assertTrue(stepDist <= 8.0f, "Consecutive vertices must not have gaps > 8 blocks (got " + stepDist + ")");
                        float dh = Math.abs(rp.vh[v + 1] - rp.vh[v]);
                        assertTrue(dh <= 1.0f, "Consecutive vertices must never drop > 1 block without a step (got " + dh + ")");
                    }
                }
            }

            // Verify StreetPlan doors and pads
            StreetPlan stp = plan.streetPlan();
            for (int i = 0; i < stp.doors(); i++) {
                assertTrue(stp.dReach[i], "Every planned settlement door in tile (" + c[0] + "," + c[1] + ") must be reachable");
            }
        }
    }

    @Test
    void compositeFallbackAndNonBlockingAsyncPlanningWithinBudget() throws Exception {
        TilePlanner.clearCache();
        TilePlanner.setWorldSeed(TEST_SEED);

        // 1. Measure cold synchronous planning time (< 2,000 ms gate)
        long t0 = System.nanoTime();
        TilePlan syncPlan = TilePlanner.planSync(TEST_SEED, 4, 0);
        long syncMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(syncMs < 2000L, "Single tile planning must complete in < 2,000 ms (took " + syncMs + " ms)");

        // 2. Verify composite fallback through RoadPlan.get(), StreetPlan.get(), ScenePlan.get() outside Tamriel
        assertTrue(syncPlan.roadPlan().vertices() > 0);
        int testX = Math.round(syncPlan.roadPlan().vx[0]);
        int testZ = Math.round(syncPlan.roadPlan().vz[0]);
        assertFalse(TamrielMap.inTamriel(testX, testZ), "Tile (4,0) vertex must be outside authored Tamriel");

        RoadPlan.Hit roadHit = new RoadPlan.Hit();
        assertTrue(RoadPlan.get().at(testX, testZ, roadHit),
                "RoadPlan.get().at(x, z) must transparently delegate to cached TilePlan outside Tamriel");
        assertFalse(Double.isNaN(RoadPlan.bedHeight(testX, testZ)),
                "RoadPlan.bedHeight(x, z) must return valid bed height on tile road outside Tamriel");

        if (syncPlan.streetPlan().settlements() > 0) {
            int sx = syncPlan.streetPlan().sX[0];
            int sz = syncPlan.streetPlan().sZ[0];
            StreetPlan.Hit streetHit = new StreetPlan.Hit();
            assertTrue(StreetPlan.get().at(sx, sz, streetHit),
                    "StreetPlan.get().at(x, z) must transparently delegate to cached TilePlan outside Tamriel");
            PlanGround.Bed bed = new PlanGround.Bed();
            assertTrue(StreetPlan.get().pad(sx - 14, sz - 6, bed),
                    "StreetPlan.get().pad(x, z) must transparently delegate to cached TilePlan plot pads outside Tamriel");
        }

        if (syncPlan.scenePlan().scenes() > 0) {
            int scX = syncPlan.scenePlan().x[0];
            int scZ = syncPlan.scenePlan().z[0];
            assertTrue(ScenePlan.get().covers(scX, scZ, 2),
                    "ScenePlan.get().covers(x, z) must transparently delegate to cached TilePlan scenes outside Tamriel");
        }

        // 3. Verify non-blocking off-thread trigger on an uncached tile (6, 2)
        int uncachedBlockX = (6 << TilePlan.TILE_SHIFT) + 500;
        int uncachedBlockZ = (2 << TilePlan.TILE_SHIFT) + 500;
        assertFalse(TilePlanner.isCached(6, 2));
        long asyncStart = System.nanoTime();
        TilePlan immediate = TilePlanner.getIfReady(uncachedBlockX, uncachedBlockZ);
        long stallMs = (System.nanoTime() - asyncStart) / 1_000_000L;
        assertTrue(stallMs < 50L, "Non-blocking getIfReady must not stall the caller thread (took " + stallMs + " ms)");
        if (immediate == null) {
            CompletableFuture<TilePlan> fut = TilePlanner.ensurePlannedAsync(TEST_SEED, 6, 2);
            TilePlan completed = fut.get(5, TimeUnit.SECONDS);
            assertNotNull(completed);
        }
        assertTrue(TilePlanner.isCached(6, 2), "Tile (6, 2) must be resident in cache after async completion");
    }
}
