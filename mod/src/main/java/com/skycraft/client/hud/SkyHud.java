package com.skycraft.client.hud;

import com.skycraft.client.ClientState;
import com.skycraft.client.SkyKeys;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.network.CorePackets;
import com.skycraft.network.NotifyKind;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * Skyrim-style HUD: Magicka (left), Health (center) and Stamina (right) bars that fade when full,
 * the sneak eye, the skill meter, banners and the message log.
 */
public final class SkyHud {
    private static float healthAlpha, magickaAlpha, staminaAlpha;
    private static long lastFadeMs = Util.getMillis();

    private SkyHud() {}

    // ------------------------------------------------------------------ bars

    public static void renderVitals(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || !gui.shouldDrawSurvivalElements()) return;
        PlayerData data = SkyData.get(mc.player);
        boolean combat = ClientState.has(CorePackets.SyncVitals.IN_COMBAT);

        float health = mc.player.getHealth() / mc.player.getMaxHealth();
        float magicka = data.getMagicka() / Math.max(1, data.maxMagicka());
        float stamina = data.getStamina() / Math.max(1, data.maxStamina());

        long nowMs = Util.getMillis();
        float step = fadeStep(nowMs - lastFadeMs);
        lastFadeMs = nowMs;
        healthAlpha = approach(healthAlpha, combat || health < 0.999f ? 1f : 0f, step);
        magickaAlpha = approach(magickaAlpha, combat || magicka < 0.999f ? 1f : 0f, step);
        staminaAlpha = approach(staminaAlpha, combat || stamina < 0.999f || mc.player.isSprinting() ? 1f : 0f, step);

        int barW = 110;
        int y = height - 14;
        drawBar(g, width / 2 - 70, height - 31, 140, health, 0xB0262A, 0x5A0E10, healthAlpha, false);
        drawBar(g, 12, y, barW, magicka, 0x2D63C8, 0x0F1F45, magickaAlpha, false);
        drawBar(g, width - 12 - barW, y, barW, stamina, 0x3F9A3A, 0x13340F, staminaAlpha, ClientState.has(CorePackets.SyncVitals.EXHAUSTED));

        if (data.getPendingLevelUps() > 0 || data.getPerkPoints() > 0) {
            Font font = mc.font;
            Component hint = Component.translatable("hud.skycraft.perks_available", data.getPerkPoints());
            g.drawString(font, hint, width - font.width(hint) - 6, 6, 0xFFE8D9A0, true);
        }

        // Daily racial power recharge indicator
        com.skycraft.core.Race race = data.getRace();
        if (race != null && !race.power.isEmpty() && mc.level != null) {
            long readyAt = data.getPowerReadyAt();
            long now = mc.level.getGameTime();
            long diff = readyAt - now;
            String text;
            int col;
            if (diff <= 0) {
                text = "[" + SkyKeys.SHOUT.getTranslatedKeyMessage().getString() + "] " + race.powerName().getString();
                col = 0xFFE0C060;
            } else {
                long sec = diff / 20;
                text = race.powerName().getString() + " (" + (sec >= 60 ? (sec / 60) + "m" : sec + "s") + ")";
                col = 0xFFA09888;
            }
            g.drawString(mc.font, text, 12, height - 26, col, true);
        }
    }

    /** Fraction of the remaining distance to cover after {@code elapsedMs}: ~8% per frame at 60 fps, at any frame rate. */
    private static float fadeStep(long elapsedMs) {
        float seconds = Mth.clamp(elapsedMs, 0, 250) / 1000f;
        return 1f - (float) Math.exp(-5.0 * seconds);
    }

    private static float approach(float current, float target, float step) {
        return current + (target - current) * step;
    }

    /** A Skyrim bar: dark frame, fill shrinking toward the center for the health bar, colored fill. */
    private static void drawBar(GuiGraphics g, int x, int y, int w, float fill, int color, int dark, float alpha, boolean flash) {
        if (alpha < 0.02f) return;
        int a = (int) (alpha * 255) << 24;
        int frame = a | 0x1A1A1A;
        int trim = a | 0x8A7F66;
        fill = Mth.clamp(fill, 0, 1);
        g.fill(x - 3, y - 1, x + w + 3, y + 6, frame);
        g.fill(x - 2, y, x + w + 2, y + 1, trim & 0x60FFFFFF | (int) (alpha * 96) << 24);
        g.fill(x, y + 1, x + w, y + 5, a | dark);
        int filled = (int) (w * fill);
        int left = x + (w - filled) / 2;
        int c = color;
        if (flash && (System.currentTimeMillis() / 250) % 2 == 0) c = 0xC0C040;
        g.fill(left, y + 1, left + filled, y + 5, a | c);
        g.fill(left, y + 1, left + filled, y + 2, a | brighten(c));
        // end caps
        g.fill(x - 5, y + 1, x - 3, y + 4, trim);
        g.fill(x + w + 3, y + 1, x + w + 5, y + 4, trim);
    }

    private static int brighten(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + 60);
        int gr = Math.min(255, ((rgb >> 8) & 0xFF) + 60);
        int b = Math.min(255, (rgb & 0xFF) + 60);
        return r << 16 | gr << 8 | b;
    }

    // ------------------------------------------------------------------ sneak eye

    public static void renderSneakEye(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !mc.player.isCrouching()) return;
        boolean detected = ClientState.has(CorePackets.SyncVitals.DETECTED);
        int cx = width / 2;
        int cy = 34;
        int col = detected ? 0xFFE0E0E0 : 0xFF707070;
        // eye outline
        g.fill(cx - 12, cy, cx + 12, cy + 1, col);
        g.fill(cx - 9, cy - 3, cx + 9, cy - 2, col);
        g.fill(cx - 9, cy + 3, cx + 9, cy + 4, col);
        g.fill(cx - 12, cy, cx - 9, cy - 2, col);
        g.fill(cx + 9, cy, cx + 12, cy - 2, col);
        g.fill(cx - 12, cy + 1, cx - 9, cy + 3, col);
        g.fill(cx + 9, cy + 1, cx + 12, cy + 3, col);
        if (detected) g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
        Component label = Component.translatable(detected ? "hud.skycraft.detected" : "hud.skycraft.hidden");
        g.drawCenteredString(mc.font, label, cx, cy + 7, detected ? 0xFFFFFFFF : 0xFF909090);
    }

    // ------------------------------------------------------------------ notifications

    public static void renderNotifications(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        HudNotifications.prune();
        Font font = mc.font;

        // Top-left message log (below compass bar)
        int my = 32;
        for (HudNotifications.Entry e : HudNotifications.MESSAGES) {
            float age = e.age();
            int alpha = (int) (255 * Mth.clamp((1 - age) * 4, 0, 1));
            if (alpha < 8) continue;
            int color = e.msg().kind() == NotifyKind.CRIME ? 0xE04040 : 0xE8E2D0;
            g.drawString(font, e.msg().title(), 8, my, alpha << 24 | color, true);
            my += 11;
        }

        HudNotifications.Entry banner = HudNotifications.banner();
        if (banner == null) return;
        float age = banner.age();
        float alpha = Mth.clamp(Math.min(age * 6, (1 - age) * 4), 0, 1);
        if (alpha < 0.03f) return;
        int a = (int) (alpha * 255) << 24;
        CorePackets.Notify msg = banner.msg();
        switch (msg.kind()) {
            case SKILL_UP -> {
                g.drawCenteredString(font, msg.title(), width / 2, 46, a | 0xF0EAD6);
                var player = mc.player;
                Skill skill = msg.value() >= 0 && msg.value() < Skill.VALUES.length ? Skill.VALUES[msg.value()] : null;
                float prog = skill == null ? 0 : SkyData.get(player).skillProgress(skill);
                drawThinBar(g, width / 2 - 60, 58, 120, prog, a);
            }
            case LEVEL_UP -> {
                drawDivider(g, width / 2, 58, 110, a);
                g.pose().pushPose();
                g.pose().translate(width / 2f, 64, 0);
                g.pose().scale(2f, 2f, 1f);
                g.drawCenteredString(font, msg.title(), 0, 0, a | 0xF5EBC8);
                g.pose().popPose();
                g.drawCenteredString(font, msg.subtitle(), width / 2, 86, a | 0xBDB59E);
                drawDivider(g, width / 2, 98, 110, a);
            }
            case BIG_TITLE -> {
                g.pose().pushPose();
                g.pose().translate(width / 2f, height / 2f - 50, 0);
                g.pose().scale(2.2f, 2.2f, 1f);
                g.drawCenteredString(font, msg.title(), 0, 0, a | 0xF5EBC8);
                g.pose().popPose();
                g.drawCenteredString(font, msg.subtitle(), width / 2, height / 2 - 24, a | 0xD0C8B0);
            }
            default -> {
                // Quest and location banners: "QUEST STARTED" small caps over the name, between two dividers.
                Component header = Component.translatable("notify.skycraft." + msg.kind().name().toLowerCase(java.util.Locale.ROOT));
                drawDivider(g, width / 2, 50, 100, a);
                g.drawCenteredString(font, header, width / 2, 55, a | 0xA89F86);
                g.pose().pushPose();
                g.pose().translate(width / 2f, 67, 0);
                g.pose().scale(1.5f, 1.5f, 1f);
                g.drawCenteredString(font, msg.title(), 0, 0, a | 0xF5EBC8);
                g.pose().popPose();
                if (!msg.subtitle().getString().isEmpty()) g.drawCenteredString(font, msg.subtitle(), width / 2, 84, a | 0xBDB59E);
                drawDivider(g, width / 2, 95, 100, a);
            }
        }
    }

    private static void drawThinBar(GuiGraphics g, int x, int y, int w, float progress, int a) {
        g.fill(x - 1, y - 1, x + w + 1, y + 4, a | 0x101010);
        g.fill(x, y, x + w, y + 3, a | 0x2A2A2A);
        g.fill(x, y, x + (int) (w * Mth.clamp(progress, 0, 1)), y + 3, a | 0xD8D0B8);
    }

    private static void drawDivider(GuiGraphics g, int cx, int y, int half, int a) {
        g.fill(cx - half, y, cx + half, y + 1, a | 0x8A7F66);
        g.fill(cx - 2, y - 2, cx + 3, y + 3, a | 0xC8BC9A);
    }
}
