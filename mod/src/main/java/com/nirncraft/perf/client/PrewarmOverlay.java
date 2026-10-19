package com.nirncraft.perf.client;

import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** The "Travelling to ..." progress bar shown while the server loads the destination of a fast travel. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class PrewarmOverlay {
    private static int ready, total;
    private static Component label = Component.empty();
    private static long lastUpdate;

    private PrewarmOverlay() {}

    public static void show(int r, int t, Component l) {
        ready = r;
        total = t;
        label = l;
        lastUpdate = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        if (total <= 0 || System.currentTimeMillis() - lastUpdate > 4000) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphicsExtractor g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        g.fill(0, 0, w, h, 0xB0000000);
        Component title = Component.translatable("perf.nirncraft.travelling", label);
        g.centeredText(mc.font, title, w / 2, h / 2 - 16, 0xFFE8D8A8);
        int bw = 160, bx = w / 2 - bw / 2, by = h / 2;
        g.fill(bx - 1, by - 1, bx + bw + 1, by + 7, 0xFF6A5A3A);
        g.fill(bx, by, bx + bw, by + 6, 0xFF1A1610);
        g.fill(bx, by, bx + bw * Math.min(ready, total) / total, by + 6, 0xFFC9A44C);
        g.centeredText(mc.font, Component.translatable("perf.nirncraft.travelling.progress", ready, total), w / 2, by + 12, 0xFFB8B0A0);
    }
}
