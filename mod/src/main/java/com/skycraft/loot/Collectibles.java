package com.skycraft.loot;

import com.skycraft.core.PlayerData;
import com.skycraft.core.Skill;
import com.skycraft.economy.ItemValues;
import com.skycraft.economy.SkillBookItem;
import com.skycraft.tamriel.Place;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Skycraft's long-term collecting goals. Every collection is a list of entries, a place each entry is found (a named
 * place of {@code places/*.json}, a place-id prefix such as {@code shrine_azura_*}, or a province for random finds)
 * and a reward for completing it. Progress is per character in {@code module("loot")}: {@code found} (entry key ->
 * game time) and {@code rewarded} (collection id -> 1); the Journal's Collections tab reads the synced copy.
 *
 * <ul>
 *   <li><b>Dragon Priest masks</b>: dropped by the named priests; the eight together reveal Konahrik.</li>
 *   <li><b>Daedric artifacts</b>: offered at the Princes' shrines, one per Prince, for a worthy (levelled) hero.</li>
 *   <li><b>Artifacts of legend</b>: Wuuthrad in Ysgramor's Tomb, the Staff of Magnus in Labyrinthian...</li>
 *   <li><b>Stones of Barenziah</b>: 24 "unusual gems" in named dungeons of Skyrim; the Crown is the reward.</li>
 *   <li><b>Black Books</b>: seven, in Solstheim's ruins; each one read grants a perk point and skill.</li>
 *   <li><b>Elder Scrolls</b>: Dragon (Tower of Mzark), Blood (Dimhollow Crypt), Sun (the Forgotten Vale's gate).</li>
 *   <li><b>Skill books</b>: five volumes per skill; reading all five of a skill grants one more level.</li>
 *   <li><b>Regional treasures</b>: five per province (Ayleid welkynd and varla stones, Hist amber...).</li>
 *   <li><b>Legendary uniques</b>: {@link LegendaryUniques}, placed by the world's explorers by id.</li>
 * </ul>
 */
public final class Collectibles {
    public static final String MODULE = "loot";
    /** NBT key on NBT-identified collectibles: {@code "<collection>/<entry>"}. */
    public static final String NBT = "skycraft_collectible";

    private Collectibles() {}

    public enum Kind { CARRY, READ }

    /**
     * One collectible.
     *
     * @param places   place ids (or {@code prefix*}) where {@code skycraft:place_collectibles} hands it out
     * @param province gear key ({@link com.skycraft.crafting.RegionalGear#gearKey}) of random finds, or null
     * @param minLevel character level from which loot hands it out
     * @param value    gold value of the stack (0: the item's own)
     * @param color    tint of the item's tinted layer (treasures), -1 for none
     */
    public record Entry(String collection, String id, @Nullable Supplier<Item> item, List<String> places,
                        @Nullable String province, int minLevel, int value, int color) {
        public String key() {
            return collection + "/" + id;
        }

        public Component name() {
            if (item != null) return item.get().getName(new ItemStack(item.get()));
            if (collection.equals(LegendaryUniques.COLLECTION)) return Component.translatable("unique.skycraft." + id);
            return Component.translatable("collectible.skycraft." + collection + "." + id);
        }

        /** A new stack of this collectible (empty for read-only entries such as skill book volumes). */
        public ItemStack create() {
            if (collection.equals(LegendaryUniques.COLLECTION)) return LegendaryUniques.create(id);
            if (collection.equals(SKILL_BOOKS)) return LootFunctions.skillBook(Skill.byId(id.substring(0, id.lastIndexOf('_'))),
                    Integer.parseInt(id.substring(id.lastIndexOf('_') + 1)));
            if (item == null) return ItemStack.EMPTY;
            ItemStack s = new ItemStack(item.get());
            if (s.getItem() instanceof CollectibleItem || s.getItem() instanceof BlackBookItem) {
                com.skycraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putString(NBT, key()));
                if (value > 0) com.skycraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putInt(ItemValues.VALUE_NBT, value));
            }
            return s;
        }

        public boolean boundTo(String placeId) {
            for (String p : places) {
                if (p.endsWith("*") ? placeId.startsWith(p.substring(0, p.length() - 1)) : placeId.equals(p)) return true;
            }
            return false;
        }
    }

    public record Reward(long gold, int perkPoints, @Nullable Supplier<ItemStack> item) {}

    public static final class Collection {
        public final String id;
        public final Kind kind;
        public final List<Entry> entries = new ArrayList<>();
        public final Reward reward;
        /** Journal grouping: "relics", "books", "treasures", "uniques". */
        public final String group;

        Collection(String id, Kind kind, String group, Reward reward) {
            this.id = id;
            this.kind = kind;
            this.group = group;
            this.reward = reward;
        }

        public Component name() {
            return Component.translatable("collection.skycraft." + id);
        }
    }

    private static final Map<String, Collection> ALL = new LinkedHashMap<>();
    private static final Map<String, Entry> BY_KEY = new HashMap<>();
    private static final Map<Identifier, Entry> BY_ITEM = new HashMap<>();

    public static final String MASKS = "dragon_priest_masks";
    public static final String DAEDRIC = "daedric_artifacts";
    public static final String LEGEND = "artifacts_of_legend";
    public static final String BARENZIAH = "stones_of_barenziah";
    public static final String BLACK_BOOKS = "black_books";
    public static final String ELDER_SCROLLS = "elder_scrolls";
    public static final String SKILL_BOOKS = "skill_books";
    public static final String TREASURES = "treasures_";

    /** Provinces with a treasure set, in Journal order. */
    public static final String[] TREASURE_PROVINCES = {"skyrim", "cyrodiil", "morrowind", "black_marsh", "elsweyr", "valenwood",
            "summerset", "hammerfell", "high_rock", "akavir", "atmora", "yokuda", "pyandonea", "thras", "realm"};

    private static volatile boolean built;

    /** Builds the registry (items must be registered: call after registry events, e.g. on server start or lazily). */
    public static void build() {
        if (built) return;
        synchronized (Collectibles.class) {
            if (!built) buildLocked();
        }
    }

    private static void buildLocked() {

        // ---- Dragon Priest masks: Konahrik is the reward of the other eight
        Collection masks = add(new Collection(MASKS, Kind.CARRY, "relics",
                new Reward(0, 1, () -> stack("skycraft:mask_konahrik"))));
        for (String m : new String[]{"morokei", "rahgot", "vokun", "volsung", "nahkriin", "otar", "krosis", "hevnoraak"}) {
            itemEntry(masks, m, "skycraft:mask_" + m, List.of(), null, 1);
        }

        // ---- Daedric artifacts at the Princes' shrines
        Collection daedric = add(new Collection(DAEDRIC, Kind.CARRY, "relics", new Reward(2500, 2, null)));
        itemEntry(daedric, "azuras_star", "skycraft:azuras_star", List.of("shrine_of_azura", "shrine_azura_*"), null, 5);
        itemEntry(daedric, "wabbajack", "skycraft:wabbajack", List.of("shrine_sheogorath_*"), null, 10);
        itemEntry(daedric, "dawnbreaker", "skycraft:dawnbreaker", List.of("statue_to_meridia", "shrine_meridia_*"), null, 12);
        itemEntry(daedric, "spellbreaker", "skycraft:spellbreaker", List.of("shrine_to_peryite", "shrine_peryite_*"), null, 15);
        itemEntry(daedric, "mehrunes_razor", "skycraft:mehrunes_razor", List.of("shrine_of_mehrunes_dagon", "shrine_mehrunes_dagon_*"), null, 15);
        itemEntry(daedric, "volendrung", "skycraft:volendrung", List.of("shrine_malacath_*"), null, 15);
        itemEntry(daedric, "ebony_blade", "skycraft:ebony_blade", List.of("shrine_mephala_*"), null, 20);
        itemEntry(daedric, "mace_of_molag_bal", "skycraft:mace_of_molag_bal", List.of("shrine_molag_bal_*"), null, 20);

        // ---- Artifacts of legend: in their famous tombs, or (unbound ones) the rarest boss-chest finds
        Collection legend = add(new Collection(LEGEND, Kind.CARRY, "relics", new Reward(2000, 2, null)));
        itemEntry(legend, "bloodskal_blade", "skycraft:bloodskal_blade", List.of("bloodskal_barrow"), "solstheim", 12);
        itemEntry(legend, "zephyr", "skycraft:zephyr", List.of("arkngthamz"), null, 12);
        itemEntry(legend, "dragonbane", "skycraft:dragonbane", List.of("sky_haven_temple"), null, 15);
        itemEntry(legend, "wuuthrad", "skycraft:wuuthrad", List.of("ysgramors_tomb"), null, 18);
        itemEntry(legend, "windshear", "skycraft:windshear", List.of(), "skyrim", 18);
        itemEntry(legend, "auriels_bow", "skycraft:auriels_bow", List.of("darkfall_cave"), null, 20);
        itemEntry(legend, "chillrend", "skycraft:chillrend", List.of("nightingale_hall"), null, 20);
        itemEntry(legend, "nightingale_bow", "skycraft:nightingale_bow", List.of("twilight_sepulcher"), null, 20);
        itemEntry(legend, "staff_of_magnus", "skycraft:staff_of_magnus", List.of("labyrinthian"), null, 20);
        itemEntry(legend, "bow_of_shadows", "skycraft:bow_of_shadows", List.of(), "skyrim", 22);

        // ---- Stones of Barenziah ("unusual gems"): one in each of 24 named dungeons
        Collection stones = add(new Collection(BARENZIAH, Kind.CARRY, "relics",
                new Reward(1000, 1, () -> new ItemStack(LootModule.CROWN_OF_BARENZIAH.get()))));
        for (String place : new String[]{"pinewatch", "fellglow_keep", "blind_cliff_cave", "darklight_tower", "yngvild", "tolvalds_cave",
                "forsaken_cave", "rimerock_burrow", "stony_creek_cave", "mzulft", "alftand", "ironbind_barrow", "movarths_lair",
                "silverdrift_lair", "folgunthur", "korvanjund", "ansilvund", "snow_veil_sanctum", "volunruud", "bthardamz", "nilheim",
                "valthume", "shroud_hearth_barrow", "hillgrunds_tomb"}) {
            stones.entries.add(entry(stones, place, () -> LootModule.UNUSUAL_GEM.get(), List.of(place), null, 1, 0, -1));
        }

        // ---- Black Books of Solstheim
        Collection black = add(new Collection(BLACK_BOOKS, Kind.CARRY, "books", new Reward(0, 2, null)));
        String[][] books = {{"epistolary_acumen", "tel_mithryn"}, {"filament_and_filigree", "nchardak"},
                {"hidden_twilight", "white_ridge_barrow"}, {"sallow_regent", "gyldenhul_barrow"},
                {"untold_legends", "bloodskal_barrow"}, {"waking_dreams", "temple_of_miraak"}, {"winds_of_change", "fahlbtharz"}};
        for (String[] b : books) black.entries.add(entry(black, b[0], () -> LootModule.BLACK_BOOK.get(), List.of(b[1]), "solstheim", 10, 0, -1));

        // ---- Elder Scrolls
        Collection scrolls = add(new Collection(ELDER_SCROLLS, Kind.CARRY, "relics", new Reward(0, 2, null)));
        scrolls.entries.add(entry(scrolls, "dragon", () -> LootModule.ELDER_SCROLL.get(), List.of("tower_of_mzark"), null, 10, 0, -1));
        scrolls.entries.add(entry(scrolls, "blood", () -> LootModule.ELDER_SCROLL.get(), List.of("dimhollow_crypt"), null, 15, 0, -1));
        scrolls.entries.add(entry(scrolls, "sun", () -> LootModule.ELDER_SCROLL.get(), List.of("darkfall_cave"), null, 15, 0, -1));

        // ---- Skill books: five volumes per skill, counted when read
        Collection skill = add(new Collection(SKILL_BOOKS, Kind.READ, "books", new Reward(0, 3, null)));
        for (Skill s : Skill.VALUES) {
            for (int v = 1; v <= SkillBookItem.VOLUMES; v++) skill.entries.add(entry(skill, s.id() + "_" + v, null, List.of(), null, 1, 0, -1));
        }

        // ---- Regional treasures (Thieves Guild "special items"): five per province
        treasures("skyrim", 0xB08840, "dwemer_gyro", 300, "dwemer_lexicon", 450, "aetherium_shard", 500, "dwemer_puzzle_cube", 350, "centurion_dynamo_core", 600);
        treasures("cyrodiil", 0x9AD8F0, "welkynd_stone", 250, "great_welkynd_stone", 600, "varla_stone", 500, "meteoric_glass_shard", 350, "ayleid_crown_fragment", 650);
        treasures("morrowind", 0xC0603A, "ash_statue", 300, "velothi_reliquary", 450, "kwama_queen_pearl", 400, "tribunal_icon", 500, "dwemer_coherer", 550);
        treasures("black_marsh", 0x6FA040, "hist_amber", 400, "xanmeer_glyph_tablet", 450, "hist_sap_idol", 500, "lizard_king_scale", 350, "swamp_root_charm", 250);
        treasures("elsweyr", 0xE0C060, "moon_sugar_idol", 450, "lunar_lattice_plate", 500, "mane_prayer_beads", 350, "sugar_censer", 300, "clan_mother_comb", 400);
        treasures("valenwood", 0x5C8A3A, "green_pact_bone_charm", 300, "graht_oak_heartseed", 450, "wild_hunt_mask", 500, "spinner_quill", 400, "jaqspur_horn", 300);
        treasures("summerset", 0xE8E0FF, "psijic_sigil", 600, "crystal_tower_shard", 650, "aldmeri_scroll_case", 400, "sapiarch_lens", 450, "merethic_coin", 350);
        treasures("hammerfell", 0xD89040, "yokudan_sword_hilt", 450, "shehai_shard", 550, "ansei_ward", 400, "satakal_figurine", 350, "alikr_sandglass", 300);
        treasures("high_rock", 0x7080C0, "direnni_crest", 500, "coven_poppet", 250, "wyrd_tree_acorn", 350, "breton_signet", 400, "orsinium_war_totem", 450);
        treasures("akavir", 0xC83A3A, "tsaesci_golden_scale", 450, "kamal_thaw_stone", 400, "tang_mo_jade_seal", 350, "ka_po_tun_tiger_fang", 500, "akaviri_blood_seal", 550);
        treasures("atmora", 0x78B0D8, "jylkurfyk_keel_rune", 400, "clever_craft_owl_totem", 450, "ysgramor_sea_compass", 550, "brodir_grove_amber", 350, "atmoran_hearth_horn", 500);
        treasures("yokuda", 0xD8A848, "memory_stone_fragment", 550, "na_totambu_sun_disc", 500, "tava_wind_flute", 350, "orichalc_tower_brick", 450, "left_handed_elf_torc", 400);
        treasures("pyandonea", 0x38A8B0, "orgnum_coffer_coin", 550, "sea_serpent_fang_charm", 450, "abbarbas_kelp_pearl", 400, "chameleon_skin_vellum", 350, "tempest_conch_horn", 500);
        treasures("thras", 0x6A8A60, "sload_soap_jar", 450, "agalon_coral_idol", 400, "all_flags_admiral_medal", 500, "krempen_lagoon_pearl", 350, "necromancers_coral_wand", 550);
        treasures("realm", 0x8A48B8, "sigil_stone_splinter", 550, "coldharbour_azure_plasm", 450, "soul_cairn_reaper_shard", 500, "shivering_amber_matrix", 400, "apocrypha_glyph_cylinder", 450);

        // ---- Legendary uniques
        Collection uniques = add(new Collection(LegendaryUniques.COLLECTION, Kind.CARRY, "uniques", new Reward(10000, 3, null)));
        for (LegendaryUniques.Unique u : LegendaryUniques.all()) {
            uniques.entries.add(entry(uniques, u.id(), null, u.place() == null ? List.of() : List.of(u.place()), u.province(), u.minLevel(), u.value(), -1));
        }

        for (Collection c : ALL.values()) {
            for (Entry e : c.entries) {
                BY_KEY.put(e.key(), e);
                if (e.item != null && !(e.item.get() instanceof CollectibleItem) && !(e.item.get() instanceof BlackBookItem)) {
                    Identifier id = BuiltInRegistries.ITEM.getKey(e.item.get());
                    if (id != null) BY_ITEM.put(id, e);
                }
            }
        }
        built = true;
    }

    private static void treasures(String province, int color, Object... idsAndValues) {
        Collection c = add(new Collection(TREASURES + province, Kind.CARRY, "treasures", new Reward(1500, 1, null)));
        for (int i = 0; i < idsAndValues.length; i += 2) {
            c.entries.add(entry(c, (String) idsAndValues[i], () -> LootModule.REGIONAL_TREASURE.get(), List.of(), province, 1,
                    (Integer) idsAndValues[i + 1], shade(color, i / 2)));
        }
    }

    /** A slightly different shade per treasure of a set. */
    private static int shade(int rgb, int i) {
        float f = 0.82f + 0.09f * (i % 5);
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    private static Collection add(Collection c) {
        ALL.put(c.id, c);
        return c;
    }

    private static Entry entry(Collection c, String id, @Nullable Supplier<Item> item, List<String> places, @Nullable String province,
                               int minLevel, int value, int color) {
        return new Entry(c.id, id, item, places, province, minLevel, value, color);
    }

    private static void itemEntry(Collection c, String id, String itemId, List<String> places, @Nullable String province, int minLevel) {
        c.entries.add(entry(c, id, () -> item(itemId), places, province, minLevel, 0, -1));
    }

    private static Item item(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        return item == null ? Items.AIR : item;
    }

    private static ItemStack stack(String id) {
        Item item = item(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    // ------------------------------------------------------------------ lookups

    public static java.util.Collection<Collection> all() {
        build();
        return Collections.unmodifiableCollection(ALL.values());
    }

    @Nullable
    public static Collection get(String id) {
        build();
        return ALL.get(id);
    }

    @Nullable
    public static Entry entry(String key) {
        build();
        return BY_KEY.get(key);
    }

    /** The collectible a stack is, or null. */
    @Nullable
    public static Entry identify(ItemStack stack) {
        if (stack.isEmpty()) return null;
        build();
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag != null && tag.contains(NBT)) return BY_KEY.get(tag.getStringOr(NBT, ""));
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? null : BY_ITEM.get(id);
    }

    /** Entries bound to a named place (exact id or prefix). */
    public static List<Entry> boundTo(Place place) {
        build();
        List<Entry> out = new ArrayList<>();
        for (Collection c : ALL.values()) {
            for (Entry e : c.entries) if (!e.places.isEmpty() && e.boundTo(place.id())) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ player progress

    public static CompoundTag found(PlayerData data) {
        return data.module(MODULE).getCompoundOrEmpty("found");
    }

    public static boolean has(PlayerData data, Entry e) {
        if (e.collection.equals(SKILL_BOOKS)) {
            int us = e.id.lastIndexOf('_');
            Skill skill = Skill.byId(e.id.substring(0, us));
            return skill != null && SkillBookItem.hasRead(data, skill, Integer.parseInt(e.id.substring(us + 1)));
        }
        return found(data).contains(e.key());
    }

    public static int progress(PlayerData data, Collection c) {
        int n = 0;
        for (Entry e : c.entries) if (has(data, e)) n++;
        return n;
    }

    public static boolean complete(PlayerData data, String collection) {
        Collection c = get(collection);
        return c != null && progress(data, c) >= c.entries.size();
    }

    public static boolean rewarded(PlayerData data, String collection) {
        return data.module(MODULE).getCompoundOrEmpty("rewarded").contains(collection);
    }

    /** A random entry of the collection that the player lacks and may find at this level (null if none). */
    @Nullable
    public static Entry missing(PlayerData data, String collection, int level, @Nullable String province, RandomSource random) {
        Collection c = get(collection);
        if (c == null) return null;
        List<Entry> pool = new ArrayList<>();
        for (Entry e : c.entries) {
            if (e.minLevel > level || has(data, e)) continue;
            if (province != null && e.province != null && !e.province.equals(province)) continue;
            pool.add(e);
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    public static String groupKey(String group) {
        return "journal.skycraft.collections.group." + group.toLowerCase(Locale.ROOT);
    }
}
