package com.nirncraft.fire;

import com.nirncraft.fire.client.FireClientPackets;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Network messages of the fire module (registered in this fixed order, after every older module's). */
public final class FirePackets {
    private FirePackets() {}

    public static void register() {
        SkyNetwork.register(SyncSites.class, PacketFlow.CLIENTBOUND, SyncSites::encode, SyncSites::decode, SyncSites::handle);
        SkyNetwork.register(Burst.class, PacketFlow.CLIENTBOUND, Burst::encode, Burst::decode, Burst::handle);
    }

    /** S2C: the full list of fire sites around the player in {@code dimension} (replaces the client's server sites). */
    public record SyncSites(Identifier dimension, List<FireSites.Site> sites) {
        static void encode(SyncSites m, RegistryFriendlyByteBuf buf) {
            buf.writeIdentifier(m.dimension);
            buf.writeVarInt(m.sites.size());
            for (FireSites.Site s : m.sites) {
                buf.writeVarInt(s.id());
                buf.writeInt(s.x());
                buf.writeInt(s.y());
                buf.writeInt(s.z());
                buf.writeByte(Math.round(Math.min(2.55f, s.intensity()) * 100));
                buf.writeByte(s.height());
                buf.writeByte(s.smoke());
                buf.writeByte(s.flags());
                // remaining life of a temporary site in ticks (0 = persistent), so the client can fade it out
                buf.writeVarInt(0);
            }
        }

        static SyncSites decode(RegistryFriendlyByteBuf buf) {
            Identifier dim = buf.readIdentifier();
            int n = buf.readVarInt();
            List<FireSites.Site> out = new ArrayList<>(Math.min(n, 1024));
            for (int i = 0; i < n && i < 4096; i++) {
                int id = buf.readVarInt();
                int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
                float intensity = (buf.readByte() & 0xFF) / 100.0f;
                int h = buf.readByte() & 0xFF, smoke = buf.readByte(), flags = buf.readByte();
                buf.readVarInt();
                out.add(new FireSites.Site(id, x, y, z, intensity, h, smoke, flags, 0L));
            }
            return new SyncSites(dim, out);
        }

        static void handle(SyncSites m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> FireClientPackets.sync(m.dimension, m.sites));
        }
    }

    /** S2C: a one-off blast of fire at a position. */
    public record Burst(double x, double y, double z, float intensity, int radius) {
        static void encode(Burst m, RegistryFriendlyByteBuf buf) {
            buf.writeDouble(m.x);
            buf.writeDouble(m.y);
            buf.writeDouble(m.z);
            buf.writeFloat(m.intensity);
            buf.writeVarInt(m.radius);
        }

        static Burst decode(RegistryFriendlyByteBuf buf) {
            return new Burst(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readVarInt());
        }

        static void handle(Burst m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> FireClientPackets.burst(m.x, m.y, m.z, m.intensity, m.radius));
        }
    }
}
