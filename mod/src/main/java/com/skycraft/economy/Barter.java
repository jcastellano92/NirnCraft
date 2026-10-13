package com.skycraft.economy;

import com.skycraft.core.Currency;
import com.skycraft.core.Skill;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.network.SkyNetwork;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Server side of bartering. Every price and check is done here; the client only displays the state it is sent. */
public final class Barter {
    public static final byte STATUS_OK = 0;
    public static final byte STATUS_NOT_DEALT = 1;
    public static final byte STATUS_STOLEN = 2;
    /** Items flagged with this boolean NBT (quest items) can never be sold. */
    public static final String NO_SELL_NBT = "skycraft_quest_item";

    private static final double MAX_DIST_SQ = 10 * 10;
    private static final int MAIN_INVENTORY = 36;

    private Barter() {}

    /** Opens the barter menu with a merchant. */
    public static void open(ServerPlayer player, LivingEntity npc) {
        if (!Merchants.isMerchant(npc)) return;
        if (!Merchants.alwaysOpen(npc) && !Shop.isOpen(player.level())) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.economy.closed"));
            return;
        }
        Shop shop = Shop.of(npc, player);
        sendState(player, npc, shop, Merchants.greeting(npc), true);
    }

    static boolean canReach(ServerPlayer player, LivingEntity npc) {
        return npc.isAlive() && npc.level() == player.level() && player.distanceToSqr(npc) <= MAX_DIST_SQ && !player.isSpectator();
    }

    /** C2S: buy (from stock index) or sell (from inventory slot) one item or the whole stack. */
    static void handleAction(ServerPlayer player, int entityId, boolean buy, int index, boolean all, ResourceLocation itemId) {
        if (!(player.level().getEntity(entityId) instanceof LivingEntity npc) || !Merchants.isMerchant(npc) || !canReach(player, npc)) return;
        Shop shop = Shop.of(npc, player);
        Component msg = buy ? buy(player, shop, index, all, itemId) : sell(player, shop, index, all, itemId);
        sendState(player, npc, shop, msg, false);

        // Sync stock & gold to all players viewing this merchant
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other != player && canReach(other, npc)) {
                sendState(other, npc, shop, Component.empty(), false);
            }
        }
    }

    private static boolean matches(ItemStack stack, ResourceLocation itemId) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId);
    }

    private static Component buy(ServerPlayer player, Shop shop, int index, boolean all, ResourceLocation itemId) {
        List<ItemStack> stock = shop.stock();
        if (index < 0 || index >= stock.size()) return Component.empty();
        ItemStack stack = stock.get(index);
        if (!matches(stack, itemId)) return Component.empty();
        Component name = stack.getHoverName();
        int unit = Pricing.buyPrice(player, stack, shop.npc());
        int want = all ? stack.getCount() : 1;
        int affordable = (int) Math.min(want, Currency.balance(player) / unit);
        if (affordable <= 0) return Component.translatable("message.skycraft.not_enough_gold");

        ItemStack give = stack.copy();
        give.setCount(affordable);
        ActionHandler.addToBags(player, give);
        int bought = affordable - give.getCount();
        if (bought <= 0) return Component.translatable("message.skycraft.economy.inventory_full");
        int total = unit * bought;
        Currency.take(player, total);
        stack.shrink(bought);
        if (stack.isEmpty()) stock.remove(index);
        shop.receive(total);
        shop.save();
        Progression.addSkillXp(player, Skill.SPEECH, total);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
        if (bought < want) {
            return bought < affordable
                    ? Component.translatable("message.skycraft.economy.inventory_full")
                    : Component.translatable("message.skycraft.not_enough_gold");
        }
        return Component.translatable("message.skycraft.economy.bought", bought, name, total);
    }

    private static Component sell(ServerPlayer player, Shop shop, int slot, boolean all, ResourceLocation itemId) {
        Inventory inv = player.getInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) return Component.empty();
        ItemStack stack = inv.getItem(slot);
        if (!matches(stack, itemId)) return Component.empty();
        LivingEntity npc = shop.npc();
        if (!sellable(stack) || Pricing.sellPrice(player, stack, npc) <= 0) return Component.translatable("message.skycraft.economy.cant_sell");
        if (!Merchants.buysFrom(player, npc, stack)) return Component.translatable("message.skycraft.economy.not_dealt");
        boolean stolen = Merchants.isStolen(stack);
        if (stolen && !Merchants.isFence(player, npc, shop)) return Component.translatable("message.skycraft.economy.stolen");
        if (Merchants.isContraband(npc, stack) && !Merchants.takesContraband(player, npc, shop)) {
            return Component.translatable("message.skycraft.economy.contraband");
        }

        int unit = Pricing.sellPrice(player, stack, npc);
        int want = all ? stack.getCount() : 1;
        int n = Math.min(want, shop.available(player) / unit);
        if (n <= 0) return Component.translatable("message.skycraft.economy.no_merchant_gold");
        Component name = stack.getHoverName();
        ItemStack sold = inv.removeItem(slot, n);
        if (sold.isEmpty()) return Component.empty();
        n = sold.getCount();
        int total = unit * n;
        if (stolen) Merchants.clearStolen(sold);
        shop.addToStock(sold);
        shop.pay(player, total);
        shop.save();
        Currency.give(player, total);
        Progression.addSkillXp(player, Skill.SPEECH, total);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 0.8f);
        if (n < want) return Component.translatable("message.skycraft.economy.no_merchant_gold");
        return Component.translatable("message.skycraft.economy.sold", n, name, total);
    }

    /** Coins, quest items and filled containers (shulker boxes, bundles) are never sold. */
    public static boolean sellable(ItemStack stack) {
        if (stack.isEmpty() || Currency.valueOf(stack) > 0) return false;
        CompoundTag tag = stack.getTag();
        if (tag == null) return true;
        if (tag.getBoolean(NO_SELL_NBT) || com.skycraft.quest.QuestItems.isQuestItem(stack)) return false;
        if (tag.contains("Items", Tag.TAG_LIST) && !tag.getList("Items", Tag.TAG_COMPOUND).isEmpty()) return false;
        CompoundTag be = tag.getCompound("BlockEntityTag");
        return !(be.contains("Items", Tag.TAG_LIST) && !be.getList("Items", Tag.TAG_COMPOUND).isEmpty());
    }

    // ------------------------------------------------------------------ state

    static void sendState(ServerPlayer player, LivingEntity npc, Shop shop, Component message, boolean open) {
        List<EconomyPackets.Entry> merchant = new ArrayList<>();
        List<ItemStack> stock = shop.stock();
        for (int i = 0; i < stock.size(); i++) {
            ItemStack s = stock.get(i);
            if (s.isEmpty()) continue;
            merchant.add(new EconomyPackets.Entry(i, s.copy(), Pricing.buyPrice(player, s, npc), (byte) ItemCategory.of(s).ordinal(), STATUS_OK));
        }
        List<EconomyPackets.Entry> mine = new ArrayList<>();
        boolean fence = Merchants.isFence(player, npc, shop);
        boolean smuggler = Merchants.takesContraband(player, npc, shop);
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack s = inv.getItem(slot);
            if (!sellable(s)) continue;
            int price = Pricing.sellPrice(player, s, npc);
            if (price <= 0) continue;
            byte status = STATUS_OK;
            if (!Merchants.buysFrom(player, npc, s)) {
                if (!com.skycraft.perk.Perks.has(player, "speech.merchant")) continue;
            }
            if (Merchants.isStolen(s) && !fence) status = STATUS_STOLEN;
            if (!smuggler && Merchants.isContraband(npc, s)) status = STATUS_STOLEN;
            mine.add(new EconomyPackets.Entry(slot, s.copy(), price, (byte) ItemCategory.of(s).ordinal(), status));
        }
        SkyNetwork.sendToPlayer(player, new EconomyPackets.BarterState(npc.getId(), npc.getDisplayName(), open,
                Currency.balance(player), shop.available(player), message, merchant, mine));
    }

    // ------------------------------------------------------------------ bulk selling (dialogue topics)

    public static boolean hasAny(ServerPlayer player, TagKey<Item> filter) {
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < MAIN_INVENTORY; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.is(filter) && sellable(s) && !Merchants.isStolen(s)) return true;
        }
        return false;
    }

    /** Sells every (non-stolen) item matching {@code filter} from the main inventory in one go, then reopens the dialogue. */
    public static void bulkSell(ServerPlayer player, LivingEntity npc, TagKey<Item> filter) {
        if (!Merchants.isMerchant(npc) || !canReach(player, npc)) return;
        Shop shop = Shop.of(npc, player);
        Inventory inv = player.getInventory();
        int total = 0;
        int count = 0;
        boolean ranOut = false;
        for (int slot = 0; slot < MAIN_INVENTORY; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty() || !s.is(filter) || !sellable(s) || Merchants.isStolen(s)) continue;
            int unit = Pricing.sellPrice(player, s, npc);
            if (unit <= 0) continue;
            int n = Math.min(s.getCount(), shop.available(player) / unit);
            if (n < s.getCount()) ranOut = true;
            if (n <= 0) continue;
            ItemStack sold = inv.removeItem(slot, n);
            int got = sold.getCount() * unit;
            shop.addToStock(sold);
            shop.pay(player, got);
            total += got;
            count += sold.getCount();
        }
        shop.save();
        Component line;
        if (count == 0) {
            line = Component.translatable(ranOut ? "dialogue.skycraft.economy.bulk.no_gold" : "dialogue.skycraft.economy.bulk.nothing");
        } else {
            Currency.give(player, total);
            Progression.addSkillXp(player, Skill.SPEECH, total);
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 0.9f);
            line = ranOut
                    ? Component.translatable("dialogue.skycraft.economy.bulk.partial", count, total)
                    : Component.translatable("dialogue.skycraft.economy.bulk.done", count, total);
        }
        Dialogue.open(player, npc, line);
    }
}
