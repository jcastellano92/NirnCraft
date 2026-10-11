package com.skycraft.economy.homes;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The architecture of a province's player home: materials, walls (square or round), roof shape, storeys, posts. Each
 * is the home builder's own ({@link HomeBuilder}), independent of the settlement kits.
 */
public final class HomeStyle {
    public enum Roof { GABLE, HIP, FLAT, DOME, CONE, MUSHROOM }

    public final String id;
    BlockState foundation = s(Blocks.STONE_BRICKS);
    BlockState floor = s(Blocks.OAK_PLANKS);
    BlockState wallLow = s(Blocks.STONE_BRICKS);
    BlockState wall = s(Blocks.WHITE_TERRACOTTA);
    BlockState post = s(Blocks.STRIPPED_OAK_LOG);
    BlockState roofStairs = s(Blocks.BRICK_STAIRS);
    BlockState roofSlab = s(Blocks.BRICK_SLAB);
    BlockState roofBlock = s(Blocks.BRICKS);
    BlockState trim = s(Blocks.STONE_BRICK_SLAB);
    BlockState window = s(Blocks.GLASS_PANE);
    BlockState door = s(Blocks.OAK_DOOR);
    BlockState light = s(Blocks.LANTERN);
    BlockState carpet = s(Blocks.RED_CARPET);
    BlockState bed = s(Blocks.RED_BED);
    BlockState table = s(Blocks.OAK_FENCE);
    BlockState chair = s(Blocks.OAK_STAIRS);
    BlockState accent = s(Blocks.RED_WOOL);
    Roof roof = Roof.GABLE;
    boolean round;
    boolean stilts;
    boolean timber;
    int storeys = 1;
    int wallH = 4;
    boolean hearth;

    private HomeStyle(String id) {
        this.id = id;
    }

    private static BlockState s(Block b) {
        return b.defaultBlockState();
    }

    /** The style for a plot's {@code style} name ({@code tamriel.settlement.plan.HousePlots}). */
    public static HomeStyle of(String id) {
        HomeStyle h = new HomeStyle(id);
        switch (id) {
            case "imperial_manor" -> {
                h.wall = s(Blocks.SMOOTH_SANDSTONE);
                h.wallLow = s(Blocks.STONE_BRICKS);
                h.post = s(Blocks.QUARTZ_PILLAR);
                h.roof = HomeStyle.Roof.HIP;
                h.storeys = 2;
                h.bed = s(Blocks.RED_BED);
                h.carpet = s(Blocks.RED_CARPET);
                h.table = s(Blocks.DARK_OAK_FENCE);
                h.chair = s(Blocks.DARK_OAK_STAIRS);
                h.floor = s(Blocks.POLISHED_ANDESITE);
            }
            case "imperial" -> {
                h.wall = s(Blocks.SMOOTH_SANDSTONE);
                h.roof = HomeStyle.Roof.HIP;
                h.storeys = 2;
            }
            case "imperial_cottage" -> {
                h.wall = s(Blocks.WHITE_TERRACOTTA);
                h.wallLow = s(Blocks.COBBLESTONE);
                h.timber = true;
                h.roofStairs = s(Blocks.SPRUCE_STAIRS);
                h.roofSlab = s(Blocks.SPRUCE_SLAB);
                h.roofBlock = s(Blocks.SPRUCE_PLANKS);
                h.carpet = s(Blocks.BROWN_CARPET);
                h.bed = s(Blocks.BROWN_BED);
            }
            case "imperial_shack" -> {
                h.foundation = s(Blocks.OAK_PLANKS);
                h.wallLow = s(Blocks.OAK_PLANKS);
                h.wall = s(Blocks.OAK_PLANKS);
                h.post = s(Blocks.OAK_LOG);
                h.roofStairs = s(Blocks.OAK_STAIRS);
                h.roofSlab = s(Blocks.OAK_SLAB);
                h.roofBlock = s(Blocks.OAK_PLANKS);
                h.window = s(Blocks.OAK_FENCE);
                h.carpet = s(Blocks.BROWN_CARPET);
                h.bed = s(Blocks.BROWN_BED);
                h.stilts = true;
                h.timber = true;
            }
            case "colovian" -> {
                h.wall = s(Blocks.STONE_BRICKS);
                h.wallLow = s(Blocks.COBBLESTONE);
                h.post = s(Blocks.SPRUCE_LOG);
                h.roofStairs = s(Blocks.DEEPSLATE_TILE_STAIRS);
                h.roofSlab = s(Blocks.DEEPSLATE_TILE_SLAB);
                h.roofBlock = s(Blocks.DEEPSLATE_TILES);
                h.floor = s(Blocks.SPRUCE_PLANKS);
                h.table = s(Blocks.SPRUCE_FENCE);
                h.chair = s(Blocks.SPRUCE_STAIRS);
                h.door = s(Blocks.SPRUCE_DOOR);
                h.storeys = 2;
                h.hearth = true;
            }
            case "hlaalu" -> {
                h.foundation = s(Blocks.SANDSTONE);
                h.wallLow = s(Blocks.CUT_SANDSTONE);
                h.wall = s(Blocks.SMOOTH_SANDSTONE);
                h.post = s(Blocks.STRIPPED_DARK_OAK_LOG);
                h.roof = HomeStyle.Roof.FLAT;
                h.roofSlab = s(Blocks.SMOOTH_SANDSTONE_SLAB);
                h.roofBlock = s(Blocks.CUT_SANDSTONE);
                h.trim = s(Blocks.DARK_OAK_SLAB);
                h.window = s(Blocks.DARK_OAK_FENCE);
                h.door = s(Blocks.DARK_OAK_DOOR);
                h.floor = s(Blocks.DARK_OAK_PLANKS);
                h.carpet = s(Blocks.ORANGE_CARPET);
                h.bed = s(Blocks.ORANGE_BED);
                h.accent = s(Blocks.BROWN_WOOL);
                h.table = s(Blocks.DARK_OAK_FENCE);
                h.chair = s(Blocks.DARK_OAK_STAIRS);
                h.storeys = 2;
            }
            case "telvanni" -> {
                h.foundation = s(Blocks.MUSHROOM_STEM);
                h.wallLow = s(Blocks.MUSHROOM_STEM);
                h.wall = s(Blocks.MUSHROOM_STEM);
                h.post = s(Blocks.MUSHROOM_STEM);
                h.roof = HomeStyle.Roof.MUSHROOM;
                h.roofBlock = s(Blocks.BROWN_MUSHROOM_BLOCK);
                h.roofSlab = s(Blocks.MUD_BRICK_SLAB);
                h.window = s(Blocks.PURPLE_STAINED_GLASS_PANE);
                h.door = s(Blocks.CRIMSON_DOOR);
                h.light = s(Blocks.SHROOMLIGHT);
                h.floor = s(Blocks.CRIMSON_PLANKS);
                h.carpet = s(Blocks.PURPLE_CARPET);
                h.bed = s(Blocks.PURPLE_BED);
                h.accent = s(Blocks.PURPLE_WOOL);
                h.table = s(Blocks.CRIMSON_FENCE);
                h.chair = s(Blocks.CRIMSON_STAIRS);
                h.round = true;
                h.storeys = 3;
            }
            case "breton" -> {
                h.foundation = s(Blocks.COBBLESTONE);
                h.wallLow = s(Blocks.COBBLESTONE);
                h.wall = s(Blocks.WHITE_TERRACOTTA);
                h.post = s(Blocks.DARK_OAK_LOG);
                h.timber = true;
                h.roofStairs = s(Blocks.DEEPSLATE_TILE_STAIRS);
                h.roofSlab = s(Blocks.DEEPSLATE_TILE_SLAB);
                h.roofBlock = s(Blocks.DEEPSLATE_TILES);
                h.door = s(Blocks.DARK_OAK_DOOR);
                h.floor = s(Blocks.DARK_OAK_PLANKS);
                h.carpet = s(Blocks.BLUE_CARPET);
                h.bed = s(Blocks.BLUE_BED);
                h.accent = s(Blocks.BLUE_WOOL);
                h.table = s(Blocks.DARK_OAK_FENCE);
                h.chair = s(Blocks.DARK_OAK_STAIRS);
                h.storeys = 2;
                h.hearth = true;
            }
            case "orc" -> {
                h.foundation = s(Blocks.COBBLED_DEEPSLATE);
                h.wallLow = s(Blocks.COBBLED_DEEPSLATE);
                h.wall = s(Blocks.SPRUCE_LOG);
                h.post = s(Blocks.STRIPPED_SPRUCE_LOG);
                h.roofStairs = s(Blocks.SPRUCE_STAIRS);
                h.roofSlab = s(Blocks.SPRUCE_SLAB);
                h.roofBlock = s(Blocks.SPRUCE_PLANKS);
                h.window = s(Blocks.SPRUCE_FENCE);
                h.door = s(Blocks.SPRUCE_DOOR);
                h.floor = s(Blocks.SPRUCE_PLANKS);
                h.carpet = s(Blocks.GRAY_CARPET);
                h.bed = s(Blocks.GRAY_BED);
                h.accent = s(Blocks.GRAY_WOOL);
                h.table = s(Blocks.SPRUCE_FENCE);
                h.chair = s(Blocks.SPRUCE_STAIRS);
                h.hearth = true;
            }
            case "redguard" -> {
                h.foundation = s(Blocks.SANDSTONE);
                h.wallLow = s(Blocks.SANDSTONE);
                h.wall = s(Blocks.SMOOTH_SANDSTONE);
                h.post = s(Blocks.CHISELED_SANDSTONE);
                h.roof = HomeStyle.Roof.DOME;
                h.roofBlock = s(Blocks.CYAN_TERRACOTTA);
                h.roofSlab = s(Blocks.SMOOTH_SANDSTONE_SLAB);
                h.trim = s(Blocks.SANDSTONE_SLAB);
                h.window = s(Blocks.ACACIA_FENCE);
                h.door = s(Blocks.ACACIA_DOOR);
                h.floor = s(Blocks.ACACIA_PLANKS);
                h.carpet = s(Blocks.RED_CARPET);
                h.bed = s(Blocks.RED_BED);
                h.table = s(Blocks.ACACIA_FENCE);
                h.chair = s(Blocks.ACACIA_STAIRS);
            }
            case "khajiit" -> {
                h.foundation = s(Blocks.SANDSTONE);
                h.wallLow = s(Blocks.CUT_SANDSTONE);
                h.wall = s(Blocks.SANDSTONE);
                h.post = s(Blocks.STRIPPED_ACACIA_LOG);
                h.roof = HomeStyle.Roof.DOME;
                h.roofBlock = s(Blocks.ORANGE_TERRACOTTA);
                h.roofSlab = s(Blocks.SANDSTONE_SLAB);
                h.window = s(Blocks.ACACIA_FENCE);
                h.door = s(Blocks.ACACIA_DOOR);
                h.floor = s(Blocks.ACACIA_PLANKS);
                h.carpet = s(Blocks.ORANGE_CARPET);
                h.bed = s(Blocks.ORANGE_BED);
                h.accent = s(Blocks.ORANGE_WOOL);
                h.table = s(Blocks.ACACIA_FENCE);
                h.chair = s(Blocks.ACACIA_STAIRS);
            }
            case "bosmer" -> {
                h.foundation = s(Blocks.MOSSY_COBBLESTONE);
                h.wallLow = s(Blocks.JUNGLE_LOG);
                h.wall = s(Blocks.JUNGLE_PLANKS);
                h.post = s(Blocks.JUNGLE_LOG);
                h.roof = HomeStyle.Roof.CONE;
                h.roofBlock = s(Blocks.JUNGLE_LEAVES).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
                h.roofSlab = s(Blocks.MOSS_CARPET);
                h.window = s(Blocks.JUNGLE_FENCE);
                h.door = s(Blocks.JUNGLE_DOOR);
                h.floor = s(Blocks.JUNGLE_PLANKS);
                h.carpet = s(Blocks.GREEN_CARPET);
                h.bed = s(Blocks.GREEN_BED);
                h.accent = s(Blocks.GREEN_WOOL);
                h.table = s(Blocks.JUNGLE_FENCE);
                h.chair = s(Blocks.JUNGLE_STAIRS);
                h.round = true;
                h.stilts = true;
            }
            case "altmer" -> {
                h.foundation = s(Blocks.QUARTZ_BRICKS);
                h.wallLow = s(Blocks.QUARTZ_BRICKS);
                h.wall = s(Blocks.SMOOTH_QUARTZ);
                h.post = s(Blocks.QUARTZ_PILLAR);
                h.roof = HomeStyle.Roof.HIP;
                h.roofStairs = s(Blocks.PRISMARINE_BRICK_STAIRS);
                h.roofSlab = s(Blocks.PRISMARINE_BRICK_SLAB);
                h.roofBlock = s(Blocks.PRISMARINE_BRICKS);
                h.trim = s(Blocks.QUARTZ_SLAB);
                h.window = s(Blocks.LIGHT_BLUE_STAINED_GLASS_PANE);
                h.door = s(Blocks.BIRCH_DOOR);
                h.light = s(Blocks.SEA_LANTERN);
                h.floor = s(Blocks.BIRCH_PLANKS);
                h.carpet = s(Blocks.YELLOW_CARPET);
                h.bed = s(Blocks.LIGHT_BLUE_BED);
                h.accent = s(Blocks.YELLOW_WOOL);
                h.table = s(Blocks.BIRCH_FENCE);
                h.chair = s(Blocks.BIRCH_STAIRS);
                h.storeys = 2;
            }
            case "argonian" -> {
                h.foundation = s(Blocks.MANGROVE_PLANKS);
                h.wallLow = s(Blocks.MANGROVE_PLANKS);
                h.wall = s(Blocks.BAMBOO_PLANKS);
                h.post = s(Blocks.MANGROVE_LOG);
                h.roof = HomeStyle.Roof.CONE;
                h.roofBlock = s(Blocks.HAY_BLOCK);
                h.roofSlab = s(Blocks.BAMBOO_MOSAIC_SLAB);
                h.window = s(Blocks.MANGROVE_FENCE);
                h.door = s(Blocks.MANGROVE_DOOR);
                h.light = s(Blocks.VERDANT_FROGLIGHT);
                h.floor = s(Blocks.MANGROVE_PLANKS);
                h.carpet = s(Blocks.GREEN_CARPET);
                h.bed = s(Blocks.GREEN_BED);
                h.accent = s(Blocks.GREEN_WOOL);
                h.table = s(Blocks.MANGROVE_FENCE);
                h.chair = s(Blocks.MANGROVE_STAIRS);
                h.round = true;
                h.stilts = true;
            }
            default -> {
            }
        }
        return h;
    }
}
