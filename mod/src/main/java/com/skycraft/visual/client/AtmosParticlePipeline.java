package com.skycraft.visual.client;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.skycraft.Skycraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/** Native reverse-depth testing without atmospheric particles writing into scene depth. */
@EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class AtmosParticlePipeline {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(Skycraft.MODID, "pipeline/atmosphere_particle"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .build();
    static final SingleQuadParticle.Layer LAYER = new SingleQuadParticle.Layer(
            true, TextureAtlas.LOCATION_PARTICLES, PIPELINE, RenderPipelines.OIT_PARTICLE);

    private AtmosParticlePipeline() {}

    @SubscribeEvent
    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }
}
