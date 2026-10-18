package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The rooms a dungeon is remembered by. The planner marks one room on the way to the boss
 * ({@link DungeonPiece#FLAG_SETPIECE}); its theme dresses it as its set piece:
 * <ul>
 *     <li>barrows: the sarcophagus hall, upright coffins down both walls whose dead wake as you pass; and the hall of
 *     stories (the puzzle room before the gate), its walls carved with panels;</li>
 *     <li>Dwemer ruins: the steam machinery hall (boilers, pipe galleries, gears, vents);</li>
 *     <li>caves: the waterfall cavern, a fall from a crack in the roof into a pool and a stream across the floor;</li>
 *     <li>mines: the ore-vein gallery; vampire, necromancer and hagraven lairs: their ritual places;</li>
 *     <li>culture ruins: the sanctum of the culture, a ring of lights around its relic.</li>
 * </ul>
 */
public final class SetPieces {
    private SetPieces() {}

    // ------------------------------------------------------------------ barrow

    /** Upright sarcophagi in the long walls, each with a sleeper; the dead stir in turn as a player walks the aisle. */
    static void sarcophagusHall(Painter p, Room room) {
        boolean alongX = room.halfX() >= room.halfZ();
        int half = alongX ? room.halfX() : room.halfZ();
        int sleepers = 0, max = 2 + room.tier() / 2;
        for (Direction wall : alongX ? new Direction[]{Direction.NORTH, Direction.SOUTH} : new Direction[]{Direction.WEST, Direction.EAST}) {
            Direction inward = wall.getOpposite();
            for (int a = -half + 2; a <= half - 2; a += 2) {
                int x = alongX ? room.cx + a : (wall == Direction.WEST ? room.minX + 1 : room.maxX - 1);
                int z = alongX ? (wall == Direction.NORTH ? room.minZ + 1 : room.maxZ - 1) : room.cz + a;
                if (room.nearDoor(x, z, 1) || room.onLane(x, z)) continue;
                // the coffin: polished sides, a carved lid over the sleeper's head
                Direction side = alongX ? Direction.EAST : Direction.SOUTH;
                for (int s : new int[]{-1, 1}) {
                    int fx = x + side.getStepX() * s, fz = z + side.getStepZ() * s;
                    if (room.nearDoor(fx, fz, 0) || room.onLane(fx, fz)) continue;
                    for (int dy = 0; dy <= 2; dy++) p.set(fx, room.floor + dy, fz, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                }
                p.set(x, room.floor + 2, z, Blocks.CHISELED_DEEPSLATE.defaultBlockState());
                p.air(x, room.floor, z);
                p.air(x, room.floor + 1, z);
                if (sleepers < max && p.chance(x, room.floor, z, 520, 2)) {
                    sleepers++;
                    p.spawnDormant(BarrowRooms.foe(room), x, room.floor, z, Painter.yaw(inward));
                } else {
                    p.set(x, room.floor, z, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(inward)));
                }
            }
        }
        if (sleepers == 0) p.spawnDormant(BarrowRooms.foe(room), room.cx + (alongX ? 2 : 0), room.floor, room.cz + (alongX ? 0 : 2), 0f);
        // the aisle: braziers down the middle, soul light on chains
        for (int a = -half + 3; a <= half - 3; a += 4) {
            for (int s : new int[]{-2, 2}) {
                int x = alongX ? room.cx + a : room.cx + s, z = alongX ? room.cz + s : room.cz + a;
                if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
                p.set(x, room.floor, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                p.set(x, room.floor + 1, z, Deco.campfire(true, true));
            }
        }
        for (int y = room.maxY - 1; y >= room.maxY - 2 && room.height >= 6; y--) p.set(room.cx, y, room.cz, Deco.chain(Direction.Axis.Y));
        p.set(room.cx, room.height >= 6 ? room.maxY - 3 : room.maxY - 1, room.cz, Deco.lantern(true, true));
        p.feature("setpiece:sarcophagus_hall");
    }

    /** The carved walls before the sealed door: panels of the dead's deeds, set in alternating stone. */
    static void hallOfStories(Painter p, Room room) {
        for (int x = room.minX; x <= room.maxX; x++) {
            for (int z = room.minZ; z <= room.maxZ; z++) {
                if (!room.onWall(x, z)) continue;
                boolean corner = (x == room.minX || x == room.maxX) && (z == room.minZ || z == room.maxZ);
                if (corner) continue;
                int along = x == room.minX || x == room.maxX ? z : x;
                for (int dy = 1; dy <= Math.min(3, room.height - 2); dy++) {
                    if (room.doorway(x, room.floor + dy, z) || room.doorway(x, room.floor, z)) continue;
                    int panel = Math.floorMod(along, 4);
                    BlockState s = panel == 0 ? Blocks.POLISHED_DEEPSLATE.defaultBlockState()
                            : dy == 2 ? (panel == 2 ? Blocks.CHISELED_DEEPSLATE.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState())
                            : Blocks.DEEPSLATE_TILES.defaultBlockState();
                    p.set(x, room.floor + dy, z, s);
                }
            }
        }
        // a runner down the room, and candles at the panels' feet
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                boolean ring = x == room.minX + 1 || x == room.maxX - 1 || z == room.minZ + 1 || z == room.maxZ - 1;
                if (ring && !room.nearDoor(x, z, 1) && Math.floorMod(x + z, 4) == 0) p.set(x, room.floor, z, Deco.candle(Blocks.CANDLE, 2, true));
            }
        }
        p.feature("setpiece:hall_of_stories");
    }

    // ------------------------------------------------------------------ Dwemer

    /** Boilers, a pipe gallery under the roof, gears turning in the walls, and vents breathing steam. */
    static void machineHall(Painter p, Room room) {
        int top = room.maxY - 1;
        // twin boilers with glowing cores
        for (int[] c : new int[][]{{-1, -1}, {1, 1}, {-1, 1}, {1, -1}}) {
            int bx = room.cx + c[0] * Math.max(2, room.halfX() - 2), bz = room.cz + c[1] * Math.max(2, room.halfZ() - 2);
            if (room.onLane(bx, bz) || room.nearDoor(bx, bz, 1) || !room.interior(bx, bz)) continue;
            for (int y = room.floor; y <= Math.min(room.floor + 3, top - 1); y++) {
                p.set(bx, y, bz, y == room.floor + 1 ? Blocks.OCHRE_FROGLIGHT.defaultBlockState() : com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState());
            }
            for (int y = room.floor + 4; y < room.maxY; y++) p.set(bx, y, bz, DwemerRooms.rod());
        }
        // the pipe gallery: copper mains across the roof
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z : new int[]{room.cz - 2, room.cz + 2}) {
                if (room.height >= 6 && !room.nearDoor(x, z, 0)) p.set(x, top, z, com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState());
            }
        }
        // gears in the walls (grindstones), steam vents in the floor
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (room.hasDoor(d)) continue;
            for (int s : new int[]{-2, 2}) {
                int x = room.localX(d, room.half(d), s), z = room.localZ(d, room.half(d), s);
                p.set(x, room.floor + 2, z, Blocks.GRINDSTONE.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.GrindstoneBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
                        .setValue(net.minecraft.world.level.block.GrindstoneBlock.FACING, d.getOpposite()));
            }
        }
        DwemerRooms.vents(p, room, 4);
        p.feature("setpiece:machine_hall");
    }

    // ------------------------------------------------------------------ culture ruins

    /** The heart of a culture's ruin before its last chamber: a ring of the culture's lights around its relic. */
    static void sanctum(Painter p, Room room, Kit kit, Kit.Palette pal) {
        int r = Math.max(2, Math.min(room.halfX(), room.halfZ()) - 2);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int x = room.cx + (int) Math.round(Math.cos(a) * r), z = room.cz + (int) Math.round(Math.sin(a) * r);
            if (room.onLane(x, z) || room.nearDoor(x, z, 1) || !room.interior(x, z)) continue;
            p.set(x, room.floor, z, pal.trim);
            p.set(x, room.floor + 1, z, pal.light.hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)
                    ? pal.light.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, false) : pal.light);
        }
        // the relic, raised where the lanes cross would block them: set it to one side of the middle
        int rx = room.cx + 2, rz = room.cz + 2;
        if (!room.onLane(rx, rz) && !room.nearDoor(rx, rz, 1)) {
            p.set(rx, room.floor, rz, pal.pillar(0, 1));
            p.set(rx, room.floor + 1, rz, CultureRooms.centrepiece(kit, pal));
        }
        p.feature("setpiece:" + kit.id + "_sanctum");
    }

    // ------------------------------------------------------------------ caves and lairs

    static void natural(Painter p, Room room) {
        switch (room.theme()) {
            case MINE -> oreVein(p, room);
            case VAMPIRE_LAIR -> bloodPool(p, room);
            case NECROMANCER_LAIR -> ritualCircle(p, room);
            case HAGRAVEN_LAIR -> briarAltar(p, room);
            default -> waterfall(p, room);
        }
    }

    /**
     * Water falls from a crack in the roof into a pool by the wall, and a stream crosses the cavern (stepping over the
     * walking lanes on rock). Moss, ferns and glow lichen grow in the spray.
     */
    static void waterfall(Painter p, Room room) {
        int[] s = NaturalRooms.edgeSpot(p, room, 900);
        if (s == null) return;
        int px = s[0], pz = s[1];
        BlockState water = Blocks.WATER.defaultBlockState();
        // the pool (sunk into the floor layer, only where rock holds it)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = px + dx, z = pz + dz;
                if (room.onLane(x, z) || room.nearDoor(x, z, 1) || !NaturalRooms.inside(p, room, x, z, 0.95)) continue;
                if (!p.ok(x, room.floor - 1, z)) continue;
                p.set(x, room.floor - 2, z, NaturalBuilder.rock(room.floor - 2));
                p.set(x, room.floor - 1, z, water);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int nx = x + d.getStepX(), nz = z + d.getStepZ();
                    if (p.ok(nx, room.floor - 1, nz) && Painter.open(p.get(nx, room.floor - 1, nz))) p.set(nx, room.floor - 1, nz, Blocks.MOSS_BLOCK.defaultBlockState());
                }
            }
        }
        // the fall: a source in a crack of the roof, falling water down to the pool
        if (p.ok(px, room.floor, pz)) {
            int roof = -1;
            for (int y = room.floor + 2; y < room.maxY + 1; y++) {
                if (!p.get(px, y, pz).isAir()) {
                    roof = y;
                    break;
                }
            }
            if (roof > room.floor + 2) {
                p.set(px, roof, pz, water);
                for (Direction d : Direction.Plane.HORIZONTAL) p.set(px + d.getStepX(), roof, pz + d.getStepZ(), NaturalBuilder.rock(roof));
                p.set(px, roof + 1, pz, NaturalBuilder.rock(roof + 1));
                BlockState falling = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
                for (int y = room.floor; y < roof; y++) p.set(px, y, pz, falling);
                p.scheduleFluid(px, roof, pz);
            }
        }
        // the stream: away from the pool to the far wall, bridged by rock where the lanes cross
        Direction away = BarrowRooms.towardCenter(room, px, pz);
        int x = px + away.getStepX() * 2, z = pz + away.getStepZ() * 2;
        for (int k = 0; k < 14; k++, x += away.getStepX(), z += away.getStepZ()) {
            if (!room.interior(x, z) || !NaturalRooms.inside(p, room, x, z, 0.9)) break;
            if (room.onLane(x, z) || room.nearDoor(x, z, 1) || !p.ok(x, room.floor - 1, z)) continue;
            p.set(x, room.floor - 2, z, NaturalBuilder.rock(room.floor - 2));
            p.set(x, room.floor - 1, z, water);
        }
        // spray-fed growth
        BlockState lichen = Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        for (int i = 0; i < 6; i++) {
            int[] g = NaturalRooms.spot(p, room, 910 + i);
            if (g == null || !p.ok(g[0], room.floor, g[1])) continue;
            p.setIfOpen(g[0], room.floor, g[1], i % 3 == 0 ? lichen : i % 3 == 1 ? Blocks.FERN.defaultBlockState() : Blocks.MOSS_CARPET.defaultBlockState());
        }
        p.feature("setpiece:waterfall");
    }

    /** A rich vein laid bare: ore in the walls, scaffold props, a loaded cart. */
    static void oreVein(Painter p, Room room) {
        for (int i = 0; i < 10; i++) {
            int[] s = NaturalRooms.edgeSpot(p, room, 930 + i);
            if (s == null) continue;
            for (int dy = 0; dy <= 2; dy++) {
                int y = room.floor + dy;
                if (p.ok(s[0], y, s[1]) && !Painter.open(p.get(s[0], y, s[1]))) p.set(s[0], y, s[1], Deco.ore(p, s[0], y, s[1], y < 0));
            }
            if (i % 4 == 0) p.setIfOpen(s[0], room.floor, s[1], Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0));
        }
        int[] c = NaturalRooms.spot(p, room, 945);
        if (c != null) {
            p.set(c[0], room.floor, c[1], Blocks.RAIL.defaultBlockState());
            p.chestMinecart(c[0], room.floor, c[1], Deco.LOOT_MINE);
        }
        p.feature("setpiece:ore_vein");
    }

    /** The vampires' blood pool ringed with cages of their cattle (long dead). */
    static void bloodPool(Painter p, Room room) {
        for (int i = 0; i < 3; i++) {
            int[] s = NaturalRooms.edgeSpot(p, room, 950 + i);
            if (s == null) continue;
            for (int dy = 0; dy <= 2; dy++) p.set(s[0], room.floor + dy, s[1], dy == 2 ? Blocks.IRON_BARS.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState());
            p.set(s[0], room.floor + 3, s[1], com.skycraft.worldgen.VanillaBlocks.CHAIN.defaultBlockState());
        }
        int[] a = NaturalRooms.spot(p, room, 955);
        if (a != null) {
            p.set(a[0], room.floor - 1, a[1], Blocks.REDSTONE_BLOCK.defaultBlockState());
            p.set(a[0], room.floor, a[1], Deco.candle(com.skycraft.worldgen.VanillaBlocks.RED_CANDLE, 4, true));
        }
        p.feature("setpiece:blood_pool");
    }

    /** A summoning circle of soul fire and black candles, a fresh body at its centre. */
    static void ritualCircle(Painter p, Room room) {
        int[] c = NaturalRooms.spot(p, room, 960);
        if (c == null) return;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int x = c[0] + (int) Math.round(Math.cos(a) * 2), z = c[1] + (int) Math.round(Math.sin(a) * 2);
            if (room.onLane(x, z) || !NaturalRooms.inside(p, room, x, z, 0.9)) continue;
            p.set(x, room.floor - 1, z, Blocks.SOUL_SOIL.defaultBlockState());
            p.set(x, room.floor, z, i % 2 == 0 ? Blocks.SOUL_FIRE.defaultBlockState() : Deco.candle(com.skycraft.worldgen.VanillaBlocks.BLACK_CANDLE, 3, true));
        }
        p.set(c[0], room.floor, c[1], Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X));
        p.feature("setpiece:ritual_circle");
    }

    /** The hagravens' altar of briars and antlers, where hearts are taken. */
    static void briarAltar(Painter p, Room room) {
        int[] c = NaturalRooms.edgeSpot(p, room, 970);
        if (c == null) return;
        p.set(c[0], room.floor, c[1], Blocks.ROOTED_DIRT.defaultBlockState());
        p.set(c[0], room.floor + 1, c[1], Blocks.MANGROVE_ROOTS.defaultBlockState());
        p.set(c[0], room.floor + 2, c[1], Deco.skull(Blocks.SKELETON_SKULL, 0));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = c[0] + d.getStepX(), z = c[1] + d.getStepZ();
            if (room.onLane(x, z) || !NaturalRooms.inside(p, room, x, z, 0.9)) continue;
            p.set(x, room.floor, z, d.getAxis() == Direction.Axis.X ? Blocks.SWEET_BERRY_BUSH.defaultBlockState() : Deco.candle(com.skycraft.worldgen.VanillaBlocks.BROWN_CANDLE, 2, true));
        }
        p.feature("setpiece:briar_altar");
    }
}
