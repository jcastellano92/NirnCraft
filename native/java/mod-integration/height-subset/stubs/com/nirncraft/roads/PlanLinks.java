package com.nirncraft.roads;

import com.nirncraft.tamriel.PlaceType;

/** harness stub: RoadGraph.isTrailDestination(PlaceType), verbatim */
public final class PlanLinks {
    public static boolean isTrailDestination(PlaceType t) {
        return t == PlaceType.DUNGEON_FIXED || t == PlaceType.FORT || t == PlaceType.STANDING_STONE
                || t == PlaceType.DAEDRIC_SHRINE || t == PlaceType.LANDMARK || t == PlaceType.WORD_WALL
                || t == PlaceType.DRAGON_MOUND;
    }
}
