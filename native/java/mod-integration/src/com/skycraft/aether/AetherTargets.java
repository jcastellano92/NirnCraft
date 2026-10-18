package com.skycraft.aether;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.lang.reflect.Method;
import java.util.OptionalDouble;

/**
 * The textures the Skycraft shader pack samples (customTexture.aetherColor = skycraft:aether/color, aetherDepth =
 * skycraft:aether/depth): ordinary Minecraft textures registered in the TextureManager, which is how Iris resolves a
 * pack's namespaced custom textures. aether copies each finished frame into them (glCopyImageSubData, no CPU), so
 * nothing outside aether ever touches the Vulkan-shared images themselves.
 */
final class AetherTargets implements AutoCloseable {
    static final Identifier COLOR = Identifier.fromNamespaceAndPath("skycraft", "aether/color");
    static final Identifier DEPTH = Identifier.fromNamespaceAndPath("skycraft", "aether/depth");
    /** the sun map (RG32F, size x size + 1: occluder along the light, cloud transmittance; last row parameters) */
    static final Identifier SUNMAP = Identifier.fromNamespaceAndPath("skycraft", "aether/sunmap");

    private final int width, height, sunSize;
    private final Target color, depth, sun;
    private final AetherNative.GlTargets shared;

    private AetherTargets(int width, int height, int sunSize, Target color, Target depth, Target sun, AetherNative.GlTargets shared) {
        this.width = width;
        this.height = height;
        this.sunSize = sunSize;
        this.color = color;
        this.depth = depth;
        this.sun = sun;
        this.shared = shared;
    }

    /** sunSize: the sun map's size (0: none; the pack then sees a 1 x 2 map without a valid flag and skips it). */
    static AetherTargets create(AetherNative.Context ctx, int width, int height, int sunSize) {
        AetherNative.GlTargets shared = ctx.glAttach(width, height);
        Target color = new Target("aether color", GpuFormat.RGBA16_FLOAT, width, height);
        Target depth = new Target("aether depth", GpuFormat.R32_FLOAT, width, height);
        Target sun = new Target("aether sun map", GpuFormat.RG32_FLOAT, Math.max(1, sunSize), Math.max(2, sunSize + 1));
        Minecraft.getInstance().getTextureManager().register(COLOR, color);
        Minecraft.getInstance().getTextureManager().register(DEPTH, depth);
        Minecraft.getInstance().getTextureManager().register(SUNMAP, sun);
        return new AetherTargets(width, height, sunSize, color, depth, sun, shared);
    }

    int sunSize() {
        return sunSize;
    }

    int sunId() {
        return sunSize > 0 ? sun.glId : 0;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int colorId() {
        return color.glId;
    }

    int depthId() {
        return depth.glId;
    }

    /** Nothing far this frame (another dimension): coverage 0, so the pack's composite shows the near field alone. */
    void clear() {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(color.getTexture(), new Vector4f(0f, 0f, 0f, 0f));
        encoder.clearColorTexture(depth.getTexture(), new Vector4f(1e30f, 0f, 0f, 0f));
    }

    /** Let native code read GL_CLIP_DEPTH_MODE (Renderpearl uses [0,1] depth when glClipControl exists). */
    static int depthZeroToOne() {
        return AetherNative.GlFrameIO.DEPTH_AUTO;
    }

    @Override
    public void close() {
        color.close();
        depth.close();
        sun.close();
    }

    /** One registered texture; its GL name is what aether copies into. */
    static final class Target extends AbstractTexture {
        final int glId;

        Target(String label, GpuFormat format, int w, int h) {
            GpuDevice device = RenderSystem.getDevice();
            // render attachment: clearable (clear() in other dimensions)
            this.texture = device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST
                    | GpuTexture.USAGE_RENDER_ATTACHMENT, format, w, h, 1, 1);
            this.textureView = device.createTextureView(this.texture);
            this.sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST,
                    FilterMode.NEAREST, 1, OptionalDouble.empty());
            this.glId = glIdOf(this.texture);
        }
    }

    /** The GL name of a texture on Renderpearl's OpenGL backend (Iris adds iris$getGlId for wrapped ones). */
    static int glIdOf(GpuTexture t) {
        if (t instanceof GlTexture gl) return gl.glId();
        try {
            Method m = t.getClass().getMethod("iris$getGlId");
            return (int) m.invoke(t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("aether needs the OpenGL backend (texture is " + t.getClass().getName() + ")", e);
        }
    }
}
