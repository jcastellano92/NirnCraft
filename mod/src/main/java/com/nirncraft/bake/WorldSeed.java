package com.nirncraft.bake;

/**
 * The fixed world seed. Nirn is the same world for everyone: new worlds default to this seed (the create-world screen,
 * the dedicated server template), and everything deterministic about it is pre-computed at build time ({@link Bake}).
 * A world with another seed still works; it just computes everything live.
 *
 * <p>The value is {@code "Nirn".hashCode()}, so typing {@code Nirn} as a seed gives the same world.</p>
 */
public final class WorldSeed {
    public static final String NAME = "Nirn";
    public static final long NIRN = 2428247L;

    private WorldSeed() {}

    public static boolean isNirn(long seed) {
        return seed == NIRN;
    }
}
