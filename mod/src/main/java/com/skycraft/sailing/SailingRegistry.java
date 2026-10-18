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
import com.skycraft.worldgen.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

import java.util.EnumMap;
import java.util.Map;

/** Entity types, items, blocks and world features of the sailing module. */
public final class SailingRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, Skycraft.MODID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, Skycraft.MODID);
    public static final DeferredRegister<com.mojang.serialization.MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(BuiltInRegistries.FEATURE_TYPE, Skycraft.MODID);

    /** One entity type per ship (each has its own fixed hit box). */
    public static final Map<ShipType, Supplier<EntityType<ShipEntity>>> SHIPS = new EnumMap<>(ShipType.class);
    public static final Map<ShipType, Supplier<Item>> SHIP_ITEMS = new EnumMap<>(ShipType.class);

    static {
        for (ShipType t : ShipType.VALUES) {
            SHIPS.put(t, ENTITIES.register(t.id, () -> EntityType.Builder.<ShipEntity>of((type, level) -> new ShipEntity(type, level, t), MobCategory.MISC)
                    .sized(t.hitWidth(), t.height).clientTrackingRange(t == ShipType.ROWBOAT ? 10 : 16).updateInterval(2)
                    .build(net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.fromNamespaceAndPath(Skycraft.MODID, t.id)))));
            SHIP_ITEMS.put(t, ITEMS.register(t.id, () -> new ShipItem(t, com.skycraft.platform.SkyProperties.item(t.id).stacksTo(1))));
        }
    }

    public static final Supplier<Block> SHIPWRIGHT_BENCH = BLOCKS.register("shipwright_bench",
            () -> new ShipwrightBlock(com.skycraft.platform.SkyProperties.block("shipwright_bench").mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD)
                    .noOcclusion()));
    public static final Supplier<Item> SHIPWRIGHT_BENCH_ITEM = ITEMS.register("shipwright_bench",
            () -> new BlockItem(SHIPWRIGHT_BENCH.get(), com.skycraft.platform.SkyProperties.item("shipwright_bench")));

    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> SEA_WRECK = FEATURES.register("sea_wreck",
            () -> new SeaWreckFeature(NoneFeatureConfiguration.CODEC).codec());
    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> FLOTSAM = FEATURES.register("flotsam",
            () -> new FlotsamFeature(NoneFeatureConfiguration.CODEC).codec());

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
