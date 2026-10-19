package com.nirncraft.society;

import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The world's register of named people (overworld saved data {@code nirncraft_npcs}): who they are, where they live,
 * whether they're alive and whom they married. Names are unique across the world, so every Lydia-like housecarl and
 * every farmer is a different person. Entries are written when an NPC is created, moves home, marries or dies; the
 * entities themselves still carry all of their state.
 */
public final class NpcRegistry extends SavedData {
    private static final String NAME = "nirncraft_npcs";
    /** Persistent-data key holding an NPC's plain personal name. */
    public static final String NAME_KEY = "nirncraft_name";
    private static final int NAME_TRIES = 16;

    private final Map<UUID, CompoundTag> entries = new HashMap<>();
    private final Set<String> names = new HashSet<>();

    public static NpcRegistry get(MinecraftServer server) {
        return com.nirncraft.platform.SavedDataTypes.get(server.overworld(), NAME, NpcRegistry::load, NpcRegistry::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
    }

    @Nullable
    public static NpcRegistry get(net.minecraft.world.level.Level level) {
        return level instanceof ServerLevel sl ? get(sl.getServer()) : null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** A full name nobody else in the world has (gives up after a few tries and accepts a repeat). */
    public String uniqueName(NpcNames.Race race, boolean female, net.minecraft.util.RandomSource r) {
        String name = NpcNames.fullName(race, female, r);
        for (int i = 0; i < NAME_TRIES && names.contains(key(name)); i++) name = NpcNames.fullName(race, female, r);
        return name;
    }

    public boolean taken(String name) {
        return names.contains(key(name));
    }

    /** Records a named NPC that settled somewhere (server; no-op for unnamed NPCs or unchanged entries). */
    public static void onSettled(NpcEntity npc) {
        String name = npc.getPersistentData().getStringOr(NAME_KEY, "");
        NpcRegistry registry = get(npc.level());
        if (registry != null && !name.isEmpty()) registry.record(npc, name);
    }

    /** Records (or refreshes) a named NPC. */
    public void record(NpcEntity npc, String name) {
        CompoundTag e = entries.computeIfAbsent(npc.getUUID(), k -> new CompoundTag());
        CompoundTag before = e.copy();
        String old = e.getStringOr("name","");
        if (!old.isEmpty() && !old.equals(name)) names.remove(key(old));
        e.putString("name", name);
        e.putString("role", npc.role().id);
        e.putInt("look", npc.getLook());
        e.putString("dim", npc.level().dimension().identifier().toString());
        BlockPos home = npc.getHome() != null ? npc.getHome() : npc.blockPosition();
        e.putLong("home", home.asLong());
        e.putBoolean("dead", false);
        names.add(key(name));
        if (!e.equals(before)) setDirty();
    }

    public void markDead(UUID id) {
        CompoundTag e = entries.get(id);
        if (e == null || e.getBooleanOr("dead",false)) return;
        e.putBoolean("dead", true);
        setDirty();
    }

    public void setSpouse(UUID npc, @Nullable UUID player) {
        CompoundTag e = entries.get(npc);
        if (e == null) return;
        if (player == null) e.remove("spouse");
        else e.store("spouse", net.minecraft.core.UUIDUtil.CODEC,  player);
        setDirty();
    }

    public void setHome(UUID npc, BlockPos home, String dim) {
        CompoundTag e = entries.get(npc);
        if (e == null) return;
        e.putLong("home", home.asLong());
        e.putString("dim", dim);
        setDirty();
    }

    @Nullable
    public CompoundTag entry(UUID id) {
        return entries.get(id);
    }

    public boolean isDead(UUID id) {
        CompoundTag e = entries.get(id);
        return e != null && e.getBooleanOr("dead",false);
    }

    public int size() {
        return entries.size();
    }

    static NpcRegistry load(CompoundTag tag) {
        NpcRegistry r = new NpcRegistry();
        CompoundTag all = tag.getCompoundOrEmpty("npcs");
        for (String k : all.keySet()) {
            try {
                CompoundTag e = all.getCompoundOrEmpty(k);
                r.entries.put(UUID.fromString(k), e);
                if (!e.getBooleanOr("dead",false) && com.nirncraft.platform.NbtData.hasType(e, "name", Tag.TAG_STRING)) r.names.add(key(e.getStringOr("name","")));
            } catch (IllegalArgumentException ignored) {
                // a corrupt key: skip it
            }
        }
        return r;
    }

    public CompoundTag save(CompoundTag tag) {
        CompoundTag all = new CompoundTag();
        entries.forEach((id, e) -> all.put(id.toString(), e));
        tag.put("npcs", all);
        return tag;
    }
}
