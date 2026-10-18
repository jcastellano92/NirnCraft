package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Surface entrances at the top of the stair tunnel: the Nordic arch set into a burial mound, the bronze Dwemer
 * gatehouse, a rocky cave mouth (dressed for bandits, beasts, vampires, necromancers or hagravens) and a timbered
 * mine adit with rails running out of it.
 */
public final class Portals {
    private Portals() {}

    /** Paints with tunnel-frame coordinates (t along the tunnel, lat sideways). */
    private record Frame(Painter p, DungeonPiece c) {
        int x(int t, int lat) {
            return c.frameX(t, lat);
        }

        int z(int t, int lat) {
            return c.frameZ(t, lat);
        }

        void set(int t, int y, int lat, BlockState s) {
            p.set(x(t, lat), y, z(t, lat), s);
        }

        void setIfOpen(int t, int y, int lat, BlockState s) {
            p.setIfOpen(x(t, lat), y, z(t, lat), s);
        }

        void air(int t, int y, int lat) {
            p.air(x(t, lat), y, z(t, lat));
        }

        void foundation(int t, int topY, int lat, BlockState s) {
            p.foundation(x(t, lat), topY, z(t, lat), s, 9);
        }

        void clear(int t, int fromY, int lat, int toY) {
            p.clear(x(t, lat), fromY, z(t, lat), toY);
        }
    }

    public static void build(Painter p, DungeonPiece c, RandomSource r) {
        Frame f = new Frame(p, c);
        switch (c.theme) {
            case BARROW -> {
                if (c.kit == Kit.ATMORAN) icebound(f, c);
                barrow(f, c);
            }
            case DWEMER -> dwemer(f, c);
            case MINE -> {
                outcrop(f, c);
                mine(f, c);
            }
            case RUIN -> ruin(f, c);
            default -> {
                outcrop(f, c);
                caveMouth(f, c);
            }
        }
    }

    // ------------------------------------------------------------------ culture ruins

    /** The face a culture ruin shows the world, over the top of its stairs. */
    private static void ruin(Frame f, DungeonPiece c) {
        Kit.Palette pal = (c.kit == Kit.NONE ? Kit.NORD : c.kit).palette();
        switch (c.kit) {
            case AYLEID -> ayleid(f, c, pal);
            case VELOTHI -> tombHouse(f, c, pal, true);
            case DAEDRIC -> daedricGate(f, c, pal);
            case XANMEER -> pyramid(f, c, pal, 4);
            case KHAJIIT -> moonTemple(f, c, pal);
            case BOSMER -> hollowTree(f, c);
            case ALTMER -> crystalSpire(f, c, pal);
            case REDGUARD -> tombHouse(f, c, pal, false);
            case ATMORAN -> {
                icebound(f, c);
                barrow(f, c);
            }
            case MAORMER, SLOAD -> coralMouth(f, c, pal);
            case AKAVIRI -> akaviriGate(f, c, pal);
            default -> barrow(f, c);
        }
    }

    /** Paved forecourt and a 3x3 opening in a facade wall at t = T + 1. */
    private static void facade(Frame f, DungeonPiece c, Kit.Palette pal, int halfWidth, int height) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        for (int t = T + 1; t <= T + 6; t++) {
            for (int lat = -halfWidth; lat <= halfWidth; lat++) {
                f.clear(t, y, lat, y + height + 2);
                f.set(t, y - 1, lat, pal.floor(p, f.x(t, lat), y - 1, f.z(t, lat)));
                f.foundation(t, y - 2, lat, pal.wall(p, f.x(t, lat), y - 2, f.z(t, lat), 0));
            }
        }
        int at = T + 1;
        for (int lat = -halfWidth; lat <= halfWidth; lat++) {
            for (int yy = y; yy <= y + height; yy++) {
                if (Math.abs(lat) <= 1 && yy <= y + 2) {
                    f.air(at, yy, lat);
                    continue;
                }
                f.set(at, yy, lat, Math.abs(lat) == halfWidth || yy == y + height ? pal.trim : pal.wall(p, f.x(at, lat), yy, f.z(at, lat), yy - y));
            }
        }
        descent(f, c, 1, c.tunnelLength() + 6);
    }

    private static void ayleid(Frame f, DungeonPiece c, Kit.Palette pal) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        mound(f, c, true);
        facade(f, c, pal, 3, 5);
        // the welkynd arch: a pointed crown over the door with a glowing stone at its peak
        int at = T + 1;
        f.set(at, y + 6, 0, Blocks.SEA_LANTERN.defaultBlockState());
        for (int s : new int[]{-1, 1}) f.set(at, y + 6, s, Deco.stair(pal.stairs, s < 0 ? c.right() : c.right().getOpposite(), false));
        // a ring of broken columns around the forecourt
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * (i / 7.0);
            int t = T + 4 + (int) Math.round(Math.sin(a) * 4), lat = (int) Math.round(Math.cos(a) * 6);
            int h = 2 + p.roll(f.x(t, lat), 0, f.z(t, lat), 330, 4);
            for (int dy = 0; dy < h; dy++) f.set(t, y + dy, lat, pal.pillar(dy, h + 4));
            if (h >= 4) f.set(t, y + h, lat, Blocks.SEA_LANTERN.defaultBlockState());
        }
    }

    /** A small domed (Velothi) or flat-roofed sandstone (Yokudan) tomb house over the stairs. */
    private static void tombHouse(Frame f, DungeonPiece c, Kit.Palette pal, boolean dome) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        // walls around the last stretch of the stairs (t = T-3 .. T+1, lat -3..3)
        for (int t = T - 3; t <= T + 1; t++) {
            for (int lat = -3; lat <= 3; lat++) {
                boolean wall = t == T - 3 || t == T + 1 || Math.abs(lat) == 3;
                int ft = c.tunnelFloor(Math.min(t, T));
                for (int yy = y - 1; yy <= y + 4; yy++) {
                    boolean stairwell = Math.abs(lat) <= 1 && t <= T && yy >= ft - 1 && yy <= ft + 3;
                    if (stairwell) continue;
                    if (wall && !(t == T + 1 && Math.abs(lat) <= 1 && yy >= y && yy <= y + 2)) {
                        f.set(t, yy, lat, yy == y + 4 ? pal.trim : pal.wall(p, f.x(t, lat), yy, f.z(t, lat), yy - y));
                    } else if (yy >= y && !stairwell) {
                        f.air(t, yy, lat);
                    }
                }
                f.foundation(t, y - 2, lat, pal.wall(p, f.x(t, lat), y - 2, f.z(t, lat), 0));
                // roof
                int ct = t - (T - 1);
                if (dome) {
                    int d = Math.max(Math.abs(ct), Math.abs(lat));
                    int h = d <= 1 ? 3 : d == 2 ? 2 : 1;
                    for (int dy = 1; dy <= h; dy++) f.set(t, y + 4 + dy, lat, dy == h ? pal.trim : pal.wall(p, f.x(t, lat), y + 4 + dy, f.z(t, lat), 2));
                } else {
                    f.set(t, y + 5, lat, Deco.slab(pal.slab, false));
                }
            }
        }
        f.set(T + 2, y + 2, -2, pal.light);
        f.set(T + 2, y + 2, 2, pal.light);
        approach(f, c, 2, 5, pal.floor(p, f.x(T + 3, 0), y - 1, f.z(T + 3, 0)), true);
    }

    private static void daedricGate(Frame f, DungeonPiece c, Kit.Palette pal) {
        int T = c.tunnelLength(), y = c.aux;
        mound(f, c, true);
        facade(f, c, pal, 3, 4);
        int at = T + 1;
        for (int s : new int[]{-4, 4}) {
            for (int dy = 0; dy <= 9; dy++) {
                BlockState st = dy == 9 ? Blocks.POINTED_DRIPSTONE.defaultBlockState()
                        : dy % 3 == 2 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                f.set(at, y + dy, s, st);
            }
            f.set(at + 1, y, s, Blocks.MAGMA_BLOCK.defaultBlockState());
            f.set(at + 1, y + 1, s, Deco.campfire(true, true));
        }
        for (int lat = -3; lat <= 3; lat++) f.set(at, y + 5 + (3 - Math.abs(lat)) / 2, lat, Blocks.GILDED_BLACKSTONE.defaultBlockState());
    }

    /** A stepped xanmeer: square tiers rising over the stairs, the door in the bottom tier's face. */
    private static void pyramid(Frame f, DungeonPiece c, Kit.Palette pal, int tiers) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        int ct = T - 4; // centre of the pyramid, behind the door
        int r0 = 7;
        for (int t = ct - r0; t <= ct + r0; t++) {
            for (int lat = -r0; lat <= r0; lat++) {
                int d = Math.max(Math.abs(t - ct), Math.abs(lat));
                int top = y - 1 + (r0 - d) / 2 * 2 + 1;
                int ft = c.tunnelFloor(Math.max(1, Math.min(t, T)));
                for (int yy = y - 1; yy <= Math.min(top, y + tiers * 2); yy++) {
                    boolean stairwell = Math.abs(lat) <= 1 && t <= T && yy >= ft - 1 && yy <= ft + 3;
                    boolean doorway = Math.abs(lat) <= 1 && t > T && yy >= y && yy <= y + 2;
                    if (stairwell || doorway) {
                        if (yy >= y) f.air(t, yy, lat);
                        continue;
                    }
                    f.set(t, yy, lat, yy == top && (r0 - d) % 2 == 1 ? Deco.slab(pal.slab, false) : pal.wall(p, f.x(t, lat), yy, f.z(t, lat), yy - y));
                }
                // (never down the stairwell: the foundation would wall up the stairs)
                if (Math.abs(lat) > 1 || t > T) f.foundation(t, y - 2, lat, pal.wall(p, f.x(t, lat), y - 2, f.z(t, lat), 0));
            }
        }
        // the shrine on top, and vines down the faces
        int topY = y + tiers * 2;
        f.set(ct, topY + 1, 0, pal.light);
        for (int s : new int[]{-1, 1}) f.set(ct, topY + 1, s, pal.accent);
        approach(f, c, 2, 4, pal.floor(p, f.x(T + 8, 0), y - 1, f.z(T + 8, 0)), true);
    }

    private static void moonTemple(Frame f, DungeonPiece c, Kit.Palette pal) {
        int T = c.tunnelLength(), y = c.aux;
        mound(f, c, true);
        facade(f, c, pal, 4, 6);
        int at = T + 1;
        // four columns under a porch roof
        for (int s : new int[]{-4, -2, 2, 4}) {
            for (int dy = 0; dy <= 5; dy++) f.set(at + 4, y + dy, s, pal.pillar(dy, 5));
        }
        for (int t = at; t <= at + 4; t++) for (int lat = -5; lat <= 5; lat++) f.set(t, y + 6, lat, Deco.slab(pal.slab, false));
        // the twin moons over the door: a gold crescent and a pale froglight
        int[][] crescent = {{-2, 7}, {-1, 8}, {0, 8}, {1, 8}, {2, 7}, {-2, 6}, {2, 6}};
        for (int[] cr : crescent) f.set(at, y + cr[1], cr[0], Blocks.GOLD_BLOCK.defaultBlockState());
        f.set(at, y + 7, 0, Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
    }

    /** A dead Graht-oak, hollow, the stairs going down between its roots. */
    private static void hollowTree(Frame f, DungeonPiece c) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        int ct = T - 1;
        for (int t = ct - 5; t <= ct + 5; t++) {
            for (int lat = -5; lat <= 5; lat++) {
                double d = Math.hypot(t - ct, lat);
                int ft = c.tunnelFloor(Math.max(1, Math.min(t, T)));
                for (int yy = y - 2; yy <= y + 16; yy++) {
                    boolean stairwell = Math.abs(lat) <= 1 && t <= T && yy >= ft - 1 && yy <= ft + 3;
                    boolean doorway = Math.abs(lat) <= 1 && t > ct && yy >= y && yy <= y + 2;
                    if (stairwell) continue;
                    double rr = yy < y + 2 ? 4.6 : 3.6;
                    if (d <= rr && d >= rr - 1.2 && !doorway) f.set(t, yy, lat, Deco.pillar(Blocks.JUNGLE_LOG, Direction.Axis.Y));
                    else if (d < rr - 1.2 && yy >= y) f.air(t, yy, lat);
                }
                // canopy
                for (int yy = y + 14; yy <= y + 18; yy++) {
                    double cd = Math.hypot(t - ct, lat) + Math.abs(yy - (y + 16)) * 1.2;
                    if (cd < 5.5 + p.noise(f.x(t, lat), yy, f.z(t, lat), 2.0, 340) && !(d < 3.6 && yy <= y + 16)) {
                        f.setIfOpen(t, yy, lat, Blocks.JUNGLE_LEAVES.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
                    }
                }
                // roots
                if (d > 4.6 && d < 5.4 && p.chance(f.x(t, lat), 0, f.z(t, lat), 341, 2)) {
                    f.setIfOpen(t, y, lat, Blocks.MANGROVE_ROOTS.defaultBlockState());
                }
            }
        }
        f.set(ct + 3, y + 2, 0, Blocks.SHROOMLIGHT.defaultBlockState());
        approach(f, c, 1, 5, Blocks.ROOTED_DIRT.defaultBlockState(), false);
    }

    private static void crystalSpire(Frame f, DungeonPiece c, Kit.Palette pal) {
        int T = c.tunnelLength(), y = c.aux;
        mound(f, c, true);
        facade(f, c, pal, 3, 5);
        int at = T + 1;
        for (int s : new int[]{-3, 3}) {
            for (int dy = 0; dy <= 12; dy++) {
                BlockState st = dy < 6 ? pal.trim : dy % 2 == 0 ? Blocks.AMETHYST_BLOCK.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.PURPLE_STAINED_GLASS.defaultBlockState();
                f.set(at, y + dy, s, st);
            }
            f.set(at, y + 13, s, Blocks.END_ROD.defaultBlockState());
        }
        for (int lat = -2; lat <= 2; lat++) f.set(at, y + 6, lat, com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
        f.set(at, y + 7, 0, Blocks.AMETHYST_CLUSTER.defaultBlockState());
    }

    /** Snow and ice drifted over a Nordic mound. */
    private static void icebound(Frame f, DungeonPiece c) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        for (int t = T - 4; t <= T + 1; t++) {
            for (int lat = -6; lat <= 6; lat++) {
                int top = y + 5 - Math.max(0, Math.abs(lat) - 2);
                int ft = c.tunnelFloor(Math.max(1, Math.min(t, T)));
                for (int yy = y; yy <= top; yy++) {
                    if (Math.abs(lat) <= 2 && yy <= ft + 3) continue;
                    f.setIfOpen(t, yy, lat, p.chance(f.x(t, lat), yy, f.z(t, lat), 350, 3) ? Blocks.PACKED_ICE.defaultBlockState() : Blocks.SNOW_BLOCK.defaultBlockState());
                }
            }
        }
    }

    private static void coralMouth(Frame f, DungeonPiece c, Kit.Palette pal) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        outcrop(f, c);
        for (int t = T - 2; t <= T + 4; t++) {
            for (int lat = -5; lat <= 5; lat++) {
                double d = Math.hypot((t - T - 1) / 1.3, lat);
                if (d < 4 || d > 5.5) continue;
                int h = 1 + p.roll(f.x(t, lat), 0, f.z(t, lat), 360, 3);
                for (int dy = 0; dy < h; dy++) f.set(t, y + dy, lat, pal.wall(p, f.x(t, lat), y + dy, f.z(t, lat), 1));
                if (c.kit == Kit.SLOAD && p.chance(f.x(t, lat), 1, f.z(t, lat), 361, 4)) f.set(t, y + h, lat, Blocks.SLIME_BLOCK.defaultBlockState());
                else f.set(t, y + h, lat, Blocks.DEAD_BRAIN_CORAL_FAN.defaultBlockState());
            }
        }
        f.set(T + 1, y + 3, 0, pal.light);
    }

    /** An Akaviri gate: lacquered posts, a double crossbeam and a curved roof. */
    private static void akaviriGate(Frame f, DungeonPiece c, Kit.Palette pal) {
        int T = c.tunnelLength(), y = c.aux;
        mound(f, c, true);
        facade(f, c, pal, 3, 4);
        int gt = T + 4;
        for (int s : new int[]{-3, 3}) {
            for (int dy = 0; dy <= 5; dy++) f.set(gt, y + dy, s, Deco.pillar(Blocks.STRIPPED_MANGROVE_LOG, Direction.Axis.Y));
            f.set(gt + 1, y, s, Blocks.LANTERN.defaultBlockState());
        }
        for (int lat = -4; lat <= 4; lat++) {
            f.set(gt, y + 4, lat, com.skycraft.worldgen.VanillaBlocks.RED_WOOL.defaultBlockState());
            f.set(gt, y + 6, lat, Math.abs(lat) == 4 ? Deco.stair(Blocks.DEEPSLATE_TILE_STAIRS, lat < 0 ? c.right() : c.right().getOpposite(), false)
                    : Blocks.DEEPSLATE_TILE_SLAB.defaultBlockState());
        }
    }

    // ------------------------------------------------------------------ shared

    /** Clears a 5-wide approach in front of the entrance and gives it a floor. */
    private static void approach(Frame f, DungeonPiece c, int halfWidth, int length, BlockState floor, boolean pave) {
        int T = c.tunnelLength(), y = c.aux;
        for (int t = T + 1; t <= T + length; t++) {
            for (int lat = -halfWidth; lat <= halfWidth; lat++) {
                f.clear(t, y, lat, y + 5);
                if (pave) {
                    f.set(t, y - 1, lat, floor);
                    f.foundation(t, y - 2, lat, Blocks.COBBLESTONE.defaultBlockState());
                } else {
                    f.setIfOpen(t, y - 1, lat, floor);
                    f.foundation(t, y - 2, lat, Blocks.DIRT.defaultBlockState());
                }
            }
        }
        descent(f, c, halfWidth, T + length);
    }

    /**
     * Where the ground falls away beyond the forecourt, steps lead down to it (a mouth on a slope is never a ledge
     * over a drop the player can't climb back). Each column checks only its own ground, so chunks agree.
     */
    private static void descent(Frame f, DungeonPiece c, int halfWidth, int from) {
        Painter p = f.p();
        Direction up = c.facing().getOpposite();
        Block stairs = c.theme.natural() ? Blocks.COBBLESTONE_STAIRS : BuiltBuilder.stairBlock(p, c.theme);
        for (int t = from + 1; t <= c.tunnelLength() + APPROACH; t++) {
            int walk = c.aux - (t - from);
            for (int lat = -halfWidth; lat <= halfWidth; lat++) {
                int x = f.x(t, lat), z = f.z(t, lat);
                if (!p.ownsColumn(x, z)) continue;
                int ground = p.surfaceAt(x, z) - 1;
                if (ground >= walk - 1) continue;
                p.set(x, walk - 1, z, Deco.stair(stairs, up, false));
                p.foundation(x, walk - 2, z, c.theme.natural() ? Blocks.COBBLESTONE.defaultBlockState() : BuiltBuilder.wall(p, c.theme, x, walk - 2, z, 0), 24);
                f.clear(t, walk, lat, walk + 3);
            }
        }
    }

    /** How far beyond the tunnel's surface end the portal piece reaches (forecourt and descent). */
    public static final int APPROACH = 16;

    /** A burial mound / hill over the last stretch of the tunnel so the entrance sits in the ground. */
    private static void mound(Frame f, DungeonPiece c, boolean stone) {
        int T = c.tunnelLength(), y = c.aux;
        for (int t = T - 3; t <= T; t++) {
            int ft = c.tunnelFloor(t);
            for (int lat = -5; lat <= 5; lat++) {
                int top = y + 4 - Math.max(0, Math.abs(lat) - 2) - Math.max(0, t - (T - 1));
                for (int yy = y - 1; yy <= top; yy++) {
                    if (Math.abs(lat) <= 2 && yy <= ft + 3) continue;
                    BlockState s = stone ? (f.p().chance(f.x(t, lat), yy, f.z(t, lat), 300, 3) ? Blocks.ANDESITE.defaultBlockState() : Blocks.STONE.defaultBlockState())
                            : yy == top ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                    f.setIfOpen(t, yy, lat, s);
                }
                if (Math.abs(lat) > 2) f.foundation(t, y - 2, lat, Blocks.DIRT.defaultBlockState());
            }
        }
    }

    // ------------------------------------------------------------------ Nordic barrow

    private static void barrow(Frame f, DungeonPiece c) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        Direction out = c.facing(), right = c.right();
        mound(f, c, false);
        // weathered stone platform
        for (int t = T + 1; t <= T + 6; t++) {
            for (int lat = -4; lat <= 4; lat++) {
                f.clear(t, y, lat, y + 7);
                f.set(t, y - 1, lat, BuiltBuilder.floor(p, Theme.FORT, f.x(t, lat), y - 1, f.z(t, lat)));
                f.foundation(t, y - 2, lat, Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        // the arch
        int at = T + 1;
        for (int lat = -3; lat <= 3; lat++) {
            int a = Math.abs(lat);
            for (int yy = y; yy <= y + 7; yy++) {
                boolean opening = a <= 1 && yy <= y + 2;
                if (opening) {
                    f.air(at, yy, lat);
                    continue;
                }
                int peak = y + 7 - a;
                if (yy > peak) continue;
                BlockState s;
                if (yy == peak) s = a == 0 ? Deco.slab(Blocks.STONE_BRICK_SLAB, false) : Deco.stair(Blocks.STONE_BRICK_STAIRS, lat < 0 ? right : right.getOpposite(), false);
                else if (a == 3 || (a <= 1 && yy == y + 3)) s = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                else s = BuiltBuilder.wall(p, Theme.BARROW, f.x(at, lat), yy, f.z(at, lat), 1);
                f.set(at, yy, lat, s);
            }
        }
        // dragon-horn finials curling out of the gable
        for (int s : new int[]{-1, 1}) {
            Direction side = s < 0 ? right.getOpposite() : right;
            f.set(at, y + 5, 4 * s, Deco.stair(Blocks.STONE_BRICK_STAIRS, side.getOpposite(), true));
            f.set(at, y + 6, 4 * s, Deco.stair(Blocks.STONE_BRICK_STAIRS, side, false));
            f.set(at + 1, y + 4, 4 * s, Deco.stair(Blocks.STONE_BRICK_STAIRS, out.getOpposite(), true));
        }
        // braziers and urns
        for (int s : new int[]{-4, 4}) {
            f.set(T + 5, y, s, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            f.set(T + 5, y + 1, s, Deco.campfire(false, true));
            f.set(T + 2, y, s - Integer.signum(s), Blocks.DECORATED_POT.defaultBlockState());
        }
        // standing stones along the path
        for (int s : new int[]{-3, 3}) {
            for (int yy = y; yy <= y + 1; yy++) f.set(T + 7, yy, s, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
            f.set(T + 7, y + 2, s, Deco.slab(Blocks.MOSSY_STONE_BRICK_SLAB, false));
        }
        descent(f, c, 1, c.tunnelLength() + 6);
    }

    // ------------------------------------------------------------------ Dwemer gatehouse

    private static void dwemer(Frame f, DungeonPiece c) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        Direction right = c.right();
        mound(f, c, true);
        for (int t = T + 1; t <= T + 6; t++) {
            for (int lat = -4; lat <= 4; lat++) {
                f.clear(t, y, lat, y + 8);
                boolean border = Math.abs(lat) == 4 || t == T + 6;
                f.set(t, y - 1, lat, border ? com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                f.foundation(t, y - 2, lat, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            }
        }
        int at = T + 1;
        for (int lat = -3; lat <= 3; lat++) {
            int a = Math.abs(lat);
            for (int yy = y; yy <= y + 6; yy++) {
                if (a <= 1 && yy <= y + 2) {
                    f.air(at, yy, lat);
                    continue;
                }
                BlockState s;
                if (a >= 2 && yy <= y + 4) s = a == 3 ? com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.WAXED_EXPOSED_CUT_COPPER.defaultBlockState();
                else if (yy == y + 3) s = Blocks.GILDED_BLACKSTONE.defaultBlockState();
                else if (yy == y + 5) s = a <= 2 ? com.skycraft.worldgen.VanillaBlocks.WAXED_WEATHERED_CUT_COPPER.defaultBlockState() : Deco.slab(com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER_SLAB, false);
                else if (yy == y + 6) s = a == 0 ? Blocks.OCHRE_FROGLIGHT.defaultBlockState() : a == 1 ? Deco.slab(com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER_SLAB, false) : Blocks.AIR.defaultBlockState();
                else s = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                f.set(at, yy, lat, s);
            }
        }
        for (int s : new int[]{-3, 3}) {
            f.set(at, y + 5, s, com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState());
            f.set(at, y + 6, s, DwemerRooms.rod());
            f.set(at, y + 7, s, DwemerRooms.rod());
            f.set(at + 1, y + 2, s, Deco.lantern(false, false));
            f.set(at + 1, y + 1, s, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
            f.set(at + 1, y, s, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
        }
        // steam vents on the forecourt
        for (int s : new int[]{-3, 3}) {
            f.set(T + 4, y - 1, s, Deco.campfire(false, true));
            f.set(T + 4, y, s, Deco.trapdoor(Blocks.IRON_TRAPDOOR, right, false, false));
        }
        // a broken pipe column
        f.set(T + 6, y, -2, com.skycraft.worldgen.VanillaBlocks.WAXED_EXPOSED_COPPER.defaultBlockState());
        f.set(T + 6, y + 1, -2, DwemerRooms.rod());
        p.spawn(Mobs.DWARVEN_SPIDER, f.x(T + 3, 0), y, f.z(T + 3, 0), Painter.yaw(c.facing()), null);
        descent(f, c, 1, c.tunnelLength() + 6);
    }

    // ------------------------------------------------------------------ caves and lairs

    /** A rocky knoll the cave mouth opens out of. */
    private static void outcrop(Frame f, DungeonPiece c) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        double ct = T - 0.5;
        for (int t = T - 3; t <= T + 2; t++) {
            for (int lat = -6; lat <= 6; lat++) {
                double dt = (t - ct) / 3.6, dl = lat / 5.8;
                double d = dt * dt + dl * dl;
                if (d >= 1.0) continue;
                int x = f.x(t, lat), z = f.z(t, lat);
                int h = y + (int) Math.round((1.0 - d) * 6.0 + p.noise(x, y, z, 3.0, 310) * 1.5);
                for (int yy = y - 1; yy <= h; yy++) {
                    if (Math.abs(lat) <= 1 && yy <= y + 2) continue;
                    if (Math.abs(lat) <= 1 && t <= T && yy <= c.tunnelFloor(t) + 2) continue;
                    int roll = p.roll(x, yy, z, 311, 10);
                    BlockState s = yy == h && roll < 4 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                            : roll < 2 ? Blocks.COBBLESTONE.defaultBlockState() : roll < 3 ? Blocks.ANDESITE.defaultBlockState()
                            : roll < 4 ? Blocks.TUFF.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    f.setIfOpen(t, yy, lat, s);
                }
                f.foundation(t, y - 2, lat, Blocks.STONE.defaultBlockState());
            }
        }
        // keep the mouth itself open
        for (int t = T - 1; t <= T + 3; t++) {
            for (int lat = -1; lat <= 1; lat++) f.clear(t, y, lat, y + 2);
        }
        approach(f, c, 1, 5, Blocks.COARSE_DIRT.defaultBlockState(), false);
    }

    private static void caveMouth(Frame f, DungeonPiece c) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        switch (c.theme) {
            case VAMPIRE_LAIR -> {
                f.set(T + 2, y, -2, Deco.candle(com.skycraft.worldgen.VanillaBlocks.RED_CANDLE, 3, true));
                f.set(T + 2, y, 2, Deco.skull(Blocks.SKELETON_SKULL, 0));
            }
            case NECROMANCER_LAIR -> {
                for (int s : new int[]{-2, 2}) {
                    f.set(T + 2, y, s, Blocks.DARK_OAK_FENCE.defaultBlockState());
                    f.set(T + 2, y + 1, s, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(c.facing())));
                }
                f.set(T + 1, y + 2, 0, Deco.lantern(true, true));
            }
            case HAGRAVEN_LAIR -> {
                f.set(T + 2, y, -2, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Y));
                f.set(T + 2, y + 1, -2, com.skycraft.worldgen.VanillaBlocks.WHITE_CARPET.defaultBlockState());
                f.set(T + 3, y, 2, Blocks.MANGROVE_ROOTS.defaultBlockState());
            }
            default -> {
                int roster = CaveRoster.of(c.flavor);
                if (roster == CaveRoster.FORSWORN) {
                    // Forsworn totems: antlers and hide on poles
                    for (int s : new int[]{-2, 2}) {
                        f.set(T + 2, y, s, Blocks.SPRUCE_FENCE.defaultBlockState());
                        f.set(T + 2, y + 1, s, Blocks.SPRUCE_FENCE.defaultBlockState());
                        f.set(T + 2, y + 2, s, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(c.facing())));
                    }
                    p.spawn(Mobs.forsworn(), f.x(T + 3, 0), y, f.z(T + 3, 0), Painter.yaw(c.facing()), null);
                } else if (roster == CaveRoster.ICE) {
                    for (int s : new int[]{-2, 2}) f.set(T + 1, y, s, Blocks.PACKED_ICE.defaultBlockState());
                    f.set(T + 2, y, 0, Blocks.SNOW.defaultBlockState());
                } else if (roster == CaveRoster.FALMER || roster == CaveRoster.SPIDER) {
                    for (int s : new int[]{-1, 1}) f.setIfOpen(T, y + 2, s, Blocks.COBWEB.defaultBlockState());
                    f.set(T + 2, y, 2, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X));
                } else if (roster == CaveRoster.SPRIGGAN) {
                    f.set(T + 2, y, -2, Blocks.AZALEA.defaultBlockState());
                    f.set(T + 2, y, 2, Blocks.MOSS_BLOCK.defaultBlockState());
                } else if (CaveRoster.inhabited(roster)) {
                    // bandit lookout: torches and a sentry
                    for (int s : new int[]{-2, 2}) {
                        f.set(T + 2, y, s, Blocks.SPRUCE_FENCE.defaultBlockState());
                        f.set(T + 2, y + 1, s, Blocks.TORCH.defaultBlockState());
                    }
                    p.spawn(Mobs.bandit(), f.x(T + 3, 0), y, f.z(T + 3, 0), Painter.yaw(c.facing()), null);
                } else {
                    f.set(T + 2, y, 2, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X));
                    f.set(T + 3, y, -2, Deco.skull(Blocks.SKELETON_SKULL, 6));
                }
            }
        }
    }

    // ------------------------------------------------------------------ mine adit

    private static void mine(Frame f, DungeonPiece c) {
        int T = c.tunnelLength(), y = c.aux;
        Painter p = f.p();
        Direction out = c.facing(), right = c.right();
        // timber portal
        int at = T + 1;
        for (int s : new int[]{-2, 2}) {
            for (int yy = y; yy <= y + 2; yy++) f.set(at, yy, s, Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y));
        }
        for (int lat = -3; lat <= 3; lat++) f.set(at, y + 3, lat, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, right.getAxis()));
        for (int lat = -2; lat <= 2; lat++) f.set(at, y + 4, lat, Deco.slab(Blocks.SPRUCE_SLAB, false));
        f.set(at, y + 2, 0, Deco.lantern(false, true));
        // rails running out onto the spoil heap
        RailShape shape = out.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        for (int t = T + 1; t <= T + 5; t++) f.set(t, y, 0, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape));
        p.chestMinecart(f.x(T + 4, 0), y, f.z(T + 4, 0), Deco.LOOT_MINE);
        f.set(T + 3, y, 2, Blocks.BARREL.defaultBlockState());
        f.set(T + 4, y, 2, p.chance(f.x(T, 0), y, f.z(T, 0), 320, 2) ? Blocks.RAW_IRON_BLOCK.defaultBlockState() : Blocks.COAL_BLOCK.defaultBlockState());
        f.set(T + 3, y, -2, Blocks.TUFF.defaultBlockState());
        f.set(T + 4, y, -2, Blocks.COBBLESTONE.defaultBlockState());
        f.set(T + 4, y + 1, -2, Deco.slab(Blocks.COBBLESTONE_SLAB, false));
    }
}
