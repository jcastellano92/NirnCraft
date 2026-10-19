package com.nirncraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Camps and dwellings: bandit camps (a palisade of stakes, tents and a lookout), Forsworn camps (hide huts, bone
 * totems, a hagraven's altar), the Legion's and the Stormcloaks' military camps (flavor 0 / 1: command tent, rows of
 * tents, banners, a smithy and the war table) and lone cabins and shacks.
 */
public final class Camps {
    private Camps() {}

    public static void build(Frame fr, DungeonPiece c) {
        switch (c.theme) {
            case FORSWORN_CAMP -> forsworn(fr, c);
            case MILITARY_CAMP -> military(fr, c);
            case CABIN -> cabin(fr, c);
            default -> bandits(fr, c);
        }
    }

    /** Round plot of trampled ground. */
    private static void ground(Frame fr, int radius, int salt) {
        boolean pad = fr.p.box.minY() >= fr.base - 1;
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > radius + 0.5) return;
            fr.p.clear(x, fr.base, z, fr.base + 10);
            int g = fr.p.roll(x, 0, z, salt, 10);
            BlockState s = d > radius - 1.5 && g < 5 ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : g < 4 ? Blocks.COARSE_DIRT.defaultBlockState() : g < 6 ? Blocks.DIRT_PATH.defaultBlockState()
                    : g < 7 ? Blocks.GRAVEL.defaultBlockState() : Blocks.PODZOL.defaultBlockState();
            fr.p.set(x, fr.base - 1, z, s);
            if (!pad) fr.p.foundation(x, fr.base - 2, z, Blocks.DIRT.defaultBlockState(), 10);
        });
    }

    // ------------------------------------------------------------------ bandits

    private static void bandits(Frame fr, DungeonPiece c) {
        ground(fr, 11, 820);
        // a ring of sharpened stakes with a gap at the front
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            int fw = (int) Math.round(Math.cos(a) * 11), lt = (int) Math.round(Math.sin(a) * 11);
            if (fw > 8 && Math.abs(lt) <= 2) continue;
            if (fr.chance(fw, lt, 821, 5)) continue;
            fr.set(fw, 0, lt, Blocks.SPRUCE_FENCE);
            fr.set(fw, 1, lt, Blocks.SPRUCE_FENCE);
            if (fr.chance(fw, lt, 822, 3)) fr.set(fw, 2, lt, Blocks.SPRUCE_FENCE);
        }
        Towers.tent(fr, -3, -5, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState());
        Towers.tent(fr, -3, 5, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState());
        Towers.tent(fr, -7, 0, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState());
        fire(fr, 1, 0);
        // a lookout platform on stilts
        lookout(fr, 5, -7);
        fr.container(-7, 0, 3, Deco.chest(fr.f), Deco.LOOT_COMMON);
        fr.container(-1, 0, 8, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
        fr.set(-1, 0, 7, Blocks.BARREL);
        fr.set(3, 0, 6, Blocks.GRINDSTONE);
        fr.set(4, 0, 6, Blocks.SMITHING_TABLE);
        fr.spawn(Mobs.bandit(), 2, 0, 2, fr.f, null);
        fr.spawn(Mobs.bandit(), 0, 0, -3, fr.r, null);
        fr.spawn(Mobs.bandit(), 5, 5, -7, fr.f, null);
        fr.spawn(Mobs.banditChief(), -6, 0, 0, fr.f, null);
    }

    /** A campfire with log seats and a cooking spit. */
    static void fire(Frame fr, int fw, int lt) {
        fr.set(fw, -1, lt, Blocks.COBBLESTONE);
        fr.set(fw, 0, lt, Deco.campfire(false, true));
        fr.set(fw + 2, 0, lt, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, fr.r.getAxis()));
        fr.set(fw - 2, 0, lt, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, fr.r.getAxis()));
        fr.set(fw, 0, lt + 2, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, fr.f.getAxis()));
    }

    /** A raised wooden platform with a ladder (bandit and Forsworn lookouts). */
    static void lookout(Frame fr, int fw0, int lt0) {
        for (int[] leg : new int[][]{{-1, -1}, {-1, 1}, {1, -1}, {1, 1}}) {
            for (int dy = 0; dy <= 3; dy++) fr.set(fw0 + leg[0], dy, lt0 + leg[1], Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y));
        }
        fr.fill(fw0 - 1, 4, lt0 - 1, fw0 + 1, 4, lt0 + 1, Blocks.SPRUCE_PLANKS);
        for (int[] rail : new int[][]{{-1, -1}, {-1, 0}, {-1, 1}, {1, -1}, {1, 1}, {0, -1}, {0, 1}}) fr.set(fw0 + rail[0], 5, lt0 + rail[1], Blocks.SPRUCE_FENCE);
        Towers.ladder(fr, fw0 + 2, lt0, 0, 4, Direction.NORTH);
    }

    // ------------------------------------------------------------------ Forsworn

    private static void forsworn(Frame fr, DungeonPiece c) {
        ground(fr, 11, 830);
        // hide huts: a frame of poles under brown and white hides
        for (int[] hut : new int[][]{{-5, -5}, {-5, 5}, {-8, 0}}) hut(fr, hut[0], hut[1]);
        fire(fr, 1, 0);
        // bone totems with antlers at the entrance
        for (int lt : new int[]{-3, 3}) {
            for (int dy = 0; dy <= 2; dy++) fr.set(9, dy, lt, dy == 2 ? Blocks.BONE_BLOCK.defaultBlockState() : Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            fr.set(9, 3, lt, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(fr.f)));
            fr.set(9, 2, lt + (lt < 0 ? -1 : 1), Blocks.DEAD_BUSH);
        }
        // the hagraven's altar: a stone slab with bones, feathers and a cauldron
        fr.set(-2, 0, 7, Blocks.CHISELED_STONE_BRICKS);
        fr.set(-2, 1, 7, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(fr.r.getOpposite())));
        fr.set(-1, 0, 7, Blocks.CAULDRON);
        fr.set(-3, 0, 7, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.BROWN_CANDLE, 3, true));
        fr.set(-2, 0, 8, com.nirncraft.worldgen.VanillaBlocks.WHITE_CARPET);
        lookout(fr, 4, -8);
        fr.container(-8, 0, 2, Deco.chest(fr.f), Deco.LOOT_COMMON);
        fr.container(-2, 0, 6, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
        String named = Painter.entityExists("nirncraft:forsworn") ? null : "Forsworn";
        fr.spawn(Mobs.forsworn(), 2, 0, 2, fr.f, named);
        fr.spawn(Mobs.forsworn(), 0, 0, -3, fr.r, named);
        fr.spawn(Mobs.forsworn(), 4, 5, -8, fr.f, named);
        fr.spawn(Mobs.forsworn(), -6, 0, 1, fr.f, "Forsworn Briarheart");
        if (fr.chance(0, 0, 831, 2)) fr.spawn(Mobs.hagraven(), -3, 0, 6, fr.f, Painter.entityExists("nirncraft:hagraven") ? null : "Hagraven");
    }

    /** A Forsworn hide hut: four leaning poles, hide walls, a pelt floor. */
    private static void hut(Frame fr, int fw0, int lt0) {
        for (int dfw = -2; dfw <= 2; dfw++) {
            for (int dlt = -2; dlt <= 2; dlt++) {
                int d = Math.max(Math.abs(dfw), Math.abs(dlt));
                boolean door = dfw == 2 && dlt == 0;
                if (d == 2 && !door) {
                    fr.set(fw0 + dfw, 0, lt0 + dlt, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL);
                    fr.set(fw0 + dfw, 1, lt0 + dlt, (dfw + dlt) % 2 == 0 ? com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState());
                } else if (d == 1) {
                    fr.set(fw0 + dfw, 2, lt0 + dlt, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL);
                }
                if (d <= 1) fr.set(fw0 + dfw, 0, lt0 + dlt, com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET);
            }
        }
        fr.set(fw0, 3, lt0, Blocks.SPRUCE_FENCE);
        fr.set(fw0, 4, lt0, Blocks.SPRUCE_FENCE);
    }

    // ------------------------------------------------------------------ military camp

    private static void military(Frame fr, DungeonPiece c) {
        boolean imperial = c.flavor % 2 == 0;
        BlockState cloth = (imperial ? com.nirncraft.worldgen.VanillaBlocks.RED_WOOL : com.nirncraft.worldgen.VanillaBlocks.BLUE_WOOL).defaultBlockState();
        BlockState canvas = com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState();
        BlockState banner = (imperial ? com.nirncraft.worldgen.VanillaBlocks.RED_BANNER : com.nirncraft.worldgen.VanillaBlocks.BLUE_BANNER).defaultBlockState();
        ground(fr, 15, 840);
        // the command tent at the back: a big pavilion with the war table
        fr.columns(-12, -4, -6, 4, (fw, lt, x, z) -> {
            boolean edge = fw == -12 || fw == -6 || Math.abs(lt) == 4;
            boolean door = fw == -6 && Math.abs(lt) <= 1;
            int roof = 4 - Math.abs(lt) / 2;
            for (int dy = 0; dy <= roof; dy++) {
                if (dy == roof) fr.p.set(x, fr.base + dy, z, Math.abs(lt) % 2 == 0 ? cloth : canvas);
                else if (edge && !(door && dy <= 2)) fr.p.set(x, fr.base + dy, z, dy == 0 ? Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y) : canvas);
            }
        });
        // the war table with its map, and the commander
        fr.set(-9, 0, -1, Blocks.SPRUCE_FENCE);
        fr.set(-9, 0, 1, Blocks.SPRUCE_FENCE);
        fr.set(-9, 1, -1, Blocks.CARTOGRAPHY_TABLE);
        fr.set(-9, 1, 0, Deco.slab(Blocks.SPRUCE_SLAB, false));
        fr.set(-9, 1, 1, Deco.slab(Blocks.SPRUCE_SLAB, false));
        fr.set(-9, 0, 0, Blocks.SPRUCE_FENCE);
        fr.container(-11, 0, 3, Deco.chest(fr.f), Deco.LOOT_COMMON);
        fr.set(-11, 0, -3, Blocks.LECTERN);
        // rows of tents
        for (int fw : new int[]{-1, 4}) {
            Towers.tent(fr, fw, -9, canvas);
            Towers.tent(fr, fw, 9, canvas);
        }
        fire(fr, 3, 0);
        // the smithy and stores
        fr.set(8, 0, -5, Blocks.ANVIL);
        fr.set(8, 0, -6, Blocks.GRINDSTONE);
        fr.set(9, 0, -5, Blocks.BLAST_FURNACE);
        fr.set(8, 0, 6, Blocks.BARREL);
        fr.set(9, 0, 6, Deco.pillar(Blocks.HAY_BLOCK, Direction.Axis.Y));
        fr.container(9, 0, 5, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
        // banners at the gate and the weapon racks
        for (int lt : new int[]{-3, 3}) {
            fr.set(13, 0, lt, Blocks.SPRUCE_FENCE);
            fr.set(13, 1, lt, Blocks.SPRUCE_FENCE);
            fr.set(13, 2, lt, banner);
            fr.set(12, 0, lt * 2, Blocks.SPRUCE_FENCE);
            fr.set(12, 1, lt * 2, Deco.lantern(false, false));
        }
        String soldier = Mobs.soldier();
        String name = imperial ? "Imperial Soldier" : "Stormcloak Soldier";
        fr.spawn(soldier, 1, 0, 3, fr.f, name);
        fr.spawn(soldier, 6, 0, -2, fr.r, name);
        fr.spawn(soldier, 12, 0, 0, fr.f, name);
        fr.spawn(soldier, -8, 0, 0, fr.f, imperial ? "Legate" : "Stormcloak Officer");
    }

    // ------------------------------------------------------------------ cabins and shacks

    private static void cabin(Frame fr, DungeonPiece c) {
        ground(fr, 7, 850);
        shed(fr, 0, 0, false);
        // a woodpile and a chopping block outside
        fr.set(4, 0, 3, Deco.pillar(Blocks.SPRUCE_LOG, fr.r.getAxis()));
        fr.set(4, 0, 4, Deco.pillar(Blocks.SPRUCE_LOG, fr.r.getAxis()));
        fr.set(4, 1, 3, Deco.pillar(Blocks.SPRUCE_LOG, fr.r.getAxis()));
        fr.set(5, 0, -3, Blocks.STRIPPED_OAK_LOG);
        // whoever lives here now: a hunter's trophies, or squatters
        switch (Math.floorMod(c.flavor, 4)) {
            case 0 -> fr.spawn(Mobs.bandit(), 1, 0, 0, fr.f, null);
            case 1 -> {
                fr.set(-2, 0, -2, Blocks.BREWING_STAND);
                fr.container(-2, 0, 2, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
            }
            case 2 -> fr.spawn(Mobs.vampire(), 1, 0, 0, fr.f, null);
            default -> fr.set(-2, 1, 0, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(fr.f)));
        }
    }

    /**
     * A small timber shack: log corners, plank walls, a gabled roof, a bed, a table and a chest. Centred at (fw0, lt0),
     * door to the front.
     */
    static void shed(Frame fr, int fw0, int lt0, boolean small) {
        int hf = small ? 2 : 3, hl = small ? 2 : 4;
        fr.columns(fw0 - hf, lt0 - hl, fw0 + hf, lt0 + hl, (fw, lt, x, z) -> {
            int a = fw - fw0, b = lt - lt0;
            boolean edge = Math.abs(a) == hf || Math.abs(b) == hl;
            boolean corner = Math.abs(a) == hf && Math.abs(b) == hl;
            boolean door = a == hf && b == 0;
            boolean window = Math.abs(b) == hl && a == 0;
            fr.p.set(x, fr.base - 1, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
            for (int dy = 0; dy <= 3; dy++) {
                BlockState s;
                if (!edge) s = Blocks.AIR.defaultBlockState();
                else if (corner) s = Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y);
                else if (door && dy <= 1) s = Blocks.AIR.defaultBlockState();
                else if (window && dy == 1) s = Blocks.GLASS_PANE.defaultBlockState();
                else s = dy == 3 ? Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, Math.abs(a) == hf ? fr.r.getAxis() : fr.f.getAxis())
                            : Blocks.SPRUCE_PLANKS.defaultBlockState();
                fr.p.set(x, fr.base + dy, z, s);
            }
        });
        fr.gableRoof(fw0 - hf - 1, lt0 - hl - 1, fw0 + hf + 1, lt0 + hl + 1, 4, true, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB,
                Blocks.SPRUCE_PLANKS.defaultBlockState());
        fr.set(fw0 - hf + 1, 0, lt0 - hl + 1, com.nirncraft.worldgen.VanillaBlocks.RED_CARPET);
        fr.set(fw0 - hf + 1, 0, lt0 - hl + 2, com.nirncraft.worldgen.VanillaBlocks.RED_CARPET);
        fr.container(fw0 - hf + 1, 0, lt0 + hl - 1, Deco.chest(fr.f), Deco.LOOT_MINOR);
        if (!small) {
            fr.set(fw0, 0, lt0 + hl - 1, Blocks.SPRUCE_FENCE);
            fr.set(fw0, 1, lt0 + hl - 1, Deco.slab(Blocks.SPRUCE_SLAB, false));
            fr.set(fw0 - 1, 0, lt0, Deco.campfire(false, false));
            fr.set(fw0, 2, lt0, Deco.lantern(false, true));
        }
    }
}
