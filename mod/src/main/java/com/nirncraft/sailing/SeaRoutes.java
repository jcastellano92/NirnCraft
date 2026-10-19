package com.nirncraft.sailing;

import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.TamrielMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The paths ships take between ports: A* over a coarse sea grid of the whole Nirn map (Tamriel's seas and the oceans
 * out to the foreign lands), smoothed by line of sight. Used for fares and voyage time on the server and for the sea
 * routes drawn on the map. Computed once in a background thread; until it is ready, callers get a straight-line
 * estimate. Thread-safe, both sides.
 */
public final class SeaRoutes {
    private static final int CELL = 64;
    private static final int MIN_X = -23040, MIN_Z = -13312;
    private static final int W = 780, H = 568; // out to x = 26880: Akavir and the Tang Mo isles

    /** A route's path in block coordinates and its length in blocks. */
    public record Path(float[] xs, float[] zs, float length) {
    }

    private static volatile boolean[] sea;
    private static volatile boolean charted;
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final Map<String, Path> PATHS = new ConcurrentHashMap<>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NirnCraft sea routes");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private SeaRoutes() {}

    private static volatile boolean sited;

    /** True once every port's site is known (cheap to ask for from then on). */
    public static boolean sitesReady() {
        warmUp();
        return sited;
    }

    /** True once every route is charted. */
    public static boolean charted() {
        return charted;
    }

    /** Starts computing the port sites, the sea grid and every route (idempotent). */
    public static void warmUp() {
        if (!STARTED.compareAndSet(false, true)) return;
        EXEC.execute(() -> {
            try {
                long t0 = System.nanoTime();
                for (Port p : Ports.all()) PortSites.of(p);
                sited = true;
                boolean[] grid = new boolean[W * H];
                com.nirncraft.tamriel.MapSample s = new com.nirncraft.tamriel.MapSample();
                for (int z = 0; z < H; z++) {
                    for (int x = 0; x < W; x++) {
                        boolean isSea = false;
                        int bx = MIN_X + x * CELL, bz = MIN_Z + z * CELL;
                        for (int dz = 16; dz <= 48 && !isSea; dz += 32) {
                            for (int dx = 16; dx <= 48 && !isSea; dx += 32) {
                                TamrielMap.sample(bx + dx, bz + dz, s);
                                if (s.region == 0 || s.coastDistance <= 8f || s.elevation < 62f) isSea = true;
                            }
                        }
                        grid[z * W + x] = isSea;
                    }
                }
                keepMainOcean(grid);
                sea = grid;
                for (Route r : Ports.routes()) {
                    Path p = compute(r);
                    if (p != null) PATHS.put(r.key(), p);
                }
                charted = true;
                NirnCraft.LOGGER.info("Charted {} sea routes in {} ms", PATHS.size(), (System.nanoTime() - t0) / 1_000_000);
            } catch (RuntimeException e) {
                NirnCraft.LOGGER.warn("Could not chart the sea routes", e);
            }
        });
    }

    /** The charted path of a route oriented from {@code fromPort}, or null while the charts are not ready. */
    @Nullable
    public static Path path(Route route, String fromPort) {
        warmUp();
        Path p = PATHS.get(route.key());
        if (p == null) return null;
        if (route.key().startsWith(fromPort + ">")) return p;
        return reversed(p);
    }

    /** Sailing distance of a route in blocks: the charted path, or the straight line plus a quarter until charted. */
    public static float length(Route route) {
        warmUp();
        Path p = PATHS.get(route.key());
        if (p != null) return p.length();
        Port a = Ports.byId(route.a()).orElse(null), b = Ports.byId(route.b()).orElse(null);
        if (a == null || b == null) return 1000f;
        PortSites.Site sa = PortSites.of(a), sb = PortSites.of(b);
        double dx = sa.waterX() - sb.waterX(), dz = sa.waterZ() - sb.waterZ();
        return (float) (Math.sqrt(dx * dx + dz * dz) * 1.25);
    }

    private static Path reversed(Path p) {
        int n = p.xs().length;
        float[] xs = new float[n], zs = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = p.xs()[n - 1 - i];
            zs[i] = p.zs()[n - 1 - i];
        }
        return new Path(xs, zs, p.length());
    }

    // ------------------------------------------------------------------ A*

    private static Path compute(Route r) {
        Port a = Ports.byId(r.a()).orElse(null), b = Ports.byId(r.b()).orElse(null);
        if (a == null || b == null) return null;
        PortSites.Site sa = PortSites.of(a), sb = PortSites.of(b);
        int start = nearestSeaCell(sa.waterX(), sa.waterZ());
        int goal = nearestSeaCell(sb.waterX(), sb.waterZ());
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{sa.waterX(), sa.waterZ()});
        int[] cells = start >= 0 && goal >= 0 ? astar(start, goal) : null;
        if (cells != null) {
            for (int c : smooth(cells)) pts.add(new float[]{MIN_X + (c % W) * CELL + CELL / 2f, MIN_Z + (c / W) * CELL + CELL / 2f});
        } else {
            NirnCraft.LOGGER.warn("Sea route {} - {} has no open-water path (straight line used)", r.a(), r.b());
        }
        pts.add(new float[]{sb.waterX(), sb.waterZ()});
        float[] xs = new float[pts.size()], zs = new float[pts.size()];
        float len = 0;
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i)[0];
            zs[i] = pts.get(i)[1];
            if (i > 0) len += (float) Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        return new Path(xs, zs, len);
    }

    private static int cellOf(double x, double z) {
        int cx = (int) Math.floor((x - MIN_X) / CELL), cz = (int) Math.floor((z - MIN_Z) / CELL);
        if (cx < 0 || cz < 0 || cx >= W || cz >= H) return -1;
        return cz * W + cx;
    }

    private static int nearestSeaCell(int x, int z) {
        boolean[] grid = sea;
        int c = cellOf(x, z);
        if (c < 0) return -1;
        int cx = c % W, cz = c / W;
        for (int r = 0; r <= 24; r++) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x2 = cx + dx, z2 = cz + dz;
                    if (x2 < 0 || z2 < 0 || x2 >= W || z2 >= H || !grid[z2 * W + x2]) continue;
                    double d = dx * dx + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = z2 * W + x2;
                    }
                }
            }
            if (best >= 0) return best;
        }
        return -1;
    }

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private static void keepMainOcean(boolean[] grid) {
        int n = W * H;
        int[] comp = new int[n];
        int[] queue = new int[n];
        int nextId = 0, bestId = -1, bestSize = 0;
        for (int i = 0; i < n; i++) {
            if (!grid[i] || comp[i] != 0) continue;
            int id = ++nextId;
            int head = 0, tail = 0;
            comp[i] = id;
            queue[tail++] = i;
            while (head < tail) {
                int c = queue[head++];
                int cx = c % W, cz = c / W;
                for (int k = 0; k < 8; k++) {
                    int nx = cx + DX[k], nz = cz + DZ[k];
                    if (nx < 0 || nz < 0 || nx >= W || nz >= H) continue;
                    int nb = nz * W + nx;
                    if (!grid[nb] || comp[nb] != 0) continue;
                    if (k >= 4 && (!grid[cz * W + nx] || !grid[nz * W + cx])) continue;
                    comp[nb] = id;
                    queue[tail++] = nb;
                }
            }
            if (tail > bestSize) {
                bestSize = tail;
                bestId = id;
            }
        }
        if (bestId > 0) {
            for (int i = 0; i < n; i++) if (grid[i] && comp[i] != bestId) grid[i] = false;
        }
    }

    private static int[] astar(int start, int goal) {
        boolean[] grid = sea;
        float[] g = new float[W * H];
        Arrays.fill(g, Float.MAX_VALUE);
        int[] from = new int[W * H];
        Arrays.fill(from, -1);
        boolean[] closed = new boolean[W * H];
        Heap open = new Heap(W * H);
        int gx = goal % W, gz = goal / W;
        g[start] = 0;
        open.push(start, heuristic(start % W, start / W, gx, gz));
        while (!open.isEmpty()) {
            int c = open.pop();
            if (c == goal) break;
            if (closed[c]) continue;
            closed[c] = true;
            int cx = c % W, cz = c / W;
            for (int k = 0; k < 8; k++) {
                int nx = cx + DX[k], nz = cz + DZ[k];
                if (nx < 0 || nz < 0 || nx >= W || nz >= H) continue;
                int n = nz * W + nx;
                if (!grid[n] || closed[n]) continue;
                if (k >= 4 && (!grid[cz * W + nx] || !grid[nz * W + cx])) continue; // no corner cutting
                float step = k >= 4 ? 1.4142f : 1f;
                if (nearLand(grid, nx, nz)) step += 0.6f; // keep off the coast where there is room
                float ng = g[c] + step;
                if (ng < g[n]) {
                    g[n] = ng;
                    from[n] = c;
                    open.push(n, ng + heuristic(nx, nz, gx, gz));
                }
            }
        }
        if (from[goal] < 0 && goal != start) return null;
        List<Integer> path = new ArrayList<>();
        for (int c = goal; c >= 0; c = from[c]) {
            path.add(c);
            if (c == start) break;
        }
        int[] out = new int[path.size()];
        for (int i = 0; i < out.length; i++) out[i] = path.get(out.length - 1 - i);
        return out;
    }

    private static boolean nearLand(boolean[] grid, int x, int z) {
        for (int k = 0; k < 4; k++) {
            int nx = x + DX[k], nz = z + DZ[k];
            if (nx >= 0 && nz >= 0 && nx < W && nz < H && !grid[nz * W + nx]) return true;
        }
        return false;
    }

    private static float heuristic(int x, int z, int gx, int gz) {
        int dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        return Math.max(dx, dz) + 0.4142f * Math.min(dx, dz);
    }

    /** Keeps only the corners: from each kept cell, jump to the farthest cell still in clear line of sight. */
    private static List<Integer> smooth(int[] cells) {
        List<Integer> out = new ArrayList<>();
        int i = 0;
        out.add(cells[0]);
        while (i < cells.length - 1) {
            int j = cells.length - 1;
            while (j > i + 1 && !lineOfSight(cells[i], cells[j])) j--;
            out.add(cells[j]);
            i = j;
        }
        return out;
    }

    private static boolean lineOfSight(int a, int b) {
        boolean[] grid = sea;
        double ax = a % W + 0.5, az = a / W + 0.5, bx = b % W + 0.5, bz = b / W + 0.5;
        double len = Math.hypot(bx - ax, bz - az);
        int steps = (int) Math.ceil(len * 3);
        for (int s = 1; s < steps; s++) {
            double t = s / (double) steps;
            int x = (int) Math.floor(ax + (bx - ax) * t), z = (int) Math.floor(az + (bz - az) * t);
            if (!grid[z * W + x]) return false;
        }
        return true;
    }

    /** Minimal binary min-heap of cell indices keyed by a float priority (duplicates allowed, stale ones skipped). */
    private static final class Heap {
        private int[] items;
        private float[] keys;
        private int size;

        Heap(int capacity) {
            items = new int[Math.min(capacity, 1 << 16)];
            keys = new float[items.length];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(int item, float key) {
            if (size == items.length) {
                items = Arrays.copyOf(items, size * 2);
                keys = Arrays.copyOf(keys, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (keys[p] <= key) break;
                items[i] = items[p];
                keys[i] = keys[p];
                i = p;
            }
            items[i] = item;
            keys[i] = key;
        }

        int pop() {
            int top = items[0];
            int last = items[--size];
            float lastKey = keys[size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int c = l + 1 < size && keys[l + 1] < keys[l] ? l + 1 : l;
                if (keys[c] >= lastKey) break;
                items[i] = items[c];
                keys[i] = keys[c];
                i = c;
            }
            items[i] = last;
            keys[i] = lastKey;
            return top;
        }
    }
}
