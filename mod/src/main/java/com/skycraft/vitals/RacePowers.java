package com.skycraft.vitals;

import com.skycraft.core.Buffs;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/** Once-per-day racial greater powers (Khajiit Night Eye is a lesser power with a short cooldown). */
public final class RacePowers {
    private static final int DAY = 24000;

    private RacePowers() {}

    public static void use(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        Race race = data.getRace();
        if (race == null) return;
        long now = player.level().getGameTime();
        if (now < data.getPowerReadyAt()) {
            long hours = (data.getPowerReadyAt() - now) / 1000 + 1;
            Notifier.message(player, Component.translatable("message.skycraft.power_cooldown", race.powerName(), hours));
            return;
        }
        int seconds = 60;
        switch (race) {
            case NORD -> forEachEnemy(player, 12, mob -> {
                mob.setTarget(null);
                mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 30, 1));
                mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 30, 1));
                Vec3 away = mob.position().subtract(player.position()).normalize().scale(1.2);
                mob.push(away.x, 0.3, away.z);
            });
            case IMPERIAL -> forEachEnemy(player, 16, mob -> {
                mob.setTarget(null);
                mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 60, 3));
            });
            case BRETON -> player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * seconds, 3));
            case REDGUARD -> Buffs.apply(player, "adrenaline_rush", 20 * seconds);
            case ALTMER -> Buffs.apply(player, "highborn", 20 * seconds);
            case BOSMER -> {
                for (Animal animal : player.level().getEntitiesOfClass(Animal.class, player.getBoundingBox().inflate(16))) {
                    animal.setTarget(null);
                    animal.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 30, 1));
                }
                forEachEnemy(player, 16, mob -> {
                    if (mob instanceof net.minecraft.world.entity.animal.Wolf || mob instanceof net.minecraft.world.entity.animal.PolarBear) mob.setTarget(null);
                });
            }
            case DUNMER -> Buffs.apply(player, "fire_cloak", 20 * seconds);
            case ORSIMER -> {
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * seconds, 1));
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * seconds, 1));
            }
            case KHAJIIT -> player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * seconds, 0, false, false, true));
            case ARGONIAN -> Buffs.apply(player, "histskin", 20 * seconds);
        }
        data.setPowerReadyAt(now + (race == Race.KHAJIIT ? 200 : DAY));
        player.level().playSound(null, player.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1f, 0.8f);
        Notifier.message(player, Component.translatable("message.skycraft.power_used", race.powerName()));
    }

    private static void forEachEnemy(ServerPlayer player, double radius, java.util.function.Consumer<Mob> action) {
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(radius), m -> m instanceof Enemy || m.getTarget() == player)) {
            action.accept(mob);
        }
    }

    /** Dunmer "Ancestor's Wrath" fire cloak tick, called from the combat handler each second. */
    public static void tickFireCloak(ServerPlayer player) {
        if (!Buffs.active(player, "fire_cloak")) return;
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(3), m -> m instanceof Enemy)) {
            mob.setSecondsOnFire(2);
            com.skycraft.combat.CombatHandler.secondaryHurt(mob, player.damageSources().indirectMagic(player, player), 2f);
        }
    }
}
