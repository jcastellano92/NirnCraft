package com.skycraft.dungeons.world;

/**
 * Who lives in a cave ({@link Theme#CAVE}): the dungeon's {@code flavor}. Named caves take theirs from the map
 * (variant {@code den}, {@code falmer_cave}...); random caves roll among the generic ones.
 */
public final class CaveRoster {
    public static final int BANDIT = 0, DEN = 1, SPIDER = 2, TROLL = 3, FALMER = 4, FORSWORN = 5, SPRIGGAN = 6,
            ICE = 7, SKEEVER = 8, SMUGGLER = 9;

    /** Rosters a random cave may roll (Falmer, Forsworn and spriggan caves belong to named places and kits). */
    private static final int[] RANDOM = {BANDIT, DEN, SPIDER, TROLL, SKEEVER, BANDIT, DEN, SMUGGLER};
    public static final int RANDOM_COUNT = RANDOM.length;

    private CaveRoster() {}

    /** The roster of a cave piece. Random caves store an index into {@link #RANDOM} offset by 100. */
    public static int of(int flavor) {
        if (flavor >= 100) return RANDOM[Math.floorMod(flavor - 100, RANDOM.length)];
        return Math.floorMod(flavor, 10);
    }

    /** Flavor value for the {@code i}-th random roll. */
    public static int random(int i) {
        return 100 + i;
    }

    /** Lit and furnished by people (torches, bedrolls) rather than beasts. */
    public static boolean inhabited(int roster) {
        return roster == BANDIT || roster == FORSWORN || roster == SMUGGLER;
    }
}
