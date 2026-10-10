package com.skycraft.world;

import com.skycraft.tamriel.Region;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * Display helpers for the provinces of Nirn (docs/TAMRIEL.md section 5): names ({@code province.skycraft.<id>}),
 * map tints and rough label anchors. Shared by the map, the region banners and discovery; usable on both sides.
 */
public final class Provinces {
    /** Map tint per province (parchment-friendly, low saturation). */
    private static final Map<String, Integer> TINTS = Map.ofEntries(
            Map.entry("skyrim", 0x7E8C9A),
            Map.entry("cyrodiil", 0x8C9A5E),
            Map.entry("morrowind", 0x9A6A52),
            Map.entry("black_marsh", 0x5E8A62),
            Map.entry("elsweyr", 0xB09050),
            Map.entry("valenwood", 0x5A8A4A),
            Map.entry("summerset", 0xB0A060),
            Map.entry("hammerfell", 0xB08A5A),
            Map.entry("high_rock", 0x7A8A6E),
            Map.entry("atmora", 0xA0B0C0),
            Map.entry("akavir", 0x9A7A8A),
            Map.entry("yokuda", 0xB89A6A),
            Map.entry("pyandonea", 0x6A9A8A),
            Map.entry("thras", 0x7AA0A8),
            Map.entry("realm", 0x8A8A7A));

    /** Where a province's name goes on the map when no region data says otherwise (block x, z). */
    private static final Map<String, int[]> ANCHORS = Map.ofEntries(
            Map.entry("skyrim", new int[]{-200, -600}),
            Map.entry("cyrodiil", new int[]{700, 6900}),
            Map.entry("morrowind", new int[]{6900, 1500}),
            Map.entry("black_marsh", new int[]{7300, 8200}),
            Map.entry("elsweyr", new int[]{1300, 11800}),
            Map.entry("valenwood", new int[]{-4800, 11000}),
            Map.entry("summerset", new int[]{-10600, 12300}),
            Map.entry("hammerfell", new int[]{-6800, 6200}),
            Map.entry("high_rock", new int[]{-7400, 900}),
            Map.entry("atmora", new int[]{0, -11000}),
            Map.entry("akavir", new int[]{17000, 3000}),
            Map.entry("yokuda", new int[]{-19000, 7000}),
            Map.entry("pyandonea", new int[]{-17000, 19000}),
            Map.entry("thras", new int[]{-15000, 16500}));

    private Provinces() {}

    public static Component name(String province) {
        return Component.translatable("province.skycraft." + province);
    }

    public static int tint(String province) {
        return TINTS.getOrDefault(province, 0x8A8A7A);
    }

    /** Label anchor of a province, or null for generated realms. */
    @org.jetbrains.annotations.Nullable
    public static int[] anchor(String province) {
        return ANCHORS.get(province);
    }

    public static Map<String, int[]> anchors() {
        return ANCHORS;
    }

    /** Whether a region is open water (seas never get "Entering" banners or labels of their own). */
    public static boolean isSea(Region region) {
        return "sea".equals(region.kind()) || "sea".equals(region.province());
    }
}
