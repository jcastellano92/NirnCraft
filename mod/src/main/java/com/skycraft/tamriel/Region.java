package com.skycraft.tamriel;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A political/geographic region of Nirn: a Skyrim hold, a Cyrodiil county, a Morrowind district, a foreign land's
 * region, or a generated region of the endless lands. See docs/TAMRIEL.md section 5.
 *
 * @param id       stable id, e.g. {@code skyrim/whiterun}, {@code akavir/tsaesci_3}, {@code realm/x12_z-4/2}
 * @param province {@code skyrim}, {@code cyrodiil}, ... {@code realm}
 * @param kind     {@code hold}, {@code county}, {@code district}, {@code kingdom}, {@code realm}, {@code sea}, {@code dimension}
 * @param culture  building/NPC culture, e.g. {@code nord}, {@code imperial}, {@code dunmer}
 * @param capital  place id of the region's capital, if any
 */
public record Region(String id, String province, String kind, String culture, Component name,
                     int minLevel, int maxLevel, String palette, String music, String weather,
                     @Nullable String capital) {

    /** Short id without the province prefix, e.g. {@code whiterun}. */
    public String shortId() {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    public boolean isSkyrimHold() {
        return "skyrim".equals(province) && "hold".equals(kind);
    }
}
