package com.skycraft.economy;

import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skyrim gold values of items.
 *
 * <p>Values come from data files {@code data/<namespace>/skycraft_values/*.json} of the form
 * {@code {"values": {"minecraft:diamond": 250, "#forge:ingots/gold": 100}}} (item ids and {@code #tags}; an explicit
 * item id always wins over a tag). Items without an entry get a heuristic value from their rarity, food, armor,
 * weapon damage, tool tier and durability. On top of the base value come potion effects, enchantments
 * ({@code ~50 gold per enchantment level}), Smithing quality ({@code skycraft_quality} NBT, +15% per level) and wear.</p>
 *
 * <p>The loaded table is synced to clients (so tooltips and the barter screen can show values) but every price
 * is computed on the server.</p>
 */
public final class ItemValues {
    /** Optional NBT override of an individual stack's base value (int), e.g. for quest items. */
    public static final String VALUE_NBT = "skycraft_value";
    public static final String QUALITY_NBT = "skycraft_quality";

    private static volatile Table table = new Table(Collections.emptyMap(), Collections.emptyMap());

    private ItemValues() {}

    /**
     * The gold value of ONE item of {@code stack} (never negative), including potion effects, enchantments,
     * quality and wear. Multiply by the count yourself when you need the whole stack.
     */
    public static int get(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        long coins = Currency.valueOf(stack);
        if (coins > 0) return (int) Math.min(Integer.MAX_VALUE, coins / Math.max(1, stack.getCount()));

        CompoundTag nbt = stack.getTag();
        double value;
        if (nbt != null && nbt.contains(VALUE_NBT, Tag.TAG_ANY_NUMERIC)) value = Math.max(0, nbt.getInt(VALUE_NBT));
        else value = base(stack.getItem());

        // potions: an empty bottle of water is worth nothing, real potions scale with their effects
        if (stack.getItem() instanceof PotionItem || stack.getItem() instanceof TippedArrowItem) {
            List<MobEffectInstance> effects = PotionUtils.getMobEffects(stack);
            if (effects.isEmpty()) {
                value = Math.min(value, 1);
            } else {
                double factor = 0;
                for (MobEffectInstance e : effects) {
                    double strength = 1 + 0.75 * e.getAmplifier();
                    double length = e.getEffect().isInstantenous() ? 1 : Math.max(0.5, Math.sqrt(e.getDuration() / 3600.0));
                    factor += strength * length;
                }
                value *= Math.max(0.5, factor);
            }
        }

        // enchantments (also reads the stored enchantments of enchanted books)
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
        if (!enchantments.isEmpty()) {
            double bonus = 0;
            for (Map.Entry<Enchantment, Integer> e : enchantments.entrySet()) {
                Enchantment ench = e.getKey();
                int lvl = Math.max(1, e.getValue());
                if (ench.isCurse()) {
                    bonus -= 25.0 * lvl;
                    continue;
                }
                double rarity = switch (ench.getRarity()) {
                    case COMMON -> 1.0;
                    case UNCOMMON -> 1.5;
                    case RARE -> 2.0;
                    case VERY_RARE -> 3.0;
                    default -> 1.0;
                };
                bonus += 50.0 * lvl * rarity * (ench.isTreasureOnly() ? 1.5 : 1.0);
            }
            value = Math.max(value * 0.5, value + bonus);
        }

        // Smithing quality from tempering at the grindstone/workbench (crafting module)
        if (nbt != null && nbt.contains(QUALITY_NBT, Tag.TAG_ANY_NUMERIC)) {
            int q = nbt.getInt(QUALITY_NBT);
            if (q > 0) value *= 1 + 0.15 * q;
        }

        // worn-out gear is worth less
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0 && stack.getDamageValue() > 0) {
            value *= 1 - 0.5 * Math.min(1.0, stack.getDamageValue() / (double) stack.getMaxDamage());
        }
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(value)));
    }

    /** Value of the whole stack ({@link #get(ItemStack)} times the count). */
    public static long getStack(ItemStack stack) {
        return (long) get(stack) * Math.max(0, stack.getCount());
    }

    /** Base value of an item type (data table, then tags, then heuristic). Cached until the next reload/tag sync. */
    public static int base(Item item) {
        Table t = table;
        return t.cache.computeIfAbsent(item, t::resolve);
    }

    /** Where an item's base value comes from: {@code "item"} (listed), {@code "tag"} or {@code "heuristic"} (audits). */
    public static String source(Item item) {
        Table t = table;
        if (t.items.containsKey(BuiltInRegistries.ITEM.getKey(item))) return "item";
        ItemStack probe = new ItemStack(item);
        for (Map.Entry<TagKey<Item>, Integer> e : t.tagKeys) if (probe.is(e.getKey())) return "tag";
        return "heuristic";
    }

    // ------------------------------------------------------------------ table

    /** Immutable snapshot of the loaded data (swapped atomically on reload / client sync). */
    static final class Table {
        final Map<ResourceLocation, Integer> items;
        final Map<ResourceLocation, Integer> tags;
        final List<Map.Entry<TagKey<Item>, Integer>> tagKeys = new ArrayList<>();
        final Map<Item, Integer> cache = new ConcurrentHashMap<>();

        Table(Map<ResourceLocation, Integer> items, Map<ResourceLocation, Integer> tags) {
            this.items = Collections.unmodifiableMap(new HashMap<>(items));
            this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
            for (Map.Entry<ResourceLocation, Integer> e : this.tags.entrySet()) {
                tagKeys.add(Map.entry(TagKey.create(Registries.ITEM, e.getKey()), e.getValue()));
            }
        }

        int resolve(Item item) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            Integer direct = items.get(id);
            if (direct != null) return Math.max(0, direct);
            if (!tagKeys.isEmpty()) {
                ItemStack probe = new ItemStack(item);
                int best = -1;
                for (Map.Entry<TagKey<Item>, Integer> e : tagKeys) {
                    if (probe.is(e.getKey())) best = Math.max(best, e.getValue());
                }
                if (best >= 0) return best;
            }
            return heuristic(item);
        }
    }

    static Map<ResourceLocation, Integer> itemTable() {
        return table.items;
    }

    static Map<ResourceLocation, Integer> tagTable() {
        return table.tags;
    }

    /** Replaces the table (server reload, or client receiving the sync packet). */
    static void setTable(Map<ResourceLocation, Integer> items, Map<ResourceLocation, Integer> tags) {
        table = new Table(items, tags);
    }

    /** Tags changed: forget the cached resolutions but keep the data. */
    static void clearCache() {
        Table t = table;
        table = new Table(t.items, t.tags);
    }

    // ------------------------------------------------------------------ heuristic

    /** Fallback value for items nobody priced: rarity, food, armor, weapon damage, tool tier and durability. */
    static int heuristic(Item item) {
        ItemStack stack = new ItemStack(item);
        double value = 0;

        if (item instanceof ArmorItem armor) {
            double defense = armor.getDefense();
            double toughness = armor.getToughness();
            value = Math.max(value, defense * 15 * (1 + toughness * 0.5) + armor.getMaterial().getEnchantmentValue());
        }

        double damage = attackDamage(item);
        if (damage > 0) {
            double weapon;
            if (item instanceof TieredItem tiered) {
                int tier = Math.max(0, tiered.getTier().getLevel());
                weapon = (damage + 1) * 1.2 * Math.pow(3, tier);
            } else {
                weapon = (damage + 1) * (damage + 1) * 1.5;
            }
            value = Math.max(value, weapon);
        } else if (item instanceof TieredItem tiered) {
            value = Math.max(value, 2 * Math.pow(3, Math.max(0, tiered.getTier().getLevel())));
        }
        if (item instanceof ProjectileWeaponItem) value = Math.max(value, 30);

        FoodProperties food = item.getFoodProperties();
        if (food != null) {
            value = Math.max(value, food.getNutrition() * (1 + food.getSaturationModifier()) * 0.7);
        }

        if (value <= 0 && stack.isDamageableItem()) value = stack.getMaxDamage() / 20.0;
        if (value <= 0) value = item instanceof BlockItem ? 0 : 1;

        Rarity rarity = stack.getRarity();
        value = switch (rarity) {
            case COMMON -> value;
            case UNCOMMON -> value * 1.5 + 10;
            case RARE -> value * 2 + 50;
            case EPIC -> value * 3 + 200;
            default -> value * 2 + 50; // Forge-extended rarities
        };
        return (int) Math.max(0, Math.round(value));
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

    /** Loads every {@code data/<ns>/skycraft_values/*.json}. Registered from {@link EconomyEvents}. */
    public static class Loader extends SimpleJsonResourceReloadListener {
        private static final Gson GSON = new GsonBuilder().create();

        public Loader() {
            super(GSON, "skycraft_values");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, Integer> items = new HashMap<>();
            Map<ResourceLocation, Integer> tags = new LinkedHashMap<>();
            // deterministic order: "vanilla" files first so other modules' files override them
            List<ResourceLocation> ids = new ArrayList<>(files.keySet());
            ids.sort((a, b) -> {
                boolean va = a.getPath().equals("vanilla"), vb = b.getPath().equals("vanilla");
                if (va != vb) return va ? -1 : 1;
                return a.toString().compareTo(b.toString());
            });
            for (ResourceLocation file : ids) {
                try {
                    JsonElement root = files.get(file);
                    if (!root.isJsonObject()) continue;
                    JsonObject obj = root.getAsJsonObject();
                    if (!obj.has("values") || !obj.get("values").isJsonObject()) continue;
                    for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("values").entrySet()) {
                        String key = e.getKey();
                        int v;
                        try {
                            v = Math.max(0, e.getValue().getAsInt());
                        } catch (Exception ex) {
                            Skycraft.LOGGER.warn("Bad item value for {} in {}", key, file);
                            continue;
                        }
                        boolean tag = key.startsWith("#");
                        ResourceLocation id = ResourceLocation.tryParse(tag ? key.substring(1) : key);
                        if (id == null) {
                            Skycraft.LOGGER.warn("Bad item id {} in {}", key, file);
                            continue;
                        }
                        if (tag) tags.put(id, v);
                        else items.put(id, v);
                    }
                } catch (Exception ex) {
                    Skycraft.LOGGER.error("Failed to load item values {}", file, ex);
                }
            }
            setTable(items, tags);
            Skycraft.LOGGER.info("Loaded {} item values and {} tag values", items.size(), tags.size());
        }
    }
}
