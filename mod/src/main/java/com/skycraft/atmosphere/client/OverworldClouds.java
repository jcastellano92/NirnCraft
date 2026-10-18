package com.skycraft.atmosphere.client;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Raises the overworld cloud layer from vanilla's y 192 to {@link AtmosphereConfig#CLOUD_HEIGHT} (default 330), so the
 * clouds no longer slice through the Reach, the Velothi and the Jerall Mountains. It replaces the effects registered
 * for {@code minecraft:overworld} with vanilla's own {@link DimensionSpecialEffects.OverworldEffects} and changes only
 * {@link DimensionSpecialEffects#getCloudHeight()}. Vanilla, Embeddium and Oculus/Iris (the {@code cloudHeight}
 * uniform that Complementary's clouds follow) all read the height from there. It is also Skycraft's cloud hook:
 * {@code renderClouds} hands the pass to {@link CloudRenderer}, which suppresses vanilla's mesh when a shader pack
 * draws volumetric clouds, and declines when Skycraft clouds are off.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OverworldClouds {
    private OverworldClouds() {}

    @SubscribeEvent
    public static void onRegisterEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(BuiltinDimensionTypes.OVERWORLD_EFFECTS, new DimensionSpecialEffects.OverworldEffects() {
            @Override
            public float getCloudHeight() {
                try {
                    return AtmosphereConfig.CLOUD_HEIGHT.get();
                } catch (IllegalStateException notLoadedYet) {
                    return 330.0F;
                }
            }

            @Override
            public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX,
                                        double camY, double camZ, Matrix4f projectionMatrix) {
                return CloudRenderer.render(level, partialTick, poseStack, camX, camY, camZ, projectionMatrix);
            }
        });
    }
}
