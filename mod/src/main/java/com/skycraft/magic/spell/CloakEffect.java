package com.skycraft.magic.spell;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * Flame, Frost and Lightning Cloak and Stendarr's Aura: while the effect lasts the wearer burns nearby enemies twice a
 * second ({@link DestructionSpells#cloakPulse}).
 */
public class CloakEffect extends MagicEffect {
    private final Element element;

    public CloakEffect(int color, Element element) {
        super(MobEffectCategory.BENEFICIAL, color);
        this.element = element;
    }

    @Override
    public boolean applyEffectTick(net.minecraft.server.level.ServerLevel level, LivingEntity entity, int amplifier) {
        if (!entity.level().isClientSide() && entity instanceof ServerPlayer p) DestructionSpells.cloakPulse(p, element);
        return true;
        }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % 10 == 0;
    }
}
