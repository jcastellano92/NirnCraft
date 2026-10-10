package com.skycraft.dungeons.world;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Site lists for {@link SitesPlacement}: fixed block positions, one structure start each, for landmarks too long or
 * too big for one start (a start's pieces must stay within about eight chunks of it).
 *
 * <ul>
 *     <li>{@code seven_thousand_steps}: waypoints every {@value #STEP} blocks along the pilgrims' path from Ivarstead
 *     up the Throat of the World to High Hrothgar; start {@code i} paves the stretch to waypoint {@code i + 1}.</li>
 *     <li>{@code throat_path}: the last climb from High Hrothgar's back gate to the summit.</li>
 *     <li>{@code blackreach}: the tiles of the great cavern under the Dwemer cities ({@link Blackreach}).</li>
 * </ul>
 * Everything is computed once from constants, identically on every thread and every seed.
 */
public final class Sites {
    /** Spacing of path waypoints: more than a chunk diagonal, so no chunk holds two. */
    public static final int STEP = 30;

    private record List2(int[][] points, Map<Long, Integer> byChunk, boolean path) {
    }

    private static final Map<String, List2> LISTS = new HashMap<>();

    static {
        // Ivarstead's bridge, up the south-east shoulder, round the east face and across to the monastery
        path("seven_thousand_steps", new int[][]{
                {884, 892}, {862, 860}, {842, 818}, {806, 790}, {790, 742}, {752, 712}, {742, 668}, {716, 626},
                {722, 584}, {690, 548}, {664, 512}, {624, 486}, {584, 466}, {552, 458}});
        // the monastery's back gate (its south courtyard) to the summit
        path("throat_path", new int[][]{{506, 494}, {520, 524}, {548, 548}, {546, 584}, {566, 612}, {574, 640}});
        tiles("blackreach", Blackreach.tiles());
    }

    private Sites() {}

    private static void path(String id, int[][] control) {
        List<int[]> pts = new ArrayList<>();
        pts.add(control[0]);
        double carry = 0;
        for (int i = 0; i + 1 < control.length; i++) {
            double ax = control[i][0], az = control[i][1], bx = control[i + 1][0], bz = control[i + 1][1];
            double len = Math.hypot(bx - ax, bz - az);
            double d = STEP - carry;
            while (d <= len) {
                pts.add(new int[]{(int) Math.round(ax + (bx - ax) * d / len), (int) Math.round(az + (bz - az) * d / len)});
                d += STEP;
            }
            carry = len - (d - STEP);
        }
        int[] last = control[control.length - 1];
        int[] tail = pts.get(pts.size() - 1);
        if (Math.hypot(last[0] - tail[0], last[1] - tail[1]) > 6) pts.add(last);
        int[][] arr = pts.toArray(new int[0][]);
        Map<Long, Integer> byChunk = new HashMap<>();
        // every waypoint but the last starts a stretch
        for (int i = 0; i + 1 < arr.length; i++) byChunk.putIfAbsent(ChunkPos.asLong(arr[i][0] >> 4, arr[i][1] >> 4), i);
        LISTS.put(id, new List2(arr, byChunk, true));
    }

    private static void tiles(String id, int[][] tileOrigins) {
        Map<Long, Integer> byChunk = new HashMap<>();
        for (int i = 0; i < tileOrigins.length; i++) byChunk.put(ChunkPos.asLong(tileOrigins[i][0] >> 4, tileOrigins[i][1] >> 4), i);
        LISTS.put(id, new List2(tileOrigins, byChunk, false));
    }

    /** Index of the site starting in chunk (cx, cz), or -1. */
    public static int indexAt(String id, int cx, int cz) {
        List2 l = LISTS.get(id);
        if (l == null) return -1;
        return l.byChunk().getOrDefault(ChunkPos.asLong(cx, cz), -1);
    }

    public static int[] point(String id, int i) {
        return LISTS.get(id).points()[i];
    }

    public static int count(String id) {
        List2 l = LISTS.get(id);
        return l == null ? 0 : l.points().length;
    }

    public static boolean exists(String id) {
        return LISTS.containsKey(id);
    }
}
