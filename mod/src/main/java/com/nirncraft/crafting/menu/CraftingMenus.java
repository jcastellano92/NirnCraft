package com.nirncraft.crafting.menu;

import com.nirncraft.NirnCraft;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/** Menu types of the smithing module. */
public final class CraftingMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(BuiltInRegistries.MENU, NirnCraft.MODID);

    public static final Supplier<MenuType<StationMenu>> STATION = MENUS.register("smithing_station",
            () -> IMenuTypeExtension.create(StationMenu::fromNetwork));

    public static final Supplier<MenuType<com.nirncraft.homestead.HomesteadMenu>> HOMESTEAD = MENUS.register("homestead_table",
            () -> IMenuTypeExtension.create(com.nirncraft.homestead.HomesteadMenu::fromNetwork));

    private CraftingMenus() {}
}
