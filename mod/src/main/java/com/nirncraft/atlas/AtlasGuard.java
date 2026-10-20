package com.nirncraft.atlas;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Never crash for visuals: every atlas hook outside the frame (server ticks, packets, client ticks, setup tasks)
 * runs through {@link #run}. The first failure of a feature turns that feature off for the session with exactly one
 * WARN line naming it and the error; the game goes on, and the hook becomes a no-op. Pure (AtlasGuardTest).
 */
final class AtlasGuard {
    private AtlasGuard() {}

    private static final Set<String> OFF = ConcurrentHashMap.newKeySet();
    /** Where the WARN line goes (the mod's logger; tests capture it). */
    static volatile Consumer<String> warn = m -> com.nirncraft.NirnCraft.LOGGER.warn("{}", m);

    /** Runs the hook unless its feature is off; on any Throwable turns the feature off (one WARN line). */
    static void run(String feature, Runnable hook) {
        if (OFF.contains(feature)) return;
        try {
            hook.run();
        } catch (Throwable t) {
            disable(feature, t);
        }
    }

    /** Turns a feature off with its one WARN line (later calls are silent). True the first time. */
    static boolean disable(String feature, Throwable t) {
        if (!OFF.add(feature)) return false;
        try {
            warn.accept(feature + " off for this session: " + t);
        } catch (Throwable ignored) {
            // logging must not be what takes the game down
        }
        try {  // where it came from, to the debug log (the WARN line stays one line)
            com.nirncraft.NirnCraft.LOGGER.debug(feature + " failure", t);
        } catch (Throwable ignored) {
            // (no logger: a unit test)
        }
        return true;
    }

    static boolean off(String feature) {
        return OFF.contains(feature);
    }

    /** Tests only. */
    static void reset() {
        OFF.clear();
    }
}
