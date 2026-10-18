package com.skycraft.economy;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.crafting.Tempering;
import com.skycraft.crafting.arcane.alchemy.AlchemyEffect;
import com.skycraft.crafting.arcane.alchemy.Ingredients;
import com.skycraft.crafting.arcane.enchant.Enchanting;
import com.skycraft.inventory.InvCategory;
import com.skycraft.inventory.ItemWeights;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.File;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

/**
 * Developer tool for {@code tools/audit_items.py}: when {@code skycraft-item-audit.request} exists in the server
 * directory, the started server writes {@code skycraft-item-audit.json} next to it with what the running game says
 * about every Skycraft item: value and weight (and whether they come from data or the heuristic), barter and
 * inventory category, alchemy effects, how many enchantments fit it at the Arcane Enchanter, its tempering kind and
 * the merchants that may stock it. Nothing happens on ordinary servers.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class ItemAudit {
    private static final String REQUEST = "skycraft-item-audit.request";
    private static final String OUTPUT = "skycraft-item-audit.json";

    private ItemAudit() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        File dir = event.getServer().getServerDirectory().toFile();
        if (!new File(dir, REQUEST).exists()) return;
        try {
            JsonObject root = dump();
            root.add("shops", sampleShops(event.getServer().overworld()));
            try (Writer w = Files.newBufferedWriter(new File(dir, OUTPUT).toPath(), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root, w);
            }
            Skycraft.LOGGER.info("Item audit: wrote {} items to {}", root.getAsJsonObject("items").size(), OUTPUT);
        } catch (Exception e) {
            Skycraft.LOGGER.error("Item audit failed", e);
        }
    }

    static JsonObject dump() {
        Map<Item, Set<ShopType>> merchants = ShopStock.candidates();
        JsonObject items = new JsonObject();
        for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> e : BuiltInRegistries.ITEM.entrySet()) {
            Identifier id = e.getKey().identifier();
            if (!id.getNamespace().equals(Skycraft.MODID)) continue;
            Item item = e.getValue();
            ItemStack stack = new ItemStack(item);
            JsonObject o = new JsonObject();
            o.addProperty("class", item.getClass().getName());
            o.addProperty("block", item instanceof BlockItem);
            o.addProperty("max_stack", stack.getMaxStackSize());
            o.addProperty("value", ItemValues.base(item));
            o.addProperty("value_source", ItemValues.source(item));
            o.addProperty("weight", ItemWeights.base(item));
            o.addProperty("weight_source", ItemWeights.source(item));
            o.addProperty("barter_category", ItemCategory.of(stack).name());
            o.addProperty("inv_category", InvCategory.of(stack).name());
            Ingredients.Ingredient ing = Ingredients.get(item);
            if (ing != null) {
                JsonArray effects = new JsonArray();
                for (AlchemyEffect eff : ing.effects()) effects.add(eff.id());
                o.add("effects", effects);
            }
            int fits = 0;
            if (Enchanting.canEnchantItem(null, stack)) {
                for (var ench : com.skycraft.inventory.ItemData.currentRegistries().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).listElements().toList())
                    if (!ench.is(net.minecraft.tags.EnchantmentTags.CURSE) && Enchanting.fits(stack, ench.value())) fits++;
            }
            o.addProperty("enchantments", fits);
            o.addProperty("temper", Tempering.kind(stack).name());
            JsonArray sold = new JsonArray();
            for (ShopType t : merchants.getOrDefault(item, Set.of())) sold.add(t.id());
            o.add("merchants", sold);
            JsonArray tags = new JsonArray();
            stack.tags().forEach(t -> tags.add(t.location().toString()));
            o.add("tags", tags);
            items.add(id.getPath(), o);
        }
        JsonArray missing = new JsonArray();
        for (Ingredients.Ingredient ing : Ingredients.all()) if (ing.item() == null) missing.add(ing.id());
        JsonObject root = new JsonObject();
        root.add("items", items);
        root.add("leveled", leveledCurves());
        root.add("unregistered_ingredients", missing);
        root.addProperty("registry_size", BuiltInRegistries.ITEM.size());
        return root;
    }

    /** A town of each province (docs/TAMRIEL.md anchors) where sample shops are stocked. */
    private static final Object[][] TOWNS = {
            {"whiterun", 0, 0}, {"raven_rock", 4300, -2900}, {"balmora", 6300, 500}, {"imperial_city", 900, 5900},
            {"rimmen", 1200, 10400}, {"lilmoth", 7700, 10000}, {"elden_root", -4000, 11300}, {"alinor", -11200, 13000},
            {"sentinel", -8200, 4900}, {"wayrest", -5300, 3300}, {"orsinium", -4300, 300}};

    /**
     * Stocks a level 3 shop of every type in a town of every province (unspawned villagers carrying the shop's pools)
     * and lists what they sell, so the audit report shows how stock varies by region and merchant type.
     */
    private static JsonObject sampleShops(net.minecraft.server.level.ServerLevel level) {
        JsonObject out = new JsonObject();
        for (Object[] town : TOWNS) {
            JsonObject byType = new JsonObject();
            for (ShopType type : ShopType.values()) {
                net.minecraft.world.entity.npc.villager.Villager v = new net.minecraft.world.entity.npc.villager.Villager(net.minecraft.world.entity.EntityTypes.VILLAGER, level);
                v.setPos((Integer) town[1] + 0.5, 80, (Integer) town[2] + 0.5);
                v.setVillagerData(v.getVillagerData().withLevel(3));
                net.minecraft.nbt.CompoundTag shop = new net.minecraft.nbt.CompoundTag();
                net.minecraft.nbt.ListTag pools = new net.minecraft.nbt.ListTag();
                pools.add(net.minecraft.nbt.StringTag.valueOf(type.id()));
                shop.put("pools", pools);
                v.getPersistentData().put(Shop.KEY, shop);
                JsonArray stock = new JsonArray();
                for (ItemStack s : Shop.preview(v).stock()) {
                    stock.add(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath() + (s.getCount() > 1 ? " x" + s.getCount() : "")
                            + (s.isEnchanted() ? " (enchanted)" : ""));
                }
                byType.add(type.id(), stock);
                v.discard();
            }
            out.add((String) town[0], byType);
        }
        return out;
    }

    private static final int[] LEVELS = {1, 3, 6, 12, 19, 27, 36, 46, 60};
    private static final int SAMPLES = 400;

    /**
     * Rolls the leveled lists many times per character level and records the highest leveled-list level that came
     * out (gear: the material's level; tomes: the spell level's loot level), per province gear key and for Skyrim's
     * core lists. The audit fails if anything above the character's level drops (no Daedric at level 1).
     */
    private static JsonObject leveledCurves() {
        net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create(42);
        JsonObject out = new JsonObject();
        com.skycraft.arsenal.LeveledGear.Kind[] kinds = com.skycraft.arsenal.LeveledGear.Kind.values();
        java.util.List<String> keys = new java.util.ArrayList<>(ShopStock.GEAR_KEYS);
        keys.add(0, "core");
        for (String key : keys) {
            JsonObject byLevel = new JsonObject();
            for (int level : LEVELS) {
                int max = 0;
                String worst = "";
                for (com.skycraft.arsenal.LeveledGear.Kind kind : kinds) {
                    if (kind == com.skycraft.arsenal.LeveledGear.Kind.ARROW) continue;
                    for (int i = 0; i < SAMPLES; i++) {
                        ItemStack s = key.equals("core") ? com.skycraft.arsenal.LeveledGear.roll(kind, level, rnd)
                                : com.skycraft.arsenal.LeveledGear.regional(key, kind, level, rnd);
                        int min = com.skycraft.crafting.RegionalGear.minLevel(s.getItem());
                        if (min > max) {
                            max = min;
                            worst = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                        }
                    }
                }
                if (key.equals("core")) {
                    for (int i = 0; i < SAMPLES; i++) {
                        ItemStack t = EconomyLoot.tome(level, null, false, 0, java.util.EnumSet.noneOf(com.skycraft.magic.spell.School.class), rnd);
                        if (t.getItem() instanceof com.skycraft.magic.spell.SpellTomeItem tome && tome.spell() != null) {
                            int min = EconomyLoot.lootLevel(tome.spell().tier);
                            if (min > max) {
                                max = min;
                                worst = BuiltInRegistries.ITEM.getKey(t.getItem()).toString();
                            }
                        }
                    }
                }
                JsonObject o = new JsonObject();
                o.addProperty("max_level", max);
                o.addProperty("item", worst);
                byLevel.add(String.valueOf(level), o);
            }
            out.add(key, byLevel);
        }
        return out;
    }
}
