package com.nirncraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Nordic barrow furnishing: burial alcoves with urns and dormant draugr, sarcophagus halls, chapels, ossuaries and
 * collapsed halls; the antechamber; and the boss chamber with its dais, Draugr Deathlord, treasure and Word Wall.
 */
public final class BarrowRooms {
    private BarrowRooms() {}

    public static void decorate(Painter p, Room room, RandomSource r) {
        if (room.boss()) {
            boss(p, room, r);
            return;
        }
        if (room.start()) {
            start(p, room);
            return;
        }
        int v = room.kind() % 5;
        if (room.puzzle()) v = 3;
        if (room.has(DungeonPiece.FLAG_SETPIECE)) {
            // the set piece: the sarcophagus hall, its dead waking as you pass
            SetPieces.sarcophagusHall(p, room);
            common(p, room);
            if (room.piece.has(DungeonPiece.FLAG_TREASURE)) treasure(p, room, 1, Deco.LOOT_COMMON);
            return;
        }
        if (room.puzzle()) SetPieces.hallOfStories(p, room);
        switch (v) {
            case 0 -> alcoves(p, room);
            case 1 -> sarcophagi(p, room);
            case 2 -> {
                if (!chapel(p, room)) ossuary(p, room);
            }
            case 3 -> ossuary(p, room);
            default -> collapsed(p, room);
        }
        if (v != 0) BuiltBuilder.cornerPillars(p, room, 2);
        common(p, room);
        if (room.piece.has(DungeonPiece.FLAG_TREASURE)) treasure(p, room, v == 0 ? 2 : 1, Deco.LOOT_COMMON);
        if (room.piece.has(DungeonPiece.FLAG_DEAD_END)) urns(p, room, v == 0 ? 2 : 1);
    }

    // ------------------------------------------------------------------ shared

    static void common(Painter p, Room room) {
        // hanging soul lantern on a short chain, cobwebs in the upper corners
        int top = room.maxY - 1;
        if (room.height >= 5) p.set(room.cx, top, room.cz, Deco.chain(Direction.Axis.Y));
        p.set(room.cx, room.height >= 5 ? top - 1 : top, room.cz, Deco.lantern(true, true));
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                boolean edge = x == room.minX + 1 || x == room.maxX - 1 || z == room.minZ + 1 || z == room.maxZ - 1;
                if (edge && p.chance(x, top, z, 5, 7)) p.setIfOpen(x, top, z, Blocks.COBWEB.defaultBlockState());
            }
        }
    }

    /** A loot container in a free corner (deterministic). */
    static void treasure(Painter p, Room room, int inset, net.minecraft.resources.Identifier loot) {
        int[][] corners = {
                {room.minX + inset, room.minZ + inset}, {room.maxX - inset, room.minZ + inset},
                {room.minX + inset, room.maxZ - inset}, {room.maxX - inset, room.maxZ - inset}};
        int first = p.roll(room.cx, room.floor, room.cz, 41, 4);
        for (int i = 0; i < 4; i++) {
            int[] c = corners[(first + i) % 4];
            if (room.nearDoor(c[0], c[1], 1) || room.onLane(c[0], c[1])) continue;
            Direction face = towardCenter(room, c[0], c[1]);
            p.container(c[0], room.floor, c[1], Deco.chest(face), loot);
            return;
        }
    }

    static void urns(Painter p, Room room, int inset) {
        int[][] spots = {
                {room.minX + inset, room.cz + 2}, {room.maxX - inset, room.cz - 2},
                {room.cx + 2, room.minZ + inset}, {room.cx - 2, room.maxZ - inset}};
        for (int[] s : spots) {
            if (!room.interior(s[0], s[1]) || room.nearDoor(s[0], s[1], 1) || room.onLane(s[0], s[1])) continue;
            if (p.chance(s[0], room.floor, s[1], 43, 3)) p.container(s[0], room.floor, s[1], Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
            else p.set(s[0], room.floor, s[1], Blocks.DECORATED_POT.defaultBlockState());
        }
    }

    static Direction towardCenter(Room room, int x, int z) {
        int dx = room.cx - x, dz = room.cz - z;
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ------------------------------------------------------------------ variants

    /** A thick inner wall pierced with burial niches: urns, skulls, candles and sleeping draugr. */
    static void alcoves(Painter p, Room room) {
        p.feature("room:alcoves");
        int draugr = 0;
        int ringTop = Math.min(room.floor + 2, room.maxY - 2);
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                boolean xr = x == room.minX + 1 || x == room.maxX - 1;
                boolean zr = z == room.minZ + 1 || z == room.maxZ - 1;
                if (!xr && !zr) continue;
                if (room.nearDoor(x, z, 1)) continue;
                for (int y = room.floor; y <= ringTop; y++) p.set(x, y, z, BuiltBuilder.wall(p, room.theme(), x, y, z, y - room.floor));
                p.set(x, ringTop + 1, z, Deco.slab(Blocks.STONE_BRICK_SLAB, false));
                boolean corner = xr && zr;
                int along = xr ? z : x;
                if (corner || Math.floorMod(along, 2) != 0) continue;
                // niche
                Direction inward = x == room.minX + 1 ? Direction.EAST : x == room.maxX - 1 ? Direction.WEST
                        : z == room.minZ + 1 ? Direction.SOUTH : Direction.NORTH;
                p.set(x, room.floor, z, Blocks.AIR.defaultBlockState());
                p.set(x, room.floor + 1, z, Blocks.AIR.defaultBlockState());
                int roll = p.roll(x, room.floor, z, 21, 12);
                if (roll <= 2) p.set(x, room.floor, z, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(inward)));
                else if (roll <= 4) p.set(x, room.floor, z, Blocks.DECORATED_POT.defaultBlockState());
                else if (roll == 5) p.container(x, room.floor, z, Deco.barrel(inward), Deco.LOOT_MINOR);
                else if (roll <= 7 && draugr < 1 + room.tier() / 2) {
                    draugr++;
                    p.spawnDormant(foe(room), x, room.floor, z, Painter.yaw(inward));
                } else if (roll == 8) p.set(x, room.floor, z, Deco.candle(Blocks.CANDLE, 2 + roll % 2, true));
                else if (roll == 9) p.set(x, room.floor, z, Blocks.BONE_BLOCK.defaultBlockState());
                else p.set(x, room.floor + 1, z, Blocks.COBWEB.defaultBlockState());
            }
        }
    }

    static String foe(Room room) {
        return room.piece.kit == Kit.FORSWORN ? Mobs.forsworn() : Mobs.draugr();
    }

    /** Stone coffins against the long walls, their occupants already standing. */
    static void sarcophagi(Painter p, Room room) {
        p.feature("room:sarcophagi");
        boolean alongX = room.halfX() >= room.halfZ();
        int draugr = 0;
        for (Direction wall : alongX ? new Direction[]{Direction.NORTH, Direction.SOUTH} : new Direction[]{Direction.WEST, Direction.EAST}) {
            Direction inward = wall.getOpposite();
            int half = alongX ? room.halfX() : room.halfZ();
            for (int a = -half + 2; a <= half - 2; a += 3) {
                int bx = alongX ? room.cx + a : (wall == Direction.WEST ? room.minX : room.maxX);
                int bz = alongX ? (wall == Direction.NORTH ? room.minZ : room.maxZ) : room.cz + a;
                int hx = bx + inward.getStepX(), hz = bz + inward.getStepZ();
                int fx = hx + inward.getStepX(), fz = hz + inward.getStepZ();
                if (room.nearDoor(hx, hz, 1) || room.nearDoor(fx, fz, 1) || room.onLane(fx, fz)) continue;
                p.set(hx, room.floor, hz, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                p.set(hx, room.floor + 1, hz, Blocks.CHISELED_DEEPSLATE.defaultBlockState());
                p.set(fx, room.floor, fz, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                p.set(fx, room.floor + 1, fz, Deco.slab(Blocks.DEEPSLATE_TILE_SLAB, false));
                p.set(hx, room.floor + 2, hz, Deco.candle(Blocks.CANDLE, 1, p.chance(hx, 0, hz, 22, 2)));
                int sx = fx + inward.getStepX(), sz = fz + inward.getStepZ();
                if (draugr < 1 + room.tier() / 2 && !room.onLane(sx, sz) && p.chance(sx, room.floor, sz, 23, 2)) {
                    draugr++;
                    p.spawnDormant(foe(room), sx, room.floor, sz, Painter.yaw(inward));
                }
            }
        }
        if (draugr == 0) p.spawn(foe(room), room.cx + 1, room.floor, room.cz + 1, 0f, null);
    }

    /** An altar against a doorless wall with pews facing it. */
    static boolean chapel(Painter p, Room room) {
        p.feature("room:chapel");
        Direction side = null;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!room.hasDoor(d)) side = d;
        if (side == null) return false;
        Direction inward = side.getOpposite();
        Direction right = side.getClockWise();
        int half = room.half(side);
        int width = room.half(right);
        for (int lat = -1; lat <= 1; lat++) {
            int x = room.localX(side, half - 1, lat), z = room.localZ(side, half - 1, lat);
            p.set(x, room.floor, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            p.set(x, room.floor + 1, z, lat == 0 ? Blocks.SKELETON_SKULL.defaultBlockState() : Deco.candle(Blocks.CANDLE, 3, true));
            if (lat == 0) p.set(x, room.floor + 1, z, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(inward)));
        }
        for (int fwd = half - 3; fwd >= -half + 2; fwd -= 2) {
            for (int lat = 2; lat <= Math.min(3, width - 1); lat++) {
                for (int s : new int[]{-1, 1}) {
                    int x = room.localX(side, fwd, lat * s), z = room.localZ(side, fwd, lat * s);
                    if (room.nearDoor(x, z, 1) || room.onLane(x, z)) continue;
                    p.set(x, room.floor, z, Deco.stair(Blocks.DARK_OAK_STAIRS, inward, false));
                }
            }
        }
        int x = room.localX(side, half - 1, 0) + inward.getStepX(), z = room.localZ(side, half - 1, 0) + inward.getStepZ();
        if (p.chance(x, room.floor, z, 24, 2)) p.spawn(foe(room), x, room.floor, z, Painter.yaw(inward), null);
        return true;
    }

    /** Bone-lined walls and skull shelves. */
    static void ossuary(Painter p, Room room) {
        p.feature("room:ossuary");
        int count = 0;
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                boolean ring = x == room.minX + 1 || x == room.maxX - 1 || z == room.minZ + 1 || z == room.maxZ - 1;
                if (!ring || room.nearDoor(x, z, 1)) continue;
                p.set(x, room.floor, z, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Y));
                int roll = p.roll(x, room.floor, z, 25, 6);
                if (roll == 0) p.set(x, room.floor + 1, z, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(towardCenter(room, x, z))));
                else if (roll == 1) p.set(x, room.floor + 1, z, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.WHITE_CANDLE, 2, true));
            }
        }
        for (int[] c : new int[][]{{-1, -1}, {1, 1}, {1, -1}, {-1, 1}}) {
            int x = room.cx + c[0] * 2, z = room.cz + c[1] * 2;
            if (room.onLane(x, z) || count >= 2) continue;
            if (p.chance(x, room.floor, z, 26, 2)) {
                count++;
                p.spawn(foe(room), x, room.floor, z, p.roll(x, 0, z, 27, 360), null);
            }
        }
        if (room.puzzle() && count == 0) p.spawn(foe(room), room.cx + 1, room.floor, room.cz - 1, 0f, null);
    }

    /** Fallen masonry, webs and skeevers. */
    static void collapsed(Painter p, Room room) {
        p.feature("room:collapsed");
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
                double n = p.noise(x, room.floor, z, 3.0, 28);
                if (n > 0.25) {
                    p.set(x, room.floor, z, p.chance(x, 0, z, 29, 3) ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
                    if (n > 0.5) p.set(x, room.floor + 1, z, Deco.slab(Blocks.COBBLESTONE_SLAB, false));
                } else if (n < -0.55) {
                    p.set(x, room.floor, z, Blocks.COBWEB.defaultBlockState());
                }
            }
        }
        p.spawn(Mobs.skeever(), room.cx - 1, room.floor, room.cz, 90f, null);
        p.spawn(Mobs.skeever(), room.cx + 1, room.floor, room.cz + 1, 270f, null);
        if (p.chance(room.cx, room.floor, room.cz, 30, 2)) p.spawn(foe(room), room.cx, room.floor, room.cz - 1, 180f, null);
    }

    // ------------------------------------------------------------------ special rooms

    /** The antechamber at the foot of the entrance stairs: braziers, urns and an unlucky adventurer. */
    static void start(Painter p, Room room) {
        p.feature("room:start");
        BuiltBuilder.cornerPillars(p, room, 1);
        Direction out = null;
        for (Direction d : Direction.Plane.HORIZONTAL) if (room.hasDoor(d)) { out = d; break; }
        if (out != null) {
            for (int s : new int[]{-2, 2}) {
                int x = room.localX(out, room.half(out) - 1, s), z = room.localZ(out, room.half(out) - 1, s);
                p.set(x, room.floor, z, Deco.campfire(false, true));
            }
        }
        int ax = room.minX + 2, az = room.maxZ - 2;
        if (!room.onLane(ax, az) && !room.nearDoor(ax, az, 1)) {
            p.set(ax, room.floor, az, Deco.skull(Blocks.SKELETON_SKULL, 3));
            p.container(ax + 1, room.floor, az, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
            p.set(ax, room.floor, az - 1, Blocks.BONE_BLOCK.defaultBlockState());
        }
        urns(p, room, 1);
        common(p, room);
        Puzzles.clawCache(p, room);
    }

    /**
     * The final chamber: a raised dais under the Word Wall, the Draugr Deathlord rising in front of it, his treasure
     * at its side, guards in the hall and soul-light hanging from chains.
     */
    static void boss(Painter p, Room room, RandomSource r) {
        p.feature("room:boss");
        Direction entered = room.entered();
        Direction f = entered != null ? entered.getOpposite() : Direction.NORTH;
        Direction back = f.getOpposite();
        int halfF = room.half(f);
        int halfL = room.half(f.getClockWise());
        Block stair = Blocks.STONE_BRICK_STAIRS;
        // dais
        for (int fwd = halfF - 3; fwd <= halfF - 1; fwd++) {
            for (int s = -(halfL - 1); s <= halfL - 1; s++) {
                int x = room.localX(f, fwd, s), z = room.localZ(f, fwd, s);
                BlockState st = fwd == halfF - 3 && Math.abs(s) <= 1 ? Deco.stair(stair, f, false)
                        : fwd == halfF - 3 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                p.set(x, room.floor, z, st);
            }
        }
        // Word Wall framed in the far wall
        int wx = room.localX(f, halfF, 0), wz = room.localZ(f, halfF, 0);
        Direction right = f.getClockWise();
        for (int s = -2; s <= 2; s++) {
            for (int dy = 1; dy <= 4; dy++) {
                int x = wx + right.getStepX() * s, z = wz + right.getStepZ() * s;
                if (s == 0 && dy == 2) continue;
                BlockState st = Math.abs(s) == 2 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                if (dy == 4) st = Math.abs(s) == 2 ? Deco.stair(stair, s < 0 ? right : right.getOpposite(), true) : Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                p.set(x, room.floor + dy, z, st);
            }
        }
        Deco.wordWall(p, wx, room.floor + 2, wz, back, room.piece.param);
        // braziers and the boss chest on the dais
        for (int s : new int[]{-(halfL - 1), halfL - 1}) {
            p.set(room.localX(f, halfF - 1, s), room.floor + 1, room.localZ(f, halfF - 1, s), Deco.campfire(false, true));
        }
        int cs = halfL >= 5 ? 3 : 2;
        int side = p.chance(room.cx, 0, room.cz, 50, 2) ? cs : -cs;
        p.container(room.localX(f, halfF - 1, side), room.floor + 1, room.localZ(f, halfF - 1, side), Deco.chest(back), Deco.LOOT_BOSS);
        // the Deathlord's open sarcophagus on the other side
        int ss = -side;
        for (int fwd = halfF - 2; fwd <= halfF - 1; fwd++) {
            int x = room.localX(f, fwd, ss), z = room.localZ(f, fwd, ss);
            p.set(x, room.floor + 1, z, fwd == halfF - 1 ? Blocks.CHISELED_DEEPSLATE.defaultBlockState() : Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        }
        p.set(room.localX(f, halfF - 3, ss + Integer.signum(ss)), room.floor + 1, room.localZ(f, halfF - 3, ss + Integer.signum(ss)),
                Deco.slab(Blocks.DEEPSLATE_TILE_SLAB, false));
        p.spawnBoss(room.piece.kit == Kit.FORSWORN ? Mobs.hagraven() : Mobs.deathlord(),
                room.localX(f, halfF - 2, 0), room.floor + 1, room.localZ(f, halfF - 2, 0), Painter.yaw(back), null);
        // pillars down the hall
        int top = room.height - 1;
        for (int fwd = -(halfF - 2); fwd <= halfF - 5; fwd += 3) {
            for (int s : new int[]{-(halfL - 1), halfL - 1}) {
                int x = room.localX(f, fwd, s), z = room.localZ(f, fwd, s);
                if (room.nearDoor(x, z, 1)) continue;
                for (int rel = 0; rel <= top; rel++) p.set(x, room.floor + rel, z, BuiltBuilder.pillar(p, Theme.BARROW, rel, top));
            }
        }
        // guards
        p.spawn(foe(room), room.localX(f, 0, 3), room.floor, room.localZ(f, 0, 3), Painter.yaw(back), null);
        p.spawn(foe(room), room.localX(f, 0, -3), room.floor, room.localZ(f, 0, -3), Painter.yaw(back), null);
        // hanging lights
        for (int s : new int[]{-2, 2}) {
            int x = room.localX(f, 0, s), z = room.localZ(f, 0, s);
            for (int y = room.maxY - 1; y >= room.maxY - 2; y--) p.set(x, y, z, Deco.chain(Direction.Axis.Y));
            p.set(x, room.maxY - 3, z, Deco.lantern(true, true));
        }
        // urns along the walls
        urns(p, room, 1);
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                if (p.chance(x, room.maxY - 1, z, 51, 10)) p.setIfOpen(x, room.maxY - 1, z, Blocks.COBWEB.defaultBlockState());
            }
        }
        if (room.piece.special != Special.NONE) Exteriors.bossRoom(p, room);
    }
}
