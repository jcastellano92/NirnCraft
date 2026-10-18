package com.skycraft.render.quality;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.skycraft.Skycraft;
import com.skycraft.render.client.DataTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The shader pack's adaptive-quality knobs ({@code customTexture.qualityKnobs}, shaders/lib/quality.glsl): a 16 x 1
 * RGBA8 resource texture updated in place (Iris holds the texture object from pipeline creation), r in the low byte.
 * Texel 0 is the header (165, layout 1, overall level, 255); texels 1..9 the knobs (layout in quality.glsl). The
 * bundled quality.png is blank: until this writes it (no mod, an older build) the pack runs its fallback counts.
 * Render thread only, outside render passes (ClientTickEvent.Post).
 */
final class QualityTexture {
    static final Identifier LOCATION = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/environment/quality.png");
    static final int MAGIC = 165, LAYOUT = 1;

    private final NativeImage image = new NativeImage(NativeImage.Format.RGBA, 16, 1, true);
    private final byte[][] bytes = new byte[16][4];
    private final byte[][] written = new byte[16][4];
    private GpuTexture target;
    private boolean failed;

    /** Fills the texels for knobs q (Knob order) and the overall level; true when they differ from what is on the GPU. */
    boolean fill(double[] q, double level) {
        for (byte[] t : bytes) java.util.Arrays.fill(t, (byte) 0);
        bytes[0][0] = (byte) MAGIC;
        bytes[0][1] = (byte) LAYOUT;
        bytes[0][2] = b(level);
        bytes[0][3] = (byte) 255;
        for (Knob k : Knob.ALL) {
            if (k.owner != Knob.Owner.PACK) continue;
            double v = q[k.ordinal()];
            switch (k) {
                case SHADOW_TAPS -> {
                    bytes[1][0] = b(v);
                    bytes[1][1] = b(v);  // the penumbra narrows with the taps (fewer taps: less visible noise)
                }
                case WAVES -> {
                    bytes[5][0] = b(v);
                    bytes[5][1] = b(v);  // and the waves' LOD distance with their count
                }
                case WATER_EXTRAS -> {
                    bytes[5][2] = b(v);  // caustics
                    bytes[5][3] = b(v);  // falls spray
                }
                case FAR_DETAIL -> {
                    bytes[9][0] = b(v);  // where the falloff starts
                    bytes[9][1] = b(v);  // and how far it falls
                }
                default -> bytes[k.texel][k.channel] = b(v);
            }
        }
        for (int t = 0; t < 16; t++) if (!java.util.Arrays.equals(bytes[t], written[t])) return true;
        return !DataTexture.current(LOCATION, target);  // a resource reload brought the blank back
    }

    /** Uploads the filled texels. False when the texture is not there (yet) or the upload failed. */
    boolean upload() {
        if (failed) return false;
        try {
            Minecraft.getInstance().getTextureManager().getTexture(LOCATION);  // loads (registers) it on first use
            for (int t = 0; t < 16; t++) {
                byte[] c = bytes[t];
                image.setPixelABGR(t, 0, (c[0] & 255) | (c[1] & 255) << 8 | (c[2] & 255) << 16 | (c[3] & 255) << 24);
            }
            target = DataTexture.write(LOCATION, image);
            for (int t = 0; t < 16; t++) System.arraycopy(bytes[t], 0, written[t], 0, 4);
            return true;
        } catch (RuntimeException e) {
            failed = true;  // the pack keeps its fallback counts; the far field still adapts
            Skycraft.LOGGER.warn("adaptive quality: the pack's knob texture is unavailable: {}", e.toString());
            return false;
        }
    }

    private static byte b(double v) {
        return (byte) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }
}
