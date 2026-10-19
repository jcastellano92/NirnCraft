package com.nirncraft.realms.worldgen.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sovngarde's small decorations: carved Nordic rune stones standing in the golden grass, and the stone cairns
 * heroes raised for those still on their way.
 */
final class SovngardeDecor {
    private SovngardeDecor() {}

    static void register() {
        RealmDecor.add("sovngarde_runestone", SovngardeDecor::runestone);
        RealmDecor.add("sovngarde_cairn", SovngardeDecor::cairn);
    }

    /** A tall, slightly leaning slab of stone with carved faces, sometimes a pair. */
    static boolean runestone(WorldGenLevel level, BlockPos origin, RandomSource r, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x = origin.getX(), z = origin.getZ();
        int y = k.surface(x, z);
        if (!k.standable(x, y, z)) return false;
        boolean alongX = r.nextBoolean();
        int h = 3 + r.nextInt(3);
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState(), carved = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        BlockState mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        for (int d = -1; d <= 0; d++) {
            for (int dy = 0; dy < h; dy++) {
                int bx = alongX ? x + d : x, bz = alongX ? z : z + d;
                BlockState s = dy == 1 || dy == h - 2 ? carved : r.nextInt(4) == 0 ? mossy : stone;
                k.force(bx, y + dy, bz, s);
            }
            int bx = alongX ? x + d : x, bz = alongX ? z : z + d;
            k.root(bx, y - 1, bz, stone, 4);
        }
        k.force(x, y + h, z, Blocks.STONE_BRICK_SLAB.defaultBlockState());
        if (r.nextInt(3) == 0) {
            int ox = x + (alongX ? 0 : 3), oz = z + (alongX ? 3 : 0);
            int oy = k.surface(ox, oz);
            if (k.standable(ox, oy, oz)) {
                for (int dy = 0; dy < h - 1; dy++) k.force(ox, oy + dy, oz, dy == 1 ? carved : stone);
                k.root(ox, oy - 1, oz, stone, 4);
            }
        }
        return true;
    }

    /** A heap of stones with a candle-lit shrine slab and a fallen warrior's helm-stone. */
    static boolean cairn(WorldGenLevel level, BlockPos origin, RandomSource r, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x = origin.getX(), z = origin.getZ();
        int y = k.surface(x, z);
        if (!k.standable(x, y, z)) return false;
        BlockState cobble = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        k.blob(x, y, z, 2.2, 1.8, 2.2, Blocks.COBBLESTONE.defaultBlockState(), false);
        k.blob(x, y + 1, z, 1.4, 1.6, 1.4, cobble, false);
        k.force(x, y + 3, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
        k.force(x, y + 4, z, Blocks.CANDLE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CandleBlock.LIT, true)
                .setValue(net.minecraft.world.level.block.CandleBlock.CANDLES, 3));
        Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(r);
        k.put(x + d.getStepX() * 3, y, z + d.getStepZ() * 3, Blocks.STONE_BRICK_SLAB.defaultBlockState());
        return true;
    }
}
