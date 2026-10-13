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
        // aftermath poses: sitting on the ground uses the seated leg pose, kneeling and tending the crouch
        if (entity.isHuddled()) this.riding = true;
        if (entity.isKneeling() || entity.isTending()) this.crouching = true;
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
        if (entity.isHuddled() || entity.isKneeling() || entity.isTending() || entity.isCarrying() || entity.isWeeping()) {
            aftermathPose(entity, ageInTicks);
        } else if (entity.isPrisoner()) {
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

    /** Huddled, kneeling, tending, working, weeping: the people of a burnt town (see fire.aftermath.Tableau). */
    private void aftermathPose(NpcEntity entity, float age) {
        float breath = Mth.sin(age * 0.09F + entity.getId()) * 0.02F;
        if (entity.isHuddled()) {
            // knees up, arms round them, head down
            this.rightLeg.xRot = -1.5F;
            this.leftLeg.xRot = -1.5F;
            this.rightLeg.yRot = 0.38F;
            this.leftLeg.yRot = -0.38F;
            this.body.xRot = 0.34F + breath;
            this.head.xRot = 0.62F;
            this.rightArm.xRot = -1.2F;
            this.leftArm.xRot = -1.2F;
            this.rightArm.yRot = -0.55F;
            this.leftArm.yRot = 0.55F;
            this.rightArm.zRot = 0.0F;
            this.leftArm.zRot = 0.0F;
        } else if (entity.isKneeling()) {
            this.rightLeg.xRot = 0.1F;
            this.leftLeg.xRot = -0.25F;
            this.body.xRot = 0.28F + breath;
            this.head.xRot = 0.5F;
            this.rightArm.xRot = -0.55F;
            this.leftArm.xRot = -0.55F;
            this.rightArm.yRot = -0.15F;
            this.leftArm.yRot = 0.15F;
        } else if (entity.isTending()) {
            this.body.xRot = 0.85F + breath;
            this.head.xRot = 0.15F;
            this.rightArm.xRot = -1.45F + Mth.sin(age * 0.07F) * 0.08F;
            this.leftArm.xRot = -1.3F;
            this.rightArm.yRot = -0.25F;
            this.leftArm.yRot = 0.25F;
            this.rightLeg.xRot = -0.35F;
            this.leftLeg.xRot = 0.3F;
        } else if (entity.isCarrying()) {
            // arms out in front, holding the load (the walk cycle still moves the legs)
            this.rightArm.xRot = -1.1F;
            this.leftArm.xRot = -1.1F;
            this.rightArm.yRot = -0.28F;
            this.leftArm.yRot = 0.28F;
            this.rightArm.zRot = 0.0F;
            this.leftArm.zRot = 0.0F;
        }
        if (entity.isWeeping()) {
            // hands to the face, shoulders shaking
            float shake = Mth.sin(age * 0.75F + entity.getId()) * 0.03F;
            this.rightArm.xRot = -2.25F + shake;
            this.leftArm.xRot = -2.25F - shake;
            this.rightArm.yRot = -0.45F;
            this.leftArm.yRot = 0.45F;
            this.rightArm.zRot = 0.1F;
            this.leftArm.zRot = -0.1F;
            this.head.xRot = Math.max(this.head.xRot, 0.5F);
            this.body.xRot += shake;
        }
        this.hat.copyFrom(this.head);
        this.jacket.copyFrom(this.body);
        this.leftPants.copyFrom(this.leftLeg);
        this.rightPants.copyFrom(this.rightLeg);
    }
}
