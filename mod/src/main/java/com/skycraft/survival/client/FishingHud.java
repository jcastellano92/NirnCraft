package com.skycraft.survival.client;

import com.skycraft.inventory.client.SkyUi;
import com.skycraft.network.SkyNetwork;
import com.skycraft.survival.SurvivalPackets;
import com.skycraft.survival.fishing.SkyFishingHook;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * Stardew-style fishing minigame HUD: when a fish bites, a vertical water column and catch progress bar appear next to
 * the crosshair. Hold Right-Click or Left-Click to thrust the green Catch Bar upward and release to let gravity pull it
 * down; keep the swimming Fish Icon inside the Catch Bar until the Catch Progress meter reaches 100%.
 */
public final class FishingHud {
    private static final int COL_W = 14;
    private static final int COL_H = 104;
    private static final int PROG_W = 5;

    private static boolean active;
    private static int steadyRank;
    private static int rarity = 1;
    private static int window = 400;
    private static int ticksActive;

    private static float barHeight = 0.28f;
    private static float barPos, barPosO;
    private static float barVel;

    private static float fishPos, fishPosO;
    private static float fishVel;
    private static float fishTarget;
    private static int fishMoveTimer;

    private static float progress, progressO;
    private static boolean insideBar;

    private static Component result = Component.empty();
    private static int resultColor;
    private static long resultUntil;

    private FishingHud() {}

    public static void reset() {
        active = false;
        resultUntil = 0;
    }

    public static boolean isActive() {
        return active;
    }

    public static void receive(SurvivalPackets.Bite m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (m.phase() == 1) {
            if (m.zoneEnd() >= 1000) {
                // Minigame disabled in config: auto-complete immediately
                SkyNetwork.sendToServer(new SurvivalPackets.ReelResult(true));
                return;
            }
            active = true;
            steadyRank = Mth.clamp(m.zoneStart(), 0, 5);
            barHeight = Mth.clamp(m.zoneEnd() / 1000f, 0.18f, 0.50f);
            rarity = Mth.clamp(m.periodTicks() / 100, 1, 4);
            window = Math.max(100, m.windowTicks());
            ticksActive = 0;

            barPos = barPosO = 0.12f;
            barVel = 0f;

            fishPos = fishPosO = 0.24f;
            fishVel = 0f;
            fishTarget = 0.55f;
            fishMoveTimer = 16;

            progress = progressO = 0.32f;
            insideBar = true;
            resultUntil = 0;
            return;
        }
        active = false;
        switch (m.result()) {
            case 1 -> {
                result = Component.translatable("fishing.skycraft.caught", Component.translatable(m.fish()));
                resultColor = 0xFFE8C060;
            }
            case 2 -> {
                result = Component.translatable("fishing.skycraft.too_early");
                resultColor = 0xFFD07060;
            }
            case 3 -> {
                result = Component.translatable("fishing.skycraft.too_late");
                resultColor = 0xFFD07060;
            }
            default -> {
                result = Component.translatable("fishing.skycraft.slipped");
                resultColor = 0xFFBDB59E;
            }
        }
        resultUntil = Util.getMillis() + 2500;
    }

    public static void clientTick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.level == null || mc.player.fishing == null || ticksActive > window + 20) {
            active = false;
            return;
        }
        ticksActive++;
        barPosO = barPos;
        fishPosO = fishPos;
        progressO = progress;

        // Swallow discrete click pulses so holding RMB/LMB controls the Catch Bar without swinging or re-casting
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyAttack.consumeClick()) {}
        boolean thrust = mc.options.keyUse.isDown() || mc.options.keyAttack.isDown();

        // Player Catch Bar physics (thrust upward on hold, gravity downward on release, soft bounce at bounds)
        barVel += thrust ? 0.0086f : -0.0068f;
        barVel *= (steadyRank > 0 ? 0.86f : 0.90f);
        barVel = Mth.clamp(barVel, -0.065f, 0.065f);
        barPos += barVel;
        float maxBarPos = Math.max(0.01f, 1.0f - barHeight);
        if (barPos < 0f) {
            barPos = 0f;
            if (barVel < 0f) barVel = -barVel * 0.32f;
        } else if (barPos > maxBarPos) {
            barPos = maxBarPos;
            if (barVel > 0f) barVel = 0f;
        }

        // Fish AI movement along the water column
        if (--fishMoveTimer <= 0) {
            float span = 0.25f + rarity * 0.14f;
            float delta = (mc.level.getRandom().nextFloat() - 0.48f) * span * 2f;
            fishTarget = Mth.clamp(fishPos + delta, 0.06f, 0.94f);
            fishMoveTimer = Math.max(9, 32 - rarity * 5 + mc.level.getRandom().nextInt(16));
        }
        float diff = fishTarget - fishPos;
        float maxFishSpeed = 0.016f + rarity * 0.0065f;
        float accel = Mth.clamp(diff * (0.065f + rarity * 0.012f), -0.0085f, 0.0085f);
        fishVel = Mth.clamp(fishVel * 0.82f + accel, -maxFishSpeed, maxFishSpeed);
        fishPos = Mth.clamp(fishPos + fishVel, 0.04f, 0.96f);

        // Check overlap between Fish Icon and Player Catch Bar
        insideBar = fishPos >= barPos - 0.025f && fishPos <= barPos + barHeight + 0.025f;
        if (insideBar) {
            float gain = 0.0135f * (1.0f + 0.10f * steadyRank);
            progress = Math.min(1.0f, progress + gain);
            if (ticksActive % 5 == 0) {
                mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 0.14f, 1.15f + progress * 0.55f, false);
            }
        } else {
            float decay = 0.0082f * (float) Math.pow(0.75, steadyRank);
            progress = Math.max(0.0f, progress - decay);
        }

        if (progress >= 1.0f) {
            active = false;
            SkyNetwork.sendToServer(new SurvivalPackets.ReelResult(true));
        } else if (progress <= 0.0f) {
            active = false;
            SkyNetwork.sendToServer(new SurvivalPackets.ReelResult(false));
        }
    }

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) return;
        Font font = mc.font;
        int cx = width / 2;
        int y = height / 2 + 34;
        long nowMs = Util.getMillis();

        if (active && mc.player.fishing != null) {
            float a = Mth.clamp((ticksActive + partialTick) / 4f, 0f, 1f);
            int ia = (int) (a * 255) << 24;

            int colX = cx + 26;
            int colY = height / 2 - COL_H / 2;

            // Outer ornate frame & deep water column background
            g.fill(colX - 2, colY - 2, colX + COL_W + 2 + PROG_W + 3, colY + COL_H + 2, ((int) (a * 210) << 24) | 0x12161A);
            g.fill(colX - 1, colY - 1, colX + COL_W + 1, colY + COL_H + 1, ia | 0x5E5238);
            g.fill(colX, colY, colX + COL_W, colY + COL_H, ((int) (a * 225) << 24) | 0x0E2436);

            // Subtle water depth segments
            for (int i = 1; i < 4; i++) {
                int ly = colY + (COL_H * i) / 4;
                g.fill(colX + 2, ly, colX + COL_W - 2, ly + 1, ((int) (a * 70) << 24) | 0x4A88B0);
            }

            // Player Catch Bar (mapped bottom-up from colY + COL_H)
            float interpBar = Mth.lerp(partialTick, barPosO, barPos);
            int barPixH = Math.max(12, Math.round(barHeight * COL_H));
            int barBottom = colY + COL_H - Math.round(interpBar * COL_H);
            int barTop = Math.max(colY, barBottom - barPixH);
            barBottom = Math.min(colY + COL_H, barTop + barPixH);

            int barFill = insideBar ? (((int) (a * 200) << 24) | 0x48C864) : (((int) (a * 145) << 24) | 0x7A8C54);
            int barBorder = insideBar ? (ia | 0xA8F8A0) : (ia | 0xB8C488);
            g.fill(colX + 1, barTop, colX + COL_W - 1, barBottom, barFill);
            g.fill(colX + 1, barTop, colX + COL_W - 1, barTop + 1, barBorder);
            g.fill(colX + 1, barBottom - 1, colX + COL_W - 1, barBottom, barBorder);
            g.fill(colX + 1, barTop, colX + 2, barBottom, barBorder);
            g.fill(colX + COL_W - 2, barTop, colX + COL_W - 1, barBottom, barBorder);

            // Swimming Fish Icon marker inside the column
            float interpFish = Mth.lerp(partialTick, fishPosO, fishPos);
            int fishY = Mth.clamp(colY + COL_H - Math.round(interpFish * COL_H), colY + 4, colY + COL_H - 4);
            int wobble = insideBar ? 0 : Math.round(Mth.sin((ticksActive + partialTick) * 0.9f) * 1.2f);
            int fx = colX + COL_W / 2 + wobble;
            int fishColor = insideBar ? (ia | 0xFFE878) : (ia | 0xE0A850);
            // Fish body + tail silhouette
            g.fill(fx - 3, fishY - 2, fx + 2, fishY + 2, fishColor);
            g.fill(fx - 2, fishY - 3, fx + 1, fishY + 3, fishColor);
            g.fill(fx + 2, fishY - 2, fx + 4, fishY - 1, fishColor);
            g.fill(fx + 2, fishY + 1, fx + 4, fishY + 2, fishColor);
            g.fill(fx - 2, fishY - 1, fx - 1, fishY, ia | 0x182028);

            // Right-side Catch Progress Bar (0%..100%, shifts red -> gold -> bright green)
            int progX = colX + COL_W + 2;
            g.fill(progX - 1, colY - 1, progX + PROG_W + 1, colY + COL_H + 1, ia | 0x5E5238);
            g.fill(progX, colY, progX + PROG_W, colY + COL_H, ((int) (a * 225) << 24) | 0x14181C);
            float interpProg = Mth.clamp(Mth.lerp(partialTick, progressO, progress), 0f, 1f);
            int fillH = Math.round(interpProg * COL_H);
            if (fillH > 0) {
                int rCol = interpProg < 0.5f ? 225 : Math.round(225 * (1f - (interpProg - 0.5f) * 1.6f));
                int gCol = interpProg < 0.5f ? Math.round(70 + interpProg * 310f) : 225;
                int bCol = 65;
                int progColor = ia | (Mth.clamp(rCol, 40, 240) << 16) | (Mth.clamp(gCol, 60, 240) << 8) | bCol;
                g.fill(progX, colY + COL_H - fillH, progX + PROG_W, colY + COL_H, progColor);
                g.fill(progX, colY + COL_H - fillH, progX + PROG_W, colY + COL_H - fillH + 1, ia | 0xFFFFFF);
            }

            SkyUi.scaledCentered(g, font, SkyUi.caps(Component.translatable("fishing.skycraft.reel_in")),
                    colX + (COL_W + PROG_W) / 2, colY - 11, 0.85f, SkyUi.fade(0xFFF5EBC8, a), true);
            return;
        }

        if (nowMs < resultUntil) {
            float a = Mth.clamp((resultUntil - nowMs) / 500f, 0f, 1f);
            SkyUi.scaledCentered(g, font, result.getString(), cx, y - 6, 1.0f, SkyUi.fade(resultColor, a), true);
            return;
        }
        if (mc.player.fishing instanceof SkyFishingHook hook && hook.isInWater()) {
            SkyUi.scaledCentered(g, font, Component.translatable("fishing.skycraft.waiting").getString(), cx, y - 6, 0.8f, 0x90BDB59E, true);
        }
    }
}
