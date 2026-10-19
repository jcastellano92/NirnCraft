package com.skycraft.economy;

import com.skycraft.core.Currency;
import com.skycraft.core.Skill;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.inventory.CarryWeight;
import com.skycraft.inventory.InventoryActions;
import com.skycraft.inventory.Pack;
import com.skycraft.network.SkyNetwork;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;

/** Server side of bartering. Every price and check is done here; the client only displays the state it is sent. */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.skycraft.Skycraft.MODID)
public final class Barter {
    public static final byte STATUS_OK = 0;
    public static final byte STATUS_NOT_DEALT = 1;
    public static final byte STATUS_STOLEN = 2;
    /** A sell row the shop will not put on its shelves: kept for you alone (buy-back), explicit second click. */
    public static final byte STATUS_KEEPSAKE = 3;
    /** {@link EconomyPackets.BarterAction} operations. */
    public static final int OP_BUY = 0, OP_SELL = 1, OP_BUYBACK = 2;
    /** Items flagged with this boolean NBT (quest items) can never be sold. */
    public static final String NO_SELL_NBT = "skycraft_quest_item";

    private static final double MAX_DIST_SQ = 10 * 10;
    private static final int MAIN_INVENTORY = 36;
    private record Viewer(ServerPlayer player, int entityId, java.util.UUID npc, String shop) {}
    private static final java.util.Map<java.util.UUID, Viewer> VIEWERS = new java.util.HashMap<>();

    private Barter() {}

    /** Opens the barter menu with a merchant. */
    public static void open(ServerPlayer player, LivingEntity npc) {
        if (!Merchants.isMerchant(npc) || !canReach(player, npc)) return;
        com.skycraft.society.RaceAttitudes.Evaluation eval = com.skycraft.society.RaceAttitudes.evaluate(npc, player);
        if (com.skycraft.society.RaceAttitudes.KEY_BLOOD_KIN_REQUIRED.equals(eval.attitudeKey())) {
            Dialogue.open(player, npc, Component.translatable("society.skycraft.attitude.blood_kin_required.0"));
            return;
        }
        if (!Merchants.alwaysOpen(npc) && !Shop.isOpen(player.level())) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.economy.closed"));
            return;
        }
        Shop shop = Shop.of(npc, player);
        VIEWERS.put(player.getUUID(), new Viewer(player, npc.getId(), npc.getUUID(), shop.key()));
        sendState(player, npc, shop, Merchants.greeting(npc), true);
        // the merchant stays put and faces the player while the shop is open; closing it ends the conversation
        Dialogue.hold(player, npc);
    }

    /** Returns the regional/faction attitude price multiplier for {@code player} trading with {@code npc}. */
    public static float attitudePriceMultiplier(net.minecraft.world.entity.player.Player player, LivingEntity npc) {
        return PriceEngine.attitudeMultiplier(player, npc);
    }

    static boolean canReach(ServerPlayer player, LivingEntity npc) {
        return npc.isAlive() && npc.level() == player.level() && player.distanceToSqr(npc) <= MAX_DIST_SQ && !player.isSpectator();
    }

    /**
     * C2S: buy (from stock index), sell (from inventory slot or pack entry) or buy back (from the buy-back list) one
     * item or the whole stack. {@code confirm} is the explicit second click that sells an equipped item.
     */
    static void handleAction(ServerPlayer player, int entityId, int op, int index, boolean all, Identifier itemId, boolean confirm) {
        Viewer viewer = VIEWERS.get(player.getUUID());
        if (viewer == null || viewer.player != player || viewer.entityId != entityId) return;
        if (!(player.level().getEntity(entityId) instanceof LivingEntity npc) || !Merchants.isMerchant(npc) || !canReach(player, npc)) return;
        if (!viewer.npc.equals(npc.getUUID())) return;
        // a screen opened at 19:59 (or before a fight) is not good for ever: the shop hours and the mood are re-checked
        if (!Merchants.alwaysOpen(npc) && !Shop.isOpen(player.level())) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.economy.closed"));
            return;
        }
        if (com.skycraft.crime.Bounty.isHostile(player)) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.economy.closed"));
            return;
        }
        Shop shop = Shop.of(npc, player);
        Component msg = switch (op) {
            case OP_BUY -> buy(player, shop, index, all, itemId);
            case OP_SELL -> sell(player, shop, index, all, itemId, confirm);
            case OP_BUYBACK -> buyback(player, shop, index, all, itemId);
            default -> Component.empty();
        };
        sendState(player, npc, shop, msg, false);

        refreshViewers(player, shop.key());
    }

    /** Shop updates go only to active, reachable screens, including other counters of the same shop. */
    private static void refreshViewers(ServerPlayer actor, String shopKey) {
        var iterator = VIEWERS.values().iterator();
        while (iterator.hasNext()) {
            Viewer v = iterator.next();
            if (!(v.player.level().getEntity(v.npc) instanceof LivingEntity counter)
                    || v.player.isRemoved() || !v.player.isAlive() || !canReach(v.player, counter)
                    || !Merchants.isMerchant(counter)) {
                iterator.remove();
                continue;
            }
            if (v.player != actor && v.shop.equals(shopKey)) {
                Shop theirs = Shop.of(counter, v.player);
                if (theirs.key().equals(shopKey)) sendState(v.player, counter, theirs, Component.empty(), false);
            }
        }
    }

    public static void close(ServerPlayer player, int entityId) {
        Viewer v = VIEWERS.get(player.getUUID());
        if (v != null && v.player == player && v.entityId == entityId) VIEWERS.remove(player.getUUID());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        VIEWERS.remove(event.getEntity().getUUID());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onStop(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        VIEWERS.clear();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        VIEWERS.values().removeIf(v -> v.player.isRemoved() || !v.player.isAlive()
                || !(v.player.level().getEntity(v.npc) instanceof LivingEntity npc) || !canReach(v.player, npc));
    }

    private static boolean matches(ItemStack stack, Identifier itemId) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId);
    }

    static Component buy(ServerPlayer player, Shop shop, int index, boolean all, Identifier itemId) {
        if (index < 0 || index >= shop.rows()) return Component.empty();
        ItemStack stack = shop.row(index);
        if (!matches(stack, itemId)) return Component.empty();
        // the same filter the list was built with: a cook sells the food only (plus what players sold to the shop), a keepsake is never for sale
        if (!shelfVisible(shop.npc(), stack) && (Resale.isKeepsake(stack) || shop.soldCount(stack) <= 0)) return Component.empty();
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
        // the shelf row, read and reduced in this one server-thread step: two customers can never both get the last one
        shop.take(index, bought);
        shop.receive(total);
        shop.save();
        Progression.addSkillXp(player, Skill.SPEECH, total);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
        if (bought < want) {
            return bought < affordable
                    ? Component.translatable("message.skycraft.economy.inventory_full")
                    : Component.translatable("message.skycraft.economy.not_enough_gold");
        }
        return Component.translatable("message.skycraft.economy.bought", bought, name, total);
    }


    static Component sell(ServerPlayer player, Shop shop, int slot, boolean all, Identifier itemId, boolean confirm) {
        Inventory inv = player.getInventory();
        boolean packed = Pack.isVirtual(slot);
        ItemStack stack;
        int eq;
        if (packed) {
            stack = Pack.get(player, slot - Pack.VIRTUAL_BASE);
            eq = InventoryActions.isWornJewelry(player, stack) ? InventoryActions.EQ_WORN : InventoryActions.EQ_NONE;
        } else {
            if (slot < 0 || slot >= inv.getContainerSize()) return Component.empty();
            stack = inv.getItem(slot);
            eq = InventoryActions.equipState(player, inv, slot, stack);
        }
        if (!matches(stack, itemId)) return Component.empty();
        ItemStack template = stack.copy();
        LivingEntity npc = shop.npc();
        Component name = stack.getHoverName();
        // quest items are never sold (also rejected here when a modified client asks): they are filtered out of the list
        if (isQuestKind(stack)) return Component.translatable("message.skycraft.economy.quest_item", name);
        if (!sellable(stack) || Pricing.sellPrice(player, stack, npc) <= 0) return Component.translatable("message.skycraft.economy.cant_sell");
        // a worn item with the curse of binding can't be taken off, so it can't be sold either
        if (eq != InventoryActions.EQ_NONE && !player.isCreative() && EnchantmentHelper.has(stack, net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            return Component.translatable("message.skycraft.economy.bound", name);
        }
        if (!Merchants.buysFrom(player, npc, stack)) return Component.translatable("message.skycraft.economy.not_dealt");
        boolean stolen = Merchants.isStolen(stack);
        if (stolen && !Merchants.isFence(player, npc, shop)) return Component.translatable("message.skycraft.economy.stolen");
        if (Merchants.isContraband(npc, stack) && !Merchants.takesContraband(player, npc, shop)) {
            return Component.translatable("message.skycraft.economy.contraband");
        }
        // an equipped item is sold only on an explicit second click, and only one at a time
        boolean equipped = eq != InventoryActions.EQ_NONE;
        if (equipped && !confirm) return Component.translatable("message.skycraft.economy.confirm_equipped", name);
        // an artifact or a unique thing is not put on the shelf: the shop keeps it for the seller alone (buy-back), so it
        // takes an explicit second click, and it is gone for good once the buy-back time runs out
        boolean keepsake = Resale.isKeepsake(stack);
        if (keepsake && !confirm) return Component.translatable("message.skycraft.economy.confirm_keepsake", name);

        // where the items come from: the clicked slot, the other unequipped stacks of the row, the pack part
        List<Integer> slots = new ArrayList<>();
        boolean usePack = false;
        int available = 0;
        if (equipped) {
            if (packed) usePack = true;
            else slots.add(slot);
            available = 1;
        } else {
            if (!packed) {
                slots.add(slot);
                available += stack.getCount();
            }
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                if (i == slot) continue;
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, template)) continue;
                if (InventoryActions.equipState(player, inv, i, s) != InventoryActions.EQ_NONE) continue;
                slots.add(i);
                available += s.getCount();
            }
            int pi = Pack.indexOf(player, template);
            if (pi >= 0) {
                usePack = true;
                available += packed ? stack.getCount() : Pack.get(player, pi).getCount();
            }
        }
        int unit = Pricing.sellPrice(player, template, npc);
        int want = equipped || !all ? 1 : available;
        want = Math.min(want, available);
        int n = Math.min(want, shop.available(player) / unit);
        if (n <= 0) return Component.translatable("message.skycraft.economy.no_merchant_gold");

        ItemStack sold = template.copy();
        sold.setCount(0);
        int left = n;
        for (int s : slots) {
            if (left <= 0) break;
            ItemStack part = inv.removeItem(s, left);
            sold.grow(part.getCount());
            left -= part.getCount();
        }
        if (left > 0 && usePack) {
            int pi = Pack.indexOf(player, template);
            ItemStack part = pi < 0 ? ItemStack.EMPTY : Pack.take(player, pi, left);
            sold.grow(part.getCount());
            left -= part.getCount();
        }
        n = sold.getCount();
        if (n <= 0) return Component.empty();
        int total = unit * n;
        ItemStack original = sold.copy();
        if (stolen) Merchants.clearStolen(sold);
        ItemStack inStock = sold.copy();
        shop.pay(player, total);
        if (keepsake) {
            // never on the shared shelf: only this player can buy it back (persisted with the shop)
            Buyback.recordHeld(player, shop, original, n, unit);
        } else {
            // on sale to every player from now on, at the shop's markup; the seller's buy-back takes it off again
            shop.addSold(sold);
            Buyback.record(player, shop, original, inStock, n, unit);
        }
        shop.save();
        Currency.give(player, total);
        if (!Buyback.noXp(player, shop)) Progression.addSkillXp(player, Skill.SPEECH, total);
        if (equipped && eq == InventoryActions.EQ_WORN) unwearJewelry(player, original);
        CarryWeight.invalidate(player);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 0.8f);
        if (n < want) return Component.translatable("message.skycraft.economy.no_merchant_gold");
        return Component.translatable("message.skycraft.economy.sold", n, name, total);
    }

    /** Sold the last of a worn piece of jewellery: it is no longer worn (the apparel module keeps the item id). */
    private static void unwearJewelry(ServerPlayer player, ItemStack sold) {
        if (!ItemCategory.isJewelry(sold)) return;
        Item item = sold.getItem();
        if (player.getInventory().countItem(item) > 0 || Pack.count(player, item) > 0) return;
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        com.skycraft.core.PlayerData data = com.skycraft.core.SkyData.get(player);
        CompoundTag apparel = data.module("apparel");
        boolean changed = false;
        for (String kind : new String[]{"necklace", "ring", "circlet"}) {
            if (id != null && id.toString().equals(apparel.getStringOr(kind, ""))) {
                apparel.remove(kind);
                changed = true;
            }
        }
        if (changed) {
            data.markDirty();
            com.skycraft.core.PlayerDataEvents.fullSync(player);
        }
    }

    /** Buys back what this player sold to this merchant: at the price it was paid, from the shop's stock. */
    static Component buyback(ServerPlayer player, Shop shop, int index, boolean all, Identifier itemId) {
        List<Buyback.Entry> list = Buyback.list(player, shop);
        if (index < 0 || index >= list.size()) return Component.empty();
        Buyback.Entry e = list.get(index);
        if (!matches(e.original, itemId)) return Component.empty();
        Component name = e.original.getHoverName();
        // shelf goods must still be on the shelf or in the seller's shelf-expired buyback pool (if another customer
        // bought them meanwhile, they are gone); kept goods are the seller's alone and are always there
        int available = e.availableCount(shop);
        int want = Math.min(all ? e.count : 1, Math.min(e.count, available));
        if (want <= 0) return Component.translatable("message.skycraft.economy.buyback_gone");
        int affordable = (int) Math.min(want, Currency.balance(player) / Math.max(1, e.unit));
        if (affordable <= 0) return Component.translatable("message.skycraft.not_enough_gold");

        ItemStack give = e.original.copy();
        give.setCount(affordable);
        ActionHandler.addToBags(player, give);
        int bought = affordable - give.getCount();
        if (bought <= 0) return Component.translatable("message.skycraft.economy.inventory_full");
        int total = e.unit * bought;
        Currency.take(player, total);
        if (!e.held()) {
            int fromShelf = shop.takeEqual(e.inStock, bought);
            int fromExpired = bought - fromShelf;
            if (fromExpired > 0) e.shelfExpiredCount = Math.max(0, e.shelfExpiredCount - fromExpired);
        }
        shop.receive(total);
        shop.save();
        Buyback.consume(player, shop, e, bought);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
        return Component.translatable("message.skycraft.economy.bought_back", bought, name, total);
    }

    /**
     * Whether this merchant offers this shelf row: never a keepsake (an old per-NPC stock may still hold one), and a
     * person whose job sells part of the shop's stock (the cook) only sells that part.
     */
    static boolean shelfVisible(LivingEntity npc, ItemStack row) {
        if (row.isEmpty() || Resale.isKeepsake(row)) return false;
        java.util.Set<ItemCategory> sells = Merchants.sells(npc);
        return sells.isEmpty() || sells.contains(ItemCategory.of(row));
    }

    /** True for quest items: the quest flag, and the item kinds that are quest items by nature (letters, relics, keys). */
    public static boolean isQuestKind(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (com.skycraft.quest.QuestItems.isQuestItem(stack)) return true;
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag != null && tag.getBooleanOr(NO_SELL_NBT, false)) return true;
        Item it = stack.getItem();
        return it instanceof com.skycraft.quest.QuestItems.QuestItem || it instanceof com.skycraft.quest.QuestItems.RelicItem
                || it instanceof com.skycraft.quest.QuestItems.BlackHandLetter;
    }

    /** Coins, quest items, conjured weapons and filled containers (shulker boxes, bundles) are never sold. */
    public static boolean sellable(ItemStack stack) {
        if (stack.isEmpty() || Currency.valueOf(stack) > 0 || isQuestKind(stack)) return false;
        if (com.skycraft.magic.bound.BoundWeapons.isBound(stack)) return false;
        // 26.3: contents live in components (bundle_contents, container), not in the 1.20.1 "Items" /
        // "BlockEntityTag.Items" NBT the stack used to carry
        net.minecraft.world.item.component.BundleContents bundle = stack.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) return false;
        net.minecraft.world.item.component.ItemContainerContents box = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        return box == null || !box.nonEmptyItemCopyStream().findAny().isPresent();
    }

    // ------------------------------------------------------------------ state

    static void sendState(ServerPlayer player, LivingEntity npc, Shop shop, Component message, boolean open) {
        List<EconomyPackets.Entry> merchant = new ArrayList<>();
        // the shop's shelves, as this person sells them (the same rows for every customer and every counter)
        int rows = shop.rows();
        for (int i = 0; i < rows; i++) {
            ItemStack s = shop.row(i);
            if (!shelfVisible(npc, s)) continue;
            merchant.add(new EconomyPackets.Entry(i, s, Pricing.buyPrice(player, s, npc), (byte) ItemCategory.of(s).ordinal(), STATUS_OK, (byte) 0));
        }
        List<EconomyPackets.Entry> mine = sellEntries(player, npc, shop);
        // the buy-back list: what this player sold here lately (shelf goods or shelf-expired buyback pool)
        List<EconomyPackets.Entry> back = new ArrayList<>();
        List<Buyback.Entry> sold = Buyback.list(player, shop);
        for (int i = 0; i < sold.size(); i++) {
            Buyback.Entry e = sold.get(i);
            int count = e.availableCount(shop);
            if (count <= 0) continue;
            ItemStack shown = e.original.copy();
            shown.setCount(count);
            back.add(new EconomyPackets.Entry(i, shown, e.unit, (byte) ItemCategory.of(shown).ordinal(), STATUS_OK, (byte) 0));
        }
        SkyNetwork.sendToPlayer(player, new EconomyPackets.BarterState(npc.getId(), npc.getDisplayName(), open,
                Currency.balance(player), shop.available(player), message, merchant, mine, back));
    }

    /**
     * The sell list: every equipped item is a row of its own (marked), the unequipped stacks of one item are one row
     * (slots and overflow pack together), so selling a row never touches what you wear or hold. Quest items, coins,
     * conjured weapons and filled containers are not listed.
     */
    static List<EconomyPackets.Entry> sellEntries(ServerPlayer player, LivingEntity npc, Shop shop) {
        List<EconomyPackets.Entry> mine = new ArrayList<>();
        boolean fence = Merchants.isFence(player, npc, shop);
        boolean smuggler = Merchants.takesContraband(player, npc, shop);
        Inventory inv = player.getInventory();
        List<SellRow> rows = new ArrayList<>();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty()) continue;
            addRow(rows, slot, s, InventoryActions.equipState(player, inv, slot, s));
        }
        List<ItemStack> packed = Pack.entries(player);
        for (int i = 0; i < packed.size(); i++) {
            ItemStack s = packed.get(i);
            if (s.isEmpty()) continue;
            addRow(rows, Pack.VIRTUAL_BASE + i, s, InventoryActions.isWornJewelry(player, s) ? InventoryActions.EQ_WORN : InventoryActions.EQ_NONE);
        }
        for (SellRow row : rows) {
            ItemStack s = row.stack;
            if (!sellable(s)) continue;
            int price = Pricing.sellPrice(player, s, npc);
            if (price <= 0) continue;
            byte status = STATUS_OK;
            if (!Merchants.buysFrom(player, npc, s)) {
                if (!com.skycraft.perk.Perks.has(player, "speech.merchant")) continue;
            }
            if (Resale.isKeepsake(s)) status = STATUS_KEEPSAKE;
            if (Merchants.isStolen(s) && !fence) status = STATUS_STOLEN;
            if (!smuggler && Merchants.isContraband(npc, s)) status = STATUS_STOLEN;
            mine.add(new EconomyPackets.Entry(row.index, s.copy(), price, (byte) ItemCategory.of(s).ordinal(), status, (byte) row.equip));
        }
        return mine;
    }

    /** One line of the sell list. */
    private static final class SellRow {
        final int index;
        final ItemStack stack;
        final int equip;

        SellRow(int index, ItemStack stack, int equip) {
            this.index = index;
            this.stack = stack;
            this.equip = equip;
        }
    }

    private static void addRow(List<SellRow> rows, int index, ItemStack s, int equip) {
        if (equip == InventoryActions.EQ_NONE) {
            for (SellRow r : rows) {
                if (r.equip == InventoryActions.EQ_NONE && ItemStack.isSameItemSameComponents(r.stack, s)) {
                    r.stack.grow(s.getCount());
                    return;
                }
            }
        }
        rows.add(new SellRow(index, s.copy(), equip));
    }

    // ------------------------------------------------------------------ bulk selling (dialogue topics)

    public static boolean hasAny(ServerPlayer player, TagKey<Item> filter) {
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < MAIN_INVENTORY; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.is(filter) && sellable(s) && !Merchants.isStolen(s)
                    && InventoryActions.equipState(player, inv, slot, s) == InventoryActions.EQ_NONE) return true;
        }
        return Pack.has(player, s -> s.is(filter) && sellable(s) && !Merchants.isStolen(s));
    }

    /** Sells every (non-stolen) item matching {@code filter} from the main inventory in one go, then reopens the dialogue. */
    public static void bulkSell(ServerPlayer player, LivingEntity npc, TagKey<Item> filter) {
        if (!Merchants.isMerchant(npc) || !canReach(player, npc)) return;
        Shop shop = Shop.of(npc, player);
        Inventory inv = player.getInventory();
        int total = 0;
        int count = 0;
        boolean ranOut = false;
        // slots first (never what is held or worn), then the overflow pack entries (last entry first)
        int packSize = Pack.size(player);
        for (int k = 0; k < MAIN_INVENTORY + packSize; k++) {
            boolean fromPack = k >= MAIN_INVENTORY;
            int slot = k;
            int packIndex = packSize - 1 - (k - MAIN_INVENTORY);
            ItemStack s = fromPack ? Pack.get(player, packIndex) : inv.getItem(slot);
            if (s.isEmpty() || !s.is(filter) || !sellable(s) || Merchants.isStolen(s) || Resale.isKeepsake(s)) continue;
            if (!fromPack && InventoryActions.equipState(player, inv, slot, s) != InventoryActions.EQ_NONE) continue;
            if (fromPack && InventoryActions.isWornJewelry(player, s)) continue;
            int unit = Pricing.sellPrice(player, s, npc);
            if (unit <= 0) continue;
            int n = Math.min(s.getCount(), shop.available(player) / unit);
            if (n < s.getCount()) ranOut = true;
            if (n <= 0) continue;
            ItemStack sold = fromPack ? Pack.take(player, packIndex, n) : inv.removeItem(slot, n);
            if (sold.isEmpty()) continue;
            int got = sold.getCount() * unit;
            ItemStack original = sold.copy();
            shop.addSold(sold);
            shop.pay(player, got);
            Buyback.record(player, shop, original, original, original.getCount(), unit);
            total += got;
            count += original.getCount();
        }
        shop.save();
        Component line;
        if (count == 0) {
            line = Component.translatable(ranOut ? "dialogue.skycraft.economy.bulk.no_gold" : "dialogue.skycraft.economy.bulk.nothing");
        } else {
            Currency.give(player, total);
            if (!Buyback.noXp(player, shop)) Progression.addSkillXp(player, Skill.SPEECH, total);
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 0.9f);
            line = ranOut
                    ? Component.translatable("dialogue.skycraft.economy.bulk.partial", count, total)
                    : Component.translatable("dialogue.skycraft.economy.bulk.done", count, total);
        }
        Dialogue.open(player, npc, line);
    }
}
