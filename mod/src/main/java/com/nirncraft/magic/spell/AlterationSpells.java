package com.nirncraft.magic.spell;

import com.nirncraft.combat.CombatHandler;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.magic.MagicFx;
import com.nirncraft.magic.MagicRegistry;
import com.nirncraft.magic.Targeting;
import com.nirncraft.registry.ModEffects;
import com.nirncraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Alteration spells added on top of {@link SpellEffects}: Magelight, Detect Dead, Equilibrium, Ash Shell and
 * Dragonhide.
 */
public final class AlterationSpells {
    private static final String MAGELIGHT_POS = "nirncraft_magelight_pos";
    private static final String MAGELIGHT_DIM = "nirncraft_magelight_dim";
    private static final String MAGELIGHT_UNTIL = "nirncraft_magelight_until";

    private AlterationSpells() {}

    // ================================================================== Magelight

    /**
     * Magelight: a ball of light that sticks where it lands and lights the area for 60 seconds. One per caster: a new
     * one replaces the old. The light is a {@code minecraft:light} block remembered in the caster's persistent data,
     * so it is always removed (expiry, logout, server stop, next login after a crash).
     */
    public static void magelightHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        BlockPos at = BlockPos.containing(pos);
        if (!canHold(level, at)) at = at.above();
        if (!canHold(level, at)) {
            MagicFx.burst(level, pos, Element.ARCANE, 0.6f);
            return;
        }
        removeMagelight(caster);
        BlockState here = level.getBlockState(at);
        boolean water = here.getFluidState().is(Fluids.WATER) && here.getFluidState().isSource();
        level.setBlock(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15).setValue(LightBlock.WATERLOGGED, water), 3);
        CompoundTag pd = caster.getPersistentData();
        pd.putLong(MAGELIGHT_POS, at.asLong());
        pd.putString(MAGELIGHT_DIM, level.dimension().identifier().toString());
        int duration = SpellMath.alterationDuration(caster, spell);
        pd.putLong(MAGELIGHT_UNTIL, level.getGameTime() + duration);
        MagicFx.burst(level, Vec3.atCenterOf(at), Element.ARCANE, 0.8f);
        SpellSounds.impact(level, Vec3.atCenterOf(at), Element.ARCANE, 0.8f);
        // XP only when it actually lights a dark place.
        if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at) < 15 && level.getMaxLocalRawBrightness(at) < 8) {
            SpellEffects.xp(caster, spell);
        }
    }

    private static boolean canHold(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    /** Every second: glow particles, and removal when the time is up. */
    public static void tickMagelight(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(MAGELIGHT_POS)) return;
        if (pd.getLongOr(MAGELIGHT_UNTIL, 0L) <= p.level().getGameTime()) {
            removeMagelight(p);
            return;
        }
        if (pd.getStringOr(MAGELIGHT_DIM, "").equals(p.level().dimension().identifier().toString())) {
            BlockPos at = BlockPos.of(pd.getLongOr(MAGELIGHT_POS, 0L));
            p.level().sendParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 2, 0.15, 0.15, 0.15, 0.01);
            p.level().sendParticles(ParticleTypes.GLOW, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0.0);
        }
    }

    /** Removes the caster's magelight wherever it is. */
    public static void removeMagelight(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(MAGELIGHT_POS)) return;
        BlockPos pos = BlockPos.of(pd.getLongOr(MAGELIGHT_POS, 0L));
        ServerLevel level = p.level();
        MinecraftServer server = p.level().getServer();
        String dim = pd.getStringOr(MAGELIGHT_DIM, "");
        if (server != null && !dim.equals(level.dimension().identifier().toString())) {
            Identifier id = Identifier.tryParse(dim);
            ServerLevel other = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (other != null) level = other;
        }
        if (level.isLoaded(pos)) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.LIGHT)) {
                level.setBlock(pos, state.getValue(LightBlock.WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
        }
        pd.remove(MAGELIGHT_POS);
        pd.remove(MAGELIGHT_DIM);
        pd.remove(MAGELIGHT_UNTIL);
    }

    /** Game time the caster's Magelight goes out (0 if none), for the HUD countdown. */
    public static long magelightUntil(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        return pd.contains(MAGELIGHT_POS) ? pd.getLongOr(MAGELIGHT_UNTIL, 0L) : 0L;
    }

    // ================================================================== detection

    /** Detect Dead: undead nearby can be seen through walls. */
    public static Spell.Result detectDead(ServerPlayer p, Spell spell, int tick) {
        int duration = SpellMath.alterationDuration(p, spell);
        com.nirncraft.magic.ActiveMagic.note(p, spell, duration);
        int found = 0;
        for (LivingEntity e : Targeting.around(p, p.position(), spell.radius, e -> e != p && com.nirncraft.platform.Effects.isUndead(e))) {
            e.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.GLOWING), duration, 0, false, false));
            found++;
        }
        MagicFx.send(p, MagicFx.AURA, Element.SOUL, p.position(), p.position(), p.getId(), 4);
        SpellSounds.play(p, SpellSounds.Slot.CAST_SOUL, 1f, 0.6f);
        return found > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    // ================================================================== Equilibrium

    /** Equilibrium: converts 25 health into 25 magicka. Costs no magicka and trains nothing (no free XP loop). */
    public static Spell.Result equilibrium(ServerPlayer p, Spell spell, int tick) {
        PlayerData data = SkyData.get(p);
        if (data.getMagicka() >= data.maxMagicka() - 1) {
            Notifier.message(p, Component.translatable("message.nirncraft.magicka_full"));
            return Spell.Result.FAILED;
        }
        if (p.getHealth() <= spell.magnitude + 1f) {
            Notifier.message(p, Component.translatable("message.nirncraft.too_weak"));
            return Spell.Result.FAILED;
        }
        com.nirncraft.platform.Effects.hurt(p, p.damageSources().magic(), spell.magnitude);
        data.setMagicka(Math.min(data.maxMagicka(), data.getMagicka() + spell.magnitude * CombatHandler.SKYRIM_SCALE));
        MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 1);
        SpellSounds.play(p, SpellSounds.Slot.ABSORB, 1f, 0.6f);
        return Spell.Result.CAST;
    }

    // ================================================================== Ash Shell

    /** Ash Shell: a creature up to a fair level is encased in ash: it can't move, act or be harmed. */
    public static void ashShellHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.NATURE, 1f);
        level.sendParticles(ParticleTypes.ASH, pos.x, pos.y, pos.z, 30, 0.4, 0.6, 0.4, 0.02);
        SpellSounds.impact(level, pos, Element.NATURE, 1f);
        if (target == null || target instanceof ArmorStand || !Targeting.canHarm(caster, target)
                || target.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) return;
        float limit = 60f * (spell.dual ? 1.5f : 1f);
        if (!(target instanceof ServerPlayer) && target.getMaxHealth() > limit) {
            Notifier.message(caster, Component.translatable("message.nirncraft.illusion_too_strong", target.getDisplayName()));
            return;
        }
        int ticks = SpellMath.alterationDuration(caster, spell) * (spell.dual ? 3 : 2) / 2;
        if (target instanceof ServerPlayer) ticks = Math.min(ticks, 60);
        target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MagicRegistry.ASH_SHELL.get()), ticks, 0));
        target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), ticks, 0));
        if (target instanceof Mob mob) mob.setTarget(null);
        target.setDeltaMovement(0, Math.min(0, target.getDeltaMovement().y), 0);
        MagicFx.send(target, MagicFx.AURA, Element.NATURE, target.position(), target.position(), target.getId(), 3);
        Vitals.markInCombat(caster);
        SpellEffects.xp(caster, spell);
    }

    // ================================================================== Dragonhide

    /** Dragonhide: 80% of physical damage is ignored for 30 seconds. */
    public static Spell.Result dragonhide(ServerPlayer p, Spell spell, int tick) {
        boolean wasActive = p.hasEffect(com.nirncraft.platform.Effects.holder(MagicRegistry.DRAGONHIDE.get()));
        p.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MagicRegistry.DRAGONHIDE.get()), SpellMath.alterationDuration(p, spell), 0, false, true, true));
        MagicFx.send(p, MagicFx.AURA, Element.NATURE, p.position(), p.position(), p.getId(), 4);
        MagicFx.sendNear(p.level(), p.position(), 48, MagicFx.RING, Element.NATURE, p.position().add(0, 0.2, 0), p.position(), p.getId(), 25);
        MagicFx.sound(p, SoundEvents.ENDER_DRAGON_GROWL, 0.4f, 1.6f);
        MagicFx.sound(p, SoundEvents.ANVIL_LAND, 0.3f, 0.6f);
        return SpellEffects.buffResult(p, wasActive);
    }
}
