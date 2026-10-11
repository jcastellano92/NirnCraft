package com.skycraft.guide.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client side of the guide module: opening the handbook, the {@code /handbook} client command and (on the mod bus,
 * {@link Setup}) the tutorial-hint overlay.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class GuideClient {
    /** Opened on the next tick: a chat command's screen would otherwise be closed by the chat screen itself. */
    private static boolean openPending;
    private static String openChapter;

    private GuideClient() {}

    public static void openHandbook() {
        Minecraft.getInstance().setScreen(new HandbookScreen());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !openPending) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        openPending = false;
        if (openChapter != null) HandbookScreen.preselect(openChapter);
        openChapter = null;
        openHandbook();
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
            event.registerAboveAll("tutorial_hints", TutorialHints::render);
        }
    }
}
