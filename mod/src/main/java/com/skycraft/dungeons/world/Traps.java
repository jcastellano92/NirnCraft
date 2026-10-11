package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Corridor traps as tension beats, built from vanilla mechanisms and always readable before they bite: a tripwire
 * strung at ankle height between two hooks, and dart throwers above the hooks in both walls; and the barred window
 * (no trap: a view into the boss chamber through iron bars).
 */
public final class Traps {
    private Traps() {}

    /**
     * A tripwire across a 3-wide walk at position (x, f, z): hooks on the two side lanes, each fixed to a wall block
     * that carries its signal to the dart thrower above it. The string is visible and can be jumped or cut.
     *
     * @param anchor  the block the hooks hang on (must carry redstone: a full solid block)
     * @param natural a cave tunnel: shore up the rock around the trap first
     */
    static void tripwire(Painter p, int x, int f, int z, Direction latPlus, BlockState anchor, boolean natural) {
        int lx = latPlus.getStepX(), lz = latPlus.getStepZ();
        for (int s : new int[]{-1, 1}) {
            int wx = x + lx * 2 * s, wz = z + lz * 2 * s;
            if (natural) {
                for (int dy = -1; dy <= 2; dy++) p.set(wx, f + dy, wz, NaturalBuilder.rock(f + dy));
                for (int dy = 0; dy <= 2; dy++) p.air(x + lx * s, f + dy, z + lz * s);
            }
            p.set(wx, f, wz, anchor);
            Direction out = s < 0 ? latPlus : latPlus.getOpposite();
            p.dispenser(wx, f + 1, wz, out, Deco.ARROWS);
            p.set(x + lx * s, f, z + lz * s, Blocks.TRIPWIRE_HOOK.defaultBlockState()
                    .setValue(TripWireHookBlock.FACING, out).setValue(TripWireHookBlock.ATTACHED, true));
        }
        boolean xLat = latPlus.getAxis() == Direction.Axis.X;
        p.set(x, f, z, Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.ATTACHED, true)
                .setValue(TripWireBlock.EAST, xLat).setValue(TripWireBlock.WEST, xLat)
                .setValue(TripWireBlock.NORTH, !xLat).setValue(TripWireBlock.SOUTH, !xLat));
        p.feature("trap:tripwire");
    }

    /** Iron bars across a 3-wide walk: a window, not a way. */
    static void bars(Painter p, int x, int f, int z, Direction latPlus) {
        boolean xLat = latPlus.getAxis() == Direction.Axis.X;
        BlockState bars = Blocks.IRON_BARS.defaultBlockState().setValue(CrossCollisionBlock.EAST, xLat).setValue(CrossCollisionBlock.WEST, xLat)
                .setValue(CrossCollisionBlock.NORTH, !xLat).setValue(CrossCollisionBlock.SOUTH, !xLat);
        for (int s = -1; s <= 1; s++) {
            for (int dy = 0; dy <= 2; dy++) p.set(x + latPlus.getStepX() * s, f + dy, z + latPlus.getStepZ() * s, bars);
        }
        p.feature("foreshadow:window");
    }
}
