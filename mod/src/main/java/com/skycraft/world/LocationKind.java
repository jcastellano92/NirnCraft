package com.skycraft.world;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a discovered structure "is" in Skyrim terms. Whether a structure is a location at all and whether it is a town
 * comes from the structure tags in {@link LocationTags}; the finer kind is derived from keywords in the structure's
 * registry path, so vanilla and modded structures both get a sensible kind.
 */
public enum LocationKind {
    // icon index = position in textures/gui/map_icons.png (8 per row)
    TOWN("town", 0, false, true, 0xE8D8A8),
    RUIN("ruin", 1, true, true, 0xC8B898),
    CAVE("cave", 2, true, true, 0xB0A890),
    MINE("mine", 3, true, true, 0xB8A070),
    FORT("fort", 4, true, true, 0xC8C0A8),
    TOWER("tower", 5, true, true, 0xC8C0A8),
    CAMP("camp", 6, true, true, 0xD0A070),
    BARROW("barrow", 7, true, true, 0xA8B0C0),
    DWEMER("dwemer", 8, true, true, 0xE0B050),
    SHRINE("shrine", 9, true, true, 0x80B0C8),
    WRECK("wreck", 10, false, true, 0x90A8B8),
    OBLIVION_GATE("oblivion_gate", 11, false, true, 0xE05030),
    CITADEL("citadel", 12, true, true, 0xE05030),
    HUT("hut", 13, false, true, 0xC0A880),
    MANOR("manor", 14, true, true, 0xD8C8A0),
    TEMPLE("temple", 15, true, true, 0xE8E0C0),
    INN("inn", 16, false, true, 0xE8C080),
    LANDMARK("landmark", 17, false, true, 0xB8B8B0),
    HALL_OF_VALOR("hall_of_valor", 18, true, true, 0xF0D060),
    TOMB("tomb", 19, true, true, 0xE0C080),
    SANCTUM("sanctum", 20, true, true, 0xA8B0C0),
    BANDIT_CAMP("bandit_camp", 21, true, true, 0xD08060),
    DAEDRIC_TOWER("daedric_tower", 22, true, true, 0xE05030);

    public static final int QUEST_ICON = 23;
    public static final int UNKNOWN_ICON = 24;

    /** Structures too small or too common to be worth a discovery banner. */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "buried_treasure", "nether_fossil", "fossil", "end_gateway", "desert_well", "ocean_ruin_cold_small",
            "geode", "meteor", "boulder", "rock", "lamp", "lamppost", "sign", "well", "grave", "statue"));

    public final String id;
    public final int icon;
    /** Whether killing every hostile inside triggers "CLEARED". */
    public final boolean clearable;
    /** True: the player must stand inside one of the structure's pieces (underground places); false: anywhere in its bounds. */
    public final boolean insidePieces;
    public final int color;

    LocationKind(String id, int icon, boolean clearable, boolean insidePieces, int color) {
        this.id = id;
        this.icon = icon;
        this.clearable = clearable;
        this.insidePieces = insidePieces;
        this.color = color;
    }

    public Component displayName() {
        return Component.translatable("location.skycraft.kind." + id);
    }

    /** Underground kinds put fast travellers at the surface above their entrance. */
    public boolean underground() {
        return insidePieces;
    }

    public static LocationKind byId(String id) {
        for (LocationKind k : values()) if (k.id.equals(id)) return k;
        return LANDMARK;
    }

    /**
     * THE classifier (discovery, map, roads, signposts and loot all use it): {@code null} for structures that are not
     * locations ({@code #skycraft:ignored_landmarks} or too small), {@link #TOWN}/{@link #INN} exactly for
     * {@code #skycraft:towns}, otherwise a kind from the registry path that is never a town.
     */
    @org.jetbrains.annotations.Nullable
    public static LocationKind classify(Holder<Structure> structure, ResourceKey<Level> dimension) {
        if (structure.is(LocationTags.IGNORED_LANDMARKS)) return null;
        ResourceLocation key = structure.unwrapKey().map(ResourceKey::location).orElse(null);
        if (key == null) return null;
        if (structure.is(LocationTags.TOWNS)) {
            return key.getPath().toLowerCase(Locale.ROOT).contains("tavern") ? INN : TOWN;
        }
        LocationKind kind = classify(key, dimension);
        if (kind == TOWN || kind == INN) {
            // named like a town but not an inhabited one (bandit villages, ruined towns, roadside shacks...)
            String path = key.getPath().toLowerCase(Locale.ROOT);
            return path.contains("bandit") || path.contains("illager") ? BANDIT_CAMP : RUIN;
        }
        return kind;
    }

    /**
     * Keyword-only classification of a structure registry key, or {@code null} if it should not be discovered at all.
     * Prefer {@link #classify(Holder, ResourceKey)}, which also honours the structure tags.
     */
    public static LocationKind classify(ResourceLocation key, ResourceKey<Level> dimension) {
        String path = key.getPath().toLowerCase(Locale.ROOT);
        String[] tokens = path.split("[/_\\-.]+");
        Set<String> t = new HashSet<>(Arrays.asList(tokens));
        if (SKIP.contains(path) || t.contains("fossil") || path.contains("buried_treasure") || path.contains("end_gateway")
                || path.contains("desert_well")) {
            return null;
        }
        // vanilla structures first (exact keywords)
        if (path.contains("village")) return TOWN;
        if (path.contains("ancient_city")) return DWEMER;
        if (path.contains("mineshaft")) return MINE;
        if (path.contains("stronghold")) return SANCTUM;
        if (path.contains("desert_pyramid")) return TOMB;
        if (path.contains("jungle_pyramid") || path.contains("jungle_temple")) return RUIN;
        if (path.contains("monument")) return SHRINE;
        if (path.contains("trail_ruins")) return BARROW;
        if (path.contains("igloo") || path.contains("swamp_hut") || path.contains("witch_hut")) return HUT;
        if (path.contains("mansion")) return MANOR;
        if (path.contains("pillager_outpost") || t.contains("outpost")) return BANDIT_CAMP;
        if (path.contains("shipwreck") || t.contains("wreck")) return WRECK;
        if (path.contains("ruined_portal") || t.contains("portal")) return OBLIVION_GATE;
        if (path.equals("fortress") || path.contains("nether_fortress")
                || (t.contains("fortress") && dimension == Level.NETHER)) return CITADEL;
        if (path.contains("bastion")) return DAEDRIC_TOWER;
        if (path.contains("end_city")) return HALL_OF_VALOR;
        if (path.contains("ocean_ruin")) return RUIN;
        // modded keywords
        if (any(t, "dungeon", "crypt", "crypts", "catacomb", "catacombs", "barrow", "tomb", "mausoleum", "necropolis", "labyrinth")) return BARROW;
        if (any(t, "dwemer", "dwarven", "dwarf")) return DWEMER;
        if (any(t, "tower", "towers", "spire", "watchtower", "lighthouse")) return TOWER;
        if (any(t, "castle", "keep", "fort", "fortress", "citadel", "stronghold", "bastion", "garrison", "barracks")) return FORT;
        if (any(t, "camp", "campsite", "encampment", "hideout")) return CAMP;
        if (any(t, "cave", "caves", "cavern", "grotto", "den", "lair", "burrow", "hollow")) return CAVE;
        if (any(t, "mine", "mines", "quarry")) return MINE;
        if (any(t, "temple", "shrine", "altar", "chapel", "church", "monastery", "cathedral", "sanctuary")) return TEMPLE;
        if (any(t, "ruin", "ruins", "ruined", "remains", "abandoned")) return RUIN;
        if (any(t, "tavern", "inn", "pub")) return INN;
        if (any(t, "mansion", "manor", "estate", "villa", "house", "cottage", "cabin", "hut", "shack", "farm")) {
            return t.contains("mansion") || t.contains("manor") || t.contains("estate") || t.contains("villa") ? MANOR : HUT;
        }
        if (any(t, "town", "hamlet", "settlement", "city")) return TOWN;
        if (any(t, "pyramid")) return TOMB;
        if (dimension == Level.NETHER) return CITADEL;
        if (dimension == Level.END) return HALL_OF_VALOR;
        return LANDMARK;
    }

    private static boolean any(Set<String> tokens, String... words) {
        for (String w : words) if (tokens.contains(w)) return true;
        return false;
    }
}
