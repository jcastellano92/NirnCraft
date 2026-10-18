package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.platform.GlStateManager;
import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

/** Four 3D discharge packets, 512 bytes/frame during flashes. No weather-grid quantization. */
final class LightningFieldTexture {
    private static final ResourceLocation LOCATION = new ResourceLocation(Skycraft.MODID, "textures/environment/lightning_sources.png");
    private static NativeImage image;
    private static int uploadedId = -1;
    private static boolean active;
    private LightningFieldTexture() {}

    static void renderUpdate(float partialTick) {
        if (image == null) image = new NativeImage(32, 4, true);
        var mc = Minecraft.getInstance();
        var manager = mc.getTextureManager();
        // Preserve the resource texture object: Oculus can already hold its ID when
        // the first world frame arrives. Replacing it with DynamicTexture leaves a
        // stale custom-sampler binding until the next shader reload.
        var target = manager.getTexture(LOCATION, null);
        if (target == null) { target = new SimpleTexture(LOCATION); manager.register(LOCATION, target); }
        int glId = target.getId();
        boolean newTarget = glId != uploadedId;
        if (newTarget) { TextureUtil.prepareImage(glId, 0, 32, 4); uploadedId = glId; }
        boolean hasFlash = DistantStorms.hasActiveFlash();
        if (!hasFlash && !active && !newTarget) return;
        int count = DistantStorms.writeLightSources(image, mc.gameRenderer.getMainCamera().getPosition(), partialTick);
        image.setPixelRGBA(31, 0, count);
        GlStateManager._bindTexture(glId);
        image.upload(0, 0, 0, 0, 0, 32, 4, true, false, false, false);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        active = count > 0;
    }

    static int rgba(float r, float g, float b, float a) {
        return byteValue(r) | byteValue(g) << 8 | byteValue(b) << 16 | byteValue(a) << 24;
    }
    private static int byteValue(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    static void point(NativeImage target, int column, int row, float x, float y, float z) {
        // Camera-relative metre precision, across the whole 30 km DH lighting range.
        int ix = encode(x), iy = encode(y), iz = encode(z);
        target.setPixelRGBA(column, row, (ix & 255) | (ix >>> 8) << 8 | (iy & 255) << 16 | (iy >>> 8) << 24);
        target.setPixelRGBA(column + 1, row, (iz & 255) | (iz >>> 8) << 8);
    }
    private static int encode(float v) { return Math.max(0, Math.min(65535, Math.round(v + 32768))); }
}
