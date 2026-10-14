package com.skycraft.lore.client;

import com.skycraft.core.HudStack;
import com.skycraft.core.SkyData;
import com.skycraft.lore.StandingStone;
import com.skycraft.lore.StandingStones;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import java.util.List;

/**
 * A small banner (bottom left) shown for a few seconds whenever the active Standing Stone changes: the sign's
 * constellation, its name and its blessing.
 */
public final class StoneHud {
    private static final long SHOW_MS = 9000;
    private static final int SETTLE_TICKS = 60;

    private static String last = "";
    private static int settle = 0;
    private static StandingStone shown;
    private static long shownAt;

    private StoneHud() {}

    static void reset() {
        last = "";
        settle = 0;
        shown = null;
    }

    /** Watches the synced stone id; ignores the first few seconds after joining (initial sync). */
    static void tick(Minecraft mc) {
        String now = SkyData.get(mc.player).module(StandingStones.MODULE).getString(StandingStones.KEY);
        if (settle < SETTLE_TICKS) {
            settle++;
            last = now;
            return;
        }
        if (!now.equals(last)) {
            last = now;
            StandingStone s = StandingStone.byId(now);
            if (s != null) {
                shown = s;
                shownAt = System.currentTimeMillis();
            }
        }
    }

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (shown == null || mc.player == null || mc.options.hideGui) return;
        long age = System.currentTimeMillis() - shownAt;
        if (age > SHOW_MS) {
            shown = null;
            return;
        }
        float fade = age < 400 ? age / 400f : age > SHOW_MS - 1000 ? (SHOW_MS - age) / 1000f : 1f;
        int alpha = Math.max(8, Math.min(255, (int) (fade * 255)));
        Font font = mc.font;
        int w = 190;
        List<FormattedCharSequence> desc = font.split(shown.description(), w - 44);
        int h = Math.max(52, 26 + desc.size() * 10);
        int x = 8;
        // above the racial power, the left spell and the other bottom-left lines
        int y = HudStack.reserve(HudStack.Zone.BOTTOM_LEFT, HudStack.BL_STONE, h, height);

        g.fill(x, y, x + w, y + h, (int) (alpha * 0.75f) << 24);
        g.fill(x, y, x + w, y + 1, alpha << 24 | 0x8A7F66);
        g.fill(x, y + h - 1, x + w, y + h, alpha << 24 | 0x8A7F66);
        Constellations.draw(g, shown, x + 6, y + (h - 44) / 2, 2, alpha);

        int tx = x + 36;
        g.drawString(font, Component.translatable("hud.skycraft.lore.active_stone"), tx, y + 4, alpha << 24 | 0xBDB59E, false);
        g.drawString(font, shown.stoneName(), tx, y + 14, alpha << 24 | shown.color, true);
        int ly = y + 26;
        for (FormattedCharSequence line : desc) {
            g.drawString(font, line, tx, ly, alpha << 24 | 0xD8D0B8, false);
            ly += 10;
        }
    }
}
