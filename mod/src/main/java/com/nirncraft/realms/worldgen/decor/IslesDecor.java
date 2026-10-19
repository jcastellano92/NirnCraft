package com.nirncraft.realms.worldgen.decor;

import com.nirncraft.realms.RealmBlocks;
import com.nirncraft.tamriel.biome.BiomeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.state.BlockState;

import static com.nirncraft.realms.worldgen.decor.DecorBase.NONE;

/**
 * The Shivering Isles' decorations.
 * <ul>
 *     <li>{@code giant_mushroom} (size 1..3): Mania's towering mushrooms: a pale, leaning stem 8 to 22 tall under a
 *     violet, teal or amber cap (radius 3 to 7) with a drooping, spotted rim and glowing gills.</li>
 *     <li>{@code crystal_tree}: a trunk of crystal branching into shards of coloured glass and crystal buds.</li>
 *     <li>{@code gnarled_tree}: Dementia's twisted dark oak or mangrove: buttress roots, crooked drooping branches,
 *     sparse dark leaves hung with glowing vines.</li>
 *     <li>{@code mania_spire}: a tall, twisting pillar of white stone with spiralling purple bands, shelf caps and a
 *     glowing cap on top.</li>
 * </ul>
 */
final class IslesDecor {
    private IslesDecor() {}

    static void register() {
        RealmDecor.add("giant_mushroom", IslesDecor::giantMushroom);
        RealmDecor.add("crystal_tree", IslesDecor::crystalTree);
        RealmDecor.add("gnarled_tree", IslesDecor::gnarledTree);
        RealmDecor.add("mania_spire", IslesDecor::maniaSpire);
    }

    static BlockState cap(int i) {
        return switch (Math.floorMod(i, 3)) {
            case 0 -> RealmBlocks.MANIA_CAP_VIOLET.get().defaultBlockState();
            case 1 -> RealmBlocks.MANIA_CAP_TEAL.get().defaultBlockState();
            default -> RealmBlocks.MANIA_CAP_AMBER.get().defaultBlockState();
        };
    }

    /** The glowing gill block that goes with each cap colour. */
    static BlockState gill(int i) {
        return switch (Math.floorMod(i, 3)) {
            case 0 -> Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState();
            case 1 -> Blocks.VERDANT_FROGLIGHT.defaultBlockState();
            default -> Blocks.OCHRE_FROGLIGHT.defaultBlockState();
        };
    }

    // ------------------------------------------------------------------ giant mushroom

    static boolean giantMushroom(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE || !DecorBase.dry(k, x0, y0, z0)) return false;
        int s = Mth.clamp(size, 1, 3);
        int h = Math.min(22, 8 + s * 3 + random.nextInt(5) + (s == 3 ? random.nextInt(3) : 0));
        int capR = Math.min(7, 2 + s + (s >= 2 ? 1 : 0) + random.nextInt(2));
        int capH = 1 + s + random.nextInt(2);
        int droop = 1 + random.nextInt(s + 1);
        int colour = random.nextInt(3);
        BlockState capB = cap(colour), spot = cap(colour + 1 + random.nextInt(2)), gill = gill(colour);
        BlockState stem = RealmBlocks.MANIA_STEM.get().defaultBlockState();
        double a = random.nextDouble() * Math.PI * 2, lean = 0.8 + random.nextDouble() * (1.0 + s);
        double tx = x0 + 0.5 + Math.cos(a) * lean, tz = z0 + 0.5 + Math.sin(a) * lean;
        int ty = y0 + h - capH;
        double r0 = s == 1 ? 0.8 : s == 2 ? 1.2 : 1.6;
        // a flared foot and an S-curved stem
        k.blob(x0, y0, z0, r0 + 1.2, 1.0, r0 + 1.2, stem, false);
        k.curve(x0 + 0.5, y0 - 1, z0 + 0.5, x0 + 0.5 - Math.cos(a) * lean * 0.5, y0 + h * 0.45, z0 + 0.5 - Math.sin(a) * lean * 0.5,
                tx, ty + capH, tz, r0, r0 * 0.75, stem, false);
        // the cap: a dome whose rim droops, spotted, with radial glowing gills underneath
        int cx = (int) Math.floor(tx), cz = (int) Math.floor(tz);
        for (int dx = -capR - 1; dx <= capR + 1; dx++)
            for (int dz = -capR - 1; dz <= capR + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > capR + 0.4) continue;
                double t = Math.min(1, d / capR);
                int yTop = ty + (int) Math.round((1 - t * t) * capH);
                int yLow = t > 0.72 ? yTop - (int) Math.round((t - 0.72) / 0.28 * droop) : yTop;
                for (int y = yLow; y <= yTop; y++) {
                    BlockState c = ((dx * 31 + dz * 17 + y * 7) & 7) == 0 ? spot : capB;
                    BlockState old = k.get(cx + dx, y, cz + dz);
                    if (DecorKit.open(old) || old.is(stem.getBlock())) k.force(cx + dx, y, cz + dz, c);
                }
                if (t > 0.3 && t < 0.85) {
                    double ang = Math.atan2(dz, dx) + Math.PI;
                    if (Math.floorMod((int) Math.floor(ang * capR * 0.9), 2) == 0) k.put(cx + dx, yLow - 1, cz + dz, gill);
                }
            }
        // a few blooms at its foot
        for (int i = 0; i < 4 + s * 2; i++) {
            int x = x0 + random.nextInt(9) - 4, z = z0 + random.nextInt(9) - 4;
            int y = DecorBase.floor(k, x, y0 + 3, z);
            if (y != NONE && Math.abs(y - y0) <= 3 && k.standable(x, y, z) && DecorBase.dry(k, x, y, z)) {
                k.put(x, y, z, RealmBlocks.MANIA_BLOOM.get().defaultBlockState());
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ crystal tree

    static boolean crystalTree(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE || !DecorBase.dry(k, x0, y0, z0)) return false;
        int s = Mth.clamp(size, 1, 3);
        int height = 5 + s * 2 + random.nextInt(4);
        BlockState crystal = BiomeBlocks.CRYSTAL_BLOCK.get().defaultBlockState();
        BlockState bud = DecorBase.cluster(BiomeBlocks.CRYSTAL_CLUSTER.get(), Direction.UP);
        Block[] glass = {com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS, com.nirncraft.worldgen.VanillaBlocks.CYAN_STAINED_GLASS, com.nirncraft.worldgen.VanillaBlocks.PURPLE_STAINED_GLASS, com.nirncraft.worldgen.VanillaBlocks.MAGENTA_STAINED_GLASS};
        double lean = random.nextDouble() * 1.5, a = random.nextDouble() * Math.PI * 2;
        double topX = x0 + 0.5 + Math.cos(a) * lean, topZ = z0 + 0.5 + Math.sin(a) * lean;
        k.line(x0 + 0.5, y0 - 1, z0 + 0.5, topX, y0 + height, topZ, 0.6 + s * 0.3, crystal, false);
        int n = 3 + random.nextInt(3);
        for (int i = 0; i < n; i++) {
            double ba = i * Math.PI * 2 / n + random.nextDouble() * 0.6;
            double t = 0.55 + random.nextDouble() * 0.4;
            double sx = Mth.lerp(t, x0 + 0.5, topX), sz = Mth.lerp(t, z0 + 0.5, topZ), sy = y0 + height * t;
            double len = 2.5 + s + random.nextDouble() * 2;
            double ex = sx + Math.cos(ba) * len, ez = sz + Math.sin(ba) * len, ey = sy + len * (0.5 + random.nextDouble() * 0.5);
            k.line(sx, sy, sz, ex, ey, ez, 0.5, crystal, false);
            int bx = (int) Math.floor(ex), by = (int) Math.floor(ey), bz = (int) Math.floor(ez);
            k.blob(bx, by, bz, 1.2, 1.2, 1.2, glass[random.nextInt(glass.length)].defaultBlockState(), false);
            k.put(bx, by + 2, bz, bud);
        }
        k.blob((int) Math.floor(topX), y0 + height, (int) Math.floor(topZ), 1.4, 1.4, 1.4, crystal, false);
        k.put((int) Math.floor(topX), y0 + height + 2, (int) Math.floor(topZ), bud);
        // buds scattered round the foot
        for (int i = 0; i < 3 + s; i++) {
            int x = x0 + random.nextInt(7) - 3, z = z0 + random.nextInt(7) - 3;
            int y = DecorBase.floor(k, x, y0 + 3, z);
            if (y != NONE && Math.abs(y - y0) <= 3 && k.standable(x, y, z) && DecorBase.dry(k, x, y, z)) k.put(x, y, z, bud);
        }
        return true;
    }

    // ------------------------------------------------------------------ gnarled tree

    static boolean gnarledTree(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE) return false;
        int s = Mth.clamp(size, 1, 3);
        boolean mangrove = random.nextBoolean();
        BlockState log = (mangrove ? Blocks.MANGROVE_LOG : Blocks.DARK_OAK_LOG).defaultBlockState();
        BlockState roots = mangrove ? Blocks.MANGROVE_ROOTS.defaultBlockState() : log;
        BlockState leaves = DecorBase.leaves(mangrove ? Blocks.MANGROVE_LEAVES : Blocks.DARK_OAK_LEAVES);
        int height = 7 + s * 3 + random.nextInt(4);
        double r0 = 0.9 + s * 0.25;
        double a = random.nextDouble() * Math.PI * 2;
        double tx = x0 + 0.5 + Math.cos(a) * (1.5 + random.nextDouble() * 2), tz = z0 + 0.5 + Math.sin(a) * (1.5 + random.nextDouble() * 2);
        double cx = x0 + 0.5 + Math.cos(a + Math.PI / 2) * (2 + random.nextDouble() * 1.5);
        double cz = z0 + 0.5 + Math.sin(a + Math.PI / 2) * (2 + random.nextDouble() * 1.5);
        k.curve(x0 + 0.5, y0 - 1, z0 + 0.5, cx, y0 + height * 0.5, cz, tx, y0 + height, tz, r0, r0 * 0.6, log, false);
        // buttress roots spreading into the mud
        int nr = 4 + random.nextInt(3);
        for (int i = 0; i < nr; i++) {
            double ra = i * Math.PI * 2 / nr + random.nextDouble() * 0.5;
            double len = 2.5 + random.nextDouble() * 2 + s;
            k.curve(x0 + 0.5, y0 + 1.5, z0 + 0.5, x0 + 0.5 + Math.cos(ra) * len * 0.6, y0 + 1.2, z0 + 0.5 + Math.sin(ra) * len * 0.6,
                    x0 + 0.5 + Math.cos(ra) * len, y0 - 1, z0 + 0.5 + Math.sin(ra) * len, 0.6, 0.45, roots, false);
        }
        // crooked, drooping branches with thin dark crowns hung with glowing vines
        BlockState vine = Blocks.CAVE_VINES_PLANT.defaultBlockState();
        int nb = 3 + random.nextInt(3);
        for (int i = 0; i < nb; i++) {
            double ba = i * Math.PI * 2 / nb + random.nextDouble() * 0.7;
            double t = 0.6 + random.nextDouble() * 0.4;
            double sx = DecorBase.bez(x0 + 0.5, cx, tx, t), sz = DecorBase.bez(z0 + 0.5, cz, tz, t), sy = y0 + height * t;
            double len = 3 + random.nextDouble() * 3 + s;
            double ex = sx + Math.cos(ba) * len, ez = sz + Math.sin(ba) * len, ey = sy + random.nextDouble() * 2;
            k.curve(sx, sy, sz, (sx + ex) / 2, ey + 1.5, (sz + ez) / 2, ex, ey - 1, ez, 0.55, 0.4, log, false);
            int lx = (int) Math.floor(ex), ly = (int) Math.floor(ey), lz = (int) Math.floor(ez);
            DecorBase.sparseBlob(k, random, lx, ly, lz, 2.2, 1.2, 2.2, leaves, 0.5);
            for (int j = 0; j < 4; j++) {
                int vx = lx + random.nextInt(5) - 2, vz = lz + random.nextInt(5) - 2;
                for (int vy = ly + 1; vy >= ly - 2; vy--) {
                    if (k.get(vx, vy, vz).is(leaves.getBlock()) && k.get(vx, vy - 1, vz).isAir()) {
                        boolean glow = random.nextInt(3) == 0;
                        DecorBase.hang(k, vx, vy - 1, vz, 2 + random.nextInt(5), vine.setValue(CaveVines.BERRIES, glow),
                                Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, glow));
                        break;
                    }
                }
            }
        }
        // vines and roots creeping up the trunk
        for (int i = 0; i < 14; i++) {
            int y = y0 + 1 + random.nextInt(Math.max(1, height - 3));
            double t = (y - y0) / (double) height;
            int x = (int) Math.floor(DecorBase.bez(x0 + 0.5, cx, tx, t)) + random.nextInt(5) - 2;
            int z = (int) Math.floor(DecorBase.bez(z0 + 0.5, cz, tz, t)) + random.nextInt(5) - 2;
            if (!k.get(x, y, z).isAir()) continue;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (k.get(x + dir.getStepX(), y, z + dir.getStepZ()).is(log.getBlock())) {
                    k.put(x, y, z, Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.PROPERTY_BY_DIRECTION.get(dir), true));
                    break;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ Mania spire

    static boolean maniaSpire(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE || !DecorBase.dry(k, x0, y0, z0)) return false;
        int s = Mth.clamp(size, 1, 3);
        int height = 14 + s * 5 + random.nextInt(7);
        double rBase = 2.0 + s * 0.5, rTop = 0.6;
        double sway = 1.2 + random.nextDouble() * 1.3, turns = 1 + random.nextDouble() * 1.5, ph = random.nextDouble() * Math.PI * 2;
        BlockState base = Blocks.CALCITE.defaultBlockState();
        BlockState band = random.nextBoolean() ? Blocks.PURPUR_BLOCK.defaultBlockState() : Blocks.AMETHYST_BLOCK.defaultBlockState();
        double cx = x0 + 0.5, cz = z0 + 0.5;
        for (int y = y0 - 2; y <= y0 + height; y++) {
            double t = Mth.clamp((y - y0) / (double) height, 0, 1);
            double r = Mth.lerp(t, rBase, rTop);
            double twist = t * turns * Math.PI * 2;
            cx = x0 + 0.5 + Math.cos(ph + twist) * sway * t;
            cz = z0 + 0.5 + Math.sin(ph + twist) * sway * t;
            for (int x = (int) Math.floor(cx - r); x <= (int) Math.ceil(cx + r); x++)
                for (int z = (int) Math.floor(cz - r); z <= (int) Math.ceil(cz + r); z++) {
                    double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
                    if (dx * dx + dz * dz > r * r + 0.2) continue;
                    double ang = Math.atan2(dz, dx) + twist * 1.5 + Math.PI * 4;
                    boolean stripe = ang % (Math.PI / 2) < 0.55;
                    BlockState st = stripe ? band : base;
                    if (y < y0) k.force(x, y, z, st);
                    else k.put(x, y, z, st);
                }
            // shelf caps on the flanks
            if ((y - y0) % 5 == 2 && y < y0 + height - 3) {
                double sa = y * 2.3;
                int px = (int) Math.floor(cx + Math.cos(sa) * (r + 1)), pz = (int) Math.floor(cz + Math.sin(sa) * (r + 1));
                k.blob(px, y, pz, 1.6, 0.5, 1.6, cap(y), false);
            }
        }
        BlockState crown = random.nextBoolean() ? BiomeBlocks.GLOWING_MUSHROOM_CAP.get().defaultBlockState() : cap(random.nextInt(3));
        k.blob((int) Math.floor(cx), y0 + height + 1, (int) Math.floor(cz), 1.8, 1.0, 1.8, crown, false);
        return true;
    }
}
