package com.skycraft.dungeons.world.cavern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One look of a grand cavern, read from {@code skycraft/dungeons/caverns/profiles/*.json}: where it belongs, the
 * shapes it takes, its blocks, fluid, flora, signature features, set piece, inhabitants and loot. Immutable.
 *
 * <p>A block is written as {@code "minecraft:moss_block@30"} (state string, {@code @weight}); {@code rock} stands for
 * the dungeon's own rock (stone, deepslate below y 0).</p>
 */
public final class CavernProfile {
    // ------------------------------------------------------------------ small value types

    /** A weighted list of block states; a null entry is the cavern's plain rock. */
    public static final class Pal {
        public static final Pal EMPTY = new Pal(new BlockState[0], new int[0]);
        private final BlockState[] states;
        private final int[] cum;
        private final int total;

        Pal(BlockState[] states, int[] weights) {
            this.states = states;
            this.cum = new int[weights.length];
            int t = 0;
            for (int i = 0; i < weights.length; i++) {
                t += weights[i];
                cum[i] = t;
            }
            this.total = t;
        }

        public boolean isEmpty() {
            return states.length == 0;
        }

        public int total() {
            return Math.max(1, total);
        }

        /** The block for a roll in [0, total); {@code null} means the plain rock of the level. */
        @Nullable
        public BlockState pick(int roll) {
            if (states.length == 0) return null;
            for (int i = 0; i < cum.length; i++) if (roll < cum[i]) return states[i];
            return states[states.length - 1];
        }

        @Nullable
        public BlockState first() {
            return states.length == 0 ? null : states[0];
        }

        public boolean has(BlockState s) {
            for (BlockState b : states) if (b == s) return true;
            return false;
        }
    }

    /** Fluid kinds. {@code POOL} is a solid bed (honey, mud, ash, sand) filled where a lake would be. */
    public enum FluidKind { NONE, WATER, LAVA, ICE, POOL }

    public record Fluid(FluidKind kind, float chance, Pal block, Pal cap) {}

    public enum FloraType { PLANT, CEIL, HANG, COLUMN, MUSHROOM, SPIRE, BED, FLOAT, TREE }

    /** One band of the per-column dice: {@code density} per thousand columns. */
    public record Flora(FloraType type, Pal block, Pal cap, Pal tip, int density, int minLen, int maxLen, int radius) {}

    /** A signature feature: vein, pool, vent, arch, ruin, statue, scatter... (see GrandCavern). */
    public record Feature(String type, Pal block, Pal accent, String fluid, int minCount, int maxCount, int minSize, int maxSize,
                          double scale, double amount) {}

    public record Lights(Pal roof, int density, int cluster, Pal post) {}

    public record SetPiece(String type, Pal block, Pal accent) {}

    public record Ambient(String entity, int min, int max, String where) {}

    // ------------------------------------------------------------------ fields

    public final String id;
    public final String name;
    public final String lore;
    public final float weight;
    final Set<String> provinces, regions, cultures, weathers, themes, kits, rosters, temps;
    final int minY, maxY;
    /** Allowed shapes with weights. */
    public final Map<CavernShape, Float> shapes;
    /** Per-shape overrides of the shape parameters (key to [min, max]). */
    public final Map<CavernShape, Map<String, double[]>> shapeParams;
    public final Pal floor, wall, spike, pillar, platform, terrace, bed, ruin, patch;
    /** Noise level above which the patch palette replaces the floor. */
    public final double patchAt;
    public final Fluid fluid;
    public final List<Flora> flora;
    public final List<Feature> features;
    public final Lights lights;
    public final SetPiece setPiece;
    public final List<String> foes;
    @Nullable
    public final String eliteName;
    /** Loot tables of the relic cache on a gallery, the platform chest and the minor chests; null = the dungeon's own. */
    @Nullable
    public final ResourceLocation lootRelic, lootPlatform, lootMinor;
    public final List<Ambient> ambient;
    public final String particle, sound, lightColor;

    private CavernProfile(JsonObject o, String origin) {
        this.id = str(o, "id", null);
        if (id == null) throw new IllegalArgumentException("profile without id in " + origin);
        this.name = str(o, "name", id);
        this.lore = str(o, "lore", "");
        this.weight = (float) num(o, "weight", 1.0);
        JsonObject m = o.has("match") ? o.getAsJsonObject("match") : new JsonObject();
        this.provinces = set(m, "provinces");
        this.regions = set(m, "regions");
        this.cultures = set(m, "cultures");
        this.weathers = set(m, "weather");
        this.themes = set(m, "themes");
        this.kits = set(m, "kits");
        this.rosters = set(m, "rosters");
        this.temps = set(m, "temps");
        this.minY = (int) num(m, "minY", -4096);
        this.maxY = (int) num(m, "maxY", 4096);
        Map<CavernShape, Float> sh = new LinkedHashMap<>();
        if (o.has("shapes")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("shapes").entrySet()) {
                CavernShape s = CavernShape.byId(e.getKey());
                if (s == null) throw new IllegalArgumentException("unknown shape " + e.getKey() + " in profile " + id);
                sh.put(s, e.getValue().getAsFloat());
            }
        }
        if (sh.isEmpty()) sh.put(CavernShape.BOWL_BRIDGES, 1f);
        this.shapes = Collections.unmodifiableMap(sh);
        Map<CavernShape, Map<String, double[]>> sp = new EnumMap<>(CavernShape.class);
        if (o.has("shapeParams")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("shapeParams").entrySet()) {
                CavernShape s = CavernShape.byId(e.getKey());
                if (s != null) sp.put(s, CavernLibrary.ranges(e.getValue().getAsJsonObject()));
            }
        }
        this.shapeParams = sp;
        JsonObject pal = o.has("palette") ? o.getAsJsonObject("palette") : new JsonObject();
        this.floor = pal(pal, "floor");
        this.wall = pal(pal, "wall");
        this.spike = pal(pal, "spike");
        Pal pil = pal(pal, "pillar");
        this.pillar = pil.isEmpty() ? spike : pil;
        this.platform = pal(pal, "platform");
        Pal ter = pal(pal, "terrace");
        this.terrace = ter.isEmpty() ? floor : ter;
        this.bed = pal(pal, "bed");
        this.ruin = pal(pal, "ruin");
        this.patch = pal(pal, "patch");
        this.patchAt = num(pal, "patchAt", 0.2);
        this.fluid = fluid(o.has("fluid") ? o.get("fluid") : null);
        List<Flora> fl = new ArrayList<>();
        if (o.has("flora")) for (JsonElement e : o.getAsJsonArray("flora")) fl.add(flora(e.getAsJsonObject()));
        int sum = 0;
        for (Flora f : fl) sum += f.density;
        if (sum > 1000) Skycraft.LOGGER.warn("Skycraft caverns: profile {} flora density {} exceeds 1000", id, sum);
        this.flora = List.copyOf(fl);
        List<Feature> ft = new ArrayList<>();
        if (o.has("features")) for (JsonElement e : o.getAsJsonArray("features")) ft.add(feature(e.getAsJsonObject()));
        this.features = List.copyOf(ft);
        JsonObject lg = o.has("lights") ? o.getAsJsonObject("lights") : new JsonObject();
        this.lights = new Lights(pal(lg, "roof"), (int) num(lg, "density", 44), (int) num(lg, "cluster", 0), pal(lg, "post"));
        JsonObject spo = o.has("setpiece") ? o.getAsJsonObject("setpiece") : new JsonObject();
        this.setPiece = new SetPiece(str(spo, "type", "camp"), pal(spo, "block"), pal(spo, "accent"));
        List<String> foes = new ArrayList<>();
        if (o.has("foes")) for (JsonElement e : o.getAsJsonArray("foes")) foes.add(e.getAsString());
        this.foes = List.copyOf(foes);
        this.eliteName = o.has("elite") ? o.get("elite").getAsString() : null;
        JsonObject loot = o.has("loot") ? o.getAsJsonObject("loot") : new JsonObject();
        this.lootRelic = loot.has("relic") ? rl(loot.get("relic").getAsString()) : null;
        this.lootPlatform = loot.has("platform") ? rl(loot.get("platform").getAsString()) : null;
        this.lootMinor = loot.has("minor") ? rl(loot.get("minor").getAsString()) : null;
        List<Ambient> am = new ArrayList<>();
        if (o.has("ambient")) {
            for (JsonElement e : o.getAsJsonArray("ambient")) {
                JsonObject a = e.getAsJsonObject();
                double[] cnt = range(a, "count", 2, 4);
                am.add(new Ambient(str(a, "entity", "minecraft:bat"), (int) cnt[0], (int) cnt[1], str(a, "where", "air")));
            }
        }
        this.ambient = List.copyOf(am);
        this.particle = str(o, "particle", "");
        this.sound = str(o, "sound", "");
        this.lightColor = str(o, "lightColor", "");
    }

    static CavernProfile parse(JsonObject o, String origin) {
        return new CavernProfile(o, origin);
    }

    // ------------------------------------------------------------------ matching

    /**
     * Whether the profile may be used in a context, and the multiplier its specificity earns (0 = not eligible).
     * Every list the profile gives must contain the context's value; the more lists it satisfies, the more specific.
     */
    double score(CavernContext c) {
        double s = weight;
        if (c.y < minY || c.y > maxY) return 0;
        if (!themes.isEmpty()) {
            if (!themes.contains(c.theme.id)) return 0;
            s *= 1.6;
        }
        if (!regions.isEmpty()) {
            if (!regions.contains(c.regionId) && !regions.contains(c.regionShort)) return 0;
            s *= 7;
        }
        if (!provinces.isEmpty()) {
            if (!provinces.contains(c.province)) return 0;
            s *= 3;
        }
        if (!cultures.isEmpty()) {
            if (!cultures.contains(c.culture)) return 0;
            s *= 2.5;
        }
        if (!weathers.isEmpty()) {
            if (!weathers.contains(c.weather)) return 0;
            s *= 2;
        }
        if (!temps.isEmpty()) {
            if (!temps.contains(c.temp)) return 0;
            s *= 1.7;
        }
        if (!kits.isEmpty()) {
            if (!kits.contains(c.kit.id)) return 0;
            s *= 4;
        }
        if (!rosters.isEmpty()) {
            if (c.roster == null || !rosters.contains(c.roster)) return 0;
            s *= 4;
        }
        return s;
    }

    /** True when the profile names this province, region or culture (not just a theme). */
    public boolean regional() {
        return !provinces.isEmpty() || !regions.isEmpty() || !cultures.isEmpty();
    }

    // ------------------------------------------------------------------ parsing helpers

    private static ResourceLocation rl(String s) {
        ResourceLocation r = ResourceLocation.tryParse(s);
        return r != null ? r : new ResourceLocation(Skycraft.MODID, "chests/dungeon_common");
    }

    private static String str(JsonObject o, String k, @Nullable String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static double num(JsonObject o, String k, double def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def;
    }

    /** A number or a [min, max] pair. */
    static double[] range(JsonObject o, String k, double lo, double hi) {
        if (!o.has(k)) return new double[]{lo, hi};
        JsonElement e = o.get(k);
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            return new double[]{a.get(0).getAsDouble(), a.get(a.size() > 1 ? 1 : 0).getAsDouble()};
        }
        double v = e.getAsDouble();
        return new double[]{v, v};
    }

    private static Set<String> set(JsonObject o, String k) {
        Set<String> out = new LinkedHashSet<>();
        if (o.has(k)) for (JsonElement e : o.getAsJsonArray(k)) out.add(e.getAsString().toLowerCase(Locale.ROOT));
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }

    private static final Map<String, BlockState> STATES = new HashMap<>();

    @Nullable
    private static synchronized BlockState state(String text, String where) {
        if (text.equals("rock")) return null;
        if (STATES.containsKey(text)) return STATES.get(text);
        BlockState s;
        try {
            s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState();
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Skycraft caverns: bad block '{}' in {}", text, where);
            s = Blocks.STONE.defaultBlockState();
        }
        STATES.put(text, s);
        return s;
    }

    /** {@code "id[props]@weight"}, a bare id, or an array of these. */
    static Pal pal(JsonObject o, String key) {
        if (!o.has(key)) return Pal.EMPTY;
        JsonElement e = o.get(key);
        List<String> items = new ArrayList<>();
        if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) items.add(x.getAsString());
        else items.add(e.getAsString());
        BlockState[] st = new BlockState[items.size()];
        int[] w = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            String t = items.get(i);
            int at = t.lastIndexOf('@');
            int weight = 1;
            if (at > 0) {
                try {
                    weight = Math.max(1, Integer.parseInt(t.substring(at + 1).trim()));
                } catch (NumberFormatException ex) {
                    weight = 1;
                }
                t = t.substring(0, at);
            }
            st[i] = state(t.trim(), key);
            w[i] = weight;
        }
        return new Pal(st, w);
    }

    private static Fluid fluid(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull()) return new Fluid(FluidKind.NONE, 0f, Pal.EMPTY, Pal.EMPTY);
        if (e.isJsonPrimitive()) e = objOf("kind", e.getAsString());
        JsonObject o = e.getAsJsonObject();
        FluidKind k = FluidKind.NONE;
        String s = str(o, "kind", "none").toLowerCase(Locale.ROOT);
        for (FluidKind f : FluidKind.values()) if (f.name().toLowerCase(Locale.ROOT).equals(s)) k = f;
        return new Fluid(k, (float) num(o, "chance", 1.0), pal(o, "block"), pal(o, "cap"));
    }

    private static JsonObject objOf(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    private static Flora flora(JsonObject o) {
        FloraType t = FloraType.PLANT;
        String s = str(o, "type", "plant").toUpperCase(Locale.ROOT);
        for (FloraType f : FloraType.values()) if (f.name().equals(s)) t = f;
        double[] len = range(o, "len", 2, 5);
        return new Flora(t, pal(o, "block"), pal(o, "cap"), pal(o, "tip"), (int) num(o, "density", 10), (int) len[0], (int) len[1],
                (int) num(o, "radius", 2));
    }

    private static Feature feature(JsonObject o) {
        double[] cnt = range(o, "count", 2, 4);
        double[] size = range(o, "size", 2, 3);
        return new Feature(str(o, "type", "scatter"), pal(o, "block"), pal(o, "accent"), str(o, "fluid", "water"), (int) cnt[0],
                (int) cnt[1], (int) size[0], (int) size[1], num(o, "scale", 5.0), num(o, "amount", 0.1));
    }
}
