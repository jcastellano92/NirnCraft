package com.nirncraft.magic;

import com.nirncraft.combat.WeaponClass;
import com.nirncraft.core.Notifier;
import com.nirncraft.magic.bound.BoundBattleaxeItem;
import com.nirncraft.magic.bound.BoundBowItem;
import com.nirncraft.magic.bound.BoundWeapons;
import com.nirncraft.magic.spell.Spell;
import com.nirncraft.magic.spell.SpellCasting;
import com.nirncraft.magic.spell.Spells;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
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
    public static final String FROM_RIGHT_ITEM = "prev_right_item";
    public static final String FROM_LEFT_ITEM = "prev_left_item";

    /** What a hand holds right now, see {@link #right} and {@link #left}. */
    public enum Held { EMPTY, SPELL, BOUND, ITEM }

    /** Hotbar slot each player had selected last tick: tells a selection change from an item appearing in the slot. */
    private static final Map<UUID, Integer> LAST_SELECTED = new HashMap<>();
    /** Game time of each player's last item pickup, so a picked-up stack never bumps a spell out of the hand. */
    private static final Map<UUID, Long> PICKUP = new HashMap<>();

    private Hands() {}

    private static CompoundTag keyOf(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return com.nirncraft.inventory.ItemData.save(copy);
    }

    // ------------------------------------------------------------------ queries (client and server)

    /** Whether {@code stack} takes both hands. */
    public static boolean twoHanded(ItemStack stack) {
        if (stack.isEmpty()) return false;
        net.minecraft.world.item.Item it = stack.getItem();
        if (it instanceof BoundBattleaxeItem || it instanceof BoundBowItem) return true;
        if (it instanceof com.nirncraft.arsenal.item.StaffItem || it instanceof com.nirncraft.arsenal.item.EnchantedStaffItem) return true;
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
        ItemStack held = p.getMainHandItem();
        if (!held.isEmpty() && !BoundWeapons.isBound(held)) {
            MagicData.tag(p).put("prev_right_item", keyOf(held));
        }
        if (!makeRoomInMainHand(p)) {
            Notifier.message(p, Component.translatable("message.nirncraft.inventory_full"));
            return false;
        }
        MagicData.setSelectedSpell(p, spellId);
        if (MagicData.leftSpell(p).isEmpty()) MagicData.setLeftSpell(p, MagicData.NONE);
        return true;
    }

    /** Equips a spell in the left hand: its off-hand item and any two-handed item are put away. */
    public static boolean equipLeftSpell(ServerPlayer p, String spellId) {
        if (!mayEquip(p, spellId)) return false;
        SpellCasting.stop(p, SpellCasting.LEFT);
        if (twoHanded(p.getMainHandItem())) {
            ItemStack held = p.getMainHandItem();
            if (!BoundWeapons.isBound(held)) {
                MagicData.tag(p).put("prev_right_item", keyOf(held));
            }
            if (!makeRoomInMainHand(p)) {
                Notifier.message(p, Component.translatable("message.nirncraft.inventory_full"));
                return false;
            }
        }
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) {
            if (BoundWeapons.isBound(off)) {
                p.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            } else {
                MagicData.tag(p).put("prev_left_item", keyOf(off));
                if (!stow(p, off)) {
                    Notifier.message(p, Component.translatable("message.nirncraft.inventory_full"));
                    return false;
                }
                p.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            }
        }
        MagicData.setLeftSpell(p, spellId);
        return true;
    }

    private static boolean mayEquip(ServerPlayer p, String spellId) {
        return Spells.byId(spellId) != null && (MagicData.knows(p, spellId) || p.isCreative());
    }

    // ------------------------------------------------------------------ bound weapons

    public static boolean equipBound(ServerPlayer p, ItemStack bound) {
        return equipBound(p, bound, false);
    }

    /**
     * Conjures a bound weapon into the casting hand (right hand, or left hand when {@code leftHand} is true for a
     * one-handed bound weapon). Remembers both the spell and any previous weapon/shield that hand held so they return
     * when the bound weapon's timer expires.
     */
    public static boolean equipBound(ServerPlayer p, ItemStack bound, boolean leftHand) {
        Inventory inv = p.getInventory();
        CompoundTag mtag = MagicData.tag(p);
        if (leftHand && !twoHanded(bound)) {
            String left = MagicData.leftSpell(p);
            CompoundTag prevLeftItem = com.nirncraft.platform.NbtData.hasType(mtag, "prev_left_item", net.minecraft.nbt.Tag.TAG_COMPOUND)
                    ? mtag.getCompoundOrEmpty("prev_left_item").copy() : null;
            ItemStack curOff = p.getOffhandItem();
            if (BoundWeapons.isBound(curOff) && com.nirncraft.inventory.ItemData.custom(curOff) != null) {
                if (left.isEmpty() || MagicData.NONE.equals(left)) left = com.nirncraft.inventory.ItemData.custom(curOff).getStringOr(BoundWeapons.FROM_LEFT, "");
                if (prevLeftItem == null && com.nirncraft.platform.NbtData.hasType(com.nirncraft.inventory.ItemData.custom(curOff), BoundWeapons.FROM_LEFT_ITEM, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    prevLeftItem = com.nirncraft.inventory.ItemData.custom(curOff).getCompoundOrEmpty(BoundWeapons.FROM_LEFT_ITEM).copy();
                }
                inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            } else if (!curOff.isEmpty()) {
                prevLeftItem = keyOf(curOff);
                if (!stow(p, curOff)) return false;
                inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            }
            if (twoHanded(p.getMainHandItem()) && !makeRoomInMainHand(p)) return false;
            CompoundTag tag = com.nirncraft.inventory.ItemData.customOrEmpty(bound);
            tag.putBoolean(BoundWeapons.BOUND_LEFT, true);
            if (!left.isEmpty() && !MagicData.NONE.equals(left)) tag.putString(BoundWeapons.FROM_LEFT, left);
            if (prevLeftItem != null && !prevLeftItem.isEmpty()) tag.put(BoundWeapons.FROM_LEFT_ITEM, prevLeftItem);
            com.nirncraft.inventory.ItemData.setCustom(bound, tag);
            inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, bound);
            SpellCasting.stop(p, SpellCasting.LEFT);
            MagicData.setLeftSpell(p, MagicData.NONE);
            return true;
        }

        // Right-hand (or two-handed) bound weapon
        String right = MagicData.selectedSpell(p);
        String left = MagicData.leftSpell(p);
        CompoundTag prevRightItem = com.nirncraft.platform.NbtData.hasType(mtag, "prev_right_item", net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? mtag.getCompoundOrEmpty("prev_right_item").copy() : null;
        CompoundTag prevLeftItem = com.nirncraft.platform.NbtData.hasType(mtag, "prev_left_item", net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? mtag.getCompoundOrEmpty("prev_left_item").copy() : null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == Inventory.SLOT_OFFHAND && !twoHanded(bound)) continue;
            ItemStack s = inv.getItem(i);
            if (!BoundWeapons.isBound(s) || com.nirncraft.inventory.ItemData.custom(s) == null) continue;
            if (right.isEmpty()) right = com.nirncraft.inventory.ItemData.custom(s).getStringOr(BoundWeapons.FROM_RIGHT, "");
            if (left.isEmpty() || MagicData.NONE.equals(left)) left = com.nirncraft.inventory.ItemData.custom(s).getStringOr(BoundWeapons.FROM_LEFT, "");
            if (prevRightItem == null && com.nirncraft.platform.NbtData.hasType(com.nirncraft.inventory.ItemData.custom(s), BoundWeapons.FROM_RIGHT_ITEM, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                prevRightItem = com.nirncraft.inventory.ItemData.custom(s).getCompoundOrEmpty(BoundWeapons.FROM_RIGHT_ITEM).copy();
            }
            if (prevLeftItem == null && com.nirncraft.platform.NbtData.hasType(com.nirncraft.inventory.ItemData.custom(s), BoundWeapons.FROM_LEFT_ITEM, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                prevLeftItem = com.nirncraft.inventory.ItemData.custom(s).getCompoundOrEmpty(BoundWeapons.FROM_LEFT_ITEM).copy();
            }
        }
        ItemStack[] removed = new ItemStack[inv.getContainerSize()];
        boolean any = false;
        for (int i = 0; i < removed.length; i++) {
            if (i == Inventory.SLOT_OFFHAND && !twoHanded(bound)) continue;
            ItemStack s = inv.getItem(i);
            if (BoundWeapons.isBound(s)) {
                removed[i] = s;
                inv.setItem(i, ItemStack.EMPTY);
                any = true;
            }
        }
        int sel = inv.getSelectedSlot();
        int target = sel;
        ItemStack main = inv.getItem(sel);
        if (!main.isEmpty()) {
            prevRightItem = keyOf(main);
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
        CompoundTag tag = com.nirncraft.inventory.ItemData.customOrEmpty(bound);
        if (!right.isEmpty() && !MagicData.NONE.equals(right)) tag.putString(BoundWeapons.FROM_RIGHT, right);
        if (!left.isEmpty() && !MagicData.NONE.equals(left)) tag.putString(BoundWeapons.FROM_LEFT, left);
        if (prevRightItem != null && !prevRightItem.isEmpty()) tag.put(BoundWeapons.FROM_RIGHT_ITEM, prevRightItem);
        inv.setItem(target, bound);
        if (target != sel) selectSlot(p, target);
        SpellCasting.stop(p, SpellCasting.RIGHT);
        MagicData.clearRightSpell(p);
        if (twoHanded(bound)) {
            SpellCasting.stop(p, SpellCasting.LEFT);
            MagicData.setLeftSpell(p, MagicData.NONE);
            ItemStack off = p.getOffhandItem();
            if (!off.isEmpty()) {
                if (BoundWeapons.isBound(off)) {
                    inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
                } else {
                    prevLeftItem = keyOf(off);
                    if (stow(p, off)) inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
                }
            }
            if (prevLeftItem != null && !prevLeftItem.isEmpty()) tag.put(BoundWeapons.FROM_LEFT_ITEM, prevLeftItem);
        }
        com.nirncraft.inventory.ItemData.setCustom(bound, tag);
        LAST_SELECTED.put(p.getUUID(), inv.getSelectedSlot());
        return true;
    }

    /** The bound weapon ran out while held: the previous weapon (or the spell) it replaced returns to the hand. */
    public static void restoreAfterBound(ServerPlayer p, CompoundTag boundTag) {
        if (boundTag.getBooleanOr(BoundWeapons.BOUND_LEFT, false)) {
            if (p.getOffhandItem().isEmpty() && !twoHanded(p.getMainHandItem())) {
                if (com.nirncraft.platform.NbtData.hasType(boundTag, BoundWeapons.FROM_LEFT_ITEM, net.minecraft.nbt.Tag.TAG_COMPOUND)
                        && restoreItemToHand(p, boundTag.getCompoundOrEmpty(BoundWeapons.FROM_LEFT_ITEM), true)) {
                    MagicData.tag(p).remove("prev_left_item");
                    return;
                }
                String left = boundTag.getStringOr(BoundWeapons.FROM_LEFT, "");
                String now = MagicData.leftSpell(p);
                if (!left.isEmpty() && mayEquip(p, left) && (now.isEmpty() || MagicData.NONE.equals(now))) {
                    MagicData.setLeftSpell(p, left);
                }
            }
            return;
        }
        if (p.getMainHandItem().isEmpty()) {
            if (com.nirncraft.platform.NbtData.hasType(boundTag, BoundWeapons.FROM_RIGHT_ITEM, net.minecraft.nbt.Tag.TAG_COMPOUND)
                    && restoreItemToHand(p, boundTag.getCompoundOrEmpty(BoundWeapons.FROM_RIGHT_ITEM), false)) {
                MagicData.tag(p).remove("prev_right_item");
            } else {
                String right = boundTag.getStringOr(BoundWeapons.FROM_RIGHT, "");
                if (!right.isEmpty() && mayEquip(p, right) && MagicData.selectedSpell(p).isEmpty()) {
                    MagicData.setSelectedSpell(p, right);
                }
            }
        }
        if (p.getOffhandItem().isEmpty() && !twoHanded(p.getMainHandItem())) {
            if (com.nirncraft.platform.NbtData.hasType(boundTag, BoundWeapons.FROM_LEFT_ITEM, net.minecraft.nbt.Tag.TAG_COMPOUND)
                    && restoreItemToHand(p, boundTag.getCompoundOrEmpty(BoundWeapons.FROM_LEFT_ITEM), true)) {
                MagicData.tag(p).remove("prev_left_item");
                return;
            }
            String left = boundTag.getStringOr(BoundWeapons.FROM_LEFT, "");
            String now = MagicData.leftSpell(p);
            if (!left.isEmpty() && mayEquip(p, left) && (now.isEmpty() || MagicData.NONE.equals(now))) {
                MagicData.setLeftSpell(p, left);
            }
        }
    }

    /** Finds a previously displaced item in the player's inventory or pack and puts it back in the hand. */
    private static boolean restoreItemToHand(ServerPlayer p, CompoundTag itemKey, boolean leftHand) {
        if (itemKey == null || itemKey.isEmpty()) return false;
        ItemStack target = com.nirncraft.inventory.ItemData.parse(itemKey);
        if (target.isEmpty() || BoundWeapons.isBound(target)) return false;
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (!leftHand && i == inv.getSelectedSlot()) continue;
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, target)) {
                if (leftHand) {
                    ItemStack one = s.split(1);
                    if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                    inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, one);
                } else if (Inventory.isHotbarSlot(i) && inv.getItem(inv.getSelectedSlot()).isEmpty()) {
                    selectSlot(p, i);
                } else {
                    ItemStack one = s.split(1);
                    if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                    inv.setItem(inv.getSelectedSlot(), one);
                }
                return true;
            }
        }
        int pIdx = com.nirncraft.inventory.Pack.find(p, s -> ItemStack.isSameItemSameComponents(s, target));
        if (pIdx >= 0) {
            ItemStack taken = com.nirncraft.inventory.Pack.take(p, pIdx, 1);
            if (!taken.isEmpty()) {
                if (leftHand) inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, taken);
                else inv.setItem(inv.getSelectedSlot(), taken);
                com.nirncraft.inventory.Pack.sync(p);
                return true;
            }
        }
        return false;
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
        int sel = inv.getSelectedSlot();
        ItemStack main = inv.getItem(sel);
        ItemStack off = inv.getItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND);
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
        if (twoHanded && BoundWeapons.isBound(off)) {
            inv.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            off = ItemStack.EMPTY;
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
            if (i == inv.getSelectedSlot()) continue;
            if (i == Inventory.SLOT_OFFHAND && !twoHanded(inv.getItem(i)) && !twoHanded(inv.getItem(inv.getSelectedSlot()))) continue;
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
        saved.putInt("slot", p.getInventory().getSelectedSlot());
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
        CompoundTag saved = t.getCompoundOrEmpty(SAVED);
        t.remove(SAVED);
        String right = saved.getStringOr("right", "");
        String left = saved.getStringOr("left", "");
        int slot = saved.getIntOr("slot", 0);
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
        ItemStack held = inv.getItem(inv.getSelectedSlot());
        if (held.isEmpty()) return true;
        if (BoundWeapons.isBound(held)) {
            inv.setItem(inv.getSelectedSlot(), ItemStack.EMPTY);
            return true;
        }
        int hot = emptyHotbar(inv, inv.getSelectedSlot());
        if (hot >= 0) {
            selectSlot(p, hot);
            return true;
        }
        int bag = emptyBag(inv);
        inv.setItem(inv.getSelectedSlot(), ItemStack.EMPTY);
        if (bag < 0) com.nirncraft.inventory.Pack.giveOrDrop(p, held); // every slot is taken: the overflow pack
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
        if (to < 0) to = emptyHotbar(inv, inv.getSelectedSlot());
        if (to < 0) {
            // every slot is taken: the overflow pack holds it (inventory.Pack)
            com.nirncraft.inventory.Pack.giveOrDrop(p, stack.copy());
            return true;
        }
        inv.setItem(to, stack.copy());
        return true;
    }

    private static void freeOffHand(ServerPlayer p) {
        ItemStack off = p.getOffhandItem();
        if (off.isEmpty()) return;
        if (BoundWeapons.isBound(off)) {
            p.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
        } else if (stow(p, off)) {
            p.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
        }
    }

    private static int emptyHotbar(Inventory inv, int except) {
        for (int i = 0; i < 9; i++) if (i != except && inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    private static int emptyBag(Inventory inv) {
        for (int i = 9; i < inv.getNonEquipmentItems().size(); i++) if (inv.getNonEquipmentItems().get(i).isEmpty()) return i;
        return -1;
    }

    private static void selectSlot(ServerPlayer p, int slot) {
        p.getInventory().setSelectedSlot(slot);
        if (p.connection != null) p.connection.send(new ClientboundSetHeldSlotPacket(slot));
    }

    /** The spell a player holds in a hand, or null (for the HUD and the self-test). */
    public static Spell spellIn(Player p, boolean left) {
        if (left ? left(p) != Held.SPELL : right(p) != Held.SPELL) return null;
        return Spells.byId(left ? MagicData.leftSpell(p) : MagicData.selectedSpell(p));
    }
}
