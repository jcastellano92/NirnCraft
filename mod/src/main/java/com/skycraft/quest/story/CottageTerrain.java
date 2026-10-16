package com.skycraft.quest.story;

import com.skycraft.worldplan.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The land under and around the start cottage: where the ground really is (ignoring trees), grading the pad down to
 * the surrounding terrain (cut, fill, retaining wall: nothing hangs on air), the winding path from the door to the
 * planned road, and clearing the sight cone to Helgen (trees above the sight line are topped; the forest stays
 * elsewhere). Also the measurements the QA command reports: viewshed percentage, unsupported columns, path continuity.
 * Server thread only; every chunk touched must be loaded (the caller loads them).
 */
final class CottageTerrain {
    /** Block flags: send to clients, skip neighbour shape updates (bulk edits). */
    static final int SET = 2 | 16;
    static final int NO_GROUND = Integer.MIN_VALUE;

    private CottageTerrain() {}

    // ------------------------------------------------------------------ what a block is

    static boolean isCanopy(BlockState s) {
        if (s.isAir()) return false;
        if (s.getBlock() instanceof LeavesBlock || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.getBlock() instanceof VineBlock) return true;
        var key = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (key == null) return false;
        String p = key.getPath();
        return p.contains("leaves") || p.endsWith("_log") || p.endsWith("_wood") || p.contains("vine") || p.contains("mushroom_block")
                || p.equals("mushroom_stem") || p.equals("bee_nest") || p.contains("hanging_roots") || p.contains("trunk") || p.contains("branch");
    }

    static boolean isPlant(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        return s.getBlock() instanceof BushBlock || s.getBlock() instanceof SnowLayerBlock
                || s.getBlock() instanceof com.skycraft.crafting.arcane.block.IngredientPlantBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.TallGrassBlock
                || s.getBlock() instanceof VineBlock
                || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.SNOW)
                || s.canBeReplaced() && s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    /** Air, tree, bush or ground cover: what the "ground" scan sees through. */
    static boolean isSkippable(BlockState s) {
        return s.isAir() || isCanopy(s) || isPlant(s);
    }

    /** True when a view ray is stopped by this block (leaves, logs, stone...); glass and plants are see-through. */
    static boolean blocksView(ServerLevel level, BlockPos pos, BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock || s.getBlock() instanceof net.minecraft.world.level.block.AbstractGlassBlock) return false;
        if (s.getBlock() instanceof LeavesBlock || s.is(BlockTags.LEAVES)) return true;
        return !s.getCollisionShape(level, pos).isEmpty() && !(s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                && !(s.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock)
                && !(s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock)
                && !(s.getBlock() instanceof net.minecraft.world.level.block.LanternBlock);
    }

    // ------------------------------------------------------------------ the ground

    static boolean loaded(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }

    /** y of the top natural ground block at (x, z), seeing through trees and ground cover; {@link #NO_GROUND} when unloaded. */
    static int groundTop(ServerLevel level, int x, int z) {
        if (!loaded(level, x, z)) return NO_GROUND;
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        while (y > min) {
            p.set(x, y, z);
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty()) return y;
            if (isSkippable(s)) {
                y--;
                continue;
            }
            return y;
        }
        return y;
    }

    static boolean isWater(ServerLevel level, int x, int z) {
        if (!loaded(level, x, z)) return false;
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        return !level.getFluidState(new BlockPos(x, y, z)).isEmpty();
    }

    static void set(ServerLevel level, BlockPos p, BlockState s) {
        if (level.getBlockState(p) != s) level.setBlock(p, s, SET);
    }

    /** Removes any plant, double-plant or snow layer at (x, y+1, z) and (x, y+2, z) after a support block is cleared. */
    static int stripAbovePlants(ServerLevel level, int x, int y, int z) {
        int cleared = 0;
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos up = new BlockPos(x, y + dy, z);
            BlockState us = level.getBlockState(up);
            if (!us.isAir() && (isPlant(us) || us.is(Blocks.SNOW))) {
                level.setBlock(up, Blocks.AIR.defaultBlockState(), SET);
                cleared++;
            }
        }
        return cleared;
    }

    /** Removes an entire tree (trunk and connected crown) so no half-trees or floating leaf rings ever remain. */
    static int fellTree(ServerLevel level, int cx, int cy, int cz) {
        int removed = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        List<int[]> logs = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!loaded(level, x, z)) continue;
                int g = groundTop(level, x, z);
                if (g == NO_GROUND) continue;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 2;
                for (int y = g + 1; y <= top; y++) {
                    p.set(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.is(BlockTags.LOGS)) {
                        logs.add(new int[]{x, z, g, top});
                        break;
                    }
                }
            }
        }
        if (logs.isEmpty() && loaded(level, cx, cz)) {
            int g = groundTop(level, cx, cz);
            if (g != NO_GROUND) logs.add(new int[]{cx, cz, g, level.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz) + 2});
        }
        for (int[] tr : logs) {
            int lx = tr[0], lz = tr[1];
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx * dx + dz * dz > 11) continue;
                    int x = lx + dx, z = lz + dz;
                    if (!loaded(level, x, z)) continue;
                    int g = groundTop(level, x, z);
                    if (g == NO_GROUND) continue;
                    int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 2;
                    for (int y = g + 1; y <= top; y++) {
                        p.set(x, y, z);
                        BlockState s = level.getBlockState(p);
                        if (!s.isAir() && isCanopy(s)) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), SET);
                            removed++;
                            removed += stripAbovePlants(level, x, y, z);
                        }
                    }
                }
            }
        }
        return removed;
    }

    /** Removes trees and ground cover in the column strictly above {@code fromY} up to the top of the canopy. */
    static void clearVegetation(ServerLevel level, int x, int z, int fromY, int toY) {
        if (!loaded(level, x, z)) return;
        int top = Math.max(toY, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 2);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean hitTree = false;
        for (int y = fromY + 1; y <= top; y++) {
            p.set(x, y, z);
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && (isCanopy(s) || isPlant(s))) {
                if (isCanopy(s)) hitTree = true;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), SET);
                stripAbovePlants(level, x, y, z);
            }
        }
        if (hitTree) fellTree(level, x, fromY + 1, z);
    }

    /** The surface block to put on top of a column that was graded: the natural top when it is soil-like, else grass. */
    static BlockState topFor(ServerLevel level, int x, int top, int z) {
        BlockState nat = level.getBlockState(new BlockPos(x, top, z));
        if (nat.is(BlockTags.DIRT) || nat.is(Blocks.SAND) || nat.is(Blocks.RED_SAND) || nat.is(Blocks.SNOW_BLOCK) || nat.is(Blocks.GRAVEL)
                || nat.is(BlockTags.BASE_STONE_OVERWORLD) || nat.is(Blocks.PODZOL) || nat.is(Blocks.COARSE_DIRT)) {
            if (nat.is(Blocks.DIRT_PATH) || nat.is(Blocks.FARMLAND) || nat.is(Blocks.MUD)) return Blocks.GRASS_BLOCK.defaultBlockState();
            return nat;
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    // ------------------------------------------------------------------ grading

    /** Counts of what the grading did, for the log and the QA report. */
    record GradeReport(int columns, int filled, int cut, int retaining, int margin) {
    }

    /**
     * Levels the pad (local l -3..10, d -5..8) to {@code floorY - 1}, blending to the natural ground outside it at 45
     * degrees: columns above the natural ground are filled down to it (stone-brick retaining face where the fill is
     * deeper than 2), columns below are cut, and trees and cover over the pad and its margin are removed.
     */
    static GradeReport grade(ServerLevel level, StartCottage.Site s) {
        int padTop = s.floorY() - 1;
        int maxDelta = 0;
        for (int l = -3; l <= 10; l++) {
            for (int d = -5; d <= 8; d++) {
                BlockPos c = s.at(l, d, 0);
                int n = groundTop(level, c.getX(), c.getZ());
                if (n != NO_GROUND) maxDelta = Math.max(maxDelta, Math.abs(padTop - n));
            }
        }
        int margin = Mth.clamp(maxDelta + 1, 3, 9);
        int filled = 0, cut = 0, retaining = 0, columns = 0;
        for (int l = -3 - margin; l <= 10 + margin; l++) {
            for (int d = -5 - margin; d <= 8 + margin; d++) {
                int dd = Math.max(Math.max(-3 - l, l - 10), Math.max(-5 - d, d - 8));
                dd = Math.max(0, dd);
                BlockPos c = s.at(l, d, 0);
                int x = c.getX(), z = c.getZ();
                int n = groundTop(level, x, z);
                if (n == NO_GROUND || isWater(level, x, z)) continue;
                int delta = padTop - n;
                int t = dd == 0 ? padTop : delta > 0 ? Math.max(n, padTop - dd) : Math.min(n, padTop + dd);
                columns++;
                BlockState top = topFor(level, x, n, z);
                boolean edge = dd == 0 && (l == -3 || l == 10 || d == -5 || d == 8);
                if (t > n) {
                    boolean wall = edge && t - n >= 3;
                    for (int y = n + 1; y <= t; y++) {
                        BlockState st;
                        if (y == t) st = wall ? Blocks.STONE_BRICKS.defaultBlockState() : top;
                        else st = wall ? ((x + y + z) % 5 == 0 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState()
                                : Blocks.DIRT.defaultBlockState();
                        set(level, new BlockPos(x, y, z), st);
                    }
                    if (wall) retaining++;
                    filled++;
                    clearVegetation(level, x, z, t, dd == 0 ? s.floorY() + 14 : t + 8);
                } else if (t < n) {
                    for (int y = n; y > t; y--) set(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    set(level, new BlockPos(x, t, z), top);
                    cut++;
                    clearVegetation(level, x, z, t, dd == 0 ? s.floorY() + 14 : t + 8);
                } else {
                    set(level, new BlockPos(x, t, z), top);
                    clearVegetation(level, x, z, t, dd == 0 ? s.floorY() + 14 : t + 8);
                }
            }
        }
        return new GradeReport(columns, filled, cut, retaining, margin);
    }

    /**
     * Footprint support check: of the columns under the cottage floor (l 0..8, d 0..6), how many have solid ground within
     * {@code reach} blocks under the floor (the rest hang on air). Returns {supported, total}.
     */
    static int[] support(ServerLevel level, StartCottage.Site s, int reach) {
        int ok = 0, total = 0;
        for (int l = 0; l <= 8; l++) {
            for (int d = 0; d <= 6; d++) {
                BlockPos c = s.at(l, d, 0);
                total++;
                boolean solid = false;
                for (int k = 1; k <= reach + 1 && !solid; k++) {
                    BlockPos p = new BlockPos(c.getX(), s.floorY() - k, c.getZ());
                    BlockState st = level.getBlockState(p);
                    solid = !st.isAir() && st.getFluidState().isEmpty() && !isCanopy(st) && st.isSolidRender(level, p);
                    if (st.is(BlockTags.LOGS) || st.getBlock() instanceof LeavesBlock) break;
                }
                if (solid) ok++;
            }
        }
        return new int[]{ok, total};
    }

    // ------------------------------------------------------------------ the path

    /** The painted path: its cells, the junction with the road plan and the checks. */
    record PathReport(boolean built, int cells, int steps, double roadGap, int airGaps, int discontinuities, int maxStep, int goalX, int goalZ,
                      boolean joinsRoad, String note) {
        static PathReport none(String note) {
            return new PathReport(false, 0, 0, -1, 0, 0, 0, 0, 0, false, note);
        }
    }

    private static final int[][] NB = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** The road-plan cell (x, z) nearest the start whose distance from a planned road centre line is within its band edge. */
    private static int[] roadGoal(ServerLevel level, StartCottage.Site site, int sx, int sz, int reach) {
        RoadPlan plan;
        try {
            plan = RoadPlan.get();
        } catch (RuntimeException | Error e) {
            return null;
        }
        if (plan == null || plan.segments() == 0) return null;
        RoadPlan.Hit hit = new RoadPlan.Hit();
        double hx = site.helgenX() - sx, hz = site.helgenZ() - sz;
        double hlen = Math.max(1.0, Math.hypot(hx, hz));
        double ux = hx / hlen, uz = hz / hlen;
        double best = Double.MAX_VALUE;
        int[] out = null;
        for (int dx = -reach; dx <= reach; dx += 2) {
            for (int dz = -reach; dz <= reach; dz += 2) {
                int x = sx + dx, z = sz + dz;
                if (!plan.at(x + 0.5, z + 0.5, hit)) continue;
                if (hit.distance > hit.halfWidth + 1.6f) continue;
                if (hit.kind == RoadPlan.NORMAL ? false : hit.span == RoadPlan.BRIDGE || hit.span == RoadPlan.TUNNEL) continue;
                double dist = Math.hypot(dx, dz);
                double along = dx * ux + dz * uz;
                if (along < -12) continue;
                double edgePenalty = Math.abs(hit.distance - (hit.halfWidth + 0.8f)) * 1.5;
                double score = dist - along * 0.25 + edgePenalty;
                if (score < best) {
                    best = score;
                    out = new int[]{x, z};
                }
            }
        }
        return out;
    }

    /** The planned road's distance from its centre line minus its half width at (x, z): <= 0 inside the band; NaN without a road. */
    static double roadGap(int x, int z) {
        try {
            RoadPlan plan = RoadPlan.get();
            RoadPlan.Hit hit = new RoadPlan.Hit();
            if (plan != null && plan.at(x + 0.5, z + 0.5, hit)) return hit.distance - hit.halfWidth;
        } catch (RuntimeException | Error e) {
            // no plan in this build
        }
        return Double.NaN;
    }

    /** Local-frame chunks (as a long set of chunk positions) a path from the cottage door to the road needs. */
    static java.util.Set<Long> pathChunks(ServerLevel level, StartCottage.Site s) {
        java.util.Set<Long> set = new java.util.HashSet<>();
        BlockPos st = s.at(1, -5, 0);
        int[] goal = roadGoal(level, s, st.getX(), st.getZ(), 110);
        if (goal == null) {
            double ang = Math.atan2(s.helgenZ() - st.getZ(), s.helgenX() - st.getX());
            double dist = Math.max(40.0, Math.hypot(s.helgenX() - st.getX(), s.helgenZ() - st.getZ()) - 56.0);
            goal = new int[]{st.getX() + (int) Math.round(Math.cos(ang) * dist), st.getZ() + (int) Math.round(Math.sin(ang) * dist)};
        }
        int x0 = st.getX(), z0 = st.getZ(), x1 = goal[0], z1 = goal[1];
        int minX = Math.min(x0, x1) - 24, maxX = Math.max(x0, x1) + 24, minZ = Math.min(z0, z1) - 24, maxZ = Math.max(z0, z1) + 24;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) set.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
        }
        return set;
    }

    /**
     * Paints the path from the cottage's front step to the planned road: an A* over the real ground (slope cost, a little
     * noise so it winds), a smoothed height profile (at most one block per cell, cut and fill so nothing floats),
     * dirt-path centre and coarse edges, spruce stairs on steep runs, posts and lanterns beside it, and the vegetation
     * overhead removed.
     */
    static PathReport buildPath(ServerLevel level, StartCottage.Site site) {
        BlockPos st = site.at(1, -5, 0);
        int sx = st.getX(), sz = st.getZ();
        int[] goal = roadGoal(level, site, sx, sz, 110);
        boolean roadFound = goal != null;
        if (goal == null) {
            // no planned road in reach (or no plan in this build): a trail toward Helgen's approach
            double ang = Math.atan2(site.helgenZ() - sz, site.helgenX() - sx);
            double dist = Math.min(90.0, Math.max(50.0, Math.hypot(site.helgenX() - sx, site.helgenZ() - sz) - 56.0));
            goal = new int[]{sx + (int) Math.round(Math.cos(ang) * dist), sz + (int) Math.round(Math.sin(ang) * dist)};
        }
        int gx = goal[0], gz = goal[1];
        int pad = 22;
        int minX = Math.min(sx, gx) - pad, maxX = Math.max(sx, gx) + pad, minZ = Math.min(sz, gz) - pad, maxZ = Math.max(sz, gz) + pad;
        int w = maxX - minX + 1, h = maxZ - minZ + 1;
        if (w > 260 || h > 260) return PathReport.none("path box too large: " + w + "x" + h);
        int[] ground = new int[w * h];
        boolean[] blocked = new boolean[w * h];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int x = minX + i, z = minZ + j;
                int g = groundTop(level, x, z);
                ground[i * h + j] = g;
                blocked[i * h + j] = g == NO_GROUND || isWater(level, x, z);
            }
        }
        // the pad (cottage, porch, garden, well) is not walked across, except the start cell
        for (int l = -3; l <= 10; l++) {
            for (int d = -5; d <= 8; d++) {
                BlockPos c = site.at(l, d, 0);
                int i = c.getX() - minX, j = c.getZ() - minZ;
                if (i >= 0 && j >= 0 && i < w && j < h && !(l == 1 && d == -5)) blocked[i * h + j] = true;
            }
        }
        int si = sx - minX, sj = sz - minZ, gi = gx - minX, gj = gz - minZ;
        blocked[si * h + sj] = false;
        blocked[gi * h + gj] = false;
        // A*
        double[] cost = new double[w * h];
        java.util.Arrays.fill(cost, Double.MAX_VALUE);
        int[] from = new int[w * h];
        java.util.Arrays.fill(from, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        cost[si * h + sj] = 0;
        open.add(new double[]{heur(si, sj, gi, gj), si, sj});
        boolean found = false;
        int expanded = 0;
        while (!open.isEmpty() && expanded < 400_000) {
            double[] cur = open.poll();
            int ci = (int) cur[1], cj = (int) cur[2];
            if (cur[0] - heur(ci, cj, gi, gj) > cost[ci * h + cj] + 1e-9) continue;
            if (ci == gi && cj == gj) {
                found = true;
                break;
            }
            expanded++;
            int cg = ground[ci * h + cj];
            for (int[] nb : NB) {
                int ni = ci + nb[0], nj = cj + nb[1];
                if (ni < 0 || nj < 0 || ni >= w || nj >= h || blocked[ni * h + nj]) continue;
                int ng = ground[ni * h + nj];
                int dh = Math.abs(ng - cg);
                if (dh > 5) continue;
                double step = (nb[0] != 0 && nb[1] != 0) ? 1.414 : 1.0;
                double slope = dh == 0 ? 0 : dh == 1 ? 0.9 : dh == 2 ? 4.5 : 9.0 * dh;
                // a little deterministic noise so the path meanders instead of running a ruler line
                double wob = 0.35 * (hash(minX + ni, minZ + nj) & 255) / 255.0;
                double c = cost[ci * h + cj] + step * (1 + wob) + slope;
                if (c < cost[ni * h + nj]) {
                    cost[ni * h + nj] = c;
                    from[ni * h + nj] = ci * h + cj;
                    open.add(new double[]{c + heur(ni, nj, gi, gj), ni, nj});
                }
            }
        }
        if (!found) return PathReport.none("no walkable route from the cottage to " + gx + "," + gz + " (" + expanded + " cells searched)");
        List<int[]> cells = new ArrayList<>();
        for (int at = gi * h + gj; at >= 0; at = from[at]) cells.add(new int[]{minX + at / h, minZ + at % h});
        java.util.Collections.reverse(cells);
        int n = cells.size();
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = ground[(cells.get(i)[0] - minX) * h + (cells.get(i)[1] - minZ)];
        p[0] = site.floorY() - 1;
        for (int rep = 0; rep < 2; rep++) {
            for (int i = 1; i < n; i++) p[i] = Mth.clamp(p[i], p[i - 1] - 1, p[i - 1] + 1);
            for (int i = n - 2; i >= 0; i--) p[i] = Mth.clamp(p[i], p[i + 1] - 1, p[i + 1] + 1);
            p[0] = site.floorY() - 1;
        }
        for (int i = 1; i < n; i++) p[i] = Mth.clamp(p[i], p[i - 1] - 1, p[i - 1] + 1);

        // column -> target top height (centre cells win over edge cells)
        Map<Long, int[]> target = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int x = cells.get(i)[0], z = cells.get(i)[1];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean centre = dx == 0 && dz == 0;
                    if (!centre && Math.abs(dx) + Math.abs(dz) > 1) continue; // a plus shape: 3 wide, soft corners
                    long key = BlockPos.asLong(x + dx, 0, z + dz);
                    int[] have = target.get(key);
                    if (have == null || (centre && have[1] == 0)) target.put(key, new int[]{p[i], centre ? 1 : 0});
                }
            }
        }
        int steps = 0, maxStep = 0, airGaps = 0, discontinuities = 0;
        for (Map.Entry<Long, int[]> e : target.entrySet()) {
            int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
            int t = e.getValue()[0];
            boolean centre = e.getValue()[1] == 1;
            int ng = groundTop(level, x, z);
            if (ng == NO_GROUND) continue;
            if (isWater(level, x, z)) continue;
            double bedGap = roadGap(x, z);
            if (!Double.isNaN(bedGap) && bedGap < -0.5) continue; // the road's own bed: leave it
            BlockState top = (centre || ((x * 31 + z * 17) & 3) != 0) ? Blocks.DIRT_PATH.defaultBlockState()
                    : (((x + z) & 1) == 0 ? Blocks.COARSE_DIRT : Blocks.GRAVEL).defaultBlockState();
            if (t > ng) {
                for (int y = ng + 1; y < t; y++) set(level, new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
                set(level, new BlockPos(x, t, z), Blocks.DIRT.defaultBlockState());
            } else if (t < ng) {
                for (int y = ng; y > t; y--) set(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
            clearVegetation(level, x, z, t, t + 7);
            // a path block needs air above; a block above it (leaf litter) is gone by now
            set(level, new BlockPos(x, t, z), top);
        }
        // trees overhanging the path within 2 blocks of the centre line
        for (int i = 0; i < n; i++) {
            int x = cells.get(i)[0], z = cells.get(i)[1];
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx * dx + dz * dz > 5) continue;
                    clearVegetation(level, x + dx, z + dz, p[i], p[i] + 7);
                }
            }
        }
        // steps: spruce stairs over the lower cell of a run of one-block rises
        for (int i = 0; i + 1 < n; i++) {
            int dh = p[i + 1] - p[i];
            if (dh == 0) continue;
            steps++;
            maxStep = Math.max(maxStep, Math.abs(dh));
            boolean run = (i + 2 < n && p[i + 2] - p[i + 1] == dh) || (i > 0 && p[i] - p[i - 1] == dh);
            if (!run) continue;
            int dx = cells.get(i + 1)[0] - cells.get(i)[0], dz = cells.get(i + 1)[1] - cells.get(i)[1];
            if (dx != 0 && dz != 0) continue;
            int lowI = dh > 0 ? i : i + 1, highI = dh > 0 ? i + 1 : i;
            Direction up = Direction.fromDelta(cells.get(highI)[0] - cells.get(lowI)[0], 0, cells.get(highI)[1] - cells.get(lowI)[1]);
            if (up == null) continue;
            BlockPos at = new BlockPos(cells.get(lowI)[0], p[lowI] + 1, cells.get(lowI)[1]);
            if (level.getBlockState(at).isAir()) {
                set(level, at, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, up));
            }
        }
        // posts with lanterns and edge stones
        for (int i = 4; i + 2 < n; i += 1) {
            int dx = cells.get(i + 1)[0] - cells.get(i - 1)[0], dz = cells.get(i + 1)[1] - cells.get(i - 1)[1];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) continue;
            int sideSign = ((i / 12) & 1) == 0 ? 1 : -1;
            int ox = (int) Math.round(-dz / len * 2 * sideSign), oz = (int) Math.round(dx / len * 2 * sideSign);
            int x = cells.get(i)[0] + ox, z = cells.get(i)[1] + oz;
            if (blockedAt(blocked, minX, minZ, w, h, x, z)) continue;
            int g = groundTop(level, x, z);
            if (g == NO_GROUND || isWater(level, x, z)) continue;
            if (i % 12 == 0) {
                set(level, new BlockPos(x, g + 1, z), Blocks.SPRUCE_FENCE.defaultBlockState());
                set(level, new BlockPos(x, g + 2, z), Blocks.LANTERN.defaultBlockState());
            } else if (i % 3 == 0 && (hash(x, z) & 3) == 0) {
                set(level, new BlockPos(x, g + 1, z), ((x + z) & 1) == 0 ? Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState()
                        : Blocks.COBBLESTONE_SLAB.defaultBlockState());
            }
        }
        // checks: continuity, supports under the centre cells, the junction with the road plan
        for (int i = 1; i < n; i++) {
            if (Math.max(Math.abs(cells.get(i)[0] - cells.get(i - 1)[0]), Math.abs(cells.get(i)[1] - cells.get(i - 1)[1])) > 1) discontinuities++;
            if (Math.abs(p[i] - p[i - 1]) > 1) discontinuities++;
        }
        for (int i = 0; i < n; i++) {
            int x = cells.get(i)[0], z = cells.get(i)[1];
            for (int k = 1; k <= 3; k++) {
                BlockState below = level.getBlockState(new BlockPos(x, p[i] - k, z));
                if (below.isAir() || !below.getFluidState().isEmpty()) {
                    airGaps++;
                    break;
                }
                if (below.isSolidRender(level, new BlockPos(x, p[i] - k, z))) break;
            }
        }
        double gap = roadGap(gx, gz);
        boolean joins = roadFound && !Double.isNaN(gap) && gap <= 2.0;
        return new PathReport(true, n, steps, gap, airGaps, discontinuities, maxStep, gx, gz, joins,
                roadFound ? "joins the planned road at " + gx + "," + gz : "no road plan in reach: trail of " + n + " cells toward Helgen");
    }

    private static boolean blockedAt(boolean[] blocked, int minX, int minZ, int w, int h, int x, int z) {
        int i = x - minX, j = z - minZ;
        return i < 0 || j < 0 || i >= w || j >= h || blocked[i * h + j];
    }

    private static double heur(int i, int j, int gi, int gj) {
        double dx = Math.abs(i - gi), dz = Math.abs(j - gj);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
    }

    static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    // ------------------------------------------------------------------ sight to Helgen

    /** The circle of the town the cottage must see: centre and radius. */
    record Town(int x, int z, int radius, int groundY) {
    }

    /** Viewshed result: visible points of the grid and the total, per height row. */
    record View(int visible, int total, int blockedByTrees, int blockedByTerrain, int groundVisible, int groundTotal, String sample) {
        double percent() {
            return total == 0 ? 0 : 100.0 * visible / total;
        }

        double groundPercent() {
            return groundTotal == 0 ? 0 : 100.0 * groundVisible / groundTotal;
        }
    }

    /**
     * Samples rays from the window to a grid (10-block spacing) over the town's disc at ground level and 10 above and
     * counts the ones not stopped before the town edge by trees or terrain.
     */
    static View viewshed(ServerLevel level, StartCottage.Site site, Town town) {
        Vec3 eye = site.windowEye();
        int vis = 0, total = 0, trees = 0, terrain = 0, gvis = 0, gtotal = 0;
        StringBuilder sample = new StringBuilder();
        double edge = town.radius() + 4.0; // the town's own walls and roofs are its business, not the land's
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int gx = -town.radius(); gx <= town.radius(); gx += 10) {
            for (int gz = -town.radius(); gz <= town.radius(); gz += 10) {
                if (gx * gx + gz * gz > town.radius() * town.radius()) continue;
                int tx = town.x() + gx, tz = town.z() + gz;
                int g = groundTop(level, tx, tz);
                if (g == NO_GROUND) g = town.groundY();
                for (int up = 0; up <= 10; up += 10) {
                    total++;
                    if (up == 0) gtotal++;
                    Vec3 tgt = new Vec3(tx + 0.5, g + 1.5 + up, tz + 0.5);
                    Vec3 dir = tgt.subtract(eye);
                    double len = dir.length();
                    int steps = (int) (len * 2);
                    boolean blockedHit = false, tree = false;
                    for (int k = 5; k < steps; k++) {
                        double t = k / (double) steps;
                        double px = eye.x + dir.x * t, py = eye.y + dir.y * t, pz = eye.z + dir.z * t;
                        double hx = px - town.x(), hz = pz - town.z();
                        if (hx * hx + hz * hz < edge * edge) break; // inside the town: its own business
                        bp.set(Mth.floor(px), Mth.floor(py), Mth.floor(pz));
                        if (!loaded(level, bp.getX(), bp.getZ())) continue;
                        BlockState s = level.getBlockState(bp);
                        if (blocksView(level, bp, s)) {
                            blockedHit = true;
                            tree = isCanopy(s);
                            if (sample.length() < 300) sample.append(s.getBlock().getName().getString()).append(" at ").append(bp.toShortString()).append("; ");
                            break;
                        }
                    }
                    if (!blockedHit) {
                        vis++;
                        if (up == 0) gvis++;
                    } else if (tree) trees++;
                    else terrain++;
                }
            }
        }
        return new View(vis, total, trees, terrain, gvis, gtotal, sample.toString());
    }

    /**
     * Tops the trees in the cone from the window to the town: every canopy block above the lowest sight line over its
     * column (to the nearest ground inside the town), less a block of slack, is removed. Trees below the line and
     * everything outside the cone stay. Returns the number of blocks removed.
     */
    static int clearCone(ServerLevel level, StartCottage.Site site, Town town) {
        Vec3 eye = site.windowEye();
        double ex = eye.x, ez = eye.z;
        double hx = town.x() + 0.5 - ex, hz = town.z() + 0.5 - ez;
        double dist = Math.sqrt(hx * hx + hz * hz);
        if (dist < 8) return 0;
        double ux = hx / dist, uz = hz / dist;
        double half = Math.asin(Math.min(0.95, (town.radius() + 8.0) / dist)) + Math.toRadians(5);
        double reachEnd = dist - town.radius() - 4.0; // the cone stops at the town edge
        int minX = (int) Math.floor(Math.min(ex, town.x()) - town.radius() - 8), maxX = (int) Math.ceil(Math.max(ex, town.x()) + town.radius() + 8);
        int minZ = (int) Math.floor(Math.min(ez, town.z()) - town.radius() - 8), maxZ = (int) Math.ceil(Math.max(ez, town.z()) + town.radius() + 8);
        int removed = 0;
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double vx = x + 0.5 - ex, vz = z + 0.5 - ez;
                double vlen = Math.sqrt(vx * vx + vz * vz);
                double along = vx * ux + vz * uz;
                if (along < 2.5 || along > reachEnd || vlen < 2.5) continue;
                double cos = along / vlen;
                if (Math.acos(Mth.clamp(cos, -1, 1)) > half) continue;
                int gTop = groundTop(level, x, z);
                if (gTop == NO_GROUND) continue;
                // the lowest sight line over this column: to the town's ground at four distances along the bearing
                double wx = vx / vlen, wz = vz / vlen;
                double ceiling = Double.MAX_VALUE;
                double near = Math.max(vlen + 1, dist - town.radius());
                for (int k = 0; k < 4; k++) {
                    double tt = near + k * town.radius() * 0.5;
                    int tx = (int) Math.floor(ex + wx * tt), tz = (int) Math.floor(ez + wz * tt);
                    int tg = groundTop(level, tx, tz);
                    if (tg == NO_GROUND) tg = town.groundY();
                    double y = eye.y + (tg + 1.0 - eye.y) * (vlen / tt);
                    ceiling = Math.min(ceiling, y);
                }
                ceiling -= 1.5;
                int from = (int) Math.floor(ceiling);
                // up to the top of whatever stands in the column (a tree on a ledge may be far above the ground proper)
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 2;
                boolean hasBlockingCanopy = vlen < 24.0;
                if (!hasBlockingCanopy) {
                    for (int y = from + 1; y <= top; y++) {
                        bp.set(x, y, z);
                        if (isCanopy(level.getBlockState(bp))) {
                            hasBlockingCanopy = true;
                            break;
                        }
                    }
                }
                if (hasBlockingCanopy) {
                    for (int y = gTop + 1; y <= top; y++) {
                        bp.set(x, y, z);
                        BlockState s = level.getBlockState(bp);
                        if (s.isAir()) continue;
                        if (isCanopy(s)) {
                            level.setBlock(bp, Blocks.AIR.defaultBlockState(), SET);
                            removed++;
                            removed += stripAbovePlants(level, x, y, z);
                        }
                    }
                    removed += fellTree(level, x, gTop + 1, z);
                }
                // Also sweep any plant in the column above gTop + 1 whose support below is air or canopy
                for (int y = gTop + 2; y <= top; y++) {
                    bp.set(x, y, z);
                    BlockState s = level.getBlockState(bp);
                    if (s.isAir()) continue;
                    if (isPlant(s) || s.is(Blocks.SNOW)) {
                        BlockState below = level.getBlockState(bp.below());
                        if (below.isAir() || isCanopy(below)) {
                            level.setBlock(bp, Blocks.AIR.defaultBlockState(), SET);
                            removed++;
                            removed += stripAbovePlants(level, x, y, z);
                        }
                    }
                }
            }
        }
        return removed;
    }

    /**
     * Removes stone, dirt and ore that hangs in the air over the chunks of the preparation (boulders, spires and loose
     * ore whose ground was cut away by the terrain pass, roads or the town's grading): what the window would see as
     * "floating rocks". Only loaded chunks are touched. Returns the number of blocks removed.
     */
    static int sweepRocks(ServerLevel level, java.util.Collection<Long> chunks) {
        if (chunks.isEmpty()) return 0;
        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
        for (long k : chunks) {
            minCx = Math.min(minCx, net.minecraft.world.level.ChunkPos.getX(k));
            maxCx = Math.max(maxCx, net.minecraft.world.level.ChunkPos.getX(k));
            minCz = Math.min(minCz, net.minecraft.world.level.ChunkPos.getZ(k));
            maxCz = Math.max(maxCz, net.minecraft.world.level.ChunkPos.getZ(k));
        }
        java.util.Set<Long> set = new java.util.HashSet<>(chunks);
        return com.skycraft.world.RockSweep.sweep(level, minCx * 16, minCz * 16, maxCx * 16 + 15, maxCz * 16 + 15, 0,
                minCx * 16, minCz * 16, maxCx * 16 + 15, maxCz * 16 + 15, 48,
                (cx, cz) -> set.contains(net.minecraft.world.level.ChunkPos.asLong(cx, cz)) && level.getChunkSource().getChunkNow(cx, cz) != null,
                p -> com.skycraft.dig.PlacedBlocks.isPlayerPlaced(level, p));
    }

    /** One column of the cone as the clearing sees it, for the QA command. */
    static String debugColumn(ServerLevel level, StartCottage.Site site, Town town, int x, int z) {
        Vec3 eye = site.windowEye();
        double hx = town.x() + 0.5 - eye.x, hz = town.z() + 0.5 - eye.z;
        double dist = Math.sqrt(hx * hx + hz * hz);
        double ux = hx / dist, uz = hz / dist;
        double half = Math.asin(Math.min(0.95, (town.radius() + 8.0) / dist)) + Math.toRadians(5);
        double vx = x + 0.5 - eye.x, vz = z + 0.5 - eye.z, vlen = Math.sqrt(vx * vx + vz * vz), along = vx * ux + vz * uz;
        double ang = Math.toDegrees(Math.acos(Mth.clamp(along / vlen, -1, 1)));
        int g = groundTop(level, x, z);
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        double wx = vx / vlen, wz = vz / vlen, ceiling = Double.MAX_VALUE;
        double near = Math.max(vlen + 1, dist - town.radius());
        for (int k = 0; k < 4; k++) {
            double tt = near + k * town.radius() * 0.5;
            int tg = groundTop(level, (int) Math.floor(eye.x + wx * tt), (int) Math.floor(eye.z + wz * tt));
            if (tg == NO_GROUND) tg = town.groundY();
            ceiling = Math.min(ceiling, eye.y + (tg + 1.0 - eye.y) * (vlen / tt));
        }
        return "column " + x + "," + z + ": groundTop " + g + ", surface top " + top + ", along " + (int) along + "/" + (int) (dist - town.radius() - 4)
                + ", angle " + (int) ang + "/" + (int) Math.toDegrees(half) + ", sight line " + String.format("%.1f", ceiling) + ", eye y "
                + String.format("%.1f", eye.y) + ", town ground " + town.groundY();
    }

    /** Fixes up fences, panes and walls (their connections) over a box once everything is placed. */
    static void shapes(ServerLevel level, BlockPos a, BlockPos b) {
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
        int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    p.set(x, y, z);
                    BlockState s = level.getBlockState(p);
                    Block b0 = s.getBlock();
                    if (b0 instanceof net.minecraft.world.level.block.IronBarsBlock || b0 instanceof net.minecraft.world.level.block.FenceBlock
                            || b0 instanceof net.minecraft.world.level.block.WallBlock) {
                        BlockState u = Block.updateFromNeighbourShapes(s, level, p);
                        if (u != s) level.setBlock(p, u, SET);
                    }
                }
            }
        }
    }
}
