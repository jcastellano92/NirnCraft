package com.skycraft.quest;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-level storage of every quest, keyed by owner ({@code party:<uuid>} or {@code p:<player uuid>}), plus rewards
 * waiting for party members who were offline when a shared quest was completed.
 */
public class QuestStore extends SavedData {
    private static final String NAME = "skycraft_quests";
    /** Completed/failed quests kept per owner for the journal. */
    private static final int HISTORY = 40;

    private final Map<String, List<Quest>> quests = new HashMap<>();
    private final Map<UUID, ListTag> pending = new HashMap<>();
    private final Map<String, UUID> designatedGivers = new HashMap<>();

    public static QuestStore get(MinecraftServer server) {
        return com.skycraft.platform.SavedDataTypes.get(server.overworld(), NAME, QuestStore::load, QuestStore::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
    }

    @Nullable
    public UUID getDesignatedGiver(String questId) {
        return designatedGivers.get(questId);
    }

    public void setDesignatedGiver(String questId, UUID giver) {
        designatedGivers.put(questId, giver);
        setDirty();
    }

    public List<Quest> quests(String owner) {
        return quests.computeIfAbsent(owner, k -> new ArrayList<>());
    }

    public Map<String, List<Quest>> all() {
        return quests;
    }

    @Nullable
    public Quest find(String owner, String id) {
        List<Quest> list = quests.get(owner);
        if (list == null) return null;
        for (Quest q : list) if (q.id.equals(id)) return q;
        return null;
    }

    public void remove(String owner) {
        quests.remove(owner);
        setDirty();
    }

    /** Drops the oldest finished quests beyond the history limit. */
    public void trim(String owner) {
        List<Quest> list = quests.get(owner);
        if (list == null) return;
        int finished = 0;
        for (Quest q : list) if (!q.isActive()) finished++;
        for (int i = 0; i < list.size() && finished > HISTORY; ) {
            if (!list.get(i).isActive()) {
                list.remove(i);
                finished--;
            } else {
                i++;
            }
        }
    }

    public void addPending(UUID player, CompoundTag reward) {
        pending.computeIfAbsent(player, k -> new ListTag()).add(reward);
        setDirty();
    }

    @Nullable
    public ListTag takePending(UUID player) {
        ListTag list = pending.remove(player);
        if (list != null) setDirty();
        return list;
    }

    // ------------------------------------------------------------------ persistence

    public static QuestStore load(CompoundTag tag) {
        QuestStore store = new QuestStore();
        CompoundTag owners = tag.getCompoundOrEmpty("owners");
        for (String owner : owners.keySet()) {
            List<Quest> list = new ArrayList<>();
            for (Tag t : owners.getListOrEmpty(owner)) list.add(Quest.load((CompoundTag) t));
            store.quests.put(owner, list);
        }
        CompoundTag pend = tag.getCompoundOrEmpty("pending");
        for (String key : pend.keySet()) {
            try {
                store.pending.put(UUID.fromString(key), pend.getListOrEmpty(key).copy());
            } catch (IllegalArgumentException ignored) {
            }
        }
        CompoundTag givers = tag.getCompoundOrEmpty("designated_givers");
        for (String qId : givers.keySet()) {
            try {
                store.designatedGivers.put(qId, UUID.fromString(givers.getStringOr(qId, "")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return store;
    }

    public CompoundTag save(CompoundTag tag) {
        CompoundTag owners = new CompoundTag();
        quests.forEach((owner, list) -> {
            if (list.isEmpty()) return;
            ListTag l = new ListTag();
            for (Quest q : list) l.add(q.save());
            owners.put(owner, l);
        });
        tag.put("owners", owners);
        CompoundTag pend = new CompoundTag();
        pending.forEach((id, list) -> pend.put(id.toString(), list.copy()));
        tag.put("pending", pend);
        CompoundTag givers = new CompoundTag();
        designatedGivers.forEach((qId, uuid) -> givers.putString(qId, uuid.toString()));
        tag.put("designated_givers", givers);
        return tag;
    }
}
