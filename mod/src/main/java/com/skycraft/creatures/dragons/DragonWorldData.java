package com.skycraft.creatures.dragons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * World state of Skyrim's dragons (overworld): which burial mounds were already raised, when each word wall may host a
 * lair dragon again, and whether Paarthurnax is dead.
 */
public final class DragonWorldData extends SavedData {
    private static final String NAME = "skycraft_dragons";

    private final Set<String> raisedMounds = new HashSet<>();
    private final Map<String, Long> lairReady = new HashMap<>();
    private boolean paarthurnaxDead;

    public static DragonWorldData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(DragonWorldData::load, DragonWorldData::new, NAME);
    }

    public boolean isRaised(String mound) {
        return raisedMounds.contains(mound);
    }

    public void markRaised(String mound) {
        if (raisedMounds.add(mound)) setDirty();
    }

    /** Game time from which a lair dragon may appear at this place again (0 = any time). */
    public long lairReady(String place) {
        return lairReady.getOrDefault(place, 0L);
    }

    public void setLairReady(String place, long gameTime) {
        lairReady.put(place, gameTime);
        setDirty();
    }

    public boolean paarthurnaxDead() {
        return paarthurnaxDead;
    }

    public void setPaarthurnaxDead() {
        paarthurnaxDead = true;
        setDirty();
    }

    private static DragonWorldData load(CompoundTag tag) {
        DragonWorldData d = new DragonWorldData();
        ListTag raised = tag.getList("Raised", Tag.TAG_STRING);
        for (int i = 0; i < raised.size(); i++) d.raisedMounds.add(raised.getString(i));
        CompoundTag lairs = tag.getCompound("Lairs");
        for (String k : lairs.getAllKeys()) d.lairReady.put(k, lairs.getLong(k));
        d.paarthurnaxDead = tag.getBoolean("PaarthurnaxDead");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag raised = new ListTag();
        for (String m : raisedMounds) raised.add(StringTag.valueOf(m));
        tag.put("Raised", raised);
        CompoundTag lairs = new CompoundTag();
        lairReady.forEach(lairs::putLong);
        tag.put("Lairs", lairs);
        tag.putBoolean("PaarthurnaxDead", paarthurnaxDead);
        return tag;
    }
}
