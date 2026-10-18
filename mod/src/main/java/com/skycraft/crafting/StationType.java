package com.skycraft.crafting;

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
        return Component.translatable("block.skycraft." + blockId);
    }

    public BlockBehaviour.Properties properties() {
        return switch (this) {
            case FORGE, SMELTER -> com.skycraft.platform.SkyProperties.block(blockId).mapColor(MapColor.STONE).strength(3.5f)
                    .requiresCorrectToolForDrops().sound(SoundType.STONE).lightLevel(state -> 9);
            default -> com.skycraft.platform.SkyProperties.block(blockId).mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD);
        };
    }
}
