package com.skycraft.creatures.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;

/**
 * A hittable piece of a {@link DragonEntity} outside its body box: the head (about five blocks in front), the neck and
 * the wings. Never saved or sent on its own; the dragon places its parts every tick on both sides and parts forward
 * the damage they take to it, scaled by where the hit landed.
 */
public class DragonPart extends PartEntity<DragonEntity> {
    public final String name;
    private final EntityDimensions size;
    private final float damageScale;

    public DragonPart(DragonEntity dragon, String name, float width, float height, float damageScale) {
        super(dragon);
        this.name = name;
        this.size = EntityDimensions.scalable(width, height);
        this.damageScale = damageScale;
        this.refreshDimensions();
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263);
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
    
        output263.store(tag);
    }

    @Override
    public boolean isPickable() {
        return getParent().isAlive();
    }

    @Override
    @Nullable
    public ItemStack getPickResult() {
        return getParent().getPickResult();
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel serverLevel263, DamageSource source, float amount) {
        return !this.isInvulnerableToBase(source) && getParent().hurtFromPart(this, source, amount * damageScale);
    }

    @Override
    public boolean is(Entity entity) {
        return this == entity || getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return size;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
