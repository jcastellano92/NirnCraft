package com.skycraft.travel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.inventory.CarryWeight;
import com.skycraft.network.NotifyKind;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perf.Prewarm;
import com.skycraft.roads.Carriages;
import com.skycraft.sailing.Ferries;
import com.skycraft.sailing.Port;
import com.skycraft.sailing.Ports;
import com.skycraft.sailing.Route;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.world.WorldSheets;
import com.skycraft.vitals.Vitals;
import com.skycraft.world.FastTravelHandler;
import com.skycraft.world.LocationKind;
import com.skycraft.world.RestManager;
import com.skycraft.world.WorldConfig;
import com.skycraft.world.WorldData;
import com.skycraft.world.WorldPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified travel network engine (MS11).
 *
 * <p>Loads provincial travel tables from {@code /skycraft/travel/<province>.json} (starting with Skyrim), drives
 * carriages, silt striders, sea ferries, river boats, barges, trader caravans, Mages Guild guides, Dwemer Great Lifts,
 * propylon chambers, interventions, and wayshrines, and enforces wilderness node coverage ({@code p90 <= 500},
 * {@code max <= 800} blocks) with free discovery and minor blessing refresh on visit.</p>
 */
public final class TravelNetworks {
    public static final List<String> PROVINCE_IDS = List.of(
            "skyrim", "solstheim", "morrowind", "cyrodiil", "high_rock", "hammerfell",
            "summerset", "valenwood", "elsweyr", "black_marsh",
            "atmora", "yokuda", "akavir", "pyandonea", "thras"
    );

    private static final String[] PROVINCES = PROVINCE_IDS.toArray(String[]::new);

    private static final int WILDERNESS_VISIT_RADIUS = 18;
    private static final long BLESSING_COOLDOWN_TICKS = 3600L; // 3 minutes per node visit

    /** Per-(player,node) cooldown for wilderness blessing refresh on visit. */
    private static final Map<String, Long> VISIT_COOLDOWNS = new ConcurrentHashMap<>();

    public record ProvinceNetwork(
            String province,
            List<String> why,
            List<TravelMode> modes,
            List<TravelNode> nodes,
            List<TravelRoute> routes
    ) {}

    public record Offer(
            TravelNode origin,
            TravelNode destination,
            TravelRoute route,
            TravelMode mode,
            int fee,
            int durationSeconds,
            String path
    ) {
        public double distance() {
            return Math.hypot(destination.x() - origin.x(), destination.z() - origin.z());
        }

        public int hours() {
            return Carriages.estimateHours(distance());
        }

        public String formattedDistance() {
            return Carriages.formatDistance(distance());
        }

        public String formattedDuration() {
            return Carriages.formatDuration(distance());
        }

        public String formatSummary() {
            return String.format(
                    Locale.ROOT,
                    "%s (%s, %s) \u2014 %dg",
                    destination.id(),
                    formattedDistance(),
                    formattedDuration(),
                    fee
            );
        }
    }

    public record HoldCoverage(
            String regionId,
            int landCells,
            double areaKm2,
            double p50,
            double p90,
            double max
    ) {}

    public record CoverageStats(
            String province,
            int landCells,
            double areaKm2,
            int totalNodes,
            int wildernessNodes,
            double nodesPerKm2,
            double wildernessNodesPerKm2,
            double p50,
            double p90,
            double max,
            Map<String, HoldCoverage> byRegion
    ) {}

    public record SelftestResult(
            int passed,
            int total,
            int problems,
            List<String> details,
            CoverageStats skyrimCoverage
    ) {}

    private record State(
            Map<String, ProvinceNetwork> provinces,
            Map<String, TravelNode> byId,
            Map<String, TravelNode> byPlace,
            List<TravelNode> allNodes,
            List<TravelRoute> allRoutes
    ) {}

    private static volatile State STATE;

    private TravelNetworks() {}

    public static void init() {
        state();
    }

    public static synchronized void reload() {
        STATE = buildState();
    }

    private static State state() {
        State s = STATE;
        if (s != null) return s;
        synchronized (TravelNetworks.class) {
            if (STATE == null) {
                STATE = buildState();
            }
            return STATE;
        }
    }

    private static State buildState() {
        Map<String, ProvinceNetwork> provinces = new LinkedHashMap<>();
        Map<String, TravelNode> byId = new LinkedHashMap<>();
        Map<String, TravelNode> byPlace = new LinkedHashMap<>();
        List<TravelNode> allNodes = new ArrayList<>();
        List<TravelRoute> allRoutes = new ArrayList<>();

        for (String prov : PROVINCES) {
            String resPath = "/skycraft/travel/" + prov + ".json";
            try (InputStream in = TravelNetworks.class.getResourceAsStream(resPath)) {
                if (in == null) continue;
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                ProvinceNetwork net = parseProvince(prov, root);
                net = ensureWildernessCoverage(net);
                provinces.put(prov, net);
                for (TravelNode n : net.nodes()) {
                    TravelNode existing = byId.get(n.id());
                    if (existing != null) {
                        List<TravelMode> mergedModes = new ArrayList<>(existing.modes());
                        for (TravelMode m : n.modes()) {
                            if (!mergedModes.contains(m)) mergedModes.add(m);
                        }
                        if (mergedModes.size() != existing.modes().size()) {
                            TravelNode merged = new TravelNode(
                                    existing.id(), existing.province(), existing.region(), existing.place(),
                                    existing.nameKey(), existing.x(), existing.z(), existing.primaryMode(),
                                    List.copyOf(mergedModes), existing.kind(), existing.discoveredOnArrival(),
                                    existing.free(), existing.hashed(), existing.blessing(), existing.why()
                            );
                            byId.put(merged.id(), merged);
                            if (merged.place() != null && !merged.place().isBlank()) {
                                byPlace.put(merged.place(), merged);
                            }
                            for (int idx = 0; idx < allNodes.size(); idx++) {
                                if (allNodes.get(idx).id().equals(merged.id())) {
                                    allNodes.set(idx, merged);
                                    break;
                                }
                            }
                        }
                    } else {
                        byId.put(n.id(), n);
                        if (n.place() != null && !n.place().isBlank()) {
                            byPlace.putIfAbsent(n.place(), n);
                        }
                        allNodes.add(n);
                    }
                }
                allRoutes.addAll(net.routes());
            } catch (Exception ex) {
                Skycraft.LOGGER.error("Failed to load travel network {}", resPath, ex);
            }
        }

        synthesizeFallbackNetworks(provinces, byId, byPlace, allNodes, allRoutes);

        Skycraft.LOGGER.info("Loaded travel networks: {} provinces, {} nodes, {} routes",
                provinces.size(), allNodes.size(), allRoutes.size());
        return new State(
                Collections.unmodifiableMap(provinces),
                Collections.unmodifiableMap(byId),
                Collections.unmodifiableMap(byPlace),
                List.copyOf(allNodes),
                List.copyOf(allRoutes)
        );
    }

    private static ProvinceNetwork parseProvince(String fallbackProv, JsonObject root) {
        String prov = str(root, "province", fallbackProv);
        List<String> why = strList(root.get("why"));
        List<TravelMode> modes = new ArrayList<>();
        if (root.has("modes") && root.get("modes").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("modes")) {
                TravelMode.byId(e.getAsString()).ifPresent(modes::add);
            }
        }

        List<TravelNode> nodes = new ArrayList<>();
        if (root.has("nodes") && root.get("nodes").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("nodes")) {
                JsonObject o = e.getAsJsonObject();
                String id = str(o, "id", "");
                if (id.isEmpty()) continue;
                String place = o.has("place") && !o.get("place").isJsonNull() ? o.get("place").getAsString() : null;
                String region = str(o, "region", prov);
                String nameKey = str(o, "name", place != null ? "place.skycraft." + place : "travel.skycraft.node." + id);
                Place resolvedPlace = Places.byId(place != null ? place : id).orElse(null);
                int x = resolvedPlace != null ? resolvedPlace.x() : (o.has("x") ? o.get("x").getAsInt() : 0);
                int z = resolvedPlace != null ? resolvedPlace.z() : (o.has("z") ? o.get("z").getAsInt() : 0);
                TravelMode primary = TravelMode.byId(str(o, "mode", "wayshrine")).orElse(TravelMode.WAYSHRINE);
                List<TravelMode> nodeModes = new ArrayList<>();
                if (o.has("modes") && o.get("modes").isJsonArray()) {
                    for (JsonElement me : o.getAsJsonArray("modes")) {
                        TravelMode.byId(me.getAsString()).ifPresent(nodeModes::add);
                    }
                }
                if (!nodeModes.contains(primary)) nodeModes.add(0, primary);
                String kind = str(o, "kind", primary == TravelMode.WAYSHRINE ? "wayshrine" : "town");
                boolean disc = !o.has("discoveredOnArrival") || o.get("discoveredOnArrival").getAsBoolean();
                boolean free = o.has("free") ? o.get("free").getAsBoolean() : primary == TravelMode.WAYSHRINE;
                boolean hashed = o.has("hashed") && o.get("hashed").getAsBoolean();
                String blessing = null;
                if (o.has("blessing") && !o.get("blessing").isJsonNull()) {
                    JsonElement b = o.get("blessing");
                    if (b.isJsonPrimitive() && b.getAsJsonPrimitive().isBoolean()) {
                        blessing = b.getAsBoolean() ? "kynareth" : null;
                    } else {
                        blessing = b.getAsString();
                    }
                }
                List<String> nodeWhy = strList(o.get("why"));
                nodes.add(new TravelNode(id, prov, region, place, nameKey, x, z, primary, List.copyOf(nodeModes),
                        kind, disc, free, hashed, blessing, nodeWhy));
            }
        }

        List<TravelRoute> routes = new ArrayList<>();
        if (root.has("routes") && root.get("routes").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("routes")) {
                JsonObject o = e.getAsJsonObject();
                String from = str(o, "from", "");
                String to = str(o, "to", "");
                if (from.isEmpty() || to.isEmpty()) continue;
                TravelMode mode = TravelMode.byId(str(o, "mode", "carriage")).orElse(TravelMode.CARRIAGE);
                int fee = o.has("fee")
                        ? Math.max(0, o.get("fee").getAsInt())
                        : (o.has("cost") ? Math.max(0, o.get("cost").getAsInt()) : 0);
                int duration = o.has("duration") ? Math.max(1, o.get("duration").getAsInt()) : 10;
                String path = str(o, "path", mode.id() + "_route");
                boolean bi = !o.has("bidirectional") || o.get("bidirectional").getAsBoolean();
                List<String> routeWhy = strList(o.get("why"));
                routes.add(new TravelRoute(from, to, prov, mode, fee, duration, path, bi, routeWhy));
            }
        }

        return new ProvinceNetwork(prov, why, List.copyOf(modes), List.copyOf(nodes), List.copyOf(routes));
    }

    /**
     * Ensures no land cell in {@code net.province()} is more than 800 blocks from a travel node (p90 <= 500) by
     * deterministically filling any uncovered land buckets with hashed wilderness wayshrine nodes.
     */
    private static ProvinceNetwork ensureWildernessCoverage(ProvinceNetwork net) {
        String prov = net.province();
        List<TravelNode> nodes = new ArrayList<>(net.nodes());
        List<TravelRoute> routes = new ArrayList<>(net.routes());

        List<int[]> landCells = new ArrayList<>();
        List<String> cellRegions = new ArrayList<>();
        for (int gz = 0; gz < TamrielMap.HEIGHT_CELLS; gz++) {
            int wz = TamrielMap.MIN_Z + gz * TamrielMap.CELL + TamrielMap.CELL / 2;
            for (int gx = 0; gx < TamrielMap.WIDTH_CELLS; gx++) {
                int wx = TamrielMap.MIN_X + gx * TamrielMap.CELL + TamrielMap.CELL / 2;
                Region r = TamrielMap.region(wx, wz);
                if (!prov.equals(r.province())) continue;
                if (TamrielMap.elevation(wx, wz) <= TamrielMap.SEA_LEVEL) continue;
                landCells.add(new int[]{wx, wz});
                cellRegions.add(r.id());
            }
        }
        if (landCells.isEmpty()) return net;

        int added = 0;
        while (true) {
            int worstIdx = -1;
            double worstDist = 0;
            for (int i = 0; i < landCells.size(); i++) {
                int[] pt = landCells.get(i);
                double d = nearestDist(nodes, pt[0], pt[1]);
                if (d > worstDist) {
                    worstDist = d;
                    worstIdx = i;
                }
            }
            if (worstDist <= 520.0 || worstIdx < 0) break;
            int[] pt = landCells.get(worstIdx);
            String reg = cellRegions.get(worstIdx);
            String hold = reg.contains("/") ? reg.substring(reg.indexOf('/') + 1) : reg;
            added++;
            String nid = "wayshrine_" + hold + "_auto_" + added;
            List<String> why = !net.why().isEmpty() ? net.why() : defaultWhyForRegion(prov, hold);
            TravelNode wn = new TravelNode(
                    nid, prov, reg, null, "travel.skycraft.node." + nid,
                    pt[0], pt[1], TravelMode.WAYSHRINE, List.of(TravelMode.WAYSHRINE),
                    "wayshrine", true, true, true, "kynareth", why
            );
            TravelNode nearest = nearestNodeInList(nodes, pt[0], pt[1]);
            nodes.add(wn);
            if (nearest != null) {
                routes.add(new TravelRoute(nid, nearest.id(), prov, TravelMode.WAYSHRINE, 0, 6,
                        hold + "_cairn_trail", true, why));
            }
        }
        if (added == 0) return net;
        return new ProvinceNetwork(prov, net.why(), net.modes(), List.copyOf(nodes), List.copyOf(routes));
    }

    private static List<String> defaultWhyForRegion(String prov, String hold) {
        return switch (hold) {
            case "whiterun" -> List.of("whiterun.L5.crossroads_trade", "whiterun.L8.kynareth_tree");
            case "haafingar" -> List.of("haafingar.L5.toll_bridge", "haafingar.L8.divines_temple");
            case "falkreath" -> List.of("falkreath.L5.border_trade", "falkreath.L8.arkay_priests");
            case "hjaalmarch" -> List.of("hjaalmarch.L6.hjaal_road", "hjaalmarch.L8.ruined_shrines");
            case "the_pale" -> List.of("the_pale.L5.roadside_inn", "the_pale.L8.mara_priest");
            case "the_rift" -> List.of("the_rift.L8.mara_temple", "the_rift.L8.voice_pilgrims");
            case "winterhold" -> List.of("winterhold.L6.winterhold_remnant", "winterhold.L8.azura_statue");
            case "eastmarch" -> List.of("eastmarch.L8.talos_open", "eastmarch.L3.darkwater_ford");
            case "the_reach" -> List.of("the_reach.L6.karth_road", "the_reach.L8.dibella_temple");
            default -> List.of(prov + ".L5.road_hub");
        };
    }

    private static void synthesizeFallbackNetworks(
            Map<String, ProvinceNetwork> provinces,
            Map<String, TravelNode> byId,
            Map<String, TravelNode> byPlace,
            List<TravelNode> allNodes,
            List<TravelRoute> allRoutes
    ) {
        // Ensure all sea ports and routes in ports.json are addressable in TravelNetworks
        for (Port p : Ports.all()) {
            if (byId.containsKey(p.id())) continue;
            Region reg = TamrielMap.region(p.x(), p.z());
            String prov = reg.province();
            TravelNode node = new TravelNode(
                    p.id(), prov, reg.id(), p.place(),
                    p.place() != null ? "place.skycraft." + p.place() : "port.skycraft." + p.id(),
                    p.x(), p.z(), TravelMode.BOAT, List.of(TravelMode.BOAT),
                    "port", true, false, false, null, List.of()
            );
            byId.put(node.id(), node);
            if (node.place() != null) byPlace.putIfAbsent(node.place(), node);
            allNodes.add(node);
        }
        for (Route r : Ports.routes()) {
            boolean exists = false;
            for (TravelRoute tr : allRoutes) {
                if (tr.mode() == TravelMode.BOAT && tr.connects(r.a(), r.b())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                TravelNode a = byId.get(r.a());
                String prov = a != null ? a.province() : "tamriel";
                int fee = r.fare() >= 0 ? r.fare() : 50;
                allRoutes.add(new TravelRoute(r.a(), r.b(), prov, TravelMode.BOAT, fee, 16,
                        r.key(), true, List.of()));
            }
        }

        // Synthesize Morrowind Vvardenfell silt strider network if morrowind.json is not yet authored
        if (!provinces.containsKey("morrowind")) {
            String[][] striderStops = {
                    {"seyda_neen", "6050", "2700"},
                    {"balmora", "6300", "500"},
                    {"vivec", "7400", "1300"},
                    {"ald_ruhn", "6100", "-500"},
                    {"ebonheart", "4700", "2400"},
                    {"gnisis", "5400", "-1300"},
                    {"maar_gan", "6400", "-1100"},
                    {"suran", "7900", "1100"},
                    {"molag_mar", "8400", "700"},
                    {"khuul", "5600", "-2000"}
            };
            for (String[] s : striderStops) {
                String id = s[0];
                if (byId.containsKey(id)) continue;
                Place place = Places.byId(id).orElse(null);
                int x = place != null ? place.x() : Integer.parseInt(s[1]);
                int z = place != null ? place.z() : Integer.parseInt(s[2]);
                String reg = place != null && !place.regionId().isEmpty() ? place.regionId() : "morrowind/vvardenfell";
                TravelNode n = new TravelNode(id, "morrowind", reg, id, "place.skycraft." + id,
                        x, z, TravelMode.STRIDER, List.of(TravelMode.STRIDER),
                        "town", true, false, false, null, List.of());
                byId.put(id, n);
                byPlace.putIfAbsent(id, n);
                allNodes.add(n);
            }
            String[][] striderLinks = {
                    {"seyda_neen", "balmora"}, {"seyda_neen", "vivec"}, {"seyda_neen", "ebonheart"},
                    {"balmora", "ald_ruhn"}, {"balmora", "vivec"}, {"balmora", "suran"},
                    {"ald_ruhn", "gnisis"}, {"ald_ruhn", "maar_gan"}, {"ald_ruhn", "khuul"},
                    {"gnisis", "khuul"}, {"maar_gan", "khuul"},
                    {"vivec", "ebonheart"}, {"vivec", "suran"}, {"vivec", "molag_mar"},
                    {"suran", "molag_mar"}
            };
            for (String[] link : striderLinks) {
                allRoutes.add(new TravelRoute(link[0], link[1], "morrowind", TravelMode.STRIDER, 30, 12,
                        "vvardenfell_ash_road", true, List.of()));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public static Collection<ProvinceNetwork> provinces() {
        return state().provinces().values();
    }

    public static Optional<ProvinceNetwork> province(String provinceId) {
        if (provinceId == null) return Optional.empty();
        return Optional.ofNullable(state().provinces().get(provinceId.trim().toLowerCase(Locale.ROOT)));
    }

    public static List<TravelNode> allNodes() {
        return state().allNodes();
    }

    public static List<TravelRoute> allRoutes() {
        return state().allRoutes();
    }

    public static List<TravelNode> nodesInProvince(String provinceId) {
        if (provinceId == null) return List.of();
        String p = provinceId.trim().toLowerCase(Locale.ROOT);
        ProvinceNetwork net = state().provinces().get(p);
        if (net != null) return net.nodes();
        List<TravelNode> out = new ArrayList<>();
        for (TravelNode n : state().allNodes()) {
            if (p.equals(n.province())) out.add(n);
        }
        return out;
    }

    public static List<TravelRoute> routesInProvince(String provinceId) {
        if (provinceId == null) return List.of();
        String p = provinceId.trim().toLowerCase(Locale.ROOT);
        ProvinceNetwork net = state().provinces().get(p);
        if (net != null) return net.routes();
        List<TravelRoute> out = new ArrayList<>();
        for (TravelRoute r : state().allRoutes()) {
            if (p.equals(r.province())) out.add(r);
        }
        return out;
    }

    public static Optional<TravelNode> nodeForPlace(String placeId) {
        return node(placeId);
    }

    public static Optional<TravelNode> node(String idOrPlace) {
        if (idOrPlace == null || idOrPlace.isBlank()) return Optional.empty();
        String key = idOrPlace.trim().toLowerCase(Locale.ROOT);
        State s = state();
        TravelNode n = s.byId().get(key);
        if (n != null) return Optional.of(n);
        n = s.byPlace().get(key);
        if (n != null) return Optional.of(n);
        String alias = switch (key) {
            case "ald_ruhn" -> "aldruhn";
            case "aldruhn" -> "ald_ruhn";
            case "frostmoth" -> "fort_frostmoth";
            case "balfiera" -> "isle_of_balfiera";
            default -> null;
        };
        if (alias != null) {
            n = s.byId().get(alias);
            if (n != null) return Optional.of(n);
            n = s.byPlace().get(alias);
            if (n != null) return Optional.of(n);
        }
        if (key.startsWith("the_")) {
            n = s.byId().get(key.substring(4));
            if (n != null) return Optional.of(n);
        } else {
            n = s.byPlace().get("the_" + key);
            if (n != null) return Optional.of(n);
        }
        return Optional.empty();
    }

    public static Optional<TravelNode> nearestNode(int x, int z, double maxRadius) {
        TravelNode best = null;
        double bestDist = maxRadius;
        for (TravelNode n : state().allNodes()) {
            double d = n.distanceTo(x, z);
            if (d <= bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<TravelNode> nearestNode(int x, int z, TravelMode mode, double maxRadius) {
        TravelNode best = null;
        double bestDist = maxRadius;
        for (TravelNode n : state().allNodes()) {
            if (!n.supports(mode)) continue;
            double d = n.distanceTo(x, z);
            if (d <= bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Returns all outgoing route offers from {@code nodeOrPlaceId}. */
    public static List<Offer> offersFrom(String nodeOrPlaceId) {
        return offersFrom(nodeOrPlaceId, null);
    }

    /** Returns outgoing route offers from {@code nodeOrPlaceId} filtered by {@code mode} (or all modes if null). */
    public static List<Offer> offersFrom(String nodeOrPlaceId, @Nullable TravelMode mode) {
        Optional<TravelNode> origOpt = node(nodeOrPlaceId);
        if (origOpt.isEmpty()) return List.of();
        TravelNode origin = origOpt.get();
        State s = state();
        List<Offer> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TravelRoute r : s.allRoutes()) {
            if (mode != null && r.mode() != mode) continue;
            Optional<String> otherId = r.otherEnd(origin.id());
            if (otherId.isEmpty()) continue;
            TravelNode dest = s.byId().get(otherId.get());
            if (dest == null) continue;
            if (r.mode() == TravelMode.CARRIAGE) {
                double dist = Math.hypot(dest.x() - origin.x(), dest.z() - origin.z());
                if (dist > Carriages.MAX_CARRIAGE_DISTANCE) continue;
            }
            String dedup = r.mode().id() + ":" + dest.id();
            if (!seen.add(dedup)) continue;
            out.add(new Offer(origin, dest, r, r.mode(), r.fee(), r.durationSeconds(), r.path()));
        }
        out.sort(Comparator.comparing((Offer o) -> o.mode().ordinal())
                .thenComparingInt(Offer::fee)
                .thenComparing(o -> o.destination().id()));
        return out;
    }

    public static Optional<Offer> findOffer(String fromIdOrPlace, String toIdOrPlace, @Nullable TravelMode preferredMode) {
        Optional<TravelNode> fromOpt = node(fromIdOrPlace);
        Optional<TravelNode> toOpt = node(toIdOrPlace);
        if (fromOpt.isEmpty() || toOpt.isEmpty()) return Optional.empty();
        TravelNode from = fromOpt.get();
        TravelNode to = toOpt.get();
        for (Offer o : offersFrom(from.id(), preferredMode)) {
            if (o.destination().id().equals(to.id())) return Optional.of(o);
        }
        if (preferredMode != null) {
            for (Offer o : offersFrom(from.id(), null)) {
                if (o.destination().id().equals(to.id())) return Optional.of(o);
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Travel Execution Engine (Server-authoritative, multiplayer-safe)
    // -------------------------------------------------------------------------

    /**
     * Universal Prewarm helper used across all travel modes ({@code carriage}, {@code strider}, {@code boat},
     * {@code river_boat}, {@code barge}, {@code caravan}, {@code guild_guide}, {@code lift}, {@code propylon},
     * {@code intervention}, {@code wayshrine}, {@code foot}).
     */
    public static boolean prewarmTo(ServerPlayer player, int destX, int destZ, Component destName, Runnable onReady) {
        ServerLevel level = player.serverLevel();
        if (!Prewarm.isReady(level, destX, destZ)
                && Prewarm.start(player, destX, destZ, destName, onReady)) {
            return true;
        }
        onReady.run();
        return true;
    }

    /**
     * Executes travel for {@code player} along a route from {@code fromIdOrPlace} to {@code toIdOrPlace}.
     * Preserves existing carriage, strider, and ferry visuals and the "Travelling to..." prewarm.
     */
    public static boolean travel(ServerPlayer player, String fromIdOrPlace, String toIdOrPlace, @Nullable TravelMode preferredMode) {
        if (player == null || !player.isAlive() || player.level().dimension() != Level.OVERWORLD) return false;
        if (Vitals.inCombat(player)) {
            Notifier.message(player, Component.translatable("world.skycraft.travel.combat"));
            return false;
        }
        if (CarryWeight.isOverencumbered(player)) {
            Notifier.message(player, Component.translatable("world.skycraft.travel.encumbered"));
            return false;
        }

        Optional<Offer> offerOpt = findOffer(fromIdOrPlace, toIdOrPlace, preferredMode);
        if (offerOpt.isEmpty()) {
            // Allow free wayshrine/intervention travel to discovered wilderness nodes
            Optional<TravelNode> fromOpt = node(fromIdOrPlace);
            Optional<TravelNode> toOpt = node(toIdOrPlace);
            if (fromOpt.isPresent() && toOpt.isPresent() && toOpt.get().isWilderness()) {
                TravelNode fn = fromOpt.get();
                TravelNode tn = toOpt.get();
                TravelMode m = preferredMode != null ? preferredMode : TravelMode.WAYSHRINE;
                TravelRoute synth = new TravelRoute(fn.id(), tn.id(), tn.province(), m, 0, 6, "wayshrine_path", true, tn.why());
                offerOpt = Optional.of(new Offer(fn, tn, synth, m, 0, 6, "wayshrine_path"));
            } else {
                return false;
            }
        }

        Offer offer = offerOpt.get();
        TravelNode origin = offer.origin();
        TravelNode dest = offer.destination();
        TravelMode mode = offer.mode();
        int fee = offer.fee();

        // For sea ports with a Route in Ports, prewarm the destination chunk and delegate to Ferries
        if (mode == TravelMode.BOAT) {
            Port fromPort = Ports.byId(origin.id()).orElse(null);
            Port toPort = Ports.byId(dest.id()).orElse(null);
            if (fromPort != null && toPort != null) {
                for (Route r : Ports.routesFrom(fromPort.id())) {
                    if (r.other(fromPort.id()).equals(toPort.id())) {
                        if (fee > 0 && !Currency.take(player, fee)) {
                            Notifier.message(player, Component.translatable("travel.skycraft.no_gold", fee));
                            return false;
                        }
                        final Port fp = fromPort;
                        final Port tp = toPort;
                        final Route fr = r;
                        return prewarmTo(player, dest.x(), dest.z(), dest.displayName(), () -> {
                            Ferries.start(player, fp, tp, fr);
                            onArrival(player, dest, mode);
                        });
                    }
                }
            }
        }

        if (fee > 0 && !Currency.take(player, fee)) {
            Notifier.message(player, Component.translatable("travel.skycraft.no_gold", fee));
            player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.8f, 1.0f);
            return false;
        }

        playDepartureScene(player, mode);
        return prewarmTo(player, dest.x(), dest.z(), dest.displayName(), () -> completeRide(player, origin, dest, mode));
    }

    private static void playDepartureScene(ServerPlayer player, TravelMode mode) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        switch (mode) {
            case BOAT, RIVER_BOAT, BARGE ->
                    level.playSound(null, pos, SoundEvents.BOAT_PADDLE_WATER, SoundSource.NEUTRAL, 0.9f, 0.9f);
            case GUILD_GUIDE, PROPYLON, INTERVENTION -> {
                level.sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(),
                        28, 0.4, 0.8, 0.4, 0.15);
                level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.1f);
            }
            case LIFT -> {
                level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5, player.getZ(),
                        16, 0.4, 0.2, 0.4, 0.04);
                level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.8f, 0.75f);
            }
            case WAYSHRINE -> {
                level.sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1.0, player.getZ(),
                        20, 0.4, 0.6, 0.4, 0.1);
                level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.9f, 1.0f);
            }
            default ->
                    level.playSound(null, pos, SoundEvents.HORSE_GALLOP, SoundSource.NEUTRAL, 0.7f, 1.0f);
        }
    }

    private static void completeRide(ServerPlayer player, TravelNode origin, TravelNode dest, TravelMode mode) {
        if (!player.isAlive()) return;
        ServerLevel level = player.serverLevel();
        BlockPos targetPos = resolveArrivalPos(level, dest, mode);

        Entity vehicle = player.getVehicle();
        List<Mob> followers = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                m -> m.isAlive() && !m.isPassenger() && FastTravelHandler.isFollower(m, player));

        double distance = Math.hypot(dest.x() - origin.x(), dest.z() - origin.z());

        player.stopRiding();
        player.teleportTo(level, targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        player.resetFallDistance();

        if (vehicle != null && vehicle.isAlive() && mode.isRoad()) {
            vehicle.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
            vehicle.resetFallDistance();
            player.startRiding(vehicle, true);
        }

        int i = 0;
        for (Mob mob : followers) {
            double angle = i++ * (Math.PI * 2 / Math.max(1, followers.size()));
            double fx = targetPos.getX() + 0.5 + Math.cos(angle) * 1.5;
            double fz = targetPos.getZ() + 0.5 + Math.sin(angle) * 1.5;
            mob.getNavigation().stop();
            mob.teleportTo(fx, targetPos.getY(), fz);
            mob.resetFallDistance();
        }

        int hours = 0;
        if (mode != TravelMode.GUILD_GUIDE && mode != TravelMode.PROPYLON
                && mode != TravelMode.INTERVENTION && mode != TravelMode.LIFT) {
            int perHour = WorldConfig.BLOCKS_PER_TRAVEL_HOUR.get();
            long online = player.server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count();
            if (perHour > 0 && online <= 1) {
                hours = Mth.clamp((int) Math.round(distance / perHour), 1, 72);
                RestManager.advanceTime(player.server, hours * (long) RestManager.TICKS_PER_HOUR);
            }
        }

        SkyNetwork.sendToPlayer(player, new WorldPackets.RestFade(hours, WorldPackets.RestFade.TRAVEL));
        Notifier.message(player, Component.translatable("travel.skycraft.arrive." + mode.id(), dest.displayName()));
        onArrival(player, dest, mode);
    }

    public static BlockPos resolveArrivalPos(ServerLevel level, TravelNode dest, TravelMode mode) {
        if (mode == TravelMode.CARRIAGE || mode == TravelMode.CARAVAN) {
            if (dest.place() != null) {
                Place p = Places.byId(dest.place()).orElse(null);
                if (p != null) return Carriages.stopFor(level, p);
            }
        }
        level.getChunk(dest.x() >> 4, dest.z() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dest.x(), dest.z());
        BlockPos anchor = new BlockPos(dest.x(), Math.max(y, level.getSeaLevel() + 1), dest.z());
        LocationKind kind = dest.isWilderness() ? LocationKind.SHRINE : LocationKind.TOWN;
        BlockPos safe = FastTravelHandler.findArrival(level, anchor, kind);
        return safe != null ? safe : anchor;
    }

    /**
     * Marks {@code node} discovered for {@code player} if {@code discoveredOnArrival} is set, and refreshes its minor
     * blessing when applicable.
     */
    public static void onArrival(ServerPlayer player, TravelNode node, TravelMode mode) {
        if (player == null || !player.isAlive()) return;
        if (node.discoveredOnArrival()) {
            discoverNode(player, node, false);
        }
        if (node.blessing() != null && !node.blessing().isBlank()) {
            refreshMinorBlessing(player, node);
        }
    }

    /**
     * Periodic check called from server player tick: discovers nearby wilderness nodes (shrines, standing stones,
     * wayshrines) on visit and refreshes their minor road blessing.
     */
    public static void tickPlayer(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.level().dimension() != Level.OVERWORLD) return;
        int px = player.getBlockX();
        int pz = player.getBlockZ();
        long now = player.level().getGameTime();
        for (TravelNode node : state().allNodes()) {
            if (!node.isWilderness()) continue;
            int dx = px - node.x();
            int dz = pz - node.z();
            if (dx * dx + dz * dz > WILDERNESS_VISIT_RADIUS * WILDERNESS_VISIT_RADIUS) continue;
            discoverNode(player, node, true);
            if (node.blessing() != null && !node.blessing().isBlank()) {
                String key = player.getUUID() + ":" + node.id();
                Long last = VISIT_COOLDOWNS.get(key);
                if (last == null || now - last >= BLESSING_COOLDOWN_TICKS) {
                    VISIT_COOLDOWNS.put(key, now);
                    refreshMinorBlessing(player, node);
                }
            }
            break;
        }
    }

    public static boolean discoverNode(ServerPlayer player, TravelNode node, boolean notifyIfNew) {
        PlayerData data = SkyData.get(player);
        String locId = node.place() != null && !node.place().isBlank()
                ? WorldData.placeLocId(node.place())
                : "travel|" + node.id();
        CompoundTag existing = WorldData.find(data, locId);
        if (existing != null && !existing.getBoolean("known")) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        int y = level.hasChunk(node.x() >> 4, node.z() >> 4)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, node.x(), node.z())
                : Math.max(level.getSeaLevel() + 1, (int) TamrielMap.elevation(node.x(), node.z()));
        if (existing != null) {
            existing.remove("known");
            existing.putInt("ax", player.getBlockX());
            existing.putInt("ay", player.getBlockY());
            existing.putInt("az", player.getBlockZ());
        } else {
            CompoundTag loc = new CompoundTag();
            loc.putString("id", locId);
            if (node.place() != null && !node.place().isBlank()) {
                loc.putString("place", node.place());
            }
            loc.putString("name", node.displayName().getString());
            LocationKind kind = "standing_stone".equals(node.kind())
                    ? LocationKind.STANDING_STONE
                    : (node.isWilderness() ? LocationKind.SHRINE : LocationKind.TOWN);
            loc.putString("type", kind.id);
            loc.putInt("x", node.x());
            loc.putInt("y", y);
            loc.putInt("z", node.z());
            loc.putString("dim", level.dimension().location().toString());
            loc.putInt("ax", player.getBlockX());
            loc.putInt("ay", player.getBlockY());
            loc.putInt("az", player.getBlockZ());
            WorldData.discovered(data).add(loc);
        }
        data.markDirty();
        if (notifyIfNew && node.hashed()) {
            Notifier.send(player, NotifyKind.LOCATION_DISCOVERED, node.displayName(), LocationKind.SHRINE.displayName());
        }
        return true;
    }

    /**
     * Grants a minor 10-minute road blessing when visiting or arriving at a wilderness shrine, standing stone, or
     * road wayshrine.
     */
    public static void refreshMinorBlessing(ServerPlayer player, TravelNode node) {
        if (player == null || !player.isAlive()) return;
        int durationTicks = 20 * 60 * 10; // 10 minutes
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 12, 0, true, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, durationTicks, 0, true, false, true));
        String blessingName = node.blessing() != null
                ? node.blessing().substring(0, 1).toUpperCase(Locale.ROOT) + node.blessing().substring(1)
                : "the Wayshrine";
        Notifier.message(player, Component.translatable("travel.skycraft.blessing_refreshed",
                node.displayName(), Component.literal(blessingName)));
    }

    // -------------------------------------------------------------------------
    // Coverage & Selftest (F-TRAVEL, MS11)
    // -------------------------------------------------------------------------

    public static CoverageStats coverage(String provinceId) {
        String prov = provinceId == null ? "skyrim" : provinceId.trim().toLowerCase(Locale.ROOT);
        List<TravelNode> nodes = nodesInProvince(prov);
        int wildCount = 0;
        for (TravelNode n : nodes) {
            if (n.isWilderness()) wildCount++;
        }

        Map<String, List<Double>> distsByHold = new LinkedHashMap<>();
        List<Double> allDists = new ArrayList<>();

        for (int gz = 0; gz < TamrielMap.HEIGHT_CELLS; gz++) {
            int wz = TamrielMap.MIN_Z + gz * TamrielMap.CELL + TamrielMap.CELL / 2;
            for (int gx = 0; gx < TamrielMap.WIDTH_CELLS; gx++) {
                int wx = TamrielMap.MIN_X + gx * TamrielMap.CELL + TamrielMap.CELL / 2;
                Region r = TamrielMap.region(wx, wz);
                if (!prov.equals(r.province())) continue;
                if (TamrielMap.elevation(wx, wz) <= TamrielMap.SEA_LEVEL) continue;
                double d = nearestDist(nodes, wx, wz);
                allDists.add(d);
                distsByHold.computeIfAbsent(r.id(), k -> new ArrayList<>()).add(d);
            }
        }

        Collections.sort(allDists);
        int totalCells = allDists.size();
        double areaKm2 = totalCells * (double) TamrielMap.CELL * TamrielMap.CELL / 1_000_000.0;
        double p50 = percentile(allDists, 0.50);
        double p90 = percentile(allDists, 0.90);
        double max = allDists.isEmpty() ? 0.0 : allDists.get(allDists.size() - 1);
        double nodesPerKm2 = areaKm2 > 0 ? nodes.size() / areaKm2 : 0.0;
        double wildPerKm2 = areaKm2 > 0 ? wildCount / areaKm2 : 0.0;

        Map<String, HoldCoverage> byHold = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : distsByHold.entrySet()) {
            List<Double> hd = e.getValue();
            Collections.sort(hd);
            double hArea = hd.size() * (double) TamrielMap.CELL * TamrielMap.CELL / 1_000_000.0;
            byHold.put(e.getKey(), new HoldCoverage(
                    e.getKey(),
                    hd.size(),
                    hArea,
                    percentile(hd, 0.50),
                    percentile(hd, 0.90),
                    hd.isEmpty() ? 0.0 : hd.get(hd.size() - 1)
            ));
        }

        return new CoverageStats(
                prov, totalCells, areaKm2, nodes.size(), wildCount,
                nodesPerKm2, wildPerKm2, p50, p90, max, Collections.unmodifiableMap(byHold)
        );
    }

    public static SelftestResult selftest() {
        List<String> details = new ArrayList<>();
        int passed = 0;
        int problems = 0;

        ProvinceNetwork skyrim = province("skyrim").orElse(null);
        if (skyrim == null) {
            problems++;
            details.add("FAIL: missing skycraft/travel/skyrim.json");
            CoverageStats empty = coverage("skyrim");
            return new SelftestResult(0, 1, 1, details, empty);
        }

        // Check 1: Skyrim modes >= 2 beyond walking and horse
        Set<TravelMode> activeModes = EnumSet.noneOf(TravelMode.class);
        for (TravelRoute r : skyrim.routes()) activeModes.add(r.mode());
        if (skyrim.modes().size() >= 2 && activeModes.size() >= 2) {
            passed++;
            details.add("PASS: Skyrim travel modes = " + activeModes.size() + " (" + activeModes + ")");
        } else {
            problems++;
            details.add("FAIL: Skyrim needs >= 2 travel modes beyond walking/horse, found " + activeModes);
        }

        // Check 2: Required Skyrim nodes (9 hold capitals, coastal ports, Great Lifts, standing stones, shrines)
        String[] requiredNodes = {
                "whiterun", "solitude", "windhelm", "riften", "markarth", "winterhold", "falkreath", "morthal", "dawnstar",
                "riverwood", "rorikstead", "ivarstead", "dragon_bridge", "karthwasten", "shors_stone", "kynesgrove",
                "raven_rock", "alftand", "mzinchaleft", "raldbthar",
                "guardian_stones", "lover_stone", "lord_stone", "mage_stone", "thief_stone", "warrior_stone",
                "apprentice_stone", "atronach_stone", "lady_stone", "ritual_stone", "serpent_stone", "shadow_stone",
                "steed_stone", "tower_stone",
                "statue_to_meridia", "shrine_of_azura", "shrine_of_mehrunes_dagon", "shrine_to_peryite", "sacellum_of_boethiah"
        };
        List<String> missingNodes = new ArrayList<>();
        for (String req : requiredNodes) {
            if (node(req).isEmpty()) missingNodes.add(req);
        }
        if (missingNodes.isEmpty()) {
            passed++;
            details.add("PASS: All " + requiredNodes.length + " required Skyrim capitals, ports, lifts, stones, and shrines exist");
        } else {
            problems++;
            details.add("FAIL: Missing required Skyrim nodes: " + missingNodes);
        }

        // Check 3: Every route's endpoints exist, fee >= 0, duration > 0, path non-empty
        List<String> badRoutes = new ArrayList<>();
        for (TravelRoute r : skyrim.routes()) {
            if (node(r.from()).isEmpty() || node(r.to()).isEmpty() || r.from().equals(r.to())
                    || r.fee() < 0 || r.durationSeconds() <= 0 || r.path() == null || r.path().isBlank()) {
                badRoutes.add(r.from() + "->" + r.to() + "(" + r.mode().id() + ")");
            }
        }
        if (badRoutes.isEmpty()) {
            passed++;
            details.add("PASS: All " + skyrim.routes().size() + " Skyrim routes have valid endpoints, fees, durations, and paths");
        } else {
            problems++;
            details.add("FAIL: Invalid routes in skyrim.json: " + badRoutes);
        }

        // Check 4: No unintended all-to-all (routes restricted to real links unless mode.allowAllToAll())
        List<String> allToAllViolations = new ArrayList<>();
        for (TravelMode m : activeModes) {
            if (m.allowAllToAll()) continue;
            Set<String> modeNodes = new HashSet<>();
            Set<String> undirectedEdges = new HashSet<>();
            for (TravelRoute r : skyrim.routes()) {
                if (r.mode() != m) continue;
                modeNodes.add(r.from());
                modeNodes.add(r.to());
                String a = r.from().compareTo(r.to()) <= 0 ? r.from() : r.to();
                String b = r.from().compareTo(r.to()) <= 0 ? r.to() : r.from();
                undirectedEdges.add(a + "<->" + b);
            }
            int n = modeNodes.size();
            int maxPairs = n * (n - 1) / 2;
            if (n >= 4 && undirectedEdges.size() >= maxPairs) {
                allToAllViolations.add(m.id() + " (" + undirectedEdges.size() + "/" + maxPairs + ")");
            }
        }
        // Also verify hold capitals are not all-to-all (9 capitals => 36 pairs; real links <= 24)
        Set<String> capitals = Set.of("whiterun", "solitude", "windhelm", "riften", "markarth",
                "winterhold", "falkreath", "morthal", "dawnstar");
        Set<String> capitalEdges = new HashSet<>();
        for (TravelRoute r : skyrim.routes()) {
            if (r.mode() == TravelMode.CARRIAGE && capitals.contains(r.from()) && capitals.contains(r.to())) {
                String a = r.from().compareTo(r.to()) <= 0 ? r.from() : r.to();
                String b = r.from().compareTo(r.to()) <= 0 ? r.to() : r.from();
                capitalEdges.add(a + "<->" + b);
            }
        }
        if (capitalEdges.size() >= 36) {
            allToAllViolations.add("carriage_capitals (" + capitalEdges.size() + "/36)");
        }
        if (allToAllViolations.isEmpty()) {
            passed++;
            details.add("PASS: No unintended all-to-all graphs (capital carriage links = " + capitalEdges.size() + "/36)");
        } else {
            problems++;
            details.add("FAIL: Unintended all-to-all routes: " + allToAllViolations);
        }

        // Check 5: WorldSheets why citations resolve
        List<String> unresolvedWhy = new ArrayList<>();
        for (String w : skyrim.why()) {
            if (!WorldSheets.resolves(w)) unresolvedWhy.add("province:" + w);
        }
        for (TravelNode n : skyrim.nodes()) {
            if (n.why().isEmpty()) unresolvedWhy.add("node_empty:" + n.id());
            for (String w : n.why()) {
                if (!WorldSheets.resolves(w)) unresolvedWhy.add("node:" + n.id() + ":" + w);
            }
        }
        for (TravelRoute r : skyrim.routes()) {
            if (r.why().isEmpty()) unresolvedWhy.add("route_empty:" + r.from() + "->" + r.to());
            for (String w : r.why()) {
                if (!WorldSheets.resolves(w)) unresolvedWhy.add("route:" + r.from() + "->" + r.to() + ":" + w);
            }
        }
        if (unresolvedWhy.isEmpty()) {
            passed++;
            details.add("PASS: All node and route WorldSheets 'why' citations resolve cleanly");
        } else {
            problems++;
            details.add("FAIL: Unresolved WorldSheets 'why' citations: " + unresolvedWhy);
        }

        // Check 6: F-TRAVEL coverage gate (Skyrim p90 <= 500, max <= 800, wilderness density >= 0.7/km2)
        CoverageStats cov = coverage("skyrim");
        List<String> covFailures = new ArrayList<>();
        if (cov.p90() > 500.0) covFailures.add(String.format(Locale.ROOT, "skyrim p90=%.1f > 500", cov.p90()));
        if (cov.max() > 800.0) covFailures.add(String.format(Locale.ROOT, "skyrim max=%.1f > 800", cov.max()));
        if (cov.wildernessNodesPerKm2() < 0.7) {
            covFailures.add(String.format(Locale.ROOT, "skyrim wild/km2=%.2f < 0.7", cov.wildernessNodesPerKm2()));
        }
        for (HoldCoverage hc : cov.byRegion().values()) {
            if (hc.p90() > 500.0) covFailures.add(String.format(Locale.ROOT, "%s p90=%.1f > 500", hc.regionId(), hc.p90()));
            if (hc.max() > 800.0) covFailures.add(String.format(Locale.ROOT, "%s max=%.1f > 800", hc.regionId(), hc.max()));
        }
        if (covFailures.isEmpty()) {
            passed++;
            details.add(String.format(Locale.ROOT,
                    "PASS: F-TRAVEL Skyrim coverage p50=%.1fm, p90=%.1fm (<=500), max=%.1fm (<=800), wild=%.2f/km2 (>=0.7)",
                    cov.p50(), cov.p90(), cov.max(), cov.wildernessNodesPerKm2()));
        } else {
            problems++;
            details.add("FAIL: F-TRAVEL coverage violations: " + covFailures);
        }

        // Check 7: All 15 provincial/regional travel network files load cleanly with valid endpoints, places, and why citations
        List<String> provErrors = new ArrayList<>();
        for (String pid : PROVINCE_IDS) {
            ProvinceNetwork pn = province(pid).orElse(null);
            if (pn == null) {
                provErrors.add("missing_province:" + pid);
                continue;
            }
            for (TravelNode n : pn.nodes()) {
                if (n.place() != null && Places.byId(n.place()).isEmpty()) {
                    provErrors.add(pid + ":missing_place:" + n.id() + "->" + n.place());
                }
                for (String w : n.why()) {
                    if (!WorldSheets.resolves(w)) provErrors.add(pid + ":node_why:" + n.id() + ":" + w);
                }
            }
            for (TravelRoute r : pn.routes()) {
                if (node(r.from()).isEmpty() || node(r.to()).isEmpty()) {
                    provErrors.add(pid + ":route_endpoint:" + r.from() + "->" + r.to());
                }
                for (String w : r.why()) {
                    if (!WorldSheets.resolves(w)) provErrors.add(pid + ":route_why:" + r.from() + "->" + r.to() + ":" + w);
                }
            }
        }
        if (provErrors.isEmpty()) {
            passed++;
            details.add("PASS: All " + PROVINCE_IDS.size() + " travel networks load with 0 missing places, 0 broken endpoints, and 100% resolved why citations");
        } else {
            problems++;
            details.add("FAIL: Travel network errors across provinces: " + provErrors.subList(0, Math.min(10, provErrors.size())));
        }

        // Check 8: Carriage distance cap (<= Carriages.MAX_CARRIAGE_DISTANCE = 2800m) across all settlements and routes
        List<String> overCapCarriages = new ArrayList<>();
        for (TravelRoute r : allRoutes()) {
            if (r.mode() != TravelMode.CARRIAGE) continue;
            TravelNode a = node(r.from()).orElse(null);
            TravelNode b = node(r.to()).orElse(null);
            if (a != null && b != null) {
                double d = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (d > Carriages.MAX_CARRIAGE_DISTANCE) {
                    overCapCarriages.add(String.format(Locale.ROOT, "%s->%s (%.1fm)", r.from(), r.to(), d));
                }
            }
        }
        Place whiterun = Places.byId("whiterun").orElse(null);
        Set<String> whiterunDestIds = new HashSet<>();
        if (whiterun != null) {
            for (Carriages.Destination cd : Carriages.destinations(whiterun)) {
                whiterunDestIds.add(cd.place().id());
                if (cd.distance() > Carriages.MAX_CARRIAGE_DISTANCE) {
                    overCapCarriages.add("whiterun_offer->" + cd.place().id() + ":" + cd.distance());
                }
            }
        }
        for (String cap : Set.of("solitude", "windhelm", "riften", "markarth", "morthal", "dawnstar", "winterhold", "falkreath")) {
            if (!whiterunDestIds.contains(cap)) {
                overCapCarriages.add("whiterun_missing_capital:" + cap);
            }
        }
        if (overCapCarriages.isEmpty()) {
            passed++;
            details.add("PASS: 0 carriage routes or offers exceed MAX_CARRIAGE_DISTANCE (" + Carriages.MAX_CARRIAGE_DISTANCE + "m), and Whiterun reaches all 8 other Skyrim capitals");
        } else {
            problems++;
            details.add("FAIL: Carriage distance cap or capital reachability violations: " + overCapCarriages);
        }

        // Check 9: Multi-modal regional networks (Morrowind strider/boat/guild_guide/propylon, Solstheim boat/foot, outer realms)
        List<String> multiModalErrors = new ArrayList<>();
        if (offersFrom("seyda_neen", TravelMode.STRIDER).isEmpty() || offersFrom("balmora", TravelMode.STRIDER).isEmpty()) {
            multiModalErrors.add("morrowind_strider");
        }
        if (offersFrom("hlormaren", TravelMode.PROPYLON).isEmpty() || offersFrom("marandus", TravelMode.PROPYLON).isEmpty()) {
            multiModalErrors.add("morrowind_propylon_ring");
        }
        if (offersFrom("balmora", TravelMode.GUILD_GUIDE).isEmpty() || offersFrom("imperial_city", TravelMode.GUILD_GUIDE).isEmpty()) {
            multiModalErrors.add("mages_guild_guides");
        }
        if (offersFrom("raven_rock", TravelMode.BOAT).isEmpty() || offersFrom("thirsk_mead_hall", TravelMode.FOOT).isEmpty()) {
            multiModalErrors.add("solstheim_boat_or_foot");
        }
        if (offersFrom("jylkurfyk", TravelMode.BOAT).isEmpty() || offersFrom("tang_mo_harbor_village", TravelMode.BOAT).isEmpty()) {
            multiModalErrors.add("outer_realm_expedition_boats");
        }
        if (multiModalErrors.isEmpty()) {
            passed++;
            details.add("PASS: Multi-modal regional transport (Silt Striders, Propylon ring, Mages Guild guides, Solstheim & outer-realm boats/trails) verified");
        } else {
            problems++;
            details.add("FAIL: Missing multi-modal regional transport links: " + multiModalErrors);
        }

        int total = passed + problems;
        Skycraft.LOGGER.info(String.format(Locale.ROOT,
                "Travel selftest: %d/%d checks passed, %d problems (Skyrim p50=%.1fm, p90=%.1fm, max=%.1fm, %d nodes, %d routes)",
                passed, total, problems, cov.p50(), cov.p90(), cov.max(), skyrim.nodes().size(), skyrim.routes().size()));
        return new SelftestResult(passed, total, problems, List.copyOf(details), cov);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static double nearestDist(List<TravelNode> nodes, int x, int z) {
        double best = Double.MAX_VALUE;
        for (TravelNode n : nodes) {
            double d = Math.hypot(n.x() - x, n.z() - z);
            if (d < best) best = d;
        }
        return best == Double.MAX_VALUE ? 9999.0 : best;
    }

    @Nullable
    private static TravelNode nearestNodeInList(List<TravelNode> nodes, int x, int z) {
        TravelNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (TravelNode n : nodes) {
            double d = Math.hypot(n.x() - x, n.z() - z);
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best;
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return 0.0;
        int idx = Mth.clamp((int) Math.floor(sorted.size() * q), 0, sorted.size() - 1);
        return sorted.get(idx);
    }

    private static String str(JsonObject o, String key, String def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        JsonElement el = o.get(key);
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.isEmpty()) return def;
            JsonElement first = arr.get(0);
            return first.isJsonPrimitive() ? first.getAsString() : def;
        }
        return def;
    }

    private static List<String> strList(@Nullable JsonElement el) {
        if (el == null || !el.isJsonArray()) return List.of();
        JsonArray arr = el.getAsJsonArray();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (!e.isJsonNull()) out.add(e.getAsString());
        }
        return List.copyOf(out);
    }
}
