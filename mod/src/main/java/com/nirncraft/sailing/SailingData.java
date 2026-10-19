package com.nirncraft.sailing;

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
 * Per-world sailing state, stored with the overworld ({@code data/nirncraft_sailing.dat}): which ports have their pier
 * built (or a settlement's dock adopted), where travellers arrive, and who keeps the harbour.
 */
public final class SailingData extends SavedData {
    private static final String NAME = "nirncraft_sailing";

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
            t.put("arrival", com.nirncraft.platform.NbtPositions.write(arrival));
            t.put("keeper", com.nirncraft.platform.NbtPositions.write(keeper));
            t.putBoolean("adopted", adopted);
            if (harbormaster != null) t.store("harbormaster", net.minecraft.core.UUIDUtil.CODEC,  harbormaster);
            if (ferry != null) t.store("ferry", net.minecraft.core.UUIDUtil.CODEC,  ferry);
            t.putLong("keeperSeen", keeperSeen);
            return t;
        }

        static Dock load(CompoundTag t) {
            Dock d = new Dock();
            d.arrival = com.nirncraft.platform.NbtPositions.read(t.getCompoundOrEmpty("arrival"));
            d.keeper = com.nirncraft.platform.NbtPositions.read(t.getCompoundOrEmpty("keeper"));
            d.adopted = t.getBooleanOr("adopted",false);
            d.harbormaster = t.read("harbormaster", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? t.read("harbormaster", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
            d.ferry = t.read("ferry", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? t.read("ferry", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : null;
            d.keeperSeen = t.getLongOr("keeperSeen",0L);
            return d;
        }
    }

    final Map<String, Dock> docks = new HashMap<>();
    /** Docks registered by other modules (e.g. settlements building their own harbour): port id -> pier head. */
    final Map<String, BlockPos> external = new HashMap<>();

    public static SailingData get(MinecraftServer server) {
        return com.nirncraft.platform.SavedDataTypes.get(server.overworld(), NAME, SailingData::load, SailingData::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
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
        CompoundTag docks = tag.getCompoundOrEmpty("docks");
        for (String id : docks.keySet()) data.docks.put(id, Dock.load(docks.getCompoundOrEmpty(id)));
        CompoundTag ext = tag.getCompoundOrEmpty("external");
        for (String id : ext.keySet()) data.external.put(id, com.nirncraft.platform.NbtPositions.read(ext.getCompoundOrEmpty(id)));
        return data;
    }

    public CompoundTag save(CompoundTag tag) {
        CompoundTag docks = new CompoundTag();
        this.docks.forEach((id, d) -> docks.put(id, d.save()));
        tag.put("docks", docks);
        CompoundTag ext = new CompoundTag();
        external.forEach((id, p) -> ext.put(id, com.nirncraft.platform.NbtPositions.write(p)));
        tag.put("external", ext);
        return tag;
    }
}
