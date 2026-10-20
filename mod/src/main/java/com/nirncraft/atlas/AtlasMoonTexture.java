package com.nirncraft.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

/**
 * A moon's baked map (tools/dev/moon_gen.py: albedo or relief, equirectangular; or its tiling detail) with its full mip chain, box-filtered
 * on load: the discs are drawn a few times smaller than their maps (Masser's 4096 texels round the moon), and a plain
 * {@code SimpleTexture} has no mipmaps, so they would shimmer. atlas_moons.glsl picks the level from the pixel's
 * footprint on the sphere.
 */
final class AtlasMoonTexture extends ReloadableTexture {
    private final Identifier location;

    private AtlasMoonTexture(Identifier location) {
        super(location);
        this.location = location;
    }

    private static boolean registered;

    /** Registers the moons' maps with their mips, once (render thread, outside any pass: the first lookup uploads). */
    static void ensure(Minecraft mc, Identifier... maps) {
        if (registered) return;
        registered = true;
        var tm = mc.getTextureManager();
        for (Identifier id : maps) tm.registerAndLoad(id, new AtlasMoonTexture(id));
    }

    /** The sampler the moons' maps are read with: trilinear, clamped (the face towards Nirn never meets the seam). */
    static com.mojang.renderpearl.api.textures.GpuSampler mipmapped() {
        return RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, true);
    }

    /** The detail tiles' sampler: trilinear, repeating (they tile the sphere). */
    static com.mojang.renderpearl.api.textures.GpuSampler mipmappedRepeat() {
        return RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.LINEAR, FilterMode.LINEAR, true);
    }

    @Override public TextureContents loadContents(ResourceManager manager) throws IOException {
        return TextureContents.load(manager, location);
    }

    @Override public void apply(TextureContents contents) {
        NativeImage top = contents.image();
        int levels = 1;
        while ((top.getWidth() >> levels) >= 8 && (top.getHeight() >> levels) >= 4) levels++;
        NativeImage[] mips = new NativeImage[levels];
        mips[0] = top;
        try {
            for (int level = 1; level < levels; level++) mips[level] = half(mips[level - 1]);
            releaseTextures();
            var device = RenderSystem.getDevice();
            texture = device.createTexture(location::toString, 5, GpuFormat.RGBA8_UNORM, top.getWidth(), top.getHeight(),
                    1, levels);
            textureView = device.createTextureView(texture);
            sampler = mipmapped();
            var encoder = device.createCommandEncoder();
            for (int level = 0; level < levels; level++) encoder.writeToTexture(texture, mips[level], level, 0, 0, 0);
        } catch (RuntimeException | Error error) {
            releaseTextures();
            throw error;
        } finally {
            for (NativeImage mip : mips) if (mip != null) mip.close();
        }
    }

    /** Averages 2 x 2 blocks (each channel of the packed ABGR pixels). */
    private static NativeImage half(NativeImage src) {
        int w = Math.max(1, src.getWidth() / 2), h = Math.max(1, src.getHeight() / 2);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = src.getPixel(x * 2, y * 2), b = src.getPixel(x * 2 + 1, y * 2);
                int c = src.getPixel(x * 2, y * 2 + 1), d = src.getPixel(x * 2 + 1, y * 2 + 1);
                int px = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
                    px |= ((sum + 2) >> 2) << shift;
                }
                out.setPixel(x, y, px);
            }
        }
        return out;
    }
}
