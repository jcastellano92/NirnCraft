package com.skycraft.tamriel.settlement.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Developer tool: a scripted camera tour for checking generated settlements. Enabled only by the system property
 * {@code skycraft.tour=<world>;<name>,x,y,z,yaw,pitch;...} (e.g. through {@code JAVA_TOOL_OPTIONS}): loads the
 * singleplayer world, flies the player to each stop at noon, waits for the chunks to build and takes a screenshot
 * ({@code screenshots/tour_<name>.png}), then quits.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SettlementTour {
    private static final String SPEC = System.getProperty("skycraft.tour", "");
    private static final int SETTLE_TICKS = Integer.getInteger("skycraft.tour.wait", 500);

    private record Stop(String name, double x, double y, double z, float yaw, float pitch) {}

    private static List<Stop> stops;
    private static String world;
    private static int state, index, wait;

    private SettlementTour() {}

    private static boolean parse() {
        if (stops != null) return !stops.isEmpty();
        stops = new ArrayList<>();
        String[] parts = SPEC.split(";");
        if (parts.length < 2) return false;
        world = parts[0].trim();
        for (int i = 1; i < parts.length; i++) {
            String[] v = parts[i].trim().split(",");
            if (v.length < 6) continue;
            try {
                stops.add(new Stop(v[0], Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]),
                        Float.parseFloat(v[4]), Float.parseFloat(v[5])));
            } catch (NumberFormatException ignored) {
            }
        }
        return !stops.isEmpty();
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (SPEC.isEmpty() || !parse()) return;
        Minecraft mc = Minecraft.getInstance();
        switch (state) {
            case 0 -> {
                if (mc.gui.screen() instanceof TitleScreen || mc.gui.screen() instanceof com.skycraft.client.screen.title.SkyTitleScreen) {
                    state = 1;
                    Skycraft.LOGGER.info("Settlement tour: loading world {}", world);
                    com.skycraft.client.autotest.ReviewWorlds.open(mc,world,mc.gui.screen());
                }
            }
            case 1 -> {
                // confirm prompts on the way in (experimental settings, backups)
                if (mc.gui.screen() != null && ++wait % 20 == 0) {
                    for (var child : mc.gui.screen().children()) {
                        if (!(child instanceof net.minecraft.client.gui.components.Button b)) continue;
                        String label = b.getMessage().getString().toLowerCase(java.util.Locale.ROOT);
                        if (label.equals("proceed") || label.equals("yes") || label.equals("load") || label.equals("continue")
                                || label.contains("i know what") || label.equals("play")) {
                            Skycraft.LOGGER.info("Settlement tour: pressing '{}' on {}", label, mc.gui.screen().getClass().getSimpleName());
                            b.onPress(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN,0,0));
                            break;
                        }
                    }
                }
                if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null) {
                    state = 2;
                    wait = 100;
                    com.skycraft.client.autotest.ReviewWorlds.hudHidden(mc,true);
                }
            }
            case 2 -> {
                if (mc.gui.screen() != null) mc.gui.setScreen(null);
                if (--wait > 0) return;
                if (index >= stops.size()) {
                    Skycraft.LOGGER.info("Settlement tour: done");
                    state = 4;
                    mc.stop();
                    return;
                }
                Stop s = stops.get(index);
                IntegratedServer server = mc.getSingleplayerServer();
                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set 6000");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"weather clear");
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        p.setGameMode(GameType.CREATIVE);
                        p.getAbilities().flying = true;
                        p.onUpdateAbilities();
                        p.teleportTo(level,s.x(),s.y(),s.z(),java.util.Set.of(),s.yaw(),s.pitch(),true);
                    }
                });
                Skycraft.LOGGER.info("Settlement tour: stop {} at {}, {}, {}", s.name(), s.x(), s.y(), s.z());
                state = 3;
                wait = SETTLE_TICKS;
            }
            case 3 -> {
                if (mc.gui.screen() != null) mc.gui.setScreen(null);
                if (mc.player != null) {
                    Stop s = stops.get(index);
                    mc.player.setYRot(s.yaw());
                    mc.player.setXRot(s.pitch());
                }
                if (--wait > 0) return;
                Stop s = stops.get(index);
                Screenshot.grab(mc.gameDirectory, "tour_" + s.name() + ".png", mc.gameRenderer.mainRenderTarget(), 1, msg -> Skycraft.LOGGER.info("Settlement tour: {}", msg.getString()));
                index++;
                state = 2;
                wait = 20;
            }
            default -> {
            }
        }
    }
}
