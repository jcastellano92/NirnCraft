package com.skycraft.landmarks.build;

import com.skycraft.landmarks.Lore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Tier M: scenes with a story, 8 to 12 blocks across: a pilgrim at a wayside shrine, a hermit and her goats, hunters
 * dressing a kill, two travellers at a fire, a smuggler's drop, a ghost-story ruin, an ambush, a skirmish, a boat
 * cast ashore, a sacrificial stone, a hunting blind, a prospector's claim, a dolmen, a scholar's dig. Residents
 * named in the kind's {@code cast} are spawned at runtime, the first time a player comes near.
 */
final class Scenes {
    private Scenes() {}

    // ------------------------------------------------------------------ helpers

    /** Levels a small disc to the site's base and clears the plants above it. */
    private static void flat(Stage s, int radius) {
        BlockState fill = s.sandy ? Blocks.SANDSTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        s.b.level(radius, null, fill, 5);
    }

    private static ItemStack note(Stage s, String category, String title) {
        RandomSource r = RandomSource.create(s.seed ^ 0x3C6EF372L);
        return Lore.book(title, Lore.person(s.site.province(), r), s.story(category));
    }

    private static BlockState log(Stage s, Direction.Axis axis) {
        return s.wood.log().defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
    }

    private static BlockState slab(Block b, SlabType t) {
        return b.defaultBlockState().setValue(SlabBlock.TYPE, t);
    }

    private static void fireWithSeats(Stage s, int cx, int cz, boolean lit) {
        int g = 0;
        s.set(cx, g, cz, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, lit).setValue(CampfireBlock.SIGNAL_FIRE, lit));
        Vignettes.ring(s, cx, cz, 1, true);
        s.set(cx, g, cz, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, lit));
        s.set(cx - 2, g, cz, log(s, Direction.Axis.Z));
        s.set(cx + 2, g, cz, log(s, Direction.Axis.Z));
    }

    // ------------------------------------------------------------------ pilgrim at a shrine

    static boolean pilgrim(Stage s) {
        flat(s, 5);
        int g = 0;
        String style = s.kind.style();
        // a little stepped platform
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 5) continue;
                s.set(dx, g - 1, dz, s.cutStone ? s.b.pal.brick().defaultBlockState() : s.rk(dx, g - 1, dz));
            }
        }
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
            s.set(dx, g - 1, dz, s.b.pal.stone().defaultBlockState());
        }
        switch (style) {
            case "grove" -> {
                for (int[] p : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
                    for (int y = 0; y < 4; y++) s.set(p[0], g + y, p[1], s.wood.log());
                    s.lantern(p[0], g + 4, p[1], s.chance(p[0] + 4, 0.3));
                }
                s.set(0, g, 2, s.rk(0, g, 2));
                s.set(0, g + 1, 2, Blocks.CHISELED_STONE_BRICKS);
                s.set(0, g + 2, 2, s.rk(0, g + 2, 2));
            }
            case "pool" -> {
                for (int dx = -1; dx <= 1; dx++) s.set(dx, g - 1, 3, Blocks.WATER);
                for (int dx = -2; dx <= 2; dx++) s.set(dx, g, 4, s.rk(dx, g, 4));
                s.set(0, g, 2, s.rk(0, g, 2));
                s.set(0, g + 1, 2, s.rk(0, g + 1, 2));
                s.set(0, g + 2, 2, Blocks.CHISELED_STONE_BRICKS);
            }
            default -> { // stone: a pillar with the god's banner
                for (int y = 0; y < 4; y++) s.set(0, g + y, 2, y == 3 ? s.b.pal.brick().defaultBlockState() : s.rk(0, g + y, 2));
                s.b.banner(0, g + 4, 2, s.b.dye(net.minecraft.world.item.DyeColor.WHITE));
                s.set(-1, g, 2, s.rk(-1, g, 2));
                s.set(1, g, 2, s.rk(1, g, 2));
            }
        }
        // the pilgrim's mat, candles, offerings
        s.set(0, g, 0, Blocks.RED_CARPET);
        Vignettes.candle(s, -2, g, 1, 3, true);
        Vignettes.candle(s, 2, g, 1, 3, true);
        s.lying(-1, g, -1, Vignettes.offering(s, 2));
        s.lying(1, g, -1, Vignettes.offering(s, 3));
        s.lectern(-2, g, -1, Direction.EAST, note(s, "pilgrim", "A Pilgrim's Note"));
        s.set(3, g, -3, s.chance(4, 0.5) ? Blocks.FERN : Blocks.AIR);
        s.dress(-4, g, 2);
        return true;
    }

    // ------------------------------------------------------------------ hermit with goats

    static boolean hermit(Stage s) {
        flat(s, 6);
        int g = 0;
        // hut: 4x3, log corners, plank walls, slab roof
        for (int dx = -2; dx <= 1; dx++) {
            for (int dz = 0; dz <= 3; dz++) {
                boolean corner = (dx == -2 || dx == 1) && (dz == 0 || dz == 3);
                boolean wall = dx == -2 || dx == 1 || dz == 0 || dz == 3;
                s.set(dx, g - 1, dz, s.wood.planks());
                if (!wall) continue;
                for (int y = 0; y < 3; y++) s.set(dx, g + y, dz, corner ? s.wood.log().defaultBlockState() : s.wood.planks().defaultBlockState());
            }
        }
        s.air(0, g, 0);
        s.air(0, g + 1, 0); // doorway facing -z
        s.set(-1, g + 1, 0, Blocks.GLASS_PANE);
        for (int dx = -3; dx <= 2; dx++) for (int dz = -1; dz <= 4; dz++) {
            s.set(dx, g + 3, dz, slab(s.wood.slab(), SlabType.BOTTOM));
        }
        Vignettes.bed(s, -1, g, 2, Direction.SOUTH, Vignettes.bedFor(s));
        s.set(0, g, 3, Blocks.BREWING_STAND);
        s.set(1, g, 1, Blocks.BARREL);
        s.set(0, g, 2, Blocks.CAULDRON);
        s.lantern(0, g + 2, 2, false);
        BlockPos shelf = s.barrel(1, g, 2, s.lootSmall(1));
        s.put(shelf, note(s, "hermit", "Hermit's Rule"));
        // a herb patch and the goat pen
        for (int i = 0; i < 6; i++) {
            int x = 3 + i % 3, z = -2 + i / 3;
            s.set(x, g - 1, z, Blocks.FARMLAND);
            s.set(x, g, z, i % 2 == 0 ? Blocks.FERN : Vignettes.flower(s, i));
        }
        for (int x = -5; x <= -2; x++) for (int z = 4; z <= 7; z++) {
            boolean edge = x == -5 || x == -2 || z == 4 || z == 7;
            if (edge && !(x == -3 && z == 4)) s.set(x, g, z, s.wood.fence());
        }
        s.set(-4, g, 6, Blocks.HAY_BLOCK);
        s.set(-3, g, 5, Blocks.HAY_BLOCK);
        s.dress(4, g, 4);
        return true;
    }

    // ------------------------------------------------------------------ hunters dressing a kill

    static boolean hunters(Stage s) {
        flat(s, 6);
        int g = 0;
        fireWithSeats(s, 3, 0, true);
        // a tripod over the carcass
        for (int[] p : new int[][]{{-3, -1}, {-1, -1}, {-2, 1}}) {
            for (int y = 0; y < 3; y++) s.set(p[0], g + y, p[1], s.wood.fence());
        }
        s.set(-2, g + 3, -1, s.wood.fence());
        s.set(-3, g + 3, -1, s.wood.fence());
        s.set(-1, g + 3, -1, s.wood.fence());
        s.set(-2, g + 2, -1, Blocks.CHAIN);
        s.set(-2, g + 1, -1, s.chance(1, 0.5) ? Blocks.RED_WOOL : Blocks.BROWN_WOOL);
        s.set(-2, g, -1, s.chance(2, 0.5) ? Blocks.RED_WOOL : Blocks.BONE_BLOCK);
        // drying rack with stretched hides
        for (int x = 1; x <= 4; x++) {
            s.set(x, g, -4, s.wood.fence());
            s.set(x, g + 1, -4, Blocks.AIR);
        }
        s.set(1, g + 1, -4, s.wood.fence());
        s.set(4, g + 1, -4, s.wood.fence());
        s.set(2, g + 1, -4, Blocks.BROWN_CARPET);
        s.set(3, g + 1, -4, Blocks.WHITE_CARPET);
        s.set(1, g + 2, -4, s.wood.fence());
        s.set(4, g + 2, -4, s.wood.fence());
        s.set(2, g + 2, -4, s.wood.fence());
        s.set(3, g + 2, -4, s.wood.fence());
        s.set(0, g, 2, s.wood.log().defaultBlockState());
        BlockPos pack = s.barrel(-4, g, 2, "explore/hunter");
        s.put(pack, note(s, "hunter", "Hunter's Tally"));
        s.lying(-3, g, 3, new ItemStack(Items.BONE));
        s.set(1, g, 3, Blocks.CAULDRON);
        s.dress(5, g, 3);
        return true;
    }

    // ------------------------------------------------------------------ travellers resting

    static boolean travellers(Stage s) {
        flat(s, 5);
        int g = 0;
        fireWithSeats(s, 0, 0, true);
        s.set(0, g + 1, 0, Blocks.AIR);
        // packs and a lean for the night
        BlockPos p1 = s.barrel(-3, g, 2, "explore/adventurer");
        s.put(p1, note(s, "traveller", "Travel Diary"));
        s.barrel(3, g, 2, null);
        s.set(-3, g + 1, 2, Blocks.BROWN_CARPET);
        s.set(2, g, -3, slab(s.wood.slab(), SlabType.BOTTOM));
        s.set(3, g, -3, slab(s.wood.slab(), SlabType.BOTTOM));
        s.set(4, g, 0, s.wood.fence());
        s.set(4, g + 1, 0, s.wood.fence());
        s.set(4, g + 2, 0, s.wood.fence());
        s.lantern(4, g + 3, 0, false);
        if (s.chance(3, 0.6)) Vignettes.bed(s, -4, g, -2, Direction.EAST, Vignettes.bedFor(s));
        s.dress(3, g, 4);
        return true;
    }

    // ------------------------------------------------------------------ smuggler's drop

    static boolean smuggler(Stage s) {
        int g = s.top(0, 0);
        // a boulder with a hollow at its back; the cache sits in the hollow, the marks outside
        s.blob(0, g + 2, 2, 5.0, 3.2, 4.0, 0.25, 1);
        for (int dx = -1; dx <= 1; dx++) for (int dz = 0; dz <= 2; dz++) for (int dy = 0; dy <= 1; dy++) s.air(dx, g + dy, dz + 1);
        s.set(0, g - 1, 2, s.rubble(0, g, 2));
        s.set(0, g, 2, Blocks.AIR);
        s.chest(0, g, 2, Direction.NORTH, "explore/cache");
        BlockPos crate = s.barrel(-1, g, 2, "explore/camp");
        s.put(crate, note(s, "smuggler", "Drop Orders"));
        s.barrel(1, g, 2, null);
        s.lantern(0, g + 1, 3, true);
        // the marks: a stone on the post, a cloth on a stick
        s.set(-3, g, -2, s.wood.fence());
        s.set(-3, g + 1, -2, s.wood.fence());
        s.set(-3, g + 2, -2, Blocks.BLACK_WOOL);
        s.set(3, g, -2, s.rubble(3, g, -2));
        s.set(3, g + 1, -2, s.rubble(3, g, -2));
        s.rubbleRing(0, 0, 6, 6);
        return true;
    }

    // ------------------------------------------------------------------ ghost-story ruin

    static boolean ghostRuin(Stage s) {
        flat(s, 7);
        int g = 0;
        String style = s.kind.style();
        Block wall = style.equals("timber") ? s.wood.planks() : style.equals("mud") ? Blocks.MUD_BRICKS : s.b.pal.brick();
        int w = 5 + s.vary(1, 0, 2), d = 4 + s.vary(2, 0, 2);
        for (int dx = -w / 2; dx <= w / 2; dx++) {
            for (int dz = -d / 2; dz <= d / 2; dz++) {
                s.set(dx, g - 1, dz, s.b.pal.path().defaultBlockState());
                boolean edge = dx == -w / 2 || dx == w / 2 || dz == -d / 2 || dz == d / 2;
                if (!edge) continue;
                int h = 1 + (int) (s.n(dx, 0, dz, 3.0, 4) * 3);
                if (dx == 0 && dz == -d / 2) h = 0;
                for (int y = 0; y < h; y++) s.set(dx, g + y, dz, s.chance(dx * 9 + dz, 0.2) ? s.b.pal.mossy().defaultBlockState() : wall.defaultBlockState());
            }
        }
        // a cold hearth, a table that is always set, soul light
        s.set(-w / 2 + 1, g, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        s.set(1, g, 0, s.wood.fence());
        s.set(1, g + 1, 0, slab(s.wood.slab(), SlabType.BOTTOM));
        s.set(2, g, 0, s.wood.fence());
        s.set(2, g + 1, 0, slab(s.wood.slab(), SlabType.BOTTOM));
        Vignettes.candle(s, 1, g + 2, 0, 1, true);
        s.lantern(w / 2 - 1, g, d / 2 - 1, true);
        s.lectern(0, g, d / 2 - 1, Direction.NORTH, note(s, "ghost", "A Ghost Story"));
        s.set(-1, g, d / 2 - 1, Blocks.COBWEB);
        s.set(w / 2 - 1, g, -d / 2 + 1, Blocks.COBWEB);
        Vignettes.bed(s, -w / 2 + 1, g, d / 2 - 1, Direction.EAST, Vignettes.bedFor(s));
        s.set(-w / 2 + 1, g + 1, d / 2 - 1, Blocks.SKELETON_SKULL);
        s.rubbleRing(0, 0, 6, 8);
        s.dress(0, g, -d / 2 - 1);
        return true;
    }

    // ------------------------------------------------------------------ ambush

    static boolean ambush(Stage s) {
        flat(s, 6);
        int g = 0;
        // a cart on its side: bed, one wheel, a snapped shaft
        for (int dx = -2; dx <= 2; dx++) for (int dz = 0; dz <= 2; dz++) {
            if (s.chance(dx * 5 + dz, 0.12)) continue;
            s.set(dx, g, dz, s.wood.planks());
        }
        for (int dx = -2; dx <= 2; dx += 4) { s.set(dx, g + 1, 0, s.wood.fence()); s.set(dx, g + 1, 2, s.wood.fence()); }
        for (int dx = -1; dx <= 1; dx++) s.set(dx, g + 1, 0, slab(s.wood.slab(), SlabType.BOTTOM));
        s.set(3, g, 1, s.wood.fence());
        s.set(4, g, 1, s.wood.fence());
        s.set(-3, g, 1, s.wood.fence());
        s.set(-3, g + 1, 1, s.wood.fence());
        s.set(-3, g, 2, s.wood.fence());
        // cargo, spilled
        BlockPos crate = s.chest(0, g + 1, 1, Direction.SOUTH, s.chance(8, 0.5) ? "explore/adventurer" : "explore/camp");
        s.put(crate, note(s, "ambush", "Last Page"));
        s.set(-1, g + 1, 2, Blocks.BARREL);
        s.set(3, g, -1, Blocks.BARREL);
        s.set(1, g, -2, Blocks.HAY_BLOCK);
        s.set(-2, g, -2, Blocks.WHITE_WOOL);
        // the dead: bones and gear
        s.set(2, g, -3, Blocks.SKELETON_SKULL);
        s.set(1, g, -3, Blocks.BONE_BLOCK);
        s.set(-3, g, -3, Blocks.SKELETON_SKULL);
        s.set(-4, g, -3, Blocks.BONE_BLOCK);
        s.b.standWith(4, -3, 90f, new ItemStack(Items.IRON_HELMET), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, new ItemStack(Items.IRON_SWORD));
        s.b.scatter(5, 5, Blocks.BONE_BLOCK.defaultBlockState(), false);
        s.lying(0, g, -2, new ItemStack(Items.ARROW));
        s.lying(-2, g, -4, new ItemStack(Items.ARROW));
        return true;
    }

    // ------------------------------------------------------------------ skirmish

    static boolean skirmish(Stage s) {
        flat(s, 8);
        int g = 0;
        net.minecraft.world.item.Item[] heads = {Items.IRON_HELMET, Items.CHAINMAIL_HELMET, Items.LEATHER_HELMET, Items.GOLDEN_HELMET};
        net.minecraft.world.item.Item[] chests = {Items.IRON_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.LEATHER_CHESTPLATE, Items.AIR};
        net.minecraft.world.item.Item[] arms = {Items.IRON_SWORD, Items.STONE_AXE, Items.BOW, Items.WOODEN_SWORD};
        for (int i = 0; i < 5; i++) {
            int x = s.vary(i, -5, 5), z = s.vary(10 + i, -5, 5);
            s.b.standWith(x, z, s.vary(20 + i, 0, 359),
                    new ItemStack(heads[Math.floorMod(s.vary(30 + i, 0, 99), heads.length)]),
                    new ItemStack(chests[Math.floorMod(s.vary(40 + i, 0, 99), chests.length)]),
                    ItemStack.EMPTY, ItemStack.EMPTY, new ItemStack(arms[Math.floorMod(s.vary(50 + i, 0, 99), arms.length)]));
            s.set(x + 1, g, z, Blocks.BONE_BLOCK);
        }
        // a broken standard and scorched ground
        s.set(0, g, 0, s.wood.fence());
        s.set(0, g + 1, 0, s.wood.fence());
        s.set(0, g + 2, 0, s.wood.fence());
        s.b.banner(0, g + 3, 0, s.b.dye(net.minecraft.world.item.DyeColor.RED));
        s.set(1, g + 2, 0, Blocks.AIR);
        for (int i = 0; i < 12; i++) {
            int x = s.vary(60 + i, -6, 6), z = s.vary(80 + i, -6, 6);
            if (s.dry(x, z)) s.set(x, s.top(x, z) - 1, z, s.chance(i, 0.5) ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.GRAVEL.defaultBlockState());
        }
        s.chest(2, g, 2, Direction.WEST, "explore/battlefield");
        s.b.scatter(7, 8, Blocks.BONE_BLOCK.defaultBlockState(), false);
        s.rubbleRing(0, 0, 7, 6);
        return true;
    }

    // ------------------------------------------------------------------ boat cast ashore

    static boolean boatWreck(Stage s) {
        int g = s.top(0, 0);
        int len = 7 + s.vary(1, 0, 2);
        for (int i = -len / 2; i <= len / 2; i++) {
            double half = 2.0 * Math.sqrt(Math.max(0, 1 - Math.pow(i / (len / 2.0 + 0.5), 2)));
            int hw = (int) Math.round(half);
            for (int lt = -hw; lt <= hw; lt++) {
                s.set(i, g, lt, s.wood.planks());
                if (Math.abs(lt) == hw && !(s.chance(i * 7 + lt, 0.25))) s.set(i, g + 1, lt, s.wood.planks());
                if (Math.abs(lt) == hw && Math.abs(i) < 2 && s.chance(5, 0.5)) s.set(i, g + 2, lt, slab(s.wood.slab(), SlabType.BOTTOM));
            }
        }
        for (int y = 1; y <= 4; y++) s.set(1, g + y, 0, s.wood.fence());
        s.set(1, g + 3, 1, Blocks.WHITE_WOOL);
        s.set(1, g + 2, -1, Blocks.WHITE_CARPET);
        BlockPos b = s.barrel(-2, g + 1, 0, "explore/shipwreck");
        s.put(b, note(s, "traveller", "Ship's Log"));
        s.barrel(2, g + 1, 0, null);
        s.rubbleRing(0, 0, 6, 5);
        s.set(-len / 2 - 1, g, 1, s.wood.fence());
        s.set(len / 2 + 1, g, -1, s.wood.fence());
        return true;
    }

    // ------------------------------------------------------------------ sacrificial stone

    static boolean sacrifice(Stage s) {
        flat(s, 6);
        int g = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > 3) continue;
            s.set(dx, g - 1, dz, s.chance(dx * 7 + dz, 0.3) ? Blocks.RED_TERRACOTTA.defaultBlockState() : s.rk(dx, g - 1, dz));
        }
        for (int dx = -1; dx <= 1; dx++) s.set(dx, g, 0, s.rk(dx, g, 0));
        s.set(0, g + 1, 0, s.chance(1, 0.5) ? Blocks.RED_CARPET : Blocks.BLACK_CARPET);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int x = (int) Math.round(Math.cos(a) * 4), z = (int) Math.round(Math.sin(a) * 4);
            if (!s.within(x, z)) continue;
            if (i % 2 == 0) {
                s.set(x, g, z, Blocks.BONE_BLOCK);
                s.set(x, g + 1, z, Blocks.SKELETON_SKULL);
            } else {
                Vignettes.candle(s, x, g, z, 1 + (i % 3), true);
            }
        }
        s.set(0, g, 2, Blocks.SOUL_CAMPFIRE.defaultBlockState());
        s.lectern(-3, g, -3, Direction.SOUTH, note(s, "scholar", "Ritual Notes"));
        return true;
    }

    // ------------------------------------------------------------------ hunting blind

    static boolean blind(Stage s) {
        flat(s, 4);
        int g = 0;
        for (int[] p : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) for (int y = 0; y < 4; y++) s.set(p[0], g + y, p[1], s.wood.log());
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            s.set(dx, g + 3, dz, s.wood.planks());
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                s.set(dx, g + 4, dz, s.wood.fence());
            }
        }
        s.air(0, g + 3, -2);
        for (int y = 0; y <= 3; y++) {
            s.set(0, g + y, -3, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        }
        s.set(0, g + 3, -3, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        s.chest(1, g + 4, 1, Direction.NORTH, "explore/hunter");
        s.set(-1, g + 4, 1, Blocks.HAY_BLOCK);
        for (int dx = -3; dx <= 3; dx++) s.set(dx, g + 5, -3 + 0, Blocks.AIR);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) s.set(dx, g + 5, dz, slab(s.wood.slab(), SlabType.BOTTOM));
        }
        s.dress(3, g, 3);
        return true;
    }

    // ------------------------------------------------------------------ prospector's claim

    static boolean claim(Stage s) {
        int g = s.top(0, 0);
        s.blob(0, g + 1, 1, 4.0, 2.6, 3.0, 0.3, 1);
        Block[] ores = {Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.COAL_ORE, Blocks.GOLD_ORE, Blocks.RAW_IRON_BLOCK};
        if (s.ashy) ores = new Block[]{Blocks.GOLD_ORE, Blocks.COPPER_ORE, Blocks.RAW_COPPER_BLOCK, Blocks.IRON_ORE};
        for (int i = 0; i < 9; i++) {
            int x = s.vary(i, -3, 3), z = s.vary(10 + i, -1, 3), y = g + s.vary(20 + i, 0, 2);
            if (s.solidAt(x, y, z)) s.set(x, y, z, ores[Math.floorMod(s.vary(30 + i, 0, 99), ores.length)]);
        }
        s.set(0, g, -2, s.wood.fence());
        s.set(0, g + 1, -2, s.wood.fence());
        s.standingSign(1, g, -3, 8, "CLAIM", s.graveLines()[1], "PROSPECTOR", "KEEP OFF");
        s.barrel(-2, g, -3, "explore/miner");
        s.lantern(-1, g, -3, false);
        s.set(2, g, -2, Blocks.RAIL);
        s.set(3, g, -2, Blocks.RAIL);
        s.rubbleRing(0, 0, 5, 6);
        return true;
    }

    // ------------------------------------------------------------------ dolmen

    static boolean dolmen(Stage s) {
        int g = s.top(0, 0);
        int h = 4 + s.vary(1, 0, 2);
        int[][] posts = {{-2, 0}, {2, 0}, {0, 3}};
        for (int[] p : posts) {
            int x = p[0], z = p[1];
            int lean = s.vary(x * 3 + z + 20, 0, 1);
            for (int y = 0; y < h; y++) s.set(x, s.top(x, z) + y, z, s.rk(x, y, z));
            if (lean == 1) s.set(x, s.top(x, z) + h, z, s.rubble(x, h, z));
        }
        // capstone across the first two
        for (int x = -3; x <= 3; x++) for (int z = -1; z <= 1; z++) {
            if (Math.abs(x) == 3 && Math.abs(z) == 1) continue;
            s.set(x, g + h, z, s.rk(x, g + h, z));
        }
        // the altar table and its candles
        s.set(0, g, 1, s.rk(0, g, 1));
        Vignettes.candle(s, 0, g + 1, 1, 3, true);
        s.lying(-1, g, 1, Vignettes.offering(s, 1));
        s.rubbleRing(0, 1, 5, 8);
        if (s.lush) for (int i = 0; i < 6; i++) s.dress(s.vary(i, -4, 4), g, s.vary(10 + i, -2, 5));
        return true;
    }

    // ------------------------------------------------------------------ scholar's dig

    static boolean dig(Stage s) {
        flat(s, 7);
        int g = 0;
        // three trenches, roped off
        for (int t = 0; t < 3; t++) {
            int x0 = -4 + t * 3, z0 = -3;
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 4; dz++) {
                s.set(x0 + dx, g - 1, z0 + dz, Blocks.AIR);
                s.set(x0 + dx, g - 2, z0 + dz, t == 1 && dz == 2 ? Blocks.BONE_BLOCK.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
            }
            s.set(x0 - 1, g, z0 - 1, s.wood.fence());
            s.set(x0 + 2, g, z0 - 1, s.wood.fence());
            s.set(x0 - 1, g, z0 + 4, s.wood.fence());
            s.set(x0 + 2, g, z0 + 4, s.wood.fence());
        }
        // an A-frame tent and a work table
        for (int dz = 2; dz <= 4; dz++) {
            s.set(-2, g, dz + 3, Blocks.WHITE_WOOL);
            s.set(2, g, dz + 3, Blocks.WHITE_WOOL);
            s.set(-1, g + 1, dz + 3, Blocks.WHITE_WOOL);
            s.set(1, g + 1, dz + 3, Blocks.WHITE_WOOL);
            s.set(0, g + 2, dz + 3, Blocks.WHITE_WOOL);
        }
        s.set(0, g, 6, Blocks.AIR);
        BlockPos b = s.chest(0, g, 7, Direction.NORTH, "explore/mage");
        s.put(b, note(s, "scholar", "Dig Notes"));
        s.set(0, g, 8, Blocks.AIR);
        s.lectern(4, g, 4, Direction.WEST, note(s, "scholar", "Field Rubbing"));
        s.barrel(-4, g, 5, null);
        s.set(3, g, 2, Blocks.CAULDRON);
        return true;
    }

    // ------------------------------------------------------------------ pyre (beacon)

    static boolean beacon(Stage s) {
        flat(s, 4);
        int g = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                s.set(dx, g, dz, s.rk(dx, g, dz));
                if (s.chance(dx * 5 + dz, 0.4)) s.set(dx, g + 1, dz, s.rk(dx, g + 1, dz));
            } else {
                s.set(dx, g - 1, dz, s.b.pal.stone().defaultBlockState());
            }
        }
        // a stacked pyre, laid and ready, with a flint nearby
        for (int y = 0; y < 3; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                s.set(dx, g + y, 0, y % 2 == 0 ? log(s, Direction.Axis.X) : log(s, Direction.Axis.Z));
            }
        }
        s.set(0, g + 3, 0, Blocks.HAY_BLOCK);
        s.set(0, g + 4, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        s.lying(3, g, 0, new ItemStack(Items.FLINT_AND_STEEL));
        s.standingSign(0, g, -3, 8, "BEACON", "Light only for", "raiders or war", "");
        return true;
    }
}
