package com.skycraft.vitals;

import com.skycraft.combat.RespawnPoints;
import com.skycraft.combat.Sheathe;
import com.skycraft.combat.WeaponClass;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.network.CorePackets.Action;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side state for keybind actions sent by clients (power attacks, weapon blocking, racial power, climbing, dodge, unstuck). */
public final class ActionHandler {
    private static final Map<UUID, Long> POWER_ATTACK_ARMED = new HashMap<>();
    private static final Set<UUID> BLOCKING = new HashSet<>();
    /** Last accepted CLIMB_TICK per player: {game time, y}. */
    private static final Map<UUID, double[]> LAST_CLIMB = new HashMap<>();

    private ActionHandler() {}

    public static void handle(ServerPlayer player, int action, int arg) {
        switch (action) {
            case Action.POWER_ATTACK -> POWER_ATTACK_ARMED.put(player.getUUID(), player.level().getGameTime() + 10);
            case Action.BLOCK_START -> {
                ItemStack main = player.getMainHandItem();
                ItemStack off = player.getOffhandItem();
                boolean dual = !main.isEmpty() && !off.isEmpty()
                        && WeaponClass.of(main).skill == Skill.ONE_HANDED
                        && WeaponClass.of(off).skill == Skill.ONE_HANDED;
                if (!dual) BLOCKING.add(player.getUUID());
            }
            case Action.BLOCK_STOP -> BLOCKING.remove(player.getUUID());
            case Action.USE_POWER -> RacePowers.use(player);
            case Action.MAKE_LEGENDARY -> {
                if (arg >= 0 && arg < Skill.VALUES.length) Progression.makeLegendary(player, Skill.VALUES[arg]);
            }
            case Action.SHEATHE_TOGGLE -> Sheathe.toggle(player);
            case Action.DODGE_ROLL -> handleDodgeRoll(player);
            case Action.CLIMB_TICK -> handleClimbTick(player);
            case Action.UNSTUCK -> handleUnstuck(player);
            case Action.TAKE_WORLD_ITEM -> handleTakeWorldItem(player, arg);
            case Action.SHIELD_BASH -> handleShieldBash(player);
            default -> {
            }
        }
    }

    private static void handleShieldBash(ServerPlayer player) {
        if (!Vitals.consumeStamina(player, 15f, false)) return;
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.SHIELD_BLOCK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.9f);
        net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0f);
        net.minecraft.world.phys.AABB box = player.getBoundingBox().expandTowards(look.scale(3.0)).inflate(1.0);
        for (net.minecraft.world.entity.LivingEntity target : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box, e -> e != player && e.isAlive())) {
            if (player.distanceTo(target) < 3.0) {
                float bashDmg = 4.0f + (com.skycraft.perk.Perks.has(player, "block.deadly_bash") ? 6.0f : 0f);
                // A bash is not a weapon swing: no swing stamina, multipliers or weapon XP.
                com.skycraft.combat.CombatHandler.secondaryHurt(target, player.damageSources().playerAttack(player), bashDmg);
                target.knockback(0.6, -look.x, -look.z);
                break;
            }
        }
    }

    private static void handleTakeWorldItem(ServerPlayer player, int entityId) {
        net.minecraft.world.entity.Entity entity = player.serverLevel().getEntity(entityId);
        if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) || !itemEntity.isAlive()) return;
        if (player.distanceToSqr(itemEntity) > 25.0) return; // within 5 blocks

        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return;

        // Check if item is currency (Septims)
        long coinVal = com.skycraft.core.Currency.valueOf(stack);
        if (coinVal > 0) {
            com.skycraft.core.Currency.give(player, coinVal);
            player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 1.2f);
            itemEntity.discard();
            return;
        }

        // Check ownership & theft
        boolean owned = com.skycraft.crime.Ownership.isOwnedByOther(player, itemEntity);
        if (owned) {
            com.skycraft.crime.Bounty.markStolen(stack);
            com.skycraft.crime.Bounty.increment(player, "items_stolen", stack.getCount());
            net.minecraft.world.entity.LivingEntity witness = com.skycraft.crime.Crimes.findWitness(player, null);
            if (witness != null) {
                int bounty = (int) Math.max(com.skycraft.crime.Bounty.MIN_THEFT,
                        Math.min(100000, com.skycraft.economy.ItemValues.get(stack) * stack.getCount() / 2));
                com.skycraft.crime.Theft.handleWitnessedTheft(player, witness, itemEntity.blockPosition(), bounty);
            }
        }

        // Put item into storage slots (slots 9-35) or non-active slots so it NEVER equips into the active hand!
        int origCount = stack.getCount();
        addToBags(player, stack);

        if (stack.getCount() < origCount) {
            player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.1f);
            if (stack.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(stack);
            }
            player.inventoryMenu.broadcastChanges();
        }
    }

    /**
     * Routes items directly into backpack slots (9..35) or inactive hotbar slots.
     * Auto-deposits currency directly into the player's gold wallet.
     * Never equips into the player's active held hand slot unless completely full.
     */
    public static boolean addToBags(Player player, ItemStack stack) {
        return addToBags(player, stack, true);
    }

    /**
     * @param walletCoins false keeps coins as items (taken from a container: they become gold when the menu closes,
     *                    see InventoryEvents, so theft detection sees them leave the container)
     */
    public static boolean addToBags(Player player, ItemStack stack, boolean walletCoins) {
        if (stack.isEmpty()) return true;

        // Auto-deposit currency directly into gold wallet
        long coinVal = walletCoins ? com.skycraft.core.Currency.valueOf(stack) : 0;
        if (coinVal > 0) {
            com.skycraft.core.Currency.give(player, coinVal);
            stack.setCount(0);
            return true;
        }

        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        int activeSlot = inv.selected;

        // 1. Try to merge into existing matching stacks in storage (9..35)
        for (int i = 9; i < 36; i++) {
            ItemStack inSlot = inv.getItem(i);
            if (ItemStack.isSameItemSameTags(inSlot, stack)) {
                int space = inSlot.getMaxStackSize() - inSlot.getCount();
                if (space > 0) {
                    int move = Math.min(space, stack.getCount());
                    inSlot.grow(move);
                    stack.shrink(move);
                    if (stack.isEmpty()) return true;
                }
            }
        }

        // 2. Try to merge into existing matching stacks in hotbar (except active slot)
        for (int i = 0; i < 9; i++) {
            if (i == activeSlot) continue;
            ItemStack inSlot = inv.getItem(i);
            if (ItemStack.isSameItemSameTags(inSlot, stack)) {
                int space = inSlot.getMaxStackSize() - inSlot.getCount();
                if (space > 0) {
                    int move = Math.min(space, stack.getCount());
                    inSlot.grow(move);
                    stack.shrink(move);
                    if (stack.isEmpty()) return true;
                }
            }
        }

        // 3. Try empty storage slots (9..35)
        if (!stack.isEmpty()) {
            for (int i = 9; i < 36; i++) {
                if (inv.getItem(i).isEmpty()) {
                    inv.setItem(i, stack.copy());
                    stack.setCount(0);
                    return true;
                }
            }
        }

        // 4. Try empty hotbar slots (except active slot)
        if (!stack.isEmpty()) {
            for (int i = 0; i < 9; i++) {
                if (i == activeSlot) continue;
                if (inv.getItem(i).isEmpty()) {
                    inv.setItem(i, stack.copy());
                    stack.setCount(0);
                    return true;
                }
            }
        }

        // 5. Fallback only if all other 35 slots are completely full
        if (!stack.isEmpty()) {
            return inv.add(stack);
        }
        return true;
    }

    private static void handleDodgeRoll(ServerPlayer player) {
        if (!Perks.has(player, "athletics.dodge_roll")) return;
        if (Vitals.consumeStamina(player, 15f, false)) {
            player.invulnerableTime = 12; // ~0.6s i-frames
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8f, 1.4f);
            Progression.addSkillXp(player, Skill.ATHLETICS, 1.5f);
        }
    }

    /**
     * The client sends CLIMB_TICK every 5 ticks while clinging to a wall. The server rate-limits it, checks the
     * player is really against a sturdy wall face, and only cancels fall damage for a continuing climb that isn't
     * dropping fast (so spamming the packet while falling does nothing).
     */
    private static void handleClimbTick(ServerPlayer player) {
        if (player.isSpectator() || player.isPassenger() || player.isInWater()) return;
        long now = player.level().getGameTime();
        double[] last = LAST_CLIMB.get(player.getUUID());
        if (last != null && now - (long) last[0] < 3) return; // rate limit
        if (!againstWall(player)) {
            LAST_CLIMB.remove(player.getUUID());
            return;
        }
        LAST_CLIMB.put(player.getUUID(), new double[]{now, player.getY()});
        boolean continuing = last != null && now - (long) last[0] <= 12 && player.getY() >= last[1] - 1.0;
        float cost = Perks.has(player, "athletics.climber") ? 0.8f : 1.2f;
        if (Vitals.consumeStamina(player, cost, false)) {
            Progression.addSkillXp(player, Skill.ATHLETICS, 0.15f);
            if (continuing) player.resetFallDistance();
        } else {
            Vitals.isExhausted(player);
        }
    }

    /** True if a sturdy block face is right next to the player's body (feet or head level). */
    private static boolean againstWall(ServerPlayer player) {
        var level = player.level();
        var box = player.getBoundingBox();
        BlockPos feet = player.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            // distance from the body's side to the neighbouring block column on that side
            double gap = switch (dir) {
                case NORTH -> box.minZ - feet.getZ();
                case SOUTH -> feet.getZ() + 1 - box.maxZ;
                case WEST -> box.minX - feet.getX();
                default -> feet.getX() + 1 - box.maxX;
            };
            if (gap > 0.45) continue;
            for (BlockPos p : new BlockPos[]{feet.relative(dir), feet.above().relative(dir)}) {
                if (level.getBlockState(p).isFaceSturdy(level, p, dir.getOpposite())) return true;
            }
        }
        return false;
    }

    private static void handleUnstuck(ServerPlayer player) {
        if (Vitals.inCombat(player)) {
            Notifier.message(player, Component.translatable("message.skycraft.unstuck_combat"));
            return;
        }
        PlayerData data = SkyData.get(player);
        long now = player.level().getGameTime();
        long next = data.module("unstuck").getLong("cooldown");
        if (now < next) {
            long sec = (next - now) / 20;
            Notifier.message(player, Component.translatable("message.skycraft.unstuck_cooldown", sec));
            return;
        }
        data.module("unstuck").putLong("cooldown", now + 6000); // 5 minutes
        data.markDirty();
        RespawnPoints.teleportToRestPoint(player);
    }

    /** Consumes the "power attack" flag the client sets while the power-attack key is held during a swing. */
    public static boolean consumePowerAttack(ServerPlayer player) {
        Long until = POWER_ATTACK_ARMED.remove(player.getUUID());
        return until != null && until >= player.level().getGameTime();
    }

    public static boolean isWeaponBlocking(ServerPlayer player) {
        return BLOCKING.contains(player.getUUID());
    }

    public static void forget(UUID player) {
        POWER_ATTACK_ARMED.remove(player);
        BLOCKING.remove(player);
        LAST_CLIMB.remove(player);
    }

    // ------------------------------------------------------------------ Skyrim container screen (take / store)

    /**
     * Number of container slots at the start of the menu if it is a plain storage container (chests, barrels,
     * corpses and followers use ChestMenu; shulker boxes, hoppers, dispensers/droppers), else -1. Other menus
     * (furnaces, crafting stations, modded machines) have slot rules this shortcut must not bypass.
     */
    private static int storageSlots(ServerPlayer player, int containerId) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || menu.containerId != containerId || !menu.stillValid(player)) return -1;
        int count;
        if (menu instanceof ChestMenu cm) count = cm.getRowCount() * 9;
        else if (menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu) count = menu.slots.size() - 36;
        else return -1;
        return count > 0 && count <= menu.slots.size() ? count : -1;
    }

    public static void handleContainerTake(ServerPlayer player, int containerId, int slotIndex, boolean all) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        int containerSlotCount = storageSlots(player, containerId);
        if (containerSlotCount <= 0) return;
        AbstractContainerMenu menu = player.containerMenu;

        boolean takenAny = false;
        if (all) {
            // a mount's gear slots (saddle, barding) are taken one at a time, never by "take all"
            for (int i = menu instanceof com.skycraft.mounts.SaddlebagMenu ? com.skycraft.mounts.Saddlebags.GEAR : 0; i < containerSlotCount; i++) {
                takenAny |= takeSlot(player, menu.getSlot(i));
            }
        } else if (slotIndex >= 0 && slotIndex < containerSlotCount) {
            takenAny = takeSlot(player, menu.getSlot(slotIndex));
        }

        if (takenAny) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
            menu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            player.getInventory().setChanged();
        }
    }

    /** Takes a container slot into the bags through the slot's own rules (mayPickup / onTake); leftovers go back. */
    private static boolean takeSlot(ServerPlayer player, Slot slot) {
        if (slot == null || !slot.hasItem() || !slot.mayPickup(player)) return false;
        ItemStack taken = slot.safeTake(slot.getItem().getCount(), Integer.MAX_VALUE, player);
        if (taken.isEmpty()) return false;
        int before = taken.getCount();
        addToBags(player, taken, false);
        boolean moved = taken.getCount() < before;
        if (!taken.isEmpty()) {
            ItemStack rest = slot.safeInsert(taken);
            if (!rest.isEmpty()) player.getInventory().placeItemBackInInventory(rest);
        }
        return moved;
    }

    public static void handleContainerStore(ServerPlayer player, int containerId, int slotIndex, boolean all) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        int containerSlotCount = storageSlots(player, containerId);
        if (containerSlotCount <= 0) return;
        AbstractContainerMenu menu = player.containerMenu;

        boolean storedAny = false;
        if (all) {
            for (int i = containerSlotCount; i < menu.slots.size(); i++) {
                Slot pSlot = menu.getSlot(i);
                // Don't store actively held mainhand or offhand item when storing all
                if (pSlot.getItem() == player.getMainHandItem() || pSlot.getItem() == player.getOffhandItem()) continue;
                // a mount's saddle and barding are equipped one at a time, never swapped in by "store all"
                if (menu instanceof com.skycraft.mounts.SaddlebagMenu bm && bm.isGear(pSlot.getItem())) continue;
                storedAny |= storeSlot(player, menu, containerSlotCount, pSlot);
            }
        } else if (slotIndex >= containerSlotCount && slotIndex < menu.slots.size()) {
            Slot pSlot = menu.getSlot(slotIndex);
            if (menu instanceof com.skycraft.mounts.SaddlebagMenu bm && bm.isGear(pSlot.getItem())) {
                storedAny = bm.equipFrom(player, pSlot); // "store" is "equip" for the mount's tack
            } else {
                storedAny = storeSlot(player, menu, containerSlotCount, pSlot);
            }
        }

        if (storedAny) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 0.9f);
            menu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            player.getInventory().setChanged();
        }
    }

    /** Moves a player-inventory slot of the menu into the container slots; whatever doesn't fit stays put. */
    private static boolean storeSlot(ServerPlayer player, AbstractContainerMenu menu, int containerSlotCount, Slot pSlot) {
        if (pSlot == null || !pSlot.hasItem() || !pSlot.mayPickup(player)) return false;
        ItemStack moving = pSlot.safeTake(pSlot.getItem().getCount(), Integer.MAX_VALUE, player);
        if (moving.isEmpty()) return false;
        int before = moving.getCount();
        storeIntoContainerSlots(menu, containerSlotCount, moving);
        boolean stored = moving.getCount() < before;
        if (!moving.isEmpty()) {
            ItemStack rest = pSlot.safeInsert(moving);
            if (!rest.isEmpty()) player.getInventory().placeItemBackInInventory(rest);
        }
        return stored;
    }

    /** Inserts through each slot's own rules (mayPlace, max stack size), merging first. Shrinks {@code stack}. */
    private static void storeIntoContainerSlots(AbstractContainerMenu menu, int containerSlotCount, ItemStack stack) {
        // 1. Merge into matching existing stacks in container
        for (int i = 0; i < containerSlotCount && !stack.isEmpty(); i++) {
            Slot s = menu.getSlot(i);
            if (s.hasItem() && ItemStack.isSameItemSameTags(s.getItem(), stack)) s.safeInsert(stack);
        }
        // 2. Put into empty container slots
        for (int i = 0; i < containerSlotCount && !stack.isEmpty(); i++) {
            Slot s = menu.getSlot(i);
            if (!s.hasItem()) s.safeInsert(stack);
        }
    }
}
