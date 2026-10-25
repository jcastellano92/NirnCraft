package com.nirncraft.atlas;

import com.nirncraft.hold.client.HoldClient;
import com.nirncraft.inventory.client.SkyUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The loading phase (docs/ATLAS-WORLD-STORE.md section 1, step 2): on joining a world, after vanilla's own terrain
 * screen, this screen holds the player until atlas is ready, the world paused behind it (single player):
 * <ol>
 *   <li>the renderer: atlas's context made, its Vulkan pipelines with it (warm from the instance's pipeline cache,
 *       atlas-cache/pipelines.bin, after the first run);</li>
 *   <li>the generator: the client generator up on the context's device (its pipelines too);</li>
 *   <li>the spawn area: every chunk of the generator's window around the player in the grid, from the self-baking
 *       cache where it knows them, else generated;</li>
 *   <li>the horizon: the coarse levels' tiles, from the cache or generated (not waited for past a few seconds once the
 *       spawn area is in).</li>
 * </ol>
 * Then control goes to the player. Under the player hold (docs/PLAYER-HOLD.md) this screen is the hold's face: it
 * reports ready to the server only when {@link AtlasReady} says everything above is truly loaded, and closes when the
 * server releases the player. It never lets go by itself: on a failure or after {@link #MAX_SECONDS} it says what is
 * wrong and offers Retry or "Enter anyway" (the player's explicit choice, logged; only once the vanilla terrain around
 * the player is in); Esc opens the game menu (to leave). Without a hold (disabled on the server) it closes when ready,
 * as before. Off, without a hold, with {@code -Dnirncraft.atlas.loadingScreen=false}.
 */
final class AtlasLoadingScreen extends Screen {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.loadingScreen"));
    static final int MAX_SECONDS = 180;
    private static volatile boolean armed, open;

    private long openedNs = System.nanoTime();
    private String lastLine = "";
    private boolean sentReady;          // the server was told (the screen waits for its release)
    private long sentAtNs, checkedNs;
    private String problem;             // a failure or a timeout: what is wrong (the hold stays)
    private String pending = "", lastPending = "-";
    private long lastPendingLogNs;
    private boolean isReady, failed, vanillaIn;
    private Button retry, enter;

    private AtlasLoadingScreen() {
        super(Component.literal("Preparing the world"));
    }

    /** On joining (single player with the client generator): the screen opens once vanilla's terrain screen closed. */
    static void arm() {
        armed = ON && AtlasClientGen.ON;
    }

    /** The screen is due to open (armed) and has not yet. */
    static boolean waiting() {
        return armed;
    }

    static void disarm() {
        armed = false;
    }

    /** Whether the player is held: armed to open, or open (the background bake of level 2 waits for control). */
    static boolean holding() {
        return armed || open;
    }

    /** (client tick) Opens the screen when armed or held, and the world is in. */
    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.gui.screen() != null) return;
        if (!HoldClient.held() && !armed) return;
        armed = false;
        open = true;
        mc.gui.setScreen(new AtlasLoadingScreen());
    }

    @Override
    public void removed() {
        open = false;
    }

    @Override
    protected void init() {
        retry = addRenderableWidget(Button.builder(Component.literal("Retry"), b -> retry()).bounds(width / 2 - 154, height / 2 + 110, 150, 20).build());
        enter = addRenderableWidget(Button.builder(Component.literal("Enter anyway"), b -> enterAnyway()).bounds(width / 2 + 4, height / 2 + 110, 150, 20).build());
        retry.visible = enter.visible = problem != null;
    }

    @Override
    public boolean isPauseScreen() {
        return !HoldClient.held();  // (a hold: the world runs and protects the player; without one, nothing moves while it comes in)
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;  // (never skipped: Esc opens the game menu, see keyPressed)
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {  // (the way out: Save and Quit, or back to this screen)
            minecraft.gui.setScreen(new PauseScreen(true));
            return true;
        }
        return super.keyPressed(event);
    }

    /** Retry: try atlas again if its failure could be transient, and wait afresh. */
    private void retry() {
        AtlasClient.retry();
        openedNs = System.nanoTime();
        problem = null;
        retry.visible = enter.visible = false;
    }

    /** The player's own choice to go on without a complete loading (logged on both sides). */
    private void enterAnyway() {
        com.nirncraft.NirnCraft.LOGGER.warn("atlas loading phase: the player entered anyway ({})", problem);
        if (HoldClient.held()) {
            HoldClient.sendReady(true);
            sentReady = true;
            sentAtNs = System.nanoTime();
            problem = null;
            retry.visible = enter.visible = false;
        } else {
            minecraft.gui.setScreen(null);
        }
    }

    /** Vanilla's own readiness: the chunks around the player are in (the fallback when atlas cannot be waited for). */
    private boolean vanillaReady() {
        if (minecraft.level == null || minecraft.player == null) return false;
        int cx = minecraft.player.blockPosition().getX() >> 4, cz = minecraft.player.blockPosition().getZ() >> 4;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (!minecraft.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
        return true;
    }

    /** Re-reads the readiness (atlas_info is read: a few times a second at most). */
    private void check() {
        long now = System.nanoTime();
        if (now - checkedNs < 250_000_000L) return;
        checkedNs = now;
        vanillaIn = vanillaReady();
        failed = false;
        if (!vanillaIn) {
            isReady = false;
            pending = "the terrain around you is arriving";
        } else if (AtlasClient.offByChoice()) {  // (atlas is off by the player's setting: nothing more to wait for)
            isReady = true;
            pending = "";
        } else if (AtlasReady.offForSession()) {
            isReady = false;
            failed = true;
            String why = AtlasClient.offReason();
            pending = "Infinite ATLAS stopped: " + (why == null ? "it is off for this session" : why);
        } else {
            pending = AtlasReady.pending();
            isReady = pending.isEmpty();
        }
    }

    @Override
    public void tick() {
        double s = (System.nanoTime() - openedNs) / 1e9;
        check();
        if (!pending.equals(lastPending) && (System.nanoTime() - lastPendingLogNs) / 1e9 >= 5) {  // (what the hold waits on, for the logs)
            lastPending = pending;
            lastPendingLogNs = System.nanoTime();
            com.nirncraft.NirnCraft.LOGGER.info("atlas loading phase: {} s, {}", String.format("%.1f", s), pending.isEmpty() ? "ready" : "waiting for: " + pending);
        }
        boolean held = HoldClient.held();
        if (!held && (isReady || sentReady)) {  // released by the server (or never held and ready): the world is the player's
            com.nirncraft.NirnCraft.LOGGER.info("atlas loading phase: ready after {} s ({})", String.format("%.1f", s),
                    AtlasClientGen.progress() == null ? "no generator" : AtlasClientGen.progress().describe());
            minecraft.gui.setScreen(null);
            return;
        }
        if (isReady) {
            problem = null;
            if (held && (!sentReady || (System.nanoTime() - sentAtNs) / 1e9 > 2)) {  // (again until the release arrives: a lost message)
                if (HoldClient.sendReady(false) && !sentReady) {
                    com.nirncraft.NirnCraft.LOGGER.info("atlas loading phase: ready after {} s, told the server", String.format("%.1f", s));
                    sentReady = true;
                }
                sentAtNs = System.nanoTime();
            }
        } else if (!sentReady) {
            if (failed) problem = pending;
            else if (s > MAX_SECONDS) problem = "Loading is taking longer than expected (" + pending + ")";
            else problem = null;
        }
        if (retry != null) {
            retry.visible = problem != null;
            enter.visible = problem != null;
            enter.active = vanillaIn;
            retry.active = !failed || AtlasClient.canRetry();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF0D0C0B);
        int cx = width / 2, y = height / 2 - 60;
        g.centeredText(font, getTitle(), cx, y, SkyUi.GOLD);
        y += 22;
        AtlasClientGen.Progress p = AtlasClientGen.progress();
        boolean renderer = AtlasClient.started();
        // the renderer's kernels as the driver compiles them (atlas_pipeline_progress: done of the backend's list, by name)
        AtlasNative.PipelineProgress pp = AtlasClient.building();
        String rText = renderer ? "ready" : pp != null && AtlasClient.buildingContext() && pp.total() > 0
                ? "compiling " + Math.min(pp.done() + 1, pp.total()) + " / " + pp.total() + " (" + pp.name() + ")" : "starting";
        double rFrac = renderer ? 1 : pp != null && pp.total() > 0 ? Math.min(1.0, pp.done() / (double) pp.total()) : 0;
        y = line(g, cx, y, "Renderer", rText, rFrac);
        // the split frames' far kernel: one compile in the background (no count to show: a state), not waited for
        if (pp != null && pp.split() == 1) y = line(g, cx, y, "Renderer (fast far kernel)", "compiling in the background", -1);
        y = line(g, cx, y, "Generator", p != null && p.running() ? "ready" : renderer ? "starting" : "waiting", p != null && p.running() ? 1 : 0);
        double near = p == null || p.nearTotal() == 0 ? 0 : Math.min(1.0, p.near() / (double) p.nearTotal());
        String nearText = p == null ? "waiting" : p.nearDone() ? "done" : p.near() + " / " + p.nearTotal() + " chunks"
                + (p.cached() > 0 ? " (" + p.cached() + " from the cache)" : "");
        y = line(g, cx, y, "Spawn area", nearText, p != null && p.nearDone() ? 1 : near);
        AtlasCoarseBake.Progress bake = AtlasCoarseBake.progress();
        if (bake != null && bake.phase() > 0) {
            boolean first = bake.total() > 0 && bake.phase() == 1;
            y = line(g, cx, y, "Horizon", bake.loaded() ? "ready" : first ? "baking the world " + bake.done() + " / " + bake.total() + " tiles"
                    : bake.phase() == 1 ? "loading" : "loading " + bake.servePending() + " tiles", bake.loaded() ? 1 : bake.total() == 0 ? 0
                    : Math.min(1.0, bake.done() / (double) bake.total()));
        } else if (p != null && p.farTotal() > 0) {
            double far = Math.min(1.0, p.far() / (double) p.farTotal());
            y = line(g, cx, y, "Horizon", p.far() + " / " + p.farTotal() + " tiles" + (p.farCached() > 0 ? " (" + p.farCached() + " from the cache)" : ""), far);
        }
        y = bakeLevels(g, cx, y, bake);
        y = cacheLoad(g, cx, y, p, bake);
        if (problem != null) {
            int ty = y + 6;
            for (var line : font.split(Component.literal(problem), Math.min(360, width - 40))) {
                g.centeredText(font, line, cx, ty, 0xFFE07050);
                ty += font.lineHeight + 2;
            }
            g.centeredText(font, Component.literal(vanillaIn ? "You stay held (invisible, untouchable) until you choose."
                    : "You stay held until the terrain around you has arrived."), cx, ty + 4, SkyUi.DIM);
        } else {
            String held = HoldClient.held() ? (sentReady ? "Entering the world" : "Preparing the world: you are not in play yet") : "";
            if (!pending.isEmpty()) held += (held.isEmpty() ? "" : " - ") + pending;
            g.centeredText(font, Component.literal(held), cx, y + 6, SkyUi.DIM);
        }
        g.centeredText(font, Component.literal("Esc: game menu"), cx, y + 26, SkyUi.DIM);
        String text = nearText;
        if (!text.equals(lastLine)) lastLine = text;
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** The bake of the coarse levels, one count a level (tiles finished of planned: on disk, made or given up), from the bake's own tallies. */
    private int bakeLevels(GuiGraphicsExtractor g, int cx, int y, AtlasCoarseBake.Progress bake) {
        if (bake == null || bake.phase() <= 0 || bake.levelTiles() == null) return y;
        StringBuilder t = new StringBuilder("Bake by level");
        boolean any = false;
        for (int k = 6; k >= 3; k--) {
            int n = bake.levelTiles()[k];
            if (n == 0) continue;
            t.append(any ? ",  L" : "   L").append(k).append(' ').append(bake.levelDone()[k]).append(" / ").append(n);
            any = true;
        }
        return any ? note(g, cx, y, t.toString()) : y;
    }

    /**
     * What is read from disk instead of made: the horizon's tiles into the grid (read of the known ones: those read and those
     * still waiting), the spawn area's chunks from the cache (of the window's chunks), and the cache's sections behind them.
     */
    private int cacheLoad(GuiGraphicsExtractor g, int cx, int y, AtlasClientGen.Progress p, AtlasCoarseBake.Progress bake) {
        if (p == null) return y;
        StringBuilder t = new StringBuilder("Cache");
        if (bake != null && bake.phase() > 0) {
            long known = bake.served() + bake.servePending();
            t.append("   tiles ").append(bake.served()).append(" / ").append(known);
        }
        t.append(bake != null && bake.phase() > 0 ? ",  chunks " : "   chunks ").append(p.cached()).append(" / ").append(p.nearTotal());
        return note(g, cx, y, t.toString());
    }

    /** A line of small text under a stage (no bar). */
    private int note(GuiGraphicsExtractor g, int cx, int y, String text) {
        int w = Math.min(320, width - 40), x0 = cx - w / 2;
        g.text(font, Component.literal(text), x0, y, SkyUi.DIM, false);
        return y + font.lineHeight + 4;
    }

    /** One stage: its name, its state and a bar. */
    private int line(GuiGraphicsExtractor g, int cx, int y, String name, String state, double frac) {
        int w = Math.min(320, width - 40), x0 = cx - w / 2;
        g.text(font, Component.literal(name), x0, y, SkyUi.TEXT, false);
        g.text(font, Component.literal(state), x0 + w - font.width(state), y, SkyUi.DIM, false);
        int by = y + font.lineHeight + 2;
        if (frac < 0) return by + 4;  // (a state with no count behind it: no bar)
        g.fill(x0, by, x0 + w, by + 3, 0xFF2A2724);
        g.fill(x0, by, x0 + (int) Math.round(w * Math.max(0, Math.min(1, frac))), by + 3, SkyUi.GOLD);
        return by + 12;
    }
}
