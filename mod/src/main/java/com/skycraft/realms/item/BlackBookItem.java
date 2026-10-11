package com.skycraft.realms.item;

import com.skycraft.realms.RealmLayout;
import com.skycraft.realms.RealmTravel;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A Black Book of Hermaeus Mora. Reading one (use) pulls the reader into Apocrypha at its chapter (NBT {@code chapter}:
 * 0 = Waking Dreams, the Summit; 1..{@link RealmLayout#APOCRYPHA_CHAPTERS} the chapter path). The book is not used up:
 * whoever owns one can always go back.
 */
public class BlackBookItem extends Item {
    public static final String[] TITLES = {"waking_dreams", "epistolary_acumen", "the_sallow_regent",
            "filament_and_filigree", "untold_legends", "the_winds_of_change"};

    public BlackBookItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC));
    }

    public static ItemStack of(Item item, int chapter) {
        ItemStack s = new ItemStack(item);
        s.getOrCreateTag().putInt("chapter", Math.max(0, Math.min(RealmLayout.APOCRYPHA_CHAPTERS, chapter)));
        s.getOrCreateTag().putInt("skycraft_value", 500);
        return s;
    }

    public static int chapter(ItemStack stack) {
        return stack.getTag() == null ? 1 : Math.max(0, Math.min(RealmLayout.APOCRYPHA_CHAPTERS, stack.getTag().getInt("chapter")));
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.skycraft.black_book.titled",
                Component.translatable("book.skycraft.black_book." + TITLES[chapter(stack)]));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) RealmTravel.readBlackBookItem(sp, chapter(stack));
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.skycraft.black_book.tooltip").withStyle(ChatFormatting.DARK_GREEN));
    }
}
