package com.skycraft.client.hud;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side markers shown on the Skyrim compass. Each module owns a "source" (e.g. {@code "quest"},
 * {@code "locations"}) and replaces its whole marker list whenever it changes.
 */
public final class CompassMarkers {
    public enum Shape { QUEST, LOCATION, DISCOVERED, ENEMY, PLAYER, DOOR }

    /**
     * @param range markers farther than this are hidden (0 = always shown, e.g. quest targets)
     */
    public record Marker(Vec3 pos, Shape shape, int color, String label, double range) {
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

    /** {@link #all()} without markers that sit within 4 blocks of an earlier one (cached). */
    public static List<Marker> deduplicated() {
        List<Marker> cached = deduplicated;
        if (cached != null) return cached;
        List<Marker> out = new ArrayList<>();
        for (Marker m : all()) {
            boolean exists = false;
            for (Marker ex : out) {
                if (ex.pos().distanceToSqr(m.pos()) < 16.0) {
                    exists = true;
                    break;
                }
            }
            if (!exists) out.add(m);
        }
        cached = List.copyOf(out);
        deduplicated = cached;
        return cached;
    }
}
