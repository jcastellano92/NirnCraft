package com.nirncraft.magic.bound;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Bound Battleaxe (Conjuration, Adept): a two-handed Daedric axe. */
public class BoundBattleaxeItem extends Item implements BoundWeapons.BoundItem {
    public BoundBattleaxeItem(Properties props) {
        super(BoundTier.weapon(props.stacksTo(1).rarity(Rarity.RARE).fireResistant(), 6.0f, -3.1f, true));
    }

    /** No log stripping with a conjured axe. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel level, Entity entity, net.minecraft.world.entity.EquipmentSlot slot) {
        BoundWeapons.inventoryTick(stack, level, entity);
    }

    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        return BoundWeapons.onEntityItemUpdate(entity);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        BoundTooltip.add(stack, context.level(), tooltip);
    }
}
