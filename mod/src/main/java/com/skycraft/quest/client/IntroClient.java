package com.skycraft.quest.client;

import com.skycraft.Skycraft;
import com.skycraft.quest.story.IntroPackets;
import com.skycraft.quest.story.IntroState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import com.skycraft.client.screen.RaceScreen;
import com.skycraft.core.SkyData;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Client side of the opening: the player's own state, the loading screen after character creation, the letterboxed
 * cutscene (controls stopped, zoom, camera shake, a client-only dragon), and the players hidden until they leave the
 * cottage. The server owns every decision; this class only shows and reports that the chunks are in.
 */
public final class IntroClient {
    private static IntroState state;
    private static IntroPackets.State last;
    private static Set<UUID> hidden = new HashSet<>();
    private static IntroCutscene cutscene;
    private static long loadingSince;
    private static final net.minecraft.util.context.ContextKey<Boolean> HIDDEN=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft","intro_hidden"));
    private static final net.minecraft.util.context.ContextKey<IntroCutscene.Extracted> DRAGON=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft","intro_dragon"));
    public static void extractPlayer(net.minecraft.world.entity.player.Player player,net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
        state.setRenderData(HIDDEN,player!=Minecraft.getInstance().player&&hidden.contains(player.getUUID()));
    }

    /** True from joining until the server has said whether this player is in the opening: the world is not shown meanwhile. */
    private static boolean awaiting;
    private static int awaitTicks;
    /** The character was confirmed (the server has not moved on yet): do not open the character screen again. */
    private static boolean characterDone;
    private static int characterTicks;

    private IntroClient() {}

    // ------------------------------------------------------------------ from the packets (client thread)

    public static void onState(IntroPackets.State s) {
        IntroState was = state;
        state = IntroState.byId(s.state());
        last = s;
        awaiting = false;
        if (state != IntroState.JOINED && state != IntroState.CHARACTER) characterDone = false;
        if (state == IntroState.LOADING && was != IntroState.LOADING) loadingSince = System.currentTimeMillis();
        if (state != IntroState.CUTSCENE && cutscene != null && state != null) {
            // the server moved on (relog, admin): drop the scene
            if (state != IntroState.CUTSCENE) cutscene = null;
        }
    }

    public static void onCutscene(IntroPackets.Cutscene c) {
        cutscene = c.start() ? new IntroCutscene(c) : null;
    }

    public static void onHidden(List<UUID> ids) {
        hidden = new HashSet<>(ids);
    }

    // ------------------------------------------------------------------ what the other classes ask

    static IntroState state() {
        return state;
    }

    static IntroPackets.State loadingInfo() {
        return last;
    }

    static boolean controlsLocked() {
        return cutscene != null || coverWanted();
    }

    /**
     * The world must not be seen: from the moment the player exists until the server has said where they are in the
     * opening, and while the character is made and the valley loads. (The cottage is the first thing they ever see
     * once this lifts, and by then the chunks are in.)
     */
    static boolean coverWanted() {
        if (awaiting) return true;
        return state == IntroState.JOINED || state == IntroState.CHARACTER || state == IntroState.LOADING;
    }

    /** True while the character screen is what the player must be looking at. */
    private static boolean needsCharacter(Minecraft mc) {
        if (characterDone || mc.player == null) return false;
        if (state != IntroState.JOINED && state != IntroState.CHARACTER) return false;
        return SkyData.get(mc.player).getRace() == null;
    }

    /** Called by the character screen when it is confirmed: the loading cover takes over at once. */
    public static void characterConfirmed() {
        characterDone = true;
        characterTicks = 0;
    }

    static void reset() {
        state = null;
        last = null;
        hidden = new HashSet<>();
        cutscene = null;
        awaiting = false;
        characterDone = false;
    }

    // ------------------------------------------------------------------ events

    @net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
    public static final class IntroClientEvents {
        private IntroClientEvents() {}

        @SubscribeEvent
        public static void introClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            if (awaiting && ++awaitTicks > 200) awaiting = false; // the server never answered: do not hold the world back
            if (characterDone && (state == IntroState.JOINED || state == IntroState.CHARACTER)) {
                // the server did not take the character: let them choose again
                if (++characterTicks > 200) {
                    characterDone = false;
                    characterTicks = 0;
                }
            }
            if (coverWanted()) {
                var s = mc.gui.screen();
                if (needsCharacter(mc)) {
                    if (!(s instanceof RaceScreen)) mc.gui.setScreen(new RaceScreen());
                } else if (!(s instanceof IntroLoadingScreen) && !(s instanceof RaceScreen)) {
                    mc.gui.setScreen(new IntroLoadingScreen());
                }
            }
            if (cutscene != null) {
                if (mc.gui.screen() != null && !(mc.gui.screen() instanceof PauseScreen) && !(mc.gui.screen() instanceof IntroLoadingScreen)
                        && mc.gui.screen().getClass().getName().indexOf("Pause") < 0) {
                    mc.gui.setScreen(null);
                }
                cutscene.tick(mc);
                if (cutscene.finished()) cutscene = null;
            }
        }

        /** Hidden players are not drawn (nor their name tags); the local player always is. */
        @SubscribeEvent
        public static void introHidePlayer(RenderPlayerEvent.Pre event) {
            if (hidden.isEmpty()) return;
            if(Boolean.TRUE.equals(event.getRenderState().getRenderData(HIDDEN)))event.setCanceled(true);
        }

        @SubscribeEvent
        public static void introHideNameTag(RenderNameTagEvent.CanRender event) {
            if (hidden.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (event.getEntity() != mc.player && hidden.contains(event.getEntity().getUUID())) event.setCanRender(net.minecraft.util.TriState.FALSE);
        }

        /** Controls stopped: no walking, jumping or sneaking while the scene or the loading screen runs. */
        @SubscribeEvent
        public static void introMovementInput(MovementInputUpdateEvent event) {
            if (!controlsLocked()) return;
            com.skycraft.client.SkyMovementInput.stop(event);
        }

        @SubscribeEvent
        public static void introNoInteraction(InputEvent.InteractionKeyMappingTriggered event) {
            if (cutscene != null) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        /** The camera is the scene's: the view is held where the scene looks, whatever the mouse does. */
        @SubscribeEvent
        public static void introHoldLook(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
            if (cutscene == null) return;
            LocalPlayer pl = Minecraft.getInstance().player;
            if (pl == null) return;
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            float yaw = cutscene.yaw(partial), pitch = cutscene.pitch(partial);
            pl.setYRot(yaw);
            pl.setXRot(pitch);
            pl.yRotO = yaw;
            pl.xRotO = pitch;
            pl.yHeadRot = yaw;
            pl.yHeadRotO = yaw;
            pl.yBodyRot = yaw;
            pl.yBodyRotO = yaw;
        }

        @SubscribeEvent
        public static void introFov(ViewportEvent.ComputeFov event) {
            if (cutscene == null) return;
            event.setFOV(event.getFOV() * cutscene.zoom((float) event.getPartialTick()));
        }

        @SubscribeEvent
        public static void introShake(ViewportEvent.ComputeCameraAngles event) {
            if (cutscene == null) return;
            float[] s = cutscene.shakeNow((float) event.getPartialTick());
            event.setRoll(event.getRoll() + s[0]);
            event.setPitch(event.getPitch() + s[1]);
            event.setYaw(event.getYaw() + s[2]);
        }

        @SubscribeEvent
        public static void introNoHands(RenderHandEvent event) {
            if (cutscene != null) event.setCanceled(true);
        }

        /** The vanilla and mod HUD steps aside for the cutscene (the narration and the bars stay). */
        @SubscribeEvent
        public static void introNoHud(RenderGuiLayerEvent.Pre event) {
            if (cutscene == null) return;
            String id = event.getName().toString();
            if (id.equals("skycraft:narration") || id.equals("skycraft:intro_letterbox")) return;
            event.setCanceled(true);
        }

        @SubscribeEvent public static void extractDragon(net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent event) {
            if(cutscene!=null) event.getRenderState().setRenderData(DRAGON,cutscene.extract(Minecraft.getInstance(),event.getDeltaTracker().getGameTimeDeltaPartialTick(false)));
        }
        @SubscribeEvent public static void submitDragon(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent event) {
            var dragon=event.getLevelRenderState().getRenderData(DRAGON);
            if(dragon!=null) dragon.submit(event.getPoseStack(),event.getSubmitNodeCollector(),event.getLevelRenderState().cameraRenderState);
        }

        @SubscribeEvent
        public static void introLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            reset();
        }

        /** Joining: hold the world back until the server says whether this player is in the opening. */
        @SubscribeEvent
        public static void introLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            reset();
            awaiting = true;
            awaitTicks = 0;
        }

        /**
         * A screen is closing and would show the world (the vanilla "loading terrain" screen, the character screen):
         * while the opening needs the world hidden, the cover or the character screen takes its place in the same frame.
         */
        @SubscribeEvent(priority = EventPriority.HIGH)
        public static void introScreenOpening(ScreenEvent.Opening event) {
            if (event.getNewScreen() != null || !coverWanted()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            event.setNewScreen(needsCharacter(mc) ? new RaceScreen() : new IntroLoadingScreen());
        }
    }

    /** Mod bus: the letterbox overlay (above everything, including the narration card's band). */
    @net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
    public static final class IntroClientSetup {
        private IntroClientSetup() {}

        @SubscribeEvent
        public static void introOverlays(RegisterGuiLayersEvent event) {
            event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "intro_letterbox"), IntroClient::renderLetterbox);
        }
    }

    private static void renderLetterbox(GuiGraphicsExtractor g,net.minecraft.client.DeltaTracker delta) {
        float partialTick=delta.getGameTimeDeltaPartialTick(false);int width=g.guiWidth(),height=g.guiHeight();
        IntroCutscene c = cutscene;
        if (c == null) return;
        int bar = Math.round(height * c.bars(partialTick));
        if (bar <= 0) return;
        g.fill(0, 0, width, bar, 0xFF000000);
        g.fill(0, height - bar, width, height, 0xFF000000);
    }
}
