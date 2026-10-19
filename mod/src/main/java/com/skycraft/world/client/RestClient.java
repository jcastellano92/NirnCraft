package com.skycraft.world.client;

import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.core.HudStack;
import com.skycraft.network.SkyNetwork;
import com.skycraft.world.RestLogic;
import com.skycraft.world.SkyrimCalendar;
import com.skycraft.world.WorldPackets;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * The client half of the rest system ({@code world/RestManager}): the state the server sends, the third-person camera
 * (remembered on entry and given back on every way out), the slow zoom and vignette of the fast-forward, the lying
 * pose of players in a bed, the non-blocking time panel and the "X is resting, press [key] to join" line.
 *
 * <p>Controls while resting: left/right (or the mouse wheel) hours, up until morning, down until evening, Enter starts
 * (the initiator, once everybody rests); any movement key, Escape or the wait key's "stand up" cancels; the wait key
 * opens {@link RestScreen} to change between sleeping and waiting.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class RestClient {
    private static int phase;
    private static int mode;
    private static int flags;
    private static int hours;
    private static int progress;
    private static int resting;
    private static int required;
    private static String initiator = "";
    private static WorldPackets.RestHint hint;

    private static int chosen = 8;
    private static boolean cancelSent;
    private static boolean prevMove;

    /** Camera the player had before resting; restored on every exit. */
    private static CameraType savedCamera;
    private static boolean cameraTaken;

    private static float zoom, prevZoom, eased;

    private RestClient() {}

    // ------------------------------------------------------------------ server state

    public static void onState(WorldPackets.RestState m) {
        Minecraft mc = Minecraft.getInstance();
        boolean was = phase != 0;
        phase = m.phase();
        mode = m.mode();
        flags = m.flags();
        hours = m.hours();
        progress = m.progress();
        resting = m.resting();
        required = m.required();
        initiator = m.initiator();
        cancelSent = false;
        if (phase == 0) {
            restoreCamera();
            if (mc.gui.screen() instanceof RestScreen rs) {
                rs.closeFromServer();
            }
            return;
        }
        hint = null;
        if (mc.player == null) return;
        if (mc.gui.screen() instanceof InBedChatScreen) mc.gui.setScreen(null);
        if (!was) {
            prevMove = movementDown(mc);
        }
        if (mode != RestLogic.JAIL) {
            if (mc.gui.screen() instanceof RestScreen rs) {
                rs.onServerState();
            } else {
                mc.gui.setScreen(new RestScreen(mode == RestLogic.SLEEP));
            }
        }
    }

    public static void onHint(WorldPackets.RestHint m) {
        hint = m.required() <= 0 ? null : m;
    }

    private static void restoreCamera() {
        if (!cameraTaken) return;
        cameraTaken = false;
        Minecraft mc = Minecraft.getInstance();
        if (savedCamera != null && mc.options != null) mc.options.setCameraType(savedCamera);
        savedCamera = null;
    }

    private static void reset() {
        phase = 0;
        hint = null;
        zoom = prevZoom = eased = 0f;
        cancelSent = false;
        restoreCamera();
    }

    // ------------------------------------------------------------------ state accessors for RestScreen

    public static int phase() {
        return phase;
    }

    public static int mode() {
        return mode;
    }

    public static int flags() {
        return flags;
    }

    public static int hours() {
        return hours;
    }

    public static int progress() {
        return progress;
    }

    public static int resting() {
        return resting;
    }

    public static int required() {
        return required;
    }

    public static String initiator() {
        return initiator;
    }

    public static boolean canStart() {
        return phase == 1 && (flags & WorldPackets.RestState.F_CAN_START) != 0;
    }

    public static boolean isSkipDisabled() {
        return (flags & WorldPackets.RestState.F_SKIP_OFF) != 0;
    }

    public static boolean isInitiator() {
        return (flags & WorldPackets.RestState.F_INITIATOR) != 0;
    }

    public static void start(int chosenHours) {
        if (!canStart()) return;
        chosen = Mth.clamp(chosenHours, 1, 24);
        SkyNetwork.sendToServer(new WorldPackets.Rest(WorldPackets.Rest.START, mode, chosen));
    }

    // ------------------------------------------------------------------ keys

    /**
     * The wait key opens the Skyrim Wait menu. Pressing it again cancels.
     * Sleeping is triggered by right-clicking a bed you may use.
     */
    public static void onWaitKey(Minecraft mc) {
        if (mc.player == null || mc.gui.screen() != null) return;
        if (phase != 0) {
            sendCancel();
            return;
        }
        sendEnter(RestLogic.WAIT);
    }

    static void sendEnter(int rest) {
        SkyNetwork.sendToServer(new WorldPackets.Rest(WorldPackets.Rest.ENTER, rest, 0));
    }

    static void sendCancel() {
        if (cancelSent) return;
        cancelSent = true;
        SkyNetwork.sendToServer(new WorldPackets.Rest(WorldPackets.Rest.CANCEL, 0, 0));
    }

    private static void start() {
        start(chosen);
    }

    private static boolean movementDown(Minecraft mc) {
        var o = mc.options;
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown()
                || o.keyShift.isDown();
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (phase != 1 || mc.gui.screen() != null || mc.player == null || mc.level == null || event.getAction() != com.mojang.blaze3d.platform.InputConstants.PRESS) return;
        boolean can = (flags & WorldPackets.RestState.F_SKIP_OFF) == 0;
        switch (event.getKey()) {
            case com.mojang.blaze3d.platform.InputConstants.KEY_LEFT -> chosen = Math.max(1, chosen - 1);
            case com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT -> chosen = Math.min(24, chosen + 1);
            case com.mojang.blaze3d.platform.InputConstants.KEY_UP -> chosen = RestLogic.hoursUntil(mc.level.getOverworldClockTime(), 6);
            case com.mojang.blaze3d.platform.InputConstants.KEY_DOWN -> chosen = RestLogic.hoursUntil(mc.level.getOverworldClockTime(), 18);
            case com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER -> {
                if (can) start();
            }
            default -> {
            }
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (phase != 1 || mc.gui.screen() != null || mc.player == null) return;
        chosen = Mth.clamp(chosen + (event.getScrollDeltaY() > 0 ? 1 : -1), 1, 24);
        event.setCanceled(true);
    }

    /** Escape while resting without RestScreen open stands up instead of opening the pause menu. */
    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        if (phase == 0 || !(event.getNewScreen() instanceof PauseScreen) || !mc.isWindowActive()) return;
        event.setCanceled(true);
        sendCancel();
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        prevZoom = zoom;
        float target = phase == 2 ? 1f : 0f;
        zoom += (target - zoom) * 0.08f;
        eased += (progress / 1000f - eased) * 0.3f;
        if (phase == 0) eased = 0f;
        if (mc.player == null || mc.level == null) {
            if (phase != 0 || cameraTaken) reset();
            return;
        }
        if (!mc.player.isAlive() && (phase != 0 || cameraTaken)) {
            phase = 0;
            restoreCamera();
            return;
        }
        if (cameraTaken && phase == 0) restoreCamera();
        if (phase != 0 && mc.gui.screen() == null) {
            boolean move = movementDown(mc);
            if (move && !prevMove) sendCancel();
            prevMove = move;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    // ------------------------------------------------------------------ camera

    /** Slowly zooms forward while time passes. */
    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        float z = Mth.lerp((float) event.getPartialTick(), prevZoom, zoom);
        if (z <= 0.001f) return;
        float ease = 0.5f - 0.5f * Mth.cos(z * (float) Math.PI);
        event.setFOV((float)(event.getFOV() * (1.0 - 0.28 * ease * (0.4 + 0.6 * Mth.clamp(eased, 0f, 1f)))));
    }

    // ------------------------------------------------------------------ vanilla sleeping chrome

    /**
     * Sleeping uses the vanilla sleeping state, which opens the "Leave Bed" chat screen and blacks the screen out.
     * While the rest system runs, neither is wanted: the time panel and the cinematic replace them (ESC, movement or
     * a hit stands the player up). A vanilla sneak-use sleep (no rest session) keeps the vanilla screen.
     */
    @SubscribeEvent
    public static void onInBedScreen(ScreenEvent.Opening event) {
        if (phase != 0 && event.getNewScreen() instanceof InBedChatScreen) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onOverlay(RenderGuiLayerEvent.Pre event) {
        if (phase != 0 && event.getName().equals(VanillaGuiLayers.SLEEP_OVERLAY)) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ HUD

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || mc.player == null || mc.level == null) return;
        Font font = mc.font;
        float z = Mth.lerp(partialTick, prevZoom, zoom);
        if (z > 0.01f) vignette(g, width, height, z);

        if (phase == 0) {
            if (hint != null && mc.gui.screen() == null && !mc.player.isSpectator()) {
                Component line = Component.translatable("world.skycraft.rest.hint", hint.who(),
                        SkyKeys.WAIT.getTranslatedKeyMessage(), hint.resting(), hint.required());
                int y = HudStack.reserve(HudStack.Zone.BOTTOM_CENTER, HudStack.BC_REST, 12, height);
                int w = font.width(line);
                int x = (width - w) / 2;
                g.fill(x - 5, y - 2, x + w + 5, y + 10, 0x90100C08);
                g.text(font, line, x, y, 0xFFE8C060, true);
            }
            return;
        }
        if (mc.gui.screen() instanceof RestScreen) return;
        long dayTime = mc.level.getOverworldClockTime();
        boolean jail = mode == RestLogic.JAIL;
        if (phase == 2) {
            int panelH = 36;
            int y = HudStack.reserve(HudStack.Zone.BOTTOM_CENTER, HudStack.BC_REST, panelH, height);
            int bw = 200;
            int x = (width - bw) / 2;
            Component label = jail ? Component.translatable("world.skycraft.rest.passing_jail")
                    : Component.translatable("world.skycraft.rest.passing", Math.min(hours, (int) (hours * (long) progress / 1000L)), hours);
            g.centeredText(font, label, width / 2, y, 0xFFF5EBC8);
            g.centeredText(font, SkyrimCalendar.time(dayTime), width / 2, y + 11, 0xFFE8C060);
            g.fill(x, y + 24, x + bw, y + 28, 0xA0000000);
            g.fill(x, y + 24, x + (int) (bw * Mth.clamp(progress / 1000f, 0f, 1f)), y + 28, 0xFFC8A850);
            g.centeredText(font, Component.translatable("world.skycraft.rest.cancel_hint"), width / 2, y + 31, 0xFFA89F86);
            return;
        }
        // phase 1: the time panel
        boolean skipOff = (flags & WorldPackets.RestState.F_SKIP_OFF) != 0;
        int lines = 4;
        int panelH = lines * 11 + 6;
        int y = HudStack.reserve(HudStack.Zone.BOTTOM_CENTER, HudStack.BC_REST, panelH, height);
        int w = 250;
        int x = (width - w) / 2;
        g.fillGradient(x, y - 2, x + w, y + panelH, 0xB0100C08, 0xB0201810);
        g.fill(x, y - 2, x + w, y - 1, 0xFF8A7F66);
        Component title = Component.translatable(mode == RestLogic.SLEEP ? "world.skycraft.rest.state.sleep" : "world.skycraft.rest.state.wait");
        g.centeredText(font, title, width / 2, y + 1, 0xFFF5EBC8);
        if (skipOff) {
            g.centeredText(font, Component.translatable("world.skycraft.rest.recovering"), width / 2, y + 12, 0xFFA89F86);
            g.centeredText(font, Component.translatable("world.skycraft.rest.skip_off"), width / 2, y + 23, 0xFFA89F86);
        } else {
            Component pick = Component.translatable(chosen == 1 ? "world.skycraft.rest.pick.hour" : "world.skycraft.rest.pick.hours", chosen,
                    SkyrimCalendar.time(dayTime + chosen * 1000L));
            g.centeredText(font, pick, width / 2, y + 12, 0xFFE8C060);
            Component action;
            if (canStart()) action = Component.translatable("world.skycraft.rest.controls");
            else if ((flags & WorldPackets.RestState.F_INITIATOR) == 0 && resting >= required && required > 0)
                action = Component.translatable("world.skycraft.rest.chooses", initiator);
            else action = Component.translatable("world.skycraft.rest.waiting_others", resting, required);
            g.centeredText(font, action, width / 2, y + 23, canStart() ? 0xFFD0C8B0 : 0xFFA89F86);
        }
        g.centeredText(font, Component.translatable("world.skycraft.rest.stand_hint", SkyKeys.WAIT.getTranslatedKeyMessage()),
                width / 2, y + 34, 0xFF8A7F66);
    }

    private static void vignette(GuiGraphicsExtractor g, int w, int h, float z) {
        int a = (int) (0xB0 * Mth.clamp(z, 0f, 1f));
        int edge = a << 24;
        int bandW = (int) (w * 0.22f), bandH = (int) (h * 0.22f);
        g.fillGradient(0, 0, w, bandH, edge, 0);
        g.fillGradient(0, h - bandH, w, h, 0, edge);
        // sides: horizontal gradients drawn as stacked thin columns
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            int col = ((int) (a * (1f - i / (float) steps) * 0.8f)) << 24;
            int x0 = (int) (bandW * (i / (float) steps)), x1 = (int) (bandW * ((i + 1) / (float) steps));
            g.fill(x0, 0, x1, h, col);
            g.fill(w - x1, 0, w - x0, h, col);
        }
    }
}
