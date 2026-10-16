package com.skycraft.tamriel.settlement.build.landmarks;

import com.skycraft.tamriel.settlement.build.B;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.build.Landmarks;
import com.skycraft.tamriel.settlement.build.Props;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The first batch of registry landmarks ({@link com.skycraft.tamriel.settlement.build.LandmarkRegistry} ids 100+): the
 * silhouettes of the marquee cities. Every builder is original and procedural, takes its footprint and height from the
 * spec, takes its materials from the kit palette (so a place's style overlay re-tints it), and keeps inside
 * {@code w x d x h} above the floor. {@link Spec#name} may carry one parameter (a colour, a boom length).
 */
public final class MarqueeLandmarks {
    private MarqueeLandmarks() {}

    public static void build(Canvas c, Palette p, Spec s) {
        switch (s.variant) {
            case 100 -> shell(c, p, s);
            case 101 -> mushroomSpire(c, p, s);
            case 102 -> luckyLady(c, p, s);
            case 103 -> gateHammer(c, p, s);
            case 104 -> minaret(c, p, s);
            case 105 -> twinDomes(c, p, s);
            case 106 -> chapelSpire(c, p, s);
            case 107 -> stoneCircle(c, p, s);
            case 108 -> orrery(c, p, s);
            case 109 -> libraryTerrace(c, p, s);
            case 110 -> headframe(c, p, s);
            case 111 -> chainTower(c, p, s);
            case 112 -> awningRow(c, p, s);
            case 113 -> canopyPlatform(c, p, s);
            case 114 -> glassDome(c, p, s);
            case 115 -> adamantineTower(c, p, s);
            case 116 -> baarDau(c, p, s);
            case 117 -> redMountainCitadel(c, p, s);
            case 118 -> sunspireTemple(c, p, s);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BlockState colour(String name, String suffix, Block fallback) {
        String n = name == null || name.isEmpty() ? "white" : name;
        return B.id("minecraft:" + n + "_" + suffix, fallback);
    }

    /** Level paving under the footprint, with foundations, and the area above cleared of trees and plants. */
    private static void pave(Canvas c, Palette p, int w, int d, int h) {
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, h, d - 1);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.set(x, 0, z, p.plaza);
            c.foundation(x, -1, z, p.foundation, 8);
        }
    }

    private static void box(Canvas c, int x0, int y0, int z0, int x1, int y1, int z1, BlockState b) {
        c.fill(x0, y0, z0, x1, y1, z1, b);
    }

    private static boolean ellipse(int dx, int dz, double rx, double rz) {
        return dx * dx / (rx * rx) + dz * dz / (rz * rz) <= 1.0;
    }

    // ------------------------------------------------------------------ Aldruhn: the Skar

    /** A gigantic upturned crab shell: a ribbed ellipsoid hull with a hollow hall, a ridge and a door in the long side. */
    static void shell(Canvas c, Palette p, Spec s) {
        if (c.recording()) {
            c.entrance((s.w - 1) / 2, 1, 0, p.door, Direction.SOUTH);
            return;
        }
        int w = s.w, d = s.d, h = Math.max(12, s.h);
        double cx = (w - 1) / 2.0, cz = (d - 1) / 2.0, ax = w / 2.0, az = d / 2.0;
        double f = Math.max(0.5, 1 - 2.2 / Math.min(ax, az));
        BlockState skin = p.cityWall, rib = p.cityWall2, ridge = p.beam;
        c.clearPlants(0, 1, 0, w - 1, h + 2, d - 1);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            double nx = (x - cx) / ax, nz = (z - cz) / az;
            double r2 = nx * nx + nz * nz;
            if (r2 > 1) continue;
            int top = (int) Math.round(h * Math.sqrt(1 - r2));
            boolean ribbed = x % 5 == 0 || (x + (z & 1)) % 11 == 0;
            c.foundation(x, -1, z, p.foundation, 8);
            if (r2 < f * f) {
                int in = (int) Math.round(h * f * Math.sqrt(1 - r2 / (f * f)));
                c.set(x, 0, z, p.floor);
                for (int y = 1; y <= top; y++) {
                    if (y < in) c.air(x, y, z);
                    else c.set(x, y, z, ribbed && y == top ? rib : skin);
                }
            } else {
                for (int y = 0; y <= top; y++) c.set(x, y, z, ribbed ? rib : skin);
            }
            if (Math.abs(nz) < 0.12 && top > 2) c.set(x, top + 1, z, B.axis(ridge, Direction.Axis.X));
        }
        // the door and a hall inside
        int dz0 = (int) Math.round(cz - az * 0.78);
        for (int z = 0; z <= dz0 + 4 && z < d; z++) {
            for (int x = (int) cx - 2; x <= (int) cx + 2; x++) for (int y = 1; y <= 6; y++) {
                if (Math.abs(x - cx) == 2 && y == 6) continue;
                c.air(x, y, z);
            }
        }
        for (int x = 4; x < w - 4; x += 6) {
            Props.lantern(c, p, x, 5, (int) cz, true);
        }
        Props.rug(c, p.carpet, (int) cx - 1, 1, 2, (int) cx + 1, d - 3);
        c.set((int) cx, 1, d / 2 + 3, B.stair(p.stairs, Direction.SOUTH, false));
        c.set((int) cx, 2, d / 2 + 3, p.light);
    }

    // ------------------------------------------------------------------ Sadrith Mora: spore spires

    /** A giant fungus tower: tapering stem with floors and windows, and a glowing, spotted cap. {@code name} = cap colour. */
    static void mushroomSpire(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(20, s.h);
        if (c.recording()) {
            c.entrance(cx, 1, cz - 3, p.door, Direction.SOUTH);
            return;
        }
        String col = s.name.isEmpty() ? "purple" : s.name;
        BlockState stem = B.s(Blocks.MUSHROOM_STEM), cap = colour(col, "wool", Blocks.PURPLE_WOOL), spot = B.s(Blocks.WHITE_WOOL);
        BlockState glow = B.s(Blocks.SHROOMLIGHT);
        int stemH = (int) (h * 0.62);
        double capR = Math.min(s.w, s.d) / 2.0 - 0.5;
        c.clearPlants(0, 1, 0, s.w - 1, h + 1, s.d - 1);
        for (int y = -1; y <= stemH; y++) {
            double r = 3.6 - 1.4 * Math.min(1.0, Math.max(0, y) / (double) stemH) + (y < 3 ? 0.8 : 0);
            Landmarks.ring(c, cx, y, cz, r, 1.3, stem);
            if (y >= 0 && y % 7 == 0) Landmarks.disc(c, cx, y, cz, r - 0.8, p.floor);
            if (y >= 0 && y % 7 == 0 && y > 0) c.set(cx, y, cz, B.s(Blocks.AIR));
            if (y >= 3 && y % 5 == 2) {
                for (int i = 0; i < 4; i++) {
                    Direction dd = Direction.from2DDataValue(i);
                    c.set(cx + dd.getStepX() * (int) Math.round(r - 0.4), y, cz + dd.getStepZ() * (int) Math.round(r - 0.4), p.window);
                }
            }
        }
        c.foundation(cx, -2, cz, stem, 12);
        for (int y = 1; y <= stemH; y++) c.set(cx, y, cz + 1, B.face(B.s(Blocks.LADDER), Direction.NORTH));
        for (int y = 1; y <= 2; y++) c.air(cx, y, cz - 3);
        c.entrance(cx, 1, cz - 3, p.door, Direction.SOUTH);
        // the cap: an underside of gills, then a dome
        int capH = h - stemH;
        for (int k = 0; k <= capH; k++) {
            double t = k / (double) capH;
            double r = capR * Math.sqrt(Math.max(0, 1 - t * t * 0.96));
            int y = stemH + 1 + k;
            if (k == 0) {
                Landmarks.disc(c, cx, y, cz, capR, cap);
                Landmarks.ring(c, cx, y - 1, cz, capR - 1, 1.2, glow);
            } else {
                Landmarks.ring(c, cx, y, cz, r, 1.4, cap);
                if (r < 1.5) c.set(cx, y, cz, cap);
            }
        }
        for (int i = 0; i < 9; i++) {
            double a = i * 2.399, t = 0.25 + 0.07 * (i % 5);
            double r = capR * Math.sqrt(1 - t * t) * 0.97;
            c.set(cx + (int) Math.round(Math.cos(a) * r), stemH + 1 + (int) Math.round(t * capH), cz + (int) Math.round(Math.sin(a) * r), spot);
        }
        Landmarks.disc(c, cx, stemH + 1, cz, 2, B.s(Blocks.AIR));
        c.set(cx, stemH + 1, cz, glow);
    }

    // ------------------------------------------------------------------ Bravil: the Lucky Old Lady

    /** A tall cloaked woman on a stepped plinth, one arm raised with a lantern. */
    static void luckyLady(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(24, s.h);
        BlockState stone = B.s(Blocks.MOSSY_STONE_BRICKS), dark = B.s(Blocks.STONE_BRICKS), cloak = p.cityWall, hood = p.cityWall2;
        pave(c, p, s.w, s.d, h + 2);
        for (int i = 0; i < 3; i++) {
            int r = 6 - i;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                c.set(cx + dx, 1 + i, cz + dz, (dx + dz + i) % 5 == 0 ? dark : stone);
            }
        }
        for (int i = 0; i < 4; i++) {
            Direction dd = Direction.from2DDataValue(i);
            Props.lantern(c, p, cx + dd.getStepX() * 7, 1, cz + dd.getStepZ() * 7, false);
        }
        int y0 = 4, gown = 13;
        for (int y = y0; y < y0 + gown; y++) {
            double t = (y - y0) / (double) gown;
            double r = 3.4 - 1.7 * t;
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                double fold = 0.45 * Math.sin(Math.atan2(dz, dx) * 5);
                if (dist <= r + fold) c.set(cx + dx, y, cz + dz, dist > r - 0.7 + fold ? cloak : hood);
            }
        }
        int sh = y0 + gown;
        box(c, cx - 2, sh, cz - 1, cx + 2, sh + 1, cz + 1, cloak);
        // the head under a hood, a little bowed
        box(c, cx - 1, sh + 2, cz - 1, cx + 1, sh + 4, cz + 1, B.s(Blocks.SMOOTH_STONE));
        box(c, cx - 1, sh + 3, cz - 2, cx + 1, sh + 3, cz - 2, B.s(Blocks.SMOOTH_STONE));
        box(c, cx - 2, sh + 4, cz - 1, cx + 2, sh + 4, cz + 1, hood);
        c.set(cx, sh + 5, cz, hood);
        // left arm down along the cloak, right arm raised holding the lantern
        box(c, cx - 3, sh - 4, cz, cx - 3, sh, cz, cloak);
        box(c, cx + 3, sh, cz, cx + 3, sh + 6, cz, cloak);
        c.set(cx + 3, sh + 7, cz, B.s(Blocks.CHAIN));
        c.set(cx + 3, sh + 8, cz, p.light.hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)
                ? p.light.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true) : B.s(Blocks.SEA_LANTERN));
        c.set(cx + 3, sh + 9, cz, B.s(Blocks.CHAIN));
        c.set(cx + 3, sh + 10, cz, B.s(Blocks.LIGHTNING_ROD));
    }

    // ------------------------------------------------------------------ Orsinium: the Great Gate-Hammer

    /** A gatehouse between two massive piers under a giant war-hammer standing on its handle. */
    static void gateHammer(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int w = s.w, d = s.d, h = Math.max(24, s.h), cx = (w - 1) / 2, cz = (d - 1) / 2;
        BlockState wall = p.cityWall, wall2 = p.cityWall2, iron = B.s(Blocks.IRON_BLOCK), dark = B.s(Blocks.POLISHED_BLACKSTONE);
        int pier = 5, lintelY = 14;
        c.clearPlants(0, 1, 0, w - 1, h, d - 1);
        for (int x = 0; x < w; x++) for (int z = 1; z < d - 1; z++) {
            boolean isPier = x < pier || x >= w - pier;
            c.foundation(x, -1, z, p.foundation, 10);
            c.set(x, 0, z, p.plaza);
            if (isPier) {
                for (int y = 1; y <= lintelY + 3; y++) c.set(x, y, z, (y + x + z) % 7 == 0 ? wall2 : wall);
            } else if (z >= 2 && z < d - 2) {
                for (int y = lintelY; y <= lintelY + 3; y++) c.set(x, y, z, y == lintelY ? wall2 : wall);
            }
        }
        // gate arch corners
        for (int z = 1; z < d - 1; z++) {
            c.set(pier, lintelY - 1, z, B.stair(p.cityWallStairs, Direction.EAST, true));
            c.set(w - pier - 1, lintelY - 1, z, B.stair(p.cityWallStairs, Direction.WEST, true));
        }
        // portcullis teeth
        for (int x = pier + 1; x < w - pier - 1; x += 2) for (int y = lintelY - 3; y < lintelY; y++) c.set(x, y, 1, B.s(Blocks.IRON_BARS));
        // crenellated top
        for (int x = 0; x < w; x++) {
            if (x % 2 == 0) {
                c.set(x, lintelY + 4, 1, wall);
                c.set(x, lintelY + 4, d - 2, wall);
            }
        }
        // the hammer: handle through the roof, a head crossing it, studs and a cap
        int hy = lintelY + 4;
        for (int y = hy; y < h - 6; y++) {
            box(c, cx - 1, y, cz - 1, cx + 1, y, cz + 1, y % 4 == 0 ? B.s(Blocks.STRIPPED_DARK_OAK_LOG) : B.s(Blocks.DARK_OAK_LOG));
        }
        box(c, cx - 7, h - 6, cz - 3, cx + 7, h - 1, cz + 3, iron);
        box(c, cx - 8, h - 5, cz - 2, cx + 8, h - 2, cz + 2, dark);
        for (int x = cx - 8; x <= cx + 8; x += 4) {
            c.set(x, h, cz, B.s(Blocks.IRON_BARS));
            c.set(x, h + 0, cz - 3, B.s(Blocks.CHAIN));
        }
        // trophies and banners on the piers
        for (int side = 0; side < 2; side++) {
            int bx = side == 0 ? pier : w - pier - 1;
            for (int y = 4; y < lintelY - 1; y += 3) {
                c.set(side == 0 ? bx : bx, y, 0, B.s(Blocks.RED_WOOL));
                Props.lantern(c, p, side == 0 ? 1 : w - 2, y + 1, 0, false);
            }
            c.set(side == 0 ? 1 : w - 2, lintelY + 5, 1, B.s(Blocks.SKELETON_SKULL));
        }
        // stairs up the back of the left pier
        for (int y = 1; y <= lintelY + 3; y++) c.set(1, y, d - 2, B.face(B.s(Blocks.LADDER), Direction.NORTH));
    }

    // ------------------------------------------------------------------ Rihad: the minaret

    /** A slender minaret: tapering shaft, two balconies, an onion dome and a finial. */
    static void minaret(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(30, s.h);
        BlockState wall = p.wall, band = p.cityWall2, dome = p.roofFull;
        pave(c, p, s.w, s.d, h + 6);
        int shaft = h - 12;
        for (int y = 1; y <= shaft; y++) {
            int r = y < shaft * 0.45 ? 3 : 2;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > r * 2 - 1 + (r == 3 ? 0 : 0)) continue;
                boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == r || Math.abs(dx) + Math.abs(dz) == r * 2 - 1;
                if (edge) c.set(cx + dx, y, cz + dz, y % 8 == 0 ? band : (y % 6 == 3 && (dx == 0 || dz == 0) ? p.window : wall));
                else c.air(cx + dx, y, cz + dz);
            }
            if (y % 6 == 1) Landmarks.disc(c, cx, y, cz, r - 0.5, p.floor);
        }
        for (int y = 1; y <= shaft; y++) c.set(cx, y, cz + 1, B.face(B.s(Blocks.LADDER), Direction.NORTH));
        c.air(cx, 1, cz - 3);
        c.air(cx, 2, cz - 3);
        // balconies
        for (int b : new int[]{(int) (shaft * 0.62), shaft}) {
            Landmarks.disc(c, cx, b, cz, 4.4, band);
            Landmarks.ring(c, cx, b + 1, cz, 4.4, 1.0, p.fence);
            Props.lantern(c, p, cx + 4, b + 1, cz, false);
            Props.lantern(c, p, cx - 4, b + 1, cz, false);
        }
        // the onion dome
        int base = shaft + 1;
        for (int k = 0; k <= 10; k++) {
            double r = k < 4 ? 3.2 + k * 0.35 : 4.6 * Math.cos((k - 4) / 6.0 * Math.PI / 2);
            Landmarks.ring(c, cx, base + k, cz, Math.max(0.6, r), 1.3, dome);
        }
        for (int k = 0; k < 5; k++) c.set(cx, base + 10 + k, cz, k == 4 ? B.s(Blocks.END_ROD) : B.s(Blocks.GOLD_BLOCK));
    }

    // ------------------------------------------------------------------ Torval: the twin moon domes

    /** Two great domes side by side on a low hall: ivory shell with lapis ribs and moon-silver finials. */
    static void twinDomes(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d, h = Math.max(20, s.h), cz = (d - 1) / 2;
        if (c.recording()) {
            c.entrance(w / 2, 1, 2, p.door, Direction.SOUTH);
            return;
        }
        double r = Math.min(d / 2.0 - 1, (w - 3) / 4.0);
        BlockState shellB = p.cityWall, ribB = B.s(Blocks.LAPIS_BLOCK), hall = p.wall;
        pave(c, p, w, d, h + 4);
        // the low hall joining them
        int hallH = 7;
        for (int x = 1; x < w - 1; x++) for (int z = 2; z < d - 2; z++) {
            boolean edge = x == 1 || z == 2 || x == w - 2 || z == d - 3;
            for (int y = 1; y <= hallH; y++) {
                if (edge) c.set(x, y, z, y % 3 == 2 && x % 4 == 0 ? p.window : hall);
                else if (y == hallH) c.set(x, y, z, p.roofFull);
                else c.air(x, y, z);
            }
            c.set(x, 0, z, edge ? p.plaza2 : p.floor);
        }
        int[] centers = {(int) Math.round(r + 1), w - 1 - (int) Math.round(r + 1)};
        for (int i = 0; i < 2; i++) {
            int dx0 = centers[i];
            int dh = (int) Math.min(h - hallH - 4, r * 1.25);
            for (int k = 0; k <= dh; k++) {
                double rr = r * Math.sqrt(Math.max(0, 1 - (k / (double) dh) * (k / (double) dh)));
                Landmarks.ring(c, dx0, hallH + k, cz, Math.max(0.5, rr), 1.3, k % 4 == 3 ? ribB : shellB);
                if (rr < 1.2) c.set(dx0, hallH + k, cz, shellB);
            }
            for (int y = hallH + dh + 1; y <= hallH + dh + 5; y++) c.set(dx0, y, cz, y == hallH + dh + 5 ? B.s(Blocks.SEA_LANTERN) : B.s(Blocks.IRON_BLOCK));
            // meridian ribs
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4;
                for (int k = 2; k < dh; k += 1) {
                    double rr = r * Math.sqrt(1 - (k / (double) dh) * (k / (double) dh));
                    c.set(dx0 + (int) Math.round(Math.cos(ang) * rr), hallH + k, cz + (int) Math.round(Math.sin(ang) * rr), ribB);
                }
            }
        }
        // the Mane's doors on the south wall
        for (int y = 1; y <= 4; y++) for (int x = w / 2 - 1; x <= w / 2 + 1; x++) c.air(x, y, 2);
        c.entrance(w / 2, 1, 2, p.door, Direction.SOUTH);
        for (int x = 3; x < w - 3; x += 6) Props.lantern(c, p, x, 5, 1, false);
    }

    // ------------------------------------------------------------------ Bruma: the Great Chapel of Talos

    /** A nave with a steep gable roof and a tall tower ending in a snow-capped spire at the back. */
    static void chapelSpire(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d, h = Math.max(30, s.h), cx = (w - 1) / 2;
        if (c.recording()) {
            c.entrance(cx, 1, 1, p.door, Direction.SOUTH);
            return;
        }
        int naveD = Math.min(d - 9, 17), wallH = 9;
        pave(c, p, w, d, h + 2);
        // nave
        for (int x = 1; x < w - 1; x++) for (int z = 1; z < naveD; z++) {
            boolean edge = x == 1 || x == w - 2 || z == 1 || z == naveD - 1;
            c.set(x, 0, z, edge ? p.plaza2 : p.floor);
            for (int y = 1; y <= wallH; y++) {
                if (!edge) c.air(x, y, z);
                else if (y < 3) c.set(x, y, z, p.wallLow);
                else c.set(x, y, z, (y >= 4 && y <= 7 && ((z % 4 == 3 && (x == 1 || x == w - 2)) || (x == cx && z == 1))) ? p.window : p.wall);
            }
        }
        // gable roof along z with ridge at the centre
        int half = (w - 1) / 2;
        for (int k = 0; k <= half; k++) {
            int y = wallH + 1 + k;
            for (int z = 0; z <= naveD; z++) {
                if (k == half) c.set(cx, y, z, B.slab(p.roofSlab, false));
                else {
                    c.set(1 - 1 + k, y, z, B.stair(p.roof, Direction.EAST, false));
                    c.set(w - 1 - k, y, z, B.stair(p.roof, Direction.WEST, false));
                }
            }
        }
        // the tower behind
        int tx0 = cx - 3, tx1 = cx + 3, tz0 = naveD, tz1 = naveD + 6;
        int towerH = h - 16;
        for (int y = 1; y <= towerH; y++) for (int x = tx0; x <= tx1; x++) for (int z = tz0; z <= tz1; z++) {
            boolean edge = x == tx0 || x == tx1 || z == tz0 || z == tz1;
            if (edge) c.set(x, y, z, y % 9 == 0 ? p.cityWall2 : (y % 7 == 4 && (x == cx || z == (tz0 + tz1) / 2) ? p.window : p.wall));
            else if (y % 8 == 0) c.set(x, y, z, p.floor);
            else c.air(x, y, z);
        }
        for (int y = 1; y <= towerH; y++) c.set(cx, y, tz1 - 1, B.face(B.s(Blocks.LADDER), Direction.NORTH));
        // the steep spire
        int spireH = h - towerH;
        for (int k = 0; k < spireH; k++) {
            int r = Math.max(0, 4 - (int) Math.floor(k * 4.0 / spireH));
            for (int x = cx - r; x <= cx + r; x++) for (int z = (tz0 + tz1) / 2 - r; z <= (tz0 + tz1) / 2 + r; z++) {
                boolean edge = Math.abs(x - cx) == r || Math.abs(z - (tz0 + tz1) / 2) == r;
                if (edge || k == spireH - 1) c.set(x, towerH + 1 + k, z, k > spireH - 5 ? B.s(Blocks.SNOW_BLOCK) : p.roofFull);
            }
        }
        c.set(cx, h + 1, (tz0 + tz1) / 2, B.s(Blocks.LIGHTNING_ROD));
        // door and steps
        for (int y = 1; y <= 3; y++) for (int x = cx - 1; x <= cx + 1; x++) c.air(x, y, 1);
        c.entrance(cx, 1, 1, p.door, Direction.SOUTH);
        c.set(cx, 1, 0, B.stair(p.stairs, Direction.NORTH, false));
        for (int i = 0; i < 4; i++) {
            Direction dd = Direction.from2DDataValue(i);
            if (dd.getAxis() == Direction.Axis.Z) continue;
            Props.lantern(c, p, cx + dd.getStepX() * 3, 1, 0, false);
        }
        c.set(cx, 3, 2, Props.shrine("talos"));
    }

    // ------------------------------------------------------------------ Skaal: the stone circle

    /** A ring of standing stones round a bonfire, with carved totem posts and bone-rack arches. */
    static void stoneCircle(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        double R = Math.min(s.w, s.d) / 2.0 - 2.5;
        c.clearPlants(0, 1, 0, s.w - 1, s.h, s.d - 1);
        BlockState stone = B.s(Blocks.COBBLED_DEEPSLATE), ice = B.s(Blocks.PACKED_ICE), snow = B.s(Blocks.SNOW_BLOCK);
        for (int dx = -(int) R - 1; dx <= (int) R + 1; dx++) for (int dz = -(int) R - 1; dz <= (int) R + 1; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > R + 1.5) continue;
            c.foundation(cx + dx, -1, cz + dz, p.foundation, 6);
            c.set(cx + dx, 0, cz + dz, dist < 2.5 ? B.s(Blocks.COARSE_DIRT) : dist > R - 1 && (dx + dz) % 3 == 0 ? ice : (dx * 7 + dz * 3) % 5 == 0 ? snow : p.path);
        }
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            int sx = cx + (int) Math.round(Math.cos(a) * R), sz = cz + (int) Math.round(Math.sin(a) * R);
            int sh = 4 + (i * 5 % 4);
            for (int y = 1; y <= sh; y++) {
                c.set(sx, y, sz, y == sh ? B.s(Blocks.DEEPSLATE_BRICKS) : stone);
                if (y <= sh - 1 && y > 1 && (i & 1) == 0) c.set(sx + (Math.abs(Math.cos(a)) > 0.5 ? 0 : 1), y, sz + (Math.abs(Math.cos(a)) > 0.5 ? 1 : 0), stone);
            }
            if (i % 3 == 0) c.set(sx, sh + 1, sz, B.s(Blocks.SKELETON_SKULL));
        }
        // the bonfire
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(cx + dx, 1, cz + dz, B.s(Blocks.COBBLED_DEEPSLATE));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if ((dx + dz) % 2 == 0) c.set(cx + dx, 2, cz + dz, B.s(Blocks.SPRUCE_LOG));
        c.set(cx, 2, cz, B.s(Blocks.CAMPFIRE));
        c.set(cx, 3, cz, B.s(Blocks.AIR));
        // four totem posts
        for (int i = 0; i < 4; i++) {
            Direction dd = Direction.from2DDataValue(i);
            int tx = cx + dd.getStepX() * 5, tz = cz + dd.getStepZ() * 5;
            for (int y = 1; y <= 7; y++) {
                c.set(tx, y, tz, y % 3 == 0 ? B.s(Blocks.CARVED_PUMPKIN) : y == 7 ? B.s(Blocks.BONE_BLOCK) : B.s(Blocks.STRIPPED_SPRUCE_LOG));
            }
            c.set(tx, 8, tz, B.s(Blocks.WHITE_BANNER));
        }
    }

    // ------------------------------------------------------------------ Lillandril / Alinor: the orrery

    /** An armillary on a marble drum: three gold rings in the three planes about a glowing core. */
    static void orrery(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(16, s.h);
        pave(c, p, s.w, s.d, h + 2);
        BlockState gold = B.s(Blocks.GOLD_BLOCK), copper = B.s(Blocks.WAXED_COPPER_BLOCK), marble = p.cityWall;
        double R = Math.min(s.w, s.d) / 2.0 - 2;
        Landmarks.disc(c, cx, 1, cz, R - 1, marble);
        Landmarks.ring(c, cx, 2, cz, R - 1, 1.1, p.cityWall2);
        for (int y = 1; y <= 4; y++) Landmarks.disc(c, cx, y, cz, 2.2, marble);
        int cy = Math.min(h - 7, 12);
        double r1 = Math.min(7.0, R - 1);
        for (int y = 5; y < cy - 1; y++) c.set(cx, y, cz, gold);
        c.set(cx, cy, cz, B.s(Blocks.SEA_LANTERN));
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 1) c.set(cx + dx, cy + dy, cz + dz, B.s(Blocks.SEA_LANTERN));
        }
        for (int i = 0; i < 72; i++) {
            double a = i * Math.PI / 36;
            int ux = (int) Math.round(Math.cos(a) * r1), uz = (int) Math.round(Math.sin(a) * r1);
            c.set(cx + ux, cy, cz + uz, gold);
            c.set(cx + ux, cy + uz, cz, copper);
            c.set(cx, cy + ux, cz + uz, gold);
        }
        c.set(cx + (int) r1, cy + 1, cz, B.s(Blocks.LAPIS_BLOCK));
        c.set(cx, cy + (int) r1, cz + 1, B.s(Blocks.PRISMARINE));
        c.set(cx - (int) r1, cy, cz - 1, B.s(Blocks.AMETHYST_BLOCK));
        // benches
        for (int i = 0; i < 4; i++) {
            Direction dd = Direction.from2DDataValue(i);
            c.set(cx + dd.getStepX() * (int) (R - 1), 2, cz + dd.getStepZ() * (int) (R - 1), B.stair(p.stairs, dd.getOpposite(), false));
        }
    }

    // ------------------------------------------------------------------ Lillandril: the Great Library Terraces

    /** Three stepped open-air tiers of bookshelf stacks under colonnades, a lectern court at the top. */
    static void libraryTerrace(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int w = s.w, d = s.d, h = Math.max(12, s.h);
        pave(c, p, w, d, h + 2);
        BlockState col = p.column, shelf = B.s(Blocks.BOOKSHELF), floor = p.cityWall;
        int tiers = 3;
        for (int t = 0; t < tiers; t++) {
            int x0 = 1 + t * 3, x1 = w - 2 - t * 3, z0 = d - 1 - (t + 1) * 7, z1 = d - 2 - t * 7;
            if (z0 < 0) z0 = 0;
            int y = 1 + t * 4;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                for (int yy = 1; yy <= y; yy++) c.set(x, yy, z, floor);
                c.set(x, y, z, (x + z) % 5 == 0 ? p.plaza2 : p.plaza);
            }
            // stacks along the back and the pillars along the front
            for (int x = x0 + 1; x <= x1 - 1; x++) {
                if ((x - x0) % 4 != 0) {
                    c.set(x, y + 1, z0, shelf);
                    c.set(x, y + 2, z0, shelf);
                    c.set(x, y + 3, z0, x % 3 == 0 ? B.s(Blocks.CHISELED_BOOKSHELF) : shelf);
                }
            }
            for (int x = x0; x <= x1; x += 4) {
                for (int yy = y + 1; yy <= y + 4; yy++) c.set(x, yy, z1, col);
                if (x + 4 <= x1) for (int xx = x + 1; xx < x + 4; xx++) c.set(xx, y + 5, z1, B.slab(p.cityWallSlab, false));
                c.set(x, y + 5, z1, p.cityWall2);
            }
            // reading nooks
            for (int x = x0 + 3; x <= x1 - 3; x += 6) {
                c.set(x, y + 1, z0 + 2, B.s(Blocks.LECTERN));
                Props.lantern(c, p, x + 2, y + 1, z0 + 2, false);
            }
            // stairs up to the next tier (centre)
            for (int k = 0; k < 4; k++) c.set(w / 2, y + 1 + k * 0, z0 - 1 - k * 0, B.stair(p.cityWallStairs, Direction.NORTH, false));
        }
        c.set(w / 2, 1, 0, B.s(Blocks.AIR));
    }

    // ------------------------------------------------------------------ Raven Rock: the ebony shaft head

    /** A timber headframe over the shaft with its winding wheel, a cage rail and a heap of black spoil. */
    static void headframe(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(14, s.h);
        c.clearPlants(0, 1, 0, s.w - 1, h, s.d - 1);
        BlockState log = B.s(Blocks.DARK_OAK_LOG), beam = B.s(Blocks.STRIPPED_DARK_OAK_LOG), spoil = B.s(Blocks.BLACKSTONE);
        for (int x = 0; x < s.w; x++) for (int z = 0; z < s.d; z++) {
            c.foundation(x, -1, z, p.foundation, 6);
            c.set(x, 0, z, (x + z) % 3 == 0 ? B.s(Blocks.COBBLED_DEEPSLATE) : B.s(Blocks.COARSE_DIRT));
        }
        // the shaft collar
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            c.set(cx + dx, 0, cz + dz, edge ? B.s(Blocks.POLISHED_BLACKSTONE_BRICKS) : B.s(Blocks.IRON_BARS));
            if (edge) c.set(cx + dx, 1, cz + dz, B.s(Blocks.POLISHED_BLACKSTONE_BRICK_WALL));
        }
        // A-frame legs
        int fh = h - 3;
        for (int y = 1; y <= fh; y++) {
            int off = Math.max(1, 4 - (y * 3) / fh);
            for (int side : new int[]{-1, 1}) {
                c.set(cx + side * off, y, cz - 3, log);
                c.set(cx + side * off, y, cz + 3, log);
            }
            if (y % 4 == 0) for (int x = cx - off; x <= cx + off; x++) {
                c.set(x, y, cz - 3, B.axis(beam, Direction.Axis.X));
                c.set(x, y, cz + 3, B.axis(beam, Direction.Axis.X));
            }
        }
        for (int z = cz - 3; z <= cz + 3; z++) c.set(cx, fh + 1, z, B.axis(beam, Direction.Axis.Z));
        // the winding wheel
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            c.set(cx, fh - 2 + (int) Math.round(Math.sin(a) * 3), cz + (int) Math.round(Math.cos(a) * 3), B.s(Blocks.DARK_OAK_PLANKS));
        }
        c.set(cx, fh - 2, cz, B.s(Blocks.IRON_BLOCK));
        for (int y = 2; y < fh - 4; y++) c.set(cx, y, cz, B.s(Blocks.CHAIN));
        c.set(cx, 1, cz, B.s(Blocks.BARREL));
        // the spoil heap and a lamp
        for (int dx = -5; dx <= 5; dx++) for (int dz = -2; dz <= 2; dz++) {
            int hx = cx + 7 + dx / 2, hz = cz + dz;
            if (hx >= s.w) continue;
            double r = Math.sqrt(dx * dx / 25.0 + dz * dz / 4.0);
            int ht = (int) Math.round(3 * (1 - r));
            for (int y = 1; y <= ht; y++) c.set(Math.min(s.w - 1, hx), y, hz, y == ht && (dx + dz) % 3 == 0 ? B.s(Blocks.OBSIDIAN) : spoil);
        }
        Props.lantern(c, p, cx + 3, 3, cz + 3, true);
    }

    // ------------------------------------------------------------------ Wayrest: the harbour chain tower

    /** A round tower at the quay end of a chain boom: chain links and log floats run out along local +x. */
    static void chainTower(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int w = s.w, d = s.d, h = Math.max(20, s.h), cz = (d - 1) / 2;
        int cx = 5;
        pave(c, p, w, d, h);
        Landmarks.hollowCylinder(c, cx, 1, h - 6, cz, 4.6, p.cityWall, p.floor);
        for (int y = 5; y < h - 6; y += 6) Landmarks.disc(c, cx, y, cz, 3.6, p.floor);
        for (int y = 3; y < h - 8; y += 5) for (int i = 0; i < 4; i++) {
            Direction dd = Direction.from2DDataValue(i);
            c.set(cx + dd.getStepX() * 5, y, cz + dd.getStepZ() * 5, p.window);
        }
        Landmarks.ring(c, cx, h - 5, cz, 5.4, 1.3, p.cityWall2);
        Landmarks.ring(c, cx, h - 4, cz, 5.4, 1.0, p.cityWallWall);
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            c.set(cx + (int) Math.round(Math.cos(a) * 5.4), h - 3, cz + (int) Math.round(Math.sin(a) * 5.4), p.cityWall);
        }
        Landmarks.cone(c, cx, h - 4, cz, 4, 6, p.roofFull);
        c.set(cx, h + 3, cz, B.s(Blocks.LIGHTNING_ROD));
        for (int y = 1; y < h - 6; y++) c.set(cx, y, cz + 2, B.face(B.s(Blocks.LADDER), Direction.NORTH));
        c.air(cx, 1, cz - 4);
        c.air(cx, 2, cz - 4);
        // the lever house and the boom
        for (int x = 8; x < w; x++) {
            c.set(x, 0, cz, p.plaza2);
            if (x % 2 == 0) {
                c.set(x, 1, cz, B.axis(B.s(Blocks.OAK_LOG), Direction.Axis.X));
            } else {
                c.set(x, 1, cz, B.axis(B.s(Blocks.CHAIN), Direction.Axis.X));
            }
            if (x % 6 == 2) c.set(x, 2, cz, B.s(Blocks.LANTERN));
        }
        for (int dz = -1; dz <= 1; dz += 2) for (int x = 8; x < w; x += 3) c.set(x, 1, cz + dz * 2, B.s(Blocks.OAK_FENCE));
        c.set(cx + 6, 1, cz, B.s(Blocks.LEVER));
    }

    // ------------------------------------------------------------------ awning streets

    /** A covered bazaar lane running along local z: striped awnings on posts over rows of stalls. {@code name} = colour. */
    static void awningRow(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int w = s.w, d = s.d, h = Math.min(7, Math.max(5, s.h));
        String col = s.name.isEmpty() ? "red" : s.name;
        BlockState a1 = colour(col, "wool", Blocks.RED_WOOL), a2 = B.s(Blocks.WHITE_WOOL);
        c.clearPlants(0, 1, 0, w - 1, h + 1, d - 1);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.set(x, 0, z, (x + z) % 4 == 0 ? p.plaza2 : p.plaza);
            c.foundation(x, -1, z, p.foundation, 6);
        }
        for (int z = 1; z < d - 1; z++) {
            for (int x = 0; x < w; x++) c.set(x, h, z, (z / 2) % 2 == 0 ? a1 : a2);
            if (z % 5 == 1) {
                for (int y = 1; y < h; y++) {
                    c.set(0, y, z, p.fence);
                    c.set(w - 1, y, z, p.fence);
                }
                Props.lantern(c, p, 1, h - 1, z, true);
                Props.lantern(c, p, w - 2, h - 1, z, true);
            }
            if (z % 5 == 3) {
                int kind = (z / 5) % 3;
                BlockState table = kind == 0 ? B.s(Blocks.BARREL) : kind == 1 ? B.s(Blocks.COMPOSTER) : B.s(Blocks.LOOM);
                c.set(1, 1, z, table);
                c.set(w - 2, 1, z, kind == 0 ? B.s(Blocks.COMPOSTER) : table);
                c.set(1, 2, z, B.slab(p.slab, false));
            }
        }
    }

    // ------------------------------------------------------------------ Elden Root: canopy platforms

    /** A ring-shaped walkway round the trunk (the middle of the footprint stays clear), braced on root-posts. */
    static void canopyPlatform(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        double R = Math.min(s.w, s.d) / 2.0 - 0.5, inner = Math.max(2.5, R * 0.35);
        BlockState plank = p.planks, rail = p.fence, log = B.id("skycraft:graht_oak_log", Blocks.JUNGLE_LOG);
        for (int dx = -(int) R; dx <= R; dx++) for (int dz = -(int) R; dz <= R; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > R || dist < inner) continue;
            c.set(cx + dx, 0, cz + dz, (dx + dz) % 4 == 0 ? p.floor2 : plank);
            if (dist > R - 1) c.set(cx + dx, 1, cz + dz, rail);
        }
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int px = cx + (int) Math.round(Math.cos(a) * (R - 1)), pz = cz + (int) Math.round(Math.sin(a) * (R - 1));
            c.foundation(px, -1, pz, B.axis(log, Direction.Axis.Y), 60);
            Props.lantern(c, p, px, 2, pz, false);
        }
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + Math.PI / 4;
            int px = cx + (int) Math.round(Math.cos(a) * (inner + R) / 2), pz = cz + (int) Math.round(Math.sin(a) * (inner + R) / 2);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 1) c.setIfOpen(cx + (px - cx) + dx, 3, cz + (pz - cz) + dz, Landmarks.leaves(B.id("skycraft:graht_oak_leaves", Blocks.JUNGLE_LEAVES)));
            }
        }
    }

    // ------------------------------------------------------------------ Imperial City: the Arboretum

    /** A glasshouse: a copper-ribbed glass hemisphere over a garden of trees and flower beds. */
    static void glassDome(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(12, s.h);
        double R = Math.min(s.w, s.d) / 2.0 - 1;
        c.clearPlants(0, 1, 0, s.w - 1, h + 1, s.d - 1);
        BlockState glass = p.glass, rib = B.s(Blocks.WAXED_OXIDIZED_COPPER), curb = p.cityWall;
        for (int dx = -(int) R; dx <= R; dx++) for (int dz = -(int) R; dz <= R; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > R + 0.3) continue;
            c.foundation(cx + dx, -1, cz + dz, p.foundation, 6);
            boolean path = Math.abs(dx) <= 1 || Math.abs(dz) <= 1;
            c.set(cx + dx, 0, cz + dz, dist > R - 1 ? curb : path ? p.plaza : B.s(Blocks.GRASS_BLOCK));
            if (!path && dist < R - 1.5) {
                int hsh = (dx * 31 + dz * 17) & 15;
                if (hsh == 0) {
                    for (int y = 1; y <= 4; y++) c.set(cx + dx, y, cz + dz, B.s(Blocks.OAK_LOG));
                    Landmarks.disc(c, cx + dx, 5, cz + dz, 1.5, Landmarks.leaves(B.s(Blocks.OAK_LEAVES)));
                } else if (hsh < 4) {
                    c.set(cx + dx, 1, cz + dz, hsh == 1 ? B.s(Blocks.ROSE_BUSH) : hsh == 2 ? B.s(Blocks.PEONY) : B.s(Blocks.LILAC));
                }
            }
        }
        int dh = (int) Math.min(h, R);
        for (int k = 1; k <= dh; k++) {
            double r = R * Math.sqrt(Math.max(0, 1 - (k / (double) (dh + 1)) * (k / (double) (dh + 1))));
            int ri = (int) Math.ceil(r);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.4 || dist < r - 1.1) continue;
                boolean ribbed = Math.abs(dx) <= 0 || Math.abs(dz) <= 0 || Math.abs(Math.abs(dx) - Math.abs(dz)) == 0 || k % 6 == 0;
                c.set(cx + dx, k, cz + dz, ribbed ? rib : glass);
            }
        }
        c.set(cx, dh + 1, cz, rib);
        for (int y = 1; y <= 3; y++) for (int x = cx - 1; x <= cx + 1; x++) c.air(x, y, cz - (int) R);
    }

    // ------------------------------------------------------------------ World Wonders (115..118)

    /**
     * Adamantine Tower (Direnni Tower / Ur-Tower, Isle of Balfiera):
     * a monumental 144-block fluted metallic cylinder-spire of polished deepslate, iron ribs, and smooth quartz
     * rising out of an octagonal Direnni bastion courtyard with the Aedric Zero-Stone core inside.
     */
    static void adamantineTower(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(120, s.h);
        double R = Math.min(s.w, s.d) / 2.0 - 1;
        if (c.recording()) {
            c.entrance(cx, 1, 0, p.door, Direction.SOUTH);
            return;
        }
        pave(c, p, s.w, s.d, Math.min(h, 48));
        BlockState metal = B.s(Blocks.POLISHED_DEEPSLATE);
        BlockState rib = B.s(Blocks.IRON_BLOCK);
        BlockState band = B.s(Blocks.SMOOTH_QUARTZ);
        BlockState core = B.s(Blocks.SEA_LANTERN);
        // Octagonal Direnni outer curtain wall
        for (int x = 0; x < s.w; x++) for (int z = 0; z < s.d; z++) {
            double dist = Math.hypot(x - cx, z - cz);
            if (Math.abs(dist - R) < 1.1 && !(z < cz && Math.abs(x - cx) <= 1)) {
                for (int y = 1; y <= 9; y++) {
                    c.set(x, y, z, y == 9 ? band : B.s(Blocks.DEEPSLATE_BRICKS));
                }
            }
        }
        // Fluted metallic Ur-Tower shaft (0..h)
        double r0 = Math.max(6.5, R - 4.0);
        for (int y = 1; y <= h; y++) {
            double t = y / (double) h;
            double rad = r0 * (1.0 - 0.55 * t * t);
            int ri = (int) Math.ceil(rad + 1);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double dist = Math.hypot(dx, dz);
                double ang = Math.atan2(dz, dx);
                double flute = rad * (1.0 + 0.1 * Math.cos(8 * ang));
                if (dist > flute) continue;
                if (dist <= 1.2) {
                    c.set(cx + dx, y, cz + dz, y % 4 == 0 ? core : rib);
                } else if (dist >= flute - 1.15) {
                    if (y <= 4 && dz < 0 && Math.abs(dx) <= 1) continue;
                    boolean isRib = Math.cos(8 * ang) > 0.65;
                    c.set(cx + dx, y, cz + dz, isRib ? rib : (y % 16 == 0 ? band : metal));
                } else if (y % 16 == 0) {
                    c.set(cx + dx, y, cz + dz, B.s(Blocks.DEEPSLATE_TILES));
                } else if (y < 24) {
                    c.air(cx + dx, y, cz + dz);
                }
            }
        }
        c.set(cx, h + 1, cz, core);
        c.set(cx, h + 2, cz, B.s(Blocks.LIGHTNING_ROD));
    }

    /**
     * Baar Dau (The Ministry of Truth, Vivec):
     * a hollowed levitating meteor suspended above Vivec's Temple plaza with mooring chains, Velothi wooden
     * catwalks, prison cells, and Ordinator watch-posts.
     */
    static void baarDau(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(48, s.h);
        if (c.recording()) {
            c.entrance(cx, 1, 0, p.door, Direction.SOUTH);
            return;
        }
        pave(c, p, s.w, s.d, h);
        double R = Math.min(s.w, s.d) / 2.0 - 1.5;
        // Temple plaza pedestal & 4 mooring chain pylons at the corners
        int off = Math.max(4, (int) (R * 0.6));
        int[][] pylons = {{cx - off, cz - off}, {cx + off, cz - off}, {cx - off, cz + off}, {cx + off, cz + off}};
        for (int[] py : pylons) {
            for (int y = 1; y <= 20; y++) {
                c.set(py[0], y, py[1], y <= 4 ? B.s(Blocks.WAXED_COPPER_BLOCK) : B.s(Blocks.CHAIN));
            }
        }
        // Suspended volcanic meteor (y = 14..h-2)
        int yMid = 14 + (h - 16) / 2;
        double ry = (h - 16) / 2.0;
        for (int y = 14; y <= h - 2; y++) {
            double ny = (y - yMid) / ry;
            double baseR = R * Math.sqrt(Math.max(0, 1.0 - ny * ny));
            int ri = (int) Math.ceil(baseR + 1.5);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double ang = Math.atan2(dz, dx);
                double crag = baseR + 1.2 * Math.sin(ang * 5 + y * 0.35) + 0.7 * Math.cos(ang * 9 - y * 0.5);
                double dist = Math.hypot(dx, dz);
                if (dist > crag) continue;
                if (dist >= crag - 2.0) {
                    int roll = (dx * 19 + dz * 31 + y * 7) & 7;
                    BlockState rock = roll == 0 ? B.s(Blocks.MAGMA_BLOCK)
                            : roll < 3 ? B.s(Blocks.BLACKSTONE) : B.s(Blocks.DEEPSLATE);
                    c.set(cx + dx, y, cz + dz, rock);
                } else if (y == yMid - 5 || y == yMid || y == yMid + 5) {
                    c.set(cx + dx, y, cz + dz, dist < 2.5 ? B.s(Blocks.SOUL_LANTERN) : B.s(Blocks.POLISHED_DEEPSLATE));
                } else if (Math.abs(dist - (baseR * 0.55)) < 0.7 && y > yMid - 5 && y < yMid + 5) {
                    c.set(cx + dx, y, cz + dz, B.s(Blocks.IRON_BARS));
                } else {
                    c.air(cx + dx, y, cz + dz);
                }
            }
        }
        // Central suspended Ordinator lift scaffold from plaza into the belly of Baar Dau
        for (int y = 1; y <= yMid; y++) {
            c.set(cx, y, cz, B.s(Blocks.SCAFFOLDING));
        }
    }

    /**
     * Red Mountain Citadel & Ghostfence Bastion:
     * volcanic blackstone-and-brass Dwemer citadel keep flanked by glowing Ghostfence bone-and-copper energy pylons.
     */
    static void redMountainCitadel(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(36, s.h);
        if (c.recording()) {
            c.entrance(cx, 1, 0, p.door, Direction.SOUTH);
            return;
        }
        pave(c, p, s.w, s.d, h);
        double R = Math.min(s.w, s.d) / 2.0 - 2.0;
        BlockState dark = B.s(Blocks.POLISHED_BLACKSTONE_BRICKS);
        BlockState brass = B.s(Blocks.WAXED_COPPER_BLOCK);
        BlockState bone = B.s(Blocks.BONE_BLOCK);
        BlockState glow = B.s(Blocks.SEA_LANTERN);
        // Central stepped Dwemer-volcanic keep
        for (int y = 1; y <= h; y++) {
            double rad = (y < h / 2) ? R * 0.58 : R * 0.36;
            int ri = (int) Math.ceil(rad);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > rad) continue;
                if (dist >= rad - 1.1) {
                    if (y <= 4 && dz < 0 && Math.abs(dx) <= 1) continue;
                    c.set(cx + dx, y, cz + dz, y % 6 == 0 ? brass : dark);
                } else if (y == h / 2 || y == h) {
                    c.set(cx + dx, y, cz + dz, brass);
                }
            }
        }
        // 4 corner Ghostfence Pylons with arcane barrier beams
        int off = (int) Math.round(R - 1);
        int[][] corners = {{cx - off, cz - off}, {cx + off, cz - off}, {cx - off, cz + off}, {cx + off, cz + off}};
        for (int[] q : corners) {
            for (int y = 1; y <= 20; y++) {
                c.set(q[0], y, q[1], y >= 18 ? glow : (y % 4 == 0 ? brass : bone));
            }
        }
        for (int i = -off + 1; i <= off - 1; i++) {
            c.set(cx + i, 16, cz - off, B.s(Blocks.LIGHT_BLUE_STAINED_GLASS));
            c.set(cx + i, 16, cz + off, B.s(Blocks.LIGHT_BLUE_STAINED_GLASS));
            c.set(cx - off, 16, cz + i, B.s(Blocks.LIGHT_BLUE_STAINED_GLASS));
            c.set(cx + off, 16, cz + i, B.s(Blocks.LIGHT_BLUE_STAINED_GLASS));
        }
    }

    /**
     * Sunspire Dragon-Temple of Alkosh:
     * stepped sandstone-and-gold temple crowned by a gleaming golden dome and solar-prism obelisks.
     */
    static void sunspireTemple(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2, h = Math.max(36, s.h);
        if (c.recording()) {
            c.entrance(cx, 1, 0, p.door, Direction.SOUTH);
            return;
        }
        pave(c, p, s.w, s.d, h);
        double R = Math.min(s.w, s.d) / 2.0 - 1.5;
        BlockState sand = B.s(Blocks.SMOOTH_SANDSTONE);
        BlockState gold = B.s(Blocks.GOLD_BLOCK);
        BlockState light = B.s(Blocks.SHROOMLIGHT);
        for (int y = 1; y <= h; y++) {
            double t = y / (double) h;
            double rad = (t < 0.45) ? R * (1.0 - t * 0.5) : R * 0.77 * Math.pow(1.0 - (t - 0.45) / 0.55, 0.65);
            int ri = (int) Math.ceil(rad);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > rad) continue;
                if (dist >= rad - 1.15) {
                    if (y <= 5 && (Math.abs(dx) <= 1 || Math.abs(dz) <= 1)) continue;
                    c.set(cx + dx, y, cz + dz, t >= 0.45 ? (y % 5 == 0 ? light : gold) : (y % 4 == 0 ? gold : sand));
                }
            }
        }
        c.set(cx, h + 1, cz, light);
        c.set(cx, 1, cz, B.s(Blocks.BEACON));
    }
}
