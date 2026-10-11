package com.skycraft.tamriel.settlement.build;

import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.kit.Shell;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Building shells: floors, walls, doors, windows and roofs of every {@link Shell} shape, drawn in a canvas's local
 * frame (front wall at z = 0, door in its middle). Returns the rooms the interior builders furnish.
 */
public final class Shells {
    private Shells() {}

    /** A floor's interior: inclusive local rectangle, the walking level {@code y} (one above the floor block) and the first blocked level. */
    public record Room(int x0, int z0, int x1, int z1, int y, int ceil) {
        public int w() {
            return x1 - x0 + 1;
        }

        public int d() {
            return z1 - z0 + 1;
        }

        public int cx() {
            return (x0 + x1) / 2;
        }

        public int cz() {
            return (z0 + z1) / 2;
        }
    }

    /** Shape parameters of one building. */
    public static final class Frame {
        public int w, d, wallH = 4, storeys = 1;
        public Shell shell = Shell.GABLE;
        /** Gable ridge from front to back (the gable end faces the street). */
        public boolean ridgeAlongZ = true;
        /** Raised on posts (stilts / water). */
        public boolean stilts;
        /** Thatched roof instead of the palette roof. */
        public boolean thatch;
        public boolean horns;
        public boolean chimney = true;
        /** Leave the left {@code openLeft} columns as an open lean-to (smithy yard). */
        public int openLeft;
        public boolean ruined;
    }

    public static List<Room> build(Canvas c, Palette p, Frame f) {
        return switch (f.shell) {
            case ROUND -> round(c, p, f);
            case TENT -> tent(c, p, f);
            case CHITIN -> chitin(c, p, f);
            case MUSHROOM -> mushroom(c, p, f);
            case TREE -> tree(c, p, f);
            case TOWER -> tower(c, p, f);
            case PAGODA -> pagoda(c, p, f);
            default -> rect(c, p, f);
        };
    }

    // ------------------------------------------------------------------ shared parts

    static BlockState wallBlock(Canvas c, Palette p, int x, int y, int z) {
        if (y <= 1) return p.wallLow;
        return c.chance(x, y, z, 11, 7) ? p.wall2 : p.wall;
    }

    /** Clears the building's volume and its eaves margin of terrain and plants. */
    static void clearVolume(Canvas c, int w, int d, int top, int margin) {
        if (c.recording()) return;
        c.clear(0, 1, 0, w - 1, top, d - 1);
        c.clearPlants(-margin, 1, -margin, w - 1 + margin, top + 2, d - 1 + margin);
    }

    /** Floor slab and foundation (solid fill or posts) under a w x d footprint. */
    static void base(Canvas c, Palette p, int w, int d, boolean stilts) {
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                c.set(x, 0, z, edge ? p.wallLow : p.floor);
                if (!stilts) {
                    c.foundation(x, -1, z, p.foundation, SettlementPieceDepth.DEPTH);
                } else if ((x == 0 || x == w - 1 || x % 3 == 0) && (z == 0 || z == d - 1 || z % 3 == 0)) {
                    c.foundation(x, -1, z, B.axis(p.post, Direction.Axis.Y), SettlementPieceDepth.DEPTH);
                }
            }
        }
        if (stilts) stilts(c, p, (w - 1) / 2);
    }

    /** Steps from a raised front door down to the ground. */
    static void stilts(Canvas c, Palette p, int doorX) {
        c.set(doorX, 0, -1, p.planks);
        c.set(doorX - 1, 0, -1, p.planks);
        c.set(doorX + 1, 0, -1, p.planks);
        c.set(doorX - 1, 1, -1, p.fence);
        c.set(doorX + 1, 1, -1, p.fence);
        if (c.recording()) return;
        for (int k = 1; k <= 8; k++) {
            int y = -k, z = -1 - k;
            if (Canvas.solidGround(c.get(doorX, y, z))) break;
            c.set(doorX, y, z, B.stair(p.stairs, Direction.SOUTH, false));
            c.foundation(doorX, y - 1, z, B.axis(p.post, Direction.Axis.Y), 3);
        }
    }

    static void door(Canvas c, Palette p, int x, int z) {
        c.door(x, 1, z, p.door, Direction.SOUTH);
    }

    /** Window openings along a wall segment (local x or z fixed), every third block, avoiding the ends. */
    static void windows(Canvas c, Palette p, int wallH, int fy, int ax, int az, int bx, int bz, int skipX, int skipZ) {
        boolean alongX = az == bz;
        int len = alongX ? Math.abs(bx - ax) : Math.abs(bz - az);
        for (int i = 2; i <= len - 2; i += 3) {
            int x = alongX ? Math.min(ax, bx) + i : ax;
            int z = alongX ? az : Math.min(az, bz) + i;
            if (Math.abs(x - skipX) <= 1 && Math.abs(z - skipZ) <= 1) continue;
            c.set(x, fy + 2, z, p.window);
            if (wallH >= 5) c.set(x, fy + 3, z, p.window);
        }
    }

    // ------------------------------------------------------------------ rectangular (gable, hip, flat, dome)

    static List<Room> rect(Canvas c, Palette p, Frame f) {
        int w = f.w, d = f.d;
        int floorH = f.wallH + 1;
        int wallTop = f.wallH * f.storeys + (f.storeys - 1);
        int roofH = f.shell == Shell.FLAT ? 2 : f.shell == Shell.DOME ? Math.min(w, d) / 2 + 2 : (Math.max(w, d) / 2 + 2) * p.pitch;
        clearVolume(c, w, d, wallTop + roofH + 1, 2);
        base(c, p, w, d, f.stilts);
        int x0 = Math.max(0, f.openLeft);
        int doorX = (x0 + w - 1) / 2;
        // walls
        for (int y = 1; y <= wallTop; y++) {
            for (int x = x0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = x == x0 || z == 0 || x == w - 1 || z == d - 1;
                    if (!edge) {
                        c.air(x, y, z);
                        continue;
                    }
                    boolean corner = (x == x0 || x == w - 1) && (z == 0 || z == d - 1);
                    boolean post = p.timber && (corner || (z == 0 || z == d - 1) && (x - x0) % 4 == 0 || (x == x0 || x == w - 1) && z % 4 == 0);
                    BlockState s;
                    if (corner && !p.timber) s = p.wallLow;
                    else if (post) s = B.axis(p.post, Direction.Axis.Y);
                    else if (p.timber && (y % floorH == f.wallH % floorH) && y > 1) s = B.axis(p.beam, z == 0 || z == d - 1 ? Direction.Axis.X : Direction.Axis.Z);
                    else if (isLogWall(p)) s = B.axis(p.wall, z == 0 || z == d - 1 ? Direction.Axis.X : Direction.Axis.Z);
                    else s = wallBlock(c, p, x, y, z);
                    if (f.ruined && y > 2 && c.chance(x, y, z, 5, 3 + (wallTop - y))) continue;
                    c.set(x, y, z, s);
                }
            }
        }
        // upper floors
        List<Room> rooms = new ArrayList<>();
        for (int k = 0; k < f.storeys; k++) {
            int fy = k * floorH;
            if (k > 0) {
                c.fill(x0 + 1, fy, 1, w - 2, fy, d - 2, p.floor2);
                // stair well along the left inner wall, climbing towards the back
                int sz = d - 2 - f.wallH;
                for (int i = 0; i < f.wallH; i++) {
                    c.set(x0 + 1, fy - f.wallH + i, sz + i, B.stair(p.stairs, Direction.SOUTH, false));
                    if (i > 0) c.air(x0 + 1, fy, sz + i - 1);
                }
            }
            int rx0 = f.storeys > 1 ? x0 + 2 : x0 + 1;
            rooms.add(new Room(rx0, 1, w - 2, d - 2, fy + 1, fy + f.wallH + 1));
            windows(c, p, f.wallH, fy, w - 1, 0, w - 1, d - 1, -9, -9);
            if (x0 == 0) windows(c, p, f.wallH, fy, 0, 0, 0, d - 1, -9, -9);
            windows(c, p, f.wallH, fy, x0, d - 1, w - 1, d - 1, w - 3, d - 1);
            if (k > 0) windows(c, p, f.wallH, fy, x0, 0, w - 1, 0, -9, -9);
        }
        // front: door and windows either side
        door(c, p, doorX, 0);
        if (f.wallH >= 4) c.set(doorX, 3, 0, p.timber ? B.axis(p.beam, Direction.Axis.X) : B.stair(p.trim, Direction.NORTH, true));
        windows(c, p, f.wallH, 0, x0, 0, w - 1, 0, doorX, 0);
        // roof
        int top = wallTop + 1;
        switch (f.shell) {
            case HIP -> hipRoof(c, p, x0 - 1, -1, w, d, top);
            case FLAT -> flatRoof(c, p, x0, 0, w - 1, d - 1, top);
            case DOME -> {
                flatRoof(c, p, x0, 0, w - 1, d - 1, top);
                dome(c, p, (x0 + w - 1) / 2, top, (d - 1) / 2, Math.min(w - x0, d) / 2 - 1, p.roofFull);
            }
            default -> gableRoof(c, p, x0 - 1, -1, w, d, top, f.ridgeAlongZ, f.thatch, f.horns);
        }
        if (f.openLeft > 0) leanTo(c, p, f.openLeft, d, f.wallH);
        if (f.ruined) {
            // fallen-in roof: whole rafters missing, snow and rubble inside
            for (int x = x0 - 1; x <= w; x++) {
                for (int z = -1; z <= d; z++) {
                    if (!c.chance(x / 2, 0, z / 2, 31, 2)) continue;
                    for (int y = top; y <= top + roofH; y++) c.air(x, y, z);
                    if (x > x0 && x < w - 1 && z > 0 && z < d - 1 && c.chance(x, 1, z, 33, 3)) c.set(x, 1, z, B.s(Blocks.COBBLESTONE_SLAB));
                }
            }
            return rooms;
        }
        if (f.chimney && f.shell != Shell.FLAT && f.shell != Shell.DOME) {
            int cx = w - 3, cz = d - 1;
            for (int y = 1; y <= top + roofHeight(w, d, p, f.ridgeAlongZ) - 1; y++) c.set(cx, y, cz, p.foundation);
            c.set(cx, top + roofHeight(w, d, p, f.ridgeAlongZ), cz, B.s(Blocks.COBBLESTONE_WALL));
        }
        return rooms;
    }

    static boolean isLogWall(Palette p) {
        return p.wall.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS);
    }

    static int roofHeight(int w, int d, Palette p, boolean alongZ) {
        int span = alongZ ? w : d;
        return ((span + 1) / 2) * p.pitch;
    }

    /**
     * Gable roof over the local rectangle [x0, x1] x [z0, z1] (eaves included), starting at {@code y}. With the ridge
     * along z the slopes face left and right; the gable triangles are walled in.
     */
    public static void gableRoof(Canvas c, Palette p, int x0, int z0, int x1, int z1, int y, boolean alongZ, boolean thatch, boolean horns) {
        if (thatch) {
            // straw thatch: woven stairs over a hay core
            Palette t = p.copy();
            t.roof = B.s(Blocks.BAMBOO_MOSAIC_STAIRS);
            t.roofSlab = B.s(Blocks.BAMBOO_MOSAIC_SLAB);
            t.roofFull = B.s(Blocks.HAY_BLOCK);
            gableRoof(c, t, x0, z0, x1, z1, y, alongZ, false, horns);
            return;
        }
        int span = alongZ ? x1 - x0 : z1 - z0;
        int half = span / 2;
        BlockState roof = p.roof;
        for (int k = 0; k <= half; k++) {
            int yy = y + k * p.pitch;
            boolean ridge = k == half;
            for (int t = alongZ ? z0 : x0; t <= (alongZ ? z1 : x1); t++) {
                if (alongZ) {
                    int la = x0 + k, lb = x1 - k;
                    if (ridge && la == lb) {
                        c.set(la, yy, t, thatch ? roof : B.slab(p.roofSlab, false));
                        c.set(la, yy - 1, t, thatch ? roof : p.roofFull);
                    } else {
                        c.set(la, yy, t, thatch ? roof : B.stair(roof, Direction.EAST, false));
                        c.set(lb, yy, t, thatch ? roof : B.stair(roof, Direction.WEST, false));
                        if (p.pitch > 1) {
                            c.set(la, yy - 1, t, p.roofFull);
                            c.set(lb, yy - 1, t, p.roofFull);
                        }
                    }
                } else {
                    int la = z0 + k, lb = z1 - k;
                    if (ridge && la == lb) {
                        c.set(t, yy, la, thatch ? roof : B.slab(p.roofSlab, false));
                        c.set(t, yy - 1, la, thatch ? roof : p.roofFull);
                    } else {
                        c.set(t, yy, la, thatch ? roof : B.stair(roof, Direction.SOUTH, false));
                        c.set(t, yy, lb, thatch ? roof : B.stair(roof, Direction.NORTH, false));
                        if (p.pitch > 1) {
                            c.set(t, yy - 1, la, p.roofFull);
                            c.set(t, yy - 1, lb, p.roofFull);
                        }
                    }
                }
            }
            // gable walls between the slopes at both ends
            if (k > 0) {
                for (int yy2 = y + (k - 1) * p.pitch; yy2 < yy; yy2++) {
                    for (int m = (alongZ ? x0 : z0) + k; m <= (alongZ ? x1 : z1) - k; m++) {
                        BlockState gw = p.timber && (m - (alongZ ? x0 : z0)) % 3 == 0 ? B.axis(p.post, Direction.Axis.Y) : p.wall;
                        if (isLogWall(p)) gw = B.axis(p.wall, alongZ ? Direction.Axis.X : Direction.Axis.Z);
                        if (alongZ) {
                            c.set(m, yy2, z0 + 1, gw);
                            c.set(m, yy2, z1 - 1, gw);
                        } else {
                            c.set(x0 + 1, yy2, m, gw);
                            c.set(x1 - 1, yy2, m, gw);
                        }
                    }
                }
            }
        }
        if (p.pitch == 1 && half > 0) {
            // gable walls for the lowest course (covers the top plate gap)
            for (int m = (alongZ ? x0 : z0) + 1; m <= (alongZ ? x1 : z1) - 1; m++) {
                if (alongZ) {
                    c.set(m, y, z0 + 1, p.wall);
                    c.set(m, y, z1 - 1, p.wall);
                } else {
                    c.set(x0 + 1, y, m, p.wall);
                    c.set(x1 - 1, y, m, p.wall);
                }
            }
        }
        if (horns) {
            // crossed ridge beams ("dragon horns") over both gable ends
            int ry = y + half * p.pitch;
            int mid = alongZ ? (x0 + x1) / 2 : (z0 + z1) / 2;
            BlockState beam = B.axis(p.beam, Direction.Axis.Y);
            if (alongZ) {
                c.set(mid - 1, ry + 1, z0, beam);
                c.set(mid + 1, ry + 1, z0, beam);
                c.set(mid - 1, ry + 1, z1, beam);
                c.set(mid + 1, ry + 1, z1, beam);
                c.set(mid, ry, z0, p.roofFull);
                c.set(mid, ry, z1, p.roofFull);
            } else {
                c.set(x0, ry + 1, mid - 1, beam);
                c.set(x0, ry + 1, mid + 1, beam);
                c.set(x1, ry + 1, mid - 1, beam);
                c.set(x1, ry + 1, mid + 1, beam);
                c.set(x0, ry, mid, p.roofFull);
                c.set(x1, ry, mid, p.roofFull);
            }
        }
    }

    /** Hipped roof over [x0, x1] x [z0, z1] (eaves included). */
    public static void hipRoof(Canvas c, Palette p, int x0, int z0, int x1, int z1, int y) {
        for (int k = 0; ; k++) {
            int ax = x0 + k, bx = x1 - k, az = z0 + k, bz = z1 - k;
            int yy = y + k * p.pitch;
            if (ax > bx || az > bz) break;
            if (ax == bx || az == bz) {
                c.fill(ax, yy, az, bx, yy, bz, B.slab(p.roofSlab, false));
                c.fill(ax, yy - 1, az, bx, yy - 1, bz, p.roofFull);
                break;
            }
            for (int x = ax; x <= bx; x++) {
                c.set(x, yy, az, B.stair(p.roof, Direction.SOUTH, false));
                c.set(x, yy, bz, B.stair(p.roof, Direction.NORTH, false));
                if (p.pitch > 1) {
                    c.set(x, yy - 1, az, p.roofFull);
                    c.set(x, yy - 1, bz, p.roofFull);
                }
            }
            for (int z = az + 1; z < bz; z++) {
                c.set(ax, yy, z, B.stair(p.roof, Direction.EAST, false));
                c.set(bx, yy, z, B.stair(p.roof, Direction.WEST, false));
                if (p.pitch > 1) {
                    c.set(ax, yy - 1, z, p.roofFull);
                    c.set(bx, yy - 1, z, p.roofFull);
                }
            }
            c.set(ax, yy, az, p.roofFull);
            c.set(bx, yy, az, p.roofFull);
            c.set(ax, yy, bz, p.roofFull);
            c.set(bx, yy, bz, p.roofFull);
            // fill under this ring so no gaps show
            if (k > 0) {
                for (int x = ax; x <= bx; x++) {
                    for (int yy2 = y + (k - 1) * p.pitch; yy2 < yy; yy2++) {
                        c.set(x, yy2, az, p.roofFull);
                        c.set(x, yy2, bz, p.roofFull);
                    }
                }
            }
        }
    }

    /** Flat roof over the walls with a parapet (crenellated stone, or a wooden rail for timber styles). */
    public static void flatRoof(Canvas c, Palette p, int x0, int z0, int x1, int z1, int y) {
        c.fill(x0, y, z0, x1, y, z1, p.roofFull);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || z == z0 || x == x1 || z == z1;
                if (!edge) continue;
                if (p.timber) c.set(x, y + 1, z, p.fence);
                else if ((x + z) % 2 == 0) c.set(x, y + 1, z, p.wallLow);
                else c.set(x, y + 1, z, B.slab(p.trimSlab, false));
            }
        }
        // overhanging cornice
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            c.set(x, y, z0 - 1, B.stair(p.trim, Direction.SOUTH, true));
            c.set(x, y, z1 + 1, B.stair(p.trim, Direction.NORTH, true));
        }
        for (int z = z0; z <= z1; z++) {
            c.set(x0 - 1, y, z, B.stair(p.trim, Direction.EAST, true));
            c.set(x1 + 1, y, z, B.stair(p.trim, Direction.WEST, true));
        }
    }

    /** A dome of {@code s} centred on (cx, cz), springing at {@code y}, radius {@code r}. */
    public static void dome(Canvas c, Palette p, int cx, int y, int cz, int r, BlockState s) {
        if (r < 1) return;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.5) continue;
                int hgt = (int) Math.round(Math.sqrt(Math.max(0, (r + 0.5) * (r + 0.5) - dist * dist)));
                for (int k = 0; k <= hgt; k++) {
                    double inner = Math.sqrt(dx * dx + dz * dz + k * k);
                    if (inner >= r - 0.9) c.set(cx + dx, y + k, cz + dz, s);
                    else c.air(cx + dx, y + k, cz + dz);
                }
            }
        }
        c.set(cx, y + r + 1, cz, p.column);
        c.set(cx, y + r + 2, cz, B.s(Blocks.LIGHTNING_ROD));
    }

    /** Open lean-to over the first {@code width} columns (a smithy's forge yard). */
    static void leanTo(Canvas c, Palette p, int width, int d, int wallH) {
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < width; x++) {
                c.set(x, 0, z, p.foundation);
                c.foundation(x, -1, z, p.foundation, SettlementPieceDepth.DEPTH);
                for (int y = 1; y < wallH; y++) c.air(x, y, z);
            }
        }
        for (int z = 0; z < d; z += Math.max(1, (d - 1) / 2)) {
            for (int y = 1; y < wallH; y++) c.set(0, y, z, B.axis(p.post, Direction.Axis.Y));
        }
        for (int z = -1; z <= d; z++) {
            for (int x = -1; x < width; x++) {
                int yy = wallH - 1 + (x + 1) / 2;
                c.set(x, yy, z, (x & 1) == 0 ? B.slab(p.roofSlab, true) : B.slab(p.roofSlab, false));
            }
        }
    }

    // ------------------------------------------------------------------ round huts

    static List<Room> round(Canvas c, Palette p, Frame f) {
        int r = Math.min(f.w, f.d) / 2;
        int cx = (f.w - 1) / 2, cz = (f.d - 1) / 2;
        int wallH = f.wallH;
        clearVolume(c, f.w, f.d, wallH + r + 3, 2);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.4) continue;
                int x = cx + dx, z = cz + dz;
                c.set(x, 0, z, dist > r - 0.6 ? p.wallLow : p.floor);
                if (f.stilts) {
                    if ((dx % 3 == 0 && dz % 3 == 0) || dist > r - 0.6 && (dx == 0 || dz == 0)) {
                        c.foundation(x, -1, z, B.axis(p.post, Direction.Axis.Y), SettlementPieceDepth.DEPTH);
                    }
                } else {
                    c.foundation(x, -1, z, p.foundation, SettlementPieceDepth.DEPTH);
                }
                for (int y = 1; y <= wallH; y++) {
                    if (dist > r - 0.6) {
                        boolean post = (dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz)) && y < wallH;
                        c.set(x, y, z, post ? B.axis(p.post, Direction.Axis.Y) : wallBlock(c, p, x, y, z));
                    } else {
                        c.air(x, y, z);
                    }
                }
            }
        }
        if (f.stilts) stilts(c, p, cx);
        // cone roof
        BlockState thatch = f.thatch || p.thatch ? B.s(Blocks.HAY_BLOCK) : p.roofFull;
        for (int k = 0; k <= r + 1; k++) {
            double rr = r + 1 - k;
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                for (int dz = -r - 1; dz <= r + 1; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist <= rr + 0.4 && dist > rr - 1.1) c.set(cx + dx, wallH + 1 + k, cz + dz, thatch);
                    else if (dist <= rr - 1.1) c.air(cx + dx, wallH + 1 + k, cz + dz);
                }
            }
        }
        c.set(cx, wallH + r + 3, cz, B.axis(p.post, Direction.Axis.Y));
        c.door(cx, 1, cz - r, p.door, Direction.SOUTH);
        for (int i = 0; i < 4; i++) {
            Direction dir = Direction.from2DDataValue(i);
            if (dir == Direction.NORTH) continue;
            c.set(cx + dir.getStepX() * r, 2, cz + dir.getStepZ() * r, p.window);
        }
        int in = (int) Math.floor(r / Math.sqrt(2));
        List<Room> rooms = new ArrayList<>();
        rooms.add(new Room(cx - in + 1, cz - in + 1, cx + in - 1, cz + in - 1, 1, wallH + 1));
        return rooms;
    }

    // ------------------------------------------------------------------ tents

    static List<Room> tent(Canvas c, Palette p, Frame f) {
        int w = f.w, d = f.d;
        int half = (w - 1) / 2;
        clearVolume(c, w, d, half + 3, 1);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                c.set(x, 0, z, p.path2);
                c.foundation(x, -1, z, p.foundation, 6);
            }
        }
        BlockState[] cloth = {p.accent, B.s(Blocks.RED_WOOL), B.s(Blocks.YELLOW_WOOL), B.s(Blocks.ORANGE_WOOL)};
        BlockState stripe = cloth[c.roll(0, 0, 0, 3, cloth.length)];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int yTop = 1 + Math.min(x, w - 1 - x);
                for (int y = 1; y <= half + 1; y++) {
                    if (y == yTop) c.set(x, y, z, (z % 2 == 0) ? stripe : p.accent);
                    else if (y < yTop) c.air(x, y, z);
                }
            }
        }
        for (int z = -1; z <= d; z += d + 1) {
            c.set(half, 1, z, B.axis(p.post, Direction.Axis.Y));
            c.set(half, 2, z, p.fence);
        }
        for (int y = 1; y <= half + 1; y++) c.air(half, y, 0);
        c.set(half, half + 2, 0, p.fence);
        List<Room> rooms = new ArrayList<>();
        rooms.add(new Room(1, 1, w - 2, d - 1, 1, 3));
        return rooms;
    }

    // ------------------------------------------------------------------ Redoran chitin hulls

    static List<Room> chitin(Canvas c, Palette p, Frame f) {
        int w = f.w, d = f.d;
        double a = (w - 1) / 2.0;
        int hgt = f.wallH + 3;
        int cx = (w - 1) / 2;
        clearVolume(c, w, d, hgt + 2, 2);
        base(c, p, w, d, f.stilts);
        for (int z = 0; z < d; z++) {
            // the ends taper like a beetle's shell
            double endT = Math.min(z, d - 1 - z);
            double shrink = endT < 2 ? (2 - endT) * 0.8 : 0;
            for (int x = 0; x < w; x++) {
                double nx = (x - a) / (a + 0.5 - shrink * 0.3);
                for (int y = 1; y <= hgt; y++) {
                    double ny = y / (hgt + 0.5 - shrink);
                    double v = nx * nx + ny * ny;
                    boolean shellBlock = v <= 1.0 && v > 0.62 || (z == 0 || z == d - 1) && v <= 1.0;
                    boolean rib = z % 3 == 1 && v <= 1.0 && v > 0.62;
                    if (shellBlock) {
                        c.set(x, y, z, rib ? B.axis(p.beam, Direction.Axis.Y) : (y <= 1 ? p.wallLow : (y > hgt / 2 ? p.roofFull : p.wall)));
                    } else if (v <= 1.0) {
                        c.air(x, y, z);
                    }
                }
            }
        }
        // ridge spine
        for (int z = 1; z < d - 1; z++) c.set(cx, hgt + 1, z, B.slab(p.roofSlab, false));
        door(c, p, cx, 0);
        c.air(cx, 3, 0);
        c.set(cx, 3, 0, B.slab(p.trimSlab, true));
        for (int z = 2; z < d - 2; z += 3) {
            c.set(0, 2, z, p.window);
            c.set(w - 1, 2, z, p.window);
        }
        List<Room> rooms = new ArrayList<>();
        rooms.add(new Room(2, 1, w - 3, d - 2, 1, f.wallH + 1));
        return rooms;
    }

    // ------------------------------------------------------------------ Telvanni mushroom towers

    static List<Room> mushroom(Canvas c, Palette p, Frame f) {
        int r = Math.max(2, Math.min(f.w, f.d) / 2 - 1);
        int cx = (f.w - 1) / 2, cz = (f.d - 1) / 2;
        int stemH = f.wallH * f.storeys + 3;
        int capR = r + 3;
        clearVolume(c, f.w, f.d, stemH + 6, capR - r + 1);
        BlockState cap = p.roofFull;
        BlockState under = B.s(Blocks.BROWN_MUSHROOM_BLOCK);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.4) continue;
                int x = cx + dx, z = cz + dz;
                c.set(x, 0, z, dist > r - 0.6 ? p.wall : p.floor);
                c.foundation(x, -1, z, p.foundation, SettlementPieceDepth.DEPTH);
                for (int y = 1; y <= stemH; y++) {
                    // the stem flares at the foot
                    if (dist > r - 0.6) c.set(x, y, z, (y % 6 == 3 && (dx == 0 || dz == 0)) ? p.glass : p.wall);
                    else c.air(x, y, z);
                }
            }
        }
        // flared roots
        for (int i = 0; i < 4; i++) {
            Direction dir = Direction.from2DDataValue(i);
            for (int k = 1; k <= 2; k++) {
                int x = cx + dir.getStepX() * (r + k), z = cz + dir.getStepZ() * (r + k);
                for (int y = 0; y <= 3 - k; y++) c.set(x, y, z, p.wall);
                c.foundation(x, -1, z, p.wall, 6);
            }
        }
        // cap: a flattened dome
        int capY = stemH + 1;
        for (int dx = -capR; dx <= capR; dx++) {
            for (int dz = -capR; dz <= capR; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > capR + 0.4) continue;
                int hgt = (int) Math.round(3.0 * Math.sqrt(Math.max(0, 1 - (dist / (capR + 0.5)) * (dist / (capR + 0.5)))));
                c.set(cx + dx, capY - 1, cz + dz, dist > r ? under : p.floor2);
                for (int k = 0; k <= hgt; k++) c.set(cx + dx, capY + k, cz + dz, k == hgt || dist > capR - 1 ? cap : B.s(Blocks.AIR));
            }
        }
        // glowing gills under the cap
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4;
            c.set(cx + (int) Math.round(Math.cos(ang) * (r + 2)), capY - 1, cz + (int) Math.round(Math.sin(ang) * (r + 2)), p.light);
        }
        // spiral ramp inside (stairs) to the cap room
        if (f.storeys > 1 || stemH > 6) {
            for (int y = 1; y < stemH; y++) {
                int t = y % 8;
                int[][] ring = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
                int[] o = ring[t];
                if (r >= 3) c.set(cx + o[0] * (r - 1), y, cz + o[1] * (r - 1), p.planks);
            }
        }
        c.door(cx, 1, cz - r, p.door, Direction.SOUTH);
        List<Room> rooms = new ArrayList<>();
        int in = Math.max(1, (int) Math.floor((r - 1) / Math.sqrt(2)));
        rooms.add(new Room(cx - in, cz - in + 1, cx + in, cz + in, 1, 5));
        return rooms;
    }

    // ------------------------------------------------------------------ Bosmer tree houses

    static List<Room> tree(Canvas c, Palette p, Frame f) {
        int cx = (f.w - 1) / 2, cz = (f.d - 1) / 2;
        int trunkR = Math.max(1, Math.min(f.w, f.d) / 5);
        int platformY = 6;
        int hutR = Math.max(3, Math.min(f.w, f.d) / 2 - 1);
        int top = platformY + f.wallH + hutR + 6;
        clearVolume(c, f.w, f.d, top, 3);
        BlockState log = B.id("skycraft:graht_oak_log", Blocks.JUNGLE_LOG);
        BlockState leaves = B.id("skycraft:graht_oak_leaves", Blocks.JUNGLE_LEAVES);
        if (leaves.hasProperty(LeavesBlock.PERSISTENT)) leaves = leaves.setValue(LeavesBlock.PERSISTENT, true);
        // trunk with buttress roots
        for (int dx = -trunkR - 1; dx <= trunkR + 1; dx++) {
            for (int dz = -trunkR - 1; dz <= trunkR + 1; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > trunkR + 1.2) continue;
                int hgt = dist <= trunkR + 0.3 ? top - 3 : 2;
                for (int y = 0; y <= hgt; y++) c.set(cx + dx, y, cz + dz, B.axis(log, Direction.Axis.Y));
                c.foundation(cx + dx, -1, cz + dz, B.axis(log, Direction.Axis.Y), 8);
            }
        }
        // platform ring
        for (int dx = -hutR - 1; dx <= hutR + 1; dx++) {
            for (int dz = -hutR - 1; dz <= hutR + 1; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > hutR + 1.4 || dist <= trunkR + 0.3) continue;
                c.set(cx + dx, platformY, cz + dz, p.planks);
                if (dist > hutR + 0.5) c.set(cx + dx, platformY + 1, cz + dz, p.fence);
            }
        }
        // spiral stair around the trunk up to the platform
        int sr = trunkR + 1;
        int[][] ring = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
        Direction[] up = {Direction.EAST, Direction.SOUTH, Direction.SOUTH, Direction.WEST, Direction.WEST, Direction.NORTH, Direction.NORTH, Direction.EAST};
        for (int y = 1; y < platformY; y++) {
            int t = (y - 1) % 8;
            int[] o = ring[t];
            c.set(cx + o[0] * sr, y, cz + o[1] * sr, B.stair(p.stairs, up[t], false));
            c.air(cx + o[0] * sr, y + 1, cz + o[1] * sr);
            c.air(cx + o[0] * sr, y + 2, cz + o[1] * sr);
        }
        // the hut on the platform: walls of hide between bone posts, under a leafy roof
        int hy = platformY;
        for (int dx = -hutR; dx <= hutR; dx++) {
            for (int dz = -hutR; dz <= hutR; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > hutR + 0.4 || dist <= trunkR + 0.3) continue;
                for (int y = 1; y <= f.wallH; y++) {
                    if (dist > hutR - 0.6) {
                        boolean post = (dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz));
                        c.set(cx + dx, hy + y, cz + dz, post ? B.axis(p.post, Direction.Axis.Y) : p.wall);
                    } else {
                        c.air(cx + dx, hy + y, cz + dz);
                    }
                }
            }
        }
        for (int k = 0; k <= hutR + 2; k++) {
            double rr = hutR + 2 - k;
            for (int dx = -hutR - 2; dx <= hutR + 2; dx++) {
                for (int dz = -hutR - 2; dz <= hutR + 2; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist <= rr + 0.4 && dist > rr - 1.4 && dist > trunkR + 0.3) c.set(cx + dx, hy + f.wallH + 1 + k, cz + dz, leaves);
                }
            }
        }
        // canopy crown
        for (int dx = -hutR - 3; dx <= hutR + 3; dx++) {
            for (int dz = -hutR - 3; dz <= hutR + 3; dz++) {
                for (int k = 0; k < 3; k++) {
                    double dist = Math.sqrt(dx * dx + dz * dz + (k - 1) * (k - 1) * 4);
                    if (dist < hutR + 3 && c.chance(dx, k, dz, 41, 4) == false) c.setIfOpen(cx + dx, top - 2 + k, cz + dz, leaves);
                }
            }
        }
        c.door(cx, hy + 1, cz - hutR, p.door, Direction.SOUTH);
        c.set(cx, hy, cz - hutR - 1, p.planks);
        List<Room> rooms = new ArrayList<>();
        // a ring room around the trunk: furnish its front half
        rooms.add(new Room(cx - hutR + 1, cz - hutR + 1, cx + hutR - 1, cz - trunkR - 1, hy + 1, hy + f.wallH + 1));
        rooms.add(new Room(cx - hutR + 1, cz + trunkR + 1, cx + hutR - 1, cz + hutR - 1, hy + 1, hy + f.wallH + 1));
        return rooms;
    }

    // ------------------------------------------------------------------ towers

    static List<Room> tower(Canvas c, Palette p, Frame f) {
        int w = f.w, d = f.d;
        int levels = Math.max(2, f.storeys);
        int lh = 5;
        int top = levels * lh;
        clearVolume(c, w, d, top + w + 3, 1);
        base(c, p, w, d, false);
        int cx = (w - 1) / 2, cz = (d - 1) / 2;
        for (int y = 1; y <= top; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (edge) {
                        boolean slit = (y % lh == 3) && (x == cx || z == cz);
                        if (f.ruined && y > top - 6 && c.chance(x, y, z, 9, 2)) continue;
                        c.set(x, y, z, slit ? p.window : (c.chance(x, y, z, 23, 6) ? p.cityWall2 : p.cityWall));
                    } else if (y % lh == 0) {
                        c.set(x, y, z, p.floor2);
                    } else {
                        c.air(x, y, z);
                    }
                }
            }
        }
        // ladder in the back-left corner through every floor
        for (int y = 1; y <= top; y++) {
            c.set(1, y, d - 2, B.face(B.s(Blocks.LADDER), Direction.NORTH));
        }
        door(c, p, cx, 0);
        if (f.ruined) return List.of(new Room(1, 1, w - 2, d - 2, 1, lh));
        if (p.towerShell == Shell.TOWER && (p.name.equals("altmer") || p.name.equals("redguard") || p.name.equals("breton")
                || p.name.equals("maormer") || p.name.equals("imperial") || p.name.equals("imperial_city"))) {
            // pointed spire
            int sy = top + 1;
            for (int k = 0; k <= w / 2; k++) {
                for (int x = k - 1; x <= w - k; x++) {
                    for (int z = k - 1; z <= d - k; z++) {
                        boolean edge = x == k - 1 || z == k - 1 || x == w - k || z == d - k;
                        if (!edge) continue;
                        c.set(x, sy + k * 2, z, p.roofFull);
                        c.set(x, sy + k * 2 + 1, z, p.roofFull);
                    }
                }
            }
            c.set(cx, sy + w + 2, cz, p.column);
            c.set(cx, sy + w + 3, cz, p.name.equals("altmer") ? B.s(Blocks.AMETHYST_CLUSTER) : B.s(Blocks.LIGHTNING_ROD));
        } else {
            // open fighting platform with merlons
            for (int x = -1; x <= w; x++) {
                for (int z = -1; z <= d; z++) {
                    boolean edge = x == -1 || z == -1 || x == w || z == d;
                    if (edge) {
                        c.set(x, top, z, B.stair(p.cityWallStairs, x == -1 ? Direction.EAST : x == w ? Direction.WEST : z == -1 ? Direction.SOUTH : Direction.NORTH, true));
                        if ((x + z) % 2 == 0) c.set(x, top + 1, z, p.cityWall);
                    } else {
                        c.set(x, top, z, p.cityWall);
                    }
                }
            }
            c.air(1, top, d - 2);
            c.set(1, top, d - 2, B.face(B.s(Blocks.LADDER), Direction.NORTH));
            c.set(cx, top + 1, cz, B.lit(B.s(Blocks.CAMPFIRE), true));
            c.set(cx, top, cz, p.cityWall);
        }
        List<Room> rooms = new ArrayList<>();
        for (int k = 0; k < levels; k++) rooms.add(new Room(2, 1, w - 2, d - 3, k * lh + 1, (k + 1) * lh));
        return rooms;
    }

    // ------------------------------------------------------------------ Akaviri pagodas

    static List<Room> pagoda(Canvas c, Palette p, Frame f) {
        int w = f.w, d = f.d;
        int tiers = Math.max(1, f.storeys);
        clearVolume(c, w, d, tiers * (f.wallH + 3) + 6, 3);
        base(c, p, w, d, false);
        List<Room> rooms = new ArrayList<>();
        int y0 = 0;
        int inset = 0;
        for (int t = 0; t < tiers; t++) {
            int x0 = inset, z0 = inset, x1 = w - 1 - inset, z1 = d - 1 - inset;
            if (x1 - x0 < 3 || z1 - z0 < 3) break;
            for (int y = 1; y <= f.wallH; y++) {
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        boolean edge = x == x0 || z == z0 || x == x1 || z == z1;
                        boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                        if (edge) c.set(x, y0 + y, z, corner || (x - x0) % 3 == 0 && (z == z0 || z == z1) || (z - z0) % 3 == 0 && (x == x0 || x == x1)
                                ? B.axis(p.post, Direction.Axis.Y) : (y == f.wallH ? B.axis(p.beam, Direction.Axis.X) : p.wall));
                        else c.air(x, y0 + y, z);
                    }
                }
            }
            rooms.add(new Room(x0 + 1, z0 + 1, x1 - 1, z1 - 1, y0 + 1, y0 + f.wallH + 1));
            // wide curved eaves: two rings, corners turned up
            int ry = y0 + f.wallH + 1;
            for (int ring = 0; ring < 2; ring++) {
                int ax = x0 - 2 + ring, bx = x1 + 2 - ring, az = z0 - 2 + ring, bz = z1 + 2 - ring;
                for (int x = ax; x <= bx; x++) {
                    c.set(x, ry + ring, az, B.stair(p.roof, Direction.SOUTH, false));
                    c.set(x, ry + ring, bz, B.stair(p.roof, Direction.NORTH, false));
                }
                for (int z = az; z <= bz; z++) {
                    c.set(ax, ry + ring, z, B.stair(p.roof, Direction.EAST, false));
                    c.set(bx, ry + ring, z, B.stair(p.roof, Direction.WEST, false));
                }
                if (ring == 0) {
                    c.set(ax, ry + 1, az, B.stair(p.roof, Direction.SOUTH, true));
                    c.set(bx, ry + 1, az, B.stair(p.roof, Direction.SOUTH, true));
                    c.set(ax, ry + 1, bz, B.stair(p.roof, Direction.NORTH, true));
                    c.set(bx, ry + 1, bz, B.stair(p.roof, Direction.NORTH, true));
                }
            }
            c.fill(x0, ry + 1, z0, x1, ry + 1, z1, p.roofFull);
            c.fill(x0 + 1, ry + 1, z0 + 1, x1 - 1, ry + 1, z1 - 1, p.floor);
            y0 = ry + 1;
            inset += 2;
        }
        // finial
        int cx = (w - 1) / 2, cz = (d - 1) / 2;
        hipRoof(c, p, inset - 2, inset - 2, w - 1 - inset + 2, d - 1 - inset + 2, y0 + 1);
        c.set(cx, y0 + Math.min(w, d) / 2 + 2, cz, B.s(Blocks.LIGHTNING_ROD));
        door(c, p, cx, 0);
        // red lacquered gate posts in front (torii-like)
        for (int y = 1; y <= 4; y++) {
            c.set(cx - 2, y, -2, B.axis(p.post, Direction.Axis.Y));
            c.set(cx + 2, y, -2, B.axis(p.post, Direction.Axis.Y));
        }
        for (int x = cx - 3; x <= cx + 3; x++) c.set(x, 5, -2, B.axis(p.beam, Direction.Axis.X));
        return rooms;
    }

    /** Depth limit for foundations and posts (shared with the piece clip). */
    static final class SettlementPieceDepth {
        static final int DEPTH = com.skycraft.tamriel.settlement.SettlementPiece.FOUNDATION;
    }
}
