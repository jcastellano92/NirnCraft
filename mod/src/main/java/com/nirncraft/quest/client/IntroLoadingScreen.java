package com.nirncraft.quest.client;

import com.nirncraft.network.SkyNetwork;
import com.nirncraft.quest.story.IntroPackets;
import com.nirncraft.quest.story.IntroState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

    /** The valley takes at least this long to "wake", whatever is already loaded (so it is read, and the far chunks arrive). */
    private static final long MIN_MILLIS = 6000L;

    IntroLoadingScreen() {
        super(Component.translatable("quest.nirncraft.intro2.loading.title"));
        this.since = System.currentTimeMillis();
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
        if (!IntroClient.coverWanted()) {
            // the server moved the player on (or there is no opening): the world fades in
            if (fadeStart < 0) fadeStart = now;
            if (now - fadeStart > 900) onClose();
            return;
        }
        // while the character is being made, or before the server has said LOADING, the cover just waits
        if (st != IntroState.LOADING || info == null) return;
        clientFrac = chunkFraction(mc, info);
        float server = Mth.clamp(info.progress(), 0f, 1f);
        float target = 0.5f * clientFrac + 0.5f * server;
        shown += (target - shown) * 0.2f;
        boolean timedOut = now - since > info.timeoutSeconds() * 1000L;
        boolean ready = clientFrac >= 0.98f && server >= 0.999f && now - since >= MIN_MILLIS;
        if ((ready || timedOut) && (!sent || now - sentAt > 2000)) {
            sent = true;
            sentAt = now;
            SkyNetwork.sendToServer(new IntroPackets.Loaded());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        float fadeIn = Mth.clamp((now - since) / 400f, 0f, 1f);
        float fadeOut = fadeStart < 0 ? 0f : Mth.clamp((now - fadeStart) / 900f, 0f, 1f);
        LoadingCover.draw(g, font, width, height, fadeIn * (1f - fadeOut),
                Component.translatable("quest.nirncraft.intro2.loading.title"), Component.translatable("quest.nirncraft.intro2.loading.sub"),
                Component.translatable("quest.nirncraft.intro2.loading.tip"), shown);
    }
}
