package com.skycraft.magic;

import com.skycraft.combat.WeaponClass;
import com.skycraft.core.Notifier;
import com.skycraft.magic.bound.BoundBattleaxeItem;
import com.skycraft.magic.bound.BoundBowItem;
import com.skycraft.magic.bound.BoundWeapons;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.SpellCasting;
import com.skycraft.magic.spell.Spells;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the hands hold: ONE system for spells, bound weapons and real items (server side, authoritative).
 *
 * <ul>
 *     <li>The right hand holds exactly one of {spell, bound weapon, real item, empty}; the left hand one of {spell,
 *     off-hand item, empty}. Two-handed things ({@link #twoHanded}: two-handed weapons, bows, staves, the bound
 *     battleaxe and bow) occupy both.</li>
 *     <li>Equipping any of them replaces what the hand had. The entry points are {@link #equipRightSpell},
 *     {@link #equipLeftSpell}, {@link #equipBound} (conjuring) and {@link #settle} (any item that lands in a hand:
 *     the inventory screen, the favorites wheel and keys, the hotbar keys and scroll wheel, pickups).</li>
 *     <li>A bound weapon is a spell effect with a timer: it is only ever held, and selecting anything else
 *     dismisses it (no refund). When it runs out the spells it displaced come back.</li>
 * </ul>
 */
public final class Hands {
    private static final String SAVED = "hands_saved";

    /** What a hand holds right now, see {@link #right} and {@link #left}. */
    public enum Held { EMPTY, SPELL, BOUND, ITEM }

    /** Hotbar slot each player had selected last tick: tells a selection change from an item appearing in the slot. */
    private static final Map<UUID, Integer> LAST_SELECTED = new HashMap<>();
    /** Game time of each player's last item pickup, so a picked-up stack never bumps a spell out of the hand. */
    private static final Map<UUID, Long> PICKUP = new HashMap<>();

    private Hands() {}

    // ------------------------------------------------------------------ queries (client and server)

    /** Whether {@code stack} takes both hands. */
    public static boolean twoHanded(ItemStack stack) {
        if (stack.isEmpty()) return false;
        net.minecraft.world.item.Item it = stack.getItem();
        if (it instanceof BoundBattleaxeItem || it instanceof BoundBowItem) return true;
        if (it instanceof com.skycraft.arsenal.item.StaffItem || it instanceof com.skycraft.arsenal.item.EnchantedStaffItem) return true;
        WeaponClass wc = WeaponClass.of(stack);
        return wc.twoHanded() || wc == WeaponClass.BOW;
    }

    /** The right hand: the held item wins over a (stale) spell id, so every reader agrees. */
    public static Held right(Player p) {
        ItemStack main = p.getMainHandItem();
        if (!main.isEmpty()) return BoundWeapons.isBound(main) ? Held.BOUND : Held.ITEM;
        return MagicData.selectedSpell(p).isEmpty() ? Held.EMPTY : Held.SPELL;
    }

    /** The left hand; a two-handed item in the right hand occupies it. Only an explicitly equipped spell counts. */
    public static Held left(Player p) {
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) return BoundWeapons.isBound(off) ? Held.BOUND : Held.ITEM;
        if (twoHanded(p.getMainHandItem())) return Held.ITEM;
        String l = MagicData.leftSpell(p);
        return l.isEmpty() || MagicData.NONE.equals(l) ? Held.EMPTY : Held.SPELL;
    }

    // ------------------------------------------------------------------ equipping spells

    /** Equips a spell in the right hand: the held item is put away (a bound weapon is dismissed). */
    public static boolean equipRightSpell(ServerPlayer p, String spellId) {
        if (!mayEquip(p, spellId)) return false;
        SpellCasting.stop(p, SpellCasting.RIGHT);
        if (!makeRoomInMainHand(p)) {
            Notifier.message(p, Component.translatable("message.skycraft.inventory_full"));
            return false;
        }
        MagicData.setSelectedSpell(p, spellId);
        return true;
    }

    /** Equips a spell in the left hand: its off-hand item and any two-handed item are put away. */
    public static boolean equipLeftSpell(ServerPlayer p, String spellId) {
        if (!mayEquip(p, spellId)) return false;
        SpellCasting.stop(p, SpellCasting.LEFT);
        if (twoHanded(p.getMainHandItem()) && !makeRoomInMainHand(p)) {
            Notifier.message(p, Component.translatable("message.skycraft.inventory_full"));
            return false;
        }
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) {
            if (BoundWeapons.isBound(off)) {
                p.getInventory().offhand.set(0, ItemStack.EMPTY);
            } else if (!stow(p, off)) {
                Notifier.message(p, Component.translatable("message.skycraft.inventory_full"));
                return false;
            } else {
                p.getInventory().offhand.set(0, ItemStack.EMPTY);
            }
        }
        MagicData.setLeftSpell(p, spellId);
        return true;
    }

    private static boolean mayEquip(ServerPlayer p, String spellId) {
        return Spells.byId(spellId) != null && (MagicData.knows(p, spellId) || p.isCreative());
    }

    // ------------------------------------------------------------------ bound weapons

    /**
     * Conjures a bound weapon into the right hand. A real item there is put back (its own hotbar slot is kept; the
     * bound weapon takes a free hotbar slot, else the item goes to the bags); the right-hand spell is replaced, and a
     * two-handed bound weapon also clears the left hand. The spells it displaced are remembered on the item and come
     * back when it times out. Returns false (nothing changed) when there is no room.
     */
    public static boolean equipBound(ServerPlayer p, ItemStack bound) {
        Inventory inv = p.getInventory();
        // what the weapon displaces (a re-cast keeps what the previous copy had displaced)
        String right = MagicData.selectedSpell(p);
        String left = MagicData.leftSpell(p);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!BoundWeapons.isBound(s) || s.getTag() == null) continue;
            if (right.isEmpty()) right = s.getTag().getString(BoundWeapons.FROM_RIGHT);
            if (left.isEmpty() || MagicData.NONE.equals(left)) left = s.getTag().getString(BoundWeapons.FROM_LEFT);
        }
        ItemStack[] removed = new ItemStack[inv.getContainerSize()];
        boolean any = false;
        for (int i = 0; i < removed.length; i++) {
            ItemStack s = inv.getItem(i);
            if (BoundWeapons.isBound(s)) {
                removed[i] = s;
                inv.setItem(i, ItemStack.EMPTY);
                any = true;
            }
        }
        int sel = inv.selected;
        int target = sel;
        ItemStack main = inv.getItem(sel);
        if (!main.isEmpty()) {
            int hot = emptyHotbar(inv, sel);
            if (hot >= 0) {
                target = hot;
            } else {
                int bag = emptyBag(inv);
                if (bag < 0) {
                    if (any) {
                        for (int i = 0; i < removed.length; i++) if (removed[i] != null) inv.setItem(i, removed[i]);
                    }
                    return false;
                }
                inv.setItem(bag, main);
                inv.setItem(sel, ItemStack.EMPTY);
            }
        }
        CompoundTag tag = bound.getOrCreateTag();
        if (!right.isEmpty() && !MagicData.NONE.equals(right)) tag.putString(BoundWeapons.FROM_RIGHT, right);
        if (!left.isEmpty() && !MagicData.NONE.equals(left)) tag.putString(BoundWeapons.FROM_LEFT, left);
        inv.setItem(target, bound);
        if (target != sel) selectSlot(p, target);
        SpellCasting.stop(p, SpellCasting.RIGHT);
        MagicData.clearRightSpell(p);
        if (twoHanded(bound)) {
            SpellCasting.stop(p, SpellCasting.LEFT);
            MagicData.setLeftSpell(p, MagicData.NONE);
            ItemStack off = p.getOffhandItem();
            if (!off.isEmpty() && stow(p, off)) inv.offhand.set(0, ItemStack.EMPTY);
        }
        LAST_SELECTED.put(p.getUUID(), inv.selected);
        return true;
    }

    /** The bound weapon ran out while held: the spells it replaced return to the hands that are still free. */
    public static void restoreAfterBound(ServerPlayer p, CompoundTag boundTag) {
        String right = boundTag.getString(BoundWeapons.FROM_RIGHT);
        if (!right.isEmpty() && mayEquip(p, right) && p.getMainHandItem().isEmpty() && MagicData.selectedSpell(p).isEmpty()) {
            MagicData.setSelectedSpell(p, right);
        }
        String left = boundTag.getString(BoundWeapons.FROM_LEFT);
        String now = MagicData.leftSpell(p);
        if (!left.isEmpty() && mayEquip(p, left) && p.getOffhandItem().isEmpty() && !twoHanded(p.getMainHandItem())
                && (now.isEmpty() || MagicData.NONE.equals(now))) {
            MagicData.setLeftSpell(p, left);
        }
    }

    /** Makes every bound weapon vanish (no refund). Returns how many were removed. */
    public static int dismissBound(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (BoundWeapons.isBound(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
                n++;
            }
        }
        if (n > 0) {
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.ILLUSIONER_MIRROR_MOVE,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.7f);
        }
        return n;
    }

    // ------------------------------------------------------------------ keeping both systems consistent

    /** Called every tick for every server player (and right after inventory actions): see {@link #settle}. */
    public static void tick(ServerPlayer p) {
        settle(p, false);
        dismissStray(p);
    }

    /** An item was put in a hand on purpose (inventory screen, favorites): it replaces the spell. */
    public static void settle(ServerPlayer p) {
        settle(p, true);
        dismissStray(p);
    }

    /**
     * Resolves a hand that holds both an item and a spell. The item wins when it was chosen (hotbar selection, inventory
     * screen); a stack that merely landed in the empty selected slot (a pickup) is moved out of the hand instead so the
     * spell stays. A two-handed item or an off-hand item clears the left spell.
     */
    private static void settle(ServerPlayer p, boolean explicit) {
        Inventory inv = p.getInventory();
        int sel = inv.selected;
        ItemStack main = inv.getItem(sel);
        ItemStack off = inv.offhand.get(0);
        Integer last = LAST_SELECTED.put(p.getUUID(), sel);
        boolean twoHanded = twoHanded(main);
        if (!main.isEmpty() && !MagicData.selectedSpell(p).isEmpty()) {
            boolean pickedUp = !explicit && last != null && last == sel && !BoundWeapons.isBound(main) && pickedUpRecently(p);
            if (pickedUp && moveOutOfHand(p, sel)) {
                twoHanded = false;
            } else {
                SpellCasting.stop(p, SpellCasting.RIGHT);
                MagicData.clearRightSpell(p);
            }
        }
        String left = MagicData.leftSpell(p);
        if (!left.isEmpty() && !MagicData.NONE.equals(left) && (!off.isEmpty() || twoHanded)) {
            SpellCasting.stop(p, SpellCasting.LEFT);
            MagicData.setLeftSpell(p, MagicData.NONE);
        }
    }

    /** Bound weapons only exist in a hand: one in any other slot (the player selected something else) vanishes. */
    private static void dismissStray(ServerPlayer p) {
        Inventory inv = p.getInventory();
        boolean stray = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == inv.selected || i == Inventory.SLOT_OFFHAND) continue;
            if (BoundWeapons.isBound(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
                stray = true;
            }
        }
        if (stray) {
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.ILLUSIONER_MIRROR_MOVE,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.7f);
        }
    }

    public static void onPickup(ServerPlayer p) {
        PICKUP.put(p.getUUID(), p.level().getGameTime());
    }

    private static boolean pickedUpRecently(ServerPlayer p) {
        Long t = PICKUP.get(p.getUUID());
        return t != null && p.level().getGameTime() - t <= 2;
    }

    public static void forget(UUID id) {
        LAST_SELECTED.remove(id);
        PICKUP.remove(id);
    }

    public static void clear() {
        LAST_SELECTED.clear();
        PICKUP.clear();
    }

    // ------------------------------------------------------------------ unequip / unarmed

    /** Right hand: clears its spell, or else puts the held item away on an empty hotbar slot (a bound weapon vanishes). */
    public static void unequipRight(ServerPlayer p) {
        SpellCasting.stop(p, SpellCasting.RIGHT);
        if (!MagicData.selectedSpell(p).isEmpty() && p.getMainHandItem().isEmpty()) {
            MagicData.clearRightSpell(p);
        } else {
            makeRoomInMainHand(p);
        }
    }

    /** Left hand: clears its spell (it no longer falls back to the right-hand spell), or moves the off-hand item away. */
    public static void unequipLeft(ServerPlayer p) {
        SpellCasting.stop(p, SpellCasting.LEFT);
        if (!MagicData.leftEffective(p).isEmpty() && p.getOffhandItem().isEmpty()) {
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
        makeRoomInMainHand(p);
        freeOffHand(p);
    }

    /** Back to the loadout from before {@link #goUnarmed}: the right spell, else the hotbar slot; the left spell. */
    public static void restore(ServerPlayer p) {
        CompoundTag t = MagicData.tag(p);
        if (!t.contains(SAVED)) return;
        CompoundTag saved = t.getCompound(SAVED);
        t.remove(SAVED);
        String right = saved.getString("right");
        String left = saved.getString("left");
        int slot = saved.getInt("slot");
        if (!right.isEmpty()) equipRightSpell(p, right);
        else if (slot >= 0 && slot < 9) selectSlot(p, slot);
        if (left.isEmpty() || MagicData.NONE.equals(left)) MagicData.setLeftSpell(p, left);
        else equipLeftSpell(p, left);
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

    // ------------------------------------------------------------------ slot moves

    /**
     * Leaves the main hand empty: a bound weapon vanishes, a real item moves to an empty hotbar slot (which gets
     * selected, so the item keeps its place) or to the bags. False when there is nowhere to put it.
     */
    private static boolean makeRoomInMainHand(ServerPlayer p) {
        Inventory inv = p.getInventory();
        ItemStack held = inv.getItem(inv.selected);
        if (held.isEmpty()) return true;
        if (BoundWeapons.isBound(held)) {
            inv.setItem(inv.selected, ItemStack.EMPTY);
            return true;
        }
        int hot = emptyHotbar(inv, inv.selected);
        if (hot >= 0) {
            selectSlot(p, hot);
            return true;
        }
        int bag = emptyBag(inv);
        inv.setItem(inv.selected, ItemStack.EMPTY);
        if (bag < 0) com.skycraft.inventory.Pack.giveOrDrop(p, held); // every slot is taken: the overflow pack
        else inv.setItem(bag, held);
        return true;
    }

    /** Moves the stack in {@code slot} to the bags (else another hotbar slot) without changing the selection. */
    private static boolean moveOutOfHand(ServerPlayer p, int slot) {
        Inventory inv = p.getInventory();
        int to = emptyBag(inv);
        if (to < 0) to = emptyHotbar(inv, slot);
        if (to < 0) return false;
        inv.setItem(to, inv.getItem(slot));
        inv.setItem(slot, ItemStack.EMPTY);
        return true;
    }

    /** Puts a stack in any free slot other than the selected one; false when the inventory is full. */
    private static boolean stow(ServerPlayer p, ItemStack stack) {
        Inventory inv = p.getInventory();
        int to = emptyBag(inv);
        if (to < 0) to = emptyHotbar(inv, inv.selected);
        if (to < 0) {
            // every slot is taken: the overflow pack holds it (inventory.Pack)
            com.skycraft.inventory.Pack.giveOrDrop(p, stack.copy());
            return true;
        }
        inv.setItem(to, stack.copy());
        return true;
    }

    private static void freeOffHand(ServerPlayer p) {
        ItemStack off = p.getOffhandItem();
        if (off.isEmpty()) return;
        if (BoundWeapons.isBound(off)) {
            p.getInventory().offhand.set(0, ItemStack.EMPTY);
        } else if (stow(p, off)) {
            p.getInventory().offhand.set(0, ItemStack.EMPTY);
        }
    }

    private static int emptyHotbar(Inventory inv, int except) {
        for (int i = 0; i < 9; i++) if (i != except && inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    private static int emptyBag(Inventory inv) {
        for (int i = 9; i < inv.items.size(); i++) if (inv.items.get(i).isEmpty()) return i;
        return -1;
    }

    private static void selectSlot(ServerPlayer p, int slot) {
        p.getInventory().selected = slot;
        if (p.connection != null) p.connection.send(new ClientboundSetCarriedItemPacket(slot));
    }

    /** The spell a player holds in a hand, or null (for the HUD and the self-test). */
    public static Spell spellIn(Player p, boolean left) {
        if (left ? left(p) != Held.SPELL : right(p) != Held.SPELL) return null;
        return Spells.byId(left ? MagicData.leftSpell(p) : MagicData.selectedSpell(p));
    }
}
