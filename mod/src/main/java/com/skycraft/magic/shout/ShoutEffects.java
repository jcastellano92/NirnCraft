package com.skycraft.magic.shout;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.MagicScheduler;
import com.skycraft.magic.Targeting;
import com.skycraft.magic.spell.Element;
import com.skycraft.magic.spell.Illusion;
import com.skycraft.magic.spell.SpellEffects;
import com.skycraft.magic.spell.Summons;
import com.skycraft.registry.ModEffects;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The shouts added on top of {@link Shouting}'s originals: Ice Form, Throw Voice, Dismay, Call Dragon, Call of Valor,
 * Cyclone, Drain Vitality, Soul Tear, Bend Will, Dragon Aspect and Battle Fury. Each returns false when it couldn't be
 * shouted (no voice cooldown then).
 */
public final class ShoutEffects {
    /** Persistent data on a Soul Tear victim: who tore at its soul, and until when its death raises a thrall. */
    public static final String SOUL_TEAR_BY = "skycraft_soul_tear_by";
    public static final String SOUL_TEAR_UNTIL = "skycraft_soul_tear_until";
    private static final String[] HEROES = {"felldir", "gormlaith", "hakon"};

    private ShoutEffects() {}

    private static boolean boss(LivingEntity e) {
        return e.getType().builtInRegistryHolder().is(CombatHandler.BOSSES);
    }

    /** IIZ SLEN NUS: your Thu'um freezes an opponent solid. */
    static boolean iceForm(ServerPlayer p, int w) {
        int seconds = new int[]{5, 10, 15}[w - 1];
        float damage = new float[]{2f, 3f, 4f}[w - 1];
        boolean any = false;
        for (LivingEntity e : Targeting.cone(p, 12, 0.75, e -> Targeting.canHarm(p, e))) {
            e.setInvulnerableTime(0);
            com.skycraft.platform.Effects.hurt(e, SpellEffects.damage(p, Element.FROST, null), damage);
            e.setTicksFrozen(e.getTicksRequiredToFreeze() + 40);
            if (boss(e)) {
                e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), seconds * 20, 2));
            } else {
                int ticks = e instanceof Player ? Math.min(60, seconds * 20) : seconds * 20;
                e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.ICE_FORM.get()), ticks, 0));
                e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), ticks, 0));
                if (e instanceof Mob mob) mob.setTarget(null);
                e.setDeltaMovement(0, Math.min(0, e.getDeltaMovement().y), 0);
            }
            MagicFx.send(e, MagicFx.AURA, Element.FROST, e.position(), e.position(), e.getId(), 3);
            Vitals.markInCombat(p);
            any = true;
        }
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2f, 0.5f);
        return true;
    }

    /** ZUL MEY GUT: your voice comes from somewhere else; enemies go to look. */
    static boolean throwVoice(ServerPlayer p, int w) {
        ServerLevel level = p.level();
        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(p.getViewVector(1f).scale(48)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 at = hit.getLocation();
        level.playSound(null, at.x, at.y, at.z, com.skycraft.world.WorldSounds.SHOUT_GENERIC.get(), SoundSource.PLAYERS, 0.8f + 0.3f * w, 1.2f);
        level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
        int ticks = new int[]{60, 100, 160}[w - 1];
        for (Mob mob : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(at, at).inflate(16 + 6 * w),
                m -> m.isAlive() && !boss(m) && !Targeting.isFriendly(p, m))) {
            if (mob.getTarget() != null && mob.getTarget() != p) continue;
            mob.setTarget(null);
            mob.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.CALM.get()), ticks, 0, false, false));
            if (mob instanceof PathfinderMob pm) pm.getNavigation().moveTo(at.x, at.y, at.z, 1.1);
        }
        return true;
    }

    /** FAAS RU MAAR: weaker opponents flee in terror. */
    static boolean dismay(ServerPlayer p, int w) {
        float limit = new float[]{40f, 80f, 160f}[w - 1];
        int ticks = new int[]{30, 35, 40}[w - 1] * 20;
        for (LivingEntity e : Targeting.cone(p, 14, 0.6, e -> Targeting.canHarm(p, e))) {
            if (e instanceof ServerPlayer tp) {
                Illusion.onPlayer(p, tp, "fear");
                continue;
            }
            if (boss(e) || e.getMaxHealth() > limit) continue;
            Illusion.applyFear(p.position(), e, ticks);
            MagicFx.send(e, MagicFx.AURA, Element.SOUL, e.position(), e.position(), e.getId(), 1);
        }
        Vitals.markInCombat(p);
        return true;
    }

    /** OD AH VIING: a dragon answers the call (all three words, under an open sky). */
    static boolean callDragon(ServerPlayer p, int w) {
        if (w < 3) {
            Notifier.message(p, Component.translatable("message.skycraft.call_dragon_weak"));
            return true;
        }
        ServerLevel level = p.level();
        if (!level.dimensionType().hasSkyLight() || !level.canSeeSky(p.blockPosition().above())) {
            Notifier.message(p, Component.translatable("message.skycraft.no_sky"));
            return false;
        }
        Mob dragon = Summons.conjureAlly(p, "odahviing", "odahviing", 6, 20 * 150, mob -> {
            mob.snapTo(p.getX() + 8, p.getY() + 14, p.getZ() + 8, p.getYRot(), 0f);
        });
        if (dragon == null || !dragon.getType().builtInRegistryHolder().is(MagicRegistry.DRAGONS)) {
            if (dragon != null) Summons.dismiss(dragon);
            Notifier.message(p, Component.translatable("message.skycraft.call_dragon_none"));
            return true;
        }
        level.playSound(null, p.getX(), p.getY() + 10, p.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 3f, 0.8f);
        Notifier.message(p, Component.translatable("message.skycraft.call_dragon"));
        return true;
    }

    /** HUN KAAL ZOOR: a hero of Sovngarde fights at your side for 60 seconds. */
    static boolean callOfValor(ServerPlayer p, int w) {
        String hero = HEROES[p.getRandom().nextInt(HEROES.length)];
        double health = 60 + 30 * w;
        Mob mob = Summons.conjureAlly(p, "sovngarde_hero", hero, 8, 20 * 60, m -> {
            AttributeInstance hp = m.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(health);
            m.setHealth(m.getMaxHealth());
            AttributeInstance atk = m.getAttribute(Attributes.ATTACK_DAMAGE);
            if (atk != null) atk.setBaseValue(6 + 2 * w);
            m.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.GLOWING), 20 * 60, 0, false, false));
            m.setCanPickUpLoot(false);
        });
        if (mob == null) return false;
        p.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.6f, 1.4f);
        return true;
    }

    /** VEN GAAR NOS: a whirlwind that sweeps opponents off their feet. */
    static boolean cyclone(ServerPlayer p, int w) {
        Vec3 dir = p.getViewVector(1f).multiply(1, 0, 1);
        if (dir.lengthSqr() < 1e-4) dir = new Vec3(0, 0, 1);
        Vec3 step = dir.normalize().scale(0.55);
        float damage = new float[]{2f, 4f, 6f}[w - 1];
        double lift = new double[]{0.7, 1.0, 1.4}[w - 1];
        Set<UUID> struck = new HashSet<>();
        Vec3 start = p.position().add(step.scale(3));
        MagicScheduler.area(p, start, false, 2.2, 30 + 15 * w, 1, "cyclone", (level, owner, area) -> {
            area.center = area.center.add(step);
            level.sendParticles(ParticleTypes.CLOUD, area.center.x, area.center.y + 1, area.center.z, 6, 0.6, 1.2, 0.6, 0.05);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, area.center.x, area.center.y + 0.8 + level.getRandom().nextDouble() * 2, area.center.z, 1, 0.4, 0.4, 0.4, 0);
            if (area.age % 10 == 0) level.playSound(null, area.center.x, area.center.y, area.center.z, SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.6f, 0.6f);
            if (owner == null) return;
            for (LivingEntity e : Targeting.around(level, area.center.add(0, 1, 0), area.radius, e -> Targeting.canHarm(owner, e))) {
                if (!struck.add(e.getUUID())) continue;
                e.setInvulnerableTime(0);
                com.skycraft.platform.Effects.hurt(e, MagicDamage.source(level, MagicDamage.SPELL, null, owner), damage);
                double l = boss(e) ? lift * 0.3 : lift;
                e.setDeltaMovement(step.x * 2, l, step.z * 2);
                e.syncVelocity = true;
                Vitals.markInCombat(owner);
            }
        });
        return true;
    }

    /** GAAN LAH HAAS: drains stamina, then magicka, then health from those before you. */
    static boolean drainVitality(ServerPlayer p, int w) {
        int ticks = 30 * 20;
        for (LivingEntity e : Targeting.cone(p, 12, 0.7, e -> Targeting.canHarm(p, e))) {
            if (e instanceof ServerPlayer tp) {
                PlayerData d = SkyData.get(tp);
                d.setStamina(Math.max(0, d.getStamina() - 50));
                if (w >= 2) d.setMagicka(Math.max(0, d.getMagicka() - 50));
            } else {
                e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.WEAKNESS), ticks, 0));
                e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), ticks, 0));
            }
            if (w >= 3) {
                e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.WITHER), ticks / 2, 0));
                e.setInvulnerableTime(0);
                com.skycraft.platform.Effects.hurt(e, MagicDamage.source(p.level(), MagicDamage.SPELL, null, p), 4f);
            }
            MagicFx.send(e, MagicFx.AURA, Element.SOUL, e.position(), e.position(), e.getId(), w);
            Vitals.markInCombat(p);
        }
        return true;
    }

    /** RII VAAZ ZOL: tears at an opponent's soul; if it dies soon after, it rises as your thrall (all three words). */
    static boolean soulTear(ServerPlayer p, int w) {
        if (w < 3) {
            Notifier.message(p, Component.translatable("message.skycraft.soul_tear_weak"));
            return false;
        }
        boolean any = false;
        long now = p.level().getGameTime();
        for (LivingEntity e : Targeting.cone(p, 16, 0.8, e -> Targeting.canHarm(p, e))) {
            if (!(e instanceof Player) && !boss(e)) {
                CompoundTag pd = e.getPersistentData();
                pd.store(SOUL_TEAR_BY, net.minecraft.core.UUIDUtil.CODEC, p.getUUID());
                pd.putLong(SOUL_TEAR_UNTIL, now + 100);
                SpellEffects.soulTrap(p, e, 100);
            }
            e.setInvulnerableTime(0);
            com.skycraft.platform.Effects.hurt(e, MagicDamage.source(p.level(), MagicDamage.SPELL, null, p), 10f);
            MagicFx.send(e, MagicFx.AURA, Element.SOUL, e.position(), e.position(), e.getId(), 4);
            Vitals.markInCombat(p);
            any = true;
        }
        return true;
    }

    /** Called when a creature dies: a Soul Tear victim rises as its tormentor's permanent thrall. */
    public static void onDeath(LivingEntity dead) {
        CompoundTag pd = dead.getPersistentData();
        if (!pd.read(SOUL_TEAR_BY, net.minecraft.core.UUIDUtil.CODEC).isPresent() || pd.getLongOr(SOUL_TEAR_UNTIL, 0L) < dead.level().getGameTime() || dead.level().getServer() == null) return;
        ServerPlayer owner = dead.level().getServer().getPlayerList().getPlayer(pd.read(SOUL_TEAR_BY, net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        if (owner == null || owner.level() != dead.level() || !(dead instanceof Mob)) return;
        @SuppressWarnings("unchecked")
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) dead.getType();
        Vec3 at = dead.position();
        com.skycraft.magic.MagicScheduler.schedule(owner.level().getServer(), 20, server -> {
            if (!owner.isAlive()) return;
            Mob thrall = Summons.conjureAt(owner, type, "thrall", at, Summons.PERMANENT, m -> {
                m.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.FIRE_RESISTANCE), Summons.PERMANENT, 0, false, false));
                m.setCanPickUpLoot(false);
            });
            if (thrall != null) {
                thrall.setCustomName(Component.translatable("entity.skycraft.summon.thrall", thrall.getType().getDescription()));
                MagicFx.sendNear(owner.level(), at, 64, MagicFx.SUMMON, Element.SOUL, at, at, thrall.getId(), 20);
            }
        });
    }

    /** GOL HAH DOV: your Voice bends the will of creatures (two words: people; three: dragons) to fight for you. */
    static boolean bendWill(ServerPlayer p, int w) {
        long until = p.level().getGameTime() + new int[]{30, 60, 90}[w - 1] * 20L;
        float limit = new float[]{30f, 80f, 400f}[w - 1];
        for (LivingEntity e : Targeting.cone(p, 16, 0.75, e -> Targeting.canHarm(p, e) && e instanceof Mob)) {
            Mob mob = (Mob) e;
            boolean dragon = mob.getType().builtInRegistryHolder().is(MagicRegistry.DRAGONS);
            if (dragon ? w < 3 : boss(mob) || mob.getMaxHealth() > limit || Summons.isSummon(mob)) continue;
            if (w < 2 && Illusion.isPerson(mob)) continue;
            Summons.makeAlly(mob, p, until);
            if (mob instanceof net.minecraft.world.entity.NeutralMob neutral) neutral.stopBeingAngry();
            MagicFx.send(mob, MagicFx.AURA, Element.MIND, mob.position(), mob.position(), mob.getId(), 3);
            if (dragon) Notifier.message(p, Component.translatable("message.skycraft.bend_will_dragon", mob.getDisplayName()));
        }
        return true;
    }

    /** MUL QAH DIIV: you take on the aspect of a dragon: armor, stronger blows, and shouts that recover faster. */
    static boolean dragonAspect(ServerPlayer p, int w) {
        if (p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.DRAGON_ASPECT.get()))) {
            Notifier.message(p, Component.translatable("message.skycraft.dragon_aspect_active"));
            return false;
        }
        int seconds = new int[]{60, 150, 300}[w - 1];
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.DRAGON_ASPECT.get()), seconds * 20, w - 1, false, true, true));
        MagicFx.send(p, MagicFx.AURA, Element.FIRE, p.position(), p.position(), p.getId(), 4);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 0.8f, 1.2f);
        return true;
    }

    /** MID VUR SHAAN: your allies strike faster and harder. */
    static boolean battleFury(ServerPlayer p, int w) {
        int ticks = new int[]{30, 45, 60}[w - 1] * 20;
        List<LivingEntity> allies = Targeting.around(p, p.position(), 24, e -> e != p && Targeting.isFriendly(p, e));
        for (LivingEntity e : allies) {
            e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.HASTE), ticks, w - 1));
            e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.STRENGTH), ticks, w - 1));
            MagicFx.send(e, MagicFx.AURA, Element.FIRE, e.position(), e.position(), e.getId(), 1);
        }
        if (allies.isEmpty()) Notifier.message(p, Component.translatable("message.skycraft.no_ally"));
        return true;
    }
}
