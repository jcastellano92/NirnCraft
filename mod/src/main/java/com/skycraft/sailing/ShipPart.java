package com.skycraft.sailing;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * One extra hit box along a long hull (bow and stern sections), like the ender dragon's parts: clicking or hitting it
 * acts on the ship, and the ship checks these boxes against the shore so the bow runs aground before the hull does.
 */
public class ShipPart extends PartEntity<ShipEntity> {
    /** Distance of this section from the ship's centre along its keel (blocks, + = bow). */
    public final float offset;
    private final EntityDimensions size;

    public ShipPart(ShipEntity ship, float offset, float width, float height) {
        super(ship);
        this.offset = offset;
        this.size = EntityDimensions.scalable(width, height);
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
        return getParent().isPickable();
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount) {
        return !isInvulnerableToBase(source) && com.skycraft.platform.Effects.hurt(getParent(), source, amount);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, net.minecraft.world.phys.Vec3 hit) {
        return getParent().interact(player, hand, hit);
    }

    @Override
    public boolean is(Entity entity) {
        return this == entity || getParent() == entity;
    }

    @Override
    public ItemStack getPickResult() {
        return getParent().getPickResult();
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
