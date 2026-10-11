package com.skycraft.tamriel.settlement;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.grammar.Blueprint;
import com.skycraft.tamriel.settlement.grammar.Grammar;
import com.skycraft.tamriel.settlement.plan.Spec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The stable way for other modules to get a building into Skycraft settlements. Call {@link #reserve} once at mod
 * construction or common setup: every settlement the planner lays out afterwards whose {@link Where} matches gets a
 * lot of that size on a street, in the right quarter, as a structure piece of its own (owner label {@code <role>#n}).
 * By default the lot holds a grammar-built hall in the settlement's style (a {@code guild_hall}); register a
 * {@link Furnisher} to furnish its rooms yourself, or a {@link Painter} to draw the whole building.
 *
 * <pre>{@code
 * SettlementPlan.reserve("thieves_guild_hall", SettlementPlan.SlotSize.MEDIUM, w -> w.capital() && w.culture() == Culture.NORD);
 * SettlementPlan.furnisher("thieves_guild_hall", (canvas, blueprint, rooms) -> { ...; return true; });
 * }</pre>
 *
 * <p>Builders run on worldgen threads, once per chunk the building touches, through a chunk-clipped {@link Canvas}:
 * describe the whole building every call and take variety from {@code canvas.roll}, never from a shared random.</p>
 */
public final class SettlementPlan {
    private SettlementPlan() {}

    /** Lot sizes (width along the street x depth). */
    public enum SlotSize {
        SMALL(7, 9), MEDIUM(11, 13), LARGE(13, 17), HUGE(17, 21);

        public final int w, d;

        SlotSize(int w, int d) {
            this.w = w;
            this.d = d;
        }
    }

    /** Quarter of town a slot prefers. */
    public enum Quarter { CENTRE, NOBLE, COMMON, POOR, CRAFT, GATE, OUTSKIRTS }

    /** What the planner knows about a settlement when it decides on a slot. */
    public record Where(String settlement, Culture culture, String size, boolean capital, boolean named, String regionId) {
    }

    /** A reserved lot. */
    public record Slot(String role, int w, int d, Quarter quarter, Predicate<Where> where) {
    }

    /** Furnishes the rooms of a reserved lot's grammar shell; return false to let the default program run. */
    @FunctionalInterface
    public interface Furnisher {
        boolean furnish(Canvas canvas, Blueprint blueprint, Grammar.Result rooms);
    }

    /** Draws a whole reserved building in the lot's local frame (x along the front, z to the back, y = 0 the floor). */
    @FunctionalInterface
    public interface Painter {
        void paint(Canvas canvas, Spec spec);
    }

    private static final List<Slot> SLOTS = new CopyOnWriteArrayList<>();
    private static final Map<String, Furnisher> FURNISHERS = new ConcurrentHashMap<>();
    private static final Map<String, Painter> PAINTERS = new ConcurrentHashMap<>();

    /** Reserves a lot of {@code size} in every town, city and capital. */
    public static void reserve(String role, SlotSize size) {
        reserve(role, size, Quarter.COMMON, w -> !w.size().equals("hamlet") && !w.size().equals("farm") && !w.size().equals("village"));
    }

    public static void reserve(String role, SlotSize size, Predicate<Where> where) {
        reserve(role, size, Quarter.COMMON, where);
    }

    public static void reserve(String role, SlotSize size, Quarter quarter, Predicate<Where> where) {
        reserve(role, size.w, size.d, quarter, where);
    }

    /** Reserves a lot of an exact footprint (odd sizes keep the door centred). */
    public static void reserve(String role, int w, int d, Quarter quarter, Predicate<Where> where) {
        for (Slot s : SLOTS) {
            if (s.role().equals(role)) {
                Skycraft.LOGGER.warn("Settlement slot {} reserved twice; keeping the first", role);
                return;
            }
        }
        SLOTS.add(new Slot(role, Math.max(5, w), Math.max(5, d), quarter, where));
    }

    public static void furnisher(String role, Furnisher f) {
        FURNISHERS.put(role, f);
    }

    public static void painter(String role, Painter p) {
        PAINTERS.put(role, p);
    }

    public static List<Slot> slots() {
        return List.copyOf(SLOTS);
    }

    /** The registered painter of a role, or null (then the grammar builds the lot). */
    public static Painter painterOf(String role) {
        return PAINTERS.get(role);
    }

    /** Runs the role's furnisher on a reserved lot's rooms; true if it furnished them. */
    public static boolean furnish(Canvas c, Blueprint b, Grammar.Result res) {
        Furnisher f = FURNISHERS.get(b.spec.name);
        if (f == null) return false;
        try {
            return f.furnish(c, b, res);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Settlement slot furnisher {} failed", b.spec.name, e);
            return true;
        }
    }
}
