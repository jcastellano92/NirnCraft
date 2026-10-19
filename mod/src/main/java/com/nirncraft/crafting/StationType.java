package com.nirncraft.crafting;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** The Skyrim crafting stations. All share one menu ({@link StationMenu}) and screen. */
public enum StationType {
    FORGE("blacksmith_forge"),
    SMELTER("smelter"),
    TANNING_RACK("tanning_rack"),
    GRINDSTONE("grindstone_wheel"),
    ARMOR_WORKBENCH("armor_workbench");

    public final String blockId;

    StationType(String blockId) {
        this.blockId = blockId;
    }

    /** Grindstone and workbench improve items instead of crafting them. */
    public boolean tempering() {
        return this == GRINDSTONE || this == ARMOR_WORKBENCH;
    }

    public Component title() {
        return Component.translatable("block.nirncraft." + blockId);
    }

    public BlockBehaviour.Properties properties() {
        return switch (this) {
            case FORGE, SMELTER -> com.nirncraft.platform.SkyProperties.block(blockId).mapColor(MapColor.STONE).strength(3.5f)
                    .requiresCorrectToolForDrops().sound(SoundType.STONE).lightLevel(state -> 9);
            default -> com.nirncraft.platform.SkyProperties.block(blockId).mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD);
        };
    }
}
