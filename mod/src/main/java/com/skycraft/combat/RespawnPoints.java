package com.skycraft.combat;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Contract 9 (docs/PLAYTEST-1.md): the player's rest point (last bed, inn or owned house). Respawn goes there,
 * otherwise to the world spawn.
 */
public final class RespawnPoints {
    private RespawnPoints() {}

    public static void set(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        player.setRespawnPosition(dim, pos, player.getYRot(), true, false);
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module("respawn");
        tag.putString("dim", dim.location().toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        data.markDirty();
    }

    public static boolean teleportToRestPoint(ServerPlayer player) {
        CompoundTag tag = SkyData.get(player).module("respawn");
        MinecraftServer server = player.getServer();
        if (server == null) return false;

        ServerLevel targetLevel = null;
        BlockPos targetPos = null;

        if (tag.contains("dim") && tag.contains("x")) {
            ResourceLocation dimId = new ResourceLocation(tag.getString("dim"));
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimId);
            targetLevel = server.getLevel(key);
            if (targetLevel != null) {
                targetPos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            }
        }

        if (targetLevel == null || targetPos == null) {
            ResourceKey<Level> respawnDim = player.getRespawnDimension();
            targetLevel = server.getLevel(respawnDim);
            if (targetLevel == null) targetLevel = server.overworld();
            targetPos = player.getRespawnPosition();
            if (targetPos == null) targetPos = targetLevel.getSharedSpawnPos();
        }

        player.teleportTo(targetLevel, targetPos.getX() + 0.5, targetPos.getY() + 0.1, targetPos.getZ() + 0.5, player.getYRot(), player.getXRot());
        Notifier.message(player, Component.translatable("message.skycraft.unstuck_success"));
        return true;
    }
}
