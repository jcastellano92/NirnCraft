package com.skycraft.inventory;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carry weight and encumbrance (contract 19).
 *
 * <p>Capacity is {@code baseCapacity (300) + 5 * Stamina level-ups + data.module("bonus").getFloat("carry")}. The
 * server checks every 10 ticks; when a player carries more than they can, they walk slower, cannot sprint and get
 * Skyrim's "You are carrying too much to be able to run." The state is sent to the owning client for the HUD and
 * to stop client-side sprinting.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CarryWeight {
    private static final UUID SPEED_MOD = UUID.fromString("8c2b6e1a-4f3d-4b9e-a7c5-1d2e3f4a5b61");
    private static final int CHECK_INTERVAL = 10;
    private static final int MESSAGE_COOLDOWN = 100;

    /** Last known server state per player. */
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        float current = -1f;
        float capacity = -1f;
        boolean over;
        boolean enabled;
        long lastMessage = -100000L;
        boolean sent;
    }

    private CarryWeight() {}

    // ------------------------------------------------------------------ public API

    /** How much the player can carry before becoming over-encumbered. */
    public static float capacity(Player player) {
        PlayerData data = SkyData.get(player);
        double base = safe(InventoryConfig.BASE_CAPACITY, 300.0);
        double perStamina = safe(InventoryConfig.CAPACITY_PER_STAMINA, 5.0);
        // the mount you steer carries part of the load (kind and saddle: com.skycraft.mounts.MountGear)
        return (float) (base + perStamina * data.getStaminaPoints() + data.module("bonus").getFloat("carry")
                + com.skycraft.mounts.MountGear.carryBonus(player));
    }

    /** Total weight of the main inventory, worn armor and offhand. */
    public static float current(Player player) {
        Inventory inv = player.getInventory();
        float sum = 0f;
        sum += sum(inv.items);
        sum += sum(inv.armor);
        sum += sum(inv.offhand);
        return sum;
    }

    private static float sum(List<ItemStack> stacks) {
        float s = 0f;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) s += ItemWeights.getStack(stack);
        }
        return s;
    }

    /** True if carry weight is enabled and the player carries more than their capacity (creative/spectator never are). */
    public static boolean isOverencumbered(Player player) {
        if (player == null || player.isCreative() || player.isSpectator()) return false;
        if (!player.level().isClientSide) {
            State s = STATES.get(player.getUUID());
            if (s != null && s.current >= 0) return s.over;
            return enabled() && current(player) > capacity(player);
        }
        return enabled() && current(player) > capacity(player);
    }

    /** Server-side switch (common config). */
    public static boolean enabled() {
        return safe(InventoryConfig.CARRY_WEIGHT, true);
    }

    private static double safe(net.minecraftforge.common.ForgeConfigSpec.DoubleValue v, double fallback) {
        try {
            return v.get();
        } catch (IllegalStateException e) { // config not loaded yet
            return fallback;
        }
    }

    private static boolean safe(net.minecraftforge.common.ForgeConfigSpec.BooleanValue v, boolean fallback) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        State state = STATES.computeIfAbsent(player.getUUID(), k -> new State());
        long now = player.level().getGameTime();

        if (now % CHECK_INTERVAL == 0 || !state.sent) update(player, state, now);

        if (state.over) {
            // You can't run while over-encumbered.
            if (player.isSprinting()) {
                player.setSprinting(false);
                if (now - state.lastMessage > MESSAGE_COOLDOWN) {
                    state.lastMessage = now;
                    Notifier.message(player, Component.translatable("inventory.skycraft.overencumbered"));
                }
            }
        }
    }

    private static void update(ServerPlayer player, State state, long now) {
        boolean enabled = enabled();
        float cur = current(player);
        float cap = capacity(player);
        boolean over = enabled && !player.isCreative() && !player.isSpectator() && cur > cap;

        applySpeed(player, over);
        if (over && !state.over && now - state.lastMessage > MESSAGE_COOLDOWN) {
            state.lastMessage = now;
            Notifier.message(player, Component.translatable("inventory.skycraft.overencumbered"));
        }

        float curR = Math.round(cur * 10f) / 10f;
        float capR = Math.round(cap * 10f) / 10f;
        boolean changed = !state.sent || state.over != over || state.enabled != enabled
                || Math.abs(state.current - curR) > 0.05f || Math.abs(state.capacity - capR) > 0.05f;
        state.current = curR;
        state.capacity = capR;
        state.over = over;
        state.enabled = enabled;
        if (changed) {
            state.sent = true;
            SkyNetwork.sendToPlayer(player, new InventoryPackets.CarryState(curR, capR, enabled, over));
        }
    }

    private static void applySpeed(ServerPlayer player, boolean over) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        double penalty = -safe(InventoryConfig.ENCUMBERED_SPEED_PENALTY, 0.2);
        AttributeModifier existing = speed.getModifier(SPEED_MOD);
        if (over && penalty != 0) {
            if (existing != null && existing.getAmount() == penalty) return;
            if (existing != null) speed.removeModifier(SPEED_MOD);
            speed.addTransientModifier(new AttributeModifier(SPEED_MOD, "Skycraft over-encumbered", penalty, AttributeModifier.Operation.MULTIPLY_TOTAL));
        } else if (existing != null) {
            speed.removeModifier(SPEED_MOD);
        }
    }

    /** Forces a re-check and a fresh sync on the player's next tick. */
    static void invalidate(Player player) {
        State s = STATES.get(player.getUUID());
        if (s != null) s.sent = false;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        invalidate(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        invalidate(event.getEntity());
    }
}
