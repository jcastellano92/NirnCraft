package com.skycraft.realms.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import com.skycraft.inventory.ItemData;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.ItemEnchantments;
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
    private record Roll(ResourceKey<Enchantment> ench, int level, int weight) {}

    private static final Roll[] POOL = {
            new Roll(Enchantments.FIRE_ASPECT, 2, 10), new Roll(Enchantments.SHARPNESS, 3, 10),
            new Roll(Enchantments.SMITE, 4, 6), new Roll(Enchantments.KNOCKBACK, 2, 5),
            new Roll(Enchantments.LOOTING, 2, 4), new Roll(Enchantments.POWER, 3, 6),
            new Roll(Enchantments.FLAME, 1, 5), new Roll(Enchantments.PROTECTION, 3, 10),
            new Roll(Enchantments.FIRE_PROTECTION, 4, 8), new Roll(Enchantments.BLAST_PROTECTION, 3, 5),
            new Roll(Enchantments.PROJECTILE_PROTECTION, 3, 5), new Roll(Enchantments.FEATHER_FALLING, 4, 5),
            new Roll(Enchantments.THORNS, 2, 4), new Roll(Enchantments.RESPIRATION, 3, 3),
            new Roll(Enchantments.EFFICIENCY, 4, 5), new Roll(Enchantments.UNBREAKING, 3, 7),
            new Roll(Enchantments.FORTUNE, 2, 3)};

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

    public static void set(ItemStack stack, ResourceKey<Enchantment> ench, int level) {
        CompoundTag sigil = new CompoundTag();
        Identifier id = ench.identifier();
        sigil.putString("ench", id == null ? "minecraft:sharpness" : id.toString());
        sigil.putInt("lvl", level);
        ItemData.updateCustom(stack, data -> {
            data.put("sigil", sigil);
            data.putInt("skycraft_value", 150 + level * 60);
        });
    }

    @Nullable
    public static Holder<Enchantment> enchantment(ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag tag = ItemData.custom(stack);
        if (tag == null || !tag.contains("sigil")) return null;
        Identifier id = Identifier.tryParse(tag.getCompoundOrEmpty("sigil").getStringOr("ench", ""));
        // through EnchantmentsAccess: stones made on 1.20.1 may still name minecraft:sweeping (custom data is not datafixed)
        return id == null || registries == null ? null : com.skycraft.platform.EnchantmentsAccess.find(id, registries);
    }

    public static int level(ItemStack stack) {
        CompoundTag tag = ItemData.custom(stack);
        return tag == null ? 0 : Math.max(1, tag.getCompoundOrEmpty("sigil").getIntOr("lvl", 0));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stone = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        Holder<Enchantment> ench = enchantment(stone, level.registryAccess());
        ItemStack target = player.getOffhandItem();
        if (ench == null || target.isEmpty()) {
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.skycraft.core.Notifier.message(sp, Component.translatable("realm.skycraft.sigil.hint"));
            }
            return InteractionResult.FAIL;
        }
        int lvl = level(stone);
        ItemEnchantments current = EnchantmentHelper.getEnchantmentsForCrafting(target);
        boolean compatible = ench.value().canEnchant(target) && EnchantmentHelper.isEnchantmentCompatible(
                current.keySet().stream().filter(e -> e != ench).toList(), ench);
        if (!compatible || current.getLevel(ench) >= lvl) {
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.skycraft.core.Notifier.message(sp, Component.translatable("realm.skycraft.sigil.cannot", target.getHoverName()));
            }
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide()) {
            EnchantmentHelper.updateEnchantments(target, mutable -> mutable.set(ench, lvl));
            if (!player.getAbilities().instabuild) stone.shrink(1);
            level.playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1f, 0.7f);
            level.playSound(null, player.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.6f, 0.8f);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.skycraft.core.Notifier.message(sp, Component.translatable("realm.skycraft.sigil.bound", Enchantment.getFullname(ench, lvl),
                        target.getHoverName()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        Holder<Enchantment> ench = enchantment(stack, context.registries());
        if (ench != null) tooltip.accept(Enchantment.getFullname(ench, level(stack)).copy().withStyle(ChatFormatting.GOLD));
        tooltip.accept(Component.translatable("realm.skycraft.sigil.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
