package com.skycraft.realms.worldgen.decor;

import com.skycraft.realms.RealmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;

import static com.skycraft.realms.worldgen.decor.DecorBase.NONE;

/**
 * The Soul Cairn's decorations.
 * <ul>
 *     <li>{@code bone_pillar}: a crooked column of bone, or two joined in an arch, topped and strewn with skulls.</li>
 *     <li>{@code soul_gem_spire}: a cluster of glowing soul-gem crystals bursting from the ash, small clusters at
 *     their tips and feet.</li>
 *     <li>{@code dead_tree}: a bare, blackened tree with crooked branches and twiggy ends.</li>
 *     <li>{@code bone_ribs}: the half-buried ribcage of something enormous, its spine running along the ground.</li>
 * </ul>
 */
final class SoulCairnDecor {
    private SoulCairnDecor() {}

    static void register() {
        RealmDecor.add("bone_pillar", SoulCairnDecor::bonePillar);
        RealmDecor.add("soul_gem_spire", SoulCairnDecor::soulGemSpire);
        RealmDecor.add("dead_tree", SoulCairnDecor::deadTree);
        RealmDecor.add("bone_ribs", SoulCairnDecor::boneRibs);
    }

    static BlockState bone() {
        return Blocks.BONE_BLOCK.defaultBlockState();
    }

    static BlockState skull(RandomSource random) {
        BlockState s = (random.nextInt(5) == 0 ? Blocks.WITHER_SKELETON_SKULL : Blocks.SKELETON_SKULL).defaultBlockState();
        return s.setValue(SkullBlock.ROTATION, random.nextInt(16));
    }

    /** Skulls dropped on standable spots around (x0, z0). */
    static void strewSkulls(DecorKit k, RandomSource random, int x0, int y0, int z0, int n, int spread) {
        for (int i = 0; i < n; i++) {
            int x = x0 + random.nextInt(spread * 2 + 1) - spread, z = z0 + random.nextInt(spread * 2 + 1) - spread;
            int y = DecorBase.floor(k, x, y0 + 3, z);
            if (y != NONE && Math.abs(y - y0) <= 3 && k.standable(x, y, z)) k.put(x, y, z, random.nextInt(3) == 0 ? bone() : skull(random));
        }
    }

    // ------------------------------------------------------------------ bone pillars and arches

    static boolean bonePillar(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE) return false;
        int s = Mth.clamp(size, 1, 3);
        if (random.nextInt(3) == 0) {
            // an arch: two leaning legs joined over the top
            boolean alongX = random.nextBoolean();
            int half = 3 + random.nextInt(2) + s / 2;
            int h = 5 + s * 2 + random.nextInt(3);
            int ax = x0 - (alongX ? half : 0), az = z0 - (alongX ? 0 : half), bx = x0 + (alongX ? half : 0), bz = z0 + (alongX ? 0 : half);
            int ya = DecorBase.floor(k, ax, y0 + 4, az), yb = DecorBase.floor(k, bx, y0 + 4, bz);
            if (ya == NONE || yb == NONE) return false;
            k.curve(ax + 0.5, ya - 1, az + 0.5, ax + 0.5, ya + h * 0.7, az + 0.5, x0 + 0.5, y0 + h + 1, z0 + 0.5, 0.9, 0.7, bone(), false);
            k.curve(bx + 0.5, yb - 1, bz + 0.5, bx + 0.5, yb + h * 0.7, bz + 0.5, x0 + 0.5, y0 + h + 1, z0 + 0.5, 0.9, 0.7, bone(), false);
            k.put(x0, y0 + h + 2, z0, skull(random));
            strewSkulls(k, random, x0, y0, z0, 2 + s, 4);
            return true;
        }
        int h = 5 + s * 2 + random.nextInt(4);
        double ox = random.nextDouble() * 2 - 1, oz = random.nextDouble() * 2 - 1;
        double r = 0.7 + s * 0.2;
        k.curve(x0 + 0.5, y0 - 1, z0 + 0.5, x0 + 0.5 - ox, y0 + h * 0.5, z0 + 0.5 - oz, x0 + 0.5 + ox, y0 + h, z0 + 0.5 + oz, r + 0.3, r, bone(), false);
        int tx = (int) Math.floor(x0 + 0.5 + ox), tz = (int) Math.floor(z0 + 0.5 + oz);
        // a ring of skulls round a cap of bone
        k.put(tx, y0 + h + 1, tz, bone());
        k.put(tx, y0 + h + 2, tz, skull(random));
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (random.nextInt(2) == 0) k.put(tx + dir.getStepX(), y0 + h + 1, tz + dir.getStepZ(), skull(random));
        }
        strewSkulls(k, random, x0, y0, z0, 2 + s, 3);
        return true;
    }

    // ------------------------------------------------------------------ soul-gem spires

    static boolean soulGemSpire(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE) return false;
        int s = Mth.clamp(size, 1, 3);
        BlockState gem = RealmBlocks.SOUL_GEM_BLOCK.get().defaultBlockState();
        BlockState bud = DecorBase.cluster(RealmBlocks.SOUL_GEM_CLUSTER.get(), Direction.UP);
        int n = 3 + s * 2;
        for (int i = 0; i < n; i++) {
            double bx = x0 + 0.5 + (i == 0 ? 0 : random.nextDouble() * 3 - 1.5), bz = z0 + 0.5 + (i == 0 ? 0 : random.nextDouble() * 3 - 1.5);
            double len = i == 0 ? 8 + s * 2 + random.nextInt(4) : 3 + random.nextInt(4) + s * 2;
            double tilt = i == 0 ? random.nextDouble() * 0.2 : 0.2 + random.nextDouble() * 0.45;
            double a = random.nextDouble() * Math.PI * 2;
            double dx = Math.cos(a) * tilt, dz = Math.sin(a) * tilt, dy = Math.sqrt(1 - tilt * tilt);
            double ex = bx + dx * len, ey = y0 - 1 + dy * len, ez = bz + dz * len;
            double r = i == 0 ? 1.3 + s * 0.15 : 0.7 + random.nextDouble() * 0.5;
            k.curve(bx, y0 - 1, bz, (bx + ex) / 2, (y0 - 1 + ey) / 2, (bz + ez) / 2, ex, ey, ez, r, 0.45, gem, false);
            k.put((int) Math.floor(ex), (int) Math.floor(ey) + 1, (int) Math.floor(ez), bud);
        }
        // small crystals sprouting round the foot
        for (int i = 0; i < 4 + s * 2; i++) {
            int x = x0 + random.nextInt(7) - 3, z = z0 + random.nextInt(7) - 3;
            int y = DecorBase.floor(k, x, y0 + 3, z);
            if (y != NONE && Math.abs(y - y0) <= 3 && k.standable(x, y, z)) k.put(x, y, z, bud);
        }
        return true;
    }

    // ------------------------------------------------------------------ dead trees

    static boolean deadTree(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE) return false;
        int s = Mth.clamp(size, 1, 3);
        BlockState wood = Blocks.DARK_OAK_WOOD.defaultBlockState(), charred = Blocks.BASALT.defaultBlockState();
        BlockState twig = Blocks.DARK_OAK_FENCE.defaultBlockState();
        int h = 5 + s * 2 + random.nextInt(4);
        double a = random.nextDouble() * Math.PI * 2;
        double tx = x0 + 0.5 + Math.cos(a) * (1 + random.nextDouble() * 1.5), tz = z0 + 0.5 + Math.sin(a) * (1 + random.nextDouble() * 1.5);
        double cx = x0 + 0.5 - Math.cos(a) * 1.2, cz = z0 + 0.5 - Math.sin(a) * 1.2;
        k.curve(x0 + 0.5, y0 - 1, z0 + 0.5, cx, y0 + h * 0.5, cz, tx, y0 + h, tz, 0.8 + s * 0.15, 0.5, wood, false);
        // charred bark at the foot
        for (int i = 0; i < 6; i++) {
            int x = x0 + random.nextInt(3) - 1, z = z0 + random.nextInt(3) - 1, y = y0 + random.nextInt(3);
            if (k.get(x, y, z).is(Blocks.DARK_OAK_WOOD)) k.force(x, y, z, charred);
        }
        int nb = 3 + random.nextInt(2) + s / 2;
        for (int i = 0; i < nb; i++) {
            double t = 0.45 + random.nextDouble() * 0.55;
            double sx = DecorBase.bez(x0 + 0.5, cx, tx, t), sz = DecorBase.bez(z0 + 0.5, cz, tz, t), sy = y0 + h * t;
            double ba = i * Math.PI * 2 / nb + random.nextDouble() * 0.8;
            double len = 2.5 + random.nextDouble() * 2 + s;
            double ex = sx + Math.cos(ba) * len, ez = sz + Math.sin(ba) * len, ey = sy + 1.5 + random.nextDouble() * 2.5;
            k.curve(sx, sy, sz, (sx + ex) / 2 + random.nextDouble() - 0.5, sy + 0.5, (sz + ez) / 2 + random.nextDouble() - 0.5,
                    ex, ey, ez, 0.5, 0.4, wood, false);
            // twigs: thin posts reaching on from the branch end
            int bx = (int) Math.floor(ex), by = (int) Math.floor(ey), bz = (int) Math.floor(ez);
            int tl = 1 + random.nextInt(2);
            for (int j = 1; j <= tl; j++) k.put(bx, by + j, bz, twig);
        }
        return true;
    }

    // ------------------------------------------------------------------ half-buried ribcages

    static boolean boneRibs(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE) return false;
        int s = Mth.clamp(size, 1, 3);
        double a = random.nextDouble() * Math.PI * 2, ca = Math.cos(a), sa = Math.sin(a);
        double px = -sa, pz = ca;
        double half = 5 + s * 1.5;
        double w = 3 + s, hMax = 4 + s * 1.7;
        double ax = x0 + 0.5 - ca * half, az = z0 + 0.5 - sa * half, bx = x0 + 0.5 + ca * half, bz = z0 + 0.5 + sa * half;
        // the spine, half sunk in the ground
        k.line(ax, y0 - 0.5, az, bx, y0 - 0.3, bz, 1.0, bone(), false);
        int ribs = 5 + s;
        for (int i = 0; i < ribs; i++) {
            double t = (i + 0.5) / ribs;
            double sx = Mth.lerp(t, ax, bx), sz = Mth.lerp(t, az, bz);
            double h = hMax * (0.55 + 0.45 * Math.sin(Math.PI * t));
            double ww = w * (0.6 + 0.4 * Math.sin(Math.PI * t));
            for (int side = -1; side <= 1; side += 2) {
                if (random.nextInt(7) == 0) continue; // a rib long gone
                k.curve(sx + px * side, y0, sz + pz * side,
                        sx + px * side * ww * 1.25, y0 + h * 0.9, sz + pz * side * ww * 1.25,
                        sx + px * side * ww * 0.35, y0 + h, sz + pz * side * ww * 0.35, 0.7, 0.4, bone(), false);
            }
        }
        // the skull at the head end
        int hx = (int) Math.floor(bx + ca * 1.5), hz = (int) Math.floor(bz + sa * 1.5);
        k.blob(hx, y0, hz, 1.8, 1.4, 1.8, bone(), false);
        k.put(hx, y0 + 2, hz, skull(random));
        return true;
    }
}
