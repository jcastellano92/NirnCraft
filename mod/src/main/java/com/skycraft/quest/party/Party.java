package com.skycraft.quest.party;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A group of players adventuring together: shared quests, shared rewards, no friendly fire. */
public class Party {
    public final UUID id;
    public UUID leader;
    /** Members in join order, with their last known names (for offline display). */
    public final Map<UUID, String> members = new LinkedHashMap<>();

    public Party(UUID id, UUID leader) {
        this.id = id;
        this.leader = leader;
    }

    public Set<UUID> memberIds() {
        return members.keySet();
    }

    public boolean has(UUID player) {
        return members.containsKey(player);
    }

    public String nameOf(UUID player) {
        return members.getOrDefault(player, "?");
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.store("id", net.minecraft.core.UUIDUtil.CODEC,  id);
        t.store("leader", net.minecraft.core.UUIDUtil.CODEC,  leader);
        ListTag list = new ListTag();
        members.forEach((uuid, name) -> {
            CompoundTag m = new CompoundTag();
            m.store("uuid", net.minecraft.core.UUIDUtil.CODEC,  uuid);
            m.putString("name", name);
            list.add(m);
        });
        t.put("members", list);
        return t;
    }

    public static Party load(CompoundTag t) {
        Party p = new Party(t.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow(), t.read("leader", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        for (Tag tag : t.getListOrEmpty("members")) {
            CompoundTag m = (CompoundTag) tag;
            p.members.put(m.read("uuid", net.minecraft.core.UUIDUtil.CODEC).orElseThrow(), m.getStringOr("name",""));
        }
        return p;
    }
}
