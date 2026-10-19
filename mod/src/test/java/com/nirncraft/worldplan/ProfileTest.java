package com.nirncraft.worldplan;

import com.nirncraft.roads.PlanLinks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 unit tests for {@link Profile}: verifies grade caps across all road classes on synthetic terrain,
 * endpoint pinning, water clearance on bridges/culverts/fords, and cut/fill/tunnel/stairs span classification.
 */
class ProfileTest {

    private static RoadPlanBuilder.Poly dryPoly(int n, double spacing, float[] ground) {
        List<double[]> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            pts.add(new double[]{i * spacing, 0.0});
        }
        RoadPlanBuilder.Poly p = new RoadPlanBuilder.Poly(pts);
        System.arraycopy(ground, 0, p.g, 0, n);
        Arrays.fill(p.w, Float.NaN);
        Arrays.fill(p.near, Float.NaN);
        return p;
    }

    @Test
    void wetEdgeBetweenDryVerticesCarriesAContinuousCulvertAndDeckFloor() {
        var p=dryPoly(4,4,new float[]{65,65,65,65});
        p.edgeWet=new boolean[]{false,true,false,false};
        p.edgeWater=new float[]{0,66,0,0}; p.edgeWidth=new float[]{0,3,0,0};
        p.edgeDepth=new float[]{0,1,0,0}; p.edgeLakeSea=new boolean[4];
        Profile.run(p,PlanLinks.ROAD,Float.NaN,Float.NaN);
        for(int i=1;i<=2;i++) {
            assertEquals(RoadPlan.CULVERT,p.span[i]);
            assertTrue(p.h[i]>=68);
        }
        assertTrue(maxEdgeGrade(p)<=0.125+1e-4);
    }

    private static double maxEdgeGrade(RoadPlanBuilder.Poly p) {
        double max = 0.0;
        for (int i = 0; i + 1 < p.n; i++) {
            double ds = p.s[i + 1] - p.s[i];
            assertTrue(ds > 0, "Arc-length must be strictly increasing");
            double g = Math.abs(p.h[i + 1] - p.h[i]) / ds;
            if (g > max) max = g;
        }
        return max;
    }

    @Test
    void highwayStaysUnderOneInEightGradeCapOnSyntheticCliffAndSpike() {
        int n = 40;
        float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            // Sudden 30-block cliff at i=15 and a sharp 25-block spire at i=28
            g[i] = (i < 15 ? 65f : 95f) + (i == 28 ? 25f : 0f);
        }
        RoadPlanBuilder.Poly p = dryPoly(n, RoadPlanBuilder.SPACING, g);
        Profile.run(p, PlanLinks.HIGHWAY, Float.NaN, Float.NaN);

        double maxGrade = maxEdgeGrade(p);
        assertTrue(maxGrade <= 0.125 + 1e-4, "Highway grade " + maxGrade + " exceeded 1/8 (0.125) cap");
    }

    @Test
    void holdRoadStaysUnderOneInEightGradeCapOnSyntheticMountainAndRavine() {
        int n = 50;
        float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            g[i] = (float) (75.0 + 35.0 * Math.sin(i * 0.35) - (i >= 22 && i <= 26 ? 28.0 : 0.0));
        }
        RoadPlanBuilder.Poly p = dryPoly(n, RoadPlanBuilder.SPACING, g);
        Profile.run(p, PlanLinks.ROAD, Float.NaN, Float.NaN);

        double maxGrade = maxEdgeGrade(p);
        assertTrue(maxGrade <= 0.125 + 1e-4, "Hold road grade " + maxGrade + " exceeded 1/8 (0.125) cap");
    }

    @Test
    void laneStaysUnderOneInFiveGradeCapOnSyntheticSteepRidge() {
        int n = 36;
        float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            g[i] = i < 18 ? 64f + i * 3.5f : 127f - (i - 18) * 3.5f;
        }
        RoadPlanBuilder.Poly p = dryPoly(n, RoadPlanBuilder.SPACING, g);
        Profile.run(p, PlanLinks.LANE, Float.NaN, Float.NaN);

        double maxGrade = maxEdgeGrade(p);
        assertTrue(maxGrade <= 0.20 + 1e-4, "Lane grade " + maxGrade + " exceeded 1/5 (0.20) cap");
    }

    @Test
    void trailStaysUnderTrailGradeCapAndMarksSteepStepsAsStairs() {
        int n = 30;
        float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            // Very steep 8m steps (+8 blocks every 4m = 200% ground slope, exceeding 1.0 trail cap)
            g[i] = 64f + i * 8.0f;
        }
        RoadPlanBuilder.Poly p = dryPoly(n, RoadPlanBuilder.SPACING, g);
        Profile.run(p, PlanLinks.TRAIL, Float.NaN, Float.NaN);

        double maxGrade = maxEdgeGrade(p);
        assertTrue(maxGrade <= RoadPlanBuilder.gradeCap(PlanLinks.TRAIL) + 1e-4,
                "Trail grade " + maxGrade + " exceeded trail cap");

        boolean hasStairs = false;
        for (byte sp : p.span) {
            if (sp == RoadPlan.STAIRS) {
                hasStairs = true;
                break;
            }
        }
        assertTrue(hasStairs, "Steep trail above STAIRS_GRADE must mark vertices as STAIRS");
    }

    @Test
    void pinnedEndpointsAreExactAndPreserveGradeCap() {
        int n = 32;
        float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            g[i] = 70f + (i >= 10 && i <= 20 ? 18f : 0f);
        }
        RoadPlanBuilder.Poly p = dryPoly(n, RoadPlanBuilder.SPACING, g);
        float pinA = 68.0f;
        float pinB = 74.0f;
        Profile.run(p, PlanLinks.HIGHWAY, pinA, pinB);

        assertEquals(pinA, p.h[0], 1e-4f, "Start vertex must match pinA");
        assertEquals(pinB, p.h[n - 1], 1e-4f, "End vertex must match pinB");
        double maxGrade = maxEdgeGrade(p);
        assertTrue(maxGrade <= 0.125 + 1e-4, "Pinned highway grade " + maxGrade + " exceeded 1/8 cap");
    }

    @Test
    void cutAndFillSpansAreAssignedWhenProfileDivergesFromGround() {
        int n = 40;
        float[] g = new float[n];
        Arrays.fill(g, 70f);
        // Sharp crest at i=11 (forces CUT > 2m) and moderate dip at i=27 (2m < depth <= 3m GAP so it stays FILL,
        // while a deeper dip at i=34 > 3m GAP becomes a gap BRIDGE)
        g[11] = 75f;
        g[27] = 66.2f;
        g[34] = 62f;

        RoadPlanBuilder.Poly p = dryPoly(n, RoadPlanBuilder.SPACING, g);
        Profile.run(p, PlanLinks.HIGHWAY, 70f, 70f);

        assertEquals(RoadPlan.CUT, p.span[11], "Sharp crest should be classified as CUT");
        assertEquals(RoadPlan.FILL, p.span[27], "Moderate dip (2..3m) should be classified as FILL");
        assertEquals(RoadPlan.BRIDGE, p.span[34], "Deep short gap (>3m) should be upgraded to gap BRIDGE");
    }

    @Test
    void bridgeAndCulvertSpansRespectWaterClearance() {
        int n = 30;
        float[] g = new float[n];
        Arrays.fill(g, 65f);
        RoadPlanBuilder.Poly p = dryPoly(n, RoadPlanBuilder.SPACING, g);

        // Wide river at i=10..14 (width 16 >= CULVERT_WIDTH)
        for (int i = 10; i <= 14; i++) {
            p.g[i] = 56f;
            p.w[i] = 63f;
            p.width[i] = 16f;
        }
        Profile.run(p, PlanLinks.ROAD, 65f, 65f);

        float clearance = RoadPlanBuilder.clearance(PlanLinks.ROAD);
        for (int i = 10; i <= 14; i++) {
            assertEquals(RoadPlan.BRIDGE, p.span[i], "Wide river crossing must be BRIDGE");
            assertTrue(p.h[i] >= p.w[i] + clearance - 1e-3f,
                    "Bridge deck height " + p.h[i] + " must be >= water + clearance (" + (p.w[i] + clearance) + ")");
        }
        // Abutments on dry banks i=9 and i=15 also carry BRIDGE span
        assertEquals(RoadPlan.BRIDGE, p.span[9]);
        assertEquals(RoadPlan.BRIDGE, p.span[15]);
        assertTrue(maxEdgeGrade(p) <= 0.125 + 1e-4, "Bridge approaches must obey grade cap");
    }

    @Test
    void envelopeProducesLipchitzBoundedUpperAndLowerCurves() {
        double[] s = {0, 4, 8, 12, 16, 20, 24};
        float[] target = {70f, 70f, 90f, 70f, 50f, 70f, 70f};
        float[] below = new float[s.length];
        float[] above = new float[s.length];
        float cap = 0.25f;

        Profile.envelope(s, target, cap, below, true);
        Profile.envelope(s, target, cap, above, false);

        for (int i = 0; i < s.length; i++) {
            assertTrue(below[i] <= target[i] + 1e-4f, "Lower envelope must lie <= target");
            assertTrue(above[i] >= target[i] - 1e-4f, "Upper envelope must lie >= target");
            if (i + 1 < s.length) {
                double ds = s[i + 1] - s[i];
                assertTrue(Math.abs(below[i + 1] - below[i]) / ds <= cap + 1e-4f);
                assertTrue(Math.abs(above[i + 1] - above[i]) / ds <= cap + 1e-4f);
            }
        }
    }
}
