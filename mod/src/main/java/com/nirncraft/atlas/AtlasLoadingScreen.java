package com.nirncraft.atlas;

import com.nirncraft.inventory.client.SkyUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * Then control goes to the player. Esc skips it; it never holds longer than {@link #MAX_SECONDS} (a stall, a machine
 * where atlas cannot start). Off with {@code -Dnirncraft.atlas.loadingScreen=false}.
 */
final class AtlasLoadingScreen extends Screen {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.loadingScreen"));
    static final int MAX_SECONDS = 180, HORIZON_GRACE_SECONDS = 8;
    private static volatile boolean armed, open;

    private final long openedNs = System.nanoTime();
    private long rendererNs;  // (when atlas's kernels were built: the give-up clock starts there)
    private long nearDoneNs;
    private String lastLine = "";

    private AtlasLoadingScreen() {
        super(Component.literal("Preparing the world"));
    }

    /** On joining (single player with the client generator): the screen opens once vanilla's terrain screen closed. */
    static void arm() {
        armed = ON && AtlasClientGen.ON;
    }

    static void disarm() {
        armed = false;
    }

    /** Whether the player is held: armed to open, or open (the background bake of level 2 waits for control). */
    static boolean holding() {
        return armed || open;
    }

    /** (client tick) Opens the screen when armed and the world is in. */
    static void tick(Minecraft mc) {
        if (!armed || mc.level == null || mc.player == null || mc.gui.screen() != null) return;
        armed = false;
        open = true;
        mc.gui.setScreen(new AtlasLoadingScreen());
    }

    @Override
    public void removed() {
        open = false;
    }

    @Override
    public boolean isPauseScreen() {
        return true;  // (single player: nothing moves while the world comes in)
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;  // (skip: the world fills in around the player as before)
    }

    @Override
    public void tick() {
        // (the kernels' compile has its own true progress, minutes on a cold cache: the give-up clock starts once they are built)
        if (rendererNs == 0 && !AtlasClient.buildingContext()) rendererNs = System.nanoTime();
        double s = rendererNs == 0 ? 0 : (System.nanoTime() - rendererNs) / 1e9;
        AtlasClientGen.Progress p = AtlasClientGen.progress();
        boolean failed = AtlasClient.failedForSession();
        if (p != null && p.nearDone() && nearDoneNs == 0) nearDoneNs = System.nanoTime();
        // the horizon: the whole-map coarse bake's levels 3-6 made or loaded, and the tiles of the windows in the grid
        // (AtlasCoarseBake: first launch makes them, later ones load them); without it the old rule
        AtlasCoarseBake.Progress bake = AtlasCoarseBake.progress();
        boolean baking = bake != null && bake.phase() > 0;
        boolean horizonDone = p != null && (baking ? bake.loaded() : (p.farTotal() == 0 || p.far() >= p.farTotal()
                || (nearDoneNs != 0 && (System.nanoTime() - nearDoneNs) / 1e9 > HORIZON_GRACE_SECONDS)));
        boolean done = failed || s > MAX_SECONDS || (p != null && p.nearDone() && horizonDone);
        if (!done && !AtlasClientGen.ON) done = true;
        if (done) {
            com.nirncraft.NirnCraft.LOGGER.info("atlas loading phase: {} after {} s ({}; {})", failed ? "atlas off" : s > MAX_SECONDS ? "gave up"
                    : "ready", String.format("%.1f", s), p == null ? "no generator" : p.describe(), AtlasCoarseBake.line());
            minecraft.gui.setScreen(null);
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
        g.centeredText(font, Component.literal("Esc: skip"), cx, y + 10, SkyUi.DIM);
        String text = nearText;
        if (!text.equals(lastLine)) lastLine = text;
        super.extractRenderState(g, mouseX, mouseY, partialTick);
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
