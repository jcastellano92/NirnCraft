package com.skycraft.survival.camping;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Bedrolls and tents are for a night's rest only: they never become your respawn point. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CampingEvents {
    private CampingEvents() {}

    @SubscribeEvent
    public static void onSetSpawn(PlayerSetSpawnEvent event) {
        BlockPos pos = event.getNewSpawn();
        if (pos == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.server.getLevel(event.getSpawnLevel());
        if (isCampBed(level, pos)) event.setCanceled(true);
    }

    /** A bedroll (or anything of a tent) at {@code pos}. */
    public static boolean isCampBed(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return false;
        var block = level.getBlockState(pos).getBlock();
        return block instanceof BedrollBlock || block instanceof TentBlock;
    }
}
