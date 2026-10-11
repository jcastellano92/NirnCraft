package com.skycraft.creatures.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:guar}: Morrowind's sturdy riding and pack lizard. Pack guar (variant 1) wear a saddle and bags and
 * can be ridden by anyone; a wild guar takes a saddle. Ridden guar are steered like horses; they never despawn
 * once saddled or ridden.
 */
public class GuarEntity extends SkyBeast {
    public GuarEntity(EntityType<? extends GuarEntity> type, Level level) {
        super(type, level);
    }

    public boolean isSaddled() {
        return getVariant() == 1;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!isSaddled() && held.is(Items.SADDLE)) {
            if (!this.level().isClientSide) {
                setVariant(1);
                this.setPersistenceRequired();
                this.removeTag(SkyCreature.REGION_SPAWN_TAG);
                if (!player.getAbilities().instabuild) held.shrink(1);
                this.playSound(net.minecraft.sounds.SoundEvents.HORSE_SADDLE, 1.0f, 0.8f);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (isSaddled() && !this.isVehicle() && !player.isSecondaryUseActive() && this.getTarget() == null) {
            if (!this.level().isClientSide) {
                player.setYRot(this.getYRot());
                player.startRiding(this);
                this.setPersistenceRequired();
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    @Override
    @Nullable
    public LivingEntity getControllingPassenger() {
        if (isSaddled() && this.getFirstPassenger() instanceof Player p) return p;
        return super.getControllingPassenger();
    }

    @Override
    protected void tickRidden(Player player, Vec3 input) {
        super.tickRidden(player, input);
        this.setRot(player.getYRot(), player.getXRot() * 0.5f);
        this.yRotO = this.yBodyRot = this.yHeadRot = this.getYRot();
        this.setTarget(null);
    }

    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 input) {
        float strafe = player.xxa * 0.5f;
        float forward = player.zza;
        if (forward <= 0f) forward *= 0.25f;
        return new Vec3(strafe, 0.0, forward);
    }

    @Override
    protected float getRiddenSpeed(Player player) {
        return (float) this.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1.1f;
    }

    @Override
    public double getPassengersRidingOffset() {
        return this.getBbHeight() * 0.78;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        super.positionRider(passenger, move);
        if (passenger instanceof LivingEntity living) living.yBodyRot = this.yBodyRot;
    }
}
