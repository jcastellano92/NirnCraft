package com.skycraft.roads;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides where a new road gets its roadside features: signposts where it leaves each settlement and where it meets
 * an existing road (junction), and lantern posts at regular intervals.
 */
final class RoadLayout {
    /** Signposts stand this far to the side of the road center. */
    private static final int SIGN_OFFSET = 3;
    private static final int JUNCTION_OFFSET = 4;
    private static final int LANTERN_OFFSET = 3;
    /** Exit signposts closer than this to an existing one are merged into it. */
    private static final int MERGE_RADIUS = 12;
    /** No new junction signpost within this distance of another signpost. */
    private static final int JUNCTION_SPACING = 48;
    private static final int MAX_JUNCTIONS = 3;
    /** Another road counts as "met" within this distance. */
    private static final int MEET_DIST = 3;
    private static final int MILESTONE_SPACING = 640;
    /** Lantern posts stand within this distance (along the road) of its ends. */
    private static final int LANTERN_REACH = 280;
    private static final int SHRINE_SPACING = 1800;
    private static final int SHRINE_OFFSET = 7;

    private RoadLayout() {}

    /** Roads taken from the plan have a wider painted bed (highways 5 + edge stones): posts stand one block further out. */
    private static int off(Road road, int base) {
        return road.plan ? base + 1 : base;
    }

    /**
     * Must be called BEFORE the new road is indexed (so junction detection only sees the other roads).
     * Adds the features to {@code data} and returns the chunks whose paving must be (re)done.
     */
    static LongOpenHashSet layout(RoadsData data, Road road) {
        LongOpenHashSet touched = new LongOpenHashSet();
        int n = road.size();
        if (n < 4) return touched;
        boolean[] inZone = new boolean[n];
        for (int i = 0; i < n; i++) inZone[i] = data.noRoadZoneAt(road.xs[i], road.zs[i]) != null;
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (!inZone[i]) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return touched; // the whole road lies inside settlements

        List<RoadFeature> created = new ArrayList<>();
        if (road.isTrail()) {
            if (RoadsConfig.SIGNPOSTS.get()) trailSign(data, road, inZone, created, touched);
            Dressing.layout(data, road, inZone, created, touched);
            return touched;
        }
        if (road.kind <= Road.ROAD) milestonesAndShrines(data, road, inZone, created, touched);
        if (RoadsConfig.SIGNPOSTS.get()) {
            junctions(data, road, inZone, first, last, created, touched);
            int ia = Math.min(first + 2, n - 1);
            int ib = Math.max(last - 2, 0);
            if (ia < ib) {
                exitSign(data, road, ia, +1, road.b, created, touched);
                exitSign(data, road, ib, -1, road.a, created, touched);
            }
        }
        int spacing = RoadsConfig.LANTERN_SPACING.get();
        // lanterns light highways and roads; lanes to farms stay dark
        if (RoadsConfig.LANTERNS.get() && spacing > 0 && road.kind <= Road.ROAD) {
            int step = Math.max(4, (int) Math.round(spacing / RoadPlanner.SPACING));
            int side = 1;
            for (int i = 2; i < n - 2; i += step) {
                // none inside towns or dungeon sites: the road is not paved there and the place has its own lights
                if (inZone[i] || road.flags[i] != 0) continue;
                // lit only on the approaches to towns, like the lamp posts outside Skyrim's cities
                if (road.cum[i] > LANTERN_REACH && road.length() - road.cum[i] > LANTERN_REACH) continue;
                double[] r = right(road, i);
                int x = (int) Math.round(road.xs[i] + r[0] * off(road, LANTERN_OFFSET) * side);
                int z = (int) Math.round(road.zs[i] + r[1] * off(road, LANTERN_OFFSET) * side);
                side = -side;
                if (data.noRoadZoneAt(x, z) != null) continue;
                if (data.signpostNear(x, z, 8) == null && !near(created, x, z, 8)) {
                    touched.add(data.addFeature(new RoadFeature(RoadFeature.LANTERN, x, z, road.id)));
                }
            }
        }
        Dressing.layout(data, road, inZone, created, touched);
        return touched;
    }

    /** Signpost at point {@code i} pointing along the road (direction {@code dir}) to settlement {@code dest}. */
    private static void exitSign(RoadsData data, Road road, int i, int dir, int dest, List<RoadFeature> created,
                                 LongOpenHashSet touched) {
        double[] r = right(road, i);
        // stand on the traveller's right when leaving the settlement
        int x = (int) Math.round(road.xs[i] + r[0] * off(road, SIGN_OFFSET) * dir);
        int z = (int) Math.round(road.zs[i] + r[1] * off(road, SIGN_OFFSET) * dir);
        RoadFeature.Dest d = dest(road, i, dir, dest);
        RoadFeature existing = data.signpostNear(x, z, MERGE_RADIUS);
        if (existing != null) {
            if (!existing.hasDest(dest) && existing.dests.size() < RoadFeature.MAX_DESTINATIONS) {
                existing.dests.add(d);
                touched.add(ChunkPos.asLong(existing.x >> 4, existing.z >> 4));
                data.setDirty();
            }
            return;
        }
        RoadFeature f = new RoadFeature(RoadFeature.SIGNPOST, x, z, road.id);
        f.dests.add(d);
        created.add(f);
        touched.add(data.addFeature(f));
    }

    private static RoadFeature.Dest dest(Road road, int i, int dir, int settlement) {
        int j = road.clamp(i + dir * 6);
        float dx = road.xs[j] - road.xs[i];
        float dz = road.zs[j] - road.zs[i];
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3f) {
            dx = dir;
            dz = 0;
            len = 1;
        }
        return new RoadFeature.Dest(settlement, Math.round(road.distanceTo(i, settlement)), dx / len, dz / len, road.name);
    }

    /** A milestone every {@link #MILESTONE_SPACING} blocks, and on highways a wayside shrine every {@link #SHRINE_SPACING}. */
    private static void milestonesAndShrines(RoadsData data, Road road, boolean[] inZone, List<RoadFeature> created,
                                             LongOpenHashSet touched) {
        float len = road.length();
        if (len < MILESTONE_SPACING) return;
        int nextMile = MILESTONE_SPACING, nextShrine = SHRINE_SPACING / 2;
        int side = 1;
        for (int i = 4; i < road.size() - 4; i++) {
            if (inZone[i] || road.flags[i] != 0) continue;
            float at = road.cum[i];
            if (at >= nextMile && len - at > MILESTONE_SPACING / 3f) {
                nextMile += MILESTONE_SPACING;
                double[] r = right(road, i);
                int x = (int) Math.round(road.xs[i] + r[0] * off(road, SIGN_OFFSET) * side);
                int z = (int) Math.round(road.zs[i] + r[1] * off(road, SIGN_OFFSET) * side);
                side = -side;
                if (data.noRoadZoneAt(x, z) != null || data.signpostNear(x, z, 24) != null || near(created, x, z, 24)) continue;
                RoadFeature f = new RoadFeature(RoadFeature.MILESTONE, x, z, road.id);
                f.dests.add(dest(road, i, -1, road.a));
                f.dests.add(dest(road, i, +1, road.b));
                created.add(f);
                touched.add(data.addFeature(f));
            }
            if (road.kind == Road.HIGHWAY && at >= nextShrine && len - at > 200) {
                nextShrine += SHRINE_SPACING;
                int divine = Wayshrines.divineFor(road.xs[i], road.zs[i]);
                if (divine < 0) continue;
                double[] r = right(road, i);
                int s = (i & 1) == 0 ? 1 : -1;
                int x = (int) Math.round(road.xs[i] + r[0] * SHRINE_OFFSET * s);
                int z = (int) Math.round(road.zs[i] + r[1] * SHRINE_OFFSET * s);
                if (data.noRoadZoneAt(x, z) != null || near(created, x, z, 16)) continue;
                RoadFeature f = new RoadFeature(RoadFeature.SHRINE, x, z, road.id);
                f.variant = divine;
                // the shrine faces the road
                f.dests.add(new RoadFeature.Dest(road.a, 0, (float) -r[0] * s, (float) -r[1] * s));
                created.add(f);
                touched.add(data.addFeature(f));
            }
        }
    }

    /** The signpost where a trail leaves its road: to the trail's destination, and both ways along the road. */
    private static void trailSign(RoadsData data, Road road, boolean[] inZone, List<RoadFeature> created, LongOpenHashSet touched) {
        int n = road.size();
        int at = Math.max(0, n - 5); // a few blocks up the trail from the road it joins
        if (inZone[at]) return;
        double[] r = right(road, at);
        int x = (int) Math.round(road.xs[at] + r[0] * 2);
        int z = (int) Math.round(road.zs[at] + r[1] * 2);
        if (data.noRoadZoneAt(x, z) != null) return;
        RoadFeature existing = data.signpostNear(x, z, 10);
        RoadFeature.Dest toSite = dest(road, at, -1, road.a);
        if (existing != null) {
            if (!existing.hasDest(road.a) && existing.dests.size() < RoadFeature.MAX_DESTINATIONS) {
                existing.dests.add(toSite);
                touched.add(ChunkPos.asLong(existing.x >> 4, existing.z >> 4));
                data.setDirty();
            }
            return;
        }
        RoadFeature f = new RoadFeature(RoadFeature.SIGNPOST, x, z, road.id);
        f.dests.add(toSite);
        long meet = nearestOther(data, road.xs[n - 1], road.zs[n - 1]);
        if (meet != -1) {
            Road other = data.road(RoadsData.entryRoad(meet));
            if (other != null) {
                int oi = other.clamp(RoadsData.entryIndex(meet));
                addDest(f, dest(other, oi, -1, other.a));
                addDest(f, dest(other, oi, +1, other.b));
            }
        }
        created.add(f);
        touched.add(data.addFeature(f));
    }

    /** Signposts where the new road joins or leaves an existing road outside settlements. */
    private static void junctions(RoadsData data, Road road, boolean[] inZone, int first, int last,
                                  List<RoadFeature> created, LongOpenHashSet touched) {
        int made = 0;
        boolean state = false;
        int streak = 0;
        long lastMeet = -1;
        for (int i = first; i <= last && made < MAX_JUNCTIONS; i++) {
            long meet = inZone[i] ? -1 : nearestOther(data, road.xs[i], road.zs[i]);
            boolean now = meet != -1;
            if (now) lastMeet = meet;
            if (now == state) {
                streak = 0;
                continue;
            }
            if (++streak < 3) continue; // debounce
            state = now;
            streak = 0;
            if (lastMeet == -1) continue;
            Road other = data.road(RoadsData.entryRoad(lastMeet));
            if (other == null) continue;
            int oi = other.clamp(RoadsData.entryIndex(lastMeet));
            int at = state ? i - 2 : i - 3; // the point where the roads actually meet / part
            at = Math.max(first, Math.min(last, at));
            double[] r = right(road, at);
            // put the post on the side away from the other road
            double ox = other.xs[oi] - road.xs[at], oz = other.zs[oi] - road.zs[at];
            int side = ox * r[0] + oz * r[1] > 0 ? -1 : 1;
            int x = (int) Math.round(road.xs[at] + r[0] * off(road, JUNCTION_OFFSET) * side);
            int z = (int) Math.round(road.zs[at] + r[1] * off(road, JUNCTION_OFFSET) * side);
            if (data.signpostNear(x, z, JUNCTION_SPACING) != null || near(created, x, z, JUNCTION_SPACING)) continue;

            RoadFeature f = new RoadFeature(RoadFeature.SIGNPOST, x, z, road.id);
            addDest(f, dest(road, at, +1, road.b));
            addDest(f, dest(other, oi, -1, other.a));
            addDest(f, dest(other, oi, +1, other.b));
            addDest(f, dest(road, at, -1, road.a));
            if (f.dests.size() < 2) continue;
            created.add(f);
            touched.add(data.addFeature(f));
            made++;
            // a lantern post a few blocks further along, on the same verge, lights the junction
            int li = Math.max(first, Math.min(last, at + 3));
            double[] lr = right(road, li);
            int lx = (int) Math.round(road.xs[li] + lr[0] * off(road, JUNCTION_OFFSET) * side);
            int lz = (int) Math.round(road.zs[li] + lr[1] * off(road, JUNCTION_OFFSET) * side);
            if (RoadsConfig.LANTERNS.get() && data.noRoadZoneAt(lx, lz) == null && !near(created, lx, lz, 3)
                    && !inZone[li] && road.flags[li] == 0) {
                created.add(new RoadFeature(RoadFeature.LANTERN, lx, lz, road.id));
                touched.add(data.addFeature(created.get(created.size() - 1)));
            }
        }
    }

    private static void addDest(RoadFeature f, RoadFeature.Dest d) {
        if (f.dests.size() >= RoadFeature.MAX_DESTINATIONS || f.hasDest(d.settlement())) return;
        f.dests.add(d);
    }

    /** Nearest point of another (already indexed) road within {@link #MEET_DIST}, encoded, or -1. */
    private static long nearestOther(RoadsData data, int x, int z) {
        LongArrayList entries = data.chunkIndex.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (entries == null) return -1;
        long best = -1;
        int bestD = MEET_DIST * MEET_DIST + 1;
        for (int k = 0; k < entries.size(); k++) {
            long e = entries.getLong(k);
            Road r = data.road(RoadsData.entryRoad(e));
            if (r == null) continue;
            int idx = RoadsData.entryIndex(e);
            if (idx < 0 || idx >= r.size()) continue;
            int dx = r.xs[idx] - x, dz = r.zs[idx] - z;
            int d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean near(List<RoadFeature> list, int x, int z, int radius) {
        for (RoadFeature f : list) {
            int dx = f.x - x, dz = f.z - z;
            if (dx * dx + dz * dz <= radius * radius) return true;
        }
        return false;
    }

    /** Unit vector to the right of the road's heading at point {@code i} (heading north -> east). */
    static double[] right(Road road, int i) {
        int a = road.clamp(i - 2), b = road.clamp(i + 2);
        double tx = road.xs[b] - road.xs[a];
        double tz = road.zs[b] - road.zs[a];
        double len = Math.sqrt(tx * tx + tz * tz);
        if (len < 1e-6) return new double[]{1, 0};
        tx /= len;
        tz /= len;
        return new double[]{-tz, tx};
    }
}
