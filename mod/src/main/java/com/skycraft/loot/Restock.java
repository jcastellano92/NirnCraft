package com.skycraft.loot;

import com.skycraft.Skycraft;
import com.skycraft.dig.PlacedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * Skyrim's respawn rules for loot containers: a looted dungeon or wilderness container refills after 30 days, a
 * household or shop container after 10, a boss chest (or a placed unique) never ({@link Containers#restockDays}).
 *
 * <p>With Lootr (the pack ships it) every player has their own copy of each container and Lootr's own refresh, set by
 * the pack's {@code config/lootr-common.toml} to the same 30 days for the same tables, does the work. Without Lootr
 * (a dev server, a trimmed pack) Skycraft does it: the first time a player opens a container that still has its loot
 * table, the table and the time are remembered; opening it again after the restock time empties it and rolls the
 * table afresh, at the opener's level. Containers players placed are never touched.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Restock {
    private static Boolean lootr;

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
        CompoundTag saved = be.saveWithoutMetadata();
        long now = level.getGameTime();
        if (saved.contains("LootTable", Tag.TAG_STRING)) {
            String table = saved.getString("LootTable");
            if (Containers.restockDays(table) > 0) {
                CompoundTag entry = new CompoundTag();
                entry.putString("table", table);
                entry.putLong("at", now);
                data.entries.put(key, entry);
                data.setDirty();
            }
            return;
        }
        CompoundTag entry = data.entries.getCompound(key);
        if (entry.isEmpty() || PlacedBlocks.isPlayerPlaced(level, pos)) return;
        String table = entry.getString("table");
        int days = Containers.restockDays(table);
        if (days <= 0 || now - entry.getLong("at") < days * 24000L) return;
        // the time has come: empty it and roll the table again when this open unpacks it
        container.clearContent();
        container.setLootTable(new ResourceLocation(table), level.getRandom().nextLong());
        entry.putLong("at", now);
        data.setDirty();
    }

    private static String key(Level level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    /** Remembered containers: "dim|x|y|z" -> {table, at}. Stored with the overworld. */
    public static class Data extends SavedData {
        private static final String NAME = "skycraft_restock";
        final CompoundTag entries = new CompoundTag();

        static Data get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(Data::load, Data::new, NAME);
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            d.entries.merge(tag.getCompound("entries"));
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.put("entries", entries.copy());
            return tag;
        }
    }
}
