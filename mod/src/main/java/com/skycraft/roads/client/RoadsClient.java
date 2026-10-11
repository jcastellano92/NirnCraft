package com.skycraft.roads.client;

import com.skycraft.Skycraft;
import com.skycraft.client.hud.CompassMarkers;
import com.skycraft.roads.RoadsPackets;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/** Client side of the roads module: compass markers for nearby, not yet discovered settlements. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class RoadsClient {
    public static final String SOURCE = "roads";
    private static final int COLOR = 0xC8B48A;

    private RoadsClient() {}

    public static void setMarkers(int range, List<RoadsPackets.Entry> entries) {
        if (entries.isEmpty()) {
            CompassMarkers.clear(SOURCE);
            return;
        }
        List<CompassMarkers.Marker> markers = new ArrayList<>(entries.size());
        for (RoadsPackets.Entry e : entries) {
            markers.add(new CompassMarkers.Marker(new Vec3(e.x() + 0.5, e.y(), e.z() + 0.5),
                    CompassMarkers.Shape.LOCATION, COLOR, e.name(), range));
        }
        CompassMarkers.set(SOURCE, markers);
    }

    /** The planned road network (for the map), replaced when part 0 of a new transfer arrives. */
    private static List<RoadsPackets.Line> network = List.of();
    private static List<RoadsPackets.Line> incoming = new ArrayList<>();

    public static void setNetwork(int part, List<RoadsPackets.Line> lines) {
        if (part == 0) incoming = new ArrayList<>();
        incoming.addAll(lines);
        network = List.copyOf(incoming);
        RoadMapLayer.invalidate();
    }

    public static List<RoadsPackets.Line> network() {
        return network;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CompassMarkers.clear(SOURCE);
        network = List.of();
        incoming = new ArrayList<>();
        RoadMapLayer.invalidate();
    }
}
