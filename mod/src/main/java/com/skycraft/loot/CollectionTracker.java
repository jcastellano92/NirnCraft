package com.skycraft.loot;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.economy.SkillBookItem;
import com.skycraft.network.NotifyKind;
import com.skycraft.skills.Progression;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Notices collectibles as they reach a character's inventory (a light scan once a second, plus every pick-up) and pays
 * out collection rewards: perk points, gold and reward items (Konahrik for the eight masks, the Crown of Barenziah for
 * the 24 stones). Skill book volumes count when read; all five volumes of a skill grant one more level of it.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class CollectionTracker {
    private CollectionTracker() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if ((player.tickCount + player.getId()) % 20 != 0) return;
        scan(player);
        if ((player.tickCount + player.getId()) % 100 == 0) skillBooks(player);
    }

    @SubscribeEvent
    public static void onPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) note(player, event.getOriginalStack());
    }

    /** Records every collectible carried (slots, off hand, overflow pack) and worn. */
    public static void scan(ServerPlayer player) {
        com.skycraft.inventory.Inv.forEach(player, s -> note(player, s));
        for (var slot263 : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
            ItemStack s = player.getItemBySlot(slot263);
            if (!s.isEmpty()) note(player, s);
        }
    }

    /** Records one collectible stack (if it is one the character hasn't found yet). */
    public static void note(ServerPlayer player, ItemStack stack) {
        Collectibles.Entry e = Collectibles.identify(stack);
        if (e == null) return;
        PlayerData data = SkyData.get(player);
        CompoundTag module = data.module(Collectibles.MODULE);
        CompoundTag found = module.getCompoundOrEmpty("found");
        if (found.contains(e.key())) return;
        found.putLong(e.key(), player.level().getGameTime());
        module.put("found", found);
        data.markDirty();
        Collectibles.Collection c = Collectibles.get(e.collection());
        if (c == null) return;
        int have = Collectibles.progress(data, c);
        Notifier.send(player, NotifyKind.MESSAGE, Component.translatable("message.skycraft.loot.collected", e.name(), c.name(),
                have, c.entries.size()), Component.empty());
        if (have >= c.entries.size()) complete(player, data, c);
    }

    private static void skillBooks(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        CompoundTag module = data.module(Collectibles.MODULE);
        CompoundTag sets = module.getCompoundOrEmpty("skill_sets");
        boolean changed = false;
        for (Skill skill : Skill.VALUES) {
            if (sets.contains(skill.id())) continue;
            boolean all = true;
            for (int v = 1; v <= SkillBookItem.VOLUMES && all; v++) all = SkillBookItem.hasRead(data, skill, v);
            if (!all) continue;
            sets.putLong(skill.id(), player.level().getGameTime());
            changed = true;
            if (data.getSkill(skill) < Skill.MAX_LEVEL) Progression.increaseSkill(player, skill, 1);
            Notifier.send(player, NotifyKind.MESSAGE, Component.translatable("message.skycraft.loot.skill_set", skill.displayName()), Component.empty());
        }
        if (changed) {
            module.put("skill_sets", sets);
            data.markDirty();
        }
        Collectibles.Collection books = Collectibles.get(Collectibles.SKILL_BOOKS);
        if (books != null && !Collectibles.rewarded(data, books.id) && Collectibles.progress(data, books) >= books.entries.size()) {
            complete(player, data, books);
        }
    }

    /** Pays a completed collection's reward, once. */
    public static void complete(ServerPlayer player, PlayerData data, Collectibles.Collection c) {
        CompoundTag module = data.module(Collectibles.MODULE);
        CompoundTag rewarded = module.getCompoundOrEmpty("rewarded");
        if (rewarded.contains(c.id)) return;
        rewarded.putLong(c.id, player.level().getGameTime());
        module.put("rewarded", rewarded);
        Collectibles.Reward r = c.reward;
        if (r.perkPoints() > 0) data.setPerkPoints(data.getPerkPoints() + r.perkPoints());
        if (r.gold() > 0) Currency.give(player, r.gold());
        if (r.item() != null) {
            ItemStack item = r.item().get();
            if (!item.isEmpty()) com.skycraft.inventory.Pack.giveOrDrop(player, item);
        }
        data.addStat("collections_completed", 1);
        data.markDirty();
        Notifier.send(player, NotifyKind.BIG_TITLE, Component.translatable("message.skycraft.loot.collection_complete").withStyle(ChatFormatting.GOLD),
                Component.translatable("collection.skycraft." + c.id + ".reward"));
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8f, 1f);
    }
}
