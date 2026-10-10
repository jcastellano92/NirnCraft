package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Blackreach: the great cavern under the Dwemer cities of the Pale and Hjaalmarch. It runs from Mzinchaleft's Great
 * Lift through the dome round the Tower of Mzark to the lifts of Alftand and Raldbthar: a dark world of glowing
 * mushroom trees, Falmer huts, Dwemer streets and pipes, under a ceiling lost in the dark.
 *
 * <p>The cavern is too big for one structure start, so it is cut into {@value #TILE}-block tiles ({@link Sites}),
 * one start each. Every block is decided from absolute coordinates and fixed shapes, so the tiles meet seamlessly
 * whatever order they generate in.</p>
 */
public final class Blackreach {
    public static final int FLOOR_Y = -28;
    public static final int TILE = 64;
    private static final int MAX_HEIGHT = 62;

    /** The Great Lifts (Mzinchaleft, Alftand, Raldbthar) and the Tower of Mzark. */
    private static final int[] MZINCHALEFT = {-202, -1357}, MZARK = {95, -842}, ALFTAND = {961, -997}, RALDBTHAR = {1108, -564};
    private static final int[][] LIFTS = {MZINCHALEFT, ALFTAND, RALDBTHAR};
    /** Capsules {ax, az, bx, bz, radius}: the passages between the lifts and the great dome. */
    private static final double[][] SHAPES = {
            {MZINCHALEFT[0], MZINCHALEFT[1], MZARK[0], MZARK[1], 64},
            {MZARK[0], MZARK[1], ALFTAND[0], ALFTAND[1], 84},
            {ALFTAND[0], ALFTAND[1], RALDBTHAR[0], RALDBTHAR[1], 64},
            {MZARK[0], MZARK[1], MZARK[0], MZARK[1], 150},
            {420, -930, 420, -930, 110}};

    private Blackreach() {}

    /** How far (x, z) lies inside the cavern's outline (negative outside). */
    static double depth(double x, double z) {
        double best = -1e9;
        for (double[] s : SHAPES) {
            double d = segDist(x, z, s[0], s[1], s[2], s[3]);
            best = Math.max(best, s[4] - d);
        }
        return best;
    }

    private static double segDist(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double l2 = dx * dx + dz * dz;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / l2));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }

    /** Tile origins (min x, min z) of every tile the cavern touches. */
    static int[][] tiles() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (double[] s : SHAPES) {
            minX = (int) Math.min(minX, Math.min(s[0], s[2]) - s[4]);
            maxX = (int) Math.max(maxX, Math.max(s[0], s[2]) + s[4]);
            minZ = (int) Math.min(minZ, Math.min(s[1], s[3]) - s[4]);
            maxZ = (int) Math.max(maxZ, Math.max(s[1], s[3]) + s[4]);
        }
        List<int[]> out = new ArrayList<>();
        for (int tx = Math.floorDiv(minX, TILE); tx <= Math.floorDiv(maxX, TILE); tx++) {
            for (int tz = Math.floorDiv(minZ, TILE); tz <= Math.floorDiv(maxZ, TILE); tz++) {
                int ox = tx * TILE, oz = tz * TILE;
                if (depth(ox + TILE / 2.0, oz + TILE / 2.0) > -TILE * 0.75) out.add(new int[]{ox, oz});
            }
        }
        return out.toArray(new int[0][]);
    }

    public static DungeonPiece piece(Site site, int ox, int oz, int index, long seed) {
        BoundingBox box = new BoundingBox(ox, FLOOR_Y - 10, oz, ox + TILE - 1, FLOOR_Y + MAX_HEIGHT + 6, oz + TILE - 1);
        return new DungeonPiece(site, DungeonPiece.Kind.SURFACE, box, seed, FLOOR_Y, 0, 0, index, DungeonPiece.ROLE_NORMAL, 0, 0, 0);
    }

    // ------------------------------------------------------------------ painting

    static int floorAt(int x, int z) {
        double flat = 1.0;
        for (int[] l : LIFTS) flat = Math.min(flat, Math.max(0, (Math.hypot(x - l[0], z - l[1]) - 8) / 24.0));
        return FLOOR_Y + (int) Math.round((Noise.value(x / 26.0, 0, z / 26.0, 1200) * 5 + Noise.value(x / 7.0, 0, z / 7.0, 1201) * 1.5) * flat);
    }

    static int ceilingAt(int x, int z, double depth) {
        double dome = Math.min(MAX_HEIGHT - 6, 10 + depth * 0.45);
        return floorAt(x, z) + (int) Math.round(dome + Noise.value(x / 18.0, 5, z / 18.0, 1202) * 5);
    }

    public static void build(Painter p, DungeonPiece c) {
        BlockState seal = Blocks.DEEPSLATE.defaultBlockState();
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                double d = depth(x + 0.5, z + 0.5);
                // ragged edges
                d += Noise.value(x / 9.0, 0, z / 9.0, 1203) * 6;
                if (d <= 0) continue;
                int fy = floorAt(x, z);
                int cy = ceilingAt(x, z, d);
                for (int y = Math.max(p.minY(), fy); y <= Math.min(p.maxY(), cy); y++) p.carve(x, y, z, seal);
                // the floor: dark rock with moss, mycelium and glowing lichen
                double n = Noise.value(x / 6.0, 1, z / 6.0, 1204);
                BlockState floor = n > 0.35 ? Blocks.MOSS_BLOCK.defaultBlockState() : n > 0.1 ? Blocks.MYCELIUM.defaultBlockState()
                        : n < -0.45 ? Blocks.TUFF.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState();
                p.set(x, fy - 1, z, floor);
                if (n > 0.55 && p.chance(x, 0, z, 1205, 3)) p.set(x, fy, z, Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
                else if (n > 0.2 && p.chance(x, 1, z, 1206, 7)) p.set(x, fy, z, p.chance(x, 2, z, 1207, 2) ? Blocks.BROWN_MUSHROOM.defaultBlockState() : Blocks.CRIMSON_ROOTS.defaultBlockState());
                // lichen glowing on the ceiling, like stars
                if (p.chance(x, 3, z, 1208, 23)) p.set(x, cy, z, Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.UP), true));
                features(p, x, z, fy, d);
            }
        }
        mzark(p);
    }

    // ------------------------------------------------------------------ features: mushroom trees, huts, Dwemer streets

    private static final int CELL = 14;

    /** A feature anchored in a {@value #CELL}-block cell: kind (0 none, 1 mushroom, 2 hut, 3 Dwemer), centre, size. */
    private static int[] cell(int cx, int cz) {
        long h = (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL) ^ 0x6A09E667F3BCC909L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        int roll = (int) Math.floorMod(h, 100L);
        int jx = (int) Math.floorMod(h >>> 8, (long) (CELL - 6)) + 3, jz = (int) Math.floorMod(h >>> 16, (long) (CELL - 6)) + 3;
        int size = (int) Math.floorMod(h >>> 24, 5L);
        int kind = roll < 42 ? 1 : roll < 52 ? 2 : roll < 60 ? 3 : 0;
        return new int[]{kind, cx * CELL + jx, cz * CELL + jz, size};
    }

    private static void features(Painter p, int x, int z, int fy, double depth) {
        int gx = Math.floorDiv(x, CELL), gz = Math.floorDiv(z, CELL);
        for (int ax = gx - 1; ax <= gx + 1; ax++) {
            for (int az = gz - 1; az <= gz + 1; az++) {
                int[] f = cell(ax, az);
                if (f[0] == 0) continue;
                // features need room round them inside the cavern
                if (depth(f[1], f[2]) < 14) continue;
                int dx = x - f[1], dz = z - f[2];
                double dist = Math.hypot(dx, dz);
                int base = floorAt(f[1], f[2]);
                switch (f[0]) {
                    case 1 -> mushroom(p, x, z, dist, base, f[3], f[1], f[2]);
                    case 2 -> hut(p, x, z, dist, base, f[1], f[2]);
                    default -> dwemer(p, x, z, dx, dz, base, f[1], f[2]);
                }
            }
        }
    }

    /** A giant glowing mushroom: a pale stem and a broad cap glowing blue-green underneath. */
    private static void mushroom(Painter p, int x, int z, double dist, int base, int size, int mx, int mz) {
        int height = 9 + size * 3;
        double capR = 3.5 + size * 0.9;
        if (dist <= 1.1) for (int dy = -1; dy < height; dy++) p.set(x, base + dy, z, Blocks.MUSHROOM_STEM.defaultBlockState());
        if (dist <= capR) {
            int dome = (int) Math.round(Math.sqrt(Math.max(0, capR * capR - dist * dist)) * 0.55);
            int top = base + height + dome;
            int under = base + height - (dist > capR - 1.2 ? 1 : 0);
            for (int y = under; y <= top; y++) {
                boolean skin = y == top || y == under;
                p.set(x, y, z, skin && p.chance(x, y, z, 1210, 5) ? Blocks.SHROOMLIGHT.defaultBlockState()
                        : y == under ? Blocks.WARPED_WART_BLOCK.defaultBlockState() : Blocks.WARPED_WART_BLOCK.defaultBlockState());
            }
            if (dist < 1.5) p.set(x, under - 1, z, Blocks.SEA_LANTERN.defaultBlockState());
            if (dist > capR - 1.6 && p.chance(x, 7, z, 1211, 3)) p.set(x, under - 1, z, Blocks.WEEPING_VINES.defaultBlockState());
        }
        if (x == mx && z == mz && size >= 3) p.spawn(Mobs.spider(), x + 2, base, z + 1, 0f, "Chaurus");
    }

    /** A Falmer hut: a dome of chitin with a low doorway, and its owner. */
    private static void hut(Painter p, int x, int z, double dist, int base, int hx, int hz) {
        if (dist > 3.6) return;
        int h = (int) Math.round(Math.sqrt(Math.max(0, 3.6 * 3.6 - dist * dist)));
        boolean door = z == hz && x > hx;
        for (int dy = 0; dy <= h; dy++) {
            boolean shell = dist > 2.4 || dy == h;
            if (door && dy <= 1) {
                p.air(x, base + dy, z);
                continue;
            }
            p.set(x, base + dy, z, shell ? (p.chance(x, dy, z, 1220, 3) ? Blocks.MUSHROOM_STEM.defaultBlockState() : Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState())
                    : Blocks.AIR.defaultBlockState());
        }
        if (x == hx && z == hz) {
            p.set(x, base, z, Blocks.BROWN_CARPET.defaultBlockState());
            p.set(x, base + h - 1, z, Blocks.SHROOMLIGHT.defaultBlockState());
            p.spawn(Mobs.falmer(), x + 5, base, z, 90f, null);
            if (p.chance(x, 0, z, 1221, 2)) p.container(x - 1, base, z - 1, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
        }
    }

    /** A Dwemer street corner: a paved square, bronze lamp posts, a broken wall and an automaton on watch. */
    private static void dwemer(Painter p, int x, int z, int dx, int dz, int base, int sx, int sz) {
        if (Math.abs(dx) > 5 || Math.abs(dz) > 5) return;
        p.set(x, base - 1, z, (dx + dz) % 3 == 0 ? Blocks.WAXED_CUT_COPPER.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        if (Math.abs(dx) == 5 && dz > -3) {
            int h = 2 + (int) Math.floorMod(sx * 31 + dz * 7, 4);
            for (int dy = 0; dy < h; dy++) p.set(x, base + dy, z, dy == h - 1 ? Blocks.WAXED_COPPER_BLOCK.defaultBlockState()
                    : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        }
        if (Math.abs(dx) == 3 && Math.abs(dz) == 3) {
            for (int dy = 0; dy <= 3; dy++) p.set(x, base + dy, z, dy == 3 ? Blocks.OCHRE_FROGLIGHT.defaultBlockState() : DwemerRooms.rod());
        }
        if (dx == 0 && dz == 0) {
            p.container(x, base, z, Deco.barrel(Direction.UP), Deco.LOOT_DWEMER);
            p.spawn(Mobs.DWARVEN_SPHERE, x + 1, base, z + 2, 0f, null);
        }
    }

    /** The Tower of Mzark: a bronze tower rising into the dark at the heart of the dome, the orb glowing at its top. */
    private static void mzark(Painter p) {
        int tx = MZARK[0], tz = MZARK[1];
        int base = floorAt(tx, tz);
        int top = base + 46;
        for (int x = Math.max(p.minX(), tx - 7); x <= Math.min(p.maxX(), tx + 7); x++) {
            for (int z = Math.max(p.minZ(), tz - 7); z <= Math.min(p.maxZ(), tz + 7); z++) {
                double d = Math.hypot(x - tx, z - tz);
                if (d > 7.2) continue;
                for (int y = base - 1; y <= top; y++) {
                    double r = y < base + 6 ? 7.2 : 5.2;
                    if (d > r) continue;
                    boolean shell = d > r - 1.2;
                    boolean door = y >= base && y <= base + 2 && Math.abs(z - tz) <= 1 && x > tx;
                    BlockState s;
                    if (door) s = Blocks.AIR.defaultBlockState();
                    else if (y == base - 1) s = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                    else if (shell) s = (y - base) % 8 == 4 ? Blocks.OCHRE_FROGLIGHT.defaultBlockState()
                            : (y - base) % 8 == 0 ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : Blocks.WAXED_EXPOSED_CUT_COPPER.defaultBlockState();
                    else s = (y - base) % 12 == 11 ? Blocks.WAXED_CUT_COPPER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    p.set(x, y, z, s);
                }
                // the orb
                double od = Math.sqrt((x - tx) * (x - tx) + (z - tz) * (z - tz));
                if (od <= 3) for (int y = top + 1; y <= top + 6; y++) {
                    double s3 = Math.sqrt(od * od + Math.pow(y - (top + 3.5), 2));
                    if (s3 <= 3) p.set(x, y, z, s3 < 2 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
                }
            }
        }
        // the lift inside: ladders up through the floors
        for (int y = base; y <= top - 1; y++) {
            p.set(tx - 3, y, tz, Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.EAST));
            if ((y - base) % 12 == 11) p.air(tx - 3, y, tz);
        }
        p.spawn(Mobs.DWARVEN_CENTURION, tx + 10, base, tz, 270f, null);
        p.container(tx + 2, base, tz + 2, Deco.chest(Direction.WEST), Deco.LOOT_BOSS);
    }
}
