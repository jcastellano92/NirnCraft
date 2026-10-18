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

/** Server-side state for keybind actions sent by clients (power attacks, weapon blocking, climbing, dodge, unstuck). */
public final class ActionHandler {
    private static final Map<UUID, Long> POWER_ATTACK_ARMED = new HashMap<>();
    private static final Set<UUID> BLOCKING = new HashSet<>();
    /** Last accepted CLIMB_TICK per player: {game time, y}. */
    private static final Map<UUID, double[]> LAST_CLIMB = new HashMap<>();
    /** Last accepted DODGE_ROLL game time per player. */
    private static final Map<UUID, Long> LAST_DODGE = new HashMap<>();
    /** Last accepted SHIELD_BASH game time per player. */
    private static final Map<UUID, Long> LAST_BASH = new HashMap<>();

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
        LAST_BASH.put(player.getUUID(), player.level().getGameTime());
        net.minecraft.server.level.ServerLevel level = player.level();
        level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.SHIELD_BLOCK.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.9f);
        net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0f);
        net.minecraft.world.phys.AABB box = player.getBoundingBox().expandTowards(look.scale(3.0)).inflate(1.0);
        for (net.minecraft.world.entity.LivingEntity target : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box, e -> e != player && e.isAlive())) {
            if (player.distanceTo(target) < 3.0) {
                float bashDmg = 4.0f + (com.skycraft.perk.Perks.has(player, "block.deadly_bash") ? 6.0f : 0f);
                // A bash is not a weapon swing: no swing stamina, multipliers or weapon XP.
                com.skycraft.combat.CombatHandler.secondaryHurt(target, player.damageSources().playerAttack(player), bashDmg);
                target.knockback(0.6, -look.x, -look.z, player.damageSources().playerAttack(player), bashDmg);
                break;
            }
        }
    }

    private static void handleTakeWorldItem(ServerPlayer player, int entityId) {
        net.minecraft.world.entity.Entity entity = player.level().getEntity(entityId);
        if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) || !itemEntity.isAlive()) return;
        if (player.distanceToSqr(itemEntity) > 25.0) return; // within 5 blocks

        // grouped: the same item lying split in a pile (five apples as three + two) is one take
        ItemStack template = itemEntity.getItem().copy();
        takeOneWorldItem(player, itemEntity);
        if (template.isEmpty()) return;
        for (net.minecraft.world.entity.item.ItemEntity other : player.level().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, itemEntity.getBoundingBox().inflate(1.5),
                e -> e != itemEntity && e.isAlive() && player.distanceToSqr(e) <= 25.0
                        && ItemStack.isSameItemSameComponents(e.getItem(), template))) {
            takeOneWorldItem(player, other);
        }
    }

    private static void takeOneWorldItem(ServerPlayer player, net.minecraft.world.entity.item.ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return;

        // Check if item is currency (Septims)
        long coinVal = com.skycraft.core.Currency.valueOf(stack);
        if (coinVal > 0) {
            com.skycraft.core.Currency.give(player, coinVal);
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 1.2f);
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
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.1f);
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
        int activeSlot = inv.getSelectedSlot();

        // 1. Try to merge into existing matching stacks in storage (9..35)
        for (int i = 9; i < 36; i++) {
            ItemStack inSlot = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(inSlot, stack)) {
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
            if (ItemStack.isSameItemSameComponents(inSlot, stack)) {
                int space = inSlot.getMaxStackSize() - inSlot.getCount();
                if (space > 0) {
                    int move = Math.min(space, stack.getCount());
                    inSlot.grow(move);
                    stack.shrink(move);
                    if (stack.isEmpty()) return true;
                }
            }
        }

        // 3. Try empty storage slots (9..35), then 4. empty hotbar slots (except active slot): one max-size stack each
        // (a larger stack would be a corrupt, byte-truncated slot on disk)
        for (int i = 9; i < 36 && !stack.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) inv.setItem(i, stack.split(stack.getMaxStackSize()));
        }
        for (int i = 0; i < 9 && !stack.isEmpty(); i++) {
            if (i != activeSlot && inv.getItem(i).isEmpty()) inv.setItem(i, stack.split(stack.getMaxStackSize()));
        }

        // 5. Everything the 36 slots cannot take goes to the pack: weight is the only carrying limit (inventory.Pack).
        // Only the pack's engineering cap leaves a remainder (callers drop it at the player's feet).
        if (!stack.isEmpty()) {
            com.skycraft.inventory.Pack.add(player, stack);
        }
        return stack.isEmpty();
    }

    private static void handleDodgeRoll(ServerPlayer player) {
        if (!Perks.has(player, "athletics.dodge_roll")) return;
        if (Vitals.consumeStamina(player, 15f, false)) {
            LAST_DODGE.put(player.getUUID(), player.level().getGameTime());
            player.setInvulnerableTime(12); // ~0.6s i-frames
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
        if (com.skycraft.crime.Jail.isJailed(player) || com.skycraft.quest.story.IntroFlow.inIntro(player)) {
            Notifier.message(player, Component.translatable("message.skycraft.unstuck_blocked")); // no escaping a cell
            return;
        }
        PlayerData data = SkyData.get(player);
        long now = player.level().getGameTime();
        long next = data.module("unstuck").getLongOr("cooldown",0L);
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

    public static boolean isPowerAttacking(Player player) {
        Long until = POWER_ATTACK_ARMED.get(player.getUUID());
        return (until != null && until >= player.level().getGameTime()) || (player.isSprinting() && player.isSwinging());
    }

    public static boolean isClimbing(Player player) {
        double[] last = LAST_CLIMB.get(player.getUUID());
        return last != null && player.level().getGameTime() - (long) last[0] <= 10;
    }

    public static boolean isDodgeRolling(Player player) {
        Long last = LAST_DODGE.get(player.getUUID());
        return last != null && player.level().getGameTime() - last <= 12;
    }

    public static boolean isShieldBashing(Player player) {
        Long last = LAST_BASH.get(player.getUUID());
        return last != null && player.level().getGameTime() - last <= 10;
    }

    public static boolean isWeaponBlocking(ServerPlayer player) {
        return BLOCKING.contains(player.getUUID());
    }

    public static void forget(UUID player) {
        POWER_ATTACK_ARMED.remove(player);
        BLOCKING.remove(player);
        LAST_CLIMB.remove(player);
        LAST_DODGE.remove(player);
        LAST_BASH.remove(player);
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
        // the overflow behind the container's slots (inventory.ContainerOverflow), if it has one: slots first, then it
        com.skycraft.inventory.ContainerOverflow.Bound ov = com.skycraft.inventory.ContainerOverflow.of(player, menu);
        com.skycraft.inventory.BulkStore store = ov == null ? null : ov.store();

        boolean takenAny = false;
        if (all) {
            // a mount's gear slots (saddle, barding) are taken one at a time, never by "take all"
            for (int i = menu instanceof com.skycraft.mounts.SaddlebagMenu ? com.skycraft.mounts.Saddlebags.GEAR : 0; i < containerSlotCount; i++) {
                takenAny |= takeSlot(player, menu.getSlot(i));
            }
            if (store != null) takenAny |= takeOverflow(player, store, null);
        } else if (store != null && com.skycraft.inventory.ContainerOverflow.isOverflowSlot(slotIndex)) {
            // a row that only exists in the overflow
            ItemStack template = store.get(slotIndex - com.skycraft.inventory.ContainerOverflow.SLOT_BASE);
            if (!template.isEmpty()) takenAny |= takeGrouped(player, menu, containerSlotCount, store, template);
        } else if (slotIndex >= 0 && slotIndex < containerSlotCount) {
            // grouped: one request takes EVERY stack of that exact item (item + tags) in the container, however many
            // slots (and overflow rows) they sit in ("five apples in three slots": you take the apples, all five)
            Slot first = menu.getSlot(slotIndex);
            if (first.hasItem()) takenAny |= takeGrouped(player, menu, containerSlotCount, store, first.getItem().copy());
        }

        if (takenAny) {
            if (ov != null) {
                com.skycraft.inventory.ContainerOverflow.drain(ov.container(), ov.store());
                com.skycraft.inventory.ContainerSync.markDirty(player);
            }
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
            menu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            player.getInventory().setChanged();
        }
    }

    /** Takes every stack of {@code template} (slots, then the overflow) and says how many items that was. */
    private static boolean takeGrouped(ServerPlayer player, AbstractContainerMenu menu, int containerSlotCount,
                                       @org.jetbrains.annotations.Nullable com.skycraft.inventory.BulkStore store, ItemStack template) {
        Component name = template.getHoverName();
        long before = containerCount(menu, containerSlotCount, template) + overflowCount(store, template);
        boolean any = false;
        for (int i = 0; i < containerSlotCount; i++) {
            Slot s = menu.getSlot(i);
            if (s.hasItem() && ItemStack.isSameItemSameComponents(s.getItem(), template)) any |= takeSlot(player, s);
        }
        if (store != null) any |= takeOverflow(player, store, template);
        long took = before - containerCount(menu, containerSlotCount, template) - overflowCount(store, template);
        if (took > 0) Notifier.message(player, Component.translatable("inventory.skycraft.msg.took", (int) Math.min(Integer.MAX_VALUE, took), name));
        return any;
    }

    private static int containerCount(AbstractContainerMenu menu, int containerSlotCount, ItemStack template) {
        int n = 0;
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, template)) n += s.getCount();
        }
        return n;
    }

    private static long overflowCount(@org.jetbrains.annotations.Nullable com.skycraft.inventory.BulkStore store, ItemStack template) {
        if (store == null) return 0;
        int idx = store.indexOf(template);
        return idx < 0 ? 0 : store.countAt(idx);
    }

    /**
     * Takes the overflow rows equal to {@code template} (every row when null) into the bags; what the bags' caps refuse
     * goes back into the overflow, never to the ground.
     */
    private static boolean takeOverflow(ServerPlayer player, com.skycraft.inventory.BulkStore store, @org.jetbrains.annotations.Nullable ItemStack template) {
        boolean moved = false;
        if (template != null) {
            moved = moveOverflowRow(player, store, store.indexOf(template));
        } else {
            for (int i = store.size() - 1; i >= 0; i--) moved |= moveOverflowRow(player, store, i);
        }
        return moved;
    }

    private static boolean moveOverflowRow(ServerPlayer player, com.skycraft.inventory.BulkStore store, int index) {
        if (index < 0) return false;
        ItemStack all = store.take(index, Integer.MAX_VALUE);
        if (all.isEmpty()) return false;
        int before = all.getCount();
        addToBags(player, all, false);
        boolean moved = all.getCount() < before;
        if (!all.isEmpty()) store.add(all); // the bags' engineering cap: it stays in the container
        return moved;
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
            if (!rest.isEmpty()) com.skycraft.inventory.Pack.giveOrDrop(player, rest);
        }
        return moved;
    }

    public static void handleContainerStore(ServerPlayer player, int containerId, int slotIndex, boolean all) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        int containerSlotCount = storageSlots(player, containerId);
        if (containerSlotCount <= 0) return;
        AbstractContainerMenu menu = player.containerMenu;
        // slots first, then the container's overflow: a store never fails because the slots are full
        com.skycraft.inventory.ContainerOverflow.Bound ov = com.skycraft.inventory.ContainerOverflow.of(player, menu);
        com.skycraft.inventory.BulkStore store = ov == null ? null : ov.store();

        boolean storedAny = false;
        if (all) {
            for (int i = containerSlotCount; i < menu.slots.size(); i++) {
                Slot pSlot = menu.getSlot(i);
                // Don't store actively held mainhand or offhand item when storing all
                if (pSlot.getItem() == player.getMainHandItem() || pSlot.getItem() == player.getOffhandItem()) continue;
                // a mount's saddle and barding are equipped one at a time, never swapped in by "store all"
                if (menu instanceof com.skycraft.mounts.SaddlebagMenu bm && bm.isGear(pSlot.getItem())) continue;
                storedAny |= storeSlot(player, menu, containerSlotCount, ov, pSlot);
            }
            // the overflow pack goes too (last entry first: indexes below stay valid)
            for (int i = com.skycraft.inventory.Pack.size(player) - 1; i >= 0; i--) {
                if (menu instanceof com.skycraft.mounts.SaddlebagMenu bm && bm.isGear(com.skycraft.inventory.Pack.get(player, i))) continue;
                storedAny |= storePackEntry(player, menu, containerSlotCount, ov, i);
            }
        } else if (com.skycraft.inventory.Pack.isVirtual(slotIndex)
                || (slotIndex >= containerSlotCount && slotIndex < menu.slots.size())) {
            // grouped like Take: every stack of that exact item (item + tags) goes, slots and pack together
            ItemStack template = com.skycraft.inventory.Pack.isVirtual(slotIndex)
                    ? com.skycraft.inventory.Pack.get(player, slotIndex - com.skycraft.inventory.Pack.VIRTUAL_BASE)
                    : menu.getSlot(slotIndex).getItem().copy();
            // a mount's saddle and barding: "store" is "equip" for the tack (one piece, never grouped)
            if (menu instanceof com.skycraft.mounts.SaddlebagMenu bm && !com.skycraft.inventory.Pack.isVirtual(slotIndex)
                    && slotIndex >= containerSlotCount && slotIndex < menu.slots.size() && bm.isGear(menu.getSlot(slotIndex).getItem())) {
                storedAny = bm.equipFrom(player, menu.getSlot(slotIndex));
                template = ItemStack.EMPTY;
            }
            if (!template.isEmpty()) {
                for (int i = containerSlotCount; i < menu.slots.size(); i++) {
                    Slot pSlot = menu.getSlot(i);
                    if (!pSlot.hasItem() || !ItemStack.isSameItemSameComponents(pSlot.getItem(), template)) continue;
                    // the held / off hand stack only when it is the very slot that was clicked
                    boolean hand = pSlot.getItem() == player.getMainHandItem() || pSlot.getItem() == player.getOffhandItem();
                    if (hand && i != slotIndex) continue;
                    storedAny |= storeSlot(player, menu, containerSlotCount, ov, pSlot);
                }
                int pi = com.skycraft.inventory.Pack.indexOf(player, template);
                if (pi >= 0) storedAny |= storePackEntry(player, menu, containerSlotCount, ov, pi);
            }
        }

        if (storedAny) {
            if (ov != null) com.skycraft.inventory.ContainerSync.markDirty(player);
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 0.9f);
            menu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            player.getInventory().setChanged();
        }
    }

    /** Moves a player-inventory slot of the menu into the container (slots, then overflow); whatever doesn't fit stays put. */
    private static boolean storeSlot(ServerPlayer player, AbstractContainerMenu menu, int containerSlotCount,
                                     @org.jetbrains.annotations.Nullable com.skycraft.inventory.ContainerOverflow.Bound overflow, Slot pSlot) {
        if (pSlot == null || !pSlot.hasItem() || !pSlot.mayPickup(player)) return false;
        ItemStack moving = pSlot.safeTake(pSlot.getItem().getCount(), Integer.MAX_VALUE, player);
        if (moving.isEmpty()) return false;
        int before = moving.getCount();
        storeIntoContainerSlots(menu, containerSlotCount, overflow, moving);
        boolean stored = moving.getCount() < before;
        if (!moving.isEmpty()) {
            ItemStack rest = pSlot.safeInsert(moving);
            if (!rest.isEmpty()) com.skycraft.inventory.Pack.giveOrDrop(player, rest);
        }
        return stored;
    }

    /** Moves a whole pack entry into the container (slots, then overflow); what does not fit goes back into the pack. */
    private static boolean storePackEntry(ServerPlayer player, AbstractContainerMenu menu, int containerSlotCount,
                                          @org.jetbrains.annotations.Nullable com.skycraft.inventory.ContainerOverflow.Bound overflow, int index) {
        ItemStack moving = com.skycraft.inventory.Pack.take(player, index, Integer.MAX_VALUE);
        if (moving.isEmpty()) return false;
        int before = moving.getCount();
        storeIntoContainerSlots(menu, containerSlotCount, overflow, moving);
        boolean stored = moving.getCount() < before;
        if (!moving.isEmpty()) com.skycraft.inventory.Pack.add(player, moving);
        return stored;
    }

    /**
     * Inserts through each slot's own rules (mayPlace, max stack size), merging first, then into the container's
     * overflow when it has one. Shrinks {@code stack}.
     */
    private static void storeIntoContainerSlots(AbstractContainerMenu menu, int containerSlotCount,
                                                @org.jetbrains.annotations.Nullable com.skycraft.inventory.ContainerOverflow.Bound overflow, ItemStack stack) {
        // 1. Merge into matching existing stacks in container
        for (int i = 0; i < containerSlotCount && !stack.isEmpty(); i++) {
            Slot s = menu.getSlot(i);
            if (s.hasItem() && ItemStack.isSameItemSameComponents(s.getItem(), stack)) s.safeInsert(stack);
        }
        // 2. Put into empty container slots
        for (int i = 0; i < containerSlotCount && !stack.isEmpty(); i++) {
            Slot s = menu.getSlot(i);
            if (!s.hasItem()) s.safeInsert(stack);
        }
        // 3. Everything the slots cannot hold goes to the container's overflow (never refused for lack of slots)
        if (!stack.isEmpty() && overflow != null) overflow.add(stack);   // the host's own room rule applies (Bound#add)
    }
}
