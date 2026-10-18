package com.skycraft.creatures;

import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.BanditChiefEntity;
import com.skycraft.creatures.entity.BanditEntity;
import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.creatures.entity.DraugrDeathlordEntity;
import com.skycraft.creatures.entity.DraugrEntity;
import com.skycraft.creatures.entity.GiantEntity;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.creatures.entity.SkeeverEntity;
import com.skycraft.creatures.entity.TrollEntity;
import com.skycraft.creatures.entity.FalmerEntity;
import com.skycraft.creatures.entity.ForswornEntity;
import com.skycraft.creatures.entity.FrostbiteSpiderEntity;
import com.skycraft.creatures.entity.HagravenEntity;
import com.skycraft.creatures.entity.IceWraithEntity;
import com.skycraft.creatures.entity.NecromancerEntity;
import com.skycraft.creatures.entity.SprigganEntity;
import com.skycraft.creatures.entity.VampireEntity;
import com.skycraft.creatures.entity.WispmotherEntity;
import com.skycraft.creatures.world.BanditCampFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.skycraft.worldgen.NoneFeatureConfiguration;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

import java.util.function.Supplier;

/** Entity types, spawn eggs and world-gen features of the creatures module. Registry ids are a cross-module contract. */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Skycraft.MODID);
    public static final net.minecraft.network.syncher.EntityDataSerializer<net.minecraft.nbt.CompoundTag> CORPSE_BODY_SERIALIZER = new net.minecraft.network.syncher.EntityDataSerializer<>() {
        @Override public net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, net.minecraft.nbt.CompoundTag> codec() {
            return net.minecraft.network.codec.ByteBufCodecs.COMPOUND_TAG;
        }
        @Override public net.minecraft.nbt.CompoundTag copy(net.minecraft.nbt.CompoundTag value) { return value.copy(); }
    };
    private static final DeferredRegister<net.minecraft.network.syncher.EntityDataSerializer<?>> SERIALIZERS = DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, Skycraft.MODID);
    static { SERIALIZERS.register("corpse_body", () -> CORPSE_BODY_SERIALIZER); }
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Skycraft.MODID);
    public static final DeferredRegister<com.mojang.serialization.MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(Registries.FEATURE_TYPE, Skycraft.MODID);

    public static final Supplier<EntityType<BanditEntity>> BANDIT = ENTITIES.register("bandit",
            () -> EntityType.Builder.<BanditEntity>of(BanditEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.95f).clientTrackingRange(8).build(id("bandit")));
    public static final Supplier<EntityType<BanditChiefEntity>> BANDIT_CHIEF = ENTITIES.register("bandit_chief",
            () -> EntityType.Builder.<BanditChiefEntity>of(BanditChiefEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.95f).clientTrackingRange(8).build(id("bandit_chief")));
    public static final Supplier<EntityType<DraugrEntity>> DRAUGR = ENTITIES.register("draugr",
            () -> EntityType.Builder.<DraugrEntity>of(DraugrEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.95f).clientTrackingRange(8).build(id("draugr")));
    public static final Supplier<EntityType<DraugrDeathlordEntity>> DRAUGR_DEATHLORD = ENTITIES.register("draugr_deathlord",
            () -> EntityType.Builder.<DraugrDeathlordEntity>of(DraugrDeathlordEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.7f, 2.1f).clientTrackingRange(10).build(id("draugr_deathlord")));
    public static final Supplier<EntityType<SkeeverEntity>> SKEEVER = ENTITIES.register("skeever",
            () -> EntityType.Builder.<SkeeverEntity>of(SkeeverEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.7f, 0.55f).clientTrackingRange(8).build(id("skeever")));
    public static final Supplier<EntityType<TrollEntity>> TROLL = ENTITIES.register("troll",
            () -> EntityType.Builder.<TrollEntity>of(TrollEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(1.4f, 2.5f).clientTrackingRange(10).build(id("troll")));
    public static final Supplier<EntityType<GiantEntity>> GIANT = ENTITIES.register("giant",
            () -> EntityType.Builder.<GiantEntity>of(GiantEntity::new, MobCategory.MONSTER)
                    .sized(1.5f, 4.9f).clientTrackingRange(10).build(id("giant")));
    public static final Supplier<EntityType<DragonEntity>> DRAGON = ENTITIES.register("dragon",
            () -> EntityType.Builder.<DragonEntity>of(DragonEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(3.0f, 2.5f).clientTrackingRange(16).updateInterval(2).build(id("dragon")));
    public static final Supplier<EntityType<GuardEntity>> GUARD = ENTITIES.register("guard",
            () -> EntityType.Builder.<GuardEntity>of(GuardEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.95f).clientTrackingRange(10).build(id("guard")));
    public static final Supplier<EntityType<CorpseEntity>> CORPSE = ENTITIES.register("corpse",
            () -> EntityType.Builder.<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                    .sized(1.0f, 0.5f).fireImmune().clientTrackingRange(8).updateInterval(2).build(id("corpse")));

    public static final Supplier<EntityType<ForswornEntity>> FORSWORN = ENTITIES.register("forsworn",
            () -> EntityType.Builder.<ForswornEntity>of(ForswornEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.95f).clientTrackingRange(8).build(id("forsworn")));
    public static final Supplier<EntityType<NecromancerEntity>> NECROMANCER = ENTITIES.register("necromancer",
            () -> EntityType.Builder.<NecromancerEntity>of(NecromancerEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.95f).clientTrackingRange(8).build(id("necromancer")));
    public static final Supplier<EntityType<VampireEntity>> VAMPIRE = ENTITIES.register("vampire",
            () -> EntityType.Builder.<VampireEntity>of(VampireEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.95f).clientTrackingRange(8).build(id("vampire")));
    public static final Supplier<EntityType<FalmerEntity>> FALMER = ENTITIES.register("falmer",
            () -> EntityType.Builder.<FalmerEntity>of(FalmerEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.8f).clientTrackingRange(8).build(id("falmer")));
    public static final Supplier<EntityType<FrostbiteSpiderEntity>> FROSTBITE_SPIDER = ENTITIES.register("frostbite_spider",
            () -> EntityType.Builder.<FrostbiteSpiderEntity>of(FrostbiteSpiderEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(1.6f, 1.0f).clientTrackingRange(8).build(id("frostbite_spider")));
    public static final Supplier<EntityType<IceWraithEntity>> ICE_WRAITH = ENTITIES.register("ice_wraith",
            () -> EntityType.Builder.<IceWraithEntity>of(IceWraithEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.8f, 1.4f).clientTrackingRange(8).build(id("ice_wraith")));
    public static final Supplier<EntityType<SprigganEntity>> SPRIGGAN = ENTITIES.register("spriggan",
            () -> EntityType.Builder.<SprigganEntity>of(SprigganEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.95f).clientTrackingRange(8).build(id("spriggan")));
    public static final Supplier<EntityType<HagravenEntity>> HAGRAVEN = ENTITIES.register("hagraven",
            () -> EntityType.Builder.<HagravenEntity>of(HagravenEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 1.95f).clientTrackingRange(8).build(id("hagraven")));
    public static final Supplier<EntityType<WispmotherEntity>> WISPMOTHER = ENTITIES.register("wispmother",
            () -> EntityType.Builder.<WispmotherEntity>of(WispmotherEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.6f, 2.0f).clientTrackingRange(8).build(id("wispmother")));

    public static final Supplier<Item> BANDIT_EGG = egg("bandit", BANDIT, 0x5A3E2B, 0xB08A5A);
    public static final Supplier<Item> BANDIT_CHIEF_EGG = egg("bandit_chief", BANDIT_CHIEF, 0x3B2A1E, 0xC9C9C9);
    public static final Supplier<Item> DRAUGR_EGG = egg("draugr", DRAUGR, 0x5E6A70, 0x6FD3FF);
    public static final Supplier<Item> DRAUGR_DEATHLORD_EGG = egg("draugr_deathlord", DRAUGR_DEATHLORD, 0x2D3236, 0x3FB8FF);
    public static final Supplier<Item> SKEEVER_EGG = egg("skeever", SKEEVER, 0x6B5A4A, 0xD9A08C);
    public static final Supplier<Item> TROLL_EGG = egg("troll", TROLL, 0xC8C2B4, 0x5A4E44);
    public static final Supplier<Item> GIANT_EGG = egg("giant", GIANT, 0xD8C7B0, 0x7A5A3A);
    public static final Supplier<Item> DRAGON_EGG = egg("dragon", DRAGON, 0x5B4A32, 0xB89A5E);
    public static final Supplier<Item> GUARD_EGG = egg("guard", GUARD, 0xC8A23A, 0x8C8C8C);
    public static final Supplier<Item> FORSWORN_EGG = egg("forsworn", FORSWORN, 0x4A3020, 0x805030);
    public static final Supplier<Item> NECROMANCER_EGG = egg("necromancer", NECROMANCER, 0x1A1A24, 0x6A3A8A);
    public static final Supplier<Item> VAMPIRE_EGG = egg("vampire", VAMPIRE, 0x241414, 0x991B1B);
    public static final Supplier<Item> FALMER_EGG = egg("falmer", FALMER, 0x9E9080, 0x5A3E2B);
    public static final Supplier<Item> FROSTBITE_SPIDER_EGG = egg("frostbite_spider", FROSTBITE_SPIDER, 0x334455, 0x88CCFF);
    public static final Supplier<Item> ICE_WRAITH_EGG = egg("ice_wraith", ICE_WRAITH, 0xA0D8EF, 0xE0F7FA);
    public static final Supplier<Item> SPRIGGAN_EGG = egg("spriggan", SPRIGGAN, 0x4A3020, 0x44AA33);
    public static final Supplier<Item> HAGRAVEN_EGG = egg("hagraven", HAGRAVEN, 0x2B2B2B, 0x9E7030);
    public static final Supplier<Item> WISPMOTHER_EGG = egg("wispmother", WISPMOTHER, 0xD0E8F2, 0x66B2FF);

    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> BANDIT_CAMP = FEATURES.register("bandit_camp",
            () -> new BanditCampFeature(NoneFeatureConfiguration.CODEC).codec());

    private ModEntities() {}

    public static void init(IEventBus modBus) {
        SERIALIZERS.register(modBus);
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        FEATURES.register(modBus);
    }

    private static net.minecraft.resources.ResourceKey<EntityType<?>> id(String path) {
        return net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.fromNamespaceAndPath(Skycraft.MODID, path));
    }

    private static Supplier<Item> egg(String name, Supplier<? extends EntityType<? extends Mob>> type, int bg, int fg) {
        return ITEMS.register(name + "_spawn_egg", () -> new SpawnEggItem(com.skycraft.platform.SkyProperties.item(name + "_spawn_egg").spawnEgg(type.get())));
    }
}
