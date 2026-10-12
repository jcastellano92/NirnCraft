package com.skycraft.scatter;

import com.skycraft.Skycraft;
import com.skycraft.explore.MinorSites;
import com.skycraft.explore.SiteKind;
import com.skycraft.explore.build.Palette;
import com.skycraft.explore.build.SiteBuilder;
import com.skycraft.scatter.ScatterRules.Group;
import com.skycraft.scatter.ScatterRules.Kind;
import com.skycraft.scatter.Terrain.Ground;
import com.skycraft.scatter.Terrain.RockSet;
import com.skycraft.scatter.ZoneModel.Info;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.world.LocationTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The placement engine: given a chunk being decorated, rolls the zone's rule table and builds the props that pass
 * their ground, slope, water and clearance checks. Everything is derived from the world seed, the map and the chunk
 * position, so the same chunk always gets the same clutter whatever order chunks are generated in.
 *
 * <p>Safety: it only ever reads and writes inside the 3x3 chunks a feature may use (props are centred in the chunk's
 * core), stays off settlement footprints and any chunk with a surface structure, and sets items and armour stands
 * through NBT ({@link SiteBuilder}), never through live chunk lookups.</p>
 */
public final class Scatter {
    static final long SALT = 0x5CA77E12B0A7L;
    /** Centre margin inside the chunk: props reach 3-4 blocks, so they stay within one chunk of their own. */
    private static final int MARGIN = 3;

    /** Kinds that mark a place somebody comes to (not just rubbish): they get a worn trail towards the road. */
    private static final java.util.Set<String> TRAILED = java.util.Set.of("camp_leavings", "fire_pit_cold", "bedroll",
            "lean_to", "cemetery", "grave", "cairn", "drying_rack", "water_trough", "fish_rack");

    /** Reason the last failed {@link #dress} gave (diagnostics). */
    static volatile String LAST_WHY = "";

    private Scatter() {}

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Builds the scatter of one chunk. Returns the number of props placed. */
    public static int generate(WorldGenLevel level, ChunkPos cp) {
        double density = ScatterConfig.density();
        if (density <= 0) return 0;
        long seed = level.getSeed();
        Info info = ZoneModel.chunk(seed, cp.x, cp.z);
        ScatterZone zone = info.zone();
        if (zone == ScatterZone.NONE) return 0;
        int flags = structureFlags(level, cp);
        if ((flags & 1) != 0) return 0;
        if ((flags & 2) != 0 && zone != ScatterZone.URBAN_FRINGE) zone = ScatterZone.DUNGEON_ADJACENT;
        ScatterRules rules = ScatterRules.get();
        List<Group> groups = rules.zones.get(zone);
        if (groups == null || groups.isEmpty()) return 0;
        RandomSource rnd = RandomSource.create(mix(seed ^ mix(((long) cp.x << 32) ^ (cp.z & 0xFFFFFFFFL) ^ SALT)));
        Run run = new Run(level, cp, info, zone, rnd, rules);
        ScatterStats.rolled(cp.x, cp.z, zone);
        for (Group g : groups) {
            double mult = switch (g.scale) {
                case "human" -> 0.35 + 1.3 * info.humanity();
                case "wild" -> 1.3 - 0.9 * info.humanity();
                case "remote" -> 0.7 + 0.9 * info.remote();
                default -> 1.0;
            };
            double expect = g.density * mult * density;
            int n = (int) expect;
            if (rnd.nextDouble() < expect - n) n++;
            for (int i = 0; i < n && run.count < rules.caps.chunk; i++) {
                Kind k = run.pick(g);
                if (k != null) run.place(k, 0, 0, -1);
            }
        }
        return run.count;
    }

    /**
     * API for the road module's roadside dressing (and anything else that wants one prop at a spot): builds the kind
     * {@code kindId} centred on (x, z) of a level that is ready for writes, subject to the same checks. Returns
     * whether it was built. The level must be generated at least one chunk around the point.
     */
    public static boolean dress(WorldGenLevel level, String kindId, int x, int z, long salt) {
        ScatterRules rules = ScatterRules.get();
        Kind k = rules.kinds.get(kindId);
        if (k == null) return false;
        long seed = level.getSeed();
        Info info = ZoneModel.at(seed, x, z);
        RandomSource rnd = RandomSource.create(mix(seed ^ mix(salt ^ ((long) x << 32) ^ (z & 0xFFFFFFFFL))));
        Run run = new Run(level, new ChunkPos(x >> 4, z >> 4), info, info.zone(), rnd, rules);
        for (int attempt = 0; attempt < 4; attempt++) {
            int jx = attempt == 0 ? 0 : rnd.nextInt(9) - 4, jz = attempt == 0 ? 0 : rnd.nextInt(9) - 4;
            if (run.placeAt(k, x + jx, z + jz)) return true;
        }
        LAST_WHY = run.why;
        return false;
    }

    /** 1 = a surface structure (town, camp, fort) has a piece over the chunk; 2 = a dungeon structure does. */
    static int structureFlags(WorldGenLevel level, ChunkPos chunk) {
        ChunkAccess access = level.getChunk(chunk.x, chunk.z);
        var refs = access.getAllReferences();
        if (refs.isEmpty()) return 0;
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        int flags = 0;
        for (var e : refs.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Structure s = e.getKey();
            Holder<Structure> h = registry.wrapAsHolder(s);
            if (h.is(LocationTags.TOWNS)) return 1;
            if (s.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) return 1;
            if (h.is(LocationTags.DUNGEONS)) {
                // only the surface above a dungeon's start (its entrance end) is "dungeon-adjacent", not its whole extent
                for (long start : e.getValue()) {
                    if (Math.abs(ChunkPos.getX(start) - chunk.x) <= 3 && Math.abs(ChunkPos.getZ(start) - chunk.z) <= 3) flags |= 2;
                }
            }
        }
        return flags;
    }

    // ------------------------------------------------------------------ one chunk's run

    private static final class Run {
        final WorldGenLevel level;
        final ChunkPos cp;
        final Info info;
        final ScatterZone zone;
        final RandomSource r;
        final ScatterRules rules;
        final long seed;
        final Palette palette;
        final List<MinorSites.Site> sites;
        final List<int[]> centres = new ArrayList<>();
        final Map<String, Integer> perKind = new HashMap<>();
        final MapSample ms = new MapSample();
        int count;
        int isolated;
        /** Why the last {@link #build} said no (diagnostics for {@code dress}). */
        String why = "";

        Run(WorldGenLevel level, ChunkPos cp, Info info, ScatterZone zone, RandomSource r, ScatterRules rules) {
            this.level = level;
            this.cp = cp;
            this.info = info;
            this.zone = zone;
            this.r = r;
            this.rules = rules;
            this.seed = level.getSeed();
            this.palette = Palette.of(info.province(), info.archetype());
            this.sites = MinorSites.within(seed, cp.x * 16 + 8, cp.z * 16 + 8, 60);
        }

        /** Weighted pick of a kind of the group that may stand in this province and height. */
        @Nullable
        Kind pick(Group g) {
            double total = 0;
            for (int i = 0; i < g.kinds.size(); i++) if (allowed(g.kinds.get(i))) total += g.weights.get(i);
            if (total <= 0) return null;
            double roll = r.nextDouble() * total;
            for (int i = 0; i < g.kinds.size(); i++) {
                Kind k = g.kinds.get(i);
                if (!allowed(k)) continue;
                roll -= g.weights.get(i);
                if (roll < 0) return k;
            }
            return null;
        }

        boolean allowed(Kind k) {
            if (!k.provinces.isEmpty() && !k.provinces.contains(info.province())) return false;
            if (k.denyProvinces.contains(info.province())) return false;
            if (info.elevation() < k.minElev || info.elevation() > k.maxElev) return false;
            return perKind.getOrDefault(k.id, 0) < rules.caps.perKind;
        }

        /**
         * Places kind {@code k}: at a random spot of the chunk's core, or within {@code radius} of (ox, oz) when
         * {@code radius >= 0} (members of a cluster).
         */
        boolean place(Kind k, int ox, int oz, int radius) {
            if (k.cluster != null) return placeCluster(k);
            for (int attempt = 0; attempt < rules.caps.attempts; attempt++) {
                int x, z;
                if (radius < 0) {
                    x = cp.x * 16 + MARGIN + r.nextInt(16 - 2 * MARGIN);
                    z = cp.z * 16 + MARGIN + r.nextInt(16 - 2 * MARGIN);
                } else {
                    x = ox + r.nextInt(radius * 2 + 1) - radius;
                    z = oz + r.nextInt(radius * 2 + 1) - radius;
                    // members stay within the same chunk neighbourhood the engine owns
                    x = Math.max(cp.x * 16 + 1, Math.min(cp.x * 16 + 14, x));
                    z = Math.max(cp.z * 16 + 1, Math.min(cp.z * 16 + 14, z));
                }
                if (build(k, x, z, true)) return true;
            }
            return false;
        }

        /** One kind centred on (x, z): a cluster spreads its members around it. */
        boolean placeAt(Kind k, int x, int z) {
            if (k.cluster == null) return build(k, x, z, false);
            int n = k.cluster.min + r.nextInt(Math.max(1, k.cluster.max - k.cluster.min + 1));
            int placed = 0;
            for (int i = 0; i < n && count < rules.caps.chunk; i++) {
                Kind m = rules.kinds.get(k.cluster.members.get(r.nextInt(k.cluster.members.size())));
                if (m != null && m.cluster == null && place(m, x, z, Math.min(k.cluster.radius, 6))) placed++;
            }
            return placed > 0;
        }

        boolean placeCluster(Kind k) {
            ScatterRules.Cluster c = k.cluster;
            if (c.members.isEmpty()) return false;
            int cx = cp.x * 16 + 6 + r.nextInt(5), cz = cp.z * 16 + 6 + r.nextInt(5);
            int n = c.min + r.nextInt(Math.max(1, c.max - c.min + 1));
            int placed = 0;
            for (int i = 0; i < n && count < rules.caps.chunk; i++) {
                Kind m = rules.kinds.get(c.members.get(r.nextInt(c.members.size())));
                if (m == null || m.cluster != null) continue;
                if (perKind.getOrDefault(m.id, 0) >= rules.caps.perKind + 2) continue;
                if (place(m, cx, cz, Math.min(c.radius, 6))) placed++;
            }
            if (placed > 0) ScatterStats.cluster(zone, k.id);
            return placed > 0;
        }

        /**
         * A short worn path from a camp or shrine towards the nearest road corridor of the map (within 36 blocks), laid
         * as dirt path where the ground is soft; stays inside the 3x3 chunks the feature owns.
         */
        void trail(SiteBuilder b, int x, int z) {
            final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
            MapSample t = new MapSample();
            int best = -1, bestDist = 999;
            for (int d = 0; d < dirs.length; d++) {
                for (int dist = 6; dist <= 36 && dist < bestDist; dist += 6) {
                    TamrielMap.sample(x + dirs[d][0] * dist, z + dirs[d][1] * dist, t);
                    if (t.road >= 0.6f) {
                        best = d;
                        bestDist = dist;
                        break;
                    }
                }
            }
            if (best < 0) return;
            int minX = cp.x * 16 - 14, maxX = cp.x * 16 + 29, minZ = cp.z * 16 - 14, maxZ = cp.z * 16 + 29;
            int len = Math.min(bestDist, 26);
            int wob = 0;
            BlockState path = net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState();
            for (int i = 2; i <= len; i++) {
                if (i % 5 == 0) wob = r.nextInt(3) - 1;
                int px = x + dirs[best][0] * i + (dirs[best][0] == 0 ? wob : 0);
                int pz = z + dirs[best][1] * i + (dirs[best][1] == 0 ? wob : 0);
                if (px < minX || px > maxX || pz < minZ || pz > maxZ) break;
                int gy = b.groundAt(px, pz);
                BlockPos p = new BlockPos(px, gy, pz);
                BlockState gs = level.getBlockState(p);
                if (!gs.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) && !gs.is(net.minecraft.world.level.block.Blocks.DIRT)
                        && !gs.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT) && !gs.is(net.minecraft.world.level.block.Blocks.PODZOL)) continue;
                BlockState up = level.getBlockState(p.above());
                if (!up.isAir() && !up.canBeReplaced()) continue;
                if (!up.isAir()) level.setBlock(p.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(p, path, 2);
            }
        }

        /** Checks and builds {@code k} centred on (x, z). */
        boolean fail(String reason) {
            why = reason;
            return false;
        }

        boolean build(Kind k, int x, int z, boolean spacing) {
            if (count >= rules.caps.chunk) return fail("chunk cap");
            if (k.isolated && isolated >= rules.caps.isolated) return fail("isolated cap");
            int clear = spacing ? k.radius : 0;
            // clearance from other props of this chunk
            for (int[] c : centres) {
                int dx = c[0] - x, dz = c[1] - z;
                int need = Math.max(k.radius, c[2]);
                if (dx * dx + dz * dz < need * need) return fail("clearance");
            }
            // settlement and structure footprints, minor sites
            if (!Places.overlapping(x - 3, z - 3, x + 3, z + 3).isEmpty()) return fail("place footprint");
            for (MinorSites.Site s : sites) {
                int need = s.kind().radius + 4 + clear / 2;
                if (s.distSqr(x, z) < (long) need * need) return fail("minor site");
            }
            TamrielMap.sample(x, z, ms);
            if (ms.coastDistance < -1f || (!k.wet && ms.inWater())) return fail("map water");
            if (ms.elevation < k.minElev || ms.elevation > k.maxElev) return fail("elevation " + (int) ms.elevation);
            // human traces need a reason to be there: a road, a settlement, a ruin or a dungeon mouth
            if (k.human && !k.wet && zone != ScatterZone.RUIN_ADJACENT && zone != ScatterZone.DUNGEON_ADJACENT
                    && zone != ScatterZone.SHORE && ms.road < 0.3f && info.settleEdge() > 70) {
                return fail("no context");
            }
            if (k.waterMin >= 0 || k.waterMax >= 0) {
                int wd = ZoneModel.waterDistance(ms);
                if (k.waterMin >= 0 && wd < k.waterMin) return fail("water too near " + wd);
                if (k.waterMax >= 0 && wd > k.waterMax) return fail("water too far " + wd);
            }
            int facing = r.nextInt(4);
            MinorSites.Site fake = new MinorSites.Site(x >> 4, z >> 4, x, z, k.wet ? SiteKind.FISHING_DOCK : SiteKind.ABANDONED_CAMP,
                    r.nextLong(), facing, null, info.regionId(), info.province());
            SiteBuilder b = new SiteBuilder(level, r, fake, palette);
            int g = b.groundAt(x, z);
            BlockPos col = new BlockPos(x, g, z);
            BlockState gs = level.getBlockState(col);
            boolean fluid = !gs.getFluidState().isEmpty() || !level.getFluidState(col.above()).isEmpty();
            if (fluid && !k.wet) return fail("fluid");
            if (!k.wet) {
                if (Terrain.isPath(gs)) return fail("path");
                // never stand a prop on a road's coarse dirt or gravel next to a path
                for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                    int h = b.groundAt(x + o[0], z + o[1]);
                    if (Terrain.isPath(level.getBlockState(new BlockPos(x + o[0], h, z + o[1])))) return fail("near path");
                }
            }
            Ground ground = Terrain.ground(fluid ? level.getBlockState(col.below()) : gs);
            if (!k.ground.contains(ground)) return fail("ground " + ground);
            if (!k.wet) {
                int slope = 0;
                for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                    slope = Math.max(slope, Math.abs(b.groundAt(x + o[0], z + o[1]) - g));
                }
                if (slope < k.minSlope || slope > k.maxSlope) return fail("slope " + slope);
                if (!b.free(0, 0, 0)) return fail("blocked");
            }
            b.y = g + 1;
            Brush br = new Brush(b, RockSet.of(ground, gs));
            boolean ok;
            try {
                ok = k.builder != null ? Builders.build(k.builder, br, k) : Props.build(br, k);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.debug("Skycraft scatter: {} failed at {} {}", k.id, x, z, e);
                return fail("exception");
            }
            if (!ok) return fail("builder refused");
            if (k.human && !k.wet && ms.road < 0.6f && TRAILED.contains(k.id)) trail(b, x, z);
            centres.add(new int[]{x, z, k.radius});
            count += Math.max(1, k.items);
            if (k.isolated) isolated++;
            perKind.merge(k.id, 1, Integer::sum);
            ScatterStats.placed(cp.x, cp.z, zone, k, x, z, info.humanity());
            return true;
        }
    }
}
