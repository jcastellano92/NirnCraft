package com.nirncraft.registry;

import com.nirncraft.NirnCraft;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/** Shared status effects. */
public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(BuiltInRegistries.MOB_EFFECT, NirnCraft.MODID);

    /** +10% skill XP after sleeping in a bed (Skyrim "Well Rested"). */
    public static final Supplier<MobEffect> WELL_RESTED = EFFECTS.register("well_rested",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0xE8D9A0));
    /** +15% skill XP after sleeping next to your spouse. */
    public static final Supplier<MobEffect> LOVERS_COMFORT = EFFECTS.register("lovers_comfort",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0xF08AB0));
    /** Cannot move or attack. */
    public static final Supplier<MobEffect> PARALYSIS = EFFECTS.register("paralysis",
            () -> new SimpleEffect(MobEffectCategory.HARMFUL, 0x6A6A6A)
                    .addAttributeModifier(Attributes.MOVEMENT_SPEED, net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "effect_3c1b7c7e-7d5e-4b4d-9a54-7c1f0b5a8e01"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                    .addAttributeModifier(Attributes.ATTACK_SPEED, net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "effect_3c1b7c7e-7d5e-4b4d-9a54-7c1f0b5a8e02"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    /** Staggered: briefly slowed and unable to attack (power attacks, shouts). */
    public static final Supplier<MobEffect> STAGGER = EFFECTS.register("stagger",
            () -> new SimpleEffect(MobEffectCategory.HARMFUL, 0xA0A0A0)
                    .addAttributeModifier(Attributes.MOVEMENT_SPEED, net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "effect_3c1b7c7e-7d5e-4b4d-9a54-7c1f0b5a8e03"), -0.6, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    /** Bleeding from axe perks: handled as a damage-over-time by the combat handler. */
    public static final Supplier<MobEffect> BLEEDING = EFFECTS.register("bleeding",
            () -> new SimpleEffect(MobEffectCategory.HARMFUL, 0x8A0303));

    private ModEffects() {}

    public static void init(IEventBus modBus) {
        EFFECTS.register(modBus);
    }

    public static class SimpleEffect extends MobEffect {
        public SimpleEffect(MobEffectCategory category, int color) {
            super(category, color);
        }

        @Override
        public boolean applyEffectTick(net.minecraft.server.level.ServerLevel serverLevel, net.minecraft.world.entity.LivingEntity entity, int amplifier) {
            if (this == BLEEDING.get()) {
                com.nirncraft.platform.Effects.hurt(entity, entity.damageSources().magic(), 0.5f * (amplifier + 1));
            }
            return true;
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
            return this == BLEEDING.get() && duration % 20 == 0;
        }
    }
}
