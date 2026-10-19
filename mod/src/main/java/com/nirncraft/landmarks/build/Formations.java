package com.nirncraft.landmarks.build;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Tier L: natural formations 20 to 40 blocks across, shaped from noise and a handful of parameters (size, lean,
 * weathering, the rock set of the biome family) so no two stand alike. Everything rests on the real terrain: columns
 * start at the local ground and arches carry supports down to it.
 */
final class Formations {
    private Formations() {}

    // ------------------------------------------------------------------ shared shaping

    /** A leaning shaft: radius r0 at the foot to r1 at the top, drifting {@code lx, lz} blocks over its height. */
    static void shaft(Stage s, double cx, double cz, int y0, int h, double r0, double r1, double lx, double lz, double rough, int salt) {
        int max = (int) Math.ceil(Math.max(r0, r1) + Math.hypot(lx, lz) + 2);
        for (int y = 0; y <= h; y++) {
            double t = h == 0 ? 0 : (double) y / h;
            double rr = r0 + (r1 - r0) * t;
            double ccx = cx + lx * t, ccz = cz + lz * t;
            int bx = (int) Math.round(ccx), bz = (int) Math.round(ccz);
            for (int dx = -max; dx <= max; dx++) {
                for (int dz = -max; dz <= max; dz++) {
                    int x = bx + dx, z = bz + dz;
                    if (!s.within(x, z)) continue;
                    double d = Math.hypot(x - ccx, z - ccz);
                    double lim = rr + (s.n(x, y0 + y, z, 3.5, salt) - 0.5) * 2 * rough;
                    if (d <= lim) s.rock(x, y0 + y, z);
                }
            }
        }
    }

    /** An elliptical arch along local x: the span is {@code half} each side of x0, its crown {@code height} over y0. */
    static void arch(Stage s, int x0, int z0, double half, double height, double thick, double depth, double rough, int salt) {
        int xr = (int) Math.ceil(half + thick + 1), zr = (int) Math.ceil(depth / 2 + 1);
        int yr = (int) Math.ceil(height + thick + 1);
        for (int dx = -xr; dx <= xr; dx++) {
            for (int dz = -zr; dz <= zr; dz++) {
                int x = x0 + dx, z = z0 + dz;
                if (!s.within(x, z)) continue;
                // a rougher, narrower depth at the ends so the arch is not a slab
                double zEdge = Math.abs(dz) / (depth / 2.0 + 0.001);
                double wob = (s.n(x, 0, z, 4.5, salt) - 0.5) * 2 * rough;
                if (zEdge + wob * 0.5 > 1.0) continue;
                int g = s.top(x, z) - 1;
                boolean leg = false;
                for (int y = -1; y <= yr; y++) {
                    double ox = dx / (half + thick + wob), oy = y / (height + thick + wob);
                    double ix = dx / half, iy = y / height;
                    boolean outer = ox * ox + oy * oy <= 1.0;
                    boolean inner = ix * ix + iy * iy < 1.0;
                    if (y < 0) {
                        // below the base level: only the feet of the arch (the legs), standing down to the ground
                        if (outer && !inner && Math.abs(dx) > half * 0.7) leg = true;
                        continue;
                    }
                    if (outer && !inner) {
                        s.rock(x, y, z);
                        if (y == 0 && Math.abs(dx) > half * 0.7) leg = true;
                    }
                }
                if (leg || (Math.abs(dx) > half * 0.8)) {
                    for (int y = -1; y > g - 3 && y > -30; y--) {
                        if (s.solidAt(x, y, z)) break;
                        s.rock(x, y, z);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ rock arch

    static boolean rockArch(Stage s) {
        double half = (5 + s.size * 4) * (s.kind.style().equals("twin") ? 0.85 : 1.0);
        double h = 6 + s.size * 5;
        double thick = 2.2 + s.size * 1.6;
        double depth = 6 + s.size * 4;
        arch(s, 0, 0, half, h, thick, depth, 0.6, 1);
        if (s.kind.style().equals("twin")) {
            arch(s, 3, 9, half * 0.6, h * 0.6, thick * 0.8, depth * 0.7, 0.5, 2);
        }
        s.rubbleRing(0, 0, (int) (half + 5), 14);
        // weathering: moss on the crown, snow or sand drift by family
        for (int x = (int) -(half + thick); x <= half + thick; x++) {
            for (int z = -(int) (depth / 2); z <= depth / 2; z++) {
                for (int y = (int) (h + thick) + 1; y >= 0; y--) {
                    if (s.solidAt(x, y, z)) {
                        s.dress(x, y + 1, z);
                        break;
                    }
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ hoodoo field

    static boolean hoodoos(Stage s) {
        int n = 7 + s.vary(1, 0, 6);
        for (int i = 0; i < n; i++) {
            double a = s.r.nextDouble() * Math.PI * 2, d = Math.sqrt(s.r.nextDouble()) * (s.rad - 4);
            int cx = (int) Math.round(Math.cos(a) * d), cz = (int) Math.round(Math.sin(a) * d);
            if (!s.dry(cx, cz)) continue;
            int g = s.top(cx, cz) - 1;
            int h = 4 + s.r.nextInt(8) + (i == 0 ? 4 : 0);
            double base = 2.0 + s.r.nextDouble() * 1.4, neck = 0.9 + s.r.nextDouble() * 0.9, cap = neck + 1.1 + s.r.nextDouble() * 1.4;
            double lean = (s.r.nextDouble() - 0.5) * 1.6;
            shaft(s, cx, cz, g, (int) (h * 0.5), base, neck, lean * 0.5, 0, 0.5, 5 + i);
            shaft(s, cx + lean * 0.5, cz, g + (int) (h * 0.5), h - (int) (h * 0.5) - 2, neck, neck, lean * 0.5, 0, 0.35, 6 + i);
            shaft(s, cx + lean, cz, g + h - 1, 2, cap, cap * 0.8, 0, 0, 0.5, 7 + i);
            s.dress(cx, g + h + 2, cz);
        }
        s.rubbleRing(0, 0, s.rad - 3, 16);
        return true;
    }

    // ------------------------------------------------------------------ split boulder

    static boolean splitBoulder(Stage s) {
        double rx = 6 + s.size * 3, ry = 5 + s.size * 3, rz = 5 + s.size * 2;
        int g = s.top(0, 0) - 1;
        s.blob(0, g + ry * 0.7, 0, rx, ry, rz, 0.3, 1);
        // the cleft: a passage along z, 2-3 wide, narrowing towards the top and leaning
        int w = 1 + s.vary(2, 0, 1);
        double lean = (s.r.nextDouble() - 0.5) * 3;
        for (int z = -(int) rz - 3; z <= (int) rz + 3; z++) {
            for (int y = g + 1; y <= g + (int) (ry * 2) + 2; y++) {
                double t = (y - g) / (ry * 2);
                double c = lean * t + (s.n(0, y, z, 6, 9) - 0.5) * 1.4;
                double half = w + 0.4 - t * (w - 0.2);
                for (int x = (int) Math.floor(c - half); x <= (int) Math.ceil(c + half); x++) {
                    if (Math.abs(x - c) <= half && s.within(x, z)) s.air(x, y, z);
                }
            }
        }
        for (int z = -(int) rz - 2; z <= rz + 2; z++) s.set((int) Math.round(lean * 0.0), g, z, s.rubble(0, g, z));
        // a fallen slab and broken chunks beside it
        s.blob(rx + 2, g + 1.5, -rz * 0.4, 2.5, 2, 3, 0.3, 4);
        s.blob(-rx - 1, g + 1, rz * 0.5, 2, 1.5, 2, 0.3, 5);
        s.rubbleRing(0, 0, (int) rx + 5, 12);
        if (s.lush) s.vines(0, g + (int) ry, 0, 4);
        for (int i = 0; i < 6; i++) {
            int x = s.vary(40 + i, -(int) rx, (int) rx), z = s.vary(50 + i, -(int) rz, (int) rz);
            for (int y = g + (int) (ry * 1.7) + 2; y >= g; y--) if (s.solidAt(x, y, z)) { s.dress(x, y + 1, z); break; }
        }
        return true;
    }

    // ------------------------------------------------------------------ giant standing stone

    static boolean menhir(Stage s) {
        String style = s.kind.style();
        int g = s.top(0, 0) - 1;
        int h = 12 + (int) (s.size * 7);
        double r0 = 2.4 + s.size * 0.8;
        double lean = style.equals("leaning") ? 3 + s.size * 2 : (s.r.nextDouble() - 0.5) * 2;
        double lx = Math.cos(s.vary(1, 0, 7) * Math.PI / 4) * lean, lz = Math.sin(s.vary(1, 0, 7) * Math.PI / 4) * lean;
        int top = style.equals("broken") ? (int) (h * 0.62) : h;
        shaft(s, 0, 0, g, top, r0, r0 * 0.55, lx * top / h, lz * top / h, 0.45, 2);
        // carved bands (chiseled blocks) at three heights, and a glyph column down the face
        Block carved = s.rock.main().at(0) == Blocks.SANDSTONE || s.rock.main().at(0) == Blocks.RED_SANDSTONE
                ? Blocks.CHISELED_SANDSTONE : Blocks.CHISELED_STONE_BRICKS;
        for (int k = 1; k <= 3; k++) {
            int y = g + k * top / 4;
            double t = (double) (y - g) / h;
            int bx = (int) Math.round(lx * t), bz = (int) Math.round(lz * t);
            double rr = r0 + (r0 * 0.55 - r0) * t;
            for (int a = 0; a < 16; a++) {
                double ang = a * Math.PI / 8;
                int x = bx + (int) Math.round(Math.cos(ang) * (rr + 0.2)), z = bz + (int) Math.round(Math.sin(ang) * (rr + 0.2));
                if (s.solidAt(x, y, z)) s.set(x, y, z, carved);
            }
        }
        if (style.equals("broken")) {
            // the top lies where it fell, in three pieces
            for (int i = 0; i < 3; i++) {
                int x = 5 + i * 3, z = -3 + i * 3;
                shaft(s, x, z, s.top(x, z) - 1, 2, 1.8 - i * 0.3, 1.2, 1.5, 0.5, 0.3, 20 + i);
            }
        } else {
            s.set(0, g + top + 1, 0, s.chance(3, 0.4) ? Blocks.AIR.defaultBlockState() : s.rock.cap().state(7));
        }
        // lesser stones lying about, offerings at the foot
        int stones = 3 + s.vary(5, 0, 4);
        for (int i = 0; i < stones; i++) {
            double a = i * Math.PI * 2 / stones + s.r.nextDouble();
            int x = (int) Math.round(Math.cos(a) * (r0 + 4 + s.r.nextInt(5))), z = (int) Math.round(Math.sin(a) * (r0 + 4 + s.r.nextInt(5)));
            if (!s.within(x, z) || !s.dry(x, z)) continue;
            int gy = s.top(x, z);
            int len = 2 + s.r.nextInt(3);
            Direction.Axis ax = s.r.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z;
            for (int k = 0; k < len; k++) {
                int xx = x + (ax == Direction.Axis.X ? k : 0), zz = z + (ax == Direction.Axis.Z ? k : 0);
                if (s.dry(xx, zz)) s.rock(xx, s.top(xx, zz), zz);
            }
            s.dress(x, gy + 1, z);
        }
        s.lying(2, g + 1, -(int) r0 - 1, new ItemStack(s.chance(8, 0.5) ? Items.BONE : Items.FEATHER));
        s.rubbleRing(0, 0, 8, 10);
        return true;
    }

    // ------------------------------------------------------------------ sinkhole

    static boolean sinkhole(Stage s) {
        double R = 6 + s.size * 5;
        int depth = 5 + (int) (s.size * 5);
        boolean pond = s.kind.style().equals("pond");
        int g = s.top(0, 0) - 1;
        int max = (int) R + 3;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx, dz) + (s.n(dx, 0, dz, 5, 3) - 0.5) * 4;
                if (d > R) continue;
                double t = d / R;
                int dd = (int) Math.round(depth * (1 - t * t * (3 - 2 * t)));
                if (dd < 1) continue;
                int top = Math.max(s.top(dx, dz) - 1, g);
                for (int y = top + 2; y > top - dd; y--) s.air(dx, y, dz);
                int floor = top - dd;
                boolean wetFloor = pond && d < R * 0.45;
                if (wetFloor) {
                    s.set(dx, floor, dz, Blocks.CLAY);
                    s.set(dx, floor + 1, dz, Blocks.WATER);
                    if (s.chance(dx * 3 + dz, 0.15)) s.air(dx, floor + 2, dz);
                } else {
                    s.set(dx, floor, dz, s.lush ? Blocks.MOSS_BLOCK.defaultBlockState() : s.rk(dx, floor, dz));
                    if (s.lush && s.chance(dx * 7 + dz, 0.25)) s.set(dx, floor + 1, dz, Blocks.FERN);
                    else if (s.chance(dx * 5 + dz, 0.12)) s.set(dx, floor + 1, dz, s.rubble(dx, floor, dz));
                }
                // the lip: rubble spilled over the rim
                if (t > 0.82 && s.chance(dx * 11 + dz, 0.35)) s.set(dx, top + 1, dz, s.rubble(dx, top, dz));
                if (s.lush && t > 0.7 && s.chance(dx * 13 + dz, 0.3)) s.vines(dx, top, dz, 3 + s.r.nextInt(4));
            }
        }
        // a fallen tree and a rock in the pit
        s.blob(R * 0.3, g - depth + 2, -R * 0.2, 2.2, 1.8, 2.2, 0.3, 4);
        return true;
    }

    // ------------------------------------------------------------------ collapsed tower

    static boolean towerRuin(Stage s) {
        int g = s.top(0, 0) - 1;
        double R = 3.8 + s.size * 1.8;
        int H = 10 + (int) (s.size * 7);
        Block wall = s.b.pal.brick();
        Block mossy = s.b.pal.mossy();
        Block stone = s.b.pal.stone();
        double breakAng = s.r.nextDouble() * Math.PI * 2;
        int max = (int) R + 2;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > R + 0.5) continue;
                boolean shell = d > R - 1.4;
                double ang = Math.atan2(dz, dx);
                double da = Math.abs(Math.atan2(Math.sin(ang - breakAng), Math.cos(ang - breakAng)));
                // taller away from the break, a ragged crown, nearly gone on the collapsed side
                double hh = H * (0.25 + 0.75 * Math.min(1.0, da / 2.2)) * (0.8 + 0.5 * s.n(dx, 0, dz, 3, 5));
                for (int y = g - 2; y <= g + hh; y++) {
                    if (shell) {
                        boolean window = y > g + 3 && (y - g) % 5 == 3 && Math.abs(Math.sin(ang * 2)) > 0.93;
                        if (window) continue;
                        s.set(dx, y, dz, s.chance(dx * 17 + y * 3 + dz, 0.22 + s.wear * 0.2) ? mossy.defaultBlockState() : wall.defaultBlockState());
                    } else if (y <= g) {
                        s.set(dx, y, dz, stone.defaultBlockState());
                    } else if ((y - g) % 6 == 0 && y < g + hh - 2 && s.chance(dx * 5 + dz + y, 0.55)) {
                        s.set(dx, y, dz, s.wood.planks());
                    }
                }
            }
        }
        // the fallen half: a fan of rubble down the collapsed side
        for (int i = 0; i < 70; i++) {
            double a = breakAng + (s.r.nextDouble() - 0.5) * 1.3, d = R + 1 + s.r.nextDouble() * 9;
            int x = (int) Math.round(Math.cos(a) * d), z = (int) Math.round(Math.sin(a) * d);
            if (!s.within(x, z) || !s.dry(x, z)) continue;
            int y = s.top(x, z);
            s.set(x, y, z, s.chance(i, 0.25) ? mossy.defaultBlockState() : s.rubble(x, y, z));
            if (s.chance(i + 3, 0.2)) s.set(x, y + 1, z, wall.defaultBlockState());
        }
        s.lantern(0, g + 1, 0, true);
        s.set(0, g + 1, 1, Blocks.COBWEB);
        if (s.lush) s.vines((int) R, g + 3, 0, 5);
        return true;
    }

    // ------------------------------------------------------------------ nest ledges

    static boolean cliffNests(Stage s) {
        int n = 2 + s.vary(1, 0, 1);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + s.r.nextDouble();
            int cx = (int) Math.round(Math.cos(a) * 6), cz = (int) Math.round(Math.sin(a) * 6);
            int g = s.top(cx, cz) - 1;
            int h = 10 + s.r.nextInt(8);
            shaft(s, cx, cz, g, h, 3.6, 2.2, (s.r.nextDouble() - 0.5) * 3, (s.r.nextDouble() - 0.5) * 3, 0.9, 3 + i);
            // three ledges with nests
            for (int k = 0; k < 3; k++) {
                int y = g + 4 + k * 3 + s.r.nextInt(2);
                double la = s.r.nextDouble() * Math.PI * 2;
                int lx = cx + (int) Math.round(Math.cos(la) * 3.2), lz = cz + (int) Math.round(Math.sin(la) * 3.2);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 1 || !s.within(lx + dx, lz + dz)) continue;
                    s.rock(lx + dx, y, lz + dz);
                }
                s.set(lx, y + 1, lz, Blocks.HAY_BLOCK);
                s.set(lx + 1, y + 1, lz, s.chance(k + i, 0.5) ? Blocks.OAK_FENCE : Blocks.AIR);
                s.lying(lx, y + 2, lz, new ItemStack(s.chance(k * 3 + i, 0.5) ? Items.FEATHER : Items.EGG));
                // the stains below a ledge
                for (int dy = 1; dy <= 3; dy++) {
                    int sx = lx + (int) Math.round(Math.cos(la) * 1), sz = lz + (int) Math.round(Math.sin(la) * 1);
                    if (s.solidAt(sx, y - dy, sz)) {
                        s.set(sx, y - dy, sz, com.nirncraft.worldgen.VanillaBlocks.WHITE_TERRACOTTA);
                        break;
                    }
                }
            }
        }
        s.rubbleRing(0, 0, 14, 20);
        for (int i = 0; i < 12; i++) s.lying(s.vary(60 + i, -8, 8), s.top(0, 0) , s.vary(80 + i, -8, 8), new ItemStack(Items.FEATHER));
        return true;
    }

    // ------------------------------------------------------------------ petrified tree

    static boolean petrifiedTree(Stage s) {
        int g = s.top(0, 0) - 1;
        int h = 11 + (int) (s.size * 6);
        double r = 2.2 + s.size * 0.9;
        shaft(s, 0, 0, g, h, r, r * 0.45, (s.r.nextDouble() - 0.5) * 2.5, (s.r.nextDouble() - 0.5) * 2.5, 0.4, 1);
        // flared roots
        int roots = 4 + s.vary(2, 0, 2);
        for (int i = 0; i < roots; i++) {
            double a = i * Math.PI * 2 / roots + s.r.nextDouble() * 0.5;
            int len = 5 + s.r.nextInt(5);
            for (int k = 1; k <= len; k++) {
                int x = (int) Math.round(Math.cos(a) * (r + k)), z = (int) Math.round(Math.sin(a) * (r + k));
                if (!s.within(x, z)) break;
                int gy = s.top(x, z) - 1;
                int th = Math.max(1, 3 - k / 3);
                for (int y = gy - 1; y <= gy + th; y++) s.rock(x, y, z);
            }
        }
        // broken branches
        int br = 3 + s.vary(3, 0, 2);
        for (int i = 0; i < br; i++) {
            int y0 = g + h / 2 + s.r.nextInt(h / 2);
            double a = s.r.nextDouble() * Math.PI * 2;
            double t = (double) (y0 - g) / h;
            double bx = 0, bz = 0;
            int len = 4 + s.r.nextInt(6);
            for (int k = 1; k <= len; k++) {
                bx = Math.cos(a) * k;
                bz = Math.sin(a) * k;
                int x = (int) Math.round(bx), z = (int) Math.round(bz);
                int y = y0 + (int) (k * 0.55);
                if (!s.within(x, z)) break;
                s.rock(x, y, z);
                if (k < len / 2) s.rock(x, y - 1, z);
            }
        }
        s.rubbleRing(0, 0, 10, 14);
        return true;
    }

    // ------------------------------------------------------------------ bone field

    static boolean boneField(Stage s) {
        String style = s.kind.style();
        int len = 24 + s.vary(1, 0, 14);
        int half = len / 2;
        Block bone = style.equals("mammoth") ? Blocks.BONE_BLOCK : Blocks.BONE_BLOCK;
        // spine
        for (int x = -half; x <= half; x++) {
            int g = s.top(x, 0);
            double wob = (s.n(x, 0, 0, 7, 2) - 0.5) * 3;
            int z = (int) Math.round(wob);
            if (!s.dry(x, z)) continue;
            int gy = s.top(x, z);
            if (x % 2 == 0) s.set(x, gy, z, bone);
            else s.set(x, gy, z, s.rubble(x, gy, z));
            if (x % 3 == 0 && Math.abs(x) < half - 5) {
                // a rib pair: arcs over the spine, half buried
                int rh = 5 + (int) (3 * Math.sin((x + half) * Math.PI / len)) + (style.equals("leviathan") ? 2 : 0);
                for (int side = -1; side <= 1; side += 2) {
                    for (int k = 0; k <= rh; k++) {
                        double t = (double) k / rh;
                        int zz = z + side * (int) Math.round(Math.sin(t * Math.PI * 0.62) * (rh * 0.7));
                        int yy = gy - 1 + (int) Math.round(Math.sin(t * Math.PI * 0.62) * rh * 0.55 + t * 2);
                        if (!s.within(x, zz)) continue;
                        if (s.chance(x * 7 + k + side, 0.1)) continue;
                        s.set(x, yy, zz, bone);
                    }
                }
            }
        }
        // the skull at one end
        int sx = -half - 2, g = s.top(sx, 0) - 1;
        int sl = 5, sw = 3, sh = 3;
        for (int dx = 0; dx < sl; dx++) for (int dz = -sw; dz <= sw; dz++) for (int dy = 0; dy < sh; dy++) {
            double taper = 1.0 - dx * 0.12;
            if (Math.abs(dz) > sw * taper) continue;
            boolean eye = dx == 2 && dy == 1 && Math.abs(dz) == 2;
            if (eye) continue;
            s.set(sx - dx, g + dy, dz, Blocks.BONE_BLOCK);
        }
        if (style.equals("dragon")) {
            // horns and a spiked crest
            for (int k = 0; k < 4; k++) {
                s.set(sx - 4 + k, g + sh + k / 2, 3, Blocks.BONE_BLOCK);
                s.set(sx - 4 + k, g + sh + k / 2, -3, Blocks.BONE_BLOCK);
            }
            for (int k = 0; k < 3; k++) s.set(sx - 1 - k * 2, g + sh, 0, Blocks.BONE_BLOCK);
            // a wing bone leaning at the shoulder
            for (int k = 0; k < 10; k++) s.set(-4 + k, s.top(-4 + k, 6) + (int) (k * 0.7), 6 + k / 3, Blocks.BONE_BLOCK);
        } else if (style.equals("mammoth")) {
            // curved tusks of calcite
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 0; k < 8; k++) {
                    int x = sx - 4 - k / 2 + 2, z = side * (3 + (int) (Math.sin(k * 0.4) * 2));
                    s.set(x - 2, g + 1 + (int) (Math.sin(k * 0.45) * 3), z + side * (k / 3), Blocks.CALCITE);
                }
            }
        }
        // scattered smaller bones and lost things
        s.rubbleRing(0, 0, 14, 14);
        s.b.scatter(12, 10, Blocks.BONE_BLOCK.defaultBlockState(), false);
        return true;
    }

    // ------------------------------------------------------------------ hot spring terrace

    static boolean terraces(Stage s) {
        Block rim = s.fam.equals("tundra") || s.fam.equals("mountain") ? Blocks.CALCITE : s.ashy ? Blocks.TUFF : Blocks.CALCITE;
        int tiers = 3;
        int g = s.top(0, 0) - 1;
        double a0 = s.r.nextDouble() * Math.PI;
        for (int t = 0; t < tiers; t++) {
            int cx = (int) Math.round(Math.cos(a0) * (t - 1) * 6), cz = (int) Math.round(Math.sin(a0) * (t - 1) * 6);
            double R = 6.0 - t * 0.6 + s.size;
            int y = g + t;
            int max = (int) R + 2;
            for (int dx = -max; dx <= max; dx++) {
                for (int dz = -max; dz <= max; dz++) {
                    double d = Math.hypot(dx, dz) + (s.n(cx + dx, 0, cz + dz, 4, 6 + t) - 0.5) * 2.2;
                    int x = cx + dx, z = cz + dz;
                    if (!s.within(x, z) || d > R) continue;
                    // platform body down to the ground
                    for (int yy = y - 3; yy <= y; yy++) {
                        if (yy > g - 3 && !s.solidAt(x, yy, z) || yy == y) s.set(x, yy, z, yy == y ? (d > R - 1.6 ? rim.defaultBlockState() : Blocks.WATER.defaultBlockState())
                                : (s.chance(x * 3 + yy + z, 0.2) ? com.nirncraft.worldgen.VanillaBlocks.YELLOW_TERRACOTTA.defaultBlockState() : rim.defaultBlockState()));
                    }
                    if (d <= R - 1.6) {
                        s.set(x, y - 1, z, s.chance(x * 5 + z, 0.1) ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.CLAY.defaultBlockState());
                        s.air(x, y + 1, z);
                    } else {
                        s.air(x, y + 1, z);
                        if (s.chance(x * 11 + z, 0.25)) s.set(x, y + 1, z, rim.defaultBlockState());
                    }
                }
            }
        }
        // steam: signal-fire campfires (their smoke reads as vents from far off)
        for (int i = 0; i < 2; i++) {
            int x = s.vary(70 + i, -10, 10), z = s.vary(90 + i, -8, 8);
            if (!s.dry(x, z)) continue;
            int y = s.top(x, z);
            if (s.chance(i, 0.3)) continue;
            s.set(x, y - 1, z, Blocks.HAY_BLOCK);
            s.set(x, y, z, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, true));
        }
        s.set(8, g + 1, 8, Blocks.AIR);
        s.rubbleRing(0, 0, 12, 10);
        return true;
    }

    // ------------------------------------------------------------------ crystal geode

    static boolean geode(Stage s) {
        int g = s.top(0, 0) - 1;
        double R = 5 + s.size * 2.4;
        double cy = g + R * 0.45;
        s.blob(0, cy, 0, R, R * 0.85, R, 0.35, 1);
        double ri = R - 2.3;
        // hollow it and line it
        int max = (int) R + 1;
        for (int dx = -max; dx <= max; dx++) for (int dz = -max; dz <= max; dz++) for (int dy = -max; dy <= max; dy++) {
            double d = Math.sqrt(dx * dx + dy * dy * 1.15 + dz * dz);
            int x = dx, y = (int) cy + dy, z = dz;
            if (!s.within(x, z)) continue;
            if (d <= ri) {
                if (y >= g) s.air(x, y, z);
            } else if (d <= ri + 1.2 && y >= g - 1 && s.solidAt(x, y, z)) {
                s.set(x, y, z, d <= ri + 0.6 && s.chance(x * 5 + y * 3 + z, 0.18) ? Blocks.BUDDING_AMETHYST.defaultBlockState()
                        : Blocks.AMETHYST_BLOCK.defaultBlockState());
            } else if (d <= ri + 2.0 && y >= g && s.solidAt(x, y, z)) {
                s.set(x, y, z, Blocks.CALCITE);
            }
        }
        for (int i = 0; i < 18; i++) {
            int x = s.vary(100 + i, -(int) ri + 1, (int) ri - 1), z = s.vary(130 + i, -(int) ri + 1, (int) ri - 1);
            int y = g;
            if (s.solidAt(x, y - 1, z) || s.get(x, y - 1, z).is(Blocks.AMETHYST_BLOCK)) {
                s.set(x, y, z, Blocks.AMETHYST_CLUSTER);
            }
        }
        // the way in: a crack on one side
        int ex = (int) -R;
        for (int dx = -(int) R - 1; dx <= -(int) ri + 1; dx++) {
            for (int dy = 0; dy <= 3; dy++) for (int dz = -1; dz <= 0; dz++) s.air(dx, g + dy, dz);
        }
        s.rubbleRing(0, 0, (int) R + 4, 10);
        s.dress(ex - 2, s.top(ex - 2, 0), 0);
        return true;
    }

    // ------------------------------------------------------------------ frozen waterfall

    static boolean iceFall(Stage s) {
        int g = s.top(0, 0) - 1;
        int H = 10 + (int) (s.size * 5);
        int W = 7 + s.vary(1, 0, 3);
        // the cliff: a tall block of rock whose face looks towards -z
        for (int x = -W; x <= W; x++) {
            for (int z = 0; z <= 9; z++) {
                if (!s.within(x, z)) continue;
                double edge = Math.abs(x) / (double) W;
                int top = g + (int) (H * (1 - 0.5 * edge * edge) * (1 - 0.06 * z) + (s.n(x, 0, z, 5, 4) - 0.5) * 3);
                for (int y = g - 1; y <= top; y++) s.rock(x, y, z);
            }
        }
        // the frozen curtain down the face, with icicles and a plunge pool of ice
        Block[] ice = {Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.ICE};
        for (int x = -W + 2; x <= W - 2; x++) {
            double edge = Math.abs(x) / (double) W;
            int top = g + (int) (H * (1 - 0.5 * edge * edge)) - 1;
            double mid = (s.n(x, 0, 0, 3, 5) - 0.5) * 2;
            for (int y = top; y >= g; y--) {
                int thick = 1 + (s.n(x, y, 0, 4, 6) > 0.55 ? 1 : 0);
                for (int k = 0; k < thick; k++) {
                    s.set(x, y, -1 - k, ice[Math.floorMod(x * 3 + y + (int) (mid * 3), ice.length)]);
                }
            }
            int len = 1 + (int) (s.n(x, 0, 0, 2, 7) * 4);
            for (int k = 0; k < len; k++) s.set(x, top - 2 - k, -3, Blocks.ICE);
        }
        for (int x = -W; x <= W; x++) for (int z = -7; z <= -2; z++) {
            double d = Math.hypot(x * 0.7, z + 4.5);
            if (d > 5.5 || !s.within(x, z)) continue;
            s.set(x, s.top(x, z) - 1, z, s.chance(x + z * 9, 0.5) ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState());
            if (d < 3 && s.chance(x * 5 + z, 0.3)) s.set(x, s.top(x, z), z, Blocks.SNOW);
        }
        s.rubbleRing(0, -6, 9, 10);
        return true;
    }

    // ------------------------------------------------------------------ ash-glass dune

    static boolean glassDune(Stage s) {
        int g = s.top(0, 0) - 1;
        double a = s.r.nextDouble() * Math.PI;
        double ux = Math.cos(a), uz = Math.sin(a);
        double L = 14 + s.size * 5;
        Block shard = s.chance(1, 0.5) ? Blocks.TINTED_GLASS : com.nirncraft.worldgen.VanillaBlocks.BLACK_STAINED_GLASS;
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = -20; dz <= 20; dz++) {
                if (!s.within(dx, dz)) continue;
                double along = dx * ux + dz * uz, across = -dx * uz + dz * ux;
                double ridge = Math.max(0, 1 - Math.abs(across) / (6 + (s.n(dx, 0, dz, 6, 2) * 4)));
                double len = Math.max(0, 1 - Math.abs(along) / L);
                int h = (int) Math.round(7 * ridge * len * (0.7 + 0.5 * s.n(dx, 0, dz, 4, 3)));
                if (h < 1) continue;
                int gy = s.top(dx, dz) - 1;
                for (int y = gy; y <= gy + h; y++) {
                    BlockState st = y == gy + h && s.chance(dx * 7 + dz + y, 0.5) ? Blocks.COARSE_DIRT.defaultBlockState() : s.rk(dx, y, dz);
                    s.set(dx, y, dz, st);
                }
            }
        }
        // glass shards, leaning, and a few obsidian teeth on the crest
        int shards = 9 + s.vary(2, 0, 6);
        for (int i = 0; i < shards; i++) {
            double t = (s.r.nextDouble() - 0.5) * 2 * L * 0.8;
            double off = (s.r.nextDouble() - 0.5) * 8;
            int x = (int) Math.round(ux * t - uz * off), z = (int) Math.round(uz * t + ux * off);
            if (!s.within(x, z)) continue;
            int gy = s.top(x, z);
            int h = 3 + s.r.nextInt(5);
            double lx = (s.r.nextDouble() - 0.5) * 3, lz = (s.r.nextDouble() - 0.5) * 3;
            for (int k = 0; k < h; k++) {
                int xx = x + (int) Math.round(lx * k / h), zz = z + (int) Math.round(lz * k / h);
                s.set(xx, gy + k, zz, k == h - 1 && s.chance(i, 0.3) ? Blocks.OBSIDIAN.defaultBlockState() : shard.defaultBlockState());
            }
        }
        for (int i = 0; i < 3; i++) {
            int x = s.vary(40 + i, -12, 12), z = s.vary(50 + i, -12, 12);
            if (s.dry(x, z)) s.set(x, s.top(x, z), z, Blocks.CRYING_OBSIDIAN);
        }
        return true;
    }

    // ------------------------------------------------------------------ columns

    static boolean columns(Stage s) {
        int g0 = s.top(0, 0) - 1;
        double R = 7 + s.size * 5;
        int max = (int) R + 1;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx, dz) + (s.n(dx, 0, dz, 4, 3) - 0.5) * 3;
                if (d > R) continue;
                // 2x2 and 1x1 clusters give the hexagon feel: height from a per-cell hash
                int cx = Math.floorDiv(dx, 2), cz = Math.floorDiv(dz, 2);
                double hv = s.n(cx * 2, 0, cz * 2, 1.7, 9);
                int h = (int) Math.round((1 - d / R) * (5 + 9 * hv));
                if (h < 1) continue;
                int gy = Math.min(g0, s.top(dx, dz) - 1);
                for (int y = gy; y <= g0 + h; y++) s.rock(dx, y, dz);
            }
        }
        s.rubbleRing(0, 0, (int) R + 5, 16);
        return true;
    }

    // ------------------------------------------------------------------ sea stacks

    static boolean seaStacks(Stage s) {
        int n = 3 + s.vary(1, 0, 2);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + s.r.nextDouble() * 0.7;
            double d = 6 + s.r.nextDouble() * 8;
            int cx = (int) Math.round(Math.cos(a) * d), cz = (int) Math.round(Math.sin(a) * d);
            if (!s.within(cx, cz) || !s.dry(cx, cz)) continue;
            int g = s.top(cx, cz) - 1;
            int h = 7 + s.r.nextInt(10);
            shaft(s, cx, cz, g, h, 2.8 + s.r.nextDouble() * 1.5, 1.4 + s.r.nextDouble(), (s.r.nextDouble() - 0.5) * 2, (s.r.nextDouble() - 0.5) * 2, 0.8, 7 + i);
            if (s.chance(i, 0.4)) {
                // a small arch notch at the foot
                for (int dy = 0; dy <= 2; dy++) s.air(cx, g + 1 + dy, cz);
            }
            s.set(cx, g + h + 1, cz, s.chance(i + 4, 0.5) ? com.nirncraft.worldgen.VanillaBlocks.WHITE_TERRACOTTA.defaultBlockState() : s.rock.cap().state(3));
        }
        s.rubbleRing(0, 0, 16, 18);
        for (int i = 0; i < 6; i++) s.lying(s.vary(60 + i, -14, 14), 1, s.vary(80 + i, -14, 14), new ItemStack(Items.FEATHER));
        return true;
    }

    // ------------------------------------------------------------------ mushrooms

    static boolean mushrooms(Stage s) {
        int n = 4 + s.vary(1, 0, 4);
        boolean red = s.chance(1, 0.5);
        for (int i = 0; i < n; i++) {
            double a = s.r.nextDouble() * Math.PI * 2, d = Math.sqrt(s.r.nextDouble()) * (s.rad - 6);
            int cx = (int) Math.round(Math.cos(a) * d), cz = (int) Math.round(Math.sin(a) * d);
            if (!s.dry(cx, cz)) continue;
            int g = s.top(cx, cz);
            int h = 4 + s.r.nextInt(6);
            int cr = 2 + s.r.nextInt(3);
            boolean isRed = (i % 3 == 0) ? !red : red;
            Block cap = isRed ? Blocks.RED_MUSHROOM_BLOCK : Blocks.BROWN_MUSHROOM_BLOCK;
            for (int y = 0; y < h; y++) s.set(cx, g + y, cz, Blocks.MUSHROOM_STEM);
            for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++) for (int dy = 0; dy <= 2; dy++) {
                double dd = Math.hypot(dx, dz) + dy * 0.9 * (cr / 2.0);
                if (dd > cr + 0.3 || dd < cr - 1.5 - (dy == 2 ? 0 : 0.5) && dy < 2) continue;
                if (dy == 2 && Math.hypot(dx, dz) > cr - 1) continue;
                s.set(cx + dx, g + h - 1 + dy, cz + dz, cap);
            }
            s.set(cx, g + h + 1, cz, cap);
            if (s.chance(i, 0.5)) s.set(cx + 1, g, cz, Blocks.RED_MUSHROOM);
        }
        for (int i = 0; i < 10; i++) {
            int x = s.vary(100 + i, -s.rad + 3, s.rad - 3), z = s.vary(120 + i, -s.rad + 3, s.rad - 3);
            if (s.dry(x, z) && s.free(x, s.top(x, z), z)) s.set(x, s.top(x, z), z, s.chance(i, 0.5) ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM);
        }
        return true;
    }
}
