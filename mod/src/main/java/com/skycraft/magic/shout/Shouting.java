package com.skycraft.magic.shout;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicPackets;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.MagicScheduler;
import com.skycraft.magic.Targeting;
import com.skycraft.magic.bound.BoundWeapons;
import com.skycraft.magic.spell.Element;
import com.skycraft.magic.spell.Illusion;
import com.skycraft.magic.spell.SpellEffects;
import com.skycraft.magic.spell.Summons;
import com.skycraft.network.SkyNetwork;
import com.skycraft.registry.ModEffects;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shouting: validation (words unlocked, voice cooldown) and every shout's effect. */
public final class Shouting {
    /** Persistent-data key: no fall damage until this game time (Whirlwind Sprint). */
    public static final String NO_FALL = "skycraft_nofall_until";
    /** Persistent-data key on dragons, read by the creatures module: forced to land until this game time. */
    public static final String DRAGONREND = "skycraft_dragonrend_until";

    private static final Map<UUID, Sprint> SPRINTS = new HashMap<>();

    private record Sprint(Vec3 dir, int ticksLeft) {}

    private Shouting() {}

    /** Handles the Voice slot activation (Z key): executes equipped shout or power. */
    public static void executeVoice(ServerPlayer p, int requested) {
        if (!p.isAlive() || p.isSpectator() || p.hasEffect(ModEffects.PARALYSIS.get())) return;
        String voice = MagicData.selectedVoice(p);
        if (voice.startsWith("shout:")) {
            String shoutId = voice.substring("shout:".length());
            shout(p, shoutId, requested);
        } else if (voice.equals("power:racial")) {
            com.skycraft.vitals.RacePowers.use(p);
        } else if (voice.startsWith("power:stone:")) {
            com.skycraft.lore.StandingStones.usePower(p);
        } else {
            Shout shout = Shout.byId(voice);
            if (shout != null) shout(p, shout.id(), requested);
            else com.skycraft.vitals.RacePowers.use(p);
        }
    }

    /** Handles a shout key release: {@code requested} words (1..3, from how long the key was held). */
    public static void shout(ServerPlayer p, int requested) {
        shout(p, MagicData.selectedShout(p), requested);
    }

    public static void shout(ServerPlayer p, String shoutId, int requested) {
        if (!p.isAlive() || p.isSpectator() || p.hasEffect(ModEffects.PARALYSIS.get())) return;
        Shout shout = Shout.byId(shoutId);
        if (shout == null) {
            Notifier.message(p, Component.translatable("message.skycraft.no_shout", Component.keybind("key.skycraft.magic_menu")));
            return;
        }
        int usable = p.isCreative() ? 3 : MagicData.usableWords(p, shout);
        if (usable <= 0) {
            boolean learned = MagicData.wordsLearned(p, shout) > 0;
            Notifier.message(p, Component.translatable(learned ? "message.skycraft.shout_locked" : "message.skycraft.shout_unknown", shout.displayName(), Component.keybind("key.skycraft.magic_menu")));
            return;
        }
        int words = Math.max(1, Math.min(requested, usable));
        long now = p.level().getGameTime();
        if (!p.isCreative() && now < MagicData.shoutReadyAt(p)) {
            Notifier.message(p, Component.translatable("message.skycraft.voice_not_ready"));
            p.playNotifySound(SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.PLAYERS, 0.8f, 0.6f);
            return;
        }
        if (!perform(p, shout, words)) return;

        long cooldown = Math.round(shout.cooldownSeconds(words) * 20L * cooldownMultiplier(p, shout));
        if (!p.isCreative()) MagicData.setShoutCooldown(p, now + cooldown, cooldown);
        SkyNetwork.sendToTracking(p, new MagicPackets.ShoutFx(p.getId(), shout.ordinal(), words));
        ServerLevel level = p.serverLevel();
        // the Thu'um: the words actually shouted (one, two or all three)
        net.minecraft.sounds.SoundEvent shoutSound = com.skycraft.audio.AudioSounds.shout(shout, words);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), shoutSound, SoundSource.PLAYERS, 1.4f + 0.3f * words, 1.0f);
        p.swing(InteractionHand.MAIN_HAND, true);
        SkyData.get(p).addStat("shouts", 1);
    }

    /**
     * Voice recharge multiplier: the Blessing of Talos (shrine, flag {@code blessing_talos}) shortens it by 20%, and
     * Dragon Aspect makes every other shout recover a quarter faster.
     */
    public static float cooldownMultiplier(ServerPlayer p, Shout shout) {
        float m = 1f;
        if (com.skycraft.core.Buffs.active(p, "blessing_talos")) m *= 0.8f;
        if (shout != Shout.DRAGON_ASPECT && p.hasEffect(MagicRegistry.DRAGON_ASPECT.get())) m *= 0.75f;
        return m;
    }

    private static boolean perform(ServerPlayer p, Shout shout, int w) {
        ServerLevel level = p.serverLevel();
        long now = level.getGameTime();
        switch (shout) {
            case UNRELENTING_FORCE -> unrelentingForce(p, w);
            case FIRE_BREATH -> breath(p, w, Element.FIRE);
            case FROST_BREATH -> breath(p, w, Element.FROST);
            case WHIRLWIND_SPRINT -> {
                Vec3 dir = p.getViewVector(1f).multiply(1, 0, 1);
                if (dir.lengthSqr() < 1e-4) {
                    double yaw = Math.toRadians(p.getYRot());
                    dir = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                }
                SPRINTS.put(p.getUUID(), new Sprint(dir.normalize(), new int[]{4, 7, 11}[w - 1]));
                p.getPersistentData().putLong(NO_FALL, now + 80);
                level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.5f, 1.8f);
            }
            case BECOME_ETHEREAL -> {
                p.addEffect(new MobEffectInstance(MagicRegistry.ETHEREAL.get(), new int[]{8, 13, 18}[w - 1] * 20, 0, false, true, true));
                level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ILLUSIONER_PREPARE_MIRROR, SoundSource.PLAYERS, 1f, 0.7f);
            }
            case CLEAR_SKIES -> {
                if (!level.dimensionType().hasSkyLight()) {
                    Notifier.message(p, Component.translatable("message.skycraft.no_sky"));
                    return false;
                }
                level.setWeatherParameters(6000 + 6000 * w, 0, false, false);
                ShoutHooks.clearSkies(level, p, w, 6000 + 6000 * w);
                level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.WEATHER, 1f, 1.4f);
            }
            case AURA_WHISPER -> {
                int duration = new int[]{10, 15, 20}[w - 1] * 20;
                for (LivingEntity e : Targeting.around(p, p.position(), new int[]{32, 48, 64}[w - 1], e -> e != p)) {
                    e.addEffect(new MobEffectInstance(MobEffects.GLOWING, duration, 0, false, false));
                }
            }
            case SLOW_TIME -> {
                SlowTime.start(p, w);
                level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.2f, 0.5f);
            }
            case ICE_FORM -> {
                return ShoutEffects.iceForm(p, w);
            }
            case THROW_VOICE -> {
                return ShoutEffects.throwVoice(p, w);
            }
            case DISMAY -> {
                return ShoutEffects.dismay(p, w);
            }
            case CALL_DRAGON -> {
                return ShoutEffects.callDragon(p, w);
            }
            case CALL_OF_VALOR -> {
                return ShoutEffects.callOfValor(p, w);
            }
            case CYCLONE -> {
                return ShoutEffects.cyclone(p, w);
            }
            case DRAIN_VITALITY -> {
                return ShoutEffects.drainVitality(p, w);
            }
            case SOUL_TEAR -> {
                return ShoutEffects.soulTear(p, w);
            }
            case BEND_WILL -> {
                return ShoutEffects.bendWill(p, w);
            }
            case DRAGON_ASPECT -> {
                return ShoutEffects.dragonAspect(p, w);
            }
            case BATTLE_FURY -> {
                return ShoutEffects.battleFury(p, w);
            }
            case MARKED_FOR_DEATH -> {
                for (LivingEntity e : Targeting.cone(p, 14, 0.8, e -> Targeting.canHarm(p, e))) {
                    e.addEffect(new MobEffectInstance(MagicRegistry.MARKED_FOR_DEATH.get(), 60 * 20, w - 1));
                    Vitals.markInCombat(p);
                }
            }
            case DISARM -> disarm(p, w);
            case ELEMENTAL_FURY -> p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, new int[]{15, 20, 25}[w - 1] * 20, w, false, true, true));
            case KYNES_PEACE -> {
                int duration = new int[]{60, 90, 120}[w - 1] * 20;
                for (LivingEntity e : Targeting.around(p, p.position(), new int[]{20, 30, 40}[w - 1], e -> e instanceof Animal)) {
                    Illusion.applyCalm(e, duration);
                }
            }
            case ANIMAL_ALLEGIANCE -> {
                long until = now + new int[]{30, 45, 60}[w - 1] * 20L;
                for (LivingEntity e : Targeting.around(p, p.position(), new int[]{20, 30, 40}[w - 1],
                        e -> e instanceof Animal && !(e instanceof TamableAnimal t && t.isTame()) && !Summons.isSummon(e))) {
                    Mob mob = (Mob) e;
                    if (mob instanceof NeutralMob neutral) neutral.stopBeingAngry();
                    e.removeEffect(MagicRegistry.FEAR.get());
                    e.removeEffect(MagicRegistry.CALM.get());
                    Summons.makeAlly(mob, p, until);
                    MagicFx.send(e, MagicFx.AURA, Element.NATURE, e.position(), e.position(), e.getId(), 0);
                }
            }
            case STORM_CALL -> {
                if (!level.dimensionType().hasSkyLight() || !level.canSeeSky(p.blockPosition().above())) {
                    Notifier.message(p, Component.translatable("message.skycraft.no_sky"));
                    return false;
                }
                int duration = new int[]{30, 45, 60}[w - 1] * 20;
                level.setWeatherParameters(0, duration + 1200, true, true);
                ShoutHooks.stormCall(level, p, w, duration);
                MagicScheduler.area(p, p.position(), true, 32, duration, 50, "storm_call", (lvl, owner, area) -> {
                    if (owner == null || area.age == 0) return;
                    List<LivingEntity> targets = new ArrayList<>(Targeting.around(lvl, area.center, area.radius,
                            e -> Targeting.canHarm(owner, e) && (e instanceof Enemy || e instanceof Mob m && m.getTarget() == owner)
                                    && lvl.canSeeSky(e.blockPosition())));
                    if (targets.isEmpty()) return;
                    LivingEntity t = targets.get(lvl.random.nextInt(targets.size()));
                    LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(lvl);
                    if (bolt != null) {
                        bolt.moveTo(Vec3.atBottomCenterOf(t.blockPosition()));
                        bolt.setVisualOnly(true);
                        lvl.addFreshEntity(bolt);
                    }
                    t.invulnerableTime = 0;
                    t.hurt(MagicDamage.source(lvl, MagicDamage.SHOCK, null, owner), 12f);
                    t.setSecondsOnFire(3);
                    MagicFx.burst(lvl, t.position().add(0, 1, 0), Element.SHOCK, 1.5f);
                });
            }
            case DRAGONREND -> {
                boolean rent = false;
                for (LivingEntity e : Targeting.cone(p, 40, 0.85, e -> e != p)) {
                    if (e.getType().is(MagicRegistry.DRAGONS)) {
                        e.getPersistentData().putLong(DRAGONREND, now + 20 * 30);
                        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 30, 1));
                        MagicFx.send(e, MagicFx.AURA, Element.SOUL, e.position(), e.position(), e.getId(), 3);
                        rent = true;
                    } else if (Targeting.canHarm(p, e)) {
                        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                    }
                }
                if (rent) Notifier.message(p, Component.translatable("message.skycraft.dragonrend"));
            }
        }
        return true;
    }

    private static void unrelentingForce(ServerPlayer p, int w) {
        double range = 6 + 3 * w;
        double force = new double[]{0.9, 1.7, 3.0}[w - 1];
        float damage = new float[]{0f, 1f, 4f}[w - 1];
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(range), e -> !e.isSpectator())) {
            Vec3 to = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            double dist = to.length();
            if (dist > range || dist < 1e-3 || to.scale(1 / dist).dot(look) < 0.6 && dist > 1.5) continue;
            Vec3 dir = to.normalize();
            double strength = force * (1.0 - 0.4 * dist / range);
            if (e instanceof LivingEntity living) {
                if (!Targeting.canHarm(p, living) || !p.hasLineOfSight(living)) continue;
                if (damage > 0) {
                    living.invulnerableTime = 0;
                    living.hurt(MagicDamage.source(p.level(), MagicDamage.SPELL, null, p), damage);
                }
                CombatHandler.stagger(p, living, 20 * w);
                living.push(dir.x * strength, 0.25 + 0.2 * w, dir.z * strength);
                living.hurtMarked = true;
                Vitals.markInCombat(p);
            } else if (e instanceof Projectile || e instanceof net.minecraft.world.entity.item.ItemEntity) {
                e.setDeltaMovement(dir.scale(strength));
                e.hurtMarked = true;
            }
        }
    }

    private static void breath(ServerPlayer p, int w, Element element) {
        double range = new double[]{7, 9, 11}[w - 1];
        float damage = new float[]{10f, 14f, 18f}[w - 1];
        for (LivingEntity e : Targeting.cone(p, range, 0.7, e -> Targeting.canHarm(p, e))) {
            e.invulnerableTime = 0;
            if (e.hurt(SpellEffects.damage(p, element, null), damage)) {
                SpellEffects.applyElement(p, element, e, damage, false);
                if (element == Element.FIRE) e.setSecondsOnFire(4 + 2 * w);
                Vitals.markInCombat(p);
            }
        }
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), element == Element.FIRE ? SoundEvents.BLAZE_SHOOT : SoundEvents.POWDER_SNOW_BREAK,
                SoundSource.PLAYERS, 1.5f, 0.6f);
    }

    private static void disarm(ServerPlayer p, int w) {
        float limit = new float[]{30f, 60f, Float.MAX_VALUE}[w - 1];
        for (LivingEntity e : Targeting.cone(p, 9, 0.7, e -> Targeting.canHarm(p, e))) {
            if (e.getType().is(CombatHandler.BOSSES) || e.getMaxHealth() > limit) continue;
            ItemStack held = e.getMainHandItem();
            if (held.isEmpty()) continue;
            if (e instanceof Player other) {
                other.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                if (!BoundWeapons.isBound(held)) other.drop(held, true, false);
            } else if (e instanceof Mob mob) {
                if (!Summons.isSummon(mob)) mob.spawnAtLocation(held.copy());
                mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
            Vitals.markInCombat(p);
            e.level().playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 0.8f, 1.4f);
        }
    }

    /** Whirlwind Sprint movement and Ethereal shimmer; every tick for every player. */
    public static void tick(ServerPlayer p) {
        Sprint sprint = SPRINTS.get(p.getUUID());
        if (sprint != null) {
            if (sprint.ticksLeft <= 0 || !p.isAlive()) {
                SPRINTS.remove(p.getUUID());
            } else {
                double speed = 1.45;
                p.setDeltaMovement(sprint.dir.x * speed, Math.max(p.getDeltaMovement().y, 0.02), sprint.dir.z * speed);
                p.hurtMarked = true;
                p.fallDistance = 0;
                SPRINTS.put(p.getUUID(), new Sprint(sprint.dir, sprint.ticksLeft - 1));
                p.serverLevel().sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.8, p.getZ(), 3, 0.3, 0.4, 0.3, 0.02);
            }
        }
        if (p.tickCount % 5 == 0 && p.hasEffect(MagicRegistry.ETHEREAL.get())) {
            p.serverLevel().sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 2, 0.3, 0.6, 0.3, 0.005);
            p.serverLevel().sendParticles(ParticleTypes.WHITE_ASH, p.getX(), p.getY() + 1, p.getZ(), 4, 0.35, 0.7, 0.35, 0.0);
        }
    }

    public static void forget(UUID id) {
        SPRINTS.remove(id);
    }

    // ------------------------------------------------------------------ words

    /** Spends a dragon soul to unlock the next learned word of a shout. */
    public static void unlockWord(ServerPlayer p, Shout shout) {
        int learned = MagicData.wordsLearned(p, shout);
        int unlocked = MagicData.wordsUnlocked(p, shout);
        if (unlocked >= learned) {
            Notifier.message(p, Component.translatable("message.skycraft.no_word_to_unlock"));
            return;
        }
        int souls = MagicData.dragonSouls(p);
        if (souls <= 0) {
            Notifier.message(p, Component.translatable("message.skycraft.no_dragon_souls"));
            return;
        }
        MagicData.setDragonSouls(p, souls - 1);
        MagicData.setWordsUnlocked(p, shout, unlocked + 1);
        if (MagicData.selectedShout(p).isEmpty()) MagicData.setSelectedShout(p, shout.id());
        Notifier.title(p, Component.translatable("notify.skycraft.word_unlocked"), shout.wordWithTranslation(unlocked));
        MagicFx.send(p, MagicFx.AURA, Element.SOUL, p.position(), p.position(), p.getId(), 4);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 1.5f, 0.6f);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1f, 0.8f);
    }
}
