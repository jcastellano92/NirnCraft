package com.skycraft.client.screen.title;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.lwjgl.glfw.GLFW;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Skyrim-styled Load Game Screen:
 * Displays previous adventures in a dark Nordic scrollable list with last played timestamps,
 * one-click loading, and safe deletion.
 */
public class SkyLoadWorldScreen extends Screen {
    private static final int GOLD_COLOR = 0xFFE8C060;
    private static final int BRIGHT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFC8BC9A;
    private static final int DIM_COLOR = 0xFF8A8478;
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat();

    private final Screen lastScreen;
    private final List<LevelSummary> saves = new ArrayList<>();
    private int selectedIndex = 0;
    private float scrollOffset = 0f;
    private int maxScroll = 0;
    private boolean loading = true;
    private Button loadBtn;
    private Button deleteBtn;

    public SkyLoadWorldScreen(Screen lastScreen) {
        super(Component.literal("Load Journey"));
        this.lastScreen = lastScreen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    protected void init() {
        refreshSaves();

        int cx = width / 2;
        int btnW = 100;
        int btnH = 22;
        int btnY = height - 36;

        loadBtn = addRenderableWidget(Button.builder(
                Component.literal("LOAD"),
                btn -> loadSelected()
        ).bounds(cx - btnW - 55, btnY, btnW, btnH).build());

        deleteBtn = addRenderableWidget(Button.builder(
                Component.literal("DELETE"),
                btn -> deleteSelected()
        ).bounds(cx - 50, btnY, btnW, btnH).build());

        addRenderableWidget(Button.builder(
                CommonComponents.GUI_BACK,
                btn -> onClose()
        ).bounds(cx + btnW - 45, btnY, btnW, btnH).build());

        updateButtonState();
    }

    private void refreshSaves() {
        loading = true;
        saves.clear();
        Minecraft mc = Minecraft.getInstance();
        LevelStorageSource source = mc.getLevelSource();
        try {
            LevelStorageSource.LevelCandidates candidates = source.findLevelCandidates();
            // Summaries load on a worker thread; build the sorted list there and swap it in on the render thread.
            source.loadLevelSummaries(candidates).thenAccept(summaries -> {
                List<LevelSummary> sorted = new ArrayList<>();
                if (summaries != null) {
                    sorted.addAll(summaries);
                    sorted.sort((a, b) -> Long.compare(b.getLastPlayed(), a.getLastPlayed()));
                }
                mc.execute(() -> {
                    saves.clear();
                    saves.addAll(sorted);
                    loading = false;
                    if (selectedIndex >= saves.size()) selectedIndex = Math.max(0, saves.size() - 1);
                    updateButtonState();
                });
            }).exceptionally(ex -> {
                mc.execute(() -> {
                    loading = false;
                    updateButtonState();
                });
                return null;
            });
        } catch (Exception e) {
            loading = false;
            updateButtonState();
        }
    }

    private void updateButtonState() {
        boolean hasSave = !saves.isEmpty() && selectedIndex >= 0 && selectedIndex < saves.size();
        if (loadBtn != null) loadBtn.active = hasSave;
        if (deleteBtn != null) deleteBtn.active = hasSave;
    }

    private void loadSelected() {
        if (selectedIndex >= 0 && selectedIndex < saves.size()) {
            LevelSummary summary = saves.get(selectedIndex);
            Minecraft.getInstance().createWorldOpenFlows().loadLevel(this, summary.getLevelId());
        }
    }

    private void deleteSelected() {
        if (selectedIndex < 0 || selectedIndex >= saves.size()) return;
        LevelSummary summary = saves.get(selectedIndex);
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(
                confirm -> {
                    if (confirm) {
                        // Close the access afterwards so its session lock is released.
                        try (var access = mc.getLevelSource().createAccess(summary.getLevelId())) {
                            access.deleteLevel();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        refreshSaves();
                        mc.setScreen(this);
                    } else {
                        mc.setScreen(this);
                    }
                },
                Component.literal("Delete Adventure?"),
                Component.literal("Are you sure you want to permanently delete '" + summary.getLevelName() + "'?"),
                Component.literal("Delete"),
                CommonComponents.GUI_CANCEL
        ));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Deep Nordic slate background
        g.fillGradient(0, 0, width, height, 0xFF080808, 0xFF141210);

        int cx = width / 2;

        // Header
        g.drawCenteredString(font, "LOAD ADVENTURE", cx, 14, GOLD_COLOR);
        g.fill(cx - 100, 26, cx + 100, 27, 0x80E8C060);

        // List bounds
        int listX = cx - 140;
        int listW = 280;
        int listY = 36;
        int listH = height - 80;
        int itemH = 34;

        if (loading) {
            g.drawCenteredString(font, "Searching archives...", cx, height / 2, DIM_COLOR);
        } else if (saves.isEmpty()) {
            g.drawCenteredString(font, "No adventures recorded.", cx, height / 2, DIM_COLOR);
        } else {
            int totalH = saves.size() * itemH;
            maxScroll = Math.max(0, totalH - listH);

            g.enableScissor(listX - 2, listY, listX + listW + 4, listY + listH);

            for (int i = 0; i < saves.size(); i++) {
                LevelSummary s = saves.get(i);
                int itemY = listY + i * itemH - (int) scrollOffset;
                if (itemY + itemH < listY || itemY > listY + listH) continue;

                boolean isSel = (i == selectedIndex);
                boolean isHov = mouseX >= listX && mouseX <= listX + listW && mouseY >= itemY && mouseY < itemY + itemH;

                if (isSel) {
                    g.fill(listX, itemY, listX + listW, itemY + itemH - 2, 0x30E8C060);
                    g.fill(listX - 2, itemY + 2, listX, itemY + itemH - 4, GOLD_COLOR);
                } else if (isHov) {
                    g.fill(listX, itemY, listX + listW, itemY + itemH - 2, 0x20FFFFFF);
                }

                // World Name
                g.drawString(font, s.getLevelName(), listX + 8, itemY + 4, isSel ? BRIGHT_COLOR : TEXT_COLOR, isSel);

                // Date and ID
                String dateStr = DATE_FORMAT.format(new Date(s.getLastPlayed()));
                g.drawString(font, dateStr + " • " + s.getLevelId(), listX + 8, itemY + 16, DIM_COLOR, false);
            }

            g.disableScissor();

            // Scrollbar
            if (maxScroll > 0) {
                int scrollbarH = Math.max(16, listH * listH / totalH);
                int scrollbarY = listY + (int) ((listH - scrollbarH) * (scrollOffset / maxScroll));
                g.fill(listX + listW + 2, scrollbarY, listX + listW + 4, scrollbarY + scrollbarH, GOLD_COLOR);
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && !saves.isEmpty()) {
            int cx = width / 2;
            int listX = cx - 140;
            int listW = 280;
            int listY = 36;
            int listH = height - 80;
            int itemH = 34;

            if (mx >= listX && mx <= listX + listW && my >= listY && my <= listY + listH) {
                int clickedIndex = (int) ((my - listY + scrollOffset) / itemH);
                if (clickedIndex >= 0 && clickedIndex < saves.size()) {
                    if (selectedIndex == clickedIndex) {
                        // Double click loads save
                        loadSelected();
                    } else {
                        selectedIndex = clickedIndex;
                        updateButtonState();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (maxScroll > 0) {
            scrollOffset = Mth.clamp(scrollOffset - (float) delta * 20f, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) {
            if (!saves.isEmpty()) {
                selectedIndex = Math.max(0, selectedIndex - 1);
                updateButtonState();
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S) {
            if (!saves.isEmpty()) {
                selectedIndex = Math.min(saves.size() - 1, selectedIndex + 1);
                updateButtonState();
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            loadSelected();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }
}

