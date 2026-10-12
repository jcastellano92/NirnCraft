package com.skycraft.scatter;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.skills.Progression;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Arrays;

/**
 * "Found: Great arch" the first time a player comes upon a hidden stash or a formation (a little Athletics
 * experience, like the minor sites of the explore module). Only players in the overworld are checked, every two
 * seconds.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ScatterEvents {
    private static final int INTERVAL = 40;

    private ScatterEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.isSpectator() || player.level().dimension() != Level.OVERWORLD) return;
        if ((player.tickCount + (player.getId() & 31) + 11) % INTERVAL != 0) return;
        if (!ScatterConfig.discoveries()) return;
        try {
            ServerLevel level = player.serverLevel();
            int x = player.getBlockX(), z = player.getBlockZ();
            for (Discoveries.Find f : Discoveries.within(level.getSeed(), x, z, 48)) {
                int reach = f.type() == Discoveries.Type.HIDDEN_STASH ? 7 : f.type().radius + 6;
                if (f.distSqr(x, z) > (long) reach * reach) continue;
                found(player, level, f);
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft scatter check failed for {}", player.getName().getString(), e);
        }
    }

    private static void found(ServerPlayer player, ServerLevel level, Discoveries.Find f) {
        // only once the chunk exists (a find far from loaded terrain is not "found" yet)
        if (level.getChunkSource().getChunkNow(f.chunkX(), f.chunkZ()) == null) return;
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module("scatter");
        long[] found = mod.getLongArray("found");
        long key = f.key() ^ ((long) f.type().ordinal() << 56);
        for (long k : found) if (k == key) return;
        long[] grown = Arrays.copyOf(found, found.length + 1);
        grown[found.length] = key;
        mod.putLongArray("found", grown);
        data.addStat("scatter_found", 1);
        data.markDirty();
        if (ScatterConfig.messages()) {
            Notifier.message(player, Component.translatable("scatter.skycraft.found",
                    Component.translatable("scatter.skycraft.find." + f.type().id())));
        }
        double xp = ScatterConfig.xp();
        if (xp > 0) Progression.addSkillXp(player, Skill.ATHLETICS, (float) xp);
    }
}
