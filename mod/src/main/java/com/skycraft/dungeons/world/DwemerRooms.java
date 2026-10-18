package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dwemer ruin furnishing: bronze (waxed copper) halls with pipe runs, steam vents (lit campfires under iron grates),
 * golden froglights, workshops full of scrap, boiler machines, and the automatons that still guard them; the
 * Centurion waits in the great hall.
 */
public final class DwemerRooms {
    private DwemerRooms() {}

    public static void decorate(Painter p, Room room, RandomSource r) {
        if (room.boss()) {
            boss(p, room);
            return;
        }
        pipes(p, room);
        lights(p, room);
        if (room.start()) {
            vents(p, room, 1);
            BuiltBuilder.cornerPillars(p, room, 1);
            p.spawn(Mobs.DWARVEN_SPIDER, room.cx + 2, room.floor, room.cz - 2, 0f, null);
            return;
        }
        int v = room.kind() % 5;
        if (room.has(DungeonPiece.FLAG_SETPIECE)) v = 5;
        switch (v) {
            case 5 -> SetPieces.machineHall(p, room);
            case 0 -> {
                boilers(p, room);
                vents(p, room, 2);
            }
            case 1 -> workshop(p, room);
            case 2 -> machine(p, room);
            case 3 -> {
                gallery(p, room);
                vents(p, room, 1);
            }
            default -> storeroom(p, room);
        }
        // guardians
        int spiders = 1 + p.roll(room.cx, room.floor, room.cz, 60, 2);
        for (int i = 0; i < spiders; i++) {
            int x = room.cx + (i == 0 ? -2 : 2), z = room.cz + (i == 0 ? 1 : -1);
            p.spawn(Mobs.DWARVEN_SPIDER, x, room.floor, z, i * 180f, null);
        }
        if (room.puzzle() || v == 2 || p.chance(room.cx, room.floor, room.cz, 61, 4)) {
            p.spawn(Mobs.DWARVEN_SPHERE, room.cx, room.floor, room.cz + 2, 180f, null);
        }
        if (room.piece.has(DungeonPiece.FLAG_TREASURE)) BarrowRooms.treasure(p, room, 1, Deco.LOOT_COMMON);
        if (room.piece.has(DungeonPiece.FLAG_DEAD_END)) scrap(p, room, 2);
    }

    static BlockState rod() {
        return com.skycraft.worldgen.VanillaBlocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, Direction.UP);
    }

    /** Horizontal chain pipes under the ceiling and vertical rod pipes in the wall corners. */
    static void pipes(Painter p, Room room) {
        if (room.height < 5) return;
        int y = room.maxY - 2;
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z : new int[]{room.minZ + 1, room.maxZ - 1}) {
                if (!room.nearDoor(x, z, 0)) p.set(x, y, z, Deco.chain(Direction.Axis.X));
            }
        }
        for (int z = room.minZ + 2; z < room.maxZ - 1; z++) {
            for (int x : new int[]{room.minX + 1, room.maxX - 1}) {
                if (!room.nearDoor(x, z, 0)) p.set(x, y, z, Deco.chain(Direction.Axis.Z));
            }
        }
        for (int x : new int[]{room.minX + 1, room.maxX - 1}) {
            for (int z : new int[]{room.minZ + 1, room.maxZ - 1}) {
                for (int yy = room.floor; yy < y; yy++) p.set(x, yy, z, yy == room.floor ? com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState() : rod());
                p.set(x, y, z, com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState());
            }
        }
    }

    /** Froglight panels in the ceiling and walls. */
    static void lights(Painter p, Room room) {
        p.set(room.cx, room.maxY, room.cz, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        for (int x = room.minX + 2; x < room.maxX - 1; x += 4) {
            for (int z : new int[]{room.minZ, room.maxZ}) {
                if (!room.doorway(x, room.floor + 1, z)) p.set(x, room.floor + 3, z, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
            }
        }
        if (room.halfX() >= 4 && room.halfZ() >= 4) {
            for (int[] c : new int[][]{{-2, -2}, {2, 2}, {-2, 2}, {2, -2}}) {
                p.set(room.cx + c[0], room.maxY - 1, room.cz + c[1], Deco.lantern(false, true));
            }
        }
    }

    /** Steam vents: a lit campfire sunk into the floor under an iron grate. */
    static void vents(Painter p, Room room, int count) {
        int placed = 0;
        for (int i = 0; i < 12 && placed < count; i++) {
            int x = room.minX + 2 + p.roll(room.cx, i, room.cz, 62, Math.max(1, room.maxX - room.minX - 3));
            int z = room.minZ + 2 + p.roll(room.cx, i, room.cz, 63, Math.max(1, room.maxZ - room.minZ - 3));
            if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
            p.set(x, room.minY, z, Deco.campfire(false, true));
            p.set(x, room.floor, z, Deco.trapdoor(Blocks.IRON_TRAPDOOR, Direction.NORTH, false, false));
            placed++;
        }
    }

    static void boilers(Painter p, Room room) {
        p.feature("room:boilers");
        for (int[] c : new int[][]{{-1, -1}, {1, 1}}) {
            int bx = room.cx + c[0] * Math.max(2, room.halfX() - 2), bz = room.cz + c[1] * Math.max(2, room.halfZ() - 2);
            if (room.onLane(bx, bz) || room.nearDoor(bx, bz, 1)) continue;
            int top = Math.min(room.floor + 3, room.maxY - 2);
            for (int y = room.floor; y <= top; y++) {
                p.set(bx, y, bz, y == top ? com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.WAXED_EXPOSED_COPPER.defaultBlockState());
            }
            for (int y = top + 1; y < room.maxY; y++) p.set(bx, y, bz, rod());
        }
    }

    static void workshop(Painter p, Room room) {
        p.feature("room:workshop");
        BlockState[] benches = {
                Blocks.SMITHING_TABLE.defaultBlockState(),
                Blocks.CHIPPED_ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.NORTH),
                Blocks.GRINDSTONE.defaultBlockState(),
                com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER_SLAB.defaultBlockState(),
                Blocks.CRAFTING_TABLE.defaultBlockState()};
        int i = 0;
        for (int x = room.minX + 1; x < room.maxX; x += 2) {
            int z = room.minZ + 1;
            if (room.nearDoor(x, z, 1) || room.onLane(x, z)) continue;
            p.set(x, room.floor, z, benches[i++ % benches.length]);
        }
        scrap(p, room, 1);
        BuiltBuilder.cornerPillars(p, room, 2);
    }

    /** A boiler machine on a platform in the middle of the room, with docking cradles for spheres. */
    static void machine(Painter p, Room room) {
        p.feature("room:machine");
        int ox = room.cx + (room.halfX() >= 5 ? 3 : 0), oz = room.cz + (room.halfZ() >= 5 ? 3 : 0);
        if (room.onLane(ox, oz)) {
            ox = room.cx - Math.max(2, room.halfX() - 2);
            oz = room.cz - Math.max(2, room.halfZ() - 2);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = ox + dx, z = oz + dz;
                if (!room.interior(x, z) || room.onLane(x, z) || room.nearDoor(x, z, 0)) continue;
                p.set(x, room.floor, z, dx == 0 && dz == 0 ? com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState());
                if (dx == 0 && dz == 0) {
                    p.set(x, room.floor + 1, z, com.skycraft.worldgen.VanillaBlocks.WAXED_EXPOSED_COPPER.defaultBlockState());
                    p.set(x, room.floor + 2, z, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
                    for (int y = room.floor + 3; y < room.maxY; y++) p.set(x, y, z, rod());
                }
            }
        }
        vents(p, room, 2);
    }

    /** Copper colonnade with iron railings. */
    static void gallery(Painter p, Room room) {
        p.feature("room:gallery");
        int top = room.height - 1;
        for (int x = room.minX + 2; x < room.maxX - 1; x += 3) {
            for (int z : new int[]{room.minZ + 2, room.maxZ - 2}) {
                if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
                for (int rel = 0; rel <= top; rel++) p.set(x, room.floor + rel, z, BuiltBuilder.pillar(Theme.DWEMER, rel, top));
                for (int dx = 1; dx <= 2 && x + dx < room.maxX - 1; dx++) {
                    if (!room.onLane(x + dx, z) && !room.nearDoor(x + dx, z, 1)) p.set(x + dx, room.floor, z, Deco.bars(Blocks.IRON_BARS, Direction.Axis.X));
                }
            }
        }
    }

    static void storeroom(Painter p, Room room) {
        p.feature("room:storeroom");
        scrap(p, room, 3);
        BarrowRooms.treasure(p, room, 1, Deco.LOOT_COMMON);
    }

    /** Barrels of Dwemer scrap along the walls. */
    static void scrap(Painter p, Room room, int count) {
        int placed = 0;
        for (int x = room.minX + 2; x < room.maxX - 1 && placed < count; x += 2) {
            int z = room.maxZ - 1;
            if (room.nearDoor(x, z, 1) || room.onLane(x, z)) continue;
            p.container(x, room.floor, z, Deco.barrel(Direction.UP), Deco.LOOT_DWEMER);
            placed++;
        }
    }

    /** The great hall: the Centurion on its platform, a boss chest, sphere sentries and steam everywhere. */
    static void boss(Painter p, Room room) {
        p.feature("room:boss");
        Direction entered = room.entered();
        Direction f = entered != null ? entered.getOpposite() : Direction.NORTH;
        Direction back = f.getOpposite();
        int halfF = room.half(f);
        int halfL = room.half(f.getClockWise());
        int top = room.height - 1;
        // platform
        for (int fwd = halfF - 4; fwd <= halfF - 1; fwd++) {
            for (int s = -(halfL - 1); s <= halfL - 1; s++) {
                int x = room.localX(f, fwd, s), z = room.localZ(f, fwd, s);
                p.set(x, room.floor, z, fwd == halfF - 4 && Math.abs(s) <= 1
                        ? Deco.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, f, false)
                        : (Math.floorMod(s, 2) == 0 ? com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()));
            }
        }
        p.feature("setpiece:centurion_arena");
        // giant pipes on the far wall
        for (int s : new int[]{-3, -1, 1, 3}) {
            int x = room.localX(f, halfF - 1, s), z = room.localZ(f, halfF - 1, s);
            for (int rel = 1; rel <= top; rel++) p.set(x, room.floor + rel, z, Math.abs(s) == 1 ? rod() : com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState());
        }
        p.set(room.localX(f, halfF, 0), room.floor + 3, room.localZ(f, halfF, 0), Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        // colonnade
        for (int fwd = -(halfF - 2); fwd <= halfF - 5; fwd += 3) {
            for (int s : new int[]{-(halfL - 1), halfL - 1}) {
                int x = room.localX(f, fwd, s), z = room.localZ(f, fwd, s);
                if (room.nearDoor(x, z, 1)) continue;
                for (int rel = 0; rel <= top; rel++) p.set(x, room.floor + rel, z, BuiltBuilder.pillar(Theme.DWEMER, rel, top));
            }
        }
        lights(p, room);
        vents(p, room, 4);
        int side = halfL >= 5 ? 4 : 2;
        p.container(room.localX(f, halfF - 1, side), room.floor + 1, room.localZ(f, halfF - 1, side), Deco.chest(back), Deco.LOOT_BOSS);
        p.container(room.localX(f, halfF - 1, -side), room.floor + 1, room.localZ(f, halfF - 1, -side), Deco.barrel(back), Deco.LOOT_DWEMER);
        p.spawnBoss(Mobs.DWARVEN_CENTURION, room.localX(f, halfF - 3, 0), room.floor + 1, room.localZ(f, halfF - 3, 0), Painter.yaw(back), null);
        p.spawn(Mobs.DWARVEN_SPHERE, room.localX(f, 0, 3), room.floor, room.localZ(f, 0, 3), Painter.yaw(back), null);
        p.spawn(Mobs.DWARVEN_SPHERE, room.localX(f, 0, -3), room.floor, room.localZ(f, 0, -3), Painter.yaw(back), null);
    }
}
