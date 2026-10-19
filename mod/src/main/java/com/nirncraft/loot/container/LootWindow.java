package com.nirncraft.loot.container;

import com.nirncraft.inventory.BulkStore;
import com.nirncraft.inventory.ItemWeights;
import com.nirncraft.inventory.OverflowHost;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * One player's open view of their {@link LootInstance} (docs/CONTAINERS.md section 6): the 27 slots of an ordinary
 * {@code GENERIC_9x3} chest menu, so the Skyrim container screen ({@code inventory.client.SkyrimContainerScreen}), the
 * take/store handlers, the theft and lock machinery and the vanilla creative screen all work unchanged.
 *
 * <ul>
 *     <li><b>Slots.</b> Filled from the copy's store when the window opens; everything else stays in the store, which
 *     is the window's overflow ({@link OverflowHost}) and is listed after the slots. On close the slots fold back.</li>
 *     <li><b>Storing.</b> The slots never accept a stored item ({@link LootMenu}'s take-only slots; a menu slot never
 *     asks {@code canPlaceItem}, which is false too for anything else that might), so every store goes
 *     through {@link #overflowRoom}: the balance rule of world containers and the books-only rule of shelves apply
 *     exactly, with no stack of slack.</li>
 * </ul>
 */
public final class LootWindow extends SimpleContainer implements OverflowHost {
    public static final int SLOTS = 27;

    private final LootContainerBlockEntity be;
    private final LootInstance instance;
    private boolean closed;
    /** Menus showing this window (a shared copy two players look into is one window). */
    private int openers;

    LootWindow(LootContainerBlockEntity be, LootInstance instance) {
        super(SLOTS);
        this.be = be;
        this.instance = instance;
        BulkStore store = instance.items();
        for (int i = 0; i < SLOTS && !store.isEmpty(); i++) {
            ItemStack head = store.get(0);
            if (head.isEmpty()) {
                store.take(0, Integer.MAX_VALUE); // unreadable row: drop it so the loop cannot stick
                i--;
                continue;
            }
            setItem(i, store.take(0, head.getMaxStackSize()));
        }
        instance.window = this;
    }

    public LootContainerBlockEntity blockEntity() {
        return be;
    }

    public LootInstance instance() {
        return instance;
    }

    @Override
    public BulkStore overflow() {
        return instance.items();
    }

    @Override
    public int overflowRoom(ItemStack stack) {
        if (closed || !accepts(be.kind(), stack)) return 0;
        return instance.room(stack, be.stashKg(), ItemWeights::get);
    }

    /** Whether a kind takes this item at all (a shelf holds books and notes only). */
    public static boolean accepts(ContainerKind kind, ItemStack stack) {
        if (!kind.booksOnly()) return true;
        return stack.is(ItemTags.BOOKSHELF_BOOKS) || stack.getItem() instanceof com.nirncraft.lore.LoreBookItem
                || stack.getItem() instanceof com.nirncraft.loot.BlackBookItem;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return !closed && !be.isRemoved() && be.getLevel() == player.level() && Container.stillValidBlockEntity(be, player);
    }

    @Override
    public void startOpen(ContainerUser user) {
        openers++;
        if (user != null && user.getLivingEntity() instanceof Player p) be.viewerOpened(p);
    }

    @Override
    public void stopOpen(ContainerUser user) {
        if (--openers <= 0) foldBack();
        if (user != null && user.getLivingEntity() instanceof Player p) be.viewerClosed(p);
    }

    public boolean isClosed() {
        return closed;
    }

    /** Moves the slots back into the store and detaches the window (idempotent). */
    void foldBack() {
        if (closed) return;
        closed = true;
        BulkStore store = instance.items();
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack s = removeItemNoUpdate(i);
            if (s.isEmpty()) continue;
            store.add(s);
            // only the bulk store's engineering caps can refuse: never lose it, spill it where the container stands
            if (!s.isEmpty() && be.getLevel() instanceof ServerLevel level) {
                BulkStore rest = new BulkStore(1, Integer.MAX_VALUE, null);
                rest.add(s);
                com.nirncraft.inventory.ContainerOverflow.spill(level, be.getBlockPos(), rest);
            }
        }
        if (instance.window == this) instance.window = null;
        be.setChanged();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        be.setChanged();
    }
}
