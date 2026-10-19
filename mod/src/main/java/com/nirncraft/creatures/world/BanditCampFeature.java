package com.nirncraft.creatures.world;

import com.mojang.serialization.Codec;
import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.ModEntities;
import com.nirncraft.creatures.entity.BanditEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.NoneFeatureConfiguration;

/**
 * A small bandit camp: two hide tents, a campfire with log seats, a loot chest, supply barrels, a lantern post and
 * a black banner, guarded by 3-5 persistent bandits and sometimes their chief.
 */
public class BanditCampFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> CHEST_LOOT = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/bandit_camp"));
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> SUPPLY_LOOT = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/bandit_camp_supplies"));
    private static final int RADIUS = 6;

    public BanditCampFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        BlockPos origin = context.origin();
        int baseY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        BlockPos center = new BlockPos(origin.getX(), baseY, origin.getZ());
        if (!suitable(level, center)) return false;
        // no camps in the start area (they are placed encounters, not something to stumble into at the spawn)
        if (com.nirncraft.creatures.spawn.SpawnPacing.nearStart(level.getLevel(), center.getX(), center.getZ())) return false;

        flatten(level, center, random);
        BlockState wool = random.nextBoolean() ? com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState();
        BlockState wool2 = random.nextBoolean() ? com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.LIGHT_GRAY_WOOL.defaultBlockState();
        tent(level, center.offset(-4, 0, -3), wool, random);
        tent(level, center.offset(4, 0, -3), wool2, random);

        // campfire and seats
        set(level, center, Blocks.CAMPFIRE.defaultBlockState());
        BlockState logX = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState logZ = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        set(level, center.offset(-1, 0, 2), logX);
        set(level, center.offset(0, 0, 2), logX);
        set(level, center.offset(2, 0, 0), logZ);
        set(level, center.offset(2, 0, 1), logZ);

        // loot
        BlockPos chest = center.offset(0, 0, -4);
        set(level, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        com.nirncraft.worldgen.WorldgenData.setLootTable(level, random, chest, CHEST_LOOT.identifier());
        BlockPos barrel1 = center.offset(-3, 0, 4);
        BlockPos barrel2 = center.offset(-2, 0, 4);
        set(level, barrel1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        set(level, barrel2, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        com.nirncraft.worldgen.WorldgenData.setLootTable(level, random, barrel1, SUPPLY_LOOT.identifier());
        com.nirncraft.worldgen.WorldgenData.setLootTable(level, random, barrel2, SUPPLY_LOOT.identifier());
        if (random.nextBoolean()) set(level, center.offset(-2, 1, 4), Blocks.HAY_BLOCK.defaultBlockState());

        // lantern post and banner
        set(level, center.offset(-5, 0, 2), Blocks.SPRUCE_FENCE.defaultBlockState());
        set(level, center.offset(-5, 1, 2), Blocks.LANTERN.defaultBlockState());
        set(level, center.offset(4, 0, 3), com.nirncraft.worldgen.VanillaBlocks.BLACK_BANNER.defaultBlockState().setValue(BannerBlock.ROTATION, random.nextInt(16)));
        if (random.nextBoolean()) set(level, center.offset(5, 0, 1), Blocks.CRAFTING_TABLE.defaultBlockState());
        set(level, center.offset(3, 0, 4), Blocks.GRINDSTONE.defaultBlockState());

        // the bandits
        int count = 3 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            spawn(level, ModEntities.BANDIT.get(), center.offset(random.nextInt(7) - 3, 0, 1 + random.nextInt(3)), random);
        }
        if (random.nextFloat() < 0.4f) spawn(level, ModEntities.BANDIT_CHIEF.get(), center.offset(0, 0, -2), random);
        return true;
    }

    private static boolean suitable(WorldGenLevel level, BlockPos center) {
        if (!level.getFluidState(center).isEmpty() || !level.getFluidState(center.below()).isEmpty()) return false;
        if (!level.getBlockState(center.below()).isFaceSturdy(level, center.below(), Direction.UP)) return false;
        // never on a planned road, a street, a settlement plot or another structure (the plan-first placement mask)
        if (!com.nirncraft.world.PlacementMask.clear(level, center.getX(), center.getZ(), RADIUS + 2)) return false;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx += 3) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += 3) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX() + dx, center.getZ() + dz);
                BlockPos p = new BlockPos(center.getX() + dx, y - 1, center.getZ() + dz);
                if (!level.getFluidState(p).isEmpty()) return false;
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        return max - min <= 3;
    }

    /** Flattens the camp ground: a dirt floor, cleared space above. */
    private static void flatten(WorldGenLevel level, BlockPos center, RandomSource random) {
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS + 2) continue;
                BlockPos floor = center.offset(dx, -1, dz);
                for (int down = 1; down <= 4; down++) {
                    BlockPos fill = floor.below(down);
                    if (level.getBlockState(fill).isAir() || !level.getFluidState(fill).isEmpty()) set(level, fill, Blocks.DIRT.defaultBlockState());
                    else break;
                }
                BlockState ground = random.nextInt(3) == 0 ? Blocks.COARSE_DIRT.defaultBlockState()
                        : random.nextInt(4) == 0 ? Blocks.PODZOL.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                if (dx * dx + dz * dz <= 5) ground = Blocks.COARSE_DIRT.defaultBlockState();
                set(level, floor, ground);
                for (int up = 0; up <= 7; up++) {
                    BlockPos above = center.offset(dx, up, dz);
                    if (!level.getBlockState(above).isAir()) set(level, above, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** An A-frame hide tent along the Z axis, open to the south, with a bedroll inside. */
    private static void tent(WorldGenLevel level, BlockPos pos, BlockState wool, RandomSource random) {
        for (int dz = -1; dz <= 1; dz++) {
            set(level, pos.offset(-2, 0, dz), wool);
            set(level, pos.offset(2, 0, dz), wool);
            set(level, pos.offset(-1, 1, dz), wool);
            set(level, pos.offset(1, 1, dz), wool);
            set(level, pos.offset(0, 2, dz), wool);
        }
        // closed back
        set(level, pos.offset(-1, 0, -2), wool);
        set(level, pos.offset(0, 0, -2), wool);
        set(level, pos.offset(1, 0, -2), wool);
        set(level, pos.offset(0, 1, -2), wool);
        // poles at the entrance
        set(level, pos.offset(-2, 0, 2), Blocks.SPRUCE_FENCE.defaultBlockState());
        set(level, pos.offset(2, 0, 2), Blocks.SPRUCE_FENCE.defaultBlockState());
        // bedroll
        Block carpet = random.nextBoolean() ? com.nirncraft.worldgen.VanillaBlocks.RED_CARPET : com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET;
        set(level, pos.offset(0, 0, -1), carpet.defaultBlockState());
        set(level, pos.offset(0, 0, 0), carpet.defaultBlockState());
    }

    private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 2);
    }

    private static void spawn(WorldGenLevel level, EntityType<? extends BanditEntity> type, BlockPos pos, RandomSource random) {
        BanditEntity bandit = type.create(level.getLevel(), net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (bandit == null) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        bandit.snapTo(pos.getX() + 0.5, y, pos.getZ() + 0.5, random.nextFloat() * 360f, 0);
        bandit.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.STRUCTURE, null);
        bandit.setPersistenceRequired();
        level.addFreshEntity(bandit);
    }
}
