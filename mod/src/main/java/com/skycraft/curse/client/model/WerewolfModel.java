package com.skycraft.curse.client.model;

import com.skycraft.curse.CurseAnims;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.LivingEntity;

import static com.skycraft.curse.client.model.Keys.anim;
import static com.skycraft.curse.client.model.Keys.k;
import static com.skycraft.curse.client.model.Keys.pos;
import static com.skycraft.curse.client.model.Keys.rot;

/**
 * The werewolf: a hunched, shaggy wolf-man on digitigrade legs, long clawed arms, a maned back, a long-snouted head
 * with a hinged jaw and a tail. Walks upright, runs on all fours, howls, swipes, feeds. Texture 128x64
 * ({@code tools/textures/curse.py} paints it from the boxes below). Used for {@code skycraft:werewolf} and Beast Form.
 */
public class WerewolfModel<T extends LivingEntity> extends BeastModel<T> {
    public WerewolfModel(ModelPart root) {
        super(root, net.minecraft.client.renderer.rendertype.RenderTypes::entityCutout);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-6f, -15f, -4f, 12f, 15f, 8f)
                        .texOffs(40, 0).addBox(-6.5f, -16f, 0f, 13f, 7f, 6f),
                PartPose.offsetAndRotation(0f, 10f, 2f, 0.45f, 0f, 0f));
        PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 23).addBox(-4f, -6f, -6f, 8f, 7f, 7f)
                        .texOffs(30, 23).addBox(-2.5f, -3f, -11f, 5f, 4f, 5f)
                        .texOffs(40, 13).addBox(-4f, -9f, -2f, 2f, 3f, 1f)
                        .texOffs(46, 13).addBox(2f, -9f, -2f, 2f, 3f, 1f),
                PartPose.offsetAndRotation(0f, -15f, -2f, -0.45f, 0f, 0f));
        head.addOrReplaceChild("jaw", CubeListBuilder.create()
                        .texOffs(52, 13).addBox(-2f, 0f, -5f, 4f, 2f, 5f),
                PartPose.offset(0f, 1f, -6f));
        PartDefinition rightArm = body.addOrReplaceChild("right_arm", CubeListBuilder.create()
                        .texOffs(80, 0).addBox(-3f, -1f, -2.5f, 4f, 11f, 5f),
                PartPose.offsetAndRotation(-7f, -13f, -1f, 0f, 0f, 0.15f));
        rightArm.addOrReplaceChild("right_forearm", CubeListBuilder.create()
                        .texOffs(98, 0).addBox(-2f, 0f, -2f, 4f, 10f, 4f)
                        .texOffs(98, 14).addBox(-2.5f, 10f, -2.5f, 5f, 3f, 5f),
                PartPose.offsetAndRotation(-1f, 9.5f, 0f, -0.4f, 0f, 0f));
        PartDefinition leftArm = body.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(80, 16).addBox(-1f, -1f, -2.5f, 4f, 11f, 5f),
                PartPose.offsetAndRotation(7f, -13f, -1f, 0f, 0f, -0.15f));
        leftArm.addOrReplaceChild("left_forearm", CubeListBuilder.create()
                        .texOffs(98, 22).addBox(-2f, 0f, -2f, 4f, 10f, 4f)
                        .texOffs(98, 36).addBox(-2.5f, 10f, -2.5f, 5f, 3f, 5f),
                PartPose.offsetAndRotation(1f, 9.5f, 0f, -0.4f, 0f, 0f));
        root.addOrReplaceChild("tail", CubeListBuilder.create()
                        .texOffs(50, 23).addBox(-1.5f, -1.5f, 0f, 3f, 3f, 10f),
                PartPose.offsetAndRotation(0f, 9f, 5f, -0.9f, 0f, 0f));
        PartDefinition rightLeg = root.addOrReplaceChild("right_leg", CubeListBuilder.create()
                        .texOffs(0, 37).addBox(-2.5f, -1f, -3f, 5f, 8f, 6f),
                PartPose.offsetAndRotation(-3.5f, 10f, 2.5f, -0.35f, 0f, 0f));
        PartDefinition rightShin = rightLeg.addOrReplaceChild("right_shin", CubeListBuilder.create()
                        .texOffs(22, 37).addBox(-2f, 0f, -2f, 4f, 7f, 4f),
                PartPose.offsetAndRotation(0f, 6.5f, -1f, 0.75f, 0f, 0f));
        rightShin.addOrReplaceChild("right_foot", CubeListBuilder.create()
                        .texOffs(38, 37).addBox(-2.5f, 0f, -5f, 5f, 2f, 6f),
                PartPose.offsetAndRotation(0f, 6.5f, 0f, -0.4f, 0f, 0f));
        PartDefinition leftLeg = root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                        .texOffs(60, 37).addBox(-2.5f, -1f, -3f, 5f, 8f, 6f),
                PartPose.offsetAndRotation(3.5f, 10f, 2.5f, -0.35f, 0f, 0f));
        PartDefinition leftShin = leftLeg.addOrReplaceChild("left_shin", CubeListBuilder.create()
                        .texOffs(82, 37).addBox(-2f, 0f, -2f, 4f, 7f, 4f),
                PartPose.offsetAndRotation(0f, 6.5f, -1f, 0.75f, 0f, 0f));
        leftShin.addOrReplaceChild("left_foot", CubeListBuilder.create()
                        .texOffs(0, 51).addBox(-2.5f, 0f, -5f, 5f, 2f, 6f),
                PartPose.offsetAndRotation(0f, 6.5f, 0f, -0.4f, 0f, 0f));
        return LayerDefinition.create(mesh, 128, 64);
    }

    // ------------------------------------------------------------------ animations

    static final AnimationDefinition IDLE = anim(3f, true,
            rot("body", k(0f, 0f, 0f, 0f), k(1.5f, 3f, 0f, 0f), k(3f, 0f, 0f, 0f)),
            rot("head", k(0f, 0f, 0f, 0f), k(1.5f, -3f, 4f, 0f), k(3f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, 0f, 0f, 0f), k(1.5f, -4f, 0f, 3f), k(3f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, 0f, 0f, 0f), k(1.5f, -4f, 0f, -3f), k(3f, 0f, 0f, 0f)),
            rot("tail", k(0f, 0f, -8f, 0f), k(1.5f, 4f, 8f, 0f), k(3f, 0f, -8f, 0f)));

    static final AnimationDefinition WALK = anim(1f, true,
            rot("right_leg", k(0f, -28f, 0f, 0f), k(0.5f, 24f, 0f, 0f), k(1f, -28f, 0f, 0f)),
            rot("right_shin", k(0f, 10f, 0f, 0f), k(0.25f, 30f, 0f, 0f), k(0.5f, 0f, 0f, 0f), k(1f, 10f, 0f, 0f)),
            rot("left_leg", k(0f, 24f, 0f, 0f), k(0.5f, -28f, 0f, 0f), k(1f, 24f, 0f, 0f)),
            rot("left_shin", k(0f, 0f, 0f, 0f), k(0.5f, 10f, 0f, 0f), k(0.75f, 30f, 0f, 0f), k(1f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, 22f, 0f, 0f), k(0.5f, -26f, 0f, 0f), k(1f, 22f, 0f, 0f)),
            rot("left_arm", k(0f, -26f, 0f, 0f), k(0.5f, 22f, 0f, 0f), k(1f, -26f, 0f, 0f)),
            rot("body", k(0f, 0f, 3f, 0f), k(0.5f, 0f, -3f, 0f), k(1f, 0f, 3f, 0f)),
            pos("body", k(0f, 0f, 0f, 0f), k(0.25f, 0f, 0.8f, 0f), k(0.5f, 0f, 0f, 0f), k(0.75f, 0f, 0.8f, 0f), k(1f, 0f, 0f, 0f)),
            rot("tail", k(0f, 0f, -12f, 0f), k(0.5f, 0f, 12f, 0f), k(1f, 0f, -12f, 0f)));

    /** Loping on all fours: chest dropped low, arms reaching and pulling like forelegs. */
    static final AnimationDefinition RUN = anim(0.6f, true,
            rot("body", k(0f, 38f, 0f, 0f), k(0.3f, 46f, 0f, 0f), k(0.6f, 38f, 0f, 0f)),
            pos("body", k(0f, 0f, -1f, 0f), k(0.15f, 0f, 0.5f, 0f), k(0.3f, 0f, -1f, 0f), k(0.45f, 0f, 0.5f, 0f), k(0.6f, 0f, -1f, 0f)),
            rot("head", k(0f, -34f, 0f, 0f), k(0.3f, -40f, 0f, 0f), k(0.6f, -34f, 0f, 0f)),
            rot("right_arm", k(0f, -75f, 0f, 0f), k(0.3f, -10f, 0f, 0f), k(0.6f, -75f, 0f, 0f)),
            rot("left_arm", k(0f, -60f, 0f, 0f), k(0.3f, -5f, 0f, 0f), k(0.6f, -60f, 0f, 0f)),
            rot("right_forearm", k(0f, 10f, 0f, 0f), k(0.3f, -20f, 0f, 0f), k(0.6f, 10f, 0f, 0f)),
            rot("left_forearm", k(0f, 10f, 0f, 0f), k(0.3f, -20f, 0f, 0f), k(0.6f, 10f, 0f, 0f)),
            rot("right_leg", k(0f, 35f, 0f, 0f), k(0.3f, -45f, 0f, 0f), k(0.6f, 35f, 0f, 0f)),
            rot("left_leg", k(0f, 25f, 0f, 0f), k(0.3f, -50f, 0f, 0f), k(0.6f, 25f, 0f, 0f)),
            rot("right_shin", k(0f, 0f, 0f, 0f), k(0.15f, 30f, 0f, 0f), k(0.3f, 10f, 0f, 0f), k(0.6f, 0f, 0f, 0f)),
            rot("left_shin", k(0f, 0f, 0f, 0f), k(0.15f, 30f, 0f, 0f), k(0.3f, 10f, 0f, 0f), k(0.6f, 0f, 0f, 0f)),
            rot("tail", k(0f, 25f, 0f, 0f), k(0.3f, 35f, 0f, 0f), k(0.6f, 25f, 0f, 0f)));

    static final AnimationDefinition ATTACK_RIGHT = anim(0.6f, false,
            rot("right_arm", k(0f, 0f, 0f, 0f), k(0.15f, -150f, 0f, 30f), k(0.32f, -20f, 0f, -20f), k(0.6f, 0f, 0f, 0f)),
            rot("right_forearm", k(0f, 0f, 0f, 0f), k(0.15f, -30f, 0f, 0f), k(0.32f, 0f, 0f, 0f), k(0.6f, 0f, 0f, 0f)),
            rot("body", k(0f, 0f, 0f, 0f), k(0.15f, -6f, 15f, 0f), k(0.32f, 12f, -18f, 0f), k(0.6f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.2f, 25f, 0f, 0f), k(0.45f, 0f, 0f, 0f)));

    static final AnimationDefinition ATTACK_LEFT = anim(0.6f, false,
            rot("left_arm", k(0f, 0f, 0f, 0f), k(0.15f, -150f, 0f, -30f), k(0.32f, -20f, 0f, 20f), k(0.6f, 0f, 0f, 0f)),
            rot("left_forearm", k(0f, 0f, 0f, 0f), k(0.15f, -30f, 0f, 0f), k(0.32f, 0f, 0f, 0f), k(0.6f, 0f, 0f, 0f)),
            rot("body", k(0f, 0f, 0f, 0f), k(0.15f, -6f, -15f, 0f), k(0.32f, 12f, 18f, 0f), k(0.6f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.2f, 25f, 0f, 0f), k(0.45f, 0f, 0f, 0f)));

    static final AnimationDefinition HOWL = anim(2f, false,
            rot("body", k(0f, 0f, 0f, 0f), k(0.4f, -28f, 0f, 0f), k(1.6f, -30f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("head", k(0f, 0f, 0f, 0f), k(0.4f, -45f, 0f, 0f), k(1.0f, -55f, 0f, 0f), k(1.6f, -50f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.4f, 35f, 0f, 0f), k(1.6f, 35f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, 0f, 0f, 0f), k(0.4f, 20f, 0f, 35f), k(1.6f, 20f, 0f, 40f), k(2f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, 0f, 0f, 0f), k(0.4f, 20f, 0f, -35f), k(1.6f, 20f, 0f, -40f), k(2f, 0f, 0f, 0f)),
            rot("tail", k(0f, 0f, 0f, 0f), k(0.4f, 30f, 0f, 0f), k(1.6f, 30f, 0f, 0f), k(2f, 0f, 0f, 0f)));

    static final AnimationDefinition FEED = anim(2.5f, false,
            rot("body", k(0f, 0f, 0f, 0f), k(0.4f, 45f, 0f, 0f), k(2.1f, 45f, 0f, 0f), k(2.5f, 0f, 0f, 0f)),
            pos("body", k(0f, 0f, 0f, 0f), k(0.4f, 0f, -4f, -2f), k(2.1f, 0f, -4f, -2f), k(2.5f, 0f, 0f, 0f)),
            rot("head", k(0f, 0f, 0f, 0f), k(0.4f, 30f, 0f, 0f), k(0.8f, 10f, 10f, 0f), k(1.2f, 35f, -10f, 0f), k(1.6f, 15f, 8f, 0f), k(2.1f, 30f, 0f, 0f), k(2.5f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.6f, 30f, 0f, 0f), k(0.8f, 0f, 0f, 0f), k(1.0f, 30f, 0f, 0f), k(1.2f, 0f, 0f, 0f), k(1.4f, 30f, 0f, 0f),
                    k(1.6f, 0f, 0f, 0f), k(1.8f, 30f, 0f, 0f), k(2.5f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, 0f, 0f, 0f), k(0.4f, -60f, 0f, 10f), k(2.1f, -60f, 0f, 10f), k(2.5f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, 0f, 0f, 0f), k(0.4f, -60f, 0f, -10f), k(2.1f, -60f, 0f, -10f), k(2.5f, 0f, 0f, 0f)),
            rot("right_leg", k(0f, 0f, 0f, 0f), k(0.4f, -40f, 0f, 0f), k(2.1f, -40f, 0f, 0f), k(2.5f, 0f, 0f, 0f)),
            rot("left_leg", k(0f, 0f, 0f, 0f), k(0.4f, -40f, 0f, 0f), k(2.1f, -40f, 0f, 0f), k(2.5f, 0f, 0f, 0f)),
            rot("right_shin", k(0f, 0f, 0f, 0f), k(0.4f, 50f, 0f, 0f), k(2.1f, 50f, 0f, 0f), k(2.5f, 0f, 0f, 0f)),
            rot("left_shin", k(0f, 0f, 0f, 0f), k(0.4f, 50f, 0f, 0f), k(2.1f, 50f, 0f, 0f), k(2.5f, 0f, 0f, 0f)));

    /** The change: doubled over, then thrown back with arms wide. */
    static final AnimationDefinition TRANSFORM = anim(2f, false,
            rot("body", k(0f, 50f, 0f, 0f), k(0.3f, 55f, 4f, 0f), k(0.5f, 50f, -4f, 0f), k(0.8f, 55f, 4f, 0f), k(1.2f, -30f, 0f, 0f), k(1.6f, -25f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("head", k(0f, 30f, 0f, 0f), k(0.8f, 30f, 0f, 0f), k(1.2f, -50f, 0f, 0f), k(1.6f, -45f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(1.2f, 35f, 0f, 0f), k(1.6f, 35f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, -20f, 0f, 10f), k(0.8f, -20f, 0f, 10f), k(1.2f, 30f, 0f, 70f), k(1.6f, 30f, 0f, 65f), k(2f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, -20f, 0f, -10f), k(0.8f, -20f, 0f, -10f), k(1.2f, 30f, 0f, -70f), k(1.6f, 30f, 0f, -65f), k(2f, 0f, 0f, 0f)));

    @Override
    protected AnimationDefinition oneShot(CurseAnims.Anim anim, boolean left) {
        return switch (anim) {
            case ATTACK -> left ? ATTACK_LEFT : ATTACK_RIGHT;
            case HOWL, CAST -> HOWL;
            case FEED -> FEED;
            case TRANSFORM -> TRANSFORM;
            default -> null;
        };
    }

    @Override
    protected void loop(T entity, float limbSwing, float limbSwingAmount, float ageInTicks) {
        if (limbSwingAmount > 0.05f) {
            if (running(entity, limbSwingAmount)) this.animateWalk(RUN, limbSwing, limbSwingAmount, 1.6f, 1f);
            else this.animateWalk(WALK, limbSwing, limbSwingAmount, 2.0f, 2.5f);
        } else {
            looping(IDLE, ageInTicks, 1f);
        }
    }
}
