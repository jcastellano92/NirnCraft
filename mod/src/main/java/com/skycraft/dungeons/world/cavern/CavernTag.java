package com.skycraft.dungeons.world.cavern;

/**
 * What a grand cavern piece remembers about its dungeon, packed into the unused high bits of the piece flags (bits
 * 12 to 30, with bit 31 as the marker; the low 12 are {@code DungeonPiece.FLAG_*}): which of the dungeon's caverns it is (3 bits), the
 * dungeon's colour in the neighbourhood lattice (3 bits) and 13 bits of a per-dungeon key. All caverns of one dungeon
 * share colour and key, so selection can give them different looks, and dungeons a lattice step apart different
 * colours, which is how neighbours differ.
 */
public final class CavernTag {
    private static final int ORD_SHIFT = 12, COLOR_SHIFT = 15, KEY_SHIFT = 18;
    /** Side of a lattice cell in blocks: same-colour cells are at least 2.8 cells apart. */
    private static final int CELL = 320;

    private CavernTag() {}

    /** Flag bits for the {@code ordinal}-th grand cavern of the dungeon whose first room lies at (ox, oz). */
    public static int encode(long dungeonSeed, int ox, int oz, int ordinal) {
        int key = (int) (CavernContext.mix(dungeonSeed) & 0x1FFF);
        return (Math.min(ordinal, 7) << ORD_SHIFT) | (colorAt(ox, oz) << COLOR_SHIFT) | (key << KEY_SHIFT) | (1 << 31);
    }

    /** Colour (0..7) of the lattice cell holding (x, z): neighbours (even diagonally) never share it. */
    public static int colorAt(int x, int z) {
        return Math.floorMod(Math.floorDiv(x, CELL) + 3 * Math.floorDiv(z, CELL), 8);
    }

    /** Non-zero when the flags carry a tag. */
    public static int of(int flags) {
        return flags >>> 31;
    }

    public static int ordinal(int flags) {
        return (flags >>> ORD_SHIFT) & 7;
    }

    public static int color(int flags) {
        return (flags >>> COLOR_SHIFT) & 7;
    }

    public static long key(int flags) {
        return CavernContext.mix(0x5CA1AB1EL + ((flags >>> KEY_SHIFT) & 0x1FFF));
    }
}
