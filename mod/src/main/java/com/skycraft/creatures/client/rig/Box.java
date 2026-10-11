package com.skycraft.creatures.client.rig;

/**
 * One cuboid of a {@link Bone}, in Minecraft model units (1/16 block, y pointing down). The UV origin is assigned by
 * {@link Rig#pack()}; boxes sharing a {@link #uvKey} share one texture area (mirrored limbs).
 *
 * <p>Pure Java (no Minecraft classes): the rig kit also runs standalone to export UV layouts for the texture tools.</p>
 */
public final class Box {
    /** Unique id within the rig: {@code <bone>#<index>}. */
    public final String id;
    /** Material name the texture painter uses (fur, scales, chitin, metal...). */
    public final String mat;
    public final float x;
    public final float y;
    public final float z;
    public final int w;
    public final int h;
    public final int d;
    public final float inflate;
    public final boolean mirror;
    /** Boxes with the same key share their UV area; null = own area. */
    public final String uvKey;
    /** Free-form hint for the texture painter ("face", "eyes", "belly"...). */
    public String tag = "";
    public int u = -1;
    public int v = -1;
    /** Uses the UV area of another box with the same {@link #uvKey} (the painter skips it). */
    public boolean sharedUv;

    Box(String id, String mat, float x, float y, float z, int w, int h, int d, float inflate, boolean mirror, String uvKey) {
        if (w < 0 || h < 0 || d < 0) throw new IllegalArgumentException("negative box size in " + id);
        this.id = id;
        this.mat = mat;
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
        this.h = h;
        this.d = d;
        this.inflate = inflate;
        this.mirror = mirror;
        this.uvKey = uvKey;
    }

    /** Width of the box's unfolded UV area. */
    public int uvWidth() {
        return 2 * d + 2 * w;
    }

    /** Height of the box's unfolded UV area. */
    public int uvHeight() {
        return d + h;
    }
}
