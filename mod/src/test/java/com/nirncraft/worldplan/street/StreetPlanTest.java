package com.nirncraft.worldplan.street;

import com.nirncraft.bake.Bake;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.settlement.plan.Archetype;
import com.nirncraft.tamriel.settlement.plan.Cities;
import com.nirncraft.tamriel.settlement.plan.Spec;
import com.nirncraft.worldplan.PlanGround;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for {@link StreetPlan}, {@link StreetPlanBuilder}, and settlement seed-independence.
 */
class StreetPlanTest {

    private static byte[] shippedBytes;
    private static StreetPlan shippedPlan;

    @BeforeAll
    static void loadShippedStreetBake() throws Exception {
        try (InputStream raw = Bake.class.getResourceAsStream("/nirncraft/baked/streetplan.bin")) {
            assertNotNull(raw, "Missing /nirncraft/baked/streetplan.bin on classpath");
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
        shippedPlan = StreetPlan.read(shippedBytes);
    }

    private static StreetPlan buildSyntheticStreetPlan(long ambientSeed) {
        new Random(ambientSeed).nextLong();
        StreetPlanBuilder.Result r = new StreetPlanBuilder.Result();
        Place p = new Place("riverwood_synth", net.minecraft.network.chat.Component.literal("Riverwood"), PlaceType.VILLAGE, "nord", "skyrim/whiterun", 200, 300, 48, "");
        r.id = p.id();
        r.x = p.x();
        r.z = p.z();
        r.reach = 48;

        // Node 0: GATE node at (200, 260, 68), joined to road segment 3, vertex 14
        int nGate = r.node(200f, 260f, 68f, StreetPlan.N_GATE);
        r.nSeg.set(nGate, 3);
        r.nVert.set(nGate, 14);
        // Node 1: STREET node at (200, 300, 70)
        int nStreet = r.node(200f, 300f, 70f, StreetPlan.N_STREET);
        // Node 2: DOOR node at (208, 300, 70)
        int nDoor = r.node(208f, 300f, 70f, StreetPlan.N_DOOR);

        // Edge 0: MAIN street from gate to centre (width 4)
        r.edge(nGate, nStreet, 4.0f, StreetPlan.MAIN, StreetPlan.PAVED);
        // Edge 1: PATH from door to street (width 1)
        r.edge(nDoor, nStreet, 1.0f, StreetPlan.PATH, StreetPlan.BEATEN);

        // Door 0 linked to nDoor
        r.doorSpec.add(0);
        r.doorX.add(208);
        r.doorY.add(71);
        r.doorZ.add(300);
        r.doorNode.add(nDoor);
        r.doorFacing.add((byte) Direction.WEST.get2DDataValue());
        r.doorReach.add((byte) 1);

        // Plot 0: building at [208..216, 296..304] with pad 70
        r.plot(0, new int[]{208, 296, 216, 304}, 70, StreetPlan.PLOT_BUILDING);

        Spec spec = new Spec(Archetype.HOUSE);
        spec.culture = 0;
        spec.style = 1;
        spec.x = 212;
        spec.z = 300;
        spec.w = 9;
        spec.d = 9;
        spec.h = 6;
        spec.facing = Direction.WEST;
        spec.floor = 70;
        spec.gseed = (int) Cities.seed(p);
        spec.name = "Sleeping Giant";
        r.specs = List.of(spec);

        return StreetPlanBuilder.assemble(List.of(r));
    }

    @Test
    void buildTwiceUnderDifferentSeedsIsByteIdentical() throws IOException {
        StreetPlan a = buildSyntheticStreetPlan(123456789L);
        StreetPlan b = buildSyntheticStreetPlan(987654321L);
        assertArrayEquals(a.write(), b.write(), "StreetPlan built under different seeds must be byte-identical");
    }

    @Test
    void everyDoorLinksToValidSettlementNodeInShippedAndSyntheticPlans() {
        assertTrue(shippedPlan.settlements() > 0, "Shipped streetplan.bin must have settlements");
        assertTrue(shippedPlan.doors() > 0, "Shipped streetplan.bin must have doors");

        for (StreetPlan plan : List.of(buildSyntheticStreetPlan(1L), shippedPlan)) {
            for (int s = 0; s < plan.settlements(); s++) {
                int n0 = plan.sNodeOff[s];
                int n1 = plan.sNodeOff[s + 1];
                int d0 = plan.sDoorOff[s];
                int d1 = plan.sDoorOff[s + 1];
                for (int d = d0; d < d1; d++) {
                    int node = plan.dNode[d];
                    assertTrue(node >= n0 && node < n1,
                            "Door " + d + " in settlement " + plan.sId[s] + " must link to a node in [" + n0 + ", " + n1 + "), got " + node);
                }
            }
        }
    }

    @Test
    void everyGateNodeCarriesValidRoadJoinInShippedAndSyntheticPlans() {
        int totalGates = 0;
        for (StreetPlan plan : List.of(buildSyntheticStreetPlan(1L), shippedPlan)) {
            for (int n = 0; n < plan.nodes(); n++) {
                if (plan.nClass[n] == StreetPlan.N_GATE) {
                    totalGates++;
                    assertTrue(plan.nJoinSeg[n] >= 0, "GATE node " + n + " must carry a valid road segment join (nJoinSeg >= 0)");
                    assertTrue(plan.nJoinVert[n] >= 0, "GATE node " + n + " must carry a valid road vertex join (nJoinVert >= 0)");
                }
            }
        }
        assertTrue(totalGates > 0, "Must have tested at least one N_GATE node");
    }

    @Test
    void everyRoadJoinNodeCarriesJoinAndNonJoinNodesHaveMinusOne() {
        for (int n = 0; n < shippedPlan.nodes(); n++) {
            byte cls = shippedPlan.nClass[n];
            if (cls == StreetPlan.N_GATE || cls == StreetPlan.N_ROAD) {
                assertTrue(shippedPlan.nJoinSeg[n] >= 0 && shippedPlan.nJoinVert[n] >= 0,
                        "Node " + n + " of class " + cls + " must carry a road join");
            } else {
                assertEquals(-1, shippedPlan.nJoinSeg[n], "Non-gate/road node " + n + " should have nJoinSeg == -1");
                assertEquals(-1, shippedPlan.nJoinVert[n], "Non-gate/road node " + n + " should have nJoinVert == -1");
            }
        }
    }

    @Test
    void binaryRoundTripPreservesAllArrays() throws IOException {
        StreetPlan orig = buildSyntheticStreetPlan(42L);
        byte[] bytes = orig.write();
        StreetPlan read = StreetPlan.read(bytes);

        assertEquals(orig.settlements(), read.settlements());
        assertEquals(orig.nodes(), read.nodes());
        assertEquals(orig.edges(), read.edges());
        assertEquals(orig.doors(), read.doors());
        assertEquals(orig.plots(), read.plots());

        assertArrayEquals(orig.sId, read.sId);
        assertArrayEquals(orig.sX, read.sX);
        assertArrayEquals(orig.sZ, read.sZ);
        assertArrayEquals(orig.sReach, read.sReach);
        assertArrayEquals(orig.sNodeOff, read.sNodeOff);
        assertArrayEquals(orig.sEdgeOff, read.sEdgeOff);
        assertArrayEquals(orig.sDoorOff, read.sDoorOff);
        assertArrayEquals(orig.sPlotOff, read.sPlotOff);
        assertArrayEquals(orig.sSpecOff, read.sSpecOff);
        assertArrayEquals(orig.nx, read.nx);
        assertArrayEquals(orig.nz, read.nz);
        assertArrayEquals(orig.nh, read.nh);
        assertArrayEquals(orig.nClass, read.nClass);
        assertArrayEquals(orig.nJoinSeg, read.nJoinSeg);
        assertArrayEquals(orig.nJoinVert, read.nJoinVert);
        assertArrayEquals(orig.ea, read.ea);
        assertArrayEquals(orig.eb, read.eb);
        assertArrayEquals(orig.ew, read.ew);
        assertArrayEquals(orig.eClass, read.eClass);
        assertArrayEquals(orig.eSurf, read.eSurf);
        assertArrayEquals(orig.dSpec, read.dSpec);
        assertArrayEquals(orig.dx, read.dx);
        assertArrayEquals(orig.dy, read.dy);
        assertArrayEquals(orig.dz, read.dz);
        assertArrayEquals(orig.dNode, read.dNode);
        assertArrayEquals(orig.dFacing, read.dFacing);
        assertArrayEquals(orig.dReach, read.dReach);
        assertArrayEquals(orig.pSpec, read.pSpec);
        assertArrayEquals(orig.pMinX, read.pMinX);
        assertArrayEquals(orig.pMinZ, read.pMinZ);
        assertArrayEquals(orig.pMaxX, read.pMaxX);
        assertArrayEquals(orig.pMaxZ, read.pMaxZ);
        assertArrayEquals(orig.pPad, read.pPad);
        assertArrayEquals(orig.pKind, read.pKind);
        assertArrayEquals(orig.specInts, read.specInts);
        assertArrayEquals(orig.specNames, read.specNames);
        assertArrayEquals(bytes, read.write());
    }

    @Test
    void shippedStreetPlanBinaryRoundTripIsByteIdentical() throws IOException {
        assertArrayEquals(shippedBytes, shippedPlan.write(), "Shipped streetplan.bin must round-trip byte-for-byte");
    }

    @Test
    void readRejectsInvalidMagicAndVersion() {
        ByteArrayOutputStream badMagic = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (DataOutputStream out = new DataOutputStream(badMagic)) {
                out.writeInt(0xDEAD1234);
                out.writeInt(StreetPlan.VERSION);
            }
        });
        assertThrows(IOException.class, () -> StreetPlan.read(badMagic.toByteArray()));

        ByteArrayOutputStream badVer = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            try (DataOutputStream out = new DataOutputStream(badVer)) {
                out.writeInt(StreetPlan.MAGIC);
                out.writeInt(StreetPlan.VERSION + 1);
            }
        });
        assertThrows(IOException.class, () -> StreetPlan.read(badVer.toByteArray()));
    }

    @Test
    void atContractReturnsNearestStreetEdgeAndInterpolatesHeight() {
        StreetPlan plan = buildSyntheticStreetPlan(1L);
        StreetPlan.Hit hit = new StreetPlan.Hit();

        // Midpoint of Edge 0 from (200, 260, h=68) to (200, 300, h=70) -> (200, 280)
        assertTrue(plan.at(200.0, 280.0, hit));
        assertEquals(0, hit.edge);
        assertEquals(0, hit.settlement);
        assertEquals(StreetPlan.MAIN, hit.cls);
        assertEquals(StreetPlan.PAVED, hit.surface);
        assertEquals(4.0f, hit.width, 1e-4f);
        assertEquals(0.0f, hit.distance, 1e-4f);
        assertEquals(0.5f, hit.t, 1e-4f);
        assertEquals(69.0f, hit.height, 1e-4f);

        assertFalse(plan.at(9999.0, 9999.0, hit));
        assertEquals(-1, hit.edge);
    }

    @Test
    void padContractShapesPlotAndStreetBandAndBlendsOutward() {
        StreetPlan plan = buildSyntheticStreetPlan(1L);
        PlanGround.Bed bed = new PlanGround.Bed();

        // Inside building plot [208..216, 296..304] with pad 70
        assertTrue(plan.pad(212, 300, bed));
        assertEquals(1.0f, bed.plotWeight, 1e-4f);
        assertEquals(70.5f, bed.plotHeight, 1e-4f);

        // 3 blocks outside plot (within PAD_BLEND = 4 beyond the 1-block rim)
        assertTrue(plan.pad(219, 300, bed));
        assertTrue(bed.plotWeight > 0.0f && bed.plotWeight < 1.0f, "Plot pad weight must blend in (0, 1)");

        // Far outside
        assertFalse(plan.pad(5000, 5000, bed));
        assertEquals(0.0f, bed.plotWeight, 1e-4f);
    }

    @Test
    void doorsOfAndLayoutRoundTripDecodedSpecs() {
        StreetPlan plan = buildSyntheticStreetPlan(1L);
        List<StreetPlan.Door> doors = plan.doorsOf("riverwood_synth");
        assertEquals(1, doors.size());
        StreetPlan.Door door = doors.get(0);
        assertEquals(208, door.x());
        assertEquals(71, door.y());
        assertEquals(300, door.z());
        assertEquals(Direction.WEST, door.facing());
        assertEquals(2, door.node());
        assertTrue(door.reachable());

        List<Spec> specs = plan.layout("riverwood_synth");
        assertNotNull(specs);
        assertEquals(1, specs.size());
        Spec s = specs.get(0);
        assertEquals(Archetype.HOUSE, s.kind);
        assertEquals(212, s.x);
        assertEquals(300, s.z);
        assertEquals(70, s.floor);
        assertEquals("Sleeping Giant", s.name);

        assertTrue(plan.doorsOf("unknown_place").isEmpty());
        assertNull(plan.layout("unknown_place"));
    }
}



