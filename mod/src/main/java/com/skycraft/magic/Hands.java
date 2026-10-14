package com.skycraft.magic;

import com.skycraft.magic.spell.SpellCasting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * What the hands hold, server side: unequip a hand (its spell, or the weapon in it), go unarmed (fists, no spells) and
 * come back to the previous loadout. Driven by the magic menu buttons and the hands-cycle key.
 */
public final class Hands {
    private static final String SAVED = "hands_saved";

    private Hands() {}

    /** Right hand: clears its spell, or else puts the held item away on an empty hotbar slot. */
    public static void unequipRight(ServerPlayer p) {
        SpellCasting.stop(p, SpellCasting.RIGHT);
        if (!MagicData.selectedSpell(p).isEmpty()) {
            MagicData.clearRightSpell(p);
        } else {
            freeMainHand(p);
        }
    }

    /** Left hand: clears its spell (it no longer falls back to the right-hand spell), or moves the off-hand item away. */
    public static void unequipLeft(ServerPlayer p) {
        SpellCasting.stop(p, SpellCasting.LEFT);
        if (!MagicData.leftEffective(p).isEmpty()) {
            MagicData.setLeftSpell(p, MagicData.NONE);
        } else {
            freeOffHand(p);
        }
    }

    /** Fists: no spells in either hand and nothing held. The loadout is remembered for {@link #restore}. */
    public static void goUnarmed(ServerPlayer p) {
        if (isUnarmed(p)) return;
        CompoundTag saved = new CompoundTag();
        saved.putString("right", MagicData.selectedSpell(p));
        saved.putString("left", MagicData.leftSpell(p));
        saved.putInt("slot", p.getInventory().selected);
        MagicData.tag(p).put(SAVED, saved);
        SpellCasting.stop(p, SpellCasting.RIGHT);
        SpellCasting.stop(p, SpellCasting.LEFT);
        MagicData.clearRightSpell(p);
        MagicData.setLeftSpell(p, MagicData.NONE);
        freeMainHand(p);
        freeOffHand(p);
    }

    /** Back to the loadout from before {@link #goUnarmed} (spells and the hotbar slot). */
    public static void restore(ServerPlayer p) {
        CompoundTag t = MagicData.tag(p);
        if (!t.contains(SAVED)) return;
        CompoundTag saved = t.getCompound(SAVED);
        t.remove(SAVED);
        String right = saved.getString("right");
        String left = saved.getString("left");
        if (!right.isEmpty()) MagicData.setSelectedSpell(p, right);
        MagicData.setLeftSpell(p, left);
        int slot = saved.getInt("slot");
        if (slot >= 0 && slot < 9) selectSlot(p, slot);
    }

    /** The hands-cycle key: fists if armed, otherwise back to what you had. */
    public static void cycle(ServerPlayer p) {
        if (isUnarmed(p)) restore(p);
        else goUnarmed(p);
    }

    public static boolean isUnarmed(ServerPlayer p) {
        return MagicData.selectedSpell(p).isEmpty() && MagicData.leftEffective(p).isEmpty()
                && p.getMainHandItem().isEmpty() && p.getOffhandItem().isEmpty();
    }

    private static void freeMainHand(ServerPlayer p) {
        Inventory inv = p.getInventory();
        if (inv.getItem(inv.selected).isEmpty()) return;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                selectSlot(p, i);
                return;
            }
        }
        // a full hotbar: stow the held stack in the bags instead
        for (int i = 9; i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, inv.items.get(inv.selected));
                inv.items.set(inv.selected, ItemStack.EMPTY);
                return;
            }
        }
        // every slot is taken: the overflow pack holds it (inventory.Pack)
        ItemStack held = inv.items.get(inv.selected);
        inv.items.set(inv.selected, ItemStack.EMPTY);
        com.skycraft.inventory.Pack.giveOrDrop(p, held);
    }

    private static void freeOffHand(ServerPlayer p) {
        ItemStack off = p.getOffhandItem();
        if (off.isEmpty()) return;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, off.copy());
                inv.offhand.set(0, ItemStack.EMPTY);
                return;
            }
        }
        inv.offhand.set(0, ItemStack.EMPTY);
        com.skycraft.inventory.Pack.giveOrDrop(p, off.copy());
    }

    private static void selectSlot(ServerPlayer p, int slot) {
        p.getInventory().selected = slot;
        if (p.connection != null) p.connection.send(new ClientboundSetCarriedItemPacket(slot));
    }
}
