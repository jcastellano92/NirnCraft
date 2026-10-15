package com.skycraft.roads;

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The seed-independent part of the road network, for the baked road plan ({@code com.skycraft.worldplan}): the links
 * of the {@link RoadGraph}, where each one meets its settlements ({@link GateEnds}), and the rules {@link WorldPlan}
 * uses for trails and gate lanes. Pure map work; nothing here reads a world.
 */
public final class PlanLinks {
    public static final byte HIGHWAY = Road.HIGHWAY, ROAD = Road.ROAD, LANE = Road.LANE, TRAIL = Road.TRAIL;
    public static final int DEFAULT_BRIDGE = RoadGraph.DEFAULT_BRIDGE;
    public static final int TRAIL_RANGE = WorldPlan.TRAIL_RANGE, FOOTPATH_RANGE = WorldPlan.FOOTPATH_RANGE;
    public static final int GATE_SERVED = WorldPlan.GATE_SERVED, GATE_RANGE = WorldPlan.GATE_RANGE;

    /** One planned link: its places, class, historic name (or empty), longest allowed bridge and road ends {x, z}. */
    public record Link(Place a, Place b, byte kind, String name, int maxBridge, int[] endA, int[] endB) {
    }

    /** The settlements of the graph (in registration order) and its links. */
    public record Network(List<Place> nodes, List<Link> links) {
    }

    private PlanLinks() {}

    /** Builds the road graph and resolves every link's two road ends (the gates facing each other). */
    public static Network network() {
        RoadGraph.Graph g = RoadGraph.build();
        Map<Place, Integer> id = new HashMap<>();
        for (int i = 0; i < g.nodes().size(); i++) id.put(g.nodes().get(i), i);
        Map<Place, List<int[]>> neighbours = new HashMap<>();
        for (RoadGraph.Edge e : g.edges()) {
            neighbours.computeIfAbsent(e.a(), k -> new ArrayList<>()).add(new int[]{id.get(e.b()), e.b().x(), e.b().z()});
            neighbours.computeIfAbsent(e.b(), k -> new ArrayList<>()).add(new int[]{id.get(e.a()), e.a().x(), e.a().z()});
        }
        Map<Place, int[][]> sorted = new HashMap<>();
        for (Map.Entry<Place, List<int[]>> en : neighbours.entrySet()) {
            List<int[]> l = new ArrayList<>(en.getValue());
            l.sort(java.util.Comparator.comparingInt(r -> r[0]));
            sorted.put(en.getKey(), l.toArray(new int[0][]));
        }
        List<Link> links = new ArrayList<>();
        for (RoadGraph.Edge e : g.edges()) {
            int[] ea = end(e.a(), id.get(e.b()), e.b(), sorted.get(e.a()));
            int[] eb = end(e.b(), id.get(e.a()), e.a(), sorted.get(e.b()));
            links.add(new Link(e.a(), e.b(), e.kind(), e.name(), e.maxBridge(), ea, eb));
        }
        return new Network(List.copyOf(g.nodes()), List.copyOf(links));
    }

    private static int[] end(Place p, int otherId, Place other, int[][] neighbours) {
        int[] g = GateEnds.pick(p, otherId, other.x(), other.z(), neighbours == null ? new int[0][] : neighbours);
        return g != null ? g : RoadGraph.endpoint(p);
    }

    /** Where a road meets a place that is not a graph settlement (a trail destination): its dry centre. */
    public static int[] endpoint(Place p) {
        return RoadGraph.endpoint(p);
    }

    /** The gates a road can reach, main gate first. */
    public static List<int[]> gates(Place p) {
        return GateEnds.usable(p);
    }

    /** Radius around the centre inside which the town is built (0 if unknown). */
    public static int coreRadius(Place p) {
        return GateEnds.coreRadius(p);
    }

    /** 0 = capital/city, 1 = town, 2 = village, 3 = farm. */
    public static int rank(Place p) {
        return RoadGraph.rank(p);
    }

    public static boolean isSpur(Place p) {
        return RoadGraph.isSpur(p);
    }

    public static boolean isTrailDestination(PlaceType t) {
        return RoadGraph.isTrailDestination(t);
    }

    public static boolean isTrailDestination(Place p) {
        return RoadGraph.isTrailDestination(p);
    }

    public static boolean isTourist(Place p) {
        return RoadGraph.isTourist(p);
    }

    /** True on a traced historic route (strongly preferred by the planner). */
    public static boolean onCorridor(int x, int z) {
        return Corridors.on(x, z);
    }

    public static boolean connected(int x0, int z0, int x1, int z1) {
        return Landmass.connected(x0, z0, x1, z1);
    }
}
