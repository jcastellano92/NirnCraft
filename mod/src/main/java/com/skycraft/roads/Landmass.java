package com.skycraft.roads;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;

/**
 * Which landmass of the Tamriel map a column is on: a flood fill of the map raster's land cells (32 blocks), where
 * straits narrow enough to bridge count as land. Roads never cross open sea, so the road graph, the carriages and the
 * generic-town links only join places on the same landmass. Built once (about a second, on the road planner thread
 * or whoever asks first) and immutable afterwards, so reads are thread-safe. Outside the raster (the foreign and
 * generated lands) the answer is {@link #UNKNOWN}.
 */
public final class Landmass {
    public static final int UNKNOWN = -1;
    public static final int SEA = 0;
    /** Sea up to this far from the coast counts as land, so straits up to about twice this wide join two shores. */
    private static final float STRAIT = 24f;
    private static final int W = TamrielMap.WIDTH_CELLS, H = TamrielMap.HEIGHT_CELLS, CELL = TamrielMap.CELL;

    private Landmass() {}

    private static final class Holder {
        static final short[] LABELS = build();
        static int count;
    }

    private static short[] build() {
        long t0 = System.nanoTime();
        short[] labels = new short[W * H];
        boolean[] land = new boolean[W * H];
        MapSample s = new MapSample();
        for (int cz = 0; cz < H; cz++) {
            for (int cx = 0; cx < W; cx++) {
                TamrielMap.sample(TamrielMap.MIN_X + cx * CELL + CELL / 2, TamrielMap.MIN_Z + cz * CELL + CELL / 2, s);
                land[cz * W + cx] = s.region != 0 && s.coastDistance > -STRAIT;
            }
        }
        int[] queue = new int[W * H];
        short next = 1;
        for (int start = 0; start < W * H; start++) {
            if (!land[start] || labels[start] != 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            labels[start] = next;
            while (head < tail) {
                int c = queue[head++];
                int x = c % W, z = c / W;
                for (int d = 0; d < 4; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx < 0 || nz < 0 || nx >= W || nz >= H) continue;
                    int n = nz * W + nx;
                    if (!land[n] || labels[n] != 0) continue;
                    labels[n] = next;
                    queue[tail++] = n;
                }
            }
            if (next < Short.MAX_VALUE) next++;
        }
        Holder.count = next - 1;
        Skycraft.LOGGER.info("Skycraft roads: {} landmasses on the Tamriel map ({} ms)", next - 1, (System.nanoTime() - t0) / 1_000_000);
        return labels;
    }

    /** Forces the flood fill (call from a background thread to keep it off the server thread). */
    public static void warm() {
        if (Holder.LABELS.length == 0) throw new IllegalStateException();
    }

    public static int count() {
        warm();
        return Holder.count;
    }

    /** The landmass at block column (x, z): {@link #SEA}, {@link #UNKNOWN} outside the map, else its id (1+). */
    public static int at(int x, int z) {
        if (!TamrielMap.inTamriel(x, z)) return UNKNOWN;
        int cx = Math.floorDiv(x - TamrielMap.MIN_X, CELL), cz = Math.floorDiv(z - TamrielMap.MIN_Z, CELL);
        return Holder.LABELS[cz * W + cx];
    }

    /**
     * The landmass of a place: its own cell, or the nearest land cell within {@code searchBlocks} (coastal towns whose
     * anchor sits on the shore line). {@link #SEA} if there is none.
     */
    public static int near(int x, int z, int searchBlocks) {
        int own = at(x, z);
        if (own != SEA) return own;
        int r = Math.max(1, searchBlocks / CELL);
        int best = SEA;
        long bestD = Long.MAX_VALUE;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int l = at(x + dx * CELL, z + dz * CELL);
                if (l <= SEA) continue;
                long d = (long) dx * dx + (long) dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = l;
                }
            }
        }
        return best;
    }

    /** True if two columns can be joined by road (same landmass; anything outside the map is assumed reachable). */
    public static boolean connected(int x0, int z0, int x1, int z1) {
        int a = near(x0, z0, 96), b = near(x1, z1, 96);
        if (a == UNKNOWN || b == UNKNOWN) return true;
        return a != SEA && a == b;
    }
}
