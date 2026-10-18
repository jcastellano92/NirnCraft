package com.skycraft.inventory.client;

import com.skycraft.core.HudStack;
import com.skycraft.inventory.InventoryConfig;
import com.skycraft.inventory.ItemWeights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * "Carry 312/300" above the stamina bar (bottom right), only while close to or over the carry capacity. Takes its slot
 * in the {@link HudStack} bottom-right column, above the magic module's elements (shout words, right-hand spell).
 */
public final class CarryHud {
    private CarryHud() {}

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.hud.isHidden() || !(mc.gameMode!=null&&mc.gameMode.hasExperience()) || player.isCreative() || player.isSpectator()) return;
        if (!ClientInventoryHandlers.received || !ClientInventoryHandlers.enabled) return;
        boolean show;
        double threshold;
        try {
            show = InventoryConfig.CARRY_HUD.get();
            threshold = InventoryConfig.CARRY_HUD_THRESHOLD.get();
        } catch (IllegalStateException e) {
            show = true;
            threshold = 0.9;
        }
        if (!show) return;
        float cur = ClientInventoryHandlers.current;
        float cap = ClientInventoryHandlers.capacity;
        boolean over = ClientInventoryHandlers.over; // too heavy to run
        boolean heavy = cur > cap;                   // slower (graded), see CarryWeight.speedFactor
        if (!heavy && cur < cap * threshold) return;

        Font font = mc.font;
        Component text = heavy
                ? Component.translatable("inventory.skycraft.hud.carry_slow", ItemWeights.format(cur), ItemWeights.format(cap),
                        Math.round((1f - com.skycraft.inventory.CarryWeight.speedFactor(cur, cap)) * 100f))
                : Component.translatable("inventory.skycraft.hud.carry", ItemWeights.format(cur), ItemWeights.format(cap));
        // one slot in the bottom-right stack, above the voice words and the right-hand spell (whatever is drawn there)
        int y = HudStack.reserve(HudStack.Zone.BOTTOM_RIGHT, HudStack.BR_CARRY, 10, height);
        int x = width - 12 - font.width(text);
        int color;
        if (over) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 300.0);
            int r = (int) (0xE0 * pulse), gg = (int) (0x50 * pulse), b = (int) (0x50 * pulse);
            color = 0xFF000000 | r << 16 | gg << 8 | b;
        } else if (heavy) {
            color = 0xFFE8A050; // slower, but still able to run
        } else {
            color = 0xFFE8D9A0;
        }
        g.text(font, text, x, y, color, true);
    }
}
