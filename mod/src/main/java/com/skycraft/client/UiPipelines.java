package com.skycraft.client;

import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/** Explicit blend state for parchment fibres, preserving the original multiplicative map treatment. */
@EventBusSubscriber(modid="skycraft",value=Dist.CLIENT)
public final class UiPipelines {
    public static final RenderPipeline MAP_GRAIN=RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("skycraft","pipeline/map_grain"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("skycraft","core/map_grain"))
        .withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.DST_COLOR,BlendFactor.ZERO)))
        .build();
    @SubscribeEvent public static void register(RegisterRenderPipelinesEvent event) { event.registerPipeline(MAP_GRAIN); }
}
