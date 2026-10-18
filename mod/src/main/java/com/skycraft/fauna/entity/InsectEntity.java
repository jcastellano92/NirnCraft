package com.skycraft.fauna.entity;

import com.skycraft.core.Skill;
import com.skycraft.crafting.arcane.block.IngredientPlantBlock;
import com.skycraft.fauna.FaunaItems;
import com.skycraft.skills.Progression;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * A catchable insect (Bat-style ambient flier): butterflies and dragonflies by day, torchbugs and luna moths by night.
 * Flutters about its home spot (a flower patch, a pond) a few blocks above the ground. Right-click it with an empty
 * hand to catch it: the player gets its alchemy ingredient and a little Alchemy XP. Spawned near players by
 * {@link com.skycraft.fauna.InsectSpawner}; vanishes when no player is near, or when its time of day is over.
 */
public class InsectEntity extends AmbientCreature {
    public enum Kind {
        BUTTERFLY(true, 0.10, 3),
        DRAGONFLY(true, 0.24, 2),
        TORCHBUG(false, 0.06, 4),
        MOTH(false, 0.09, 3);

        /** Active by day (true) or by night (false). */
        public final boolean diurnal;
        final double speed;
        final int heightRange;

        Kind(boolean diurnal, double speed, int heightRange) {
            this.diurnal = diurnal;
            this.speed = speed;
            this.heightRange = heightRange;
        }

        /** Resolves the alchemy ingredient directly on Kind without generating a synthetic switch-map inner class. */
        public Item ingredient(int variant) {
            if (this == BUTTERFLY) {
                return variant == BLUE ? FaunaItems.BLUE_BUTTERFLY_WING.get() : FaunaItems.MONARCH_WING.get();
            }
            if (this == DRAGONFLY) {
                return FaunaItems.BLUE_DARTWING.get();
            }
            if (this == TORCHBUG) {
                return FaunaItems.TORCHBUG_THORAX.get();
            }
            return FaunaItems.LUNA_MOTH_WING.get();
        }
    }

    /** Butterfly variants. */
    public static final int MONARCH = 0;
    public static final int BLUE = 1;

    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(InsectEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> PERCHED = SynchedEntityData.defineId(InsectEntity.class, EntityDataSerializers.BOOLEAN);
    private static final Vector3f TORCHBUG_GLOW = new Vector3f(1.0f, 0.9f, 0.35f);

    private final Kind kind;
    @Nullable
    private BlockPos target;
    @Nullable
    private BlockPos flowerTarget;
    @Nullable
    private BlockPos home;
    private int hover;
    private int perchTicks;
    private int perchCooldown;

    public InsectEntity(EntityType<? extends InsectEntity> type, Level level, Kind kind) {
        super(type, level);
        this.kind = kind;
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 1.0).add(Attributes.MOVEMENT_SPEED, 0.1);
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(VARIANT, 0);
        builder.define(PERCHED, false);
    }

    public int getVariant() {
        return this.entityData.get(VARIANT);
    }

    public void setVariant(int variant) {
        this.entityData.set(VARIANT, variant);
    }

    public boolean isPerched() {
        return this.entityData.get(PERCHED);
    }

    public void setPerched(boolean perched) {
        this.entityData.set(PERCHED, perched);
    }

    public void setHome(BlockPos home) {
        this.home = home.immutable();
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putInt("Variant", getVariant());
        if (home != null) tag.putLong("Home", home.asLong());
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "Variant", "Home");
        
        setVariant(tag.getIntOr("Variant",0));
        if (tag.contains("Home")) home = BlockPos.of(tag.getLongOr("Home",0L));
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                        @Nullable SpawnGroupData data) {
        if (kind == Kind.BUTTERFLY) setVariant(this.random.nextInt(3) == 0 ? BLUE : MONARCH);
        if (home == null) home = blockPosition();
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    /** The ingredient this insect yields when caught. */
    public Item ingredient() {
        return kind().ingredient(getVariant());
    }

    // ------------------------------------------------------------------ catching

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.isAlive() || this.isRemoved()) {
            return InteractionResult.PASS;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            return super.mobInteract(player, hand);
        }
        if (this.level() instanceof ServerLevel level) {
            this.discard();
            ItemStack loot = new ItemStack(ingredient());
            if (!com.skycraft.vitals.ActionHandler.addToBags(player, loot)) {
                player.drop(loot, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.35f, 1.6f + this.random.nextFloat() * 0.3f);
            level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.15, getZ(), 3, 0.05, 0.05, 0.05, 0.01);
            if (player instanceof ServerPlayer sp) Progression.addSkillXp(sp, Skill.ALCHEMY, 0.5f);
        }
        return com.skycraft.platform.Interactions.sidedSuccess(this.level().isClientSide());
    }

    // ------------------------------------------------------------------ flight

    @Override
    public void tick() {
        super.tick();
        Level level = this.level();
        if (level.isClientSide()) {
            if (kind == Kind.TORCHBUG && this.random.nextInt(8) == 0) {
                level.addParticle(new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, TORCHBUG_GLOW.x(), TORCHBUG_GLOW.y(), TORCHBUG_GLOW.z()), 0.48f), getX(), getY() + 0.05, getZ(), 0, 0, 0);
            }
            return;
        }
        if (this.tickCount % 100 == 0) {
            Player near = level.getNearestPlayer(this, 64.0);
            if (near == null) {
                this.discard();
                return;
            }
            // daytime insects leave at dusk, night insects at dawn (out of sight)
            boolean day = level.isBrightOutside();
            if (day != kind.diurnal && this.random.nextInt(3) == 0 && this.distanceToSqr(near) > 144.0) this.discard();
        }
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        if (home == null) home = blockPosition();
        if (perchCooldown > 0) perchCooldown--;

        // Butterflies and moths: flower-seeking & gentle wing-folding perch for 3-6 seconds
        if (kind == Kind.BUTTERFLY || kind == Kind.MOTH) {
            if (perchTicks > 0) {
                perchTicks--;
                boolean startled = this.level().getNearestPlayer(this, 1.8) != null;
                boolean flowerGone = flowerTarget == null || !isFlowerOrFlora(this.level().getBlockState(flowerTarget));
                if (startled || flowerGone || perchTicks <= 0) {
                    perchTicks = 0;
                    setPerched(false);
                    perchCooldown = 70 + this.random.nextInt(70);
                    flowerTarget = null;
                    target = pickTarget();
                    setDeltaMovement((this.random.nextDouble() - 0.5) * 0.08, 0.07 + this.random.nextDouble() * 0.04, (this.random.nextDouble() - 0.5) * 0.08);
                } else {
                    setPerched(true);
                    double px = flowerTarget.getX() + 0.5 - getX();
                    double py = flowerTarget.getY() + 0.35 - getY();
                    double pz = flowerTarget.getZ() + 0.5 - getZ();
                    setDeltaMovement(px * 0.25, py * 0.25, pz * 0.25);
                    return;
                }
            } else if (isPerched()) {
                setPerched(false);
            }

            if (flowerTarget != null) {
                if (!isFlowerOrFlora(this.level().getBlockState(flowerTarget))) {
                    flowerTarget = null;
                } else {
                    double fx = flowerTarget.getX() + 0.5 - getX();
                    double fy = flowerTarget.getY() + 0.45 - getY();
                    double fz = flowerTarget.getZ() + 0.5 - getZ();
                    double fDistSqr = fx * fx + fy * fy + fz * fz;
                    if (fDistSqr < 0.45 * 0.45) {
                        perchTicks = 60 + this.random.nextInt(61); // 3-6 seconds
                        setPerched(true);
                        setDeltaMovement(Vec3.ZERO);
                        return;
                    }
                    target = flowerTarget;
                }
            } else if (perchCooldown == 0 && this.random.nextInt(25) == 0) {
                BlockPos found = findNearbyFlower(10);
                if (found != null) {
                    flowerTarget = found;
                    target = found;
                    home = found;
                }
            }
        }

        if (target != null && target != flowerTarget && (!this.level().isEmptyBlock(target) || target.getY() <= this.level().getMinY())) {
            target = null;
        }
        if (hover > 0) {
            // dragonflies hang motionless in the air over water/reeds, then dart swiftly off
            hover--;
            Vec3 cur = getDeltaMovement();
            setDeltaMovement(cur.x * 0.35, Math.sin(this.tickCount * 0.65) * 0.008, cur.z * 0.35);
            return;
        }
        int retarget = kind == Kind.DRAGONFLY ? 24 : (kind == Kind.TORCHBUG ? 80 : 65);
        if (target == null || (target != flowerTarget && (this.random.nextInt(retarget) == 0 || target.closerToCenterThan(position(), 0.9)))) {
            if (target != null && kind == Kind.DRAGONFLY && this.random.nextInt(2) == 0) {
                hover = 18 + this.random.nextInt(35);
            }
            target = flowerTarget != null ? flowerTarget : pickTarget();
        }
        if (target == null) return;
        double tyOff = target == flowerTarget ? 0.45 : 0.5;
        double dx = target.getX() + 0.5 - getX();
        double dy = target.getY() + tyOff - getY();
        double dz = target.getZ() + 0.5 - getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0e-3) return;
        double s = kind.speed;
        Vec3 v = getDeltaMovement();
        Vec3 nv;
        if (kind == Kind.DRAGONFLY) {
            // Swift darting bursts with sharp agile turns
            double accel = 0.28;
            nv = v.add((dx / dist * s - v.x) * accel, (dy / dist * s * 0.7 - v.y) * accel, (dz / dist * s - v.z) * accel);
        } else if (kind == Kind.TORCHBUG) {
            // Warm bobbing lantern drift at dusk/night
            double bob = Mth.sin(this.tickCount * 0.18f) * 0.025 + Mth.cos(this.tickCount * 0.07f) * 0.012;
            double swayX = Mth.cos(this.tickCount * 0.11f) * 0.012;
            double swayZ = Mth.sin(this.tickCount * 0.11f) * 0.012;
            nv = v.add((dx / dist * s + swayX - v.x) * 0.12, (dy / dist * s * 0.6 + bob - v.y) * 0.12, (dz / dist * s + swayZ - v.z) * 0.12);
        } else {
            // Butterfly & moth dancing flutter
            double flutter = Mth.sin(this.tickCount * 0.7f) * 0.05 + Mth.cos(this.tickCount * 0.23f) * 0.02;
            double spiralX = -dz / dist * Mth.sin(this.tickCount * 0.25f) * 0.025;
            double spiralZ = dx / dist * Mth.sin(this.tickCount * 0.25f) * 0.025;
            nv = v.add((dx / dist * s + spiralX - v.x) * 0.16, (dy / dist * s * 0.85 + flutter - v.y) * 0.16, (dz / dist * s + spiralZ - v.z) * 0.16);
        }
        setDeltaMovement(nv);
        if (nv.horizontalDistanceSqr() > 1.0e-4) {
            float yaw = (float) (Mth.atan2(nv.z, nv.x) * (180.0 / Math.PI)) - 90.0f;
            setYRot(getYRot() + Mth.wrapDegrees(yaw - getYRot()) * (kind == Kind.DRAGONFLY ? 0.8f : 0.55f));
            this.yBodyRot = getYRot();
            this.yHeadRot = getYRot();
        }
    }

    private static boolean isFlowerOrFlora(BlockState state) {
        return state.is(BlockTags.FLOWERS) || state.getBlock() instanceof IngredientPlantBlock;
    }

    @Nullable
    private BlockPos findNearbyFlower(int radius) {
        BlockPos origin = blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 18; i++) {
            int dx = this.random.nextInt(radius * 2 + 1) - radius;
            int dz = this.random.nextInt(radius * 2 + 1) - radius;
            int x = origin.getX() + dx;
            int z = origin.getZ() + dz;
            int topY = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int dy = -2; dy <= 2; dy++) {
                p.set(x, topY + dy, z);
                BlockState st = this.level().getBlockState(p);
                if (isFlowerOrFlora(st) && this.level().isEmptyBlock(p.above())) {
                    double d = origin.distSqr(p);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    @Nullable
    private BlockPos pickTarget() {
        BlockPos base = home != null && home.distSqr(blockPosition()) < 24 * 24 ? home : blockPosition();
        int r = kind == Kind.DRAGONFLY ? 8 : 6;
        BlockPos fallback = null;
        for (int i = 0; i < 8; i++) {
            int x = base.getX() + this.random.nextInt(2 * r + 1) - r;
            int z = base.getZ() + this.random.nextInt(2 * r + 1) - r;
            int ground = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int y = ground + 1 + this.random.nextInt(kind.heightRange);
            if (Math.abs(y - getY()) > 8) continue;
            BlockPos p = new BlockPos(x, y, z);
            if (!this.level().isEmptyBlock(p)) continue;
            if (kind == Kind.DRAGONFLY) {
                BlockState below = this.level().getBlockState(new BlockPos(x, ground - 1, z));
                BlockState surface = this.level().getBlockState(new BlockPos(x, ground, z));
                if (below.getFluidState().is(FluidTags.WATER) || surface.is(Blocks.SUGAR_CANE) || surface.is(BlockTags.FLOWERS)) {
                    return new BlockPos(x, ground + 1 + this.random.nextInt(2), z);
                }
            }
            if (fallback == null) fallback = p;
        }
        return fallback;
    }

    // ------------------------------------------------------------------ ambient-creature plumbing

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        EntityDimensions dimensions = super.getDefaultDimensions(pose);
        return dimensions.withEyeHeight(dimensions.height() * 0.5f);
    }

    @Override
    protected float getSoundVolume() {
        return 0.2f;
    }

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    @Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        return null;
    }

    @Override
    @Nullable
    protected SoundEvent getDeathSound() {
        return null;
    }
}
