package com.nirncraft.dungeons.world;

import javax.annotation.Nullable;

/**
 * The combat beats the planner puts on a dungeon's main path, for every theme:
 * <ul>
 *     <li>the ambush ({@link DungeonPiece#FLAG_AMBUSH}): foes lying still along the walls, who wake (with the rest of
 *     their kind nearby) as the player crosses the room;</li>
 *     <li>the elite ({@link DungeonPiece#FLAG_ELITE}): a tougher, named guard before the gate, the mini-boss.</li>
 * </ul>
 */
public final class Beats {
    private Beats() {}

    public static void dress(Painter p, Room room) {
        if (room.boss() || room.start()) return;
        if (room.has(DungeonPiece.FLAG_AMBUSH)) ambush(p, room);
        if (room.has(DungeonPiece.FLAG_ELITE)) {
            Blueprints.place(p, room, "elite");
            elite(p, room);
        }
        // a named place's own motif (its name and story) stands against the far wall of the set-piece room
        boolean motif = room.has(DungeonPiece.FLAG_SETPIECE) && Identity.motif(p, room);
        // a second, authored set piece against the far wall of the bigger machine halls and sanctums
        if (!motif && room.has(DungeonPiece.FLAG_SETPIECE) && (room.theme() == Theme.DWEMER || room.theme() == Theme.RUIN)
                && p.chance(room.cx, room.floor, room.cz, 981, 2)) {
            Blueprints.place(p, room, "setpiece");
        }
    }

    /** The common foe of this dungeon, and the title its elite wears. */
    static String foe(Painter p, Room room) {
        return switch (room.theme()) {
            case BARROW -> room.piece.kit == Kit.FORSWORN || p.kit == Kit.FORSWORN ? Mobs.forsworn() : Mobs.draugr();
            case DWEMER -> Mobs.DWARVEN_SPIDER;
            case FORT -> p.kit != Kit.NONE && p.kit != Kit.NORD && p.kit != Kit.IMPERIAL ? p.kit.minion(1).id()
                    : switch (Math.floorMod(room.piece.flavor, 4)) {
                case 1 -> Mobs.forsworn();
                case 2 -> Mobs.vampire();
                case 3 -> Mobs.necromancer();
                default -> Mobs.bandit();
            };
            case RUIN -> (p.kit == Kit.NONE ? Kit.NORD : p.kit).minion(2).id();
            default -> NaturalRooms.commonFoe(room);
        };
    }

    @Nullable
    static String eliteName(Painter p, Room room, String id) {
        if (!id.startsWith("minecraft:") && room.theme() != Theme.RUIN) return switch (room.theme()) {
            case BARROW -> room.piece.kit == Kit.FORSWORN || p.kit == Kit.FORSWORN ? "Forsworn Briarheart" : "Draugr Wight";
            case DWEMER -> null;
            default -> null;
        };
        return switch (room.theme()) {
            case RUIN -> {
                Kit.Spawn s = (p.kit == Kit.NONE ? Kit.NORD : p.kit).minion(2);
                yield (s.name() != null ? s.name() : "Guardian") + " Champion";
            }
            default -> "Veteran";
        };
    }

    static void ambush(Painter p, Room room) {
        String id = foe(p, room);
        int n = 2 + room.tier() / 3;
        int placed = 0;
        for (int i = 0; i < 12 && placed < n; i++) {
            int[] s = spot(p, room, 700 + i);
            if (s == null) continue;
            p.spawnDormant(id, s[0], room.floor, s[1], Painter.yaw(BarrowRooms.towardCenter(room, s[0], s[1])));
            placed++;
        }
        p.feature("beat:ambush");
    }

    static void elite(Painter p, Room room) {
        String id = room.theme() == Theme.DWEMER ? Mobs.DWARVEN_SPHERE : foe(p, room);
        int[] s = room.theme().natural() ? NaturalRooms.spot(p, room, 720) : new int[]{room.cx + 1, room.cz - 1};
        if (s == null) s = new int[]{room.cx + 1, room.cz + 1};
        p.spawnElite(id, s[0], room.floor, s[1], 0f, eliteName(p, room, id));
        p.feature("beat:elite");
    }

    /** A spot along the walls, off the walking lanes and away from the doors. */
    @Nullable
    static int[] spot(Painter p, Room room, int salt) {
        if (room.theme().natural()) return NaturalRooms.edgeSpot(p, room, salt);
        // two blocks in from the walls (alcove rings and wall furniture take the first), never in the corners
        int w = room.maxX - room.minX - 7, d = room.maxZ - room.minZ - 7;
        if (w < 1 || d < 1) return null;
        int side = p.roll(room.cx, salt, room.cz, 701, 4);
        int x, z;
        int a = p.roll(room.cx, salt, room.cz, 702, side < 2 ? w : d);
        switch (side) {
            case 0 -> { x = room.minX + 3 + a; z = room.minZ + 2; }
            case 1 -> { x = room.minX + 3 + a; z = room.maxZ - 2; }
            case 2 -> { x = room.minX + 2; z = room.minZ + 3 + a; }
            default -> { x = room.maxX - 2; z = room.minZ + 3 + a; }
        }
        if (!room.interior(x, z) || room.onLane(x, z) || room.nearDoor(x, z, 1)) return null;
        return new int[]{x, z};
    }
}
