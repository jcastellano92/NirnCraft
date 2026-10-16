package com.skycraft.tamriel.terrain;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import org.jetbrains.annotations.Nullable;

/**
 * Deterministic procedural micro-islands, ring atolls, rocky sea stacks, and sandbar shoals across the open seas
 * ({@code coastDistance < -96}). Pure function of world coordinates and the static Tamriel coast raster, so terrain
 * generation ({@link TamrielHeight}) and maritime minor-site placement ({@link com.skycraft.explore.MinorSites})
 * agree on every island's exact dry centre and shape without modifying the mainland landmass raster.
 */
public final class OceanIsles {
    /** Grid spacing (blocks) of candidate ocean micro-island cells. */
    public static final int CELL = 512;
    /** Minimum distance from the mainland/raster coast for an island centre. */
    private static final float MIN_CENTER_COAST = -180f;
    private static final long SALT = 0x4F4345414E49534CL; // "OCEANISL"

    public static final int TYPE_SEA_STACK = 0;
    public static final int TYPE_ATOLL = 1;
    public static final int TYPE_CAY = 2;
    public static final int TYPE_SANDBAR = 3;

    /** Summary of a deterministic ocean micro-island inside one {@value #CELL}-block cell. */
    public record Isle(int cellX, int cellZ, int centerX, int centerZ, int siteX, int siteZ, int type, int radius, float peakAboveSea, long hash) {
    }

    private OceanIsles() {}

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long cellHash(int cx, int cz) {
        return mix(cx * 0x9E3779B97F4A7C15L ^ cz * 0xC2B2AE3D27D4EB4FL ^ SALT);
    }

    static double unit(long h, int k) {
        return (mix(h + k * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
    }

    /** Returns the micro-island for cell (cx, cz), or null if this cell has none. */
    @Nullable
    public static Isle cellIsle(int cx, int cz) {
        long h = cellHash(cx, cz);
        // ~40% of open-ocean cells contain a micro-island or shoal
        if (unit(h, 0) >= 0.40) return null;
        int ix = cx * CELL + 112 + (int) Math.round(unit(h, 1) * (CELL - 224));
        int iz = cz * CELL + 112 + (int) Math.round(unit(h, 2) * (CELL - 224));
        MapSample ms = new MapSample();
        TamrielMap.sample(ix, iz, ms);
        if (ms.coastDistance > MIN_CENTER_COAST) return null;
        if (!TamrielMap.inTamriel(ix, iz)) {
            // In Beyond outer-realm archipelagos, ensure the island perimeter stays clear of foreign island shores
            int[][] card = {{72, 0}, {-72, 0}, {0, 72}, {0, -72}};
            for (int[] o : card) {
                TamrielMap.sample(ix + o[0], iz + o[1], ms);
                if (ms.coastDistance > -96f) return null;
            }
        }
        if (!Places.overlapping(ix - 140, iz - 140, ix + 140, iz + 140).isEmpty()) return null;

        double tr = unit(h, 3);
        int type = tr < 0.24 ? TYPE_SEA_STACK : tr < 0.50 ? TYPE_ATOLL : tr < 0.80 ? TYPE_CAY : TYPE_SANDBAR;
        int radius = switch (type) {
            case TYPE_SEA_STACK -> 20 + (int) Math.round(unit(h, 4) * 14);
            case TYPE_ATOLL -> 36 + (int) Math.round(unit(h, 4) * 18);
            case TYPE_CAY -> 28 + (int) Math.round(unit(h, 4) * 18);
            default -> 22 + (int) Math.round(unit(h, 4) * 14);
        };
        float peak = switch (type) {
            case TYPE_SEA_STACK -> 11f + (float) (unit(h, 5) * 11.0);
            case TYPE_ATOLL -> 4.5f + (float) (unit(h, 5) * 3.0);
            case TYPE_CAY -> 7.0f + (float) (unit(h, 5) * 7.5);
            default -> 3.0f + (float) (unit(h, 5) * 2.5);
        };
        int sx = ix, sz = iz;
        if (type == TYPE_ATOLL) {
            double ang = unit(h, 6) * Math.PI * 2.0;
            double ringR = radius * 0.58;
            sx = ix + (int) Math.round(Math.cos(ang) * ringR);
            sz = iz + (int) Math.round(Math.sin(ang) * ringR);
        }
        return new Isle(cx, cz, ix, iz, sx, sz, type, radius, peak, h);
    }

    /**
     * Computes the micro-island surface elevation at (x, z) when {@code coastDistance < -96}, or {@link Double#NaN}
     * if no micro-island influences this column.
     */
    public static double surfaceHeight(int x, int z, double coastDistance, double hillsN, double detailN) {
        if (coastDistance >= -96.0) return Double.NaN;
        int cx = Math.floorDiv(x, CELL), cz = Math.floorDiv(z, CELL);
        Isle isle = cellIsle(cx, cz);
        if (isle == null) return Double.NaN;

        double dx = x - isle.centerX, dz = z - isle.centerZ;
        double r = isle.radius;
        double outer = r + 36.0;
        if (dx * dx + dz * dz > outer * outer) return Double.NaN;

        // Subtle organic shoreline warp from detailN/hillsN
        double ang = Math.atan2(dz, dx);
        double stretch = 1.0 + 0.18 * Math.cos(2 * ang + unit(isle.hash, 8) * 6.283);
        double d = Math.hypot(dx, dz) * stretch - detailN * 3.2 - hillsN * 2.5;
        if (d > outer) return Double.NaN;

        double sea = TamrielMap.SEA_LEVEL;
        return switch (isle.type) {
            case TYPE_SEA_STACK -> {
                // Steep rocky spire + secondary satellite stack
                double tMain = MapNoise.clamp(1.0 - d / r, 0.0, 1.0);
                double hMain = sea - 14.0 + (14.0 + isle.peakAboveSea) * Math.pow(tMain, 1.35) + Math.max(0, detailN) * 2.5 * tMain;
                double satAng = unit(isle.hash, 9) * Math.PI * 2.0;
                double sx = isle.centerX + Math.cos(satAng) * (r * 0.85);
                double sz = isle.centerZ + Math.sin(satAng) * (r * 0.85);
                double sd = Math.hypot(x - sx, z - sz) - detailN * 1.8;
                double tSat = MapNoise.clamp(1.0 - sd / (r * 0.48), 0.0, 1.0);
                double hSat = sea - 14.0 + (14.0 + isle.peakAboveSea * 0.7) * Math.pow(tSat, 1.4);
                yield Math.max(hMain, hSat);
            }
            case TYPE_ATOLL -> {
                // Ring atoll with a central shallow lagoon and a broader windward cay at (siteX, siteZ)
                double ringR = r * 0.58;
                double band = Math.abs(d - ringR);
                double ringW = r * 0.30;
                double tRing = MapNoise.clamp(1.0 - band / ringW, 0.0, 1.0);
                double hRing = d < ringR
                        ? sea - 2.2 + (2.2 + isle.peakAboveSea) * (tRing * tRing * (3 - 2 * tRing))
                        : sea - 14.0 + (14.0 + isle.peakAboveSea) * (tRing * tRing * (3 - 2 * tRing));
                double cdx = x - isle.siteX, cdz = z - isle.siteZ;
                double cdCay = Math.hypot(cdx, cdz) - detailN * 2.0;
                double tCay = MapNoise.clamp(1.0 - cdCay / (r * 0.42), 0.0, 1.0);
                double hCay = sea - 8.0 + (8.0 + isle.peakAboveSea + 1.5) * (tCay * tCay * (3 - 2 * tCay));
                yield Math.max(hRing, hCay);
            }
            case TYPE_CAY -> {
                // Gentle sandy beach rising to a flat vegetated crown
                if (d >= outer) yield Double.NaN;
                if (d > r) {
                    double shoal = MapNoise.smoothstep(outer, r, d);
                    yield sea - 16.0 + 16.0 * shoal;
                }
                double t = MapNoise.clamp(1.0 - d / r, 0.0, 1.0);
                double crown = t * t * (3.0 - 2.0 * t);
                yield sea + 0.5 + isle.peakAboveSea * crown + detailN * 0.8 * crown;
            }
            default -> {
                // Elongated sandbar & shoal chain
                double dir = unit(isle.hash, 10) * Math.PI;
                double cos = Math.cos(dir), sin = Math.sin(dir);
                double lx = dx * cos + dz * sin;
                double lz = -dx * sin + dz * cos - 0.012 * lx * lx;
                double ed = Math.hypot(lx * 0.62, lz * 1.25) - detailN * 2.2;
                if (ed > outer) yield Double.NaN;
                if (ed > r) {
                    double shoal = MapNoise.smoothstep(outer, r, ed);
                    yield sea - 14.0 + 14.0 * shoal;
                }
                double t = MapNoise.clamp(1.0 - ed / r, 0.0, 1.0);
                yield sea + 0.4 + isle.peakAboveSea * (t * t * (3.0 - 2.0 * t));
            }
        };
    }

    /**
     * If a {@link com.skycraft.explore.MinorSites} cell {@code (mcx, mcz)} (160x160 blocks) contains the dry anchor
     * of an ocean micro-island, returns {@code {siteX, siteZ, type}}; otherwise null.
     */
    @Nullable
    public static int[] siteInMinorCell(int mcx, int mcz, int minorCellSize) {
        int minX = mcx * minorCellSize + 12, maxX = mcx * minorCellSize + minorCellSize - 13;
        int minZ = mcz * minorCellSize + 12, maxZ = mcz * minorCellSize + minorCellSize - 13;
        int cx0 = Math.floorDiv(minX, CELL), cx1 = Math.floorDiv(maxX, CELL);
        int cz0 = Math.floorDiv(minZ, CELL), cz1 = Math.floorDiv(maxZ, CELL);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Isle isle = cellIsle(cx, cz);
                if (isle == null) continue;
                if (isle.siteX >= minX && isle.siteX <= maxX && isle.siteZ >= minZ && isle.siteZ <= maxZ) {
                    return new int[]{isle.siteX, isle.siteZ, isle.type};
                }
            }
        }
        return null;
    }
}

