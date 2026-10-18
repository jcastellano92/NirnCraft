package com.skycraft.creatures.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;

/** Full-bright eyes (the blue glow of draugr eyes in a dark barrow). */
public class GlowEyesLayer<T extends net.minecraft.client.renderer.entity.state.EntityRenderState, M extends EntityModel<T>> extends EyesLayer<T, M> {
    private final RenderType type;

    public GlowEyesLayer(RenderLayerParent<T, M> parent, Identifier texture) {
        super(parent);
        this.type = net.minecraft.client.renderer.rendertype.RenderTypes.eyes(texture);
    }

    @Override
    public RenderType renderType() {
        return type;
    }
}
