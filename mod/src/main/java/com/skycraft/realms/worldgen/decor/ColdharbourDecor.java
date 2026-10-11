package com.skycraft.realms.worldgen.decor;

import com.skycraft.realms.RealmBlocks;
import com.skycraft.realms.block.SpikeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.state.BlockState;

import static com.skycraft.realms.worldgen.decor.DecorBase.NONE;

/**
 * Coldharbour's decorations.
 * <ul>
 *     <li>{@code chain_pylon}: a spiked anchor head of Coldharbour bricks and blackstone driven into the ground, with
 *     chains rising from it high into the sky (as if hung from it).</li>
 *     <li>{@code broken_pillar}: a snapped pillar with a jagged top and its fallen pieces beside it.</li>
 *     <li>{@code iron_spikes}: a thicket of Daedric spikes, some on iron-bar posts.</li>
 *     <li>{@code soul_fire_pit}: a sunken ring of soul soil burning with soul fire, edged with stone and spikes.</li>
 * </ul>
 */
final class ColdharbourDecor {
    private ColdharbourDecor() {}

    static void register() {
        RealmDecor.add("chain_pylon", ColdharbourDecor::chainPylon);
        RealmDecor.add("broken_pillar", ColdharbourDecor::brokenPillar);
        RealmDecor.add("iron_spikes", ColdharbourDecor::ironSpikes);
        RealmDecor.add("soul_fire_pit", ColdharbourDecor::soulFirePit);
    }

    static BlockState bricks() {
        return RealmBlocks.COLDHARBOUR_BRICKS.get().defaultBlockState();
    }

    static BlockState spike(Direction d) {
        return RealmBlocks.DAEDRIC_SPIKE.get().defaultBlockState().setValue(SpikeBlock.FACING, d);
    }

    static BlockState chain() {
        return Blocks.CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, Direction.Axis.Y);
    }

    // ------------------------------------------------------------------ chain pylons

    static boolean chainPylon(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE || !DecorBase.dry(k, x0, y0, z0)) return false;
        int s = Mth.clamp(size, 1, 3);
        BlockState black = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        // the anchor head, sunk to its shoulders
        for (int y = y0 - 2; y <= y0 + 3; y++) {
            double r = Mth.lerp((y - y0 + 2) / 5.0, 2.6, 0.8);
            for (int x = x0 - 3; x <= x0 + 3; x++)
                for (int z = z0 - 3; z <= z0 + 3; z++) {
                    double dx = x - x0, dz = z - z0;
                    if (dx * dx + dz * dz > r * r + 0.3) continue;
                    BlockState st = (y + x + z & 3) == 0 ? black : bricks();
                    if (y < y0) k.force(x, y, z, st);
                    else k.put(x, y, z, st);
                }
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            k.put(x0 + dir.getStepX() * 2, y0 + 1, z0 + dir.getStepZ() * 2, spike(dir));
            k.put(x0 + dir.getStepX() * 3, y0, z0 + dir.getStepZ() * 3, spike(dir));
        }
        // the chains: one great strand from the crown, lesser ones beside it
        int top = y0 + 24 + s * 8 + random.nextInt(10);
        for (int y = y0 + 4; y <= top; y++) k.put(x0, y, z0, chain());
        int n = 1 + random.nextInt(2) + (s >= 2 ? 1 : 0);
        for (int i = 0; i < n; i++) {
            Direction dir = Direction.from2DDataValue(random.nextInt(4));
            int x = x0 + dir.getStepX(), z = z0 + dir.getStepZ();
            int t = top - 4 - random.nextInt(10);
            for (int y = y0 + 3; y <= t; y++) k.put(x, y, z, chain());
        }
        return true;
    }

    // ------------------------------------------------------------------ broken pillars

    static boolean brokenPillar(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE || !DecorBase.dry(k, x0, y0, z0)) return false;
        int s = Mth.clamp(size, 1, 3);
        int h = 4 + s * 2 + random.nextInt(5);
        boolean square = random.nextBoolean();
        BlockState band = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState(), stone = RealmBlocks.COLDHARBOUR_STONE.get().defaultBlockState();
        for (int x = x0 - 1; x <= x0 + 1; x++)
            for (int z = z0 - 1; z <= z0 + 1; z++) {
                if (square ? (x == x0 + 1 || z == z0 + 1) : (Math.abs(x - x0) + Math.abs(z - z0) > 1)) continue;
                int top = y0 + h - random.nextInt(4);
                k.root(x, y0 - 1, z, stone, 6);
                for (int y = y0; y <= top; y++) k.put(x, y, z, y == y0 || y == y0 + 4 ? band : random.nextInt(9) == 0 ? stone : bricks());
            }
        // the fallen drum and chips
        double a = random.nextDouble() * Math.PI * 2;
        double fx = x0 + 0.5 + Math.cos(a) * 3, fz = z0 + 0.5 + Math.sin(a) * 3;
        int fy = DecorBase.floor(k, (int) Math.floor(fx), y0 + 3, (int) Math.floor(fz));
        if (fy != NONE && Math.abs(fy - y0) <= 3) {
            k.line(fx, fy + 0.5, fz, fx + Math.cos(a) * (2 + s), fy + 0.5, fz + Math.sin(a) * (2 + s), 0.8, bricks(), false);
        }
        for (int i = 0; i < 3 + s; i++) {
            int x = x0 + random.nextInt(7) - 3, z = z0 + random.nextInt(7) - 3;
            int y = DecorBase.floor(k, x, y0 + 3, z);
            if (y != NONE && Math.abs(y - y0) <= 3 && k.standable(x, y, z)) {
                k.put(x, y, z, random.nextBoolean() ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultBlockState());
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ iron spikes

    static boolean ironSpikes(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE) return false;
        int s = Mth.clamp(size, 1, 3);
        int n = 5 + s * 3 + random.nextInt(4);
        int r = 2 + s;
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int x = x0 + random.nextInt(r * 2 + 1) - r, z = z0 + random.nextInt(r * 2 + 1) - r;
            int y = DecorBase.floor(k, x, y0 + 3, z);
            if (y == NONE || Math.abs(y - y0) > 3 || !k.standable(x, y, z) || !DecorBase.dry(k, x, y, z)) continue;
            int posts = random.nextInt(3) == 0 ? 1 + random.nextInt(3) : 0;
            for (int j = 0; j < posts; j++) k.put(x, y + j, z, Blocks.IRON_BARS.defaultBlockState());
            k.put(x, y + posts, z, spike(Direction.UP));
            any = true;
        }
        return any;
    }

    // ------------------------------------------------------------------ soul-fire pits

    static boolean soulFirePit(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE || !DecorBase.dry(k, x0, y0, z0)) return false;
        int s = Mth.clamp(size, 1, 3);
        double r = 1.5 + s * 0.5 + random.nextDouble() * 0.5;
        int ir = (int) Math.ceil(r) + 1;
        BlockState soil = Blocks.SOUL_SOIL.defaultBlockState(), fire = Blocks.SOUL_FIRE.defaultBlockState();
        for (int x = x0 - ir; x <= x0 + ir; x++)
            for (int z = z0 - ir; z <= z0 + ir; z++) {
                double d = Math.sqrt((x - x0) * (x - x0) + (z - z0) * (z - z0));
                if (d <= r) {
                    k.force(x, y0 - 2, z, soil);
                    k.force(x, y0 - 1, z, fire);
                    for (int y = y0; y <= y0 + 2; y++) if (!k.get(x, y, z).isAir()) k.force(x, y, z, Blocks.AIR.defaultBlockState());
                } else if (d <= r + 1.2) {
                    k.force(x, y0 - 1, z, (x + z & 1) == 0 ? bricks() : Blocks.BLACKSTONE.defaultBlockState());
                    if (random.nextInt(3) == 0) k.put(x, y0, z, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultBlockState());
                    else if (random.nextInt(7) == 0) k.put(x, y0, z, spike(Direction.UP));
                }
            }
        return true;
    }
}
