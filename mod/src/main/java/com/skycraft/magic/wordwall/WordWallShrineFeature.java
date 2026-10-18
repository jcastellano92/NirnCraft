package com.skycraft.magic.wordwall;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import com.skycraft.magic.MagicRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;

/**
 * A small ruined Nordic shrine: a stone platform with steps, a curved wall of weathered stone bricks and, at its
 * heart, a Word Wall. A chest ({@code skycraft:chests/word_wall}) holds gold, spell tomes and a soul gem.
 */
public class WordWallShrineFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public static final Identifier LOOT = Identifier.fromNamespaceAndPath(Skycraft.MODID, "chests/word_wall");

    public WordWallShrineFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        Direction front = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        Direction back = front.getOpposite();
        Direction right = back.getClockWise();

        // Needs fairly flat, dry, solid ground.
        BlockState ground = level.getBlockState(origin.below());
        if (!ground.isFaceSturdy(level, origin.below(), Direction.UP) || ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS) || !level.getFluidState(origin).isEmpty()
                || !level.getFluidState(origin.below()).isEmpty()) {
            return false;
        }
        int y0 = origin.getY();
        int[][] corners = {{-4, -3}, {4, -3}, {-4, 4}, {4, 4}, {0, 4}, {0, -3}};
        for (int[] c : corners) {
            BlockPos p = at(origin, right, back, c[0], 0, c[1]);
            int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, p.getX(), p.getZ());
            if (Math.abs(h - y0) > 3) return false;
            if (!level.getFluidState(new BlockPos(p.getX(), h - 1, p.getZ())).isEmpty()) return false;
        }

        // Platform: foundation, floor and cleared space above.
        for (int lx = -4; lx <= 4; lx++) {
            for (int lz = -3; lz <= 4; lz++) {
                if (Math.abs(lx) == 4 && (lz == -3 || lz == 4)) continue;
                for (int ly = -2; ly >= -7; ly--) {
                    BlockPos p = at(origin, right, back, lx, ly, lz);
                    BlockState s = level.getBlockState(p);
                    if (!s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty()) break;
                    set(level, p, random.nextInt(3) == 0 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
                set(level, at(origin, right, back, lx, -1, lz), weathered(random));
                for (int ly = 0; ly <= 6; ly++) {
                    BlockPos p = at(origin, right, back, lx, ly, lz);
                    if (!level.getBlockState(p).isAir()) set(level, p, Blocks.AIR.defaultBlockState());
                }
                if (lz <= 0 && random.nextInt(9) == 0) set(level, at(origin, right, back, lx, 0, lz), Blocks.MOSS_CARPET.defaultBlockState());
            }
        }

        // Dais with steps.
        for (int lx = -3; lx <= 3; lx++) {
            for (int lz = 1; lz <= 3; lz++) set(level, at(origin, right, back, lx, 0, lz), weathered(random));
        }
        for (int lx = -1; lx <= 1; lx++) {
            set(level, at(origin, right, back, lx, 0, 0), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, back));
        }
        for (int lx : new int[]{-3, -2, 2, 3}) {
            set(level, at(origin, right, back, lx, 0, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        }

        // Curved wall: {lx, lz, height}
        int[][] wall = {{0, 4, 5}, {-1, 4, 5}, {1, 4, 5}, {-2, 4, 4}, {2, 4, 4}, {-3, 3, 4}, {3, 3, 4}, {-4, 2, 3}, {4, 2, 3}, {-4, 1, 2}, {4, 1, 2}};
        for (int[] col : wall) {
            int height = col[2];
            boolean ruined = random.nextInt(4) == 0 && Math.abs(col[0]) >= 2;
            if (ruined) height -= 1 + random.nextInt(2);
            for (int ly = 0; ly < height; ly++) {
                BlockState s = ly == height - 1 && Math.abs(col[0]) <= 1 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : weathered(random);
                set(level, at(origin, right, back, col[0], ly, col[1]), s);
            }
            if (!ruined && Math.abs(col[0]) >= 2) {
                set(level, at(origin, right, back, col[0], height, col[1]), Blocks.STONE_BRICK_SLAB.defaultBlockState());
            }
            if (random.nextInt(5) == 0) set(level, at(origin, right, back, col[0], height, col[1]), Blocks.COBWEB.defaultBlockState());
        }

        // The carved tablet with the Word Wall at its center.
        for (int lx = -1; lx <= 1; lx++) {
            for (int ly = 1; ly <= 3; ly++) {
                if (lx == 0 && ly == 2) continue;
                set(level, at(origin, right, back, lx, ly, 3), ly == 3 ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            }
        }
        set(level, at(origin, right, back, -1, 3, 3), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, right));
        set(level, at(origin, right, back, 1, 3, 3), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, right.getOpposite()));
        BlockPos wallPos = at(origin, right, back, 0, 2, 3);
        set(level, wallPos, MagicRegistry.WORD_WALL.get().defaultBlockState().setValue(WordWallBlock.FACING, front));
        if (level.getBlockEntity(wallPos) instanceof WordWallBlockEntity be) be.setShout(WordWalls.randomShout(random));

        // Treasure chest beside the tablet.
        int side = random.nextBoolean() ? 2 : -2;
        BlockPos chest = at(origin, right, back, side, 1, 2);
        set(level, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, front));
        if (level.getBlockEntity(chest) instanceof RandomizableContainerBlockEntity container) container.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, LOOT), random.nextLong());

        // Cold braziers at the foot of the steps.
        for (int lx : new int[]{-3, 3}) {
            set(level, at(origin, right, back, lx, 0, -2), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        }
        return true;
    }

    private static BlockPos at(BlockPos origin, Direction right, Direction back, int lx, int ly, int lz) {
        return origin.relative(right, lx).relative(back, lz).above(ly);
    }

    private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).is(BlockTags.FEATURES_CANNOT_REPLACE)) return;
        level.setBlock(pos, state, 2);
    }

    private static BlockState weathered(RandomSource random) {
        int r = random.nextInt(20);
        if (r < 10) return Blocks.STONE_BRICKS.defaultBlockState();
        if (r < 15) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        if (r < 19) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        return Blocks.COBBLESTONE.defaultBlockState();
    }
}
