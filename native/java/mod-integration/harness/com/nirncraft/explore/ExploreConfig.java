package com.nirncraft.explore;

/**
 * harness stub (ahead of the mod's class on the harness class path): the real ExploreConfig builds a NeoForge
 * ModConfigSpec, whose night-config library is not on the offline class path. Offline the config is never loaded,
 * so the density is the mod's own default before loading: 1 (see the real densityScale()).
 */
public final class ExploreConfig {
    private ExploreConfig() {}

    public static double densityScale() {
        return 1.0;
    }
}
