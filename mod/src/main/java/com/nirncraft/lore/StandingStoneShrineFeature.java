package com.nirncraft.lore;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.NoneFeatureConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * A Standing Stone shrine on the surface: a round weathered plinth with a ring of mossy stones and the carved
 * stone at its heart. One generation in eleven is the Guardian Stones instead: the Warrior, Mage and Thief stones
 * on three small plinths at the corners of a triangle (about eight blocks a side), all facing its center.
 */
public class StandingStoneShrineFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    private static final StandingStone[] LONE = {
            StandingStone.LADY, StandingStone.LORD, StandingStone.LOVER, StandingStone.ATRONACH, StandingStone.APPRENTICE,
            StandingStone.RITUAL, StandingStone.SERPENT, StandingStone.SHADOW, StandingStone.STEED, StandingStone.TOWER};

    public StandingStoneShrineFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        if (!goodGround(level, origin)) return false;
        int choice = random.nextInt(LONE.length + 1);
        if (choice == LONE.length) return placeGuardians(level, random, origin);
        // check the footprint is roughly flat
        if (!flat(level, origin, 4, 2)) return false;
        placeLone(level, random, origin, LONE[choice]);
        return true;
    }

    // ------------------------------------------------------------------ layouts

    private void placeLone(WorldGenLevel level, RandomSource random, BlockPos origin, StandingStone sign) {
        int y0 = origin.getY();
        // round platform of radius 3, foundation underneath, cleared air above
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 4.3) continue;
                BlockPos col = new BlockPos(origin.getX() + dx, y0, origin.getZ() + dz);
                if (d <= 3.3) {
                    foundation(level, random, col.below(2));
                    set(level, col.below(), d <= 1.5 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : weathered(random));
                    clearAbove(level, col, 5);
                } else if (random.nextInt(3) != 0) {
                    // a loose ring of moss and gravel blending into the ground
                    BlockPos ground = col.below();
                    if (level.getBlockState(ground).isSolid()) set(level, ground, random.nextBoolean() ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState());
                }
            }
        }
        // central plinth (one step up) with slab edges
        set(level, origin, Blocks.POLISHED_ANDESITE.defaultBlockState());
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            set(level, origin.relative(dir), Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        }
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        stone(level, origin.above(), sign, facing);

        // standing ring stones at radius 4: short mossy pillars, some broken
        int pillars = 6 + random.nextInt(3);
        double phase = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < pillars; i++) {
            double a = phase + i * Math.PI * 2 / pillars;
            int px = origin.getX() + (int) Math.round(Math.cos(a) * 4.2);
            int pz = origin.getZ() + (int) Math.round(Math.sin(a) * 4.2);
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, px, pz);
            if (Math.abs(surface - y0) > 2) continue;
            BlockPos base = new BlockPos(px, surface, pz);
            foundation(level, random, base.below());
            int h = random.nextInt(4) == 0 ? 1 : 2 + random.nextInt(2);
            for (int y = 0; y < h; y++) {
                set(level, base.above(y), y == 0 || random.nextBoolean() ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
            }
            if (h >= 2 && random.nextInt(3) == 0) set(level, base.above(h), Blocks.MOSSY_STONE_BRICK_SLAB.defaultBlockState());
            if (random.nextInt(4) == 0) set(level, base.above(h), Blocks.MOSS_CARPET.defaultBlockState());
        }
        // offerings: a cold brazier and a few candles on the plinth corners
        Direction side = facing.getClockWise();
        set(level, origin.relative(facing, 2).relative(side, 2), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        if (random.nextBoolean()) {
            candle(level, origin.relative(facing, 2).relative(side, -2));
        }
        scatterMoss(level, random, origin, 3, 6);
    }

    /** A candle only where something sturdy is under it (never floating in a stone circle). */
    private static void candle(WorldGenLevel level, BlockPos pos) {
        BlockPos under = pos.below();
        if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) return;
        set(level, pos, Blocks.CANDLE.defaultBlockState());
    }

    private boolean placeGuardians(WorldGenLevel level, RandomSource random, BlockPos origin) {
        // Equilateral-ish triangle, side ~8: vertices at radius 5 around the center.
        double phase = random.nextDouble() * Math.PI * 2;
        StandingStone[] trio = {StandingStone.WARRIOR, StandingStone.MAGE, StandingStone.THIEF};
        List<BlockPos> spots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double a = phase + i * Math.PI * 2 / 3;
            int x = origin.getX() + (int) Math.round(Math.cos(a) * 5);
            int z = origin.getZ() + (int) Math.round(Math.sin(a) * 5);
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            if (Math.abs(surface - origin.getY()) > 3) return false;
            BlockPos spot = new BlockPos(x, surface, z);
            if (!goodGround(level, spot)) return false;
            spots.add(spot);
        }
        // a worn path between the stones and around the center
        for (int i = 0; i < 3; i++) {
            BlockPos a = spots.get(i);
            BlockPos b = spots.get((i + 1) % 3);
            int steps = 12;
            for (int s = 0; s <= steps; s++) {
                int x = a.getX() + (b.getX() - a.getX()) * s / steps;
                int z = a.getZ() + (b.getZ() - a.getZ()) * s / steps;
                path(level, random, x, z, origin.getY());
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) path(level, random, origin.getX() + dx, origin.getZ() + dz, origin.getY());
        }
        // a low cairn in the middle
        int cy = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX(), origin.getZ());
        if (Math.abs(cy - origin.getY()) <= 3) {
            BlockPos c = new BlockPos(origin.getX(), cy, origin.getZ());
            set(level, c, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
            set(level, c.above(), Blocks.COBBLESTONE_WALL.defaultBlockState());
            if (random.nextBoolean()) candle(level, c.above(2));
        }
        // the three stones on their plinths, facing the center
        for (int i = 0; i < 3; i++) {
            BlockPos spot = spots.get(i);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = spot.offset(dx, 0, dz);
                    foundation(level, random, p.below());
                    set(level, p, dx == 0 && dz == 0 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                            : Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    clearAbove(level, p.above(), 4);
                }
            }
            int fx = origin.getX() - spot.getX();
            int fz = origin.getZ() - spot.getZ();
            Direction facing = Math.abs(fx) > Math.abs(fz) ? (fx > 0 ? Direction.EAST : Direction.WEST) : (fz > 0 ? Direction.SOUTH : Direction.NORTH);
            stone(level, spot.above(), trio[i], facing);
        }
        scatterMoss(level, random, origin, 6, 10);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static void stone(WorldGenLevel level, BlockPos pos, StandingStone sign, Direction facing) {
        Block block = LoreModule.STONES.get(sign).get();
        BlockState lower = block.defaultBlockState().setValue(StandingStoneBlock.FACING, facing).setValue(StandingStoneBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, 2);
        level.setBlock(pos.above(), lower.setValue(StandingStoneBlock.HALF, DoubleBlockHalf.UPPER), 2);
    }

    private static boolean goodGround(WorldGenLevel level, BlockPos pos) {
        BlockState ground = level.getBlockState(pos.below());
        return ground.isSolid() && !ground.is(BlockTags.LEAVES) && !ground.is(BlockTags.LOGS) && !ground.is(BlockTags.ICE)
                && level.getFluidState(pos).isEmpty() && level.getFluidState(pos.below()).isEmpty();
    }

    private static boolean flat(WorldGenLevel level, BlockPos origin, int radius, int tolerance) {
        int[][] probes = {{-radius, 0}, {radius, 0}, {0, -radius}, {0, radius}, {-radius + 1, -radius + 1}, {radius - 1, radius - 1}};
        for (int[] p : probes) {
            int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX() + p[0], origin.getZ() + p[1]);
            if (Math.abs(h - origin.getY()) > tolerance) return false;
            if (!level.getFluidState(new BlockPos(origin.getX() + p[0], h - 1, origin.getZ() + p[1])).isEmpty()) return false;
        }
        return true;
    }

    private static void path(WorldGenLevel level, RandomSource random, int x, int z, int nearY) {
        int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        if (Math.abs(h - nearY) > 3 || random.nextInt(4) == 0) return;
        BlockPos ground = new BlockPos(x, h - 1, z);
        BlockState s = level.getBlockState(ground);
        if (!s.isSolid() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return;
        int r = random.nextInt(5);
        set(level, ground, r < 2 ? Blocks.GRAVEL.defaultBlockState() : r < 4 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        BlockState above = level.getBlockState(ground.above());
        if (!above.isAir() && above.canBeReplaced()) set(level, ground.above(), Blocks.AIR.defaultBlockState());
    }

    private static void foundation(WorldGenLevel level, RandomSource random, BlockPos top) {
        for (int i = 0; i < 6; i++) {
            BlockPos p = top.below(i);
            BlockState s = level.getBlockState(p);
            if (i > 0 && s.isSolid() && s.getFluidState().isEmpty()) break;
            set(level, p, random.nextInt(3) == 0 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
    }

    private static void clearAbove(WorldGenLevel level, BlockPos from, int height) {
        for (int y = 0; y < height; y++) {
            BlockPos p = from.above(y);
            if (!level.getBlockState(p).isAir()) set(level, p, Blocks.AIR.defaultBlockState());
        }
    }

    private static void scatterMoss(WorldGenLevel level, RandomSource random, BlockPos origin, int radius, int count) {
        for (int i = 0; i < count; i++) {
            int x = origin.getX() + random.nextInt(radius * 2 + 1) - radius;
            int z = origin.getZ() + random.nextInt(radius * 2 + 1) - radius;
            int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            BlockPos p = new BlockPos(x, h, z);
            if (Math.abs(h - origin.getY()) > 3 || !level.getBlockState(p).isAir() || !level.getBlockState(p.below()).isSolid()) continue;
            if (level.getBlockState(p.below()).getBlock() instanceof StandingStoneBlock) continue;
            set(level, p, random.nextInt(3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.MOSS_CARPET.defaultBlockState());
        }
    }

    private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).is(BlockTags.FEATURES_CANNOT_REPLACE)) return;
        level.setBlock(pos, state, 2);
    }

    private static BlockState weathered(RandomSource random) {
        int r = random.nextInt(20);
        if (r < 9) return Blocks.STONE_BRICKS.defaultBlockState();
        if (r < 15) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        if (r < 18) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    }
}
