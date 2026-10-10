package com.skycraft.arsenal.item;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.ArsenalTags;
import com.skycraft.core.Notifier;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.SpellCasting;
import com.skycraft.magic.spell.Spells;
import com.skycraft.skills.Progression;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A staff made at a Staff Enchanter (Dragonborn): it casts the spell bound into it (NBT {@value #SPELL}) without
 * magicka. Each cast spends a charge; concentration spells spend one per second of channelling. Charges come from the
 * soul used to make it and are refilled like any staff (sneak + use with a filled soul gem in the other hand).
 * Using it trains the spell's school at half the rate of casting the spell yourself.
 */
public class EnchantedStaffItem extends Item {
    public static final String SPELL = "skycraft_staff_spell";
    public static final String CHARGES = "skycraft_charges";
    public static final String MAX_CHARGES = "skycraft_max_charges";
    private static final ResourceLocation AZURAS_STAR = new ResourceLocation(Skycraft.MODID, "azuras_star");
    private static final ResourceLocation BLACK_STAR = new ResourceLocation(Skycraft.MODID, "black_star");

    public EnchantedStaffItem(Properties props) {
        super(props.stacksTo(1));
    }

    @Nullable
    public static Spell spell(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? null : Spells.byId(tag.getString(SPELL));
    }

    public static int maxCharges(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? 1 : Math.max(1, tag.getInt(MAX_CHARGES));
    }

    public static int charges(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : Math.max(0, Math.min(maxCharges(stack), tag.getInt(CHARGES)));
    }

    public static void setCharges(ItemStack stack, int charges) {
        stack.getOrCreateTag().putInt(CHARGES, Math.max(0, Math.min(maxCharges(stack), charges)));
    }

    /** Binds a spell (Staff Enchanter). */
    public static void bind(ItemStack stack, Spell spell, int maxCharges) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(SPELL, spell.id);
        tag.putInt(MAX_CHARGES, maxCharges);
        tag.putInt(CHARGES, maxCharges);
    }

    private static boolean spend(Player player, ItemStack stack) {
        int c = charges(stack);
        if (c <= 0) return false;
        if (!player.getAbilities().instabuild) setCharges(stack, c - 1);
        return true;
    }

    private static int side(Player player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? 1 : -1;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (player.isShiftKeyDown() && other.is(ArsenalTags.SOUL_GEMS)) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) recharge(sp, stack, other);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        Spell spell = spell(stack);
        if (spell == null) return InteractionResultHolder.pass(stack);
        if (charges(stack) <= 0) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                Notifier.message(sp, Component.translatable("message.skycraft.arsenal.staff_empty", stack.getHoverName()));
                level.playSound(null, player.blockPosition(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6f, 1.4f);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (spell.isConcentration()) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(stack);
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            Spell.Result result = SpellCasting.castExternal(sp, spell, 0, side(sp, hand));
            if (result != Spell.Result.FAILED) {
                spend(sp, stack);
                sp.getCooldowns().addCooldown(this, spell.chargeTicks > 0 ? 40 : 15);
                sp.swing(hand, true);
                if (result == Spell.Result.EFFECT) Progression.addSkillXp(sp, spell.school.skill, spell.cost * 0.5f);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        Spell spell = spell(stack);
        return spell != null && spell.isConcentration() ? 72000 : 0;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return getUseDuration(stack) > 0 ? UseAnim.BOW : UseAnim.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) return;
        Spell spell = spell(stack);
        if (spell == null || !spell.isConcentration()) return;
        int used = getUseDuration(stack) - remaining;
        if (charges(stack) <= 0) {
            player.releaseUsingItem();
            Notifier.message(player, Component.translatable("message.skycraft.arsenal.staff_empty", stack.getHoverName()));
            return;
        }
        int side = player.getUsedItemHand() == InteractionHand.MAIN_HAND ? 1 : -1;
        Spell.Result result = SpellCasting.castExternal(player, spell, used, side);
        if (result == Spell.Result.FAILED) {
            player.releaseUsingItem();
            return;
        }
        if (used > 0 && used % 20 == 0) {
            spend(player, stack);
            if (result == Spell.Result.EFFECT) Progression.addSkillXp(player, spell.school.skill, spell.cost * 0.5f);
        }
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    private void recharge(ServerPlayer player, ItemStack staff, ItemStack gem) {
        CompoundTag gemTag = gem.getTag();
        int soul = gemTag == null ? 0 : gemTag.getInt("soul");
        if (soul <= 0) {
            Notifier.message(player, Component.translatable("message.skycraft.arsenal.gem_empty"));
            return;
        }
        int max = maxCharges(staff);
        int charges = charges(staff);
        if (charges >= max) {
            Notifier.message(player, Component.translatable("message.skycraft.arsenal.staff_full", staff.getHoverName()));
            return;
        }
        setCharges(staff, charges + Math.max(1, Math.round(max * Math.min(5, soul) / 5f)));
        ResourceLocation gemId = ForgeRegistries.ITEMS.getKey(gem.getItem());
        if (AZURAS_STAR.equals(gemId) || BLACK_STAR.equals(gemId)) {
            gemTag.remove("soul");
            gemTag.remove("skycraft_value");
            if (gemTag.isEmpty()) gem.setTag(null);
        } else {
            gem.shrink(1);
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.7f);
        MagicFx.send(player, MagicFx.AURA, com.skycraft.magic.spell.Element.SOUL, player.position(), player.position(), player.getId(), 1);
        Progression.addSkillXp(player, com.skycraft.core.Skill.ENCHANTING, 0.04f * Math.min(5, soul));
        Notifier.message(player, Component.translatable("message.skycraft.arsenal.staff_recharged", staff.getHoverName()));
    }

    @Override
    public Component getName(ItemStack stack) {
        Spell spell = spell(stack);
        return spell == null ? super.getName(stack) : Component.translatable("item.skycraft.enchanted_staff.named", spell.displayName());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return spell(stack) != null;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return spell(stack) != null && charges(stack) < maxCharges(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13f * charges(stack) / maxCharges(stack));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x9B5DE5;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        Spell spell = spell(stack);
        if (spell == null) {
            tooltip.add(Component.translatable("tooltip.skycraft.enchanted_staff.blank").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.translatable("tooltip.skycraft.spell_school", spell.school.displayName(), spell.tier.displayName())
                .withStyle(s -> s.withColor(spell.school.color)));
        tooltip.add(spell.description().copy().withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.skycraft.arsenal.charges", charges(stack), maxCharges(stack)).withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("tooltip.skycraft.arsenal.recharge_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
