package com.nirncraft.tamriel.settlement.grammar;

import com.nirncraft.tamriel.settlement.plan.Archetype;
import com.nirncraft.tamriel.settlement.plan.Layouts;
import com.nirncraft.tamriel.settlement.plan.Plan;
import com.nirncraft.tamriel.settlement.plan.Spec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How well a settlement plan reads as a town: built density (building footprints over the area of the town's
 * boundary, the convex hull of its buildings within the town radius), variety (share of neighbouring buildings that
 * differ in footprint, roof and materials), distinct designs, and the amount of street life (props, stalls, pens).
 * The zoning planner's quality gate re-plans thin results; the preview command prints these for before/after.
 *
 * @param density  built footprint / boundary area, 0..1
 * @param variety  share of neighbouring building pairs that look different, 0..1
 * @param coverage built footprint / the disc the town spreads over (centre to its farthest building), 0..1: sprawl
 * @param meanGap  mean wall-to-wall distance from each building to its nearest neighbour, in blocks
 */
public record Metrics(int buildings, int townBuildings, double builtArea, double hullArea, double density, double variety,
                      int distinct, int props, int stalls, int pens, int streets, double streetLength, int walls, int gates,
                      double coverage, double meanGap) {

    /** Minimum density per size (the owner's bar: 35-50% for towns, higher for cities). */
    public static double target(Layouts.Size s) {
        return switch (s) {
            case CITY -> 0.42;
            case TOWN -> 0.36;
            case VILLAGE -> 0.30;
            default -> 0.22;
        };
    }

    public boolean passes(Layouts.Size s) {
        return density >= target(s) && variety >= 0.95;
    }

    public static Metrics of(Plan plan, int from, int townRadius) {
        List<Spec> town = new ArrayList<>();
        int buildings = 0, props = 0, stalls = 0, pens = 0, streets = 0, walls = 0, gates = 0;
        double streetLen = 0;
        for (int i = from; i < plan.specs.size(); i++) {
            Spec s = plan.specs.get(i);
            switch (s.kind) {
                case YARD -> props++;
                case STALL -> stalls++;
                case PEN, GARDEN -> pens++;
                case STREET -> {
                    streets++;
                    streetLen += Math.hypot(s.x2 - s.x, s.z2 - s.z);
                }
                case WALL -> walls++;
                case GATE -> gates++;
                default -> {
                }
            }
            if (!s.kind.building || s.kind == Archetype.TOWER && !s.grammar()) continue;
            buildings++;
            long dx = s.x - plan.cx, dz = s.z - plan.cz;
            if (dx * dx + dz * dz <= (long) townRadius * townRadius) town.add(s);
        }
        double built = 0;
        List<double[]> pts = new ArrayList<>();
        for (Spec s : town) {
            int hx = s.halfX() - s.margin, hz = s.halfZ() - s.margin;
            built += (2.0 * hx + 1) * (2.0 * hz + 1);
            pts.add(new double[]{s.x - hx, s.z - hz});
            pts.add(new double[]{s.x + hx + 1, s.z - hz});
            pts.add(new double[]{s.x - hx, s.z + hz + 1});
            pts.add(new double[]{s.x + hx + 1, s.z + hz + 1});
        }
        double hull = hullArea(pts);
        double density = hull > 1 ? Math.min(1, built / hull) : 0;
        // variety among neighbours
        int pairs = 0, differ = 0;
        int[] sig = new int[town.size()];
        Set<Integer> distinct = new HashSet<>();
        for (int i = 0; i < town.size(); i++) {
            sig[i] = signature(town.get(i));
            distinct.add(sig[i]);
        }
        for (int i = 0; i < town.size(); i++) {
            for (int j = i + 1; j < town.size(); j++) {
                if (!Zoning.neighbours(town.get(i), town.get(j))) continue;
                pairs++;
                if (sig[i] != sig[j]) differ++;
            }
        }
        double variety = pairs == 0 ? 1 : differ / (double) pairs;
        double extent = 0, gapSum = 0;
        for (Spec a : town) {
            int ahx = a.halfX() - a.margin, ahz = a.halfZ() - a.margin;
            extent = Math.max(extent, Math.hypot(a.x - plan.cx, a.z - plan.cz) + Math.max(ahx, ahz));
            double nearest = 64;
            for (Spec b : town) {
                if (a == b) continue;
                int bhx = b.halfX() - b.margin, bhz = b.halfZ() - b.margin;
                double gx = Math.max(0, Math.abs(a.x - b.x) - ahx - bhx - 1), gz = Math.max(0, Math.abs(a.z - b.z) - ahz - bhz - 1);
                nearest = Math.min(nearest, Math.hypot(gx, gz));
            }
            gapSum += nearest;
        }
        double coverage = extent > 0 ? Math.min(1, built / (Math.PI * extent * extent)) : 0;
        double meanGap = town.isEmpty() ? 0 : gapSum / town.size();
        return new Metrics(buildings, town.size(), built, hull, density, variety, distinct.size(), props, stalls, pens, streets, streetLen, walls, gates,
                coverage, meanGap);
    }

    static int signature(Spec s) {
        if (s.grammar()) return Blueprint.of(s).signature();
        // legacy builders draw every building of an archetype, size and style alike
        return ((s.kind.ordinal() * 64 + s.w) * 64 + s.d) * 32 + s.style * 2 + (s.has(Spec.UPPER) ? 1 : 0) + 100000 * s.culture;
    }

    /** Area of the convex hull of the points (Andrew's monotone chain). */
    static double hullArea(List<double[]> pts) {
        if (pts.size() < 3) return 0;
        pts.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = pts.size();
        double[][] h = new double[2 * n][];
        int k = 0;
        for (double[] p : pts) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], p) <= 0) k--;
            h[k++] = p;
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            double[] p = pts.get(i);
            while (k >= t && cross(h[k - 2], h[k - 1], p) <= 0) k--;
            h[k++] = p;
        }
        double area = 0;
        for (int i = 0; i < k - 1; i++) area += h[i][0] * h[i + 1][1] - h[i + 1][0] * h[i][1];
        return Math.abs(area) / 2;
    }

    static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "buildings %d (town %d), density %.0f%% (built %.0f / boundary %.0f m2), coverage %.0f%%, mean gap %.1f, "
                        + "variety %.0f%%, %d designs, props %d, stalls %d, pens/gardens %d, streets %d (%.0f m), walls %d, gates %d",
                buildings, townBuildings, density * 100, builtArea, hullArea, coverage * 100, meanGap, variety * 100, distinct, props, stalls, pens,
                streets, streetLength, walls, gates);
    }
}
