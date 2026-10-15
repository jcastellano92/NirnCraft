package com.skycraft.dungeons.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.lore.place.PlaceLore;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What makes a named dungeon this dungeon and not a generic one. The data ({@code skycraft/dungeons/identity/*.json},
 * written by {@code tools/qa/dungeon_identity_gen.py} from the place lore) gives each place:
 * <ul>
 *     <li>its master's name (a chief's own name where the lore has one, otherwise an original one) and the person its
 *     story is about;</li>
 *     <li>the notes of its lore that stand where they will be seen: the first on a lectern at the entrance, with a
 *     sign naming the place and the person; the boss's own on a lectern in the boss chamber beside the master's hoard;
 *     a third in the room of the dead adventurer;</li>
 *     <li>a set-piece motif (a {@code blueprints/motif_*.json} furnishing in the set-piece room) and a family of
 *     loot ({@code chests/story_<family>}) that half of the ordinary chests hold.</li>
 * </ul>
 * Every decision is column-local or geometry-only, so each chunk of a piece agrees with the others.
 */
public final class Identity {
    private Identity() {}

    /** One place's identity. {@code sign} has four lines of at most 14 characters. */
    public record Entry(String id, String family, String boss, String person, List<String> sign, String motif, ResourceLocation loot,
                        String entrance, String entranceKind, String bossNote, String story) {
    }

    private static volatile Map<String, Entry> entries;

    @Nullable
    public static Entry of(String place) {
        if (place == null || place.isEmpty()) return null;
        return table().get(place);
    }

    /** Every entry (for tools and tests). */
    public static Map<String, Entry> all() {
        return table();
    }

    private static Map<String, Entry> table() {
        Map<String, Entry> t = entries;
        if (t == null) {
            synchronized (Identity.class) {
                t = entries;
                if (t == null) entries = t = load();
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ names and loot

    /** The name of the master of this place, or null (random sites and places without identity). */
    @Nullable
    public static String bossName(String place) {
        Entry e = of(place);
        return e == null || e.boss().isEmpty() ? null : e.boss();
    }

    /** The name of the master of this place, or {@code fallback} when the place has no identity boss name. */
    @Nullable
    public static String bossName(String place, @Nullable String fallback) {
        String n = bossName(place);
        return n != null ? n : fallback;
    }

    /** The chest table a story chest rolls in this place instead of an ordinary dungeon table, or the table itself. */
    public static ResourceLocation loot(String place, ResourceLocation table, long roll) {
        Entry e = of(place);
        if (e == null) return table;
        String path = table.getPath();
        boolean ordinary = path.equals("chests/dungeon_common") || path.equals("chests/dungeon_minor")
                || path.equals("containers/dungeon/standard") || path.equals("containers/dungeon/minor");
        return ordinary && (roll & 1L) == 0L ? e.loot() : table;
    }

    /** The side branch's strongbox: the place's own loot two times in three. */
    public static ResourceLocation cacheLoot(String place, ResourceLocation table, int roll) {
        Entry e = of(place);
        return e == null || roll == 0 ? table : e.loot();
    }

    // ------------------------------------------------------------------ the lore's notes as books

    @Nullable
    private static ItemStack note(String place, String noteId) {
        if (noteId == null || noteId.isEmpty()) return null;
        String bookId = PlaceLore.BOOK_PREFIX + place + "." + noteId;
        ItemStack s = PlaceLore.createNote(place, noteId);
        if (s.isEmpty()) {
            PlaceLore.Note n = PlaceLore.note(bookId);
            if (n == null) return null;
            com.skycraft.lore.LoreBooks.Book b = PlaceLore.book(bookId);
            if (b == null) return null;
            s = com.skycraft.lore.LoreBookItem.create(b);
        }
        s.getOrCreateTag().putString(NOTE_TAG, bookId);
        return s;
    }

    /** Tag on the written book of a lectern note: the id of the place's lore note it stands for. */
    public static final String NOTE_TAG = "SkyNote";

    /** The journal of the dead adventurer's room: the place's third note, or null for the generic journal. */
    @Nullable
    static ItemStack storyBook(String place) {
        Entry e = of(place);
        return e == null ? null : note(place, e.story());
    }

    /** The person the adventurer's bones are named for, or null (a generated name is used). */
    @Nullable
    static String storyPerson(String place) {
        Entry e = of(place);
        if (e == null || e.person().isEmpty()) return null;
        return e.entranceKind().equals("journal") || e.entranceKind().equals("diary") ? e.person() : null;
    }

    // ------------------------------------------------------------------ the entrance

    /** Sign and lectern in the forecourt of the entrance (the portal piece). */
    static void entrance(Painter p, DungeonPiece c) {
        Entry e = of(c.place);
        if (e == null || c.special == Special.MARQUEE) return;
        int t = c.tunnelLength() + 3;
        marker(p, e, c.frameX(t, 3), c.frameZ(t, 3), c.frameX(t, -3), c.frameZ(t, -3), c.aux, c.facing(), false);
    }

    /** The same for surface sites (forts, camps, towers): at the front edge of the footprint. */
    static void surfaceMarker(Painter p, DungeonPiece c) {
        Entry e = of(c.place);
        if (e == null || c.special != Special.NONE || c.variant != 0 || c.doors != 0 || c.extra != 0 || c.role != DungeonPiece.ROLE_NORMAL) return;
        if (c.theme.underground() && c.theme != Theme.FORT) return;
        var b = c.getBoundingBox();
        int cx = (b.minX() + b.maxX()) / 2, cz = (b.minZ() + b.maxZ()) / 2;
        Direction f = c.facing(), r = f.getClockWise();
        int fwd = Math.min(b.getXSpan(), b.getZSpan()) / 2 - 1;
        int fx = cx + f.getStepX() * fwd, fz = cz + f.getStepZ() * fwd;
        int baseY = c.theme == Theme.SHIPWRECK ? Math.max(c.floor + 2, p.level.getSeaLevel()) : c.floor;
        BlockState padFloor = c.theme == Theme.SHIPWRECK || c.theme == Theme.CABIN
                ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        marker(p, e, fx + r.getStepX() * 3, fz + r.getStepZ() * 3, fx - r.getStepX() * 3, fz - r.getStepZ() * 3, baseY, f, true);
        // its stash: a chest of the place's own loot by the way in
        int sx = fx - r.getStepX() * 6, sz = fz - r.getStepZ() * 6, sy = standY(p, sx, sz, baseY);
        if (sy == Integer.MIN_VALUE) sy = ensureStand(p, sx, sz, baseY, padFloor);
        if (sy != Integer.MIN_VALUE) {
            p.container(sx, sy, sz, Deco.chest(f), e.loot());
            p.feature("story:hoard");
        }
        // its motif, laid out on open ground beside the way in
        int off = Math.max(3, Math.min(6, fwd - 3));
        int mx = fx + r.getStepX() * off - f.getStepX() * 2, mz = fz + r.getStepZ() * off - f.getStepZ() * 2;
        ensureMotifPad(p, mx, mz, baseY, f.getOpposite(), padFloor);
        Blueprints.placeAt(p, "motif:" + e.motif(), mx, mz, baseY, f.getOpposite());
        // and its master, waiting inside (forts keep theirs in the cellars)
        String boss = c.theme == Theme.FORT ? null : surfaceBoss(c.theme, e.family());
        if (boss != null) {
            int bx = fx - f.getStepX() * 5, bz = fz - f.getStepZ() * 5;
            int by = standY(p, bx, bz, baseY);
            if (by == Integer.MIN_VALUE) by = ensureStand(p, bx, bz, baseY, padFloor);
            if (by != Integer.MIN_VALUE) p.spawnBoss(boss, bx, by, bz, Painter.yaw(f), bossName(c.place));
        }
    }

    @Nullable
    private static String surfaceBoss(Theme theme, String family) {
        if (theme == Theme.GREAT_LIFT) return Mobs.DWARVEN_SPHERE;
        return switch (family) {
            case "barrow", "ruin" -> Mobs.deathlord();
            case "bandit", "shipwreck" -> Mobs.banditChief();
            case "military" -> Mobs.soldier();
            case "forsworn", "hagraven" -> Mobs.forsworn();
            case "giant" -> Mobs.orElse(Mobs.GIANT, "minecraft:iron_golem");
            case "grove" -> Mobs.spriggan();
            case "dwemer" -> Mobs.DWARVEN_CENTURION;
            case "mage", "necromancer" -> Mobs.necromancer();
            case "vampire" -> Mobs.vampireLord();
            default -> null;
        };
    }

    private static void marker(Painter p, Entry e, int sx, int sz, int lx, int lz, int hint, Direction out, boolean forcePad) {
        BlockState padFloor = Blocks.SPRUCE_PLANKS.defaultBlockState();
        int sy = standY(p, sx, sz, hint);
        // the post is a plain column: where it cannot stand, a spot a block further out of the same chunk takes over
        if (sy == Integer.MIN_VALUE && p.ownsColumn(sx, sz)) {
            for (int k = 1; k <= 3 && sy == Integer.MIN_VALUE; k++) {
                int ax = sx + out.getStepX() * k, az = sz + out.getStepZ() * k;
                sy = standY(p, ax, az, hint);
                if (sy != Integer.MIN_VALUE) {
                    sx = ax;
                    sz = az;
                }
            }
            if (sy == Integer.MIN_VALUE && forcePad) sy = ensureStand(p, sx, sz, hint, padFloor);
        }
        if (sy != Integer.MIN_VALUE) {
            int rotation = Math.floorMod(Math.round(out.toYRot() / 22.5f), 16);
            p.set(sx, sy, sz, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation));
            if (p.blockEntity(sx, sy, sz) instanceof SignBlockEntity sign) {
                SignText text = new SignText();
                for (int i = 0; i < 4 && i < e.sign().size(); i++) text = text.setMessage(i, Component.literal(e.sign().get(i)));
                // worldgen block entities have no level yet: load the text through NBT (setText would notify the level)
                net.minecraft.nbt.CompoundTag tag = sign.saveWithoutMetadata();
                SignText.DIRECT_CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, text).result().ifPresent(t -> tag.put("front_text", t));
                tag.putBoolean("is_waxed", true);
                sign.load(tag);
            } else {
                p.set(sx, sy, sz, Blocks.AIR.defaultBlockState());
            }
            p.feature("story:sign");
        }
        ItemStack book = note(e.id(), e.entrance());
        int ly = standY(p, lx, lz, hint);
        if (ly == Integer.MIN_VALUE && p.ownsColumn(lx, lz)) {
            for (int k = 1; k <= 3 && ly == Integer.MIN_VALUE; k++) {
                int ax = lx + out.getStepX() * k, az = lz + out.getStepZ() * k;
                ly = standY(p, ax, az, hint);
                if (ly != Integer.MIN_VALUE) {
                    lx = ax;
                    lz = az;
                }
            }
            if (ly == Integer.MIN_VALUE && forcePad) ly = ensureStand(p, lx, lz, hint, padFloor);
        }
        if (book != null && ly != Integer.MIN_VALUE) {
            p.set(lx, ly, lz, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, out).setValue(LecternBlock.HAS_BOOK, true));
            if (p.blockEntity(lx, ly, lz) instanceof LecternBlockEntity lectern) {
                lectern.setBook(book);
                lectern.setChanged();
                p.feature("story:entrance_lectern");
            } else {
                p.set(lx, ly, lz, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static int ensureStand(Painter p, int x, int z, int y, BlockState floor) {
        if (!p.ownsColumn(x, z) || !p.ok(x, y, z) || !p.ok(x, y + 1, z)) return Integer.MIN_VALUE;
        BlockState below = p.get(x, y - 1, z);
        if (below.isAir() || !below.getFluidState().isEmpty() || below.is(BlockTags.LEAVES) || !below.canOcclude()) {
            p.set(x, y - 1, z, floor);
        }
        for (int dy = 0; dy <= 1; dy++) {
            if (!free(p.get(x, y + dy, z)) && p.blockEntity(x, y + dy, z) == null) {
                p.set(x, y + dy, z, Blocks.AIR.defaultBlockState());
            }
        }
        return y;
    }

    private static void ensureMotifPad(Painter p, int cx, int cz, int floor, Direction face, BlockState padFloor) {
        Direction right = face.getClockWise();
        for (int v = 0; v < 5; v++) {
            for (int u = -2; u <= 2; u++) {
                int x = cx + right.getStepX() * u + face.getStepX() * v;
                int z = cz + right.getStepZ() * u + face.getStepZ() * v;
                ensureStand(p, x, z, floor, padFloor);
            }
        }
    }

    /** A free standing spot of this column near the hint level: solid and dry below, two blocks of air, or MIN_VALUE. */
    private static int standY(Painter p, int x, int z, int hint) {
        if (!p.ownsColumn(x, z)) return Integer.MIN_VALUE;
        int g = p.surfaceAt(x, z);
        int[] tries = {hint, g, hint + 1, hint - 1, hint + 2, g + 1, hint - 2};
        for (int y : tries) {
            if (y == Integer.MIN_VALUE || !p.ok(x, y, z) || !p.ok(x, y + 1, z)) continue;
            BlockState below = p.get(x, y - 1, z);
            if (below.isAir() || !below.getFluidState().isEmpty() || below.is(BlockTags.LEAVES) || !below.canOcclude()) continue;
            if (!free(p.get(x, y, z)) || !free(p.get(x, y + 1, z))) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean free(BlockState s) {
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------ the boss chamber and the set piece

    /** The master's chamber: the boss's own note on a lectern, and the hoard (the place's loot) in a chest of its own. */
    static void bossRoom(Painter p, Room room) {
        Entry e = of(p.place);
        if (e == null) return;
        int[] s = Story.spot(p, room, 8);
        ItemStack book = note(e.id(), e.bossNote());
        if (s != null && book != null) {
            Direction face = BarrowRooms.towardCenter(room, s[0], s[1]);
            p.set(s[0], room.floor, s[1], Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, face).setValue(LecternBlock.HAS_BOOK, true));
            if (p.blockEntity(s[0], room.floor, s[1]) instanceof LecternBlockEntity lectern) {
                lectern.setBook(book);
                lectern.setChanged();
                p.feature("story:boss_lectern");
            } else {
                p.set(s[0], room.floor, s[1], Blocks.AIR.defaultBlockState());
            }
        }
        int[] h = Story.spot(p, room, 9);
        if (h != null) {
            Direction face = BarrowRooms.towardCenter(room, h[0], h[1]);
            p.container(h[0], room.floor, h[1], Deco.chest(face), e.loot());
            int cx = h[0] + face.getClockWise().getStepX(), cz = h[1] + face.getClockWise().getStepZ();
            if (room.interior(cx, cz) && !room.onLane(cx, cz) && !room.nearDoor(cx, cz, 0)) {
                p.set(cx, room.floor, cz, Deco.candle(Blocks.CANDLE, 3, true));
            }
            p.feature("story:hoard");
        }
        // the chamber wears the place's motif too (the set-piece room has it, and so do dungeons too small for one)
        if (!e.motif().isEmpty()) Blueprints.place(p, room, "motif:" + e.motif());
    }

    /** The place's set-piece motif, furnished against the far wall of the set-piece room. */
    static boolean motif(Painter p, Room room) {
        Entry e = of(p.place);
        return e != null && !e.motif().isEmpty() && Blueprints.place(p, room, "motif:" + e.motif());
    }

    // ------------------------------------------------------------------ loading

    private static Map<String, Entry> load() {
        Map<String, Entry> out = new HashMap<>();
        JsonElement index = read("/skycraft/dungeons/identity/index.json");
        if (index == null || !index.isJsonArray()) return out;
        for (JsonElement f : index.getAsJsonArray()) {
            JsonElement root = read("/skycraft/dungeons/identity/" + f.getAsString());
            if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has("places")) continue;
            for (Map.Entry<String, JsonElement> pe : root.getAsJsonObject().getAsJsonObject("places").entrySet()) {
                JsonObject o = pe.getValue().getAsJsonObject();
                List<String> sign = new java.util.ArrayList<>();
                if (o.has("sign")) for (JsonElement l : o.getAsJsonArray("sign")) sign.add(l.getAsString());
                String loot = str(o, "loot");
                out.put(pe.getKey(), new Entry(pe.getKey(), str(o, "family"), str(o, "boss"), str(o, "person"), List.copyOf(sign),
                        str(o, "motif"), new ResourceLocation(Skycraft.MODID, "chests/story_" + (loot.isEmpty() ? "cave" : loot)),
                        str(o, "entrance"), str(o, "entranceKind"), str(o, "bossNote"), str(o, "story")));
            }
        }
        return Map.copyOf(out);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    @Nullable
    private static JsonElement read(String path) {
        try (InputStream in = Identity.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            Skycraft.LOGGER.warn("Skycraft dungeons: could not read {}", path, ex);
            return null;
        }
    }
}
