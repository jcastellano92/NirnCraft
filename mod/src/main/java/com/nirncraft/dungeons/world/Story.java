package com.nirncraft.dungeons.world;

import com.nirncraft.NirnCraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Environmental storytelling and the side branch's reward. The planner marks one room of every medium dungeon for
 * each ({@link DungeonPiece#FLAG_STORY}, {@link DungeonPiece#FLAG_CACHE}):
 * <ul>
 *     <li>the story: an adventurer who got this far, their bones, pack and lantern, and their journal (original
 *     text, one of several per kind of dungeon: {@code book.nirncraft.journal.<theme>.<n>});</li>
 *     <li>the cache: a strongbox someone hid down the side passage, with better odds than the common chests.</li>
 * </ul>
 */
public final class Story {
    public static final Identifier LOOT_CACHE = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/dungeon_cache");

    /** Journal texts per theme (keys in the dungeons lang file). */
    private static final int BARROW = 5, DWEMER = 3, CAVE = 4, MINE = 2, LAIR = 1, FORT = 2, RUIN = 2;

    private Story() {}

    /** Adds the room's story and cache, if the planner gave it one. */
    public static void dress(Painter p, Room room) {
        if (room.boss()) {
            // a named place's master keeps its own note and hoard in the chamber
            Identity.bossRoom(p, room);
            return;
        }
        if (room.start()) return;
        if (room.has(DungeonPiece.FLAG_STORY)) adventurer(p, room);
        if (room.has(DungeonPiece.FLAG_CACHE)) cache(p, room);
    }

    // ------------------------------------------------------------------ spots

    /** A free floor spot against the walls (built: an inner corner; natural: the cavern's edge), or null. */
    @Nullable
    static int[] spot(Painter p, Room room, int salt) {
        if (room.theme().natural()) {
            int[] s = NaturalRooms.edgeSpot(p, room, salt);
            return s != null && !room.inVista(s[0], s[1]) ? s : null;
        }
        int first = p.roll(room.cx, salt, room.cz, 610, 4);
        for (int inset = 1; inset <= 2; inset++) {
            int[][] corners = {
                    {room.minX + inset, room.minZ + inset + 1}, {room.maxX - inset - 1, room.minZ + inset},
                    {room.minX + inset + 1, room.maxZ - inset}, {room.maxX - inset, room.maxZ - inset - 1}};
            for (int i = 0; i < 4; i++) {
                int[] c = corners[(first + i) % 4];
                // geometry only (never what other chunks painted), so every chunk agrees
                if (!room.interior(c[0], c[1]) || room.nearDoor(c[0], c[1], 1) || room.onLane(c[0], c[1])) continue;
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the dead adventurer

    static void adventurer(Painter p, Room room) {
        int[] s = spot(p, room, 1);
        if (s == null) return;
        Direction face = BarrowRooms.towardCenter(room, s[0], s[1]);
        int x = s[0], z = s[1], y = room.floor;
        // the body: bones slumped against the wall, the skull fallen beside them
        p.set(x, y, z, Deco.pillar(Blocks.BONE_BLOCK, face.getAxis()));
        int sx = x + face.getClockWise().getStepX(), sz = z + face.getClockWise().getStepZ();
        if (room.interior(sx, sz) && !room.onLane(sx, sz) && !room.nearDoor(sx, sz, 0)) {
            p.set(sx, y, sz, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(face) + 2));
        }
        // the journal on a reading lectern in front of the body, where it can be seen from across the room
        int jx = x + face.getStepX(), jz = z + face.getStepZ();
        boolean onLectern = false;
        if (room.interior(jx, jz) && !room.onLane(jx, jz) && !room.nearDoor(jx, jz, 0)
                && (!room.theme().natural() || NaturalRooms.inside(p, room, jx, jz, 0.85)) && p.ok(jx, y, jz)) {
            p.set(jx, y, jz, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, face).setValue(LecternBlock.HAS_BOOK, true));
            if (p.blockEntity(jx, y, jz) instanceof LecternBlockEntity lectern) {
                lectern.setBook(journal(p, room));
                onLectern = true;
            } else {
                p.set(jx, y, jz, Blocks.AIR.defaultBlockState());
            }
        }
        // the pack, with the journal in it when there is no lectern, and the lantern that went out
        int bx = x + face.getCounterClockWise().getStepX(), bz = z + face.getCounterClockWise().getStepZ();
        if (!room.interior(bx, bz) || room.onLane(bx, bz) || room.nearDoor(bx, bz, 0)) {
            bx = x + face.getStepX();
            bz = z + face.getStepZ();
            if (onLectern) bx = -1;
        }
        if (bx != -1 && room.interior(bx, bz) && !room.onLane(bx, bz)) {
            // no loot table: filling a container that still has one would unpack it, which needs a live level
            p.set(bx, y, bz, Deco.barrel(face));
            if (p.blockEntity(bx, y, bz) instanceof RandomizableContainerBlockEntity be) {
                // the spot may have held a loot barrel already: drop its table, filling it would unpack it
                be.setLootTable(null, 0L);
                if (!onLectern) be.setItem(13, journal(p, room));
                be.setItem(11, new ItemStack(Blocks.TORCH, 2 + p.roll(bx, y, bz, 612, 4)));
                net.minecraft.world.item.Item coin = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(NirnCraft.MODID, "septim"));
                if (coin != null && coin != net.minecraft.world.item.Items.AIR) be.setItem(15, new ItemStack(coin, 5 + p.roll(bx, y, bz, 613, 20)));
            }
            int lx = bx + face.getStepX(), lz = bz + face.getStepZ();
            if (room.interior(lx, lz) && !room.onLane(lx, lz) && !room.nearDoor(lx, lz, 0)) p.set(lx, y, lz, Deco.lantern(false, false));
        }
        p.feature("story:journal");
    }

    static ItemStack journal(Painter p, Room room) {
        // a named place's own note (written about someone in its lore) lies here when its lore left one for this room
        ItemStack lore = Identity.storyBook(p.place);
        if (lore != null) return lore;
        String key = switch (room.theme()) {
            case BARROW -> "barrow." + p.roll(room.cx, 1, room.cz, 611, BARROW);
            case DWEMER -> "dwemer." + p.roll(room.cx, 1, room.cz, 611, DWEMER);
            case MINE -> "mine." + p.roll(room.cx, 1, room.cz, 611, MINE);
            case FORT -> "fort." + p.roll(room.cx, 1, room.cz, 611, FORT);
            case VAMPIRE_LAIR, NECROMANCER_LAIR, HAGRAVEN_LAIR -> room.theme().id + "." + p.roll(room.cx, 1, room.cz, 611, LAIR);
            case RUIN -> room.piece.kit == Kit.ATMORAN || room.piece.kit == Kit.NORD
                    ? "barrow." + p.roll(room.cx, 1, room.cz, 611, BARROW) : "ruin." + p.roll(room.cx, 1, room.cz, 611, RUIN);
            default -> "cave." + p.roll(room.cx, 1, room.cz, 611, CAVE);
        };
        String named = Identity.storyPerson(p.place);
        String who = named != null ? named : adventurer(p, room, 0);
        String what = adventurer(p, room, 1);
        return Exteriors.book("Journal of " + who, List.of(
                Component.literal(who + "\n" + what + "\n\nFound with the remains of its owner."),
                Component.translatable("book.nirncraft.journal." + key)));
    }

    private static final String[] FIRST = {"Aldric", "Berren", "Cassia", "Dunwald", "Edda", "Fenwick", "Garrick", "Halla",
            "Isolde", "Jorund", "Kestrel", "Liora", "Marek", "Nessa", "Orsin", "Petra", "Quillon", "Rolf", "Sigrun", "Tamsin",
            "Varek", "Wynne", "Yorick", "Zora"};
    private static final String[] LAST = {"Ashmere", "Blackbriar", "Coldwater", "Dunmoor", "Elmstead", "Frostholm", "Greywell",
            "Hollowmere", "Ironvale", "Kettleburn", "Longstride", "Mossgrove", "Northcote", "Pinecrest", "Quickwater",
            "Ravenhollow", "Stonehand", "Thornfield", "Varnell", "Whitlock", "Yarrow", "Brightwater", "Cairnwood", "Dunhallow"};
    private static final String[] TRADE = {"a sellsword out of work", "a prospector and part-time cartographer", "a pilgrim",
            "a hedge-mage who read too far", "a bounty hunter", "a treasure-hunter with debts", "a scout of the old war",
            "a deserter from the garrison"};

    /** The dead adventurer's name (kind 0) or trade (kind 1): every dungeon has its own. */
    private static String adventurer(Painter p, Room room, int kind) {
        if (kind == 0) {
            return FIRST[p.roll(room.cx, 21, room.cz, 620, FIRST.length)] + " " + LAST[p.roll(room.cx, 22, room.cz, 621, LAST.length)];
        }
        return TRADE[p.roll(room.cx, 23, room.cz, 622, TRADE.length)];
    }

    // ------------------------------------------------------------------ the cache

    /** The side branch's strongbox: a relic table of the dungeon's own kind two times in three, the common cache otherwise. */
    static Identifier relicTable(Theme theme, int roll) {
        if (roll == 0) return LOOT_CACHE;
        String id = switch (theme) {
            case MINE -> "mine";
            case VAMPIRE_LAIR, NECROMANCER_LAIR, HAGRAVEN_LAIR -> "lair";
            case BARROW -> "barrow";
            case DWEMER -> "dwemer";
            case RUIN, FORT -> "ruin";
            default -> "cave";
        };
        return Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/relic_" + id);
    }

    static void cache(Painter p, Room room) {
        Blueprints.place(p, room, "cache");
        int[] s = spot(p, room, 2);
        if (s == null) return;
        Direction face = BarrowRooms.towardCenter(room, s[0], s[1]);
        int roll = p.roll(room.cx, 5, room.cz, 640, 3);
        p.container(s[0], room.floor, s[1], Deco.chest(face), Identity.cacheLoot(p.place, relicTable(room.theme(), roll), roll));
        int cx = s[0] + face.getClockWise().getStepX(), cz = s[1] + face.getClockWise().getStepZ();
        if (room.interior(cx, cz) && !room.onLane(cx, cz) && !room.nearDoor(cx, cz, 0)) {
            p.set(cx, room.floor, cz, Deco.candle(Blocks.CANDLE, 2, true));
        }
        p.feature("reward:cache");
    }
}
