package com.skycraft.arsenal.entity;

import com.skycraft.arsenal.ArsenalEntities;
import com.skycraft.arsenal.ArsenalEvents;
import com.skycraft.arsenal.item.ArrowKind;
import com.skycraft.arsenal.item.SkyArrowItem;
import com.skycraft.magic.MagicScheduler;
import com.skycraft.perk.Perks;
import com.skycraft.registry.ModEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The arrow entity of every Skycraft arrow and bolt. It remembers the exact item it was shot as (synced, so the
 * client can pick the texture and particles), which is what you pick back up - and what is recorded on a mob's body
 * when it hits one.
 */
public class SkyArrow extends AbstractArrow {
    private static final EntityDataAccessor<ItemStack> AMMO = SynchedEntityData.defineId(SkyArrow.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> FROM_CROSSBOW = SynchedEntityData.defineId(SkyArrow.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> EXTINGUISHED = SynchedEntityData.defineId(SkyArrow.class, EntityDataSerializers.BOOLEAN);

    private int flightTicks = 0;
    private int groundedLightTicks = 0;
    @Nullable
    private BlockPos lastLightPos = null;

    public SkyArrow(EntityType<? extends SkyArrow> type, Level level) {
        super(type, level);
    }

    public SkyArrow(Level level, LivingEntity shooter, ItemStack ammo) {
        this(level, shooter, ammo, null);
    }

    public SkyArrow(Level level, LivingEntity shooter, ItemStack ammo, @Nullable ItemStack weapon) {
        super(ArsenalEntities.SKY_ARROW.get(), shooter, level, ammo.copyWithCount(1), weapon);
        setAmmo(ammo);
    }

    public SkyArrow(Level level, double x, double y, double z, ItemStack ammo) {
        super(ArsenalEntities.SKY_ARROW.get(), x, y, z, level, ammo.copyWithCount(1), null);
        setAmmo(ammo);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(AMMO, ItemStack.EMPTY);
        builder.define(FROM_CROSSBOW, false);
        builder.define(EXTINGUISHED, false);
    }

    public void setAmmo(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        this.entityData.set(AMMO, copy);
    }

    public ItemStack ammo() {
        return this.entityData.get(AMMO);
    }

    public void setFromCrossbow(boolean fromCrossbow) {
        this.entityData.set(FROM_CROSSBOW, fromCrossbow);
    }

    public boolean isFromCrossbow() {
        return this.entityData.get(FROM_CROSSBOW) || (getWeaponItem() != null && getWeaponItem().getItem() instanceof net.minecraft.world.item.CrossbowItem);
    }

    public void setExtinguished(boolean extinguished) {
        this.entityData.set(EXTINGUISHED, extinguished);
    }

    public boolean isExtinguished() {
        return this.entityData.get(EXTINGUISHED);
    }

    @Nullable
    public ArrowKind kind() {
        return ammo().getItem() instanceof SkyArrowItem item ? item.kind : null;
    }

    public ArrowKind.Element element() {
        ArrowKind kind = kind();
        return kind == null ? ArrowKind.Element.NONE : kind.element;
    }

    /** Safe accessor for protected AbstractArrow.inGround across packages. */
    public static boolean isArrowInGround(AbstractArrow arrow) {
        if (arrow == null) return false;
        if (arrow instanceof SkyArrow sky) return sky.isInGround();
        return arrow.onGround() || (arrow.getDeltaMovement().lengthSqr() < 1.0e-4 && !arrow.isInWater() && !arrow.isNoGravity());
    }

    /** Safe mutator for protected AbstractArrow.inGround across packages. */
    public static void setArrowInGround(AbstractArrow arrow, boolean value) {
        if (arrow instanceof SkyArrow sky) {
            sky.setInGround(value);
        } else if (arrow != null) {
            arrow.setOnGround(value);
        }
    }

    /** Material-scaled break probability on impact, before Hunter's Discipline. */
    public static float baseBreakChance(@Nullable ArrowKind kind) {
        if (kind == null) return 0.50f;
        return switch (kind) {
            case IRON -> 0.50f;
            case STEEL, FIRE, FROST, SHOCK, STEEL_BOLT -> 0.40f;
            case ORCISH -> 0.35f;
            case DWARVEN, DWARVEN_BOLT -> 0.30f;
            case ELVEN -> 0.25f;
            case GLASS -> 0.20f;
            case EBONY -> 0.15f;
            case DAEDRIC -> 0.10f;
            case DRAGONBONE -> 0.05f;
            case EXPLOSIVE_BOLT -> 1.00f;
        };
    }

    public static float breakChanceFor(@Nullable ArrowKind kind, @Nullable Entity owner) {
        float chance = baseBreakChance(kind);
        if (owner instanceof Player player && Perks.has(player, "archery.hunters_discipline")) {
            chance *= 0.5f;
        }
        return chance;
    }

    /** Effective per-tick gravity pull for bow-shot arrows by material tier (vanilla default is 0.05F). */
    public float effectiveBowGravity() {
        ArrowKind k = kind();
        if (k == null) return 0.050f;
        return switch (k) {
            case IRON -> 0.052f;
            case STEEL, FIRE, FROST, SHOCK, STEEL_BOLT, EXPLOSIVE_BOLT -> 0.048f;
            case ORCISH -> 0.046f;
            case DWARVEN, DWARVEN_BOLT -> 0.045f;
            case ELVEN -> 0.042f;
            case GLASS -> 0.040f;
            case EBONY -> 0.039f;
            case DAEDRIC -> 0.038f;
            case DRAGONBONE -> 0.036f;
        };
    }

    /** The item you get back for this arrow (also recorded on bodies). */
    public ItemStack pickupStack() {
        return getPickupItem();
    }

    @Override
    protected ItemStack getDefaultPickupItem() { return new ItemStack(Items.ARROW); }

    @Override
    protected ItemStack getPickupItem() {
        ItemStack stack = ammo().copy();
        if (stack.isEmpty()) return new ItemStack(Items.ARROW);
        stack.setCount(1);
        return stack;
    }

    /** No walk-over auto-pickup: stuck and floating arrows must be collected interactively. */
    @Override
    public void playerTouch(Player player) {
        // Intentionally empty: players pick up stuck or floating arrows via right-click or [F].
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public float getPickRadius() {
        return 0.35F;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hit) {
        if (ArsenalEvents.tryPickupArrow(player, this)) return com.skycraft.platform.Interactions.sidedSuccess(level().isClientSide());
        return super.interact(player, hand, hit);
    }

    @Override
    public void tick() {
        super.tick();

        ArrowKind.Element elem = element();

        // Extinguish fire arrows immediately upon entering water
        if (elem == ArrowKind.Element.FIRE && !isExtinguished() && isInWater()) {
            setExtinguished(true);
            clearFire();
            if (!level().isClientSide() && level() instanceof ServerLevel sl) {
                clearDynamicLight();
                sl.playSound(null, blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.7f, 1.2f + random.nextFloat() * 0.3f);
                sl.sendParticles(ParticleTypes.CLOUD, getX(), getY(), getZ(), 8, 0.15, 0.15, 0.15, 0.04);
                sl.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY(), getZ(), 5, 0.12, 0.12, 0.12, 0.02);
            }
        }

        // Water buoyancy: once slowed by water drag, wooden-shafted arrows float up to the surface and bob gently
        if (isInWater() && !isInGround()) {
            Vec3 m = getDeltaMovement();
            double horizSq = m.x * m.x + m.z * m.z;
            if (horizSq < 0.04) {
                boolean waterAbove = level().getFluidState(blockPosition().above()).is(FluidTags.WATER);
                double vy;
                if (waterAbove) {
                    vy = Math.min(0.06, Math.max(0.02, m.y + 0.025));
                } else {
                    double surfaceY = blockPosition().getY() + 0.88;
                    double diff = surfaceY - getY();
                    vy = net.minecraft.util.Mth.clamp(diff * 0.12, -0.02, 0.03) + Math.sin(tickCount * 0.18) * 0.004;
                }
                setDeltaMovement(m.x * 0.82, vy, m.z * 0.82);
            }
        } else if (!isInGround() && !isNoGravity() && ! getPersistentData().contains("skycraft_slow_time")) {
            // Bow vs. Crossbow ballistic gravity adjustment (vanilla AbstractArrow subtracts 0.05 each tick)
            flightTicks++;
            float targetGravity;
            if (isFromCrossbow()) {
                targetGravity = flightTicks <= 15 ? 0.026f : 0.042f;
            } else {
                targetGravity = effectiveBowGravity();
            }
            double adjustY = 0.05 - targetGravity;
            double windPushX = 0.0;
            double windPushZ = 0.0;
            if (level().dimension() == Level.OVERWORLD && level().canSeeSky(blockPosition())) {
                float windStrength = 0f;
                float wx = 0f, wz = 0f;
                if (!level().isClientSide() && level() instanceof ServerLevel sl) {
                    com.skycraft.atmosphere.weather.WeatherKind wk = com.skycraft.atmosphere.weather.RegionalWeather.kindAt(sl, blockPosition());
                    int wDeg = com.skycraft.atmosphere.weather.RegionalWeather.windAt(sl, blockPosition());
                    if (wk != null && wDeg >= 0) {
                        windStrength = wk.wind;
                        float rad = wDeg * net.minecraft.util.Mth.DEG_TO_RAD;
                        wx = -net.minecraft.util.Mth.sin(rad);
                        wz = net.minecraft.util.Mth.cos(rad);
                    }
                } else if (level().isClientSide()) {
                    windStrength = com.skycraft.atmosphere.client.ClientWeather.wind();
                    wx = com.skycraft.atmosphere.client.ClientWeather.windX();
                    wz = com.skycraft.atmosphere.client.ClientWeather.windZ();
                }
                // Only deflect slightly in strong wind (>0.45), capped at a subtle 0.0018 blocks/tick^2 at max gale
                if (windStrength > 0.45f) {
                    float excess = net.minecraft.util.Mth.clamp((windStrength - 0.45f) / 0.55f, 0f, 1f);
                    float boltScale = isFromCrossbow() ? 0.65f : 1.0f;
                    double drift = 0.0018 * (excess * excess) * boltScale;
                    windPushX = wx * drift;
                    windPushZ = wz * drift;
                }
            }
            if (Math.abs(adjustY) > 1.0e-4 || windPushX != 0.0 || windPushZ != 0.0) {
                Vec3 m = getDeltaMovement();
                setDeltaMovement(m.x + windPushX, m.y + adjustY, m.z + windPushZ);
            }
        }

        // Server-side dynamic illumination for elemental arrows in flight and briefly after sticking
        if (!level().isClientSide()) {
            int lightLevel = switch (elem) {
                case FIRE -> isExtinguished() ? 0 : 13;
                case SHOCK -> 9;
                case FROST -> 7;
                default -> 0;
            };
            if (lightLevel > 0) {
                if (isInGround()) {
                    groundedLightTicks++;
                    if (groundedLightTicks <= 80) {
                        updateDynamicLight(lightLevel);
                    } else {
                        clearDynamicLight();
                    }
                } else {
                    updateDynamicLight(lightLevel);
                }
            } else {
                clearDynamicLight();
            }
            return;
        }

        // Client-side continuous sub-tick particle trails
        float trailWx = 0f, trailWz = 0f;
        if (level().dimension() == Level.OVERWORLD && level().canSeeSky(blockPosition())) {
            float ws = com.skycraft.atmosphere.client.ClientWeather.wind();
            trailWx = com.skycraft.atmosphere.client.ClientWeather.windX() * ws * 0.035f;
            trailWz = com.skycraft.atmosphere.client.ClientWeather.windZ() * ws * 0.035f;
        }
        if (isInGround()) {
            if (elem == ArrowKind.Element.FIRE && !isExtinguished() && inGroundTime < 80 && random.nextInt(3) == 0) {
                level().addParticle(ParticleTypes.SMALL_FLAME, getX(), getY() + 0.05, getZ(), trailWx * 0.5, 0.015, trailWz * 0.5);
                level().addParticle(ParticleTypes.SMOKE, getX(), getY() + 0.08, getZ(), trailWx, 0.02, trailWz);
            }
            return;
        }
        Vec3 m = getDeltaMovement();
        for (int i = 0; i < 4; i++) {
            double t = i / 4.0;
            double px = getX() - m.x * t;
            double py = getY() - m.y * t;
            double pz = getZ() - m.z * t;
            switch (elem) {
                case FIRE -> {
                    if (!isExtinguished()) {
                        level().addParticle(ParticleTypes.FLAME, px, py, pz, -m.x * 0.04 + trailWx * 0.6, 0.01 - m.y * 0.04, -m.z * 0.04 + trailWz * 0.6);
                        level().addParticle(ParticleTypes.SMALL_FLAME, px + (random.nextDouble() - 0.5) * 0.08,
                                py + (random.nextDouble() - 0.5) * 0.08, pz + (random.nextDouble() - 0.5) * 0.08, trailWx * 0.4, 0.01, trailWz * 0.4);
                        if ((i & 1) == 0) {
                            level().addParticle(ParticleTypes.SMOKE, px, py, pz, -m.x * 0.02 + trailWx, 0.015, -m.z * 0.02 + trailWz);
                        }
                    }
                }
                case FROST -> {
                    level().addParticle(ParticleTypes.SNOWFLAKE, px, py, pz, -m.x * 0.03 + trailWx, -m.y * 0.03, -m.z * 0.03 + trailWz);
                    if ((i & 1) == 0) {
                        level().addParticle(ParticleTypes.WHITE_ASH, px, py, pz, trailWx, -0.01, trailWz);
                    }
                }
                case SHOCK -> level().addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, 0, 0, 0);
                case EXPLOSIVE -> {
                    if ((i & 1) == 0) level().addParticle(ParticleTypes.SMOKE, px, py, pz, trailWx, 0.01, trailWz);
                }
                default -> {
                }
            }
        }
    }

    private void updateDynamicLight(int lightLevel) {
        BlockPos cur = blockPosition();
        if (cur.equals(lastLightPos)) return;
        clearDynamicLight();
        BlockState state = level().getBlockState(cur);
        boolean isAir = state.isAir();
        boolean isWater = state.is(Blocks.WATER) && state.getFluidState().isSource();
        if (isAir || (isWater && element() != ArrowKind.Element.FIRE)) {
            BlockState lightState = Blocks.LIGHT.defaultBlockState()
                    .setValue(LightBlock.LEVEL, lightLevel)
                    .setValue(LightBlock.WATERLOGGED, isWater);
            if (level().setBlock(cur, lightState, 2)) {
                lastLightPos = cur.immutable();
            }
        }
    }

    public void clearDynamicLight() {
        if (lastLightPos != null && !level().isClientSide() && level().isLoaded(lastLightPos)) {
            BlockState state = level().getBlockState(lastLightPos);
            if (state.is(Blocks.LIGHT)) {
                boolean water = state.getValue(LightBlock.WATERLOGGED);
                level().setBlock(lastLightPos, water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
        }
        lastLightPos = null;
    }

    @Override
    public void remove(RemovalReason reason) {
        clearDynamicLight();
        super.remove(reason);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Vec3 preHitVel = getDeltaMovement();
        super.onHitEntity(result);
        // If the hit was canceled or bounced off an invulnerable/blocking target (vanilla reverses velocity by -0.1),
        // discard the arrow cleanly instead of leaving a duplicated/unpickable bounced arrow on the ground.
        if (!level().isClientSide() && !isRemoved() && getDeltaMovement().dot(preHitVel) < 0) {
            clearDynamicLight();
            discard();
        }
    }

    @Override
    protected void doPostHurtEffects(LivingEntity target) {
        super.doPostHurtEffects(target);
        switch (element()) {
            case FIRE -> {
                if (!isExtinguished() && !isInWater()) {
                    target.igniteForSeconds(6);
                    if (level() instanceof ServerLevel sl) {
                        sl.sendParticles(ParticleTypes.FLAME, target.getX(), target.getY() + target.getBbHeight() * 0.5,
                                target.getZ(), 14, 0.25, 0.35, 0.25, 0.06);
                        sl.sendParticles(ParticleTypes.LAVA, target.getX(), target.getY() + target.getBbHeight() * 0.5,
                                target.getZ(), 4, 0.2, 0.2, 0.2, 0.02);
                    }
                }
            }
            case FROST -> {
                target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 100, 2));
                target.setTicksFrozen(Math.max(target.getTicksFrozen(), target.getTicksRequiredToFreeze() + 100));
                if (level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.SNOWFLAKE, target.getX(), target.getY() + target.getBbHeight() * 0.5,
                            target.getZ(), 16, 0.3, 0.35, 0.3, 0.08);
                    sl.sendParticles(ParticleTypes.ITEM_SNOWBALL, target.getX(), target.getY() + target.getBbHeight() * 0.5,
                            target.getZ(), 8, 0.25, 0.25, 0.25, 0.05);
                    sl.playSound(null, target.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.5f, 1.5f);
                }
            }
            case SHOCK -> {
                if (random.nextFloat() < 0.15f && !target.getType().builtInRegistryHolder().is(com.skycraft.combat.CombatHandler.BOSSES)) {
                    target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(ModEffects.STAGGER.get()), 15, 0, false, false));
                }
            }
            case EXPLOSIVE -> explode();
            default -> {
            }
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (level().isClientSide()) return;

        ArrowKind.Element elem = element();
        if (elem == ArrowKind.Element.EXPLOSIVE) {
            explode();
            return;
        }

        if (elem == ArrowKind.Element.FIRE && !isExtinguished() && !isInWater() && level() instanceof ServerLevel sl) {
            sl.playSound(null, result.getBlockPos(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.5f, 1.2f + random.nextFloat() * 0.2f);
            sl.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 12, 0.18, 0.18, 0.18, 0.05);
            sl.sendParticles(ParticleTypes.LAVA, getX(), getY(), getZ(), 3, 0.1, 0.1, 0.1, 0.02);
            BlockPos firePos = result.getBlockPos().relative(result.getDirection());
            if (sl.getBlockState(firePos).isAir() && sl.getFluidState(firePos).isEmpty()
                    && Blocks.FIRE.defaultBlockState().canSurvive(sl, firePos)) {
                sl.setBlock(firePos, Blocks.FIRE.defaultBlockState(), 3);
                int duration = 60 + random.nextInt(41);
                var dim = sl.dimension();
                MagicScheduler.schedule(sl.getServer(), duration, server -> {
                    ServerLevel targetLevel = server.getLevel(dim);
                    if (targetLevel != null && targetLevel.isLoaded(firePos) && targetLevel.getBlockState(firePos).is(Blocks.FIRE)) {
                        targetLevel.removeBlock(firePos, false);
                    }
                });
            }
        } else if (elem == ArrowKind.Element.FROST && level() instanceof ServerLevel sl) {
            sl.playSound(null, result.getBlockPos(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.4f, 1.6f);
            sl.sendParticles(ParticleTypes.SNOWFLAKE, getX(), getY(), getZ(), 14, 0.2, 0.2, 0.2, 0.06);
            sl.sendParticles(ParticleTypes.ITEM_SNOWBALL, getX(), getY(), getZ(), 6, 0.15, 0.15, 0.15, 0.04);
        }

        // Material-scaled break chance on block impact
        float breakChance = breakChanceFor(kind(), getOwner());
        if (random.nextFloat() < breakChance) {
            if (level() instanceof ServerLevel sl) {
                sl.playSound(null, blockPosition(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 0.5f, 1.2f + random.nextFloat() * 0.3f);
                sl.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, net.minecraft.world.item.ItemStackTemplate.fromStack( pickupStack())), getX(), getY(), getZ(), 6, 0.1, 0.1, 0.1, 0.05);
            }
            clearDynamicLight();
            discard();
        }
    }

    private void explode() {
        if (level().isClientSide() || isRemoved()) return;
        clearDynamicLight();
        level().explode(this, getX(), getY(), getZ(), 1.6f, Level.ExplosionInteraction.NONE);
        discard();
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        ItemStack ammo = ammo();
        if (!ammo.isEmpty()) tag.put("ammo", com.skycraft.inventory.ItemData.save(ammo));
        tag.putBoolean("from_crossbow", isFromCrossbow());
        tag.putBoolean("extinguished", isExtinguished());
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "ammo", "from_crossbow", "extinguished");
        
        if (tag.contains("ammo")) setAmmo(com.skycraft.inventory.ItemData.parse(tag.getCompoundOrEmpty("ammo")));
        if (tag.contains("from_crossbow")) setFromCrossbow(tag.getBooleanOr("from_crossbow",false));
        if (tag.contains("extinguished")) setExtinguished(tag.getBooleanOr("extinguished",false));
    }
}

