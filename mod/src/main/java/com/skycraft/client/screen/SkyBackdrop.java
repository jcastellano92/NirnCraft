package com.skycraft.client.screen;

import com.skycraft.Skycraft;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.visual.VisualConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ErrorScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Skycraft's menu scene: the painted Tamriel landscape ({@code textures/gui/title_background.png}) slowly drifting
 * and breathing under drifting smoke. The title screen draws it sharp behind its menu; every other out-of-world
 * screen (world list, options, multiplayer, disconnected / failed to connect, mod list, dialogs...) gets it in place
 * of vanilla's panorama.
 *
 * <p>Hook (26.3): NeoForge posts {@link ScreenEvent.Render.Pre} before {@code Screen.extractRenderStateWithTooltipAndSubtitles},
 * which opens a new GUI stratum and then runs the screen's own {@code extractBackground}. Drawing the scene in Pre puts
 * it in the stratum below, so vanilla's {@code blurBeforeThisStratum} (the "Menu Background Blur" option), the
 * panorama overlay and the menu background tint all still apply on top, exactly as they would to the panorama, and the
 * screen's widgets are untouched. Vanilla's cube map would be hidden underneath, so it is skipped for that frame.
 * Screens whose rendering Skycraft replaces outright (loading screens, skinned dialogs) cancel Pre first; this listener
 * runs at the lowest priority and never sees those. The scene's motion follows the vanilla "Panorama Scroll Speed"
 * accessibility option (0 holds it still).
 *
 * <p>Cost: two textured quads for the scene, two for the smoke, three gradients; no uploads beyond the first use of the
 * two resource textures, which happens during GUI extraction (outside any render pass).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SkyBackdrop {
    public static final Identifier IMAGE = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/gui/title_background.png");
    private static final int IMAGE_W = 1280, IMAGE_H = 720;
    /** One slow drift cycle, in seconds of scene time. */
    private static final float CYCLE = 72f;

    /** Scene clock (seconds), advanced by real time times the panorama speed option. */
    private static double clock;
    private static long lastMillis = -1;
    /** The extractor of the frame in which this listener drew the scene (a new one is made every frame). */
    private static GuiGraphicsExtractor drawnFor;

    private SkyBackdrop() {}

    /** The full scene (landscape, vignette, smoke, darkened foot) covering a {@code w} x {@code h} screen. */
    public static void draw(GuiGraphicsExtractor g, int w, int h) {
        drawScene(g, w, h);
        drawAtmosphere(g, w, h, 0.8f);
    }

    /** The drifting landscape with its overall vignette. */
    public static void drawScene(GuiGraphicsExtractor g, int w, int h) {
        float t = (float) (tick() * (Mth.TWO_PI / CYCLE));
        // Cover-fit, then a gentle breathing zoom and pan. The 5% minimum overscan always exceeds the pan, so no edge shows.
        float cover = Math.max(w / (float) IMAGE_W, h / (float) IMAGE_H);
        float zoom = cover * (1.08f + 0.03f * Mth.sin(t));
        float panX = Mth.sin(t * 0.5f) * w * 0.02f;
        float panY = Mth.cos(t * 0.7f) * h * 0.012f;
        g.pose().pushMatrix();
        g.pose().translate(w / 2f + panX, h / 2f + panY);
        g.pose().scale(zoom, zoom);
        SkyUi.blit(g, IMAGE, -IMAGE_W / 2, -IMAGE_H / 2, IMAGE_W, IMAGE_H, 0, 0, IMAGE_W, IMAGE_H, IMAGE_W, IMAGE_H);
        g.pose().popMatrix();
        g.fillGradient(0, 0, w, h, 0x10000000, 0x40000000);
    }

    /** Drifting smoke and the darkened lower third. */
    public static void drawAtmosphere(GuiGraphicsExtractor g, int w, int h, float smoke) {
        SkyUi.smoke(g, w, h, (float) (clock % 100_000.0), smoke);
        g.fillGradient(0, h * 2 / 3, w, h, 0x00000000, 0x90000000);
    }

    private static double tick() {
        long now = Util.getMillis();
        if (lastMillis >= 0) {
            double speed = 1.0;
            try {
                speed = Minecraft.getInstance().options.panoramaSpeed().get();
            } catch (RuntimeException ignored) {
            }
            clock += Mth.clamp((now - lastMillis) / 1000.0, 0.0, 0.1) * speed;
        }
        lastMillis = now;
        return clock;
    }

    /** True for screens that get the scene in place of vanilla's panorama. */
    private static boolean wantsBackdrop(Minecraft mc, Screen screen) {
        return mc.level == null
                && !screen.isInGameUi()
                && !(screen instanceof com.skycraft.client.screen.title.SkyTitleScreen) // draws it itself, unblurred
                && !(screen instanceof ErrorScreen) // its own opaque error colours
                && !(screen instanceof WinScreen); // credits / end poem keep their own backgrounds
    }

    private static boolean enabled() {
        try {
            return VisualConfig.SKIN_VANILLA_SCREENS.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void beneathScreen(ScreenEvent.Render.Pre event) {
        GuiGraphicsExtractor g = event.getGuiGraphics();
        // Once per frame: with NeoForge screen layers only the bottom layer gets it, the others draw over that.
        if (g == drawnFor || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        Screen screen = event.getScreen();
        if (!wantsBackdrop(mc, screen)) return;
        drawnFor = g;
        draw(g, screen.width, screen.height);
    }

    /** The screen's background has been extracted: drop vanilla's cube map, which the scene would hide anyway. */
    @SubscribeEvent
    public static void hidePanorama(ScreenEvent.Render.Background event) {
        if (event.getGuiGraphics() == drawnFor) {
            Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState.panoramaRenderState = null;
        }
    }
}
