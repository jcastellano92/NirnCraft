package com.nirncraft.inventory;

import net.minecraft.world.Container;

/**
 * A {@link Container} that owns an overflow {@link BulkStore} behind its slots (docs/INVENTORY.md "Containers").
 *
 * <p>Implemented by the containers that are not block entities: the two corpse kinds ({@code survival.corpse} and
 * {@code creatures.entity.CorpseEntity}) and, for whoever wants it, a mount's saddlebags or a follower's inventory.
 * Block containers (chests, barrels) get theirs from {@link ContainerOverflow}'s capability instead. The Skyrim
 * container screen and {@code vitals.ActionHandler.handleContainerTake/Store} find the overflow of an open
 * {@code ChestMenu} through {@link ContainerOverflow#of}, whichever way it is provided.</p>
 *
 * <p>The implementer owns persistence (save {@link BulkStore#save()} next to the slots, {@link BulkStore#load} back) and
 * the change hook: give the store an {@code onChange} that marks the owner dirty. Nothing here is gameplay: the owner
 * decides what an overflow means for its weight or capacity rules.</p>
 */
public interface OverflowHost extends Container {
    /** The overflow store, or null when this container has none right now. */
    BulkStore overflow();

    /**
     * How many of {@code stack} a Skyrim "store" may put into the overflow right now (default: any number, only the
     * store's engineering caps apply). A weight-limited host (a mount's saddlebags) returns what its limit still
     * allows, so the overflow never bypasses its rule.
     */
    default int overflowRoom(net.minecraft.world.item.ItemStack stack) {
        return Integer.MAX_VALUE;
    }
}
