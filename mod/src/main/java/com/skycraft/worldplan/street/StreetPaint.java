package com.skycraft.worldplan.street;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.concurrent.atomic.LongAdder;

/**
 * The street painter (docs/WORLDGEN-PIPELINE.md P5, the settlement counterpart of {@code roads.RoadPaint}): paints the
 * baked {@link StreetPlan} into every chunk at the last decoration step, after the settlement's pieces and the
 * vegetation. Pure per-column evaluation of the plan: reads and writes only the chunk it decorates, needs no neighbour,
 * does not depend on the order chunks generate in, and painting twice gives the same blocks.
 * <ul>
 *   <li><b>Doorstep paths</b> (PATH edges): one block wide at the planned height: a hole is filled (dirt under a path
 *   block), grass and dirt become a worn path, open water gets a plank walk, and the two cells above are cleared of
 *   clutter, plants, fences (a fence of a yard or pen becomes a gate) and low walls. The building's own porch and steps
 *   (surface {@link StreetPlan#STONE}) are left as built.</li>
 *   <li><b>Gate lanes</b> (the edge from a road's last vertex to the street it meets) the same, over the lane's width.</li>
 * </ul>
 * The streets themselves, their steps on slopes and the stairs down from terraces are the settlement's own pieces
 * (built on the real ground); the painter only joins doors and road ends to them.
 * Inside a building, tower, wall or gatehouse footprint nothing is built or removed except plants and snow.
 */
public class StreetPaint extends Feature<NoneFeatureConfiguration> {
    private static final int FLAGS = Block.UPDATE_CLIENTS;
    /** Longest gate lane painted (a longer one runs across the town: the doorstep paths join it instead). */
    private static final float MAX_LANE = 28f;
    /** Dev aid: a column whose painting is logged ({@code /skycraft-streetplan trace}). */
    public static volatile int TRACE_X = Integer.MIN_VALUE, TRACE_Z;
    static final LongAdder CHUNKS = new LongAdder(), CELLS = new LongAdder(), BLOCKS = new LongAdder(), NANOS = new LongAdder();

    public StreetPaint(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    static String stats() {
        long c = CHUNKS.sum();
        return String.format("StreetPaint: %d chunks painted, %d cells, %d blocks, avg %.1f us per painted chunk", c, CELLS.sum(),
                BLOCKS.sum(), c == 0 ? 0.0 : NANOS.sum() / 1000.0 / c);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        if (level.getLevel().dimension() != Level.OVERWORLD) return false;
        ChunkPos chunk = new ChunkPos(ctx.origin());
        StreetPlan plan = StreetPlan.get();
        int k = plan.bucket(chunk.x, chunk.z);
        if (k < 0) return false;
        long t0 = System.nanoTime();
        Painter p = new Painter(level, plan, chunk.getMinBlockX(), chunk.getMinBlockZ(), k);
        p.traceX = TRACE_X;
        p.traceZ = TRACE_Z;
        try {
            p.run();
            if (p.trace.length() > 0) Skycraft.LOGGER.info("[streetplan] paint trace{}", p.trace);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Skycraft streets: painting chunk {} {} failed", chunk.x, chunk.z, e);
        }
        if (p.cells > 0) {
            CHUNKS.increment();
            CELLS.add(p.cells);
            BLOCKS.add(p.blocks);
            NANOS.add(System.nanoTime() - t0);
        }
        return p.blocks > 0;
    }

    /**
     * True when column (x, z) lies in a named settlement's open work (field, yard, camp, pen, plaza...) and in no
     * building, wall or gatehouse plot: the road painter may pave it like open ground (P5).
     */
    public static boolean workOnly(int x, int z) {
        StreetPlan plan = StreetPlan.get();
        int k = plan.bucket(x >> StreetPlan.BUCKET_SHIFT, z >> StreetPlan.BUCKET_SHIFT);
        if (k < 0) return false;
        boolean work = false;
        for (int j = plan.plotFrom(k), end = plan.plotTo(k); j < end; j++) {
            int p = plan.plotAt(j);
            if (x < plan.pMinX[p] || x > plan.pMaxX[p] || z < plan.pMinZ[p] || z > plan.pMaxZ[p]) continue;
            if (plan.pKind[p] != StreetPlan.PLOT_WORK) return false;
            work = true;
        }
        return work;
    }

    /** Dev aid: paints the chunk of (x, z) again in a live level; returns "cells painted, blocks changed". */
    static String repaint(WorldGenLevel level, int x, int z) {
        StreetPlan plan = StreetPlan.get();
        int k = plan.bucket(x >> 4, z >> 4);
        if (k < 0) return "no street plan data in that chunk";
        Painter p = new Painter(level, plan, (x >> 4) << 4, (z >> 4) << 4, k);
        p.traceX = x;
        p.traceZ = z;
        p.run();
        return p.cells + " cells painted, " + p.blocks + " blocks changed" + p.trace;
    }

    /** One chunk's painting. */
    private static final class Painter {
        final WorldGenLevel level;
        final StreetPlan plan;
        final int x0, z0, bucket, minY, maxY;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        /** Columns of this chunk inside a building / wall plot (painted softly). */
        final boolean[] solidPlot = new boolean[256];
        int cells, blocks;
        /** Dev trace of one column (repaint). */
        int traceX = Integer.MIN_VALUE, traceZ;
        final StringBuilder trace = new StringBuilder();

        Painter(WorldGenLevel level, StreetPlan plan, int x0, int z0, int bucket) {
            this.level = level;
            this.plan = plan;
            this.x0 = x0;
            this.z0 = z0;
            this.bucket = bucket;
            this.minY = level.getMinBuildHeight() + 1;
            this.maxY = level.getMaxBuildHeight() - 4;
        }

        void run() {
            for (int j = plan.plotFrom(bucket), end = plan.plotTo(bucket); j < end; j++) {
                int p = plan.plotAt(j);
                if (plan.pKind[p] == StreetPlan.PLOT_WORK) continue;
                int ax = Math.max(x0, plan.pMinX[p]), bx = Math.min(x0 + 15, plan.pMaxX[p]);
                int az = Math.max(z0, plan.pMinZ[p]), bz = Math.min(z0 + 15, plan.pMaxZ[p]);
                for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) solidPlot[(z - z0) << 4 | (x - x0)] = true;
            }
            for (int j = plan.edgeFrom(bucket), end = plan.edgeTo(bucket); j < end; j++) {
                int e = plan.edgeAt(j);
                byte cls = plan.eClass[e];
                int a = plan.ea[e], b = plan.eb[e];
                if (cls == StreetPlan.PATH) {
                    // the stub from a walk's last cell onto the street, plaza, gate or road it reached is theirs to pave
                    if (joins(a) || joins(b)) continue;
                    path(e, a, b, plan.eSurf[e] == StreetPlan.STONE);
                } else if (cls == StreetPlan.STAIRS && plan.eSurf[e] == StreetPlan.STONE) {
                    path(e, a, b, true);
                } else if (gateLane(a, b)) {
                    float len = (float) Math.hypot(plan.nx[b] - plan.nx[a], plan.nz[b] - plan.nz[a]);
                    if (len <= MAX_LANE) band(e, a, b);
                }
                // street lines (and their steps on slopes) are the settlement's own STREET pieces (Works.street), on the real ground
            }
        }

        boolean joins(int n) {
            byte c = plan.nClass[n];
            return c == StreetPlan.N_STREET || c == StreetPlan.N_PLAZA || c == StreetPlan.N_GATE || c == StreetPlan.N_ROAD;
        }

        boolean gateLane(int a, int b) {
            byte ca = plan.nClass[a], cb = plan.nClass[b];
            return ca == StreetPlan.N_GATE || ca == StreetPlan.N_ROAD || cb == StreetPlan.N_GATE || cb == StreetPlan.N_ROAD;
        }

        /** A one-block path: the cells from node a to node b (4-connected runs between corners). */
        void path(int e, int a, int b, boolean soft) {
            float ax = plan.nx[a], az = plan.nz[a], bx = plan.nx[b], bz = plan.nz[b];
            int n = (int) Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(bz - az)));
            Direction along = Math.abs(bx - ax) >= Math.abs(bz - az) ? Direction.EAST : Direction.SOUTH;
            for (int i = 0; i <= n; i++) {
                float t = n == 0 ? 0f : i / (float) n;
                int x = (int) Math.floor(ax + (bx - ax) * t), z = (int) Math.floor(az + (bz - az) * t);
                if (x < x0 || x > x0 + 15 || z < z0 || z > z0 + 15) continue;
                int h = Math.round(plan.nh[a] + (plan.nh[b] - plan.nh[a]) * t);
                cell(x, z, h, soft, along, plan.eSurf[e] == StreetPlan.PLANKS);
            }
        }

        /** A gate lane: the columns of this chunk within half its width of the centre line, at the lane's height. */
        void band(int e, int a, int b) {
            float ax = plan.nx[a], az = plan.nz[a], bx = plan.nx[b], bz = plan.nz[b];
            float dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
            float half = Math.max(0.5f, plan.ew[e] / 2f);
            Direction along = Math.abs(dx) >= Math.abs(dz) ? Direction.EAST : Direction.SOUTH;
            float ha = plan.nh[a], hb = plan.nh[b];
            for (int z = z0; z <= z0 + 15; z++) {
                for (int x = x0; x <= x0 + 15; x++) {
                    float px = x + 0.5f - ax, pz = z + 0.5f - az;
                    float t = l2 < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (px * dx + pz * dz) / l2));
                    float qx = px - t * dx, qz = pz - t * dz;
                    if (Math.max(Math.abs(qx), Math.abs(qz)) > half + 0.01f) continue;
                    cell(x, z, Math.round(ha + (hb - ha) * t), false, along, false);
                }
            }
        }

        /** One cell of a walk whose top block is at y = h. */
        void cell(int x, int z, int h, boolean soft, Direction along, boolean planks) {
            if (h < minY || h > maxY) return;
            cells++;
            boolean inPlot = solidPlot[(z - z0) << 4 | (x - x0)];
            if (x == traceX && z == traceZ) {
                trace.append(String.format("; cell %d %d h %d soft %s plot %s top %s feet %s head %s", x, z, h, soft, inPlot,
                        get(x, h, z).getBlock(), get(x, h + 1, z).getBlock(), get(x, h + 2, z).getBlock()));
            }
            if (soft || inPlot) {
                // the building's own porch, steps, passage: plants and snow only
                for (int y = h + 1; y <= h + 2; y++) {
                    BlockState s = get(x, y, z);
                    if (!s.isAir() && s.canBeReplaced() && s.getFluidState().isEmpty()) set(x, y, z, Blocks.AIR.defaultBlockState());
                }
                return;
            }
            BlockState top = get(x, h, z);
            boolean water = !top.getFluidState().isEmpty() || !get(x, h - 1, z).getFluidState().isEmpty() && top.isAir();
            if (water || planks && top.isAir()) {
                // a plank walk on the water
                set(x, h, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
            } else if (!solidTop(top)) {
                set(x, h, z, Blocks.DIRT_PATH.defaultBlockState());
                for (int y = h - 1, k = 0; k < 24 && y >= minY; y--, k++) {
                    BlockState s = get(x, y, z);
                    if (solidTop(s) || !s.getFluidState().isEmpty()) break;
                    set(x, y, z, Blocks.DIRT.defaultBlockState());
                }
            } else {
                if (natural(top)) set(x, h, z, Blocks.DIRT_PATH.defaultBlockState());
                if (h - 1 >= minY && !solidTop(get(x, h - 1, z)) && get(x, h - 1, z).getFluidState().isEmpty()) {
                    for (int y = h - 1, k = 0; k < 24 && y >= minY; y--, k++) {
                        BlockState s = get(x, y, z);
                        if (solidTop(s) || !s.getFluidState().isEmpty()) break;
                        set(x, y, z, Blocks.DIRT.defaultBlockState());
                    }
                }
            }
            for (int y = h + 1; y <= h + 2; y++) {
                BlockState s = get(x, y, z);
                if (s.isAir()) continue;
                Block b = s.getBlock();
                if (b instanceof FenceGateBlock) {
                    if (!s.getValue(FenceGateBlock.OPEN)) set(x, y, z, s.setValue(FenceGateBlock.OPEN, true));
                    continue;
                }
                if (b instanceof DoorBlock || b instanceof LadderBlock) continue;
                if (b instanceof FenceBlock && y == h + 1) {
                    set(x, y, z, gateFor(s).setValue(FenceGateBlock.FACING, along).setValue(FenceGateBlock.OPEN, true));
                    continue;
                }
                // a full block of a building stays (the plan routed the path round every building); clutter, plants,
                // low walls, slabs and stairs of yard props, tree trunks and natural ground (a cut) go
                if (s.isSolidRender(level, pos.set(x, y, z)) && !natural(s) && !s.is(BlockTags.BASE_STONE_OVERWORLD)
                        && !s.is(BlockTags.OVERWORLD_NATURAL_LOGS) && !s.is(BlockTags.LEAVES)) {
                    continue;
                }
                set(x, y, z, Blocks.AIR.defaultBlockState());
            }
            // a path block needs air above it (or it turns to dirt)
            if (get(x, h, z).is(Blocks.DIRT_PATH) && !get(x, h + 1, z).isAir()) set(x, h, z, Blocks.COARSE_DIRT.defaultBlockState());
        }

        static boolean solidTop(BlockState s) {
            return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced() && !s.is(BlockTags.LEAVES);
        }

        static boolean natural(BlockState s) {
            return s.is(BlockTags.DIRT) && !s.is(Blocks.DIRT_PATH) || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND) || s.is(Blocks.SNOW_BLOCK)
                    || s.is(Blocks.GRAVEL) || s.is(Blocks.FARMLAND) || s.is(Blocks.CLAY);
        }

        /** The fence gate of a fence's wood (oak when there is none). */
        static BlockState gateFor(BlockState fence) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(fence.getBlock());
            ResourceLocation g = new ResourceLocation(id.getNamespace(), id.getPath() + "_gate");
            Block b = BuiltInRegistries.BLOCK.get(g);
            if (b == Blocks.AIR || !(b instanceof FenceGateBlock)) b = Blocks.OAK_FENCE_GATE;
            return b.defaultBlockState();
        }

        BlockState get(int x, int y, int z) {
            return level.getBlockState(pos.set(x, y, z));
        }

        void set(int x, int y, int z, BlockState s) {
            pos.set(x, y, z);
            if (level.getBlockState(pos) == s) return;
            level.setBlock(pos, s, FLAGS);
            blocks++;
        }
    }
}
