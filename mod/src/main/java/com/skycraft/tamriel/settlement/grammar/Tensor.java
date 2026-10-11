package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.settlement.grammar.Zoning.Form;
import com.skycraft.tamriel.settlement.grammar.Zoning.Street;
import com.skycraft.tamriel.settlement.plan.Plan;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Organic lanes from a street tensor field (after Chen et al. 2008, "Interactive procedural street modeling"). At every
 * point the field blends a few basis tensors: the settlement's main axis (planned towns), a radial field round the
 * square (cluster and hilltop towns), the terrain's contours (from the noise terrain the planner already samples) and
 * the shore. Lanes are streamlines: traced from seeds along the main streets in the minor direction (branching off
 * and climbing), then alleys along the major direction (along the contours, parallel to the shore). Streamlines stop
 * at the edge of town, at water, and where they run into another street (a T junction), so lanes end in junctions
 * instead of crossing. Everything is seeded: the same settlement always gets the same lanes.
 */
final class Tensor {
    private Tensor() {}

    static void lanes(Zoning.Ctx x, int attempt) {
        Form f = x.sk.form;
        if (f == Form.GRID || f == Form.RING || f == Form.CANAL) return;
        RandomSource r = RandomSource.create(x.plan.seed ^ 0x7E450L ^ (attempt + 1) * 0x632BE59BD9B4E019L);
        double axis = mainAxis(x);
        List<Street> seeds = new ArrayList<>();
        for (Street s : x.sk.streets) if (s.rank() <= 1) seeds.add(s);
        int width = x.tier >= 1 ? 3 : 2;
        double maxLen = x.R * (x.tier >= 2 ? 0.75 : 0.6);
        List<Street> lanes = new ArrayList<>();
        for (Street s : seeds) {
            double len = s.len();
            if (len < 14) continue;
            double ux = (s.x2() - s.x1()) / len, uz = (s.z2() - s.z1()) / len;
            int spacing = x.tier >= 2 ? 15 : 18;
            for (double t = 7 + r.nextInt(6); t < len - 6; t += spacing + r.nextInt(5)) {
                for (int side = -1; side <= 1; side += 2) {
                    if (r.nextInt(7) == 0) continue;
                    double nx = -uz * side, nz = ux * side;
                    double px = s.x1() + ux * t + nx * (s.width() / 2.0 + 1), pz = s.z1() + uz * t + nz * (s.width() / 2.0 + 1);
                    List<double[]> pts = trace(x, axis, px, pz, nx, nz, false, maxLen * (0.6 + r.nextDouble() * 0.4));
                    add(x, pts, width, 1, lanes);
                }
            }
        }
        // alleys along the contours / the shore, between the lanes (towns and cities)
        if (x.tier >= 2) {
            List<Street> from = new ArrayList<>(lanes);
            for (Street s : from) {
                double len = s.len();
                if (len < 16) continue;
                double ux = (s.x2() - s.x1()) / len, uz = (s.z2() - s.z1()) / len;
                double t = len * (0.4 + r.nextDouble() * 0.3);
                for (int side = -1; side <= 1; side += 2) {
                    if (r.nextBoolean()) continue;
                    double nx = -uz * side, nz = ux * side;
                    double px = s.x1() + ux * t + nx * 2.5, pz = s.z1() + uz * t + nz * 2.5;
                    List<double[]> pts = trace(x, axis, px, pz, nx, nz, true, x.R * 0.4);
                    add(x, pts, 2, 2, lanes);
                }
            }
        }
    }

    /** Direction (radians, x = cos, z = sin) of the longest main street. */
    static double mainAxis(Zoning.Ctx x) {
        double best = -1, a = Math.PI / 2;
        for (Street s : x.sk.streets) {
            if (s.rank() != 0 || s.len() <= best) continue;
            best = s.len();
            a = Math.atan2(s.z2() - s.z1(), s.x2() - s.x1());
        }
        return a;
    }

    /** The major direction of the field at a local point (radians). */
    static double major(Zoning.Ctx x, double axis, double lx, double lz) {
        double c2 = 0, s2 = 0;
        // planned axis
        double wa = x.sk.form == Form.CLUSTER ? 0.2 : 0.7;
        c2 += wa * Math.cos(2 * axis);
        s2 += wa * Math.sin(2 * axis);
        // radial round the square
        int[] sq = x.sk.squares.isEmpty() ? new int[]{0, 0} : x.sk.squares.get(0);
        double dx = lx - sq[0], dz = lz - sq[1];
        if (dx * dx + dz * dz > 4) {
            double wr = x.sk.form == Form.CLUSTER || x.sk.form == Form.HILLTOP ? 0.9 : 0.25;
            double b = Math.atan2(dz, dx);
            c2 += wr * Math.cos(2 * b);
            s2 += wr * Math.sin(2 * b);
        }
        // contours of the terrain
        Plan p = x.plan;
        double hx = (land(p, lx + 3, lz) - land(p, lx - 3, lz)) / 6.0, hz = (land(p, lx, lz + 3) - land(p, lx, lz - 3)) / 6.0;
        double g = Math.hypot(hx, hz);
        if (g > 0.05) {
            double wc = Math.min(1.6, g * 1.4);
            double cang = Math.atan2(hx, -hz);
            c2 += wc * Math.cos(2 * cang);
            s2 += wc * Math.sin(2 * cang);
        }
        // the shore
        if (x.sk.water != null && x.sk.waterDist < x.R + 12) {
            int[] wv = Zoning.rot(-1, 0, Zoning.quarter(x.sk.water));
            double sa = Math.atan2(wv[0], -wv[1]);
            c2 += 0.8 * Math.cos(2 * sa);
            s2 += 0.8 * Math.sin(2 * sa);
        }
        return 0.5 * Math.atan2(s2, c2);
    }

    static int land(Plan p, double lx, double lz) {
        int ix = (int) Math.round(lx), iz = (int) Math.round(lz);
        return p.terrain.land(p.wx(ix, iz), p.wz(ix, iz));
    }

    /** Traces a streamline from (px, pz), starting along (dx, dz); {@code alongMajor} picks the field family. */
    static List<double[]> trace(Zoning.Ctx x, double axis, double px, double pz, double dx, double dz, boolean alongMajor, double maxLen) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{px, pz});
        double travelled = 0;
        double step = 2;
        while (travelled < maxLen) {
            double th = major(x, axis, px, pz);
            double fx = Math.cos(th), fz = Math.sin(th);
            if (!alongMajor) {
                double t = fx;
                fx = -fz;
                fz = t;
            }
            if (fx * dx + fz * dz < 0) {
                fx = -fx;
                fz = -fz;
            }
            // smooth turns: streets bend, they don't zigzag
            double nx = dx * 0.55 + fx * 0.45, nz = dz * 0.55 + fz * 0.45;
            double n = Math.hypot(nx, nz);
            if (n < 1e-6) break;
            dx = nx / n;
            dz = nz / n;
            px += dx * step;
            pz += dz * step;
            travelled += step;
            if (Math.hypot(px, pz) > x.R * 0.93) break;
            Plan p = x.plan;
            int ix = (int) Math.round(px), iz = (int) Math.round(pz);
            if (p.terrain.wet(p.wx(ix, iz), p.wz(ix, iz))) break;
            if (Math.abs(land(p, px, pz) - land(p, pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1])) > 3) break;
            if (travelled > 7 && nearStreet(x, px, pz, 5)) {
                pts.add(new double[]{px, pz});
                break;
            }
            pts.add(new double[]{px, pz});
        }
        return pts;
    }

    static boolean nearStreet(Zoning.Ctx x, double px, double pz, double gap) {
        for (Street s : x.sk.streets) {
            double ax = s.x1(), az = s.z1(), bx = s.x2(), bz = s.z2();
            double vx = bx - ax, vz = bz - az;
            double l2 = vx * vx + vz * vz;
            double t = l2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / l2));
            double qx = ax + vx * t - px, qz = az + vz * t - pz;
            if (Math.hypot(qx, qz) < gap + s.width() / 2.0) return true;
        }
        for (int[] sq : x.sk.squares) {
            if (Math.abs(px - sq[0]) < sq[2] / 2.0 + gap && Math.abs(pz - sq[1]) < sq[2] / 2.0 + gap) return true;
        }
        return false;
    }

    /** Simplifies a streamline into a few straight streets and adds them to the skeleton. */
    static void add(Zoning.Ctx x, List<double[]> pts, int width, int rank, List<Street> out) {
        if (pts.size() < 6) return;
        List<double[]> simple = new ArrayList<>();
        simplify(pts, 0, pts.size() - 1, 1.3, simple);
        simple.add(pts.get(pts.size() - 1));
        for (int i = 0; i + 1 < simple.size(); i++) {
            double[] a = simple.get(i), b = simple.get(i + 1);
            Street s = new Street((int) Math.round(a[0]), (int) Math.round(a[1]), (int) Math.round(b[0]), (int) Math.round(b[1]), width, rank);
            if (s.len() < 2) continue;
            x.sk.streets.add(s);
            out.add(s);
        }
    }

    /** Douglas-Peucker: keeps the points that matter, start inclusive, end exclusive. */
    static void simplify(List<double[]> pts, int a, int b, double eps, List<double[]> out) {
        double[] pa = pts.get(a), pb = pts.get(b);
        double vx = pb[0] - pa[0], vz = pb[1] - pa[1];
        double len = Math.hypot(vx, vz);
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
            simplify(pts, a, idx, eps, out);
            simplify(pts, idx, b, eps, out);
        } else {
            out.add(pa);
        }
    }
}
