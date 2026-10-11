package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Geometry of a room piece. The box includes the walls: floor layer at {@code minY}, walking level {@link #floor},
 * ceiling layer at {@code maxY}. Doorways are 3 wide and 3 high, centred on the room's axes.
 */
public final class Room {
    public final DungeonPiece piece;
    public final int minX, maxX, minZ, maxZ, minY, maxY;
    public final int cx, cz;
    /** Walking level (first air block above the floor). */
    public final int floor;
    /** Interior height. */
    public final int height;

    public Room(DungeonPiece piece) {
        this.piece = piece;
        BoundingBox b = piece.getBoundingBox();
        this.minX = b.minX();
        this.maxX = b.maxX();
        this.minZ = b.minZ();
        this.maxZ = b.maxZ();
        this.minY = b.minY();
        this.maxY = b.maxY();
        this.cx = (minX + maxX) / 2;
        this.cz = (minZ + maxZ) / 2;
        this.floor = piece.floor;
        this.height = maxY - floor;
    }

    public Theme theme() {
        return piece.theme;
    }

    public int role() {
        return piece.role;
    }

    public boolean boss() {
        return piece.role == DungeonPiece.ROLE_BOSS;
    }

    public boolean start() {
        return piece.role == DungeonPiece.ROLE_START;
    }

    public boolean puzzle() {
        return piece.role == DungeonPiece.ROLE_PUZZLE;
    }

    public boolean hasDoor(Direction d) {
        return (piece.doors & (1 << d.get2DDataValue())) != 0;
    }

    /** The gated side of a puzzle room, or null. */
    public Direction gate() {
        return piece.extra >= 0 && piece.role == DungeonPiece.ROLE_PUZZLE ? Direction.from2DDataValue(piece.extra) : null;
    }

    /** The room's furnishing roll (0..15): which variant its builder picks. */
    public int kind() {
        return piece.variant & 15;
    }

    /** How deep the room lies on its dungeon's tree, 0 (the entrance) to 7 (the deepest): the danger ramps with it. */
    public int tier() {
        return (piece.variant >> 4) & 7;
    }

    public boolean has(int flag) {
        return piece.has(flag);
    }

    /** The boss chamber's one-way back door (to the exit shaft), or null. */
    public Direction exit() {
        return piece.role == DungeonPiece.ROLE_BOSS && piece.extra >= 0 ? Direction.from2DDataValue(piece.extra) : null;
    }

    /** The wall of the raised entrance landing (an entrance hall's vista), or null. */
    public Direction vista() {
        return piece.aux > 0 && piece.aux <= 4 ? Direction.from2DDataValue(piece.aux - 1) : null;
    }

    /** Distance inwards from wall {@code d} (1 = the first block inside). */
    public int inward(Direction d, int x, int z) {
        return switch (d) {
            case EAST -> maxX - x;
            case WEST -> x - minX;
            case SOUTH -> maxZ - z;
            default -> z - minZ;
        };
    }

    /** True on the entrance landing and its stairs (kept free of furniture and spawns). */
    public boolean inVista(int x, int z) {
        Direction v = vista();
        if (v == null) return false;
        int in = inward(v, x, z);
        int lat = v.getAxis() == Direction.Axis.X ? z - cz : x - cx;
        return in <= Layout.VISTA_LIFT + 1 || (in <= Layout.VISTA_LIFT + 3 && Math.abs(lat) <= 2);
    }

    /** The side the room was entered from (null for the start room). */
    public Direction entered() {
        return piece.dir >= 0 ? Direction.from2DDataValue(piece.dir) : null;
    }

    public int halfX() {
        return (maxX - minX) / 2;
    }

    public int halfZ() {
        return (maxZ - minZ) / 2;
    }

    public boolean onWall(int x, int z) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }

    /** Which wall a wall position belongs to (corners report the x wall). */
    public Direction wallSide(int x, int z) {
        if (x == minX) return Direction.WEST;
        if (x == maxX) return Direction.EAST;
        if (z == minZ) return Direction.NORTH;
        return Direction.SOUTH;
    }

    /** True for the open part of a doorway in a wall. */
    public boolean doorway(int x, int y, int z) {
        Direction v = vista();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!hasDoor(d)) continue;
            int base = d == v ? floor + Layout.VISTA_LIFT : floor;
            if (y < base || y > base + 2) continue;
            if (d.getAxis() == Direction.Axis.X) {
                int wx = d == Direction.EAST ? maxX : minX;
                if (x == wx && Math.abs(z - cz) <= 1) return true;
            } else {
                int wz = d == Direction.SOUTH ? maxZ : minZ;
                if (z == wz && Math.abs(x - cx) <= 1) return true;
            }
        }
        return false;
    }

    /** True if (x, z) is within {@code r} blocks (inward and sideways) of any doorway: keep it clear of furniture. */
    public boolean nearDoor(int x, int z, int r) {
        if (inVista(x, z)) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!hasDoor(d)) continue;
            int inward, lateral;
            switch (d) {
                case EAST -> { inward = maxX - x; lateral = z - cz; }
                case WEST -> { inward = x - minX; lateral = z - cz; }
                case SOUTH -> { inward = maxZ - z; lateral = x - cx; }
                default -> { inward = z - minZ; lateral = x - cx; }
            }
            if (inward >= 0 && inward <= r + 1 && Math.abs(lateral) <= r) return true;
        }
        return false;
    }

    /** Clear walking lanes: the cross through the centre that joins the doorways. */
    public boolean onLane(int x, int z) {
        boolean ns = (hasDoor(Direction.NORTH) || hasDoor(Direction.SOUTH)) && Math.abs(x - cx) <= 1;
        boolean ew = (hasDoor(Direction.EAST) || hasDoor(Direction.WEST)) && Math.abs(z - cz) <= 1;
        return ns || ew || (Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1);
    }

    /** Interior (inside the walls). */
    public boolean interior(int x, int z) {
        return x > minX && x < maxX && z > minZ && z < maxZ;
    }

    /**
     * Local frame for directional layouts (boss rooms): {@code fwd} metres towards {@code forward} from the centre and
     * {@code side} metres to its right. Returns world x.
     */
    public int localX(Direction forward, int fwd, int side) {
        Direction right = forward.getClockWise();
        return cx + forward.getStepX() * fwd + right.getStepX() * side;
    }

    public int localZ(Direction forward, int fwd, int side) {
        Direction right = forward.getClockWise();
        return cz + forward.getStepZ() * fwd + right.getStepZ() * side;
    }

    /** Half extent along {@code d}'s axis. */
    public int half(Direction d) {
        return d.getAxis() == Direction.Axis.X ? halfX() : halfZ();
    }
}
