package com.skycraft.inventory;

import com.skycraft.Skycraft;
import com.skycraft.network.SkyNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the opening client's copy of a container's overflow equal to the server's while its menu is open
 * ({@link InventoryPackets.ContainerOverflowSync}, chunked like the pack sync, at most {@value #ROWS} rows a packet and
 * {@link ContainerOverflow#MAX_ENTRIES} rows in all).
 *
 * <p>One session per player: the open menu's container id, the overflow store it shows and the last version sent.
 * A session is created when a menu with an overflow opens (after normalising it: free slots are drained from it), is
 * resent on the first server tick after the store changed (another player's change to a shared chest included, because
 * the version is the store's own counter), and ends with the menu. Menus with no overflow cost nothing.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ContainerSync {
    static final int ROWS = 100;
    /** Smallest gap between two syncs of one session (ticks): a burst of take requests is one resend. */
    private static final int MIN_GAP = 2;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private ContainerSync() {}

    private static final class Session {
        final int containerId;
        BulkStore store;
        int sentVersion = -1;
        int sentTick;
        boolean dirty;

        Session(int containerId, BulkStore store) {
            this.containerId = containerId;
            this.store = store;
        }
    }

    // ------------------------------------------------------------------ server API

    /** An operation changed the open container's overflow (or its slots): resend on the next tick. */
    public static void markDirty(ServerPlayer player) {
        Session s = SESSIONS.get(player.getUUID());
        if (s != null) s.dirty = true;
    }

    /** True while a session for this player's current menu exists. */
    public static boolean isOpen(ServerPlayer player) {
        Session s = SESSIONS.get(player.getUUID());
        return s != null && s.containerId == player.containerMenu.containerId;
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AbstractContainerMenu menu = event.getContainer();
        SESSIONS.remove(player.getUUID());
        if (menu == player.inventoryMenu) return;
        ContainerOverflow.Bound bound = ContainerOverflow.of(player, menu);
        if (bound == null) return;
        // an overflow only exists while the slots are really full: normalise before the first sync
        ContainerOverflow.drain(bound.container(), bound.store());
        Session s = new Session(menu.containerId, bound.store());
        SESSIONS.put(player.getUUID(), s);
        send(player, s);
    }

    @SubscribeEvent
    public static void onClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Session s = SESSIONS.get(player.getUUID());
        if (s != null && s.containerId == event.getContainer().containerId) SESSIONS.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        Session s = SESSIONS.get(player.getUUID());
        if (s == null) return;
        if (player.containerMenu.containerId != s.containerId || player.containerMenu == player.inventoryMenu) {
            SESSIONS.remove(player.getUUID());
            return;
        }
        if ((s.dirty || s.store.modCount() != s.sentVersion) && (player.tickCount - s.sentTick >= MIN_GAP || player.tickCount < s.sentTick)) {
            send(player, s);
        }
    }

    // ------------------------------------------------------------------ packets

    private static void send(ServerPlayer player, Session s) {
        s.dirty = false;
        s.sentVersion = s.store.modCount();
        s.sentTick = player.tickCount;
        for (InventoryPackets.ContainerOverflowSync p : packets(s.containerId, s.store)) SkyNetwork.sendToPlayer(player, p);
    }

    /** The packets that make a client's copy equal to {@code store} (always at least one: the empty case clears). */
    public static List<InventoryPackets.ContainerOverflowSync> packets(int containerId, BulkStore store) {
        int total = Math.min(store.size(), ContainerOverflow.MAX_ENTRIES);
        List<InventoryPackets.ContainerOverflowSync> out = new ArrayList<>();
        if (total == 0) {
            out.add(new InventoryPackets.ContainerOverflowSync(containerId, 0, 0, List.of()));
            return out;
        }
        for (int start = 0; start < total; start += ROWS) {
            List<InventoryPackets.PackSync.Row> rows = new ArrayList<>();
            for (int i = start; i < Math.min(total, start + ROWS); i++) {
                rows.add(new InventoryPackets.PackSync.Row(store.keyAt(i).copy(), store.countAt(i)));
            }
            out.add(new InventoryPackets.ContainerOverflowSync(containerId, start, total, rows));
        }
        return out;
    }

    /** For the selftest: a player's current session store (null when none). */
    static BulkStore sessionStore(Player player) {
        Session s = SESSIONS.get(player.getUUID());
        return s == null ? null : s.store;
    }
}
