package com.skycraft.sailing;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-world sailing state, stored with the overworld ({@code data/skycraft_sailing.dat}): which ports have their pier
 * built (or a settlement's dock adopted), where travellers arrive, and who keeps the harbour.
 */
public final class SailingData extends SavedData {
    private static final String NAME = "skycraft_sailing";

    /** One port's state in this world. */
    public static final class Dock {
        /** Where passengers step ashore (on the pier head). */
        public BlockPos arrival;
        /** Where the harbormaster stands. */
        public BlockPos keeper;
        /** True when a settlement's own dock was adopted instead of building one. */
        public boolean adopted;
        @Nullable
        public UUID harbormaster;
        @Nullable
        public UUID ferry;
        /** Game time the harbormaster was last seen (or spawned). */
        public long keeperSeen;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.put("arrival", NbtUtils.writeBlockPos(arrival));
            t.put("keeper", NbtUtils.writeBlockPos(keeper));
            t.putBoolean("adopted", adopted);
            if (harbormaster != null) t.putUUID("harbormaster", harbormaster);
            if (ferry != null) t.putUUID("ferry", ferry);
            t.putLong("keeperSeen", keeperSeen);
            return t;
        }

        static Dock load(CompoundTag t) {
            Dock d = new Dock();
            d.arrival = NbtUtils.readBlockPos(t.getCompound("arrival"));
            d.keeper = NbtUtils.readBlockPos(t.getCompound("keeper"));
            d.adopted = t.getBoolean("adopted");
            d.harbormaster = t.hasUUID("harbormaster") ? t.getUUID("harbormaster") : null;
            d.ferry = t.hasUUID("ferry") ? t.getUUID("ferry") : null;
            d.keeperSeen = t.getLong("keeperSeen");
            return d;
        }
    }

    final Map<String, Dock> docks = new HashMap<>();
    /** Docks registered by other modules (e.g. settlements building their own harbour): port id -> pier head. */
    final Map<String, BlockPos> external = new HashMap<>();

    public static SailingData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SailingData::load, SailingData::new, NAME);
    }

    @Nullable
    public Dock dock(String port) {
        return docks.get(port);
    }

    void put(String port, Dock dock) {
        docks.put(port, dock);
        setDirty();
    }

    static SailingData load(CompoundTag tag) {
        SailingData data = new SailingData();
        CompoundTag docks = tag.getCompound("docks");
        for (String id : docks.getAllKeys()) data.docks.put(id, Dock.load(docks.getCompound(id)));
        CompoundTag ext = tag.getCompound("external");
        for (String id : ext.getAllKeys()) data.external.put(id, NbtUtils.readBlockPos(ext.getCompound(id)));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag docks = new CompoundTag();
        this.docks.forEach((id, d) -> docks.put(id, d.save()));
        tag.put("docks", docks);
        CompoundTag ext = new CompoundTag();
        external.forEach((id, p) -> ext.put(id, NbtUtils.writeBlockPos(p)));
        tag.put("external", ext);
        return tag;
    }
}
