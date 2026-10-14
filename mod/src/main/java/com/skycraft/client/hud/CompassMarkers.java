package com.skycraft.client.hud;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side markers shown on the Skyrim compass. Each module owns a "source" (e.g. {@code "quest"},
 * {@code "locations"}) and replaces its whole marker list whenever it changes.
 */
public final class CompassMarkers {
    public enum Shape {
        QUEST, LOCATION, DISCOVERED, ENEMY, PLAYER, DOOR,
        /** A hostile that lost the player and is looking for them (hollow, yellow-orange). */
        SEARCHING
    }

    /** Priority of the tracked quest's marker: it wins every near-duplicate and is drawn on top. */
    public static final int TRACKED_PRIORITY = 10;

    /**
     * @param range    markers farther than this are hidden (0 = always shown, e.g. quest targets)
     * @param icon     index into the map icon sheet ({@code textures/gui/map_icons.png}) drawn instead of the plain
     *                 shape, or -1
     * @param priority higher wins when two markers of the same layer sit within 4 blocks, and is drawn later
     *                 (on top)
     */
    public record Marker(Vec3 pos, Shape shape, int color, String label, double range, int icon, int priority) {
        public Marker(Vec3 pos, Shape shape, int color, String label, double range, int icon) {
            this(pos, shape, color, label, range, icon, 0);
        }

        public Marker(Vec3 pos, Shape shape, int color, String label, double range) {
            this(pos, shape, color, label, range, -1, 0);
        }
    }

    private static final Map<String, List<Marker>> SOURCES = new ConcurrentHashMap<>();
    /** All markers with near-duplicates (within 4 blocks) removed; rebuilt only when a source changes. */
    private static volatile List<Marker> deduplicated;

    private CompassMarkers() {}

    public static void set(String source, List<Marker> markers) {
        List<Marker> copy = List.copyOf(markers);
        if (copy.equals(SOURCES.put(source, copy))) return;
        deduplicated = null;
    }

    public static void clear(String source) {
        if (SOURCES.remove(source) != null) deduplicated = null;
    }

    public static List<Marker> all() {
        List<Marker> out = new ArrayList<>();
        SOURCES.values().forEach(out::addAll);
        return out;
    }

    /** Draw layer: places below party members below hostiles below quest targets. */
    private static int layer(Marker m) {
        return switch (m.shape()) {
            case LOCATION, DISCOVERED, DOOR -> 0;
            case PLAYER -> 1;
            case ENEMY, SEARCHING -> 2;
            case QUEST -> 3;
        };
    }

    /**
     * {@link #all()} without markers that sit within 4 blocks of a better one of the same layer (cached), in draw
     * order. A quest target is never swallowed by the place marker it points at (they were once compared with each
     * other, and whichever source happened to come first won), the tracked quest beats the other quests, and
     * hostiles are never merged: every one that is hunting you is shown.
     */
    public static List<Marker> deduplicated() {
        List<Marker> cached = deduplicated;
        if (cached != null) return cached;
        List<Marker> in = all();
        in.sort(Comparator.comparingInt((Marker m) -> -m.priority())); // stable: best first
        List<Marker> out = new ArrayList<>();
        for (Marker m : in) {
            boolean exists = false;
            if (m.shape() != Shape.ENEMY && m.shape() != Shape.SEARCHING) {
                int l = layer(m);
                for (Marker ex : out) {
                    if (layer(ex) == l && ex.pos().distanceToSqr(m.pos()) < 16.0) {
                        exists = true;
                        break;
                    }
                }
            }
            if (!exists) out.add(m);
        }
        // draw order: lower layers first, higher priority later (on top)
        out.sort(Comparator.comparingInt(CompassMarkers::layer).thenComparingInt(Marker::priority));
        cached = List.copyOf(out);
        deduplicated = cached;
        return cached;
    }
}
