package com.nirncraft.loot;

import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.skills.Progression;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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
public class BlackBookItem extends Item {
    /** The secret each book teaches (Skyrim's Black Books give a choice of three powers; NirnCraft grants a skill). */
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
    public boolean supportsEnchantment(ItemStack stack, net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> enchantment) { return false; }

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
        return data.module(Collectibles.MODULE).getCompoundOrEmpty("black_books_read").contains(book);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Collectibles.Entry e = CollectibleItem.entry(stack);
        if (e == null) {
            // a realm-placed Black Book with only a chapter: it just opens the way into Apocrypha
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                com.nirncraft.realms.RealmTravel.readBlackBookItem(sp, com.nirncraft.realms.item.BlackBookItem.chapter(stack));
            }
            return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
        }
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            PlayerData data = SkyData.get(sp);
            CompoundTag read = data.module(Collectibles.MODULE).getCompoundOrEmpty("black_books_read");
            if (read.contains(e.id())) {
                Notifier.message(sp, Component.translatable("message.nirncraft.loot.black_book_read_again"));
            } else {
                read.putLong(e.id(), level.getGameTime());
                data.module(Collectibles.MODULE).put("black_books_read", read);
                data.setPerkPoints(data.getPerkPoints() + 1);
                Skill skill = SECRET.getOrDefault(e.id(), Skill.ENCHANTING);
                if (data.getSkill(skill) < Skill.MAX_LEVEL) Progression.increaseSkill(sp, skill, 2);
                data.markDirty();
                sp.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NAUSEA), 20 * 6, 0));
                sp.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.DARKNESS), 20 * 4, 0));
                Notifier.title(sp, Component.translatable("message.nirncraft.loot.black_book_title").withStyle(ChatFormatting.DARK_GREEN),
                        Component.translatable("message.nirncraft.loot.black_book_secret", skill.displayName()));
                level.playSound(null, player.blockPosition(), SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 1f, 0.5f);
            }
            // every Black Book is also a door: reading it pulls the reader into Apocrypha (chapter from the book, else by title)
            int chapter = com.nirncraft.inventory.ItemData.hasCustom(stack) && com.nirncraft.inventory.ItemData.custom(stack).contains("chapter")
                    ? com.nirncraft.realms.item.BlackBookItem.chapter(stack)
                    : 1 + Math.floorMod(e.id().hashCode(), com.nirncraft.realms.RealmLayout.APOCRYPHA_CHAPTERS);
            com.nirncraft.realms.RealmTravel.readBlackBookItem(sp, chapter);
        }
        return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        Collectibles.Entry e = CollectibleItem.entry(stack);
        if (e == null) return;
        Skill skill = SECRET.get(e.id());
        tooltip.accept(Component.translatable("tooltip.nirncraft.loot.black_book").withStyle(ChatFormatting.DARK_GREEN));
        if (skill != null) tooltip.accept(Component.translatable("tooltip.nirncraft.loot.black_book_secret", skill.displayName())
                .withStyle(ChatFormatting.GRAY));
    }
}
