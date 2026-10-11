package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Furnishing for the culture ruins of Tamriel ({@link Theme#RUIN}): Ayleid halls lit by welkynd stones, Velothi
 * ancestral tombs, Daedric ruins, Argonian xanmeer, Khajiit moon temples, Bosmer tree tombs, Aldmeri crystal vaults,
 * Yokudan sepulchres, Atmoran ice tombs, Maormer sea vaults, Sload coral pits and Akaviri strongholds. The kit
 * ({@link Painter#kit}) gives the masonry, lights and roster; this class gives each room its purpose.
 */
public final class CultureRooms {
    private CultureRooms() {}

    public static void decorate(Painter p, Room room, RandomSource r) {
        Kit kit = p.kit == Kit.NONE ? Kit.NORD : p.kit;
        Kit.Palette pal = kit.palette();
        if (room.boss()) {
            boss(p, room, kit, pal);
            return;
        }
        lights(p, room, pal);
        if (room.start()) {
            BuiltBuilder.cornerPillars(p, room, 1);
            BarrowRooms.urns(p, room, 1);
            if (p.chance(room.cx, room.floor, room.cz, 300, 2)) mob(p, room, kit, room.cx + 1, room.cz + 1, 0);
            return;
        }
        int v = room.kind() % 6;
        if (room.puzzle()) v = 0;
        if (room.has(DungeonPiece.FLAG_SETPIECE)) v = 6;
        switch (v) {
            case 6 -> SetPieces.sanctum(p, room, kit, pal);
            case 0 -> colonnade(p, room, pal);
            case 1 -> niches(p, room, kit, pal);
            case 2 -> altar(p, room, kit, pal);
            case 3 -> overgrown(p, room, pal);
            case 4 -> pool(p, room, kit, pal);
            default -> stores(p, room);
        }
        if (v != 1) {
            mob(p, room, kit, room.cx - 1, room.cz, 1);
            if (p.chance(room.cx, room.floor, room.cz, 301, 2)) mob(p, room, kit, room.cx + 1, room.cz - 1, 2);
        }
        if (room.piece.has(DungeonPiece.FLAG_TREASURE)) BarrowRooms.treasure(p, room, 1, Deco.LOOT_COMMON);
        if (room.piece.has(DungeonPiece.FLAG_DEAD_END)) BarrowRooms.urns(p, room, 1);
        if (pal.webs) webs(p, room);
    }

    /** Undead cultures sleep in their niches; living ones keep watch. */
    static boolean undead(Kit kit) {
        return switch (kit) {
            case AYLEID, VELOTHI, REDGUARD, ATMORAN, NORD, KHAJIIT -> true;
            default -> false;
        };
    }

    static void mob(Painter p, Room room, Kit kit, int x, int z, int roll) {
        if (!room.interior(x, z)) return;
        Kit.Spawn s = kit.minion(roll + p.roll(x, room.floor, z, 302, 6));
        p.spawn(s.id(), x, room.floor, z, p.roll(x, 0, z, 303, 360), s.label());
    }

    static void lights(Painter p, Room room, Kit.Palette pal) {
        BlockState light = pal.light;
        boolean hanging = light.is(Blocks.LANTERN) || light.is(Blocks.SOUL_LANTERN);
        if (hanging) {
            p.set(room.cx, room.maxY - 1, room.cz, Deco.chain(Direction.Axis.Y));
            p.set(room.cx, room.maxY - 2, room.cz, light);
        } else if (light.is(Blocks.END_ROD) || light.getBlock() instanceof net.minecraft.world.level.block.CandleBlock
                || light.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock) {
            // floor-standing lights: on pedestals in the corners
            for (int[] c : new int[][]{{-1, -1}, {1, 1}}) {
                int x = room.cx + c[0] * Math.max(2, room.halfX() - 1), z = room.cz + c[1] * Math.max(2, room.halfZ() - 1);
                if (room.onLane(x, z) || room.nearDoor(x, z, 1) || !room.interior(x, z)) continue;
                p.set(x, room.floor, z, pal.trim);
                p.set(x, room.floor + 1, z, light);
            }
        } else {
            // glowing blocks set into the ceiling and the walls
            p.set(room.cx, room.maxY, room.cz, light);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (room.hasDoor(d)) continue;
                p.set(room.localX(d, room.half(d), 0), room.floor + 2, room.localZ(d, room.half(d), 0), light);
            }
        }
    }

    static void webs(Painter p, Room room) {
        int top = room.maxY - 1;
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                boolean edge = x == room.minX + 1 || x == room.maxX - 1 || z == room.minZ + 1 || z == room.maxZ - 1;
                if (edge && p.chance(x, top, z, 305, 9)) p.setIfOpen(x, top, z, Blocks.COBWEB.defaultBlockState());
            }
        }
    }

    /** Two rows of pillars with the culture's accent between them. */
    static void colonnade(Painter p, Room room, Kit.Palette pal) {
        p.feature("room:colonnade");
        int top = room.height - 1;
        boolean alongX = room.halfX() >= room.halfZ();
        int half = alongX ? room.halfX() : room.halfZ();
        int off = Math.max(2, (alongX ? room.halfZ() : room.halfX()) - 1);
        for (int a = -half + 2; a <= half - 2; a += 3) {
            for (int s : new int[]{-off, off}) {
                int x = alongX ? room.cx + a : room.cx + s, z = alongX ? room.cz + s : room.cz + a;
                if (!room.interior(x, z) || room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
                for (int rel = 0; rel <= top; rel++) p.set(x, room.floor + rel, z, pal.pillar(rel, top));
                if (room.height >= 5) p.set(x, room.floor + top - 1, z, pal.accent.is(Blocks.LIGHT_BLUE_STAINED_GLASS) ? pal.light : pal.trim);
            }
        }
    }

    /** Burial niches in the walls, the dead laid in them. */
    static void niches(Painter p, Room room, Kit kit, Kit.Palette pal) {
        p.feature("room:niches");
        int sleepers = 0;
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                boolean xr = x == room.minX + 1 || x == room.maxX - 1;
                boolean zr = z == room.minZ + 1 || z == room.maxZ - 1;
                if ((!xr && !zr) || room.nearDoor(x, z, 1)) continue;
                for (int y = room.floor; y <= Math.min(room.floor + 2, room.maxY - 2); y++) p.set(x, y, z, pal.wall(p, x, y, z, 1));
                boolean corner = xr && zr;
                if (corner || Math.floorMod(xr ? z : x, 2) != 0) continue;
                Direction inward = x == room.minX + 1 ? Direction.EAST : x == room.maxX - 1 ? Direction.WEST
                        : z == room.minZ + 1 ? Direction.SOUTH : Direction.NORTH;
                p.set(x, room.floor, z, Blocks.AIR.defaultBlockState());
                p.set(x, room.floor + 1, z, Blocks.AIR.defaultBlockState());
                int roll = p.roll(x, room.floor, z, 306, 10);
                if (roll <= 2 && sleepers < 3) {
                    sleepers++;
                    Kit.Spawn s = kit.minion(roll);
                    if (undead(kit)) p.spawn(s.id(), x, room.floor, z, Painter.yaw(inward), s.label(), DormantMobs::makeDormant);
                    else p.spawn(s.id(), x, room.floor, z, Painter.yaw(inward), s.label());
                } else if (roll <= 4) p.set(x, room.floor, z, Blocks.DECORATED_POT.defaultBlockState());
                else if (roll == 5) p.container(x, room.floor, z, Deco.barrel(inward), Deco.LOOT_MINOR);
                else if (roll == 6) p.set(x, room.floor, z, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(inward)));
                else if (roll == 7) p.set(x, room.floor, z, Deco.candle(Blocks.CANDLE, 2, true));
            }
        }
    }

    /** An altar against a doorless wall, flanked by lights, with offerings. */
    static void altar(Painter p, Room room, Kit kit, Kit.Palette pal) {
        p.feature("room:altar");
        Direction side = null;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!room.hasDoor(d)) side = d;
        if (side == null) {
            colonnade(p, room, pal);
            return;
        }
        Direction inward = side.getOpposite();
        int half = room.half(side);
        for (int lat = -1; lat <= 1; lat++) {
            int x = room.localX(side, half - 1, lat), z = room.localZ(side, half - 1, lat);
            p.set(x, room.floor, z, pal.trim);
            p.set(x, room.floor + 1, z, lat == 0 ? centrepiece(kit, pal) : Deco.candle(Blocks.CANDLE, 3, true));
        }
        for (int lat : new int[]{-2, 2}) {
            int x = room.localX(side, half - 1, lat), z = room.localZ(side, half - 1, lat);
            if (!room.interior(x, z)) continue;
            p.set(x, room.floor, z, pal.pillar(0, 2));
            p.set(x, room.floor + 1, z, pal.light);
        }
        int x = room.localX(side, half - 2, 1), z = room.localZ(side, half - 2, 1);
        if (room.interior(x, z) && !room.onLane(x, z)) p.container(x, room.floor, z, Deco.chest(inward), Deco.LOOT_SHRINE);
    }

    /** What sits on a culture's altar. */
    static BlockState centrepiece(Kit kit, Kit.Palette pal) {
        return switch (kit) {
            case AYLEID -> Blocks.SEA_LANTERN.defaultBlockState();
            case DAEDRIC -> Blocks.CRYING_OBSIDIAN.defaultBlockState();
            case KHAJIIT -> Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState();
            case ALTMER -> Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP);
            case XANMEER -> Blocks.VERDANT_FROGLIGHT.defaultBlockState();
            case MAORMER, SLOAD -> Blocks.SEA_LANTERN.defaultBlockState();
            case BOSMER -> Blocks.SHROOMLIGHT.defaultBlockState();
            default -> Deco.skull(Blocks.SKELETON_SKULL, 0);
        };
    }

    /** Fallen masonry, roots and moss. */
    static void overgrown(Painter p, Room room, Kit.Palette pal) {
        p.feature("room:overgrown");
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
                double n = p.noise(x, room.floor, z, 3.0, 307);
                if (n > 0.3) {
                    p.set(x, room.floor, z, pal.wall(p, x, room.floor, z, 1));
                    if (n > 0.55) p.set(x, room.floor + 1, z, Deco.slab(pal.slab, false));
                } else if (n < -0.5 && pal.webs) {
                    p.set(x, room.floor, z, Blocks.MOSS_CARPET.defaultBlockState());
                }
            }
        }
        if (pal.webs) {
            for (int x = room.minX + 1; x < room.maxX; x++) {
                if (p.chance(x, room.maxY, room.cz, 308, 3)) p.setIfOpen(x, room.maxY - 1, room.minZ + 1, Blocks.HANGING_ROOTS.defaultBlockState());
            }
        }
    }

    /** A sunken basin of water (or lava, in Daedric ruins) ringed with trim. */
    static void pool(Painter p, Room room, Kit kit, Kit.Palette pal) {
        p.feature("room:pool");
        int r = Math.min(2, Math.min(room.halfX(), room.halfZ()) - 2);
        if (r < 1) {
            colonnade(p, room, pal);
            return;
        }
        int px = room.cx;
        BlockState fluid = kit == Kit.DAEDRIC ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.WATER.defaultBlockState();
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                int x = px + dx, z = room.cz + dz;
                if (!room.interior(x, z)) continue;
                boolean rim = Math.abs(dx) == r + 1 || Math.abs(dz) == r + 1;
                if (rim) {
                    if (!room.onLane(x, z)) p.set(x, room.floor, z, Deco.slab(pal.slab, false));
                } else if (p.ok(x, room.minY, z) && Painter.ground(p.get(x, room.minY - 1, z))) {
                    // the basin is the floor layer itself, only where solid rock holds it
                    p.set(x, room.minY, z, fluid);
                }
            }
        }
        if (kit != Kit.DAEDRIC && pal.light.is(Blocks.SEA_LANTERN)) p.set(px, room.minY, room.cz, Blocks.SEA_LANTERN.defaultBlockState());
    }

    static void stores(Painter p, Room room) {
        p.feature("room:stores");
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                boolean edge = x == room.minX + 1 || x == room.maxX - 1 || z == room.minZ + 1 || z == room.maxZ - 1;
                if (!edge || room.nearDoor(x, z, 1) || room.onLane(x, z)) continue;
                int roll = p.roll(x, room.floor, z, 309, 8);
                if (roll == 0) p.container(x, room.floor, z, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
                else if (roll <= 2) p.set(x, room.floor, z, Blocks.DECORATED_POT.defaultBlockState());
                else if (roll == 3) p.set(x, room.floor, z, Blocks.BARREL.defaultBlockState());
            }
        }
    }

    /** The ruin's heart: a dais with the culture's relic, the master of the place and its hoard. */
    static void boss(Painter p, Room room, Kit kit, Kit.Palette pal) {
        p.feature("room:boss");
        Direction entered = room.entered();
        Direction f = entered != null ? entered.getOpposite() : Direction.NORTH;
        Direction back = f.getOpposite();
        int halfF = room.half(f);
        int halfL = room.half(f.getClockWise());
        for (int fwd = halfF - 3; fwd <= halfF - 1; fwd++) {
            for (int s = -(halfL - 1); s <= halfL - 1; s++) {
                int x = room.localX(f, fwd, s), z = room.localZ(f, fwd, s);
                p.set(x, room.floor, z, fwd == halfF - 3 && Math.abs(s) <= 1 ? Deco.stair(pal.stairs, f, false) : pal.trim);
            }
        }
        // the relic on its pedestal
        int rx = room.localX(f, halfF - 2, 0), rz = room.localZ(f, halfF - 2, 0);
        p.set(rx, room.floor + 1, rz, pal.pillar(0, 1));
        p.set(rx, room.floor + 2, rz, centrepiece(kit, pal));
        if (kit == Kit.ATMORAN || kit == Kit.NORD) {
            Deco.wordWall(p, room.localX(f, halfF, 0), room.floor + 2, room.localZ(f, halfF, 0), back, room.piece.param);
        }
        int top = room.height - 1;
        for (int fwd = -(halfF - 2); fwd <= halfF - 5; fwd += 3) {
            for (int s : new int[]{-(halfL - 1), halfL - 1}) {
                int x = room.localX(f, fwd, s), z = room.localZ(f, fwd, s);
                if (room.nearDoor(x, z, 1)) continue;
                for (int rel = 0; rel <= top; rel++) p.set(x, room.floor + rel, z, pal.pillar(rel, top));
            }
        }
        for (int s : new int[]{-(halfL - 1), halfL - 1}) {
            p.set(room.localX(f, halfF - 1, s), room.floor + 1, room.localZ(f, halfF - 1, s), pal.light);
        }
        int cs = halfL >= 5 ? 3 : 2;
        p.container(room.localX(f, halfF - 1, cs), room.floor + 1, room.localZ(f, halfF - 1, cs), Deco.chest(back), Deco.LOOT_BOSS);
        p.container(room.localX(f, halfF - 1, -cs), room.floor + 1, room.localZ(f, halfF - 1, -cs), Deco.barrel(back), Deco.LOOT_COMMON);
        Kit.Spawn boss = kit.boss();
        p.spawnBoss(boss.id(), room.localX(f, halfF - 4, 0), room.floor, room.localZ(f, halfF - 4, 0), Painter.yaw(back), boss.label());
        mob(p, room, kit, room.localX(f, 0, 3), room.localZ(f, 0, 3), 3);
        mob(p, room, kit, room.localX(f, 0, -3), room.localZ(f, 0, -3), 4);
        BarrowRooms.urns(p, room, 1);
        if (pal.webs) webs(p, room);
    }
}
