package com.skycraft.explore;

import com.skycraft.Skycraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Blocks, items and the worldgen feature of the exploration module. */
public final class ExploreRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> MINOR_SITE = FEATURES.register("minor_site",
            () -> new MinorSiteFeature(NoneFeatureConfiguration.CODEC));

    /** The glowing welkynd stone of an Ayleid well: use it to restore your magicka (once a day). */
    public static final RegistryObject<Block> AYLEID_WELL = BLOCKS.register("ayleid_well",
            () -> new AyleidWellBlock(BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).strength(-1f, 3600000f)
                    .sound(SoundType.AMETHYST).lightLevel(s -> 15).noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final RegistryObject<Item> AYLEID_WELL_ITEM = ITEMS.register("ayleid_well",
            () -> new BlockItem(AYLEID_WELL.get(), new Item.Properties().rarity(Rarity.RARE)));

    public static final RegistryObject<Item> TREASURE_MAP = ITEMS.register("treasure_map",
            () -> new TreasureMapItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    private ExploreRegistry() {}

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        FEATURES.register(modBus);
    }
}
