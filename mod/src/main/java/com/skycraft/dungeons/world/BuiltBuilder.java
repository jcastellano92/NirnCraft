package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Masonry dungeons (Nordic barrows, Dwemer ruins, fort dungeons): box rooms with 1-block walls, 3x3 corridors with
 * stairs, arches, lights and arrow traps, and the entrance stair tunnel. Room furnishing is delegated per theme.
 */
public final class BuiltBuilder {
    private BuiltBuilder() {}

    // ------------------------------------------------------------------ palettes

    public static BlockState wall(Painter p, Theme t, int x, int y, int z, int rel) {
        if (p.kit != Kit.NONE) return p.kit.palette().wall(p, x, y, z, rel);
        int roll = p.roll(x, y, z, 1, 20);
        return switch (t) {
            case DWEMER -> {
                if (rel <= 0 || rel == 4) yield roll < 3 ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
                        : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                if (Math.floorMod(x + z, 6) == 0) yield Blocks.WAXED_COPPER_BLOCK.defaultBlockState();
                if (roll < 7) yield Blocks.WAXED_CUT_COPPER.defaultBlockState();
                if (roll < 13) yield Blocks.WAXED_EXPOSED_CUT_COPPER.defaultBlockState();
                if (roll < 18) yield Blocks.WAXED_WEATHERED_CUT_COPPER.defaultBlockState();
                yield Blocks.WAXED_OXIDIZED_CUT_COPPER.defaultBlockState();
            }
            case FORT -> {
                if (roll < 8) yield Blocks.STONE_BRICKS.defaultBlockState();
                if (roll < 13) yield Blocks.COBBLESTONE.defaultBlockState();
                if (roll < 15) yield Blocks.ANDESITE.defaultBlockState();
                if (roll < 17) yield Blocks.MOSSY_COBBLESTONE.defaultBlockState();
                if (roll < 19) yield Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                yield Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
            }
            default -> {
                if (rel <= 0 && roll < 6) yield Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                if (roll < 9) yield Blocks.STONE_BRICKS.defaultBlockState();
                if (roll < 13) yield Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                if (roll < 18) yield Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
                if (roll < 19) yield Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
                yield Blocks.TUFF.defaultBlockState();
            }
        };
    }

    public static BlockState floor(Painter p, Theme t, int x, int y, int z) {
        if (p.kit != Kit.NONE) return p.kit.palette().floor(p, x, y, z);
        int roll = p.roll(x, y, z, 2, 20);
        return switch (t) {
            case DWEMER -> {
                if (Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0) yield Blocks.WAXED_CUT_COPPER.defaultBlockState();
                if (Math.floorMod(x + z, 4) == 2 && roll < 6) yield Blocks.WAXED_EXPOSED_CUT_COPPER.defaultBlockState();
                yield roll < 3 ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState() : Blocks.POLISHED_BLACKSTONE.defaultBlockState();
            }
            case FORT -> roll < 10 ? Blocks.COBBLESTONE.defaultBlockState() : roll < 15 ? Blocks.STONE_BRICKS.defaultBlockState()
                    : roll < 18 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            default -> roll < 11 ? Blocks.DEEPSLATE_TILES.defaultBlockState() : roll < 15 ? Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState()
                    : roll < 18 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        };
    }

    public static BlockState ceiling(Painter p, Theme t, int x, int y, int z) {
        if (p.kit != Kit.NONE) return p.kit.palette().ceiling(p, x, y, z);
        int roll = p.roll(x, y, z, 3, 20);
        return switch (t) {
            case DWEMER -> Math.floorMod(x, 3) == 0 || Math.floorMod(z, 3) == 0 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
                    : roll < 10 ? Blocks.WAXED_OXIDIZED_CUT_COPPER.defaultBlockState() : Blocks.WAXED_WEATHERED_CUT_COPPER.defaultBlockState();
            case FORT -> Math.floorMod(x, 4) == 0 ? Blocks.DARK_OAK_PLANKS.defaultBlockState()
                    : roll < 12 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            default -> roll < 10 ? Blocks.STONE_BRICKS.defaultBlockState() : roll < 15 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                    : Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        };
    }

    public static Block stairBlock(Painter p, Theme t) {
        return p.kit != Kit.NONE ? p.kit.palette().stairs : stairBlock(t);
    }

    public static Block slabBlock(Painter p, Theme t) {
        return p.kit != Kit.NONE ? p.kit.palette().slab : slabBlock(t);
    }

    public static BlockState pillar(Painter p, Theme t, int rel, int top) {
        return p.kit != Kit.NONE ? p.kit.palette().pillar(rel, top) : pillar(t, rel, top);
    }

    /** The culture's light (hanging soul lantern in Nordic tombs). */
    public static BlockState light(Painter p) {
        return p.kit != Kit.NONE ? p.kit.palette().light : Deco.lantern(true, true);
    }

    public static Block stairBlock(Theme t) {
        return switch (t) {
            case DWEMER -> Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS;
            case FORT -> Blocks.COBBLESTONE_STAIRS;
            default -> Blocks.STONE_BRICK_STAIRS;
        };
    }

    public static Block slabBlock(Theme t) {
        return switch (t) {
            case DWEMER -> Blocks.POLISHED_BLACKSTONE_BRICK_SLAB;
            case FORT -> Blocks.COBBLESTONE_SLAB;
            default -> Blocks.STONE_BRICK_SLAB;
        };
    }

    public static BlockState pillar(Theme t, int rel, int top) {
        return switch (t) {
            case DWEMER -> rel == 0 || rel == top ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : Blocks.WAXED_CUT_COPPER.defaultBlockState();
            case FORT -> Deco.pillar(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y);
            default -> rel == 0 || rel == top ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
        };
    }

    // ------------------------------------------------------------------ rooms

    public static void room(Painter p, Room room, RandomSource r) {
        Theme t = room.theme();
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                boolean wall = room.onWall(x, z);
                for (int y = p.minY(); y <= p.maxY(); y++) {
                    if (y == room.minY) p.set(x, y, z, floor(p, t, x, y, z));
                    else if (y == room.maxY) p.set(x, y, z, ceiling(p, t, x, y, z));
                    else if (wall && !room.doorway(x, y, z)) p.set(x, y, z, wall(p, t, x, y, z, y - room.floor));
                    else p.air(x, y, z);
                }
            }
        }
        switch (t) {
            case DWEMER -> DwemerRooms.decorate(p, room, r);
            case FORT -> FortRooms.decorate(p, room, r);
            case RUIN -> CultureRooms.decorate(p, room, r);
            default -> BarrowRooms.decorate(p, room, r);
        }
        Direction gate = room.gate();
        if (gate != null) {
            switch (Puzzles.kind(room.piece)) {
                case CLAW -> Puzzles.clawDoor(p, room, gate);
                case PILLARS -> {
                    gate(p, room, gate);
                    Puzzles.pillars(p, room, gate);
                }
                default -> gate(p, room, gate);
            }
        }
    }

    /** Four pillars set in from the corners, if the room is big enough. */
    public static void cornerPillars(Painter p, Room room, int inset) {
        if (room.halfX() < inset + 2 || room.halfZ() < inset + 2) return;
        int top = room.height - 1;
        for (int sx : new int[]{room.minX + inset, room.maxX - inset}) {
            for (int sz : new int[]{room.minZ + inset, room.maxZ - inset}) {
                if (room.nearDoor(sx, sz, 1) || room.onLane(sx, sz)) continue;
                for (int rel = 0; rel <= top; rel++) p.set(sx, room.floor + rel, sz, pillar(p, room.theme(), rel, top));
            }
        }
    }

    /**
     * The sealed door before the boss: the doorway is walled up around an iron door. The lever that opens it hangs
     * on the door jamb, hidden behind a pillar (a lever strongly powers the jamb, which powers the door next to it).
     */
    private static void gate(Painter p, Room room, Direction side) {
        Theme t = room.theme();
        Direction inward = side.getOpposite();
        Direction right = side.getClockWise();
        int wx = side == Direction.EAST ? room.maxX : side == Direction.WEST ? room.minX : room.cx;
        int wz = side == Direction.SOUTH ? room.maxZ : side == Direction.NORTH ? room.minZ : room.cz;
        for (int lat = -1; lat <= 1; lat++) {
            for (int dy = 0; dy <= 2; dy++) {
                int x = wx + right.getStepX() * lat, z = wz + right.getStepZ() * lat;
                if (lat == 0 && dy < 2) continue;
                p.set(x, room.floor + dy, z, lat == 0 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                        : t == Theme.DWEMER ? Blocks.WAXED_COPPER_BLOCK.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            }
        }
        Deco.ironDoor(p, wx, room.floor, wz, inward);
        // lever on the jamb's room face, one block up (next to the door's upper half)
        int jx = wx + right.getStepX(), jz = wz + right.getStepZ();
        p.set(jx + inward.getStepX(), room.floor + 1, jz + inward.getStepZ(), Deco.wallLever(inward));
        // the pillar that hides it, and a decoy on the other side
        int top = room.height - 1;
        for (int s : new int[]{1, -1}) {
            int px = wx + right.getStepX() * s + inward.getStepX() * 2;
            int pz = wz + right.getStepZ() * s + inward.getStepZ() * 2;
            for (int rel = 0; rel <= top; rel++) p.set(px, room.floor + rel, pz, pillar(p, t, rel, top));
        }
        // the claw-door motif: carved pedestals with candles flanking the approach
        for (int s : new int[]{-3, 3}) {
            int px = wx + right.getStepX() * s + inward.getStepX() * 3;
            int pz = wz + right.getStepZ() * s + inward.getStepZ() * 3;
            if (!room.interior(px, pz) || room.onLane(px, pz)) continue;
            p.set(px, room.floor, pz, t == Theme.DWEMER ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            p.set(px, room.floor + 1, pz, t == Theme.DWEMER ? Blocks.WAXED_CUT_COPPER.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            p.set(px, room.floor + 2, pz, Deco.candle(Blocks.CANDLE, 3, true));
        }
    }

    // ------------------------------------------------------------------ corridors

    public static void corridor(Painter p, DungeonPiece c, RandomSource r) {
        Theme t = c.theme;
        BoundingBox b = c.getBoundingBox();
        boolean xAxis = c.dir == 0;
        int center = xAxis ? (b.minZ() + b.maxZ()) / 2 : (b.minX() + b.maxX()) / 2;
        int low = xAxis ? b.minX() : b.minZ();
        int len = c.corridorLength();
        Direction plus = xAxis ? Direction.EAST : Direction.SOUTH;
        Direction latPlus = xAxis ? Direction.SOUTH : Direction.EAST;
        Block stair = stairBlock(p, t);
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int k = (xAxis ? x : z) - low;
                int lat = (xAxis ? z : x) - center;
                int f = c.corridorFloor(k);
                int fPrev = k > 0 ? c.corridorFloor(k - 1) : f;
                int fNext = k < len - 1 ? c.corridorFloor(k + 1) : f;
                for (int y = p.minY(); y <= p.maxY(); y++) {
                    if (Math.abs(lat) <= 1) {
                        if (y >= f && y <= f + 2) p.air(x, y, z);
                        else if (y == f - 1) {
                            if (fPrev == f - 1) p.set(x, y, z, Deco.stair(stair, plus, false));
                            else if (fNext == f - 1) p.set(x, y, z, Deco.stair(stair, plus.getOpposite(), false));
                            else p.set(x, y, z, floor(p, t, x, y, z));
                        } else if (y == f + 3) p.set(x, y, z, ceiling(p, t, x, y, z));
                        else p.set(x, y, z, wall(p, t, x, y, z, 1));
                    } else {
                        p.set(x, y, z, wall(p, t, x, y, z, y - f));
                    }
                }
            }
        }
        // furnishing, step by step along the corridor
        for (int k = 0; k < len; k++) {
            int f = c.corridorFloor(k);
            boolean flat = (k == 0 || c.corridorFloor(k - 1) == f) && (k == len - 1 || c.corridorFloor(k + 1) == f);
            int ax = xAxis ? low + k : center, az = xAxis ? center : low + k;
            int lx = latPlus.getStepX(), lz = latPlus.getStepZ();
            if (flat && k % 4 == 2) {
                // arch brackets
                p.set(ax - lx, f + 2, az - lz, Deco.stair(stair, latPlus.getOpposite(), true));
                p.set(ax + lx, f + 2, az + lz, Deco.stair(stair, latPlus, true));
            } else if (t != Theme.DWEMER) {
                for (int s : new int[]{-1, 1}) {
                    if (p.chance(ax + lx * s, f + 2, az + lz * s, 11, 9)) p.set(ax + lx * s, f + 2, az + lz * s, Blocks.COBWEB.defaultBlockState());
                }
            }
            if (t == Theme.DWEMER) {
                for (int s : new int[]{-1, 1}) {
                    if (!p.chance(ax, f, az, 12 + s, 6)) p.set(ax + lx * s, f + 2, az + lz * s, Deco.chain(xAxis ? Direction.Axis.X : Direction.Axis.Z));
                }
                if (k % 5 == 2) p.set(ax, f + 3, az, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
            } else if (k % 6 == 3) {
                if (t == Theme.FORT) p.set(ax + lx, f + 1, az + lz, Deco.wallTorch(false, latPlus.getOpposite()));
                else p.set(ax, f + 2, az, Deco.lantern(true, true));
            }
        }
        if (c.has(DungeonPiece.FLAG_TRAP) && len >= 3) {
            int k = len / 2;
            int f = c.corridorFloor(k);
            int ax = xAxis ? low + k : center, az = xAxis ? center : low + k;
            int lx = latPlus.getStepX(), lz = latPlus.getStepZ();
            BlockState plate = t == Theme.DWEMER ? Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE.defaultBlockState()
                    : Blocks.STONE_PRESSURE_PLATE.defaultBlockState();
            for (int s = -1; s <= 1; s++) p.set(ax + lx * s, f, az + lz * s, plate);
            p.dispenser(ax - lx * 2, f, az - lz * 2, latPlus, Deco.ARROWS);
            p.dispenser(ax + lx * 2, f, az + lz * 2, latPlus.getOpposite(), Deco.ARROWS);
        }
    }

    // ------------------------------------------------------------------ entrance tunnel

    public static void tunnel(Painter p, DungeonPiece c, RandomSource r) {
        Theme t = c.theme;
        Direction out = c.facing();
        Direction right = c.right();
        Block stair = stairBlock(p, t);
        boolean openTop = c.has(DungeonPiece.FLAG_OPEN_TOP);
        int surfaceY = c.aux;
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int tt = c.frameT(x, z);
                int lat = c.frameLat(x, z);
                int f = c.tunnelFloor(tt);
                int fPrev = c.tunnelFloor(tt - 1);
                for (int y = p.minY(); y <= p.maxY(); y++) {
                    if (openTop && y >= surfaceY) continue;
                    if (Math.abs(lat) <= 1) {
                        if (y >= f && y <= f + 2) p.air(x, y, z);
                        else if (y == f - 1) p.set(x, y, z, fPrev == f - 1 && tt > 1 ? Deco.stair(stair, out, false) : floor(p, t, x, y, z));
                        else if (y == f + 3) p.set(x, y, z, ceiling(p, t, x, y, z));
                        else if (y < f) p.set(x, y, z, wall(p, t, x, y, z, 1));
                        // above the ceiling: leave the terrain
                    } else if (y <= f + 3) {
                        p.set(x, y, z, wall(p, t, x, y, z, y - f));
                    }
                }
                // lights
                if (lat == 0 && tt % 5 == 3 && !(openTop && f + 2 >= surfaceY - 1)) {
                    if (t == Theme.DWEMER) p.set(x, f + 3, z, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
                    else if (t == Theme.FORT) p.set(x + right.getStepX(), f + 1, z + right.getStepZ(), Deco.wallTorch(false, right.getOpposite()));
                    else p.set(x, f + 2, z, Deco.lantern(true, true));
                }
            }
        }
    }
}
