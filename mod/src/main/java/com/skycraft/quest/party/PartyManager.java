package com.skycraft.quest.party;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** World-level storage of all parties. Game logic (invites, messages) lives in {@link Parties}. */
public class PartyManager extends SavedData {
    private static final String NAME = "skycraft_parties";

    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, UUID> byPlayer = new HashMap<>();

    public static PartyManager get(MinecraftServer server) {
        return com.skycraft.platform.SavedDataTypes.get(server.overworld(), NAME, PartyManager::load, PartyManager::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
    }

    @Nullable
    public Party partyOf(UUID player) {
        UUID id = byPlayer.get(player);
        return id == null ? null : parties.get(id);
    }

    @Nullable
    public Party byId(UUID id) {
        return parties.get(id);
    }

    public Collection<Party> all() {
        return parties.values();
    }

    public Party create(UUID leader, String leaderName) {
        Party party = new Party(UUID.randomUUID(), leader);
        party.members.put(leader, leaderName);
        parties.put(party.id, party);
        byPlayer.put(leader, party.id);
        setDirty();
        return party;
    }

    public void addMember(Party party, UUID player, String name) {
        party.members.put(player, name);
        byPlayer.put(player, party.id);
        setDirty();
    }

    /** Removes a member; returns true if the party is now empty and was deleted. */
    public boolean removeMember(Party party, UUID player) {
        party.members.remove(player);
        byPlayer.remove(player);
        if (party.members.isEmpty()) {
            parties.remove(party.id);
            setDirty();
            return true;
        }
        if (party.leader.equals(player)) party.leader = party.members.keySet().iterator().next();
        setDirty();
        return false;
    }

    public void rename(UUID player, String name) {
        Party p = partyOf(player);
        if (p != null && !name.equals(p.members.get(player))) {
            p.members.put(player, name);
            setDirty();
        }
    }

    public static PartyManager load(CompoundTag tag) {
        PartyManager m = new PartyManager();
        ListTag list = tag.getListOrEmpty("parties");
        for (Tag t : list) {
            try {
                Party p = Party.load((CompoundTag) t);
                if (p.members.isEmpty()) continue;
                if (!p.members.containsKey(p.leader)) p.leader = p.members.keySet().iterator().next();
                m.parties.put(p.id, p);
                for (UUID member : p.members.keySet()) m.byPlayer.put(member, p.id);
            } catch (Exception ignored) {
                // corrupt entry: skip
            }
        }
        return m;
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Party p : parties.values()) list.add(p.save());
        tag.put("parties", list);
        return tag;
    }
}
