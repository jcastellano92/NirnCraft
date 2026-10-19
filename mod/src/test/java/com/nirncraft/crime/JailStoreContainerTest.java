package com.nirncraft.crime;

import com.nirncraft.inventory.BulkStore;
import com.nirncraft.testing.BoundedServerProvider;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The jail's confiscated goods ({@link JailEvidence.StoreContainer}) never lose what a player stores. Its 27 slots
 * belong to a container made for one opening and thrown away; a menu slot never asks {@code canPlaceItem}, so Store,
 * Store All, shift-click and clicks could put things in those slots, and they vanished when the menu closed.
 */
class JailStoreContainerTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @FunctionalInterface private interface Body { void run() throws Exception; }

    /** Item storage needs the live registries of the server thread: run each test there, between native ticks. */
    private static void onServer(MinecraftServer server, Body body) {
        var result = new java.util.concurrent.CompletableFuture<Void>();
        server.execute(() -> {
            try {
                body.run();
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            result.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static BulkStore store() {
        return new BulkStore(4096, 1_000_000, null);
    }

    private static Inventory inventory() {
        return new Inventory(null, new EntityEquipment());
    }

    /** The old bug, on the old construction (a plain chest menu): what reached a slot must be in the store after close. */
    @Test @ExtendWith(BoundedServerProvider.class)
    void whatReachedASlotIsInTheStoreAfterClose(MinecraftServer server) {
        onServer(server, () -> {
            BulkStore store = store();
            JailEvidence.StoreContainer container = new JailEvidence.StoreContainer(store, BlockPos.ZERO);
            ChestMenu plain = new ChestMenu(MenuType.GENERIC_9x3, 1, inventory(), container, 3);
            ItemStack rest = plain.getSlot(0).safeInsert(new ItemStack(Items.APPLE, 10)); // Store's slot step
            assertTrue(rest.isEmpty(), "a plain chest slot takes it (Slot#mayPlace never asks canPlaceItem)");
            container.stopOpen(null); // what the menu's removed() does when the player closes it
            assertEquals(10, store.count(Items.APPLE), "the ten apples must be in the jail's store, not lost with the slots");
            assertTrue(container.isEmpty());
        });
    }

    /** The menu the jail opens now: take-only slots, shift-click into the store, nothing lost. */
    @Test @ExtendWith(BoundedServerProvider.class)
    void theConfiscatedMenuStoresIntoTheJailsStore(MinecraftServer server) {
        onServer(server, () -> {
            BulkStore store = store();
            JailEvidence.StoreContainer container = new JailEvidence.StoreContainer(store, BlockPos.ZERO);
            Inventory inv = inventory();
            ChestMenu menu = JailEvidence.confiscatedMenu(1, inv, container);
            assertEquals(MenuType.GENERIC_9x3, menu.getType(), "the client still builds a chest menu");
            for (int i = 0; i < 27; i++) {
                ItemStack offer = new ItemStack(Items.IRON_INGOT, 16);
                assertFalse(menu.getSlot(i).mayPlace(offer), "slot " + i + " must be take-only");
                assertEquals(16, menu.getSlot(i).safeInsert(offer).getCount(), "slot " + i + " took a stored item");
            }
            inv.setItem(9, new ItemStack(Items.IRON_INGOT, 40)); // inventory slot 9 is menu slot 27
            menu.quickMoveStack(null, 27);
            assertTrue(inv.getItem(9).isEmpty(), "shift-click stores it");
            assertEquals(40, store.count(Items.IRON_INGOT), "into the jail's store");
            container.stopOpen(null);
            assertEquals(40, store.count(Items.IRON_INGOT), "closing loses and duplicates nothing");
        });
    }
}
