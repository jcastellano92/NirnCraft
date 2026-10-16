package com.skycraft.seas;

import com.skycraft.Skycraft;
import com.skycraft.creatures.Bestiary;
import com.skycraft.creatures.entity.SkyBeast;
import com.skycraft.creatures.entity.SkyMonster;
import com.skycraft.seas.world.CoastSiteFeature;
import com.skycraft.seas.world.IsletFeature;
import com.skycraft.seas.world.KollopBlock;
import com.skycraft.seas.world.LairMarkerBlock;
import com.skycraft.seas.world.LairMarkerBlockEntity;
import com.skycraft.seas.world.SeaGardenFeature;
import com.skycraft.seas.world.SeaSiteFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registrations of the seas module. The sea creatures with bestiary specs go through the bestiary's registration
 * ({@link Bestiary#register}: attributes, spawn eggs and spawn rules come with it); the schooling fish, the kollop
 * shellfish, the lair markers and the world features are registered here.
 */
public final class SeasRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Skycraft.MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);

    // ---------------------------------------------------------------- the deep (bestiary creatures)
    public static final RegistryObject<EntityType<SkyMonster>> DREUGH = Bestiary.register(SeaSpecs.dreugh(), SkyMonster::new);
    public static final RegistryObject<EntityType<SkyMonster>> DREUGH_QUEEN = Bestiary.register(SeaSpecs.dreughQueen(), SkyMonster::new);
    public static final RegistryObject<EntityType<SkyMonster>> SEA_LEVIATHAN = Bestiary.register(SeaSpecs.seaLeviathan(), SkyMonster::new);
    public static final RegistryObject<EntityType<SkyMonster>> GIANT_SLAUGHTERFISH = Bestiary.register(SeaSpecs.giantSlaughterfish(), SkyMonster::new);
    public static final RegistryObject<EntityType<SkyBeast>> SEA_NETCH = Bestiary.register(SeaSpecs.seaNetch(), SkyBeast::new);
    public static final RegistryObject<EntityType<SkyBeast>> WHALE = Bestiary.register(SeaSpecs.whale(), SkyBeast::new);
    // ---------------------------------------------------------------- coasts, ice and skies
    public static final RegistryObject<EntityType<SkyMonster>> SEA_CRAB = Bestiary.register(SeaSpecs.seaCrab(), SkyMonster::new);
    public static final RegistryObject<EntityType<SkyBeast>> SEAL = Bestiary.register(SeaSpecs.seal(), SkyBeast::new);
    public static final RegistryObject<EntityType<SkyBeast>> HAWK = Bestiary.register(SeaSpecs.hawk(), SkyBeast::new);

    // ---------------------------------------------------------------- schooling fish
    public static final RegistryObject<EntityType<SchoolFishEntity>> RIVER_BETTY = fish("river_betty", 0.5f, 0.3f);
    public static final RegistryObject<EntityType<SchoolFishEntity>> ABECEAN_LONGFIN = fish("abecean_longfin", 0.6f, 0.35f);
    public static final RegistryObject<Item> RIVER_BETTY_EGG = ITEMS.register("river_betty_spawn_egg",
            () -> new ForgeSpawnEggItem(RIVER_BETTY, 0x7A8A6A, 0xC8B070, new Item.Properties()));
    public static final RegistryObject<Item> ABECEAN_LONGFIN_EGG = ITEMS.register("abecean_longfin_spawn_egg",
            () -> new ForgeSpawnEggItem(ABECEAN_LONGFIN, 0x4A7AA0, 0xE0C060, new Item.Properties()));

    // ---------------------------------------------------------------- blocks
    /** Kollops: the pearl-bearing shellfish of Morrowind's shallows (pearl diving). */
    public static final RegistryObject<Block> KOLLOP = BLOCKS.register("kollop_bed", () -> new KollopBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.TERRACOTTA_WHITE).strength(0.6f).sound(SoundType.BONE_BLOCK).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Item> KOLLOP_ITEM = ITEMS.register("kollop_bed", () -> new BlockItem(KOLLOP.get(), new Item.Properties()));
    /** Where a site's inhabitants wait to be placed when someone comes near (invisible, never obtainable). */
    public static final RegistryObject<Block> LAIR_MARKER = BLOCKS.register("lair_marker", () -> new LairMarkerBlock(BlockBehaviour.Properties.of()
            .noCollission().noOcclusion().noLootTable().strength(-1f, 3600000f).pushReaction(PushReaction.BLOCK)));
    public static final RegistryObject<BlockEntityType<LairMarkerBlockEntity>> LAIR_MARKER_BE = BLOCK_ENTITIES.register("lair_marker",
            () -> BlockEntityType.Builder.of(LairMarkerBlockEntity::new, LAIR_MARKER.get()).build(null));

    // ---------------------------------------------------------------- world features
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> SEA_SITE = FEATURES.register("sea_site",
            () -> new SeaSiteFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> SEA_GARDEN = FEATURES.register("sea_garden",
            () -> new SeaGardenFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> ISLET = FEATURES.register("islet",
            () -> new IsletFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> COAST_SITE = FEATURES.register("coast_site",
            () -> new CoastSiteFeature(NoneFeatureConfiguration.CODEC));

    private SeasRegistry() {}

    private static RegistryObject<EntityType<SchoolFishEntity>> fish(String id, float w, float h) {
        return ENTITIES.register(id, () -> EntityType.Builder.<SchoolFishEntity>of(SchoolFishEntity::new, MobCategory.WATER_AMBIENT)
                .sized(w, h).clientTrackingRange(6).build(Skycraft.MODID + ":" + id));
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

    private static void onSpawnPlacements(SpawnPlacementRegisterEvent event) {
        for (RegistryObject<EntityType<SchoolFishEntity>> f : java.util.List.of(RIVER_BETTY, ABECEAN_LONGFIN)) {
            event.register(f.get(), SpawnPlacements.Type.IN_WATER, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    WaterAnimal::checkSurfaceWaterAnimalSpawnRules, SpawnPlacementRegisterEvent.Operation.REPLACE);
        }
    }
}
