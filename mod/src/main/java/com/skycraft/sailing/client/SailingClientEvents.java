package com.skycraft.sailing.client;

import com.skycraft.Skycraft;
import com.skycraft.client.hud.CompassMarkers;
import com.skycraft.network.SkyNetwork;
import com.skycraft.sailing.Port;
import com.skycraft.sailing.PortSites;
import com.skycraft.sailing.Ports;
import com.skycraft.sailing.SailingPackets;
import com.skycraft.sailing.SeaRoutes;
import com.skycraft.sailing.ShipEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/**
 * Forge-bus client logic for sailing: feeds the helmsman's movement keys to the ship (and tells the server, so other
 * players see the oars and rudder), the sail keys, and the compass marker for the nearest port.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SailingClientEvents {
    public static final String COMPASS_SOURCE = "sailing";
    private static final int COMPASS_RANGE = 2000;
    private static final int COMPASS_COLOR = 0x8FC4E8;

    private static int lastBits = -1;
    private static int lastShip = -1;
    private static int ticks;
    private static String lastPort = "";

    private SailingClientEvents() {}

    public static void openShipwright(BlockPos pos) {
        Minecraft.getInstance().gui.setScreen(new ShipwrightScreen(pos));
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (player.getVehicle() instanceof ShipEntity ship) {
            if (ship.getControllingPassenger() == player) {
                ship.setInput(player.input.keyPresses.forward(), player.input.keyPresses.backward(), player.input.keyPresses.left(), player.input.keyPresses.right());
                int bits = ship.localInput();
                if (bits != lastBits || ship.getId() != lastShip) {
                    SkyNetwork.sendToServer(new SailingPackets.ShipControl(ship.getId(), bits));
                    lastBits = bits;
                    lastShip = ship.getId();
                }
            }
            while (SailingClient.SAIL_UP.consumeClick()) SkyNetwork.sendToServer(new SailingPackets.SailTrim(ship.getId(), 1));
            while (SailingClient.SAIL_DOWN.consumeClick()) SkyNetwork.sendToServer(new SailingPackets.SailTrim(ship.getId(), -1));
        } else {
            lastBits = -1;
            lastShip = -1;
            while (SailingClient.SAIL_UP.consumeClick()) {
                // not aboard: nothing to trim
            }
            while (SailingClient.SAIL_DOWN.consumeClick()) {
                // not aboard: nothing to trim
            }
        }
        if (++ticks % 40 == 0) updateCompass(player);
    }

    /** The nearest port's pier on the compass (overworld only). */
    private static void updateCompass(LocalPlayer player) {
        if (player.level().dimension() != Level.OVERWORLD) {
            clearCompass();
            return;
        }
        if (!SeaRoutes.sitesReady()) return;
        Port best = null;
        double bestD = (double) COMPASS_RANGE * COMPASS_RANGE;
        for (Port p : Ports.all()) {
            PortSites.Site s = PortSites.of(p);
            double dx = s.pierX() - player.getX(), dz = s.pierZ() - player.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best == null || bestD < 20 * 20) {
            clearCompass();
            return;
        }
        if (best.id().equals(lastPort)) return;
        lastPort = best.id();
        PortSites.Site s = PortSites.of(best);
        CompassMarkers.set(COMPASS_SOURCE, List.of(new CompassMarkers.Marker(new Vec3(s.pierX() + 0.5, 64, s.pierZ() + 0.5),
                CompassMarkers.Shape.LOCATION, COMPASS_COLOR, best.name().getString(), COMPASS_RANGE)));
    }

    private static void clearCompass() {
        if (lastPort.isEmpty()) return;
        lastPort = "";
        CompassMarkers.clear(COMPASS_SOURCE);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        lastPort = "";
        lastBits = -1;
        CompassMarkers.clear(COMPASS_SOURCE);
        VoyageOverlay.stop();
    }
}
