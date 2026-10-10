package com.skycraft.magic.spell;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.Targeting;
import com.skycraft.perk.Perks;
import com.skycraft.vitals.Vitals;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Illusion: mind-affecting spells and the AI behaviour of calmed, feared and frenzied creatures.
 *
 * <p>Skyrim limits illusion spells by target level; we approximate level with max health. Each spell level affects
 * creatures up to a health threshold, raised by Animage (animals), Kindred Mage (people), Hypnotic Gaze (calm),
 * Aspect of Terror (fear) and Rage (frenzy). Undead and constructs need Master of the Mind. Players and bosses
 * are never affected.</p>
 */
public final class Illusion {
    public static final String FEAR_X = "skycraft_fear_x";
    public static final String FEAR_Y = "skycraft_fear_y";
    public static final String FEAR_Z = "skycraft_fear_z";
    public static final String INVIS_FLAG = "skycraft_spell_invisibility";

    private static final float[] BASE_THRESHOLD = {24f, 32f, 48f, 70f, 120f};

    private Illusion() {}

    /** People: villagers, illagers, witches and piglins (Kindred Mage). */
    public static boolean isPerson(LivingEntity e) {
        return e instanceof AbstractVillager || e instanceof AbstractIllager || e instanceof Witch || e instanceof AbstractPiglin
                || e.getType().getDescriptionId().contains("skycraft");
    }

    public static float threshold(Player caster, Tier tier, @Nullable LivingEntity target, String kind) {
        float t = BASE_THRESHOLD[tier.ordinal()];
        if (target instanceof Animal && Perks.has(caster, "illusion.animage")) t *= 1.5f;
        if (target != null && isPerson(target) && Perks.has(caster, "illusion.kindred_mage")) t *= 1.5f;
        switch (kind) {
            case "calm" -> {
                if (Perks.has(caster, "illusion.hypnotic_gaze")) t *= 1.5f;
            }
            case "fear" -> {
                if (Perks.has(caster, "illusion.aspect_of_terror")) t *= 1.5f;
            }
            case "frenzy" -> {
                if (Perks.has(caster, "illusion.rage")) t *= 1.5f;
            }
            default -> {
            }
        }
        return t;
    }

    /**
     * Whether an illusion spell works on a creature. Dual casting raises the level limit by half. Players are handled
     * by {@link #onPlayer} instead.
     */
    public static boolean affects(ServerPlayer caster, Spell spell, LivingEntity target, String kind, boolean feedback) {
        if (!(target instanceof Mob) || target instanceof ArmorStand || target.getType().is(CombatHandler.BOSSES)) return false;
        Tier tier = spell.tier;
        float dualMult = spell.dual ? 1.5f : 1f;
        boolean resistant = target.getMobType() == MobType.UNDEAD || target instanceof AbstractGolem;
        if (resistant && !Perks.has(caster, "illusion.master_of_the_mind")) {
            if (feedback) Notifier.message(caster, Component.translatable("message.skycraft.illusion_immune", target.getDisplayName()));
            return false;
        }
        if (target.getMaxHealth() > threshold(caster, tier, target, kind) * dualMult) {
            if (feedback) Notifier.message(caster, Component.translatable("message.skycraft.illusion_too_strong", target.getDisplayName()));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ applying states

    public static void applyCalm(LivingEntity target, int ticks) {
        target.removeEffect(MagicRegistry.FRENZY.get());
        target.removeEffect(MagicRegistry.FEAR.get());
        target.addEffect(new MobEffectInstance(MagicRegistry.CALM.get(), ticks, 0));
        if (target instanceof Mob mob) clearAggression(mob);
    }

    public static void applyFear(Vec3 from, LivingEntity target, int ticks) {
        target.removeEffect(MagicRegistry.CALM.get());
        target.removeEffect(MagicRegistry.FRENZY.get());
        target.addEffect(new MobEffectInstance(MagicRegistry.FEAR.get(), ticks, 0));
        CompoundTag pd = target.getPersistentData();
        pd.putDouble(FEAR_X, from.x);
        pd.putDouble(FEAR_Y, from.y);
        pd.putDouble(FEAR_Z, from.z);
        if (target instanceof Mob mob) {
            clearAggression(mob);
            flee(mob);
        }
    }

    public static void applyFrenzy(LivingEntity target, int ticks) {
        target.removeEffect(MagicRegistry.CALM.get());
        target.removeEffect(MagicRegistry.FEAR.get());
        target.addEffect(new MobEffectInstance(MagicRegistry.FRENZY.get(), ticks, 0));
        if (target instanceof Mob mob) frenzyRetarget(mob, null);
    }

    private static void clearAggression(Mob mob) {
        mob.setTarget(null);
        mob.setAggressive(false);
        if (mob instanceof NeutralMob neutral) neutral.stopBeingAngry();
        mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
        if (mob instanceof Creeper creeper) creeper.setSwellDir(-1);
    }

    private static void flee(Mob mob) {
        if (!(mob instanceof PathfinderMob pm)) return;
        CompoundTag pd = mob.getPersistentData();
        Vec3 from = pd.contains(FEAR_X) ? new Vec3(pd.getDouble(FEAR_X), pd.getDouble(FEAR_Y), pd.getDouble(FEAR_Z)) : mob.position();
        Vec3 away = DefaultRandomPos.getPosAway(pm, 16, 7, from);
        if (away != null) pm.getNavigation().moveTo(away.x, away.y, away.z, 1.35);
    }

    private static void frenzyRetarget(Mob mob, @Nullable LivingEntity exclude) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(16),
                e -> e != mob && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand) && !(e instanceof Player p && p.isCreative()))) {
            double d = e.distanceToSqr(mob);
            if (e instanceof Player) d += 64; // prefers other creatures, like Skyrim's Fury
            if (e == exclude) d += 400;
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        if (best != null) mob.setTarget(best);
    }

    /** Behaviour of calmed / feared / frenzied mobs, called every 5 ticks from the living tick event. */
    public static void tickMob(Mob mob) {
        if (mob.hasEffect(MagicRegistry.CALM.get())) {
            if (mob.getTarget() != null || mob.isAggressive()) clearAggression(mob);
        } else if (mob.hasEffect(MagicRegistry.FEAR.get())) {
            if (mob.getTarget() != null) clearAggression(mob);
            if (mob instanceof Creeper creeper) creeper.setSwellDir(-1);
            if (mob.getNavigation().isDone() || mob.tickCount % 40 == 0) flee(mob);
        } else if (mob.hasEffect(MagicRegistry.FRENZY.get())) {
            LivingEntity t = mob.getTarget();
            if (t == null || !t.isAlive() || mob.tickCount % 60 == 0) frenzyRetarget(mob, null);
        }
    }

    /**
     * PvP: illusion magic only briefly hinders other players (when the server allows PvP and they aren't in the
     * caster's party): Fear slows and weakens, Calm leaves them too weak to hurt anyone, Fury/Frenzy disorients.
     *
     * @return -1 if the target isn't a player (use the creature rules), 0 if it is one that can't be affected,
     *         1 if it was affected
     */
    public static int onPlayer(ServerPlayer caster, LivingEntity target, String kind) {
        if (!(target instanceof ServerPlayer tp)) return -1;
        if (tp == caster || !Targeting.canHarm(caster, tp)) return 0;
        switch (kind) {
            case "fear" -> {
                tp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1));
                tp.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
                Notifier.message(tp, Component.translatable("message.skycraft.pvp_feared", caster.getDisplayName()));
            }
            case "calm" -> {
                tp.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 4));
                Notifier.message(tp, Component.translatable("message.skycraft.pvp_calmed", caster.getDisplayName()));
            }
            default -> {
                tp.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0));
                Notifier.message(tp, Component.translatable("message.skycraft.pvp_frenzied", caster.getDisplayName()));
            }
        }
        mindFx(tp.serverLevel(), tp);
        Vitals.markInCombat(caster);
        Vitals.markInCombat(tp);
        return 1;
    }

    // ------------------------------------------------------------------ spells

    private static void mindFx(ServerLevel level, LivingEntity target) {
        MagicFx.send(target, MagicFx.AURA, Element.MIND, target.position(), target.position(), target.getId(), 0);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ILLUSIONER_PREPARE_MIRROR, SoundSource.PLAYERS, 0.6f, 1.4f);
    }

    public static void furyHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        MagicFx.burst((ServerLevel) proj.level(), pos, Element.MIND, 0.6f);
        if (target == null) return;
        int pvp = onPlayer(caster, target, "frenzy");
        if (pvp >= 0) {
            if (pvp > 0) SpellEffects.xp(caster, spell);
            return;
        }
        if (!affects(caster, spell, target, "frenzy", true)) return;
        applyFrenzy(target, spell.duration);
        mindFx((ServerLevel) proj.level(), target);
        SpellEffects.xp(caster, spell);
    }

    public static void calmHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        MagicFx.burst((ServerLevel) proj.level(), pos, Element.MIND, 0.6f);
        if (target == null) return;
        int pvp = onPlayer(caster, target, "calm");
        if (pvp >= 0) {
            if (pvp > 0) SpellEffects.xp(caster, spell);
            return;
        }
        if (!affects(caster, spell, target, "calm", true)) return;
        applyCalm(target, spell.duration);
        mindFx((ServerLevel) proj.level(), target);
        SpellEffects.xp(caster, spell);
    }

    public static void fearHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        MagicFx.burst((ServerLevel) proj.level(), pos, Element.MIND, 0.6f);
        if (target == null) return;
        int pvp = onPlayer(caster, target, "fear");
        if (pvp >= 0) {
            if (pvp > 0) SpellEffects.xp(caster, spell);
            return;
        }
        if (!affects(caster, spell, target, "fear", true)) return;
        applyFear(caster.position(), target, spell.duration);
        mindFx((ServerLevel) proj.level(), target);
        SpellEffects.xp(caster, spell);
    }

    /**
     * Turn Lesser Undead, Turn Undead, Turn Greater Undead: undead up to the spell's level flee (no Master of the Mind
     * needed, it's a Restoration spell).
     */
    public static void turnUndeadHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        MagicFx.burst((ServerLevel) proj.level(), pos, Element.HOLY, 0.8f);
        if (target == null || target.getMobType() != MobType.UNDEAD || target.getType().is(CombatHandler.BOSSES)) return;
        if (!RestorationSpells.turnable(caster, spell, target)) {
            Notifier.message(caster, Component.translatable("message.skycraft.illusion_too_strong", target.getDisplayName()));
            return;
        }
        applyFear(caster.position(), target, spell.duration);
        target.setSecondsOnFire(2);
        MagicFx.send(target, MagicFx.HEAL, Element.HOLY, target.position(), target.position(), target.getId(), 1);
        SpellEffects.xp(caster, spell);
    }

    private static int area(ServerPlayer caster, Spell spell, Vec3 pos, String kind) {
        int n = 0;
        List<LivingEntity> targets = Targeting.around(caster, pos, spell.radius, e -> e != caster && !Targeting.isFriendly(caster, e));
        for (LivingEntity e : targets) {
            int pvp = onPlayer(caster, e, kind);
            if (pvp >= 0) {
                n += pvp;
                continue;
            }
            if (!affects(caster, spell, e, kind, false)) continue;
            switch (kind) {
                case "calm" -> applyCalm(e, spell.duration);
                case "fear" -> applyFear(pos, e, spell.duration);
                default -> applyFrenzy(e, spell.duration);
            }
            MagicFx.send(e, MagicFx.AURA, Element.MIND, e.position(), e.position(), e.getId(), 0);
            n++;
        }
        return n;
    }

    public static void pacifyHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.MIND, spell.radius);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1f, 1.5f);
        if (area(caster, spell, pos, "calm") > 0) SpellEffects.xp(caster, spell);
    }

    public static void frenzyHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.MIND, spell.radius);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.EVOKER_PREPARE_WOLOLO, SoundSource.PLAYERS, 1f, 0.8f);
        if (area(caster, spell, pos, "frenzy") > 0) {
            SpellEffects.xp(caster, spell);
            Vitals.markInCombat(caster);
        }
    }

    public static Spell.Result harmony(ServerPlayer p, Spell spell, int tick) {
        MagicFx.sendNear(p.serverLevel(), p.position(), 96, MagicFx.RING, Element.MIND, p.position().add(0, 0.2, 0), p.position(), p.getId(), Math.round(spell.radius * 10));
        MagicFx.sound(p, SoundEvents.ILLUSIONER_CAST_SPELL, 1.5f, 0.7f);
        return area(p, spell, p.position(), "calm") > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static Spell.Result mayhem(ServerPlayer p, Spell spell, int tick) {
        MagicFx.sendNear(p.serverLevel(), p.position(), 96, MagicFx.RING, Element.FIRE, p.position().add(0, 0.2, 0), p.position(), p.getId(), Math.round(spell.radius * 10));
        MagicFx.sound(p, SoundEvents.EVOKER_PREPARE_WOLOLO, 1.5f, 0.6f);
        Vitals.markInCombat(p);
        return area(p, spell, p.position(), "frenzy") > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    private static void embolden(LivingEntity e, int ticks) {
        e.removeEffect(MagicRegistry.FEAR.get());
        e.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, ticks, 0));
        e.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ticks, 1));
        MagicFx.send(e, MagicFx.AURA, Element.HOLY, e.position(), e.position(), e.getId(), 0);
    }

    /** Courage: the ally you aim at (or your own summons nearby) won't flee and fights harder. */
    public static Spell.Result courage(ServerPlayer p, Spell spell, int tick) {
        Targeting.Ray ray = Targeting.ray(p, spell.radius, e -> Targeting.isHelpable(p, e) && !(e instanceof Player));
        if (ray.entity() != null) {
            embolden(ray.entity(), spell.duration);
            MagicFx.sound(p, SoundEvents.ILLUSIONER_CAST_SPELL, 1f, 1.6f);
            return Spell.Result.EFFECT;
        }
        int n = 0;
        for (LivingEntity e : Targeting.around(p, p.position(), 16, e -> e != p && Targeting.isFriendly(p, e))) {
            embolden(e, spell.duration);
            n++;
        }
        if (n == 0) {
            Notifier.message(p, Component.translatable("message.skycraft.no_ally"));
            return Spell.Result.FAILED;
        }
        MagicFx.sound(p, SoundEvents.ILLUSIONER_CAST_SPELL, 1f, 1.6f);
        return Spell.Result.EFFECT;
    }

    /** Rally: every ally around you is emboldened. */
    public static Spell.Result rally(ServerPlayer p, Spell spell, int tick) {
        int n = 0;
        for (LivingEntity e : Targeting.around(p, p.position(), spell.radius, e -> e != p && !(e instanceof Player)
                && Targeting.isHelpable(p, e) && !(e instanceof net.minecraft.world.entity.monster.Enemy) || Targeting.isFriendly(p, e) && e != p)) {
            embolden(e, spell.duration);
            n++;
        }
        MagicFx.sendNear(p.serverLevel(), p.position(), 64, MagicFx.RING, Element.HOLY, p.position().add(0, 0.2, 0), p.position(), p.getId(), Math.round(spell.radius * 10));
        MagicFx.sound(p, SoundEvents.ILLUSIONER_CAST_SPELL, 1.2f, 1.3f);
        return n > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static Spell.Result muffle(ServerPlayer p, Spell spell, int tick) {
        MobEffectInstance current = p.getEffect(MagicRegistry.MUFFLE.get());
        boolean wasActive = current != null && current.getDuration() > spell.duration / 2;
        p.addEffect(new MobEffectInstance(MagicRegistry.MUFFLE.get(), spell.duration, 0, false, true, true));
        MagicFx.send(p, MagicFx.AURA, Element.MIND, p.position(), p.position(), p.getId(), 0);
        p.playNotifySound(SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 1f, 0.6f);
        return SpellEffects.buffResult(p, wasActive);
    }

    public static Spell.Result invisibility(ServerPlayer p, Spell spell, int tick) {
        boolean wasActive = p.hasEffect(MobEffects.INVISIBILITY);
        boolean useful = !wasActive && SpellEffects.hostilesNear(p, 24);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, spell.duration, 0, false, false, true));
        p.getPersistentData().putBoolean(INVIS_FLAG, true);
        MagicFx.send(p, MagicFx.AURA, Element.MIND, p.position(), p.position(), p.getId(), 3);
        p.playNotifySound(SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1f, 1f);
        // Enemies lose track of you.
        for (Mob mob : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32), m -> m.getTarget() == p)) {
            mob.setTarget(null);
        }
        return useful ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    // ------------------------------------------------------------------ added spells

    /** Rout: creatures and people in the target area flee for 30 seconds. */
    public static void routHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.MIND, spell.radius);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.PLAYERS, 1f, 0.7f);
        if (area(caster, spell, pos, "fear") > 0) SpellEffects.xp(caster, spell);
    }

    /** Hysteria: everything around you flees in terror for 30 seconds. */
    public static Spell.Result hysteria(ServerPlayer p, Spell spell, int tick) {
        MagicFx.sendNear(p.serverLevel(), p.position(), 96, MagicFx.RING, Element.SOUL, p.position().add(0, 0.2, 0), p.position(), p.getId(),
                Math.round(spell.radius * 10));
        MagicFx.sound(p, SoundEvents.EVOKER_PREPARE_ATTACK, 1.5f, 0.5f);
        MagicFx.sound(p, SoundEvents.WARDEN_ROAR, 0.4f, 1.5f);
        return area(p, spell, p.position(), "fear") > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Call to Arms: allies around you fight harder, take less damage and regain stamina for 60 seconds. */
    public static Spell.Result callToArms(ServerPlayer p, Spell spell, int tick) {
        int n = 0;
        for (LivingEntity e : Targeting.around(p, p.position(), spell.radius, e -> e != p && (Targeting.isFriendly(p, e)
                || !(e instanceof net.minecraft.world.entity.monster.Enemy) && Targeting.isHelpable(p, e) && !(e instanceof net.minecraft.world.entity.animal.Animal)))) {
            embolden(e, spell.duration);
            e.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, spell.duration, 0));
            e.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, spell.duration, 1));
            if (e instanceof ServerPlayer tp) {
                com.skycraft.core.PlayerData data = com.skycraft.core.SkyData.get(tp);
                data.setStamina(data.maxStamina());
            }
            n++;
        }
        MagicFx.sendNear(p.serverLevel(), p.position(), 96, MagicFx.RING, Element.HOLY, p.position().add(0, 0.2, 0), p.position(), p.getId(),
                Math.round(spell.radius * 10));
        MagicFx.sound(p, SoundEvents.RAID_HORN.value(), 0.6f, 1.4f);
        return n > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Last game time each player got Clairvoyance XP (it trains once per 30 s, not per second of staring). */
    private static final java.util.Map<java.util.UUID, Long> CLAIRVOYANCE_XP = new java.util.HashMap<>();

    /**
     * Clairvoyance: while held, a trail of light shows the way to your nearest quest objective (or, with none, the
     * nearest town).
     */
    public static Spell.Result clairvoyance(ServerPlayer p, Spell spell, int tick) {
        Vec3 goal = clairvoyanceGoal(p);
        if (goal == null) {
            if (tick == 0) Notifier.message(p, Component.translatable("message.skycraft.clairvoyance_nothing"));
            return tick == 0 ? Spell.Result.FAILED : Spell.Result.CAST;
        }
        if (tick % 10 == 0) {
            Vec3 from = p.position().add(0, 0.3, 0);
            Vec3 dir = goal.subtract(from);
            double len = Math.min(spell.radius, dir.length());
            Vec3 to = len < 1e-3 ? from : from.add(dir.normalize().scale(len));
            // only the caster sees the path
            com.skycraft.network.SkyNetwork.sendToPlayer(p, new com.skycraft.magic.MagicPackets.Fx(MagicFx.PATH, Element.MIND.ordinal(), from, to, p.getId(), 0));
        }
        if (tick % 20 == 0) MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.4f, 1.6f);
        long now = p.level().getGameTime();
        Long last = CLAIRVOYANCE_XP.get(p.getUUID());
        if (tick == 20 && (last == null || now - last > 600)) {
            CLAIRVOYANCE_XP.put(p.getUUID(), now);
            return Spell.Result.EFFECT;
        }
        return Spell.Result.CAST;
    }

    @Nullable
    private static Vec3 clairvoyanceGoal(ServerPlayer p) {
        net.minecraft.nbt.ListTag markers = com.skycraft.core.SkyData.get(p).module("quest").getList("markers", net.minecraft.nbt.Tag.TAG_COMPOUND);
        String dim = p.level().dimension().location().toString();
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < markers.size(); i++) {
            CompoundTag m = markers.getCompound(i);
            if (!m.getString("dim").isEmpty() && !m.getString("dim").equals(dim)) continue;
            Vec3 v = new Vec3(m.getInt("x") + 0.5, m.getInt("y"), m.getInt("z") + 0.5);
            double d = v.distanceToSqr(p.position());
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        if (best != null || p.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return best;
        // no objective: the nearest settlement
        com.skycraft.tamriel.Place town = null;
        long bestSq = Long.MAX_VALUE;
        for (com.skycraft.tamriel.Place pl : com.skycraft.tamriel.Places.all()) {
            if (!pl.type().isSettlement()) continue;
            long d = pl.distSqr(p.getBlockX(), p.getBlockZ());
            if (d < bestSq && d > (long) pl.radius() * pl.radius()) {
                bestSq = d;
                town = pl;
            }
        }
        return town == null ? null : new Vec3(town.x() + 0.5, p.getY(), town.z() + 0.5);
    }

    public static void forget(java.util.UUID id) {
        CLAIRVOYANCE_XP.remove(id);
    }
}
