package com.skycraft.vitals;

import com.skycraft.core.Buffs;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/**
 * Once-per-day racial greater powers (Khajiit Night Eye is a lesser power with a short cooldown). Lasting effects
 * that aren't vanilla mob effects are {@link Buffs} read elsewhere: highborn, adrenaline rush and histskin by
 * {@link Vitals}, the fire cloak by the combat handler, berserker rage, dragonskin and tonal resonance by
 * {@link RacePassives}. Calmed creatures ({@link #calm}) won't pick a player as target until it wears off.
 */
public final class RacePowers {
    /** Persistent-data key on a mob: game time until which it won't target players. */
    public static final String CALM_KEY = "skycraft_calm_until";

    private RacePowers() {}

    public static void use(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        Race race = data.getRace();
        if (race == null) return;
        long now = player.level().getGameTime();
        if (now < data.getPowerReadyAt()) {
            String hours = com.skycraft.SkyConfig.formatTicks(data.getPowerReadyAt() - now);
            Notifier.message(player, Component.translatable("message.skycraft.power_cooldown", race.powerName(), hours));
            return;
        }
        int seconds = 60;
        SoundEvent sound = SoundEvents.EVOKER_CAST_SPELL;
        switch (race) {
            case NORD -> {
                // Battle Cry: enemies flee in terror
                forEachEnemy(player, 12, mob -> {
                    calm(mob, now, 20 * 30);
                    mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 30, 1));
                    mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 30, 1));
                    push(player, mob, 1.2);
                });
                sound = SoundEvents.RAVAGER_ROAR;
            }
            case IMPERIAL -> forEachEnemy(player, 16, mob -> {
                calm(mob, now, 20 * 60);
                mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 60, 1));
            });
            case BRETON -> Buffs.apply(player, "dragonskin", 20 * seconds);
            case REDGUARD -> Buffs.apply(player, "adrenaline_rush", 20 * seconds);
            case ALTMER -> Buffs.apply(player, "highborn", 20 * seconds);
            case BOSMER -> {
                // Command Animal: the beasts around become friends for a while
                for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16), RacePowers::isBeast)) {
                    calm(mob, now, 20 * 60);
                    mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 30, 1));
                }
            }
            case DUNMER -> Buffs.apply(player, "fire_cloak", 20 * seconds);
            case ORSIMER -> {
                Buffs.apply(player, "berserker_rage", 20 * seconds);
                sound = SoundEvents.RAVAGER_ROAR;
            }
            case KHAJIIT -> self(player, MobEffects.NIGHT_VISION, seconds, 0);
            case ARGONIAN -> Buffs.apply(player, "histskin", 20 * seconds);
            case MAORMER -> {
                self(player, MobEffects.INVISIBILITY, seconds, 0);
                self(player, MobEffects.WATER_BREATHING, seconds, 0);
                self(player, MobEffects.DOLPHINS_GRACE, seconds, 0);
                forEachEnemy(player, 16, mob -> calm(mob, now, 20 * 5));
            }
            case REACHMAN -> {
                self(player, MobEffects.DAMAGE_BOOST, seconds, 0);
                self(player, MobEffects.DAMAGE_RESISTANCE, seconds, 0);
                self(player, MobEffects.REGENERATION, seconds, 0);
                self(player, MobEffects.ABSORPTION, seconds, 1);
                sound = SoundEvents.WARDEN_HEARTBEAT;
            }
            case OHMES_RAHT -> {
                self(player, MobEffects.INVISIBILITY, 30, 0);
                self(player, MobEffects.NIGHT_VISION, seconds, 0);
                forEachEnemy(player, 16, mob -> calm(mob, now, 20 * 5));
            }
            case LILMOTHIIT -> {
                forEachEnemy(player, 16, mob -> calm(mob, now, 20 * 20));
                self(player, MobEffects.INVISIBILITY, 20, 0);
                self(player, MobEffects.MOVEMENT_SPEED, 20, 1);
                sound = SoundEvents.FOX_SCREECH;
            }
            case IMGA -> {
                forEachEnemy(player, 12, mob -> {
                    mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 30, 1));
                    mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 30, 0));
                    push(player, mob, 0.6);
                });
                self(player, MobEffects.DAMAGE_BOOST, 30, 0);
                self(player, MobEffects.DAMAGE_RESISTANCE, 30, 0);
                sound = SoundEvents.RAVAGER_STUNNED;
            }
            case TSAESCI -> {
                forEachEnemy(player, 12, mob -> {
                    mob.setTarget(null);
                    mob.addEffect(new MobEffectInstance(com.skycraft.registry.ModEffects.PARALYSIS.get(), 20 * 6, 0));
                    mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 20, 0));
                });
                sound = SoundEvents.ELDER_GUARDIAN_CURSE;
            }
            case KA_PO_TUN -> {
                forEachEnemy(player, 10, mob -> {
                    calm(mob, now, 20 * 10);
                    mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 30, 1));
                    push(player, mob, 1.8);
                    com.skycraft.combat.CombatHandler.secondaryHurt(mob, player.damageSources().mobAttack(player), 4f);
                });
                sound = SoundEvents.RAVAGER_ROAR;
            }
            case TANG_MO -> {
                self(player, MobEffects.DIG_SPEED, seconds, 1);
                self(player, MobEffects.DAMAGE_BOOST, seconds, 0);
                self(player, MobEffects.MOVEMENT_SPEED, seconds, 0);
            }
            case ATMORAN -> {
                // the Voice is ready again at once
                com.skycraft.magic.MagicData.setShoutCooldown(player, now, 0);
                self(player, MobEffects.DAMAGE_RESISTANCE, seconds, 0);
                sound = SoundEvents.ELDER_GUARDIAN_AMBIENT;
            }
            case FALMER -> {
                for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                        m -> m.getMobType() == MobType.UNDEAD)) {
                    mob.setSecondsOnFire(5);
                    com.skycraft.combat.CombatHandler.secondaryHurt(mob, player.damageSources().indirectMagic(player, player), 8f);
                }
                forEachEnemy(player, 16, mob -> mob.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 8, 0)));
                self(player, MobEffects.REGENERATION, 10, 1);
                sound = SoundEvents.BEACON_ACTIVATE;
            }
            case DWEMER -> {
                Buffs.apply(player, "tonal_resonance", 20 * 120);
                for (Mob mob : player.level().getEntitiesOfClass(com.skycraft.dungeons.entity.Automaton.class, player.getBoundingBox().inflate(32),
                        m -> m.getTarget() == player)) {
                    mob.setTarget(null);
                }
                sound = SoundEvents.AMETHYST_BLOCK_RESONATE;
            }
            case AYLEID -> {
                data.setMagicka(data.maxMagicka());
                forEachEnemy(player, 16, mob -> {
                    mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 30, 0));
                    mob.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 4, 0));
                });
                self(player, MobEffects.NIGHT_VISION, seconds, 0);
                sound = SoundEvents.BEACON_POWER_SELECT;
            }
            case KOTHRINGI -> {
                self(player, MobEffects.DAMAGE_RESISTANCE, seconds, 1);
                forEachEnemy(player, 8, mob -> mob.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 8, 0)));
                sound = SoundEvents.AMETHYST_BLOCK_CHIME;
            }
        }
        data.setPowerReadyAt(now + race.powerCooldown(data.getLevel()));
        player.level().playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1f, 0.8f * race.voicePitch);
        Notifier.message(player, Component.translatable("message.skycraft.power_used", race.powerName()));
    }

    private static void self(ServerPlayer player, MobEffect effect, int seconds, int amplifier) {
        player.addEffect(new MobEffectInstance(effect, 20 * seconds, amplifier, false, false, true));
    }

    private static void push(ServerPlayer player, Mob mob, double strength) {
        Vec3 away = mob.position().subtract(player.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) return;
        away = away.normalize().scale(strength);
        mob.push(away.x, 0.3, away.z);
        mob.hurtMarked = true;
    }

    private static void forEachEnemy(ServerPlayer player, double radius, Consumer<Mob> action) {
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(radius), m -> m instanceof Enemy || m.getTarget() == player)) {
            action.accept(mob);
        }
    }

    /** Animals and wild beasts (vanilla animals, Skycraft fauna), not people, undead or constructs. */
    private static boolean isBeast(Mob mob) {
        if (mob.getMobType() == MobType.UNDEAD || mob instanceof com.skycraft.dungeons.entity.Automaton) return false;
        return mob instanceof Animal || mob.getType().getCategory() == MobCategory.CREATURE
                || mob.getClass().getName().startsWith("com.skycraft.fauna.");
    }

    /** The mob drops its target and won't pick a player for {@code ticks} (broken if a player hurts it). */
    public static void calm(Mob mob, long now, int ticks) {
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        mob.getPersistentData().putLong(CALM_KEY, now + ticks);
    }

    /** Whether {@code mob} is calmed (by a racial power) at game time {@code now}. */
    public static boolean calmed(Entity mob, long now) {
        return mob.getPersistentData().getLong(CALM_KEY) > now;
    }

    /** Ends a calm (the mob was attacked). */
    public static void endCalm(LivingEntity mob) {
        mob.getPersistentData().remove(CALM_KEY);
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
