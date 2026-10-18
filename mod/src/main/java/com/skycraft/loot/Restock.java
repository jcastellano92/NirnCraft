package com.skycraft.loot;

import com.skycraft.Skycraft;
import com.skycraft.dig.PlacedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skyrim's respawn rules for loot containers: a looted dungeon or wilderness container refills after 30 days, a
 * household or shop container after 10, a boss chest (or a placed unique) never ({@link Containers#restockDays}).
 *
 * <p>With Lootr (the pack ships it) every player has their own copy of each container and Lootr's own refresh, set by
 * the pack's {@code config/lootr-common.toml} to the same 30 days for the same tables, does the work. Without Lootr
 * (a dev server, a trimmed pack) Skycraft does it: the first time a player opens a container that still has its loot
 * table, the table and the time are remembered; opening it again after the restock time empties it and rolls the
 * table afresh, at the opener's level. Containers players placed are never touched, and neither is one the player
 * stored their own things in: what the table generated is remembered when the container is first opened, and a
 * restock waits until nothing else is inside.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Restock {
    private static Boolean lootr;
    /** Opener -> the container whose freshly generated contents are recorded when its menu opens. */
    private static final Map<UUID, BlockPos> PENDING = new HashMap<>();

    private Restock() {}

    public static boolean lootrPresent() {
        if (lootr == null) lootr = ModList.get().isLoaded("lootr");
        return lootr;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || lootrPresent()) return;
        BlockPos pos = event.getPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RandomizableContainerBlockEntity container)) return;
        Data data = Data.get(level.getServer());
        String key = key(level, pos);
        CompoundTag saved = be.saveWithoutMetadata(level.registryAccess());
        long now = level.getGameTime();
        if (com.skycraft.platform.NbtData.hasType(saved, "LootTable", Tag.TAG_STRING)) {
            String table = saved.getStringOr("LootTable","");
            if (Containers.restockDays(table) > 0) {
                CompoundTag entry = new CompoundTag();
                entry.putString("table", table);
                entry.putLong("at", now);
                data.entries.put(key, entry);
                data.setDirty();
                if (event.getEntity() instanceof ServerPlayer) PENDING.put(event.getEntity().getUUID(), pos.immutable());
            }
            return;
        }
        CompoundTag entry = data.entries.getCompoundOrEmpty(key);
        if (entry.isEmpty() || PlacedBlocks.isPlayerPlaced(level, pos)) return;
        String table = entry.getStringOr("table","");
        int days = Containers.restockDays(table);
        if (days <= 0 || now - entry.getLongOr("at",0L) < days * 24000L) return;
        // the player keeps their own things: wait until only what the table generated is left inside
        if (holdsForeignItems(entry, container)) return;
        // the time has come: empty it (the overflow behind the slots too: only table items are inside) and roll the
        // table again when this open unpacks it
        container.clearContent();
        com.skycraft.inventory.BulkStore overflow = com.skycraft.inventory.ContainerOverflow.store(container);
        if (overflow != null) overflow.clear();
        container.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, Identifier.parse(table)), level.getRandom().nextLong());
        entry.putLong("at", now);
        entry.remove("gen");
        data.setDirty();
        if (event.getEntity() instanceof ServerPlayer) PENDING.put(event.getEntity().getUUID(), pos.immutable());
    }

    /** The menu is open, so the table has been unpacked: remember what it generated. */
    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = PENDING.remove(player.getUUID());
        if (pos == null || !(player.level() instanceof ServerLevel level)) return;
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container)) return;
        Data data = Data.get(level.getServer());
        CompoundTag entry = data.entries.getCompoundOrEmpty(key(level, pos));
        if (entry.isEmpty() || com.skycraft.platform.NbtData.hasType(entry, "gen", Tag.TAG_LIST)) return;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) counts.merge(itemId(s), s.getCount(), Integer::sum);
        }
        ListTag gen = new ListTag();
        counts.forEach((id, n) -> {
            CompoundTag t = new CompoundTag();
            t.putString("id", id);
            t.putInt("n", n);
            gen.add(t);
        });
        entry.put("gen", gen);
        data.entries.put(key(level, pos), entry);
        data.setDirty();
    }

    private static String itemId(ItemStack s) {
        Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id == null ? "" : id.toString();
    }

    /**
     * Whether the container holds anything the table did not generate (an item kind it did not roll, or more of one
     * than it rolled). The overflow behind the slots ({@code inventory.ContainerOverflow}) counts as inside the
     * container: an item a player stored there is the player's. Without a record of the generated contents (entries
     * from before it was kept) any item counts.
     */
    public static boolean holdsForeignItems(CompoundTag entry, RandomizableContainerBlockEntity container) {
        Map<String, Integer> allowed = new HashMap<>();
        for (Tag t : entry.getListOrEmpty("gen")) {
            CompoundTag c = (CompoundTag) t;
            allowed.merge(c.getStringOr("id",""), c.getIntOr("n",0), Integer::sum);
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty()) continue;
            String id = itemId(s);
            int left = allowed.getOrDefault(id, 0) - s.getCount();
            if (left < 0) return true;
            allowed.put(id, left);
        }
        com.skycraft.inventory.BulkStore overflow = com.skycraft.inventory.ContainerOverflow.store(container);
        if (overflow != null) {
            for (int i = 0; i < overflow.size(); i++) {
                // a row whose item no longer resolves cannot be told from the table's: it is the player's, keep it
                ItemStack row = overflow.get(i);
                if (row.isEmpty()) return true;
                String id = itemId(row);
                long left = (long) allowed.getOrDefault(id, 0) - row.getCount();
                if (left < 0) return true;
                allowed.put(id, (int) left);
            }
        }
        return false;
    }

    private static String key(Level level, BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    /** Remembered containers: "dim|x|y|z" -> {table, at}. Stored with the overworld. */
    public static class Data extends SavedData {
        private static final String NAME = "skycraft_restock";
        final CompoundTag entries = new CompoundTag();

        static Data get(MinecraftServer server) {
            return com.skycraft.platform.SavedDataTypes.get(server.overworld(), NAME, Data::load, Data::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            d.entries.merge(tag.getCompoundOrEmpty("entries"));
            return d;
        }

        public CompoundTag save(CompoundTag tag) {
            tag.put("entries", entries.copy());
            return tag;
        }
    }
}
