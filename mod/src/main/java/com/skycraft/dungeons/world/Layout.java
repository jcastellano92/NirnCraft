package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Roguelike planner. Underground dungeons are a tree of rooms on a grid of {@value #CELL}-block cells around the
 * start room (grown with a "growing tree" walk, plus a loop or two), each joined to its parent by a corridor that
 * may climb or descend with stairs. The deepest room becomes the boss chamber; in barrows and Dwemer ruins the room
 * before it is sealed by a lever gate. The start room connects to the surface through a straight stair tunnel.
 *
 * <p>Everything stays within ~70 blocks of the start chunk so the structure start references cover it.</p>
 */
public final class Layout {
    public static final int CELL = 15;
    private static final int RADIUS = 3;
    private static final int START_HALF = 4;
    private static final int MAX_HALF = 6;

    private Layout() {}

    private static final class Cell {
        final int i, j;
        int hx, hz, floor, height, depth, doors, role = DungeonPiece.ROLE_NORMAL, entered = -1, gate = -1, variant, flags;
        Cell parent;
        final List<Cell> links = new ArrayList<>();

        Cell(int i, int j) {
            this.i = i;
            this.j = j;
        }

        int half(Direction.Axis axis) {
            return axis == Direction.Axis.X ? hx : hz;
        }
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }

    public static int surfaceRadius(Theme theme) {
        return surfaceRadius(Site.of(theme, 0));
    }

    /** Half-width of a surface site's footprint (its piece box reaches this far from the centre). */
    public static int surfaceRadius(Site site) {
        return switch (site.theme()) {
            case FORT -> 15;
            case GIANT_CAMP -> 13;
            case DRAGON_LAIR -> 12;
            case DAEDRIC_SHRINE -> 9;
            case WATCHTOWER -> 9;
            case SHIPWRECK -> 12;
            case GROVE -> 14;
            case BANDIT_CAMP, FORSWORN_CAMP -> 12;
            case MILITARY_CAMP -> 16;
            case CABIN -> 8;
            case LIGHTHOUSE -> 8;
            case LANDMARK -> 10;
            case STANDING_STONE -> site.param() == SiteParams.GUARDIANS ? 10 : 7;
            case WORD_WALL -> 8;
            case DRAGON_MOUND -> 7;
            case NORDIC_RUIN -> 10;
            case GREAT_LIFT -> 7;
            case PRINCE_SHRINE -> site.is(Special.AZURA) || site.is(Special.MERIDIA) ? 24 : 11;
            case MAGE_TOWER -> 8;
            case HIGH_HROTHGAR -> 38;
            case THROAT_SUMMIT -> 14;
            case SKY_HAVEN -> 26;
            case LABYRINTHIAN -> 56;
            default -> 8;
        };
    }

    /** Blocks the surface piece reaches above its ground level. */
    private static int surfaceAbove(Site site) {
        return switch (site.theme()) {
            case FORT -> 24;
            case DAEDRIC_SHRINE -> 20;
            case DRAGON_LAIR -> 16;
            case WATCHTOWER -> 26;
            case LIGHTHOUSE -> 30;
            case GREAT_LIFT -> 18;
            case PRINCE_SHRINE -> site.is(Special.AZURA) || site.is(Special.MERIDIA) ? 56 : 24;
            case MAGE_TOWER -> 32;
            case HIGH_HROTHGAR -> 40;
            case SKY_HAVEN -> 32;
            case LABYRINTHIAN -> 30;
            case THROAT_SUMMIT -> 20;
            case NORDIC_RUIN, LANDMARK, GROVE -> 18;
            default -> 14;
        };
    }

    /** Blocks the surface piece reaches below its ground level (foundations, cellars, wells). */
    private static int surfaceBelow(Site site) {
        return switch (site.theme()) {
            case FORT -> 14;
            case GREAT_LIFT -> 96;
            case SHIPWRECK -> 14;
            case SKY_HAVEN, HIGH_HROTHGAR, PRINCE_SHRINE -> 24;
            case LABYRINTHIAN -> 16;
            default -> 12;
        };
    }

    // ------------------------------------------------------------------ surface sites

    public static void surface(StructurePiecesBuilder builder, Theme theme, int x, int y, int z, Direction facing, long seed, int flavor) {
        surface(builder, Site.of(theme, flavor), x, y, z, facing, seed, false);
    }

    /**
     * One surface piece for the whole site.
     *
     * @param pad the structure uses terrain adaptation: the piece box starts at ground level so the beardifier levels
     *            the ground to it (and foundations are left to the terrain)
     */
    public static void surface(StructurePiecesBuilder builder, Site site, int x, int y, int z, Direction facing, long seed, boolean pad) {
        int r = surfaceRadius(site);
        int below = pad ? 1 : surfaceBelow(site);
        int above = surfaceAbove(site);
        BoundingBox box = new BoundingBox(x - r, y - below, z - r, x + r, y + above, z + r);
        builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.SURFACE, box, seed, y, y, facing.get2DDataValue(), 0,
                DungeonPiece.ROLE_NORMAL, 0, 0, pad ? DungeonPiece.FLAG_PAD : 0));
    }

    /**
     * A Great Lift: the Dwemer lift house on the surface and its shaft straight down into Blackreach. One piece
     * from the cavern floor to the top of the lift house.
     */
    public static void greatLift(StructurePiecesBuilder builder, Site site, int x, int y, int z, Direction facing, long seed) {
        int r = surfaceRadius(site);
        int bottom = Math.min(y - 8, Blackreach.FLOOR_Y - 2);
        BoundingBox box = new BoundingBox(x - r, bottom, z - r, x + r, y + surfaceAbove(site), z + r);
        builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.SURFACE, box, seed, y, y, facing.get2DDataValue(), 0,
                DungeonPiece.ROLE_NORMAL, 0, 0, 0));
    }

    /**
     * The grounds of a named barrow or mine around its entrance (Bleak Falls' temple, Saarthal's dig, Forelhost's
     * terraces...): one surface piece reaching out in front of the portal. {@link Exteriors} leaves the portal's own
     * approach alone.
     */
    public static void exterior(StructurePiecesBuilder builder, Site site, int x, int y, int z, Direction out, long seed) {
        int r = EXTERIOR_RADIUS;
        int cx = x + out.getStepX() * (r - 6), cz = z + out.getStepZ() * (r - 6);
        BoundingBox box = new BoundingBox(cx - r, y - 16, cz - r, cx + r, y + 30, cz + r);
        builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.SURFACE, box, seed ^ 0x2545F491L, y, y, out.get2DDataValue(),
                0, DungeonPiece.ROLE_NORMAL, x, z, 0));
    }

    public static final int EXTERIOR_RADIUS = 24;

    // ------------------------------------------------------------------ underground dungeons

    /**
     * @param ex,ez     surface end of the entrance tunnel
     * @param surfaceY  walking level at the entrance
     * @param out       direction from the start room towards the entrance
     * @param minFloor  lowest walking level any room may use
     * @param openTop   fort: the tunnel opens into the keep floor instead of a portal
     */
    public static void underground(StructurePiecesBuilder builder, Theme theme, int ex, int surfaceY, int ez, Direction out,
                                   int depth, long seed, int minFloor, int flavor, boolean openTop) {
        underground(builder, Site.of(theme, flavor), ex, surfaceY, ez, out, depth, seed, minFloor, openTop);
    }

    public static void underground(StructurePiecesBuilder builder, Site site, int ex, int surfaceY, int ez, Direction out,
                                   int depth, long seed, int minFloor, boolean openTop) {
        Theme theme = site.theme();
        RandomSource r = RandomSource.create(seed);
        int floor0 = surfaceY - depth;
        int tunnelLen = depth + 4;
        Direction.Axis axis = out.getAxis();
        int eAxis = axis == Direction.Axis.X ? ex : ez;
        int lateral = axis == Direction.Axis.X ? ez : ex;
        int step = out.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        int wall = eAxis - step * tunnelLen;
        int startAxis = wall - step * START_HALF;
        int ox = axis == Direction.Axis.X ? startAxis : lateral;
        int oz = axis == Direction.Axis.X ? lateral : startAxis;

        Map<Long, Cell> grid = new HashMap<>();
        Set<Long> reserved = new HashSet<>();
        int reach = (tunnelLen + START_HALF + 17 + CELL - 1) / CELL;
        for (int k = 1; k <= reach; k++) reserved.add(key(out.getStepX() * k, out.getStepZ() * k));

        Cell start = new Cell(0, 0);
        start.hx = start.hz = START_HALF;
        start.floor = floor0;
        start.height = Math.min(theme.maxHeight, theme.minHeight + 1);
        start.role = DungeonPiece.ROLE_START;
        start.doors |= 1 << out.get2DDataValue();
        start.variant = r.nextInt(8);
        grid.put(key(0, 0), start);
        List<Cell> cells = new ArrayList<>();
        cells.add(start);

        int target = theme.minRooms + r.nextInt(theme.maxRooms - theme.minRooms + 1) + (site.special() != Special.NONE ? 3 : 0);
        int maxFloor = floor0 + 2;
        for (int attempt = 0; attempt < 240 && cells.size() < target; attempt++) {
            Cell from = r.nextFloat() < 0.6f ? cells.get(cells.size() - 1 - r.nextInt(Math.min(2, cells.size()))) : cells.get(r.nextInt(cells.size()));
            Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(r);
            if (from == start && d == out) continue;
            int ni = from.i + d.getStepX(), nj = from.j + d.getStepZ();
            if (Math.abs(ni) > RADIUS || Math.abs(nj) > RADIUS) continue;
            long k = key(ni, nj);
            if (grid.containsKey(k) || reserved.contains(k)) continue;
            Cell c = new Cell(ni, nj);
            c.hx = 3 + r.nextInt(MAX_HALF - 2);
            c.hz = 3 + r.nextInt(MAX_HALF - 2);
            c.height = theme.minHeight + r.nextInt(theme.maxHeight - theme.minHeight + 1);
            int gap = CELL - from.half(d.getAxis()) - c.half(d.getAxis()) - 1;
            int maxDy = Math.max(0, Math.min(3, gap - 1));
            int dy = 0;
            if (maxDy > 0) {
                int roll = r.nextInt(10);
                if (roll < 5) dy = -1 - r.nextInt(maxDy);          // dungeons lead downwards
                else if (roll < 7) dy = 1 + r.nextInt(Math.min(2, maxDy));
            }
            c.floor = Math.max(minFloor, Math.min(maxFloor, from.floor + dy));
            if (Math.abs(c.floor - from.floor) > maxDy) c.floor = from.floor;
            c.depth = from.depth + 1;
            c.parent = from;
            c.entered = d.getOpposite().get2DDataValue();
            c.variant = r.nextInt(8);
            link(from, c, d);
            grid.put(k, c);
            cells.add(c);
        }

        // boss: the deepest room (a leaf of the tree)
        Cell boss = null;
        for (Cell c : cells) if (c != start && (boss == null || c.depth > boss.depth)) boss = c;
        if (boss != null) {
            boss.role = DungeonPiece.ROLE_BOSS;
            Direction toParent = Direction.from2DDataValue(boss.entered);
            boss.hx = boss.hz = MAX_HALF;
            boss.height = theme.natural() ? theme.maxHeight + 2 : theme.maxHeight + 3;
            int gap = CELL - boss.parent.half(toParent.getAxis()) - MAX_HALF - 1;
            int maxDy = Math.max(0, gap - 1);
            int dy = boss.floor - boss.parent.floor;
            if (Math.abs(dy) > maxDy) boss.floor = boss.parent.floor + Integer.signum(dy) * maxDy;
            if (theme.puzzle && boss.parent != start) {
                Cell gateRoom = boss.parent;
                gateRoom.role = DungeonPiece.ROLE_PUZZLE;
                gateRoom.gate = toParent.getOpposite().get2DDataValue();
            }
        }

        // a loop or two so it isn't strictly linear
        for (int attempt = 0; attempt < 6; attempt++) {
            Cell a = cells.get(r.nextInt(cells.size()));
            Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(r);
            Cell b = grid.get(key(a.i + d.getStepX(), a.j + d.getStepZ()));
            if (b == null || a == boss || b == boss || a.links.contains(b)) continue;
            if ((a == start || b == start) && cells.size() > 3 && r.nextBoolean()) continue;
            int gap = CELL - a.half(d.getAxis()) - b.half(d.getAxis()) - 1;
            if (Math.abs(a.floor - b.floor) > gap - 1) continue;
            link(a, b, d);
            if (attempt >= 2) break;
        }

        // dead ends and treasure
        List<Cell> candidates = new ArrayList<>();
        for (Cell c : cells) {
            if (c.role != DungeonPiece.ROLE_NORMAL) continue;
            if (c.links.size() == 1) c.flags |= DungeonPiece.FLAG_DEAD_END;
            candidates.add(c);
        }
        candidates.sort((p, q) -> Integer.compare(q.flags & DungeonPiece.FLAG_DEAD_END, p.flags & DungeonPiece.FLAG_DEAD_END));
        int treasures = Math.min(candidates.size(), 1 + r.nextInt(2));
        for (int t = 0; t < treasures; t++) candidates.get(t).flags |= DungeonPiece.FLAG_TREASURE;

        // ---- emit pieces
        boolean natural = theme.natural();
        int lat = natural ? 3 : 2;
        int head = natural ? 4 : 3;

        // tunnel
        {
            int a1 = wall + step, a2 = wall + step * tunnelLen;
            BoundingBox box = axisBox(axis, a1, a2, lateral - lat, lateral + lat, floor0 - 1, surfaceY + head);
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.TUNNEL, box, r.nextLong(), floor0, surfaceY,
                    out.get2DDataValue(), depth, DungeonPiece.ROLE_NORMAL, lateral, wall, openTop ? DungeonPiece.FLAG_OPEN_TOP : 0));
        }
        if (!openTop) {
            // culture ruins raise bigger facades (pyramids, hollow trees, spires) over the stairs
            boolean big = theme == Theme.RUIN || site.kit() == Kit.ATMORAN;
            int a1 = wall + step * (tunnelLen - (big ? 12 : 3)), a2 = wall + step * (tunnelLen + 9);
            int w = big ? 8 : 6;
            BoundingBox box = axisBox(axis, a1, a2, lateral - w, lateral + w, surfaceY - 10, surfaceY + (big ? 20 : 10));
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.PORTAL, box, r.nextLong(), floor0, surfaceY,
                    out.get2DDataValue(), depth, DungeonPiece.ROLE_NORMAL, lateral, wall, 0));
        }

        // rooms
        for (Cell c : cells) {
            int cx = ox + c.i * CELL, cz = oz + c.j * CELL;
            BoundingBox box = new BoundingBox(cx - c.hx, c.floor - 1, cz - c.hz, cx + c.hx, c.floor + c.height, cz + c.hz);
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.ROOM, box, r.nextLong(), c.floor, 0, c.entered, c.gate,
                    c.role, c.doors, c.variant, c.flags));
        }

        // corridors (each link once: from the lower-coordinate cell)
        for (Cell a : cells) {
            for (Cell b : a.links) {
                if (b.i < a.i || b.j < a.j) continue;
                int ax = ox + a.i * CELL, az = oz + a.j * CELL;
                int bx = ox + b.i * CELL, bz = oz + b.j * CELL;
                BoundingBox box;
                int axisId;
                if (b.i != a.i) {
                    int x1 = ax + a.hx + 1, x2 = bx - b.hx - 1;
                    if (x2 < x1) continue;
                    box = new BoundingBox(x1, Math.min(a.floor, b.floor) - 1, az - lat, x2, Math.max(a.floor, b.floor) + head, az + lat);
                    axisId = 0;
                } else {
                    int z1 = az + a.hz + 1, z2 = bz - b.hz - 1;
                    if (z2 < z1) continue;
                    box = new BoundingBox(ax - lat, Math.min(a.floor, b.floor) - 1, z1, ax + lat, Math.max(a.floor, b.floor) + head, z2);
                    axisId = 1;
                }
                int flags = 0;
                boolean flat = a.floor == b.floor;
                int len = axisId == 0 ? box.getXSpan() : box.getZSpan();
                if (!natural && flat && len >= 3 && r.nextInt(10) < 3) flags |= DungeonPiece.FLAG_TRAP;
                builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.CORRIDOR, box, r.nextLong(), a.floor, b.floor, axisId, 0,
                        DungeonPiece.ROLE_NORMAL, 0, r.nextInt(8), flags));
            }
        }
    }

    private static void link(Cell a, Cell b, Direction d) {
        a.links.add(b);
        b.links.add(a);
        a.doors |= 1 << d.get2DDataValue();
        b.doors |= 1 << d.getOpposite().get2DDataValue();
    }

    private static BoundingBox axisBox(Direction.Axis axis, int a1, int a2, int l1, int l2, int y1, int y2) {
        int amin = Math.min(a1, a2), amax = Math.max(a1, a2);
        if (axis == Direction.Axis.X) return new BoundingBox(amin, y1, l1, amax, y2, l2);
        return new BoundingBox(l1, y1, amin, l2, y2, amax);
    }
}
