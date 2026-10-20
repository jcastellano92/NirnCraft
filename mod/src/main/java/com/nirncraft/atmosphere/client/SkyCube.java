package com.nirncraft.atmosphere.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nirncraft.render.client.WorldDraw;
import com.nirncraft.render.client.WorldMesh;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Map;

/**
 * The night sky's layers (stars, the galaxy, constellations, moons, the aurora, meteors: SkyRenderer's draws) rendered
 * into a small cube each frame, six 96 x 96 faces side by side in one texture (+X, -X, +Y, -Y, +Z, -Z), over black: the
 * water mirrors the sky off screen with it (near_forward.fsh skyCubeAt), the air's own sky (the sky-view LUT) added
 * under it there. Reverse-Z 90 degree views, as the sky materials depth-test (GREATER_EQUAL, cleared to 0).
 */
public final class SkyCube {
    private SkyCube() {}

    public static final int FACE = 96;
    private static TextureTarget face, cube;
    private static MappableRingBuffer proj;
    private static boolean drawn;

    // world -> each face's view (camera looking along the axis), as JOML lookAlong(dir, up)
    private static final float[][] DIRS = {{1, 0, 0, 0, -1, 0}, {-1, 0, 0, 0, -1, 0}, {0, 1, 0, 0, 0, 1},
            {0, -1, 0, 0, 0, -1}, {0, 0, 1, 0, -1, 0}, {0, 0, -1, 0, -1, 0}};

    /** The cube texture (6 FACE x FACE faces side by side), or null before the first one. */
    public static GpuTextureView view() {
        return cube != null && drawn ? cube.getColorTextureView() : null;
    }

    static void render(List<SkyRenderer.Draw> draws, Map<Identifier, net.minecraft.client.renderer.texture.AbstractTexture> textures,
                       WorldMesh meteor) {
        var device = RenderSystem.getDevice();
        if (face == null) face = new TextureTarget("nirncraft sky cube face", FACE, FACE, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        if (cube == null) cube = new TextureTarget("nirncraft sky cube", FACE * 6, FACE, GpuFormat.RGBA8_UNORM, null);
        if (proj == null) proj = new MappableRingBuffer(() -> "nirncraft sky cube projection", 130, 64);
        // 90 degrees, square, reverse-Z (near -> 1, far -> 0) as the level's projection
        Matrix4f p = new Matrix4f().m22(-1f).m32(1f).mul(new Matrix4f().setPerspective((float) Math.toRadians(90), 1f, 0.05f, 4000f, true));
        try (var v = proj.currentBuffer().map(false, true)) {
            com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(v.data()).putMat4f(p);
        }
        var encoder = device.createCommandEncoder();
        for (int f = 0; f < 6; f++) {
            float[] d = DIRS[f];
            Matrix4f view = new Matrix4f().lookAlong(d[0], d[1], d[2], d[3], d[4], d[5]);
            encoder.clearColorAndDepthTextures(face.getColorTexture(), new Vector4f(0, 0, 0, 0), face.getDepthTexture(), 0.0);
            try (var pass = WorldDraw.open(face)) {
                SkyRenderer.drawAll(pass, draws, view, proj.currentBuffer().slice(0, 64), textures, meteor);
            }
            encoder.copyTextureToTexture(face.getColorTexture(), cube.getColorTexture(), 0, f * FACE, 0, 0, 0, FACE, FACE);
        }
        proj.rotate();
        drawn = true;
    }
}
