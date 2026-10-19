package com.nirncraft.combat;

import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Contract 9 (docs/PLAYTEST-1.md): the player's rest point. Death sends you to a bed you actually slept in and may
 * use (an owned house, a rented inn room, a bed of your own); otherwise to the nearest settlement you have
 * discovered ({@code core.PlayerDataEvents}); the world spawn only when you have found nothing yet.
 *
 * <p>{@link #set} is the provisional kind (the intro cottage's bed is where you wake, not a home): it moves where the
 * vanilla respawn would go, but a death does not honour it. {@link #setOwned} is a home: a bed the player slept in and
 * is allowed to use, or a house they bought.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.nirncraft.NirnCraft.MODID)
public final class RespawnPoints {
    private RespawnPoints() {}

    /** Walking into a town (or to its gate) puts it on the player's list of places to come back to. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isSpectator() || (player.tickCount + player.getId()) % 40 != 5) return;
        try {
            noteVisit(player);
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.debug("NirnCraft: visited-town check failed", e);
        }
    }

    /** A place to wake (the start cottage): never the death respawn, never a home, and sleeping in it makes it none. */
    public static void set(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        write(player, dim, pos, false);
    }

    /** A home: the death respawn until the player sleeps somewhere else they may use. */
    public static void setOwned(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        write(player, dim, pos, true);
    }

    private static void write(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos, boolean owned) {
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module("respawn");
        if (!owned && home(player) != null && !pos.equals(home(player))) {
            // a provisional place to wake never replaces a home the player has: it is only remembered as one
            long[] old = tag.getLongArray("provisional").orElseGet(() -> new long[0]);
            if (java.util.Arrays.stream(old).noneMatch(l -> l == pos.asLong())) { // each place only once
                long[] arr = java.util.Arrays.copyOf(old, old.length + 1);
                arr[old.length] = pos.asLong();
                tag.putLongArray("provisional", arr);
                data.markDirty();
            }
            return;
        }
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(net.minecraft.world.level.storage.LevelData.RespawnData.of(dim, pos, player.getYRot(), player.getXRot()), true), false);
        tag.putString("dim", dim.identifier().toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putBoolean("earned", owned);
        // the beds that are only ever places to wake: sleeping in them later does not make them a home either
        long key = pos.asLong();
        long[] old = tag.getLongArray("provisional").orElseGet(() -> new long[0]);
        java.util.List<Long> list = new java.util.ArrayList<>();
        for (long l : old) if (l != key) list.add(l);
        if (!owned) list.add(key);
        long[] arr = new long[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        tag.putLongArray("provisional", arr);
        data.markDirty();
    }

    /** True if {@code pos} was only ever set as a provisional place to wake (the start cottage's bed). */
    public static boolean isProvisional(ServerPlayer player, BlockPos pos) {
        long key = pos.asLong();
        for (long l : SkyData.get(player).module("respawn").getLongArray("provisional").orElseGet(() -> new long[0])) if (l == key) return true;
        return false;
    }

    /** True when the player has a home (a bed slept in and usable, a bought house), not just a provisional place to wake. */
    public static boolean isEarned(ServerPlayer player) {
        return home(player) != null;
    }

    /** The player's home bed (overworld), or null. Vanilla sets its own respawn on any bed slept in; this is the record. */
    @javax.annotation.Nullable
    public static BlockPos home(ServerPlayer player) {
        CompoundTag tag = SkyData.get(player).module("respawn");
        if (!tag.getBooleanOr("earned",false) || !tag.contains("x")) return null;
        String dim = tag.getStringOr("dim","");
        if (!dim.isEmpty() && !dim.equals(Level.OVERWORLD.identifier().toString())) return null;
        BlockPos pos = new BlockPos(tag.getIntOr("x",0), tag.getIntOr("y",0), tag.getIntOr("z",0));
        return isProvisional(player, pos) ? null : pos;
    }

    // ------------------------------------------------------------------ the towns a player has been to

    /** Place ids of the settlements this player has walked into (their own record, independent of the map). */
    public static java.util.Set<String> visitedTowns(ServerPlayer player) {
        net.minecraft.nbt.ListTag list = SkyData.get(player).module("respawn").getListOrEmpty("towns");
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) out.add(list.getStringOr(i, ""));
        return out;
    }

    /**
     * Records the settlements the player is standing in or at the gate of (its layout reach plus a margin, or within
     * thirty blocks of one of its gates). Cheap: one place-list lookup. Returns true when a new town was added.
     */
    public static boolean noteVisit(ServerPlayer player) {
        ServerLevel level = player.level();
        if (level.dimension() != Level.OVERWORLD) return false;
        int px = player.getBlockX(), pz = player.getBlockZ();
        boolean added = false;
        CompoundTag tag = SkyData.get(player).module("respawn");
        net.minecraft.nbt.ListTag list = tag.getListOrEmpty("towns");
        for (com.nirncraft.tamriel.Place p : com.nirncraft.tamriel.Places.overlapping(px - 160, pz - 160, px + 160, pz + 160)) {
            if (!p.type().isSettlement() || "homestead".equals(p.variant())) continue;
            boolean known = false;
            for (int i = 0; i < list.size() && !known; i++) known = list.getStringOr(i, "").equals(p.id());
            if (known) continue;
            int reach;
            try {
                reach = Math.max(p.radius(), com.nirncraft.tamriel.settlement.plan.Cities.layoutRadius(p));
            } catch (RuntimeException e) {
                reach = p.radius();
            }
            long r = reach + 24L;
            boolean in = p.distSqr(px, pz) <= r * r;
            if (!in) {
                // any gate of the settlement within thirty blocks: standing at the town's entrance
                for (int[] g : com.nirncraft.tamriel.settlement.SettlementAnchors.gates(p)) {
                    long dx = px - g[0], dz = pz - g[1];
                    if (dx * dx + dz * dz <= 30L * 30L) {
                        in = true;
                        break;
                    }
                }
            }
            if (!in) continue;
            list.add(net.minecraft.nbt.StringTag.valueOf(p.id()));
            added = true;
        }
        if (added) {
            tag.put("towns", list);
            SkyData.get(player).markDirty();
        }
        return added;
    }

    public static boolean teleportToRestPoint(ServerPlayer player) {
        CompoundTag tag = SkyData.get(player).module("respawn");
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;

        ServerLevel targetLevel = null;
        BlockPos targetPos = null;

        if (tag.contains("dim") && tag.contains("x")) {
            Identifier dimId = Identifier.parse(tag.getStringOr("dim",""));
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimId);
            targetLevel = server.getLevel(key);
            if (targetLevel != null) {
                targetPos = new BlockPos(tag.getIntOr("x",0), tag.getIntOr("y",0), tag.getIntOr("z",0));
            }
        }

        if (targetLevel == null || targetPos == null) {
            ResourceKey<Level> respawnDim = ServerPlayer.RespawnConfig.getDimensionOrDefault(player.getRespawnConfig());
            targetLevel = server.getLevel(respawnDim);
            if (targetLevel == null) targetLevel = server.overworld();
            targetPos = player.getRespawnConfig() == null ? null : player.getRespawnConfig().respawnData().pos();
            if (targetPos == null) targetPos = targetLevel.getRespawnData().pos();
        }

        player.teleportTo(targetLevel, targetPos.getX() + 0.5, targetPos.getY() + 0.1, targetPos.getZ() + 0.5, java.util.Set.of(), player.getYRot(), player.getXRot(), true);
        Notifier.message(player, Component.translatable("message.nirncraft.unstuck_success"));
        return true;
    }
}
