package com.skycraft.tamriel.settlement.build;

import com.skycraft.tamriel.settlement.build.landmarks.MarqueeLandmarks;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Spec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Landmark builders by string id, so a city plan ({@code cityplans/*.json}) names its silhouette ("shell",
 * "mushroom_spire", "lucky_lady"...) and a new landmark never touches the {@link Landmarks#build} switch.
 *
 * <p>{@link Spec#variant} of a LANDMARK spec stays an int (it is saved in structure pieces). The first nineteen ids
 * are the original {@link Landmarks} constants (1..19); ids registered here start at 100 and are append-only: never
 * renumber one, a world already holds them.</p>
 */
public final class LandmarkRegistry {
    /** A registered landmark: id, saved variant, and its default footprint and height. */
    public record Def(String id, int variant, int w, int d, int h) {
    }

    private static final Map<String, Def> BY_ID = new LinkedHashMap<>();
    private static final Map<Integer, Def> BY_VARIANT = new LinkedHashMap<>();

    private static void reg(String id, int variant, int w, int d, int h) {
        Def def = new Def(id, variant, w, d, h);
        if (BY_ID.put(id, def) != null || BY_VARIANT.put(variant, def) != null) {
            throw new IllegalStateException("Duplicate landmark " + id + " / " + variant);
        }
    }

    static {
        // the original set (builders in Landmarks)
        reg("gildergreen", Landmarks.GILDERGREEN, 15, 15, 18);
        reg("skyforge", Landmarks.SKYFORGE, 17, 17, 18);
        reg("talos", Landmarks.TALOS, 7, 7, 16);
        reg("arch", Landmarks.ARCH, 49, 25, 36);
        reg("college", Landmarks.COLLEGE, 51, 51, 60);
        reg("sea_stack", Landmarks.SEA_STACK, 57, 57, 4);
        reg("white_gold_tower", Landmarks.WHITE_GOLD_TOWER, 27, 27, 210);
        reg("giant_tree", Landmarks.GIANT_TREE, 61, 61, 110);
        reg("canton", Landmarks.CANTON, 31, 31, 30);
        reg("dome_temple", Landmarks.DOME_TEMPLE, 25, 25, 32);
        reg("xanmeer", Landmarks.XANMEER, 33, 33, 26);
        reg("crystal_tower", Landmarks.CRYSTAL_TOWER, 19, 19, 110);
        reg("castle", Landmarks.CASTLE, 31, 31, 32);
        reg("cliff", Landmarks.CLIFF, 55, 25, 46);
        reg("ratway", Landmarks.RATWAY, 5, 7, 8);
        reg("blue_palace", Landmarks.BLUE_PALACE, 23, 29, 26);
        reg("dome_palace", Landmarks.DOME_PALACE, 27, 27, 32);
        reg("rock", Landmarks.ROCK, 61, 61, 6);
        reg("arena", Landmarks.ARENA, 37, 37, 16);
        // marquee set (builders in MarqueeLandmarks), append only
        reg("shell", 100, 41, 17, 28);
        reg("mushroom_spire", 101, 15, 15, 40);
        reg("lucky_lady", 102, 15, 15, 36);
        reg("gate_hammer", 103, 23, 11, 32);
        reg("minaret", 104, 11, 11, 44);
        reg("twin_domes", 105, 39, 21, 34);
        reg("chapel_spire", 106, 17, 25, 50);
        reg("stone_circle", 107, 27, 27, 10);
        reg("orrery", 108, 19, 19, 24);
        reg("library_terrace", 109, 35, 27, 18);
        reg("headframe", 110, 15, 15, 26);
        reg("chain_tower", 111, 31, 11, 30);
        reg("awning_row", 112, 7, 41, 7);
        reg("canopy_platform", 113, 29, 29, 8);
        reg("glass_dome", 114, 25, 25, 20);
    }

    private LandmarkRegistry() {}

    /** The landmark called {@code id}, or null. */
    public static Def of(String id) {
        return BY_ID.get(id);
    }

    public static Def ofVariant(int variant) {
        return BY_VARIANT.get(variant);
    }

    /** Ids in registration order (validation and tooling). */
    public static Iterable<Def> all() {
        return BY_ID.values();
    }

    /** Builds registry landmarks (variants from 100) into the canvas; called by {@link Landmarks#build}'s default case. */
    static void build(Canvas c, Palette p, Spec s) {
        MarqueeLandmarks.build(c, p, s);
    }
}
