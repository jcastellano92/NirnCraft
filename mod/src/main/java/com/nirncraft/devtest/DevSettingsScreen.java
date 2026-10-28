package com.nirncraft.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.nirncraft.client.screen.RaceScreen;
import com.nirncraft.client.screen.UiInput;
import com.nirncraft.core.Holds;
import com.nirncraft.crime.Bounty;
import com.nirncraft.inventory.client.SkyUi;
import com.nirncraft.render.quality.Knob;
import com.nirncraft.render.quality.QualityClient;
import com.nirncraft.world.client.MapScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Skyrim-styled Dev &amp; Hardware Quality Profile Suite opened with the {@code -} key.
 *
 * <p>Provides one-click access to the Hardware Quality Profile Ceiling ({@code Steam Deck} up to {@code Max}),
 * Traced vs Fallback Lighting, God/Fly/No-Hands/Quests/Map/Bounty controls, HUD overlay toggles, FPS overlay modes,
 * and smooth Time-of-Day presets. Scales responsively from 427x267 (Steam Deck GUI scale 3) up to 4K.</p>
 */
public class DevSettingsScreen extends Screen {
    private static final int GOOD_GREEN = 0xFF7AD87A;
    private static final int OFF_GRAY = 0xFF7A7468;

    @Nullable
    private final Screen parent;

    private record HitRect(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<HitRect> hitRects = new ArrayList<>();
    private float leftScroll = 0f;
    private float rightScroll = 0f;
    private int maxLeftScroll = 0;
    private int maxRightScroll = 0;

    private boolean compactLayout;
    private int panelX, panelY, panelW, panelH;
    private int colLeftX, colRightX, colW, colTopY, colBottomY;

    public DevSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("NirnCraft Dev & Quality Suite"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        compactLayout = height < 300 || width < 480;
        int marginX = compactLayout ? 8 : 16;
        int marginY = compactLayout ? 6 : 12;
        panelW = Mth.clamp(width - marginX * 2, 360, 680);
        panelH = Mth.clamp(height - marginY * 2, 236, 440);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        int innerPad = compactLayout ? 6 : 10;
        int gap = compactLayout ? 6 : 10;
        colW = (panelW - innerPad * 2 - gap) / 2;
        colLeftX = panelX + innerPad;
        colRightX = colLeftX + colW + gap;
        colTopY = panelY + (compactLayout ? 92 : 102);
        colBottomY = panelY + panelH - (compactLayout ? 18 : 22);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hitRects.clear();

        // Translucent dark backdrop with subtle drifting Skyrim smoke
        g.fillGradient(0, 0, width, height, 0xC8060608, 0xDC100E0C);
        SkyUi.smoke(g, width, height, (net.minecraft.util.Util.getMillis() % 1_000_000L) / 1000f, 0.45f);

        // Outer Skyrim framed panel
        SkyUi.panel(g, panelX, panelY, panelX + panelW, panelY + panelH);
        int cx = panelX + panelW / 2;

        // Title & ornament
        SkyUi.capsCentered(g, font, Component.literal("NIRNCRAFT DEV, QUALITY & TESTING SUITE"), cx, panelY + 5, SkyUi.GOLD);
        if (!compactLayout) {
            SkyUi.ornament(g, cx, panelY + 16, Math.min(110, panelW / 4), 0xB0C8BC9A);
        } else {
            SkyUi.divider(g, cx, panelY + 15, Math.min(120, panelW / 3));
        }

        // Top Navigation / Mode Bar
        int navY = panelY + (compactLayout ? 19 : 22);
        renderTopNavBar(g, mouseX, mouseY, navY);

        // Featured Section: Hardware Quality Profile Ceiling & Lighting Mode
        int featY = navY + (compactLayout ? 17 : 20);
        renderQualityAndLightingSection(g, mouseX, mouseY, featY);

        // Left & Right Columns
        renderLeftColumn(g, mouseX, mouseY);
        renderRightColumn(g, mouseX, mouseY);

        // Bottom hint & Close button
        int pad = compactLayout ? 6 : 10;
        int footerLineY = panelY + panelH - (compactLayout ? 16 : 19);
        SkyUi.hline(g, panelX + pad, panelX + panelW - pad, footerLineY, 0x70);

        int closeW = compactLayout ? 60 : 68;
        int closeH = compactLayout ? 12 : 14;
        int closeX = panelX + panelW - closeW - pad;
        int closeY = footerLineY + 2;

        String footer = "[0-9] Time of Day  |  [-] Cycle Quality  |  [=] Capture  |  [ESC] Return";
        g.text(font, SkyUi.ellipsize(font, footer, panelW - closeW - pad * 2 - 8),
                panelX + pad + 2, footerLineY + (compactLayout ? 4 : 5), SkyUi.DIM, false);

        drawActionPill(g, mouseX, mouseY, closeX, closeY, closeW, closeH, "CLOSE [ESC]", false, SkyUi.TEXT, this::onClose);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void renderTopNavBar(GuiGraphicsExtractor g, int mouseX, int mouseY, int y) {
        int pad = compactLayout ? 6 : 10;
        int availW = panelW - pad * 2;
        int gap = compactLayout ? 4 : 6;
        int count = 4;
        int btnW = (availW - gap * (count - 1)) / count;
        int btnH = compactLayout ? 14 : 15;
        int x = panelX + pad;

        drawActionPill(g, mouseX, mouseY, x, y, btnW, btnH, "CREATIVE CATALOG (E)", false, SkyUi.GOLD, () -> {
            if (minecraft != null) minecraft.gui.setScreen(new SkyrimCreativeScreen(this));
        });
        x += btnW + gap;

        drawActionPill(g, mouseX, mouseY, x, y, btnW, btnH, "CAPTURE SESSION (=)", false, SkyUi.GOLD, () -> {
            DevTestClient.triggerSessionCapture(this);
        });
        x += btnW + gap;

        drawActionPill(g, mouseX, mouseY, x, y, btnW, btnH, "WORLD MAP (M)", false, SkyUi.GOLD, () -> {
            if (minecraft != null) minecraft.gui.setScreen(new MapScreen());
        });
        x += btnW + gap;

        drawActionPill(g, mouseX, mouseY, x, y, btnW, btnH, "RACE MENU (PREVIEW)", false, SkyUi.GOLD, () -> {
            RaceScreen.openForDevPreview(this);
        });
    }

    private void renderQualityAndLightingSection(GuiGraphicsExtractor g, int mouseX, int mouseY, int y) {
        int pad = compactLayout ? 6 : 10;
        int x = panelX + pad;
        int w = panelW - pad * 2;
        int h = compactLayout ? 52 : 56;
        SkyUi.panel(g, x, y, x + w, y + h);

        DevTestState.QualityCeiling current = DevTestState.qualityCeiling;
        String hwTier = QualityClient.qualityName();
        if (hwTier == null || hwTier.isBlank()) hwTier = "NATIVE";

        // Row 1: Quality Ceiling selector (< current >) + Traced Lighting prominent button on right
        int lightingW = Math.min(200, w * 43 / 100);
        int selectorW = w - lightingW - 12;
        int row1Y = y + 3;
        int rowH = compactLayout ? 13 : 14;

        String ceilingPrefix = compactLayout ? "CEILING [-]:" : "QUALITY CEILING [-]:";
        g.text(font, ceilingPrefix, x + 5, row1Y + 3, SkyUi.HEADER, false);
        int labelW = font.width(ceilingPrefix) + 8;
        int stepX = x + labelW;
        int stepW = Math.max(90, selectorW - labelW);

        drawActionPill(g, mouseX, mouseY, stepX, row1Y, 14, rowH, "<", false, SkyUi.GOLD, () -> DevTestState.cycleQualityCeiling(-1));
        drawActionPill(g, mouseX, mouseY, stepX + stepW - 14, row1Y, 14, rowH, ">", false, SkyUi.GOLD, () -> DevTestState.cycleQualityCeiling(1));

        String ceilingTitle = current.label.toUpperCase(Locale.ROOT) + " [" + current.shortTag + "]";
        g.centeredText(font, Component.literal(SkyUi.ellipsize(font, ceilingTitle, stepW - 32)),
                stepX + stepW / 2, row1Y + 3, SkyUi.GOLD);

        // Prominent Traced Lighting Toggle
        int lightX = x + w - lightingW - 5;
        boolean traced = DevTestState.tracedLighting;
        String lightLabel = traced ? "TRACED LIGHTING: ON" : "FALLBACK / CHEAP LIGHTING";
        drawActionPill(g, mouseX, mouseY, lightX, row1Y, lightingW, rowH, lightLabel, traced,
                traced ? GOOD_GREEN : SkyUi.GOLD, DevTestState::toggleTracedLighting);

        // Row 2: All 6 Quality Ceiling tier pills
        int row2Y = y + (compactLayout ? 19 : 20);
        DevTestState.QualityCeiling[] tiers = DevTestState.QualityCeiling.VALUES;
        int pillGap = 3;
        int pillW = (w - 10 - pillGap * (tiers.length - 1)) / tiers.length;
        for (int i = 0; i < tiers.length; i++) {
            DevTestState.QualityCeiling tier = tiers[i];
            int px = x + 5 + i * (pillW + pillGap);
            boolean active = tier == current;
            String pillText = compactLayout ? tier.shortTag : (tier.shortTag + " (" + shortTierDesc(tier) + ")");
            drawActionPill(g, mouseX, mouseY, px, row2Y, pillW, rowH, pillText, active,
                    active ? SkyUi.GOLD : SkyUi.TEXT, () -> selectQualityCeiling(tier));
        }

        // Row 3: Live Clamped Knob Percentages & Hardware Info
        int row3Y = y + (compactLayout ? 36 : 38);
        double[] caps = current.knobCaps;
        double rangePct = readKnobPercent(Knob.ATLAS_SCALE, caps, 0);
        double detailPct = readKnobPercent(Knob.FAR_DETAIL, caps, 1);
        double clutterPct = readKnobPercent(Knob.AIR_MARCH, caps, 2);
        double shadingPct = readKnobPercent(Knob.SHADOW_TAPS, caps, 5);
        if (!traced) shadingPct = Math.min(shadingPct, 25.0);
        double cloudsPct = readKnobPercent(Knob.ATLAS_CLOUDS, caps, 8);

        String knobSummary = String.format(
                Locale.ROOT,
                "Knobs: Range %.0f%% | Detail %.0f%% | Clutter %.0f%% | Shading %.0f%% | Clouds %.0f%%  (HW: %s)",
                rangePct, detailPct, clutterPct, shadingPct, cloudsPct, hwTier);
        g.text(font, SkyUi.ellipsize(font, knobSummary, w - 10), x + 5, row3Y + 2, SkyUi.DIM, false);
    }

    private static String shortTierDesc(DevTestState.QualityCeiling tier) {
        return switch (tier) {
            case STEAM_DECK -> "Deck";
            case IGPU -> "iGPU";
            case LOW -> "Low";
            case MEDIUM -> "Med";
            case HIGH -> "High";
            case ULTRA_MAX -> "Max";
        };
    }

    private static double readKnobPercent(Knob knob, double[] caps, int fallbackCapIdx) {
        double[] live = QualityClient.knobs();
        double cap = 1.0;
        if (caps != null && caps.length > 0) {
            int idx = knob.ordinal() < caps.length ? knob.ordinal() : Math.min(fallbackCapIdx, caps.length - 1);
            cap = caps[idx];
        }
        double val = (live != null && knob.ordinal() < live.length)
                ? Math.min(live[knob.ordinal()], cap)
                : cap;
        return Mth.clamp(val * 100.0, 0.0, 100.0);
    }

    private static void selectQualityCeiling(DevTestState.QualityCeiling tier) {
        DevTestState.qualityCeiling = tier;
        DevTestState.recordEvent("QualityCeiling -> " + tier.label);
        DevTestState.showToast(
                "PROFILE CEILING: " + tier.label.toUpperCase(Locale.ROOT),
                "Adaptive quality upper bound set to [" + tier.shortTag + "]",
                2800L);
    }

    private void renderLeftColumn(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int colH = colBottomY - colTopY;
        SkyUi.panel(g, colLeftX, colTopY, colLeftX + colW, colBottomY);
        SkyUi.caps(g, font, Component.literal("PLAYER, WORLD & GAMEPLAY"), colLeftX + 5, colTopY + 4, SkyUi.GOLD);
        SkyUi.divider(g, colLeftX + colW / 2, colTopY + 15, colW / 2 - 8);

        int viewTop = colTopY + 18;
        int viewBottom = colBottomY - 3;
        g.enableScissor(colLeftX + 1, viewTop, colLeftX + colW - 1, viewBottom);

        int y = viewTop - (int) leftScroll;
        int rx = colLeftX + 5;
        int rw = colW - 10;

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "God Mode (Invulnerable)", DevTestState.godMode, () -> {
                    DevTestState.godMode = !DevTestState.godMode;
                    DevTestState.recordEvent("God Mode -> " + DevTestState.godMode);
                    DevTestClient.sendApplyFlags();
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Flying Mode", DevTestState.flyMode, () -> {
                    DevTestState.flyMode = !DevTestState.flyMode;
                    DevTestState.recordEvent("Flying Mode -> " + DevTestState.flyMode);
                    DevTestClient.sendApplyFlags();
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "No-Hands / Passive Mode", DevTestState.noHandsMode, () -> {
                    DevTestState.noHandsMode = !DevTestState.noHandsMode;
                    DevTestState.recordEvent("No-Hands Mode -> " + DevTestState.noHandsMode);
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Quests & HUD Markers", DevTestState.questsEnabled, () -> {
                    DevTestState.questsEnabled = !DevTestState.questsEnabled;
                    DevTestState.recordEvent("Quests Enabled -> " + DevTestState.questsEnabled);
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Unlock All Map & Fast Travel", DevTestState.unlockAllMap, () -> {
                    DevTestState.unlockAllMap = !DevTestState.unlockAllMap;
                    DevTestState.recordEvent("Unlock All Map -> " + DevTestState.unlockAllMap);
                    DevTestClient.sendApplyFlags();
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Disable Map Fog of War", DevTestState.noFogOfWar, () -> {
                    DevTestState.noFogOfWar = !DevTestState.noFogOfWar;
                    DevTestState.recordEvent("Disable Map Fog -> " + DevTestState.noFogOfWar);
                });

        y += 3;
        SkyUi.hline(g, rx, rx + rw, y, 0x60);
        y += 4;

        // Bounty / Wanted System Controls
        SkyUi.caps(g, font, Component.literal("BOUNTY / WANTED CONTROLS"), rx, y, SkyUi.GOLD);
        y += 11;

        int holdBounty = 0;
        int totalBounty = 0;
        boolean hostile = false;
        String holdName = "Tamriel";
        if (minecraft != null && minecraft.player != null && minecraft.level != null) {
            holdBounty = Bounty.current(minecraft.player);
            totalBounty = Bounty.total(minecraft.player);
            hostile = Bounty.isHostile(minecraft.player);
            String holdId = Holds.holdAt(minecraft.level, minecraft.player.blockPosition());
            holdName = Holds.displayName(holdId).getString();
        }

        String bountyLine = String.format(Locale.ROOT, "Hold (%s): %d gold | Total: %d", holdName, holdBounty, totalBounty);
        g.text(font, SkyUi.ellipsize(font, bountyLine, rw), rx, y, SkyUi.TEXT, false);
        y += 10;

        String statusLine = "Guard Status: " + (hostile ? "HOSTILE (ATTACK ON SIGHT)" : (holdBounty >= Bounty.KILL_ON_SIGHT ? "WANTED (KILL ON SIGHT)" : "PEACEFUL / NORMAL"));
        g.text(font, SkyUi.ellipsize(font, statusLine, rw), rx, y, hostile || holdBounty >= Bounty.KILL_ON_SIGHT ? SkyUi.BAD : GOOD_GREEN, false);
        y += 12;

        int btnGap = 4;
        int halfW = (rw - btnGap) / 2;
        if (y + 13 >= viewTop && y < viewBottom) {
            drawActionPillClipped(g, mouseX, mouseY, rx, y, halfW, 13, viewTop, viewBottom,
                    "CLEAR ALL BOUNTY", false, SkyUi.TEXT, () -> DevTestClient.sendBountyAction("clear", 0));
            drawActionPillClipped(g, mouseX, mouseY, rx + halfW + btnGap, y, rw - halfW - btnGap, 13, viewTop, viewBottom,
                    "+100 BOUNTY", false, SkyUi.GOLD, () -> DevTestClient.sendBountyAction("add", 100));
        }
        y += 15;

        if (y + 13 >= viewTop && y < viewBottom) {
            drawActionPillClipped(g, mouseX, mouseY, rx, y, rw, 13, viewTop, viewBottom,
                    "+1000 WANTED / TOGGLE HOSTILE", hostile, hostile ? SkyUi.BAD : SkyUi.GOLD,
                    () -> DevTestClient.sendBountyAction("hostile", 1000));
        }
        y += 16;

        g.disableScissor();
        int contentH = y + (int) leftScroll - viewTop;
        int availH = viewBottom - viewTop;
        maxLeftScroll = Math.max(0, contentH - availH);
        leftScroll = Mth.clamp(leftScroll, 0f, maxLeftScroll);
        if (maxLeftScroll > 0 && colH > 24) {
            int barH = Math.max(12, availH * availH / Math.max(1, contentH));
            int barY = viewTop + (int) ((availH - barH) * (leftScroll / maxLeftScroll));
            g.fill(colLeftX + colW - 3, barY, colLeftX + colW - 1, barY + barH, 0xA0E8C060);
        }
    }

    private void renderRightColumn(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SkyUi.panel(g, colRightX, colTopY, colRightX + colW, colBottomY);
        SkyUi.caps(g, font, Component.literal("HUD OVERLAYS, FPS & TIME OF DAY"), colRightX + 5, colTopY + 4, SkyUi.GOLD);
        SkyUi.divider(g, colRightX + colW / 2, colTopY + 15, colW / 2 - 8);

        int viewTop = colTopY + 18;
        int viewBottom = colBottomY - 3;
        g.enableScissor(colRightX + 1, viewTop, colRightX + colW - 1, viewBottom);

        int y = viewTop - (int) rightScroll;
        int rx = colRightX + 5;
        int rw = colW - 10;

        // FPS & Performance Overlay selector
        String fpsLabel = compactLayout ? "FPS Overlay:" : "FPS & Perf Overlay:";
        g.text(font, fpsLabel, rx, y + 2, SkyUi.TEXT, false);
        int cycleW = Math.min(compactLayout ? 82 : 104, Math.max(64, rw - font.width(fpsLabel) - 6));
        if (y + 13 >= viewTop && y < viewBottom) {
            drawActionPillClipped(g, mouseX, mouseY, rx + rw - cycleW, y, cycleW, 13, viewTop, viewBottom,
                    DevTestState.fpsOverlayMode.label.toUpperCase(Locale.ROOT),
                    DevTestState.fpsOverlayMode != DevTestState.FpsOverlayMode.OFF,
                    SkyUi.GOLD, () -> {
                        DevTestState.fpsOverlayMode = DevTestState.fpsOverlayMode.next();
                        DevTestState.recordEvent("FPS Overlay -> " + DevTestState.fpsOverlayMode.label);
                    });
        }
        y += 16;

        // 4 direct FPS mode pills
        DevTestState.FpsOverlayMode[] modes = DevTestState.FpsOverlayMode.VALUES;
        int mGap = 3;
        int mW = (rw - mGap * (modes.length - 1)) / modes.length;
        String[] shortModes = {"OFF", "BASIC", "ADV S", "ADV L"};
        if (y + 13 >= viewTop && y < viewBottom) {
            for (int i = 0; i < modes.length; i++) {
                DevTestState.FpsOverlayMode m = modes[i];
                int mx = rx + i * (mW + mGap);
                boolean active = DevTestState.fpsOverlayMode == m;
                drawActionPillClipped(g, mouseX, mouseY, mx, y, mW, 13, viewTop, viewBottom,
                        shortModes[i], active, active ? SkyUi.GOLD : SkyUi.DIM, () -> {
                            DevTestState.fpsOverlayMode = m;
                            DevTestState.recordEvent("FPS Overlay -> " + m.label);
                        });
            }
        }
        y += 16;

        // Master HUD Toggle + Individual HUD toggles
        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Master Screen UI Overlays", DevTestState.hudMaster, () -> {
                    DevTestState.hudMaster = !DevTestState.hudMaster;
                    DevTestState.recordEvent("HUD Master -> " + DevTestState.hudMaster);
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Crosshair", DevTestState.hudCrosshair, () -> {
                    DevTestState.hudCrosshair = !DevTestState.hudCrosshair;
                    DevTestState.recordEvent("HUD Crosshair -> " + DevTestState.hudCrosshair);
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Compass Bar", DevTestState.hudCompass, () -> {
                    DevTestState.hudCompass = !DevTestState.hudCompass;
                    DevTestState.recordEvent("HUD Compass -> " + DevTestState.hudCompass);
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Vitals (Health / Magicka / Stamina)", DevTestState.hudVitals, () -> {
                    DevTestState.hudVitals = !DevTestState.hudVitals;
                    DevTestState.recordEvent("HUD Vitals -> " + DevTestState.hudVitals);
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Hotbar", DevTestState.hudHotbar, () -> {
                    DevTestState.hudHotbar = !DevTestState.hudHotbar;
                    DevTestState.recordEvent("HUD Hotbar -> " + DevTestState.hudHotbar);
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "Quest & Crime Notifications", DevTestState.hudNotifications, () -> {
                    DevTestState.hudNotifications = !DevTestState.hudNotifications;
                    DevTestState.recordEvent("HUD Notifications -> " + DevTestState.hudNotifications);
                });

        y = drawToggleRow(g, mouseX, mouseY, rx, y, rw, viewTop, viewBottom,
                "First-Person Hands", DevTestState.hudHands, () -> {
                    DevTestState.hudHands = !DevTestState.hudHands;
                    DevTestState.recordEvent("HUD Hands -> " + DevTestState.hudHands);
                });

        y += 3;
        SkyUi.hline(g, rx, rx + rw, y, 0x60);
        y += 4;

        // Quick Time-of-Day Preset Row
        long dayTime = (minecraft != null && minecraft.level != null) ? minecraft.level.getOverworldClockTime() : 6000L;
        String todLabel = "TIME OF DAY [0-9]: " + DevTestState.formatDayTime(dayTime);
        g.text(font, SkyUi.ellipsize(font, todLabel, rw), rx, y, SkyUi.GOLD, false);
        y += 11;

        int[] presetDigits = {3, 6, 8, 9, 0};
        String[] presetLabels = {"DAWN (3)", "NOON (6)", "SUNSET (8)", "DUSK (9)", "NIGHT (0)"};
        int pGap = 3;
        int pW = (rw - pGap * (presetDigits.length - 1)) / presetDigits.length;
        if (y + 13 >= viewTop && y < viewBottom) {
            for (int i = 0; i < presetDigits.length; i++) {
                int digit = presetDigits[i];
                int px = rx + i * (pW + pGap);
                drawActionPillClipped(g, mouseX, mouseY, px, y, pW, 13, viewTop, viewBottom,
                        presetLabels[i], false, SkyUi.TEXT, () -> {
                            long cur = (minecraft != null && minecraft.level != null)
                                    ? minecraft.level.getOverworldClockTime()
                                    : 6000L;
                            DevTestState.startTimeFade(cur, digit);
                        });
            }
        }
        y += 16;

        g.disableScissor();
        int contentH = y + (int) rightScroll - viewTop;
        int availH = viewBottom - viewTop;
        maxRightScroll = Math.max(0, contentH - availH);
        rightScroll = Mth.clamp(rightScroll, 0f, maxRightScroll);
        if (maxRightScroll > 0) {
            int barH = Math.max(12, availH * availH / Math.max(1, contentH));
            int barY = viewTop + (int) ((availH - barH) * (rightScroll / maxRightScroll));
            g.fill(colRightX + colW - 3, barY, colRightX + colW - 1, barY + barH, 0xA0E8C060);
        }
    }

    private int drawToggleRow(
            GuiGraphicsExtractor g,
            int mouseX,
            int mouseY,
            int x,
            int y,
            int w,
            int clipTop,
            int clipBottom,
            String label,
            boolean state,
            Runnable onToggle) {
        int rowH = compactLayout ? 14 : 15;
        if (y + rowH >= clipTop && y < clipBottom) {
            boolean insideClip = mouseY >= clipTop && mouseY < clipBottom;
            boolean hov = insideClip && SkyUi.inside(mouseX, mouseY, x, y, x + w, y + rowH);
            if (hov) {
                g.fill(x, y, x + w, y + rowH - 1, 0x28FFFFFF);
            }
            SkyUi.diamond(g, x + 5, y + rowH / 2 - 1, 2, state ? SkyUi.GOLD : OFF_GRAY);
            int pillW = compactLayout ? 36 : 42;
            String shown = SkyUi.ellipsize(font, label, w - pillW - 16);
            g.text(font, shown, x + 11, y + 3, hov ? SkyUi.BRIGHT : SkyUi.TEXT, false);

            int pillX = x + w - pillW;
            drawActionPillClipped(g, mouseX, mouseY, pillX, y + 1, pillW, rowH - 3, clipTop, clipBottom,
                    state ? "ON" : "OFF", state, state ? GOOD_GREEN : OFF_GRAY, onToggle);
            SkyUi.hline(g, x, x + w, y + rowH - 1, 0x28);
        }
        return y + rowH;
    }

    private void drawActionPill(
            GuiGraphicsExtractor g,
            int mouseX,
            int mouseY,
            int x,
            int y,
            int w,
            int h,
            String text,
            boolean selected,
            int textColor,
            Runnable action) {
        drawActionPillClipped(g, mouseX, mouseY, x, y, w, h, 0, height, text, selected, textColor, action);
    }

    private void drawActionPillClipped(
            GuiGraphicsExtractor g,
            int mouseX,
            int mouseY,
            int x,
            int y,
            int w,
            int h,
            int clipTop,
            int clipBottom,
            String text,
            boolean selected,
            int textColor,
            Runnable action) {
        boolean inClip = mouseY >= clipTop && mouseY < clipBottom;
        boolean hov = inClip && SkyUi.inside(mouseX, mouseY, x, y, x + w, y + h);
        int bgTop = selected ? 0x703A3018 : (hov ? 0x6828241E : 0x58101014);
        int bgBot = selected ? 0x90241C0E : (hov ? 0x88181612 : 0x7808080A);
        g.fillGradient(x, y, x + w, y + h, bgTop, bgBot);
        int borderAlpha = selected ? 0xD0 : (hov ? 0xA0 : 0x55);
        SkyUi.hline(g, x, x + w, y, borderAlpha);
        SkyUi.hline(g, x, x + w, y + h - 1, borderAlpha);
        if (selected) {
            g.fill(x, y, x + 1, y + h, 0xFFE8C060);
            g.fill(x + w - 1, y, x + w, y + h, 0xFFE8C060);
        }
        String shown = SkyUi.ellipsize(font, text, Math.max(8, w - 6));
        int col = hov ? SkyUi.BRIGHT : textColor;
        g.text(font, shown, x + (w - font.width(shown)) / 2, y + (h - 8) / 2, col, selected || hov);

        int hitY0 = Math.max(y, clipTop);
        int hitY1 = Math.min(y + h, clipBottom);
        if (hitY1 > hitY0 && action != null) {
            hitRects.add(new HitRect(x, hitY0, w, hitY1 - hitY0, action));
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        int button = UiInput.actionButton(event.button());
        if (button == 0) {
            double mx = event.x();
            double my = event.y();
            for (int i = hitRects.size() - 1; i >= 0; i--) {
                HitRect rect = hitRects.get(i);
                if (rect.contains(mx, my)) {
                    rect.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        if (SkyUi.inside(mx, my, colLeftX, colTopY, colLeftX + colW, colBottomY) && maxLeftScroll > 0) {
            leftScroll = Mth.clamp(leftScroll - (float) delta * 16f, 0f, maxLeftScroll);
            return true;
        }
        if (SkyUi.inside(mx, my, colRightX, colTopY, colRightX + colW, colBottomY) && maxRightScroll > 0) {
            rightScroll = Mth.clamp(rightScroll - (float) delta * 16f, 0f, maxRightScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, delta);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        // Pressing '-' inside DevSettingsScreen cycles the Hardware Quality Ceiling
        if (key == InputConstants.KEY_MINUS) {
            int delta = UiInput.shiftDown() ? -1 : 1;
            DevTestState.cycleQualityCeiling(delta);
            return true;
        }
        // Pressing '=' / '+' inside DevSettingsScreen opens Session Capture
        if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
            DevTestClient.triggerSessionCapture(this);
            return true;
        }
        // Pressing 'E' switches to SkyrimCreativeScreen
        if (key == InputConstants.KEY_E) {
            if (minecraft != null) minecraft.gui.setScreen(new SkyrimCreativeScreen(this));
            return true;
        }
        // Pressing 'M' switches to MapScreen
        if (key == InputConstants.KEY_M) {
            if (minecraft != null) minecraft.gui.setScreen(new MapScreen());
            return true;
        }
        // Number keys 0..9 trigger smooth Time-of-Day fade
        if (key >= InputConstants.KEY_0 && key <= InputConstants.KEY_9) {
            int digit = key - InputConstants.KEY_0;
            long cur = (minecraft != null && minecraft.level != null)
                    ? minecraft.level.getOverworldClockTime()
                    : 6000L;
            DevTestState.startTimeFade(cur, digit);
            return true;
        }
        return super.keyPressed(event);
    }
}
