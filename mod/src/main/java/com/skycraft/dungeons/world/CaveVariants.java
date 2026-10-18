package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static com.skycraft.dungeons.world.NaturalRooms.edgeSpot;
import static com.skycraft.dungeons.world.NaturalRooms.inside;
import static com.skycraft.dungeons.world.NaturalRooms.loot;
import static com.skycraft.dungeons.world.NaturalRooms.put;
import static com.skycraft.dungeons.world.NaturalRooms.putEdge;
import static com.skycraft.dungeons.world.NaturalRooms.spot;

/**
 * Room templates for the carved dungeons, laid over each cavern's own furnishing so a cave, mine or lair is not one
 * room repeated: a collapsed gallery, a flooded pool, a shrine, an ore outcrop, a bone nest, a fungal garden, a ritual
 * circle, a prison cell, an armoury, a camp. Rooms pick from their theme's pool by position, so neighbours differ.
 */
public final class CaveVariants {
    private CaveVariants() {}

    private enum Kind { NONE, COLLAPSE, POOL, SHRINE, ORE, BONES, FUNGAL, RITUAL, PRISON, ARMOURY, CAMP }

    private static Kind[] pool(Theme t) {
        return switch (t) {
            case MINE -> new Kind[]{Kind.COLLAPSE, Kind.ARMOURY, Kind.ORE, Kind.POOL, Kind.CAMP, Kind.COLLAPSE, Kind.NONE, Kind.ORE};
            case VAMPIRE_LAIR -> new Kind[]{Kind.SHRINE, Kind.PRISON, Kind.BONES, Kind.RITUAL, Kind.NONE, Kind.POOL, Kind.SHRINE, Kind.PRISON};
            case NECROMANCER_LAIR -> new Kind[]{Kind.RITUAL, Kind.PRISON, Kind.BONES, Kind.SHRINE, Kind.FUNGAL, Kind.NONE, Kind.RITUAL, Kind.POOL};
            case HAGRAVEN_LAIR -> new Kind[]{Kind.BONES, Kind.FUNGAL, Kind.SHRINE, Kind.POOL, Kind.RITUAL, Kind.NONE, Kind.BONES, Kind.CAMP};
            default -> new Kind[]{Kind.NONE, Kind.COLLAPSE, Kind.POOL, Kind.SHRINE, Kind.BONES, Kind.FUNGAL, Kind.ORE, Kind.CAMP};
        };
    }

    public static void dress(Painter p, Room room) {
        if (room.boss() || room.vista() != null) return;
        Kind[] pool = pool(room.theme());
        Kind k = pool[p.roll(room.cx, 11, room.cz, 2100, pool.length)];
        // the entrance cavern stays open: only a camp or a shrine may stand in it
        if (room.start() && k != Kind.CAMP && k != Kind.SHRINE) return;
        switch (k) {
            case COLLAPSE -> collapse(p, room);
            case POOL -> pool(p, room);
            case SHRINE -> shrine(p, room);
            case ORE -> ore(p, room);
            case BONES -> bones(p, room);
            case FUNGAL -> fungal(p, room);
            case RITUAL -> ritual(p, room);
            case PRISON -> prison(p, room);
            case ARMOURY -> armoury(p, room);
            case CAMP -> camp(p, room);
            default -> {
            }
        }
    }

    /** True for a free, carved floor cell: inside the cavern, off the lanes and the doors. */
    private static boolean free(Painter p, Room room, int x, int z) {
        return room.interior(x, z) && !room.onLane(x, z) && !room.nearDoor(x, z, 1) && inside(p, room, x, z, 0.82) && p.ok(x, room.floor, z);
    }

    // ------------------------------------------------------------------ templates

    /** Rubble heaps and a fallen beam: the roof came down here. */
    private static void collapse(Painter p, Room room) {
        p.feature("room:collapse");
        for (int i = 0; i < 3; i++) {
            int[] s = spot(p, room, 2110 + i);
            if (s == null) continue;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = s[0] + dx, z = s[1] + dz;
                    if (!free(p, room, x, z)) continue;
                    int roll = p.roll(x, i, z, 2111, 6);
                    BlockState b = roll < 2 ? Blocks.COBBLESTONE.defaultBlockState() : roll < 4 ? Blocks.GRAVEL.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
                    p.setIfOpen(x, room.floor, z, b);
                    if (dx == 0 && dz == 0 || p.chance(x, i, z, 2112, 3)) p.setIfOpen(x, room.floor + 1, z, b);
                }
            }
        }
        int[] s = edgeSpot(p, room, 2115);
        if (s != null) {
            boolean alongX = p.chance(s[0], 0, s[1], 2116, 2);
            for (int k = 0; k < 4; k++) {
                int x = s[0] + (alongX ? k : 0), z = s[1] + (alongX ? 0 : k);
                if (free(p, room, x, z)) p.setIfOpen(x, room.floor, z, Deco.pillar(Blocks.SPRUCE_LOG, alongX ? Direction.Axis.X : Direction.Axis.Z));
            }
        }
        putEdge(p, room, 2117, Blocks.COBWEB.defaultBlockState());
    }

    /** A pool sunk into the floor (sealed by the rock round it), with moss, lilies and a glimmer at the bottom. */
    private static void pool(Painter p, Room room) {
        p.feature("room:pool");
        int[] s = spot(p, room, 2120);
        if (s == null) return;
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = s[0] + dx, z = s[1] + dz;
                if (dx * dx + dz * dz > 5 || !free(p, room, x, z) || !p.ok(x, room.floor - 2, z)) continue;
                // the rim must be rock all round: skip cells that touch a doorway or the cavern's edge
                if (!inside(p, room, x + 1, z, 0.85) || !inside(p, room, x - 1, z, 0.85) || !inside(p, room, x, z + 1, 0.85) || !inside(p, room, x, z - 1, 0.85)) continue;
                p.set(x, room.floor - 2, z, NaturalBuilder.rock(room.floor - 2));
                p.set(x, room.floor - 1, z, water);
                if (p.chance(x, 0, z, 2121, 6)) p.setIfOpen(x, room.floor, z, Blocks.LILY_PAD.defaultBlockState());
            }
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = s[0] + d.getStepX() * 3, z = s[1] + d.getStepZ() * 3;
            if (free(p, room, x, z)) p.setIfOpen(x, room.floor, z, Blocks.MOSS_CARPET.defaultBlockState());
        }
        p.set(s[0], room.floor - 2, s[1], Blocks.SEA_LANTERN.defaultBlockState());
    }

    /** A little raised altar with candles and a lantern, and an offering beside it. */
    private static void shrine(Painter p, Room room) {
        p.feature("room:shrine");
        int[] s = edgeSpot(p, room, 2130);
        if (s == null) return;
        boolean dark = room.theme() == Theme.VAMPIRE_LAIR || room.theme() == Theme.NECROMANCER_LAIR;
        BlockState slab = (dark ? Blocks.POLISHED_BLACKSTONE_SLAB : Blocks.STONE_BRICK_SLAB).defaultBlockState();
        net.minecraft.world.level.block.Block candle = room.theme() == Theme.VAMPIRE_LAIR ? com.skycraft.worldgen.VanillaBlocks.RED_CANDLE
                : room.theme() == Theme.NECROMANCER_LAIR ? com.skycraft.worldgen.VanillaBlocks.PURPLE_CANDLE : Blocks.CANDLE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (free(p, room, s[0] + dx, s[1] + dz)) p.setIfOpen(s[0] + dx, room.floor, s[1] + dz, slab);
            }
        }
        p.set(s[0], room.floor, s[1], dark ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        p.set(s[0], room.floor + 1, s[1], Deco.candle(candle, 4, true));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = s[0] + d.getStepX() * 2, z = s[1] + d.getStepZ() * 2;
            if (free(p, room, x, z) && p.chance(x, 0, z, 2131, 2)) p.setIfOpen(x, room.floor, z, Deco.candle(candle, 2, true));
        }
        loot(p, room, 2132, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
    }

    /** An outcrop of ore standing out of the floor. */
    private static void ore(Painter p, Room room) {
        p.feature("room:ore");
        for (int i = 0; i < 2; i++) {
            int[] s = spot(p, room, 2140 + i);
            if (s == null) continue;
            boolean deep = room.floor < 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = s[0] + dx, z = s[1] + dz;
                    if (!free(p, room, x, z)) continue;
                    if (Math.abs(dx) + Math.abs(dz) == 2 && p.chance(x, i, z, 2141, 2)) continue;
                    int h = dx == 0 && dz == 0 ? 3 : 1 + p.roll(x, i, z, 2142, 2);
                    for (int y = 0; y < h; y++) p.set(x, room.floor + y, z, y == h - 1 && p.chance(x, y, z, 2143, 3)
                            ? Blocks.RAW_IRON_BLOCK.defaultBlockState() : Deco.ore(p, x, room.floor + y, z, deep));
                }
            }
        }
        putEdge(p, room, 2144, Blocks.COAL_BLOCK.defaultBlockState());
    }

    /** A nest: a ring of bones round straw, a skull or two, webs. */
    private static void bones(Painter p, Room room) {
        p.feature("room:nest");
        int[] s = spot(p, room, 2150);
        if (s == null) return;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int d2 = dx * dx + dz * dz;
                int x = s[0] + dx, z = s[1] + dz;
                if (d2 > 5 || !free(p, room, x, z)) continue;
                if (d2 >= 4) p.setIfOpen(x, room.floor, z, p.chance(x, 0, z, 2151, 3) ? Deco.skull(Blocks.SKELETON_SKULL, p.roll(x, 1, z, 2152, 16))
                        : Deco.pillar(Blocks.BONE_BLOCK, p.chance(x, 2, z, 2153, 2) ? Direction.Axis.X : Direction.Axis.Z));
                else p.setIfOpen(x, room.floor, z, Deco.pillar(Blocks.HAY_BLOCK, Direction.Axis.Y));
            }
        }
        putEdge(p, room, 2154, Blocks.COBWEB.defaultBlockState());
    }

    /** Mushrooms and moss: a small garden with one squat giant. */
    private static void fungal(Painter p, Room room) {
        p.feature("room:fungal_garden");
        int[] s = spot(p, room, 2160);
        if (s != null && free(p, room, s[0], s[1])) {
            for (int y = 0; y < 3; y++) p.set(s[0], room.floor + y, s[1], Blocks.MUSHROOM_STEM.defaultBlockState());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) p.set(s[0] + dx, room.floor + 3, s[1] + dz, Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState());
            }
            p.set(s[0], room.floor + 2, s[1], Blocks.SHROOMLIGHT.defaultBlockState());
        }
        for (int i = 0; i < 9; i++) {
            int[] t = spot(p, room, 2165 + i);
            if (t == null || !free(p, room, t[0], t[1])) continue;
            p.setIfOpen(t[0], room.floor, t[1], i % 3 == 0 ? Blocks.RED_MUSHROOM.defaultBlockState() : i % 3 == 1
                    ? Blocks.BROWN_MUSHROOM.defaultBlockState() : Blocks.MOSS_CARPET.defaultBlockState());
        }
    }

    /** A circle of candles round an altar, with a skull on it. */
    private static void ritual(Painter p, Room room) {
        p.feature("room:ritual_circle");
        int[] s = spot(p, room, 2170);
        if (s == null) return;
        net.minecraft.world.level.block.Block candle = room.theme() == Theme.VAMPIRE_LAIR ? com.skycraft.worldgen.VanillaBlocks.RED_CANDLE : com.skycraft.worldgen.VanillaBlocks.PURPLE_CANDLE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                int x = s[0] + dx, z = s[1] + dz;
                if (d < 2.4 || d > 3.2 || !free(p, room, x, z)) continue;
                p.setIfOpen(x, room.floor, z, (dx + dz) % 2 == 0 ? Deco.candle(candle, 1 + Math.floorMod(x + z, 3), true)
                        : Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState());
            }
        }
        p.set(s[0], room.floor, s[1], Blocks.CRYING_OBSIDIAN.defaultBlockState());
        p.set(s[0], room.floor + 1, s[1], Deco.skull(Blocks.SKELETON_SKULL, 8));
    }

    /** A 3x3 cell of iron bars with a skeleton in it and a way in. */
    private static void prison(Painter p, Room room) {
        p.feature("room:prison");
        int[] s = edgeSpot(p, room, 2180);
        if (s == null) return;
        Direction open = NaturalRooms.faceCenter(room, s[0], s[1]);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                int x = s[0] + dx, z = s[1] + dz;
                if (!free(p, room, x, z)) continue;
                // the gap in the bars faces the cavern
                if (dx == open.getStepX() * 2 && dz == open.getStepZ() * 2) continue;
                for (int y = 0; y < 2; y++) p.set(x, room.floor + y, z, Blocks.IRON_BARS.defaultBlockState());
            }
        }
        if (free(p, room, s[0], s[1])) {
            p.set(s[0], room.floor, s[1], Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X));
            p.set(s[0] + 1, room.floor, s[1], Deco.skull(Blocks.SKELETON_SKULL, 4));
            p.set(s[0], room.floor + 2, s[1], Deco.chain(Direction.Axis.Y));
        }
        p.container(s[0] - open.getStepX(), room.floor, s[1] - open.getStepZ(), Deco.chest(open), Deco.LOOT_MINOR);
    }

    /** Racks and benches: an anvil, a grindstone, barrels of kit. */
    private static void armoury(Painter p, Room room) {
        p.feature("room:armoury");
        putEdge(p, room, 2190, Blocks.ANVIL.defaultBlockState());
        putEdge(p, room, 2191, Blocks.GRINDSTONE.defaultBlockState());
        putEdge(p, room, 2192, Blocks.FLETCHING_TABLE.defaultBlockState());
        putEdge(p, room, 2193, Blocks.SMITHING_TABLE.defaultBlockState());
        loot(p, room, 2194, Deco.barrel(Direction.UP), room.theme() == Theme.MINE ? Deco.LOOT_MINE : Deco.LOOT_MINOR);
        loot(p, room, 2195, Deco.chest(Direction.SOUTH), Deco.LOOT_COMMON);
        put(p, room, 2196, Deco.lantern(false, false));
    }

    /** A cold camp: a fire, a bedroll or two, a barrel. */
    private static void camp(Painter p, Room room) {
        p.feature("room:camp");
        put(p, room, 2200, Deco.campfire(false, true));
        NaturalRooms.bedroll(p, room, 2201);
        NaturalRooms.bedroll(p, room, 2202);
        loot(p, room, 2203, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
        NaturalRooms.lampPost(p, room, 2204, false);
    }
}
