package com.nirncraft.tamriel.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.MapSample;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sharp (horn) peaks, drawn analytically on top of the 32-block map raster at full resolution: the Throat of the
 * World and Mount Anthor. A 32-block raster can only hold a rounded dome; these get a pointed summit, aretes running
 * out along authored bearings (the peak reaches further along its spurs, so the contours bulge into ridges), level
 * shelves (High Hrothgar's ledge) and calm corridors along paths (the 7,000 Steps), where the seeded crag detail is
 * suppressed so the path stays climbable.
 *
 * <p>Loaded from {@code nirncraft/tamriel/peaks.json}, written by {@code tools/worldgen/tamriel_map.py}
 * ({@code peak_profile} and {@code sharp_lift} there are the same shape; the raster itself leaves these peaks out).
 * Immutable after loading; {@link #apply} is allocation-free and thread-safe.</p>
 */
public final class MapPeaks {
    private static final String PATH = "/nirncraft/tamriel/peaks.json";
    /** Bearing table resolution: the per-bearing reach (wobble and spurs) is precomputed. */
    private static final int BINS = 720;
    private static final double TWO_PI = Math.PI * 2;

    private static final class Peak {
        String name;
        double x, z, height, shape, power, hornHeight, hornRadius, calderaRadius, calderaDepth, jag, maxReach;
        float[] reach;            // per bearing bin
        double[][] shelves;       // x, z, radius, level
        double[][] clear;         // x, z, radius
        double[][][] paths;       // polylines of x, z, graded level
        int minX, maxX, minZ, maxZ;
    }

    private static final Peak[] PEAKS = load();
    /** The union of the peaks' boxes: almost every column is outside it. */
    private static final int MIN_X, MAX_X, MIN_Z, MAX_Z;

    static {
        int a = Integer.MAX_VALUE, b = Integer.MIN_VALUE, c = Integer.MAX_VALUE, d = Integer.MIN_VALUE;
        for (Peak p : PEAKS) {
            a = Math.min(a, p.minX);
            b = Math.max(b, p.maxX);
            c = Math.min(c, p.minZ);
            d = Math.max(d, p.maxZ);
        }
        MIN_X = a;
        MAX_X = b;
        MIN_Z = c;
        MAX_Z = d;
    }

    private MapPeaks() {}

    /** Total number of analytical peaks loaded from {@code peaks.json}. */
    public static int count() {
        return PEAKS.length;
    }

    /** Returns true if a named peak is present in {@code peaks.json}. */
    public static boolean hasPeak(String name) {
        return find(name) != null;
    }

    /** Configured summit height of a named peak, or {@code 0} if absent. */
    public static double peakHeight(String name) {
        Peak p = find(name);
        return p != null ? p.height : 0;
    }

    /** Configured caldera radius of a named peak, or {@code 0} if absent/non-volcanic. */
    public static double calderaRadius(String name) {
        Peak p = find(name);
        return p != null ? p.calderaRadius : 0;
    }

    /** Configured caldera depth of a named peak, or {@code 0} if absent/non-volcanic. */
    public static double calderaDepth(String name) {
        Peak p = find(name);
        return p != null ? p.calderaDepth : 0;
    }

    /** Number of authored shelves on a named peak, or {@code 0} if absent. */
    public static int shelfCount(String name) {
        Peak p = find(name);
        return p != null ? p.shelves.length : 0;
    }

    /** Number of authored graded ascent paths on a named peak, or {@code 0} if absent. */
    public static int pathCount(String name) {
        Peak p = find(name);
        return p != null ? p.paths.length : 0;
    }

    /** Block coordinates {@code [x, z]} of a named peak's center, or {@code null} if absent. */
    public static int[] peakCoords(String name) {
        Peak p = find(name);
        return p != null ? new int[]{(int) Math.round(p.x), (int) Math.round(p.z)} : null;
    }

    private static Peak find(String name) {
        if (name == null) return null;
        for (Peak p : PEAKS) {
            if (name.equals(p.name)) return p;
        }
        return null;
    }

    /**
     * Raises a raster sample by the sharp peaks over it: {@code out.elevation} gets the peak, {@code out.peak} the
     * amplitude of its crag detail in blocks and {@code out.calm} how much of the seeded detail to suppress.
     */
    public static void apply(int x, int z, MapSample out) {
        if (x < MIN_X || x > MAX_X || z < MIN_Z || z > MAX_Z) return;
        for (Peak p : PEAKS) {
            if (x < p.minX || x > p.maxX || z < p.minZ || z > p.maxZ) continue;
            double dx = x - p.x, dz = z - p.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            double reach = reachAt(p, Math.atan2(dz, dx));
            if (r >= reach) continue;
            // (1 - u^shape)^power, damped in road corridors and settlement pads so they stay passes and level ground
            double u = r / reach, ua = StrictMath.pow(u, p.shape);
            double prof = StrictMath.pow(1 - ua, p.power) * (1 - 0.85 * out.road);
            // the massif's own steepness (d height / d r), for the cliff bands and the detail damping
            double dprof = p.power * StrictMath.pow(Math.max(1e-6, 1 - ua), p.power - 1) * p.shape * ua / Math.max(u, 1e-6) / reach;
            out.slope = (float) Math.max(out.slope, Math.max(p.height - p.hornHeight - out.elevation, 0) * dprof
                    * (1 - 0.85 * out.road));
            for (double[] c : p.clear) {
                prof *= MapNoise.smoothstep(c[2] * 0.8, c[2] + 60, Math.hypot(x - c[0], z - c[1]));
            }
            double base = out.elevation;
            double y = base + Math.max(p.height - p.hornHeight - base, 0) * prof;
            double calm = 0;
            if (p.calderaRadius > 0) {
                if (r >= p.calderaRadius) {
                    double hr = r - p.calderaRadius;
                    if (hr < p.hornRadius) y += p.hornHeight * StrictMath.pow(1 - hr / p.hornRadius, 1.5);
                } else {
                    double cu = r / p.calderaRadius;
                    y += p.hornHeight - p.calderaDepth * StrictMath.pow(1 - cu * cu, 1.35);
                    calm = Math.max(calm, 0.65 * (1 - cu * cu));
                }
            } else if (r < p.hornRadius) {
                y += p.hornHeight * StrictMath.pow(1 - r / p.hornRadius, 1.5);
            }
            for (double[] s : p.shelves) {
                double d = Math.hypot(x - s[0], z - s[1]);
                if (d >= s[2] + 40) continue;
                double w = 1 - MapNoise.smoothstep(s[2], s[2] + 40, d);
                y += (s[3] - y) * w;
                calm = Math.max(calm, 1 - MapNoise.smoothstep(s[2] - 8, s[2] + 24, d));
            }
            for (double[][] path : p.paths) {
                // the path's ledge, graded evenly from its foot to its end, cut into (or built out from) the slope
                double best = Double.MAX_VALUE, level = 0;
                for (int i = 0; i + 1 < path.length; i++) {
                    double ax = path[i][0], az = path[i][1], bx = path[i + 1][0], bz = path[i + 1][1];
                    double vx = bx - ax, vz = bz - az;
                    double l2 = vx * vx + vz * vz;
                    double t = l2 > 0 ? MapNoise.clamp(((x - ax) * vx + (z - az) * vz) / l2, 0, 1) : 0;
                    double ex = x - (ax + t * vx), ez = z - (az + t * vz);
                    double d2 = ex * ex + ez * ez;
                    if (d2 < best) {
                        best = d2;
                        level = path[i][2] + (path[i + 1][2] - path[i][2]) * t;
                    }
                }
                double d = Math.sqrt(best);
                // deeper cuts and fills get wider banks (talus about 1.25:1), never sheer walls
                double bank = 12 + Math.min(48, 0.8 * Math.abs(level - y));
                if (d < bank) {
                    y += (level - y) * (1 - MapNoise.smoothstep(2.5, bank, d));
                    calm = Math.max(calm, 1 - MapNoise.smoothstep(3, 14, d));
                }
            }
            out.elevation = (float) y;
            // full crags down the flanks, fewer right at the top so the summit stays one point
            double jag = p.jag * MapNoise.smoothstep(0, 0.15, prof) * (1 - 0.5 * MapNoise.smoothstep(0.75, 1, prof));
            out.peak = (float) Math.max(out.peak, jag);
            out.calm = (float) Math.max(out.calm, calm);
        }
    }

    private static double reachAt(Peak p, double bearing) {
        double f = (bearing + Math.PI) / TWO_PI * BINS;
        int i = (int) Math.floor(f);
        double t = f - i;
        float a = p.reach[Math.floorMod(i, BINS)], b = p.reach[Math.floorMod(i + 1, BINS)];
        return a + (b - a) * t;
    }

    // -----------------------------------------------------------------------------------------------------------------
    private static Peak[] load() {
        List<Peak> out = new ArrayList<>();
        try (InputStream in = MapPeaks.class.getResourceAsStream(PATH)) {
            if (in == null) {
                NirnCraft.LOGGER.warn("No {}: the map's sharp peaks are missing", PATH);
                return new Peak[0];
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("peaks")) {
                JsonObject o = e.getAsJsonObject();
                Peak p = new Peak();
                p.name = o.has("name") ? o.get("name").getAsString() : "";
                p.x = o.get("x").getAsDouble();
                p.z = o.get("z").getAsDouble();
                p.height = o.get("height").getAsDouble();
                p.shape = o.has("shape") ? o.get("shape").getAsDouble() : 1;
                p.power = o.get("power").getAsDouble();
                JsonArray horn = o.has("horn") ? o.getAsJsonArray("horn") : null;
                p.hornHeight = horn != null ? horn.get(0).getAsDouble() : 0;
                p.hornRadius = horn != null ? Math.max(1, horn.get(1).getAsDouble()) : 1;
                p.calderaRadius = o.has("calderaRadius") ? Math.max(0, o.get("calderaRadius").getAsDouble()) : 0;
                p.calderaDepth = o.has("calderaDepth") ? Math.max(0, o.get("calderaDepth").getAsDouble()) : 0;
                p.jag = o.has("jag") ? o.get("jag").getAsDouble() : 20;
                double reach = o.get("reach").getAsDouble();
                JsonArray spurs = o.getAsJsonArray("spurs");
                p.reach = new float[BINS];
                for (int i = 0; i < BINS; i++) {
                    double th = -Math.PI + TWO_PI * i / BINS;
                    double r = reach * (1 + 0.06 * (0.5 * StrictMath.sin(3 * th + 1.3) + 0.3 * StrictMath.sin(7 * th + 0.4)
                            + 0.2 * StrictMath.sin(11 * th + 2.1)));
                    for (JsonElement se : spurs) {
                        JsonArray s = se.getAsJsonArray();
                        double da = th - Math.toRadians(s.get(0).getAsDouble());
                        da -= TWO_PI * Math.round(da / TWO_PI);
                        double q = Math.toDegrees(da) / s.get(1).getAsDouble();
                        r *= 1 + s.get(2).getAsDouble() * StrictMath.exp(-q * q);
                    }
                    p.reach[i] = (float) r;
                    p.maxReach = Math.max(p.maxReach, r);
                }
                p.shelves = rows(o, "shelves");
                p.clear = rows(o, "clear");
                JsonArray paths = o.has("paths") ? o.getAsJsonArray("paths") : new JsonArray();
                p.paths = new double[paths.size()][][];
                for (int i = 0; i < paths.size(); i++) p.paths[i] = rows(paths.get(i).getAsJsonArray());
                int m = (int) Math.ceil(p.maxReach) + 2;
                p.minX = (int) Math.floor(p.x) - m;
                p.maxX = (int) Math.ceil(p.x) + m;
                p.minZ = (int) Math.floor(p.z) - m;
                p.maxZ = (int) Math.ceil(p.z) + m;
                out.add(p);
            }
        } catch (Exception e) {
            NirnCraft.LOGGER.error("Could not read {}", PATH, e);
        }
        return out.toArray(new Peak[0]);
    }

    private static double[][] rows(JsonObject o, String key) {
        return o.has(key) ? rows(o.getAsJsonArray(key)) : new double[0][];
    }

    private static double[][] rows(JsonArray a) {
        double[][] r = new double[a.size()][];
        for (int i = 0; i < a.size(); i++) {
            JsonArray row = a.get(i).getAsJsonArray();
            r[i] = new double[row.size()];
            for (int j = 0; j < row.size(); j++) r[i][j] = row.get(j).getAsDouble();
        }
        return r;
    }
}
