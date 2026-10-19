package com.nirncraft.magic;

import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.magic.bound.BoundWeapons;
import com.nirncraft.magic.spell.Spell;
import com.nirncraft.magic.spell.Spells;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Self-test of the one hand system ({@link Hands}), the bound weapon rules, tome learning, spell favorites and the HUD
 * countdown text, run by {@code /nirncraft magic selftest}. The checks that need a player run on the caller: their
 * inventory and magic data are saved first and put back afterwards (also when a check throws).
 */
final class HandsSelfTest {
    private HandsSelfTest() {}

    /** Returns the problems found; {@code note} receives informational lines. */
    static List<String> run(@Nullable ServerPlayer p, Consumer<String> note) {
        List<String> problems = new ArrayList<>();
        pure(problems);
        if (p == null) {
            note.accept("hands selftest: the player checks need a player (run the command in game)");
            return problems;
        }
        Inventory inv = p.getInventory();
        ListTag savedInv = com.nirncraft.inventory.InventoryData.save(inv);
        int savedSel = inv.getSelectedSlot();
        CompoundTag magic = MagicData.tag(p);
        CompoundTag savedMagic = magic.copy();
        try {
            player(p, problems);
        } catch (RuntimeException e) {
            problems.add("hands selftest threw " + e);
        } finally {
            inv.clearContent();
            com.nirncraft.inventory.InventoryData.load(inv, savedInv);
            inv.setSelectedSlot(savedSel);
            for (String key : new ArrayList<>(magic.keySet())) magic.remove(key);
            magic.merge(savedMagic);
            Hands.forget(p.getUUID());
            SkyData.get(p).markDirty();
            p.inventoryMenu.broadcastChanges();
        }
        return problems;
    }

    // ------------------------------------------------------------------ no player needed

    private static void pure(List<String> problems) {
        // HUD countdown text
        String[][] cases = {{"0", "0s"}, {"42", "42s"}, {"59", "59s"}, {"60", "1:00"}, {"65", "1:05"}, {"125", "2:05"}, {"600", "10:00"}};
        for (String[] c : cases) {
            String got = ActiveMagic.format(Long.parseLong(c[0]));
            if (!c[1].equals(got)) problems.add("countdown text of " + c[0] + " s is '" + got + "', expected '" + c[1] + "'");
        }
        // which items take both hands
        if (Hands.twoHanded(new ItemStack(Items.IRON_SWORD))) problems.add("an iron sword is two-handed");
        if (Hands.twoHanded(new ItemStack(Items.SHIELD))) problems.add("a shield is two-handed");
        if (!Hands.twoHanded(new ItemStack(Items.BOW))) problems.add("a bow is not two-handed");
        if (Hands.twoHanded(new ItemStack(MagicRegistry.BOUND_SWORD.get()))) problems.add("the bound sword is two-handed");
        if (!Hands.twoHanded(new ItemStack(MagicRegistry.BOUND_BATTLEAXE.get()))) problems.add("the bound battleaxe is not two-handed");
        if (!Hands.twoHanded(new ItemStack(MagicRegistry.BOUND_BOW.get()))) problems.add("the bound bow is not two-handed");

        // a bound weapon never reaches a save file: logout purges before the save, login purges what a crash saved
        Inventory bare = new Inventory(null, new net.minecraft.world.entity.EntityEquipment());
        bare.setItem(3, boundStack());
        bare.setItem(4, new ItemStack(Items.IRON_SWORD));
        Inventory crashed = new Inventory(null, new net.minecraft.world.entity.EntityEquipment());
        com.nirncraft.inventory.InventoryData.load(crashed, com.nirncraft.inventory.InventoryData.save(bare)); // an autosave that caught the weapon
        if (!hasBound(crashed)) problems.add("relog test could not build a save holding a bound weapon");
        BoundWeapons.purgeFrom(crashed); // what login does
        if (hasBound(crashed)) problems.add("a bound weapon survived the login purge");
        if (crashed.countItem(Items.IRON_SWORD) != 1) problems.add("the login purge removed a real item");
        BoundWeapons.purgeFrom(bare); // what logout does, before the player is written
        Inventory reloaded = new Inventory(null, new net.minecraft.world.entity.EntityEquipment());
        com.nirncraft.inventory.InventoryData.load(reloaded, com.nirncraft.inventory.InventoryData.save(bare));
        if (hasBound(reloaded)) problems.add("a bound weapon survived logout, save and reload");
        if (reloaded.countItem(Items.IRON_SWORD) != 1) problems.add("logout lost a real item");
    }

    private static ItemStack boundStack() {
        ItemStack s = new ItemStack(MagicRegistry.BOUND_SWORD.get());
        com.nirncraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putLong(BoundWeapons.UNTIL, Long.MAX_VALUE / 2));
        com.nirncraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.store(BoundWeapons.OWNER, net.minecraft.core.UUIDUtil.CODEC, java.util.UUID.randomUUID()));
        return s;
    }

    private static boolean hasBound(Inventory inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) if (BoundWeapons.isBound(inv.getItem(i))) return true;
        return false;
    }

    // ------------------------------------------------------------------ on a real player

    private static void reset(ServerPlayer p) {
        Inventory inv = p.getInventory();
        inv.clearContent();
        inv.setSelectedSlot(0);
        CompoundTag magic = MagicData.tag(p);
        magic.putString("right_spell", "");
        magic.putString("selected_spell", "");
        magic.putString("left_spell", "");
        magic.remove("hands_saved");
        Hands.forget(p.getUUID());
    }

    private static int count(Inventory inv, net.minecraft.world.item.Item item) {
        return inv.countItem(item);
    }

    private static void player(ServerPlayer p, List<String> problems) {
        Inventory inv = p.getInventory();
        Spell boundSword = Spells.byId("bound_sword");
        Spell boundAxe = Spells.byId("bound_battleaxe");
        Spell flames = Spells.byId("flames");
        if (boundSword == null || boundAxe == null || flames == null) {
            problems.add("hands selftest: spells missing");
            return;
        }
        for (Spell s : List.of(boundSword, boundAxe, flames)) MagicData.learn(p, s.id);

        // 1. a sword in hand, conjure the bound sword: the sword is put back, the bound weapon is held
        reset(p);
        inv.setItem(0, new ItemStack(Items.IRON_SWORD));
        Hands.tick(p);
        Spell.Result r = BoundWeapons.sword(p, boundSword, 0);
        if (r == Spell.Result.FAILED) problems.add("conjuring a bound sword failed with a sword in hand");
        if (!BoundWeapons.isBound(p.getMainHandItem())) problems.add("the bound sword is not in the right hand (held " + p.getMainHandItem() + ")");
        if (count(inv, Items.IRON_SWORD) != 1) problems.add("the iron sword was lost when conjuring (count " + count(inv, Items.IRON_SWORD) + ")");
        if (inv.getItem(inv.getSelectedSlot()).is(Items.IRON_SWORD)) problems.add("the iron sword is still in the right hand");
        if (Hands.right(p) != Hands.Held.BOUND) problems.add("right hand reads " + Hands.right(p) + " with a bound sword");
        boolean timer = false;
        for (MagicPackets.Timers.Entry e : ActiveMagic.collect(p)) {
            if (e.ticks() > 0 && e.ticks() <= boundSword.duration && e.name().getString().toLowerCase().contains("bound")) timer = true;
        }
        if (!timer) problems.add("the bound sword has no countdown entry for the HUD");

        // 2. select a real item: the bound weapon vanishes (the hotbar selection path)
        inv.setItem(5, new ItemStack(Items.DIAMOND_SWORD));
        inv.setSelectedSlot(5);
        Hands.tick(p);
        if (hasBound(inv)) problems.add("the bound sword stayed after a real item was selected");
        if (Hands.right(p) != Hands.Held.ITEM) problems.add("right hand reads " + Hands.right(p) + " holding a diamond sword");
        // the inventory screen path (explicit equip) behaves the same
        reset(p);
        BoundWeapons.sword(p, boundSword, 0);
        if (!hasBound(inv)) problems.add("settle test: no bound weapon to start with");
        inv.setItem(3, new ItemStack(Items.IRON_AXE));
        inv.setSelectedSlot(3);
        Hands.settle(p);
        if (hasBound(inv)) problems.add("settle(): a bound weapon stayed beside a real item in the hand");

        // 3. spell versus bound: they never coexist in the right hand
        reset(p);
        if (!Hands.equipRightSpell(p, "bound_sword")) problems.add("equipRightSpell refused a known spell");
        if (Hands.right(p) != Hands.Held.SPELL) problems.add("right hand reads " + Hands.right(p) + " with a spell equipped");
        BoundWeapons.sword(p, boundSword, 0);
        if (Hands.right(p) != Hands.Held.BOUND) problems.add("conjuring from the equipped spell left right hand " + Hands.right(p));
        if (!MagicData.selectedSpell(p).isEmpty()) problems.add("the spell stayed selected beside the bound weapon: " + MagicData.selectedSpell(p));
        Hands.equipRightSpell(p, "flames");
        if (hasBound(inv)) problems.add("equipping a spell did not dismiss the bound weapon");
        if (!"flames".equals(MagicData.selectedSpell(p)) || !p.getMainHandItem().isEmpty()) problems.add("equipping a spell left the hand inconsistent");
        // the spell it displaced comes back when the weapon runs out while held
        BoundWeapons.sword(p, boundSword, 0);
        ItemStack held = p.getMainHandItem();
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(held) == null ? new CompoundTag() : com.nirncraft.inventory.ItemData.custom(held).copy();
        if (!"flames".equals(tag.getStringOr(BoundWeapons.FROM_RIGHT, ""))) problems.add("the bound sword did not remember the spell it replaced");
        inv.setItem(inv.getSelectedSlot(), ItemStack.EMPTY);
        Hands.restoreAfterBound(p, tag);
        if (!"flames".equals(MagicData.selectedSpell(p))) problems.add("the replaced spell did not come back after the bound weapon ran out");

        // 4. an item that lands in the empty hand: chosen items replace the spell, picked-up ones are moved out of it
        reset(p);
        Hands.equipRightSpell(p, "flames");
        Hands.tick(p);
        inv.setItem(inv.getSelectedSlot(), new ItemStack(Items.COBBLESTONE)); // no pickup: e.g. dragged in on purpose
        Hands.tick(p);
        if (!MagicData.selectedSpell(p).isEmpty()) problems.add("an item chosen into the hand did not replace the spell");
        reset(p);
        Hands.equipRightSpell(p, "flames");
        Hands.tick(p);
        Hands.onPickup(p);
        inv.setItem(inv.getSelectedSlot(), new ItemStack(Items.COBBLESTONE)); // picked up into the selected slot
        Hands.tick(p);
        if (!"flames".equals(MagicData.selectedSpell(p))) problems.add("picking an item up bumped the spell out of the hand");
        if (!inv.getItem(inv.getSelectedSlot()).isEmpty() || count(inv, Items.COBBLESTONE) != 1) problems.add("the picked-up item was not moved out of the spell hand");

        // 5. left hand: a left spell stows the off-hand item; a two-handed bound weapon takes both hands
        reset(p);
        inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, new ItemStack(Items.SHIELD));
        if (!Hands.equipLeftSpell(p, "flames")) problems.add("equipLeftSpell failed with a shield in the off hand");
        if (!p.getOffhandItem().isEmpty() || count(inv, Items.SHIELD) != 1) problems.add("the shield was not put away for the left spell");
        if (Hands.left(p) != Hands.Held.SPELL) problems.add("left hand reads " + Hands.left(p) + " with a left spell");
        BoundWeapons.battleaxe(p, boundAxe, 0);
        if (Hands.left(p) == Hands.Held.SPELL || !MagicData.NONE.equals(MagicData.leftSpell(p))) problems.add("a bound battleaxe left the left spell equipped");
        reset(p);
        Hands.equipLeftSpell(p, "flames");
        Hands.tick(p);
        inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, new ItemStack(Items.SHIELD));
        Hands.tick(p);
        if (Hands.left(p) != Hands.Held.ITEM || MagicData.leftSpell(p).equals("flames")) problems.add("a shield in the off hand did not replace the left spell");

        // 6. learning a tome learns only: the spell does not go into a hand
        reset(p);
        String tomeSpell = "candlelight";
        MagicData.forget(p, tomeSpell);
        if (MagicRegistry.TOMES.containsKey(tomeSpell)) {
            inv.setItem(0, new ItemStack(MagicRegistry.TOMES.get(tomeSpell).get()));
            inv.setSelectedSlot(0);
            inv.getItem(0).getItem().use(p.level(), p, InteractionHand.MAIN_HAND);
            if (!MagicData.knows(p, tomeSpell)) problems.add("reading the tome did not learn the spell");
            if (!MagicData.selectedSpell(p).isEmpty() || !MagicData.leftSpell(p).isEmpty()) {
                problems.add("reading the tome put the spell in a hand (right '" + MagicData.selectedSpell(p) + "', left '" + MagicData.leftSpell(p) + "')");
            }
        } else {
            problems.add("no tome for " + tomeSpell);
        }
        // learning by any other route (spellmaking, commands) equips nothing either
        reset(p);
        MagicData.forget(p, tomeSpell);
        MagicData.learn(p, tomeSpell);
        if (!MagicData.selectedSpell(p).isEmpty() || !MagicData.leftSpell(p).isEmpty()) problems.add("MagicData.learn equipped the spell");

        // 7. favorites persist in the player data (the same save() / load() as the capability uses)
        reset(p);
        if (MagicData.isFavorite(p, "flames")) MagicData.toggleFavorite(p, "flames");
        if (!MagicData.canFavorite(p, "flames")) problems.add("a known spell cannot be favorited");
        MagicData.toggleFavorite(p, "flames");
        PlayerData copy = new PlayerData();
        copy.load(SkyData.get(p).save());
        if (!favoriteIn(copy, "flames")) problems.add("a favorited spell did not survive save and load");
        MagicData.toggleFavorite(p, "flames");
        copy = new PlayerData();
        copy.load(SkyData.get(p).save());
        if (favoriteIn(copy, "flames")) problems.add("an unfavorited spell was still saved as a favorite");

        // 8. the real logout path on the live player: nothing bound is left to be written
        reset(p);
        BoundWeapons.sword(p, boundSword, 0);
        BoundWeapons.purgeAll(p);
        Inventory written = new Inventory(null, new net.minecraft.world.entity.EntityEquipment());
        com.nirncraft.inventory.InventoryData.load(written, com.nirncraft.inventory.InventoryData.save(inv));
        if (hasBound(written) || hasBound(inv)) problems.add("a bound weapon was still in the inventory after the logout purge");
    }

    private static boolean favoriteIn(PlayerData data, String id) {
        ListTag list = data.module(MagicData.MODULE).getListOrEmpty("favorites");
        for (int i = 0; i < list.size(); i++) if (list.getStringOr(i, "").equals(id)) return true;
        return false;
    }
}
