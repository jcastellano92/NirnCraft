package com.skycraft.roads;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Plans a road between two points without touching any chunk: A* over a grid (8-block cells; long links first find
 * their corridor on a 32-block grid and are then refined stretch by stretch) whose heights come from the chunk
 * generator's noise ({@link ChunkGenerator#getBaseHeight}), then Chaikin smoothing and densification to one point
 * every 2 blocks. If no acceptable path exists the link is dropped, never replaced by a straight line. Runs on the background planner thread; everything it reads is either
 * immutable or thread-safe world generation state.
 *
 * <p>The cost also reads the Tamriel map: its road corridors (the valleys and passes the old roads take) are cheaper,
 * elevated lakes and rivers count as water at their real surface, water wider than the link's longest allowed bridge
 * and open sea are impassable, and walled cities near the route are expensive to cross (roads go round to the gate).</p>
 */
final class RoadPlanner {
    static final int CELL = 8;
    static final double SPACING = 2.0;
    private static final double SQRT2 = Math.sqrt(2.0);
    /** Even directions are orthogonal, odd ones diagonal. */
    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};
    private static final double SLOPE_K = 25.0;
    /** A rise above 1:2 between neighbouring cells is a cliff: the road switches back instead (stairs/ramp only where nothing else fits). */
    private static final int CLIFF = CELL / 2;
    private static final double CLIFF_COST = 400.0;
    private static final double SHALLOW_WATER = 6.0;
    private static final double DEEP_WATER = 30.0;
    private static final double ROAD_DISCOUNT = 0.35;
    private static final double TURN_COST = 3.0;
    private static final double HEURISTIC_WEIGHT = 1.15;
    /** Links up to this long (blocks) are planned directly on the fine grid. */
    private static final double DIRECT_LIMIT = 384;
    /** Coarse grid used to find the corridor of long links. */
    private static final int COARSE_CELL = 32;
    /** Long links are refined between corridor waypoints this many coarse cells apart (~256 blocks). */
    private static final int WAYPOINT_STEP = 8;

    /**
     * @param maxBridge longest water crossing (blocks) the road may bridge
     * @param avoid     circles (x, z, radius triples) the road should go round: walled cities
     */
    record Job(int generation, int a, int b, int ax, int az, int bx, int bz, int seaLevel, int maxExpansions,
               int longWaterPoints, LongOpenHashSet roadCells, int maxBridge, int[] avoid) {
    }

    /**
     * A planned road, or no points at all if no acceptable path exists ({@code aborted} = the plan was interrupted or
     * crashed rather than proven impossible, so the pair may be tried again later).
     */
    record Result(int generation, int a, int b, int[] xs, int[] zs, byte[] flags, boolean aborted, int expansions, int samples) {
    }

    private RoadPlanner() {}

    /** What the planner knows of one grid cell: noise ground height, water surface, map hints. */
    private static final class Cell {
        /** First free y above the solid terrain (water does not count as solid). */
        int raw;
        /** Water surface over this cell (sea level, or an elevated lake's or river's level), or MIN_VALUE if dry. */
        int water = Integer.MIN_VALUE;
        /** 0..1 road corridor strength from the map. */
        float road;
        /** Open sea, or water wider than the longest allowed bridge. */
        boolean blocked;
        /** Extra cost per block (inside a walled city). */
        float penalty;
    }

    /** Terrain per grid cell ({@code cell} blocks), cached: noise heights plus the Tamriel map's water and hints. */
    private static final class Heights {
        private final ChunkGenerator generator;
        private final RandomState random;
        private final LevelHeightAccessor heightAccessor;
        private final int sea;
        private final int cell;
        private final int maxBridge;
        private final int[] avoid;
        private final MapSample sample = new MapSample();
        private final Long2ObjectOpenHashMap<Cell> cache = new Long2ObjectOpenHashMap<>();
        int samples;

        Heights(ChunkGenerator generator, RandomState random, LevelHeightAccessor heightAccessor, int sea, int cell,
                int maxBridge, int[] avoid) {
            this.generator = generator;
            this.random = random;
            this.heightAccessor = heightAccessor;
            this.sea = sea;
            this.cell = cell;
            this.maxBridge = maxBridge;
            this.avoid = avoid;
        }

        Cell cell(int cx, int cz) {
            long k = ChunkPos.asLong(cx, cz);
            Cell c = cache.get(k);
            if (c != null) return c;
            c = new Cell();
            samples++;
            int x = cx * cell + cell / 2, z = cz * cell + cell / 2;
            try {
                c.raw = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, random);
            } catch (RuntimeException e) {
                c.raw = sea;
            }
            if (c.raw < sea) c.water = sea;
            TamrielMap.sample(x, z, sample);
            c.road = sample.road;
            if (Corridors.on(x, z)) c.road = 1.6f; // a traced historic route: strongly preferred
            if (sample.inWater() && sample.waterLevel > c.raw) c.water = Math.max(c.water, Math.round(sample.waterLevel));
            float half = maxBridge / 2f + 8f;
            if (sample.coastDistance < -half) c.blocked = true; // open sea, in the raster or beyond it
            if (c.water != Integer.MIN_VALUE) {
                if (sample.lakeDistance > half) c.blocked = true;
                if (sample.riverHalfWidth > half && sample.riverDistance < sample.riverHalfWidth - half) c.blocked = true;
            }
            for (int i = 0; i + 2 < avoid.length; i += 3) {
                long dx = x - avoid[i], dz = z - avoid[i + 1];
                if (dx * dx + dz * dz < (long) avoid[i + 2] * avoid[i + 2]) c.penalty = 40f;
            }
            cache.put(k, c);
            return c;
        }

        boolean water(int cx, int cz) {
            return cell(cx, cz).water != Integer.MIN_VALUE;
        }

        /** Walking height: the water surface counts as the deck of a bridge. */
        int effective(int cx, int cz) {
            Cell c = cell(cx, cz);
            return Math.max(c.raw, c.water);
        }
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

    private record Open(double f, double g, Node node) {
    }

    /** Thrown out of A* when the planner thread is interrupted (server stopping, replan). */
    private static final class Aborted extends RuntimeException {
        Aborted() {
            super(null, null, false, false);
        }
    }

    static Result plan(Job job, ChunkGenerator generator, RandomState random, LevelHeightAccessor heightAccessor) {
        Heights fine = new Heights(generator, random, heightAccessor, job.seaLevel(), CELL, job.maxBridge(), job.avoid());
        int[] expansions = new int[2];
        List<double[]> poly;
        try {
            poly = route(job, fine, generator, random, heightAccessor, expansions);
        } catch (Aborted e) {
            return new Result(job.generation(), job.a(), job.b(), new int[0], new int[0], new byte[0], true, expansions[0], expansions[1] + fine.samples);
        }
        if (poly == null) {
            // never a straight line over cliffs or sea: the caller links another neighbour instead
            return new Result(job.generation(), job.a(), job.b(), new int[0], new int[0], new byte[0], false, expansions[0], expansions[1] + fine.samples);
        }
        poly = chaikin(chaikin(poly));
        IntArrayList xs = new IntArrayList();
        IntArrayList zs = new IntArrayList();
        densify(poly, xs, zs);
        byte[] flags = waterFlags(xs, zs, fine, job.longWaterPoints());
        return new Result(job.generation(), job.a(), job.b(), xs.toIntArray(), zs.toIntArray(), flags, false, expansions[0], expansions[1] + fine.samples);
    }

    // ------------------------------------------------------------------ hierarchical routing

    /**
     * Short links: one A* on the 8-block grid. Long links: an A* on a coarse 32-block grid finds the corridor, then
     * each ~256-block stretch of it is refined on the 8-block grid with its own node budget (so the budget no longer
     * has to cover a whole 1600-block link). Returns null if any stretch has no path.
     */
    @Nullable
    private static List<double[]> route(Job job, Heights fine, ChunkGenerator generator, RandomState random,
                                        LevelHeightAccessor heightAccessor, int[] expansions) {
        double dx = job.bx() - job.ax(), dz = job.bz() - job.az();
        if (Math.sqrt(dx * dx + dz * dz) <= DIRECT_LIMIT) {
            return segment(job, fine, job.ax(), job.az(), job.bx(), job.bz(), expansions);
        }
        Heights coarse = new Heights(generator, random, heightAccessor, job.seaLevel(), COARSE_CELL, job.maxBridge(), job.avoid());
        List<double[]> corridor = astar(job, coarse, COARSE_CELL, job.ax(), job.az(), job.bx(), job.bz(), 8,
                job.maxExpansions(), expansions);
        expansions[1] += coarse.samples;
        if (corridor == null) return null;
        // waypoints along the corridor, every WAYPOINT_STEP coarse cells (always ending at the goal)
        List<int[]> waypoints = new ArrayList<>();
        waypoints.add(new int[]{job.ax(), job.az()});
        for (int i = WAYPOINT_STEP; i < corridor.size() - WAYPOINT_STEP / 2; i += WAYPOINT_STEP) {
            double[] p = corridor.get(i);
            waypoints.add(new int[]{(int) Math.floor(p[0]), (int) Math.floor(p[1])});
        }
        waypoints.add(new int[]{job.bx(), job.bz()});
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new Aborted();
            int[] from = waypoints.get(i), to = waypoints.get(i + 1);
            List<double[]> part = segment(job, fine, from[0], from[1], to[0], to[1], expansions);
            if (part == null) return null;
            if (!out.isEmpty()) part.remove(0); // shared waypoint
            out.addAll(part);
            Thread.yield(); // a low-priority background thread; let the server breathe between stretches
        }
        return out;
    }

    /** One fine-grid A*, retried once with a wider search corridor. */
    @Nullable
    private static List<double[]> segment(Job job, Heights fine, int ax, int az, int bx, int bz, int[] expansions) {
        List<double[]> p = astar(job, fine, CELL, ax, az, bx, bz, 16, job.maxExpansions(), expansions);
        if (p == null) p = astar(job, fine, CELL, ax, az, bx, bz, 40, job.maxExpansions(), expansions);
        return p;
    }

    // ------------------------------------------------------------------ A*

    @Nullable
    private static List<double[]> astar(Job job, Heights heights, int cell, int ax, int az, int bx, int bz, int minMargin,
                                        int budget, int[] expansionsOut) {
        int sx = Math.floorDiv(ax, cell), sz = Math.floorDiv(az, cell);
        int gx = Math.floorDiv(bx, cell), gz = Math.floorDiv(bz, cell);
        int span = Math.max(Math.abs(gx - sx), Math.abs(gz - sz));
        int margin = Math.max(minMargin, span / 2);
        int minX = Math.min(sx, gx) - margin, maxX = Math.max(sx, gx) + margin;
        int minZ = Math.min(sz, gz) - margin, maxZ = Math.max(sz, gz) + margin;
        int sea = job.seaLevel();
        boolean fineGrid = cell == CELL;
        // a cliff is a rise steeper than 1:1 between neighbouring cells, whatever the grid size
        int cliff = fineGrid ? CLIFF : (int) Math.round(cell * 0.5);
        double cliffCost = CLIFF_COST * cell / CELL;

        Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>((p, q) -> Double.compare(p.f(), q.f()));
        Node start = new Node(sx, sz);
        start.g = 0;
        nodes.put(ChunkPos.asLong(sx, sz), start);
        open.add(new Open(heuristic(sx, sz, gx, gz, cell), 0, start));
        int expansions = 0;

        try {
            while (!open.isEmpty()) {
                Open entry = open.poll();
                Node cur = entry.node();
                if (cur.closed || entry.g() > cur.g + 1e-6) continue;
                cur.closed = true;
                if (cur.x == gx && cur.z == gz) {
                    List<double[]> path = reconstruct(cur, cell, ax, az, bx, bz);
                    return fineGrid ? straighten(path, heights, cell, job.roadCells()) : path;
                }
                if (++expansions > budget) break;
                if ((expansions & 1023) == 0 && Thread.currentThread().isInterrupted()) throw new Aborted();

                int hc = heights.effective(cur.x, cur.z);
                for (int d = 0; d < 8; d++) {
                    int nx = cur.x + DX[d], nz = cur.z + DZ[d];
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                    long key = ChunkPos.asLong(nx, nz);
                    Node nb = nodes.get(key);
                    if (nb != null && nb.closed) continue;

                    double len = ((d & 1) == 0 ? 1.0 : SQRT2) * cell;
                    Cell c = heights.cell(nx, nz);
                    if (c.blocked && !(nx == gx && nz == gz)) continue;
                    int raw = c.raw;
                    int hn = Math.max(raw, c.water);
                    int rise = Math.abs(hn - hc);
                    double grade = rise / len;
                    double cost = len * (1.0 + SLOPE_K * grade * grade);
                    if (rise > cliff) cost += cliffCost;
                    if (c.water != Integer.MIN_VALUE) {
                        // a long bridge (the Talos Bridge) runs straight across; ordinary ones take the narrowest spot
                        double w = job.maxBridge() > RoadGraph.DEFAULT_BRIDGE ? 3.0 : c.water - raw > 6 ? DEEP_WATER : SHALLOW_WATER;
                        cost += len * w;
                    } else {
                        cost *= 1.0 - 0.4 * c.road;
                        if (fineGrid && job.roadCells().contains(key)) cost *= ROAD_DISCOUNT;
                    }
                    cost += len * c.penalty;
                    if (cur.dir >= 0 && cur.dir != d) {
                        int turn = Math.abs(cur.dir - d);
                        cost += TURN_COST * Math.min(turn, 8 - turn);
                    }
                    double g = cur.g + cost;
                    if (nb == null) {
                        nb = new Node(nx, nz);
                        nodes.put(key, nb);
                    } else if (g >= nb.g) {
                        continue;
                    }
                    nb.g = g;
                    nb.parent = cur;
                    nb.dir = d;
                    open.add(new Open(g + heuristic(nx, nz, gx, gz, cell), g, nb));
                }
            }
            return null;
        } finally {
            expansionsOut[0] += expansions;
        }
    }

    /** Octile distance in blocks, slightly inflated (weighted A*: much faster, near-optimal). */
    private static double heuristic(int x, int z, int gx, int gz, int cell) {
        int dx = Math.abs(gx - x), dz = Math.abs(gz - z);
        double octile = Math.max(dx, dz) + (SQRT2 - 1.0) * Math.min(dx, dz);
        return octile * cell * HEURISTIC_WEIGHT;
    }

    /** Look-ahead (in path points) when straightening. */
    private static final int STRAIGHTEN_LOOK = 28;
    /** Steepest rise (blocks) between neighbouring samples on a straightened stretch. */
    private static final int STRAIGHT_RISE = 2;

    /**
     * Any-angle smoothing of a grid path: each point is joined straight to the farthest later point the road can reach
     * in a line over gentle, dry, already-sampled ground (no new height samples). The octilinear zigzag of the grid
     * becomes the long straight runs and gentle bends of a real road, while the A* still decides where it goes.
     */
    private static List<double[]> straighten(List<double[]> path, Heights heights, int cell, LongOpenHashSet roadCells) {
        int n = path.size();
        if (n < 3) return path;
        List<double[]> out = new ArrayList<>();
        out.add(path.get(0));
        int i = 0;
        while (i < n - 1) {
            int next = i + 1;
            // where the path follows an existing road it keeps to it exactly (the roads merge into one)
            boolean onRoad = roadCells.contains(cellOf(path.get(i), cell)) || roadCells.contains(cellOf(path.get(next), cell));
            for (int j = Math.min(n - 1, i + STRAIGHTEN_LOOK); j > i + 1 && !onRoad; j--) {
                if (clearLine(path.get(i), path.get(j), heights, cell)) {
                    next = j;
                    break;
                }
            }
            out.add(path.get(next));
            i = next;
        }
        return out;
    }

    private static long cellOf(double[] p, int cell) {
        return ChunkPos.asLong(Math.floorDiv((int) Math.floor(p[0]), cell), Math.floorDiv((int) Math.floor(p[1]), cell));
    }

    private static boolean clearLine(double[] a, double[] b, Heights heights, int cell) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double len = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / (cell * 0.5)));
        int last = Integer.MIN_VALUE;
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            int cx = Math.floorDiv((int) Math.floor(a[0] + dx * t), cell), cz = Math.floorDiv((int) Math.floor(a[1] + dz * t), cell);
            Cell c = heights.cache.get(ChunkPos.asLong(cx, cz));
            if (c == null || c.blocked || c.penalty > 0 || c.water != Integer.MIN_VALUE) return false;
            if (last != Integer.MIN_VALUE && Math.abs(c.raw - last) > STRAIGHT_RISE) return false;
            last = c.raw;
        }
        return true;
    }

    private static List<double[]> reconstruct(Node goal, int cell, int ax, int az, int bx, int bz) {
        List<double[]> out = new ArrayList<>();
        for (Node n = goal; n != null; n = n.parent) {
            out.add(new double[]{n.x * cell + cell / 2.0, n.z * cell + cell / 2.0});
        }
        Collections.reverse(out);
        out.set(0, new double[]{ax + 0.5, az + 0.5});
        if (out.size() == 1) out.add(new double[]{bx + 0.5, bz + 0.5});
        else out.set(out.size() - 1, new double[]{bx + 0.5, bz + 0.5});
        return out;
    }

    // ------------------------------------------------------------------ smoothing

    /** One round of Chaikin corner cutting; endpoints are kept. */
    static List<double[]> chaikin(List<double[]> p) {
        if (p.size() < 3) return p;
        List<double[]> out = new ArrayList<>(p.size() * 2);
        out.add(p.get(0));
        for (int i = 0; i < p.size() - 1; i++) {
            double[] a = p.get(i), b = p.get(i + 1);
            out.add(new double[]{a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25});
            out.add(new double[]{a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75});
        }
        out.add(p.get(p.size() - 1));
        return out;
    }

    /** Resamples the polyline to block columns {@link #SPACING} blocks apart. */
    static void densify(List<double[]> poly, IntArrayList xs, IntArrayList zs) {
        add(xs, zs, poly.get(0)[0], poly.get(0)[1]);
        double carry = 0;
        for (int i = 0; i < poly.size() - 1; i++) {
            double[] a = poly.get(i), b = poly.get(i + 1);
            double dx = b[0] - a[0], dz = b[1] - a[1];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-9) continue;
            double t = SPACING - carry;
            while (t <= len) {
                add(xs, zs, a[0] + dx * t / len, a[1] + dz * t / len);
                t += SPACING;
            }
            carry = len - (t - SPACING);
        }
        double[] last = poly.get(poly.size() - 1);
        add(xs, zs, last[0], last[1]);
        if (xs.size() == 1) {
            xs.add(xs.getInt(0));
            zs.add(zs.getInt(0));
        }
    }

    private static void add(IntArrayList xs, IntArrayList zs, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        int n = xs.size();
        if (n > 0 && xs.getInt(n - 1) == ix && zs.getInt(n - 1) == iz) return;
        xs.add(ix);
        zs.add(iz);
    }

    /** Marks water points and the ones inside crossings too long to bridge. */
    private static byte[] waterFlags(IntArrayList xs, IntArrayList zs, Heights heights, int longWaterPoints) {
        int n = xs.size();
        byte[] flags = new byte[n];
        for (int i = 0; i < n; i++) {
            if (heights.water(Math.floorDiv(xs.getInt(i), CELL), Math.floorDiv(zs.getInt(i), CELL))) flags[i] = Road.WATER;
        }
        int i = 0;
        while (i < n) {
            if ((flags[i] & Road.WATER) == 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && (flags[j] & Road.WATER) != 0) j++;
            if (j - i > longWaterPoints) {
                for (int k = i; k < j; k++) flags[k] |= Road.LONG_WATER;
            }
            i = j;
        }
        return flags;
    }
}
