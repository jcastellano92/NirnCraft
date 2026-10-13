package com.skycraft.quest.client;

import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.story.IntroPackets;
import com.skycraft.quest.story.IntroState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * "The valley wakes": shown after the character is made, while the chunks around the cottage and the town load and the
 * server prepares the scene (the cleared line of sight, the path). It says "loaded" to the server once the chunks it
 * was told about are in (or its own time limit passes) and fades away when the server moves the player on, so the
 * world is never seen popping in. Not closable by the player.
 */
final class IntroLoadingScreen extends Screen {
    private final long since;
    private boolean sent;
    private long sentAt;
    private long fadeStart = -1;
    private float shown;
    private float clientFrac;

    IntroLoadingScreen(long since) {
        super(Component.translatable("quest.skycraft.intro2.loading.title"));
        this.since = since;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Fraction of the chunks the server asked for that this client already has. */
    private float chunkFraction(Minecraft mc, IntroPackets.State info) {
        if (mc.level == null || mc.player == null) return 0f;
        int view = mc.options.renderDistance().get();
        int r = Math.max(2, Math.min(info.chunkRadius(), view));
        int pcx = mc.player.blockPosition().getX() >> 4, pcz = mc.player.blockPosition().getZ() >> 4;
        int total = 0, have = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + r) continue;
                total++;
                if (mc.level.getChunkSource().hasChunk(pcx + dx, pcz + dz)) have++;
            }
        }
        int tr = Math.max(1, info.townRadius() >> 4);
        for (int dx = -tr; dx <= tr; dx++) {
            for (int dz = -tr; dz <= tr; dz++) {
                int cx = info.townCx() + dx, cz = info.townCz() + dz;
                if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) > view) continue; // beyond the view distance: never comes
                total++;
                if (mc.level.getChunkSource().hasChunk(cx, cz)) have++;
            }
        }
        return total == 0 ? 1f : have / (float) total;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        IntroPackets.State info = IntroClient.loadingInfo();
        IntroState st = IntroClient.state();
        long now = System.currentTimeMillis();
        if (st != IntroState.LOADING) {
            if (fadeStart < 0) fadeStart = now;
            if (now - fadeStart > 900) onClose();
            return;
        }
        if (info == null) return;
        clientFrac = chunkFraction(mc, info);
        float server = Mth.clamp(info.progress(), 0f, 1f);
        float target = 0.5f * clientFrac + 0.5f * server;
        shown += (target - shown) * 0.2f;
        boolean timedOut = now - since > info.timeoutSeconds() * 1000L;
        boolean ready = clientFrac >= 0.98f && server >= 0.999f;
        if ((ready || timedOut) && (!sent || now - sentAt > 2000)) {
            sent = true;
            sentAt = now;
            SkyNetwork.sendToServer(new IntroPackets.Loaded());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        float fadeIn = Mth.clamp((now - since) / 400f, 0f, 1f);
        float fadeOut = fadeStart < 0 ? 0f : Mth.clamp((now - fadeStart) / 900f, 0f, 1f);
        float a = fadeIn * (1f - fadeOut);
        int alpha = (int) (a * 255);
        g.fill(0, 0, width, height, (alpha << 24) | 0x0A0806);
        if (alpha < 8) return;
        int ta = Math.max(8, alpha);
        int cx = width / 2, cy = height / 2;
        g.drawCenteredString(font, Component.translatable("quest.skycraft.intro2.loading.title"), cx, cy - 34, (ta << 24) | 0xE8D9A8);
        g.drawCenteredString(font, Component.translatable("quest.skycraft.intro2.loading.sub"), cx, cy - 18, (ta << 24) | 0xA89C80);
        int bw = Math.min(220, width - 40), bx = cx - bw / 2, by = cy + 4;
        g.fill(bx - 1, by - 1, bx + bw + 1, by + 7, (ta << 24) | 0x4A3E26);
        g.fill(bx, by, bx + bw, by + 6, (ta << 24) | 0x16120C);
        int fill = (int) (bw * Mth.clamp(shown, 0f, 1f));
        g.fill(bx, by, bx + fill, by + 6, (ta << 24) | 0xC9A650);
        g.drawCenteredString(font, Component.translatable("quest.skycraft.intro2.loading.tip"), cx, by + 18, (ta << 24) | 0x7C735E);
    }
}
