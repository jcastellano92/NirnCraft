package com.skycraft.tamriel.settlement.build;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.build.Shells.Room;
import com.skycraft.tamriel.settlement.kit.Palette;
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
                c.setIfOpen(r.x1(), y, r.z0() + 1, B.s(Blocks.BOOKSHELF));
                c.setIfOpen(r.x1(), y + 1, r.z0() + 1, B.s(Blocks.BOOKSHELF));
                Props.pot(c, r.x1(), y + 2, r.z0() + 1, 11);
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
                Props.table(c, p, deskX, y, r.z0() + 2);
                c.setIfOpen(deskX, y + 1, r.z0() + 2, B.s(Blocks.LECTERN));
                Props.chair(c, p, deskX, y, r.z0() + 3, Direction.SOUTH);
            } else {
                c.setIfOpen(sideRight ? sx1 : sx0, y, r.z0() + 2, Props.cookingPot());
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
                for (int z = r.z0() + 1; z <= r.z1() - 2; z += 2) {
                    int bx = sideRight ? hx0 : hx1;
                    c.setIfOpen(bx, y, z, B.s(Blocks.BOOKSHELF));
                    c.setIfOpen(bx, y + 1, z, B.s(Blocks.BOOKSHELF));
                }
            }
            Props.rug(c, rich ? p.carpet : p.carpet2, Math.max(hx0, hcx - 1), y, r.z0() + 1,
                    Math.min(hx1, hcx + 1), Math.max(r.z0() + 1, r.z1() - 2));
        } else {
            // Family 2: Central hearth hall + twin rear alcoves (kitchen nook & sleeping alcove)
            if (r.d() >= 7 && r.w() >= 6) {
                int pz = r.z1() - 3;
                int dLeft = Math.max(r.x0() + 1, r.cx() - 2);
                int dRight = Math.min(r.x1() - 1, r.cx() + 2);
                for (int x = r.x0(); x <= r.x1(); x++) {
                    if (x == dLeft || x == dRight) {
                        if (!c.isReserved(x, y, pz)) c.door(x, y, pz, p.door, Direction.SOUTH);
                        continue;
                    }
                    if (!c.isReserved(x, y, pz)) fillWallColumn(c, x, y, r.ceil(), pz, rich ? p.wall : p.planks);
                }
                for (int z = pz + 1; z <= r.z1(); z++) {
                    if (!c.isReserved(r.cx(), y, z)) fillWallColumn(c, r.cx(), y, r.ceil(), z, rich ? p.wall : p.planks);
                }
                // Left rear alcove: kitchen & pantry
                c.setIfOpen(r.x0(), y, r.z1(), Props.cookingPot());
                if (r.x0() + 1 < r.cx()) c.setIfOpen(r.x0() + 1, y, r.z1(), B.face(B.s(Blocks.SMOKER), Direction.NORTH));
                c.setIfOpen(r.x0(), y, pz + 1, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
                // Right rear alcove: bedroom
                placeBedsInRange(c, p, r.cx() + 1, r.x1(), r.z1() - 1, y, Math.max(1, beds), bed, chestLoot, -99);
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
            }

            // Front dining table & chairs
            int tx = doorX <= r.cx() ? Math.min(r.x1() - 1, r.cx() + 2) : Math.max(r.x0() + 1, r.cx() - 2);
            int tz = Math.min(r.z1() - 2, r.z0() + 1);
            diningSet(c, p, tx, y, tz, r, rich);

            if (rich && r.w() >= 5) {
                c.setIfOpen(r.x0(), y, r.z0() + 2, B.s(Blocks.BOOKSHELF));
                c.setIfOpen(r.x0(), y + 1, r.z0() + 2, B.s(Blocks.BOOKSHELF));
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
        c.setIfOpen(tx, y + 1, tz, rich ? Props.cheese() : B.s(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
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
            if (c.isReserved(x, y, z) || c.isReserved(x, y + 1, z) || c.isReserved(x, y, z - 1) || c.isReserved(x, y, z + 1)) continue;
            if (x == doorX) {
                c.door(x, y, z, p.door, Direction.SOUTH);
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
            if (c.isReserved(x, y, z) || c.isReserved(x, y + 1, z) || c.isReserved(x - 1, y, z) || c.isReserved(x + 1, y, z)) continue;
            if (z == doorZ) {
                c.door(x, y, z, p.door, Direction.EAST);
                continue;
            }
            fillWallColumn(c, x, y, ceil, z, wall);
        }
    }

    /** A weapon/armour rack against a wall. */
    public static void weaponRack(Canvas c, Palette p, int x, int y, int z, Direction face) {
        if (c.isReserved(x, y, z)) return;
        c.set(x, y, z, p.fence);
        c.set(x, y + 1, z, B.face(p.trapdoor, face));
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
                c.set(x, y, cz, p.planks);
                c.set(x, y + 1, cz, i % 2 == 0 ? B.slab(p.slab, false) : B.s(Blocks.AIR));
            }
            c.set(xe, y, cz + 1, p.planks); // the counter turns the corner; the open cells behind it are the flap
            c.set(xa + s, y + 1, cz, B.s(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
            c.set(xa + s * 2, y + 1, cz, B.face(B.s(Blocks.LECTERN), Direction.NORTH));
            c.set(xa + s * 3, y + 1, cz, Props.cheese());
            for (int i = 0; i < 4; i++) {
                int x = xa + s * i;
                // one kept keg carries the inn's stock; the rest are plain empty barrels (not counted as containers)
                if (i == 1) Props.barrel(c, x, y, hall.z1(), "inn");
                else c.set(x, y, hall.z1(), B.face(B.s(Blocks.BARREL), Direction.UP));
                if (i % 2 == 0) c.set(x, y + 1, hall.z1(), B.face(B.s(Blocks.BARREL), Direction.UP));
            }
            int ix = xa + s * 2;
            Props.chair(c, p, ix, y, cz + 1, Direction.SOUTH);
            c.anchor(AnchorType.INN, ix, y, cz + 1);
            Props.lantern(c, p, ix, hall.ceil() - 1, cz + 1, true);
        } else {
            // a small hall: unsealed counter run with a chair behind it and open passage at x1
            c.keepClear(hall.x1(), y, y + 1, hall.z1() - 1);
            c.keepClear(hall.x1(), y, y + 1, hall.z1() - 2);
            for (int x = hall.x0() + 1; x <= hall.x1() - 1; x++) c.set(x, y, hall.z1() - 2, p.planks);
            Props.chair(c, p, hall.cx(), y, hall.z1() - 1, Direction.SOUTH);
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
                Props.chair(c, p, tx, y, z + 1, Direction.SOUTH);
                if (c.chance(tx, y, z, 3, 2)) Props.candles(c, tx, y + 1, z, 2);
                else c.set(tx, y + 1, z, Props.cheese());
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
                    Props.candles(c, tx, y + 1, tz, 2);
                }
            }
        }
        // Bard's corner stage at front right
        Props.rug(c, p.carpet, hall.x1() - 1, y, hall.z0(), hall.x1(), hall.z0() + 1);
        Props.chair(c, p, hall.x1(), y, hall.z0(), Direction.NORTH);
        c.anchor(AnchorType.BARD, hall.x1() - 1, y, hall.z0() + 1);
        Props.lantern(c, p, hall.cx(), hall.ceil() - 1, hall.cz(), true);

        if (upstairs != null) {
            // Kitchen & staff alcove in the back corner opposite the bar
            int kx0 = s == 1 ? Math.max(xe + 2, hall.x1() - 4) : hall.x0();
            int kx1 = s == 1 ? hall.x1() : Math.min(xe - 2, hall.x0() + 4);
            if (kx1 - kx0 >= 3 && !c.anyReserved(kx0, cz, kx1, hall.z1(), y)) {
                int kWallX = s == 1 ? kx0 : kx1;
                int kDoorX = (kx0 + kx1) / 2;
                for (int z = cz; z <= hall.z1(); z++) {
                    fillWallColumn(c, kWallX, y, hall.ceil(), z, p.planks);
                }
                for (int x = kx0; x <= kx1; x++) {
                    if (x == kDoorX) {
                        c.door(x, y, cz, p.door, Direction.NORTH);
                        continue;
                    }
                    fillWallColumn(c, x, y, hall.ceil(), cz, p.planks);
                }
                int innerK = s == 1 ? kx1 : kx0;
                c.set(innerK, y, hall.z1(), Props.cookingPot());
                c.set(innerK, y, hall.z1() - 1, B.face(B.s(Blocks.SMOKER), s == 1 ? Direction.WEST : Direction.EAST));
                c.set(innerK, y, cz + 1, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
                Props.barrel(c, kDoorX, y, hall.z1(), "food");
                // Innkeeper's ground-floor staff bed so the inn always owns a resident BED
                int bedX = s == 1 ? kx0 + 1 : kx1 - 1;
                if (bedX != kDoorX) {
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
                    Props.table(c, p, dx, y, hall.z1());
                    Props.candles(c, dx, y + 1, hall.z1(), 1);
                    Props.chair(c, p, dx, y, hall.z1() - 1, Direction.NORTH);
                    Props.chest(c, bx, y, pz + 1, Direction.WEST, null);
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
            rooms += privateRooms(c, p, r, true, 6);
            if (rooms < 4 && r.d() >= 9) {
                int more = privateRooms(c, p, r, false, Math.min(6 - rooms, 3));
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
            // Place 2 traveller dormitory beds (GUEST_BED) and 2 inn staff beds (BED) in remaining corridor/side space
            int midZ = r.cz();
            int bx = r.x1() - 1;
            int guestPlaced = 0;
            for (int z = frontRooms ? r.z0() + 4 : r.z0(); z + 1 <= r.z1() - 4 && guestPlaced < 2; z += 2) {
                if (c.anyReserved(bx - 1, z, bx, z + 1, y)) continue;
                int before = c.bedCount();
                c.bed(bx, y, z, p.bed, Direction.EAST);
                if (c.bedCount() > before) {
                    c.anchor(AnchorType.GUEST_BED, bx, y, z);
                    guestPlaced++;
                }
            }
            // Inn staff beds (for innkeeper / cook / server)
            int sx = r.x0();
            if (!c.anyReserved(sx, midZ - 1, sx + 1, midZ, y)) {
                int before = c.bedCount();
                c.bed(sx, y, midZ, p.bed, Direction.WEST);
                if (c.bedCount() > before) c.anchor(AnchorType.BED, sx, y, midZ);
            }
        }
        // a runner down the corridor and lamps
        int corZ = frontRooms ? r.cz() : Math.max(r.z0() + 1, r.z1() - 4);
        Props.rug(c, p.carpet, r.x0(), y, corZ, r.x1(), corZ);
        for (int x = r.x0() + 2; x <= r.x1(); x += 4) Props.lantern(c, p, x, r.ceil() - 1, corZ, true);
    }

    /** Private rooms along one long wall of {@code r}; each has a locked door, bed, chest, lantern, and writing desk + chair. */
    private static int privateRooms(Canvas c, Palette p, Room r, boolean back, int max) {
        int y = r.y();
        int zr0 = back ? r.z1() - 2 : r.z0() + 2;       // the room's row just inside the door
        int zw = back ? zr0 - 1 : zr0 + 1;               // the partition row with the doors
        int zFar = back ? r.z1() : r.z0();               // against the outer wall
        int n = 0;
        Direction toHead = back ? Direction.SOUTH : Direction.NORTH;
        for (int xw = r.x0(); xw + 3 <= r.x1() + 1 && n < max; xw += 3) {
            int za = Math.min(zw, zFar), zb = Math.max(zw, zFar);
            if (c.anyReserved(xw, za, Math.min(r.x1(), xw + 3), zb, y)) continue;
            // keep the cell outside the private room door clear so a opposite room never blocks it
            int zOut = back ? zw - 1 : zw + 1;
            if (zOut >= r.z0() && zOut <= r.z1() && c.isReserved(xw + 1, y, zOut)) continue;
            for (int z = za; z <= zb; z++) {
                if (xw >= r.x0() && xw <= r.x1()) fillWallColumn(c, xw, y, r.ceil(), z, p.planks);
                if (xw + 3 <= r.x1()) fillWallColumn(c, xw + 3, y, r.ceil(), z, p.planks);
            }
            for (int x = xw + 1; x <= xw + 2; x++) fillWallColumn(c, x, y, r.ceil(), zw, p.planks);
            c.entrance(xw + 1, y, zw, p.door, back ? Direction.SOUTH : Direction.NORTH);
            c.anchor(AnchorType.INN_DOOR, xw + 1, y, zw);
            int footZ = back ? r.z1() - 1 : r.z0() + 1;
            c.bed(xw + 2, y, footZ, p.bed, toHead);
            c.anchor(AnchorType.INN_BED, xw + 2, y, footZ);
            // the guest's own chest at the foot/side of the bed
            c.set(xw + 2, y, zr0, B.face(B.s(Blocks.CHEST), Direction.WEST));
            // writing desk (table + candle + chair) against the outer wall
            Props.table(c, p, xw + 1, y, zFar);
            Props.candles(c, xw + 1, y + 1, zFar, 1);
            Props.chair(c, p, xw + 1, y, footZ, back ? Direction.NORTH : Direction.SOUTH);
            Props.lantern(c, p, xw + 1, r.ceil() - 1, footZ, true);
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
     * Inside the smithy shop room: unsealed counter with sitting chair and ledger, weapon/armour racks, indoor anvil,
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
            c.set(x, y, cz, p.planks);
            c.set(x, y + 1, cz, B.s(Blocks.AIR));
        }
        c.set(r.x0() + 1, y + 1, cz, B.s(Blocks.LANTERN));
        if (r.cx() - 1 > r.x0() + 1) c.set(r.cx() - 1, y + 1, cz, B.face(B.s(Blocks.LECTERN), Direction.NORTH));
        Props.chair(c, p, r.cx(), y, cz + 1, Direction.SOUTH);

        // Indoor blacksmith stations along the left wall (front of counter)
        if (r.z0() + 1 < cz) c.set(r.x0(), y, r.z0() + 1, B.s(Blocks.ANVIL));
        if (r.z0() + 2 < cz) c.set(r.x0(), y, r.z0() + 2, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        if (r.z0() + 3 < cz) c.set(r.x0(), y, r.z0() + 3, B.face(Props.grindstone(), Direction.EAST));
        if (r.z0() + 4 < cz) c.set(r.x0(), y, r.z0() + 4, B.face(Props.workbench(), Direction.EAST));
        if (r.z0() + 5 < cz) c.set(r.x0(), y, r.z0() + 5, Props.tanningRack());

        // Weapon racks along the right wall (front of counter passage)
        for (int z = r.z0() + 1; z < cz - 1; z += 2) {
            if (r.x1() == doorX && z == r.z0()) continue;
            weaponRack(c, p, r.x1(), y, z, Direction.WEST);
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
     * A shop: an unsealed counter across the room with a 1-block walkable passage on the right (`x1`), a stair chair
     * directly behind the counter facing the customer area with the shopkeeper's trade anchor on it, stock shelves,
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

        BlockState counterBlock = alchemy ? B.s(Blocks.SPRUCE_PLANKS) : p.planks;
        for (int x = r.x0() + 1; x <= r.x1() - 1; x++) {
            c.set(x, y, cz, counterBlock);
            c.set(x, y + 1, cz, B.s(Blocks.AIR));
        }
        // Trapdoor flap on the passage end of the counter
        c.set(r.x1(), y, cz, B.with(B.face(p.trapdoor, Direction.WEST), net.minecraft.world.level.block.TrapDoorBlock.OPEN, true));
        c.set(r.x0() + 1, y + 1, cz, B.s(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
        if (r.cx() - 1 > r.x0() + 1) {
            c.set(r.cx() - 1, y + 1, cz, alchemy ? B.s(Blocks.BREWING_STAND) : B.face(B.s(Blocks.LECTERN), Direction.NORTH));
        }

        // Sitting chair directly behind the counter facing NORTH toward customers, with the trade anchor on it
        int seatZ = Math.min(r.z1(), cz + 1);
        Props.chair(c, p, r.cx(), y, seatZ, Direction.SOUTH);
        c.anchor(who, r.cx(), y, seatZ);

        // Back wall behind the counter: shelves of wares/vials, brewing stands, barrels and strongbox
        for (int x = r.x0(); x <= r.x1() - 1; x++) {
            if (x == r.cx() && seatZ == r.z1()) continue;
            BlockState back = alchemy
                    ? (x % 2 == 0 ? B.s(Blocks.CHISELED_BOOKSHELF) : B.s(Blocks.BOOKSHELF))
                    : (x % 2 == 0 ? B.s(Blocks.BOOKSHELF) : null);
            if (back != null) {
                c.set(x, y, r.z1(), back);
                if (alchemy) {
                    c.set(x, y + 1, r.z1(), x % 3 == 0 ? B.s(Blocks.BREWING_STAND) : B.s(Blocks.BOOKSHELF));
                    if (x % 3 != 0) Props.pot(c, x, y + 2, r.z1(), 7 + x);
                } else {
                    c.set(x, y + 1, r.z1(), B.s(Blocks.BOOKSHELF));
                }
            } else {
                Props.barrel(c, x, y, r.z1(), loot);
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
                c.set(r.x0(), y + 1, r.z0() + 3, B.s(Blocks.BREWING_STAND));
            }
            for (int z = r.z0() + 1; z < cz - 1; z++) {
                if (r.x1() == doorX && z == r.z0()) continue;
                c.set(r.x1(), y, z, z % 2 == 0 ? B.s(Blocks.BOOKSHELF) : B.slab(p.slab, true));
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
            // General store shelves, barrels, and customer display table
            for (int z = r.z0() + 1; z < cz - 1; z++) {
                if (z == r.z0() && r.x0() == doorX) continue;
                if (z % 2 == 0) {
                    Props.barrel(c, r.x0(), y, z, z == r.z0() + 2 ? loot : null);
                } else {
                    c.set(r.x0(), y, z, B.s(Blocks.BOOKSHELF));
                    c.set(r.x0(), y + 1, z, B.s(Blocks.DECORATED_POT));
                }
                if (r.x1() != doorX) {
                    c.set(r.x1(), y, z, B.slab(p.slab, true));
                    c.set(r.x1(), y + 1, z, z % 2 == 0 ? Props.cheese() : B.s(Blocks.LANTERN));
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
        for (int x = r.x0(); x <= r.x1(); x += 2) Props.candles(c, x, y, r.z1() - 1, 1 + Math.floorMod(x, 4));
        Props.tapestry(c, p, r.cx() - 1, y + 3, r.z1());
        Props.tapestry(c, p, r.cx() + 1, y + 3, r.z1());
        c.anchor(AnchorType.PRIEST, r.cx(), y, r.z1() - 3);
        // pews facing the altar
        for (int z = r.z0() + 2; z <= r.z1() - 5; z += 2) {
            for (int x = r.x0(); x <= r.x1(); x++) {
                if (Math.abs(x - r.cx()) <= 1) continue;
                c.set(x, y, z, B.stair(p.stairs, Direction.NORTH, false));
            }
        }
        // aisle carpet
        for (int z = r.z0(); z <= r.z1() - 3; z++) c.setIfOpen(r.cx(), y, z, hallOfDead ? B.s(Blocks.GRAY_CARPET) : p.carpet);
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
        // Grand throne chair at (cx, y + 1, z1 - 1) facing NORTH down the Great Hall
        c.set(r.cx(), y + 1, r.z1() - 1, B.stair(p.stairs, Direction.SOUTH, false));
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
                Props.table(c, p, tx, y, z);
                Props.chair(c, p, tx - side, y, z, side < 0 ? Direction.EAST : Direction.WEST);
                Props.chair(c, p, tx + side, y, z, side < 0 ? Direction.WEST : Direction.EAST);
                if (z % 3 == 0) c.set(tx, y + 1, z, Props.cheese());
                else if (z % 3 == 1) Props.candles(c, tx, y + 1, z, 3);
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

        // ---- Steward's Office alcove (front-left) with desk, chair, lectern, bookshelves & strongbox
        int sx = r.x0(), sz = r.z0() + 2;
        Props.table(c, p, sx, y, sz);
        c.set(sx, y + 1, sz, B.face(B.s(Blocks.LECTERN), Direction.EAST));
        Props.table(c, p, sx, y, sz + 1);
        Props.candles(c, sx, y + 1, sz + 1, 3);
        Props.chest(c, sx, y, sz + 2, Direction.EAST, "palace");
        if (sz + 3 < dz) {
            c.set(sx, y, sz + 3, B.s(Blocks.BOOKSHELF));
            c.set(sx, y + 1, sz + 3, B.s(Blocks.BOOKSHELF));
        }
        Props.chair(c, p, sx + 1, y, sz, Direction.EAST);
        c.anchor(AnchorType.STEWARD, sx + 1, y, sz);

        // ---- Court Mage's Study alcove (front-right) with enchanter, alchemy lab, bookshelves, desk & chair
        if (wizard) {
            int wx = r.x1(), wz = r.z0() + 2;
            c.set(wx, y, wz, B.face(Props.enchanter(), Direction.WEST));
            c.set(wx, y, wz + 1, B.face(Props.alchemyLab(), Direction.WEST));
            c.set(wx, y, wz + 2, B.s(Blocks.BOOKSHELF));
            c.set(wx, y + 1, wz + 2, B.s(Blocks.BOOKSHELF));
            if (wz + 3 < dz) {
                Props.table(c, p, wx, y, wz + 3);
                c.set(wx, y + 1, wz + 3, B.s(Blocks.BREWING_STAND));
                c.set(wx, y, wz + 4 < dz ? wz + 4 : wz + 3, B.s(Blocks.CHISELED_BOOKSHELF));
            }
            Props.chair(c, p, wx - 1, y, wz, Direction.WEST);
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
            c.setIfOpen(r.x1(), y, r.z1() - 2, B.face(Props.workbench(), Direction.WEST));
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
                Props.chest(c, upstairs.cx() - 1, uy, upstairs.z1(), Direction.NORTH, "palace");
                Props.chest(c, upstairs.cx() + 2 <= upstairs.x1() ? upstairs.cx() + 2 : upstairs.x1(), uy, upstairs.z1(), Direction.NORTH, "palace");
                for (int x = upstairs.x0(); x <= upstairs.x0() + 2 && x < upstairs.cx() - 1; x++) {
                    c.setIfOpen(x, uy, upstairs.z1(), B.s(Blocks.BOOKSHELF));
                    c.setIfOpen(x, uy + 1, upstairs.z1(), B.s(Blocks.BOOKSHELF));
                }
                int rtx = Math.min(upstairs.x1() - 1, upstairs.cx() + 3);
                int rtz = Math.min(upstairs.z1() - 2, uzMid + 2);
                Props.table(c, p, rtx, uy, rtz);
                Props.chair(c, p, rtx - 1, uy, rtz, Direction.WEST);
                Props.chair(c, p, rtx + 1 <= upstairs.x1() ? rtx + 1 : rtx, uy, rtz, Direction.EAST);
                Props.candles(c, rtx, uy + 1, rtz, 3);
                Props.rug(c, p.carpet, Math.max(upstairs.x0() + 1, upstairs.cx() - 3), uy, uzMid + 1,
                        Math.min(upstairs.x1() - 1, upstairs.cx() + 3), upstairs.z1() - 2);
                Props.lantern(c, p, upstairs.cx(), upstairs.ceil() - 1, (uzMid + upstairs.z1()) / 2, true);

                // (b) Advisor / Steward & Court Mage Quarters (front-right: x = cx + 1 .. x1, z = z0 .. uzMid - 1)
                for (int x = upstairs.cx() + 2; x <= upstairs.x1() - 1; x += 2) {
                    int before = c.bedCount();
                    c.bed(x, uy, upstairs.z0() + 1, p.bed, Direction.NORTH);
                    if (c.bedCount() > before) c.anchor(AnchorType.BED, x, uy, upstairs.z0() + 1);
                }
                Props.table(c, p, upstairs.x1(), uy, Math.max(upstairs.z0() + 2, uzMid - 2));
                c.setIfOpen(upstairs.x1(), uy + 1, Math.max(upstairs.z0() + 2, uzMid - 2), B.s(Blocks.LECTERN));
                Props.chair(c, p, upstairs.x1() - 1, uy, Math.max(upstairs.z0() + 2, uzMid - 2), Direction.WEST);
                c.setIfOpen(upstairs.x1(), uy, uzMid - 1, B.s(Blocks.BOOKSHELF));
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
                Props.chest(c, upstairs.cx() - 1, uy, upstairs.z1(), Direction.NORTH, "palace");
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
                Props.table(c, p, tx, y, z);
                Props.chair(c, p, tx + side, y, z, side < 0 ? Direction.WEST : Direction.EAST);
                if (z % 2 == 0) c.set(tx, y + 1, z, z % 4 == 0 ? Props.cheese() : B.s(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
            }
        }
        // Rear partition separating the chieftain/jarl's private quarters from the main feast hall
        if (r.d() >= 11) {
            int dLeft = Math.max(r.x0() + 1, r.cx() - 3);
            int dRight = Math.min(r.x1() - 1, r.cx() + 3);
            for (int x = r.x0(); x <= r.x1(); x++) {
                if (c.isReserved(x, y, pz) || c.isReserved(x, y + 1, pz)) continue;
                if (x == dLeft || x == dRight) {
                    c.door(x, y, pz, p.door, Direction.SOUTH);
                    continue;
                }
                fillWallColumn(c, x, y, r.ceil(), pz, p.planks);
            }
        }
        if (seat) {
            int tz = r.d() >= 11 ? pz - 1 : r.z1() - 1;
            int backZ = r.d() >= 11 ? pz : r.z1();
            // Throne chair on a central dais in front of the rear partition
            for (int x = r.cx() - 1; x <= r.cx() + 1; x++) c.set(x, y - 1, tz, p.foundation);
            c.set(r.cx(), y, tz, B.stair(p.stairs, Direction.SOUTH, false));
            c.set(r.cx() - 1, y, backZ, p.column);
            c.set(r.cx() + 1, y, backZ, p.column);
            c.set(r.cx(), y, backZ, p.column);
            c.set(r.cx(), y + 1, backZ, p.column);
            c.anchor(AnchorType.JARL, r.cx(), y, tz);
            c.anchor(AnchorType.HOUSECARL, Math.min(r.x1() - 1, r.cx() + 2), y, tz);

            // Steward's desk & chair (front-left)
            int sx = r.x0(), sz = r.z0() + 2;
            Props.table(c, p, sx, y, sz);
            c.set(sx, y + 1, sz, B.face(B.s(Blocks.LECTERN), Direction.EAST));
            Props.chest(c, sx, y, sz + 1, Direction.EAST, "house_rich");
            Props.chair(c, p, sx + 1, y, sz, Direction.EAST);
            c.anchor(AnchorType.STEWARD, sx + 1, y, sz);

            // Court Mystic / Wise Woman corner (front-right) with enchanter, alchemy lab, bookshelf & chair
            int wx = r.x1(), wz = r.z0() + 2;
            c.set(wx, y, wz, B.face(Props.enchanter(), Direction.WEST));
            c.set(wx, y, wz + 1, B.face(Props.alchemyLab(), Direction.WEST));
            c.set(wx, y, wz + 2, B.s(Blocks.BOOKSHELF));
            c.set(wx, y + 1, wz + 2, B.s(Blocks.BOOKSHELF));
            Props.chair(c, p, wx - 1, y, wz, Direction.WEST);
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
        Props.chest(c, r.x1(), y, r.z1() - 2, Direction.WEST, "house_rich");
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    public static void barracks(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        for (int z = r.z0() + 1; z <= r.z1() - 1; z += 2) {
            c.bed(r.x0() + 1, y, z, p.bed, Direction.WEST);
            c.anchor(AnchorType.GUARD, r.x0() + 1, y, z);
            if (r.w() >= 5) {
                c.bed(r.x1() - 1, y, z, p.bed, Direction.EAST);
                c.anchor(AnchorType.GUARD, r.x1() - 1, y, z);
            }
            Props.chest(c, r.x0() + 2, y, z, Direction.EAST, "barracks");
        }
        Props.table(c, p, r.cx(), y, r.cz());
        Props.chair(c, p, r.cx() - 1, y, r.cz(), Direction.WEST);
        Props.chair(c, p, r.cx() + 1, y, r.cz(), Direction.EAST);
        Props.candles(c, r.cx(), y + 1, r.cz(), 3);
        c.set(r.cx(), y, r.z1(), B.s(Blocks.SMITHING_TABLE));
        weaponRack(c, p, r.x0(), y, r.z1(), Direction.EAST);
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
        Props.candles(c, r.cx(), y + 1, r.cz(), 2);
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
