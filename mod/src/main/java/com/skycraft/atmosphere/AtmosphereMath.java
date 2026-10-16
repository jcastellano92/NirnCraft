package com.skycraft.atmosphere;

/**
 * Pure mathematical helpers for continuous world-Z latitude, aurora elevation angles, curtain scaling,
 * star-dome tilt, and spatial atmospheric scattering weights. Safe to call on any thread or in Tier 0 unit tests
 * without initializing Minecraft client classes.
 */
public final class AtmosphereMath {
    /** Northern full-corona latitude anchor (Atmora, Z = -6500). */
    public static final double NORTH_MAX_Z = -6500.0;
    /** Southern aurora cutoff latitude anchor (southern Tamriel, Z = +6000). */
    public static final double SOUTH_ZERO_Z = 6000.0;

    private static final double[] KNOT_Z = {-6500.0, -2000.0, 0.0, 3500.0, 6000.0};
    private static final double[] KNOT_LAT = {1.00, 0.68, 0.45, 0.18, 0.00};
    /** Monotone tangents (dLat/dZ) at each knot ensuring C1 continuity and strict monotonicity. */
    private static final double[] KNOT_SLOPE = {
            -0.000045,
            -0.000093,
            -0.000096,
            -0.000074,
            -0.000035
    };

    private AtmosphereMath() {}

    /**
     * Continuous, strictly monotonic normalized latitude factor in {@code [0.0, 1.0]} from world {@code blockZ}:
     * <ul>
     *   <li>{@code blockZ <= -6500} (Atmora) &rarr; {@code 1.00}</li>
     *   <li>{@code blockZ == -2000} (central Skyrim) &rarr; {@code 0.68}</li>
     *   <li>{@code blockZ == 0} (Skyrim / Cyrodiil border) &rarr; {@code 0.45}</li>
     *   <li>{@code blockZ == +3500} (central Cyrodiil) &rarr; {@code 0.18}</li>
     *   <li>{@code blockZ >= +6000} (southern Tamriel) &rarr; {@code 0.00}</li>
     * </ul>
     */
    public static float latitudeFactor(double blockZ) {
        if (blockZ <= KNOT_Z[0]) return 1.0f;
        if (blockZ >= KNOT_Z[KNOT_Z.length - 1]) return 0.0f;
        int seg = 0;
        for (int i = 0; i < KNOT_Z.length - 1; i++) {
            if (blockZ < KNOT_Z[i + 1]) {
                seg = i;
                break;
            }
        }
        double z0 = KNOT_Z[seg], z1 = KNOT_Z[seg + 1];
        double h = z1 - z0;
        double t = (blockZ - z0) / h;
        double t2 = t * t, t3 = t2 * t;
        double h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
        double h10 = t3 - 2.0 * t2 + t;
        double h01 = -2.0 * t3 + 3.0 * t2;
        double h11 = t3 - t2;
        double val = h00 * KNOT_LAT[seg] + h10 * h * KNOT_SLOPE[seg]
                + h01 * KNOT_LAT[seg + 1] + h11 * h * KNOT_SLOPE[seg + 1];
        return (float) Math.max(0.0, Math.min(1.0, val));
    }

    /**
     * Lower edge elevation angle (in degrees above the northern {@code -Z} horizon) of the aurora curtains.
     * Shifts continuously from {@code 38°} in Atmora, to {@code ~19°..27°} in Skyrim, down to {@code 4°} at the
     * southern visibility limit.
     */
    public static float auroraElevationMinDeg(double blockZ) {
        return 4.0f + 34.0f * latitudeFactor(blockZ);
    }

    /**
     * Upper corona/fringe elevation angle (in degrees above the northern {@code -Z} horizon) of the aurora curtains.
     * Shifts continuously from {@code 76°} in Atmora, to {@code ~43°..56°} in Skyrim, down to {@code 16°} at the
     * southern visibility limit.
     */
    public static float auroraElevationMaxDeg(double blockZ) {
        return 16.0f + 60.0f * latitudeFactor(blockZ);
    }

    /**
     * Continuous base aurora visibility/alpha multiplier in {@code [0.0, 1.0]} from world {@code blockZ}.
     * Zero south of {@code Z = +6000}, rising smoothly northward to {@code 1.0} in Atmora.
     */
    public static float auroraAlphaFactor(double blockZ) {
        float lat = latitudeFactor(blockZ);
        if (lat <= 0.0f) return 0.0f;
        return lat * (0.35f + 0.65f * lat);
    }

    /**
     * Pitch offset in degrees applied to the reference northern aurora geometry so its elevation tracks
     * {@link #auroraElevationMinDeg(double)} .. {@link #auroraElevationMaxDeg(double)}.
     */
    public static float auroraPitchOffsetDeg(double blockZ) {
        return auroraElevationMinDeg(blockZ) - 20.0f;
    }

    /**
     * Vertical curtain scale factor applied to the reference aurora geometry so its angular span matches
     * {@code auroraElevationMaxDeg(blockZ) - auroraElevationMinDeg(blockZ)}.
     */
    public static float auroraHeightScale(double blockZ) {
        float span = auroraElevationMaxDeg(blockZ) - auroraElevationMinDeg(blockZ);
        return span / 24.0f;
    }

    /**
     * Subtle latitude tilt of the celestial star/constellation dome (degrees) from world {@code blockZ}.
     */
    public static float starDomeTiltDeg(double blockZ) {
        return (latitudeFactor(blockZ) - 0.5f) * 16.0f;
    }
}
