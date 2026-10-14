package com.skycraft.inventory;

import com.skycraft.combat.WeaponClass;
import com.skycraft.core.Notifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.ForgeEventFactory;
import org.jetbrains.annotations.Nullable;

/**
 * Server side of the Skyrim inventory screen and the favorites menu. Every request names a slot of the player's own
 * inventory and the item expected there; anything that doesn't match the server's state is ignored.
 */
public final class InventoryActions {
    /** {@link Inventory#getItem(int)} index of the first armor slot (feet); head is {@code ARMOR_START + 3}. */
    public static final int ARMOR_START = Inventory.INVENTORY_SIZE;
    public static final int OFFHAND = Inventory.SLOT_OFFHAND;
    public static final int SLOT_COUNT = OFFHAND + 1;

    private InventoryActions() {}

    static void handle(ServerPlayer player, int action, int slot, ResourceLocation itemId) {
        if (!player.isAlive() || player.isSpectator()) return;
        // only while no other container is open (the Skyrim screen is not a container screen)
        if (player.containerMenu != player.inventoryMenu) return;
        if (!player.inventoryMenu.getCarried().isEmpty()) return;
        Inventory inv = player.getInventory();
        if (Pack.isVirtual(slot)) {
            // a row that lives only in the overflow pack: favorite and drop act on the pack directly, everything else
            // pulls one stack into a real slot first and then runs the normal slot rules
            int index = slot - Pack.VIRTUAL_BASE;
            ItemStack entry = Pack.get(player, index);
            if (entry.isEmpty() || !BuiltInRegistries.ITEM.getKey(entry.getItem()).equals(itemId)) {
                // Stale virtual index: search pack first
                int foundIdx = Pack.find(player, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).equals(itemId));
                if (foundIdx >= 0) {
                    index = foundIdx;
                    entry = Pack.get(player, index);
                } else {
                    // Check if it already drained/refilled into real slots
                    int real = -1;
                    for (int i = 0; i < SLOT_COUNT; i++) {
                        ItemStack s = inv.getItem(i);
                        if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).equals(itemId)) {
                            real = i;
                            break;
                        }
                    }
                    if (real >= 0) {
                        slot = real;
                    } else {
                        return;
                    }
                }
            }
            if (Pack.isVirtual(slot)) {
                if (action == InventoryPackets.InvAction.FAVORITE) {
                    Pack.setFavorite(player, entry, !InvCategory.isFavorite(entry));
                    CarryWeight.invalidate(player);
                    player.containerMenu.broadcastChanges();
                    Pack.sync(player);
                    return;
                }
                if (action == InventoryPackets.InvAction.DROP_ONE || action == InventoryPackets.InvAction.DROP_ALL) {
                    if (com.skycraft.quest.QuestItems.isQuestItem(entry)) {
                        message(player, "quest_item");
                        return;
                    }
                    tossAll(player, inv, Pack.take(player, index, action == InventoryPackets.InvAction.DROP_ALL ? Integer.MAX_VALUE : 1));
                    CarryWeight.invalidate(player);
                    player.containerMenu.broadcastChanges();
                    Pack.sync(player);
                    return;
                }
                slot = Pack.materialize(player, index, itemId);
                if (slot < 0) {
                    message(player, "full");
                    return;
                }
            }
        }
        if (slot < 0 || slot >= SLOT_COUNT || slot >= inv.getContainerSize()) return;
        ItemStack stack = inv.getItem(slot);
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
            // Real slot mismatch: search other real slots
            int found = -1;
            for (int i = 0; i < SLOT_COUNT; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).equals(itemId)) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                slot = found;
                stack = inv.getItem(slot);
            } else {
                // Check if it moved into the pack
                int pIdx = Pack.find(player, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).equals(itemId));
                if (pIdx >= 0) {
                    slot = Pack.materialize(player, pIdx, itemId);
                    if (slot < 0) {
                        message(player, "full");
                        return;
                    }
                    stack = inv.getItem(slot);
                } else {
                    return;
                }
            }
        }

        switch (action) {
            case InventoryPackets.InvAction.EQUIP -> equip(player, inv, slot, stack);
            case InventoryPackets.InvAction.EQUIP_LEFT -> equipLeft(player, inv, slot, stack);
            case InventoryPackets.InvAction.USE -> use(player, inv, slot, stack);
            case InventoryPackets.InvAction.DROP_ONE -> drop(player, inv, slot, stack, false);
            case InventoryPackets.InvAction.DROP_ALL -> drop(player, inv, slot, stack, true);
            case InventoryPackets.InvAction.FAVORITE -> favorite(player, inv, stack);
            case InventoryPackets.InvAction.READ -> read(player, inv, slot, stack, itemId);
            default -> {
                return;
            }
        }
        com.skycraft.magic.Hands.settle(player); // one hand system: a new item in a hand replaces its spell / bound weapon
        CarryWeight.invalidate(player);
        player.containerMenu.broadcastChanges();
        Pack.sync(player);
    }

    // ------------------------------------------------------------------ slot helpers

    public static boolean isArmorSlot(int slot) {
        return slot >= ARMOR_START && slot < ARMOR_START + 4;
    }

    /** Equip states of a row (the same numbers as the client's InvEntry). */
    public static final int EQ_NONE = 0, EQ_RIGHT = 1, EQ_LEFT = 2, EQ_WORN = 3;

    /**
     * What a stack in {@code slot} is doing: worn armor / worn jewellery (the apparel module), the left hand, the held
     * right hand, or nothing. Same rules the Skyrim inventory screen draws its equipped marks with.
     */
    public static int equipState(net.minecraft.world.entity.player.Player player, Inventory inv, int slot, ItemStack stack) {
        if (isArmorSlot(slot)) return EQ_WORN;
        if (slot == OFFHAND) return EQ_LEFT;
        if (slot == inv.selected) return EQ_RIGHT;
        return isWornJewelry(player, stack) ? EQ_WORN : EQ_NONE;
    }

    /** Jewellery counts as worn when its item id is the one in the apparel module (any stack of it, slot or pack). */
    public static boolean isWornJewelry(net.minecraft.world.entity.player.Player player, ItemStack stack) {
        if (stack.isEmpty() || !com.skycraft.economy.ItemCategory.isJewelry(stack)) return false;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = key != null ? key.toString() : "";
        CompoundTag apparel = com.skycraft.core.SkyData.get(player).module("apparel");
        return id.equals(apparel.getString("necklace")) || id.equals(apparel.getString("ring")) || id.equals(apparel.getString("circlet"));
    }

    /** Armor slot / offhand / the selected hotbar slot. */
    public static boolean isEquipped(Inventory inv, int slot) {
        return isArmorSlot(slot) || slot == OFFHAND || slot == inv.selected;
    }

    /** The armor or offhand slot an item goes to, or null for "hold it in the right hand". */
    @Nullable
    public static EquipmentSlot equipSlotFor(ItemStack stack) {
        if (com.skycraft.economy.ItemCategory.isJewelry(stack)) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key != null) {
                String path = key.getPath();
                if (path.contains("circlet")) return EquipmentSlot.HEAD;
                if (path.contains("necklace") || path.contains("amulet") || path.contains("pendant")) return EquipmentSlot.CHEST;
                if (path.contains("ring")) return EquipmentSlot.OFFHAND;
            }
        }
        EquipmentSlot forge = stack.getEquipmentSlot();
        if (forge != null && forge != EquipmentSlot.MAINHAND) return forge;
        Equipable eq = null;
        if (stack.getItem() instanceof Equipable e) eq = e;
        else if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof Equipable e) eq = e;
        if (eq == null) return null;
        EquipmentSlot s = eq.getEquipmentSlot();
        return s == EquipmentSlot.MAINHAND ? null : s;
    }

    private static int armorIndex(EquipmentSlot slot) {
        return ARMOR_START + slot.getIndex();
    }

    /** A free slot, main inventory (9-35) first, then the hotbar. -1 if the inventory is full. */
    private static int freeSlot(Inventory inv) {
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) if (inv.getItem(i).isEmpty()) return i;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    private static int freeHotbarSlot(Inventory inv) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    private static void select(ServerPlayer player, int hotbarSlot) {
        if (player.getInventory().selected == hotbarSlot) return;
        player.getInventory().selected = hotbarSlot;
        player.connection.send(new ClientboundSetCarriedItemPacket(hotbarSlot));
    }

    private static boolean bound(ServerPlayer player, ItemStack stack) {
        return !player.isCreative() && EnchantmentHelper.hasBindingCurse(stack);
    }

    private static void message(ServerPlayer player, String key) {
        Notifier.message(player, Component.translatable("inventory.skycraft.msg." + key));
    }

    // ------------------------------------------------------------------ equip

    private static boolean isUsableEquipment(ItemStack stack) {
        if (com.skycraft.economy.ItemCategory.isJewelry(stack)) return true;
        EquipmentSlot slot = equipSlotFor(stack);
        if (slot != null && slot.getType() == EquipmentSlot.Type.ARMOR) return true;
        net.minecraft.world.item.Item it = stack.getItem();
        if (it instanceof net.minecraft.world.item.SwordItem || it instanceof net.minecraft.world.item.DiggerItem
                || it instanceof net.minecraft.world.item.ProjectileWeaponItem || it instanceof net.minecraft.world.item.ShieldItem
                || it instanceof net.minecraft.world.item.TridentItem || it instanceof net.minecraft.world.item.FishingRodItem
                || it instanceof net.minecraft.world.item.ShearsItem) {
            return true;
        }
        // a saddle is held in the hand: sneak up to a wild mount and use it (com.skycraft.mounts)
        return it == net.minecraft.world.item.Items.TORCH || it == net.minecraft.world.item.Items.SOUL_TORCH
                || com.skycraft.mounts.MountItems.isSaddle(stack);
    }

    private static void equip(ServerPlayer player, Inventory inv, int slot, ItemStack stack) {
        if (isArmorSlot(slot) || slot == OFFHAND) {
            unequip(player, inv, slot, stack);
            return;
        }
        if (!isUsableEquipment(stack)) return;

        if (com.skycraft.economy.ItemCategory.isJewelry(stack)) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String path = key != null ? key.getPath() : "";
            String kind = path.contains("circlet") ? "circlet" : path.contains("ring") ? "ring" : "necklace";
            com.skycraft.core.PlayerData data = com.skycraft.core.SkyData.get(player);
            CompoundTag apparel = data.module("apparel");
            String curWorn = apparel.getString(kind);
            String thisId = key != null ? key.toString() : "";
            if (thisId.equals(curWorn)) {
                apparel.remove(kind);
            } else {
                apparel.putString(kind, thisId);
            }
            data.markDirty();
            com.skycraft.core.PlayerDataEvents.fullSync(player);
            return;
        }

        EquipmentSlot target = equipSlotFor(stack);
        if (target != null && target.getType() == EquipmentSlot.Type.ARMOR) {
            wear(player, inv, slot, stack, target);
        } else if (target == EquipmentSlot.OFFHAND) {
            equipLeft(player, inv, slot, stack);
        } else if (slot == inv.selected) {
            unequip(player, inv, slot, stack);
        } else {
            holdRight(player, inv, slot, stack);
        }
    }

    private static void wear(ServerPlayer player, Inventory inv, int slot, ItemStack stack, EquipmentSlot target) {
        ItemStack worn = inv.getItem(armorIndex(target));
        if (!worn.isEmpty() && bound(player, worn)) {
            message(player, "bound");
            return;
        }
        if (stack.getCount() > 1) {
            ItemStack one = stack.split(1);
            player.setItemSlot(target, one);
            if (!worn.isEmpty()) Pack.giveOrDrop(player, worn);
        } else {
            inv.setItem(slot, ItemStack.EMPTY);
            player.setItemSlot(target, stack);
            inv.setItem(slot, worn);
        }
    }

    /** Moves an item into the right hand (selected hotbar slot), keeping the hotbar tidy. */
    private static void holdRight(ServerPlayer player, Inventory inv, int slot, ItemStack stack) {
        if (Inventory.isHotbarSlot(slot)) {
            select(player, slot);
        } else {
            int sel = inv.selected;
            ItemStack held = inv.getItem(sel);
            if (held.isEmpty()) {
                inv.setItem(slot, ItemStack.EMPTY);
                inv.setItem(sel, stack);
            } else {
                int free = freeHotbarSlot(inv);
                if (free >= 0) {
                    inv.setItem(slot, ItemStack.EMPTY);
                    inv.setItem(free, stack);
                    select(player, free);
                } else {
                    inv.setItem(slot, held);
                    inv.setItem(sel, stack);
                }
            }
        }
        // Two-handed weapons need both hands: put away whatever is in the left hand if there's room.
        if (WeaponClass.of(stack).twoHanded()) {
            ItemStack off = inv.getItem(OFFHAND);
            if (!off.isEmpty()) {
                int free = freeSlot(inv);
                inv.setItem(OFFHAND, ItemStack.EMPTY);
                if (free >= 0) inv.setItem(free, off);
                else Pack.giveOrDrop(player, off); // all 36 slots taken: the pack holds it
            }
        }
    }

    private static void equipLeft(ServerPlayer player, Inventory inv, int slot, ItemStack stack) {
        if (slot == OFFHAND) {
            unequip(player, inv, slot, stack);
            return;
        }
        if (isArmorSlot(slot)) return;
        EquipmentSlot target = equipSlotFor(stack);
        if (target != null && target.getType() == EquipmentSlot.Type.ARMOR) return; // armor can't be held
        ItemStack off = inv.getItem(OFFHAND);
        inv.setItem(slot, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.OFFHAND, stack);
        inv.setItem(slot, off);
    }

    private static void unequip(ServerPlayer player, Inventory inv, int slot, ItemStack stack) {
        if (isArmorSlot(slot) && bound(player, stack)) {
            message(player, "bound");
            return;
        }
        if (slot == inv.selected && Inventory.isHotbarSlot(slot)) {
            // put the weapon away into the backpack; with a full backpack, just switch to an empty hand
            for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
                if (inv.getItem(i).isEmpty()) {
                    inv.setItem(slot, ItemStack.EMPTY);
                    inv.setItem(i, stack);
                    return;
                }
            }
            int empty = freeHotbarSlot(inv);
            if (empty >= 0) {
                select(player, empty);
            } else {
                // every slot is taken: the weapon goes into the pack (weight is the only carrying limit)
                inv.setItem(slot, ItemStack.EMPTY);
                Pack.giveOrDrop(player, stack);
            }
            return;
        }
        int free = freeSlot(inv);
        if (isArmorSlot(slot)) {
            EquipmentSlot eqSlot = EquipmentSlot.byTypeAndIndex(EquipmentSlot.Type.ARMOR, slot - ARMOR_START);
            player.setItemSlot(eqSlot, ItemStack.EMPTY);
        } else {
            inv.setItem(slot, ItemStack.EMPTY);
        }
        if (free >= 0) inv.setItem(free, stack);
        else Pack.giveOrDrop(player, stack);
    }

    // ------------------------------------------------------------------ use / read

    private static void use(ServerPlayer player, Inventory inv, int slot, ItemStack stack) {
        if (!InvCategory.isConsumable(stack)) return;
        com.skycraft.crafting.arcane.alchemy.Ingredients.Ingredient ing = com.skycraft.crafting.arcane.alchemy.Ingredients.get(stack);
        if (ing != null && !stack.isEdible()) {
            com.skycraft.crafting.arcane.alchemy.Alchemy.discoverByTasting(player, ing);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 0.7f, 0.9f + player.getRandom().nextFloat() * 0.2f);
            if (stack.isEmpty()) {
                inv.setItem(slot, ItemStack.EMPTY);
            }
            return;
        }
        if (stack.isEdible()) {
            FoodProperties food = stack.getFoodProperties(player);
            if (food != null && !player.canEat(food.canAlwaysEat())) {
                message(player, "not_hungry");
                return;
            }
        }
        boolean drink = stack.getUseAnimation() == UseAnim.DRINK;
        ItemStack before = stack.copy();
        // Same sequence as LivingEntity#completeUsingItem, so food/potion effects, advancements and
        // LivingEntityUseItemEvent.Finish listeners (Skyrim food healing) all run.
        ItemStack result = stack.finishUsingItem(player.level(), player);
        result = ForgeEventFactory.onItemUseFinish(player, before, 0, result);
        if (result != stack) {
            if (stack.isEmpty()) {
                inv.setItem(slot, result);
            } else if (!result.isEmpty()) {
                Pack.giveOrDrop(player, result); // an empty bottle...
            }
        } else if (stack.isEmpty()) {
            inv.setItem(slot, ItemStack.EMPTY);
        }
        if (drink) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GENERIC_DRINK,
                    SoundSource.PLAYERS, 0.5f, player.level().random.nextFloat() * 0.1f + 0.9f);
        }
    }

    /** Puts a book in the right hand and asks the client to use it the vanilla way (opens it / learns the spell). */
    private static void read(ServerPlayer player, Inventory inv, int slot, ItemStack stack, ResourceLocation itemId) {
        if (!InvCategory.isReadable(stack)) return;
        if (slot != inv.selected) {
            if (isArmorSlot(slot)) return;
            if (slot == OFFHAND) {
                ItemStack held = inv.getItem(inv.selected);
                inv.setItem(OFFHAND, held);
                inv.setItem(inv.selected, stack);
            } else {
                holdRight(player, inv, slot, stack);
            }
        }
        // push the slot changes now so they reach the client before the UseHeld request
        player.inventoryMenu.broadcastChanges();
        com.skycraft.network.SkyNetwork.sendToPlayer(player, new InventoryPackets.UseHeld(itemId));
    }

    // ------------------------------------------------------------------ drop / favorite

    private static void drop(ServerPlayer player, Inventory inv, int slot, ItemStack stack, boolean all) {
        if (isArmorSlot(slot) && bound(player, stack)) {
            message(player, "bound");
            return;
        }
        if (com.skycraft.quest.QuestItems.isQuestItem(stack)) {
            message(player, "quest_item");
            return;
        }
        if (!all) {
            toss(player, inv, inv.removeItem(slot, 1));
            return;
        }
        if (isEquipped(inv, slot)) {
            toss(player, inv, inv.removeItem(slot, stack.getCount()));
            return;
        }
        // "drop all" drops the whole row of the list: every unequipped stack of this exact item
        ItemStack template = stack.copy();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (i == inv.selected) continue;
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && !com.skycraft.quest.QuestItems.isQuestItem(s) && ItemStack.isSameItemSameTags(s, template)) {
                toss(player, inv, inv.removeItem(i, s.getCount()));
            }
        }
        // ... and the part of the row that sits in the overflow pack
        tossAll(player, inv, Pack.takeAll(player, template));
    }

    private static void toss(ServerPlayer player, Inventory inv, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemEntity entity = player.drop(stack, true);
        if (entity == null) {
            // a mod cancelled the toss: don't let the item vanish
            Pack.giveOrDrop(player, stack);
        }
    }

    /** Tosses a stack of any size as max-size stacks (at most 64 entities per action; the rest stays in the pack). */
    private static void tossAll(ServerPlayer player, Inventory inv, ItemStack stack) {
        int entities = 0;
        while (!stack.isEmpty() && entities++ < 64) toss(player, inv, stack.split(stack.getMaxStackSize()));
        if (!stack.isEmpty()) Pack.add(player, stack);
    }

    /** Toggles the favorite mark on every stack of this exact item (the whole list row), pack entry included. */
    private static void favorite(ServerPlayer player, Inventory inv, ItemStack stack) {
        boolean fav = !InvCategory.isFavorite(stack);
        ItemStack template = stack.copy();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameTags(s, template)) continue;
            setFavorite(s, fav);
            inv.setChanged();
        }
        Pack.setFavorite(player, template, fav);
    }

    static void setFavorite(ItemStack stack, boolean fav) {
        if (fav) {
            stack.getOrCreateTag().putBoolean(InvCategory.FAVORITE_NBT, true);
        } else {
            CompoundTag tag = stack.getTag();
            if (tag != null) stack.removeTagKey(InvCategory.FAVORITE_NBT);
        }
    }
}
