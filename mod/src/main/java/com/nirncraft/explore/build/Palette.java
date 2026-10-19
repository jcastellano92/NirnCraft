package com.nirncraft.explore.build;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Block palette of a minor site: the wood, cloth and stone a culture builds its camps, huts and shrines from. Picked
 * from the province (and a realm's archetype), so a Khajiit camp is acacia and red sandstone and a Nord cairn is
 * spruce and mossy cobble.
 */
public record Palette(Block log, Block planks, Block slab, Block stairs, Block fence, Block trapdoor, Block door,
                      Block wool, Block accentWool, Block carpet, Block stone, Block brick, Block mossy, Block brickStairs,
                      Block brickSlab, Block wall, Block path, DyeColor banner) {

    public static Palette of(String province, String archetype) {
        String key = province.equals("realm") ? "realm_" + archetype : province;
        return switch (key) {
            case "cyrodiil", "realm_temperate" -> new Palette(Blocks.OAK_LOG, Blocks.OAK_PLANKS, Blocks.OAK_SLAB, Blocks.OAK_STAIRS,
                    Blocks.OAK_FENCE, Blocks.OAK_TRAPDOOR, Blocks.OAK_DOOR, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL, com.nirncraft.worldgen.VanillaBlocks.RED_WOOL, com.nirncraft.worldgen.VanillaBlocks.RED_CARPET,
                    Blocks.STONE, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
                    Blocks.STONE_BRICK_WALL, Blocks.DIRT_PATH, DyeColor.RED);
            case "high_rock" -> new Palette(Blocks.DARK_OAK_LOG, Blocks.OAK_PLANKS, Blocks.OAK_SLAB, Blocks.OAK_STAIRS,
                    Blocks.DARK_OAK_FENCE, Blocks.OAK_TRAPDOOR, Blocks.DARK_OAK_DOOR, com.nirncraft.worldgen.VanillaBlocks.LIGHT_GRAY_WOOL, com.nirncraft.worldgen.VanillaBlocks.BLUE_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.BLUE_CARPET, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE_STAIRS,
                    Blocks.COBBLESTONE_SLAB, Blocks.MOSSY_COBBLESTONE_WALL, Blocks.COARSE_DIRT, DyeColor.BLUE);
            case "morrowind", "realm_ash", "realm_mushroom" -> new Palette(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_SLAB,
                    Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_TRAPDOOR, Blocks.DARK_OAK_DOOR, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.ORANGE_WOOL, com.nirncraft.worldgen.VanillaBlocks.ORANGE_CARPET, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE,
                    Blocks.SANDSTONE_STAIRS, Blocks.CUT_SANDSTONE_SLAB, Blocks.SANDSTONE_WALL, Blocks.COARSE_DIRT, DyeColor.ORANGE);
            case "black_marsh", "realm_swamp" -> new Palette(Blocks.MANGROVE_LOG, Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_SLAB,
                    Blocks.MANGROVE_STAIRS, Blocks.MANGROVE_FENCE, Blocks.MANGROVE_TRAPDOOR, Blocks.MANGROVE_DOOR, com.nirncraft.worldgen.VanillaBlocks.GREEN_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.LIME_WOOL, com.nirncraft.worldgen.VanillaBlocks.GREEN_CARPET, Blocks.MUD_BRICKS, Blocks.MUD_BRICKS, Blocks.PACKED_MUD,
                    Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_SLAB, Blocks.MUD_BRICK_WALL, Blocks.MUD, DyeColor.GREEN);
            case "elsweyr", "realm_savanna" -> new Palette(Blocks.ACACIA_LOG, Blocks.ACACIA_PLANKS, Blocks.ACACIA_SLAB,
                    Blocks.ACACIA_STAIRS, Blocks.ACACIA_FENCE, Blocks.ACACIA_TRAPDOOR, Blocks.ACACIA_DOOR, com.nirncraft.worldgen.VanillaBlocks.ORANGE_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.PURPLE_WOOL, com.nirncraft.worldgen.VanillaBlocks.PURPLE_CARPET, Blocks.RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE,
                    Blocks.RED_SANDSTONE_STAIRS, Blocks.CUT_RED_SANDSTONE_SLAB, Blocks.RED_SANDSTONE_WALL, Blocks.COARSE_DIRT, DyeColor.PURPLE);
            case "hammerfell", "yokuda", "realm_desert" -> new Palette(Blocks.ACACIA_LOG, Blocks.BIRCH_PLANKS, Blocks.BIRCH_SLAB,
                    Blocks.BIRCH_STAIRS, Blocks.ACACIA_FENCE, Blocks.BIRCH_TRAPDOOR, Blocks.ACACIA_DOOR, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.YELLOW_WOOL, com.nirncraft.worldgen.VanillaBlocks.YELLOW_CARPET, Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE,
                    Blocks.SANDSTONE_STAIRS, Blocks.CUT_SANDSTONE_SLAB, Blocks.SANDSTONE_WALL, Blocks.COARSE_DIRT, DyeColor.YELLOW);
            case "valenwood", "realm_jungle" -> new Palette(Blocks.JUNGLE_LOG, Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_SLAB,
                    Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_FENCE, Blocks.JUNGLE_TRAPDOOR, Blocks.JUNGLE_DOOR, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.GREEN_WOOL, com.nirncraft.worldgen.VanillaBlocks.GREEN_CARPET, Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_COBBLESTONE,
                    Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_WALL, Blocks.PODZOL, DyeColor.GREEN);
            case "summerset", "realm_crystal" -> new Palette(Blocks.BIRCH_LOG, Blocks.BIRCH_PLANKS, Blocks.BIRCH_SLAB,
                    Blocks.BIRCH_STAIRS, Blocks.BIRCH_FENCE, Blocks.BIRCH_TRAPDOOR, Blocks.BIRCH_DOOR, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.YELLOW_WOOL, com.nirncraft.worldgen.VanillaBlocks.YELLOW_CARPET, Blocks.CALCITE, Blocks.QUARTZ_BRICKS, Blocks.SMOOTH_QUARTZ,
                    Blocks.QUARTZ_STAIRS, Blocks.QUARTZ_SLAB, Blocks.DIORITE_WALL, Blocks.GRAVEL, DyeColor.YELLOW);
            case "akavir" -> new Palette(Blocks.CHERRY_LOG, Blocks.CHERRY_PLANKS, Blocks.CHERRY_SLAB, Blocks.CHERRY_STAIRS,
                    Blocks.CHERRY_FENCE, Blocks.CHERRY_TRAPDOOR, Blocks.CHERRY_DOOR, com.nirncraft.worldgen.VanillaBlocks.RED_WOOL, com.nirncraft.worldgen.VanillaBlocks.BLACK_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.RED_CARPET, Blocks.DEEPSLATE_TILES, Blocks.POLISHED_DEEPSLATE, Blocks.COBBLED_DEEPSLATE,
                    Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILE_WALL, Blocks.GRAVEL, DyeColor.RED);
            case "pyandonea", "thras" -> new Palette(Blocks.MANGROVE_LOG, Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_SLAB,
                    Blocks.MANGROVE_STAIRS, Blocks.MANGROVE_FENCE, Blocks.MANGROVE_TRAPDOOR, Blocks.MANGROVE_DOOR, com.nirncraft.worldgen.VanillaBlocks.CYAN_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_WOOL, com.nirncraft.worldgen.VanillaBlocks.CYAN_CARPET, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE,
                    Blocks.PRISMARINE_BRICK_STAIRS, Blocks.PRISMARINE_BRICK_SLAB, Blocks.PRISMARINE_WALL, Blocks.SAND, DyeColor.CYAN);
            // Skyrim, Atmora and the cold or high realms: spruce, hide and cobble
            default -> new Palette(Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_STAIRS,
                    Blocks.SPRUCE_FENCE, Blocks.SPRUCE_TRAPDOOR, Blocks.SPRUCE_DOOR, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL,
                    com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE_STAIRS,
                    Blocks.COBBLESTONE_SLAB, Blocks.COBBLESTONE_WALL, Blocks.COARSE_DIRT,
                    province.equals("atmora") ? DyeColor.LIGHT_BLUE : DyeColor.BROWN);
        };
    }
}
