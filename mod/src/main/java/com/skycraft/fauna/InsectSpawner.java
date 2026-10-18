package com.skycraft.fauna;

import com.skycraft.Skycraft;
import com.skycraft.fauna.entity.InsectEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Ambient insect spawning around each player (instead of biome spawn lists): up to {@link #CAP} insects within
 * {@link #RADIUS} blocks. By day butterflies gather over flowers and dragonflies over water; at night torchbugs drift
 * through meadows and forests and luna moths visit flowers. No insects in the cold, the rain, or underground.
 * Insects remove themselves when no player is within 64 blocks (see {@link InsectEntity#tick()}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class InsectSpawner {
    public static final int CAP = 6;
    public static final int RADIUS = 40;
    private static final int INTERVAL = 40;

    private InsectSpawner() {}

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.LevelTickEvent.Post) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimensionType().hasSkyLight() || level.getGameTime() % INTERVAL != 0) return;
        if (!level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS)) return;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            int count = level.getEntitiesOfClass(InsectEntity.class, player.getBoundingBox().inflate(RADIUS)).size();
            if (count < CAP && level.getRandom().nextInt(3) != 0) trySpawn(level, player, level.getRandom());
        }
    }

    private static void trySpawn(ServerLevel level, ServerPlayer player, RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 10 + random.nextDouble() * 22;
        int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
        int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
        if (!level.hasChunksAt(new BlockPos(x - 4, 0, z - 4), new BlockPos(x + 4, 0, z + 4))) return;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(surface - player.getY()) > 24) return;
        BlockPos ground = new BlockPos(x, surface - 1, z);
        BlockPos air = ground.above();
        if (!level.canSeeSky(air) && level.getBrightness(LightLayer.SKY, air) < 8) return;
        if (level.isRaining() && level.isRainingAt(air)) return;
        Holder<Biome> biome = level.getBiome(air);
        if (biome.value().getBaseTemperature() < 0.2f) return;

        boolean day = level.isBrightOutside();
        boolean water = level.getFluidState(ground).is(FluidTags.WATER) || nearby(level, ground, 3, s -> s.getFluidState().is(FluidTags.WATER));
        boolean flowers = nearby(level, air, 3, s -> s.is(BlockTags.FLOWERS));
        BlockState below = level.getBlockState(ground);
        boolean greenery = below.is(BlockTags.DIRT) || below.is(BlockTags.LEAVES) || flowers;

        EntityType<InsectEntity> type = choose(day, water, flowers, greenery, random);
        if (type == null) return;
        int y = surface + 1 + random.nextInt(2);
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isEmptyBlock(pos)) return;
        InsectEntity insect = type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (insect == null) return;
        insect.snapTo(x + 0.5, y + 0.2, z + 0.5, random.nextFloat() * 360f, 0f);
        insect.setHome(air);
        insect.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null);
        level.addFreshEntity(insect);
    }

    @Nullable
    private static EntityType<InsectEntity> choose(boolean day, boolean water, boolean flowers, boolean greenery, RandomSource random) {
        if (day) {
            if (water && random.nextInt(3) != 0) return FaunaEntities.DRAGONFLY.get();
            if (flowers) return FaunaEntities.BUTTERFLY.get();
            if (greenery && random.nextInt(4) == 0) return FaunaEntities.BUTTERFLY.get();
            return null;
        }
        if (flowers && random.nextInt(3) == 0) return FaunaEntities.MOTH.get();
        if (greenery || water) return random.nextInt(5) == 0 ? FaunaEntities.MOTH.get() : FaunaEntities.TORCHBUG.get();
        return null;
    }

    private static boolean nearby(ServerLevel level, BlockPos center, int r, Predicate<BlockState> test) {
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -1, -r), center.offset(r, 1, r))) {
            if (test.test(level.getBlockState(p))) return true;
        }
        return false;
    }
}
