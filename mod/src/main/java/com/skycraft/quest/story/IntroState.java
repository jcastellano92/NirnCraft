package com.skycraft.quest.story;

import javax.annotation.Nullable;

/**
 * Where a player is in the opening, saved per player ({@code module("intro").state}) so a relog resumes it:
 * JOINED (placed in the cottage, character not made) -> CHARACTER (the character screen is up) -> LOADING (the world
 * loads behind a screen) -> IN_COTTAGE (free to look around, walk to the window) -> CUTSCENE (the dragon attack, only
 * this player sees it) -> KEY_GIVEN (the cottage key is in the pack) -> LEFT (out of the door; one way).
 */
public enum IntroState {
    JOINED, CHARACTER, LOADING, IN_COTTAGE, CUTSCENE, KEY_GIVEN, LEFT;

    private static final IntroState[] VALUES = values();

    /** Still inside the opening: hidden from other players, door locked, teleports blocked. */
    public boolean inIntro() {
        return this != LEFT;
    }

    /** The dragon has not attacked yet for this player (no Helgen smoke for them). */
    public boolean beforeAttack() {
        return ordinal() <= CUTSCENE.ordinal();
    }

    @Nullable
    public static IntroState byName(String name) {
        for (IntroState s : VALUES) if (s.name().equals(name)) return s;
        return null;
    }

    public static IntroState byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : LEFT;
    }
}
