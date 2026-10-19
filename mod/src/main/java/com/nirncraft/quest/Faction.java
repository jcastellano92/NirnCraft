package com.nirncraft.quest;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The guilds, armies, houses, orders and cults of Tamriel. Ids are part of the cross-module contract
 * ({@code module("quest").factions}). Everything beyond the id, rank count, colour, home province and exclusive
 * rivals (halls, staff, requirements, benefits, rank gear) lives in {@link com.nirncraft.quest.faction.FactionData}.
 */
public enum Faction {
    // ---------------------------------------------------------------- Skyrim
    COMPANIONS("companions", 4, 0xC8A050),
    COLLEGE("college", 5, 0x5A7FD0),
    THIEVES_GUILD("thieves_guild", 6, 0x707070),
    DARK_BROTHERHOOD("dark_brotherhood", 6, 0xA02020),
    IMPERIAL_LEGION("imperial_legion", 5, 0xB04040),
    STORMCLOAKS("stormcloaks", 5, 0x3D6DB5),
    BARDS_COLLEGE("bards_college", 3, 0x9A60B0),
    GREYBEARDS("greybeards", 5, 0xB8B8C8, Group.SKYRIM),
    VIGILANTS_OF_STENDARR("vigilants_of_stendarr", 5, 0xE8DCA0, Group.SKYRIM),
    SILVER_HAND("silver_hand", 5, 0xC0C8D0, Group.SKYRIM, false),
    FORSWORN("forsworn", 5, 0x8A6A3A, Group.SKYRIM),
    // ---------------------------------------------------------------- across Tamriel
    FIGHTERS_GUILD("fighters_guild", 10, 0xB06A2A, Group.TAMRIEL),
    MAGES_GUILD("mages_guild", 10, 0x4E6FD8, Group.TAMRIEL),
    UNDAUNTED("undaunted", 6, 0x9C7A4A, Group.TAMRIEL),
    IMPERIAL_CULT("imperial_cult", 10, 0xD8C070, Group.TAMRIEL),
    THALMOR("thalmor", 5, 0x3F8F3F, Group.TAMRIEL),
    // ---------------------------------------------------------------- Cyrodiil
    BLADES("blades", 5, 0x8A8F96, Group.CYRODIIL),
    KNIGHTS_OF_THE_NINE("knights_of_the_nine", 5, 0xE6D79A, Group.CYRODIIL),
    ARENA("arena", 9, 0xA8452A, Group.CYRODIIL),
    // ---------------------------------------------------------------- Morrowind
    HOUSE_HLAALU("house_hlaalu", 10, 0xC9A23A, Group.MORROWIND),
    HOUSE_REDORAN("house_redoran", 10, 0x9E3A26, Group.MORROWIND),
    HOUSE_TELVANNI("house_telvanni", 10, 0x6B4AA0, Group.MORROWIND),
    TRIBUNAL_TEMPLE("tribunal_temple", 10, 0xC07A3A, Group.MORROWIND),
    MORAG_TONG("morag_tong", 7, 0x6E2A2A, Group.MORROWIND),
    ASHLANDERS("ashlanders", 6, 0x8A7A5A, Group.MORROWIND),
    CAMONNA_TONG("camonna_tong", 6, 0x5A2A3A, Group.MORROWIND),
    EAST_EMPIRE_COMPANY("east_empire_company", 6, 0x2F5F9F, Group.MORROWIND),
    // ---------------------------------------------------------------- the other provinces
    SHADOWSCALES("shadowscales", 5, 0x2F5F3A, Group.BLACK_MARSH),
    MOON_PRIESTS("moon_priests", 5, 0xB8B0E8, Group.ELSWEYR),
    RENRIJRA_KRIN("renrijra_krin", 5, 0xA06030, Group.ELSWEYR),
    GREEN_PACT("green_pact", 5, 0x4E8A3A, Group.VALENWOOD),
    ALDMERI_DOMINION("aldmeri_dominion", 5, 0xD4B030, Group.SUMMERSET),
    PSIJIC_ORDER("psijic_order", 5, 0x56B6A6, Group.SUMMERSET),
    CROWNS("crowns", 5, 0xC8A060, Group.HAMMERFELL),
    FOREBEARS("forebears", 5, 0x3A7A9A, Group.HAMMERFELL),
    KNIGHTS_OF_THE_DRAGON("knights_of_the_dragon", 5, 0xA02A2A, Group.HIGH_ROCK),
    KNIGHTS_OF_THE_ROSE("knights_of_the_rose", 5, 0xD06A8A, Group.HIGH_ROCK),
    ORC_STRONGHOLDS("orc_strongholds", 5, 0x5A7A3A, Group.HIGH_ROCK);

    public static final Faction[] VALUES = values();

    /** Where a faction is at home (journal grouping). */
    public enum Group {
        SKYRIM, TAMRIEL, CYRODIIL, MORROWIND, BLACK_MARSH, ELSWEYR, VALENWOOD, SUMMERSET, HAMMERFELL, HIGH_ROCK;

        public Component displayName() {
            return Component.translatable("faction.nirncraft.group." + name().toLowerCase(Locale.ROOT));
        }
    }

    public final String id;
    public final int ranks;
    public final int color;
    public final Group group;
    /** Whether players can become members (the Silver Hand only hunts them). */
    public final boolean joinable;

    Faction(String id, int ranks, int color) {
        this(id, ranks, color, Group.SKYRIM, true);
    }

    Faction(String id, int ranks, int color, Group group) {
        this(id, ranks, color, group, true);
    }

    Faction(String id, int ranks, int color, Group group, boolean joinable) {
        this.id = id;
        this.ranks = ranks;
        this.color = color;
        this.group = group;
        this.joinable = joinable;
    }

    @Nullable
    public static Faction byId(String id) {
        for (Faction f : VALUES) if (f.id.equals(id)) return f;
        return null;
    }

    public int maxRank() {
        return ranks - 1;
    }

    /** Total reputation points needed for a rank: 0, 2, 6, 12, 20, 30. */
    public static int pointsFor(int rank) {
        return rank <= 0 ? 0 : rank * (rank + 1);
    }

    /** The first faction that would never accept a member of this one (the civil war, the Great Houses...), if any. */
    @Nullable
    public Faction rival() {
        List<Faction> ex = exclusive();
        return ex.isEmpty() ? null : ex.get(0);
    }

    /**
     * Factions whose members this one turns away (symmetric): the civil war sides, the three Great Houses, the
     * Crowns and Forebears, the two assassins' guilds, the Thalmor and the Talos-worshipping orders...
     */
    public List<Faction> exclusive() {
        List<Faction> out = new ArrayList<>(2);
        for (Faction[] pair : EXCLUSIVE) {
            if (pair[0] == this) out.add(pair[1]);
            else if (pair[1] == this) out.add(pair[0]);
        }
        return out;
    }

    private static final Faction[][] EXCLUSIVE = {
            {IMPERIAL_LEGION, STORMCLOAKS},
            {HOUSE_HLAALU, HOUSE_REDORAN}, {HOUSE_HLAALU, HOUSE_TELVANNI}, {HOUSE_REDORAN, HOUSE_TELVANNI},
            {CROWNS, FOREBEARS},
            {MORAG_TONG, DARK_BROTHERHOOD}, {MORAG_TONG, SHADOWSCALES},
            {KNIGHTS_OF_THE_NINE, DARK_BROTHERHOOD},
            {MOON_PRIESTS, RENRIJRA_KRIN},
            {CAMONNA_TONG, THIEVES_GUILD}, {CAMONNA_TONG, EAST_EMPIRE_COMPANY},
            {THALMOR, BLADES}, {THALMOR, STORMCLOAKS}, {ALDMERI_DOMINION, BLADES}, {ALDMERI_DOMINION, STORMCLOAKS},
            {SILVER_HAND, COMPANIONS},
    };

    public Component displayName() {
        return Component.translatable("faction.nirncraft." + id);
    }

    public Component rankName(int rank) {
        return Component.translatable("faction.nirncraft." + id + ".rank." + Math.max(0, Math.min(maxRank(), rank)));
    }

    public Component description() {
        return Component.translatable("faction.nirncraft." + id + ".desc");
    }

    public Component howToJoin() {
        return Component.translatable("faction.nirncraft." + id + ".join");
    }

    /** What membership gives (trainers, vendors, beds, discounts, gear). */
    public Component benefits() {
        return Component.translatable("faction.nirncraft." + id + ".benefits");
    }

    /** Where the faction keeps its halls. */
    public Component halls() {
        return Component.translatable("faction.nirncraft." + id + ".halls");
    }
}
