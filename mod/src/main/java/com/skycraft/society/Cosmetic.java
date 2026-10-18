package com.skycraft.society;

import com.skycraft.Skycraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Faction regalia drawn over a player's skin by the client render layer. Each piece is a set of model parts
 * (hood, torso overlay, tabard skirt, cloak, shoulder pelts) textured from
 * {@code textures/entity/society/cosmetic/<id>.png}.
 */
public enum Cosmetic {
    IMPERIAL_TABARD("imperial_legion", Part.TORSO, Part.SKIRT),
    STORMCLOAK_CLOAK("stormcloaks", Part.CLOAK, Part.PELTS),
    COMPANIONS_PELT("companions", Part.HOOD, Part.CLOAK, Part.PELTS),
    COLLEGE_SASH("college", Part.TORSO, Part.SKIRT),
    THIEVES_HOOD("thieves_guild", Part.HOOD, Part.PELTS),
    BROTHERHOOD_HOOD("dark_brotherhood", Part.HOOD, Part.CLOAK),
    BARDS_CAPE("bards_college", Part.CLOAK, Part.TORSO);

    public enum Part { HOOD, TORSO, SKIRT, CLOAK, PELTS }

    public static final Cosmetic[] VALUES = values();

    public final String id;
    /** Faction id (quest module's {@code factions}) the wearer must belong to. */
    public final String faction;
    private final int parts;
    public final Identifier texture;

    Cosmetic(String faction, Part... parts) {
        this.id = name().toLowerCase(Locale.ROOT);
        this.faction = faction;
        int mask = 0;
        for (Part p : parts) mask |= 1 << p.ordinal();
        this.parts = mask;
        this.texture = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/entity/society/cosmetic/" + id + ".png");
    }

    public boolean has(Part part) {
        return (parts & (1 << part.ordinal())) != 0;
    }

    public Component displayName() {
        return Component.translatable("society.skycraft.cosmetic." + id);
    }

    @Nullable
    public static Cosmetic byId(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        for (Cosmetic c : VALUES) if (c.id.equals(id)) return c;
        return null;
    }
}
