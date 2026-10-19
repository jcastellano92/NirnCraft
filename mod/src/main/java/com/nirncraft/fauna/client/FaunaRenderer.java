package com.nirncraft.fauna.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nirncraft.client.PoseEntityModel;
import com.nirncraft.client.PoseRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

import java.util.function.Function;

/** Generic renderer of the fauna module: a model, a per-entity texture and a uniform scale. */
public class FaunaRenderer<T extends Mob, M extends PoseEntityModel<T>> extends MobRenderer<T, PoseRenderState, M> {
    private final Function<T, Identifier> texture;
    private final float scale;

    public FaunaRenderer(EntityRendererProvider.Context context, M model, float shadow, float scale, Function<T, Identifier> texture) {
        super(context, model, shadow * scale);
        this.texture = texture;
        this.scale = scale;
    }

    /** Adds a full-bright layer (e.g. the torchbug's glowing abdomen). */
    public FaunaRenderer<T, M> withGlow(Identifier glowTexture) {
        this.addLayer(new GlowLayer<>(this, glowTexture));
        return this;
    }

    @Override public PoseRenderState createRenderState() { return new PoseRenderState(); }
    @Override public Identifier getTextureLocation(PoseRenderState state) { return state.texture; }
    @Override public void extractRenderState(T entity, PoseRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.texture = texture.apply(entity);
        model.extractPose(entity, state, partialTick);
    }

    @Override
    protected void scale(PoseRenderState entity, PoseStack poseStack) {
        if (scale != 1.0f) poseStack.scale(scale, scale, scale);
    }

    /** Fish out of water: wiggle, and flop on the side (like vanilla cod). */
    public static class Fish<T extends Mob, M extends PoseEntityModel<T>> extends FaunaRenderer<T, M> {
        public Fish(EntityRendererProvider.Context context, M model, float shadow, float scale, Function<T, Identifier> texture) {
            super(context, model, shadow, scale, texture);
        }

        @Override
        protected void setupRotations(PoseRenderState entity, PoseStack poseStack, float rotationYaw, float entityScale) {
            super.setupRotations(entity, poseStack, rotationYaw, entityScale);
            poseStack.rotateDegrees(Axis.YP, 4.3f * Mth.sin(0.6f * entity.ageInTicks));
            if (!entity.isInWater) {
                poseStack.translate(0.1f, 0.1f, -0.1f);
                poseStack.rotateDegrees(Axis.ZP, 90.0f);
            }
        }
    }

    /** A full-bright emissive layer. */
    public static class GlowLayer<T extends Mob, M extends PoseEntityModel<T>> extends EyesLayer<PoseRenderState, M> {
        private final RenderType type;

        public GlowLayer(RenderLayerParent<PoseRenderState, M> parent, Identifier texture) {
            super(parent);
            this.type = RenderTypes.eyes(texture);
        }

        @Override
        public RenderType renderType() {
            return type;
        }
    }
}
