package com.skycraft.registry;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/** One creative tab that lists every Skycraft item, whichever module registered it. */
public final class ModCreativeTab {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Skycraft.MODID);

    public static final Supplier<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.skycraft"))
            .icon(() -> new ItemStack(ModItems.SEPTIM.get()))
            .displayItems((params, output) -> BuiltInRegistries.ITEM.entrySet().stream()
                    .filter(e -> e.getKey().identifier().getNamespace().equals(Skycraft.MODID))
                    .forEach(e -> output.accept(e.getValue())))
            .build());

    private ModCreativeTab() {}

    public static void init(IEventBus modBus) {
        TABS.register(modBus);
    }
}
