package com.skycraft.survival.fishing;

import com.skycraft.core.Skill;
import com.skycraft.inventory.Inv;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.survival.SurvivalPackets;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The bobber of a {@link SkyFishingRodItem} ({@code skycraft:fishing_hook}). It flies and floats like the vanilla
 * hook (same renderer), with physical fish approaching the bobber and a Stardew-style catch bar minigame.
 */
public class SkyFishingHook extends FishingHook {
    private static final EntityDataAccessor<Boolean> BITING = SynchedEntityData.defineId(SkyFishingHook.class, EntityDataSerializers.BOOLEAN);
    private enum State { FLYING, HOOKED, BOBBING }
    private enum Phase { WAIT, NIBBLE, BITE }

    private State state = State.FLYING;
    private Phase phase = Phase.WAIT;
    private SkyFishingRodItem.Tier tier = SkyFishingRodItem.Tier.COMMON;
    private int lure;
    private int enchantLuck;
    private int life;
    private int timer = -1;
    private int nibbles;
    private boolean bait;
    private boolean glowBait;
    private float approachAngle;
    // the current bite (server)
    private long biteStart;
    private int zoneStart, zoneEnd, period, window;
    private int outOfWaterTicks;
    private int biteMisses;
    private FishSpecies species;
    private Mob approachingFish;
    private boolean spawnedDecoy;

    public SkyFishingHook(EntityType<? extends SkyFishingHook> type, Level level) {
        super(type, level);
    }

    public boolean isBiting() {
        return this.entityData.get(BITING);
    }

    public SkyFishingHook(Player player, Level level, SkyFishingRodItem.Tier tier, int lure, int luck) {
        this(FishingRegistry.HOOK.get(), level);
        this.tier = tier;
        this.lure = lure;
        this.enchantLuck = luck;
        this.setOwner(player);
        float pitch = player.getXRot();
        float yaw = player.getYRot();
        float cosYaw = Mth.cos(-yaw * Mth.DEG_TO_RAD - (float) Math.PI);
        float sinYaw = Mth.sin(-yaw * Mth.DEG_TO_RAD - (float) Math.PI);
        float cosPitch = -Mth.cos(-pitch * Mth.DEG_TO_RAD);
        float sinPitch = Mth.sin(-pitch * Mth.DEG_TO_RAD);
        this.snapTo(player.getX() - sinYaw * 0.3, player.getEyeY(), player.getZ() - cosYaw * 0.3, yaw, pitch);
        Vec3 dir = new Vec3(-sinYaw, Mth.clamp(-(sinPitch / cosPitch), -5.0f, 5.0f), -cosYaw);
        double len = dir.length();
        dir = dir.multiply(0.6 / len + this.random.triangle(0.5, 0.0103365), 0.6 / len + this.random.triangle(0.5, 0.0103365),
                0.6 / len + this.random.triangle(0.5, 0.0103365));
        this.setDeltaMovement(dir);
        this.setYRot((float) (Mth.atan2(dir.x, dir.z) * Mth.RAD_TO_DEG));
        this.setXRot((float) (Mth.atan2(dir.y, dir.horizontalDistance()) * Mth.RAD_TO_DEG));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(BITING, false);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BITING.equals(key) && this.entityData.get(BITING)) {
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(v.x, -0.4f * Mth.nextFloat(this.random, 0.6f, 1.0f), v.z);
        }
    }

    // ------------------------------------------------------------------ movement (the vanilla hook's, minus its fish)

    @Override
    public void tick() {
        this.baseTick();
        Player player = this.getPlayerOwner();
        if (player == null) {
            this.discard();
            return;
        }
        if (!this.level().isClientSide() && shouldStop(player)) return;
        if (this.onGround()) {
            if (++life >= 1200) {
                this.discard();
                return;
            }
        } else {
            life = 0;
        }
        BlockPos pos = this.blockPosition();
        FluidState fluid = this.level().getFluidState(pos);
        float height = fluid.is(FluidTags.WATER) ? fluid.getHeight(this.level(), pos) : 0f;
        boolean inWater = height > 0f;
        boolean touchingWater = inWater
                || this.level().getFluidState(pos.below()).is(FluidTags.WATER)
                || this.level().getFluidState(pos.above()).is(FluidTags.WATER);

        if (state == State.FLYING) {
            if (this.getHookedIn() != null) {
                this.setDeltaMovement(Vec3.ZERO);
                state = State.HOOKED;
                return;
            }
            if (inWater || touchingWater) {
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.3, 0.2, 0.3));
                state = State.BOBBING;
                return;
            }
            HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
            this.onHit(hit);
        } else if (state == State.HOOKED) {
            Entity hooked = this.getHookedIn();
            if (hooked == null || hooked.isRemoved() || hooked.level().dimension() != this.level().dimension()) {
                if (!this.level().isClientSide()) this.discard();
                return;
            }
            this.setPos(hooked.getX(), hooked.getY(0.8), hooked.getZ());
            return;
        } else {
            Vec3 v = this.getDeltaMovement();
            double off = this.getY() + v.y - pos.getY() - height;
            if (Math.abs(off) < 0.01) off += Math.signum(off) * 0.1;
            this.setDeltaMovement(v.x * 0.9, v.y - off * this.random.nextFloat() * 0.2, v.z * 0.9);
            if (inWater || touchingWater) {
                outOfWaterTicks = 0;
                if (this.entityData.get(BITING)) {
                    this.setDeltaMovement(this.getDeltaMovement().add(0, -0.1 * this.random.nextFloat() * this.random.nextFloat(), 0));
                }
                if (!this.level().isClientSide()) fish(pos);
            } else if (!this.level().isClientSide() && phase != Phase.WAIT) {
                if (++outOfWaterTicks > 30) {
                    escape(player, (byte) 0);
                }
            }
        }
        if (!fluid.is(FluidTags.WATER)) this.setDeltaMovement(this.getDeltaMovement().add(0, -0.03, 0));
        this.move(MoverType.SELF, this.getDeltaMovement());
        this.updateRotation();
        if (state == State.FLYING && (this.onGround() || this.horizontalCollision)) this.setDeltaMovement(Vec3.ZERO);
        this.setDeltaMovement(this.getDeltaMovement().scale(0.92));
        this.reapplyPosition();
    }

    private boolean shouldStop(Player player) {
        boolean main = player.getMainHandItem().canPerformAction(ItemAbilities.FISHING_ROD_CAST);
        boolean off = player.getOffhandItem().canPerformAction(ItemAbilities.FISHING_ROD_CAST);
        if (!player.isRemoved() && player.isAlive() && (main || off) && this.distanceToSqr(player) <= 1024.0) return false;
        this.discard();
        return true;
    }

    // ------------------------------------------------------------------ the fish (server)

    private void fish(BlockPos pos) {
        ServerLevel level = (ServerLevel) this.level();
        if (!(this.getPlayerOwner() instanceof ServerPlayer player)) return;
        if (timer < 0) startWaiting(level, player, pos, true);
        timer--;
        tickApproachingFish(level, pos);
        switch (phase) {
            case WAIT -> {
                // the fish's wake approaching the bobber
                if (timer < 55 && timer > 0 && this.random.nextFloat() < 0.45f) {
                    double d = timer * 0.08;
                    double x = this.getX() + Mth.sin(approachAngle) * d;
                    double z = this.getZ() + Mth.cos(approachAngle) * d;
                    double y = pos.getY() + 1.0;
                    level.sendParticles(ParticleTypes.BUBBLE, x, y - 0.1, z, 2, 0.05, 0, 0.05, 0);
                    level.sendParticles(ParticleTypes.FISHING, x, y, z, 0, -Mth.sin(approachAngle) * 0.03, 0.01, -Mth.cos(approachAngle) * 0.03, 1.0);
                }
                if (timer <= 0) {
                    if (nibbles > 0) {
                        nibbles--;
                        phase = Phase.NIBBLE;
                        timer = 10;
                        this.setDeltaMovement(this.getDeltaMovement().add(0, -0.12, 0));
                        this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.08f, 1.6f);
                    } else {
                        bite(level, player, pos);
                    }
                }
            }
            case NIBBLE -> {
                if (timer <= 0) {
                    phase = Phase.WAIT;
                    timer = 20 + this.random.nextInt(50);
                    approachAngle = this.random.nextFloat() * Mth.TWO_PI;
                }
            }
            case BITE -> {
                if (timer % 6 == 0) this.setDeltaMovement(this.getDeltaMovement().add(0, -0.08, 0));
                if (timer % 10 == 0) level.sendParticles(ParticleTypes.SPLASH, this.getX(), pos.getY() + 1.0, this.getZ(), 4, 0.1, 0, 0.1, 0.05);
                if (timer <= 0) escape(player, (byte) 3);
            }
        }
    }

    /**
     * Recruits a nearby wild fish entity (or spawns a temporary visual decoy fish in the water) and steers it
     * to circle, nibble, and latch onto the bobber so the angler sees a real fish approach.
     */
    private void tickApproachingFish(ServerLevel level, BlockPos pos) {
        if (approachingFish != null && (!approachingFish.isAlive() || approachingFish.isRemoved()
                || approachingFish.distanceToSqr(this) > 36.0 * 36.0)) {
            releaseApproachingFish(false);
        }
        if (approachingFish == null && (phase != Phase.WAIT || timer <= 85)) {
            List<WaterAnimal> nearby = level.getEntitiesOfClass(WaterAnimal.class, this.getBoundingBox().inflate(14.0, 6.0, 14.0),
                    e -> e instanceof AbstractFish && e.isAlive() && e.isInWater()
                            && !e.getPersistentData().getBooleanOr("skycraft_hook_target",false));
            if (!nearby.isEmpty()) {
                approachingFish = nearby.get(0);
                approachingFish.getPersistentData().putBoolean("skycraft_hook_target", true);
                spawnedDecoy = false;
            } else if (timer <= 70 || phase != Phase.WAIT) {
                double spawnDist = 3.5 + this.random.nextDouble() * 2.0;
                double sx = this.getX() + Mth.sin(approachAngle) * spawnDist;
                double sz = this.getZ() + Mth.cos(approachAngle) * spawnDist;
                double sy = this.getY() - 0.35;
                BlockPos spawnPos = BlockPos.containing(sx, sy, sz);
                if (!level.getFluidState(spawnPos).is(FluidTags.WATER)) {
                    sx = this.getX() + Mth.sin(approachAngle) * 1.2;
                    sz = this.getZ() + Mth.cos(approachAngle) * 1.2;
                    sy = this.getY() - 0.25;
                }
                Mob decoy = (this.random.nextBoolean() ? net.minecraft.world.entity.EntityTypes.SALMON : net.minecraft.world.entity.EntityTypes.COD).create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
                if (decoy != null) {
                    decoy.snapTo(sx, sy, sz, this.random.nextFloat() * 360f, 0f);
                    decoy.setPermanentlyInvulnerable(true);
                    decoy.setPersistenceRequired();
                    decoy.getPersistentData().putBoolean("skycraft_hook_decoy", true);
                    decoy.getPersistentData().putBoolean("skycraft_hook_target", true);
                    if (level.addFreshEntity(decoy)) {
                        approachingFish = decoy;
                        spawnedDecoy = true;
                    }
                }
            }
        }
        if (approachingFish == null) return;

        Vec3 hookPos = new Vec3(this.getX(), this.getY() - 0.2, this.getZ());
        Vec3 targetPos;
        double speed;
        if (phase == Phase.WAIT) {
            double radius = Math.max(0.45, Math.min(4.2, timer * 0.055));
            float orbit = approachAngle + timer * 0.045f;
            targetPos = hookPos.add(Mth.sin(orbit) * radius, -0.1, Mth.cos(orbit) * radius);
            speed = 0.09;
        } else if (phase == Phase.NIBBLE) {
            double dart = timer > 5 ? 0.15 : 0.75;
            targetPos = hookPos.add(Mth.sin(approachAngle) * dart, -0.05, Mth.cos(approachAngle) * dart);
            speed = 0.16;
        } else {
            float thrash = this.tickCount * 0.65f;
            targetPos = hookPos.add(Mth.sin(thrash) * 0.22, -0.05, Mth.cos(thrash) * 0.22);
            speed = 0.18;
        }

        Vec3 toTarget = targetPos.subtract(approachingFish.position());
        double dist = toTarget.length();
        if (dist > 0.02) {
            Vec3 step = toTarget.scale(Math.min(speed, dist * 0.35) / dist);
            approachingFish.setDeltaMovement(approachingFish.getDeltaMovement().scale(0.4).add(step));
        }
        Vec3 toHook = hookPos.subtract(approachingFish.position());
        if (toHook.horizontalDistanceSqr() > 1.0E-4) {
            float yaw = (float) (Mth.atan2(toHook.z, toHook.x) * Mth.RAD_TO_DEG) - 90f;
            approachingFish.setYRot(yaw);
            approachingFish.yBodyRot = yaw;
            approachingFish.yHeadRot = yaw;
        }
    }

    private void releaseApproachingFish(boolean caught) {
        if (approachingFish == null) return;
        approachingFish.getPersistentData().remove("skycraft_hook_target");
        if (spawnedDecoy || caught) {
            if (!approachingFish.isRemoved()) approachingFish.discard();
        } else if (approachingFish.isAlive()) {
            Vec3 away = approachingFish.position().subtract(this.position()).normalize().scale(0.35);
            approachingFish.setDeltaMovement(away.x, -0.05, away.z);
        }
        approachingFish = null;
        spawnedDecoy = false;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide()) {
            releaseApproachingFish(false);
        }
        super.remove(reason);
    }

    private void startWaiting(ServerLevel level, ServerPlayer player, BlockPos pos, boolean first) {
        phase = Phase.WAIT;
        this.entityData.set(BITING, false);
        int skill = Fishing.skill(player);
        findBait(player, false);
        float mul = tier.wait / (float) (double) SurvivalConfig.FISHING_BITE_SPEED.get();
        mul *= 1f - Math.min(0.3f, skill * 0.003f);
        mul *= 1f - Math.min(0.45f, lure * 0.15f);
        if (bait) mul *= 0.75f;
        if (FishingSpots.spotAt(pos.getX(), pos.getZ()) != null) mul *= 0.5f;
        int stock = FishStock.get(level).stock(level, pos);
        mul *= 1f + (FishStock.MAX - stock) * 0.3f;
        if (level.isRainingAt(pos.above())) mul *= 0.8f;
        timer = Math.max(30, Math.round((120 + this.random.nextInt(281)) * mul));
        nibbles = this.random.nextInt(3);
        approachAngle = this.random.nextFloat() * Mth.TWO_PI;
    }

    private void bite(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Fishing.Waters waters = Fishing.waters(level, pos);
        float luck = tier.luck + enchantLuck + Fishing.skill(player) / 25f;
        if (FishingSpots.spotAt(pos.getX(), pos.getZ()) != null) luck += 1;
        findBait(player, true);
        if (glowBait) luck += 2;
        species = Fishing.pick(waters, luck, this.random);
        phase = Phase.BITE;
        int skill = Fishing.skill(player);
        int steadyRank = Perks.rank(player, "fishing.steady_reel");
        float steadyMul = 1.0f + 0.20f * steadyRank;
        int barSize = Mth.clamp(Math.round((240 + skill * 1.4f) * tier.zone * steadyMul) - species.rarity() * 18, 180, 480);
        zoneStart = steadyRank;
        zoneEnd = barSize;
        period = species.rarity() * 100 + Mth.clamp(species.maxCm(), 10, 200);
        window = 400;
        timer = window;
        biteStart = level.getGameTime();
        biteMisses = 0;
        this.entityData.set(BITING, true);
        this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.3f, 1.0f + (this.random.nextFloat() - this.random.nextFloat()) * 0.4f);
        double y = this.getY() + 0.5;
        level.sendParticles(ParticleTypes.BUBBLE, this.getX(), y, this.getZ(), 6, 0.25, 0, 0.25, 0.2);
        level.sendParticles(ParticleTypes.FISHING, this.getX(), y, this.getZ(), 6, 0.25, 0, 0.25, 0.2);
        boolean minigame = SurvivalConfig.FISHING_MINIGAME.get();
        SkyNetwork.sendToPlayer(player, new SurvivalPackets.Bite((byte) 1, (short) (minigame ? zoneStart : 0), (short) (minigame ? zoneEnd : 1000),
                (short) period, (short) window, (byte) 0, "skycraft:" + species.id()));
    }

    /** Completes the Stardew-style catch bar minigame reported by the client. */
    public void completeMinigame(ServerPlayer player, boolean caught) {
        if (this.level().isClientSide() || phase != Phase.BITE || species == null) return;
        InteractionHand hand = player.getMainHandItem().canPerformAction(ItemAbilities.FISHING_ROD_CAST)
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack rod = player.getItemInHand(hand);
        if (caught) {
            int damage = land(player, rod);
            if (this.onGround()) damage = Math.max(damage, 2);
            if (!rod.isEmpty()) rod.hurtAndBreak(damage, player, hand);
            this.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1.0f, 1.0f);
            this.discard();
        } else {
            escape(player, (byte) 0);
            if (!rod.isEmpty()) rod.hurtAndBreak(1, player, hand);
            this.discard();
        }
    }

    /** The fish got away: {@code result} 0 out of the water, 2 too early or off the zone, 3 too late. */
    private void escape(Player player, byte result) {
        releaseApproachingFish(false);
        if (player instanceof ServerPlayer sp && (phase == Phase.BITE || result == 2)) {
            SkyNetwork.sendToPlayer(sp, new SurvivalPackets.Bite((byte) 0, (short) 0, (short) 0, (short) 0, (short) 0, result, ""));
        }
        species = null;
        timer = -1;
        biteMisses = 0;
        outOfWaterTicks = 0;
        phase = Phase.WAIT;
        this.entityData.set(BITING, false);
    }

    /** Bait is used from the offhand first, then the inventory; glow bait is preferred when {@code consume}. */
    private void findBait(ServerPlayer player, boolean consume) {
        bait = false;
        glowBait = false;
        net.minecraft.world.item.Item pick = Inv.has(player, FishingRegistry.GLOW_BAIT.get())
                ? FishingRegistry.GLOW_BAIT.get()
                : Inv.has(player, FishingRegistry.BAIT.get()) ? FishingRegistry.BAIT.get() : null;
        if (pick == null) return;
        bait = true;
        glowBait = pick == FishingRegistry.GLOW_BAIT.get();
        if (consume && !player.isCreative()) Inv.take(player, pick, 1);
    }

    // ------------------------------------------------------------------ reeling in

    @Override
    public int retrieve(ItemStack rod) {
        Player player = this.getPlayerOwner();
        if (this.level().isClientSide() || player == null) return 0;
        int damage = 0;
        Entity hooked = this.getHookedIn();
        if (hooked != null) {
            this.pullEntity(hooked);
            if (player instanceof ServerPlayer sp) CriteriaTriggers.FISHING_ROD_HOOKED.trigger(sp, rod, this, java.util.Collections.emptyList());
            this.level().broadcastEntityEvent(this, (byte) 31);
            damage = hooked instanceof ItemEntity ? 3 : 5;
        } else if (phase == Phase.BITE && species != null && player instanceof ServerPlayer sp) {
            if (!SurvivalConfig.FISHING_MINIGAME.get()) {
                damage = land(sp, rod);
                if (this.onGround()) damage = Math.max(damage, 2);
                this.discard();
                return damage;
            }
            return 0;
        } else if (phase == Phase.NIBBLE) {
            escape(player, (byte) 2);
        }
        releaseApproachingFish(false);
        if (this.onGround()) damage = 2;
        this.discard();
        return damage;
    }

    /** Marker position 0..1000 after {@code ticks}: kept for compatibility. */
    public static float markerAt(float ticks, int period) {
        float phase = (ticks % period + period) % period / period;
        float tri = phase < 0.5f ? phase * 2f : 2f - phase * 2f;
        return tri * 1000f;
    }

    private int land(ServerPlayer player, ItemStack rod) {
        releaseApproachingFish(true);
        ServerLevel level = player.level();
        ItemStack fish = Fishing.catchOf(species, this.random, false);
        List<ItemStack> drops = new ArrayList<>();
        if (!fish.isEmpty()) drops.add(fish);
        ItemFishedEvent event = new ItemFishedEvent(drops, this.onGround() ? 2 : 1, this);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return event.getRodDamage();
        CriteriaTriggers.FISHING_ROD_HOOKED.trigger(player, rod, this, drops);
        for (ItemStack stack : drops) {
            ItemEntity item = new ItemEntity(level, this.getX(), this.getY(), this.getZ(), stack);
            double dx = player.getX() - this.getX();
            double dy = player.getY() - this.getY();
            double dz = player.getZ() - this.getZ();
            item.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
            level.addFreshEntity(item);
        }
        level.addFreshEntity(new ExperienceOrb(level, player.getX(), player.getY() + 0.5, player.getZ() + 0.5, this.random.nextInt(6) + 1));
        player.awardStat(Stats.FISH_CAUGHT, 1);
        FishStock.get(level).take(level, this.blockPosition());
        if (species.rarity() > 0) Progression.addSkillXp(player, Skill.FISHING, 6f * species.rarity());
        SkyNetwork.sendToPlayer(player, new SurvivalPackets.Bite((byte) 0, (short) 0, (short) 0, (short) 0, (short) 0, (byte) 1,
                fish.isEmpty() ? "" : fish.getItem().getDescriptionId()));
        this.entityData.set(BITING, false);
        return event.getRodDamage();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
