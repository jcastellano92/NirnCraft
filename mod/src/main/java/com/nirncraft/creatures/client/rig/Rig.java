package com.nirncraft.creatures.client.rig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A creature model authored in plain Java: a tree of {@link Bone}s with boxes. UVs are packed automatically
 * ({@link #pack()}), so models are written as geometry only; the texture tools read the packed layout that
 * {@link RigDump} exports ({@code tools/textures/rigs/<name>.json}).
 *
 * <p>The root bone sits at (0, 24, 0): model y 24 is the ground, as in every vanilla model.</p>
 */
public final class Rig {
    public final String name;
    private final Bone root;
    private final Map<String, Bone> bones = new LinkedHashMap<>();
    private int texWidth;
    private int texHeight;
    private boolean packed;

    public Rig(String name) {
        this.name = name;
        this.root = new Bone(this, "root", null, 0, null, 0, 24, 0);
        bones.put("root", root);
    }

    public Bone root() {
        return root;
    }

    void register(Bone bone) {
        if (bones.putIfAbsent(bone.name, bone) != null) {
            throw new IllegalStateException("Duplicate bone name " + bone.name + " in rig " + name);
        }
    }

    public Bone bone(String name) {
        Bone b = bones.get(name);
        if (b == null) throw new IllegalArgumentException("No bone " + name + " in rig " + name);
        return b;
    }

    public boolean has(String name) {
        return bones.containsKey(name);
    }

    public Iterable<Bone> bones() {
        return bones.values();
    }

    public int texWidth() {
        pack();
        return texWidth;
    }

    public int texHeight() {
        pack();
        return texHeight;
    }

    /**
     * Assigns texture coordinates: shelf packing of every box's unfolded area (largest first) into the narrowest
     * power-of-two width that keeps the sheet at most as tall as it is wide. Deterministic for a given rig.
     */
    public synchronized Rig pack() {
        if (packed) return this;
        List<Box> unique = new ArrayList<>();
        Map<String, Box> byKey = new HashMap<>();
        List<Box> all = new ArrayList<>();
        for (Bone b : bones.values()) {
            for (Box box : b.boxes) {
                all.add(box);
                if (box.uvKey == null) {
                    unique.add(box);
                } else if (!byKey.containsKey(box.uvKey)) {
                    byKey.put(box.uvKey, box);
                    unique.add(box);
                }
            }
        }
        unique.sort(Comparator.comparingInt(Box::uvHeight).reversed().thenComparing(Comparator.comparingInt(Box::uvWidth).reversed()));
        int area = 0;
        int widest = 16;
        for (Box b : unique) {
            area += Math.max(1, b.uvWidth()) * Math.max(1, b.uvHeight());
            widest = Math.max(widest, b.uvWidth());
        }
        int width = 32;
        while (width < widest || width * width < area * 1.15) width *= 2;
        int height;
        while (true) {
            height = shelf(unique, width);
            if (height <= width) break;
            width *= 2;
        }
        int texH = 16;
        while (texH < height) texH *= 2;
        texWidth = width;
        texHeight = texH;
        for (Box box : all) {
            if (box.uvKey != null && box.u < 0) {
                Box src = byKey.get(box.uvKey);
                if (src.w != box.w || src.h != box.h || src.d != box.d) {
                    throw new IllegalStateException("Shared UV " + box.uvKey + " with different sizes in rig " + name);
                }
                box.u = src.u;
                box.v = src.v;
                box.sharedUv = true;
            }
        }
        packed = true;
        return this;
    }

    /** Places the boxes in rows of the given width; returns the used height. */
    private static int shelf(List<Box> boxes, int width) {
        int x = 0;
        int y = 0;
        int rowH = 0;
        for (Box b : boxes) {
            int w = b.uvWidth();
            int h = b.uvHeight();
            if (w == 0 || h == 0) {
                // flat planes still need a strip to paint on
                w = Math.max(w, 1);
                h = Math.max(h, 1);
            }
            if (x + w > width) {
                x = 0;
                y += rowH;
                rowH = 0;
            }
            b.u = x;
            b.v = y;
            x += w;
            rowH = Math.max(rowH, h);
        }
        return y + rowH;
    }

    /** The packed layout as JSON for the texture tools. */
    public String toJson() {
        pack();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"name\": \"").append(name).append("\",\n  \"width\": ").append(texWidth)
                .append(",\n  \"height\": ").append(texHeight).append(",\n  \"bones\": [\n");
        boolean firstBone = true;
        for (Bone bone : bones.values()) {
            if (!firstBone) sb.append(",\n");
            firstBone = false;
            sb.append(String.format(Locale.ROOT,
                    "    {\"name\": \"%s\", \"parent\": \"%s\", \"x\": %.3f, \"y\": %.3f, \"z\": %.3f, \"rx\": %.3f, \"ry\": %.3f, \"rz\": %.3f}",
                    bone.name, bone.parent == null ? "" : bone.parent.name, bone.x, bone.y, bone.z, bone.rx, bone.ry, bone.rz));
        }
        sb.append("\n  ],\n  \"boxes\": [\n");
        boolean first = true;
        for (Bone bone : bones.values()) {
            for (Box b : bone.boxes) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(String.format(Locale.ROOT,
                        "    {\"id\": \"%s\", \"bone\": \"%s\", \"mat\": \"%s\", \"tag\": \"%s\", \"u\": %d, \"v\": %d, \"w\": %d, \"h\": %d, \"d\": %d, \"mirror\": %s, \"shared\": %s, \"x\": %.3f, \"y\": %.3f, \"z\": %.3f, \"inflate\": %.3f}",
                        b.id, bone.name, b.mat, b.tag, b.u, b.v, b.w, b.h, b.d, b.mirror, b.sharedUv, b.x, b.y, b.z, b.inflate));
            }
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }
}
