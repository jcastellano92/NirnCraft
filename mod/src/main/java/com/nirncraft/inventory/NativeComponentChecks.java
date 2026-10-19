package com.nirncraft.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Runs with real loaded registries in the existing inventory acceptance command/autorun. */
final class NativeComponentChecks {
    private NativeComponentChecks() {}
    static void run(net.minecraft.server.level.ServerLevel level, PackSelfTest.Checks checks) {
        checks.check("components: humanoid armor classification", EquipmentData.isArmor(new ItemStack(Items.IRON_HELMET)), "iron helmet");
        checks.check("components: costume and glider excluded", !EquipmentData.isArmor(new ItemStack(Items.CARVED_PUMPKIN)) && !EquipmentData.isArmor(new ItemStack(Items.ELYTRA)), "pumpkin and elytra");
        checks.check("components: body equipment classified separately", !EquipmentData.isArmor(new ItemStack(Items.DIAMOND_HORSE_ARMOR)) && !EquipmentData.isArmor(new ItemStack(Items.WOLF_ARMOR)) && EquipmentData.isBodyArmor(new ItemStack(Items.DIAMOND_HORSE_ARMOR)), "horse and wolf armor");
        ItemStack robe = new ItemStack(Items.IRON_CHESTPLATE);
        robe.set(DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.builder()
                .add(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                        new net.minecraft.world.entity.ai.attributes.AttributeModifier(net.minecraft.resources.Identifier.parse("nirncraft:test_zero_armor"), 0, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
                        net.minecraft.world.entity.EquipmentSlotGroup.CHEST).build());
        checks.check("components: zero-defense armor retains classification", EquipmentData.isArmor(robe) && EquipmentData.defense(robe) == 0, "zero armor attribute");
        ItemStack named = new ItemStack(Items.IRON_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("A named blade"));
        ItemData.updateCustom(named, t -> t.putInt("temper", 3));
        ItemData.custom(named).putInt("temper", 9);
        boolean detached = ItemData.custom(named).getIntOr("temper", 0) == 3;
        ItemData.updateCustom(named, t -> t.putInt("temper", 4));
        checks.check("components: detached custom-data edits never leak", detached && ItemData.custom(named).getIntOr("temper", 0) == 4 && named.getHoverName().getString().equals("A named blade"), "native name retained");
        Component text = Component.translatable("message.nirncraft.example", "value").withColor(0xAABBCC);
        Component restored = java.util.concurrent.CompletableFuture.supplyAsync(() -> com.nirncraft.platform.ArcaneData.readComponent(com.nirncraft.platform.ArcaneData.writeComponent(text))).join();
        checks.check("components: authored text works off-thread", text.equals(restored), "literal/translatable style without world registries");
        legacy(level, checks);
    }
    private static void legacy(net.minecraft.server.level.ServerLevel level, PackSelfTest.Checks checks) {
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        root.putString("id", "minecraft:leather_chestplate"); root.putByte("Count", (byte) 3);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag(); tag.putInt("nirncraft_temper", 7);
        net.minecraft.nbt.CompoundTag display = new net.minecraft.nbt.CompoundTag(); display.putInt("color", 0x506070); display.putString("Name", "{\"text\":\"Legacy armor\"}"); tag.put("display", display);
        net.minecraft.nbt.ListTag enchants = new net.minecraft.nbt.ListTag(); net.minecraft.nbt.CompoundTag sharp = new net.minecraft.nbt.CompoundTag(); sharp.putString("id", "minecraft:protection"); sharp.putShort("lvl", (short) 2); enchants.add(sharp); tag.put("Enchantments", enchants); root.put("tag", tag);
        ItemStack migrated = ItemData.parse(root, level.registryAccess());
        checks.check("components: legacy custom item data migrates", migrated.getCount() == 3 && ItemData.customOrEmpty(migrated).getIntOr("nirncraft_temper", 0) == 7, "1.20.1 custom save stack");
        checks.check("components: legacy native item state migrates", migrated.getHoverName().getString().equals("Legacy armor") && migrated.get(DataComponents.DYED_COLOR).rgb() == 0x506070 && migrated.isEnchanted(), "name, dye and enchantments");
        checks.check("components: migrated stack native round trip", ItemStack.matches(migrated, ItemData.parse(ItemData.save(migrated, level.registryAccess()), level.registryAccess())), "registry-aware codec");
    }
}
