package com.nirncraft.homestead;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What may be placed in a homestead. A home needs far more than walls: gardens (dirt, farmland, seeds, saplings,
 * flowers, water), every crafting station (forge, anvil, grindstone, alchemy lab, enchanter, loom, brewing stand,
 * smokers and furnaces...), storage and furniture. So this is a deny-list of the blocks that only grief or break the
 * world's rules: TNT, spawners, command and structure blocks, bedrock, barriers, portals and their frames, obsidian,
 * respawn anchors, fire and lava. (Explosions and fire never destroy anything anyway, see
 * {@code com.nirncraft.protect}.)
 */
public final class HomesteadBlocks {
    private HomesteadBlocks() {}

    /** True if {@code state} may be placed by a builder in their homestead. */
    public static boolean isAllowedBuildingBlock(BlockState state) {
        return !isForbidden(state);
    }

    public static boolean isForbidden(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof TntBlock || block instanceof SpawnerBlock || block instanceof CommandBlock
                || block instanceof StructureBlock || block instanceof JigsawBlock || block instanceof StructureVoidBlock
                || block instanceof EndPortalBlock || block instanceof EndPortalFrameBlock || block instanceof EndGatewayBlock
                || block instanceof NetherPortalBlock || block instanceof BarrierBlock || block instanceof RespawnAnchorBlock
                || block instanceof LightBlock) {
            return true;
        }
        return block == Blocks.BEDROCK || block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.REINFORCED_DEEPSLATE || block == Blocks.DRAGON_EGG || block == Blocks.LAVA
                || state.is(BlockTags.FIRE);
    }
}
