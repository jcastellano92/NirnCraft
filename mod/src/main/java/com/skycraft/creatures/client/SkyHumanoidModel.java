package com.skycraft.creatures.client;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Player-shaped model (full 64x64 skin layout with hat/jacket/sleeve/pants overlays) for bandits, draugr, guards and
 * giants. Raises the weapon arm when holding an item and draws the bow when an archer is aggressive.
 */
public class SkyHumanoidModel<T extends Mob> extends PlayerModel<T> {
    public SkyHumanoidModel(ModelPart root) {
        super(root, false);
    }

    public static LayerDefinition createLayer() {
        return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
    }

    @Override
    public void prepareMobModel(T entity, float limbSwing, float limbSwingAmount, float partialTick) {
        ArmPose main = ArmPose.EMPTY;
        ArmPose off = ArmPose.EMPTY;
        ItemStack mainStack = entity.getMainHandItem();
        if (mainStack.getItem() instanceof BowItem && entity.isAggressive()) main = ArmPose.BOW_AND_ARROW;
        else if (!mainStack.isEmpty()) main = ArmPose.ITEM;
        if (!entity.getOffhandItem().isEmpty()) off = ArmPose.ITEM;
        if (entity.getMainArm() == HumanoidArm.RIGHT) {
            this.rightArmPose = main;
            this.leftArmPose = off;
        } else {
            this.leftArmPose = main;
            this.rightArmPose = off;
        }
        super.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTick);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        if (this.attackTime > 0.0F) {
            float swing = net.minecraft.util.Mth.sin(this.attackTime * (float) Math.PI);
            ItemStack main = entity.getMainHandItem();
            boolean twoHanded = com.skycraft.combat.WeaponClass.of(main).twoHanded();
            this.rightArm.xRot -= swing * (twoHanded ? 0.85F : 0.45F);
            if (twoHanded) {
                this.leftArm.xRot -= swing * 0.65F;
                this.leftArm.yRot += swing * 0.25F;
            }
            this.body.yRot += swing * 0.20F;
            this.jacket.copyFrom(this.body);
            this.leftSleeve.copyFrom(this.leftArm);
            this.rightSleeve.copyFrom(this.rightArm);
        } else if (entity.isAggressive() && entity.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem) {
            this.leftArm.xRot = -0.85F;
            this.leftArm.yRot = 0.35F;
            this.leftSleeve.copyFrom(this.leftArm);
        }
    }
}

