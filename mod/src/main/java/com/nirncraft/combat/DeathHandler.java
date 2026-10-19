package com.nirncraft.combat;

import com.nirncraft.SkyConfig;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.quest.QuestItems;
import com.nirncraft.vitals.ActionHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles Skyrim player death rules, kept equipment and quest items, respawn points, and legendary item tooltips.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class DeathHandler {
    private static final TagKey<Item> JEWELRY = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "jewelry"));
    private static final TagKey<Item> SOUL_GEMS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "soul_gems"));
    private static final TagKey<Item> GEMS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "merchant/gems"));

    private DeathHandler() {}

    /**
     * Kept items captured at HIGHEST priority (before other mods move inventories), committed at LOWEST only if the
     * death wasn't cancelled by another mod (totems, revive mods...).
     */
    private static final Map<UUID, ListTag> PENDING = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // With keepInventory nothing drops and vanilla restores the whole inventory: keeping items would dupe them.
        if (player.level().getGameRules().get(GameRules.KEEP_INVENTORY) || player.isSpectator()) {
            PENDING.put(player.getUUID(), new ListTag());
            return;
        }
        ListTag list = new ListTag();

        SkyConfig.DeathDrops mode = SkyConfig.DEATH_DROPS.get();

        // 1. Equipped gear: head, chest, legs, feet, mainhand, offhand (armour, weapons, ammo in hand...)
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty() && !com.nirncraft.magic.bound.BoundWeapons.isBound(stack)
                    && (mode != SkyConfig.DeathDrops.EVERYTHING || QuestItems.isQuestItem(stack))) {
                CompoundTag entry = new CompoundTag();
                entry.putString("slot", slot.getName());
                entry.put("item", com.nirncraft.inventory.ItemData.save(stack));
                list.add(entry);
            }
        }

        // 2. Quest items in backpack; in VALUABLES mode everything that is not a valuable (weapons, ammo, tools,
        // food, potions...) too: only gold and valuables are lost
        Inventory inv = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (i == inv.getSelectedSlot()) continue; // main hand already saved above
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || com.nirncraft.magic.bound.BoundWeapons.isBound(stack)) continue; // conjured: never kept
            boolean keep = QuestItems.isQuestItem(stack)
                    || (mode == SkyConfig.DeathDrops.VALUABLES && !isValuable(stack));
            if (keep) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("inv_slot", i);
                entry.put("item", com.nirncraft.inventory.ItemData.save(stack));
                list.add(entry);
            }
        }
        PENDING.put(player.getUUID(), list);
    }

    /** Gold, gems, jewellery, soul gems and costly misc items: what a death drops in VALUABLES mode. */
    public static boolean isValuable(ItemStack stack) {
        if (com.nirncraft.core.Currency.valueOf(stack) > 0) return true;
        if (stack.is(JEWELRY) || stack.is(SOUL_GEMS) || stack.is(GEMS)) return true;
        if (stack.is(Items.GOLD_INGOT) || stack.is(Items.GOLD_NUGGET) || stack.is(Items.RAW_GOLD) || stack.is(Items.GOLD_BLOCK)
                || stack.is(Items.DIAMOND) || stack.is(Items.DIAMOND_BLOCK) || stack.is(Items.EMERALD) || stack.is(Items.EMERALD_BLOCK)
                || stack.is(Items.AMETHYST_SHARD) || stack.is(Items.NETHERITE_INGOT) || stack.is(Items.NETHERITE_SCRAP)) return true;
        if (com.nirncraft.economy.ItemCategory.isJewelry(stack)) return true;
        // tools, weapons, shields, ammo and anything with durability are kit, never a valuable
        if (stack.isDamageableItem() || stack.getItem() instanceof net.minecraft.world.item.ArrowItem
                || stack.is(net.minecraft.tags.ItemTags.ARROWS)) return false;
        return com.nirncraft.economy.ItemCategory.of(stack) == com.nirncraft.economy.ItemCategory.MISC
                && com.nirncraft.economy.ItemValues.get(stack) >= SkyConfig.DEATH_VALUABLE_MIN_VALUE.get();
    }

    /** Commits the kept items and the corpse marker once every other handler had its say. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onDeathCommit(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ListTag list = PENDING.remove(player.getUUID());
        if (list == null || event.isCanceled()) return;
        PlayerData data = SkyData.get(player);
        CompoundTag keptTag = data.module("death_kept");

        // Place "Your Corpse" death marker on compass and map
        CompoundTag corpseLoc = new CompoundTag();
        String corpseId = "corpse|" + player.getStringUUID();
        corpseLoc.putString("id", corpseId);
        corpseLoc.putString("name", "Your Corpse");
        corpseLoc.putString("type", "tomb");
        corpseLoc.putInt("x", player.getBlockX());
        corpseLoc.putInt("y", player.getBlockY());
        corpseLoc.putInt("z", player.getBlockZ());
        corpseLoc.putString("dim", player.level().dimension().identifier().toString());
        corpseLoc.putLong("t", player.level().getGameTime());
        corpseLoc.putInt("ax", player.getBlockX());
        corpseLoc.putInt("ay", player.getBlockY());
        corpseLoc.putInt("az", player.getBlockZ());
        corpseLoc.putIntArray("box", new int[]{player.getBlockX() - 2, player.getBlockY() - 2, player.getBlockZ() - 2,
                player.getBlockX() + 2, player.getBlockY() + 2, player.getBlockZ() + 2});
        corpseLoc.putString("structure", "nirncraft:player_corpse");

        ListTag discovered = com.nirncraft.world.WorldData.discovered(data);
        for (int i = discovered.size() - 1; i >= 0; i--) {
            if (discovered.getCompoundOrEmpty(i).getStringOr("name","").equals("Your Corpse")) {
                discovered.remove(i);
            }
        }
        discovered.add(corpseLoc);

        keptTag.remove("confirmed"); // set by onPlayerDrops once the kept stacks were really taken out of the drops
        if (list.isEmpty()) keptTag.remove("items");
        else keptTag.put("items", list);
        data.markDirty();
    }

    /** Moves the "Your Corpse" marker to where the body really lies (survival/corpse puts it on safe ground). */
    public static void moveCorpseMarker(ServerPlayer player, BlockPos pos) {
        PlayerData data = SkyData.get(player);
        String corpseId = "corpse|" + player.getStringUUID();
        ListTag discovered = com.nirncraft.world.WorldData.discovered(data);
        for (int i = 0; i < discovered.size(); i++) {
            CompoundTag loc = discovered.getCompoundOrEmpty(i);
            if (!corpseId.equals(loc.getStringOr("id",""))) continue;
            loc.putInt("x", pos.getX());
            loc.putInt("y", pos.getY());
            loc.putInt("z", pos.getZ());
            loc.putInt("ax", pos.getX());
            loc.putInt("ay", pos.getY());
            loc.putInt("az", pos.getZ());
            loc.putIntArray("box", new int[]{pos.getX() - 2, pos.getY() - 2, pos.getZ() - 2, pos.getX() + 2, pos.getY() + 2, pos.getZ() + 2});
            data.markDirty();
        }
    }

    /**
     * Removes exactly the kept stacks from the death drops: whole identical stacks first, then the remaining
     * counts from stacks of the same item and tags (never a different stack that merely shares the item type).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        dropGold(player, event);
        dropPack(player, event);
        CompoundTag keptTag = SkyData.get(player).module("death_kept");
        if (!com.nirncraft.platform.NbtData.hasType(keptTag, "items", Tag.TAG_LIST)) return;

        ListTag list = keptTag.getListOrEmpty("items");
        List<ItemStack> toKeep = new ArrayList<>();
        int[] removed = new int[list.size()];
        for (int i = 0; i < list.size(); i++) toKeep.add(com.nirncraft.inventory.ItemData.parse(list.getCompoundOrEmpty(i).getCompoundOrEmpty("item")));

        // Pass 1: identical stacks (item, tags and count).
        for (int k = 0; k < toKeep.size(); k++) {
            ItemStack keep = toKeep.get(k);
            if (keep.isEmpty()) continue;
            for (Iterator<ItemEntity> it = event.getDrops().iterator(); it.hasNext(); ) {
                if (ItemStack.matches(it.next().getItem(), keep)) {
                    it.remove();
                    removed[k] = keep.getCount();
                    break;
                }
            }
        }
        // Pass 2: whatever is left, by count, from stacks with the same item and tags.
        for (int k = 0; k < toKeep.size(); k++) {
            ItemStack keep = toKeep.get(k);
            int remaining = keep.getCount() - removed[k];
            for (Iterator<ItemEntity> it = event.getDrops().iterator(); it.hasNext() && remaining > 0 && !keep.isEmpty(); ) {
                ItemEntity drop = it.next();
                ItemStack dropStack = drop.getItem();
                if (!ItemStack.isSameItemSameComponents(dropStack, keep)) continue;
                int take = Math.min(remaining, dropStack.getCount());
                remaining -= take;
                removed[k] += take;
                if (take >= dropStack.getCount()) {
                    it.remove();
                } else {
                    ItemStack rest = dropStack.copy();
                    rest.shrink(take);
                    drop.setItem(rest);
                }
            }
        }

        // Only what was really taken out of the drops comes back on respawn. Anything another mod already moved
        // (a grave, a corpse) stays there, so nothing is duplicated.
        ListTag confirmed = new ListTag();
        for (int k = 0; k < toKeep.size(); k++) {
            if (removed[k] <= 0) continue;
            CompoundTag entry = list.getCompoundOrEmpty(k).copy();
            ItemStack stack = toKeep.get(k).copy();
            stack.setCount(removed[k]);
            entry.put("item", com.nirncraft.inventory.ItemData.save(stack));
            confirmed.add(entry);
        }
        keptTag.put("items", confirmed);
        keptTag.putBoolean("confirmed", true);
    }

    /**
     * The overflow pack (inventory.Pack) falls to the corpse like the backpack does: the same keep rule as the slots
     * (quest items always, and in VALUABLES mode everything that is not a valuable); the rest leaves the pack as drops.
     * What stays is part of the player data and comes back with the clone.
     */
    private static void dropPack(ServerPlayer player, LivingDropsEvent event) {
        if (player.isSpectator() || player.level().getGameRules().get(GameRules.KEEP_INVENTORY)) return;
        SkyConfig.DeathDrops mode = SkyConfig.DEATH_DROPS.get();
        // whole entries (int counts) go to the player corpse next to the slots' drops, never as thousands of max-size
        // item entities (survival/corpse/PlayerCorpses; with corpses off they fall as entities like before)
        List<ItemStack> cargo = new ArrayList<>();
        for (ItemStack entry : com.nirncraft.inventory.Pack.entries(player)) {
            if (entry.isEmpty()) continue;
            if (QuestItems.isQuestItem(entry) || (mode == SkyConfig.DeathDrops.VALUABLES && !isValuable(entry))) continue;
            ItemStack all = com.nirncraft.inventory.Pack.takeAll(player, entry);
            if (!all.isEmpty()) cargo.add(all);
        }
        com.nirncraft.survival.corpse.PlayerCorpses.stashPackDrops(player, cargo, event);
    }

    /** A share of the gold wallet falls to the corpse as coins (configurable, 0 disables). */
    private static void dropGold(ServerPlayer player, LivingDropsEvent event) {
        if (player.isSpectator() || player.level().getGameRules().get(GameRules.KEEP_INVENTORY)) return;
        double pct = SkyConfig.DEATH_GOLD_DROP_PERCENT.get();
        PlayerData data = SkyData.get(player);
        long lose = (long) (data.getGold() * pct / 100.0);
        if (lose <= 0) return;
        data.setGold(data.getGold() - lose);
        com.nirncraft.core.Currency.markNotFound(player, lose);
        ItemEntity coins = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), com.nirncraft.core.Currency.coins(lose));
        event.getDrops().add(coins);
        Notifier.message(player, Component.translatable("message.nirncraft.death_gold_lost", lose));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer newPlayer)) return;
        PlayerData data = SkyData.get(newPlayer);

        // Reset negative status effects and cure diseases on respawn
        newPlayer.removeAllEffects();
        com.nirncraft.survival.Diseases.cureAll(newPlayer);
        data.setLastCombatTick(-100000L);

        CompoundTag keptTag = data.module("death_kept");
        if (!com.nirncraft.platform.NbtData.hasType(keptTag, "items", Tag.TAG_LIST)) return;
        // keepInventory: vanilla already restored everything (onDeath records nothing then; this guards old saves).
        // Not confirmed: the drops never reached us (another mod took them), so the items are wherever they went.
        if (newPlayer.level().getGameRules().get(GameRules.KEEP_INVENTORY) || !keptTag.getBooleanOr("confirmed",false)) {
            keptTag.remove("items");
            keptTag.remove("confirmed");
            data.markDirty();
            return;
        }

        ListTag list = keptTag.getListOrEmpty("items");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            ItemStack stack = com.nirncraft.inventory.ItemData.parse(entry.getCompoundOrEmpty("item"));
            if (stack.isEmpty() || com.nirncraft.magic.bound.BoundWeapons.isBound(stack)) continue;
            if (entry.contains("slot")) {
                EquipmentSlot slot = EquipmentSlot.byName(entry.getStringOr("slot",""));
                newPlayer.setItemSlot(slot, stack);
            } else if (entry.contains("inv_slot")) {
                int slot = entry.getIntOr("inv_slot",0);
                if (slot >= 0 && slot < newPlayer.getInventory().getContainerSize()
                        && newPlayer.getInventory().getItem(slot).isEmpty()) {
                    newPlayer.getInventory().setItem(slot, stack);
                } else {
                    ActionHandler.addToBags(newPlayer, stack);
                }
            } else {
                ActionHandler.addToBags(newPlayer, stack);
            }
        }

        keptTag.remove("items");
        keptTag.remove("confirmed");
        data.markDirty();
    }

    @SubscribeEvent
    public static void onWakeUp(PlayerWakeUpEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlockPos bed = player.getSleepingPos().orElse(player.blockPosition());
            // a prisoner sleeping off the sentence in the cell bed does not make the cell their home
            if (com.nirncraft.crime.Jail.isJailArea(player.level(), bed)) return;
            // bedrolls and tents are for one night: they never become a respawn point
            if (com.nirncraft.survival.camping.CampingEvents.isCampBed(player.level(), bed)) return;
            // the intro cottage's bed is only where you wake; somebody else's bed is no home of yours
            if (RespawnPoints.isProvisional(player, bed)) return;
            if (com.nirncraft.crime.Ownership.isOwnedByOther(player, player.level(), bed)) return;
            RespawnPoints.setOwned(player, player.level().dimension(), bed);
        }
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        LegendaryItem.Prefix prefix = LegendaryItem.getPrefix(stack);
        if (prefix != null) {
            event.getToolTip().add(Component.literal("★ " + prefix.displayName + ": " + prefix.description)
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
        }
    }
}

