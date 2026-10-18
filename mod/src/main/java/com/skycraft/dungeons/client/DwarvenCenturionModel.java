package com.skycraft.dungeons.client;

import com.skycraft.dungeons.entity.DwarvenCenturion;
import com.skycraft.client.PoseEntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Dwarven centurion: a towering steam golem — boiler chest, exhaust stacks, a grilled helm, a hammer fist and a
 * blade forearm. Built at 1x and rendered at 1.75x. Texture 128x64.
 */
public class DwarvenCenturionModel extends PoseEntityModel<DwarvenCenturion> {
    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart head;
    private final ModelPart armRight;
    private final ModelPart armLeft;
    private final ModelPart legRight;
    private final ModelPart legLeft;

    public DwarvenCenturionModel(ModelPart root) {
        super(root);
        this.root = root;
        this.torso = root.getChild("torso");
        this.head = torso.getChild("head");
        this.armRight = torso.getChild("arm_right");
        this.armLeft = torso.getChild("arm_left");
        this.legRight = root.getChild("leg_right");
        this.legLeft = root.getChild("leg_left");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("leg_right", CubeListBuilder.create().texOffs(0, 0).addBox(-3.0f, 0.0f, -3.0f, 6, 12, 6),
                PartPose.offset(-4.0f, 12.0f, 0.0f));
        root.addOrReplaceChild("leg_left", CubeListBuilder.create().texOffs(0, 0).mirror().addBox(-3.0f, 0.0f, -3.0f, 6, 12, 6),
                PartPose.offset(4.0f, 12.0f, 0.0f));
        PartDefinition torso = root.addOrReplaceChild("torso", CubeListBuilder.create()
                        .texOffs(0, 18).addBox(-6.0f, -2.0f, -4.0f, 12, 4, 8)
                        .texOffs(0, 30).addBox(-7.0f, -14.0f, -4.0f, 14, 12, 8)
                        .texOffs(44, 34).addBox(-3.0f, -11.0f, -5.5f, 6, 6, 3),
                PartPose.offset(0.0f, 10.0f, 0.0f));
        torso.addOrReplaceChild("stack_right", CubeListBuilder.create().texOffs(104, 0).addBox(-1.0f, -8.0f, -1.0f, 2, 8, 2),
                PartPose.offset(-4.0f, -12.0f, 4.5f));
        torso.addOrReplaceChild("stack_left", CubeListBuilder.create().texOffs(104, 0).addBox(-1.0f, -8.0f, -1.0f, 2, 8, 2),
                PartPose.offset(4.0f, -12.0f, 4.5f));
        torso.addOrReplaceChild("head", CubeListBuilder.create().texOffs(48, 0).addBox(-4.0f, -7.0f, -4.0f, 8, 7, 8),
                PartPose.offset(0.0f, -14.0f, -0.5f));
        PartDefinition right = torso.addOrReplaceChild("arm_right", CubeListBuilder.create()
                        .texOffs(80, 0).addBox(-6.0f, -2.0f, -3.0f, 6, 14, 6)
                        .texOffs(0, 50).addBox(-7.0f, -3.0f, -4.0f, 8, 4, 8),
                PartPose.offset(-7.0f, -12.0f, 0.0f));
        right.addOrReplaceChild("hammer", CubeListBuilder.create().texOffs(48, 15).addBox(-3.0f, 0.0f, -5.0f, 6, 6, 10),
                PartPose.offset(-3.0f, 12.0f, 0.0f));
        PartDefinition left = torso.addOrReplaceChild("arm_left", CubeListBuilder.create()
                        .texOffs(80, 0).mirror().addBox(0.0f, -2.0f, -3.0f, 6, 14, 6)
                        .texOffs(0, 50).mirror().addBox(-1.0f, -3.0f, -4.0f, 8, 4, 8),
                PartPose.offset(7.0f, -12.0f, 0.0f));
        left.addOrReplaceChild("blade", CubeListBuilder.create().texOffs(80, 20).addBox(-0.5f, 0.0f, -2.0f, 1, 10, 4),
                PartPose.offset(3.0f, 12.0f, 0.0f));
        return LayerDefinition.create(mesh, 128, 64);
    }

    @Override
    public void setupAnim(DwarvenCenturion entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
        this.head.xRot = headPitch * Mth.DEG_TO_RAD;
        float walk = Math.min(1.0f, limbSwingAmount);
        float swing = limbSwing * 0.55f;
        this.legRight.xRot = Mth.cos(swing) * 0.9f * walk;
        this.legLeft.xRot = Mth.cos(swing + Mth.PI) * 0.9f * walk;
        this.torso.zRot = Mth.cos(swing) * 0.04f * walk;
        this.torso.y = 10.0f + Mth.abs(Mth.sin(swing)) * 0.6f * walk;
        float smash = this.attackTime > 0 ? Mth.sin(this.attackTime * Mth.PI) : 0.0f;
        this.armRight.xRot = Mth.cos(swing + Mth.PI) * 0.5f * walk - smash * 2.2f;
        this.armLeft.xRot = Mth.cos(swing) * 0.5f * walk;
        if (entity.isSteaming()) {
            // brace and lean into the breath
            this.head.xRot = 0.25f;
            this.armLeft.xRot = -0.6f;
            this.armRight.xRot = -0.6f;
        }
        this.armRight.zRot = 0.05f;
        this.armLeft.zRot = -0.05f;
    }
}
