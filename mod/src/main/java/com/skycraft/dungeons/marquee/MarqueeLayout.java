package com.skycraft.dungeons.marquee;

import com.skycraft.Skycraft;
import com.skycraft.dungeons.world.DungeonPiece;
import com.skycraft.dungeons.world.Layout;
import com.skycraft.dungeons.world.Portals;
import com.skycraft.dungeons.world.Site;
import com.skycraft.dungeons.world.Special;
import com.skycraft.dungeons.world.Theme;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link MarqueePlans.Plan} into structure pieces: the same piece kinds the procedural {@link Layout} makes
 * (tunnel, portal, rooms on the 15-block cell grid, corridors), so the analyzer, the corridor and tunnel builders and
 * the save format all work unchanged. Rooms are painted by {@link MarqueeBuilder} (the piece's special is
 * {@link Special#MARQUEE}); the piece's {@code flavor} stores the direction the entrance lies in and {@code param}
 * the plan's hash.
 */
final class MarqueeLayout {
    static final int CELL = Layout.CELL;

    private MarqueeLayout() {}

    /** Local side letter for a cell step: e (+u), w (-u), s (+v, towards the entrance), n (-v). */
    static char side(int di, int dj) {
        return di > 0 ? 'e' : di < 0 ? 'w' : dj > 0 ? 's' : 'n';
    }

    /** World direction of a local side letter, for an entrance lying towards {@code out}. */
    static Direction world(char side, Direction out) {
        return switch (side) {
            case 's' -> out;
            case 'n' -> out.getOpposite();
            case 'e' -> out.getCounterClockWise();
            default -> out.getClockWise();
        };
    }

    static void emit(StructurePiecesBuilder builder, Site base, MarqueePlans.Plan plan, int ex, int surfaceY, int ez,
                     Direction out, int depthIn, long seed, int minFloor) {
        Site site = new Site(base.theme() == null ? Theme.RUIN : base.theme(), base.kit() != com.skycraft.dungeons.world.Kit.NONE ? base.kit() : plan.kit,
                Special.MARQUEE, out.get2DDataValue(), plan.hash);
        RandomSource r = RandomSource.create(seed);
        List<MarqueePlans.Room> rooms = plan.rooms;
        MarqueePlans.Room start = null;
        for (MarqueePlans.Room rm : rooms) if (rm.role == 0) start = rm;
        if (start == null) start = rooms.get(0);

        int depth = depthIn;
        int floor0 = surfaceY - depth;
        int lowestAbs = floor0 + plan.lowest;
        if (lowestAbs < minFloor) {
            int shift = minFloor - lowestAbs;
            depth = Math.max(6, depth - shift);
            floor0 = surfaceY - depth;
        }
        int tunnelLen = depth + 4;
        Direction.Axis axis = out.getAxis();
        int eAxis = axis == Direction.Axis.X ? ex : ez;
        int lateral = axis == Direction.Axis.X ? ez : ex;
        int step = out.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        int wall = eAxis - step * tunnelLen;
        int startAxis = wall - step * start.hv;
        int ox = axis == Direction.Axis.X ? startAxis : lateral;
        int oz = axis == Direction.Axis.X ? lateral : startAxis;
        Direction e = out.getCounterClockWise();

        // doors, entered sides and which links carry gates
        int n = rooms.size();
        int[] doors = new int[n];
        Map<Long, int[]> linkAt = new HashMap<>();
        List<int[]> edges = new java.util.ArrayList<>();
        for (MarqueePlans.Link l : plan.links) {
            int ia = plan.byId.get(l.a), ib = plan.byId.get(l.b);
            MarqueePlans.Room a = rooms.get(ia), b = rooms.get(ib);
            int di = b.i - a.i, dj = b.j - a.j;
            if (Math.abs(di) + Math.abs(dj) != 1) {
                Skycraft.LOGGER.warn("Skycraft marquee {}: link {}-{} is not between neighbouring cells", plan.id, l.a, l.b);
                continue;
            }
            Direction dab = world(side(di, dj), out);
            doors[ia] |= 1 << dab.get2DDataValue();
            doors[ib] |= 1 << dab.getOpposite().get2DDataValue();
            edges.add(new int[]{ia, ib});
        }
        // the side each room was entered from: breadth first from the start room
        int[] entered = new int[n];
        java.util.Arrays.fill(entered, -1);
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int si = rooms.indexOf(start);
        // the entrance tunnel meets the start room on its entrance side
        doors[si] |= 1 << out.get2DDataValue();
        seen[si] = true;
        queue.add(si);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (int[] ed : edges) {
                int other = ed[0] == cur ? ed[1] : ed[1] == cur ? ed[0] : -1;
                if (other < 0 || seen[other]) continue;
                seen[other] = true;
                MarqueePlans.Room a = rooms.get(cur), b = rooms.get(other);
                Direction toParent = world(side(a.i - b.i, a.j - b.j), out);
                entered[other] = toParent.get2DDataValue();
                queue.add(other);
            }
        }

        // tunnel and portal (as in the procedural layout)
        int head = 3, lat = 2;
        {
            int a1 = wall + step, a2 = wall + step * tunnelLen;
            BoundingBox box = axisBox(axis, a1, a2, lateral - lat, lateral + lat, floor0 - 1, surfaceY + head);
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.TUNNEL, box, r.nextLong(), floor0, surfaceY,
                    out.get2DDataValue(), depth, DungeonPiece.ROLE_NORMAL, lateral, wall,
                    plan.openTop ? DungeonPiece.FLAG_OPEN_TOP : 0));
        }
        if (!plan.openTop) {
            boolean big = true;
            int a1 = wall + step * (tunnelLen - (big ? 12 : 3)), a2 = wall + step * (tunnelLen + Portals.APPROACH);
            int w = big ? 8 : 6;
            BoundingBox box = axisBox(axis, a1, a2, lateral - w, lateral + w, surfaceY - 24, surfaceY + (big ? 20 : 10));
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.PORTAL, box, r.nextLong(), floor0, surfaceY,
                    out.get2DDataValue(), depth, DungeonPiece.ROLE_NORMAL, lateral, wall, 0));
        }

        // rooms
        int[] cxs = new int[n], czs = new int[n], hxs = new int[n], hzs = new int[n], fl = new int[n];
        for (int k = 0; k < n; k++) {
            MarqueePlans.Room rm = rooms.get(k);
            cxs[k] = ox + (rm.i * e.getStepX() + rm.j * out.getStepX()) * CELL;
            czs[k] = oz + (rm.i * e.getStepZ() + rm.j * out.getStepZ()) * CELL;
            boolean alongX = axis == Direction.Axis.X;
            hxs[k] = alongX ? rm.hv : rm.hu;
            hzs[k] = alongX ? rm.hu : rm.hv;
            fl[k] = floor0 + rm.y;
            BoundingBox box = new BoundingBox(cxs[k] - hxs[k], fl[k] - 1 - rm.drop, czs[k] - hzs[k],
                    cxs[k] + hxs[k], fl[k] + rm.h, czs[k] + hzs[k]);
            int role = rm.role == 0 ? DungeonPiece.ROLE_START : rm.role == 2 ? DungeonPiece.ROLE_BOSS : DungeonPiece.ROLE_NORMAL;
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.ROOM, box, r.nextLong(), fl[k], 0, entered[k], k,
                    role, doors[k], (Math.min(7, rm.tier) << 4), 0));
        }

        // corridors: each link once, from the lower-coordinate room
        for (MarqueePlans.Link l : plan.links) {
            int ia = plan.byId.get(l.a), ib = plan.byId.get(l.b);
            MarqueePlans.Room ra = rooms.get(ia), rb = rooms.get(ib);
            if (Math.abs(rb.i - ra.i) + Math.abs(rb.j - ra.j) != 1) continue;
            char sideA = side(rb.i - ra.i, rb.j - ra.j), sideB = side(ra.i - rb.i, ra.j - rb.j);
            int fa = fl[ia] + ra.doors.getOrDefault(sideA, 0);
            int fb = fl[ib] + rb.doors.getOrDefault(sideB, 0);
            int lo = cxs[ia] != cxs[ib] ? (cxs[ia] < cxs[ib] ? ia : ib) : (czs[ia] < czs[ib] ? ia : ib);
            int hi = lo == ia ? ib : ia;
            int flo = lo == ia ? fa : fb, fhi = lo == ia ? fb : fa;
            BoundingBox box;
            int axisId;
            int len;
            if (cxs[ia] != cxs[ib]) {
                int x1 = cxs[lo] + hxs[lo] + 1, x2 = cxs[hi] - hxs[hi] - 1;
                if (x2 < x1) {
                    Skycraft.LOGGER.warn("Skycraft marquee {}: rooms {} and {} touch", plan.id, l.a, l.b);
                    continue;
                }
                len = x2 - x1 + 1;
                box = new BoundingBox(x1, Math.min(fa, fb) - 1, czs[lo] - lat, x2, Math.max(fa, fb) + head, czs[lo] + lat);
                axisId = 0;
            } else {
                int z1 = czs[lo] + hzs[lo] + 1, z2 = czs[hi] - hzs[hi] - 1;
                if (z2 < z1) {
                    Skycraft.LOGGER.warn("Skycraft marquee {}: rooms {} and {} touch", plan.id, l.a, l.b);
                    continue;
                }
                len = z2 - z1 + 1;
                box = new BoundingBox(cxs[lo] - lat, Math.min(fa, fb) - 1, z1, cxs[lo] + lat, Math.max(fa, fb) + head, z2);
                axisId = 1;
            }
            if (Math.abs(fa - fb) > len - 1) {
                Skycraft.LOGGER.warn("Skycraft marquee {}: link {}-{} climbs {} over {} blocks", plan.id, l.a, l.b, Math.abs(fa - fb), len);
            }
            builder.addPiece(new DungeonPiece(site, DungeonPiece.Kind.CORRIDOR, box, r.nextLong(), flo, fhi, axisId, 0,
                    DungeonPiece.ROLE_NORMAL, 0, r.nextInt(8), l.trap ? DungeonPiece.FLAG_TRAP : 0));
        }
    }

    private static BoundingBox axisBox(Direction.Axis axis, int a1, int a2, int l1, int l2, int y1, int y2) {
        int amin = Math.min(a1, a2), amax = Math.max(a1, a2);
        if (axis == Direction.Axis.X) return new BoundingBox(amin, y1, l1, amax, y2, l2);
        return new BoundingBox(l1, y1, amin, l2, y2, amax);
    }
}
