package com.nirncraft.tamriel.settlement.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nirncraft.NirnCraft;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Organic street networks of a city plan ({@code "nets"} in {@code cityplans/<id>.json}). Every net is a smooth
 * curve (Catmull-Rom through the authored via points, or traced over the heightfield) that is cut into short straight
 * street / canal / wall specs with shared integer end points, so the settlement builders (which only know straight
 * segments) draw it as a continuous curve. Streets also register a {@link Plan.Chain} the fabric pass walks to put
 * cardinal-facing houses along them. Everything is deterministic (seeded by the plan) and data driven:
 *
 * <pre>
 * { "type": "path",   "via": [[x,z],..|"@landmark+dx,dz"], "width": 4, "rank": 0, "tag": "", "closed": false, "raise": 0 }
 * { "type": "loop",   "at": [x,z], "rx": 50, "rz": 40, "wobble": 5, "lobes": 3, "phase": 0.4, "width": 3, "rank": 1 }
 * { "type": "radial", "at": [x,z], "spokes": 8, "r0": 14, "r1": 90, "wobble": 5, "offset": 0.3, "skip": [3],
 *                     "rings": [30, 52, 76], "ring_wobble": 4, "width": 3, "ring_width": 3 }
 * { "type": "switchback", "from": [x,z], "to": [x,z], "legs": 5, "amp": 20, "width": 3 }
 * { "type": "contour", "from": [x,z], "len": 120, "both": true, "dir": 0.0, "width": 3, "rank": 1 }
 * { "type": "canal",  "via": [..], "width": 7, "bridges": [0.25, 0.6], "bridge_width": 3 }
 * { "type": "wall",   "via": [..], "closed": true, "h": 9, "towers": true, "gates": [[x,z],..], "flags": ["major"] }
 * { "type": "terraces", "rect": [x0,z0,x1,z1], "bands": 4, "axis": "auto|x|z", "width": 3, "parapet": 3, "stairs": 2 }
 * </pre>
 *
 * A point may also be {@code "@id"} / {@code "@id+dx,dz"}: the centre of the plan's landmark with that id (so a layout
 * can be built round a landmark), or {@code "@gate0"} for a gate.
 */
public final class Nets {
    private Nets() {}

    private static final Pattern REF = Pattern.compile("@([A-Za-z0-9_]+)(?:([+-]\\d+),([+-]?\\d+))?");

    // ------------------------------------------------------------------ points

    /** A local point: {@code [x, z]} or {@code "@landmark+dx,dz"}; null if unresolvable. */
    public static double[] pt(CityPlan cp, JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            return a.size() >= 2 ? new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()} : null;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            Matcher m = REF.matcher(e.getAsString());
            if (!m.matches()) return null;
            String id = m.group(1);
            double ox = m.group(2) != null ? Integer.parseInt(m.group(2).replace("+", "")) : 0;
            double oz = m.group(3) != null ? Integer.parseInt(m.group(3).replace("+", "")) : 0;
            if (id.startsWith("gate") && cp.gates != null) {
                int i = id.length() > 4 ? Integer.parseInt(id.substring(4)) : 0;
                if (i < cp.gates.length) return new double[]{cp.gates[i][0] + ox, cp.gates[i][1] + oz};
            }
            for (CityPlan.Landmark l : cp.landmarks) {
                if (l.id().equals(id) || l.name().equals(id)) return new double[]{l.x() + ox, l.z() + oz};
            }
            for (CityPlan.Building b : cp.buildings) if (b.name().equals(id)) return new double[]{b.x() + ox, b.z() + oz};
        }
        return null;
    }

    public static double[] pt(CityPlan cp, JsonObject o, String key, double dx, double dz) {
        double[] p = o.has(key) ? pt(cp, o.get(key)) : null;
        return p != null ? p : new double[]{dx, dz};
    }

    private static List<double[]> via(CityPlan cp, JsonObject o, String key) {
        List<double[]> out = new ArrayList<>();
        if (!o.has(key)) return out;
        for (JsonElement e : o.getAsJsonArray(key)) {
            double[] p = pt(cp, e);
            if (p != null) out.add(p);
        }
        return out;
    }

    public static double num(JsonObject o, String k, double d) {
        return o.has(k) ? o.get(k).getAsDouble() : d;
    }

    public static boolean bool(JsonObject o, String k, boolean d) {
        return o.has(k) ? o.get(k).getAsBoolean() : d;
    }

    public static String str(JsonObject o, String k, String d) {
        return o.has(k) ? o.get(k).getAsString() : d;
    }

    // ------------------------------------------------------------------ curves

    /** Catmull-Rom through {@code via}, sampled about every {@code step} blocks (closed curves repeat the first point). */
    public static List<double[]> smooth(List<double[]> via, boolean closed, double step) {
        List<double[]> out = new ArrayList<>();
        int n = via.size();
        if (n < 2) return new ArrayList<>(via);
        int segs = closed ? n : n - 1;
        for (int i = 0; i < segs; i++) {
            double[] p0 = via.get(closed ? Math.floorMod(i - 1, n) : Math.max(0, i - 1));
            double[] p1 = via.get(i);
            double[] p2 = via.get((i + 1) % n);
            double[] p3 = via.get(closed ? (i + 2) % n : Math.min(n - 1, i + 2));
            double len = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            int k = Math.max(1, (int) Math.ceil(len / step));
            for (int j = 0; j < k; j++) {
                double t = j / (double) k, t2 = t * t, t3 = t2 * t;
                out.add(new double[]{
                        0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3),
                        0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)});
            }
        }
        out.add(closed ? via.get(0).clone() : via.get(n - 1).clone());
        return out;
    }

    private static void dp(List<double[]> pts, int a, int b, double eps, boolean[] keep) {
        if (b <= a + 1) return;
        double[] pa = pts.get(a), pb = pts.get(b);
        double vx = pb[0] - pa[0], vz = pb[1] - pa[1], len = Math.hypot(vx, vz);
        int idx = -1;
        double far = 0;
        for (int i = a + 1; i < b; i++) {
            double[] q = pts.get(i);
            double d = len < 1e-6 ? Math.hypot(q[0] - pa[0], q[1] - pa[1]) : Math.abs((q[0] - pa[0]) * vz - (q[1] - pa[1]) * vx) / len;
            if (d > far) {
                far = d;
                idx = i;
            }
        }
        if (idx >= 0 && far > eps) {
            keep[idx] = true;
            dp(pts, a, idx, eps, keep);
            dp(pts, idx, b, eps, keep);
        }
    }

    /** Integer polyline of a curve: Douglas-Peucker, segments capped at {@code maxSeg} blocks, no repeated points. */
    static List<int[]> toInts(List<double[]> pts, double eps, double maxSeg) {
        List<int[]> out = new ArrayList<>();
        if (pts.isEmpty()) return out;
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        dp(pts, 0, pts.size() - 1, eps, keep);
        double[] prev = null;
        for (int i = 0; i < pts.size(); i++) {
            if (!keep[i]) continue;
            double[] p = pts.get(i);
            if (prev != null) {
                double l = Math.hypot(p[0] - prev[0], p[1] - prev[1]);
                int k = (int) Math.ceil(l / maxSeg);
                for (int j = 1; j < k; j++) add(out, prev[0] + (p[0] - prev[0]) * j / k, prev[1] + (p[1] - prev[1]) * j / k);
            }
            add(out, p[0], p[1]);
            prev = p;
        }
        return out;
    }

    private static void add(List<int[]> out, double x, double z) {
        int ix = (int) Math.round(x), iz = (int) Math.round(z);
        if (!out.isEmpty()) {
            int[] l = out.get(out.size() - 1);
            if (l[0] == ix && l[1] == iz) return;
        }
        out.add(new int[]{ix, iz});
    }

    private static List<double[]> asDoubles(List<int[]> pts) {
        List<double[]> out = new ArrayList<>();
        for (int[] p : pts) out.add(new double[]{p[0], p[1]});
        return out;
    }

    // ------------------------------------------------------------------ terrain

    /** Land height under a local point. */
    static int land(Plan plan, double lx, double lz) {
        int ix = (int) Math.round(lx), iz = (int) Math.round(lz);
        return plan.terrain.land(plan.wx(ix, iz), plan.wz(ix, iz));
    }

    static boolean wet(Plan plan, double lx, double lz) {
        int ix = (int) Math.round(lx), iz = (int) Math.round(lz);
        return plan.terrain.wet(plan.wx(ix, iz), plan.wz(ix, iz));
    }

    /** The local cardinal direction pointing from the centre towards (lx, lz). */
    static Direction outwardLocal(double lx, double lz) {
        if (Math.abs(lx) > Math.abs(lz)) return lx > 0 ? Direction.EAST : Direction.WEST;
        return lz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ------------------------------------------------------------------ emitting

    /** Adds a street chain (specs plus the registered chain); returns it, or null if too short. */
    static Plan.Chain street(Plan plan, List<double[]> curve, int width, int rank, boolean major, String tag, int raise, int flags) {
        List<int[]> ip = toInts(curve, 0.9, rank == 0 ? 16 : 13);
        if (ip.size() < 2) return null;
        for (int i = 0; i + 1 < ip.size(); i++) {
            int[] a = ip.get(i), b = ip.get(i + 1);
            Spec s = plan.street(a[0], a[1], b[0], b[1], width, major || rank == 0);
            s.flags |= flags;
            if (raise != 0) s.minFloor = raise;
        }
        Plan.Chain c = new Plan.Chain(asDoubles(ip), width, rank, tag);
        plan.chains.add(c);
        return c;
    }

    // ------------------------------------------------------------------ the dispatcher

    public static void apply(Plan plan, CityPlan cp) {
        int n = 0;
        for (JsonObject o : cp.nets) {
            String type = str(o, "type", "path").toLowerCase(Locale.ROOT);
            try {
                switch (type) {
                    case "path" -> path(plan, cp, o);
                    case "loop" -> loop(plan, cp, o);
                    case "radial" -> radial(plan, cp, o, n);
                    case "switchback" -> switchback(plan, cp, o);
                    case "contour" -> contour(plan, cp, o);
                    case "canal" -> canal(plan, cp, o);
                    case "wall" -> wall(plan, cp, o);
                    case "terraces" -> terraces(plan, cp, o);
                    default -> NirnCraft.LOGGER.error("City plan {}: unknown net type {}", cp.id, type);
                }
            } catch (RuntimeException ex) {
                NirnCraft.LOGGER.error("City plan {}: bad {} net", cp.id, type, ex);
            }
            n++;
        }
        connect(plan, cp);
    }

    private static int rank(JsonObject o, int dflt) {
        return o.has("rank") ? o.get("rank").getAsInt() : bool(o, "major", false) ? 0 : dflt;
    }

    private static void path(Plan plan, CityPlan cp, JsonObject o) {
        List<double[]> v = via(cp, o, "via");
        boolean closed = bool(o, "closed", false);
        List<double[]> c = bool(o, "smooth", true) ? smooth(v, closed, 4) : v;
        street(plan, c, (int) num(o, "width", 3), rank(o, 1), bool(o, "major", false), str(o, "tag", ""), (int) num(o, "raise", 0), 0);
    }

    private static List<double[]> blob(double cx, double cz, double rx, double rz, double wobble, int lobes, double phase, int samples) {
        List<double[]> v = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            double a = i * 2 * Math.PI / samples;
            double k = 1 + (wobble / Math.max(8, (rx + rz) / 2)) * Math.sin(lobes * a + phase);
            v.add(new double[]{cx + Math.sin(a) * rx * k, cz + Math.cos(a) * rz * k});
        }
        return v;
    }

    private static void loop(Plan plan, CityPlan cp, JsonObject o) {
        double[] at = pt(cp, o, "at", 0, 0);
        double rx = num(o, "rx", num(o, "r", 40)), rz = num(o, "rz", num(o, "r", rx));
        List<double[]> v = blob(at[0], at[1], rx, rz, num(o, "wobble", 4), (int) num(o, "lobes", 3), num(o, "phase", 0),
                Math.max(8, (int) ((rx + rz) / 5)));
        street(plan, smooth(v, true, 4), (int) num(o, "width", 3), rank(o, 1), bool(o, "major", false), str(o, "tag", ""),
                (int) num(o, "raise", 0), 0);
    }

    private static void radial(Plan plan, CityPlan cp, JsonObject o, int salt) {
        double[] at = pt(cp, o, "at", 0, 0);
        int spokes = (int) num(o, "spokes", 8);
        double r0 = num(o, "r0", 12), r1 = num(o, "r1", 80), wob = num(o, "wobble", 4), off = num(o, "offset", 0);
        int width = (int) num(o, "width", 3), rw = (int) num(o, "ring_width", width);
        java.util.Set<Integer> skip = new java.util.HashSet<>();
        if (o.has("skip")) for (JsonElement e : o.getAsJsonArray("skip")) skip.add(e.getAsInt());
        RandomSource r = RandomSource.create(plan.seed ^ 0x9AD1A1L ^ salt * 0x9E3779B97F4A7C15L);
        List<Double> rings = new ArrayList<>();
        if (o.has("rings")) for (JsonElement e : o.getAsJsonArray("rings")) rings.add(e.getAsDouble());
        for (int i = 0; i < spokes; i++) {
            if (skip.contains(i)) continue;
            double a = off + i * 2 * Math.PI / spokes;
            double phase = r.nextDouble() * 6.28;
            List<double[]> v = new ArrayList<>();
            int steps = Math.max(3, (int) ((r1 - r0) / 14));
            for (int k = 0; k <= steps; k++) {
                double rad = r0 + (r1 - r0) * k / steps;
                double aa = a + Math.sin(phase + k * 1.3) * wob / Math.max(20, rad);
                v.add(new double[]{at[0] + Math.sin(aa) * rad, at[1] + Math.cos(aa) * rad});
            }
            street(plan, smooth(v, false, 4), width, i % 2 == 0 ? 0 : 1, false, str(o, "tag", "spoke"), 0, 0);
        }
        double rwob = num(o, "ring_wobble", 3);
        for (int k = 0; k < rings.size(); k++) {
            double rad = rings.get(k);
            List<double[]> v = blob(at[0], at[1], rad, rad, rwob, 3 + k, r.nextDouble() * 6.28, Math.max(10, (int) (rad / 4)));
            street(plan, smooth(v, true, 4), rw, 1, false, str(o, "ring_tag", "ring"), 0, 0);
        }
    }

    private static void switchback(Plan plan, CityPlan cp, JsonObject o) {
        double[] a = pt(cp, o, "from", 0, 60), b = pt(cp, o, "to", 0, -40);
        int legs = Math.max(2, (int) num(o, "legs", 4));
        double amp = num(o, "amp", 18);
        double dx = b[0] - a[0], dz = b[1] - a[1], len = Math.hypot(dx, dz);
        if (len < 1) return;
        double ux = dx / len, uz = dz / len, nx = -uz, nz = ux;
        List<double[]> v = new ArrayList<>();
        v.add(new double[]{a[0], a[1]});
        for (int i = 0; i < legs; i++) {
            double t = (i + 0.5) / legs;
            double side = i % 2 == 0 ? 1 : -1;
            v.add(new double[]{a[0] + ux * len * t + nx * amp * side, a[1] + uz * len * t + nz * amp * side});
        }
        v.add(new double[]{b[0], b[1]});
        street(plan, smooth(v, false, 4), (int) num(o, "width", 3), rank(o, 1), bool(o, "major", false), str(o, "tag", ""), (int) num(o, "raise", 0), 0);
    }

    private static void contour(Plan plan, CityPlan cp, JsonObject o) {
        double[] from = pt(cp, o, "from", 0, 0);
        double len = num(o, "len", 100);
        boolean both = bool(o, "both", true);
        double dir0 = num(o, "dir", 0);
        List<double[]> all = new ArrayList<>();
        List<double[]> fwd = traceContour(plan, from, len * (both ? 0.5 : 1), 1, dir0, plan.radius * 1.05);
        if (both) {
            List<double[]> back = traceContour(plan, from, len * 0.5, -1, dir0, plan.radius * 1.05);
            java.util.Collections.reverse(back);
            all.addAll(back);
            if (!fwd.isEmpty()) all.addAll(fwd.subList(1, fwd.size()));
        } else {
            all.addAll(fwd);
        }
        if (all.size() < 3) return;
        street(plan, all, (int) num(o, "width", 3), rank(o, 1), bool(o, "major", false), str(o, "tag", ""), (int) num(o, "raise", 0), 0);
    }

    /** A streamline along the terrain's contour (constant height), steered back to its start height. */
    static List<double[]> traceContour(Plan plan, double[] from, double len, double sgn, double dir0, double maxR) {
        List<double[]> pts = new ArrayList<>();
        double px = from[0], pz = from[1];
        double target = land(plan, px, pz);
        double hx = Math.cos(dir0) * sgn, hz = Math.sin(dir0) * sgn;
        pts.add(new double[]{px, pz});
        double step = 3;
        for (double t = 0; t < len; t += step) {
            double gx = (land(plan, px + 3, pz) - land(plan, px - 3, pz)) / 6.0;
            double gz = (land(plan, px, pz + 3) - land(plan, px, pz - 3)) / 6.0;
            double g = Math.hypot(gx, gz);
            double dx = hx, dz = hz;
            if (g > 0.04) {
                double cx = -gz / g, cz = gx / g;
                if (cx * hx + cz * hz < 0) {
                    cx = -cx;
                    cz = -cz;
                }
                double err = target - land(plan, px, pz);
                double lean = Math.max(-0.6, Math.min(0.6, err * 0.15));
                dx = cx + (gx / g) * lean;
                dz = cz + (gz / g) * lean;
            }
            double n = Math.hypot(dx, dz);
            dx /= n;
            dz /= n;
            hx = hx * 0.5 + dx * 0.5;
            hz = hz * 0.5 + dz * 0.5;
            double hn = Math.hypot(hx, hz);
            hx /= hn;
            hz /= hn;
            px += hx * step;
            pz += hz * step;
            if (Math.hypot(px, pz) > maxR || wet(plan, px, pz)) break;
            pts.add(new double[]{px, pz});
        }
        return pts;
    }

    private static void canal(Plan plan, CityPlan cp, JsonObject o) {
        List<double[]> v = via(cp, o, "via");
        if (v.size() < 2) return;
        List<double[]> c = smooth(v, bool(o, "closed", false), 4);
        List<int[]> ip = toInts(c, 0.7, 12);
        int width = (int) num(o, "width", 7);
        for (int i = 0; i + 1 < ip.size(); i++) {
            int[] a = ip.get(i), b = ip.get(i + 1);
            plan.put(plan.line(Archetype.CANAL, a[0], a[1], b[0], b[1], width));
        }
        if (!o.has("bridges")) return;
        double total = 0;
        double[] cum = new double[ip.size()];
        for (int i = 1; i < ip.size(); i++) {
            total += Math.hypot(ip.get(i)[0] - ip.get(i - 1)[0], ip.get(i)[1] - ip.get(i - 1)[1]);
            cum[i] = total;
        }
        int bw = (int) num(o, "bridge_width", 3);
        for (JsonElement e : o.getAsJsonArray("bridges")) {
            double s = e.getAsDouble() * total;
            int i = 0;
            while (i + 2 < ip.size() && cum[i + 1] < s) i++;
            int[] a = ip.get(i), b = ip.get(i + 1);
            double seg = Math.max(1e-6, cum[i + 1] - cum[i]), t = Math.max(0, Math.min(1, (s - cum[i]) / seg));
            double cx = a[0] + (b[0] - a[0]) * t, cz = a[1] + (b[1] - a[1]) * t;
            double ux = (b[0] - a[0]) / seg, uz = (b[1] - a[1]) / seg;
            double half = width / 2.0 + 4;
            int x1 = (int) Math.round(cx - uz * half), z1 = (int) Math.round(cz + ux * half);
            int x2 = (int) Math.round(cx + uz * half), z2 = (int) Math.round(cz - ux * half);
            Spec br = plan.line(Archetype.BRIDGE, x1, z1, x2, z2, bw);
            br.flags |= Spec.FIXED;
            br.floor = plan.base + 1;
            br.floor2 = plan.base + 1;
            plan.put(br);
            List<double[]> link = new ArrayList<>();
            link.add(new double[]{x1, z1});
            link.add(new double[]{x2, z2});
            plan.chains.add(new Plan.Chain(link, bw, 1, "bridge"));
        }
    }

    private static void wall(Plan plan, CityPlan cp, JsonObject o) {
        List<double[]> v = via(cp, o, "via");
        if (v.size() < 3) return;
        boolean closed = bool(o, "closed", true);
        int h = (int) num(o, "h", 9);
        int flags = 0;
        if (o.has("flags")) for (JsonElement e : o.getAsJsonArray("flags")) flags |= CityPlan.flagOf(e.getAsString());
        List<int[]> ip = toInts(smooth(v, closed, 6), 1.0, 14);
        List<int[]> gates = new ArrayList<>();
        if (o.has("gates")) for (JsonElement e : o.getAsJsonArray("gates")) {
            double[] g = pt(cp, e);
            if (g != null) gates.add(new int[]{(int) Math.round(g[0]), (int) Math.round(g[1])});
        }
        int gateHalf = 6;
        for (int i = 0; i + 1 < ip.size(); i++) {
            int[] a = ip.get(i), b = ip.get(i + 1);
            double mx = (a[0] + b[0]) / 2.0, mz = (a[1] + b[1]) / 2.0, half = Math.hypot(a[0] - b[0], a[1] - b[1]) / 2.0;
            boolean cut = false;
            for (int[] g : gates) if (Math.hypot(mx - g[0], mz - g[1]) < gateHalf + half) cut = true;
            if (cut) continue;
            Spec w = plan.line(Archetype.WALL, a[0], a[1], b[0], b[1], 3);
            w.h = h;
            w.facing = plan.dir(outwardLocal(mx, mz));
            w.flags |= flags;
            plan.add(w);
        }
        if (bool(o, "towers", true) && (flags & Spec.PALISADE) == 0) {
            for (int i = 0; i < ip.size() - (closed ? 1 : 0); i += 2) {
                int[] p = ip.get(i);
                boolean nearGate = false;
                for (int[] g : gates) if (Math.hypot(p[0] - g[0], p[1] - g[1]) < gateHalf + 8) nearGate = true;
                if (nearGate) continue;
                // the tower's door opens into the town (P5: a door onto the field outside the wall has no way in)
                Spec t = plan.at(Archetype.TOWER, p[0], p[1], plan.dir(outwardLocal(p[0], p[1])).getOpposite()).size(7, 7);
                t.h = h + 14;
                t.margin = 2;
                plan.add(t);
            }
        }
        for (int[] g : gates) {
            Direction out = plan.dir(outwardLocal(g[0], g[1]));
            Spec gate = plan.at(Archetype.GATE, g[0], g[1], Direction.SOUTH).size(11, 7);
            gate.facing = out;
            gate.h = h + 6;
            gate.margin = 2;
            gate.flags |= flags & Spec.PALISADE;
            plan.add(gate);
        }
    }

    private static void terraces(Plan plan, CityPlan cp, JsonObject o) {
        if (!o.has("rect")) return;
        JsonArray r = o.getAsJsonArray("rect");
        int x0 = r.get(0).getAsInt(), z0 = r.get(1).getAsInt(), x1 = r.get(2).getAsInt(), z1 = r.get(3).getAsInt();
        int bands = Math.max(2, (int) num(o, "bands", 3));
        String axis = str(o, "axis", "auto");
        // slope direction from the heightfield: stack the shelves along the axis the ground falls most over
        double hxa = 0, hxb = 0, hza = 0, hzb = 0;
        for (int i = 0; i <= 4; i++) {
            double f = i / 4.0;
            double ax = x0 + (x1 - x0) * f, az = z0 + (z1 - z0) * f;
            hxa += land(plan, x0, az);
            hxb += land(plan, x1, az);
            hza += land(plan, ax, z0);
            hzb += land(plan, ax, z1);
        }
        boolean alongZ = axis.equals("z") || axis.equals("auto") && Math.abs(hza - hzb) >= Math.abs(hxa - hxb);
        int parapet = (int) num(o, "parapet", 3);
        int width = (int) num(o, "width", 3);
        List<double[]> centres = new ArrayList<>();
        RandomSource rnd = RandomSource.create(plan.seed ^ 0x7E44ACEL);
        for (int b = 0; b < bands; b++) {
            int a0 = alongZ ? z0 + (z1 - z0) * b / bands : x0 + (x1 - x0) * b / bands;
            int a1 = alongZ ? z0 + (z1 - z0) * (b + 1) / bands : x0 + (x1 - x0) * (b + 1) / bands;
            int[] rc = alongZ ? new int[]{x0, a0, x1, a1} : new int[]{a0, z0, a1, z1};
            double sum = 0;
            int cnt = 0;
            for (int i = 0; i <= 4; i++) {
                for (int j = 0; j <= 2; j++) {
                    sum += land(plan, rc[0] + (rc[2] - rc[0]) * i / 4.0, rc[1] + (rc[3] - rc[1]) * (0.2 + 0.3 * j));
                    cnt++;
                }
            }
            int lvl = Math.max(0, (int) Math.round(sum / cnt) - plan.base);
            Cities.terraces(plan, rc[0], rc[1], rc[2], rc[3], lvl, parapet);
            double cx = (rc[0] + rc[2]) / 2.0, cz = (rc[1] + rc[3]) / 2.0;
            centres.add(new double[]{cx, cz});
            if (bool(o, "street", true)) {
                List<double[]> v = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    double f = i / 4.0, wob = (rnd.nextDouble() - 0.5) * 6;
                    double lx = alongZ ? rc[0] + 6 + (rc[2] - rc[0] - 12) * f : cx + wob;
                    double lz = alongZ ? cz + wob : rc[1] + 6 + (rc[3] - rc[1] - 12) * f;
                    v.add(new double[]{lx, lz});
                }
                street(plan, smooth(v, false, 4), width, 1, false, str(o, "tag", "shelf"), lvl, 0);
            }
        }
        int stairs = (int) num(o, "stairs", 2);
        for (int b = 0; b + 1 < bands; b++) {
            for (int s = 0; s < stairs; s++) {
                double f = (s + 1.0) / (stairs + 1);
                double[] c0 = centres.get(b), c1 = centres.get(b + 1);
                double lx = alongZ ? x0 + (x1 - x0) * f : c0[0], lz = alongZ ? c0[1] : z0 + (z1 - z0) * f;
                double mx = alongZ ? lx : c1[0], mz = alongZ ? c1[1] : lz;
                List<double[]> v = new ArrayList<>();
                v.add(new double[]{lx, lz});
                v.add(new double[]{(lx + mx) / 2 + (alongZ ? 2 : 0), (lz + mz) / 2 + (alongZ ? 0 : 2)});
                v.add(new double[]{mx, mz});
                street(plan, v, width, 2, false, "stairs", 0, 0);
            }
        }
    }

    // ------------------------------------------------------------------ connectivity

    private static double[] nearest(Plan.Chain c, double px, double pz) {
        double best = 1e18, bx = 0, bz = 0;
        for (int i = 0; i + 1 < c.pts().size(); i++) {
            double[] a = c.pts().get(i), b = c.pts().get(i + 1);
            double vx = b[0] - a[0], vz = b[1] - a[1], l2 = vx * vx + vz * vz;
            double t = l2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * vx + (pz - a[1]) * vz) / l2));
            double qx = a[0] + vx * t, qz = a[1] + vz * t, d = Math.hypot(qx - px, qz - pz);
            if (d < best) {
                best = d;
                bx = qx;
                bz = qz;
            }
        }
        return new double[]{best, bx, bz};
    }

    private static boolean touches(Plan.Chain a, Plan.Chain b) {
        double reach = (a.width() + b.width()) / 2.0 + 2.5;
        for (double[] p : a.pts()) if (nearest(b, p[0], p[1])[0] <= reach) return true;
        for (double[] p : b.pts()) if (nearest(a, p[0], p[1])[0] <= reach) return true;
        return false;
    }

    /**
     * Joins every street chain to the network the main gate feeds: a chain that touches nothing in the main component
     * gets a link street to the nearest point of it, and each gate is tied to the nearest chain, so no street is cut
     * off and every gate leads to the centre.
     */
    static void connect(Plan plan, CityPlan cp) {
        List<Plan.Chain> chains = plan.chains;
        if (chains.isEmpty()) return;
        int start = 0;
        if (cp.gates != null && cp.gates.length > 0) {
            double bd = 1e18;
            for (int i = 0; i < chains.size(); i++) {
                double d = nearest(chains.get(i), cp.gates[0][0], cp.gates[0][1])[0];
                if (d < bd) {
                    bd = d;
                    start = i;
                }
            }
            if (bd > 3) linkPoint(plan, chains.get(start), cp.gates[0][0], cp.gates[0][1]);
        } else {
            double best = -1;
            for (int i = 0; i < chains.size(); i++) {
                if (chains.get(i).rank() == 0 && chains.get(i).length() > best) {
                    best = chains.get(i).length();
                    start = i;
                }
            }
        }
        int n0 = chains.size();
        boolean[] in = new boolean[n0];
        in[start] = true;
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < n0; i++) {
                if (in[i]) continue;
                for (int j = 0; j < n0; j++) {
                    if (in[j] && touches(chains.get(i), chains.get(j))) {
                        in[i] = true;
                        grew = true;
                        break;
                    }
                }
            }
        }
        int links = 0;
        for (int i = 0; i < n0; i++) {
            if (in[i] || links > 24) continue;
            Plan.Chain c = chains.get(i);
            double bestD = 1e18;
            double[] from = null, to = null;
            for (double[] p : c.pts()) {
                for (int j = 0; j < n0; j++) {
                    if (!in[j]) continue;
                    double[] n = nearest(chains.get(j), p[0], p[1]);
                    if (n[0] < bestD) {
                        bestD = n[0];
                        from = p;
                        to = new double[]{n[1], n[2]};
                    }
                }
            }
            if (from == null || bestD > 60) continue;
            List<double[]> v = new ArrayList<>();
            v.add(from);
            v.add(new double[]{(from[0] + to[0]) / 2, (from[1] + to[1]) / 2});
            v.add(to);
            if (street(plan, v, 3, 2, false, "link", 0, 0) != null) links++;
            in[i] = true;
        }
        if (cp.gates != null) {
            for (int gi = 1; gi < cp.gates.length; gi++) {
                Plan.Chain best = null;
                double bb = 1e18;
                for (int j = 0; j < n0; j++) {
                    double d = nearest(chains.get(j), cp.gates[gi][0], cp.gates[gi][1])[0];
                    if (d < bb) {
                        bb = d;
                        best = chains.get(j);
                    }
                }
                if (best != null && bb > 3) linkPoint(plan, best, cp.gates[gi][0], cp.gates[gi][1]);
            }
        }
    }

    private static void linkPoint(Plan plan, Plan.Chain c, double px, double pz) {
        double[] n = nearest(c, px, pz);
        if (n[0] < 3) return;
        List<double[]> v = new ArrayList<>();
        v.add(new double[]{px, pz});
        v.add(new double[]{(px + n[1]) / 2, (pz + n[2]) / 2});
        v.add(new double[]{n[1], n[2]});
        street(plan, v, 5, 0, true, "gate", 0, 0);
    }
}
