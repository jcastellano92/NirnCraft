package com.nirncraft.qa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks camera poses for a place from the terrain alone (no chunks): a ring of candidates around the place, scored on
 * a clear line of sight to its middle and its footprint, clearance from the hillside, somewhere to stand (approach
 * shots), and the sun behind the camera. Shared by the in-game tour (/nirncraft-tour, the world's own seed) and the
 * offline harness planner (native/java/mod-integration/harness CameraPlanHarness), so both pick the same shots.
 * Trees and buildings are not in the height field: callers that need certainty check the pose in the real world.
 */
public final class CameraPlanner {
    private CameraPlanner() {}

    /** The world's columns: terrain height and surface (terrain or the water on it). Must be thread-safe if shared. */
    public interface Ground {
        double terrain(int x, int z);

        double surface(int x, int z);
    }

    public enum Kind { AERIAL, APPROACH }

    /** A pose: the camera and the point it looks at; yaw / pitch in Minecraft's convention. */
    public record Shot(Kind kind, double x, double y, double z, double lookX, double lookY, double lookZ, double score,
                       double visible) {
        public float yaw() {
            return (float) Math.toDegrees(Math.atan2(-(lookX - x), lookZ - z));
        }

        public float pitch() {
            double h = Math.hypot(lookX - x, lookZ - z);
            return (float) -Math.toDegrees(Math.atan2(lookY - y, h));
        }
    }

    /** Memoises a Ground per column (rays revisit columns); one per planning call, not thread-safe itself. */
    public static final class Cached implements Ground {
        private final Ground g;
        private final Map<Long, double[]> cache = new HashMap<>();

        public Cached(Ground g) { this.g = g; }

        private double[] at(int x, int z) {
            return cache.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), k -> new double[] {g.terrain(x, z), g.surface(x, z)});
        }

        @Override public double terrain(int x, int z) { return at(x, z)[0]; }

        @Override public double surface(int x, int z) { return at(x, z)[1]; }
    }

    static double surface(Ground g, double x, double z) { return g.surface((int) Math.floor(x), (int) Math.floor(z)); }

    static double terrain(Ground g, double x, double z) { return g.terrain((int) Math.floor(x), (int) Math.floor(z)); }

    static boolean water(Ground g, double x, double z) { return surface(g, x, z) > terrain(g, x, z) + 0.5; }

    /** Fraction of the segment that clears the surface (by `clear`, tapering to 0 at the target). */
    static double sight(Ground g, double[] c, double[] t, double clear) {
        double dx = t[0] - c[0], dy = t[1] - c[1], dz = t[2] - c[2];
        int n = Math.max(4, (int) (Math.hypot(dx, dz) / 3));
        int ok = 0;
        for (int i = 1; i < n; i++) {
            double f = (double) i / n;
            if (c[1] + dy * f >= surface(g, c[0] + dx * f, c[2] + dz * f) + clear * (1 - f)) ok++;
        }
        return ok / (double) (n - 1);
    }

    static double highest(Ground g, double x, double z, double r) {
        double top = -1e9;
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j++) top = Math.max(top, surface(g, x + i * r, z + j * r));
        return top;
    }

    static double slope(Ground g, double x, double z) {
        return Math.max(Math.abs(terrain(g, x + 2, z) - terrain(g, x - 2, z)),
                Math.abs(terrain(g, x, z + 2) - terrain(g, x, z - 2))) / 4;
    }

    /** Best-first candidates of one kind for a place at (tx, tz) with the given radius; at most `keep`. */
    public static List<Shot> plan(Ground g, double tx, double tz, double radius, Kind kind, int keep) {
        boolean aerial = kind == Kind.AERIAL;
        double ty = surface(g, tx, tz) + Math.max(6, Math.min(30, radius * 0.08));
        double[] target = {tx, ty, tz};
        double d = aerial ? Math.max(90, Math.min(600, radius * 2.2)) : Math.max(50, Math.min(260, radius * 1.4 + 30));
        List<Shot> out = new ArrayList<>();
        for (int k = 0; k < 16; k++) {
            double th = Math.PI * 2 * k / 16;
            double cx = tx + Math.sin(th) * d, cz = tz + Math.cos(th) * d;
            double ground = surface(g, cx, cz);
            double cy = aerial ? Math.max(ty + d * 0.42, highest(g, cx, cz, 6) + 20) : ground + 2.6;
            if (!aerial && (water(g, cx, cz) || slope(g, cx, cz) > 1.2)) continue;  // somewhere to stand
            if (cy < highest(g, cx, cz, 3) + 1.8) continue;                         // not inside a hill
            double[] cam = {cx, cy, cz};
            double centre = sight(g, cam, target, aerial ? 4 : 1.5);
            double ring = 0;
            for (int r = 0; r < 4; r++) {  // the place's footprint, not only its middle
                double a2 = th + Math.PI / 2 * r + Math.PI / 4;
                double px = tx + Math.sin(a2) * radius * 0.6, pz = tz + Math.cos(a2) * radius * 0.6;
                ring += sight(g, cam, new double[] {px, surface(g, px, pz) + 3, pz}, 1.0) / 4;
            }
            // the sun crosses the southern sky: a camera south of its subject (looking north) sees lit faces
            double sunBehind = 0.5 + 0.5 * Math.cos(th);
            double lookout = aerial ? 0 : Math.min(1, Math.max(0, (ground - surface(g, tx, tz)) / 25));
            double score = centre * 2 + ring * 1.5 + sunBehind * 0.35 + lookout * 0.4 - (water(g, tx, tz) ? 0.3 : 0);
            out.add(new Shot(kind, cx, cy, cz, tx, ty, tz, score, centre * 0.5 + ring * 0.5));
        }
        out.sort((p, q) -> Double.compare(q.score(), p.score()));
        return out.size() > keep ? new ArrayList<>(out.subList(0, keep)) : out;
    }
}
