package com.skycraft.worldplan.street;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.SettlementPiece;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Cities;
import com.skycraft.tamriel.settlement.plan.Plan;
import com.skycraft.tamriel.settlement.plan.Spec;
import com.skycraft.tamriel.settlement.plan.Terrain;
import com.skycraft.worldplan.PlanGround;
import com.skycraft.worldplan.RoadPlan;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.Direction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Bakes the {@link StreetPlan} (docs/WORLDGEN-PIPELINE.md P5): every named settlement is laid out by the settlement
 * grammar (its authored city plan is the input) on the seed-independent plan terrain, with the planned roads' beds kept
 * free ({@link Plan#keepOut}); then the street pass resolves what the grammar left implicit:
 * <ol>
 *   <li>the street graph from the street and bridge lines (endpoints merged, T-junctions split), plaza nodes, and a GATE
 *   node at the last vertex of every planned road that ends at the settlement (joined to the nearest street node by the
 *   gate lane);</li>
 *   <li>every declared entrance, recorded by replaying the building's builder, gets a doorstep path: the cheapest
 *   4-connected walk on a 1-block grid (buildings, walls and towers blocked, a gate only through its passage, yards and
 *   fields dear, water dearer: a plank walk; no step of more than one block) from the door to a street, plaza, gate or
 *   planned road; a door no path reaches is a lint error;</li>
 *   <li>props without anchors (yard clutter, lamp posts) that stand on a doorstep path, a street or a planned road are
 *   left out ({@link Spec#OMIT});</li>
 *   <li>plots: building and plaza footprints with their pad (floor) heights, walls and works for the masks.</li>
 * </ol>
 * Deterministic and seed-free: settlements are resolved in parallel and assembled in {@link Places#all} order.
 */
public final class StreetPlanBuilder {
    public static final String BAKE_NAME = "streetplan";

    // grid cell costs (0 = blocked)
    private static final byte C_FREE = 1, C_PROP = 3, C_EAVE = 4, C_WORK = 6, C_WATER = 9;
    // grid targets
    private static final byte T_NONE = 0, T_STREET = 1, T_PLAZA = 2, T_GATE = 3, T_ROAD = 4;
    /** Highest path cost explored from a door, and the most cells. */
    private static final int MAX_COST = 420, MAX_VISIT = 90_000;
    /** A rise of two blocks between neighbouring cells is allowed at this extra cost (the painter cuts a step). */
    private static final int STEP2_COST = 12;
    private static final int MARGIN = 24, MAX_SIDE = 720;

    private StreetPlanBuilder() {}

    // ================================================================================================ layout

    /**
     * The seed-independent layout of a named place: the zoning planner on {@link StreetTerrain}, with the planned roads'
     * beds kept free. {@code cultureOverride} is a culture id or "auto". The structure, the dev tools and the population
     * report all use this, so they agree with the bake.
     */
    public static Plan plan(Place p, String cultureOverride) {
        Culture c = Culture.of(cultureOverride == null || cultureOverride.equals("auto") ? p.culture() : cultureOverride);
        int style = Cities.style(p, c);
        int radius = Cities.layoutRadius(p);
        Terrain t = new Terrain(radius > 60 ? 16 : 8, TamrielMap.SEA_LEVEL, StreetTerrain::land);
        Plan plan = new Plan(p.id(), p.x(), p.z(), radius, c, style, Cities.seed(p), t, Cities.back(p));
        plan.keepOut = StreetTerrain::roadBed;
        com.skycraft.tamriel.settlement.grammar.Zoning.place(plan, p);
        plan.resolve();
        return plan;
    }

    // ================================================================================================ bake

    public static byte[] computeLive() throws IOException {
        StringBuilder stats = new StringBuilder();
        StreetPlan p = build(stats);
        Skycraft.LOGGER.info("[bake] streetplan: {}", stats);
        return p.write();
    }

    /** Resolves every named settlement. {@code stats} (optional) receives a one-line summary. */
    static StreetPlan build(StringBuilder stats) {
        long t0 = System.nanoTime();
        RoadPlan rp = RoadPlan.get();
        PlanGround.get();
        Map<String, LongArrayList> ends = roadEnds(rp);
        List<Place> places = new ArrayList<>();
        for (Place p : Places.all()) if (p.type().isSettlement()) places.add(p);
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        String env = System.getenv("SKYCRAFT_BAKE_THREADS");
        if (env != null) {
            try {
                threads = Math.max(1, Integer.parseInt(env.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Skycraft street plan");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        List<Result> results = new ArrayList<>();
        try {
            List<Future<Result>> fs = new ArrayList<>();
            for (Place p : places) {
                LongArrayList e = ends.getOrDefault(p.id(), new LongArrayList());
                fs.add(pool.submit(() -> {
                    try {
                        return new Resolver(p, rp, e).run();
                    } catch (RuntimeException ex) {
                        Skycraft.LOGGER.error("[streetplan] resolving {} failed", p.id(), ex);
                        return null;
                    }
                }));
            }
            for (Future<Result> f : fs) {
                Result r;
                try {
                    r = f.get();
                } catch (Exception ex) {
                    r = null;
                }
                if (r != null) results.add(r);
            }
        } finally {
            pool.shutdownNow();
        }
        StreetPlan plan = assemble(results);
        if (stats != null) {
            int doors = 0, bad = 0, gates = 0, roadJoins = 0, omitted = 0, specs = 0, paths = 0, turned = 0, noBack = 0;
            for (Result r : results) {
                doors += r.doorSpec.size();
                for (int i = 0; i < r.doorReach.size(); i++) if (r.doorReach.getByte(i) == 0) bad++;
                for (int i = 0; i < r.nCls.size(); i++) {
                    if (r.nCls.getByte(i) == StreetPlan.N_GATE) gates++;
                    else if (r.nCls.getByte(i) == StreetPlan.N_ROAD) roadJoins++;
                }
                for (int i = 0; i < r.eCls.size(); i++) if (r.eCls.getByte(i) == StreetPlan.PATH) paths++;
                omitted += r.omitted;
                turned += r.turned;
                specs += r.specs.size();
                for (Spec sp : r.specs) if (sp.has(Spec.NO_BACK_DOOR)) noBack++;
            }
            stats.append(String.format(Locale.ROOT,
                    "%d settlements, %d specs (%d props left out, %d buildings turned to a free side, %d back doors walled up), %d nodes, %d edges (%d doorstep path edges), %d doors (%d unreachable, %.2f%%), %d gate nodes + %d road joins, %d plots; %d ms (%d threads)",
                    results.size(), specs, omitted, turned, noBack, plan.nodes(), plan.edges(), paths, doors, bad, doors == 0 ? 0.0 : 100.0 * bad / doors,
                    gates, roadJoins, plan.plots(), (System.nanoTime() - t0) / 1_000_000, threads));
        }
        return plan;
    }

    /** Road plan segment ends that lie on a settlement's pad, by place id: (segment << 32 | global vertex). */
    static Map<String, LongArrayList> roadEnds(RoadPlan rp) {
        Map<String, LongArrayList> out = new HashMap<>();
        for (int s = 0; s < rp.segments(); s++) {
            for (int end = 0; end < 2; end++) {
                int join = end == 0 ? rp.segJoinA[s] : rp.segJoinB[s];
                if (join != RoadPlan.JOIN_SITE) continue;
                int v = end == 0 ? rp.segStart[s] : rp.segEnd[s] - 1;
                float x = rp.vx[v], z = rp.vz[v];
                int best = -1;
                double bestD = Double.MAX_VALUE;
                for (int i = 0; i < rp.sites(); i++) {
                    double d = Math.hypot(x - rp.siteX[i], z - rp.siteZ[i]) - rp.siteRadius[i];
                    if (d < 40 && d < bestD) {
                        bestD = d;
                        best = i;
                    }
                }
                if (best >= 0) out.computeIfAbsent(rp.siteId[best], k -> new LongArrayList()).add(((long) s << 32) | v);
            }
        }
        return out;
    }

    // ================================================================================================ one settlement

    /** The resolved data of one settlement (local node indices). */
    static final class Result {
        String id;
        int x, z, reach;
        final FloatArrayList nx = new FloatArrayList(), nz = new FloatArrayList(), nh = new FloatArrayList();
        final ByteArrayList nCls = new ByteArrayList();
        final IntArrayList nSeg = new IntArrayList(), nVert = new IntArrayList();
        final IntArrayList ea = new IntArrayList(), eb = new IntArrayList();
        final FloatArrayList ew = new FloatArrayList();
        final ByteArrayList eCls = new ByteArrayList(), eSurf = new ByteArrayList();
        final IntArrayList doorSpec = new IntArrayList(), doorX = new IntArrayList(), doorY = new IntArrayList(),
                doorZ = new IntArrayList(), doorNode = new IntArrayList();
        final ByteArrayList doorFacing = new ByteArrayList(), doorReach = new ByteArrayList();
        final IntArrayList pSpec = new IntArrayList(), pMinX = new IntArrayList(), pMinZ = new IntArrayList(),
                pMaxX = new IntArrayList(), pMaxZ = new IntArrayList(), pPad = new IntArrayList();
        final ByteArrayList pKind = new ByteArrayList();
        List<Spec> specs;
        int omitted, turned;

        int node(float x, float z, float h, byte cls) {
            nx.add(x);
            nz.add(z);
            nh.add(h);
            nCls.add(cls);
            nSeg.add(-1);
            nVert.add(-1);
            return nx.size() - 1;
        }

        void edge(int a, int b, float w, byte cls, byte surf) {
            if (a == b) return;
            ea.add(a);
            eb.add(b);
            ew.add(w);
            eCls.add(cls);
            eSurf.add(surf);
        }

        void plot(int spec, int[] r, int pad, byte kind) {
            pSpec.add(spec);
            pMinX.add(r[0]);
            pMinZ.add(r[1]);
            pMaxX.add(r[2]);
            pMaxZ.add(r[3]);
            pPad.add(pad);
            pKind.add(kind);
        }
    }

    /** A street line of the graph being built: its spec, end nodes and the split points along it. */
    private static final class Line {
        final int spec;
        final double ax, az, bx, bz;
        final float w;
        final byte cls, surf;
        int a, b;
        final List<double[]> splits = new ArrayList<>(); // {t, node}

        Line(int spec, double ax, double az, double bx, double bz, float w, byte cls, byte surf) {
            this.spec = spec;
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
            this.w = w;
            this.cls = cls;
            this.surf = surf;
        }

        double len() {
            return Math.hypot(bx - ax, bz - az);
        }

        /** {t, distance} of the projection of (px, pz). */
        double[] project(double px, double pz) {
            double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
            double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / l2));
            return new double[]{t, Math.hypot(ax + dx * t - px, az + dz * t - pz)};
        }
    }

    /** Resolves one settlement. Not thread-safe; one per task. */
    static final class Resolver {
        final Place place;
        final RoadPlan rp;
        final LongArrayList roadEnds;
        final Result r = new Result();
        Plan plan;
        List<Spec> specs;
        // grid
        int gx0, gz0, W, H;
        byte[] cost, target;
        int[] owner, prop, targetLine, surf, land;
        boolean[] wet, ownPass;
        final List<Line> lines = new ArrayList<>();
        final IntArrayList plazaNode = new IntArrayList(), plazaSpec = new IntArrayList();
        final IntArrayList gateNodes = new IntArrayList();
        boolean[] anchored;
        boolean[] omit;
        /** The settlement's kit builds on stilts (its own buildings get no pad). */
        boolean stiltKit;
        /** Doorstep runs of all doors: no walk enters one (but its own start). */
        boolean[] reserved;
        // dijkstra scratch
        int[] dist, from, stamp;
        int stampNow;

        Resolver(Place place, RoadPlan rp, LongArrayList roadEnds) {
            this.place = place;
            this.rp = rp;
            this.roadEnds = roadEnds;
        }

        Result run() {
            plan = plan(place, "auto");
            specs = plan.specs;
            int n = specs.size();
            r.id = place.id();
            r.x = place.x();
            r.z = place.z();
            r.reach = Math.max(Cities.reach(place), plan.radius);
            r.specs = specs;
            anchored = new boolean[n];
            omit = new boolean[n];
            // 1. replay every builder once: anchors (props with none may be left out) and declared entrances
            long seed = Cities.seed(place);
            List<int[]> doors = new ArrayList<>(); // {spec, x, y, z, outward}
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                List<Anchor> an = new ArrayList<>();
                List<int[]> dl = new ArrayList<>();
                new SettlementPiece(s, seed ^ (i * 0x9E3779B97F4A7C15L), place.id(), i).recordDoors(an, dl);
                anchored[i] = !an.isEmpty();
                for (int[] d : dl) doors.add(new int[]{i, d[0], d[1], d[2], d[3]});
                if (s.has(Spec.RESERVED)) {
                    // a player-home plot: the economy module builds the house; its front door is recorded as an anchor
                    for (Anchor a : an) {
                        if (a.type() == AnchorType.HOUSE_DOOR) {
                            doors.add(new int[]{i, a.pos().getX(), a.pos().getY(), a.pos().getZ(), s.facing.get2DDataValue()});
                        }
                    }
                }
            }
            grid();
            graph();
            // doors building by building (the list is in plan order): turned where the front has no walk, then every
            // doorstep run (landing, porch, the builder's steps) is reserved, so no other walk crosses it or comes back
            // over it at another height, then the walks
            List<int[]> all = new ArrayList<>();
            for (int i = 0, k = 0; i < n; i++) {
                List<int[]> dl = new ArrayList<>();
                while (k < doors.size() && doors.get(k)[0] == i) dl.add(doors.get(k++));
                if (dl.isEmpty()) continue;
                all.addAll(turnIfBlocked(i, dl, seed));
            }
            reserved = new boolean[W * H];
            for (int[] d : all) {
                IntArrayList pre = route(d, false).prefix();
                for (int k = 0; k < pre.size(); k++) if (target[pre.getInt(k)] == T_NONE) reserved[pre.getInt(k)] = true;
            }
            for (int[] d : all) door(d);
            omitProps();
            plots();
            r.specs = finalSpecs();
            return r;
        }

        // ------------------------------------------------------------------ geometry helpers

        /** Inclusive world rectangle {minX, minZ, maxX, maxZ} of a building-like footprint (no margin) grown by {@code g}. */
        static int[] rect(Spec s, int g) {
            int[] o = SettlementPiece.origin(s);
            int ax = o[0] + mapX(s.facing, 0, 0), az = o[1] + mapZ(s.facing, 0, 0);
            int bx = o[0] + mapX(s.facing, s.w - 1, s.d - 1), bz = o[1] + mapZ(s.facing, s.w - 1, s.d - 1);
            return new int[]{Math.min(ax, bx) - g, Math.min(az, bz) - g, Math.max(ax, bx) + g, Math.max(az, bz) + g};
        }

        static int mapX(Direction f, int lx, int lz) {
            return switch (f) {
                case EAST -> -lz;
                case SOUTH -> -lx;
                case WEST -> lz;
                default -> lx;
            };
        }

        static int mapZ(Direction f, int lx, int lz) {
            return switch (f) {
                case EAST -> lx;
                case SOUTH -> -lz;
                case WEST -> -lx;
                default -> lz;
            };
        }

        static boolean propLike(Spec s) {
            return switch (s.kind) {
                case YARD, LAMP, WELL, STALL -> true;
                default -> false;
            };
        }

        static boolean softWork(Archetype k) {
            return switch (k) {
                case FIELD, GARDEN, PEN, GRAVEYARD, LUMBER, CAMP, HOMESTEAD_PLOT, TERRACE -> true;
                default -> false;
            };
        }

        boolean blockingWork(Spec s) {
            return switch (s.kind) {
                case MINE, QUARRY, CANAL -> true;
                case LANDMARK -> !s.has(Spec.GROUND);
                default -> false;
            };
        }

        int idx(int x, int z) {
            int i = x - gx0, k = z - gz0;
            return i < 0 || k < 0 || i >= W || k >= H ? -1 : k * W + i;
        }

        /** Visits the grid cells of a line spec's band (Chebyshev offset, as the street painter draws it). */
        void band(Spec s, double half, java.util.function.IntConsumer v) {
            double ax = s.x + 0.5, az = s.z + 0.5, bx = s.x2 + 0.5, bz = s.z2 + 0.5;
            double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
            int x0 = Math.min(s.x, s.x2) - (int) Math.ceil(half) - 1, x1 = Math.max(s.x, s.x2) + (int) Math.ceil(half) + 1;
            int z0 = Math.min(s.z, s.z2) - (int) Math.ceil(half) - 1, z1 = Math.max(s.z, s.z2) + (int) Math.ceil(half) + 1;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    int i = idx(x, z);
                    if (i < 0) continue;
                    double px = x + 0.5 - ax, pz = z + 0.5 - az;
                    double t = l2 < 1e-6 ? 0 : Math.max(0, Math.min(1, (px * dx + pz * dz) / l2));
                    double qx = px - t * dx, qz = pz - t * dz;
                    if (Math.max(Math.abs(qx), Math.abs(qz)) > half + 0.01) continue;
                    v.accept(i);
                }
            }
        }

        void fillRect(int[] rc, java.util.function.IntConsumer v) {
            for (int x = rc[0]; x <= rc[2]; x++) {
                for (int z = rc[1]; z <= rc[3]; z++) {
                    int i = idx(x, z);
                    if (i >= 0) v.accept(i);
                }
            }
        }

        // ------------------------------------------------------------------ 2. the walk grid

        void grid() {
            stiltKit = Kits.palette(plan.culture, plan.style).stilts;
            int minX = place.x() - r.reach, maxX = place.x() + r.reach, minZ = place.z() - r.reach, maxZ = place.z() + r.reach;
            for (Spec s : specs) {
                minX = Math.min(minX, s.centerX() - s.halfX());
                maxX = Math.max(maxX, s.centerX() + s.halfX());
                minZ = Math.min(minZ, s.centerZ() - s.halfZ());
                maxZ = Math.max(maxZ, s.centerZ() + s.halfZ());
            }
            for (int k = 0; k < roadEnds.size(); k++) {
                int v = (int) roadEnds.getLong(k);
                minX = Math.min(minX, (int) Math.floor(rp.vx[v]));
                maxX = Math.max(maxX, (int) Math.floor(rp.vx[v]));
                minZ = Math.min(minZ, (int) Math.floor(rp.vz[v]));
                maxZ = Math.max(maxZ, (int) Math.floor(rp.vz[v]));
            }
            minX -= MARGIN;
            minZ -= MARGIN;
            maxX += MARGIN;
            maxZ += MARGIN;
            // never larger than MAX_SIDE (a city's outworks): clip round the centre
            minX = Math.max(minX, place.x() - MAX_SIDE / 2);
            maxX = Math.min(maxX, place.x() + MAX_SIDE / 2);
            minZ = Math.max(minZ, place.z() - MAX_SIDE / 2);
            maxZ = Math.min(maxZ, place.z() + MAX_SIDE / 2);
            gx0 = minX;
            gz0 = minZ;
            W = maxX - minX + 1;
            H = maxZ - minZ + 1;
            int N = W * H;
            cost = new byte[N];
            target = new byte[N];
            owner = new int[N];
            prop = new int[N];
            targetLine = new int[N];
            surf = new int[N];
            wet = new boolean[N];
            java.util.Arrays.fill(cost, C_FREE);
            java.util.Arrays.fill(owner, -1);
            java.util.Arrays.fill(prop, -1);
            java.util.Arrays.fill(targetLine, -1);
            // every column of the plan terrain exactly (the layout itself was made on the planner's 8/16-block lattice):
            // inside the site pad this is the generated ground to the block
            land = new int[N];
            int[] deck = new int[1];
            for (int k = 0; k < H; k++) {
                for (int i = 0; i < W; i++) {
                    int x = gx0 + i, z = gz0 + k, c = k * W + i;
                    land[c] = StreetTerrain.column(x, z, deck);
                    surf[c] = land[c];
                    if (deck[0] != Integer.MIN_VALUE) {
                        wet[c] = true;
                        cost[c] = C_WATER;
                        surf[c] = deck[0]; // a plank walk on the water
                    }
                }
            }
            int n = specs.size();
            // ground works raise the walk surface (terraces, rock plateaus)
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                if (!s.has(Spec.GROUND) || s.isLine() || s.floor == Spec.UNSET || s.kind == Archetype.CAMP) continue;
                int f = s.floor;
                fillRect(rect(s, 0), c -> {
                    if (!wet[c]) surf[c] = Math.max(surf[c], f);
                });
            }
            // the plot pads pull the ground under and round each plot to its floor (StreetPlan.pad): the walk follows that
            float[] padW = new float[N];
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                int pad = padOf(s);
                if (pad == StreetPlan.NO_PAD) continue;
                int[] rc = rect(s, 0);
                int reach = 1 + (int) StreetPlan.PAD_BLEND;
                for (int x = rc[0] - reach; x <= rc[2] + reach; x++) {
                    for (int z = rc[1] - reach; z <= rc[3] + reach; z++) {
                        int c = idx(x, z);
                        if (c < 0 || wet[c]) continue;
                        int ox = x < rc[0] ? rc[0] - x : x > rc[2] ? x - rc[2] : 0, oz = z < rc[1] ? rc[1] - z : z > rc[3] ? z - rc[3] : 0;
                        float dd = Math.max(ox, oz) - 1f;
                        float w = dd <= 0f ? 1f : dd >= StreetPlan.PAD_BLEND ? 0f : 1f - smoothstep(dd / StreetPlan.PAD_BLEND);
                        if (w <= padW[c]) continue;
                        padW[c] = w;
                        double h = land[c] + 0.5;
                        surf[c] = Math.max(surf[c] == land[c] ? Integer.MIN_VALUE : surf[c], (int) Math.floor(h + (pad + 0.5 - h) * w));
                    }
                }
            }
            // soft works and props
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                if (s.isLine() || s.kind.building || s.has(Spec.RESERVED)) continue;
                int si = i;
                if (s.kind == Archetype.DOCK) {
                    fillRect(rect(s, 0), c -> {
                        cost[c] = C_FREE;
                        if (s.floor != Spec.UNSET) surf[c] = s.floor;
                    });
                } else if (softWork(s.kind)) {
                    fillRect(rect(s, 0), c -> cost[c] = (byte) Math.max(cost[c], C_WORK));
                } else if (propLike(s)) {
                    int[] rc = rect(s, 0);
                    int area = (rc[2] - rc[0] + 1) * (rc[3] - rc[1] + 1);
                    if (!anchored[i] && area <= 30) {
                        fillRect(rc, c -> {
                            cost[c] = (byte) Math.max(cost[c], C_PROP);
                            prop[c] = si;
                        });
                    } else if (!anchored[i]) {
                        fillRect(rc, c -> cost[c] = (byte) Math.max(cost[c], C_WORK));
                    } else {
                        fillRect(rc, c -> {
                            cost[c] = 0;
                            owner[c] = si;
                        });
                    }
                }
            }
            // targets: streets, bridges, plazas, planned road beds
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                int si = i;
                if (s.kind == Archetype.STREET || s.kind == Archetype.BRIDGE) {
                    boolean ramped = s.floor != Spec.UNSET && s.floor2 != Spec.UNSET && (s.kind == Archetype.BRIDGE || s.floor != s.floor2);
                    double ax = s.x + 0.5, az = s.z + 0.5, dx = s.x2 - s.x, dz = s.z2 - s.z, l2 = dx * dx + dz * dz;
                    band(s, s.w / 2.0, c -> {
                        target[c] = T_STREET;
                        targetLine[c] = si;
                        if (cost[c] == C_WATER) cost[c] = C_FREE;
                        if (ramped) {
                            double px = gx0 + c % W + 0.5 - ax, pz = gz0 + c / W + 0.5 - az;
                            double t = l2 < 1e-6 ? 0 : Math.max(0, Math.min(1, (px * dx + pz * dz) / l2));
                            surf[c] = (int) Math.round(s.floor + (s.floor2 - s.floor) * t);
                            wet[c] = false;
                        } else if (s.kind == Archetype.BRIDGE && s.floor != Spec.UNSET) {
                            wet[c] = false;
                        } else if (wet[c]) {
                            wet[c] = false; // the street's own plank walk
                        }
                    });
                } else if (s.kind == Archetype.PLAZA) {
                    fillRect(rect(s, 0), c -> {
                        target[c] = T_PLAZA;
                        targetLine[c] = si;
                    });
                }
            }
            RoadPlan.Hit hit = new RoadPlan.Hit();
            for (int k = 0; k < H; k++) {
                for (int i = 0; i < W; i++) {
                    int c = k * W + i;
                    if (target[c] != T_NONE) continue;
                    if (rp.at(gx0 + i + 0.5, gz0 + k + 0.5, hit) && hit.distance <= hit.halfWidth && hit.span != RoadPlan.TUNNEL) {
                        target[c] = T_ROAD;
                        if (cost[c] == C_WATER) cost[c] = C_FREE;
                        wet[c] = false;
                        surf[c] = (int) Math.floor(hit.height + 0.002f);
                    }
                }
            }
            // blocked: buildings, walls, towers, gates (but their passage), blocking works
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                int si = i;
                if (s.kind == Archetype.WALL || s.kind == Archetype.CANAL) {
                    band(s, s.w / 2.0 + 0.5, c -> block(c, si));
                } else if (s.kind == Archetype.GATE) {
                    fillRect(rect(s, 0), c -> block(c, si));
                    // the passage along the gate's facing axis, three wide, stays open
                    boolean alongZ = s.facing.getAxis() == Direction.Axis.Z;
                    int[] rc = rect(s, 0);
                    for (int x = rc[0]; x <= rc[2]; x++) {
                        for (int z = rc[1]; z <= rc[3]; z++) {
                            int off = alongZ ? Math.abs(x - s.x) : Math.abs(z - s.z);
                            int c = idx(x, z);
                            if (c >= 0 && off <= 1) {
                                cost[c] = C_FREE;
                                owner[c] = -1;
                            }
                        }
                    }
                } else if (!s.isLine() && (s.kind.building || s.has(Spec.RESERVED) || blockingWork(s))) {
                    // the margin its builder may paint (eaves, porch steps, outside stairs) is dear but open: back doors
                    // and alleys between close buildings run through it
                    if (s.margin > 0) fillRect(rect(s, Math.min(2, s.margin)), c -> {
                        if (cost[c] != 0) cost[c] = (byte) Math.max(cost[c], C_EAVE);
                    });
                    fillRect(rect(s, 0), c -> block(c, si));
                }
            }
        }

        /** The floor a building's plot pads the terrain to, or {@link StreetPlan#NO_PAD} (water, stilts, tents, sunk works). */
        int padOf(Spec s) {
            if (s.isLine() || s.has(Spec.OMIT) || s.floor == Spec.UNSET) return StreetPlan.NO_PAD;
            if (!(s.kind.building || s.has(Spec.RESERVED)) || blockingWork(s)) return s.kind == Archetype.PLAZA ? s.floor : StreetPlan.NO_PAD;
            boolean stilted = stiltKit && s.kind.building && s.culture == plan.culture.ordinal();
            boolean ok = !s.has(Spec.WATER) && !stilted && s.kind != Archetype.TENT && Math.abs(s.floor - plan.terrain.land(s.x, s.z)) <= 12;
            return ok ? s.floor : StreetPlan.NO_PAD;
        }

        static float smoothstep(float t) {
            return t * t * (3f - 2f * t);
        }

        void block(int c, int spec) {
            cost[c] = 0;
            owner[c] = spec;
            if (target[c] != T_ROAD) target[c] = T_NONE;
        }

        // ------------------------------------------------------------------ 3. the street graph

        void graph() {
            int n = specs.size();
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                if (s.kind != Archetype.STREET && s.kind != Archetype.BRIDGE) continue;
                if (s.x == s.x2 && s.z == s.z2) continue;
                byte cls, surface;
                if (s.kind == Archetype.BRIDGE) {
                    cls = StreetPlan.BRIDGE;
                    surface = StreetPlan.PLANKS;
                } else {
                    cls = s.has(Spec.MAJOR) ? StreetPlan.MAIN : s.w >= 3 ? StreetPlan.LANE : StreetPlan.ALLEY;
                    surface = s.has(Spec.MAJOR) ? StreetPlan.PAVED : StreetPlan.BEATEN;
                }
                Line l = new Line(i, s.x + 0.5, s.z + 0.5, s.x2 + 0.5, s.z2 + 0.5, Math.max(1, s.w), cls, surface);
                boolean fixed = (s.kind == Archetype.BRIDGE || s.floor2 != Spec.UNSET) && s.floor != Spec.UNSET;
                l.a = streetNode(l.ax, l.az, fixed ? s.floor : Integer.MIN_VALUE);
                l.b = streetNode(l.bx, l.bz, fixed ? (s.floor2 != Spec.UNSET ? s.floor2 : s.floor) : Integer.MIN_VALUE);
                lines.add(l);
            }
            // T-junctions: a street end lying on another street's side splits it there
            int streetNodes = r.nx.size();
            for (int nd = 0; nd < streetNodes; nd++) {
                double px = r.nx.getFloat(nd), pz = r.nz.getFloat(nd);
                for (Line l : lines) {
                    if (l.a == nd || l.b == nd) continue;
                    double[] pr = l.project(px, pz);
                    if (pr[0] <= 0.02 || pr[0] >= 0.98) continue;
                    if (pr[1] <= l.w / 2.0 + 1.5) l.splits.add(new double[]{pr[0], nd});
                }
            }
            // plazas: a node at the centre, joined to the nearest street node
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                if (s.kind != Archetype.PLAZA) continue;
                int nd = r.node(s.x + 0.5f, s.z + 0.5f, surfAt(s.x, s.z), StreetPlan.N_PLAZA);
                plazaNode.add(nd);
                plazaSpec.add(i);
                int near = nearestNode(s.x + 0.5, s.z + 0.5, streetNodes, Math.max(s.w, s.d) + 12);
                if (near >= 0) r.edge(nd, near, 3, StreetPlan.LANE, StreetPlan.PAVED);
            }
            // gates: the last vertex of every planned road that ends here is a node of the graph
            List<int[]> predicted = new ArrayList<>(SettlementAnchors.gates(place));
            for (Spec s : specs) {
                if (s.kind == Archetype.GATE) predicted.add(new int[]{s.x, s.z});
            }
            int maxSearch = Math.max(128, r.reach * 2);
            boolean hasGateNode = false;
            for (int k = 0; k < roadEnds.size(); k++) {
                long e = roadEnds.getLong(k);
                int seg = (int) (e >>> 32), v = (int) e;
                float x = rp.vx[v], z = rp.vz[v];
                boolean atGate = false;
                for (int[] g : predicted) if (Math.hypot(g[0] + 0.5 - x, g[1] + 0.5 - z) <= 24) atGate = true;
                if (atGate) hasGateNode = true;
                int nd = r.node(x, z, (float) Math.floor(rp.vh[v] + 0.002f), atGate ? StreetPlan.N_GATE : StreetPlan.N_ROAD);
                r.nSeg.set(nd, seg);
                r.nVert.set(nd, v);
                gateNodes.add(nd);
                int c = idx((int) Math.floor(x), (int) Math.floor(z));
                if (c >= 0) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int cc = idx((int) Math.floor(x) + dx, (int) Math.floor(z) + dz);
                            if (cc >= 0 && cost[cc] != 0) {
                                target[cc] = T_GATE;
                                targetLine[cc] = nd;
                            }
                        }
                    }
                }
                // the gate lane: the road's end to the nearest street node (the main street starts at the gate)
                int near = nearestNode(x, z, streetNodes, maxSearch);
                if (near >= 0) {
                    float w = 3f;
                    for (Line l : lines) if (l.a == near || l.b == near) w = Math.max(w, l.w);
                    r.edge(nd, near, w, StreetPlan.MAIN, StreetPlan.PAVED);
                }
            }
            // ensure every planned gate connects to the street graph and promotes its nearest road end to N_GATE
            for (int[] g : predicted) {
                float gx = g[0] + 0.5f, gz = g[1] + 0.5f;
                int bestGate = -1;
                double bestGateDist = Double.MAX_VALUE;
                for (int i = 0; i < gateNodes.size(); i++) {
                    int gn = gateNodes.getInt(i);
                    double d = Math.hypot(r.nx.getFloat(gn) - gx, r.nz.getFloat(gn) - gz);
                    if (d < bestGateDist) {
                        bestGateDist = d;
                        bestGate = gn;
                    }
                }
                if (bestGate >= 0 && bestGateDist <= maxSearch) {
                    r.nCls.set(bestGate, StreetPlan.N_GATE);
                    hasGateNode = true;
                }
                if (bestGateDist <= 24) continue;
                int nd = r.node(gx, gz, surfAt(g[0], g[1]), StreetPlan.N_STREET);
                int near = nearestNode(gx, gz, streetNodes, maxSearch);
                if (near >= 0) {
                    float w = 3f;
                    for (Line l : lines) if (l.a == near || l.b == near) w = Math.max(w, l.w);
                    r.edge(nd, near, w, StreetPlan.MAIN, StreetPlan.PAVED);
                }
                if (bestGate >= 0 && bestGateDist <= maxSearch) {
                    r.edge(bestGate, nd, 3f, StreetPlan.MAIN, StreetPlan.PAVED);
                }
            }
            if (!hasGateNode && !gateNodes.isEmpty()) {
                r.nCls.set(gateNodes.getInt(0), StreetPlan.N_GATE);
            }
        }

        int streetNode(double x, double z, int fixedH) {
            int h = fixedH != Integer.MIN_VALUE ? fixedH : surfAt((int) Math.floor(x), (int) Math.floor(z));
            for (int nd = 0; nd < r.nx.size(); nd++) {
                if (r.nCls.getByte(nd) != StreetPlan.N_STREET) continue;
                if (Math.abs(r.nx.getFloat(nd) - x) <= 2 && Math.abs(r.nz.getFloat(nd) - z) <= 2
                        && Math.abs(r.nh.getFloat(nd) - h) <= 3) return nd;
            }
            return r.node((float) x, (float) z, h, StreetPlan.N_STREET);
        }

        int nearestNode(double x, double z, int limit, double maxD) {
            int best = -1;
            double bd = maxD;
            for (int nd = 0; nd < limit; nd++) {
                if (r.nCls.getByte(nd) != StreetPlan.N_STREET) continue;
                double d = Math.hypot(r.nx.getFloat(nd) - x, r.nz.getFloat(nd) - z);
                if (d < bd) {
                    bd = d;
                    best = nd;
                }
            }
            return best;
        }

        int surfAt(int x, int z) {
            int c = idx(x, z);
            return c >= 0 ? surf[c] : plan.terrain.land(x, z);
        }

        // ------------------------------------------------------------------ 4. doors

        /** A door's walk: the doorstep run (porch and builder's steps), its start height, and the searched cells (or null). */
        record Route(IntArrayList prefix, int steps, int startH, IntArrayList path) {
        }

        Route route(int[] d) {
            return route(d, true);
        }

        /** The doorstep run of a door, and (when {@code walk}) the searched walk from its end. */
        Route route(int[] d, boolean walk) {
            int spec = d[0], x = d[1], y = d[2], z = d[3];
            Direction out = Direction.from2DDataValue(d[4]);
            int ox = out.getStepX(), oz = out.getStepZ();
            int floor = y - 1;
            Spec bs = specs.get(spec);
            // the doorstep, then out through the building's own porch and steps (its builder lays those)
            IntArrayList prefix = new IntArrayList();
            int cx = x + ox, cz = z + oz;
            int c = idx(cx, cz);
            IntArrayList path = null;
            int steps = 0, startH = floor;
            if (c >= 0) {
                prefix.add(c);
                int maxOut = Math.max(4, Math.max(bs.w, bs.d));
                for (int k = 0; k < maxOut && owner[c] == spec; k++) {
                    cx += ox;
                    cz += oz;
                    c = idx(cx, cz);
                    if (c < 0) break;
                    prefix.add(c);
                }
                if (c >= 0 && (owner[c] < 0 || owner[c] == spec)) {
                    // the builder's steps (Grammar.steps) run straight out from the landing at the doorstep while the ground
                    // is below the floor, one stair per block of drop; the walk starts on the ground after the last stair
                    int below = Math.min(8, floor - surf[c]);
                    boolean front = out == bs.facing || !bs.grammar();
                    if (!front && below >= 2) return new Route(prefix, 0, floor, null); // a back door gets no steps
                    for (int k = 0; k < below + 1 && below > 0 && front; k++) {
                        int nc = idx(cx + ox, cz + oz);
                        if (nc < 0 || cost[nc] == 0 || target[nc] != T_NONE && k < below) break;
                        cx += ox;
                        cz += oz;
                        c = nc;
                        prefix.add(c);
                        steps++;
                    }
                    startH = (steps > 0 || Math.abs(floor - surf[c]) > 2) ? surf[c] : floor;
                    if (walk) path = search(c, spec, startH);
                }
            }
            return new Route(prefix, steps, startH, path);
        }

        /**
         * The doors of building {@code i}; when its front door has no walk and the building is a square (odd) one that
         * turning leaves on the same footprint (a wall tower, a cottage), it is turned to the first side whose door has
         * one (the opposite side first), and its doors are recorded again.
         */
        List<int[]> turnIfBlocked(int i, List<int[]> dl, long seed) {
            Spec s = specs.get(i);
            if (!s.kind.building || s.isLine() || s.w != s.d || (s.w & 1) == 0 || s.has(Spec.RESERVED) || s.has(Spec.SEAT)
                    || s.has(Spec.FOR_SALE) || s.has(Spec.GROUND) || s.has(Spec.FIXED)) return dl;
            int[] front = frontOf(s, dl);
            if (front == null || route(front).path() != null) return dl;
            Direction orig = s.facing;
            for (Direction f : new Direction[]{orig.getOpposite(), orig.getClockWise(), orig.getCounterClockWise()}) {
                s.facing = f;
                List<int[]> d2 = new ArrayList<>();
                List<int[]> raw = new ArrayList<>();
                new SettlementPiece(s, seed ^ (i * 0x9E3779B97F4A7C15L), place.id(), i).recordDoors(new ArrayList<>(), raw);
                for (int[] d : raw) d2.add(new int[]{i, d[0], d[1], d[2], d[3]});
                int[] fr = frontOf(s, d2);
                if (fr != null && route(fr).path() != null) {
                    r.turned++;
                    return d2;
                }
            }
            s.facing = orig;
            return dl;
        }

        static int[] frontOf(Spec s, List<int[]> dl) {
            for (int[] d : dl) if (d[4] == s.facing.get2DDataValue()) return d;
            return null;
        }

        void door(int[] d) {
            int spec = d[0], x = d[1], y = d[2], z = d[3];
            Direction out = Direction.from2DDataValue(d[4]);
            int ox = out.getStepX(), oz = out.getStepZ();
            int floor = y - 1;
            Route rt = route(d);
            IntArrayList prefix = rt.prefix(), path = rt.path();
            int steps = rt.steps(), startH = rt.startH();
            Spec bld = specs.get(spec);
            if (path == null && bld.grammar() && !bld.has(Spec.RUINED) && out == bld.facing.getOpposite()) {
                // a back door that opens onto a wall, a terrace face or a neighbour's back: the house is built without it
                bld.flags |= Spec.NO_BACK_DOOR;
                return;
            }
            r.doorSpec.add(spec);
            r.doorX.add(x);
            r.doorY.add(y);
            r.doorZ.add(z);
            r.doorFacing.add((byte) d[4]);
            int doorNode = r.node(x + ox + 0.5f, z + oz + 0.5f, floor, StreetPlan.N_DOOR);
            r.doorNode.add(doorNode);
            if (path == null) {
                r.doorReach.add((byte) 0);
                return;
            }
            r.doorReach.add((byte) 1);
            // heights: from the start (the door's floor, or the foot of its steps) along the ground, at most one block per
            // cell, then back from the end so the path meets the street, plaza, gate or road it reached
            int np = path.size();
            int[] hy = new int[np];
            int last = path.getInt(np - 1);
            hy[0] = startH;
            for (int k = 1; k < np; k++) hy[k] = Math.max(hy[k - 1] - 1, Math.min(hy[k - 1] + 1, surf[path.getInt(k)]));
            if (np > 1) {
                hy[np - 1] = surf[last];
                for (int k = np - 2; k >= 1; k--) hy[k] = Math.max(hy[k + 1] - 1, Math.min(hy[k + 1] + 1, hy[k]));
            }
            // the props the walk crosses (the doorstep and the building's own approach included) are left out
            for (int k = 0; k < np; k++) {
                int pc = prop[path.getInt(k)];
                if (pc >= 0) omit[pc] = true;
            }
            for (int k = 0; k < prefix.size(); k++) {
                int pc = prop[prefix.getInt(k)];
                if (pc >= 0) omit[pc] = true;
            }
            // nodes: the door node (the doorstep), the end of the building's own porch and steps (built: the painter
            // leaves that stretch alone, surface STONE), then every corner (direction or grade change), then the join
            int prev = doorNode;
            if (prefix.size() > 1) {
                int pc = path.getInt(0);
                int nd = r.node(gx0 + pc % W + 0.5f, gz0 + pc / W + 0.5f, hy[0], StreetPlan.N_PATH);
                r.edge(prev, nd, 1, steps > 0 ? StreetPlan.STAIRS : StreetPlan.PATH, StreetPlan.STONE);
                prev = nd;
            }
            for (int k = 1; k < np; k++) {
                boolean corner = k == np - 1;
                if (!corner) {
                    int a = path.getInt(k - 1), b = path.getInt(k), cc = path.getInt(k + 1);
                    int d1 = b - a, d2 = cc - b;
                    int g1 = hy[k] - hy[k - 1], g2 = hy[k + 1] - hy[k];
                    corner = d1 != d2 || g1 != g2;
                }
                if (!corner) continue;
                int pc = path.getInt(k);
                int nd = r.node(gx0 + pc % W + 0.5f, gz0 + pc / W + 0.5f, hy[k], StreetPlan.N_PATH);
                r.edge(prev, nd, 1, StreetPlan.PATH, wet[pc] ? StreetPlan.PLANKS : StreetPlan.BEATEN);
                prev = nd;
            }
            join(prev, last);
        }

        /** Links a path's last node to what its last cell reached: a street line (split at the projection), a plaza, a gate, a road. */
        void join(int nd, int cell) {
            byte tg = target[cell];
            float px = gx0 + cell % W + 0.5f, pz = gz0 + cell / W + 0.5f;
            if (tg == T_STREET) {
                int spec = targetLine[cell];
                for (Line l : lines) {
                    if (l.spec != spec) continue;
                    double[] pr = l.project(px, pz);
                    int at;
                    if (pr[0] <= 0.02) at = l.a;
                    else if (pr[0] >= 0.98) at = l.b;
                    else {
                        float hx = (float) (l.ax + (l.bx - l.ax) * pr[0]), hz = (float) (l.az + (l.bz - l.az) * pr[0]);
                        float ha = r.nh.getFloat(l.a), hb = r.nh.getFloat(l.b);
                        at = r.node(hx, hz, (float) Math.floor(ha + (hb - ha) * pr[0] + 0.5f), StreetPlan.N_STREET);
                        l.splits.add(new double[]{pr[0], at});
                    }
                    r.edge(nd, at, 1, StreetPlan.PATH, StreetPlan.BEATEN);
                    return;
                }
            } else if (tg == T_PLAZA) {
                int spec = targetLine[cell];
                for (int k = 0; k < plazaSpec.size(); k++) {
                    if (plazaSpec.getInt(k) == spec) {
                        r.edge(nd, plazaNode.getInt(k), 1, StreetPlan.PATH, StreetPlan.BEATEN);
                        return;
                    }
                }
            } else if (tg == T_GATE) {
                r.edge(nd, targetLine[cell], 1, StreetPlan.PATH, StreetPlan.BEATEN);
            } else if (tg == T_ROAD) {
                RoadPlan.Hit hit = new RoadPlan.Hit();
                if (rp.at(px, pz, hit)) {
                    // the node is the road plan vertex itself, so the walk and the road meet in one cell
                    int v = hit.t < 0.5f ? hit.vertex : hit.vertex + 1;
                    int rn = r.node(rp.vx[v], rp.vz[v], (float) Math.floor(rp.vh[v] + 0.002f), StreetPlan.N_ROAD);
                    r.nSeg.set(rn, hit.segment);
                    r.nVert.set(rn, v);
                    r.edge(nd, rn, 1, StreetPlan.PATH, StreetPlan.BEATEN);
                }
            }
        }

        /**
         * Cheapest 4-connected walk from {@code startCell} to any target cell; cells of the door's own building are
         * blocked; steps between neighbours of more than two blocks are impossible, two cost extra. Returns the cells
         * from the start to the target, or null.
         */
        IntArrayList search(int startCell, int ownSpec, int startH) {
            int N = W * H;
            if (dist == null) {
                dist = new int[N];
                from = new int[N];
                stamp = new int[N];
            }
            int st = ++stampNow;
            it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue q = new it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue();
            dist[startCell] = 0;
            from[startCell] = -1;
            stamp[startCell] = st;
            q.enqueue(startCell);
            int visits = 0;
            while (!q.isEmpty()) {
                long e = q.dequeueLong();
                int c = (int) e, dc = (int) (e >>> 32);
                if (dc > dist[c]) continue;
                if (target[c] != T_NONE) return trace(c);
                if (++visits > MAX_VISIT) break;
                int cxi = c % W, czi = c / W;
                int hc = c == startCell ? startH : surf[c];
                for (int dir = 0; dir < 4; dir++) {
                    int nxi = cxi + (dir == 0 ? 1 : dir == 1 ? -1 : 0), nzi = czi + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                    if (nxi < 0 || nzi < 0 || nxi >= W || nzi >= H) continue;
                    int nc = nzi * W + nxi;
                    if (cost[nc] == 0 || owner[nc] == ownSpec || reserved != null && reserved[nc] && nc != startCell) continue;
                    int step = Math.abs(surf[nc] - hc);
                    if (step > 2) continue;
                    int nd = dc + cost[nc] + (step == 2 ? STEP2_COST : 0);
                    if (nd > MAX_COST) continue;
                    if (stamp[nc] == st && dist[nc] <= nd) continue;
                    stamp[nc] = st;
                    dist[nc] = nd;
                    from[nc] = c;
                    q.enqueue(((long) nd << 32) | nc);
                }
            }
            return null;
        }

        IntArrayList trace(int c) {
            IntArrayList out = new IntArrayList();
            for (int k = c; k != -1; k = from[k]) out.add(k);
            java.util.Collections.reverse(out);
            return out;
        }

        // ------------------------------------------------------------------ 5. props on paths, streets and roads

        void omitProps() {
            int n = specs.size();
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                if (omit[i] || anchored[i] || !propLike(s) || s.isLine()) continue;
                int[] rc = rect(s, 0);
                int area = (rc[2] - rc[0] + 1) * (rc[3] - rc[1] + 1);
                if (area > 30) continue;
                boolean[] hit = {false};
                fillRect(rc, c -> {
                    if (target[c] == T_STREET && specs.get(targetLine[c]).kind == Archetype.STREET || target[c] == T_ROAD) hit[0] = true;
                });
                if (hit[0]) omit[i] = true;
            }
            for (int i = 0; i < n; i++) if (omit[i] && (anchored[i] || !propLike(specs.get(i)))) omit[i] = false;
        }

        List<Spec> finalSpecs() {
            List<Spec> out = new ArrayList<>(specs.size());
            for (int i = 0; i < specs.size(); i++) {
                Spec s = specs.get(i);
                if (omit[i] && !s.has(Spec.OMIT)) {
                    s.flags |= Spec.OMIT;
                    r.omitted++;
                }
                out.add(s);
            }
            return out;
        }

        // ------------------------------------------------------------------ 6. plots

        void plots() {
            int n = specs.size();
            for (int i = 0; i < n; i++) {
                Spec s = specs.get(i);
                if (omit[i] || s.has(Spec.OMIT) || s.floor == Spec.UNSET) continue;
                if (s.kind == Archetype.WALL) {
                    // a wall line as short rectangles along it
                    double len = Math.hypot(s.x2 - s.x, s.z2 - s.z);
                    int steps = Math.max(1, (int) Math.ceil(len / 3));
                    int hw = s.w / 2 + 1;
                    for (int k = 0; k <= steps; k++) {
                        int px = (int) Math.round(s.x + (s.x2 - s.x) * k / (double) steps);
                        int pz = (int) Math.round(s.z + (s.z2 - s.z) * k / (double) steps);
                        r.plot(i, new int[]{px - hw, pz - hw, px + hw, pz + hw}, StreetPlan.NO_PAD, StreetPlan.PLOT_WALL);
                    }
                    continue;
                }
                if (s.isLine()) continue;
                if (s.kind == Archetype.GATE) {
                    r.plot(i, rect(s, 0), StreetPlan.NO_PAD, StreetPlan.PLOT_WALL);
                } else if (s.kind.building || s.has(Spec.RESERVED) || blockingWork(s)) {
                    r.plot(i, rect(s, 0), padOf(s), s.kind == Archetype.TOWER ? StreetPlan.PLOT_WALL : StreetPlan.PLOT_BUILDING);
                } else if (s.kind == Archetype.PLAZA) {
                    r.plot(i, rect(s, 0), padOf(s), StreetPlan.PLOT_WORK);
                } else if (s.kind != Archetype.STREET && s.kind != Archetype.BRIDGE && (!s.has(Spec.GROUND) || s.kind == Archetype.CAMP)) {
                    // open works (a clearing camp's ground included): masks, and the road painter paves through them
                    r.plot(i, rect(s, 0), StreetPlan.NO_PAD, StreetPlan.PLOT_WORK);
                }
            }
            // the street lines, split where paths and junctions join them
            for (Line l : lines) {
                l.splits.sort((p, q) -> Double.compare(p[0], q[0]));
                int prev = l.a;
                double ha = r.nh.getFloat(l.a), hb = r.nh.getFloat(l.b);
                for (double[] sp : l.splits) {
                    int nd = (int) sp[1];
                    if (nd == prev) continue;
                    r.edge(prev, nd, l.w, cls(l, prev, nd), l.surf);
                    prev = nd;
                }
                r.edge(prev, l.b, l.w, cls(l, prev, l.b), l.surf);
            }
        }

        byte cls(Line l, int a, int b) {
            if (l.cls == StreetPlan.BRIDGE) return l.cls;
            double len = Math.hypot(r.nx.getFloat(b) - r.nx.getFloat(a), r.nz.getFloat(b) - r.nz.getFloat(a));
            double dh = Math.abs(r.nh.getFloat(b) - r.nh.getFloat(a));
            return dh >= 2 && dh > 0.35 * len ? StreetPlan.STAIRS : l.cls;
        }
    }

    // ================================================================================================ debugging

    /**
     * Dev aid: resolves {@code p} again and draws its walk grid round (x, z), radius {@code r}: '#' a building, 'W' a
     * wall or other blocking piece, 'S' street, 'P' plaza, 'G' gate, 'R' road, '~' water, 'y' a prop, 'f' a work, '.'
     * free; the second map gives the walk surface relative to the centre cell (0-9, a-z above, A-Z below), and each
     * door of the window with its reach.
     */
    public static String debug(Place p, int x, int z, int rad) {
        RoadPlan rp = RoadPlan.get();
        Resolver v = new Resolver(p, rp, roadEnds(rp).getOrDefault(p.id(), new LongArrayList()));
        Result res = v.run();
        StringBuilder a = new StringBuilder(), h = new StringBuilder();
        int c0 = v.idx(x, z);
        int h0 = c0 >= 0 ? v.surf[c0] : 0;
        for (int dz = -rad; dz <= rad; dz++) {
            for (int dx = -rad; dx <= rad; dx++) {
                int c = v.idx(x + dx, z + dz);
                char ch;
                if (c < 0) ch = ' ';
                else if (v.cost[c] == 0) ch = v.owner[c] >= 0 && v.specs.get(v.owner[c]).kind.building ? '#' : 'W';
                else if (v.target[c] == T_STREET) ch = 'S';
                else if (v.target[c] == T_PLAZA) ch = 'P';
                else if (v.target[c] == T_GATE) ch = 'G';
                else if (v.target[c] == T_ROAD) ch = 'R';
                else if (v.wet[c]) ch = '~';
                else if (v.prop[c] >= 0) ch = 'y';
                else if (v.cost[c] >= C_WORK) ch = 'f';
                else ch = '.';
                if (dx == 0 && dz == 0) ch = '@';
                a.append(ch);
                int d = c < 0 ? 0 : v.surf[c] - h0;
                h.append(c < 0 ? ' ' : d == 0 ? '0' : d > 0 ? (d < 10 ? (char) ('0' + d) : (char) ('a' + Math.min(25, d - 10)))
                        : (char) ('A' + Math.min(25, -d - 1)));
            }
            a.append('\n');
            h.append('\n');
        }
        StringBuilder doors = new StringBuilder();
        for (int i = 0; i < res.doorSpec.size(); i++) {
            int dx = res.doorX.getInt(i), dz = res.doorZ.getInt(i);
            if (Math.abs(dx - x) > rad || Math.abs(dz - z) > rad) continue;
            Spec s = v.specs.get(res.doorSpec.getInt(i));
            int[] rc = Resolver.rect(s, 0);
            Resolver.Route rt = v.route(new int[]{res.doorSpec.getInt(i), dx, res.doorY.getInt(i), dz, res.doorFacing.getByte(i)});
            StringBuilder cells = new StringBuilder();
            if (rt.path() != null) for (int k = 0; k < Math.min(12, rt.path().size()); k++) {
                int c = rt.path().getInt(k);
                cells.append(String.format(Locale.ROOT, " (%d %d s%d t%d)", v.gx0 + c % v.W, v.gz0 + c / v.W, v.surf[c], v.target[c]));
            }
            doors.append(String.format(Locale.ROOT, "door %d %d %d out %s spec %d %s %s floor %d rect %d..%d %d..%d reach %d prefix %d steps %d start %d path%s\n",
                    dx, res.doorY.getInt(i), dz, Direction.from2DDataValue(res.doorFacing.getByte(i)), res.doorSpec.getInt(i), s.kind,
                    s.facing, s.floor, rc[0], rc[2], rc[1], rc[3], res.doorReach.getByte(i), rt.prefix().size(), rt.steps(), rt.startH(), cells));
        }
        return "grid (centre " + x + " " + z + ", walk y " + h0 + "):\n" + a + "heights:\n" + h + doors;
    }

    // ================================================================================================ assembly

    static StreetPlan assemble(List<Result> results) {
        StreetPlan.Builder b = new StreetPlan.Builder();
        int ns = results.size();
        b.sId = new String[ns];
        b.sX = new int[ns];
        b.sZ = new int[ns];
        b.sReach = new int[ns];
        b.sNodeOff = new int[ns + 1];
        b.sEdgeOff = new int[ns + 1];
        b.sDoorOff = new int[ns + 1];
        b.sPlotOff = new int[ns + 1];
        b.sSpecOff = new int[ns + 1];
        for (int s = 0; s < ns; s++) {
            Result r = results.get(s);
            b.sId[s] = r.id;
            b.sX[s] = r.x;
            b.sZ[s] = r.z;
            b.sReach[s] = r.reach;
            b.sNodeOff[s + 1] = b.sNodeOff[s] + r.nx.size();
            b.sEdgeOff[s + 1] = b.sEdgeOff[s] + r.ea.size();
            b.sDoorOff[s + 1] = b.sDoorOff[s] + r.doorSpec.size();
            b.sPlotOff[s + 1] = b.sPlotOff[s] + r.pSpec.size();
            b.sSpecOff[s + 1] = b.sSpecOff[s] + r.specs.size();
        }
        int nn = b.sNodeOff[ns], ne = b.sEdgeOff[ns], nd = b.sDoorOff[ns], np = b.sPlotOff[ns], nsp = b.sSpecOff[ns];
        b.nx = new float[nn];
        b.nz = new float[nn];
        b.nh = new float[nn];
        b.nClass = new byte[nn];
        b.nJoinSeg = new int[nn];
        b.nJoinVert = new int[nn];
        b.ea = new int[ne];
        b.eb = new int[ne];
        b.ew = new float[ne];
        b.eClass = new byte[ne];
        b.eSurf = new byte[ne];
        b.dSpec = new int[nd];
        b.dx = new int[nd];
        b.dy = new int[nd];
        b.dz = new int[nd];
        b.dNode = new int[nd];
        b.dFacing = new byte[nd];
        b.dReach = new boolean[nd];
        b.pSpec = new int[np];
        b.pMinX = new int[np];
        b.pMinZ = new int[np];
        b.pMaxX = new int[np];
        b.pMaxZ = new int[np];
        b.pPad = new int[np];
        b.pKind = new byte[np];
        b.specInts = new int[nsp * StreetPlan.SPEC_INTS];
        b.specNames = new String[nsp];
        for (int s = 0; s < ns; s++) {
            Result r = results.get(s);
            int n0 = b.sNodeOff[s];
            for (int i = 0; i < r.nx.size(); i++) {
                b.nx[n0 + i] = r.nx.getFloat(i);
                b.nz[n0 + i] = r.nz.getFloat(i);
                b.nh[n0 + i] = r.nh.getFloat(i);
                b.nClass[n0 + i] = r.nCls.getByte(i);
                b.nJoinSeg[n0 + i] = r.nSeg.getInt(i);
                b.nJoinVert[n0 + i] = r.nVert.getInt(i);
            }
            int e0 = b.sEdgeOff[s];
            for (int i = 0; i < r.ea.size(); i++) {
                b.ea[e0 + i] = n0 + r.ea.getInt(i);
                b.eb[e0 + i] = n0 + r.eb.getInt(i);
                b.ew[e0 + i] = r.ew.getFloat(i);
                b.eClass[e0 + i] = r.eCls.getByte(i);
                b.eSurf[e0 + i] = r.eSurf.getByte(i);
            }
            int d0 = b.sDoorOff[s];
            for (int i = 0; i < r.doorSpec.size(); i++) {
                b.dSpec[d0 + i] = r.doorSpec.getInt(i);
                b.dx[d0 + i] = r.doorX.getInt(i);
                b.dy[d0 + i] = r.doorY.getInt(i);
                b.dz[d0 + i] = r.doorZ.getInt(i);
                b.dNode[d0 + i] = n0 + r.doorNode.getInt(i);
                b.dFacing[d0 + i] = r.doorFacing.getByte(i);
                b.dReach[d0 + i] = r.doorReach.getByte(i) != 0;
            }
            int p0 = b.sPlotOff[s];
            for (int i = 0; i < r.pSpec.size(); i++) {
                b.pSpec[p0 + i] = r.pSpec.getInt(i);
                b.pMinX[p0 + i] = r.pMinX.getInt(i);
                b.pMinZ[p0 + i] = r.pMinZ.getInt(i);
                b.pMaxX[p0 + i] = r.pMaxX.getInt(i);
                b.pMaxZ[p0 + i] = r.pMaxZ.getInt(i);
                b.pPad[p0 + i] = r.pPad.getInt(i);
                b.pKind[p0 + i] = r.pKind.getByte(i);
            }
            int s0 = b.sSpecOff[s];
            for (int i = 0; i < r.specs.size(); i++) {
                Spec sp = r.specs.get(i);
                StreetPlan.encode(sp, b.specInts, (s0 + i) * StreetPlan.SPEC_INTS);
                b.specNames[s0 + i] = sp.name == null ? "" : sp.name;
            }
        }
        return new StreetPlan(b);
    }
}
