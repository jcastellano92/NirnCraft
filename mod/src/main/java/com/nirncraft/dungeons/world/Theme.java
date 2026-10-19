package com.nirncraft.dungeons.world;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * The kind of dungeon a {@link DungeonStructure} builds. One structure type serves every theme; the structure JSON
 * picks the theme ({@code "theme": "barrow"}).
 */
public enum Theme implements StringRepresentable {
    //               id                  style           rooms  depth   room height  puzzle
    BARROW("barrow", Style.BUILT, 9, 12, 16, 24, 5, 7, true),
    CAVE("cave", Style.NATURAL, 7, 11, 24, 32, 4, 6, false),
    DWEMER("dwemer", Style.BUILT, 9, 12, 20, 30, 6, 8, true),
    VAMPIRE_LAIR("vampire_lair", Style.NATURAL, 6, 9, 24, 30, 4, 6, false),
    NECROMANCER_LAIR("necromancer_lair", Style.NATURAL, 6, 9, 24, 30, 4, 6, false),
    HAGRAVEN_LAIR("hagraven_lair", Style.NATURAL, 6, 8, 22, 30, 4, 6, false),
    MINE("mine", Style.NATURAL, 7, 10, 24, 32, 4, 5, false),
    FORT("fort", Style.BUILT, 4, 6, 18, 26, 4, 5, false),
    GIANT_CAMP("giant_camp", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    DRAGON_LAIR("dragon_lair", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    DAEDRIC_SHRINE("daedric_shrine", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    // ---- appended: saved by ordinal, never reorder
    // surface sites of Skyrim's map (and the wilds beyond)
    WATCHTOWER("watchtower", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    SHIPWRECK("shipwreck", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    GROVE("grove", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    BANDIT_CAMP("bandit_camp", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    MILITARY_CAMP("military_camp", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    CABIN("cabin", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    LIGHTHOUSE("lighthouse", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    LANDMARK("landmark", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    STANDING_STONE("standing_stone", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    WORD_WALL("word_wall", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    DRAGON_MOUND("dragon_mound", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    NORDIC_RUIN("nordic_ruin", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    FORSWORN_CAMP("forsworn_camp", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    GREAT_LIFT("great_lift", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    PRINCE_SHRINE("prince_shrine", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    MAGE_TOWER("mage_tower", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    // culture tombs and ruins: masonry like a barrow, dressed by the kit
    RUIN("ruin", Style.BUILT, 8, 12, 16, 26, 5, 8, true),
    // unique sites
    HIGH_HROTHGAR("high_hrothgar", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    THROAT_SUMMIT("throat_summit", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    STEPS("steps", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    SKY_HAVEN("sky_haven", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    LABYRINTHIAN("labyrinthian", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    BLACKREACH("blackreach", Style.SURFACE, 0, 0, 0, 0, 0, 0, false),
    BRETON_KEEP_RUIN("breton_keep_ruin", Style.BUILT, 6, 9, 18, 26, 5, 7, true),
    SCORPION_DEN("scorpion_den", Style.NATURAL, 6, 9, 22, 30, 4, 6, false);

    public static final Codec<Theme> CODEC = StringRepresentable.fromEnum(Theme::values);

    /** How rooms are shaped: masonry boxes, noise-carved caverns, or a single surface site. */
    public enum Style { BUILT, NATURAL, SURFACE }

    public final String id;
    public final Style style;
    public final int minRooms;
    public final int maxRooms;
    public final int minDepth;
    public final int maxDepth;
    public final int minHeight;
    public final int maxHeight;
    /** Whether the room before the boss is sealed with a lever-operated gate. */
    public final boolean puzzle;

    Theme(String id, Style style, int minRooms, int maxRooms, int minDepth, int maxDepth, int minHeight, int maxHeight, boolean puzzle) {
        this.id = id;
        this.style = style;
        this.minRooms = minRooms;
        this.maxRooms = maxRooms;
        this.minDepth = minDepth;
        this.maxDepth = maxDepth;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.puzzle = puzzle;
    }

    public boolean underground() {
        return style != Style.SURFACE;
    }

    public boolean natural() {
        return style == Style.NATURAL;
    }

    /** Lairs share the cave carving with their own dressing. */
    public boolean lair() {
        return this == VAMPIRE_LAIR || this == NECROMANCER_LAIR || this == HAGRAVEN_LAIR;
    }

    /** Surface sites with a cellar or crypt below them (built as surface + underground pieces). */
    public boolean hasUndercroft() {
        return this == FORT || this == BRETON_KEEP_RUIN;
    }

    /** Surface sites placed on open water or the shore rather than dry land. */
    public boolean coastal() {
        return this == SHIPWRECK || this == LIGHTHOUSE;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public static Theme byOrdinal(int ordinal) {
        Theme[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : BARROW;
    }
}
