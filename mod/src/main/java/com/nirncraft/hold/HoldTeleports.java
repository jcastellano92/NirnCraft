package com.nirncraft.hold;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server teleports of each player, as the client is told them: the position packet (ClientboundPlayerPositionPacket)
 * that every server-side teleport sends (ServerGamePacketListenerImpl.teleport: commands, pearls, the mod's travel, a
 * spectator's teleport to an entity, the anti-cheat's corrections). Counted by a handler on the player's connection
 * (public API: Connection.channel()). The jump check (PlayerHold.watchJump) holds an arrival only on a real
 * discontinuity: a big move with a teleport since the last ticks. Flight, however fast, and a lagging server's long
 * ticks (78 blocks in one server tick of flight held the owner on 10-26) send none.
 */
final class HoldTeleports {
    static final String HANDLER = "nirncraft_hold_teleports";
    private static final Map<UUID, AtomicLong> COUNT = new ConcurrentHashMap<>();

    private HoldTeleports() {}

    /** Watches this player's connection (once per login); false when it has no channel to watch (a stand-in). */
    static boolean install(ServerPlayer p) {
        AtomicLong n = COUNT.computeIfAbsent(p.getUUID(), k -> new AtomicLong());
        try {
            if (p.connection == null) return false;
            ChannelPipeline pl = p.connection.getConnection().channel().pipeline();
            if (pl.get(HANDLER) != null) pl.remove(HANDLER);
            var h = new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    if (isTeleport(msg)) n.incrementAndGet();
                    super.write(ctx, msg, promise);
                }
            };
            if (pl.get("packet_handler") != null) pl.addBefore("packet_handler", HANDLER, h);
            else pl.addLast(HANDLER, h);
            return true;
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("hold: cannot watch {}'s teleports (arrivals hold on teleport events only)", p.getGameProfile().name(), e);
            return false;
        }
    }

    static void forget(UUID id) {
        COUNT.remove(id);
    }

    /** Teleports sent to this player so far (0 when not watched). */
    static long count(UUID id) {
        AtomicLong n = COUNT.get(id);
        return n == null ? 0 : n.get();
    }

    /** (tests, the stand-ins) A teleport as the handler would count it. */
    static void countForTests(UUID id) {
        COUNT.computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet();
    }

    static boolean isTeleport(Object msg) {
        if (msg instanceof ClientboundPlayerPositionPacket) return true;
        if (msg instanceof ClientboundBundlePacket b)
            for (Packet<?> sub : b.subPackets()) if (sub instanceof ClientboundPlayerPositionPacket) return true;
        return false;
    }
}
