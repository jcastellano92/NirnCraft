package com.nirncraft.quest.party;

import com.nirncraft.NirnCraft;
import com.nirncraft.magic.MagicDamage;
import com.nirncraft.quest.QuestConfig;
import com.nirncraft.quest.Quests;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * No friendly fire between party members (direct melee weapons, punching, arrows, or direct spells).
 * Indirect accidental environmental hazards (lightning traveling through water, standing in fire or lava)
 * remain possible.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class PartyEvents {
    private PartyEvents() {}

    /** Prevents swinging at, punching, or melee-attacking party members. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlayerAttack(AttackEntityEvent event) {
        if (QuestConfig.FRIENDLY_FIRE.get()) return;
        if (event.getTarget() instanceof Player target && event.getEntity() instanceof ServerPlayer attacker) {
            if (attacker != target && target instanceof ServerPlayer spTarget) {
                if (Parties.sameParty(attacker.level().getServer(), attacker, spTarget)) {
                    event.setCanceled(true);
                }
            }
        }
    }

    /** Checks whether damage is an accidental indirect environmental effect. */
    private static boolean isIndirectEnvironmental(DamageSource source, ServerPlayer victim) {
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || source.is(DamageTypes.LAVA)
                || source.is(DamageTypes.HOT_FLOOR)) {
            return true;
        }
        if (source.is(DamageTypes.LIGHTNING_BOLT)) {
            return true;
        }
        // Lightning / shock traveling through water:
        if ((victim.isInWaterOrRain() || victim.level().getBlockState(victim.blockPosition()).is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)) && source.is(MagicDamage.SHOCK)) {
            return true;
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer target) || QuestConfig.FRIENDLY_FIRE.get()) return;
        ServerPlayer attacker = Quests.asPlayer(target.level().getServer(), event.getSource().getEntity());
        if (attacker == null || attacker == target) return;
        if (Parties.sameParty(target.level().getServer(), attacker, target)) {
            if (isIndirectEnvironmental(event.getSource(), target)) return;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHurt(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer target) || QuestConfig.FRIENDLY_FIRE.get()) return;
        ServerPlayer attacker = Quests.asPlayer(target.level().getServer(), event.getSource().getEntity());
        if (attacker == null || attacker == target) return;
        if (Parties.sameParty(target.level().getServer(), attacker, target)) {
            if (isIndirectEnvironmental(event.getSource(), target)) return;
            event.setCanceled(true);
        }
    }
}

