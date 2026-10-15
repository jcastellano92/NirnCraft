package com.skycraft.guide.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.guide.GuideEvents;
import com.skycraft.quest.client.NarrationOverlay;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.world.Provinces;
import com.skycraft.world.client.MusicController;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * Client side of the guide module:
 * <ul>
 *   <li>Opening the Traveler's Handbook and the {@code /handbook} client command.</li>
 *   <li>Tutorial-hint overlay ({@link TutorialHints}).</li>
 *   <li>Region First-Entry Arrival Moment (MS19 — {@code F-ARRIVAL}, R3d-2.3): renders the styled region arrival banner
 *   with province header and {@code "Rumours here: ..."} subtitle and triggers the region musical sting via
 *   {@link MusicController}.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class GuideClient {
    private static final long ARRIVAL_BANNER_MS = 5500L;
    private static final ResourceLocation DISCOVERY_STING = new ResourceLocation(Skycraft.MODID, "music.discovery");

    /** Opened on the next tick: a chat command's screen would otherwise be closed by the chat screen itself. */
    private static boolean openPending;
    private static String openChapter;

    private static int lastSeenArrivalSeq = -1;
    private static Component bannerTitle;
    private static Component bannerProvince;
    private static Component bannerRumour;
    private static long bannerStartMs;

    private GuideClient() {}

    public static void openHandbook() {
        Minecraft.getInstance().setScreen(new HandbookScreen());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Check for newly synced region arrival from GuideEvents
        CompoundTag mod = SkyData.get(mc.player).module(GuideEvents.MODULE);
        int seq = mod.getInt("last_arrival_seq");
        if (lastSeenArrivalSeq == -1) {
            // Initialize on first client tick after login; if seqJustTriggered on fresh spawn, show it
            lastSeenArrivalSeq = seq;
            if (seq == 1 && mc.player.tickCount < 160) {
                String regionId = mod.getString("last_arrival_region");
                if (!regionId.isEmpty()) showArrivalBanner(mc, regionId, false);
            }
        } else if (seq > lastSeenArrivalSeq) {
            lastSeenArrivalSeq = seq;
            String regionId = mod.getString("last_arrival_region");
            if (!regionId.isEmpty()) showArrivalBanner(mc, regionId, false);
        }

        if (!openPending || mc.screen != null) return;
        openPending = false;
        if (openChapter != null) HandbookScreen.preselect(openChapter);
        openChapter = null;
        openHandbook();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        lastSeenArrivalSeq = -1;
        bannerTitle = null;
        bannerProvince = null;
        bannerRumour = null;
    }

    /**
     * Displays the MS19 region first-entry banner (region name, province header, and 1-line {@code "Rumours here: ..."}
     * subtitle) and triggers the region's musical sting via {@link MusicController}.
     */
    public static void showArrivalBanner(Minecraft mc, String regionId, boolean includeNarrationCard) {
        Region region = Regions.byId(regionId).orElseGet(() ->
                mc.level != null && mc.player != null ? Regions.at(mc.level, mc.player.blockPosition()) : null);
        String key = GuideEvents.shortKey(regionId);
        if (region != null) {
            bannerTitle = spaced(region.name().getString());
            bannerProvince = Provinces.name(region.province());
        } else {
            bannerTitle = spaced(key.replace('_', ' '));
            bannerProvince = Component.empty();
        }
        bannerRumour = Component.translatable("skycraft.guide.banner.rumour_sub",
                Component.translatable("skycraft.guide.rumour." + key));
        bannerStartMs = Util.getMillis();

        // Trigger the region musical sting and notify MusicController to duck background music
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(DISCOVERY_STING), 1.0f, 0.85f));
        MusicController.stingStarted(9);

        if (includeNarrationCard) {
            NarrationOverlay.show(Component.translatable("skycraft.guide.arrive." + key), 140);
        }
    }

    private static Component spaced(String s) {
        StringBuilder sb = new StringBuilder();
        String up = s.toUpperCase(Locale.ROOT);
        for (int i = 0; i < up.length(); i++) {
            if (i > 0) sb.append(up.charAt(i) == ' ' ? "  " : " ");
            if (up.charAt(i) != ' ') sb.append(up.charAt(i));
        }
        return Component.literal(sb.toString());
    }

    public static void renderArrivalBanner(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        if (bannerTitle == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        long age = Util.getMillis() - bannerStartMs;
        if (age < 0 || age > ARRIVAL_BANNER_MS) {
            bannerTitle = null;
            return;
        }
        float in = age / 500f;
        float out = (ARRIVAL_BANNER_MS - age) / 1000f;
        float a = Mth.clamp(Math.min(in, out), 0f, 1f);
        if (a < 0.03f) return;

        Font font = mc.font;
        int alpha = (int) (a * 255) << 24;
        int y = Math.max(96, height / 2 - 68);

        if (bannerProvince != null && !bannerProvince.getString().isEmpty()) {
            g.drawCenteredString(font, bannerProvince, width / 2, y - 12, alpha | 0xC8BFA6);
        }
        g.pose().pushPose();
        g.pose().translate(width / 2f, y, 0);
        g.pose().scale(1.6f, 1.6f, 1f);
        g.drawCenteredString(font, bannerTitle, 0, 0, alpha | 0xF5EBC8);
        g.pose().popPose();

        int half = Math.min(width / 2 - 12, (int) (font.width(bannerTitle) * 0.85f) + 16);
        g.fill(width / 2 - half, y + 16, width / 2 + half, y + 17, alpha | 0x8A7F66);

        if (bannerRumour != null) {
            String sub = font.plainSubstrByWidth(bannerRumour.getString(), width - 32);
            g.drawCenteredString(font, sub, width / 2, y + 21, alpha | 0xE6D296);
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("handbook")
                .executes(ctx -> {
                    openPending = true;
                    return 1;
                })
                .then(Commands.literal("chapter").then(Commands.argument("id", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            HandbookScreen.CHAPTERS.forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            openChapter = StringArgumentType.getString(ctx, "id");
                            openPending = true;
                            return 1;
                        })))
                .then(Commands.literal("arrival").then(Commands.argument("region", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> {
                            for (Region r : Regions.all()) {
                                if (!Provinces.isSea(r)) b.suggest(r.id());
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            showArrivalBanner(Minecraft.getInstance(), StringArgumentType.getString(ctx, "region"), true);
                            return 1;
                        })))
                .then(Commands.literal("hints")
                        .then(Commands.literal("reset").executes(ctx -> {
                            TutorialHints.reset();
                            ctx.getSource().sendSuccess(() -> Component.translatable("guide.skycraft.hints.reset"), false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            TutorialHints.setEnabled(false);
                            ctx.getSource().sendSuccess(() -> Component.translatable("guide.skycraft.hints.off"), false);
                            return 1;
                        }))
                        .then(Commands.literal("on").executes(ctx -> {
                            TutorialHints.setEnabled(true);
                            ctx.getSource().sendSuccess(() -> Component.translatable("guide.skycraft.hints.on"), false);
                            return 1;
                        }))
                        .then(Commands.literal("show").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    TutorialHints.force(StringArgumentType.getString(ctx, "id"));
                                    return 1;
                                })))));
    }

    /** Mod-bus client registration. */
    @Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void overlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("region_arrival", GuideClient::renderArrivalBanner);
            event.registerAboveAll("tutorial_hints", TutorialHints::render);
        }
    }
}
