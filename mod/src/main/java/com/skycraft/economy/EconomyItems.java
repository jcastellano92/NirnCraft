package com.skycraft.economy;

import com.skycraft.Skycraft;
import com.skycraft.core.Skill;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

import java.util.EnumMap;
import java.util.Map;

/** Items of the economy module: one Skyrim skill book per skill ({@code skycraft:skill_book_<skill>}). */
public final class EconomyItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, Skycraft.MODID);

    public static final Map<Skill, Supplier<Item>> SKILL_BOOKS = new EnumMap<>(Skill.class);
    /** The key of a bought house ({@link HouseKeyItem}). */
    public static final Supplier<Item> HOUSE_KEY = ITEMS.register("house_key",
            key -> new HouseKeyItem(new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, key)).stacksTo(1)));

    static {
        for (Skill skill : Skill.VALUES) {
            SKILL_BOOKS.put(skill, ITEMS.register("skill_book_" + skill.id(),
                    key -> new SkillBookItem(skill, new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, key)).stacksTo(16).rarity(Rarity.UNCOMMON))));
        }
    }

    private EconomyItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
