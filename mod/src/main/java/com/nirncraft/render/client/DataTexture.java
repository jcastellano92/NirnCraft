package com.nirncraft.render.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Updates the original resource texture in place, retaining Iris custom-sampler identity and native backend ownership. */
public final class DataTexture {
    private DataTexture() {}
    public static GpuTexture target(Identifier location, NativeImage image) {
        var target = Minecraft.getInstance().getTextureManager().getTexture(location).getTexture();
        if (target.getWidth(0) != image.getWidth() || target.getHeight(0) != image.getHeight())
            throw new IllegalStateException("Data texture resource has incompatible dimensions: " + location);
        return target;
    }
    public static GpuTexture write(Identifier location, NativeImage image) {
        var target = target(location, image);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(target, image);
        return target;
    }
    public static void writeRect(Identifier location, NativeImage image, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > image.getWidth() || y + height > image.getHeight())
            throw new IllegalArgumentException("Data texture upload rectangle outside image");
        var target = target(location, image);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var pixels = image.getPixelBytes();
        // Native encoder takes tightly packed source rows. Keep small clock updates small, without a staging allocation.
        if (width == image.getWidth()) {
            encoder.writeToTexture(target, pixels.slice(y * image.getWidth() * 4, width * height * 4), 0, 0, x, y, width, height);
        } else {
            for (int row = 0; row < height; row++)
                encoder.writeToTexture(target, pixels.slice(((y + row) * image.getWidth() + x) * 4, width * 4),
                        0, 0, x, y + row, width, 1);
        }
    }
    public static boolean current(Identifier location, GpuTexture previous) {
        return previous != null && !previous.isClosed()
                && Minecraft.getInstance().getTextureManager().getTexture(location).getTexture() == previous;
    }
}
