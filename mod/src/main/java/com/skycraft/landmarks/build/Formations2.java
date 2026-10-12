package com.skycraft.landmarks.build;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/** More tier L formations: vents, craters, fallen colossi, aqueducts, fallen trunks, rune circles, spikes and lakebeds. */
final class Formations2 {
    private Formations2() {}

    // ------------------------------------------------------------------ vents and pits

    static boolean vent(Stage s) {
        String style = s.kind.style();
        int g = s.top(0, 0) - 1;
        double R = 7 + s.size * 4;
        boolean tar = style.equals("tar");
        int max = (int) R + 2;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx, dz) + (s.n(dx, 0, dz, 4, 3) - 0.5) * 3;
                if (d > R) continue;
                int gy = Math.min(g, s.top(dx, dz) - 1);
                if (tar) {
                    if (d < R * 0.65) {
                        for (int y = gy + 2; y > gy - 2; y--) s.air(dx, y, dz);
                        s.set(dx, gy - 2, dz, Blocks.COAL_BLOCK);
                        s.set(dx, gy - 1, dz, s.chance(dx * 3 + dz, 0.2) ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.MUD.defaultBlockState());
                        s.set(dx, gy, dz, Blocks.AIR);
                    } else {
                        s.set(dx, gy, dz, s.chance(dx * 5 + dz, 0.3) ? Blocks.COAL_BLOCK.defaultBlockState() : Blocks.PACKED_MUD.defaultBlockState());
                    }
                } else {
                    // sulfur crust and cracks
                    double t = 1 - d / R;
                    BlockState crust = t > 0.55 ? Blocks.YELLOW_TERRACOTTA.defaultBlockState()
                            : (s.chance(dx * 7 + dz, 0.4) ? Blocks.ORANGE_TERRACOTTA.defaultBlockState() : s.rk(dx, gy, dz));
                    s.set(dx, gy, dz, crust);
                    if (t > 0.3 && t < 0.5 && s.chance(dx * 11 + dz, 0.1)) s.set(dx, gy, dz, Blocks.MAGMA_BLOCK);
                    int up = (int) Math.round(t * 3 * s.n(dx, 0, dz, 3, 8));
                    for (int k = 1; k <= up; k++) s.set(dx, gy + k, dz, s.chance(dx + k * 5 + dz, 0.5) ? Blocks.YELLOW_TERRACOTTA.defaultBlockState() : Blocks.SMOOTH_BASALT.defaultBlockState());
                }
            }
        }
        // the plume: a lit signal fire sunk in the middle
        if (!tar) {
            s.set(0, g, 0, Blocks.MAGMA_BLOCK);
            s.set(0, g + 1, 0, Blocks.HAY_BLOCK);
            s.set(0, g + 2, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, true));
            for (int i = 0; i < 3; i++) {
                int x = s.vary(10 + i, -4, 4), z = s.vary(20 + i, -4, 4);
                if (s.dry(x, z)) {
                    int y = s.top(x, z);
                    s.set(x, y - 1, z, Blocks.HAY_BLOCK);
                    s.set(x, y, z, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, true));
                }
            }
        } else {
            // bones sticking out of the tar, and a dead tree at the edge
            for (int i = 0; i < 6; i++) {
                int x = s.vary(30 + i, -4, 4), z = s.vary(40 + i, -4, 4);
                for (int k = 0; k < 2 + i % 3; k++) s.set(x, g + k, z, Blocks.BONE_BLOCK);
            }
            for (int k = 0; k < 7; k++) s.set((int) R - 1, s.top((int) R - 1, 1) + k, 1, Blocks.STRIPPED_DARK_OAK_LOG);
        }
        return true;
    }

    // ------------------------------------------------------------------ impact crater

    static boolean crater(Stage s) {
        int g = s.top(0, 0) - 1;
        double R = 9 + s.size * 5;
        int depth = 3 + (int) (s.size * 3);
        int max = (int) R + 4;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx, dz) + (s.n(dx, 0, dz, 4.5, 3) - 0.5) * 3;
                int gy = s.top(dx, dz) - 1;
                if (d <= R) {
                    double t = d / R;
                    int dd = (int) Math.round(depth * (1 - t * t));
                    for (int y = gy + 3; y > gy - dd; y--) s.air(dx, y, dz);
                    int floor = gy - dd;
                    BlockState fl = d < R * 0.3 ? Blocks.GLASS.defaultBlockState()
                            : (s.chance(dx * 5 + dz, 0.5) ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
                    if (d < R * 0.3 && s.chance(dx * 3 + dz, 0.4)) fl = Blocks.TINTED_GLASS.defaultBlockState();
                    s.set(dx, floor, dz, fl);
                } else if (d <= R + 3) {
                    // the thrown-up rim
                    int up = (int) Math.round(2.5 * (1 - (d - R) / 3));
                    for (int k = 1; k <= up; k++) s.set(dx, gy + k, dz, s.chance(dx * 7 + dz + k, 0.3) ? Blocks.BLACKSTONE.defaultBlockState() : s.rk(dx, gy + k, dz));
                }
            }
        }
        // the cause, half buried
        s.blob(0, g - depth + 2, 0, 2.6, 2.2, 2.6, 0.3, 4);
        s.set(0, g - depth + 3, 0, Blocks.RAW_IRON_BLOCK);
        s.set(1, g - depth + 2, 0, Blocks.GILDED_BLACKSTONE);
        return true;
    }

    // ------------------------------------------------------------------ fallen colossus

    static boolean statue(Stage s) {
        String style = s.kind.style();
        int g = s.top(0, 0) - 1;
        Block stone = s.cutStone ? s.b.pal.brick() : s.rock.main().at(5);
        BlockState mat = stone.defaultBlockState();
        if (style.equals("hand")) {
            // a palm up, the fingers curling
            for (int dx = -3; dx <= 3; dx++) for (int dz = -2; dz <= 3; dz++) {
                s.set(dx, s.top(dx, dz) - 1 + 1, dz, mat);
                if (Math.abs(dx) <= 2 && dz >= 0 && dz <= 2) s.set(dx, s.top(dx, dz) + 1, dz, s.rk(dx, g + 2, dz));
            }
            for (int f = 0; f < 5; f++) {
                int x = -3 + f * 1;
                int len = 4 + (f == 2 ? 2 : f == 0 ? -1 : 0);
                for (int k = 0; k < len; k++) {
                    int z = 4 + k;
                    int y = g + 2 + (k > len / 2 ? (k - len / 2) : 0);
                    s.set(x, y, z, mat);
                    if (f % 2 == 0) s.set(x, y + 1, z, s.rk(x, y + 1, z));
                }
            }
            for (int k = 0; k < 4; k++) s.set(4 + k, g + 2, -1 + k / 2, mat);
            s.rubbleRing(0, 3, 8, 14);
        } else if (style.equals("torso")) {
            // a broken body: chest, one arm, jagged top
            for (int dx = -5; dx <= 5; dx++) for (int dz = -3; dz <= 3; dz++) {
                double e = Math.abs(dx) / 5.0 + Math.abs(dz) / 3.5;
                if (e > 1.2) continue;
                int h = 3 + (int) (2 * (1 - e)) + (int) (s.n(dx, 0, dz, 3, 3) * 2);
                for (int y = g; y <= g + h; y++) s.set(dx, y, dz, s.chance(dx * 3 + y + dz, 0.1) ? s.b.pal.mossy().defaultBlockState() : mat);
            }
            for (int k = 0; k < 10; k++) s.set(6 + k, g + 1 + (k > 6 ? 1 : 0), -2 + k / 4, mat);
            s.blob(-9, g + 2, 3, 3, 2.4, 2.4, 0.3, 5);
            s.rubbleRing(0, 0, 11, 16);
        } else {
            // a head on its side, half buried: crown, brow, nose, empty eyes
            double rx = 4 + s.size, ry = 4.5 + s.size, rz = 4 + s.size * 0.8;
            s.blob(0, g + ry * 0.55, 0, rx, ry, rz, 0.15, 1);
            for (int dx = -2; dx <= 2; dx++) s.set(dx, g + 4, -(int) rz - 1, mat);
            for (int dy = 2; dy <= 4; dy++) s.set(0, g + dy, -(int) rz - 1, mat);
            s.set(0, g + 3, -(int) rz - 2, mat);
            s.air(-2, g + 5, -(int) rz);
            s.air(2, g + 5, -(int) rz);
            s.air(-2, g + 5, -(int) rz + 1);
            s.air(2, g + 5, -(int) rz + 1);
            for (int dx = -4; dx <= 4; dx++) s.set(dx, g + (int) (ry * 1.5), 0, s.b.pal.stone().defaultBlockState());
            s.rubbleRing(0, 0, 10, 12);
            if (s.lush) s.vines(0, g + 4, (int) rz, 4);
        }
        return true;
    }

    // ------------------------------------------------------------------ aqueduct and bridge stub

    static boolean aqueduct(Stage s) {
        boolean bridge = s.kind.style().equals("bridge");
        int bays = bridge ? 2 : 3 + s.vary(1, 0, 1);
        double span = 4.2;
        int h = bridge ? 6 : 7;
        Block wall = s.b.pal.brick();
        int total = (int) (bays * (span * 2 + 2));
        int x0 = -total / 2;
        for (int i = 0; i < bays; i++) {
            int cx = (int) Math.round(x0 + i * (span * 2 + 2) + span + 1);
            // piers
            for (int pz = -1; pz <= 1; pz++) {
                for (int side = -1; side <= 1; side += 2) {
                    int px = cx + side * (int) (span + 1);
                    if (!s.within(px, pz)) continue;
                    int gy = s.top(px, pz) - 1;
                    int top = g(s, i, side, h);
                    for (int y = gy; y <= top; y++) s.set(px, y, pz, s.chance(px + y * 5 + pz, 0.25) ? s.b.pal.mossy().defaultBlockState() : wall.defaultBlockState());
                }
            }
            // the arch between
            for (int dx = -(int) span; dx <= span; dx++) {
                double ya = Math.sqrt(Math.max(0, 1 - (dx / span) * (dx / span))) * (h - 2.5);
                for (int pz = -1; pz <= 1; pz++) {
                    int px = cx + dx;
                    if (!s.within(px, pz)) continue;
                    int base = s.top(px, pz) - 1;
                    int top = base + h;
                    for (int y = base + (int) Math.round(ya) + 1; y <= top; y++) {
                        if (s.chance(px * 3 + y + i * 7, bridge ? 0.12 : 0.05)) continue;
                        s.set(px, y, pz, wall);
                    }
                }
            }
            // the channel on top, or a deck
            for (int dx = -(int) span - 1; dx <= span + 1; dx++) {
                int px = cx + dx;
                if (!s.within(px, 0)) continue;
                int top = s.top(px, 0) - 1 + h;
                if (s.chance(px * 5 + i, 0.15)) continue;
                s.set(px, top + 1, -1, wall);
                s.set(px, top + 1, 1, wall);
                s.set(px, top + 1, 0, bridge ? s.b.pal.path().defaultBlockState() : Blocks.WATER.defaultBlockState());
                if (!bridge) s.set(px, top, 0, Blocks.CLAY);
            }
        }
        // the broken end and the fallen stones
        s.rubbleRing(x0 + total, 0, 6, 14);
        s.rubbleRing(0, 0, 10, 10);
        return true;
    }

    private static int g(Stage s, int i, int side, int h) {
        return s.top(0, 0) - 1 + h - (s.chance(i * 5 + side + 9, 0.25) ? 1 + s.r.nextInt(3) : 0);
    }

    // ------------------------------------------------------------------ fallen trunk

    static boolean fallenLog(Stage s) {
        int len = 18 + s.vary(1, 0, 8);
        double r = 2.6 + s.size * 0.8;
        int half = len / 2;
        Block log = s.wood.log();
        Block stripped = Blocks.STRIPPED_OAK_LOG;
        int g = s.top(0, 0) - 1;
        for (int x = -half; x <= half; x++) {
            double wob = (s.n(x, 0, 0, 6, 2) - 0.5) * 1.2;
            int gy = s.top(x, 0) - 1;
            double rr = r * (1.0 - 0.18 * (x + half) / len);
            for (int dz = -(int) Math.ceil(rr); dz <= rr; dz++) {
                for (int dy = 0; dy <= (int) Math.ceil(rr * 2); dy++) {
                    double zz = dz, yy = dy - rr;
                    double d = Math.hypot(zz - wob * 0.3, yy);
                    if (d > rr) continue;
                    int y = gy + dy;
                    boolean hollow = d < rr - 1.4 && x > -half + 1;
                    if (hollow) {
                        s.air(x, y, dz);
                    } else {
                        s.set(x, y, dz, d > rr - 1.4 ? log.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)
                                : stripped.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
                    }
                }
            }
            if (s.chance(x * 5, 0.25)) s.dress(x, gy + (int) Math.ceil(rr * 2) + 1, 0);
        }
        // the root plate at one end: a standing disc of earth and roots
        int rx = -half - 1;
        for (int dz = -5; dz <= 5; dz++) for (int dy = 0; dy <= 9; dy++) {
            double d = Math.hypot(dz, dy - 4.5);
            if (d > 5 + (s.n(0, dy, dz, 3, 4) - 0.5) * 3) continue;
            boolean root = s.chance(dz * 7 + dy, 0.45);
            s.set(rx, s.top(rx, dz) - 1 + dy, dz, root ? (s.wet ? Blocks.MANGROVE_ROOTS.defaultBlockState() : s.wood.log().defaultBlockState())
                    : Blocks.ROOTED_DIRT.defaultBlockState());
        }
        for (int i = 0; i < 6; i++) {
            int x = s.vary(40 + i, -half, half), z = s.vary(60 + i, 3, 7) * (i % 2 == 0 ? 1 : -1);
            if (s.dry(x, z) && s.free(x, s.top(x, z), z)) s.set(x, s.top(x, z), z, s.chance(i, 0.5) ? Blocks.BROWN_MUSHROOM : Blocks.FERN);
        }
        return true;
    }

    // ------------------------------------------------------------------ rune circle

    static boolean runeCircle(Stage s) {
        int n = 7 + s.vary(1, 0, 3);
        double R = 9 + s.size * 4;
        int g = s.top(0, 0) - 1;
        Block carved = s.rock.main().at(0) == Blocks.SANDSTONE ? Blocks.CHISELED_SANDSTONE : Blocks.CHISELED_STONE_BRICKS;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + (s.n(i, 0, 0, 1.3, 2) - 0.5) * 0.3;
            int x = (int) Math.round(Math.cos(a) * R), z = (int) Math.round(Math.sin(a) * R);
            if (!s.within(x, z)) continue;
            int gy = s.top(x, z) - 1;
            int h = 4 + s.r.nextInt(5);
            boolean fallen = s.chance(i + 20, 0.2);
            if (fallen) {
                Direction.Axis ax = s.r.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z;
                for (int k = 0; k < h; k++) {
                    int xx = x + (ax == Direction.Axis.X ? k : 0), zz = z + (ax == Direction.Axis.Z ? k : 0);
                    if (s.within(xx, zz)) { s.rock(xx, s.top(xx, zz), zz); s.rock(xx, s.top(xx, zz) + 0, zz); }
                }
                continue;
            }
            double lx = (s.r.nextDouble() - 0.5) * 2, lz = (s.r.nextDouble() - 0.5) * 2;
            Formations.shaft(s, x, z, gy, h, 1.6, 1.1, lx, lz, 0.35, 3 + i);
            int gl = gy + 1 + h / 2;
            if (s.solidAt(x, gl, z)) s.set(x, gl, z, carved);
            if (s.chance(i + 5, 0.4)) s.dress(x, gy + h + 1, z);
        }
        // the slab and what rests on it
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) s.set(dx, g + 1, dz, s.rk(dx, g + 1, dz));
        s.set(0, g + 1, 0, carved);
        s.set(0, g + 2, 0, Blocks.AIR);
        if (s.chance(7, 0.5)) s.lying(0, g + 2, 0, new ItemStack(Items.AMETHYST_SHARD));
        s.rubbleRing(0, 0, (int) R + 3, 14);
        return true;
    }

    // ------------------------------------------------------------------ spikes (ice, obsidian)

    static boolean spikes(Stage s) {
        int n = 6 + s.vary(1, 0, 4);
        for (int i = 0; i < n; i++) {
            double a = s.r.nextDouble() * Math.PI * 2, d = Math.sqrt(s.r.nextDouble()) * (s.rad - 6);
            int cx = (int) Math.round(Math.cos(a) * d), cz = (int) Math.round(Math.sin(a) * d);
            if (!s.dry(cx, cz)) continue;
            int g = s.top(cx, cz) - 1;
            int h = 5 + s.r.nextInt(9) + (i == 0 ? 5 : 0);
            double lx = Math.cos(a) * (1 + s.r.nextDouble() * 3), lz = Math.sin(a) * (1 + s.r.nextDouble() * 3);
            Formations.shaft(s, cx, cz, g, h, 1.6 + s.r.nextDouble() * 1.4, 0.2, lx, lz, 0.3, 4 + i);
        }
        s.rubbleRing(0, 0, s.rad - 2, 14);
        return true;
    }

    // ------------------------------------------------------------------ dry lakebed

    static boolean dryLake(Stage s) {
        int g = s.top(0, 0) - 1;
        double R = 13 + s.size * 5;
        int max = (int) R + 2;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx * 1.0, dz * 0.8) + (s.n(dx, 0, dz, 6, 3) - 0.5) * 5;
                if (d > R) continue;
                int gy = Math.min(g, s.top(dx, dz) - 1);
                double t = d / R;
                int dd = t < 0.8 ? 1 : 0;
                for (int y = gy + 2; y > gy - dd; y--) s.air(dx, y, dz);
                boolean crack = ((dx * 7 + dz * 3) % 5 == 0 || s.n(dx, 0, dz, 2.2, 4) > 0.78) && t < 0.9;
                BlockState b;
                if (crack) b = s.chance(dx + dz * 9, 0.5) ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.BROWN_TERRACOTTA.defaultBlockState();
                else b = t < 0.4 ? Blocks.WHITE_TERRACOTTA.defaultBlockState()
                        : (s.chance(dx * 5 + dz, 0.5) ? Blocks.TERRACOTTA.defaultBlockState() : Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState());
                if (s.snowy) b = t < 0.5 ? Blocks.PACKED_ICE.defaultBlockState() : Blocks.SNOW_BLOCK.defaultBlockState();
                s.set(dx, gy - dd, dz, b);
                if (!crack && s.chance(dx * 13 + dz, 0.025)) s.set(dx, gy - dd + 1, dz, Blocks.DEAD_BUSH);
            }
        }
        // the ribs of a boat that was left behind
        for (int i = 0; i < 7; i++) {
            int x = -3 + i;
            int hw = (int) Math.round(2 * Math.sin((i + 0.6) / 7.6 * Math.PI));
            int y = s.top(x, 4);
            s.set(x, y, 4 - hw, s.wood.planks());
            s.set(x, y, 4 + hw, s.wood.planks());
            if (i % 2 == 0) { s.set(x, y + 1, 4 - hw, s.wood.fence()); s.set(x, y + 1, 4 + hw, s.wood.fence()); }
        }
        s.set(0, s.top(0, 4), 4, Blocks.SKELETON_SKULL);
        s.set(4, s.top(4, -3), -3, Blocks.BONE_BLOCK);
        return true;
    }

    // ------------------------------------------------------------------ drowned grove

    static boolean drownedGrove(Stage s) {
        int g = s.top(0, 0) - 1;
        double R = 11 + s.size * 4;
        int max = (int) R + 2;
        // pools: shallow, mud-rimmed
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx, dz);
                if (d > R) continue;
                double nz = s.n(dx, 0, dz, 5, 3);
                int gy = s.top(dx, dz) - 1;
                if (nz < 0.42 && d < R - 2) {
                    s.set(dx, gy - 1, dz, Blocks.CLAY);
                    s.set(dx, gy, dz, Blocks.WATER);
                    if (s.chance(dx * 3 + dz, 0.2)) s.set(dx, gy + 1, dz, Blocks.LILY_PAD);
                } else if (nz < 0.55) {
                    s.set(dx, gy, dz, Blocks.MUD);
                }
            }
        }
        // the old walls, sunk and mossy
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + 0.4;
            int x0 = (int) Math.round(Math.cos(a) * 6), z0 = (int) Math.round(Math.sin(a) * 6);
            boolean horiz = i % 2 == 0;
            for (int k = -2; k <= 2; k++) {
                int x = x0 + (horiz ? k : 0), z = z0 + (horiz ? 0 : k);
                if (!s.within(x, z) || s.get(x, s.top(x, z) - 1, z).is(Blocks.WATER)) continue;
                int h = 1 + s.r.nextInt(3);
                for (int y = 0; y < h; y++) s.set(x, s.top(x, z) + y, z, s.chance(x + y + z * 3, 0.5) ? s.b.pal.mossy().defaultBlockState() : s.b.pal.brick().defaultBlockState());
            }
        }
        // dead mangroves
        int trees = 4 + s.vary(2, 0, 3);
        for (int i = 0; i < trees; i++) {
            double a = s.r.nextDouble() * Math.PI * 2, d = 3 + s.r.nextDouble() * (R - 5);
            int x = (int) Math.round(Math.cos(a) * d), z = (int) Math.round(Math.sin(a) * d);
            if (!s.within(x, z)) continue;
            int gy = s.top(x, z);
            int h = 5 + s.r.nextInt(5);
            for (int y = 0; y < h; y++) s.set(x, gy + y, z, s.wood.log());
            for (int k = 0; k < 3; k++) {
                Direction d4 = Direction.Plane.HORIZONTAL.getRandomDirection(s.r);
                s.set(x + d4.getStepX(), gy + h - 2 - k, z + d4.getStepZ(), s.wood.log());
                s.set(x + d4.getStepX() * 2, gy + h - 3 - k, z + d4.getStepZ() * 2, s.wood.log());
            }
            if (s.chance(i, 0.5)) s.vines(x, gy + h - 2, z, 4);
            for (int k = 0; k < 4; k++) {
                Direction d4 = Direction.Plane.HORIZONTAL.getRandomDirection(s.r);
                s.set(x + d4.getStepX(), gy, z + d4.getStepZ(), Blocks.MANGROVE_ROOTS);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ old field walls

    static boolean oldWalls(Stage s) {
        int g = s.top(0, 0);
        int w = 8 + s.vary(1, 0, 4), d = 6 + s.vary(2, 0, 4);
        Block wall = s.cutStone ? s.b.pal.wall() : Blocks.COBBLESTONE_WALL;
        for (int x = -w; x <= w; x++) {
            for (int zz : new int[]{-d, d}) wallBlock(s, wall, x, zz, 0);
        }
        for (int z = -d; z <= d; z++) {
            for (int xx : new int[]{-w, w}) wallBlock(s, wall, xx, z, 1);
        }
        // a second, lower enclosure and a gap or two
        for (int x = -w + 3; x <= w - 3; x++) wallBlock(s, wall, x, d - 4, 2);
        // a hearthstone, a well, a crooked fruit tree
        s.set(-w + 2, s.top(-w + 2, -d + 2), -d + 2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        s.set(-w + 3, s.top(-w + 3, -d + 2), -d + 2, s.b.pal.stone());
        for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) {
            s.set(w - 3 + dx, s.top(w - 3 + dx, -d + 3 + dz), -d + 3 + dz, s.chance(dx * 2 + dz, 0.5) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
        }
        int tx = 1, tz = 0, tg = s.top(tx, tz);
        for (int y = 0; y < 4; y++) s.set(tx, tg + y, tz + (y > 2 ? 1 : 0), s.wood.log());
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > 3 || s.chance(dx * 5 + dz, 0.25)) continue;
            if (s.free(tx + dx, tg + 4, tz + dz)) s.set(tx + dx, tg + 4, tz + dz, s.wood.leaves().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        }
        s.rubbleRing(0, 0, 14, 8);
        return true;
    }

    private static void wallBlock(Stage s, Block wall, int x, int z, int salt) {
        if (!s.within(x, z) || !s.dry(x, z)) return;
        if (s.chance(x * 7 + z * 13 + salt, 0.22)) return;
        int y = s.top(x, z);
        int h = 1 + (s.chance(x * 3 + z + salt, 0.3) ? 1 : 0);
        for (int k = 0; k < h; k++) s.set(x, y + k, z, s.chance(x + k + z * 5, 0.3) ? Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState() : wall.defaultBlockState());
    }

    // ------------------------------------------------------------------ fins

    static boolean fins(Stage s) {
        int n = 3 + s.vary(1, 0, 2);
        double a = s.vary(2, 0, 7) * Math.PI / 4;
        double ux = Math.cos(a), uz = Math.sin(a);
        for (int i = 0; i < n; i++) {
            double off = (i - (n - 1) / 2.0) * 6 + (s.r.nextDouble() - 0.5) * 2;
            double cx = -uz * off, cz = ux * off;
            int len = 7 + s.r.nextInt(8);
            int h = 8 + s.r.nextInt(8);
            for (int k = -len / 2; k <= len / 2; k++) {
                int x = (int) Math.round(cx + ux * k), z = (int) Math.round(cz + uz * k);
                if (!s.within(x, z)) continue;
                double t = 1 - Math.abs(k) / (len / 2.0 + 1);
                int hh = (int) Math.round(h * Math.sqrt(t) * (0.8 + 0.4 * s.n(x, 0, z, 3, 5)));
                int gy = s.top(x, z) - 1;
                for (int y = gy; y <= gy + hh; y++) {
                    s.rock(x, y, z);
                    if (y < gy + hh * 0.6 && s.chance(x + y * 3 + z, 0.5)) s.rock((int) Math.round(x - uz), y, (int) Math.round(z + ux));
                }
            }
        }
        s.rubbleRing(0, 0, 14, 12);
        return true;
    }

    // ------------------------------------------------------------------ butte

    static boolean butte(Stage s) {
        int g = s.top(0, 0) - 1;
        double R = 6 + s.size * 3;
        int h = 8 + (int) (s.size * 6);
        for (int dx = -(int) R - 2; dx <= R + 2; dx++) {
            for (int dz = -(int) R - 2; dz <= R + 2; dz++) {
                if (!s.within(dx, dz)) continue;
                double d = Math.hypot(dx, dz) + (s.n(dx, 0, dz, 4, 3) - 0.5) * 2.5;
                int gy = s.top(dx, dz) - 1;
                if (d <= R) {
                    int top = g + h + (s.n(dx, 0, dz, 3, 5) > 0.6 ? 1 : 0);
                    for (int y = gy; y <= top; y++) s.rock(dx, y, dz);
                } else if (d <= R + 3) {
                    int sk = (int) Math.round(h * 0.45 * (1 - (d - R) / 3.0));
                    for (int y = gy; y <= g + sk; y++) s.rock(dx, y, dz);
                }
            }
        }
        // a goat path of rubble up one side
        double a = s.r.nextDouble() * Math.PI * 2;
        for (int k = 0; k < 14; k++) {
            double d = R + 3 - k * 0.55;
            int x = (int) Math.round(Math.cos(a + k * 0.07) * d), z = (int) Math.round(Math.sin(a + k * 0.07) * d);
            if (!s.within(x, z)) continue;
            int y = Math.min(g + h, s.top(x, z) - 1 + 0) + 0;
            s.set(x, g + 1 + (int) (k * h / 14.0), z, s.rubble(x, k, z));
        }
        s.dress(0, g + h + 2, 0);
        return true;
    }
}
