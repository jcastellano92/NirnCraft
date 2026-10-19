package com.nirncraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * The cloud sprite atlas with a mipmap chain and linear filtering, so a puff a kilometre away stays a soft smudge
 * instead of shimmering (a plain {@code SimpleTexture} has no mipmaps). Levels are box-filtered on load.
 */
final class CloudTexture extends ReloadableTexture {
    private static final int LEVELS = 4;
    private final Identifier location;

    CloudTexture(Identifier location) {
        super(location);
        this.location = location;
    }

    @Override public TextureContents loadContents(ResourceManager manager) throws IOException {
        return TextureContents.load(manager, location);
    }

    @Override public void apply(TextureContents contents) {
        NativeImage[] mips = new NativeImage[LEVELS + 1];
        mips[0] = contents.image();
        try {
            for (int level = 1; level <= LEVELS; level++) mips[level] = half(mips[level - 1]);
            releaseTextures();
            var device = RenderSystem.getDevice();
            texture = device.createTexture(location::toString, 5, GpuFormat.RGBA8_UNORM,
                    mips[0].getWidth(), mips[0].getHeight(), 1, LEVELS + 1);
            textureView = device.createTextureView(texture);
            sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true);
            var encoder = device.createCommandEncoder();
            for (int level = 0; level <= LEVELS; level++) encoder.writeToTexture(texture, mips[level], level, 0, 0, 0);
        } catch (RuntimeException | Error error) {
            releaseTextures();
            throw error;
        } finally {
            for (NativeImage mip : mips) if (mip != null) mip.close();
        }
    }

    /** Averages 2x2 blocks (each channel of the packed ABGR pixels). */
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
