package com.nirncraft.atmosphere.weather;

import com.nirncraft.atmosphere.WeatherFront;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server -> client sync of the regional weather table and active moving synoptic {@link WeatherFront}s. */
public final class WeatherPackets {
    private WeatherPackets() {}

    public static void register() {
        SkyNetwork.register(Sync.class, PacketFlow.CLIENTBOUND, Sync::encode, Sync::decode, Sync::handle);
    }

    /** One region's current weather; {@code kind < 0} removes the region from the client's table. */
    public record Entry(String region, int kind, int wind) {}

    /**
     * @param full   replace the client's whole table (login, dimension change) instead of patching it
     * @param fronts current server-authoritative moving weather fronts (null when unchanged)
     */
    public record Sync(boolean full, List<Entry> entries, List<WeatherFront> fronts) {
        private static final int MAX_ENTRIES = 4096;
        private static final int MAX_FRONTS = 128;

        public Sync(boolean full, List<Entry> entries) {
            this(full, entries, null);
        }

        public static void encode(Sync m, RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(m.full);
            buf.writeVarInt(m.entries.size());
            for (Entry e : m.entries) {
                buf.writeUtf(e.region, 128);
                buf.writeByte(e.kind);
                buf.writeShort(e.wind);
            }
            boolean hasFronts = m.fronts != null;
            buf.writeBoolean(hasFronts);
            if (hasFronts) {
                buf.writeVarInt(m.fronts.size());
                for (WeatherFront f : m.fronts) {
                    f.write(buf);
                }
            }
        }

        public static Sync decode(RegistryFriendlyByteBuf buf) {
            boolean full = buf.readBoolean();
            int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
            List<Entry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new Entry(buf.readUtf(128), buf.readByte(), buf.readShort()));
            }
            List<WeatherFront> fronts = null;
            if (buf.isReadable() && buf.readBoolean()) {
                int fn = Math.min(buf.readVarInt(), MAX_FRONTS);
                fronts = new ArrayList<>(fn);
                for (int i = 0; i < fn; i++) {
                    fronts.add(WeatherFront.read(buf));
                }
            }
            return new Sync(full, list, fronts);
        }

        static void handle(Sync m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.atmosphere.client.ClientWeather.receive(m));
        }
    }
}
