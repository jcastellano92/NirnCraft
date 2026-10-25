package com.nirncraft.atlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Vanilla's texture animation clock, for atlas's animated bricks (atlas_set_texture_ticks): how many times the block
 * atlas's animations have moved since it was built. Vanilla builds every sprite's animation at its first frame
 * (TextureAtlas.upload, then TextureAtlasStitchedEvent) and moves each one sub-frame once per runTick that has game
 * ticks to do while the level runs normally (Minecraft.runTick: textureManager.tick(), however many ticks follow; also
 * during the first resource load, before any client tick event). Seen from RenderFrameEvent.Pre, later in the same
 * runTick: ticks to do = (int) (the timer's residual before + this frame's delta), the timer's own float sum; while
 * paused the timer shows its frozen residual, so a client tick event since the last frame says it instead. Render thread.
 */
final class AtlasTextureClock {
    private AtlasTextureClock() {}

    private static long ticks;
    private static int gameTicks;      // client tick events since the last frame (paused frames)
    private static float residual;     // the timer's residual after the last frame
    private static boolean wasPaused;

    static void init(IEventBus modBus) {
        modBus.addListener((TextureAtlasStitchedEvent e) -> {
            if (TextureAtlas.LOCATION_BLOCKS.equals(e.getAtlas().location())) ticks = 0;
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> gameTicks++);
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> frame(Minecraft.getInstance()));
    }

    private static void frame(Minecraft mc) {
        var dt = mc.getDeltaTracker();
        boolean paused = mc.isPaused();
        boolean ticked;
        if (paused || wasPaused) {
            ticked = gameTicks > 0;
        } else {
            float sum = residual + dt.getGameTimeDeltaTicks();
            ticked = (int) sum > 0;
        }
        var level = mc.level;
        if (ticked && (level == null || level.tickRateManager().runsNormally())) ticks++;
        residual = dt.getGameTimeDeltaPartialTick(true);
        wasPaused = paused;
        gameTicks = 0;
    }

    /** The block atlas's animation ticks so far (each animated sprite's place in its sequence). */
    static long ticks() {
        return ticks;
    }
}
