package com.nirncraft.curse;

import com.nirncraft.combat.CombatHandler;
import com.nirncraft.core.Buffs;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.creatures.entity.CorpseEntity;
import com.nirncraft.crime.Crimes;
import com.nirncraft.magic.MagicDamage;
import com.nirncraft.magic.shout.SlowTime;
import com.nirncraft.magic.spell.Summons;
import com.nirncraft.network.NotifyKind;
import com.nirncraft.registry.ModEffects;
import com.nirncraft.vitals.Vitals;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The Vampire Lord (Dawnguard). A winged form with no time limit. On the ground it fights with claws and bites;
 * levitating (jump twice, it never rises far above the ground) it casts: Drain Life on the attack key, the chosen
 * night spell (Raise Dead, Vampiric Grip, Corpse Curse) on the use key, and the chosen power (Bats, Mist Form,
 * Supernatural Reflexes, Detect Life, Summon Gargoyle, Night Cloak) on the Voice key. Bites and Drain Life kills
 * feed it and earn perks. Sneak + Voice returns to mortal form.
 */
public final class VampireLord {
    static final net.minecraft.resources.Identifier ARMOR = net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "modifier_5d9a7c1e-2b44-4b5e-8f0e-3a6c9d1b7e01");
    static final net.minecraft.resources.Identifier KNOCKBACK = net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "modifier_5d9a7c1e-2b44-4b5e-8f0e-3a6c9d1b7e02");
    /** Vanilla's default flying speed (restored on revert). */
    private static final float DEFAULT_FLY = 0.05f;
    /** Levitation is a slow hover. */
    public static final float LORD_FLY = 0.025f;
    /** Highest the Vampire Lord floats above the ground (blocks); enforced on the client, which moves the player. */
    public static final double CEILING = 3.5;
    private static final float DRAIN_COST = 9f;
    private static final float DRAIN_RANGE = 24f;
    private static final String NIGHT_CLOAK = "night_cloak";
    /** Bats released by the Bats power: they fly off and are gone again a few seconds later. */
    private static final List<BatEntry> BATS = new ArrayList<>();

    private record BatEntry(Bat bat, long until) {}

    private VampireLord() {}

    // ------------------------------------------------------------------ changing form

    static boolean transform(ServerPlayer player) {
        if (!Curses.hasVampireLord(player) || Curses.transformed(player) || !player.isAlive()) return false;
        CompoundTag t = Curses.tag(player);
        t.putString(Curses.FORM, Form.LORD.id);
        t.remove(Curses.FORM_UNTIL);
        Curses.dirty(player);
        apply(player, true);
        Vampirism.refreshBonuses(player);
        PlayerData data = SkyData.get(player);
        data.setMagicka(data.maxMagicka());
        player.setHealth(player.getMaxHealth());
        SkyData.get(player).addStat("vampire_lord", 1);
        CursePackets.sync(player);
        CursePackets.anim(player, CurseAnims.Anim.TRANSFORM);
        ServerLevel level = player.level();
        level.playSound(null, player.blockPosition(), SoundEvents.PHANTOM_AMBIENT, SoundSource.PLAYERS, 1.4f, 0.5f);
        level.playSound(null, player.blockPosition(), SoundEvents.BAT_TAKEOFF, SoundSource.PLAYERS, 1.2f, 0.6f);
        burst(level, player.position().add(0, 1.2, 0), 40);
        Notifier.message(player, Component.translatable("curse.nirncraft.lord.transformed"));
        if (Crimes.canCommitCrime(player)) Crimes.report(player, player.blockPosition(), Lycanthropy.TRANSFORM_BOUNTY, false, null);
        return true;
    }

    static void revert(ServerPlayer player, boolean natural) {
        CompoundTag t = Curses.tag(player);
        if (Form.byId(t.getStringOr(Curses.FORM, "")) != Form.LORD) return;
        t.remove(Curses.FORM);
        Curses.dirty(player);
        apply(player, false);
        Buffs.clear(player, NIGHT_CLOAK);
        Vampirism.refreshBonuses(player);
        CursePackets.sync(player);
        CurseEvents.noFall(player, 100);
        if (natural && player.isAlive()) {
            CursePackets.anim(player, CurseAnims.Anim.TRANSFORM);
            burst(player.level(), player.position().add(0, 1.2, 0), 25);
            player.level().playSound(null, player.blockPosition(), SoundEvents.BAT_TAKEOFF, SoundSource.PLAYERS, 1f, 0.8f);
            Notifier.message(player, Component.translatable("curse.nirncraft.lord.reverted"));
        }
    }

    /** Levitation (flight, capped near the ground on the client), thick hide, night eye. */
    static void apply(ServerPlayer player, boolean on) {
        Lycanthropy.modifier(player.getAttribute(Attributes.ARMOR), ARMOR, "NirnCraft Vampire Lord", on ? 8 : 0,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        Lycanthropy.modifier(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE), KNOCKBACK, "NirnCraft Vampire Lord", on ? 0.4 : 0,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        var abilities = player.getAbilities();
        boolean changed = false;
        if (on) {
            if (!abilities.mayfly) {
                abilities.mayfly = true;
                changed = true;
            }
            if (abilities.getFlyingSpeed() != LORD_FLY && !player.isCreative() && !player.isSpectator()) {
                abilities.setFlyingSpeed(LORD_FLY);
                changed = true;
            }
            MobEffectInstance nv = player.getEffect(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION));
            if (nv == null || nv.getDuration() < 300) player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION), 20 * 60, 0, false, false, false));
        } else {
            if (!player.isCreative() && !player.isSpectator()) {
                abilities.mayfly = false;
                abilities.flying = false;
            }
            abilities.setFlyingSpeed(DEFAULT_FLY);
            changed = true;
            if (!Curses.tag(player).getBooleanOr(Curses.SIGHT, false)) player.removeEffect(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION));
        }
        if (changed) player.onUpdateAbilities();
    }

    /** Once a second while in Vampire Lord form. */
    static void tick(ServerPlayer player, long now) {
        apply(player, true);
        if (Buffs.active(player, NIGHT_CLOAK)) {
            ServerLevel level = player.level();
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.5),
                    e -> e != player && e.isAlive() && (e instanceof Enemy || e instanceof Mob m && m.getTarget() == player))) {
                if (CombatHandler.secondaryHurt(e, MagicDamage.source(level, MagicDamage.SPELL, null, player), 2f)) player.heal(1f);
                level.sendParticles(ParticleTypes.SQUID_INK, e.getX(), e.getY() + 1, e.getZ(), 3, 0.3, 0.4, 0.3, 0.01);
            }
        }
    }

    /** Server tick: bats from the Bats power fly off and vanish. */
    static void tickBats(long now) {
        for (Iterator<BatEntry> it = BATS.iterator(); it.hasNext(); ) {
            BatEntry b = it.next();
            if (now >= b.until || b.bat.isRemoved()) {
                if (!b.bat.isRemoved()) {
                    if (b.bat.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.SMOKE, b.bat.getX(), b.bat.getY(), b.bat.getZ(), 4, 0.1, 0.1, 0.1, 0.01);
                    b.bat.discard();
                }
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ casting (levitating)

    private static float cost(ServerPlayer player, float base) {
        return CursePerks.has(player, CursePerks.UNEARTHLY_WILL) ? base * 0.67f : base;
    }

    /** Drain Life: a red stream from the claws to whatever is in the crosshair, healing what it takes. */
    static void drainLife(ServerPlayer player) {
        if (Curses.form(player) != Form.LORD) return;
        if (!Vitals.consumeMagicka(player, cost(player, DRAIN_COST))) {
            Notifier.message(player, Component.translatable("curse.nirncraft.lord.no_magicka"));
            return;
        }
        ServerLevel level = player.level();
        Vec3 from = player.getEyePosition().add(player.getViewVector(1f).scale(0.6)).add(0, -0.35, 0);
        LivingEntity target = aimed(player, DRAIN_RANGE);
        Vec3 to = target != null ? target.position().add(0, target.getBbHeight() * 0.6, 0) : rayEnd(player, DRAIN_RANGE);
        beam(level, from, to, new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, 0.75f, 0.05f, 0.08f), 1.1f));
        CursePackets.anim(player, CurseAnims.Anim.CAST);
        if (target == null) return;
        int stage = Math.max(1, Curses.stage(player));
        float amount = 2.5f + 0.4f * stage + SkyData.get(player).getLevel() * 0.05f;
        DamageSource src = MagicDamage.source(level, MagicDamage.SPELL, null, player);
        if (com.nirncraft.platform.Effects.hurt(target, src, amount)) {
            player.heal(amount);
            if (target instanceof ServerPlayer tp) {
                PlayerData td = SkyData.get(tp);
                td.setStamina(td.getStamina() - amount * 4);
            }
            if (player.tickCount % 4 == 0) level.playSound(null, target.blockPosition(), SoundEvents.HONEY_DRINK.value(), SoundSource.PLAYERS, 0.6f, 0.5f);
            if (!target.isAlive() && CurseCombat.isPerson(target)) killedByFeeding(player, target);
        }
    }

    /** The night spell on the use key. */
    static void castSpell(ServerPlayer player) {
        if (Curses.form(player) != Form.LORD) return;
        String id = CursePowers.selected(player, Curses.SELECTED_SPELL, CursePowers.RAISE_DEAD);
        ServerLevel level = player.level();
        switch (id) {
            case CursePowers.RAISE_DEAD -> {
                CorpseEntity corpse = aimedCorpse(player, 20);
                if (corpse == null) {
                    Notifier.message(player, Component.translatable("curse.nirncraft.lord.no_corpse"));
                    return;
                }
                CompoundTag saved = com.nirncraft.platform.EntityData.saveWithoutId(corpse);
                EntityType<?> type = com.nirncraft.platform.EntityData.type(saved.getStringOr("BodyType","")).orElse(null);
                Entity probe = type == null || type == net.minecraft.world.entity.EntityTypes.PLAYER ? null : type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
                if (!(probe instanceof Mob) || type.builtInRegistryHolder().is(CombatHandler.BOSSES)) {
                    if (probe != null) probe.discard();
                    Notifier.message(player, Component.translatable("message.nirncraft.corpse_cannot_rise"));
                    return;
                }
                probe.discard();
                if (!Vitals.consumeMagicka(player, cost(player, 40))) {
                    Notifier.message(player, Component.translatable("curse.nirncraft.lord.no_magicka"));
                    return;
                }
                net.minecraft.nbt.ListTag loot = saved.getListOrEmpty("Loot");
                for (int i = 0; i < loot.size(); i++) {
                    net.minecraft.world.item.ItemStack stack = com.nirncraft.inventory.ItemData.parse(loot.getCompoundOrEmpty(i));
                    if (!stack.isEmpty()) corpse.spawnAtLocation(level, stack, 0.3f);
                }
                Vec3 at = corpse.position();
                corpse.discard();
                @SuppressWarnings("unchecked")
                EntityType<? extends Mob> mobType = (EntityType<? extends Mob>) type;
                Mob thrall = Summons.conjureAt(player, mobType, "thrall", at, 20 * 60, m -> {
                    m.setCanPickUpLoot(false);
                    m.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.FIRE_RESISTANCE), 20 * 60, 0, false, false));
                });
                if (thrall != null) {
                    thrall.setCustomName(Component.translatable("entity.nirncraft.summon.thrall", thrall.getType().getDescription()));
                    level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 0.5, at.z, 20, 0.4, 0.5, 0.4, 0.02);
                    level.playSound(null, thrall.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.8f, 0.5f);
                }
            }
            case CursePowers.VAMPIRIC_GRIP -> {
                LivingEntity target = aimed(player, 20);
                if (target == null || target.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) return;
                if (!Vitals.consumeMagicka(player, cost(player, 35))) {
                    Notifier.message(player, Component.translatable("curse.nirncraft.lord.no_magicka"));
                    return;
                }
                Vec3 pull = player.position().add(player.getViewVector(1f).scale(2)).subtract(target.position()).scale(0.25);
                target.setDeltaMovement(pull.x, Math.min(0.9, pull.y + 0.45), pull.z);
                target.syncVelocity = true;
                com.nirncraft.platform.Effects.hurt(target, MagicDamage.source(level, MagicDamage.SPELL, null, player), 3f);
                target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(ModEffects.STAGGER.get()), 40), player);
                beam(level, player.getEyePosition(), target.getEyePosition(), ParticleTypes.SCULK_SOUL);
            }
            case CursePowers.CORPSE_CURSE -> {
                LivingEntity target = aimed(player, 20);
                if (target == null) return;
                if (!Vitals.consumeMagicka(player, cost(player, 50))) {
                    Notifier.message(player, Component.translatable("curse.nirncraft.lord.no_magicka"));
                    return;
                }
                if (!target.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), 20 * 8), player);
                target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.WITHER), 20 * 8, 0), player);
                beam(level, player.getEyePosition(), target.getEyePosition(), ParticleTypes.SOUL_FIRE_FLAME);
            }
            default -> {
                return;
            }
        }
        CursePackets.anim(player, CurseAnims.Anim.CAST);
        level.playSound(null, player.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.8f, 0.6f);
    }

    /** The power on the Voice key. */
    static void power(ServerPlayer player, String id) {
        if (id.equals(CursePowers.REVERT)) {
            revert(player, true);
            return;
        }
        if (!CursePowers.hasAbility(player, id) || !CursePowers.ready(player, id)) return;
        ServerLevel level = player.level();
        switch (id) {
            case CursePowers.BATS -> {
                if (!Vitals.consumeStamina(player, 20f, false)) {
                    Notifier.message(player, Component.translatable("curse.nirncraft.lord.no_stamina"));
                    return;
                }
                Vec3 from = player.position();
                Vec3 dir = player.getViewVector(1f);
                Vec3 eye = player.getEyePosition();
                BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(16)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                Vec3 dest = hit.getLocation().subtract(dir.scale(1.0)).subtract(0, player.getEyeHeight(), 0);
                burst(level, from.add(0, 1, 0), 30);
                releaseBats(level, from.add(0, 1.2, 0), 5);
                player.teleportTo(dest.x, Math.max(dest.y, from.y - 3), dest.z);
                player.fallDistance = 0;
                CurseEvents.noFall(player, 60);
                burst(level, player.position().add(0, 1, 0), 30);
                level.playSound(null, player.blockPosition(), SoundEvents.BAT_TAKEOFF, SoundSource.PLAYERS, 1.2f, 0.7f);
            }
            case CursePowers.MIST_FORM -> {
                player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.INVISIBILITY), 20 * 8, 0, false, false, true));
                player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.RESISTANCE), 20 * 8, 4, false, false, true));
                player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.REGENERATION), 20 * 8, 1, false, false, true));
                level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 1, player.getZ(), 40, 0.6, 0.9, 0.6, 0.01);
                level.playSound(null, player.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1f, 0.6f);
            }
            case CursePowers.SUPERNATURAL_REFLEXES -> {
                SlowTime.start(player, 2);
                level.playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1f, 0.5f);
            }
            case CursePowers.DETECT_LIFE -> {
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(48),
                        e -> e != player && e.isAlive() && !CurseCombat.isUndead(e))) {
                    e.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.GLOWING), 20 * 30, 0, false, false));
                }
                level.playSound(null, player.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1f, 1.2f);
            }
            case CursePowers.SUMMON_GARGOYLE -> {
                Mob g = Summons.conjure(player, CurseRegistry.GARGOYLE.get(), "gargoyle", 8, 20 * 120, m -> {
                    if (m instanceof com.nirncraft.curse.entity.GargoyleEntity ge) ge.setDormant(false);
                });
                if (g == null) return;
                level.sendParticles(ParticleTypes.LARGE_SMOKE, g.getX(), g.getY() + 1, g.getZ(), 20, 0.5, 1, 0.5, 0.02);
                level.playSound(null, g.blockPosition(), SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 1.2f, 0.5f);
            }
            case CursePowers.NIGHT_CLOAK -> {
                Buffs.apply(player, NIGHT_CLOAK, 20 * 60);
                releaseBats(level, player.position().add(0, 1.5, 0), 3);
                level.playSound(null, player.blockPosition(), SoundEvents.BAT_AMBIENT, SoundSource.PLAYERS, 1f, 0.6f);
            }
            default -> {
                return;
            }
        }
        CursePowers.startCooldown(player, id);
        CursePackets.anim(player, id.equals(CursePowers.BATS) ? CurseAnims.Anim.CAST : CurseAnims.Anim.HOWL);
    }

    // ------------------------------------------------------------------ feeding (bites)

    /**
     * Bite (use key on a person while on the ground): kills a sleeping, unaware or badly wounded person and feeds on
     * them. Bosses can't be bitten.
     */
    static boolean bite(ServerPlayer player, LivingEntity victim) {
        if (Curses.form(player) != Form.LORD || !victim.isAlive() || player.distanceToSqr(victim) > 4 * 4) return false;
        if (!CurseCombat.isPerson(victim) || victim.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) return false;
        boolean unaware = !(victim instanceof Mob m) || m.getTarget() != player;
        boolean weak = victim.getHealth() <= victim.getMaxHealth() * 0.5f;
        if (!victim.isSleeping() && !weak && !(unaware && !(victim instanceof Player))) {
            CurseCombat.tooStrong(player);
            return true;
        }
        CursePackets.anim(player, CurseAnims.Anim.FEED);
        ServerLevel level = player.level();
        level.playSound(null, victim.blockPosition(), SoundEvents.PHANTOM_BITE, SoundSource.PLAYERS, 1.2f, 0.5f);
        level.sendParticles(new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, 0.6f, 0.02f, 0.05f), 1.4f), victim.getX(), victim.getY() + victim.getBbHeight() * 0.8,
                victim.getZ(), 30, 0.25, 0.25, 0.25, 0.05);
        com.nirncraft.platform.Effects.hurt(victim, player.damageSources().playerAttack(player), victim.getHealth() + victim.getAbsorptionAmount() + 50f);
        if (!victim.isAlive()) killedByFeeding(player, victim);
        return true;
    }

    /** A person died to a bite or Drain Life: the Vampire Lord feeds. */
    private static void killedByFeeding(ServerPlayer player, LivingEntity victim) {
        Vampirism.fed(player);
        if (CursePerks.has(player, CursePerks.BLOOD_HEALING)) player.heal(player.getMaxHealth() * 0.5f);
        CursePerks.addFeed(player, CursePerks.Tree.VAMPIRE_LORD);
        Notifier.send(player, NotifyKind.MESSAGE, Component.translatable("curse.nirncraft.lord.fed"), Component.empty());
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    static LivingEntity aimed(ServerPlayer player, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = rayEnd(player, range);
        AABB box = player.getBoundingBox().expandTowards(player.getViewVector(1f).scale(range)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
                e -> e instanceof LivingEntity l && l.isAlive() && !e.isSpectator() && e != player && !player.getUUID().equals(Summons.ownerOf(e)),
                range * range);
        return hit != null && hit.getEntity() instanceof LivingEntity l ? l : null;
    }

    private static Vec3 rayEnd(ServerPlayer player, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1f).scale(range));
        BlockHitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return block.getType() == HitResult.Type.MISS ? end : block.getLocation();
    }

    @Nullable
    private static CorpseEntity aimedCorpse(ServerPlayer player, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 limit = rayEnd(player, range);
        CorpseEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (CorpseEntity c : player.level().getEntitiesOfClass(CorpseEntity.class, new AABB(eye, limit).inflate(1.5))) {
            var hit = c.getBoundingBox().inflate(0.6).clip(eye, limit);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < bestDist) {
                bestDist = eye.distanceToSqr(hit.get());
                best = c;
            }
        }
        return best;
    }

    private static void beam(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle) {
        Vec3 d = to.subtract(from);
        int steps = Math.max(2, (int) (d.length() * 3));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.add(d.scale(i / (double) steps));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0);
        }
    }

    /** A swirl of shadow and blood (transformations, Bats). */
    private static void burst(ServerLevel level, Vec3 at, int n) {
        level.sendParticles(ParticleTypes.SQUID_INK, at.x, at.y, at.z, n / 2, 0.5, 0.8, 0.5, 0.05);
        level.sendParticles(new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, 0.45f, 0.02f, 0.04f), 1.2f), at.x, at.y, at.z, n, 0.6, 0.9, 0.6, 0.02);
    }

    private static void releaseBats(ServerLevel level, Vec3 at, int n) {
        long until = level.getGameTime() + 50;
        for (int i = 0; i < n; i++) {
            Bat bat = net.minecraft.world.entity.EntityTypes.BAT.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (bat == null) continue;
            bat.snapTo(at.x + level.getRandom().nextGaussian() * 0.4, at.y + level.getRandom().nextGaussian() * 0.3, at.z + level.getRandom().nextGaussian() * 0.4, 0, 0);
            bat.setResting(false);
            bat.setPermanentlyInvulnerable(true);
            bat.setSilent(i > 1);
            bat.addTag("nirncraft_lord_bat");
            if (level.addFreshEntity(bat)) BATS.add(new BatEntry(bat, until + level.getRandom().nextInt(20)));
        }
    }

    static void clearBats() {
        for (BatEntry b : BATS) if (!b.bat.isRemoved()) b.bat.discard();
        BATS.clear();
    }
}
