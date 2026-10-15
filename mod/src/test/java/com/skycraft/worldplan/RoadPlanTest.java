package com.skycraft.worldplan;

import com.skycraft.bake.Bake;
import com.skycraft.roads.PlanLinks;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for {@link RoadPlan}, {@link PlanGround}, {@link PlanTerrain}, {@link PlanRouter}, and
 * {@link RoadPlanBuilder}. Runs without booting Minecraft or touching Forge registries.
 */
class RoadPlanTest {

    private static byte[] shippedBytes;
    private static RoadPlan shippedPlan;

    @BeforeAll
    static void loadShippedBake() throws Exception {
        try (InputStream raw = Bake.class.getResourceAsStream("/skycraft/baked/roadplan.bin")) {
            assertNotNull(raw, "Missing /skycraft/baked/roadplan.bin on classpath");
            try (DataInputStream in = new DataInputStream(raw)) {
                String recordedHash = in.readUTF();
                // Pin Bake.inputs so RoadPlan.get() / PlanGround.get() always load the fast baked payload in <100ms
                Field f = Bake.class.getDeclaredField("inputs");
                f.setAccessible(true);
                if (f.get(null) == null) {
                    f.set(null, recordedHash);
                }
                try (InflaterInputStream z = new InflaterInputStream(in)) {
                    shippedBytes = z.readAllBytes();
                }
            }
        }
        shippedPlan = RoadPlan.read(shippedBytes);
    }

    /** Builds a small synthetic 2-segment road plan with known geometry, heights, spans, and piers. */
    private static RoadPlan buildTinyPlan() {
        RoadPlan.Builder b = new RoadPlan.Builder();
        b.sites(2);
        b.siteId[0] = "whiterun_test";
        b.siteType[0] = (byte) PlaceType.CAPITAL.ordinal();
        b.siteX[0] = 100;
        b.siteZ[0] = 200;
        b.siteRadius[0] = 24;
        b.sitePad[0] = RoadPlan.quant(68.0f);

        b.siteId[1] = "riverwood_test";
        b.siteType[1] = (byte) PlaceType.VILLAGE.ordinal();
        b.siteX[1] = 140;
        b.siteZ[1] = 200;
        b.siteRadius[1] = 16;
        b.sitePad[1] = RoadPlan.quant(76.0f);

        b.links(2);
        b.linkA[0] = "whiterun_test";
        b.linkB[0] = "riverwood_test";
        b.linkName[0] = "Test Highway";
        b.linkKind[0] = PlanLinks.HIGHWAY;
        b.linkStatus[0] = RoadPlan.LINK_OK;
        b.linkBridge[0] = 20;

        b.linkA[1] = "riverwood_test";
        b.linkB[1] = "bleak_falls_test";
        b.linkName[1] = "";
        b.linkKind[1] = PlanLinks.TRAIL;
        b.linkStatus[1] = RoadPlan.LINK_OK;
        b.linkBridge[1] = 0;

        b.segs(2);
        // Segment 0: 5 vertices along z=200 from x=100 to x=140 (10 blocks per edge)
        b.segLink[0] = 0;
        b.segKind[0] = PlanLinks.HIGHWAY;
        b.segHalfWidth[0] = 2.5f;
        b.segStart[0] = 0;
        b.segEnd[0] = 5;
        b.segJoinA[0] = RoadPlan.JOIN_SITE;
        b.segJoinB[0] = RoadPlan.JOIN_SITE;

        // Segment 1: 3 vertices branching south from (120, 200) to (120, 220)
        b.segLink[1] = 1;
        b.segKind[1] = PlanLinks.TRAIL;
        b.segHalfWidth[1] = 1.0f;
        b.segStart[1] = 5;
        b.segEnd[1] = 8;
        b.segJoinA[1] = 0;
        b.segJoinB[1] = RoadPlan.JOIN_NONE;

        b.vx = new float[]{
                RoadPlan.quant(100f), RoadPlan.quant(110f), RoadPlan.quant(120f), RoadPlan.quant(130f), RoadPlan.quant(140f),
                RoadPlan.quant(120f), RoadPlan.quant(120f), RoadPlan.quant(120f)
        };
        b.vz = new float[]{
                RoadPlan.quant(200f), RoadPlan.quant(200f), RoadPlan.quant(200f), RoadPlan.quant(200f), RoadPlan.quant(200f),
                RoadPlan.quant(200f), RoadPlan.quant(210f), RoadPlan.quant(220f)
        };
        b.vh = new float[]{
                RoadPlan.quant(68f), RoadPlan.quant(70f), RoadPlan.quant(72f), RoadPlan.quant(74f), RoadPlan.quant(76f),
                RoadPlan.quant(72f), RoadPlan.quant(75f), RoadPlan.quant(78f)
        };
        b.vg = new float[]{
                RoadPlan.quant(68f), RoadPlan.quant(64f), RoadPlan.quant(60f), RoadPlan.quant(66f), RoadPlan.quant(76f),
                RoadPlan.quant(72f), RoadPlan.quant(75f), RoadPlan.quant(78f)
        };
        b.vw = new float[]{
                Float.NaN, RoadPlan.quant(62f), RoadPlan.quant(62f), Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN
        };
        b.vspan = new byte[]{
                RoadPlan.NORMAL, RoadPlan.BRIDGE, RoadPlan.BRIDGE, RoadPlan.FILL, RoadPlan.NORMAL,
                RoadPlan.NORMAL, RoadPlan.STAIRS, RoadPlan.STAIRS
        };

        // Spans: Seg 0 has [0..0 NORMAL], [1..2 BRIDGE], [3..3 FILL], [4..4 NORMAL]; Seg 1 has [5..5 NORMAL], [6..7 STAIRS]
        b.spanOff = new int[]{0, 4, 6};
        b.spanType = new byte[]{
                RoadPlan.NORMAL, RoadPlan.BRIDGE, RoadPlan.FILL, RoadPlan.NORMAL,
                RoadPlan.NORMAL, RoadPlan.STAIRS
        };
        b.spanFrom = new int[]{0, 1, 3, 4, 5, 6};
        b.spanTo = new int[]{0, 2, 3, 4, 5, 7};
        b.spanDeck = new float[]{Float.NaN, RoadPlan.quant(72f), Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        b.pierOff = new int[]{0, 0, 2, 2, 2, 2, 2};
        b.pierAt = new float[]{RoadPlan.quant(3.5f), RoadPlan.quant(7.0f)};

        return new RoadPlan(b);
    }

    @Test
    void binaryRoundTripPreservesAllArraysAndMetadata() throws IOException {
        RoadPlan orig = buildTinyPlan();
        byte[] bytes = orig.write();
        RoadPlan read = RoadPlan.read(bytes);

        assertEquals(orig.sites(), read.sites());
        assertEquals(orig.links(), read.links());
        assertEquals(orig.segments(), read.segments());
        assertEquals(orig.vertices(), read.vertices());
        assertEquals(orig.buckets(), read.buckets());

        assertArrayEquals(orig.siteId, read.siteId);
        assertArrayEquals(orig.siteType, read.siteType);
        assertArrayEquals(orig.siteX, read.siteX);
        assertArrayEquals(orig.siteZ, read.siteZ);
        assertArrayEquals(orig.siteRadius, read.siteRadius);
        assertArrayEquals(orig.sitePad, read.sitePad);

        assertArrayEquals(orig.linkA, read.linkA);
        assertArrayEquals(orig.linkB, read.linkB);
        assertArrayEquals(orig.linkName, read.linkName);
        assertArrayEquals(orig.linkKind, read.linkKind);
        assertArrayEquals(orig.linkStatus, read.linkStatus);
        assertArrayEquals(orig.linkBridge, read.linkBridge);

        assertArrayEquals(orig.segLink, read.segLink);
        assertArrayEquals(orig.segKind, read.segKind);
        assertArrayEquals(orig.segHalfWidth, read.segHalfWidth);
        assertArrayEquals(orig.segStart, read.segStart);
        assertArrayEquals(orig.segEnd, read.segEnd);
        assertArrayEquals(orig.segJoinA, read.segJoinA);
        assertArrayEquals(orig.segJoinB, read.segJoinB);

        assertArrayEquals(orig.vx, read.vx);
        assertArrayEquals(orig.vz, read.vz);
        assertArrayEquals(orig.vh, read.vh);
        assertArrayEquals(orig.vg, read.vg);
        assertArrayEquals(orig.vw, read.vw);
        assertArrayEquals(orig.vspan, read.vspan);
        assertArrayEquals(orig.varc, read.varc, 1e-4f);
        assertArrayEquals(orig.vseg, read.vseg);

        assertArrayEquals(orig.spanOff, read.spanOff);
        assertArrayEquals(orig.spanType, read.spanType);
        assertArrayEquals(orig.spanFrom, read.spanFrom);
        assertArrayEquals(orig.spanTo, read.spanTo);
        assertArrayEquals(orig.spanDeck, read.spanDeck);
        assertArrayEquals(orig.pierOff, read.pierOff);
        assertArrayEquals(orig.pierAt, read.pierAt);

        assertArrayEquals(bytes, read.write(), "Re-serialised RoadPlan bytes must be identical");
    }

    @Test
    void shippedRoadPlanBinaryRoundTripIsByteIdentical() throws IOException {
        assertTrue(shippedPlan.sites() > 0, "Shipped road plan should contain sites");
        assertTrue(shippedPlan.links() > 0, "Shipped road plan should contain links");
        assertTrue(shippedPlan.segments() > 0, "Shipped road plan should contain segments");
        assertTrue(shippedPlan.vertices() > 0, "Shipped road plan should contain vertices");

        byte[] rewritten = shippedPlan.write();
        assertArrayEquals(shippedBytes, rewritten, "Shipped roadplan.bin must round-trip byte-for-byte");
    }

    @Test
    void readRejectsInvalidMagicAndUnsupportedVersion() throws IOException {
        ByteArrayOutputStream badMagic = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(badMagic)) {
            out.writeInt(0x12345678);
            out.writeInt(RoadPlan.VERSION);
        }
        assertThrows(IOException.class, () -> RoadPlan.read(badMagic.toByteArray()));

        ByteArrayOutputStream badVer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(badVer)) {
            out.writeInt(RoadPlan.MAGIC);
            out.writeInt(RoadPlan.VERSION + 99);
        }
        assertThrows(IOException.class, () -> RoadPlan.read(badVer.toByteArray()));
    }

    @Test
    void atContractOnCentrelineAndOffCentreline() {
        RoadPlan plan = buildTinyPlan();
        RoadPlan.Hit hit = new RoadPlan.Hit();

        // Midpoint of edge 1 (between v1=(110,200) and v2=(120,200)): (115, 200)
        assertTrue(plan.at(115.0, 200.0, hit));
        assertEquals(0, hit.segment);
        assertEquals(0, hit.link);
        assertEquals(1, hit.vertex);
        assertEquals(PlanLinks.HIGHWAY, hit.kind);
        assertEquals(2.5f, hit.halfWidth, 1e-5f);
        assertEquals(0.0f, hit.distance, 1e-4f);
        assertEquals(0.5f, hit.t, 1e-4f);
        assertEquals(15.0f, hit.along, 1e-3f);
        assertEquals(71.0f, hit.height, 1e-3f);
        assertEquals(62.0f, hit.ground, 1e-3f);
        assertEquals(62.0f, hit.water, 1e-3f);
        assertEquals(RoadPlan.BRIDGE, hit.span);
        assertEquals(1.0f, hit.dirX, 1e-4f);
        assertEquals(0.0f, hit.dirZ, 1e-4f);

        // 4.0 blocks north of edge 0 at x=104, z=196 (t=0.4 < 0.5 selects vspan[0]=NORMAL)
        assertTrue(plan.at(104.0, 196.0, hit));
        assertEquals(0, hit.segment);
        assertEquals(0, hit.vertex);
        assertEquals(4.0f, hit.distance, 1e-4f);
        assertEquals(0.4f, hit.t, 1e-4f);
        assertEquals(68.8f, hit.height, 1e-3f);
        assertEquals(66.4f, hit.ground, 1e-3f);
        assertEquals(RoadPlan.NORMAL, hit.span);

        // At x=105, z=196 (t=0.5 >= 0.5 selects vspan[1]=BRIDGE)
        assertTrue(plan.at(105.0, 196.0, hit));
        assertEquals(RoadPlan.BRIDGE, hit.span);
    }

    @Test
    void atHeightAndGroundInterpolateLinearlyAlongEdge() {
        RoadPlan plan = buildTinyPlan();
        RoadPlan.Hit hit = new RoadPlan.Hit();

        // Edge 3 runs from x=130 (vh=74, vg=66) to x=140 (vh=76, vg=76)
        for (double frac : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
            double x = 130.0 + 10.0 * frac;
            assertTrue(plan.at(x, 200.0, hit));
            assertEquals(74.0 + 2.0 * frac, hit.height, 1e-3, "Height mismatch at frac=" + frac);
            assertEquals(66.0 + 10.0 * frac, hit.ground, 1e-3, "Ground mismatch at frac=" + frac);
        }
    }

    @Test
    void atReturnsFalseFarOutsideReach() {
        RoadPlan plan = buildTinyPlan();
        RoadPlan.Hit hit = new RoadPlan.Hit();

        assertFalse(plan.at(5000.0, 5000.0, hit));
        assertEquals(-1, hit.segment);

        // Same bucket, but > REACH (32 blocks) away from the road at z=200..220
        assertFalse(plan.at(100.0, 160.0, hit));
        assertEquals(-1, hit.segment);
    }

    @Test
    void atPicksNearestSegmentAtBranchJunction() {
        RoadPlan plan = buildTinyPlan();
        RoadPlan.Hit hit = new RoadPlan.Hit();

        // Near segment 1 (x=120, z=215): closer to segment 1 than segment 0 (which is at z=200)
        assertTrue(plan.at(121.0, 215.0, hit));
        assertEquals(1, hit.segment);
        assertEquals(1, hit.link);
        assertEquals(PlanLinks.TRAIL, hit.kind);
        assertEquals(1.0f, hit.halfWidth, 1e-4f);
        assertEquals(1.0f, hit.distance, 1e-4f);
        assertEquals(RoadPlan.STAIRS, hit.span);

        // Near segment 0 (x=135, z=201): closer to segment 0
        assertTrue(plan.at(135.0, 201.0, hit));
        assertEquals(0, hit.segment);
        assertEquals(0, hit.link);
    }

    @Test
    void atSegmentRestrictsSearchToRequestedSegment() {
        RoadPlan plan = buildTinyPlan();
        RoadPlan.Hit hit = new RoadPlan.Hit();

        // At (121, 212), segment 1 is 1 block away, while segment 0 is 12 blocks away
        assertTrue(plan.atSegment(121.0, 212.0, 0, hit));
        assertEquals(0, hit.segment);
        assertEquals(12.0f, hit.distance, 1e-3f);

        assertTrue(plan.atSegment(121.0, 212.0, 1, hit));
        assertEquals(1, hit.segment);
        assertEquals(1.0f, hit.distance, 1e-3f);

        assertFalse(plan.atSegment(121.0, 212.0, -1, hit));
        assertFalse(plan.atSegment(121.0, 212.0, 99, hit));
    }

    @Test
    void hitSetCopiesAllFields() {
        RoadPlan plan = buildTinyPlan();
        RoadPlan.Hit a = new RoadPlan.Hit();
        RoadPlan.Hit b = new RoadPlan.Hit();
        assertTrue(plan.at(115.0, 202.0, a));
        b.set(a);
        assertEquals(a.segment, b.segment);
        assertEquals(a.link, b.link);
        assertEquals(a.vertex, b.vertex);
        assertEquals(a.kind, b.kind);
        assertEquals(a.span, b.span);
        assertEquals(a.halfWidth, b.halfWidth);
        assertEquals(a.distance, b.distance);
        assertEquals(a.height, b.height);
        assertEquals(a.ground, b.ground);
        assertEquals(a.water, b.water);
        assertEquals(a.t, b.t);
        assertEquals(a.along, b.along);
        assertEquals(a.dirX, b.dirX);
        assertEquals(a.dirZ, b.dirZ);
    }

    @Test
    void spansTileEachSegmentWithoutGapOrOverlapOnTinyPlan() {
        RoadPlan plan = buildTinyPlan();
        assertSpansTileSegments(plan);
        assertEquals(0, plan.spanAt(0, 0));
        assertEquals(1, plan.spanAt(0, 1));
        assertEquals(1, plan.spanAt(0, 2));
        assertEquals(2, plan.spanAt(0, 3));
        assertEquals(3, plan.spanAt(0, 4));
        assertEquals(-1, plan.spanAt(0, 5));
        assertEquals(4, plan.spanAt(1, 5));
        assertEquals(5, plan.spanAt(1, 6));
        assertEquals(5, plan.spanAt(1, 7));
    }

    @Test
    void spansTileEachSegmentWithoutGapOrOverlapOnShippedPlan() {
        assertSpansTileSegments(shippedPlan);
    }

    private static void assertSpansTileSegments(RoadPlan plan) {
        for (int s = 0; s < plan.segments(); s++) {
            int start = plan.segStart[s];
            int end = plan.segEnd[s];
            int s0 = plan.spanOff[s];
            int s1 = plan.spanOff[s + 1];
            assertTrue(s1 > s0, "Segment " + s + " must have at least one span");
            assertEquals(start, plan.spanFrom[s0], "First span of segment " + s + " must start at segStart");
            assertEquals(end - 1, plan.spanTo[s1 - 1], "Last span of segment " + s + " must end at segEnd - 1");
            for (int k = s0; k < s1; k++) {
                assertTrue(plan.spanFrom[k] <= plan.spanTo[k], "Span " + k + " has inverted range");
                if (k > s0) {
                    assertEquals(plan.spanTo[k - 1] + 1, plan.spanFrom[k],
                            "Spans " + (k - 1) + " and " + k + " of segment " + s + " must tile without gap or overlap");
                }
                for (int v = plan.spanFrom[k]; v <= plan.spanTo[k]; v++) {
                    assertEquals(plan.spanType[k], plan.vspan[v], "Vertex span must match enclosing spanType");
                }
            }
        }
    }

    @Test
    void lengthAndSegmentsOfMatchTinyPlanGeometry() {
        RoadPlan plan = buildTinyPlan();
        assertEquals(40.0, plan.length(PlanLinks.HIGHWAY), 1e-3);
        assertEquals(20.0, plan.length(PlanLinks.TRAIL), 1e-3);
        assertEquals(60.0, plan.length(-1), 1e-3);

        IntArrayList link0Segs = plan.segmentsOf(0);
        assertEquals(IntArrayList.of(0), link0Segs);
        IntArrayList link1Segs = plan.segmentsOf(1);
        assertEquals(IntArrayList.of(1), link1Segs);
    }

    @Test
    void emptyPlanIsValidAndAnswersFalse() {
        RoadPlan empty = RoadPlan.empty();
        assertEquals(0, empty.sites());
        assertEquals(0, empty.links());
        assertEquals(0, empty.segments());
        assertEquals(0, empty.vertices());
        assertEquals(0.0, empty.length(-1));
        RoadPlan.Hit hit = new RoadPlan.Hit();
        assertFalse(empty.at(0, 0, hit));
    }

    @Test
    void planGroundShapesBedAndPadOnShippedPlan() {
        PlanGround ground = PlanGround.get();
        PlanGround.Bed bed = new PlanGround.Bed();

        // Pick a non-bridge vertex on the first segment of the shipped plan
        int seg = 0;
        int v = shippedPlan.segStart[seg];
        int x = Math.round(shippedPlan.vx[v]);
        int z = Math.round(shippedPlan.vz[v]);
        assertTrue(ground.bed(x, z, bed), "Column on road vertex should be shaped by PlanGround.bed");
        assertTrue(bed.inBed, "Vertex centre must lie inside the road bed");
        assertTrue(bed.distance <= 1.5f);

        // Site pad centre of site 0
        int sx = shippedPlan.siteX[0];
        int sz = shippedPlan.siteZ[0];
        if (PlaceType.values()[shippedPlan.siteType[0]].isSettlement()) {
            assertTrue(ground.pad(sx, sz, bed), "Settlement centre must lie inside its site pad");
            assertEquals(1.0f, bed.padWeight, 1e-4f);
            assertEquals(shippedPlan.sitePad[0] + PlanGround.EPS, bed.padHeight, 0.1f);
        }
    }

    @Test
    void planRouterResampleAndChaikinAreDeterministic() {
        List<double[]> raw = List.of(
                new double[]{0.0, 0.0},
                new double[]{32.0, 0.0},
                new double[]{32.0, 32.0},
                new double[]{64.0, 32.0}
        );
        List<double[]> smooth1 = PlanRouter.resample(PlanRouter.chaikin(PlanRouter.chaikin(raw)), RoadPlanBuilder.SPACING);
        List<double[]> smooth2 = PlanRouter.resample(PlanRouter.chaikin(PlanRouter.chaikin(raw)), RoadPlanBuilder.SPACING);

        assertEquals(smooth1.size(), smooth2.size());
        assertTrue(smooth1.size() >= 10);
        assertArrayEquals(raw.get(0), smooth1.get(0), 1e-6);
        assertArrayEquals(raw.get(raw.size() - 1), smooth1.get(smooth1.size() - 1), 1e-6);
        for (int i = 0; i < smooth1.size(); i++) {
            assertArrayEquals(smooth1.get(i), smooth2.get(i), 0.0, "Vertex " + i + " must be bit-identical");
        }
    }

    @Test
    void roadPlanBuilderRouteAndProfileDeterministicOnSmallSyntheticSitePair() {
        Place a = new Place("synth_a", net.minecraft.network.chat.Component.literal("Synth A"), PlaceType.TOWN, "nord", "skyrim/whiterun", 0, 0, 24, "");
        Place b = new Place("synth_b", net.minecraft.network.chat.Component.literal("Synth B"), PlaceType.VILLAGE, "nord", "skyrim/whiterun", 160, 64, 16, "");
        List<Place> places = List.of(a, b);

        PlanTerrain t1 = new PlanTerrain(places, p -> true);
        PlanTerrain t2 = new PlanTerrain(places, p -> true);

        PlanRouter.Job job1 = new PlanRouter.Job(0, 0, 160, 64, PlanLinks.ROAD, 64, new int[]{0, 1}, new LongOpenHashSet(), 4000);
        PlanRouter.Job job2 = new PlanRouter.Job(0, 0, 160, 64, PlanLinks.ROAD, 64, new int[]{0, 1}, new LongOpenHashSet(), 4000);

        List<double[]> route1 = PlanRouter.route(t1, job1);
        List<double[]> route2 = PlanRouter.route(t2, job2);
        assertNotNull(route1);
        assertNotNull(route2);
        assertEquals(route1.size(), route2.size());

        List<double[]> polyPts1 = PlanRouter.resample(PlanRouter.chaikin(route1), RoadPlanBuilder.SPACING);
        List<double[]> polyPts2 = PlanRouter.resample(PlanRouter.chaikin(route2), RoadPlanBuilder.SPACING);

        RoadPlanBuilder.Poly p1 = new RoadPlanBuilder.Poly(polyPts1);
        RoadPlanBuilder.Poly p2 = new RoadPlanBuilder.Poly(polyPts2);
        MapSample ms = new MapSample();
        PlanTerrain.Col col = new PlanTerrain.Col();
        for (RoadPlanBuilder.Poly p : List.of(p1, p2)) {
            for (int i = 0; i < p.n; i++) {
                t1.sample((int) Math.round(p.x[i]), (int) Math.round(p.z[i]), ms, col);
                p.g[i] = col.ground;
                p.w[i] = col.water;
                p.width[i] = col.waterWidth;
                p.near[i] = col.nearWater;
                p.lakeSea[i] = col.sea || col.lake;
            }
            Profile.run(p, PlanLinks.ROAD, t1.sitePad[0], t1.sitePad[1]);
        }

        assertArrayEquals(p1.h, p2.h, 0.0f);
        assertArrayEquals(p1.span, p2.span);
    }
}
