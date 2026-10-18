package com.skycraft.platform;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;

/** Explicit holders at native effect boundaries; registered custom effects retain their registry identity. */
public final class Effects {
    private Effects() {}
    public static Holder<MobEffect> holder(MobEffect effect) { return BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect); }
    public static Holder<MobEffect> holder(Holder<MobEffect> effect) { return effect; }
    public static boolean isUndead(LivingEntity entity) { return entity.getType().builtInRegistryHolder().is(net.minecraft.tags.EntityTypeTags.UNDEAD); }
    public static boolean isArthropod(LivingEntity entity) { return entity.getType().builtInRegistryHolder().is(net.minecraft.tags.EntityTypeTags.ARTHROPOD); }
    /** Native authoritative hurt result; client-only invocation has no damage side effects. */
    public static boolean hurt(net.minecraft.world.entity.Entity entity, net.minecraft.world.damagesource.DamageSource source, float amount) {
        return entity.level() instanceof net.minecraft.server.level.ServerLevel level && entity.hurtServer(level, source, amount);
    }
}

