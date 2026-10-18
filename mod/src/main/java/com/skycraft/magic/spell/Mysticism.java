package com.skycraft.magic.spell;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.Targeting;
import com.skycraft.perk.Perks;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mysticism, the sixth school (Oblivion and Morrowind): Telekinesis, Detect Life, Soul Trap, Slowfall, Dispel,
 * Water Walking, the Absorb spells, Mark and Recall, Divine and Almsivi Intervention, Reflect Spell, Spell Absorption,
 * Levitate, Telekinetic Hurl, Spell Turning and Mass Dispel.
 */
public final class Mysticism {
    /** Persistent data: this player's flight was granted by Levitate (so we take it away again). */
    private static final String LEVITATING = "skycraft_levitating";
    /** Persistent data on a thrown item: who threw it and until when it hurts what it hits. */
    private static final String THROWN_BY = "skycraft_tk_thrower";
    private static final String THROWN_UNTIL = "skycraft_tk_until";

    /** Telekinesis: item entity each player is holding. */
    private static final Map<UUID, Integer> HELD = new HashMap<>();
    /** Telekinesis: arrow entity each player has intercepted/grabbed. */
    private static final Map<UUID, Integer> HELD_ARROWS = new HashMap<>();
    /** Telekinesis XP is granted once per item/arrow grabbed. */
    private static final Map<UUID, UUID> LAST_GRAB = new HashMap<>();
    private static final List<ItemEntity> THROWN = new ArrayList<>();

    private Mysticism() {}

    /** Duration of a Mysticism effect: +50% with Mystic Mastery, +50% for movement spells with Featherweight. */
    static int duration(ServerPlayer p, Spell spell, boolean movement) {
        float d = spell.duration;
        if (Perks.has(p, "mysticism.mystic_mastery")) d *= 1.5f;
        if (movement && Perks.has(p, "mysticism.featherweight")) d *= 1.5f;
        return Math.round(d);
    }

    private static double reach(ServerPlayer p, Spell spell) {
        return Perks.has(p, "mysticism.far_sight") ? spell.radius * 2 : spell.radius;
    }

    // ================================================================== Telekinesis

    /** Intercepts an incoming or aimed arrow while channeling Telekinesis. */
    public static boolean catchArrow(ServerPlayer p, net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) {
        if (p == null || arrow == null || !arrow.isAlive() || !SpellCasting.isCasting(p, "telekinesis")) return false;
        Integer curArrowId = HELD_ARROWS.get(p.getUUID());
        if (curArrowId != null && curArrowId != arrow.getId()
                && p.level().getEntity(curArrowId) instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow existing
                && existing.isAlive()) {
            return false;
        }
        // Release any held item if we are catching an arrow
        Integer itemId = HELD.remove(p.getUUID());
        if (itemId != null && p.level().getEntity(itemId) instanceof ItemEntity ie && ie.isAlive()) {
            ie.setNoGravity(false);
        }
        com.skycraft.arsenal.entity.SkyArrow.setArrowInGround(arrow, false);
        arrow.setNoGravity(true);
        arrow.setOwner(p);
        arrow.setDeltaMovement(Vec3.ZERO);
        arrow.syncVelocity = true;
        arrow.fallDistance = 0;
        HELD_ARROWS.put(p.getUUID(), arrow.getId());
        SpellSounds.play(p, SpellSounds.Slot.CAST_ARCANE, 0.9f, 1.7f);
        MagicFx.send(p, MagicFx.BEAM, Element.ARCANE, Targeting.handPos(p), arrow.position(), p.getId(), 1);
        return true;
    }

    /** Telekinesis: hold to pull a distant item or intercept an incoming/grounded arrow; let go to launch it. */
    public static Spell.Result telekinesis(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.level();

        // 1. Check if already holding or able to intercept an arrow
        Integer heldArrowId = HELD_ARROWS.get(p.getUUID());
        net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow = heldArrowId == null ? null
                : level.getEntity(heldArrowId) instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow a && a.isAlive() ? a : null;
        if (arrow == null) {
            HELD_ARROWS.remove(p.getUUID());
            if (!HELD.containsKey(p.getUUID())) {
                arrow = aimedOrIncomingArrow(p, reach(p, spell));
                if (arrow != null) {
                    catchArrow(p, arrow);
                }
            }
        }
        if (arrow != null) {
            com.skycraft.arsenal.entity.SkyArrow.setArrowInGround(arrow, false);
            arrow.setNoGravity(true);
            arrow.setOwner(p);
            arrow.fallDistance = 0;
            Vec3 look = p.getViewVector(1f);
            Vec3 hold = p.getEyePosition().add(look.scale(2.0)).add(0, -0.15, 0);
            Vec3 to = hold.subtract(arrow.position());
            arrow.setDeltaMovement(to.scale(0.45));
            double horiz = Math.sqrt(look.x * look.x + look.z * look.z);
            arrow.setYRot((float) (Math.atan2(look.x, look.z) * (180.0 / Math.PI)));
            arrow.setXRot((float) (Math.atan2(look.y, horiz) * (180.0 / Math.PI)));
            arrow.syncVelocity = true;
            if (tick % 2 == 0) MagicFx.send(p, MagicFx.BEAM, Element.ARCANE, Targeting.handPos(p), arrow.position(), p.getId(), 1);
            if (tick % 20 == 0) SpellSounds.loop(p, spell);
            if (!arrow.getUUID().equals(LAST_GRAB.get(p.getUUID()))) {
                LAST_GRAB.put(p.getUUID(), arrow.getUUID());
                return Spell.Result.EFFECT;
            }
            return Spell.Result.CAST;
        }

        // 2. Otherwise pull/hold a distant ItemEntity
        Integer heldId = HELD.get(p.getUUID());
        ItemEntity item = heldId == null ? null : level.getEntity(heldId) instanceof ItemEntity ie && ie.isAlive() ? ie : null;
        if (item == null) {
            HELD.remove(p.getUUID());
            item = aimedItem(p, reach(p, spell));
            if (item == null) {
                if (tick % 4 == 0) {
                    Vec3 hand = Targeting.handPos(p);
                    Vec3 end = p.getEyePosition().add(p.getViewVector(1f).scale(reach(p, spell)));
                    MagicFx.send(p, MagicFx.BEAM, Element.ARCANE, hand, end, p.getId(), 0);
                }
                return Spell.Result.CAST;
            }
            HELD.put(p.getUUID(), item.getId());
            SpellSounds.play(p, SpellSounds.Slot.CAST_ARCANE, 0.8f, 1.6f);
        }
        Vec3 hold = p.getEyePosition().add(p.getViewVector(1f).scale(2.2)).add(0, -0.3, 0);
        Vec3 to = hold.subtract(item.position());
        item.setDeltaMovement(to.scale(0.3));
        item.setNoGravity(true);
        item.syncVelocity = true;
        item.fallDistance = 0;
        if (tick % 2 == 0) MagicFx.send(p, MagicFx.BEAM, Element.ARCANE, Targeting.handPos(p), item.position().add(0, 0.2, 0), p.getId(), 1);
        if (tick % 20 == 0) SpellSounds.loop(p, spell);
        // trains once per item picked up with it, not per second held
        if (!item.getUUID().equals(LAST_GRAB.get(p.getUUID())) && item.distanceToSqr(p) > 9) {
            LAST_GRAB.put(p.getUUID(), item.getUUID());
            return Spell.Result.EFFECT;
        }
        return Spell.Result.CAST;
    }

    @Nullable
    private static net.minecraft.world.entity.projectile.arrow.AbstractArrow aimedOrIncomingArrow(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);

        // Priority 1: In-flight incoming arrows near the caster
        net.minecraft.world.entity.projectile.arrow.AbstractArrow bestIncoming = null;
        double bestIncDist = Double.MAX_VALUE;
        for (net.minecraft.world.entity.projectile.arrow.AbstractArrow a : p.level().getEntitiesOfClass(
                net.minecraft.world.entity.projectile.arrow.AbstractArrow.class, p.getBoundingBox().inflate(5.5),
                a -> a.isAlive() && !com.skycraft.arsenal.entity.SkyArrow.isArrowInGround(a) && a.getOwner() != p)) {
            Vec3 toPlayer = eye.subtract(a.position());
            Vec3 vel = a.getDeltaMovement();
            boolean headingTowardPlayer = vel.lengthSqr() > 0.01 && vel.normalize().dot(toPlayer.normalize()) > 0.25;
            boolean inFrontOfCaster = look.dot(a.position().subtract(eye).normalize()) > 0.20;
            if (headingTowardPlayer || inFrontOfCaster) {
                double d = eye.distanceToSqr(a.position());
                if (d < bestIncDist) {
                    bestIncDist = d;
                    bestIncoming = a;
                }
            }
        }
        if (bestIncoming != null) return bestIncoming;

        // Priority 2: Grounded or water-floating collectible arrow along the look ray
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 limit = block.getType() == HitResult.Type.MISS ? end : block.getLocation().add(look.scale(0.5));
        net.minecraft.world.entity.projectile.arrow.AbstractArrow bestAimed = null;
        double bestAimDist = Double.MAX_VALUE;
        for (net.minecraft.world.entity.projectile.arrow.AbstractArrow a : p.level().getEntitiesOfClass(
                net.minecraft.world.entity.projectile.arrow.AbstractArrow.class, new AABB(eye, limit).inflate(1.2),
                a -> com.skycraft.arsenal.ArsenalEvents.isCollectibleArrow(p, a))) {
            Optional<Vec3> hit = a.getBoundingBox().inflate(0.55).clip(eye, limit);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestAimDist) {
                    bestAimDist = d;
                    bestAimed = a;
                }
            }
        }
        return bestAimed;
    }

    @Nullable
    private static ItemEntity aimedItem(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(range));
        BlockHitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 limit = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity e : p.level().getEntitiesOfClass(ItemEntity.class, new AABB(eye, limit).inflate(1.5), ItemEntity::isAlive)) {
            Optional<Vec3> hit = e.getBoundingBox().inflate(0.5).clip(eye, limit);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
        }
        return best;
    }

    /** Lets go of a held arrow or item: launched forward along the caster's aim if still floating nearby. */
    public static void release(ServerPlayer p) {
        Integer arrowId = HELD_ARROWS.remove(p.getUUID());
        if (arrowId != null && p.level().getEntity(arrowId) instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow && arrow.isAlive()) {
            com.skycraft.arsenal.entity.SkyArrow.setArrowInGround(arrow, false);
            arrow.setNoGravity(false);
            arrow.setOwner(p);
            if (arrow.distanceToSqr(p) < 36) {
                Vec3 look = p.getViewVector(1f);
                arrow.shoot(look.x, look.y, look.z, 3.2F, 0.2F);
                arrow.setCritArrow(true);
                arrow.setBaseDamage(Math.max(com.skycraft.platform.EntityData.arrowDamage(arrow), 2.5));
                arrow.syncVelocity = true;
                SpellSounds.play(p, SpellSounds.Slot.CAST_ARCANE, 0.9f, 1.1f);
                p.level().playSound(null, p.blockPosition(), SoundEvents.ARROW_SHOOT, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.2f);
                Vitals.markInCombat(p);
            }
        }

        Integer id = HELD.remove(p.getUUID());
        if (id == null) return;
        if (!(p.level().getEntity(id) instanceof ItemEntity item) || !item.isAlive()) return;
        item.setNoGravity(false);
        if (item.distanceToSqr(p) < 25) {
            item.setDeltaMovement(p.getViewVector(1f).scale(1.3));
            item.syncVelocity = true;
            item.getPersistentData().store(THROWN_BY, net.minecraft.core.UUIDUtil.CODEC, p.getUUID());
            item.getPersistentData().putLong(THROWN_UNTIL, p.level().getGameTime() + 30);
            item.setPickUpDelay(20);
            THROWN.add(item);
            SpellSounds.play(p, SpellSounds.Slot.CAST_ARCANE, 0.6f, 0.8f);
        }
    }

    /** A thrown item hurts the first creature it hits (heavier stacks hurt more). */
    private static void tickThrown(net.minecraft.server.MinecraftServer server) {
        if (THROWN.isEmpty()) return;
        THROWN.removeIf(item -> {
            if (!item.isAlive() || !(item.level() instanceof ServerLevel level)) return true;
            CompoundTag pd = item.getPersistentData();
            if (pd.getLongOr(THROWN_UNTIL, 0L) < level.getGameTime() || !pd.read(THROWN_BY, net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
                pd.remove(THROWN_BY);
                return true;
            }
            ServerPlayer thrower = server.getPlayerList().getPlayer(pd.read(THROWN_BY, net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            if (thrower == null) return true;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, item.getBoundingBox().inflate(0.4),
                    e -> e != thrower && Targeting.canHarm(thrower, e))) {
                float dmg = 1f + Math.min(4f, item.getItem().getCount() / 8f + (item.getItem().getMaxStackSize() == 1 ? 2f : 0f));
                com.skycraft.platform.Effects.hurt(e, MagicDamage.source(level, MagicDamage.SPELL, item, thrower), dmg);
                item.setDeltaMovement(item.getDeltaMovement().scale(-0.2));
                pd.remove(THROWN_BY);
                Vitals.markInCombat(thrower);
                return true;
            }
            return false;
        });
    }

    // ================================================================== detection, soul trap

    /** Detect Life (Mysticism): every living thing nearby, the undead included, glows through walls. */
    public static Spell.Result senseLife(ServerPlayer p, Spell spell, int tick) {
        int found = 0;
        int ticks = duration(p, spell, false);
        com.skycraft.magic.ActiveMagic.note(p, spell, ticks);
        for (LivingEntity e : Targeting.around(p, p.position(), reach(p, spell), e -> e != p && !(e instanceof ArmorStand))) {
            e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.GLOWING), ticks, 0, false, false));
            found++;
        }
        MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 4);
        MagicFx.sendNear(p.level(), p.position(), 48, MagicFx.RING, Element.ARCANE, p.position().add(0, 0.3, 0), p.position(), p.getId(), 60);
        SpellSounds.play(p, SpellSounds.Slot.CAST_ARCANE, 1f, 0.7f);
        return found > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    // ================================================================== movement

    /** Slowfall: you fall gently for 30 seconds. */
    public static Spell.Result slowfall(ServerPlayer p, Spell spell, int tick) {
        boolean useful = !p.onGround() && p.fallDistance > 2 || !p.hasEffect(com.skycraft.platform.Effects.holder(MobEffects.SLOW_FALLING)) && SpellEffects.hostilesNear(p, 16);
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOW_FALLING), duration(p, spell, true), 0, false, true, true));
        p.fallDistance = 0;
        MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 1);
        SpellSounds.play(p, SpellSounds.Slot.LEVITATE, 0.4f, 1.6f);
        return useful ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Water Walking: you walk on water as if it were solid ground. */
    public static Spell.Result waterWalking(ServerPlayer p, Spell spell, int tick) {
        boolean wasActive = p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.WATER_WALKING.get()));
        boolean nearWater = p.isInWater() || p.level().getBlockState(p.blockPosition().below()).getFluidState().isSource()
                || p.level().getBlockState(p.blockPosition().below(2)).getFluidState().isSource();
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.WATER_WALKING.get()), duration(p, spell, true), 0, false, true, true));
        MagicFx.send(p, MagicFx.AURA, Element.FROST, p.position(), p.position(), p.getId(), 1);
        MagicFx.sound(p, SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, 1f, 1.5f);
        return nearWater && !wasActive ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Levitate: limited flight for 30 seconds (slow, and never far above the ground). */
    public static Spell.Result levitate(ServerPlayer p, Spell spell, int tick) {
        return startLevitation(p, duration(p, spell, true)) ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Starts (or extends) levitation; returns true if the player wasn't already levitating. */
    static boolean startLevitation(ServerPlayer p, int ticks) {
        if (p.isCreative() || p.isSpectator()) return false;
        boolean wasActive = p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.LEVITATE.get()));
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.LEVITATE.get()), ticks, 0, false, true, true));
        Abilities ab = p.getAbilities();
        ab.mayfly = true;
        ab.flying = true;
        ab.setFlyingSpeed(Perks.has(p, "mysticism.featherweight") ? 0.035f : 0.025f);
        p.onUpdateAbilities();
        p.getPersistentData().putBoolean(LEVITATING, true);
        p.fallDistance = 0;
        MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 3);
        SpellSounds.play(p, SpellSounds.Slot.LEVITATE, 0.6f, 1.3f);
        return !wasActive;
    }

    /** Highest a levitating player may rise above the ground below it. */
    public static int levitateCeiling(LivingEntity p) {
        return p instanceof net.minecraft.world.entity.player.Player pl && Perks.has(pl, "mysticism.featherweight") ? 20 : 12;
    }

    private static void endLevitation(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.getBooleanOr(LEVITATING, false)) return;
        pd.remove(LEVITATING);
        if (p.isCreative() || p.isSpectator()) return;
        Abilities ab = p.getAbilities();
        ab.mayfly = false;
        ab.flying = false;
        ab.setFlyingSpeed(0.05f);
        p.onUpdateAbilities();
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOW_FALLING), 100, 0, false, false, true));
        p.removeEffect(com.skycraft.platform.Effects.holder(MagicRegistry.LEVITATE.get()));
    }

    // ================================================================== dispel

    private static boolean isHarmful(MobEffectInstance e) {
        return e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
    }

    /** Removes harmful magic from {@code e}; returns how many effects were removed. */
    static int cleanse(LivingEntity e) {
        List<net.minecraft.core.Holder<MobEffect>> remove = new ArrayList<>();
        for (MobEffectInstance inst : e.getActiveEffects()) {
            if (isHarmful(inst) && inst.getEffect().value() != MagicRegistry.ASH_SHELL.get()) remove.add(inst.getEffect());
        }
        remove.forEach(e::removeEffect);
        int n = remove.size();
        if (e.isOnFire()) {
            e.clearFire();
            n++;
        }
        return n;
    }

    /** Strips beneficial magic from an enemy; returns how many effects were removed. */
    private static int strip(LivingEntity e) {
        List<net.minecraft.core.Holder<MobEffect>> remove = new ArrayList<>();
        for (MobEffectInstance inst : e.getActiveEffects()) {
            if (inst.getEffect().value().getCategory() == MobEffectCategory.BENEFICIAL) remove.add(inst.getEffect());
        }
        remove.forEach(e::removeEffect);
        return remove.size();
    }

    /** Dispel: removes harmful magic from you (dual cast: and from your allies nearby). */
    public static Spell.Result dispel(ServerPlayer p, Spell spell, int tick) {
        int removed = cleanse(p);
        if (spell.dual) {
            for (LivingEntity e : Targeting.around(p, p.position(), spell.radius, e -> e != p && Targeting.isFriendly(p, e))) removed += cleanse(e);
        }
        if (Perks.has(p, "mysticism.dispeller")) {
            for (LivingEntity e : Targeting.around(p, p.position(), spell.radius, e -> Targeting.canHarm(p, e))) {
                if (e instanceof Mob mob && Summons.isSummon(mob)) {
                    Summons.dismiss(mob);
                    removed++;
                } else {
                    removed += strip(e);
                }
            }
        }
        MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 2);
        MagicFx.sendNear(p.level(), p.position(), 48, MagicFx.RING, Element.ARCANE, p.position().add(0, 0.3, 0), p.position(), p.getId(),
                Math.round(spell.radius * 10));
        SpellSounds.play(p, SpellSounds.Slot.DISPEL, 1f, 1.4f);
        return removed > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Mass Dispel: allies around you are cleansed; enemies lose their magic and enemy summons are banished. */
    public static Spell.Result massDispel(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.level();
        int n = cleanse(p);
        for (LivingEntity e : Targeting.around(p, p.position(), spell.radius, e -> e != p)) {
            if (Targeting.isFriendly(p, e)) {
                n += cleanse(e);
            } else if (Targeting.canHarm(p, e)) {
                if (e instanceof Mob mob && Summons.isSummon(mob)) {
                    Summons.dismiss(mob);
                    n++;
                } else {
                    n += strip(e);
                    if (e.removeEffect(com.skycraft.platform.Effects.holder(MagicRegistry.FLAME_CLOAK.get()))) n++;
                    if (e.removeEffect(com.skycraft.platform.Effects.holder(MagicRegistry.FROST_CLOAK.get()))) n++;
                    if (e.removeEffect(com.skycraft.platform.Effects.holder(MagicRegistry.LIGHTNING_CLOAK.get()))) n++;
                    if (e.removeEffect(com.skycraft.platform.Effects.holder(MagicRegistry.STENDARRS_AURA.get()))) n++;
                }
            }
        }
        MagicFx.sendNear(level, p.position(), 96, MagicFx.RING, Element.ARCANE, p.position().add(0, 0.2, 0), p.position(), p.getId(),
                Math.round(spell.radius * 10));
        MagicFx.burst(level, p.position().add(0, 1, 0), Element.ARCANE, 5f);
        SpellSounds.play(p, SpellSounds.Slot.DISPEL, 2f, 0.7f);
        return n > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    // ================================================================== absorb

    private static float absorbMult(ServerPlayer p) {
        return 1f + 0.25f * Perks.rank(p, "mysticism.thaumaturge");
    }

    private static LivingEntity absorbBeam(ServerPlayer p, Spell spell, int tick) {
        Targeting.Ray ray = Targeting.ray(p, spell.radius, e -> Targeting.canHarm(p, e));
        Vec3 hand = Targeting.handPos(p);
        // the stream flows from the victim into the caster's hand
        if (tick % 2 == 0) MagicFx.send(p, MagicFx.BEAM, spell.element, ray.end(), hand, p.getId(), ray.entity() != null ? 1 : 0);
        if (tick % 12 == 0) SpellSounds.play(p, SpellSounds.Slot.ABSORB, 0.5f, 0.8f + p.getRandom().nextFloat() * 0.2f);
        return tick % 5 == 0 ? ray.entity() : null;
    }

    /** Absorb Health: drains 8 health per second from the target into you. */
    public static Spell.Result absorbHealth(ServerPlayer p, Spell spell, int tick) {
        LivingEntity t = absorbBeam(p, spell, tick);
        if (t == null) return Spell.Result.CAST;
        float amount = spell.magnitude / 4f * absorbMult(p);
        float before = t.getHealth();
        if (!Targeting.hurtNoKnockback(t, MagicDamage.source(p.level(), MagicDamage.SPELL, null, p), amount)) return Spell.Result.CAST;
        Vitals.markInCombat(p);
        p.heal(Math.max(0, before - Math.max(0, t.getHealth())));
        return Spell.Result.EFFECT;
    }

    /** Absorb Stamina: drains stamina (creatures are slowed and weakened) into you. */
    public static Spell.Result absorbStamina(ServerPlayer p, Spell spell, int tick) {
        LivingEntity t = absorbBeam(p, spell, tick);
        if (t == null) return Spell.Result.CAST;
        float amount = spell.magnitude / 4f * absorbMult(p) * CombatHandler.SKYRIM_SCALE;
        if (t instanceof ServerPlayer tp) {
            PlayerData td = SkyData.get(tp);
            amount = Math.min(amount, td.getStamina());
            td.setStamina(td.getStamina() - amount);
        } else {
            t.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 30, 0));
            t.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.WEAKNESS), 30, 0));
        }
        if (amount <= 0) return Spell.Result.CAST;
        PlayerData data = SkyData.get(p);
        data.setStamina(Math.min(data.maxStamina(), data.getStamina() + amount));
        Vitals.markInCombat(p);
        return Spell.Result.EFFECT;
    }

    /** Absorb Magicka: drains magicka (from creatures: their magical essence, as a little damage) into you. */
    public static Spell.Result absorbMagicka(ServerPlayer p, Spell spell, int tick) {
        LivingEntity t = absorbBeam(p, spell, tick);
        if (t == null) return Spell.Result.CAST;
        float amount = spell.magnitude / 4f * absorbMult(p) * CombatHandler.SKYRIM_SCALE;
        if (t instanceof ServerPlayer tp) {
            PlayerData td = SkyData.get(tp);
            amount = Math.min(amount, td.getMagicka());
            td.setMagicka(td.getMagicka() - amount);
        } else if (!Targeting.hurtNoKnockback(t, MagicDamage.source(p.level(), MagicDamage.SPELL, null, p), spell.magnitude / 8f)) {
            return Spell.Result.CAST;
        }
        if (amount <= 0) return Spell.Result.CAST;
        PlayerData data = SkyData.get(p);
        data.setMagicka(Math.min(data.maxMagicka(), data.getMagicka() + amount));
        Vitals.markInCombat(p);
        return Spell.Result.EFFECT;
    }

    // ================================================================== reflect & absorption

    /** Chance (percent) of a reflect or absorb spell: base magnitude, x2.2 dual cast, +15 with Mirror Mage, max 80. */
    private static int chance(ServerPlayer p, float magnitude) {
        float c = magnitude + (Perks.has(p, "mysticism.mirror_mage") ? 15 : 0);
        return Math.max(5, Math.min(80, Math.round(c)));
    }

    /** Effect amplifier storing a percent chance: amplifier + 1 = chance / 5. */
    private static int amp(int percent) {
        return Math.max(0, percent / 5 - 1);
    }

    /** Percent chance stored in a reflect/absorb effect instance. */
    public static int chanceOf(MobEffectInstance inst) {
        return (inst.getAmplifier() + 1) * 5;
    }

    public static Spell.Result reflect(ServerPlayer p, Spell spell, int tick) {
        boolean wasActive = p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.REFLECT_SPELL.get()));
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.REFLECT_SPELL.get()), duration(p, spell, false), amp(chance(p, spell.magnitude)), false, true, true));
        MagicFx.send(p, MagicFx.WARD, Element.ARCANE, p.getEyePosition(), p.getViewVector(1f), p.getId(), 1);
        SpellSounds.play(p, SpellSounds.Slot.REFLECT, 0.8f, 1.4f);
        return SpellEffects.buffResult(p, wasActive);
    }

    public static Spell.Result absorption(ServerPlayer p, Spell spell, int tick) {
        boolean wasActive = p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.SPELL_ABSORPTION.get()));
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.SPELL_ABSORPTION.get()), duration(p, spell, false), amp(chance(p, spell.magnitude)), false, true, true));
        MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 2);
        SpellSounds.play(p, SpellSounds.Slot.ABSORB, 0.8f, 1.2f);
        return SpellEffects.buffResult(p, wasActive);
    }

    /** Spell Turning: a strong reflect and half as strong an absorption at once. */
    public static Spell.Result spellTurning(ServerPlayer p, Spell spell, int tick) {
        boolean wasActive = p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.REFLECT_SPELL.get()));
        int d = duration(p, spell, false);
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.REFLECT_SPELL.get()), d, amp(chance(p, spell.magnitude)), false, true, true));
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.SPELL_ABSORPTION.get()), d, amp(chance(p, spell.magnitude / 2)), false, true, true));
        MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 4);
        MagicFx.send(p, MagicFx.WARD, Element.ARCANE, p.getEyePosition(), p.getViewVector(1f), p.getId(), 1);
        SpellSounds.play(p, SpellSounds.Slot.REFLECT, 1f, 1.0f);
        return SpellEffects.buffResult(p, wasActive);
    }

    // ================================================================== Telekinetic Hurl

    /** Telekinetic Hurl: the target (and anything right next to it) is flung away and takes 50 damage. */
    public static void hurlHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.ARCANE, spell.radius);
        SpellSounds.world(level, pos, SpellSounds.Slot.IMPACT_ARCANE, 1.4f, 0.6f);
        MagicFx.worldSound(level, pos, SoundEvents.PLAYER_ATTACK_KNOCKBACK, net.minecraft.sounds.SoundSource.PLAYERS, 1.2f, 0.6f);
        int n = 0;
        for (LivingEntity e : Targeting.around(level, pos, spell.radius, e -> Targeting.canHarm(caster, e))) {
            float dmg = e == target ? spell.magnitude : spell.magnitude / 2f;
            Vec3 dir = e.position().subtract(caster.position()).multiply(1, 0, 1);
            dir = dir.lengthSqr() < 1e-4 ? caster.getViewVector(1f) : dir.normalize();
            if (Targeting.hurtNoKnockback(e, MagicDamage.source(level, MagicDamage.SPELL, proj, caster), dmg)) {
                double force = e.getType().builtInRegistryHolder().is(CombatHandler.BOSSES) ? 0.3 : 1.4;
                e.setDeltaMovement(dir.x * force, 0.55 + force * 0.2, dir.z * force);
                e.syncVelocity = true;
                CombatHandler.stagger(caster, e, 30);
                n++;
            }
        }
        if (n > 0) {
            Vitals.markInCombat(caster);
            SpellEffects.xp(caster, spell);
        }
    }

    // ================================================================== Mark, Recall, Intervention

    public static Spell.Result mark(ServerPlayer p, Spell spell, int tick) {
        CompoundTag m = new CompoundTag();
        m.putString("dim", p.level().dimension().identifier().toString());
        m.putDouble("x", p.getX());
        m.putDouble("y", p.getY());
        m.putDouble("z", p.getZ());
        m.putFloat("yaw", p.getYRot());
        MagicData.tag(p).put("mark", m);
        SkyData.get(p).markDirty();
        MagicFx.sendNear(p.level(), p.position(), 48, MagicFx.RUNE, Element.ARCANE, p.position().add(0, 0.05, 0), p.position(), -1, 12);
        SpellSounds.play(p, SpellSounds.Slot.CAST_ARCANE, 1f, 0.6f);
        Notifier.message(p, Component.translatable("message.skycraft.mark_set"));
        // Mark trains Mysticism only through Recall (no free XP for re-marking the same spot).
        return Spell.Result.CAST;
    }

    public static Spell.Result recall(ServerPlayer p, Spell spell, int tick) {
        CompoundTag m = MagicData.tag(p).getCompoundOrEmpty("mark");
        if (m.isEmpty()) {
            Notifier.message(p, Component.translatable("message.skycraft.no_mark"));
            return Spell.Result.FAILED;
        }
        Identifier dimId = Identifier.tryParse(m.getStringOr("dim", ""));
        ServerLevel target = dimId == null ? null : p.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (target == null) {
            Notifier.message(p, Component.translatable("message.skycraft.no_mark"));
            return Spell.Result.FAILED;
        }
        if (target != p.level() && !Perks.has(p, "mysticism.anchor")) {
            Notifier.message(p, Component.translatable("message.skycraft.mark_other_world"));
            return Spell.Result.FAILED;
        }
        Vec3 dest = new Vec3(m.getDoubleOr("x", 0d), m.getDoubleOr("y", 0d), m.getDoubleOr("z", 0d));
        boolean far = target != p.level() || dest.distanceToSqr(p.position()) > 64 * 64;
        teleport(p, target, dest, m.getFloatOr("yaw", 0f));
        return far ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Divine Intervention: to the nearest shrine of the Nine (a capital or city outside Morrowind). */
    public static Spell.Result divineIntervention(ServerPlayer p, Spell spell, int tick) {
        Place place = nearestTemple(p, false);
        return intervene(p, place);
    }

    /** Almsivi Intervention: to the nearest Tribunal temple (a Morrowind settlement, Solstheim included). */
    public static Spell.Result almsiviIntervention(ServerPlayer p, Spell spell, int tick) {
        Place place = nearestTemple(p, true);
        return intervene(p, place);
    }

    @Nullable
    private static Place nearestTemple(ServerPlayer p, boolean tribunal) {
        int x = p.getBlockX(), z = p.getBlockZ();
        if (!tribunal) {
            // The Imperial cult keeps a temple in every capital and city: try the contract lookups first.
            Optional<Place> capital = Places.nearest(PlaceType.CAPITAL, x, z, 1 << 20);
            Optional<Place> city = Places.nearest(PlaceType.CITY, x, z, 1 << 20);
            Place best = null;
            for (Optional<Place> o : List.of(capital, city)) {
                if (o.isEmpty() || o.get().regionId().startsWith("morrowind/")) continue;
                if (best == null || o.get().distSqr(x, z) < best.distSqr(x, z)) best = o.get();
            }
            if (best != null) return best;
        }
        Place best = null;
        long bestD = Long.MAX_VALUE;
        for (Place pl : Places.all()) {
            boolean temple = pl.type() == PlaceType.CAPITAL || pl.type() == PlaceType.CITY || tribunal && pl.type() == PlaceType.TOWN;
            if (!temple || pl.regionId().startsWith("morrowind/") != tribunal) continue;
            long d = pl.distSqr(x, z);
            if (d < bestD) {
                bestD = d;
                best = pl;
            }
        }
        return best;
    }

    private static Spell.Result intervene(ServerPlayer p, @Nullable Place place) {
        ServerLevel overworld = p.level().getServer().getLevel(Level.OVERWORLD);
        if (place == null || overworld == null) {
            Notifier.message(p, Component.translatable("message.skycraft.intervention_failed"));
            return Spell.Result.FAILED;
        }
        overworld.getChunk(place.x() >> 4, place.z() >> 4);
        int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, place.x(), place.z());
        BlockPos at = new BlockPos(place.x(), y, place.z());
        for (int i = 0; i < 8 && !(overworld.getBlockState(at).isAir() && overworld.getBlockState(at.above()).isAir()); i++) at = at.above();
        boolean far = p.level() != overworld || place.distSqr(p.getBlockX(), p.getBlockZ()) > 128L * 128L;
        teleport(p, overworld, Vec3.atBottomCenterOf(at), p.getYRot());
        Notifier.message(p, Component.translatable("message.skycraft.intervention", place.name()));
        return far ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    private static void teleport(ServerPlayer p, ServerLevel level, Vec3 dest, float yaw) {
        ServerLevel from = p.level();
        MagicFx.sendNear(from, p.position(), 48, MagicFx.SUMMON, Element.ARCANE, p.position(), p.position(), p.getId(), 20);
        SpellSounds.world(from, p.position(), SpellSounds.Slot.TELEPORT, 1f, 0.8f);
        p.stopRiding();
        SpellCasting.stop(p);
        p.teleportTo(level, dest.x, dest.y, dest.z, java.util.Set.of(), yaw, p.getXRot(), false);
        p.fallDistance = 0;
        MagicFx.sendNear(level, dest, 48, MagicFx.SUMMON, Element.ARCANE, dest, dest, p.getId(), 20);
        SpellSounds.world(level, dest, SpellSounds.Slot.TELEPORT, 1f, 1.1f);
    }

    // ================================================================== ticking & cleanup

    /** Every tick for every player: telekinesis release, levitation upkeep, water walking. */
    public static void tick(ServerPlayer p) {
        if ((HELD.containsKey(p.getUUID()) || HELD_ARROWS.containsKey(p.getUUID())) && !SpellCasting.isCasting(p, "telekinesis")) {
            release(p);
        }
        CompoundTag pd = p.getPersistentData();
        if (pd.getBooleanOr(LEVITATING, false)) {
            if (!p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.LEVITATE.get())) || !p.isAlive()) {
                endLevitation(p);
            } else {
                p.fallDistance = 0;
                if (p.tickCount % 10 == 0 && heightAboveGround(p) > levitateCeiling(p) + 2) {
                    p.setDeltaMovement(p.getDeltaMovement().x, -0.3, p.getDeltaMovement().z);
                    p.syncVelocity = true;
                }
                if (p.tickCount % 6 == 0) {
                    p.level().sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, p.getX(), p.getY() + 0.1, p.getZ(), 1, 0.25, 0.05, 0.25, 0.0);
                }
            }
        }
        if (p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.WATER_WALKING.get())) && !p.isShiftKeyDown()) {
            BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.1, p.getZ());
            if (p.level().getFluidState(below).isSource() || p.isInWater()) p.fallDistance = 0;
        }
    }

    /** Blocks between the player's feet and the first solid block below (capped at 40). */
    public static int heightAboveGround(LivingEntity p) {
        BlockPos pos = p.blockPosition();
        for (int i = 0; i < 40; i++) {
            BlockPos b = pos.below(i + 1);
            if (!p.level().getBlockState(b).getCollisionShape(p.level(), b).isEmpty() || !p.level().getFluidState(b).isEmpty()) return i;
        }
        return 40;
    }

    /** Server tick: thrown telekinesis items. */
    public static void serverTick(net.minecraft.server.MinecraftServer server) {
        tickThrown(server);
    }

    /** Logout, death or dimension change. */
    public static void forget(ServerPlayer p) {
        release(p);
        LAST_GRAB.remove(p.getUUID());
        endLevitation(p);
    }

    /** On login: a levitation that ran out while away is ended. */
    public static void onLogin(ServerPlayer p) {
        if (p.getPersistentData().getBooleanOr(LEVITATING, false) && !p.hasEffect(com.skycraft.platform.Effects.holder(MagicRegistry.LEVITATE.get()))) endLevitation(p);
    }

    public static void clear() {
        HELD.clear();
        HELD_ARROWS.clear();
        LAST_GRAB.clear();
        THROWN.clear();
    }
}
