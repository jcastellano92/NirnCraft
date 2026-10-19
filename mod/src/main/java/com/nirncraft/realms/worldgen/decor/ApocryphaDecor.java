package com.nirncraft.realms.worldgen.decor;

import com.nirncraft.realms.RealmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static com.nirncraft.realms.worldgen.decor.DecorBase.NONE;

/**
 * Apocrypha's decorations. All of them rise from the ink floor as readily as from dry stacks.
 * <ul>
 *     <li>{@code book_stack} (size 1..3): leaning towers of loose pages, black pages and shelves, 6 to 25 tall, laid in
 *     crossing courses like stacked books.</li>
 *     <li>{@code ink_tentacle}: a green-black tentacle 8 to 26 tall, curling out of the ink and tapering to a hooked
 *     tip.</li>
 *     <li>{@code glyph_obelisk}: a tapering obelisk of Apocrypha stone with glowing glyph bands and a capstone.</li>
 * </ul>
 */
final class ApocryphaDecor {
    private ApocryphaDecor() {}

    static void register() {
        RealmDecor.add("book_stack", ApocryphaDecor::bookStack);
        RealmDecor.add("ink_tentacle", ApocryphaDecor::inkTentacle);
        RealmDecor.add("glyph_obelisk", ApocryphaDecor::glyphObelisk);
    }

    static BlockState page() {
        return RealmBlocks.PAGE_BLOCK.get().defaultBlockState();
    }

    static BlockState blackPage() {
        return RealmBlocks.BLACK_PAGE_BLOCK.get().defaultBlockState();
    }

    // ------------------------------------------------------------------ book stacks

    static boolean bookStack(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int s = Mth.clamp(size, 1, 3);
        int n = 1 + random.nextInt(s + 1);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int x0 = origin.getX() + (i == 0 ? 0 : random.nextInt(9) - 4), z0 = origin.getZ() + (i == 0 ? 0 : random.nextInt(9) - 4);
            int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
            if (y0 == NONE) continue;
            int height = Math.min(25, 6 + s * 4 + random.nextInt(8) - (i > 0 ? 4 : 0));
            stack(k, random, x0, y0, z0, Math.max(6, height));
            any = true;
        }
        return any;
    }

    /** One leaning stack: courses of 2x3 "books" turned alternately, drifting further from upright as it rises. */
    static void stack(DecorKit k, RandomSource random, int x0, int y0, int z0, int height) {
        double a = random.nextDouble() * Math.PI * 2, lean = 0.04 + random.nextDouble() * 0.1;
        double drift = 0;
        BlockState course = page();
        int left = 0;
        boolean turned = random.nextBoolean();
        for (int y = y0 - 1; y < y0 + height; y++) {
            int t = y - y0;
            drift += lean * Math.max(0, t) * 0.08;
            if (left-- <= 0) {
                // a new book: its own material, thickness and slight twist
                int pick = random.nextInt(7);
                course = pick < 3 ? page() : pick < 6 ? blackPage() : Blocks.BOOKSHELF.defaultBlockState();
                left = random.nextInt(3);
                turned = !turned;
            }
            int cx = x0 + (int) Math.round(Math.cos(a) * drift), cz = z0 + (int) Math.round(Math.sin(a) * drift);
            int w = turned ? 1 : 0, dpt = turned ? 0 : 1;
            boolean top = y >= y0 + height - 3;
            for (int x = cx - w; x <= cx + 1; x++)
                for (int z = cz - dpt; z <= cz + 1; z++) {
                    if (top && x != cx && z != cz && random.nextInt(3) == 0) continue;
                    if (y < y0) k.force(x, y, z, course);
                    else k.put(x, y, z, course);
                }
        }
    }

    // ------------------------------------------------------------------ tentacles

    static boolean inkTentacle(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE) return false;
        int s = Mth.clamp(size, 1, 3);
        int height = Math.min(26, 8 + s * 5 + random.nextInt(5));
        double r0 = 1.0 + s * 0.35;
        BlockState flesh = RealmBlocks.APOCRYPHA_TENTACLE.get().defaultBlockState();
        double a = random.nextDouble() * Math.PI * 2, ca = Math.cos(a), sa = Math.sin(a);
        double bx = x0 + 0.5, bz = z0 + 0.5;
        double mx = bx + ca * 2.5, mz = bz + sa * 2.5, my = y0 + height * 0.45;
        double tx = bx - ca * 1.5, tz = bz - sa * 1.5, ty = y0 + height * 0.85;
        // the root spreads a little into the floor
        k.blob(x0, y0 - 1, z0, r0 + 1, 1.2, r0 + 1, flesh, false);
        k.curve(bx, y0 - 2, bz, bx - ca, y0 + height * 0.2, bz - sa, mx, my, mz, r0, r0 * 0.8, flesh, false);
        k.curve(mx, my, mz, mx + ca * 1.5, (my + ty) / 2, mz + sa * 1.5, tx, ty, tz, r0 * 0.8, r0 * 0.55, flesh, false);
        // the hooked, curling tip
        double hx = tx + ca * 3, hz = tz + sa * 3, hy = y0 + height;
        double side = random.nextBoolean() ? 1 : -1;
        k.curve(tx, ty, tz, tx - ca * 0.5, hy + 1.5, tz - sa * 0.5, hx, hy, hz, r0 * 0.55, 0.45, flesh, false);
        k.curve(hx, hy, hz, hx + ca * 1.5 - sa * side * 1.5, hy - 1, hz + sa * 1.5 + ca * side * 1.5,
                hx - sa * side * 2, hy - 3, hz + ca * side * 2, 0.45, 0.3, flesh, false);
        // a few black pages caught on it
        for (int i = 0; i < 3; i++) {
            double t = 0.2 + random.nextDouble() * 0.6;
            int x = (int) Math.floor(DecorBase.bez(bx, mx, tx, t)) + random.nextInt(3) - 1;
            int z = (int) Math.floor(DecorBase.bez(bz, mz, tz, t)) + random.nextInt(3) - 1;
            k.put(x, (int) (y0 + height * t), z, blackPage());
        }
        return true;
    }

    // ------------------------------------------------------------------ obelisks

    static boolean glyphObelisk(WorldGenLevel level, BlockPos origin, RandomSource random, int size) {
        DecorKit k = new DecorKit(level, origin);
        int x0 = origin.getX(), z0 = origin.getZ();
        int y0 = DecorBase.floor(k, x0, origin.getY(), z0);
        if (y0 == NONE) return false;
        int s = Mth.clamp(size, 1, 3);
        int height = 8 + s * 2 + random.nextInt(5);
        BlockState stone = RealmBlocks.APOCRYPHA_STONE.get().defaultBlockState();
        BlockState glyph = RealmBlocks.APOCRYPHA_GLYPH_STONE.get().defaultBlockState();
        // a plinth reaching down to the floor
        for (int x = x0 - 2; x <= x0 + 2; x++)
            for (int z = z0 - 2; z <= z0 + 2; z++) {
                k.root(x, y0, z, stone, 8);
                if (Math.abs(x - x0) <= 1 && Math.abs(z - z0) <= 1) k.put(x, y0 + 1, z, stone);
            }
        int band = 2 + random.nextInt(3);
        for (int y = y0 + 2; y <= y0 + height; y++) {
            int r = y < y0 + height * 0.6 ? 1 : 0;
            boolean glow = (y - y0 - band) % 4 == 0;
            for (int x = x0 - r; x <= x0 + r; x++)
                for (int z = z0 - r; z <= z0 + r; z++) k.put(x, y, z, glow ? glyph : stone);
        }
        k.put(x0, y0 + height + 1, z0, glyph);
        return true;
    }
}
