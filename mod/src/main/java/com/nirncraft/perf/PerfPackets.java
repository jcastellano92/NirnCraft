package com.nirncraft.perf;

import com.nirncraft.network.SkyNetwork;
import com.nirncraft.perf.client.PrewarmOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/** Packets of the perf module (registration order is fixed; ids must match on both sides). */
public final class PerfPackets {
    private PerfPackets() {}

    static void register() {
        SkyNetwork.register(PrewarmProgress.class, PacketFlow.CLIENTBOUND, PrewarmProgress::encode,
                PrewarmProgress::decode, PrewarmProgress::handle);
    }

    /** "Travelling to <label>": chunks ready of needed; {@code total == 0} hides the bar. */
    public record PrewarmProgress(int ready, int total, Component label) {
        static void encode(PrewarmProgress m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.ready);
            buf.writeVarInt(m.total);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.label);
        }

        static PrewarmProgress decode(RegistryFriendlyByteBuf buf) {
            return new PrewarmProgress(buf.readVarInt(), buf.readVarInt(), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf));
        }

        static void handle(PrewarmProgress m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> PrewarmOverlay.show(m.ready, m.total, m.label));
        }
    }
}
