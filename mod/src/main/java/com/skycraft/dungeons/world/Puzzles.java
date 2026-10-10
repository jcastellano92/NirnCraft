package com.skycraft.dungeons.world;

import com.skycraft.dungeons.DungeonsRegistry;
import com.skycraft.dungeons.puzzle.Claw;
import com.skycraft.dungeons.puzzle.ClawKeyholeBlock;
import com.skycraft.dungeons.puzzle.Symbol;
import com.skycraft.dungeons.puzzle.SymbolStoneBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * The sealed door before a barrow's last chamber. Every dungeon picks one kind from its dungeon-wide flavor, so the
 * antechamber (which holds the claw) and the gate room agree without seeing each other:
 * <ul>
 *     <li>{@link Kind#CLAW}: a claw door; its claw waits in a pack by a dead adventurer in the antechamber;</li>
 *     <li>{@link Kind#PILLARS}: three glyph stones over the door must match the tablets over the doorway across the
 *     room before the lever works (wrong glyphs earn poisoned darts);</li>
 *     <li>{@link Kind#LEVER}: a lever hidden behind a pillar.</li>
 * </ul>
 */
public final class Puzzles {
    private Puzzles() {}

    public enum Kind { LEVER, PILLARS, CLAW }

    public static Kind kind(DungeonPiece piece) {
        if (piece.theme == Theme.DWEMER || piece.theme == Theme.FORT) return Kind.LEVER;
        if (piece.special == Special.BLEAK_FALLS || piece.special == Special.USTENGRAV) return Kind.CLAW;
        if (piece.special == Special.FORELHOST || piece.special == Special.SAARTHAL) return Kind.PILLARS;
        if (piece.theme == Theme.BARROW && (piece.kit == Kit.NONE || piece.kit == Kit.NORD)) {
            return switch (Math.floorMod(piece.flavor, 3)) {
                case 0 -> Kind.CLAW;
                case 1 -> Kind.PILLARS;
                default -> Kind.LEVER;
            };
        }
        if (piece.theme == Theme.RUIN) return Math.floorMod(piece.flavor, 2) == 0 ? Kind.PILLARS : Kind.LEVER;
        return Kind.LEVER;
    }

    /** The claw that opens this dungeon's claw door. */
    public static Claw claw(DungeonPiece piece) {
        if (piece.special == Special.BLEAK_FALLS) return Claw.GOLDEN;
        if (piece.special == Special.USTENGRAV) return Claw.IVORY;
        return Claw.byIndex(piece.flavor * 7 + piece.param * 3 + piece.special.ordinal() * 5 + 1);
    }

    /** The answer of a pillar puzzle: glyphs for lateral offsets -1, 0, +1 (seen facing the door). */
    static Symbol[] answer(Painter p, Room room) {
        Symbol[] a = new Symbol[3];
        for (int i = 0; i < 3; i++) a[i] = Symbol.byIndex(p.roll(room.cx, i, room.cz, 500, Symbol.COUNT));
        return a;
    }

    // ------------------------------------------------------------------ claw door

    public static void clawDoor(Painter p, Room room, Direction side) {
        Direction inward = side.getOpposite();
        Direction right = side.getClockWise();
        int wx = side == Direction.EAST ? room.maxX : side == Direction.WEST ? room.minX : room.cx;
        int wz = side == Direction.SOUTH ? room.maxZ : side == Direction.NORTH ? room.minZ : room.cz;
        Claw claw = claw(room.piece);
        BlockState seal = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        BlockState ring = DungeonsRegistry.SYMBOL_PILLAR.get().defaultBlockState();
        for (int lat = -1; lat <= 1; lat++) {
            for (int dy = 0; dy <= 2; dy++) {
                int x = wx + right.getStepX() * lat, z = wz + right.getStepZ() * lat;
                if (lat == 0) {
                    // the rings start scrambled: never already showing the claw's palm
                    Symbol s = Symbol.byIndex(claw.symbol(2 - dy).ordinal() + 1 + p.roll(x, dy, z, 501, Symbol.COUNT - 1));
                    p.set(x, room.floor + dy, z, SymbolStoneBlock.with(ring, inward, s));
                } else if (lat == 1 && dy == 1) {
                    p.set(x, room.floor + dy, z, DungeonsRegistry.CLAW_KEYHOLE.get().defaultBlockState()
                            .setValue(ClawKeyholeBlock.FACING, inward).setValue(ClawKeyholeBlock.CLAW, claw.ordinal()));
                } else {
                    p.set(x, room.floor + dy, z, seal);
                }
            }
        }
        // the dragon's brow over the door, candles on the approach
        for (int lat = -2; lat <= 2; lat++) {
            int x = wx + right.getStepX() * lat, z = wz + right.getStepZ() * lat;
            p.set(x, room.floor + 3, z, Math.abs(lat) == 2 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                    : Deco.stair(Blocks.POLISHED_DEEPSLATE_STAIRS, side, true));
        }
        for (int s : new int[]{-2, 2}) {
            int x = wx + right.getStepX() * s + inward.getStepX() * 2, z = wz + right.getStepZ() * s + inward.getStepZ() * 2;
            if (!room.interior(x, z) || room.onLane(x, z)) continue;
            p.set(x, room.floor, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            p.set(x, room.floor + 1, z, Deco.candle(Blocks.CANDLE, 4, true));
        }
    }

    /** The claw in its owner's pack, in the antechamber: a chest by a skeleton on a stone bench. */
    public static void clawCache(Painter p, Room room) {
        if (kind(room.piece) != Kind.CLAW) return;
        int[][] spots = {{room.maxX - 2, room.minZ + 2}, {room.minX + 2, room.minZ + 2}, {room.maxX - 2, room.maxZ - 2}};
        for (int[] s : spots) {
            if (!room.interior(s[0], s[1]) || room.onLane(s[0], s[1]) || room.nearDoor(s[0], s[1], 1)) continue;
            p.set(s[0], room.floor, s[1], Deco.chest(BarrowRooms.towardCenter(room, s[0], s[1])));
            if (p.blockEntity(s[0], room.floor, s[1]) instanceof ChestBlockEntity chest) {
                chest.setItem(13, new ItemStack(DungeonsRegistry.CLAWS.get(claw(room.piece)).get()));
            }
            int bx = s[0] + (s[0] > room.cx ? -1 : 1);
            if (room.interior(bx, s[1]) && !room.onLane(bx, s[1])) {
                p.set(bx, room.floor, s[1], Deco.skull(Blocks.SKELETON_SKULL, p.roll(bx, 0, s[1], 502, 16)));
            }
            return;
        }
    }

    // ------------------------------------------------------------------ pillar puzzle

    /** Glyph stones over the gate (scrambled), the answer over the doorway across the room, the puzzle lever. */
    public static void pillars(Painter p, Room room, Direction side) {
        if (room.height < 5) return;
        Direction inward = side.getOpposite();
        Direction right = side.getClockWise();
        int wx = side == Direction.EAST ? room.maxX : side == Direction.WEST ? room.minX : room.cx;
        int wz = side == Direction.SOUTH ? room.maxZ : side == Direction.NORTH ? room.minZ : room.cz;
        Symbol[] answer = answer(p, room);
        BlockState pillar = DungeonsRegistry.SYMBOL_PILLAR.get().defaultBlockState();
        BlockState tablet = DungeonsRegistry.SYMBOL_TABLET.get().defaultBlockState();
        // across the room: the far wall's centre
        int span = side.getAxis() == Direction.Axis.X ? room.maxX - room.minX : room.maxZ - room.minZ;
        int hx = wx + inward.getStepX() * span, hz = wz + inward.getStepZ() * span;
        for (int lat = -1; lat <= 1; lat++) {
            int x = wx + right.getStepX() * lat, z = wz + right.getStepZ() * lat;
            Symbol start = Symbol.byIndex(answer[lat + 1].ordinal() + 1 + p.roll(x, 3, z, 503, Symbol.COUNT - 1));
            p.set(x, room.floor + 3, z, SymbolStoneBlock.with(pillar, inward, start));
            // mirrored, so both read left to right from where one stands
            int tx = hx - right.getStepX() * lat, tz = hz - right.getStepZ() * lat;
            p.set(tx, room.floor + 3, tz, SymbolStoneBlock.with(tablet, side, answer[lat + 1]));
        }
        // the lever on the right jamb becomes the puzzle lever
        int jx = wx + right.getStepX() + inward.getStepX(), jz = wz + right.getStepZ() + inward.getStepZ();
        p.set(jx, room.floor + 1, jz, DungeonsRegistry.PUZZLE_LEVER.get().defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, inward));
        // brazier glow on the tablets
        for (int s : new int[]{-2, 2}) {
            int x = hx - right.getStepX() * s - inward.getStepX(), z = hz - right.getStepZ() * s - inward.getStepZ();
            if (room.interior(x, z) && !room.onLane(x, z) && !room.nearDoor(x, z, 0)) p.set(x, room.floor + 3, z, Deco.wallTorch(true, side));
        }
    }
}
