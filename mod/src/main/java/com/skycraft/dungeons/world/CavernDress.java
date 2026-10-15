package com.skycraft.dungeons.world;

import com.skycraft.dungeons.world.cavern.CavernProfile;
import com.skycraft.dungeons.world.cavern.CavernProfile.Feature;
import com.skycraft.dungeons.world.cavern.CavernProfile.Flora;
import com.skycraft.dungeons.world.cavern.CavernProfile.FloraType;
import com.skycraft.dungeons.world.cavern.CavernProfile.FluidKind;
import com.skycraft.dungeons.world.cavern.CavernProfile.Pal;
import com.skycraft.dungeons.world.cavern.CavernShape;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

/**
 * Everything on and around a grand cavern's terrain that its profile decides: flora, glow, signature features
 * (veins, pools, vents, ribs, ruins, statues), the shape's own structures, the set piece, the lore lectern, the
 * inhabitants and the loot. All of it a pure function of position and the piece's seed.
 */
final class CavernDress {
    private CavernDress() {}

    private static BlockState pick(CavernGen g, Pal pal, int x, int y, int z, int salt, BlockState fallback) {
        if (pal.isEmpty()) return fallback;
        BlockState s = pal.pick(g.p.roll(x, y, z, salt, pal.total()));
        return s != null ? s : g.rock(y);
    }

    // ------------------------------------------------------------------ veins and the lake bed

    /** A vein block (sap, ore, glow, lava cracks) at a wall or roof position, or null. */
    static BlockState vein(CavernGen g, int x, int y, int z) {
        List<Feature> fs = g.prof.features;
        for (int i = 0; i < fs.size(); i++) {
            Feature f = fs.get(i);
            if (!f.type().equals("vein")) continue;
            double n = Math.abs(g.p.noise(x, y, z, f.scale(), 1360 + i));
            if (n < f.amount()) return pick(g, f.block(), x, y, z, 1361 + i, Blocks.STONE.defaultBlockState());
        }
        return null;
    }

    static void lakeBed(CavernGen g, int x0, int x1, int z0, int z1, int d, int[] fl) {
        List<Feature> fs = g.prof.features;
        for (int i = 0; i < fs.size(); i++) {
            Feature f = fs.get(i);
            if (!f.type().equals("bedlight") && !f.type().equals("bubbles")) continue;
            int n = Math.max(1, (int) Math.round(1.0 / Math.max(0.005, f.amount())));
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    int fy = fl[(x - x0 + 1) * d + (z - z0 + 1)];
                    if (fy == Integer.MIN_VALUE || fy > g.lakeTop) continue;
                    if (g.p.roll(x, 0, z, 1272 + i, n) != 0) continue;
                    if (f.type().equals("bubbles")) {
                        // soul sand under water raises a column of bubbles
                        if (g.fkind == FluidKind.WATER || g.fkind == FluidKind.ICE) g.p.set(x, fy - 1, z, Blocks.SOUL_SAND.defaultBlockState());
                    } else {
                        g.p.set(x, fy - 1, z, pick(g, f.block(), x, fy, z, 1273, Blocks.SEA_LANTERN.defaultBlockState()));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ dressing

    static void dress(CavernGen g, int x0, int x1, int z0, int z1) {
        Painter p = g.p;
        // stalactites; roots in a margin so spikes crossing a chunk edge agree
        double mul = switch (g.shape) {
            case CATHEDRAL -> 0.25;
            case PILLAR_MAZE, COLLAPSED_CITY, HONEYCOMB -> 0.6;
            case LAVA_TUBE -> 1.3;
            default -> 1.0;
        };
        int limit = (int) Math.round(24 * mul);
        for (int x = x0 - 3; x <= x1 + 3; x++) {
            for (int z = z0 - 3; z <= z1 + 3; z++) {
                if (!g.inside(x, z)) continue;
                long h = p.hash(x, 5, z, 1300);
                int roll = (int) Math.floorMod(h, 400L);
                if (roll >= limit) continue;
                int f = g.floorAt(x, z), c = g.ceilAt(x, z, f);
                boolean wet = g.lake && f <= g.lakeTop;
                int len = 2 + (int) Math.floorMod(h >>> 12, 5L) + (c - f) / 9;
                int clear = wet ? g.lakeTop + 6 : f + 5;
                if (g.lane(x, z, 1)) clear = Math.max(clear, f + 6);
                if (g.chasm && f < g.floor - 3) clear = Math.max(clear, g.floor + 5);
                len = Math.min(len, c - clear);
                if (len < 2) continue;
                cone(g, x, c - 1, z, len);
            }
        }
        flora(g, x0, x1, z0, z1);
        features(g);
        lights(g, x0, x1, z0, z1);
        posts(g);
    }

    /** A hanging spike of {@code len} blocks, 3 wide at the root where long. */
    private static void cone(CavernGen g, int x, int y, int z, int len) {
        for (int i = 0; i < len; i++) {
            double rad = (len - i) * 0.34;
            int r = (int) Math.ceil(rad);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > rad * rad + 0.25) continue;
                    int px = x + dx, pz = z + dz;
                    if (!g.p.ok(px, y - i, pz) || !g.inside(px, pz)) continue;
                    g.p.set(px, y - i, pz, g.spike(px, y - i, pz));
                }
            }
        }
    }

    // ------------------------------------------------------------------ flora

    private static void flora(CavernGen g, int x0, int x1, int z0, int z1) {
        List<Flora> rules = g.prof.flora;
        if (rules.isEmpty()) return;
        Painter p = g.p;
        int[] start = new int[rules.size()];
        int acc = 0;
        for (int i = 0; i < rules.size(); i++) {
            start[i] = acc;
            acc += rules.get(i).density();
        }
        for (int x = x0 - 3; x <= x1 + 3; x++) {
            for (int z = z0 - 3; z <= z1 + 3; z++) {
                if (!g.inside(x, z)) continue;
                long h = p.hash(x, 9, z, 1310);
                int roll = (int) Math.floorMod(h, 1000L);
                if (roll >= acc) continue;
                int ri = 0;
                for (int i = 0; i < rules.size(); i++) if (roll >= start[i]) ri = i;
                Flora fl = rules.get(ri);
                int f = g.floorAt(x, z), c = g.ceilAt(x, z, f);
                int w = g.walk(x, z);
                boolean dry = w != Integer.MIN_VALUE && g.free(x, z);
                int sub = roll - start[ri];
                place(g, fl, x, z, f, c, w, dry, h, sub);
            }
        }
    }

    private static void place(CavernGen g, Flora fl, int x, int z, int f, int c, int w, boolean dry, long h, int sub) {
        Painter p = g.p;
        boolean wet = g.lake && f <= g.lakeTop;
        switch (fl.type()) {
            case PLANT -> {
                if (!dry) return;
                p.setIfOpen(x, w, z, pick(g, fl.block(), x, w, z, 1311, Blocks.MOSS_CARPET.defaultBlockState()));
            }
            case CEIL -> {
                int base = w != Integer.MIN_VALUE ? w : g.lakeTop;
                if (c - 1 <= base + 3) return;
                p.setIfOpen(x, c - 1, z, pick(g, fl.block(), x, c, z, 1312, Blocks.GLOW_LICHEN.defaultBlockState()));
            }
            case HANG -> {
                int base = w != Integer.MIN_VALUE ? w : g.lakeTop;
                int len = fl.minLen() + (int) Math.floorMod(h >>> 10, (long) Math.max(1, fl.maxLen() - fl.minLen() + 1));
                len = Math.min(len, c - 1 - (base + 3));
                for (int i = 0; i < len; i++) {
                    int y = c - 1 - i;
                    BlockState s = i == len - 1 && !fl.tip().isEmpty() ? pick(g, fl.tip(), x, y, z, 1313, null)
                            : pick(g, fl.block(), x, y, z, 1314, Blocks.CAVE_VINES_PLANT.defaultBlockState());
                    p.setIfOpen(x, y, z, s);
                }
            }
            case COLUMN -> {
                if (!dry) return;
                int len = fl.minLen() + (int) Math.floorMod(h >>> 10, (long) Math.max(1, fl.maxLen() - fl.minLen() + 1));
                if (w + len + 2 >= c) return;
                for (int i = 0; i < len; i++) p.setIfOpen(x, w + i, z, pick(g, fl.block(), x, w + i, z, 1315, Blocks.BASALT.defaultBlockState()));
                if (!fl.tip().isEmpty()) p.setIfOpen(x, w + len, z, pick(g, fl.tip(), x, w + len, z, 1316, null));
            }
            case MUSHROOM -> {
                if (!dry) return;
                giantMushroom(g, fl, x, w, z, c, h);
            }
            case SPIRE -> {
                if (!dry) return;
                spire(g, fl, x, w, z, c, h);
            }
            case TREE -> {
                if (!dry) return;
                tree(g, fl, x, w, z, c, h);
            }
            case BED -> {
                if (!wet || g.fkind == FluidKind.LAVA || g.fkind == FluidKind.POOL) return;
                p.set(x, f, z, pick(g, fl.block(), x, f, z, 1317, Blocks.SEAGRASS.defaultBlockState()));
            }
            case FLOAT -> {
                if (!wet || g.fkind != FluidKind.WATER) return;
                if (p.ok(x, g.lakeTop + 1, z)) p.setIfOpen(x, g.lakeTop + 1, z, pick(g, fl.block(), x, 0, z, 1318, Blocks.LILY_PAD.defaultBlockState()));
            }
        }
    }

    private static void giantMushroom(CavernGen g, Flora fl, int x, int w, int z, int c, long h) {
        Painter p = g.p;
        int rad = Math.max(2, fl.radius());
        int stem = 3 + (int) Math.floorMod(h >>> 14, 4L) + (rad - 2) * 2;
        int capY = w + stem;
        if (capY + rad >= c) return;
        BlockState capS = pick(g, fl.cap(), x, capY, z, 1319, Blocks.RED_MUSHROOM_BLOCK.defaultBlockState());
        BlockState stemS = pick(g, fl.block(), x, w, z, 1320, Blocks.MUSHROOM_STEM.defaultBlockState());
        for (int y = w; y < capY; y++) p.set(x, y, z, stemS);
        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                if (dx * dx + dz * dz > rad * rad + 1) continue;
                p.set(x + dx, capY, z + dz, capS);
            }
        }
        for (int dx = -rad + 1; dx <= rad - 1; dx++) {
            for (int dz = -rad + 1; dz <= rad - 1; dz++) {
                if (dx * dx + dz * dz > (rad - 1) * (rad - 1) + 1) continue;
                p.set(x + dx, capY + 1, z + dz, capS);
            }
        }
        BlockState glow = fl.tip().isEmpty() ? Blocks.SHROOMLIGHT.defaultBlockState() : pick(g, fl.tip(), x, capY, z, 1321, Blocks.SHROOMLIGHT.defaultBlockState());
        p.set(x, capY - 1, z, glow);
    }

    /** A crystal (or ice) spire: body, a glowing core, a tip. */
    private static void spire(CavernGen g, Flora fl, int x, int w, int z, int c, long h) {
        Painter p = g.p;
        int len = fl.minLen() + (int) Math.floorMod(h >>> 14, (long) Math.max(1, fl.maxLen() - fl.minLen() + 1));
        if (w + len + 2 >= c) return;
        for (int i = 0; i < len; i++) {
            BlockState s = pick(g, fl.block(), x, w + i, z, 1330, Blocks.AMETHYST_BLOCK.defaultBlockState());
            if (i == len / 2 && !fl.cap().isEmpty() && p.roll(x, 3, z, 1331, 2) == 0) s = pick(g, fl.cap(), x, w + i, z, 1332, s);
            p.set(x, w + i, z, s);
        }
        if (!fl.tip().isEmpty()) p.set(x, w + len, z, pick(g, fl.tip(), x, w + len, z, 1333, Blocks.AMETHYST_CLUSTER.defaultBlockState()));
        // a shoulder at the root
        BlockState body = pick(g, fl.block(), x, w, z, 1334, Blocks.AMETHYST_BLOCK.defaultBlockState());
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (p.roll(x + dir.getStepX(), 4, z + dir.getStepZ(), 1331, 2) == 0) p.setIfOpen(x + dir.getStepX(), w, z + dir.getStepZ(), body);
        }
    }

    /** A tree with a crown: logs (block), leaves (cap), glow fruit (tip). */
    private static void tree(CavernGen g, Flora fl, int x, int w, int z, int c, long h) {
        Painter p = g.p;
        int len = Math.min(fl.minLen() + (int) Math.floorMod(h >>> 14, (long) Math.max(1, fl.maxLen() - fl.minLen() + 1)), c - w - 4);
        if (len < 4) return;
        int rad = Math.max(2, fl.radius());
        for (int i = 0; i < len; i++) p.set(x, w + i, z, pick(g, fl.block(), x, w + i, z, 1335, Blocks.OAK_LOG.defaultBlockState()));
        BlockState leaf = pick(g, fl.cap(), x, w + len, z, 1336, Blocks.OAK_LEAVES.defaultBlockState());
        for (int dy = -1; dy <= 1; dy++) {
            int r = dy == 0 ? rad : rad - 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    p.setIfOpen(x + dx, w + len + dy, z + dz, leaf);
                }
            }
        }
        if (!fl.tip().isEmpty()) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                p.setIfOpen(x + dir.getStepX() * rad, w + len - 1, z + dir.getStepZ() * rad, pick(g, fl.tip(), x, w, z, 1337, leaf));
            }
        }
    }

    // ------------------------------------------------------------------ light

    private static void lights(CavernGen g, int x0, int x1, int z0, int z1) {
        CavernProfile.Lights lg = g.prof.lights;
        if (lg.roof().isEmpty()) return;
        Painter p = g.p;
        int n = Math.max(2, lg.density());
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                if (!g.inside(x, z) || p.roll(x, 0, z, 1322, n) != 0) continue;
                int f = g.floorAt(x, z), c = g.ceilAt(x, z, f);
                BlockState s = pick(g, lg.roof(), x, c, z, 1323, Blocks.GLOWSTONE.defaultBlockState());
                if (p.ok(x, c, z)) p.set(x, c, z, s);
                if (lg.cluster() > 0) {
                    // a hanging cluster of the same, low enough to light the floor
                    int len = lg.cluster() + (int) Math.floorMod(p.hash(x, 2, z, 1324), 3L);
                    int fb = g.chasm && f < g.floor - 3 ? g.floor : f;
                    for (int i = 1; i <= len; i++) if (c - i > fb + 6) p.set(x, c - i, z, s);
                }
            }
        }
    }

    /** Posts of light along the bridges, the platform and the lanes: dark caverns would not be navigable. */
    private static void posts(CavernGen g) {
        Painter p = g.p;
        int cx = g.cx, cz = g.cz;
        double ex = g.ex, ez = g.ez;
        int reachX = (int) (0.62 * ex), reachZ = (int) (0.62 * ez);
        int[][] spots = {
                {cx + 3, cz + 3}, {cx - 3, cz - 3}, {cx + 3, cz - 3}, {cx - 3, cz + 3},
                {cx + 3, cz + reachZ}, {cx - 3, cz + reachZ}, {cx + 3, cz - reachZ}, {cx - 3, cz - reachZ},
                {cx + reachX, cz + 3}, {cx + reachX, cz - 3}, {cx - reachX, cz + 3}, {cx - reachX, cz - 3},
                {cx + 3, cz + (int) (0.82 * ez)}, {cx - 3, cz - (int) (0.82 * ez)},
                {cx + (int) (0.82 * ex), cz - 3}, {cx - (int) (0.82 * ex), cz + 3}};
        int[][] all = new int[spots.length + 20][];
        System.arraycopy(spots, 0, all, 0, spots.length);
        for (int k = 0; k < 12; k++) {
            double ang = Math.toRadians(30 * k + 15);
            all[spots.length + k] = new int[]{cx + (int) Math.round(Math.cos(ang) * 0.77 * ex), cz + (int) Math.round(Math.sin(ang) * 0.77 * ez)};
        }
        for (int k = 0; k < 8; k++) {
            double ang = Math.toRadians(45 * k + 22);
            all[spots.length + 12 + k] = new int[]{cx + (int) Math.round(Math.cos(ang) * 0.5 * ex), cz + (int) Math.round(Math.sin(ang) * 0.5 * ez)};
        }
        // plus a post at every causeway's middle
        int extra = g.causeways.length;
        int[][] all2 = java.util.Arrays.copyOf(all, all.length + extra);
        for (int i = 0; i < extra; i++) {
            int[] c = g.causeways[i];
            all2[all.length + i] = new int[]{(c[0] + c[1]) / 2, (c[2] + c[3]) / 2};
        }
        for (int[] s : all2) {
            int x = s[0], z = s[1];
            if (!g.inside(x, z) || g.lane(x, z, 2) || g.blocked(x, z)) continue;
            int w = g.walk(x, z);
            if (w == Integer.MIN_VALUE || g.pillarAt(x, z, 0)) continue;
            if (g.chasm && g.floorAt(x, z) < g.floor - 3 && !g.bridge(x, z) && !g.onPlatform(x, z) && !g.onIsland(x, z, 0)) continue;
            p.set(x, w, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
            p.set(x, w + 1, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
            p.set(x, w + 2, z, g.lantern());
        }
    }

    // ------------------------------------------------------------------ signature features

    private static void features(CavernGen g) {
        List<Feature> fs = g.prof.features;
        for (int i = 0; i < fs.size(); i++) {
            Feature f = fs.get(i);
            switch (f.type()) {
                case "pool" -> pools(g, f, i);
                case "smoke" -> smoke(g, f, i);
                case "ruin" -> ruins(g, f, i);
                case "statue" -> statues(g, f, i);
                case "boulders" -> boulders(g, f, i);
                case "ribs" -> ribsOf(g, f, 0.7);
                default -> {
                }
            }
        }
    }

    /** A deterministic spot of the cavern: radius fraction in [a, b], any angle; null when not free in a square of radius r. */
    static int[] flatSpot(CavernGen g, int salt, double a, double b, int r, double theta, double spread) {
        for (int i = 0; i < 30; i++) {
            double ang = spread >= 6 ? Math.toRadians(g.p.roll(g.cx, salt, i, 1400 + i, 360))
                    : theta + (g.p.roll(g.cx, salt, i, 1400, 100) - 50) / 100.0 * spread;
            double rad = a + (b - a) * g.p.roll(g.cz, salt, i, 1401, 100) / 100.0;
            int x = g.cx + (int) Math.round(Math.cos(ang) * rad * g.ex), z = g.cz + (int) Math.round(Math.sin(ang) * rad * g.ez);
            if (!g.free(x, z)) continue;
            int f0 = g.floorAt(x, z);
            boolean ok = g.walkDist(x, z) >= r + 1;
            for (int dx = -r; dx <= r && ok; dx++) {
                for (int dz = -r; dz <= r && ok; dz++) {
                    if (!g.free(x + dx, z + dz) || g.floorAt(x + dx, z + dz) != f0) ok = false;
                }
            }
            if (ok) return new int[]{x, f0, z};
        }
        return null;
    }

    private static void pools(CavernGen g, Feature f, int fi) {
        int n = f.minCount() + g.p.roll(g.cx, 60 + fi, g.cz, 1370, Math.max(1, f.maxCount() - f.minCount() + 1));
        for (int k = 0; k < n; k++) {
            int r = f.minSize() + g.p.roll(g.cx, k, g.cz, 1371 + fi, Math.max(1, f.maxSize() - f.minSize() + 1));
            int[] s = flatSpot(g, 100 + fi * 10 + k, 0.18, 0.78, r + 1, 0, 7);
            if (s == null) continue;
            BlockState fluid = switch (f.fluid()) {
                case "lava" -> Blocks.LAVA.defaultBlockState();
                case "powder_snow" -> Blocks.POWDER_SNOW.defaultBlockState();
                case "honey" -> Blocks.HONEY_BLOCK.defaultBlockState();
                case "mud" -> Blocks.MUD.defaultBlockState();
                default -> Blocks.WATER.defaultBlockState();
            };
            boolean liquid = !f.fluid().equals("honey") && !f.fluid().equals("mud");
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    int x = s[0] + dx, z = s[2] + dz, y = s[1] - 1;
                    g.p.set(x, y, z, fluid);
                    if (liquid && !f.accent().isEmpty() && (dx * 3 + dz * 5 + k) % 4 == 0) {
                        g.p.set(x, y - 1, z, pick(g, f.accent(), x, y, z, 1372, Blocks.SEA_LANTERN.defaultBlockState()));
                    }
                    if (!f.block().isEmpty() && dx * dx + dz * dz >= (r - 0.5) * (r - 0.5)
                            && g.p.roll(x, 0, z, 1373, 3) == 0) g.p.set(x, s[1], z, pick(g, f.block(), x, s[1], z, 1374, Blocks.STONE.defaultBlockState()));
                }
            }
            g.p.feature("pool:" + f.fluid());
        }
    }

    private static void smoke(CavernGen g, Feature f, int fi) {
        int n = f.minCount() + g.p.roll(g.cx, 61 + fi, g.cz, 1375, Math.max(1, f.maxCount() - f.minCount() + 1));
        for (int k = 0; k < n; k++) {
            int[] s = flatSpot(g, 140 + fi * 10 + k, 0.2, 0.8, 2, 0, 7);
            if (s == null) continue;
            // a sunken vent ringed by its own rock: smoke rises from a campfire in a pit
            int x = s[0], y = s[1], z = s[2];
            g.p.set(x, y - 1, z, Blocks.MAGMA_BLOCK.defaultBlockState());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                g.p.set(x + d.getStepX(), y, z + d.getStepZ(), pick(g, f.block(), x, y, z, 1376, Blocks.BASALT.defaultBlockState()));
            }
            g.p.set(x, y, z, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, true)
                    .setValue(net.minecraft.world.level.block.CampfireBlock.SIGNAL_FIRE, true));
            g.p.feature("vent:smoke");
        }
    }

    private static void ruins(CavernGen g, Feature f, int fi) {
        Pal pal = !f.block().isEmpty() ? f.block() : !g.prof.ruin.isEmpty() ? g.prof.ruin : g.prof.wall;
        int n = f.minCount() + g.p.roll(g.cx, 62 + fi, g.cz, 1377, Math.max(1, f.maxCount() - f.minCount() + 1));
        for (int k = 0; k < n; k++) {
            int[] s = flatSpot(g, 180 + fi * 10 + k, 0.2, 0.82, 1, 0, 7);
            if (s == null) continue;
            boolean alongX = g.p.roll(s[0], k, s[2], 1378, 2) == 0;
            int len = 3 + g.p.roll(s[0], k, s[2], 1379, 4);
            int hgt = 1 + g.p.roll(s[0], k, s[2], 1380, 3);
            for (int i = 0; i < len; i++) {
                int x = s[0] + (alongX ? i : 0), z = s[2] + (alongX ? 0 : i);
                if (!g.free(x, z) || g.floorAt(x, z) != s[1]) break;
                int hh = Math.max(1, hgt - (g.p.roll(x, k, z, 1381, 3) == 0 ? 1 : 0));
                for (int y = 0; y < hh; y++) g.p.set(x, s[1] + y, z, pick(g, pal, x, s[1] + y, z, 1382, Blocks.STONE_BRICKS.defaultBlockState()));
            }
            g.p.feature("ruin:wall");
        }
    }

    private static void boulders(CavernGen g, Feature f, int fi) {
        int n = f.minCount() + g.p.roll(g.cx, 63 + fi, g.cz, 1383, Math.max(1, f.maxCount() - f.minCount() + 1));
        for (int k = 0; k < n; k++) {
            int r = Math.max(1, f.minSize() + g.p.roll(g.cx, k, g.cz, 1384 + fi, Math.max(1, f.maxSize() - f.minSize() + 1)) - 1);
            int[] s = flatSpot(g, 220 + fi * 10 + k, 0.2, 0.8, r, 0, 7);
            if (s == null) continue;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = 0; dy <= r; dy++) {
                        if (dx * dx + dz * dz + dy * dy * 1.6 > r * r + 0.6) continue;
                        int x = s[0] + dx, z = s[2] + dz;
                        g.p.setIfOpen(x, s[1] + dy, z, pick(g, f.block(), x, s[1] + dy, z, 1385, Blocks.COBBLESTONE.defaultBlockState()));
                    }
                }
            }
        }
    }

    private static void statues(CavernGen g, Feature f, int fi) {
        int n = f.minCount() + g.p.roll(g.cx, 64 + fi, g.cz, 1386, Math.max(1, f.maxCount() - f.minCount() + 1));
        Pal pal = !f.block().isEmpty() ? f.block() : !g.prof.ruin.isEmpty() ? g.prof.ruin : g.prof.platform;
        for (int k = 0; k < n; k++) {
            int[] s = flatSpot(g, 260 + fi * 10 + k, 0.3, 0.78, 2, 0, 7);
            if (s == null) continue;
            statue(g, s[0], s[1], s[2], pal, f.accent(), k);
        }
    }

    /** A weathered standing figure on a plinth: legs, torso, arms, head, helm; the upper blocks eaten by time. */
    static void statue(CavernGen g, int x, int w, int z, Pal pal, Pal accent, int salt) {
        Painter p = g.p;
        int c = g.ceilAt(x, z, w);
        int hgt = Math.min(10, c - w - 3);
        if (hgt < 6) return;
        boolean rot = p.roll(x, salt, z, 1390, 2) == 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) put(g, pal, x + dx, w, z + dz, 0);
        }
        int ax = rot ? 1 : 0, az = rot ? 0 : 1;
        // legs
        for (int y = 1; y <= 3; y++) {
            put(g, pal, x - az, w + y, z - ax, y);
            put(g, pal, x + az, w + y, z + ax, y);
        }
        // torso, a cape of the same rock down the back
        for (int y = 4; y <= 6; y++) {
            for (int d = -1; d <= 1; d++) put(g, pal, x + az * d, w + y, z + ax * d, y);
        }
        // arms: one lowered, one raised
        for (int y = 3; y <= 5; y++) put(g, pal, x - az * 2, w + y, z - ax * 2, y);
        for (int y = 5; y <= 7; y++) put(g, pal, x + az * 2, w + y, z + ax * 2, y);
        // head and helm
        put(g, pal, x, w + 7, z, 7);
        put(g, pal, x, w + 8, z, 8);
        if (hgt >= 9) put(g, pal, x, w + 9, z, 9);
        if (!accent.isEmpty()) p.set(x + ax, w + 8, z + az, pick(g, accent, x, w, z, 1391, Blocks.SEA_LANTERN.defaultBlockState()));
        // the fallen part of it
        p.setIfOpen(x + 3, w, z + 2, pick(g, pal, x, w, z, 1392, Blocks.STONE_BRICKS.defaultBlockState()));
        p.feature("statue:ancient");
    }

    private static void put(CavernGen g, Pal pal, int x, int y, int z, int decayY) {
        if (decayY >= 6 && g.p.roll(x, y, z, 1393, 7) == 0) return;
        g.p.set(x, y, z, pick(g, pal, x, y, z, 1394, Blocks.STONE_BRICKS.defaultBlockState()));
    }

    // ------------------------------------------------------------------ shape structures

    /** Waterfall: a spring in the wall high above the floor, falling to the ledges and the lake. */
    static void waterfall(CavernGen g) {
        Painter p = g.p;
        double cs = Math.cos(g.wfTheta), sn = Math.sin(g.wfTheta);
        int sx0 = g.cx + (int) Math.round(cs * 0.6 * g.ex), sz0 = g.cz + (int) Math.round(sn * 0.6 * g.ez);
        int stepX = Math.abs(cs) >= Math.abs(sn) ? (cs >= 0 ? 1 : -1) : 0;
        int stepZ = stepX == 0 ? (sn >= 0 ? 1 : -1) : 0;
        int xi = sx0, zi = sz0, xe = g.cx, ze = g.cz;
        for (int k = 0; k < 40; k++) {
            int x = sx0 + stepX * k, z = sz0 + stepZ * k;
            if (!g.inside(x, z)) {
                xe = x;
                ze = z;
                break;
            }
            xi = x;
            zi = z;
        }
        if (xe == g.cx && ze == g.cz) return;
        int fi = g.floorAt(xi, zi);
        int y0 = Math.min(g.ceilAt(xi, zi, fi) - 3, fi + 12);
        if (y0 > fi + 5 && p.ok(xe, y0, ze)) {
            p.set(xe, y0, ze, Blocks.WATER.defaultBlockState());
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                int sx = xe + dir.getStepX(), sz = ze + dir.getStepZ();
                if (sx == xi && sz == zi) continue;
                if (p.ok(sx, y0, sz) && Painter.open(p.get(sx, y0, sz))) p.set(sx, y0, sz, g.rock(y0));
            }
            if (p.ok(xe, y0 + 1, ze)) p.set(xe, y0 + 1, ze, g.rock(y0 + 1));
            if (p.ok(xe, y0 - 1, ze)) p.set(xe, y0 - 1, ze, g.rock(y0 - 1));
            p.scheduleFluid(xe, y0, ze);
            p.feature("setpiece:grand_waterfall");
        }
    }

    // ---- the shaft's spiral ramp

    private static final double RAMP_R = 0.47, RAMP_W = 1.7;

    /** Walking level of the ramp at a column, or MIN when the column is not on it. */
    static int rampLevel(CavernGen g, int x, int z) {
        double u = (x - g.cx) / g.ex, v = (z - g.cz) / g.ez;
        double rho = Math.sqrt(u * u + v * v);
        double widthN = RAMP_W / Math.min(g.ex, g.ez);
        if (Math.abs(rho - RAMP_R) > widthN) return Integer.MIN_VALUE;
        int landing = g.lake ? g.lakeTop + 2 : g.floor - g.deepDepth + 3;
        int drop = Math.max(0, g.floor - landing);
        double theta0 = Math.toRadians(45 + 90 * ((g.p.hash(g.cx, 1, g.cz, 1100) >>> 12) & 3) + 90);
        double phi = Math.atan2(v, u);
        double t = (phi - theta0) / (2 * Math.PI);
        t -= Math.floor(t);
        double tt = Math.min(1.0, t / 0.9);
        return g.floor - (int) Math.round(tt * drop);
    }

    static void spiral(CavernGen g) {
        Painter p = g.p;
        int x0 = p.minX(), x1 = p.maxX(), z0 = p.minZ(), z1 = p.maxZ();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!g.inside(x, z) || g.lane(x, z, 2) || g.onPlatform(x, z) || g.cross(x, z)) continue;
                int wl = rampLevel(g, x, z);
                if (wl == Integer.MIN_VALUE) continue;
                int f = g.floorAt(x, z);
                // dig into the ring where the ramp is below it, build up where it is above the well
                for (int y = wl; y < f; y++) p.air(x, y, z);
                for (int y = wl; y <= wl + 4; y++) p.air(x, y, z);
                int bottom = Math.max(f - 1, g.floor - g.deepDepth - 1);
                for (int y = wl - 1; y >= bottom && y >= p.minY(); y--) {
                    p.set(x, y, z, y == wl - 1 ? g.floorBlock(x, y, z) : g.rock(y));
                }
            }
        }
        p.feature("setpiece:spiral_ramp");
    }

    /** The landing of the ramp: {x, y, z} of its last blocks, or null. */
    static int[] landing(CavernGen g) {
        double theta0 = Math.toRadians(45 + 90 * ((g.p.hash(g.cx, 1, g.cz, 1100) >>> 12) & 3) + 90);
        double phi = theta0 + 2 * Math.PI * 0.93;
        int x = g.cx + (int) Math.round(Math.cos(phi) * RAMP_R * g.ex), z = g.cz + (int) Math.round(Math.sin(phi) * RAMP_R * g.ez);
        int wl = rampLevel(g, x, z);
        if (wl == Integer.MIN_VALUE) return null;
        return new int[]{x, wl, z};
    }

    // ---- city and hive walls

    static void cityWalls(CavernGen g, int x0, int x1, int z0, int z1) {
        Pal pal = !g.prof.ruin.isEmpty() ? g.prof.ruin : g.prof.wall;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!g.inside(x, z) || g.lane(x, z, 2) || g.cross(x, z) || g.onPlatform(x, z)) continue;
                int hgt = g.cityWall(x, z);
                if (hgt <= 0) continue;
                int f = g.floorAt(x, z);
                if (g.lake && f <= g.lakeTop) continue;
                int c = g.ceilAt(x, z, f);
                for (int y = 0; y < hgt && f + y < c - 1; y++) {
                    g.p.set(x, f + y, z, pick(g, pal, x, f + y, z, 1395, Blocks.STONE_BRICKS.defaultBlockState()));
                }
            }
        }
        g.p.feature("setpiece:sunken_streets");
    }

    static void hexWalls(CavernGen g, int x0, int x1, int z0, int z1) {
        Pal pal = !g.prof.ruin.isEmpty() ? g.prof.ruin : g.prof.wall;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!g.inside(x, z) || g.lane(x, z, 2) || g.cross(x, z) || g.onPlatform(x, z) || g.pillarAt(x, z, 0)) continue;
                int hgt = g.hexWall(x, z);
                if (hgt <= 0) continue;
                int f = g.floorAt(x, z);
                if (g.lake && f <= g.lakeTop) continue;
                int c = g.ceilAt(x, z, f);
                for (int y = 0; y < hgt && f + y < c - 1; y++) {
                    g.p.set(x, f + y, z, pick(g, pal, x, f + y, z, 1396, Blocks.HONEYCOMB_BLOCK.defaultBlockState()));
                }
            }
        }
        g.p.feature("setpiece:hive_cells");
    }

    // ---- ribs (cathedral vaults, the bones of a great beast) and the skull

    static void ribs(CavernGen g) {
        double apex = g.shape == CavernShape.BONE_CAVERN ? 0.50 : 0.80;
        ribsOf(g, null, apex);
    }

    /** Arches across the minor axis at fixed spacing; {@code f} may override block, count and thickness. */
    private static void ribsOf(CavernGen g, Feature f, double apexFrac) {
        Painter p = g.p;
        boolean bone = g.shape == CavernShape.BONE_CAVERN;
        Pal pal = f != null && !f.block().isEmpty() ? f.block() : bone && g.prof.pillar.isEmpty() ? Pal.EMPTY : g.prof.pillar;
        BlockState def = bone ? Blocks.BONE_BLOCK.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
        double eLong = g.longX ? g.ex : g.ez, eMinor = g.longX ? g.ez : g.ex;
        int pitch = bone ? 5 : 6;
        if (f != null) pitch = Math.max(4, 12 - Math.max(1, f.maxCount()));
        int endB = (int) Math.round(0.76 * eMinor);
        for (int a = -(int) (0.74 * eLong); a <= (int) (0.74 * eLong); a++) {
            if (Math.floorMod(a - 1, pitch) != 0) continue;
            if (Math.abs(a) <= 5) continue;
            // the cross's arm and the lanes pass beneath: arches stand wide of them (their crowns are far above heads)
            double apexY = g.floor + apexFrac * g.height;
            int ax = g.cx + (g.longX ? a : 0), az = g.cz + (g.longX ? 0 : a);
            for (int s = -endB; s <= endB; s++) {
                int x = g.longX ? ax : g.cx + s, z = g.longX ? g.cz + s : az;
                if (x < p.minX() - 2 || x > p.maxX() + 2 || z < p.minZ() - 2 || z > p.maxZ() + 2) continue;
                double t = (double) s / endB;
                int fEnd = g.floorAt(g.longX ? ax : g.cx + (s < 0 ? -endB : endB), g.longX ? g.cz + (s < 0 ? -endB : endB) : az);
                double y = fEnd + (apexY - fEnd) * Math.sqrt(Math.max(0, 1 - t * t));
                int yy = (int) Math.round(y);
                for (int dy = 0; dy <= 1; dy++) {
                    int px = x, pz = z, py = yy + dy;
                    if (!g.inside(px, pz)) continue;
                    int fl = g.floorAt(px, pz);
                    if (py < fl + 1 || py >= g.ceilAt(px, pz, fl) - 0) continue;
                    // never across a walkway at head height
                    if (py < g.floor + 5 && (g.lane(px, pz, 2) || g.cross(px, pz))) continue;
                    p.setIfOpen(px, py, pz, pick(g, pal, px, py, pz, 1397, def));
                }
            }
        }
        // the spine joining the crowns, for bones
        if (bone) {
            double apexY = g.floor + apexFrac * g.height;
            for (int a = -(int) (0.72 * eLong); a <= (int) (0.72 * eLong); a++) {
                int x = g.cx + (g.longX ? a : 0), z = g.cz + (g.longX ? 0 : a);
                int py = (int) Math.round(apexY) + 1;
                if (!g.inside(x, z) || py >= g.ceilAt(x, z, g.floorAt(x, z)) - 1) continue;
                if (Math.floorMod(a, 2) == 0) p.setIfOpen(x, py, z, Blocks.BONE_BLOCK.defaultBlockState());
            }
        }
        p.feature("setpiece:ribs");
    }

    /** A vast skull at one end of the long axis. */
    static void skull(CavernGen g) {
        Painter p = g.p;
        double eLong = g.longX ? g.ex : g.ez;
        int dirSign = ((g.p.hash(g.cx, 6, g.cz, 1100) >>> 8) & 1) == 0 ? 1 : -1;
        int a = (int) Math.round(dirSign * 0.64 * eLong);
        int sx = g.cx + (g.longX ? a : 0), sz = g.cz + (g.longX ? 0 : a);
        int base = Math.max(g.floorAt(sx, sz), g.floor);
        if (!g.inside(sx, sz)) return;
        // keep clear of the arms and lanes: the skull sits to the side of the long axis
        int side = ((g.p.hash(g.cx, 7, g.cz, 1100) >>> 9) & 1) == 0 ? 1 : -1;
        int off = (int) Math.round(side * 0.36 * (g.longX ? g.ez : g.ex));
        int cxs = sx + (g.longX ? 0 : off), czs = sz + (g.longX ? off : 0);
        if (!g.inside(cxs, czs)) return;
        base = g.floorAt(cxs, czs);
        int c = g.ceilAt(cxs, czs, base);
        double rh = Math.min(3.0, (c - base - 2) / 2.2);
        if (rh < 2.0) return;
        double ra = 4.2, rb = 3.4;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 0; dy <= (int) (rh * 2); dy++) {
                    double along = g.longX ? dx : dz, lat = g.longX ? dz : dx;
                    double q = (along / ra) * (along / ra) + (lat / rb) * (lat / rb) + ((dy - rh) / rh) * ((dy - rh) / rh);
                    if (q > 1.0 || q < 0.52) continue;
                    int x = cxs + dx, z = czs + dz, y = base + dy;
                    if (!g.free(x, z) && !(g.inside(x, z) && !g.lane(x, z, 2) && !g.cross(x, z))) continue;
                    // eye sockets and the nose: holes on the face toward the cavern's middle
                    boolean face = (g.longX ? dx * -dirSign : dz * -dirSign) >= 2;
                    boolean eye = face && Math.abs(lat) >= 1.5 && Math.abs(lat) <= 2.5 && dy >= rh * 0.9 && dy <= rh * 1.4;
                    boolean nose = face && Math.abs(lat) <= 0.5 && dy >= rh * 0.5 && dy <= rh * 0.9;
                    if (eye || nose) {
                        p.air(x, y, z);
                        continue;
                    }
                    p.set(x, y, z, Blocks.BONE_BLOCK.defaultBlockState());
                }
            }
        }
        p.feature("setpiece:giant_skull");
    }

    // ---- the karst's shaft of light

    static void lightShaft(CavernGen g) {
        Painter p = g.p;
        double ang = Math.toRadians(45 + 90 * ((g.p.hash(g.cx, 1, g.cz, 1100) >>> 12) & 3) + 90 + 180);
        int sx = g.cx + (int) Math.round(Math.cos(ang) * 0.36 * g.ex), sz = g.cz + (int) Math.round(Math.sin(ang) * 0.36 * g.ez);
        int f = g.floorAt(sx, sz);
        int c = g.ceilAt(sx, sz, f);
        int top = g.top;
        if (top - c < 3) return;
        for (int y = c; y <= top - 2; y++) {
            double t = (double) (y - c) / Math.max(1, top - 2 - c);
            double rad = 3.4 - 1.2 * t;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx * dx + dz * dz > rad * rad) continue;
                    p.air(sx + dx, y, sz + dz);
                }
            }
        }
        // the sun: a disc of light at the top of the shaft
        BlockState sun = !g.prof.lights.roof().isEmpty() ? g.prof.lights.roof().first() : Blocks.SEA_LANTERN.defaultBlockState();
        if (sun == null) sun = Blocks.SEA_LANTERN.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 5) continue;
                p.set(sx + dx, top - 1, sz + dz, sun);
            }
        }
        p.feature("setpiece:light_shaft");
    }

    // ------------------------------------------------------------------ people and loot

    private static int[] spotNear(CavernGen g, double theta, double a, double b, int salt) {
        for (int i = 0; i < 24; i++) {
            double ang = theta + (g.p.roll(g.cx, salt, i, 1400, 100) - 50) / 100.0 * 1.1;
            double rad = a + (b - a) * g.p.roll(g.cz, salt, i, 1401, 100) / 100.0;
            int x = g.cx + (int) Math.round(Math.cos(ang) * rad * g.ex), z = g.cz + (int) Math.round(Math.sin(ang) * rad * g.ez);
            if (!g.free(x, z)) continue;
            return new int[]{x, g.walk(x, z), z};
        }
        return null;
    }

    private static int[] spotAny(CavernGen g, int salt) {
        double theta = Math.toRadians(g.p.roll(g.cx, salt, g.cz, 1402, 360));
        return spotNear(g, theta, 0.55, 0.85, salt);
    }

    private static boolean inhabited(CavernGen g) {
        return switch (g.room.theme()) {
            case CAVE -> CaveRoster.inhabited(CaveRoster.of(g.room.piece.flavor));
            case MINE -> g.room.piece.flavor % 2 == 1;
            case FORT, VAMPIRE_LAIR, NECROMANCER_LAIR, HAGRAVEN_LAIR -> true;
            default -> false;
        };
    }

    private static String foe(CavernGen g) {
        if (g.p.place != null && !g.p.place.isEmpty()) return Beats.foe(g.p, g.room);
        for (String id : g.prof.foes) {
            if (Painter.entityExists(id)) return id;
        }
        return Beats.foe(g.p, g.room);
    }

    private static void spawn(CavernGen g, String id, int[] s, boolean asleep) {
        float yaw = g.p.roll(s[0], s[1], s[2], 1440, 360);
        if (asleep) g.p.spawnDormant(id, s[0], s[1], s[2], yaw);
        else g.p.spawn(id, s[0], s[1], s[2], yaw, null);
    }

    private static void carpet(CavernGen g, int x, int z) {
        if (g.free(x, z)) g.p.set(x, g.walk(x, z), z, Blocks.BROWN_CARPET.defaultBlockState());
    }

    private static void put(CavernGen g, int x, int z, BlockState s, ResourceLocation loot) {
        if (!g.free(x, z)) return;
        g.p.container(x, g.walk(x, z), z, s, loot);
    }

    static void populate(CavernGen g) {
        Painter p = g.p;
        Room room = g.room;
        CavernProfile prof = g.prof;
        String foe = foe(g);
        boolean folk = inhabited(g);
        int tier = room.tier();
        boolean cold = g.fkind == FluidKind.ICE || prof.lightColor.equals("blue") || prof.lightColor.equals("violet")
                || prof.lightColor.equals("cyan");
        // the camp (or the den) on the free sector's ledge
        int[] camp = spotNear(g, g.campTheta, 0.62, 0.8, 1);
        if (camp != null) {
            int x = camp[0], y = camp[1], z = camp[2];
            if (folk) {
                p.set(x, y, z, Deco.campfire(cold, true));
                put(g, x + 2, z, Deco.barrel(Direction.UP), prof.lootMinor != null ? prof.lootMinor : Deco.LOOT_MINOR);
                carpet(g, x - 2, z);
                carpet(g, x, z + 2);
                carpet(g, x, z - 2);
                p.feature("camp:grand");
            } else {
                for (int i = 0; i < 5; i++) {
                    int dx = p.roll(x, i, z, 1410, 5) - 2, dz = p.roll(x, i, z, 1411, 5) - 2;
                    if (!g.free(x + dx, z + dz)) continue;
                    int wy = g.walk(x + dx, z + dz);
                    BlockState s = i % 3 == 0 ? Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X) : i % 3 == 1
                            ? Deco.skull(Blocks.SKELETON_SKULL, i * 3) : Blocks.COBWEB.defaultBlockState();
                    p.set(x + dx, wy, z + dz, s);
                }
                p.feature("camp:den");
            }
            int n = 2 + tier / 3;
            for (int i = 0; i < n; i++) {
                int[] s = spotNear(g, g.campTheta, 0.55, 0.85, 20 + i);
                if (s == null) continue;
                spawn(g, foe, s, !folk || i % 2 == 1);
            }
        }
        setPiece(g);
        // the platform: a chest, the lore on a lectern and a guardian
        ResourceLocation platLoot = prof.lootPlatform != null ? prof.lootPlatform
                : room.piece.theme == Theme.MINE ? Deco.LOOT_MINE : Deco.LOOT_COMMON;
        p.container(g.cx, g.floor, g.cz, Deco.chest(Direction.NORTH), platLoot);
        p.set(g.cx + 2, g.floor, g.cz + 2, Deco.candle(Blocks.CANDLE, 3, true));
        p.set(g.cx - 2, g.floor, g.cz - 2, Deco.candle(Blocks.CANDLE, 2, true));
        lore(g, g.cx - 2, g.floor, g.cz + 2, Direction.EAST);
        if (tier >= 3) {
            int[] s = spotAny(g, 30);
            if (s != null) p.spawnElite(foe, s[0], s[1], s[2], p.roll(s[0], 1, s[2], 1420, 360), prof.eliteName);
        }
        // the galleries: the cache, and a minor chest
        for (int k = 0; k < g.humpCount; k++) {
            int[] s = spotNear(g, g.humpTheta[k], 0.74, 0.86, 40 + k);
            if (s == null) continue;
            Direction face = BarrowRooms.towardCenter(room, s[0], s[2]);
            ResourceLocation loot = k == 0 ? (prof.lootRelic != null ? prof.lootRelic : Story.relicTable(room.theme(), 1))
                    : (prof.lootMinor != null ? prof.lootMinor : Deco.LOOT_MINOR);
            p.container(s[0], s[1], s[2], Deco.chest(face), loot);
            p.set(s[0] + face.getClockWise().getStepX(), s[1], s[2] + face.getClockWise().getStepZ(), g.lantern());
            if (k == 0) p.feature("reward:cache");
        }
        // the shaft's landing, and the islands' treasure
        if (g.shape == CavernShape.SHAFT) {
            int[] l = landing(g);
            if (l != null) {
                p.container(l[0], l[1], l[2], Deco.chest(Direction.NORTH), prof.lootRelic != null ? prof.lootRelic : Story.relicTable(room.theme(), 1));
                p.set(l[0] + 1, l[1], l[2], g.lantern());
                p.feature("reward:landing");
            }
        }
        for (int[] q : g.islands) {
            if (p.roll(q[0], 5, q[1], 1450, 2) == 0) {
                p.container(q[0], g.floor, q[1], Deco.chest(Direction.NORTH), prof.lootMinor != null ? prof.lootMinor : Deco.LOOT_MINOR);
            }
        }
        // sleepers among the spires and a patrol on the ring
        for (int i = 0; i < 3 + tier / 4; i++) {
            int[] s = spotAny(g, 60 + i);
            if (s != null) p.spawnDormant(foe, s[0], s[1], s[2], p.roll(s[0], i, s[2], 1430, 360));
        }
        for (int i = 0; i < 2; i++) {
            int[] s = spotNear(g, Math.toRadians(p.roll(g.cx, 90 + i, g.cz, 1431, 360)), 0.7, 0.9, 80 + i);
            if (s != null) spawn(g, foe, s, false);
        }
        ambient(g);
        String id = prof.id.toLowerCase(Locale.ROOT);
        p.feature("cavern:" + id);
        p.feature("shape:" + g.shape.id);
        if (!prof.particle.isEmpty()) p.feature("ambient:" + prof.particle);
        p.feature("setpiece:grand_" + id + (g.chasm ? "_chasm" : "_bowl"));
        p.feature("beat:vista");
    }

    /** Bats and sea life: the cavern's own small creatures. */
    private static void ambient(CavernGen g) {
        Painter p = g.p;
        int salt = 0;
        for (CavernProfile.Ambient a : g.prof.ambient) {
            int n = a.min() + p.roll(g.cx, 70 + salt, g.cz, 1460, Math.max(1, a.max() - a.min() + 1));
            salt++;
            for (int i = 0; i < n; i++) {
                double ang = Math.toRadians(p.roll(g.cx, 100 + salt, i, 1461, 360));
                double rad = 0.15 + 0.6 * p.roll(g.cz, 100 + salt, i, 1462, 100) / 100.0;
                int x = g.cx + (int) Math.round(Math.cos(ang) * rad * g.ex), z = g.cz + (int) Math.round(Math.sin(ang) * rad * g.ez);
                if (!g.inside(x, z)) continue;
                int f = g.floorAt(x, z);
                switch (a.where()) {
                    case "water" -> {
                        if (g.lake && f <= g.lakeTop - 1 && g.fkind == FluidKind.WATER) p.spawn(a.entity(), x, g.lakeTop - 1, z, 0f, null);
                    }
                    case "floor" -> {
                        if (g.free(x, z)) p.spawn(a.entity(), x, g.walk(x, z), z, p.roll(x, 0, z, 1463, 360), null);
                    }
                    default -> {
                        if (g.free(x, z)) p.spawn(a.entity(), x, g.walk(x, z) + 3, z, p.roll(x, 0, z, 1463, 360), null);
                    }
                }
            }
        }
    }

    /** A lectern with the place's lore note. */
    private static void lore(CavernGen g, int x, int y, int z, Direction face) {
        if (g.prof.lore.isEmpty()) return;
        Painter p = g.p;
        if (!p.ok(x, y, z)) return;
        p.set(x, y, z, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, face).setValue(LecternBlock.HAS_BOOK, true));
        if (p.blockEntity(x, y, z) instanceof LecternBlockEntity lectern) {
            lectern.setBook(Exteriors.book(g.prof.name, List.of(Component.literal(g.prof.name + "\n\n" + g.prof.lore))));
            p.feature("lore:" + g.prof.id);
        } else {
            p.set(x, y, z, Blocks.AIR.defaultBlockState());
        }
    }

    // ------------------------------------------------------------------ the set piece

    private static void setPiece(CavernGen g) {
        CavernProfile.SetPiece sp = g.prof.setPiece;
        String type = sp.type();
        if (type.equals("camp") || type.equals("none")) return;
        Painter p = g.p;
        int r = switch (type) {
            case "altar", "well", "shrine" -> 3;
            case "gate" -> 0;
            default -> 2;
        };
        if (type.equals("gate")) {
            gate(g, sp);
            return;
        }
        int[] s = flatSpot(g, 300, 0.40, 0.62, r, g.setTheta, 1.2);
        if (s == null) s = flatSpot(g, 301, 0.3, 0.8, r, 0, 7);
        if (s == null) return;
        int x = s[0], w = s[1], z = s[2];
        Pal pal = sp.block().isEmpty() ? g.prof.platform : sp.block();
        BlockState accent = sp.accent().isEmpty() ? Blocks.SOUL_LANTERN.defaultBlockState() : sp.accent().first();
        if (accent == null) accent = Blocks.LANTERN.defaultBlockState();
        switch (type) {
            case "altar" -> {
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) p.set(x + dx, w, z + dz, pick(g, pal, x + dx, w, z + dz, 1470, Blocks.STONE_BRICKS.defaultBlockState()));
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) p.set(x + dx, w + 1, z + dz, pick(g, pal, x + dx, w + 1, z + dz, 1471, Blocks.STONE_BRICKS.defaultBlockState()));
                p.set(x, w + 2, z, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH));
                int[][] corner = {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}};
                for (int[] c : corner) {
                    p.set(x + c[0], w + 1, z + c[1], pick(g, pal, x, w, z, 1472, Blocks.STONE_BRICKS.defaultBlockState()));
                    p.set(x + c[0], w + 2, z + c[1], accent);
                }
                p.feature("setpiece:altar");
            }
            case "statue" -> {
                statue(g, x, w, z, pal, sp.accent(), 9);
                p.feature("setpiece:statue");
            }
            case "obelisk" -> {
                int c = g.ceilAt(x, z, w);
                int hgt = Math.min(11, c - w - 3);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) p.set(x + dx, w, z + dz, pick(g, pal, x + dx, w, z + dz, 1473, Blocks.STONE_BRICKS.defaultBlockState()));
                for (int y = 1; y <= hgt; y++) {
                    BlockState s2 = pick(g, pal, x, w + y, z, 1474, Blocks.STONE_BRICKS.defaultBlockState());
                    p.set(x, w + y, z, s2);
                    if (y <= 3) for (Direction d : Direction.Plane.HORIZONTAL) p.set(x + d.getStepX(), w + y, z + d.getStepZ(), s2);
                }
                p.set(x, w + hgt + 1, z, accent);
                p.feature("setpiece:obelisk");
            }
            case "well" -> {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                        if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                        if (ring) p.set(x + dx, w, z + dz, pick(g, pal, x + dx, w, z + dz, 1475, Blocks.STONE_BRICKS.defaultBlockState()));
                        else {
                            p.set(x + dx, w - 1, z + dz, Blocks.WATER.defaultBlockState());
                            p.set(x + dx, w - 2, z + dz, Blocks.WATER.defaultBlockState());
                        }
                    }
                }
                p.set(x - 2, w + 1, z - 2, pick(g, pal, x, w, z, 1476, Blocks.STONE_BRICKS.defaultBlockState()));
                p.set(x + 2, w + 1, z + 2, accent);
                p.feature("setpiece:well");
            }
            case "shrine" -> {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) p.set(x + dx, w, z + dz, pick(g, pal, x + dx, w, z + dz, 1477, Blocks.STONE_BRICKS.defaultBlockState()));
                p.set(x, w + 1, z, pick(g, pal, x, w + 1, z, 1478, Blocks.STONE_BRICKS.defaultBlockState()));
                p.set(x, w + 2, z, accent);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    p.set(x + d.getStepX() * 2, w, z + d.getStepZ() * 2, Deco.candle(Blocks.CANDLE, 4, true));
                }
                p.feature("setpiece:shrine");
            }
            case "tree" -> {
                int c = g.ceilAt(x, z, w);
                int hgt = Math.min(14, c - w - 3);
                if (hgt < 6) return;
                BlockState log = pick(g, pal, x, w, z, 1479, Blocks.OAK_LOG.defaultBlockState());
                for (int y = 0; y < hgt; y++) {
                    for (int dx = 0; dx <= (y < 3 ? 1 : 0); dx++) for (int dz = 0; dz <= (y < 3 ? 1 : 0); dz++) p.set(x + dx, w + y, z + dz, log);
                }
                for (int dy = -1; dy <= 1; dy++) {
                    int rad = dy == 0 ? 4 : 3;
                    for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                        if (dx * dx + dz * dz > rad * rad + 1) continue;
                        p.setIfOpen(x + dx, w + hgt + dy, z + dz, accent);
                    }
                }
                p.feature("setpiece:great_tree");
            }
            default -> {
            }
        }
    }

    /** A gate across the first lane: two piers and a lintel, wide enough for the walkway. */
    private static void gate(CavernGen g, CavernProfile.SetPiece sp) {
        Painter p = g.p;
        Pal pal = sp.block().isEmpty() ? g.prof.platform : sp.block();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!g.room.hasDoor(d)) continue;
            double along = 0.58 * (d.getAxis() == Direction.Axis.X ? g.ex : g.ez);
            int gx = g.cx + (int) Math.round(d.getStepX() * along), gz = g.cz + (int) Math.round(d.getStepZ() * along);
            int w = g.floor;
            int c = g.ceilAt(gx, gz, w);
            int hgt = Math.min(7, c - w - 2);
            if (hgt < 5) return;
            int lx = d.getAxis() == Direction.Axis.X ? 0 : 1, lz = d.getAxis() == Direction.Axis.X ? 1 : 0;
            for (int s = -1; s <= 1; s += 2) {
                for (int y = 0; y < hgt; y++) {
                    p.set(gx + lx * 3 * s, w + y, gz + lz * 3 * s, pick(g, pal, gx, w + y, gz, 1480, Blocks.STONE_BRICKS.defaultBlockState()));
                }
            }
            for (int k = -3; k <= 3; k++) {
                p.set(gx + lx * k, w + hgt, gz + lz * k, pick(g, pal, gx + k, w + hgt, gz, 1481, Blocks.STONE_BRICKS.defaultBlockState()));
            }
            BlockState accent = sp.accent().isEmpty() ? null : sp.accent().first();
            if (accent != null) p.set(gx, w + hgt - 1, gz, accent);
            p.feature("setpiece:gate");
            return;
        }
    }
}
