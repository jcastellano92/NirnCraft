package com.nirncraft.render.client;

import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Loads every mod texture the InfiniteATLAS pack samples (its shaders.properties customTexture.* entries) once, on the
 * render thread, before any world pipeline exists. Iris resolves custom textures lazily, in the middle of a draw: when
 * a producer had not created one yet (a remote client, before the first weather/smoke/lightning packet) the texture
 * manager loaded the placeholder PNG right there, inside an open render pass, and the client crashed ("Close the
 * existing render pass"). Producers then update these same objects in place ({@link DataTexture}).
 * The atlas targets (nirncraft:atlas/*) are registered by AtlasTargets.
 */
public final class PackTextures {
    /** Keep in step with shaderpack/InfiniteATLAS/shaders/shaders.properties. */
    private static final List<String> ENVIRONMENT = List.of(
            "weather_field", "weather_dynamics", "fog_radiance", "lightning_sources", "local_occlusion",
            "smoke_sources", "sun_horizon", "masser_phases", "secunda_phases", "quality");

    private static boolean loaded;

    private PackTextures() {}

    /** Render thread only, outside any pass (client setup, via Minecraft.execute). */
    public static void preload() {
        if (loaded) return;
        loaded = true;
        var textures = Minecraft.getInstance().getTextureManager();
        for (String name : ENVIRONMENT) {
            Identifier id = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/" + name + ".png");
            try {
                textures.getTexture(id);
            } catch (RuntimeException e) {
                NirnCraft.LOGGER.warn("shader pack texture {} could not be preloaded", id, e);
            }
        }
    }
}
