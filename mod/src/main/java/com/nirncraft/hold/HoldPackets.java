package com.nirncraft.hold;

import com.nirncraft.network.SkyNetwork;
import com.nirncraft.platform.Sides;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The player hold's two messages (docs/ATLAS-PLAN.md section 0.9). The server holds a player from login until their
 * client says its loading is complete; the client is the authority on its own loading (atlas generates and draws the
 * world on its GPU), the server is the authority on what a held player may do.
 */
public final class HoldPackets {
    private HoldPackets() {}

    public static void register() {
        SkyNetwork.register(Held.class, PacketFlow.CLIENTBOUND, Held::encode, Held::decode, Held::handle);
        SkyNetwork.register(Ready.class, PacketFlow.SERVERBOUND, Ready::encode, Ready::decode, Ready::handle);
    }

    /**
     * Server to client: "you are held" ({@code held}) or "you are released" (the client may close its loading screen).
     * {@code token} names this hold; the client echoes it in {@link Ready}. {@code reason} is "login" or why a player in
     * play is held again on arriving somewhere (a teleport, a dimension change, a respawn, a jump): the client shows its
     * loading screen only if the arrival is not ready within a moment, so a warm arrival stays instant.
     */
    public record Held(boolean held, long token, String reason) {
        static void encode(Held m, RegistryFriendlyByteBuf b) {
            b.writeBoolean(m.held);
            b.writeVarLong(m.token);
            b.writeUtf(m.reason, 64);
        }

        static Held decode(RegistryFriendlyByteBuf b) {
            return new Held(b.readBoolean(), b.readVarLong(), b.readUtf(64));
        }

        static void handle(Held m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.hold.client.HoldClient.onHeld(m));
        }
    }

    /**
     * Client to server: this client's loading is complete ({@code fallback} false), or the player chose to enter without
     * it after a failure or a timeout ({@code fallback} true: logged, never silent). A stale or unknown token is ignored.
     */
    public record Ready(long token, boolean fallback) {
        static void encode(Ready m, RegistryFriendlyByteBuf b) {
            b.writeVarLong(m.token);
            b.writeBoolean(m.fallback);
        }

        static Ready decode(RegistryFriendlyByteBuf b) {
            return new Ready(b.readVarLong(), b.readBoolean());
        }

        static void handle(Ready m, IPayloadContext ctx) {
            ServerPlayer p = SkyNetwork.sender(ctx);
            if (p != null) PlayerHold.onReady(p, m.token, m.fallback);
        }
    }
}
