package com.skycraft.arsenal.item;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.skycraft.crafting.SmithingTier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A spear, halberd or glaive. A sword-like tiered weapon with extra reach (Forge's entity reach attribute; Better
 * Combat's own movesets add their range on top). Tagged {@code #skycraft:polearms}, so hits train the Polearms skill.
 */
public class PolearmItem extends SwordItem {
    private static final UUID REACH_ID = UUID.fromString("7d4a3c1e-5b2f-4e8a-9c6d-1f0e2a3b4c5d");

    public final SmithingTier smithingTier;
    public final PolearmKind kind;
    private final Supplier<Multimap<Attribute, AttributeModifier>> mainHand;

    public PolearmItem(SmithingTier tier, PolearmKind kind, Properties props) {
        super(tier, kind.damage, kind.speed, props);
        this.smithingTier = tier;
        this.kind = kind;
        // Built lazily: Forge's reach attribute may not be registered yet when items are constructed.
        this.mainHand = Suppliers.memoize(() -> {
            ImmutableMultimap.Builder<Attribute, AttributeModifier> b = ImmutableMultimap.builder();
            b.putAll(super.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND));
            b.put(ForgeMod.ENTITY_REACH.get(), new AttributeModifier(REACH_ID, "Polearm reach", kind.reach, AttributeModifier.Operation.ADDITION));
            return b.build();
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? mainHand.get() : super.getDefaultAttributeModifiers(slot);
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ToolAction toolAction) {
        if (toolAction == ToolActions.SWORD_SWEEP && !kind.sweeps()) return false;
        return super.canPerformAction(stack, toolAction);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.skycraft.polearm." + kind.id()).withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.add(Component.translatable(kind.twoHanded ? "tooltip.skycraft.polearm.two_handed" : "tooltip.skycraft.polearm.one_handed")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }
}
