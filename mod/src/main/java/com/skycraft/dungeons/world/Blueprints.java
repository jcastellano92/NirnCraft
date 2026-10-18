package com.skycraft.dungeons.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-authored set-piece furnishings as data ({@code /skycraft/dungeons/blueprints/*.json} on the class path, listed in
 * {@code index.json}): a few layers of character rows and a key, placed against a room's far wall at a mission beat
 * (the elite's post, the side cache, a set piece). Rows run from the wall inwards, columns across; layer 0 stands on
 * the floor. Key values:
 * <ul>
 *     <li>a block state ({@code "minecraft:candle[candles=3,lit=true]"}); {@code "@in"} at the end turns its
 *     {@code facing} towards the room;</li>
 *     <li>{@code "loot:chest:<table>"} / {@code "loot:barrel:<table>"}: a container facing the room;</li>
 *     <li>{@code "foe"} / {@code "foe:dormant"} / {@code "mob:<id>"}: an inhabitant (the dungeon's common foe or an id);</li>
 *     <li>{@code "light"}: the culture's light; {@code "air"}: cleared.</li>
 * </ul>
 * Cells on the walking lanes or by the doors are skipped, so a blueprint never blocks the way.
 */
public final class Blueprints {
    public record Blueprint(String id, List<String> themes, List<String> beats, List<List<String>> layers, Map<Character, String> key) {
        int depth() {
            return layers.isEmpty() ? 0 : layers.get(0).size();
        }

        int width() {
            return layers.isEmpty() || layers.get(0).isEmpty() ? 0 : layers.get(0).get(0).length();
        }
    }

    private static final List<Blueprint> ALL = load();
    private static final Map<String, BlockState> STATES = new HashMap<>();

    private Blueprints() {}

    public static List<Blueprint> all() {
        return ALL;
    }

    /** A blueprint for this theme and beat that fits the room, picked by the room's hash; null if none. */
    @Nullable
    static Blueprint pick(Painter p, Room room, String beat) {
        Direction far = far(room);
        List<Blueprint> fit = new ArrayList<>();
        for (Blueprint b : ALL) {
            if (!b.beats().contains(beat) || !(b.themes().contains(room.theme().id) || b.themes().contains("*"))) continue;
            if (b.depth() > room.half(far) || b.width() > 2 * room.half(far.getClockWise()) - 1) continue;
            fit.add(b);
        }
        return fit.isEmpty() ? null : fit.get(p.roll(room.cx, beat.hashCode(), room.cz, 980, fit.size()));
    }

    /** The wall a blueprint stands against: across from where the room was entered. */
    static Direction far(Room room) {
        Direction e = room.entered();
        return e != null ? e.getOpposite() : Direction.NORTH;
    }

    /** Places the blueprint against the far wall; returns false if nothing fit. */
    static boolean place(Painter p, Room room, String beat) {
        Blueprint b = pick(p, room, beat);
        if (b == null) return false;
        Direction wall = far(room);
        Direction inward = wall.getOpposite();
        Direction right = wall.getClockWise();
        int half = room.half(wall);
        int w = b.width();
        for (int layer = 0; layer < b.layers().size(); layer++) {
            List<String> rows = b.layers().get(layer);
            for (int row = 0; row < rows.size(); row++) {
                String line = rows.get(row);
                for (int col = 0; col < line.length(); col++) {
                    char ch = line.charAt(col);
                    if (ch == ' ' || ch == '.') continue;
                    String v = b.key().get(ch);
                    if (v == null) continue;
                    int fwd = half - 1 - row, lat = col - w / 2;
                    // (the frame's lateral axis runs to the wall's left: mirror so rows read as seen from the room)
                    int x = room.localX(wall, fwd, -lat), z = room.localZ(wall, fwd, -lat);
                    if (!room.interior(x, z) || room.onLane(x, z) || room.nearDoor(x, z, 1)) continue;
                    put(p, room, x, room.floor + layer, z, v, inward);
                }
            }
        }
        p.feature("blueprint:" + b.id());
        return true;
    }

    /**
     * Places the blueprint of this beat on open ground of a surface site: rows run from (ox, oz) along {@code inward},
     * columns across, layer 0 at {@code y}. Cells that are not open air over solid ground are left alone (it never
     * replaces a wall); every decision is per cell, so chunks agree. False if no blueprint has the beat.
     */
    static boolean placeAt(Painter p, String beat, int ox, int oz, int y, Direction inward) {
        Blueprint b = null;
        for (Blueprint c : ALL) if (c.beats().contains(beat)) b = c;
        if (b == null) return false;
        Direction right = inward.getClockWise();
        int w = b.width(), placed = 0;
        for (int layer = 0; layer < b.layers().size(); layer++) {
            List<String> rows = b.layers().get(layer);
            for (int row = 0; row < rows.size(); row++) {
                String line = rows.get(row);
                for (int col = 0; col < line.length(); col++) {
                    char ch = line.charAt(col);
                    if (ch == ' ' || ch == '.') continue;
                    String v = b.key().get(ch);
                    if (v == null) continue;
                    int x = ox + inward.getStepX() * row + right.getStepX() * (col - w / 2);
                    int z = oz + inward.getStepZ() * row + right.getStepZ() * (col - w / 2);
                    int yy = y + layer;
                    if (!p.ok(x, yy, z) || !p.ok(x, y - 1, z)) continue;
                    var here = p.get(x, yy, z);
                    if (!(here.isAir() || here.canBeReplaced()) || !here.getFluidState().isEmpty()) continue;
                    var below = layer == 0 ? p.get(x, y - 1, z) : p.get(x, yy - 1, z);
                    if (below.isAir() || !below.getFluidState().isEmpty() || !below.canOcclude()) continue;
                    put(p, null, x, yy, z, v, inward);
                    placed++;
                }
            }
        }
        if (placed > 0) p.feature("blueprint:" + b.id());
        return placed > 0;
    }

    private static void put(Painter p, @Nullable Room room, int x, int y, int z, String v, Direction inward) {
        if (v.equals("air")) {
            p.air(x, y, z);
        } else if (v.equals("light")) {
            p.set(x, y, z, BuiltBuilder.light(p).hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)
                    ? BuiltBuilder.light(p).setValue(net.minecraft.world.level.block.LanternBlock.HANGING, false) : BuiltBuilder.light(p));
        } else if (v.startsWith("loot:")) {
            String[] parts = v.split(":", 3);
            BlockState box = parts[1].equals("barrel") ? Deco.barrel(inward) : Deco.chest(inward);
            p.container(x, y, z, box, Identifier.parse(parts[2]));
        } else if (v.startsWith("foe")) {
            String id = Beats.foe(p, room);
            if (v.endsWith(":dormant")) p.spawnDormant(id, x, y, z, Painter.yaw(inward));
            else p.spawn(id, x, y, z, Painter.yaw(inward), null);
        } else if (v.startsWith("mob:")) {
            p.spawn(v.substring(4), x, y, z, Painter.yaw(inward), null);
        } else {
            boolean turn = v.endsWith("@in");
            BlockState s = state(turn ? v.substring(0, v.length() - 3) : v);
            if (s != null) p.set(x, y, z, turn ? Painter.facing(s, inward) : s);
        }
    }

    @Nullable
    private static synchronized BlockState state(String text) {
        if (STATES.containsKey(text)) return STATES.get(text);
        BlockState s = null;
        try {
            s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Skycraft dungeons: bad blueprint block {}", text);
        }
        STATES.put(text, s);
        return s;
    }

    private static List<Blueprint> load() {
        List<Blueprint> out = new ArrayList<>();
        JsonElement index = read("/skycraft/dungeons/blueprints/index.json");
        if (index == null || !index.isJsonArray()) return out;
        for (JsonElement f : index.getAsJsonArray()) {
            JsonElement e = read("/skycraft/dungeons/blueprints/" + f.getAsString());
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            List<List<String>> layers = new ArrayList<>();
            for (JsonElement l : o.getAsJsonArray("layers")) {
                List<String> rows = new ArrayList<>();
                for (JsonElement r : l.getAsJsonArray()) rows.add(r.getAsString());
                layers.add(rows);
            }
            Map<Character, String> key = new HashMap<>();
            for (Map.Entry<String, JsonElement> k : o.getAsJsonObject("key").entrySet()) key.put(k.getKey().charAt(0), k.getValue().getAsString());
            out.add(new Blueprint(o.get("id").getAsString(), strings(o.getAsJsonArray("themes")), strings(o.getAsJsonArray("beats")), layers, key));
        }
        return List.copyOf(out);
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    private static JsonElement read(String path) {
        try (InputStream in = Blueprints.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Skycraft dungeons: could not read {}", path, e);
            return null;
        }
    }
}
