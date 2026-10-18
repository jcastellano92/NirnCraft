package com.skycraft.curse.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.curse.client.model.BeastModel;
import com.skycraft.curse.entity.GargoyleEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.skycraft.client.PoseRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

/** Renderer of the curse creatures: their keyframe-animated model, a size, and eyes that glow in the dark. */
public class BeastRenderer<T extends Mob, M extends BeastModel<T>> extends MobRenderer<T,PoseRenderState,M> {
    private final Identifier texture;
    private final float scale;

    public BeastRenderer(EntityRendererProvider.Context ctx, M model, Identifier texture, Identifier eyes, float scale, float shadow) {
        super(ctx, model, shadow * scale);
        this.texture = texture;
        this.scale = scale;
        this.addLayer(new Eyes<>(this, RenderTypes.eyes(eyes)));
    }

    @Override
    public Identifier getTextureLocation(PoseRenderState entity) {
        return texture;
    }

    @Override
    protected void scale(PoseRenderState entity, PoseStack pose) {
        pose.scale(scale, scale, scale);
    }

    @Override public PoseRenderState createRenderState() { return new PoseRenderState(); }
    @Override public void extractRenderState(T entity,PoseRenderState state,float partialTick) {
        super.extractRenderState(entity,state,partialTick);
        state.hideBody=entity instanceof GargoyleEntity g && g.isDormant();
        model.extractPose(entity,state,partialTick);
    }

    /** Full-bright eyes (not on a dormant gargoyle: it's only stone until it wakes). */
    static final class Eyes<T extends Mob, M extends BeastModel<T>> extends RenderLayer<PoseRenderState,M> {
        private final RenderType type;

        Eyes(RenderLayerParent<PoseRenderState,M> parent, RenderType type) {
            super(parent);
            this.type = type;
        }

        @Override public void submit(PoseStack pose,SubmitNodeCollector collector,int light,PoseRenderState state,float yaw,float pitch) {
            if (!state.isInvisible && !state.hideBody)
                collector.order(1).submitModel(getParentModel(),state,pose,type,0xf00000,OverlayTexture.NO_OVERLAY,-1,null,state.outlineColor);
        }
    }
}
