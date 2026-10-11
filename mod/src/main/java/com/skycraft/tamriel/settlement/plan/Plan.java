package com.skycraft.tamriel.settlement.plan;

import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.kit.Palette;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * A settlement being laid out. Layouts work in a local frame centred on the settlement whose local north points
 * towards the settlement's "back" (the palace / castle side) and whose local south is the main gate; specs are stored
 * in world coordinates. After layout, {@link #resolve} sets every floor from the (noise) terrain.
 */
public final class Plan {
    public final String key;
    public final int cx, cz;
    public final int radius;
    public final Culture culture;
    public final int style;
    public final long seed;
    public final RandomSource rnd;
    public final Terrain terrain;
    /** World direction of local north. */
    public final Direction back;
    /** Floor of the settlement centre. */
    public int base;
    public final List<Spec> specs = new ArrayList<>();
    /** Ignore slopes when placing (a second pass, so named places never end up empty on rough ground). */
    public boolean relaxed;
    /** The zoning planner has run (or adopted this plan): don't zone it twice. */
    public boolean zoned;
    /** The named place being planned (null for generic settlements and sites). */
    public com.skycraft.tamriel.Place place;
    /** Jail, guild halls and player-home plots have been placed (plan/Halls, plan/HousePlots). */
    public boolean civicDone;

    public Plan(String key, int cx, int cz, int radius, Culture culture, int style, long seed, Terrain terrain, Direction back) {
        this.key = key;
        this.cx = cx;
        this.cz = cz;
        this.radius = radius;
        this.culture = culture;
        this.style = style;
        this.seed = seed;
        this.rnd = RandomSource.create(seed);
        this.terrain = terrain;
        this.back = back;
        int r = Math.max(4, radius / 3);
        int[] v = {terrain.land(cx, cz), terrain.land(cx + r, cz), terrain.land(cx - r, cz), terrain.land(cx, cz + r), terrain.land(cx, cz - r)};
        java.util.Arrays.sort(v);
        this.base = Math.max(terrain.sea, v[2]);
    }

    public Palette palette() {
        return Kits.palette(culture, style);
    }

    // ------------------------------------------------------------------ local frame

    public int wx(int lx, int lz) {
        return cx + switch (back) {
            case EAST -> -lz;
            case SOUTH -> -lx;
            case WEST -> lz;
            default -> lx;
        };
    }

    public int wz(int lx, int lz) {
        return cz + switch (back) {
            case EAST -> lx;
            case SOUTH -> -lz;
            case WEST -> -lx;
            default -> lz;
        };
    }

    /** World direction of a local horizontal direction. */
    public Direction dir(Direction local) {
        return switch (back) {
            case EAST -> local.getClockWise();
            case SOUTH -> local.getOpposite();
            case WEST -> local.getCounterClockWise();
            default -> local;
        };
    }

    // ------------------------------------------------------------------ adding pieces

    public Spec add(Spec s) {
        if (s.culture < 0) s.culture = culture.ordinal();
        if (s.style < 0) s.style = s.culture == culture.ordinal() ? style : 0;
        specs.add(s);
        return s;
    }

    /** A building (or square work) centred on local (lx, lz) whose front faces local {@code face}. */
    public Spec at(Archetype kind, int lx, int lz, Direction face) {
        return Spec.at(kind, wx(lx, lz), wz(lx, lz), dir(face));
    }

    public Spec at(Archetype kind, int lx, int lz, Direction face, int w, int d) {
        return at(kind, lx, lz, face).size(w, d);
    }

    /** Adds the spec if it is free of the others (and of streets), on dry, not too steep land within the radius. */
    public Spec place(Spec s, int gap) {
        return fits(s, gap) ? add(s) : null;
    }

    /** Adds the spec unconditionally (unique set pieces). */
    public Spec put(Spec s) {
        return add(s);
    }

    public Spec line(Archetype kind, int lx1, int lz1, int lx2, int lz2, int width) {
        return Spec.line(kind, wx(lx1, lz1), wz(lx1, lz1), wx(lx2, lz2), wz(lx2, lz2), width);
    }

    public Spec street(int lx1, int lz1, int lx2, int lz2, int width, boolean major) {
        Spec s = line(Archetype.STREET, lx1, lz1, lx2, lz2, width);
        if (major) s.flag(Spec.MAJOR);
        return add(s);
    }

    public boolean fits(Spec s, int gap) {
        if (!s.isLine()) {
            long dx = s.x - cx, dz = s.z - cz;
            if (dx * dx + dz * dz > (long) radius * radius && radius > 0) return false;
            if (s.kind != Archetype.DOCK && !s.has(Spec.WATER)) {
                int hx = Math.max(1, s.halfX() - s.margin), hz = Math.max(1, s.halfZ() - s.margin);
                if (terrain.anyWet(s.x, s.z, hx, hz)) return false;
                if (!relaxed && terrain.spread(s.x, s.z, hx, hz) > maxSlope(s.kind)) return false;
            }
        }
        for (Spec o : specs) if (collides(s, o, gap)) return false;
        return true;
    }

    static int maxSlope(Archetype k) {
        return switch (k) {
            case FIELD, GARDEN, PLAZA, GRAVEYARD -> 4;
            case MINE -> 30;
            default -> 9;
        };
    }

    /** Footprint collision: rectangles by extent, lines by sampling along their length. */
    public static boolean collides(Spec a, Spec b, int gap) {
        if (a.isLine() && b.isLine()) return false;
        if (a.has(Spec.GROUND) || b.has(Spec.GROUND)) return false;
        if (a.isLine()) return lineHits(a, b, 0);
        if (b.isLine()) return lineHits(b, a, 0);
        return a.overlaps(b, gap);
    }

    static boolean lineHits(Spec line, Spec r, int gap) {
        int len = (int) Math.ceil(Math.hypot(line.x2 - line.x, line.z2 - line.z));
        int hw = line.w / 2;
        for (int i = 0; i <= len; i += 2) {
            double t = len == 0 ? 0 : i / (double) len;
            int px = (int) Math.round(line.x + (line.x2 - line.x) * t), pz = (int) Math.round(line.z + (line.z2 - line.z) * t);
            if (Math.abs(px - r.x) < r.halfX() + hw + gap && Math.abs(pz - r.z) < r.halfZ() + hw + gap) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ walls

    /**
     * A ring of wall segments (a regular polygon of {@code sides} around the centre, radius {@code r}) with towers at
     * the corners and gatehouses at the given local angles (radians, 0 = local south / the main gate, increasing
     * clockwise seen from above).
     */
    public void wallRing(int r, int sides, int h, double[] gates, int flags, boolean towers) {
        double step = 2 * Math.PI / sides;
        int gateHalf = 6;
        for (int i = 0; i < sides; i++) {
            double a0 = i * step + step / 2, a1 = a0 + step;
            double lx0 = Math.sin(a0) * r, lz0 = Math.cos(a0) * r;
            double lx1 = Math.sin(a1) * r, lz1 = Math.cos(a1) * r;
            double mid = (a0 + a1) / 2;
            double len = Math.hypot(lx1 - lx0, lz1 - lz0);
            double ux = (lx1 - lx0) / len, uz = (lz1 - lz0) / len;
            // cut the side where a gate stands: [0, len] minus [t - gateHalf, t + gateHalf]
            List<double[]> spans = new ArrayList<>();
            spans.add(new double[]{0, len});
            for (double g : gates) {
                double gx = Math.sin(g) * r, gz = Math.cos(g) * r;
                double t = (gx - lx0) * ux + (gz - lz0) * uz;
                double off = Math.abs((gx - lx0) * uz - (gz - lz0) * ux);
                if (t < -gateHalf || t > len + gateHalf || off > 8) continue;
                List<double[]> next = new ArrayList<>();
                for (double[] sp : spans) {
                    if (t - gateHalf > sp[0]) next.add(new double[]{sp[0], Math.min(sp[1], t - gateHalf)});
                    if (t + gateHalf < sp[1]) next.add(new double[]{Math.max(sp[0], t + gateHalf), sp[1]});
                }
                spans = next;
            }
            for (double[] sp : spans) {
                double spanLen = sp[1] - sp[0];
                if (spanLen < 1) continue;
                // short pieces (beardifier and chunk reach)
                int parts = Math.max(1, (int) Math.ceil(spanLen / 24.0));
                for (int k = 0; k < parts; k++) {
                    double t0 = sp[0] + spanLen * k / parts, t1 = sp[0] + spanLen * (k + 1) / parts;
                    int ax = (int) Math.round(lx0 + ux * t0), az = (int) Math.round(lz0 + uz * t0);
                    int bx = (int) Math.round(lx0 + ux * t1), bz = (int) Math.round(lz0 + uz * t1);
                    Spec w = line(Archetype.WALL, ax, az, bx, bz, 3);
                    w.h = h;
                    w.facing = dir(outward(mid));
                    w.flags |= flags;
                    add(w);
                }
            }
            if (towers && !s(flags, Spec.PALISADE)) {
                Spec t = at(Archetype.TOWER, (int) Math.round(lx1), (int) Math.round(lz1), outward(a1)).size(7, 7);
                t.h = h + 14;
                t.margin = 2;
                boolean nearGate = false;
                for (double g : gates) if (angleDist(a1, g) < Math.toRadians(8)) nearGate = true;
                if (!nearGate) add(t);
            }
        }
        for (double g : gates) {
            int lx = (int) Math.round(Math.sin(g) * r), lz = (int) Math.round(Math.cos(g) * r);
            Direction out = outward(g);
            Spec gate = at(Archetype.GATE, lx, lz, out).size(11, 7);
            gate.h = h + 6;
            gate.flags |= flags & Spec.PALISADE;
            gate.margin = 2;
            add(gate);
        }
    }

    private static boolean s(int flags, int f) {
        return (flags & f) != 0;
    }

    static double angleDist(double a, double b) {
        double d = Math.abs(Mth.wrapDegrees(Math.toDegrees(a - b)));
        return Math.toRadians(d);
    }

    /** Local outward direction at a local angle (0 = south). */
    public static Direction outward(double a) {
        double sx = Math.sin(a), sz = Math.cos(a);
        if (Math.abs(sx) > Math.abs(sz)) return sx > 0 ? Direction.EAST : Direction.WEST;
        return sz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ------------------------------------------------------------------ floors

    /** Sets every spec's floor from the terrain; drops docks without water. */
    public void resolve() {
        Palette pal = palette();
        List<Spec> drop = new ArrayList<>();
        for (Spec s : specs) {
            if (s.has(Spec.FIXED) && s.floor != Spec.UNSET) continue;
            if (s.isLine()) {
                int mx = (s.x + s.x2) / 2, mz = (s.z + s.z2) / 2;
                int f = terrain.land(mx, mz);
                if (s.kind == Archetype.WALL) f = Math.max(f, Math.max(terrain.land(s.x, s.z), terrain.land(s.x2, s.z2)));
                if (s.kind == Archetype.BRIDGE) {
                    if (s.floor == Spec.UNSET) s.floor = Math.max(terrain.land(s.x, s.z), waterTop(s.x, s.z)) + 1;
                    if (s.floor2 == Spec.UNSET) s.floor2 = Math.max(terrain.land(s.x2, s.z2), waterTop(s.x2, s.z2)) + 1;
                    continue;
                }
                if (s.kind == Archetype.CANAL) f = s.minFloor != Spec.UNSET ? base + s.minFloor : base;
                if (s.minFloor != Spec.UNSET) f = Math.max(f, base + s.minFloor);
                s.floor = f;
                continue;
            }
            int hx = Math.max(1, s.halfX() - s.margin), hz = Math.max(1, s.halfZ() - s.margin);
            int f = terrain.median(s.x, s.z, hx, hz);
            if (s.kind == Archetype.DOCK) {
                int w = terrain.water(s.x2 == 0 && s.z2 == 0 ? s.x : s.x, s.z);
                if (w == Terrain.NO_WATER || w <= terrain.land(s.x, s.z)) {
                    drop.add(s);
                    continue;
                }
                f = w + 1;
            } else if (s.has(Spec.WATER)) {
                int w = terrain.water(s.x, s.z);
                f = w != Terrain.NO_WATER && w > f ? w + 2 : f + 2;
            } else if (pal.stilts && s.kind.building && s.culture == culture.ordinal()) {
                f += 2;
            }
            if (s.minFloor != Spec.UNSET) f = Math.max(f, base + s.minFloor);
            s.floor = f;
        }
        specs.removeAll(drop);
    }

    private int waterTop(int x, int z) {
        int w = terrain.water(x, z);
        return w == Terrain.NO_WATER ? Integer.MIN_VALUE : w;
    }

    // ------------------------------------------------------------------ districts

    /** District of a spec relative to the anchor chunk: (0, 0) for the anchor start, else the 12-chunk grid cell. */
    public static long district(Spec s, int anchorX, int anchorZ) {
        int pcx = s.centerX() >> 4, pcz = s.centerZ() >> 4;
        int acx = anchorX >> 4, acz = anchorZ >> 4;
        int dx = pcx - acx, dz = pcz - acz;
        int ix = Math.abs(dx) <= 6 ? 0 : Math.round(dx / 12f), iz = Math.abs(dz) <= 6 ? 0 : Math.round(dz / 12f);
        return pack(ix, iz);
    }

    public static long pack(int ix, int iz) {
        return ((long) ix << 32) ^ (iz & 0xFFFFFFFFL);
    }
}
