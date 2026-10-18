package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Towers of the wilds: the ruined Imperial and Nordic watchtowers (flavor 0 / 1), lighthouses, mage towers (a
 * necromancer's black tower, or an Aldmeri crystal spire with the {@code altmer} kit) and the Dwemer Great Lifts that
 * drop into Blackreach.
 */
public final class Towers {
    private Towers() {}

    public static void build(Frame fr, DungeonPiece c) {
        switch (c.theme) {
            case LIGHTHOUSE -> lighthouse(fr, c);
            case MAGE_TOWER -> mageTower(fr, c);
            case GREAT_LIFT -> greatLift(fr, c);
            default -> watchtower(fr, c);
        }
    }

    // ------------------------------------------------------------------ shared

    /** Levels a round (or square) plot around the tower. */
    static void plot(Frame fr, int radius, Frame.Masonry ground, int clear) {
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > radius + 0.5) return;
            fr.p.clear(x, fr.base, z, fr.base + clear);
            if (d <= radius - 1.5 || fr.p.chance(x, 0, z, 800, 2)) fr.p.set(x, fr.base - 1, z, ground.at(fr.p, x, fr.base - 1, z));
            fr.p.foundation(x, fr.base - 2, z, Blocks.STONE.defaultBlockState(), 12);
        });
    }

    /** A ladder up the inside of a wall at local (fwd, lat), facing {@code face} (local direction), dy0..dy1. */
    static void ladder(Frame fr, int fwd, int lat, int dy0, int dy1, Direction face) {
        BlockState l = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, fr.dir(face));
        for (int dy = dy0; dy <= dy1; dy++) fr.set(fwd, dy, lat, l);
    }

    /** A hide tent with a bedroll, along the forward axis at (fwd0, lat0). */
    static void tent(Frame fr, int fwd0, int lat0, BlockState hide) {
        for (int fwd = fwd0 - 2; fwd <= fwd0 + 1; fwd++) {
            fr.set(fwd, 0, lat0 - 1, hide);
            fr.set(fwd, 1, lat0 - 1, hide);
            fr.set(fwd, 0, lat0 + 1, hide);
            fr.set(fwd, 1, lat0 + 1, hide);
            fr.set(fwd, 2, lat0, hide);
            fr.set(fwd, 0, lat0, fwd < fwd0 ? com.skycraft.worldgen.VanillaBlocks.RED_CARPET.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
    }

    static void occupant(Frame fr, DungeonPiece c, int fwd, int dy, int lat, boolean chief) {
        int x = fr.x(fwd, lat), z = fr.z(fwd, lat);
        if (c.kit == Kit.FORSWORN) {
            boolean hag = chief && fr.p.chance(x, 0, z, 801, 2);
            fr.p.spawn(hag ? Mobs.hagraven() : Mobs.forsworn(), x, fr.base + dy, z, fr.p.roll(x, 0, z, 802, 360),
                    chief ? (hag ? null : "Forsworn Briarheart") : null);
            return;
        }
        FortRooms.occupant(fr.p, c.flavor >= 2 ? c.flavor : 0, x, fr.base + dy, z, fr.p.roll(x, 0, z, 803, 360), chief);
    }

    // ------------------------------------------------------------------ watchtower

    private static void watchtower(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        boolean nordic = c.flavor % 2 == 1;
        Frame.Masonry stone = (pp, x, y, z) -> BuiltBuilder.wall(pp, nordic ? Theme.BARROW : Theme.FORT, x, y, z, 1);
        plot(fr, 9, (pp, x, y, z) -> pp.chance(x, y, z, 804, 3) ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 26);
        int h = 19;
        int r = 3;
        // walls: square Imperial keep, or an octagonal Nordic tower
        fr.columns(-r, -r, r, r, (fw, lt, x, z) -> {
            boolean edge = Math.abs(fw) == r || Math.abs(lt) == r;
            boolean corner = Math.abs(fw) == r && Math.abs(lt) == r;
            if (nordic && corner) return;
            int top = h;
            double n = p.noise(x, 0, z, 3.0, 805);
            if (n > 0.25) top = h - 3 - (int) (n * 6); // ruined crown
            for (int dy = -1; dy <= top; dy++) {
                if (edge || (nordic && Math.abs(fw) + Math.abs(lt) >= 2 * r - 1)) {
                    boolean door = fw == r && lt == 0 && dy >= 0 && dy <= 1;
                    boolean slit = !edge ? false : (fw == 0 || lt == 0) && (dy == 4 || dy == 10 || dy == 16);
                    if (door || slit) {
                        p.air(x, fr.base + dy, z);
                        continue;
                    }
                    p.set(x, fr.base + dy, z, stone.at(p, x, fr.base + dy, z));
                } else if (dy == -1 || dy == 6 || dy == 12) {
                    p.set(x, fr.base + dy, z, dy == -1 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState());
                } else {
                    p.air(x, fr.base + dy, z);
                }
            }
            if (edge && top == h && Math.floorMod(fw + lt, 2) == 0) p.set(x, fr.base + h + 1, z, stone.at(p, x, fr.base + h + 1, z));
            if (!edge && top == h) p.set(x, fr.base + h, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
        });
        // ladder well in the back-left corner, through every floor
        for (int dy = 0; dy <= h; dy++) fr.air(-r + 1, dy, -r + 1);
        ladder(fr, -r + 1, -r + 1, 0, h, Direction.EAST);
        fr.set(r, 2, 0, Blocks.CHISELED_STONE_BRICKS);
        fr.set(r + 1, 2, -1, Deco.wallTorch(false, fr.f));
        // the camp at its foot
        tent(fr, 6, -5, com.skycraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState());
        fr.set(6, 0, 3, Deco.campfire(false, true));
        fr.set(7, 0, 5, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, fr.r.getAxis()));
        fr.container(-r + 1, 7, r - 1, Deco.chest(fr.r.getOpposite()), Deco.LOOT_COMMON);
        fr.container(5, 0, -3, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
        fr.set(-r + 2, 13, r - 1, Deco.lantern(false, false));
        occupant(fr, c, 5, 0, 1, false);
        occupant(fr, c, 0, 7, 1, false);
        occupant(fr, c, 1, h + 1, -1, true);
    }

    // ------------------------------------------------------------------ lighthouse

    private static void lighthouse(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        plot(fr, 8, (pp, x, y, z) -> pp.chance(x, y, z, 810, 2) ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 30);
        int h = 24;
        fr.columns(-4, -4, 4, 4, (fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > 3.6) return;
            boolean wall = d > 2.4;
            for (int dy = -1; dy <= h; dy++) {
                if (wall) {
                    boolean door = fw >= 3 && lt == 0 && dy >= 0 && dy <= 1;
                    boolean window = (dy == 8 || dy == 16) && (lt == 0 || fw == 0);
                    boolean lantern = dy >= h - 3 && dy <= h - 1 && (Math.abs(fw) <= 1 || Math.abs(lt) <= 1);
                    if (door || window || lantern) {
                        p.air(x, fr.base + dy, z);
                        continue;
                    }
                    BlockState band = (dy / 4) % 2 == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.CALCITE.defaultBlockState();
                    p.set(x, fr.base + dy, z, dy >= h - 4 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : band);
                } else {
                    p.set(x, fr.base + dy, z, dy == -1 || dy == h - 4 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
            // the conical cap
            if (d <= 3.6) {
                int capH = (int) Math.round((3.6 - d) * 1.2);
                for (int dy = 0; dy <= capH; dy++) p.set(x, fr.base + h + 1 + dy, z, Blocks.DEEPSLATE_TILES.defaultBlockState());
            }
        });
        // the great fire at the top, and the climb
        fr.set(0, h - 3, 0, Deco.campfire(false, true));
        fr.set(0, h - 2, 0, Blocks.AIR.defaultBlockState());
        for (int dy = 0; dy <= h - 4; dy++) fr.air(-2, dy, 0);
        ladder(fr, -2, 0, 0, h - 4, Direction.NORTH);
        fr.set(-2, h - 4, 0, Blocks.SPRUCE_TRAPDOOR.defaultBlockState());
        fr.container(1, 0, 1, Deco.chest(fr.f), Deco.LOOT_COMMON);
        fr.set(1, 0, -1, Blocks.BARREL.defaultBlockState());
        fr.set(1, 1, -1, Deco.lantern(false, false));
        // the keeper's shed
        Camps.shed(fr, 6, -2, true);
        if (fr.chance(0, 0, 811, 2)) fr.spawn(Mobs.skeever(), 0, 0, 1, fr.f, null);
    }

    // ------------------------------------------------------------------ mage tower

    private static void mageTower(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        boolean crystal = c.kit == Kit.ALTMER;
        Kit.Palette pal = (c.kit == Kit.NONE ? Kit.DAEDRIC : c.kit).palette();
        plot(fr, 8, (pp, x, y, z) -> pal.floor(pp, x, y, z), 34);
        int h = 26;
        fr.columns(-4, -4, 4, 4, (fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > 4.2) return;
            boolean wall = d > 3.0;
            for (int dy = -1; dy <= h; dy++) {
                if (wall) {
                    boolean door = fw >= 3 && lt == 0 && dy >= 0 && dy <= 2;
                    boolean window = (dy % 7 == 3 || dy % 7 == 4) && (Math.abs(lt) <= 0 || Math.abs(fw) <= 0) && dy > 2;
                    if (door) {
                        p.air(x, fr.base + dy, z);
                        continue;
                    }
                    if (window) {
                        p.set(x, fr.base + dy, z, crystal ? com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.PURPLE_STAINED_GLASS.defaultBlockState());
                        continue;
                    }
                    p.set(x, fr.base + dy, z, crystal && dy > 8 && Math.floorMod(fw * 3 + lt + dy, 5) == 0
                            ? Blocks.AMETHYST_BLOCK.defaultBlockState() : pal.wall(p, x, fr.base + dy, z, dy));
                } else {
                    boolean floor = dy == -1 || dy == 8 || dy == 16;
                    p.set(x, fr.base + dy, z, floor ? pal.floor(p, x, fr.base + dy, z) : Blocks.AIR.defaultBlockState());
                }
            }
            // spire
            int capH = (int) Math.round((4.2 - d) * (crystal ? 3.2 : 2.0));
            for (int dy = 1; dy <= capH; dy++) {
                p.set(x, fr.base + h + dy, z, crystal ? (dy % 2 == 0 ? Blocks.AMETHYST_BLOCK.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.PURPLE_STAINED_GLASS.defaultBlockState())
                        : pal.trim);
            }
        });
        if (crystal) fr.set(0, h + 15, 0, Blocks.END_ROD);
        // stairs: a ladder up the back wall through the floors
        for (int dy = 0; dy <= 16; dy++) fr.air(-2, dy, 0);
        ladder(fr, -2, 0, 0, 17, Direction.NORTH);
        // ground floor: study; first floor: library; top: the ritual circle
        fr.set(1, 0, 2, Blocks.CRAFTING_TABLE);
        fr.set(1, 0, -2, Blocks.BREWING_STAND);
        fr.container(0, 0, 2, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
        for (int lt = -2; lt <= 2; lt++) fr.set(0, 9, lt, Blocks.BOOKSHELF);
        fr.set(1, 9, 0, Blocks.LECTERN.defaultBlockState());
        fr.container(2, 9, -1, Deco.chest(fr.f.getOpposite()), Deco.LOOT_COMMON);
        fr.set(0, 17, 0, crystal ? Blocks.AMETHYST_BLOCK.defaultBlockState() : Blocks.CRYING_OBSIDIAN.defaultBlockState());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            fr.set(d.getStepZ() * -2, 17, d.getStepX() * 2, Deco.candle(crystal ? com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_CANDLE : com.skycraft.worldgen.VanillaBlocks.PURPLE_CANDLE, 3, true));
        }
        fr.container(2, 17, 1, Deco.chest(fr.f.getOpposite()), Deco.LOOT_BOSS);
        fr.set(0, 7, 0, pal.light.is(Blocks.SHROOMLIGHT) ? Deco.lantern(true, true) : pal.light);
        String mage = Mobs.necromancer();
        fr.spawn(mage, 1, 0, 0, fr.f, crystal ? "Aldmeri Sorcerer" : null);
        fr.spawn(crystal ? "minecraft:vex" : "minecraft:skeleton", 1, 9, 1, fr.f, crystal ? "Crystal Sentinel" : null);
        fr.p.spawnBoss(mage, fr.x(1, -1), fr.base + 17, fr.z(1, -1), Painter.yaw(fr.f),
                Identity.bossName(fr.p.place, crystal ? "Aldmeri Archmage" : "Master Necromancer"));
        if (!crystal) fr.spawn("minecraft:zombie", 4, 0, 4, fr.f, "Thrall");
    }

    // ------------------------------------------------------------------ Great Lift

    /**
     * A Dwemer lift house: a bronze hall over a shaft that drops (by ladder) all the way to Blackreach's floor, where
     * it opens into the cavern.
     */
    private static void greatLift(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        int bottom = Blackreach.FLOOR_Y - fr.base;
        plot(fr, 7, (pp, x, y, z) -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), 18);
        // the shaft
        fr.columns(-2, -2, 2, 2, (fw, lt, x, z) -> {
            boolean wall = Math.abs(fw) == 2 || Math.abs(lt) == 2;
            for (int dy = bottom; dy <= -1; dy++) {
                int y = fr.base + dy;
                boolean open = dy <= bottom + 4 && (fw == 0 || lt == 0); // arches into the cavern at the bottom
                if (wall && !open) p.set(x, y, z, dy % 6 == 0 ? Blocks.OCHRE_FROGLIGHT.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState());
                else if (dy == bottom - 1) p.set(x, y, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                else p.air(x, y, z);
            }
        });
        ladder(fr, 0, -1, bottom, 0, Direction.EAST);
        // the lift house: bronze walls, a domed roof, pipes and the lift cage
        fr.columns(-5, -5, 5, 5, (fw, lt, x, z) -> {
            boolean wall = Math.abs(fw) == 5 || Math.abs(lt) == 5;
            boolean door = fw == 5 && Math.abs(lt) <= 1;
            for (int dy = 0; dy <= 7; dy++) {
                int y = fr.base + dy;
                if (wall && !(door && dy <= 2)) {
                    p.set(x, y, z, dy == 0 || dy == 7 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
                            : dy == 4 ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.WAXED_EXPOSED_CUT_COPPER.defaultBlockState());
                } else if (!wall) {
                    boolean shaft = Math.abs(fw) <= 1 && Math.abs(lt) <= 1;
                    if (dy == 0 && !shaft && !(Math.abs(fw) == 2 || Math.abs(lt) == 2)) p.set(x, y - 1, z, com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState());
                    if (!(dy == 0 && (Math.abs(fw) == 2 || Math.abs(lt) == 2))) p.air(x, y, z);
                }
            }
            int roof = 8 + Math.max(0, 4 - Math.max(Math.abs(fw), Math.abs(lt)));
            for (int dy = 8; dy <= roof; dy++) p.set(x, fr.base + dy, z, dy == roof ? com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.WAXED_WEATHERED_CUT_COPPER.defaultBlockState());
        });
        // railings round the shaft and the lift's gears
        for (int i = -2; i <= 2; i++) {
            for (int[] e : new int[][]{{i, -2}, {i, 2}, {-2, i}, {2, i}}) {
                if (e[0] == 0 && e[1] == -2) continue;
                fr.set(e[0], 0, e[1], Deco.bars(Blocks.IRON_BARS, Math.abs(e[0]) == 2 ? fr.r.getAxis() : fr.f.getAxis()));
            }
        }
        fr.set(0, 0, -2, Blocks.AIR.defaultBlockState());
        for (int s : new int[]{-4, 4}) {
            for (int dy = 0; dy <= 7; dy++) fr.set(-4, dy, s, dy % 2 == 0 ? DwemerRooms.rod() : com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState());
            fr.set(3, 3, s, Blocks.OCHRE_FROGLIGHT);
        }
        fr.set(4, 1, 3, Deco.wallLever(fr.f.getOpposite()));
        fr.p.spawnBoss(Mobs.DWARVEN_SPHERE, fr.x(3, -3), fr.base, fr.z(3, -3), Painter.yaw(fr.f),
                Identity.bossName(fr.p.place, "Dwarven Sphere"));
        if (fr.chance(0, 0, 812, 2)) fr.spawn(Mobs.falmer(), 0, bottom, 1, fr.f, null);
    }

    /** Persistent leaves (so they never decay off trees we build). */
    static BlockState leaves(net.minecraft.world.level.block.Block b) {
        return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }
}
