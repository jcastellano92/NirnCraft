package com.nirncraft.atmosphere;

/** Integer world-cell anchor for quarter-block local media packets, independent of render interpolation. */
public final class LocalAirCoordinates {
    private LocalAirCoordinates() {}

    public static int anchor(double position) {
        return (int) (Math.floor(position / 256.0) * 256.0);
    }

    public static int encode(double relative) {
        return Math.max(0, Math.min(65535, (int) Math.round(relative * 4) + 32768));
    }

    public static double decode(int encoded) {
        return (encoded - 32768) * 0.25;
    }
}
