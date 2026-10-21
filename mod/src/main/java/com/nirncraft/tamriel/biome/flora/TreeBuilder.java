package com.nirncraft.tamriel.biome.flora;

import com.nirncraft.tamriel.biome.feature.GenBox;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

/**
 * Collects one tree's blocks, then writes them in one go: logs first (oriented along their branch), then leaves with
 * their real decay distance (so a felled tree's crown falls like a vanilla one), then hanging vines and other
 * trimmings. Every write goes through {@link GenBox}, which keeps the tree inside the 3×3-chunk write area.
 */
final class TreeBuilder {
    final GenBox box;
    final RandomSource random;
    private final Long2ObjectOpenHashMap<BlockState> logs = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet anchors = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<BlockState> leaves = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<BlockState> extras = new Long2ObjectOpenHashMap<>();
    private int lastX, lastY, lastZ;
    private boolean hasLast;

    TreeBuilder(GenBox box, RandomSource random) {
        this.box = box;
        this.random = random;
    }

    static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    // ------------------------------------------------------------------------------------------------ logs
    static BlockState axis(BlockState log, Direction.Axis axis) {
        return log.hasProperty(RotatedPillarBlock.AXIS) ? log.setValue(RotatedPillarBlock.AXIS, axis) : log;
    }

    void log(int x, int y, int z, BlockState state) {
        long k = key(x, y, z);
        logs.put(k, state);
        leaves.remove(k);
    }

    /** A log that may replace soil (roots, the foot of a trunk on a slope). */
    void anchor(int x, int y, int z, BlockState state) {
        log(x, y, z, state);
        anchors.add(key(x, y, z));
    }

    boolean isLog(int x, int y, int z) {
        return logs.containsKey(key(x, y, z));
    }

    /** Extends a trunk foot down into the ground until it meets something solid (at most {@code max} blocks). */
    void sink(int x, int y, int z, BlockState state, int max) {
        for (int d = 1; d <= max; d++) {
            BlockState here = box.get(x, y - d, z);
            boolean soft = GenBox.isSoft(here) || here.is(Blocks.WATER);
            anchor(x, y - d, z, state);
            if (!soft) break;
        }
    }

    /** A filled disc of logs (a thick trunk's cross-section). */
    void disc(double cx, int y, double cz, double r, BlockState state) {
        if (r < 0.75) {
            log(Mth.floor(cx), y, Mth.floor(cz), state);
            return;
        }
        int ri = (int) Math.ceil(r);
        int bx = Mth.floor(cx), bz = Mth.floor(cz);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                double fx = bx + dx + 0.5 - cx, fz = bz + dz + 0.5 - cz;
                if (fx * fx + fz * fz <= r * r + 0.15) log(bx + dx, y, bz + dz, state);
            }
        }
    }

    /**
     * A branch from one point to another, tapering from {@code r0} to {@code r1}. Thin branches are a face-connected
     * chain of logs lying along the branch's main axis; thick ones are a chain of discs.
     */
    void branch(double x0, double y0, double z0, double x1, double y1, double z1, double r0, double r1, BlockState log) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double len = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        int steps = Math.max(1, (int) Math.ceil(len * 1.2));
        Direction.Axis axis = Math.abs(dy) >= Math.max(Math.abs(dx), Math.abs(dz)) ? Direction.Axis.Y
                : Math.abs(dx) >= Math.abs(dz) ? Direction.Axis.X : Direction.Axis.Z;
        BlockState state = axis(log, axis);
        hasLast = false;
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            double x = x0 + dx * t, y = y0 + dy * t, z = z0 + dz * t;
            double r = r0 + (r1 - r0) * t;
            if (r >= 0.75) {
                int ri = (int) Math.ceil(r);
                for (int ox = -ri; ox <= ri; ox++) {
                    for (int oy = -ri; oy <= ri; oy++) {
                        for (int oz = -ri; oz <= ri; oz++) {
                            if (ox * ox + oy * oy + oz * oz <= r * r + 0.2) {
                                log(Mth.floor(x) + ox, Mth.floor(y) + oy, Mth.floor(z) + oz, state);
                            }
                        }
                    }
                }
            } else {
                chain(Mth.floor(x), Mth.floor(y), Mth.floor(z), state, log);
            }
        }
        hasLast = false;
    }

    /** Places a block of a thin branch and fills the corner to the previous one, so the branch is face-connected. */
    private void chain(int x, int y, int z, BlockState state, BlockState base) {
        if (hasLast) {
            int cx = lastX, cy = lastY, cz = lastZ;
            while (Math.abs(x - cx) + Math.abs(y - cy) + Math.abs(z - cz) > 1) {
                if (x != cx) {
                    cx += Integer.signum(x - cx);
                    log(cx, cy, cz, axis(base, Direction.Axis.X));
                } else if (z != cz) {
                    cz += Integer.signum(z - cz);
                    log(cx, cy, cz, axis(base, Direction.Axis.Z));
                } else {
                    cy += Integer.signum(y - cy);
                    log(cx, cy, cz, axis(base, Direction.Axis.Y));
                }
            }
        }
        if (!isLog(x, y, z)) log(x, y, z, state);
        lastX = x;
        lastY = y;
        lastZ = z;
        hasLast = true;
    }

    // ---------------------------------------------------------------------------------------------- leaves
    void leaf(int x, int y, int z, BlockState state) {
        long k = key(x, y, z);
        if (!logs.containsKey(k)) leaves.put(k, state);
    }

    boolean isLeaf(int x, int y, int z) {
        return leaves.containsKey(key(x, y, z));
    }

    /**
     * A clump of leaves: an ellipsoid with radii (rx, ry, rz) whose rim is broken up by {@code ragged} (0 a smooth
     * blob, 0.5 very ragged), so no two clumps look alike.
     */
    /** Dev tracing (FloraTreeFeature.trace): a builder step, with the random draws so far and a hash of its input. */
    void mark(int stage, double a, double b, double c) {
        java.util.function.Consumer<long[]> t = FloraTreeFeature.trace;
        if (t == null) return;
        long n = random instanceof net.minecraft.world.level.levelgen.WorldgenRandom w ? w.getCount() : -1;
        t.accept(new long[]{stage, 0, 0, 0, n, (long) Math.floor(a * 16) * 1000003L + (long) Math.floor(b * 16) * 1009L + (long) Math.floor(c * 16)});
    }

    void clump(double cx, double cy, double cz, double rx, double ry, double rz, BlockState state, double ragged) {
        mark(43, cx, cy, cz);
        mark(44, rx, ry, rz);
        int x0 = Mth.floor(cx - rx), x1 = Mth.floor(cx + rx);
        int y0 = Mth.floor(cy - ry), y1 = Mth.floor(cy + ry);
        int z0 = Mth.floor(cz - rz), z1 = Mth.floor(cz + rz);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    double fx = (x + 0.5 - cx) / rx, fy = (y + 0.5 - cy) / ry, fz = (z + 0.5 - cz) / rz;
                    double d = fx * fx + fy * fy + fz * fz;
                    if (d > 1.0) continue;
                    if (d > 0.45 && random.nextFloat() * ragged > (1.0 - d) * 0.9) continue;
                    leaf(x, y, z, state);
                }
            }
        }
    }

    /** A flat pad of leaves (acacia, juniper, bonsai-like crag trees): a ragged disc one or two blocks thick. */
    void pad(double cx, int y, double cz, double rx, double rz, BlockState state, double ragged, boolean domed) {
        mark(45, cx, y, cz);
        int x0 = Mth.floor(cx - rx), x1 = Mth.floor(cx + rx), z0 = Mth.floor(cz - rz), z1 = Mth.floor(cz + rz);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double fx = (x + 0.5 - cx) / rx, fz = (z + 0.5 - cz) / rz;
                double d = fx * fx + fz * fz;
                if (d > 1.0) continue;
                if (d > 0.5 && random.nextFloat() * ragged > (1.0 - d)) continue;
                leaf(x, y, z, state);
                if (domed && d < 0.3) leaf(x, y + 1, z, state);
            }
        }
    }

    /** Recolours some of the leaves already placed (autumn mixes, blossom, snow on top surfaces). */
    void tint(BlockState from, BlockState to, double chance, boolean topOnly) {
        for (Long2ObjectMap.Entry<BlockState> e : leaves.long2ObjectEntrySet()) {
            if (e.getValue() != from) continue;
            long k = e.getLongKey();
            if (topOnly && leaves.containsKey(BlockPos.offset(k, Direction.UP))) continue;
            if (random.nextDouble() < chance) e.setValue(to);
        }
    }

    int leafCount() {
        return leaves.size();
    }

    // -------------------------------------------------------------------------------------------- trimmings
    void extra(int x, int y, int z, BlockState state) {
        long k = key(x, y, z);
        if (!logs.containsKey(k) && !leaves.containsKey(k)) extras.put(k, state);
    }

    /**
     * Vines hanging from the crown's outer leaves: each strand clings to the side of a leaf block and hangs down up
     * to {@code maxLength} blocks (the way vanilla's leaf vines grow, so they survive block updates).
     */
    void hangVines(double chance, int maxLength) {
        if (chance <= 0) return;
        long[] keys = leaves.keySet().toLongArray();
        for (long k : keys) {
            if (random.nextDouble() >= chance) continue;
            Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            long side = BlockPos.offset(k, d);
            if (leaves.containsKey(side) || logs.containsKey(side)) continue;
            BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d.getOpposite()), true);
            int len = 1 + random.nextInt(maxLength);
            int x = BlockPos.getX(side), y = BlockPos.getY(side), z = BlockPos.getZ(side);
            for (int i = 0; i < len; i++) {
                if (leaves.containsKey(key(x, y - i, z)) || logs.containsKey(key(x, y - i, z))) break;
                extra(x, y - i, z, vine);
            }
        }
    }

    /**
     * Hanging strands of leaves under the outer crown (willow curtains): from leaves at least {@code minDist} from the
     * centre with nothing under them, a strand hangs down up to {@code maxLength} blocks.
     */
    void drape(double cx, double cz, double minDist, double chance, int maxLength) {
        long[] keys = leaves.keySet().toLongArray();
        for (long k : keys) {
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
            if (dx * dx + dz * dz < minDist * minDist) continue;
            if (leaves.containsKey(key(x, y - 1, z)) || logs.containsKey(key(x, y - 1, z))) continue;
            if (random.nextDouble() >= chance) continue;
            BlockState state = leaves.get(k);
            int len = 1 + random.nextInt(maxLength);
            for (int i = 1; i <= len; i++) {
                if (logs.containsKey(key(x, y - i, z))) break;
                leaves.put(key(x, y - i, z), state);
            }
        }
    }

    /** Vines climbing a trunk: on random faces of the given log column. */
    void trunkVines(int x, int y0, int y1, int z, double chance) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (random.nextDouble() >= chance) continue;
            BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d.getOpposite()), true);
            int top = y0 + random.nextInt(Math.max(1, y1 - y0));
            for (int y = y0; y <= top; y++) extra(x + d.getStepX(), y, z + d.getStepZ(), vine);
        }
    }

    // ---------------------------------------------------------------------------------------------- writing
    /**
     * Writes the tree. Logs replace only air, plants, snow, water and leaves (anchors may also replace soil). Only
     * what is really connected is written: logs that can be written and reach a log standing on solid ground, and
     * leaves touching those logs (through other leaves), so a branch blocked by a cliff or by the box edge never
     * leaves a crown or a trunk stub hanging in the air. Leaves get their distance to the nearest log, and those
     * too far for vanilla's decay rule become persistent. Returns false (writing nothing) when the trunk's foot is
     * blocked or nothing of the tree would stand on the ground.
     */
    boolean commit(int baseX, int baseY, int baseZ) {
        if (!GenBox.isSoft(box.get(baseX, baseY, baseZ)) && !logs.containsKey(key(baseX, baseY, baseZ))) return false;
        BlockState atBase = box.get(baseX, baseY, baseZ);
        if (atBase.is(BlockTags.LOGS)) return false;
        // logs that can be written at all
        LongOpenHashSet writable = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<BlockState> e : logs.long2ObjectEntrySet()) {
            long k = e.getLongKey();
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            if (!box.contains(x, y, z)) continue;
            BlockState here = box.get(x, y, z);
            if (GenBox.isSoft(here) || (anchors.contains(k) && isGround(here))) writable.add(k);
        }
        // the logs connected (26-neighbourhood) to one standing on solid ground
        LongOpenHashSet rooted = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (long k : writable) {
            long below = BlockPos.offset(k, Direction.DOWN);
            if (writable.contains(below)) continue;
            int bx = BlockPos.getX(below), by = BlockPos.getY(below), bz = BlockPos.getZ(below);
            if (!box.contains(bx, by, bz)) continue;
            BlockState b = box.get(bx, by, bz);
            if (!GenBox.isSoft(b) && !b.is(BlockTags.LOGS) && b.isSolid() && rooted.add(k)) queue.enqueue(k);
        }
        if (rooted.isEmpty()) return false;
        while (!queue.isEmpty()) {
            long k = queue.dequeueLong();
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if ((dx | dy | dz) == 0) continue;
                long n = key(x + dx, y + dy, z + dz);
                if (writable.contains(n) && rooted.add(n)) queue.enqueue(n);
            }
        }
        for (long k : rooted) {
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            BlockState here = box.get(x, y, z);
            box.set(x, y, z, waterlog(logs.get(k), here));
        }
        // leaves reachable from the written logs through leaves (26-neighbourhood: nothing hangs on its own)
        LongOpenHashSet kept = new LongOpenHashSet();
        for (long k : rooted) queue.enqueue(k);
        while (!queue.isEmpty()) {
            long k = queue.dequeueLong();
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if ((dx | dy | dz) == 0) continue;
                long n = key(x + dx, y + dy, z + dz);
                if (leaves.containsKey(n) && box.contains(x + dx, y + dy, z + dz) && kept.add(n)) queue.enqueue(n);
            }
        }
        // leaf distances: breadth-first from the logs, through leaves only
        Long2IntOpenHashMap dist = new Long2IntOpenHashMap(kept.size() * 2);
        dist.defaultReturnValue(99);
        for (long k : rooted) {
            dist.put(k, 0);
            queue.enqueue(k);
        }
        while (!queue.isEmpty()) {
            long k = queue.dequeueLong();
            int d = dist.get(k);
            if (d >= 6) continue;
            for (Direction dir : Direction.values()) {
                long n = BlockPos.offset(k, dir);
                if (kept.contains(n) && dist.get(n) > d + 1) {
                    dist.put(n, d + 1);
                    queue.enqueue(n);
                }
            }
        }
        for (long k : kept) {
            BlockState state = leaves.get(k);
            if (state.hasProperty(LeavesBlock.DISTANCE)) {
                int d = dist.get(k);
                state = d <= 6 ? state.setValue(LeavesBlock.DISTANCE, d).setValue(LeavesBlock.PERSISTENT, false)
                        : state.setValue(LeavesBlock.DISTANCE, 7).setValue(LeavesBlock.PERSISTENT, true);
            }
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            BlockState here = box.get(x, y, z);
            if (GenBox.isSoft(here) && !here.is(BlockTags.LEAVES)) box.set(x, y, z, waterlog(state, here));
            else if (here.is(BlockTags.LEAVES)) box.set(x, y, z, waterlog(state, here));
        }
        // trimmings (vines): only where they hang on a written leaf or log, or on another trimming above
        java.util.ArrayList<Long2ObjectMap.Entry<BlockState>> ex = new java.util.ArrayList<>(extras.long2ObjectEntrySet());
        ex.sort((a, b) -> Integer.compare(BlockPos.getY(b.getLongKey()), BlockPos.getY(a.getLongKey())));
        LongOpenHashSet placed = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<BlockState> e : ex) {
            long k = e.getLongKey();
            boolean held = false;
            for (Direction dir : Direction.values()) {
                long n = BlockPos.offset(k, dir);
                if (rooted.contains(n) || kept.contains(n) || placed.contains(n)) {
                    held = true;
                    break;
                }
            }
            if (!held) continue;
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            if (box.get(x, y, z).isAir() && box.set(x, y, z, e.getValue())) placed.add(k);
        }
        return true;
    }

    private static boolean isGround(BlockState s) {
        return s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.MUD)
                || s.is(Blocks.CLAY) || s.is(BlockTags.SNOW) || s.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    private static BlockState waterlog(BlockState state, BlockState here) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return state.setValue(BlockStateProperties.WATERLOGGED, here.getFluidState().is(Fluids.WATER)
                    && here.getFluidState().isSource());
        }
        return state;
    }
}
