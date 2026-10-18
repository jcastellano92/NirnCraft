package com.skycraft.realms.worldgen.site;

import com.skycraft.dungeons.world.Painter;
import com.skycraft.realms.RealmBlocks;
import com.skycraft.realms.RealmLayout;
import com.skycraft.realms.block.RealmPortalBlock;
import com.skycraft.realms.worldgen.RealmPiece;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Optional;

import static com.skycraft.realms.worldgen.site.Gates.st;
import static com.skycraft.realms.worldgen.site.RealmKit.b;

/**
 * Coldharbour's structures: Molag Bal's dark, burning mirror of Tamriel, all blue-black stone, iron and soul fire.
 * <ul>
 *     <li>{@code coldharbour/arrival}: the Dark Anchor's landing: a spiked platform where travellers arrive facing
 *     north, the portal home six blocks behind them, and ahead the anchor's pinion sunk in a scorched crater, its
 *     giant chain rising into the sky.</li>
 *     <li>{@code coldharbour/hollow_city}: the Hollow City: a breached wall, burnt-out streets of gutted houses,
 *     collapsed towers, and at its heart the cathedral of Molag Bal (twin spires, a broken roof, a spiked throne
 *     before the Prince's horned statue), held by dremora.</li>
 *     <li>{@code coldharbour/ruined_tower}: a broken tower hung with chains and soul lanterns, a ramp winding up to
 *     its last floor.</li>
 * </ul>
 */
final class ColdharbourSites {
    private ColdharbourSites() {}

    static final String DREMORA = "skycraft:dremora";

    static void register() {
        RealmSites.add("coldharbour/arrival", new Arrival());
        RealmSites.add("coldharbour/hollow_city", new HollowCity());
        RealmSites.add("coldharbour/ruined_tower", new RuinedTower());
    }

    // ------------------------------------------------------------------ palettes and pieces

    static Draw.Pal stone() {
        return Draw.Pal.weighted(new BlockState[]{st(RealmBlocks.COLDHARBOUR_BRICKS), st(RealmBlocks.COLDHARBOUR_STONE),
                b(Blocks.CRACKED_DEEPSLATE_BRICKS), b(Blocks.BLACKSTONE)}, new int[]{8, 3, 1, 1});
    }

    static Draw.Pal dark() {
        return RealmKit.weighted(new Block[]{Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.BLACKSTONE},
                new int[]{6, 2, 1});
    }

    static Draw.Pal iron() {
        return RealmKit.weighted(new Block[]{Blocks.POLISHED_BLACKSTONE, Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE}, new int[]{9, 3, 1});
    }

    /** A soul-fire brazier standing on the floor at y. */
    static void brazier(Draw d, int x, int y, int z) {
        d.set(x, y + 1, z, b(Blocks.POLISHED_BLACKSTONE));
        d.set(x, y + 2, z, b(Blocks.SOUL_SOIL));
        d.set(x, y + 3, z, b(Blocks.SOUL_FIRE));
    }

    /**
     * A giant chain of oval links rising from (x, y0, z) to y1, alternate links turned a quarter: what hangs Coldharbour
     * from Molag Bal's sky.
     */
    static void giantChain(Draw d, int x, int z, int y0, int y1) {
        Draw.Pal pal = iron();
        double rx = 2.2, ry = 3.4;
        int k = 0;
        for (double cy = y0 + ry; cy - ry < y1; cy += 5.2, k++) {
            boolean alongX = (k & 1) == 0;
            double px = 0, py = 0;
            for (int i = 0; i <= 16; i++) {
                double a = i * Math.PI * 2 / 16;
                double u = Math.cos(a) * rx, v = Math.sin(a) * ry;
                if (i > 0) {
                    if (alongX) d.line(x + 0.5 + px, cy + py, z + 0.5, x + 0.5 + u, cy + v, z + 0.5, 0.75, pal);
                    else d.line(x + 0.5, cy + py, z + 0.5 + px, x + 0.5, cy + v, z + 0.5 + u, 0.75, pal);
                }
                px = u;
                py = v;
            }
        }
    }

    // ================================================================================================ the Dark Anchor

    static final class Arrival implements RealmSites.Site {
        static final int ANCHOR_DZ = -14;
        static final int CHAIN_H = 110;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int z = RealmLayout.CH_ARRIVAL_Z;
            int y = RealmSites.ground(ctx, 0, z);
            return Optional.of(new RealmSites.Start(0, Mth.clamp(y, 58, 150), z, 0, 2));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return new BoundingBox(s.x() - 14, s.y() - 14, s.z() + ANCHOR_DZ - 13, s.x() + 14, s.y() + CHAIN_H + 6, s.z() + 11);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, deck = piece.oy;
            int ax = cx, az = cz + ANCHOR_DZ, ay = piece.oy - 1;

            // ---- the scorched crater round the pinion (natural ground only, before anything is built)
            for (int x = Math.max(ax - 12, p.minX()); x <= Math.min(ax + 12, p.maxX()); x++)
                for (int z = Math.max(az - 12, p.minZ()); z <= Math.min(az + 12, p.maxZ()); z++) {
                    double dist = Math.sqrt((x - ax) * (x - ax) + (z - az) * (z - az));
                    if (dist > 9 + p.noise(x, 0, z, 4, 9) * 2) continue;
                    int top = p.surfaceAt(x, z) - 1;
                    if (top < ay - 6 || top > ay + 6) continue;
                    for (int y = top + 1; y <= top + 10; y++) p.air(x, y, z);
                    p.set(x, top, z, p.chance(x, top, z, 3, 3) ? b(Blocks.BLACKSTONE) : st(RealmBlocks.COLDHARBOUR_STONE));
                    if (dist > 5 && p.chance(x, top, z, 5, 9)) {
                        p.set(x, top, z, b(Blocks.SOUL_SOIL));
                        p.set(x, top + 1, z, b(Blocks.SOUL_FIRE));
                    }
                }

            // ---- the platform
            Draw.Pal floor = Draw.Pal.mix(st(RealmBlocks.COLDHARBOUR_BRICKS), st(RealmBlocks.COLDHARBOUR_STONE), 4);
            d.footing(cx, cz, 9, deck - 1, 12, stone());
            d.cylinder(cx, cz, 9, deck - 1, deck - 1, dark());
            d.cylinder(cx, cz, 8, deck, deck, floor);
            d.tube(cx, cz, 5.6, 4.6, deck, deck, Draw.Pal.of(b(Blocks.CHISELED_POLISHED_BLACKSTONE)));
            d.clearCylinder(cx, cz, 9.5, deck + 1, deck + 14);
            d.set(cx, deck, cz, b(Blocks.POLISHED_BLACKSTONE));
            for (int i = 0; i < 18; i++) {
                double a = i * Math.PI / 9;
                if (Math.abs(Math.sin(a)) > 0.8) continue; // open to the north and south
                int x = (int) Math.floor(cx + 0.5 + Math.cos(a) * 8.6), z = (int) Math.floor(cz + 0.5 + Math.sin(a) * 8.6);
                d.set(x, deck, z, Gates.spike(Direction.UP));
            }
            brazier(d, cx - 6, deck, cz);
            brazier(d, cx + 6, deck, cz);
            // a short causeway to the crater's rim
            d.footing(cx - 1, az + 4, cx + 1, cz - 8, deck - 1, 8, stone());
            d.box(cx - 1, deck - 1, az + 4, cx + 1, deck - 1, cz - 8, floor);

            // ---- the portal home, six blocks behind the arrival spot
            BlockState portal = st(RealmBlocks.COLDHARBOUR_PORTAL).setValue(RealmPortalBlock.AXIS_X, true).setValue(RealmPortalBlock.RETURN, true);
            Gates.framedPortal(d, cx, deck, cz + 6, true, 1, 4, portal, stone(), dark());
            d.set(cx - 3, deck + 8, cz + 6, Gates.spike(Direction.UP));
            d.set(cx + 3, deck + 8, cz + 6, Gates.spike(Direction.UP));
            d.set(cx, deck + 9, cz + 6, Gates.spike(Direction.UP));

            // ---- the pinion: a spiked anchor head sunk into the ground, flukes clawing the earth
            Draw.Pal head = RealmKit.weighted(new Block[]{Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.BLACKSTONE},
                    new int[]{5, 3, 1});
            d.footing(ax, az, 4.5, ay, 12, head);
            d.cylinder(ax, az, 4.5, ay, ay, head);
            d.cone(ax, az, 3.8, 1.4, ay + 1, ay + 7, head);
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 4 + i * Math.PI / 2;
                double ca = Math.cos(a), sa = Math.sin(a);
                d.curve(ax + 0.5, ay + 3, az + 0.5, ax + 0.5 + ca * 6.5, ay + 1.5, az + 0.5 + sa * 6.5,
                        ax + 0.5 + ca * 7.5, ay + 6, az + 0.5 + sa * 7.5, 1.3, 0.5, head);
                d.set((int) Math.floor(ax + 0.5 + ca * 7.5), ay + 7, (int) Math.floor(az + 0.5 + sa * 7.5), Gates.spike(Direction.UP));
            }
            giantChain(d, ax, az, ay + 5, deck + CHAIN_H);
        }
    }

    // ================================================================================================ the Hollow City

    static final class HollowCity implements RealmSites.Site {
        static final int WALL_R = 100;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int z = RealmLayout.CH_CITY_Z;
            int y = RealmSites.ground(ctx, 0, z);
            return Optional.of(new RealmSites.Start(0, Mth.clamp(y, 58, 150), z, 0, 0));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, WALL_R + 12, 16, 62);
        }

        static boolean street(int dx, int dz) {
            if (Math.abs(dx) <= 4 && dz > 0) return true;
            return Math.floorMod(dx + 2, 24) < 5 || Math.floorMod(dz + 2, 24) < 5;
        }

        /** The cathedral's ground (no houses there). */
        static boolean cathedral(int dx, int dz) {
            return Math.abs(dx) < 26 && dz > -46 && dz < 32;
        }

        static int[][] towers(long seed) {
            int[][] out = new int[5][];
            for (int i = 0; i < 5; i++) {
                long h = RealmSites.hash(seed, i, 61);
                double a = Math.toRadians(30 + i * 72 + (h & 15));
                double r = 62 + (h >>> 4 & 7);
                out[i] = new int[]{(int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r), (int) (h >>> 8 & 1023)};
            }
            return out;
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, fy = piece.oy - 1;
            Draw.Pal paving = Draw.Pal.weighted(new BlockState[]{st(RealmBlocks.COLDHARBOUR_STONE), b(Blocks.CRACKED_DEEPSLATE_TILES),
                    b(Blocks.DEEPSLATE_TILES), b(Blocks.BLACKSTONE)}, new int[]{5, 3, 2, 1});

            // ---- the streets and the burnt ground between, column by column
            for (int x = p.minX(); x <= p.maxX(); x++)
                for (int z = p.minZ(); z <= p.maxZ(); z++) {
                    int dx = x - cx, dz = z - cz;
                    double r = Math.sqrt(dx * dx + dz * dz);
                    if (r > WALL_R - 2) continue;
                    if (street(dx, dz) && !cathedral(dx, dz)) {
                        d.column(x, z, fy, 6, paving);
                        if (p.chance(x, fy, z, 3, 13)) p.set(x, fy, z, b(Blocks.SOUL_SOIL));
                        for (int y = fy + 1; y <= fy + 5; y++) p.air(x, y, z);
                        continue;
                    }
                    int top = p.surfaceAt(x, z) - 1;
                    if (top < fy - 4 || top > fy + 4) continue;
                    if (p.chance(x, top, z, 4, 5)) p.set(x, top, z, p.chance(x, top, z, 5, 2) ? b(Blocks.BLACKSTONE) : b(Blocks.SOUL_SOIL));
                    else if (p.chance(x, top, z, 6, 37)) {
                        p.set(x, top, z, b(Blocks.NETHERRACK));
                        p.set(x, top + 1, z, b(Blocks.FIRE));
                    } else if (p.chance(x, top, z, 7, 23)) p.set(x, top + 1, z, b(Blocks.COBBLED_DEEPSLATE));
                }

            wall(d, p, cx, cz, fy);
            int[][] towers = towers(piece.seed);
            for (int[] t : towers) collapsedTower(d, p, cx + t[0], cz + t[1], fy, t[2]);

            // ---- gutted houses between the streets
            for (int i = -4; i <= 3; i++)
                for (int j = -4; j <= 3; j++) {
                    long h = RealmSites.hash(piece.seed, i, j);
                    int dx = 12 + 24 * i + (int) (h & 3) - 1, dz = 12 + 24 * j + (int) (h >>> 2 & 3) - 1;
                    double r = Math.sqrt(dx * dx + dz * dz);
                    if (r > 86 || cathedral(dx, dz)) continue;
                    boolean nearTower = false;
                    for (int[] t : towers) if (Math.abs(t[0] - dx) < 13 && Math.abs(t[1] - dz) < 13) nearTower = true;
                    if (nearTower || (h >>> 30) % 7 == 0) continue;
                    gutted(d, p, cx + dx, cz + dz, fy, h);
                }

            cathedralOfMolagBal(d, p, cx, cz, fy);

            // ---- soul-fire braziers along the avenue from the gate, and the city's garrison
            for (int dz = 40; dz <= WALL_R - 8; dz += 12) {
                brazier(d, cx - 6, fy, cz + dz);
                brazier(d, cx + 6, fy, cz + dz);
            }
            p.spawn(DREMORA, cx - 2, fy + 1, cz + 60, 180f, null);
            p.spawn(DREMORA, cx + 3, fy + 1, cz + 84, 180f, null);
            p.spawn(DREMORA, cx + 48, fy + 1, cz + 2, 90f, null);
            p.spawn(DREMORA, cx - 50, fy + 1, cz - 22, 270f, null);
            p.spawn(DREMORA, cx + 2, fy + 1, cz - 70, 0f, null);
        }

        // ------------------------------------------------------------------ the breached wall

        static void wall(Draw d, Painter p, int cx, int cz, int fy) {
            Draw.Pal pal = stone();
            for (int x = p.minX(); x <= p.maxX(); x++)
                for (int z = p.minZ(); z <= p.maxZ(); z++) {
                    int dx = x - cx, dz = z - cz;
                    double r = Math.sqrt(dx * dx + dz * dz);
                    if (r < WALL_R - 1.5 || r > WALL_R + 1.5) continue;
                    if (Math.abs(dx) <= 5 && dz > 0) continue; // the gate
                    double a = Math.atan2(dz, dx);
                    double n = p.noise(Math.cos(a) * 30, 0, Math.sin(a) * 30, 9, 5);
                    d.column(x, z, fy, 12, pal);
                    if (n < -0.25) {
                        // a breach: only rubble
                        if (p.chance(x, fy, z, 8, 2)) p.set(x, fy + 1, z, pal.at(p, x, fy + 1, z));
                        continue;
                    }
                    int h = 4 + (int) Math.round((n + 0.25) / 1.25 * 12) - p.roll(x, 0, z, 9, 2);
                    for (int y = fy + 1; y <= fy + h; y++) p.set(x, y, z, pal.at(p, x, y, z));
                    if (r > WALL_R + 0.5 && Math.floorMod(x + z, 2) == 0) p.set(x, fy + h + 1, z, pal.at(p, x, fy + h + 1, z));
                }
            // the gate towers, one still standing tall, one broken
            for (int s = -1; s <= 1; s += 2) {
                int tx = cx + 8 * s, tz = cz + (int) Math.round(Math.sqrt(WALL_R * WALL_R - 64.0));
                int h = s < 0 ? 26 : 13;
                d.footing(tx, tz, 4, fy, 12, pal);
                d.cylinder(tx, tz, 4, fy + 1, fy + h, pal);
                d.clearCylinder(tx, tz, 2.6, fy + 1, fy + h - 1);
                if (s < 0) {
                    d.cone(tx, tz, 4.5, 0.6, fy + h + 1, fy + h + 8, dark());
                    d.set(tx, fy + h + 9, tz, Gates.spike(Direction.UP));
                }
                d.air(tx - 4 * s, fy + 1, tz, tx - 2 * s, fy + 3, tz);
            }
        }

        // ------------------------------------------------------------------ ruins

        /** A tower broken off at different heights, its fallen top lying across the ground. */
        static void collapsedTower(Draw d, Painter p, int tx, int tz, int fy, int h) {
            Draw.Pal pal = stone();
            double r = 4.5;
            int base = 12 + (h & 7);
            d.footing(tx, tz, r + 0.5, fy, 12, pal);
            for (int x = Math.max(tx - 5, p.minX()); x <= Math.min(tx + 5, p.maxX()); x++)
                for (int z = Math.max(tz - 5, p.minZ()); z <= Math.min(tz + 5, p.maxZ()); z++) {
                    double dd = Math.sqrt((x - tx) * (x - tx) + (z - tz) * (z - tz));
                    if (dd > r + 0.4) continue;
                    if (dd <= r - 1.4) {
                        for (int y = fy + 1; y <= fy + base + 20; y++) p.air(x, y, z);
                        p.set(x, fy, z, b(Blocks.CRACKED_DEEPSLATE_TILES));
                        continue;
                    }
                    double a = Math.atan2(z - tz, x - tx);
                    int top = fy + base + (int) Math.round((p.noise(Math.cos(a) * 6, h, Math.sin(a) * 6, 3, 17) * 0.5 + 0.5) * 16);
                    for (int y = fy + 1; y <= top; y++) {
                        if (y > fy + 4 && p.chance(x, y, z, 18, 19)) continue;
                        p.set(x, y, z, pal.at(p, x, y, z));
                    }
                }
            // the doorway
            d.air(tx - 1, fy + 1, tz + 3, tx + 1, fy + 3, tz + 5);
            // the fallen top: a trail of masonry
            double fa = (h & 255) / 255.0 * Math.PI * 2;
            double ca = Math.cos(fa), sa = Math.sin(fa);
            d.line(tx + 0.5 + ca * 6, fy + 1.2, tz + 0.5 + sa * 6, tx + 0.5 + ca * 17, fy + 0.8, tz + 0.5 + sa * 17, 1.7, pal);
            d.blob((int) Math.floor(tx + 0.5 + ca * 18), fy + 1, (int) Math.floor(tz + 0.5 + sa * 18), 2.5, 1.5, 2.5, dark());
            // chains still hanging from the stump
            for (int i = 0; i < 3; i++) {
                double ba = fa + Math.PI + (i - 1) * 0.9;
                int x = (int) Math.floor(tx + 0.5 + Math.cos(ba) * (r + 1)), z = (int) Math.floor(tz + 0.5 + Math.sin(ba) * (r + 1));
                int y0 = fy + base - 1;
                d.set(x, y0, z, b(Blocks.POLISHED_BLACKSTONE));
                for (int y = y0 - 1; y >= y0 - 5 - i * 2; y--) d.set(x, y, z, RealmKit.chain(Direction.Axis.Y));
                d.set(x, y0 - 6 - i * 2, z, RealmKit.hanging(Blocks.SOUL_LANTERN));
            }
        }

        /** A gutted house: walls burnt down to different heights, no roof, rubble and fire inside. */
        static void gutted(Draw d, Painter p, int hx, int hz, int fy, long h) {
            int hw = 4 + (int) (h >>> 6 & 1), hd = 4 + (int) (h >>> 7 & 1);
            int x0 = hx - hw, x1 = hx + hw, z0 = hz - hd, z1 = hz + hd;
            if (x1 + 2 < p.minX() || x0 - 2 > p.maxX() || z1 + 2 < p.minZ() || z0 - 2 > p.maxZ()) return;
            Draw.Pal wall = (h >>> 9 & 1) == 0 ? stone() : RealmKit.weighted(new Block[]{Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
                    Blocks.COBBLED_DEEPSLATE}, new int[]{5, 3, 2});
            int wh = 6 + (int) (h >>> 10 & 3);
            d.footing(x0, z0, x1, z1, fy, 8, stone());
            d.clearAbove(x0, z0, x1, z1, fy + 1, fy + wh + 4);
            d.box(x0 + 1, fy, z0 + 1, x1 - 1, fy, z1 - 1, Draw.Pal.mix(b(Blocks.CRACKED_DEEPSLATE_TILES), b(Blocks.SOUL_SOIL), 6));
            for (int x = Math.max(x0, p.minX()); x <= Math.min(x1, p.maxX()); x++)
                for (int z = Math.max(z0, p.minZ()); z <= Math.min(z1, p.maxZ()); z++) {
                    if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                    boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                    int top = corner ? fy + wh : fy + wh - (int) Math.round((p.noise(x, 0, z, 3, 19) * 0.5 + 0.5) * (wh - 1));
                    for (int y = fy + 1; y <= top; y++) {
                        boolean window = !corner && (y == fy + 3 || y == fy + 4) && Math.floorMod(x + z, 3) == 0;
                        if (!window) p.set(x, y, z, wall.at(p, x, y, z));
                    }
                }
            // a doorway on the side facing the nearest street
            Direction out = Direction.from2DDataValue((int) (h >>> 12 & 3));
            int dx = out.getStepX() == 0 ? hx : out.getStepX() > 0 ? x1 : x0;
            int dz = out.getStepZ() == 0 ? hz : out.getStepZ() > 0 ? z1 : z0;
            d.air(dx, fy + 1, dz, dx, fy + 3, dz);
            // a fallen upper floor, rubble, fire
            if ((h >>> 14 & 1) == 0) {
                for (int x = Math.max(x0 + 1, p.minX()); x <= Math.min(x1 - 1, p.maxX()); x++)
                    for (int z = Math.max(z0 + 1, p.minZ()); z <= Math.min(z1 - 1, p.maxZ()); z++) {
                        if (p.noise(x, 3, z, 3, 20) > 0.1) p.set(x, fy + 4, z, b(Blocks.DEEPSLATE_TILE_SLAB));
                    }
            }
            d.blob(hx + (int) (h >>> 15 & 3) - 1, fy + 1, hz - 1, 1.8, 1.0, 1.6, Draw.Pal.mix(b(Blocks.COBBLED_DEEPSLATE), b(Blocks.BLACKSTONE), 3));
            int fx = hx + 1 - (int) (h >>> 17 & 1) * 2, fz = hz + 2;
            d.set(fx, fy, fz, (h >>> 18 & 1) == 0 ? b(Blocks.NETHERRACK) : b(Blocks.SOUL_SOIL));
            d.set(fx, fy + 1, fz, (h >>> 18 & 1) == 0 ? b(Blocks.FIRE) : b(Blocks.SOUL_FIRE));
            if ((h >>> 19) % 4 == 0) p.container(x0 + 1, fy + 1, z0 + 1, Draw.chest(Direction.SOUTH), RealmLoot.COLDHARBOUR);
        }

        // ------------------------------------------------------------------ the cathedral of Molag Bal

        static void cathedralOfMolagBal(Draw d, Painter p, int cx, int cz, int fy) {
            Draw.Pal wall = RealmKit.banded(stone(), Draw.Pal.of(b(Blocks.CHISELED_POLISHED_BLACKSTONE)), 6, fy);
            int x0 = cx - 10, x1 = cx + 10, z0 = cz - 34, z1 = cz + 14, wh = 20;
            // the ground and the walls
            d.footing(x0 - 3, z0 - 1, x1 + 3, z1 + 6, fy, 12, stone());
            d.box(x0 - 3, fy, z0 - 1, x1 + 3, fy, z1 + 6, dark());
            d.clearAbove(x0 - 3, z0 - 1, x1 + 3, z1 + 6, fy + 1, fy + wh + 16);
            d.walls(x0, fy + 1, z0, x1, fy + wh, z1, wall);
            d.air(x0 + 1, fy + 1, z0 + 1, x1 - 1, fy + wh, z1 - 1);
            for (int x = Math.max(x0 + 1, p.minX()); x <= Math.min(x1 - 1, p.maxX()); x++)
                for (int z = Math.max(z0 + 1, p.minZ()); z <= Math.min(z1 - 1, p.maxZ()); z++) {
                    p.set(x, fy, z, ((x + z) & 1) == 0 ? b(Blocks.DEEPSLATE_TILES) : b(Blocks.POLISHED_BLACKSTONE));
                }
            // buttresses with spikes and the lancet windows between them
            for (int z = z0 + 2; z <= z1 - 2; z += 6) {
                for (int s = -1; s <= 1; s += 2) {
                    int x = s < 0 ? x0 - 1 : x1 + 1, xo = s < 0 ? x0 - 2 : x1 + 2;
                    d.box(x, fy + 1, z, x, fy + wh - 2, z, dark());
                    d.box(xo, fy + 1, z, xo, fy + wh - 8, z, dark());
                    d.set(x, fy + wh - 1, z, Gates.spike(Direction.UP));
                    d.set(xo, fy + wh - 7, z, Gates.spike(Direction.UP));
                    if (z + 3 < z1) d.box(s < 0 ? x0 : x1, fy + 5, z + 3, s < 0 ? x0 : x1, fy + 14, z + 3, b(com.skycraft.worldgen.VanillaBlocks.RED_STAINED_GLASS));
                }
            }
            // the roof, broken open over one bay
            RealmKit.gableRoof(d, x0, z0, x1, z1, fy + wh + 1, false, Blocks.DEEPSLATE_TILE_STAIRS,
                    Draw.Pal.of(b(Blocks.POLISHED_BLACKSTONE_BRICKS)), wall, 13);
            d.air(x0 - 2, fy + wh, cz - 9, x1 + 2, fy + wh + 13, cz - 3);
            d.blob(cx + 2, fy + 1, cz - 6, 4.5, 2.0, 3.0, Draw.Pal.mix(b(Blocks.COBBLED_DEEPSLATE), b(Blocks.DEEPSLATE_TILES), 3));
            d.set(cx, fy + wh + 13, z1, Gates.spike(Direction.UP));
            // vault ribs over the nave
            for (int z = z0 + 4; z <= z1 - 4; z += 6) {
                if (z > cz - 10 && z < cz - 2) continue;
                d.curve(x0 + 1.5, fy + 14, z + 0.5, cx + 0.5, fy + 22, z + 0.5, x1 - 0.5, fy + 14, z + 0.5, 0.6, 0.6, dark());
                for (int s = -1; s <= 1; s += 2) d.box(cx + 6 * s, fy + 1, z, cx + 6 * s, fy + 14, z, st(RealmBlocks.COLDHARBOUR_BRICKS));
            }
            // the facade: a pointed door and a rose window of red glass
            for (int x = cx - 3; x <= cx + 3; x++) {
                int ax = Math.abs(x - cx);
                d.air(x, fy + 1, z1, x, fy + (ax == 3 ? 6 : ax == 2 ? 8 : 9), z1);
            }
            for (int x = cx - 4; x <= cx + 4; x++)
                for (int y = fy + 11; y <= fy + 19; y++) {
                    double rr = Math.sqrt((x - cx) * (x - cx) + (y - fy - 15) * (y - fy - 15));
                    if (rr > 3.6) continue;
                    d.set(x, y, z1, rr < 1 || x == cx || y == fy + 15 ? b(Blocks.POLISHED_BLACKSTONE) : b(com.skycraft.worldgen.VanillaBlocks.RED_STAINED_GLASS));
                }
            // twin spires flanking the front
            for (int s = -1; s <= 1; s += 2) {
                int tx = cx + 14 * s, tz = z1 + 1;
                d.footing(tx, tz, 4, fy, 12, wall);
                d.cylinder(tx, tz, 4, fy + 1, fy + 38, wall);
                d.tube(tx, tz, 4.7, 3.6, fy + 38, fy + 39, dark());
                d.cone(tx, tz, 4.2, 0.5, fy + 40, fy + 54, dark());
                d.set(tx, fy + 55, tz, Gates.spike(Direction.UP));
                for (int y = fy + 12; y <= fy + 32; y += 10) d.box(tx, y, tz + 4, tx, y + 3, tz + 4, b(com.skycraft.worldgen.VanillaBlocks.RED_STAINED_GLASS));
            }
            // the runner, braziers down the nave
            for (int z = z0 + 8; z <= z1 - 1; z++) d.box(cx - 1, fy + 1, z, cx + 1, fy + 1, z, b(com.skycraft.worldgen.VanillaBlocks.RED_CARPET));
            for (int z = z0 + 10; z <= z1 - 6; z += 12) {
                brazier(d, cx - 4, fy, z);
                brazier(d, cx + 4, fy, z);
            }

            // ---- the dais, the spiked throne and the statue of the Lord of Domination
            d.box(cx - 6, fy + 1, z0 + 1, cx + 6, fy + 1, z0 + 7, dark());
            d.box(cx - 4, fy + 2, z0 + 1, cx + 4, fy + 2, z0 + 6, dark());
            d.box(cx - 3, fy + 3, z0 + 1, cx + 3, fy + 3, z0 + 5, b(Blocks.POLISHED_BLACKSTONE));
            int ty = fy + 4, tz = z0 + 4;
            d.set(cx, ty, tz, RealmKit.stairs(Blocks.POLISHED_BLACKSTONE_STAIRS, Direction.NORTH));
            for (int s = -1; s <= 1; s += 2) {
                d.set(cx + s, ty, tz, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
                d.set(cx + s, ty + 1, tz, Gates.spike(Direction.UP));
                d.box(cx + s, ty, tz - 1, cx + s, ty + 5, tz - 1, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
                d.set(cx + 2 * s, ty + 4, tz - 1, Gates.spike(s < 0 ? Direction.WEST : Direction.EAST));
                d.set(cx + s, ty + 6, tz - 1, Gates.spike(Direction.UP));
                brazier(d, cx + 5 * s, fy + 1, z0 + 3);
                p.container(cx + 5 * s, fy + 2, z0 + 6, Draw.chest(Direction.SOUTH), RealmLoot.COLDHARBOUR);
            }
            d.box(cx, ty, tz - 1, cx, ty + 6, tz - 1, b(Blocks.POLISHED_BLACKSTONE));
            d.set(cx, ty + 7, tz - 1, Gates.spike(Direction.UP));
            statue(d, cx, fy + 4, z0 + 2);
            p.spawn(DREMORA, cx, fy + 1, z0 + 12, 0f, "Dremora Valkynaz");
            p.spawn(DREMORA, cx - 4, fy + 1, cz, 0f, null);
            p.spawn(DREMORA, cx + 4, fy + 1, cz + 6, 0f, null);
        }

        /** Molag Bal: a horned, broad-shouldered giant of black stone with a spiked mace, eyes of embers; facing south. */
        static void statue(Draw d, int cx, int y, int fz) {
            Draw.Pal body = iron();
            int bz = fz - 1;
            // legs, torso, belt
            for (int s = -1; s <= 1; s += 2) d.box(cx + s, y, bz, cx + 2 * s, y + 4, fz, body);
            d.box(cx - 3, y + 5, bz, cx + 3, y + 9, fz, body);
            d.box(cx - 3, y + 5, fz, cx + 3, y + 5, fz, b(Blocks.GILDED_BLACKSTONE));
            // spiked pauldrons
            for (int s = -1; s <= 1; s += 2) {
                d.box(cx + 4 * s, y + 8, bz, cx + 4 * s, y + 9, fz, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
                d.set(cx + 4 * s, y + 10, fz, Gates.spike(Direction.UP));
                d.set(cx + 4 * s, y + 10, bz, Gates.spike(Direction.UP));
                d.set(cx + 5 * s, y + 9, fz, Gates.spike(s < 0 ? Direction.WEST : Direction.EAST));
            }
            // the left arm hangs, the right raises the mace
            d.box(cx - 4, y + 4, fz, cx - 4, y + 7, fz, body);
            d.line(cx + 4.5, y + 8.5, fz + 0.5, cx + 6.5, y + 11.5, fz + 1.5, 0.6, body);
            d.line(cx + 6.5, y + 9, fz + 1.5, cx + 6.5, y + 13.5, fz + 1.5, 0.45, Draw.Pal.of(b(Blocks.POLISHED_BLACKSTONE)));
            d.blob(cx + 6, y + 14, fz + 1, 1.2, 1.2, 1.2, body);
            d.set(cx + 6, y + 16, fz + 1, Gates.spike(Direction.UP));
            d.set(cx + 8, y + 14, fz + 1, Gates.spike(Direction.EAST));
            d.set(cx + 6, y + 14, fz + 3, Gates.spike(Direction.SOUTH));
            // the head, its burning eyes and great curling horns
            d.box(cx - 1, y + 10, bz, cx + 1, y + 12, fz, body);
            d.set(cx - 1, y + 11, fz, b(Blocks.MAGMA_BLOCK));
            d.set(cx + 1, y + 11, fz, b(Blocks.MAGMA_BLOCK));
            for (int s = -1; s <= 1; s += 2) {
                d.curve(cx + 0.5 + s * 1.5, y + 12.5, fz + 0.5, cx + 0.5 + s * 4.5, y + 13.5, fz + 0.5, cx + 0.5 + s * 4, y + 16, fz, 0.6, 0.35,
                        Draw.Pal.of(b(Blocks.BLACKSTONE)));
            }
        }
    }

    // ================================================================================================ ruined towers

    static final class RuinedTower implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = ctx.chunkPos().getMiddleBlockX(), z = ctx.chunkPos().getMiddleBlockZ();
            if (RealmLayout.dist(x, z, 0, RealmLayout.CH_CITY_Z) < RealmLayout.CH_CITY_R + 70) return Optional.empty();
            if (RealmLayout.dist(x, z, 0, RealmLayout.CH_ARRIVAL_Z) < 70) return Optional.empty();
            int g = RealmSites.ground(ctx, x, z);
            if (g < 62 || RealmSites.surface(ctx, x, z) > g) return Optional.empty();
            int min = g, max = g;
            for (int[] o : new int[][]{{6, 6}, {-6, 6}, {6, -6}, {-6, -6}}) {
                int h = RealmSites.ground(ctx, x + o[0], z + o[1]);
                if (RealmSites.surface(ctx, x + o[0], z + o[1]) > h) return Optional.empty();
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
            if (min < 60 || max - min > 6) return Optional.empty();
            return Optional.of(new RealmSites.Start(x, min, z, 0, ctx.random().nextInt(4)));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 11, 14, 42);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, fy = piece.oy - 1;
            Direction door = Direction.from2DDataValue(piece.facing);
            Draw.Pal pal = stone();
            double r = 5.5;
            int height = 22 + (int) (Math.abs(piece.seed) % 12);
            int upper = fy + height - 9;
            d.footing(cx, cz, r + 1, fy, 12, pal);
            d.cylinder(cx, cz, r + 1, fy, fy, dark());
            // the shell, broken off at the top
            for (int x = Math.max(cx - 6, p.minX()); x <= Math.min(cx + 6, p.maxX()); x++)
                for (int z = Math.max(cz - 6, p.minZ()); z <= Math.min(cz + 6, p.maxZ()); z++) {
                    double dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    if (dd > r + 0.4) continue;
                    if (dd <= r - 1.4) {
                        for (int y = fy + 1; y <= fy + height + 4; y++) p.air(x, y, z);
                        continue;
                    }
                    double a = Math.atan2(z - cz, x - cx);
                    int top = fy + height - (int) Math.round((p.noise(Math.cos(a) * 6, 0, Math.sin(a) * 6, 3, 23) * 0.5 + 0.5) * 8);
                    for (int y = fy + 1; y <= top; y++) {
                        boolean slit = y % 6 == 0 && Math.floorMod(x * 3 + z, 5) == 0;
                        p.set(x, y, z, slit ? b(Blocks.AIR) : pal.at(p, x, y, z));
                    }
                    for (int y = top + 1; y <= fy + height + 4; y++) p.air(x, y, z);
                }
            // the doorway
            for (int k = (int) r - 2; k <= (int) r + 1; k++) {
                int x = cx + door.getStepX() * k, z = cz + door.getStepZ() * k;
                d.air(x, fy + 1, z, x, fy + 3, z);
            }
            // the ramp and the last floor, partly fallen
            d.spiral(cx, cz, 1.4, 4.3, fy + 1, upper, 10.0, 0.0, Draw.Pal.of(st(RealmBlocks.COLDHARBOUR_BRICKS)));
            for (int x = Math.max(cx - 4, p.minX()); x <= Math.min(cx + 4, p.maxX()); x++)
                for (int z = Math.max(cz - 4, p.minZ()); z <= Math.min(cz + 4, p.maxZ()); z++) {
                    double dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    if (dd > r - 1.3) continue;
                    if (p.noise(x, 7, z, 2.5, 24) > 0.25 && dd > 1.5) continue;
                    p.set(x, upper, z, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
                }
            int chx = cx - door.getStepX() * 3, chz = cz - door.getStepZ() * 3;
            d.set(chx, upper, chz, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
            p.container(chx, upper + 1, chz, Draw.chest(door), RealmLoot.COLDHARBOUR);
            brazier(d, cx, upper, cz);
            // beams jutting from the walls with chains and soul lanterns hanging from them
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 4 + i * Math.PI / 2;
                double ca = Math.cos(a), sa = Math.sin(a);
                int by = fy + height - 10 + (i & 1) * 2;
                d.line(cx + 0.5 + ca * (r - 0.5), by + 0.5, cz + 0.5 + sa * (r - 0.5), cx + 0.5 + ca * (r + 2.5), by + 0.5, cz + 0.5 + sa * (r + 2.5),
                        0.5, dark());
                int hx = (int) Math.floor(cx + 0.5 + ca * (r + 2.5)), hz = (int) Math.floor(cz + 0.5 + sa * (r + 2.5));
                int len = 5 + (int) (RealmSites.hash(piece.seed, i, 3) & 7);
                for (int y = by - 1; y >= by - len; y--) d.set(hx, y, hz, RealmKit.chain(Direction.Axis.Y));
                d.set(hx, by - len - 1, hz, RealmKit.hanging(Blocks.SOUL_LANTERN));
            }
            // a ring of iron spikes round the foot
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6 + 0.2;
                int x = (int) Math.floor(cx + 0.5 + Math.cos(a) * 8), z = (int) Math.floor(cz + 0.5 + Math.sin(a) * 8);
                if (!p.ownsColumn(x, z)) continue;
                int top = p.surfaceAt(x, z);
                if (top < fy - 3 || top > fy + 4) continue;
                p.set(x, top, z, Gates.spike(Direction.UP));
            }
            p.spawn(DREMORA, cx + door.getStepX() * 8, fy + 1, cz + door.getStepZ() * 8, Painter.yaw(door), null);
            p.spawn(DREMORA, cx + 1, upper + 1, cz + 1, 0f, null);
        }
    }
}
