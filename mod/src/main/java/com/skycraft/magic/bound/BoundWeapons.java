package com.skycraft.magic.bound;

import com.skycraft.core.Notifier;
import com.skycraft.magic.Hands;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.spell.Spell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Bound weapons: conjured Daedric weapons that exist for a limited time and only in their caster's hands. They
 * vanish when the time is up, when dropped, when put into any container or bundle, or when their owner dies.
 */
public final class BoundWeapons {
    public static final String UNTIL = "skycraft_bound_until";
    public static final String OWNER = "skycraft_bound_owner";
    /** Length of the conjuration in ticks (for the HUD countdown). */
    public static final String DURATION = "skycraft_bound_duration";
    /** The spells this weapon displaced from the right / left hand; they come back when it runs out while held. */
    public static final String FROM_RIGHT = "skycraft_bound_from_right";
    public static final String FROM_LEFT = "skycraft_bound_from_left";
    /** The item keys ({@link com.skycraft.inventory.Pack#keyOf}) displaced from the right / left hand when conjuring. */
    public static final String FROM_RIGHT_ITEM = "skycraft_bound_from_right_item";
    public static final String FROM_LEFT_ITEM = "skycraft_bound_from_left_item";
    /** True when a one-handed bound weapon was conjured into the left (off) hand. */
    public static final String BOUND_LEFT = "skycraft_bound_left";
    /** Marks arrows fired from a bound bow (persistent data), so bound-weapon perks apply to them. */
    public static final String ARROW_TAG = "skycraft_bound_arrow";
    /** Vanilla bundle contents tag. */
    private static final String BUNDLE_ITEMS = "Items";

    private BoundWeapons() {}

    /** Marker for bound weapon items. */
    public interface BoundItem {}

    public static boolean isBound(ItemStack stack) {
        return stack.getItem() instanceof BoundItem;
    }

    public static Spell.Result sword(ServerPlayer p, Spell spell, int tick) {
        return give(p, spell, MagicRegistry.BOUND_SWORD.get());
    }

    /** Bound Dagger (Dragonborn). */
    public static Spell.Result dagger(ServerPlayer p, Spell spell, int tick) {
        return give(p, spell, MagicRegistry.BOUND_DAGGER.get());
    }

    public static Spell.Result battleaxe(ServerPlayer p, Spell spell, int tick) {
        return give(p, spell, MagicRegistry.BOUND_BATTLEAXE.get());
    }

    public static Spell.Result bow(ServerPlayer p, Spell spell, int tick) {
        return give(p, spell, MagicRegistry.BOUND_BOW.get());
    }

    private static Spell.Result give(ServerPlayer p, Spell spell, Item item) {
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = com.skycraft.inventory.ItemData.customOrEmpty(stack);
        tag.putLong(UNTIL, p.level().getGameTime() + spell.duration);
        tag.store(OWNER, net.minecraft.core.UUIDUtil.CODEC, p.getUUID());
        tag.putInt(DURATION, spell.duration);
        com.skycraft.inventory.ItemData.setCustom(stack, tag);
        boolean leftHand = com.skycraft.magic.spell.SpellCasting.castingSide() < 0 && !Hands.twoHanded(stack);
        // One hand system (magic.Hands): the weapon replaces what the casting hand held (a re-cast refreshes the old copy).
        if (!Hands.equipBound(p, stack, leftHand)) {
            Notifier.message(p, Component.translatable("message.skycraft.inventory_full"));
            return Spell.Result.FAILED;
        }
        MagicFx.send(p, MagicFx.SUMMON, spell.element, p.position(), p.position(), p.getId(), 18);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.8f, 1.4f);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1f, 0.6f);
        return Spell.Result.EFFECT;
    }

    /** Whether a bound stack should vanish right now in the inventory of {@code holder}. */
    public static boolean expired(ItemStack stack, Level level, Entity holder) {
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag == null || !tag.read(OWNER, net.minecraft.core.UUIDUtil.CODEC).isPresent()) return true;
        if (tag.getLongOr(UNTIL, 0L) <= level.getGameTime()) return true;
        UUID owner = tag.read(OWNER, net.minecraft.core.UUIDUtil.CODEC).orElseThrow();
        return !(holder instanceof Player p) || !p.getUUID().equals(owner) || !p.isAlive();
    }

    /** Shared inventoryTick for bound items. */
    public static void inventoryTick(ItemStack stack, Level level, Entity holder) {
        if (level.isClientSide() || level.getGameTime() % 10 != 0) return;
        if (expired(stack, level, holder)) {
            CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
            boolean ranOut = tag != null && tag.read(OWNER, net.minecraft.core.UUIDUtil.CODEC).isPresent() && tag.read(OWNER, net.minecraft.core.UUIDUtil.CODEC).orElseThrow().equals(holder.getUUID())
                    && tag.getLongOr(UNTIL, 0L) <= level.getGameTime();
            CompoundTag from = tag == null ? new CompoundTag() : tag.copy();
            boolean heldRight = holder instanceof ServerPlayer hp && hp.getMainHandItem() == stack;
            boolean heldLeft = holder instanceof ServerPlayer hp && hp.getOffhandItem() == stack;
            if (heldLeft) from.putBoolean(BOUND_LEFT, true);
            stack.setCount(0);
            if (holder instanceof ServerPlayer sp) {
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 0.7f, 1.6f);
                Notifier.message(sp, Component.translatable("message.skycraft.bound_vanished"));
                // the hand it was in gets the previous weapon or spell back that it replaced
                if (ranOut && (heldRight || heldLeft) && sp.isAlive()) Hands.restoreAfterBound(sp, from);
            }
        }
    }

    /**
     * Removes every bound weapon from an inventory (hotbar, bags, armor, off hand) and its bundles. Used on logout (the
     * logged-out event fires before the player is saved), login, death, respawn and dimension change, so a bound weapon
     * never reaches a save file. Returns how many were removed.
     */
    public static int purgeFrom(Inventory inv) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (isBound(stack)) {
                inv.setItem(i, ItemStack.EMPTY);
                n++;
            } else if (purgeBundle(stack)) {
                n++;
            }
        }
        return n;
    }

    /** {@link #purgeFrom} for a player, plus the stack held on the cursor of the inventory screen. */
    public static int purgeAll(Player player) {
        int n = purgeFrom(player.getInventory());
        ItemStack carried = player.containerMenu.getCarried();
        if (isBound(carried)) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            n++;
        }
        return n;
    }

    /** Bound items never lie around in the world. */
    public static boolean onEntityItemUpdate(ItemEntity entity) {
        if (!entity.level().isClientSide()) entity.discard();
        return true;
    }

    /** A bound weapon, or a bundle with one inside: neither may be stored anywhere but the caster's own inventory. */
    public static boolean holdsBound(ItemStack stack) {
        if (isBound(stack)) return true;
        if (!(stack.getItem() instanceof BundleItem)) return false;
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag == null || !com.skycraft.platform.NbtData.hasType(tag, BUNDLE_ITEMS, Tag.TAG_LIST)) return false;
        ListTag items = tag.getListOrEmpty(BUNDLE_ITEMS);
        for (int i = 0; i < items.size(); i++) {
            if (holdsBound(com.skycraft.inventory.ItemData.parse(items.getCompoundOrEmpty(i)))) return true;
        }
        return false;
    }

    /** Removes bound weapons from a bundle's contents (also nested bundles). Returns true if anything was removed. */
    public static boolean purgeBundle(ItemStack bundle) {
        if (!(bundle.getItem() instanceof BundleItem)) return false;
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(bundle);
        if (tag == null || !com.skycraft.platform.NbtData.hasType(tag, BUNDLE_ITEMS, Tag.TAG_LIST)) return false;
        ListTag items = tag.getListOrEmpty(BUNDLE_ITEMS);
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            ItemStack inside = com.skycraft.inventory.ItemData.parse(items.getCompoundOrEmpty(i));
            if (isBound(inside)) {
                items.remove(i);
                changed = true;
            } else if (purgeBundle(inside)) {
                items.set(i, com.skycraft.inventory.ItemData.save(inside));
                changed = true;
            }
        }
        if (items.isEmpty()) tag.remove(BUNDLE_ITEMS);
        return changed;
    }

    /**
     * Removes bound weapons from every slot of {@code menu} that isn't the player's own inventory, and from bundles in
     * any of its slots.
     */
    public static void purge(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (isBound(stack)) {
                if (!(slot.container instanceof Inventory)) slot.set(ItemStack.EMPTY);
            } else if (purgeBundle(stack)) {
                slot.setChanged();
            }
        }
    }

    /** Bundles in the player's own inventory can't hold bound weapons either. */
    public static void purgeInventoryBundles(Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof BundleItem && purgeBundle(stack)) inv.setChanged();
        }
    }

    /** Seconds left, for the tooltip (client side uses the client level time). */
    public static long secondsLeft(ItemStack stack, Level level) {
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        if (tag == null) return 0;
        return Math.max(0, (tag.getLongOr(UNTIL, 0L) - level.getGameTime()) / 20);
    }
}
