package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * The cloud sprite atlas with a mipmap chain and linear filtering, so a puff a kilometre away stays a soft smudge
 * instead of shimmering (a plain {@code SimpleTexture} has no mipmaps). Levels are box-filtered on load.
 */
final class CloudTexture extends AbstractTexture {
    private static final int LEVELS = 4;
    private final ResourceLocation location;

    CloudTexture(ResourceLocation location) {
        this.location = location;
    }

    @Override
    public void load(ResourceManager manager) throws IOException {
        NativeImage[] mips = new NativeImage[LEVELS + 1];
        try (InputStream in = manager.getResourceOrThrow(location).open()) {
            mips[0] = NativeImage.read(in);
        }
        for (int l = 1; l <= LEVELS; l++) mips[l] = half(mips[l - 1]);
        if (RenderSystem.isOnRenderThreadOrInit()) upload(mips);
        else RenderSystem.recordRenderCall(() -> upload(mips));
    }

    private void upload(NativeImage[] mips) {
        int w = mips[0].getWidth(), h = mips[0].getHeight();
        TextureUtil.prepareImage(getId(), LEVELS, w, h);
        for (int l = 0; l <= LEVELS; l++) {
            mips[l].upload(l, 0, 0, 0, 0, w >> l, h >> l, true, true, true, true);
        }
    }

    /** Averages 2x2 blocks (each channel of the packed ABGR pixels). */
    private static NativeImage half(NativeImage src) {
        int w = Math.max(1, src.getWidth() / 2), h = Math.max(1, src.getHeight() / 2);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = src.getPixelRGBA(x * 2, y * 2), b = src.getPixelRGBA(x * 2 + 1, y * 2);
                int c = src.getPixelRGBA(x * 2, y * 2 + 1), d = src.getPixelRGBA(x * 2 + 1, y * 2 + 1);
                int px = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
                    px |= ((sum + 2) >> 2) << shift;
                }
                out.setPixelRGBA(x, y, px);
            }
        }
        return out;
    }
}
