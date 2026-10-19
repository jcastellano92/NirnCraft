package com.nirncraft.sailing.world;

import com.mojang.serialization.Codec;
import com.nirncraft.NirnCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.NoneFeatureConfiguration;

/**
 * Floating debris on the open sea: a raft of loose planks, a drifting spar and a barrel or crate or two with what was
 * left of some ship's stores ({@code nirncraft:chests/flotsam}). Blocks rest on the water surface.
 */
public class FlotsamFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    private static final Identifier LOOT = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/flotsam");

    public FlotsamFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource r = ctx.random();
        BlockPos o = ctx.origin();
        int top = height(level, Heightmap.Types.WORLD_SURFACE_WG, o.getX(), o.getZ());
        BlockPos surface = new BlockPos(o.getX(), top, o.getZ());
        if (!level.getFluidState(surface.below()).is(FluidTags.WATER) || !level.isEmptyBlock(surface)) return false;
        if (top - height(level, Heightmap.Types.OCEAN_FLOOR_WG, o.getX(), o.getZ()) < 3) return false;

        int pieces = 3 + r.nextInt(4);
        boolean barrel = false;
        for (int i = 0; i < pieces; i++) {
            BlockPos p = surface.offset(r.nextInt(7) - 3, 0, r.nextInt(7) - 3);
            if (!level.getFluidState(p.below()).is(FluidTags.WATER) || !level.isEmptyBlock(p)) continue;
            int kind = r.nextInt(5);
            if (!barrel || kind == 0) {
                setBlock(level, p, r.nextInt(3) == 0 ? Blocks.CHEST.defaultBlockState().setValue(
                        net.minecraft.world.level.block.ChestBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r))
                        : Blocks.BARREL.defaultBlockState());
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof RandomizableContainerBlockEntity c) c.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, LOOT), r.nextLong());
                barrel = true;
            } else if (kind <= 2) {
                BlockState slab = (r.nextBoolean() ? Blocks.SPRUCE_SLAB : Blocks.OAK_SLAB).defaultBlockState().setValue(SlabBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
                setBlock(level, p, slab);
                BlockPos q = p.relative(Direction.Plane.HORIZONTAL.getRandomDirection(r));
                if (level.getFluidState(q.below()).is(FluidTags.WATER) && level.isEmptyBlock(q)) setBlock(level, q, slab);
            } else {
                Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(r);
                for (int k = 0; k < 3; k++) {
                    BlockPos q = p.relative(d, k);
                    if (!level.getFluidState(q.below()).is(FluidTags.WATER) || !level.isEmptyBlock(q)) break;
                    setBlock(level, q, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, d.getAxis()));
                }
            }
        }
        return barrel;
    }

    /** A worldgen heightmap, or its live counterpart when placed by command in a finished world. */
    static int height(WorldGenLevel level, Heightmap.Types type, int x, int z) {
        if (level instanceof net.minecraft.server.level.WorldGenRegion) return level.getHeight(type, x, z);
        // placed by command in a live world: the worldgen heightmaps are gone
        return level.getHeight(type == Heightmap.Types.OCEAN_FLOOR_WG ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.WORLD_SURFACE, x, z);
    }
}
