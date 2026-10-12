package com.skycraft.scatter;

import com.skycraft.explore.ExploreLoot;
import com.skycraft.scatter.ScatterRules.Kind;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The larger one-off shapes of {@link Discoveries}: a rock arch, a grove of spires, a glacial erratic, a bloom of
 * crystals, the bones of a dragon, a hollow giant tree, and the hidden stash under a cairn. All are built in the
 * 3x3 chunks around the chunk that holds their centre (extent at most 13 blocks).
 */
final class Formations {
    private Formations() {}

    private static BlockState log(Block log, Direction.Axis a) {
        return log.defaultBlockState().setValue(RotatedPillarBlock.AXIS, a);
    }

    /** One needle of rock at (cx, cz). */
    static boolean spireAt(Brush br, int cx, int cz, int h) {
        int ox = 0, oz = 0;
        boolean any = false;
        for (int y = 0; y < h; y++) {
            double rad = Math.max(0.0, 2.1 * (1.0 - (double) y / h) + (br.r.nextDouble() - 0.5) * 0.5);
            if (y % 3 == 2) {
                ox = Math.max(-1, Math.min(1, ox + br.r.nextInt(3) - 1));
                oz = Math.max(-1, Math.min(1, oz + br.r.nextInt(3) - 1));
            }
            int ri = (int) Math.ceil(rad);
            for (int dx = -ri; dx <= ri; dx++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    if (dx * dx + dz * dz > rad * rad + 0.4 && !(y == h - 1 && dx == 0 && dz == 0)) continue;
                    Block blk = br.rock.pick(br.r);
                    any |= br.set(cx + ox + dx, y, cz + oz + dz, blk);
                    if (y == 0) br.support(cx + ox + dx, 0, cz + oz + dz, blk.defaultBlockState());
                }
            }
        }
        return any;
    }

    static boolean spireGrove(Brush br, Kind k) {
        int n = br.range(3, 5);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n + br.r.nextDouble() * 0.8;
            int d = i == 0 ? 0 : br.range(4, 8);
            int cx = (int) Math.round(Math.cos(a) * d), cz = (int) Math.round(Math.sin(a) * d);
            any |= spireAt(br, cx, cz, br.range(7, 16));
        }
        for (int i = 0; i < 8; i++) br.top(br.r.nextInt(17) - 8, br.r.nextInt(17) - 8, 0, br.rock.pick(br.r));
        return any;
    }

    static boolean erratic(Brush br, Kind k) {
        boolean any = Builders.blob(br, 0, 0, 5, 4, 4, true);
        Builders.blob(br, 6, 2, 2, 2, 2, true);
        Builders.blob(br, -5, -3, 2, 1, 2, false);
        br.b.lying(0, br.gy(0, -5), -5, new ItemStack(Items.BONE));
        for (int i = 0; i < 4; i++) br.top(br.r.nextInt(13) - 6, br.r.nextInt(13) - 6, 0, br.rock.slab());
        return any;
    }

    static boolean crystalBloom(Brush br, Kind k) {
        Terrain.Ground g = Terrain.ground(br.b.level.getBlockState(br.b.at(0, -1, 0)));
        Block base = switch (g) {
            case ASH -> Blocks.BLACKSTONE;
            case SAND -> Blocks.SMOOTH_SANDSTONE;
            default -> Blocks.CALCITE;
        };
        boolean any = false;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 26 || (d2 > 12 && br.r.nextInt(3) == 0) || br.b.waterAt(dx, dz)) continue;
                any |= br.top(dx, dz, 0, br.r.nextInt(4) == 0 ? Blocks.SMOOTH_BASALT : base);
            }
        }
        int n = br.range(16, 24);
        for (int i = 0; i < n; i++) {
            int dx = br.r.nextInt(9) - 4, dz = br.r.nextInt(9) - 4;
            if (dx * dx + dz * dz > 20) continue;
            int h = br.range(1, 3);
            for (int y = 1; y <= h; y++) br.top(dx, dz, y, Blocks.AMETHYST_BLOCK);
            br.top(dx, dz, h + 1, Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (br.r.nextInt(3) == 0) {
                    br.top(dx + d.getStepX(), dz + d.getStepZ(), h,
                            Blocks.MEDIUM_AMETHYST_BUD.defaultBlockState().setValue(BlockStateProperties.FACING, d));
                }
            }
        }
        return any;
    }

    static boolean dragonBones(Brush br, Kind k) {
        boolean any = false;
        // spine, ribs, tail
        for (int i = -6; i <= 7; i++) {
            any |= br.top(i, 0, 0, Blocks.BONE_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            if (i >= 4) {
                br.top(i, br.r.nextInt(3) - 1, 0, Blocks.BONE_BLOCK);
            }
        }
        for (int rib = -4; rib <= 2; rib += 2) {
            int radius = 3 - Math.abs(rib + 1) / 3;
            for (int t = 0; t <= 8; t++) {
                double a = t * Math.PI / 8;
                int z = (int) Math.round(Math.cos(a) * radius), y = 1 + (int) Math.round(Math.sin(a) * (radius + 0.5));
                br.top(rib, z, y - 1, Blocks.BONE_BLOCK);
            }
        }
        // the skull at the head end (local -x), jaw open, horns up
        for (int dx = -9; dx <= -7; dx++) {
            for (int dz = -1; dz <= 1; dz++) br.top(dx, dz, 0, Blocks.BONE_BLOCK);
        }
        for (int dx = -9; dx <= -8; dx++) br.top(dx, 0, 1, Blocks.BONE_BLOCK);
        br.top(-8, 1, 1, Blocks.BONE_BLOCK);
        br.top(-8, -1, 1, Blocks.BONE_BLOCK);
        br.top(-9, 1, 2, Blocks.BONE_BLOCK);
        br.top(-9, -1, 2, Blocks.BONE_BLOCK);
        for (int dz : new int[]{-1, 1}) {
            int y = br.gy(-7, dz) + 1;
            if (br.b.free(-7, y, dz)) br.b.skull(-7, y, dz);
        }
        br.b.lying(0, br.gy(0, 1), 1, new ItemStack(Items.BONE, 2));
        // what the dragon took with it
        if (br.r.nextInt(3) == 0) {
            int y = br.gy(0, -1);
            if (br.b.free(0, y, -1)) br.b.chest(0, y, -1, Direction.SOUTH, ExploreLoot.table("cache"));
        }
        return any;
    }

    static boolean giantHollow(Brush br, Kind k) {
        Block lg = br.b.pal.log();
        boolean any = false;
        int gap = br.r.nextInt(8);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            int dx = (int) Math.round(Math.cos(a) * 3), dz = (int) Math.round(Math.sin(a) * 3);
            if (i / 2 == gap) continue;
            int h = br.range(3, 5);
            for (int y = 0; y < h; y++) {
                any |= br.top(dx, dz, y, log(lg, Direction.Axis.Y));
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 5) continue;
                br.force(dx, br.gy(dx, dz) - 1, dz, br.r.nextInt(3) == 0 ? Blocks.PODZOL.defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState());
                if (br.r.nextInt(3) == 0) br.top(dx, dz, 0, br.r.nextBoolean() ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM);
            }
        }
        int y = br.gy(0, 0);
        br.b.barrel(0, y, 0, ExploreLoot.table("herbalist"));
        br.written++;
        return any;
    }

    /** A cairn over a buried chest, ringed by stones that look like any other rock field. */
    static boolean stash(Brush br, Kind k) {
        String[] tables = {"cache", "adventurer", "hunter", "miner", "herbalist"};
        String table = tables[br.r.nextInt(tables.length)];
        br.force(0, -1, 0, Blocks.COARSE_DIRT.defaultBlockState());
        br.b.chest(0, -2, 0, Direction.SOUTH, ExploreLoot.table(table));
        br.written++;
        // sealed from below by natural ground; a boulder cluster over it
        Builders.blob(br, 0, 0, 1, 1, 1, true);
        Builders.blob(br, 2, 1, 1, 1, 1, true);
        Builders.blob(br, -2, -1, 1, 2, 1, true);
        br.top(1, -2, 0, br.rock.slab());

        return true;
    }
}
