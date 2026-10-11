package com.skycraft.survival.fishing;

import com.skycraft.core.Skill;
import com.skycraft.network.SkyNetwork;
import com.skycraft.skills.Progression;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.survival.SurvivalPackets;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.entity.player.ItemFishedEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The bobber of a {@link SkyFishingRodItem} ({@code skycraft:fishing_hook}). It flies and floats like the vanilla
 * hook (same renderer), but the fishing is Skyrim's:
 *
 * <ol>
 *     <li>Wait: a fish takes {@code 6-20 s} to come (faster with a better rod, bait, rain, Fishing skill, at a fishing
 *     spot; slower in fished-out water, see {@link FishStock}). It may nibble once or twice first; reeling in then
 *     scares it away.</li>
 *     <li>Bite: the bobber is pulled under and the HUD shows the reel bar: a marker sweeps across it and you must reel
 *     in (use the rod again) while it is inside the catch zone. The zone is wider with Fishing skill and better rods,
 *     the marker faster for rarer fish. After two sweeps the fish gets away.</li>
 *     <li>Catch: the {@link FishSpecies} that lives in this water ({@link Fishing#pick}) flies out to you. The Forge
 *     {@link ItemFishedEvent} is posted, so the Fishing skill's XP and perks (skills/LifeSkills) apply.</li>
 * </ol>
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
    private FishSpecies species;

    public SkyFishingHook(EntityType<? extends SkyFishingHook> type, Level level) {
        super(type, level);
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
        this.moveTo(player.getX() - sinYaw * 0.3, player.getEyeY(), player.getZ() - cosYaw * 0.3, yaw, pitch);
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
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(BITING, false);
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
        if (!this.level().isClientSide && shouldStop(player)) return;
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

        if (state == State.FLYING) {
            if (this.getHookedIn() != null) {
                this.setDeltaMovement(Vec3.ZERO);
                state = State.HOOKED;
                return;
            }
            if (inWater) {
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.3, 0.2, 0.3));
                state = State.BOBBING;
                return;
            }
            HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
            this.onHit(hit);
        } else if (state == State.HOOKED) {
            Entity hooked = this.getHookedIn();
            if (hooked == null || hooked.isRemoved() || hooked.level().dimension() != this.level().dimension()) {
                if (!this.level().isClientSide) this.discard();
                return;
            }
            this.setPos(hooked.getX(), hooked.getY(0.8), hooked.getZ());
            return;
        } else {
            Vec3 v = this.getDeltaMovement();
            double off = this.getY() + v.y - pos.getY() - height;
            if (Math.abs(off) < 0.01) off += Math.signum(off) * 0.1;
            this.setDeltaMovement(v.x * 0.9, v.y - off * this.random.nextFloat() * 0.2, v.z * 0.9);
            if (inWater) {
                if (this.entityData.get(BITING)) {
                    this.setDeltaMovement(this.getDeltaMovement().add(0, -0.1 * this.random.nextFloat() * this.random.nextFloat(), 0));
                }
                if (!this.level().isClientSide) fish(pos);
            } else if (!this.level().isClientSide && phase != Phase.WAIT) {
                escape(player, (byte) 0);
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
        boolean main = player.getMainHandItem().canPerformAction(ToolActions.FISHING_ROD_CAST);
        boolean off = player.getOffhandItem().canPerformAction(ToolActions.FISHING_ROD_CAST);
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
        switch (phase) {
            case WAIT -> {
                // the fish's wake approaching the bobber
                if (timer < 40 && timer > 0 && this.random.nextFloat() < 0.4f) {
                    double d = timer * 0.1;
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
        int width = Mth.clamp(Math.round((130 + skill * 1.2f) * tier.zone) - species.rarity() * 25, 70, 420);
        zoneStart = 80 + this.random.nextInt(Math.max(1, 840 - width));
        zoneEnd = zoneStart + width;
        period = Mth.clamp(46 - species.rarity() * 8 - (species.maxCm() >= 120 ? 4 : 0), 26, 50);
        window = period * 2 + 6;
        timer = window;
        biteStart = level.getGameTime();
        this.entityData.set(BITING, true);
        this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.3f, 1.0f + (this.random.nextFloat() - this.random.nextFloat()) * 0.4f);
        double y = this.getY() + 0.5;
        level.sendParticles(ParticleTypes.BUBBLE, this.getX(), y, this.getZ(), 6, 0.25, 0, 0.25, 0.2);
        level.sendParticles(ParticleTypes.FISHING, this.getX(), y, this.getZ(), 6, 0.25, 0, 0.25, 0.2);
        boolean minigame = SurvivalConfig.FISHING_MINIGAME.get();
        SkyNetwork.sendToPlayer(player, new SurvivalPackets.Bite((byte) 1, (short) (minigame ? zoneStart : 0), (short) (minigame ? zoneEnd : 1000),
                (short) period, (short) window, (byte) 0, ""));
    }

    /** The fish got away: {@code result} 0 out of the water, 2 too early or off the zone, 3 too late. */
    private void escape(Player player, byte result) {
        if (player instanceof ServerPlayer sp && (phase == Phase.BITE || result == 2)) {
            SkyNetwork.sendToPlayer(sp, new SurvivalPackets.Bite((byte) 0, (short) 0, (short) 0, (short) 0, (short) 0, result, ""));
        }
        species = null;
        timer = -1;
        phase = Phase.WAIT;
        this.entityData.set(BITING, false);
    }

    /** Bait is used from the offhand first, then the inventory; glow bait is preferred when {@code consume}. */
    private void findBait(ServerPlayer player, boolean consume) {
        bait = false;
        glowBait = false;
        ItemStack found = ItemStack.EMPTY;
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(player.getOffhandItem());
        stacks.addAll(player.getInventory().items);
        for (ItemStack s : stacks) {
            if (s.is(FishingRegistry.GLOW_BAIT.get())) {
                found = s;
                glowBait = true;
                break;
            }
            if (found.isEmpty() && s.is(FishingRegistry.BAIT.get())) found = s;
        }
        bait = !found.isEmpty();
        if (consume && bait && !player.isCreative()) found.shrink(1);
    }

    // ------------------------------------------------------------------ reeling in

    @Override
    public int retrieve(ItemStack rod) {
        Player player = this.getPlayerOwner();
        if (this.level().isClientSide || player == null) return 0;
        int damage = 0;
        Entity hooked = this.getHookedIn();
        if (hooked != null) {
            this.pullEntity(hooked);
            if (player instanceof ServerPlayer sp) CriteriaTriggers.FISHING_ROD_HOOKED.trigger(sp, rod, this, java.util.Collections.emptyList());
            this.level().broadcastEntityEvent(this, (byte) 31);
            damage = hooked instanceof ItemEntity ? 3 : 5;
        } else if (phase == Phase.BITE && species != null && player instanceof ServerPlayer sp) {
            if (inZone(sp)) {
                damage = land(sp, rod);
            } else {
                escape(sp, (byte) 2);
            }
        } else if (phase == Phase.NIBBLE) {
            escape(player, (byte) 2);
        }
        if (this.onGround()) damage = 2;
        this.discard();
        return damage;
    }

    /** Where the marker was when the player clicked (their ping taken off), within a little slack. */
    private boolean inZone(ServerPlayer player) {
        if (!SurvivalConfig.FISHING_MINIGAME.get()) return true;
        int lag = Mth.clamp(player.latency / 50, 0, 10);
        long elapsed = this.level().getGameTime() - biteStart - lag;
        float speed = 2000f / period;
        float slack = speed * 1.5f;
        float pos = markerAt(elapsed, period);
        return pos >= zoneStart - slack && pos <= zoneEnd + slack;
    }

    /** Marker position 0..1000 after {@code ticks}: it sweeps right then back left once per {@code period}. */
    public static float markerAt(float ticks, int period) {
        float phase = (ticks % period + period) % period / period;
        float tri = phase < 0.5f ? phase * 2f : 2f - phase * 2f;
        return tri * 1000f;
    }

    private int land(ServerPlayer player, ItemStack rod) {
        ServerLevel level = player.serverLevel();
        ItemStack fish = Fishing.catchOf(species, this.random, false);
        List<ItemStack> drops = new ArrayList<>();
        if (!fish.isEmpty()) drops.add(fish);
        ItemFishedEvent event = new ItemFishedEvent(drops, this.onGround() ? 2 : 1, this);
        MinecraftForge.EVENT_BUS.post(event);
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
                fish.isEmpty() ? "" : fish.getDescriptionId()));
        this.entityData.set(BITING, false);
        return event.getRodDamage();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
