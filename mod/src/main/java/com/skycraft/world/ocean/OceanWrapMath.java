package com.skycraft.world.ocean;

import java.util.Optional;

/** Exact rectangular torus coordinates; no Minecraft, loading or mutable world state. */
public final class OceanWrapMath {
    public static final int MIN_HALF_WIDTH = 65_536;
    public static final int MAX_HALF_WIDTH = 29_360_128;
    public static final int DEFAULT_HALF_WIDTH = 29_360_128;
    public record Point(double x,double z) {}
    public record Bounds(int halfWidth,int prewarmDistance) {
        public Bounds {
            if(halfWidth<MIN_HALF_WIDTH || halfWidth>MAX_HALF_WIDTH) throw new IllegalArgumentException("Unsafe ocean half width");
            if(prewarmDistance<64 || prewarmDistance>4096) throw new IllegalArgumentException("Unsafe ocean arrival buffer");
        }
    }
    private OceanWrapMath() {}

    public static boolean outside(double value,Bounds bounds) {
        finite(value);return value < -bounds.halfWidth || value >= bounds.halfWidth;
    }
    public static double axis(double value,Bounds bounds) {
        finite(value);double h=bounds.halfWidth,period=2*h;
        if(value>=-h && value<h) return value;
        double result=value%period;
        // Remainder avoids overflowing the intermediate quotient*period for large finite inputs.
        return result>=h ? result-period : result < -h ? result+period : result;
    }
    public static Optional<Point> crossing(double x,double z,Bounds bounds) {
        finite(x);finite(z);
        return outside(x,bounds)||outside(z,bounds) ? Optional.of(new Point(axis(x,bounds),axis(z,bounds))) : Optional.empty();
    }
    /** Opposite seam entry, predicted before crossing; buffer affects prefetch timing, never arrival position. */
    public static Optional<Point> prefetch(double x,double z,Bounds bounds) {
        var crossed=crossing(x,z,bounds);if(crossed.isPresent()) return crossed;
        int h=bounds.halfWidth,b=bounds.prewarmDistance;
        boolean east=x>=h-b,west=x< -h+b,south=z>=h-b,north=z< -h+b;
        if(!east&&!west&&!south&&!north) return Optional.empty();
        return Optional.of(new Point(east?-h:west?Math.nextDown((double)h):x,
                south?-h:north?Math.nextDown((double)h):z));
    }
    private static void finite(double value) { if(!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite ocean coordinate"); }
}
