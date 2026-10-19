package com.nirncraft.loot;

import com.nirncraft.core.Notifier;
import net.minecraft.ChatFormatting;
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

/**
 * A collectible trinket identified by its {@link Collectibles#NBT} entry key: an unusual gem (Stone of Barenziah), a
 * regional treasure (tinted per entry), an Elder Scroll, or the Crown of Barenziah. Treasures and Elder Scrolls are
 * named after their entry; reading an Elder Scroll without a Moth Priest's training blinds the reader for a while.
 */
public class CollectibleItem extends Item {
    public enum Kind { GEM, TREASURE, SCROLL, CROWN }

    private final Kind kind;

    public CollectibleItem(Kind kind, Properties props) {
        super(props);
        this.kind = kind;
    }

    @Nullable
    public static Collectibles.Entry entry(ItemStack stack) {
        return com.nirncraft.inventory.ItemData.hasCustom(stack) && com.nirncraft.inventory.ItemData.custom(stack).contains(Collectibles.NBT) ? Collectibles.entry(com.nirncraft.inventory.ItemData.custom(stack).getStringOr(Collectibles.NBT, "")) : null;
    }

    @Override
    public Component getName(ItemStack stack) {
        if (kind == Kind.TREASURE || kind == Kind.SCROLL) {
            Collectibles.Entry e = entry(stack);
            if (e != null) return e.name();
        }
        return super.getName(stack);
    }

    /** Tint of the treasure's tinted layer (item colour handler, client). */
    public static int color(ItemStack stack) {
        Collectibles.Entry e = entry(stack);
        return e == null || e.color() < 0 ? 0xFFFFFF : e.color();
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return kind == Kind.SCROLL || kind == Kind.CROWN;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (kind != Kind.SCROLL) return InteractionResult.PASS;
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            // only Moth Priests read an Elder Scroll unharmed: the hero glimpses the past and goes blind for a while
            sp.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.BLINDNESS), 20 * 20, 0));
            sp.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NAUSEA), 20 * 8, 0));
            Notifier.message(sp, Component.translatable("message.nirncraft.loot.elder_scroll_read").withStyle(ChatFormatting.LIGHT_PURPLE));
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8f, 0.6f);
            sp.getCooldowns().addCooldown(sp.getItemInHand(hand), 20 * 30);
        }
        return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        if (kind == Kind.CROWN) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.loot.crown_of_barenziah").withStyle(ChatFormatting.GOLD));
            return;
        }
        Collectibles.Entry e = entry(stack);
        if (e == null) return;
        Collectibles.Collection c = Collectibles.get(e.collection());
        if (c != null) tooltip.accept(Component.translatable("tooltip.nirncraft.loot.part_of", c.name()).withStyle(ChatFormatting.GRAY));
        if (kind == Kind.TREASURE || kind == Kind.SCROLL) {
            tooltip.accept(Component.translatable("collectible.nirncraft." + e.collection() + "." + e.id() + ".desc")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }
}
