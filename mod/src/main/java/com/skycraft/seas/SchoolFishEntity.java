package com.skycraft.seas;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.fish.AbstractSchoolingFish;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Small schooling fish (vanilla schooling AI): river betties in Skyrim's rivers and lakes, Abecean longfins in the
 * warm seas. They drop the fish item of the same name (the survival module's catch); they are not bucketable.
 */
public class SchoolFishEntity extends AbstractSchoolingFish {
    public SchoolFishEntity(EntityType<? extends SchoolFishEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public int getMaxSchoolSize() {
        return 9;
    }

    @Override
    public ItemStack getBucketItemStack() {
        return ItemStack.EMPTY;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    protected SoundEvent getFlopSound() {
        return SoundEvents.COD_FLOP;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.COD_AMBIENT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.COD_DEATH;
    }

    @Override
    protected SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return SoundEvents.COD_HURT;
    }
}
