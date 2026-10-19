package com.nirncraft.tamriel.settlement.grammar;

import com.nirncraft.tamriel.settlement.build.B;
import com.nirncraft.tamriel.settlement.build.Canvas;
import com.nirncraft.tamriel.settlement.build.Shells;
import com.nirncraft.tamriel.settlement.kit.Palette;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Roof painters of the building grammar. Every roof covers an inclusive local rectangle (eaves included) starting at
 * height {@code y}. Gables can leave out their front or back gable wall (a wing running into the main roof), and
 * take a pitch profile, so the same painter draws a Nord A-frame, a Breton slate roof and a barn's gambrel.
 */
public final class Roofs {
    private Roofs() {}

    /**
     * A gable roof. With {@code alongZ} the ridge runs front to back and the slopes face left and right; otherwise the
     * ridge runs along x. {@code gambrel}: steep lower courses, shallow upper ones. Returns the ridge height.
     */
    public static int gable(Canvas c, Palette p, int x0, int z0, int x1, int z1, int y, boolean alongZ, int pitch, boolean gambrel,
                            boolean endA, boolean endB, BlockState gableWall) {
        int a0 = alongZ ? x0 : z0, a1 = alongZ ? x1 : z1;
        int half = (a1 - a0) / 2;
        int[] hk = new int[half + 1];
        int yy = y;
        for (int k = 0; k <= half; k++) {
            int step = gambrel ? (k <= 2 ? 2 : 1) : pitch;
            if (k > 0) yy += step;
            hk[k] = yy;
            boolean ridge = k == half;
            int la = a0 + k, lb = a1 - k;
            for (int t = alongZ ? z0 : x0; t <= (alongZ ? z1 : x1); t++) {
                if (ridge && la == lb) {
                    put(c, alongZ, la, yy, t, B.slab(p.roofSlab, false));
                    put(c, alongZ, la, yy - 1, t, p.roofFull);
                } else {
                    put(c, alongZ, la, yy, t, B.stair(p.roof, alongZ ? Direction.EAST : Direction.SOUTH, false));
                    put(c, alongZ, lb, yy, t, B.stair(p.roof, alongZ ? Direction.WEST : Direction.NORTH, false));
                    for (int s = 1; s < step; s++) {
                        put(c, alongZ, la, yy - s, t, p.roofFull);
                        put(c, alongZ, lb, yy - s, t, p.roofFull);
                    }
                    if (ridge && lb == la + 1) {
                        // even span: a slab ridge cap over the pair
                        put(c, alongZ, la, yy + 1, t, B.slab(p.roofSlab, false));
                        put(c, alongZ, lb, yy + 1, t, B.slab(p.roofSlab, false));
                    }
                }
            }
        }
        // gable triangles at the ends, one in from the eaves (over the end walls)
        if (endA || endB) {
            for (int m = a0 + 1; m <= a1 - 1; m++) {
                int k = Math.min(half, Math.min(m - a0, a1 - m));
                for (int gy = y; gy < hk[k]; gy++) {
                    if (endA) put(c, alongZ, m, gy, alongZ ? z0 + 1 : x0 + 1, gableWall);
                    if (endB) put(c, alongZ, m, gy, alongZ ? z1 - 1 : x1 - 1, gableWall);
                }
            }
        }
        return hk[half];
    }

    /** Sets a block by (across the ridge, y, along the ridge) coordinates. */
    private static void put(Canvas c, boolean alongZ, int across, int y, int along, BlockState s) {
        if (alongZ) c.set(across, y, along, s);
        else c.set(along, y, across, s);
    }

    /** A hipped roof (four slopes) over the rectangle. Returns the ridge height. */
    public static int hip(Canvas c, Palette p, int x0, int z0, int x1, int z1, int y, int pitch) {
        Palette q = p;
        if (pitch != p.pitch) {
            q = p.copy();
            q.pitch = pitch;
        }
        Shells.hipRoof(c, q, x0, z0, x1, z1, y);
        return y + Math.min(x1 - x0, z1 - z0) / 2 * pitch;
    }

    /** A single-slope (shed) roof: high along the front edge, falling to the back. */
    public static int shed(Canvas c, Palette p, int x0, int z0, int x1, int z1, int y, BlockState wall) {
        int top = y;
        for (int z = z0; z <= z1; z++) {
            int hh = z1 - z;
            int yy = y + hh / 2;
            top = Math.max(top, yy);
            BlockState s = (hh & 1) == 1 ? B.slab(p.roofSlab, true) : B.slab(p.roofSlab, false);
            for (int x = x0; x <= x1; x++) c.set(x, yy, z, s);
            // close the side walls (and the front wall) up under the slope
            if (z > z0 && z < z1) {
                for (int yw = y; yw < yy; yw++) {
                    c.set(x0 + 1, yw, z, wall);
                    c.set(x1 - 1, yw, z, wall);
                    if (z == z0 + 1) for (int x = x0 + 1; x < x1; x++) c.set(x, yw, z, wall);
                }
            }
        }
        return top;
    }

    /** A flat roof over the given footprint mask with a parapet on its outer edge; crenellated when {@code crenel}. */
    public static void flat(Canvas c, Palette p, boolean[][] mask, int y, boolean crenel, boolean timberRail) {
        int w = mask.length, d = mask[0].length;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!mask[x][z]) continue;
                c.set(x, y, z, p.roofFull);
                boolean edge = !in(mask, x - 1, z) || !in(mask, x + 1, z) || !in(mask, x, z - 1) || !in(mask, x, z + 1)
                        || !in(mask, x - 1, z - 1) || !in(mask, x + 1, z - 1) || !in(mask, x - 1, z + 1) || !in(mask, x + 1, z + 1);
                if (!edge) continue;
                if (timberRail) c.set(x, y + 1, z, p.fence);
                else if (crenel) {
                    c.set(x, y + 1, z, p.wallLow);
                    if ((x + z) % 2 == 0) c.set(x, y + 2, z, p.wallLow);
                } else if ((x + z) % 2 == 0) c.set(x, y + 1, z, p.wallLow);
                else c.set(x, y + 1, z, B.slab(p.trimSlab, false));
            }
        }
        // overhanging cornice round the outside
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!mask[x][z]) continue;
                if (!in(mask, x, z - 1)) c.setIfOpen(x, y, z - 1, B.stair(p.trim, Direction.SOUTH, true));
                if (!in(mask, x, z + 1)) c.setIfOpen(x, y, z + 1, B.stair(p.trim, Direction.NORTH, true));
                if (!in(mask, x - 1, z)) c.setIfOpen(x - 1, y, z, B.stair(p.trim, Direction.EAST, true));
                if (!in(mask, x + 1, z)) c.setIfOpen(x + 1, y, z, B.stair(p.trim, Direction.WEST, true));
            }
        }
    }

    static boolean in(boolean[][] m, int x, int z) {
        return x >= 0 && z >= 0 && x < m.length && z < m[0].length && m[x][z];
    }

    /** A steep pyramid spire over a rectangle with a finial. Returns the tip height. */
    public static int spire(Canvas c, Palette p, int x0, int z0, int x1, int z1, int y, BlockState finial) {
        int k = 0;
        int yy = y;
        while (x0 + k <= x1 - k && z0 + k <= z1 - k) {
            int ax = x0 + k, bx = x1 - k, az = z0 + k, bz = z1 - k;
            for (int s = 0; s < 2; s++) {
                for (int x = ax; x <= bx; x++) {
                    c.set(x, yy + s, az, s == 0 ? B.stair(p.roof, Direction.SOUTH, false) : p.roofFull);
                    c.set(x, yy + s, bz, s == 0 ? B.stair(p.roof, Direction.NORTH, false) : p.roofFull);
                }
                for (int z = az; z <= bz; z++) {
                    c.set(ax, yy + s, z, s == 0 ? B.stair(p.roof, Direction.EAST, false) : p.roofFull);
                    c.set(bx, yy + s, z, s == 0 ? B.stair(p.roof, Direction.WEST, false) : p.roofFull);
                }
            }
            yy += 2;
            k++;
        }
        int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        c.set(cx, yy, cz, p.column);
        c.set(cx, yy + 1, cz, finial);
        return yy + 1;
    }

    /** A dome springing from {@code y} over the rectangle's inscribed circle. */
    public static int dome(Canvas c, Palette p, int x0, int z0, int x1, int z1, int y) {
        int r = Math.min(x1 - x0, z1 - z0) / 2;
        Shells.dome(c, p, (x0 + x1) / 2, y, (z0 + z1) / 2, r, p.roofFull);
        return y + r + 2;
    }

    /** A gabled dormer on a roof slope facing local {@code out}, its window at (x, y, z). */
    public static void dormer(Canvas c, Palette p, int x, int y, int z, Direction out, BlockState window, BlockState wall) {
        Direction side = out.getClockWise();
        int ox = out.getStepX(), oz = out.getStepZ(), sx = side.getStepX(), sz = side.getStepZ();
        // cheeks and window
        c.set(x, y, z, window);
        c.set(x + sx, y, z + sz, wall);
        c.set(x - sx, y, z - sz, wall);
        c.set(x, y - 1, z, wall);
        // little gable on top, running back into the roof
        for (int k = 0; k <= 1; k++) {
            int bx = x - ox * k, bz = z - oz * k;
            c.set(bx + sx, y + 1, bz + sz, B.stair(p.roof, side.getOpposite(), false));
            c.set(bx - sx, y + 1, bz - sz, B.stair(p.roof, side, false));
            c.set(bx, y + 1, bz, p.roofFull);
            c.set(bx, y + 2, bz, B.slab(p.roofSlab, false));
        }
        c.air(x - ox, y, z - oz);
    }

    /** Crossed "dragon horn" ridge beams over a gable end at (mid, ry, end). */
    public static void horns(Canvas c, Palette p, boolean alongZ, int mid, int ry, int end) {
        BlockState beam = B.axis(p.beam, Direction.Axis.Y);
        BlockState ridge = B.axis(p.beam, alongZ ? Direction.Axis.Z : Direction.Axis.X);
        if (alongZ) {
            c.set(mid, ry, end, p.roofFull);
            c.set(mid, ry + 1, end, ridge);
            c.set(mid - 1, ry + 1, end, beam);
            c.set(mid + 1, ry + 1, end, beam);
            c.set(mid - 1, ry + 2, end, B.s(Blocks.AIR));
        } else {
            c.set(end, ry, mid, p.roofFull);
            c.set(end, ry + 1, mid, ridge);
            c.set(end, ry + 1, mid - 1, beam);
            c.set(end, ry + 1, mid + 1, beam);
        }
    }

    /** A thatch copy of a palette (straw over a hay core). */
    public static Palette thatch(Palette p) {
        Palette t = p.copy();
        t.roof = B.s(Blocks.BAMBOO_MOSAIC_STAIRS);
        t.roofSlab = B.s(Blocks.BAMBOO_MOSAIC_SLAB);
        t.roofFull = B.s(Blocks.HAY_BLOCK);
        return t;
    }
}
