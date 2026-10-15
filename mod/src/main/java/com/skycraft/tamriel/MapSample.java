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
    /** Macro surface height in blocks (sea level is 63). Lakes report their bed. */
    public float elevation;
    /** 0..1 strengths. */
    public float ridge;
    public float river;
    public float lake;
    /** Distance to the nearest coast in blocks, negative at sea. */
    public float coastDistance;

    // ---- Detail used by the terrain density functions and the water feature (added in Phase 2) ----

    /** Water surface of the nearest lake or river in blocks, or {@link Float#NaN} when there is none nearby. */
    public float waterLevel;
    /** Blocks to the nearest river centre line (large when none is near) and that river's half width. */
    public float riverDistance;
    public float riverHalfWidth;
    /** Signed blocks to the nearest lake shore, positive inside the lake (very negative when none is near). */
    public float lakeDistance;
    /** Terrain character, 0..1: hill amplitude, cliffs/crags, dunes, wetland, and a road corridor. */
    public float roughness;
    public float crag;
    public float dunes;
    public float wetland;
    public float road;
    /** True inside the authored Tamriel raster, false in the foreign and generated lands. */
    public boolean inTamriel;
    /** On a sharp peak ({@code MapPeaks}): the amplitude of its crag detail in blocks (0 elsewhere). */
    public float peak;
    /** 0..1: how much of the seeded terrain detail is suppressed (a peak's ledges and climbing paths). */
    public float calm;
    /** Steepness of the macro elevation, blocks per block (0 where unknown, e.g. beyond Tamriel). */
    public float slope;
    /** 0..1 rainshadow strength (leeward of ridges along regional prevailing wind). */
    public float rainshadow;
    /** 0..1 geothermal heat strength (steam basins, hot springs, Dwemer vents, volcanic ashlands). */
    public float heat;
    /** Unit downslope aspect vector (-gradX / |grad|, -gradZ / |grad|); (0, 0) on flat ground. */
    public float aspectX;
    public float aspectZ;

    public void clear() {
        region = 0;
        blendRegion = 0;
        blendWeight = 0f;
        elevation = 0f;
        ridge = 0f;
        river = 0f;
        lake = 0f;
        coastDistance = 0f;
        waterLevel = Float.NaN;
        riverDistance = 10000f;
        riverHalfWidth = 0f;
        lakeDistance = -10000f;
        roughness = 0f;
        crag = 0f;
        dunes = 0f;
        wetland = 0f;
        road = 0f;
        inTamriel = false;
        peak = 0f;
        calm = 0f;
        slope = 0f;
        rainshadow = 0f;
        heat = 0f;
        aspectX = 0f;
        aspectZ = 0f;
    }

    /** Distance in blocks to the nearest fresh water (river bank or lake shore), clamped to 0..512. */
    public float waterDistance() {
        float rd = Math.max(0f, riverDistance - riverHalfWidth);
        float ld = lakeDistance >= 0f ? 0f : -lakeDistance;
        float d = Math.min(rd, ld);
        return d > 512f ? 512f : d;
    }

    /** True when the column is inside a lake or a river channel (where the map places water). */
    public boolean inWater() {
        if (Float.isNaN(waterLevel)) return false;
        return lakeDistance > 0 || riverDistance < riverHalfWidth;
    }
}
