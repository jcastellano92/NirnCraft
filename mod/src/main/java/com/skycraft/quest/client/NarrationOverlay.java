package com.skycraft.quest.client;

import com.skycraft.Skycraft;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Narration cards for the opening: a short passage in a dark band in the lower third of the screen. The text is wrapped
 * to the GUI width (never clipped, whatever the GUI scale or window size), at most four lines, fading in and out.
 * Sent by the server ({@code QuestPackets.NarrationCard}); one card at a time, a new one replaces the old.
 */
public final class NarrationOverlay {
    private static final int MAX_LINES = 4;
    private static final long FADE_MS = 700;

    private static Component text;
    private static long startMs;
    private static long stayMs;

    private NarrationOverlay() {}

    public static void show(Component card, int stayTicks) {
        text = card;
        startMs = Util.getMillis();
        stayMs = Math.max(1000L, stayTicks * 50L);
    }

    public static void clear() {
        text = null;
    }

    @Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void overlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("narration", NarrationOverlay::render);
        }
    }

    @Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            clear();
        }
    }

    static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        if (text == null) return;
        long age = Util.getMillis() - startMs;
        long total = stayMs + 2 * FADE_MS;
        if (age >= total) {
            text = null;
            return;
        }
        float alpha = age < FADE_MS ? age / (float) FADE_MS : age > total - FADE_MS ? (total - age) / (float) FADE_MS : 1f;
        Font font = Minecraft.getInstance().font;
        // wrap to the screen; widen the column a little if it would need more than four lines
        int wrap = Mth.clamp(width - 48, 120, 360);
        List<FormattedCharSequence> lines = font.split(text, wrap);
        if (lines.size() > MAX_LINES) {
            wrap = Math.max(120, width - 24);
            lines = font.split(text, wrap);
        }
        int shown = Math.min(MAX_LINES, lines.size());
        int lineH = font.lineHeight + 3;
        int boxH = shown * lineH + 18;
        int widest = 0;
        for (int i = 0; i < shown; i++) widest = Math.max(widest, font.width(lines.get(i)));
        int boxW = Math.min(width - 8, widest + 36);
        int x0 = (width - boxW) / 2;
        int y0 = Mth.clamp((int) (height * 0.70f), 4, Math.max(4, height - boxH - 8));
        int a = (int) (alpha * 170);
        g.fill(x0, y0, x0 + boxW, y0 + boxH, (a << 24));
        g.fill(x0, y0, x0 + boxW, y0 + 1, ((int) (alpha * 120) << 24) | 0xB89A5E);
        g.fill(x0, y0 + boxH - 1, x0 + boxW, y0 + boxH, ((int) (alpha * 120) << 24) | 0xB89A5E);
        int ta = Math.max(5, (int) (alpha * 255));
        for (int i = 0; i < shown; i++) {
            FormattedCharSequence line = lines.get(i);
            g.drawString(font, line, (width - font.width(line)) / 2, y0 + 9 + i * lineH, (ta << 24) | 0xEDE6D3, true);
        }
    }
}
