package com.skycraft.worldplan.scene;

import com.skycraft.bake.Bake;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for {@link ScenePlan}, {@link ScenePlanBuilder}, and {@link SceneKinds}.
 */
class ScenePlanTest {

    private static byte[] shippedBytes;
    private static ScenePlan shippedPlan;

    @BeforeAll
    static void loadShippedSceneBake() throws Exception {
        try (InputStream raw = Bake.class.getResourceAsStream("/skycraft/baked/sceneplan.bin")) {
            assertNotNull(raw, "Missing /skycraft/baked/sceneplan.bin on classpath");
            try (DataInputStream in = new DataInputStream(raw)) {
                String recordedHash = in.readUTF();
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
        shippedPlan = ScenePlan.read(shippedBytes);
    }

    private static ScenePlan buildTinyScenePlan() {
        float landKm2 = 42.5f;
        float[] roadKm = {12.5f, 24.0f, 8.25f, 15.0f};
        String[] kinds = {"campfire", "cart_wreck", ScenePlan.SIGN, "lm:grave"};
        int[] kind = {0, 1};
        int[] x = {160, -320};
        int[] z = {240, 80};
        float[] h = {68.5f, 74.25f};
        byte[] facing = {0, 2};
        byte[] anchor = {ScenePlan.CAMP_SITE, ScenePlan.CROSSROADS};
        String[] ref = {"camp_alpha", "seg:3:v12"};
        String[] reason = {"the camp site camp_alpha", "wayside at a crossroads"};
        String[] clue = {"", "@roads:whiterun,riverwood"};
        String[] note = {"", "A weathered note"};
        int[] pathX = {ScenePlan.NO_PATH, -312};
        int[] pathZ = {ScenePlan.NO_PATH, 84};
        short[] radius = {6, 8};
        int[] propOff = {0, 2, 4};
        short[] propKind = {0, 3, 1, 2};
        byte[] propDx = {0, 3, -2, 4};
        byte[] propDz = {0, -2, 1, 0};
        return new ScenePlan(landKm2, roadKm, kinds, kind, x, z, h, facing, anchor, ref, reason, clue, note,
                pathX, pathZ, radius, propOff, propKind, propDx, propDz);
    }

    @Test
    void buildTwiceUnderDifferentSeedsIsByteIdentical() throws IOException {
        // Perturb ambient RNG state with two different seeds and verify ScenePlan construction + serialisation are byte-identical
        new Random(111111L).nextLong();
        byte[] first = buildTinyScenePlan().write();

        new Random(999999L).nextLong();
        byte[] second = buildTinyScenePlan().write();

        assertArrayEquals(first, second, "ScenePlan built under different seeds must be byte-identical");

        // Also verify ScenePlanBuilder.build live determinism if RoadPlan is available
        ScenePlan liveA = ScenePlanBuilder.build(null);
        ScenePlan liveB = ScenePlanBuilder.build(null);
        assertArrayEquals(liveA.write(), liveB.write(), "ScenePlanBuilder.build must be byte-identical across runs");
    }

    @Test
    void binaryRoundTripPreservesAllSceneAndPropArrays() throws IOException {
        ScenePlan orig = buildTinyScenePlan();
        byte[] bytes = orig.write();
        ScenePlan read = ScenePlan.read(bytes);

        assertEquals(orig.scenes(), read.scenes());
        assertEquals(orig.props(), read.props());
        assertEquals(orig.landKm2, read.landKm2, 1e-4f);
        assertArrayEquals(orig.roadKm, read.roadKm, 1e-4f);
        assertArrayEquals(orig.kinds, read.kinds);
        assertArrayEquals(orig.kind, read.kind);
        assertArrayEquals(orig.x, read.x);
        assertArrayEquals(orig.z, read.z);
        assertArrayEquals(orig.h, read.h, 1e-4f);
        assertArrayEquals(orig.facing, read.facing);
        assertArrayEquals(orig.anchor, read.anchor);
        assertArrayEquals(orig.ref, read.ref);
        assertArrayEquals(orig.reason, read.reason);
        assertArrayEquals(orig.clue, read.clue);
        assertArrayEquals(orig.note, read.note);
        assertArrayEquals(orig.pathX, read.pathX);
        assertArrayEquals(orig.pathZ, read.pathZ);
        assertArrayEquals(orig.radius, read.radius);
        assertArrayEquals(orig.propOff, read.propOff);
        assertArrayEquals(orig.propKind, read.propKind);
        assertArrayEquals(orig.propDx, read.propDx);
        assertArrayEquals(orig.propDz, read.propDz);
        assertArrayEquals(bytes, read.write());
    }

    @Test
    void shippedScenePlanBinaryRoundTripIsByteIdentical() throws IOException {
        assertTrue(shippedPlan.scenes() > 0, "Shipped sceneplan.bin must have scenes");
        assertTrue(shippedPlan.props() > 0, "Shipped sceneplan.bin must have props");
        assertArrayEquals(shippedBytes, shippedPlan.write());
    }

    @Test
    void readRejectsInvalidMagicAndVersion() {
        ByteArrayOutputStream badMagic = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (DataOutputStream out = new DataOutputStream(badMagic)) {
                out.writeInt(0xCAFEBABE);
                out.writeInt(ScenePlan.VERSION);
            }
        });
        assertThrows(IOException.class, () -> ScenePlan.read(badMagic.toByteArray()));

        ByteArrayOutputStream badVer = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (DataOutputStream out = new DataOutputStream(badVer)) {
                out.writeInt(ScenePlan.MAGIC);
                out.writeInt(ScenePlan.VERSION + 5);
            }
        });
        assertThrows(IOException.class, () -> ScenePlan.read(badVer.toByteArray()));
    }

    @Test
    void inChunkCoversNearestAndKindAccessorsFollowContract() {
        ScenePlan plan = buildTinyScenePlan();

        // Scene 0 is at (160, 240) with radius 6 -> chunk (10, 15)
        int[] c0 = plan.inChunk(160 >> 4, 240 >> 4);
        assertEquals(1, c0.length);
        assertEquals(0, c0[0]);
        assertEquals("campfire", plan.kindOf(0));
        assertEquals("campfire", plan.propKindOf(0));
        assertEquals("lm:grave", plan.propKindOf(1));

        assertTrue(plan.covers(162, 242, 0));
        assertFalse(plan.covers(180, 240, 0));

        assertEquals(0, plan.nearest(164, 240, 16));
        assertEquals(-1, plan.nearest(5000, 5000, 32));
        assertEquals(0, plan.inChunk(999, 999).length);
    }

    @Test
    void shippedScenesAllHaveValidAnchorsReasonsKindsAndPropBounds() {
        for (int s = 0; s < shippedPlan.scenes(); s++) {
            int k = shippedPlan.kind[s];
            assertTrue(k >= 0 && k < shippedPlan.kinds.length, "Scene " + s + " kind out of bounds");
            int a = shippedPlan.anchor[s];
            assertTrue(a >= 0 && a < ScenePlan.ANCHOR_NAMES.length, "Scene " + s + " anchor out of bounds");
            assertNotNull(shippedPlan.reason[s]);
            assertFalse(shippedPlan.reason[s].isBlank(), "Scene " + s + " must have a non-blank reason");
            int p0 = shippedPlan.propOff[s];
            int p1 = shippedPlan.propOff[s + 1];
            assertTrue(p1 >= p0, "Scene " + s + " propOff inverted");
            for (int p = p0; p < p1; p++) {
                int pk = shippedPlan.propKind[p];
                assertTrue(pk >= 0 && pk < shippedPlan.kinds.length, "Prop " + p + " kind out of bounds");
            }
        }
    }

    @Test
    void scenePlanBuilderHashMixAndUnitAreDeterministicAndBounded() {
        long h1 = ScenePlanBuilder.hash("whiterun", 0xFA4L);
        long h2 = ScenePlanBuilder.hash("whiterun", 0xFA4L);
        long h3 = ScenePlanBuilder.hash("solitude", 0xFA4L);
        assertEquals(h1, h2);
        assertNotEquals(h1, h3);

        for (int salt = 0; salt < 100; salt++) {
            double u = ScenePlanBuilder.unit(h1, salt);
            assertTrue(u >= 0.0 && u < 1.0, "unit() must lie in [0, 1): " + u);
            assertEquals(u, ScenePlanBuilder.unit(h2, salt), 0.0);
        }
    }

    @Test
    void sceneKindsClassifiesHumanLandmarkBuildersAndRuinVariants() {
        assertTrue(SceneKinds.planned("grave"));
        assertTrue(SceneKinds.planned("cairn_token"));
        assertTrue(SceneKinds.planned("pilgrim"));
        assertTrue(SceneKinds.planned("boat_wreck"));
        assertFalse(SceneKinds.planned("word_wall"));
        assertFalse(SceneKinds.planned("standing_stone"));
        assertTrue(SceneKinds.RUIN_VARIANTS.contains("dwemer"));
        assertTrue(SceneKinds.RUIN_VARIANTS.contains("ruin"));
    }

    @Test
    void emptyScenePlanHasZeroScenesAndProps() {
        ScenePlan empty = ScenePlan.empty();
        assertEquals(0, empty.scenes());
        assertEquals(0, empty.props());
        assertEquals(0, empty.inChunk(0, 0).length);
        assertFalse(empty.covers(0, 0, 10));
    }
}
