package com.nirncraft.fauna;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

/**
 * Spawn placement rules of the wildlife (the biome lists live in {@code data/nirncraft/forge/biome_modifier/fauna_*.json}).
 * Predators and giants' mammoths are thinned out by "few nearby" checks so the wilds don't fill up with them.
 */
public final class FaunaSpawns {
    private FaunaSpawns() {}

    static void register(RegisterSpawnPlacementsEvent event) {
        RegisterSpawnPlacementsEvent.Operation op = RegisterSpawnPlacementsEvent.Operation.REPLACE;
        net.minecraft.world.entity.SpawnPlacementType ground = net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND;
        Heightmap.Types surface = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        event.register(FaunaEntities.DEER.get(), ground, surface, FaunaSpawns::grazer, op);
        event.register(FaunaEntities.ELK.get(), ground, surface, FaunaSpawns::grazer, op);
        event.register(FaunaEntities.MAMMOTH.get(), ground, surface,
                (type, level, reason, pos, random) -> grazer(type, level, reason, pos, random) && fewNearby(type, level, pos, 96, 6), op);
        event.register(FaunaEntities.SABRE_CAT.get(), ground, surface,
                (type, level, reason, pos, random) -> wild(level, pos) && (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION
                        || level.canSeeSky(pos)) && fewNearby(type, level, pos, 64, 1), op);
        event.register(FaunaEntities.BEAR.get(), ground, surface,
                (type, level, reason, pos, random) -> wild(level, pos) && fewNearby(type, level, pos, 64, 1), op);
        event.register(FaunaEntities.HORKER.get(), ground, surface,
                (type, level, reason, pos, random) -> shore(level, pos) && fewNearby(type, level, pos, 32, 6), op);
        event.register(FaunaEntities.MUDCRAB.get(), ground, surface,
                (type, level, reason, pos, random) -> shore(level, pos) && nearWater(level, pos, 3) && fewNearby(type, level, pos, 24, 4), op);
        event.register(FaunaEntities.SLAUGHTERFISH.get(), net.minecraft.world.entity.SpawnPlacementTypes.IN_WATER, Heightmap.Types.OCEAN_FLOOR,
                (type, level, reason, pos, random) -> WaterAnimal.checkSurfaceWaterAnimalSpawnRules(type, level, reason, pos, random)
                        && fewNearby(type, level, pos, 16, 4), op);
    }

    /** Grass, dirt, podzol, snow, stone or gravel underfoot. */
    static boolean wild(ServerLevelAccessor level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(BlockTags.ANIMALS_SPAWNABLE_ON) || below.is(BlockTags.GOATS_SPAWNABLE_ON) || below.is(BlockTags.SUBSTRATE_OVERWORLD);
    }

    /** Wild ground plus sand, gravel and stone of beaches and river banks. */
    static boolean shore(ServerLevelAccessor level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return wild(level, pos) || below.is(BlockTags.SAND) || below.is(Blocks.GRAVEL) || below.is(Blocks.CLAY) || below.is(Blocks.MUD);
    }

    /** Grazers want open, daylit ground. */
    static <T extends Entity> boolean grazer(EntityType<T> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource random) {
        return wild(level, pos) && level.getRawBrightness(pos, 0) > 8;
    }

    static boolean nearWater(ServerLevelAccessor level, BlockPos pos, int radius) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-radius, -1, -radius), pos.offset(radius, 0, radius))) {
            // during world generation only the region's chunks are readable
            if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (level.getFluidState(p).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** True when fewer than {@code max} mobs of this type are within {@code radius} blocks. */
    static boolean fewNearby(EntityType<?> type, ServerLevelAccessor level, BlockPos pos, double radius, int max) {
        return level.getEntitiesOfClass(Mob.class, new AABB(pos).inflate(radius), e -> e.getType() == type).size() < max;
    }
}
