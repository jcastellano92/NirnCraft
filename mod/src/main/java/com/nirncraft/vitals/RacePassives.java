package com.nirncraft.vitals;

import com.nirncraft.NirnCraft;
import com.nirncraft.combat.CombatHandler;
import com.nirncraft.core.Buffs;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.Race;
import com.nirncraft.core.SkyData;
import com.nirncraft.magic.MagicDamage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * The always-on racial abilities that no other system applies: water breathing (Argonians, Maormer), natural leaping
 * (Tang Mo, Lilmothiit), light falls, wall-climbing's fall reset (the climb itself is client movement, see
 * {@code client.RaceMovement}), the Tsaesci venom bite, and the effects of three greater powers: Berserker Rage
 * (double damage dealt, half taken), Dragonskin (half of every spell absorbed as magicka) and Tonal Resonance
 * (Dwemer automatons ignore you). Calmed mobs ({@link RacePowers#calm}) can't target players.
 *
 * <p>Resistances, claws, attribute bonuses, disease resistance, shout recharge and Imperial luck are applied where
 * those systems live, from the values in {@link Race}.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class RacePassives {
    private RacePassives() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        Race race = SkyData.get(player).getRace();
        if (race == null) return;
        if (race.climbs() && player.horizontalCollision && !player.onGround()) player.resetFallDistance();
        if (player.tickCount % 20 != 0) return;
        if (race.waterBreathing() && (player.isInWater() || player.isEyeInFluid(FluidTags.WATER))) {
            passive(player, MobEffects.WATER_BREATHING, 0);
        }
        if (race.leap() >= 0 && !player.isShiftKeyDown()) passive(player, MobEffects.JUMP_BOOST, race.leap());
    }

    /** A hidden, short, constantly refreshed effect (no icon or particles; gone shortly after the condition ends). */
    private static void passive(ServerPlayer player, net.minecraft.core.Holder<MobEffect> effect, int amplifier) {
        MobEffectInstance cur = player.getEffect(com.nirncraft.platform.Effects.holder(effect));
        // never cut short a stronger or longer effect from a potion or spell
        if (cur != null && (cur.getAmplifier() > amplifier || cur.getDuration() > 60 && cur.isVisible())) return;
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(effect), 50, amplifier, true, false, false));
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) return;
        Race race = SkyData.get(player).getRace();
        if (race != null && race.fallDamage() < 1f) event.setDamageMultiplier(event.getDamageMultiplier() * race.fallDamage());
    }

    /** After the combat handler (which runs at HIGH priority) has settled the amount. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;
        DamageSource source = event.getSource();
        float amount = event.getAmount();
        if (source.getEntity() instanceof ServerPlayer attacker && attacker != target) {
            // attacking a calmed creature breaks the calm
            RacePowers.endCalm(target);
            if (Buffs.active(attacker, "berserker_rage")) amount *= 2f;
            Race race = SkyData.get(attacker).getRace();
            if (race == Race.TSAESCI && source.getDirectEntity() == attacker && source.is(DamageTypes.PLAYER_ATTACK)
                    && attacker.getMainHandItem().isEmpty()) {
                target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.POISON), 20 * 4, 0), attacker);
            }
        }
        if (target instanceof ServerPlayer player) {
            if (Buffs.active(player, "berserker_rage")) amount *= 0.5f;
            if (Buffs.active(player, "dragonskin") && MagicDamage.isWardable(source)) {
                float absorbed = amount * 0.5f;
                amount -= absorbed;
                PlayerData data = SkyData.get(player);
                data.setMagicka(data.getMagicka() + absorbed * CombatHandler.SKYRIM_SCALE);
            }
        }
        if (amount != event.getAmount()) event.setAmount(amount);
    }

    /** Calmed mobs won't pick a player; Dwemer automatons leave a tonally attuned player alone. */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!(event.getNewAboutToBeSetTarget() instanceof Player player) || !(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        if (RacePowers.calmed(mob, mob.level().getGameTime())) {
            event.setCanceled(true);
            return;
        }
        if (mob instanceof com.nirncraft.dungeons.entity.Automaton && Buffs.active(player, "tonal_resonance")) event.setCanceled(true);
    }
}
