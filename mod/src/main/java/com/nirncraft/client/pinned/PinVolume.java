package com.nirncraft.client.pinned;

/**
 * The block data a pin keeps: per block of its box the block state id and the four biome tints the game blends there
 * (grass, foliage, water, dry foliage: the colour exactly as the level returned it), and the light (sky << 4 | block, as the client's light engine had it) over
 * the box grown by one block on every side.
 *
 * <p>The pin is one element cut from the world, the rule of the exact offline reference's {@code box=} (atlas_truth:
 * only the blocks inside exist, the rest is air, the light keeps what the world gave it): outside the box every block
 * is air, the faces on the box's outside are lit by the world's real light next to them (the one-block margin), and
 * beyond the margin there is open sky (0xF0: sky 15, block 0, what atlas_truth answers outside its volumes). Tints
 * outside come from the nearest block of the box (a tint source asks at the block it colours).
 */
public final class PinVolume {
    public static final int TINT_GRASS = 0, TINT_FOLIAGE = 1, TINT_WATER = 2, TINT_DRY_FOLIAGE = 3, TINTS = 4;
    /** The state id outside the box: none (the reader returns air). */
    public static final int OUTSIDE = -1;
    public static final int OUTSIDE_LIGHT = 0xF0;
    /** How far around the box the light is kept (smooth lighting reads one block out). */
    public static final int MARGIN = 1;

    public final PinBox box;
    /** The box grown by MARGIN: where the light is kept. */
    public final PinBox lightBox;
    private final int[] states;
    private final byte[] light;
    private final int[][] tints = new int[TINTS][];

    public PinVolume(PinBox box) {
        if (box.cells() > PinBox.MAX_CELLS) throw new IllegalArgumentException("box too big: " + box.sx() + "x" + box.sy()
                + "x" + box.sz() + " (at most " + PinBox.MAX_CELLS + " blocks)");
        this.box = box;
        this.lightBox = new PinBox(box.x0() - MARGIN, box.y0() - MARGIN, box.z0() - MARGIN, box.x1() + MARGIN,
                box.y1() + MARGIN, box.z1() + MARGIN);
        int n = (int) box.cells();
        states = new int[n];
        light = new byte[(int) lightBox.cells()];
        java.util.Arrays.fill(light, (byte) OUTSIDE_LIGHT);
        for (int k = 0; k < TINTS; k++) tints[k] = new int[n];
    }

    public void setState(int x, int y, int z, int state) {
        int i = box.index(x, y, z);
        if (i < 0) throw new IndexOutOfBoundsException(x + " " + y + " " + z + " outside " + box);
        states[i] = state;
    }

    /** The light at a block of the box or its margin. */
    public void setLight(int x, int y, int z, int sky, int block) {
        int i = lightBox.index(x, y, z);
        if (i < 0) throw new IndexOutOfBoundsException(x + " " + y + " " + z + " outside " + lightBox);
        light[i] = (byte) ((Math.max(0, Math.min(15, sky)) << 4) | Math.max(0, Math.min(15, block)));
    }

    public void setTint(int kind, int x, int y, int z, int rgb) {
        int i = box.index(x, y, z);
        if (i < 0) throw new IndexOutOfBoundsException(x + " " + y + " " + z + " outside " + box);
        tints[kind][i] = rgb;
    }

    /** The block state id at a world position, or OUTSIDE. */
    public int state(int x, int y, int z) {
        int i = box.index(x, y, z);
        return i < 0 ? OUTSIDE : states[i];
    }

    /** sky << 4 | block. */
    public int light(int x, int y, int z) {
        int i = lightBox.index(x, y, z);
        return i < 0 ? OUTSIDE_LIGHT : light[i] & 0xFF;
    }

    public int skyLight(int x, int y, int z) { return light(x, y, z) >> 4; }
    public int blockLight(int x, int y, int z) { return light(x, y, z) & 15; }

    /** The blended tint of a kind (as the level returned it) at a position; outside the box, at the nearest block of the box. */
    public int tint(int kind, int x, int y, int z) {
        int cx = Math.max(box.x0(), Math.min(box.x1(), x));
        int cy = Math.max(box.y0(), Math.min(box.y1(), y));
        int cz = Math.max(box.z0(), Math.min(box.z1(), z));
        return tints[kind][box.index(cx, cy, cz)];
    }

    /** How many blocks of the box are not the given (air) state id. */
    public int countNot(int air) {
        int n = 0;
        for (int s : states) if (s != air) n++;
        return n;
    }

    /** The approximate memory the volume holds (bytes). */
    public long bytes() {
        return (long) states.length * (4 + 4 * TINTS) + light.length;
    }
}
