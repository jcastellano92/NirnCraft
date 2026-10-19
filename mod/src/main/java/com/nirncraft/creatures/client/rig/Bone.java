package com.nirncraft.creatures.client.rig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A model bone: a pivot (relative to its parent), a rest rotation (degrees) and its boxes. Minecraft model space:
 * x = the creature's left (+) / right (-), y down, z back (+) / front (-).
 *
 * <p>Side bones ({@link #pair}) are authored once for the right side; the left copy mirrors box positions, rotations
 * and UVs automatically ({@link #sbox}).</p>
 */
public final class Bone {
    public final Rig rig;
    public final String name;
    public final Bone parent;
    /** 0 = centre, -1 = right side, +1 = left side (mirrored). */
    public final int side;
    public float x;
    public float y;
    public float z;
    public float rx;
    public float ry;
    public float rz;
    public final List<Box> boxes = new ArrayList<>();
    public final List<Bone> children = new ArrayList<>();
    /** Base name of a side bone (without _right/_left), for shared UVs. */
    final String baseName;

    Bone(Rig rig, String name, Bone parent, int side, String baseName, float x, float y, float z) {
        this.rig = rig;
        this.name = name;
        this.parent = parent;
        this.side = side;
        this.baseName = baseName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** A child bone with its pivot at (x, y, z) relative to this bone (x mirrored on the left side). */
    public Bone child(String name, float x, float y, float z) {
        String full = side == 0 ? name : name + (side < 0 ? "_right" : "_left");
        Bone b = new Bone(rig, full, this, side, side == 0 ? null : name, side > 0 ? -x : x, y, z);
        rig.register(b);
        children.add(b);
        return b;
    }

    /**
     * Two mirrored bones {@code <name>_right} (pivot at -|x|) and {@code <name>_left} (+|x|), built by the same code:
     * {@code build.accept(bone, side)} with side -1 / +1. Authored as the right side.
     */
    public Bone[] pair(String name, float x, float y, float z, BiConsumer<Bone, Integer> build) {
        float ax = Math.abs(x);
        Bone right = new Bone(rig, name + "_right", this, -1, name, -ax, y, z);
        Bone left = new Bone(rig, name + "_left", this, 1, name, ax, y, z);
        rig.register(right);
        rig.register(left);
        children.add(right);
        children.add(left);
        build.accept(right, -1);
        build.accept(left, 1);
        return new Bone[]{right, left};
    }

    /** Rest rotation in degrees (y and z mirrored on the left side). */
    public Bone rot(float xDeg, float yDeg, float zDeg) {
        this.rx = xDeg;
        this.ry = side > 0 ? -yDeg : yDeg;
        this.rz = side > 0 ? -zDeg : zDeg;
        return this;
    }

    public Bone box(String mat, float x, float y, float z, int w, int h, int d) {
        return box(mat, x, y, z, w, h, d, 0f);
    }

    /** A box in this bone's space (x NOT mirrored; use {@link #sbox} inside side bones). */
    public Bone box(String mat, float x, float y, float z, int w, int h, int d, float inflate) {
        boxes.add(new Box(name + "#" + boxes.size(), mat, x, y, z, w, h, d, inflate, false, null));
        return this;
    }

    public Bone sbox(String mat, float x, float y, float z, int w, int h, int d) {
        return sbox(mat, x, y, z, w, h, d, 0f);
    }

    /**
     * A side-aware box: authored for the right side; on the left side its x range is mirrored, the texture flipped
     * and the UV area shared with the right box. On centre bones this is a plain box.
     */
    public Bone sbox(String mat, float x, float y, float z, int w, int h, int d, float inflate) {
        if (side == 0) return box(mat, x, y, z, w, h, d, inflate);
        String key = baseName + "#" + boxes.size();
        float bx = side > 0 ? -(x + w) : x;
        boxes.add(new Box(name + "#" + boxes.size(), mat, bx, y, z, w, h, d, inflate, side > 0, key));
        return this;
    }

    /** Tags the last added box for the texture painter. */
    public Bone tag(String tag) {
        if (!boxes.isEmpty()) boxes.get(boxes.size() - 1).tag = tag;
        return this;
    }

    /** Full name path (for error messages). */
    @Override
    public String toString() {
        return rig.name + ":" + name;
    }
}
