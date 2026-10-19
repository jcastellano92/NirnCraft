package com.nirncraft.crafting.arcane.item;

import com.nirncraft.core.Notifier;
import com.nirncraft.core.Skill;
import com.nirncraft.perk.Perks;
import com.nirncraft.skills.Progression;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A soul gem. NBT int {@code soul} holds the trapped soul: 0 empty, 1 petty, 2 lesser, 3 common, 4 greater, 5 grand,
 * 6 black (a person's soul). Filled gems power enchanting at the Arcane Enchanter; using a filled gem while holding a
 * damaged enchanted item in the other hand "recharges" (repairs) it.
 */
public class SoulGemItem extends Item {
    public static final String SOUL = "soul";
    public static final String VALUE = "nirncraft_value";
    public static final int BLACK_SOUL = 6;

    /** Largest white soul the gem holds (1..5). */
    public final int capacity;
    /** Black soul gems also hold the souls of people. */
    public final boolean black;
    /** Azura's Star: emptied, not consumed, when used. */
    public final boolean reusable;
    /** Gold value when empty. */
    public final int baseValue;

    public SoulGemItem(int capacity, boolean black, boolean reusable, int baseValue, Properties props) {
        super(props);
        this.capacity = capacity;
        this.black = black;
        this.reusable = reusable;
        this.baseValue = baseValue;
    }

    // ------------------------------------------------------------------ soul NBT

    public static int getSoul(ItemStack stack) {
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        return tag == null ? 0 : tag.getIntOr(SOUL, 0);
    }

    public static void setSoul(ItemStack stack, int soul) {
        if (soul <= 0) {
            CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
            if (tag != null) {
                tag.remove(SOUL);
                tag.remove(VALUE);
                com.nirncraft.inventory.ItemData.setCustom(stack, tag);
            }
            return;
        }
        CompoundTag tag = com.nirncraft.inventory.ItemData.customOrEmpty(stack);
        tag.putInt(SOUL, soul);
        if (stack.getItem() instanceof SoulGemItem gem) tag.putInt(VALUE, gem.value(soul));
        com.nirncraft.inventory.ItemData.setCustom(stack, tag);
    }

    /** Effective soul strength for enchanting: black souls count as grand. */
    public static int strength(int soul) {
        return Math.min(5, Math.max(0, soul));
    }

    /** Sort rank: gems are filled smallest first, black gems last. */
    public int rank() {
        return black ? 6 : capacity;
    }

    public boolean canHold(int soul) {
        if (soul <= 0) return false;
        if (soul >= BLACK_SOUL) return black;
        return black || capacity >= soul;
    }

    public int value(int soul) {
        if (soul <= 0) return baseValue;
        int cap = black ? 5 : capacity;
        return Math.round(baseValue * (1f + 2f * strength(soul) / Math.max(1, cap)));
    }

    public static Component soulName(int soul) {
        return Component.translatable("soul.nirncraft." + Math.max(0, Math.min(BLACK_SOUL, soul)));
    }

    /** Uses up the soul: reusable gems are emptied, others destroyed. */
    public static void consume(ItemStack gemStack) {
        if (gemStack.getItem() instanceof SoulGemItem gem && gem.reusable) {
            setSoul(gemStack, 0);
        } else {
            gemStack.shrink(1);
        }
    }

    // ------------------------------------------------------------------ item behaviour

    @Override
    public Component getName(ItemStack stack) {
        int soul = getSoul(stack);
        Component base = super.getName(stack);
        if (soul <= 0) return base;
        return Component.translatable("item.nirncraft.soul_gem.filled", base, soulName(soul));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        int soul = getSoul(stack);
        if (soul > 0) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.soul_gem.holds", soulName(soul)).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            tooltip.accept(Component.translatable("tooltip.nirncraft.soul_gem.empty").withStyle(ChatFormatting.GRAY));
        }
        if (black) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.soul_gem.black").withStyle(ChatFormatting.DARK_PURPLE));
        } else {
            tooltip.accept(Component.translatable("tooltip.nirncraft.soul_gem.capacity", soulName(capacity)).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (reusable) tooltip.accept(Component.translatable("tooltip.nirncraft.soul_gem.reusable").withStyle(ChatFormatting.GOLD));
        tooltip.accept(Component.translatable("tooltip.nirncraft.soul_gem.recharge_hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return reusable && getSoul(stack) > 0;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack gem = player.getItemInHand(hand);
        int soul = getSoul(gem);
        InteractionHand other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack target = player.getItemInHand(other);
        if (soul <= 0 || target.isEmpty() || !target.isEnchanted() || !target.isDamageableItem() || !target.isDamaged()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            float mult = Perks.has(sp, "enchanting.soul_squeezer") ? 1.5f : 1f;
            int repair = Math.max(1, Math.round(target.getMaxDamage() * 0.08f * strength(soul) * mult));
            target.setDamageValue(Math.max(0, target.getDamageValue() - repair));
            consume(gem);
            level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.8f);
            Progression.addSkillXp(sp, Skill.ENCHANTING, 0.04f * strength(soul));
            Notifier.message(sp, Component.translatable("message.nirncraft.arcane.recharged", target.getHoverName()));
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }
}
