package com.nirncraft.curse.client.model;

import com.nirncraft.curse.CurseAnims;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.LivingEntity;

import static com.nirncraft.curse.client.model.Keys.anim;
import static com.nirncraft.curse.client.model.Keys.k;
import static com.nirncraft.curse.client.model.Keys.pos;
import static com.nirncraft.curse.client.model.Keys.rot;

/**
 * The Vampire Lord (Dawnguard): a gaunt, grey-skinned giant with bat ears, a spiked crown, long clawed arms and great
 * membrane wings that fold down its back on the ground and spread wide when it levitates. Texture 128x64. Used for
 * {@code nirncraft:vampire_lord} and the player's Vampire Lord form; the gargoyle shares its part names and
 * animations ({@link GargoyleModel}).
 */
public class VampireLordModel<T extends LivingEntity> extends BeastModel<T> {
    public VampireLordModel(ModelPart root) {
        super(root, net.minecraft.client.renderer.rendertype.RenderTypes::entityCutout);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.5f, -14f, -3f, 9f, 14f, 6f)
                        .texOffs(30, 0).addBox(-3.5f, -1f, -2.5f, 7f, 4f, 5f)
                        .texOffs(54, 0).addBox(-3f, 2f, -2.7f, 6f, 7f, 0f),
                PartPose.offsetAndRotation(0f, 7f, 1f, 0.25f, 0f, 0f));
        PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 20).addBox(-3.5f, -7f, -4f, 7f, 7f, 7f)
                        .texOffs(28, 20).addBox(-6.5f, -9f, -1f, 3f, 5f, 1f)
                        .texOffs(36, 20).addBox(3.5f, -9f, -1f, 3f, 5f, 1f)
                        .texOffs(44, 20).addBox(-3f, -9f, -2f, 6f, 2f, 4f)
                        .texOffs(28, 26).addBox(-2f, -3f, -6f, 4f, 3f, 2f),
                PartPose.offsetAndRotation(0f, -14f, -1f, -0.25f, 0f, 0f));
        head.addOrReplaceChild("jaw", CubeListBuilder.create()
                        .texOffs(40, 26).addBox(-2.5f, 0f, -3f, 5f, 2f, 4f),
                PartPose.offset(0f, 0f, -3f));
        PartDefinition rightArm = body.addOrReplaceChild("right_arm", CubeListBuilder.create()
                        .texOffs(66, 0).addBox(-2f, -1f, -1.5f, 3f, 13f, 3f),
                PartPose.offsetAndRotation(-5.5f, -12.5f, 0f, 0f, 0f, 0.1f));
        rightArm.addOrReplaceChild("right_forearm", CubeListBuilder.create()
                        .texOffs(78, 0).addBox(-1.5f, 0f, -1.5f, 3f, 12f, 3f)
                        .texOffs(90, 0).addBox(-2f, 11.5f, -2.5f, 4f, 4f, 5f),
                PartPose.offsetAndRotation(-0.5f, 11.5f, 0f, -0.3f, 0f, 0f));
        PartDefinition leftArm = body.addOrReplaceChild("left_arm", CubeListBuilder.create()
                        .texOffs(66, 16).addBox(-1f, -1f, -1.5f, 3f, 13f, 3f),
                PartPose.offsetAndRotation(5.5f, -12.5f, 0f, 0f, 0f, -0.1f));
        leftArm.addOrReplaceChild("left_forearm", CubeListBuilder.create()
                        .texOffs(78, 16).addBox(-1.5f, 0f, -1.5f, 3f, 12f, 3f)
                        .texOffs(90, 9).addBox(-2f, 11.5f, -2.5f, 4f, 4f, 5f),
                PartPose.offsetAndRotation(0.5f, 11.5f, 0f, -0.3f, 0f, 0f));
        PartDefinition rightWing = body.addOrReplaceChild("right_wing", CubeListBuilder.create()
                        .texOffs(0, 34).addBox(-16f, -1f, -1f, 16f, 2f, 2f)
                        .texOffs(0, 38).addBox(-16f, 0f, 0f, 16f, 18f, 0f),
                PartPose.offsetAndRotation(-2.5f, -12f, 3f, 0f, 1.35f, 0f));
        rightWing.addOrReplaceChild("right_wing_tip", CubeListBuilder.create()
                        .texOffs(36, 34).addBox(-14f, -1f, -1f, 14f, 2f, 2f)
                        .texOffs(32, 38).addBox(-14f, 0f, 0f, 14f, 22f, 0f),
                PartPose.offsetAndRotation(-16f, 0f, 0f, 0f, -2.8f, 0f));
        PartDefinition leftWing = body.addOrReplaceChild("left_wing", CubeListBuilder.create()
                        .texOffs(60, 38).addBox(0f, -1f, -1f, 16f, 2f, 2f)
                        .texOffs(60, 42).addBox(0f, 0f, 0f, 16f, 18f, 0f),
                PartPose.offsetAndRotation(2.5f, -12f, 3f, 0f, -1.35f, 0f));
        leftWing.addOrReplaceChild("left_wing_tip", CubeListBuilder.create()
                        .texOffs(68, 34).addBox(0f, -1f, -1f, 14f, 2f, 2f)
                        .texOffs(92, 42).addBox(0f, 0f, 0f, 14f, 22f, 0f),
                PartPose.offsetAndRotation(16f, 0f, 0f, 0f, 2.8f, 0f));
        PartDefinition rightLeg = root.addOrReplaceChild("right_leg", CubeListBuilder.create()
                        .texOffs(108, 0).addBox(-1.5f, 0f, -1.5f, 3f, 9f, 3f),
                PartPose.offsetAndRotation(-2.5f, 7f, 1.5f, -0.3f, 0f, 0f));
        rightLeg.addOrReplaceChild("right_shin", CubeListBuilder.create()
                        .texOffs(108, 12).addBox(-1.5f, 0f, -1.5f, 3f, 8f, 3f)
                        .texOffs(90, 18).addBox(-2f, 7.5f, -4f, 4f, 1f, 5f),
                PartPose.offsetAndRotation(0f, 8.5f, 0f, 0.6f, 0f, 0f));
        PartDefinition leftLeg = root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                        .texOffs(108, 23).addBox(-1.5f, 0f, -1.5f, 3f, 9f, 3f),
                PartPose.offsetAndRotation(2.5f, 7f, 1.5f, -0.3f, 0f, 0f));
        leftLeg.addOrReplaceChild("left_shin", CubeListBuilder.create()
                        .texOffs(54, 9).addBox(-1.5f, 0f, -1.5f, 3f, 8f, 3f)
                        .texOffs(90, 24).addBox(-2f, 7.5f, -4f, 4f, 1f, 5f),
                PartPose.offsetAndRotation(0f, 8.5f, 0f, 0.6f, 0f, 0f));
        return LayerDefinition.create(mesh, 128, 64);
    }

    // ------------------------------------------------------------------ animations (offsets from the folded pose)

    static final AnimationDefinition IDLE = anim(4f, true,
            rot("body", k(0f, 0f, 0f, 0f), k(2f, 2.5f, 0f, 0f), k(4f, 0f, 0f, 0f)),
            rot("head", k(0f, 0f, 0f, 0f), k(2f, -3f, 3f, 0f), k(4f, 0f, 0f, 0f)),
            rot("right_wing", k(0f, 0f, 0f, 0f), k(2f, 0f, -6f, 3f), k(4f, 0f, 0f, 0f)),
            rot("left_wing", k(0f, 0f, 0f, 0f), k(2f, 0f, 6f, -3f), k(4f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, 0f, 0f, 0f), k(2f, -3f, 0f, 3f), k(4f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, 0f, 0f, 0f), k(2f, -3f, 0f, -3f), k(4f, 0f, 0f, 0f)));

    static final AnimationDefinition WALK = anim(1.2f, true,
            rot("right_leg", k(0f, -25f, 0f, 0f), k(0.6f, 22f, 0f, 0f), k(1.2f, -25f, 0f, 0f)),
            rot("right_shin", k(0f, 5f, 0f, 0f), k(0.3f, 25f, 0f, 0f), k(0.6f, 0f, 0f, 0f), k(1.2f, 5f, 0f, 0f)),
            rot("left_leg", k(0f, 22f, 0f, 0f), k(0.6f, -25f, 0f, 0f), k(1.2f, 22f, 0f, 0f)),
            rot("left_shin", k(0f, 0f, 0f, 0f), k(0.6f, 5f, 0f, 0f), k(0.9f, 25f, 0f, 0f), k(1.2f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, 20f, 0f, 0f), k(0.6f, -22f, 0f, 0f), k(1.2f, 20f, 0f, 0f)),
            rot("left_arm", k(0f, -22f, 0f, 0f), k(0.6f, 20f, 0f, 0f), k(1.2f, -22f, 0f, 0f)),
            rot("body", k(0f, 0f, 4f, 0f), k(0.6f, 0f, -4f, 0f), k(1.2f, 0f, 4f, 0f)),
            pos("body", k(0f, 0f, 0f, 0f), k(0.3f, 0f, 0.7f, 0f), k(0.6f, 0f, 0f, 0f), k(0.9f, 0f, 0.7f, 0f), k(1.2f, 0f, 0f, 0f)));

    /** Levitating: upright, wings spread and beating slowly, legs and talons hanging. */
    static final AnimationDefinition HOVER = anim(1.4f, true,
            rot("body", k(0f, -12f, 0f, 0f), k(0.7f, -9f, 0f, 0f), k(1.4f, -12f, 0f, 0f)),
            pos("body", k(0f, 0f, 0f, 0f), k(0.7f, 0f, 1.2f, 0f), k(1.4f, 0f, 0f, 0f)),
            rot("right_wing", k(0f, 0f, -57f, 40f), k(0.5f, 0f, -62f, -15f), k(0.9f, 0f, -55f, 5f), k(1.4f, 0f, -57f, 40f)),
            rot("right_wing_tip", k(0f, 0f, 170f, 10f), k(0.5f, 0f, 160f, -20f), k(1.4f, 0f, 170f, 10f)),
            rot("left_wing", k(0f, 0f, 57f, -40f), k(0.5f, 0f, 62f, 15f), k(0.9f, 0f, 55f, -5f), k(1.4f, 0f, 57f, -40f)),
            rot("left_wing_tip", k(0f, 0f, -170f, -10f), k(0.5f, 0f, -160f, 20f), k(1.4f, 0f, -170f, -10f)),
            rot("right_leg", k(0f, 25f, 0f, 5f), k(0.7f, 20f, 0f, 3f), k(1.4f, 25f, 0f, 5f)),
            rot("left_leg", k(0f, 18f, 0f, -5f), k(0.7f, 24f, 0f, -3f), k(1.4f, 18f, 0f, -5f)),
            rot("right_shin", k(0f, 15f, 0f, 0f), k(0.7f, 25f, 0f, 0f), k(1.4f, 15f, 0f, 0f)),
            rot("left_shin", k(0f, 25f, 0f, 0f), k(0.7f, 15f, 0f, 0f), k(1.4f, 25f, 0f, 0f)),
            rot("right_arm", k(0f, -15f, 0f, 25f), k(0.7f, -10f, 0f, 30f), k(1.4f, -15f, 0f, 25f)),
            rot("left_arm", k(0f, -15f, 0f, -25f), k(0.7f, -10f, 0f, -30f), k(1.4f, -15f, 0f, -25f)));

    static final AnimationDefinition ATTACK_RIGHT = anim(0.6f, false,
            rot("right_arm", k(0f, 0f, 0f, 0f), k(0.15f, -140f, 0f, 35f), k(0.32f, -15f, 0f, -25f), k(0.6f, 0f, 0f, 0f)),
            rot("body", k(0f, 0f, 0f, 0f), k(0.15f, -6f, 15f, 0f), k(0.32f, 10f, -18f, 0f), k(0.6f, 0f, 0f, 0f)),
            rot("right_wing", k(0f, 0f, 0f, 0f), k(0.2f, 0f, -20f, 10f), k(0.6f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.2f, 25f, 0f, 0f), k(0.45f, 0f, 0f, 0f)));

    static final AnimationDefinition ATTACK_LEFT = anim(0.6f, false,
            rot("left_arm", k(0f, 0f, 0f, 0f), k(0.15f, -140f, 0f, -35f), k(0.32f, -15f, 0f, 25f), k(0.6f, 0f, 0f, 0f)),
            rot("body", k(0f, 0f, 0f, 0f), k(0.15f, -6f, -15f, 0f), k(0.32f, 10f, 18f, 0f), k(0.6f, 0f, 0f, 0f)),
            rot("left_wing", k(0f, 0f, 0f, 0f), k(0.2f, 0f, 20f, -10f), k(0.6f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.2f, 25f, 0f, 0f), k(0.45f, 0f, 0f, 0f)));

    /** Drain Life / a spell: the right claw thrust forward, fingers spread. */
    static final AnimationDefinition CAST = anim(0.8f, false,
            rot("right_arm", k(0f, 0f, 0f, 0f), k(0.15f, -85f, -10f, 5f), k(0.6f, -80f, -10f, 5f), k(0.8f, 0f, 0f, 0f)),
            rot("right_forearm", k(0f, 0f, 0f, 0f), k(0.15f, 15f, 0f, 0f), k(0.6f, 15f, 0f, 0f), k(0.8f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, 0f, 0f, 0f), k(0.2f, -30f, 0f, -20f), k(0.6f, -30f, 0f, -20f), k(0.8f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.2f, 20f, 0f, 0f), k(0.6f, 20f, 0f, 0f), k(0.8f, 0f, 0f, 0f)));

    /** A roar with the wings flared wide (night powers, Raise Dead). */
    static final AnimationDefinition ROAR = anim(2f, false,
            rot("body", k(0f, 0f, 0f, 0f), k(0.4f, -20f, 0f, 0f), k(1.5f, -20f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("head", k(0f, 0f, 0f, 0f), k(0.4f, -40f, 0f, 0f), k(1.5f, -40f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.4f, 35f, 0f, 0f), k(1.5f, 35f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("right_wing", k(0f, 0f, 0f, 0f), k(0.4f, 0f, -70f, 30f), k(1.5f, 0f, -70f, 30f), k(2f, 0f, 0f, 0f)),
            rot("right_wing_tip", k(0f, 0f, 0f, 0f), k(0.4f, 0f, 175f, 0f), k(1.5f, 0f, 175f, 0f), k(2f, 0f, 0f, 0f)),
            rot("left_wing", k(0f, 0f, 0f, 0f), k(0.4f, 0f, 70f, -30f), k(1.5f, 0f, 70f, -30f), k(2f, 0f, 0f, 0f)),
            rot("left_wing_tip", k(0f, 0f, 0f, 0f), k(0.4f, 0f, -175f, 0f), k(1.5f, 0f, -175f, 0f), k(2f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, 0f, 0f, 0f), k(0.4f, -20f, 0f, 60f), k(1.5f, -20f, 0f, 60f), k(2f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, 0f, 0f, 0f), k(0.4f, -20f, 0f, -60f), k(1.5f, -20f, 0f, -60f), k(2f, 0f, 0f, 0f)));

    /** The bite: seizing the victim and sinking the fangs in. */
    static final AnimationDefinition BITE = anim(2.5f, false,
            rot("body", k(0f, 0f, 0f, 0f), k(0.3f, 30f, 0f, 0f), k(2.1f, 30f, 0f, 0f), k(2.5f, 0f, 0f, 0f)),
            rot("head", k(0f, 0f, 0f, 0f), k(0.3f, 25f, -20f, 15f), k(2.1f, 25f, -20f, 15f), k(2.5f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.3f, 35f, 0f, 0f), k(0.6f, 10f, 0f, 0f), k(2.1f, 10f, 0f, 0f), k(2.5f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, 0f, 0f, 0f), k(0.3f, -75f, 0f, -10f), k(2.1f, -75f, 0f, -10f), k(2.5f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, 0f, 0f, 0f), k(0.3f, -75f, 0f, 10f), k(2.1f, -75f, 0f, 10f), k(2.5f, 0f, 0f, 0f)),
            rot("right_wing", k(0f, 0f, 0f, 0f), k(0.3f, 0f, -35f, 15f), k(2.1f, 0f, -35f, 15f), k(2.5f, 0f, 0f, 0f)),
            rot("left_wing", k(0f, 0f, 0f, 0f), k(0.3f, 0f, 35f, -15f), k(2.1f, 0f, 35f, -15f), k(2.5f, 0f, 0f, 0f)));

    /** The change: wings burst open, arms thrown wide, then settle. */
    static final AnimationDefinition TRANSFORM = anim(2f, false,
            rot("body", k(0f, 40f, 0f, 0f), k(0.6f, 40f, 0f, 0f), k(1.0f, -25f, 0f, 0f), k(1.5f, -20f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("head", k(0f, 30f, 0f, 0f), k(0.6f, 30f, 0f, 0f), k(1.0f, -40f, 0f, 0f), k(1.5f, -35f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(1.0f, 35f, 0f, 0f), k(1.5f, 35f, 0f, 0f), k(2f, 0f, 0f, 0f)),
            rot("right_wing", k(0f, 0f, 10f, 0f), k(0.6f, 0f, 10f, 0f), k(1.0f, 0f, -75f, 35f), k(1.5f, 0f, -70f, 30f), k(2f, 0f, 0f, 0f)),
            rot("right_wing_tip", k(0f, 0f, 0f, 0f), k(0.6f, 0f, 0f, 0f), k(1.0f, 0f, 175f, 0f), k(1.5f, 0f, 175f, 0f), k(2f, 0f, 0f, 0f)),
            rot("left_wing", k(0f, 0f, -10f, 0f), k(0.6f, 0f, -10f, 0f), k(1.0f, 0f, 75f, -35f), k(1.5f, 0f, 70f, -30f), k(2f, 0f, 0f, 0f)),
            rot("left_wing_tip", k(0f, 0f, 0f, 0f), k(0.6f, 0f, 0f, 0f), k(1.0f, 0f, -175f, 0f), k(1.5f, 0f, -175f, 0f), k(2f, 0f, 0f, 0f)),
            rot("right_arm", k(0f, -30f, 0f, 0f), k(0.6f, -30f, 0f, 0f), k(1.0f, 10f, 0f, 75f), k(1.5f, 10f, 0f, 70f), k(2f, 0f, 0f, 0f)),
            rot("left_arm", k(0f, -30f, 0f, 0f), k(0.6f, -30f, 0f, 0f), k(1.0f, 10f, 0f, -75f), k(1.5f, 10f, 0f, -70f), k(2f, 0f, 0f, 0f)));

    @Override
    protected AnimationDefinition oneShot(CurseAnims.Anim anim, boolean left) {
        return switch (anim) {
            case ATTACK -> left ? ATTACK_LEFT : ATTACK_RIGHT;
            case CAST -> CAST;
            case HOWL -> ROAR;
            case FEED -> BITE;
            case TRANSFORM -> TRANSFORM;
            default -> null;
        };
    }

    @Override
    protected void loop(T entity, float limbSwing, float limbSwingAmount, float ageInTicks) {
        if (airborne(entity)) {
            looping(HOVER, ageInTicks, 1f);
        } else if (limbSwingAmount > 0.05f) {
            this.animateWalk(WALK, limbSwing, limbSwingAmount, 2.0f, 2.5f);
        } else {
            looping(IDLE, ageInTicks, 1f);
        }
    }
}
