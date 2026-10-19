package com.nirncraft.crime;

import com.nirncraft.NirnCraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Crime items: the lockpick. */
public final class CrimeItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, NirnCraft.MODID);

    public static final Supplier<Item> LOCKPICK = ITEMS.register("lockpick", () -> new LockpickItem(com.nirncraft.platform.SkyProperties.item("lockpick").stacksTo(64)));
    public static final Supplier<Item> JAIL_KEY = ITEMS.register("jail_key", () -> new JailKeyItem(com.nirncraft.platform.SkyProperties.item("jail_key").stacksTo(1)));
    /** Boolean tag on a jail key: the jail's property (never stolen goods, never confiscated, returned on leaving). */
    public static final String JAIL_PROPERTY = "nirncraft_jail_property";

    private CrimeItems() {}

    static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    /** A guard's key to the jail's doors and goods. Works only inside a jail (Locks); the jail takes it back. */
    public static class JailKeyItem extends Item {
        public JailKeyItem(Properties props) {
            super(props);
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("item.nirncraft.jail_key.desc").withStyle(ChatFormatting.GRAY));
        }
    }

    public static class LockpickItem extends Item {
        public LockpickItem(Properties props) {
            super(props);
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("item.nirncraft.lockpick.desc").withStyle(ChatFormatting.GRAY));
        }
    }
}
