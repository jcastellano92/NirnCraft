package com.nirncraft.sailing.client;

import com.nirncraft.core.HudStack;
import com.nirncraft.sailing.ShipEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The sailing panel shown while aboard a ship (bottom right): a wind rose with the ship's head up and the wind
 * arrow, the point of sail, the sail setting with its keys, the hull and the speed.
 */
public final class SailingHud {
    private static final int BG = 0x9A0E0B08, EDGE = 0xC0806A48, TEXT = 0xE8DFC8, DIM = 0xA09880;

    private SailingHud() {}

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || VoyageOverlay.active()) return;
        if (!(mc.player.getVehicle() instanceof ShipEntity ship)) return;
        Font font = mc.font;
        int w = 168, h = 58;
        // the top of the bottom-right column (voice words, spell and carry weight stack below it)
        int x = width - w - 6, y = HudStack.reserve(HudStack.Zone.BOTTOM_RIGHT, HudStack.BR_SHIP, h, height);
        g.fill(x, y, x + w, y + h, BG);
        g.fill(x, y, x + w, y + 1, EDGE);
        g.fill(x, y + h - 1, x + w, y + h, EDGE);

        // wind rose
        int cx = x + 26, cy = y + h / 2;
        int r = 20;
        for (int i = 0; i < 24; i++) {
            float a = i * Mth.TWO_PI / 24;
            int px = cx + Math.round(Mth.sin(a) * r), py = cy - Math.round(Mth.cos(a) * r);
            g.fill(px, py, px + 1, py + 1, i % 6 == 0 ? 0xFFE8DFC8 : 0x80A09880);
        }
        // the ship's hull pointing up
        g.fill(cx - 1, cy - 9, cx + 2, cy + 8, 0xFF8A6A40);
        g.fill(cx - 2, cy - 5, cx + 3, cy + 6, 0xFF8A6A40);
        float yaw = Mth.rotLerp(partial, ship.yRotO, ship.getYRot());
        float rel = Mth.wrapDegrees(ship.getWindDir() - yaw) * Mth.DEG_TO_RAD;
        float wind = ship.getWind();
        float dx = Mth.sin(rel), dy = -Mth.cos(rel);
        int len = (int) (8 + 10 * Math.min(1f, wind));
        // the arrow flows with the wind: from upwind through the centre
        for (int k = -len; k <= len; k++) {
            int px = cx + Math.round(dx * k), py = cy + Math.round(dy * k);
            g.fill(px, py, px + 2, py + 2, 0xFF8FC4E8);
        }
        int hx = cx + Math.round(dx * len), hy = cy + Math.round(dy * len);
        g.fill(hx - 2, hy - 2, hx + 3, hy + 3, 0xFFCFEAFF);

        int tx = x + 52;
        int ty = y + 6;
        float off = Math.abs(Mth.wrapDegrees(ship.getWindDir() - yaw));
        String pointKey = !ship.shipType.hasSail() ? "sailing.nirncraft.hud.oars"
                : off < 35 ? "sailing.nirncraft.hud.running" : off < 80 ? "sailing.nirncraft.hud.broad_reach"
                : off < 110 ? "sailing.nirncraft.hud.beam_reach" : off < ship.shipType.upwindLimit ? "sailing.nirncraft.hud.close_hauled"
                : "sailing.nirncraft.hud.in_irons";
        g.text(font, Component.translatable(pointKey), tx, ty, TEXT, false);
        ty += 11;
        if (ship.shipType.hasSail()) {
            Component sail = Component.translatable("sailing.nirncraft.hud.sail", Component.translatable("sailing.nirncraft.sail." + ship.getSail()),
                    SailingClient.SAIL_UP.getTranslatedKeyMessage(), SailingClient.SAIL_DOWN.getTranslatedKeyMessage());
            g.text(font, sail, tx, ty, DIM, false);
        }
        ty += 11;
        // hull bar
        float hull = ship.getHull() / ship.shipType.maxHull;
        g.text(font, Component.translatable("sailing.nirncraft.hud.hull"), tx, ty, DIM, false);
        int bx = tx + 28, bw = w - (bx - x) - 6;
        g.fill(bx, ty + 2, bx + bw, ty + 7, 0xFF2A1A12);
        int col = hull > 0.5f ? 0xFFB08A50 : hull > 0.25f ? 0xFFD07030 : 0xFFE03020;
        g.fill(bx, ty + 2, bx + Math.round(bw * hull), ty + 7, col);
        ty += 11;
        double speed = Math.hypot(ship.getX() - ship.xo, ship.getZ() - ship.zo) * 20;
        g.text(font, Component.translatable("sailing.nirncraft.hud.speed", String.format(java.util.Locale.ROOT, "%.1f", speed)), tx, ty, DIM, false);
    }
}
