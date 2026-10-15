package com.skycraft.crime;

import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.NotifyKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public crime API. All state lives in {@code SkyData.get(player).module("crime")} (synced to the owning client):
 * <ul>
 *     <li>{@code bounty}: compound hold id -> int</li>
 *     <li>{@code murders}, {@code assaults}, {@code items_stolen}, {@code pickpockets}: ints (read by other modules)</li>
 *     <li>{@code violent}: compound hold id -> 1 when the hold's bounty includes a murder</li>
 *     <li>{@code hostile_until}: game time until which guards attack on sight (resisting arrest / assault)</li>
 *     <li>{@code jail}: compound present while serving a sentence (see {@link Jail})</li>
 *     <li>{@code unlocked}: legacy list of picked locks, migrated into the world lock data on first use (see {@link Locks})</li>
 * </ul>
 */
public final class Bounty {
    public static final String MODULE = "crime";
    public static final String STOLEN_TAG = "skycraft_stolen";

    // Skyrim crime gold values
    public static final int PICKPOCKET = 25;
    public static final int ASSAULT = 40;
    public static final int MURDER = 1000;
    public static final int TRESPASS = 5;
    public static final int ESCAPE = 100;
    public static final int MIN_THEFT = 5;
    /** At or above this bounty in a hold, its guards attack on sight. */
    public static final int KILL_ON_SIGHT = 1000;

    private Bounty() {}

    public static CompoundTag state(Player player) {
        CompoundTag state = SkyData.get(player).module(MODULE);
        if (state.getInt("hold_ids") < 2 && !player.level().isClientSide) migrateHoldIds(player, state);
        return state;
    }

    /**
     * Once per player: re-keys bounties saved under the pre-Tamriel hold ids ({@code "whiterun"}, {@code "whiterun_2"})
     * to region ids ({@code "skyrim/whiterun"}). Bounties of the old repeated tiles add up into their hold.
     */
    private static void migrateHoldIds(Player player, CompoundTag state) {
        state.putInt("hold_ids", 2);
        for (String key : new String[]{"bounty", "violent"}) {
            if (!state.contains(key, Tag.TAG_COMPOUND)) continue;
            CompoundTag old = state.getCompound(key);
            CompoundTag moved = new CompoundTag();
            for (String hold : old.getAllKeys()) {
                String id = Holds.migrate(hold);
                if (key.equals("bounty")) moved.putInt(id, moved.getInt(id) + old.getInt(hold));
                else moved.putBoolean(id, moved.getBoolean(id) || old.getBoolean(hold));
            }
            state.put(key, moved);
        }
        for (String k : new java.util.ArrayList<>(state.getAllKeys())) {
            if (!k.startsWith("persuade_")) continue;
            String id = Holds.migrate(k.substring("persuade_".length()));
            int v = state.getInt(k);
            state.remove(k);
            state.putInt("persuade_" + id, v);
        }
        if (state.contains("jail", Tag.TAG_COMPOUND)) {
            CompoundTag jail = state.getCompound("jail");
            jail.putString("hold", Holds.migrate(jail.getString("hold")));
        }
        SkyData.get(player).markDirty();
    }

    private static CompoundTag bounties(Player player) {
        CompoundTag state = state(player);
        if (!state.contains("bounty", Tag.TAG_COMPOUND)) state.put("bounty", new CompoundTag());
        return state.getCompound("bounty");
    }

    /** Bounty in one hold (0 if none). */
    public static int get(Player player, String hold) {
        CompoundTag state = state(player);
        return state.contains("bounty", Tag.TAG_COMPOUND) ? state.getCompound("bounty").getInt(hold) : 0;
    }

    /** Bounty in the hold the player is standing in. */
    public static int current(Player player) {
        return get(player, Holds.holdAt(player.level(), player.blockPosition()));
    }

    /** Sum of the bounties in every hold. */
    public static int total(Player player) {
        int sum = 0;
        for (int v : all(player).values()) sum += v;
        return sum;
    }

    /** Every hold with a bounty, hold id -> gold. */
    public static Map<String, Integer> all(Player player) {
        Map<String, Integer> out = new LinkedHashMap<>();
        CompoundTag state = state(player);
        if (!state.contains("bounty", Tag.TAG_COMPOUND)) return out;
        CompoundTag b = state.getCompound("bounty");
        for (String k : b.getAllKeys()) {
            int v = b.getInt(k);
            if (v > 0) out.put(k, v);
        }
        return out;
    }

    /** Sets a hold's bounty silently (no notification). */
    public static void set(Player player, String hold, int amount) {
        PlayerData data = SkyData.get(player);
        CompoundTag b = bounties(player);
        if (amount <= 0) {
            b.remove(hold);
            CompoundTag state = state(player);
            if (state.contains("violent", Tag.TAG_COMPOUND)) state.getCompound("violent").remove(hold);
            state.remove("persuade_" + hold);
        } else {
            b.putInt(hold, amount);
        }
        data.markDirty();
    }

    /** Adds a bounty in {@code hold} and shows the red "Bounty added" message. */
    public static void add(ServerPlayer player, String hold, int amount) {
        if (amount <= 0) return;
        set(player, hold, get(player, hold) + amount);
        Notifier.send(player, NotifyKind.CRIME,
                Component.translatable("crime.skycraft.bounty_added", amount, Holds.displayName(hold)), Component.empty());
    }

    /** Clears a hold's bounty, and makes nearby guards stand down. */
    public static void clear(Player player, String hold) {
        set(player, hold, 0);
        CompoundTag state = state(player);
        state.remove("hostile_until");
        SkyData.get(player).markDirty();
        if (player instanceof ServerPlayer sp) Guards.pacify(sp);
    }

    public static void clearAll(Player player) {
        for (String hold : all(player).keySet()) set(player, hold, 0);
        state(player).remove("hostile_until");
        SkyData.get(player).markDirty();
        if (player instanceof ServerPlayer sp) Guards.pacify(sp);
    }

    /** Whether the hold's bounty includes a murder (persuasion can't talk you out of those). */
    public static boolean violent(Player player, String hold) {
        CompoundTag state = state(player);
        return state.contains("violent", Tag.TAG_COMPOUND) && state.getCompound("violent").getBoolean(hold);
    }

    static void markViolent(Player player, String hold) {
        CompoundTag state = state(player);
        if (!state.contains("violent", Tag.TAG_COMPOUND)) state.put("violent", new CompoundTag());
        state.getCompound("violent").putBoolean(hold, true);
        SkyData.get(player).markDirty();
    }

    // ------------------------------------------------------------------ counters

    public static int count(Player player, String key) {
        return state(player).getInt(key);
    }

    public static void increment(Player player, String key, int amount) {
        CompoundTag state = state(player);
        state.putInt(key, state.getInt(key) + amount);
        SkyData.get(player).markDirty();
    }

    // ------------------------------------------------------------------ hostility

    /** Guards attack on sight until this game time (resisting arrest, fresh assault). */
    public static boolean isHostile(Player player) {
        return state(player).getLong("hostile_until") > player.level().getGameTime();
    }

    public static void makeHostile(Player player, int ticks) {
        CompoundTag state = state(player);
        long until = player.level().getGameTime() + ticks;
        if (state.getLong("hostile_until") < until) state.putLong("hostile_until", until);
        SkyData.get(player).markDirty();
    }

    public static void clearHostile(Player player) {
        CompoundTag state = state(player);
        state.remove("hostile_until");
        SkyData.get(player).markDirty();
    }

    // ------------------------------------------------------------------ stolen goods

    /** Whether the stack is stolen goods. A quest item never is, even one that carries the tag: it is never confiscated. */
    public static boolean isStolen(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && stack.getTag() != null && stack.getTag().getBoolean(STOLEN_TAG)
                && !com.skycraft.quest.QuestItems.isQuestItem(stack);
    }

    public static void markStolen(net.minecraft.world.item.ItemStack stack) {
        if (!stack.isEmpty() && !com.skycraft.quest.QuestItems.isQuestItem(stack)) stack.getOrCreateTag().putBoolean(STOLEN_TAG, true);
    }

    /** Removes every stolen item from the player's inventory. Returns how many items were taken. */
    public static int confiscate(Player player) {
        return confiscate(player, Holds.holdAt(player.level(), player.blockPosition()));
    }

    /** Removes every stolen item from the player's inventory and routes them to the hold's jail confiscated barrel. */
    public static int confiscate(Player player, String hold) {
        var inv = player.getInventory();
        int taken = 0;
        String jailKey = JailEvidence.DIM;
        if (player instanceof ServerPlayer sp) {
            com.skycraft.tamriel.Place place = JailLoading.placeFor(hold, sp.blockPosition());
            if (place != null) jailKey = place.id();
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (isStolen(stack)) {
                taken += stack.getCount();
                inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                if (player instanceof ServerPlayer sp) {
                    JailEvidence.confiscate(sp, jailKey, stack);
                }
            }
        }
        for (int i = com.skycraft.inventory.Pack.size(player) - 1; i >= 0; i--) {
            var stack = com.skycraft.inventory.Pack.get(player, i);
            if (isStolen(stack)) {
                var removed = com.skycraft.inventory.Pack.take(player, i, Integer.MAX_VALUE);
                if (!removed.isEmpty()) {
                    taken += removed.getCount();
                    if (player instanceof ServerPlayer sp) {
                        JailEvidence.confiscate(sp, jailKey, removed);
                    }
                }
            }
        }
        if (taken > 0 && player instanceof ServerPlayer sp) {
            Notifier.send(sp, NotifyKind.CRIME, Component.translatable("crime.skycraft.confiscated", taken), Component.empty());
        }
        return taken;
    }
}
