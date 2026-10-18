package com.skycraft.client;

import java.util.List;
import java.util.function.Function;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Retains authored procedural animation while publishing only immutable part poses to rendering. */
public abstract class PoseEntityModel<T extends Entity> extends EntityModel<PoseRenderState> {
    private final List<ModelPart> poseParts;
    protected float attackTime;

    protected PoseEntityModel(ModelPart root) { this(root, RenderTypes::entityCutout); }
    protected PoseEntityModel(ModelPart root, Function<net.minecraft.resources.Identifier, RenderType> type) {
        super(root, type);
        poseParts = root.getAllParts();
    }
    public abstract void setupAnim(T entity, float walk, float speed, float age, float yaw, float pitch);

    public void extractPose(T entity, PoseRenderState state, float partialTick) {
        attackTime = entity instanceof LivingEntity living ? living.getSwingAnimation(partialTick) : 0;
        setupAnim(entity, state.walkAnimationPos, state.walkAnimationSpeed, state.ageInTicks, state.yRot, state.xRot);
        capturePose(state);
    }
    public void capturePose(PoseRenderState state) {
        int n = poseParts.size();
        state.partPoses = new PartPose[n];
        state.partVisible = new boolean[n];
        state.partSkipDraw = new boolean[n];
        for (int i=0;i<n;i++) {
            ModelPart part = poseParts.get(i);
            state.partPoses[i] = part.storePose();
            state.partVisible[i] = part.visible;
            state.partSkipDraw[i] = part.skipDraw;
        }
    }
    @Override public void setupAnim(PoseRenderState state) {
        if (state.partPoses == null) return;
        for (int i=0;i<poseParts.size();i++) {
            ModelPart part = poseParts.get(i);
            part.loadPose(state.partPoses[i]);
            part.visible = state.partVisible[i];
            part.skipDraw = state.partSkipDraw[i];
        }
    }
}
