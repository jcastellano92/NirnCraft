package com.nirncraft.tamriel.settlement.build;

import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.settlement.AnchorType;
import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.tamriel.settlement.kit.Palette;
import com.nirncraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * A hold's jail (Castle Dour's dungeon, the Dragonsreach dungeon, Mistveil Keep's jail...) as a stone gaol beside
 * the keep. Front: the guard room with the guards' tables, the evidence chest and the confiscated goods. Through a locked iron
 * gate: the cell block, a corridor between two rows of three barred cells with iron doors, beds and buckets; the two
 * back cells hold NPC prisoners. At the corridor's end a rusted drain grate drops into an old culvert that comes out
 * behind the jail - the way out for those who would rather not serve their time (the other is picking the cell door
 * and the gate). Variant 1 is Cidhna Mine: rough-hewn rock with silver veins in the cell walls for the prisoners'
 * labour. Footprint 15 x 21; anchors in cell order (see {@link AnchorType#JAIL_CELL}). A guard of the jail
 * walks the cell block (spawned by {@code crime.Jail}); the cell walls have a grate window by each door.
 */
public final class Jails {
    private Jails() {}

    public static final int W = 15, D = 21;
    /** {@link Spec#variant} of a minor town's guardhouse (see {@link #guardhouse}), footprint {@link #GW} x {@link #GD}. */
    public static final int GUARDHOUSE = 2;
    public static final int GW = 13, GD = 13;
    /** Cell rows: first and last z of each cell (left cells x 1..4, right cells x 10..13). */
    static final int[][] ROWS = {{8, 10}, {12, 14}, {16, 19}};
    static final int GATE_Z = 7, CORRIDOR_X = 7, ROOF = 6;

    public static void build(Canvas c, Palette p, Spec s, Culture culture, String settlement) {
        if (s.variant == GUARDHOUSE) {
            guardhouse(c, p, settlement);
            return;
        }
        boolean mine = s.variant == 1;
        int w = W, d = D;
        BlockState wall = mine ? B.s(Blocks.STONE) : p.cityWall;
        BlockState wall2 = mine ? B.s(Blocks.ANDESITE) : p.cityWall2;
        BlockState floor = mine ? B.s(Blocks.COBBLESTONE) : B.s(Blocks.STONE_BRICKS);
        BlockState ore = Props.ore("nirncraft:silver_ore", Blocks.IRON_ORE);

        if (!c.recording()) {
            c.clear(1, 1, 1, w - 2, ROOF - 1, d - 2);
            c.clearPlants(-2, 1, -2, w + 1, ROOF + 3, d + 1);
        }
        // floor, foundation, walls and a flat crenellated roof
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                c.set(x, 0, z, edge ? wall : (c.chance(x, 0, z, 41, 6) ? B.s(Blocks.CRACKED_STONE_BRICKS) : floor));
                c.foundation(x, -1, z, p.foundation, Shells.SettlementPieceDepth.DEPTH);
                if (edge) {
                    for (int y = 1; y < ROOF; y++) c.set(x, y, z, c.chance(x, y, z, 43, 5) ? wall2 : wall);
                }
                c.set(x, ROOF, z, wall);
                if (edge && (x + z) % 2 == 0) c.set(x, ROOF + 1, z, p.cityWallWall);
            }
        }
        // arrow slits with bars on the long walls
        for (int z = 3; z < d - 2; z += 4) {
            c.set(0, 3, z, bars(Direction.NORTH, Direction.SOUTH));
            c.set(w - 1, 3, z, bars(Direction.NORTH, Direction.SOUTH));
        }
        // the front door, the jail's name and a lamp either side
        int doorX = (w - 1) / 2;
        c.entrance(doorX, 1, 0, mine ? B.s(Blocks.IRON_DOOR) : p.door, Direction.SOUTH);
        c.set(doorX - 1, 3, -1, p.light);
        c.set(doorX + 1, 3, -1, p.light);
        if (!c.recording()) {
            Component name = jailTitle(s.name, settlement);
            Props.shopSign(c, p, "jail", doorX, 4, -1, Direction.EAST, name, Component.literal("Barracks & Jail"));
        }
        c.anchor(AnchorType.JAIL_EXIT, doorX, 1, -2);
        c.anchor(AnchorType.JAIL_MIN, 0, -4, 0);
        c.anchor(AnchorType.JAIL_MAX, w - 1, ROOF + 1, d - 1);

        guardRoom(c, p, w, mine);
        cellBlock(c, p, w, d, mine, wall, ore);
        culvert(c, p, d, wall);
    }

    static Component jailTitle(String name, String settlement) {
        if (!"jail".equals(name)) return Component.translatableWithFallback("settlement.nirncraft.name." + name, "Jail");
        Component place = Places.byId(settlement).map(pl -> pl.name()).orElse(Component.literal(settlement));
        return Component.translatableWithFallback("settlement.nirncraft.jail_of", "%s Jail", place);
    }

    static BlockState bars(Direction a, Direction b) {
        BlockState s = B.s(Blocks.IRON_BARS);
        for (Direction dir : new Direction[]{a, b}) {
            s = s.setValue(switch (dir) {
                case NORTH -> IronBarsBlock.NORTH;
                case SOUTH -> IronBarsBlock.SOUTH;
                case EAST -> IronBarsBlock.EAST;
                default -> IronBarsBlock.WEST;
            }, true);
        }
        return s;
    }

    /** Guard room (z 1..6): tables, the guards' posts, the evidence chest and the confiscated goods by the side walls. */
    static void guardRoom(Canvas c, Palette p, int w, boolean mine) {
        int y = 1;
        // the jail's two containers, in the guard room and in no cell (docs/JAIL.md): the evidence chest by the west
        // wall (unlocked, a prisoner's own things come back when it is used) and the confiscated goods, a barrel by
        // the east wall (locked, the shared stolen goods); each with a sign above it
        c.set(1, y, 1, B.face(B.s(Blocks.CHEST), Direction.EAST).setValue(ChestBlock.TYPE, ChestType.SINGLE));
        c.anchor(AnchorType.JAIL_PERSONAL, 1, y, 1);
        c.set(w - 2, y, 1, B.s(Blocks.BARREL));
        c.anchor(AnchorType.JAIL_CONFISCATED, w - 2, y, 1);
        if (!c.recording()) {
            Component key = Component.keybind("key.nirncraft.interact");
            c.sign(1, y + 1, 1, p.sign, Direction.EAST, Component.translatable("crime.nirncraft.jail.sign.evidence"),
                    Component.translatable("crime.nirncraft.jail.sign.evidence_2"), Component.translatable("crime.nirncraft.jail.sign.use_evidence", key));
            c.sign(w - 2, y + 1, 1, p.sign, Direction.WEST, Component.translatable("crime.nirncraft.jail.sign.confiscated"),
                    Component.translatable("crime.nirncraft.jail.sign.confiscated_2"), Component.translatable("crime.nirncraft.jail.sign.use_confiscated", key));
        }
        // the guards' tables, a weapon rack and lamps
        Props.desk(c, p, 3, y, 5, Direction.NORTH);
        Props.table(c, p, 4, y, 5);
        Props.chair(c, p, 4, y, 4, Direction.SOUTH);
        Props.weaponRack(c, p, 1, y, 4, Direction.EAST);
        Props.table(c, p, w - 4, y, 5);
        c.set(w - 5, y, 5, B.s(Blocks.SMITHING_TABLE));
        c.set(w - 4, y + 1, 5, B.s(Blocks.LANTERN));
        c.anchor(AnchorType.GUARD, 4, y, 3);
        c.anchor(AnchorType.GUARD, w - 5, y, 3);
        Props.lantern(c, p, 5, ROOF - 1, 3, true);
        Props.lantern(c, p, w - 6, ROOF - 1, 3, true);
        // the wall to the cell block with its locked iron gate and barred peep holes
        for (int x = 1; x < w - 1; x++) {
            for (int yy = 1; yy < ROOF; yy++) c.set(x, yy, GATE_Z, p.cityWall);
        }
        c.door(CORRIDOR_X, 1, GATE_Z, B.s(Blocks.IRON_DOOR), Direction.SOUTH);
        c.set(CORRIDOR_X - 1, 2, GATE_Z, bars(Direction.EAST, Direction.WEST));
        c.set(CORRIDOR_X + 1, 2, GATE_Z, bars(Direction.EAST, Direction.WEST));
        c.anchor(AnchorType.JAIL_GATE, CORRIDOR_X, 1, GATE_Z);
        if (mine) {
            // Cidhna's mouth: rubble and a cart of ore by the gate
            c.set(2, y, 6, B.s(Blocks.COBBLESTONE));
            Props.barrel(c, w - 4, y, 6, null);
        }
    }

    /** The cell block (z 8..19): corridor x 6..8, barred walls at x 5 and 9, three cells either side. */
    static void cellBlock(Canvas c, Palette p, int w, int d, boolean mine, BlockState wall, BlockState ore) {
        int y = 1;
        int lastZ = d - 2;
        // barred walls along the corridor
        for (int z = GATE_Z + 1; z <= lastZ; z++) {
            for (int yy = 1; yy <= 3; yy++) {
                c.set(5, yy, z, bars(Direction.NORTH, Direction.SOUTH));
                c.set(9, yy, z, bars(Direction.NORTH, Direction.SOUTH));
            }
            c.set(5, 4, z, wall);
            c.set(9, 4, z, wall);
        }
        // dividers between the cells
        for (int[] row : new int[][]{{11}, {15}}) {
            for (int yy = 1; yy < ROOF; yy++) {
                for (int x = 1; x <= 4; x++) c.set(x, yy, row[0], wall);
                for (int x = 10; x <= 13; x++) c.set(x, yy, row[0], wall);
            }
        }
        int cell = 0;
        for (int[] row : ROWS) {
            int za = row[0], zb = row[1], zc = (za + zb) / 2;
            for (int side = 0; side < 2; side++, cell++) {
                boolean left = side == 0;
                int bars = left ? 5 : 9;
                Direction out = left ? Direction.EAST : Direction.WEST;
                // the door stands against the divider (or the gate wall) with a stone post beside it, so the bars
                // never end at a door's thin panel; one bar of the wall is left out at head height: a pane-free
                // grate a prisoner can reach through (pickpocket, talk) and cannot fit through
                c.door(bars, 1, za, B.s(Blocks.IRON_DOOR), out);
                c.anchor(AnchorType.JAIL_DOOR, bars, 1, za);
                for (int yy = 1; yy <= 3; yy++) c.set(bars, yy, za + 1, wall);
                c.set(bars, 2, za + 2, B.s(Blocks.AIR));
                int stand = left ? 3 : 11;
                c.anchor(AnchorType.JAIL_CELL, stand, y, zc);
                // a straw bed in the back corner and a slop bucket
                int bx = left ? 2 : 12;
                c.bed(bx, y, za, B.s(com.nirncraft.worldgen.VanillaBlocks.BROWN_BED), left ? Direction.WEST : Direction.EAST);
                c.set(left ? 1 : 13, y, zb, B.s(Blocks.CAULDRON));
                if (cell % 2 == 0) c.setIfOpen(left ? 2 : 12, y, zb, B.slab(B.s(Blocks.SPRUCE_SLAB), false));
                if (cell >= 4) c.anchor(AnchorType.JAIL_PRISONER, stand, y, zc);
                if (mine) {
                    // silver veins in the cell's rock for the prisoners' labour
                    int wx = left ? 0 : w - 1;
                    c.set(wx, 1, za, ore);
                    c.set(wx, 2, zc, ore);
                    c.set(wx, 1, zb, ore);
                }
            }
        }
        // the corridor: a dim lamp, and the drain grate at its end
        Props.lantern(c, p, CORRIDOR_X, ROOF - 1, 11, true);
        Props.lantern(c, p, CORRIDOR_X, ROOF - 1, 17, true);
        c.set(CORRIDOR_X, 0, lastZ, B.s(Blocks.IRON_TRAPDOOR).setValue(TrapDoorBlock.HALF, Half.TOP));
    }

    /** The old culvert under the back wall: drops from the grate, runs out and climbs to a hatch behind the jail. */
    static void culvert(Canvas c, Palette p, int d, BlockState wall) {
        int x = CORRIDOR_X;
        int z0 = d - 2, z1 = d + 1;
        for (int z = z0; z <= z1; z++) {
            for (int yy = -4; yy <= 0; yy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    boolean inside = dx == 0 && yy >= -3 && yy <= -2;
                    boolean shaft = dx == 0 && (z == z0 || z == z1) && yy >= -3;
                    if (inside || shaft) {
                        if (!(z == z0 && yy == 0) && !(z == z1 && yy == 0)) c.set(x + dx, yy, z, B.s(Blocks.AIR));
                    } else if (yy < 0 || z > d - 1) {
                        if (!(z == z1 && yy == 0 && dx == 0)) c.set(x + dx, yy, z, yy == -4 ? B.s(Blocks.MOSSY_COBBLESTONE) : wall);
                    }
                }
            }
        }
        // a ladder up the far shaft against its back wall, under a wooden hatch in the ground
        for (int yy = -3; yy <= -1; yy++) {
            c.set(x, yy, z1 + 1, wall);
            c.set(x, yy, z1, B.face(B.s(Blocks.LADDER), Direction.NORTH).setValue(LadderBlock.FACING, Direction.NORTH));
        }
        c.set(x, 0, z1 + 1, wall);
        c.set(x, 0, z1, B.s(Blocks.SPRUCE_TRAPDOOR).setValue(TrapDoorBlock.HALF, Half.TOP));
    }

    /**
     * A minor town's guardhouse: one low building above ground, 13 x 13. Front: the guard room with the guards' table,
     * the evidence chest and the confiscated goods (as in a hold's jail). Through a locked iron gate: a one block
     * corridor and one to three barred cells in a row (the count is the town's own), each with its iron door against a
     * stone post, a grate window beside it, a bed and a bucket. No drain: the way out is the front door past the guard.
     * Anchors as a hold jail's, so {@code crime.Jail} serves it the same way (cells claimed in order).
     */
    static void guardhouse(Canvas c, Palette p, String settlement) {
        int w = GW, d = GD, roof = 5, gateZ = 6, frontZ = 8, doorX = (w - 1) / 2;
        int cells = 1 + Math.floorMod(settlement.hashCode(), 3);
        BlockState wall = p.cityWall, wall2 = p.cityWall2, floor = B.s(Blocks.STONE_BRICKS);
        if (!c.recording()) {
            c.clear(1, 1, 1, w - 2, roof - 1, d - 2);
            c.clearPlants(-2, 1, -2, w + 1, roof + 3, d + 1);
        }
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                c.set(x, 0, z, edge ? wall : (c.chance(x, 0, z, 41, 6) ? B.s(Blocks.CRACKED_STONE_BRICKS) : floor));
                c.foundation(x, -1, z, p.foundation, Shells.SettlementPieceDepth.DEPTH);
                if (edge) {
                    for (int y = 1; y < roof; y++) c.set(x, y, z, c.chance(x, y, z, 43, 5) ? wall2 : wall);
                }
                c.set(x, roof, z, wall);
                if (edge && (x + z) % 2 == 0) c.set(x, roof + 1, z, p.cityWallWall);
            }
        }
        c.entrance(doorX, 1, 0, p.door, Direction.SOUTH);
        c.set(doorX - 1, 3, -1, p.light);
        c.set(doorX + 1, 3, -1, p.light);
        if (!c.recording()) {
            Props.shopSign(c, p, "guardhouse", doorX, 4, -1, Direction.EAST, jailTitle("jail", settlement), Component.literal("Guardhouse"));
        }
        c.anchor(AnchorType.JAIL_EXIT, doorX, 1, -2);
        c.anchor(AnchorType.JAIL_MIN, 0, -4, 0);
        c.anchor(AnchorType.JAIL_MAX, w - 1, roof + 1, d - 1);

        // the guard room (z 1..5): the evidence chest and the confiscated goods by the side walls, a table, two posts
        int y = 1;
        c.set(1, y, 1, B.face(B.s(Blocks.CHEST), Direction.EAST).setValue(ChestBlock.TYPE, ChestType.SINGLE));
        c.anchor(AnchorType.JAIL_PERSONAL, 1, y, 1);
        c.set(w - 2, y, 1, B.s(Blocks.BARREL));
        c.anchor(AnchorType.JAIL_CONFISCATED, w - 2, y, 1);
        if (!c.recording()) {
            Component key = Component.keybind("key.nirncraft.interact");
            c.sign(1, y + 1, 1, p.sign, Direction.EAST, Component.translatable("crime.nirncraft.jail.sign.evidence"),
                    Component.translatable("crime.nirncraft.jail.sign.evidence_2"), Component.translatable("crime.nirncraft.jail.sign.use_evidence", key));
            c.sign(w - 2, y + 1, 1, p.sign, Direction.WEST, Component.translatable("crime.nirncraft.jail.sign.confiscated"),
                    Component.translatable("crime.nirncraft.jail.sign.confiscated_2"), Component.translatable("crime.nirncraft.jail.sign.use_confiscated", key));
        }
        Props.table(c, p, 5, y, 4);
        Props.table(c, p, 7, y, 4);
        Props.chair(c, p, 6, y, 3, Direction.SOUTH);
        c.anchor(AnchorType.GUARD, 3, y, 3);
        c.anchor(AnchorType.GUARD, w - 4, y, 3);
        Props.lantern(c, p, 3, roof - 1, 3, true);
        Props.lantern(c, p, w - 4, roof - 1, 3, true);

        // the wall to the cells with its locked iron gate and barred peep holes
        for (int x = 1; x < w - 1; x++) {
            for (int yy = 1; yy < roof; yy++) c.set(x, yy, gateZ, p.cityWall);
        }
        c.door(doorX, 1, gateZ, B.s(Blocks.IRON_DOOR), Direction.SOUTH);
        c.set(doorX - 1, 2, gateZ, bars(Direction.EAST, Direction.WEST));
        c.set(doorX + 1, 2, gateZ, bars(Direction.EAST, Direction.WEST));
        c.anchor(AnchorType.JAIL_GATE, doorX, 1, gateZ);

        // the corridor (z 7) and the cells' barred front (z 8): door against a post, then bars with a grate window
        Props.lantern(c, p, doorX, roof - 1, 7, true);
        for (int x = 1; x < w - 1; x++) {
            for (int yy = 1; yy <= 3; yy++) c.set(x, yy, frontZ, bars(Direction.EAST, Direction.WEST));
            c.set(x, 4, frontZ, wall);
        }
        for (int k = 0; k < cells; k++) {
            int xa = 1 + 4 * k;
            c.door(xa, 1, frontZ, B.s(Blocks.IRON_DOOR), Direction.NORTH);
            c.anchor(AnchorType.JAIL_DOOR, xa, 1, frontZ);
            for (int yy = 1; yy <= 3; yy++) c.set(xa + 1, yy, frontZ, wall);
            c.set(xa + 2, 2, frontZ, B.s(Blocks.AIR));
            c.anchor(AnchorType.JAIL_CELL, xa + 1, y, 10);
            c.bed(xa + 2, y, 10, B.s(com.nirncraft.worldgen.VanillaBlocks.BROWN_BED), Direction.SOUTH);
            c.set(xa, y, 11, B.s(Blocks.CAULDRON));
            if (k < cells - 1) {
                for (int z = frontZ; z <= d - 2; z++) {
                    for (int yy = 1; yy < roof; yy++) c.set(xa + 3, yy, z, wall);
                }
            }
        }
        // what is not a cell is a stone store room
        for (int x = 1 + 4 * cells; x < w - 1; x++) {
            for (int z = frontZ; z <= d - 2; z++) {
                for (int yy = 1; yy < roof; yy++) c.set(x, yy, z, wall);
            }
        }
    }
}
