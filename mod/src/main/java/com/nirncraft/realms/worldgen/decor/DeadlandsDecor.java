package com.nirncraft.realms.worldgen.decor;

import com.nirncraft.realms.RealmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Deadlands' decorations: jagged rock spikes leaning out of the ground (some with burning cores), eternal
 * flames on the glowing rock, and red tendrils (the hanging harrada) dripping from overhangs.
 */
final class DeadlandsDecor {
    private DeadlandsDecor() {}

    static void register() {
        RealmDecor.add("deadlands_spike", DeadlandsDecor::spike);
        RealmDecor.add("deadlands_flames", DeadlandsDecor::flames);
        RealmDecor.add("hanging_vines", DeadlandsDecor::hangingVines);
    }

    /** A cluster of leaning rock spikes: thick at the root, needle-thin at the tip; size 2 makes taller ones. */
    static boolean spike(WorldGenLevel level, BlockPos origin, RandomSource r, int size) {
        DecorKit k = new DecorKit(level, origin);
        int y0 = k.surface(origin.getX(), origin.getZ());
        if (y0 <= level.getSeaLevel() + 1) return false;
        BlockState rock = RealmBlocks.DEADLANDS_ROCK.get().defaultBlockState();
        BlockState black = Blocks.BLACKSTONE.defaultBlockState();
        BlockState magma = RealmBlocks.DEADLANDS_MAGMA_ROCK.get().defaultBlockState();
        int n = 1 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            double bx = origin.getX() + 0.5 + (r.nextDouble() - 0.5) * 7, bz = origin.getZ() + 0.5 + (r.nextDouble() - 0.5) * 7;
            int base = k.surface((int) Math.floor(bx), (int) Math.floor(bz)) - 2;
            int h = (6 + r.nextInt(10)) * (size >= 2 ? 2 : 1) - i * 2;
            if (h < 4) continue;
            double lean = 0.25 + r.nextDouble() * 0.35;
            double a = r.nextDouble() * Math.PI * 2;
            double tx = bx + Math.cos(a) * h * lean, tz = bz + Math.sin(a) * h * lean;
            double r0 = 1.4 + h * 0.07;
            k.curve(bx, base, bz, bx + (tx - bx) * 0.4, base + h * 0.55, bz + (tz - bz) * 0.4, tx, base + h, tz, r0, 0.35,
                    r.nextInt(4) == 0 ? black : rock, false);
            if (r.nextInt(3) == 0) k.line(bx, base + 1, bz, bx + (tx - bx) * 0.3, base + h * 0.35, bz + (tz - bz) * 0.3, 0.6, magma, true);
            // root it into the ground so it never floats over a dip
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) k.root((int) Math.floor(bx) + dx, base, (int) Math.floor(bz) + dz, rock, 8);
        }
        return true;
    }

    /** Eternal fires on glowing rock (the realm's infiniburn keeps them lit). */
    static boolean flames(WorldGenLevel level, BlockPos origin, RandomSource r, int size) {
        DecorKit k = new DecorKit(level, origin);
        BlockState magma = RealmBlocks.DEADLANDS_MAGMA_ROCK.get().defaultBlockState();
        boolean any = false;
        for (int i = 0; i < 10 * size; i++) {
            int x = origin.getX() + r.nextInt(9) - 4, z = origin.getZ() + r.nextInt(9) - 4;
            int y = k.surface(x, z);
            if (y <= level.getSeaLevel() + 1) continue;
            BlockState below = k.get(x, y - 1, z);
            // natural ground only (never the keeps' and citadels' floors)
            if (!below.is(RealmBlocks.DEADLANDS_MAGMA_ROCK.get()) && !below.is(RealmBlocks.DEADLANDS_ROCK.get())
                    && !below.is(RealmBlocks.DEADLANDS_ASH.get())) continue;
            if (!below.is(RealmBlocks.DEADLANDS_MAGMA_ROCK.get()) && r.nextInt(3) != 0) continue;
            if (!below.is(RealmBlocks.DEADLANDS_MAGMA_ROCK.get())) k.force(x, y - 1, z, magma);
            any |= k.put(x, y, z, Blocks.FIRE.defaultBlockState());
        }
        return any;
    }

    /** Red tendrils hanging from the underside of ledges and overhangs. */
    static boolean hangingVines(WorldGenLevel level, BlockPos origin, RandomSource r, int size) {
        DecorKit k = new DecorKit(level, origin);
        boolean any = false;
        for (int i = 0; i < 24; i++) {
            int x = origin.getX() + r.nextInt(11) - 5, z = origin.getZ() + r.nextInt(11) - 5;
            int y = origin.getY() + r.nextInt(17) - 8;
            if (!k.get(x, y, z).isAir()) continue;
            BlockState above = k.get(x, y + 1, z);
            if (!above.isFaceSturdy(level, new BlockPos(x, y + 1, z), net.minecraft.core.Direction.DOWN)) continue;
            int len = 2 + r.nextInt(9);
            int placed = 0;
            for (int d = 0; d < len; d++) {
                if (!k.get(x, y - d, z).isAir()) break;
                boolean last = d == len - 1 || !k.get(x, y - d - 1, z).isAir();
                BlockState s = last ? Blocks.WEEPING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 25)
                        : Blocks.WEEPING_VINES_PLANT.defaultBlockState();
                if (!k.put(x, y - d, z, s)) break;
                placed++;
            }
            any |= placed > 0;
        }
        return any;
    }
}
