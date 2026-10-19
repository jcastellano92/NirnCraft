package com.nirncraft.homestead;

import com.nirncraft.crafting.recipe.Cost;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hearthfire's building plans, drafted at the drafting table: the small house, then the main hall (the small house
 * becomes its entryway), then one of three rooms for each wing (west: bedrooms, greenhouse or enchanter's tower;
 * north: storage room, trophy room or alchemy laboratory; east: kitchen, library or armory), a cellar, and the yard
 * (garden, animal pen, workshop). Each plan needs Hearthfire materials: sawn logs, quarried stone, clay, nails,
 * hinges, locks and iron fittings, plus a few extras.
 *
 * @param slot     plans sharing a slot exclude each other (a wing holds one room)
 * @param requires plans that must be built first
 */
public record HomesteadPlans(String id, String slot, List<String> requires, List<Cost> costs) {
    public static final List<String> SLOTS = List.of("house", "hall", "west", "north", "east", "cellar", "garden", "pen", "workshop");

    private static final Map<String, HomesteadPlans> BY_ID = new LinkedHashMap<>();

    static {
        Supplier<ItemLike> log = HomesteadRegistry.SAWN_LOG::get;
        Supplier<ItemLike> stone = HomesteadRegistry.QUARRIED_STONE::get;
        Supplier<ItemLike> nails = HomesteadRegistry.NAILS::get;
        Supplier<ItemLike> hinge = HomesteadRegistry.HINGE::get;
        Supplier<ItemLike> lock = HomesteadRegistry.LOCK::get;
        Supplier<ItemLike> fittings = HomesteadRegistry.IRON_FITTINGS::get;
        Supplier<ItemLike> clay = () -> Items.CLAY_BALL;
        Supplier<ItemLike> glass = () -> Items.GLASS_PANE;

        plan("small_house", "house", List.of(), c(stone, 8), c(log, 6), c(nails, 16), c(hinge, 2), c(lock, 1));
        plan("main_hall", "hall", List.of("small_house"), c(stone, 20), c(log, 16), c(clay, 8), c(nails, 40), c(hinge, 4),
                c(fittings, 2), c(lock, 1), c(glass, 8));
        List<String> hall = List.of("main_hall");
        plan("bedrooms", "west", hall, c(stone, 10), c(log, 10), c(clay, 4), c(nails, 24), c(hinge, 2), c(fittings, 2),
                Cost.tag(ItemTags.WOOL, () -> com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.asItem(), 8));
        plan("greenhouse", "west", hall, c(stone, 8), c(log, 8), c(nails, 20), c(hinge, 1), c(glass, 24), c(() -> Items.DIRT, 12));
        plan("enchanters_tower", "west", hall, c(stone, 16), c(log, 12), c(clay, 6), c(nails, 30), c(hinge, 2), c(fittings, 2),
                c(glass, 8), c(() -> Items.LAPIS_LAZULI, 4));
        plan("storage_room", "north", hall, c(stone, 10), c(log, 12), c(clay, 4), c(nails, 24), c(hinge, 4), c(lock, 2), c(fittings, 2));
        plan("trophy_room", "north", hall, c(stone, 12), c(log, 8), c(clay, 4), c(nails, 20), c(hinge, 2), c(fittings, 2),
                c(() -> Items.BONE, 4));
        plan("alchemy_laboratory", "north", hall, c(stone, 10), c(log, 8), c(clay, 6), c(nails, 20), c(hinge, 2), c(fittings, 2),
                c(glass, 6), c(() -> Items.GLASS_BOTTLE, 4));
        plan("kitchen", "east", hall, c(stone, 12), c(log, 8), c(clay, 6), c(nails, 20), c(hinge, 2), c(fittings, 2),
                c(() -> Items.IRON_INGOT, 2));
        plan("library", "east", hall, c(stone, 10), c(log, 10), c(clay, 4), c(nails, 24), c(hinge, 2), c(fittings, 2),
                c(() -> Items.BOOK, 6));
        plan("armory", "east", hall, c(stone, 12), c(log, 8), c(clay, 4), c(nails, 20), c(hinge, 2), c(fittings, 4),
                c(() -> Items.IRON_INGOT, 4));
        plan("cellar", "cellar", hall, c(stone, 16), c(log, 4), c(nails, 12), c(hinge, 1), c(lock, 1));
        plan("garden", "garden", List.of("small_house"), c(log, 4), c(nails, 8), c(() -> Items.DIRT, 8));
        plan("animal_pen", "pen", List.of("small_house"), c(log, 8), c(nails, 16), c(hinge, 1), c(() -> Items.HAY_BLOCK, 2));
        plan("workshop", "workshop", List.of(), c(stone, 8), c(log, 4), c(clay, 6), c(nails, 8), c(fittings, 2), c(() -> Items.IRON_INGOT, 2));
    }

    private static Cost c(Supplier<ItemLike> item, int count) {
        return Cost.of(item, count);
    }

    private static void plan(String id, String slot, List<String> requires, Cost... costs) {
        BY_ID.put(id, new HomesteadPlans(id, slot, requires, List.of(costs)));
    }

    public static List<HomesteadPlans> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_ID.values()));
    }

    public static HomesteadPlans get(String id) {
        return BY_ID.get(id);
    }

    /** The built plan occupying this plan's slot, or null. */
    public String occupant(java.util.Collection<String> built) {
        for (String b : built) {
            HomesteadPlans p = BY_ID.get(b);
            if (p != null && p.slot.equals(slot)) return b;
        }
        return null;
    }

    /** Why this plan can't be built yet ({@code requires:<id>}, {@code slot:<id>}, {@code built}), or null. */
    public String blocker(java.util.Collection<String> built) {
        if (built.contains(id)) return "built";
        for (String r : requires) if (!built.contains(r)) return "requires:" + r;
        String occ = occupant(built);
        return occ == null ? null : "slot:" + occ;
    }

    /** How many of a cost's items this plan needs for a builder (Hearthfire perk: a quarter less, at least one). */
    public static int needed(Cost cost, boolean carpenter) {
        return carpenter ? Math.max(1, (int) Math.ceil(cost.count() * 0.75)) : cost.count();
    }
}
