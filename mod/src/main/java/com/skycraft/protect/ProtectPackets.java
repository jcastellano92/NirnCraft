package com.skycraft.protect;

import com.skycraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client-side prediction of the world protection (server stays authoritative): the server tells each client the
 * homestead claims around it (and whether the rules apply to this player at all), and tells it about every denial so
 * the client stops drawing cracks and ghost removals for blocks that will not break. See
 * {@code protect.client.ProtectClient}.
 */
public final class ProtectPackets {
    private ProtectPackets() {}

    public static void register() {
        SkyNetwork.register(Policy.class, NetworkDirection.PLAY_TO_CLIENT, Policy::encode, Policy::decode, Policy::handle);
        SkyNetwork.register(Denied.class, NetworkDirection.PLAY_TO_CLIENT, Denied::encode, Denied::decode, Denied::handle);
    }

    /** One homestead claim as the client needs it. */
    public record ClaimView(int x, int y, int z, int radius, int minDepth, int maxHeight, boolean mine) {
        public boolean containsHorizontally(BlockPos p) {
            long dx = p.getX() - x, dz = p.getZ() - z;
            return dx * dx + dz * dz <= (long) radius * radius;
        }
    }

    /** The rules that apply to this player and the claims near them. {@code enabled} false: nothing is predicted. */
    public record Policy(boolean enabled, boolean bypass, List<ClaimView> claims) {
        static void encode(Policy m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.enabled);
            buf.writeBoolean(m.bypass);
            buf.writeVarInt(m.claims.size());
            for (ClaimView c : m.claims) {
                buf.writeVarInt(c.x);
                buf.writeVarInt(c.y);
                buf.writeVarInt(c.z);
                buf.writeVarInt(c.radius);
                buf.writeVarInt(c.minDepth);
                buf.writeVarInt(c.maxHeight);
                buf.writeBoolean(c.mine);
            }
        }

        static Policy decode(FriendlyByteBuf buf) {
            boolean enabled = buf.readBoolean();
            boolean bypass = buf.readBoolean();
            int n = Math.min(buf.readVarInt(), 512);
            List<ClaimView> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new ClaimView(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readBoolean()));
            }
            return new Policy(enabled, bypass, list);
        }

        static void handle(Policy m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.protect.client.ProtectClient.onPolicy(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /** The server refused to let this player break the block at {@code pos}: stop predicting it (and its kind nearby). */
    public record Denied(BlockPos pos, String blockId) {
        static void encode(Denied m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeUtf(m.blockId, 128);
        }

        static Denied decode(FriendlyByteBuf buf) {
            return new Denied(buf.readBlockPos(), buf.readUtf(128));
        }

        static void handle(Denied m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.protect.client.ProtectClient.onDenied(m));
            ctx.get().setPacketHandled(true);
        }
    }

    static void sendPolicy(ServerPlayer player, Policy policy) {
        SkyNetwork.sendToPlayer(player, policy);
    }

    static void sendDenied(ServerPlayer player, BlockPos pos, String blockId) {
        SkyNetwork.sendToPlayer(player, new Denied(pos.immutable(), blockId));
    }
}
