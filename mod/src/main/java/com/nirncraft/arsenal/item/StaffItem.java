package com.nirncraft.arsenal.item;

import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.ArsenalTags;
import com.nirncraft.arsenal.StaffEffects;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.Skill;
import com.nirncraft.skills.Progression;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A Skyrim staff. Right-click casts (or, for streaming staves, hold to channel). Charges are stored in NBT
 * {@value #CHARGES}; a stack without the tag is fully charged. Sneak + use with a filled soul gem
 * ({@code #nirncraft:soul_gems}, NBT int {@code soul}) in the other hand recharges it.
 */
public class StaffItem extends Item {
    public static final String CHARGES = "nirncraft_charges";
    private static final Identifier AZURAS_STAR = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "azuras_star");
    private static final Identifier BLACK_STAR = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "black_star");

    public final StaffKind kind;

    public StaffItem(StaffKind kind, Properties props) {
        super(props.stacksTo(1));
        this.kind = kind;
    }

    // ------------------------------------------------------------------ charges

    public static int charges(ItemStack stack) {
        if (!(stack.getItem() instanceof StaffItem staff)) return 0;
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        if (tag == null || !tag.contains(CHARGES)) return staff.kind.maxCharges;
        return Math.max(0, Math.min(staff.kind.maxCharges, tag.getIntOr(CHARGES, 0)));
    }

    public static void setCharges(ItemStack stack, int charges) {
        if (stack.getItem() instanceof StaffItem staff) {
            com.nirncraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putInt(CHARGES, Math.max(0, Math.min(staff.kind.maxCharges, charges))));
        }
    }

    /** Spends one charge (creative players cast for free). Returns false if the staff is empty. */
    public static boolean spend(Player player, ItemStack stack) {
        int c = charges(stack);
        if (c <= 0) return false;
        if (!player.getAbilities().instabuild) setCharges(stack, c - 1);
        return true;
    }

    // ------------------------------------------------------------------ use

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (player.isShiftKeyDown() && other.is(ArsenalTags.SOUL_GEMS)) {
            if (!level.isClientSide() && player instanceof ServerPlayer sp) recharge(sp, stack, other);
            return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
        }
        if (charges(stack) <= 0) {
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                Notifier.message(sp, Component.translatable("message.nirncraft.arsenal.staff_empty", stack.getHoverName()));
                level.playSound(null, player.blockPosition(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6f, 1.4f);
            }
            return InteractionResult.FAIL;
        }
        if (kind.stream) {
            player.startUsingItem(hand);
            return InteractionResult.CONSUME.heldItemTransformedTo(stack);
        }
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            if (StaffEffects.cast(sp, stack, kind)) {
                spend(sp, stack);
                if (kind.cooldown > 0) sp.getCooldowns().addCooldown(sp.getItemInHand(hand), kind.cooldown);
            }
        }
        return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return kind.stream ? 72000 : 0;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return kind.stream ? ItemUseAnimation.BOW : ItemUseAnimation.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer player) || !kind.stream) return;
        int used = getUseDuration(stack, entity) - remaining;
        if (charges(stack) <= 0) {
            player.releaseUsingItem();
            Notifier.message(player, Component.translatable("message.nirncraft.arsenal.staff_empty", stack.getHoverName()));
            return;
        }
        StaffEffects.stream(player, stack, kind, used);
        if (used > 0 && used % 20 == 0) spend(player, stack);
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    // ------------------------------------------------------------------ recharge

    private void recharge(ServerPlayer player, ItemStack staff, ItemStack gem) {
        CompoundTag gemTag = com.nirncraft.inventory.ItemData.custom(gem);
        int soul = gemTag == null ? 0 : gemTag.getIntOr("soul",0);
        if (soul <= 0) {
            Notifier.message(player, Component.translatable("message.nirncraft.arsenal.gem_empty"));
            return;
        }
        int charges = charges(staff);
        if (charges >= kind.maxCharges) {
            Notifier.message(player, Component.translatable("message.nirncraft.arsenal.staff_full", staff.getHoverName()));
            return;
        }
        int restore = Math.max(1, Math.round(kind.maxCharges * Math.min(5, soul) / 5f));
        setCharges(staff, charges + restore);

        Identifier gemId = BuiltInRegistries.ITEM.getKey(gem.getItem());
        if (AZURAS_STAR.equals(gemId) || BLACK_STAR.equals(gemId)) {
            gemTag.remove("soul");
            gemTag.remove("nirncraft_value");
            com.nirncraft.inventory.ItemData.setCustom(gem, gemTag);
        } else {
            gem.shrink(1);
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.7f);
        if (player.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.SOUL, player.getX(), player.getY() + 1.2, player.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
        }
        Progression.addSkillXp(player, Skill.ENCHANTING, 0.04f * Math.min(5, soul));
        Notifier.message(player, Component.translatable("message.nirncraft.arsenal.staff_recharged", staff.getHoverName()));
    }

    // ------------------------------------------------------------------ display

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return charges(stack) < kind.maxCharges;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13f * charges(stack) / kind.maxCharges);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x9B5DE5;
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.nirncraft.staff." + kind.id).withStyle(ChatFormatting.AQUA));
        tooltip.accept(Component.translatable("tooltip.nirncraft.arsenal.charges", charges(stack), kind.maxCharges).withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.accept(Component.translatable("tooltip.nirncraft.arsenal.staff_skill", kind.skill.displayName()).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.accept(Component.translatable("tooltip.nirncraft.arsenal.recharge_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
