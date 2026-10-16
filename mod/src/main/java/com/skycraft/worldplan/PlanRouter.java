package com.skycraft.worldplan;

import com.skycraft.roads.PlanLinks;
import com.skycraft.tamriel.MapSample;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Finds the route of one link on the seed-independent {@link PlanTerrain}: the road planner's A* (hierarchical: a
 * 32-block corridor for long links, refined on an 8-block grid stretch by stretch; any-angle straightening; Chaikin
 * smoothing), with costs for slope (and for climbing steeper than the class's grade cap), curvature, water crossings
 * (a bridge costs by the width of the water, a culvert under a narrow stream little, a ford on a footpath little),
 * the map's road corridors, roads already planned (so later roads join them) and settlement interiors.
 *
 * <p>Pure function of its job and the immutable terrain, so routes are identical on every run and every thread.</p>
 */
final class PlanRouter {
    static final int CELL = 8;
    private static final int COARSE_CELL = 32;
    private static final double SQRT2 = Math.sqrt(2.0);
    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};
    private static final double SLOPE_K = 25.0;
    /** Extra cost per block and per cap of grade over the class's cap (the profile would need cut, fill or a switchback). */
    private static final double OVER_CAP_K = 6.0;
    private static final double CLIFF_COST = 400.0;
    private static final double TURN_COST = 3.0;
    private static final double ROAD_DISCOUNT = 0.25;
    private static final double HEURISTIC_WEIGHT = 1.15;
    private static final double DIRECT_LIMIT = 384;
    private static final int WAYPOINT_STEP = 8;
    /** Water costs: per block of bridge (plus per block of water width), entering a bridge, culvert, ford. */
    private static final double BRIDGE_PER_BLOCK = 4.0, BRIDGE_WIDTH_K = 1.0 / 16.0, BRIDGE_ENTRY = 60.0;
    private static final double CULVERT_PER_BLOCK = 0.5, CULVERT_ENTRY = 6.0, FORD_PER_BLOCK = 1.5, FORD_ENTRY = 4.0;
    /** Streams narrower than this (blocks) pass under the road in a culvert. */
    static final float CULVERT_WIDTH = 6f;
    /** A footpath fords water this shallow (blocks) and narrow. */
    static final float FORD_DEPTH = 1.5f, FORD_WIDTH = 16f;
    private static final int STRAIGHTEN_LOOK = 28;
    /** Douglas-Peucker tolerance (blocks) after straightening. */
    private static final double SIMPLIFY = 10.0;

    /** One routing job. {@code avoid}: settlement interiors as (x, z, radius) triples. */
    record Job(double ax, double az, double bx, double bz, byte kind, int maxBridge, int[] avoid, LongOpenHashSet roadCells,
               int budget, int siteA, int siteB) {
        Job(double ax, double az, double bx, double bz, byte kind, int maxBridge, int[] avoid, LongOpenHashSet roadCells, int budget) {
            this(ax, az, bx, bz, kind, maxBridge, avoid, roadCells, budget, -1, -1);
        }
    }

    private PlanRouter() {}

    private static final class Cell {
        float ground, eff, water = Float.NaN, width, road;
        boolean lake, sea, blocked;
        float penalty;
    }

    private static final class Grid {
        final PlanTerrain terrain;
        final int cell;
        final Job job;
        final MapSample sample = new MapSample();
        final PlanTerrain.Col col = new PlanTerrain.Col();
        final Long2ObjectOpenHashMap<Cell> cache = new Long2ObjectOpenHashMap<>();

        Grid(PlanTerrain terrain, int cell, Job job) {
            this.terrain = terrain;
            this.cell = cell;
            this.job = job;
        }

        Cell cell(int cx, int cz) {
            long k = key(cx, cz);
            Cell c = cache.get(k);
            if (c != null) return c;
            c = new Cell();
            int x = cx * cell + cell / 2, z = cz * cell + cell / 2;
            terrain.sample(x, z, sample, col);
            c.ground = col.ground;
            c.road = col.road;
            if (PlanLinks.onCorridor(x, z)) c.road = 1.6f;
            if (!Float.isNaN(col.water)) {
                c.water = col.water;
                c.width = col.waterWidth;
                c.lake = col.lake;
                c.sea = col.sea;
            }
            c.eff = Float.isNaN(c.water) ? c.ground : Math.max(c.ground, c.water);
            float half = job.maxBridge() / 2f + 8f;
            if (col.seaDepthIn > half) c.blocked = true;
            if (!Float.isNaN(c.water)) {
                if (col.lakeDepthIn > half) c.blocked = true;
                if (!c.lake && !c.sea && c.width > job.maxBridge() + 8) c.blocked = true;
            }
            // Exempt cells within 20 blocks of the chosen gate endpoints from settlement/pin avoidance penalties
            double dA = Math.hypot(x - job.ax(), z - job.az());
            double dB = Math.hypot(x - job.bx(), z - job.bz());
            if (dA > 20.0 && dB > 20.0) {
                int[] avoid = job.avoid();
                for (int i = 0; i + 2 < avoid.length; i += 3) {
                    long dx = x - avoid[i], dz = z - avoid[i + 1];
                    if (dx * dx + dz * dz < (long) avoid[i + 2] * avoid[i + 2]) c.penalty = 40f;
                }
            }
            cache.put(k, c);
            return c;
        }
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** The 8-block grid cell key of a block position (for {@link Job#roadCells}). */
    static long cellKey(double x, double z) {
        return key(Math.floorDiv((int) Math.floor(x), CELL), Math.floorDiv((int) Math.floor(z), CELL));
    }

    private static boolean onRoad(double[] p, @Nullable LongOpenHashSet roadCells) {
        return roadCells != null && !roadCells.isEmpty() && roadCells.contains(cellKey(p[0], p[1]));
    }

    private static boolean hasRoadCell(@Nullable LongOpenHashSet roadCells, int cx, int cz, boolean fineGrid) {
        if (roadCells == null || roadCells.isEmpty()) return false;
        if (fineGrid) return roadCells.contains(key(cx, cz));
        int baseX = cx << 2, baseZ = cz << 2;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                if (roadCells.contains(key(baseX + dx, baseZ + dz))) return true;
            }
        }
        return false;
    }

    private static final class Node {
        final int x, z;
        double g = Double.MAX_VALUE;
        Node parent;
        int dir = -1;
        boolean closed;

        Node(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private record Open(double f, double g, long seq, Node node) {
    }

    /** The smoothed route as {x, z} points (not yet resampled), or null if no acceptable path exists. */
    @Nullable
    static List<double[]> route(PlanTerrain terrain, Job job) {
        Grid fine = new Grid(terrain, CELL, job);
        double dx = job.bx() - job.ax(), dz = job.bz() - job.az();
        List<double[]> poly;
        if (Math.sqrt(dx * dx + dz * dz) <= DIRECT_LIMIT) {
            poly = segment(job, fine, job.ax(), job.az(), job.bx(), job.bz());
        } else {
            Grid coarse = new Grid(terrain, COARSE_CELL, job);
            List<double[]> corridor = astar(job, coarse, job.ax(), job.az(), job.bx(), job.bz(), 8);
            if (corridor == null) return null;
            List<double[]> waypoints = new ArrayList<>();
            waypoints.add(new double[]{job.ax(), job.az()});
            for (int i = WAYPOINT_STEP; i < corridor.size() - WAYPOINT_STEP / 2; i += WAYPOINT_STEP) {
                double[] p = corridor.get(i);
                // a waypoint in water would force the fine route through it: keep it only on dry land
                Cell c = fine.cell(Math.floorDiv((int) Math.floor(p[0]), CELL), Math.floorDiv((int) Math.floor(p[1]), CELL));
                if (!Float.isNaN(c.water) || c.blocked) continue;
                waypoints.add(p);
            }
            waypoints.add(new double[]{job.bx(), job.bz()});
            poly = new ArrayList<>();
            for (int i = 0; i + 1 < waypoints.size(); i++) {
                double[] from = waypoints.get(i), to = waypoints.get(i + 1);
                List<double[]> part = segment(job, fine, from[0], from[1], to[0], to[1]);
                if (part == null) return null;
                if (!poly.isEmpty()) part.remove(0);
                poly.addAll(part);
            }
        }
        if (poly == null) return null;
        poly = straighten(poly, fine);
        poly = simplify(poly, fine, SIMPLIFY);
        poly = withGateApproaches(poly, terrain, fine, job);
        List<double[]> smoothed = chaikin(chaikin(poly, job.roadCells()), job.roadCells());
        smoothed.set(0, new double[]{job.ax(), job.az()});
        smoothed.set(smoothed.size() - 1, new double[]{job.bx(), job.bz()});
        return smoothed;
    }

    /**
     * Pins exact gate coordinates (ax, az) and (bx, bz) and inserts an outward gate-normal approach point 12 blocks
     * straight outside each gate before Chaikin smoothing so roads meet settlement gates dead-center and perpendicular.
     */
    private static List<double[]> withGateApproaches(List<double[]> poly, PlanTerrain terrain, Grid fine, Job job) {
        if (poly.size() < 2) return poly;
        List<double[]> out = new ArrayList<>(poly);
        out.set(0, new double[]{job.ax(), job.az()});
        out.set(out.size() - 1, new double[]{job.bx(), job.bz()});
        double[] na = gateNormal(terrain, job.siteA(), job.ax(), job.az());
        if (na != null) {
            double px = job.ax() + na[0] * 12.0, pz = job.az() + na[1] * 12.0;
            Cell c = fine.cell(Math.floorDiv((int) Math.floor(px), CELL), Math.floorDiv((int) Math.floor(pz), CELL));
            if (Float.isNaN(c.water) && !c.blocked) {
                while (out.size() > 2 && Math.hypot(out.get(1)[0] - job.ax(), out.get(1)[1] - job.az()) < 16.0) {
                    out.remove(1);
                }
                out.add(1, new double[]{px, pz});
            }
        }
        double[] nb = gateNormal(terrain, job.siteB(), job.bx(), job.bz());
        if (nb != null) {
            double px = job.bx() + nb[0] * 12.0, pz = job.bz() + nb[1] * 12.0;
            Cell c = fine.cell(Math.floorDiv((int) Math.floor(px), CELL), Math.floorDiv((int) Math.floor(pz), CELL));
            if (Float.isNaN(c.water) && !c.blocked) {
                while (out.size() > 2 && Math.hypot(out.get(out.size() - 2)[0] - job.bx(), out.get(out.size() - 2)[1] - job.bz()) < 16.0) {
                    out.remove(out.size() - 2);
                }
                out.add(out.size() - 1, new double[]{px, pz});
            }
        }
        return out;
    }

    @Nullable
    private static double[] gateNormal(PlanTerrain terrain, int site, double gx, double gz) {
        int s = site;
        if (s < 0 || s >= terrain.siteX.length) {
            for (int i = 0; i < terrain.siteX.length; i++) {
                double d = Math.hypot(gx - terrain.siteX[i], gz - terrain.siteZ[i]);
                if (d >= 8.0 && d <= terrain.siteRadius[i] + 16.0) {
                    s = i;
                    break;
                }
            }
        }
        if (s < 0 || s >= terrain.siteX.length) return null;
        double dx = gx - terrain.siteX[s], dz = gz - terrain.siteZ[s];
        double d = Math.hypot(dx, dz);
        if (d < 8.0) return null;
        return new double[]{dx / d, dz / d};
    }

    /**
     * Douglas-Peucker simplification (tolerance in blocks): removes the grid's sawtooth on slopes, where the straight
     * pass cannot see a clear line (the grade there is the profile's and the switchbacks' job, not the grid's). A
     * shortcut never leaves the corridor's dry ground where the route was dry, nor enters a blocked cell or a town.
     */
    private static List<double[]> simplify(List<double[]> path, Grid grid, double tol) {
        int n = path.size();
        if (n < 3) return path;
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        LongOpenHashSet roadCells = grid.job.roadCells();
        for (int i = 1; i < n - 1; i++) {
            if (onRoad(path.get(i), roadCells)) keep[i] = true;
        }
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] r = stack.pop();
            int a = r[0], b = r[1];
            if (b - a < 2) continue;
            double[] pa = path.get(a), pb = path.get(b);
            double dx = pb[0] - pa[0], dz = pb[1] - pa[1], len = Math.hypot(dx, dz);
            int far = -1;
            double worst = -1;
            boolean wetBefore = false;
            boolean hasPinnedRoad = false;
            for (int i = a + 1; i < b; i++) {
                double[] p = path.get(i);
                double d = len < 1e-9 ? Math.hypot(p[0] - pa[0], p[1] - pa[1]) : Math.abs((p[0] - pa[0]) * dz - (p[1] - pa[1]) * dx) / len;
                if (d > worst) {
                    worst = d;
                    far = i;
                }
                if (keep[i]) hasPinnedRoad = true;
                Cell c = grid.cell(Math.floorDiv((int) Math.floor(p[0]), grid.cell), Math.floorDiv((int) Math.floor(p[1]), grid.cell));
                wetBefore |= !Float.isNaN(c.water);
            }
            if (worst > tol || hasPinnedRoad && worst > 0.5 || !clearShortcut(pa, pb, grid, wetBefore)) {
                keep[far] = true;
                stack.push(new int[]{a, far});
                stack.push(new int[]{far, b});
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(path.get(i));
        return out;
    }

    private static boolean clearShortcut(double[] a, double[] b, Grid grid, boolean wetAllowed) {
        int cell = grid.cell;
        double dx = b[0] - a[0], dz = b[1] - a[1];
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / (cell * 0.5)));
        for (int s = 1; s < steps; s++) {
            double t = s / (double) steps;
            Cell c = grid.cell(Math.floorDiv((int) Math.floor(a[0] + dx * t), cell), Math.floorDiv((int) Math.floor(a[1] + dz * t), cell));
            if (c.blocked || c.penalty > 0 || (!wetAllowed && !Float.isNaN(c.water))) return false;
        }
        return true;
    }

    @Nullable
    private static List<double[]> segment(Job job, Grid fine, double ax, double az, double bx, double bz) {
        List<double[]> p = astar(job, fine, ax, az, bx, bz, 16);
        if (p == null) p = astar(job, fine, ax, az, bx, bz, 40);
        return p;
    }

    @Nullable
    private static List<double[]> astar(Job job, Grid grid, double ax, double az, double bx, double bz, int minMargin) {
        int cell = grid.cell;
        int sx = Math.floorDiv((int) Math.floor(ax), cell), sz = Math.floorDiv((int) Math.floor(az), cell);
        int gx = Math.floorDiv((int) Math.floor(bx), cell), gz = Math.floorDiv((int) Math.floor(bz), cell);
        int span = Math.max(Math.abs(gx - sx), Math.abs(gz - sz));
        int margin = Math.max(minMargin, span / 2);
        int minX = Math.min(sx, gx) - margin, maxX = Math.max(sx, gx) + margin;
        int minZ = Math.min(sz, gz) - margin, maxZ = Math.max(sz, gz) + margin;
        boolean fineGrid = cell == CELL;
        double cliff = cell * 0.5;
        double cliffCost = CLIFF_COST * cell / CELL;
        // (footpaths: stairs up to 1:1 before the route bends away from the fall line)
        double cap = RoadPlanBuilder.gradeCap(job.kind());
        boolean longBridge = job.maxBridge() > PlanLinks.DEFAULT_BRIDGE;
        boolean trail = job.kind() == PlanLinks.TRAIL;

        Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>((p, q) -> {
            int c = Double.compare(p.f(), q.f());
            return c != 0 ? c : Long.compare(p.seq(), q.seq());
        });
        long seq = 0;
        Node start = new Node(sx, sz);
        start.g = 0;
        nodes.put(key(sx, sz), start);
        open.add(new Open(heuristic(sx, sz, gx, gz, cell), 0, seq++, start));
        int expansions = 0;
        while (!open.isEmpty()) {
            Open entry = open.poll();
            Node cur = entry.node();
            if (cur.closed || entry.g() > cur.g + 1e-6) continue;
            cur.closed = true;
            if (cur.x == gx && cur.z == gz) return reconstruct(cur, cell, ax, az, bx, bz);
            if (++expansions > job.budget()) return null;
            Cell cc = grid.cell(cur.x, cur.z);
            boolean curWet = !Float.isNaN(cc.water);
            for (int d = 0; d < 8; d++) {
                int nx = cur.x + DX[d], nz = cur.z + DZ[d];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                long k = key(nx, nz);
                Node nb = nodes.get(k);
                if (nb != null && nb.closed) continue;
                Cell c = grid.cell(nx, nz);
                boolean goal = nx == gx && nz == gz;
                if (c.blocked && !goal) continue;
                double len = ((d & 1) == 0 ? 1.0 : SQRT2) * cell;
                double rise = Math.abs(c.eff - cc.eff);
                double grade = rise / len;
                double cost = len * (1.0 + SLOPE_K * grade * grade);
                if (grade > cap) cost += len * OVER_CAP_K * (grade - cap) / cap;
                if (rise > cliff) cost += cliffCost;
                if (!Float.isNaN(c.water)) {
                    float depth = c.water - c.ground;
                    if (longBridge) {
                        cost += len * 3.0; // the Talos Bridge runs straight across
                    } else if (trail && !c.lake && !c.sea && depth <= FORD_DEPTH && c.width <= FORD_WIDTH) {
                        cost += len * FORD_PER_BLOCK + (curWet ? 0 : FORD_ENTRY);
                    } else if (!c.lake && !c.sea && c.width < CULVERT_WIDTH) {
                        cost += len * CULVERT_PER_BLOCK + (curWet ? 0 : CULVERT_ENTRY);
                    } else {
                        cost += len * (BRIDGE_PER_BLOCK + Math.min(c.width, 256f) * BRIDGE_WIDTH_K) + (curWet ? 0 : BRIDGE_ENTRY);
                    }
                } else {
                    cost *= 1.0 - 0.4 * c.road;
                    if (hasRoadCell(job.roadCells(), nx, nz, fineGrid)) cost *= ROAD_DISCOUNT;
                }
                cost += len * c.penalty;
                if (cur.dir >= 0 && cur.dir != d) {
                    int turn = Math.abs(cur.dir - d);
                    cost += TURN_COST * Math.min(turn, 8 - turn);
                }
                double g = cur.g + cost;
                if (nb == null) {
                    nb = new Node(nx, nz);
                    nodes.put(k, nb);
                } else if (g >= nb.g) {
                    continue;
                }
                nb.g = g;
                nb.parent = cur;
                nb.dir = d;
                open.add(new Open(g + heuristic(nx, nz, gx, gz, cell), g, seq++, nb));
            }
        }
        return null;
    }

    private static double heuristic(int x, int z, int gx, int gz, int cell) {
        int dx = Math.abs(gx - x), dz = Math.abs(gz - z);
        double octile = Math.max(dx, dz) + (SQRT2 - 1.0) * Math.min(dx, dz);
        return octile * cell * HEURISTIC_WEIGHT;
    }

    private static List<double[]> reconstruct(Node goal, int cell, double ax, double az, double bx, double bz) {
        List<double[]> out = new ArrayList<>();
        for (Node n = goal; n != null; n = n.parent) out.add(new double[]{n.x * cell + cell / 2.0, n.z * cell + cell / 2.0});
        Collections.reverse(out);
        out.set(0, new double[]{ax, az});
        if (out.size() == 1) out.add(new double[]{bx, bz});
        else out.set(out.size() - 1, new double[]{bx, bz});
        return out;
    }

    /**
     * Any-angle smoothing: each point is joined straight to the farthest later point reachable in a line over gentle,
     * dry, already-sampled ground (the grid's zigzag becomes straight runs and gentle bends).
     */
    private static List<double[]> straighten(List<double[]> path, Grid grid) {
        int n = path.size();
        if (n < 3) return path;
        LongOpenHashSet roadCells = grid.job.roadCells();
        List<double[]> out = new ArrayList<>();
        out.add(path.get(0));
        int i = 0;
        while (i < n - 1) {
            int next = i + 1;
            int maxLook = Math.min(n - 1, i + STRAIGHTEN_LOOK);
            for (int k = i + 1; k < maxLook; k++) {
                if (onRoad(path.get(k), roadCells)) {
                    maxLook = k;
                    break;
                }
            }
            for (int j = maxLook; j > i + 1; j--) {
                if (clearLine(path.get(i), path.get(j), grid)) {
                    next = j;
                    break;
                }
            }
            out.add(path.get(next));
            i = next;
        }
        return out;
    }

    private static boolean clearLine(double[] a, double[] b, Grid grid) {
        int cell = grid.cell;
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double len = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / (cell * 0.5)));
        double cap = RoadPlanBuilder.gradeCap(grid.job.kind()) * cell * 0.5;
        float last = Float.NaN;
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            int cx = Math.floorDiv((int) Math.floor(a[0] + dx * t), cell), cz = Math.floorDiv((int) Math.floor(a[1] + dz * t), cell);
            Cell c = grid.cache.get(key(cx, cz));
            if (c == null || c.blocked || c.penalty > 0 || !Float.isNaN(c.water)) return false;
            if (!Float.isNaN(last) && Math.abs(c.ground - last) > Math.max(1.0, cap)) return false;
            last = c.ground;
        }
        return true;
    }

    static List<double[]> chaikin(List<double[]> p) {
        return chaikin(p, null);
    }

    static List<double[]> chaikin(List<double[]> p, @Nullable LongOpenHashSet roadCells) {
        if (p.size() < 3) return p;
        List<double[]> out = new ArrayList<>(p.size() * 2);
        out.add(p.get(0));
        for (int i = 0; i < p.size() - 1; i++) {
            double[] a = p.get(i), b = p.get(i + 1);
            boolean aOn = onRoad(a, roadCells), bOn = onRoad(b, roadCells);
            if (aOn && bOn) {
                if (i > 0) out.add(a);
            } else if (aOn) {
                if (i > 0) out.add(a);
                out.add(new double[]{a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75});
            } else if (bOn) {
                out.add(new double[]{a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25});
            } else {
                out.add(new double[]{a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25});
                out.add(new double[]{a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75});
            }
        }
        out.add(p.get(p.size() - 1));
        return out;
    }

    /** Resamples a polyline to points {@code spacing} blocks apart (the ends kept). */
    static List<double[]> resample(List<double[]> poly, double spacing) {
        List<double[]> out = new ArrayList<>();
        out.add(poly.get(0));
        double carry = 0;
        for (int i = 0; i < poly.size() - 1; i++) {
            double[] a = poly.get(i), b = poly.get(i + 1);
            double dx = b[0] - a[0], dz = b[1] - a[1];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-9) continue;
            double t = spacing - carry;
            while (t < len - 1e-6) {
                out.add(new double[]{a[0] + dx * t / len, a[1] + dz * t / len});
                t += spacing;
            }
            carry = len - (t - spacing);
        }
        double[] last = poly.get(poly.size() - 1), prev = out.get(out.size() - 1);
        if (Math.hypot(last[0] - prev[0], last[1] - prev[1]) < spacing * 0.35 && out.size() > 1) out.set(out.size() - 1, last);
        else out.add(last);
        return out;
    }
}
