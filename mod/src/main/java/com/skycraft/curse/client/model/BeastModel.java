package com.skycraft.curse.client.model;

import com.skycraft.curse.CurseAnims;
import com.skycraft.curse.client.CurseClient;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.Function;

/**
 * Base of the curse models: keyframe animations ({@link Keys}) driven by {@link CurseAnims} states, which belong to
 * the creature itself or, for a transformed player, to {@link CurseClient}. Subclasses pose the looping motion (idle,
 * walk, run, hover) and list their one-shot animations.
 */
public abstract class BeastModel<T extends LivingEntity> extends HierarchicalModel<T> {
    protected final ModelPart root;
    protected final ModelPart head;

    protected BeastModel(ModelPart root, Function<ResourceLocation, RenderType> renderType) {
        super(renderType);
        this.root = root;
        this.head = find(root, "head");
    }

    private static ModelPart find(ModelPart root, String name) {
        if (root.hasChild(name)) return root.getChild(name);
        if (root.hasChild("body") && root.getChild("body").hasChild(name)) return root.getChild("body").getChild(name);
        return null;
    }

    @Override
    public ModelPart root() {
        return root;
    }

    /** The one-shot animation for a {@link CurseAnims.Anim}, or null if this model has none. */
    protected abstract AnimationDefinition oneShot(CurseAnims.Anim anim, boolean left);

    /** Looping motion: idle breathing, walking or running, hovering. */
    protected abstract void loop(T entity, float limbSwing, float limbSwingAmount, float ageInTicks);

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.root().getAllParts().forEach(ModelPart::resetPose);
        CurseAnims anims = CurseClient.anims(entity);
        loop(entity, limbSwing, limbSwingAmount, ageInTicks);
        if (head != null) {
            head.yRot += Mth.clamp(netHeadYaw, -60f, 60f) * Mth.DEG_TO_RAD;
            head.xRot += Mth.clamp(headPitch, -40f, 50f) * Mth.DEG_TO_RAD;
        }
        if (anims == null) return;
        for (CurseAnims.Anim a : CurseAnims.Anim.VALUES) {
            AnimationDefinition def = oneShot(a, anims.leftSwing);
            if (def != null) this.animate(anims.get(a), def, ageInTicks);
        }
    }

    private static final org.joml.Vector3f CACHE = new org.joml.Vector3f();

    /** Applies a looping animation by the entity's age (no state: the model instance is shared by all entities). */
    protected void looping(AnimationDefinition def, float ageInTicks, float scale) {
        net.minecraft.client.animation.KeyframeAnimations.animate(this, def, (long) (ageInTicks * 50f), scale, CACHE);
    }

    /** Whether the creature hovers (Vampire Lord levitating, a transformed player in the air). */
    protected static boolean airborne(LivingEntity e) {
        if (e instanceof com.skycraft.curse.entity.VampireLordEntity lord) return lord.isLevitating();
        return e instanceof net.minecraft.world.entity.player.Player && !e.onGround() && !e.isInWater();
    }

    /** Running on all fours: a sprinting player, or a creature closing in fast. */
    protected static boolean running(LivingEntity e, float limbSwingAmount) {
        if (e instanceof net.minecraft.world.entity.player.Player) return e.isSprinting();
        return limbSwingAmount > 0.55f;
    }
}
