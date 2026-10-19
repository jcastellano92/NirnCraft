package com.nirncraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The cellars beneath a fort: prison cells, armory, barracks, mess hall and storerooms, held by bandits (or
 * Forsworn raiders); the chief keeps the strongbox in the deepest room.
 */
public final class FortRooms {
    private FortRooms() {}

    /**
     * Flavor 0 = bandits, 1 = Forsworn, 2 = vampires, 3 = mages and necromancers. Forts of other cultures (a culture
     * kit other than Nord or Imperial) are held by that culture's roster instead.
     */
    static void occupant(Painter p, int flavor, int x, int y, int z, float yaw, boolean chief) {
        if (p.kit != Kit.NONE && p.kit != Kit.NORD && p.kit != Kit.IMPERIAL) {
            Kit.Spawn s = chief ? p.kit.boss() : p.kit.minion(p.roll(x, y, z, 72, 6));
            put(p, chief, s.id(), x, y, z, yaw, s.label());
            return;
        }
        switch (Math.floorMod(flavor, 4)) {
            case 1 -> put(p, chief, Mobs.forsworn(), x, y, z, yaw,
                    chief ? "Forsworn Briarheart" : Painter.entityExists("nirncraft:forsworn") ? null : "Forsworn");
            case 2 -> put(p, chief, chief ? Mobs.vampireLord() : Mobs.vampire(), x, y, z, yaw,
                    chief && !Painter.entityExists("nirncraft:vampire_lord") ? "Vampire Master" : null);
            case 3 -> put(p, chief, Mobs.necromancer(), x, y, z, yaw, chief ? "Master Wizard" : null);
            default -> put(p, chief, chief ? Mobs.banditChief() : Mobs.bandit(), x, y, z, yaw, null);
        }
    }

    /** The chief is the fort's boss. */
    private static void put(Painter p, boolean chief, String id, int x, int y, int z, float yaw, String name) {
        if (chief) p.spawnBoss(id, x, y, z, yaw, name);
        else p.spawn(id, x, y, z, yaw, name);
    }

    public static void decorate(Painter p, Room room, RandomSource r) {
        torches(p, room);
        int flavor = room.piece.flavor;
        if (room.boss()) {
            boss(p, room, flavor);
            return;
        }
        if (room.start()) {
            BarrowRooms.urns(p, room, 1);
            occupant(p, flavor, room.cx + 1, room.floor, room.cz + 1, 0f, false);
            return;
        }
        if (room.has(DungeonPiece.FLAG_SETPIECE)) {
            // the cell block: the garrison's prisoners, and their gaoler
            prison(p, room);
            p.feature("setpiece:cell_block");
        } else if (p.kit == Kit.BRETON_KEEP_RUIN || room.theme() == Theme.BRETON_KEEP_RUIN) {
            switch (room.kind() % 5) {
                case 0 -> keepGatehouse(p, room);
                case 1 -> knightlyArmory(p, room);
                case 2 -> hauntedCrypt(p, room);
                case 3 -> prison(p, room);
                default -> mess(p, room);
            }
        } else switch (room.kind() % 5) {
            case 0 -> prison(p, room);
            case 1 -> armory(p, room);
            case 2 -> barracks(p, room);
            case 3 -> mess(p, room);
            default -> stores(p, room);
        }
        occupant(p, flavor, room.cx - 1, room.floor, room.cz, 90f, false);
        if (p.chance(room.cx, room.floor, room.cz, 71, 2)) occupant(p, flavor, room.cx + 1, room.floor, room.cz - 1, 270f, false);
        if (room.piece.has(DungeonPiece.FLAG_TREASURE)) BarrowRooms.treasure(p, room, 1, Deco.LOOT_COMMON);
    }

    static void torches(Painter p, Room room) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (room.hasDoor(d)) continue;
            int x = room.localX(d, room.half(d) - 1, 0), z = room.localZ(d, room.half(d) - 1, 0);
            p.set(x, room.floor + 2, z, Deco.wallTorch(false, d.getOpposite()));
        }
    }

    static Direction blankWall(Room room) {
        for (Direction d : Direction.Plane.HORIZONTAL) if (!room.hasDoor(d)) return d;
        return null;
    }

    /** Barred cells along a doorless wall with what's left of their prisoners. */
    static void prison(Painter p, Room room) {
        p.feature("room:prison");
        Direction side = blankWall(room);
        if (side == null) {
            stores(p, room);
            return;
        }
        Direction right = side.getClockWise();
        int half = room.half(side);
        int width = room.half(right) - 1;
        Direction.Axis along = right.getAxis();
        for (int s = -width; s <= width; s++) {
            int x = room.localX(side, half - 3, s), z = room.localZ(side, half - 3, s);
            boolean divider = Math.floorMod(s, 3) == 0;
            for (int dy = 0; dy <= 2; dy++) {
                if (divider) p.set(x, room.floor + dy, z, Blocks.STONE_BRICKS.defaultBlockState());
                else p.set(x, room.floor + dy, z, Deco.bars(Blocks.IRON_BARS, along));
            }
            if (divider) {
                for (int fwd = half - 2; fwd <= half - 1; fwd++) {
                    for (int dy = 0; dy <= 2; dy++) p.set(room.localX(side, fwd, s), room.floor + dy, room.localZ(side, fwd, s), Blocks.STONE_BRICKS.defaultBlockState());
                }
            } else {
                int cx = room.localX(side, half - 1, s), cz = room.localZ(side, half - 1, s);
                int roll = p.roll(cx, room.floor, cz, 72, 5);
                if (roll == 0) p.set(cx, room.floor, cz, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(side.getOpposite())));
                else if (roll == 1) p.set(cx, room.floor, cz, Blocks.BONE_BLOCK.defaultBlockState());
                else if (roll == 2) p.set(cx, room.floor + 2, cz, Deco.chain(Direction.Axis.Y));
                else if (roll == 3) p.set(cx, room.floor, cz, com.nirncraft.worldgen.VanillaBlocks.WHITE_CARPET.defaultBlockState());
            }
        }
    }

    static void armory(Painter p, Room room) {
        p.feature("room:armory");
        BlockState[] gear = {
                Blocks.GRINDSTONE.defaultBlockState(), Blocks.SMITHING_TABLE.defaultBlockState(),
                Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.EAST), Blocks.FLETCHING_TABLE.defaultBlockState()};
        int i = 0;
        for (int x = room.minX + 1; x < room.maxX; x += 2) {
            int z = room.minZ + 1;
            if (room.nearDoor(x, z, 1) || room.onLane(x, z)) continue;
            p.set(x, room.floor, z, gear[i++ % gear.length]);
        }
        if (!room.nearDoor(room.minX + 1, room.cz, 1) && !room.onLane(room.minX + 1, room.cz)) {
            Deco.furniture(p, room.minX + 1, room.floor, room.cz, Direction.EAST,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.WEAPON_RACK);
        }
        if (!room.nearDoor(room.maxX - 1, room.cz, 1) && !room.onLane(room.maxX - 1, room.cz)) {
            Deco.furniture(p, room.maxX - 1, room.floor, room.cz, Direction.WEST,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_ARMOR_STAND);
        }
        for (int x = room.minX + 2; x < room.maxX - 1; x += 3) {
            int z = room.maxZ - 1;
            if (room.nearDoor(x, z, 1) || room.onLane(x, z)) continue;
            p.container(x, room.floor, z, Deco.barrel(Direction.NORTH), Deco.LOOT_MINOR);
        }
    }

    /** Bedrolls in rows with footlockers. */
    static void barracks(Painter p, Room room) {
        p.feature("room:barracks");
        for (int x = room.minX + 1; x < room.maxX; x += 2) {
            for (int z : new int[]{room.minZ + 1, room.maxZ - 2}) {
                if (room.nearDoor(x, z, 1) || room.nearDoor(x, z + 1, 1) || room.onLane(x, z) || room.onLane(x, z + 1)) continue;
                BlockState wool = p.chance(x, 0, z, 73, 2) ? com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.RED_CARPET.defaultBlockState();
                p.set(x, room.floor, z, wool);
                p.set(x, room.floor, z + 1, wool);
            }
        }
        p.set(room.minX + 1, room.floor, room.cz + 2, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
    }

    /** A long table with benches. */
    static void mess(Painter p, Room room) {
        p.feature("room:mess");
        boolean alongX = room.halfX() >= room.halfZ();
        Direction side = alongX ? Direction.NORTH : Direction.WEST;
        int off = 2;
        int half = alongX ? room.halfX() : room.halfZ();
        for (int a = -half + 2; a <= half - 2; a++) {
            int x = alongX ? room.cx + a : room.cx - off, z = alongX ? room.cz - off : room.cz + a;
            if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
            Deco.furniture(p, x, room.floor, z, side,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.BANQUET_TABLE);
            Deco.furniture(p, x, room.floor + 1, z, side,
                    (a & 1) == 0 ? com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_TANKARD
                            : com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_PLATTER);
            int bx = x + side.getStepX(), bz = z + side.getStepZ();
            if (room.interior(bx, bz)) {
                Deco.furniture(p, bx, room.floor, bz, side,
                        com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.BENCH);
            }
        }
        BarrowRooms.urns(p, room, 1);
    }

    static void stores(Painter p, Room room) {
        p.feature("room:stores");
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                boolean edge = x == room.minX + 1 || x == room.maxX - 1 || z == room.minZ + 1 || z == room.maxZ - 1;
                if (!edge || room.nearDoor(x, z, 1) || room.onLane(x, z)) continue;
                int roll = p.roll(x, room.floor, z, 74, 7);
                if (roll == 0) p.container(x, room.floor, z, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
                else if (roll <= 2) p.set(x, room.floor, z, Deco.pillar(Blocks.HAY_BLOCK, Direction.Axis.Y));
                else if (roll == 3) p.set(x, room.floor, z, Blocks.BARREL.defaultBlockState());
            }
        }
    }

    /** Breton keep gatehouse gallery: portcullis iron bars, collapsed masonry, and heraldic blue hangings. */
    static void keepGatehouse(Painter p, Room room) {
        p.feature("room:keep_gatehouse");
        BuiltBuilder.cornerPillars(p, room, 1);
        Direction side = blankWall(room);
        if (side != null) {
            int half = room.half(side);
            Direction right = side.getClockWise();
            for (int s = -1; s <= 1; s++) {
                int x = room.localX(side, half - 1, s), z = room.localZ(side, half - 1, s);
                for (int dy = 1; dy <= 2; dy++) {
                    p.set(x, room.floor + dy, z, Deco.bars(Blocks.IRON_BARS, right.getAxis()));
                }
            }
            for (int s : new int[]{-2, 2}) {
                int x = room.localX(side, half - 1, s), z = room.localZ(side, half - 1, s);
                if (!room.interior(x, z)) continue;
                p.set(x, room.floor + 1, z, com.nirncraft.worldgen.VanillaBlocks.BLUE_WOOL.defaultBlockState());
                p.set(x, room.floor + 2, z, Blocks.GOLD_BLOCK.defaultBlockState());
            }
        }
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
                if (p.chance(x, room.floor, z, 75, 11)) {
                    p.set(x, room.floor, z, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
                }
            }
        }
    }

    /** Breton knightly armory: heraldic banners, smithing stations, and strongboxes. */
    static void knightlyArmory(Painter p, Room room) {
        p.feature("room:knightly_armory");
        armory(p, room);
        Direction side = blankWall(room);
        if (side != null) {
            int half = room.half(side);
            for (int s : new int[]{-2, 0, 2}) {
                int x = room.localX(side, half - 1, s), z = room.localZ(side, half - 1, s);
                if (!room.interior(x, z) || room.nearDoor(x, z, 1)) continue;
                p.set(x, room.floor + 2, z, s == 0 ? Blocks.GOLD_BLOCK.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.BLUE_WOOL.defaultBlockState());
            }
        }
    }

    /** Breton haunted crypt cellar: deepslate sarcophagi, soul candles, and fallen knights. */
    static void hauntedCrypt(Painter p, Room room) {
        p.feature("room:haunted_crypt");
        boolean alongX = room.halfX() >= room.halfZ();
        int half = alongX ? room.halfX() : room.halfZ();
        for (int a = -half + 2; a <= half - 2; a += 3) {
            for (int s : new int[]{-2, 2}) {
                int x = alongX ? room.cx + a : room.cx + s, z = alongX ? room.cz + s : room.cz + a;
                if (!room.interior(x, z) || room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
                p.set(x, room.floor, z, Blocks.DEEPSLATE_TILES.defaultBlockState());
                p.set(x, room.floor + 1, z, p.chance(x, room.floor, z, 76, 2)
                        ? Deco.candle(com.nirncraft.worldgen.VanillaBlocks.BLUE_CANDLE, 3, true)
                        : Deco.skull(Blocks.SKELETON_SKULL, p.roll(x, 0, z, 77, 16)));
            }
        }
        BarrowRooms.urns(p, room, 1);
    }

    /** The chief's hall: a seat of furs, the strongbox and bodyguards. */
    static void boss(Painter p, Room room, int flavor) {
        p.feature("room:boss");
        Direction entered = room.entered();
        Direction f = entered != null ? entered.getOpposite() : Direction.NORTH;
        Direction back = f.getOpposite();
        int halfF = room.half(f);
        // throne
        int tx = room.localX(f, halfF - 1, 0), tz = room.localZ(f, halfF - 1, 0);
        Deco.furniture(p, tx, room.floor, tz, back,
                com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.THRONE);
        for (int s : new int[]{-1, 1}) {
            p.set(room.localX(f, halfF - 1, s), room.floor, room.localZ(f, halfF - 1, s), Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            p.set(room.localX(f, halfF - 1, s), room.floor + 1, room.localZ(f, halfF - 1, s), Deco.lantern(false, false));
        }
        for (int fwd = -2; fwd <= halfF - 2; fwd++) {
            p.set(room.localX(f, fwd, 0), room.floor, room.localZ(f, fwd, 0), com.nirncraft.worldgen.VanillaBlocks.RED_CARPET.defaultBlockState());
        }
        p.container(room.localX(f, halfF - 1, 3), room.floor, room.localZ(f, halfF - 1, 3), Deco.chest(back), Deco.LOOT_BOSS);
        p.container(room.localX(f, halfF - 1, -3), room.floor, room.localZ(f, halfF - 1, -3), Deco.chest(back), Deco.LOOT_COMMON);
        occupant(p, flavor, room.localX(f, halfF - 2, 0), room.floor, room.localZ(f, halfF - 2, 0), Painter.yaw(back), true);
        occupant(p, flavor, room.localX(f, 0, 2), room.floor, room.localZ(f, 0, 2), Painter.yaw(back), false);
        occupant(p, flavor, room.localX(f, 0, -2), room.floor, room.localZ(f, 0, -2), Painter.yaw(back), false);
        BuiltBuilder.cornerPillars(p, room, 2);
    }
}
