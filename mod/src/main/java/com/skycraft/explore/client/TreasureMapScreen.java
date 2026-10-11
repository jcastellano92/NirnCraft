package com.skycraft.explore.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.explore.TreasureMapItem;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.world.PlaceText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The drawing on a treasure map: a sketch of the land around the cache in ink on parchment (coasts, lakes and
 * rivers, height lines, roads, the nearby named places) with a red X on the spot. Drawn from the Tamriel map
 * itself, so it matches the world the player walks through.
 */
public class TreasureMapScreen extends Screen {
    private static final ResourceLocation TEX = new ResourceLocation(Skycraft.MODID, "explore/treasure_map_sketch");
    private static final int N = 128;
    /** Blocks per sketch pixel. */
    private static final int SCALE = 5;

    private final CompoundTag tag;
    private final Component title;
    private DynamicTexture texture;
    private final List<Place> labels = new ArrayList<>();
    private int x0, z0;

    public TreasureMapScreen(CompoundTag tag, Component title) {
        super(title);
        this.tag = tag;
        this.title = title;
    }

    @Override
    protected void init() {
        if (texture == null) build();
    }

    private void build() {
        int ox = tag.getInt("ox"), oz = tag.getInt("oz");
        x0 = ox - N * SCALE / 2;
        z0 = oz - N * SCALE / 2;
        texture = new DynamicTexture(N, N, false);
        NativeImage img = texture.getPixels();
        if (img == null) return;
        MapSample s = new MapSample();
        boolean[] water = new boolean[N * N];
        int[] band = new int[N * N];
        float[] road = new float[N * N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int wx = x0 + i * SCALE + SCALE / 2, wz = z0 + j * SCALE + SCALE / 2;
                TamrielMap.sample(wx, wz, s);
                water[j * N + i] = s.coastDistance <= 0 || s.inWater();
                band[j * N + i] = (int) Math.floor(s.elevation / 18f);
                road[j * N + i] = s.road;
            }
        }
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int k = j * N + i;
                // parchment, a little blotchy and darker towards the edges
                double grain = hash(i, j) * 14 - 7;
                double edge = Math.min(Math.min(i, N - 1 - i), Math.min(j, N - 1 - j));
                double vignette = edge < 10 ? (10 - edge) * 5 : 0;
                int r = clamp(222 + grain - vignette), g = clamp(200 + grain - vignette * 1.1), b = clamp(152 + grain - vignette * 1.3);
                if (water[k]) {
                    r = clamp(r - 50);
                    g = clamp(g - 30);
                    b = clamp(b - 5);
                    // hatched shading along the shore
                    if ((i + j) % 4 == 0) {
                        r -= 15;
                        g -= 12;
                    }
                }
                boolean shore = false, contour = false;
                if (i > 0 && water[k - 1] != water[k]) shore = true;
                if (j > 0 && water[k - N] != water[k]) shore = true;
                if (!water[k] && ((i > 0 && band[k - 1] != band[k]) || (j > 0 && band[k - N] != band[k]))) contour = true;
                if (shore) {
                    r = 70;
                    g = 52;
                    b = 40;
                } else if (contour) {
                    r = clamp(r - 55);
                    g = clamp(g - 60);
                    b = clamp(b - 55);
                }
                if (!water[k] && road[k] > 0.6f && (i + j) % 3 != 0) {
                    r = 120;
                    g = 72;
                    b = 40;
                }
                img.setPixelRGBA(i, j, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }
        texture.upload();
        minecraft.getTextureManager().register(TEX, texture);
        for (Place p : Places.overlapping(x0, z0, x0 + N * SCALE, z0 + N * SCALE)) {
            if (p.x() >= x0 && p.x() < x0 + N * SCALE && p.z() >= z0 && p.z() < z0 + N * SCALE) labels.add(p);
        }
    }

    private static double hash(int i, int j) {
        long h = i * 0x9E3779B97F4A7C15L ^ j * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        return ((h ^ (h >>> 32)) & 0xFFFF) / 65535.0;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int size = Math.min(256, Math.min(width - 40, height - 70));
        int left = (width - size) / 2, top = (height - size) / 2 + 6;
        // a darker border like a torn edge
        g.fill(left - 4, top - 4, left + size + 4, top + size + 4, 0xFF5A4630);
        g.fill(left - 2, top - 2, left + size + 2, top + size + 2, 0xFFC8AE7C);
        if (texture != null) g.blit(TEX, left, top, 0f, 0f, size, size, size, size);
        float px = size / (float) (N * SCALE);
        // named places as ink dots with names
        for (Place p : labels) {
            int sx = left + (int) ((p.x() - x0) * px), sy = top + (int) ((p.z() - z0) * px);
            g.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF3A2A1E);
            g.drawString(font, PlaceText.plain(p), sx + 4, sy - 4, 0xFF3A2A1E, false);
        }
        // the X
        int tx = left + (int) ((tag.getInt("tx") - x0) * px), ty = top + (int) ((tag.getInt("tz") - z0) * px);
        for (int d = -5; d <= 5; d++) {
            g.fill(tx + d - 1, ty + d - 1, tx + d + 2, ty + d + 2, 0xFFB01818);
            g.fill(tx + d - 1, ty - d - 1, tx + d + 2, ty - d + 2, 0xFFB01818);
        }
        // a compass rose in the corner
        int cx = left + size - 18, cy = top + 18;
        g.drawCenteredString(font, "N", cx, cy - 13, 0xFF3A2A1E);
        g.fill(cx - 1, cy - 6, cx + 1, cy + 6, 0xFF3A2A1E);
        g.fill(cx - 6, cy - 1, cx + 6, cy + 1, 0xFF3A2A1E);
        g.drawCenteredString(font, title, width / 2, top - 18, 0xFFE8D8B0);
        Component near = TreasureMapItem.nearName(tag);
        if (!near.getString().isEmpty()) {
            g.drawCenteredString(font, Component.translatable("explore.skycraft.treasure_map.near", near), width / 2, top + size + 8, 0xFFE8D8B0);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (texture != null) {
            minecraft.getTextureManager().release(TEX);
            texture = null;
        }
    }
}
