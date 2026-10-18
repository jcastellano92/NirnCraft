package com.skycraft.aether;

// aether:sandbox-only (compiled against the real mod by sandbox_check.ps1, not by compile_check.ps1's stubs)

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.Predicate;

/**
 * Far silhouettes that match the near field: a {@link Shape} is a world-aligned raster of solid spans (per column the
 * top, the bottom of the span when it does not stand on the ground, the top block and the column's wall block).
 * {@link #capture} fills one by running the mod's own builder for a piece (a settlement landmark: the White-Gold
 * Tower, the College of Winterhold, Elden Root's tree ...) into a capturing level: a {@code WorldGenLevel} proxy over a
 * stand-in ground (stone up to the far field's ground, air above), which records every block the builder writes. Per
 * column the span is the run of blocks that reaches the top, gaps under {@link #MAX_GAP} blocks merged (so hollow
 * towers and halls stay solid from outside, while an arch's deck, a tree's crown or a floating rock keeps the air under
 * it). Light-emitting blocks are kept as far lights. Parametric silhouettes (AetherSites) fill the same raster.
 */
final class AetherCapture {
    private AetherCapture() {}

    /** Air gaps (blocks) inside a column that still count as one span. */
    static final int MAX_GAP = 10;
    static final int MIN_Y = -64, HEIGHT = 704;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState();

    /** A far silhouette: solid spans over the world columns [x0, x0 + w) x [z0, z0 + d). */
    static final class Shape {
        final int x0, z0, w, d;
        /** Top of the span (blocks, exclusive), NaN = nothing here. */
        final float[] top;
        /** Bottom of the span, NaN = from the ground. */
        final float[] base;
        final BlockState[] wall, roof;
        /** Light sources the far field shows at night: {x, y, z, emission 1..15}. */
        final List<int[]> lights = new ArrayList<>();
        /** Highest point above the ground at the centre (to skip shapes that vanish at a LOD). */
        float rise;

        Shape(int x0, int z0, int w, int d) {
            this.x0 = x0;
            this.z0 = z0;
            this.w = Math.max(1, w);
            this.d = Math.max(1, d);
            top = new float[this.w * this.d];
            base = new float[this.w * this.d];
            Arrays.fill(top, Float.NaN);
            Arrays.fill(base, Float.NaN);
            wall = new BlockState[this.w * this.d];
            roof = new BlockState[this.w * this.d];
        }

        /** A raster centred on (cx, cz), radius r. */
        static Shape around(int cx, int cz, int r) {
            return new Shape(cx - r, cz - r, 2 * r + 1, 2 * r + 1);
        }

        int index(int x, int z) {
            int i = x - x0, j = z - z0;
            return i < 0 || j < 0 || i >= w || j >= d ? -1 : j * w + i;
        }

        boolean empty() {
            for (float t : top) if (!Float.isNaN(t)) return false;
            return true;
        }

        /** A span over column (x, z) from bottom (NaN: the ground) to top; the taller one wins. */
        void span(int x, int z, float bottom, float topY, BlockState wallB, BlockState roofB) {
            int c = index(x, z);
            if (c < 0 || !(topY > (Float.isNaN(top[c]) ? -1e30f : top[c]))) return;
            top[c] = topY;
            base[c] = bottom;
            wall[c] = wallB;
            roof[c] = roofB;
        }

        // ---- parametric primitives (AetherSites), world coordinates ------------------------------------------

        /** A solid round column of radius r(dy) for dy in [0, h), standing on y0: towers, spires, trunks, cones. */
        void profile(double cx, double cz, int y0, int h, DoubleUnaryOperator radiusAt, BlockState wallB, BlockState roofB) {
            double rMax = 0;
            for (int dy = 0; dy < h; dy++) rMax = Math.max(rMax, radiusAt.applyAsDouble(dy));
            int ri = (int) Math.ceil(rMax);
            for (int z = (int) Math.floor(cz) - ri; z <= (int) Math.floor(cz) + ri; z++)
                for (int x = (int) Math.floor(cx) - ri; x <= (int) Math.floor(cx) + ri; x++) {
                    double dist = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                    int topDy = -1;
                    for (int dy = h - 1; dy >= 0; dy--)
                        if (radiusAt.applyAsDouble(dy) >= dist) {
                            topDy = dy;
                            break;
                        }
                    if (topDy >= 0) span(x, z, Float.NaN, y0 + topDy + 1, wallB, roofB);
                }
        }

        /** A box [x0, x1] x [z0, z1] (inclusive, world) up to y (exclusive), from the ground. */
        void box(int ax, int az, int bx, int bz, float y, BlockState wallB, BlockState roofB) {
            for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++)
                for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++) span(x, z, Float.NaN, y, wallB, roofB);
        }

        /** An ellipsoid (centre, radii) that floats when its bottom is above the ground: crowns, caps, floating rocks. */
        void ellipsoid(double cx, double cy, double cz, double rx, double ry, double rz, boolean floating, BlockState wallB,
                       BlockState roofB) {
            for (int z = (int) Math.floor(cz - rz); z <= (int) Math.ceil(cz + rz); z++)
                for (int x = (int) Math.floor(cx - rx); x <= (int) Math.ceil(cx + rx); x++) {
                    double u = (x + 0.5 - cx) / rx, v = (z + 0.5 - cz) / rz, q = 1 - u * u - v * v;
                    if (q < 0) continue;
                    double hh = ry * Math.sqrt(q);
                    span(x, z, floating ? (float) Math.floor(cy - hh) : Float.NaN, (float) Math.ceil(cy + hh), wallB, roofB);
                }
        }

        /** An annulus rIn..rOut around (cx, cz) up to y. */
        void ring(double cx, double cz, double rIn, double rOut, float y, BlockState wallB, BlockState roofB) {
            int ri = (int) Math.ceil(rOut);
            for (int z = (int) Math.floor(cz) - ri; z <= (int) Math.floor(cz) + ri; z++)
                for (int x = (int) Math.floor(cx) - ri; x <= (int) Math.floor(cx) + ri; x++) {
                    double dist = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                    if (dist >= rIn && dist <= rOut) span(x, z, Float.NaN, y, wallB, roofB);
                }
        }
    }

    /**
     * Runs {@code paint} against a capturing level whose ground is {@code ground} (block x, z -> the y of the top solid
     * block) and returns what it built above the ground inside {@code box}, or null when it built nothing there.
     * {@code paint} gets the level to build its canvas on (e.g. a settlement Canvas clipped to the box).
     */
    static Shape capture(BoundingBox box, IntBinaryOperator ground, long seed, Consumer<WorldGenLevel> paint) {
        Recorder rec = new Recorder(ground, seed);
        paint.accept(rec.level());
        return rec.shape(box, ground);
    }

    /** The capturing level's state: blocks written so far over the stand-in ground. */
    private static final class Recorder {
        final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        final Long2IntOpenHashMap columnTop = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap groundCache = new Long2IntOpenHashMap();
        final IntBinaryOperator ground;
        final long seed;
        final RandomSource random;

        Recorder(IntBinaryOperator ground, long seed) {
            this.ground = ground;
            this.seed = seed;
            random = RandomSource.create(seed);
            columnTop.defaultReturnValue(Integer.MIN_VALUE);
            groundCache.defaultReturnValue(Integer.MIN_VALUE);
        }

        int ground(int x, int z) {
            long k = BlockPos.asLong(x, 0, z);
            int g = groundCache.get(k);
            if (g == Integer.MIN_VALUE) {
                g = ground.applyAsInt(x, z);
                groundCache.put(k, g);
            }
            return g;
        }

        BlockState state(BlockPos p) {
            BlockState s = blocks.get(p.asLong());
            if (s != null) return s;
            return p.getY() <= ground(p.getX(), p.getZ()) ? STONE : AIR;
        }

        void put(BlockPos p, BlockState s) {
            blocks.put(p.asLong(), s);
            if (!s.isAir()) {
                long k = BlockPos.asLong(p.getX(), 0, p.getZ());
                if (p.getY() > columnTop.get(k)) columnTop.put(k, p.getY());
            }
        }

        int height(int x, int z) {
            return Math.max(ground(x, z), columnTop.get(BlockPos.asLong(x, 0, z))) + 1;
        }

        @SuppressWarnings("unchecked")
        WorldGenLevel level() {
            InvocationHandler h = (proxy, m, args) -> {
                int n = args == null ? 0 : args.length;
                switch (m.getName()) {
                    case "getBlockState":
                        if (n == 1 && args[0] instanceof BlockPos p) return state(p);
                        break;
                    case "getFluidState":
                        if (n == 1 && args[0] instanceof BlockPos p) return state(p).getFluidState();
                        break;
                    case "setBlock":
                        if (n >= 3 && args[0] instanceof BlockPos p && args[1] instanceof BlockState s) {
                            put(p.immutable(), s);
                            return true;
                        }
                        break;
                    case "removeBlock":
                    case "destroyBlock":
                        if (n >= 1 && args[0] instanceof BlockPos p) {
                            put(p.immutable(), AIR);
                            return true;
                        }
                        break;
                    case "getHeight":
                        if (n == 0) return HEIGHT;
                        if (n == 3) return height((int) args[1], (int) args[2]);
                        break;
                    case "getMinY":
                        return MIN_Y;
                    case "hasChunk":
                    case "hasChunksAt":
                        return true;
                    case "isStateAtPosition":
                        return ((Predicate<BlockState>) args[1]).test(state((BlockPos) args[0]));
                    case "isFluidAtPosition":
                        return ((Predicate<FluidState>) args[1]).test(state((BlockPos) args[0]).getFluidState());
                    case "getBlockEntity":
                        return n == 2 ? Optional.empty() : null;
                    case "getSeed":
                        return seed;
                    case "getRandom":
                        return random;
                    case "isClientSide":
                        return false;
                    case "getSeaLevel":
                        return 63;
                    case "getHeightmapPos":
                        if (n == 2 && args[1] instanceof BlockPos p) return new BlockPos(p.getX(), height(p.getX(), p.getZ()), p.getZ());
                        break;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "aether capture level";
                    default:
                        break;
                }
                if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
                return zero(m.getReturnType());
            };
            return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, h);
        }

        /** Per column of the box: the span reaching the column's top, above the ground. */
        Shape shape(BoundingBox box, IntBinaryOperator g) {
            Shape s = new Shape(box.minX(), box.minZ(), box.getXSpan(), box.getZSpan());
            Map<Long, List<long[]>> cols = new HashMap<>();
            Map<BlockState, Integer> stateIds = new HashMap<>();
            List<BlockState> states = new ArrayList<>();
            for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<BlockState> e : blocks.long2ObjectEntrySet()) {
                BlockState st = e.getValue();
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                long pos = e.getLongKey();
                int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
                if (s.index(x, z) < 0 || y <= ground(x, z)) continue;  // foundations and pads: the far field's ground
                int sid = stateIds.computeIfAbsent(st, k -> {
                    states.add(k);
                    return states.size() - 1;
                });
                cols.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> new ArrayList<>()).add(new long[] {y, sid});
                int light = st.getLightEmission();
                if (light >= 10) s.lights.add(new int[] {x, y, z, light});
            }
            for (Map.Entry<Long, List<long[]>> e : cols.entrySet()) {
                int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
                List<long[]> l = e.getValue();
                l.sort((a, b) -> Long.compare(b[0], a[0]));
                int topY = (int) l.get(0)[0], bottom = topY;
                Map<Integer, Integer> count = new HashMap<>();
                for (long[] v : l) {
                    if (v[0] < bottom - MAX_GAP - 1) break;
                    bottom = (int) v[0];
                    count.merge((int) v[1], 1, Integer::sum);
                }
                int wallId = count.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                int gr = ground(x, z);
                s.span(x, z, bottom <= gr + 1 ? Float.NaN : bottom, topY + 1, states.get(wallId), states.get((int) l.get(0)[1]));
            }
            float rise = 0;
            for (int c = 0; c < s.top.length; c++)
                if (!Float.isNaN(s.top[c])) rise = Math.max(rise, s.top[c] - ground(s.x0 + c % s.w, s.z0 + c / s.w));
            s.rise = rise;
            return s.empty() ? null : s;
        }
    }

    private static Object zero(Class<?> t) {
        if (!t.isPrimitive() || t == void.class) return null;
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        return '\0';
    }

    /** Direction helpers for parametric silhouettes in a site's local frame (fwd along the facing, lat to its right). */
    static int wx(int cx, Direction f, int fwd, int lat) {
        Direction r = f.getClockWise();
        return cx + f.getStepX() * fwd + r.getStepX() * lat;
    }

    static int wz(int cz, Direction f, int fwd, int lat) {
        Direction r = f.getClockWise();
        return cz + f.getStepZ() * fwd + r.getStepZ() * lat;
    }
}
