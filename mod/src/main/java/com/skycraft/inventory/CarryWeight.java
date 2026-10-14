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

    /** Where a character's carry capacity comes from (the inventory screen lists these). */
    public record CapacityParts(float base, float stamina, float race, float perks, float bonus, float mount) {
        public float total() {
            return base + stamina + race + perks + bonus + mount;
        }
    }

    /**
     * Base + 5 per Stamina level-up + the people's build ({@link #raceBonus}) + carry perks (Athletics: Strong Back,
     * Pack Mule, Beast of Burden) + enchantment/potion bonuses + the mount you steer (server side only).
     */
    public static CapacityParts parts(Player player) {
        PlayerData data = SkyData.get(player);
        double base = safe(InventoryConfig.BASE_CAPACITY, 300.0);
        double perStamina = safe(InventoryConfig.CAPACITY_PER_STAMINA, 5.0);
        float perks = PERK_STRONG_BACK * data.getPerkRank("athletics.strong_back") + PERK_PACK_MULE * data.getPerkRank("athletics.pack_mule")
                + PERK_BEAST * data.getPerkRank("athletics.beast_of_burden");
        // the mount you steer carries part of the load (kind and saddle: com.skycraft.mounts.MountGear)
        return new CapacityParts((float) base, (float) (perStamina * data.getStaminaPoints()), raceBonus(data.getRace()), perks,
                data.module("bonus").getFloat("carry"), com.skycraft.mounts.MountGear.carryBonus(player));
    }

    /** Carry weight per rank of the Athletics carry perks. */
    public static final float PERK_STRONG_BACK = 25f, PERK_PACK_MULE = 50f, PERK_BEAST = 100f;

    /** How much the player can carry before becoming over-encumbered. */
    public static float capacity(Player player) {
        return parts(player).total();
    }

    /**
     * Carry-weight bonus of a people (Skyrim: the strong carry more). Orsimer, Nord and Redguard are higher (and the
     * big folk of Akavir and Atmora); Altmer, Bosmer, Khajiit and the lithe ones lower; Imperial, Breton, Dunmer and
     * Argonian are about average.
     */
    public static float raceBonus(com.skycraft.core.Race race) {
        if (race == null) return 0f;
        return switch (race) {
            case ORSIMER -> 60f;
            case NORD, KA_PO_TUN -> 50f;
            case REDGUARD, IMGA, ATMORAN -> 40f;
            case TSAESCI -> 30f;
            case IMPERIAL, ARGONIAN -> 10f;
            case DUNMER -> 5f;
            case ALTMER -> -20f;
            case BOSMER, LILMOTHIIT, TANG_MO -> -15f;
            case KHAJIIT, OHMES_RAHT -> -10f;
            default -> 0f;
        };
    }

    /** Total weight of the main inventory, worn armor, offhand and the pack. */
    public static float current(Player player) {
        Inventory inv = player.getInventory();
        float sum = 0f;
        sum += sum(inv.items);
        sum += sum(inv.armor);
        sum += sum(inv.offhand);
        sum += Pack.weight(player); // the overflow pack weighs exactly like slots do: weight is the only limit
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
            if (s != null && s.current >= 0) return enabled() && s.current > s.capacity;
            return enabled() && current(player) > capacity(player);
        }
        return enabled() && current(player) > capacity(player);
    }

    /** Overweight beyond this ratio (of capacity) and you can no longer run (below it you are only slower). */
    public static final float RUN_LIMIT = 0.10f;
    /** The slowest an over-encumbered character moves: none, reached at +100% of capacity (drop things). */
    public static final float MIN_SPEED = 0f;

    /**
     * Movement speed as a fraction of normal: 1.0 at or under capacity, then falling smoothly with the overweight
     * ratio r = (current - capacity) / capacity: {@code max(0, 1 - r)}. Nothing is ever refused for weight;
     * you just get slower and slower.
     */
    public static float speedFactor(float current, float capacity) {
        if (capacity <= 0f || current <= capacity) return 1f;
        float r = (current - capacity) / capacity;
        return Math.max(MIN_SPEED, 1f - r);
    }

    /**
     * The attribute modifier (MULTIPLY_TOTAL amount) of a speed factor: never -1, so the movement attribute is never
     * exactly zero (swimming and riding maths stay sane) yet far too small to walk; the inventory stays usable to drop.
     */
    static double speedModifier(float factor) {
        return Math.round((Math.max(factor, ATTRIBUTE_EPSILON) - 1.0) * 100.0) / 100.0;
    }

    private static final float ATTRIBUTE_EPSILON = 0.02f;
    /** Below this speed factor jumping shrinks in proportion (and is gone at 0): a loaded-down body cannot leap. */
    private static final float JUMP_LIMIT = 0.5f;

    /** Lowers the jump of a player too loaded down to leap (both sides: the client moves itself). */
    @SubscribeEvent
    public static void onJump(net.minecraftforge.event.entity.living.LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        float factor = speedFactor(player);
        if (factor >= JUMP_LIMIT) return;
        var dm = player.getDeltaMovement();
        player.setDeltaMovement(dm.x, dm.y * (factor / JUMP_LIMIT), dm.z);
    }

    /** {@link #speedFactor(float, float)} of a player (1.0 for creative/spectator or with carry weight off). */
    public static float speedFactor(Player player) {
        if (player == null || player.isCreative() || player.isSpectator() || !enabled()) return 1f;
        if (!player.level().isClientSide) {
            State s = STATES.get(player.getUUID());
            if (s != null && s.current >= 0) return speedFactor(s.current, s.capacity);
        }
        return speedFactor(current(player), capacity(player));
    }

    /**
     * How far over capacity the player is, as a fraction of capacity (0 when at or under it, 1.0 at double). Uses the
     * server's cached state, so it is cheap to call every tick (mounts read it).
     */
    public static float overweightRatio(Player player) {
        if (player == null || player.isCreative() || player.isSpectator() || !enabled()) return 0f;
        float cur, cap;
        State s = player.level().isClientSide ? null : STATES.get(player.getUUID());
        if (s != null && s.current >= 0) {
            cur = s.current;
            cap = s.capacity;
        } else {
            cur = current(player);
            cap = capacity(player);
        }
        return cap <= 0f || cur <= cap ? 0f : (cur - cap) / cap;
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
        boolean heavy = enabled && !player.isCreative() && !player.isSpectator() && cur > cap;
        // "over" = too heavy to run (more than RUN_LIMIT above capacity); between capacity and that you are only slower
        boolean over = heavy && cap > 0f && (cur - cap) / cap > RUN_LIMIT;

        applySpeed(player, heavy ? speedFactor(cur, cap) : 1f);
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

    /** Graded encumbrance: the movement-speed modifier is {@code factor - 1} (0 = no modifier at all). */
    private static void applySpeed(ServerPlayer player, float factor) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        double penalty = speedModifier(factor);
        AttributeModifier existing = speed.getModifier(SPEED_MOD);
        if (penalty < 0) {
            if (existing != null && existing.getAmount() == penalty) return;
            if (existing != null) speed.removeModifier(SPEED_MOD);
            speed.addTransientModifier(new AttributeModifier(SPEED_MOD, "Skycraft over-encumbered", penalty, AttributeModifier.Operation.MULTIPLY_TOTAL));
        } else if (existing != null) {
            speed.removeModifier(SPEED_MOD);
        }
    }

    /** Forces a re-check and a fresh sync on the player's next tick. */
    public static void invalidate(Player player) {
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
