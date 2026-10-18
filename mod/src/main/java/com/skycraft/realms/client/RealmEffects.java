package com.skycraft.realms.client;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.skycraft.realms.Realm;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.CustomCloudsRenderer;
import net.neoforged.neoforge.client.CustomSkyboxRenderer;
import net.neoforged.neoforge.client.CustomWeatherEffectRenderer;
import org.joml.Matrix4fc;

/** Authored realm skies. These planes intentionally have no Nirn clouds, rain or rain particles. */
public final class RealmEffects implements CustomSkyboxRenderer, CustomCloudsRenderer, CustomWeatherEffectRenderer {
    final Realm realm;
    RealmEffects(Realm realm) { this.realm=realm; }
    @Override public boolean renderSky(LevelRenderState level,SkyRenderState sky,Matrix4fc modelView,GpuBufferSlice fog) {
        var frame=level.getRenderData(RealmsClient.FRAME);
        if(frame!=null && frame.realm()==realm && frame.sky()!=null)frame.sky().render(modelView);
        return true;
    }
    @Override public boolean renderClouds(LevelRenderState level,CloudStatus status,Matrix4fc modelView,RenderPass pass) { return true; }
    @Override public boolean renderCloudsOit(LevelRenderState level,CloudStatus status,Matrix4fc modelView,OitStage stage,GpuTextureView depth,OitRenderPassProvider.Parameters parameters) { return true; }
    @Override public boolean renderSnowAndRain(LevelRenderState level,WeatherRenderState weather,Vec3 camera,RenderPass pass) { return true; }
    @Override public boolean renderSnowAndRainOit(LevelRenderState level,WeatherRenderState weather,Vec3 camera,OitStage stage,RenderPass pass) { return true; }
    @Override public boolean tickRain(ClientLevel level,long ticks,Camera camera) { return true; }
}
