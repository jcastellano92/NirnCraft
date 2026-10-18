package com.skycraft.client.hud;

import com.skycraft.client.ClientState;
import com.skycraft.client.SkyKeys;
import com.skycraft.core.HudStack;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.network.CorePackets;
import com.skycraft.network.NotifyKind;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Skyrim-style HUD: Magicka (left), Health (center) and Stamina (right) bars that fade when full,
 * the sneak eye, the skill meter, banners and the message log.
 */
public final class SkyHud {
    private static float healthAlpha, magickaAlpha, staminaAlpha;
    /** Casting without enough magicka: the magicka bar flashes red with the cost marked on it until this time. */
    private static long magickaFlashUntil;
    private static float magickaFlashCost;
    private static long lastFadeMs = Util.getMillis();

    /** Skyrim Character Level XP bar animation state (shown on quest completion and character XP gains). */
    private static int trackedLevel = -1;
    private static float trackedLevelProg = -1f;
    private static float animStartProg = 0f;
    private static float animTargetProg = 0f;
    private static int animDisplayLevel = 1;
    private static long levelXpAnimStartMs = 0L;
    private static long levelXpBarUntilMs = 0L;

    private SkyHud() {}

    // ------------------------------------------------------------------ bars

    public static void renderVitals(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null || !(mc.gameMode != null && mc.gameMode.canHurtPlayer())) return;
        PlayerData data = SkyData.get(mc.player);
        boolean combat = ClientState.has(CorePackets.SyncVitals.IN_COMBAT);

        float health = mc.player.getHealth() / mc.player.getMaxHealth();
        float magicka = data.getMagicka() / Math.max(1, data.maxMagicka());
        float stamina = data.getStamina() / Math.max(1, data.maxStamina());

        long nowMs = Util.getMillis();
        float step = fadeStep(nowMs - lastFadeMs);
        lastFadeMs = nowMs;
        healthAlpha = approach(healthAlpha, combat || health < 0.999f ? 1f : 0f, step);
        boolean magickaFlash = nowMs < magickaFlashUntil;
        magickaAlpha = approach(magickaAlpha, combat || magicka < 0.999f || magickaFlash ? 1f : 0f, step);
        staminaAlpha = approach(staminaAlpha, combat || stamina < 0.999f || mc.player.isSprinting() ? 1f : 0f, step);

        int barW = 110;
        int y = height - 14;
        drawBar(g, width / 2 - 70, height - 31, 140, health, 0xB0262A, 0x5A0E10, healthAlpha, false);
        drawBar(g, 12, y, barW, magicka, 0x2D63C8, 0x0F1F45, magickaAlpha, false);
        if (magickaFlash) {
            // cost preview: the magicka the spell needs, as a bracket around the middle of the bar, blinking red
            float remaining = Mth.clamp((magickaFlashUntil - nowMs) / 900f, 0f, 1f);
            int need = Mth.clamp((int) (barW * magickaFlashCost / Math.max(1, data.maxMagicka())), 3, barW);
            int cx = 12 + barW / 2;
            int col = ((nowMs / 120) % 2 == 0 ? 0xFFFF4040 : 0xFFFFA0A0);
            int a = (int) (Mth.clamp(remaining * 2f, 0f, 1f) * 255) << 24;
            col = a | (col & 0xFFFFFF);
            g.fill(cx - need / 2 - 1, y, cx + need / 2 + 1, y + 1, col);
            g.fill(cx - need / 2 - 1, y + 5, cx + need / 2 + 1, y + 6, col);
            g.fill(cx - need / 2 - 1, y, cx - need / 2, y + 6, col);
            g.fill(cx + need / 2, y, cx + need / 2 + 1, y + 6, col);
        }
        drawBar(g, width - 12 - barW, y, barW, stamina, 0x3F9A3A, 0x13340F, staminaAlpha, ClientState.has(CorePackets.SyncVitals.EXHAUSTED));

        if (data.getPendingLevelUps() > 0 || data.getPerkPoints() > 0) {
            Font font = mc.font;
            Component hint = Component.translatable("hud.skycraft.perks_available", data.getPerkPoints());
            g.text(font, hint, width - font.width(hint) - 6, HudStack.reserve(HudStack.Zone.TOP_RIGHT, HudStack.TR_PERKS, 10, height),
                    0xFFE8D9A0, true);
        }
    }

    /** Casting something you cannot afford: flash the magicka bar and mark {@code cost} on it (magic HUD). */
    public static void flashMagicka(float cost) {
        magickaFlashUntil = Util.getMillis() + 900;
        magickaFlashCost = cost;
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
    private static void drawBar(GuiGraphicsExtractor g, int x, int y, int w, float fill, int color, int dark, float alpha, boolean flash) {
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

    /** The Skyrim sneak eye replaces the crosshair while sneaking ({@link SneakEye}). */
    public static void renderSneakEye(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) return;
        SneakEye.render(g, width / 2, height / 2);
    }

    // ------------------------------------------------------------------ notifications

    public static void renderNotifications(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        HudNotifications.prune();
        Font font = mc.font;
        PlayerData pData = SkyData.get(mc.player);
        long nowMs = Util.getMillis();
        int curLvl = pData.getLevel();
        float curProg = pData.levelProgress();
        if (trackedLevel < 0) {
            trackedLevel = curLvl;
            trackedLevelProg = curProg;
        } else if (curLvl > trackedLevel || (curLvl == trackedLevel && curProg > trackedLevelProg + 0.002f)) {
            animStartProg = curLvl > trackedLevel ? 0f : trackedLevelProg;
            animTargetProg = curProg;
            animDisplayLevel = curLvl;
            levelXpAnimStartMs = nowMs;
            levelXpBarUntilMs = nowMs + 3800L;
            trackedLevel = curLvl;
            trackedLevelProg = curProg;
        } else {
            trackedLevel = curLvl;
            trackedLevelProg = curProg;
        }

        // Top-left message log (below compass bar)
        int my = 32;
        for (HudNotifications.Entry e : HudNotifications.MESSAGES) {
            float age = e.age();
            int alpha = (int) (255 * Mth.clamp((1 - age) * 4, 0, 1));
            if (alpha < 8) continue;
            int color = e.msg().kind() == NotifyKind.CRIME ? 0xE04040 : 0xE8E2D0;
            g.text(font, e.msg().title(), 8, my, alpha << 24 | color, true);
            my += 11;
        }

        HudNotifications.Entry banner = HudNotifications.banner();
        if (banner == null) {
            if (nowMs < levelXpBarUntilMs) {
                renderStandaloneLevelXpBar(g, font, width, nowMs);
            }
            return;
        }
        float age = banner.age();
        float alpha = Mth.clamp(Math.min(age * 6, (1 - age) * 4), 0, 1);
        if (alpha < 0.03f) return;
        int a = (int) (alpha * 255) << 24;
        CorePackets.Notify msg = banner.msg();
        if (com.skycraft.visual.VisualConfig.STYLED_BANNERS.get()) {
            renderBannerStyled(g, font, mc, msg, width, height, alpha, age);
            return;
        }
        switch (msg.kind()) {
            case SKILL_UP -> {
                g.centeredText(font, msg.title(), width / 2, 46, a | 0xF0EAD6);
                var player = mc.player;
                Skill skill = msg.value() >= 0 && msg.value() < Skill.VALUES.length ? Skill.VALUES[msg.value()] : null;
                float prog = skill == null ? 0 : SkyData.get(player).skillProgress(skill);
                drawThinBar(g, width / 2 - 60, 58, 120, prog, a);
            }
            case LEVEL_UP -> {
                drawDivider(g, width / 2, 58, 110, a);
                g.pose().pushMatrix();
                g.pose().translate(width / 2f, 64);
                g.pose().scale(2f, 2f);
                g.centeredText(font, msg.title(), 0, 0, a | 0xF5EBC8);
                g.pose().popMatrix();
                g.centeredText(font, msg.subtitle(), width / 2, 86, a | 0xBDB59E);
                drawDivider(g, width / 2, 98, 110, a);
            }
            case BIG_TITLE -> {
                g.pose().pushMatrix();
                g.pose().translate(width / 2f, height / 2f - 50);
                g.pose().scale(2.2f, 2.2f);
                g.centeredText(font, msg.title(), 0, 0, a | 0xF5EBC8);
                g.pose().popMatrix();
                g.centeredText(font, msg.subtitle(), width / 2, height / 2 - 24, a | 0xD0C8B0);
            }
            default -> {
                // Quest and location banners: "QUEST STARTED" small caps over the name, between two dividers.
                Component header = Component.translatable("notify.skycraft." + msg.kind().name().toLowerCase(java.util.Locale.ROOT));
                drawDivider(g, width / 2, 50, 100, a);
                g.centeredText(font, header, width / 2, 55, a | 0xA89F86);
                g.pose().pushMatrix();
                g.pose().translate(width / 2f, 67);
                g.pose().scale(1.5f, 1.5f);
                g.centeredText(font, msg.title(), 0, 0, a | 0xF5EBC8);
                g.pose().popMatrix();
                if (!msg.subtitle().getString().isEmpty()) g.centeredText(font, msg.subtitle(), width / 2, 84, a | 0xBDB59E);
                drawDivider(g, width / 2, 95, 100, a);
                if (msg.kind() == NotifyKind.QUEST_COMPLETED) {
                    drawAnimatedLevelXpRow(g, font, width / 2, 101, alpha, nowMs, pData);
                }
            }
        }
    }

    /**
     * Skyrim-style banners: each sits on a soft dark band that fades out at the sides, framed by ornaments.
     * Skill increases show the skill's progress bar; level ups and big titles ("DRAGON SOUL ABSORBED") settle in from
     * slightly larger with a faint glow.
     */
    private static void renderBannerStyled(GuiGraphicsExtractor g, Font font, Minecraft mc, CorePackets.Notify msg, int width, int height,
                                           float alpha, float age) {
        int cx = width / 2;
        float settle = 1f + 0.12f * (1f - Mth.clamp(age * 5f, 0f, 1f));
        // stay clear of the boss and enemy bars under the compass
        boolean boss = com.skycraft.client.SkyBossHud.isShowing(null);
        boolean target = TargetHud.isVisible();
        int off = msg.kind() == NotifyKind.BIG_TITLE ? 0 : ((boss || target) ? 28 : 0);
        g.pose().pushMatrix();
        g.pose().translate(0, off);
        renderBannerBody(g, font, mc, msg, width, height, alpha, cx, settle);
        g.pose().popMatrix();
    }

    private static void renderBannerBody(GuiGraphicsExtractor g, Font font, Minecraft mc, CorePackets.Notify msg, int width, int height,
                                         float alpha, int cx, float settle) {
        switch (msg.kind()) {
            case SKILL_UP -> {
                band(g, cx, 42, 78, 125, alpha * 0.55f);
                String title = SkyUi.caps(msg.title());
                SkyUi.scaledCentered(g, font, title, cx, 47, 0.9f, SkyUi.fade(0xFFF0EAD6, alpha), true);
                Skill skill = msg.value() >= 0 && msg.value() < Skill.VALUES.length ? Skill.VALUES[msg.value()] : null;
                float prog = skill == null ? msg.progress() : SkyData.get(mc.player).skillProgress(skill);
                SkyUi.ornateBar(g, cx - 60, 59, 120, 3, prog, 0xD8D0B8, alpha, false, false);
                drawAnimatedLevelXpRow(g, font, cx, 66, alpha, Util.getMillis(), SkyData.get(mc.player));
            }
            case LEVEL_UP -> {
                band(g, cx, 50, 100, 150, alpha * 0.6f);
                SkyUi.ornament(g, cx, 56, 110, SkyUi.fade(0xFFC8BC9A, alpha));
                glowTitle(g, font, SkyUi.caps(msg.title()), cx, 64, 2.0f * settle, alpha, 0xF5EBC8);
                SkyUi.scaledCentered(g, font, msg.subtitle().getString(), cx, 84, 1f, SkyUi.fade(0xFFBDB59E, alpha), true);
                SkyUi.ornament(g, cx, 96, 110, SkyUi.fade(0xFFC8BC9A, alpha));
            }
            case BIG_TITLE -> {
                int y = height / 2 - 54;
                band(g, cx, y - 8, y + 40, 170, alpha * 0.5f);
                glowTitle(g, font, SkyUi.caps(msg.title()), cx, y, 2.2f * settle, alpha, 0xF5EBC8);
                SkyUi.ornament(g, cx, y + 24, 100, SkyUi.fade(0xFFC8BC9A, alpha));
                if (!msg.subtitle().getString().isEmpty()) {
                    SkyUi.scaledCentered(g, font, msg.subtitle().getString(), cx, y + 30, 1f, SkyUi.fade(0xFFD0C8B0, alpha), true);
                }
            }
            default -> {
                // quests and locations: "QUEST STARTED" in small capitals over the name, between two ornaments
                Component header = Component.translatable("notify.skycraft." + msg.kind().name().toLowerCase(java.util.Locale.ROOT));
                boolean failed = msg.kind() == NotifyKind.QUEST_FAILED;
                boolean completed = msg.kind() == NotifyKind.QUEST_COMPLETED;
                band(g, cx, 46, completed ? 114 : 98, 130, alpha * 0.55f);
                SkyUi.ornament(g, cx, 50, 100, SkyUi.fade(0xFFC8BC9A, alpha));
                SkyUi.scaledCentered(g, font, SkyUi.caps(header), cx, 56, 0.75f, SkyUi.fade(failed ? 0xFFD07060 : 0xFFA89F86, alpha), false);
                SkyUi.scaledCentered(g, font, msg.title().getString(), cx, 66, 1.5f, SkyUi.fade(0xFFF5EBC8, alpha), true);
                if (!msg.subtitle().getString().isEmpty()) {
                    SkyUi.scaledCentered(g, font, msg.subtitle().getString(), cx, 81, 0.9f, SkyUi.fade(0xFFBDB59E, alpha), true);
                }
                SkyUi.ornament(g, cx, 94, 100, SkyUi.fade(0xFFC8BC9A, alpha));
                if (completed) {
                    drawAnimatedLevelXpRow(g, font, cx, 100, alpha, Util.getMillis(), SkyData.get(mc.player));
                }
            }
        }
    }

    /** Renders the Skyrim Character Level XP bar when XP is gained outside an active banner (e.g. shared party XP). */
    private static void renderStandaloneLevelXpBar(GuiGraphicsExtractor g, Font font, int width, long nowMs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float rem = (levelXpBarUntilMs - nowMs) / 3800f;
        float alpha = Mth.clamp(Math.min((1f - rem) * 6f, rem * 4f), 0f, 1f);
        if (alpha < 0.03f) return;
        int cx = width / 2;
        boolean boss = com.skycraft.client.SkyBossHud.isShowing(null);
        boolean target = TargetHud.isVisible();
        int y = (boss || target) ? 72 : 44;
        band(g, cx, y - 4, y + 18, 115, alpha * 0.5f);
        drawAnimatedLevelXpRow(g, font, cx, y, alpha, nowMs, SkyData.get(mc.player));
    }

    /** Draws "LEVEL X" and the animated Skyrim Character Level XP bar filling from animStartProg to animTargetProg. */
    private static void drawAnimatedLevelXpRow(GuiGraphicsExtractor g, Font font, int cx, int y, float alpha, long nowMs, PlayerData data) {
        float t = levelXpAnimStartMs > 0 ? Mth.clamp((nowMs - levelXpAnimStartMs) / 1400f, 0f, 1f) : 1f;
        float ease = 1f - (1f - t) * (1f - t);
        float startP = nowMs < levelXpBarUntilMs ? animStartProg : data.levelProgress();
        float endP = nowMs < levelXpBarUntilMs ? animTargetProg : data.levelProgress();
        float shownProg = Mth.lerp(ease, startP, endP);
        int lvl = nowMs < levelXpBarUntilMs ? animDisplayLevel : data.getLevel();
        String label = "LEVEL " + lvl;
        SkyUi.scaledCentered(g, font, label, cx, y, 0.75f, SkyUi.fade(0xFFE8D9A0, alpha), true);
        SkyUi.ornateBar(g, cx - 55, y + 8, 110, 3, shownProg, 0xE2C478, alpha, false, false);
    }

    /** A horizontal dark band from y1 to y2 that fades out over its last third on each side. */
    private static void band(GuiGraphicsExtractor g, int cx, int y1, int y2, int half, float alpha) {
        int solid = half * 2 / 3;
        int mid = (y1 + y2) / 2;
        int steps = 12;
        int fadeW = half - solid;
        for (int i = -1; i < steps; i++) {
            // i = -1 is the solid centre; then columns fading toward each side
            float f = i < 0 ? 1f : 1f - (i + 1) / (float) (steps + 1);
            int ca = (int) (alpha * 0x98 * f) << 24;
            int x0 = i < 0 ? cx - solid : cx + solid + i * fadeW / steps;
            int x1 = i < 0 ? cx + solid : cx + solid + (i + 1) * fadeW / steps;
            // soft top and bottom edges: transparent -> dark -> transparent
            g.fillGradient(x0, y1, x1, mid, 0, ca);
            g.fillGradient(x0, mid, x1, y2, ca, 0);
            if (i >= 0) {
                g.fillGradient(2 * cx - x1, y1, 2 * cx - x0, mid, 0, ca);
                g.fillGradient(2 * cx - x1, mid, 2 * cx - x0, y2, ca, 0);
            }
        }
    }

    /** Large title with a faint warm glow (offset copies) under it. */
    private static void glowTitle(GuiGraphicsExtractor g, Font font, String text, int cx, int y, float scale, float alpha, int rgb) {
        int glow = SkyUi.fade(0x40E8C060, alpha);
        for (int i = 0; i < 4; i++) {
            float ox = (i & 1) == 0 ? -0.8f : 0.8f;
            float oy = (i & 2) == 0 ? -0.8f : 0.8f;
            SkyUi.scaledCentered(g, font, text, cx + ox, y + oy, scale, glow, false);
        }
        SkyUi.scaledCentered(g, font, text, cx, y, scale, SkyUi.fade(0xFF000000 | rgb, alpha), true);
    }

    private static void drawThinBar(GuiGraphicsExtractor g, int x, int y, int w, float progress, int a) {
        g.fill(x - 1, y - 1, x + w + 1, y + 4, a | 0x101010);
        g.fill(x, y, x + w, y + 3, a | 0x2A2A2A);
        g.fill(x, y, x + (int) (w * Mth.clamp(progress, 0, 1)), y + 3, a | 0xD8D0B8);
    }

    private static void drawDivider(GuiGraphicsExtractor g, int cx, int y, int half, int a) {
        g.fill(cx - half, y, cx + half, y + 1, a | 0x8A7F66);
        g.fill(cx - 2, y - 2, cx + 3, y + 3, a | 0xC8BC9A);
    }
}
