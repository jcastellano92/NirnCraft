package com.skycraft.realms.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * A Sigil Stone torn from the top of a Great Sigil Tower: it holds one enchantment, burned into it by the Daedra.
 * Hold the stone in the main hand and the weapon, armour piece or tool in the off hand, then use the stone: the
 * enchantment is bound to that item (when it can take it) and the stone crumbles. NBT {@code sigil}: {@code ench}
 * (enchantment id) and {@code lvl}.
 */
public class SigilStoneItem extends Item {
    private record Roll(Enchantment ench, int level, int weight) {}

    private static final Roll[] POOL = {
            new Roll(Enchantments.FIRE_ASPECT, 2, 10), new Roll(Enchantments.SHARPNESS, 3, 10),
            new Roll(Enchantments.SMITE, 4, 6), new Roll(Enchantments.KNOCKBACK, 2, 5),
            new Roll(Enchantments.MOB_LOOTING, 2, 4), new Roll(Enchantments.POWER_ARROWS, 3, 6),
            new Roll(Enchantments.FLAMING_ARROWS, 1, 5), new Roll(Enchantments.ALL_DAMAGE_PROTECTION, 3, 10),
            new Roll(Enchantments.FIRE_PROTECTION, 4, 8), new Roll(Enchantments.BLAST_PROTECTION, 3, 5),
            new Roll(Enchantments.PROJECTILE_PROTECTION, 3, 5), new Roll(Enchantments.FALL_PROTECTION, 4, 5),
            new Roll(Enchantments.THORNS, 2, 4), new Roll(Enchantments.RESPIRATION, 3, 3),
            new Roll(Enchantments.BLOCK_EFFICIENCY, 4, 5), new Roll(Enchantments.UNBREAKING, 3, 7),
            new Roll(Enchantments.BLOCK_FORTUNE, 2, 3)};

    public SigilStoneItem(Properties properties) {
        super(properties.stacksTo(16).rarity(Rarity.RARE).fireResistant());
    }

    /** A new Sigil Stone with a rolled enchantment. */
    public static ItemStack roll(Item item, RandomSource random) {
        int total = 0;
        for (Roll r : POOL) total += r.weight;
        int pick = random.nextInt(total);
        Roll chosen = POOL[0];
        for (Roll r : POOL) {
            pick -= r.weight;
            if (pick < 0) {
                chosen = r;
                break;
            }
        }
        ItemStack stack = new ItemStack(item);
        set(stack, chosen.ench, chosen.level);
        return stack;
    }

    public static void set(ItemStack stack, Enchantment ench, int level) {
        CompoundTag sigil = new CompoundTag();
        ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(ench);
        sigil.putString("ench", id == null ? "minecraft:sharpness" : id.toString());
        sigil.putInt("lvl", level);
        stack.getOrCreateTag().put("sigil", sigil);
        stack.getOrCreateTag().putInt("skycraft_value", 150 + level * 60);
    }

    @Nullable
    public static Enchantment enchantment(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("sigil")) return null;
        ResourceLocation id = ResourceLocation.tryParse(tag.getCompound("sigil").getString("ench"));
        return id == null ? null : BuiltInRegistries.ENCHANTMENT.get(id);
    }

    public static int level(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : Math.max(1, tag.getCompound("sigil").getInt("lvl"));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stone = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stone);
        Enchantment ench = enchantment(stone);
        ItemStack target = player.getOffhandItem();
        if (ench == null || target.isEmpty()) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("realm.skycraft.sigil.hint"), true);
            return InteractionResultHolder.fail(stone);
        }
        int lvl = level(stone);
        Map<Enchantment, Integer> current = EnchantmentHelper.getEnchantments(target);
        boolean compatible = ench.canEnchant(target) && EnchantmentHelper.isEnchantmentCompatible(
                current.keySet().stream().filter(e -> e != ench).toList(), ench);
        if (!compatible || current.getOrDefault(ench, 0) >= lvl) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("realm.skycraft.sigil.cannot", target.getHoverName()), true);
            }
            return InteractionResultHolder.fail(stone);
        }
        if (!level.isClientSide) {
            current.put(ench, lvl);
            EnchantmentHelper.setEnchantments(current, target);
            if (!player.getAbilities().instabuild) stone.shrink(1);
            level.playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1f, 0.7f);
            level.playSound(null, player.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.6f, 0.8f);
            player.displayClientMessage(Component.translatable("realm.skycraft.sigil.bound", ench.getFullname(lvl),
                    target.getHoverName()), true);
        }
        return InteractionResultHolder.sidedSuccess(stone, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        Enchantment ench = enchantment(stack);
        if (ench != null) tooltip.add(ench.getFullname(level(stack)).copy().withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("realm.skycraft.sigil.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
