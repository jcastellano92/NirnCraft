package com.skycraft.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/** The Skyrim compass bar at the top of the screen with cardinal directions and markers. */
public final class Compass {
    private static final String[] DIRS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private Compass() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Font font = mc.font;
        int half = 110;
        int cx = width / 2;
        int y = 8;
        float yaw = Mth.wrapDegrees(mc.player.getViewYRot(partialTick));

        // frame
        g.fillGradient(cx - half, y, cx + half, y + 12, 0x90000000, 0x60000000);
        g.fill(cx - half - 4, y + 5, cx - half, y + 7, 0xFF8A7F66);
        g.fill(cx + half, y + 5, cx + half + 4, y + 7, 0xFF8A7F66);
        g.fill(cx - half, y + 12, cx + half, y + 13, 0x808A7F66);

        // cardinal & intercardinal letters every 45 degrees
        for (int i = 0; i < 8; i++) {
            float rel = Mth.wrapDegrees(i * 45f - yaw);
            if (Math.abs(rel) > 90) continue;
            int x = cx + (int) (rel / 90f * half);
            float fade = 1f - Math.abs(rel) / 95f;
            int a = (int) (fade * 255) << 24;
            boolean major = i % 2 == 0;
            int color = DIRS[i].equals("N") ? 0xE8C060 : major ? 0xF0EAD6 : 0xA8A090;
            g.drawCenteredString(font, DIRS[i], x, y + 2, a | color);
        }
        // tick marks every 15 degrees
        for (int d = 0; d < 360; d += 15) {
            if (d % 45 == 0) continue;
            float rel = Mth.wrapDegrees(d - yaw);
            if (Math.abs(rel) > 90) continue;
            int x = cx + (int) (rel / 90f * half);
            g.fill(x, y + 4, x + 1, y + 8, 0x60FFFFFF);
        }

        // markers
        Vec3 eye = mc.player.position();
        for (CompassMarkers.Marker m : CompassMarkers.deduplicated()) {
            double dx = m.pos().x - eye.x;
            double dz = m.pos().z - eye.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (m.range() > 0 && dist > m.range()) continue;
            float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float rel = Mth.wrapDegrees(bearing - yaw);
            if (Math.abs(rel) > 90) {
                if (m.shape() != CompassMarkers.Shape.QUEST) continue;
                rel = Math.signum(rel) * 90; // quest markers stick to the edge
            }
            int x = cx + (int) (rel / 90f * half);
            drawMarker(g, x, y + 6, m);
            if (Math.abs(rel) < 4 && !m.label().isEmpty()) {
                // Undiscovered locations (LOCATION) show only their icon without a name
                if (m.shape() != CompassMarkers.Shape.LOCATION) {
                    String label = m.label() + "  " + (int) dist + "m";
                    g.drawCenteredString(font, label, cx, y + 16, 0xFFE8E2D0);
                }
            }
        }
    }

    private static final net.minecraft.resources.ResourceLocation ICONS =
            new net.minecraft.resources.ResourceLocation(com.skycraft.Skycraft.MODID, "textures/gui/map_icons.png");

    private static void drawMarker(GuiGraphics g, int x, int y, CompassMarkers.Marker m) {
        int c = 0xFF000000 | m.color();
        if (m.icon() >= 0 && (m.shape() == CompassMarkers.Shape.DISCOVERED || m.shape() == CompassMarkers.Shape.LOCATION)) {
            // Skyrim: discovered places in ink-white, undiscovered ones as dark silhouettes
            float tone = m.shape() == CompassMarkers.Shape.DISCOVERED ? 1f : 0.35f;
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            g.pose().pushPose();
            g.pose().translate(x - 5, y - 6, 0);
            g.pose().scale(0.6875f, 0.6875f, 1f);
            g.setColor(0f, 0f, 0f, 0.6f);
            g.blit(ICONS, 1, 1, (float) ((m.icon() % 8) * 16), (float) ((m.icon() / 8) * 16), 16, 16, 128, 64);
            g.setColor(tone * 1.25f, tone * 1.2f, tone * 1.1f, 1f);
            g.blit(ICONS, 0, 0, (float) ((m.icon() % 8) * 16), (float) ((m.icon() / 8) * 16), 16, 16, 128, 64);
            g.setColor(1f, 1f, 1f, 1f);
            g.pose().popPose();
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            return;
        }
        switch (m.shape()) {
            case QUEST -> {
                // downward arrow
                for (int i = 0; i < 5; i++) g.fill(x - 4 + i, y - 5 + i, x + 5 - i, y - 4 + i, c);
                g.fill(x - 1, y - 8, x + 2, y - 5, c);
            }
            case ENEMY -> g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFD03030);
            case DISCOVERED -> {
                g.fill(x - 3, y - 3, x + 4, y + 4, c);
                g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
                g.fill(x - 1, y - 1, x + 2, y + 2, c);
            }
            default -> {
                g.fill(x - 3, y - 3, x + 4, y + 4, 0x80000000);
                g.fill(x - 2, y - 2, x + 3, y + 3, c);
            }
        }
    }
}
