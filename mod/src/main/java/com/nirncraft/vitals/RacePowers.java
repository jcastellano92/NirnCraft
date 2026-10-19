package com.nirncraft.vitals;

import com.nirncraft.core.Buffs;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.Race;
import com.nirncraft.core.SkyData;
import com.nirncraft.magic.MagicFx;
import com.nirncraft.magic.MagicPackets;
import com.nirncraft.magic.MagicRegistry;
import com.nirncraft.magic.Targeting;
import com.nirncraft.magic.spell.Element;
import com.nirncraft.magic.spell.Illusion;
import com.nirncraft.magic.spell.Summons;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Once-per-day racial greater powers (Khajiit Night Eye is a lesser power with a short cooldown). Lasting effects
 * that aren't vanilla mob effects are {@link Buffs} read elsewhere: highborn, adrenaline rush and histskin by
 * {@link Vitals}, the fire cloak by the combat handler, berserker rage, dragonskin and tonal resonance by
 * {@link RacePassives}. Calmed creatures ({@link #calm}) won't pick a player as target until it wears off.
 */
public final class RacePowers {
    /** Persistent-data key on a mob: game time until which it won't target players. */
    public static final String CALM_KEY = "nirncraft_calm_until";

    private RacePowers() {}

    public static void use(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        Race race = data.getRace();
        if (race == null) return;
        long now = player.level().getGameTime();
        if (now < data.getPowerReadyAt()) {
            String hours = com.nirncraft.SkyConfig.formatTicks(data.getPowerReadyAt() - now);
            Notifier.message(player, Component.translatable("message.nirncraft.power_cooldown", race.powerName(), hours));
            return;
        }
        int seconds = 60;
        SoundEvent sound = SoundEvents.EVOKER_CAST_SPELL;
        switch (race) {
            case NORD -> {
                // Battle Cry: nearby enemies flee in terror for 30 seconds
                int hits = forEachEnemy(player, 16, mob -> {
                    calm(mob, now, 20 * 30);
                    Illusion.applyFear(player.position(), mob, 20 * 30);
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 20 * 30, 1));
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.WEAKNESS), 20 * 30, 1));
                    push(player, mob, 1.2);
                });
                if (hits == 0) {
                    notifyNoTargets(player, race);
                    return;
                }
                MagicFx.sendNear(player.level(), player.position(), 48, MagicFx.RING, Element.FROST,
                        player.position().add(0, 0.2, 0), player.position(), player.getId(), 120);
                sound = SoundEvents.RAVAGER_ROAR;
            }
            case IMPERIAL -> {
                // Voice of the Emperor: calms nearby hostiles for 60 seconds
                int hits = forEachEnemy(player, 16, mob -> {
                    calm(mob, now, 20 * 60);
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 20 * 60, 1));
                    MagicFx.send(mob, MagicFx.AURA, Element.HOLY, mob.position(), mob.position(), mob.getId(), 1);
                });
                if (hits == 0) {
                    notifyNoTargets(player, race);
                    return;
                }
                MagicFx.sendNear(player.level(), player.position(), 48, MagicFx.RING, Element.HOLY,
                        player.position().add(0, 0.2, 0), player.position(), player.getId(), 120);
            }
            case BRETON -> {
                Buffs.apply(player, "dragonskin", 20 * seconds);
                MagicFx.send(player, MagicFx.AURA, Element.ARCANE, player.position(), player.position(), player.getId(), 3);
            }
            case REDGUARD -> {
                Buffs.apply(player, "adrenaline_rush", 20 * seconds);
                MagicFx.send(player, MagicFx.AURA, Element.HOLY, player.position(), player.position(), player.getId(), 2);
            }
            case ALTMER -> {
                Buffs.apply(player, "highborn", 20 * seconds);
                MagicFx.send(player, MagicFx.AURA, Element.ARCANE, player.position(), player.position(), player.getId(), 4);
            }
            case BOSMER -> {
                // Command Animal: calms all wild beasts in range and recruits up to 3 as loyal fighting allies for 60s
                List<Mob> beasts = findBeastsForCommand(player);
                if (beasts.isEmpty()) {
                    notifyNoTargets(player, race);
                    return;
                }
                long allyUntil = com.nirncraft.world.RestManager.liveTime(player.level()) + 20L * seconds;
                int recruited = 0;
                for (Mob mob : beasts) {
                    calm(mob, now, 20 * seconds);
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.REGENERATION), 20 * seconds, 1));
                    if (recruited < 3) {
                        Summons.makeAlly(mob, player, allyUntil);
                        mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.STRENGTH), 20 * seconds, 0));
                        MagicFx.send(mob, MagicFx.AURA, Element.NATURE, mob.position(), mob.position(), mob.getId(), 2);
                        recruited++;
                    }
                }
                MagicFx.sendNear(player.level(), player.position(), 48, MagicFx.RING, Element.NATURE,
                        player.position().add(0, 0.2, 0), player.position(), player.getId(), 140);
                player.level().playSound(null, player.blockPosition(), com.nirncraft.audio.AudioSounds.creature("wolf", "howl"), SoundSource.PLAYERS, 0.6f, 1.2f);
                sound = SoundEvents.AMETHYST_BLOCK_CHIME;
            }
            case DUNMER -> {
                // Ancestor's Wrath: surrounds the Dunmer in a fiery cloak for 60 seconds
                Buffs.apply(player, "fire_cloak", 20 * seconds);
                player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MagicRegistry.FLAME_CLOAK.get()), 20 * seconds, 0, false, true, true));
                MagicFx.send(player, MagicFx.AURA, Element.FIRE, player.position(), player.position(), player.getId(), 3);
                sound = SoundEvents.BLAZE_SHOOT;
            }
            case ORSIMER -> {
                Buffs.apply(player, "berserker_rage", 20 * seconds);
                MagicFx.send(player, MagicFx.AURA, Element.FIRE, player.position(), player.position(), player.getId(), 2);
                sound = SoundEvents.RAVAGER_ROAR;
            }
            case KHAJIIT -> self(player, MobEffects.NIGHT_VISION, seconds, 0);
            case ARGONIAN -> {
                Buffs.apply(player, "histskin", 20 * seconds);
                MagicFx.send(player, MagicFx.AURA, Element.NATURE, player.position(), player.position(), player.getId(), 3);
            }
            case MAORMER -> {
                self(player, MobEffects.INVISIBILITY, seconds, 0);
                self(player, MobEffects.WATER_BREATHING, seconds, 0);
                self(player, MobEffects.DOLPHINS_GRACE, seconds, 0);
                forEachEnemy(player, 16, mob -> calm(mob, now, 20 * 5));
            }
            case REACHMAN -> {
                self(player, MobEffects.STRENGTH, seconds, 0);
                self(player, MobEffects.RESISTANCE, seconds, 0);
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
                self(player, MobEffects.SPEED, 20, 1);
                sound = SoundEvents.FOX_SCREECH;
            }
            case IMGA -> {
                forEachEnemy(player, 12, mob -> {
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.WEAKNESS), 20 * 30, 1));
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 20 * 30, 0));
                    push(player, mob, 0.6);
                });
                self(player, MobEffects.STRENGTH, 30, 0);
                self(player, MobEffects.RESISTANCE, 30, 0);
                sound = SoundEvents.RAVAGER_STUNNED;
            }
            case TSAESCI -> {
                int hits = forEachEnemy(player, 12, mob -> {
                    mob.setTarget(null);
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(com.nirncraft.registry.ModEffects.PARALYSIS.get()), 20 * 6, 0));
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.WEAKNESS), 20 * 20, 0));
                });
                if (hits == 0) {
                    notifyNoTargets(player, race);
                    return;
                }
                sound = SoundEvents.ELDER_GUARDIAN_CURSE;
            }
            case KA_PO_TUN -> {
                int hits = forEachEnemy(player, 10, mob -> {
                    calm(mob, now, 20 * 10);
                    Illusion.applyFear(player.position(), mob, 20 * 10);
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.WEAKNESS), 20 * 30, 1));
                    push(player, mob, 1.8);
                    com.nirncraft.combat.CombatHandler.secondaryHurt(mob, player.damageSources().mobAttack(player), 4f);
                });
                if (hits == 0) {
                    notifyNoTargets(player, race);
                    return;
                }
                sound = SoundEvents.RAVAGER_ROAR;
            }
            case TANG_MO -> {
                self(player, MobEffects.HASTE, seconds, 1);
                self(player, MobEffects.STRENGTH, seconds, 0);
                self(player, MobEffects.SPEED, seconds, 0);
            }
            case ATMORAN -> {
                // the Voice is ready again at once (syncs immediately to client HUD via SkyData)
                com.nirncraft.magic.MagicData.setShoutCooldown(player, now, 0);
                self(player, MobEffects.RESISTANCE, seconds, 0);
                sound = SoundEvents.ELDER_GUARDIAN_AMBIENT;
            }
            case FALMER -> {
                for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                        m -> com.nirncraft.platform.Effects.isUndead(m) && !Targeting.isFriendly(player, m))) {
                    mob.igniteForSeconds(5);
                    com.nirncraft.combat.CombatHandler.secondaryHurt(mob, player.damageSources().indirectMagic(player, player), 8f);
                }
                forEachEnemy(player, 16, mob -> mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.BLINDNESS), 20 * 8, 0)));
                self(player, MobEffects.REGENERATION, 10, 1);
                sound = SoundEvents.BEACON_ACTIVATE;
            }
            case DWEMER -> {
                Buffs.apply(player, "tonal_resonance", 20 * 120);
                for (Mob mob : player.level().getEntitiesOfClass(com.nirncraft.dungeons.entity.Automaton.class, player.getBoundingBox().inflate(32),
                        m -> m.getTarget() == player)) {
                    mob.setTarget(null);
                }
                sound = SoundEvents.AMETHYST_BLOCK_RESONATE;
            }
            case AYLEID -> {
                data.setMagicka(data.maxMagicka());
                forEachEnemy(player, 16, mob -> {
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.GLOWING), 20 * 30, 0));
                    mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.BLINDNESS), 20 * 4, 0));
                });
                self(player, MobEffects.NIGHT_VISION, seconds, 0);
                sound = SoundEvents.BEACON_POWER_SELECT;
            }
            case KOTHRINGI -> {
                self(player, MobEffects.RESISTANCE, seconds, 1);
                forEachEnemy(player, 8, mob -> mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.BLINDNESS), 20 * 8, 0)));
                sound = SoundEvents.AMETHYST_BLOCK_CHIME;
            }
        }
        data.setPowerReadyAt(now + race.powerCooldown(data.getLevel()));
        player.level().playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1f, 0.8f * race.voicePitch);
        Notifier.message(player, Component.translatable("message.nirncraft.power_used", race.powerName()));
    }

    private static void notifyNoTargets(ServerPlayer player, Race race) {
        com.nirncraft.magic.MagicFx.soundToPlayer(player, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5f, 1.4f);
        Notifier.message(player, Component.translatable("message.nirncraft.power_no_targets", race.powerName()));
    }

    private static List<Mob> findBeastsForCommand(ServerPlayer player) {
        Set<Mob> found = new LinkedHashSet<>();
        // 1. Check 28-block look ray first so aimed beasts are always prioritized
        Targeting.Ray ray = Targeting.ray(player, 28.0, e -> e instanceof Mob m && isCommandableBeast(player, m));
        if (ray.entity() instanceof Mob aimed) {
            found.add(aimed);
        }
        // 2. Gather beasts within 24 blocks of the player (and 20 blocks of the ray endpoint)
        List<Mob> nearby = new ArrayList<>(player.level().getEntitiesOfClass(
                Mob.class, player.getBoundingBox().inflate(24.0), m -> isCommandableBeast(player, m)));
        AABB rayBox = new AABB(ray.end(), ray.end()).inflate(20.0);
        for (Mob m : player.level().getEntitiesOfClass(Mob.class, rayBox, m -> isCommandableBeast(player, m))) {
            if (!nearby.contains(m)) nearby.add(m);
        }
        nearby.sort(Comparator.comparingDouble(m -> m.distanceToSqr(player)));
        found.addAll(nearby);
        return new ArrayList<>(found);
    }

    private static boolean isCommandableBeast(ServerPlayer player, Mob mob) {
        if (!mob.isAlive() || !isBeast(mob)) return false;
        java.util.UUID existingOwner = Summons.ownerOf(mob);
        return existingOwner == null || existingOwner.equals(player.getUUID());
    }

    private static void self(ServerPlayer player, net.minecraft.core.Holder<MobEffect> effect, int seconds, int amplifier) {
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(effect), 20 * seconds, amplifier, false, false, true));
    }

    private static void push(ServerPlayer player, Mob mob, double strength) {
        Vec3 away = mob.position().subtract(player.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) return;
        away = away.normalize().scale(strength);
        mob.push(away.x, 0.3, away.z);
        mob.syncVelocity = true;
    }

    private static int forEachEnemy(ServerPlayer player, double radius, Consumer<Mob> action) {
        int count = 0;
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(radius),
                m -> m.isAlive() && (m instanceof Enemy || m.getTarget() == player) && !Targeting.isFriendly(player, m))) {
            action.accept(mob);
            count++;
        }
        return count;
    }

    /** Animals and wild beasts (vanilla animals, spiders, NirnCraft fauna & wild creatures), not people, undead, dragons or constructs. */
    public static boolean isBeast(Mob mob) {
        if (com.nirncraft.platform.Effects.isUndead(mob)
                || mob instanceof com.nirncraft.dungeons.entity.Automaton
                || mob instanceof AbstractGolem
                || mob instanceof Npc
                || mob.getType().builtInRegistryHolder().is(com.nirncraft.combat.CombatHandler.BOSSES)) {
            return false;
        }
        if (mob instanceof Animal || mob instanceof WaterAnimal || mob instanceof AmbientCreature || mob instanceof Spider) {
            return true;
        }
        MobCategory cat = mob.getType().getCategory();
        if (cat == MobCategory.CREATURE || cat == MobCategory.WATER_CREATURE
                || cat == MobCategory.UNDERGROUND_WATER_CREATURE || cat == MobCategory.AXOLOTLS
                || cat == MobCategory.AMBIENT) {
            return true;
        }
        String cls = mob.getClass().getName();
        if (cls.startsWith("com.nirncraft.fauna.") || cls.startsWith("com.nirncraft.seas.")) {
            return true;
        }
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (key != null) {
            String path = key.getPath();
            return path.contains("wolf") || path.contains("bear") || path.contains("sabre") || path.contains("cat")
                    || path.contains("spider") || path.contains("chaurus") || path.contains("mudcrab") || path.contains("crab")
                    || path.contains("skeever") || path.contains("troll") || path.contains("horker") || path.contains("mammoth")
                    || path.contains("deer") || path.contains("elk") || path.contains("fox") || path.contains("boar")
                    || path.contains("goat") || path.contains("horse") || path.contains("nix") || path.contains("guar")
                    || path.contains("kagouti") || path.contains("alit") || path.contains("cliff_racer") || path.contains("netch")
                    || path.contains("slaughterfish") || path.contains("fish") || path.contains("shark");
        }
        return false;
    }

    /** The mob drops its target and won't pick a player for {@code ticks} (broken if a player hurts it). */
    public static void calm(Mob mob, long now, int ticks) {
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        mob.getPersistentData().putLong(CALM_KEY, now + ticks);
    }

    /** Whether {@code mob} is calmed (by a racial power) at game time {@code now}. */
    public static boolean calmed(Entity mob, long now) {
        return mob.getPersistentData().getLongOr(CALM_KEY, 0L) > now;
    }

    /** Ends a calm (the mob was attacked). */
    public static void endCalm(LivingEntity mob) {
        mob.getPersistentData().remove(CALM_KEY);
    }

    /** Dunmer "Ancestor's Wrath" fire cloak tick, called from the combat handler each second. */
    public static void tickFireCloak(ServerPlayer player) {
        if (!Buffs.active(player, "fire_cloak")) return;
        player.level().sendParticles(ParticleTypes.FLAME,
                player.getX(), player.getY() + 0.9, player.getZ(), 10, 0.55, 0.45, 0.55, 0.02);
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(3.5),
                m -> m.isAlive() && (m instanceof Enemy || m.getTarget() == player) && !Targeting.isFriendly(player, m))) {
            mob.igniteForSeconds(3);
            com.nirncraft.combat.CombatHandler.secondaryHurt(mob, player.damageSources().indirectMagic(player, player), 2f);
        }
    }
}
