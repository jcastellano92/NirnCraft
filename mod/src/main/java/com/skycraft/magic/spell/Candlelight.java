package com.skycraft.magic.spell;

import com.skycraft.magic.MagicFx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Candlelight: a floating orb of light that follows the caster. Implemented with invisible {@code minecraft:light}
 * blocks that move with the player; the current light position is kept in the player's persistent data so it is
 * always cleaned up (expiry, death, logout, dimension change, server stop, and after a crash on the next login).
 */
public final class Candlelight {
    private static final String UNTIL = "skycraft_candle_until";
    private static final String POS = "skycraft_candle_pos";
    private static final String DIM = "skycraft_candle_dim";

    private Candlelight() {}

    public static Spell.Result cast(ServerPlayer p, Spell spell, int tick) {
        // Trains Alteration only when it lights up somewhere dark and isn't just a refresh (no free XP spam).
        boolean wasActive = active(p);
        boolean dark = p.serverLevel().getMaxLocalRawBrightness(BlockPos.containing(p.getEyePosition())) < 8;
        p.getPersistentData().putLong(UNTIL, p.level().getGameTime() + SpellMath.alterationDuration(p, spell));
        MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.8f);
        MagicFx.sound(p, SoundEvents.BEACON_POWER_SELECT, 0.3f, 2f);
        update(p);
        return dark && !wasActive ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** A light of {@code ticks} (made spells). Returns true if it lit somewhere dark that wasn't lit by one already. */
    public static boolean light(ServerPlayer p, int ticks) {
        boolean wasActive = active(p);
        boolean dark = p.serverLevel().getMaxLocalRawBrightness(BlockPos.containing(p.getEyePosition())) < 8;
        p.getPersistentData().putLong(UNTIL, p.level().getGameTime() + ticks);
        update(p);
        return dark && !wasActive;
    }

    public static boolean active(ServerPlayer p) {
        return p.getPersistentData().getLong(UNTIL) > p.level().getGameTime();
    }

    /** Called every 2 ticks for every player. */
    public static void tick(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(UNTIL) && !pd.contains(POS)) return;
        update(p);
    }

    private static void update(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        long until = pd.getLong(UNTIL);
        boolean alive = p.isAlive() && !p.isSpectator() && until > p.level().getGameTime();
        if (!alive) {
            remove(p);
            pd.remove(UNTIL);
            return;
        }
        ServerLevel level = p.serverLevel();
        BlockPos old = pd.contains(POS) ? BlockPos.of(pd.getLong(POS)) : null;
        boolean sameDim = pd.getString(DIM).equals(level.dimension().location().toString());
        if (old != null && !sameDim) {
            remove(p);
            old = null;
        }

        BlockPos above = BlockPos.containing(p.getX(), p.getEyeY() + 0.9, p.getZ());
        BlockPos head = BlockPos.containing(p.getX(), p.getEyeY(), p.getZ());
        BlockPos target = null;
        for (BlockPos candidate : new BlockPos[]{above, head}) {
            if (candidate.equals(old) && level.getBlockState(old).is(Blocks.LIGHT) || canHold(level, candidate)) {
                target = candidate;
                break;
            }
        }
        if (target == null && old != null && old.distSqr(head) > 4) {
            remove(p); // nowhere to float (e.g. swimming in flowing water): don't leave the light behind
        } else if (target != null && (!target.equals(old) || !level.getBlockState(target).is(Blocks.LIGHT))) {
            BlockState here = level.getBlockState(target);
            boolean water = here.getFluidState().is(Fluids.WATER) && here.getFluidState().isSource();
            level.setBlock(target, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15).setValue(LightBlock.WATERLOGGED, water), 3);
            if (old != null && !old.equals(target)) clear(level, old);
            pd.putLong(POS, target.asLong());
            pd.putString(DIM, level.dimension().location().toString());
        }
        BlockPos orb = pd.contains(POS) ? BlockPos.of(pd.getLong(POS)) : null;
        if (orb != null && p.tickCount % 4 == 0) {
            level.sendParticles(ParticleTypes.END_ROD, orb.getX() + 0.5, orb.getY() + 0.5, orb.getZ() + 0.5, 1, 0.08, 0.08, 0.08, 0.0);
            if (p.tickCount % 12 == 0) level.sendParticles(ParticleTypes.GLOW, orb.getX() + 0.5, orb.getY() + 0.5, orb.getZ() + 0.5, 1, 0.15, 0.15, 0.15, 0.0);
        }
    }

    private static boolean canHold(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        return state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    private static void clear(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LIGHT)) {
            level.setBlock(pos, state.getValue(LightBlock.WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** Removes the player's light block wherever it is (any dimension). */
    public static void remove(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(POS)) return;
        BlockPos pos = BlockPos.of(pd.getLong(POS));
        MinecraftServer server = p.getServer();
        ServerLevel level = p.serverLevel();
        String dim = pd.getString(DIM);
        if (server != null && !dim.isEmpty() && !dim.equals(level.dimension().location().toString())) {
            ResourceLocation id = ResourceLocation.tryParse(dim);
            ServerLevel other = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (other != null) level = other;
        }
        clear(level, pos);
        pd.remove(POS);
        pd.remove(DIM);
    }

    /** Ends the spell and removes the light. */
    public static void end(ServerPlayer p) {
        remove(p);
        p.getPersistentData().remove(UNTIL);
    }
}
