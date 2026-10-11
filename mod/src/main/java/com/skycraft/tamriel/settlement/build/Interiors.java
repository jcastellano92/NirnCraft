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
     * A household: beds along the back wall with chests at their feet, a hearth, a table with chairs, storage and a rug.
     *
     * @param bed     anchor type of the beds ({@link AnchorType#BED}, or {@link AnchorType#HOUSE_BED} for a house for sale)
     * @param chimney whether the shell has a chimney at (w - 3, back wall); otherwise the fire sits in the middle
     */
    public static void house(Canvas c, Palette p, Room r, int doorX, int beds, boolean rich, AnchorType bed, boolean chimney, String loot) {
        if (r.w() < 3 || r.d() < 3) return;
        int y = r.y();
        // beds along the back wall, heads to the wall
        int placed = 0;
        for (int x = r.x0(); x <= r.x1() && placed < beds; x += 2) {
            if (chimney && x >= r.x1() - 2) break;
            if (r.d() < 4) break;
            c.bed(x, y, r.z1() - 1, p.bed, Direction.SOUTH);
            c.anchor(bed, x, y, r.z1() - 1);
            if (r.d() >= 5) Props.chest(c, x, y, r.z1() - 2, Direction.NORTH, bed == AnchorType.HOUSE_BED ? null : loot);
            placed++;
        }
        // hearth
        if (chimney) {
            Props.hearth(c, p, r.x1() - 1, y, r.z1(), Direction.NORTH);
        } else if (r.w() >= 5 && r.d() >= 5) {
            c.set(r.cx(), y - 1, r.cz(), p.foundation);
            c.set(r.cx(), y, r.cz(), B.lit(p.hearth, true));
        }
        // table and chairs at the front right
        if (r.w() >= 4 && r.d() >= 5) {
            int tx = r.x1() - 1, tz = r.z0() + 1;
            if (tx == doorX) tx--;
            Props.table(c, p, tx, y, tz);
            Props.chair(c, p, tx, y, tz + 1, Direction.SOUTH);
            if (tx + 1 <= r.x1() && tx + 1 != doorX) Props.chair(c, p, tx + 1, y, tz, Direction.EAST);
            c.set(tx, y + 1, tz, rich ? Props.cheese() : B.s(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
        }
        // storage by the door
        if (r.x0() != doorX) {
            Props.barrel(c, r.x0(), y, r.z0(), bed == AnchorType.HOUSE_BED ? null : "food");
            Props.lantern(c, p, r.x0(), y + 1, r.z0(), false);
        }
        if (rich && r.w() >= 5) {
            c.set(r.x0(), y, r.cz(), B.s(Blocks.BOOKSHELF));
            c.set(r.x0(), y + 1, r.cz(), B.s(Blocks.BOOKSHELF));
            Props.pot(c, r.x1(), y, r.cz(), 3);
        }
        Props.rug(c, rich ? p.carpet : p.carpet2, Math.max(r.x0(), r.cx() - 1), y, Math.max(r.z0() + 1, r.cz() - 1),
                Math.min(r.x1(), r.cx() + 1), Math.min(r.z1() - 2, r.cz() + 1));
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
    }

    // ------------------------------------------------------------------ inns

    public static void inn(Canvas c, Palette p, Room hall, Room upstairs, int doorX, boolean firePit) {
        int y = hall.y();
        // bar counter in the back left corner, kegs behind it
        int cx1 = Math.min(hall.x1(), hall.x0() + 4);
        int cz = hall.z1() - 3;
        for (int x = hall.x0(); x <= cx1; x++) {
            c.set(x, y, cz, p.planks);
            c.set(x, y + 1, cz, x % 2 == 0 ? B.slab(p.slab, false) : B.s(Blocks.AIR));
        }
        for (int z = cz + 1; z < hall.z1(); z++) c.set(cx1, y, z, p.planks);
        c.set(hall.x0() + 1, y + 1, cz, B.s(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
        c.set(hall.x0() + 3, y + 1, cz, Props.cheese());
        for (int x = hall.x0(); x < cx1; x++) {
            Props.barrel(c, x, y, hall.z1(), "inn");
            if (x % 2 == 0) Props.barrel(c, x, y + 1, hall.z1(), "inn");
        }
        c.anchor(AnchorType.INN, hall.x0() + 1, y, hall.z1() - 1);
        // fire and cooking
        if (firePit && hall.d() >= 9) {
            Props.firePit(c, p, hall.cx(), y, hall.z0() + 3, 3, true);
        } else {
            Props.hearth(c, p, hall.x1() - 1, y, hall.z1(), Direction.NORTH);
        }
        // tables either side of the fire
        for (int z = hall.z0() + 2; z <= cz - 2; z += 3) {
            for (int side = -1; side <= 1; side += 2) {
                int tx = hall.cx() + side * 3;
                if (tx < hall.x0() || tx > hall.x1()) continue;
                Props.table(c, p, tx, y, z);
                Props.chair(c, p, tx, y, z - 1, Direction.NORTH);
                Props.chair(c, p, tx, y, z + 1, Direction.SOUTH);
                if (c.chance(tx, y, z, 3, 2)) Props.candles(c, tx, y + 1, z, 2);
                else c.set(tx, y + 1, z, Props.cheese());
            }
        }
        c.anchor(AnchorType.BARD, hall.x1(), y, hall.z0() + 1);
        Props.lantern(c, p, hall.cx(), hall.ceil() - 1, hall.cz(), true);
        Props.rug(c, p.carpet, hall.x1() - 1, y, hall.z0(), hall.x1(), hall.z0() + 1);
        // rentable rooms upstairs, or a partitioned back corner
        if (upstairs != null) {
            int uy = upstairs.y();
            int n = 0;
            for (int x = upstairs.x0(); x + 1 <= upstairs.x1() && n < 4; x += 3) {
                c.bed(x, uy, upstairs.z1() - 1, p.bed, Direction.SOUTH);
                c.anchor(AnchorType.INN_BED, x, uy, upstairs.z1() - 1);
                Props.chest(c, x + 1, uy, upstairs.z1(), Direction.NORTH, null);
                Props.candles(c, x + 1, uy + 1, upstairs.z1(), 1);
                n++;
            }
            for (int x = upstairs.x0() + 1; x + 1 <= upstairs.x1() && n < 6; x += 3) {
                c.bed(x, uy, upstairs.z0() + 1, p.bed, Direction.NORTH);
                c.anchor(AnchorType.INN_BED, x, uy, upstairs.z0() + 1);
                n++;
            }
            Props.rug(c, p.carpet, upstairs.x0() + 1, uy, upstairs.cz() - 1, upstairs.x1() - 1, upstairs.cz() + 1);
            Props.lantern(c, p, upstairs.cx(), upstairs.ceil() - 1, upstairs.cz(), true);
        } else if (hall.w() >= 8) {
            int px = hall.x1() - 3;
            for (int z = hall.z1() - 3; z <= hall.z1(); z++) for (int yy = y; yy < hall.ceil() - 1; yy++) c.set(px, yy, z, p.wall);
            for (int x = px; x <= hall.x1(); x++) for (int yy = y; yy < hall.ceil() - 1; yy++) c.set(x, yy, hall.z1() - 3, p.wall);
            c.door(px + 1, y, hall.z1() - 3, p.door, Direction.NORTH);
            c.bed(px + 2, y, hall.z1() - 1, p.bed, Direction.SOUTH);
            c.anchor(AnchorType.INN_BED, px + 2, y, hall.z1() - 1);
            Props.chest(c, px + 1, y, hall.z1(), Direction.NORTH, null);
        }
    }

    // ------------------------------------------------------------------ crafts and trade

    /** The open forge yard of a smithy (local columns 0 .. width-1). */
    public static void forgeYard(Canvas c, Palette p, int width, int d) {
        int y = 1;
        c.set(1, y, 1, B.face(Props.forge(), Direction.EAST));
        c.set(1, y, 3, B.face(Props.grindstone(), Direction.EAST));
        c.set(width - 2, y, d - 2, B.face(Props.workbench(), Direction.NORTH));
        c.set(1, y, d - 2, B.face(Props.smelter(), Direction.EAST));
        c.set(width - 2, y, 1, B.s(Blocks.ANVIL));
        c.set(1, y, 5 < d - 2 ? 5 : d - 3, Props.tanningRack());
        c.set(width - 2, y, 3, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        Props.chest(c, width - 1, y, d - 1, Direction.WEST, "smithy");
        c.anchor(AnchorType.SMITH, 2, y, 2);
    }

    public static void smithyRoom(Canvas c, Palette p, Room r, int doorX) {
        int y = r.y();
        c.bed(r.x1(), y, r.z1() - 1, p.bed, Direction.SOUTH);
        c.anchor(AnchorType.BED, r.x1(), y, r.z1() - 1);
        Props.chest(c, r.x1(), y, r.z1() - 2, Direction.NORTH, "smithy");
        for (int z = r.z0(); z < r.z1() - 2; z++) Props.barrel(c, r.x0(), y, z, z == r.z0() ? "smithy" : null);
        Props.table(c, p, r.cx(), y, r.cz());
        c.set(r.cx(), y + 1, r.cz(), B.s(Blocks.LANTERN));
        Props.chair(c, p, r.cx(), y, r.cz() + 1, Direction.SOUTH);
    }

    /** A shop: a counter across the room, the merchant behind it, shelves and a strongbox. */
    public static void shop(Canvas c, Palette p, Room r, int doorX, AnchorType who, String loot, boolean alchemy) {
        int y = r.y();
        int cz = r.z1() - 2;
        for (int x = r.x0() + 1; x <= r.x1(); x++) {
            c.set(x, y, cz, alchemy ? B.s(Blocks.SPRUCE_PLANKS) : p.planks);
            c.set(x, y + 1, cz, B.s(Blocks.AIR));
        }
        c.set(r.x1(), y + 1, cz, B.s(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
        c.anchor(who, r.cx(), y, r.z1() - 1);
        for (int x = r.x0(); x <= r.x1(); x++) {
            BlockState back = alchemy ? (x % 2 == 0 ? B.s(Blocks.BOOKSHELF) : B.s(Blocks.BREWING_STAND)) : (x % 3 == 0 ? B.s(Blocks.BOOKSHELF) : null);
            if (back != null) c.set(x, y, r.z1(), back);
            else Props.barrel(c, x, y, r.z1(), loot);
            if (alchemy) Props.pot(c, x, y + 1, r.z1(), 7);
        }
        Props.chest(c, r.x0(), y, r.z1() - 1, Direction.EAST, loot);
        if (alchemy) {
            c.set(r.x0(), y, r.z0() + 1, B.face(Props.alchemyLab(), Direction.EAST));
            c.set(r.x0(), y, r.z0() + 3 <= cz - 1 ? r.z0() + 3 : r.z0() + 2, B.s(Blocks.CAULDRON));
        } else {
            for (int z = r.z0(); z < cz - 1; z++) {
                if (z == r.z0() && r.x0() == doorX) continue;
                Props.barrel(c, r.x0(), y, z, z % 2 == 0 ? loot : null);
            }
        }
        Props.lantern(c, p, r.cx(), r.ceil() - 1, r.cz(), true);
        Props.rug(c, p.carpet, Math.max(r.x0() + 1, r.cx() - 1), y, r.z0(), Math.min(r.x1(), r.cx() + 1), cz - 1);
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
     * A great hall: a long fire pit, tables either side, the throne on a dais at the back, the steward's desk and the
     * court wizard's enchanter in side alcoves. Upstairs (if any) is the ruler's bedchamber.
     */
    public static void palace(Canvas c, Palette p, Room r, Room upstairs, int doorX, boolean wizard, boolean firePit) {
        int y = r.y();
        // dais and throne
        int dz = r.z1() - 3;
        for (int x = r.cx() - 3; x <= r.cx() + 3; x++) {
            for (int z = dz; z <= r.z1(); z++) c.set(x, y, z, p.floor2.equals(p.floor) ? p.planks : p.floor2);
            c.set(x, y, dz - 1, B.stair(p.trim, Direction.SOUTH, false));
        }
        c.set(r.cx(), y + 1, r.z1() - 1, B.stair(p.stairs, Direction.SOUTH, false));
        c.set(r.cx() - 1, y + 1, r.z1() - 1, p.column);
        c.set(r.cx() + 1, y + 1, r.z1() - 1, p.column);
        c.set(r.cx(), y + 1, r.z1(), p.column);
        c.set(r.cx(), y + 2, r.z1(), p.column);
        Props.tapestry(c, p, r.cx() - 2, y + 4, r.z1());
        Props.tapestry(c, p, r.cx() + 2, y + 4, r.z1());
        Props.tapestry(c, p, r.cx(), y + 5, r.z1());
        c.anchor(AnchorType.JARL, r.cx(), y + 1, r.z1() - 1);
        c.anchor(AnchorType.HOUSECARL, r.cx() + 2, y + 1, r.z1() - 2);
        c.anchor(AnchorType.GUARD, r.cx() - 3, y + 1, r.z1() - 2);
        c.anchor(AnchorType.GUARD, doorX - 2, y, r.z0() + 1);
        c.anchor(AnchorType.GUARD, doorX + 2, y, r.z0() + 1);
        // fire pit and long tables
        int pitLen = Math.max(3, dz - r.z0() - 6);
        if (firePit) Props.firePit(c, p, r.cx(), y, r.z0() + 3, pitLen, true);
        else Props.rug(c, p.carpet, r.cx() - 1, y, r.z0(), r.cx() + 1, dz - 2);
        for (int side = -1; side <= 1; side += 2) {
            int tx = r.cx() + side * 4;
            if (tx <= r.x0() || tx >= r.x1()) continue;
            for (int z = r.z0() + 3; z < r.z0() + 3 + pitLen; z++) {
                Props.table(c, p, tx, y, z);
                Props.chair(c, p, tx - side, y, z, side < 0 ? Direction.EAST : Direction.WEST);
                Props.chair(c, p, tx + side, y, z, side < 0 ? Direction.WEST : Direction.EAST);
                if (z % 3 == 0) c.set(tx, y + 1, z, Props.cheese());
                else if (z % 3 == 1) Props.candles(c, tx, y + 1, z, 3);
            }
        }
        // steward's desk (left) and wizard's corner (right)
        int sx = r.x0(), sz = r.z0() + 2;
        Props.table(c, p, sx, y, sz);
        c.set(sx, y + 1, sz, B.s(Blocks.LECTERN));
        Props.chest(c, sx, y, sz + 1, Direction.EAST, "palace");
        c.anchor(AnchorType.STEWARD, sx + 1, y, sz);
        if (wizard) {
            int wx = r.x1(), wz = r.z0() + 2;
            c.set(wx, y, wz, B.face(Props.enchanter(), Direction.WEST));
            c.set(wx, y, wz + 1, B.face(Props.alchemyLab(), Direction.WEST));
            c.set(wx, y, wz + 2, B.s(Blocks.BOOKSHELF));
            c.set(wx, y + 1, wz + 2, B.s(Blocks.BOOKSHELF));
            c.anchor(AnchorType.WIZARD, wx - 1, y, wz);
        }
        for (int z = r.z0() + 1; z < dz - 1; z += 4) {
            Props.brazier(c, p, r.x0(), y, z + 2);
            Props.brazier(c, p, r.x1(), y, z + 2);
        }
        for (int z = r.z0() + 4; z < dz; z += 5) Props.lantern(c, p, r.cx(), r.ceil() - 1, z, true);
        Props.chest(c, r.x0(), y, r.z1(), Direction.EAST, "palace");
        Props.chest(c, r.x1(), y, r.z1(), Direction.WEST, "palace");
        if (upstairs != null) {
            int uy = upstairs.y();
            c.bed(upstairs.cx(), uy, upstairs.z1() - 1, p.bed, Direction.SOUTH);
            c.bed(upstairs.cx() + 1, uy, upstairs.z1() - 1, p.bed, Direction.SOUTH);
            c.anchor(AnchorType.BED, upstairs.cx(), uy, upstairs.z1() - 1);
            Props.chest(c, upstairs.cx() - 1, uy, upstairs.z1(), Direction.NORTH, "palace");
            Props.rug(c, p.carpet, upstairs.cx() - 2, uy, upstairs.cz() - 1, upstairs.cx() + 2, upstairs.cz() + 1);
            for (int x = upstairs.x0(); x <= upstairs.x1(); x += 3) {
                c.bed(x, uy, upstairs.z0() + 1, p.bed, Direction.NORTH);
                c.anchor(AnchorType.BED, x, uy, upstairs.z0() + 1);
            }
            Props.lantern(c, p, upstairs.cx(), upstairs.ceil() - 1, upstairs.cz(), true);
        }
    }

    /** A mead hall / chieftain's longhouse. */
    public static void longhouse(Canvas c, Palette p, Room r, int doorX, boolean seat) {
        int y = r.y();
        Props.firePit(c, p, r.cx(), y, r.z0() + 3, Math.max(3, r.d() - 9), true);
        for (int side = -1; side <= 1; side += 2) {
            int tx = r.cx() + side * 3;
            for (int z = r.z0() + 3; z < r.z1() - 4; z++) {
                Props.table(c, p, tx, y, z);
                Props.chair(c, p, tx + side, y, z, side < 0 ? Direction.WEST : Direction.EAST);
                if (z % 2 == 0) c.set(tx, y + 1, z, z % 4 == 0 ? Props.cheese() : B.s(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
            }
        }
        if (seat) {
            c.set(r.cx(), y, r.z1() - 1, B.stair(p.stairs, Direction.SOUTH, false));
            c.set(r.cx(), y, r.z1(), p.column);
            c.set(r.cx(), y + 1, r.z1(), p.column);
            c.anchor(AnchorType.JARL, r.cx(), y, r.z1() - 1);
            c.anchor(AnchorType.HOUSECARL, r.cx() + 1, y, r.z1() - 2);
        }
        for (int x = r.x0(); x <= r.x1(); x += 2) {
            if (Math.abs(x - r.cx()) <= 1) continue;
            c.bed(x, y, r.z1() - 1, p.bed, Direction.SOUTH);
            c.anchor(AnchorType.BED, x, y, r.z1() - 1);
        }
        for (int z = r.z0() + 2; z < r.z1() - 3; z += 4) {
            Props.pelts(c, p, r.x0(), y, z);
            Props.pelts(c, p, r.x1(), y, z);
            Props.barrel(c, r.x0(), y, z + 1, "food");
        }
        Props.tapestry(c, p, r.cx() - 2, y + 3, r.z1());
        Props.tapestry(c, p, r.cx() + 2, y + 3, r.z1());
        Props.chest(c, r.x1(), y, r.z1() - 2, Direction.WEST, "house_rich");
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
        Props.candles(c, r.cx(), y + 1, r.cz(), 3);
        c.set(r.cx(), y, r.z1(), B.s(Blocks.SMITHING_TABLE));
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
