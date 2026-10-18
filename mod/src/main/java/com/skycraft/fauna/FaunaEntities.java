package com.skycraft.fauna;

import com.skycraft.Skycraft;
import com.skycraft.fauna.entity.BearEntity;
import com.skycraft.fauna.entity.DeerEntity;
import com.skycraft.fauna.entity.ElkEntity;
import com.skycraft.fauna.entity.HorkerEntity;
import com.skycraft.fauna.entity.InsectEntity;
import com.skycraft.fauna.entity.MammothEntity;
import com.skycraft.fauna.entity.MudcrabEntity;
import com.skycraft.fauna.entity.SabreCatEntity;
import com.skycraft.fauna.entity.SlaughterfishEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

import java.util.function.Supplier;

/** Entity types and spawn eggs of the fauna module: Skyrim wildlife and catchable insects. */
public final class FaunaEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Skycraft.MODID);

    // ---------------------------------------------------------------- wildlife
    public static final Supplier<EntityType<DeerEntity>> DEER = ENTITIES.register("deer",
            () -> EntityType.Builder.<DeerEntity>of(DeerEntity::new, MobCategory.CREATURE)
                    .sized(0.9f, 1.5f).clientTrackingRange(10).build(id("deer")));
    public static final Supplier<EntityType<ElkEntity>> ELK = ENTITIES.register("elk",
            () -> EntityType.Builder.<ElkEntity>of(ElkEntity::new, MobCategory.CREATURE)
                    .sized(1.2f, 1.95f).clientTrackingRange(10).build(id("elk")));
    public static final Supplier<EntityType<SabreCatEntity>> SABRE_CAT = ENTITIES.register("sabre_cat",
            () -> EntityType.Builder.<SabreCatEntity>of(SabreCatEntity::new, MobCategory.CREATURE)
                    .sized(1.1f, 1.25f).clientTrackingRange(10).build(id("sabre_cat")));
    public static final Supplier<EntityType<HorkerEntity>> HORKER = ENTITIES.register("horker",
            () -> EntityType.Builder.<HorkerEntity>of(HorkerEntity::new, MobCategory.CREATURE)
                    .sized(1.5f, 1.2f).clientTrackingRange(10).build(id("horker")));
    public static final Supplier<EntityType<MudcrabEntity>> MUDCRAB = ENTITIES.register("mudcrab",
            () -> EntityType.Builder.<MudcrabEntity>of(MudcrabEntity::new, MobCategory.CREATURE)
                    .sized(0.8f, 0.45f).clientTrackingRange(8).build(id("mudcrab")));
    public static final Supplier<EntityType<MammothEntity>> MAMMOTH = ENTITIES.register("mammoth",
            () -> EntityType.Builder.<MammothEntity>of(MammothEntity::new, MobCategory.CREATURE)
                    .sized(2.4f, 3.6f).clientTrackingRange(12).build(id("mammoth")));
    public static final Supplier<EntityType<BearEntity>> BEAR = ENTITIES.register("bear",
            () -> EntityType.Builder.<BearEntity>of(BearEntity::new, MobCategory.CREATURE)
                    .sized(1.3f, 1.4f).clientTrackingRange(10).build(id("bear")));
    public static final Supplier<EntityType<SlaughterfishEntity>> SLAUGHTERFISH = ENTITIES.register("slaughterfish",
            () -> EntityType.Builder.<SlaughterfishEntity>of(SlaughterfishEntity::new, MobCategory.WATER_CREATURE)
                    .sized(0.7f, 0.34f).clientTrackingRange(6).build(id("slaughterfish")));

    // ---------------------------------------------------------------- insects (spawned by InsectSpawner, not biomes)
    public static final Supplier<EntityType<InsectEntity>> BUTTERFLY = insect("butterfly", InsectEntity.Kind.BUTTERFLY, 0.4f);
    public static final Supplier<EntityType<InsectEntity>> DRAGONFLY = insect("dragonfly", InsectEntity.Kind.DRAGONFLY, 0.45f);
    public static final Supplier<EntityType<InsectEntity>> TORCHBUG = insect("torchbug", InsectEntity.Kind.TORCHBUG, 0.35f);
    public static final Supplier<EntityType<InsectEntity>> MOTH = insect("moth", InsectEntity.Kind.MOTH, 0.45f);

    // ---------------------------------------------------------------- spawn eggs
    public static final Supplier<Item> DEER_EGG = egg("deer", DEER, 0x8A5A32, 0xE8D6B8);
    public static final Supplier<Item> ELK_EGG = egg("elk", ELK, 0x6A4A2E, 0xD8C8A0);
    public static final Supplier<Item> SABRE_CAT_EGG = egg("sabre_cat", SABRE_CAT, 0xC8924A, 0x3A2A1A);
    public static final Supplier<Item> HORKER_EGG = egg("horker", HORKER, 0x6A5A50, 0xEAE0C8);
    public static final Supplier<Item> MUDCRAB_EGG = egg("mudcrab", MUDCRAB, 0x5A6A4A, 0xB89A60);
    public static final Supplier<Item> MAMMOTH_EGG = egg("mammoth", MAMMOTH, 0x4A3222, 0xF0E6D0);
    public static final Supplier<Item> BEAR_EGG = egg("bear", BEAR, 0x5A3A22, 0x2A1A10);
    public static final Supplier<Item> SLAUGHTERFISH_EGG = egg("slaughterfish", SLAUGHTERFISH, 0x4A5A4A, 0xC83A2A);
    public static final Supplier<Item> BUTTERFLY_EGG = egg("butterfly", BUTTERFLY, 0xF08A20, 0x1A1A1A);
    public static final Supplier<Item> DRAGONFLY_EGG = egg("dragonfly", DRAGONFLY, 0x2A6AD8, 0xC8E8F8);
    public static final Supplier<Item> TORCHBUG_EGG = egg("torchbug", TORCHBUG, 0x3A2A1A, 0xF8E060);
    public static final Supplier<Item> MOTH_EGG = egg("moth", MOTH, 0xB8F0C0, 0x6A9A70);

    private FaunaEntities() {}

    public static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
    }

    private static net.minecraft.resources.ResourceKey<EntityType<?>> id(String path) {
        return net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.fromNamespaceAndPath(Skycraft.MODID, path));
    }

    private static Supplier<EntityType<InsectEntity>> insect(String name, InsectEntity.Kind kind, float width) {
        return ENTITIES.register(name, () -> EntityType.Builder.<InsectEntity>of((type, level) -> new InsectEntity(type, level, kind), MobCategory.AMBIENT)
                .sized(width, 0.3f).clientTrackingRange(6).build(id(name)));
    }

    private static Supplier<Item> egg(String name, Supplier<? extends EntityType<? extends Mob>> type, int bg, int fg) {
        return ITEMS.register(name + "_spawn_egg", () -> new SpawnEggItem(com.skycraft.platform.SkyProperties.item(name + "_spawn_egg").spawnEgg(type.get())));
    }
}
