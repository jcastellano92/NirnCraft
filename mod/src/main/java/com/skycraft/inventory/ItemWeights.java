package com.skycraft.inventory;

import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.combat.ArmorClass;
import com.skycraft.core.Currency;
import com.skycraft.economy.ItemCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BookItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skyrim item weights (contract 19).
 *
 * <p>Weights come from data files {@code data/<namespace>/skycraft_weights/*.json} of the form
 * {@code {"weights": {"minecraft:iron_sword": 9, "#forge:ingots": 1}}}. An explicit item id always wins over a tag;
 * when several tags match, the one declared <b>last</b> wins (files load {@code default.json} first, then the rest
 * alphabetically, so later and more specific entries override generic ones). Items nobody listed get a heuristic weight
 * (armor by slot and heavy/light class, weapons by attack damage, blocks 1-5 by hardness, food 0.1-0.5, ...).</p>
 *
 * <p>An individual stack can override its weight with the float NBT {@value #WEIGHT_NBT}. Septims and coin purses
 * weigh nothing (gold lives in the wallet). The table is synced to clients on login and after {@code /reload}.</p>
 */
public final class ItemWeights {
    /** Optional per-stack override (float, weight of ONE item). */
    public static final String WEIGHT_NBT = "skycraft_weight";

    private static volatile Table table = new Table(Collections.emptyMap(), Collections.emptyMap());
    /** Bumped whenever the table is replaced; lets {@link Pack} cache its weight (the session part keeps saved values honest). */
    private static final long SESSION = System.nanoTime() << 12;
    private static volatile long generation;

    private ItemWeights() {}

    /** Weight of ONE item of {@code stack} (never negative). Multiply by the count for the whole stack. */
    public static float get(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0f;
        CompoundTag nbt = stack.getTag();
        if (nbt != null && nbt.contains(WEIGHT_NBT, Tag.TAG_ANY_NUMERIC)) return Math.max(0f, nbt.getFloat(WEIGHT_NBT));
        if (Currency.valueOf(stack) > 0) return 0f;
        return base(stack.getItem());
    }

    /** Weight of the whole stack ({@link #get(ItemStack)} times the count). */
    public static float getStack(ItemStack stack) {
        return get(stack) * Math.max(0, stack.getCount());
    }

    /** Base weight of an item type (data table, then tags, then heuristic). Cached until the next reload/tag sync. */
    public static float base(Item item) {
        Table t = table;
        return t.cache.computeIfAbsent(item, t::resolve);
    }

    /** Where an item's weight comes from: {@code "item"} (listed), {@code "tag"} or {@code "heuristic"} (audits). */
    public static String source(Item item) {
        Table t = table;
        if (t.items.containsKey(BuiltInRegistries.ITEM.getKey(item))) return "item";
        ItemStack probe = new ItemStack(item);
        for (Map.Entry<TagKey<Item>, Float> e : t.tagKeys) if (probe.is(e.getKey())) return "tag";
        return "heuristic";
    }

    /** Pretty "9", "0.5", "12.3" (one decimal at most). */
    public static String format(float weight) {
        float rounded = Math.round(weight * 10f) / 10f;
        if (Math.abs(rounded - Math.round(rounded)) < 0.001f) return String.valueOf(Math.round(rounded));
        return String.valueOf(rounded);
    }

    // ------------------------------------------------------------------ table

    /** Immutable snapshot of the loaded data (swapped atomically on reload / client sync). */
    static final class Table {
        final Map<ResourceLocation, Float> items;
        final Map<ResourceLocation, Float> tags;
        final List<Map.Entry<TagKey<Item>, Float>> tagKeys = new ArrayList<>();
        final Map<Item, Float> cache = new ConcurrentHashMap<>();

        Table(Map<ResourceLocation, Float> items, Map<ResourceLocation, Float> tags) {
            this.items = Collections.unmodifiableMap(new HashMap<>(items));
            this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
            for (Map.Entry<ResourceLocation, Float> e : this.tags.entrySet()) {
                tagKeys.add(Map.entry(TagKey.create(Registries.ITEM, e.getKey()), e.getValue()));
            }
        }

        float resolve(Item item) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            Float direct = items.get(id);
            if (direct != null) return Math.max(0f, direct);
            if (!tagKeys.isEmpty()) {
                ItemStack probe = new ItemStack(item);
                Float found = null;
                for (Map.Entry<TagKey<Item>, Float> e : tagKeys) {
                    if (probe.is(e.getKey())) found = e.getValue(); // last declared wins
                }
                if (found != null) return Math.max(0f, found);
            }
            try {
                return heuristic(item);
            } catch (Exception ex) {
                return 0.5f;
            }
        }
    }

    static Map<ResourceLocation, Float> itemTable() {
        return table.items;
    }

    static Map<ResourceLocation, Float> tagTable() {
        return table.tags;
    }

    /** Replaces the table (server reload, or client receiving the sync packet). */
    static void setTable(Map<ResourceLocation, Float> items, Map<ResourceLocation, Float> tags) {
        table = new Table(items, tags);
        generation++;
    }

    /** Changes whenever weights may have changed (reload, tag update, client sync). */
    static long version() {
        return SESSION + generation;
    }

    /** Tags changed: forget the cached resolutions but keep the data. */
    static void clearCache() {
        Table t = table;
        table = new Table(t.items, t.tags);
        generation++;
    }

    // ------------------------------------------------------------------ heuristic

    /** Fallback weight for items nobody listed. */
    static float heuristic(Item item) {
        ItemStack stack = new ItemStack(item);

        if (item instanceof ArmorItem armor) {
            boolean heavy = ArmorClass.isHeavy(stack);
            EquipmentSlot slot = armor.getEquipmentSlot();
            float base = switch (slot) {
                case HEAD -> heavy ? 6f : 2f;
                case CHEST -> heavy ? 30f : 7f;
                case LEGS -> heavy ? 8f : 3f;
                case FEET -> heavy ? 7f : 2f;
                default -> heavy ? 8f : 3f;
            };
            // sturdier armor is a little heavier
            float defense = armor.getDefense() + armor.getToughness();
            return round1(base * (0.8f + 0.05f * Math.min(8f, defense)));
        }
        if (item instanceof ShieldItem) return 10f;
        if (item instanceof ElytraItem) return 5f;
        if (item instanceof CrossbowItem) return 14f;
        if (item instanceof ProjectileWeaponItem) return 7f;

        double damage = attackDamage(item);
        if (item instanceof DiggerItem && damage < 6) {
            // pickaxes, shovels, hoes (axes with big damage fall through to the weapon rule)
            return round1((float) Mth.clamp(3 + damage * 1.2, 3, 12));
        }
        if (damage >= 2) return round1((float) Mth.clamp(damage * 1.8, 2, 35));

        if (item instanceof PotionItem) return 0.5f;
        if (item instanceof BookItem || item instanceof WritableBookItem || item instanceof WrittenBookItem
                || item instanceof EnchantedBookItem || stack.is(ItemCategory.SPELL_TOMES) || stack.is(ItemCategory.SKILL_BOOKS)) {
            return 1f;
        }
        if (stack.is(ItemCategory.INGREDIENTS_TAG)) return 0.1f;

        FoodProperties food = item.getFoodProperties();
        if (food != null) return round1(Mth.clamp(0.1f + food.getNutrition() * 0.05f, 0.1f, 0.5f));

        if (item instanceof BlockItem block) {
            float hardness;
            try {
                // BlockStateBase#getDestroySpeed just returns the cached hardness; level/pos are unused in vanilla
                hardness = block.getBlock().defaultBlockState().getDestroySpeed(null, null);
            } catch (Exception ex) {
                hardness = 1.5f;
            }
            if (hardness < 0) return 5f; // unbreakable blocks (bedrock...) are heavy
            return round1(Mth.clamp(1f + hardness * 0.5f, 1f, 5f));
        }
        // misc: single items weigh a little, stackable materials almost nothing
        return stack.getMaxStackSize() == 1 ? 1f : 0.1f;
    }

    private static float round1(float v) {
        return Math.round(v * 10f) / 10f;
    }

    /** Extra attack damage granted in the main hand (0 for non-weapons). */
    private static double attackDamage(Item item) {
        try {
            Multimap<Attribute, AttributeModifier> mods = item.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND);
            double sum = 0;
            for (AttributeModifier m : mods.get(Attributes.ATTACK_DAMAGE)) {
                if (m.getOperation() == AttributeModifier.Operation.ADDITION) sum += m.getAmount();
            }
            return Math.max(0, sum);
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ data loading

    /** Loads every {@code data/<ns>/skycraft_weights/*.json}. Registered from {@link InventoryEvents}. */
    public static class Loader extends SimpleJsonResourceReloadListener {
        private static final Gson GSON = new GsonBuilder().create();

        public Loader() {
            super(GSON, "skycraft_weights");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, Float> items = new HashMap<>();
            Map<ResourceLocation, Float> tags = new LinkedHashMap<>();
            // deterministic order: "default" files first so other files override them
            List<ResourceLocation> ids = new ArrayList<>(files.keySet());
            ids.sort((a, b) -> {
                boolean da = a.getPath().equals("default"), db = b.getPath().equals("default");
                if (da != db) return da ? -1 : 1;
                return a.toString().compareTo(b.toString());
            });
            for (ResourceLocation file : ids) {
                try {
                    JsonElement root = files.get(file);
                    if (!root.isJsonObject()) continue;
                    JsonObject obj = root.getAsJsonObject();
                    if (!obj.has("weights") || !obj.get("weights").isJsonObject()) continue;
                    for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("weights").entrySet()) {
                        String key = e.getKey();
                        float w;
                        try {
                            w = Math.max(0f, e.getValue().getAsFloat());
                        } catch (Exception ex) {
                            Skycraft.LOGGER.warn("Bad item weight for {} in {}", key, file);
                            continue;
                        }
                        boolean tag = key.startsWith("#");
                        ResourceLocation id = ResourceLocation.tryParse(tag ? key.substring(1) : key);
                        if (id == null) {
                            Skycraft.LOGGER.warn("Bad item id {} in {}", key, file);
                            continue;
                        }
                        if (tag) {
                            tags.remove(id); // re-insert so a later declaration also moves to the end (wins)
                            tags.put(id, w);
                        } else {
                            items.put(id, w);
                        }
                    }
                } catch (Exception ex) {
                    Skycraft.LOGGER.error("Failed to load item weights {}", file, ex);
                }
            }
            setTable(items, tags);
            Skycraft.LOGGER.info("Loaded {} item weights and {} tag weights", items.size(), tags.size());
        }
    }
}
