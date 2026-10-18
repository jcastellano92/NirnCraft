package com.skycraft.dungeons.client;

import com.skycraft.dungeons.entity.DwarvenSphere;
import com.skycraft.client.PoseEntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Dwarven sphere: a rolling bronze ball that unfolds into a torso on a wheel, with a blade arm and a crossbow arm.
 * The ball and the warrior are separate parts cross-faded by the unfold progress. Texture 64x64.
 */
public class DwarvenSphereModel extends PoseEntityModel<DwarvenSphere> {
    private final ModelPart root;
    private final ModelPart ball;
    private final ModelPart wheel;
    private final ModelPart torso;
    private final ModelPart head;
    private final ModelPart armRight;
    private final ModelPart armLeft;
    private float open;
    private float roll;

    public DwarvenSphereModel(ModelPart root) {
        super(root);
        this.root = root;
        this.ball = root.getChild("ball");
        this.wheel = root.getChild("wheel");
        this.torso = root.getChild("torso");
        this.head = torso.getChild("head");
        this.armRight = torso.getChild("arm_right");
        this.armLeft = torso.getChild("arm_left");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("ball", CubeListBuilder.create().texOffs(0, 0).addBox(-5.0f, -5.0f, -5.0f, 10, 10, 10),
                PartPose.offset(0.0f, 19.0f, 0.0f));
        root.addOrReplaceChild("wheel", CubeListBuilder.create().texOffs(0, 20).addBox(-4.5f, -4.5f, -4.5f, 9, 9, 9),
                PartPose.offset(0.0f, 19.5f, 0.0f));
        PartDefinition torso = root.addOrReplaceChild("torso", CubeListBuilder.create().texOffs(0, 38).addBox(-4.0f, -9.0f, -2.5f, 8, 9, 5),
                PartPose.offset(0.0f, 15.0f, 0.0f));
        torso.addOrReplaceChild("head", CubeListBuilder.create().texOffs(40, 0).addBox(-2.5f, -5.0f, -2.5f, 5, 5, 5),
                PartPose.offset(0.0f, -9.0f, 0.0f));
        PartDefinition right = torso.addOrReplaceChild("arm_right", CubeListBuilder.create().texOffs(40, 10).addBox(-3.0f, -1.0f, -1.5f, 3, 8, 3),
                PartPose.offset(-4.0f, -8.0f, 0.0f));
        right.addOrReplaceChild("blade", CubeListBuilder.create().texOffs(40, 22).addBox(-0.5f, 0.0f, -1.0f, 1, 10, 2),
                PartPose.offsetAndRotation(-1.5f, 6.5f, 0.0f, -0.4f, 0.0f, 0.0f));
        PartDefinition left = torso.addOrReplaceChild("arm_left", CubeListBuilder.create().texOffs(40, 10).mirror().addBox(0.0f, -1.0f, -1.5f, 3, 8, 3),
                PartPose.offset(4.0f, -8.0f, 0.0f));
        left.addOrReplaceChild("crossbow", CubeListBuilder.create()
                        .texOffs(46, 22).addBox(-0.5f, -0.5f, -6.0f, 1, 1, 6)
                        .texOffs(26, 52).addBox(-3.5f, -0.5f, -5.5f, 7, 1, 2),
                PartPose.offset(1.5f, 7.0f, 0.0f));
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void extractPose(DwarvenSphere entity, com.skycraft.client.PoseRenderState state, float partialTick) {
        this.open = Mth.lerp(partialTick, entity.openAnimO, entity.openAnim);
        this.roll = Mth.lerp(partialTick, entity.rollAngleO, entity.rollAngle);
        super.extractPose(entity,state,partialTick);
    }

    @Override
    public void setupAnim(DwarvenSphere entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        boolean folded = open < 0.35f;
        this.ball.visible = folded;
        this.wheel.visible = !folded;
        this.torso.visible = !folded;
        this.ball.xRot = roll;
        this.wheel.xRot = limbSwing * 0.9f;
        // the torso rises out of the shell as it unfolds
        float rise = Mth.clamp((open - 0.35f) / 0.65f, 0.0f, 1.0f);
        this.torso.y = 15.0f + (1.0f - rise) * 8.0f;
        this.head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
        this.head.xRot = headPitch * Mth.DEG_TO_RAD;
        float slash = this.attackTime > 0 ? Mth.sin(this.attackTime * Mth.PI) : 0.0f;
        this.armRight.xRot = -0.3f - slash * 1.6f + Mth.sin(ageInTicks * 0.08f) * 0.05f;
        this.armRight.zRot = 0.15f + (1.0f - rise) * 1.2f;
        this.armLeft.xRot = -1.45f + headPitch * Mth.DEG_TO_RAD;
        this.armLeft.yRot = netHeadYaw * Mth.DEG_TO_RAD * 0.5f;
        this.armLeft.zRot = -0.1f - (1.0f - rise) * 1.2f;
    }
}
