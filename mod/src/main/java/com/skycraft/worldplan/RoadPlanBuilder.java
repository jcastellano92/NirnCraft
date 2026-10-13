package com.skycraft.worldplan;

import com.skycraft.Skycraft;
import com.skycraft.roads.PlanLinks;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Computes the {@link RoadPlan} from the map and the places alone (no world, no seed): the links of the road graph
 * (highways, then roads, then farm lanes), then gate lanes, roadside-inn spurs and trails to the destinations, in that
 * order, so each class joins the ones planned before it. Per class the routes are found in parallel
 * ({@link PlanRouter}); then, one by one in a fixed order, each route is cut where it runs along a road already planned
 * (the piece ends on that road: a junction), given switchbacks where the slope exceeds its grade cap, a vertical
 * profile and its spans. The result is identical on every run.
 */
public final class RoadPlanBuilder {
    public static final String BAKE_NAME = "roadplan";
    /** Polyline resolution (blocks). */
    static final double SPACING = 4.0;
    /** Expansion budget per A* stage. */
    private static final int BUDGET = 30000;
    private static final int NORMAL_BRIDGE = 48, RETRY_BRIDGE = 160, TRAIL_BRIDGE = 24;
    /** Accepted-road index bucket (blocks). */
    private static final int IDX_SHIFT = 5;
    /** Trails closer than this to their road get none (the junction sign will do). */
    private static final int TRAIL_MIN = 20;
    /** A road keeps this far outside the ring of gates of a settlement it ends at (blocks), see {@code avoid}. */
    private static final int OWN_MARGIN = 16;
    /** Height differences (blocks) that make a cut or fill span, a gap bridge, a tunnel. */
    static final float CUT_FILL = 2f, GAP = 3f, TUNNEL = 12f;
    static final float GAP_BRIDGE_MAX = 80f, TUNNEL_MIN = 16f;
    /** A footpath steeper than this is stairs. */
    static final float STAIRS_GRADE = 0.25f;
    /** Switchback stretches tried per road piece (worst first). */
    private static final int SWITCHBACK_ROUNDS = 24;
    /** Arc length (blocks, plus the half width) at each end of a piece that counts as its junction with another road. */
    static final double JUNCTION_ZONE = 14;

    private RoadPlanBuilder() {}

    // ------------------------------------------------------------------ class parameters

    /** Grade cap (rise per block): main roads 1:8, tracks 1:5, footpaths steep (steps). */
    static double gradeCap(byte kind) {
        return switch (kind) {
            case 0, 1 -> 1.0 / 8.0;
            case 2 -> 1.0 / 5.0;
            default -> 1.0;
        };
    }

    static float halfWidth(byte kind) {
        return switch (kind) {
            case 0 -> 2.5f;
            case 1 -> 2f;
            case 2 -> 1.5f;
            default -> 1f;
        };
    }

    static float clearance(byte kind) {
        return kind <= 1 ? 3f : 2f;
    }

    /** Width (blocks) of the corridor a switchback may zigzag across; 0 = none (footpaths take stairs). */
    static float corridor(byte kind) {
        return switch (kind) {
            case 0 -> 48f;
            case 1 -> 40f;
            case 2 -> 28f;
            default -> 0f;
        };
    }

    /** |profile - ground| that sends a main road or lane into switchbacks. */
    private static float switchbackTrigger(byte kind) {
        return kind <= 1 ? 6f : 5f;
    }

    // ------------------------------------------------------------------ entry points

    public static byte[] computeLive() throws IOException {
        StringBuilder stats = new StringBuilder();
        byte[] out = build(stats).write();
        Skycraft.LOGGER.info("[bake] roadplan: {}", stats);
        return out;
    }

    /** Builds the plan; {@code stats} (if not null) receives a summary line. */
    static RoadPlan build(StringBuilder stats) {
        return new RoadPlanBuilder.Run().run(stats);
    }

    // ------------------------------------------------------------------ the run

    /** A planned piece of road, before it is frozen into the plan. */
    private static final class Seg {
        int link;
        byte kind;
        boolean spur;
        float hw;
        int joinA = RoadPlan.JOIN_NONE, joinB = RoadPlan.JOIN_NONE;
        double[] x, z;
        float[] h, g, w;
        byte[] span;
    }

    /** One link to plan. */
    private record Task(int link, byte kind, boolean spur, double ax, double az, double bx, double bz, int siteA, int siteB,
                        int maxBridge) {
    }

    private static final class Run {
        PlanTerrain terrain;
        final List<Seg> segs = new ArrayList<>();
        final Long2ObjectOpenHashMap<LongArrayList> index = new Long2ObjectOpenHashMap<>();
        final MapSample sample = new MapSample();
        final PlanTerrain.Col col = new PlanTerrain.Col();
        final List<String> linkA = new ArrayList<>(), linkB = new ArrayList<>(), linkName = new ArrayList<>();
        final ByteArrayList linkKind = new ByteArrayList(), linkStatus = new ByteArrayList();
        final IntArrayList linkBridge = new IntArrayList();
        List<Place> nodes;
        int[] nodeCore;
        /** Authored buildings and landmarks of each node's city plan as (x, z, r) triples (roads keep off them), or null. */
        int[][] nodePins;
        int failed, merged, pinMiss, switchbacks, dropped;
        ExecutorService pool;

        RoadPlan run(StringBuilder stats) {
            long t0 = System.nanoTime();
            PlanLinks.Network net = PlanLinks.network();
            nodes = net.nodes();
            List<Place> places = Places.all();
            terrain = new PlanTerrain(places, p -> p.type().isSettlement() || PlanLinks.isTrailDestination(p.type()));
            Map<String, Integer> siteOf = new HashMap<>();
            for (int i = 0; i < terrain.siteId.length; i++) siteOf.putIfAbsent(terrain.siteId[i], i);
            nodeCore = new int[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) nodeCore[i] = PlanLinks.coreRadius(nodes.get(i));
            nodePins = new int[nodes.size()][];
            for (int i = 0; i < nodes.size(); i++) nodePins[i] = pins(nodes.get(i));
            long tSites = System.nanoTime();
            int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
            String env = System.getenv("SKYCRAFT_BAKE_THREADS");
            if (env != null) {
                try {
                    threads = Math.max(1, Integer.parseInt(env.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "Skycraft road plan");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            try {
                // 1-3. the graph: highways, roads, farm lanes
                for (byte kind = PlanLinks.HIGHWAY; kind <= PlanLinks.LANE; kind++) {
                    List<Task> tasks = new ArrayList<>();
                    for (PlanLinks.Link l : net.links()) {
                        if (l.kind() != kind) continue;
                        int id = addLink(l.a().id(), l.b().id(), l.name(), kind, l.maxBridge());
                        int bridge = l.maxBridge() > PlanLinks.DEFAULT_BRIDGE ? l.maxBridge() : Math.min(l.maxBridge(), NORMAL_BRIDGE);
                        linkBridge.set(id, bridge);
                        tasks.add(new Task(id, kind, false, l.endA()[0] + 0.5, l.endA()[1] + 0.5, l.endB()[0] + 0.5, l.endB()[1] + 0.5,
                                siteOf.getOrDefault(l.a().id(), -1), siteOf.getOrDefault(l.b().id(), -1), bridge));
                    }
                    wave(tasks, true);
                }
                // 4. gate lanes: every gate no road serves gets a lane to the nearest road
                List<Task> lanes = new ArrayList<>();
                for (Place p : nodes) {
                    if (PlanLinks.isSpur(p)) continue;
                    List<int[]> gates = PlanLinks.gates(p);
                    for (int gi = 0; gi < gates.size() && gi < 8; gi++) {
                        int[] g = gates.get(gi);
                        if (nearestVertex(g[0] + 0.5, g[1] + 0.5, PlanLinks.GATE_SERVED, true) != -1) continue;
                        long hit = nearestVertex(g[0] + 0.5, g[1] + 0.5, PlanLinks.GATE_RANGE, true);
                        if (hit == -1) continue;
                        Seg s = segs.get((int) (hit >>> 32));
                        int v = (int) hit;
                        int id = addLink(p.id(), "@gate" + gi, "", PlanLinks.LANE, TRAIL_BRIDGE);
                        lanes.add(new Task(id, PlanLinks.LANE, true, g[0] + 0.5, g[1] + 0.5, s.x[v], s.z[v],
                                siteOf.getOrDefault(p.id(), -1), -1, TRAIL_BRIDGE));
                    }
                }
                wave(lanes, false);
                // 5-6. roadside inns and trail destinations: a footpath from the nearest road
                List<Task> trails = new ArrayList<>();
                for (Place p : places) {
                    boolean spur = p.type().isSettlement() && PlanLinks.isSpur(p);
                    if (!spur && !PlanLinks.isTrailDestination(p.type())) continue;
                    if (!spur && insideTown(p)) continue;
                    int range = !spur && PlanLinks.isTourist(p) ? PlanLinks.FOOTPATH_RANGE : PlanLinks.TRAIL_RANGE;
                    int[] e = PlanLinks.endpoint(p);
                    long hit = nearestVertex(e[0] + 0.5, e[1] + 0.5, range, false);
                    if (hit == -1) continue;
                    Seg s = segs.get((int) (hit >>> 32));
                    int v = (int) hit;
                    if (Math.hypot(s.x[v] - p.x(), s.z[v] - p.z()) < TRAIL_MIN) continue;
                    int id = addLink(p.id(), "@road", "", PlanLinks.TRAIL, TRAIL_BRIDGE);
                    trails.add(new Task(id, PlanLinks.TRAIL, true, e[0] + 0.5, e[1] + 0.5, s.x[v], s.z[v],
                            siteOf.getOrDefault(p.id(), -1), -1, TRAIL_BRIDGE));
                }
                wave(trails, false);
            } finally {
                pool.shutdownNow();
            }
            RoadPlan plan = freeze();
            if (stats != null) {
                int[] spans = new int[RoadPlan.SPAN_NAMES.length];
                for (int k = 0; k < plan.spanType.length; k++) spans[plan.spanType[k]]++;
                StringBuilder sp = new StringBuilder();
                for (int k = 1; k < spans.length; k++) sp.append(k > 1 ? ", " : "").append(spans[k]).append(' ').append(RoadPlan.SPAN_NAMES[k]);
                stats.append(String.format(java.util.Locale.ROOT,
                        "%d links (%d failed, %d merged into other roads), %d segments, %d vertices; %.0f km (highway %.0f, road %.0f, lane %.0f, trail %.0f); spans: %s; %d switchback stretches, %d junction pins missed, %d slivers dropped; %d sites; %d ms (sites %d ms, %d threads)",
                        plan.links(), failed, merged, plan.segments(), plan.vertices(), plan.length(-1) / 1000, plan.length(0) / 1000,
                        plan.length(1) / 1000, plan.length(2) / 1000, plan.length(3) / 1000, sp, switchbacks, pinMiss, dropped,
                        plan.sites(), (System.nanoTime() - t0) / 1_000_000, (tSites - t0) / 1_000_000, threads));
            }
            return plan;
        }

        int addLink(String a, String b, String name, byte kind, int bridge) {
            linkA.add(a);
            linkB.add(b);
            linkName.add(name == null ? "" : name);
            linkKind.add(kind);
            linkStatus.add(RoadPlan.LINK_OK);
            linkBridge.add(bridge);
            return linkA.size() - 1;
        }

        /** True if a destination lies inside a graph settlement (a temple, a keep...: part of the town). */
        boolean insideTown(Place p) {
            for (Place s : nodes) {
                long r = s.radius() + 8L;
                if (Math.abs(s.x() - p.x()) > r || Math.abs(s.z() - p.z()) > r) continue;
                if (s.distSqr(p.x(), p.z()) <= r * r) return true;
            }
            return false;
        }

        /**
         * P5: the buildings and landmarks a city plan pins (a mill, a tavern by the gate...) are placed whatever the
         * ground, so a road must keep off them; (x, z, r) triples in world blocks, or null without a city plan.
         */
        static int[] pins(Place p) {
            com.skycraft.tamriel.settlement.plan.CityPlan cp = com.skycraft.tamriel.settlement.plan.CityPlans.of(p);
            if (cp == null) return null;
            IntArrayList out = new IntArrayList();
            for (com.skycraft.tamriel.settlement.plan.CityPlan.Building b : cp.buildings) {
                for (int k = 0; k < Math.max(1, b.count()); k++) {
                    int[] w = com.skycraft.tamriel.settlement.plan.Cities.world(p, b.x() + k * b.stepX(), b.z() + k * b.stepZ());
                    out.add(w[0]);
                    out.add(w[1]);
                    out.add(Math.max(b.w(), b.d()) / 2 + Math.max(0, b.margin()) + 3);
                }
            }
            for (com.skycraft.tamriel.settlement.plan.CityPlan.Landmark l : cp.landmarks) {
                int[] w = com.skycraft.tamriel.settlement.plan.Cities.world(p, l.x(), l.z());
                out.add(w[0]);
                out.add(w[1]);
                out.add(Math.max(l.w(), l.d()) / 2 + Math.max(0, l.margin()) + 3);
            }
            return out.isEmpty() ? null : out.toIntArray();
        }

        /** Walled settlement interiors near a link: (x, z, r) triples, as the live planner avoids them. */
        int[] avoid(double ax, double az, double bx, double bz) {
            double mx = (ax + bx) / 2, mz = (az + bz) / 2;
            double reach = Math.hypot(ax - bx, az - bz) / 2 + 400;
            IntArrayList out = new IntArrayList();
            for (int i = 0; i < nodes.size(); i++) {
                Place p = nodes.get(i);
                if (Math.hypot(p.x() - mx, p.z() - mz) > reach) continue;
                boolean city = p.type() == PlaceType.CAPITAL || p.type() == PlaceType.CITY;
                int core = nodeCore[i];
                boolean own = Math.hypot(ax - p.x(), az - p.z()) <= Math.max(p.radius(), core) + 60
                        || Math.hypot(bx - p.x(), bz - p.z()) <= Math.max(p.radius(), core) + 60;
                // P5: a road ending at this settlement keeps out of its walls, fields and outskirts until it turns in at
                // the gate (the ring of gates plus a margin; the gate itself is inside, so the last stretch runs in radially)
                int r = own ? (core > 0 ? core + 14 + OWN_MARGIN : 0) : city ? p.radius() : core;
                if (own && nodePins[i] != null) out.addElements(out.size(), nodePins[i]);
                if (r < 16) continue;
                out.add(p.x());
                out.add(p.z());
                out.add(r);
            }
            return out.toIntArray();
        }

        /** Routes a class of links in parallel, then accepts them one by one in order. */
        void wave(List<Task> tasks, boolean retryWider) {
            LongOpenHashSet cells = new LongOpenHashSet();
            for (Seg s : segs) {
                if (s.spur) continue;
                for (int i = 0; i < s.x.length; i++) cells.add(PlanRouter.cellKey(s.x[i], s.z[i]));
            }
            List<List<double[]>> routes = routeAll(tasks, cells, false);
            if (retryWider) {
                List<Task> again = new ArrayList<>();
                IntArrayList at = new IntArrayList();
                for (int i = 0; i < tasks.size(); i++) {
                    Task t = tasks.get(i);
                    if (routes.get(i) == null && t.maxBridge() < RETRY_BRIDGE) {
                        again.add(new Task(t.link(), t.kind(), t.spur(), t.ax(), t.az(), t.bx(), t.bz(), t.siteA(), t.siteB(), RETRY_BRIDGE));
                        at.add(i);
                    }
                }
                List<List<double[]>> more = routeAll(again, cells, false);
                for (int k = 0; k < again.size(); k++) {
                    if (more.get(k) == null) continue;
                    routes.set(at.getInt(k), more.get(k));
                    tasks.set(at.getInt(k), again.get(k));
                    linkBridge.set(again.get(k).link(), RETRY_BRIDGE);
                }
            }
            for (int i = 0; i < tasks.size(); i++) {
                Task t = tasks.get(i);
                List<double[]> r = routes.get(i);
                if (r == null) {
                    linkStatus.set(t.link(), RoadPlan.LINK_FAILED);
                    failed++;
                    continue;
                }
                accept(t, r);
            }
        }

        List<List<double[]>> routeAll(List<Task> tasks, LongOpenHashSet cells, boolean unused) {
            List<Future<List<double[]>>> futures = new ArrayList<>();
            for (Task t : tasks) {
                PlanRouter.Job job = new PlanRouter.Job(t.ax(), t.az(), t.bx(), t.bz(), t.kind(), t.maxBridge(),
                        avoid(t.ax(), t.az(), t.bx(), t.bz()), cells, BUDGET);
                futures.add(pool.submit(() -> {
                    try {
                        return PlanRouter.route(terrain, job);
                    } catch (RuntimeException e) {
                        Skycraft.LOGGER.warn("[roadplan] routing failed", e);
                        return null;
                    }
                }));
            }
            List<List<double[]>> out = new ArrayList<>();
            for (Future<List<double[]>> f : futures) {
                try {
                    out.add(f.get());
                } catch (Exception e) {
                    out.add(null);
                }
            }
            return out;
        }

        // ------------------------------------------------------------ the accepted-road index

        private static long ikey(int bx, int bz) {
            return ((long) bx << 32) ^ (bz & 0xffffffffL);
        }

        void index(int si) {
            Seg s = segs.get(si);
            for (int i = 0; i + 1 < s.x.length; i++) {
                int x0 = (int) Math.floor(Math.min(s.x[i], s.x[i + 1])) >> IDX_SHIFT, x1 = (int) Math.floor(Math.max(s.x[i], s.x[i + 1])) >> IDX_SHIFT;
                int z0 = (int) Math.floor(Math.min(s.z[i], s.z[i + 1])) >> IDX_SHIFT, z1 = (int) Math.floor(Math.max(s.z[i], s.z[i + 1])) >> IDX_SHIFT;
                for (int bx = x0; bx <= x1; bx++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        index.computeIfAbsent(ikey(bx, bz), k -> new LongArrayList()).add(((long) si << 32) | i);
                    }
                }
            }
        }

        /** Nearest accepted edge within {@code r}: {seg, edge, t, distance, px, pz} or null. */
        double[] nearestEdge(double x, double z, double r) {
            int bx0 = (int) Math.floor(x - r) >> IDX_SHIFT, bx1 = (int) Math.floor(x + r) >> IDX_SHIFT;
            int bz0 = (int) Math.floor(z - r) >> IDX_SHIFT, bz1 = (int) Math.floor(z + r) >> IDX_SHIFT;
            double best = r;
            double[] out = null;
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    LongArrayList l = index.get(ikey(bx, bz));
                    if (l == null) continue;
                    for (int k = 0; k < l.size(); k++) {
                        long e = l.getLong(k);
                        Seg s = segs.get((int) (e >>> 32));
                        int i = (int) e;
                        double ax = s.x[i], az = s.z[i], dx = s.x[i + 1] - ax, dz = s.z[i + 1] - az;
                        double l2 = dx * dx + dz * dz;
                        double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / l2));
                        double px = ax + dx * t, pz = az + dz * t;
                        double d = Math.hypot(px - x, pz - z);
                        if (d < best) {
                            best = d;
                            out = new double[]{(int) (e >>> 32), i, t, d, px, pz};
                        }
                    }
                }
            }
            return out;
        }

        /**
         * Nearest accepted road vertex within {@code range} as (seg << 32 | vertex), or -1. Footpaths and gate lanes
         * only count with {@code anyKind}; never a vertex on or beside a water crossing.
         */
        long nearestVertex(double x, double z, int range, boolean anyKind) {
            int bx0 = (int) Math.floor(x - range) >> IDX_SHIFT, bx1 = (int) Math.floor(x + range) >> IDX_SHIFT;
            int bz0 = (int) Math.floor(z - range) >> IDX_SHIFT, bz1 = (int) Math.floor(z + range) >> IDX_SHIFT;
            double best = (double) range * range;
            long out = -1;
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    LongArrayList l = index.get(ikey(bx, bz));
                    if (l == null) continue;
                    for (int k = 0; k < l.size(); k++) {
                        long e = l.getLong(k);
                        int si = (int) (e >>> 32), i = (int) e;
                        Seg s = segs.get(si);
                        if (!anyKind && s.spur) continue;
                        if (wetNear(s, i)) continue;
                        double dx = s.x[i] - x, dz = s.z[i] - z, d = dx * dx + dz * dz;
                        if (d < best) {
                            best = d;
                            out = ((long) si << 32) | i;
                        }
                    }
                }
            }
            return out;
        }

        private static boolean wetNear(Seg s, int i) {
            for (int k = Math.max(0, i - 2); k <= Math.min(s.x.length - 1, i + 2); k++) {
                byte t = s.span[k];
                if (t == RoadPlan.BRIDGE || t == RoadPlan.CULVERT || t == RoadPlan.FORD || t == RoadPlan.TUNNEL) return true;
            }
            return false;
        }

        float heightOn(int si, int edge, double t) {
            Seg s = segs.get(si);
            return (float) (s.h[edge] + (s.h[edge + 1] - s.h[edge]) * t);
        }

        // ------------------------------------------------------------ accepting a route

        void accept(Task task, List<double[]> route) {
            List<double[]> pts = PlanRouter.resample(route, SPACING);
            int n = pts.size();
            float hw = halfWidth(task.kind());
            // which vertices run along a road already planned
            boolean[] shared = new boolean[n];
            double[][] proj = new double[n][];
            for (int i = 0; i < n; i++) {
                double[] p = pts.get(i);
                double[] e = nearestEdge(p[0], p[1], hw + 2.5 + 1.5);
                if (e == null) continue;
                Seg o = segs.get((int) e[0]);
                if (e[3] < hw + o.hw + 1.5) {
                    shared[i] = true;
                    proj[i] = e;
                }
            }
            int pieces = 0;
            int i = 0;
            while (i < n) {
                if (shared[i]) {
                    i++;
                    continue;
                }
                int j = i;
                while (j + 1 < n && !shared[j + 1]) j++;
                List<double[]> piece = new ArrayList<>(pts.subList(i, j + 1));
                int joinA = RoadPlan.JOIN_NONE, joinB = RoadPlan.JOIN_NONE;
                float pinA = Float.NaN, pinB = Float.NaN;
                if (i > 0) {
                    double[] e = proj[i - 1];
                    piece.add(0, new double[]{e[4], e[5]});
                    joinA = (int) e[0];
                    pinA = heightOn((int) e[0], (int) e[1], e[2]);
                } else if (task.siteA() >= 0) {
                    joinA = RoadPlan.JOIN_SITE;
                    pinA = terrain.sitePad[task.siteA()];
                }
                if (j < n - 1) {
                    double[] e = proj[j + 1];
                    piece.add(new double[]{e[4], e[5]});
                    joinB = (int) e[0];
                    pinB = heightOn((int) e[0], (int) e[1], e[2]);
                } else if (task.siteB() >= 0) {
                    joinB = RoadPlan.JOIN_SITE;
                    pinB = terrain.sitePad[task.siteB()];
                }
                i = j + 1;
                double len = 0;
                for (int k = 1; k < piece.size(); k++) len += Math.hypot(piece.get(k)[0] - piece.get(k - 1)[0], piece.get(k)[1] - piece.get(k - 1)[1]);
                if (len < 8 || (joinA >= 0 && joinA == joinB && len < 96)) { // a sliver or a short loop beside one road
                    dropped++;
                    continue;
                }
                Seg s = new Seg();
                s.link = task.link();
                s.kind = task.kind();
                s.spur = task.spur();
                s.hw = hw;
                s.joinA = joinA;
                s.joinB = joinB;
                shape(s, piece, pinA, pinB);
                segs.add(s);
                index(segs.size() - 1);
                pieces++;
            }
            if (pieces == 0) {
                merged++;
                linkStatus.set(task.link(), RoadPlan.LINK_MERGED);
            }
        }

        /** Samples, gives switchbacks where needed, profiles and classifies one piece. */
        void shape(Seg s, List<double[]> pts, float pinA, float pinB) {
            Poly p = new Poly(withCrossings(pts));
            p.sampleAll(this);
            float corridor = corridor(s.kind);
            if (corridor > 0) {
                for (int iter = 0; iter < SWITCHBACK_ROUNDS; iter++) {
                    Profile.run(p, s.kind, pinA, pinB);
                    if (!switchback(p, s.kind, corridor)) break;
                }
            }
            Profile.run(p, s.kind, pinA, pinB);
            if (!Float.isNaN(pinA) && Math.abs(p.h[0] - pinA) > 0.6f) pinMiss++;
            if (!Float.isNaN(pinB) && Math.abs(p.h[p.n - 1] - pinB) > 0.6f) pinMiss++;
            s.x = p.x;
            s.z = p.z;
            s.h = p.h;
            s.g = p.g;
            s.w = p.w;
            s.span = p.span;
        }

        /**
         * The polyline with a vertex added on every stream narrower than the vertex spacing that an edge crosses
         * between two dry vertices (checked every block), so no crossing goes unseen.
         */
        List<double[]> withCrossings(List<double[]> pts) {
            List<double[]> out = new ArrayList<>(pts.size() + 8);
            out.add(pts.get(0));
            boolean lastWet = wet(pts.get(0));
            for (int i = 1; i < pts.size(); i++) {
                double[] a = pts.get(i - 1), b = pts.get(i);
                boolean bWet = wet(b);
                if (!lastWet && !bWet) {
                    double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                    for (int k = 1; k < len; k++) {
                        double t = k / len;
                        double[] q = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
                        if (wet(q)) {
                            out.add(q);
                            break;
                        }
                    }
                }
                out.add(b);
                lastWet = bWet;
            }
            return out;
        }

        private boolean wet(double[] p) {
            terrain.sample((int) Math.floor(p[0]), (int) Math.floor(p[1]), sample, col);
            return !Float.isNaN(col.water);
        }

        /**
         * Where the profile strays far from the ground (the slope exceeds the grade cap), replaces the steepest window
         * of the piece (about two corridor widths long) by a zigzag across the corridor, long enough to climb it at the
         * cap. Called repeatedly, worst window first. True if it changed something (the polyline or the skip marks).
         */
        boolean switchback(Poly p, byte kind, float corridor) {
            float trigger = switchbackTrigger(kind);
            double cap = gradeCap(kind);
            boolean off = false;
            for (int i = 0; i < p.n && !off; i++) off = eligible(p, i) && Math.abs(p.h[i] - p.g[i]) > trigger;
            if (!off) return false;
            double win = corridor * 2;
            int a = -1, b = -1;
            double steepest = cap * 1.1;
            for (int i = 0; i < p.n; i++) {
                if (!eligible(p, i)) continue;
                int j = i;
                while (j + 1 < p.n && p.s[j] - p.s[i] < win && eligible(p, j + 1)) j++;
                double len = p.s[j] - p.s[i];
                if (len < win * 0.75) continue;
                double grade = Math.abs(p.g[j] - p.g[i]) / len;
                if (grade > steepest) {
                    steepest = grade;
                    a = i;
                    b = j;
                }
            }
            if (a < 0) { // no steep window left: what remains is for cut and fill
                for (int i = 0; i < p.n; i++) if (Math.abs(p.h[i] - p.g[i]) > trigger) p.skip[i] = true;
                return true;
            }
            double rise = Math.abs(p.g[b] - p.g[a]);
            double lreq = rise / (0.8 * cap);
            double cx = p.x[b] - p.x[a], cz = p.z[b] - p.z[a], chord = Math.hypot(cx, cz);
            if (chord < 8) {
                for (int i = a; i <= b; i++) p.skip[i] = true;
                return true;
            }
            double ux = cx / chord, uz = cz / chord, nx = -uz, nz = ux;
            for (float w = corridor; w >= corridor / 4; w /= 2) {
                int k = (int) Math.ceil(Math.sqrt(Math.max(0, lreq * lreq - chord * chord)) / w);
                k = Math.max(2, Math.min(40, k));
                List<double[]> zig = new ArrayList<>();
                zig.add(new double[]{p.x[a], p.z[a]});
                boolean ok = true;
                for (int m = 0; m < k && ok; m++) {
                    double along = chord * (m + 0.5) / k, side = (m % 2 == 0 ? 0.5 : -0.5) * w;
                    double qx = p.x[a] + ux * along + nx * side, qz = p.z[a] + uz * along + nz * side;
                    terrain.sample((int) Math.floor(qx), (int) Math.floor(qz), sample, col);
                    if (!Float.isNaN(col.water)) ok = false;
                    zig.add(new double[]{qx, qz});
                }
                if (!ok) continue;
                zig.add(new double[]{p.x[b], p.z[b]});
                List<double[]> dense = withCrossings(PlanRouter.resample(zig, SPACING));
                for (int qi = 1; qi + 1 < dense.size() && ok; qi++) ok = !wet(dense.get(qi)); // no bridges in a hairpin
                if (!ok) continue;
                float hw = halfWidth(kind);
                double denseLen = 0;
                for (int q = 1; q < dense.size(); q++) denseLen += Math.hypot(dense.get(q)[0] - dense.get(q - 1)[0], dense.get(q)[1] - dense.get(q - 1)[1]);
                double total = p.s[a] + denseLen + (p.s[p.n - 1] - p.s[b]), arc = p.s[a], junction = JUNCTION_ZONE + hw;
                for (int qi = 0; qi < dense.size(); qi++) { // never zigzag across another road (the piece's own junctions aside)
                    double[] q = dense.get(qi);
                    if (qi > 0) arc += Math.hypot(q[0] - dense.get(qi - 1)[0], q[1] - dense.get(qi - 1)[1]);
                    if (arc < junction || total - arc < junction) continue;
                    double[] e = nearestEdge(q[0], q[1], hw + 4);
                    if (e != null && e[3] < hw + segs.get((int) e[0]).hw + 1.5) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                p.replace(a, b, dense, this);
                switchbacks++;
                return true;
            }
            for (int i = a; i <= b; i++) p.skip[i] = true;
            return true;
        }

        private static boolean eligible(Poly p, int i) {
            return Float.isNaN(p.w[i]) && !p.sb[i] && !p.skip[i];
        }

        // ------------------------------------------------------------ freezing

        RoadPlan freeze() {
            RoadPlan.Builder b = new RoadPlan.Builder();
            int ns = terrain.siteId.length;
            b.sites(ns);
            for (int i = 0; i < ns; i++) {
                b.siteId[i] = terrain.siteId[i];
                b.siteType[i] = terrain.siteType[i];
                b.siteX[i] = terrain.siteX[i];
                b.siteZ[i] = terrain.siteZ[i];
                b.siteRadius[i] = terrain.siteRadius[i];
                b.sitePad[i] = terrain.sitePad[i];
            }
            int nl = linkA.size();
            b.links(nl);
            for (int i = 0; i < nl; i++) {
                b.linkA[i] = linkA.get(i);
                b.linkB[i] = linkB.get(i);
                b.linkName[i] = linkName.get(i);
                b.linkKind[i] = linkKind.getByte(i);
                b.linkStatus[i] = linkStatus.getByte(i);
                b.linkBridge[i] = linkBridge.getInt(i);
            }
            b.segs(segs.size());
            int total = 0;
            for (Seg s : segs) total += s.x.length;
            b.vx = new float[total];
            b.vz = new float[total];
            b.vh = new float[total];
            b.vg = new float[total];
            b.vw = new float[total];
            b.vspan = new byte[total];
            ByteArrayList st = new ByteArrayList();
            IntArrayList sf = new IntArrayList(), sto = new IntArrayList(), soff = new IntArrayList(), poff = new IntArrayList();
            RoadPlan.FloatList deck = new RoadPlan.FloatList(), piers = new RoadPlan.FloatList();
            int v = 0;
            for (int si = 0; si < segs.size(); si++) {
                Seg s = segs.get(si);
                b.segLink[si] = s.link;
                b.segKind[si] = s.kind;
                b.segHalfWidth[si] = s.hw;
                b.segJoinA[si] = s.joinA;
                b.segJoinB[si] = s.joinB;
                b.segStart[si] = v;
                int n = s.x.length;
                for (int i = 0; i < n; i++) {
                    b.vx[v + i] = Math.round(s.x[i] * 16) / 16f;
                    b.vz[v + i] = Math.round(s.z[i] * 16) / 16f;
                    b.vh[v + i] = RoadPlan.quant(s.h[i]);
                    b.vg[v + i] = RoadPlan.quant(s.g[i]);
                    b.vw[v + i] = RoadPlan.quant(s.w[i]);
                    b.vspan[v + i] = s.span[i];
                }
                soff.add(st.size());
                int i = 0;
                while (i < n) {
                    int j = i;
                    while (j + 1 < n && s.span[j + 1] == s.span[i]) j++;
                    st.add(s.span[i]);
                    sf.add(v + i);
                    sto.add(v + j);
                    poff.add(piers.size());
                    if (s.span[i] == RoadPlan.BRIDGE) {
                        float top = -Float.MAX_VALUE;
                        for (int k = i; k <= j; k++) top = Math.max(top, s.h[k]);
                        deck.add(RoadPlan.quant(top));
                        double arc = 0;
                        double[] at = new double[j - i + 1];
                        for (int k = i + 1; k <= j; k++) {
                            arc += Math.hypot(s.x[k] - s.x[k - 1], s.z[k] - s.z[k - 1]);
                            at[k - i] = arc;
                        }
                        int count = arc > 16 ? (int) Math.ceil(arc / 14) - 1 : 0;
                        for (int q = 1; q <= count; q++) piers.add(RoadPlan.quant((float) (arc * q / (count + 1))));
                    } else {
                        deck.add(Float.NaN);
                    }
                    i = j + 1;
                }
                b.segEnd[si] = v + n;
                v += n;
            }
            soff.add(st.size());
            poff.add(piers.size());
            b.spanOff = soff.toIntArray();
            b.spanType = st.toByteArray();
            b.spanFrom = sf.toIntArray();
            b.spanTo = sto.toIntArray();
            b.spanDeck = deck.toArray();
            b.pierOff = poff.toIntArray();
            b.pierAt = piers.toArray();
            return new RoadPlan(b);
        }
    }

    // ------------------------------------------------------------------ a piece being shaped

    /** Vertex arrays of a piece: position, arc length, sampled ground and water, profile and span type. */
    static final class Poly {
        int n;
        double[] x, z, s;
        float[] g, w, width, near, h;
        boolean[] lakeSea, sb, skip;
        byte[] span;

        Poly(List<double[]> pts) {
            set(pts, null);
        }

        private void set(List<double[]> pts, boolean[] keepSb) {
            n = pts.size();
            x = new double[n];
            z = new double[n];
            s = new double[n];
            g = new float[n];
            w = new float[n];
            width = new float[n];
            near = new float[n];
            h = new float[n];
            lakeSea = new boolean[n];
            sb = keepSb != null ? keepSb : new boolean[n];
            skip = new boolean[n];
            span = new byte[n];
            for (int i = 0; i < n; i++) {
                x[i] = pts.get(i)[0];
                z[i] = pts.get(i)[1];
                if (i > 0) s[i] = s[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
            }
        }

        void sampleAll(Run r) {
            for (int i = 0; i < n; i++) sample(r, i);
        }

        private void sample(Run r, int i) {
            r.terrain.sample((int) Math.floor(x[i]), (int) Math.floor(z[i]), r.sample, r.col);
            g[i] = r.col.ground;
            w[i] = r.col.water;
            width[i] = r.col.waterWidth;
            near[i] = r.col.nearWater;
            lakeSea[i] = r.col.lake || r.col.sea;
        }

        /** Replaces vertices a..b by {@code mid} (which starts at a and ends at b); the new ones are switchbacks. */
        void replace(int a, int b, List<double[]> mid, Run r) {
            List<double[]> pts = new ArrayList<>();
            IntArrayList from = new IntArrayList();
            for (int i = 0; i < a; i++) {
                pts.add(new double[]{x[i], z[i]});
                from.add(i);
            }
            for (double[] q : mid) {
                pts.add(q);
                from.add(-1);
            }
            for (int i = b + 1; i < n; i++) {
                pts.add(new double[]{x[i], z[i]});
                from.add(i);
            }
            float[] og = g, ow = w, owidth = width, onear = near;
            boolean[] olake = lakeSea, osb = sb;
            set(pts, null);
            for (int i = 0; i < n; i++) {
                int f = from.getInt(i);
                if (f >= 0) {
                    g[i] = og[f];
                    w[i] = ow[f];
                    width[i] = owidth[f];
                    near[i] = onear[f];
                    lakeSea[i] = olake[f];
                    sb[i] = osb[f];
                } else {
                    sample(r, i);
                    sb[i] = true;
                }
            }
        }
    }
}
