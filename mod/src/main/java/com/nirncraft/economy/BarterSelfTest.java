package com.nirncraft.economy;

import com.nirncraft.core.Currency;
import com.nirncraft.core.SkyData;
import com.nirncraft.inventory.Pack;
import com.nirncraft.inventory.PackSelfTest;
import com.nirncraft.inventory.ValueDensity;
import com.nirncraft.quest.QuestItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Selling, part of the inventory self-test ({@code com.nirncraft.inventory.PackSelfTest}): a real merchant entity and the
 * real server-side sell/buy-back code paths. Quest items are refused and never listed, an equipped item needs the
 * explicit confirmation and only one goes, the unequipped row sells around it, and everything sold can be bought back.
 */
public final class BarterSelfTest {
    private BarterSelfTest() {}

    private static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString();
    }

    private static int count(ServerPlayer p, Item item) {
        return p.getInventory().countItem(item) + Pack.count(p, item);
    }

    public static void run(MinecraftServer server, ServerLevel level, ServerPlayer p, PackSelfTest.Checks c) {
        p.getInventory().clearContent();
        Pack.clear(p);
        Villager npc = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        if (npc == null) {
            c.check("barter: a test merchant", false, "could not create a villager");
            return;
        }
        npc.setVillagerData(npc.getVillagerData().withProfession(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.CARTOGRAPHER)).withLevel(2));
        npc.setPos(p.getX(), p.getY(), p.getZ());
        Shop shop = Shop.of(npc, p);
        SkyData.get(p).setGold(1000);

        // an item this merchant deals in
        Item sellItem = null;
        for (Item it : new Item[]{Items.IRON_SWORD, Items.IRON_INGOT, Items.WHEAT, Items.LEATHER, Items.PAPER, Items.BREAD, Items.COAL}) {
            ItemStack probe = new ItemStack(it);
            if (Merchants.dealsIn(npc, probe) && Pricing.sellPrice(p, probe, npc) > 0) {
                sellItem = it;
                break;
            }
        }
        if (sellItem == null) {
            c.check("barter: the test merchant deals in something", false, "no candidate item is bought by " + Merchants.pools(npc));
            return;
        }
        Identifier sellId = BuiltInRegistries.ITEM.getKey(sellItem);

        // ---- quest items: filtered out of the list and refused server-side (slot and pack)
        ItemStack letter = QuestItems.letter("whiterun");
        ItemStack key = QuestItems.cottageKey(p.getUUID());
        ItemStack relic = new ItemStack(QuestItems.QUEST_RELIC.get());
        com.nirncraft.inventory.ItemData.updateCustom(relic, customData263 -> customData263.putString(QuestItems.RELIC_TAG, "selftest"));
        p.getInventory().setItem(10, letter);
        p.getInventory().setItem(11, key);
        p.getInventory().setItem(12, relic); // a relic by item kind alone (no quest flag in its NBT)
        ItemStack packLetter = QuestItems.letter("riften");
        Pack.add(p, packLetter.copy());
        int stockBefore = shop.stock().size();
        long goldBefore = Currency.balance(p);
        boolean listed = false;
        for (EconomyPackets.Entry e : Barter.sellEntries(p, npc, shop)) if (Barter.isQuestKind(e.stack())) listed = true;
        c.check("barter: quest items are not in the sell list", !listed, listed ? "a quest item is listed" : "none listed");
        String k1 = key(Barter.sell(p, shop, 10, true, BuiltInRegistries.ITEM.getKey(letter.getItem()), true));
        String k2 = key(Barter.sell(p, shop, 11, true, BuiltInRegistries.ITEM.getKey(key.getItem()), true));
        String k3 = key(Barter.sell(p, shop, 12, true, BuiltInRegistries.ITEM.getKey(relic.getItem()), true));
        String k4 = key(Barter.sell(p, shop, Pack.VIRTUAL_BASE + Pack.find(p, s -> QuestItems.isQuestItem(s)),
                true, BuiltInRegistries.ITEM.getKey(packLetter.getItem()), true));
        boolean refused = k1.endsWith("quest_item") && k2.endsWith("quest_item") && k3.endsWith("quest_item") && k4.endsWith("quest_item");
        c.check("barter: a quest item sale is rejected server-side (letter, key, relic, pack letter)", refused, k1 + ", " + k2 + ", " + k3 + ", " + k4);
        c.check("barter: a rejected sale takes nothing and pays nothing",
                !p.getInventory().getItem(10).isEmpty() && !p.getInventory().getItem(11).isEmpty() && !p.getInventory().getItem(12).isEmpty()
                        && Pack.find(p, s -> QuestItems.isQuestItem(s)) >= 0 && shop.stock().size() == stockBefore && Currency.balance(p) == goldBefore,
                "gold " + goldBefore + " -> " + Currency.balance(p));
        p.getInventory().clearContent();
        Pack.clear(p);

        // ---- equipped rows: marked, listed apart, confirmed, one at a time; the unequipped row sells around them
        Inventory inv = p.getInventory();
        inv.setItem(inv.getSelectedSlot(), new ItemStack(sellItem, 1));
        inv.setItem(10, new ItemStack(sellItem, 1));
        inv.setItem(11, new ItemStack(sellItem, 1));
        Pack.add(p, new ItemStack(sellItem, 1)); // the pack part of the same row (a virtual slot)
        int unequipped = 3;
        List<EconomyPackets.Entry> rows = Barter.sellEntries(p, npc, shop);
        EconomyPackets.Entry heldRow = null;
        EconomyPackets.Entry freeRow = null;
        for (EconomyPackets.Entry e : rows) {
            if (e.stack().is(sellItem)) {
                if (e.equip() != 0) heldRow = e;
                else freeRow = e;
            }
        }
        c.check("barter: the held item is its own marked row", heldRow != null && heldRow.equip() == 1 && heldRow.index() == inv.getSelectedSlot(),
                heldRow == null ? "no row" : "equip " + heldRow.equip());
        c.check("barter: the unequipped copies are one row (slots and pack together)",
                freeRow != null && freeRow.equip() == 0 && freeRow.stack().getCount() == unequipped, freeRow == null ? "no row" : "count " + freeRow.stack().getCount());
        String confirm = key(Barter.sell(p, shop, inv.getSelectedSlot(), false, sellId, false));
        c.check("barter: selling an equipped item without confirmation is refused", confirm.endsWith("confirm_equipped")
                && !inv.getItem(inv.getSelectedSlot()).isEmpty(), confirm);
        long gold0 = Currency.balance(p);
        String rowSale = key(Barter.sell(p, shop, freeRow == null ? 10 : freeRow.index(), true, sellId, false));
        c.check("barter: 'sell all' on the unequipped row sells only the unequipped items",
                rowSale.endsWith("sold") && !inv.getItem(inv.getSelectedSlot()).isEmpty() && inv.getItem(10).isEmpty() && inv.getItem(11).isEmpty()
                        && Pack.count(p, sellItem) == 0, rowSale + ", held kept: " + !inv.getItem(inv.getSelectedSlot()).isEmpty());
        long goldAfterSale = Currency.balance(p);

        // ---- buy back: everything just sold, at the price it was paid
        List<Buyback.Entry> back = Buyback.list(p, shop);
        c.check("barter: the sale is on the buy-back list", back.size() == 1 && back.get(0).count == unequipped,
                back.size() + " entries" + (back.isEmpty() ? "" : ", count " + back.get(0).count));
        String bb = key(Barter.buyback(p, shop, 0, true, sellId));
        c.check("barter: buy back returns the items for the sale price", bb.endsWith("bought_back") && count(p, sellItem) == 1 + unequipped
                && Currency.balance(p) == gold0, bb + ", items " + count(p, sellItem) + ", gold " + Currency.balance(p) + " (was " + gold0
                + ", after the sale " + goldAfterSale + ")");

        // the equipped item itself: confirmed sale sells one, then it can be bought back too
        String eq = key(Barter.sell(p, shop, inv.getSelectedSlot(), true, sellId, true));
        c.check("barter: a confirmed sale of the equipped item sells exactly that one", eq.endsWith("sold") && inv.getItem(inv.getSelectedSlot()).isEmpty()
                && count(p, sellItem) == unequipped, eq + ", items left " + count(p, sellItem));
        String eb = key(Barter.buyback(p, shop, 0, false, sellId));
        c.check("barter: the equipped item can be bought back", eb.endsWith("bought_back") && count(p, sellItem) == unequipped + 1, eb);
        inv.clearContent();
        Pack.clear(p);
        Buyback.forget(p.getUUID());
        ShopBook.get(server).forget(shop.key());
        sharedShops(server, level, p, c);
        bulkBuybackXp(level, p, c);
        activeShopViewers(server, level, p, c);
        tradeLint(level, p, c);
        valueDensity(c);
        tradeNetworkAndHomesteads(level, c);
    }

    // ------------------------------------------------------------------ shared shops, resale, kept goods

    /** A villager working at a counter of the shop {@code key}, selling the {@code general} trade (it buys anything). */
    private static Villager counter(ServerLevel level, ServerPlayer p, String key, String job) {
        Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        if (v == null) return null;
        v.setVillagerData(v.getVillagerData().withProfession(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.CARTOGRAPHER)).withLevel(3));
        v.setPos(p.getX(), p.getY(), p.getZ());
        CompoundTag shop = new CompoundTag();
        net.minecraft.nbt.ListTag pools = new net.minecraft.nbt.ListTag();
        pools.add(net.minecraft.nbt.StringTag.valueOf("general"));
        shop.put("pools", pools);
        shop.putInt("level", 3);
        shop.putString(ShopKeys.KEY, key);
        v.getPersistentData().put(Shop.KEY, shop);
        if (job != null) v.getPersistentData().putString(com.nirncraft.society.Housing.JOB_KEY, job);
        return v;
    }

    private static void bulkBuybackXp(ServerLevel level, ServerPlayer p, PackSelfTest.Checks c) {
        String shopKey = "selftest/bulk-xp";
        Villager npc = counter(level, p, shopKey, null);
        if (npc == null) { c.check("bulk sale: merchant exists", false, "no villager"); return; }
        npc.getPersistentData().putBoolean("nirncraft_always_open", true);
        p.getInventory().clearContent();
        Pack.clear(p);
        Buyback.forget(p.getUUID());
        Shop shop = Shop.of(npc, p);
        shop.record().gold = 100_000;
        SkyData.get(p).setGold(100_000);
        p.getInventory().setItem(10, new ItemStack(Items.WHEAT, 6));
        Barter.bulkSell(p, npc, ShopType.CROPS);
        var entries = Buyback.list(p, shop);
        int row = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).original.is(Items.WHEAT)) row = i;
        c.check("bulk sale: wheat enters real buyback", row >= 0, entries.size() + " rows");
        if (row >= 0) {
            Barter.buyback(p, shop, row, true, BuiltInRegistries.ITEM.getKey(Items.WHEAT));
            var data = SkyData.get(p);
            int skill = data.getSkill(com.nirncraft.core.Skill.SPEECH);
            float xp = data.getSkillXp(com.nirncraft.core.Skill.SPEECH);
            int before = count(p, Items.WHEAT);
            Barter.bulkSell(p, npc, ShopType.CROPS);
            c.check("bulk sale: recently bought back goods still sell without Speech XP",
                    before == 6 && count(p, Items.WHEAT) == 0 && Buyback.noXp(p, shop)
                    && skill == data.getSkill(com.nirncraft.core.Skill.SPEECH)
                    && xp == data.getSkillXp(com.nirncraft.core.Skill.SPEECH), "wheat " + before + " -> " + count(p, Items.WHEAT));
        }
        Buyback.forget(p.getUUID());
        ShopBook.get(level.getServer()).forget(shopKey);
        p.getInventory().clearContent();
        Pack.clear(p);
    }

    /** Real merchant/player identities and PacketDistributor recipient dispatch, recorded at the native listener boundary.
     * This checks subscriptions; physical connection negotiation/codec acceptance is a separate multiplayer gate. */
    private static void activeShopViewers(MinecraftServer server, ServerLevel level, ServerPlayer anchor, PackSelfTest.Checks c) {
        String key = "selftest/viewers";
        var players = new java.util.ArrayList<ServerPlayer>();
        var wires = new java.util.ArrayList<PackSelfTest.RecordingPacketListener>();
        var merchants = new java.util.ArrayList<LivingEntity>();
        try {
            for (int i = 0; i < 3; i++) {
                var p = new net.neoforged.neoforge.common.util.FakePlayer(level,
                        new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "[TradeView" + i + "]"));
                p.setPos(anchor.getX() + i, anchor.getY(), anchor.getZ());
                var wire = new PackSelfTest.RecordingPacketListener(server, p);
                players.add(p); wires.add(wire);
                level.addNewPlayer(p);
            }
            for (int i = 0; i < 2; i++) {
                var npc = com.nirncraft.society.NpcEntities.NPC.get().create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
                if (npc == null) throw new IllegalStateException("Cannot create test counter");
                npc.setRole(com.nirncraft.society.NpcRole.MERCHANT);
                npc.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
                npc.setNoAi(true);
                CompoundTag shop = new CompoundTag();
                var pools = new net.minecraft.nbt.ListTag();
                pools.add(net.minecraft.nbt.StringTag.valueOf("general"));
                shop.put("pools", pools);
                shop.putString(ShopKeys.KEY, key);
                shop.putInt("level", 3);
                npc.getPersistentData().put(Shop.KEY, shop);
                npc.getPersistentData().putBoolean("nirncraft_always_open", true);
                merchants.add(npc);
                if (!level.addFreshEntity(npc)) throw new IllegalStateException("Cannot add test counter");
            }
            Barter.open(players.get(0), merchants.get(0));
            Barter.open(players.get(1), merchants.get(1));
            wires.forEach(w -> w.packets = 0);
            Barter.handleAction(players.get(0), merchants.get(0).getId(), 999, 0, false,
                    BuiltInRegistries.ITEM.getKey(Items.WHEAT), false);
            c.check("barter subscriptions: active other counter refreshes; nearby bystander gets no packets",
                    wires.get(0).packets == 1 && wires.get(1).packets == 1 && wires.get(2).packets == 0,
                    wires.stream().map(w -> Integer.toString(w.packets)).collect(java.util.stream.Collectors.joining("/")));
            Barter.close(players.get(1), merchants.get(1).getId());
            wires.forEach(w -> w.packets = 0);
            Barter.handleAction(players.get(0), merchants.get(0).getId(), 999, 0, false,
                    BuiltInRegistries.ITEM.getKey(Items.WHEAT), false);
            c.check("barter subscriptions: closing a screen stops updates", wires.get(0).packets == 1
                    && wires.get(1).packets == 0 && wires.get(2).packets == 0, "closed and unopened peers receive0");
            wires.forEach(w -> w.packets = 0);
            Barter.handleAction(players.get(2), merchants.get(0).getId(), 999, 0, false,
                    BuiltInRegistries.ITEM.getKey(Items.WHEAT), false);
            c.check("barter subscriptions: unopened screen cannot issue actions", wires.stream().allMatch(w -> w.packets == 0),
                    "no packets sent");
        } finally {
            for (ServerPlayer p : players) {
                for (LivingEntity npc : merchants) com.nirncraft.dialogue.Dialogue.endConversation(p, npc.getId());
                level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
            merchants.forEach(net.minecraft.world.entity.Entity::discard);
            ShopBook.get(server).forget(key);
        }
    }

    private static boolean sameStock(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getCount() != b.get(i).getCount() || !ItemStack.isSameItemSameComponents(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private static int rowOf(Shop shop, Item item) {
        for (int i = 0; i < shop.rows(); i++) if (shop.row(i).is(item)) return i;
        return -1;
    }

    /**
     * Docs/INVENTORY.md "Selling": a shop is a building, not a person. Two counters of one inn share stock, gold and
     * buy-back; a restock is a pure function of key and period; what one player sells another can buy; artifacts are
     * kept for the seller alone; the cook sells a filter over the same stock; the whole book survives NBT.
     */
    private static void sharedShops(MinecraftServer server, ServerLevel level, ServerPlayer p, PackSelfTest.Checks c) {
        ShopBook book = ShopBook.get(server);
        String key = "selftest/inn#1";
        book.forget(key);
        p.getInventory().clearContent();
        Pack.clear(p);
        Buyback.forget(p.getUUID());
        SkyData.get(p).setGold(1_000_000);
        Villager innkeeper = counter(level, p, key, null);
        Villager cook = counter(level, p, key, "cook");
        if (innkeeper == null || cook == null) {
            c.check("shops: test counters", false, "could not create villagers");
            return;
        }
        Shop a = Shop.of(innkeeper, p);
        Shop b = Shop.of(cook, p);
        c.check("shops: two counters of one building are one shop (same key, same record)", a.key().equals(key) && b.key().equals(key)
                && a.record() == b.record(), a.key() + " / " + b.key());
        c.check("shops: a shop is stocked for its trade", a.rows() >= ShopTrades.trade(ShopType.GENERAL).minRows(), a.rows() + " rows");

        // determinism: the same key and period give the same list, whoever opens it and whenever
        List<ItemStack> first = a.stock();
        book.forget(key);
        Shop again = Shop.of(innkeeper, p);
        c.check("shops: a restock is a pure function of (shop key, period): rebuilt list is identical", sameStock(first, again.stock()),
                first.size() + " vs " + again.stock().size() + " rows");
        a = again;
        b = Shop.of(cook, p);
        a.record().gold = 900_000; // the shop can pay for anything below

        // a purchase at one counter is gone at the other
        int total = 0;
        for (ItemStack s : a.stock()) total += s.getCount();
        ItemStack row0 = a.row(0);
        Identifier row0Id = BuiltInRegistries.ITEM.getKey(row0.getItem());
        int before = a.count(row0);
        int carried = count(p, row0.getItem());
        Component bought = Barter.buy(p, a, 0, false, row0Id);
        c.check("shops: buying at one counter takes it off the shelf of the other", key(bought).endsWith("bought")
                && b.count(row0) == before - 1 && count(p, row0.getItem()) == carried + 1,
                key(bought) + ", shelf " + before + " -> " + b.count(row0));
        c.check("shops: the gold of the shop is one pool", a.available(p) == b.available(p), a.available(p) + " vs " + b.available(p));

        // the cook only sells a part of the one stock (declared in shop_trades.json), never a stock of her own
        a.addToStock(new ItemStack(Items.BREAD, 5));
        int foodRows = 0, visibleToCook = 0, nonFoodVisible = 0;
        for (int i = 0; i < a.rows(); i++) {
            ItemStack s = a.row(i);
            if (s.isEmpty()) continue;
            if (ItemCategory.of(s) == ItemCategory.FOOD) foodRows++;
            if (Barter.shelfVisible(cook, s)) {
                visibleToCook++;
                if (ItemCategory.of(s) != ItemCategory.FOOD) nonFoodVisible++;
            }
        }
        c.check("shops: the cook sells the food of the shared stock and nothing else", foodRows > 0 && visibleToCook == foodRows && nonFoodVisible == 0,
                foodRows + " food rows, cook sees " + visibleToCook);
        int nonFoodRow = -1;
        for (int i = 0; i < a.rows(); i++) {
            if (!a.row(i).isEmpty() && ItemCategory.of(a.row(i)) != ItemCategory.FOOD) {
                nonFoodRow = i;
                break;
            }
        }
        if (nonFoodRow >= 0) {
            ItemStack nf = a.row(nonFoodRow);
            int had = a.count(nf);
            Component refused = Barter.buy(p, b, nonFoodRow, false, BuiltInRegistries.ITEM.getKey(nf.getItem()));
            c.check("shops: the server refuses the cook's customer a row that is not hers to sell", refused.getString().isEmpty() && a.count(nf) == had,
                    "count " + had + " -> " + a.count(nf));
        }

        // what a player sells goes on the shared shelf: the other counter sells it, after the buy-back time too
        Item sold = null;
        for (Item it : new Item[]{Items.EMERALD, Items.DIAMOND, Items.GOLD_INGOT, Items.AMETHYST_SHARD, Items.IRON_SWORD, Items.CLOCK}) {
            ItemStack probe = new ItemStack(it);
            if (a.count(probe) == 0 && Pricing.sellPrice(p, probe, innkeeper) > 0) {
                sold = it;
                break;
            }
        }
        if (sold == null) {
            c.check("shops: a sale candidate", false, "none of the candidates is worth gold");
        } else {
            Identifier soldId = BuiltInRegistries.ITEM.getKey(sold);
            p.getInventory().setItem(10, new ItemStack(sold, 3));
            String sale = key(Barter.sell(p, a, 10, true, soldId, false));
            c.check("shops: a sale is paid", sale.endsWith("sold") && p.getInventory().getItem(10).isEmpty(), sale);
            c.check("shops: the sold goods are on the shelf of every counter of the shop", a.count(new ItemStack(sold)) == 3
                    && b.count(new ItemStack(sold)) == 3 && a.soldCount(new ItemStack(sold)) == 3,
                    "a " + a.count(new ItemStack(sold)) + ", b " + b.count(new ItemStack(sold)));
            List<Buyback.Entry> list = Buyback.list(p, b);
            c.check("shops: the seller's buy-back list belongs to the shop (the other counter lists it)", list.size() == 1 && list.get(0).count == 3
                    && !list.get(0).held(), list.size() + " entries");
            // the buy-back time runs out: the goods stay for everyone and a customer buys them
            Buyback.forget(p.getUUID());
            c.check("shops: after the buy-back time the goods stay on the shelf", Buyback.list(p, b).isEmpty()
                    && b.count(new ItemStack(sold)) == 3, "shelf " + b.count(new ItemStack(sold)));
            int idx = rowOf(b, sold);
            int have = count(p, sold);
            String buy = idx < 0 ? "no row" : key(Barter.buy(p, b, idx, true, soldId));
            c.check("shops: another customer buys what was sold, at the shop's markup", buy.endsWith("bought") && count(p, sold) == have + 3
                    && b.count(new ItemStack(sold)) == 0 && a.soldCount(new ItemStack(sold)) == 0, buy + ", carried " + count(p, sold));
            String twice = idx < 0 ? "no row" : key(Barter.buy(p, b, idx, true, soldId));
            c.check("shops: the last of a row cannot be bought twice (no duplication)", count(p, sold) == have + 3,
                    "carried " + count(p, sold) + " (" + twice + ")");
        }

        // artifacts are kept for the seller only: never on a shelf, back from the buy-back list, persisted
        Item artifact = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("nirncraft", "dawnbreaker"));
        if (artifact == Items.AIR) {
            c.check("shops: an artifact is a keepsake", false, "nirncraft:dawnbreaker is not registered");
        } else {
            Identifier artId = BuiltInRegistries.ITEM.getKey(artifact);
            p.getInventory().setItem(11, new ItemStack(artifact));
            c.check("shops: an artifact is a keepsake (never resold)", Resale.isKeepsake(new ItemStack(artifact)), "isKeepsake");
            String ask = key(Barter.sell(p, a, 11, false, artId, false));
            c.check("shops: selling an artifact asks for a second click", ask.endsWith("confirm_keepsake") && !p.getInventory().getItem(11).isEmpty(), ask);
            String done = key(Barter.sell(p, a, 11, false, artId, true));
            boolean onShelf = a.count(new ItemStack(artifact)) > 0 || b.count(new ItemStack(artifact)) > 0;
            for (int i = 0; i < b.rows(); i++) if (b.row(i).is(artifact)) onShelf = true;
            boolean listed = false;
            for (EconomyPackets.Entry e : shelfRows(p, innkeeper, a)) if (e.stack().is(artifact)) listed = true;
            c.check("shops: a sold artifact is not on any shelf and not in any customer's list", done.endsWith("sold") && !onShelf && !listed,
                    done + ", shelf " + onShelf + ", listed " + listed);
            List<Buyback.Entry> kept = Buyback.list(p, b);
            c.check("shops: the seller can see it on the buy-back list", kept.size() == 1 && kept.get(0).held() && kept.get(0).original.is(artifact),
                    kept.size() + " entries");

            // the whole book through NBT: stock, ledger, gold, trades and the kept goods
            CompoundTag saved = book.save(new CompoundTag());
            ShopBook copy = ShopBook.load(saved);
            ShopBook.Record r1 = a.record();
            ShopBook.Record r2 = copy.find(key);
            c.check("shops: the shop book survives a save and load (stock, ledger, gold, trades, kept goods)", r2 != null
                    && r2.stock().sameAs(r1.stock()) && r2.sold().sameAs(r1.sold()) && r2.gold == r1.gold && r2.pools().equals(r1.pools())
                    && r2.kept().size() == r1.kept().size() && r2.period == r1.period && r2.level == r1.level,
                    r2 == null ? "record missing" : r2.stock().size() + " rows, " + r2.kept().size() + " kept");

            int idx = kept.isEmpty() ? -1 : 0;
            String back = idx < 0 ? "none" : key(Barter.buyback(p, b, idx, false, artId));
            c.check("shops: the keepsake comes back at the price paid and leaves the record", back.endsWith("bought_back")
                    && count(p, artifact) == 1 && a.record().kept().isEmpty(), back);
        }

        // caps: a flood of sold kinds is bounded, nothing throws, the ledger stays a subset of the shelves
        for (int i = 0; i < ShopBook.MAX_SOLD_ROWS + 20; i++) {
            ItemStack junk = new ItemStack(Items.STICK);
            final int kind = i;
            com.nirncraft.inventory.ItemData.updateCustom(junk, customData263 -> customData263.putInt("selftest_kind", kind));
            a.addSold(junk);
        }
        boolean subset = true;
        for (ItemStack s : a.record().sold().entries()) if (a.count(s) < s.getCount()) subset = false;
        c.check("shops: sold goods are bounded by the ledger cap and stay a subset of the shelves", a.record().sold().size() <= ShopBook.MAX_SOLD_ROWS
                && a.rows() <= ShopBook.MAX_ROWS && subset, a.record().sold().size() + " ledger rows, " + a.rows() + " shelf rows");

        // a restock keeps what players sold (and rebuilds the rest): move the clock one period on
        ItemStack mark = new ItemStack(Items.STICK);
        com.nirncraft.inventory.ItemData.updateCustom(mark, customData263 -> customData263.putInt("selftest_kind", ShopBook.MAX_SOLD_ROWS + 19));
        int markCount = a.count(mark);
        a.record().period -= 1;
        Shop restocked = Shop.of(innkeeper, p);
        c.check("shops: a restock keeps the goods players sold", restocked.count(mark) == markCount && markCount > 0,
                "marker " + markCount + " -> " + restocked.count(mark));

        book.forget(key);
        p.getInventory().clearContent();
        Pack.clear(p);
        Buyback.forget(p.getUUID());
    }

    /** The rows a customer is offered at this counter (what {@code Barter.sendState} puts in the packet). */
    private static List<EconomyPackets.Entry> shelfRows(ServerPlayer p, net.minecraft.world.entity.LivingEntity npc, Shop shop) {
        List<EconomyPackets.Entry> out = new java.util.ArrayList<>();
        for (int i = 0; i < shop.rows(); i++) {
            ItemStack s = shop.row(i);
            if (!Barter.shelfVisible(npc, s)) continue;
            out.add(new EconomyPackets.Entry(i, s, Pricing.buyPrice(p, s, npc), (byte) ItemCategory.of(s).ordinal(), Barter.STATUS_OK, (byte) 0));
        }
        return out;
    }

    // ------------------------------------------------------------------ stock sanity

    /** Where a shop's stock is rolled: a town of several provinces (tags and gear differ by region). */
    private static final int[][] PLACES = {{0, 0}, {6300, 500}, {-8200, 4900}, {-4000, 11300}, {-11200, 13000}, {7700, 10000}};

    /**
     * Every trade carries enough kinds, only its own kind of goods, and its province's flavour: no blacksmith sells
     * potions, no apothecary sells swords, no empty shelf (the trade's floor and allowed categories are data).
     */
    private static void tradeLint(ServerLevel level, ServerPlayer p, PackSelfTest.Checks c) {
        List<String> thin = new java.util.ArrayList<>();
        List<String> foreign = new java.util.ArrayList<>();
        List<String> keepsakes = new java.util.ArrayList<>();
        int runs = 0;
        for (ShopType t : ShopType.values()) {
            ShopTrades.Trade trade = ShopTrades.trade(t);
            for (int[] at : PLACES) {
                for (int lvl : new int[]{1, 3, 5}) {
                    for (int seed = 0; seed < 4; seed++) {
                        com.nirncraft.inventory.BulkStore out = new com.nirncraft.inventory.BulkStore(ShopBook.MAX_ROWS, ShopBook.MAX_PER_ROW, null);
                        int share = Math.max(3, (6 + 3 * lvl) / 1);
                        ShopStock.generate(out, level, new net.minecraft.core.BlockPos(at[0], 80, at[1]), lvl, share, false, t,
                                net.minecraft.util.RandomSource.create(1000L * t.ordinal() + 10L * lvl + seed));
                        runs++;
                        if (out.size() < trade.minRows() && thin.size() < 6) thin.add(t.id() + "@" + at[0] + "," + at[1] + " L" + lvl + ": " + out.size() + " < " + trade.minRows());
                        for (ItemStack s : out.entries()) {
                            if (s.isEmpty()) continue;
                            ItemCategory cat = ItemCategory.of(s);
                            if (!trade.allows(cat) && foreign.size() < 8) {
                                foreign.add(t.id() + " sells " + BuiltInRegistries.ITEM.getKey(s.getItem()) + " (" + cat + ")");
                            }
                            if (Resale.isKeepsake(s) && keepsakes.size() < 4) keepsakes.add(t.id() + " stocks " + BuiltInRegistries.ITEM.getKey(s.getItem()));
                        }
                    }
                }
            }
        }
        c.check("shops: every trade carries at least its declared number of kinds", thin.isEmpty(), runs + " stocks rolled" + (thin.isEmpty() ? "" : "; thin: " + thin));
        c.check("shops: no trade sells another trade's kind of goods", foreign.isEmpty(), foreign.isEmpty() ? "all within the declared categories" : String.join("; ", foreign));
        c.check("shops: no shop stocks a keepsake (artifact, key, curse) of its own accord", keepsakes.isEmpty(), keepsakes.isEmpty() ? "none" : String.join("; ", keepsakes));

        // region flavour: a Dunmer food shop sells Dunmer goods, a Nord one does not
        List<Item> dunmer = ShopType.FOOD.provinceItems("morrowind");
        boolean dunmerHas = false, nordHas = false;
        for (int seed = 0; seed < 12; seed++) {
            for (int[] at : new int[][]{PLACES[1], PLACES[0]}) {
                com.nirncraft.inventory.BulkStore out = new com.nirncraft.inventory.BulkStore(ShopBook.MAX_ROWS, ShopBook.MAX_PER_ROW, null);
                ShopStock.generate(out, level, new net.minecraft.core.BlockPos(at[0], 80, at[1]), 3, 15, false, ShopType.FOOD,
                        net.minecraft.util.RandomSource.create(77L + seed));
                for (ItemStack s : out.entries()) if (dunmer.contains(s.getItem())) {
                    if (at == PLACES[1]) dunmerHas = true;
                    else nordHas = true;
                }
            }
        }
        c.check("shops: a Dunmer food shop sells kwama eggs and saltrice, a Nord one does not", dunmer.isEmpty() || dunmerHas && !nordHas,
                dunmer.size() + " Dunmer goods, Balmora " + dunmerHas + ", Whiterun " + nordHas);
        c.check("shops: mead is a northern drink", ShopTrades.servedIn("nirncraft:nord_mead", "skyrim") && !ShopTrades.servedIn("nirncraft:nord_mead", "morrowind"),
                "nord_mead");

        // every counter of the settlements the world already knows belongs to a building (owner label), so every
        // merchant working in it resolves to the building's one shop; a counter with no owner would be a shop of its own
        int buildings = 0, counters = 0, unkeyed = 0;
        java.util.List<String> unkeyedAt = new java.util.ArrayList<>();
        for (var entry : com.nirncraft.tamriel.settlement.SettlementAnchors.get(level).entries()) {
            java.util.Set<String> owners = new java.util.HashSet<>();
            for (var anchor : entry.anchors()) {
                if (ShopTrades.poolsForAnchor(anchor.type().id()).isEmpty()) continue;
                counters++;
                if (anchor.owner().isEmpty()) {
                    unkeyed++;
                    if (unkeyedAt.size() < 5) unkeyedAt.add(entry.key + ":" + anchor.type().id() + "@" + anchor.pos().toShortString());
                } else {
                    owners.add(anchor.owner());
                }
            }
            buildings += owners.size();
        }
        c.check("shops: every counter of the known settlements belongs to a building (no counter is a shop of its own)", unkeyed == 0,
                counters + " counters in " + buildings + " buildings" + (unkeyed == 0 ? "" : "; unowned: " + unkeyedAt));
        for (String anchorId : ShopTrades.anchorIds()) {
            if (com.nirncraft.tamriel.settlement.AnchorType.byId(anchorId) == null) {
                c.check("shops: shop_trades.json names real anchor types", false, anchorId);
            }
        }
    }

    // ------------------------------------------------------------------ gold per weight

    private static void valueDensity(PackSelfTest.Checks c) {
        c.check("V/W: weightless items show a dash and sort first", ValueDensity.format(ValueDensity.of(500, 0f)).equals("-")
                && ValueDensity.sortKey(ValueDensity.NONE) > ValueDensity.sortKey(ValueDensity.of(1000, 1f)), ValueDensity.format(ValueDensity.of(500, 0f)));
        c.check("V/W: compact formatting (one decimal below 10, whole numbers, k and m suffixes)",
                ValueDensity.format(3.54f).equals("3.5") && ValueDensity.format(0f).equals("0") && ValueDensity.format(12.4f).equals("12")
                        && ValueDensity.format(999.4f).equals("999") && ValueDensity.format(1500f).equals("1.5k") && ValueDensity.format(12500f).equals("13k")
                        && ValueDensity.format(2_500_000f).equals("2.5m") && ValueDensity.format(999.6f).equals("1k"),
                ValueDensity.format(3.54f) + " " + ValueDensity.format(1500f) + " " + ValueDensity.format(12500f) + " " + ValueDensity.format(999.6f));
        c.check("V/W: a ring is worth more per weight than a sword", ValueDensity.of(500, 0.1f) > ValueDensity.of(500, 12f), "ratio");
        boolean short4 = true;
        for (float r : new float[]{0.04f, 0.5f, 9.94f, 99.5f, 999f, 9_999f, 99_999f, 999_999f, 9_999_999f, 2e9f}) {
            if (ValueDensity.format(r).length() > 4) short4 = false;
        }
        c.check("V/W: never wider than four characters", short4, "checked 10 magnitudes");
    }

    // ------------------------------------------------------------------ causal trade network, crops & all-province homesteads (T5)

    private static void tradeNetworkAndHomesteads(ServerLevel level, PackSelfTest.Checks c) {
        // 1. WorldSheets causal citations for every province in TradeNetwork
        List<String> brokenCitations = new java.util.ArrayList<>();
        for (var entry : TradeNetwork.citations().entrySet()) {
            for (var ref : entry.getValue()) {
                if (!com.nirncraft.tamriel.world.WorldSheets.resolves(ref)) {
                    brokenCitations.add(entry.getKey() + ":" + ref);
                }
            }
        }
        c.check("trade: every province's L5/L7 WorldSheets citations resolve", brokenCitations.isEmpty() && !TradeNetwork.citations().isEmpty(),
                brokenCitations.isEmpty() ? TradeNetwork.citations().size() + " provinces cited" : String.join(", ", brokenCitations));

        // 2. Local exports & 1..3 hop import graph across all provinces
        String[] provinces = {"skyrim", "solstheim", "cyrodiil", "morrowind", "high_rock", "orsinium", "hammerfell", "summerset", "valenwood", "elsweyr", "black_marsh"};
        List<String> emptyProv = new java.util.ArrayList<>();
        for (String prov : provinces) {
            if (TradeNetwork.provinceExports(prov).isEmpty()) emptyProv.add(prov);
        }
        c.check("trade: every province declares causal local exports", emptyProv.isEmpty(),
                emptyProv.isEmpty() ? provinces.length + " provinces" : "missing: " + emptyProv);

        String[] hubs = {"whiterun", "solitude", "windhelm", "riften", "imperial_city", "anvil", "balmora", "vivec", "raven_rock",
                "wayrest", "sentinel", "alinor", "elden_root", "torval", "lilmoth"};
        List<String> disconnected = new java.util.ArrayList<>();
        for (String hub : hubs) {
            TradeNetwork.SettlementNode n = TradeNetwork.place(hub);
            if (n == null || n.localExports().isEmpty() || n.imports().isEmpty()) {
                disconnected.add(hub);
            }
        }
        c.check("trade: every major provincial hub has local exports and 1..3-hop import routes", disconnected.isEmpty(),
                disconnected.isEmpty() ? hubs.length + " hubs connected (" + TradeNetwork.nodes().size() + " total nodes)" : "disconnected: " + disconnected);

        // 3. Embargoes, contraband & Green Pact enforcement
        boolean contrabandBlocked = TradeNetwork.isContraband("skyrim", "whiterun", "nirncraft:skooma")
                && TradeNetwork.isContraband("cyrodiil", "imperial_city", "nirncraft:moon_sugar")
                && !TradeNetwork.isContraband("elsweyr", "torval", "nirncraft:moon_sugar")
                && !ShopTrades.servedAt("nirncraft:skooma", "skyrim", "whiterun", ShopType.GENERAL)
                && !ShopTrades.servedAt("nirncraft:skooma", "skyrim", "whiterun", ShopType.APOTHECARY)
                && ShopTrades.servedAt("nirncraft:skooma", "skyrim", "whiterun", ShopType.FENCE)
                && ShopTrades.servedAt("nirncraft:moon_sugar", "elsweyr", "torval", ShopType.FOOD);
        c.check("trade: skooma and moon sugar are contraband outside Elsweyr (stocked only by fences/exotic traders)", contrabandBlocked,
                "skooma/moon_sugar embargo check");

        boolean greenPact = TradeNetwork.isGreenPactViolation("valenwood", "elden_root", "nirncraft:sawn_log")
                && !ShopTrades.servedAt("nirncraft:cabbage", "valenwood", "elden_root", ShopType.FOOD)
                && !ShopTrades.servedAt("minecraft:wheat", "valenwood", "elden_root", ShopType.FOOD);
        c.check("trade: Valenwood Green Pact forbids local timber and plant produce at Bosmer food/hunter stalls", greenPact,
                "elden_root Green Pact check");

        // 4. Supply-and-demand PriceEngine multipliers
        float localWheat = PriceEngine.supplyMultiplier("skyrim", "whiterun", "minecraft:wheat");
        float localSaltrice = PriceEngine.supplyMultiplier("morrowind", "balmora", "nirncraft:saltrice");
        float contrabandSkooma = PriceEngine.supplyMultiplier("skyrim", "whiterun", "nirncraft:skooma");
        float alinorSkooma = PriceEngine.supplyMultiplier("summerset", "alinor", "nirncraft:skooma");
        int wheatWhiterun = PriceEngine.regionalValue("skyrim", "whiterun", new ItemStack(Items.WHEAT), 20);
        int wheatBalmora = PriceEngine.regionalValue("morrowind", "balmora", new ItemStack(Items.WHEAT), 20);
        c.check("trade: PriceEngine discounts local surplus (0.75x-0.85x) and marks up imports and contraband (1.60x-2.20x)",
                localWheat >= 0.75f && localWheat <= 0.85f
                        && localSaltrice >= 0.75f && localSaltrice <= 0.85f
                        && contrabandSkooma >= 1.60f && alinorSkooma >= 1.90f
                        && wheatWhiterun < wheatBalmora,
                String.format(java.util.Locale.ROOT, "local=%.2f, skooma=%.2f/%.2f, wheat %d vs %d",
                        localWheat, contrabandSkooma, alinorSkooma, wheatWhiterun, wheatBalmora));

        // 5. 3D regional crops & native livestock
        String[] regionalCrops = {"carrots", "potatoes", "beetroots", "cabbage", "leek", "garlic", "tomato", "ash_yam",
                "saltrice", "marshmerrow", "comberry", "wickwheat", "hackle_lo", "scathecraw", "coda_flower", "sugar",
                "vines", "melon", "gourd", "orchard", "honey", "kwama"};
        List<String> wheatFallbacks = new java.util.ArrayList<>();
        for (String crop : regionalCrops) {
            int v = com.nirncraft.tamriel.settlement.grammar.Zoning.cropVariant(crop);
            if (v <= 0 || com.nirncraft.tamriel.settlement.build.Works.crop(v).defaultBlockState().isAir()) {
                wheatFallbacks.add(crop + "->" + v);
            }
        }
        c.check("crops: every non-wheat cultural crop maps to a distinct 3D crop variant", wheatFallbacks.isEmpty(),
                wheatFallbacks.isEmpty() ? regionalCrops.length + " regional crops verified" : "fallback: " + wheatFallbacks);

        String[] nativeLivestock = {"guar", "netch", "kwama_forager", "nix_hound", "silt_strider", "echatere", "durzog",
                "senche_tiger", "indrik", "horker", "mammoth", "camel"};
        List<String> chickenFallbacks = new java.util.ArrayList<>();
        for (String animal : nativeLivestock) {
            if (com.nirncraft.tamriel.settlement.grammar.Outdoors.animalType(animal) == net.minecraft.world.entity.EntityTypes.CHICKEN) {
                chickenFallbacks.add(animal);
            }
        }
        c.check("livestock: native Tamriel livestock resolve to real creature entities (never Chicken fallback)", chickenFallbacks.isEmpty(),
                chickenFallbacks.isEmpty() ? nativeLivestock.length + " species verified" : "chicken fallback: " + chickenFallbacks);

        // 6. All-province Hearthfire homesteads & provincial city homes
        int homesteadPlots = com.nirncraft.homestead.Homesteads.PLOTS.size();
        int registeredHomesteads = 0;
        for (Houses.HouseDef h : Houses.HOUSES) if (h.homestead()) registeredHomesteads++;
        boolean aliasesWork = com.nirncraft.homestead.Homesteads.isPlot("lakeview_manor")
                && Houses.byId("lakeview_manor") != null
                && Houses.byId("windstad_manor") != null
                && Houses.byId("heljarchen_hall") != null;
        c.check("homesteads: all 107+ provincial homestead places are registered in Homesteads and Houses",
                homesteadPlots >= 107 && registeredHomesteads == homesteadPlots && aliasesWork,
                homesteadPlots + " plots, " + registeredHomesteads + " in Houses, aliases=" + aliasesWork);

        String[] homeProvinces = {"skyrim", "cyrodiil", "morrowind", "high_rock", "hammerfell", "summerset", "valenwood", "elsweyr", "black_marsh"};
        List<String> missingHomes = new java.util.ArrayList<>();
        for (String prov : homeProvinces) {
            boolean hasCityHome = false, hasHomestead = false;
            for (Houses.HouseDef h : Houses.HOUSES) {
                if (h.hold().startsWith(prov + "/") || h.hold().equals(prov)) {
                    if (h.homestead()) hasHomestead = true;
                    else hasCityHome = true;
                }
            }
            if (!hasCityHome || !hasHomestead) missingHomes.add(prov + "(city=" + hasCityHome + ",homestead=" + hasHomestead + ")");
        }
        c.check("homes: every mainland province has both purchasable city homes and Hearthfire homestead plots",
                missingHomes.isEmpty(), missingHomes.isEmpty() ? Houses.HOUSES.size() + " total homes/plots across 9 provinces" : String.join(", ", missingHomes));
    }
}
