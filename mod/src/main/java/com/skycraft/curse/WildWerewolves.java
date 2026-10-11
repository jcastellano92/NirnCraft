package com.skycraft.curse;

import com.skycraft.creatures.CreatureSpawns;
import com.skycraft.curse.entity.WerewolfEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
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
    private static final int INTERVAL = 20 * 90;
    private static final float CHANCE = 0.06f;
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private WildWerewolves() {}

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 37) return;
        ServerLevel level = server.overworld();
        if (level.getDifficulty() == Difficulty.PEACEFUL || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) return;
        if (!level.isNight()) return;
        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.isCreative()) continue;
            Long last = LAST.get(player.getUUID());
            if (last != null && now - last < INTERVAL && now >= last) continue;
            LAST.put(player.getUUID(), now);
            RandomSource r = player.getRandom();
            float chance = CHANCE * (level.getMoonBrightness() >= 1f ? 2f : 1f);
            if (r.nextFloat() >= chance) continue;
            if (!level.canSeeSky(player.blockPosition()) || CreatureSpawns.isSettlementOrHouse(level, player.blockPosition())) continue;
            if (!level.getEntitiesOfClass(WerewolfEntity.class, player.getBoundingBox().inflate(128)).isEmpty()) continue;
            spawnNear(level, player, r);
        }
    }

    private static void spawnNear(ServerLevel level, ServerPlayer player, RandomSource r) {
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
            if (CreatureSpawns.isSettlementOrHouse(level, pos)) continue;
            WerewolfEntity wolf = CurseRegistry.WEREWOLF.get().create(level);
            if (wolf == null) return;
            wolf.moveTo(x + 0.5, y, z + 0.5, r.nextFloat() * 360, 0);
            if (!level.noCollision(wolf)) continue;
            wolf.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            wolf.setWild(true);
            level.addFreshEntity(wolf);
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
