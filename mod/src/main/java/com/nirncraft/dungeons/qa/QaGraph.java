package com.nirncraft.dungeons.qa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Where a player can stand and walk in a {@link QaLevel}: every standing spot (a column and the height of the floor
 * under the feet, with room for a 1.8-block body), joined to the spots of the four neighbouring columns by the moves a
 * player makes: walking, auto-stepping slabs and stairs, jumping up one block (with headroom), dropping down (fall damage
 * over 3 blocks), swimming and climbing ladders. Closed iron doors, bars and walls block; wooden doors and gates count as
 * open. Also the block light at every spot.
 */
public final class QaGraph {
    public static final int SWIM = 1, CLIMB = 2, HAZARD = 4, COBWEB = 8, LETHAL = 16;
    /** Fall damage at which a drop counts as lethal for a fresh player (20 health). */
    public static final float LETHAL_FALL = 23f;

    /** Collision and movement properties of one block state. */
    static final class Props {
        float lo, hi; // collision interval (hi < 0: no collision)
        boolean body;  // never blocks a body (no collision, openable, climbable, fluid)
        boolean climb, water, lava, hazard, cobweb, stepUp;
    }

    private final QaLevel lvl;
    public final BoundingBox area;
    private final Map<net.minecraft.world.level.block.state.BlockState, Props> cache = new IdentityHashMap<>();
    private final int sx, sz;

    // nodes
    public int count;
    public int[] nx = new int[4096], nz = new int[4096];
    public float[] ns = new float[4096];
    public byte[] flags = new byte[4096];
    /** First node of each column, and the next node in the same column (by rising height), -1 terminated. */
    private int[] colHead;
    private int[] nextInCol = new int[4096];
    public byte[] light;

    public QaGraph(QaLevel lvl) {
        this.lvl = lvl;
        this.area = lvl.area;
        this.sx = area.getXSpan();
        this.sz = area.getZSpan();
        build();
    }

    // ------------------------------------------------------------------ block properties

    Props props(BlockState s) {
        Props p = cache.get(s);
        if (p != null) return p;
        p = new Props();
        VoxelShape shape = s.getCollisionShape(lvl, BlockPos.ZERO);
        if (shape.isEmpty()) {
            p.lo = 0;
            p.hi = -1;
        } else {
            p.lo = (float) shape.min(Direction.Axis.Y);
            p.hi = (float) shape.max(Direction.Axis.Y);
        }
        p.water = s.getFluidState().is(FluidTags.WATER);
        p.lava = s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.LAVA);
        p.climb = s.is(BlockTags.CLIMBABLE) && !s.is(Blocks.SCAFFOLDING);
        p.cobweb = s.is(Blocks.COBWEB);
        p.hazard = s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.FIRE)
                || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE)
                || (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT));
        boolean openable = (s.getBlock() instanceof DoorBlock && (s.is(BlockTags.WOODEN_DOORS) || s.getValue(DoorBlock.OPEN)))
                || s.getBlock() instanceof FenceGateBlock
                || (s.getBlock() instanceof TrapDoorBlock && (s.is(BlockTags.WOODEN_TRAPDOORS) || s.getValue(TrapDoorBlock.OPEN)));
        p.body = p.hi < 0 || openable || p.climb || (p.water && p.hi < 0);
        if (s.getBlock() instanceof DoorBlock && !openable) p.body = false;
        p.stepUp = (s.getBlock() instanceof StairBlock && s.getValue(StairBlock.HALF) == Half.BOTTOM)
                || (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM);
        cache.put(s, p);
        return p;
    }

    public Props propsAt(int x, int y, int z) {
        return props(lvl.get(x, y, z));
    }

    /** True if nothing at column (x, z) intersects the vertical span [a, b] (block {@code skip} excepted). */
    boolean clear(int x, int z, float a, float b, int skip) {
        for (int k = (int) Math.floor(a); k <= (int) Math.floor(b - 1e-3); k++) {
            if (k == skip) continue;
            Props p = propsAt(x, k, z);
            if (p.lava) return false;
            if (p.body) continue;
            if (k + p.hi > a + 0.01f && k + p.lo < b - 0.01f) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ nodes

    private int col(int x, int z) {
        return (x - area.minX()) * sz + (z - area.minZ());
    }

    public void rebuild() {
        count = 0;
        build();
    }

    private void build() {
        colHead = new int[sx * sz];
        Arrays.fill(colHead, -1);
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                int last = -1;
                for (int y = area.minY(); y < area.maxY() - 1; y++) {
                    BlockState st = lvl.get(x, y, z);
                    Props p = props(st);
                    float s;
                    int f;
                    if (p.water || p.climb) {
                        // swimming or on a ladder: anywhere in the block, if there is room for a head above
                        Props above = propsAt(x, y + 1, z);
                        if (!above.body && !above.water && above.hi >= 0) continue;
                        s = y;
                        f = p.water ? SWIM : CLIMB;
                    } else if (p.hi > 0 && !p.body) {
                        s = y + p.hi;
                        if (s > y + 1.0f && p.hi > 1.0f) continue; // fences and walls: not a floor
                        if (!clear(x, z, s, s + 1.8f, y)) continue;
                        f = 0;
                        if (p.hazard) f |= HAZARD;
                    } else {
                        continue;
                    }
                    // what the feet stand in
                    Props feet = propsAt(x, (int) Math.floor(s + 0.01f), z);
                    if (feet.cobweb) f |= COBWEB;
                    if (feet.hazard) f |= HAZARD;
                    if (last >= 0 && Math.abs(ns[last] - s) < 0.05f) {
                        flags[last] |= (byte) f;
                        continue;
                    }
                    int n = add(x, z, s, f);
                    if (last < 0) colHead[col(x, z)] = n;
                    else nextInCol[last] = n;
                    last = n;
                }
            }
        }
        computeLight();
    }

    private int add(int x, int z, float s, int f) {
        if (count == nx.length) {
            int cap = count * 2;
            nx = Arrays.copyOf(nx, cap);
            nz = Arrays.copyOf(nz, cap);
            ns = Arrays.copyOf(ns, cap);
            flags = Arrays.copyOf(flags, cap);
            nextInCol = Arrays.copyOf(nextInCol, cap);
        }
        nx[count] = x;
        nz[count] = z;
        ns[count] = s;
        flags[count] = (byte) f;
        nextInCol[count] = -1;
        return count++;
    }

    public int first(int x, int z) {
        if (x < area.minX() || x > area.maxX() || z < area.minZ() || z > area.maxZ()) return -1;
        return colHead[col(x, z)];
    }

    public int next(int n) {
        return nextInCol[n];
    }

    /** The feet block of a node. */
    public int feetY(int n) {
        return (int) Math.floor(ns[n] + 0.01f);
    }

    /** The node standing nearest to (x, y, z) in that column, within {@code tol} blocks, or -1. */
    public int nodeAt(int x, int y, int z, float tol) {
        int best = -1;
        float bd = tol;
        for (int n = first(x, z); n >= 0; n = nextInCol[n]) {
            float d = Math.abs(ns[n] - y);
            if (d <= bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ moves

    /** Callback for the moves out of a node: target node, cost in blocks, fall damage. */
    public interface Edge {
        void accept(int to, float cost, float damage);
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public void edges(int a, Edge out) {
        int x = nx[a], z = nz[a];
        float sa = ns[a];
        boolean free = (flags[a] & (SWIM | CLIMB)) != 0;
        // up and down a ladder or through water
        if (free) {
            for (int b = first(x, z); b >= 0; b = nextInCol[b]) {
                if (b == a) continue;
                float d = ns[b] - sa;
                if (Math.abs(d) <= 1.05f && ((flags[b] & (SWIM | CLIMB)) != 0 || d < 0)) {
                    out.accept(b, (flags[a] & SWIM) != 0 ? 1.5f : 1.3f, 0);
                }
            }
        }
        for (int[] d : DIRS) {
            int tx = x + d[0], tz = z + d[1];
            for (int b = first(tx, tz); b >= 0; b = nextInCol[b]) {
                float sb = ns[b];
                float ds = sb - sa;
                if (ds > 0.05f) {
                    boolean ok = ds <= 0.6f
                            || (ds <= 1.05f && propsAt(tx, (int) Math.floor(sb - 0.01f), tz).stepUp)
                            || (ds <= 1.3f && (free || clear(x, z, sa, sa + 1.8f + ds + 0.2f, Integer.MIN_VALUE)));
                    if (!ok) continue;
                    if (!clear(tx, tz, sb, sb + 1.8f, (int) Math.floor(sb - 0.01f))) continue;
                    out.accept(b, 1.0f + ds, 0);
                } else {
                    float fall = -ds;
                    // the body passes down through the target column from the start height
                    if (!clear(tx, tz, sb, sa + 1.8f, (int) Math.floor(sb - 0.01f))) continue;
                    float dmg = (flags[b] & SWIM) != 0 || (flags[b] & CLIMB) != 0 ? 0 : Math.max(0, fall - 3.0f);
                    out.accept(b, 1.0f + fall * 0.3f, dmg);
                }
            }
        }
    }

    // ------------------------------------------------------------------ search

    /**
     * Shortest walking distances (in blocks, with climbing and swimming dearer) from the given start nodes; drops that
     * would deal {@code maxDamage} or more are not taken. Unreached nodes are +inf. {@code prev} (optional) records the
     * path.
     */
    public float[] distances(int[] starts, float maxDamage, int[] prev) {
        float[] dist = new float[count];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        if (prev != null) Arrays.fill(prev, -1);
        PriorityQueue<long[]> pq = new PriorityQueue<>((p, q) -> Float.compare(Float.intBitsToFloat((int) p[1]), Float.intBitsToFloat((int) q[1])));
        for (int s : starts) {
            if (s < 0) continue;
            dist[s] = 0;
            pq.add(new long[]{s, Float.floatToIntBits(0f)});
        }
        float[] cur = new float[1];
        int[] from = new int[1];
        while (!pq.isEmpty()) {
            long[] e = pq.poll();
            int a = (int) e[0];
            float d = Float.intBitsToFloat((int) e[1]);
            if (d > dist[a]) continue;
            cur[0] = d;
            from[0] = a;
            edges(a, (to, cost, dmg) -> {
                if (dmg >= maxDamage) return;
                float c = cost + ((flags[to] & COBWEB) != 0 ? 3f : 0) + ((flags[to] & HAZARD) != 0 ? 4f : 0);
                float nd = cur[0] + c;
                if (nd < dist[to]) {
                    dist[to] = nd;
                    if (prev != null) prev[to] = from[0];
                    pq.add(new long[]{to, Float.floatToIntBits(nd)});
                }
            });
        }
        return dist;
    }

    /** Which nodes can reach any of the targets (reverse search over the same moves). */
    public boolean[] canReach(boolean[] target, float maxDamage) {
        // build reverse adjacency once
        int[] head = new int[count];
        Arrays.fill(head, -1);
        int[][] lists = {new int[count * 8 + 16], new int[count * 8 + 16]}; // {source, next}
        int[] ec = {0};
        for (int a = 0; a < count; a++) {
            final int from = a;
            edges(a, (to, cost, dmg) -> {
                if (dmg >= maxDamage) return;
                if (ec[0] == lists[0].length) {
                    lists[0] = Arrays.copyOf(lists[0], ec[0] * 2);
                    lists[1] = Arrays.copyOf(lists[1], ec[0] * 2);
                }
                lists[0][ec[0]] = from;
                lists[1][ec[0]] = head[to];
                head[to] = ec[0]++;
            });
        }
        int[] toE = lists[0], nextE = lists[1];
        boolean[] ok = new boolean[count];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < count; i++) if (target[i]) {
            ok[i] = true;
            q.add(i);
        }
        while (!q.isEmpty()) {
            int b = q.poll();
            for (int e = head[b]; e >= 0; e = nextE[e]) {
                int a = toE[e];
                if (!ok[a]) {
                    ok[a] = true;
                    q.add(a);
                }
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------ light

    private int vidx(int x, int y, int z) {
        return ((x - area.minX()) * sz + (z - area.minZ())) * area.getYSpan() + (y - area.minY());
    }

    /** Block light (0..15) at every position of the area, propagated from every light source like the game does. */
    private void computeLight() {
        int sy = area.getYSpan();
        light = new byte[sx * sz * sy];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                for (int y = area.minY(); y <= area.maxY(); y++) {
                    int e = lvl.get(x, y, z).getLightEmission();
                    if (e > 0) {
                        light[vidx(x, y, z)] = (byte) e;
                        q.add(new int[]{x, y, z});
                    }
                }
            }
        }
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int lv = light[vidx(c[0], c[1], c[2])];
            if (lv <= 1) continue;
            for (Direction d : Direction.values()) {
                int x = c[0] + d.getStepX(), y = c[1] + d.getStepY(), z = c[2] + d.getStepZ();
                if (x < area.minX() || x > area.maxX() || y < area.minY() || y > area.maxY() || z < area.minZ() || z > area.maxZ()) continue;
                BlockState st = lvl.get(x, y, z);
                int block = st.getLightDampening();
                if (block >= 15) continue;
                int nl = lv - Math.max(1, block);
                int i = vidx(x, y, z);
                if (nl > light[i]) {
                    light[i] = (byte) nl;
                    q.add(new int[]{x, y, z});
                }
            }
        }
    }

    public int lightAt(int x, int y, int z) {
        if (x < area.minX() || x > area.maxX() || y < area.minY() || y > area.maxY() || z < area.minZ() || z > area.maxZ()) return 0;
        return light[vidx(x, y, z)];
    }

    /** Light where a node's player stands: the brighter of the feet and head blocks. */
    public int nodeLight(int n) {
        int f = feetY(n);
        return Math.max(lightAt(nx[n], f, nz[n]), lightAt(nx[n], f + 1, nz[n]));
    }
}
