package com.nirncraft.inventory;

import com.nirncraft.arsenal.ArsenalItems;
import com.nirncraft.arsenal.item.*;
import com.nirncraft.crafting.CraftingItems;
import com.nirncraft.crafting.SmithingTier;
import com.nirncraft.crafting.SkyArmorMaterial;
import com.nirncraft.crafting.WeaponType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import com.nirncraft.testing.BoundedServerProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.*;

/** Compare actual registered native components to the authored pre-migration material catalogue. */
@ExtendWith(BoundedServerProvider.class)
class NativeEquipmentDefaultsTest {
    @BeforeAll static void loadedComponents(MinecraftServer server) {
        assertNotNull(server.registryAccess());
        assertNotNull(Items.IRON_SWORD.components());
    }

    private static double attribute(ItemStack stack, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, EquipmentSlot slot) {
        return stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).compute(attribute, 0, slot);
    }

    @Test void everyRegisteredCraftedWeaponRetainsDurabilityDamageSpeedAndRepairMaterial() {
        for (var entry : CraftingItems.WEAPONS.entrySet()) {
            SmithingTier tier = entry.getKey();
            for (var weapon : entry.getValue().entrySet()) {
                WeaponType type = weapon.getKey(); ItemStack stack = new ItemStack(weapon.getValue().get());
                String id = type.itemId(tier);
                int durability = type == WeaponType.BOW ? 200 + Math.round(tier.getUses() * 0.8f)
                        : Math.max(1, Math.round(tier.getUses() * type.durabilityMult));
                assertEquals(durability, stack.getMaxDamage(), id);
                assertEquals(tier.getEnchantmentValue(), stack.get(DataComponents.ENCHANTABLE).value(), id);
                assertTrue(stack.get(DataComponents.REPAIRABLE).items().contains(tier.material().builtInRegistryHolder()), id);
                if (type != WeaponType.BOW) {
                    assertEquals(type.damage + tier.getAttackDamageBonus(), attribute(stack, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND), 0.00001, id);
                    assertEquals(type.speed, attribute(stack, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND), 0.00001, id);
                    if (!type.axe()) assertEquals(type == WeaponType.SWORD || type == WeaponType.GREATSWORD,
                            stack.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SWORD_SWEEP), id);
                }
            }
        }
    }

    @Test void everyRegisteredArmorPieceAndShieldRetainsMaterialAndBlockingComponents() {
        for (var entry : CraftingItems.ARMOR.entrySet()) {
            SkyArmorMaterial material = entry.getKey();
            for (var piece : entry.getValue().entrySet()) {
                var type = piece.getKey(); ItemStack stack = new ItemStack(piece.getValue().get()); String id = material.itemId(type);
                assertTrue(EquipmentData.isArmor(stack), id);
                assertEquals(type.getSlot(), EquipmentData.slot(stack), id);
                assertEquals(material.getDefenseForType(type), EquipmentData.defense(stack), 0.00001, id);
                assertEquals(material.getToughness(), EquipmentData.toughness(stack), 0.00001, id);
                assertEquals(material.getKnockbackResistance(), attribute(stack, Attributes.KNOCKBACK_RESISTANCE, type.getSlot()), 0.00001, id);
                assertEquals(material.getDurabilityForType(type), stack.getMaxDamage(), id);
                assertEquals(material.getEnchantmentValue(), stack.get(DataComponents.ENCHANTABLE).value(), id);
                assertTrue(stack.get(DataComponents.REPAIRABLE).items().contains(material.material().builtInRegistryHolder()), id);
            }
        }
        for (var entry : CraftingItems.SHIELDS.entrySet()) {
            ItemStack stack = new ItemStack(entry.getValue().get()); SkyArmorMaterial material = entry.getKey();
            assertEquals(material.shieldDurability(), stack.getMaxDamage(), material.shieldId());
            assertEquals(material.shieldDefense, EquipmentData.defense(stack), 0.00001, material.shieldId());
            assertEquals(material.getToughness()*0.5, EquipmentData.toughness(stack), 0.00001, material.shieldId());
            assertEquals(EquipmentSlot.OFFHAND, EquipmentData.slot(stack));
            assertEquals(Items.SHIELD.components().get(DataComponents.BLOCKS_ATTACKS), stack.get(DataComponents.BLOCKS_ATTACKS));
            assertEquals(Items.SHIELD.components().get(DataComponents.BREAK_SOUND), stack.get(DataComponents.BREAK_SOUND));
        }
        assertEquals(Items.SHIELD.components().get(DataComponents.BLOCKS_ATTACKS), ArsenalItems.SPELLBREAKER.get().components().get(DataComponents.BLOCKS_ATTACKS));
    }

    @Test void polearmMultipliersAndArtifactDurabilitySurviveNativeWeaponHelpers() {
        for (var entry : ArsenalItems.POLEARMS.entrySet()) {
            SmithingTier tier = entry.getKey();
            for (var polearm : entry.getValue().entrySet()) {
                PolearmKind kind = polearm.getKey(); ItemStack stack = new ItemStack(polearm.getValue().get()); String id = kind.itemId(tier);
                assertEquals(Math.max(1,Math.round(tier.getUses()*kind.durabilityMult)), stack.getMaxDamage(), id);
                assertEquals(kind.damage+tier.getAttackDamageBonus(), attribute(stack,Attributes.ATTACK_DAMAGE,EquipmentSlot.MAINHAND),0.00001,id);
                assertEquals(kind.speed, attribute(stack,Attributes.ATTACK_SPEED,EquipmentSlot.MAINHAND),0.00001,id);
                assertEquals(kind.reach, attribute(stack,Attributes.ENTITY_INTERACTION_RANGE,EquipmentSlot.MAINHAND),0.00001,id);
                assertEquals(kind.sweeps(), stack.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SWORD_SWEEP),id);
            }
        }
        for (var artifact : ArsenalItems.ARTIFACTS) {
            var item = artifact.get();
            if (item instanceof ArtifactMeleeItem || item instanceof ArtifactAxeItem || item instanceof ArtifactBowItem || item instanceof SpellbreakerItem)
                assertEquals(ArtifactItem.DURABILITY,new ItemStack(item).getMaxDamage(),item.toString());
        }
    }

    @Test void stavesUseChargesAndCrossbowsRetainNativeEnchantability() {
        for (var entry : ArsenalItems.STAVES.entrySet()) {
            ItemStack stack = new ItemStack(entry.getValue().get()); StaffKind kind = entry.getKey();
            assertEquals(1, stack.getMaxStackSize(), kind.id); assertFalse(stack.isDamageableItem(),kind.id);
            assertEquals(kind.maxCharges,StaffItem.charges(stack),kind.id);
            StaffItem.setCharges(stack,0); assertEquals(0,StaffItem.charges(stack),kind.id);
            StaffItem.setCharges(stack,kind.maxCharges+1); assertEquals(kind.maxCharges,StaffItem.charges(stack),kind.id);
        }
        for (var crossbow : ArsenalItems.CROSSBOWS) {
            var stack = new ItemStack(crossbow.get());
            assertEquals(1,stack.get(DataComponents.ENCHANTABLE).value());
            assertNotNull(stack.get(DataComponents.CHARGED_PROJECTILES));
        }
    }
}
