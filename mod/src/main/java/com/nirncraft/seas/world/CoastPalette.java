package com.nirncraft.seas.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Building materials of the coastal sites by the culture of the region they stand in (lighthouses, keepers' and
 * hermits' huts, smugglers' fittings): Nord timber and fieldstone, Imperial dressed stone and red tile, Dunmer
 * plaster, Redguard and Khajiit sandstone, Altmer white stone and gold, Argonian and Bosmer living wood, Breton slate.
 */
public record CoastPalette(BlockState wall, BlockState wallAlt, BlockState base, BlockState floor, Block roofStairs,
                           BlockState roofBlock, BlockState log, BlockState fence, BlockState trim, BlockState light) {

    public static CoastPalette of(String culture) {
        return switch (culture) {
            case "nord", "atmoran", "reachman" -> new CoastPalette(Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(),
                    Blocks.COBBLESTONE.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_STAIRS,
                    Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_FENCE.defaultBlockState(),
                    Blocks.STONE_BRICKS.defaultBlockState(), Blocks.LANTERN.defaultBlockState());
            case "dunmer", "velothi" -> new CoastPalette(Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(),
                    Blocks.SANDSTONE.defaultBlockState(), Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.SMOOTH_SANDSTONE_STAIRS,
                    Blocks.TERRACOTTA.defaultBlockState(), Blocks.DARK_OAK_LOG.defaultBlockState(), Blocks.DARK_OAK_FENCE.defaultBlockState(),
                    com.nirncraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA.defaultBlockState(), Blocks.LANTERN.defaultBlockState());
            case "redguard", "khajiit", "yokudan" -> new CoastPalette(Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(),
                    Blocks.SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.SANDSTONE_STAIRS,
                    com.nirncraft.worldgen.VanillaBlocks.ORANGE_TERRACOTTA.defaultBlockState(), Blocks.ACACIA_LOG.defaultBlockState(), Blocks.ACACIA_FENCE.defaultBlockState(),
                    Blocks.CHISELED_SANDSTONE.defaultBlockState(), Blocks.LANTERN.defaultBlockState());
            case "altmer", "maormer" -> new CoastPalette(Blocks.QUARTZ_BLOCK.defaultBlockState(), Blocks.SMOOTH_QUARTZ.defaultBlockState(),
                    Blocks.CALCITE.defaultBlockState(), Blocks.BIRCH_PLANKS.defaultBlockState(), Blocks.QUARTZ_STAIRS,
                    Blocks.QUARTZ_BRICKS.defaultBlockState(), Blocks.STRIPPED_BIRCH_LOG.defaultBlockState(), Blocks.BIRCH_FENCE.defaultBlockState(),
                    Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState());
            case "argonian", "bosmer" -> new CoastPalette(Blocks.MANGROVE_PLANKS.defaultBlockState(), Blocks.MUD_BRICKS.defaultBlockState(),
                    Blocks.MUD_BRICKS.defaultBlockState(), Blocks.MANGROVE_PLANKS.defaultBlockState(), Blocks.MANGROVE_STAIRS,
                    Blocks.MANGROVE_PLANKS.defaultBlockState(), Blocks.MANGROVE_LOG.defaultBlockState(), Blocks.MANGROVE_FENCE.defaultBlockState(),
                    Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Blocks.LANTERN.defaultBlockState());
            case "breton", "orc" -> new CoastPalette(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.CRACKED_STONE_BRICKS.defaultBlockState(),
                    Blocks.COBBLESTONE.defaultBlockState(), Blocks.DARK_OAK_PLANKS.defaultBlockState(), Blocks.DEEPSLATE_TILE_STAIRS,
                    Blocks.DEEPSLATE_TILES.defaultBlockState(), Blocks.DARK_OAK_LOG.defaultBlockState(), Blocks.DARK_OAK_FENCE.defaultBlockState(),
                    Blocks.POLISHED_ANDESITE.defaultBlockState(), Blocks.LANTERN.defaultBlockState());
            default -> new CoastPalette(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.POLISHED_ANDESITE.defaultBlockState(),
                    Blocks.COBBLESTONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.BRICK_STAIRS,
                    Blocks.BRICKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(),
                    Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), Blocks.LANTERN.defaultBlockState());
        };
    }
}
