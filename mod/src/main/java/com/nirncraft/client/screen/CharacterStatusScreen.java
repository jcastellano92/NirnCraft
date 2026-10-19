package com.nirncraft.client.screen;

import com.nirncraft.core.PlayerData;
import com.nirncraft.core.Race;
import com.nirncraft.core.SkyData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import com.mojang.blaze3d.platform.InputConstants;

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
        super(Component.translatable("screen.nirncraft.character_status"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xDD000000, 0xEE0A0806);

        if (minecraft.player == null) {
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }

        PlayerData data = SkyData.get(minecraft.player);
        int cx = width / 2;
        int cy = height / 2;

        // Top Header
        g.centeredText(font, Component.translatable("screen.nirncraft.character_status_header"), cx, 16, GOLD_COLOR);
        drawHLine(g, cx - 120, cx + 120, 28, 0x90E8C060);

        // Center: 3D Player Paper Doll
        int modelX = cx;
        int modelY = height - 45;
        int modelScale = Math.min(85, height / 3);
        InventoryScreen.extractEntityInInventoryFollowsMouse(g, modelX - modelScale, modelY - modelScale * 3,
                modelX + modelScale, modelY, modelScale, 0f, mouseX, mouseY, minecraft.player);

        // Left Panel: Identity & Attributes
        int leftX = 25;
        int y = 45;
        g.text(font, minecraft.player.getName().getString().toUpperCase(java.util.Locale.ROOT), leftX, y, 0xFFFFFFFF, true);
        y += 14;

        Race race = data.getRace();
        String raceName = race != null ? race.displayName().getString() : "Unknown";
        g.text(font, Component.translatable("stat.nirncraft.race").getString() + ": " + raceName, leftX, y, TEXT_COLOR, false);
        y += 12;
        g.text(font, Component.translatable("screen.nirncraft.level", data.getLevel()), leftX, y, TEXT_COLOR, false);
        y += 18;

        drawHLine(g, leftX, leftX + 110, y, 0x60C8BC9A);
        y += 6;

        // Vitals
        g.text(font, Component.translatable("stat.nirncraft.health").getString() + ": " + (int) (minecraft.player.getHealth() * 5) + " / " + (int) (minecraft.player.getMaxHealth() * 5), leftX, y, 0xFFE06060, false);
        y += 12;
        g.text(font, Component.translatable("stat.nirncraft.magicka").getString() + ": " + (int) data.getMagicka() + " / " + (int) data.maxMagicka(), leftX, y, 0xFF6080E0, false);
        y += 12;
        g.text(font, Component.translatable("stat.nirncraft.stamina").getString() + ": " + (int) data.getStamina() + " / " + (int) data.maxStamina(), leftX, y, 0xFF60C060, false);
        y += 18;

        drawHLine(g, leftX, leftX + 110, y, 0x60C8BC9A);
        y += 6;

        // Greater Power & Cooldown
        if (race != null) {
            g.text(font, Component.translatable("screen.nirncraft.race_power"), leftX, y, GOLD_COLOR, false);
            y += 12;
            g.text(font, race.powerName().getString(), leftX + 4, y, TEXT_COLOR, false);
            y += 11;
            long now = minecraft.player.level().getGameTime();
            if (now < data.getPowerReadyAt()) {
                String hours = com.nirncraft.SkyConfig.formatTicks(data.getPowerReadyAt() - now);
                g.text(font, Component.translatable("message.nirncraft.power_cooldown", "", hours).getString().trim(), leftX + 4, y, 0xFFD06050, false);
            } else {
                g.text(font, Component.translatable("screen.nirncraft.power_ready").getString(), leftX + 4, y, 0xFF70D070, false);
            }
            y += 16;
        }

        // Right Panel: Active Effects
        int rightX = width - 145;
        y = 45;
        g.text(font, Component.translatable("screen.nirncraft.active_effects"), rightX, y, GOLD_COLOR, true);
        y += 14;

        Collection<MobEffectInstance> effects = minecraft.player.getActiveEffects();
        java.util.List<Component> hoveredEffectTip = null;
        if (effects.isEmpty()) {
            g.text(font, Component.translatable("screen.nirncraft.no_active_effects"), rightX, y, DIM_COLOR, false);
        } else {
            for (MobEffectInstance effect : effects) {
                if (y > height - 40) break;
                g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,net.minecraft.client.gui.Hud.getMobEffectSprite(effect.getEffect()),rightX,y-1,10,10);
                Component effectTitle = effect.getEffect().value().getDisplayName();
                int durationTicks = effect.getDuration();
                String durStr = effect.isInfiniteDuration() ? "\u221E"
                        : (durationTicks > 1200) ? (durationTicks / 1200) + "m" : (durationTicks / 20) + "s";
                g.text(font, effectTitle.getString() + " (" + durStr + ")", rightX + 13, y, TEXT_COLOR, false);
                if (mouseX >= rightX && mouseX <= width - 10 && mouseY >= y - 1 && mouseY < y + 11) {
                    hoveredEffectTip = tooltip(effect);
                }
                y += 12;
            }
        }

        // Bottom close hint
        g.centeredText(font, Component.translatable("gui.back"), cx, height - 16, DIM_COLOR);

        if (hoveredEffectTip != null && !hoveredEffectTip.isEmpty()) {
            g.setComponentTooltipForNextFrame(font, hoveredEffectTip, mouseX, mouseY);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** What a status effect is and does: name and level, time left, its description, and the stat changes it makes. */
    private static java.util.List<Component> tooltip(MobEffectInstance inst) {
        net.minecraft.world.effect.MobEffect effect = inst.getEffect().value();
        java.util.List<Component> out = new java.util.ArrayList<>();
        net.minecraft.ChatFormatting colour = effect.isBeneficial() ? net.minecraft.ChatFormatting.GREEN
                : effect.getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL ? net.minecraft.ChatFormatting.RED
                : net.minecraft.ChatFormatting.WHITE;
        net.minecraft.network.chat.MutableComponent title = effect.getDisplayName().copy();
        if (inst.getAmplifier() > 0) title.append(" ").append(Component.translatable("potion.potency." + inst.getAmplifier()));
        out.add(title.withStyle(colour));
        if (inst.isInfiniteDuration()) {
            out.add(Component.translatable("screen.nirncraft.effect.permanent").withStyle(net.minecraft.ChatFormatting.GRAY));
        } else if (!effect.isInstantaneous()) {
            int secs = inst.getDuration() / 20;
            out.add(Component.translatable("screen.nirncraft.effect.time_left", String.format(java.util.Locale.ROOT, "%d:%02d", secs / 60, secs % 60))
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(effect);
        if (id != null) {
            String key = "effect." + id.getNamespace() + "." + id.getPath() + ".desc";
            if (net.minecraft.locale.Language.getInstance().has(key)) {
                out.add(Component.translatable(key).withStyle(net.minecraft.ChatFormatting.WHITE));
            }
        }
        effect.createModifiers(inst.getAmplifier(),(attribute,modifier)->{
            double value=modifier.amount();boolean percent=modifier.operation()!=net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE;
            double shown=percent?value*100:value;
            String number=String.format(java.util.Locale.ROOT,Math.abs(shown)==Math.rint(Math.abs(shown))?"%.0f":"%.1f",Math.abs(shown));
            out.add(Component.literal((shown>=0?"+":"-")+number+(percent?"% ":" ")).append(Component.translatable(attribute.value().getDescriptionId()))
                .withStyle((shown>=0)==effect.isBeneficial()?net.minecraft.ChatFormatting.BLUE:net.minecraft.ChatFormatting.RED));
        });
        return out;
    }

    private void drawHLine(GuiGraphicsExtractor g, int x1, int x2, int y, int color) {
        g.fill(x1, y, x2, y + 1, color);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_TAB || key == InputConstants.KEY_C) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}

