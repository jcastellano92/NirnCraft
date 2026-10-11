package com.skycraft.curse.client.model;

import com.skycraft.curse.CurseAnims;
import com.skycraft.curse.entity.GargoyleEntity;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.LivingEntity;

import static com.skycraft.curse.client.model.Keys.anim;
import static com.skycraft.curse.client.model.Keys.k;
import static com.skycraft.curse.client.model.Keys.pos;
import static com.skycraft.curse.client.model.Keys.rot;

/**
 * The gargoyle: a squat, horned stone brute with short wings, heavy arms and a tail. Shares the Vampire Lord's part
 * names, so it moves with the same walk, claw and roar animations; while dormant it crouches as a statue, and
 * {@link CurseAnims.Anim#AWAKEN} cracks it free. Texture 128x64.
 */
public class GargoyleModel<T extends LivingEntity> extends BeastModel<T> {
    public GargoyleModel(ModelPart root) {
        super(root, RenderType::entityCutout);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-5.5f, -13f, -3.5f, 11f, 13f, 7f)
                        .texOffs(36, 0).addBox(-4f, -1f, -3f, 8f, 4f, 6f),
                PartPose.offsetAndRotation(0f, 6f, 1f, 0.35f, 0f, 0f));
        PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 20).addBox(-3.5f, -6f, -5f, 7f, 6f, 6f)
                        .texOffs(26, 20).addBox(-4.5f, -9f, -2f, 2f, 4f, 2f)
                        .texOffs(34, 20).addBox(2.5f, -9f, -2f, 2f, 4f, 2f)
                        .texOffs(42, 20).addBox(-2.5f, -3f, -7f, 5f, 3f, 2f),
                PartPose.offsetAndRotation(0f, -13f, -2f, -0.35f, 0f, 0f));
        head.addOrReplaceChild("jaw", CubeListBuilder.create()
                        .texOffs(26, 26).addBox(-2.5f, 0f, -3f, 5f, 2f, 4f),
                PartPose.offset(0f, 0f, -4f));
        PartDefinition rightArm = body.addOrReplaceChild("right_arm", CubeListBuilder.create()
                        .texOffs(64, 0).addBox(-2.5f, -1.5f, -2f, 4f, 12f, 4f),
                PartPose.offsetAndRotation(-6.5f, -11f, 0f, 0f, 0f, 0.15f));
        rightArm.addOrReplaceChild("right_forearm", CubeListBuilder.create()
                        .texOffs(80, 0).addBox(-2f, 0f, -2f, 4f, 11f, 4f)
                        .texOffs(96, 0).addBox(-2.5f, 10.5f, -2.5f, 5f, 3f, 5f),
                PartPose.offsetAndRotation(-0.5f, 10f, 0f, -0.35f, 0f, 0f));
        PartDefinition leftArm = body.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(64, 16).addBox(-1.5f, -1.5f, -2f, 4f, 12f, 4f),
                PartPose.offsetAndRotation(6.5f, -11f, 0f, 0f, 0f, -0.15f));
        leftArm.addOrReplaceChild("left_forearm", CubeListBuilder.create()
                        .texOffs(80, 16).addBox(-2f, 0f, -2f, 4f, 11f, 4f)
                        .texOffs(96, 8).addBox(-2.5f, 10.5f, -2.5f, 5f, 3f, 5f),
                PartPose.offsetAndRotation(0.5f, 10f, 0f, -0.35f, 0f, 0f));
        PartDefinition rightWing = body.addOrReplaceChild("right_wing", CubeListBuilder.create()
                        .texOffs(0, 32).addBox(-10f, -1f, -1f, 10f, 2f, 2f)
                        .texOffs(0, 36).addBox(-10f, 0f, 0f, 10f, 12f, 0f),
                PartPose.offsetAndRotation(-2.5f, -11f, 3.5f, 0f, 1.2f, 0f));
        rightWing.addOrReplaceChild("right_wing_tip", CubeListBuilder.create()
                        .texOffs(24, 32).addBox(-8f, -1f, -1f, 8f, 2f, 2f)
                        .texOffs(20, 36).addBox(-8f, 0f, 0f, 8f, 14f, 0f),
                PartPose.offsetAndRotation(-10f, 0f, 0f, 0f, -2.6f, 0f));
        PartDefinition leftWing = body.addOrReplaceChild("left_wing", CubeListBuilder.create()
                        .texOffs(44, 32).addBox(0f, -1f, -1f, 10f, 2f, 2f)
                        .texOffs(36, 36).addBox(0f, 0f, 0f, 10f, 12f, 0f),
                PartPose.offsetAndRotation(2.5f, -11f, 3.5f, 0f, -1.2f, 0f));
        leftWing.addOrReplaceChild("left_wing_tip", CubeListBuilder.create()
                        .texOffs(68, 32).addBox(0f, -1f, -1f, 8f, 2f, 2f)
                        .texOffs(56, 36).addBox(0f, 0f, 0f, 8f, 14f, 0f),
                PartPose.offsetAndRotation(10f, 0f, 0f, 0f, 2.6f, 0f));
        root.addOrReplaceChild("tail", CubeListBuilder.create()
                        .texOffs(0, 50).addBox(-1.5f, -1f, 0f, 3f, 3f, 10f),
                PartPose.offsetAndRotation(0f, 6f, 4f, -0.7f, 0f, 0f));
        PartDefinition rightLeg = root.addOrReplaceChild("right_leg", CubeListBuilder.create()
                        .texOffs(96, 16).addBox(-2f, 0f, -2f, 4f, 9f, 4f),
                PartPose.offsetAndRotation(-3f, 6f, 2f, -0.35f, 0f, 0f));
        rightLeg.addOrReplaceChild("right_shin", CubeListBuilder.create()
                        .texOffs(112, 16).addBox(-2f, 0f, -2f, 4f, 8f, 4f)
                        .texOffs(88, 32).addBox(-2.5f, 7.5f, -4f, 5f, 2f, 6f),
                PartPose.offsetAndRotation(0f, 8.5f, 0f, 0.7f, 0f, 0f));
        PartDefinition leftLeg = root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                        .texOffs(96, 40).addBox(-2f, 0f, -2f, 4f, 9f, 4f),
                PartPose.offsetAndRotation(3f, 6f, 2f, -0.35f, 0f, 0f));
        leftLeg.addOrReplaceChild("left_shin", CubeListBuilder.create()
                        .texOffs(112, 40).addBox(-2f, 0f, -2f, 4f, 8f, 4f)
                        .texOffs(72, 40).addBox(-2.5f, 7.5f, -4f, 5f, 2f, 6f),
                PartPose.offsetAndRotation(0f, 8.5f, 0f, 0.7f, 0f, 0f));
        return LayerDefinition.create(mesh, 128, 64);
    }

    /** Crouched on its haunches, wings wrapped, claws on its knees: a statue. */
    static final AnimationDefinition STATUE = anim(1f, true,
            rot("body", k(0f, 25f, 0f, 0f), k(1f, 25f, 0f, 0f)),
            pos("body", k(0f, 0f, -5f, 1f), k(1f, 0f, -5f, 1f)),
            rot("head", k(0f, -20f, 0f, 0f), k(1f, -20f, 0f, 0f)),
            rot("right_leg", k(0f, -75f, 0f, 10f), k(1f, -75f, 0f, 10f)),
            pos("right_leg", k(0f, 0f, -5f, 0f), k(1f, 0f, -5f, 0f)),
            rot("left_leg", k(0f, -75f, 0f, -10f), k(1f, -75f, 0f, -10f)),
            pos("left_leg", k(0f, 0f, -5f, 0f), k(1f, 0f, -5f, 0f)),
            rot("right_shin", k(0f, 80f, 0f, 0f), k(1f, 80f, 0f, 0f)),
            rot("left_shin", k(0f, 80f, 0f, 0f), k(1f, 80f, 0f, 0f)),
            rot("right_arm", k(0f, -40f, 0f, 5f), k(1f, -40f, 0f, 5f)),
            rot("left_arm", k(0f, -40f, 0f, -5f), k(1f, -40f, 0f, -5f)),
            rot("tail", k(0f, 40f, 0f, 0f), k(1f, 40f, 0f, 0f)));

    static final AnimationDefinition AWAKEN = anim(1.5f, false,
            rot("body", k(0f, 25f, 0f, 0f), k(0.4f, 25f, 6f, 0f), k(0.7f, -20f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            pos("body", k(0f, 0f, -5f, 1f), k(0.4f, 0f, -5f, 1f), k(0.8f, 0f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("right_leg", k(0f, -75f, 0f, 10f), k(0.4f, -75f, 0f, 10f), k(0.8f, 0f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            pos("right_leg", k(0f, 0f, -5f, 0f), k(0.4f, 0f, -5f, 0f), k(0.8f, 0f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("left_leg", k(0f, -75f, 0f, -10f), k(0.4f, -75f, 0f, -10f), k(0.8f, 0f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            pos("left_leg", k(0f, 0f, -5f, 0f), k(0.4f, 0f, -5f, 0f), k(0.8f, 0f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("right_shin", k(0f, 80f, 0f, 0f), k(0.4f, 80f, 0f, 0f), k(0.8f, 0f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("left_shin", k(0f, 80f, 0f, 0f), k(0.4f, 80f, 0f, 0f), k(0.8f, 0f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("right_wing", k(0f, 0f, 0f, 0f), k(0.6f, 0f, -60f, 30f), k(1.1f, 0f, -60f, 30f), k(1.5f, 0f, 0f, 0f)),
            rot("left_wing", k(0f, 0f, 0f, 0f), k(0.6f, 0f, 60f, -30f), k(1.1f, 0f, 60f, -30f), k(1.5f, 0f, 0f, 0f)),
            rot("right_wing_tip", k(0f, 0f, 0f, 0f), k(0.6f, 0f, 150f, 0f), k(1.1f, 0f, 150f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("left_wing_tip", k(0f, 0f, 0f, 0f), k(0.6f, 0f, -150f, 0f), k(1.1f, 0f, -150f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("head", k(0f, -20f, 0f, 0f), k(0.7f, -40f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.7f, 35f, 0f, 0f), k(1.2f, 35f, 0f, 0f), k(1.5f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, -40f, 0f, 5f), k(0.4f, -40f, 0f, 5f), k(0.8f, 0f, 0f, 60f), k(1.5f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, -40f, 0f, -5f), k(0.4f, -40f, 0f, -5f), k(0.8f, 0f, 0f, -60f), k(1.5f, 0f, 0f, 0f)));

    @Override
    protected AnimationDefinition oneShot(CurseAnims.Anim anim, boolean left) {
        return switch (anim) {
            case ATTACK -> left ? VampireLordModel.ATTACK_LEFT : VampireLordModel.ATTACK_RIGHT;
            case HOWL, CAST -> VampireLordModel.ROAR;
            case AWAKEN -> AWAKEN;
            default -> null;
        };
    }

    @Override
    protected void loop(T entity, float limbSwing, float limbSwingAmount, float ageInTicks) {
        if (entity instanceof GargoyleEntity g && g.isDormant()) {
            looping(STATUE, 0f, 1f);
        } else if (limbSwingAmount > 0.05f) {
            this.animateWalk(VampireLordModel.WALK, limbSwing, limbSwingAmount, 2.0f, 2.5f);
        } else {
            looping(VampireLordModel.IDLE, ageInTicks, 1f);
        }
    }
}
