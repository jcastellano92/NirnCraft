package com.skycraft.explore;

import com.skycraft.Skycraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.skycraft.worldgen.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/** Blocks, items and the worldgen feature of the exploration module. */
public final class ExploreRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, Skycraft.MODID);
    public static final DeferredRegister<com.mojang.serialization.MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(BuiltInRegistries.FEATURE_TYPE, Skycraft.MODID);

    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> RIVER_SITE = FEATURES.register("river_site",
            () -> new RiverSiteFeature(NoneFeatureConfiguration.CODEC).codec());
    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> MINOR_SITE = FEATURES.register("minor_site",
            () -> new MinorSiteFeature(NoneFeatureConfiguration.CODEC).codec());

    /** The glowing welkynd stone of an Ayleid well: use it to restore your magicka (once a day). */
    public static final Supplier<Block> AYLEID_WELL = BLOCKS.register("ayleid_well",
            () -> new AyleidWellBlock(com.skycraft.platform.SkyProperties.block("ayleid_well").mapColor(MapColor.QUARTZ).strength(-1f, 3600000f)
                    .sound(SoundType.AMETHYST).lightLevel(s -> 15).noOcclusion().pushReaction(PushReaction.IMMOVEABLE)));
    public static final Supplier<Item> AYLEID_WELL_ITEM = ITEMS.register("ayleid_well",
            () -> new BlockItem(AYLEID_WELL.get(), com.skycraft.platform.SkyProperties.item("ayleid_well").rarity(Rarity.RARE)));

    public static final Supplier<Item> TREASURE_MAP = ITEMS.register("treasure_map",
            () -> new TreasureMapItem(com.skycraft.platform.SkyProperties.item("treasure_map").stacksTo(1).rarity(Rarity.UNCOMMON)));

    private ExploreRegistry() {}

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        FEATURES.register(modBus);
    }
}
