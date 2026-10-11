package com.skycraft.economy;

import com.skycraft.Skycraft;
import com.skycraft.core.Skill;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

/** Items of the economy module: one Skyrim skill book per skill ({@code skycraft:skill_book_<skill>}). */
public final class EconomyItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);

    public static final Map<Skill, RegistryObject<Item>> SKILL_BOOKS = new EnumMap<>(Skill.class);
    /** The key of a bought house ({@link HouseKeyItem}). */
    public static final RegistryObject<Item> HOUSE_KEY = ITEMS.register("house_key",
            () -> new HouseKeyItem(new Item.Properties().stacksTo(1)));

    static {
        for (Skill skill : Skill.VALUES) {
            SKILL_BOOKS.put(skill, ITEMS.register("skill_book_" + skill.id(),
                    () -> new SkillBookItem(skill, new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON))));
        }
    }

    private EconomyItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
