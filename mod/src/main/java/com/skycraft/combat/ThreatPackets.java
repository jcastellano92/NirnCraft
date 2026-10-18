package com.skycraft.combat;

import com.skycraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** The hostile-awareness compass feed ({@link Threats}): compact deltas, server to the owning client only. */
public final class ThreatPackets {
    /** The most entries one packet may carry (the server never tracks more than {@link Threats#MAX}). */
    private static final int LIMIT = 64;

    private ThreatPackets() {}

    public static void register() {
        SkyNetwork.register(Sync.class, PacketFlow.CLIENTBOUND, Sync::encode, Sync::decode, Sync::handle);
    }

    /**
     * One tracked hostile: its entity id, why it is shown ({@link Threats#COMBAT} or {@link Threats#SEARCH}) and its
     * last known block position (the client prefers the live entity when it is loaded there).
     */
    public record Entry(int id, int kind, int x, int y, int z) {}

    /**
     * A delta of the player's threat list.
     *
     * @param reset   forget everything first (dimension change, respawn)
     * @param upserts hostiles added, changed kind or moved noticeably
     * @param removed entity ids no longer tracked
     */
    public record Sync(boolean reset, List<Entry> upserts, int[] removed) {
        static void encode(Sync m, RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(m.reset);
            int n = Math.min(LIMIT, m.upserts.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Entry e = m.upserts.get(i);
                buf.writeVarInt(e.id);
                buf.writeByte(e.kind);
                buf.writeInt(e.x);
                buf.writeShort(e.y);
                buf.writeInt(e.z);
            }
            int r = Math.min(LIMIT, m.removed.length);
            buf.writeVarInt(r);
            for (int i = 0; i < r; i++) buf.writeVarInt(m.removed[i]);
        }

        static Sync decode(RegistryFriendlyByteBuf buf) {
            boolean reset = buf.readBoolean();
            int n = Math.min(LIMIT, buf.readVarInt());
            List<Entry> up = new ArrayList<>(n);
            for (int i = 0; i < n; i++) up.add(new Entry(buf.readVarInt(), buf.readByte(), buf.readInt(), buf.readShort(), buf.readInt()));
            int r = Math.min(LIMIT, buf.readVarInt());
            int[] removed = new int[r];
            for (int i = 0; i < r; i++) removed[i] = buf.readVarInt();
            return new Sync(reset, up, removed);
        }

        static void handle(Sync m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.skycraft.combat.client.ThreatClient.onSync(m));
        }
    }
}
