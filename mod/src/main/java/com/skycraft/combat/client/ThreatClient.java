package com.skycraft.combat.client;

import com.skycraft.Skycraft;
import com.skycraft.client.hud.CompassMarkers;
import com.skycraft.combat.ThreatPackets;
import com.skycraft.combat.Threats;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client end of the hostile-awareness compass ({@link Threats}): keeps the server's list of hunters (red) and
 * searchers (yellow-orange hollow) and publishes it as the {@code "threat"} compass source. A marker follows the live
 * entity when this client has it loaded, otherwise the last position the server sent; it goes away when the server
 * drops it, when the entity dies here, and on logout, respawn and dimension change.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ThreatClient {
    private static final String SOURCE = "threat";
    private static final int ENEMY_COLOR = 0xD03030;
    private static final int SEARCH_COLOR = 0xE8A838;

    private static final Map<Integer, ThreatPackets.Entry> ENTRIES = new HashMap<>();

    private ThreatClient() {}

    public static void onSync(ThreatPackets.Sync m) {
        if (m.reset()) ENTRIES.clear();
        for (int id : m.removed()) ENTRIES.remove(id);
        for (ThreatPackets.Entry e : m.upserts()) ENTRIES.put(e.id(), e);
        publish();
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (ENTRIES.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            clear();
            return;
        }
        publish();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        clear();
    }

    private static void clear() {
        ENTRIES.clear();
        CompassMarkers.clear(SOURCE);
    }

    private static void publish() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ENTRIES.isEmpty()) {
            CompassMarkers.clear(SOURCE);
            return;
        }
        List<CompassMarkers.Marker> out = new ArrayList<>(ENTRIES.size());
        for (ThreatPackets.Entry e : ENTRIES.values()) {
            Entity live = mc.level.getEntity(e.id());
            Vec3 pos;
            if (live != null) {
                if (!live.isAlive()) continue;
                pos = live.position();
            } else {
                pos = new Vec3(e.x() + 0.5, e.y(), e.z() + 0.5);
            }
            boolean combat = e.kind() == Threats.COMBAT;
            out.add(new CompassMarkers.Marker(pos, combat ? CompassMarkers.Shape.ENEMY : CompassMarkers.Shape.SEARCHING,
                    combat ? ENEMY_COLOR : SEARCH_COLOR, "", 0));
        }
        CompassMarkers.set(SOURCE, out);
    }
}
