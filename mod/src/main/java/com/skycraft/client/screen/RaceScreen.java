package com.skycraft.client.screen;

import com.skycraft.core.Race;
import com.skycraft.core.Skill;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Skyrim-styled Character Creator:
 * Choose race with live 3D preview model, scrolling descriptions/bonuses, no "Decide later",
 * and fully responsive at 427x267 (Steam Deck) scaled resolution.
 */
public class RaceScreen extends Screen {
    private static final int TEXT_COLOR = 0xFFE8E0C8;
    private static final int BRIGHT_COLOR = 0xFFFFFFFF;
    private static final int GOLD_COLOR = 0xFFE8C060;
    private static final int DIM_COLOR = 0xFF8A8478;

    private Race selected = Race.NORD;
    private float descScroll = 0f;
    private int maxDescScroll = 0;
    private float modelYawOffset = 0f;
    private boolean isDraggingModel = false;
    private double lastDragX = 0;

    public RaceScreen() {
        super(Component.translatable("screen.skycraft.race"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // First-time character creation cannot be cancelled without choosing a race
        return false;
    }

    @Override
    protected void init() {
        descScroll = 0f;
        com.skycraft.client.RaceSkins.setPreviewRace(selected);
    }

    @Override
    public void removed() {
        super.removed();
        com.skycraft.client.RaceSkins.setPreviewRace(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Dark vignette background
        g.fillGradient(0, 0, width, height, 0xF0050505, 0xF0181410);
        com.skycraft.inventory.client.SkyUi.smoke(g, width, height, (net.minecraft.Util.getMillis() % 1_000_000L) / 1000f, 0.7f);
        // a soft pool of light where the character stands
        int glowCx = (12 + Math.min(95, width / 4) + width - 164) / 2;
        for (int i = 6; i > 0; i--) {
            int w = 12 * i;
            g.fillGradient(glowCx - w, height / 3, glowCx + w, height - 30, 0, 0x08E8D8B0);
        }

        // Header
        g.drawCenteredString(font, com.skycraft.inventory.client.SkyUi.caps(Component.translatable("screen.skycraft.race_header")), width / 2, 6, GOLD_COLOR);
        com.skycraft.inventory.client.SkyUi.ornament(g, width / 2, 18, Math.min(90, width / 5), 0xB0C8BC9A);

        // Layout dimensions
        int listWidth = Math.min(95, width / 4);
        int listX = 12;
        int listStartY = 24;
        int itemHeight = 18;

        // Render Race List on the Left
        for (int i = 0; i < Race.VALUES.length; i++) {
            Race r = Race.VALUES[i];
            int itemY = listStartY + i * itemHeight;
            boolean isSel = (r == selected);
            boolean isHov = mouseX >= listX && mouseX <= listX + listWidth && mouseY >= itemY && mouseY < itemY + itemHeight;

            if (isSel) {
                // Skyrim selection: smoky band between fading gold lines, a diamond at the left
                g.fillGradient(listX - 2, itemY, listX + listWidth + 2, itemY + itemHeight - 2, 0x60000000, 0x90000000);
                com.skycraft.inventory.client.SkyUi.hline(g, listX - 2, listX + listWidth + 2, itemY, 0xA0);
                com.skycraft.inventory.client.SkyUi.hline(g, listX - 2, listX + listWidth + 2, itemY + itemHeight - 3, 0xA0);
                com.skycraft.inventory.client.SkyUi.diamond(g, listX - 4, itemY + itemHeight / 2 - 1, 2, GOLD_COLOR);
            } else if (isHov) {
                g.fill(listX - 2, itemY, listX + listWidth + 2, itemY + itemHeight - 2, 0x30FFFFFF);
            }

            int color = isSel ? BRIGHT_COLOR : (isHov ? TEXT_COLOR : DIM_COLOR);
            g.drawString(font, r.displayName(), listX + 4, itemY + 4, color, isSel);
        }

        // Right Panel: Race Info & Attributes (Scrollable)
        int infoWidth = 150;
        int infoX = width - infoWidth - 14;
        int infoY = 24;
        int infoHeight = height - 60;

        // Center: Live 3D Character Preview Model
        int modelCenterX = (listX + listWidth + infoX) / 2;
        int modelScale = Math.min(65, (int) (height * 0.32f));
        int modelCenterY = height - 38;

        if (minecraft.player != null) {
            minecraft.player.setInvisible(false);
            minecraft.player.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
            com.skycraft.client.RaceSkins.setPreviewRace(selected);
            // Skyrim circular stone dais under feet
            int daisRadius = (int) (modelScale * 0.55f);
            g.fill(modelCenterX - daisRadius, modelCenterY - 3, modelCenterX + daisRadius, modelCenterY + 2, 0x50000000);
            g.fill(modelCenterX - daisRadius + 4, modelCenterY - 2, modelCenterX + daisRadius - 4, modelCenterY + 1, 0x80353028);
            drawHLine(g, modelCenterX - daisRadius + 2, modelCenterX + daisRadius - 2, modelCenterY - 1, 0x60E8C060);

            // Compute head eye position and mouse pitch/yaw angles
            int headY = modelCenterY - (int) (1.55f * modelScale);
            float yawLook = (float) (modelCenterX - mouseX) + modelYawOffset;
            float pitchLook = (float) (headY - mouseY);

            InventoryScreen.renderEntityInInventoryFollowsMouse(g, modelCenterX, modelCenterY, modelScale, yawLook, pitchLook, minecraft.player);

            // Subtle rotation helper hint
            g.drawCenteredString(font, Component.literal("⟳ Drag to Rotate"), modelCenterX, height - 16, 0x809E9689);
        }

        // Info Background Panel
        g.fill(infoX - 4, infoY - 4, infoX + infoWidth + 4, infoY + infoHeight + 4, 0x85000000);
        drawHLine(g, infoX - 4, infoX + infoWidth + 4, infoY - 4, 0x70C8BC9A);
        drawHLine(g, infoX - 4, infoX + infoWidth + 4, infoY + infoHeight + 4, 0x70C8BC9A);

        // Scissor or clip drawing for scroll area
        g.enableScissor(infoX - 4, infoY, infoX + infoWidth + 4, infoY + infoHeight);

        int currentY = infoY + 2 - (int) descScroll;

        // Race Name
        g.drawString(font, selected.displayName().getString().toUpperCase(java.util.Locale.ROOT), infoX, currentY, GOLD_COLOR, true);
        currentY += 14;

        // Description text
        List<FormattedCharSequence> descLines = font.split(selected.description(), infoWidth);
        for (FormattedCharSequence line : descLines) {
            g.drawString(font, line, infoX, currentY, TEXT_COLOR, false);
            currentY += 10;
        }
        currentY += 8;

        // Skills Header & List
        g.drawString(font, Component.translatable("screen.skycraft.race_skills"), infoX, currentY, GOLD_COLOR, false);
        currentY += 12;
        g.drawString(font, selected.major.displayName().copy().append(" +10"), infoX + 6, currentY, BRIGHT_COLOR, false);
        currentY += 10;
        for (Skill s : selected.minors) {
            g.drawString(font, s.displayName().copy().append(" +5"), infoX + 6, currentY, TEXT_COLOR, false);
            currentY += 10;
        }
        currentY += 8;

        // Greater Power
        g.drawString(font, Component.translatable("screen.skycraft.race_power"), infoX, currentY, GOLD_COLOR, false);
        currentY += 12;
        g.drawString(font, selected.powerName(), infoX + 6, currentY, BRIGHT_COLOR, false);
        currentY += 10;
        List<FormattedCharSequence> powerLines = font.split(Component.translatable("power.skycraft." + selected.power + ".desc"), infoWidth - 6);
        for (FormattedCharSequence line : powerLines) {
            g.drawString(font, line, infoX + 6, currentY, DIM_COLOR, false);
            currentY += 10;
        }

        // Calculate max scroll
        int contentHeight = currentY + (int) descScroll - infoY;
        maxDescScroll = Math.max(0, contentHeight - infoHeight);

        g.disableScissor();

        // Scrollbar indicator if needed
        if (maxDescScroll > 0) {
            int scrollbarH = Math.max(12, infoHeight * infoHeight / contentHeight);
            int scrollbarY = infoY + (int) ((infoHeight - scrollbarH) * (descScroll / maxDescScroll));
            g.fill(infoX + infoWidth + 2, scrollbarY, infoX + infoWidth + 4, scrollbarY + scrollbarH, GOLD_COLOR);
        }

        // Bottom Confirm Button
        int btnW = 120;
        int btnH = 20;
        int btnX = width - btnW - 14;
        int btnY = height - 26;
        boolean btnHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;

        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnHov ? 0xFF2A261E : 0xFF181512);
        drawHLine(g, btnX, btnX + btnW, btnY, btnHov ? GOLD_COLOR : 0xFF8A7F66);
        drawHLine(g, btnX, btnX + btnW, btnY + btnH - 1, btnHov ? GOLD_COLOR : 0xFF8A7F66);
        g.drawCenteredString(font, Component.translatable("screen.skycraft.race_confirm"), btnX + btnW / 2, btnY + 6, btnHov ? BRIGHT_COLOR : TEXT_COLOR);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawHLine(GuiGraphics g, int x1, int x2, int y, int color) {
        g.fill(x1, y, x2, y + 1, color);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            // Check race list items
            int listWidth = Math.min(95, width / 4);
            int listX = 12;
            int listStartY = 24;
            int itemHeight = 18;

            for (int i = 0; i < Race.VALUES.length; i++) {
                int itemY = listStartY + i * itemHeight;
                if (mx >= listX && mx <= listX + listWidth && my >= itemY && my < itemY + itemHeight) {
                    selected = Race.VALUES[i];
                    descScroll = 0f;
                    com.skycraft.client.RaceSkins.setPreviewRace(selected);
                    return true;
                }
            }

            // Check Confirm Button
            int btnW = 120;
            int btnH = 20;
            int btnX = width - btnW - 14;
            int btnY = height - 26;
            if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                confirmSelection();
                return true;
            }

            // Check Character Model Click to start drag rotation
            int infoWidth = 150;
            int infoX = width - infoWidth - 14;
            int modelCenterX = (listX + listWidth + infoX) / 2;
            int modelScale = Math.min(65, (int) (height * 0.32f));
            int modelCenterY = height - 38;
            if (mx >= modelCenterX - 55 && mx <= modelCenterX + 55 && my >= modelCenterY - (int) (1.8f * modelScale) && my <= modelCenterY + 10) {
                isDraggingModel = true;
                lastDragX = mx;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (isDraggingModel && button == 0) {
            modelYawOffset += (float) (mx - lastDragX) * 1.8f;
            lastDragX = mx;
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            isDraggingModel = false;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (maxDescScroll > 0) {
            descScroll = Mth.clamp(descScroll - (float) delta * 14f, 0, maxDescScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            confirmSelection();
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) {
            int prev = Math.floorMod(selected.ordinal() - 1, Race.VALUES.length);
            selected = Race.VALUES[prev];
            descScroll = 0f;
            com.skycraft.client.RaceSkins.setPreviewRace(selected);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S) {
            int next = Math.floorMod(selected.ordinal() + 1, Race.VALUES.length);
            selected = Race.VALUES[next];
            descScroll = 0f;
            com.skycraft.client.RaceSkins.setPreviewRace(selected);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void confirmSelection() {
        SkyNetwork.sendToServer(new CorePackets.ChooseRace(selected.id()));
        onClose();
    }
}
