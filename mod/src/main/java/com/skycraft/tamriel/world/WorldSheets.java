package com.skycraft.tamriel.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Loader and query API for MS22 causal world-model sheets ({@code /skycraft/tamriel/world/<province>.json}).
 *
 * <p>Pure Java + Gson with no Minecraft registry dependencies so Tier 0 unit tests can query sheets,
 * verify causal citations, and resolve {@code why} field IDs directly. Thread-safe and immutable once loaded;
 * {@link #preloadAsync()} warms the snapshot on a background thread at server startup.</p>
 */
public final class WorldSheets {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldSheets.class);

    /** The eleven causal layers in order L1..L11. */
    public static final List<String> LAYERS = List.of(
            "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10", "L11");

    private static final String[] KNOWN_PROVINCES = {
            "skyrim", "solstheim", "high_rock", "hammerfell", "cyrodiil",
            "morrowind", "black_marsh", "elsweyr", "valenwood", "summerset", "foreign"
    };

    private static final Map<String, String> SCOPE_ALIASES = Map.ofEntries(
            Map.entry("sk", "skyrim"),
            Map.entry("skyrim", "skyrim"),
            Map.entry("pale", "the_pale"),
            Map.entry("the_pale", "the_pale"),
            Map.entry("rift", "the_rift"),
            Map.entry("the_rift", "the_rift"),
            Map.entry("reach", "the_reach"),
            Map.entry("the_reach", "the_reach"),
            Map.entry("hr", "high_rock"),
            Map.entry("hf", "hammerfell"),
            Map.entry("cy", "cyrodiil"),
            Map.entry("mw", "morrowind"),
            Map.entry("bm", "black_marsh"),
            Map.entry("el", "elsweyr"),
            Map.entry("vw", "valenwood"),
            Map.entry("si", "summerset"),
            Map.entry("so", "solstheim"),
            Map.entry("fl", "foreign")
    );

    private static final Map<String, String> ROLE_ALIASES = Map.ofEntries(
            Map.entry("blacksmith", "smith"),
            Map.entry("weaponsmith", "smith"),
            Map.entry("armorer", "smith"),
            Map.entry("toolsmith", "smith"),
            Map.entry("apothecary", "alchemist"),
            Map.entry("cleric", "alchemist"),
            Map.entry("general", "merchant"),
            Map.entry("general_merchant", "merchant"),
            Map.entry("cartographer", "merchant"),
            Map.entry("trader", "merchant"),
            Map.entry("shopkeeper", "merchant"),
            Map.entry("librarian", "bookseller"),
            Map.entry("shepherd", "tailor"),
            Map.entry("fisherman", "fisher"),
            Map.entry("fishmonger", "fisher"),
            Map.entry("leatherworker", "hunter"),
            Map.entry("inn", "innkeeper"),
            Map.entry("wizard", "court_wizard"),
            Map.entry("jail_prisoner", "prisoner")
    );

    /** A single causal field in a province or region sheet. */
    public record Field(String globalId, String scope, String province, String layer, int layerNum,
                        String id, String text, List<String> cites) {}

    /** Job weight and causal {@code why} field IDs for a settlement role in a region. */
    public record JobSpec(String role, int weight, List<String> why) {}

    /** A forbidden contradiction pair declared by a province sheet. */
    public record ForbiddenPair(String kind, String a, String b, String reason) {}

    /** One sheet block (either the province block or a region sub-sheet). */
    public record SheetBlock(String scope, String province, boolean isProvince, String truth,
                             Map<String, List<Field>> layers,
                             Map<String, JobSpec> jobs,
                             Map<String, List<String>> dungeonThemes) {
        public List<Field> fieldsIn(String layer) {
            if (layer == null) return List.of();
            return layers.getOrDefault(layer.trim().toUpperCase(Locale.ROOT), List.of());
        }

        @Nullable
        public Field field(String layer, String id) {
            for (Field f : fieldsIn(layer)) {
                if (f.id().equals(id)) return f;
            }
            return null;
        }

        public int jobWeight(String roleName) {
            JobSpec spec = jobs.get(canonRole(roleName));
            return spec != null ? spec.weight() : 1;
        }

        public List<String> whyForJob(String roleName) {
            JobSpec spec = jobs.get(canonRole(roleName));
            if (spec != null && !spec.why().isEmpty()) return spec.why();
            List<Field> l5 = fieldsIn("L5");
            if (!l5.isEmpty()) return List.of(l5.get(0).globalId());
            return List.of();
        }
    }

    /** A full province world-model sheet. */
    public record ProvinceSheet(String province, SheetBlock provinceBlock,
                                Map<String, SheetBlock> regions,
                                List<ForbiddenPair> forbidden) {
        public String truth() {
            return provinceBlock.truth();
        }

        public Map<String, List<Field>> layers() {
            return provinceBlock.layers();
        }

        public List<Field> fieldsIn(String layer) {
            return provinceBlock.fieldsIn(layer);
        }
    }

    private record Snapshot(Map<String, ProvinceSheet> provinces,
                            Map<String, SheetBlock> regions,
                            Map<String, SheetBlock> allScopes,
                            Map<String, Field> fields) {}

    private static volatile Snapshot SNAPSHOT;

    private WorldSheets() {}

    /** Starts loading all world sheets on a background daemon thread if not already loaded. */
    public static void preloadAsync() {
        if (SNAPSHOT != null) return;
        CompletableFuture.runAsync(WorldSheets::snapshot);
    }

    private static Snapshot snapshot() {
        Snapshot s = SNAPSHOT;
        if (s != null) return s;
        synchronized (WorldSheets.class) {
            if (SNAPSHOT == null) {
                SNAPSHOT = loadAll();
            }
            return SNAPSHOT;
        }
    }

    /** Canonicalizes a province or region scope identifier (e.g. {@code "skyrim/the_reach"} or {@code "reach"} -> {@code "the_reach"}). */
    public static String canonScope(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        return SCOPE_ALIASES.getOrDefault(s, s);
    }

    /** Canonicalizes a job or NPC role name (e.g. {@code "MINER"} or {@code "WEAPONSMITH"} -> {@code "miner"} / {@code "smith"}). */
    public static String canonRole(String roleName) {
        if (roleName == null || roleName.isBlank()) return "citizen";
        String r = roleName.trim().toLowerCase(Locale.ROOT);
        int colon = r.indexOf(':');
        if (colon >= 0) r = r.substring(colon + 1);
        return ROLE_ALIASES.getOrDefault(r, r);
    }

    /** Canonicalizes a global field ID {@code <scope>.L<n>.<id>}. */
    public static String canonFieldId(String globalFieldId) {
        if (globalFieldId == null || globalFieldId.isBlank()) return "";
        String[] parts = globalFieldId.trim().split("\\.");
        if (parts.length != 3) return globalFieldId.trim();
        return canonScope(parts[0]) + "." + parts[1].trim().toUpperCase(Locale.ROOT) + "." + parts[2].trim().toLowerCase(Locale.ROOT);
    }

    /** Returns the sheet block for a region (accepts {@code "the_reach"}, {@code "reach"}, or {@code "skyrim/the_reach"}), or null if absent. */
    @Nullable
    public static SheetBlock region(String regionId) {
        String key = canonScope(regionId);
        return snapshot().regions().get(key);
    }

    /** Returns the province sheet for a province ID (accepts {@code "skyrim"} or {@code "sk"}), or null if absent. */
    @Nullable
    public static ProvinceSheet province(String provinceId) {
        String key = canonScope(provinceId);
        return snapshot().provinces().get(key);
    }

    /** Returns either a region block or province block matching {@code scope}, or null if absent. */
    @Nullable
    public static SheetBlock scope(String scope) {
        String key = canonScope(scope);
        return snapshot().allScopes().get(key);
    }

    /** All loaded province sheets. */
    public static Collection<ProvinceSheet> provinces() {
        return snapshot().provinces().values();
    }

    /** All loaded scope keys (provinces + regions). */
    public static Set<String> scopes() {
        return snapshot().allScopes().keySet();
    }

    /** Looks up a field by its global ID (e.g. {@code "the_reach.L5.silver_mining"} or {@code "skyrim.L9.civil_war"}). */
    @Nullable
    public static Field field(String globalFieldId) {
        String cid = canonFieldId(globalFieldId);
        return snapshot().fields().get(cid);
    }

    /** Returns true if {@code globalFieldId} resolves to a field in any loaded sheet. */
    public static boolean resolves(String globalFieldId) {
        return field(globalFieldId) != null;
    }

    /** Returns all fields in {@code layer} ({@code "L1"}..{@code "L11"}) for the given province or region {@code scope}. */
    public static List<Field> fieldsIn(String scope, String layer) {
        SheetBlock b = scope(scope);
        return b != null ? b.fieldsIn(layer) : List.of();
    }

    /** Returns all fields in {@code layer} ({@code "L1"}..{@code "L11"}) across all loaded scopes. */
    public static List<Field> fieldsIn(String layer) {
        if (layer == null) return List.of();
        String norm = layer.trim().toUpperCase(Locale.ROOT);
        List<Field> out = new ArrayList<>();
        for (SheetBlock b : snapshot().allScopes().values()) {
            out.addAll(b.fieldsIn(norm));
        }
        return List.copyOf(out);
    }

    /** Returns the world truth paragraph for a region or province {@code scope}, or {@code ""} if absent. */
    public static String truth(String scope) {
        SheetBlock b = scope(scope);
        return b != null ? b.truth() : "";
    }

    /**
     * Returns the resolving {@code why} field IDs for a settlement job or NPC role in {@code regionId}.
     * Falls back to an empty list when the region has no loaded world sheet.
     */
    public static List<String> whyForJob(String regionId, String roleName) {
        SheetBlock b = scope(regionId);
        return b != null ? b.whyForJob(roleName) : List.of();
    }

    /**
     * Returns the L5-derived job weight for a settlement job or NPC role in {@code regionId} (1 if no sheet).
     */
    public static int jobWeight(String regionId, String roleName) {
        SheetBlock b = scope(regionId);
        return b != null ? b.jobWeight(roleName) : 1;
    }

    /**
     * Returns the resolving L10 {@code why} field IDs for a dungeon family in {@code regionId}, or empty list if absent.
     */
    public static List<String> whyForDungeon(String regionId, String family) {
        SheetBlock b = scope(regionId);
        if (b == null || family == null) return List.of();
        List<String> why = b.dungeonThemes().get(family.trim().toLowerCase(Locale.ROOT));
        if (why != null && !why.isEmpty()) return why;
        List<Field> l10 = b.fieldsIn("L10");
        return !l10.isEmpty() ? List.of(l10.get(0).globalId()) : List.of();
    }

    private static Snapshot loadAll() {
        Map<String, ProvinceSheet> provinces = new LinkedHashMap<>();
        Map<String, SheetBlock> regions = new LinkedHashMap<>();
        Map<String, SheetBlock> allScopes = new LinkedHashMap<>();
        Map<String, Field> fields = new LinkedHashMap<>();

        for (String provId : KNOWN_PROVINCES) {
            String path = "/skycraft/tamriel/world/" + provId + ".json";
            try (InputStream in = WorldSheets.class.getResourceAsStream(path)) {
                if (in == null) continue;
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                String prov = canonScope(str(root, "province", provId));
                SheetBlock pBlock = parseBlock(prov, prov, true, root, fields);
                allScopes.put(prov, pBlock);

                Map<String, SheetBlock> regMap = new LinkedHashMap<>();
                if (root.has("regions") && root.get("regions").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> re : root.getAsJsonObject("regions").entrySet()) {
                        String rkey = canonScope(re.getKey());
                        SheetBlock rBlock = parseBlock(rkey, prov, false, re.getValue().getAsJsonObject(), fields);
                        regMap.put(rkey, rBlock);
                        regions.put(rkey, rBlock);
                        allScopes.put(rkey, rBlock);
                    }
                }

                List<ForbiddenPair> forbidden = new ArrayList<>();
                if (root.has("forbidden") && root.get("forbidden").isJsonArray()) {
                    for (JsonElement fe : root.getAsJsonArray("forbidden")) {
                        if (!fe.isJsonObject()) continue;
                        JsonObject fo = fe.getAsJsonObject();
                        forbidden.add(new ForbiddenPair(
                                str(fo, "kind", ""),
                                str(fo, "a", ""),
                                str(fo, "b", ""),
                                str(fo, "reason", "")));
                    }
                }

                provinces.put(prov, new ProvinceSheet(
                        prov,
                        pBlock,
                        Collections.unmodifiableMap(regMap),
                        List.copyOf(forbidden)));
            } catch (Exception ex) {
                LOGGER.error("Failed to load world sheet {}", path, ex);
            }
        }
        LOGGER.info("Loaded {} world model sheet(s): {} blocks, {} fields",
                provinces.size(), allScopes.size(), fields.size());
        return new Snapshot(
                Collections.unmodifiableMap(provinces),
                Collections.unmodifiableMap(regions),
                Collections.unmodifiableMap(allScopes),
                Collections.unmodifiableMap(fields));
    }

    private static SheetBlock parseBlock(String scope, String prov, boolean isProvince,
                                         JsonObject obj, Map<String, Field> globalFields) {
        String truth = str(obj, "truth", "");
        Map<String, List<Field>> layers = new LinkedHashMap<>();
        JsonObject layersObj = obj.has("layers") && obj.get("layers").isJsonObject()
                ? obj.getAsJsonObject("layers") : new JsonObject();

        for (int i = 1; i <= 11; i++) {
            String lyr = "L" + i;
            List<Field> flist = new ArrayList<>();
            if (layersObj.has(lyr) && layersObj.get(lyr).isJsonArray()) {
                for (JsonElement el : layersObj.getAsJsonArray(lyr)) {
                    if (!el.isJsonObject()) continue;
                    JsonObject fo = el.getAsJsonObject();
                    String id = str(fo, "id", "").trim();
                    String text = str(fo, "text", "").trim();
                    List<String> cites = new ArrayList<>();
                    if (fo.has("cites") && fo.get("cites").isJsonArray()) {
                        for (JsonElement ce : fo.getAsJsonArray("cites")) {
                            cites.add(canonFieldId(ce.getAsString()));
                        }
                    }
                    String gid = scope + "." + lyr + "." + id;
                    Field field = new Field(gid, scope, prov, lyr, i, id, text, List.copyOf(cites));
                    flist.add(field);
                    globalFields.put(gid, field);
                }
            }
            layers.put(lyr, List.copyOf(flist));
        }

        Map<String, JobSpec> jobs = new LinkedHashMap<>();
        if (obj.has("jobs") && obj.get("jobs").isJsonObject()) {
            for (Map.Entry<String, JsonElement> je : obj.getAsJsonObject("jobs").entrySet()) {
                if (!je.getValue().isJsonObject()) continue;
                String role = canonRole(je.getKey());
                JsonObject jo = je.getValue().getAsJsonObject();
                int weight = jo.has("weight") ? jo.get("weight").getAsInt() : 1;
                List<String> why = stringList(jo.get("why"));
                jobs.put(role, new JobSpec(role, weight, why));
            }
        }

        Map<String, List<String>> themes = new LinkedHashMap<>();
        if (obj.has("dungeon_themes") && obj.get("dungeon_themes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> te : obj.getAsJsonObject("dungeon_themes").entrySet()) {
                themes.put(te.getKey().trim().toLowerCase(Locale.ROOT), stringList(te.getValue()));
            }
        }

        return new SheetBlock(
                scope,
                prov,
                isProvince,
                truth,
                Collections.unmodifiableMap(layers),
                Collections.unmodifiableMap(jobs),
                Collections.unmodifiableMap(themes));
    }

    private static List<String> stringList(@Nullable JsonElement el) {
        if (el == null || !el.isJsonArray()) return List.of();
        JsonArray arr = el.getAsJsonArray();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (!e.isJsonNull()) out.add(canonFieldId(e.getAsString()));
        }
        return List.copyOf(out);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
