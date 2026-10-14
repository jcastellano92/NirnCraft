package com.skycraft.inventory.client;

import com.skycraft.inventory.CarryWeight;
import com.skycraft.inventory.InventoryPackets;
import com.skycraft.inventory.Pack;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** Client-only state and S2C handlers of the inventory module. */
public final class ClientInventoryHandlers {
    /** Carry weight as last reported by the server. */
    public static float current;
    public static float capacity = 300f;
    public static boolean enabled = true;
    public static boolean over;
    public static boolean received;

    private ClientInventoryHandlers() {}

    public static void carryState(float cur, float cap, boolean en, boolean ov) {
        current = cur;
        capacity = cap;
        enabled = en;
        over = ov;
        received = true;
    }

    /** The server's copy of the pack (overflow inventory), applied to the local player's data. */
    public static void packSync(InventoryPackets.PackSync m) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) Pack.applyClientSync(player, m.start(), m.total(), m.rows());
    }

    public static void reset() {
        current = 0f;
        capacity = 300f;
        enabled = true;
        over = false;
        received = false;
    }

    /** Live carry weight of the local player (the server's capacity, client-side sum so screens update instantly). */
    public static float liveCurrent() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? current : CarryWeight.current(player);
    }

    public static float liveCapacity() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (received || player == null) return capacity;
        return CarryWeight.capacity(player);
    }

    /** The server moved a book into the right hand: use it the vanilla way so its screen / logic runs. */
    public static void useHeld(ResourceLocation item) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return;
        if (mc.screen instanceof SkyrimInventoryScreen || mc.screen instanceof FavoritesScreen) mc.setScreen(null);
        if (mc.screen != null) return;
        ItemStack main = player.getMainHandItem();
        if (main.isEmpty() || !BuiltInRegistries.ITEM.getKey(main.getItem()).equals(item)) return;
        InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result.shouldSwing()) player.swing(InteractionHand.MAIN_HAND);
    }

    /** Sends an inventory action for a row (its first slot). */
    public static void send(int action, InvEntry entry) {
        if (entry == null) return;
        SkyNetwork.sendToServer(new InventoryPackets.InvAction(action, entry.primarySlot(), entry.itemId));
    }

    /**
     * The row's main action: Eat/Drink, Read, or Equip/Unequip. Returns true if the screen should close
     * (reading opens the book's own screen).
     */
    public static boolean primary(InvEntry entry) {
        if (entry == null) return false;
        if (entry.consumable() && entry.equip != InvEntry.WORN) {
            send(InventoryPackets.InvAction.USE, entry);
            return false;
        }
        if (entry.readable() && entry.equip != InvEntry.WORN) {
            Minecraft.getInstance().setScreen(null);
            send(InventoryPackets.InvAction.READ, entry);
            return true;
        }
        if (entry.equipped() || entry.isUsableEquipment()) {
            send(InventoryPackets.InvAction.EQUIP, entry);
        }
        return false;
    }

    /** Equip to / unequip from the left hand, falling back to the main action when the item can't be held there. */
    public static boolean secondary(InvEntry entry) {
        if (entry == null) return false;
        if (entry.equip == InvEntry.LEFT || entry.leftHandable()) {
            send(InventoryPackets.InvAction.EQUIP_LEFT, entry);
            return false;
        }
        return primary(entry);
    }

    public static String primaryLabelKey(InvEntry entry) {
        if (entry.consumable() && entry.equip != InvEntry.WORN) return entry.drink() ? "drink" : "eat";
        if (entry.readable() && entry.equip != InvEntry.WORN) return "read";
        if (entry.equipped()) return "unequip";
        if (entry.isUsableEquipment()) return "equip";
        return null;
    }
}
