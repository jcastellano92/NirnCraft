package com.skycraft.realms.worldgen.site;

import com.skycraft.dungeons.world.Painter;
import com.skycraft.realms.RealmBlocks;
import com.skycraft.realms.RealmLayout;
import com.skycraft.realms.block.BlackBookBlock;
import com.skycraft.realms.worldgen.RealmPiece;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Optional;

import static com.skycraft.realms.worldgen.site.Gates.st;
import static com.skycraft.realms.worldgen.site.RealmKit.b;

/**
 * Apocrypha's structure: {@code apocrypha/chapter}, one start in every 512-block cell ({@link RealmLayout#AP_SPACING}).
 * Each chapter raises its own round reading platform, a pillar of stacked pages and glyph stone, from the ink floor
 * (y 30) to {@link RealmLayout#AP_PLATFORM_Y} at the cell centre; travellers arrive in its middle facing north, with a
 * Black Book back to Nirn on the platform's south side. The rest depends on the cell:
 * <ul>
 *     <li>cell (0, 0), the Summit: the arrival platform is Miraak's arena itself, a broad flat disc (radius
 *     {@link #ARENA_R}) at {@link RealmLayout#AP_PLATFORM_Y} on a huge flaring tower of pages, with a low rim. Tall spires
 *     around it curl tentacles high over it (never over its middle: the quests summon Miraak and his dragon at the
 *     centre column). The way-out book stands behind the arrival spot, the Summit's chests at the north rim.</li>
 *     <li>cells (k, 0) for k = 1..{@link RealmLayout#APOCRYPHA_CHAPTERS}, the chapters: a winding way of floating page
 *     platforms and bridges (about 80 blocks, rising and falling) north to the chapter's end, where a Black Book reads
 *     on to the next chapter and another leads out; Seekers and a Lurker guard it.</li>
 *     <li>any other cell, a lost chapter: the arrival platform and a bridge to a floating, ruined library.</li>
 * </ul>
 */
final class ApocryphaSites {
    private ApocryphaSites() {}

    static final String SEEKER = "skycraft:seeker";
    static final String LURKER = "skycraft:lurker";
    static final int T = RealmLayout.AP_PLATFORM_Y;
    /** Radius of the Summit's arena. */
    static final int ARENA_R = 16;
    static final int SUMMIT = 0, PATH = 1, LOST = 2;

    static void register() {
        RealmSites.add("apocrypha/chapter", new Chapter());
    }

    // ------------------------------------------------------------------ palettes

    /** Stacked pages: white and black leaves in layers, glyph stone every few. */
    static Draw.Pal stacked() {
        BlockState page = st(RealmBlocks.PAGE_BLOCK), black = st(RealmBlocks.BLACK_PAGE_BLOCK), stone = st(RealmBlocks.APOCRYPHA_STONE),
                glyph = st(RealmBlocks.APOCRYPHA_GLYPH_STONE);
        return (pp, x, y, z) -> {
            if (Math.floorMod(y, 7) == 0) return pp.chance(x, y, z, 3, 3) ? glyph : stone;
            if (pp.chance(x, y, z, 4, 9)) return stone;
            return Math.floorMod(y, 2) == 0 ? black : page;
        };
    }

    static Draw.Pal pages() {
        return Draw.Pal.weighted(new BlockState[]{st(RealmBlocks.PAGE_BLOCK), st(RealmBlocks.BLACK_PAGE_BLOCK), st(RealmBlocks.APOCRYPHA_STONE)},
                new int[]{5, 4, 1});
    }

    static Draw.Pal tentacle() {
        return Draw.Pal.mix(st(RealmBlocks.APOCRYPHA_TENTACLE), st(RealmBlocks.BLACK_PAGE_BLOCK), 11);
    }

    static void book(Draw d, int x, int y, int z, Direction facing, BlackBookBlock.Mode mode) {
        d.set(x, y, z, st(RealmBlocks.BLACK_BOOK).setValue(BlackBookBlock.FACING, facing).setValue(BlackBookBlock.MODE, mode));
    }

    static BlockState candle(long h) {
        Block c = (h & 1) == 0 ? com.skycraft.worldgen.VanillaBlocks.BLACK_CANDLE : com.skycraft.worldgen.VanillaBlocks.GREEN_CANDLE;
        return RealmKit.candles(c, 1 + (int) (h >>> 1 & 3), true);
    }

    // ================================================================================================ the chapter

    static final class Chapter implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int cells = RealmLayout.AP_SPACING / 16;
            int cx = Math.floorDiv(ctx.chunkPos().x(), cells), cz = Math.floorDiv(ctx.chunkPos().z(), cells);
            int kind = cx == 0 && cz == 0 ? SUMMIT : cz == 0 && cx >= 1 && cx <= RealmLayout.APOCRYPHA_CHAPTERS ? PATH : LOST;
            return Optional.of(new RealmSites.Start(RealmLayout.chapterX(cx), T, RealmLayout.chapterZ(cz), kind, 2));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            int x = s.x(), z = s.z();
            return switch (s.param()) {
                case SUMMIT -> new BoundingBox(x - 42, 20, z - 42, x + 42, T + 60, z + 42);
                case PATH -> new BoundingBox(x - 48, 20, z - 134, x + 48, T + 42, z + 14);
                default -> new BoundingBox(x - 32, 20, z - 54, x + 32, T + 30, z + 14);
            };
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            switch (piece.param) {
                case SUMMIT -> summit(d, p, piece);
                case PATH -> path(d, p, piece);
                default -> lost(d, p, piece);
            }
        }
    }

    // ------------------------------------------------------------------ shared pieces

    /** The arrival platform: a waisted pillar of stacked pages from the ink floor, a reading floor on top, the way out. */
    static void arrival(Draw d, Painter p, int cx, int cz, long seed) {
        Draw.Pal body = stacked();
        for (int y = 24; y <= T; y++) {
            double t = Mth.clamp((y - 26) / (double) (T - 26), 0, 1);
            double r = 5.5 + 3.5 * Math.pow(1 - t, 3) + 1.5 * Math.pow(t, 4);
            d.cylinder(cx, cz, r, y, y, body);
        }
        d.tube(cx, cz, 7.6, 6.4, T - 1, T - 1, Draw.Pal.of(st(RealmBlocks.BLACK_PAGE_BLOCK)));
        mosaic(d, p, cx, T, cz, 7.2);
        d.clearCylinder(cx, cz, 8, T + 1, T + 12);
        p.set(cx, T, cz, st(RealmBlocks.APOCRYPHA_GLYPH_STONE));
        // a broken rim of page stacks and shelves with candles, open to the north (the way on) and south (the book)
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8 + 0.1;
            double sa = Math.sin(a);
            if (sa < -0.75 || sa > 0.85) continue;
            long h = RealmSites.hash(seed, i, 5);
            int x = (int) Math.floor(cx + 0.5 + Math.cos(a) * 6.6), z = (int) Math.floor(cz + 0.5 + sa * 6.6);
            int hh = 1 + (int) (h & 1);
            d.box(x, T + 1, z, x, T + hh, z, i % 3 == 0 ? b(Blocks.BOOKSHELF) : st(RealmBlocks.BLACK_PAGE_BLOCK));
            if (i % 2 == 0) d.set(x, T + hh + 1, z, candle(h >>> 2));
        }
        // the way out, behind the arrival spot
        book(d, cx, T + 1, cz + 5, Direction.NORTH, BlackBookBlock.Mode.EXIT);
        d.set(cx - 1, T + 1, cz + 5, RealmKit.candles(com.skycraft.worldgen.VanillaBlocks.BLACK_CANDLE, 3, true));
        d.set(cx + 1, T + 1, cz + 5, RealmKit.candles(com.skycraft.worldgen.VanillaBlocks.BLACK_CANDLE, 2, true));
        // tentacles coiled about the pillar
        for (int i = 0; i < 3; i++) {
            double a = 0.9 + i * 2.1 + (seed & 7) * 0.05;
            coil(d, cx, cz, a, 9.5, 8.2, 26, T - 1);
        }
    }

    /** A tentacle climbing round a pillar from angle {@code a} at radius r0 and y0 to y1, its tip curling out. */
    static void coil(Draw d, int cx, int cz, double a, double r0, double r1, int y0, int y1) {
        Draw.Pal ten = tentacle();
        double ex = cx + 0.5 + Math.cos(a + 1.4) * r1, ez = cz + 0.5 + Math.sin(a + 1.4) * r1;
        d.curve(cx + 0.5 + Math.cos(a) * r0, y0, cz + 0.5 + Math.sin(a) * r0,
                cx + 0.5 + Math.cos(a + 0.8) * (r0 - 0.3), (y0 + y1) / 2.0, cz + 0.5 + Math.sin(a + 0.8) * (r0 - 0.3),
                ex, y1, ez, 1.6, 0.9, ten);
        double tip = a + 1.9;
        if (Math.abs(Math.sin(tip)) > 0.6) return; // never curl over the ways on and out
        d.curve(ex, y1, ez, cx + 0.5 + Math.cos(a + 1.6) * (r1 + 1.8), y1 + 4, cz + 0.5 + Math.sin(a + 1.6) * (r1 + 1.8),
                cx + 0.5 + Math.cos(tip) * (r1 + 1), y1 + 6, cz + 0.5 + Math.sin(tip) * (r1 + 1), 0.9, 0.35, ten);
    }

    /** A reading floor: pages in two tones with a glowing glyph ring near the rim. */
    static void mosaic(Draw d, Painter p, int cx, int y, int cz, double r) {
        int ir = (int) Math.ceil(r);
        BlockState page = st(RealmBlocks.PAGE_BLOCK), black = st(RealmBlocks.BLACK_PAGE_BLOCK), glyph = st(RealmBlocks.APOCRYPHA_GLYPH_STONE),
                stone = st(RealmBlocks.APOCRYPHA_STONE);
        for (int x = Math.max(cx - ir, p.minX()); x <= Math.min(cx + ir, p.maxX()); x++)
            for (int z = Math.max(cz - ir, p.minZ()); z <= Math.min(cz + ir, p.maxZ()); z++) {
                double dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                if (dd > r + 0.3) continue;
                BlockState s;
                if (Math.abs(dd - (r - 1.6)) < 0.5) s = glyph;
                else if (dd > r - 1.1) s = stone;
                else if (Math.abs(dd - r * 0.45) < 0.5) s = black;
                else s = (Math.floorMod(x + 2 * z, 5) < 2) ? black : page;
                p.set(x, y, z, s);
            }
    }

    /** A floating platform: a page floor over an inverted cone of stacked pages, sometimes trailing a tentacle. */
    static void island(Draw d, Painter p, int x, int y, int z, double r, long h) {
        int depth = (int) Math.round(r * 1.7);
        Draw.Pal under = stacked();
        for (int k = 1; k <= depth; k++) {
            double rr = r * (1 - k / (double) (depth + 1)) + 0.3;
            d.cylinder(x, z, rr, y - k, y - k, under);
        }
        mosaic(d, p, x, y, z, r);
        d.clearCylinder(x, z, r + 0.5, y + 1, y + 6);
        if ((h & 3) != 0) {
            double ox = (h >>> 2 & 3) - 1.5, oz = (h >>> 4 & 3) - 1.5;
            d.curve(x + 0.5, y - depth + 1, z + 0.5, x + 0.5 + ox, y - depth - 3, z + 0.5 + oz, x + 0.5 - ox, y - depth - 7, z + 0.5 - oz,
                    0.9, 0.3, tentacle());
        }
    }

    /** Candle-topped page stacks scattered near a platform's edge. */
    static void stacks(Draw d, int x, int y, int z, double r, long h, int n) {
        for (int i = 0; i < n; i++) {
            long hh = RealmSites.hash(h, i, 77);
            double a = (hh & 1023) / 1023.0 * Math.PI * 2;
            int sx = (int) Math.floor(x + 0.5 + Math.cos(a) * (r - 1.2)), sz = (int) Math.floor(z + 0.5 + Math.sin(a) * (r - 1.2));
            int ht = 1 + (int) (hh >>> 10 & 1);
            for (int k = 1; k <= ht; k++) d.set(sx, y + k, sz, (k & 1) == 0 ? st(RealmBlocks.PAGE_BLOCK) : st(RealmBlocks.BLACK_PAGE_BLOCK));
            d.set(sx, y + ht + 1, sz, candle(hh >>> 12));
        }
    }

    /**
     * A bridge of pages {@code 2*halfW+1} wide between two platforms (centres a and b, floor heights ay and by, radii
     * ra and rb). The deck stays level over each platform and climbs in half-block steps between them.
     */
    static void bridge(Draw d, Painter p, double ax, int ay, double az, double ra, double bx, int by, double bz, double rb, double halfW) {
        double dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz, len = Math.sqrt(len2);
        if (len < 1) return;
        double t0 = Math.min(0.49, ra / len), t1 = Math.max(0.51, 1 - rb / len);
        Draw.Pal deck = pages();
        BlockState step = b(Blocks.DEEPSLATE_TILE_SLAB), under = st(RealmBlocks.BLACK_PAGE_BLOCK);
        int pad = (int) Math.ceil(halfW) + 1;
        for (int x = Math.max((int) Math.floor(Math.min(ax, bx)) - pad, p.minX()); x <= Math.min((int) Math.ceil(Math.max(ax, bx)) + pad, p.maxX()); x++)
            for (int z = Math.max((int) Math.floor(Math.min(az, bz)) - pad, p.minZ()); z <= Math.min((int) Math.ceil(Math.max(az, bz)) + pad, p.maxZ()); z++) {
                double t = ((x + 0.5 - ax) * dx + (z + 0.5 - az) * dz) / len2;
                if (t < 0 || t > 1) continue;
                double along = t * len;
                if (along < ra - 0.5 || along > len - rb + 0.5) continue; // the platforms keep their own floors
                double px = ax + dx * t, pz = az + dz * t;
                double off = Math.sqrt((x + 0.5 - px) * (x + 0.5 - px) + (z + 0.5 - pz) * (z + 0.5 - pz));
                if (off > halfW + 0.35) continue;
                double u = Mth.clamp((t - t0) / (t1 - t0), 0, 1);
                double hh = Mth.lerp(u * u * (3 - 2 * u), ay, by);
                int yi = (int) Math.floor(hh);
                boolean half = hh - yi >= 0.5;
                p.set(x, yi, z, deck.at(p, x, yi, z));
                p.set(x, yi - 1, z, under);
                if (half) p.set(x, yi + 1, z, step);
                for (int y = yi + (half ? 2 : 1); y <= yi + 4; y++) p.air(x, y, z);
                if (off > halfW - 0.6 && !half && p.chance(x, yi, z, 9, 11)) p.set(x, yi + 1, z, candle(p.hash(x, yi, z, 10)));
            }
    }

    // ------------------------------------------------------------------ the chapters

    /** The way through a chapter: platform centres and radii, arrival first, the chapter's end last. */
    static int[][] nodes(long seed, int cx, int cz) {
        int n = 5 + (int) (RealmSites.hash(seed, 0, 17) & 1);
        int[][] out = new int[n + 1][];
        out[0] = new int[]{cx, T, cz, 7};
        int x = cx, y = T, z = cz;
        for (int i = 1; i <= n; i++) {
            long h = RealmSites.hash(seed, i, 17);
            int r = i == n ? 8 : 3 + (int) (h >>> 12 & 1);
            int step = 13 + (int) (h & 3) + (i == 1 ? 3 : 0) + (i == n ? 4 : 0);
            int nx = Mth.clamp(x + (int) ((h >>> 2) % 19) - 9, cx - 30, cx + 30);
            int nz = z - step;
            double vis = Math.sqrt((nx - x) * (nx - x) + step * step) - out[i - 1][3] - r;
            int maxDy = Math.max(0, (int) Math.floor(vis * 0.45));
            int dy = Mth.clamp((int) ((h >>> 8) % 11) - 4, -maxDy, maxDy);
            int ny = Mth.clamp(y + dy, T - 4, T + 18);
            out[i] = new int[]{nx, ny, nz, r};
            x = nx;
            y = ny;
            z = nz;
        }
        return out;
    }

    static void path(Draw d, Painter p, RealmPiece piece) {
        int cx = piece.ox, cz = piece.oz;
        int[][] nodes = nodes(piece.seed, cx, cz);
        int last = nodes.length - 1;
        arrival(d, p, cx, cz, piece.seed);
        for (int i = 1; i <= last; i++) {
            int[] n = nodes[i];
            island(d, p, n[0], n[1], n[2], n[3], RealmSites.hash(piece.seed, i, 23));
        }
        for (int i = 1; i <= last; i++) {
            int[] a = nodes[i - 1], c = nodes[i];
            bridge(d, p, a[0] + 0.5, a[1], a[2] + 0.5, a[3], c[0] + 0.5, c[1], c[2] + 0.5, c[3], 1.0);
        }
        // candles, seekers along the way
        for (int i = 1; i < last; i++) {
            int[] n = nodes[i];
            stacks(d, n[0], n[1], n[2], n[3], RealmSites.hash(piece.seed, i, 29), 2);
            if (i % 2 == 0) p.spawn(SEEKER, n[0], n[1] + 1, n[2], 0f, null);
        }
        // the chapter's end: an arch of black pages over two books, a chest, its guardians
        int[] e = nodes[last];
        int ex = e[0], ey = e[1], ez = e[2];
        Draw.Pal arch = Draw.Pal.mix(st(RealmBlocks.BLACK_PAGE_BLOCK), st(RealmBlocks.APOCRYPHA_GLYPH_STONE), 6);
        d.box(ex - 4, ey + 1, ez - 5, ex - 4, ey + 6, ez - 5, arch);
        d.box(ex + 4, ey + 1, ez - 5, ex + 4, ey + 6, ez - 5, arch);
        d.curve(ex - 3.5, ey + 6.5, ez - 4.5, ex + 0.5, ey + 11, ez - 4.5, ex + 4.5, ey + 6.5, ez - 4.5, 0.8, 0.8, arch);
        d.set(ex, ey + 9, ez - 5, st(RealmBlocks.APOCRYPHA_GLYPH_STONE));
        d.box(ex - 3, ey + 1, ez - 5, ex + 3, ey + 1, ez - 3, st(RealmBlocks.APOCRYPHA_STONE));
        book(d, ex - 2, ey + 2, ez - 4, Direction.SOUTH, BlackBookBlock.Mode.NEXT);
        book(d, ex + 2, ey + 2, ez - 4, Direction.SOUTH, BlackBookBlock.Mode.EXIT);
        d.set(ex, ey + 2, ez - 4, RealmKit.candles(com.skycraft.worldgen.VanillaBlocks.GREEN_CANDLE, 4, true));
        p.container(ex, ey + 1, ez - 6, Draw.chest(Direction.SOUTH), RealmLoot.APOCRYPHA_CHAPTER);
        for (int s = -1; s <= 1; s += 2) obelisk(d, ex + 6 * s, ey, ez - 2, 6);
        stacks(d, ex, ey, ez, e[3], RealmSites.hash(piece.seed, last, 29), 4);
        p.spawn(LURKER, ex, ey + 1, ez + 2, 180f, null);
        p.spawn(SEEKER, ex - 4, ey + 1, ez, 180f, null);
        p.spawn(SEEKER, ex + 4, ey + 1, ez + 1, 180f, null);
    }

    /** A small glyph obelisk standing on a floor at y. */
    static void obelisk(Draw d, int x, int y, int z, int h) {
        Draw.Pal stone = RealmKit.banded(Draw.Pal.of(st(RealmBlocks.APOCRYPHA_STONE)), Draw.Pal.of(st(RealmBlocks.APOCRYPHA_GLYPH_STONE)), 3, y);
        d.box(x, y + 1, z, x, y + h, z, stone);
        d.set(x, y + h + 1, z, st(RealmBlocks.APOCRYPHA_GLYPH_STONE));
    }

    // ------------------------------------------------------------------ lost chapters

    static void lost(Draw d, Painter p, RealmPiece piece) {
        int cx = piece.ox, cz = piece.oz;
        arrival(d, p, cx, cz, piece.seed);
        int lx = cx + (int) (RealmSites.hash(piece.seed, 1, 1) % 9), ly = T - 2, lz = cz - 31;
        island(d, p, lx, ly, lz, 12, piece.seed);
        bridge(d, p, cx + 0.5, T, cz + 0.5, 7, lx + 0.5, ly, lz + 0.5, 12, 1.0);
        library(d, p, lx, ly, lz, piece.seed);
    }

    /** A ruined library on a floating platform: broken shelf walls, reading lecterns, page stacks, a chest. */
    static void library(Draw d, Painter p, int cx, int y, int cz, long seed) {
        BlockState stone = st(RealmBlocks.APOCRYPHA_STONE);
        for (int x = Math.max(cx - 12, p.minX()); x <= Math.min(cx + 12, p.maxX()); x++)
            for (int z = Math.max(cz - 12, p.minZ()); z <= Math.min(cz + 12, p.maxZ()); z++) {
                double dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                if (dd <= 10.4 || dd > 11.6) continue;
                double a = Math.atan2(z - cz, x - cx);
                if (Math.sin(a) > 0.8) continue; // the entrance from the bridge
                double brk = p.noise(Math.cos(a) * 8, 0, Math.sin(a) * 8, 3.5, 21);
                int hgt = 1 + (int) Math.round((brk * 0.5 + 0.5) * 5);
                boolean post = Math.floorMod((int) Math.round(a * 12 / Math.PI), 2) == 0
                        && Math.abs(a * 12 / Math.PI - Math.round(a * 12 / Math.PI)) < 0.12;
                Direction in = Math.abs(x - cx) > Math.abs(z - cz) ? (x > cx ? Direction.WEST : Direction.EAST)
                        : (z > cz ? Direction.NORTH : Direction.SOUTH);
                for (int k = 1; k <= hgt; k++) p.set(x, y + k, z, post ? stone : shelf(p, x, y + k, z, in));
                if (post) p.set(x, y + hgt + 1, z, st(RealmBlocks.APOCRYPHA_GLYPH_STONE));
            }
        // two rows of shelves across the reading room, one fallen
        for (int s = -1; s <= 1; s += 2) {
            int z = cz + 4 * s;
            int hgt = s < 0 ? 3 : 1;
            for (int x = cx - 4; x <= cx + 4; x++)
                for (int k = 1; k <= hgt; k++) d.set(x, y + k, z, shelf(p, x, y + k, z, s < 0 ? Direction.SOUTH : Direction.NORTH));
        }
        // the lecterns around a glyph circle
        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 2 + (i - 1) * 0.9;
            int x = (int) Math.floor(cx + 0.5 + Math.cos(a) * 2.5), z = (int) Math.floor(cz + 0.5 + Math.sin(a) * 2.5);
            d.set(x, y + 1, z, b(Blocks.LECTERN).setValue(LecternBlock.FACING, Direction.SOUTH));
        }
        d.set(cx, y, cz, st(RealmBlocks.APOCRYPHA_GLYPH_STONE));
        stacks(d, cx, y, cz, 9, seed, 6);
        p.container(cx, y + 1, cz - 8, Draw.chest(Direction.SOUTH), RealmLoot.APOCRYPHA_CHAPTER);
        p.spawn(SEEKER, cx - 5, y + 1, cz, 0f, null);
        p.spawn(SEEKER, cx + 5, y + 1, cz - 2, 0f, null);
    }

    /** A bookshelf or a half-filled chiseled bookshelf facing {@code in}. */
    static BlockState shelf(Painter p, int x, int y, int z, Direction in) {
        if (!p.chance(x, y, z, 31, 3)) return b(Blocks.BOOKSHELF);
        BlockState s = b(Blocks.CHISELED_BOOKSHELF).setValue(HorizontalDirectionalBlock.FACING, in);
        for (int i = 0; i < ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.size(); i++) {
            s = s.setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i), p.chance(x, y, z, 32 + i, 2));
        }
        return s;
    }

    // ------------------------------------------------------------------ the Summit

    /**
     * The Summit: the arena is the arrival platform itself, a broad disc at {@link #T} on a huge flaring tower of pages
     * rising from the ink, ringed by tall spires whose tentacles curl high over it. The cell centre column stays open
     * above the floor (the quests summon Miraak and his dragon there).
     */
    static void summit(Draw d, Painter p, RealmPiece piece) {
        int cx = piece.ox, cz = piece.oz;
        Draw.Pal body = stacked();
        for (int y = 22; y < T; y++) {
            double flare = 1 - Mth.clamp((y - 22) / 26.0, 0, 1);
            double r = ARENA_R + 6 * flare * flare + 0.6 * Math.sin(y / 4.0);
            d.cylinder(cx, cz, Math.min(r, ARENA_R + 6), y, y, body);
        }
        d.tube(cx, cz, ARENA_R + 0.8, ARENA_R - 0.5, T - 1, T - 1, Draw.Pal.of(st(RealmBlocks.BLACK_PAGE_BLOCK)));
        arena(d, p, cx, T, cz);

        // ---- the spires around, curling tentacles high over the arena (never over its middle)
        Draw.Pal spire = stacked();
        for (int i = 0; i < 7; i++) {
            double a = i * Math.PI * 2 / 7 + 0.25;
            double r = 27 + (i % 2) * 2;
            int ox = (int) Math.floor(cx + 0.5 + Math.cos(a) * r), oz = (int) Math.floor(cz + 0.5 + Math.sin(a) * r);
            int h = T + 34 + (int) (RealmSites.hash(piece.seed, i, 41) & 15);
            d.cone(ox, oz, 4.0, 0.8, 24, h, spire);
            d.set(ox, h + 1, oz, st(RealmBlocks.APOCRYPHA_GLYPH_STONE));
            double ix = cx + 0.5 + Math.cos(a) * 11, iz = cz + 0.5 + Math.sin(a) * 11;
            d.curve(ox + 0.5, h - 4, oz + 0.5, cx + 0.5 + Math.cos(a) * 19, h + 8, cz + 0.5 + Math.sin(a) * 19,
                    ix, T + 24, iz, 1.4, 0.4, tentacle());
            // lesser tentacles writhing out of the ink at each spire's foot
            coil(d, ox, oz, a + 2.2, 5.5, 4.6, 40, T + 6 + i * 3);
        }
        // tentacles coiled about the tower, their tips curling up beside the rim
        for (int i = 0; i < 4; i++) coil(d, cx, cz, 0.6 + i * Math.PI / 2, ARENA_R + 4, ARENA_R + 1.5, 28, T - 2);

        // ---- the way out behind the arrival spot, the Summit's hoard at the north rim
        book(d, cx, T + 1, cz + 6, Direction.NORTH, BlackBookBlock.Mode.EXIT);
        d.set(cx - 1, T + 1, cz + 6, RealmKit.candles(com.skycraft.worldgen.VanillaBlocks.BLACK_CANDLE, 3, true));
        d.set(cx + 1, T + 1, cz + 6, RealmKit.candles(com.skycraft.worldgen.VanillaBlocks.BLACK_CANDLE, 2, true));
        p.container(cx - 3, T + 1, cz - ARENA_R + 3, Draw.chest(Direction.SOUTH), RealmLoot.APOCRYPHA_SUMMIT);
        p.container(cx + 3, T + 1, cz - ARENA_R + 3, Draw.chest(Direction.SOUTH), RealmLoot.APOCRYPHA_SUMMIT);
        obelisk(d, cx - ARENA_R + 3, T, cz, 5);
        obelisk(d, cx + ARENA_R - 3, T, cz, 5);
        p.spawn(SEEKER, cx - 9, T + 1, cz - 9, 0f, null);
        p.spawn(SEEKER, cx + 9, T + 1, cz - 9, 0f, null);
    }

    /** The Summit's arena floor: rings of glyph stone and black pages around the eye of the Daedric Prince. */
    static void arena(Draw d, Painter p, int sx, int top, int sz) {
        BlockState page = st(RealmBlocks.PAGE_BLOCK), black = st(RealmBlocks.BLACK_PAGE_BLOCK), glyph = st(RealmBlocks.APOCRYPHA_GLYPH_STONE),
                stone = st(RealmBlocks.APOCRYPHA_STONE);
        for (int x = Math.max(sx - ARENA_R, p.minX()); x <= Math.min(sx + ARENA_R, p.maxX()); x++)
            for (int z = Math.max(sz - ARENA_R, p.minZ()); z <= Math.min(sz + ARENA_R, p.maxZ()); z++) {
                double dx = x - sx, dz = z - sz, dd = Math.sqrt(dx * dx + dz * dz);
                if (dd > ARENA_R + 0.3) continue;
                BlockState s;
                double eye = (dx / 6.5) * (dx / 6.5) + (dz / 3.5) * (dz / 3.5);
                if (dd < 1.6) s = glyph;
                else if (dd < 2.6) s = black;
                else if (eye <= 1.0) s = page;
                else if (eye <= 1.45) s = black;
                else if (Math.abs(dd - 10) < 0.6 || Math.abs(dd - (ARENA_R - 2)) < 0.5) s = glyph;
                else if (dd > ARENA_R - 1.2) s = stone;
                else s = Math.floorMod((int) Math.floor(Math.atan2(dz, dx) * 8 / Math.PI), 2) == 0 ? black : stone;
                p.set(x, top, z, s);
                for (int y = top + 1; y <= top + 20; y++) p.air(x, y, z);
            }
        // a low rim of black pages
        for (int x = Math.max(sx - ARENA_R, p.minX()); x <= Math.min(sx + ARENA_R, p.maxX()); x++)
            for (int z = Math.max(sz - ARENA_R, p.minZ()); z <= Math.min(sz + ARENA_R, p.maxZ()); z++) {
                double dd = Math.sqrt((x - sx) * (x - sx) + (z - sz) * (z - sz));
                if (dd <= ARENA_R - 0.8 || dd > ARENA_R + 0.3) continue;
                p.set(x, top + 1, z, black);
            }
    }
}
