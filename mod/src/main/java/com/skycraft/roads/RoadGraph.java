package com.skycraft.roads;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.settlement.plan.Cities;
import net.minecraft.core.Direction;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The planned road graph of Tamriel: which named settlements ({@link Places}) are joined by which class of road. Pure
 * map work (no world access), so it is the same for every seed; the routes themselves are found later by the
 * {@link RoadPlanner} on the world's noise heights.
 *
 * <ul>
 *     <li>Named historic roads ({@code skycraft/roads/named_roads.json}) come first: the Gold Road, the Red Ring Road,
 *     the roads between Skyrim's holds, the Talos Bridge...</li>
 *     <li>Then a greedy spanner over every settlement of a landmass: candidate links in order of their terrain-weighted
 *     length, kept only if the network so far has no route within {@link #STRETCH} times as long. It contains a
 *     minimum spanning tree, so everything on one landmass is connected, without parallel duplicate roads.</li>
 *     <li>Farms are leaves: one lane to their nearest neighbour.</li>
 *     <li>A link that passes close by another settlement is routed through it (roads run through villages).</li>
 * </ul>
 * Links never cross open sea: places on different {@link Landmass landmasses} stay apart (ships and ferries).
 */
final class RoadGraph {
    /** Keep a candidate link only if the current network detours by more than this factor. */
    private static final double STRETCH = 1.45;
    private static final int MAJOR_LINK = 4600, MINOR_LINK = 2400, FARM_LINK = 1600;
    /** Normal bridges (rivers, narrow straits); named links may allow more (the Talos Bridge). */
    static final int DEFAULT_BRIDGE = 64;

    /** One planned link between two places. */
    record Edge(Place a, Place b, byte kind, String name, int maxBridge) {
        double length() {
            return Math.sqrt(a.distSqr(b.x(), b.z()));
        }
    }

    /** A named road: its waypoints, class and longest allowed bridge. */
    record Named(String id, byte kind, List<String> route, int bridge) {
    }

    record Graph(List<Place> nodes, List<Edge> edges, Map<Place, Integer> landmass) {
    }

    private RoadGraph() {}

    // ------------------------------------------------------------------ nodes

    /** 0 = capital/city, 1 = town, 2 = village, 3 = farm. */
    static int rank(Place p) {
        return switch (p.type()) {
            case CAPITAL, CITY -> 0;
            case TOWN -> 1;
            case VILLAGE -> 2;
            default -> 3;
        };
    }

    /**
     * Roadside inns proposed along the highways ({@code roadside_*} places): not nodes of the graph but, like the
     * landmarks, joined by a short path from the road they stand beside.
     */
    static boolean isSpur(Place p) {
        return p.id().startsWith("roadside_");
    }

    /** Places that are trail destinations (one signposted trail from the nearest road, if it is close enough). */
    static boolean isTrailDestination(PlaceType t) {
        return t == PlaceType.DUNGEON_FIXED || t == PlaceType.FORT || t == PlaceType.STANDING_STONE
                || t == PlaceType.DAEDRIC_SHRINE || t == PlaceType.LANDMARK || t == PlaceType.WORD_WALL
                || t == PlaceType.DRAGON_MOUND;
    }

    /** Dungeon and landmark variants worth a long footpath (keep in step with TOURIST_VARIANTS in tools/qa/reach_lint.py). */
    private static final Set<String> TOURIST_VARIANTS = Set.of("altar", "monument", "stones", "ruin", "keep", "barrow",
            "nordic_ruin", "moon_temple", "dwemer_ruin", "ayleid_ruin", "velothi_tomb", "xanmeer", "daedric_ruin", "tower",
            "mage_tower", "watchtower", "lighthouse", "dragon_lair", "imperial_crypt", "yokudan_tomb", "orc_ruin",
            "giant_camp", "fort_mage", "fort_vampire", "oblivion_gate", "pass", "peak", "waterfall", "sixth_house_base",
            "crystal_vault", "tree_tomb", "breton_crypt", "ice_cave");
    private static final Set<String> MINOR_LANDMARKS = Set.of("clearing", "rock", "source", "island", "district");

    /**
     * Tourist-grade destinations (shrines, stones, word walls, dragon mounds, forts, marquee ruins and tombs): they get
     * a footpath from the nearest road even when it is farther than an ordinary trail goes.
     */
    static boolean isTourist(Place p) {
        PlaceType t = p.type();
        String v = p.variant() == null ? "" : p.variant();
        return switch (t) {
            case WORD_WALL, DRAGON_MOUND, DAEDRIC_SHRINE, STANDING_STONE, FORT -> true;
            case LANDMARK -> !MINOR_LANDMARKS.contains(v);
            case DUNGEON_FIXED -> TOURIST_VARIANTS.contains(v);
            default -> false;
        };
    }

    /**
     * Fallback where roads meet a place the settlement plan cannot predict gates for ({@link GateEnds} is used first):
     * the main gate guess (local south of its layout, past its walls and bridges) for capitals and cities, the centre
     * for everything else; never a point in water (the nearest dry one is taken, as for a town on a river).
     */
    static int[] endpoint(Place p) {
        if (p.type() != PlaceType.CAPITAL && p.type() != PlaceType.CITY) return GateEnds.dryNear(p.x(), p.z(), 96);
        int reach;
        Direction back;
        try {
            reach = Math.max(p.radius(), Cities.reach(p)) + 8;
            back = Cities.back(p);
        } catch (RuntimeException e) {
            reach = p.radius() + 8;
            back = Direction.NORTH;
        }
        // the main gate (local south); a harbour city whose gate faces the water uses a side gate, else the centre
        MapSample s = new MapSample();
        Direction gate = back.getOpposite();
        for (Direction d : new Direction[]{gate, gate.getClockWise(), gate.getCounterClockWise(), back}) {
            int x = p.x() + d.getStepX() * reach, z = p.z() + d.getStepZ() * reach;
            TamrielMap.sample(x, z, s);
            if (s.region != 0 && s.coastDistance > 12 && !s.inWater() && s.lakeDistance < -12) return new int[]{x, z};
        }
        return GateEnds.dryNear(p.x(), p.z(), 96);
    }

    // ------------------------------------------------------------------ named roads

    private static List<Named> named;

    static synchronized List<Named> named() {
        if (named != null) return named;
        List<Named> out = new ArrayList<>();
        try (InputStream in = RoadGraph.class.getResourceAsStream("/skycraft/roads/named_roads.json")) {
            if (in != null) {
                JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (JsonElement e : (JsonArray) root) {
                    JsonObject o = e.getAsJsonObject();
                    List<String> route = new ArrayList<>();
                    for (JsonElement r : o.getAsJsonArray("route")) route.add(r.getAsString());
                    byte kind = switch (o.has("class") ? o.get("class").getAsString() : "highway") {
                        case "road" -> Road.ROAD;
                        case "lane" -> Road.LANE;
                        default -> Road.HIGHWAY;
                    };
                    out.add(new Named(o.get("id").getAsString(), kind, route,
                            o.has("bridge") ? o.get("bridge").getAsInt() : DEFAULT_BRIDGE));
                }
            }
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Skycraft roads: could not read named_roads.json", e);
        }
        named = List.copyOf(out);
        return named;
    }

    // ------------------------------------------------------------------ the graph

    static Graph build() {
        long t0 = System.nanoTime();
        List<Place> nodes = new ArrayList<>();
        Map<Place, Integer> land = new HashMap<>();
        for (Place p : Places.all()) {
            if (!p.type().isSettlement() || isSpur(p)) continue;
            int l = Landmass.near(p.x(), p.z(), 160);
            if (l <= Landmass.SEA) {
                Skycraft.LOGGER.warn("Skycraft roads: settlement {} at {}, {} is not on land; no roads", p.id(), p.x(), p.z());
                continue;
            }
            nodes.add(p);
            land.put(p, l);
        }
        Map<String, Place> byId = new HashMap<>();
        for (Place p : nodes) byId.put(p.id(), p);

        Links links = new Links();
        // 1. named roads, split where they pass other settlements
        for (Named n : named()) {
            for (int i = 0; i + 1 < n.route().size(); i++) {
                Place a = byId.get(n.route().get(i)), b = byId.get(n.route().get(i + 1));
                if (a == null || b == null) {
                    Skycraft.LOGGER.warn("Skycraft roads: named road {} names unknown place {} / {}", n.id(), n.route().get(i), n.route().get(i + 1));
                    continue;
                }
                if (n.bridge() <= DEFAULT_BRIDGE && !land.get(a).equals(land.get(b))) continue;
                links.addSplit(nodes, land, new Edge(a, b, n.kind(), n.id(), n.bridge()));
            }
        }
        // 2. greedy spanner per landmass over towns, villages and cities (farms come last)
        List<Place> hubs = new ArrayList<>();
        for (Place p : nodes) if (rank(p) < 3) hubs.add(p);
        List<double[]> candidates = new ArrayList<>(); // {weight, i, j}
        for (int i = 0; i < hubs.size(); i++) {
            Place a = hubs.get(i);
            for (int j = i + 1; j < hubs.size(); j++) {
                Place b = hubs.get(j);
                if (!land.get(a).equals(land.get(b))) continue;
                double d = Math.sqrt(a.distSqr(b.x(), b.z()));
                int limit = rank(a) == 0 && rank(b) == 0 ? MAJOR_LINK : MINOR_LINK;
                if (d > limit || seaRun(a.x(), a.z(), b.x(), b.z()) > 2 * DEFAULT_BRIDGE) continue; // a bay between them
                candidates.add(new double[]{d * terrainFactor(a.x(), a.z(), b.x(), b.z()), i, j});
            }
        }
        candidates.sort(Comparator.comparingDouble(c -> c[0]));
        for (double[] c : candidates) {
            Place a = hubs.get((int) c[1]), b = hubs.get((int) c[2]);
            if (links.distance(a, b, c[0] * STRETCH) <= c[0] * STRETCH) continue;
            byte kind = rank(a) == 0 && rank(b) == 0 ? Road.HIGHWAY : Road.ROAD;
            links.addSplit(nodes, land, new Edge(a, b, kind, "", DEFAULT_BRIDGE));
        }
        // 3. hubs the spanner could not reach (all links over the limit): join each group to the nearest other one
        joinComponents(links, hubs, land);
        // 4. farms: one lane to the nearest settlement already on the network (a farm on it only if much nearer),
        //    nearest farms first, so farm lanes chain outwards but never form a group of their own
        List<Place> attached = new ArrayList<>(hubs);
        List<Place> farms = new ArrayList<>();
        for (Place f : nodes) if (rank(f) == 3 && links.degree(f) == 0) farms.add(f);
        while (!farms.isEmpty()) {
            Place bestFarm = null, best = null;
            double bestD = Double.MAX_VALUE;
            for (Place f : farms) {
                for (Place o : attached) {
                    if (!land.get(o).equals(land.get(f))) continue;
                    double d = Math.sqrt(o.distSqr(f.x(), f.z())) * (rank(o) == 3 ? 1.6 : 1.0);
                    if (d < bestD) {
                        bestD = d;
                        best = o;
                        bestFarm = f;
                    }
                }
            }
            if (bestFarm == null || bestD > FARM_LINK * 2) break;
            links.add(new Edge(bestFarm, best, Road.LANE, "", DEFAULT_BRIDGE));
            farms.remove(bestFarm);
            attached.add(bestFarm);
        }
        // farms on a landmass without any town (Solstheim's farm has Raven Rock; a lone homestead isle has nobody)
        for (Place f : farms) {
            Place best = null;
            double bestD = Double.MAX_VALUE;
            for (Place o : nodes) {
                if (o == f || !land.get(o).equals(land.get(f))) continue;
                double d = o.distSqr(f.x(), f.z());
                if (d < bestD) {
                    bestD = d;
                    best = o;
                }
            }
            if (best != null) links.add(new Edge(f, best, Road.LANE, "", DEFAULT_BRIDGE));
        }
        Skycraft.LOGGER.info("Skycraft roads: road graph of {} settlements, {} links ({} ms)", nodes.size(), links.edges.size(),
                (System.nanoTime() - t0) / 1_000_000);
        return new Graph(nodes, List.copyOf(links.edges), land);
    }

    private static void joinComponents(Links links, List<Place> hubs, Map<Place, Integer> land) {
        while (true) {
            Map<Place, Integer> comp = links.components(hubs);
            double best = Double.MAX_VALUE;
            Place ba = null, bb = null;
            for (Place a : hubs) {
                for (Place b : hubs) {
                    if (comp.get(a) >= comp.get(b) || !land.get(a).equals(land.get(b))) continue;
                    double d = a.distSqr(b.x(), b.z());
                    if (d < best) {
                        best = d;
                        ba = a;
                        bb = b;
                    }
                }
            }
            if (ba == null) return;
            links.add(new Edge(ba, bb, rank(ba) == 0 && rank(bb) == 0 ? Road.HIGHWAY : Road.ROAD, "", DEFAULT_BRIDGE));
        }
    }

    /**
     * How much harder than flat ground the straight line is: mountains (ridge strength and climbing), lakes and rivers
     * along the way cost more, the map's road corridors less. Only a ranking aid for choosing links.
     */
    static double terrainFactor(int x0, int z0, int x1, int z1) {
        double len = Math.sqrt((double) (x1 - x0) * (x1 - x0) + (double) (z1 - z0) * (z1 - z0));
        int steps = Math.max(2, (int) (len / 64));
        MapSample s = new MapSample();
        double ridge = 0, climb = 0, water = 0, road = 0;
        float last = Float.NaN;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            TamrielMap.sample((int) Math.round(x0 + (x1 - x0) * t), (int) Math.round(z0 + (z1 - z0) * t), s);
            ridge += s.ridge;
            road += s.road;
            if (s.coastDistance < 0 || s.inWater()) water++;
            if (!Float.isNaN(last)) climb += Math.abs(s.elevation - last);
            last = s.elevation;
        }
        int n = steps + 1;
        return 1.0 + 1.6 * ridge / n + 6.0 * climb / Math.max(1, len) + 2.5 * water / n - 0.25 * road / n;
    }

    /** The longest stretch of open sea (blocks) on the straight line between two points. */
    static double seaRun(int x0, int z0, int x1, int z1) {
        double len = Math.sqrt((double) (x1 - x0) * (x1 - x0) + (double) (z1 - z0) * (z1 - z0));
        int steps = Math.max(2, (int) (len / 16));
        MapSample s = new MapSample();
        double run = 0, best = 0, step = len / steps;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            TamrielMap.sample((int) Math.round(x0 + (x1 - x0) * t), (int) Math.round(z0 + (z1 - z0) * t), s);
            if (s.region == 0 || s.coastDistance < 0) {
                run += step;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
        }
        return best;
    }

    /** The links chosen so far, with adjacency for detour checks. */
    private static final class Links {
        final List<Edge> edges = new ArrayList<>();
        final Map<Place, List<Edge>> adj = new LinkedHashMap<>();
        final Set<String> pairs = new HashSet<>();

        int degree(Place p) {
            List<Edge> l = adj.get(p);
            return l == null ? 0 : l.size();
        }

        boolean add(Edge e) {
            if (e.a() == e.b()) return false;
            String k = e.a().id().compareTo(e.b().id()) < 0 ? e.a().id() + "|" + e.b().id() : e.b().id() + "|" + e.a().id();
            if (!pairs.add(k)) return false;
            edges.add(e);
            adj.computeIfAbsent(e.a(), x -> new ArrayList<>()).add(e);
            adj.computeIfAbsent(e.b(), x -> new ArrayList<>()).add(e);
            return true;
        }

        /** Adds the link, routed through settlements that lie just beside it (in order along it). */
        void addSplit(List<Place> nodes, Map<Place, Integer> land, Edge e) {
            Place a = e.a(), b = e.b();
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double len2 = dx * dx + dz * dz, len = Math.sqrt(len2);
            List<double[]> via = new ArrayList<>(); // {t, index}
            if (len > 200) {
                double tolerance = Math.min(260, Math.max(60, len * 0.12));
                for (int i = 0; i < nodes.size(); i++) {
                    Place c = nodes.get(i);
                    if (c == a || c == b || rank(c) == 3 || !land.get(c).equals(land.get(a))) continue;
                    double t = ((c.x() - a.x()) * dx + (c.z() - a.z()) * dz) / len2;
                    if (t < 0.06 || t > 0.94) continue;
                    double px = a.x() + dx * t - c.x(), pz = a.z() + dz * t - c.z();
                    if (Math.sqrt(px * px + pz * pz) > tolerance) continue;
                    via.add(new double[]{t, i});
                }
            }
            if (via.isEmpty()) {
                add(e);
                return;
            }
            via.sort(Comparator.comparingDouble(v -> v[0]));
            Place prev = a;
            for (double[] v : via) {
                Place c = nodes.get((int) v[1]);
                add(new Edge(prev, c, e.kind(), e.name(), e.maxBridge()));
                prev = c;
            }
            add(new Edge(prev, b, e.kind(), e.name(), e.maxBridge()));
        }

        /** Network distance from a to b (Euclidean link lengths), or +inf beyond {@code limit}. */
        double distance(Place a, Place b, double limit) {
            Map<Place, Double> dist = new HashMap<>();
            PriorityQueue<Object[]> open = new PriorityQueue<>(Comparator.comparingDouble(o -> (Double) o[0]));
            dist.put(a, 0.0);
            open.add(new Object[]{0.0, a});
            while (!open.isEmpty()) {
                Object[] cur = open.poll();
                double d = (Double) cur[0];
                Place p = (Place) cur[1];
                if (p == b) return d;
                if (d > dist.getOrDefault(p, Double.MAX_VALUE) || d > limit) continue;
                for (Edge e : adj.getOrDefault(p, List.of())) {
                    Place o = e.a() == p ? e.b() : e.a();
                    double nd = d + e.length() * terrainFactorCached(e);
                    if (nd < dist.getOrDefault(o, Double.MAX_VALUE) && nd <= limit) {
                        dist.put(o, nd);
                        open.add(new Object[]{nd, o});
                    }
                }
            }
            return Double.POSITIVE_INFINITY;
        }

        private final Map<Edge, Double> factor = new HashMap<>();

        private double terrainFactorCached(Edge e) {
            return factor.computeIfAbsent(e, k -> terrainFactor(k.a().x(), k.a().z(), k.b().x(), k.b().z()));
        }

        /** Connected-component index of every place. */
        Map<Place, Integer> components(List<Place> places) {
            Map<Place, Integer> comp = new HashMap<>();
            int next = 0;
            for (Place p : places) {
                if (comp.containsKey(p)) continue;
                int id = next++;
                List<Place> stack = new ArrayList<>(List.of(p));
                comp.put(p, id);
                while (!stack.isEmpty()) {
                    Place cur = stack.remove(stack.size() - 1);
                    for (Edge e : adj.getOrDefault(cur, List.of())) {
                        Place o = e.a() == cur ? e.b() : e.a();
                        if (comp.putIfAbsent(o, id) == null) stack.add(o);
                    }
                }
            }
            return comp;
        }
    }

    // ------------------------------------------------------------------ stats

    /** Connected groups of the planned graph per landmass, for the log: "landmass 3: 2 groups, ...". */
    static String componentSummary(Graph g) {
        Links l = new Links();
        for (Edge e : g.edges()) l.add(e);
        Map<Place, Integer> comp = l.components(g.nodes());
        Map<Integer, Set<Integer>> byLand = new java.util.TreeMap<>();
        for (Place p : g.nodes()) byLand.computeIfAbsent(g.landmass().get(p), k -> new HashSet<>()).add(comp.get(p));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Set<Integer>> e : byLand.entrySet()) {
            int count = 0;
            for (Place p : g.nodes()) if (g.landmass().get(p).equals(e.getKey())) count++;
            if (sb.length() > 0) sb.append(", ");
            sb.append("landmass ").append(e.getKey()).append(": ").append(count).append(" places in ")
                    .append(e.getValue().size()).append(e.getValue().size() == 1 ? " network" : " networks");
        }
        return sb.toString();
    }

    static String describe(Edge e) {
        return e.a().id() + " -> " + e.b().id() + Arrays.toString(new Object[]{e.kind(), e.name()});
    }
}
