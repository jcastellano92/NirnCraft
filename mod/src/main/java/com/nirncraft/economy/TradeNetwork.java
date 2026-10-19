package com.nirncraft.economy;

import com.nirncraft.sailing.Port;
import com.nirncraft.sailing.Ports;
import com.nirncraft.sailing.Route;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.world.WorldSheets;
import com.nirncraft.travel.TravelMode;
import com.nirncraft.travel.TravelNetworks;
import com.nirncraft.travel.TravelNode;
import com.nirncraft.travel.TravelRoute;
import com.nirncraft.worldplan.RoadPlan;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Causal inter-settlement trade network (MS22 / T5).
 *
 * <p>Derives each settlement's local production from its {@link Region}, province, settlement tags/type, and
 * {@link WorldSheets} L1–L5 resource/economy layers; traces imported goods along {@code N <= 3} hops across
 * {@link RoadPlan} road links, {@link TravelNetworks} carriage/silt-strider/barge/caravan routes, and {@link Ports}
 * sea lanes; and enforces L7 embargoes, monopolies, and tariffs:</p>
 * <ul>
 *   <li><b>Moon Sugar &amp; Skooma</b> ({@code elsweyr.L5.moon_sugar}, {@code cyrodiil.L5.skooma_smuggling}): legal open
 *   trade in Elsweyr; contraband in Skyrim, Cyrodiil, High Rock, Summerset, Hammerfell, Morrowind, Solstheim, Valenwood,
 *   and Orsinium (stocked only by fences and Khajiit caravan / exotic traders at smuggling risk premiums).</li>
 *   <li><b>Valenwood Green Pact</b> ({@code valenwood.L5.meat_and_jagga}, {@code valenwood.L5.pact_imports}): zero local
 *   timber or tilled plant produce in Valenwood; Bosmer settlements export hunted meats, hides, bone, feathers, and
 *   fermented honey/jagga while importing foreign timber and grain at tariff premiums.</li>
 *   <li><b>East Empire Charter Monopoly</b> ({@code morrowind.L5.east_empire_charter}, {@code morrowind.L5.ebony_mining}):
 *   raw ebony, raw volcanic glass, and Dwemer scrap carry charter export tariffs outside licensed smiths and charter ports.</li>
 *   <li><b>Summerset Restricted Imports</b> ({@code summerset.L5.restricted_imports}, {@code summerset.L7.permit_outsiders}):
 *   foreign goods enter Summerset through permitted ports with Altmer customs duties.</li>
 * </ul>
 */
public final class TradeNetwork {
    /** Maximum hop count traced along roads, sea routes, silt striders, and caravans for imported goods. */
    public static final int MAX_IMPORT_HOPS = 3;

    /** Transport mode carrying a trade good between two settlements. */
    public enum Transport {
        LOCAL(1.0f),
        ROAD(1.08f),
        CARRIAGE(1.08f),
        SILT_STRIDER(1.07f),
        BOAT(1.05f),
        SHIP(1.06f),
        CARAVAN(1.12f);

        private final float hopCostFactor;

        Transport(float hopCostFactor) {
            this.hopCostFactor = hopCostFactor;
        }

        public float hopCostFactor() {
            return hopCostFactor;
        }
    }

    /** A directed trade link between two settlements. */
    public record TradeEdge(String fromPlaceId, String toPlaceId, Transport transport) {}

    /** Provenance and hop trace of a good available at a settlement (hops == 0 for local production). */
    public record ImportRoute(
            String itemId,
            String originPlaceId,
            String originProvince,
            int hops,
            Transport transport,
            float tariffMultiplier,
            String whyFieldId
    ) {
        public boolean isLocal() {
            return hops == 0;
        }
    }

    /** Trade profile of a single settlement in Tamriel. */
    public record SettlementNode(
            String placeId,
            String province,
            String regionId,
            String culture,
            int x,
            int z,
            boolean port,
            Set<String> localExports,
            Map<String, ImportRoute> imports,
            List<TradeEdge> edges,
            List<String> whyFields
    ) {
        /** True if this settlement produces {@code itemId} locally (0 hops). */
        public boolean exports(String itemId) {
            return localExports.contains(itemId);
        }

        /** True if this settlement either produces {@code itemId} locally or imports it within {@link #MAX_IMPORT_HOPS}. */
        public boolean hasGood(String itemId) {
            return localExports.contains(itemId) || imports.containsKey(itemId);
        }
    }

    private static final Set<String> CONTRABAND_SKOOMA = Set.of(
            "nirncraft:skooma",
            "nirncraft:moon_sugar"
    );

    private static final Set<String> CHARTER_MONOPOLY_GOODS = Set.of(
            "nirncraft:ebony_ore",
            "nirncraft:deepslate_ebony_ore",
            "nirncraft:raw_ebony",
            "nirncraft:malachite_ore",
            "nirncraft:deepslate_malachite_ore",
            "nirncraft:raw_malachite",
            "nirncraft:dwarven_scrap",
            "nirncraft:dwemer_gear",
            "nirncraft:dwarven_oil"
    );

    private static final Set<String> GREEN_PACT_PLANT_AND_TIMBER = Set.of(
            "nirncraft:sawn_log",
            "nirncraft:firewood",
            "minecraft:oak_log",
            "minecraft:spruce_log",
            "minecraft:birch_log",
            "minecraft:jungle_log",
            "minecraft:acacia_log",
            "minecraft:dark_oak_log",
            "minecraft:mangrove_log",
            "minecraft:wheat",
            "minecraft:potato",
            "minecraft:carrot",
            "minecraft:beetroot",
            "nirncraft:cabbage",
            "nirncraft:cabbage_seeds",
            "nirncraft:leek",
            "nirncraft:leek_seeds",
            "nirncraft:tomato",
            "nirncraft:tomato_seeds",
            "nirncraft:garlic"
    );

    private static final Set<String> CHARTER_PORTS = Set.of(
            "solitude", "windhelm", "dawnstar", "raven_rock",
            "ebonheart", "vivec", "seyda_neen", "dagon_fel",
            "anvil", "imperial_city", "leyawiin",
            "wayrest", "daggerfall", "sentinel", "stros_mkai",
            "senchal", "woodhearth", "haven", "shimmerene", "lillandril", "lilmoth"
    );

    private static final Set<String> SUMMERSET_PERMIT_PORTS = Set.of(
            "shimmerene", "lillandril", "firsthold", "skywatch", "alinor", "dusk"
    );

    private static final Map<String, List<String>> PROVINCE_CITATIONS = Map.ofEntries(
            Map.entry("skyrim", List.of("skyrim.L5.mining_wealth", "skyrim.L5.farm_and_mead", "skyrim.L5.timber", "skyrim.L5.sea_trade", "skyrim.L5.road_hub")),
            Map.entry("solstheim", List.of("solstheim.L5.ebony_mining", "solstheim.L5.stalhrim_craft", "solstheim.L5.hunting_and_hide", "solstheim.L5.ash_farming", "solstheim.L5.dwemer_scrap")),
            Map.entry("morrowind", List.of("morrowind.L5.kwama_eggs", "morrowind.L5.saltrice_plantations", "morrowind.L5.ebony_mining", "morrowind.L5.guar_and_netch", "morrowind.L5.east_empire_charter", "morrowind.L5.house_trade")),
            Map.entry("cyrodiil", List.of("cyrodiil.L5.grain_belt", "cyrodiil.L5.wine", "cyrodiil.L5.timber", "cyrodiil.L5.river_trade", "cyrodiil.L5.sea_trade", "cyrodiil.L5.skooma_smuggling")),
            Map.entry("elsweyr", List.of("elsweyr.L5.moon_sugar", "elsweyr.L5.skooma", "elsweyr.L5.caravans", "elsweyr.L5.herding_hides", "elsweyr.L5.moonstone", "elsweyr.L5.senchal_port")),
            Map.entry("hammerfell", List.of("hammerfell.L5.caravans_and_salt", "hammerfell.L5.dyes_and_spice", "hammerfell.L5.dragontail_ore", "hammerfell.L5.blades", "hammerfell.L5.timber_and_river_fish", "hammerfell.L5.oasis_dates")),
            Map.entry("high_rock", List.of("high_rock.L5.wool_and_hides", "high_rock.L5.grain_orchards", "high_rock.L5.fish_salt", "high_rock.L5.bay_shipping", "high_rock.L5.orc_ore", "high_rock.L5.tolls")),
            Map.entry("orsinium", List.of("high_rock.L5.orc_ore", "high_rock.L5.wool_and_hides", "high_rock.L5.tolls")),
            Map.entry("summerset", List.of("summerset.L5.wine", "summerset.L5.crystal_and_lenses", "summerset.L5.silk", "summerset.L5.ships", "summerset.L5.pearls_and_fish", "summerset.L5.restricted_imports", "summerset.L7.permit_outsiders")),
            Map.entry("valenwood", List.of("valenwood.L5.meat_and_jagga", "valenwood.L5.bone_hide_craft", "valenwood.L5.pact_imports", "valenwood.L5.ports", "valenwood.L5.pelts_and_feathers", "valenwood.L7.outsider_traders")),
            Map.entry("black_marsh", List.of("black_marsh.L5.reed_and_mud_craft", "black_marsh.L5.alchemical_reagents", "black_marsh.L5.river_barge_and_punt", "black_marsh.L5.border_timber_and_rice", "black_marsh.L5.coastal_smuggling")),
            Map.entry("akavir", List.of("foreign.L5.charter_salvage_and_treaty_trade", "foreign.L7.captains_factors_and_custodians")),
            Map.entry("atmora", List.of("foreign.L5.charter_salvage_and_treaty_trade", "foreign.L7.captains_factors_and_custodians")),
            Map.entry("yokuda", List.of("foreign.L5.charter_salvage_and_treaty_trade", "foreign.L7.captains_factors_and_custodians")),
            Map.entry("pyandonea", List.of("foreign.L5.charter_salvage_and_treaty_trade", "foreign.L7.captains_factors_and_custodians")),
            Map.entry("realm", List.of("foreign.L5.charter_salvage_and_treaty_trade"))
    );

    private static final Map<String, Set<String>> PROVINCE_EXPORTS = buildProvinceExports();

    private record Graph(
            Map<String, SettlementNode> nodes,
            Map<String, Set<String>> provinceExports
    ) {}

    private static volatile Graph GRAPH;

    private TradeNetwork() {}

    private static Graph graph() {
        Graph g = GRAPH;
        if (g != null) return g;
        synchronized (TradeNetwork.class) {
            if (GRAPH == null) {
                GRAPH = buildGraph();
            }
            return GRAPH;
        }
    }

    /** Forces a rebuild of the trade network snapshot (e.g. during self-tests). */
    public static synchronized void reload() {
        GRAPH = buildGraph();
    }

    // ------------------------------------------------------------------ public queries

    /** All settlement nodes in the Tamriel trade network. */
    public static Collection<SettlementNode> nodes() {
        return graph().nodes().values();
    }

    /** All settlement IDs in the trade network. */
    public static Set<String> settlementIds() {
        return graph().nodes().keySet();
    }

    /** Looks up a settlement node by place ID (or returns empty if not a settlement). */
    public static Optional<SettlementNode> node(String placeId) {
        if (placeId == null || placeId.isBlank()) return Optional.empty();
        String key = canonPlaceId(placeId);
        return Optional.ofNullable(graph().nodes().get(key));
    }

    /** Direct nullable settlement node lookup (alias for {@link #node(String)}). */
    @Nullable
    public static SettlementNode place(@Nullable String placeId) {
        return node(placeId).orElse(null);
    }

    /** Map of all province causal WorldSheets L5/L7 citations. */
    public static Map<String, List<String>> citations() {
        return PROVINCE_CITATIONS;
    }

    /** Nearest settlement trade node within {@code maxDist} blocks of {@code (x, z)}. */
    public static Optional<SettlementNode> nearestNode(int x, int z, int maxDist) {
        SettlementNode best = null;
        long bestD2 = (long) maxDist * maxDist;
        for (SettlementNode n : graph().nodes().values()) {
            long dx = (long) n.x() - x;
            long dz = (long) n.z() - z;
            long d2 = dx * dx + dz * dz;
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = n;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Goods produced locally by a province (gear key or province ID). */
    public static Set<String> provinceExports(String provinceOrGearKey) {
        if (provinceOrGearKey == null) return Set.of();
        String p = canonProvince(provinceOrGearKey);
        return PROVINCE_EXPORTS.getOrDefault(p, Set.of());
    }

    /** Goods produced locally at a specific settlement (0 hops). */
    public static Set<String> localExports(String placeId) {
        return node(placeId).map(SettlementNode::localExports).orElse(Set.of());
    }

    /** Goods imported into {@code placeId} along {@code 1..3} network hops. */
    public static Map<String, ImportRoute> importsFor(String placeId) {
        return node(placeId).map(SettlementNode::imports).orElse(Map.of());
    }

    /**
     * Resolves the trade provenance of {@code itemId} at {@code placeId} (or in {@code fallbackProvince} if
     * {@code placeId} is null/wilderness). Returns a 0-hop {@link ImportRoute} if produced locally.
     */
    public static Optional<ImportRoute> routeFor(@Nullable String placeId, String fallbackProvince, String itemId) {
        String prov = canonProvince(fallbackProvince);
        if (placeId != null && !placeId.isBlank()) {
            SettlementNode n = node(placeId).orElse(null);
            if (n != null) {
                if (n.localExports().contains(itemId)) {
                    return Optional.of(new ImportRoute(itemId, n.placeId(), n.province(), 0, Transport.LOCAL, 1.0f, primaryCitation(n.province())));
                }
                ImportRoute imp = n.imports().get(itemId);
                if (imp != null) return Optional.of(imp);
                prov = n.province();
            }
        }
        if (provinceExports(prov).contains(itemId)) {
            return Optional.of(new ImportRoute(itemId, placeId != null ? placeId : prov, prov, 0, Transport.LOCAL, 1.0f, primaryCitation(prov)));
        }
        // Check if another province produces this item and compute cross-province route
        for (Map.Entry<String, Set<String>> e : PROVINCE_EXPORTS.entrySet()) {
            if (e.getValue().contains(itemId)) {
                String originProv = e.getKey();
                int hops = adjacentProvinces(prov).contains(originProv) ? 1 : 2;
                Transport mode = isSeaOnly(prov, originProv) ? Transport.SHIP : Transport.CARAVAN;
                float tariff = tariffMultiplier(itemId, originProv, prov, placeId);
                return Optional.of(new ImportRoute(itemId, originProv, originProv, hops, mode, tariff, primaryCitation(originProv)));
            }
        }
        return Optional.empty();
    }

    /** Causal {@link WorldSheets} L5/L7 field IDs grounding the trade network of {@code province}. */
    public static List<String> causalCitations(String province) {
        return PROVINCE_CITATIONS.getOrDefault(canonProvince(province), List.of("foreign.L5.charter_salvage_and_treaty_trade"));
    }

    /** True when {@code itemId} is contraband in {@code province} (e.g. skooma / moon sugar outside Elsweyr). */
    public static boolean isContraband(String itemId, String province) {
        if (itemId == null) return false;
        String prov = canonProvince(province);
        if (CONTRABAND_SKOOMA.contains(itemId)) {
            return !prov.equals("elsweyr") && !prov.equals("realm");
        }
        return false;
    }

    /** 3-arg overload for {@code (province, placeId, itemId)}. */
    public static boolean isContraband(String province, @Nullable String placeId, String itemId) {
        return isContraband(itemId, province);
    }

    /** True when {@code itemId} is subject to the East Empire Company / Imperial charter monopoly. */
    public static boolean isCharterMonopoly(String itemId) {
        return itemId != null && CHARTER_MONOPOLY_GOODS.contains(itemId);
    }

    /** True when {@code itemId} violates Valenwood's Green Pact if harvested/produced locally in Valenwood. */
    public static boolean isGreenPactViolation(String itemId, String province) {
        if (itemId == null) return false;
        return "valenwood".equals(canonProvince(province)) && GREEN_PACT_PLANT_AND_TIMBER.contains(itemId);
    }

    /** 3-arg overload for {@code (province, placeId, itemId)}. */
    public static boolean isGreenPactViolation(String province, @Nullable String placeId, String itemId) {
        return isGreenPactViolation(itemId, province);
    }

    /**
     * Whether a merchant of type {@code shopType} in {@code province} (at optional {@code placeId}) may stock {@code itemId}
     * on their shelf under causal L7 trade laws and embargoes.
     */
    public static boolean mayStock(ShopType shopType, String province, @Nullable String placeId, String itemId) {
        String prov = canonProvince(province);
        if (isContraband(itemId, prov)) {
            // Skooma and moon sugar outside Elsweyr are sold ONLY by fences and Khajiit caravan / exotic merchants
            return shopType == ShopType.FENCE || shopType == ShopType.EXOTIC;
        }
        if (isGreenPactViolation(itemId, prov)) {
            // Bosmer food, hunter, and apothecary stalls in Valenwood never stock local timber or tilled crops;
            // only general/exotic/mason traders carry imported foreign timber or grain.
            return shopType == ShopType.GENERAL || shopType == ShopType.EXOTIC || shopType == ShopType.MASON;
        }
        if (isCharterMonopoly(itemId)) {
            boolean charterPort = placeId != null && CHARTER_PORTS.contains(canonPlaceId(placeId));
            if (charterPort) {
                return shopType == ShopType.BLACKSMITH || shopType == ShopType.GENERAL
                        || shopType == ShopType.EXOTIC || shopType == ShopType.FENCE || shopType == ShopType.ARCANE;
            }
            return shopType == ShopType.BLACKSMITH || shopType == ShopType.FENCE
                    || shopType == ShopType.EXOTIC || shopType == ShopType.ARCANE;
        }
        return true;
    }

    /**
     * Computes the L7 tariff / embargo / smuggling risk multiplier on {@code itemId} shipped from
     * {@code originProvince} into {@code destProvince} (at {@code destPlaceId}).
     */
    public static float tariffMultiplier(String itemId, String originProvince, String destProvince, @Nullable String destPlaceId) {
        String orig = canonProvince(originProvince);
        String dest = canonProvince(destProvince);
        float mult = 1.0f;

        if (isContraband(itemId, dest)) {
            // Smuggling risk premium across Imperial/Provincial borders
            mult *= 1.75f;
        }
        if (isCharterMonopoly(itemId)) {
            boolean charterPort = destPlaceId != null && CHARTER_PORTS.contains(canonPlaceId(destPlaceId));
            if (!orig.equals(dest)) {
                mult *= charterPort ? 1.30f : 1.55f;
            } else if (!charterPort && ("morrowind".equals(dest) || "solstheim".equals(dest))) {
                mult *= 1.25f;
            }
        }
        if (isGreenPactViolation(itemId, dest)) {
            // Imported foreign timber and grain under Valenwood's Pact import rules
            mult *= 1.45f;
        }
        if ("summerset".equals(dest) && !orig.equals("summerset")) {
            // Summerset restricted foreign imports (summerset.L5.restricted_imports)
            boolean permitPort = destPlaceId != null && SUMMERSET_PERMIT_PORTS.contains(canonPlaceId(destPlaceId));
            mult *= permitPort ? 1.25f : 1.40f;
        }
        if ("high_rock".equals(dest) && !orig.equals("high_rock")) {
            // High Rock feudal tolls (high_rock.L5.tolls)
            mult *= 1.10f;
        }
        return mult;
    }

    // ------------------------------------------------------------------ graph construction

    private static Graph buildGraph() {
        Map<String, Place> settlements = new LinkedHashMap<>();
        for (Place p : Places.all()) {
            if (p.type().isSettlement() || "homestead".equals(p.variant())) {
                if (p.type().isSettlement()) {
                    settlements.put(p.id(), p);
                }
            }
        }

        // Collect ports by place ID
        Set<String> portPlaces = new LinkedHashSet<>(CHARTER_PORTS);
        Map<String, String> portIdToPlace = new LinkedHashMap<>();
        for (Port port : Ports.all()) {
            if (port.place() != null && !port.place().isBlank()) {
                String pid = canonPlaceId(port.place());
                portPlaces.add(pid);
                portIdToPlace.put(port.id(), pid);
            }
            if (settlements.containsKey(port.id())) {
                portPlaces.add(port.id());
                portIdToPlace.put(port.id(), port.id());
            }
        }

        // Adjacency list: placeId -> Map<neighborPlaceId, Transport>
        Map<String, Map<String, Transport>> adj = new LinkedHashMap<>();
        for (String id : settlements.keySet()) {
            adj.put(id, new LinkedHashMap<>());
        }

        // 1. RoadPlan links
        try {
            RoadPlan rp = RoadPlan.get();
            if (rp != null && rp.linkA != null) {
                for (int i = 0; i < rp.linkA.length; i++) {
                    if (rp.linkStatus != null && rp.linkStatus[i] == RoadPlan.LINK_FAILED) continue;
                    String a = canonPlaceId(rp.linkA[i]);
                    String b = canonPlaceId(rp.linkB[i]);
                    if (settlements.containsKey(a) && settlements.containsKey(b) && !a.equals(b)) {
                        addEdge(adj, a, b, Transport.ROAD);
                        addEdge(adj, b, a, Transport.ROAD);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fallback if RoadPlan is not baked in minimal unit test context
        }

        // 2. TravelNetworks routes (carriage, silt strider, boat, river_boat, barge, caravan)
        try {
            for (TravelRoute r : TravelNetworks.allRoutes()) {
                String a = resolveSettlementId(r.from(), settlements);
                String b = resolveSettlementId(r.to(), settlements);
                if (a == null || b == null || a.equals(b)) continue;
                Transport t = mapTransport(r.mode());
                addEdge(adj, a, b, t);
                if (r.bidirectional()) addEdge(adj, b, a, t);
            }
        } catch (Throwable ignored) {
        }

        // 3. Ports sea routes
        try {
            for (Route r : Ports.routes()) {
                String a = portIdToPlace.getOrDefault(r.a(), canonPlaceId(r.a()));
                String b = portIdToPlace.getOrDefault(r.b(), canonPlaceId(r.b()));
                if (settlements.containsKey(a) && settlements.containsKey(b) && !a.equals(b)) {
                    addEdge(adj, a, b, Transport.SHIP);
                    addEdge(adj, b, a, Transport.SHIP);
                }
            }
        } catch (Throwable ignored) {
        }

        // 4. Inter-province caravan & sea trunk corridors ensuring every province's capitals and ports are connected
        String[][] trunks = {
                // Sea lanes (East Empire Company & coastal shipping)
                {"solitude", "daggerfall", "SHIP"},
                {"solitude", "wayrest", "SHIP"},
                {"solitude", "sentinel", "SHIP"},
                {"solitude", "anvil", "SHIP"},
                {"solitude", "dawnstar", "BOAT"},
                {"dawnstar", "windhelm", "BOAT"},
                {"windhelm", "raven_rock", "SHIP"},
                {"raven_rock", "ebonheart", "SHIP"},
                {"raven_rock", "vivec", "SHIP"},
                {"raven_rock", "dagon_fel", "BOAT"},
                {"vivec", "ebonheart", "BOAT"},
                {"vivec", "sadrith_mora", "SHIP"},
                {"ebonheart", "mournhold", "ROAD"},
                {"ebonheart", "stormhold", "CARAVAN"},
                {"stormhold", "lilmoth", "BOAT"},
                {"lilmoth", "leyawiin", "SHIP"},
                {"leyawiin", "senchal", "SHIP"},
                {"leyawiin", "bravil", "BOAT"},
                {"bravil", "imperial_city", "BOAT"},
                {"senchal", "torval", "CARAVAN"},
                {"senchal", "anvil", "SHIP"},
                {"senchal", "woodhearth", "SHIP"},
                {"woodhearth", "elden_root", "CARAVAN"},
                {"woodhearth", "alinor", "SHIP"},
                {"woodhearth", "lillandril", "SHIP"},
                {"alinor", "shimmerene", "ROAD"},
                {"shimmerene", "skywatch", "SHIP"},
                {"skywatch", "firsthold", "ROAD"},
                {"firsthold", "anvil", "SHIP"},
                {"anvil", "kvatch", "ROAD"},
                {"kvatch", "skingrad", "ROAD"},
                {"skingrad", "imperial_city", "ROAD"},
                {"imperial_city", "chorrol", "ROAD"},
                {"imperial_city", "cheydinhal", "ROAD"},
                {"imperial_city", "bruma", "ROAD"},
                {"bruma", "falkreath", "CARAVAN"},
                {"falkreath", "whiterun", "ROAD"},
                {"whiterun", "windhelm", "ROAD"},
                {"whiterun", "riften", "ROAD"},
                {"whiterun", "markarth", "ROAD"},
                {"whiterun", "solitude", "ROAD"},
                {"whiterun", "morthal", "ROAD"},
                {"whiterun", "dawnstar", "ROAD"},
                {"windhelm", "winterhold", "ROAD"},
                {"riften", "cheydinhal", "CARAVAN"},
                {"riften", "mournhold", "CARAVAN"},
                {"markarth", "orsinium", "CARAVAN"},
                {"markarth", "elinhir", "CARAVAN"},
                {"chorrol", "elinhir", "CARAVAN"},
                {"anvil", "rihad", "ROAD"},
                {"rihad", "taneth", "ROAD"},
                {"taneth", "hegathe", "ROAD"},
                {"hegathe", "sentinel", "SHIP"},
                {"sentinel", "wayrest", "SHIP"},
                {"wayrest", "daggerfall", "SHIP"},
                {"wayrest", "orsinium", "CARAVAN"},
                {"wayrest", "shornhelm", "ROAD"},
                {"daggerfall", "camlorn", "ROAD"},
                {"shornhelm", "evermor", "ROAD"},
                {"evermor", "elinhir", "CARAVAN"},
                {"torval", "rimmen", "CARAVAN"},
                {"rimmen", "riverhold", "CARAVAN"},
                {"riverhold", "bravil", "CARAVAN"},
                {"riverhold", "elden_root", "CARAVAN"},
                {"balmora", "vivec", "SILT_STRIDER"},
                {"balmora", "aldruhn", "SILT_STRIDER"},
                {"aldruhn", "sadrith_mora", "BOAT"}
        };
        for (String[] t : trunks) {
            String a = t[0], b = t[1];
            Transport mode = Transport.valueOf(t[2]);
            if (settlements.containsKey(a) && settlements.containsKey(b)) {
                addEdge(adj, a, b, mode);
                addEdge(adj, b, a, mode);
            }
        }

        // Ensure every settlement in a region connects to its nearest regional neighbor or capital
        for (Place p : settlements.values()) {
            Map<String, Transport> neighbors = adj.get(p.id());
            if (neighbors != null && neighbors.isEmpty()) {
                Place nearest = null;
                long bestD2 = Long.MAX_VALUE;
                for (Place other : settlements.values()) {
                    if (other.id().equals(p.id())) continue;
                    long d2 = p.distSqr(other.x(), other.z());
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        nearest = other;
                    }
                }
                if (nearest != null) {
                    addEdge(adj, p.id(), nearest.id(), Transport.ROAD);
                    addEdge(adj, nearest.id(), p.id(), Transport.ROAD);
                }
            }
        }

        // Compute local exports for each settlement
        Map<String, Set<String>> localByPlace = new LinkedHashMap<>();
        Map<String, String> provByPlace = new LinkedHashMap<>();
        for (Place p : settlements.values()) {
            String prov = provinceOfPlace(p);
            provByPlace.put(p.id(), prov);
            localByPlace.put(p.id(), computeLocalExports(p, prov, portPlaces.contains(p.id())));
        }

        // BFS up to MAX_IMPORT_HOPS (3) for each settlement to populate imports
        Map<String, SettlementNode> nodes = new LinkedHashMap<>();
        for (Place p : settlements.values()) {
            String id = p.id();
            String prov = provByPlace.get(id);
            Set<String> local = localByPlace.get(id);
            Map<String, ImportRoute> imports = new LinkedHashMap<>();

            Map<String, Integer> dist = new LinkedHashMap<>();
            Map<String, Transport> firstTransport = new LinkedHashMap<>();
            Deque<String> queue = new ArrayDeque<>();
            dist.put(id, 0);
            queue.add(id);

            while (!queue.isEmpty()) {
                String cur = queue.poll();
                int d = dist.get(cur);
                if (d >= MAX_IMPORT_HOPS) continue;
                Map<String, Transport> nbrs = adj.getOrDefault(cur, Map.of());
                for (Map.Entry<String, Transport> e : nbrs.entrySet()) {
                    String nxt = e.getKey();
                    if (dist.containsKey(nxt)) continue;
                    int nd = d + 1;
                    dist.put(nxt, nd);
                    Transport tr = d == 0 ? e.getValue() : firstTransport.get(cur);
                    firstTransport.put(nxt, tr);
                    queue.add(nxt);

                    String origProv = provByPlace.getOrDefault(nxt, prov);
                    for (String item : localByPlace.getOrDefault(nxt, Set.of())) {
                        if (local.contains(item) || imports.containsKey(item)) continue;
                        float tariff = tariffMultiplier(item, origProv, prov, id);
                        imports.put(item, new ImportRoute(item, nxt, origProv, nd, tr, tariff, primaryCitation(origProv)));
                    }
                }
            }

            List<TradeEdge> edges = new ArrayList<>();
            for (Map.Entry<String, Transport> e : adj.getOrDefault(id, Map.of()).entrySet()) {
                edges.add(new TradeEdge(id, e.getKey(), e.getValue()));
            }
            nodes.put(id, new SettlementNode(
                    id,
                    prov,
                    p.regionId(),
                    p.culture(),
                    p.x(),
                    p.z(),
                    portPlaces.contains(id),
                    Collections.unmodifiableSet(local),
                    Collections.unmodifiableMap(imports),
                    List.copyOf(edges),
                    causalCitations(prov)
            ));
        }

        return new Graph(Collections.unmodifiableMap(nodes), PROVINCE_EXPORTS);
    }

    private static Set<String> computeLocalExports(Place p, String prov, boolean isPort) {
        Set<String> out = new LinkedHashSet<>(PROVINCE_EXPORTS.getOrDefault(prov, Set.of()));
        String reg = p.regionId() == null ? "" : p.regionId().toLowerCase(Locale.ROOT);
        String id = p.id().toLowerCase(Locale.ROOT);

        // Hold / sub-region causal specializations
        if (reg.contains("the_reach") || id.equals("markarth") || id.equals("karthwasten")) {
            out.add("nirncraft:silver_ore");
            out.add("nirncraft:silver_ingot");
            out.add("nirncraft:raw_silver");
            out.add("nirncraft:dwarven_scrap");
            out.add("nirncraft:dwarven_metal_ingot");
            out.add("nirncraft:juniper_berries");
            out.add("nirncraft:briar_heart");
        } else if (reg.contains("the_rift") || id.equals("riften") || id.equals("ivars_stead") || id.equals("shors_stone")) {
            out.add("nirncraft:black_briar_mead");
            out.add("minecraft:honey_bottle");
            out.add("nirncraft:salmon");
            out.add("nirncraft:river_betty");
            out.add("nirncraft:mora_tapinella");
            out.add("nirncraft:ebony_ore");
        } else if (reg.contains("whiterun") || id.equals("whiterun") || id.equals("rorikstead") || id.equals("riverwood")) {
            out.add("nirncraft:honningbrew_mead");
            out.add("nirncraft:nord_mead");
            out.add("minecraft:wheat");
            out.add("nirncraft:cabbage");
            out.add("nirncraft:leek");
            out.add("nirncraft:mammoth_steak");
            out.add("nirncraft:cheese_wheel");
            out.add("nirncraft:sawn_log");
        } else if (reg.contains("haafingar") || reg.contains("eastmarch") || reg.contains("the_pale") || reg.contains("winterhold")) {
            out.add("nirncraft:horker_loaf");
            out.add("nirncraft:horker_stew");
            out.add("nirncraft:raw_horker_meat");
            out.add("nirncraft:horker_tusk");
            out.add("nirncraft:arctic_char");
            out.add("nirncraft:quicksilver_ore");
            out.add("nirncraft:quicksilver_ingot");
            out.add("nirncraft:jazbay_grapes");
        } else if (reg.contains("falkreath") || reg.contains("hjaalmarch")) {
            out.add("nirncraft:sawn_log");
            out.add("nirncraft:firewood");
            out.add("nirncraft:raw_venison");
            out.add("nirncraft:venison_stew");
            out.add("nirncraft:deathbell");
            out.add("nirncraft:swamp_fungal_pod");
        }

        // what the land around the place makes: honey where it keeps hives (same rule the planner uses for its apiary)
        LocalProduce.addTo(out, p.id(), p.culture());
        if (isPort && !"valenwood".equals(prov)) {
            out.add("nirncraft:small_pearl");
            out.add("nirncraft:salt_pile");
        }
        if ("valenwood".equals(prov)) {
            // Strictly enforce Green Pact: zero local plant or timber exports in Valenwood
            out.removeAll(GREEN_PACT_PLANT_AND_TIMBER);
        }
        return out;
    }

    private static Map<String, Set<String>> buildProvinceExports() {
        Map<String, Set<String>> m = new LinkedHashMap<>();

        m.put("skyrim", Set.of(
                "nirncraft:nord_mead", "nirncraft:honningbrew_mead", "nirncraft:black_briar_mead",
                "nirncraft:horker_loaf", "nirncraft:horker_stew", "nirncraft:raw_horker_meat", "nirncraft:horker_tusk",
                "nirncraft:mammoth_steak", "nirncraft:raw_mammoth_snout", "nirncraft:mammoth_tusk", "nirncraft:powdered_mammoth_tusk",
                "nirncraft:cheese_wheel", "nirncraft:eidar_cheese_wedge", "nirncraft:goat_cheese_wedge",
                "nirncraft:cabbage", "nirncraft:leek", "nirncraft:grilled_leeks", "nirncraft:apple_cabbage_stew",
                "nirncraft:raw_venison", "nirncraft:venison_chop", "nirncraft:venison_stew",
                "nirncraft:salmon", "nirncraft:salmon_steak", "nirncraft:arctic_char", "nirncraft:brook_trout",
                "nirncraft:silver_ore", "nirncraft:raw_silver", "nirncraft:silver_ingot",
                "nirncraft:corundum_ore", "nirncraft:raw_corundum", "nirncraft:corundum_ingot",
                "minecraft:iron_ingot", "minecraft:raw_iron", "nirncraft:steel_ingot",
                "nirncraft:sawn_log", "nirncraft:firewood", "nirncraft:wolf_pelt", "nirncraft:hide",
                "nirncraft:nordic_barnacle", "nirncraft:juniper_berries", "nirncraft:frost_mirriam"
        ));

        m.put("solstheim", Set.of(
                "nirncraft:ebony_ore", "nirncraft:raw_ebony", "nirncraft:ebony_ingot",
                "nirncraft:stalhrim", "nirncraft:chitin_plate", "nirncraft:netch_leather", "nirncraft:netch_jelly",
                "nirncraft:ash_yam", "nirncraft:trama_root", "nirncraft:ashen_grass_pod",
                "nirncraft:ash_creep_cluster", "nirncraft:ash_hopper_jelly", "nirncraft:spawn_ash",
                "nirncraft:boar_tusk", "nirncraft:horker_tusk", "nirncraft:horker_loaf", "nirncraft:nord_mead",
                "nirncraft:dwarven_scrap", "nirncraft:dwemer_gear"
        ));

        m.put("morrowind", Set.of(
                "nirncraft:kwama_egg", "nirncraft:saltrice", "nirncraft:ash_yam",
                "nirncraft:comberry", "nirncraft:marshmerrow", "nirncraft:scrib_jelly",
                "nirncraft:trama_root", "nirncraft:ashen_grass_pod", "nirncraft:ash_creep_cluster",
                "nirncraft:ash_hopper_jelly", "nirncraft:ash_salts", "nirncraft:bittergreen_petals",
                "nirncraft:hypha_facia", "nirncraft:gold_kanet", "nirncraft:emperor_parasol_moss",
                "nirncraft:telvanni_cap", "nirncraft:parasol_cap", "nirncraft:glowing_mushroom",
                "nirncraft:ebony_ore", "nirncraft:raw_ebony", "nirncraft:ebony_ingot",
                "nirncraft:malachite_ore", "nirncraft:raw_malachite", "nirncraft:refined_malachite",
                "nirncraft:netch_leather", "nirncraft:netch_jelly", "nirncraft:chitin_plate",
                "nirncraft:guar_hide", "nirncraft:alit_hide", "nirncraft:kagouti_hide",
                "nirncraft:cliff_racer_plumes", "nirncraft:nix_hound_meat", "nirncraft:dreugh_wax",
                "nirncraft:dwarven_scrap", "nirncraft:dwemer_gear", "nirncraft:dwarven_metal_ingot", "nirncraft:dwarven_oil",
                "nirncraft:ash_perch", "nirncraft:telvanni_glowfish", "nirncraft:bittergreen_eel"
        ));

        m.put("cyrodiil", Set.of(
                "nirncraft:alto_wine", "nirncraft:spiced_wine", "nirncraft:jazbay_grapes",
                "minecraft:wheat", "minecraft:bread", "nirncraft:sweetroll", "nirncraft:garlic_bread",
                "nirncraft:garlic", "nirncraft:tomato", "nirncraft:tomato_soup", "nirncraft:vegetable_soup",
                "nirncraft:beef_stew", "nirncraft:eidar_cheese_wedge", "nirncraft:goat_cheese_wedge", "nirncraft:cheese_wheel",
                "nirncraft:steel_ingot", "nirncraft:corundum_ingot", "nirncraft:silver_ingot", "minecraft:iron_ingot",
                "nirncraft:sawn_log", "nirncraft:firewood", "nirncraft:elves_ear",
                "nirncraft:cyrodilic_spadetail", "nirncraft:niben_eel", "nirncraft:rumare_bass",
                "nirncraft:colovian_trout", "nirncraft:gold_coast_snapper", "nirncraft:abecean_longfin"
        ));

        m.put("elsweyr", Set.of(
                "nirncraft:moon_sugar", "nirncraft:skooma", "minecraft:sugar", "minecraft:sugar_cane",
                "nirncraft:elsweyr_fondue", "nirncraft:spiced_wine", "nirncraft:honey_nut_treat",
                "minecraft:cocoa_beans", "nirncraft:jazbay_grapes",
                "nirncraft:moonstone_ore", "nirncraft:raw_moonstone", "nirncraft:refined_moonstone",
                "nirncraft:sabre_cat_tooth", "nirncraft:eye_of_sabre_cat", "nirncraft:scorpion_stinger",
                "nirncraft:senchal_snapper", "nirncraft:riverhold_catfish", "nirncraft:sugar_koi"
        ));

        m.put("hammerfell", Set.of(
                "nirncraft:salt_pile", "nirncraft:spiced_wine", "minecraft:sugar", "minecraft:cocoa_beans",
                "nirncraft:steel_ingot", "nirncraft:corundum_ingot", "nirncraft:raw_orichalcum", "nirncraft:orichalcum_ingot",
                "nirncraft:refined_malachite", "nirncraft:pearl", "nirncraft:small_pearl",
                "nirncraft:scorpion_stinger", "nirncraft:harpy_feather", "nirncraft:sea_serpent_scale",
                "nirncraft:stros_mkai_marlin", "nirncraft:dune_minnow", "nirncraft:gold_coast_snapper",
                "nirncraft:leg_of_goat_roast", "nirncraft:raw_goat_meat"
        ));

        m.put("high_rock", Set.of(
                "minecraft:white_wool", "minecraft:blue_wool", "minecraft:red_wool",
                "nirncraft:spiced_wine", "nirncraft:alto_wine", "nirncraft:goat_cheese_wedge", "nirncraft:sliced_goat_cheese",
                "nirncraft:boiled_creme_treat", "nirncraft:sweetroll", "minecraft:apple", "minecraft:bread",
                "nirncraft:silver_ore", "nirncraft:raw_silver", "nirncraft:silver_ingot", "nirncraft:steel_ingot",
                "nirncraft:orichalcum_ingot", "nirncraft:juniper_berries", "nirncraft:hagraven_feathers", "nirncraft:hagraven_claw",
                "nirncraft:iliac_tuna", "nirncraft:glenumbra_herring", "nirncraft:bjoulsae_trout"
        ));

        m.put("orsinium", Set.of(
                "nirncraft:orichalcum_ore", "nirncraft:deepslate_orichalcum_ore", "nirncraft:raw_orichalcum", "nirncraft:orichalcum_ingot",
                "nirncraft:steel_ingot", "minecraft:iron_ingot",
                "nirncraft:echatere_hide", "nirncraft:durzog_hide", "nirncraft:troll_fat", "nirncraft:hide",
                "nirncraft:leg_of_goat_roast", "nirncraft:venison_chop", "nirncraft:wrothgar_char"
        ));

        m.put("summerset", Set.of(
                "nirncraft:moonstone_ore", "nirncraft:raw_moonstone", "nirncraft:refined_moonstone",
                "nirncraft:malachite_ore", "nirncraft:raw_malachite", "nirncraft:refined_malachite",
                "nirncraft:quicksilver_ore", "nirncraft:raw_quicksilver", "nirncraft:quicksilver_ingot",
                "nirncraft:crystal_cluster", "nirncraft:crystal_block",
                "nirncraft:alto_wine", "nirncraft:spiced_wine", "nirncraft:jazbay_grapes",
                "nirncraft:pearl", "nirncraft:small_pearl", "nirncraft:elves_ear",
                "nirncraft:soul_gem_petty", "nirncraft:soul_gem_lesser", "nirncraft:soul_gem_common",
                "nirncraft:alinor_sunfish", "nirncraft:auridon_silverfin", "nirncraft:artaeum_moonfish"
        ));

        m.put("valenwood", Set.of(
                "nirncraft:raw_venison", "nirncraft:venison_chop", "nirncraft:venison_stew",
                "nirncraft:raw_goat_meat", "nirncraft:leg_of_goat_roast", "minecraft:cooked_mutton",
                "nirncraft:hide", "nirncraft:leather_strips", "minecraft:leather", "minecraft:bone",
                "nirncraft:indrik_feather", "nirncraft:small_antlers", "nirncraft:large_antlers",
                "minecraft:honey_bottle", "minecraft:honeycomb", "nirncraft:honey_nut_treat", "nirncraft:ale",
                "nirncraft:valenwood_tigerfish", "nirncraft:greenshade_glimmerfish", "nirncraft:woodhearth_mackerel"
        ));

        m.put("black_marsh", Set.of(
                "nirncraft:hist_sap", "nirncraft:hist_sap_block", "nirncraft:marshmerrow", "nirncraft:swamp_reeds",
                "nirncraft:bittergreen_petals", "nirncraft:swamp_fungal_pod", "nirncraft:giant_lichen",
                "nirncraft:deathbell", "nirncraft:nightshade", "nirncraft:poison_bloom", "nirncraft:imp_stool",
                "nirncraft:wamasu_scale", "nirncraft:leviathan_tooth", "nirncraft:chitin_plate",
                "nirncraft:mudcrab_chitin", "nirncraft:slaughterfish_scales", "nirncraft:small_pearl",
                "nirncraft:histcarp", "nirncraft:marsh_gar", "nirncraft:murkmire_mudskipper", "nirncraft:bloodfin"
        ));

        m.put("akavir", Set.of(
                "nirncraft:tsaesci_scale", "nirncraft:kamal_horn", "nirncraft:pearl", "nirncraft:small_pearl",
                "nirncraft:steel_ingot", "nirncraft:refined_moonstone", "nirncraft:akaviri_koi", "nirncraft:tsaesci_eel"
        ));

        m.put("atmora", Set.of(
                "nirncraft:horker_tusk", "nirncraft:horker_loaf", "nirncraft:frost_salts", "nirncraft:ice_wraith_teeth",
                "nirncraft:stalhrim", "nirncraft:nord_mead", "nirncraft:atmoran_icefish"
        ));

        m.put("yokuda", Set.of(
                "nirncraft:pearl", "nirncraft:small_pearl", "nirncraft:sea_serpent_scale", "nirncraft:salt_pile",
                "nirncraft:steel_ingot", "nirncraft:corundum_ingot", "nirncraft:yokudan_sailfin"
        ));

        m.put("pyandonea", Set.of(
                "nirncraft:sea_serpent_scale", "nirncraft:pearl", "nirncraft:small_pearl", "nirncraft:dreugh_wax",
                "nirncraft:maormer_serpentfish", "nirncraft:thras_coralfish"
        ));

        m.put("realm", Set.of(
                "nirncraft:void_salts", "nirncraft:fire_salts", "nirncraft:frost_salts", "nirncraft:daedra_heart",
                "nirncraft:ectoplasm", "nirncraft:scamp_skin", "nirncraft:soul_gem_black"
        ));

        return Collections.unmodifiableMap(m);
    }

    private static Set<String> adjacentProvinces(String prov) {
        return switch (prov) {
            case "skyrim" -> Set.of("solstheim", "morrowind", "cyrodiil", "hammerfell", "high_rock", "orsinium", "atmora");
            case "solstheim" -> Set.of("skyrim", "morrowind");
            case "morrowind" -> Set.of("solstheim", "skyrim", "cyrodiil", "black_marsh", "akavir");
            case "cyrodiil" -> Set.of("skyrim", "morrowind", "black_marsh", "elsweyr", "valenwood", "hammerfell", "high_rock");
            case "elsweyr" -> Set.of("cyrodiil", "valenwood", "black_marsh");
            case "valenwood" -> Set.of("cyrodiil", "elsweyr", "summerset");
            case "summerset" -> Set.of("valenwood", "cyrodiil", "hammerfell", "pyandonea");
            case "hammerfell" -> Set.of("cyrodiil", "skyrim", "high_rock", "orsinium", "summerset", "yokuda");
            case "high_rock", "orsinium" -> Set.of("skyrim", "hammerfell", "cyrodiil", "high_rock", "orsinium");
            case "black_marsh" -> Set.of("morrowind", "cyrodiil", "elsweyr");
            default -> Set.of("skyrim", "cyrodiil", "morrowind", "hammerfell", "summerset");
        };
    }

    private static boolean isSeaOnly(String a, String b) {
        Set<String> islands = Set.of("solstheim", "summerset", "akavir", "atmora", "yokuda", "pyandonea");
        return islands.contains(a) || islands.contains(b);
    }

    private static String primaryCitation(String prov) {
        List<String> cites = causalCitations(prov);
        return cites.isEmpty() ? "foreign.L5.charter_salvage_and_treaty_trade" : cites.get(0);
    }

    private static void addEdge(Map<String, Map<String, Transport>> adj, String from, String to, Transport t) {
        Map<String, Transport> m = adj.get(from);
        if (m == null) return;
        Transport existing = m.get(to);
        if (existing == null || t.hopCostFactor() < existing.hopCostFactor()) {
            m.put(to, t);
        }
    }

    @Nullable
    private static String resolveSettlementId(String nodeOrPlaceId, Map<String, Place> settlements) {
        String id = canonPlaceId(nodeOrPlaceId);
        if (settlements.containsKey(id)) return id;
        Optional<TravelNode> n = TravelNetworks.node(id);
        if (n.isPresent() && n.get().place() != null) {
            String pid = canonPlaceId(n.get().place());
            if (settlements.containsKey(pid)) return pid;
        }
        return null;
    }

    private static Transport mapTransport(TravelMode mode) {
        if (mode == null) return Transport.ROAD;
        return switch (mode) {
            case BOAT, RIVER_BOAT, BARGE -> Transport.BOAT;
            case STRIDER -> Transport.SILT_STRIDER;
            case CARAVAN -> Transport.CARAVAN;
            case CARRIAGE -> Transport.CARRIAGE;
            default -> Transport.ROAD;
        };
    }

    private static String provinceOfPlace(Place p) {
        String r = p.regionId();
        if (r != null && !r.isBlank()) {
            if (r.contains("solstheim")) return "solstheim";
            if (r.contains("wrothgar") || p.id().equals("orsinium")) return "orsinium";
            int slash = r.indexOf('/');
            if (slash > 0) return canonProvince(r.substring(0, slash));
            Region reg = com.nirncraft.tamriel.Regions.get(r);
            if (reg != null) return canonProvince(reg.province());
        }
        return "skyrim";
    }

    public static String canonPlaceId(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "ald_ruhn" -> "aldruhn";
            case "frostmoth" -> "fort_frostmoth";
            default -> s;
        };
    }

    public static String canonProvince(String raw) {
        if (raw == null || raw.isBlank()) return "skyrim";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.contains("solstheim")) return "solstheim";
        if (s.contains("wrothgar") || s.equals("orsinium")) return "orsinium";
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        return switch (s) {
            case "sk" -> "skyrim";
            case "so" -> "solstheim";
            case "mw" -> "morrowind";
            case "cy" -> "cyrodiil";
            case "el" -> "elsweyr";
            case "hf" -> "hammerfell";
            case "hr" -> "high_rock";
            case "vw" -> "valenwood";
            case "si" -> "summerset";
            case "bm" -> "black_marsh";
            default -> s;
        };
    }
}
