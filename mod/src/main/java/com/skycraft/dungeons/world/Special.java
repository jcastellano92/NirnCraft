package com.skycraft.dungeons.world;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Named sites whose layout differs from their theme's procedural default: the great barrows of the Dragonborn's
 * path, Kolskeggr, and the giant statue of Azura. Saved by ordinal in pieces (append only).
 */
public enum Special implements StringRepresentable {
    NONE("none"),
    /** Above Riverwood: the mountain-top temple, the Golden Claw door and the Dragonstone. */
    BLEAK_FALLS("bleak_falls"),
    /** In the marsh: a small ruin over deep caverns, the Horn of Jurgen Windcaller on its pedestal. */
    USTENGRAV("ustengrav"),
    /** The College's excavation over the old Atmoran city, and the Eye of Magnus. */
    SAARTHAL("saarthal"),
    /** Rahgot's fortress, climbing the cliff in tiers. */
    FORELHOST("forelhost"),
    /** The great maze-city of Bromjunaar, under the ruined halls (the surface is its own theme). */
    LABYRINTHIAN("labyrinthian"),
    /** The gold mine the Forsworn took. */
    KOLSKEGGR("kolskeggr"),
    /** Azura's Shrine above Winterhold: the giant statue holding the moon and the star. */
    AZURA("azura"),
    /** Ysgramor's Tomb: the Companions' barrow on the northern shore. */
    YSGRAMOR("ysgramor"),
    /** Statue to Meridia on Mount Kilkreath. */
    MERIDIA("meridia"),
    /** Castle Volkihar: the vampire lord's castle on its island. */
    VOLKIHAR("volkihar"),
    /** Shalidor's maze on the Labyrinthian surface. */
    SHALIDOR("shalidor"),
    /** A hand-authored marquee plan ({@code com.skycraft.dungeons.marquee}); the piece's param names the plan. */
    MARQUEE("marquee");

    public static final Codec<Special> CODEC = StringRepresentable.fromEnum(Special::values);

    public final String id;

    Special(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public static Special byOrdinal(int ordinal) {
        Special[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : NONE;
    }
}
