package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The 7,000 Steps: the pilgrims' path from Ivarstead up the Throat of the World to High Hrothgar (and on to the
 * summit). Each start paves one stretch between two waypoints ({@link Sites}), following the real ground column by
 * column: worn stone and gravel, stairs where it climbs, and now and then one of the shrine tablets that tell the
 * story of the Tongues.
 */
public final class Steps {
    public static final String[] ROUTES = {"seven_thousand_steps", "throat_path"};
    private static final double HALF_WIDTH = 1.6;

    private Steps() {}

    public static int routeIndex(String sites) {
        for (int i = 0; i < ROUTES.length; i++) if (ROUTES[i].equals(sites)) return i;
        return 0;
    }

    public static void build(Painter p, DungeonPiece c) {
        String route = ROUTES[Math.floorMod(c.variant, ROUTES.length)];
        int i = c.extra;
        if (i + 1 >= Sites.count(route)) return;
        int[] a = Sites.point(route, i), b = Sites.point(route, i + 1);
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double len = Math.max(1, Math.hypot(dx, dz));
        double ux = dx / len, uz = dz / len;
        // the uphill step: along the segment, rounded to an axis
        Direction along = Math.abs(ux) >= Math.abs(uz) ? (ux > 0 ? Direction.EAST : Direction.WEST) : (uz > 0 ? Direction.SOUTH : Direction.NORTH);
        BoundingBox box = c.getBoundingBox();
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                double rx = x + 0.5 - (a[0] + 0.5), rz = z + 0.5 - (a[1] + 0.5);
                double t = rx * ux + rz * uz;
                if (t < -1.5 || t > len + 1.5) continue;
                double side = Math.abs(rx * -uz + rz * ux);
                if (side > HALF_WIDTH + 1.2) continue;
                int y = p.surfaceAt(x, z);
                if (y == Integer.MIN_VALUE || y - 1 < box.minY() || y + 3 > box.maxY()) continue;
                if (side <= HALF_WIDTH) {
                    p.clear(x, y, z, y + 2);
                    int ahead = p.ownsColumn(x + along.getStepX(), z + along.getStepZ()) ? p.surfaceAt(x + along.getStepX(), z + along.getStepZ()) : y;
                    int behind = p.ownsColumn(x - along.getStepX(), z - along.getStepZ()) ? p.surfaceAt(x - along.getStepX(), z - along.getStepZ()) : y;
                    BlockState s;
                    if (ahead == y + 1) s = Deco.stair(Blocks.STONE_BRICK_STAIRS, along, false);
                    else if (behind == y + 1) s = Deco.stair(Blocks.STONE_BRICK_STAIRS, along.getOpposite(), false);
                    else {
                        int r = p.roll(x, 0, z, 1100, 10);
                        s = r < 4 ? Blocks.COBBLESTONE.defaultBlockState() : r < 7 ? Blocks.STONE_BRICKS.defaultBlockState()
                                : r < 9 ? Blocks.GRAVEL.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
                    }
                    p.set(x, y - 1, z, s);
                } else if (p.chance(x, 0, z, 1101, 9)) {
                    // the odd waymarker stone at the edge
                    p.setIfOpen(x, y, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
                }
            }
        }
        // a shrine tablet at every third waypoint of the main path, set back from the path's left side
        if (c.variant == 0 && i % 3 == 1) {
            int sx = (int) Math.round(a[0] + uz * 3.5), sz = (int) Math.round(a[1] - ux * 3.5);
            if (p.ownsColumn(sx, sz)) {
                int y = p.surfaceAt(sx, sz);
                if (y != Integer.MIN_VALUE && y - 1 >= box.minY() && y + 3 <= box.maxY()) {
                    Direction face = Math.abs(uz) >= Math.abs(ux) ? (uz > 0 ? Direction.WEST : Direction.EAST) : (ux > 0 ? Direction.NORTH : Direction.SOUTH);
                    p.set(sx, y - 1, sz, Blocks.STONE_BRICKS.defaultBlockState());
                    p.set(sx, y, sz, Blocks.STONE_BRICKS.defaultBlockState());
                    p.set(sx, y + 1, sz, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                    p.set(sx, y + 2, sz, Deco.slab(Blocks.STONE_BRICK_SLAB, false));
                    int cx = sx + face.getOpposite().getStepX(), cz = sz + face.getOpposite().getStepZ();
                    if (p.ownsColumn(cx, cz)) p.setIfOpen(cx, p.surfaceAt(cx, cz), cz, Deco.candle(com.skycraft.worldgen.VanillaBlocks.WHITE_CANDLE, 2, true));
                }
            }
        }
    }
}
