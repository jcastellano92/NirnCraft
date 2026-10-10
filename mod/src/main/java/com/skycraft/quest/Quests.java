package com.skycraft.quest;

import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.NotifyKind;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.party.Party;
import com.skycraft.quest.party.PartyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Quest lifecycle: starting, progressing, completing (shared rewards), failing and syncing quests. Quests are stored
 * per owner: {@code party:<id>} when the player is in a party (the whole party shares progress and rewards) or
 * {@code p:<uuid>} for solo players. The main quest always belongs to the player personally.
 */
public final class Quests {
    /** Persistent-data keys under which summons/minions may record their owning player. */
    private static final String[] SUMMON_OWNER_KEYS = {"skycraft_owner", "skycraft_summoner", "skycraft_summon_owner"};

    /** {@link Quest#extra} list of member UUIDs in the party when the quest was accepted (or shared with it). */
    private static final String ACCEPTED_BY = "accepted_by";
    /** {@link Quest#extra} flag: every eligible party member gets the full gold reward instead of a share. */
    public static final String PER_PLAYER = "per_player";

    private static final Set<String> DIRTY_KEYS = new HashSet<>();
    private static final Set<UUID> DIRTY_PLAYERS = new HashSet<>();

    private Quests() {}

    public record Ref(String key, Quest quest) {
    }

    // ------------------------------------------------------------------ owners

    public static String personalKey(UUID player) {
        return "p:" + player;
    }

    public static String partyKey(UUID party) {
        return "party:" + party;
    }

    public static boolean isPartyKey(String key) {
        return key.startsWith("party:");
    }

    /** Every owner key whose quests concern this player: personal first, then the party's. */
    public static List<String> keysOf(MinecraftServer server, UUID player) {
        List<String> out = new ArrayList<>(2);
        out.add(personalKey(player));
        Party party = PartyManager.get(server).partyOf(player);
        if (party != null) out.add(partyKey(party.id));
        return out;
    }

    /** Where a new side quest of this player goes. */
    public static String keyForNew(MinecraftServer server, UUID player) {
        Party party = PartyManager.get(server).partyOf(player);
        return party != null ? partyKey(party.id) : personalKey(player);
    }

    public static List<UUID> members(MinecraftServer server, String key) {
        try {
            if (key.startsWith("party:")) {
                Party p = PartyManager.get(server).byId(UUID.fromString(key.substring(6)));
                return p == null ? List.of() : new ArrayList<>(p.memberIds());
            }
            if (key.startsWith("p:")) return List.of(UUID.fromString(key.substring(2)));
        } catch (IllegalArgumentException ignored) {
        }
        return List.of();
    }

    public static List<ServerPlayer> onlineMembers(MinecraftServer server, String key) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : members(server, key)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    public static boolean isMember(MinecraftServer server, String key, @Nullable Player player) {
        return player != null && members(server, key).contains(player.getUUID());
    }

    /** All active quests concerning this player (personal and party). */
    public static List<Ref> active(MinecraftServer server, UUID player) {
        List<Ref> out = new ArrayList<>();
        QuestStore store = QuestStore.get(server);
        for (String key : keysOf(server, player)) {
            for (Quest q : store.quests(key)) if (q.isActive()) out.add(new Ref(key, q));
        }
        return out;
    }

    @Nullable
    public static Ref find(MinecraftServer server, UUID player, String questId) {
        QuestStore store = QuestStore.get(server);
        for (String key : keysOf(server, player)) {
            Quest q = store.find(key, questId);
            if (q != null) return new Ref(key, q);
        }
        return null;
    }

    public static boolean hasActiveKind(MinecraftServer server, UUID player, String kindPrefix) {
        for (Ref r : active(server, player)) if (r.quest().kind.startsWith(kindPrefix)) return true;
        return false;
    }

    public static int activeSideQuests(MinecraftServer server, String key) {
        int n = 0;
        for (Quest q : QuestStore.get(server).quests(key)) if (q.isActive() && !q.isMain()) n++;
        return n;
    }

    public static boolean canTakeMore(ServerPlayer player) {
        return activeSideQuests(player.server, keyForNew(player.server, player.getUUID())) < QuestConfig.MAX_ACTIVE_QUESTS.get();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts a quest for the player (their party's if they're in one). */
    public static boolean start(ServerPlayer player, Quest q) {
        String key = q.isMain() ? personalKey(player.getUUID()) : keyForNew(player.server, player.getUUID());
        return start(player.server, key, q, player);
    }

    public static boolean start(MinecraftServer server, String key, Quest q, @Nullable ServerPlayer starter) {
        QuestStore store = QuestStore.get(server);
        if (!q.isMain() && activeSideQuests(server, key) >= QuestConfig.MAX_ACTIVE_QUESTS.get()) {
            if (starter != null) Notifier.message(starter, Component.translatable("quest.skycraft.too_many", QuestConfig.MAX_ACTIVE_QUESTS.get()));
            return false;
        }
        if (q.id.isEmpty()) q.id = newId(store, key);
        q.status = Quest.Status.ACTIVE;
        q.started = server.overworld().getGameTime();
        recordAcceptedBy(server, key, q);
        store.quests(key).add(q);
        Objective first = q.current();
        if (first != null) begin(server, key, q, first);
        if (starter != null) {
            for (Tag t : q.extra.getList("give_on_start", Tag.TAG_COMPOUND)) give(starter, ItemStack.of((CompoundTag) t));
        }
        Component sub = first != null ? first.text : Component.empty();
        for (ServerPlayer m : onlineMembers(server, key)) Notifier.send(m, NotifyKind.QUEST_STARTED, q.title, sub);
        changed(server, key);
        return true;
    }

    private static String newId(QuestStore store, String key) {
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 8);
        } while (store.find(key, id) != null);
        return id;
    }

    /** Prepares an objective that just became current (condition baselines, start time, story NPC spot...). */
    private static void begin(MinecraftServer server, String key, Quest q, Objective o) {
        if (o.type == Objective.Type.CONDITION && o.target.startsWith("crime:")) {
            // baselines are filled in lazily per member by checkCondition
            o.data.putBoolean("begun", true);
        }
        com.skycraft.quest.story.Story.onBegin(server, key, q, o);
    }

    /** Adds kill/clear progress; completes the objective when the count is reached. */
    public static void addProgress(MinecraftServer server, String key, Quest q, Objective o, int amount, @Nullable ServerPlayer actor) {
        if (o.done || !q.isActive()) return;
        o.progress = Math.min(o.required, o.progress + amount);
        if (o.progress >= o.required) {
            completeObjective(server, key, q, o, actor);
        } else {
            Component line = Component.translatable("quest.skycraft.progress", o.text, o.progress, o.required);
            for (ServerPlayer m : onlineMembers(server, key)) Notifier.message(m, line);
            changed(server, key);
        }
    }

    public static void completeObjective(MinecraftServer server, String key, Quest q, Objective o, @Nullable ServerPlayer actor) {
        if (o.done || !q.isActive()) return;
        o.done = true;
        o.progress = o.required;
        runOnComplete(server, key, q, o, actor);
        com.skycraft.quest.story.Story.onObjectiveCompleted(server, key, q, o, actor);
        if (!q.isActive()) return; // an action ended the quest
        Objective next = q.current();
        if (next == null) {
            completeQuest(server, key, q);
            return;
        }
        begin(server, key, q, next);
        for (ServerPlayer m : onlineMembers(server, key)) Notifier.send(m, NotifyKind.QUEST_UPDATED, q.title, next.text);
        changed(server, key);
    }

    /**
     * Runs the objective's completion actions ({@code give:skycraft:ancient_tome,...;scene:...}). The action language
     * is shared with the story quests, see {@link com.skycraft.quest.story.Story#runActions}.
     */
    private static void runOnComplete(MinecraftServer server, String key, Quest q, Objective o, @Nullable ServerPlayer actor) {
        if (o.onComplete == null || o.onComplete.isEmpty()) return;
        // older saves wrote "give:a,give:b": every comma-separated part may repeat the "give:" prefix
        String actions = o.onComplete.replace(",give:", ",");
        com.skycraft.quest.story.Story.runActions(server, key, q, actions, actor);
    }

    public static void completeQuest(MinecraftServer server, String key, Quest q) {
        if (!q.isActive()) return;
        q.status = Quest.Status.COMPLETED;
        q.finished = server.overworld().getGameTime();
        for (Objective o : q.objectives) o.done = true;
        QuestStore store = QuestStore.get(server);
        List<UUID> members = eligible(server, key, q);
        boolean shared = isPartyKey(key) && members.size() > 1;
        // gold is split between the eligible members (unless the quest pays each of them), items go to each
        Quest paid = q;
        if (shared && !q.extra.getBoolean(PER_PLAYER)) {
            paid = Quest.load(q.save());
            paid.rewardGold = Math.round(q.rewardGold / (double) members.size());
        }
        for (UUID id : members) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null) {
                grantRewards(m, paid, shared);
            } else {
                CompoundTag pending = new CompoundTag();
                pending.put("quest", paid.save());
                pending.putBoolean("shared", shared);
                store.addPending(id, pending);
            }
        }
        store.trim(key);
        changed(server, key);
        if (q.extra.contains(com.skycraft.quest.story.Story.DEF)) com.skycraft.quest.story.Story.onQuestCompleted(server, key, q);
        else if (q.isMain()) MainQuest.onStageCompleted(server, key, q);
    }

    /** Remembers who was in the party when it took on (or was given) the quest. */
    private static void recordAcceptedBy(MinecraftServer server, String key, Quest q) {
        if (!isPartyKey(key)) return;
        ListTag list = new ListTag();
        for (UUID id : members(server, key)) list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        q.extra.put(ACCEPTED_BY, list);
    }

    /**
     * Who gets the rewards: party members who were in the party when the quest was accepted, plus later joiners who
     * are online and within 64 blocks of one of them (or of the last objective) at completion. Quests without the
     * record (older saves, solo keys) reward every member.
     */
    private static List<UUID> eligible(MinecraftServer server, String key, Quest q) {
        List<UUID> members = members(server, key);
        if (!isPartyKey(key) || !q.extra.contains(ACCEPTED_BY, Tag.TAG_LIST)) return members;
        Set<String> accepted = new HashSet<>();
        for (Tag t : q.extra.getList(ACCEPTED_BY, Tag.TAG_STRING)) accepted.add(t.getAsString());
        List<ServerPlayer> anchors = new ArrayList<>();
        for (UUID id : members) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && accepted.contains(id.toString())) anchors.add(p);
        }
        Objective last = q.objectives.isEmpty() ? null : q.objectives.get(q.objectives.size() - 1);
        List<UUID> out = new ArrayList<>();
        for (UUID id : members) {
            if (accepted.contains(id.toString())) {
                out.add(id);
                continue;
            }
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            boolean near = false;
            for (ServerPlayer a : anchors) {
                if (a.level() == p.level() && a.distanceToSqr(p) <= 64 * 64) near = true;
            }
            if (!near && last != null && last.hasPos && p.level().dimension().location().toString().equals(last.dim)
                    && p.blockPosition().distSqr(last.pos) <= 64 * 64) near = true;
            if (near) out.add(id);
        }
        return out.isEmpty() ? members : out;
    }

    public static void fail(MinecraftServer server, String key, Quest q, Component reason) {
        if (!q.isActive()) return;
        q.status = Quest.Status.FAILED;
        q.finished = server.overworld().getGameTime();
        q.extra.putString("failReason", Quest.writeComponent(reason));
        for (ServerPlayer m : onlineMembers(server, key)) Notifier.send(m, NotifyKind.QUEST_FAILED, q.title, reason);
        QuestStore.get(server).trim(key);
        changed(server, key);
    }

    /** Player gives up a side quest (main quest can't be abandoned). */
    public static void abandon(ServerPlayer player, String questId) {
        Ref ref = find(player.server, player.getUUID(), questId);
        if (ref == null || !ref.quest().isActive() || ref.quest().isMain()) return;
        fail(player.server, ref.key(), ref.quest(), Component.translatable("quest.skycraft.abandoned", player.getDisplayName()));
    }

    /** Share a personal active side quest with the player's party. */
    public static void shareWithParty(ServerPlayer player, String questId) {
        Party party = PartyManager.get(player.server).partyOf(player.getUUID());
        if (party == null) {
            Notifier.message(player, Component.translatable("party.skycraft.no_party"));
            return;
        }
        QuestStore store = QuestStore.get(player.server);
        String pKey = personalKey(player.getUUID());
        Quest q = store.find(pKey, questId);
        if (q == null || !q.isActive() || q.isMain()) return;

        String ptKey = partyKey(party.id);
        // Move from personal list to party list
        store.quests(pKey).remove(q);
        if (store.find(ptKey, q.id) != null) q.id = newId(store, ptKey);
        recordAcceptedBy(player.server, ptKey, q);
        store.quests(ptKey).add(q);
        for (ServerPlayer m : onlineMembers(player.server, ptKey)) {
            Notifier.message(m, Component.translatable("quest.skycraft.shared_with_party", player.getDisplayName(), q.title));
        }
        changed(player.server, pKey);
        changed(player.server, ptKey);
    }

    // ------------------------------------------------------------------ rewards

    public static void grantRewards(ServerPlayer player, Quest q, boolean shared) {
        PlayerData data = SkyData.get(player);
        double mult = QuestConfig.GOLD_MULTIPLIER.get() * (q.sneakBonus() ? 1.5 : 1.0);
        long gold = Math.round(q.rewardGold * mult);
        Notifier.send(player, NotifyKind.QUEST_COMPLETED, q.title,
                shared ? Component.translatable("quest.skycraft.rewards_shared") : Component.empty());
        if (q.sneakBonus()) Notifier.message(player, Component.translatable("quest.skycraft.sneak_bonus"));
        Currency.give(player, gold);
        for (ItemStack s : q.rewardItems) give(player, s.copy());
        if (!q.faction.isEmpty()) Factions.onQuestReward(player, q);
        com.skycraft.quest.story.Story.grantStoryRewards(player, q);
        if (q.kind.startsWith("college_")) {
            data.module("quest").putBoolean(q.kind + "_completed", true);
        }
        data.addStat("quests_completed", 1);
        if (q.category == Quest.Category.BOUNTY) data.addStat("bounties_collected", 1);
        data.markDirty();
    }

    /** Delivers rewards of shared quests completed while the player was offline. */
    public static void deliverPending(ServerPlayer player) {
        ListTag list = QuestStore.get(player.server).takePending(player.getUUID());
        if (list == null) return;
        for (Tag t : list) {
            CompoundTag c = (CompoundTag) t;
            grantRewards(player, Quest.load(c.getCompound("quest")), c.getBoolean("shared"));
        }
    }

    // ------------------------------------------------------------------ items

    public static void give(Player player, ItemStack stack) {
        if (stack.isEmpty()) return;
        // Never auto-equip quest rewards into armor or active hand slots! Place directly into inventory storage (9-35)
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 9; i < 36; i++) {
            ItemStack inSlot = inv.getItem(i);
            if (ItemStack.isSameItemSameTags(inSlot, stack)) {
                int space = inSlot.getMaxStackSize() - inSlot.getCount();
                if (space > 0) {
                    int move = Math.min(space, stack.getCount());
                    inSlot.grow(move);
                    stack.shrink(move);
                    if (stack.isEmpty()) return;
                }
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, stack.copy());
                return;
            }
        }
        if (!inv.add(stack) && !stack.isEmpty()) player.drop(stack, false);
    }

    public static int count(Player player, Item item) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    public static void remove(Player player, Item item, int amount) {
        for (ItemStack s : player.getInventory().items) {
            if (amount <= 0) break;
            if (s.is(item)) {
                int take = Math.min(amount, s.getCount());
                s.shrink(take);
                amount -= take;
            }
        }
    }

    // ------------------------------------------------------------------ killers

    /** The player responsible for a kill: the attacker, the owner of a pet/summon, or the victim's kill credit. */
    @Nullable
    public static ServerPlayer resolveKiller(MinecraftServer server, DamageSource source, LivingEntity victim) {
        ServerPlayer p = asPlayer(server, source.getEntity());
        if (p == null) p = asPlayer(server, victim.getKillCredit());
        return p;
    }

    @Nullable
    public static ServerPlayer asPlayer(MinecraftServer server, @Nullable Entity e) {
        if (e == null) return null;
        if (e instanceof ServerPlayer sp) return sp;
        if (e instanceof OwnableEntity own && own.getOwnerUUID() != null) return server.getPlayerList().getPlayer(own.getOwnerUUID());
        CompoundTag pd = e.getPersistentData();
        for (String k : SUMMON_OWNER_KEYS) {
            if (pd.hasUUID(k)) return server.getPlayerList().getPlayer(pd.getUUID(k));
        }
        return null;
    }

    // ------------------------------------------------------------------ party hooks

    public static void onPartyChanged(MinecraftServer server, UUID player) {
        DIRTY_PLAYERS.add(player);
    }

    /** The last member leaves: they keep the party's quests. */
    public static void onPartyDisband(MinecraftServer server, Party party, UUID lastMember) {
        QuestStore store = QuestStore.get(server);
        String from = partyKey(party.id);
        List<Quest> moved = new ArrayList<>(store.quests(from));
        store.remove(from);
        String to = personalKey(lastMember);
        List<Quest> target = store.quests(to);
        for (Quest q : moved) {
            if (store.find(to, q.id) != null) q.id = newId(store, to);
            target.add(q);
            // re-tag the quest's living enemies would be expensive; kills of them are matched by quest id + any key
            q.extra.putString("formerOwner", from);
        }
        changed(server, to);
    }

    // ------------------------------------------------------------------ sync

    public static void changed(MinecraftServer server, String key) {
        QuestStore.get(server).setDirty();
        DIRTY_KEYS.add(key);
    }

    public static void reset() {
        DIRTY_KEYS.clear();
        DIRTY_PLAYERS.clear();
    }

    public static void markPlayerDirty(UUID player) {
        DIRTY_PLAYERS.add(player);
    }

    /** Sends updated journals and markers to every affected online player. Called at the end of each server tick. */
    public static void flush(MinecraftServer server) {
        if (DIRTY_KEYS.isEmpty() && DIRTY_PLAYERS.isEmpty()) return;
        Set<UUID> players = new LinkedHashSet<>(DIRTY_PLAYERS);
        for (String key : DIRTY_KEYS) players.addAll(members(server, key));
        DIRTY_KEYS.clear();
        DIRTY_PLAYERS.clear();
        for (UUID id : players) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                sync(p);
                updateMarkers(p);
            }
        }
    }

    public static void sync(ServerPlayer player) {
        MinecraftServer server = player.server;
        QuestStore store = QuestStore.get(server);
        ListTag list = new ListTag();
        for (String key : keysOf(server, player.getUUID())) {
            boolean shared = isPartyKey(key);
            for (Quest q : store.quests(key)) {
                CompoundTag t = q.save();
                t.putBoolean("shared", shared);
                // the client doesn't need spawn specs
                ListTag objs = t.getList("objectives", Tag.TAG_COMPOUND);
                for (int i = 0; i < objs.size(); i++) {
                    objs.getCompound(i).remove("spawns");
                    objs.getCompound(i).remove("spawnedIds");
                }
                list.add(t);
            }
        }
        CompoundTag root = new CompoundTag();
        root.put("quests", list);
        SkyNetwork.sendToPlayer(player, new QuestPackets.SyncQuests(root));
    }

    /** Marker position of a quest's current objective, or null. */
    @Nullable
    public static BlockPos markerPos(Quest q, Objective o) {
        if (o.hasPos) return o.pos;
        return null;
    }

    /** Keeps {@code module("quest").markers} (read by the world map) in sync with the current objectives. */
    public static void updateMarkers(ServerPlayer player) {
        ListTag markers = new ListTag();
        for (Ref ref : active(player.server, player.getUUID())) {
            Quest q = ref.quest();
            Objective o = q.current();
            if (o == null) continue;
            BlockPos pos = markerPos(q, o);
            if (pos == null) continue;
            CompoundTag m = new CompoundTag();
            m.putInt("x", pos.getX());
            m.putInt("y", pos.getY());
            m.putInt("z", pos.getZ());
            m.putString("dim", o.dim);
            String label = o.label.isEmpty() ? q.title.getString() : o.label;
            m.putString("label", label);
            m.putString("label_json", Quest.writeComponent(q.title));
            m.putString("quest", q.id);
            markers.add(m);
        }
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module("quest");
        if (!markers.equals(mod.getList("markers", Tag.TAG_COMPOUND))) {
            mod.put("markers", markers);
            data.markDirty();
        }
    }
}
