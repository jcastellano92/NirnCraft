package com.nirncraft.roads;

import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.TamrielMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which landmass of the Tamriel map a column is on: a flood fill of the map raster's land cells (32 blocks), where
 * straits narrow enough to bridge count as land. Roads never cross open sea, so the road graph, the carriages and the
 * generic-town links only join places on the same landmass. Built once (about a second, on the road planner thread
 * or whoever asks first) and immutable afterwards, so reads are thread-safe.
 *
 * <p>Outside the raster (the foreign and generated lands) islands are labelled the same way, lazily around the
 * off-raster places: a flood fill of the {@link TamrielMap#sample} land test on the same 32-block cells, bounded to
 * {@link #ISLAND_RADIUS} blocks around each place. Ids continue above the raster's, places on one island share an id,
 * and a flood that reaches a raster landmass joins it. Off-raster columns on no labelled island stay
 * {@link #UNKNOWN}.</p>
 */
public final class Landmass {
    public static final int UNKNOWN = -1;
    public static final int SEA = 0;
    /** Sea up to this far from the coast counts as land, so straits up to about twice this wide join two shores. */
    private static final float STRAIT = 24f;
    private static final int W = TamrielMap.WIDTH_CELLS, H = TamrielMap.HEIGHT_CELLS, CELL = TamrielMap.CELL;
    /** How far (blocks) from an off-raster place a flood fill spreads. */
    private static final int ISLAND_RADIUS = 3000;
    /** A place anchored on a shore cell of sea: land within this many blocks counts as its island. */
    private static final int SEED_SEARCH = 160;

    private Landmass() {}

    private static final class Holder {
        static int count;
        static int offCount;
        static final short[] LABELS;
        static final Long2ShortOpenHashMap OFF;

        static {
            short[] l = null;
            Long2ShortOpenHashMap o = null;
            byte[] baked = com.nirncraft.bake.Bake.load("landmass");
            if (baked != null) {
                try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(baked))) {
                    count = in.readInt();
                    offCount = in.readInt();
                    l = new short[in.readInt()];
                    for (int i = 0; i < l.length; i++) l[i] = in.readShort();
                    int n = in.readInt();
                    o = new Long2ShortOpenHashMap(n);
                    for (int i = 0; i < n; i++) {
                        long k = in.readLong();
                        o.put(k, in.readShort());
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    l = null;
                    o = null;
                }
            }
            if (l == null || l.length != W * H) {
                l = build();
                o = buildOff(count, l);
            }
            LABELS = l;
            OFF = o;
        }
    }

    /** The landmass labels computed from scratch, serialised for {@code com.nirncraft.bake.Bake}. */
    public static byte[] computeLive() throws java.io.IOException {
        short[] l = build();
        int c = Holder.count;
        Long2ShortOpenHashMap o = buildOff(c, l);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bo)) {
            out.writeInt(c);
            out.writeInt(Holder.offCount);
            out.writeInt(l.length);
            for (short v : l) out.writeShort(v);
            out.writeInt(o.size());
            var it = o.long2ShortEntrySet().fastIterator();
            while (it.hasNext()) {
                var e = it.next();
                out.writeLong(e.getLongKey());
                out.writeShort(e.getShortValue());
            }
        }
        return bo.toByteArray();
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
        NirnCraft.LOGGER.info("NirnCraft roads: {} landmasses on the Tamriel map ({} ms)", next - 1, (System.nanoTime() - t0) / 1_000_000);
        return labels;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    private static int find(IntArrayList parent, int a) {
        while (parent.getInt(a) != a) {
            parent.set(a, parent.getInt(parent.getInt(a)));
            a = parent.getInt(a);
        }
        return a;
    }

    /** Land test of an off-raster cell (cached): the raster's rule, so bridgeable straits count as land. */
    private static boolean offLand(Long2ByteOpenHashMap memo, MapSample s, int cx, int cz) {
        long k = key(cx, cz);
        byte v = memo.get(k);
        if (v == 0) {
            TamrielMap.sample(cx * CELL + CELL / 2, cz * CELL + CELL / 2, s);
            v = (byte) (s.coastDistance > -STRAIT ? 1 : 2); // NaN compares false: sea
            memo.put(k, v);
        }
        return v == 1;
    }

    /** Off-raster islands: cell key to island id (above the raster's ids), around every off-raster place. */
    private static Long2ShortOpenHashMap buildOff(int rasterCount, short[] raster) {
        long t0 = System.nanoTime();
        Long2ShortOpenHashMap labels = new Long2ShortOpenHashMap();
        List<int[]> seeds = new ArrayList<>();
        for (Place p : Places.all()) if (!TamrielMap.inTamriel(p.x(), p.z())) seeds.add(new int[]{p.x(), p.z()});
        if (seeds.isEmpty()) return labels;
        Long2ByteOpenHashMap memo = new Long2ByteOpenHashMap();
        MapSample s = new MapSample();
        IntArrayList parent = new IntArrayList();
        for (int i = 0; i <= rasterCount; i++) parent.add(i);
        int rr = ISLAND_RADIUS / CELL, sr = Math.max(1, SEED_SEARCH / CELL);
        int cells = 0;
        IntArrayList queue = new IntArrayList();
        for (int[] seed : seeds) {
            int sx = Math.floorDiv(seed[0], CELL), sz = Math.floorDiv(seed[1], CELL);
            int bx = 0, bz = 0;
            long bestD = Long.MAX_VALUE;
            for (int dz = -sr; dz <= sr; dz++) {
                for (int dx = -sr; dx <= sr; dx++) {
                    long d = (long) dx * dx + (long) dz * dz;
                    if (d >= bestD || !offLand(memo, s, sx + dx, sz + dz)) continue;
                    bestD = d;
                    bx = sx + dx;
                    bz = sz + dz;
                }
            }
            if (bestD == Long.MAX_VALUE || labels.containsKey(key(bx, bz))) continue;
            int id = parent.size();
            if (id >= Short.MAX_VALUE) break;
            parent.add(id);
            labels.put(key(bx, bz), (short) id);
            queue.clear();
            queue.add(bx);
            queue.add(bz);
            for (int head = 0; head < queue.size(); head += 2) {
                int x = queue.getInt(head), z = queue.getInt(head + 1);
                for (int d = 0; d < 4; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (Math.abs(nx - bx) > rr || Math.abs(nz - bz) > rr) continue;
                    int px = nx * CELL + CELL / 2, pz = nz * CELL + CELL / 2;
                    int other;
                    if (TamrielMap.inTamriel(px, pz)) {
                        int rx = Math.floorDiv(px - TamrielMap.MIN_X, CELL), rz = Math.floorDiv(pz - TamrielMap.MIN_Z, CELL);
                        other = raster[rz * W + rx];
                    } else {
                        other = labels.get(key(nx, nz));
                        if (other == 0) {
                            if (!offLand(memo, s, nx, nz)) continue;
                            labels.put(key(nx, nz), (short) id);
                            queue.add(nx);
                            queue.add(nz);
                            cells++;
                            continue;
                        }
                    }
                    if (other <= 0) continue;
                    int ra = find(parent, id), rb = find(parent, other);
                    if (ra == rb || (ra <= rasterCount && rb <= rasterCount)) continue;
                    // raster ids stay roots (they are fixed); islands hang under them or under the lower island id
                    if (ra <= rasterCount) parent.set(rb, ra);
                    else if (rb <= rasterCount) parent.set(ra, rb);
                    else if (ra < rb) parent.set(rb, ra);
                    else parent.set(ra, rb);
                }
            }
        }
        Set<Integer> roots = new HashSet<>();
        for (var it = labels.long2ShortEntrySet().fastIterator(); it.hasNext(); ) {
            var e = it.next();
            int r = find(parent, e.getShortValue());
            e.setValue((short) r);
            if (r > rasterCount) roots.add(r);
        }
        Holder.offCount = roots.size();
        NirnCraft.LOGGER.info("NirnCraft roads: {} off-raster islands around {} places ({} cells, {} ms)", roots.size(),
                seeds.size(), cells, (System.nanoTime() - t0) / 1_000_000);
        return labels;
    }

    /** Forces the flood fill (call from a background thread to keep it off the server thread). */
    public static void warm() {
        if (Holder.LABELS.length == 0 || Holder.OFF == null) throw new IllegalStateException();
    }

    public static int count() {
        warm();
        return Holder.count;
    }

    /** Number of labelled off-raster islands (ids above {@link #count()}, not consecutive). */
    public static int offRasterCount() {
        warm();
        return Holder.offCount;
    }

    /**
     * The landmass at block column (x, z): {@link #SEA}, {@link #UNKNOWN} outside the map and any labelled island,
     * else its id (1+).
     */
    public static int at(int x, int z) {
        if (!TamrielMap.inTamriel(x, z)) {
            short v = Holder.OFF.get(key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)));
            return v == 0 ? UNKNOWN : v;
        }
        int cx = Math.floorDiv(x - TamrielMap.MIN_X, CELL), cz = Math.floorDiv(z - TamrielMap.MIN_Z, CELL);
        return Holder.LABELS[cz * W + cx];
    }

    /**
     * The landmass of a place: its own cell, or the nearest land cell within {@code searchBlocks} (coastal towns whose
     * anchor sits on the shore line). {@link #SEA} (or {@link #UNKNOWN} off the map) if there is none.
     */
    public static int near(int x, int z, int searchBlocks) {
        int own = at(x, z);
        if (own > SEA) return own;
        int r = Math.max(1, searchBlocks / CELL);
        int best = own;
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
