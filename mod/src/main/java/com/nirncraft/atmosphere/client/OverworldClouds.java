package com.nirncraft.atmosphere.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nirncraft.NirnCraft;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.CustomCloudsRenderer;
import net.neoforged.neoforge.client.event.RegisterCustomEnvironmentEffectRendererEvent;
import org.joml.Matrix4fc;

/** Native environment attribute hook, selected by the Tamriel overworld dimension data. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class OverworldClouds implements CustomCloudsRenderer {
    @SubscribeEvent public static void register(RegisterCustomEnvironmentEffectRendererEvent event) {
        event.registerCloudRenderer(Identifier.fromNamespaceAndPath(NirnCraft.MODID, "tamriel_clouds"), new OverworldClouds());
    }
    @Override public void prepare(LevelRenderState state, Vec3 position, CloudStatus status, int color,
                                  float height, int range, Matrix4fc modelView) { CloudRenderer.prepare(state); }
    @Override public boolean renderClouds(LevelRenderState state, CloudStatus status, Matrix4fc modelView, RenderPass pass) {
        return CloudRenderer.render(state, modelView, pass, null);
    }
    @Override public boolean renderCloudsOit(LevelRenderState state, CloudStatus status, Matrix4fc modelView,
                                           OitStage stage, GpuTextureView depth, OitRenderPassProvider.Parameters parameters) {
        try (var pass = OitRenderPassProvider.createRenderPass(stage, () -> "NirnCraft regional clouds", parameters)) {
            return CloudRenderer.render(state, modelView, pass, stage);
        }
    }
}
