package com.nirncraft.registry;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Currency;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Items shared by every module (currency). Module-specific items live in each module's own registry. */
public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, NirnCraft.MODID);

    public static final Supplier<Item> SEPTIM = ITEMS.register("septim", () -> new CoinItem(com.nirncraft.platform.SkyProperties.item("septim")));
    public static final Supplier<Item> COIN_PURSE = ITEMS.register("coin_purse",
            () -> new CoinItem(com.nirncraft.platform.SkyProperties.item("coin_purse").stacksTo(16).rarity(Rarity.UNCOMMON)));

    private ModItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    /** Coins are deposited into the wallet when picked up; using them in hand deposits them too. */
    public static class CoinItem extends Item {
        public CoinItem(Properties props) {
            super(props);
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide()) {
                long value = Currency.valueOf(stack);
                Currency.give(player, value);
                stack.setCount(0);
            }
            return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
        }

        @Override
        public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.gold_value", Currency.valueOf(stack)).withStyle(ChatFormatting.GOLD));
        }
    }
}
