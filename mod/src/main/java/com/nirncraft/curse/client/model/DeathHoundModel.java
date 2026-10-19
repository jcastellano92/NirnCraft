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

/** The death hound: a gaunt, long-legged undead dog with a narrow skull, a snapping jaw and a whip tail. Texture 64x32. */
public class DeathHoundModel<T extends LivingEntity> extends BeastModel<T> {
    public DeathHoundModel(ModelPart root) {
        super(root, net.minecraft.client.renderer.rendertype.RenderTypes::entityCutout);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.5f, -3.5f, -7f, 7f, 7f, 14f),
                PartPose.offset(0f, 12.5f, 0f));
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(42, 0).addBox(-3f, -3f, -5f, 6f, 6f, 5f)
                        .texOffs(42, 11).addBox(-1.5f, 0f, -9f, 3f, 3f, 4f)
                        .texOffs(56, 11).addBox(-3f, -5f, -2f, 2f, 2f, 1f)
                        .texOffs(56, 14).addBox(1f, -5f, -2f, 2f, 2f, 1f),
                PartPose.offset(0f, 10.5f, -7f));
        head.addOrReplaceChild("jaw", CubeListBuilder.create()
                        .texOffs(0, 21).addBox(-1.5f, 0f, -4f, 3f, 1f, 4f),
                PartPose.offset(0f, 3f, -5f));
        root.addOrReplaceChild("right_front_leg", CubeListBuilder.create()
                        .texOffs(14, 21).addBox(-1f, 0f, -1f, 2f, 8f, 2f),
                PartPose.offset(-2.5f, 16f, -5f));
        root.addOrReplaceChild("left_front_leg", CubeListBuilder.create()
                        .texOffs(14, 21).addBox(-1f, 0f, -1f, 2f, 8f, 2f),
                PartPose.offset(2.5f, 16f, -5f));
        root.addOrReplaceChild("right_hind_leg", CubeListBuilder.create()
                        .texOffs(14, 21).addBox(-1f, 0f, -1f, 2f, 8f, 2f),
                PartPose.offset(-2.5f, 16f, 5.5f));
        root.addOrReplaceChild("left_hind_leg", CubeListBuilder.create()
                        .texOffs(14, 21).addBox(-1f, 0f, -1f, 2f, 8f, 2f),
                PartPose.offset(2.5f, 16f, 5.5f));
        root.addOrReplaceChild("tail", CubeListBuilder.create()
                        .texOffs(22, 21).addBox(-1f, -1f, 0f, 2f, 2f, 8f),
                PartPose.offsetAndRotation(0f, 10.5f, 7f, -0.6f, 0f, 0f));
        return LayerDefinition.create(mesh, 64, 32);
    }

    static final AnimationDefinition IDLE = anim(2.4f, true,
            pos("body", k(0f, 0f, 0f, 0f), k(1.2f, 0f, 0.3f, 0f), k(2.4f, 0f, 0f, 0f)),
            rot("tail", k(0f, 0f, -10f, 0f), k(1.2f, 0f, 10f, 0f), k(2.4f, 0f, -10f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(1.2f, 12f, 0f, 0f), k(2.4f, 0f, 0f, 0f)));

    static final AnimationDefinition RUN = anim(0.5f, true,
            rot("right_front_leg", k(0f, -45f, 0f, 0f), k(0.25f, 40f, 0f, 0f), k(0.5f, -45f, 0f, 0f)),
            rot("left_front_leg", k(0f, -30f, 0f, 0f), k(0.25f, 45f, 0f, 0f), k(0.5f, -30f, 0f, 0f)),
            rot("right_hind_leg", k(0f, 40f, 0f, 0f), k(0.25f, -40f, 0f, 0f), k(0.5f, 40f, 0f, 0f)),
            rot("left_hind_leg", k(0f, 45f, 0f, 0f), k(0.25f, -30f, 0f, 0f), k(0.5f, 45f, 0f, 0f)),
            rot("body", k(0f, -4f, 0f, 0f), k(0.25f, 4f, 0f, 0f), k(0.5f, -4f, 0f, 0f)),
            pos("body", k(0f, 0f, 0f, 0f), k(0.125f, 0f, 0.6f, 0f), k(0.25f, 0f, 0f, 0f), k(0.375f, 0f, 0.6f, 0f), k(0.5f, 0f, 0f, 0f)),
            rot("tail", k(0f, 20f, 0f, 0f), k(0.25f, 30f, 0f, 0f), k(0.5f, 20f, 0f, 0f)));

    static final AnimationDefinition BITE = anim(0.6f, false,
            rot("head", k(0f, 0f, 0f, 0f), k(0.15f, -20f, 0f, 0f), k(0.3f, 15f, 0f, 0f), k(0.6f, 0f, 0f, 0f)),
            pos("head", k(0f, 0f, 0f, 0f), k(0.3f, 0f, 0f, -2f), k(0.6f, 0f, 0f, 0f)),
            rot("jaw", k(0f, 0f, 0f, 0f), k(0.15f, 40f, 0f, 0f), k(0.3f, 0f, 0f, 0f), k(0.6f, 0f, 0f, 0f)));

    @Override
    protected AnimationDefinition oneShot(CurseAnims.Anim anim, boolean left) {
        return anim == CurseAnims.Anim.ATTACK ? BITE : null;
    }

    @Override
    protected void loop(T entity, float limbSwing, float limbSwingAmount, float ageInTicks) {
        if (limbSwingAmount > 0.05f) this.animateWalk(RUN, limbSwing, limbSwingAmount, 1.5f, 2f);
        else looping(IDLE, ageInTicks, 1f);
    }
}
