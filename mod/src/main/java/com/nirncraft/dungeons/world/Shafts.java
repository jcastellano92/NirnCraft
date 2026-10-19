package com.nirncraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The loop-back exit of a dungeon (Skyrim's signature shortcut): a 3x3 shaft behind the boss chamber's one-way door
 * (lever on the chamber side only), a ladder up its back wall, and a trapdoor hatch in a little cairn on the surface.
 * Built of the dungeon's masonry, or of bare rock under caves and lairs.
 */
public final class Shafts {
    private Shafts() {}

    public static void build(Painter p, DungeonPiece c) {
        BoundingBox b = c.getBoundingBox();
        int sx = (b.minX() + b.maxX()) / 2, sz = (b.minZ() + b.maxZ()) / 2;
        Direction d = Direction.from2DDataValue(c.dir);
        Direction right = d.getClockWise();
        int floor = c.floor, top = c.aux;
        boolean natural = c.theme.natural();
        p.feature("loopback");
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int dx = x - sx, dz = z - sz;
                int along = dx * d.getStepX() + dz * d.getStepZ();
                int lat = dx * right.getStepX() + dz * right.getStepZ();
                boolean inner = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                boolean ladder = along == 1 && lat == 0;
                for (int y = p.minY(); y <= p.maxY(); y++) {
                    if (y < floor - 1) continue;
                    if (inner) {
                        if (y == floor - 1) p.set(x, y, z, natural ? NaturalBuilder.rock(y) : BuiltBuilder.floor(p, c.theme, x, y, z));
                        else if (y <= top - 2) p.air(x, y, z);
                        else if (y == top - 1) {
                            if (ladder) p.air(x, y, z);
                            else p.set(x, y, z, natural ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : BuiltBuilder.ceiling(p, c.theme, x, y, z));
                        } else if (y == top) {
                            if (ladder) p.set(x, y, z, Deco.trapdoor(Blocks.SPRUCE_TRAPDOOR, d.getOpposite(), false, false));
                            else p.air(x, y, z);
                        } else if (y <= top + 2) p.air(x, y, z);
                    } else {
                        boolean entry = along == -2 && Math.abs(lat) <= 1 && y >= floor && y <= floor + 2;
                        if (entry) {
                            p.air(x, y, z);
                        } else if (y <= top - 1) {
                            p.set(x, y, z, natural ? NaturalBuilder.rock(y) : BuiltBuilder.wall(p, c.theme, x, y, z, y - floor));
                        } else if (y <= top + 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2 && y <= top + 1) {
                            // the cairn that marks the hatch
                            p.set(x, y, z, y == top + 1 && along == 2 && lat == 2 ? Deco.lantern(false, false) : Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState());
                        } else if (y <= top + 2) {
                            p.clear(x, y, z, y);
                        }
                    }
                }
                if (ladder) {
                    for (int y = Math.max(floor, p.minY()); y <= Math.min(top - 1, p.maxY()); y++) {
                        p.set(x, y, z, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, d.getOpposite()));
                    }
                }
                // a light every few rungs, on the side walls
                if (along == 0 && Math.abs(lat) == 1) {
                    Direction face = lat > 0 ? right.getOpposite() : right;
                    for (int y = floor + 2; y <= top - 3; y += 6) {
                        if (lat > 0) p.set(x, y, z, natural ? Deco.wallTorch(false, face) : Deco.wallTorch(c.theme != Theme.DWEMER, face));
                    }
                }
            }
        }
    }

    /**
     * The boss chamber's back door to the shaft passage: an iron door set in the doorway, opened by a lever on the
     * chamber side only, so the back way is a way out and never a way in.
     */
    public static void exitDoor(Painter p, Room room, Direction side) {
        Direction inward = side.getOpposite();
        Direction right = side.getClockWise();
        int wx = side == Direction.EAST ? room.maxX : side == Direction.WEST ? room.minX : room.cx;
        int wz = side == Direction.SOUTH ? room.maxZ : side == Direction.NORTH ? room.minZ : room.cz;
        BlockState frame = room.theme().natural() ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        for (int lat = -1; lat <= 1; lat++) {
            for (int dy = 0; dy <= 2; dy++) {
                if (lat == 0 && dy < 2) continue;
                p.set(wx + right.getStepX() * lat, room.floor + dy, wz + right.getStepZ() * lat, frame);
            }
        }
        Deco.ironDoor(p, wx, room.floor, wz, inward);
        p.set(wx + right.getStepX() + inward.getStepX(), room.floor + 1, wz + right.getStepZ() + inward.getStepZ(), Deco.wallLever(inward));
        // a draught from the door: candles flicker on either side of it
        for (int s : new int[]{-2, 2}) {
            int x = wx + right.getStepX() * s + inward.getStepX(), z = wz + right.getStepZ() * s + inward.getStepZ();
            // (never over the boss's chest or anything else already standing there)
            if (room.interior(x, z) && !room.onLane(x, z)) p.setIfOpen(x, room.floor, z, Deco.candle(Blocks.CANDLE, 3, true));
        }
    }
}
