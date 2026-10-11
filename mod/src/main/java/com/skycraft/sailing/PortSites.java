package com.skycraft.sailing;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.Direction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each port's pier goes, worked out from the Tamriel map alone (so the server and the client map agree, and
 * no chunk is loaded): the nearest navigable water to the port's hint (open sea, or a broad river at sea level such
 * as the White River at Windhelm), and the cardinal direction from there to the shore. Expedition landings march
 * from their approach port towards the foreign land and stop at its first coast. Thread-safe; results are cached.
 */
public final class PortSites {
    /** A pier site: open water, the shore behind it (map estimate) and the cardinal direction water -> land. */
    public record Site(int waterX, int waterZ, int shoreX, int shoreZ, Direction toLand) {
        /** The pier's rough position for maps and the compass: a few blocks off the shore. */
        public int pierX() {
            return shoreX - toLand.getStepX() * 10;
        }

        public int pierZ() {
            return shoreZ - toLand.getStepZ() * 10;
        }
    }

    private static final Map<String, Site> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_SEARCH = 1600;

    private PortSites() {}

    public static Site of(Port port) {
        Site s = CACHE.get(port.id());
        if (s != null) return s;
        s = locate(port);
        CACHE.put(port.id(), s);
        return s;
    }

    private static Site locate(Port port) {
        if (port.dock() != null) return shoreFrom(port.dock()[0], port.dock()[1], port.x(), port.z());
        if (port.isLanding()) {
            Port from = Ports.byId(port.approach()).orElse(null);
            if (from != null) {
                Site start = of(from);
                Site landing = landingFrom(start.waterX(), start.waterZ(), port.x(), port.z(), port.land());
                if (landing != null) return landing;
            }
        }
        int[] w = nearestWater(port.x(), port.z());
        if (w == null) return new Site(port.x(), port.z(), port.x(), port.z(), Direction.NORTH);
        return shoreFrom(w[0], w[1], port.x(), port.z());
    }

    // ------------------------------------------------------------------ water

    /** Sea deep enough for a pier and a ship (not a pond: inland lakes report their own water level). */
    static boolean sea(int x, int z) {
        MapSample s = TamrielMap.sampleScratch(x, z);
        return s.elevation < 58f && (Float.isNaN(s.waterLevel) || s.waterLevel <= 63.6f);
    }

    /** A broad river flowing at sea level (navigable up from its mouth). */
    static boolean river(int x, int z) {
        MapSample s = TamrielMap.sampleScratch(x, z);
        return !Float.isNaN(s.waterLevel) && s.waterLevel <= 63.6f && s.riverHalfWidth >= 8
                && s.riverDistance < s.riverHalfWidth * 0.5f;
    }

    static boolean land(int x, int z) {
        MapSample s = TamrielMap.sampleScratch(x, z);
        return s.elevation >= 63.5f && !s.inWater();
    }

    private static boolean openSea(int x, int z) {
        int n = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            if (TamrielMap.elevation(x + (int) Math.round(64 * Math.cos(a)), z + (int) Math.round(64 * Math.sin(a))) < 62f) n++;
        }
        return n >= 3;
    }

    /**
     * The nearest navigable water to (x, z): open sea close by, else the middle of a broad river at sea level (the
     * White River at Windhelm, the Karth at Solitude), else open sea farther out.
     */
    static int[] nearestWater(int x, int z) {
        int[] w = ring(x, z, 0, 400, 16, true);
        if (w == null) w = ring(x, z, 0, 600, 8, false);
        if (w == null) w = ring(x, z, 400, MAX_SEARCH, 16, true);
        return w;
    }

    private static int[] ring(int x, int z, int from, int to, int step, boolean sea) {
        for (int r = from; r < to; r += step) {
            int n = Math.max(1, (int) (2 * Math.PI * r / step));
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n;
                int px = (int) Math.round(x + r * Math.cos(a)), pz = (int) Math.round(z + r * Math.sin(a));
                if (sea ? sea(px, pz) && openSea(px, pz) : river(px, pz)) return new int[]{px, pz};
            }
        }
        return null;
    }

    /** From a water point, the cardinal direction to the nearest shore (preferring the side the hint lies on). */
    static Site shoreFrom(int wx, int wz, int hintX, int hintZ) {
        double hx = hintX - wx, hz = hintZ - wz;
        double hl = Math.sqrt(hx * hx + hz * hz);
        if (hl > 1) {
            hx /= hl;
            hz /= hl;
        }
        Direction best = null;
        int bestDist = 0;
        double bestScore = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int t = 4; t <= 480; t += 4) {
                int x = wx + d.getStepX() * t, z = wz + d.getStepZ() * t;
                if (!land(x, z)) continue;
                double score = t - (hl > 1 ? 80 * (d.getStepX() * hx + d.getStepZ() * hz) : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = d;
                    bestDist = t;
                }
                break;
            }
        }
        if (best == null) return new Site(wx, wz, wx, wz, Direction.NORTH);
        return new Site(wx, wz, wx + best.getStepX() * bestDist, wz + best.getStepZ() * bestDist, best);
    }

    /** Sails from (x0, z0) towards (x1, z1) and lands on the first coast after a stretch of open sea. */
    static Site landingFrom(int x0, int z0, int x1, int z1, String province) {
        double dx = x1 - x0, dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return null;
        dx /= len;
        dz /= len;
        int seaRun = 0;
        int deepX = x0, deepZ = z0;
        for (double t = 0; t < len; t += 24) {
            int x = (int) Math.round(x0 + dx * t), z = (int) Math.round(z0 + dz * t);
            float e = TamrielMap.elevation(x, z);
            if (e < 61f) {
                seaRun += 24;
                if (e < 57f) {
                    deepX = x;
                    deepZ = z;
                }
                continue;
            }
            if (e < 64f) continue; // the beach: neither sea nor land yet
            // the first real land of the foreign coast (skipping islands on the way)
            if (seaRun >= 600 && (province.isEmpty() || province.equals(TamrielMap.region(x, z).province()))) {
                // anchor in the last deep water before the coast (reefs and shelves are shallow), but not too far out
                double back = Math.min(160, Math.max(48, Math.hypot(x - deepX, z - deepZ)));
                int wx = (int) Math.round(x - dx * back), wz = (int) Math.round(z - dz * back);
                return shoreFrom(wx, wz, x, z);
            }
            seaRun = 0;
        }
        return null;
    }
}
