package com.nirncraft.tamriel.settlement.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.tamriel.settlement.build.LandmarkRegistry;
import com.nirncraft.tamriel.settlement.grammar.Overlays;
import com.nirncraft.tamriel.settlement.grammar.Zoning;
import com.nirncraft.tamriel.settlement.kit.Kits;
import net.minecraft.core.Direction;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The registry and applier of {@link CityPlan}s. Plans are listed in {@code cityplans/index.json}, parsed once at
 * class load (immutable afterwards, so safe from any worldgen thread) and looked up by place id. {@link Cities}
 * delegates to a plan whenever one exists; Skyrim's unique cities stay hand-written and untouched.
 */
public final class CityPlans {
    private static final String ROOT = "/nirncraft/tamriel/cityplans/";

    private record Holder(Map<String, CityPlan> plans, Map<Integer, String> overlays) {
    }

    private static volatile Holder holder = load();

    private CityPlans() {}

    // ------------------------------------------------------------------ registry

    public static CityPlan of(Place p) {
        return holder.plans.get(p.id());
    }

    public static CityPlan of(String placeId) {
        return holder.plans.get(placeId);
    }

    public static Collection<CityPlan> all() {
        return holder.plans.values();
    }

    /** The overlay id behind a packed overlay index, or null. */
    public static String overlayId(int idx) {
        return holder.overlays.get(idx);
    }

    /** The inn name key (lang {@code settlement.nirncraft.name.<key>}) of a plan's inn, or null. */
    public static String innKey(String placeId) {
        CityPlan cp = holder.plans.get(placeId);
        if (cp == null) return null;
        if (!cp.innKey.isEmpty()) return cp.innKey;
        return cp.names.inn().isEmpty() ? null : cp.names.inn();
    }

    /** Re-reads the plan files (dev reload). */
    public static void reload() {
        holder = load();
    }

    private static Holder load() {
        Map<String, CityPlan> plans = new LinkedHashMap<>();
        Map<Integer, String> overlays = new HashMap<>();
        try (InputStream in = com.nirncraft.dev.DevResources.open(CityPlans.class, ROOT + "index.json")) {
            if (in == null) return new Holder(plans, overlays);
            JsonObject idx = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            java.util.Set<String> ovl = new TreeSet<>();
            if (idx.has("overlays")) for (JsonElement e : idx.getAsJsonArray("overlays")) ovl.add(e.getAsString());
            for (JsonElement e : idx.getAsJsonArray("plans")) {
                String id = e.getAsString();
                try (InputStream pin = com.nirncraft.dev.DevResources.open(CityPlans.class, ROOT + id + ".json")) {
                    if (pin == null) {
                        NirnCraft.LOGGER.warn("City plan {} listed but missing", id);
                        continue;
                    }
                    JsonObject o = JsonParser.parseReader(new InputStreamReader(pin, StandardCharsets.UTF_8)).getAsJsonObject();
                    CityPlan cp = new CityPlan(o);
                    if (!cp.id.equals(id)) throw new IllegalArgumentException("id " + cp.id + " in " + id + ".json");
                    plans.put(id, cp);
                    cp.overlayIds(ovl);
                } catch (RuntimeException | java.io.IOException ex) {
                    NirnCraft.LOGGER.error("Bad city plan {}", id, ex);
                }
            }
            for (String o : ovl) {
                String old = overlays.put(Overlays.index(o), o);
                if (old != null && !old.equals(o)) NirnCraft.LOGGER.error("Style overlay index collision: {} and {}", old, o);
            }
        } catch (Exception ex) {
            NirnCraft.LOGGER.error("Could not read the city plan index", ex);
        }
        return new Holder(plans, overlays);
    }

    // ------------------------------------------------------------------ applying a plan

    /** Lays out a place from its plan. Returns true (the plan owns the layout). */
    static boolean build(Plan plan, Place p, CityPlan cp) {
        String id = p != null ? p.id() : cp.id;
        boolean hasJava = cp.base == CityPlan.Base.JAVA || Cities.hasJavaBuilder(id);
        switch (cp.base) {
            case ZONING -> {
                if (hasJava) Cities.buildJava(plan, p);
                pins(plan, cp);
                if (!plan.zoned) {
                    Layouts.Size sz = Zoning.sizeOf(p, plan.radius);
                    Zoning.generic(plan, sz, p.variant(), cp.names);
                }
            }
            default -> {
                if (hasJava) Cities.buildJava(plan, p);
                pins(plan, cp);
                fill(plan, cp);
            }
        }
        return true;
    }

    /** Terraces, walls, lines, landmarks, named buildings, streets, rings and plazas. */
    private static void pins(Plan plan, CityPlan cp) {
        int javaCount = plan.specs.size();
        for (CityPlan.Terrace t : cp.terraces) {
            if (!hasTerrace(plan, t)) {
                Cities.terraces(plan, t.x0(), t.z0(), t.x1(), t.z1(), t.level(), t.parapet());
            }
        }
        for (CityPlan.Wall w : cp.walls) {
            if (hasWallRing(plan, w.r())) continue;
            int from = plan.specs.size();
            plan.wallRing(w.r(), w.sides(), w.h(), w.gates(), w.flags(), w.towers());
            if (w.raise() != 0) Cities.raiseFrom(plan, from, w.raise());
        }
        for (CityPlan.Line l : cp.lines) line(plan, l);
        for (CityPlan.Landmark l : cp.landmarks) landmark(plan, l);
        for (CityPlan.Line l : cp.streets) line(plan, l);
        for (CityPlan.Ring r : cp.rings) ring(plan, r);
        Nets.apply(plan, cp);
        for (CityPlan.Plaza z : cp.plazas) {
            Spec ex = findPlaza(plan, z.x(), z.z(), 24);
            if (ex != null) {
                if (!z.name().isEmpty() && (ex.name == null || ex.name.isEmpty())) ex.name = z.name();
                continue;
            }
            int px = z.x(), pz = z.z();
            Spec probe = plan.at(Archetype.PLAZA, px, pz, Direction.SOUTH, z.size(), z.size());
            probe.margin = 0;
            if (anyNonLineCollision(plan, probe, 1)) {
                int[] nudged = findClearSpot(plan, probe, px, pz, false);
                if (nudged != null) {
                    px = nudged[0];
                    pz = nudged[1];
                }
            }
            Spec s = Cities.plaza(plan, px, pz, z.size(), z.variant(), z.raise());
            if (!z.name().isEmpty()) s.name = z.name();
            if (z.stalls() > 0) Cities.stalls(plan, px, pz, z.stalls(), z.raise());
        }
        if (javaCount > 0) relocateCollidingJavaBuildings(plan, javaCount);
        for (CityPlan.Building b : cp.buildings) building(plan, b);
    }

    private static boolean hasTerrace(Plan plan, CityPlan.Terrace t) {
        int wx0 = Math.min(plan.wx(t.x0(), t.z0()), plan.wx(t.x1(), t.z1()));
        int wx1 = Math.max(plan.wx(t.x0(), t.z0()), plan.wx(t.x1(), t.z1()));
        int wz0 = Math.min(plan.wz(t.x0(), t.z0()), plan.wz(t.x1(), t.z1()));
        int wz1 = Math.max(plan.wz(t.x0(), t.z0()), plan.wz(t.x1(), t.z1()));
        for (Spec s : plan.specs) {
            if (s.kind == Archetype.TERRACE && s.minFloor == t.level()
                    && s.x >= wx0 - 8 && s.x <= wx1 + 8 && s.z >= wz0 - 8 && s.z <= wz1 + 8) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWallRing(Plan plan, int r) {
        for (Spec s : plan.specs) {
            if (s.kind == Archetype.WALL && s.isLine()) {
                double mx = (s.x + s.x2) * 0.5 - plan.cx;
                double mz = (s.z + s.z2) * 0.5 - plan.cz;
                double d = Math.hypot(mx, mz);
                if (Math.abs(d - r) <= 18) return true;
            }
        }
        return false;
    }

    private static Spec findPlaza(Plan plan, int lx, int lz, int tol) {
        int wx = plan.wx(lx, lz), wz = plan.wz(lx, lz);
        for (Spec s : plan.specs) {
            if (s.kind == Archetype.PLAZA && Math.hypot(s.x - wx, s.z - wz) <= tol) return s;
        }
        return null;
    }

    private static boolean hasDuplicateLine(Plan plan, Archetype kind, int lx1, int lz1, int lx2, int lz2) {
        int wx1 = plan.wx(lx1, lz1), wz1 = plan.wz(lx1, lz1);
        int wx2 = plan.wx(lx2, lz2), wz2 = plan.wz(lx2, lz2);
        for (Spec s : plan.specs) {
            if (s.isLine() && s.kind == kind) {
                boolean same = Math.hypot(s.x - wx1, s.z - wz1) <= 8 && Math.hypot(s.x2 - wx2, s.z2 - wz2) <= 8;
                boolean rev = Math.hypot(s.x - wx2, s.z - wz2) <= 8 && Math.hypot(s.x2 - wx1, s.z2 - wz1) <= 8;
                if (same || rev) return true;
            }
        }
        return false;
    }

    private static void line(Plan plan, CityPlan.Line l) {
        Archetype kind = switch (l.kind()) {
            case "wall" -> Archetype.WALL;
            case "bridge" -> Archetype.BRIDGE;
            case "canal" -> Archetype.CANAL;
            default -> Archetype.STREET;
        };
        if (hasDuplicateLine(plan, kind, l.x1(), l.z1(), l.x2(), l.z2())) return;
        Spec s;
        switch (l.kind()) {
            case "wall" -> {
                s = plan.line(Archetype.WALL, l.x1(), l.z1(), l.x2(), l.z2(), l.width());
                s.h = l.h() > 0 ? l.h() : 9;
                s.facing = plan.dir(Direction.SOUTH);
                s.flags |= l.flags();
                plan.put(s);
            }
            case "bridge" -> {
                s = plan.line(Archetype.BRIDGE, l.x1(), l.z1(), l.x2(), l.z2(), l.width());
                s.variant = l.variant();
                s.flags |= l.flags();
                if (l.lift() != null) {
                    s.flags |= Spec.FIXED;
                    s.floor = plan.base + l.lift();
                    s.floor2 = plan.base + (l.lift2() != null ? l.lift2() : l.lift());
                }
                plan.put(s);
            }
            case "canal" -> {
                s = plan.line(Archetype.CANAL, l.x1(), l.z1(), l.x2(), l.z2(), l.width());
                s.flags |= l.flags();
                plan.put(s);
            }
            default -> {
                s = plan.street(l.x1(), l.z1(), l.x2(), l.z2(), l.width(), l.major());
                s.flags |= l.flags();
                if (l.lift() != null) {
                    s.flags |= Spec.FIXED;
                    s.floor = plan.base + l.lift();
                    s.floor2 = plan.base + (l.lift2() != null ? l.lift2() : l.lift());
                }
            }
        }
        if (l.raise() != 0) s.minFloor = l.raise();
    }

    private static final int[][] HOUSE_SIZES = {
            {7, 9}, {9, 9}, {9, 11}, {11, 9}, {11, 11}, {11, 13}, {13, 15}
    };

    private static int[][] polygon(CityPlan.Ring r) {
        int[][] v = new int[r.sides()][];
        double step = 2 * Math.PI / r.sides();
        for (int i = 0; i < r.sides(); i++) {
            double a = r.offset() + i * step;
            v[i] = new int[]{r.cx() + (int) Math.round(Math.sin(a) * r.r()), r.cz() + (int) Math.round(Math.cos(a) * r.r())};
        }
        return v;
    }

    private static void ring(Plan plan, CityPlan.Ring r) {
        int[][] v = polygon(r);
        for (int i = 0; i < v.length; i++) {
            int[] a = v[i], b = v[(i + 1) % v.length];
            if (hasDuplicateLine(plan, Archetype.STREET, a[0], a[1], b[0], b[1])) continue;
            Spec s = plan.street(a[0], a[1], b[0], b[1], r.width(), r.major());
            if (r.raise() != 0) s.minFloor = r.raise();
        }
    }

    private static int styleOf(Plan plan, Spec s, String culture, String kit, String overlay) {
        int cul = culture.isEmpty() ? s.culture : Culture.of(culture).ordinal();
        s.culture = cul;
        int base = culture.isEmpty() ? (s.style >= 0 ? s.style : plan.style) : 0;
        int k = kit.isEmpty() ? base & Kits.KIT_MASK : Kits.styleOf(Culture.byOrdinal(cul), kit, base & Kits.KIT_MASK);
        int ov = overlay.isEmpty() ? base >>> Kits.OVERLAY_SHIFT : Overlays.index(overlay);
        return Kits.pack(k, ov);
    }

    private static void landmark(Plan plan, CityPlan.Landmark l) {
        LandmarkRegistry.Def def = LandmarkRegistry.of(l.id());
        if (def == null) {
            NirnCraft.LOGGER.error("City plan {}: unknown landmark {}", plan.key, l.id());
            return;
        }
        int w = l.w() > 0 ? l.w() : def.w(), d = l.d() > 0 ? l.d() : def.d();
        Spec s = plan.at(Archetype.LANDMARK, l.x(), l.z(), l.face(), w, d);
        s.variant = def.variant();
        s.h = l.h() > 0 ? l.h() : def.h();
        if (l.margin() >= 0) s.margin = l.margin();
        s.flags |= l.flags();
        s.name = l.name();
        String lid = l.id().toLowerCase(java.util.Locale.ROOT);
        String lname = l.name().toLowerCase(java.util.Locale.ROOT);
        if (lid.contains("palace") || lid.contains("castle") || lid.contains("keep") || lid.contains("white_gold")
                || lid.contains("giant_tree") || lid.contains("xanmeer") || lid.contains("canton") || lid.contains("college")
                || lname.contains("palace") || lname.contains("castle") || lname.contains("keep") || lname.contains("reach")) {
            s.flags |= Spec.SEAT;
        }
        if (l.raise() != 0) s.minFloor = l.raise();
        if (!l.kit().isEmpty() || !l.overlay().isEmpty()) s.style = styleOf(plan, s, "", l.kit(), l.overlay());

        Spec dup = findMatchingLandmark(plan, s, lid);
        if (dup != null) {
            if (!l.name().isEmpty()) dup.name = l.name();
            dup.flags |= s.flags;
            if (!l.kit().isEmpty() || !l.overlay().isEmpty()) dup.style = styleOf(plan, dup, "", l.kit(), l.overlay());
            return;
        }
        if (anyNonLineCollision(plan, s, 1)) {
            int[] nudged = findClearSpot(plan, s, l.x(), l.z(), false);
            if (nudged != null) {
                s.x = plan.wx(nudged[0], nudged[1]);
                s.z = plan.wz(nudged[0], nudged[1]);
            }
        }
        plan.put(s);
    }

    private static Spec findMatchingLandmark(Plan plan, Spec cand, String lid) {
        boolean multiAllowed = lid.contains("canton") || lid.contains("arch") || lid.contains("statue")
                || lid.contains("wayshrine") || lid.contains("standing_stone") || lid.contains("obelisk");
        for (Spec ex : plan.specs) {
            if (ex.kind == Archetype.LANDMARK) {
                double dist = Math.hypot(ex.x - cand.x, ex.z - cand.z);
                if (ex.variant == cand.variant && (!multiAllowed || dist <= 28)) return ex;
                if (cand.has(Spec.SEAT) && ex.has(Spec.SEAT) && !multiAllowed && dist <= 48) return ex;
            }
        }
        return null;
    }

    private static void building(Plan plan, CityPlan.Building b) {
        for (int i = 0; i < b.count(); i++) {
            int w = b.w() > 0 ? b.w() : b.kind().w, d = b.d() > 0 ? b.d() : b.kind().d;
            if (b.count() > 1 && b.w() == 0 && (b.kind() == Archetype.HOUSE || b.kind() == Archetype.MANOR || b.kind() == Archetype.FARMHOUSE)) {
                int[] sz = HOUSE_SIZES[i % HOUSE_SIZES.length];
                w = sz[0];
                d = sz[1];
            }
            int lx = b.x() + i * b.stepX(), lz = b.z() + i * b.stepZ();
            Spec s = plan.at(b.kind(), lx, lz, b.face(), w, d);
            s.flags |= b.flags();
            if (s.kind == Archetype.CAMP || s.kind == Archetype.SHRINE || s.kind == Archetype.LUMBER
                    || s.kind == Archetype.YARD || s.kind == Archetype.PEN || s.kind == Archetype.FIELD
                    || s.kind == Archetype.GARDEN || s.kind == Archetype.GRAVEYARD
                    || (plan.place != null && Math.hypot(lx, lz) > plan.place.radius() - 4)
                    || Math.hypot(lx, lz) > plan.radius - 4) {
                s.flags |= Spec.NO_BEARD;
            }
            s.variant = b.variant();
            if (b.h() > 0) s.h = b.h();
            if (b.margin() >= 0) s.margin = b.margin();
            if (!b.name().isEmpty() && (b.count() == 1 || i == 0)) {
                s.name = b.name();
                s.flag(Spec.SIGN);
            }
            if (b.raise() != 0) s.minFloor = b.raise();
            if (!b.use().isEmpty()) grammarUse(plan, s, b.use(), b.wealth());
            boolean allowSeat = com.nirncraft.tamriel.settlement.grammar.Zoning.hasCourtSeat(plan.place, plan.place != null && (plan.place.type() == com.nirncraft.tamriel.PlaceType.CAPITAL || plan.place.type() == com.nirncraft.tamriel.PlaceType.CITY) ? 3 : 2);
            if (!allowSeat) {
                s.flags &= ~Spec.SEAT;
            } else if (s.kind == Archetype.PALACE || s.kind == Archetype.LONGHOUSE
                    || s.use == com.nirncraft.tamriel.settlement.grammar.Use.KEEP.ordinal()
                    || s.use == com.nirncraft.tamriel.settlement.grammar.Use.LONGHOUSE.ordinal()) {
                s.flags |= Spec.SEAT;
                if (b.w() == 0) {
                    boolean cityKeep = s.kind == Archetype.PALACE || s.use == com.nirncraft.tamriel.settlement.grammar.Use.KEEP.ordinal();
                    s.size(Math.max(s.w, cityKeep ? 19 : 15), Math.max(s.d, cityKeep ? 23 : 19));
                }
            }
            if (s.kind == Archetype.HOUSE || s.kind == Archetype.MANOR || s.kind == Archetype.FARMHOUSE
                    || (s.grammar() && com.nirncraft.tamriel.settlement.grammar.Use.byOrdinal(s.use).home())) {
                if (s.w < 7) s.w = 7;
                if (s.d < 9) s.d = 9;
            }
            if (!b.culture().isEmpty() || !b.kit().isEmpty() || !b.overlay().isEmpty()) {
                s.style = styleOf(plan, s, b.culture(), b.kit(), b.overlay());
            }
            Spec dup = findMatchingBuilding(plan, s);
            if (dup != null) {
                if (s.name != null && !s.name.isEmpty()) {
                    dup.name = s.name;
                    dup.flag(Spec.SIGN);
                }
                dup.flags |= (s.flags & (Spec.SEAT | Spec.RICH | Spec.UPPER | Spec.SIGN));
                if (!b.culture().isEmpty() || !b.kit().isEmpty() || !b.overlay().isEmpty()) {
                    dup.style = styleOf(plan, dup, b.culture(), b.kit(), b.overlay());
                }
                if (!b.use().isEmpty() && !dup.grammar() && dup.kind != Archetype.LANDMARK) {
                    grammarUse(plan, dup, b.use(), b.wealth());
                }
                continue;
            }
            placePin(plan, s, lx, lz);
        }
    }

    private static Spec findMatchingBuilding(Plan plan, Spec cand) {
        String cn = cand.name == null ? "" : cand.name.toLowerCase(java.util.Locale.ROOT);
        for (Spec ex : plan.specs) {
            if (!ex.kind.building && ex.kind != Archetype.LANDMARK) continue;
            String en = ex.name == null ? "" : ex.name.toLowerCase(java.util.Locale.ROOT);
            if (!cn.isEmpty() && !en.isEmpty() && (cn.equals(en) || cn.endsWith("_" + en) || en.endsWith("_" + cn))) {
                return ex;
            }
            if (cand.has(Spec.SEAT) && ex.has(Spec.SEAT) && Math.hypot(ex.x - cand.x, ex.z - cand.z) <= 56) {
                return ex;
            }
            if (ex.kind == cand.kind && Math.hypot(ex.x - cand.x, ex.z - cand.z) <= 10) {
                return ex;
            }
        }
        return null;
    }

    private static boolean anyCollision(Plan plan, Spec s, int gap) {
        for (Spec o : plan.specs) {
            if (o == s) continue;
            if (Plan.collides(s, o, gap)) return true;
        }
        return false;
    }

    private static boolean anyNonLineCollision(Plan plan, Spec s, int gap) {
        for (Spec o : plan.specs) {
            if (o == s || o.isLine() || o.has(Spec.GROUND)) continue;
            if (s.overlaps(o, gap)) return true;
        }
        return false;
    }

    private static boolean insideRadius(Plan plan, Spec s, int lx, int lz) {
        if (plan.radius <= 0) return true;
        int limit = Math.max(16, plan.radius - 2);
        return Math.hypot(lx, lz) <= limit;
    }

    private static boolean buildableGround(Plan plan, Spec s) {
        if (s.kind == Archetype.DOCK || s.has(Spec.WATER)) return true;
        int hx = Math.max(1, s.halfX() - s.margin), hz = Math.max(1, s.halfZ() - s.margin);
        if (plan.terrain.anyWet(s.x, s.z, hx, hz)) return false;
        if (s.kind.building && s.kind != Archetype.MINE && s.kind != Archetype.WALL && s.kind != Archetype.GATE) {
            return plan.terrain.spread(s.x, s.z, hx, hz) <= 10;
        }
        return true;
    }

    private static int[] findClearSpot(Plan plan, Spec probe, int lx, int lz, boolean checkLines) {
        int origX = probe.x, origZ = probe.z;
        int maxR = Math.max(64, plan.radius);
        for (int gap : new int[]{1, 0}) {
            for (int r = 3; r <= maxR; r += 3) {
                int steps = Math.max(8, (int) Math.round(2 * Math.PI * r / 4.0));
                for (int k = 0; k < steps; k++) {
                    double a = 2 * Math.PI * k / steps;
                    int nx = lx + (int) Math.round(Math.cos(a) * r);
                    int nz = lz + (int) Math.round(Math.sin(a) * r);
                    if (!insideRadius(plan, probe, nx, nz)) continue;
                    probe.x = plan.wx(nx, nz);
                    probe.z = plan.wz(nx, nz);
                    if (!buildableGround(plan, probe)) continue;
                    boolean hit = checkLines ? anyCollision(plan, probe, gap) : anyNonLineCollision(plan, probe, gap);
                    if (!hit) {
                        probe.x = origX;
                        probe.z = origZ;
                        return new int[]{nx, nz};
                    }
                }
            }
        }
        probe.x = origX;
        probe.z = origZ;
        return null;
    }

    private static void placePin(Plan plan, Spec s, int lx, int lz) {
        if (insideRadius(plan, s, lx, lz) && buildableGround(plan, s) && !anyCollision(plan, s, 0)) {
            plan.put(s);
            return;
        }
        int[] spot = findClearSpot(plan, s, lx, lz, true);
        if (spot == null && (lx != 0 || lz != 0)) {
            spot = findClearSpot(plan, s, 0, 0, true);
        }
        if (spot != null) {
            s.x = plan.wx(spot[0], spot[1]);
            s.z = plan.wz(spot[0], spot[1]);
            if (s.grammar()) {
                s.slope = plan.terrain.spread(s.x, s.z, Math.max(1, s.halfX() - s.margin), Math.max(1, s.halfZ() - s.margin));
            }
            plan.put(s);
        } else if ((s.name != null && !s.name.isEmpty()) || s.has(Spec.SEAT)) {
            int[] nonLineSpot = findClearSpot(plan, s, lx, lz, false);
            if (nonLineSpot != null) {
                s.x = plan.wx(nonLineSpot[0], nonLineSpot[1]);
                s.z = plan.wz(nonLineSpot[0], nonLineSpot[1]);
            }
            plan.put(s);
        }
    }

    private static void relocateCollidingJavaBuildings(Plan plan, int javaCount) {
        List<Spec> toRelocate = new ArrayList<>();
        int limit = Math.min(javaCount, plan.specs.size());
        for (int i = 0; i < limit; i++) {
            Spec b = plan.specs.get(i);
            if (!b.kind.building || b.kind == Archetype.LANDMARK) continue;
            boolean hit = false;
            for (int j = 0; j < plan.specs.size(); j++) {
                if (i == j) continue;
                Spec o = plan.specs.get(j);
                if (j < i && b.kind.building && o.kind.building && !Plan.collides(b, o, 0)) continue;
                if (Plan.collides(b, o, 0)) {
                    hit = true;
                    break;
                }
            }
            if (hit) toRelocate.add(b);
        }
        if (toRelocate.isEmpty()) return;
        plan.specs.removeAll(toRelocate);
        for (Spec b : toRelocate) {
            int lx = Zoning.localX(plan, b.x, b.z), lz = Zoning.localZ(plan, b.x, b.z);
            placePin(plan, b, lx, lz);
        }
    }

    /** Makes a pinned piece a building-grammar building of {@code use} (a sawmill, a mage tower, a warehouse...). */
    public static void grammarUse(Plan plan, Spec s, String use, String wealth) {
        com.nirncraft.tamriel.settlement.grammar.Use u = com.nirncraft.tamriel.settlement.grammar.Use.byId(use.toLowerCase(java.util.Locale.ROOT));
        if (u == null) {
            NirnCraft.LOGGER.error("City plan {}: unknown use {}", plan.key, use);
            return;
        }
        com.nirncraft.tamriel.settlement.grammar.Wealth w = com.nirncraft.tamriel.settlement.grammar.Wealth.COMMON;
        for (com.nirncraft.tamriel.settlement.grammar.Wealth c : com.nirncraft.tamriel.settlement.grammar.Wealth.values()) {
            if (c.name().equalsIgnoreCase(wealth)) w = c;
        }
        s.use(u, w);
        long h = plan.seed ^ (s.x * 0x9E3779B97F4A7C15L) ^ (s.z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        s.gseed = (int) h;
        s.slope = plan.terrain.spread(s.x, s.z, Math.max(1, s.halfX() - s.margin), Math.max(1, s.halfZ() - s.margin));
        if (w.atLeast(com.nirncraft.tamriel.settlement.grammar.Wealth.WEALTHY) || u == com.nirncraft.tamriel.settlement.grammar.Use.MANOR) s.flags |= Spec.RICH;
    }

    /** Fills the axis-aligned street frontages with the plan's queue of buildings. */
    private static void fill(Plan plan, CityPlan cp) {
        if (cp.queue.isEmpty()) return;
        // queue entries are grouped by the street tag they fill ("" = every fillable street), in first-use order
        java.util.Set<String> groups = new java.util.LinkedHashSet<>();
        for (CityPlan.Queued e : cp.queue) groups.add(e.on());
        for (String group : groups) {
            List<int[]> lots = new ArrayList<>();
            for (CityPlan.Line s : cp.streets) {
                if (!s.fill() || !(s.x1() == s.x2() || s.z1() == s.z2())) continue;
                if (group.isEmpty() || group.equals(s.tag())) lots.add(new int[]{s.x1(), s.z1(), s.x2(), s.z2(), s.width()});
            }
            for (CityPlan.Ring r : cp.rings) {
                if (!r.fill() || !(group.isEmpty() || group.equals(r.tag()))) continue;
                int[][] v = polygon(r);
                for (int i = 0; i < v.length; i++) {
                    int[] a = v[i], b = v[(i + 1) % v.length];
                    if (a[0] == b[0] || a[1] == b[1]) lots.add(new int[]{a[0], a[1], b[0], b[1], r.width()});
                }
            }
            if (lots.isEmpty()) continue;
            Deque<Spec> q = new ArrayDeque<>();
            for (CityPlan.Queued e : cp.queue) {
                if (e.on().equals(group)) enqueue(q, e);
            }
            Layouts.fillStreets(plan, lots, q, cp.fillGap);
        }
    }

    /** Queue pieces are unplaced: the use and wealth are set now, the seed and slope are stamped when the lot is found. */
    private static void grammarUseQueued(Spec h, String use, String wealth) {
        com.nirncraft.tamriel.settlement.grammar.Use u = com.nirncraft.tamriel.settlement.grammar.Use.byId(use.toLowerCase(java.util.Locale.ROOT));
        if (u == null) return;
        com.nirncraft.tamriel.settlement.grammar.Wealth w = com.nirncraft.tamriel.settlement.grammar.Wealth.COMMON;
        for (com.nirncraft.tamriel.settlement.grammar.Wealth c : com.nirncraft.tamriel.settlement.grammar.Wealth.values()) {
            if (c.name().equalsIgnoreCase(wealth)) w = c;
        }
        h.use(u, w);
    }

    private static void enqueue(Deque<Spec> q, CityPlan.Queued e) {
        {
            for (int i = 0; i < e.count(); i++) {
                Spec h = new Spec(e.kind());
                h.flags = e.flags();
                if (e.w() > 0) {
                    h.size(e.w(), e.d());
                } else if (e.kind() == Archetype.HOUSE || e.kind() == Archetype.MANOR || e.kind() == Archetype.FARMHOUSE) {
                    int[] sz = HOUSE_SIZES[i % HOUSE_SIZES.length];
                    h.size(sz[0], sz[1]);
                }
                if ((h.flags & Spec.UPPER) != 0 && e.plainEvery() > 0 && i % e.plainEvery() == e.plainEvery() - 1) h.flags &= ~Spec.UPPER;
                if (e.smallEvery() > 0 && e.w() == 0 && i % e.smallEvery() == 1) h.size(9, 9);
                h.variant = e.variant();
                h.minFloor = e.raise() == 0 ? Spec.UNSET : e.raise();
                if (!e.use().isEmpty()) grammarUseQueued(h, e.use(), e.wealth());
                if (h.kind == Archetype.HOUSE || h.kind == Archetype.MANOR || h.kind == Archetype.FARMHOUSE
                        || (h.grammar() && com.nirncraft.tamriel.settlement.grammar.Use.byOrdinal(h.use).home())) {
                    if (h.w < 7) h.w = 7;
                    if (h.d < 9) h.d = 9;
                }
                if (!e.name().isEmpty() && i == 0) {
                    h.name = e.name();
                    h.flag(Spec.SIGN);
                }
                q.add(h);
            }
        }
    }

    // ------------------------------------------------------------------ quarters

    /** After zoning: recolours the specs inside each quarter (per-ring, per-district kit, culture or overlay). */
    public static void finish(Plan plan, Place p) {
        CityPlan cp = holder.plans.get(p.id());
        if (cp == null || cp.quarters.isEmpty()) return;
        for (Spec s : plan.specs) {
            int[] l = local(plan, s.centerX(), s.centerZ());
            for (CityPlan.Quarter q : cp.quarters) {
                if (!inside(q, l[0], l[1])) continue;
                s.style = styleOf(plan, s, q.culture(), q.kit(), q.overlay());
                break;
            }
        }
    }

    private static boolean inside(CityPlan.Quarter q, int lx, int lz) {
        if (q.rect() != null && q.rect().length >= 4) {
            int[] r = q.rect();
            return lx >= Math.min(r[0], r[2]) && lx <= Math.max(r[0], r[2]) && lz >= Math.min(r[1], r[3]) && lz <= Math.max(r[1], r[3]);
        }
        if (q.ring() != null && q.ring().length >= 2) {
            double d = Math.hypot(lx, lz);
            return d >= q.ring()[0] && d < q.ring()[1];
        }
        return false;
    }

    /** Local (plan frame) coordinates of a world point. */
    static int[] local(Plan plan, int wx, int wz) {
        int dx = wx - plan.cx, dz = wz - plan.cz;
        return switch (plan.back) {
            case EAST -> new int[]{dz, -dx};
            case SOUTH -> new int[]{-dx, -dz};
            case WEST -> new int[]{-dz, dx};
            default -> new int[]{dx, dz};
        };
    }

    // ------------------------------------------------------------------ export (dev tooling)

    /** The plan's specs in local coordinates, for the dev preview and offline checks. */
    public static JsonArray describe(Plan plan) {
        JsonArray out = new JsonArray();
        for (Spec s : plan.specs) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", s.kind.name().toLowerCase(java.util.Locale.ROOT));
            if (s.use >= 0) o.addProperty("use", com.nirncraft.tamriel.settlement.grammar.Use.byOrdinal(s.use).id());
            int[] a = local(plan, s.x, s.z);
            o.addProperty("x", a[0]);
            o.addProperty("z", a[1]);
            if (s.isLine()) {
                int[] b = local(plan, s.x2, s.z2);
                o.addProperty("x2", b[0]);
                o.addProperty("z2", b[1]);
            } else {
                o.addProperty("face", s.facing.getName());
            }
            o.addProperty("w", s.w);
            o.addProperty("d", s.d);
            o.addProperty("h", s.h);
            if (s.variant != 0) o.addProperty("variant", s.variant);
            if (!s.name.isEmpty()) o.addProperty("name", s.name);
            o.addProperty("culture", s.culture);
            o.addProperty("style", s.style);
            if (s.minFloor != Spec.UNSET) o.addProperty("raise", s.minFloor);
            out.add(o);
        }
        return out;
    }
}
