package com.skycraft.client.screen;

import com.skycraft.client.CharacterClient;
import com.skycraft.inventory.client.InventoryClient;
import com.skycraft.magic.client.MagicClient;
import com.skycraft.quest.client.JournalClient;
import com.skycraft.world.client.MapClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Skyrim four-way Hub Menu (Tab key):
 * <p>
 * Up: Skills (North/Heavens)
 * Left: Items / Inventory
 * Right: Magic
 * Down: Map
 * Center / Key J: Journal
 * </p>
 */
public class HubScreen extends Screen {
    private static final int BG_COLOR = 0xC0050505;
    private static final int TEXT_COLOR = 0xFFE8E0C8;
    private static final int HIGHLIGHT_COLOR = 0xFFFFFFFF;
    private static final int DIM_COLOR = 0xFF8A8478;
    private static final int GOLD_COLOR = 0xFFE8C060;

    private int hoveredSection = -1; // 0=Skills, 1=Items, 2=Magic, 3=Map, 4=Character/Journal

    public HubScreen() {
        super(Component.translatable("screen.skycraft.hub"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xD0000000, 0xE8080604);

        int cx = width / 2;
        int cy = height / 2;

        // Determine hovered direction from mouse relative to center
        int dx = mouseX - cx;
        int dy = mouseY - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);

        hoveredSection = -1;
        if (dist > 25 && dist < 120) {
            double angle = Math.atan2(dy, dx); // radians, -pi to pi
            // -PI/2 is UP, 0 is RIGHT, PI/2 is DOWN, PI/-PI is LEFT
            if (angle > -3 * Math.PI / 4 && angle < -Math.PI / 4) {
                hoveredSection = 0; // SKILLS (UP)
            } else if (angle >= -Math.PI / 4 && angle <= Math.PI / 4) {
                hoveredSection = 2; // MAGIC (RIGHT)
            } else if (angle > Math.PI / 4 && angle < 3 * Math.PI / 4) {
                hoveredSection = 3; // MAP (DOWN)
            } else {
                hoveredSection = 1; // ITEMS (LEFT)
            }
        } else if (dist <= 25) {
            hoveredSection = 4; // CENTER (CHARACTER / JOURNAL)
        }

        // Draw ornamental compass cross lines
        drawFadingLine(g, cx, cy - 85, cx, cy - 15, hoveredSection == 0);
        drawFadingLine(g, cx - 85, cy, cx - 15, cy, hoveredSection == 1);
        drawFadingLine(g, cx + 15, cy, cx + 85, cy, hoveredSection == 2);
        drawFadingLine(g, cx, cy + 15, cx, cy + 85, hoveredSection == 3);

        // Center diamond
        int centerColor = (hoveredSection == 4) ? GOLD_COLOR : 0xFFA09070;
        drawDiamond(g, cx, cy, 6, centerColor);

        // Draw Section Labels
        drawOption(g, font, Component.translatable("screen.skycraft.hub_skills"), cx, cy - 95, hoveredSection == 0, 0);
        drawOption(g, font, Component.translatable("screen.skycraft.hub_items"), cx - 100, cy - 4, hoveredSection == 1, 1);
        drawOption(g, font, Component.translatable("screen.skycraft.hub_magic"), cx + 100, cy - 4, hoveredSection == 2, 2);
        drawOption(g, font, Component.translatable("screen.skycraft.hub_map"), cx, cy + 90, hoveredSection == 3, 0);

        // Center label: Level & Character Status
        if (minecraft.player != null) {
            var data = com.skycraft.core.SkyData.get(minecraft.player);
            String centerText = "LVL " + data.getLevel();
            g.centeredText(font, centerText, cx, cy + 10, (hoveredSection == 4) ? GOLD_COLOR : DIM_COLOR);
            if (hoveredSection == 4) {
                g.centeredText(font, Component.translatable("screen.skycraft.hub_character"), cx, cy - 16, HIGHLIGHT_COLOR);
            }
        }

        // Hint bar at the bottom
        g.centeredText(font, Component.translatable("screen.skycraft.hub_hint"), cx, height - 20, DIM_COLOR);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawOption(GuiGraphicsExtractor g, Font font, Component label, int x, int y, boolean hovered, int align) {
        int color = hovered ? HIGHLIGHT_COLOR : TEXT_COLOR;
        String text = label.getString().toUpperCase(java.util.Locale.ROOT);
        int tw = font.width(text);
        int drawX = (align == 0) ? x - tw / 2 : (align == 1) ? x - tw : x;
        g.text(font, text, drawX, y, color, true);

        if (hovered) {
            // Draw a subtle gold underline
            g.fill(drawX - 2, y + font.lineHeight + 1, drawX + tw + 2, y + font.lineHeight + 2, GOLD_COLOR);
        }
    }

    private void drawDiamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(cx - w, cy + i, cx + w + 1, cy + i + 1, color);
        }
    }

    private void drawFadingLine(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, boolean highlight) {
        int color = highlight ? GOLD_COLOR : 0x80807868;
        if (x1 == x2) {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            g.fill(x1, minY, x1 + 1, maxY, color);
        } else {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            g.fill(minX, y1, maxX, y1 + 1, color);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0 && hoveredSection >= 0) {
            selectSection(hoveredSection);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_W) {
            selectSection(0);
            return true;
        }
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_A) {
            selectSection(1);
            return true;
        }
        if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_D) {
            selectSection(2);
            return true;
        }
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_S) {
            selectSection(3);
            return true;
        }
        if (com.skycraft.client.SkyKeys.JOURNAL.matches(event) || key == InputConstants.KEY_J) {
            JournalClient.open();
            return true;
        }
        if (key == InputConstants.KEY_C || key == InputConstants.KEY_RETURN) {
            selectSection(4);
            return true;
        }
        if (com.skycraft.client.SkyKeys.HUB.matches(event) || key == InputConstants.KEY_TAB) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void selectSection(int section) {
        switch (section) {
            case 0 -> minecraft.gui.setScreen(new SkillsScreen());
            case 1 -> InventoryClient.open();
            case 2 -> MagicClient.openMagicMenu();
            case 3 -> MapClient.openMap();
            case 4 -> CharacterClient.open();
        }
    }
}

