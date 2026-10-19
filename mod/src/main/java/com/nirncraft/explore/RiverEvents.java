package com.nirncraft.explore;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.Skill;
import com.nirncraft.core.SkyData;
import com.nirncraft.skills.Progression;
import com.nirncraft.tamriel.terrain.Hydrology;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * "Found: Waterfall Cave" for the river sites ({@code hydrology.json}): the first time a player comes within a few
 * blocks of one. Stored per player in the explore module's {@code river_found} long array (the site's chunk key), so
 * it never touches the minor-site bookkeeping.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class RiverEvents {
    private static final int INTERVAL = 50;

    private RiverEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isSpectator() || player.level().dimension() != Level.OVERWORLD) return;
        if ((player.tickCount + (player.getId() & 31) + 29) % INTERVAL != 0) return;
        try {
            int px = player.getBlockX(), pz = player.getBlockZ();
            for (Hydrology.Site s : Hydrology.get().sites()) {
                long dx = s.x() - px, dz = s.z() - pz;
                int reach = s.kind().equals("fall_secret") ? 8 : s.kind().equals("sunken_cache") ? 6 : 12;
                if (dx * dx + dz * dz > (long) reach * reach) continue;
                found(player, s);
            }
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.debug("NirnCraft river site check failed", e);
        }
    }

    private static void found(ServerPlayer player, Hydrology.Site s) {
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module("explore");
        long key = ChunkPos.pack(s.chunkX(), s.chunkZ());
        long[] have = mod.getLongArray("river_found").orElseGet(() -> new long[0]);
        for (long k : have) if (k == key) return;
        long[] grown = java.util.Arrays.copyOf(have, have.length + 1);
        grown[have.length] = key;
        mod.putLongArray("river_found", grown);
        data.addStat("river_sites_found", 1);
        data.markDirty();
        Notifier.message(player, Component.translatable("explore.nirncraft.found",
                Component.translatable("explore.nirncraft.river." + s.kind())));
        double xp = s.kind().equals("fall_secret") ? 6.0 : 2.0;
        Progression.addSkillXp(player, Skill.ATHLETICS, (float) xp);
    }
}
