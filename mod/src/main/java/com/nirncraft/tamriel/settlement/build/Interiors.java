package com.nirncraft.tamriel.settlement.build;

import com.nirncraft.tamriel.settlement.AnchorType;
import com.nirncraft.tamriel.settlement.build.Shells.Room;
import com.nirncraft.tamriel.settlement.kit.Palette;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Furnishing of building interiors by function: households, inns, smithies, shops, temples, palaces, barracks.
 * Every bed, counter and throne declares its anchor; containers carry household loot (taking it is theft) and are
 * recorded as owned.
 */
public final class Interiors {
    private Interiors() {}

    // ------------------------------------------------------------------ homes

    /**
     * A household: 3 distinct layout families selected deterministically so houses in a settlement never share
     * repetitive identical interiors. Includes interior partitions with doorways, distinct hearth placements (side,
     * back, or central), separated bedrooms, entrance storage by the door, and bookshelves/study desks in rich homes.
     *
     * @param bed     anchor type of the beds ({@link AnchorType#BED}, or {@link AnchorType#HOUSE_BED} for a house for sale)
     * @param chimney whether the shell has a chimney at (w - 3, back wall); otherwise the fire sits in the middle
     */
    public static void house(Canvas c, Palette p, Room r, int doorX, int beds, boolean rich, AnchorType bed, boolean chimney, String loot) {
        int family = c.roll(r.x0(), r.y(), r.z0(), 37, 3);
        house(c, p, r, doorX, beds, rich, bed, chimney, loot, family);
    }

    public static void house(Canvas c, Palette p, Room r, int doorX, int beds, boolean rich, AnchorType bed, boolean chimney, String loot, int family) {
        if (r.w() < 3 || r.d() < 3) return;
        int y = r.y();
        String chestLoot = bed == AnchorType.HOUSE_BED ? null : loot;
        String foodLoot = bed == AnchorType.HOUSE_BED ? null : "food";
        int fam = Math.floorMod(family, 3);

        // Entrance storage right inside the front door (never blocking doorX)
        int entX = doorX - 1 >= r.x0() ? doorX - 1 : doorX + 1 <= r.x1() ? doorX + 1 : r.x0();
        if (entX != doorX && !c.isReserved(entX, y, r.z0())) {
            Props.barrel(c, entX, y, r.z0(), foodLoot);
            Props.lantern(c, p, entX, y + 1, r.z0(), false);
        }

        if (fam == 0 && r.d() >= 7 && r.w() >= 5) {
            // Family 0: Front parlour & dining room + partitioned rear bedroom & kitchen nook
            int pz = r.z1() - 3;
            int pDoorX = Math.max(r.x0() + 1, Math.min(r.x1() - 1, r.cx()));
            partitionH(c, p, r.x0(), r.x1(), pz, y, r.ceil(), pDoorX, rich);

            // Rear bedroom (z = pz + 1 .. r.z1())
            int placed = placeBedsInRange(c, p, r.x0(), r.x1(), r.z1() - 1, y, beds, bed, chestLoot, pDoorX);
            if (placed == 0 && beds > 0) {
                placeBedsInRange(c, p, r.x0(), r.x1(), r.z1() - 1, y, 1, bed, chestLoot, -99);
            }
            if (r.x1() != pDoorX) {
                c.setIfOpen(r.x1(), y, pz + 1, Props.cookingPot());
            }

            // Side hearth in front room
            int hz = Math.max(r.z0() + 1, pz - 2);
            if (chimney) {
                Props.hearth(c, p, r.x1() - 1, y, r.z1(), Direction.NORTH);
            } else {
                c.setIfOpen(r.x0(), y, hz, B.lit(p.hearth, true));
                c.setIfOpen(r.x0(), y + 1, hz, Props.cookingPot());
            }

            // Dining table & chairs in front room
            int tx = Math.min(r.x1() - 1, r.cx() + 1);
            int tz = Math.max(r.z0() + 1, (r.z0() + pz) / 2);
            if (tx == doorX && tz <= r.z0() + 1) tx = Math.max(r.x0() + 1, tx - 1);
            diningSet(c, p, tx, y, tz, r, rich);

            if (rich) {
                Props.bookshelf(c, r.x1(), y, r.z0() + 1, Direction.WEST);
                Props.bookshelf(c, r.x1(), y + 1, r.z0() + 1, Direction.WEST);
                Props.pot(c, r.x1(), y + 2, r.z0() + 1, 11);
                Props.wardrobe(c, p, r.x0(), y, r.z1(), Direction.EAST, chestLoot);
            } else {
                Props.dresser(c, p, r.x0(), y, r.z1(), Direction.EAST, chestLoot);
            }
            if (r.ceil() - y >= 3) {
                Props.wallClutter(c, p, r.x0(), y + 2, Math.max(r.z0() + 1, tz),
                        com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.WALL_ANTLER_TROPHY, Direction.EAST);
            }
            Props.rug(c, rich ? p.carpet : p.carpet2, Math.max(r.x0() + 1, r.cx() - 1), y, r.z0() + 1,
                    Math.min(r.x1() - 1, r.cx() + 1), Math.max(r.z0() + 1, pz - 1));
        } else if (fam == 1 && r.w() >= 7 && r.d() >= 6) {
            // Family 1: Long hearth hall + side-partitioned sleeping chamber
            boolean sideRight = doorX <= r.cx();
            int px = sideRight ? r.x1() - 3 : r.x0() + 3;
            int pDoorZ = r.cz();
            partitionV(c, p, px, r.z0() + 2, r.z1(), y, r.ceil(), pDoorZ, rich);

            int sx0 = sideRight ? px + 1 : r.x0();
            int sx1 = sideRight ? r.x1() : px - 1;
            int hx0 = sideRight ? r.x0() : px + 1;
            int hx1 = sideRight ? px - 1 : r.x1();
            int hcx = (hx0 + hx1) / 2;

            // Beds inside the side chamber
            placeBedsInRange(c, p, sx0, sx1, r.z1() - 1, y, Math.max(1, beds), bed, chestLoot, -99);
            if (rich) {
                int deskX = sideRight ? sx1 : sx0;
                Props.desk(c, p, deskX, y, r.z0() + 2, Direction.SOUTH);
                Props.chair(c, p, deskX, y, r.z0() + 3, Direction.SOUTH);
                Props.wardrobe(c, p, sideRight ? sx0 : sx1, y, r.z0() + 2, sideRight ? Direction.EAST : Direction.WEST, chestLoot);
            } else {
                c.setIfOpen(sideRight ? sx1 : sx0, y, r.z0() + 2, Props.cookingPot());
                Props.dresser(c, p, sideRight ? sx0 : sx1, y, r.z0() + 2, sideRight ? Direction.EAST : Direction.WEST, chestLoot);
            }

            // Back hearth in the main hall
            if (chimney) {
                Props.hearth(c, p, Math.min(hx1 - 1, Math.max(hx0 + 1, hcx)), y, r.z1(), Direction.NORTH);
            } else {
                c.setIfOpen(hcx, y, r.z1(), B.lit(p.hearth, true));
                c.setIfOpen(hcx, y + 1, r.z1(), Props.cookingPot());
            }

            // Dining table & chairs in the main hall
            diningSet(c, p, hcx, y, r.cz(), r, rich);
            if (rich) {
                Direction shelfFace = sideRight ? Direction.EAST : Direction.WEST;
                for (int z = r.z0() + 1; z <= r.z1() - 2; z += 2) {
                    int bx = sideRight ? hx0 : hx1;
                    Props.bookshelf(c, bx, y, z, shelfFace);
                    Props.bookshelf(c, bx, y + 1, z, shelfFace);
                }
            }
            if (r.ceil() - y >= 3) {
                Props.wallClutter(c, p, hcx, y + 2, r.z1(),
                        com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.WALL_SHIELD_PLAQUE, Direction.NORTH);
            }
            Props.rug(c, rich ? p.carpet : p.carpet2, Math.max(hx0, hcx - 1), y, r.z0() + 1,
                    Math.min(hx1, hcx + 1), Math.max(r.z0() + 1, r.z1() - 2));
        } else {
            // Family 2: Central hearth hall + twin rear alcoves (kitchen nook & sleeping alcove)
            if (r.d() >= 7 && r.w() >= 6) {
                int pz = r.z1() - 3;
                int dLeft = Math.max(r.x0() + 1, r.cx() - 2);
                int dRight = Math.min(r.x1() - 1, r.cx() + 2);
                BlockState partWall = rich ? p.wall : p.planks;
                for (int x = r.x0(); x <= r.x1(); x++) {
                    if (x == dLeft || x == dRight) {
                        fillWallColumn(c, x, y + 2, r.ceil(), pz, partWall);
                        if (!c.isReserved(x, y, pz)) c.door(x, y, pz, p.door, Direction.SOUTH);
                        continue;
                    }
                    if (!c.isReserved(x, y, pz)) fillWallColumn(c, x, y, r.ceil(), pz, partWall);
                    else fillWallColumn(c, x, y + 2, r.ceil(), pz, partWall);
                }
                for (int z = pz + 1; z <= r.z1(); z++) {
                    if (!c.isReserved(r.cx(), y, z)) fillWallColumn(c, r.cx(), y, r.ceil(), z, partWall);
                    else fillWallColumn(c, r.cx(), y + 2, r.ceil(), z, partWall);
                }
                // Left rear alcove: kitchen & pantry
                c.setIfOpen(r.x0(), y, r.z1(), Props.cookingPot());
                if (r.x0() + 1 < r.cx()) c.setIfOpen(r.x0() + 1, y, r.z1(), B.face(B.s(Blocks.SMOKER), Direction.NORTH));
                c.setIfOpen(r.x0(), y, pz + 1, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
                Props.cabinet(c, p, Math.max(r.x0(), r.cx() - 1), y, r.z1(), Direction.NORTH, foodLoot);
                // Right rear alcove: bedroom
                placeBedsInRange(c, p, r.cx() + 1, r.x1(), r.z1() - 1, y, Math.max(1, beds), bed, chestLoot, -99);
                Props.wardrobe(c, p, r.x1(), y, pz + 1, Direction.WEST, chestLoot);
            } else {
                // Compact cottage/hovel fallback
                placeBedsInRange(c, p, r.x0(), r.x1(), r.z1() - 1, y, beds, bed, chestLoot, doorX);
            }

            // Central hearth
            int hz = r.d() >= 7 ? r.z0() + 2 : r.cz();
            if (chimney) {
                Props.hearth(c, p, r.x1() - 1, y, r.z1(), Direction.NORTH);
            } else if (r.w() >= 5 && r.d() >= 5 && !c.isReserved(r.cx(), y, hz)) {
                c.set(r.cx(), y - 1, hz, p.foundation);
                c.set(r.cx(), y, hz, B.lit(p.hearth, true));
                c.set(r.cx(), y + 1, hz, Props.cookingPot());
            }

            // Front dining table & chairs
            int tx = doorX <= r.cx() ? Math.min(r.x1() - 1, r.cx() + 2) : Math.max(r.x0() + 1, r.cx() - 2);
            int tz = Math.min(r.z1() - 2, r.z0() + 1);
            diningSet(c, p, tx, y, tz, r, rich);

            if (rich && r.w() >= 5) {
                Props.bookshelf(c, r.x0(), y, r.z0() + 2, Direction.EAST);
                Props.bookshelf(c, r.x0(), y + 1, r.z0() + 2, Direction.EAST);
                Props.pot(c, r.x1(), y, r.z0() + 2, 3);
            }
            Props.rug(c, rich ? p.carpet : p.carpet2, Math.max(r.x0(), r.cx() - 1), y, Math.max(r.z0() + 1, hz - 1),
                    Math.min(r.x1(), r.cx() + 1), Math.min(r.z1() - 2, hz + 1));
        }
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    private static int placeBedsInRange(Canvas c, Palette p, int x0, int x1, int footZ, int y, int beds, AnchorType bed, String loot, int avoidX) {
        int placed = 0;
        for (int x = x0; x <= x1 && placed < beds; x += 2) {
            if (x == avoidX) continue;
            int before = c.bedCount();
            c.bed(x, y, footZ, p.bed, Direction.SOUTH);
            if (c.bedCount() == before) continue;
            c.anchor(bed, x, y, footZ);
            if (footZ - 1 >= 1) Props.chest(c, x, y, footZ - 1, Direction.NORTH, loot);
            if (x + 1 <= x1 && x + 1 != avoidX && !c.isReserved(x + 1, y, footZ + 1)) {
                Props.nightstand(c, p, x + 1, y, footZ + 1, Direction.NORTH);
            }
            placed++;
        }
        return placed;
    }

    private static void diningSet(Canvas c, Palette p, int tx, int y, int tz, Room r, boolean rich) {
        if (tx < r.x0() || tx > r.x1() || tz < r.z0() || tz > r.z1()) return;
        if (c.isReserved(tx, y, tz)) return;
        Props.table(c, p, tx, y, tz);
        if (tz + 1 <= r.z1() && !c.isReserved(tx, y, tz + 1)) Props.chair(c, p, tx, y, tz + 1, Direction.SOUTH);
        if (tz - 1 >= r.z0() && !c.isReserved(tx, y, tz - 1)) Props.chair(c, p, tx, y, tz - 1, Direction.NORTH);
        if (tx + 1 <= r.x1() && !c.isReserved(tx + 1, y, tz)) Props.chair(c, p, tx + 1, y, tz, Direction.EAST);
        var clutterKind = rich
                ? (c.chance(tx, y, tz, 19, 2)
                    ? com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_GOBLETS
                    : com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_PLATTER)
                : (c.chance(tx, y, tz, 19, 2)
                    ? com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_TANKARD
                    : com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_PLATTER);
        Props.clutter(c, p, tx, y + 1, tz, clutterKind, Direction.SOUTH);
    }

    private static void fillWallColumn(Canvas c, int x, int y, int ceil, int z, BlockState wall) {
        for (int yy = y; yy < ceil; yy++) c.set(x, yy, z, wall);
        if (!c.recording()) {
            for (int yy = ceil; yy < ceil + 12 && c.get(x, yy, z).isAir(); yy++) c.set(x, yy, z, wall);
        }
    }

    /** Horizontal interior partition wall along row {@code z} with a door at {@code doorX}, skipping reserved stair cells. */
    public static void partitionH(Canvas c, Palette p, int x0, int x1, int z, int y, int ceil, int doorX, boolean rich) {
        BlockState wall = rich ? p.wall : p.planks;
        for (int x = x0; x <= x1; x++) {
            if (c.isReserved(x, y, z) || c.isReserved(x, y + 1, z)) {
                fillWallColumn(c, x, y + 2, ceil, z, wall);
                continue;
            }
            if (x == doorX) {
                fillWallColumn(c, x, y + 2, ceil, z, wall);
                c.door(x, y, z, p.door, Direction.SOUTH);
                c.keepClear(x, y, y + 1, z - 1);
                c.keepClear(x, y, y + 1, z + 1);
                continue;
            }
            fillWallColumn(c, x, y, ceil, z, wall);
        }
    }

    /** Vertical interior partition wall along column {@code x} with a door at {@code doorZ}, skipping reserved stair cells. */
    public static void partitionV(Canvas c, Palette p, Room r, int x, int doorZ) {
        partitionV(c, p, x, r.z0(), r.z1(), r.y(), r.ceil(), doorZ, true);
    }

    public static void partitionV(Canvas c, Palette p, int x, int z0, int z1, int y, int ceil, int doorZ, boolean rich) {
        BlockState wall = rich ? p.wall : p.planks;
        for (int z = z0; z <= z1; z++) {
            if (c.isReserved(x, y, z) || c.isReserved(x, y + 1, z)) {
                fillWallColumn(c, x, y + 2, ceil, z, wall);
                continue;
            }
            if (z == doorZ) {
                fillWallColumn(c, x, y + 2, ceil, z, wall);
                c.door(x, y, z, p.door, Direction.EAST);
                c.keepClear(x - 1, y, y + 1, z);
                c.keepClear(x + 1, y, y + 1, z);
                continue;
            }
            fillWallColumn(c, x, y, ceil, z, wall);
        }
    }

    /** A custom 3D weapon rack with visible steel swords & shield against a wall, plus an upper shield plaque. */
    public static void weaponRack(Canvas c, Palette p, int x, int y, int z, Direction face) {
        if (c.isReserved(x, y, z)) return;
        Props.weaponRack(c, p, x, y, z, face);
        Props.wallClutter(c, p, x, y + 1, z,
                com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.WALL_SHIELD_PLAQUE, face);
    }

    // ------------------------------------------------------------------ inns

    /**
     * An inn. Ground floor: the common room with a grand hearth or fire pit, 4+ dining tables with chairs for eating,
     * an L-shaped bar counter in a back corner with a seated stool for the innkeeper and kegs behind, a kitchen alcove,
     * and a bard's stage corner. Upstairs: see {@link #innUpper}. Without an upper floor, partitioned private guest
     * rooms with locked doors, beds and writing desks are built along the back wall.
     */
    public static void inn(Canvas c, Palette p, Room hall, Room upstairs, int doorX, boolean firePit) {
        int y = hall.y();
        int cz = hall.z1() - 3;
        if (cz <= hall.z0() + 2) cz = hall.z1() - 2;
        // the bar sits in the back left corner, or the right one when the stairs claim the left
        int s = 1, xa = hall.x0();
        if (c.anyReserved(xa, cz, xa + 4, hall.z1(), y)) {
            s = -1;
            xa = hall.x1();
        }
        int xe = xa + s * 4;
        boolean bar = hall.w() >= 9 && cz > hall.z0() + 2 && !c.anyReserved(Math.min(xa, xe), cz, Math.max(xa, xe), hall.z1(), y);
        if (bar) {
            // Keep the walkthrough passage beside xe unobstructed so the innkeeper is never sealed in
            if (xe + s >= hall.x0() && xe + s <= hall.x1()) {
                c.keepClear(xe + s, y, y + 1, cz);
                c.keepClear(xe + s, y, y + 1, cz + 1);
            }
            for (int i = 0; i <= 4; i++) {
                int x = xa + s * i;
                Props.counter(c, p, x, y, cz, Direction.NORTH);
                c.set(x, y + 1, cz, B.s(Blocks.AIR));
            }
            Props.counter(c, p, xe, y, cz + 1, s == 1 ? Direction.EAST : Direction.WEST);
            Props.clutter(c, p, xa + s, y + 1, cz,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_TANKARD, Direction.NORTH);
            Props.clutter(c, p, xa + s * 2, y + 1, cz,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_LEDGER, Direction.NORTH);
            Props.clutter(c, p, xa + s * 3, y + 1, cz,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_PLATTER, Direction.NORTH);
            for (int i = 0; i < 4; i++) {
                int x = xa + s * i;
                // one kept keg carries the inn's stock; the rest are plain empty barrels (not counted as containers)
                if (i == 1) Props.barrel(c, x, y, hall.z1(), "inn");
                else c.set(x, y, hall.z1(), B.face(B.s(Blocks.BARREL), Direction.UP));
                if (i % 2 == 0) c.set(x, y + 1, hall.z1(), B.face(B.s(Blocks.BARREL), Direction.UP));
            }
            int ix = xa + s * 2;
            c.keepClear(ix, y, y + 1, cz + 1);
            c.anchor(AnchorType.INN, ix, y, cz + 1);
            Props.lantern(c, p, ix, hall.ceil() - 1, cz + 1, true);
        } else {
            // a small hall: unsealed counter run with clear standing floor behind it and open passage at x1
            c.keepClear(hall.x1(), y, y + 1, hall.z1() - 1);
            c.keepClear(hall.x1(), y, y + 1, hall.z1() - 2);
            for (int x = hall.x0() + 1; x <= hall.x1() - 1; x++) {
                Props.counter(c, p, x, y, hall.z1() - 2, Direction.NORTH);
            }
            Props.clutter(c, p, hall.cx(), y + 1, hall.z1() - 2,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_TANKARD, Direction.NORTH);
            c.keepClear(hall.cx(), y, y + 1, hall.z1() - 1);
            c.anchor(AnchorType.INN, hall.cx(), y, hall.z1() - 1);
            Props.barrel(c, hall.x0(), y, hall.z1(), "inn");
        }
        // grand hearth / central fire pit
        if (firePit && hall.d() >= 9) {
            Props.firePit(c, p, hall.cx(), y, hall.z0() + 3, Math.max(3, Math.min(5, cz - hall.z0() - 4)), true);
        } else {
            int hx = s == 1 ? hall.x1() : hall.x0();
            Direction hIn = s == 1 ? Direction.WEST : Direction.EAST;
            Props.hearth(c, p, hx, y, hall.cz(), hIn);
            Props.rug(c, p.carpet, Math.max(hall.x0() + 1, hall.cx() - 1), y, hall.z0() + 2,
                    Math.min(hall.x1() - 1, hall.cx() + 1), Math.max(hall.z0() + 3, cz - 2));
        }
        if (hall.ceil() - y >= 3) {
            Props.wallClutter(c, p, hall.x0(), y + 2, hall.z0() + 2,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.WALL_ANTLER_TROPHY, Direction.EAST);
            Props.wallClutter(c, p, hall.x1(), y + 2, hall.z0() + 2,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.WALL_SHIELD_PLAQUE, Direction.WEST);
        }
        // 4+ dining tables for eating, either side of the fire, each with chairs at both ends
        int tableStep = (cz - 2) - (hall.z0() + 2) >= 6 ? 3 : 2;
        int tablesPlaced = 0;
        for (int z = hall.z0() + 2; z <= cz - 2; z += tableStep) {
            for (int side = -1; side <= 1; side += 2) {
                int tx = hall.cx() + side * 3;
                if (tx < hall.x0() + 1 || tx > hall.x1() - 1 || Math.abs(tx - doorX) <= 1 && z <= hall.z0() + 2) continue;
                if (c.anyReserved(tx, z - 1, tx, z + 1, y)) continue;
                Props.table(c, p, tx, y, z);
                Props.chair(c, p, tx, y, z - 1, Direction.NORTH);
                Props.chair(c, p, tx, z + 1 <= hall.z1() ? y : y, z + 1, Direction.SOUTH);
                var tableClutter = switch (tablesPlaced % 3) {
                    case 0 -> com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_TANKARD;
                    case 1 -> com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_PLATTER;
                    default -> com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_GOBLETS;
                };
                Props.clutter(c, p, tx, y + 1, z, tableClutter, Direction.SOUTH);
                tablesPlaced++;
            }
        }
        if (tablesPlaced < 4 && hall.w() >= 11) {
            for (int side = -1; side <= 1; side += 2) {
                int tx = hall.cx() + side * 5;
                int tz = hall.z0() + 4;
                if (tx >= hall.x0() + 1 && tx <= hall.x1() - 1 && tz < cz - 1 && !c.anyReserved(tx, tz - 1, tx, tz + 1, y)) {
                    Props.table(c, p, tx, y, tz);
                    Props.chair(c, p, tx, y, tz - 1, Direction.NORTH);
                    Props.chair(c, p, tx, y, tz + 1, Direction.SOUTH);
                    Props.clutter(c, p, tx, y + 1, tz,
                            com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_CANDELABRA, Direction.SOUTH);
                }
            }
        }
        // Bard's corner stage at front right
        Props.rug(c, p.carpet, hall.x1() - 1, y, hall.z0(), hall.x1(), hall.z0() + 1);
        Props.stool(c, p, hall.x1(), y, hall.z0(), Direction.NORTH);
        c.anchor(AnchorType.BARD, hall.x1() - 1, y, hall.z0() + 1);
        Props.lantern(c, p, hall.cx(), hall.ceil() - 1, hall.cz(), true);

        if (upstairs != null) {
            // Kitchen & staff alcove in the back corner opposite the bar
            int kx0 = s == 1 ? Math.max(xe + 2, hall.x1() - 4) : hall.x0();
            int kx1 = s == 1 ? hall.x1() : Math.min(xe - 2, hall.x0() + 4);
            if (kx1 - kx0 >= 3 && !c.anyReserved(kx0, cz, kx1, hall.z1(), y)) {
                int kWallX = s == 1 ? kx0 : kx1;
                boolean closeOuter = s != 1 && kx0 > 1;
                int kDoorX = (kx0 + kx1) / 2;
                for (int z = cz; z <= hall.z1(); z++) {
                    fillWallColumn(c, kWallX, y, hall.ceil(), z, p.planks);
                    if (closeOuter) fillWallColumn(c, kx0, y, hall.ceil(), z, p.planks);
                }
                for (int x = kx0; x <= kx1; x++) {
                    if (x == kDoorX) {
                        fillWallColumn(c, x, y + 2, hall.ceil(), cz, p.planks);
                        c.door(x, y, cz, p.door, Direction.NORTH);
                        continue;
                    }
                    fillWallColumn(c, x, y, hall.ceil(), cz, p.planks);
                }
                int innerK = s == 1 ? kx1 : (closeOuter ? kx0 + 1 : kx0);
                c.set(innerK, y, hall.z1(), Props.cookingPot());
                c.set(innerK, y, hall.z1() - 1, B.face(B.s(Blocks.SMOKER), s == 1 ? Direction.WEST : Direction.EAST));
                c.set(innerK, y, cz + 1, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
                Props.barrel(c, kDoorX, y, hall.z1(), "food");
                // Innkeeper's ground-floor staff bed so the inn always owns a resident BED
                int bedX = s == 1 ? kx0 + 1 : kx1 - 1;
                if (bedX != kDoorX && bedX != innerK) {
                    c.bed(bedX, y, hall.z1() - 1, p.bed, Direction.SOUTH);
                    c.anchor(AnchorType.BED, bedX, y, hall.z1() - 1);
                }
            }
            innUpper(c, p, upstairs, true);
        } else if (hall.w() >= 8 && hall.d() >= 8) {
            // 1-storey inn: build 2 to 4 private rented rooms with beds and desks along the back/front
            int built = privateRooms(c, p, hall, false, 2);
            if (built < 2) {
                int px = s == 1 ? hall.x1() - 3 : hall.x0() + 3;
                int xa2 = Math.min(px, s == 1 ? hall.x1() : hall.x0()), xb2 = Math.max(px, s == 1 ? hall.x1() : hall.x0());
                int pz = hall.z1() - 3;
                if (!c.anyReserved(xa2, pz, xb2, hall.z1(), y)) {
                    for (int z = pz; z <= hall.z1(); z++) fillWallColumn(c, px, y, hall.ceil(), z, p.wall);
                    for (int x = xa2; x <= xb2; x++) fillWallColumn(c, x, y, hall.ceil(), pz, p.wall);
                    int dx = s == 1 ? px + 1 : px - 1;
                    c.entrance(dx, y, pz, p.door, Direction.NORTH);
                    c.anchor(AnchorType.INN_DOOR, dx, y, pz);
                    int bx = s == 1 ? px + 2 : px - 2;
                    c.bed(bx, y, hall.z1() - 1, p.bed, Direction.SOUTH);
                    c.anchor(AnchorType.INN_BED, bx, y, hall.z1() - 1);
                    Props.desk(c, p, dx, y, hall.z1(), Direction.NORTH);
                    Props.chair(c, p, dx, y, hall.z1() - 1, Direction.NORTH);
                    Props.wardrobe(c, p, bx, y, pz + 1, Direction.WEST, null);
                }
            }
        }
    }

    /**
     * The guest floor of an inn: a central corridor with 4 to 6 private rented rooms (locked {@link AnchorType#INN_DOOR},
     * {@link AnchorType#INN_BED}, storage chest, lantern, and writing desk + chair) along the back and front walls,
     * plus dormitory {@link AnchorType#GUEST_BED}s and staff {@link AnchorType#BED}s.
     */
    public static void innUpper(Canvas c, Palette p, Room r, boolean dorm) {
        int y = r.y();
        int rooms = 0;
        boolean frontRooms = false;
        if (r.w() >= 6 && r.d() >= 7) {
            rooms += privateRooms(c, p, r, true, 4);
            if (rooms < 3 && r.d() >= 11) {
                int more = privateRooms(c, p, r, false, Math.min(4 - rooms, 2));
                frontRooms = more > 0;
                rooms += more;
            }
        }
        if (rooms == 0) {
            // a narrow wing: two plain bunks, nothing to lock
            for (int x = r.x0(); x + 1 <= r.x1() && x < r.x0() + 6; x += 3) {
                c.bed(x, y, r.z1() - 1, p.bed, Direction.SOUTH);
                c.anchor(AnchorType.INN_BED, x, y, r.z1() - 1);
            }
        } else if (dorm) {
            // Only place a traveller bench/bed at the open end of the corridor if clear of room doors and stairs
            int corZ = frontRooms ? r.cz() : Math.max(r.z0() + 1, r.z1() - 4);
            int bx = r.x1() - 1;
            if (corZ - 1 >= r.z0() && !c.anyReserved(bx - 1, r.z0(), bx, r.z0() + 1, y) && !frontRooms) {
                int before = c.bedCount();
                c.bed(bx, y, r.z0() + 1, p.bed, Direction.NORTH);
                if (c.bedCount() > before) c.anchor(AnchorType.GUEST_BED, bx, y, r.z0() + 1);
            }
        }
        // a wide runner down the corridor and lamps
        int corZ = frontRooms ? r.cz() : Math.max(r.z0() + 1, r.z1() - 4);
        Props.rug(c, p.carpet, r.x0(), y, corZ, r.x1(), corZ);
        for (int x = r.x0() + 2; x <= r.x1(); x += 4) Props.lantern(c, p, x, r.ceil() - 1, corZ, true);
    }

    /**
     * Spacious private guest rooms (4-5 blocks wide, 3-4 blocks deep) along a long wall of {@code r};
     * each has a locked door, clear walkway, bed against the side wall, bedside table + candle, chest,
     * carpet rug, and writing desk + chair.
     */
    private static int privateRooms(Canvas c, Palette p, Room r, boolean back, int max) {
        int y = r.y();
        boolean deep = r.d() >= 8;
        int zr0 = back ? (deep ? r.z1() - 2 : r.z1() - 1) : (deep ? r.z0() + 2 : r.z0() + 1);
        int zw = back ? zr0 - 1 : zr0 + 1;
        int zFar = back ? r.z1() : r.z0();
        int step = r.w() >= 11 ? 5 : 4;
        int n = 0;
        Direction toHead = back ? Direction.SOUTH : Direction.NORTH;
        for (int xw = r.x0(); xw + step <= r.x1() + 1 && n < max; xw += step) {
            int xEnd = Math.min(r.x1(), xw + step);
            int za = Math.min(zw, zFar), zb = Math.max(zw, zFar);
            if (c.anyReserved(xw, za, xEnd, zb, y)) continue;
            int doorX = xw + 1;
            int zOut = back ? zw - 1 : zw + 1;
            if (zOut >= r.z0() && zOut <= r.z1() && c.isReserved(doorX, y, zOut)) continue;
            for (int z = za; z <= zb; z++) {
                if (xw >= r.x0() && xw <= r.x1()) fillWallColumn(c, xw, y, r.ceil(), z, p.planks);
                if (xEnd <= r.x1()) fillWallColumn(c, xEnd, y, r.ceil(), z, p.planks);
            }
            for (int x = xw + 1; x < xEnd; x++) fillWallColumn(c, x, y, r.ceil(), zw, p.planks);
            c.entrance(doorX, y, zw, p.door, back ? Direction.SOUTH : Direction.NORTH);
            c.anchor(AnchorType.INN_DOOR, doorX, y, zw);
            // Keep both the outer corridor cell and the inner entry foyer cell directly inside the door completely unobstructed
            if (zOut >= r.z0() && zOut <= r.z1()) c.keepClear(doorX, y, y + 1, zOut);
            c.keepClear(doorX, y, y + 1, zr0);
            int footZ = back ? r.z1() - 1 : r.z0() + 1;
            int bedX = xEnd - 1;
            c.bed(bedX, y, footZ, p.bed, toHead);
            c.anchor(AnchorType.INN_BED, bedX, y, footZ);
            // Custom 3D Nightstand beside the bed head
            if (bedX - 1 > doorX) {
                Props.nightstand(c, p, bedX - 1, y, zFar, toHead.getOpposite());
            }
            // Guest's wardrobe & writing desk against the far wall
            if (deep) {
                Props.wardrobe(c, p, bedX, y, zr0, Direction.WEST, null);
                Props.desk(c, p, doorX, y, zFar, toHead.getOpposite());
                if (bedX - 1 > doorX + 1) {
                    Props.chair(c, p, doorX + 1, y, zFar, Direction.EAST);
                }
            }
            if (bedX - 1 >= doorX + 1) {
                Props.rug(c, p.carpet2, doorX + 1, y, Math.min(zr0, footZ), bedX - 1, Math.max(zr0, footZ));
            }
            Props.lantern(c, p, (xw + xEnd) / 2, r.ceil() - 1, footZ, true);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ crafts and trade

    /** The open forge yard of a smithy (local columns 0 .. width-1): complete blacksmith kit + SMITH anchor. */
    public static void forgeYard(Canvas c, Palette p, int width, int d) {
        int y = 1;
        int w = Math.max(3, width);
        c.set(1, y, 1, B.face(Props.forge(), Direction.EAST));
        c.set(1, y, Math.min(d - 2, 3), B.face(Props.grindstone(), Direction.EAST));
        c.set(w - 2, y, Math.max(1, d - 2), B.face(Props.workbench(), Direction.NORTH));
        c.set(1, y, Math.max(2, d - 2), B.face(Props.smelter(), Direction.EAST));
        c.set(w - 2, y, 1, B.s(Blocks.ANVIL));
        c.set(1, y, 5 < d - 2 ? 5 : Math.max(2, d - 3), Props.tanningRack());
        c.set(w - 2, y, Math.min(d - 2, 3), B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        Props.chest(c, w - 1, y, d - 1, Direction.WEST, "smithy");
        c.anchor(AnchorType.SMITH, Math.min(w - 1, 2), y, Math.min(d - 2, 2));
    }

    /**
     * Inside the smithy shop room: unsealed counter with ledger, weapon/armour racks, indoor anvil,
     * workbench, grindstone, tanning rack, quench cauldron, stock barrels, and the smith's bed & strongbox.
     */
    public static void smithyRoom(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        int cz = Math.max(r.z0() + 2, r.z1() - 3);
        // Reserve passage at x1 so the counter is never sealed
        c.keepClear(r.x1(), y, y + 1, cz - 1);
        c.keepClear(r.x1(), y, y + 1, cz);
        c.keepClear(r.x1(), y, y + 1, cz + 1);
        for (int x = r.x0() + 1; x <= r.x1() - 1; x++) {
            Props.counter(c, p, x, y, cz, Direction.NORTH);
            c.set(x, y + 1, cz, B.s(Blocks.AIR));
        }
        Props.clutter(c, p, r.x0() + 1, y + 1, cz,
                com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_SCALES, Direction.NORTH);
        if (r.cx() - 1 > r.x0() + 1) {
            Props.clutter(c, p, r.cx() - 1, y + 1, cz,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_LEDGER, Direction.NORTH);
        }
        c.keepClear(r.cx(), y, y + 1, cz + 1);

        // Indoor blacksmith stations along the left wall (front of counter)
        if (r.z0() + 1 < cz) c.set(r.x0(), y, r.z0() + 1, B.s(Blocks.ANVIL));
        if (r.z0() + 2 < cz) c.set(r.x0(), y, r.z0() + 2, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        if (r.z0() + 3 < cz) c.set(r.x0(), y, r.z0() + 3, B.face(Props.grindstone(), Direction.EAST));
        if (r.z0() + 4 < cz) c.set(r.x0(), y, r.z0() + 4, B.face(Props.workbench(), Direction.EAST));
        if (r.z0() + 5 < cz) c.set(r.x0(), y, r.z0() + 5, Props.tanningRack());

        // Weapon racks and armor stand along the right wall (front of counter passage)
        for (int z = r.z0() + 1; z < cz - 1; z += 2) {
            if (r.x1() == doorX && z == r.z0()) continue;
            if (z == r.z0() + 1) {
                weaponRack(c, p, r.x1(), y, z, Direction.WEST);
            } else {
                Props.armorStand(c, p, r.x1(), y, z, Direction.WEST);
            }
        }

        // Back wall behind the counter: keeper's bed, strongbox chest, and stock barrels
        c.bed(r.x0(), y, r.z1() - 1, p.bed, Direction.SOUTH);
        c.anchor(AnchorType.BED, r.x0(), y, r.z1() - 1);
        Props.chest(c, r.x1() - 1, y, r.z1(), Direction.NORTH, "smithy");
        for (int x = r.x0() + 1; x <= r.x1() - 2; x++) {
            Props.barrel(c, x, y, r.z1(), x == r.x0() + 1 ? "smithy" : null);
        }
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    /**
     * A shop: an unsealed counter across the room with a 1-block walkable passage on the right (`x1`), a clear standing
     * spot directly behind the counter facing the customer area with the shopkeeper's trade anchor on it, stock shelves,
     * strongbox, and full apothecary kit (alchemy lab, brewing stands, cauldron, herb pots, hanging drying herbs)
     * when {@code alchemy} is true.
     */
    public static void shop(Canvas c, Palette p, Room r, int doorX, AnchorType who, String loot, boolean alchemy) {
        int y = r.y();
        int cz = Math.max(r.z0() + 2, r.d() >= 7 ? r.z1() - 3 : r.z1() - 2);
        // Reserve a 1-block passage at x1 so the shopkeeper is never trapped in a sealed pocket (F18)
        c.keepClear(r.x1(), y, y + 1, Math.max(r.z0(), cz - 1));
        c.keepClear(r.x1(), y, y + 1, cz);
        c.keepClear(r.x1(), y, y + 1, Math.min(r.z1(), cz + 1));

        for (int x = r.x0() + 1; x <= r.x1() - 1; x++) {
            Props.counter(c, p, x, y, cz, Direction.NORTH);
            c.set(x, y + 1, cz, B.s(Blocks.AIR));
        }
        // Trapdoor flap on the passage end of the counter
        c.set(r.x1(), y, cz, B.with(B.face(p.trapdoor, Direction.WEST), net.minecraft.world.level.block.TrapDoorBlock.OPEN, true));
        Props.clutter(c, p, r.x0() + 1, y + 1, cz,
                alchemy ? com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_APOTHECARY
                        : com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_SCALES,
                Direction.NORTH);
        if (r.cx() - 1 > r.x0() + 1) {
            Props.clutter(c, p, r.cx() - 1, y + 1, cz,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_LEDGER,
                    Direction.NORTH);
        }

        // Clear floor spot directly behind the counter facing NORTH toward customers, with the trade anchor on it
        int seatZ = Math.min(r.z1(), cz + 1);
        c.keepClear(r.cx(), y, y + 1, seatZ);
        c.anchor(who, r.cx(), y, seatZ);

        // Back wall behind the counter: shelves of wares/vials, brewing stands, cabinets and strongbox
        for (int x = r.x0(); x <= r.x1() - 1; x++) {
            if (x == r.cx() && seatZ == r.z1()) continue;
            if (x % 2 == 0) {
                Props.bookshelf(c, x, y, r.z1(), Direction.NORTH);
                if (alchemy) {
                    c.set(x, y + 1, r.z1(), x % 3 == 0 ? B.s(Blocks.BREWING_STAND) : B.face(B.s(Blocks.CHISELED_BOOKSHELF), Direction.NORTH));
                    if (x % 3 != 0) Props.pot(c, x, y + 2, r.z1(), 7 + x);
                } else {
                    Props.bookshelf(c, x, y + 1, r.z1(), Direction.NORTH);
                }
            } else {
                Props.cabinet(c, p, x, y, r.z1(), Direction.NORTH, loot);
            }
        }
        Props.chest(c, r.x0(), y, seatZ, Direction.EAST, loot);

        if (alchemy) {
            // Apothecary stations: alchemy lab, brewing stands, water cauldron, herb pots, and overhead drying herbs
            c.set(r.x0(), y, r.z0() + 1, B.face(Props.alchemyLab(), Direction.EAST));
            int cauldZ = Math.min(cz - 1, r.z0() + 2);
            c.set(r.x0(), y, cauldZ, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
            if (r.z0() + 3 < cz) {
                Props.table(c, p, r.x0(), y, r.z0() + 3);
                Props.clutter(c, p, r.x0(), y + 1, r.z0() + 3,
                        com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_APOTHECARY, Direction.EAST);
            }
            for (int z = r.z0() + 1; z < cz - 1; z++) {
                if (r.x1() == doorX && z == r.z0()) continue;
                if (z % 2 == 0) {
                    Props.bookshelf(c, r.x1(), y, z, Direction.WEST);
                } else {
                    Props.table(c, p, r.x1(), y, z);
                }
                Props.pot(c, r.x1(), y + 1, z, 13 + z);
            }
            // Overhead hanging drying herbs & glow berries above the counter where ceiling is solid
            for (int x = r.x0() + 1; x <= r.x1() - 1; x += 2) {
                if (x == r.cx()) continue;
                if (!c.recording() && !c.get(x, r.ceil(), cz).isAir()) {
                    BlockState herb = (x + cz) % 4 == 0 ? B.s(Blocks.CAVE_VINES) : B.s(Blocks.HANGING_ROOTS);
                    c.setIfOpen(x, r.ceil() - 1, cz, herb);
                }
            }
        } else {
            // General store shelves, barrels, and customer display tables with clutter
            for (int z = r.z0() + 1; z < cz - 1; z++) {
                if (z == r.z0() && r.x0() == doorX) continue;
                if (z % 2 == 0) {
                    Props.barrel(c, r.x0(), y, z, z == r.z0() + 2 ? loot : null);
                } else {
                    Props.bookshelf(c, r.x0(), y, z, Direction.EAST);
                    c.set(r.x0(), y + 1, z, B.s(Blocks.DECORATED_POT));
                }
                if (r.x1() != doorX) {
                    Props.table(c, p, r.x1(), y, z);
                    Props.clutter(c, p, r.x1(), y + 1, z,
                            z % 2 == 0 ? com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_GOBLETS
                                    : com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_SCALES,
                            Direction.WEST);
                }
            }
        }
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
        Props.rug(c, p.carpet, Math.max(r.x0() + 1, r.cx() - 1), y, r.z0(), Math.min(r.x1() - 1, r.cx() + 1), cz - 1);
    }

    // ------------------------------------------------------------------ temples

    public static void temple(Canvas c, Palette p, Room r, int doorX, String divine, boolean hallOfDead) {
        int y = r.y();
        // the altar dais at the back
        for (int x = r.x0(); x <= r.x1(); x++) {
            for (int z = r.z1() - 2; z <= r.z1(); z++) c.set(x, y - 1, z, p.foundation);
            c.set(x, y, r.z1() - 2, B.slab(p.trimSlab, false));
        }
        c.set(r.cx(), y, r.z1(), Props.shrine(divine));
        if (r.w() >= 7) {
            String[] others = Props.DIVINES;
            int i = Math.floorMod(divine.hashCode(), others.length);
            String a = others[(i + 1) % others.length], b = others[(i + 4) % others.length];
            if (hallOfDead) a = b = "arkay";
            c.set(r.cx() - 3, y, r.z1(), Props.shrine(a));
            c.set(r.cx() + 3, y, r.z1(), Props.shrine(b));
        }
        for (int x = r.x0(); x <= r.x1(); x += 2) {
            Props.clutter(c, p, x, y, r.z1() - 1,
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_CANDELABRA, Direction.NORTH);
        }
        Props.tapestry(c, p, r.cx() - 1, y + 3, r.z1());
        Props.tapestry(c, p, r.cx() + 1, y + 3, r.z1());
        c.anchor(AnchorType.PRIEST, r.cx(), y, r.z1() - 3);
        // 3D carved wooden pews facing the altar (players & worshippers can sit on them!)
        for (int z = r.z0() + 2; z <= r.z1() - 5; z += 2) {
            for (int x = r.x0(); x <= r.x1(); x++) {
                if (Math.abs(x - r.cx()) <= 1) continue;
                Props.bench(c, p, x, y, z, Direction.SOUTH);
            }
        }
        // aisle carpet
        for (int z = r.z0(); z <= r.z1() - 3; z++) c.setIfOpen(r.cx(), y, z, hallOfDead ? B.s(com.nirncraft.worldgen.VanillaBlocks.GRAY_CARPET) : p.carpet);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
        if (hallOfDead) {
            // ossuary shelves with skulls and an urn
            for (int z = r.z0() + 1; z <= r.z1() - 4; z += 2) {
                c.set(r.x0(), y + 1, z, B.s(Blocks.SKELETON_SKULL));
                c.set(r.x1(), y + 1, z, B.s(Blocks.DECORATED_POT));
            }
            Props.chest(c, r.x0(), y, r.z1() - 3, Direction.EAST, "temple");
        } else {
            Props.chest(c, r.x1(), y, r.z1() - 3, Direction.WEST, "temple");
        }
    }

    // ------------------------------------------------------------------ halls of power

    /**
     * A grand seat of power: Great Hall with colonnade, banners, central fire pit, long banquet tables with chairs,
     * raised throne dais with {@link AnchorType#JARL} on the throne and advisor seats + {@link AnchorType#HOUSECARL},
     * Steward's Office with desk + chair ({@link AnchorType#STEWARD}), Court Mage's Study with enchanter + alchemy lab
     * + bookshelves + desk & chair ({@link AnchorType#WIZARD}), kitchen & armoury alcoves, and a partitioned upper floor
     * divided into the Jarl & Family Royal Suite, Advisor/Steward & Mage Quarters, and Servant/Guard Quarters.
     */
    public static void palace(Canvas c, Palette p, Room r, Room upstairs, int doorX, boolean wizard, boolean firePit) {
        int y = r.y();
        // ---- Raised Throne Dais at the back of the Great Hall
        int dz = r.z1() - 3;
        int daisHalf = Math.min(4, Math.max(2, r.w() / 4));
        for (int x = r.cx() - daisHalf; x <= r.cx() + daisHalf; x++) {
            for (int z = dz; z <= r.z1(); z++) c.set(x, y, z, p.floor2.equals(p.floor) ? p.planks : p.floor2);
            c.set(x, y, dz - 1, B.stair(p.trim, Direction.SOUTH, false));
        }
        // Grand 3D cultural throne at (cx, y + 1, z1 - 1) facing NORTH down the Great Hall
        Props.throne(c, p, r.cx(), y + 1, r.z1() - 1, Direction.NORTH);
        c.set(r.cx() - 1, y + 1, r.z1() - 1, p.column);
        c.set(r.cx() + 1, y + 1, r.z1() - 1, p.column);
        c.set(r.cx(), y + 1, r.z1(), p.column);
        c.set(r.cx(), y + 2, r.z1(), p.column);
        // Advisor seats flanking the throne on the dais
        if (daisHalf >= 2) {
            Props.chair(c, p, r.cx() - 2, y + 1, r.z1() - 1, Direction.SOUTH);
            Props.chair(c, p, r.cx() + 2, y + 1, r.z1() - 1, Direction.SOUTH);
        }
        Props.tapestry(c, p, r.cx() - 2, y + 4, r.z1());
        Props.tapestry(c, p, r.cx() + 2, y + 4, r.z1());
        Props.tapestry(c, p, r.cx(), y + 5, r.z1());
        c.anchor(AnchorType.JARL, r.cx(), y + 1, r.z1() - 1);
        c.anchor(AnchorType.HOUSECARL, r.cx() + Math.min(2, daisHalf), y + 1, r.z1() - 2);
        c.anchor(AnchorType.GUARD, r.cx() - Math.min(3, daisHalf), y + 1, r.z1() - 2);
        c.anchor(AnchorType.GUARD, Math.max(r.x0() + 1, doorX - 2), y, r.z0() + 1);
        c.anchor(AnchorType.GUARD, Math.min(r.x1() - 1, doorX + 2), y, r.z0() + 1);

        // ---- Colonnade, central fire pit, and long banquet tables
        int pitLen = Math.max(3, dz - r.z0() - 6);
        if (firePit) Props.firePit(c, p, r.cx(), y, r.z0() + 3, pitLen, true);
        else Props.rug(c, p.carpet, r.cx() - 1, y, r.z0(), r.cx() + 1, dz - 2);
        for (int side = -1; side <= 1; side += 2) {
            int tx = r.cx() + side * (r.w() >= 15 ? 4 : 3);
            if (tx <= r.x0() + 1 || tx >= r.x1() - 1) continue;
            for (int z = r.z0() + 3; z < r.z0() + 3 + pitLen; z++) {
                Props.banquetTable(c, p, tx, y, z, Direction.SOUTH);
                Props.chair(c, p, tx - side, y, z, side < 0 ? Direction.EAST : Direction.WEST);
                Props.chair(c, p, tx + side, y, z, side < 0 ? Direction.WEST : Direction.EAST);
                var feastClutter = switch (Math.floorMod(z, 3)) {
                    case 0 -> com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_PLATTER;
                    case 1 -> com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_GOBLETS;
                    default -> com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_CANDELABRA;
                };
                Props.clutter(c, p, tx, y + 1, z, feastClutter, Direction.SOUTH);
            }
            // Colonnade pillars with banners along the outer aisles
            int colX = tx + side * 2;
            if (colX > r.x0() + 1 && colX < r.x1() - 1) {
                for (int z = r.z0() + 3; z < dz - 1; z += 4) {
                    if (c.isReserved(colX, y, z)) continue;
                    for (int yy = y; yy < r.ceil(); yy++) c.set(colX, yy, z, p.column);
                }
            }
        }

        // ---- Steward's Office alcove (front-left) with 3D desk, chair, bookshelves & strongbox
        int sx = r.x0(), sz = r.z0() + 2;
        Props.desk(c, p, sx, y, sz, Direction.EAST);
        Props.table(c, p, sx, y, sz + 1);
        Props.clutter(c, p, sx, y + 1, sz + 1,
                com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_LEDGER, Direction.EAST);
        Props.chest(c, sx, y, sz + 2, Direction.EAST, "palace");
        if (sz + 3 < dz) {
            Props.bookshelf(c, sx, y, sz + 3, Direction.EAST);
            Props.bookshelf(c, sx, y + 1, sz + 3, Direction.EAST);
        }
        c.keepClear(sx + 1, y, y + 1, sz);
        c.anchor(AnchorType.STEWARD, sx + 1, y, sz);

        // ---- Court Mage's Study alcove (front-right) with enchanter, alchemy lab, bookshelves, desk & standing post
        if (wizard) {
            int wx = r.x1(), wz = r.z0() + 2;
            c.set(wx, y, wz, B.face(Props.enchanter(), Direction.WEST));
            c.set(wx, y, wz + 1, B.face(Props.alchemyLab(), Direction.WEST));
            Props.bookshelf(c, wx, y, wz + 2, Direction.WEST);
            Props.bookshelf(c, wx, y + 1, wz + 2, Direction.WEST);
            if (wz + 3 < dz) {
                Props.table(c, p, wx, y, wz + 3);
                Props.clutter(c, p, wx, y + 1, wz + 3,
                        com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_APOTHECARY, Direction.WEST);
                Props.bookshelf(c, wx, y, wz + 4 < dz ? wz + 4 : wz + 3, Direction.WEST);
            }
            c.keepClear(wx - 1, y, y + 1, wz);
            c.anchor(AnchorType.WIZARD, wx - 1, y, wz);
        }

        // ---- Kitchen & Armoury corners flanking the dais in large keeps
        if (r.w() >= 13) {
            // Back-left kitchen & larder corner
            c.setIfOpen(r.x0(), y, r.z1() - 1, Props.cookingPot());
            c.setIfOpen(r.x0(), y, r.z1() - 2, B.face(B.s(Blocks.SMOKER), Direction.EAST));
            Props.barrel(c, r.x0() + 1, y, r.z1(), "food");
            // Back-right armoury corner
            weaponRack(c, p, r.x1(), y, r.z1() - 1, Direction.WEST);
            Props.armorStand(c, p, r.x1(), y, r.z1() - 2, Direction.WEST);
        }
        for (int z = r.z0() + 1; z < dz - 1; z += 4) {
            Props.brazier(c, p, r.x0(), y, z + 2);
            Props.brazier(c, p, r.x1(), y, z + 2);
        }
        for (int z = r.z0() + 4; z < dz; z += 5) Props.lantern(c, p, r.cx(), r.ceil() - 1, z, true);
        Props.chest(c, r.x0(), y, r.z1(), Direction.EAST, "palace");
        Props.chest(c, r.x1(), y, r.z1(), Direction.WEST, "palace");

        // ---- Partitioned Upper Floor: (a) Jarl & Family Royal Suite, (b) Advisor/Steward & Mage Quarters, (c) Servant/Guard Quarters
        if (upstairs != null) {
            int uy = upstairs.y();
            int uzMid = upstairs.cz();
            if (upstairs.w() >= 9 && upstairs.d() >= 9) {
                // Cross wall separating the rear Royal Suite from the front quarters
                partitionH(c, p, upstairs.x0(), upstairs.x1(), uzMid, uy, upstairs.ceil(), upstairs.cx(), true);
                // Divider wall between Front-Left Servant/Guard Quarters and Front-Right Advisor/Steward/Mage Quarters
                partitionV(c, p, upstairs.cx(), upstairs.z0(), uzMid - 1, uy, upstairs.ceil(), (upstairs.z0() + uzMid - 1) / 2, true);

                // (a) Jarl & Family Royal Suite (back half: z = uzMid + 1 .. upstairs.z1())
                c.bed(upstairs.cx(), uy, upstairs.z1() - 1, p.bed, Direction.SOUTH);
                c.bed(upstairs.cx() + 1, uy, upstairs.z1() - 1, p.bed, Direction.SOUTH);
                c.anchor(AnchorType.BED, upstairs.cx(), uy, upstairs.z1() - 1);
                c.anchor(AnchorType.BED, upstairs.cx() + 1, uy, upstairs.z1() - 1);
                Props.wardrobe(c, p, upstairs.cx() - 1, uy, upstairs.z1(), Direction.NORTH, "palace");
                Props.dresser(c, p, upstairs.cx() + 2 <= upstairs.x1() ? upstairs.cx() + 2 : upstairs.x1(), uy, upstairs.z1(), Direction.NORTH, "palace");
                for (int x = upstairs.x0(); x <= upstairs.x0() + 2 && x < upstairs.cx() - 1; x++) {
                    Props.bookshelf(c, x, uy, upstairs.z1(), Direction.NORTH);
                    Props.bookshelf(c, x, uy + 1, upstairs.z1(), Direction.NORTH);
                }
                int rtx = Math.min(upstairs.x1() - 1, upstairs.cx() + 3);
                int rtz = Math.min(upstairs.z1() - 2, uzMid + 2);
                Props.table(c, p, rtx, uy, rtz);
                Props.chair(c, p, rtx - 1, uy, rtz, Direction.WEST);
                Props.chair(c, p, rtx + 1 <= upstairs.x1() ? rtx + 1 : rtx, uy, rtz, Direction.EAST);
                Props.clutter(c, p, rtx, uy + 1, rtz,
                        com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_GOBLETS, Direction.SOUTH);
                Props.rug(c, p.carpet, Math.max(upstairs.x0() + 1, upstairs.cx() - 3), uy, uzMid + 1,
                        Math.min(upstairs.x1() - 1, upstairs.cx() + 3), upstairs.z1() - 2);
                Props.lantern(c, p, upstairs.cx(), upstairs.ceil() - 1, (uzMid + upstairs.z1()) / 2, true);

                // (b) Advisor / Steward & Court Mage Quarters (front-right: x = cx + 1 .. x1, z = z0 .. uzMid - 1)
                for (int x = upstairs.cx() + 2; x <= upstairs.x1() - 1; x += 2) {
                    int before = c.bedCount();
                    c.bed(x, uy, upstairs.z0() + 1, p.bed, Direction.NORTH);
                    if (c.bedCount() > before) c.anchor(AnchorType.BED, x, uy, upstairs.z0() + 1);
                }
                Props.desk(c, p, upstairs.x1(), uy, Math.max(upstairs.z0() + 2, uzMid - 2), Direction.WEST);
                Props.chair(c, p, upstairs.x1() - 1, uy, Math.max(upstairs.z0() + 2, uzMid - 2), Direction.WEST);
                Props.bookshelf(c, upstairs.x1(), uy, uzMid - 1, Direction.WEST);
                Props.lantern(c, p, (upstairs.cx() + upstairs.x1()) / 2, upstairs.ceil() - 1, (upstairs.z0() + uzMid) / 2, true);

                // (c) Servant & Guard/Housecarl Quarters (front-left: x = x0 .. cx - 1, z = z0 .. uzMid - 1)
                for (int x = upstairs.x0() + 1; x <= upstairs.cx() - 1; x += 2) {
                    int before = c.bedCount();
                    c.bed(x, uy, upstairs.z0() + 1, p.bed, Direction.NORTH);
                    if (c.bedCount() > before) c.anchor(AnchorType.BED, x, uy, upstairs.z0() + 1);
                }
                weaponRack(c, p, upstairs.x0(), uy, Math.max(upstairs.z0() + 2, uzMid - 2), Direction.EAST);
                Props.lantern(c, p, (upstairs.x0() + upstairs.cx()) / 2, upstairs.ceil() - 1, (upstairs.z0() + uzMid) / 2, true);
            } else {
                // Compact upstairs fallback
                c.bed(upstairs.cx(), uy, upstairs.z1() - 1, p.bed, Direction.SOUTH);
                c.bed(upstairs.cx() + 1, uy, upstairs.z1() - 1, p.bed, Direction.SOUTH);
                c.anchor(AnchorType.BED, upstairs.cx(), uy, upstairs.z1() - 1);
                c.anchor(AnchorType.BED, upstairs.cx() + 1, uy, upstairs.z1() - 1);
                Props.wardrobe(c, p, upstairs.cx() - 1, uy, upstairs.z1(), Direction.NORTH, "palace");
                Props.rug(c, p.carpet, upstairs.cx() - 2, uy, upstairs.cz() - 1, upstairs.cx() + 2, upstairs.cz() + 1);
                for (int x = upstairs.x0(); x <= upstairs.x1(); x += 3) {
                    int before = c.bedCount();
                    c.bed(x, uy, upstairs.z0() + 1, p.bed, Direction.NORTH);
                    if (c.bedCount() > before) c.anchor(AnchorType.BED, x, uy, upstairs.z0() + 1);
                }
                Props.lantern(c, p, upstairs.cx(), upstairs.ceil() - 1, upstairs.cz(), true);
            }
        } else {
            // Single-storey palace fallback: ensure 4 court beds at the back corners
            for (int x : new int[]{r.x0() + 1, r.x0() + 3, r.x1() - 3, r.x1() - 1}) {
                if (Math.abs(x - r.cx()) <= daisHalf) continue;
                int before = c.bedCount();
                c.bed(x, y, r.z1() - 1, p.bed, Direction.SOUTH);
                if (c.bedCount() > before) c.anchor(AnchorType.BED, x, y, r.z1() - 1);
            }
        }
    }

    /** A mead hall / chieftain's longhouse with a partitioned rear chieftain chamber and full court stations when {@code seat} is true. */
    public static void longhouse(Canvas c, Palette p, Room r, int doorX, boolean seat) {
        int y = r.y();
        int pz = r.d() >= 13 ? r.z1() - 4 : r.z1() - 3;
        int pitLen = Math.max(3, pz - r.z0() - 5);
        Props.firePit(c, p, r.cx(), y, r.z0() + 3, pitLen, true);
        for (int side = -1; side <= 1; side += 2) {
            int tx = r.cx() + side * 3;
            if (tx <= r.x0() || tx >= r.x1()) continue;
            for (int z = r.z0() + 3; z < pz - 1; z++) {
                Props.banquetTable(c, p, tx, y, z, Direction.SOUTH);
                Props.chair(c, p, tx + side, y, z, side < 0 ? Direction.WEST : Direction.EAST);
                if (z % 2 == 0) {
                    Props.clutter(c, p, tx, y + 1, z,
                            z % 4 == 0 ? com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_PLATTER
                                    : com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_TANKARD,
                            Direction.SOUTH);
                }
            }
        }
        // Rear partition separating the chieftain/jarl's private quarters from the main feast hall
        if (r.d() >= 11) {
            int dLeft = Math.max(r.x0() + 1, r.cx() - 3);
            int dRight = Math.min(r.x1() - 1, r.cx() + 3);
            for (int x = r.x0(); x <= r.x1(); x++) {
                if (c.isReserved(x, y, pz) || c.isReserved(x, y + 1, pz)) {
                    fillWallColumn(c, x, y + 2, r.ceil(), pz, p.planks);
                    continue;
                }
                if (x == dLeft || x == dRight) {
                    fillWallColumn(c, x, y + 2, r.ceil(), pz, p.planks);
                    c.door(x, y, pz, p.door, Direction.SOUTH);
                    continue;
                }
                fillWallColumn(c, x, y, r.ceil(), pz, p.planks);
            }
        }
        if (seat) {
            int tz = r.d() >= 11 ? pz - 1 : r.z1() - 1;
            int backZ = r.d() >= 11 ? pz : r.z1();
            // Grand 3D cultural throne on a central dais in front of the rear partition
            for (int x = r.cx() - 1; x <= r.cx() + 1; x++) c.set(x, y - 1, tz, p.foundation);
            Props.throne(c, p, r.cx(), y, tz, Direction.NORTH);
            c.set(r.cx() - 1, y, backZ, p.column);
            c.set(r.cx() + 1, y, backZ, p.column);
            c.set(r.cx(), y, backZ, p.column);
            c.set(r.cx(), y + 1, backZ, p.column);
            c.anchor(AnchorType.JARL, r.cx(), y, tz);
            c.anchor(AnchorType.HOUSECARL, Math.min(r.x1() - 1, r.cx() + 2), y, tz);

            // Steward's desk (front-left)
            int sx = r.x0(), sz = r.z0() + 2;
            Props.desk(c, p, sx, y, sz, Direction.EAST);
            Props.chest(c, sx, y, sz + 1, Direction.EAST, "house_rich");
            c.keepClear(sx + 1, y, y + 1, sz);
            c.anchor(AnchorType.STEWARD, sx + 1, y, sz);

            // Court Mystic / Wise Woman corner (front-right) with enchanter, alchemy lab, bookshelf & standing post
            int wx = r.x1(), wz = r.z0() + 2;
            c.set(wx, y, wz, B.face(Props.enchanter(), Direction.WEST));
            c.set(wx, y, wz + 1, B.face(Props.alchemyLab(), Direction.WEST));
            Props.bookshelf(c, wx, y, wz + 2, Direction.WEST);
            Props.bookshelf(c, wx, y + 1, wz + 2, Direction.WEST);
            c.keepClear(wx - 1, y, y + 1, wz);
            c.anchor(AnchorType.WIZARD, wx - 1, y, wz);
        }
        // Beds in the rear quarters behind the partition (at least 4 beds when seat == true)
        for (int x = r.x0(); x <= r.x1(); x += 2) {
            if (!seat && Math.abs(x - r.cx()) <= 1 && r.d() < 11) continue;
            int before = c.bedCount();
            c.bed(x, y, r.z1() - 1, p.bed, Direction.SOUTH);
            if (c.bedCount() > before) c.anchor(AnchorType.BED, x, y, r.z1() - 1);
        }
        for (int z = r.z0() + 4; z < pz - 1; z += 4) {
            Props.pelts(c, p, r.x0(), y, z);
            Props.pelts(c, p, r.x1(), y, z);
            Props.barrel(c, r.x0(), y, z + 1, "food");
        }
        Props.tapestry(c, p, r.cx() - 2, y + 3, r.z1());
        Props.tapestry(c, p, r.cx() + 2, y + 3, r.z1());
        Props.wardrobe(c, p, r.x1(), y, r.z1() - 2, Direction.WEST, "house_rich");
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    public static void barracks(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        // Reserve a clean 2-deep entry vestibule at the front door so no bed or furniture ever blocks the entrance
        for (int dx = -1; dx <= 1; dx++) {
            int kx = doorX + dx;
            if (kx >= r.x0() && kx <= r.x1()) {
                c.keepClear(kx, y, y + 1, r.z0());
                if (r.z0() + 1 <= r.z1()) c.keepClear(kx, y, y + 1, r.z0() + 1);
            }
        }
        boolean twoSided = r.w() >= 7;
        int bunkStartZ = Math.min(r.z1() - 1, r.z0() + 2);
        // Front armoury corners flanking the entrance: 3D weapon racks and armor stands
        if (r.x0() < doorX - 1 && !c.isReserved(r.x0(), y, r.z0())) {
            weaponRack(c, p, r.x0(), y, r.z0(), Direction.EAST);
        }
        if (r.x1() > doorX + 1 && !c.isReserved(r.x1(), y, r.z0())) {
            Props.armorStand(c, p, r.x1(), y, r.z0(), Direction.WEST);
        }

        // Neat rows of full 2-block bunks along the walls with footlockers between them
        for (int z = bunkStartZ; z <= r.z1() - 1; z += 2) {
            int beforeL = c.bedCount();
            c.bed(r.x0() + 1, y, z, p.bed, Direction.WEST);
            if (c.bedCount() > beforeL) {
                c.anchor(AnchorType.GUARD, r.x0() + 1, y, z);
                if (z + 1 <= r.z1() - 1 && !c.isReserved(r.x0(), y, z + 1)) {
                    Props.chest(c, r.x0(), y, z + 1, Direction.EAST, "barracks");
                }
            }
            if (twoSided) {
                int beforeR = c.bedCount();
                c.bed(r.x1() - 1, y, z, p.bed, Direction.EAST);
                if (c.bedCount() > beforeR) {
                    c.anchor(AnchorType.GUARD, r.x1() - 1, y, z);
                    if (z + 1 <= r.z1() - 1 && !c.isReserved(r.x1(), y, z + 1)) {
                        Props.chest(c, r.x1(), y, z + 1, Direction.WEST, "barracks");
                    }
                }
            }
        }
        if (!twoSided) {
            // Narrow barracks: keep a clear central aisle and put 3D mess table, chairs, weapon rack & wardrobe along the right wall
            int tx = r.x1();
            int tz = r.cz();
            if (!c.isReserved(tx, y, tz)) {
                Props.table(c, p, tx, y, tz);
                Props.clutter(c, p, tx, y + 1, tz,
                        com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_TANKARD, Direction.WEST);
                if (tz - 1 >= r.z0() + 1 && !c.isReserved(tx, y, tz - 1)) Props.chair(c, p, tx, y, tz - 1, Direction.NORTH);
                if (tz + 1 <= r.z1() - 1 && !c.isReserved(tx, y, tz + 1)) Props.chair(c, p, tx, y, tz + 1, Direction.SOUTH);
            }
            if (r.z0() + 1 < tz - 1 && !c.isReserved(r.x1(), y, r.z0() + 1)) {
                weaponRack(c, p, r.x1(), y, r.z0() + 1, Direction.WEST);
            }
            if (!c.isReserved(r.x1(), y, r.z1())) {
                Props.wardrobe(c, p, r.x1(), y, r.z1(), Direction.WEST, "barracks");
            }
        } else if (r.w() >= 9) {
            // Wide barracks: central mess table with chairs on the north/south ends and commander's desk at the back
            Props.table(c, p, r.cx(), y, r.cz());
            Props.clutter(c, p, r.cx(), y + 1, r.cz(),
                    com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_PLATTER, Direction.SOUTH);
            if (r.cz() - 1 >= r.z0() + 1) Props.chair(c, p, r.cx(), y, r.cz() - 1, Direction.NORTH);
            if (r.cz() + 1 <= r.z1() - 1) Props.chair(c, p, r.cx(), y, r.cz() + 1, Direction.SOUTH);
            if (!c.isReserved(r.cx(), y, r.z1())) {
                Props.desk(c, p, r.cx(), y, r.z1(), Direction.NORTH);
            }
        }
        if (!c.isReserved(r.x0(), y, r.z1())) {
            weaponRack(c, p, r.x0(), y, r.z1(), Direction.EAST);
        }
        Props.rug(c, p.carpet2, r.cx(), y, Math.max(r.z0() + 1, r.z0() + 2), r.cx(), Math.max(r.z0() + 2, r.z1() - 2));
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    public static void mill(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        c.set(r.cx(), y, r.cz(), B.s(Blocks.STONECUTTER));
        c.set(r.cx() + 1, y, r.cz(), B.s(Blocks.GRINDSTONE));
        for (int z = r.z0(); z <= r.z1(); z += 2) {
            c.set(r.x1(), y, z, B.s(Blocks.HAY_BLOCK));
            c.set(r.x1(), y + 1, z, B.s(Blocks.HAY_BLOCK));
        }
        c.bed(r.x0(), y, r.z1() - 1, p.bed, Direction.SOUTH);
        c.anchor(AnchorType.BED, r.x0(), y, r.z1() - 1);
        Props.chest(c, r.x0(), y, r.z1() - 2, Direction.NORTH, "mill");
        c.anchor(AnchorType.MILLER, r.cx(), y, r.cz() - 1);
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    public static void guardPost(Canvas c, Palette p, Room r) {
        int y = r.y();
        Props.table(c, p, r.cx(), y, r.cz());
        Props.clutter(c, p, r.cx(), y + 1, r.cz(),
                com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_TANKARD, Direction.SOUTH);
        Props.chair(c, p, r.cx(), y, Math.max(r.z0(), r.cz() - 1), Direction.NORTH);
        weaponRack(c, p, r.x0(), y, r.z1(), Direction.EAST);
        Props.chest(c, r.x1(), y, r.z1(), Direction.WEST, "barracks");
        c.anchor(AnchorType.GUARD, r.cx(), y, r.cz() + 1);
    }

    public static void tent(Canvas c, Palette p, Room r) {
        int y = r.y();
        c.bed(r.x0(), y, r.z1() - 1, p.bed, Direction.SOUTH);
        c.anchor(AnchorType.BED, r.x0(), y, r.z1() - 1);
        Props.chest(c, r.x1(), y, r.z1(), Direction.WEST, "food");
        Props.rug(c, p.carpet, r.x0(), y, r.z0(), r.x1(), r.z1() - 2);
        Props.lantern(c, p, r.x1(), y, r.z0() + 1, false);
    }
}
