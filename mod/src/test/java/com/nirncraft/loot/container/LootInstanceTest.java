package com.nirncraft.loot.container;

import com.nirncraft.testing.BoundedServerProvider;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules of one player's copy of a loot container (docs/CONTAINERS.md sections 4, 5 and 7) without a world: when a
 * copy rolls and restocks, that a restock never touches the player's own things, the stash allowance of world
 * containers against unlimited player storage, and the save format. The live per-player and multiplayer behaviour is
 * {@link ContainerSelfTest}, run on a server.
 */
class LootInstanceTest {
    private static final ToDoubleFunction<ItemStack> KG = s -> s.is(Items.IRON_BLOCK) ? 10.0 : s.is(Items.EMERALD) ? 0.0 : 1.0;

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

    private static List<ItemStack> roll() {
        return List.of(new ItemStack(Items.GOLD_NUGGET, 12), new ItemStack(Items.IRON_SWORD), new ItemStack(Items.BREAD, 3));
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void aCopyRollsOnceThenRestocksOnlyAfterItsTime(MinecraftServer server) {
        onServer(server, () -> {
            LootInstance i = new LootInstance(() -> {});
            assertTrue(i.dueForRoll(1000, 30), "a fresh copy is rolled on its first open");
            i.restock(roll(), 1000, KG);
            assertFalse(i.dueForRoll(1000 + 29 * LootInstance.DAY, 30), "not before 30 days");
            assertTrue(i.dueForRoll(1000 + 30 * LootInstance.DAY, 30), "after 30 days, with only its own loot inside");
            assertFalse(i.dueForRoll(1000 + 3000 * LootInstance.DAY, 0), "a boss hoard (0 days) never restocks");
            i.items().take(0, 5); // the player took some gold: what is left is still the table's
            assertFalse(i.holdsForeign());
            assertTrue(i.dueForRoll(1000 + 31 * LootInstance.DAY, 30));
    
        });
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void aRestockNeverTouchesThePlayersOwnThings(MinecraftServer server) {
        onServer(server, () -> {
            LootInstance i = new LootInstance(() -> {});
            i.restock(roll(), 0, KG);
            i.items().add(new ItemStack(Items.DIAMOND));
            assertTrue(i.holdsForeign(), "a kind the table did not roll is the player's");
            assertFalse(i.dueForRoll(100 * LootInstance.DAY, 30));
            i.items().takeAll(new ItemStack(Items.DIAMOND));
            i.items().add(new ItemStack(Items.BREAD, 2)); // 5 bread where the roll made 3
            assertTrue(i.holdsForeign(), "more of a kind than the roll made is the player's too");
    
        });
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void worldContainersTakeTheirRollBackPlusTheirAllowanceAndPlayerStorageIsUnlimited(MinecraftServer server) {
        onServer(server, () -> {
            LootInstance i = new LootInstance(() -> {});
            i.restock(roll(), 0, KG); // 12 + 1 + 3 = 16 kg rolled
            assertEquals(16f, i.rolledKg(), 1e-4);
            // 40 kg allowance on top of the 16 rolled; 16 are inside: 40 kg more, 4 iron blocks
            assertEquals(4, i.room(new ItemStack(Items.IRON_BLOCK), 40f, KG));
            i.items().take(i.items().indexOf(new ItemStack(Items.GOLD_NUGGET)), 12); // taken out: room to put it back
            assertEquals(5, i.room(new ItemStack(Items.IRON_BLOCK), 40f, KG));
            assertEquals(1, i.room(new ItemStack(Items.IRON_BLOCK), 0f, KG), "with no allowance only the roll's own weight (12 kg) fits back");
            assertEquals(Integer.MAX_VALUE, i.room(new ItemStack(Items.IRON_BLOCK), -1f, KG), "a player's own container has no limit");
            // weightless things are limited by entries, not weight
            for (int n = 0; n < LootInstance.STASH_ENTRIES + 3; n++) {
                ItemStack e = new ItemStack(Items.EMERALD);
                com.nirncraft.inventory.ItemData.updateCustom(e, t -> t.putInt("n", i.items().size()));
                if (i.room(e, 40f, KG) > 0) i.items().add(e);
            }
            assertTrue(i.items().size() <= 3 + LootInstance.STASH_ENTRIES, i.items().size() + " entries");
    
        });
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void aCopySurvivesSaveAndLoad(MinecraftServer server) {
        onServer(server, () -> {
            LootInstance i = new LootInstance(() -> {});
            i.restock(roll(), 12345, KG);
            i.items().add(new ItemStack(Items.ARROW, 5000));
            CompoundTag tag = i.save();
            LootInstance back = new LootInstance(() -> {});
            back.load(tag);
            assertEquals(12345, back.rolledAt());
            assertEquals(i.rolledKg(), back.rolledKg(), 1e-4);
            assertTrue(back.items().sameAs(i.items()));
            assertEquals(5000, back.items().count(Items.ARROW));
            assertTrue(back.holdsForeign(), "the generated record survives too");
            assertEquals(tag, back.save());
    
        });
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void theBlockEntityKeepsEachPlayersCopyApartAndSavesAnOpenWindow(MinecraftServer server) {
        onServer(server, () -> {
            var pos = net.minecraft.core.BlockPos.ZERO;
            var state = ContainerRegistry.block(ContainerKind.CHEST).defaultBlockState();
            LootContainerBlockEntity be = new LootContainerBlockEntity(pos, state);
            be.setWorldLoot("nirncraft:containers/dungeon/standard");
            java.util.UUID alice = java.util.UUID.randomUUID(), bob = java.util.UUID.randomUUID();
            LootInstance a = be.instance(alice), b = be.instance(bob);
            assertNotSame(a, b, "each player has a copy of their own");
            a.restock(roll(), 10, KG);
            b.restock(List.of(new ItemStack(Items.APPLE, 2)), 20, KG);
            LootWindow w = new LootWindow(be, a); // Alice has it open: her rows are in the window's slots now
            assertTrue(a.items().isEmpty() && !w.isEmpty(), "the window shows the first rows in its slots");
            assertEquals(0, w.overflowRoom(ItemStack.EMPTY));
            assertFalse(w.canPlaceItem(0, new ItemStack(Items.APPLE)), "stores always go through the room rule");

            CompoundTag tag = be.saveWithFullMetadata(server.registryAccess());
            var re = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, state, tag, server.registryAccess());
            assertInstanceOf(LootContainerBlockEntity.class, re);
            LootContainerBlockEntity l = (LootContainerBlockEntity) re;
            assertEquals("nirncraft:containers/dungeon/standard", l.table());
            assertTrue(l.isWorldLoot());
            assertEquals(3, l.existing(alice).items().size(), "the open window's slots were saved as rows");
            assertEquals(2, l.existing(bob).items().count(Items.APPLE));
            assertEquals("nirncraft:containers/dungeon/standard", tag.getStringOr("LootTable", ""));

            w.foldBack();
            assertNull(a.window);
            assertEquals(3, a.items().size(), "closing folds the slots back into the copy");

            // a Lootr chest's block entity data, read through the registry alias: the table survives
            CompoundTag lootr = new CompoundTag();
            lootr.putString("id", "nirncraft:loot_container");
            lootr.putString("LootTable", "nirncraft:containers/dungeon/boss");
            lootr.putIntArray("LootrId", new int[]{1, 2, 3, 4});
            var migrated = (LootContainerBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, state, lootr, server.registryAccess());
            assertNotNull(migrated);
            assertEquals("nirncraft:containers/dungeon/boss", migrated.table());
            assertTrue(migrated.isWorldLoot() && migrated.pendingNormalize(), "world loot, waiting for its kind and style");
    
        });
    }

    @Test @ExtendWith(BoundedServerProvider.class)
    void kindsStylesAndLids(MinecraftServer server) {
        onServer(server, () -> {
            assertEquals(ContainerKind.GRAND_CHEST, ContainerKind.forPlacement("nirncraft:containers/dungeon/boss", null, ContainerStyle.NORDIC));
            assertEquals(ContainerKind.STRONGBOX, ContainerKind.forPlacement("nirncraft:containers/strongbox/adept", null, null));
            assertEquals(ContainerKind.URN, ContainerKind.forPlacement("nirncraft:containers/dungeon/minor", Blocks.BARREL.defaultBlockState(), ContainerStyle.NORDIC));
            assertEquals(ContainerKind.BARREL, ContainerKind.forPlacement("nirncraft:containers/dungeon/minor", Blocks.BARREL.defaultBlockState(), ContainerStyle.RUSTIC));
            assertEquals(ContainerKind.CHEST, ContainerKind.forPlacement("nirncraft:containers/household/common", Blocks.CHEST.defaultBlockState(), ContainerStyle.IMPERIAL));
            assertEquals(ContainerKind.BOOKSHELF, ContainerKind.forPlacement("nirncraft:containers/household_books/common", null, null));
            assertEquals(ContainerStyle.DWEMER, ContainerStyle.ofTable("nirncraft:containers/dungeon/theme/dwemer"));
            assertNull(ContainerStyle.ofTable("nirncraft:containers/dungeon/standard"), "no theme: the region decides");
            assertEquals(ContainerStyle.VELOTHI, ContainerStyle.ofCulture("dunmer"));
            assertEquals(ContainerStyle.NORDIC, ContainerStyle.ofCulture("nord"));
            assertEquals(ContainerStyle.RUSTIC, ContainerStyle.ofCulture(null));
            assertEquals(LidState.AJAR, LidState.CLOSED.toward(LidState.OPEN));
            assertEquals(LidState.OPEN, LidState.AJAR.toward(LidState.OPEN));
            assertEquals(LidState.AJAR, LidState.OPEN.toward(LidState.CLOSED));
            assertEquals(LidState.CLOSED, LidState.CLOSED.toward(LidState.CLOSED));
    
        });
    }
}
