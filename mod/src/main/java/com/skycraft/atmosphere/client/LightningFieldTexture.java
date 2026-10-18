package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.render.client.DataTexture;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Four 3D discharge packets, 512 bytes/frame during flashes. No weather-grid quantization. */
final class LightningFieldTexture {
    private static final Identifier LOCATION = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/environment/lightning_sources.png");
    private static NativeImage image;
    private static GpuTexture uploaded;
    private static boolean active;
    private LightningFieldTexture() {}

    static void renderUpdate(float partialTick) {
        try(var uploadState=TextureUploadState.preserve()) {
        if (image == null) image = new NativeImage(32, 4, true);
        var mc = Minecraft.getInstance();
        var manager = mc.getTextureManager();
        var target = DataTexture.target(LOCATION, image);
        boolean newTarget = target != uploaded;
        uploaded = target;
        boolean hasFlash = DistantStorms.hasActiveFlash();
        if (!hasFlash && !active && !newTarget) return;
        int count = DistantStorms.writeLightSources(image, mc.gameRenderer.mainCamera().position(), partialTick);
        image.setPixelABGR(31, 0, count);

        DataTexture.write(LOCATION, image);


        active = count > 0;
        }
    }

    static int rgba(float r, float g, float b, float a) {
        return byteValue(r) | byteValue(g) << 8 | byteValue(b) << 16 | byteValue(a) << 24;
    }
    private static int byteValue(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    static void point(NativeImage target, int column, int row, float x, float y, float z) {
        // Camera-relative metre precision, across the whole 30 km DH lighting range.
        int ix = encode(x), iy = encode(y), iz = encode(z);
        target.setPixelABGR(column, row, (ix & 255) | (ix >>> 8) << 8 | (iy & 255) << 16 | (iy >>> 8) << 24);
        target.setPixelABGR(column + 1, row, (iz & 255) | (iz >>> 8) << 8);
    }
    private static int encode(float v) { return Math.max(0, Math.min(65535, Math.round(v + 32768))); }
}
