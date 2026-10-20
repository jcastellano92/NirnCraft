package com.nirncraft.atlas;

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
 * The textures the NirnCraft shader pack samples (customTexture.atlasColor = nirncraft:atlas/color, atlasDepth =
 * nirncraft:atlas/depth): ordinary Minecraft textures registered in the TextureManager, which is how Iris resolves a
 * pack's namespaced custom textures. atlas copies each finished frame into them (glCopyImageSubData, no CPU), so
 * nothing outside atlas ever touches the Vulkan-shared images themselves.
 * <p>Iris resolves a pack's custom textures once, when it loads the pack, and keeps those texture objects: so the three
 * targets are created once, at client setup (before any pack loads), as 1 x 1 placeholders that read "nothing far",
 * and later resizes swap the GPU texture inside the same objects ({@link Target#resize}); Iris reads the current one at
 * every bind. Registering new objects instead left Iris holding an unallocated or closed texture and crashed the
 * first composite pass ("Texture does not exist").</p>
 */
final class AtlasTargets implements AutoCloseable {
    static final Identifier COLOR = Identifier.fromNamespaceAndPath("nirncraft", "atlas/color");
    static final Identifier DEPTH = Identifier.fromNamespaceAndPath("nirncraft", "atlas/depth");
    /** the sun map (RG32F, size x size + 1: occluder along the light, cloud transmittance; last row parameters) */
    static final Identifier SUNMAP = Identifier.fromNamespaceAndPath("nirncraft", "atlas/sunmap");

    private static Target colorTarget, depthTarget, sunTarget;
    private static Target nearLightTarget;  // atlas's light on the near pass's pixels (RGBA16F: irradiance, sun visibility)

    private final int width, height, sunSize;
    private final Target color, depth, sun;
    private final AtlasNative.GlTargets shared;

    private AtlasTargets(int width, int height, int sunSize, Target color, Target depth, Target sun, AtlasNative.GlTargets shared) {
        this.width = width;
        this.height = height;
        this.sunSize = sunSize;
        this.color = color;
        this.depth = depth;
        this.sun = sun;
        this.shared = shared;
    }

    /** sunSize: the sun map's size (0: none; the pack then sees a 1 x 2 map without a valid flag and skips it). */
    static AtlasTargets create(AtlasNative.Context ctx, int width, int height, int sunSize) {
        registerPlaceholders();
        AtlasNative.GlTargets shared = ctx.glAttach(width, height);
        colorTarget.resize(width, height);
        depthTarget.resize(width, height);
        sunTarget.resize(Math.max(1, sunSize), Math.max(2, sunSize + 1));
        nearLightTarget.resize(width, height);
        AtlasTargets t = new AtlasTargets(width, height, sunSize, colorTarget, depthTarget, sunTarget, shared);
        t.clear();
        return t;
    }

    /** Client setup (render thread): the pack's custom textures exist, readable and empty, before Iris loads it. */
    static synchronized void registerPlaceholders() {
        if (colorTarget != null) return;
        if (!RenderSystem.isOnRenderThread()) throw new IllegalStateException("atlas targets: render thread only");
        colorTarget = new Target("atlas color", GpuFormat.RGBA16_FLOAT, 1, 1);
        depthTarget = new Target("atlas depth", GpuFormat.R32_FLOAT, 1, 1);
        sunTarget = new Target("atlas sun map", GpuFormat.RG32_FLOAT, 1, 2);
        nearLightTarget = new Target("atlas near light", GpuFormat.RGBA16_FLOAT, 1, 1);
        var textures = Minecraft.getInstance().getTextureManager();
        textures.register(COLOR, colorTarget);
        textures.register(DEPTH, depthTarget);
        textures.register(SUNMAP, sunTarget);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(colorTarget.getTexture(), new Vector4f(0f, 0f, 0f, 0f));
        encoder.clearColorTexture(depthTarget.getTexture(), new Vector4f(1e30f, 0f, 0f, 0f));
        encoder.clearColorTexture(sunTarget.getTexture(), new Vector4f(0f, 0f, 0f, 0f));
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

    /** The registered textures (what the pack samples; the fallback compositor draws from them). */
    Target colorTarget() {
        return color;
    }

    Target depthTarget() {
        return depth;
    }

    /** atlas's light on the near pass's pixels (AtlasGlIO.dstNearLight; AtlasNearPass's resolve samples it). */
    Target nearLightTarget() {
        return nearLightTarget;
    }

    /** The sun map (the far field's terrain seen from the sun; the resolve's crepuscular rays), and its size. */
    Target sunTarget() {
        return sun;
    }

    int sunMapSize() {
        return sunSize;
    }

    int nearLightId() {
        return nearLightTarget.glId;
    }

    /** Nothing far (another dimension, or no window built yet): coverage 0, so the pack shows the near field alone.
     * Requested from inside the level render (where a pass is open and no encoder work is allowed) and carried out
     * by {@link #flushClear()} between frames. */
    void clear() {
        clearPending = true;
    }

    private static volatile boolean clearPending;

    /** Client tick (render thread, no pass open): apply a requested clear. */
    static void flushClear() {
        if (!clearPending || colorTarget == null) return;
        clearPending = false;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(colorTarget.getTexture(), new Vector4f(0f, 0f, 0f, 0f));
        encoder.clearColorTexture(depthTarget.getTexture(), new Vector4f(1e30f, 0f, 0f, 0f));
    }

    /** Let native code read GL_CLIP_DEPTH_MODE (Renderpearl uses [0,1] depth when glClipControl exists). */
    static int depthZeroToOne() {
        return AtlasNative.GlFrameIO.DEPTH_AUTO;
    }

    /** Back to the empty placeholders (the texture objects stay registered: Iris holds them). */
    @Override
    public void close() {
        color.resize(1, 1);
        depth.resize(1, 1);
        sun.resize(1, 2);  // a fresh 1 x 2 sun map has no valid flag: the pack skips it
        clear();
    }

    /** One registered texture; its GL name is what atlas copies into. */
    static final class Target extends AbstractTexture {
        private final String label;
        private final GpuFormat format;
        private int w, h;
        int glId;

        Target(String label, GpuFormat format, int w, int h) {
            this.label = label;
            this.format = format;
            this.sampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
            allocate(w, h);
        }

        private void allocate(int w, int h) {
            GpuDevice device = RenderSystem.getDevice();
            // render attachment: clearable (clear() in other dimensions)
            this.texture = device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST
                    | GpuTexture.USAGE_RENDER_ATTACHMENT, format, w, h, 1, 1);
            this.textureView = device.createTextureView(this.texture);
            this.glId = glIdOf(this.texture);
            this.w = w;
            this.h = h;
        }

        /** A new size in the same object: the old GPU texture is released, the new one is what Iris binds next. */
        void resize(int nw, int nh) {
            if (nw == w && nh == h) return;
            var oldView = this.textureView;
            var oldTexture = this.texture;
            allocate(nw, nh);
            if (oldView != null) oldView.close();
            if (oldTexture != null) oldTexture.close();
        }
    }

    /** The GL name of a texture on Renderpearl's OpenGL backend (Iris adds iris$getGlId for wrapped ones). */
    static int glIdOf(GpuTexture t) {
        if (t instanceof GlTexture gl) return gl.glId();
        try {
            Method m = t.getClass().getMethod("iris$getGlId");
            return (int) m.invoke(t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("atlas needs the OpenGL backend (texture is " + t.getClass().getName() + ")", e);
        }
    }
}
