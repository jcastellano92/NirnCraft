package com.skycraft.fauna;

import com.skycraft.Skycraft;
import com.skycraft.dig.PlacedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Hives built by the world (apiaries beside settlements and on trails, hives in the outlying work grounds) are placed as
 * plain blocks, so they stood empty: no bees came out to work the flowers. Near each player this moves a few honey bees
 * into every such hive once; from then on vanilla takes over (they leave by day, pollinate flowers, come home and fill the
 * hive with honey that can be harvested with a bottle or shears). Hives players place themselves are left alone.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class HiveSeeder {
    private static final String SEEDED = "skycraft_bees_seeded";
    private static final int INTERVAL = 100;
    private static final int CHUNK_RADIUS = 3;

    private HiveSeeder() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % INTERVAL != 0) return;
        for (ServerPlayer player : level.players()) {
            ChunkPos centre = player.chunkPosition();
            for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
                for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz);
                    if (chunk != null) seed(level, chunk);
                }
            }
        }
    }

    private static void seed(ServerLevel level, LevelChunk chunk) {
        List<BeehiveBlockEntity> hives = null;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof BeehiveBlockEntity hive && !hive.getPersistentData().getBoolean(SEEDED)) {
                if (hives == null) hives = new ArrayList<>();
                hives.add(hive);
            }
        }
        if (hives == null) return;
        for (BeehiveBlockEntity hive : hives) {
            hive.getPersistentData().putBoolean(SEEDED, true);
            hive.setChanged();
            BlockPos pos = hive.getBlockPos();
            if (PlacedBlocks.isPlayerPlaced(level, pos) || !hive.isEmpty()) continue;
            int bees = 2 + level.random.nextInt(2);
            for (int i = 0; i < bees && !hive.isFull(); i++) {
                Bee bee = EntityType.BEE.create(level);
                if (bee == null) break;
                bee.moveTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                hive.addOccupant(bee, false);
            }
        }
    }
}

