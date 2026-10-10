package com.skycraft.society.client;

import com.skycraft.society.entity.NpcEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Player-shaped NPC model (full 64x64 skin layout with overlays). Adds poses: bound hands for prisoners, raised
 * hands while casting (mages, priests), and strumming while a bard plays.
 */
public class NpcModel extends PlayerModel<NpcEntity> {
    public NpcModel(ModelPart root) {
        super(root, false);
    }

    public static LayerDefinition createLayer() {
        return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
    }

    @Override
    public void prepareMobModel(NpcEntity entity, float limbSwing, float limbSwingAmount, float partialTick) {
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
    public void setupAnim(NpcEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        // children: a bigger head on the small body (the renderer scales the whole child down)
        float headScale = entity.isChild() ? 1.22F : 1.0F;
        this.head.xScale = this.head.yScale = this.head.zScale = headScale;
        this.hat.xScale = this.hat.yScale = this.hat.zScale = headScale;
        boolean changed = true;
        if (entity.isPrisoner()) {
            // hands bound behind the back, head hung low
            this.rightArm.xRot = 0.55F;
            this.rightArm.yRot = 0.0F;
            this.rightArm.zRot = -0.35F;
            this.leftArm.xRot = 0.55F;
            this.leftArm.yRot = 0.0F;
            this.leftArm.zRot = 0.35F;
            this.head.xRot = Math.max(this.head.xRot, 0.35F);
            this.hat.copyFrom(this.head);
        } else if (entity.isCasting()) {
            float wave = Mth.cos(ageInTicks * 0.6F) * 0.08F;
            this.rightArm.xRot = -1.35F + wave;
            this.rightArm.yRot = -0.15F;
            this.rightArm.zRot = 0.0F;
            this.leftArm.xRot = -1.35F - wave;
            this.leftArm.yRot = 0.15F;
            this.leftArm.zRot = 0.0F;
        } else if (entity.isPlaying()) {
            // holding an invisible lute: left hand on the neck, right hand strumming
            this.leftArm.xRot = -0.95F;
            this.leftArm.yRot = 0.55F;
            this.leftArm.zRot = 0.0F;
            this.rightArm.xRot = -0.55F + Mth.sin(ageInTicks * 0.9F) * 0.12F;
            this.rightArm.yRot = -0.45F;
            this.rightArm.zRot = 0.0F;
        } else {
            changed = false;
        }
        if (changed) {
            this.leftSleeve.copyFrom(this.leftArm);
            this.rightSleeve.copyFrom(this.rightArm);
        }
    }
}
