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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.NoneFeatureConfiguration;

/**
 * A ship lost at sea, lying on the sea bed: a broken clinker hull listing to one side, planks stove in, the mast
 * snapped or fallen across the sand, rags of sail, and the cargo it went down with (a chest of
 * {@code nirncraft:chests/sea_wreck} and barrels of {@code nirncraft:chests/flotsam}). Fits within one chunk of its
 * origin; placed in ocean biomes ({@code nirncraft:sea_wreck}).
 */
public class SeaWreckFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    private static final Identifier WRECK_LOOT = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/sea_wreck");
    private static final Identifier FLOTSAM_LOOT = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/flotsam");

    public SeaWreckFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource r = ctx.random();
        BlockPos origin = ctx.origin();
        int surface = height(level, Heightmap.Types.WORLD_SURFACE_WG, origin.getX(), origin.getZ());
        int bed = height(level, Heightmap.Types.OCEAN_FLOOR_WG, origin.getX(), origin.getZ());
        if (surface - bed < 4 || !level.getFluidState(new BlockPos(origin.getX(), bed, origin.getZ())).is(FluidTags.WATER)) return false;

        Direction fwd = Direction.Plane.HORIZONTAL.getRandomDirection(r);
        Direction side = fwd.getClockWise();
        int half = 4 + r.nextInt(3);          // half length 4..6
        int list = r.nextBoolean() ? 1 : -1; // which side lies lower
        boolean dark = r.nextBoolean();
        BlockState plank = dark ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState rot = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        BlockState log = (dark ? Blocks.DARK_OAK_LOG : Blocks.SPRUCE_LOG).defaultBlockState();
        BlockState stairs = (dark ? Blocks.DARK_OAK_STAIRS : Blocks.SPRUCE_STAIRS).defaultBlockState();
        BlockState fence = (dark ? Blocks.DARK_OAK_FENCE : Blocks.SPRUCE_FENCE).defaultBlockState();

        for (int a = -half; a <= half; a++) {
            double t = a / (half + 1.0);
            int w = Math.max(0, (int) Math.round(2.2 * Math.sqrt(Math.max(0, 1 - t * t))));
            // the bed under the hull (sand drifts make it uneven)
            for (int l = -w; l <= w; l++) {
                BlockPos col = at(origin, fwd, side, a, 0, l);
                int y0 = height(level, Heightmap.Types.OCEAN_FLOOR_WG, col.getX(), col.getZ());
                boolean wall = Math.abs(l) == w;
                int sideDrop = l * list > 0 ? 1 : 0;
                int h = wall ? 2 + (a < -half + 3 ? 1 : 0) - sideDrop : 0;
                // keel and floor
                if (r.nextInt(6) > 0) put(level, col.atY(y0), Math.abs(l) <= 1 && a % 3 == 0 ? log.setValue(RotatedPillarBlock.AXIS, fwd.getAxis()) : plank);
                for (int y = 1; y <= h; y++) {
                    if (r.nextInt(10) < 3) continue; // stove-in planks
                    put(level, col.atY(y0 + y), y == h && r.nextInt(3) == 0
                            ? stairs.setValue(StairBlock.FACING, l > 0 ? side : side.getOpposite()) : plank);
                }
                if (wall && r.nextInt(8) == 0) put(level, col.atY(y0 + 1), rot);
            }
            // stern cabin wreckage
            if (a == -half + 1) {
                for (int l = -1; l <= 1; l++) {
                    BlockPos col = at(origin, fwd, side, a, 0, l);
                    int y0 = height(level, Heightmap.Types.OCEAN_FLOOR_WG, col.getX(), col.getZ());
                    put(level, col.atY(y0 + 3), plank);
                    if (l != 0) put(level, col.atY(y0 + 4), fence);
                }
            }
        }
        // the mast: snapped stub, or the whole mast fallen across the sand with its sail
        BlockPos mid = at(origin, fwd, side, 1, 0, 0);
        int my = height(level, Heightmap.Types.OCEAN_FLOOR_WG, mid.getX(), mid.getZ());
        if (r.nextBoolean()) {
            for (int y = 0; y < 2 + r.nextInt(3); y++) put(level, mid.atY(my + y), log);
        } else {
            Direction fall = r.nextBoolean() ? side : side.getOpposite();
            for (int k = 1; k <= 7; k++) {
                BlockPos p = mid.relative(fall, k);
                int y = height(level, Heightmap.Types.OCEAN_FLOOR_WG, p.getX(), p.getZ());
                put(level, p.atY(y), log.setValue(RotatedPillarBlock.AXIS, fall.getAxis()));
                if (k >= 3 && k <= 6 && r.nextBoolean()) {
                    BlockPos rag = p.relative(fwd, r.nextBoolean() ? 1 : -1);
                    int ry = height(level, Heightmap.Types.OCEAN_FLOOR_WG, rag.getX(), rag.getZ());
                    put(level, rag.atY(ry), r.nextInt(3) == 0 ? com.nirncraft.worldgen.VanillaBlocks.GRAY_WOOL.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState());
                }
            }
        }
        // cargo
        BlockPos hold = at(origin, fwd, side, -1, 0, -list);
        int hy = height(level, Heightmap.Types.OCEAN_FLOOR_WG, hold.getX(), hold.getZ());
        BlockPos chest = hold.atY(hy);
        put(level, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, fwd));
        loot(level, chest, WRECK_LOOT, r);
        for (int k = 0; k < 1 + r.nextInt(3); k++) {
            BlockPos b = at(origin, fwd, side, 2 + k, 0, list * (k % 2));
            int by = height(level, Heightmap.Types.OCEAN_FLOOR_WG, b.getX(), b.getZ());
            put(level, b.atY(by), Blocks.BARREL.defaultBlockState());
            loot(level, b.atY(by), FLOTSAM_LOOT, r);
        }
        return true;
    }

    private static BlockPos at(BlockPos o, Direction fwd, Direction side, int a, int y, int l) {
        return o.offset(fwd.getStepX() * a + side.getStepX() * l, y, fwd.getStepZ() * a + side.getStepZ() * l);
    }

    private void put(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(pos).is(FluidTags.WATER));
        }
        setBlock(level, pos, state);
    }

    private static void loot(WorldGenLevel level, BlockPos pos, Identifier table, RandomSource r) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RandomizableContainerBlockEntity c) c.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, table), r.nextLong());
    }

    /** A worldgen heightmap, or its live counterpart when placed by command in a finished world. */
    static int height(WorldGenLevel level, Heightmap.Types type, int x, int z) {
        if (level instanceof net.minecraft.server.level.WorldGenRegion) return level.getHeight(type, x, z);
        // placed by command in a live world: the worldgen heightmaps are gone
        return level.getHeight(type == Heightmap.Types.OCEAN_FLOOR_WG ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.WORLD_SURFACE, x, z);
    }
}
