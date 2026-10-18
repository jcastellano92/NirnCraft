package com.skycraft.quest.party;

import com.skycraft.core.Notifier;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.QuestPackets;
import com.skycraft.vitals.Vitals;
import com.skycraft.world.FastTravelHandler;
import com.skycraft.world.WorldConfig;
import com.skycraft.world.WorldPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Fast travel to a party member (server-authoritative). Used by the join prompt ("Fast travel to the party
 * leader?"), the map and the Party screen. Works across dimensions; pets/followers come along within a dimension.
 */
public final class PartyTravel {
    private PartyTravel() {}

    private static void fail(ServerPlayer player, String reason, Object... args) {
        Notifier.message(player, Component.translatable("party.skycraft.travel." + reason, args));
    }

    private static boolean jailed(ServerPlayer p) {
        return p.level().dimension().identifier().equals(FastTravelHandler.JAIL)
                || com.skycraft.crime.Jail.isJailed(p)
                || com.skycraft.crime.Jail.isLoadingJail(p);
    }

    /** Validates and performs travel of {@code player} to the party member {@code targetId}. */
    public static boolean travel(ServerPlayer player, UUID targetId) {
        MinecraftServer server = player.level().getServer();
        if (!WorldConfig.FAST_TRAVEL.get()) {
            fail(player, "disabled");
            return false;
        }
        Party party = PartyManager.get(server).partyOf(player.getUUID());
        if (party == null || !party.has(targetId) || targetId.equals(player.getUUID())) {
            fail(player, "not_member");
            return false;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null || !target.isAlive() || target.isSpectator()) {
            fail(player, "offline", party.nameOf(targetId));
            return false;
        }
        if (jailed(player)) {
            fail(player, "jail");
            return false;
        }
        if (Vitals.inCombat(player)) {
            fail(player, "combat");
            return false;
        }
        if (!player.isAlive() || player.fallDistance > 3f || player.isFallFlying()) {
            fail(player, "falling");
            return false;
        }

        ServerLevel from = (ServerLevel) player.level();
        ServerLevel to;
        BlockPos anchorPos;
        if (jailed(target)) {
            com.skycraft.crime.Jail.Exit exit = com.skycraft.crime.Jail.outsideFor(target);
            to = exit.level();
            anchorPos = exit.pos();
        } else {
            to = (ServerLevel) target.level();
            anchorPos = target.blockPosition();
        }
        BlockPos spot = findSpot(to, anchorPos);
        List<Mob> followers = from == to
                ? from.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                m -> m.isAlive() && !m.isPassenger() && FastTravelHandler.isFollower(m, player))
                : List.of();

        player.stopRiding();
        player.teleportTo(to, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, java.util.Set.of(), target.getYRot(), 0f, false);
        player.resetFallDistance();
        int i = 0;
        for (Mob mob : followers) {
            double angle = i++ * (Math.PI * 2 / Math.max(1, followers.size()));
            mob.getNavigation().stop();
            mob.teleportTo(spot.getX() + 0.5 + Math.cos(angle) * 1.5, spot.getY(), spot.getZ() + 0.5 + Math.sin(angle) * 1.5);
            mob.resetFallDistance();
        }
        SkyNetwork.sendToPlayer(player, new WorldPackets.RestFade(0, WorldPackets.RestFade.TRAVEL));
        Notifier.message(player, Component.translatable("party.skycraft.travel.arrived", target.getDisplayName()));
        Notifier.message(target, Component.translatable("party.skycraft.travel.joined_you", player.getDisplayName()));
        return true;
    }

    /** A safe standing spot next to {@code anchor} (falls back to the anchor itself, where the target stands). */
    private static BlockPos findSpot(ServerLevel level, BlockPos anchor) {
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 0; dy <= 3; dy++) {
                        BlockPos up = anchor.offset(dx, dy, dz);
                        if (level.isLoaded(up) && FastTravelHandler.isSafe(level, up)) return up;
                        BlockPos down = anchor.offset(dx, -dy, dz);
                        if (dy > 0 && level.isLoaded(down) && FastTravelHandler.isSafe(level, down)) return down;
                    }
                }
            }
        }
        return anchor;
    }

    // ------------------------------------------------------------------ join prompt

    /**
     * Who a player should be offered to travel to: the leader, or (if they lead) the nearest online member,
     * preferring the same dimension. Null if nobody else is online.
     */
    @Nullable
    public static ServerPlayer promptTarget(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Party party = PartyManager.get(server).partyOf(player.getUUID());
        if (party == null) return null;
        if (!party.leader.equals(player.getUUID())) {
            ServerPlayer leader = server.getPlayerList().getPlayer(party.leader);
            if (leader != null && !leader.isSpectator()) return leader;
        }
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID id : party.memberIds()) {
            if (id.equals(player.getUUID())) continue;
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m == null || m.isSpectator()) continue;
            double d = m.level() == player.level() ? m.distanceToSqr(player) : 1e12;
            if (best == null || d < bestDist) {
                best = m;
                bestDist = d;
            }
        }
        return best;
    }

    /** Asks the player whether to fast travel to their party (leader). Does nothing if they're alone. */
    public static void sendPrompt(ServerPlayer player) {
        ServerPlayer target = promptTarget(player);
        if (target == null || jailed(player)) return;
        Party party = PartyManager.get(player.level().getServer()).partyOf(player.getUUID());
        boolean leader = party != null && party.leader.equals(target.getUUID());
        SkyNetwork.sendToPlayer(player, new QuestPackets.PartyTravelPrompt(target.getUUID(), target.getGameProfile().name(), leader));
    }
}
