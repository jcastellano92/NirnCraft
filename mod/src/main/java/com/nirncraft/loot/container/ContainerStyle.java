package com.nirncraft.loot.container;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The look of a loot container (docs/CONTAINERS.md section 3): who built it. A blockstate property, so every style is a
 * plain block model the far-field renderer reads like any other block. Chosen at placement from the loot table's theme
 * (a Dwemer ruin's chest is Dwemer wherever it stands), else from the region's culture.
 */
public enum ContainerStyle implements StringRepresentable {
    /** Dark pine and black iron: Skyrim's holds, Atmora, the Skaal; Ancient Nord tombs. */
    NORDIC,
    /** Red-stained oak and brass: Cyrodiil, High Rock, the Imperial garrisons. */
    IMPERIAL,
    /** Resin-sealed chitin and bone: Morrowind, the Ashlands. */
    VELOTHI,
    /** Bronze plate and rivets: Dwemer ruins wherever they are. */
    DWEMER,
    /** Pale wood and gilt: Summerset, the Ayleid ruins. */
    ELVEN,
    /** Sun-bleached wood and copper: Hammerfell and Elsweyr. */
    DESERT,
    /** Rough planks and rope: camps, marshes, wrecks, anywhere poor. */
    RUSTIC;

    public static final ContainerStyle[] VALUES = values();

    private final String id = name().toLowerCase(Locale.ROOT);

    @Override
    public String getSerializedName() {
        return id;
    }

    public String id() {
        return id;
    }

    @Nullable
    public static ContainerStyle byId(@Nullable String id) {
        if (id == null) return null;
        for (ContainerStyle s : VALUES) if (s.id.equals(id)) return s;
        return null;
    }

    /** A region's building culture ({@code tamriel.Region#culture}) as a container style. */
    public static ContainerStyle ofCulture(@Nullable String culture) {
        if (culture == null) return RUSTIC;
        return switch (culture) {
            case "nord", "atmoran", "skaal", "giant" -> NORDIC;
            case "imperial", "breton", "akaviri" -> IMPERIAL;
            case "dunmer", "ashlander", "sixth_house", "daedric" -> VELOTHI;
            case "dwemer" -> DWEMER;
            case "altmer", "maormer", "ayleid" -> ELVEN;
            case "redguard", "yokudan", "khajiit" -> DESERT;
            default -> RUSTIC; // argonian, bosmer, orc, forsworn, falmer, sload, none
        };
    }

    /**
     * The style a loot table's theme imposes, or null when the table says nothing about who built the container (the
     * region's culture decides). Ruins keep their builders' look in every province.
     */
    @Nullable
    public static ContainerStyle ofTable(@Nullable String table) {
        if (table == null) return null;
        if (table.contains("dwemer")) return DWEMER;
        if (table.contains("ayleid")) return ELVEN;
        if (table.contains("velothi")) return VELOTHI;
        if (table.contains("nordic") || table.contains("draugr") || table.contains("sarcophagus")) return NORDIC;
        if (table.contains("forsworn") || table.contains("falmer") || table.contains("camp/") || table.contains("shipwreck")
                || table.contains("flotsam") || table.contains("smuggler")) return RUSTIC;
        return null;
    }
}
