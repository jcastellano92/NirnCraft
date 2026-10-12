package com.skycraft.perf;

import com.skycraft.network.SkyNetwork;
import com.skycraft.perf.client.PrewarmOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Packets of the perf module (registration order is fixed; ids must match on both sides). */
public final class PerfPackets {
    private PerfPackets() {}

    static void register() {
        SkyNetwork.register(PrewarmProgress.class, NetworkDirection.PLAY_TO_CLIENT, PrewarmProgress::encode,
                PrewarmProgress::decode, PrewarmProgress::handle);
    }

    /** "Travelling to <label>": chunks ready of needed; {@code total == 0} hides the bar. */
    public record PrewarmProgress(int ready, int total, Component label) {
        static void encode(PrewarmProgress m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.ready);
            buf.writeVarInt(m.total);
            buf.writeComponent(m.label);
        }

        static PrewarmProgress decode(FriendlyByteBuf buf) {
            return new PrewarmProgress(buf.readVarInt(), buf.readVarInt(), buf.readComponent());
        }

        static void handle(PrewarmProgress m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PrewarmOverlay.show(m.ready, m.total, m.label));
            ctx.get().setPacketHandled(true);
        }
    }
}
