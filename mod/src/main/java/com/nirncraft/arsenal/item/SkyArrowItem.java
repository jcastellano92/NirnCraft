package com.nirncraft.arsenal.item;

import com.nirncraft.arsenal.entity.SkyArrow;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** A Skyrim arrow or crossbow bolt: shoots a {@link SkyArrow} with the kind's base damage and element. */
public class SkyArrowItem extends ArrowItem {
    public final ArrowKind kind;

    public SkyArrowItem(ArrowKind kind, Properties props) {
        super(props);
        this.kind = kind;
    }

    @Override
    public AbstractArrow createArrow(Level level, ItemStack stack, LivingEntity shooter, @Nullable ItemStack firedFromWeapon) {
        SkyArrow arrow = new SkyArrow(level, shooter, stack, firedFromWeapon);
        arrow.setBaseDamage(kind.damage);
        if (kind.element == ArrowKind.Element.FIRE) arrow.igniteForSeconds(100);
        return arrow;
    }

    @Override
    public net.minecraft.world.entity.projectile.Projectile asProjectile(Level level, net.minecraft.core.Position position, ItemStack stack, net.minecraft.core.Direction direction) {
        SkyArrow arrow = new SkyArrow(level, position.x(), position.y(), position.z(), stack);
        arrow.pickup = AbstractArrow.Pickup.ALLOWED; arrow.setBaseDamage(kind.damage);
        if (kind.element == ArrowKind.Element.FIRE) arrow.igniteForSeconds(100);
        return arrow;
    }

    /** Skyrim arrows never benefit from Infinity. */
    @Override
    public boolean isInfinite(ItemStack stack, ItemStack bow, net.minecraft.world.entity.LivingEntity player) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.nirncraft.arsenal.arrow_damage", String.format(Locale.ROOT, "%.1f", kind.damage))
                .withStyle(ChatFormatting.GRAY));
        if (kind.element != ArrowKind.Element.NONE) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.arsenal.arrow." + kind.element.name().toLowerCase(Locale.ROOT))
                    .withStyle(ChatFormatting.AQUA));
        }
        if (kind.bolt) tooltip.accept(Component.translatable("tooltip.nirncraft.arsenal.bolt").withStyle(ChatFormatting.DARK_GRAY));
    }
}
