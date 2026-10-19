package com.nirncraft.seas;

import com.nirncraft.lore.LoreBooks;
import com.nirncraft.lore.LoreBooks.Category;

/**
 * The books and notes found at the sea and coast sites (texts in {@code assets/nirncraft/lore/books/seas_*.json}).
 * Weight 0: they only turn up where the seas loot tables put them (sunken ruins, lairs, wrecks, coves...).
 */
final class SeasLore {
    private SeasLore() {}

    static void register() {
        LoreBooks.register("seas_drowned_yokuda", Category.HISTORY, LoreBooks.BROWN, 0, 40, "hammerfell");
        LoreBooks.register("seas_sload_tablet", Category.NOTE, LoreBooks.SHEET, 0, 25, "");
        LoreBooks.register("seas_dreugh_wax", Category.GUIDE, LoreBooks.GREEN, 0, 20, "morrowind");
        LoreBooks.register("seas_pearl_diver", Category.LETTER, LoreBooks.SHEET, 0, 5, "morrowind");
        LoreBooks.register("seas_maormer_orders", Category.NOTE, LoreBooks.SHEET, 0, 20, "");
        LoreBooks.register("seas_dwemer_sealed_hall", Category.DWEMER, LoreBooks.SHEET, 0, 30, "");
        LoreBooks.register("seas_ship_graves", Category.HISTORY, LoreBooks.RED, 0, 30, "skyrim");
        LoreBooks.register("seas_keeper_log", Category.NOTE, LoreBooks.SHEET, 0, 5, "");
        LoreBooks.register("seas_hermit_diary", Category.NOTE, LoreBooks.SHEET, 0, 5, "");
        LoreBooks.register("seas_pirate_will", Category.NOTE, LoreBooks.SHEET, 0, 10, "");
        LoreBooks.register("seas_smuggler_ledger", Category.NOTE, LoreBooks.SHEET, 0, 10, "");
        LoreBooks.register("seas_ghost_ship", Category.POEM, LoreBooks.BLACK, 0, 15, "skyrim");
        LoreBooks.register("seas_serpent_warning", Category.NOTE, LoreBooks.SHEET, 0, 5, "");
    }
}
