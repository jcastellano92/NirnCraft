package com.nirncraft.inventory.client;

import com.nirncraft.inventory.CarryWeight;
import com.nirncraft.inventory.InventoryPackets;
import com.nirncraft.inventory.Pack;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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

    /** The server's copy of the open container's overflow (see {@link ContainerOverflowClient}). */
    public static void containerOverflow(InventoryPackets.ContainerOverflowSync m) {
        ContainerOverflowClient.apply(m);
    }

    public static void reset() {
        ContainerOverflowClient.clear();
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
    public static void useHeld(Identifier item) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return;
        if (mc.gui.screen() instanceof SkyrimInventoryScreen || mc.gui.screen() instanceof FavoritesScreen) mc.gui.setScreen(null);
        if (mc.gui.screen() != null) return;
        ItemStack main = player.getMainHandItem();
        if (main.isEmpty() || !BuiltInRegistries.ITEM.getKey(main.getItem()).equals(item)) return;
        InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result instanceof net.minecraft.world.InteractionResult.Success success && success.swingSource()==net.minecraft.world.InteractionResult.SwingSource.PREDICTED) player.swing(InteractionHand.MAIN_HAND,player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),false);
    }

    /** Sends an inventory action for a row (its first slot). */
    public static void send(int action, InvEntry entry) {
        if (entry == null) return;
        SkyNetwork.sendToServer(new InventoryPackets.InvAction(action, entry.primarySlot(), entry.itemId));
    }

    private static net.minecraft.client.gui.screens.Screen pendingReturnScreen;

    public static net.minecraft.client.gui.screens.Screen consumeReturnScreen(net.minecraft.client.gui.screens.Screen current) {
        if (pendingReturnScreen != null) {
            net.minecraft.client.gui.screens.Screen ret = pendingReturnScreen;
            pendingReturnScreen = null;
            return ret;
        }
        return current instanceof SkyrimInventoryScreen ? current : null;
    }

    public static void applyPoison(InvEntry entry) {
        if (entry != null && com.nirncraft.crafting.arcane.ArcaneEvents.isApplicablePoison(entry.stack)) {
            send(InventoryPackets.InvAction.APPLY_POISON, entry);
        }
    }

    /**
     * The row's main action: Eat/Drink, Read, or Equip/Unequip. Returns true if the screen was replaced or should close
     * (reading opens the book's own screen).
     */
    public static boolean primary(InvEntry entry) {
        if (entry == null) return false;
        if (entry.consumable() && entry.equip != InvEntry.WORN) {
            send(InventoryPackets.InvAction.USE, entry);
            return false;
        }
        if (entry.readable() && entry.equip != InvEntry.WORN) {
            Minecraft mc = Minecraft.getInstance();
            if (entry.stack.getItem() instanceof com.nirncraft.guide.HandbookItem) {
                net.minecraft.client.gui.screens.Screen ret = mc.gui.screen() instanceof SkyrimInventoryScreen ? mc.gui.screen() : null;
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1.0f, 1.0f));
                mc.gui.setScreen(new com.nirncraft.guide.client.HandbookScreen(ret));
                return true;
            }
            if (entry.stack.getItem() instanceof com.nirncraft.magic.spell.SpellTomeItem
                    || entry.stack.getItem() instanceof com.nirncraft.economy.SkillBookItem) {
                send(InventoryPackets.InvAction.READ, entry);
                return false;
            }
            pendingReturnScreen = mc.gui.screen() instanceof SkyrimInventoryScreen ? mc.gui.screen() : null;
            if (!(entry.stack.getItem() instanceof com.nirncraft.lore.LoreBookItem)) {
                mc.gui.setScreen(null);
            }
            send(InventoryPackets.InvAction.READ, entry);
            return true;
        }
        if (entry.equipped() || entry.isUsableEquipment()) {
            send(InventoryPackets.InvAction.EQUIP, entry);
        }
        return false;
    }

    /** Equip to / unequip from the left hand, or apply poison when right-clicking a poison potion. */
    public static boolean secondary(InvEntry entry) {
        if (entry == null) return false;
        if (com.nirncraft.crafting.arcane.ArcaneEvents.isApplicablePoison(entry.stack)) {
            send(InventoryPackets.InvAction.APPLY_POISON, entry);
            return false;
        }
        if (entry.equip == InvEntry.LEFT || entry.leftHandable()) {
            send(InventoryPackets.InvAction.EQUIP_LEFT, entry);
            return false;
        }
        return primary(entry);
    }

    public static String primaryLabelKey(InvEntry entry) {
        if (entry.consumable() && entry.equip != InvEntry.WORN) return entry.drink() ? "drink" : "eat";
        if (entry.readable() && entry.equip != InvEntry.WORN) return "read";
        if (entry.equip == InvEntry.LEFT
                && com.nirncraft.inventory.InventoryActions.equipSlotFor(entry.stack) != net.minecraft.world.entity.EquipmentSlot.OFFHAND) {
            return "equip";
        }
        if (entry.equipped()) return "unequip";
        if (entry.isUsableEquipment()) return "equip";
        return null;
    }
}
