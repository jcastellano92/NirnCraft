package com.skycraft.landmarks.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Tier S: tiny human vignettes a few blocks across. Each one is a single idea (a grave with a name, a cairn with a
 * token, a cold bedroll, a lovers' tree...) so a walker passes it with a small pang and moves on.
 */
final class Vignettes {
    private Vignettes() {}

    // ------------------------------------------------------------------ helpers

    private static final Block[] FLOWERS_LUSH = {Blocks.POPPY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.LILY_OF_THE_VALLEY,
            Blocks.OXEYE_DAISY, Blocks.DANDELION, Blocks.ALLIUM, Blocks.BLUE_ORCHID};

    static Block flower(Stage s, int salt) {
        if (s.snowy) return Blocks.AZURE_BLUET;
        if (s.sandy || s.fam.equals("savanna")) return Blocks.DEAD_BUSH;
        return FLOWERS_LUSH[Math.floorMod(s.vary(salt, 0, 1000), FLOWERS_LUSH.length)];
    }

    /** Sets the surface block under the free block at (dx, dz) to {@code state} (a patch of bare earth). */
    static void patch(Stage s, int dx, int dz, BlockState state) {
        if (!s.dry(dx, dz)) return;
        s.set(dx, s.top(dx, dz) - 1, dz, state);
    }

    static BlockState earth(Stage s) {
        if (s.snowy) return Blocks.PACKED_ICE.defaultBlockState();
        if (s.sandy) return Blocks.COARSE_DIRT.defaultBlockState();
        if (s.wet) return Blocks.MUD.defaultBlockState();
        return s.chance(1, 0.5) ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.PODZOL.defaultBlockState();
    }

    static ItemStack token(Stage s, int salt) {
        net.minecraft.world.item.Item[] pool = {Items.FEATHER, Items.BONE, Items.FLINT, Items.ARROW, Items.GOLD_NUGGET,
                Items.COPPER_INGOT, Items.AMETHYST_SHARD, Items.STRING, Items.CLAY_BALL, Items.BREAD, Items.APPLE, Items.POPPY,
                Items.PAPER, Items.CHARCOAL, Items.IRON_NUGGET, Items.WHEAT};
        return new ItemStack(pool[Math.floorMod(s.vary(salt, 0, 4096), pool.length)]);
    }

    static ItemStack offering(Stage s, int salt) {
        net.minecraft.world.item.Item[] pool = {Items.BREAD, Items.APPLE, Items.WHEAT, Items.CARROT, Items.POPPY, Items.CORNFLOWER,
                Items.GOLD_NUGGET, Items.HONEY_BOTTLE, Items.COOKED_COD, Items.SWEET_BERRIES, Items.BONE, Items.EGG};
        return new ItemStack(pool[Math.floorMod(s.vary(salt, 0, 4096), pool.length)]);
    }

    static void candle(Stage s, int dx, int dy, int dz, int count, boolean lit) {
        // a candle needs something under it: over a gap it drops to the ground of its own column
        if (s.free(dx, dy - 1, dz)) {
            int y = s.top(dx, dz);
            if (y < dy) dy = y;
        }
        s.set(dx, dy, dz, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, count).setValue(CandleBlock.LIT, lit));
    }

    static void bed(Stage s, int dx, int dy, int dz, Direction head, Block bed) {
        BlockState foot = bed.defaultBlockState().setValue(BedBlock.FACING, head).setValue(BedBlock.PART, BedPart.FOOT);
        s.set(dx, dy, dz, foot);
        s.set(dx + head.getStepX(), dy, dz + head.getStepZ(), foot.setValue(BedBlock.PART, BedPart.HEAD));
    }

    static Block bedFor(Stage s) {
        Block[] beds = {Blocks.BROWN_BED, Blocks.GRAY_BED, Blocks.GREEN_BED, Blocks.RED_BED, Blocks.WHITE_BED, Blocks.BLUE_BED};
        return beds[Math.floorMod(s.vary(5, 0, 600), beds.length)];
    }

    static void ring(Stage s, int cx, int cz, int radius, boolean stoneOnly) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > radius * radius + 1 || d2 < (radius - 1) * (radius - 1) + (radius > 1 ? 0 : 1)) continue;
                int x = cx + dx, z = cz + dz;
                if (!s.dry(x, z)) continue;
                int y = s.top(x, z);
                s.set(x, y, z, s.rubble(x, y, z));
            }
        }
    }

    // ------------------------------------------------------------------ graves

    static boolean grave(Stage s) {
        String style = s.kind.style();
        String[] lines = s.graveLines();
        int g = s.top(0, 0);
        // the dug earth
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 0; dz++) if (style.equals("mound") || dx == 0) patch(s, dx, dz, earth(s));
        switch (style) {
            case "cross" -> {
                s.set(0, g, 1, s.wood.fence());
                s.set(0, g + 1, 1, s.wood.fence());
                s.set(0, g + 2, 1, s.wood.fence());
                s.set(-1, g + 1, 1, s.wood.fence());
                s.set(1, g + 1, 1, s.wood.fence());
                s.standingSign(0, g, -1, 8, lines);
                if (s.lush && s.chance(2, 0.7)) s.set(1, g, -1, flower(s, 3));
            }
            case "cairn" -> {
                for (int dx = -1; dx <= 1; dx++) for (int dz = 0; dz <= 2; dz++) s.set(dx, g, dz, s.rk(dx, g, dz));
                for (int dx = -1; dx <= 0; dx++) for (int dz = 1; dz <= 2; dz++) s.set(dx, g + 1, dz, s.rk(dx, g + 1, dz));
                s.set(0, g + 2, 1, s.rk(0, g + 2, 1));
                s.standingSign(0, g, -1, 8, lines);
            }
            case "slab" -> {
                for (int dx = -1; dx <= 1; dx++) for (int dz = 0; dz <= 2; dz++) s.set(dx, g - 1 + 1, dz, s.rk(dx, g, dz));
                s.set(0, g + 1, 2, s.rk(0, g + 1, 2));
                s.set(-1, g + 1, 2, s.rk(-1, g + 1, 2));
                s.standingSign(0, g, -1, 8, lines);
            }
            case "mound" -> {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -1; dz <= 2; dz++) {
                        if (Math.abs(dx) + Math.abs(dz - 0.5) > 3) continue;
                        if (!s.dry(dx, dz)) continue;
                        s.set(dx, s.top(dx, dz), dz, earth(s));
                    }
                }
                s.set(0, g + 1, 1, flower(s, 4));
                s.set(0, g + 1, 2, s.wood.fence());
                s.set(0, g + 2, 2, s.wood.fence());
                s.standingSign(0, g, -2, 8, lines);
            }
            case "stakes" -> {
                s.set(-1, g, 1, s.wood.fence());
                s.set(-1, g + 1, 1, s.wood.fence());
                s.set(1, g, 1, s.wood.fence());
                s.set(1, g + 1, 1, s.wood.fence());
                s.set(-1, g + 2, 1, Blocks.WHITE_WOOL);
                s.set(1, g + 2, 1, Blocks.WHITE_WOOL);
                s.set(0, g + 2, 1, s.chance(6, 0.5) ? Blocks.RED_CARPET : Blocks.WHITE_CARPET);
                s.standingSign(0, g, -1, 8, lines);
            }
            case "sunken" -> {
                for (int dx = -1; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) {
                    if (s.dry(dx, dz)) s.set(dx, s.top(dx, dz) - 1, dz, s.lush ? Blocks.MOSS_BLOCK.defaultBlockState() : s.rubble(dx, g, dz));
                }
                s.set(0, g, 1, s.rk(0, g, 1));
                s.set(1, g, 1, s.rk(1, g, 1));
                s.standingSign(-1, g, -1, 7, lines);
            }
            case "flowers" -> {
                s.set(0, g, 1, s.rk(0, g, 1));
                s.set(0, g + 1, 1, s.rk(0, g + 1, 1));
                for (int i = 0; i < 6; i++) {
                    int x = s.vary(10 + i, -2, 2), z = s.vary(20 + i, -2, 1);
                    if (s.dry(x, z) && s.free(x, s.top(x, z), z)) s.set(x, s.top(x, z), z, flower(s, 30 + i));
                }
                s.standingSign(1, g, -1, 9, lines);
            }
            default -> { // headstone
                s.set(0, g, 1, s.rk(0, g, 1));
                s.set(0, g + 1, 1, s.cutStone ? s.b.pal.brick().defaultBlockState() : s.rk(0, g + 1, 1));
                s.set(0, g + 2, 1, s.b.pal.slab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                s.wallSign(0, g + 1, 0, Direction.NORTH, lines);
                if (s.lush) s.set(1, g, 0, flower(s, 7));
            }
        }
        s.dress(-2, s.top(-2, 1), 1);
        return true;
    }

    // ------------------------------------------------------------------ cairn with a token

    static boolean cairnToken(Stage s) {
        int g = s.top(0, 0);
        switch (s.kind.style()) {
            case "spire" -> {
                int h = s.vary(1, 4, 7);
                for (int y = 0; y < h; y++) {
                    int off = (y % 2 == 0) ? 0 : (s.vary(2 + y, 0, 2) - 1);
                    s.set(0, g + y, 0, y == 0 ? s.rk(0, g, 0) : s.rubble(0, g + y, 0));
                    if (y > 0 && y < h - 1 && off != 0 && s.chance(8 + y, 0.5)) s.set(off, g + y, 0, s.rubble(off, g + y, 0));
                }
                s.lying(0, g + h, 0, token(s, 3));
            }
            case "ring" -> {
                ring(s, 0, 0, 2, true);
                s.set(0, g, 0, s.rk(0, g, 0));
                s.lying(0, g + 1, 0, token(s, 3));
                if (s.chance(4, 0.5)) candle(s, 1, g, 0, 1 + s.vary(6, 0, 2), false);
            }
            case "marker" -> {
                s.set(0, g, 0, s.rk(0, g, 0));
                s.set(0, g + 1, 0, Blocks.CHISELED_STONE_BRICKS);
                s.set(0, g + 2, 0, s.rk(0, g + 2, 0));
                s.set(1, g, 0, s.rubble(1, g, 0));
                s.lying(1, g + 1, 0, token(s, 3));
                s.rubbleRing(0, 0, 3, 4);
            }
            default -> {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 1 && s.chance(10 + dx * 3 + dz, 0.6)) continue;
                    s.set(dx, s.top(dx, dz), dz, s.rubble(dx, g, dz));
                }
                s.set(0, g + 1, 0, s.rk(0, g + 1, 0));
                s.set(0, g + 2, 0, s.rubble(0, g + 2, 0));
                s.lying(0, g + 3, 0, token(s, 3));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ bedroll and cold fire

    static boolean bedroll(Stage s) {
        int g = s.top(0, 0);
        if (s.kind.style().equals("lean_to")) {
            // a lean-to of three posts and a sloped roof of planks, one bedroll under it
            for (int dz = -1; dz <= 1; dz += 2) {
                s.set(-2, g, dz, s.wood.log());
                s.set(2, g, dz, s.wood.log());
                s.set(-2, g + 1, dz, s.wood.log());
                s.set(2, g + 1, dz, s.wood.log());
            }
            for (int dx = -2; dx <= 2; dx++) {
                s.set(dx, g + 2, -1, s.wood.slab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                s.set(dx, g + 2, 0, s.wood.slab().defaultBlockState());
                s.set(dx, g + 2, 1, s.wood.slab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                s.set(dx, g + 1, 1, Blocks.AIR);
            }
            s.set(-2, g, 2, Blocks.AIR);
            bed(s, -1, g, 0, Direction.EAST, bedFor(s));
            s.b.campfire(3, g, 0, false, false);
        } else {
            bed(s, -1, g, 0, Direction.EAST, bedFor(s));
            s.b.campfire(2, g, 1, s.chance(4, 0.1), false);
            ring(s, 2, 1, 1, true);
            s.set(2, g, 1, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
            s.set(3, g, -1, s.wood.log().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
            s.set(0, g, -2, s.wood.log().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        }
        BlockPos pack = s.barrel(-2, g, -1, s.chance(9, 0.55) ? s.lootSmall(1) : null);
        if (s.chance(10, 0.35)) s.put(pack, token(s, 8));
        s.dress(3, s.top(3, 2), 2);
        return true;
    }

    // ------------------------------------------------------------------ lovers' tree

    static boolean loversTree(Stage s) {
        int g = s.top(0, 0);
        int h = s.vary(1, 5, 8);
        Block log = s.wood.log();
        for (int y = 0; y < h; y++) s.set(0, g + y, 0, log);
        // a small crown, leaves persistent so they never decay at worldgen
        Block leaves = s.wood.leaves();
        int cr = h >= 7 ? 3 : 2;
        for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++) for (int dy = h - 2; dy <= h + 1; dy++) {
            double d = Math.hypot(dx, dz) + Math.abs(dy - (h - 0.5)) * 0.8;
            if (d > cr + 0.4 || (dx == 0 && dz == 0 && dy < h)) continue;
            if (s.chance(40 + dx * 5 + dz * 3 + dy, 0.12)) continue;
            if (s.free(dx, g + dy, dz)) {
                s.set(dx, g + dy, dz, leaves.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
            }
        }
        // names cut in the bark: a wall sign on the trunk
        String a = initial(s, 0), c = initial(s, 1);
        s.wallSign(0, g + 1, -1, Direction.NORTH, "", a + " + " + c, "<3", "");
        if (s.kind.style().equals("ribbon")) {
            s.set(1, g, -1, s.chance(3, 0.5) ? Blocks.RED_CARPET : Blocks.PINK_CARPET);
            s.set(-1, g, -1, Blocks.PINK_CARPET);
        } else {
            // a bench under the tree for two
            s.set(-1, g, -1, s.wood.slab());
            s.set(1, g, -1, s.wood.slab());
        }
        for (int i = 0; i < 4; i++) {
            int x = s.vary(20 + i, -2, 2), z = s.vary(30 + i, -3, -1);
            if (s.dry(x, z) && s.free(x, s.top(x, z), z) && s.lush) s.set(x, s.top(x, z), z, flower(s, 50 + i));
        }
        return true;
    }

    private static String initial(Stage s, int k) {
        String n = com.skycraft.landmarks.Lore.firstName(s.site.province(), net.minecraft.util.RandomSource.create(s.seed + 131L * (k + 1)));
        return n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ offering

    static boolean offering(Stage s) {
        int g = s.top(0, 0);
        switch (s.kind.style()) {
            case "stump" -> {
                s.set(0, g, 0, s.wood.log());
                s.set(0, g + 1, 0, s.wood.log().defaultBlockState());
                s.set(1, g, 0, s.wood.log().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
                candle(s, 0, g + 2, 0, 1 + s.vary(1, 0, 2), true);
                s.lying(0, g + 2, 1, offering(s, 2));
                s.set(-1, g, 0, flower(s, 3));
            }
            case "bowl" -> {
                s.set(0, g, 0, s.rk(0, g, 0));
                s.set(0, g + 1, 0, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
                s.set(-1, g, 0, s.rk(-1, g, 0));
                s.set(1, g, 0, s.rubble(1, g, 0));
                candle(s, -1, g + 1, 0, 2, true);
                s.lying(1, g + 1, 0, offering(s, 4));
            }
            case "bones" -> {
                s.set(0, g, 0, Blocks.BONE_BLOCK);
                s.set(0, g + 1, 0, Blocks.SKELETON_SKULL);
                s.set(1, g, 1, Blocks.BONE_BLOCK);
                s.set(-1, g, 0, s.rubble(-1, g, 0));
                candle(s, 1, g, -1, 3, false);
                s.lying(-1, g + 1, 0, offering(s, 5));
            }
            default -> { // niche: three stones, a candle and a gift
                for (int dx = -1; dx <= 1; dx++) s.set(dx, g, 1, s.rk(dx, g, 1));
                s.set(-1, g + 1, 1, s.rk(-1, g + 1, 1));
                s.set(1, g + 1, 1, s.rk(1, g + 1, 1));
                s.set(0, g + 1, 1, s.rk(0, g + 1, 1));
                s.set(0, g, 0, s.rk(0, g, 0));
                candle(s, 0, g + 1, 0, 1 + s.vary(1, 0, 3), true);
                s.lying(-1, g, 0, offering(s, 6));
                s.set(1, g, 0, flower(s, 7));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ a lone traveller's pack

    static boolean pack(Stage s) {
        int g = s.top(0, 0);
        switch (s.kind.style()) {
            case "crate" -> {
                s.barrel(0, g, 0, null);
                BlockPos top = s.barrel(0, g + 1, 0, s.lootSmall(2));
                s.set(1, g, 0, Blocks.HAY_BLOCK);
                s.set(-1, g, 1, s.wood.fence());
                s.put(top, token(s, 2));
            }
            case "saddlebag" -> {
                s.chest(0, g, 0, Direction.SOUTH, s.lootSmall(2));
                s.set(1, g, 0, Blocks.HAY_BLOCK);
                s.set(-1, g, 0, Blocks.BROWN_CARPET);
                s.lying(-1, g + 1, 0, new ItemStack(Items.LEAD));
            }
            default -> {
                BlockPos b = s.barrel(0, g, 0, s.lootSmall(2));
                s.set(1, g, 0, Blocks.BROWN_CARPET);
                s.set(1, g, 1, s.wood.log().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
                s.put(b, token(s, 3));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ warnings

    static boolean warning(Stage s) {
        int g = s.top(0, 0);
        String[] w = {"", com.skycraft.landmarks.Lore.warning(net.minecraft.util.RandomSource.create(s.seed ^ 0xAB12L)), "", ""};
        switch (s.kind.style()) {
            case "skull_pike" -> {
                for (int y = 0; y < 4; y++) s.set(0, g + y, 0, s.wood.fence());
                s.set(0, g + 4, 0, Blocks.SKELETON_SKULL);
                s.set(-1, g, 1, Blocks.BONE_BLOCK);
                s.standingSign(1, g, -1, 8, w);
            }
            case "crow_post" -> {
                for (int y = 0; y < 3; y++) s.set(0, g + y, 0, s.wood.fence());
                s.set(-1, g + 2, 0, s.wood.fence());
                s.set(1, g + 2, 0, s.wood.fence());
                s.set(0, g + 3, 0, Blocks.CARVED_PUMPKIN.defaultBlockState());
                s.set(-1, g + 1, 0, Blocks.HAY_BLOCK);
                s.set(0, g + 1, -1, Blocks.HAY_BLOCK);
                s.standingSign(2, g, 0, 12, w);
            }
            default -> { // scrawl: a crooked sign among rubble
                s.standingSign(0, g, 0, 10 + s.vary(1, 0, 3), w);
                s.rubbleRing(0, 0, 3, 5);
                s.set(1, g, 1, Blocks.BONE_BLOCK);
                s.set(-1, g, -1, Blocks.SKELETON_SKULL);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ spring

    static boolean spring(Stage s) {
        int g = s.top(0, 0);
        if (!s.dry(0, 0)) return false;
        if (s.kind.style().equals("trough")) {
            s.set(0, g, 0, s.wood.planks().defaultBlockState());
            s.set(1, g, 0, s.wood.planks().defaultBlockState());
            s.set(0, g + 1, 0, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
            s.set(1, g + 1, 0, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 2));
            s.set(-1, g, 0, s.wood.fence());
            s.set(2, g, 0, s.wood.fence());
            s.set(0, g, -1, Blocks.HAY_BLOCK);
            return true;
        }
        // a stone basin sunk into the ground: water in the middle, a ring of rock around it
        int w = s.chance(1, 0.5) ? 1 : 0;
        for (int dx = -w - 1; dx <= w + 1; dx++) {
            for (int dz = -w - 1; dz <= w + 1; dz++) {
                if (!s.dry(dx, dz)) continue;
                boolean edge = Math.abs(dx) == w + 1 || Math.abs(dz) == w + 1;
                int y = s.top(dx, dz) - 1;
                if (edge) {
                    s.set(dx, y, dz, s.lush ? Blocks.MOSSY_COBBLESTONE : s.rk(dx, y, dz).getBlock());
                    if (s.chance(20 + dx * 7 + dz, 0.45)) s.set(dx, y + 1, dz, s.rk(dx, y + 1, dz));
                } else {
                    s.set(dx, y - 1, dz, Blocks.CLAY);
                    s.set(dx, y, dz, Blocks.WATER);
                }
            }
        }
        if (s.lush) s.set(w + 2, g, 0, Blocks.FERN);
        return true;
    }

    // ------------------------------------------------------------------ remains

    static boolean remains(Stage s) {
        int g = s.top(0, 0);
        switch (s.kind.style()) {
            case "ribcage" -> {
                for (int i = -2; i <= 2; i++) {
                    s.set(i, g, 1, Blocks.BONE_BLOCK);
                    s.set(i, g + 1, 0, Blocks.BONE_BLOCK);
                    s.set(i, g, -1, Blocks.BONE_BLOCK);
                    if (Math.abs(i) < 2 && i % 2 == 0) s.set(i, g + 1, 1 - 0, Blocks.AIR);
                }
                s.set(-3, g, 0, Blocks.BONE_BLOCK);
                s.set(-4, g, 0, Blocks.SKELETON_SKULL);
                s.set(0, g, 0, s.rubble(0, g, 0));
            }
            case "driftwood" -> {
                for (int i = 0; i < 5; i++) {
                    int x = s.vary(i, -3, 3), z = s.vary(10 + i, -3, 3);
                    if (!s.dry(x, z)) continue;
                    Direction.Axis ax = (i % 2 == 0) ? Direction.Axis.X : Direction.Axis.Z;
                    for (int k = 0; k < 2 + i % 3; k++) {
                        int xx = x + (ax == Direction.Axis.X ? k : 0), zz = z + (ax == Direction.Axis.Z ? k : 0);
                        if (s.dry(xx, zz) && s.free(xx, s.top(xx, zz), zz)) {
                            s.set(xx, s.top(xx, zz), zz, Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, ax));
                        }
                    }
                }
            }
            default -> { // antler pile
                for (int i = 0; i < 6; i++) {
                    int x = s.vary(i, -2, 2), z = s.vary(10 + i, -2, 2);
                    if (!s.dry(x, z)) continue;
                    s.set(x, s.top(x, z), z, i % 3 == 0 ? Blocks.BONE_BLOCK.defaultBlockState() : s.rubble(x, g, z));
                }
                s.set(0, g + 1, 0, Blocks.SKELETON_SKULL);
                s.set(0, g, 0, Blocks.BONE_BLOCK);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ fire ring

    static boolean fireRing(Stage s) {
        int g = s.top(0, 0);
        ring(s, 0, 0, 2, true);
        boolean lit = s.kind.style().equals("embers");
        s.set(0, g, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, lit));
        s.set(2, g, 2, s.wood.log().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        s.set(-3, g, 0, s.wood.log().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        s.set(1, g, -3, Blocks.BONE_BLOCK);
        if (s.chance(5, 0.5)) s.set(-1, g, 3, Blocks.SKELETON_SKULL);
        BlockPos b = s.barrel(3, g, -2, null);
        if (s.chance(8, 0.5)) s.put(b, token(s, 9));
        return true;
    }

    // ------------------------------------------------------------------ charms in a dead tree

    static boolean charms(Stage s) {
        int g = s.top(0, 0);
        int h = s.vary(1, 5, 7);
        Block log = s.chance(2, 0.5) ? Blocks.STRIPPED_SPRUCE_LOG : s.wood.log();
        for (int y = 0; y < h; y++) s.set(0, g + y, 0, log);
        s.set(1, g + h - 2, 0, log);
        s.set(2, g + h - 2, 0, log);
        s.set(-1, g + h - 3, 0, log);
        s.set(-2, g + h - 3, 0, log);
        s.set(0, g + h - 1, 1, log);
        int[][] hang = {{2, h - 2}, {-2, h - 3}, {1, h - 2}, {-1, h - 3}};
        for (int[] hpos : hang) {
            int len = 1 + s.vary(hpos[0] + 5, 0, 2);
            for (int i = 1; i <= len; i++) s.set(hpos[0], g + hpos[1] - i, 0, Blocks.CHAIN);
            s.set(hpos[0], g + hpos[1] - len - 1, 0,
                    s.chance(hpos[0] + 9, 0.5) ? Blocks.BONE_BLOCK.defaultBlockState()
                            : Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true));
        }
        s.rubbleRing(0, 0, 3, 4);
        return true;
    }
}
