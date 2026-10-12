package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A grand cavern: the epic chamber of caves, mines, lairs and the deep levels of barrows and Dwemer delves. One
 * piece 40-75 blocks across and 15-32 tall, carved as a bounded volume (a dome over a ring of ledges and a bowl or
 * chasm), with:
 * <ul>
 *     <li>a ring walkway at the doors' level, a terraced bowl with a lake (or a chasm with a lake or lava at the bottom),
 *     natural-rock bridges across it to a central platform, and raised galleries on the wall side;</li>
 *     <li>stalactites, stalagmites and giant columns, a waterfall where there is water, glowing flora by kind
 *     ({@link Kind}: fungal grotto, crystal cavern, frozen cavern, ember cavern, flooded cavern) and posts of light along the
 *     walkways so it stays navigable;</li>
 *     <li>a camp or den, loot on the platform and the galleries, sleepers among the spires and a patrol on the ring.</li>
 * </ul>
 * Everything is a pure function of position and the piece's seed, so every chunk agrees; fluids sit in basins whose
 * rim is never lower than the fluid's surface, so they stay sealed.
 */
public final class GrandCavern {
    /** How far the cavern's piece box reaches below its rim level (the chasm's depth). */
    public static final int BELOW = 18;
    public static final int MIN_HEIGHT = 15;
    public static final int MAX_HEIGHT = 32;
    private static final int HUMP_H = 4;
    private static final int PLATFORM_R = 3;
    /** The bowl (or chasm belt) ends at this fraction of the cavern's radius. */
    private static final double BOWL_EDGE = 0.62;

    /** The look of a cavern. */
    public enum Kind {
        FUNGAL, CRYSTAL, FROZEN, EMBER, FLOODED;

        public static final Kind[] VALUES = values();
    }

    private GrandCavern() {}

    /** Interior height a cavern may have under ground reaching {@code surfaceTop} when its rim lies at {@code floor}. */
    public static int heightFor(int surfaceTop, int floor) {
        return Math.min(MAX_HEIGHT, surfaceTop - floor - 5);
    }

    /** What the cavern looks like: from the theme, the roster and the piece's seed. */
    public static Kind kind(DungeonPiece pc) {
        int h = (int) Math.floorMod(pc.seed ^ (pc.seed >>> 29), 6L);
        return switch (pc.theme) {
            case CAVE -> switch (CaveRoster.of(pc.flavor)) {
                case CaveRoster.ICE -> Kind.FROZEN;
                case CaveRoster.FALMER, CaveRoster.SPRIGGAN, CaveRoster.SPIDER, CaveRoster.FORSWORN -> Kind.FUNGAL;
                case CaveRoster.BANDIT, CaveRoster.SMUGGLER -> h % 2 == 0 ? Kind.FLOODED : Kind.CRYSTAL;
                default -> h < 2 ? Kind.CRYSTAL : h < 4 ? Kind.FLOODED : Kind.FUNGAL;
            };
            case MINE -> h % 2 == 0 ? Kind.CRYSTAL : Kind.EMBER;
            case VAMPIRE_LAIR -> Kind.EMBER;
            case NECROMANCER_LAIR -> Kind.CRYSTAL;
            case HAGRAVEN_LAIR -> Kind.FUNGAL;
            case DWEMER -> h % 3 == 0 ? Kind.FLOODED : Kind.EMBER;
            case BARROW -> pc.kit == Kit.NORD || pc.kit == Kit.ATMORAN || pc.kit == Kit.NONE ? (h % 2 == 0 ? Kind.FROZEN : Kind.FLOODED) : Kind.FLOODED;
            case RUIN -> pc.kit == Kit.ATMORAN || pc.kit == Kit.ICE ? Kind.FROZEN : h % 3 == 0 ? Kind.CRYSTAL : Kind.FLOODED;
            default -> Kind.FLOODED;
        };
    }

    public static void build(Painter p, DungeonPiece piece) {
        Room room = new Room(piece);
        p.feature("room:grand_cavern");
        new Gen(p, room, kind(piece)).run();
    }

    // ------------------------------------------------------------------ the cavern

    private static final class Gen {
        final Painter p;
        final Room room;
        final Kind kind;
        final int cx, cz, floor, height, top;
        final double ex, ez;
        final boolean chasm;
        final int lakeTop;
        final boolean lava;
        final boolean lake;
        final int humpCount;
        final double[] humpTheta = new double[2];
        final double wfTheta;
        final boolean waterfall;
        final double campTheta;
        final int[][] pillars;
        final BlockState rockHere;

        Gen(Painter p, Room room, Kind kind) {
            this.p = p;
            this.room = room;
            this.kind = kind;
            this.cx = room.cx;
            this.cz = room.cz;
            this.floor = room.floor;
            this.height = room.height;
            this.top = room.maxY;
            this.ex = room.halfX() - 1.5;
            this.ez = room.halfZ() - 1.5;
            long h = p.hash(cx, 1, cz, 1100);
            // a chasm (with ladders back up) over a bowl; never over lava, where a fall would be the end
            this.chasm = kind != Kind.EMBER && (((h >>> 5) & 3) == 0 || ((h >>> 5) & 3) == 1);
            this.lava = kind == Kind.EMBER;
            this.lake = lava || kind != Kind.CRYSTAL || ((h >>> 9) & 1) == 0;
            this.lakeTop = chasm ? floor - 10 : floor - 3;
            this.rockHere = Blocks.STONE.defaultBlockState();
            // galleries and the rest of the diagonal sectors: humps opposite each other, then the fall, then the camp
            int rot = (int) ((h >>> 12) & 3);
            this.humpCount = 1 + (int) ((h >>> 14) & 1);
            humpTheta[0] = Math.toRadians(45 + 90 * rot);
            humpTheta[1] = Math.toRadians(45 + 90 * (rot + 2));
            this.wfTheta = Math.toRadians(45 + 90 * (rot + 1));
            this.waterfall = kind != Kind.EMBER && kind != Kind.FROZEN;
            this.campTheta = Math.toRadians(45 + 90 * (rot + 3));
            // giant columns in the ring and the bowl, clear of the walkways
            int n = 4;
            int[][] pl = new int[n][];
            int count = 0;
            for (int i = 0; i < n; i++) {
                double ang = Math.toRadians(45 + 90 * i + p.roll(cx, i, cz, 1101, 37) - 18);
                double rad = (i % 2 == 0 ? 0.50 : 0.76) * Math.min(ex, ez);
                int px = cx + (int) Math.round(Math.cos(ang) * rad), pz = cz + (int) Math.round(Math.sin(ang) * rad);
                int pr = 2 + p.roll(px, i, pz, 1102, 2);
                // clear of the walkways: a column swells to 1.3 times its radius at the ends
                if (Math.min(Math.abs(px - cx), Math.abs(pz - cz)) < 4 + 1.3 * pr) continue;
                pl[count++] = new int[]{px, pz, pr};
            }
            this.pillars = java.util.Arrays.copyOf(pl, count);
        }

        // ---- shape (pure functions of x, z)

        double rr(int x, int z) {
            double u = (x - cx) / ex, v = (z - cz) / ez;
            return Math.sqrt(u * u + v * v) + 0.20 * p.noise(x, 0, z, 10.0, 1201) + 0.09 * p.noise(x, 0, z, 4.5, 1202);
        }

        double rrb(int x, int z) {
            double u = (x - cx) / ex, v = (z - cz) / ez;
            return Math.sqrt(u * u + v * v) + 0.06 * p.noise(x, 0, z, 8.0, 1203);
        }

        /** Within {@code w} of a doorway's lane (from half-way out to the wall). */
        boolean lane(int x, int z, int w) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!room.hasDoor(d)) continue;
                if (d.getAxis() == Direction.Axis.X) {
                    int along = d == Direction.EAST ? x - cx : cx - x;
                    if (Math.abs(z - cz) <= w && along >= 0.5 * ex) return true;
                } else {
                    int along = d == Direction.SOUTH ? z - cz : cz - z;
                    if (Math.abs(x - cx) <= w && along >= 0.5 * ez) return true;
                }
            }
            return false;
        }

        /** The cross of walkways through the middle (bridges over the bowl), 5 wide. */
        boolean cross(int x, int z) {
            return Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 0.66 * ez || Math.abs(z - cz) <= 2 && Math.abs(x - cx) <= 0.66 * ex;
        }

        boolean inside(int x, int z) {
            if (x < room.minX || x > room.maxX || z < room.minZ || z > room.maxZ) return false;
            // the doorway lanes run through the wall layer itself, to meet the corridors
            if (lane(x, z, 1)) return true;
            if (x == room.minX || x == room.maxX || z == room.minZ || z == room.maxZ) return false;
            return rr(x, z) < 0.88;
        }

        int rimWobble(int x, int z, double r) {
            if (r < 0.62) return 0;
            return Mth.clamp((int) Math.round(1.6 * (p.noise(x, 0, z, 7.0, 1204) + 0.35)), 0, 2);
        }

        int hump(int x, int z, double r) {
            if (r < 0.66) return 0;
            double dx = x - cx, dz = z - cz;
            double rad = Math.sqrt(dx * dx + dz * dz);
            double phi = Math.atan2(dz, dx);
            int best = 0;
            for (int k = 0; k < humpCount; k++) {
                double da = Math.abs(phi - humpTheta[k]);
                if (da > Math.PI) da = 2 * Math.PI - da;
                double v = (3.0 + HUMP_H / 0.75 - da * rad) * 0.75;
                best = Math.max(best, (int) Math.round(Mth.clamp(v, 0, HUMP_H)));
            }
            return best;
        }

        private final java.util.HashMap<Long, Integer> baseMemo = new java.util.HashMap<>();
        private final java.util.HashMap<Long, Integer> floorMemo = new java.util.HashMap<>();

        private static long posKey(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        /**
         * Natural walking level of a column (before bridges and the platform). In a bowl no step is ever more than one
         * block, so every terrace can be walked up again: each column is lifted to the highest neighbour within three
         * blocks minus the distance (the smallest 1-Lipschitz surface above the terraces). Chasms keep their cliffs.
         */
        int floorAt(int x, int z) {
            long k = posKey(x, z);
            Integer c = coneMemo.get(k);
            if (c != null) return c;
            int f;
            if (forced(x, z) || chasm && baseFloor(x, z) < floor - 3) {
                f = baseFloor(x, z);
            } else {
                // terrain with its stalagmite mounds, lifted again to a 1-Lipschitz surface (no step above one block)
                f = Integer.MIN_VALUE;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -(3 - Math.abs(dx)); dz <= 3 - Math.abs(dx); dz++) {
                        f = Math.max(f, withMounds(x + dx, z + dz) - Math.abs(dx) - Math.abs(dz));
                    }
                }
            }
            coneMemo.put(k, f);
            return f;
        }

        private final java.util.HashMap<Long, Integer> coneMemo = new java.util.HashMap<>();
        private final java.util.HashMap<Long, Integer> moundMemo = new java.util.HashMap<>();

        /** Terrain plus the stalagmite mounds, before the last smoothing. */
        private int withMounds(int x, int z) {
            long k = posKey(x, z);
            Integer c = moundMemo.get(k);
            if (c != null) return c;
            int f;
            if (forced(x, z)) {
                f = baseFloor(x, z);
            } else {
                f = terrainAt(x, z) + moundAt(x, z);
                // everything above the rim level eases down to it beside a walkway, so no walkway is ever a trench
                if (f > floor) f = floor + (int) Math.round((f - floor) * Mth.clamp(walkDist(x, z) / 8.0, 0, 1));
            }
            moundMemo.put(k, f);
            return f;
        }

        /** Blocks from (x, z) to the nearest walkway band (door lanes and the cross), 0 on it. */
        double walkDist(int x, int z) {
            int dx = Math.abs(x - cx), dz = Math.abs(z - cz);
            double best = Math.min(Math.max(dz - 2, Math.max(0, dx - 0.66 * ex)), Math.max(dx - 2, Math.max(0, dz - 0.66 * ez)));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!room.hasDoor(d)) continue;
                boolean xAxis = d.getAxis() == Direction.Axis.X;
                int lat = xAxis ? dz : dx;
                int along = xAxis ? (d == Direction.EAST ? x - cx : cx - x) : (d == Direction.SOUTH ? z - cz : cz - z);
                double start = 0.5 * (xAxis ? ex : ez);
                best = Math.min(best, Math.max(lat - 2, Math.max(0, start - along)));
            }
            return best;
        }

        /** The walkways (door lanes, the cross) keep their level exactly: corridors and bridges meet them there. */
        boolean forced(int x, int z) {
            return lane(x, z, 2) || cross(x, z) && rrb(x, z) >= BOWL_EDGE;
        }

        /**
         * Stalagmites are mounds of the floor, steepest one block per step like everything else underfoot, so a
         * crevice between two of them is never a pit: pyramids (4 to 7 blocks across) rooted on a coarse grid.
         */
        int moundAt(int x, int z) {
            int best = 0;
            int gx = Math.floorDiv(x, 6), gz = Math.floorDiv(z, 6);
            for (int a = gx - 1; a <= gx + 1; a++) {
                for (int b = gz - 1; b <= gz + 1; b++) {
                    long h = p.hash(a, 41, b, 1335);
                    if (Math.floorMod(h, 4L) != 0) continue;
                    int rx = a * 6 + (int) Math.floorMod(h >>> 8, 6L), rz = b * 6 + (int) Math.floorMod(h >>> 16, 6L);
                    int hgt = 2 + (int) Math.floorMod(h >>> 24, 3L);
                    int d = Math.abs(x - rx) + Math.abs(z - rz);
                    if (d >= hgt || !coneRoot(rx, rz)) continue;
                    best = Math.max(best, hgt - d);
                }
            }
            return best;
        }

        private boolean coneRoot(int x, int z) {
            if (!inside(x, z) || lane(x, z, 3) || cross(x, z) || onPlatform(x, z) || pillarAt(x, z, 3) || rr(x, z) > 0.8) return false;
            int t = terrainAt(x, z);
            return !(lake && t <= lakeTop) && (!chasm || t >= floor);
        }

        /** The ground before stalagmites: terraces, ring, galleries, 1-Lipschitz in a bowl. */
        int terrainAt(int x, int z) {
            if (chasm || forced(x, z)) return baseFloor(x, z);
            long k = posKey(x, z);
            Integer c = floorMemo.get(k);
            if (c != null) return c;
            int best = baseFloor(x, z);
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -(3 - Math.abs(dx)); dz <= 3 - Math.abs(dx); dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int f = baseFloor(x + dx, z + dz) - Math.abs(dx) - Math.abs(dz);
                    if (f > best) best = f;
                }
            }
            floorMemo.put(k, best);
            return best;
        }

        private int baseFloor(int x, int z) {
            long k = posKey(x, z);
            Integer c = baseMemo.get(k);
            if (c != null) return c;
            int f = baseFloorRaw(x, z);
            baseMemo.put(k, f);
            return f;
        }

        private int baseFloorRaw(int x, int z) {
            if (lane(x, z, 2)) return floor;
            double r = rrb(x, z);
            int f;
            if (chasm) {
                if (r < 0.40) {
                    return floor - 14 + (p.noise(x, 0, z, 3.0, 1210) > 0.2 ? 1 : 0);
                }
                f = floor;
            } else {
                double t = Mth.clamp((BOWL_EDGE - r) / 0.52, 0, 1);
                int step = (int) Math.floor(t * 6.99);
                if (step > 0) return floor - step;
                f = floor;
            }
            if (cross(x, z) && r < 0.95) return floor;
            return f + rimWobble(x, z, r) + hump(x, z, r);
        }

        /** The first solid block above the cavern at a column. */
        int ceilAt(int x, int z, int f) {
            double r = Math.min(1.0, rrb(x, z));
            int c = floor + (int) Math.round(height * (1 - 0.74 * r * r) - 1.5 + 2.2 * p.noise(x, 0, z, 9.0, 1205));
            c = Math.max(c, f + 6);
            return Math.min(c, top - 1);
        }

        boolean onPlatform(int x, int z) {
            int dx = x - cx, dz = z - cz;
            return dx * dx + dz * dz <= PLATFORM_R * PLATFORM_R + 1;
        }

        /** The span of a bridge (the cross's arms over the bowl or chasm). */
        boolean bridge(int x, int z) {
            return cross(x, z) && rrb(x, z) < 0.64;
        }

        boolean pillarAt(int x, int z, int margin) {
            for (int[] pl : pillars) {
                int dx = x - pl[0], dz = z - pl[1];
                int r = pl[2] + 1 + margin;
                if (dx * dx + dz * dz <= r * r) return true;
            }
            return false;
        }

        /** Walking level of a column including bridges and the platform, or MIN if it is water, lava or a column. */
        int walk(int x, int z) {
            int f = floorAt(x, z);
            if (onPlatform(x, z) || (bridge(x, z) && f < floor)) return floor;
            if (lake && f <= lakeTop) return Integer.MIN_VALUE;
            return f;
        }

        // ---- palettes

        BlockState rock(int y) {
            return NaturalBuilder.rock(y);
        }

        BlockState floorBlock(int x, int y, int z) {
            int roll = p.roll(x, y, z, 1221, 100);
            double n = p.noise(x, 0, z, 5.0, 1220);
            boolean deep = y < 0;
            return switch (kind) {
                case FUNGAL -> n > 0.25 ? Blocks.MOSS_BLOCK.defaultBlockState() : roll < 14 ? Blocks.PODZOL.defaultBlockState()
                        : roll < 24 ? Blocks.MUD.defaultBlockState() : roll < 32 ? Blocks.ROOTED_DIRT.defaultBlockState() : rock(y);
                case CRYSTAL -> roll < 10 ? Blocks.CALCITE.defaultBlockState() : roll < 14 ? Blocks.AMETHYST_BLOCK.defaultBlockState()
                        : roll < 34 ? Blocks.TUFF.defaultBlockState() : roll < 44 ? Blocks.SMOOTH_BASALT.defaultBlockState() : rock(y);
                case FROZEN -> n > 0.15 ? Blocks.SNOW_BLOCK.defaultBlockState() : roll < 25 ? Blocks.PACKED_ICE.defaultBlockState()
                        : roll < 36 ? Blocks.BLUE_ICE.defaultBlockState() : rock(y);
                case EMBER -> roll < 26 ? Blocks.BASALT.defaultBlockState() : roll < 44 ? Blocks.BLACKSTONE.defaultBlockState()
                        : roll < 60 ? Blocks.SMOOTH_BASALT.defaultBlockState() : deep ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
                case FLOODED -> n > 0.3 ? Blocks.MOSS_BLOCK.defaultBlockState() : roll < 14 ? Blocks.GRAVEL.defaultBlockState()
                        : roll < 26 ? Blocks.CLAY.defaultBlockState() : roll < 40 ? (deep ? Blocks.COBBLED_DEEPSLATE : Blocks.ANDESITE).defaultBlockState() : rock(y);
            };
        }

        /** A patch of the cavern's own rock for walls and roof. */
        BlockState wallBlock(int x, int y, int z) {
            int roll = p.roll(x, y, z, 1231, 100);
            return switch (kind) {
                case FUNGAL -> roll < 40 ? Blocks.MOSS_BLOCK.defaultBlockState() : roll < 60 ? Blocks.MUD.defaultBlockState()
                        : roll < 66 ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.DRIPSTONE_BLOCK.defaultBlockState();
                case CRYSTAL -> roll < 34 ? Blocks.CALCITE.defaultBlockState() : roll < 60 ? Blocks.AMETHYST_BLOCK.defaultBlockState()
                        : roll < 66 ? Blocks.BUDDING_AMETHYST.defaultBlockState() : roll < 72 ? Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState()
                        : Blocks.TUFF.defaultBlockState();
                case FROZEN -> roll < 45 ? Blocks.PACKED_ICE.defaultBlockState() : roll < 80 ? Blocks.BLUE_ICE.defaultBlockState()
                        : roll < 88 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.SNOW_BLOCK.defaultBlockState();
                case EMBER -> roll < 40 ? Blocks.BASALT.defaultBlockState() : roll < 70 ? Blocks.BLACKSTONE.defaultBlockState()
                        : roll < 84 ? Blocks.MAGMA_BLOCK.defaultBlockState() : roll < 90 ? Blocks.GLOWSTONE.defaultBlockState()
                        : Blocks.SMOOTH_BASALT.defaultBlockState();
                case FLOODED -> roll < 38 ? Blocks.MOSS_BLOCK.defaultBlockState() : roll < 62 ? Blocks.DRIPSTONE_BLOCK.defaultBlockState()
                        : roll < 70 ? Blocks.CLAY.defaultBlockState() : roll < 76 ? Blocks.SEA_LANTERN.defaultBlockState()
                        : (y < 0 ? Blocks.COBBLED_DEEPSLATE : Blocks.ANDESITE).defaultBlockState();
            };
        }

        BlockState spike(int x, int y, int z) {
            return switch (kind) {
                case FUNGAL, FLOODED -> p.roll(x, y, z, 1241, 3) == 0 ? Blocks.DRIPSTONE_BLOCK.defaultBlockState() : rock(y);
                case CRYSTAL -> p.roll(x, y, z, 1242, 4) == 0 ? Blocks.CALCITE.defaultBlockState() : Blocks.DRIPSTONE_BLOCK.defaultBlockState();
                case FROZEN -> p.roll(x, y, z, 1243, 3) == 0 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
                case EMBER -> p.roll(x, y, z, 1244, 3) == 0 ? Blocks.BASALT.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
            };
        }

        BlockState lantern() {
            return kind == Kind.FROZEN ? Blocks.SEA_LANTERN.defaultBlockState() : Deco.lantern(false, false);
        }

        BlockState lichen(Direction face) {
            return Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(face), true);
        }

        // ---- painting

        void run() {
            int x0 = p.minX(), x1 = p.maxX(), z0 = p.minZ(), z1 = p.maxZ();
            int w = x1 - x0 + 3, d = z1 - z0 + 3;
            int[] fl = new int[w * d], ce = new int[w * d];
            for (int ix = 0; ix < w; ix++) {
                for (int iz = 0; iz < d; iz++) {
                    int x = x0 - 1 + ix, z = z0 - 1 + iz;
                    int i = ix * d + iz;
                    if (inside(x, z)) {
                        fl[i] = floorAt(x, z);
                        ce[i] = ceilAt(x, z, fl[i]);
                    } else {
                        fl[i] = Integer.MIN_VALUE;
                        ce[i] = Integer.MIN_VALUE;
                    }
                }
            }
            carve(x0, x1, z0, z1, d, fl, ce);
            walls(x0, x1, z0, z1, d, fl, ce);
            water(x0, x1, z0, z1, d, fl);
            structures(x0, x1, z0, z1);
            dress(x0, x1, z0, z1);
            populate();
        }

        private int idx(int x, int z, int x0, int z0, int d) {
            return (x - x0 + 1) * d + (z - z0 + 1);
        }

        void carve(int x0, int x1, int z0, int z1, int d, int[] fl, int[] ce) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    int i = idx(x, z, x0, z0, d);
                    int f = fl[i];
                    if (f == Integer.MIN_VALUE) continue;
                    int c = ce[i];
                    int nb = Integer.MIN_VALUE;
                    boolean edge = false;
                    for (int k = 0; k < 4; k++) {
                        int j = idx(x + (k == 0 ? 1 : k == 1 ? -1 : 0), z + (k == 2 ? 1 : k == 3 ? -1 : 0), x0, z0, d);
                        if (fl[j] == Integer.MIN_VALUE) edge = true;
                        else nb = Math.max(nb, fl[j]);
                    }
                    int yEnd = Math.min(c - 1, p.maxY());
                    for (int y = Math.max(f, p.minY()); y <= yEnd; y++) {
                        if (edge || y <= nb || y == f || y >= c - 2) p.carve(x, y, z, rock(y));
                        else p.air(x, y, z);
                    }
                    if (p.ok(x, f - 1, z)) {
                        // a stalagmite's mound shows the spike's own rock
                        boolean mound = f > terrainAt(x, z) && !forced(x, z);
                        p.set(x, f - 1, z, mound ? spike(x, f - 1, z) : floorBlock(x, f - 1, z));
                        for (int k = 2; mound && k <= f - terrainAt(x, z); k++) p.setIfOpen(x, f - k, z, spike(x, f - k, z));
                    }
                    if (p.ok(x, f - 2, z) && Painter.open(p.get(x, f - 2, z))) p.set(x, f - 2, z, rock(f - 2));
                    if (p.ok(x, c, z)) {
                        // the roof: its own rock in patches, sealed where it met open ground
                        BlockState above = p.get(x, c, z);
                        if (Painter.open(above) || p.noise(x, c, z, 4.0, 1250) > 0.2) p.set(x, c, z, wallBlock(x, c, z));
                    }
                }
            }
        }

        /** Wall patches of the cavern's rock, and a seal where water meets open ground. */
        void walls(int x0, int x1, int z0, int z1, int d, int[] fl, int[] ce) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    int i = idx(x, z, x0, z0, d);
                    if (fl[i] != Integer.MIN_VALUE || x <= room.minX || x >= room.maxX || z <= room.minZ || z >= room.maxZ) continue;
                    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    for (int k = 0; k < 4; k++) {
                        int j = idx(x + (k == 0 ? 1 : k == 1 ? -1 : 0), z + (k == 2 ? 1 : k == 3 ? -1 : 0), x0, z0, d);
                        if (fl[j] == Integer.MIN_VALUE) continue;
                        lo = Math.min(lo, fl[j]);
                        hi = Math.max(hi, ce[j]);
                    }
                    if (hi == Integer.MIN_VALUE) continue;
                    for (int y = Math.max(lo - 1, p.minY()); y < hi && y <= p.maxY(); y++) {
                        BlockState s = p.get(x, y, z);
                        boolean wet = lake && y <= lakeTop + 1;
                        if (wet && Painter.open(s)) {
                            p.set(x, y, z, rock(y));
                        } else if (s.is(BlockTags.BASE_STONE_OVERWORLD) && p.noise(x, y, z, 3.5, 1260) > 0.12) {
                            p.set(x, y, z, wallBlock(x, y, z));
                        }
                    }
                }
            }
        }

        void water(int x0, int x1, int z0, int z1, int d, int[] fl) {
            if (!lake) return;
            BlockState fluid = (lava ? Blocks.LAVA : Blocks.WATER).defaultBlockState();
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    int f = fl[idx(x, z, x0, z0, d)];
                    if (f == Integer.MIN_VALUE || f > lakeTop) continue;
                    for (int y = Math.max(f, p.minY()); y <= lakeTop && y <= p.maxY(); y++) {
                        BlockState s = fluid;
                        if (y == lakeTop && kind == Kind.FROZEN) s = Blocks.PACKED_ICE.defaultBlockState();
                        else if (y == f && !lava && kind != Kind.FROZEN) {
                            int roll = p.roll(x, y, z, 1270, 40);
                            if (roll < 3) s = Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 4).setValue(SeaPickleBlock.WATERLOGGED, true);
                        }
                        p.set(x, y, z, s);
                    }
                    if (!lava && kind != Kind.FROZEN && p.ok(x, lakeTop + 1, z) && p.roll(x, 0, z, 1271, 28) == 0) {
                        p.set(x, lakeTop + 1, z, Blocks.LILY_PAD.defaultBlockState());
                    }
                    if (p.ok(x, f - 1, z) && p.roll(x, 0, z, 1272, 20) == 0 && (kind == Kind.FLOODED || kind == Kind.FUNGAL || kind == Kind.FROZEN)) {
                        p.set(x, f - 1, z, Blocks.SEA_LANTERN.defaultBlockState());
                    }
                    if (lava && p.ok(x, f - 1, z) && p.roll(x, 0, z, 1273, 5) == 0) p.set(x, f - 1, z, Blocks.MAGMA_BLOCK.defaultBlockState());
                }
            }
        }

        /** Bridges, the platform, columns and the waterfall. */
        void structures(int x0, int x1, int z0, int z1) {
            // bridges: five wide, thick at the abutments, thin in the middle, with a low wall each side
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (!bridge(x, z) || onPlatform(x, z)) continue;
                    int f = floorAt(x, z);
                    if (f >= floor) continue;
                    double along = Math.abs(x - cx) <= 2 ? Math.abs(z - cz) : Math.abs(x - cx);
                    double reach = Math.max(1.0, 0.64 * (Math.abs(x - cx) <= 2 ? ez : ex));
                    int thick = 1 + (int) Math.round(2.0 * Math.pow(Mth.clamp(along / reach, 0, 1), 2));
                    for (int y = Math.max(floor - thick, f); y < floor; y++) {
                        p.set(x, y, z, y == floor - 1 ? floorBlock(x, y, z) : rock(y));
                    }
                    for (int y = floor; y <= floor + 4; y++) p.air(x, y, z);
                    int lat = Math.abs(x - cx) <= 2 && Math.abs(z - cz) > 2 ? Math.abs(x - cx) : Math.abs(z - cz);
                    if (lat == 2) p.set(x, floor, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
                }
            }
            // the platform: an island pier from the bottom of the bowl or chasm up to the rim level
            for (int x = cx - PLATFORM_R - 5; x <= cx + PLATFORM_R + 5; x++) {
                for (int z = cz - PLATFORM_R - 5; z <= cz + PLATFORM_R + 5; z++) {
                    if (!p.ok(x, floor, z)) continue;
                    double dd = Math.sqrt((x - cx) * (double) (x - cx) + (z - cz) * (double) (z - cz));
                    int f = floorAt(x, z);
                    for (int y = Math.max(f, p.minY()); y < floor; y++) {
                        double taper = PLATFORM_R + 0.5 + (floor - 1 - y) * 0.16;
                        if (dd <= taper) p.set(x, y, z, y == floor - 1 ? platformBlock(x, z) : rock(y));
                    }
                    if (dd <= PLATFORM_R + 0.5) {
                        p.set(x, floor - 1, z, platformBlock(x, z));
                        for (int y = floor; y <= floor + 4; y++) p.air(x, y, z);
                    }
                }
            }
            // giant columns, wide where they meet floor and roof
            for (int[] pl : pillars) {
                for (int x = pl[0] - 6; x <= pl[0] + 6; x++) {
                    for (int z = pl[1] - 6; z <= pl[1] + 6; z++) {
                        if (!inside(x, z)) continue;
                        if (x < p.minX() || x > p.maxX() || z < p.minZ() || z > p.maxZ()) continue;
                        double dd = Math.sqrt((x - pl[0]) * (double) (x - pl[0]) + (z - pl[1]) * (double) (z - pl[1]));
                        int f = floorAt(x, z), c = ceilAt(x, z, f);
                        int yMax = Math.min(c, p.maxY());
                        for (int y = Math.max(f, p.minY()); y <= yMax; y++) {
                            double t = c > f ? (double) (y - f) / (c - f) : 0;
                            // a plain shaft at the foot (no skirt to make a pit), waisted in the middle, flaring into the roof
                            double flare = t > 0.6 ? (t - 0.6) / 0.4 : 0;
                            double rad = pl[2] * (1.0 - 0.25 * Math.sin(Math.PI * Math.min(1.0, t / 0.6))) + 0.9 * pl[2] * flare * flare
                                    + 0.4 * p.noise(x, y, z, 3.0, 1280);
                            if (dd <= rad) p.set(x, y, z, spike(x, y, z));
                        }
                    }
                }
            }
            // ladders up the chasm's cliff, so a fall (into the lake) is never the end
            if (chasm) {
                for (int k = 0; k < 3; k++) {
                    double ang = Math.toRadians(45 + 90 * k);
                    int dxs = (int) Math.signum(Math.round(Math.cos(ang) * 10) / 10.0);
                    int dzs = 0;
                    if (dxs == 0) dzs = (int) Math.signum(Math.sin(ang));
                    if (Math.abs(Math.cos(ang)) < Math.abs(Math.sin(ang))) {
                        dxs = 0;
                        dzs = (int) Math.signum(Math.sin(ang));
                    } else {
                        dzs = 0;
                    }
                    Direction toWall = dxs > 0 ? Direction.EAST : dxs < 0 ? Direction.WEST : dzs > 0 ? Direction.SOUTH : Direction.NORTH;
                    int x = cx + (int) Math.round(Math.cos(ang) * 0.25 * ex), z = cz + (int) Math.round(Math.sin(ang) * 0.25 * ez);
                    int px = x, pz = z;
                    for (int s = 0; s < 40 && floorAt(x, z) < floor - 2; s++) {
                        px = x;
                        pz = z;
                        x += dxs;
                        z += dzs;
                    }
                    if (floorAt(x, z) < floor - 2 || !inside(px, pz)) continue;
                    BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, toWall.getOpposite());
                    for (int y = floorAt(px, pz); y < floor; y++) {
                        p.set(px, y, pz, ladder.setValue(LadderBlock.WATERLOGGED, y <= lakeTop));
                    }
                }
            }
            // the waterfall: a spring in the wall high above the floor, falling to the ledges and the lake
            if (waterfall && lake) {
                double cs = Math.cos(wfTheta), sn = Math.sin(wfTheta);
                int sx0 = cx + (int) Math.round(cs * 0.6 * ex), sz0 = cz + (int) Math.round(sn * 0.6 * ez);
                int stepX = Math.abs(cs) >= Math.abs(sn) ? (cs >= 0 ? 1 : -1) : 0;
                int stepZ = stepX == 0 ? (sn >= 0 ? 1 : -1) : 0;
                int xi = sx0, zi = sz0, xe = cx, ze = cz;
                for (int k = 0; k < 40; k++) {
                    int x = sx0 + stepX * k, z = sz0 + stepZ * k;
                    if (!inside(x, z)) {
                        xe = x;
                        ze = z;
                        break;
                    }
                    xi = x;
                    zi = z;
                }
                if (xe != cx || ze != cz) {
                    int fi = floorAt(xi, zi);
                    int y0 = Math.min(ceilAt(xi, zi, fi) - 3, fi + 12);
                    if (y0 > fi + 5 && p.ok(xe, y0, ze)) {
                        p.set(xe, y0, ze, Blocks.WATER.defaultBlockState());
                        for (Direction dir : Direction.Plane.HORIZONTAL) {
                            int sx = xe + dir.getStepX(), sz = ze + dir.getStepZ();
                            if (sx == xi && sz == zi) continue;
                            if (p.ok(sx, y0, sz) && Painter.open(p.get(sx, y0, sz))) p.set(sx, y0, sz, rock(y0));
                        }
                        if (p.ok(xe, y0 + 1, ze)) p.set(xe, y0 + 1, ze, rock(y0 + 1));
                        if (p.ok(xe, y0 - 1, ze)) p.set(xe, y0 - 1, ze, rock(y0 - 1));
                        p.scheduleFluid(xe, y0, ze);
                        p.feature("setpiece:grand_waterfall");
                    }
                }
            }
        }

        BlockState platformBlock(int x, int z) {
            int roll = p.roll(x, 0, z, 1290, 6);
            return switch (kind) {
                case FROZEN -> roll == 0 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.POLISHED_DIORITE.defaultBlockState();
                case EMBER -> roll == 0 ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                case CRYSTAL -> roll == 0 ? Blocks.AMETHYST_BLOCK.defaultBlockState() : Blocks.CALCITE.defaultBlockState();
                default -> roll == 0 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
            };
        }

        /** Where a column's surface is free to dress: dry, off the walkways and the platform, with headroom. */
        boolean free(int x, int z) {
            if (!inside(x, z) || lane(x, z, 2) || cross(x, z) || onPlatform(x, z) || pillarAt(x, z, 0)) return false;
            int f = floorAt(x, z);
            if (lake && f <= lakeTop) return false;
            return ceilAt(x, z, f) - f >= 5;
        }

        void dress(int x0, int x1, int z0, int z1) {
            // stalactites and stalagmites; roots in a margin so spikes crossing a chunk edge agree
            for (int x = x0 - 3; x <= x1 + 3; x++) {
                for (int z = z0 - 3; z <= z1 + 3; z++) {
                    if (!inside(x, z)) continue;
                    long h = p.hash(x, 5, z, 1300);
                    int roll = (int) Math.floorMod(h, 400L);
                    if (roll >= 80) continue;
                    int f = floorAt(x, z), c = ceilAt(x, z, f);
                    boolean wet = lake && f <= lakeTop;
                    if (roll < 24) {
                        // stalactite: only where there is room below it for a walker
                        int len = 2 + (int) Math.floorMod(h >>> 12, 5L) + (c - f) / 9;
                        int clear = wet ? lakeTop + 6 : f + 5;
                        if (lane(x, z, 1)) clear = Math.max(clear, f + 6);
                        len = Math.min(len, c - clear);
                        if (len < 2) continue;
                        cone(x, c - 1, z, len, -1);
                    }
                }
            }
            flora(x0, x1, z0, z1);
            posts();
        }

        /** A spike of {@code len} blocks, 3 wide at the root where long, growing from y in direction dir (1 up, -1 down). */
        void cone(int x, int y, int z, int len, int dir) {
            for (int i = 0; i < len; i++) {
                double rad = (len - i) * 0.34;
                int r = (int) Math.ceil(rad);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz > rad * rad + 0.25) continue;
                        int px = x + dx, pz = z + dz;
                        if (!p.ok(px, y + dir * i, pz)) continue;
                        if (!inside(px, pz)) continue;
                        // never across the walkways
                        if (dir > 0 && (lane(px, pz, 1) || cross(px, pz) || onPlatform(px, pz))) continue;
                        p.set(px, y + dir * i, pz, spike(px, y + dir * i, pz));
                    }
                }
            }
        }

        void flora(int x0, int x1, int z0, int z1) {
            for (int x = x0 - 3; x <= x1 + 3; x++) {
                for (int z = z0 - 3; z <= z1 + 3; z++) {
                    if (!inside(x, z)) continue;
                    long h = p.hash(x, 9, z, 1310);
                    int roll = (int) Math.floorMod(h, 1000L);
                    int f = floorAt(x, z), c = ceilAt(x, z, f);
                    int w = walk(x, z);
                    boolean dry = w != Integer.MIN_VALUE && free(x, z);
                    // hanging things from the roof
                    if (roll < 30 && (kind == Kind.FUNGAL || kind == Kind.FLOODED)) {
                        int base = dry || (w != Integer.MIN_VALUE) ? w : lakeTop;
                        int len = 2 + (int) Math.floorMod(h >>> 10, 7L);
                        len = Math.min(len, c - 1 - (base + 3));
                        for (int i = 0; i < len; i++) {
                            int y = c - 1 - i;
                            BlockState s = i == len - 1 ? Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.BERRIES, true)
                                    : Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(BlockStateProperties.BERRIES, true);
                            p.setIfOpen(x, y, z, s);
                        }
                        continue;
                    }
                    if (!dry) continue;
                    switch (kind) {
                        case FUNGAL -> {
                            if (roll >= 30 && roll < 44) giantMushroom(x, w, z, c, h);
                            else if (roll >= 44 && roll < 120) p.setIfOpen(x, w, z, (roll & 1) == 0 ? Blocks.RED_MUSHROOM.defaultBlockState() : Blocks.BROWN_MUSHROOM.defaultBlockState());
                            else if (roll >= 120 && roll < 200) p.setIfOpen(x, w, z, lichen(Direction.DOWN));
                            else if (roll >= 200 && roll < 260) p.setIfOpen(x, w, z, Blocks.MOSS_CARPET.defaultBlockState());
                        }
                        case CRYSTAL -> {
                            if (roll >= 30 && roll < 46) spire(x, w, z, c, h, false);
                            else if (roll >= 46 && roll < 120) p.setIfOpen(x, w, z, Blocks.AMETHYST_CLUSTER.defaultBlockState()
                                    .setValue(AmethystClusterBlock.FACING, Direction.UP));
                            else if (roll >= 120 && roll < 160) p.setIfOpen(x, w, z, Blocks.LARGE_AMETHYST_BUD.defaultBlockState()
                                    .setValue(AmethystClusterBlock.FACING, Direction.UP));
                        }
                        case FROZEN -> {
                            if (roll >= 30 && roll < 44) spire(x, w, z, c, h, true);
                            else if (roll >= 44 && roll < 140) p.setIfOpen(x, w, z, Blocks.SNOW.defaultBlockState());
                        }
                        case EMBER -> {
                            if (roll >= 30 && roll < 52) {
                                int len = 2 + (int) Math.floorMod(h >>> 8, 4L);
                                for (int i = 0; i < len; i++) p.set(x, w + i, z, i == len - 1 ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.BASALT.defaultBlockState());
                            }
                        }
                        case FLOODED -> {
                            if (roll >= 30 && roll < 110) p.setIfOpen(x, w, z, (roll & 1) == 0 ? lichen(Direction.DOWN) : Blocks.MOSS_CARPET.defaultBlockState());
                            else if (roll >= 110 && roll < 130) p.setIfOpen(x, w, z, Blocks.FERN.defaultBlockState());
                        }
                    }
                }
            }
            // glow in the roof: lights that read from the floor as stars, kinds apart
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int z = z0 - 1; z <= z1 + 1; z++) {
                    if (!inside(x, z) || p.roll(x, 0, z, 1320, 44) != 0) continue;
                    int f = floorAt(x, z), c = ceilAt(x, z, f);
                    BlockState s = switch (kind) {
                        case FUNGAL -> Blocks.SHROOMLIGHT.defaultBlockState();
                        case CRYSTAL -> p.roll(x, 1, z, 1321, 2) == 0 ? Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState() : Blocks.VERDANT_FROGLIGHT.defaultBlockState();
                        case FROZEN -> Blocks.SEA_LANTERN.defaultBlockState();
                        case EMBER -> Blocks.GLOWSTONE.defaultBlockState();
                        case FLOODED -> Blocks.OCHRE_FROGLIGHT.defaultBlockState();
                    };
                    if (p.ok(x, c, z)) p.set(x, c, z, s);
                    if (kind == Kind.EMBER || kind == Kind.CRYSTAL) {
                        // a hanging cluster of the same, low enough to light the floor
                        for (int i = 1; i <= 3 + (int) Math.floorMod(p.hash(x, 2, z, 1322), 3L); i++) {
                            if (c - i > f + 6) p.set(x, c - i, z, s);
                        }
                    }
                }
            }
        }

        void giantMushroom(int x, int w, int z, int c, long h) {
            int stem = 3 + (int) Math.floorMod(h >>> 14, 4L);
            int capY = w + stem;
            if (capY + 2 >= c) return;
            boolean red = ((h >>> 4) & 1) == 0;
            BlockState cap = (red ? Blocks.RED_MUSHROOM_BLOCK : Blocks.BROWN_MUSHROOM_BLOCK).defaultBlockState();
            for (int y = w; y < capY; y++) p.set(x, y, z, Blocks.MUSHROOM_STEM.defaultBlockState());
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    p.set(x + dx, capY, z + dz, cap);
                }
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) p.set(x + dx, capY + 1, z + dz, cap);
            p.set(x, capY - 1, z, Blocks.SHROOMLIGHT.defaultBlockState());
        }

        /** A crystal (or ice) spire with a glowing core and a cluster on top. */
        void spire(int x, int w, int z, int c, long h, boolean ice) {
            int len = 4 + (int) Math.floorMod(h >>> 14, 5L);
            if (w + len + 2 >= c) return;
            BlockState body = (ice ? Blocks.BLUE_ICE : Blocks.AMETHYST_BLOCK).defaultBlockState();
            for (int i = 0; i < len; i++) {
                BlockState s = body;
                if (i == len / 2 && p.roll(x, 3, z, 1330, 2) == 0) s = (ice ? Blocks.SEA_LANTERN : Blocks.PEARLESCENT_FROGLIGHT).defaultBlockState();
                p.set(x, w + i, z, s);
            }
            p.set(x, w + len, z, ice ? Blocks.PACKED_ICE.defaultBlockState()
                    : Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP));
            // a shoulder at the root
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (p.roll(x + dir.getStepX(), 4, z + dir.getStepZ(), 1331, 2) == 0) p.setIfOpen(x + dir.getStepX(), w, z + dir.getStepZ(), body);
            }
        }

        /** Posts of light along the bridges, the platform and the lanes: dark caverns would not be navigable. */
        void posts() {
            int reachX = (int) (0.62 * ex), reachZ = (int) (0.62 * ez);
            int[][] spots = {
                    {cx + 3, cz + 3}, {cx - 3, cz - 3}, {cx + 3, cz - 3}, {cx - 3, cz + 3},
                    {cx + 3, cz + reachZ}, {cx - 3, cz + reachZ}, {cx + 3, cz - reachZ}, {cx - 3, cz - reachZ},
                    {cx + reachX, cz + 3}, {cx + reachX, cz - 3}, {cx - reachX, cz + 3}, {cx - reachX, cz - 3},
                    {cx + 3, cz + (int) (0.82 * ez)}, {cx - 3, cz - (int) (0.82 * ez)},
                    {cx + (int) (0.82 * ex), cz - 3}, {cx - (int) (0.82 * ex), cz + 3}};
            // and a ring of them round the walkway
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
            for (int[] s : all) {
                int x = s[0], z = s[1];
                if (!inside(x, z) || lane(x, z, 2)) continue;
                int w = walk(x, z);
                if (w == Integer.MIN_VALUE || pillarAt(x, z, 0)) continue;
                p.set(x, w, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
                p.set(x, w + 1, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
                p.set(x, w + 2, z, lantern());
            }
        }

        // ---- people and loot

        /** A spot at radius fraction [a, b] of the cavern, at an angle near theta, free to stand on; null if none found. */
        int[] spotNear(double theta, double a, double b, int salt) {
            for (int i = 0; i < 24; i++) {
                double ang = theta + (p.roll(cx, salt, i, 1400, 100) - 50) / 100.0 * 1.1;
                double rad = a + (b - a) * p.roll(cz, salt, i, 1401, 100) / 100.0;
                int x = cx + (int) Math.round(Math.cos(ang) * rad * ex), z = cz + (int) Math.round(Math.sin(ang) * rad * ez);
                if (!free(x, z)) continue;
                return new int[]{x, walk(x, z), z};
            }
            return null;
        }

        int[] spotAny(int salt) {
            double theta = Math.toRadians(p.roll(cx, salt, cz, 1402, 360));
            return spotNear(theta, 0.55, 0.85, salt);
        }

        boolean inhabited() {
            return switch (room.theme()) {
                case CAVE -> CaveRoster.inhabited(CaveRoster.of(room.piece.flavor));
                case MINE -> room.piece.flavor % 2 == 1;
                case FORT, VAMPIRE_LAIR, NECROMANCER_LAIR, HAGRAVEN_LAIR -> true;
                default -> false;
            };
        }

        void populate() {
            String foe = Beats.foe(p, room);
            boolean folk = inhabited();
            int tier = room.tier();
            // the camp (or the den) on the free sector's ledge
            int[] camp = spotNear(campTheta, 0.62, 0.8, 1);
            if (camp != null) {
                int x = camp[0], y = camp[1], z = camp[2];
                if (folk) {
                    p.set(x, y, z, Deco.campfire(kind == Kind.FROZEN || kind == Kind.CRYSTAL, true));
                    put(x + 2, z, y, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
                    carpet(x - 2, y, z);
                    carpet(x, y, z + 2);
                    carpet(x, y, z - 2);
                    p.feature("camp:grand");
                } else {
                    for (int i = 0; i < 5; i++) {
                        int dx = p.roll(x, i, z, 1410, 5) - 2, dz = p.roll(x, i, z, 1411, 5) - 2;
                        if (!free(x + dx, z + dz)) continue;
                        int wy = walk(x + dx, z + dz);
                        BlockState s = i % 3 == 0 ? Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X) : i % 3 == 1
                                ? Deco.skull(Blocks.SKELETON_SKULL, i * 3) : Blocks.COBWEB.defaultBlockState();
                        p.set(x + dx, wy, z + dz, s);
                    }
                    p.feature("camp:den");
                }
                int n = 2 + tier / 3;
                for (int i = 0; i < n; i++) {
                    int[] s = spotNear(campTheta, 0.55, 0.85, 20 + i);
                    if (s == null) continue;
                    spawn(foe, s, !folk || i % 2 == 1);
                }
            }
            // the platform: a chest and a guardian
            p.container(cx, floor, cz, Deco.chest(Direction.NORTH), room.piece.theme == Theme.MINE ? Deco.LOOT_MINE : Deco.LOOT_COMMON);
            int ax = cx + 2, az = cz + 2;
            p.set(ax, floor, az, Deco.candle(Blocks.CANDLE, 3, true));
            p.set(cx - 2, floor, cz - 2, Deco.candle(Blocks.CANDLE, 2, true));
            if (tier >= 3) {
                int[] g = spotAny(30);
                if (g != null) p.spawnElite(foe, g[0], g[1], g[2], p.roll(g[0], 1, g[2], 1420, 360), null);
            }
            // the galleries: the cache, and a minor chest
            for (int k = 0; k < humpCount; k++) {
                int[] s = spotNear(humpTheta[k], 0.74, 0.86, 40 + k);
                if (s == null) continue;
                Direction face = BarrowRooms.towardCenter(room, s[0], s[2]);
                p.container(s[0], s[1], s[2], Deco.chest(face), k == 0 ? Story.relicTable(room.theme(), 1) : Deco.LOOT_MINOR);
                p.set(s[0] + face.getClockWise().getStepX(), s[1], s[2] + face.getClockWise().getStepZ(), lantern());
                if (k == 0) p.feature("reward:cache");
            }
            // sleepers among the spires and a patrol on the ring
            for (int i = 0; i < 3 + tier / 4; i++) {
                int[] s = spotAny(60 + i);
                if (s != null) p.spawnDormant(foe, s[0], s[1], s[2], p.roll(s[0], i, s[2], 1430, 360));
            }
            for (int i = 0; i < 2; i++) {
                int[] s = spotNear(Math.toRadians(p.roll(cx, 90 + i, cz, 1431, 360)), 0.7, 0.9, 80 + i);
                if (s != null) spawn(foe, s, false);
            }
            p.feature("setpiece:grand_" + kind.name().toLowerCase(java.util.Locale.ROOT) + (chasm ? "_chasm" : "_bowl"));
            p.feature("beat:vista");
        }

        void carpet(int x, int y, int z) {
            if (free(x, z)) p.set(x, walk(x, z), z, Blocks.BROWN_CARPET.defaultBlockState());
        }

        void put(int x, int z, int unused, BlockState s, net.minecraft.resources.ResourceLocation loot) {
            if (!free(x, z)) return;
            p.container(x, walk(x, z), z, s, loot);
        }

        void spawn(String id, int[] s, boolean asleep) {
            float yaw = p.roll(s[0], s[1], s[2], 1440, 360);
            if (asleep) p.spawnDormant(id, s[0], s[1], s[2], yaw);
            else p.spawn(id, s[0], s[1], s[2], yaw, null);
        }
    }
}
