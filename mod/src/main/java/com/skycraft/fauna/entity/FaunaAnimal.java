package com.skycraft.fauna.entity;

import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import com.skycraft.creatures.entity.anim.CreatureAnims;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;

/**
 * Base class of Skyrim wildlife. Wild beasts are {@link Animal}s (so the core's Hunting skill and Beast Lore apply)
 * but can't be fed or bred, and never spawn as babies. Each carries a synced variant number and sex; the loot tables
 * read them through the NBT keys {@code Variant} and {@code Male}.
 */
public abstract class FaunaAnimal extends Animal implements AnimatedCreature {
    protected static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(FaunaAnimal.class, EntityDataSerializers.INT);
    protected static final EntityDataAccessor<Boolean> MALE = SynchedEntityData.defineId(FaunaAnimal.class, EntityDataSerializers.BOOLEAN);
    /** Bedded down for the night (drives the sleep animation on the client). */
    protected static final EntityDataAccessor<Boolean> RESTING = SynchedEntityData.defineId(FaunaAnimal.class, EntityDataSerializers.BOOLEAN);
    private boolean restGoalAdded;

    protected FaunaAnimal(EntityType<? extends Animal> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(VARIANT, 0);
        builder.define(MALE, false);
        builder.define(RESTING, false);
    }

    public boolean isResting() {
        return this.entityData.get(RESTING);
    }

    void setResting(boolean resting) {
        this.entityData.set(RESTING, resting);
    }

    /** True for day animals that bed down at night; night hunters and water dwellers override it. */
    protected boolean sleepsAtNight() {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && !restGoalAdded) {
            restGoalAdded = true;
            this.goalSelector.addGoal(0, new RestAtNightGoal(this));
        }
    }

    /** A bedded-down animal makes no sound. */
    @Override
    public void playAmbientSound() {
        if (!isResting()) super.playAmbientSound();
    }

    @Override
    public boolean animSleeping() {
        return isResting();
    }

    public int getVariant() {
        return this.entityData.get(VARIANT);
    }

    public void setVariant(int variant) {
        this.entityData.set(VARIANT, variant);
    }

    public boolean isMale() {
        return this.entityData.get(MALE);
    }

    public void setMale(boolean male) {
        this.entityData.set(MALE, male);
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putInt("Variant", getVariant());
        tag.putBoolean("Male", isMale());
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "Variant", "Male");
        
        setVariant(tag.getIntOr("Variant",0));
        setMale(tag.getBooleanOr("Male",false));
    }

    /** Picks the variant for a freshly spawned animal (e.g. snowy sabre cat in cold biomes). */
    protected int chooseVariant(ServerLevelAccessor level, BlockPos pos) {
        return 0;
    }

    /** True in cold, snowy places. */
    protected static boolean isCold(ServerLevelAccessor level, BlockPos pos) {
        return level.getBiome(pos).value().getBaseTemperature() < 0.15f;
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                        @Nullable SpawnGroupData data) {
        setMale(this.random.nextBoolean());
        setVariant(chooseVariant(level, blockPosition()));
        if (data == null) data = new AgeableMob.AgeableMobGroupData(false); // wild beasts never spawn as babies
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    /** Predators and territorial beasts leave players alone on Peaceful, in creative/spectator, and in starter/town calm zones unless provoked. */
    protected boolean canAttackPlayers(LivingEntity target) {
        if (this.level().getDifficulty() == Difficulty.PEACEFUL) return false;
        if (target instanceof Player p) {
            if (p.isCreative() || p.isSpectator()) return false;
            if (this.getLastHurtByMob() != p && (com.skycraft.creatures.spawn.SpawnPacing.inStarterSafeCorridor(this.level(), p.getX(), p.getZ())
                    || com.skycraft.creatures.spawn.SpawnPacing.calm(this.level(), p.blockPosition()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Override
    @Nullable
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    // ------------------------------------------------------------------ animation (rig models)

    private final CreatureAnims anims = new CreatureAnims();

    @Override
    public CreatureAnims anims() {
        return anims;
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (!anims.onEvent(this, id)) super.handleEntityEvent(id);
    }

    /** Every melee swing plays an attack animation (alternating blows). */
    @Override
    public boolean swing(net.minecraft.world.InteractionHand hand, net.minecraft.world.item.component.SwingAnimation animation263, boolean updateSelf263) {
        boolean swung263 = super.swing(hand, animation263, updateSelf263);
        if (!this.level().isClientSide()) CreatureAnims.play(this, this.getRandom().nextInt(3) == 0 ? AnimSlot.ATTACK2 : AnimSlot.ATTACK);
        return swung263;
    }

    @Override
    public int animVariant() {
        return getVariant();
    }
}
