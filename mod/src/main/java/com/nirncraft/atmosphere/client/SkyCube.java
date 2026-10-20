package com.nirncraft.atmosphere.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4f;

/**
 * The sky's emitters in every direction (the unified sky's: the aurora, the stars, the galaxy, the moons, meteors, the
 * airglow, through the air; com.nirncraft.atlas.AtlasSky draws them each frame with core/atlas_sky_emit.fsh): six
 * FACE x FACE faces side by side in one half-float texture (+X, -X, +Y, -Y, +Z, -Z), linear radiance pre-exposed (rgb
 * x the eye's exposure, alpha that exposure: the reader divides). The water mirrors the sky off screen with it
 * (near_forward.fsh skyCubeAt), the air's own sky (the sky-view LUT) added under it there. A cheaper read than the sky
 * evaluated again per water pixel (the aurora's march, the stars): 55 k texels a frame. Cleared (alpha 0: nothing)
 * until the first draw, so the water reads no layers where the unified sky does not draw.
 */
public final class SkyCube {
    private SkyCube() {}

    public static final int FACE = 96;
    private static TextureTarget cube;
    private static boolean cleared;

    /** The target AtlasSky draws the emitters into. */
    public static TextureTarget target() {
        if (cube == null) cube = new TextureTarget("nirncraft sky cube", FACE * 6, FACE, GpuFormat.RGBA16_FLOAT, null);
        return cube;
    }

    /** AtlasSky drew this frame's emitters. */
    public static void drawn() {
        cleared = true;
    }

    /**
     * The cube texture (6 FACE x FACE faces side by side), or null before AtlasSky first drew it (callers bind a stand-in).
     * Never clears here: callers sit inside render passes (the water's), where no other command may be recorded.
     */
    public static GpuTextureView view() {
        return cube != null && cleared ? cube.getColorTextureView() : null;
    }
}
