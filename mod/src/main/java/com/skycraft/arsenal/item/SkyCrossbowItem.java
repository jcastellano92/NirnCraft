package com.skycraft.arsenal.item;

import com.skycraft.arsenal.ArsenalTags;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Dawnguard crossbows. They load arrows and bolts ({@code #skycraft:bolts}), hit harder than bows (+{@code bonus}
 * base damage) and pierce armor: the arsenal events let their bolts ignore {@code armorPierce} of the target's armor.
 */
public class SkyCrossbowItem extends CrossbowItem {
    public final float bonusDamage;
    public final float armorPierce;

    public SkyCrossbowItem(float bonusDamage, float armorPierce, Properties props) {
        super(props.enchantable(1).component(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,
                net.minecraft.world.item.component.ChargedProjectiles.EMPTY));
        this.bonusDamage = bonusDamage;
        this.armorPierce = armorPierce;
    }

    @Override
    public Predicate<ItemStack> getAllSupportedProjectiles() {
        return stack -> ARROW_ONLY.test(stack) || stack.is(ArsenalTags.BOLTS);
    }

    @Override
    public Predicate<ItemStack> getSupportedHeldProjectiles() {
        return stack -> ARROW_OR_FIREWORK.test(stack) || stack.is(ArsenalTags.BOLTS);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context263, display263, tooltip, flag);
        tooltip.accept(Component.translatable("tooltip.skycraft.arsenal.crossbow_damage", String.format(Locale.ROOT, "+%.1f", bonusDamage))
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.skycraft.arsenal.armor_pierce", Math.round(armorPierce * 100))
                .withStyle(ChatFormatting.BLUE));
    }

}
