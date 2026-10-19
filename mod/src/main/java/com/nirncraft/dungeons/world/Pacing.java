package com.nirncraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/**
 * Who is awake, and how many: every ordinary room of a dungeon gets a mood so the enemy count is not an even two or
 * three in each. A fifth of the rooms are empty (a breather), three in ten hold sleepers (dormant mobs, which wake as
 * the player comes close: bears in their dens, bandits in bedrolls, automatons until someone steps on a plate), one in
 * seven is a pack with a second of each, and the rest are as the room's builder wrote them. Corridors may hold a
 * patrol. The planner's beats (ambush, elite, boss) are never touched.
 */
public final class Pacing {
    public static final int NORMAL = 0, EMPTY = 1, SLEEP = 2, PACK = 3;

    private Pacing() {}

    /** The mood of a room; deterministic from the dungeon's seed and the room's place. */
    public static int of(Painter p, Room room) {
        if (room.boss() || room.puzzle()) return NORMAL;
        if (room.has(DungeonPiece.FLAG_AMBUSH) || room.has(DungeonPiece.FLAG_ELITE) || room.has(DungeonPiece.FLAG_SETPIECE)) return NORMAL;
        int roll = p.roll(room.cx, 7, room.cz, 1500, 20);
        if (room.start()) return roll < 8 ? EMPTY : NORMAL;
        int tier = room.tier();
        if (roll < 4) return tier >= 6 ? NORMAL : EMPTY;
        if (roll < 10) return SLEEP;
        if (roll < 13) return PACK;
        return NORMAL;
    }

    /** Automatons dormant until a plate: a stone pressure plate in the sleepers' room, just inside the way in. */
    public static void plate(Painter p, Room room) {
        if (room.theme() != Theme.DWEMER || p.mood != SLEEP) return;
        Direction in = room.entered();
        if (in == null) return;
        int x = room.cx + in.getStepX() * Math.max(2, room.half(in) - 2) + in.getClockWise().getStepX() * 2;
        int z = room.cz + in.getStepZ() * Math.max(2, room.half(in) - 2) + in.getClockWise().getStepZ() * 2;
        if (room.interior(x, z) && !room.nearDoor(x, z, 0)) p.set(x, room.floor, z, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
    }

    /**
     * A patrol in a corridor: two of the dungeon's common foe walking its length, awake. One corridor in four that is
     * long enough and has no trap.
     */
    public static void patrol(Painter p, DungeonPiece c) {
        if (c.has(DungeonPiece.FLAG_TRAP) || c.has(DungeonPiece.FLAG_BARRED)) return;
        int len = c.corridorLength();
        if (len < 6 || p.roll(c.getBoundingBox().minX(), c.getBoundingBox().minY(), c.getBoundingBox().minZ(), 1510, 4) != 0) return;
        Room r = new Room(c);
        String id = c.theme.natural() ? NaturalRooms.commonFoe(r) : Beats.foe(p, r);
        boolean xAxis = c.dir == 0;
        BoundingBoxView b = new BoundingBoxView(c);
        int center = xAxis ? (b.minZ + b.maxZ) / 2 : (b.minX + b.maxX) / 2;
        int low = xAxis ? b.minX : b.minZ;
        for (int i = 0; i < 2; i++) {
            int k = len / 2 - 1 + i * 2;
            int f = c.corridorFloor(k);
            int x = xAxis ? low + k : center, z = xAxis ? center : low + k;
            p.spawn(id, x, f, z, xAxis ? 90f : 0f, null);
        }
        p.feature("beat:patrol");
    }

    private static final class BoundingBoxView {
        final int minX, maxX, minZ, maxZ;

        BoundingBoxView(DungeonPiece c) {
            var b = c.getBoundingBox();
            minX = b.minX();
            maxX = b.maxX();
            minZ = b.minZ();
            maxZ = b.maxZ();
        }
    }
}
