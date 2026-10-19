package com.nirncraft.dungeons.client;

import com.nirncraft.client.PoseEntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * Dwarven spider: a riveted bronze body with a domed soul-gem housing, a lensed head and six jointed legs.
 * Texture 64x32 (see tools/textures/dungeons.py for the UV map).
 */
public class DwarvenSpiderModel<T extends Entity> extends PoseEntityModel<T> {
    private static final float[] LEG_Z = {-2.5f, 0.0f, 2.5f};
    private final ModelPart root;
    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart[] rightLegs = new ModelPart[3];
    private final ModelPart[] leftLegs = new ModelPart[3];

    public DwarvenSpiderModel(ModelPart root) {
        super(root);
        this.root = root;
        this.body = root.getChild("body");
        this.head = root.getChild("head");
        for (int i = 0; i < 3; i++) {
            rightLegs[i] = root.getChild("leg_right_" + i);
            leftLegs[i] = root.getChild("leg_left_" + i);
        }
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition body = root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(0, 0).addBox(-3.0f, -2.5f, -3.5f, 6, 5, 7),
                PartPose.offset(0.0f, 19.0f, 0.0f));
        body.addOrReplaceChild("dome",
                CubeListBuilder.create().texOffs(0, 12).addBox(-2.0f, -2.0f, -2.0f, 4, 2, 4),
                PartPose.offset(0.0f, -2.5f, 0.5f));
        root.addOrReplaceChild("head",
                CubeListBuilder.create()
                        .texOffs(26, 0).addBox(-2.0f, -1.5f, -3.0f, 4, 3, 3)
                        .texOffs(26, 6).addBox(-1.0f, -0.5f, -3.6f, 2, 1, 1),
                PartPose.offset(0.0f, 19.0f, -3.5f));
        for (int i = 0; i < 3; i++) {
            PartDefinition r = root.addOrReplaceChild("leg_right_" + i,
                    CubeListBuilder.create().texOffs(0, 20).addBox(0.0f, -0.5f, -0.5f, 5, 1, 1),
                    PartPose.offsetAndRotation(3.0f, 19.0f, LEG_Z[i], 0.0f, 0.0f, -0.6f));
            r.addOrReplaceChild("lower", CubeListBuilder.create().texOffs(0, 24).addBox(0.0f, -0.5f, -0.5f, 8, 1, 1),
                    PartPose.offsetAndRotation(5.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.85f));
            PartDefinition l = root.addOrReplaceChild("leg_left_" + i,
                    CubeListBuilder.create().texOffs(0, 20).mirror().addBox(-5.0f, -0.5f, -0.5f, 5, 1, 1),
                    PartPose.offsetAndRotation(-3.0f, 19.0f, LEG_Z[i], 0.0f, 0.0f, 0.6f));
            l.addOrReplaceChild("lower", CubeListBuilder.create().texOffs(0, 24).mirror().addBox(-8.0f, -0.5f, -0.5f, 8, 1, 1),
                    PartPose.offsetAndRotation(-5.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.85f));
        }
        return LayerDefinition.create(mesh, 64, 32);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.head.yRot = netHeadYaw * Mth.DEG_TO_RAD * 0.6f;
        this.head.xRot = headPitch * Mth.DEG_TO_RAD * 0.5f;
        this.body.y = 19.0f + Mth.sin(ageInTicks * 0.25f) * 0.15f;
        float swing = limbSwing * 1.4f;
        float amount = Math.min(1.0f, limbSwingAmount);
        for (int i = 0; i < 3; i++) {
            float phase = i * Mth.PI * 0.66f;
            float a = Mth.cos(swing + phase) * 0.45f * amount;
            float lift = Math.max(0.0f, Mth.sin(swing + phase)) * 0.3f * amount;
            rightLegs[i].yRot = a;
            rightLegs[i].zRot = -0.6f - lift;
            leftLegs[i].yRot = a;
            leftLegs[i].zRot = 0.6f + Math.max(0.0f, -Mth.sin(swing + phase)) * 0.3f * amount;
        }
    }
}
