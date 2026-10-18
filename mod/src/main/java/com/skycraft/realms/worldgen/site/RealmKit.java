package com.skycraft.realms.worldgen.site;

import com.skycraft.dungeons.world.Painter;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Small building helpers shared by the Shivering Isles, Apocrypha, Soul Cairn and Coldharbour sites: block states with
 * the right properties (stairs, doors, candles, clusters, skulls, chains), a gabled roof, palettes split between two
 * halves of a site, and a local frame for sites that face one of four ways.
 */
final class RealmKit {
    private RealmKit() {}

    static BlockState b(Block block) {
        return block.defaultBlockState();
    }

    static BlockState stairs(Block block, Direction facing) {
        return block.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    static BlockState leaves(Block block) {
        BlockState s = block.defaultBlockState();
        return s.hasProperty(LeavesBlock.PERSISTENT) ? s.setValue(LeavesBlock.PERSISTENT, true) : s;
    }

    /** An amethyst-like cluster growing towards {@code facing}. */
    static BlockState cluster(Block block, Direction facing) {
        BlockState s = block.defaultBlockState();
        return s.hasProperty(AmethystClusterBlock.FACING) ? s.setValue(AmethystClusterBlock.FACING, facing) : s;
    }

    static BlockState candles(Block candle, int n, boolean lit) {
        return candle.defaultBlockState().setValue(CandleBlock.CANDLES, Math.max(1, Math.min(4, n))).setValue(CandleBlock.LIT, lit);
    }

    static BlockState hanging(Block lantern) {
        return lantern.defaultBlockState().setValue(LanternBlock.HANGING, true);
    }

    static BlockState chain(Direction.Axis axis) {
        return com.skycraft.worldgen.VanillaBlocks.CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, axis);
    }

    static BlockState skull(Block skull, int rotation) {
        BlockState s = skull.defaultBlockState();
        return s.hasProperty(SkullBlock.ROTATION) ? s.setValue(SkullBlock.ROTATION, Math.floorMod(rotation, 16)) : s;
    }

    /** A vine clinging to the block on its {@code side}. */
    static BlockState vine(Direction side) {
        return Blocks.VINE.defaultBlockState().setValue(VineBlock.PROPERTY_BY_DIRECTION.get(side), true);
    }

    /** A closed two-block door; {@code facing} points into the building. */
    static void door(Painter p, int x, int y, int z, Block door, Direction facing) {
        BlockState s = door.defaultBlockState().setValue(DoorBlock.FACING, facing);
        p.set(x, y, z, s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        p.set(x, y + 1, z, s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** {@code north} where z < {@code splitZ}, {@code south} elsewhere (Mania and Dementia halves). */
    static Draw.Pal byZ(int splitZ, Draw.Pal north, Draw.Pal south) {
        return (pp, x, y, z) -> z < splitZ ? north.at(pp, x, y, z) : south.at(pp, x, y, z);
    }

    /** {@code west} where x < {@code splitX}, {@code east} elsewhere. */
    static Draw.Pal byX(int splitX, Draw.Pal west, Draw.Pal east) {
        return (pp, x, y, z) -> x < splitX ? west.at(pp, x, y, z) : east.at(pp, x, y, z);
    }

    /** {@code band} on every {@code every}-th layer, {@code base} between. */
    static Draw.Pal banded(Draw.Pal base, Draw.Pal band, int every, int offset) {
        return (pp, x, y, z) -> Math.floorMod(y - offset, every) == 0 ? band.at(pp, x, y, z) : base.at(pp, x, y, z);
    }

    static Draw.Pal weighted(Block[] blocks, int[] weights) {
        BlockState[] states = new BlockState[blocks.length];
        for (int i = 0; i < blocks.length; i++) states[i] = blocks[i].defaultBlockState();
        return Draw.Pal.weighted(states, weights);
    }

    /**
     * A gabled roof of {@code stairs} over the walls (x0..x1, z0..z1) with eaves one block out, its lowest course at
     * {@code y}; the ridge runs along x ({@code ridgeX}) or z. The gable ends are closed with {@code gable}. With
     * {@code holes} > 0 one roof block in {@code holes} is missing (run-down houses).
     */
    static void gableRoof(Draw d, int x0, int z0, int x1, int z1, int y, boolean ridgeX, Block stairs, Draw.Pal ridge,
                          Draw.Pal gable, int holes) {
        Painter p = d.p;
        int u0 = ridgeX ? x0 : z0, u1 = ridgeX ? x1 : z1, v0 = ridgeX ? z0 : x0, v1 = ridgeX ? z1 : x1;
        Direction plus = ridgeX ? Direction.SOUTH : Direction.EAST;
        BlockState up = stairs(stairs, plus), down = stairs(stairs, plus.getOpposite());
        for (int k = 0; ; k++) {
            int va = v0 - 1 + k, vb = v1 + 1 - k;
            if (va > vb) break;
            int yy = y + k;
            for (int u = u0 - 1; u <= u1 + 1; u++) {
                if (va == vb) {
                    int x = ridgeX ? u : va, z = ridgeX ? va : u;
                    if (holes <= 0 || !p.chance(x, yy, z, 41, holes)) p.set(x, yy, z, ridge.at(p, x, yy, z));
                    continue;
                }
                int xa = ridgeX ? u : va, za = ridgeX ? va : u, xb = ridgeX ? u : vb, zb = ridgeX ? vb : u;
                if (holes <= 0 || !p.chance(xa, yy, za, 41, holes)) p.set(xa, yy, za, up);
                if (holes <= 0 || !p.chance(xb, yy, zb, 41, holes)) p.set(xb, yy, zb, down);
            }
            // the gable ends under this course
            for (int v = va + 1; v <= vb - 1; v++) {
                for (int u : new int[]{u0, u1}) {
                    int x = ridgeX ? u : v, z = ridgeX ? v : u;
                    p.set(x, yy, z, gable.at(p, x, yy, z));
                }
            }
        }
    }

    /**
     * A local frame for a site facing {@code front}: {@code v} runs forwards (out of the front), {@code u} towards
     * {@code front.getClockWise()} ({@link #right()}).
     */
    record Frame(int cx, int cz, Direction front) {
        int x(int u, int v) {
            return cx + v * front.getStepX() - u * front.getStepZ();
        }

        int z(int u, int v) {
            return cz + v * front.getStepZ() + u * front.getStepX();
        }

        /** A box between two local corners. */
        void box(Draw d, int u0, int y0, int v0, int u1, int y1, int v1, Draw.Pal pal) {
            d.box(x(u0, v0), y0, z(u0, v0), x(u1, v1), y1, z(u1, v1), pal);
        }

        void box(Draw d, int u0, int y0, int v0, int u1, int y1, int v1, BlockState s) {
            d.box(x(u0, v0), y0, z(u0, v0), x(u1, v1), y1, z(u1, v1), s);
        }

        void air(Draw d, int u0, int y0, int v0, int u1, int y1, int v1) {
            d.air(x(u0, v0), y0, z(u0, v0), x(u1, v1), y1, z(u1, v1));
        }

        void set(Draw d, int u, int y, int v, BlockState s) {
            d.set(x(u, v), y, z(u, v), s);
        }

        /** The world direction of +u. */
        Direction right() {
            return front.getClockWise();
        }
    }
}
