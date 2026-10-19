package com.nirncraft.landmarks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rock and wood material sets ({@code nirncraft/landmarks/palettes.json}). */
public final class Mats {
    /** Weighted block list; {@link #at} is a pure function of the hash, so the same site always picks the same. */
    public static final class Pick {
        private final Block[] blocks;
        private final int[] cum;
        private final int total;

        Pick(List<Block> bs, List<Integer> ws) {
            blocks = bs.toArray(new Block[0]);
            cum = new int[blocks.length];
            int t = 0;
            for (int i = 0; i < blocks.length; i++) {
                t += ws.get(i);
                cum[i] = t;
            }
            total = t;
        }

        public boolean empty() {
            return total == 0;
        }

        public Block at(long h) {
            if (total == 0) return Blocks.STONE;
            int v = (int) Math.floorMod(h, (long) total);
            for (int i = 0; i < cum.length; i++) if (v < cum[i]) return blocks[i];
            return blocks[blocks.length - 1];
        }

        public BlockState state(long h) {
            return at(h).defaultBlockState();
        }
    }

    public record Rock(String id, Pick main, Pick alt, Pick moss, Pick rubble, Pick cap, Pick band) {}

    public record Wood(String id, Block log, Block planks, Block leaves, Block fence, Block slab, Block sign, Block wallSign) {}

    private static final Map<String, Rock> ROCKS = new LinkedHashMap<>();
    private static final Map<String, Wood> WOODS = new LinkedHashMap<>();
    private static final Map<String, List<Object[]>> FAMILY_ROCKS = new LinkedHashMap<>();
    private static final Map<String, List<Object[]>> FAMILY_WOODS = new LinkedHashMap<>();

    static {
        try (InputStream in = Mats.class.getResourceAsStream("/nirncraft/landmarks/palettes.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : root.getAsJsonObject("rocks").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    ROCKS.put(e.getKey(), new Rock(e.getKey(), pick(o, "main"), pick(o, "alt"), pick(o, "moss"),
                            pick(o, "rubble"), pick(o, "cap"), pick(o, "band")));
                }
                for (var e : root.getAsJsonObject("woods").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    WOODS.put(e.getKey(), new Wood(e.getKey(), block(o, "log"), block(o, "planks"), block(o, "leaves"),
                            block(o, "fence"), block(o, "slab"), block(o, "sign"), block(o, "wall_sign")));
                }
                readFamilies(root.getAsJsonObject("family_rocks"), FAMILY_ROCKS);
                readFamilies(root.getAsJsonObject("family_woods"), FAMILY_WOODS);
            }
        } catch (Exception ex) {
            NirnCraft.LOGGER.warn("NirnCraft landmarks: could not read palettes.json", ex);
        }
        if (ROCKS.isEmpty()) {
            Pick stone = new Pick(List.of(Blocks.STONE), List.of(1));
            ROCKS.put("granite", new Rock("granite", stone, stone, stone, stone, stone, new Pick(List.of(), List.of())));
        }
        if (WOODS.isEmpty()) {
            WOODS.put("oak", new Wood("oak", Blocks.OAK_LOG, Blocks.OAK_PLANKS, Blocks.OAK_LEAVES, Blocks.OAK_FENCE,
                    Blocks.OAK_SLAB, Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN));
        }
    }

    private Mats() {}

    private static Block block(JsonObject o, String key) {
        return lookup(o.get(key).getAsString());
    }

    private static Block lookup(String id) {
        Identifier rl = Identifier.tryParse(id);
        Block b = rl == null ? null : BuiltInRegistries.BLOCK.getValue(rl);
        if (b == null || b == Blocks.AIR) {
            NirnCraft.LOGGER.warn("NirnCraft landmarks: unknown block {}", id);
            return Blocks.STONE;
        }
        return b;
    }

    private static Pick pick(JsonObject o, String key) {
        List<Block> bs = new ArrayList<>();
        List<Integer> ws = new ArrayList<>();
        if (o.has(key)) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                String s = e.getAsString();
                int cut = s.lastIndexOf(':');
                int w = Integer.parseInt(s.substring(cut + 1));
                if (w <= 0) continue;
                bs.add(lookup(s.substring(0, cut)));
                ws.add(w);
            }
        }
        return new Pick(bs, ws);
    }

    private static void readFamilies(JsonObject o, Map<String, List<Object[]>> out) {
        for (var e : o.entrySet()) {
            List<Object[]> l = new ArrayList<>();
            for (JsonElement el : e.getValue().getAsJsonArray()) {
                String s = el.getAsString();
                int cut = s.lastIndexOf(':');
                l.add(new Object[]{s.substring(0, cut), Integer.parseInt(s.substring(cut + 1))});
            }
            out.put(e.getKey(), l);
        }
    }

    public static Rock rock(String id) {
        return ROCKS.getOrDefault(id, ROCKS.values().iterator().next());
    }

    public static Wood wood(String id) {
        return WOODS.getOrDefault(id, WOODS.values().iterator().next());
    }

    public static java.util.Set<String> rockIds() {
        return ROCKS.keySet();
    }

    /** A rock set for the families of a spot (base counts fully, overlays half), picked by {@code h}. */
    public static Rock rockFor(List<String> families, long h) {
        return rock(choose(FAMILY_ROCKS, families, h, "granite"));
    }

    public static Wood woodFor(List<String> families, long h) {
        return wood(choose(FAMILY_WOODS, families, h, "oak"));
    }

    private static String choose(Map<String, List<Object[]>> table, List<String> families, long h, String fallback) {
        Map<String, Double> w = new LinkedHashMap<>();
        for (int i = 0; i < families.size(); i++) {
            List<Object[]> l = table.get(families.get(i));
            if (l == null) continue;
            double scale = i == 0 ? 1.0 : 0.5;
            for (Object[] o : l) w.merge((String) o[0], (Integer) o[1] * scale, Double::sum);
        }
        double total = 0;
        for (double v : w.values()) total += v;
        if (total <= 0) return fallback;
        double roll = ((Math.floorMod(h, 1_000_003L)) / 1_000_003.0) * total;
        for (var e : w.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) return e.getKey();
        }
        return fallback;
    }
}
