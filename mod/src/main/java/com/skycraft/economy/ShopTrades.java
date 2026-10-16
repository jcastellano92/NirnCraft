package com.skycraft.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What each kind of shop is, as data ({@code /skycraft/economy/shop_trades.json}, linted by tools/qa/data_checks.py):
 * which settlement anchors stand behind which counters, which jobs sell which part of a shop's stock, what a trade may
 * stock and how many of a cheap good it carries. The stock itself is a property of the shop (building), never of a
 * person: see {@link ShopBook}.
 */
public final class ShopTrades {
    private static final String FILE = "/skycraft/economy/shop_trades.json";

    /** A job's part in a shop: the shop types it works and the categories it may sell from the shared stock. */
    public record Job(List<ShopType> pools, Set<ItemCategory> sells) {
        /** True when this job sells everything the shop has. */
        public boolean sellsAll() {
            return sells.isEmpty();
        }
    }

    /** One trade's declared floor and limits. */
    public record Trade(int minRows, Set<ItemCategory> allow, float qtyScale) {
        public boolean allows(ItemCategory c) {
            return allow.isEmpty() || allow.contains(c);
        }
    }

    private record Bracket(int maxValue, int min, int max) {}

    private static final Map<String, List<ShopType>> ANCHORS = new HashMap<>();
    private static final Map<String, Job> JOBS = new HashMap<>();
    private static final Map<ShopType, Trade> TRADES = new EnumMap<>(ShopType.class);
    private static final List<Bracket> QUANTITY = new ArrayList<>();
    private static final Map<String, Set<String>> GOODS_BY_PROVINCE = new HashMap<>();
    private static boolean loaded;

    private ShopTrades() {}

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = ShopTrades.class.getResourceAsStream(FILE)) {
            if (in == null) {
                Skycraft.LOGGER.warn("Missing {}: shops use their built-in defaults", FILE);
                defaults();
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject anchors = obj(root, "anchors");
            for (Map.Entry<String, JsonElement> e : anchors.entrySet()) {
                List<ShopType> types = types(e.getValue());
                if (!types.isEmpty()) ANCHORS.put(e.getKey().toLowerCase(Locale.ROOT), types);
            }
            JsonObject jobs = obj(root, "jobs");
            for (Map.Entry<String, JsonElement> e : jobs.entrySet()) {
                JsonObject j = e.getValue().getAsJsonObject();
                JOBS.put(e.getKey().toLowerCase(Locale.ROOT), new Job(types(j.get("pools")), categories(j.get("sells"))));
            }
            JsonObject trades = obj(root, "trades");
            for (Map.Entry<String, JsonElement> e : trades.entrySet()) {
                ShopType t = ShopType.byId(e.getKey());
                if (t == null) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                TRADES.put(t, new Trade(o.has("min_rows") ? o.get("min_rows").getAsInt() : 0, categories(o.get("allow")),
                        o.has("qty_scale") ? o.get("qty_scale").getAsFloat() : 1f));
            }
            if (root.has("quantity")) {
                for (JsonElement el : root.getAsJsonArray("quantity")) {
                    JsonObject b = el.getAsJsonObject();
                    QUANTITY.add(new Bracket(b.get("max_value").getAsInt(), b.get("min").getAsInt(), b.get("max").getAsInt()));
                }
            }
            JsonObject inn = obj(root, "goods_by_province");
            for (Map.Entry<String, JsonElement> e : inn.entrySet()) {
                Set<String> provinces = new java.util.HashSet<>();
                for (JsonElement p : e.getValue().getAsJsonArray()) provinces.add(p.getAsString());
                GOODS_BY_PROVINCE.put(e.getKey(), provinces);
            }
        } catch (Exception ex) {
            Skycraft.LOGGER.warn("Could not read {}: shops use their built-in defaults", FILE, ex);
            defaults();
        }
        if (QUANTITY.isEmpty()) defaults();
    }

    private static void defaults() {
        QUANTITY.clear();
        QUANTITY.add(new Bracket(5, 4, 16));
        QUANTITY.add(new Bracket(25, 2, 6));
        QUANTITY.add(new Bracket(100, 1, 3));
        QUANTITY.add(new Bracket(Integer.MAX_VALUE, 1, 1));
    }

    private static JsonObject obj(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : new JsonObject();
    }

    private static List<ShopType> types(@Nullable JsonElement el) {
        List<ShopType> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement p : (JsonArray) el) {
            ShopType t = ShopType.byId(p.getAsString());
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static Set<ItemCategory> categories(@Nullable JsonElement el) {
        Set<ItemCategory> out = EnumSet.noneOf(ItemCategory.class);
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement c : (JsonArray) el) {
            try {
                out.add(ItemCategory.valueOf(c.getAsString().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // the lint reports unknown names; the game just ignores them
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ queries

    /** The shop types behind an anchor of this type (lowercase AnchorType id); empty when it is no counter. */
    public static List<ShopType> poolsForAnchor(String anchorId) {
        load();
        return ANCHORS.getOrDefault(anchorId.toLowerCase(Locale.ROOT), List.of());
    }

    /** The declaration of a job (housing job id or society role id), or null. */
    @Nullable
    public static Job job(String id) {
        load();
        return id == null || id.isEmpty() ? null : JOBS.get(id.toLowerCase(Locale.ROOT));
    }

    public static Trade trade(ShopType t) {
        load();
        Trade d = TRADES.get(t);
        return d != null ? d : new Trade(0, Collections.emptySet(), 1f);
    }

    /** Anchor ids the data declares (the lint compares them with AnchorType). */
    public static Set<String> anchorIds() {
        load();
        return ANCHORS.keySet();
    }

    /**
     * {min, max} stack size of a bulk good worth {@code value} gold in a shop of this type: cheap goods come in
     * bigger lots, the trade's {@code qty_scale} makes a farmer's lot bigger than a jeweller's.
     */
    public static int[] quantity(int value, ShopType type) {
        load();
        Bracket b = QUANTITY.get(QUANTITY.size() - 1);
        for (Bracket c : QUANTITY) {
            if (value <= c.maxValue) {
                b = c;
                break;
            }
        }
        float scale = trade(type).qtyScale();
        int min = Math.max(1, Math.round(b.min * scale));
        int max = Math.max(min, Math.round(b.max * scale));
        return new int[]{min, max};
    }

    /** Whether a shop in this province (gear key) stocks this good: goods the data does not list are sold everywhere. */
    public static boolean servedIn(String itemId, String province) {
        load();
        Set<String> where = GOODS_BY_PROVINCE.get(itemId);
        return where == null || where.contains(province);
    }

    /**
     * Whether a shop of {@code shopType} in {@code province} (at optional {@code placeId}) may stock {@code itemId}:
     * enforces {@link TradeNetwork#mayStock} embargoes/monopolies (e.g. skooma &amp; moon sugar only at fences/exotic
     * traders outside Elsweyr; Valenwood Green Pact) and allows local province goods plus {@code <= 3}-hop imports.
     */
    public static boolean servedAt(String itemId, String province, @Nullable String placeId, ShopType shopType) {
        load();
        if (!TradeNetwork.mayStock(shopType, province, placeId, itemId)) return false;
        if (shopType == ShopType.FENCE || shopType == ShopType.EXOTIC) return true;
        if (servedIn(itemId, province)) return true;
        if (shopType == ShopType.FOOD || shopType == ShopType.HUNTER || shopType == ShopType.FISHER) return false;
        if (placeId == null) return false;
        TradeNetwork.ImportRoute route = TradeNetwork.importsFor(placeId).get(itemId);
        return route != null && route.hops() <= 1;
    }

    /** Unmodifiable view of the declared province-specific goods table. */
    public static Map<String, Set<String>> goodsByProvince() {
        load();
        return Collections.unmodifiableMap(GOODS_BY_PROVINCE);
    }
}

