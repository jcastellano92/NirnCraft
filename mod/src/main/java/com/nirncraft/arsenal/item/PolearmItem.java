package com.nirncraft.arsenal.item;

import com.nirncraft.crafting.SmithingTier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.ItemAbilities;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A spear, halberd or glaive. A sword-like tiered weapon with extra reach (Forge's entity reach attribute; Better
 * Combat's own movesets add their range on top). Tagged {@code #nirncraft:polearms}, so hits train the Polearms skill.
 */
public class PolearmItem extends Item {
    private static final Identifier REACH_ID = Identifier.fromNamespaceAndPath("nirncraft", "polearm_reach");

    public final SmithingTier smithingTier;
    public final PolearmKind kind;

    public PolearmItem(SmithingTier tier, PolearmKind kind, Properties props) {
        super(properties(tier, kind, props));
        this.smithingTier = tier;
        this.kind = kind;
    }

    private static Properties properties(SmithingTier tier, PolearmKind kind, Properties props) {
        props.sword(tier.vanilla(), kind.damage, kind.speed).repairable(tier.material())
                .durability(Math.max(1, Math.round(tier.getUses() * kind.durabilityMult)));
        return props.attributes(ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
                        kind.damage + tier.vanilla().attackDamageBonus(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID,
                        kind.speed, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(REACH_ID,
                        kind.reach, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build());
    }

    @Override
    public boolean canPerformAction(ItemInstance stack, ItemAbility toolAction) {
        if (toolAction == ItemAbilities.SWORD_SWEEP) return kind.sweeps();
        return super.canPerformAction(stack, toolAction);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.nirncraft.polearm." + kind.id()).withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.accept(Component.translatable(kind.twoHanded ? "tooltip.nirncraft.polearm.two_handed" : "tooltip.nirncraft.polearm.one_handed")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }
}
