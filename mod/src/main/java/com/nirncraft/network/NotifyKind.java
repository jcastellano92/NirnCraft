package com.nirncraft.network;

/** Kinds of HUD notifications, each with its own Skyrim-style presentation. */
public enum NotifyKind {
    /** "Mining increased to 23" (value = skill ordinal, progress = bar fill). */
    SKILL_UP,
    /** Big "LEVEL UP" banner. */
    LEVEL_UP,
    QUEST_STARTED,
    QUEST_UPDATED,
    QUEST_COMPLETED,
    QUEST_FAILED,
    LOCATION_DISCOVERED,
    LOCATION_CLEARED,
    /** Large centered title, e.g. "DRAGON SOUL ABSORBED" or "WORD OF POWER LEARNED". */
    BIG_TITLE,
    /** Small top-left message, e.g. "You don't have enough gold". */
    MESSAGE,
    /** Bounty/crime banner (red). */
    CRIME
}
