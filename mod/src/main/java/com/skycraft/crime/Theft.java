package com.skycraft.crime;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.economy.ItemValues;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stealing from containers that belong to someone: containers inside a village (a villager within 32 blocks or a
 * village meeting point within 48) that weren't placed by a player and aren't dungeon loot chests.
 *
 * <p>The container's contents and the player's inventory are snapshotted when the menu opens and compared when it
 * closes: items that moved from the container to the player are stolen ({@code skycraft_stolen}), and if anyone saw
 * it while the container was open, the hold adds a bounty of half the stolen value.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Theft {
    private static final Map<UUID, Click> LAST_CLICK = new HashMap<>();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** Loot chests with these loot table prefixes still belong to the villagers (village houses). */
    private static final String VILLAGE_LOOT = "chests/village";

    private record Click(BlockPos pos, long tick, boolean villageLoot, boolean dungeonLoot) {}

    private static final class Session {
        final int containerId;
        final BlockPos pos;
        final List<Entry> container;
        final List<Entry> inventory;
        /** Wallet gold when the menu opened: coins taken may be deposited into the wallet before the menu closes. */
        final long walletBefore;
        /** Items taken out of the container and thrown out of the screen (already tagged stolen). */
        final List<Entry> tossed = new ArrayList<>();
        boolean seen;

        Session(int containerId, BlockPos pos, List<Entry> container, List<Entry> inventory, long walletBefore) {
            this.containerId = containerId;
            this.pos = pos;
            this.container = container;
            this.inventory = inventory;
            this.walletBefore = walletBefore;
        }
    }

    /** One kind of item (item + NBT) and how many of it. */
    private static final class Entry {
        final ItemStack proto;
        int count;

        Entry(ItemStack proto, int count) {
            this.proto = proto;
            this.count = count;
        }
    }

    private Theft() {}

    // ------------------------------------------------------------------ ownership

    /** Whether the container at {@code pos} belongs to someone (stealing from it is a crime). */
    public static boolean isOwned(ServerLevel level, BlockPos pos) {
        if (Jail.isJailDimension(level) || Jail.isJailArea(level, pos) || PlacedBlocks.isPlayerPlaced(level, pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        String loot = lootTable(be);
        if (loot != null && !owned(loot)) return false; // dungeon/structure loot: finders keepers
        return inVillage(level, pos);
    }

    /** How long (ticks) the entity and POI part of {@link #inVillage} is remembered for a spot. */
    private static final long VILLAGE_CACHE_TICKS = 100;
    /** "dimension|cell" -> {game time until valid, 1 if a village is there}: the entity scans are costly per click. */
    private static final Map<String, long[]> VILLAGE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean inVillage(ServerLevel level, BlockPos pos) {
        // recorded settlements are a cheap lookup and always answered fresh
        if (com.skycraft.tamriel.settlement.SettlementAnchors.inSettlement(level, pos)) return true;
        // villagers, townsfolk and meeting points: scanned once per 4x4x4 cell and a few seconds, not per door click
        long now = level.getGameTime();
        String key = level.dimension().location() + "|" + (pos.getX() >> 2) + "|" + (pos.getY() >> 2) + "|" + (pos.getZ() >> 2);
        long[] hit = VILLAGE_CACHE.get(key);
        if (hit != null && hit[0] > now && hit[0] - VILLAGE_CACHE_TICKS <= now) return hit[1] != 0;
        if (VILLAGE_CACHE.size() > 2048) VILLAGE_CACHE.clear();
        boolean found = nearTownsfolk(level, pos);
        VILLAGE_CACHE.put(key, new long[]{now + VILLAGE_CACHE_TICKS, found ? 1 : 0});
        return found;
    }

    private static boolean nearTownsfolk(ServerLevel level, BlockPos pos) {
        if (!level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(32), Villager::isAlive).isEmpty()) return true;
        // Skycraft townsfolk living here
        if (!level.getEntitiesOfClass(com.skycraft.society.entity.NpcEntity.class, new AABB(pos).inflate(32),
                n -> n.isAlive() && n.getHome() != null).isEmpty()) return true;
        return level.getPoiManager().findClosest((Holder<PoiType> h) -> h.is(PoiTypes.MEETING), pos, 48, PoiManager.Occupancy.ANY).isPresent();
    }

    /** Household and shop loot (village tables and the loot module's {@code containers/household|shop}) belongs to someone. */
    static boolean owned(String loot) {
        return loot.contains(VILLAGE_LOOT) || com.skycraft.loot.Containers.isOwned(loot);
    }

    /** The loot table a container still has to generate, or null. */
    static String lootTable(BlockEntity be) {
        if (!(be instanceof RandomizableContainerBlockEntity)) return null;
        CompoundTag tag = be.saveWithoutMetadata();
        return tag.contains("LootTable", Tag.TAG_STRING) ? tag.getString("LootTable") : null;
    }

    // ------------------------------------------------------------------ tracking which block a menu belongs to

    /** Remembers the block the player just used, so the menu opened in the same tick can be tied to it. */
    static void noteClick(ServerPlayer player, BlockPos pos) {
        BlockEntity be = player.level().getBlockEntity(pos);
        String loot = lootTable(be);
        LAST_CLICK.put(player.getUUID(), new Click(pos.immutable(), player.level().getGameTime(),
                loot != null && owned(loot), loot != null && !owned(loot)));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        noteClick(player, event.getPos());
    }

    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SESSIONS.remove(player.getUUID());
        Click click = LAST_CLICK.remove(player.getUUID());
        // the jail's confiscated goods are the jail's: taking from them is stealing like from any owned container
        if (event.getContainer() instanceof net.minecraft.world.inventory.ChestMenu jailMenu
                && jailMenu.getContainer() instanceof JailEvidence.StoreContainer store && Crimes.canCommitCrime(player)) {
            SESSIONS.put(player.getUUID(), new Session(jailMenu.containerId, store.pos(), countMenu(jailMenu, player),
                    countInventory(player), Currency.balance(player)));
            return;
        }
        if (click == null || player.level().getGameTime() - click.tick() > 2 || !Crimes.canCommitCrime(player)) return;
        AbstractContainerMenu menu = event.getContainer();
        if (menu == player.inventoryMenu) return;
        ServerLevel level = player.serverLevel();
        if (click.dungeonLoot()) return; // dungeon loot: finders keepers
        if (click.villageLoot()) {
            if (PlacedBlocks.isPlayerPlaced(level, click.pos()) || !inVillage(level, click.pos())) return;
        } else {
            BlockEntity be = level.getBlockEntity(click.pos());
            if (!(be instanceof Container) || !isOwned(level, click.pos())) return;
        }

        Session s = new Session(menu.containerId, click.pos(), countMenu(menu, player), countInventory(player),
                Currency.balance(player));
        SESSIONS.put(player.getUUID(), s);
    }

    /** Items thrown out of an owned container's screen are stolen too (they left the container without reaching the bags). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onToss(ItemTossEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)) return;
        Session s = SESSIONS.get(player.getUUID());
        if (s == null || player.containerMenu.containerId != s.containerId) return;
        ItemStack stack = event.getEntity().getItem();
        if (stack.isEmpty()) return;
        // only as many as the container is now missing (and not already accounted for as thrown)
        int missing = countOf(s.container, stack) - countOf(countMenu(player.containerMenu, player), stack) - countOf(s.tossed, stack);
        int n = Math.min(missing, stack.getCount());
        if (n <= 0) return;
        ItemStack proto = stack.copy();
        proto.setCount(1);
        add(s.tossed, proto.copyWithCount(n));
        if (!player.isCreative()) {
            ItemStack tagged = stack.copy();
            Bounty.markStolen(tagged);
            event.getEntity().setItem(tagged);
        }
    }

    /** Every second while an owned container is open: did someone see the player take something? */
    static void tick(ServerPlayer player) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null || s.seen) return;
        if (player.containerMenu.containerId != s.containerId) {
            SESSIONS.remove(player.getUUID());
            return;
        }
        List<Entry> now = countMenu(player.containerMenu, player);
        boolean taken = false;
        for (Entry before : s.container) {
            if (before.count > countOf(now, before.proto)) {
                taken = true;
                break;
            }
        }
        if (taken && Crimes.witnessed(player, null)) s.seen = true;
    }

    @SubscribeEvent
    public static void onClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Session s = SESSIONS.remove(player.getUUID());
        if (s == null || event.getContainer().containerId != s.containerId) return;

        List<Entry> containerAfter = countMenu(event.getContainer(), player);
        List<Entry> inventoryAfter = countInventory(player);
        int stolenCount = 0;
        long stolenValue = 0;
        long coinsRemovedValue = 0;
        int coinsRemoved = 0;
        for (Entry before : s.container) {
            int removed = before.count - countOf(containerAfter, before.proto);
            if (removed <= 0) continue;
            // quest items are never stolen goods: nobody confiscates or recovers them
            if (com.skycraft.quest.QuestItems.isQuestItem(before.proto)) continue;
            if (Currency.valueOf(before.proto) > 0) {
                // coins are paid into the wallet (now or on close), so they are counted by value below
                coinsRemoved += removed;
                coinsRemovedValue += unitValue(before.proto) * removed;
                continue;
            }
            int tossed = Math.min(removed, countOf(s.tossed, before.proto));
            int gained = countOf(inventoryAfter, before.proto) - countOf(s.inventory, before.proto);
            int stolen = Math.min(removed - tossed, gained);
            int tagged = stolen > 0 ? tagStolen(player, before.proto, stolen) : 0;
            stolenCount += tagged + tossed;
            stolenValue += unitValue(before.proto) * (long) (tagged + tossed);
        }
        if (coinsRemovedValue > 0) {
            long coinGain = Currency.balance(player) - s.walletBefore
                    + coinValue(inventoryAfter) - coinValue(s.inventory) + coinValue(s.tossed);
            long stolenGold = Math.min(coinsRemovedValue, Math.max(0, coinGain));
            if (stolenGold > 0) {
                stolenValue += stolenGold;
                stolenCount += (int) Math.max(1, Math.round(coinsRemoved * (double) stolenGold / coinsRemovedValue));
            }
        }
        if (stolenCount <= 0) return;

        Bounty.increment(player, "items_stolen", stolenCount);
        if (s.seen || Crimes.witnessed(player, null)) {
            int bounty = (int) Math.max(Bounty.MIN_THEFT, Math.min(100000, stolenValue / 2));
            handleWitnessedTheft(player, Crimes.findWitness(player, null), s.pos, bounty);
        }
    }

    /**
     * Owned placed clutter in settlements cannot be vacuumed up by walking or crouching near it.
     * It must be intentionally taken/stolen with [F].
     */
    @SubscribeEvent
    public static void onPickup(EntityItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemEntity itemEntity = event.getItem();
        if (Ownership.isOwnedByOther(player, itemEntity)) {
            event.setCanceled(true);
        }
    }

    public record PendingReport(UUID playerUuid, UUID witnessUuid, BlockPos pos, int bounty, int[] timer) {
        public PendingReport(UUID playerUuid, UUID witnessUuid, BlockPos pos, int bounty, int ticks) {
            this(playerUuid, witnessUuid, pos, bounty, new int[]{ticks});
        }
    }

    private static final List<PendingReport> PENDING_REPORTS = new ArrayList<>();

    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || PENDING_REPORTS.isEmpty()) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        var it = PENDING_REPORTS.iterator();
        while (it.hasNext()) {
            PendingReport r = it.next();
            r.timer()[0]--;
            ServerPlayer player = server.getPlayerList().getPlayer(r.playerUuid());
            if (player == null || !player.isAlive()) {
                it.remove();
                continue;
            }
            ServerLevel level = player.serverLevel();
            net.minecraft.world.entity.Entity witnessEntity = level.getEntity(r.witnessUuid());
            if (!(witnessEntity instanceof net.minecraft.world.entity.LivingEntity witness) || !witness.isAlive()) {
                // Witness was neutralized or fled world; no report!
                it.remove();
                continue;
            }

            // Check if witness arrived within reach of a guard
            List<net.minecraft.world.entity.Mob> guards = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, witness.getBoundingBox().inflate(14), Crimes::isGuard);
            if (!guards.isEmpty()) {
                com.skycraft.society.Barks.say(witness, net.minecraft.network.chat.Component.literal("Guards! Help! That one's a thief!"));
                com.skycraft.society.Barks.say(witness, net.minecraft.network.chat.Component.literal("Guards! Help! That one's a criminal!"));
                Crimes.report(player, r.pos(), r.bounty(), true, null);
                it.remove();
                continue;
            }

            // If the timer expired, the witness only reports (once) if they reached guards near a settlement
            if (r.timer()[0] <= 0) {
                List<net.minecraft.world.entity.Mob> settlementGuards = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, witness.getBoundingBox().inflate(32), Crimes::isGuard);
                if (!settlementGuards.isEmpty()) {
                    Crimes.report(player, r.pos(), r.bounty(), true, null);
                }
                it.remove();
            }
        }
    }

    public static void queueWitnessReport(ServerPlayer player, net.minecraft.world.entity.LivingEntity witness, BlockPos pos, int bounty) {
        PENDING_REPORTS.add(new PendingReport(player.getUUID(), witness.getUUID(), pos, bounty, 400));
    }

    public static void handleWitnessedTheft(ServerPlayer player, @org.jetbrains.annotations.Nullable net.minecraft.world.entity.LivingEntity witness, BlockPos pos, int bounty) {
        if (witness == null) {
            Crimes.report(player, pos, bounty, false, null);
            return;
        }

        // 1. Guard witness: immediate arrest confrontation
        if (Crimes.isGuard(witness)) {
            com.skycraft.society.Barks.say(witness, net.minecraft.network.chat.Component.literal("Halt, thief! In the name of the Jarl!"));
            Crimes.report(player, pos, bounty, true, null);
            return;
        }

        // 2. Scaled response based on courage/role:
        boolean isFighter = false;
        if (witness instanceof com.skycraft.society.entity.NpcEntity npc) {
            isFighter = npc.role().combatant;
        } else if (witness instanceof Villager v) {
            var prof = v.getVillagerData().getProfession();
            isFighter = prof == net.minecraft.world.entity.npc.VillagerProfession.ARMORER
                    || prof == net.minecraft.world.entity.npc.VillagerProfession.WEAPONSMITH
                    || prof == net.minecraft.world.entity.npc.VillagerProfession.TOOLSMITH;
        }

        if (isFighter) {
            // Confrontation & fighting back!
            String[] barks = {
                "Keep your filthy hands off that, thief!",
                "Drop that, or I'll take it from your hide!",
                "Hey! That's not yours! Give it back!"
            };
            com.skycraft.society.Barks.say(witness, net.minecraft.network.chat.Component.literal(barks[player.getRandom().nextInt(barks.length)]));
            if (witness instanceof net.minecraft.world.entity.Mob mob) {
                mob.setTarget(player);
            }
            punchAndRetrieve(witness, player, pos, bounty);
        } else {
            // Panic, yelling for guards, and running away!
            String[] barks = {
                "Thief! Someone help! Guards!",
                "Guards! There's a thief!",
                "Help! A thief is stealing our things!"
            };
            com.skycraft.society.Barks.say(witness, net.minecraft.network.chat.Component.literal(barks[player.getRandom().nextInt(barks.length)]));
            if (witness instanceof net.minecraft.world.entity.Mob mob) {
                var away = witness.position().subtract(player.position()).normalize();
                mob.getNavigation().moveTo(witness.getX() + away.x * 16, witness.getY(), witness.getZ() + away.z * 16, 1.25);
            }
            PENDING_REPORTS.add(new PendingReport(player.getUUID(), witness.getUUID(), pos, bounty, 120));
        }
    }

    public static void punchAndRetrieve(net.minecraft.world.entity.LivingEntity witness, ServerPlayer player, BlockPos pos, int bounty) {
        player.hurt(player.damageSources().mobAttack(witness), 1.5f);
        witness.level().playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_KNOCKBACK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);

        boolean retrieved = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && Bounty.isStolen(stack)) {
                ItemStack taken = stack.split(1);
                retrieved = true;
                com.skycraft.society.Barks.say(witness, net.minecraft.network.chat.Component.literal("Got it back! Now get out before I call the guards!"));
                com.skycraft.core.Notifier.message(player, net.minecraft.network.chat.Component.literal("The " + witness.getName().getString() + " punched you and retrieved " + taken.getHoverName().getString() + "!").withStyle(net.minecraft.ChatFormatting.GOLD));
                if (witness instanceof net.minecraft.world.entity.Mob mob) {
                    mob.setTarget(null);
                }
                break;
            }
        }
        if (!retrieved) {
            PENDING_REPORTS.add(new PendingReport(player.getUUID(), witness.getUUID(), pos, bounty, 60));
        }
    }

    static long unitValue(ItemStack stack) {
        ItemStack one = stack.copy();
        one.setCount(1);
        long coins = Currency.valueOf(one);
        return coins > 0 ? coins : ItemValues.get(one);
    }

    /** Tags up to {@code amount} matching (not yet stolen) items in the player's inventory as stolen. */
    private static int tagStolen(ServerPlayer player, ItemStack proto, int amount) {
        // items that were already stolen (taken from an owned container again) still count
        if (Bounty.isStolen(proto)) return amount;
        if (com.skycraft.quest.QuestItems.isQuestItem(proto)) return 0;
        Inventory inv = player.getInventory();
        int left = amount;
        List<ItemStack> split = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || Bounty.isStolen(stack) || !ItemStack.isSameItemSameTags(stack, proto)) continue;
            if (stack.getCount() <= left) {
                left -= stack.getCount();
                Bounty.markStolen(stack);
            } else {
                ItemStack part = stack.split(left);
                Bounty.markStolen(part);
                split.add(part);
                left = 0;
            }
        }
        for (ItemStack part : split) com.skycraft.inventory.Pack.giveOrDrop(player, part);
        if (left > 0) { // what overflowed into the pack counts too
            int idx = com.skycraft.inventory.Pack.indexOf(player, proto);
            ItemStack part = idx < 0 ? ItemStack.EMPTY : com.skycraft.inventory.Pack.take(player, idx, left);
            if (!part.isEmpty()) {
                left -= part.getCount();
                Bounty.markStolen(part);
                com.skycraft.inventory.Pack.add(player, part);
            }
        }
        return amount - left;
    }

    // ------------------------------------------------------------------ counting

    private static List<Entry> countMenu(AbstractContainerMenu menu, Player player) {
        List<Entry> out = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            add(out, slot.getItem());
        }
        // the container's overflow (inventory.ContainerOverflow) is part of what it holds
        if (player instanceof ServerPlayer sp) {
            for (ItemStack s : com.skycraft.inventory.ContainerOverflow.entriesOf(sp, menu)) add(out, s);
        }
        return out;
    }

    private static List<Entry> countInventory(Player player) {
        List<Entry> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) add(out, inv.getItem(i));
        for (ItemStack s : com.skycraft.inventory.Pack.entries(player)) add(out, s);
        return out;
    }

    private static void add(List<Entry> list, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (Entry e : list) {
            if (ItemStack.isSameItemSameTags(e.proto, stack)) {
                e.count += stack.getCount();
                return;
            }
        }
        ItemStack proto = stack.copy();
        proto.setCount(1);
        list.add(new Entry(proto, stack.getCount()));
    }

    private static long coinValue(List<Entry> list) {
        long sum = 0;
        for (Entry e : list) sum += Currency.valueOf(e.proto) * e.count;
        return sum;
    }

    private static int countOf(List<Entry> list, ItemStack proto) {
        for (Entry e : list) {
            if (ItemStack.isSameItemSameTags(e.proto, proto)) return e.count;
        }
        return 0;
    }

    static void forget(UUID player) {
        LAST_CLICK.remove(player);
        SESSIONS.remove(player);
    }
}
