package com.skycraft.aether;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * Materials for aether. The exact (generator-backed) column source writes block palette ids, one per registered
 * block (registry id + 1), coloured by the block's map colour, which is what a block "looks like" from far away.
 * The seed-free preview source keeps aether's built-in preview palette instead.
 */
final class AetherPalette {
    private AetherPalette() {}

    /** Palette id of a block state for the exact source: registry id + 1 (0 is air). */
    static short idOf(BlockState state) {
        return (short) (BuiltInRegistries.BLOCK.getId(state.getBlock()) + 1);
    }

    static float[][] blocks() {
        int n = BuiltInRegistries.BLOCK.size();
        float[][] p = new float[n][];
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState s = b.defaultBlockState();
            int rgb = s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
            float r = lin((rgb >> 16) & 255), g = lin((rgb >> 8) & 255), bl = lin(rgb & 255);
            int cls = AetherNative.CLASS_TERRAIN;
            if (s.getFluidState().is(Fluids.WATER) || s.getFluidState().is(Fluids.FLOWING_WATER)) cls = AetherNative.CLASS_WATER;
            else if (s.is(BlockTags.LEAVES)) cls = AetherNative.CLASS_FOLIAGE;
            float emissive = s.getLightEmission() > 0 ? s.getLightEmission() / 15f : 0f;
            if (emissive > 0.5f) cls = AetherNative.CLASS_EMISSIVE;
            p[BuiltInRegistries.BLOCK.getId(b)] = new float[] {r, g, bl, cls == AetherNative.CLASS_WATER ? 0.05f : 0.9f, emissive, cls};
        }
        for (int i = 0; i < n; i++) if (p[i] == null) p[i] = new float[] {0.4f, 0.4f, 0.4f, 0.9f, 0f, 0};
        return p;
    }

    private static float lin(int c) {
        return (float) Math.pow(c / 255.0, 2.2);
    }

    /**
     * The mod's own baked cloud noise (assets/skycraft/textures/environment/cloud_noise_volume.png, 8x8 slices of 66^2
     * with wrap gutters) unpacked to 64^3 RGBA8, the same texels the old GLSL clouds sampled.
     */
    static int[] cloudNoiseVolume() {
        try (InputStream in = AetherPalette.class.getResourceAsStream("/assets/skycraft/textures/environment/cloud_noise_volume.png")) {
            if (in == null) return null;
            BufferedImage atlas = ImageIO.read(in);
            int[] vol = new int[64 * 64 * 64];
            for (int z = 0; z < 64; z++) {
                int x0 = (z % 8) * 66 + 1, y0 = (z / 8) * 66 + 1;
                for (int y = 0; y < 64; y++)
                    for (int x = 0; x < 64; x++) {
                        int argb = atlas.getRGB(x0 + x, y0 + y);
                        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255, a = (argb >>> 24) & 255;
                        vol[(z * 64 + y) * 64 + x] = r | g << 8 | b << 16 | a << 24;
                    }
            }
            return vol;
        } catch (Exception e) {
            return null;
        }
    }
}
