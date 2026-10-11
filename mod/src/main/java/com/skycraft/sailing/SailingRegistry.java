package com.skycraft.sailing;

import com.skycraft.Skycraft;
import com.skycraft.sailing.world.FlotsamFeature;
import com.skycraft.sailing.world.SeaWreckFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

/** Entity types, items, blocks and world features of the sailing module. */
public final class SailingRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);

    /** One entity type per ship (each has its own fixed hit box). */
    public static final Map<ShipType, RegistryObject<EntityType<ShipEntity>>> SHIPS = new EnumMap<>(ShipType.class);
    public static final Map<ShipType, RegistryObject<Item>> SHIP_ITEMS = new EnumMap<>(ShipType.class);

    static {
        for (ShipType t : ShipType.VALUES) {
            SHIPS.put(t, ENTITIES.register(t.id, () -> EntityType.Builder.<ShipEntity>of((type, level) -> new ShipEntity(type, level, t), MobCategory.MISC)
                    .sized(t.hitWidth(), t.height).clientTrackingRange(t == ShipType.ROWBOAT ? 10 : 16).updateInterval(2)
                    .build(Skycraft.MODID + ":" + t.id)));
            SHIP_ITEMS.put(t, ITEMS.register(t.id, () -> new ShipItem(t, new Item.Properties().stacksTo(1))));
        }
    }

    public static final RegistryObject<Block> SHIPWRIGHT_BENCH = BLOCKS.register("shipwright_bench",
            () -> new ShipwrightBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD)
                    .noOcclusion()));
    public static final RegistryObject<Item> SHIPWRIGHT_BENCH_ITEM = ITEMS.register("shipwright_bench",
            () -> new BlockItem(SHIPWRIGHT_BENCH.get(), new Item.Properties()));

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> SEA_WRECK = FEATURES.register("sea_wreck",
            () -> new SeaWreckFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> FLOTSAM = FEATURES.register("flotsam",
            () -> new FlotsamFeature(NoneFeatureConfiguration.CODEC));

    private SailingRegistry() {}

    public static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        FEATURES.register(modBus);
    }

    public static EntityType<ShipEntity> type(ShipType t) {
        return SHIPS.get(t).get();
    }
}
