package com.nirncraft.landmarks;

import com.nirncraft.explore.build.SiteBuilder;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Keeps built sites on the ground. Three parts, all working on real blocks of a {@link LevelReader}:
 *
 * <ul>
 *     <li>{@link #sample}: before a site is built, every column of its footprint is measured (trees, plants and snow
 *     skipped, water counted): base level, slope over the whole footprint (not five points), how deep a foundation
 *     would have to be, how much of it is wet;</li>
 *     <li>{@link #foundation}: the low columns are raised to the base level with the column's own surface and sub
 *     blocks, plus a short skirt outside the footprint that eases the plinth into the slope;</li>
 *     <li>{@link #scan} / {@link #fix}: after the build, every solid block is checked for a way down to the terrain
 *     (a flood fill over the 3-D neighbourhood, a one-block air gap allowed under a block as the spec of "ground within
 *     1 block below"); parts that hover get a pillar of their own material down to the ground (up to
 *     {@value #MAX_FILL} blocks) or are removed.</li>
 * </ul>
 *
 * <p>The same {@link #scan} is the census of {@code /nirncraft-landmarks groundcheck}: it reads finished chunks too.</p>
 */
public final class Grounding {
    /** Deepest foundation (blocks) a column or a hovering part may be given. */
    public static final int MAX_FILL = 8;
    /** Blocks outside the footprint that are raised to ease the plinth into the terrain. */
    public static final int SKIRT = 3;
    /** Largest fill (blocks) a single hovering part gets before it is removed instead. */
    private static final int MAX_PART_FILL = 1500;

    /** Share of a footprint's columns that may stand in water before the site is refused (not for shore kinds). */
    public static final double MAX_WET_SHARE = 0.10;
    /** Builders that work with water or a basin on purpose: their low columns are not raised. */
    public static final java.util.Set<String> WATER_BUILDERS = java.util.Set.of("crater_lake", "drowned_grove", "boat_wreck",
            "sea_stacks", "spring", "dry_lake", "sinkhole", "crater");

    /** Off = the legacy five-point settle and no foundations or support check (for before/after measurements). */
    public static volatile boolean enabled = true;

    private Grounding() {}

    // ------------------------------------------------------------------ counters (reported by groundcheck)

    public static final java.util.concurrent.atomic.LongAdder BUILT = new java.util.concurrent.atomic.LongAdder(),
            REF_SLOPE = new java.util.concurrent.atomic.LongAdder(), REF_WATER = new java.util.concurrent.atomic.LongAdder(),
            REF_DEEP = new java.util.concurrent.atomic.LongAdder(), FOUNDED = new java.util.concurrent.atomic.LongAdder(),
            FOUND_BLOCKS = new java.util.concurrent.atomic.LongAdder(), HOVERING = new java.util.concurrent.atomic.LongAdder(),
            PILLARED = new java.util.concurrent.atomic.LongAdder(), REMOVED = new java.util.concurrent.atomic.LongAdder();

    /** Counts a refusal; always false (the build did not happen). */
    public static boolean refuse(String why) {
        switch (why) {
            case "slope" -> REF_SLOPE.increment();
            case "water" -> REF_WATER.increment();
            default -> REF_DEEP.increment();
        }
        return false;
    }

    static void built(int foundationBlocks, Repair rep) {
        BUILT.increment();
        if (foundationBlocks > 0) {
            FOUNDED.increment();
            FOUND_BLOCKS.add(foundationBlocks);
        }
        if (rep.parts() > 0) HOVERING.increment();
        PILLARED.add(rep.filled());
        REMOVED.add(rep.removed());
    }

    public static String counters() {
        return "built " + BUILT.sum() + ", refused: slope " + REF_SLOPE.sum() + " water " + REF_WATER.sum() + " deep " + REF_DEEP.sum()
                + "; foundations on " + FOUNDED.sum() + " sites (" + FOUND_BLOCKS.sum() + " blocks); hovering parts: sites " + HOVERING.sum()
                + ", pillared " + PILLARED.sum() + ", removed " + REMOVED.sum();
    }

    // ------------------------------------------------------------------ before the build

    /** What the footprint's columns say. */
    public static final class Footprint {
        /** Y of the top solid block the site stands on (the site's base level minus one). */
        public int baseTop;
        /** Dry columns measured / columns in the water or lava. */
        public int dry, wet;
        public int lowest, highest, p05, p95;
        /** Columns that would need a foundation deeper than {@link #MAX_FILL}. */
        public int tooDeep;

        public int spread() {
            return p95 - p05;
        }

        /** Foundation depth the lowest of the 95 % of columns needs. */
        public int depth() {
            return Math.max(0, baseTop - p05);
        }

        public double wetShare() {
            int n = dry + wet;
            return n == 0 ? 0 : (double) wet / n;
        }

        public boolean flat() {
            return spread() <= 1;
        }
    }

    /** Measures the circle of {@code radius} around the builder's centre (columns the builder may write only). */
    public static Footprint sample(SiteBuilder b, int radius) {
        WorldGenLevel level = b.level;
        IntArrayList hs = new IntArrayList();
        Footprint f = new Footprint();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = b.cx + dx, z = b.cz + dz;
                if (!b.writable(p.set(x, level.getMinY() + 8, z))) continue;
                int g = b.groundAt(x, z);
                if (!level.getFluidState(p.set(x, g, z)).isEmpty() || !level.getFluidState(p.set(x, g + 1, z)).isEmpty()) {
                    f.wet++;
                } else {
                    f.dry++;
                    hs.add(g);
                }
            }
        }
        return finish(f, hs, hs.isEmpty() ? b.groundAt(b.cx, b.cz) : 0);
    }

    /**
     * A column source for {@link #predict}: the terrain without chunks (the seeded height function and the map's
     * water, as the far field sees them).
     */
    public interface Columns {
        /** Marks a column that stands in water (sea, river, lake). */
        int WET = Integer.MIN_VALUE;

        /** Y of the column's top solid block, or {@link #WET}. */
        int ground(int x, int z);
    }

    /**
     * {@link #sample}'s measurement of the circle of {@code radius} around (cx, cz) from predicted columns instead of
     * blocks (no trees to skip, no carver or decoration changes: what the noise terrain says).
     */
    public static Footprint predict(int cx, int cz, int radius, Columns c) {
        IntArrayList hs = new IntArrayList();
        Footprint f = new Footprint();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int g = c.ground(cx + dx, cz + dz);
                if (g == Columns.WET) {
                    f.wet++;
                } else {
                    f.dry++;
                    hs.add(g);
                }
            }
        }
        return finish(f, hs, 0);
    }

    /** SiteBuilder.footprintWet over predicted columns: water at the centre or at 8 points at radius/2 and radius. */
    public static boolean footprintWet(int cx, int cz, int radius, Columns c) {
        if (c.ground(cx, cz) == Columns.WET) return true;
        for (int d : new int[]{radius / 2, radius}) {
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
                if (c.ground(cx + o[0] * d, cz + o[1] * d) == Columns.WET) return true;
            }
        }
        return false;
    }

    /**
     * The terrain refusal a site of {@code kind} gets on this footprint ("slope", "water", "deep"), or null when it
     * may be built. {@code footprintWet} is asked only when the slope passed (the builder's order).
     */
    public static String refusal(Kind kind, Footprint fp, java.util.function.BooleanSupplier footprintWet) {
        boolean coastal = kind.needs().contains("coast") || kind.needs().contains("shore");
        if (fp.spread() > kind.maxSpread()) return "slope";
        if (!coastal && footprintWet.getAsBoolean()) return "water";
        if (!coastal && fp.wetShare() > MAX_WET_SHARE) return "water";
        if (fp.depth() > MAX_FILL || fp.tooDeep > Math.max(1, fp.dry / 20)) return "deep";
        return null;
    }

    /** The statistics of the dry columns' heights hs ({@code fallbackTop}: the base when every column is wet). */
    private static Footprint finish(Footprint f, IntArrayList hs, int fallbackTop) {
        if (hs.isEmpty()) {
            f.baseTop = fallbackTop;
            f.lowest = f.highest = f.p05 = f.p95 = f.baseTop;
            return f;
        }
        int[] a = hs.toIntArray();
        Arrays.sort(a);
        int n = a.length;
        f.lowest = a[0];
        f.highest = a[n - 1];
        f.p05 = a[(int) Math.floor((n - 1) * 0.05)];
        f.p95 = a[(int) Math.ceil((n - 1) * 0.95)];
        f.baseTop = a[n / 2];
        for (int g : a) if (f.baseTop - g > MAX_FILL) f.tooDeep++;
        return f;
    }

    /**
     * Raises the low dry columns of the footprint to {@code baseTop} and eases the plinth into the slope over
     * {@link #SKIRT} blocks. Returns the number of blocks placed.
     */
    public static int foundation(SiteBuilder b, int baseTop, int radius) {
        WorldGenLevel level = b.level;
        int placed = 0;
        int reach = radius + SKIRT;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double d = Math.sqrt((double) dx * dx + dz * dz);
                if (d > reach) continue;
                int x = b.cx + dx, z = b.cz + dz;
                if (!b.writable(p.set(x, level.getMinY() + 8, z))) continue;
                int target = d <= radius ? baseTop : baseTop - (int) Math.ceil(d - radius);
                int g = b.groundAt(x, z);
                if (g >= target) continue;
                if (!level.getFluidState(p.set(x, g, z)).isEmpty() || !level.getFluidState(p.set(x, g + 1, z)).isEmpty()) continue;
                BlockState top = level.getBlockState(p.set(x, g, z));
                BlockState sub = level.getBlockState(p.set(x, g - 1, z));
                if (!top.isSolidRender()) top = Blocks.DIRT.defaultBlockState();
                if (!sub.isSolidRender() || sub.is(BlockTags.LOGS) || sub.is(BlockTags.LEAVES)) {
                    sub = top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM) ? Blocks.DIRT.defaultBlockState() : top;
                }
                // a grass top over a column that is mostly sub: top block only at the new surface
                for (int y = g + 1; y <= target; y++) {
                    level.setBlock(p.set(x, y, z), y == target ? top : sub, 2);
                    placed++;
                }
                // plants and the upper half of tall plants left hanging above the new surface go
                for (int y = target + 1; y <= target + 3; y++) {
                    BlockState s = level.getBlockState(p.set(x, y, z));
                    if (s.isAir() || !s.getFluidState().isEmpty() || !s.canBeReplaced()) break;
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        return placed;
    }

    // ------------------------------------------------------------------ the support scan

    /** One connected part of solid blocks that has no way down to the terrain. */
    public static final class Part {
        public final LongArrayList cells = new LongArrayList();
        /** Lowest block of each column of the part. */
        public final LongArrayList feet = new LongArrayList();
        /** Air blocks between each foot and the supported block below it ({@link #NO_GROUND} when none within reach). */
        public final IntArrayList gaps = new IntArrayList();
        public int minY = Integer.MAX_VALUE;

        public int size() {
            return cells.size();
        }

        public int maxGap() {
            int m = 0;
            for (int g : gaps) m = Math.max(m, g);
            return m;
        }
    }

    public static final int NO_GROUND = 1 << 20;

    /** A block that must itself be carried: has collision, is not foliage. */
    private static boolean carried(LevelReader level, BlockPos p, BlockState s) {
        if (s.isAir() || s.is(BlockTags.LEAVES)) return false;
        return !s.getCollisionShape(level, p).isEmpty();
    }

    private static final int[][] NB = neighbours();

    private static int[][] neighbours() {
        List<int[]> l = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (n == 1 || n == 2) l.add(new int[]{dx, dy, dz});
                }
        return l.toArray(new int[0][]);
    }

    /**
     * Finds the parts of solid blocks in the box [x0, x1] x [z0, z1] that do not reach the terrain: the box's bottom
     * and sides are the terrain (the world continues there), blocks touching them by face or edge, or standing within
     * one air block above a supported block, are supported; whatever else is connected only to itself hovers.
     * Leaves and blocks without collision (plants, signs, torches) are not carried and do not carry.
     *
     * @param surface heightmap that includes every block on top (WORLD_SURFACE_WG while generating, WORLD_SURFACE
     *                in finished chunks)
     * @param minSize smallest part reported (blocks)
     */
    public static List<Part> scan(LevelReader level, int x0, int z0, int x1, int z1, Heightmap.Types surface, int minSize) {
        int w = x1 - x0 + 1, d = z1 - z0 + 1;
        if (w <= 0 || d <= 0) return List.of();
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        int[] tops = new int[w * d];
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int h = level.getHeight(surface, x, z);
                tops[(z - z0) * w + (x - x0)] = h;
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
            }
        }
        int yLo = Math.max(level.getMinY(), minH - 12);
        int yHi = Math.min((level.getMaxY() + 1), maxH + 2);
        int hgt = yHi - yLo;
        if (hgt <= 0) return List.of();
        // 0 air, 1 carried and unsupported yet, 2 carried and supported, 4 soft (plants, snow layers, leaves: no support, no gap)
        byte[] g = new byte[w * d * hgt];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int layer = w * d;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int top = Math.min(yHi, tops[(z - z0) * w + (x - x0)] + 1);
                for (int y = yLo; y < top; y++) {
                    p.set(x, y, z);
                    BlockState s = level.getBlockState(p);
                    int cell = (y - yLo) * layer + (z - z0) * w + (x - x0);
                    if (carried(level, p, s)) g[cell] = 1;
                    else if (!s.isAir() && s.getFluidState().isEmpty()) g[cell] = 4;
                }
            }
        }
        // supported: the bottom and the four sides of the box
        IntArrayList stack = new IntArrayList();
        for (int y = 0; y < hgt; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    boolean edge = y == 0 || x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    int i = y * layer + z * w + x;
                    if (edge && g[i] == 1) {
                        g[i] = 2;
                        stack.add(i);
                    }
                }
            }
        }
        while (!stack.isEmpty()) {
            int i = stack.removeInt(stack.size() - 1);
            int y = i / layer, r = i - y * layer, z = r / w, x = r - z * w;
            for (int[] o : NB) {
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= hgt || nz >= d) continue;
                int j = ny * layer + nz * w + nx;
                if (g[j] == 1) {
                    g[j] = 2;
                    stack.add(j);
                }
            }
            // a block resting on a soft block (a snow layer, a tuft of grass) that rests on a carried one: no real gap
            for (int s = -1; s <= 1; s += 2) {
                int ny = y + 2 * s;
                if (ny < 0 || ny >= hgt) continue;
                int j = ny * layer + z * w + x;
                if (g[j] == 1 && g[y * layer + z * w + x] == 2 && g[(y + s) * layer + z * w + x] == 4) {
                    g[j] = 2;
                    stack.add(j);
                }
            }
        }
        List<Part> out = new ArrayList<>();
        for (int i = 0; i < g.length; i++) {
            if (g[i] != 1) continue;
            Part part = new Part();
            g[i] = 3;
            stack.add(i);
            while (!stack.isEmpty()) {
                int k = stack.removeInt(stack.size() - 1);
                int y = k / layer, r = k - y * layer, z = r / w, x = r - z * w;
                part.cells.add(BlockPos.asLong(x0 + x, yLo + y, z0 + z));
                part.minY = Math.min(part.minY, yLo + y);
                for (int[] o : NB) {
                    int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= hgt || nz >= d) continue;
                    int j = ny * layer + nz * w + nx;
                    if (g[j] == 1) {
                        g[j] = 3;
                        stack.add(j);
                    }
                }
            }
            if (part.size() < minSize) continue;
            feet(part, g, w, d, hgt, layer, x0, z0, yLo);
            out.add(part);
        }
        return out;
    }

    /** The lowest block of each column of the part and how far it hovers over the supported block below. */
    private static void feet(Part part, byte[] g, int w, int d, int hgt, int layer, int x0, int z0, int yLo) {
        java.util.HashMap<Long, Integer> lowest = new java.util.HashMap<>();
        for (long c : part.cells) {
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
            Integer cur = lowest.get(key);
            if (cur == null || y < cur) lowest.put(key, y);
        }
        for (var e : lowest.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey(), y = e.getValue();
            part.feet.add(BlockPos.asLong(x, y, z));
            int gap = NO_GROUND;
            for (int yy = y - 1; yy >= yLo; yy--) {
                byte v = g[(yy - yLo) * layer + (z - z0) * w + (x - x0)];
                if (v == 2) {
                    gap = y - 1 - yy;
                    break;
                }
                if (v != 0 && v != 4) break; // another hovering part under it
            }
            part.gaps.add(gap);
        }
    }

    // ------------------------------------------------------------------ the repair

    /** What {@link #fix} did. */
    public record Repair(int parts, int filled, int removed, int blocksAdded, int blocksRemoved) {
        public static final Repair NONE = new Repair(0, 0, 0, 0, 0);
    }

    /**
     * Every hovering part inside the box gets a pillar down to the ground at each of its feet (up to
     * {@link #MAX_FILL} blocks; the pillar is the foot's own block when it is a full cube, else {@code fallback}) or,
     * when it hovers higher or would need too much, is removed.
     *
     * @param writable columns and blocks the builder may write
     */
    public static Repair fix(WorldGenLevel level, int x0, int z0, int x1, int z1, Predicate<BlockPos> writable, BlockState fallback) {
        // generation regions keep the *_WG heightmaps current; finished chunks (dev commands, showcases) the live ones
        Heightmap.Types surface = level instanceof net.minecraft.server.level.WorldGenRegion ? Heightmap.Types.WORLD_SURFACE_WG : Heightmap.Types.WORLD_SURFACE;
        if (level instanceof net.minecraft.server.level.WorldGenRegion region) {
            // 26.3: a feature may only read the 3x3 chunks it writes; past them nothing is written anyway, and the box
            // sides count as terrain (the world continues there), so the scan stops at the write zone
            net.minecraft.world.level.ChunkPos c = region.getCenter();
            x0 = Math.max(x0, (c.x() - 1) << 4);
            z0 = Math.max(z0, (c.z() - 1) << 4);
            x1 = Math.min(x1, ((c.x() + 2) << 4) - 1);
            z1 = Math.min(z1, ((c.z() + 2) << 4) - 1);
            if (x0 > x1 || z0 > z1) return Repair.NONE;
        }
        List<Part> parts = scan(level, x0, z0, x1, z1, surface, 1);
        if (parts.isEmpty()) return Repair.NONE;
        int filled = 0, removed = 0, added = 0, gone = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (Part part : parts) {
            int total = 0;
            boolean ok = part.maxGap() <= MAX_FILL;
            if (ok) for (int g : part.gaps) total += g;
            if (ok && total <= MAX_PART_FILL) {
                for (int i = 0; i < part.feet.size(); i++) {
                    long f = part.feet.getLong(i);
                    int gap = part.gaps.getInt(i);
                    BlockState foot = level.getBlockState(BlockPos.of(f));
                    BlockState pillar = foot.isSolidRender() && !foot.is(BlockTags.LEAVES) && !foot.is(BlockTags.LOGS)
                            && foot.getFluidState().isEmpty() ? foot : fallback;
                    for (int k = 1; k <= gap; k++) {
                        p.set(BlockPos.getX(f), BlockPos.getY(f) - k, BlockPos.getZ(f));
                        if (!writable.test(p)) continue;
                        level.setBlock(p, pillar, 2);
                        added++;
                    }
                }
                filled++;
            } else {
                for (long c : part.cells) {
                    BlockPos q = BlockPos.of(c);
                    if (!writable.test(q)) continue;
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                    gone++;
                }
                removed++;
            }
        }
        return new Repair(parts.size(), filled, removed, added, gone);
    }
}
