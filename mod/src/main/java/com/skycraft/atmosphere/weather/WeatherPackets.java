package com.skycraft.atmosphere.weather;

import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -> client sync of the regional weather table (a few bytes per region, sent only when a spell changes). */
public final class WeatherPackets {
    private WeatherPackets() {}

    public static void register() {
        SkyNetwork.register(Sync.class, NetworkDirection.PLAY_TO_CLIENT, Sync::encode, Sync::decode, Sync::handle);
    }

    /** One region's current weather; {@code kind < 0} removes the region from the client's table. */
    public record Entry(String region, int kind, int wind) {}

    /**
     * @param full replace the client's whole table (login, dimension change) instead of patching it
     */
    public record Sync(boolean full, List<Entry> entries) {
        private static final int MAX_ENTRIES = 4096;

        static void encode(Sync m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.full);
            buf.writeVarInt(m.entries.size());
            for (Entry e : m.entries) {
                buf.writeUtf(e.region, 128);
                buf.writeByte(e.kind);
                buf.writeShort(e.wind);
            }
        }

        static Sync decode(FriendlyByteBuf buf) {
            boolean full = buf.readBoolean();
            int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
            List<Entry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new Entry(buf.readUtf(128), buf.readByte(), buf.readShort()));
            }
            return new Sync(full, list);
        }

        static void handle(Sync m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.atmosphere.client.ClientWeather.receive(m));
            ctx.get().setPacketHandled(true);
        }
    }
}
