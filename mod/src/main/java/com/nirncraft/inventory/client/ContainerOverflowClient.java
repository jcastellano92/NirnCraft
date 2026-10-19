package com.nirncraft.inventory.client;

import com.nirncraft.inventory.InventoryPackets;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Client mirror of the overflow of the container whose menu is open ({@code inventory.ContainerOverflow}): the rows
 * the server sent in {@link InventoryPackets.ContainerOverflowSync}. One container at a time (the menu's id); the
 * Skyrim container screen lists these rows after the slot rows and asks for them with the menu slot id
 * {@code ContainerOverflow.SLOT_BASE + index}.
 */
public final class ContainerOverflowClient {
    private static int containerId = -1;
    private static List<ItemStack> entries = List.of();
    private static List<ItemStack> staging;

    private ContainerOverflowClient() {}

    /** Applies one packet; the copy is replaced when its last row arrived. */
    public static void apply(InventoryPackets.ContainerOverflowSync m) {
        if (m.start() == 0) staging = new ArrayList<>();
        if (staging == null) return;
        for (InventoryPackets.PackSync.Row r : m.rows()) {
            ItemStack s = com.nirncraft.inventory.ItemData.parse(r.key());
            if (!s.isEmpty()) s.setCount(Math.max(1, r.count()));
            staging.add(s); // unreadable rows stay as EMPTY placeholders: indexes must match the server's
        }
        if (staging.size() >= m.total()) {
            containerId = m.containerId();
            entries = staging;
            staging = null;
        }
    }

    /** The overflow rows of the open container (empty when the menu has none), index-aligned with the server's. */
    public static List<ItemStack> entries(int menuContainerId) {
        return menuContainerId == containerId ? entries : List.of();
    }

    public static void clear() {
        containerId = -1;
        entries = List.of();
        staging = null;
    }
}
