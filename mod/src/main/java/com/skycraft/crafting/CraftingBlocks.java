package com.skycraft.crafting;

import com.skycraft.Skycraft;
import com.skycraft.crafting.block.StationBlock;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ore veins and crafting stations. */
public final class CraftingBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, Skycraft.MODID);

    /** Every block of this module in registration order (block items are made for all of them). */
    public static final Map<String, Supplier<Block>> ALL = new LinkedHashMap<>();
    public static final Map<SkyOre, Supplier<Block>> ORES = new EnumMap<>(SkyOre.class);
    public static final Map<SkyOre, Supplier<Block>> DEEPSLATE_ORES = new EnumMap<>(SkyOre.class);
    public static final Map<StationType, Supplier<Block>> STATIONS = new EnumMap<>(StationType.class);

    static {
        for (SkyOre ore : SkyOre.values()) {
            Block stoneBase = ore == SkyOre.EBONY ? Blocks.DIAMOND_ORE : Blocks.IRON_ORE;
            Block deepBase = ore == SkyOre.EBONY ? Blocks.DEEPSLATE_DIAMOND_ORE : Blocks.DEEPSLATE_IRON_ORE;
            ORES.put(ore, register(ore.oreId(), () -> new DropExperienceBlock(UniformInt.of(ore.xpMin, ore.xpMax), com.skycraft.platform.SkyProperties.block(ore.oreId(), BlockBehaviour.Properties.ofFullCopy(stoneBase)))));
            DEEPSLATE_ORES.put(ore, register(ore.deepslateOreId(), () -> new DropExperienceBlock(UniformInt.of(ore.xpMin, ore.xpMax), com.skycraft.platform.SkyProperties.block(ore.deepslateOreId(), BlockBehaviour.Properties.ofFullCopy(deepBase)))));
        }
        for (StationType type : StationType.values()) {
            STATIONS.put(type, register(type.blockId, () -> new StationBlock(type, type.properties())));
        }
        DRAFTING_TABLE = register("drafting_table", com.skycraft.homestead.DraftingTableBlock::new);
    }

    public static final Supplier<Block> DRAFTING_TABLE;

    private CraftingBlocks() {}

    private static Supplier<Block> register(String id, java.util.function.Supplier<Block> factory) {
        Supplier<Block> ro = BLOCKS.register(id, factory);
        ALL.put(id, ro);
        return ro;
    }
}
