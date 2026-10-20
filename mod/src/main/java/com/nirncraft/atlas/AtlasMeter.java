package com.nirncraft.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * The eye's light meter and its adaptation: the finished frame (sky, sun, terrain, water, entities) metered on the GPU
 * (core/atlas_meter: a 32 x 18 grid of centre-weighted log luminances), read back without stalling (a ring of three
 * buffers, a frame or two late), and the exposure adapted in log space the way eyes do: towards brighter fast (stepping
 * out of a cave the world is blown out for a moment), towards darker slowly with a long tail (in a dark forest the eyes
 * open over many seconds until the moon's beams and the faint stars come out). The target is mid grey in the light,
 * less in the dark (eyes never adapt the night into day), within the eye's range (staring at the sun stops it right
 * down; a cave opens it far up). Replaces AtlasLook's canonical exposure once the first reading is in.
 */
final class AtlasMeter {
    private AtlasMeter() {}

    static final int W = 32, H = 18;
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("Frame", UniformType.COMBINED_IMAGE_SAMPLER).build();
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("nirncraft", "pipeline/atlas_meter"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("nirncraft", "core/atlas_meter"))
            .withBindGroupLayout(LAYOUT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    // the eye's range (log2 exposure) and its speeds (seconds to close most of the gap)
    static final float MIN = -4.5f, MAX = 5.5f, TAU_BRIGHTER = 0.35f, TAU_DARKER = 3.0f, TAU_DARKER_TAIL = 14f;

    private static TextureTarget grid;
    private static final GpuBuffer[] ring = new GpuBuffer[3];
    private static final boolean[] busy = new boolean[3];
    private static int next;
    private static volatile float sceneLog = Float.NaN;  // the metered scene's log2 luminance before exposure
    private static volatile float measuredAt = Float.NaN;  // the exposure the reading was taken under
    private static float exposure = Float.NaN;  // log2, adapted
    private static long lastNanos;

    /** End of the level's frame: meter the main target (vanilla's, the frame as finished). */
    static void meter(float exposureNow) {
        Minecraft mc = Minecraft.getInstance();
        var main = mc.gameRenderer.mainRenderTarget();
        if (main == null || main.getColorTexture() == null) return;
        var device = RenderSystem.getDevice();
        if (grid == null) grid = new TextureTarget("atlas meter", W, H, GpuFormat.RGBA32_FLOAT, null);
        int slot = next;
        if (busy[slot]) return;  // (the GPU is three frames behind: skip a reading rather than wait)
        if (ring[slot] == null)
            ring[slot] = device.createBuffer(() -> "atlas meter readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                    (long) W * H * 16);
        var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        var encoder = device.createCommandEncoder();
        try (var pass = encoder.createRenderPass(() -> "Infinite ATLAS light meter", grid.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setUniform("Frame", main.getColorTextureView(), linear);
            pass.draw(3, 1, 0, 0);
        }
        busy[slot] = true;
        final float under = exposureNow;
        GpuBuffer buf = ring[slot];
        encoder.copyTextureToBuffer(grid.getColorTexture(), buf, 0L, () -> {
            try (var view = buf.slice(0, (long) W * H * 16).map(true, false)) {
                ByteBuffer b = view.data().order(ByteOrder.nativeOrder());
                double sum = 0, wsum = 0;
                for (int i = 0; i < W * H; i++) {
                    float mean = b.getFloat(i * 16), w = b.getFloat(i * 16 + 8);
                    sum += mean * w;
                    wsum += w;
                }
                if (wsum > 0) {
                    // the frame is displayed (exposed, tonemapped): the scene's luminance is the reading less the
                    // exposure it was taken under (the tonemap's shoulder compresses the brightest: it meters a little low)
                    sceneLog = (float) (sum / wsum) - under;
                    measuredAt = under;
                }
            } finally {
                busy[slot] = false;
            }
        }, 0);
        next = (next + 1) % ring.length;
    }

    /** The adapted log2 exposure, or NaN until the first reading (AtlasLook keeps its canonical one meanwhile). */
    static float exposure(float canonical) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min((now - lastNanos) * 1e-9f, 0.25f);
        lastNanos = now;
        float s = sceneLog;
        if (Float.isNaN(s)) return Float.NaN;
        // the display's target for this scene: mid grey (0.18) in daylight, darker in the dark (a moonlit scene
        // stays a night: the eye opens but does not turn it into day); s is log2 of the scene's mean luminance
        float dayness = smooth(-9f, -1.5f, s);
        float target = log2(0.03f) + (log2(0.18f) - log2(0.03f)) * dayness;
        float want = clamp(target - s, MIN, MAX);
        if (Float.isNaN(exposure)) exposure = Float.isNaN(canonical) ? want : canonical;
        float gap = want - exposure;
        float tau = gap < 0 ? TAU_BRIGHTER : (gap > 1.5f ? TAU_DARKER_TAIL : TAU_DARKER);  // darker: fast first, then the tail
        exposure += gap * (1f - (float) Math.exp(-dt / tau));
        return exposure;
    }

    private static float log2(float x) {
        return (float) (Math.log(x) / Math.log(2));
    }

    private static float smooth(float a, float b, float x) {
        float t = clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float x, float a, float b) {
        return Math.max(a, Math.min(b, x));
    }
}
