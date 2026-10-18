package com.skycraft.roads;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.roads.client.RoadsClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import com.skycraft.platform.Sides;
import net.neoforged.fml.common.Mod;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Roads packets: nearby undiscovered settlements for the compass (compass source {@code "roads"}). */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class RoadsPackets {
    private static final int MAX_MARKERS = 12;
    /** Hash of the last marker list sent to each player, to skip identical updates. */
    private static final Map<UUID, Integer> LAST = new HashMap<>();

    private RoadsPackets() {}

    static void register() {
        SkyNetwork.register(SettlementMarkers.class, PacketFlow.CLIENTBOUND, SettlementMarkers::encode,
                SettlementMarkers::decode, SettlementMarkers::handle);
        SkyNetwork.register(NetworkLines.class, PacketFlow.CLIENTBOUND, NetworkLines::encode,
                NetworkLines::decode, NetworkLines::handle);
    }

    // ------------------------------------------------------------------ the planned network for the map

    /** Roads per packet (each a simplified polyline of a few dozen points). */
    private static final int LINES_PER_PACKET = 160;
    /** Polylines are simplified to within this many blocks of the planned road for the map. */
    private static final double SIMPLIFY = 6.0;
    private static final Map<Integer, Line> SIMPLE = new HashMap<>();
    /** The whole network must be re-sent (it was re-planned). */
    private static boolean networkDirty;
    /** Roads planned since the last broadcast, sent as an addition to what clients have. */
    private static final List<Integer> FRESH = new ArrayList<>();

    /** One road on the map: class, historic name id (or empty) and a simplified polyline (x, z pairs). */
    public record Line(byte kind, String name, int[] xz) {
    }

    /**
     * A part of the planned road network for the map screen. Part 0 replaces whatever the client had, later parts
     * (and the roads planned since) are added to it.
     */
    public record NetworkLines(int part, List<Line> lines) {
        static void encode(NetworkLines m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.part);
            buf.writeVarInt(m.lines.size());
            for (Line l : m.lines) {
                buf.writeByte(l.kind());
                buf.writeUtf(l.name(), 48);
                int n = l.xz().length / 2;
                buf.writeVarInt(n);
                int px = 0, pz = 0;
                for (int i = 0; i < n; i++) {
                    int x = l.xz()[i * 2], z = l.xz()[i * 2 + 1];
                    buf.writeVarInt(zigzag(x - px));
                    buf.writeVarInt(zigzag(z - pz));
                    px = x;
                    pz = z;
                }
            }
        }

        static NetworkLines decode(RegistryFriendlyByteBuf buf) {
            int part = buf.readVarInt();
            int count = Math.min(buf.readVarInt(), LINES_PER_PACKET * 2);
            List<Line> lines = new ArrayList<>(count);
            for (int k = 0; k < count; k++) {
                byte kind = buf.readByte();
                String name = buf.readUtf(48);
                int n = Math.min(buf.readVarInt(), 4096);
                int[] xz = new int[n * 2];
                int px = 0, pz = 0;
                for (int i = 0; i < n; i++) {
                    px += unzigzag(buf.readVarInt());
                    pz += unzigzag(buf.readVarInt());
                    xz[i * 2] = px;
                    xz[i * 2 + 1] = pz;
                }
                lines.add(new Line(kind, name, xz));
            }
            return new NetworkLines(part, lines);
        }

        static void handle(NetworkLines m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> RoadsClient.setNetwork(m.part, m.lines));
        }
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    /** The network was forgotten and is planned again: everyone gets it afresh at the next opportunity. */
    static void networkReset() {
        networkDirty = true;
        FRESH.clear();
        SIMPLE.clear();
    }

    /** A road was planned: it is added to everyone's map at the next opportunity. */
    static void roadAdded(Road road) {
        FRESH.add(road.id);
    }

    /** Sends the roads planned since the last call (or everything after a re-plan); called every few seconds. */
    static void sendNetworkIfChanged(ServerLevel level, RoadsData data) {
        if (networkDirty) {
            networkDirty = false;
            FRESH.clear();
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) sendNetwork(player, data);
            return;
        }
        if (FRESH.isEmpty()) return;
        List<Line> lines = new ArrayList<>(FRESH.size());
        for (int id : FRESH) {
            Road road = data.road(id);
            if (road != null) lines.add(SIMPLE.computeIfAbsent(id, k -> simplify(road)));
        }
        FRESH.clear();
        for (int i = 0; i < lines.size(); i += LINES_PER_PACKET) {
            NetworkLines msg = new NetworkLines(1, lines.subList(i, Math.min(lines.size(), i + LINES_PER_PACKET)));
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) SkyNetwork.sendToPlayer(player, msg);
        }
    }

    static void sendNetwork(ServerPlayer player, RoadsData data) {
        List<Line> all = new ArrayList<>(data.roads.size());
        for (Road road : data.roads.values()) all.add(SIMPLE.computeIfAbsent(road.id, k -> simplify(road)));
        int part = 0;
        for (int i = 0; i < all.size() || part == 0; i += LINES_PER_PACKET) {
            SkyNetwork.sendToPlayer(player, new NetworkLines(part++, all.subList(Math.min(i, all.size()),
                    Math.min(all.size(), i + LINES_PER_PACKET))));
        }
    }

    /** Douglas-Peucker simplification of a road to {@link #SIMPLIFY} blocks. */
    static Line simplify(Road road) {
        int n = road.size();
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            int a = seg[0], b = seg[1];
            if (b - a < 2) continue;
            double ax = road.xs[a], az = road.zs[a], dx = road.xs[b] - ax, dz = road.zs[b] - az;
            double len = Math.sqrt(dx * dx + dz * dz);
            int far = -1;
            double farD = SIMPLIFY;
            for (int i = a + 1; i < b; i++) {
                double d = len < 1e-6 ? Math.hypot(road.xs[i] - ax, road.zs[i] - az)
                        : Math.abs((road.xs[i] - ax) * dz - (road.zs[i] - az) * dx) / len;
                if (d > farD) {
                    farD = d;
                    far = i;
                }
            }
            if (far < 0) continue;
            keep[far] = true;
            stack.push(new int[]{a, far});
            stack.push(new int[]{far, b});
        }
        it.unimi.dsi.fastutil.ints.IntArrayList xz = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            xz.add(road.xs[i]);
            xz.add(road.zs[i]);
        }
        return new Line(road.kind, road.name, xz.toIntArray());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel overworld = player.level().getServer().overworld();
        sendNetwork(player, RoadsData.get(overworld));
    }

    public record Entry(int x, int y, int z, String name) {
    }

    /** The full list of settlement markers the client should show (replaces the previous list). */
    public record SettlementMarkers(int range, List<Entry> entries) {
        static void encode(SettlementMarkers m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.range);
            buf.writeVarInt(m.entries.size());
            for (Entry e : m.entries) {
                buf.writeVarInt(e.x());
                buf.writeVarInt(e.y());
                buf.writeVarInt(e.z());
                buf.writeUtf(e.name(), 64);
            }
        }

        static SettlementMarkers decode(RegistryFriendlyByteBuf buf) {
            int range = buf.readVarInt();
            int n = Math.min(buf.readVarInt(), 64);
            List<Entry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(new Entry(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64)));
            return new SettlementMarkers(range, list);
        }

        static void handle(SettlementMarkers m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> RoadsClient.setMarkers(m.range, m.entries));
        }
    }

    /** Sends every player in the road level the settlements near them that they haven't discovered yet. */
    static void sendMarkers(ServerLevel level, RoadsData data) {
        boolean enabled = RoadsConfig.COMPASS_MARKERS.get();
        int range = RoadsConfig.COMPASS_RANGE.get();
        double reach = (range + 64.0) * (range + 64.0);
        for (ServerPlayer player : level.players()) {
            List<Entry> list = new ArrayList<>();
            if (enabled) {
                PlayerData pd = SkyData.get(player);
                List<Settlement> near = new ArrayList<>();
                for (Settlement s : data.settlements) {
                    if (s.distSq(player.getX(), player.getZ()) <= reach && !SettlementNames.discovered(pd, s)) near.add(s);
                }
                near.sort(Comparator.comparingDouble(s -> s.distSq(player.getX(), player.getZ())));
                for (int i = 0; i < near.size() && i < MAX_MARKERS; i++) {
                    Settlement s = near.get(i);
                    list.add(new Entry(s.x, s.y, s.z, s.name));
                }
            }
            send(player, new SettlementMarkers(range, list));
        }
    }

    private static void send(ServerPlayer player, SettlementMarkers msg) {
        int hash = msg.hashCode();
        Integer last = LAST.get(player.getUUID());
        if (last != null && last == hash) return;
        LAST.put(player.getUUID(), hash);
        SkyNetwork.sendToPlayer(player, msg);
    }

    static void clear() {
        LAST.clear();
        SIMPLE.clear();
        FRESH.clear();
        networkDirty = false;
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !RoadNetwork.isRoadLevel(player.level())) {
            send(player, new SettlementMarkers(0, List.of()));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }
}
