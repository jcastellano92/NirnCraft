package com.skycraft.tamriel.settlement.grammar;

import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.settlement.grammar.Zoning.Street;
import com.skycraft.tamriel.settlement.grammar.Zoning.Zone;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.CityPlan;
import com.skycraft.tamriel.settlement.plan.CityPlans;
import com.skycraft.tamriel.settlement.plan.Layouts;
import com.skycraft.tamriel.settlement.plan.Nets;
import com.skycraft.tamriel.settlement.plan.Plan;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The built fabric of a city plan: the pass that turns authored streets (straight, curved, radial, terraced) into a
 * continuous town. It walks every street chain placing cardinal-facing homes and trades by wealth zone (so a curved
 * street gets a stair-stepped but regular frontage), puts the queued named buildings on their streets, packs the
 * blocks behind with back lots joined by footpaths, then runs a gap filler over the empty ground until the density
 * target is met: houses first, then gardens, yards, pens, trees and wells; and finally the farms, fields and orchards
 * of the outskirts, the town's civic {@link Features} and the street furniture of {@link Zoning#lining}. It reports the
 * built-area ratio. Everything is seeded, so a place always plans the same.
 *
 * <p>Plan data ({@code "fabric"}): {@code radius} the built radius (default 0.8 of the plan radius), {@code target}
 * the buildings/developed ratio to reach, {@code tier} (0..3) to override the size class, {@code gap} false to skip
 * the gap filler, {@code outskirts} false to skip farms and fields.</p>
 */
public final class Fabric {
    private Fabric() {}

    /** Called by {@link Zoning#place} after a plan's pins and civic lots, before the building grammar adopts the rest. */
    public static void apply(Plan plan, Place p, Layouts.Size size) {
        CityPlan cp = CityPlans.of(p);
        if (cp == null || cp.fabric == null || plan.zoned || "off".equals(System.getProperty("skycraft.fabric"))) return;
        JsonObject f = cp.fabric;
        long t0 = System.nanoTime();
        int firstNew = plan.specs.size();
        Zoning.Ctx x = new Zoning.Ctx();
        x.plan = plan;
        x.size = size;
        x.tier = f.has("tier") ? f.get("tier").getAsInt() : Zoning.tier(size);
        x.named = true;
        x.style = Styles.of(plan.culture, plan.style);
        x.r = RandomSource.create(plan.seed ^ 0xFAB21CL);
        x.sk = new Zoning.Skeleton();
        x.R = f.has("radius") ? f.get("radius").getAsInt() : (int) (plan.radius * 0.8);
        x.sk.radius = x.R;
        x.sk.form = Zoning.Form.CLUSTER;
        if (cp.gates != null) for (int[] g : cp.gates) x.sk.gates.add(new int[]{g[0], g[1]});
        if (x.sk.gates.isEmpty()) x.sk.gates.add(new int[]{0, x.R});
        for (Spec s : plan.specs) {
            if (s.kind == Archetype.STREET) {
                int ax = Zoning.localX(plan, s.x, s.z), az = Zoning.localZ(plan, s.x, s.z), bx = Zoning.localX(plan, s.x2, s.z2), bz = Zoning.localZ(plan, s.x2, s.z2);
                x.sk.streets.add(new Street(ax, az, bx, bz, s.w, s.has(Spec.MAJOR) ? 0 : s.w >= 3 ? 1 : 2));
            } else if (s.kind == Archetype.PLAZA) {
                x.sk.squares.add(new int[]{Zoning.localX(plan, s.x, s.z), Zoning.localZ(plan, s.x, s.z), s.w});
            }
        }
        // quarters: the plan's zone discs (noble / craft / poor / centre), else the default thirds
        x.noble = new int[]{0, -(int) (x.R * 0.6)};
        x.craft = new int[]{(int) (x.R * 0.5), (int) (x.R * 0.3)};
        x.poor = new int[]{-(int) (x.R * 0.5), (int) (x.R * 0.6)};
        List<double[]> disks = new ArrayList<>();
        List<Zone> diskZone = new ArrayList<>();
        for (JsonObject z : cp.zones) {
            double[] at = Nets.pt(cp, z, "at", 0, 0);
            Zone zone;
            try {
                zone = Zone.valueOf(Nets.str(z, "zone", "common").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            disks.add(new double[]{at[0], at[1], Nets.num(z, "r", 20)});
            diskZone.add(zone);
            if (zone == Zone.NOBLE) x.noble = new int[]{(int) at[0], (int) at[1]};
            else if (zone == Zone.CRAFT) x.craft = new int[]{(int) at[0], (int) at[1]};
            else if (zone == Zone.POOR) x.poor = new int[]{(int) at[0], (int) at[1]};
        }
        Zoner zoner = new Zoner(x, disks, diskZone);

        // ---- named buildings on the net streets
        queued(x, cp);
        // ---- the civic features claim their ground before the houses pack in
        int placed;
        String set;
        if (!cp.features.isEmpty()) {
            placed = Features.apply(x, cp);
            set = "authored";
        } else {
            List<String> done = Features.auto(x);
            placed = done.size();
            set = String.join(",", done);
        }
        // ---- homes and trades along every street chain, main streets first
        List<Plan.Chain> chains = new ArrayList<>(plan.chains);
        chains.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        for (Plan.Chain ch : chains) {
            if (ch.tag().equals("bridge")) continue;
            walk(x, zoner, ch, 1);
            walk(x, zoner, ch, -1);
        }
        // the straight authored streets too (cp.streets / rings that the queue fill did not cover)
        for (Street st : new ArrayList<>(x.sk.streets)) {
            if (st.len() < 10 || coveredByChain(plan, st)) continue;
            frontageStreet(x, zoner, st, 1);
            frontageStreet(x, zoner, st, -1);
        }
        Zoning.squareFrontage(x);
        Zoning.infill(x);
        boolean gap = !f.has("gap") || f.get("gap").getAsBoolean();
        double target = f.has("target") ? f.get("target").getAsDouble() : Metrics.target(size) + 0.2;
        if (gap) gaps(x, zoner, target, 3);
        Zoning.backyards(x);
        if (!f.has("outskirts") || f.get("outskirts").getAsBoolean()) Zoning.outskirts(x, "");
        seatOnTerraces(plan, firstNew);
        double[] m = measure(plan, x.R);
        plan.fabricReport = String.format(Locale.ROOT, "fabric %s: buildings %.0f%%, developed %.0f%% of r%d, %d features (%s), %d specs, %d ms",
                cp.id, m[0] * 100, m[1] * 100, x.R, placed, set, plan.specs.size(), (System.nanoTime() - t0) / 1_000_000);
        Skycraft.LOGGER.info(plan.fabricReport);
    }

    /**
     * A seeded set of civic features for a settlement the zoning planner laid out (hamlets and up): trees, wells,
     * fountains, shrines, memorials, markets, pools, gardens... by culture, no two the same set.
     */
    public static void decorate(Plan plan, Layouts.Size size, int R) {
        if ("off".equals(System.getProperty("skycraft.fabric"))) return;
        int tier = Zoning.tier(size);
        if (size == Layouts.Size.FARM || size == Layouts.Size.INN || size == Layouts.Size.HOMESTEAD || size == Layouts.Size.SITE) return;
        Zoning.Ctx x = new Zoning.Ctx();
        x.plan = plan;
        x.size = size;
        x.tier = tier;
        x.named = !plan.key.startsWith("gen:");
        x.style = Styles.of(plan.culture, plan.style);
        x.r = RandomSource.create(plan.seed ^ 0xDEC02L);
        x.sk = new Zoning.Skeleton();
        x.R = R;
        x.sk.radius = R;
        for (Spec s : plan.specs) {
            if (s.kind == Archetype.PLAZA) x.sk.squares.add(new int[]{Zoning.localX(plan, s.x, s.z), Zoning.localZ(plan, s.x, s.z), s.w});
            else if (s.kind == Archetype.GATE) x.sk.gates.add(new int[]{Zoning.localX(plan, s.x, s.z), Zoning.localZ(plan, s.x, s.z)});
        }
        int[] sq = x.sk.squares.isEmpty() ? null : x.sk.squares.get(0);
        if (sq == null) x.sk.squares.add(new int[]{0, 0, 9});
        Features.auto(x);
    }

    /**
     * Pieces the fabric pass added that stand on a terrace tile get that terrace's level as their minimum floor (the
     * planner's terrain model does not know the tile, so without it they would be buried in the fill).
     */
    private static void seatOnTerraces(Plan plan, int from) {
        List<Spec> tiles = new ArrayList<>();
        for (Spec s : plan.specs) if (s.has(Spec.GROUND) && s.minFloor != Spec.UNSET && s.kind != Archetype.CAMP) tiles.add(s);
        if (tiles.isEmpty()) return;
        for (int i = from; i < plan.specs.size(); i++) {
            Spec s = plan.specs.get(i);
            if (s.isLine() || s.kind == Archetype.TERRACE || s.has(Spec.GROUND) || s.minFloor != Spec.UNSET) continue;
            int best = Spec.UNSET;
            for (Spec t : tiles) {
                if (Math.abs(s.x - t.x) <= t.halfX() + 1 && Math.abs(s.z - t.z) <= t.halfZ() + 1) best = Math.max(best, t.minFloor);
            }
            if (best != Spec.UNSET) s.minFloor = best;
        }
    }

    /** Zone lookup that honours the plan's disc zones before the default quarters. */
    private static final class Zoner {
        final Zoning.Ctx x;
        final List<double[]> disks;
        final List<Zone> zones;

        Zoner(Zoning.Ctx x, List<double[]> disks, List<Zone> zones) {
            this.x = x;
            this.disks = disks;
            this.zones = zones;
        }

        Zone at(double lx, double lz) {
            for (int i = 0; i < disks.size(); i++) {
                double[] d = disks.get(i);
                if (Math.hypot(lx - d[0], lz - d[1]) < d[2]) return zones.get(i);
            }
            return Zoning.zoneAt(x, lx, lz);
        }
    }

    private static boolean coveredByChain(Plan plan, Street st) {
        double mx = (st.x1() + st.x2()) / 2.0, mz = (st.z1() + st.z2()) / 2.0;
        for (Plan.Chain c : plan.chains) {
            for (int i = 0; i + 1 < c.pts().size(); i++) {
                double[] a = c.pts().get(i), b = c.pts().get(i + 1);
                if (Math.abs(a[0] - st.x1()) < 1.5 && Math.abs(a[1] - st.z1()) < 1.5 && Math.abs(b[0] - st.x2()) < 1.5 && Math.abs(b[1] - st.z2()) < 1.5) return true;
                if (Math.abs(b[0] - st.x1()) < 1.5 && Math.abs(b[1] - st.z1()) < 1.5 && Math.abs(a[0] - st.x2()) < 1.5 && Math.abs(a[1] - st.z2()) < 1.5) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ queued named buildings

    /** Queue entries whose {@code on} tag names a net street: placed (as the legacy archetype, graded by adopt) along it. */
    private static void queued(Zoning.Ctx x, CityPlan cp) {
        Plan plan = x.plan;
        for (CityPlan.Queued q : cp.queue) {
            List<Plan.Chain> tagged = new ArrayList<>();
            for (Plan.Chain c : plan.chains) if (!q.on().isEmpty() && q.on().equals(c.tag()) && c.length() > 14) tagged.add(c);
            if (tagged.isEmpty()) continue;
            // fall back to every main/secondary street when the tagged ones have no room
            List<Plan.Chain> order = new ArrayList<>(tagged);
            for (Plan.Chain c : plan.chains) if (!tagged.contains(c) && c.rank() <= 1 && c.length() > 14) order.add(c);
            for (int i = 0; i < q.count(); i++) {
                int w = q.w() > 0 ? q.w() : q.kind().w, d = q.d() > 0 ? q.d() : q.kind().d;
                if (q.smallEvery() > 0 && q.w() == 0 && i % q.smallEvery() == 1) {
                    w = 9;
                    d = 9;
                }
                boolean placed = false;
                int first = x.r.nextInt(tagged.size());
                for (int ci = 0; ci < order.size() && !placed; ci++) {
                    Plan.Chain ch = order.get(ci < tagged.size() ? (first + ci) % tagged.size() : ci);
                    double len = ch.length();
                    double start = x.r.nextDouble() * len;
                    for (int k = 0; k < (int) (len / 2) && !placed; k++) {
                        double t = (start + k * 2.0) % len;
                        for (int sd = 0; sd < 2 && !placed; sd++) {
                            int side = sd == 0 ? 1 : -1;
                            double[] p0 = ch.at(t), p1 = ch.at(Math.min(len, t + 6));
                            double dx = p1[0] - p0[0], dz = p1[1] - p0[1], l = Math.hypot(dx, dz);
                            if (l < 1e-3) continue;
                            double ux = dx / l, uz = dz / l, nx = -uz * side, nz = ux * side;
                            Direction face = Zoning.cardinal(-nx, -nz);
                            boolean zFace = face.getAxis() == Direction.Axis.Z;
                            int ex = zFace ? w : d, ez = zFace ? d : w;
                            double off = ch.width() / 2.0 + 2 + (Math.abs(nx) * ex + Math.abs(nz) * ez) / 2.0;
                            double along = (Math.abs(ux) * ex + Math.abs(uz) * ez) / 2.0;
                            int lx = (int) Math.round(p0[0] + ux * along + nx * off), lz = (int) Math.round(p0[1] + uz * along + nz * off);
                            Spec s = plan.at(q.kind(), lx, lz, face).size(w, d);
                            s.flags = q.flags();
                            s.variant = q.variant();
                            s.minFloor = q.raise() == 0 ? Spec.UNSET : q.raise();
                            s.margin = Math.max(1, s.margin);
                            if (!q.name().isEmpty() && i == 0) {
                                s.name = q.name();
                                s.flag(Spec.SIGN);
                            }
                            if (plan.place(s, 1) != null) placed = true;
                        }
                    }
                }
                if (!placed) {
                    // named buildings must exist: relax the slope limit and try the middle of the first street
                    plan.relaxed = true;
                    Plan.Chain ch = order.get(0);
                    double[] p0 = ch.at(ch.length() / 2);
                    Spec s = plan.at(q.kind(), (int) Math.round(p0[0]) + ch.width() + w / 2 + 2, (int) Math.round(p0[1]), Direction.WEST).size(w, d);
                    s.flags = q.flags();
                    s.variant = q.variant();
                    s.minFloor = q.raise() == 0 ? Spec.UNSET : q.raise();
                    if (!q.name().isEmpty() && i == 0) {
                        s.name = q.name();
                        s.flag(Spec.SIGN);
                    }
                    plan.place(s, 0);
                    plan.relaxed = false;
                }
            }
        }
    }

    // ------------------------------------------------------------------ frontage

    private static void walk(Zoning.Ctx x, Zoner zoner, Plan.Chain ch, int side) {
        double len = ch.length();
        if (len < 8) return;
        double t = ch.width() / 2.0 + 2;
        int gapMin = x.tier >= 1 ? 1 : 2, gapMax = x.tier >= 2 ? 2 : x.tier == 1 ? 3 : 4;
        int misses = 0;
        while (t < len - 3) {
            double[] p0 = ch.at(t), p1 = ch.at(Math.min(len, t + 8));
            double dx = p1[0] - p0[0], dz = p1[1] - p0[1], l = Math.hypot(dx, dz);
            if (l < 1e-3) {
                t += 2;
                continue;
            }
            int sx = (int) Math.round(p0[0]), sz = (int) Math.round(p0[1]);
            Street tmp = new Street(sx, sz, (int) Math.round(p0[0] + dx / l * 40), (int) Math.round(p0[1] + dz / l * 40), ch.width(), ch.rank());
            if (!lot(x, zoner, tmp, side, t, gapMin, gapMax, p0)) {
                if (misses++ % 3 == 0) Zoning.gapFiller(x, tmp, side, 0);
                t += 2;
            } else {
                misses = 0;
                t += lastAdvance;
            }
        }
    }

    private static double lastAdvance;

    /** Tries a lot at the street position; sets {@link #lastAdvance} to the distance to skip on success. */
    private static boolean lot(Zoning.Ctx x, Zoner zoner, Street tmp, int side, double t, int gapMin, int gapMax, double[] at) {
        Zone z = zoner.at(at[0], at[1]);
        if (z == Zone.CENTRE && x.tier >= 2 && x.r.nextInt(4) == 0) z = Zone.COMMON;
        Wealth wl = Zoning.wealthOf(x, z);
        Use use = Zoning.homeFor(x, wl);
        if (z == Zone.CRAFT && x.r.nextInt(5) == 0) use = x.r.nextBoolean() ? Use.WAREHOUSE : Use.TANNERY;
        int[] sz = use.size(wl, x.r);
        for (int shrink = 0; shrink <= 2; shrink++) {
            int w = Math.max(5, sz[0] - shrink * 2), d = Math.max(5, sz[1] - shrink * 2);
            Use u = shrink == 2 && use != Use.HOVEL ? Use.COTTAGE : use;
            Spec s = Zoning.lotOn(x, tmp, side, 0, u, wl, w, d, Zoning.setback(x, tmp));
            if (Zoning.tryPlace(x, s)) {
                lastAdvance = Zoning.alongStreet(tmp, side, w, d) + gapMin + x.r.nextInt(gapMax - gapMin + 1);
                return true;
            }
        }
        return false;
    }

    private static void frontageStreet(Zoning.Ctx x, Zoner zoner, Street st, int side) {
        double len = st.len();
        if (len < 8) return;
        double ux = (st.x2() - st.x1()) / len, uz = (st.z2() - st.z1()) / len;
        double t = st.width() / 2.0 + 2;
        int gapMin = x.tier >= 1 ? 1 : 2, gapMax = x.tier >= 2 ? 2 : x.tier == 1 ? 3 : 4;
        while (t < len - 3) {
            double px = st.x1() + ux * t, pz = st.z1() + uz * t;
            Street tmp = new Street((int) Math.round(px), (int) Math.round(pz), (int) Math.round(px + ux * 40), (int) Math.round(pz + uz * 40), st.width(), st.rank());
            if (lot(x, zoner, tmp, side, t, gapMin, gapMax, new double[]{px, pz})) t += lastAdvance;
            else t += 2;
        }
    }

    // ------------------------------------------------------------------ the gap filler

    /** Occupancy of the built disc in 2-block cells. */
    private static final class Grid {
        final int R, n;
        final boolean[][] g;

        Grid(int R) {
            this.R = R;
            this.n = R + 1;
            this.g = new boolean[2 * n][2 * n];
        }

        void mark(int x0, int z0, int x1, int z1) {
            for (int x = Math.floorDiv(x0 + R, 2); x <= Math.floorDiv(x1 + R, 2); x++) {
                for (int z = Math.floorDiv(z0 + R, 2); z <= Math.floorDiv(z1 + R, 2); z++) {
                    if (x >= 0 && z >= 0 && x < 2 * n && z < 2 * n) g[x][z] = true;
                }
            }
        }

        boolean at(int lx, int lz) {
            int x = Math.floorDiv(lx + R, 2), z = Math.floorDiv(lz + R, 2);
            return x < 0 || z < 0 || x >= 2 * n || z >= 2 * n || g[x][z];
        }

        void spec(Plan plan, Spec s) {
            if (s.has(Spec.GROUND) && s.kind != Archetype.TERRACE || s.kind == Archetype.CAMP && s.variant == Outdoors.CLEARING) return;
            if (s.isLine()) {
                if (s.kind == Archetype.WALL) return;
                int ax = Zoning.localX(plan, s.x, s.z), az = Zoning.localZ(plan, s.x, s.z), bx = Zoning.localX(plan, s.x2, s.z2), bz = Zoning.localZ(plan, s.x2, s.z2);
                int len = (int) Math.ceil(Math.hypot(bx - ax, bz - az));
                int hw = Math.max(1, s.w / 2);
                for (int i = 0; i <= len; i += 2) {
                    double t = len == 0 ? 0 : i / (double) len;
                    int px = (int) Math.round(ax + (bx - ax) * t), pz = (int) Math.round(az + (bz - az) * t);
                    mark(px - hw, pz - hw, px + hw, pz + hw);
                }
                return;
            }
            int cx = Zoning.localX(plan, s.x, s.z), cz = Zoning.localZ(plan, s.x, s.z);
            // the footprint in the local frame (the plan frame may be rotated against the world)
            Direction lf = localFacing(plan, s.facing);
            int ex = lf.getAxis() == Direction.Axis.Z ? s.w : s.d, ez = lf.getAxis() == Direction.Axis.Z ? s.d : s.w;
            int hx = ex / 2 + s.margin, hz = ez / 2 + s.margin;
            mark(cx - hx, cz - hz, cx + hx, cz + hz);
        }
    }

    static Direction localFacing(Plan plan, Direction world) {
        // plan.dir maps local -> world; invert by trying the four local directions
        for (Direction d : Direction.Plane.HORIZONTAL) if (plan.dir(d) == world) return d;
        return world;
    }

    private static Grid grid(Plan plan, int R) {
        Grid g = new Grid(R + 4);
        for (Spec s : plan.specs) g.spec(plan, s);
        return g;
    }

    private static void gaps(Zoning.Ctx x, Zoner zoner, double target, int passes) {
        Plan plan = x.plan;
        int R = x.R;
        Grid grid = grid(plan, R);
        for (int pass = 0; pass < passes; pass++) {
            List<int[]> pts = new ArrayList<>();
            int step = pass == 0 ? 6 : 4;
            for (int gx = -R; gx <= R; gx += step) for (int gz = -R; gz <= R; gz += step) if (Math.hypot(gx, gz) < R * 0.97) pts.add(new int[]{gx + x.r.nextInt(3) - 1, gz + x.r.nextInt(3) - 1});
            for (int i = pts.size() - 1; i > 0; i--) {
                int j = x.r.nextInt(i + 1);
                int[] tmp = pts.get(i);
                pts.set(i, pts.get(j));
                pts.set(j, tmp);
            }
            int before = plan.specs.size();
            for (int[] p : pts) {
                if (grid.at(p[0], p[1])) continue;
                double best = Double.MAX_VALUE, bx = 0, bz = 0;
                for (Street st : x.sk.streets) {
                    double vx = st.x2() - st.x1(), vz = st.z2() - st.z1(), l2 = vx * vx + vz * vz;
                    double t = l2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ((p[0] - st.x1()) * vx + (p[1] - st.z1()) * vz) / l2));
                    double qx = st.x1() + vx * t, qz = st.z1() + vz * t;
                    double dd = Math.hypot(qx - p[0], qz - p[1]) - st.width() / 2.0;
                    if (dd < best) {
                        best = dd;
                        bx = qx;
                        bz = qz;
                    }
                }
                Zone z = zoner.at(p[0], p[1]);
                Wealth wl = z == Zone.NOBLE ? Wealth.WEALTHY : z == Zone.CENTRE || z == Zone.COMMON ? Wealth.COMMON : Wealth.POOR;
                int mark = plan.specs.size();
                boolean houseHere = best >= 3 && best <= (pass == 0 ? 16 : 24);
                if (houseHere && !(pass == 2 && x.r.nextInt(3) == 0)) {
                    Use use = wl == Wealth.POOR ? (x.r.nextBoolean() ? Use.HOVEL : Use.COTTAGE) : wl == Wealth.COMMON ? (x.r.nextInt(3) == 0 ? Use.COTTAGE : Use.HOUSE) : Use.HOUSE;
                    Direction face = Zoning.cardinal(bx - p[0], bz - p[1]);
                    for (int shrink = 0; shrink <= 2; shrink++) {
                        int[] sz = use.size(wl, x.r);
                        Spec s = plan.at(use.archetype(), p[0], p[1], face).size(Math.max(5, sz[0] - shrink * 2), Math.max(5, sz[1] - shrink * 2));
                        s.use(shrink == 2 ? Use.COTTAGE : use, wl);
                        s.margin = 1;
                        if (Zoning.tryPlace(x, s)) {
                            // a footpath from the door to the street
                            int fx = face.getStepX(), fz = face.getStepZ();
                            int half = (face.getAxis() == Direction.Axis.Z ? s.d : s.w) / 2;
                            int sx = p[0] + fx * (half + 2), sz2 = p[1] + fz * (half + 2);
                            int ex = (int) Math.round(bx), ez = (int) Math.round(bz);
                            Spec path = plan.line(Archetype.STREET, sx, sz2, fx != 0 ? ex : sx, fz != 0 ? ez : sz2, 1);
                            if (plan.fits(path, 0)) plan.add(path);
                            break;
                        }
                    }
                }
                if (plan.specs.size() == mark && (pass >= 1 || best > 16)) filler(x, p[0], p[1], wl, pass);
                for (int k = mark; k < plan.specs.size(); k++) grid.spec(plan, plan.specs.get(k));
            }
            double[] m = measure(plan, R);
            if (m[1] >= target && pass >= 1) break;
            if (plan.specs.size() == before && pass > 0) break;
        }
    }

    /** Something small and lived-in on a bare cell: kitchen garden, pen, orchard patch, well, tree, flowers, yard. */
    private static void filler(Zoning.Ctx x, int lx, int lz, Wealth wl, int pass) {
        Plan plan = x.plan;
        int roll = x.r.nextInt(12);
        Spec s;
        Direction face = Direction.SOUTH;
        if (roll < 4) {
            s = plan.at(Archetype.GARDEN, lx, lz, face).size(5 + 2 * x.r.nextInt(2), 5);
            s.variant = Zoning.cropVariant(x);
        } else if (roll < 6) {
            s = plan.at(Archetype.PEN, lx, lz, face).size(7, 5);
            s.variant = x.r.nextInt(8);
        } else if (roll == 6 || roll == 7) {
            s = plan.at(Archetype.YARD, lx, lz, face).size(5, 5);
            s.variant = Outdoors.TREE;
            s.h = 9;
        } else if (roll == 8) {
            s = plan.at(Archetype.YARD, lx, lz, face).size(5, 5);
            s.variant = Outdoors.WELL;
            s.h = 6;
        } else if (roll == 9) {
            s = plan.at(Archetype.YARD, lx, lz, face).size(5, 3);
            s.variant = Outdoors.FLOWER_BED;
            s.h = 3;
        } else if (roll == 10) {
            s = plan.at(Archetype.YARD, lx, lz, face).size(4, 2);
            s.variant = x.r.nextBoolean() ? Outdoors.WOODPILE : Outdoors.HAY;
            s.h = 4;
        } else {
            s = plan.at(Archetype.CAMP, lx, lz, face).size(9, 9);
            s.variant = Outdoors.ORCHARD;
            s.h = 7;
        }
        s.margin = 0;
        plan.place(s, 0);
    }

    // ------------------------------------------------------------------ measuring

    /**
     * Built-area ratios inside the local disc of radius {@code R}: {@code [buildings, developed]}. Buildings is the
     * footprint area of all buildings over the disc; developed adds streets, plazas, gardens, pens, yards and fields.
     */
    public static double[] measure(Plan plan, int R) {
        double disc = Math.PI * R * R;
        double built = 0;
        boolean[][] occ = new boolean[2 * R + 1][2 * R + 1];
        for (Spec s : plan.specs) {
            if (s.kind == Archetype.WALL || s.kind == Archetype.GATE) continue;
            if (s.kind == Archetype.CAMP && s.variant == Outdoors.CLEARING || s.has(Spec.GROUND) && s.kind != Archetype.TERRACE) continue;
            if (s.kind == Archetype.TERRACE) continue;
            int cx = Zoning.localX(plan, s.x, s.z), cz = Zoning.localZ(plan, s.x, s.z);
            int x0, z0, x1, z1;
            if (s.isLine()) {
                int ax = cx, az = cz, bx = Zoning.localX(plan, s.x2, s.z2), bz = Zoning.localZ(plan, s.x2, s.z2);
                int len = (int) Math.ceil(Math.hypot(bx - ax, bz - az));
                int hw = Math.max(0, s.w / 2);
                for (int i = 0; i <= len; i++) {
                    double t = len == 0 ? 0 : i / (double) len;
                    int px = (int) Math.round(ax + (bx - ax) * t), pz = (int) Math.round(az + (bz - az) * t);
                    for (int dx = -hw; dx <= hw; dx++) for (int dz = -hw; dz <= hw; dz++) occupy(occ, R, px + dx, pz + dz);
                }
                continue;
            }
            Direction lf = localFacing(plan, s.facing);
            int ex = lf.getAxis() == Direction.Axis.Z ? s.w : s.d, ez = lf.getAxis() == Direction.Axis.Z ? s.d : s.w;
            x0 = cx - (ex - 1) / 2;
            z0 = cz - (ez - 1) / 2;
            x1 = x0 + ex - 1;
            z1 = z0 + ez - 1;
            if (s.kind.building && s.kind != Archetype.TOWER || s.kind == Archetype.TOWER && s.grammar()) {
                for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                    if (Math.hypot(x, z) <= R) built++;
                }
            }
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) occupy(occ, R, x, z);
        }
        int dev = 0;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) if (Math.hypot(x, z) <= R && occ[x + R][z + R]) dev++;
        return new double[]{Math.min(1, built / disc), Math.min(1, dev / disc)};
    }

    private static void occupy(boolean[][] occ, int R, int x, int z) {
        int ix = x + R, iz = z + R;
        if (ix >= 0 && iz >= 0 && ix < occ.length && iz < occ.length) occ[ix][iz] = true;
    }
}
