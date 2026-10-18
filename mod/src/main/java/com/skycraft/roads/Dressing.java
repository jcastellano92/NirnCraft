package com.skycraft.roads;

import com.skycraft.dig.PlacedBlocks;
import com.skycraft.tamriel.settlement.Culture;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Roadside dressing: the small clutter that makes a road look used. Planned with the road ({@link #layout}: a feature
 * every so often, denser on busy classes and near settlements, almost none on remote trails, kinds chosen by how close
 * a town is and re-themed by the region's culture) and built when its chunk is paved ({@link #place}), on the verge
 * only, never on a road, in a structure or over player blocks. Everything is a few existing blocks: a handcart,
 * barrels, hay bales, a short fence run, a cairn, a cold campfire with log seats, a woodpile.
 */
final class Dressing {
    static final int CART = 0, BARRELS = 1, HAY = 2, FENCE = 3, CAIRN = 4, CAMP = 5, WOODPILE = 6;
    private static final int KINDS = 7;

    /** Mean distance (blocks) between two pieces along a road: [class][0 near a town, 1 midway, 2 remote]. */
    private static final int[][] GAP = {
            {70, 150, 360},   // highway
            {90, 190, 420},  // road
            {140, 320, 800},  // lane
            {320, 560, 900}, // trail
    };
    /** Kind weights by distance band: cart, barrels, hay, fence, cairn, camp, woodpile. */
    private static final int[][] WEIGHT = {
            {20, 18, 4, 16, 2, 0, 14},
            {12, 12, 4, 12, 12, 6, 12},
            {8, 4, 0, 0, 38, 24, 12},
    };
    private static final int NEAR = 260, MID = 700;
    /** Per road, so a thousand-block highway through a city's hinterland stays within budget. */
    private static final int MAX_PER_ROAD = 90;
    private static final int[] OFFSET = {6, 5, 5, 5};

    private Dressing() {}

    /** The paver's "do not build here" test for a column (structure pieces, homestead claims). */
    interface Reserved {
        boolean at(int x, int y, int z);
    }

    // ------------------------------------------------------------------ planning

    /** Adds the dressing of a new road (before it is indexed, like the other features). */
    static void layout(RoadsData data, Road road, boolean[] inZone, List<RoadFeature> created, LongOpenHashSet touched) {
        if (!RoadsConfig.ROADSIDE.get()) return;
        // pipeline P6: in a plan-era world the baked scene plan places every roadside human prop (waysides)
        if (data.planEra && com.skycraft.worldplan.scene.ScenePlan.active()) return;
        int n = road.size();
        float len = road.length();
        if (n < 12 || len < 70) return;
        Random rnd = new Random(road.id * 0x9E3779B97F4A7C15L ^ ((long) road.xs[0] << 20) ^ road.zs[n - 1]);
        int kind = Math.max(0, Math.min(3, road.kind));
        float at = 18 + rnd.nextInt(24);
        int made = 0, i = 0;
        while (at < len - 18 && made < MAX_PER_ROAD) {
            while (i < n - 1 && road.cum[i] < at) i++;
            if (i < 3 || i > n - 4) break;
            int x0 = road.xs[i], z0 = road.zs[i];
            float fromEnd = Math.min(road.cum[i], len - road.cum[i]);
            int band = fromEnd < NEAR ? 0 : fromEnd < MID ? 1 : 2;
            // a road that runs through or past another town counts as near it
            if (band > 0 && !data.near(x0, z0, 140, s -> s.town).isEmpty()) band = 0;
            at += GAP[kind][band] * (0.55f + 0.9f * rnd.nextFloat());
            if (inZone[i] || road.flags[i] != 0 || road.flags[i - 1] != 0 || road.flags[i + 1] != 0) continue;
            int dk = pick(rnd, band);
            Culture culture = RoadStyle.cultureAt(x0, z0);
            if (dk == HAY && (culture == Culture.DUNMER || culture == Culture.ARGONIAN || culture == Culture.MAORMER)) dk = BARRELS;
            double[] r = RoadLayout.right(road, i);
            int side = rnd.nextBoolean() ? 1 : -1;
            int off = OFFSET[kind];
            int x = (int) Math.round(x0 + r[0] * off * side), z = (int) Math.round(z0 + r[1] * off * side);
            Direction out = Direction.getApproximateNearest((float) (r[0] * side), 0, (float) (r[1] * side));
            // the whole footprint (3 outwards, 2 along) must be clear of towns, other roads and other features
            boolean ok = true;
            Direction along = out.getClockWise();
            for (int u = -2; u <= 2 && ok; u += 2) {
                for (int v = -1; v <= 3 && ok; v += 2) {
                    int px = x + along.getStepX() * u + out.getStepX() * v, pz = z + along.getStepZ() * u + out.getStepZ() * v;
                    if (data.noRoadZoneAt(px, pz) != null || roadNear(data, px, pz, 3)) ok = false;
                }
            }
            if (!ok || data.signpostNear(x, z, 6) != null) continue;
            boolean crowded = false;
            for (RoadFeature f : created) {
                if (Math.abs(f.x - x) <= 5 && Math.abs(f.z - z) <= 5) {
                    crowded = true;
                    break;
                }
            }
            if (crowded) continue;
            RoadFeature f = new RoadFeature(RoadFeature.DRESSING, x, z, road.id);
            f.variant = dk | (out.get2DDataValue() << 4) | (rnd.nextInt(256) << 8);
            created.add(f);
            touched.add(data.addFeature(f));
            made++;
        }
    }

    private static int pick(Random rnd, int band) {
        int[] w = WEIGHT[band];
        int total = 0;
        for (int v : w) total += v;
        int roll = rnd.nextInt(total);
        for (int k = 0; k < KINDS; k++) {
            roll -= w[k];
            if (roll < 0) return k;
        }
        return CAIRN;
    }

    /** True if any already indexed road has a point within {@code r} blocks of (x, z). */
    private static boolean roadNear(RoadsData data, int x, int z, int bandHalf) {
        return RoadCells.near(data, x, z, bandHalf + 3) >= 0;
    }

    // ------------------------------------------------------------------ building

    private record Cell(int u, int v, int dy, BlockState state) {
    }

    /** Builds one piece of dressing; returns the number of block changes (0 when blocked or already built). */
    static int place(ServerLevel level, RoadsData data, RoadFeature f, Culture culture, Reserved reserved) {
        int kind = f.variant & 15;
        Direction out = Direction.from2DDataValue((f.variant >> 4) & 3);
        Direction along = out.getClockWise();
        int salt = (f.variant >> 8) & 255;
        BlockPos base = Paver.ground(level, f.x, f.z);
        if (base == null) return 0;
        RoadStyle.Wood wood = RoadStyle.wood(culture);
        List<Cell> cells = shape(kind, salt, out, along, wood, RoadStyle.stone(culture));
        if (cells.isEmpty()) return 0;
        // every column of the footprint: natural ground at about the same height, free, not a road
        java.util.Map<Long, BlockPos> ground = new java.util.HashMap<>();
        for (Cell c : cells) {
            long key = ((long) c.u << 32) ^ (c.v & 0xffffffffL);
            if (ground.containsKey(key)) continue;
            int x = f.x + along.getStepX() * c.u + out.getStepX() * c.v;
            int z = f.z + along.getStepZ() * c.u + out.getStepZ() * c.v;
            BlockPos g = Paver.ground(level, x, z);
            if (g == null || Math.abs(g.getY() - base.getY()) > 1) return 0;
            BlockState gs = level.getBlockState(g);
            if (!Paver.isNaturalGround(gs) || gs.is(Blocks.DIRT_PATH)) return 0;
            if (RoadCells.near(data, x, z, 2) >= 0) return 0;
            if (reserved.at(x, g.getY(), z) || PlacedBlocks.isPlayerPlaced(level, g)) return 0;
            ground.put(key, g);
        }
        // room above: plants and snow may be replaced, anything else (an earlier build, a tree) means skip
        for (Cell c : cells) {
            BlockPos g = ground.get(((long) c.u << 32) ^ (c.v & 0xffffffffL));
            BlockPos at = g.above(c.dy);
            BlockState cur = level.getBlockState(at);
            if (!Paver.clearable(cur) || PlacedBlocks.isPlayerPlaced(level, at)) return 0;
        }
        int changes = 0;
        for (Cell c : cells) {
            BlockPos g = ground.get(((long) c.u << 32) ^ (c.v & 0xffffffffL));
            BlockPos at = g.above(c.dy);
            BlockState st = c.state;
            int flags = 2 | 16 | 32; // clients, no shape updates, no drops
            if (st.getBlock() instanceof FenceBlock || st.getBlock() instanceof WallBlock || st.getBlock() instanceof FenceGateBlock) {
                st = Block.updateFromNeighbourShapes(st, level, at);
                flags = 2;
            }
            if (level.setBlock(at, st, flags)) changes++;
        }
        return changes;
    }

    private static BlockState log(RoadStyle.Wood w, Direction.Axis axis) {
        BlockState s = w.log().defaultBlockState();
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, axis) : s;
    }

    private static BlockState barrel() {
        return Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
    }

    private static List<Cell> shape(int kind, int salt, Direction out, Direction along, RoadStyle.Wood w, RoadStyle.Stone st) {
        List<Cell> c = new ArrayList<>();
        Random r = new Random(salt * 0x2545F4914F6CDD1DL + kind);
        BlockState planks = w.planks().defaultBlockState(), fence = w.fence().defaultBlockState();
        switch (kind) {
            case CART -> {
                for (int u = -1; u <= 1; u++) for (int v = 0; v <= 1; v++) c.add(new Cell(u, v, 1, planks));
                for (int u = -1; u <= 1; u += 2) for (int v = 0; v <= 1; v++) c.add(new Cell(u, v, 2, fence));
                BlockState cargo = r.nextBoolean() ? Blocks.HAY_BLOCK.defaultBlockState() : barrel();
                c.add(new Cell(0, 0, 2, cargo));
                if (r.nextInt(3) != 0) c.add(new Cell(0, 1, 2, r.nextBoolean() ? Blocks.HAY_BLOCK.defaultBlockState() : barrel()));
                c.add(new Cell(2, 0, 1, fence));
                c.add(new Cell(2, 1, 1, fence));
                BlockState wheel = w.trapdoor().defaultBlockState().setValue(TrapDoorBlock.FACING, out).setValue(TrapDoorBlock.OPEN, true);
                c.add(new Cell(0, -1, 1, wheel));
                c.add(new Cell(0, 2, 1, wheel));
            }
            case BARRELS -> {
                c.add(new Cell(0, 0, 1, barrel()));
                if (r.nextInt(4) != 0) c.add(new Cell(1, 0, 1, barrel()));
                if (r.nextBoolean()) c.add(new Cell(0, 1, 1, barrel()));
                if (r.nextBoolean()) c.add(new Cell(0, 0, 2, barrel()));
            }
            case HAY -> {
                c.add(new Cell(0, 0, 1, Blocks.HAY_BLOCK.defaultBlockState()));
                if (r.nextInt(5) < 3) c.add(new Cell(1, 0, 1, Blocks.HAY_BLOCK.defaultBlockState()));
                if (r.nextBoolean()) c.add(new Cell(0, 0, 2, Blocks.HAY_BLOCK.defaultBlockState()));
                if (r.nextInt(5) < 2) c.add(new Cell(0, 1, 1, Blocks.HAY_BLOCK.defaultBlockState()));
            }
            case FENCE -> {
                int gate = r.nextInt(3) == 0 ? 0 : 99;
                for (int u = -2; u <= 2; u++) {
                    BlockState s = u == gate ? w.gate().defaultBlockState().setValue(FenceGateBlock.FACING, out) : fence;
                    c.add(new Cell(u, 0, 1, s));
                }
                for (int v = 1; v <= 2; v++) {
                    c.add(new Cell(-2, v, 1, fence));
                    if (r.nextBoolean()) c.add(new Cell(2, v, 1, fence));
                }
                if (r.nextBoolean()) c.add(new Cell(0, 1, 1, Blocks.HAY_BLOCK.defaultBlockState()));
            }
            case CAIRN -> {
                c.add(new Cell(0, 0, 1, st.block().defaultBlockState()));
                c.add(new Cell(0, 0, 2, st.mossy().defaultBlockState()));
                c.add(new Cell(0, 0, 3, st.slab().defaultBlockState()));
                if (r.nextBoolean()) c.add(new Cell(1, 0, 1, st.slab().defaultBlockState()));
                if (r.nextBoolean()) c.add(new Cell(-1, 0, 1, st.block().defaultBlockState()));
            }
            case CAMP -> {
                c.add(new Cell(0, 0, 1, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false)));
                c.add(new Cell(-2, 0, 1, log(w, along.getAxis())));
                c.add(new Cell(2, 0, 1, log(w, along.getAxis())));
                if (r.nextBoolean()) c.add(new Cell(0, 2, 1, barrel()));
            }
            default -> { // WOODPILE
                Direction.Axis axis = along.getAxis();
                for (int u = -1; u <= 1; u++) c.add(new Cell(u, 0, 1, log(w, axis)));
                for (int u = -1; u <= 0; u++) c.add(new Cell(u, 0, 2, log(w, axis)));
                c.add(new Cell(0, 0, 3, log(w, axis)));
                if (r.nextBoolean()) for (int u = 0; u <= 1; u++) c.add(new Cell(u, 1, 1, log(w, axis)));
            }
        }
        return c;
    }
}
