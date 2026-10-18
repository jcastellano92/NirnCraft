package com.skycraft.magic.bound;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.Weapon;

/** Conjured weapons use normal attack/tool components without durability, repair or enchanting components. */
public enum BoundTier {
    INSTANCE;

    public static Item.Properties weapon(Item.Properties properties, float damage, float speed, boolean axe) {
        var blocks = BuiltInRegistries.acquireBootstrapRegistrationLookup(BuiltInRegistries.BLOCK);
        var rules = axe ? java.util.List.of(
                Tool.Rule.deniesDrops(blocks.getOrThrow(BlockTags.INCORRECT_FOR_DIAMOND_TOOL)),
                Tool.Rule.minesAndDrops(blocks.getOrThrow(BlockTags.MINEABLE_WITH_AXE), 8f)) : java.util.List.of(
                Tool.Rule.minesAndDrops(net.minecraft.core.HolderSet.direct(net.minecraft.world.level.block.Blocks.COBWEB.builtInRegistryHolder()), 15f),
                Tool.Rule.overrideSpeed(blocks.getOrThrow(BlockTags.SWORD_INSTANTLY_MINES), Float.MAX_VALUE),
                Tool.Rule.overrideSpeed(blocks.getOrThrow(BlockTags.SWORD_EFFICIENT), 1.5f));
        return properties.component(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE)
                .component(DataComponents.TOOL, new Tool(rules, 1f, axe ? 1 : 2, axe))
                .component(DataComponents.WEAPON, new Weapon(axe ? 2 : 1, axe ? 5f : 0f))
                .attributes(ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, damage + 2,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, speed,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build());
    }
}
