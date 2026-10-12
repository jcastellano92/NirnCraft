package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Natural dungeons (caves, lairs, mines): noise-shaped caverns carved out of the rock, winding tunnels between
 * them, and the slope up to the surface. Carving seals any fluid it uncovers so aquifers don't flood the rooms.
 * Mines get squarer galleries, ore veins in the walls, timber supports and rails.
 */
public final class NaturalBuilder {
    private NaturalBuilder() {}

    public static BlockState rock(int y) {
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    static BlockState floorBlock(Painter p, Theme t, int x, int y, int z) {
        int roll = p.roll(x, y, z, 90, 20);
        boolean deep = y < 0;
        if (p.kit != Kit.NONE && p.kit != Kit.NORD && t == Theme.CAVE) {
            Kit.Palette pal = p.kit.palette();
            return roll < 9 ? pal.caveFloor : roll < 15 ? pal.rock : rock(y);
        }
        return switch (t) {
            case MINE -> roll < 8 ? Blocks.COBBLESTONE.defaultBlockState() : roll < 12 ? Blocks.ANDESITE.defaultBlockState() : rock(y);
            case VAMPIRE_LAIR -> roll < 6 ? Blocks.BLACKSTONE.defaultBlockState() : roll < 8 ? Blocks.RED_TERRACOTTA.defaultBlockState() : rock(y);
            case NECROMANCER_LAIR -> roll < 6 ? Blocks.SOUL_SOIL.defaultBlockState() : roll < 9 ? Blocks.TUFF.defaultBlockState() : rock(y);
            case HAGRAVEN_LAIR -> roll < 6 ? Blocks.MUD.defaultBlockState() : roll < 10 ? Blocks.MOSS_BLOCK.defaultBlockState()
                    : roll < 12 ? Blocks.ROOTED_DIRT.defaultBlockState() : rock(y);
            default -> roll < 5 ? Blocks.COARSE_DIRT.defaultBlockState() : roll < 7 ? Blocks.MOSS_BLOCK.defaultBlockState()
                    : roll < 10 ? (deep ? Blocks.COBBLED_DEEPSLATE : Blocks.ANDESITE).defaultBlockState() : rock(y);
        };
    }

    /** Patches of the cavern wall: mixed rock, ores, mud, moss or soil, by what lives here. */
    static BlockState wallPatch(Painter p, Theme t, int x, int y, int z) {
        int roll = p.roll(x, y, z, 88, 100);
        boolean deep = y < 0;
        BlockState base = rock(y);
        return switch (t) {
            case MINE -> roll < 26 ? (deep ? Blocks.COBBLED_DEEPSLATE : Blocks.ANDESITE).defaultBlockState()
                    : roll < 44 ? Blocks.COBBLESTONE.defaultBlockState() : roll < 56 ? Blocks.TUFF.defaultBlockState()
                    : roll < 66 ? Blocks.GRAVEL.defaultBlockState() : roll < 72 ? Blocks.COARSE_DIRT.defaultBlockState() : base;
            case VAMPIRE_LAIR -> roll < 34 ? Blocks.BLACKSTONE.defaultBlockState() : roll < 52 ? Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState()
                    : roll < 60 ? Blocks.RED_TERRACOTTA.defaultBlockState() : roll < 66 ? Blocks.BONE_BLOCK.defaultBlockState() : base;
            case NECROMANCER_LAIR -> roll < 30 ? Blocks.TUFF.defaultBlockState() : roll < 50 ? Blocks.SOUL_SOIL.defaultBlockState()
                    : roll < 66 ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : roll < 72 ? Blocks.AMETHYST_BLOCK.defaultBlockState() : base;
            case HAGRAVEN_LAIR -> roll < 36 ? Blocks.MOSS_BLOCK.defaultBlockState() : roll < 56 ? Blocks.MUD.defaultBlockState()
                    : roll < 70 ? Blocks.ROOTED_DIRT.defaultBlockState() : roll < 78 ? Blocks.MANGROVE_ROOTS.defaultBlockState() : base;
            default -> roll < 28 ? (deep ? Blocks.COBBLED_DEEPSLATE : Blocks.ANDESITE).defaultBlockState()
                    : roll < 42 ? Blocks.MOSS_BLOCK.defaultBlockState() : roll < 52 ? Blocks.DRIPSTONE_BLOCK.defaultBlockState()
                    : roll < 60 ? Blocks.CLAY.defaultBlockState() : roll < 68 ? Blocks.GRAVEL.defaultBlockState()
                    : roll < 72 ? Blocks.CALCITE.defaultBlockState() : base;
        };
    }

    // ------------------------------------------------------------------ rooms

    public static void room(Painter p, Room room, RandomSource r) {
        Theme t = room.theme();
        boolean mine = t == Theme.MINE;
        boolean lined = t == Theme.CAVE && p.kit != Kit.NONE && p.kit != Kit.NORD;
        double rx = room.halfX() - 0.5, rz = room.halfZ() - 0.5, ry = Math.max(3, room.height - 0.5);
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                boolean carvedColumn = false;
                int lift = vistaLift(room, x, z);
                for (int y = Math.max(p.minY(), room.floor - 1); y <= p.maxY(); y++) {
                    double d = shape(p, room, x, y, z, rx, rz, ry, mine);
                    boolean inside = y >= room.floor + lift && y < room.maxY && (d < 1.0 || passage(room, x, y, z));
                    if (inside) {
                        p.carve(x, y, z, rock(y));
                        if (y == room.floor + lift) carvedColumn = true;
                    } else if (mine && y >= room.floor - 1 && d < 1.3 && p.noise(x, y, z, 2.5, 81) > 0.42) {
                        if (p.get(x, y, z).is(BlockTags.BASE_STONE_OVERWORLD)) p.set(x, y, z, Deco.ore(p, x, y, z, y < 0));
                    } else if (lined && y >= room.floor - 1 && d < 1.25 && p.get(x, y, z).is(BlockTags.BASE_STONE_OVERWORLD)) {
                        // the cave's own rock: ice, coral, sandstone, ash...
                        p.set(x, y, z, p.noise(x, y, z, 3.0, 86) > 0.2 ? p.kit.palette().rock : p.kit.palette().caveFloor);
                    } else if (!lined && y >= room.floor - 1 && d < 1.25 && p.noise(x, y, z, 3.0, 87) > 0.12
                            && p.get(x, y, z).is(BlockTags.BASE_STONE_OVERWORLD)) {
                        // a wider palette than stone: each kind of lair and cave has its own rock mixes
                        p.set(x, y, z, wallPatch(p, t, x, y, z));
                    }
                }
                int fy = room.floor + lift - 1;
                if (carvedColumn && p.ok(x, fy, z)) p.set(x, fy, z, floorBlock(p, t, x, fy, z));
            }
        }
        p.mood = Pacing.of(p, room);
        NaturalRooms.decorate(p, room, r);
        Direction exit = room.exit();
        if (exit != null) Shafts.exitDoor(p, room, exit);
        if (room.vista() != null) p.feature("setpiece:vista");
        Story.dress(p, room);
        Beats.dress(p, room);
    }

    /**
     * The entrance cavern's vista: the floor rises in a ramp to the tunnel mouth, {@value Layout#VISTA_LIFT} blocks
     * over the cavern floor.
     */
    static int vistaLift(Room room, int x, int z) {
        Direction v = room.vista();
        if (v == null) return 0;
        return Mth.clamp(Layout.VISTA_LIFT + 1 - room.inward(v, x, z), 0, Layout.VISTA_LIFT);
    }

    private static double shape(Painter p, Room room, int x, int y, int z, double rx, double rz, double ry, boolean mine) {
        double nx = (x - room.cx) / rx, nz = (z - room.cz) / rz;
        double ny = Math.max(0, (y - room.floor) / ry);
        double n = p.noise(x, y, z, 4.0, 80);
        if (mine) {
            double ax = nx * nx, az = nz * nz;
            return ax * ax + az * az + ny * ny * ny * ny + n * 0.15;
        }
        return nx * nx + nz * nz + ny * ny + n * 0.3;
    }

    /** The 3x3 passages from the centre to each doorway, so tunnels always connect. */
    private static boolean passage(Room room, int x, int y, int z) {
        Direction v = room.vista();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!room.hasDoor(d)) continue;
            int base = room.floor + (d == v ? vistaLift(room, x, z) : 0);
            if (y < base || y > base + 2) continue;
            if (d.getAxis() == Direction.Axis.X) {
                if (Math.abs(z - room.cz) > 1) continue;
                if (d == Direction.EAST ? x >= room.cx : x <= room.cx) return true;
            } else {
                if (Math.abs(x - room.cx) > 1) continue;
                if (d == Direction.SOUTH ? z >= room.cz : z <= room.cz) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ corridors

    public static void corridor(Painter p, DungeonPiece c, RandomSource r) {
        BoundingBox b = c.getBoundingBox();
        boolean xAxis = c.dir == 0;
        int center = xAxis ? (b.minZ() + b.maxZ()) / 2 : (b.minX() + b.maxX()) / 2;
        int low = xAxis ? b.minX() : b.minZ();
        int len = c.corridorLength();
        Direction plus = xAxis ? Direction.EAST : Direction.SOUTH;
        Direction latPlus = xAxis ? Direction.SOUTH : Direction.EAST;
        boolean mine = c.theme == Theme.MINE;
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int k = (xAxis ? x : z) - low;
                int lat = (xAxis ? z : x) - center;
                int f = c.corridorFloor(k);
                carveColumn(p, c.theme, x, z, k, lat, f, len, mine, 0);
            }
        }
        if (mine) {
            for (int k = 0; k < len; k++) {
                int f = c.corridorFloor(k);
                int ax = xAxis ? low + k : center, az = xAxis ? center : low + k;
                int fPrev = k > 0 ? c.corridorFloor(k - 1) : f;
                int fNext = k < len - 1 ? c.corridorFloor(k + 1) : f;
                rail(p, ax, f, az, plus, fPrev, fNext, f);
                if (k % 4 == 1) support(p, ax, f, az, latPlus);
                if (k % 8 == 5) p.set(ax, f + 2, az, Deco.lantern(false, true));
            }
        } else {
            for (int k = 1; k < len - 1; k += 5) {
                int f = c.corridorFloor(k);
                int ax = xAxis ? low + k : center, az = xAxis ? center : low + k;
                NaturalRooms.tunnelLight(p, c.theme, ax, f, az, latPlus, c.flavor);
            }
        }
        if ((c.has(DungeonPiece.FLAG_TRAP) || c.has(DungeonPiece.FLAG_BARRED)) && len >= 1) {
            int k = len / 2;
            int ax = xAxis ? low + k : center, az = xAxis ? center : low + k;
            if (c.has(DungeonPiece.FLAG_BARRED)) Traps.bars(p, ax, c.corridorFloor(k), az, latPlus);
            else Traps.tripwire(p, ax, c.corridorFloor(k), az, latPlus, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), true);
        }
        Pacing.patrol(p, c);
    }

    /** Carves one column of a winding tunnel; position k of len, lateral offset lat, walking level f. */
    private static void carveColumn(Painter p, Theme t, int x, int z, int k, int lat, int f, int len, boolean mine, int salt) {
        int wobble = 0, half = 1, h = 3;
        if (!mine) {
            boolean nearEnd = k <= 1 || k >= len - 2;
            wobble = nearEnd ? 0 : Mth.clamp((int) Math.round(p.noise(k, salt, 0, 6.0, 82) * 1.6), -1, 1);
            half = p.noise(k, salt, 7, 5.0, 83) > 0.25 && !nearEnd ? 2 : 1;
            h = p.noise(k, salt, 13, 4.0, 84) > 0.2 ? 4 : 3;
        }
        boolean lane = Math.abs(lat) <= 1;
        boolean open = lane || Math.abs(lat - wobble) <= half;
        if (!open) return;
        int top = lane ? Math.max(h, 3) : h - (Math.abs(lat - wobble) == half && half == 2 ? 1 : 0);
        for (int y = f; y < f + top; y++) p.carve(x, y, z, rock(y));
        if (p.ok(x, f - 1, z)) {
            BlockState below = p.get(x, f - 1, z);
            if (Painter.open(below) || mine) p.set(x, f - 1, z, floorBlock(p, t, x, f - 1, z));
        }
    }

    /** A rail on the centre lane at level f, sloped towards a neighbour one block higher. */
    private static void rail(Painter p, int x, int f, int z, Direction plus, int fPrev, int fNext, int fHere) {
        RailShape shape;
        boolean xAxis = plus.getAxis() == Direction.Axis.X;
        if (fNext == fHere + 1) shape = xAxis ? RailShape.ASCENDING_EAST : RailShape.ASCENDING_SOUTH;
        else if (fPrev == fHere + 1) shape = xAxis ? RailShape.ASCENDING_WEST : RailShape.ASCENDING_NORTH;
        else shape = xAxis ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        if (!p.ok(x, f, z)) return;
        if (p.chance(x, f, z, 85, 9)) return; // a missing piece here and there
        p.set(x, f, z, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape));
    }

    /** Timber frame: posts in the walls and a beam across the ceiling. */
    static void support(Painter p, int x, int f, int z, Direction latPlus) {
        int lx = latPlus.getStepX(), lz = latPlus.getStepZ();
        Direction.Axis beam = latPlus.getAxis();
        for (int s : new int[]{-2, 2}) {
            for (int y = f; y <= f + 2; y++) p.set(x + lx * s, y, z + lz * s, Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y));
        }
        for (int s = -2; s <= 2; s++) p.set(x + lx * s, f + 3, z + lz * s, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, beam));
    }

    // ------------------------------------------------------------------ entrance slope

    public static void tunnel(Painter p, DungeonPiece c, RandomSource r) {
        Direction out = c.facing();
        Direction right = c.right();
        boolean mine = c.theme == Theme.MINE;
        int len = c.tunnelLength();
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int t = c.frameT(x, z);
                int lat = c.frameLat(x, z);
                int f = c.tunnelFloor(t);
                carveColumn(p, c.theme, x, z, t, lat, f, len + 1, mine, 3);
            }
        }
        for (int t = 1; t <= len; t++) {
            int f = c.tunnelFloor(t);
            int ax = c.frameX(t, 0), az = c.frameZ(t, 0);
            if (mine) {
                Direction plus = out.getAxisDirection() == Direction.AxisDirection.POSITIVE ? out : out.getOpposite();
                int fPrev = c.tunnelFloor(t - 1), fNext = c.tunnelFloor(t + 1);
                // the frame's "plus" ordering: swap neighbours when the tunnel runs towards negative coordinates
                if (plus != out) {
                    int tmp = fPrev;
                    fPrev = fNext;
                    fNext = tmp;
                }
                if (t < len) rail(p, ax, f, az, plus, fPrev, fNext, f);
                if (t % 4 == 1 && f + 3 < c.aux) support(p, ax, f, az, right);
                if (t % 8 == 5) p.set(ax, f + 2, az, Deco.lantern(false, true));
            } else if (t % 6 == 3 && f + 3 < c.aux) {
                NaturalRooms.tunnelLight(p, c.theme, ax, f, az, right, c.flavor);
            }
        }
    }
}
