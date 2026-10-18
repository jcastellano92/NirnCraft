package com.skycraft.fauna.client;

import com.skycraft.client.PoseEntityModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/** Tiny insect models (32x32 textures): flat zero-thickness wings that flap. Rendered scaled down. */
public final class InsectModels {
    private InsectModels() {}

    /** Butterfly / luna moth: a slim body with antennae and two broad wings. */
    public static class Butterfly<T extends Entity> extends PoseEntityModel<T> {
        private final ModelPart root;
        private final ModelPart body;
        private final ModelPart wingLeft;
        private final ModelPart wingRight;

        public Butterfly(ModelPart root) {
            super(root);
            this.root = root;
            this.body = root.getChild("body");
            this.wingLeft = root.getChild("wing_left");
            this.wingRight = root.getChild("wing_right");
        }

        public static LayerDefinition createLayer() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            root.addOrReplaceChild("body", CubeListBuilder.create()
                            .texOffs(0, 0).addBox(-0.5f, -0.5f, -2.0f, 1, 1, 4)
                            .texOffs(10, 0).addBox(-1.5f, -2.0f, -2.0f, 3, 2, 0),   // antennae
                    PartPose.offset(0.0f, 22.0f, 0.0f));
            root.addOrReplaceChild("wing_left", CubeListBuilder.create().texOffs(0, 6).addBox(0.0f, 0.0f, -4.0f, 7, 0, 8),
                    PartPose.offset(0.5f, 22.0f, 0.0f));
            root.addOrReplaceChild("wing_right", CubeListBuilder.create().texOffs(0, 6).mirror().addBox(-7.0f, 0.0f, -4.0f, 7, 0, 8),
                    PartPose.offset(-0.5f, 22.0f, 0.0f));
            return LayerDefinition.create(mesh, 32, 32);
        }

        @Override
        public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            if (entity instanceof com.skycraft.fauna.entity.InsectEntity insect && insect.isPerched()) {
                float fold = 1.18f + Mth.sin(ageInTicks * 0.12f) * 0.08f;
                this.wingLeft.zRot = -fold;
                this.wingRight.zRot = fold;
                this.body.y = 22.4f;
            } else {
                float flap = 0.35f + Mth.sin(ageInTicks * 1.3f) * 0.9f;
                this.wingLeft.zRot = -flap;
                this.wingRight.zRot = flap;
                this.body.y = 22.0f + Mth.sin(ageInTicks * 1.3f) * 0.3f;
            }
        }
    }

    /** Dragonfly: long thin abdomen, big-eyed head, four narrow translucent wings beating fast. */
    public static class Dragonfly<T extends Entity> extends PoseEntityModel<T> {
        private final ModelPart root;
        private final ModelPart frontLeft;
        private final ModelPart frontRight;
        private final ModelPart rearLeft;
        private final ModelPart rearRight;

        public Dragonfly(ModelPart root) {
            super(root, RenderTypes::entityTranslucent);
            this.root = root;
            this.frontLeft = root.getChild("front_left");
            this.frontRight = root.getChild("front_right");
            this.rearLeft = root.getChild("rear_left");
            this.rearRight = root.getChild("rear_right");
        }

        public static LayerDefinition createLayer() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            root.addOrReplaceChild("body", CubeListBuilder.create()
                            .texOffs(0, 0).addBox(-0.5f, -0.5f, -2.0f, 1, 1, 9)
                            .texOffs(20, 0).addBox(-1.0f, -1.0f, -4.0f, 2, 2, 2),
                    PartPose.offset(0.0f, 22.0f, 0.0f));
            root.addOrReplaceChild("front_left", CubeListBuilder.create().texOffs(0, 12).addBox(0.0f, 0.0f, -1.0f, 7, 0, 2),
                    PartPose.offset(0.5f, 21.5f, -1.0f));
            root.addOrReplaceChild("front_right", CubeListBuilder.create().texOffs(0, 12).mirror().addBox(-7.0f, 0.0f, -1.0f, 7, 0, 2),
                    PartPose.offset(-0.5f, 21.5f, -1.0f));
            root.addOrReplaceChild("rear_left", CubeListBuilder.create().texOffs(0, 15).addBox(0.0f, 0.0f, -1.0f, 6, 0, 2),
                    PartPose.offset(0.5f, 21.5f, 1.0f));
            root.addOrReplaceChild("rear_right", CubeListBuilder.create().texOffs(0, 15).mirror().addBox(-6.0f, 0.0f, -1.0f, 6, 0, 2),
                    PartPose.offset(-0.5f, 21.5f, 1.0f));
            return LayerDefinition.create(mesh, 32, 32);
        }

        @Override
        public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            float a = Mth.sin(ageInTicks * 3.0f) * 0.35f;
            float b = Mth.sin(ageInTicks * 3.0f + Mth.PI * 0.5f) * 0.35f;
            this.frontLeft.zRot = -a;
            this.frontRight.zRot = a;
            this.rearLeft.zRot = -b;
            this.rearRight.zRot = b;
        }
    }

    /** Torchbug: a little beetle with a fat glowing abdomen (glow layer) and two small wings. */
    public static class Torchbug<T extends Entity> extends PoseEntityModel<T> {
        private final ModelPart root;
        private final ModelPart wingLeft;
        private final ModelPart wingRight;

        public Torchbug(ModelPart root) {
            super(root);
            this.root = root;
            this.wingLeft = root.getChild("wing_left");
            this.wingRight = root.getChild("wing_right");
        }

        public static LayerDefinition createLayer() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            root.addOrReplaceChild("body", CubeListBuilder.create()
                            .texOffs(0, 0).addBox(-1.0f, -1.0f, -2.0f, 2, 2, 3)          // head and thorax
                            .texOffs(0, 5).addBox(-1.5f, -1.5f, 1.0f, 3, 3, 3),          // glowing abdomen
                    PartPose.offset(0.0f, 22.0f, 0.0f));
            root.addOrReplaceChild("wing_left", CubeListBuilder.create().texOffs(12, 0).addBox(0.0f, 0.0f, -1.0f, 4, 0, 3),
                    PartPose.offset(0.5f, 21.0f, -0.5f));
            root.addOrReplaceChild("wing_right", CubeListBuilder.create().texOffs(12, 0).mirror().addBox(-4.0f, 0.0f, -1.0f, 4, 0, 3),
                    PartPose.offset(-0.5f, 21.0f, -0.5f));
            return LayerDefinition.create(mesh, 32, 32);
        }

        @Override
        public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            float flap = 0.3f + Mth.sin(ageInTicks * 2.2f) * 0.6f;
            this.wingLeft.zRot = -flap;
            this.wingRight.zRot = flap;
        }
    }
}
