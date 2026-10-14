package com.skycraft.magic.bound;

import com.skycraft.core.Notifier;
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
import net.minecraft.world.InteractionHand;
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
        Inventory inv = p.getInventory();
        // Re-casting refreshes: remove any copy we already hold.
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) inv.setItem(i, ItemStack.EMPTY);
        }
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putLong(UNTIL, p.level().getGameTime() + spell.duration);
        tag.putUUID(OWNER, p.getUUID());

        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) {
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        } else {
            int free = inv.getFreeSlot();
            if (free >= 0) {
                inv.setItem(free, held.copy());
                p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            } else {
                // every slot is taken: the held item goes into the overflow pack and the conjured weapon takes its place
                ItemStack displaced = held.copy();
                p.setItemInHand(InteractionHand.MAIN_HAND, stack);
                com.skycraft.inventory.Pack.giveOrDrop(p, displaced);
            }
        }
        MagicFx.send(p, MagicFx.SUMMON, spell.element, p.position(), p.position(), p.getId(), 18);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.8f, 1.4f);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1f, 0.6f);
        return Spell.Result.EFFECT;
    }

    /** Whether a bound stack should vanish right now in the inventory of {@code holder}. */
    public static boolean expired(ItemStack stack, Level level, Entity holder) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(OWNER)) return true;
        if (tag.getLong(UNTIL) <= level.getGameTime()) return true;
        UUID owner = tag.getUUID(OWNER);
        return !(holder instanceof Player p) || !p.getUUID().equals(owner) || !p.isAlive();
    }

    /** Shared inventoryTick for bound items. */
    public static void inventoryTick(ItemStack stack, Level level, Entity holder) {
        if (level.isClientSide || level.getGameTime() % 10 != 0) return;
        if (expired(stack, level, holder)) {
            stack.setCount(0);
            if (holder instanceof ServerPlayer sp) {
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 0.7f, 1.6f);
                Notifier.message(sp, Component.translatable("message.skycraft.bound_vanished"));
            }
        }
    }

    /** Bound items never lie around in the world. */
    public static boolean onEntityItemUpdate(ItemEntity entity) {
        if (!entity.level().isClientSide) entity.discard();
        return true;
    }

    /** A bound weapon, or a bundle with one inside: neither may be stored anywhere but the caster's own inventory. */
    public static boolean holdsBound(ItemStack stack) {
        if (isBound(stack)) return true;
        if (!(stack.getItem() instanceof BundleItem)) return false;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(BUNDLE_ITEMS, Tag.TAG_LIST)) return false;
        ListTag items = tag.getList(BUNDLE_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            if (holdsBound(ItemStack.of(items.getCompound(i)))) return true;
        }
        return false;
    }

    /** Removes bound weapons from a bundle's contents (also nested bundles). Returns true if anything was removed. */
    public static boolean purgeBundle(ItemStack bundle) {
        if (!(bundle.getItem() instanceof BundleItem)) return false;
        CompoundTag tag = bundle.getTag();
        if (tag == null || !tag.contains(BUNDLE_ITEMS, Tag.TAG_LIST)) return false;
        ListTag items = tag.getList(BUNDLE_ITEMS, Tag.TAG_COMPOUND);
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            ItemStack inside = ItemStack.of(items.getCompound(i));
            if (isBound(inside)) {
                items.remove(i);
                changed = true;
            } else if (purgeBundle(inside)) {
                items.set(i, inside.save(new CompoundTag()));
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
        CompoundTag tag = stack.getTag();
        if (tag == null) return 0;
        return Math.max(0, (tag.getLong(UNTIL) - level.getGameTime()) / 20);
    }
}
