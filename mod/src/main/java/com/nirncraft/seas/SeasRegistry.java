package com.nirncraft.seas;

import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.Bestiary;
import com.nirncraft.creatures.entity.SkyBeast;
import com.nirncraft.creatures.entity.SkyMonster;
import com.nirncraft.seas.world.CoastSiteFeature;
import com.nirncraft.seas.world.IsletFeature;
import com.nirncraft.seas.world.KollopBlock;
import com.nirncraft.seas.world.LairMarkerBlock;
import com.nirncraft.seas.world.LairMarkerBlockEntity;
import com.nirncraft.seas.world.SeaGardenFeature;
import com.nirncraft.seas.world.SeaSiteFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.nirncraft.worldgen.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/**
 * Registrations of the seas module. The sea creatures with bestiary specs go through the bestiary's registration
 * ({@link Bestiary#register}: attributes, spawn eggs and spawn rules come with it); the schooling fish, the kollop
 * shellfish, the lair markers and the world features are registered here.
 */
public final class SeasRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, NirnCraft.MODID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, NirnCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<com.mojang.serialization.MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(BuiltInRegistries.FEATURE_TYPE, NirnCraft.MODID);

    // ---------------------------------------------------------------- the deep (bestiary creatures)
    public static final Supplier<EntityType<SkyMonster>> DREUGH = Bestiary.register(SeaSpecs.dreugh(), SkyMonster::new);
    public static final Supplier<EntityType<SkyMonster>> DREUGH_QUEEN = Bestiary.register(SeaSpecs.dreughQueen(), SkyMonster::new);
    public static final Supplier<EntityType<SkyMonster>> SEA_LEVIATHAN = Bestiary.register(SeaSpecs.seaLeviathan(), SkyMonster::new);
    public static final Supplier<EntityType<SkyMonster>> GIANT_SLAUGHTERFISH = Bestiary.register(SeaSpecs.giantSlaughterfish(), SkyMonster::new);
    public static final Supplier<EntityType<SkyBeast>> SEA_NETCH = Bestiary.register(SeaSpecs.seaNetch(), SkyBeast::new);
    public static final Supplier<EntityType<SkyBeast>> WHALE = Bestiary.register(SeaSpecs.whale(), SkyBeast::new);
    // ---------------------------------------------------------------- coasts, ice and skies
    public static final Supplier<EntityType<SkyMonster>> SEA_CRAB = Bestiary.register(SeaSpecs.seaCrab(), SkyMonster::new);
    public static final Supplier<EntityType<SkyBeast>> SEAL = Bestiary.register(SeaSpecs.seal(), SkyBeast::new);
    public static final Supplier<EntityType<SkyBeast>> HAWK = Bestiary.register(SeaSpecs.hawk(), SkyBeast::new);

    // ---------------------------------------------------------------- schooling fish
    public static final Supplier<EntityType<SchoolFishEntity>> RIVER_BETTY = fish("river_betty", 0.5f, 0.3f);
    public static final Supplier<EntityType<SchoolFishEntity>> ABECEAN_LONGFIN = fish("abecean_longfin", 0.6f, 0.35f);
    public static final Supplier<Item> RIVER_BETTY_EGG = ITEMS.register("river_betty_spawn_egg",
            key -> new SpawnEggItem(new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,key)).spawnEgg(RIVER_BETTY.get())));
    public static final Supplier<Item> ABECEAN_LONGFIN_EGG = ITEMS.register("abecean_longfin_spawn_egg",
            key -> new SpawnEggItem(new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,key)).spawnEgg(ABECEAN_LONGFIN.get())));

    // ---------------------------------------------------------------- blocks
    /** Kollops: the pearl-bearing shellfish of Morrowind's shallows (pearl diving). */
    public static final Supplier<Block> KOLLOP = BLOCKS.register("kollop_bed", key -> new KollopBlock(BlockBehaviour.Properties.of().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,key))
            .mapColor(MapColor.TERRACOTTA_WHITE).strength(0.6f).sound(SoundType.BONE_BLOCK).noOcclusion().pushReaction(PushReaction.POPPED)));
    public static final Supplier<Item> KOLLOP_ITEM = ITEMS.register("kollop_bed", key -> new BlockItem(KOLLOP.get(), new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,key))));
    /** Where a site's inhabitants wait to be placed when someone comes near (invisible, never obtainable). */
    public static final Supplier<Block> LAIR_MARKER = BLOCKS.register("lair_marker", key -> new LairMarkerBlock(BlockBehaviour.Properties.of().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,key))
            .noCollision().noOcclusion().noLootTable().strength(-1f, 3600000f).pushReaction(PushReaction.IMMOVEABLE)));
    public static final Supplier<BlockEntityType<LairMarkerBlockEntity>> LAIR_MARKER_BE = BLOCK_ENTITIES.register("lair_marker",
            () -> new BlockEntityType<>(LairMarkerBlockEntity::new, LAIR_MARKER.get()));

    // ---------------------------------------------------------------- world features
    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> SEA_SITE = FEATURES.register("sea_site",
            () -> new SeaSiteFeature(NoneFeatureConfiguration.CODEC).codec());
    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> SEA_GARDEN = FEATURES.register("sea_garden",
            () -> new SeaGardenFeature(NoneFeatureConfiguration.CODEC).codec());
    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> ISLET = FEATURES.register("islet",
            () -> new IsletFeature(NoneFeatureConfiguration.CODEC).codec());
    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> COAST_SITE = FEATURES.register("coast_site",
            () -> new CoastSiteFeature(NoneFeatureConfiguration.CODEC).codec());

    private SeasRegistry() {}

    private static Supplier<EntityType<SchoolFishEntity>> fish(String id, float w, float h) {
        return ENTITIES.register(id, () -> EntityType.Builder.<SchoolFishEntity>of(SchoolFishEntity::new, MobCategory.WATER_AMBIENT)
                .sized(w, h).clientTrackingRange(6).build(net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE,net.minecraft.resources.Identifier.fromNamespaceAndPath(NirnCraft.MODID,id))));
    }

    static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        FEATURES.register(modBus);
        modBus.addListener(SeasRegistry::onAttributes);
        modBus.addListener(SeasRegistry::onSpawnPlacements);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(RIVER_BETTY.get(), AbstractFish.createAttributes().build());
        event.put(ABECEAN_LONGFIN.get(), AbstractFish.createAttributes().build());
    }

    private static void onSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        for (Supplier<EntityType<SchoolFishEntity>> f : java.util.List.of(RIVER_BETTY, ABECEAN_LONGFIN)) {
            event.register(f.get(), net.minecraft.world.entity.SpawnPlacementTypes.IN_WATER, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    WaterAnimal::checkSurfaceWaterAnimalSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);
        }
    }
}
