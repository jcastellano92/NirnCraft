package com.skycraft.crime;

import com.skycraft.Skycraft;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.roads.RoadsData;
import com.skycraft.roads.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contract 1 (docs/PLAYTEST-1.md): who owns a block or a placed world item. Owner ids are {@code npc:<uuid>},
 * {@code faction:<id>}, {@code player:<uuid>} or {@code public}. A placed {@code ItemEntity} is owned when its
 * persistent data holds the string {@code skycraft_owner}.
 *
 * <p>Containers, beds, doors, and placed clutter in settlements are owned by the household or shop.
 * Blocks in enemy camps and dungeons are never "owned": looting them is not a crime.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Ownership {
    public static final String ENTITY_OWNER_KEY = "skycraft_owner";

    /** Client-side cache of known block ownership for responsive HUD crosshairs. */
    public static final Map<String, OwnerEntry> CLIENT_CACHE = new ConcurrentHashMap<>();

    public record OwnerEntry(String ownerId, @Nullable Component ownerName) {}

    private Ownership() {}

    /** Faction property ({@code faction:<id>}: guild hall beds and chests) is free to the faction's members. */
    private static boolean memberOf(Player player, String ownerId) {
        return ownerId.startsWith("faction:")
                && com.skycraft.core.SkyData.get(player).module("quest").getCompound("factions").contains(ownerId.substring(8));
    }

    private static String key(Level level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    /**
     * True if using or taking from this block is a crime (or not allowed) for this player.
     * Works on both client and server; client reads synced data.
     */
    public static boolean isOwnedByOther(Player player, BlockPos pos) {
        return isOwnedByOther(player, player.level(), pos);
    }

    public static boolean isOwnedByOther(Player player, Level level, BlockPos pos) {
        if (player.isCreative() || player.isSpectator()) return false;

        // Check explicit saved/synced data
        String k = key(level, pos);
        if (level.isClientSide) {
            OwnerEntry entry = CLIENT_CACHE.get(k);
            if (entry != null) {
                if ("public".equals(entry.ownerId)) return false;
                if (("player:" + player.getStringUUID()).equals(entry.ownerId)) return false;
                if (memberOf(player, entry.ownerId)) return false;
                return true;
            }
            // Client heuristics for responsive crosshair
            BlockState state = level.getBlockState(pos);
            if (state.is(net.minecraft.tags.BlockTags.ANVIL) || state.is(net.minecraft.world.level.block.Blocks.GRINDSTONE)
                    || state.is(net.minecraft.world.level.block.Blocks.SMITHING_TABLE)
                    || state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                    || state.is(net.minecraft.world.level.block.Blocks.FURNACE)
                    || state.is(net.minecraft.world.level.block.Blocks.BLAST_FURNACE)
                    || state.is(net.minecraft.world.level.block.Blocks.SMOKER)
                    || state.is(net.minecraft.world.level.block.Blocks.BREWING_STAND)
                    || state.is(net.minecraft.world.level.block.Blocks.ENCHANTING_TABLE)
                    || state.is(net.minecraft.world.level.block.Blocks.FLOWER_POT)
                    || state.getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock) {
                return false;
            }
            if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED)) {
                return true;
            }
            if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock) {
                return false;
            }
            return false;
        }

        // Server side
        if (level instanceof ServerLevel sl) {
            OwnershipData data = OwnershipData.get(sl.getServer());
            OwnerEntry entry = data.entries.get(k);
            if (entry != null) {
                if ("public".equals(entry.ownerId)) return false;
                if (("player:" + player.getStringUUID()).equals(entry.ownerId)) return false;
                if (memberOf(player, entry.ownerId)) return false;
                return true;
            }

            // Player-placed blocks belong to the builder
            if (PlacedBlocks.isPlayerPlaced(level, pos)) return false;

            // Camps and dungeons are NEVER owned: looting them is not a crime
            if (Jail.isJailDimension(level) || Jail.isJailArea(level, pos)) return false;
            BlockEntity be = level.getBlockEntity(pos);
            String loot = Theft.lootTable(be);
            if (loot != null && !loot.contains("village") && !com.skycraft.loot.Containers.isOwned(loot)) {
                return false;
            }

            // Inside a settlement/village: only private locked chests, occupied beds, or closed doors are restricted
            if (Theft.inVillage(sl, pos)) {
                BlockState state = level.getBlockState(pos);
                // Blacksmith workstations, anvils, grindstones, furnaces, and crafting tables are ALWAYS free to use!
                if (state.is(net.minecraft.tags.BlockTags.ANVIL) || state.is(Blocks.GRINDSTONE) || state.is(Blocks.SMITHING_TABLE)
                        || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE)
                        || state.is(Blocks.SMOKER) || state.is(Blocks.BREWING_STAND) || state.is(Blocks.ENCHANTING_TABLE)
                        || state.is(Blocks.FLOWER_POT) || state.getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock) {
                    return false;
                }
                // Beds: an inn's dormitory beds are public, an inn room's bed is free to whoever rented a room there,
                // every other bed in a recorded settlement belongs to the household that sleeps in it
                if (state.getBlock() instanceof BedBlock) {
                    if (com.skycraft.tamriel.settlement.SettlementAnchors.hasAnchor(sl, pos, com.skycraft.tamriel.settlement.AnchorType.GUEST_BED, true)) {
                        return false;
                    }
                    boolean rented = player instanceof ServerPlayer sp && com.skycraft.survival.inn.Innkeepers.roomHere(sp) != null;
                    if (com.skycraft.tamriel.settlement.SettlementAnchors.hasAnchor(sl, pos, com.skycraft.tamriel.settlement.AnchorType.INN_BED, true)) {
                        return !rented;
                    }
                    if (com.skycraft.tamriel.settlement.SettlementAnchors.hasBeds(sl, pos)) return true;
                    return !rented;
                }
                // Doors are public by day (8:00 - 20:00); at night only private homes are shut (inns, shops, temples,
                // the palace and guard posts stay open), by the same night as Locks and Shop.isOpen
                if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock) {
                    return Locks.nightLocked(sl, pos);
                }
                // Containers: only village loot or locked containers belong to the household
                if (be instanceof net.minecraft.world.Container) {
                    return loot == null || Theft.owned(loot);
                }
                return false;
            }
        }

        return false;
    }

    /** True if taking this placed world item or killing this animal is a crime for this player. */
    public static boolean isOwnedByOther(Player player, Entity entity) {
        if (player.isCreative() || player.isSpectator()) return false;

        CompoundTag pd = entity.getPersistentData();
        if (pd.contains(ENTITY_OWNER_KEY)) {
            String ownerId = pd.getString(ENTITY_OWNER_KEY);
            if ("public".equals(ownerId) || ("player:" + player.getStringUUID()).equals(ownerId)) return false;
            return true;
        }

        // Domestic farm animals in settlements belong to the settlement
        if (entity instanceof Animal && entity.level() instanceof ServerLevel sl) {
            if (Theft.inVillage(sl, entity.blockPosition())) return true;
        }

        return false;
    }

    /** Server: whether a player placed or owns the block (e.g. a bed in their house); NPCs must leave it alone. */
    public static boolean isPlayerOwned(ServerLevel level, BlockPos pos) {
        if (PlacedBlocks.isPlayerPlaced(level, pos)) return true;
        OwnerEntry entry = OwnershipData.get(level.getServer()).entries.get(key(level, pos));
        return entry != null && entry.ownerId() != null && entry.ownerId().startsWith("player:");
    }

    public static void setOwner(ServerLevel level, BlockPos pos, String ownerId, Component ownerName) {
        OwnershipData data = OwnershipData.get(level.getServer());
        data.entries.put(key(level, pos), new OwnerEntry(ownerId, ownerName));
        data.setDirty();
    }

    public static void setPlayerOwner(ServerLevel level, BlockPos pos, UUID player) {
        setOwner(level, pos, "player:" + player.toString(), Component.literal(player.toString()));
    }

    /** Forgets the explicit owner of a block (and of its other half, for beds and doors). */
    private static void forget(ServerLevel level, BlockPos pos) {
        OwnershipData data = OwnershipData.get(level.getServer());
        boolean changed = data.entries.remove(key(level, pos)) != null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.FACING) && state.hasProperty(BedBlock.PART)) {
            BlockPos other = pos.relative(BedBlock.getConnectedDirection(state));
            changed |= data.entries.remove(key(level, other)) != null;
        } else if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)) {
            BlockPos other = state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            changed |= data.entries.remove(key(level, other)) != null;
        }
        if (changed) data.setDirty();
    }

    /**
     * A block a player placed belongs to the builder: a stale town owner entry at that spot (a bed or chest of the
     * town they broke) must not win over it. Breaking alone changes nothing: break events are also posted as probes.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Player) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (var snapshot : multi.getReplacedBlockSnapshots()) forget(level, snapshot.getPos());
        } else {
            forget(level, event.getPos());
        }
    }

    @Nullable
    public static Component ownerName(Level level, BlockPos pos) {
        String k = key(level, pos);
        if (level.isClientSide) {
            OwnerEntry entry = CLIENT_CACHE.get(k);
            return entry != null ? entry.ownerName() : null;
        }
        if (level instanceof ServerLevel sl) {
            OwnershipData data = OwnershipData.get(sl.getServer());
            OwnerEntry entry = data.entries.get(k);
            if (entry != null) return entry.ownerName();
            if (Theft.inVillage(sl, pos)) return Component.translatable("crime.skycraft.owner.household");
        }
        return null;
    }

    // ------------------------------------------------------------------ saved data

    public static class OwnershipData extends SavedData {
        private static final String NAME = "skycraft_ownership";
        final Map<String, OwnerEntry> entries = new HashMap<>();

        static OwnershipData get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(OwnershipData::load, OwnershipData::new, NAME);
        }

        static OwnershipData load(CompoundTag tag) {
            OwnershipData data = new OwnershipData();
            CompoundTag list = tag.getCompound("entries");
            for (String k : list.getAllKeys()) {
                CompoundTag entryTag = list.getCompound(k);
                String id = entryTag.getString("id");
                Component name = entryTag.contains("name") ? Component.Serializer.fromJson(entryTag.getString("name")) : null;
                data.entries.put(k, new OwnerEntry(id, name));
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag list = new CompoundTag();
            for (Map.Entry<String, OwnerEntry> e : entries.entrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putString("id", e.getValue().ownerId());
                if (e.getValue().ownerName() != null) {
                    entryTag.putString("name", Component.Serializer.toJson(e.getValue().ownerName()));
                }
                list.put(e.getKey(), entryTag);
            }
            tag.put("entries", list);
            return tag;
        }
    }
}
