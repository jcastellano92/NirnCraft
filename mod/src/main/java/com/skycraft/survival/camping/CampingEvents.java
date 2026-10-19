package com.skycraft.survival.camping;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Bedrolls and tents are for a night's rest only: they never become your respawn point. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class CampingEvents {
    private CampingEvents() {}

    @SubscribeEvent
    public static void onSetSpawn(PlayerSetSpawnEvent event) {
        BlockPos pos = event.getNewSpawn();
        if (pos == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level().getServer().getLevel(event.getSpawnLevel());
        if (isCampBed(level, pos)) event.setCanceled(true);
        // 26.3 sets the respawn point inside vanilla's bed check, before CanPlayerSleepEvent can refuse a bed someone
        // else owns (Forge 1.20.1's PlayerSleepInBedEvent ran first and stopped it): keep a stranger's bed from
        // becoming your spawn here. Not for forced spawns (/spawnpoint).
        else if (!event.isForced() && level != null && level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && com.skycraft.crime.Ownership.isOwnedByOther(player, level, pos)) event.setCanceled(true);
    }

    /** A bedroll (or anything of a tent) at {@code pos}. */
    public static boolean isCampBed(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return false;
        var block = level.getBlockState(pos).getBlock();
        return block instanceof BedrollBlock || block instanceof TentBlock;
    }
}
