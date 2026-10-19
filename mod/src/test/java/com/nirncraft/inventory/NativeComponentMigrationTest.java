package com.nirncraft.inventory;

import com.nirncraft.platform.ArcaneData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.nirncraft.testing.BoundedServerProvider;
import net.minecraft.server.MinecraftServer;
import static org.junit.jupiter.api.Assertions.*;

class NativeComponentMigrationTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test @ExtendWith(BoundedServerProvider.class)
    void armorClassificationRetainsHumanoidAndZeroDefenseArmorOnly(MinecraftServer server) {
        assertTrue(EquipmentData.isArmor(new ItemStack(Items.IRON_HELMET)));
        assertFalse(EquipmentData.isArmor(new ItemStack(Items.CARVED_PUMPKIN)));
        assertFalse(EquipmentData.isArmor(new ItemStack(Items.ELYTRA)));
        assertFalse(EquipmentData.isArmor(new ItemStack(Items.DIAMOND_HORSE_ARMOR)));
        assertFalse(EquipmentData.isArmor(new ItemStack(Items.WOLF_ARMOR)));
        assertTrue(EquipmentData.isBodyArmor(new ItemStack(Items.DIAMOND_HORSE_ARMOR)));
        ItemStack robe = new ItemStack(Items.IRON_CHESTPLATE);
        robe.set(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.builder()
                .add(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                    new net.minecraft.world.entity.ai.attributes.AttributeModifier(net.minecraft.resources.Identifier.parse("nirncraft:test_zero_armor"), 0, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
                    net.minecraft.world.entity.EquipmentSlotGroup.CHEST).build());
        assertTrue(EquipmentData.isArmor(robe), "Zero-defense armor must retain its classification");
        assertEquals(0, EquipmentData.defense(robe));
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void customDataIsAnExplicitTransactionAndDoesNotLoseOtherComponents(MinecraftServer server) {
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("A named blade"));
        ItemData.updateCustom(stack, tag -> tag.putInt("temper", 3));
        var detached = ItemData.custom(stack);
        detached.putInt("temper", 9);
        assertEquals(3, ItemData.custom(stack).getIntOr("temper", 0));
        ItemData.updateCustom(stack, tag -> tag.putInt("temper", 4));
        assertEquals(4, ItemData.custom(stack).getIntOr("temper", 0));
        assertEquals("A named blade", stack.getHoverName().getString());
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void inventoryQueriesRetainTypedOverridesAndIgnoreUnrelatedPayloads(MinecraftServer server) {
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("An owned blade"));
        ItemData.updateCustom(stack, tag -> tag.putByteArray("unrelated_owner_payload", new byte[16_384]));
        var original = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        float ordinaryWeight = ItemWeights.base(stack.getItem());
        assertEquals(ordinaryWeight, ItemWeights.get(stack));
        assertFalse(InvCategory.isFavorite(stack));
        assertFalse(InvCategory.isStolen(stack));
        assertSame(original, stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA));
        ItemData.updateCustom(stack, tag -> {
            tag.putFloat(ItemWeights.WEIGHT_NBT, 12.5f);
            tag.putBoolean(InvCategory.FAVORITE_NBT, false);
            tag.putBoolean(InvCategory.STOLEN_NBT, true);
        });
        assertEquals(12.5f, ItemWeights.get(stack));
        assertFalse(InvCategory.isFavorite(stack));
        assertTrue(InvCategory.isStolen(stack));
        ItemData.updateCustom(stack, tag -> {
            tag.putInt(ItemWeights.WEIGHT_NBT, -7);
            tag.putInt(InvCategory.FAVORITE_NBT, 2);
            tag.putString(InvCategory.STOLEN_NBT, "not a boolean");
        });
        assertEquals(0f, ItemWeights.get(stack));
        assertEquals(ItemData.custom(stack).getBooleanOr(InvCategory.FAVORITE_NBT, false), InvCategory.isFavorite(stack));
        assertFalse(InvCategory.isStolen(stack));
        ItemData.updateCustom(stack, tag -> tag.putString(ItemWeights.WEIGHT_NBT, "not a number"));
        assertEquals(ordinaryWeight, ItemWeights.get(stack));
        assertEquals("An owned blade", stack.getHoverName().getString());
        assertEquals(16_384, ItemData.custom(stack).getByteArray("unrelated_owner_payload").orElseThrow().length);
        ItemStack purse = com.nirncraft.core.Currency.coins(500);
        assertEquals(500, com.nirncraft.core.Currency.valueOf(purse));
        ItemData.updateCustom(purse, tag -> tag.putLong("gold", 0));
        assertEquals(25, com.nirncraft.core.Currency.valueOf(purse));
    }

    @Test void plainStyledTextWorksOffThreadWithoutAnyLoadedWorld() throws Exception {
        Component component = Component.translatable("message.nirncraft.example", "value").withColor(0xAABBCC);
        var text = java.util.concurrent.CompletableFuture.supplyAsync(() -> ArcaneData.writeComponent(component)).get();
        var restored = java.util.concurrent.CompletableFuture.supplyAsync(() -> ArcaneData.readComponent(text)).get();
        assertEquals(component, restored);
    }

    @Test void nativeLevelDataFixerRetainsTypedCustomSurvivalRule() {
        var rules = new net.minecraft.nbt.CompoundTag();
        rules.putBoolean("nirncraft:survival_mode", false);
        rules.putString("doDaylightCycle", "false");
        var level = new net.minecraft.nbt.CompoundTag();
        level.put("GameRules", rules);
        var fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(
                net.minecraft.util.datafix.fixes.References.LEVEL,
                new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, level),
                3465, SharedConstants.getCurrentVersion().dataVersion().version());
        var upgraded = ((net.minecraft.nbt.CompoundTag) fixed.getValue()).getCompoundOrEmpty("game_rules");
        assertFalse(upgraded.getBoolean("nirncraft:survival_mode").orElseThrow());
        assertFalse(upgraded.getBoolean("minecraft:advance_time").orElseThrow());
        var decoded = net.minecraft.world.level.gamerules.GameRuleMap.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, upgraded).getOrThrow();
        assertEquals(Boolean.FALSE, decoded.get(com.nirncraft.survival.mode.SurvivalMode.RULE));
        assertEquals(Boolean.FALSE, decoded.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME));
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void legacyCustomStacksPreserveNativeAndCustomDataAndLargeCounts(MinecraftServer server) {
        var root = new net.minecraft.nbt.CompoundTag();
        root.putString("id", "minecraft:leather_chestplate"); root.putByte("Count", (byte) 3);
        var custom = new net.minecraft.nbt.CompoundTag(); custom.putInt("nirncraft_temper", 7);
        var display = new net.minecraft.nbt.CompoundTag();
        display.putInt("color", 0x506070); display.putString("Name", "{\"text\":\"Legacy armor\"}");
        custom.put("display", display);
        var enchants = new net.minecraft.nbt.ListTag(); var protection = new net.minecraft.nbt.CompoundTag();
        protection.putString("id", "minecraft:protection"); protection.putShort("lvl", (short) 2);
        enchants.add(protection); custom.put("Enchantments", enchants); root.put("tag", custom);
        var migrated = ItemData.parse(root, server.registryAccess());
        assertEquals(3, migrated.getCount());
        assertEquals(7, ItemData.customOrEmpty(migrated).getIntOr("nirncraft_temper", 0));
        assertEquals("Legacy armor", migrated.getHoverName().getString());
        assertEquals(0x506070, migrated.get(net.minecraft.core.component.DataComponents.DYED_COLOR).rgb());
        assertTrue(migrated.isEnchanted());
        migrated.setCount(1_000_000);
        var restored = ItemData.parse(ItemData.save(migrated, server.registryAccess()), server.registryAccess());
        assertTrue(ItemStack.matches(migrated, restored), "Bulk counts and complete native components must survive custom persistence");
        assertEquals(1_000_000, restored.getCount());
        assertEquals(3, root.getIntOr("Count", 0), "Migration must not mutate the source compound");
    }
}
