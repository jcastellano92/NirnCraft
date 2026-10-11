package com.skycraft.curse.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.curse.client.model.BeastModel;
import com.skycraft.curse.entity.GargoyleEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/** Renderer of the curse creatures: their keyframe-animated model, a size, and eyes that glow in the dark. */
public class BeastRenderer<T extends Mob, M extends BeastModel<T>> extends MobRenderer<T, M> {
    private final ResourceLocation texture;
    private final float scale;

    public BeastRenderer(EntityRendererProvider.Context ctx, M model, ResourceLocation texture, ResourceLocation eyes, float scale, float shadow) {
        super(ctx, model, shadow * scale);
        this.texture = texture;
        this.scale = scale;
        this.addLayer(new Eyes<>(this, RenderType.eyes(eyes)));
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return texture;
    }

    @Override
    protected void scale(T entity, PoseStack pose, float partialTick) {
        pose.scale(scale, scale, scale);
    }

    /** Full-bright eyes (not on a dormant gargoyle: it's only stone until it wakes). */
    static final class Eyes<T extends Mob, M extends BeastModel<T>> extends RenderLayer<T, M> {
        private final RenderType type;

        Eyes(RenderLayerParent<T, M> parent, RenderType type) {
            super(parent);
            this.type = type;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (entity.isInvisible() || entity instanceof GargoyleEntity g && g.isDormant()) return;
            VertexConsumer vc = buffers.getBuffer(type);
            this.getParentModel().renderToBuffer(pose, vc, 0xF00000, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
        }
    }
}
