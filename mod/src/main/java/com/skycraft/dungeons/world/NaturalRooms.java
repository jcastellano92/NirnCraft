package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

import javax.annotation.Nullable;

/**
 * Furnishing for carved dungeons: bandit hideouts and animal dens (caves), vampire, necromancer and hagraven lairs,
 * and mines. Placement spots are chosen from the cavern's shape function (never by reading other chunks), so every
 * chunk agrees on where things go.
 */
public final class NaturalRooms {
    private NaturalRooms() {}

    // ------------------------------------------------------------------ spots

    /** True if (x, z) at floor level is comfortably inside the carved cavern. */
    static boolean inside(Painter p, Room room, int x, int z, double margin) {
        if (!room.interior(x, z)) return false;
        double rx = room.halfX() - 0.5, rz = room.halfZ() - 0.5;
        double nx = (x - room.cx) / rx, nz = (z - room.cz) / rz;
        double n = p.noise(x, room.floor, z, 4.0, 80);
        double d = room.theme() == Theme.MINE ? (nx * nx) * (nx * nx) + (nz * nz) * (nz * nz) + n * 0.15 : nx * nx + nz * nz + n * 0.3;
        return d < margin;
    }

    /** A free floor spot off the walking lanes, or null. Salt varies the pick. */
    @Nullable
    static int[] spot(Painter p, Room room, int salt) {
        int w = Math.max(1, room.maxX - room.minX - 1), d = Math.max(1, room.maxZ - room.minZ - 1);
        for (int i = 0; i < 20; i++) {
            int x = room.minX + 1 + p.roll(room.cx, salt, i, 100, w);
            int z = room.minZ + 1 + p.roll(room.cz, salt, i, 101, d);
            if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
            if (inside(p, room, x, z, 0.72)) return new int[]{x, z};
        }
        return null;
    }

    /** A spot next to the cavern wall (for furniture). */
    @Nullable
    static int[] edgeSpot(Painter p, Room room, int salt) {
        int w = Math.max(1, room.maxX - room.minX - 1), d = Math.max(1, room.maxZ - room.minZ - 1);
        for (int i = 0; i < 24; i++) {
            int x = room.minX + 1 + p.roll(room.cx, salt, i, 102, w);
            int z = room.minZ + 1 + p.roll(room.cz, salt, i, 103, d);
            if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
            if (inside(p, room, x, z, 0.85) && !inside(p, room, x, z, 0.5)) return new int[]{x, z};
        }
        return spot(p, room, salt + 1);
    }

    static void put(Painter p, Room room, int salt, BlockState state) {
        int[] s = spot(p, room, salt);
        if (s != null) p.set(s[0], room.floor, s[1], state);
    }

    static void putEdge(Painter p, Room room, int salt, BlockState state) {
        int[] s = edgeSpot(p, room, salt);
        if (s != null) p.set(s[0], room.floor, s[1], state);
    }

    static void loot(Painter p, Room room, int salt, BlockState container, net.minecraft.resources.ResourceLocation table) {
        int[] s = edgeSpot(p, room, salt);
        // a cramped cavern still gets its chest: beside the middle, which is always carved
        if (s == null) s = new int[]{room.cx + 2 * (p.chance(room.cx, salt, room.cz, 105, 2) ? 1 : -1), room.cz + 2};
        p.container(s[0], room.floor, s[1], container, table);
    }

    /** The master of the cave, tagged as the dungeon's boss. */
    static void boss(Painter p, Room room, int salt, String id, @Nullable String name) {
        int[] s = spot(p, room, salt);
        if (s == null) s = new int[]{room.cx + 1, room.cz + 1};
        p.spawnBoss(id, s[0], room.floor, s[1], p.roll(s[0], salt, s[1], 104, 360), name);
    }

    /**
     * Glow for caverns no one lights: lichen on the floor along the walls and among the stones (beasts see in the
     * dark; players need to see them coming).
     */
    static void glow(Painter p, Room room) {
        BlockState lichen = Blocks.GLOW_LICHEN.defaultBlockState().setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        int n = 3 + room.halfX() * room.halfZ() / 9;
        for (int i = 0; i < n; i++) {
            int[] s = i % 3 == 2 ? spot(p, room, 320 + i) : edgeSpot(p, room, 320 + i);
            if (s != null && p.ok(s[0], room.floor, s[1])) p.setIfOpen(s[0], room.floor, s[1], lichen);
        }
    }

    /** The common foe of a cave's folk, for the extra guard of the deep rooms. */
    static String commonFoe(Room room) {
        return switch (room.theme()) {
            case VAMPIRE_LAIR -> Mobs.vampire();
            case NECROMANCER_LAIR -> "minecraft:skeleton";
            case HAGRAVEN_LAIR -> Mobs.forsworn();
            case MINE -> room.piece.flavor % 2 == 1 ? Mobs.bandit() : Mobs.skeever();
            default -> switch (CaveRoster.of(room.piece.flavor)) {
                case CaveRoster.BANDIT, CaveRoster.SMUGGLER -> Mobs.bandit();
                case CaveRoster.SPIDER -> Mobs.spider();
                case CaveRoster.FALMER -> Mobs.falmer();
                case CaveRoster.FORSWORN -> Mobs.forsworn();
                case CaveRoster.SPRIGGAN -> Mobs.spriggan();
                case CaveRoster.ICE -> Mobs.iceWraith();
                case CaveRoster.SKEEVER -> Mobs.skeever();
                default -> Mobs.denBeast(room.cx + room.cz);
            };
        };
    }

    static void mob(Painter p, Room room, int salt, String id, @Nullable String name) {
        int[] s = spot(p, room, salt);
        if (s == null) s = new int[]{room.cx + 1, room.cz + 1};
        p.spawn(id, s[0], room.floor, s[1], p.roll(s[0], salt, s[1], 104, 360), name);
    }

    static Direction faceCenter(Room room, int x, int z) {
        return BarrowRooms.towardCenter(room, x, z);
    }

    /** A post with a lantern on top. */
    static void lampPost(Painter p, Room room, int salt, boolean soul) {
        int[] s = edgeSpot(p, room, salt);
        if (s == null) return;
        p.set(s[0], room.floor, s[1], Blocks.SPRUCE_FENCE.defaultBlockState());
        p.set(s[0], room.floor + 1, s[1], Deco.lantern(soul, false));
    }

    /** A 1x2 bedroll of carpet. */
    static void bedroll(Painter p, Room room, int salt) {
        int[] s = spot(p, room, salt);
        if (s == null) return;
        boolean alongX = p.chance(s[0], salt, s[1], 105, 2);
        int x2 = s[0] + (alongX ? 1 : 0), z2 = s[1] + (alongX ? 0 : 1);
        if (room.onLane(x2, z2) || !inside(p, room, x2, z2, 0.8)) return;
        BlockState c = switch (p.roll(s[0], salt, s[1], 106, 3)) {
            case 0 -> Blocks.BROWN_CARPET.defaultBlockState();
            case 1 -> Blocks.WHITE_CARPET.defaultBlockState();
            default -> Blocks.LIGHT_GRAY_CARPET.defaultBlockState();
        };
        p.set(s[0], room.floor, s[1], c);
        p.set(x2, room.floor, z2, c);
    }

    // ------------------------------------------------------------------ dispatch

    public static void decorate(Painter p, Room room, RandomSource r) {
        Theme t = room.theme();
        int flavor = room.piece.flavor;
        switch (t) {
            case VAMPIRE_LAIR -> vampire(p, room);
            case NECROMANCER_LAIR -> necromancer(p, room);
            case HAGRAVEN_LAIR -> hagraven(p, room);
            case MINE -> mine(p, room, flavor);
            default -> {
                switch (CaveRoster.of(flavor)) {
                    case CaveRoster.BANDIT, CaveRoster.SMUGGLER -> bandits(p, room);
                    case CaveRoster.SPIDER -> CaveRooms.spiders(p, room);
                    case CaveRoster.TROLL -> CaveRooms.troll(p, room);
                    case CaveRoster.FALMER -> CaveRooms.falmer(p, room);
                    case CaveRoster.FORSWORN -> CaveRooms.forsworn(p, room);
                    case CaveRoster.SPRIGGAN -> CaveRooms.spriggans(p, room);
                    case CaveRoster.ICE -> CaveRooms.ice(p, room);
                    case CaveRoster.SKEEVER -> CaveRooms.skeevers(p, room);
                    default -> den(p, room);
                }
            }
        }
        if (room.piece.has(DungeonPiece.FLAG_TREASURE) && !room.boss()) loot(p, room, 99, Deco.chest(Direction.NORTH), Deco.LOOT_COMMON);
        // the deep rooms are guarded harder: the danger ramps towards the boss
        if (!room.boss() && !room.start() && room.tier() >= 5) mob(p, room, 199, commonFoe(room), null);
        boolean lit = t == Theme.CAVE && CaveRoster.inhabited(CaveRoster.of(flavor));
        if (!lit && t != Theme.MINE) glow(p, room);
        if (room.has(DungeonPiece.FLAG_SETPIECE)) SetPieces.natural(p, room);
        // dripping ceilings
        for (int i = 0; i < 3; i++) {
            int[] s = spot(p, room, 200 + i);
            if (s != null && p.chance(s[0], i, s[1], 107, 2)) {
                int top = room.floor + 3;
                for (int y = room.maxY - 1; y > top; y--) {
                    if (p.ok(s[0], y, s[1]) && p.get(s[0], y, s[1]).isAir() && !p.get(s[0], y + 1, s[1]).isAir()) {
                        p.set(s[0], y, s[1], t == Theme.MINE ? Blocks.COBWEB.defaultBlockState() : Blocks.POINTED_DRIPSTONE.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN));
                        break;
                    }
                }
            }
        }
    }

    /** Light along winding tunnels, by theme. */
    static void tunnelLight(Painter p, Theme t, int x, int f, int z, Direction latPlus, int flavor) {
        int lx = x + latPlus.getStepX(), lz = z + latPlus.getStepZ();
        switch (t) {
            case VAMPIRE_LAIR -> p.set(lx, f, lz, Deco.candle(Blocks.RED_CANDLE, 2, true));
            case NECROMANCER_LAIR -> p.set(lx, f, lz, Blocks.SOUL_TORCH.defaultBlockState());
            case HAGRAVEN_LAIR -> p.set(lx, f, lz, Deco.candle(Blocks.BROWN_CANDLE, 1, true));
            case CAVE -> {
                int roster = CaveRoster.of(flavor);
                if (CaveRoster.inhabited(roster)) p.set(lx, f, lz, Blocks.TORCH.defaultBlockState());
                else if (roster == CaveRoster.FALMER || roster == CaveRoster.SPRIGGAN) p.set(lx, f, lz, Blocks.SHROOMLIGHT.defaultBlockState());
                else p.setIfOpen(lx, f, lz, Blocks.GLOW_LICHEN.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(Direction.DOWN), true));
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ bandit hideout

    static void bandits(Painter p, Room room) {
        p.feature("room:bandits");
        if (room.boss()) {
            int[] s = edgeSpot(p, room, 1);
            if (s != null) {
                Direction face = faceCenter(room, s[0], s[1]);
                p.set(s[0], room.floor, s[1], Deco.stair(Blocks.SPRUCE_STAIRS, face.getOpposite(), false));
                p.spawnBoss(Mobs.banditChief(), s[0] + face.getStepX(), room.floor, s[1] + face.getStepZ(), Painter.yaw(face), null);
            } else {
                boss(p, room, 1, Mobs.banditChief(), null);
            }
            loot(p, room, 2, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            loot(p, room, 3, Deco.chest(Direction.NORTH), Deco.LOOT_COMMON);
            put(p, room, 4, Deco.campfire(false, true));
            for (int i = 0; i < 4; i++) put(p, room, 10 + i, Blocks.BROWN_CARPET.defaultBlockState());
            mob(p, room, 5, Mobs.bandit(), null);
            mob(p, room, 6, Mobs.bandit(), null);
            lampPost(p, room, 7, false);
            lampPost(p, room, 8, false);
            return;
        }
        lampPost(p, room, 20, false);
        int v = room.kind() % 4;
        if (room.start()) v = 2;
        switch (v) {
            case 0 -> {
                bedroll(p, room, 21);
                bedroll(p, room, 22);
                bedroll(p, room, 23);
                put(p, room, 24, Deco.campfire(false, true));
                loot(p, room, 25, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
            }
            case 1 -> {
                loot(p, room, 26, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
                putEdge(p, room, 27, Blocks.BARREL.defaultBlockState());
                putEdge(p, room, 28, Deco.pillar(Blocks.HAY_BLOCK, Direction.Axis.Y));
                loot(p, room, 29, Deco.chest(Direction.SOUTH), Deco.LOOT_COMMON);
            }
            case 2 -> {
                putEdge(p, room, 30, Blocks.CRAFTING_TABLE.defaultBlockState());
                putEdge(p, room, 31, Blocks.SMITHING_TABLE.defaultBlockState());
                put(p, room, 32, Deco.campfire(false, true));
                lampPost(p, room, 33, false);
            }
            default -> {
                putEdge(p, room, 34, Blocks.COBWEB.defaultBlockState());
                mob(p, room, 35, Mobs.skeever(), null);
            }
        }
        mob(p, room, 36, Mobs.bandit(), null);
        if (p.chance(room.cx, room.floor, room.cz, 37, 2)) mob(p, room, 38, Mobs.bandit(), null);
    }

    // ------------------------------------------------------------------ animal den

    static void den(Painter p, Room room) {
        p.feature("room:den");
        for (int i = 0; i < 6; i++) {
            int[] s = spot(p, room, 40 + i);
            if (s == null) continue;
            int roll = p.roll(s[0], i, s[1], 108, 4);
            BlockState st = roll == 0 ? Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X)
                    : roll == 1 ? Deco.skull(Blocks.SKELETON_SKULL, p.roll(s[0], 0, s[1], 109, 16))
                    : roll == 2 ? Blocks.MOSS_CARPET.defaultBlockState() : Blocks.COBWEB.defaultBlockState();
            p.set(s[0], room.floor, s[1], st);
        }
        if (room.boss()) {
            boss(p, room, 50, Mobs.denBoss(), null);
            mob(p, room, 51, Mobs.denBeast(0), null);
            loot(p, room, 52, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            put(p, room, 53, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Y));
            return;
        }
        int beasts = 1 + p.roll(room.cx, room.floor, room.cz, 54, 3);
        for (int i = 0; i < beasts; i++) mob(p, room, 55 + i, Mobs.denBeast(p.roll(room.cx, i, room.cz, 56, 4)), null);
        if (room.start()) loot(p, room, 57, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
    }

    // ------------------------------------------------------------------ vampire lair

    static void vampire(Painter p, Room room) {
        p.feature("room:vampire");
        // blood pools sunk into the floor
        int[] pool = spot(p, room, 60);
        if (pool != null) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = pool[0] + dx, z = pool[1] + dz;
                    if (Math.abs(dx) + Math.abs(dz) == 2 && p.chance(x, 0, z, 61, 2)) continue;
                    if (room.onLane(x, z) || !inside(p, room, x, z, 0.8)) continue;
                    p.set(x, room.floor - 1, z, dx == 0 && dz == 0 ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.RED_CONCRETE.defaultBlockState());
                }
            }
        }
        // coffins
        int coffins = room.boss() ? 1 : 1 + p.roll(room.cx, 0, room.cz, 62, 2);
        for (int i = 0; i < coffins; i++) {
            int[] s = spot(p, room, 63 + i);
            if (s == null) continue;
            boolean alongX = p.chance(s[0], i, s[1], 64, 2);
            int x2 = s[0] + (alongX ? 1 : 0), z2 = s[1] + (alongX ? 0 : 1);
            if (room.onLane(x2, z2) || !inside(p, room, x2, z2, 0.8)) continue;
            BlockState box = Deco.pillar(Blocks.STRIPPED_DARK_OAK_LOG, alongX ? Direction.Axis.X : Direction.Axis.Z);
            p.set(s[0], room.floor, s[1], box);
            p.set(x2, room.floor, z2, box);
            p.set(s[0], room.floor + 1, s[1], Deco.candle(Blocks.RED_CANDLE, 1, true));
        }
        for (int i = 0; i < 3; i++) put(p, room, 70 + i, Deco.candle(Blocks.RED_CANDLE, 2 + i % 3, true));
        putEdge(p, room, 74, Deco.skull(Blocks.SKELETON_SKULL, 0));
        if (room.boss()) {
            int[] s = edgeSpot(p, room, 75);
            if (s != null) {
                Direction face = faceCenter(room, s[0], s[1]);
                p.set(s[0], room.floor, s[1], Deco.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, face.getOpposite(), false));
                p.set(s[0] - face.getStepX(), room.floor, s[1] - face.getStepZ(), Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                p.set(s[0] - face.getStepX(), room.floor + 1, s[1] - face.getStepZ(), Deco.lantern(true, false));
            }
            loot(p, room, 76, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            // the lair's master: a Vampire Lord, with its court, a hound and a stone guardian (curse module)
            boss(p, room, 77, Mobs.vampireLord(), Painter.entityExists("skycraft:vampire_lord") ? null : "Vampire Lord");
            mob(p, room, 78, Mobs.vampire(), null);
            mob(p, room, 79, Mobs.deathHound(), null);
            mob(p, room, 84, Mobs.gargoyle(), null);
            return;
        }
        mob(p, room, 80, Mobs.vampire(), null);
        if (p.chance(room.cx, 0, room.cz, 81, 2)) mob(p, room, 82, Mobs.deathHound(), null);
        if (room.start()) lampPost(p, room, 83, true);
    }

    // ------------------------------------------------------------------ necromancer lair

    static void necromancer(Painter p, Room room) {
        p.feature("room:necromancer");
        lampPost(p, room, 90, true);
        // a ritual altar
        int[] a = spot(p, room, 91);
        if (a != null) {
            p.set(a[0], room.floor, a[1], Blocks.CRYING_OBSIDIAN.defaultBlockState());
            p.set(a[0], room.floor + 1, a[1], Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int x = a[0] + d.getStepX(), z = a[1] + d.getStepZ();
                if (!room.onLane(x, z) && inside(p, room, x, z, 0.85)) p.set(x, room.floor, z, Deco.candle(Blocks.PURPLE_CANDLE, 1 + Math.floorMod(x + z, 3), true));
            }
        }
        putEdge(p, room, 92, Deco.skull(Blocks.SKELETON_SKULL, 4));
        putEdge(p, room, 93, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Y));
        put(p, room, 94, Deco.candle(Blocks.BLACK_CANDLE, 3, true));
        if (room.boss()) {
            int[] s = edgeSpot(p, room, 95);
            if (s != null) {
                p.set(s[0], room.floor, s[1], Blocks.ENCHANTING_TABLE.defaultBlockState());
                p.set(s[0], room.floor + 1, s[1], Deco.candle(Blocks.PURPLE_CANDLE, 4, true));
            }
            loot(p, room, 96, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            loot(p, room, 97, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
            boss(p, room, 98, Mobs.necromancer(), "Master Necromancer");
            mob(p, room, 110, "minecraft:skeleton", null);
            mob(p, room, 111, "minecraft:skeleton", null);
            mob(p, room, 112, "minecraft:zombie", "Thrall");
            return;
        }
        int roll = p.roll(room.cx, room.floor, room.cz, 113, 4);
        if (roll == 0) mob(p, room, 114, Mobs.necromancer(), Painter.entityExists("skycraft:necromancer") ? null : "Necromancer");
        mob(p, room, 115, roll == 1 ? "minecraft:zombie" : "minecraft:skeleton", roll == 1 ? "Thrall" : null);
        if (room.piece.has(DungeonPiece.FLAG_DEAD_END)) loot(p, room, 116, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
    }

    // ------------------------------------------------------------------ hagraven lair

    static void hagraven(Painter p, Room room) {
        p.feature("room:hagraven");
        // nests of hay, roots and feathers
        for (int i = 0; i < 2; i++) {
            int[] s = edgeSpot(p, room, 120 + i);
            if (s == null) continue;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = s[0] + dx, z = s[1] + dz;
                    if (room.onLane(x, z) || !inside(p, room, x, z, 0.9)) continue;
                    int roll = p.roll(x, i, z, 121, 4);
                    p.set(x, room.floor, z, roll == 0 ? Deco.pillar(Blocks.HAY_BLOCK, Direction.Axis.Y)
                            : roll == 1 ? Blocks.MANGROVE_ROOTS.defaultBlockState()
                            : roll == 2 ? Blocks.WHITE_CARPET.defaultBlockState() : Blocks.MUD.defaultBlockState());
                }
            }
        }
        putEdge(p, room, 122, Blocks.CAULDRON.defaultBlockState());
        putEdge(p, room, 123, Blocks.BREWING_STAND.defaultBlockState());
        put(p, room, 124, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Z));
        put(p, room, 125, Deco.candle(Blocks.BROWN_CANDLE, 3, true));
        lampPost(p, room, 126, false);
        if (room.boss()) {
            loot(p, room, 127, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            loot(p, room, 128, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
            boss(p, room, 129, Mobs.hagraven(), Painter.entityExists("skycraft:hagraven") ? null : "Hagraven Matriarch");
            mob(p, room, 130, Mobs.forsworn(), "Forsworn Briarheart");
            mob(p, room, 131, Mobs.forsworn(), Painter.entityExists("skycraft:forsworn") ? null : "Forsworn");
            return;
        }
        int roll = p.roll(room.cx, room.floor, room.cz, 132, 3);
        if (roll == 0) mob(p, room, 133, Mobs.hagraven(), Painter.entityExists("skycraft:hagraven") ? null : "Hagraven");
        else mob(p, room, 134, Mobs.forsworn(), Painter.entityExists("skycraft:forsworn") ? null : "Forsworn");
        if (room.piece.has(DungeonPiece.FLAG_DEAD_END)) loot(p, room, 135, Deco.barrel(Direction.UP), Deco.LOOT_ALCHEMY);
    }

    // ------------------------------------------------------------------ mine

    static void mine(Painter p, Room room, int flavor) {
        p.feature("room:mine");
        // timber frames at the corners of the gallery
        for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
            int x = room.cx + c[0] * Math.max(2, room.halfX() - 2), z = room.cz + c[1] * Math.max(2, room.halfZ() - 2);
            if (room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
            for (int y = room.floor; y < Math.min(room.floor + 4, room.maxY); y++) p.set(x, y, z, Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y));
        }
        // rails crossing the gallery between doorways
        boolean ew = room.hasDoor(Direction.EAST) || room.hasDoor(Direction.WEST);
        boolean ns = room.hasDoor(Direction.NORTH) || room.hasDoor(Direction.SOUTH);
        if (ew) {
            int x0 = room.hasDoor(Direction.WEST) ? room.minX : room.cx, x1 = room.hasDoor(Direction.EAST) ? room.maxX : room.cx;
            for (int x = x0; x <= x1; x++) {
                if (ns && x == room.cx) continue;
                p.set(x, room.floor, room.cz, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST));
            }
        }
        if (ns) {
            int z0 = room.hasDoor(Direction.NORTH) ? room.minZ : room.cz, z1 = room.hasDoor(Direction.SOUTH) ? room.maxZ : room.cz;
            for (int z = z0; z <= z1; z++) p.set(room.cx, room.floor, z, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH));
        }
        lampPost(p, room, 140, false);
        putEdge(p, room, 141, p.chance(room.cx, 0, room.cz, 142, 2) ? Blocks.RAW_IRON_BLOCK.defaultBlockState() : Blocks.COAL_BLOCK.defaultBlockState());
        loot(p, room, 143, Deco.barrel(Direction.UP), Deco.LOOT_MINE);
        if (p.chance(room.cx, room.floor, room.cz, 144, 3) || room.start()) {
            int[] s = spot(p, room, 145);
            if (s != null) {
                p.set(s[0], room.floor, s[1], Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH));
                p.chestMinecart(s[0], room.floor, s[1], Deco.LOOT_MINE);
            }
        }
        if (room.start()) {
            putEdge(p, room, 146, Blocks.CRAFTING_TABLE.defaultBlockState());
            putEdge(p, room, 147, Blocks.GRINDSTONE.defaultBlockState());
        }
        boolean bandits = flavor % 2 == 1;
        if (room.piece.special == Special.KOLSKEGGR) {
            // the Forsworn hold Kolskeggr's gold
            putEdge(p, room, 170, Blocks.RAW_GOLD_BLOCK.defaultBlockState());
            putEdge(p, room, 171, Blocks.GOLD_ORE.defaultBlockState());
            if (room.boss()) {
                loot(p, room, 172, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
                loot(p, room, 173, Deco.barrel(Direction.UP), Deco.LOOT_MINE);
                mob(p, room, 174, Mobs.forsworn(), null);
                mob(p, room, 175, Mobs.forsworn(), null);
                boss(p, room, 176, Mobs.hagraven(), null);
            } else {
                mob(p, room, 177, Mobs.forsworn(), null);
            }
            return;
        }
        if (room.boss()) {
            loot(p, room, 148, Deco.chest(Direction.SOUTH), Deco.LOOT_BOSS);
            loot(p, room, 149, Deco.barrel(Direction.UP), Deco.LOOT_MINE);
            if (bandits) {
                boss(p, room, 150, Mobs.banditChief(), null);
                mob(p, room, 151, Mobs.bandit(), null);
            } else {
                boss(p, room, 152, Mobs.denBoss(), null);
                mob(p, room, 153, Mobs.skeever(), null);
                mob(p, room, 154, Mobs.skeever(), null);
            }
            return;
        }
        if (bandits) {
            mob(p, room, 155, Mobs.bandit(), null);
            if (room.kind() % 3 == 0) bedroll(p, room, 156);
        } else {
            int n = 1 + p.roll(room.cx, 0, room.cz, 157, 3);
            for (int i = 0; i < n; i++) mob(p, room, 158 + i, Mobs.skeever(), null);
        }
    }
}
