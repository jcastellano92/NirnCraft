package com.nirncraft.devtest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.nirncraft.NirnCraft;
import com.nirncraft.atlas.AtlasClient;
import com.nirncraft.client.UiDynamicTexture;
import com.nirncraft.client.screen.UiInput;
import com.nirncraft.inventory.client.SkyUi;
import com.nirncraft.render.quality.Knob;
import com.nirncraft.render.quality.QualityClient;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Skyrim-styled Session Capture &amp; Issue Diagnostic Screen opened with the {@code =} / {@code +} key.
 *
 * <p>Displays the live captured frame with an interactive click-and-drag crop rectangle, category selector pills,
 * keyboard issue description input, live frame/GPU/world telemetry summary, and one-click bundle export to
 * {@code dev-captures/capture-<timestamp>/}. Scales cleanly from 427x267 (Steam Deck GUI scale 3) up to 4K.</p>
 */
public class DevCaptureScreen extends Screen {
    private static final Identifier PREVIEW_ID =
            Identifier.fromNamespaceAndPath(NirnCraft.MODID, "dev_capture_preview");
    private static final DateTimeFormatter FOLDER_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final String[] CATEGORIES = {
            "Visual", "Performance", "Terrain / LOD", "Lighting", "UI / HUD", "Entity / NPC"
    };

    @Nullable
    private final Screen parent;
    private final NativeImage image;
    private final int imgW;
    private final int imgH;

    // Snapshot of telemetry at the moment capture was opened
    private final double[] capturedStats;
    private final String capturedRegion;
    private final BlockPos capturedPos;
    private final float capturedYaw;
    private final float capturedPitch;
    private final long capturedDayTime;
    private final String capturedDimension;

    @Nullable
    private UiDynamicTexture previewTexture;
    private boolean textureRegistered = false;
    private boolean resourcesClosed = false;

    // Interactive crop rectangle in normalized [0..1] image coordinates
    private boolean hasCrop = false;
    private boolean draggingCrop = false;
    private double cropStartU = 0.0;
    private double cropStartV = 0.0;
    private double cropU0 = 0.0;
    private double cropV0 = 0.0;
    private double cropU1 = 1.0;
    private double cropV1 = 1.0;

    // Issue metadata
    private int selectedCategoryIdx = 0;
    private final StringBuilder description = new StringBuilder();
    private boolean descriptionFocused = true;

    private record HitRect(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<HitRect> hitRects = new ArrayList<>();

    // Layout coordinates
    private boolean compactLayout;
    private int panelX, panelY, panelW, panelH;
    private int previewBoxX, previewBoxY, previewBoxW, previewBoxH;
    private int imgDrawX, imgDrawY, imgDrawW, imgDrawH;
    private int rightX, rightY, rightW, rightH;
    private int descBoxX, descBoxY, descBoxW, descBoxH;

    public DevCaptureScreen(@Nullable Screen parent, NativeImage image) {
        super(Component.literal("NirnCraft Session Capture"));
        this.parent = parent;
        this.image = image != null ? image : new NativeImage(320, 180, true);
        this.imgW = Math.max(1, this.image.getWidth());
        this.imgH = Math.max(1, this.image.getHeight());
        this.capturedStats = DevTestState.computeFrameStats();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) {
            this.capturedPos = mc.player.blockPosition();
            this.capturedYaw = mc.player.getYRot();
            this.capturedPitch = mc.player.getXRot();
            this.capturedDayTime = mc.level.getOverworldClockTime();
            this.capturedDimension = mc.level.dimension().identifier().toString();
            String reg = "Tamriel";
            try {
                Region r = Regions.at(mc.level, this.capturedPos);
                if (r != null) reg = r.id();
            } catch (Exception ignored) {
            }
            this.capturedRegion = reg;
        } else {
            this.capturedPos = BlockPos.ZERO;
            this.capturedYaw = 0f;
            this.capturedPitch = 0f;
            this.capturedDayTime = 6000L;
            this.capturedDimension = "minecraft:overworld";
            this.capturedRegion = "Tamriel";
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        ensureTextureRegistered();

        compactLayout = height < 300 || width < 480;
        int marginX = compactLayout ? 8 : 16;
        int marginY = compactLayout ? 6 : 12;
        panelW = Mth.clamp(width - marginX * 2, 360, 700);
        panelH = Mth.clamp(height - marginY * 2, 236, 440);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        int pad = compactLayout ? 6 : 10;
        int topY = panelY + (compactLayout ? 20 : 25);
        int contentH = panelH - (compactLayout ? 36 : 44);

        rightW = Mth.clamp((int) (panelW * 0.45f), 175, 295);
        rightX = panelX + panelW - pad - rightW;
        rightY = topY;
        rightH = contentH;

        previewBoxX = panelX + pad;
        previewBoxY = topY;
        previewBoxW = rightX - previewBoxX - (compactLayout ? 6 : 8);
        previewBoxH = contentH;

        // Fit image inside previewBox with margin for header & footer bar
        int innerX = previewBoxX + 6;
        int innerY = previewBoxY + 18;
        int innerW = Math.max(32, previewBoxW - 12);
        int innerH = Math.max(32, previewBoxH - 34);

        double scale = Math.min(innerW / (double) imgW, innerH / (double) imgH);
        imgDrawW = Math.max(16, (int) Math.round(imgW * scale));
        imgDrawH = Math.max(16, (int) Math.round(imgH * scale));
        imgDrawX = innerX + (innerW - imgDrawW) / 2;
        imgDrawY = innerY + (innerH - imgDrawH) / 2;
    }

    private void ensureTextureRegistered() {
        if (textureRegistered || resourcesClosed) return;
        Minecraft mc = Minecraft.getInstance();
        previewTexture = new UiDynamicTexture("dev_capture_preview", image, true);
        mc.getTextureManager().register(PREVIEW_ID, previewTexture);
        textureRegistered = true;
    }

    private void releaseCaptureResources() {
        if (resourcesClosed) return;
        resourcesClosed = true;
        Minecraft mc = Minecraft.getInstance();
        if (textureRegistered && mc != null) {
            mc.getTextureManager().release(PREVIEW_ID);
            textureRegistered = false;
            previewTexture = null;
        } else if (image != null) {
            image.close();
        }
    }

    @Override
    public void removed() {
        super.removed();
        releaseCaptureResources();
    }

    @Override
    public void onClose() {
        releaseCaptureResources();
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hitRects.clear();

        g.fillGradient(0, 0, width, height, 0xCC050507, 0xE0100D0A);
        SkyUi.smoke(g, width, height, (net.minecraft.util.Util.getMillis() % 1_000_000L) / 1000f, 0.45f);

        SkyUi.panel(g, panelX, panelY, panelX + panelW, panelY + panelH);
        int cx = panelX + panelW / 2;
        SkyUi.capsCentered(g, font, Component.literal("SESSION CAPTURE & ISSUE REPORT [=]"), cx, panelY + 5, SkyUi.GOLD);
        if (!compactLayout) {
            SkyUi.ornament(g, cx, panelY + 16, Math.min(110, panelW / 4), 0xB0C8BC9A);
        } else {
            SkyUi.divider(g, cx, panelY + 15, Math.min(120, panelW / 3));
        }

        renderPreviewArea(g, mouseX, mouseY);
        renderRightPanel(g, mouseX, mouseY);

        // Footer bar
        int pad = compactLayout ? 6 : 10;
        int footerLineY = panelY + panelH - (compactLayout ? 14 : 17);
        SkyUi.hline(g, panelX + pad, panelX + panelW - pad, footerLineY, 0x70);
        String hint = "Drag on Screenshot to Crop   |   Type Issue Notes   |   [ENTER] Save Bundle   |   [ESC] Cancel";
        g.text(font, SkyUi.ellipsize(font, hint, panelW - pad * 2 - 4),
                panelX + pad + 2, footerLineY + (compactLayout ? 3 : 4), SkyUi.DIM, false);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void renderPreviewArea(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SkyUi.panel(g, previewBoxX, previewBoxY, previewBoxX + previewBoxW, previewBoxY + previewBoxH);
        int resetW = compactLayout ? 68 : 88;
        int resetX = previewBoxX + previewBoxW - resetW - 5;
        int resetY = previewBoxY + 3;

        String headerTitle = SkyUi.ellipsize(font, "CAPTURED FRAME (DRAG TO CROP)", previewBoxW - resetW - 14);
        g.text(font, headerTitle, previewBoxX + 6, previewBoxY + 5, SkyUi.GOLD, false);

        drawActionPill(g, mouseX, mouseY, resetX, resetY, resetW, 13,
                compactLayout ? "RESET CROP" : "RESET FULL IMAGE", !hasCrop,
                hasCrop ? SkyUi.GOLD : SkyUi.DIM, () -> {
                    hasCrop = false;
                    cropU0 = 0.0;
                    cropV0 = 0.0;
                    cropU1 = 1.0;
                    cropV1 = 1.0;
                });

        // Gold frame around screenshot
        g.fill(imgDrawX - 2, imgDrawY - 2, imgDrawX + imgDrawW + 2, imgDrawY + imgDrawH + 2, 0xFF3A3020);
        g.fill(imgDrawX - 1, imgDrawY - 1, imgDrawX + imgDrawW + 1, imgDrawY + imgDrawH + 1, 0xFF0A0A0C);

        if (textureRegistered) {
            SkyUi.blit(g, PREVIEW_ID, imgDrawX, imgDrawY, imgDrawW, imgDrawH, 0f, 0f, imgW, imgH, imgW, imgH);
        }

        // Interactive Crop Overlay
        if (hasCrop) {
            int cx0 = imgDrawX + (int) Math.round(cropU0 * imgDrawW);
            int cy0 = imgDrawY + (int) Math.round(cropV0 * imgDrawH);
            int cx1 = imgDrawX + (int) Math.round(cropU1 * imgDrawW);
            int cy1 = imgDrawY + (int) Math.round(cropV1 * imgDrawH);

            cx0 = Mth.clamp(cx0, imgDrawX, imgDrawX + imgDrawW);
            cy0 = Mth.clamp(cy0, imgDrawY, imgDrawY + imgDrawH);
            cx1 = Mth.clamp(cx1, cx0 + 1, imgDrawX + imgDrawW);
            cy1 = Mth.clamp(cy1, cy0 + 1, imgDrawY + imgDrawH);

            // Dimmed mask outside crop box
            int maskCol = 0x98000000;
            g.fill(imgDrawX, imgDrawY, imgDrawX + imgDrawW, cy0, maskCol);
            g.fill(imgDrawX, cy1, imgDrawX + imgDrawW, imgDrawY + imgDrawH, maskCol);
            g.fill(imgDrawX, cy0, cx0, cy1, maskCol);
            g.fill(cx1, cy0, imgDrawX + imgDrawW, cy1, maskCol);

            // Bright gold crop border
            g.fill(cx0, cy0, cx1, cy0 + 1, SkyUi.GOLD);
            g.fill(cx0, cy1 - 1, cx1, cy1, SkyUi.GOLD);
            g.fill(cx0, cy0, cx0 + 1, cy1, SkyUi.GOLD);
            g.fill(cx1 - 1, cy0, cx1, cy1, SkyUi.GOLD);

            // Corner handles
            drawCornerHandle(g, cx0, cy0);
            drawCornerHandle(g, cx1, cy0);
            drawCornerHandle(g, cx0, cy1);
            drawCornerHandle(g, cx1, cy1);

            int[] pxRect = computeCropPixels();
            String dimLabel = pxRect[2] + "x" + pxRect[3] + " px";
            int lw = font.width(dimLabel) + 6;
            int lx = Mth.clamp(cx0, imgDrawX, imgDrawX + imgDrawW - lw);
            int ly = cy0 >= imgDrawY + 12 ? cy0 - 11 : Math.min(imgDrawY + imgDrawH - 10, cy1 + 2);
            g.fill(lx, ly, lx + lw, ly + 10, 0xD00A0A0E);
            g.text(font, dimLabel, lx + 3, ly + 1, SkyUi.GOLD, false);
        }

        // Bottom info line inside preview box
        int[] px = computeCropPixels();
        String cropInfo = hasCrop
                ? String.format(Locale.ROOT, "Crop: %dx%d px at (%d, %d) [Full %dx%d]", px[2], px[3], px[0], px[1], imgW, imgH)
                : String.format(Locale.ROOT, "Full Frame: %dx%d px (Drag on preview to crop)", imgW, imgH);
        g.text(font, SkyUi.ellipsize(font, cropInfo, previewBoxW - 12),
                previewBoxX + 6, previewBoxY + previewBoxH - 12, SkyUi.TEXT, false);
    }

    private static void drawCornerHandle(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x - 3, y - 3, x + 3, y + 3, 0xFF000000);
        g.fill(x - 2, y - 2, x + 2, y + 2, SkyUi.GOLD);
    }

    private void renderRightPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SkyUi.panel(g, rightX, rightY, rightX + rightW, rightY + rightH);
        int rx = rightX + 5;
        int rw = rightW - 10;
        int y = rightY + 4;

        // 1. Issue Category Pills
        SkyUi.caps(g, font, Component.literal("ISSUE CATEGORY"), rx, y, SkyUi.GOLD);
        y += 10;

        int cols = 3;
        int gap = 3;
        int pillW = (rw - gap * (cols - 1)) / cols;
        int pillH = compactLayout ? 12 : 13;
        for (int i = 0; i < CATEGORIES.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int px = rx + col * (pillW + gap);
            int py = y + row * (pillH + gap);
            int idx = i;
            boolean active = selectedCategoryIdx == idx;
            drawActionPill(g, mouseX, mouseY, px, py, pillW, pillH, CATEGORIES[i], active,
                    active ? SkyUi.GOLD : SkyUi.TEXT, () -> selectedCategoryIdx = idx);
        }
        y += 2 * (pillH + gap) + (compactLayout ? 2 : 4);

        // 2. Issue Description Input Box
        SkyUi.caps(g, font, Component.literal("ISSUE NOTES"), rx, y, SkyUi.GOLD);
        String clearHint = "[Ctrl+Bksp]";
        if (rw > font.width("ISSUE NOTES") + font.width(clearHint) + 12) {
            g.text(font, clearHint, rx + rw - font.width(clearHint), y, SkyUi.DIM, false);
        }
        y += 10;

        descBoxX = rx;
        descBoxY = y;
        descBoxW = rw;
        descBoxH = compactLayout ? 30 : Math.max(36, Math.min(54, rightH / 5));

        g.fill(descBoxX, descBoxY, descBoxX + descBoxW, descBoxY + descBoxH, 0xB0060608);
        SkyUi.hline(g, descBoxX, descBoxX + descBoxW, descBoxY, descriptionFocused ? 0xD0 : 0x60);
        SkyUi.hline(g, descBoxX, descBoxX + descBoxW, descBoxY + descBoxH - 1, descriptionFocused ? 0xD0 : 0x60);

        String rawText = description.toString();
        boolean blink = (System.currentTimeMillis() / 450L) % 2L == 0L;
        String displayStr = rawText.isEmpty() && !descriptionFocused
                ? "Type notes describing the issue..."
                : rawText + (descriptionFocused && blink ? "_" : "");
        int textCol = (rawText.isEmpty() && !descriptionFocused) ? SkyUi.DIM : SkyUi.BRIGHT;

        List<FormattedCharSequence> wrapped = font.split(Component.literal(displayStr), descBoxW - 8);
        int maxLines = Math.max(1, (descBoxH - 6) / 9);
        int startLine = Math.max(0, wrapped.size() - maxLines);
        for (int i = startLine; i < wrapped.size(); i++) {
            g.text(font, wrapped.get(i), descBoxX + 4, descBoxY + 3 + (i - startLine) * 9, textCol, false);
        }
        hitRects.add(new HitRect(descBoxX, descBoxY, descBoxW, descBoxH, () -> descriptionFocused = true));
        y += descBoxH + (compactLayout ? 3 : 5);

        // 3. Captured Telemetry Summary
        SkyUi.caps(g, font, Component.literal("TELEMETRY SNAPSHOT"), rx, y, SkyUi.GOLD);
        y += 10;

        int fps = (int) Math.round(capturedStats[0]);
        int lowFps = (int) Math.round(capturedStats[6]);
        String hwTier = QualityClient.qualityName();
        if (hwTier == null || hwTier.isBlank()) hwTier = "NATIVE";

        String[] telemetryLines = {
                String.format(Locale.ROOT, "FPS: %d (1%% Low: %d) | Avg %.1f ms", fps, lowFps, capturedStats[1]),
                String.format(Locale.ROOT, "min %.1f / p50 %.1f / p99 %.1f / max %.1f ms",
                        capturedStats[2], capturedStats[4], capturedStats[5], capturedStats[3]),
                String.format(Locale.ROOT, "Ceiling: %s [%s] | HW: %s",
                        DevTestState.qualityCeiling.label, DevTestState.qualityCeiling.shortTag, hwTier),
                String.format(Locale.ROOT, "Light: %s | Time: %s",
                        DevTestState.tracedLighting ? "Traced ON" : "Fallback Low",
                        DevTestState.formatDayTime(capturedDayTime)),
                String.format(Locale.ROOT, "Pos: (%d, %d, %d) Yaw %.0f Pitch %.0f",
                        capturedPos.getX(), capturedPos.getY(), capturedPos.getZ(), capturedYaw, capturedPitch),
                String.format(Locale.ROOT, "Region: %s | Events: %d",
                        capturedRegion, DevTestState.recentEventsSnapshot().size())
        };

        int btnH = compactLayout ? 14 : 15;
        int btnY = rightY + rightH - btnH - 3;
        int lineStep = compactLayout ? 9 : 10;
        for (String line : telemetryLines) {
            if (y + 8 > btnY - 2) break;
            g.text(font, SkyUi.ellipsize(font, line, rw), rx, y, SkyUi.TEXT, false);
            y += lineStep;
        }

        // 4. [SAVE CAPTURE BUNDLE] & [CANCEL] Buttons at bottom of Right Panel
        int cancelW = compactLayout ? 46 : 54;
        int saveW = rw - cancelW - 4;
        drawActionPill(g, mouseX, mouseY, rx, btnY, saveW, btnH, "SAVE CAPTURE BUNDLE [ENTER]", true,
                SkyUi.GOLD, this::saveCaptureBundle);
        drawActionPill(g, mouseX, mouseY, rx + saveW + 4, btnY, cancelW, btnH, "CANCEL", false,
                SkyUi.TEXT, this::onClose);
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
        boolean hov = SkyUi.inside(mouseX, mouseY, x, y, x + w, y + h);
        int bgTop = selected ? 0x783A3018 : (hov ? 0x6828241E : 0x58101014);
        int bgBot = selected ? 0x98241C0E : (hov ? 0x88181612 : 0x7808080A);
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
        if (action != null) {
            hitRects.add(new HitRect(x, y, w, h, action));
        }
    }

    private int[] computeCropPixels() {
        if (!hasCrop) {
            return new int[]{0, 0, imgW, imgH};
        }
        int x0 = Mth.clamp((int) Math.floor(cropU0 * imgW), 0, imgW - 1);
        int y0 = Mth.clamp((int) Math.floor(cropV0 * imgH), 0, imgH - 1);
        int x1 = Mth.clamp((int) Math.ceil(cropU1 * imgW), x0 + 1, imgW);
        int y1 = Mth.clamp((int) Math.ceil(cropV1 * imgH), y0 + 1, imgH);
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private void saveCaptureBundle() {
        Minecraft mc = Minecraft.getInstance();
        String folderName = "capture-" + FOLDER_TIME_FMT.format(LocalDateTime.now());
        Path primaryDir = mc.gameDirectory.toPath().resolve("dev-captures").resolve(folderName);
        List<Path> targetDirs = new ArrayList<>();
        targetDirs.add(primaryDir);

        Path scratchRoot = Path.of("E:/NirnCraft-scratch");
        if (Files.isDirectory(scratchRoot)) {
            targetDirs.add(scratchRoot.resolve("owner-play").resolve("captures").resolve(folderName));
        }

        int[] cropPx = computeCropPixels();
        String reportJson = buildReportJson(folderName, cropPx);
        List<String> latestTail = readTailLines(mc.gameDirectory.toPath().resolve("logs/latest.log"), 250);
        Path profilePath = mc.gameDirectory.toPath().resolve("logs/atlas-profile.log");
        if (!Files.isRegularFile(profilePath)) {
            profilePath = mc.gameDirectory.toPath().resolve("atlas-profile.log");
        }
        List<String> profileTail = readTailLines(profilePath, 120);

        for (Path dir : targetDirs) {
            try {
                Files.createDirectories(dir);
                if (!resourcesClosed && image != null) {
                    image.writeToFile(dir.resolve("screenshot_full.png"));
                    if (hasCrop && cropPx[2] > 0 && cropPx[3] > 0) {
                        try (NativeImage cropped = new NativeImage(cropPx[2], cropPx[3], false)) {
                            for (int py = 0; py < cropPx[3]; py++) {
                                for (int px = 0; px < cropPx[2]; px++) {
                                    int abgr = ARGB.toABGR(image.getPixel(cropPx[0] + px, cropPx[1] + py));
                                    cropped.setPixelABGR(px, py, abgr);
                                }
                            }
                            cropped.writeToFile(dir.resolve("screenshot_cropped.png"));
                        }
                    }
                }
                Files.writeString(dir.resolve("report.json"), reportJson, StandardCharsets.UTF_8);
                if (!latestTail.isEmpty()) {
                    Files.write(dir.resolve("latest_tail.log"), latestTail, StandardCharsets.UTF_8);
                }
                if (!profileTail.isEmpty()) {
                    Files.write(dir.resolve("atlas_profile_tail.log"), profileTail, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                NirnCraft.LOGGER.warn("Failed to write capture bundle to {}", dir, e);
            }
        }

        DevTestState.recordEvent("Saved Capture Bundle: " + folderName + " [" + CATEGORIES[selectedCategoryIdx] + "]");
        DevTestState.showToast("SESSION CAPTURE SAVED", "Saved to dev-captures/" + folderName, 3500L);
        onClose();
    }

    private String buildReportJson(String folderName, int[] cropPx) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("bundleId", folderName);
        root.put("timestamp", ISO_TIME_FMT.format(LocalDateTime.now()));
        root.put("category", CATEGORIES[selectedCategoryIdx]);
        root.put("description", description.toString().trim());

        Map<String, Object> cropRect = new LinkedHashMap<>();
        cropRect.put("active", hasCrop);
        cropRect.put("x", cropPx[0]);
        cropRect.put("y", cropPx[1]);
        cropRect.put("width", cropPx[2]);
        cropRect.put("height", cropPx[3]);
        cropRect.put("fullWidth", imgW);
        cropRect.put("fullHeight", imgH);
        root.put("cropRect", cropRect);

        Map<String, Object> fpsStats = new LinkedHashMap<>();
        fpsStats.put("fps", Math.round(capturedStats[0] * 10.0) / 10.0);
        fpsStats.put("avgMs", Math.round(capturedStats[1] * 100.0) / 100.0);
        fpsStats.put("minMs", Math.round(capturedStats[2] * 100.0) / 100.0);
        fpsStats.put("maxMs", Math.round(capturedStats[3] * 100.0) / 100.0);
        fpsStats.put("p50Ms", Math.round(capturedStats[4] * 100.0) / 100.0);
        fpsStats.put("p99Ms", Math.round(capturedStats[5] * 100.0) / 100.0);
        fpsStats.put("onePercentLowFps", Math.round(capturedStats[6] * 10.0) / 10.0);
        root.put("fpsStats", fpsStats);

        root.put("qualityCeiling", DevTestState.qualityCeiling.name() + " (" + DevTestState.qualityCeiling.label + ")");
        String hwTier = QualityClient.qualityName();
        root.put("hardwareTier", hwTier != null ? hwTier : "UNKNOWN");
        root.put("qualitySummary", QualityClient.describe());
        root.put("tracedLighting", DevTestState.tracedLighting);

        Map<String, Object> knobsMap = new LinkedHashMap<>();
        double[] liveKnobs = QualityClient.knobs();
        for (Knob k : Knob.ALL) {
            if (liveKnobs != null && k.ordinal() < liveKnobs.length) {
                knobsMap.put(k.name(), Math.round(liveKnobs[k.ordinal()] * 1000.0) / 1000.0);
            }
        }
        root.put("knobs", knobsMap);

        Map<String, Object> playerMap = new LinkedHashMap<>();
        playerMap.put("x", capturedPos.getX());
        playerMap.put("y", capturedPos.getY());
        playerMap.put("z", capturedPos.getZ());
        playerMap.put("yaw", Math.round(capturedYaw * 10.0f) / 10.0f);
        playerMap.put("pitch", Math.round(capturedPitch * 10.0f) / 10.0f);
        playerMap.put("dimension", capturedDimension);
        playerMap.put("region", capturedRegion);
        playerMap.put("dayTime", capturedDayTime);
        playerMap.put("formattedTime", DevTestState.formatDayTime(capturedDayTime));
        root.put("player", playerMap);

        try {
            root.put("atlasDebug", AtlasClient.debugInfo());
        } catch (Exception e) {
            root.put("atlasDebug", "unavailable: " + e.getMessage());
        }

        Runtime rt = Runtime.getRuntime();
        Map<String, Object> jvmMem = new LinkedHashMap<>();
        jvmMem.put("usedMb", (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L));
        jvmMem.put("allocatedMb", rt.totalMemory() / (1024L * 1024L));
        jvmMem.put("maxMb", rt.maxMemory() / (1024L * 1024L));
        root.put("jvmMemory", jvmMem);

        root.put("recentEvents", DevTestState.recentEventsSnapshot());

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(root);
    }

    private static List<String> readTailLines(Path path, int maxLines) {
        if (path == null || !Files.isRegularFile(path)) return List.of();
        try {
            List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (all.size() <= maxLines) return all;
            return new ArrayList<>(all.subList(all.size() - maxLines, all.size()));
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ Input Handling

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        int button = UiInput.actionButton(event.button());
        double mx = event.x();
        double my = event.y();

        if (button == 0) {
            // Check interactive crop start inside screenshot image
            if (SkyUi.inside(mx, my, imgDrawX, imgDrawY, imgDrawX + imgDrawW, imgDrawY + imgDrawH)) {
                draggingCrop = true;
                cropStartU = Mth.clamp((mx - imgDrawX) / (double) imgDrawW, 0.0, 1.0);
                cropStartV = Mth.clamp((my - imgDrawY) / (double) imgDrawH, 0.0, 1.0);
                cropU0 = cropStartU;
                cropV0 = cropStartV;
                cropU1 = Math.min(1.0, cropStartU + 0.01);
                cropV1 = Math.min(1.0, cropStartV + 0.01);
                hasCrop = true;
                return true;
            }

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
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        int button = UiInput.actionButton(event.button());
        if (draggingCrop && button == 0) {
            double u = Mth.clamp((event.x() - imgDrawX) / (double) imgDrawW, 0.0, 1.0);
            double v = Mth.clamp((event.y() - imgDrawY) / (double) imgDrawH, 0.0, 1.0);
            cropU0 = Math.min(cropStartU, u);
            cropV0 = Math.min(cropStartV, v);
            cropU1 = Math.max(cropStartU, u);
            cropV1 = Math.max(cropStartV, v);
            if (cropU1 - cropU0 < 0.015 && cropV1 - cropV0 < 0.015) {
                hasCrop = false;
            } else {
                hasCrop = true;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = UiInput.actionButton(event.button());
        if (button == 0 && draggingCrop) {
            draggingCrop = false;
            if (cropU1 - cropU0 < 0.015 || cropV1 - cropV0 < 0.015) {
                hasCrop = false;
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        boolean shift = UiInput.shiftDown();
        boolean ctrl = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);

        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }

        if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && !shift) {
            saveCaptureBundle();
            return true;
        }

        if (descriptionFocused) {
            if (key == InputConstants.KEY_BACKSPACE) {
                if (ctrl) {
                    description.setLength(0);
                } else if (!description.isEmpty()) {
                    description.deleteCharAt(description.length() - 1);
                }
                return true;
            }
            char ch = mapKeyToChar(key, shift);
            if (ch != 0 && description.length() < 400) {
                description.append(ch);
                return true;
            }
        }

        return super.keyPressed(event);
    }

    private static char mapKeyToChar(int key, boolean shift) {
        if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
            char base = (char) ('a' + (key - InputConstants.KEY_A));
            return shift ? Character.toUpperCase(base) : base;
        }
        if (key >= InputConstants.KEY_0 && key <= InputConstants.KEY_9) {
            int digit = key - InputConstants.KEY_0;
            if (!shift) return (char) ('0' + digit);
            return switch (digit) {
                case 1 -> '!';
                case 2 -> '@';
                case 3 -> '#';
                case 4 -> '$';
                case 5 -> '%';
                case 6 -> '^';
                case 7 -> '&';
                case 8 -> '*';
                case 9 -> '(';
                case 0 -> ')';
                default -> (char) ('0' + digit);
            };
        }
        return switch (key) {
            case InputConstants.KEY_SPACE -> ' ';
            case InputConstants.KEY_MINUS -> shift ? '_' : '-';
            case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> shift ? '+' : '=';
            case InputConstants.KEY_PERIOD -> shift ? '>' : '.';
            case InputConstants.KEY_COMMA -> shift ? '<' : ',';
            case InputConstants.KEY_SLASH -> shift ? '?' : '/';
            case InputConstants.KEY_SEMICOLON -> shift ? ':' : ';';
            case InputConstants.KEY_APOSTROPHE -> shift ? '"' : '\'';
            case InputConstants.KEY_LBRACKET -> shift ? '{' : '[';
            case InputConstants.KEY_RBRACKET -> shift ? '}' : ']';
            default -> 0;
        };
    }
}
