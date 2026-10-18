package com.skycraft.curse;

import com.skycraft.Skycraft;
import com.skycraft.curse.entity.DeathHoundEntity;
import com.skycraft.curse.entity.GargoyleEntity;
import com.skycraft.curse.entity.VampireLordEntity;
import com.skycraft.curse.entity.WerewolfEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Registry objects of the curse module: Sanguinare Vampiris, the curse creatures and their spawn eggs. */
public final class CurseRegistry {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, Skycraft.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Skycraft.MODID);

    /**
     * Sanguinare Vampiris: a disease (cured like the others: potion, shrine, priest) that turns its carrier into a vampire
     * after three days. The effect is only the visible marker; the infection time lives in {@code module("curse")}.
     */
    public static final Supplier<MobEffect> SANGUINARE = EFFECTS.register("sanguinare_vampiris", () -> new CurseEffect(0x6A0A14));
    /**
     * Sanies Lupinus: Stage 1 incubating lycanthropy contracted from werewolf attacks, curable like any disease
     * (Cure Disease potion, shrine, priest/healer) for three in-game days before turning the carrier into a werewolf.
     */
    public static final Supplier<MobEffect> SANIES_LUPINUS = EFFECTS.register("sanies_lupinus", () -> new CurseEffect(0x5C4428));
    /** Marker shown while a vampire is in direct sunlight (the penalties are applied by {@link Vampirism}). */
    public static final Supplier<MobEffect> SUN_DAMAGE = EFFECTS.register("sun_damage", () -> new CurseEffect(0xF0C060));

    public static final Supplier<EntityType<WerewolfEntity>> WEREWOLF = ENTITIES.register("werewolf",
            () -> EntityType.Builder.<WerewolfEntity>of(WerewolfEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.9f, 2.4f).clientTrackingRange(10).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse(Skycraft.MODID + ":werewolf"))));
    public static final Supplier<EntityType<VampireLordEntity>> VAMPIRE_LORD = ENTITIES.register("vampire_lord",
            () -> EntityType.Builder.<VampireLordEntity>of(VampireLordEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.9f, 2.6f).clientTrackingRange(10).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse(Skycraft.MODID + ":vampire_lord"))));
    public static final Supplier<EntityType<DeathHoundEntity>> DEATH_HOUND = ENTITIES.register("death_hound",
            () -> EntityType.Builder.<DeathHoundEntity>of(DeathHoundEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(0.9f, 1.0f).clientTrackingRange(8).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse(Skycraft.MODID + ":death_hound"))));
    public static final Supplier<EntityType<GargoyleEntity>> GARGOYLE = ENTITIES.register("gargoyle",
            () -> EntityType.Builder.<GargoyleEntity>of(GargoyleEntity::new, MobCategory.MONSTER).notInPeaceful()
                    .sized(1.0f, 2.3f).clientTrackingRange(10).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.Identifier.parse(Skycraft.MODID + ":gargoyle"))));

    static final List<Supplier<Item>> EGGS = new ArrayList<>();
    public static final Supplier<Item> WEREWOLF_EGG = egg("werewolf", WEREWOLF, 0x3A2E26, 0xB8A890);
    public static final Supplier<Item> VAMPIRE_LORD_EGG = egg("vampire_lord", VAMPIRE_LORD, 0x2A2A30, 0x8A1218);
    public static final Supplier<Item> DEATH_HOUND_EGG = egg("death_hound", DEATH_HOUND, 0x3A3632, 0xC01818);
    public static final Supplier<Item> GARGOYLE_EGG = egg("gargoyle", GARGOYLE, 0x6E6A64, 0x9A3A2A);

    private CurseRegistry() {}

    static void init(IEventBus modBus) {
        EFFECTS.register(modBus);
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
    }

    private static Supplier<Item> egg(String name, Supplier<? extends EntityType<? extends Mob>> type, int bg, int fg) {
        Supplier<Item> egg = ITEMS.register(name + "_spawn_egg", () -> new SpawnEggItem(com.skycraft.platform.SkyProperties.item(name + "_spawn_egg").spawnEgg(type.get())));
        EGGS.add(egg);
        return egg;
    }

    /** A harmful marker effect milk does not remove (like Skyrim's diseases). */
    public static class CurseEffect extends MobEffect {
        CurseEffect(int color) {
            super(MobEffectCategory.HARMFUL, color);
        }

    }
}
