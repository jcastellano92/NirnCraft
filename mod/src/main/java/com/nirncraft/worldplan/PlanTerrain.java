package com.nirncraft.worldplan;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.TamrielMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.List;

/**
 * The seed-independent terrain the road plan is made on: the Tamriel map's macro elevation and hydrology (rivers,
 * the derived streams, lakes, the sea) plus the site pads (S3): every place stands on a flat pad at the smoothed macro
 * ground, and the ground blends into it. Nothing here reads the world seed or the seeded detail noise. Immutable after
 * construction; {@link #sample} only writes the caller's objects, so it is safe from any thread.
 */
final class PlanTerrain {
    static final int SEA = TamrielMap.SEA_LEVEL;
    /** Site bucket (blocks). */
    private static final int SITE_SHIFT = 8;
    /** The ground blends from the pad to the macro height over this many blocks outside the pad radius. */
    static final float PAD_BLEND = 16f;

    /** One column. */
    static final class Col {
        /** Ground: the macro elevation (lake/river bed in water), flattened to a site pad nearby. */
        float ground;
        /** Water surface where the column is in water (river, stream, lake, sea), else NaN. */
        float water;
        /** Water surface of the nearest river or lake even on dry land (NaN when none is near). */
        float nearWater;
        /** Width of the channel (blocks) for a river or stream; large for lakes and the sea. */
        float waterWidth;
        boolean lake, sea;
        /** 0..1 road corridor hint from the map; true on a traced historic route. */
        float road;
        boolean corridor;
        /** Blocks into a lake (lakeDistance) / out to sea (-coastDistance); negative on land. */
        float lakeDepthIn, seaDepthIn;
        boolean land;
    }

    final String[] siteId;
    final byte[] siteType;
    final int[] siteX, siteZ, siteRadius;
    final float[] sitePad;
    /** Sites that flatten the ground (settlements, trail destinations). */
    final boolean[] sitePads;
    private final Long2ObjectOpenHashMap<int[]> siteGrid = new Long2ObjectOpenHashMap<>();

    PlanTerrain(List<Place> places, java.util.function.Predicate<Place> padded) {
        int n = places.size();
        siteId = new String[n];
        siteType = new byte[n];
        siteX = new int[n];
        siteZ = new int[n];
        siteRadius = new int[n];
        sitePad = new float[n];
        sitePads = new boolean[n];
        MapSample s = new MapSample();
        Long2ObjectOpenHashMap<IntArrayList> tmp = new Long2ObjectOpenHashMap<>();
        java.util.ArrayList<Long> order = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Place p = places.get(i);
            siteId[i] = p.id();
            siteType[i] = (byte) p.type().ordinal();
            siteX[i] = p.x();
            siteZ[i] = p.z();
            boolean settlement = p.type().isSettlement();
            int r = settlement ? Math.max(12, p.radius()) : Math.max(6, Math.min(24, p.radius()));
            siteRadius[i] = r;
            sitePad[i] = padHeight(p.x(), p.z(), r, s);
            sitePads[i] = padded.test(p);
            if (!sitePads[i]) continue;
            int reach = (int) Math.ceil(r + PAD_BLEND);
            for (int bx = (p.x() - reach) >> SITE_SHIFT; bx <= (p.x() + reach) >> SITE_SHIFT; bx++) {
                for (int bz = (p.z() - reach) >> SITE_SHIFT; bz <= (p.z() + reach) >> SITE_SHIFT; bz++) {
                    long k = key(bx, bz);
                    IntArrayList l = tmp.get(k);
                    if (l == null) {
                        tmp.put(k, l = new IntArrayList());
                        order.add(k);
                    }
                    l.add(i);
                }
            }
        }
        for (Long k : order) siteGrid.put(k.longValue(), tmp.get(k.longValue()).toIntArray());
    }

    private static long key(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xffffffffL);
    }

    /** Smoothed macro ground of a site: the mean elevation of the dry columns of its inner disc. */
    private static float padHeight(int x, int z, int r, MapSample s) {
        double sum = 0, wet = Double.NaN;
        int count = 0;
        int inner = Math.max(4, Math.round(r * 0.8f));
        int step = Math.max(2, Math.min(8, inner / 3));
        for (int dz = -inner; dz <= inner; dz += step) {
            for (int dx = -inner; dx <= inner; dx += step) {
                if (dx * dx + dz * dz > inner * inner) continue;
                TamrielMap.sample(x + dx, z + dz, s);
                boolean water = s.inWater() || s.coastDistance < 0 || s.region == 0;
                if (water) {
                    if (Double.isNaN(wet)) wet = Float.isNaN(s.waterLevel) ? SEA : s.waterLevel;
                    continue;
                }
                sum += s.elevation;
                count++;
            }
        }
        if (count > 0) return (float) (sum / count);
        return (float) (Double.isNaN(wet) ? SEA : wet) + 1f;
    }

    /** The column (x, z): the map sample plus the pad of the site it stands on. */
    void sample(int x, int z, MapSample s, Col c) {
        TamrielMap.sample(x, z, s);
        c.land = s.region != 0 && s.coastDistance >= 0;
        c.road = s.road;
        c.lakeDepthIn = s.lakeDistance;
        c.seaDepthIn = -s.coastDistance;
        c.nearWater = s.waterLevel;
        c.water = Float.NaN;
        c.lake = false;
        c.sea = false;
        c.waterWidth = 0f;
        c.ground = s.elevation;
        if (s.region == 0 || s.coastDistance < 0) {
            c.sea = true;
            c.water = SEA;
            c.waterWidth = 1e4f;
            c.nearWater = SEA;
        } else if (!Float.isNaN(s.waterLevel)) {
            // the seed-independent part of TamrielHeight's hydrology: lake basins and river channels below the water
            // surface, banks raised above it (the macro elevation alone is the smoothed valley floor)
            float wl = s.waterLevel, hw = s.riverHalfWidth;
            if (s.lakeDistance > 0) {
                c.ground = Math.min(c.ground, wl - Math.min(12f, 1.2f + s.lakeDistance * 0.25f));
            } else if (s.lakeDistance > -40) {
                c.ground = Math.max(c.ground, wl + 1f - s.lakeDistance * 0.04f);
            }
            if (hw > 0 && s.riverDistance < hw) {
                float depth = Math.max(hw < 4 ? 1f + 0.25f * hw : 1.5f, Math.min(5f, hw * 0.3f));
                float u = s.riverDistance / hw;
                c.ground = Math.min(c.ground, wl - 0.6f - depth * (1 - u * u));
            } else if (hw > 0 && s.riverDistance < hw + 6 + 10 * Math.max(0f, Math.min(1f, (hw - 1) / 3f)) && s.lakeDistance < -8) {
                c.ground = Math.max(c.ground, wl + 1f + (s.riverDistance - hw) * 0.05f);
            }
            if (s.inWater()) {
                c.water = wl;
                c.lake = s.lakeDistance > 0;
                c.waterWidth = c.lake ? 1e4f : 2f * hw;
            }
        }
        if (s.elevation < SEA - 0.5f && Float.isNaN(c.water) && s.coastDistance < 24) { // a tidal flat below sea level
            c.water = SEA;
            c.sea = true;
            c.waterWidth = 1e4f;
        }
        if (Float.isNaN(c.water)) c.ground = padded(x, z, c.ground);
    }

    /** The macro ground flattened towards the pads of nearby sites. */
    float padded(int x, int z, float ground) {
        int[] l = siteGrid.get(key(x >> SITE_SHIFT, z >> SITE_SHIFT));
        if (l == null) return ground;
        float bestW = 0f, pad = ground;
        for (int i : l) {
            float dx = x - siteX[i], dz = z - siteZ[i];
            float d = (float) Math.sqrt(dx * dx + dz * dz), r = siteRadius[i];
            if (d >= r + PAD_BLEND) continue;
            float w = d <= r ? 1f : 1f - (d - r) / PAD_BLEND;
            w = w * w * (3f - 2f * w);
            if (w > bestW) {
                bestW = w;
                pad = sitePad[i];
            }
        }
        return ground + (pad - ground) * bestW;
    }

    /** Index of the site with this id, or -1. */
    int site(String id) {
        for (int i = 0; i < siteId.length; i++) if (siteId[i].equals(id)) return i;
        return -1;
    }
}
