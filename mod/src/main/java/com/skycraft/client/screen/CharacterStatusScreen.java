package com.skycraft.client.screen;

import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import org.lwjgl.glfw.GLFW;

import java.util.Collection;

/**
 * Skyrim-styled Character and Status Screen:
 * Displays 3D player paper doll, active effects, standing stone, attributes, level, power cooldowns, bounty.
 */
public class CharacterStatusScreen extends Screen {
    private static final int BG_COLOR = 0xC8050505;
    private static final int GOLD_COLOR = 0xFFE8C060;
    private static final int TEXT_COLOR = 0xFFE8E0C8;
    private static final int DIM_COLOR = 0xFF8A8478;

    public CharacterStatusScreen() {
        super(Component.translatable("screen.skycraft.character_status"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xDD000000, 0xEE0A0806);

        if (minecraft.player == null) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        PlayerData data = SkyData.get(minecraft.player);
        int cx = width / 2;
        int cy = height / 2;

        // Top Header
        g.drawCenteredString(font, Component.translatable("screen.skycraft.character_status_header"), cx, 16, GOLD_COLOR);
        drawHLine(g, cx - 120, cx + 120, 28, 0x90E8C060);

        // Center: 3D Player Paper Doll
        int modelX = cx;
        int modelY = height - 45;
        int modelScale = Math.min(85, height / 3);
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, modelX, modelY, modelScale, (float) (modelX - mouseX), (float) (modelY - 50 - mouseY), minecraft.player);

        // Left Panel: Identity & Attributes
        int leftX = 25;
        int y = 45;
        g.drawString(font, minecraft.player.getName().getString().toUpperCase(java.util.Locale.ROOT), leftX, y, 0xFFFFFFFF, true);
        y += 14;

        Race race = data.getRace();
        String raceName = race != null ? race.displayName().getString() : "Unknown";
        g.drawString(font, Component.translatable("stat.skycraft.race").getString() + ": " + raceName, leftX, y, TEXT_COLOR, false);
        y += 12;
        g.drawString(font, Component.translatable("screen.skycraft.level", data.getLevel()), leftX, y, TEXT_COLOR, false);
        y += 18;

        drawHLine(g, leftX, leftX + 110, y, 0x60C8BC9A);
        y += 6;

        // Vitals
        g.drawString(font, Component.translatable("stat.skycraft.health").getString() + ": " + (int) (minecraft.player.getHealth() * 5) + " / " + (int) (minecraft.player.getMaxHealth() * 5), leftX, y, 0xFFE06060, false);
        y += 12;
        g.drawString(font, Component.translatable("stat.skycraft.magicka").getString() + ": " + (int) data.getMagicka() + " / " + (int) data.maxMagicka(), leftX, y, 0xFF6080E0, false);
        y += 12;
        g.drawString(font, Component.translatable("stat.skycraft.stamina").getString() + ": " + (int) data.getStamina() + " / " + (int) data.maxStamina(), leftX, y, 0xFF60C060, false);
        y += 18;

        drawHLine(g, leftX, leftX + 110, y, 0x60C8BC9A);
        y += 6;

        // Greater Power & Cooldown
        if (race != null) {
            g.drawString(font, Component.translatable("screen.skycraft.race_power"), leftX, y, GOLD_COLOR, false);
            y += 12;
            g.drawString(font, race.powerName().getString(), leftX + 4, y, TEXT_COLOR, false);
            y += 11;
            long now = minecraft.player.level().getGameTime();
            if (now < data.getPowerReadyAt()) {
                String hours = com.skycraft.SkyConfig.formatTicks(data.getPowerReadyAt() - now);
                g.drawString(font, Component.translatable("message.skycraft.power_cooldown", "", hours).getString().trim(), leftX + 4, y, 0xFFD06050, false);
            } else {
                g.drawString(font, Component.translatable("screen.skycraft.power_ready").getString(), leftX + 4, y, 0xFF70D070, false);
            }
            y += 16;
        }

        // Right Panel: Active Effects
        int rightX = width - 145;
        y = 45;
        g.drawString(font, Component.translatable("screen.skycraft.active_effects"), rightX, y, GOLD_COLOR, true);
        y += 14;

        Collection<MobEffectInstance> effects = minecraft.player.getActiveEffects();
        if (effects.isEmpty()) {
            g.drawString(font, Component.translatable("screen.skycraft.no_active_effects"), rightX, y, DIM_COLOR, false);
        } else {
            for (MobEffectInstance effect : effects) {
                if (y > height - 40) break;
                Component effectTitle = effect.getEffect().getDisplayName();
                int durationTicks = effect.getDuration();
                String durStr = (durationTicks > 1200) ? (durationTicks / 1200) + "m" : (durationTicks / 20) + "s";
                g.drawString(font, effectTitle.getString() + " (" + durStr + ")", rightX, y, TEXT_COLOR, false);
                y += 12;
            }
        }

        // Bottom close hint
        g.drawCenteredString(font, Component.translatable("gui.back"), cx, height - 16, DIM_COLOR);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawHLine(GuiGraphics g, int x1, int x2, int y, int color) {
        g.fill(x1, y, x2, y + 1, color);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_C) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}

