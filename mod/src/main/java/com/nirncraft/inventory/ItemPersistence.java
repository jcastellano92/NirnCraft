package com.nirncraft.inventory;

import com.nirncraft.NirnCraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Skyrim remembers where you left things": items a player drops (and a player's death drops) never despawn.
 *
 * <p>Such items get {@link ItemEntity#setUnlimitedLifetime()} (saved with the entity as Age -32768) and the entity
 * persistent-data mark {@value #KEEP} holding the game time they were dropped. Mob drops, broken blocks, dispensers
 * etc. keep vanilla despawning. To protect servers from item hoards, each dimension keeps at most
 * {@code maxPerDimension} loaded persistent items: when exceeded, the oldest ones go back to a normal lifetime.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class ItemPersistence {
    public static final String KEEP = "nirncraft_keep";

    /** Loaded persistent items per dimension: entity UUID -> drop time. */
    private static final Map<ResourceKey<Level>, Map<UUID, Long>> LOADED = new HashMap<>();

    private ItemPersistence() {}

    private static boolean enabled() {
        try {
            return InventoryConfig.PERSISTENT_DROPS.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static int cap() {
        try {
            return InventoryConfig.PERSISTENT_CAP.get();
        } catch (IllegalStateException e) {
            return 400;
        }
    }

    /** Marks an item entity as player-dropped and makes it permanent. */
    public static void keep(ItemEntity item) {
        if (item == null || item.level().isClientSide() || !enabled()) return;
        item.getPersistentData().putLong(KEEP, item.level().getGameTime());
        item.setUnlimitedLifetime();
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onToss(ItemTossEvent event) {
        if (event.isCanceled() || event.getPlayer().level().isClientSide()) return;
        keep(event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlayerDrops(LivingDropsEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Player player) || player.level().isClientSide()) return;
        for (ItemEntity item : event.getDrops()) keep(item);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ItemEntity item)) return;
        CompoundTag data = item.getPersistentData();
        if (!data.contains(KEEP)) return;
        if (!enabled()) {
            release(item);
            return;
        }
        Map<UUID, Long> loaded = LOADED.computeIfAbsent(event.getLevel().dimension(), k -> new LinkedHashMap<>());
        loaded.put(item.getUUID(), data.getLongOr(KEEP, 0L));
        int cap = cap();
        if (loaded.size() > cap && event.getLevel() instanceof ServerLevel level) {
            enforceCap(level, loaded, cap, item);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ItemEntity item)) return;
        Map<UUID, Long> loaded = LOADED.get(event.getLevel().dimension());
        if (loaded != null) loaded.remove(item.getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOADED.clear();
    }

    /** Releases the oldest persistent items until the dimension is back under the cap. */
    private static void enforceCap(ServerLevel level, Map<UUID, Long> loaded, int cap, ItemEntity joining) {
        int guard = 0;
        while (loaded.size() > cap && guard++ < 64) {
            UUID oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<UUID, Long> e : loaded.entrySet()) {
                if (e.getValue() < oldestTime) {
                    oldestTime = e.getValue();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) return;
            loaded.remove(oldest);
            Entity entity = oldest.equals(joining.getUUID()) ? joining : level.getEntity(oldest);
            if (entity instanceof ItemEntity item) release(item);
        }
    }

    /** Back to vanilla despawning (a fresh 5-minute lifetime). */
    private static void release(ItemEntity item) {
        item.getPersistentData().remove(KEEP);
        try {
            // ItemEntity has no public age setter besides "unlimited": reload it with Age 0.
            CompoundTag tag = com.nirncraft.platform.EntityData.saveWithoutId(item);
            tag.putShort("Age", (short) 0);
            com.nirncraft.platform.EntityData.load(item, tag);
        } catch (Exception e) {
            NirnCraft.LOGGER.debug("Could not reset the lifetime of {}", item, e);
        }
    }
}
