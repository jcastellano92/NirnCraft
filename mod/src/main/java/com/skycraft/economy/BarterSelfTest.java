package com.skycraft.economy;

import com.skycraft.core.Currency;
import com.skycraft.core.SkyData;
import com.skycraft.inventory.Pack;
import com.skycraft.inventory.PackSelfTest;
import com.skycraft.quest.QuestItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Selling, part of the inventory self-test ({@code com.skycraft.inventory.PackSelfTest}): a real merchant entity and the
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
        Villager npc = EntityType.VILLAGER.create(level);
        if (npc == null) {
            c.check("barter: a test merchant", false, "could not create a villager");
            return;
        }
        npc.setVillagerData(npc.getVillagerData().setProfession(VillagerProfession.CARTOGRAPHER).setLevel(2));
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
        ResourceLocation sellId = BuiltInRegistries.ITEM.getKey(sellItem);

        // ---- quest items: filtered out of the list and refused server-side (slot and pack)
        ItemStack letter = QuestItems.letter("whiterun");
        ItemStack key = QuestItems.cottageKey(p.getUUID());
        ItemStack relic = new ItemStack(QuestItems.QUEST_RELIC.get());
        relic.getOrCreateTag().putString(QuestItems.RELIC_TAG, "selftest");
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
        inv.setItem(inv.selected, new ItemStack(sellItem, 1));
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
        c.check("barter: the held item is its own marked row", heldRow != null && heldRow.equip() == 1 && heldRow.index() == inv.selected,
                heldRow == null ? "no row" : "equip " + heldRow.equip());
        c.check("barter: the unequipped copies are one row (slots and pack together)",
                freeRow != null && freeRow.equip() == 0 && freeRow.stack().getCount() == unequipped, freeRow == null ? "no row" : "count " + freeRow.stack().getCount());
        String confirm = key(Barter.sell(p, shop, inv.selected, false, sellId, false));
        c.check("barter: selling an equipped item without confirmation is refused", confirm.endsWith("confirm_equipped")
                && !inv.getItem(inv.selected).isEmpty(), confirm);
        long gold0 = Currency.balance(p);
        String rowSale = key(Barter.sell(p, shop, freeRow == null ? 10 : freeRow.index(), true, sellId, false));
        c.check("barter: 'sell all' on the unequipped row sells only the unequipped items",
                rowSale.endsWith("sold") && !inv.getItem(inv.selected).isEmpty() && inv.getItem(10).isEmpty() && inv.getItem(11).isEmpty()
                        && Pack.count(p, sellItem) == 0, rowSale + ", held kept: " + !inv.getItem(inv.selected).isEmpty());
        long goldAfterSale = Currency.balance(p);

        // ---- buy back: everything just sold, at the price it was paid
        List<Buyback.Entry> back = Buyback.list(p, npc);
        c.check("barter: the sale is on the buy-back list", back.size() == 1 && back.get(0).count == unequipped,
                back.size() + " entries" + (back.isEmpty() ? "" : ", count " + back.get(0).count));
        String bb = key(Barter.buyback(p, shop, 0, true, sellId));
        c.check("barter: buy back returns the items for the sale price", bb.endsWith("bought_back") && count(p, sellItem) == 1 + unequipped
                && Currency.balance(p) == gold0, bb + ", items " + count(p, sellItem) + ", gold " + Currency.balance(p) + " (was " + gold0
                + ", after the sale " + goldAfterSale + ")");

        // the equipped item itself: confirmed sale sells one, then it can be bought back too
        String eq = key(Barter.sell(p, shop, inv.selected, true, sellId, true));
        c.check("barter: a confirmed sale of the equipped item sells exactly that one", eq.endsWith("sold") && inv.getItem(inv.selected).isEmpty()
                && count(p, sellItem) == unequipped, eq + ", items left " + count(p, sellItem));
        String eb = key(Barter.buyback(p, shop, 0, false, sellId));
        c.check("barter: the equipped item can be bought back", eb.endsWith("bought_back") && count(p, sellItem) == unequipped + 1, eb);
        inv.clearContent();
        Pack.clear(p);
        Buyback.forget(p.getUUID());
    }
}
