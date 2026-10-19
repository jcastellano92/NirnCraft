package com.nirncraft.curse;

import com.nirncraft.creatures.CreatureSpawns;
import com.nirncraft.curse.entity.WerewolfEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rare werewolves on the hunt at night in the wilds (never in settlements): every minute and a half each player out
 * in the open at night has a small chance of one turning up 30-50 blocks away, at most one near anybody. A full moon
 * doubles the odds. They leave again at dawn ({@link WerewolfEntity}).
 */
public final class WildWerewolves {
    private static final int INTERVAL = 20 * 180;
    private static final long SPAWN_COOLDOWN = 24000L;
    private static final int MIN_PLAYER_LEVEL = 10;
    private static final float CHANCE = 0.025f;
    private static final String LAST_SPAWN_KEY = "last_wild_werewolf";
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private WildWerewolves() {}

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 37) return;
        ServerLevel level = server.overworld();
        if (level.getDifficulty() == Difficulty.PEACEFUL || !level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS)) return;
        if (!level.isDarkOutside()) return;
        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.isCreative()) continue;
            com.nirncraft.core.PlayerData data = com.nirncraft.core.SkyData.get(player);
            if (data.getLevel() < MIN_PLAYER_LEVEL) continue;
            long lastSpawn = Curses.tag(player).getLongOr(LAST_SPAWN_KEY, 0L);
            if (lastSpawn > 0 && now - lastSpawn < SPAWN_COOLDOWN && now >= lastSpawn) continue;
            Long last = LAST.get(player.getUUID());
            if (last != null && now - last < INTERVAL && now >= last) continue;
            LAST.put(player.getUUID(), now);
            BlockPos pPos = player.blockPosition();
            if (com.nirncraft.creatures.spawn.SpawnPacing.nearStart(level, pPos.getX(), pPos.getZ())
                    || com.nirncraft.creatures.spawn.SpawnPacing.calm(level, pPos)
                    || com.nirncraft.creatures.spawn.SpawnPacing.onRoad(level, pPos)
                    || !com.nirncraft.creatures.spawn.SpawnPacing.hostileReady(player)) continue;
            RandomSource r = player.getRandom();
            float chance = CHANCE * (level.getMoonBrightness(pPos) >= 1f ? 2f : 1f);
            if (r.nextFloat() >= chance) continue;
            if (!level.canSeeSky(pPos) || CreatureSpawns.isSettlementOrHouse(level, pPos)) continue;
            if (!level.getEntitiesOfClass(WerewolfEntity.class, player.getBoundingBox().inflate(160)).isEmpty()) continue;
            spawnNear(level, player, r, now);
        }
    }

    private static void spawnNear(ServerLevel level, ServerPlayer player, RandomSource r, long now) {
        for (int attempt = 0; attempt < 6; attempt++) {
            double a = r.nextDouble() * Math.PI * 2;
            int d = 30 + r.nextInt(20);
            int x = (int) (player.getX() + Math.cos(a) * d);
            int z = (int) (player.getZ() + Math.sin(a) * d);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getFluidState(pos.below()).isEmpty() || !level.getBlockState(pos.below()).isSolid()) continue;
            if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos) > 7) continue;
            if (CreatureSpawns.isSettlementOrHouse(level, pos)
                    || com.nirncraft.creatures.spawn.SpawnPacing.calm(level, pos)
                    || com.nirncraft.creatures.spawn.SpawnPacing.onRoad(level, pos)) continue;
            WerewolfEntity wolf = CurseRegistry.WEREWOLF.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (wolf == null) return;
            wolf.snapTo(x + 0.5, y, z + 0.5, r.nextFloat() * 360, 0);
            if (!level.noCollision(wolf)) continue;
            wolf.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
            wolf.setWild(true);
            level.addFreshEntity(wolf);
            Curses.tag(player).putLong(LAST_SPAWN_KEY, now);
            Curses.dirty(player);
            com.nirncraft.creatures.spawn.SpawnPacing.noteHostile(player);
            return;
        }
    }

    static void clear() {
        LAST.clear();
    }

    static boolean overworld(Level level) {
        return level.dimension() == Level.OVERWORLD;
    }
}
