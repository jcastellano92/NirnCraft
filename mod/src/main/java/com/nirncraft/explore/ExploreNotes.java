package com.nirncraft.explore;

import com.nirncraft.lore.LoreBooks;

import java.util.List;

/**
 * The journals, letters and scraps found at minor sites. They are ordinary lore books ({@code nirncraft:book} with a
 * {@code book} id, text in {@code assets/nirncraft/lore/books/<id>.json}, title {@code book.nirncraft.<id>}) registered
 * with weight 0 so they only turn up where the explore loot tables put them. All text is original.
 */
public final class ExploreNotes {
    /** Journals left at abandoned camps. */
    public static final List<String> JOURNALS = List.of(
            "explore_journal_snowbound", "explore_journal_deserter", "explore_journal_pilgrim",
            "explore_journal_cartographer", "explore_journal_prospector", "explore_journal_runaways");
    /** Notes that name a nearby place: reading one marks the nearest unknown location on the map. */
    public static final List<String> LEADS = List.of(
            "explore_lead_bandit_orders", "explore_lead_scholars_letter", "explore_lead_hunters_tally",
            "explore_lead_courier_rumours", "explore_lead_smugglers_note", "explore_lead_last_words");
    /** One-off notes that belong to a kind of site. */
    public static final List<String> SITE_NOTES = List.of(
            "explore_note_hermit_rules", "explore_note_failed_experiment", "explore_note_apprentice_lament",
            "explore_note_wayshrine_prayer", "explore_note_velothi_prayer", "explore_note_obelisk_rubbing",
            "explore_note_monolith_riddle", "explore_note_hist_song", "explore_note_menhir_verse",
            "explore_note_ayleid_fragment", "explore_note_akaviri_scroll", "explore_note_sea_hymn",
            "explore_note_last_orders", "explore_note_cache_ledger", "explore_note_fishers_almanac",
            "explore_note_prospector_claim", "explore_note_herb_notes", "explore_note_bathers_complaint",
            "explore_note_honey_harvest", "explore_note_sugar_ledger", "explore_note_gemcutter",
            "explore_note_dwemer_survey", "explore_note_cairn_epitaph", "explore_note_giant_hunters",
            "explore_note_ships_log");

    private ExploreNotes() {}

    static void register() {
        for (String id : JOURNALS) LoreBooks.register(id, LoreBooks.Category.NOTE, LoreBooks.BROWN, 0, 5);
        for (String id : LEADS) LoreBooks.register(id, LoreBooks.Category.NOTE, LoreBooks.SHEET, 0, 2);
        for (String id : SITE_NOTES) LoreBooks.register(id, LoreBooks.Category.NOTE, LoreBooks.SHEET, 0, 2);
        for (Uniques.UniqueSite u : Uniques.all()) {
            if (u.note() != null) LoreBooks.register(u.note(), LoreBooks.Category.LETTER, LoreBooks.SHEET, 0, 10);
        }
    }
}
