package com.skycraft.loot;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.skills.Progression;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A Black Book of Hermaeus Mora (Solstheim). Reading one for the first time pulls the reader into Apocrypha for a
 * moment and grants a secret: one perk point and two levels in the book's skill. Read books are remembered in
 * {@code module("loot").black_books_read}. Never enchantable (it is a book only in name).
 */
public class BlackBookItem extends BookItem {
    /** The secret each book teaches (Skyrim's Black Books give a choice of three powers; Skycraft grants a skill). */
    private static final Map<String, Skill> SECRET = Map.of(
            "epistolary_acumen", Skill.ENCHANTING,
            "filament_and_filigree", Skill.SPEECH,
            "hidden_twilight", Skill.SNEAK,
            "sallow_regent", Skill.CONJURATION,
            "untold_legends", Skill.DESTRUCTION,
            "waking_dreams", Skill.ALTERATION,
            "winds_of_change", Skill.ONE_HANDED);

    public BlackBookItem(Properties props) {
        super(props);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    @Override
    public int getEnchantmentValue() {
        return 0;
    }

    @Override
    public Component getName(ItemStack stack) {
        Collectibles.Entry e = CollectibleItem.entry(stack);
        return e != null ? e.name() : super.getName(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    public static boolean hasRead(PlayerData data, String book) {
        return data.module(Collectibles.MODULE).getCompound("black_books_read").contains(book);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Collectibles.Entry e = CollectibleItem.entry(stack);
        if (e == null) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            PlayerData data = SkyData.get(sp);
            CompoundTag read = data.module(Collectibles.MODULE).getCompound("black_books_read");
            if (read.contains(e.id())) {
                Notifier.message(sp, Component.translatable("message.skycraft.loot.black_book_read_again"));
            } else {
                read.putLong(e.id(), level.getGameTime());
                data.module(Collectibles.MODULE).put("black_books_read", read);
                data.setPerkPoints(data.getPerkPoints() + 1);
                Skill skill = SECRET.getOrDefault(e.id(), Skill.ENCHANTING);
                if (data.getSkill(skill) < Skill.MAX_LEVEL) Progression.increaseSkill(sp, skill, 2);
                data.markDirty();
                sp.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 6, 0));
                sp.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 20 * 4, 0));
                Notifier.title(sp, Component.translatable("message.skycraft.loot.black_book_title").withStyle(ChatFormatting.DARK_GREEN),
                        Component.translatable("message.skycraft.loot.black_book_secret", skill.displayName()));
                level.playSound(null, player.blockPosition(), SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 1f, 0.5f);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        Collectibles.Entry e = CollectibleItem.entry(stack);
        if (e == null) return;
        Skill skill = SECRET.get(e.id());
        tooltip.add(Component.translatable("tooltip.skycraft.loot.black_book").withStyle(ChatFormatting.DARK_GREEN));
        if (skill != null) tooltip.add(Component.translatable("tooltip.skycraft.loot.black_book_secret", skill.displayName())
                .withStyle(ChatFormatting.GRAY));
    }
}
