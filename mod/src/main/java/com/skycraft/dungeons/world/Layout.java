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
 * start room (grown with a "growing tree" walk, plus loops), each joined to its parent by a corridor that may climb or
 * descend with stairs. The deepest room becomes the boss chamber; in barrows and Dwemer ruins the room before it is
 * sealed by a lever gate. The start room connects to the surface through a straight stair tunnel. See
 * {@link #underground(StructurePiecesBuilder, Site, int, int, int, Direction, int, long, int, boolean, Ground)} for how
 * a medium dungeon's candidates, beats and cycles are chosen.
 *
 * <p>Everything stays within ~70 blocks of the start chunk so the structure start references cover it.</p>
 */
public final class Layout {
    public static final int CELL = 15;
    private static final int RADIUS = 3;
    private static final int START_HALF = 4;
    private static final int MAX_HALF = 6;
    /** The entrance hall of a medium dungeon: its half size, and how far its floor lies below the entrance landing. */
    private static final int VISTA_HALF = 6;
    public static final int VISTA_LIFT = 2;
    /** Distance from the boss chamber's wall to the centre of the back exit's shaft, beyond its half size. */
    private static final int SHAFT_OFFSET = 6;

    private Layout() {}

    private static final class Cell {
        final int i, j;
        int hx, hz, floor, height, depth, doors, role = DungeonPiece.ROLE_NORMAL, entered = -1, gate = -1, variant, flags, vista;
        /** Grand caverns take (2 * span + 1) grid cells along each axis (0 = an ordinary room). */
        int sx, sz;
        Cell parent;
        final List<Cell> links = new ArrayList<>();

        Cell(int i, int j) {
            this.i = i;
            this.j = j;
        }

        int half(Direction.Axis axis) {
            return axis == Direction.Axis.X ? hx : hz;
        }

        int span(Direction.Axis axis) {
            return axis == Direction.Axis.X ? sx : sz;
        }

        boolean grand() {
            return sx > 0 || sz > 0;
        }
    }

    /** Half size of a grand cavern spanning {@code span} extra cells on each side. */
    private static int grandHalf(int span) {
        return span * CELL + MAX_HALF;
    }

    /**
     * The room next to {@code a} on side {@code d} whose centre lies on a's row or column and whose wall faces a's, or
     * null (grand caverns fill several grid cells, so the neighbour may be further than one cell away).
     */
    private static Cell adjacent(Map<Long, Cell> grid, Cell a, Direction d) {
        Direction.Axis axis = d.getAxis();
        int step = a.span(axis) + 1;
        Cell b = grid.get(key(a.i + d.getStepX() * step, a.j + d.getStepZ() * step));
        if (b == null || b == a) return null;
        if (axis == Direction.Axis.X ? b.j != a.j : b.i != a.i) return null;
        int dist = Math.abs(axis == Direction.Axis.X ? b.i - a.i : b.j - a.j);
        return dist == a.span(axis) + b.span(axis) + 1 ? b : null;
    }

    /** Blocks of corridor between the walls of two aligned rooms. */
    private static int gap(Cell a, Cell b) {
        Direction.Axis axis = a.i != b.i ? Direction.Axis.X : Direction.Axis.Z;
        int dist = Math.abs(axis == Direction.Axis.X ? b.i - a.i : b.j - a.j);
        return dist * CELL - a.half(axis) - b.half(axis) - 1;
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
        underground(builder, site, ex, surfaceY, ez, out, depth, seed, minFloor, openTop, null);
    }

    /** Surface height probe for the back exit's shaft (noise heights only); {@link Integer#MIN_VALUE} = water. */
    public interface Ground {
        int surface(int x, int z);
    }

    /** One candidate layout: the room tree on the grid, its main path and the RNG that will finish it. */
    private static final class Plan {
        final RandomSource r;
        final Map<Long, Cell> grid = new HashMap<>();
        final List<Cell> cells = new ArrayList<>();
        /** Entrance hall to boss chamber, in order. */
        final List<Cell> path = new ArrayList<>();
        Cell start, boss;
        double score;
        /** Grand caverns still to place. */
        int grandLeft;

        Plan(long seed) {
            this.r = RandomSource.create(seed);
        }
    }

    /**
     * Plans a whole underground dungeon the way Skyrim's are built: mission first, then space.
     * <ol>
     *     <li><b>Candidates.</b> {@value #CANDIDATES} room trees are grown from the seed. Each is scored for fun (a long
     *     enough main path, a side branch, a loop, height changes, few unrewarded dead ends) and for how well it fits
     *     the niche the seed asks for (linear or branching, flat or vertical: a MAP-Elites style grid, so neighbouring
     *     dungeons differ in shape). The best one is kept.</li>
     *     <li><b>Beats.</b> Along its main path: the entrance vista, light combat, a trap corridor, an ambush, the set
     *     piece, an elite guard before the gate, the puzzle gate (barrows, Dwemer and culture ruins), the boss chamber
     *     and its reward, and the one-way back door to a ladder shaft (the loop-back exit). Every room knows how deep it
     *     lies (tier 0..7) so the danger ramps.</li>
     *     <li><b>Cycles.</b> Besides the shortcut exit: a loop between branches (two ways round), and a barred window
     *     from a room beside the boss chamber, which shows the boss before the way in is found (foreshadowing).</li>
     *     <li><b>Off the path.</b> The first side room holds a cache, another an adventurer's last journal, and no dead
     *     end is left empty.</li>
     * </ol>
     *
     * @param ground surface probe for the back exit, or null for none
     */
    public static void underground(StructurePiecesBuilder builder, Site site, int ex, int surfaceY, int ez, Direction out,
                                   int depth, long seed, int minFloor, boolean openTop, Ground ground) {
        underground(builder, site, ex, surfaceY, ez, out, depth, seed, minFloor, openTop, ground, 0);
    }

    /**
     * @param extraRooms rooms added to the theme's roll (named dungeons are the big ones; random sites stay small)
     */
    public static void underground(StructurePiecesBuilder builder, Site site, int ex, int surfaceY, int ez, Direction out,
                                   int depth, long seed, int minFloor, boolean openTop, Ground ground, int extraRooms) {
        Theme theme = site.theme();
        RandomSource base = RandomSource.create(seed);
        int target = theme.minRooms + base.nextInt(theme.maxRooms - theme.minRooms + 1) + (site.special() != Special.NONE ? 3 : 0)
                + extraRooms;
        boolean medium = target >= 5;
        // the vista: a bigger, taller entrance hall entered from a raised landing
        boolean vista = medium && !openTop;
        int startHalf = vista ? VISTA_HALF : START_HALF;
        int floor0 = surfaceY - depth;
        int tunnelLen = depth + 4;
        Direction.Axis axis = out.getAxis();
        int eAxis = axis == Direction.Axis.X ? ex : ez;
        int lateral = axis == Direction.Axis.X ? ez : ex;
        int step = out.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        int wall = eAxis - step * tunnelLen;

        // grand caverns: one from 8 rooms, two from 12; a natural dungeon's entrance may open onto the first, so the
        // way in takes in the whole cavern from a ledge
        int grands = target >= 12 ? 2 : target >= 8 ? 1 : 0;
        if (theme == Theme.FORT) grands = Math.min(grands, 1);
        boolean startGrand = false;
        int startHeight = 0;
        GrandProbe probe = new GrandProbe(ground, surfaceY);
        if (grands > 0 && theme.natural() && !openTop && depth <= 36 && base.nextInt(2) == 0) {
            int sh = grandHalf(1);
            int sa = wall - step * sh;
            int sox = axis == Direction.Axis.X ? sa : lateral, soz = axis == Direction.Axis.X ? lateral : sa;
            int h = probe.height(sox, soz, floor0);
            if (h >= GrandCavern.MIN_HEIGHT && floor0 - GrandCavern.BELOW >= minFloor) {
                startGrand = true;
                startHeight = h;
                startHalf = sh;
                vista = false;
                grands--;
            }
        }
        int startAxis = wall - step * startHalf;
        int ox = axis == Direction.Axis.X ? startAxis : lateral;
        int oz = axis == Direction.Axis.X ? lateral : startAxis;
        probe.origin(ox, oz);
        Set<Long> reserved = new HashSet<>();
        int reach = (tunnelLen + startHalf + 17 + CELL - 1) / CELL;
        for (int k = 1; k <= reach; k++) reserved.add(key(out.getStepX() * k, out.getStepZ() * k));

        // the niche this dungeon should fill: branching (vs linear) and vertical (vs flat)
        int niche = base.nextInt(4);
        Plan plan = null;
        for (int k = 0; k < (medium ? CANDIDATES : 1); k++) {
            Plan c = grow(site, seed + k * 0x9E3779B97F4A7C15L, target, vista, startHalf, floor0, minFloor, out, reserved,
                    startGrand ? startHeight : 0, grands, probe);
            c.score = medium ? score(c, niche) : 0;
            if (plan == null || c.score > plan.score) plan = c;
        }
        RandomSource r = plan.r;
        List<Cell> cells = plan.cells;
        Map<Long, Cell> grid = plan.grid;
        Cell start = plan.start, boss = plan.boss;
        List<Cell> path = plan.path;
        Set<Cell> onPath = new HashSet<>(path);

        // loops so it isn't strictly linear (a big dungeon gets up to three): prefer joining a side branch to the main path
        int loopsWanted = cells.size() >= 12 ? 3 : cells.size() >= 8 ? 2 : 1;
        int loopAttempts = 12 * loopsWanted;
        for (int attempt = 0; attempt < loopAttempts && loopsWanted > 0; attempt++) {
            Cell a = cells.get(r.nextInt(cells.size()));
            Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(r);
            Cell b = adjacent(grid, a, d);
            if (b == null || a == boss || b == boss || a.links.contains(b)) continue;
            if (attempt % 12 < 8 && onPath.contains(a) == onPath.contains(b)) continue;
            if ((a == start || b == start) && cells.size() > 3 && r.nextBoolean()) continue;
            int gap = gap(a, b);
            if (gap < 1 || Math.abs(a.floor - b.floor) > gap - 1) continue;
            link(a, b, d);
            loopsWanted--;
        }
        // escalation: each room knows how deep it lies (tier 0..7), so its builder can ramp up the danger
        int deepest = 1;
        for (Cell c : cells) deepest = Math.max(deepest, c.depth);
        for (Cell c : cells) c.variant = (c.variant & 15) | (Math.min(7, Math.round(c.depth * 7f / deepest)) << 4);

        // the beats along the main path (mission first): ambush, set piece, the elite before the gate
        List<Cell> inner = new ArrayList<>();
        for (Cell c : path) if (c.role == DungeonPiece.ROLE_NORMAL && !c.grand()) inner.add(c);
        Cell setPiece = null, ambush = null, elite = null;
        if (medium && !inner.isEmpty()) {
            // the set piece: the roomiest room in the middle stretch
            for (int i = 0; i < inner.size(); i++) {
                double f = (i + 1.0) / (inner.size() + 1.0);
                Cell c = inner.get(i);
                if (f < 0.34 && inner.size() > 2) continue;
                if (setPiece == null || c.hx * c.hz > setPiece.hx * setPiece.hz) setPiece = c;
            }
            elite = inner.get(inner.size() - 1);
            if (elite == setPiece && inner.size() > 1) elite = inner.get(inner.size() - 2);
            for (Cell c : inner) if (c != setPiece && c != elite && ambush == null && inner.indexOf(c) >= inner.size() / 3) ambush = c;
            if (setPiece != null) setPiece.flags |= DungeonPiece.FLAG_SETPIECE;
            if (elite != null && elite != setPiece) elite.flags |= DungeonPiece.FLAG_ELITE;
            if (ambush != null) ambush.flags |= DungeonPiece.FLAG_AMBUSH;
        }

        // off the path: the cache and the story, and no empty dead end
        List<Cell> side = new ArrayList<>();
        List<Cell> candidates = new ArrayList<>();
        for (Cell c : cells) {
            if (c.role != DungeonPiece.ROLE_NORMAL || c.grand()) continue;
            if (c.links.size() == 1) c.flags |= DungeonPiece.FLAG_DEAD_END;
            candidates.add(c);
            if (!onPath.contains(c)) side.add(c);
        }
        side.sort((p, q) -> Integer.compare(q.flags & DungeonPiece.FLAG_DEAD_END, p.flags & DungeonPiece.FLAG_DEAD_END));
        if (!side.isEmpty()) side.get(0).flags |= DungeonPiece.FLAG_TREASURE | DungeonPiece.FLAG_CACHE;
        if (medium) {
            Cell story = side.size() > 1 ? side.get(1) : !side.isEmpty() ? side.get(0) : null;
            if (story == null && !inner.isEmpty()) story = inner.get(0);
            if (story != null) story.flags |= DungeonPiece.FLAG_STORY;
        }
        for (Cell c : candidates) {
            if ((c.flags & DungeonPiece.FLAG_DEAD_END) != 0 && (c.flags & (DungeonPiece.FLAG_CACHE | DungeonPiece.FLAG_STORY)) == 0) {
                c.flags |= DungeonPiece.FLAG_TREASURE;
            }
        }
        if (candidates.stream().noneMatch(c -> (c.flags & DungeonPiece.FLAG_TREASURE) != 0) && !candidates.isEmpty()) {
            candidates.get(r.nextInt(candidates.size())).flags |= DungeonPiece.FLAG_TREASURE;
        }

        // the loop-back: a one-way door out of the boss chamber to a ladder shaft up to the surface
        int[] exitShaft = null;
        if (boss != null && ground != null) {
            Direction front = Direction.from2DDataValue(boss.entered).getOpposite();
            List<Direction> sides = new ArrayList<>(List.of(front.getClockWise(), front.getCounterClockWise()));
            if (r.nextBoolean()) java.util.Collections.reverse(sides);
            // caverns have no dais against the far wall: the back way may leave from there too
            if (theme.natural()) sides.add(front);
            for (Direction d : sides) {
                if ((boss.doors & (1 << d.get2DDataValue())) != 0) continue;
                long k = key(boss.i + d.getStepX(), boss.j + d.getStepZ());
                if (grid.containsKey(k) || reserved.contains(k)) continue;
                // the shaft stands a cell out from the boss chamber: nothing may have been built there
                long k2 = key(boss.i + d.getStepX() * 2, boss.j + d.getStepZ() * 2);
                if (grid.containsKey(k2) && grid.get(k2).grand()) continue;
                int bx = ox + boss.i * CELL, bz = oz + boss.j * CELL;
                int sx = bx + d.getStepX() * (MAX_HALF + SHAFT_OFFSET), sz = bz + d.getStepZ() * (MAX_HALF + SHAFT_OFFSET);
                int top = ground.surface(sx, sz);
                if (top == Integer.MIN_VALUE || top - boss.floor < 4 || top - boss.floor > 72) continue;
                boss.doors |= 1 << d.get2DDataValue();
                boss.gate = d.get2DDataValue();
                exitShaft = new int[]{d.get2DDataValue(), sx, sz, top};
                break;
            }
        }
        // no room for a shaft: a one-way door from the boss chamber back into the shallowest room beside it
        Cell shortcut = null;
        Direction shortcutDir = null;
        if (boss != null && exitShaft == null && medium) {
            Direction front = Direction.from2DDataValue(boss.entered).getOpposite();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (d == front || (boss.doors & (1 << d.get2DDataValue())) != 0) continue;
                Cell n = adjacent(grid, boss, d);
                if (n == null || n.role == DungeonPiece.ROLE_PUZZLE || boss.links.contains(n)) continue;
                int gap = gap(boss, n);
                if (gap < 1 || Math.abs(boss.floor - n.floor) > gap - 1) continue;
                if (shortcut == null || n.depth < shortcut.depth) {
                    shortcut = n;
                    shortcutDir = d;
                }
            }
            if (shortcut != null) {
                link(boss, shortcut, shortcutDir);
                boss.gate = shortcutDir.get2DDataValue();
            }
        }

        // foreshadowing: a barred window into the boss chamber from a room beside it (seen early, reached last)
        Cell window = null;
        Direction windowDir = null;
        if (boss != null && medium) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Cell n = adjacent(grid, boss, d);
                if (n == null || n == boss.parent || boss.links.contains(n) || n.role == DungeonPiece.ROLE_PUZZLE || n.grand()) continue;
                // never through the far wall, where the boss's dais and the Word Wall stand
                if (d == Direction.from2DDataValue(boss.entered).getOpposite()) continue;
                int gap = gap(boss, n);
                if (gap < 1 || Math.abs(boss.floor - n.floor) > gap - 1) continue;
                window = n;
                windowDir = d;
                break;
            }
            if (window != null) {
                boss.doors |= 1 << windowDir.get2DDataValue();
                window.doors |= 1 << windowDir.getOpposite().get2DDataValue();
            }
        }

        // ---- emit pieces
        boolean natural = theme.natural();
        int lat = natural ? 3 : 2;
        int head = natural ? 4 : 3;
        // every dungeon may hold a trap: tomb builders' darts, bandits' and old hunters' triplines
        boolean trapsHere = true;

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
            int a1 = wall + step * (tunnelLen - (big ? 12 : 3)), a2 = wall + step * (tunnelLen + Portals.APPROACH);
            int w = big ? 8 : 6;
            BoundingBox box = axisBox(axis, a1, a2, lateral - w, lateral + w, surfaceY - 24, surfaceY + (big ? 20 : 10));
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.PORTAL, box, r.nextLong(), floor0, surfaceY,
                    out.get2DDataValue(), depth, DungeonPiece.ROLE_NORMAL, lateral, wall, 0));
        }

        // rooms (a grand cavern also carries its dungeon's tag: which cavern it is, the lattice colour, a key: see CavernTag)
        int grandOrdinal = 0;
        for (Cell c : cells) {
            int cx = ox + c.i * CELL, cz = oz + c.j * CELL;
            int bottom = c.grand() ? c.floor - GrandCavern.BELOW : c.floor - 1;
            BoundingBox box = new BoundingBox(cx - c.hx, bottom, cz - c.hz, cx + c.hx, c.floor + c.height, cz + c.hz);
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.ROOM, box, r.nextLong(), c.floor, c.vista, c.entered, c.gate,
                    c.role, c.doors, c.variant, c.flags | (c.grand() ? DungeonPiece.FLAG_GRAND | com.skycraft.dungeons.world.cavern.CavernTag.encode(seed, ox, oz, grandOrdinal++) : 0)));
        }

        // corridors (each link once: from the lower-coordinate cell); the trap beat falls on the main path's middle third
        List<Cell[]> links = new ArrayList<>();
        for (Cell a : cells) for (Cell b : a.links) if (!(b.i < a.i || b.j < a.j)) links.add(new Cell[]{a, b});
        if (window != null) links.add(windowDir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? new Cell[]{boss, window} : new Cell[]{window, boss});
        // the trap beat: the main path's corridor nearest its middle (long enough to read the trap), else any corridor
        Cell[] trapLink = null;
        double bestWhere = 9;
        for (Cell[] ab : links) {
            int ia = path.indexOf(ab[0]), ib = path.indexOf(ab[1]);
            boolean onMain = ia >= 0 && ib >= 0 && Math.abs(ia - ib) == 1;
            if (ab[0] == boss && ab[1] == window || ab[1] == boss && ab[0] == window) continue;
            if (Math.max(ab[0].depth, ab[1].depth) < 2 || corridorLength(ab[0], ab[1]) < 3) continue;
            double where = onMain ? Math.abs(Math.max(ia, ib) / (double) path.size() - 0.5) : 1 + r.nextDouble();
            if (where < bestWhere) {
                bestWhere = where;
                trapLink = ab;
            }
        }
        for (Cell[] ab : links) {
            Cell a = ab[0], b = ab[1];
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
            boolean barred = (a == boss && b == window) || (a == window && b == boss);
            if (barred) flags |= DungeonPiece.FLAG_BARRED;
            // the beat's trap, and now and then another deeper in
            if (!barred && trapsHere && (ab == trapLink
                    || (Math.max(a.depth, b.depth) >= 2 && corridorLength(a, b) >= 3 && r.nextInt(10) < 2))) {
                flags |= DungeonPiece.FLAG_TRAP;
            }
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.CORRIDOR, box, r.nextLong(), a.floor, b.floor, axisId, 0,
                    DungeonPiece.ROLE_NORMAL, 0, r.nextInt(8), flags));
        }

        // the back exit: a short passage from the boss chamber's one-way door to the shaft
        if (exitShaft != null) {
            Direction d = Direction.from2DDataValue(exitShaft[0]);
            int bx = ox + boss.i * CELL, bz = oz + boss.j * CELL;
            int a1 = MAX_HALF + 1, a2 = MAX_HALF + SHAFT_OFFSET - 3;
            BoundingBox box = d.getAxis() == Direction.Axis.X
                    ? new BoundingBox(Math.min(bx + d.getStepX() * a1, bx + d.getStepX() * a2), boss.floor - 1, bz - lat,
                    Math.max(bx + d.getStepX() * a1, bx + d.getStepX() * a2), boss.floor + head, bz + lat)
                    : new BoundingBox(bx - lat, boss.floor - 1, Math.min(bz + d.getStepZ() * a1, bz + d.getStepZ() * a2),
                    bx + lat, boss.floor + head, Math.max(bz + d.getStepZ() * a1, bz + d.getStepZ() * a2));
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.CORRIDOR, box, r.nextLong(), boss.floor, boss.floor,
                    d.getAxis() == Direction.Axis.X ? 0 : 1, 0, DungeonPiece.ROLE_NORMAL, 0, r.nextInt(8), 0));
            int sx = exitShaft[1], sz = exitShaft[2], top = exitShaft[3];
            BoundingBox shaft = new BoundingBox(sx - 2, boss.floor - 1, sz - 2, sx + 2, top + 4, sz + 2);
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.SHAFT, shaft, r.nextLong(), boss.floor, top, exitShaft[0], 0,
                    DungeonPiece.ROLE_NORMAL, 0, 0, 0));
        }
    }

    /** Blocks of corridor between two neighbouring rooms' walls. */
    private static int corridorLength(Cell a, Cell b) {
        return gap(a, b);
    }

    /** Lazily probed ground height over the cells of the grid, for the grand caverns' ceilings (noise heights only). */
    private static final class GrandProbe {
        private final Ground ground;
        private final int surfaceY;
        private int ox, oz;
        private final Map<Long, Integer> cache = new HashMap<>();

        GrandProbe(Ground ground, int surfaceY) {
            this.ground = ground;
            this.surfaceY = surfaceY;
        }

        void origin(int ox, int oz) {
            this.ox = ox;
            this.oz = oz;
        }

        /** Interior height a cavern centred on a grid cell may have with its rim at {@code floor}. */
        int heightAtCell(int i, int j, int floor) {
            return height(ox + i * CELL, oz + j * CELL, floor);
        }

        int height(int wx, int wz, int floor) {
            long k = key(wx, wz);
            Integer lo = cache.get(k);
            if (lo == null) {
                int low = surfaceY;
                if (ground != null) {
                    low = Integer.MAX_VALUE;
                    int[][] pts = {{0, 0}, {14, 0}, {-14, 0}, {0, 14}, {0, -14}};
                    for (int[] o : pts) {
                        int h = ground.surface(wx + o[0], wz + o[1]);
                        // under a lake or the sea the bed's height is unknown: stay clear of it
                        low = Math.min(low, h == Integer.MIN_VALUE ? surfaceY - 10 : h);
                    }
                }
                lo = low;
                cache.put(k, lo);
            }
            return GrandCavern.heightFor(lo - 1, floor);
        }
    }

    /** Number of candidate layouts grown per medium dungeon. */
    private static final int CANDIDATES = 4;

    /** Grows one candidate room tree, picks its boss chamber and gate room, and grows a side branch if it has none. */
    private static Plan grow(Site site, long seed, int target, boolean vista, int startHalf, int floor0, int minFloor, Direction out,
                             Set<Long> reserved, int startGrandHeight, int grandsWanted, GrandProbe probe) {
        Theme theme = site.theme();
        Plan plan = new Plan(seed);
        RandomSource r = plan.r;
        Cell start = new Cell(0, 0);
        start.hx = start.hz = startHalf;
        start.floor = vista ? floor0 - VISTA_LIFT : floor0;
        start.height = vista ? theme.maxHeight + VISTA_LIFT : Math.min(theme.maxHeight, theme.minHeight + 1);
        start.role = DungeonPiece.ROLE_START;
        start.doors |= 1 << out.get2DDataValue();
        start.variant = r.nextInt(8);
        if (vista) start.vista = out.get2DDataValue() + 1;
        plan.grid.put(key(0, 0), start);
        if (startGrandHeight > 0) {
            // the entrance cavern: nine cells of the grid, entered from a ledge at its rim
            start.sx = start.sz = 1;
            start.height = startGrandHeight;
            start.flags |= DungeonPiece.FLAG_GRAND;
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) plan.grid.put(key(di, dj), start);
        }
        plan.cells.add(start);
        plan.start = start;
        plan.grandLeft = grandsWanted;
        int maxFloor = floor0 + 2;
        List<Cell> cells = plan.cells;
        for (int attempt = 0; attempt < 360 && cells.size() < target; attempt++) {
            Cell from = r.nextFloat() < 0.6f ? cells.get(cells.size() - 1 - r.nextInt(Math.min(2, cells.size()))) : cells.get(r.nextInt(cells.size()));
            Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(r);
            if (from == start && d == out) continue;
            grow(plan, reserved, from, d, theme, minFloor, maxFloor, probe);
        }
        // boss: the deepest ordinary room (a leaf of the tree), preferably not beside a cavern
        Cell boss = null;
        for (Cell c : cells) {
            if (c == start || c.grand()) continue;
            if (boss == null || c.depth > boss.depth || c.depth == boss.depth && boss.parent.grand() && !c.parent.grand()) boss = c;
        }
        plan.boss = boss;
        if (boss != null) {
            boss.role = DungeonPiece.ROLE_BOSS;
            Direction toParent = Direction.from2DDataValue(boss.entered);
            boss.hx = boss.hz = MAX_HALF;
            boss.height = theme.natural() ? theme.maxHeight + 2 : theme.maxHeight + 3;
            int gap = Math.abs(toParent.getAxis() == Direction.Axis.X ? boss.i - boss.parent.i : boss.j - boss.parent.j) * CELL
                    - boss.parent.half(toParent.getAxis()) - MAX_HALF - 1;
            int maxDy = Math.max(0, gap - 1);
            int dy = boss.floor - boss.parent.floor;
            if (Math.abs(dy) > maxDy) boss.floor = boss.parent.floor + Integer.signum(dy) * maxDy;
            if (theme.puzzle && boss.parent != start && !boss.parent.grand()) {
                Cell gateRoom = boss.parent;
                gateRoom.role = DungeonPiece.ROLE_PUZZLE;
                gateRoom.gate = toParent.getOpposite().get2DDataValue();
            }
        }
        for (Cell c = boss; c != null; c = c.parent) plan.path.add(0, c);
        // a side branch: medium dungeons always get one room off the main path (grown from the middle of it)
        if (target >= 5 && boss != null) {
            Set<Cell> on = new HashSet<>(plan.path);
            boolean hasSide = cells.stream().anyMatch(c -> !on.contains(c));
            List<Cell> mids = new ArrayList<>(plan.path);
            Cell b = boss;
            mids.removeIf(c -> c == b || c.role == DungeonPiece.ROLE_PUZZLE);
            mids.sort((p, q) -> Integer.compare(Math.abs(p.depth - b.depth / 2), Math.abs(q.depth - b.depth / 2)));
            for (int attempt = 0; !hasSide && attempt < 24 && !mids.isEmpty(); attempt++) {
                Cell from = mids.get(attempt % mids.size());
                Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(r);
                if (from == start && d == out) continue;
                if (grow(plan, reserved, from, d, theme, minFloor, maxFloor, probe) != null) hasSide = true;
            }
        }
        return plan;
    }

    /**
     * Fun, then fit: a long enough main path, a side branch, few dead ends, height changes; plus how well the layout
     * matches its niche (bit 0: branching vs linear, bit 1: vertical vs flat).
     */
    private static double score(Plan p, int niche) {
        int n = p.cells.size(), pathLen = p.path.size();
        int side = n - pathLen, deadEnds = 0, minF = Integer.MAX_VALUE, maxF = Integer.MIN_VALUE;
        for (Cell c : p.cells) {
            if (c.links.size() == 1 && c != p.boss && c != p.start) deadEnds++;
            minF = Math.min(minF, c.floor);
            maxF = Math.max(maxF, c.floor);
        }
        double s = 0;
        for (Cell c : p.cells) if (c.grand()) s += 3.0;  // the epic chambers are the point
        s += Math.min(pathLen, 8) * 1.0;                 // a journey, not a closet
        s += side >= 1 ? 2.0 : -3.0;                     // something to find off the way
        s -= Math.max(0, deadEnds - 2) * 1.0;            // but not a warren of dead ends
        s += Math.min(maxF - minF, 10) * 0.15;           // up and down
        double branchy = side / (double) Math.max(1, n);
        boolean wantBranchy = (niche & 1) != 0, wantVertical = (niche & 2) != 0;
        s += wantBranchy ? branchy * 4 : (1 - branchy) * 4;
        s += wantVertical ? Math.min(maxF - minF, 12) * 0.25 : (12 - Math.min(maxF - minF, 12)) * 0.15;
        return s;
    }

    /** Grows a new room from {@code from} towards {@code d}, or returns null if the cell is taken or out of reach. */
    private static Cell grow(Plan plan, Set<Long> reserved, Cell from, Direction d, Theme theme, int minFloor, int maxFloor,
                             GrandProbe probe) {
        RandomSource r = plan.r;
        Map<Long, Cell> grid = plan.grid;
        Direction.Axis axis = d.getAxis();
        // a grand cavern: three (or five) cells across, centred two (or three) cells out
        if (plan.grandLeft > 0 && plan.cells.size() >= 2 && r.nextInt(2) == 0) {
            int sx = 1, sz = 1;
            // every second one is a great hall of 75 x 43: two cells longer along one axis
            if (r.nextInt(2) == 0) {
                if (r.nextBoolean()) sx = 2;
                else sz = 2;
            }
            Cell g = grand(plan, reserved, from, d, theme, minFloor, maxFloor, probe, sx, sz);
            if (g != null) return g;
        }
        int step = from.span(axis) + 1;
        int ni = from.i + d.getStepX() * step, nj = from.j + d.getStepZ() * step;
        if (Math.abs(ni) > RADIUS || Math.abs(nj) > RADIUS) return null;
        long k = key(ni, nj);
        if (grid.containsKey(k) || reserved.contains(k)) return null;
        Cell c = new Cell(ni, nj);
        c.hx = 3 + r.nextInt(MAX_HALF - 2);
        c.hz = 3 + r.nextInt(MAX_HALF - 2);
        c.height = theme.minHeight + r.nextInt(theme.maxHeight - theme.minHeight + 1);
        int gap = gap(from, c);
        // the descents are long: up to five blocks between rooms where the corridor is long enough to climb
        int maxDy = Math.max(0, Math.min(5, gap - 1));
        int dy = 0;
        if (maxDy > 0) {
            int roll = r.nextInt(10);
            if (roll < 6) dy = -1 - r.nextInt(maxDy);          // dungeons lead downwards
            else if (roll < 8) dy = 1 + r.nextInt(Math.min(2, maxDy));
        }
        c.floor = Math.max(minFloor, Math.min(maxFloor, from.floor + dy));
        if (Math.abs(c.floor - from.floor) > maxDy) c.floor = from.floor;
        c.depth = from.depth + 1;
        c.parent = from;
        c.entered = d.getOpposite().get2DDataValue();
        c.variant = r.nextInt(8);
        link(from, c, d);
        grid.put(k, c);
        plan.cells.add(c);
        return c;
    }

    /** A grand cavern of (2 sx + 1) x (2 sz + 1) cells beyond {@code from}, or null when there is no room or ground for it. */
    private static Cell grand(Plan plan, Set<Long> reserved, Cell from, Direction d, Theme theme, int minFloor, int maxFloor,
                              GrandProbe probe, int sx, int sz) {
        RandomSource r = plan.r;
        Direction.Axis axis = d.getAxis();
        int along = axis == Direction.Axis.X ? sx : sz;
        int dist = from.span(axis) + 1 + along;
        int ci = from.i + d.getStepX() * dist, cj = from.j + d.getStepZ() * dist;
        for (int di = -sx; di <= sx; di++) {
            for (int dj = -sz; dj <= sz; dj++) {
                int i = ci + di, j = cj + dj;
                if (Math.abs(i) > RADIUS || Math.abs(j) > RADIUS) return null;
                long k = key(i, j);
                if (plan.grid.containsKey(k) || reserved.contains(k)) return null;
            }
        }
        Cell c = new Cell(ci, cj);
        c.sx = sx;
        c.sz = sz;
        c.hx = grandHalf(sx);
        c.hz = grandHalf(sz);
        int gap = gap(from, c);
        int maxDy = Math.max(0, Math.min(5, gap - 1));
        int dy = maxDy > 0 ? -r.nextInt(maxDy + 1) : 0;
        c.floor = Math.max(minFloor + GrandCavern.BELOW, Math.min(maxFloor, from.floor + dy));
        if (Math.abs(c.floor - from.floor) > maxDy) c.floor = from.floor;
        int h = probe.heightAtCell(ci, cj, c.floor);
        if (h < GrandCavern.MIN_HEIGHT || c.floor - GrandCavern.BELOW < minFloor) return null;
        c.height = h;
        c.flags |= DungeonPiece.FLAG_GRAND;
        c.depth = from.depth + 1;
        c.parent = from;
        c.entered = d.getOpposite().get2DDataValue();
        c.variant = r.nextInt(8);
        link(from, c, d);
        for (int di = -sx; di <= sx; di++) for (int dj = -sz; dj <= sz; dj++) plan.grid.put(key(ci + di, cj + dj), c);
        plan.cells.add(c);
        plan.grandLeft--;
        return c;
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
