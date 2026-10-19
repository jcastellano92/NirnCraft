package com.nirncraft.seas.world;

import com.mojang.serialization.Codec;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.NoneFeatureConfiguration;

/**
 * The living sea bed, by the warmth of the water: kelp forests (tall swaying groves over mossy boulders) in the cold
 * and temperate seas, coral reefs in the warm ones (the Inner Sea, Topal Bay, the Eltheric, Thras), and pearl beds of
 * kollops in Morrowind's waters and the warm shallows. Placed by {@code nirncraft:seas_sea_gardens}.
 */
public class SeaGardenFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    /** Sites placed since start (logged by the developer smoke test). */
    public static final java.util.concurrent.atomic.AtomicInteger PLACED = new java.util.concurrent.atomic.AtomicInteger();

    public SeaGardenFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        SiteKit k = new SiteKit(ctx.level(), ctx.random(), ctx.origin());
        int depth = k.depth(k.cx, k.cz);
        if (depth < 3) return false;
        Region region = TamrielMap.region(k.cx, k.cz);
        float temp = ctx.level().getBiome(new BlockPos(k.cx, 62, k.cz)).value().getBaseTemperature();
        String id = region.id(), prov = region.province();
        boolean warm = temp >= 0.75f || id.equals("sea/inner_sea") || id.equals("sea/topal_bay") || id.equals("sea/eltheric_ocean")
                || prov.equals("thras") || prov.equals("black_marsh") || prov.equals("elsweyr") || prov.equals("valenwood");
        boolean pearls = id.equals("sea/inner_sea") || prov.equals("morrowind") || warm && k.r.nextInt(3) == 0;
        if (warm && depth <= 16) reef(k);
        else if (!warm && depth >= 4) kelpForest(k);
        if (pearls && depth <= 14) Undersea.kollops(k, k.cx, k.cz, 7, 6 + k.r.nextInt(8));
        PLACED.incrementAndGet();
        return true;
    }

    /** A kelp forest: dense kelp to just under the surface, seagrass meadows, mossy boulders. */
    static void kelpForest(SiteKit k) {
        for (int i = 0; i < 38; i++) {
            int dx = k.nextInt(-11, 11), dz = k.nextInt(-11, 11);
            if (dx * dx + dz * dz > 121) continue;
            int x = k.cx + dx, z = k.cz + dz;
            int bed = k.floor(x, z);
            if (!k.water(x, bed, z)) continue;
            int top = k.surface(x, z) - 1;
            int h = Math.max(1, top - bed - k.r.nextInt(3));
            for (int y = 0; y < h; y++) {
                if (!k.water(x, bed + y, z)) break;
                boolean last = y == h - 1 || !k.water(x, bed + y + 1, z);
                k.set(x, bed + y, z, last ? Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 22 + k.r.nextInt(3))
                        : Blocks.KELP_PLANT.defaultBlockState());
                if (last) break;
            }
        }
        for (int i = 0; i < 40; i++) {
            int x = k.cx + k.nextInt(-12, 12), z = k.cz + k.nextInt(-12, 12);
            int bed = k.floor(x, z);
            if (!k.water(x, bed, z)) continue;
            if (k.r.nextInt(4) == 0 && k.water(x, bed + 1, z)) {
                k.set(x, bed, z, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
                k.set(x, bed + 1, z, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
            } else {
                k.set(x, bed, z, Blocks.SEAGRASS.defaultBlockState());
            }
        }
        for (int i = 0; i < 3; i++) {
            int x = k.cx + k.nextInt(-8, 8), z = k.cz + k.nextInt(-8, 8);
            int bed = k.floor(x, z);
            int r = 1 + k.r.nextInt(2);
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    for (int dy = 0; dy <= r; dy++)
                        if (dx * dx + dz * dz + dy * dy * 2 <= r * r + 1)
                            k.set(x + dx, bed + dy, z + dz, k.r.nextInt(3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
        }
    }

    private static final Block[] CORAL_BLOCKS = {Blocks.TUBE_CORAL_BLOCK, Blocks.BRAIN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK,
            Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK};
    private static final Block[] CORALS = {Blocks.TUBE_CORAL, Blocks.BRAIN_CORAL, Blocks.BUBBLE_CORAL, Blocks.FIRE_CORAL, Blocks.HORN_CORAL,
            Blocks.TUBE_CORAL_FAN, Blocks.BRAIN_CORAL_FAN, Blocks.BUBBLE_CORAL_FAN, Blocks.FIRE_CORAL_FAN, Blocks.HORN_CORAL_FAN};
    private static final Block[] WALL_FANS = {Blocks.TUBE_CORAL_WALL_FAN, Blocks.BRAIN_CORAL_WALL_FAN, Blocks.BUBBLE_CORAL_WALL_FAN,
            Blocks.FIRE_CORAL_WALL_FAN, Blocks.HORN_CORAL_WALL_FAN};

    /** A coral reef: knolls of coral heads, branching corals and fans, sea pickles glowing in the gaps. */
    static void reef(SiteKit k) {
        int knolls = 4 + k.r.nextInt(5);
        for (int n = 0; n < knolls; n++) {
            int ox = k.cx + k.nextInt(-9, 9), oz = k.cz + k.nextInt(-9, 9);
            int bed = k.floor(ox, oz);
            int maxH = Math.max(1, k.surface(ox, oz) - bed - 2);
            int h = Math.min(maxH, 1 + k.r.nextInt(4));
            float rad = 1.5f + k.r.nextFloat() * 2.5f;
            Block main = CORAL_BLOCKS[k.r.nextInt(CORAL_BLOCKS.length)];
            int ir = (int) Math.ceil(rad);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    double d = Math.hypot(dx, dz) / rad;
                    if (d > 1) continue;
                    int x = ox + dx, z = oz + dz;
                    int b = k.floor(x, z);
                    int top = b + (int) Math.round(h * (1 - d * d)) ;
                    for (int y = b; y <= top; y++) {
                        Block blk = k.r.nextInt(5) == 0 ? CORAL_BLOCKS[k.r.nextInt(CORAL_BLOCKS.length)] : main;
                        k.set(x, y, z, blk.defaultBlockState());
                    }
                    if (k.water(x, top + 1, z) && k.r.nextInt(3) > 0) {
                        Block c = CORALS[k.r.nextInt(CORALS.length)];
                        k.set(x, top + 1, z, c.defaultBlockState());
                    }
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        if (k.r.nextInt(6) != 0) continue;
                        int fx = x + dir.getStepX(), fz = z + dir.getStepZ(), fy = b + k.r.nextInt(Math.max(1, top - b + 1));
                        if (k.water(fx, fy, fz) && k.get(fx, fy, fz).is(Blocks.WATER)) {
                            k.set(fx, fy, fz, WALL_FANS[k.r.nextInt(WALL_FANS.length)].defaultBlockState()
                                    .setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
                        }
                    }
                }
            }
        }
        for (int i = 0; i < 10; i++) {
            int x = k.cx + k.nextInt(-10, 10), z = k.cz + k.nextInt(-10, 10);
            int bed = k.floor(x, z);
            if (!k.water(x, bed, z)) continue;
            BlockState st = k.r.nextInt(3) == 0 ? Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES, 1 + k.r.nextInt(4))
                    : Blocks.SEAGRASS.defaultBlockState();
            k.set(x, bed, z, st);
        }
    }
}
