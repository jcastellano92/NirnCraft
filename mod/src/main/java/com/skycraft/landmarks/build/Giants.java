package com.skycraft.landmarks.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Tier XL: shapes you see from a long way off. Footprints stay inside the 22-block reach of a site, so they are
 * tall rather than wide: a colossal tree, a fungal spire, the ribs of a leviathan, a natural bridge, needles of
 * rock, a crater lake, a mesa with a path, crystals, ice and glass. Each can hold a secret at the top or inside.
 */
final class Giants {
    private Giants() {}

    private static net.minecraft.world.level.BlockGetter level(Stage s) {
        return s.level;
    }

    private static BlockState leaves(Block b) {
        return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    /** The summit or root cache: a chest and a story, set with a lantern so it reads as deliberate. */
    static void cache(Stage s, int dx, int dy, int dz) {
        BlockPos c = s.chest(dx, dy, dz, Direction.SOUTH, "explore/cache");
        s.put(c, com.skycraft.landmarks.Lore.book("Left Here", com.skycraft.landmarks.Lore.person(s.site.province(),
                net.minecraft.util.RandomSource.create(s.seed ^ 0xA54FF53AL)), s.story("traveller")));
        s.lantern(dx + 1, dy, dz, false);
    }

    /** A rock step spiral around a shaft: from the ground to {@code top}, one block up every other step. */
    static void spiral(Stage s, double cx, double cz, double r, int y0, int top, double a0, double dir) {
        double a = a0;
        int y = y0;
        int steps = 0;
        while (y < top && steps < 600) {
            int x = (int) Math.round(cx + Math.cos(a) * r), z = (int) Math.round(cz + Math.sin(a) * r);
            if (s.within(x, z)) {
                s.rock(x, y, z);
                int x2 = (int) Math.round(cx + Math.cos(a) * (r + 1)), z2 = (int) Math.round(cz + Math.sin(a) * (r + 1));
                if (s.within(x2, z2)) s.rock(x2, y, z2);
                for (int k = 1; k <= 3; k++) {
                    s.air(x, y + k, z);
                    s.air(x2, y + k, z2);
                }
            }
            a += dir * (1.0 / Math.max(2.0, r));
            if (steps % 2 == 1) y++;
            steps++;
        }
    }

    // ------------------------------------------------------------------ colossal tree

    static boolean colossalTree(Stage s) {
        String shape = s.param("shape", "broadleaf");
        int g = s.top(0, 0) - 1;
        int H = 52 + (int) (s.size * 18);
        double r0 = 5.0 + s.size * 1.5;
        Block log = s.wood.log();
        BlockState logS = log.defaultBlockState();
        Block leafBlock = shape.equals("conifer") ? Blocks.SPRUCE_LEAVES : s.wood.leaves();
        if (s.fam.equals("crystal")) leafBlock = Blocks.CHERRY_LEAVES;
        // roots: a flare of buttress roots
        int roots = 6 + s.vary(1, 0, 3);
        for (int i = 0; i < roots; i++) {
            double a = i * Math.PI * 2 / roots + s.r.nextDouble() * 0.4;
            int len = 7 + s.r.nextInt(7);
            for (int k = 0; k <= len; k++) {
                double rr = r0 + k * 0.9;
                int x = (int) Math.round(Math.cos(a) * rr), z = (int) Math.round(Math.sin(a) * rr);
                if (!s.within(x, z)) break;
                int gy = s.top(x, z) - 1;
                int hh = Math.max(0, (int) Math.round(7 - k * 0.7));
                for (int y = gy - 1; y <= gy + hh; y++) {
                    s.set(x, y, z, logS);
                    int x2 = x + (int) Math.signum(-Math.sin(a)), z2 = z + (int) Math.signum(Math.cos(a));
                    if (hh > 2) s.set(x2, y, z2, logS);
                }
            }
        }
        // the trunk: hollow, slightly twisted, a door at the foot and a ladder to the crown platform
        double twist = (s.r.nextDouble() - 0.5) * 6;
        for (int y = 0; y <= H; y++) {
            double t = (double) y / H;
            double rr = r0 * (1 - 0.45 * t) + (y < 8 ? (8 - y) * 0.4 : 0);
            double cx = Math.sin(t * 3.1) * twist * 0.5, cz = Math.cos(t * 2.3) * twist * 0.35;
            int bx = (int) Math.round(cx), bz = (int) Math.round(cz);
            int R = (int) Math.ceil(rr + 2);
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    int x = bx + dx, z = bz + dz;
                    if (!s.within(x, z)) continue;
                    double d = Math.hypot(x - cx, z - cz) + (s.n(x, g + y, z, 4, 3) - 0.5) * 1.4;
                    if (d > rr) continue;
                    boolean hollow = d < rr - 1.6 && y > 0 && y < H - 6;
                    if (hollow) s.air(x, g + y, z);
                    else s.set(x, g + y, z, d > rr - 0.9 && s.chance(x * 5 + y + z, 0.12) ? Blocks.MOSS_BLOCK.defaultBlockState() : logS);
                }
            }
            if (y > 0 && y < H - 6) {
                int lz = bz + (int) Math.floor(rr - 1.9);
                if (s.get(bx, g + y, lz + 1).isSolidRender(level(s), s.b.at(bx, g + y, lz + 1)) || true) {
                    s.set(bx, g + y, lz, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
                }
            }
        }
        // a door into the trunk (south side) with a cache chamber at the foot
        for (int dy = 1; dy <= 3; dy++) for (int dz = (int) r0 - 3; dz <= (int) r0 + 2; dz++) s.air(0, g + dy, dz);
        s.set(0, g + 1, 0, Blocks.AIR);
        cache(s, 0, g + 1, -1);
        // branches
        int limbs = 7 + s.vary(2, 0, 3);
        for (int i = 0; i < limbs; i++) {
            double a = i * Math.PI * 2 / limbs + s.r.nextDouble() * 0.5;
            int y0 = g + (int) (H * (0.5 + 0.38 * s.r.nextDouble()));
            double len = 10 + s.r.nextDouble() * 9;
            double rise = 0.4 + s.r.nextDouble() * 0.5;
            for (int k = 1; k <= len; k++) {
                int x = (int) Math.round(Math.cos(a) * (r0 * 0.5 + k)), z = (int) Math.round(Math.sin(a) * (r0 * 0.5 + k));
                int y = y0 + (int) (k * rise) - (int) (k * k * 0.01);
                if (!s.within(x, z)) break;
                s.set(x, y, z, logS);
                if (k < len * 0.6) s.set(x, y - 1, z, logS);
            }
        }
        // the crown: layered blobs of leaves, lighter towards the edge
        if (!shape.equals("dead")) {
            int layers = 4;
            for (int L = 0; L < layers; L++) {
                double cy = g + H - 6 - L * 6;
                double rr = shape.equals("conifer") ? 6 + L * 4.5 : 13 + (L == 0 ? -4 : L == 1 ? 4 : 2);
                rr = Math.min(rr, s.rad - 1.5);
                for (int dx = -(int) rr; dx <= rr; dx++) {
                    for (int dz = -(int) rr; dz <= rr; dz++) {
                        for (int dy = -4; dy <= 4; dy++) {
                            double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 2.4);
                            double wob = (s.n(dx, (int) cy + dy, dz, 4.5, 8 + L) - 0.5) * 4;
                            if (d + wob > rr || !s.within(dx, dz)) continue;
                            int y = (int) cy + dy;
                            if (s.free(dx, y, dz)) s.set(dx, y, dz, leaves(leafBlock));
                        }
                    }
                }
            }
            // a crown platform and the summit cache among the leaves
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) s.set(dx, g + H - 5, dz, s.wood.planks());
        }
        s.rubbleRing(0, 0, 18, 22);
        return true;
    }

    // ------------------------------------------------------------------ fungal spire

    static boolean fungalSpire(Stage s) {
        int g = s.top(0, 0) - 1;
        int H = 38 + (int) (s.size * 12);
        double r0 = 4.0 + s.size;
        Block stem = Blocks.MUSHROOM_STEM;
        boolean red = s.chance(1, 0.4);
        Block cap = red ? Blocks.RED_MUSHROOM_BLOCK : Blocks.BROWN_MUSHROOM_BLOCK;
        for (int y = 0; y <= H; y++) {
            double t = (double) y / H;
            double rr = r0 * (1 - 0.35 * t) + (y < 6 ? (6 - y) * 0.5 : 0);
            int R = (int) Math.ceil(rr) + 1;
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                double d = Math.hypot(dx, dz) + (s.n(dx, g + y, dz, 3.2, 3) - 0.5);
                if (d > rr || !s.within(dx, dz)) continue;
                boolean hollow = d < rr - 1.5 && y > 0 && y < H - 4;
                if (hollow) s.air(dx, g + y, dz);
                else s.set(dx, g + y, dz, stem);
            }
        }
        // a stack of caps: broad shelves, each a shallow dome with glowing underside
        int[] heights = {(int) (H * 0.35), (int) (H * 0.62), H - 2};
        double[] radii = {Math.min(s.rad - 2, 13 + s.size * 2), Math.min(s.rad - 3, 10 + s.size), 7};
        for (int c = 0; c < 3; c++) {
            double cr = radii[c];
            int cy = g + heights[c];
            for (int dx = -(int) cr; dx <= cr; dx++) for (int dz = -(int) cr; dz <= cr; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > cr || !s.within(dx, dz)) continue;
                int dome = (int) Math.round(Math.sqrt(Math.max(0, 1 - (d / cr) * (d / cr))) * (cr * 0.45));
                for (int k = 0; k <= dome; k++) {
                    boolean under = k == 0;
                    s.set(dx, cy + k, dz, under && s.chance(dx * 7 + dz + c, 0.3) ? Blocks.SHROOMLIGHT.defaultBlockState() : cap.defaultBlockState());
                }
                if (dome > 1 && s.chance(dx * 13 + dz * 3 + c, 0.04)) s.set(dx, cy + dome + 1, dz, Blocks.RED_MUSHROOM);
            }
        }
        // a door at the foot, a spiral up the outside, the cache under the top cap
        for (int dy = 1; dy <= 3; dy++) for (int dz = -(int) r0; dz <= -(int) r0 + 3; dz++) s.air(0, g + dy, dz);
        spiral(s, 0, 0, r0 + 0.5, g + 1, g + heights[1] - 1, 0.0, 1);
        cache(s, 0, g + H - 5, 0);
        for (int i = 0; i < 10; i++) {
            int x = s.vary(100 + i, -s.rad + 3, s.rad - 3), z = s.vary(120 + i, -s.rad + 3, s.rad - 3);
            if (s.dry(x, z) && s.free(x, s.top(x, z), z)) s.set(x, s.top(x, z), z, s.chance(i, 0.5) ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM);
        }
        return true;
    }

    // ------------------------------------------------------------------ skeleton

    static boolean ribcage(Stage s) {
        String style = s.kind.style();
        int g = s.top(0, 0) - 1;
        int len = 38;
        int half = len / 2;
        Block bone = Blocks.BONE_BLOCK;
        // vertebrae: a spine along x, sinking and rising
        for (int x = -half; x <= half; x++) {
            int sink = (int) Math.round(Math.sin(x * 0.18) * 1.5);
            int gy = s.top(x, 0) - 1;
            int y = gy + 1 + sink;
            s.set(x, y, 0, bone);
            s.set(x, y + 1, 0, x % 2 == 0 ? Blocks.BONE_BLOCK.defaultBlockState() : Blocks.CALCITE.defaultBlockState());
            if (x % 4 == 0) { s.set(x, y + 2, 0, bone); s.set(x, y + 3, 0, bone); }
        }
        // ribs: tall arches rising out of the ground on both sides, shorter towards the ends
        for (int x = -half + 5; x <= half - 6; x += 3) {
            double env = Math.sin((x + half) * Math.PI / len);
            int rh = (int) Math.round(10 + env * (style.equals("leviathan") ? 15 : 13));
            double span = rh * 0.75;
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 0; k <= rh * 2; k++) {
                    double t = (double) k / (rh * 2);
                    double ang = t * Math.PI * 0.55;
                    int zz = side * (int) Math.round(span * Math.sin(ang) + 1);
                    int yy = g + (int) Math.round(rh * (1 - Math.cos(ang)) * 0.0 + rh * Math.sin(ang * 1.1) * 0.95) - 2;
                    if (s.chance(x * 5 + k + side, 0.04)) continue;
                    if (!s.within(x, zz)) continue;
                    s.set(x, yy, zz, bone);
                    if (k > 2 && k < rh * 2 - 3 && (style.equals("giant") || k % 3 == 0)) s.set(x + 1, yy, zz, bone);
                }
            }
        }
        // the skull
        int sx = -half - 1;
        int sg = s.top(sx, 0) - 1;
        int sl = 9, sw = 5, sh = style.equals("dragon") ? 6 : 5;
        for (int dx = 0; dx < sl; dx++) for (int dz = -sw; dz <= sw; dz++) for (int dy = 0; dy < sh; dy++) {
            double taper = 1.0 - dx * 0.075;
            if (Math.abs(dz) > sw * taper) continue;
            if (dy > sh - 2 && dx < 2) continue;
            boolean eye = dx >= 3 && dx <= 4 && dy >= 3 && dy <= 4 && Math.abs(dz) >= 3 && Math.abs(dz) <= 4;
            boolean nose = dx >= sl - 2 && dy >= 2 && dy <= 3 && Math.abs(dz) <= 1;
            boolean mouth = dy == 1 && dx >= 3 && Math.abs(dz) <= sw * taper - 1;
            if (eye || nose || mouth) continue;
            s.set(sx - dx, sg + 1 + dy, dz, bone);
        }
        for (int dx = 2; dx < sl - 1; dx += 2) for (int dz = -3; dz <= 3; dz += 3) s.set(sx - dx, sg + 1, dz, Blocks.CALCITE);
        if (style.equals("dragon")) {
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 0; k < 7; k++) s.set(sx - 1 - k / 2, sg + sh + k, side * (sw + k / 3), bone);
                for (int k = 0; k < 4; k++) s.set(sx - 7 + k, sg + sh + 1, side * (1 + k % 2), bone);
            }
            // a wing bone, standing
            for (int k = 0; k < 22; k++) s.set(-3 + k / 3, s.top(-3 + k / 3, 12) - 1 + k, 12 + k / 5, bone);
        } else if (style.equals("giant")) {
            // a great club, dropped
            for (int k = 0; k < 14; k++) {
                int r = k > 9 ? 2 : 1;
                for (int dz = -r; dz <= r; dz++) for (int dy = 0; dy <= r; dy++) s.set(half - 14 + k, s.top(half - 14 + k, 14 + dz) + dy, 14 + dz, s.wood.log());
            }
        } else {
            // fins
            for (int k = 0; k < 14; k++) s.set(4 + k, g + 2 + (int) (Math.sin(k * 0.22) * 6), -11 - k / 3, bone);
        }
        // the cache among the ribs
        cache(s, 0, s.top(0, 0), 5);
        s.rubbleRing(0, 0, 18, 18);
        s.b.scatter(18, 20, Blocks.BONE_BLOCK.defaultBlockState(), false);
        return true;
    }

    // ------------------------------------------------------------------ bridge arch

    static boolean bridgeArch(Stage s) {
        double half = 13 + s.size * 3;
        double h = 16 + s.size * 5;
        double thick = 3.5 + s.size;
        double depth = 8 + s.size * 2;
        int g = s.top(0, 0) - 1;
        Formations.arch(s, 0, 0, half, h, thick, depth, 0.5, 5);
        // the deck: a path across the crown, a ramp of steps up the near leg
        int ex = (int) -(half + thick) + 1;
        int topY = (int) Math.round(h + thick * 0.6);
        for (int x = -(int) (half * 0.55); x <= half * 0.55; x++) {
            double u = 1 - (x / (half * 0.6)) * (x / (half * 0.6));
            int y = (int) Math.round((h) * Math.sqrt(Math.max(0, u)) + thick * 0.7);
            for (int z = -1; z <= 1; z++) {
                if (s.solidAt(x, y, z)) s.set(x, y, z, s.b.pal.path().defaultBlockState());
                s.air(x, y + 1, z);
                s.air(x, y + 2, z);
            }
        }
        for (int k = 0; k < (int) (half + thick); k++) {
            int x = ex + (int) (k * 0.9);
            int y = (int) (k * (h / (half + thick)) * 0.9);
            for (int z = -1; z <= 1; z++) {
                s.rock(x, g + y, z);
                s.air(x, g + y + 1, z);
                s.air(x, g + y + 2, z);
            }
        }
        cache(s, 0, topY + 1, 0);
        s.dress(2, topY + 2, 0);
        return true;
    }

    // ------------------------------------------------------------------ needles

    static boolean spireStack(Stage s) {
        int g = s.top(0, 0) - 1;
        int H = 46 + (int) (s.size * 14);
        Formations.shaft(s, 0, 0, g, H, 6.2 + s.size, 1.1, (s.r.nextDouble() - 0.5) * 6, (s.r.nextDouble() - 0.5) * 6, 0.9, 1);
        int sat = 4 + s.vary(1, 0, 2);
        for (int i = 0; i < sat; i++) {
            double a = i * Math.PI * 2 / sat + s.r.nextDouble() * 0.6;
            double d = 9 + s.r.nextDouble() * 5;
            int cx = (int) Math.round(Math.cos(a) * d), cz = (int) Math.round(Math.sin(a) * d);
            if (!s.within(cx, cz)) continue;
            int gy = s.top(cx, cz) - 1;
            int h = 14 + s.r.nextInt(24);
            Formations.shaft(s, cx, cz, gy, h, 3.0 + s.r.nextDouble() * 1.6, 0.6, -Math.cos(a) * 2, -Math.sin(a) * 2, 0.7, 5 + i);
        }
        // a path up: a stair spiral hugging the main needle, a platform with the cache at its top third
        int platY = g + (int) (H * 0.62);
        spiral(s, 0, 0, 5.4 + s.size * 0.7, g + 1, platY, s.r.nextDouble() * 6, s.chance(2, 0.5) ? 1 : -1);
        cache(s, 0, platY + 1, 0);
        s.rubbleRing(0, 0, 18, 24);
        return true;
    }

    static boolean spireTwist(Stage s) {
        int g = s.top(0, 0) - 1;
        int H = 44 + (int) (s.size * 14);
        int strands = 2 + s.vary(1, 0, 1);
        for (int k = 0; k < strands; k++) {
            double phase = k * Math.PI * 2 / strands;
            for (int y = 0; y <= H; y++) {
                double t = (double) y / H;
                double rr = 3.2 * (1 - t) + 0.6;
                double arm = 4.0 * (1 - t * 0.7);
                double a = phase + t * Math.PI * 3.2;
                double cx = Math.cos(a) * arm, cz = Math.sin(a) * arm;
                int R = (int) Math.ceil(rr);
                for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                    int x = (int) Math.round(cx) + dx, z = (int) Math.round(cz) + dz;
                    if (!s.within(x, z)) continue;
                    if (Math.hypot(x - cx, z - cz) <= rr + (s.n(x, g + y, z, 3.5, 4) - 0.5)) s.rock(x, g + y, z);
                }
            }
        }
        // a pedestal and the broad base
        for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 9 || !s.within(dx, dz)) continue;
            int up = (int) Math.round((9 - d) * 0.5);
            int gy = s.top(dx, dz) - 1;
            for (int y = gy; y <= g + up; y++) s.rock(dx, y, dz);
        }
        spiral(s, 0, 0, 8.6, g + 1, g + (int) (H * 0.4), 0.0, 1);
        cache(s, 0, g + (int) (H * 0.4) + 1, 6);
        s.rubbleRing(0, 0, 16, 18);
        return true;
    }

    static boolean antlerSpires(Stage s) {
        int g = s.top(0, 0) - 1;
        int H = 46 + (int) (s.size * 10);
        for (int side = -1; side <= 1; side += 2) {
            double cx = side * 5.0;
            double lean = side * (6 + s.r.nextDouble() * 4);
            boolean broken = side == 1 && s.chance(3, 0.7);
            int top = broken ? (int) (H * 0.55) : H;
            Formations.shaft(s, cx, 0, g, top, 4.2, broken ? 3.0 : 1.4, lean * top / H, (s.r.nextDouble() - 0.5) * 3, 0.8, 2 + side);
            // tines
            int tines = 4 + s.r.nextInt(3);
            for (int i = 0; i < tines; i++) {
                double t = 0.35 + 0.6 * i / tines;
                int y = g + (int) (top * t);
                double bx = cx + lean * (y - g) / H;
                double dir = side * (0.5 + s.r.nextDouble() * 0.9) * (s.chance(i, 0.5) ? 1 : -0.6);
                int len = 6 + s.r.nextInt(8);
                for (int k = 1; k <= len; k++) {
                    int x = (int) Math.round(bx + dir * k), yy = y + (int) (k * 0.9);
                    int z = (int) Math.round((s.r.nextDouble() - 0.5) * 0.4 + k * 0.1 * (i % 2 == 0 ? 1 : -1));
                    if (!s.within(x, z)) break;
                    s.rock(x, yy, z);
                    if (k < len * 0.4) s.rock(x, yy - 1, z);
                }
            }
        }
        // the pilgrims' ladder: a stair of rock steps up the inner face of the unbroken one
        for (int k = 0; k < 30; k++) {
            int x = -2 - (int) (k * 0.1), y = g + 1 + k;
            if (k > 20) break;
            s.rock(-1 - (k % 3) * 0 , y, k % 2 == 0 ? -4 : -3);
            s.air(-1, y + 1, k % 2 == 0 ? -4 : -3);
        }
        cache(s, -6, s.top(-6, -8), -8);
        s.rubbleRing(0, 0, 18, 22);
        return true;
    }

    // ------------------------------------------------------------------ crater lake

    static boolean craterLake(Stage s) {
        int g = s.top(0, 0) - 1;
        double R = 15 + s.size * 3;
        int depth = 6 + (int) (s.size * 3);
        int max = (int) R + 6;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx, dz) + (s.n(dx, 0, dz, 6, 3) - 0.5) * 3.5;
                int gy = s.top(dx, dz) - 1;
                if (d <= R - 1.5) {
                    double t = d / R;
                    int dd = (int) Math.round(depth * (1 - t * t * 0.9));
                    for (int y = gy + 5; y > g - dd; y--) {
                        if (y <= g) s.set(dx, y, dz, Blocks.WATER);
                        else s.air(dx, y, dz);
                    }
                    s.set(dx, g - dd, dz, d < R * 0.5 ? Blocks.CLAY.defaultBlockState() : s.rk(dx, g - dd, dz));
                    s.set(dx, g - dd - 1, dz, s.rk(dx, g - dd - 1, dz));
                } else if (d <= R + 5) {
                    // the rim: a raised wall that never dips below the water, falling away outside
                    double u = (d - (R - 1.5)) / 6.5;
                    int up = (int) Math.round(7 * Math.sin(Math.min(1.0, u) * Math.PI * 0.85) + 1);
                    for (int y = Math.min(gy, g) - 1; y <= g + up; y++) s.rock(dx, y, dz);
                    if (up > 2 && s.chance(dx * 7 + dz, 0.1)) s.dress(dx, g + up + 1, dz);
                }
            }
        }
        // an islet with the cache, and a path up the outer rim
        s.blob(0, g, 0, 3, 2, 3, 0.3, 4);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) s.set(dx, g + 1, dz, s.rk(dx, g + 1, dz));
        cache(s, 0, g + 2, 0);
        double a = s.r.nextDouble() * Math.PI * 2;
        for (int k = 0; k <= 12; k++) {
            double d = R + 6 - k * 0.5;
            int x = (int) Math.round(Math.cos(a) * d), z = (int) Math.round(Math.sin(a) * d);
            if (!s.within(x, z)) continue;
            int y = s.top(x, z) - 1;
            s.set(x, y + 1, z, s.b.pal.path().defaultBlockState());
        }
        return true;
    }

    // ------------------------------------------------------------------ mesa

    static boolean mesaPath(Stage s) {
        int g = s.top(0, 0) - 1;
        double R = 15 + s.size * 3;
        int H = 22 + (int) (s.size * 8);
        int max = (int) R + 4;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx, dz) + (s.n(dx, 0, dz, 6, 3) - 0.5) * 3.5;
                int gy = s.top(dx, dz) - 1;
                if (d <= R) {
                    int top = g + H + (s.n(dx, 0, dz, 4, 5) > 0.7 ? 1 : 0);
                    for (int y = gy; y <= top; y++) s.rock(dx, y, dz);
                } else if (d <= R + 4) {
                    // a talus skirt of fallen rock
                    double u = (d - R) / 4;
                    int top = g + (int) Math.round(H * 0.38 * (1 - u));
                    for (int y = gy; y <= top; y++) s.set(dx, y, dz, s.chance(dx * 3 + dz + y, 0.3) ? s.rubble(dx, y, dz) : s.rk(dx, y, dz));
                }
            }
        }
        // the switchback path, climbing the flank in two runs; the top gets a cairn and the cache
        double a0 = s.r.nextDouble() * Math.PI * 2;
        int steps = H + 8;
        for (int k = 0; k < steps; k++) {
            double ang = a0 + (k < steps / 2 ? k * 0.045 : (steps / 2) * 0.045 - (k - steps / 2) * 0.045);
            double rr = R + 1.5 - 0.3 * (k % 2);
            int x = (int) Math.round(Math.cos(ang) * rr), z = (int) Math.round(Math.sin(ang) * rr);
            int y = g + 1 + (int) (k * (double) H / steps);
            if (!s.within(x, z)) continue;
            s.rock(x, y - 1, z);
            for (int up = 0; up <= 2; up++) s.air(x, y + up, z);
            int x2 = (int) Math.round(Math.cos(ang) * (rr - 1)), z2 = (int) Math.round(Math.sin(ang) * (rr - 1));
            s.rock(x2, y - 1, z2);
            for (int up = 0; up <= 2; up++) s.air(x2, y + up, z2);
        }
        int top = g + H;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > 5) continue;
            s.set(dx, top, dz, s.lush ? Blocks.GRASS_BLOCK.defaultBlockState() : s.rk(dx, top, dz));
            s.dress(dx, top + 1, dz);
        }
        cache(s, 0, top + 1, 0);
        s.set(2, top + 1, 2, s.rock.rubble().state(3));
        s.set(2, top + 2, 2, s.rock.rubble().state(5));
        s.rubbleRing(0, 0, 20, 16);
        return true;
    }

    // ------------------------------------------------------------------ crystals, ice and glass

    static boolean crystalSpire(Stage s) {
        int g = s.top(0, 0) - 1;
        int H = 48 + (int) (s.size * 14);
        Block body = s.chance(1, 0.5) ? Blocks.AMETHYST_BLOCK : Blocks.QUARTZ_BLOCK;
        Block shell = Blocks.WHITE_STAINED_GLASS;
        // the broad base of calcite
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > 10 || !s.within(dx, dz)) continue;
            int up = (int) Math.round((10 - d) * 0.45);
            int gy = s.top(dx, dz) - 1;
            for (int y = gy; y <= g + up; y++) s.set(dx, y, dz, s.chance(dx * 3 + dz + y, 0.15) ? Blocks.AMETHYST_BLOCK.defaultBlockState() : Blocks.CALCITE.defaultBlockState());
        }
        // the main crystal: a six-sided shaft, then a point
        for (int y = 0; y <= H; y++) {
            double t = (double) y / H;
            double rr = t < 0.82 ? 4.5 * (1 - 0.18 * t) : 4.5 * (1 - t) / 0.18 * 0.82;
            int R = (int) Math.ceil(rr);
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                double d = Math.max(Math.abs(dx) * 0.87 + Math.abs(dz) * 0.5, Math.abs(dz));
                if (d > rr) continue;
                boolean edge = d > rr - 1.1;
                BlockState b = edge ? (s.chance(dx + dz * 5 + y, 0.25) ? Blocks.SEA_LANTERN.defaultBlockState() : shell.defaultBlockState())
                        : (d < 1.5 ? Blocks.AIR.defaultBlockState() : body.defaultBlockState());
                if (y < 3 && d < 2.5) b = Blocks.AIR.defaultBlockState();
                s.set(dx, g + y, dz, b);
            }
        }
        // satellite crystals leaning outward
        int n = 5 + s.vary(2, 0, 2);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + s.r.nextDouble() * 0.4;
            int h = 14 + s.r.nextInt(18);
            double lean = 0.45 + s.r.nextDouble() * 0.35;
            for (int k = 0; k <= h; k++) {
                double rr = 2.1 * (1 - (double) k / h) + 0.3;
                int cx = (int) Math.round(Math.cos(a) * (7 + k * lean)), cz = (int) Math.round(Math.sin(a) * (7 + k * lean));
                int R = (int) Math.ceil(rr);
                for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                    if (Math.hypot(dx, dz) > rr || !s.within(cx + dx, cz + dz)) continue;
                    s.set(cx + dx, g + 1 + k, cz + dz, s.chance(i + k, 0.2) ? Blocks.AMETHYST_BLOCK.defaultBlockState() : shell.defaultBlockState());
                }
            }
        }
        cache(s, 0, g + 1, -2);
        return true;
    }

    static boolean glacierTower(Stage s) {
        int g = s.top(0, 0) - 1;
        int H = 42 + (int) (s.size * 12);
        Block[] ice = {Blocks.BLUE_ICE, Blocks.PACKED_ICE, Blocks.PACKED_ICE, Blocks.SNOW_BLOCK};
        for (int y = 0; y <= H; y++) {
            double t = (double) y / H;
            double rr = 9 * (1 - 0.65 * t) + 2 * Math.sin(y * 0.35);
            double ox = Math.sin(t * 4) * 2.5;
            int R = (int) Math.ceil(rr + 3);
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                int x = (int) Math.round(ox) + dx;
                if (!s.within(x, dz)) continue;
                double d = Math.hypot(x - ox, dz) + (s.n(x, g + y, dz, 3.2, 3) - 0.5) * 3.4;
                if (d > rr) continue;
                s.set(x, g + y, dz, ice[Math.floorMod(x * 5 + y * 3 + dz, ice.length)].defaultBlockState());
            }
        }
        // a tunnel through the foot and spikes of ice around it
        for (int dx = -12; dx <= 12; dx++) for (int dy = 1; dy <= 4; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (s.within(dx, dz)) s.air(dx, g + dy, dz);
        }
        for (int i = 0; i < 9; i++) {
            double a = i * Math.PI * 2 / 9 + s.r.nextDouble() * 0.3;
            int cx = (int) Math.round(Math.cos(a) * 14), cz = (int) Math.round(Math.sin(a) * 14);
            if (!s.within(cx, cz)) continue;
            Formations.shaft(s, cx, cz, s.top(cx, cz) - 1, 8 + s.r.nextInt(10), 2.4, 0.2, -Math.cos(a) * 3, -Math.sin(a) * 3, 0.4, 3 + i);
        }
        for (int i = 0; i < 18; i++) {
            int x = s.vary(80 + i, -9, 9), z = s.vary(100 + i, -9, 9);
            int y = g + 5 + s.vary(120 + i, 0, 12);
            if (s.solidAt(x, y, z)) {
                for (int k = 1; k <= 1 + i % 3; k++) if (!s.solidAt(x, y - k, z)) s.set(x, y - k, z, Blocks.ICE);
            }
        }
        cache(s, 0, g + 1, 0);
        s.rubbleRing(0, 0, 18, 20);
        return true;
    }

    static boolean obsidianSpire(Stage s) {
        int g = s.top(0, 0) - 1;
        int H = 44 + (int) (s.size * 12);
        for (int y = 0; y <= H; y++) {
            double t = (double) y / H;
            double rr = 7.5 * Math.pow(1 - t, 0.7) + 0.8;
            double ox = Math.sin(y * 0.11) * 2.5, oz = Math.cos(y * 0.09) * 2.5;
            int R = (int) Math.ceil(rr + 2);
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                int x = (int) Math.round(ox) + dx, z = (int) Math.round(oz) + dz;
                if (!s.within(x, z)) continue;
                double d = Math.hypot(x - ox, z - oz) + (s.n(x, g + y, z, 2.8, 3) - 0.5) * 3.0;
                if (d > rr) continue;
                BlockState b = s.rk(x, y, z);
                // veins of fire in the crust
                if (s.n(x, y, z, 2.5, 9) > 0.8) b = Blocks.MAGMA_BLOCK.defaultBlockState();
                else if (s.n(x, y, z, 3.0, 11) > 0.93) b = Blocks.CRYING_OBSIDIAN.defaultBlockState();
                s.set(x, g + y, z, b);
            }
        }
        // a ring of broken fangs
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + 0.2;
            int cx = (int) Math.round(Math.cos(a) * 15), cz = (int) Math.round(Math.sin(a) * 15);
            if (!s.within(cx, cz)) continue;
            Formations.shaft(s, cx, cz, s.top(cx, cz) - 1, 6 + s.r.nextInt(14), 2.2, 0.3, -Math.cos(a) * 2, -Math.sin(a) * 2, 0.5, 3 + i);
        }
        spiral(s, 0, 0, 8.0, g + 1, g + (int) (H * 0.3), 0.0, 1);
        cache(s, 0, g + (int) (H * 0.3) + 1, 5);
        return true;
    }
}
