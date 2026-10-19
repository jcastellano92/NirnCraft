package com.nirncraft.crime;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Currency;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.dig.PlacedBlocks;
import com.nirncraft.perk.Perks;
import com.nirncraft.skills.Progression;
import com.nirncraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Locked loot containers and the lockpicking minigame.
 *
 * <p>Any container that still has a loot table to generate (or any Lootr container) is a lock candidate the first
 * time someone interacts with it. Whether it's locked (~35%) and how hard (Novice..Master, harder deeper down and in
 * the Nether) is derived from the world seed and the position, and remembered in {@link LockData}. Every player picks
 * a lock once; afterwards it stays open for them (per world, {@link LockData#unlocked}).</p>
 *
 * <p>The sweet spot and pick breaking live on the server: the client only reports the pick angle while turning, and
 * the server answers how far the lock turns, whether the pick broke and whether the lock opened.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Locks {
    public static final int NOT_LOCKED = -1;
    public static final int NOVICE = 0, APPRENTICE = 1, ADEPT = 2, EXPERT = 3, MASTER = 4;
    public static final String[] TIERS = {"novice", "apprentice", "adept", "expert", "master"};
    /** Sweet spot width in degrees per difficulty. */
    private static final float[] SWEET_SPOT = {30f, 20f, 12f, 7f, 4f};
    /** Lockpicking skill use value per difficulty when a lock is picked. */
    private static final float[] XP = {2f, 3f, 5f, 7f, 10f};
    private static final int LOCK_CHANCE = 35;

    public static final int RESULT_PARTIAL = 0;
    public static final int RESULT_BROKEN = 1;
    public static final int RESULT_UNLOCKED = 2;
    public static final int RESULT_CLOSE = 3;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final class Session {
        final ResourceKey<Level> dim;
        final BlockPos pos;
        final int difficulty;
        final float center;
        final float half;
        int strain;
        long lastTurn = -100;

        Session(ResourceKey<Level> dim, BlockPos pos, int difficulty, float center, float half) {
            this.dim = dim;
            this.pos = pos;
            this.difficulty = difficulty;
            this.center = center;
            this.half = half;
        }
    }

    private Locks() {}

    // ------------------------------------------------------------------ lock decisions

    static String key(Level level, BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    public static BlockPos normalizePos(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        return pos;
    }

    /** Whether the block entity still has loot to generate (or is a Lootr container). */
    static boolean isCandidate(Level level, BlockPos pos, BlockEntity be) {
        if (be == null) return false;
        Identifier id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        if (id != null && id.getNamespace().equals("lootr")) return true;
        return be instanceof RandomizableContainerBlockEntity && Theft.lootTable(be) != null;
    }

    /** Whether the door at {@code pos} (lower half) is the door of an inn's private guest room. */
    public static boolean isInnRoomDoor(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof DoorBlock && !Jail.isJailDimension(level)
                && com.nirncraft.tamriel.settlement.SettlementAnchors.hasAnchor(level, pos,
                com.nirncraft.tamriel.settlement.AnchorType.INN_DOOR, false);
    }

    /** Whether the door at {@code pos} (lower half) is a jail's cell door or its cell-block gate (locked unless opened by key or pick). */
    public static boolean isJailLockDoor(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof DoorBlock) || Jail.isJailDimension(level)) return false;
        return com.nirncraft.tamriel.settlement.SettlementAnchors.hasAnchor(level, pos,
                com.nirncraft.tamriel.settlement.AnchorType.JAIL_DOOR, false)
                || com.nirncraft.tamriel.settlement.SettlementAnchors.hasAnchor(level, pos,
                com.nirncraft.tamriel.settlement.AnchorType.JAIL_GATE, false);
    }

    /**
     * The lock difficulty of the container or door at {@code pos}, or {@link #NOT_LOCKED}. Containers are decided (and
     * remembered) on first use; the decision is a pure function of seed and position, so a HUD look and a click agree.
     * Doors are never rolled: they are locked only by an explicit {@link #setLock} (jail cells, the intro cottage,
     * inn rooms) or, inside a settlement, at night when they are a private home's (see {@link #nightLocked}).
     */
    public static int lockLevel(ServerLevel level, BlockPos rawPos) {
        BlockPos pos = normalizePos(level, rawPos);
        if (isInnRoomDoor(level, pos)) return ADEPT;
        LockData data = LockData.get(level.getServer());
        String key = key(level, pos);
        Integer stored = data.locks.get(key);
        if (stored != null) return stored;
        if (data.never.contains(key)) return NOT_LOCKED;
        if (isJailLockDoor(level, pos)) return ADEPT;
        if (PlacedBlocks.isPlayerPlaced(level, pos) || Jail.isJailDimension(level)) return NOT_LOCKED;

        BlockEntity be = level.getBlockEntity(pos);
        if (isCandidate(level, pos, be)) {
            int decided = decide(level, pos);
            data.locks.put(key, decided);
            data.setDirty();
            return decided;
        }

        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock) {
            // outside a settlement (dungeon path doors, camps, quest buildings) a door only locks when set explicitly;
            // inside one a private home locks at night: a derived answer that is never stored, so asking decides nothing
            if (!nightLocked(level, pos)) return NOT_LOCKED;
            int decided = decide(level, pos);
            return decided == NOT_LOCKED ? APPRENTICE : decided;
        }

        return NOT_LOCKED;
    }

    /** The one definition of night for locks, shop hours and ownership: the hours a shop is closed (20:00-08:00). */
    public static boolean isNight(Level level) {
        return !com.nirncraft.economy.Shop.isOpen(level);
    }

    /**
     * Whether the door at {@code pos} is a settlement home's door that is shut for the night. Public buildings (inns,
     * shops, temples, the palace, guard posts, workplaces) and doors flagged {@link #markNeverLock} stay open. The
     * owner (or household) of a home is let in by the ownership check ({@link Ownership#isOwnedByOther}), not here.
     */
    public static boolean nightLocked(ServerLevel level, BlockPos rawPos) {
        if (!isNight(level)) return false;
        BlockPos pos = normalizePos(level, rawPos);
        if (Jail.isJailDimension(level) || Jail.isJailArea(level, pos) || PlacedBlocks.isPlayerPlaced(level, pos)) return false;
        if (LockData.get(level.getServer()).never.contains(key(level, pos))) return false;
        if (!Theft.inVillage(level, pos)) return false;
        return isPrivateHome(level, pos);
    }

    /** Anchor types a household's building carries (a house, a farmhouse, a house for sale). */
    private static boolean isHomeAnchor(com.nirncraft.tamriel.settlement.AnchorType t) {
        return switch (t) {
            case BED, HOUSE_BED, HOUSE_DOOR, HOUSE_MIN, HOUSE_MAX, CONTAINER, FARMER, HOMESTEAD -> true;
            default -> false;
        };
    }

    private static boolean isJailAnchor(com.nirncraft.tamriel.settlement.AnchorType t) {
        return switch (t) {
            case JAIL_CELL, JAIL_DOOR, JAIL_GATE, JAIL_EVIDENCE, JAIL_PERSONAL, JAIL_CONFISCATED,
                 JAIL_EXIT, JAIL_MIN, JAIL_MAX, JAIL_PRISONER -> true;
            default -> false;
        };
    }

    /** Anchor types that belong to the street, not to a building. */
    private static boolean isStreetAnchor(com.nirncraft.tamriel.settlement.AnchorType t) {
        return switch (t) {
            case MEETING, CARRIAGE, GATE, TABLEAU, FIRE_SITE -> true;
            default -> false;
        };
    }

    /**
     * Whether the building around the door is somebody's home (beds, chests, a farmhouse) rather than a public
     * building (inn, shop, smithy, temple, palace, barracks, guild hall, workplace). The building is the one of the
     * nearest recorded anchor; it is public if any anchor with the same owner label is not a household anchor.
     * A door with no recorded building near it counts as a home (the old night rule).
     */
    public static boolean isPrivateHome(ServerLevel level, BlockPos pos) {
        if (Jail.isJailArea(level, pos)) return false;
        var near = com.nirncraft.tamriel.settlement.SettlementAnchors.near(level, pos, 8);
        if (near.isEmpty()) return true;
        com.nirncraft.tamriel.settlement.Anchor best = null;
        long bestD = 20L * 20L;
        for (com.nirncraft.tamriel.settlement.Anchor a : near.get().anchors()) {
            if (isStreetAnchor(a.type()) || Math.abs(a.pos().getY() - pos.getY()) > 12) continue;
            long dx = a.pos().getX() - pos.getX(), dz = a.pos().getZ() - pos.getZ();
            long d = dx * dx + dz * dz;
            if (isJailAnchor(a.type()) && d <= 16L * 16L) return false;
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        if (best == null) return true;
        for (com.nirncraft.tamriel.settlement.Anchor a : near.get().anchors()) {
            if (!a.owner().equals(best.owner()) || isStreetAnchor(a.type())) continue;
            if (!isHomeAnchor(a.type())) return false;
        }
        return true;
    }

    /**
     * Server only: flags a door as a generated path door (dungeon path, marquee or quest door) that is never locked,
     * whatever the hour or the settlement around it. World-generated doors outside settlements are never rolled
     * for a lock anyway; the marker is for doors inside a settlement's area that belong to something else.
     */
    public static void markNeverLock(ServerLevel level, BlockPos rawPos) {
        BlockPos pos = normalizePos(level, rawPos);
        LockData data = LockData.get(level.getServer());
        String key = key(level, pos);
        boolean changed = data.locks.remove(key) != null;
        changed |= data.never.add(key);
        if (changed) data.setDirty();
    }

    private static int decide(ServerLevel level, BlockPos pos) {
        long h = level.getSeed() ^ pos.asLong() * 0x9E3779B97F4A7C15L ^ level.dimension().identifier().hashCode() * 0xC2B2AE3D27D4EB4FL;
        h = mix(h);
        if (Math.floorMod(h, 100) >= LOCK_CHANCE) return NOT_LOCKED;
        int[] weights;
        int y = pos.getY();
        if (y >= 50) weights = new int[]{40, 30, 20, 8, 2};
        else if (y >= 0) weights = new int[]{25, 30, 25, 15, 5};
        else weights = new int[]{10, 20, 30, 25, 15};
        int roll = (int) Math.floorMod(mix(h + 0x632BE59BD9B4E019L), 100);
        int tier = 0;
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                tier = i;
                break;
            }
        }
        // the realms' strongholds lock harder (the Deadlands +1 ... Coldharbour +3)
        com.nirncraft.realms.Realm realm = com.nirncraft.realms.Realm.of(level);
        if (realm != null) tier += realm.lockBonus;
        return Math.min(MASTER, tier);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Server only: picked locks live per world in {@link LockData} (not in the synced player data). */
    public static boolean isUnlocked(Player player, Level level, BlockPos pos) {
        if (level.getServer() == null) return false;
        LockData data = LockData.get(level.getServer());
        migrateUnlocked(player, data);
        Set<UUID> who = data.unlocked.get(key(level, pos));
        return who != null && who.contains(player.getUUID());
    }

    private static void markUnlocked(Player player, Level level, BlockPos pos) {
        if (level.getServer() == null) return;
        LockData data = LockData.get(level.getServer());
        migrateUnlocked(player, data);
        data.unlocked.computeIfAbsent(key(level, pos), k -> new HashSet<>()).add(player.getUUID());
        data.setDirty();
    }

    /** Moves a legacy {@code crime.unlocked} list out of the player's synced data into the world's lock data. */
    private static void migrateUnlocked(Player player, LockData data) {
        CompoundTag state = Bounty.state(player);
        if (!state.contains("unlocked")) return;
        ListTag list = state.getListOrEmpty("unlocked");
        for (int i = 0; i < list.size(); i++) {
            data.unlocked.computeIfAbsent(list.getStringOr(i, ""), k -> new HashSet<>()).add(player.getUUID());
        }
        state.remove("unlocked");
        SkyData.get(player).markDirty();
        data.setDirty();
    }

    public static Component tierName(int tier) {
        return Component.translatable("crime.nirncraft.lock." + TIERS[Math.max(0, Math.min(MASTER, tier))]);
    }

    public static void setLock(ServerLevel level, BlockPos rawPos, int tier) {
        BlockPos pos = normalizePos(level, rawPos);
        LockData data = LockData.get(level.getServer());
        data.locks.put(key(level, pos), tier);
        data.never.remove(key(level, pos));
        data.setDirty();
    }

    public static boolean hasJailKey(Player player) {
        return com.nirncraft.inventory.Inv.has(player, CrimeItems.JAIL_KEY.get());
    }

    /** Whether the player must pick this lock before using the block. */
    public static boolean blocks(ServerPlayer player, ServerLevel level, BlockPos rawPos) {
        if (player.isCreative() || player.isSpectator()) return false;
        BlockPos pos = normalizePos(level, rawPos);
        BlockState state = level.getBlockState(pos);
        if (isInnRoomDoor(level, pos)) {
            // an inn's guest room: open to whoever rented a room there, locked (and pickable) for everyone else
            if (com.nirncraft.survival.inn.Innkeepers.roomHere(player) != null) return false;
            return !isUnlocked(player, level, pos);
        }
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock) {
            if (!Jail.isJailDimension(level) && !Ownership.isOwnedByOther(player, level, pos)) {
                String key = key(level, pos);
                if (!LockData.get(level.getServer()).locks.containsKey(key)) {
                    return false;
                }
            }
        }
        if ((Jail.isJailDimension(level) || Jail.isJailArea(level, pos)) && hasJailKey(player)) return false;
        int tier = lockLevel(level, pos);
        return tier != NOT_LOCKED && !isUnlocked(player, level, pos);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = normalizePos(level, event.getPos());
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        boolean isDoor = state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock;
        if (be == null && !isDoor) return;
        if (!blocks(player, level, pos)) {
            if (state.getBlock() instanceof DoorBlock door) {
                if (state.is(net.minecraft.world.level.block.Blocks.IRON_DOOR)) {
                    boolean open = !state.getValue(DoorBlock.OPEN);
                    door.setOpen(player, level, state, pos, open);
                    level.playSound(null, pos, open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1f, 1f);
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                } else if (player.isSecondaryUseActive()) {
                    boolean open = !state.getValue(DoorBlock.OPEN);
                    door.setOpen(player, level, state, pos, open);
                    level.playSound(null, pos, open ? door.type().doorOpen() : door.type().doorClose(), SoundSource.BLOCKS, 1f, 1f);
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                }
            } else if (state.getBlock() instanceof TrapDoorBlock trapdoor && state.is(net.minecraft.world.level.block.Blocks.IRON_TRAPDOOR)) {
                boolean open = !state.getValue(TrapDoorBlock.OPEN);
                level.setBlock(pos, state.setValue(TrapDoorBlock.OPEN, open), 2);
                level.playSound(null, pos, open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1f, 1f);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            } else if (player.isSecondaryUseActive() && be instanceof net.minecraft.world.MenuProvider mp) {
                // In Skyrim, crouching to open containers and loot/steal is standard stealth gameplay.
                // Vanilla blocks container opening while sneaking with held items; open menu directly!
                player.openMenu(mp);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        int tier = lockLevel(level, pos);
        level.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 0.8f, 1.2f);
        int picks = countPicks(player);
        if (picks <= 0) {
            Notifier.message(player, Component.translatable("crime.nirncraft.lock.need_pick", state.getBlock().getName(), tierName(tier)));
            return;
        }
        start(player, level, pos, tier, picks);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = normalizePos(level, event.getPos());
        BlockState state = level.getBlockState(pos);
        boolean isDoor = state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock;
        if (level.getBlockEntity(pos) == null && !isDoor) return;
        if (blocks(player, level, pos)) {
            event.setCanceled(true);
            Notifier.message(player, Component.translatable("crime.nirncraft.lock.cant_break", tierName(lockLevel(level, pos))));
        }
    }

    /** Forget the decision for containers that are actually broken, so a new chest placed there isn't locked. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBroken(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getBlockEntity(event.getPos()) == null) return;
        LockData data = LockData.get(level.getServer());
        String key = key(level, normalizePos(level, event.getPos()));
        boolean changed = data.locks.remove(key) != null;
        changed |= data.unlocked.remove(key) != null;
        if (changed) data.setDirty();
    }

    // ------------------------------------------------------------------ minigame

    static int countPicks(Player player) {
        return com.nirncraft.inventory.Inv.count(player, CrimeItems.LOCKPICK.get());
    }

    private static boolean consumePick(Player player) {
        return com.nirncraft.inventory.Inv.take(player, CrimeItems.LOCKPICK.get(), 1) > 0;
    }

    /** Full sweet spot width in degrees for this player and difficulty. */
    static float sweetSpot(ServerPlayer player, int tier) {
        float width = SWEET_SPOT[tier] * (1f + SkyData.get(player).getSkill(Skill.LOCKPICKING) / 100f);
        if (Perks.has(player, "lockpicking." + TIERS[tier] + "_locks")) width *= 2f;
        return Math.min(80f, width);
    }

    private static int breakTicks(ServerPlayer player) {
        return 12 + SkyData.get(player).getSkill(Skill.LOCKPICKING) / 5;
    }

    private static void start(ServerPlayer player, ServerLevel level, BlockPos pos, int tier, int picks) {
        float half = sweetSpot(player, tier) / 2f;
        float center = -90f + half + player.getRandom().nextFloat() * (180f - 2 * half);
        SESSIONS.put(player.getUUID(), new Session(level.dimension(), pos.immutable(), tier, center, half));
        // picking a lock in a jail is heard: the guards nearby come to look
        if (Jail.isJailArea(level, pos)) JailPatrol.noise(player, 8);
        com.nirncraft.network.SkyNetwork.sendToPlayer(player, new CrimePackets.OpenLockpick(pos, tier, picks));
    }

    /** The client is turning the lock with the pick at {@code angle} (-90..90). */
    static void turn(ServerPlayer player, float angle, boolean start) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null) {
            reply(player, RESULT_CLOSE, 0);
            return;
        }
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(s.pos);
        boolean isDoor = state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock;
        if (level.dimension() != s.dim || player.distanceToSqr(Vec3.atCenterOf(s.pos)) > 49
                || (level.getBlockEntity(s.pos) == null && !isDoor)) {
            SESSIONS.remove(player.getUUID());
            reply(player, RESULT_CLOSE, 0);
            return;
        }
        long now = level.getGameTime();
        long dt = now - s.lastTurn;
        if (dt < 3) return; // rate limit: no brute-force sweeping
        s.lastTurn = now;
        if (Float.isNaN(angle)) angle = 0;
        angle = Math.max(-90f, Math.min(90f, angle));

        float dist = Math.abs(angle - s.center);
        if (dist <= s.half) {
            unlock(player, level, s);
            return;
        }
        float fraction = 1f - (dist - s.half) / (s.half + 30f);
        fraction = Math.max(0.03f, Math.min(0.9f, fraction));
        if (!Perks.has(player, "lockpicking.unbreakable")) {
            s.strain += start ? 2 : (int) Math.min(6, dt);
            if (s.strain >= breakTicks(player)) {
                s.strain = 0;
                consumePick(player);
                level.playSound(null, s.pos, SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 0.9f, 1.3f);
                int left = countPicks(player);
                reply(player, RESULT_BROKEN, left);
                if (left <= 0) {
                    SESSIONS.remove(player.getUUID());
                    Notifier.message(player, Component.translatable("crime.nirncraft.lock.out_of_picks"));
                }
                return;
            }
        }
        reply(player, RESULT_PARTIAL, fraction);
    }

    static void close(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    private static void reply(ServerPlayer player, int result, float value) {
        com.nirncraft.network.SkyNetwork.sendToPlayer(player, new CrimePackets.LockResult(result, value));
    }

    private static void unlock(ServerPlayer player, ServerLevel level, Session s) {
        SESSIONS.remove(player.getUUID());
        markUnlocked(player, level, s.pos);
        Progression.addSkillXp(player, Skill.LOCKPICKING, XP[s.difficulty]);
        level.playSound(null, s.pos, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 0.7f, 1.6f);

        // Contract 5: Roll lock bonus loot for containers
        if (level.getBlockEntity(s.pos) != null && JailEvidence.kindAt(level, s.pos) == JailEvidence.Kind.NONE) {
            rollLockBonus(player, level, s.pos, s.difficulty);
        }

        if (Perks.has(player, "lockpicking.golden_touch")) {
            Currency.give(player, 10 + player.getRandom().nextInt(41) + s.difficulty * 10);
        }
        if (Perks.has(player, "lockpicking.treasure_hunter") && player.getRandom().nextFloat() < 0.5f) {
            ItemStack bonus = switch (player.getRandom().nextInt(4)) {
                case 0 -> new ItemStack(Items.EMERALD, 1 + s.difficulty / 2);
                case 1 -> new ItemStack(Items.AMETHYST_SHARD, 2 + s.difficulty);
                case 2 -> new ItemStack(s.difficulty >= EXPERT ? Items.DIAMOND : Items.GOLD_INGOT);
                default -> Currency.coins(25L + 25L * s.difficulty);
            };
            com.nirncraft.inventory.Pack.giveOrDrop(player, bonus);
            Notifier.message(player, Component.translatable("crime.nirncraft.lock.treasure", bonus.getHoverName()));
        }

        // Picking an owned lock in plain sight is trespassing (Quick Hands: nobody notices).
        if (!Perks.has(player, "lockpicking.quick_hands") && Theft.isOwned(level, s.pos)) {
            Crimes.report(player, s.pos, Bounty.TRESPASS, false, null);
        }

        reply(player, RESULT_UNLOCKED, 1);
        // a cell door picked by someone who is not under arrest: a jailbreak (docs/JAIL.md)
        JailVisitors.freed(player, level, s.pos);
        // open the container or door right away, like Skyrim
        BlockState state = level.getBlockState(s.pos);
        if (JailEvidence.kindAt(level, s.pos) == JailEvidence.Kind.CONFISCATED) {
            // the jail's shared confiscated goods: the Skyrim screen over the jail's store, locked again behind us
            JailEvidence.openConfiscated(player, level, s.pos);
            return;
        }
        Theft.noteClick(player, s.pos);
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(player, level, state, s.pos, true);
        } else if (state.getBlock() instanceof TrapDoorBlock) {
            state = state.cycle(TrapDoorBlock.OPEN);
            level.setBlock(s.pos, state, 10);
            level.playSound(null, s.pos, state.getValue(TrapDoorBlock.OPEN) ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1f, 1f);
        } else {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(s.pos), Direction.UP, s.pos, false);
            state.useWithoutItem(level, player, hit);
        }
    }

    private static void rollLockBonus(ServerPlayer player, ServerLevel level, BlockPos pos, int difficulty) {
        int tierIdx = Math.max(0, Math.min(MASTER, difficulty));
        Identifier loc = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/lock_bonus/" + TIERS[tierIdx]);
        LootTable lootTable = com.nirncraft.platform.LootTables.get(level, loc);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.CHEST);
        List<ItemStack> bonusItems = lootTable.getRandomItems(params);
        for (ItemStack stack : bonusItems) {
            if (!stack.isEmpty()) {
                if (!ActionHandler.addToBags(player, stack)) {
                    player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
                }
            }
        }
    }

    static void forget(UUID player) {
        SESSIONS.remove(player);
    }

    // ------------------------------------------------------------------ saved data

    /** Per-world lock decisions: "dim|x|y|z" -> difficulty (or -1 when not locked). Stored with the overworld. */
    public static class LockData extends SavedData {
        private static final String NAME = "nirncraft_locks";
        final Map<String, Integer> locks = new HashMap<>();
        /** Lock key -> players who have picked it (it stays open for them). */
        final Map<String, Set<UUID>> unlocked = new HashMap<>();
        /** Keys of generated path doors that are never locked (see {@link Locks#markNeverLock}). */
        final Set<String> never = new HashSet<>();

        static LockData get(MinecraftServer server) {
            return com.nirncraft.platform.SavedDataTypes.get(server.overworld(), NAME, LockData::load, LockData::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
        }

        static LockData load(CompoundTag tag) {
            LockData data = new LockData();
            CompoundTag locks = tag.getCompoundOrEmpty("locks");
            for (String k : locks.keySet()) data.locks.put(k, locks.getIntOr(k, 0));
            CompoundTag unlocked = tag.getCompoundOrEmpty("unlocked");
            for (String k : unlocked.keySet()) {
                Set<UUID> who = new HashSet<>();
                for (Tag t : unlocked.getListOrEmpty(k)) {
                    try {
                        who.add(net.minecraft.core.UUIDUtil.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, t).getOrThrow());
                    } catch (IllegalArgumentException ignored) {
                        // corrupt entry
                    }
                }
                if (!who.isEmpty()) data.unlocked.put(k, who);
            }
            for (Tag t : tag.getListOrEmpty("never")) data.never.add(t.asString().orElse(""));
            return data;
        }

        public CompoundTag save(CompoundTag tag) {
            ListTag never = new ListTag();
            for (String k : this.never) never.add(net.minecraft.nbt.StringTag.valueOf(k));
            tag.put("never", never);
            CompoundTag locks = new CompoundTag();
            this.locks.forEach(locks::putInt);
            tag.put("locks", locks);
            CompoundTag unlocked = new CompoundTag();
            this.unlocked.forEach((k, who) -> {
                ListTag list = new ListTag();
                for (UUID u : who) list.add(net.minecraft.core.UUIDUtil.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, u).getOrThrow());
                if (!list.isEmpty()) unlocked.put(k, list);
            });
            tag.put("unlocked", unlocked);
            return tag;
        }
    }
}
