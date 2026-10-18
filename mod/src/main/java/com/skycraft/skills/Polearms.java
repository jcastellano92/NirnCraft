package com.skycraft.skills;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.item.PolearmItem;
import com.skycraft.arsenal.item.PolearmKind;
import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.magic.Targeting;
import com.skycraft.perk.Perks;
import com.skycraft.registry.ModEffects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.UUID;

/**
 * Polearms perks (spears, halberds, glaives). The skill's damage bonus and XP are applied by the combat handler like
 * One- and Two-Handed (Lancer is its "Armsman"); the rest lives here: Long Reach, Impale (bleeding), Set Against
 * Charge, Sweeping Arc (halberds and glaives), Phalanx, Pinning Strike (spears) and Skewer.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Polearms {
    private static final net.minecraft.resources.Identifier REACH_ID = net.minecraft.resources.Identifier.parse("skycraft:modifier_7d4a3c1e-5b2f-4e8a-9c6d-1f0e2a3b4c5e");
    private static final ThreadLocal<Boolean> SWEEPING = ThreadLocal.withInitial(() -> false);

    private Polearms() {}

    public static boolean isPolearm(ItemStack stack) {
        return stack.getItem() instanceof PolearmItem;
    }

    /** Long Reach: +0.5 blocks with a polearm in hand. */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 10 != 0) return;
        AttributeInstance reach = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE);
        if (reach == null) return;
        boolean want = isPolearm(p.getMainHandItem()) && Perks.has(p, "polearm.long_reach");
        boolean has = reach.getModifier(REACH_ID) != null;
        if (want && !has) reach.addTransientModifier(new AttributeModifier(REACH_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        else if (!want && has) reach.removeModifier(REACH_ID);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        // Phalanx: a polearm held out in front turns aside a fifth of melee blows from the front.
        if (victim instanceof ServerPlayer vp && isPolearm(vp.getMainHandItem()) && Perks.has(vp, "polearm.phalanx")
                && event.getSource().getDirectEntity() instanceof LivingEntity melee) {
            Vec3 to = melee.position().subtract(vp.position()).normalize();
            if (to.dot(vp.getViewVector(1f)) > 0.3) event.setAmount(event.getAmount() * 0.8f);
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer p) || event.getSource().getDirectEntity() != p) return;
        ItemStack weapon = p.getMainHandItem();
        if (!(weapon.getItem() instanceof PolearmItem polearm)) return;
        PlayerData data = SkyData.get(p);
        var rnd = p.getRandom();
        float amount = event.getAmount();
        boolean boss = victim.getType().builtInRegistryHolder().is(CombatHandler.BOSSES);

        // Set Against Charge: +50% against something running at you.
        if (Perks.has(data, "polearm.set_against_charge")) {
            Vec3 toward = p.position().subtract(victim.position()).normalize();
            if (victim.getDeltaMovement().dot(toward) > 0.05) amount *= 1.5f;
        }
        // Impale: 10% per rank to make the target bleed.
        int impale = Perks.rank(data, "polearm.impale");
        if (impale > 0 && rnd.nextFloat() < 0.1f * impale) {
            victim.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(ModEffects.BLEEDING.get()), 100, 0));
        }
        // Pinning Strike: spears pin the target in place for a moment.
        if (polearm.kind == PolearmKind.SPEAR && Perks.has(data, "polearm.pinning_strike") && !boss) {
            victim.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 40, 3));
        }
        // Skewer: 15% to paralyze for two seconds.
        if (Perks.has(data, "polearm.skewer") && !boss && rnd.nextFloat() < 0.15f) {
            victim.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), 40));
            Notifier.message(p, Component.translatable("message.skycraft.skewered"));
        }
        event.setAmount(amount);

        // Sweeping Arc: halberds and glaives cut through up to two more enemies in front for half damage.
        if (polearm.kind.sweeps() && Perks.has(data, "polearm.sweeping_arc") && !SWEEPING.get()) {
            SWEEPING.set(true);
            try {
                int n = 0;
                for (LivingEntity e : Targeting.cone(p, 3.5 + polearm.kind.reach, 0.5, e -> e != victim && Targeting.canHarm(p, e))) {
                    com.skycraft.platform.Effects.hurt(e, p.damageSources().playerAttack(p), amount * 0.5f);
                    if (++n >= 2) break;
                }
            } finally {
                SWEEPING.set(false);
            }
        }
    }
}
