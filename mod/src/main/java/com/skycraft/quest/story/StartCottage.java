package com.skycraft.quest.story;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.TamrielSpawn;
import com.skycraft.worldplan.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The opening's stage: a timber cottage on a graded hillside above the Riverwood road, 120 to 200 blocks from Helgen,
 * with a window that sees the whole town. The site is searched from the terrain noise (deterministic per world; the
 * choice is then stored with the world), the cottage is built once on real ground (the pad is graded into the hill, a
 * retaining face where it must be, never a platform on air), a winding path joins its door to the planned road, and the
 * trees in the sight cone are topped. All the heavy work happens before the player's character is done: the cottage
 * itself synchronously on first join, the path and the cone in {@link #tickPrep} as chunks load.
 *
 * <p>Local frame: {@code l} runs across the front (0..8, the door at 1, the big window 4..6), {@code d} runs back from
 * the front wall (0..6); the front faces Helgen along the dominant axis. See {@link Site#at}.</p>
 */
public final class StartCottage {
    /** Where the cottage is: floor level {@code floorY}, which way it faces, and the town it looks at. */
    public record Site(BlockPos origin, int floorY, int fx, int fz, int helgenX, int helgenZ, int helgenY) {
        /** Local frame centre (l, d) of the footprint is the origin column. */
        static final int CL = 4, CD = 3;

        /** World position of local (l = across, d = back from the front wall) at height y. */
        public BlockPos at(int l, int d, int y) {
            int rx = -fz, rz = fx;
            return new BlockPos(origin.getX() + rx * (l - CL) + fx * (CD - d), y, origin.getZ() + rz * (l - CL) + fz * (CD - d));
        }

        /** Foot of the bed. */
        public BlockPos bed() {
            return at(1, 4, floorY + 1);
        }

        /** Where a player wakes: beside the bed. */
        public Vec3 wake() {
            BlockPos p = at(3, 4, floorY + 1);
            return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        }

        /** Yaw that looks out of the big window (0 = +z, 90 = -x). */
        public float yaw() {
            return (float) (Mth.atan2(-fx, fz) * 180.0 / Math.PI);
        }

        /** Where a player stands to look out of the big window; walking up to it starts the cutscene. */
        public Vec3 windowSpot() {
            BlockPos p = at(5, 1, floorY + 1);
            return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        }

        /** The eye of a player at {@link #windowSpot}. */
        public Vec3 windowEye() {
            return windowSpot().add(0, 1.62, 0);
        }

        /** The lower half of the door. */
        public BlockPos door() {
            return at(1, 0, floorY + 1);
        }

        /** The centre of the cottage floor, for distance checks. */
        public Vec3 centre() {
            BlockPos p = at(CL, CD, floorY + 1);
            return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        }
    }

    // ------------------------------------------------------------------ persisted site

    private static final int VERSION = 3;
    private static final String DATA = "skycraft_start_cottage";

    /** The site, what has been built and prepared, saved with the overworld so the choice never moves. */
    static final class Data extends SavedData {
        int version;
        int ox, oz, floorY, fx, fz;
        boolean built, prepared;
        float visibility = -1f;
        String report = "";
        /** What the preparation measured, kept so the QA check can read it after a restart. */
        int pathCells, pathBreaks, pathGaps, unsupported = -1, cleared;
        boolean pathBuilt, pathJoins;

        static Data load(CompoundTag t) {
            Data d = new Data();
            d.pathCells = t.getInt("pathCells");
            d.pathBreaks = t.getInt("pathBreaks");
            d.pathGaps = t.getInt("pathGaps");
            d.unsupported = t.contains("unsupported") ? t.getInt("unsupported") : -1;
            d.cleared = t.getInt("cleared");
            d.pathBuilt = t.getBoolean("pathBuilt");
            d.pathJoins = t.getBoolean("pathJoins");
            d.version = t.getInt("version");
            d.ox = t.getInt("ox");
            d.oz = t.getInt("oz");
            d.floorY = t.getInt("floorY");
            d.fx = t.getInt("fx");
            d.fz = t.getInt("fz");
            d.built = t.getBoolean("built");
            d.prepared = t.getBoolean("prepared");
            d.visibility = t.contains("visibility") ? t.getFloat("visibility") : -1f;
            d.report = t.getString("report");
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag t) {
            t.putInt("version", version);
            t.putInt("ox", ox);
            t.putInt("oz", oz);
            t.putInt("floorY", floorY);
            t.putInt("fx", fx);
            t.putInt("fz", fz);
            t.putBoolean("built", built);
            t.putBoolean("prepared", prepared);
            t.putFloat("visibility", visibility);
            t.putString("report", report);
            t.putInt("pathCells", pathCells);
            t.putInt("pathBreaks", pathBreaks);
            t.putInt("pathGaps", pathGaps);
            t.putInt("unsupported", unsupported);
            t.putInt("cleared", cleared);
            t.putBoolean("pathBuilt", pathBuilt);
            t.putBoolean("pathJoins", pathJoins);
            return t;
        }
    }

    private static Data data(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(Data::load, Data::new, DATA);
    }

    private static final Map<Long, Optional2> CACHE = new HashMap<>();

    /** Nullable-site holder so "searched, nothing found" is cached too. */
    private record Optional2(@Nullable Site site) {
    }

    private static final int HELGEN_X = -24, HELGEN_Z = 1143;

    private StartCottage() {}

    // ------------------------------------------------------------------ Helgen facts

    /** Helgen's centre (x, z), from the places data when present. */
    public static int[] helgen() {
        Place p = Places.byId("helgen").orElse(null);
        return p != null ? new int[]{p.x(), p.z()} : new int[]{HELGEN_X, HELGEN_Z};
    }

    /** The town's radius in blocks (clamped), for the loading screen's chunk list. */
    public static int townRadius() {
        return helgenRadius();
    }

    private static int helgenRadius() {
        Place p = Places.byId("helgen").orElse(null);
        return p != null ? Mth.clamp(p.radius(), 40, 90) : 60;
    }

    /** True within {@code margin} blocks of Helgen's walls (used to keep it the ruined, shop-less town). */
    public static boolean nearHelgen(int x, int z, int margin) {
        Place p = Places.byId("helgen").orElse(null);
        int hx = p != null ? p.x() : HELGEN_X, hz = p != null ? p.z() : HELGEN_Z, r = p != null ? p.radius() : 60;
        long dx = x - hx, dz = z - hz;
        long lim = r + margin;
        return dx * dx + dz * dz <= lim * lim;
    }

    // ------------------------------------------------------------------ site

    /** The cottage site for this world, or null when it is not a Tamriel world or no good hill was found. */
    @Nullable
    public static synchronized Site site(ServerLevel level) {
        if (!TamrielSpawn.isTamriel(level)) return null;
        long seed = level.getSeed();
        Optional2 cached = CACHE.get(seed);
        if (cached == null) {
            Site s;
            try {
                Data d = data(level);
                if (d.version == VERSION && (d.fx != 0 || d.fz != 0)) {
                    int[] h = helgen();
                    s = new Site(new BlockPos(d.ox, d.floorY, d.oz), d.floorY, d.fx, d.fz, h[0], h[1], height(level, h[0], h[1]));
                } else {
                    s = search(level);
                    if (s != null) {
                        d.version = VERSION;
                        d.ox = s.origin().getX();
                        d.oz = s.origin().getZ();
                        d.floorY = s.floorY();
                        d.fx = s.fx();
                        d.fz = s.fz();
                        d.built = false;
                        d.prepared = false;
                        d.setDirty();
                    }
                }
            } catch (RuntimeException e) {
                com.skycraft.Skycraft.LOGGER.warn("Start cottage site search failed", e);
                s = null;
            }
            cached = new Optional2(s);
            CACHE.put(seed, cached);
        }
        return cached.site();
    }

    public static synchronized void reset() {
        CACHE.clear();
        PREP = null;
    }

    private static int height(ServerLevel level, int x, int z) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
    }

    /** The ground height at x/z: the real surface where the chunk is loaded (through trees), else the terrain noise. */
    static int groundY(ServerLevel level, int x, int z) {
        int g = CottageTerrain.groundTop(level, x, z);
        if (g != CottageTerrain.NO_GROUND) return g + 1;
        return height(level, x, z);
    }

    private static Site search(ServerLevel level) {
        int[] h = helgen();
        int hx = h[0], hz = h[1], hr = helgenRadius();
        double base = -Math.PI / 2; // north (-z) by default
        Place riverwood = Places.byId("riverwood").orElse(null);
        if (riverwood != null) base = Math.atan2(riverwood.z() - hz, riverwood.x() - hx);
        int hy = height(level, hx, hz);
        RoadPlan plan = null;
        try {
            plan = RoadPlan.get();
            if (plan.segments() == 0) plan = null;
        } catch (RuntimeException | Error e) {
            plan = null;
        }
        MapSample s = new MapSample();
        RoadPlan.Hit hit = new RoadPlan.Hit();
        Map<Long, Integer> hcache = new HashMap<>();
        double bestScore = -1e9;
        Site best = null;
        double bestVis = 0;
        int considered = 0;
        int[] dists = {130, 140, 120, 150, 160, 170, 180, 190, 200};
        for (int dist : dists) {
            for (int step = 0; step <= 10; step++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    if (step == 0 && sign == 1) continue;
                    double da = Math.toRadians(step * 6 * sign);
                    int x = hx + (int) Math.round(Math.cos(base + da) * dist);
                    int z = hz + (int) Math.round(Math.sin(base + da) * dist);
                    TamrielMap.sample(x, z, s);
                    if (!s.inTamriel || s.coastDistance < 24 || s.river > 0.15f || s.lake > 0.15f || s.riverDistance < 14) continue;
                    int y0 = height(level, x, z);
                    if (y0 <= TamrielMap.SEA_LEVEL + 4) continue;
                    double ddx = hx - x, ddz = hz - z;
                    int fx = Math.abs(ddx) >= Math.abs(ddz) ? (int) Math.signum(ddx) : 0;
                    int fz = fx == 0 ? (int) Math.signum(ddz) : 0;
                    if (fx == 0 && fz == 0) fz = 1;
                    Site cand = new Site(new BlockPos(x, y0, z), y0, fx, fz, hx, hz, hy);
                    // grounded: the footprint's columns sit within 3 blocks of the floor level (median of the noise heights)
                    int[] hs = new int[63];
                    int n = 0;
                    boolean wet = false;
                    for (int l = 0; l <= 8; l++) {
                        for (int d = 0; d <= 6; d++) {
                            BlockPos c = cand.at(l, d, 0);
                            hs[n++] = height(level, c.getX(), c.getZ());
                        }
                    }
                    int[] sorted = hs.clone();
                    Arrays.sort(sorted);
                    int floor = sorted[31];
                    int within = 0;
                    for (int v : hs) if (Math.abs(v - floor) <= 3) within++;
                    if (within < 0.9 * hs.length || floor <= TamrielMap.SEA_LEVEL + 4) continue;
                    cand = new Site(new BlockPos(x, floor, z), floor, fx, fz, hx, hz, hy);
                    considered++;
                    double vis = terrainVisibility(level, cand, hx, hz, hr, hcache);
                    if (vis < 0.45) continue;
                    // the road: how far the planned road is and how far below
                    double roadScore = 0;
                    if (plan != null) {
                        BlockPos st = cand.at(1, -5, 0);
                        double nearest = 1e9;
                        double roadY = floor;
                        for (int r = 12; r <= 110; r += 6) {
                            for (int a = 0; a < 16; a++) {
                                double ang = a * Math.PI / 8;
                                if (plan.at(st.getX() + Math.cos(ang) * r, st.getZ() + Math.sin(ang) * r, hit) && hit.distance < nearest) {
                                    nearest = r - 0 + hit.distance * 0.0;
                                    roadY = hit.height;
                                }
                            }
                            if (nearest < 1e8) break;
                        }
                        if (nearest > 1e8) continue; // no road within 110 blocks: the path would be a trail to nowhere
                        roadScore = -Math.abs(nearest - 50) * 0.15 + Math.min(18, Math.max(0, floor - roadY)) * 0.6;
                    }
                    double score = 100 * vis + Math.min(24, floor - hy) * 0.5 - Math.abs(dist - 150) * 0.1 - step * 0.4 + roadScore;
                    if (score > bestScore) {
                        bestScore = score;
                        best = cand;
                        bestVis = vis;
                    }
                }
            }
        }
        if (best == null) {
            com.skycraft.Skycraft.LOGGER.warn("Start cottage: no grounded site with a view of Helgen among {} candidates", considered);
            return null;
        }
        com.skycraft.Skycraft.LOGGER.info("Start cottage site near ({}, {}, {}), {} blocks from Helgen, facing ({}, {}), terrain viewshed {}%",
                best.origin().getX(), best.floorY(), best.origin().getZ(),
                (int) Math.sqrt((double) (best.origin().getX() - hx) * (best.origin().getX() - hx) + (double) (best.origin().getZ() - hz) * (best.origin().getZ() - hz)),
                best.fx(), best.fz(), (int) Math.round(bestVis * 100));
        return best;
    }

    private static int cachedHeight(ServerLevel level, Map<Long, Integer> cache, int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        Integer v = cache.get(key);
        if (v == null) {
            v = height(level, x, z);
            cache.put(key, v);
        }
        return v;
    }

    /** Fraction of a grid over the town (20-block spacing, ground and +10) a ray from the window clears over the terrain. */
    private static double terrainVisibility(ServerLevel level, Site s, int hx, int hz, int hr, Map<Long, Integer> cache) {
        Vec3 eye = s.windowEye();
        int vis = 0, total = 0;
        for (int gx = -hr; gx <= hr; gx += 20) {
            for (int gz = -hr; gz <= hr; gz += 20) {
                if (gx * gx + gz * gz > hr * hr) continue;
                int tx = hx + gx, tz = hz + gz;
                int tg = cachedHeight(level, cache, tx, tz);
                for (int up = 0; up <= 10; up += 10) {
                    total++;
                    double ty = tg + 1.5 + up;
                    double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    int steps = Math.max(2, (int) (len / 6));
                    boolean ok = true;
                    for (int k = 2; k < steps && ok; k++) {
                        double t = k / (double) steps;
                        double px = eye.x + dx * t, pz = eye.z + dz * t;
                        double ddx = px - hx, ddz = pz - hz;
                        if (ddx * ddx + ddz * ddz < (hr * 0.9) * (hr * 0.9)) break;
                        int th = cachedHeight(level, cache, (int) Math.floor(px / 3) * 3, (int) Math.floor(pz / 3) * 3);
                        if (th > eye.y + dy * t + 0.5) ok = false;
                    }
                    if (ok) vis++;
                }
            }
        }
        return total == 0 ? 0 : vis / (double) total;
    }

    // ------------------------------------------------------------------ chunks

    private static void loadPad(ServerLevel level, Site s) {
        Set<Long> seen = new HashSet<>();
        for (int l = -14; l <= 18; l += 8) {
            for (int d = -14; d <= 16; d += 8) {
                BlockPos p = s.at(l, d, 0);
                if (seen.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4))) level.getChunk(p.getX() >> 4, p.getZ() >> 4);
            }
        }
        for (int[] c : new int[][]{{-14, -14}, {18, -14}, {-14, 16}, {18, 16}}) {
            BlockPos p = s.at(c[0], c[1], 0);
            if (seen.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4))) level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        }
    }

    // ------------------------------------------------------------------ building

    /**
     * Makes sure the cottage stands (builds it the first time, synchronously) and returns the site; null when this world
     * has none. Loads the chunks it covers. The path and the sight cone follow in {@link #tickPrep}.
     */
    @Nullable
    public static Site ensure(ServerLevel level) {
        Site tmp = site(level);
        if (tmp == null) return null;
        loadPad(level, tmp);
        Data d = data(level);
        if (d.built) {
            requestPrep(level);
            return tmp;
        }
        // the real floor: the median of the natural ground under the footprint (trees ignored), one above it
        int[] gs = new int[63];
        int n = 0;
        for (int l = 0; l <= 8; l++) {
            for (int dd = 0; dd <= 6; dd++) {
                BlockPos p = tmp.at(l, dd, 0);
                int g = CottageTerrain.groundTop(level, p.getX(), p.getZ());
                gs[n++] = g == CottageTerrain.NO_GROUND ? tmp.floorY() - 1 : g;
            }
        }
        Arrays.sort(gs);
        int floor = Math.max(gs[31] + 1, TamrielMap.SEA_LEVEL + 2);
        Site site = new Site(tmp.origin(), floor, tmp.fx(), tmp.fz(), tmp.helgenX(), tmp.helgenZ(), tmp.helgenY());
        synchronized (StartCottage.class) {
            CACHE.put(level.getSeed(), new Optional2(site));
        }
        build(level, site);
        d.floorY = floor;
        d.built = true;
        d.setDirty();
        requestPrep(level);
        return site;
    }

    /**
     * Builds the cottage and starts its preparation at server start, when this world has one and it is not done yet, so
     * that the first player finds it ready (the build and the chunk generation happen behind the world-loading screen).
     */
    public static void prebuild(ServerLevel level) {
        Site tmp = site(level);
        if (tmp == null) return;
        Data d = data(level);
        if (d.built && d.prepared) return;
        ensure(level);
    }

    /** Admin: throws away what was built and prepared and builds again at the stored site. */
    public static void rebuild(ServerLevel level) {
        Site tmp = site(level);
        if (tmp == null) return;
        Data d = data(level);
        d.built = false;
        d.prepared = false;
        d.setDirty();
        PREP = null;
        ensure(level);
    }

    private static void put(ServerLevel level, BlockPos p, BlockState state) {
        level.setBlock(p, state, CottageTerrain.SET);
    }

    private static BlockState log(Block b, Direction.Axis axis) {
        return b.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    private static void build(ServerLevel level, Site s) {
        int y0 = s.floorY();
        CottageTerrain.GradeReport gr = CottageTerrain.grade(level, s);
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        Direction dirF = Direction.fromDelta(s.fx(), 0, s.fz());
        Direction dirR = Direction.fromDelta(-s.fz(), 0, s.fx());
        Direction back = dirF.getOpposite();
        Direction.Axis alongL = dirR.getAxis(), alongD = dirF.getAxis();

        // the volume: air from the floor up to the ridge and chimney
        for (int l = -2; l <= 10; l++) {
            for (int d = -3; d <= 8; d++) {
                for (int y = y0; y <= y0 + 11; y++) put(level, s.at(l, d, y), air);
            }
        }
        // floor and plinth
        for (int l = 0; l <= 8; l++) {
            for (int d = 0; d <= 6; d++) {
                boolean ring = l == 0 || l == 8 || d == 0 || d == 6;
                put(level, s.at(l, d, y0 - 1), Blocks.DIRT.defaultBlockState());
                put(level, s.at(l, d, y0), ring ? ((l + d) % 4 == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : cobble) : planks);
            }
        }
        // walls: dark timber posts at the corners and beside the openings, a beam under the eaves, spruce between
        BlockState post = log(Blocks.DARK_OAK_LOG, Direction.Axis.Y);
        for (int l = 0; l <= 8; l++) {
            for (int d = 0; d <= 6; d++) {
                if (!(l == 0 || l == 8 || d == 0 || d == 6)) continue;
                boolean corner = (l == 0 || l == 8) && (d == 0 || d == 6);
                boolean frontBack = d == 0 || d == 6;
                boolean postHere = corner || (frontBack && (l == 3 || l == 7)) || (!frontBack && d == 4);
                for (int y = y0 + 1; y <= y0 + 4; y++) {
                    BlockState st;
                    if (y == y0 + 4) st = log(Blocks.DARK_OAK_LOG, frontBack ? alongL : alongD);
                    else if (postHere) st = post;
                    else st = planks;
                    put(level, s.at(l, d, y), st);
                }
            }
        }
        // openings: the door and the windows (panes are placed last, with their connections)
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, back).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        put(level, s.at(1, 0, y0 + 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        put(level, s.at(1, 0, y0 + 2), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        List<BlockPos> glass = new ArrayList<>();
        for (int l = 4; l <= 6; l++) {
            for (int y = y0 + 2; y <= y0 + 3; y++) {
                glass.add(s.at(l, 0, y)); // the big window towards Helgen
                glass.add(s.at(l, 6, y)); // and its twin at the back
            }
        }
        for (int d = 2; d <= 3; d++) {
            for (int y = y0 + 2; y <= y0 + 3; y++) glass.add(s.at(0, d, y));
        }
        for (int y = y0 + 2; y <= y0 + 3; y++) glass.add(s.at(8, 5, y));
        // the roof: stepped gable across the cottage, solid so no sky shows through, eaves over the porch
        BlockState stairL = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dirR);
        BlockState stairR = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dirR.getOpposite());
        for (int d = -2; d <= 7; d++) {
            for (int k = 0; k <= 4; k++) {
                int yy = y0 + 5 + k;
                int lo = -1 + k, hi = 9 - k;
                if (k < 4) {
                    put(level, s.at(lo, d, yy), stairL);
                    put(level, s.at(hi, d, yy), stairR);
                    for (int l = lo + 1; l <= hi - 1; l++) put(level, s.at(l, d, yy), planks);
                } else {
                    put(level, s.at(lo, d, yy), stairL);
                    put(level, s.at(hi, d, yy), stairR);
                    put(level, s.at(4, d, yy), Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                }
            }
        }
        // the hearth in the east wall and the chimney outside it, smoking
        put(level, s.at(7, 3, y0 + 1), Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, dirR.getOpposite()).setValue(FurnaceBlock.LIT, true));
        for (int y = y0 + 1; y <= y0 + 4; y++) put(level, s.at(8, 3, y), Blocks.STONE_BRICKS.defaultBlockState());
        for (int y = y0; y <= y0 + 9; y++) put(level, s.at(9, 3, y), (y + 1) % 4 == 0 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
        put(level, s.at(9, 3, y0 + 10), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, false));
        // porch: deck, posts and rails, a hanging lantern, steps down
        for (int l = 0; l <= 2; l++) {
            for (int d = -2; d <= -1; d++) put(level, s.at(l, d, y0), planks);
        }
        for (int y = y0 + 1; y <= y0 + 4; y++) {
            put(level, s.at(0, -2, y), log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            put(level, s.at(2, -2, y), log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
        }
        put(level, s.at(0, -1, y0 + 1), Blocks.SPRUCE_FENCE.defaultBlockState());
        put(level, s.at(2, -1, y0 + 1), Blocks.SPRUCE_FENCE.defaultBlockState());
        put(level, s.at(1, -2, y0 + 4), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        put(level, s.at(1, -3, y0), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, back));
        put(level, s.at(1, -4, y0 - 1), Blocks.DIRT_PATH.defaultBlockState());
        put(level, s.at(1, -5, y0 - 1), Blocks.DIRT_PATH.defaultBlockState());
        // shutters either side of the big window
        for (int l : new int[]{3, 7}) {
            for (int y = y0 + 2; y <= y0 + 3; y++) {
                put(level, s.at(l, -1, y), Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, dirF)
                        .setValue(TrapDoorBlock.OPEN, true).setValue(TrapDoorBlock.HALF, Half.BOTTOM));
            }
        }
        // inside: bed under the west window, table with a lantern, hearth side with shelves, carpet, a hanging lantern
        BlockState bedFoot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, back).setValue(BedBlock.PART, BedPart.FOOT);
        BlockPos foot = s.bed();
        put(level, foot, bedFoot);
        put(level, foot.relative(back), bedFoot.setValue(BedBlock.PART, BedPart.HEAD));
        for (int l = 3; l <= 5; l++) {
            for (int d = 2; d <= 4; d++) put(level, s.at(l, d, y0 + 1), Blocks.RED_CARPET.defaultBlockState());
        }
        put(level, s.at(5, 5, y0 + 1), Blocks.SPRUCE_FENCE.defaultBlockState());
        put(level, s.at(5, 5, y0 + 2), Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState());
        put(level, s.at(5, 5, y0 + 3), Blocks.LANTERN.defaultBlockState());
        put(level, s.at(4, 5, y0 + 1), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dirR));
        put(level, s.at(7, 1, y0 + 1), Blocks.BOOKSHELF.defaultBlockState());
        put(level, s.at(7, 2, y0 + 1), Blocks.BOOKSHELF.defaultBlockState());
        put(level, s.at(7, 1, y0 + 2), Blocks.BOOKSHELF.defaultBlockState());
        put(level, s.at(7, 4, y0 + 1), Blocks.BARREL.defaultBlockState());
        put(level, s.at(7, 5, y0 + 1), Blocks.CRAFTING_TABLE.defaultBlockState());
        put(level, s.at(2, 5, y0 + 1), Blocks.BARREL.defaultBlockState());
        put(level, s.at(4, 3, y0 + 4), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        // the roof is solid; the ceiling plank row above the lantern holds it
        put(level, s.at(4, 3, y0 + 5), planks);
        // the yard: woodpile, a stump, a well, a fenced garden with a few crops, lanterns
        for (int d = 1; d <= 3; d++) {
            put(level, s.at(-1, d, y0), log(Blocks.SPRUCE_LOG, alongD));
            put(level, s.at(-2, d, y0), log(Blocks.SPRUCE_LOG, alongD));
            if (d != 2) put(level, s.at(-1, d, y0 + 1), log(Blocks.SPRUCE_LOG, alongD));
        }
        put(level, s.at(-2, 5, y0), log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
        // well
        for (int l = -3; l <= -1; l++) {
            for (int d = -4; d <= -2; d++) {
                boolean mid = l == -2 && d == -3;
                put(level, s.at(l, d, y0 - 1), mid ? Blocks.WATER.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
                put(level, s.at(l, d, y0), mid ? air : (l + d) % 2 == 0 ? Blocks.COBBLESTONE_WALL.defaultBlockState() : Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState());
            }
        }
        for (int[] c : new int[][]{{-3, -4}, {-1, -4}, {-3, -2}, {-1, -2}}) {
            put(level, s.at(c[0], c[1], y0 + 1), Blocks.SPRUCE_FENCE.defaultBlockState());
            put(level, s.at(c[0], c[1], y0 + 2), Blocks.SPRUCE_FENCE.defaultBlockState());
        }
        for (int l = -3; l <= -1; l++) {
            for (int d = -4; d <= -2; d++) put(level, s.at(l, d, y0 + 3), Blocks.SPRUCE_SLAB.defaultBlockState());
        }
        // garden: fence around l 5..9, d -5..-2 with a gap by the path, rows of crops on tilled soil
        for (int l = 5; l <= 9; l++) {
            if (l != 6) put(level, s.at(l, -5, y0), Blocks.SPRUCE_FENCE.defaultBlockState());
        }
        for (int d = -4; d <= -2; d++) {
            put(level, s.at(9, d, y0), Blocks.SPRUCE_FENCE.defaultBlockState());
            if (d != -4) put(level, s.at(5, d, y0), Blocks.SPRUCE_FENCE.defaultBlockState());
        }
        put(level, s.at(9, -5, y0 + 1), Blocks.LANTERN.defaultBlockState());
        for (int l = 6; l <= 8; l++) {
            put(level, s.at(l, -3, y0 - 1), Blocks.FARMLAND.defaultBlockState());
            put(level, s.at(l, -3, y0), l == 7 ? Blocks.CARROTS.defaultBlockState() : Blocks.POTATOES.defaultBlockState());
            put(level, s.at(l, -2, y0 - 1), Blocks.FARMLAND.defaultBlockState());
            put(level, s.at(l, -2, y0), Blocks.WHEAT.defaultBlockState());
        }
        put(level, s.at(7, -4, y0), Blocks.HAY_BLOCK.defaultBlockState());
        put(level, s.at(8, -4, y0), Blocks.PUMPKIN.defaultBlockState());
        put(level, s.at(6, -4, y0), Blocks.COMPOSTER.defaultBlockState());
        // windows last, with their connections; fences and walls too
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        for (BlockPos g : glass) put(level, g, pane);
        CottageTerrain.shapes(level, s.at(-3, -6, y0 - 1), s.at(10, 8, y0 + 12));
        for (BlockPos g : glass) {
            BlockState st = level.getBlockState(g);
            if (st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock) {
                level.setBlock(g, Block.updateFromNeighbourShapes(st, level, g), CottageTerrain.SET);
            }
        }
        // the door is locked by the intro (see IntroFlow); the lock system knows it too
        try {
            com.skycraft.crime.Locks.setLock(level, s.door(), com.skycraft.crime.Locks.MASTER);
        } catch (RuntimeException e) {
            com.skycraft.Skycraft.LOGGER.warn("Could not lock the cottage door", e);
        }
        com.skycraft.Skycraft.LOGGER.info("Built the start cottage at {} (floor y {}) facing Helgen ({}, {}); graded {} columns ({} filled, {} cut, {} retaining, margin {})",
                s.origin(), y0, s.helgenX(), s.helgenZ(), gr.columns(), gr.filled(), gr.cut(), gr.retaining(), gr.margin());
    }

    // ------------------------------------------------------------------ preparation (path, sight cone) as chunks load

    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("skycraft_cottage", Comparator.comparingLong(ChunkPos::toLong), 20 * 240);

    private static final class Prep {
        final List<Long> chunks = new ArrayList<>();
        int next;
        int stage; // 0 loading, 1 working, 2 done
        long started;
        CottageTerrain.PathReport path;
        CottageTerrain.View view;
        int cleared;
        int rocks;
        int loaded;
    }

    private static Prep PREP;

    /** Starts the path and cone preparation of this world's cottage (idempotent; nothing when it is already done). */
    public static synchronized void requestPrep(ServerLevel level) {
        if (PREP != null) return;
        Site s = site(level);
        if (s == null) return;
        Data d = data(level);
        if (d.prepared) return;
        Prep p = new Prep();
        p.started = level.getGameTime();
        p.chunks.addAll(chunkList(level, s));
        PREP = p;
        com.skycraft.Skycraft.LOGGER.info("Start cottage preparation: {} chunks to load along the sight cone, town and path", p.chunks.size());
    }

    /** Loads (synchronously, blocking) every chunk of the cone, the town and the path, then runs the preparation now. For tests. */
    public static synchronized void prepareNow(ServerLevel level) {
        Site s = site(level);
        if (s == null) return;
        loadPad(level, s);
        Data d = data(level);
        d.prepared = false;
        PREP = null;
        requestPrep(level);
        Prep p = PREP;
        if (p == null) return;
        for (long k : p.chunks) level.getChunk(ChunkPos.getX(k), ChunkPos.getZ(k));
        p.next = p.chunks.size();
        p.loaded = p.chunks.size();
        p.stage = 1;
        tickPrep(level);
    }

    /** The chunks the sight cone, the town and the path cover, the nearest to the window first. */
    private static List<Long> chunkList(ServerLevel level, Site s) {
        Set<Long> set = new HashSet<>();
        int hr = helgenRadius();
        Vec3 eye = s.windowEye();
        // the cone's chunks: the triangle from the window to the town (generous), the town disc, the path's chunks
        double dx = s.helgenX() + 0.5 - eye.x, dz = s.helgenZ() + 0.5 - eye.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double ux = dx / Math.max(1, len), uz = dz / Math.max(1, len);
        int minCx = (int) Math.floor(Math.min(eye.x, s.helgenX()) - hr - 16) >> 4, maxCx = (int) Math.ceil(Math.max(eye.x, s.helgenX()) + hr + 16) >> 4;
        int minCz = (int) Math.floor(Math.min(eye.z, s.helgenZ()) - hr - 16) >> 4, maxCz = (int) Math.ceil(Math.max(eye.z, s.helgenZ()) + hr + 16) >> 4;
        double half = Math.asin(Math.min(0.95, (hr + 12.0) / Math.max(20, len))) + Math.toRadians(8);
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                double vx = cx * 16 + 8 - eye.x, vz = cz * 16 + 8 - eye.z;
                double vl = Math.sqrt(vx * vx + vz * vz);
                double hxd = cx * 16 + 8 - s.helgenX(), hzd = cz * 16 + 8 - s.helgenZ();
                boolean town = Math.sqrt(hxd * hxd + hzd * hzd) <= hr + 20;
                boolean cone = vl < 24 || (vx * ux + vz * uz > 0 && Math.acos(Mth.clamp((vx * ux + vz * uz) / vl, -1, 1)) <= half + 12.0 / Math.max(12, vl));
                if (town || cone) set.add(ChunkPos.asLong(cx, cz));
            }
        }
        try {
            set.addAll(CottageTerrain.pathChunks(level, s));
        } catch (RuntimeException e) {
            com.skycraft.Skycraft.LOGGER.warn("Cottage path chunk list failed", e);
        }
        List<Long> out = new ArrayList<>(set);
        out.sort(Comparator.comparingDouble(k -> {
            double cx = ChunkPos.getX(k) * 16 + 8 - eye.x, cz = ChunkPos.getZ(k) * 16 + 8 - eye.z;
            return cx * cx + cz * cz;
        }));
        return out;
    }

    public static synchronized boolean prepared(ServerLevel level) {
        if (site(level) == null) return true;
        return data(level).prepared;
    }

    /** 0..1: chunks loaded so far of the preparation (1 when done). */
    public static synchronized float prepProgress(ServerLevel level) {
        if (prepared(level)) return 1f;
        Prep p = PREP;
        if (p == null || p.chunks.isEmpty()) return 0f;
        return Math.min(0.95f, 0.9f * p.loaded / (float) p.chunks.size());
    }

    /** The percentage of the town's sample points the window sees (after preparation), or -1. */
    public static synchronized float visibility(ServerLevel level) {
        return data(level).visibility;
    }

    public static synchronized String report(ServerLevel level) {
        return data(level).report;
    }

    /** Server tick: load the chunks (a few per tick, by tickets) and then do the path and the cone. */
    public static synchronized void tickPrep(ServerLevel level) {
        Prep p = PREP;
        if (p == null || p.stage >= 2) return;
        Site s = site(level);
        if (s == null) {
            p.stage = 2;
            return;
        }
        long age = level.getGameTime() - p.started;
        if (p.stage == 0) {
            // ask for a few chunks per tick; count the ones that are in
            int asked = 0;
            while (p.next < p.chunks.size() && asked < 6) {
                long k = p.chunks.get(p.next++);
                ChunkPos cp = new ChunkPos(k);
                level.getChunkSource().addRegionTicket(TICKET, cp, 0, cp);
                asked++;
            }
            int loaded = 0;
            for (long k : p.chunks) {
                if (level.getChunkSource().getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k)) != null) loaded++;
            }
            p.loaded = loaded;
            if ((loaded >= p.chunks.size() && p.next >= p.chunks.size()) || age > 20 * 150) {
                if (loaded < p.chunks.size()) {
                    com.skycraft.Skycraft.LOGGER.warn("Start cottage preparation: only {}/{} chunks loaded after {} s, going on", loaded, p.chunks.size(), age / 20);
                }
                p.stage = 1;
            }
            return;
        }
        // stage 1: keep the tickets alive and do the work in one tick
        for (long k : p.chunks) {
            ChunkPos cp = new ChunkPos(k);
            level.getChunkSource().addRegionTicket(TICKET, cp, 0, cp);
        }
        Data d = data(level);
        try {
            CottageTerrain.Town town = new CottageTerrain.Town(s.helgenX(), s.helgenZ(), helgenRadius(), 0);
            int hg = CottageTerrain.groundTop(level, s.helgenX(), s.helgenZ());
            town = new CottageTerrain.Town(s.helgenX(), s.helgenZ(), helgenRadius(), hg == CottageTerrain.NO_GROUND ? s.helgenY() - 1 : hg);
            p.path = CottageTerrain.buildPath(level, s);
            p.cleared = CottageTerrain.clearCone(level, s, town);
            // stone and dirt left hanging by the terrain pass or by roads and grading: the "floating rocks" the window saw
            p.rocks = CottageTerrain.sweepRocks(level, p.chunks);
            p.view = CottageTerrain.viewshed(level, s, town);
            int[] sup = CottageTerrain.support(level, s, 3);
            d.visibility = (float) p.view.percent();
            d.pathBuilt = p.path.built();
            d.pathCells = p.path.cells();
            d.pathBreaks = p.path.discontinuities();
            d.pathGaps = p.path.airGaps();
            d.pathJoins = p.path.joinsRoad();
            d.unsupported = sup[1] - sup[0];
            d.cleared = p.cleared;
            d.report = "view " + Math.round(p.view.percent()) + "% (" + p.view.visible() + "/" + p.view.total() + ", trees " + p.view.blockedByTrees()
                    + ", terrain " + p.view.blockedByTerrain() + ", ground level " + Math.round(p.view.groundPercent()) + "%"
                    + (p.view.sample().isEmpty() ? "" : ", blockers " + p.view.sample()) + "), cleared " + p.cleared + " canopy blocks, "
                    + p.rocks + " floating rock blocks, supports " + sup[0] + "/" + sup[1]
                    + ", path " + (p.path.built() ? p.path.cells() + " cells, " + p.path.steps() + " steps, gaps " + p.path.airGaps() + ", breaks "
                    + p.path.discontinuities() + ", " + p.path.note() : "none: " + p.path.note());
            com.skycraft.Skycraft.LOGGER.info("Start cottage ready: {}", d.report);
        } catch (RuntimeException e) {
            com.skycraft.Skycraft.LOGGER.error("Start cottage preparation failed", e);
            d.report = "preparation failed: " + e;
        }
        d.prepared = true;
        d.setDirty();
        p.stage = 2;
    }

    // ------------------------------------------------------------------ the waking and the way out

    /** Puts the player in the cottage (beside the bed, facing the window) and makes the bed their respawn point. */
    public static boolean wake(ServerPlayer p) {
        Site site = ensure(p.serverLevel());
        if (site == null) return false;
        Vec3 w = site.wake();
        if (p.position().distanceToSqr(w) > 14 * 14) {
            moveTo(p, p.serverLevel(), w.x, w.y + 0.05, w.z, site.yaw(), 8f);
            p.fallDistance = 0f;
            p.setDeltaMovement(Vec3.ZERO);
        } else {
            p.setYRot(site.yaw());
        }
        // the cottage bed is where a first death returns you, until a real bed is claimed
        com.skycraft.combat.RespawnPoints.set(p, p.serverLevel().dimension(), site.bed());
        return true;
    }

    /** Teleports a player (a stand-in without a connection is moved directly). */
    static void moveTo(ServerPlayer p, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        p.teleportTo(level, x, y, z, yaw, pitch);
        if (p.level() == level && p.position().distanceToSqr(x, y, z) > 1.0) p.setPos(x, y, z);
    }

    /** Where the way out lands: the path's first cell below the porch steps. */
    public static Vec3 exit(ServerLevel level, Site site) {
        BlockPos c = site.at(1, -5, 0);
        int g = CottageTerrain.groundTop(level, c.getX(), c.getZ());
        double y = g == CottageTerrain.NO_GROUND ? site.floorY() : g + 1;
        return new Vec3(c.getX() + 0.5, y, c.getZ() + 0.5);
    }

    /** The yaw of someone leaving: the way the front faces. */
    public static float exitYaw(Site site) {
        return site.yaw();
    }

    /** True when the block position is the cottage door (either half) of this world's cottage. */
    public static boolean isDoor(ServerLevel level, BlockPos pos) {
        Data d = data(level);
        if (!d.built) return false;
        Site s = site(level);
        if (s == null) return false;
        BlockPos door = s.door();
        return pos.getX() == door.getX() && pos.getZ() == door.getZ() && (pos.getY() == door.getY() || pos.getY() == door.getY() + 1);
    }

    /** Spare helper kept for the selftest: the list of unconnected panes of the windows (should be empty). */
    public static int unconnectedPanes(ServerLevel level, Site s) {
        int bad = 0;
        int y0 = s.floorY();
        List<BlockPos> glass = new ArrayList<>();
        for (int l = 4; l <= 6; l++) {
            for (int y = y0 + 2; y <= y0 + 3; y++) {
                glass.add(s.at(l, 0, y));
                glass.add(s.at(l, 6, y));
            }
        }
        for (int d = 2; d <= 3; d++) for (int y = y0 + 2; y <= y0 + 3; y++) glass.add(s.at(0, d, y));
        for (int y = y0 + 2; y <= y0 + 3; y++) glass.add(s.at(8, 5, y));
        for (BlockPos g : glass) {
            BlockState st = level.getBlockState(g);
            if (!(st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock)) {
                bad++;
                continue;
            }
            boolean any = st.getValue(net.minecraft.world.level.block.IronBarsBlock.NORTH) || st.getValue(net.minecraft.world.level.block.IronBarsBlock.SOUTH)
                    || st.getValue(net.minecraft.world.level.block.IronBarsBlock.EAST) || st.getValue(net.minecraft.world.level.block.IronBarsBlock.WEST);
            if (!any) bad++;
        }
        return bad;
    }

    /** Iterator-free helper for tests: the interior is air above the floor. */
    static boolean interiorClear(ServerLevel level, Site s) {
        Iterator<Integer> ys = List.of(1, 2, 3).iterator();
        while (ys.hasNext()) {
            int y = ys.next();
            for (int l = 1; l <= 7; l++) {
                for (int d = 1; d <= 5; d++) {
                    BlockState st = level.getBlockState(s.at(l, d, s.floorY() + y));
                    if (st.isSolidRender(level, s.at(l, d, s.floorY() + y)) && !st.is(Blocks.BOOKSHELF)
                            && !st.is(Blocks.BARREL) && !st.is(Blocks.CRAFTING_TABLE) && !st.is(Blocks.FURNACE)) return false;
                }
            }
        }
        return true;
    }
}
