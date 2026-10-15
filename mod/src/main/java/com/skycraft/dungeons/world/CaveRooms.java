package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static com.skycraft.dungeons.world.NaturalRooms.boss;
import static com.skycraft.dungeons.world.NaturalRooms.edgeSpot;
import static com.skycraft.dungeons.world.NaturalRooms.inside;
import static com.skycraft.dungeons.world.NaturalRooms.loot;
import static com.skycraft.dungeons.world.NaturalRooms.mob;
import static com.skycraft.dungeons.world.NaturalRooms.put;
import static com.skycraft.dungeons.world.NaturalRooms.putEdge;
import static com.skycraft.dungeons.world.NaturalRooms.spot;

/**
 * The other folk of Skyrim's caves ({@link CaveRoster}): frostbite spider nests, troll dens, Falmer warrens with
 * their chitin huts, Forsworn hideouts, spriggan-haunted grottos, ice caves full of wraiths and skeever warrens.
 */
public final class CaveRooms {
    private CaveRooms() {}

    /** Scatters {@code count} blocks from the chooser over free floor spots. */
    private static void scatter(Painter p, Room room, int salt, int count, java.util.function.IntFunction<BlockState> pick) {
        for (int i = 0; i < count; i++) {
            int[] s = spot(p, room, salt + i);
            if (s != null) p.set(s[0], room.floor, s[1], pick.apply(p.roll(s[0], i, s[1], salt, 1000)));
        }
    }

    // ------------------------------------------------------------------ frostbite spiders

    static void spiders(Painter p, Room room) {
        p.feature("room:spiders");
        // webs everywhere, egg sacs (sniffer-egg stand-ins) and wrapped victims
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                if (room.onLane(x, z) || !inside(p, room, x, z, 0.95)) continue;
                for (int y = room.floor; y < room.maxY; y++) {
                    if (p.chance(x, y, z, 600, y == room.floor ? 7 : 11)) p.setIfOpen(x, y, z, Blocks.COBWEB.defaultBlockState());
                }
            }
        }
        scatter(p, room, 610, 3, r -> r % 3 == 0 ? Blocks.BONE_BLOCK.defaultBlockState() : Deco.skull(Blocks.SKELETON_SKULL, r % 16));
        putEdge(p, room, 615, Blocks.WHITE_WOOL.defaultBlockState());
        if (room.boss()) {
            boss(p, room, 620, Mobs.orElse("skycraft:giant_frostbite_spider", "skycraft:frostbite_spider", "minecraft:spider"), "Giant Frostbite Spider");
            mob(p, room, 621, Mobs.spider(), null);
            mob(p, room, 622, Mobs.spider(), null);
            loot(p, room, 623, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            return;
        }
        int n = 1 + p.roll(room.cx, 0, room.cz, 624, 3);
        for (int i = 0; i < n; i++) mob(p, room, 625 + i, Mobs.spider(), null);
    }

    // ------------------------------------------------------------------ troll den

    static void troll(Painter p, Room room) {
        p.feature("room:troll");
        scatter(p, room, 630, 7, r -> switch (r % 4) {
            case 0 -> Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X);
            case 1 -> Deco.skull(Blocks.SKELETON_SKULL, r % 16);
            case 2 -> Blocks.COARSE_DIRT.defaultBlockState();
            default -> Blocks.MOSS_CARPET.defaultBlockState();
        });
        if (room.boss()) {
            boss(p, room, 640, Mobs.troll(), null);
            loot(p, room, 641, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            put(p, room, 642, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Y));
            return;
        }
        if (p.chance(room.cx, 0, room.cz, 643, 2)) {
            // the troll sleeps off its meal until someone comes near
            int[] s = spot(p, room, 644);
            if (s != null) p.spawnDormant(Mobs.troll(), s[0], room.floor, s[1], p.roll(s[0], 0, s[1], 104, 360));
        } else {
            mob(p, room, 645, Mobs.denBeast(p.roll(room.cx, 1, room.cz, 646, 4)), null);
        }
    }

    // ------------------------------------------------------------------ Falmer warren

    /** A Falmer hut: a squat dome of mushroom-chitin with a low doorway, at a spot in the cavern. */
    static void hut(Painter p, Room room, int salt) {
        int[] s = spot(p, room, salt);
        if (s == null) return;
        int cx = s[0], cz = s[1];
        Direction door = NaturalRooms.faceCenter(room, cx, cz);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 5) continue;
                int x = cx + dx, z = cz + dz;
                if (room.onLane(x, z) || !inside(p, room, x, z, 0.92)) continue;
                boolean shell = d2 >= 4;
                boolean doorway = dx == door.getStepX() * 2 && dz == door.getStepZ() * 2;
                for (int dy = 0; dy <= 2; dy++) {
                    boolean roof = dy == 2 && d2 <= 2;
                    if ((shell && dy < 2 && !doorway) || roof) {
                        p.set(x, room.floor + dy, z, p.chance(x, dy, z, 650, 3) ? Blocks.MUSHROOM_STEM.defaultBlockState()
                                : Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState());
                    }
                }
                if (dx == 0 && dz == 0) p.set(x, room.floor + 3, z, Blocks.SHROOMLIGHT.defaultBlockState());
            }
        }
    }

    static void falmer(Painter p, Room room) {
        p.feature("room:falmer");
        hut(p, room, 660);
        if (room.halfX() >= 5 && room.halfZ() >= 5) hut(p, room, 661);
        scatter(p, room, 665, 5, r -> switch (r % 4) {
            case 0 -> Blocks.GLOW_LICHEN.defaultBlockState().setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(Direction.DOWN), true);
            case 1 -> Blocks.BROWN_MUSHROOM.defaultBlockState();
            case 2 -> Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Z);
            default -> Blocks.MYCELIUM.defaultBlockState();
        });
        if (room.boss()) {
            boss(p, room, 670, Mobs.falmer(), "Falmer Gloomlurker");
            mob(p, room, 671, Mobs.falmer(), null);
            mob(p, room, 672, Mobs.spider(), "Chaurus");
            loot(p, room, 673, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            loot(p, room, 674, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
            return;
        }
        mob(p, room, 675, Mobs.falmer(), null);
        if (p.chance(room.cx, 0, room.cz, 676, 2)) mob(p, room, 677, Mobs.spider(), "Chaurus");
        if (room.piece.has(DungeonPiece.FLAG_DEAD_END)) loot(p, room, 678, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
    }

    // ------------------------------------------------------------------ Forsworn hideout

    static void forsworn(Painter p, Room room) {
        p.feature("room:forsworn");
        put(p, room, 680, Deco.campfire(false, true));
        putEdge(p, room, 681, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Y));
        putEdge(p, room, 682, Deco.skull(Blocks.SKELETON_SKULL, 4));
        NaturalRooms.bedroll(p, room, 683);
        // hide drying racks
        int[] s = edgeSpot(p, room, 684);
        if (s != null) {
            p.set(s[0], room.floor, s[1], Blocks.SPRUCE_FENCE.defaultBlockState());
            p.set(s[0], room.floor + 1, s[1], Blocks.BROWN_WOOL.defaultBlockState());
        }
        String named = Painter.entityExists("skycraft:forsworn") ? null : "Forsworn";
        if (room.boss()) {
            boss(p, room, 690, Mobs.forsworn(), "Forsworn Briarheart");
            mob(p, room, 691, Mobs.hagraven(), Painter.entityExists("skycraft:hagraven") ? null : "Hagraven");
            mob(p, room, 692, Mobs.forsworn(), named);
            loot(p, room, 693, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            return;
        }
        mob(p, room, 694, Mobs.forsworn(), named);
        if (p.chance(room.cx, 0, room.cz, 695, 2)) mob(p, room, 696, Mobs.forsworn(), named);
        if (room.piece.has(DungeonPiece.FLAG_DEAD_END)) loot(p, room, 697, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
    }

    // ------------------------------------------------------------------ spriggan grotto

    static void spriggans(Painter p, Room room) {
        p.feature("room:spriggans");
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                if (!inside(p, room, x, z, 0.9) || !p.ok(x, room.floor - 1, z)) continue;
                double n = p.noise(x, 0, z, 3.0, 700);
                if (n > 0.1) p.set(x, room.floor - 1, z, Blocks.MOSS_BLOCK.defaultBlockState());
                if (room.onLane(x, z)) continue;
                if (n > 0.45) p.set(x, room.floor, z, Blocks.MOSS_CARPET.defaultBlockState());
                else if (n < -0.55) p.set(x, room.floor, z, p.chance(x, 1, z, 701, 2) ? Blocks.FERN.defaultBlockState() : Blocks.BLUE_ORCHID.defaultBlockState());
            }
        }
        put(p, room, 702, Blocks.SHROOMLIGHT.defaultBlockState());
        putEdge(p, room, 703, Blocks.AZALEA.defaultBlockState());
        if (room.boss()) {
            boss(p, room, 710, Mobs.spriggan(), "Spriggan Matron");
            mob(p, room, 711, Mobs.spriggan(), null);
            mob(p, room, 712, Mobs.denBeast(0), null);
            loot(p, room, 713, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            loot(p, room, 714, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
            return;
        }
        mob(p, room, 715, Mobs.spriggan(), null);
        if (p.chance(room.cx, 0, room.cz, 716, 3)) mob(p, room, 717, Mobs.denBeast(1), null);
    }

    // ------------------------------------------------------------------ ice cave

    static void ice(Painter p, Room room) {
        p.feature("room:ice");
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                if (!inside(p, room, x, z, 0.95) || !p.ok(x, room.floor - 1, z)) continue;
                p.set(x, room.floor - 1, z, p.chance(x, 0, z, 720, 3) ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState());
                if (!room.onLane(x, z) && p.chance(x, 1, z, 721, 5)) p.set(x, room.floor, z, Blocks.SNOW.defaultBlockState());
            }
        }
        // icicles hang from the roof
        for (int i = 0; i < 4; i++) {
            int[] s = spot(p, room, 722 + i);
            if (s == null) continue;
            for (int y = room.maxY - 1; y > room.floor + 3; y--) {
                if (p.ok(s[0], y, s[1]) && p.get(s[0], y, s[1]).isAir() && !p.get(s[0], y + 1, s[1]).isAir()) {
                    p.set(s[0], y, s[1], Blocks.PACKED_ICE.defaultBlockState());
                    if (p.ok(s[0], y - 1, s[1])) p.set(s[0], y - 1, s[1], Blocks.ICE.defaultBlockState());
                    break;
                }
            }
        }
        if (room.boss()) {
            boss(p, room, 730, Mobs.troll(), "Frost Troll");
            mob(p, room, 731, Mobs.iceWraith(), null);
            mob(p, room, 732, Mobs.iceWraith(), null);
            loot(p, room, 733, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            return;
        }
        mob(p, room, 734, Mobs.iceWraith(), null);
        if (p.chance(room.cx, 0, room.cz, 735, 2)) mob(p, room, 736, Mobs.spider(), null);
    }

    // ------------------------------------------------------------------ skeever warren

    static void skeevers(Painter p, Room room) {
        p.feature("room:skeevers");
        scatter(p, room, 740, 6, r -> switch (r % 3) {
            case 0 -> Blocks.COARSE_DIRT.defaultBlockState();
            case 1 -> Blocks.BONE_BLOCK.defaultBlockState();
            default -> Blocks.HAY_BLOCK.defaultBlockState();
        });
        int n = 2 + p.roll(room.cx, 0, room.cz, 741, 3);
        for (int i = 0; i < n; i++) mob(p, room, 742 + i, Mobs.skeever(), null);
        if (room.boss()) {
            boss(p, room, 750, Mobs.skeever(), "Giant Skeever");
            loot(p, room, 751, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
        }
    }

    // ------------------------------------------------------------------ scorpion den

    static void scorpions(Painter p, Room room) {
        p.feature("room:scorpion_clutch");
        // sand-choked floor drifts and half-buried Yokudan sandstone columns
        for (int x = room.minX + 1; x < room.maxX; x++) {
            for (int z = room.minZ + 1; z < room.maxZ; z++) {
                if (!inside(p, room, x, z, 0.92) || !p.ok(x, room.floor - 1, z)) continue;
                int roll = p.roll(x, 0, z, 760, 10);
                if (roll < 4) p.set(x, room.floor - 1, z, Blocks.SAND.defaultBlockState());
                else if (roll < 7) p.set(x, room.floor - 1, z, Blocks.RED_SAND.defaultBlockState());
                else p.set(x, room.floor - 1, z, Blocks.CUT_SANDSTONE.defaultBlockState());
            }
        }
        // half-buried Yokudan columns
        int[] col = edgeSpot(p, room, 761);
        if (col != null) {
            p.set(col[0], room.floor, col[1], Blocks.CHISELED_RED_SANDSTONE.defaultBlockState());
            p.set(col[0], room.floor + 1, col[1], Blocks.CUT_RED_SANDSTONE.defaultBlockState());
        }
        // sun-bleached bones and scorpion egg clutches
        scatter(p, room, 762, 6, r -> switch (r % 4) {
            case 0 -> Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X);
            case 1 -> Deco.skull(Blocks.SKELETON_SKULL, r % 16);
            case 2 -> Blocks.SANDSTONE_SLAB.defaultBlockState();
            default -> Blocks.OCHRE_FROGLIGHT.defaultBlockState();
        });
        if (room.boss()) {
            boss(p, room, 770, Mobs.giantScorpion(), "Broodmother Scorpion");
            mob(p, room, 771, Mobs.giantScorpion(), null);
            mob(p, room, 772, Mobs.giantScorpion(), null);
            loot(p, room, 773, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            loot(p, room, 774, Deco.barrel(Direction.UP), Deco.LOOT_COMMON);
            return;
        }
        int n = 1 + p.roll(room.cx, 0, room.cz, 775, 3);
        for (int i = 0; i < n; i++) mob(p, room, 776 + i, Mobs.giantScorpion(), null);
        if (room.piece.has(DungeonPiece.FLAG_DEAD_END)) loot(p, room, 780, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
    }
}
