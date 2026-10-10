package com.skycraft.tamriel;

/**
 * Mutable result of {@link TamrielMap#sample}: reuse one instance per thread (or per call site) so sampling stays
 * allocation-free on worldgen threads.
 */
public final class MapSample {
    /** Index into the region table: authored regions are {@code >= 1}, 0 is open sea, negative is generated. */
    public int region;
    /** The second-nearest region at a border, and how much of it blends in (0 = none, 0.5 = on the border). */
    public int blendRegion;
    public float blendWeight;
    /** Macro surface height in blocks (sea level is 63). */
    public float elevation;
    /** 0..1 strengths. */
    public float ridge;
    public float river;
    public float lake;
    /** Distance to the nearest coast in blocks, negative at sea. */
    public float coastDistance;

    public void clear() {
        region = 0;
        blendRegion = 0;
        blendWeight = 0f;
        elevation = 0f;
        ridge = 0f;
        river = 0f;
        lake = 0f;
        coastDistance = 0f;
    }
}
