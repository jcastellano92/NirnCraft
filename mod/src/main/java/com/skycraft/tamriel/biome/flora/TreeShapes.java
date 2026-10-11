package com.skycraft.tamriel.biome.flora;

import com.skycraft.tamriel.biome.feature.GenBox;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The growth forms of {@link TreeSpecies}. Each grows one tree into a {@link TreeBuilder} from the decoration random:
 * every proportion is sampled per tree (height, lean and its curve, crown base, tier and limb counts, angles and
 * lengths, clump sizes, gaps, dead limbs, roots), so a forest is a crowd of individuals.
 */
final class TreeShapes {
    private TreeShapes() {}

    /** One tree being grown. */
    static final class Grow {
        final TreeBuilder b;
        final RandomSource r;
        final TreeSpecies sp;
        final int x, y, z;
        /** Prevailing wind (radians): windswept species lean and sweep this way. */
        final double windAngle;
        /** Water depth at the foot. */
        final int water;
        BlockState leaves, accent;

        Grow(TreeBuilder b, TreeSpecies sp, int x, int y, int z, double windAngle, int water) {
            this.b = b;
            this.r = b.random;
            this.sp = sp;
            this.x = x;
            this.y = y;
            this.z = z;
            this.windAngle = windAngle;
            this.water = water;
            this.leaves = sp.leaves;
            this.accent = sp.accent;
        }

        double u(double lo, double hi) {
            return lo + r.nextDouble() * (hi - lo);
        }

        int height() {
            return sp.minHeight + r.nextInt(Math.max(1, sp.maxHeight - sp.minHeight + 1));
        }

        double angle() {
            return r.nextDouble() * Math.PI * 2;
        }

        /** A lean direction: random, or downwind for windswept species. */
        double leanAngle() {
            return sp.wind > 0 ? windAngle + (r.nextDouble() - 0.5) * (1.0 - sp.wind) * Math.PI : angle();
        }

        BlockState leafFor(boolean accentPick) {
            return accentPick && accent != null ? accent : leaves;
        }

        /** Per-clump colour: the accent for a share of the clumps (autumn mixes, blossom). */
        BlockState clumpLeaves() {
            return leafFor(!sp.accentTop && accent != null && r.nextFloat() < sp.accentChance);
        }
    }

    static void grow(Grow g) {
        switch (g.sp.shape) {
            case CONIFER -> conifer(g);
            case BROADLEAF -> broadleaf(g);
            case ASPEN -> aspen(g);
            case WILLOW -> willow(g);
            case COLUMNAR -> columnar(g);
            case BALD_CYPRESS -> baldCypress(g);
            case MANGROVE -> mangrove(g);
            case PALM -> palm(g);
            case ACACIA -> acacia(g);
            case BAOBAB -> baobab(g);
            case JUNGLE -> jungle(g);
            case TWISTED -> twisted(g);
            case SHRUB -> shrub(g);
            case DEAD -> dead(g);
            case FALLEN_LOG -> fallenLog(g);
            case STUMP -> stump(g);
            case BAMBOO -> bamboo(g);
            case CRYSTAL -> crystal(g);
        }
        if (g.sp.accentTop && g.accent != null) g.b.tint(g.leaves, g.accent, g.sp.accentChance, true);
    }

    // ===================================================================================================== trunks
    /** A trunk's centre line: a gentle lean that curves in (t^1.6) plus a slow wobble. */
    static double[][] trunkLine(Grow g, int h, double leanScale) {
        double la = g.leanAngle();
        double lm = g.sp.lean * h * g.u(0.3, 1.0) * leanScale;
        double wob = g.u(0.0, 0.35), ph = g.angle(), freq = g.u(0.25, 0.5);
        double[][] c = new double[h + 2][2];
        for (int dy = 0; dy <= h + 1; dy++) {
            double t = dy / (double) Math.max(1, h);
            double off = lm * Math.pow(t, 1.6);
            c[dy][0] = g.x + 0.5 + Math.cos(la) * off + Math.sin(ph + dy * freq) * wob * t;
            c[dy][1] = g.z + 0.5 + Math.sin(la) * off + Math.cos(ph + dy * freq) * wob * t;
        }
        return c;
    }

    /** Draws a trunk along a centre line with a radius per height, and sinks its foot into the ground. */
    static void trunk(Grow g, double[][] c, int from, int to, double r0, double r1, double flare) {
        BlockState log = TreeBuilder.axis(g.sp.log, Direction.Axis.Y);
        for (int dy = from; dy <= to; dy++) {
            double t = (dy - from) / (double) Math.max(1, to - from);
            double r = r0 + (r1 - r0) * t + (dy - from < 3 ? flare * (3 - (dy - from)) / 3.0 : 0);
            if (r < 0.75) {
                g.b.log(Mth.floor(c[dy][0]), g.y + dy, Mth.floor(c[dy][1]), log);
            } else {
                g.b.disc(c[dy][0], g.y + dy, c[dy][1], r, log);
            }
        }
        // the foot: every block of the lowest ring reaches down to solid ground
        double rb = r0 + flare;
        int ri = (int) Math.ceil(rb);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                int bx = Mth.floor(c[from][0]) + dx, bz = Mth.floor(c[from][1]) + dz;
                if (g.b.isLog(bx, g.y + from, bz)) g.b.sink(bx, g.y + from, bz, log, 4 + g.water);
            }
        }
    }

    /**
     * Surface roots: thin arcs from the trunk (at {@code rise} above the foot) out and down into the ground, each
     * root its own length and bearing.
     */
    static void roots(Grow g, double cx, double cz, double r0, int count, double len0, double len1, int rise) {
        for (int i = 0; i < count; i++) {
            double a = (i + g.r.nextDouble() * 0.6) * Math.PI * 2 / count;
            double len = g.u(len0, len1);
            double sx = cx + Math.cos(a) * r0, sz = cz + Math.sin(a) * r0;
            double mx = cx + Math.cos(a) * (r0 + len * 0.45), mz = cz + Math.sin(a) * (r0 + len * 0.45);
            double ex = cx + Math.cos(a) * (r0 + len), ez = cz + Math.sin(a) * (r0 + len);
            double my = g.y + rise * 0.45;
            g.b.branch(sx, g.y + rise, sz, mx, my, mz, 0, 0, g.sp.log);
            g.b.branch(mx, my, mz, ex, g.y - 1, ez, 0, 0, g.sp.log);
            g.b.sink(Mth.floor(ex), g.y - 1, Mth.floor(ez), TreeBuilder.axis(g.sp.log, Direction.Axis.Y), 2);
        }
    }

    // ===================================================================================================== conifers
    /**
     * Skyrim's pines and spruces: a straight or gently leaning trunk; below the crown, a bare trunk with dead stubs;
     * the crown in tiers of drooping boughs, wide at the bottom and narrowing to a spire, each tier lobed by its
     * boughs, some tiers missing. Umbrella pines (West Gash) keep their few tiers at the top.
     */
    static void conifer(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double[][] c = trunkLine(g, h, 1.0);
        double tr = sp.trunk >= 2 ? g.u(1.0, 1.3) : 0;
        int rise = 0;
        if (sp.roots > 0 && g.r.nextFloat() < sp.roots && sp.trunk < 2) {
            // swamp pines stand on exposed roots
            rise = 1 + g.r.nextInt(2) + Math.min(1, g.water);
            for (int dy = 0; dy < rise; dy++) c[dy] = c[rise].clone();
            roots(g, c[0][0], c[0][1], 0.5, 3 + g.r.nextInt(2), 1.5, 3.0, rise);
        }
        trunk(g, c, rise, h, tr, tr * 0.35, sp.trunk >= 2 ? 0.45 : 0);
        if (sp.trunk >= 2 && sp.roots > 0) roots(g, c[0][0], c[0][1], tr + 0.2, 3 + g.r.nextInt(2), 1.2, 2.6, 1);
        int cb = Math.max(1, (int) (h * g.u(sp.base0, sp.base1)));
        double rMax = sp.radius * g.u(0.82, 1.15);
        // dead stubs along the bare trunk
        for (int dy = 2; dy < cb; dy += 1 + g.r.nextInt(2)) {
            if (g.r.nextFloat() < sp.deadLimbs) {
                double a = g.angle();
                double len = 1 + g.r.nextInt(2) + (tr > 0 ? 1 : 0);
                g.b.branch(c[dy][0], g.y + dy + 0.5, c[dy][1], c[dy][0] + Math.cos(a) * (len + tr),
                        g.y + dy + 0.5 - g.r.nextInt(2), c[dy][1] + Math.sin(a) * (len + tr), 0, 0, sp.log);
            }
        }
        // the tiers, from the top down
        int top = h;
        int dy = top - 1;
        while (dy >= cb) {
            double t = (dy - cb) / (double) Math.max(1, top - cb);
            double rad;
            if (sp.umbrella) {
                rad = 1.2 + rMax * Math.pow(Math.sin(Math.PI * Math.min(1, 0.25 + t * 0.85)), 0.7) * g.u(0.8, 1.1);
            } else {
                rad = 0.9 + rMax * Math.pow(1 - t, 0.85) * g.u(0.78, 1.15);
            }
            boolean gap = t > 0.12 && t < 0.8 && g.r.nextFloat() < sp.gaps;
            if (!gap) tier(g, c[dy][0], g.y + dy, c[dy][1], rad, tr);
            dy -= rad > 3.2 ? 2 + g.r.nextInt(2) : 2;
        }
        // the spire
        double sx = c[top][0], sz = c[top][1];
        int sy = g.y + top;
        BlockState lv = g.leaves;
        g.b.leaf(Mth.floor(sx), sy + 1, Mth.floor(sz), lv);
        if (g.r.nextBoolean()) g.b.leaf(Mth.floor(sx), sy + 2, Mth.floor(sz), lv);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            g.b.leaf(Mth.floor(sx) + d.getStepX(), sy, Mth.floor(sz) + d.getStepZ(), lv);
            if (g.r.nextInt(3) > 0) g.b.leaf(Mth.floor(sx) + d.getStepX(), sy - 1, Mth.floor(sz) + d.getStepZ(), lv);
        }
    }

    /** One tier of boughs: a lobed, ragged disc whose rim droops, with log boughs inside the wider tiers. */
    static void tier(Grow g, double cx, int y, double cz, double rad, double tr) {
        int n = 4 + g.r.nextInt(3);
        double a0 = g.angle();
        double[] ang = new double[n];
        double[] len = new double[n];
        for (int i = 0; i < n; i++) {
            ang[i] = a0 + i * Math.PI * 2 / n + (g.r.nextDouble() - 0.5) * 0.7;
            len[i] = rad * g.u(0.72, 1.08);
            if (rad >= 2.6) {
                double bl = len[i] * 0.35 + tr;   // inside the tier's thick middle: no log ends poking out
                g.b.branch(cx, y + 0.5, cz, cx + Math.cos(ang[i]) * bl, y + 0.5 - g.sp.droop * 0.4,
                        cz + Math.sin(ang[i]) * bl, 0, 0, g.sp.log);
            }
        }
        int ri = (int) Math.ceil(rad + tr);
        int bx = Mth.floor(cx), bz = Mth.floor(cz);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                double fx = bx + dx + 0.5 - cx, fz = bz + dz + 0.5 - cz;
                double d = Math.sqrt(fx * fx + fz * fz) - tr;
                if (d > rad + 0.5) continue;
                double th = Math.atan2(fz, fx);
                double reach = 0;
                for (int i = 0; i < n; i++) {
                    double cos = Math.cos(th - ang[i]);
                    if (cos > 0) reach = Math.max(reach, len[i] * (0.55 + 0.45 * cos * cos * cos));
                }
                reach = Math.max(reach, Math.min(1.5, rad));
                if (d > reach + (g.r.nextDouble() - 0.5) * 0.8) continue;
                double rel = d / Math.max(1, rad);
                int droop = rel > 0.5 ? (int) Math.round(g.sp.droop * (rel - 0.5) * 2.2) : 0;
                g.b.leaf(bx + dx, y - droop, bz + dz, g.leaves);
                if (rel < 0.4 || (rel < 0.65 && g.r.nextInt(3) == 0)) g.b.leaf(bx + dx, y + 1 - droop, bz + dz, g.leaves);
                if (droop > 0 && rel > 0.85 && g.r.nextInt(3) == 0) g.b.leaf(bx + dx, y - droop - 1, bz + dz, g.leaves);
            }
        }
    }

    // ===================================================================================================== broadleaves
    /**
     * Oaks, elms, beeches, fruit and flowering trees, hawthorns: a trunk to the fork, then a few main limbs (steep
     * for elms, wide for oaks), each splitting once or twice, with leaf clumps at the ends and a leader clump on top.
     * Some limbs are dead and bare; windswept species throw their whole crown downwind.
     */
    static void broadleaf(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double[][] c = trunkLine(g, h, 1.0);
        double tr = sp.trunk >= 3 ? g.u(1.5, 2.2) : sp.trunk == 2 ? g.u(0.85, 1.15) : 0;
        int fork = Math.max(2, (int) (h * g.u(sp.base0, sp.base1)));
        trunk(g, c, 0, fork, tr, tr * 0.8, tr > 0 ? 0.7 + tr * 0.25 : 0);
        if (sp.roots > 0 && g.r.nextFloat() < sp.roots) {
            roots(g, c[0][0], c[0][1], tr * 0.8 + 0.3, 3 + g.r.nextInt(3), 1.2, 2.5 + tr, tr > 1 ? 2 : 1);
        }
        double crownR = sp.radius * g.u(0.85, 1.15);
        double fx = c[fork][0], fz = c[fork][1];
        int limbs = sp.limbs + g.r.nextInt(2);
        double a0 = g.angle();
        double wind = sp.wind;
        double crownH = h - fork;
        for (int i = 0; i < limbs; i++) {
            double az = a0 + i * Math.PI * 2 / limbs + (g.r.nextDouble() - 0.5) * 0.9;
            if (wind > 0) {
                // fold the limbs downwind
                double rel = Mth.wrapDegrees(Math.toDegrees(az - g.windAngle)) / 180.0;
                az = g.windAngle + rel * Math.PI * (1 - wind * 0.6);
            }
            double elev = Math.toRadians(Mth.lerp(sp.spread, 72, 18) + (g.r.nextDouble() - 0.5) * 24);
            double len = Math.max(2.5, Math.hypot(crownR * 0.72, crownH * 0.75) * g.u(0.75, 1.05));
            double sy = g.y + fork + g.r.nextInt(2) - (i % 2);
            double ex = fx + Math.cos(az) * Math.cos(elev) * len;
            double ez = fz + Math.sin(az) * Math.cos(elev) * len;
            double ey = sy + Math.sin(elev) * len;
            boolean deadLimb = g.r.nextFloat() < sp.deadLimbs;
            g.b.branch(fx, sy, fz, ex, ey, ez, tr > 1 ? tr * 0.55 : 0, 0, sp.log);
            BlockState lv = g.clumpLeaves();
            // side shoots
            int shoots = 1 + g.r.nextInt(2) + (sp.trunk >= 3 ? 1 : 0);
            for (int k = 0; k < shoots; k++) {
                double t = g.u(0.45, 0.85);
                double px = fx + (ex - fx) * t, py = sy + (ey - sy) * t, pz = fz + (ez - fz) * t;
                double saz = az + (g.r.nextBoolean() ? 1 : -1) * g.u(0.5, 1.1);
                double sel = elev + g.u(-0.1, 0.35);
                double sl = len * g.u(0.35, 0.6);
                double qx = px + Math.cos(saz) * Math.cos(sel) * sl, qz = pz + Math.sin(saz) * Math.cos(sel) * sl;
                double qy = py + Math.sin(sel) * sl;
                g.b.branch(px, py, pz, qx, qy, qz, 0, 0, sp.log);
                if (!deadLimb) leafClump(g, qx, qy + 0.5, qz, sp.clump * g.u(0.75, 1.0), lv);
            }
            if (!deadLimb) leafClump(g, ex, ey + 0.5, ez, sp.clump * g.u(0.9, 1.2), lv);
        }
        // the leader and a fill clump so the crown is not hollow
        double lx = c[h][0] + (wind > 0 ? Math.cos(g.windAngle) * wind * 1.5 : 0);
        double lz = c[h][1] + (wind > 0 ? Math.sin(g.windAngle) * wind * 1.5 : 0);
        g.b.branch(fx, g.y + fork, fz, lx, g.y + h - 1, lz, tr > 1 ? tr * 0.5 : 0, 0, sp.log);
        leafClump(g, lx, g.y + h, lz, sp.clump * g.u(0.95, 1.25), g.clumpLeaves());
        double midY = g.y + fork + crownH * 0.65;
        g.b.clump(fx + (lx - fx) * 0.6, midY, fz + (lz - fz) * 0.6, crownR * 0.55, Math.max(1.5, crownH * 0.35),
                crownR * 0.55, g.clumpLeaves(), 0.7);
        if (sp.vines > 0) g.b.hangVines(sp.vines, 4);
    }

    /** A leaf clump, slightly flattened, sized and squashed per clump. */
    static void leafClump(Grow g, double x, double y, double z, double r, BlockState lv) {
        double rx = r * g.u(0.85, 1.15), rz = r * g.u(0.85, 1.15), ry = r * g.u(0.55, 0.8);
        g.b.clump(x, y, z, rx, Math.max(1.2, ry), rz, lv, g.sp.ragged);
    }

    // ===================================================================================================== aspens
    /**
     * The Rift's aspens and birches: a slender white trunk, sometimes twinned, with a narrow crown of small gold and
     * orange clumps stacked up its top half.
     */
    static void aspen(Grow g) {
        TreeSpecies sp = g.sp;
        int stems = g.r.nextInt(6) == 0 ? 2 : 1;
        for (int s = 0; s < stems; s++) {
            int h = (int) (g.height() * (s == 0 ? 1 : g.u(0.65, 0.85)));
            int ox = s == 0 ? 0 : (g.r.nextBoolean() ? 1 : -1), oz = s == 0 ? 0 : (g.r.nextBoolean() ? 1 : -1);
            Grow gs = s == 0 ? g : new Grow(g.b, sp, g.x + ox, g.y, g.z + oz, g.windAngle, g.water);
            double[][] c = trunkLine(gs, h, 1.0);
            trunk(gs, c, 0, h - 1, 0, 0, 0);
            int cb = (int) (h * g.u(sp.base0, sp.base1));
            int clumps = 3 + g.r.nextInt(3);
            BlockState main = g.clumpLeaves();
            for (int k = 0; k < clumps; k++) {
                double t = (k + g.r.nextDouble() * 0.6) / clumps;
                int dy = cb + (int) Math.round((h - cb) * t);
                dy = Math.min(dy, h);
                double a = g.angle(), off = g.u(0.2, 1.3) * (1 - t * 0.6);
                double px = c[dy][0] + Math.cos(a) * off, pz = c[dy][1] + Math.sin(a) * off;
                double rr = sp.radius * g.u(0.75, 1.1) * (1.05 - t * 0.35);
                BlockState lv = g.r.nextFloat() < sp.accentChance ? g.leafFor(true) : main;
                if (off > 0.8) g.b.branch(c[dy][0], g.y + dy, c[dy][1], px, g.y + dy + 1, pz, 0, 0, sp.log);
                g.b.clump(px, g.y + dy + 0.5, pz, rr, rr * g.u(0.8, 1.05), rr, lv, sp.ragged + 0.1);
            }
            g.b.clump(c[h][0], g.y + h + 0.5, c[h][1], 1.3, 1.5, 1.3, main, 0.2);
        }
    }

    // ===================================================================================================== willows
    /** Lakeside willows: a stout, short trunk, arching limbs, a broad dome and curtains of hanging leaves. */
    static void willow(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double[][] c = trunkLine(g, h, 1.2);
        double tr = sp.trunk >= 2 ? g.u(0.6, 1.0) : 0;
        int fork = Math.max(2, (int) (h * g.u(sp.base0, sp.base1)));
        trunk(g, c, 0, fork, tr, tr * 0.7, 0.6);
        double fx = c[fork][0], fz = c[fork][1];
        double crownR = sp.radius * g.u(0.85, 1.15);
        int limbs = sp.limbs + g.r.nextInt(2);
        double a0 = g.angle();
        for (int i = 0; i < limbs; i++) {
            double az = a0 + i * Math.PI * 2 / limbs + (g.r.nextDouble() - 0.5) * 0.7;
            double out = crownR * g.u(0.5, 0.8), up = g.u(2, h - fork + 1.5);
            double ex = fx + Math.cos(az) * out, ez = fz + Math.sin(az) * out, ey = g.y + fork + up;
            g.b.branch(fx, g.y + fork, fz, ex, ey, ez, 0, 0, sp.log);
            g.b.clump(ex, ey, ez, sp.clump * g.u(0.9, 1.2), sp.clump * 0.7, sp.clump * g.u(0.9, 1.2), g.clumpLeaves(), 0.35);
        }
        g.b.clump(fx, g.y + h, fz, crownR * 0.6, 2, crownR * 0.6, g.leaves, 0.4);
        drape(g, fx, fz, crownR * 0.35, 0.5, 6);
    }

    /** Hanging strands of leaves under the outer crown (willows). */
    static void drape(Grow g, double cx, double cz, double minDist, double chance, int maxLen) {
        g.b.drape(cx, cz, minDist, chance, maxLen);
    }

    // ===================================================================================================== cypresses
    /** Gold Coast cypresses: a slim, dark green flame. */
    static void columnar(Grow g) {
        int h = g.height();
        double[][] c = trunkLine(g, h, 0.4);
        trunk(g, c, 0, h - 2, 0, 0, 0);
        double rMax = g.sp.radius * g.u(0.8, 1.2);
        double peak = g.u(0.22, 0.38);
        for (int dy = 1; dy <= h + 1; dy++) {
            double t = dy / (double) (h + 1);
            double r = t < peak ? rMax * Math.pow(t / peak, 0.5) : rMax * Math.pow((1 - t) / (1 - peak), 0.8);
            r = Math.max(r, 0.6);
            int cy = Math.min(dy, h);
            int bx = Mth.floor(c[cy][0]), bz = Mth.floor(c[cy][1]);
            int ri = (int) Math.ceil(r);
            for (int dx = -ri; dx <= ri; dx++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d <= r + (g.r.nextDouble() - 0.5) * 0.7) g.b.leaf(bx + dx, g.y + dy, bz + dz, g.leaves);
                }
            }
        }
        g.b.leaf(Mth.floor(c[h][0]), g.y + h + 2, Mth.floor(c[h][1]), g.leaves);
    }

    // ===================================================================================================== swamps
    /**
     * Black Marsh's bald cypresses: a buttressed foot in the water ringed by knees, a tall straight trunk, and a
     * flat-topped crown of two or three layered pads hung with moss.
     */
    static void baldCypress(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double[][] c = trunkLine(g, h, 0.5);
        trunk(g, c, 0, h, 0.9, 0.5, 1.1 + g.u(0, 0.5));
        // knees
        int knees = 2 + g.r.nextInt(4);
        for (int i = 0; i < knees; i++) {
            double a = g.angle(), d = g.u(2.5, 5.5);
            int kx = Mth.floor(c[0][0] + Math.cos(a) * d), kz = Mth.floor(c[0][1] + Math.sin(a) * d);
            int floor = ground(g.b.box, kx, kz, g.y);
            if (floor == Integer.MIN_VALUE || Math.abs(floor - g.y) > 2) continue;
            int kh = 1 + g.water / 2 + (g.r.nextInt(3) == 0 ? 1 : 0);
            for (int k = -1; k < kh; k++) g.b.anchor(kx, floor + k, kz, TreeBuilder.axis(sp.log, Direction.Axis.Y));
        }
        int pads = 2 + g.r.nextInt(2);
        double rTop = sp.radius * g.u(0.8, 1.15);
        for (int p = 0; p < pads; p++) {
            int py = g.y + h - p * (3 + g.r.nextInt(2));
            double pr = rTop * (1 - p * 0.12) * g.u(0.85, 1.1);
            double px = c[Math.min(h, py - g.y)][0], pz = c[Math.min(h, py - g.y)][1];
            int arms = 3 + g.r.nextInt(3);
            double a0 = g.angle();
            for (int i = 0; i < arms; i++) {
                double a = a0 + i * Math.PI * 2 / arms + g.u(-0.4, 0.4);
                double al = pr * g.u(0.45, 0.75);
                double ax = px + Math.cos(a) * al, az = pz + Math.sin(a) * al;
                g.b.branch(px, py - 1, pz, ax, py, az, 0, 0, sp.log);
                g.b.pad(ax, py + 1, az, pr * 0.5, pr * 0.5, g.leaves, 0.6, true);
            }
            g.b.pad(px, py + 1, pz, pr * 0.65, pr * 0.65, g.leaves, 0.45, true);
        }
        g.b.hangVines(sp.vines, 6);
    }

    /**
     * Mangroves and the Bitter Coast's swamp trees: the trunk stands above the water on arching prop roots, leaning,
     * under a low, dense crown.
     */
    static void mangrove(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        int lift = g.water + 2 + g.r.nextInt(2);
        double[][] c = trunkLine(g, h + lift, 1.0);
        BlockState rootBlock = sp.extra != null ? sp.extra : sp.log;
        // prop roots
        int roots = 5 + g.r.nextInt(4);
        for (int i = 0; i < roots; i++) {
            double a = (i + g.r.nextDouble() * 0.6) * Math.PI * 2 / roots;
            double out = g.u(2, 4.2);
            int startY = lift - g.r.nextInt(2);
            double sx = c[startY][0], sz = c[startY][1];
            int steps = (int) Math.ceil(out * 2);
            for (int s = 0; s <= steps; s++) {
                double t = s / (double) steps;
                double rx = sx + Math.cos(a) * out * t, rz = sz + Math.sin(a) * out * t;
                // an arch: out first, then down
                int ry = g.y + (int) Math.round(startY * (1 - t * t)) - (t > 0.9 ? 1 : 0);
                g.b.anchor(Mth.floor(rx), ry, Mth.floor(rz), rootBlock);
                if (t > 0.85) g.b.sink(Mth.floor(rx), ry, Mth.floor(rz), rootBlock, 3);
            }
        }
        trunk(g, c, lift, h + lift, 0, 0, 0);
        // crown: several clumps around and over the top
        int top = h + lift;
        double crownR = sp.radius * g.u(0.85, 1.15);
        int clumps = 3 + g.r.nextInt(3);
        for (int k = 0; k < clumps; k++) {
            double a = g.angle(), d = g.u(0.5, crownR * 0.6);
            double px = c[top][0] + Math.cos(a) * d, pz = c[top][1] + Math.sin(a) * d;
            int py = g.y + top - g.r.nextInt(3);
            g.b.branch(c[top - 2][0], g.y + top - 2, c[top - 2][1], px, py, pz, 0, 0, sp.log);
            leafClump(g, px, py + 0.5, pz, sp.clump * g.u(0.9, 1.2), g.leaves);
        }
        g.b.hangVines(sp.vines, 5);
    }

    // ===================================================================================================== warm lands
    /**
     * Coconut and date palms: a ringed trunk curving away from upright, and a head of fronds that arch out and droop,
     * each at its own angle. Date palms are straighter with a fuller, shorter-fronded head.
     */
    static void palm(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double a = g.leanAngle();
        double bend = sp.lean * g.u(0.4, 1.2);
        double sx = 0, sz = 0;
        int tx = g.x, tz = g.z;
        for (int dy = 0; dy < h; dy++) {
            double t = dy / (double) h;
            sx = g.x + 0.5 + Math.cos(a) * bend * h * t * t;
            sz = g.z + 0.5 + Math.sin(a) * bend * h * t * t;
            int nx = Mth.floor(sx), nz = Mth.floor(sz);
            if (dy > 0 && (nx != tx || nz != tz)) g.b.log(nx, g.y + dy - 1, nz, sp.log);
            tx = nx;
            tz = nz;
            g.b.log(tx, g.y + dy, tz, sp.log);
        }
        g.b.sink(g.x, g.y, g.z, sp.log, 3);
        int top = g.y + h;
        g.b.leaf(tx, top, tz, g.leaves);
        g.b.leaf(tx, top + 1, tz, g.leaves);
        boolean date = sp.limbs > 8;
        int n = date ? sp.limbs + g.r.nextInt(3) : 7 + g.r.nextInt(4);
        double a0 = g.angle();
        for (int i = 0; i < n; i++) {
            double fa = a0 + i * Math.PI * 2 / n + g.u(-0.3, 0.3);
            double len = sp.radius * g.u(0.75, 1.15);
            double rise = date ? g.u(0.3, 1.0) : g.u(0.6, 1.4);
            int px = Integer.MIN_VALUE, py = 0, pz = 0;
            for (double s = 0.8; s <= len; s += 0.5) {
                double t = s / len;
                int fx = Mth.floor(tx + 0.5 + Math.cos(fa) * s), fz = Mth.floor(tz + 0.5 + Math.sin(fa) * s);
                int fy = top + (int) Math.round(rise * 2 * t - (rise * 2 + (date ? 2.2 : 3.4)) * t * t);
                if (px != Integer.MIN_VALUE && fy < py - 1) g.b.leaf(fx, fy + 1, fz, g.leaves);
                g.b.leaf(fx, fy, fz, g.leaves);
                if (t > 0.25 && t < 0.75) {
                    // the frond's width: leaflets either side of the midrib
                    double pa = fa + Math.PI / 2;
                    g.b.leaf(Mth.floor(fx + 0.5 + Math.cos(pa)), fy, Mth.floor(fz + 0.5 + Math.sin(pa)), g.leaves);
                    g.b.leaf(Mth.floor(fx + 0.5 - Math.cos(pa)), fy, Mth.floor(fz + 0.5 - Math.sin(pa)), g.leaves);
                }
                px = fx;
                py = fy;
                pz = fz;
            }
            if (px != Integer.MIN_VALUE) g.b.leaf(px, py - 1, pz, g.leaves);
        }
    }

    /**
     * Savanna acacias: a short trunk forking into two or three long, angled limbs, each holding up a wide, flat,
     * thin canopy.
     */
    static void acacia(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        int fork = 1 + g.r.nextInt(3);
        double[][] c = trunkLine(g, h, 0.6);
        trunk(g, c, 0, fork, 0, 0, 0);
        int limbs = sp.limbs - 1 + g.r.nextInt(2);
        double a0 = g.angle();
        double crownY = g.y + h;
        for (int i = 0; i < limbs; i++) {
            double az = a0 + i * Math.PI * 2 / limbs + g.u(-0.5, 0.5);
            double out = sp.radius * g.u(0.45, 0.85);
            double ex = c[fork][0] + Math.cos(az) * out, ez = c[fork][1] + Math.sin(az) * out;
            double ey = crownY - g.r.nextInt(2);
            g.b.branch(c[fork][0], g.y + fork, c[fork][1], ex, ey, ez, 0, 0, sp.log);
            double pr = sp.radius * g.u(0.6, 0.95);
            g.b.pad(ex, (int) ey + 1, ez, pr, pr * g.u(0.7, 1.0), g.leaves, 0.45, false);
            g.b.pad(ex, (int) ey + 2, ez, pr * 0.55, pr * 0.5, g.leaves, 0.5, false);
        }
    }

    /** Baobabs: a huge bottle trunk and a sparse crown of stubby branches with small leaf tufts. */
    static void baobab(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double rb = sp.radius * g.u(0.85, 1.15);
        double[][] c = trunkLine(g, h, 0.2);
        BlockState log = TreeBuilder.axis(sp.log, Direction.Axis.Y);
        for (int dy = 0; dy <= h; dy++) {
            double t = dy / (double) h;
            double r = rb * (1.0 + 0.12 * Math.sin(t * Math.PI * 1.2) - 0.55 * Math.pow(t, 1.6));
            g.b.disc(c[dy][0], g.y + dy, c[dy][1], r, log);
        }
        int ri = (int) Math.ceil(rb);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                if (g.b.isLog(Mth.floor(c[0][0]) + dx, g.y, Mth.floor(c[0][1]) + dz)) {
                    g.b.sink(Mth.floor(c[0][0]) + dx, g.y, Mth.floor(c[0][1]) + dz, log, 4);
                }
            }
        }
        int n = sp.limbs + g.r.nextInt(3);
        double a0 = g.angle();
        for (int i = 0; i < n; i++) {
            double az = a0 + i * Math.PI * 2 / n + g.u(-0.4, 0.4);
            double out = g.u(2.5, 5), up = g.u(1, 3.5);
            double sx = c[h][0] + Math.cos(az) * 0.8, sz = c[h][1] + Math.sin(az) * 0.8;
            double ex = sx + Math.cos(az) * out, ez = sz + Math.sin(az) * out, ey = g.y + h + up;
            g.b.branch(sx, g.y + h, sz, ex, ey, ez, 0, 0, sp.log);
            g.b.clump(ex, ey + 0.7, ez, g.u(1.4, 2.2), g.u(1.0, 1.4), g.u(1.4, 2.2), g.leaves, 0.6);
        }
    }

    /**
     * Jungle and rainforest trees: buttress roots, a tall trunk with a few side boughs, an emergent umbrella of big
     * flat clumps, vines down the crown and trunk, cocoa on the bark.
     */
    static void jungle(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double[][] c = trunkLine(g, h, 0.6);
        double tr = sp.trunk >= 2 ? g.u(0.95, 1.25) : 0;
        trunk(g, c, 0, h, tr, tr * 0.6, tr > 0 ? 1.0 : 0.4);
        if (sp.roots > 0) roots(g, c[0][0], c[0][1], tr + 0.4, 3 + g.r.nextInt(3), 2, 3.5 + tr * 1.5, 3 + (int) tr);
        int side = 1 + g.r.nextInt(3);
        for (int k = 0; k < side; k++) {
            int dy = (int) (h * g.u(0.5, 0.82));
            double a = g.angle(), l = g.u(2, 4) + tr;
            double ex = c[dy][0] + Math.cos(a) * l, ez = c[dy][1] + Math.sin(a) * l;
            g.b.branch(c[dy][0], g.y + dy, c[dy][1], ex, g.y + dy + 2, ez, 0, 0, sp.log);
            g.b.clump(ex, g.y + dy + 2.5, ez, sp.clump * 0.7, 1.4, sp.clump * 0.7, g.leaves, 0.45);
        }
        int clumps = 3 + g.r.nextInt(3);
        double crownR = sp.radius * g.u(0.85, 1.15);
        for (int k = 0; k < clumps; k++) {
            double a = g.angle(), d = g.u(1, crownR * 0.65);
            double ex = c[h][0] + Math.cos(a) * d, ez = c[h][1] + Math.sin(a) * d;
            int ey = g.y + h - g.r.nextInt(3);
            g.b.branch(c[h - 3][0], g.y + h - 3, c[h - 3][1], ex, ey, ez, 0, 0, sp.log);
            g.b.clump(ex, ey + 1, ez, sp.clump * g.u(0.9, 1.2), sp.clump * 0.5, sp.clump * g.u(0.9, 1.2), g.leaves, 0.4);
        }
        g.b.clump(c[h][0], g.y + h + 1.5, c[h][1], sp.clump * 0.9, sp.clump * 0.55, sp.clump * 0.9, g.leaves, 0.3);
        g.b.hangVines(sp.vines, 7);
        int bx = Mth.floor(c[0][0]), bz = Mth.floor(c[0][1]);
        g.b.trunkVines(bx + (int) Math.round(tr), g.y, g.y + h - 4, bz, 0.5);
        // cocoa pods on the bark
        if (tr == 0 && sp.log.is(Blocks.JUNGLE_LOG)) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (g.r.nextInt(4) != 0) continue;
                int dy = 2 + g.r.nextInt(Math.max(1, h / 2));
                g.b.extra(Mth.floor(c[dy][0]) + d.getStepX(), g.y + dy, Mth.floor(c[dy][1]) + d.getStepZ(),
                        Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, d.getOpposite())
                                .setValue(CocoaBlock.AGE, g.r.nextInt(3)));
            }
        }
    }

    // ===================================================================================================== crags, scrub
    /**
     * Junipers, the Reach's crag trees, Akaviri pines: a short, gnarled trunk that twists as it climbs, horizontal
     * limbs ending in flat pads of foliage, like a windswept bonsai on the rocks.
     */
    static void twisted(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double la = g.leanAngle();
        double cx = g.x + 0.5, cz = g.z + 0.5;
        int straight = 1 + g.r.nextInt(2);
        double[][] c = new double[h + 1][2];
        for (int dy = 0; dy <= h; dy++) {
            c[dy][0] = cx;
            c[dy][1] = cz;
            if (dy >= straight) {
                // the trunk bends hard, then corrects: an S-curve shaped by wind and rock
                double step = sp.lean * 2.2 * g.u(0.5, 1.3);
                cx += Math.cos(la) * step;
                cz += Math.sin(la) * step;
                la += g.u(-0.7, 0.7);
            }
        }
        for (int dy = 0; dy < h; dy++) {
            g.b.branch(c[dy][0], g.y + dy, c[dy][1], c[dy + 1][0], g.y + dy + 1, c[dy + 1][1], 0, 0, sp.log);
        }
        g.b.sink(Mth.floor(c[0][0]), g.y, Mth.floor(c[0][1]), TreeBuilder.axis(sp.log, Direction.Axis.Y), 3);
        int limbs = 3 + g.r.nextInt(3);
        double a0 = g.angle();
        for (int i = 0; i < limbs; i++) {
            int dy = Math.max(1, Math.min(h - 1, (int) Math.round(h * (0.3 + 0.6 * i / (double) limbs)) + g.r.nextInt(2)));
            double a = sp.wind > 0 && g.r.nextFloat() < sp.wind ? g.windAngle + g.u(-1.0, 1.0)
                    : a0 + i * 2.4 + g.u(-0.5, 0.5);
            double l = g.u(1.5, sp.radius + 1.5) * (1.1 - 0.4 * dy / (double) h);
            double ex = c[dy][0] + Math.cos(a) * l, ez = c[dy][1] + Math.sin(a) * l;
            int ey = g.y + dy + g.r.nextInt(2);
            g.b.branch(c[dy][0], g.y + dy, c[dy][1], ex, ey, ez, 0, 0, sp.log);
            if (g.r.nextFloat() < sp.deadLimbs) continue;
            double pr = sp.radius * g.u(0.6, 1.0);
            BlockState lv = g.clumpLeaves();
            g.b.pad(ex, ey, ez, pr, pr * g.u(0.6, 0.95), lv, 0.55, false);
            g.b.pad(ex, ey + 1, ez, pr * 0.65, pr * 0.55, lv, 0.5, false);
        }
        double pr = sp.radius * g.u(0.75, 1.15);
        BlockState lv = g.clumpLeaves();
        g.b.pad(c[h][0], g.y + h, c[h][1], pr, pr * g.u(0.65, 1.0), lv, 0.5, false);
        g.b.pad(c[h][0], g.y + h + 1, c[h][1], pr * 0.6, pr * 0.5, lv, 0.45, false);
    }

    /** Shrubs and bushes: a stub of wood under one to three overlapping, ragged leaf blobs. */
    static void shrub(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        BlockState log = TreeBuilder.axis(sp.log, Direction.Axis.Y);
        g.b.log(g.x, g.y, g.z, log);
        for (int dy = 1; dy <= h; dy++) g.b.log(g.x, g.y + dy, g.z, log);
        int blobs = 1 + g.r.nextInt(3);
        for (int i = 0; i < blobs; i++) {
            double a = g.angle(), d = i == 0 ? 0 : g.u(0.6, 1.4);
            double rr = sp.radius * g.u(0.7, 1.15);
            g.b.clump(g.x + 0.5 + Math.cos(a) * d, g.y + h * 0.6 + 0.8, g.z + 0.5 + Math.sin(a) * d, rr,
                    Math.max(1.0, rr * g.u(0.6, 0.85)), rr * g.u(0.8, 1.1), g.clumpLeaves(), sp.ragged + 0.2);
        }
    }

    // ===================================================================================================== deadwood
    /**
     * Snags: leafless trunks with a broken top and a few crooked, bare limbs, patches of weathered (stripped) wood;
     * dead conifers keep whorls of short stubs, Atmora's frozen pines a few clumps of frosted needles. Some have
     * snapped, the upper trunk lying beside them.
     */
    static void dead(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        boolean snapped = g.r.nextInt(4) == 0;
        int standing = snapped ? Math.max(3, (int) (h * g.u(0.4, 0.7))) : h;
        double[][] c = trunkLine(g, h, 1.6);
        BlockState log = TreeBuilder.axis(sp.log, Direction.Axis.Y);
        BlockState weathered = sp.extra != null ? TreeBuilder.axis(sp.extra, Direction.Axis.Y) : log;
        for (int dy = 0; dy < standing; dy++) {
            BlockState s = g.r.nextInt(6) == 0 ? weathered : log;
            g.b.log(Mth.floor(c[dy][0]), g.y + dy, Mth.floor(c[dy][1]), s);
        }
        g.b.sink(Mth.floor(c[0][0]), g.y, Mth.floor(c[0][1]), log, 3 + g.water);
        if (sp.whorls) {
            for (int dy = (int) (standing * 0.3); dy < standing - 1; dy += 2 + g.r.nextInt(2)) {
                int n = 3 + g.r.nextInt(3);
                double a0 = g.angle();
                double t = dy / (double) h;
                for (int i = 0; i < n; i++) {
                    if (g.r.nextInt(3) == 0) continue;
                    double a = a0 + i * Math.PI * 2 / n + g.u(-0.6, 0.6);
                    double l = 0.8 + (1 - t) * g.u(0.3, 1.6);
                    int sy = g.y + dy + (g.r.nextInt(3) == 0 ? 1 : 0);
                    double ex = c[dy][0] + Math.cos(a) * l, ez = c[dy][1] + Math.sin(a) * l;
                    g.b.branch(c[dy][0], sy, c[dy][1], ex, sy - (l > 1.6 && g.r.nextBoolean() ? 1 : 0), ez, 0, 0, sp.log);
                    if (sp.accentChance > 0 && g.r.nextFloat() < sp.accentChance) {
                        g.b.clump(ex, sy, ez, 1.4, 0.9, 1.4, g.leaves, 0.6);
                    }
                }
            }
        } else {
            // a few short, crooked stubs; some snags have none at all
            int limbs = g.r.nextInt(5) == 0 ? 0 : 1 + g.r.nextInt(3);
            for (int i = 0; i < limbs; i++) {
                int dy = Math.max(2, (int) (standing * g.u(0.45, 0.95)));
                dy = Math.min(dy, standing - 1);
                double a = g.angle(), el = Math.toRadians(g.u(10, 50)), l = g.u(1.2, 3.2);
                double ex = c[dy][0] + Math.cos(a) * Math.cos(el) * l, ez = c[dy][1] + Math.sin(a) * Math.cos(el) * l;
                double ey = g.y + dy + Math.sin(el) * l;
                g.b.branch(c[dy][0], g.y + dy, c[dy][1], ex, ey, ez, 0, 0, sp.log);
                if (l > 2.5 && g.r.nextInt(3) == 0) {
                    double a2 = a + g.u(-1, 1);
                    g.b.branch(ex, ey, ez, ex + Math.cos(a2), ey + 1, ez + Math.sin(a2), 0, 0, sp.log);
                }
            }
        }
        if (snapped) {
            // the broken-off top lies on the ground beside the stump
            double a = g.angle();
            int len = Math.min(8, h - standing + 1);
            lying(g, Mth.floor(c[0][0] + Math.cos(a) * 2), Mth.floor(c[0][1] + Math.sin(a) * 2), a, len, sp.log, false);
        }
    }

    /** A log lying along the ground in direction {@code a}, following the terrain; returns the blocks laid. */
    static int lying(Grow g, int x0, int z0, double a, int len, BlockState logBase, boolean dress) {
        Direction.Axis axis = Math.abs(Math.cos(a)) >= Math.abs(Math.sin(a)) ? Direction.Axis.X : Direction.Axis.Z;
        BlockState log = TreeBuilder.axis(logBase, axis);
        int prevY = Integer.MIN_VALUE, laid = 0, lx = Integer.MIN_VALUE, lz = 0;
        for (double s = 0; s < len; s += 0.75) {
            int x = Mth.floor(x0 + 0.5 + Math.cos(a) * s), z = Mth.floor(z0 + 0.5 + Math.sin(a) * s);
            if (x == lx && z == lz) continue;
            int y = ground(g.b.box, x, z, prevY == Integer.MIN_VALUE ? g.y : prevY);
            if (y == Integer.MIN_VALUE) break;
            if (prevY != Integer.MIN_VALUE && Math.abs(y - prevY) > 1) break;
            if (prevY != Integer.MIN_VALUE && y > prevY) y = prevY;     // bridge small dips, never climb
            BlockState here = g.b.box.get(x, y, z);
            if (!GenBox.isSoft(here) || !here.getFluidState().isEmpty()) break;
            if (lx != Integer.MIN_VALUE && x != lx && z != lz) g.b.log(lx, prevY, z, log);    // keep it connected
            g.b.log(x, y, z, log);
            if (dress) {
                int roll = g.r.nextInt(10);
                if (roll < 3) g.b.extra(x, y + 1, z, Blocks.MOSS_CARPET.defaultBlockState());
                else if (roll == 3) g.b.extra(x, y + 1, z, (g.r.nextBoolean() ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM).defaultBlockState());
            }
            prevY = y;
            lx = x;
            lz = z;
            laid++;
        }
        return laid;
    }

    /**
     * Floor height (the first block above the ground, under any leaves and water), or MIN_VALUE outside the box. Only
     * the decorated chunk's heightmaps are ready during decoration, so its neighbours are scanned from {@code hintY}.
     */
    static int ground(GenBox box, int x, int z, int hintY) {
        if (x < box.minX || x > box.maxX || z < box.minZ || z > box.maxZ) return Integer.MIN_VALUE;
        int top = (x >> 4) == box.center.x && (z >> 4) == box.center.z
                ? box.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : hintY + 5;
        // down through water and plants to the floor
        for (int y = top; y >= top - 14; y--) {
            BlockState below = box.get(x, y - 1, z);
            if (!GenBox.isSoft(below) || below.is(BlockTags.LOGS)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Fallen trunks: along the slope of the ground, a root plate at the foot, moss and mushrooms on the back. */
    static void fallenLog(Grow g) {
        TreeSpecies sp = g.sp;
        int len = g.height();
        double a = g.angle();
        int laid = lying(g, g.x, g.z, a, len, sp.log, true);
        if (laid < 3) return;
        if (sp.trunk >= 2) {
            double pa = a + Math.PI / 2;
            lying(g, Mth.floor(g.x + 0.5 + Math.cos(pa)), Mth.floor(g.z + 0.5 + Math.sin(pa)), a, laid, sp.log, true);
        }
        int bx = Mth.floor(g.x + 0.5 - Math.cos(a)), bz = Mth.floor(g.z + 0.5 - Math.sin(a));
        if (g.r.nextInt(3) == 0) {
            // a root plate torn out of the ground, standing on edge
            double pa = a + Math.PI / 2;
            for (int w = -2; w <= 2; w++) {
                for (int up = 0; up <= 2; up++) {
                    if (Math.abs(w) + up > 3 || g.r.nextInt(5) == 0) continue;
                    int px = Mth.floor(bx + 0.5 + Math.cos(pa) * w), pz = Mth.floor(bz + 0.5 + Math.sin(pa) * w);
                    g.b.extra(px, g.y + up, pz, (g.r.nextInt(3) == 0 ? Blocks.COARSE_DIRT : Blocks.ROOTED_DIRT).defaultBlockState());
                }
            }
        } else if (g.r.nextBoolean()) {
            g.b.log(bx, g.y, bz, TreeBuilder.axis(sp.log, Direction.Axis.Y));
        }
    }

    /** Stumps: a short sawn or snapped trunk with root nubs, moss or a mushroom on top. */
    static void stump(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        BlockState log = TreeBuilder.axis(sp.log, Direction.Axis.Y);
        for (int dy = 0; dy < h; dy++) g.b.log(g.x, g.y + dy, g.z, log);
        g.b.sink(g.x, g.y, g.z, log, 3);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (g.r.nextInt(3) == 0) continue;
            g.b.anchor(g.x + d.getStepX(), g.y - 1, g.z + d.getStepZ(), TreeBuilder.axis(sp.log, d.getAxis()));
            if (g.r.nextInt(3) == 0) g.b.log(g.x + d.getStepX(), g.y, g.z + d.getStepZ(), TreeBuilder.axis(sp.log, d.getAxis()));
        }
        int roll = g.r.nextInt(4);
        if (roll == 0) g.b.extra(g.x, g.y + h, g.z, Blocks.MOSS_CARPET.defaultBlockState());
        else if (roll == 1) g.b.extra(g.x, g.y + h, g.z, Blocks.BROWN_MUSHROOM.defaultBlockState());
    }

    // ===================================================================================================== groves
    /** A bamboo grove: a clump of stalks, tallest in the middle, with proper leafy tops. */
    static void bamboo(Grow g) {
        int n = 8 + g.r.nextInt(14);
        double rad = g.sp.radius * g.u(0.8, 1.2);
        for (int i = 0; i < n; i++) {
            double a = g.angle(), d = Math.sqrt(g.r.nextDouble()) * rad;
            int x = Mth.floor(g.x + 0.5 + Math.cos(a) * d), z = Mth.floor(g.z + 0.5 + Math.sin(a) * d);
            int y = ground(g.b.box, x, z, g.y);
            if (y == Integer.MIN_VALUE || Math.abs(y - g.y) > 3) continue;
            if (!g.b.box.get(x, y - 1, z).is(BlockTags.BAMBOO_PLANTABLE_ON)) continue;
            int h = (int) (g.height() * (1.1 - 0.5 * d / rad));
            for (int k = 0; k < h; k++) {
                BambooLeaves lv = k >= h - 2 ? BambooLeaves.LARGE : k == h - 3 ? BambooLeaves.SMALL : BambooLeaves.NONE;
                g.b.extra(x, y + k, z, Blocks.BAMBOO.defaultBlockState().setValue(BambooStalkBlock.AGE, 1)
                        .setValue(BambooStalkBlock.LEAVES, lv).setValue(BambooStalkBlock.STAGE, k == h - 1 ? 1 : 0));
            }
        }
    }

    /** Summerset's crystal trees: a calcite trunk branching into limbs of glowing crystal tipped with clusters. */
    static void crystal(Grow g) {
        TreeSpecies sp = g.sp;
        int h = g.height();
        double[][] c = trunkLine(g, h, 1.0);
        trunk(g, c, 0, h - 2, 0.0, 0.0, 0.8);
        int limbs = sp.limbs + g.r.nextInt(2);
        for (int i = 0; i < limbs; i++) {
            int dy = Math.max(2, (int) (h * g.u(0.45, 0.9)));
            double a = g.angle(), el = Math.toRadians(g.u(30, 65)), l = g.u(2, sp.radius + 1);
            double ex = c[dy][0] + Math.cos(a) * Math.cos(el) * l, ez = c[dy][1] + Math.sin(a) * Math.cos(el) * l;
            double ey = g.y + dy + Math.sin(el) * l;
            g.b.branch(c[dy][0], g.y + dy, c[dy][1], ex, ey, ez, 0, 0, g.leaves);
            if (sp.extra != null && sp.extra.hasProperty(BlockStateProperties.FACING)) {
                g.b.extra(Mth.floor(ex), Mth.floor(ey) + 1, Mth.floor(ez), sp.extra.setValue(BlockStateProperties.FACING, Direction.UP));
            }
        }
        if (sp.extra != null && sp.extra.hasProperty(BlockStateProperties.FACING)) {
            g.b.branch(c[h - 2][0], g.y + h - 2, c[h - 2][1], c[h][0], g.y + h, c[h][1], 0, 0, g.leaves);
            g.b.extra(Mth.floor(c[h][0]), g.y + h + 1, Mth.floor(c[h][1]), sp.extra.setValue(BlockStateProperties.FACING, Direction.UP));
        }
    }
}
