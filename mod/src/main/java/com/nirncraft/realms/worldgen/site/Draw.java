package com.nirncraft.realms.worldgen.site;

import com.nirncraft.dungeons.world.Painter;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.IntFunction;

/**
 * Shape primitives on top of a chunk-clipped {@link Painter}: every loop runs only over the part of the shape the
 * current chunk owns, so even a 200-block tower costs each chunk just its own columns. Coordinates are absolute.
 * Palettes ({@link Pal}) pick weathered variants from position hashes, never from a random source, so the result
 * is the same whatever order chunks generate in.
 */
public final class Draw {
    public final Painter p;

    public Draw(Painter p) {
        this.p = p;
    }

    /** A block palette: base state plus position-hashed variants. */
    @FunctionalInterface
    public interface Pal {
        BlockState at(Painter p, int x, int y, int z);

        static Pal of(BlockState s) {
            return (p, x, y, z) -> s;
        }

        /** {@code base}, with {@code alt} one time in {@code oneIn}. */
        static Pal mix(BlockState base, BlockState alt, int oneIn) {
            return (p, x, y, z) -> p.chance(x, y, z, 17, oneIn) ? alt : base;
        }

        /** Weighted choice among states: weights[i] for states[i]. */
        static Pal weighted(BlockState[] states, int[] weights) {
            int total = 0;
            for (int w : weights) total += w;
            final int t = total;
            return (p, x, y, z) -> {
                int r = p.roll(x, y, z, 23, t);
                for (int i = 0; i < states.length; i++) {
                    r -= weights[i];
                    if (r < 0) return states[i];
                }
                return states[0];
            };
        }
    }

    // ------------------------------------------------------------------ clip helpers

    private int lx(int v) {
        return Math.max(v, p.minX());
    }

    private int hx(int v) {
        return Math.min(v, p.maxX());
    }

    private int lz(int v) {
        return Math.max(v, p.minZ());
    }

    private int hz(int v) {
        return Math.min(v, p.maxZ());
    }

    private int ly(int v) {
        return Math.max(v, p.minY());
    }

    private int hy(int v) {
        return Math.min(v, p.maxY());
    }

    // ------------------------------------------------------------------ boxes

    public void box(int x0, int y0, int z0, int x1, int y1, int z1, Pal pal) {
        for (int x = lx(Math.min(x0, x1)); x <= hx(Math.max(x0, x1)); x++)
            for (int z = lz(Math.min(z0, z1)); z <= hz(Math.max(z0, z1)); z++)
                for (int y = ly(Math.min(y0, y1)); y <= hy(Math.max(y0, y1)); y++) p.set(x, y, z, pal.at(p, x, y, z));
    }

    public void box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        p.fill(x0, y0, z0, x1, y1, z1, s);
    }

    public void air(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = lx(Math.min(x0, x1)); x <= hx(Math.max(x0, x1)); x++)
            for (int z = lz(Math.min(z0, z1)); z <= hz(Math.max(z0, z1)); z++)
                for (int y = ly(Math.min(y0, y1)); y <= hy(Math.max(y0, y1)); y++) p.air(x, y, z);
    }

    /** Hollow box walls (no floor or roof). */
    public void walls(int x0, int y0, int z0, int x1, int y1, int z1, Pal pal) {
        box(x0, y0, z0, x1, y1, z0, pal);
        box(x0, y0, z1, x1, y1, z1, pal);
        box(x0, y0, z0, x0, y1, z1, pal);
        box(x1, y0, z0, x1, y1, z1, pal);
    }

    // ------------------------------------------------------------------ round shapes

    /** Solid cylinder of radius r (block centres within r). */
    public void cylinder(int cx, int cz, double r, int y0, int y1, Pal pal) {
        int ir = (int) Math.ceil(r);
        double r2 = r * r + 0.25;
        for (int x = lx(cx - ir); x <= hx(cx + ir); x++)
            for (int z = lz(cz - ir); z <= hz(cz + ir); z++) {
                double dx = x - cx, dz = z - cz;
                if (dx * dx + dz * dz > r2) continue;
                for (int y = ly(y0); y <= hy(y1); y++) p.set(x, y, z, pal.at(p, x, y, z));
            }
    }

    /** Ring wall between radii rIn and rOut. */
    public void tube(int cx, int cz, double rOut, double rIn, int y0, int y1, Pal pal) {
        int ir = (int) Math.ceil(rOut);
        double o2 = rOut * rOut + 0.25, i2 = rIn * rIn + 0.25;
        for (int x = lx(cx - ir); x <= hx(cx + ir); x++)
            for (int z = lz(cz - ir); z <= hz(cz + ir); z++) {
                double dx = x - cx, dz = z - cz, d2 = dx * dx + dz * dz;
                if (d2 > o2 || d2 <= i2) continue;
                for (int y = ly(y0); y <= hy(y1); y++) p.set(x, y, z, pal.at(p, x, y, z));
            }
    }

    /** Clears a cylinder to air. */
    public void clearCylinder(int cx, int cz, double r, int y0, int y1) {
        int ir = (int) Math.ceil(r);
        double r2 = r * r + 0.25;
        for (int x = lx(cx - ir); x <= hx(cx + ir); x++)
            for (int z = lz(cz - ir); z <= hz(cz + ir); z++) {
                double dx = x - cx, dz = z - cz;
                if (dx * dx + dz * dz > r2) continue;
                for (int y = ly(y0); y <= hy(y1); y++) p.air(x, y, z);
            }
    }

    /** A cone / tapering spire from radius r0 at y0 to r1 at y1. */
    public void cone(int cx, int cz, double r0, double r1, int y0, int y1, Pal pal) {
        int ir = (int) Math.ceil(Math.max(r0, r1));
        for (int x = lx(cx - ir); x <= hx(cx + ir); x++)
            for (int z = lz(cz - ir); z <= hz(cz + ir); z++) {
                double dx = x - cx, dz = z - cz, d = Math.sqrt(dx * dx + dz * dz);
                for (int y = ly(y0); y <= hy(y1); y++) {
                    double t = y1 == y0 ? 0 : (y - y0) / (double) (y1 - y0);
                    double r = Mth.lerp(t, r0, r1);
                    if (d <= r + 0.35) p.set(x, y, z, pal.at(p, x, y, z));
                }
            }
    }

    /** Solid half-ellipsoid dome (or hollow shell of thickness {@code shell} when > 0). */
    public void dome(int cx, int cy, int cz, double rx, double ry, double rz, double shell, Pal pal) {
        int ix = (int) Math.ceil(rx), iz = (int) Math.ceil(rz), iy = (int) Math.ceil(ry);
        for (int x = lx(cx - ix); x <= hx(cx + ix); x++)
            for (int z = lz(cz - iz); z <= hz(cz + iz); z++)
                for (int y = ly(cy); y <= hy(cy + iy); y++) {
                    double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > 1.0) continue;
                    if (shell > 0) {
                        double ex = (x - cx) / Math.max(0.5, rx - shell), ey = (y - cy) / Math.max(0.5, ry - shell),
                                ez = (z - cz) / Math.max(0.5, rz - shell);
                        if (ex * ex + ey * ey + ez * ez < 1.0) continue;
                    }
                    p.set(x, y, z, pal.at(p, x, y, z));
                }
    }

    /** Full ellipsoid blob (boulders, crystal masses, floating rocks). */
    public void blob(int cx, int cy, int cz, double rx, double ry, double rz, Pal pal) {
        int ix = (int) Math.ceil(rx), iz = (int) Math.ceil(rz), iy = (int) Math.ceil(ry);
        for (int x = lx(cx - ix); x <= hx(cx + ix); x++)
            for (int z = lz(cz - iz); z <= hz(cz + iz); z++)
                for (int y = ly(cy - iy); y <= hy(cy + iy); y++) {
                    double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                    if (dx * dx + dy * dy + dz * dz <= 1.0) p.set(x, y, z, pal.at(p, x, y, z));
                }
    }

    /** A thick straight segment (bridges, ribs, claws, chains): spheres of radius r along the line. */
    public void line(double x0, double y0, double z0, double x1, double y1, double z1, double r, Pal pal) {
        double len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        int steps = Math.max(1, (int) Math.ceil(len * 2));
        int ir = (int) Math.ceil(r);
        int bx0 = (int) Math.floor(Math.min(x0, x1)) - ir, bx1 = (int) Math.ceil(Math.max(x0, x1)) + ir;
        int bz0 = (int) Math.floor(Math.min(z0, z1)) - ir, bz1 = (int) Math.ceil(Math.max(z0, z1)) + ir;
        if (bx1 < p.minX() || bx0 > p.maxX() || bz1 < p.minZ() || bz0 > p.maxZ()) return;
        double r2 = r * r + 0.2;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double cx = Mth.lerp(t, x0, x1), cy = Mth.lerp(t, y0, y1), cz = Mth.lerp(t, z0, z1);
            for (int x = lx((int) Math.floor(cx - r)); x <= hx((int) Math.ceil(cx + r)); x++)
                for (int z = lz((int) Math.floor(cz - r)); z <= hz((int) Math.ceil(cz + r)); z++)
                    for (int y = ly((int) Math.floor(cy - r)); y <= hy((int) Math.ceil(cy + r)); y++) {
                        double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
                        if (dx * dx + dy * dy + dz * dz <= r2) p.set(x, y, z, pal.at(p, x, y, z));
                    }
        }
    }

    /** A quadratic curve (claws, arches, ribs) from a through control point c to b, tapering from r0 to r1. */
    public void curve(double ax, double ay, double az, double cx, double cy, double cz, double bx, double by, double bz,
                      double r0, double r1, Pal pal) {
        int seg = 10;
        double px = ax, py = ay, pz = az;
        for (int i = 1; i <= seg; i++) {
            double t = i / (double) seg, u = 1 - t;
            double x = u * u * ax + 2 * u * t * cx + t * t * bx;
            double y = u * u * ay + 2 * u * t * cy + t * t * by;
            double z = u * u * az + 2 * u * t * cz + t * t * bz;
            line(px, py, pz, x, y, z, Mth.lerp((i - 0.5) / seg, r0, r1), pal);
            px = x;
            py = y;
            pz = z;
        }
    }

    // ------------------------------------------------------------------ ground

    /** Fills every column of a disc down from {@code topY} to the ground (at most {@code depth} blocks). */
    public void footing(int cx, int cz, double r, int topY, int depth, Pal pal) {
        int ir = (int) Math.ceil(r);
        double r2 = r * r + 0.25;
        for (int x = lx(cx - ir); x <= hx(cx + ir); x++)
            for (int z = lz(cz - ir); z <= hz(cz + ir); z++) {
                double dx = x - cx, dz = z - cz;
                if (dx * dx + dz * dz > r2) continue;
                column(x, z, topY, depth, pal);
            }
    }

    /** Fills every column of a box footprint down from {@code topY} to the ground. */
    public void footing(int x0, int z0, int x1, int z1, int topY, int depth, Pal pal) {
        for (int x = lx(Math.min(x0, x1)); x <= hx(Math.max(x0, x1)); x++)
            for (int z = lz(Math.min(z0, z1)); z <= hz(Math.max(z0, z1)); z++) column(x, z, topY, depth, pal);
    }

    /** One footing column: down from topY until solid natural ground (fluids are filled too). */
    public void column(int x, int z, int topY, int depth, Pal pal) {
        if (x < p.minX() || x > p.maxX() || z < p.minZ() || z > p.maxZ()) return;
        for (int y = topY; y > topY - depth && y >= p.minY(); y--) {
            if (y <= p.maxY()) {
                BlockState s = p.get(x, y, z);
                if (y < topY && Painter.ground(s) && s.getFluidState().isEmpty()) return;
                p.set(x, y, z, pal.at(p, x, y, z));
            }
        }
    }

    /** Clears air above a footprint (terrain, plants) from y0 to y1. */
    public void clearAbove(int x0, int z0, int x1, int z1, int y0, int y1) {
        air(x0, y0, z0, x1, y1, z1);
    }

    // ------------------------------------------------------------------ misc

    /** Sets a block if it is inside this chunk's part (pass-through for readability). */
    public void set(int x, int y, int z, BlockState s) {
        p.set(x, y, z, s);
    }

    /** A spiral ramp inside a round tower: a helical floor between radii rIn and rOut rising {@code pitch} per turn. */
    public void spiral(int cx, int cz, double rIn, double rOut, int y0, int y1, double pitch, double phase, Pal pal) {
        int ir = (int) Math.ceil(rOut);
        double o2 = rOut * rOut + 0.25, i2 = rIn * rIn;
        for (int x = lx(cx - ir); x <= hx(cx + ir); x++)
            for (int z = lz(cz - ir); z <= hz(cz + ir); z++) {
                double dx = x - cx, dz = z - cz, d2 = dx * dx + dz * dz;
                if (d2 > o2 || d2 < i2) continue;
                double a = Math.atan2(dz, dx) + phase;
                double base = y0 + (a / (Math.PI * 2) - Math.floor(a / (Math.PI * 2))) * pitch;
                for (double y = base; y <= y1; y += pitch) {
                    int yi = (int) Math.floor(y);
                    if (yi < y0) continue;
                    p.set(x, yi, z, pal.at(p, x, yi, z));
                    // headroom above the ramp
                    for (int h = 1; h <= 3; h++) if (yi + h < y1) p.air(x, yi + h, z);
                }
            }
    }

    /** Chest/barrel facing helper. */
    public static BlockState chest(Direction facing) {
        return Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing);
    }

    /** Runs {@code body} for i in [0, n) (readability in builders). */
    public static void repeat(int n, IntFunction<Void> body) {
        for (int i = 0; i < n; i++) body.apply(i);
    }
}
