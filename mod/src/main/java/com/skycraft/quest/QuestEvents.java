package com.skycraft.quest;

import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.quest.party.Parties;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side quest progress: kills, travel, conditions, lazy spawning, logins and the per-tick sync flush. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class QuestEvents {
    private QuestEvents() {}

    // ------------------------------------------------------------------ kills

    /** LOWEST and skipping cancelled deaths: a totem/revive mod that cancels the death must not complete objectives. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        QuestStore store = QuestStore.get(server);
        ServerPlayer killer = Quests.resolveKiller(server, event.getSource(), victim);

        if (victim instanceof AbstractVillager || Dialogue.canTalk(victim) || victim.getType().is(QuestDialogue.GUARDS)) {
            failGiverQuests(server, store, victim);
        }

        CompoundTag pd = victim.getPersistentData();
        if (pd.contains(QuestSpawner.TAG_QUEST)) {
            onTaggedDeath(server, store, victim, killer, pd.getString(QuestSpawner.TAG_OWNER), pd.getString(QuestSpawner.TAG_QUEST));
            return;
        }

        Set<String> keys = new LinkedHashSet<>();
        if (killer != null) keys.addAll(Quests.keysOf(server, killer.getUUID()));
        if (victim instanceof EnderDragon) {
            // Alduin: everyone fighting in the End gets the credit, whatever landed the final blow
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(victim) < 300 * 300) keys.addAll(Quests.keysOf(server, p.getUUID()));
            }
        }
        for (String key : keys) {
            for (Quest q : new ArrayList<>(store.quests(key))) {
                if (!q.isActive()) continue;
                Objective o = q.current();
                if (o == null) continue;
                if (o.type == Objective.Type.KILL_TYPE_COUNT && matchesType(victim, o.target) && inArea(victim, o)) {
                    Quests.addProgress(server, key, q, o, 1, killer);
                } else if (o.type == Objective.Type.CLEAR_AREA && victim instanceof Enemy && o.hasPos && inArea(victim, o)) {
                    Quests.addProgress(server, key, q, o, 1, killer);
                }
            }
        }
    }

    private static void onTaggedDeath(MinecraftServer server, QuestStore store, LivingEntity victim, ServerPlayer killer, String owner, String questId) {
        String key = owner;
        Quest q = store.find(owner, questId);
        if (q == null) {
            // the owning party was disbanded and its quests moved to the last member
            for (Map.Entry<String, List<Quest>> e : store.all().entrySet()) {
                for (Quest candidate : e.getValue()) {
                    if (candidate.id.equals(questId) && owner.equals(candidate.extra.getString("formerOwner"))) {
                        q = candidate;
                        key = e.getKey();
                    }
                }
            }
        }
        if (q == null || !q.isActive()) return;
        UUID id = victim.getUUID();
        Objective o = null;
        for (Objective c : q.objectives) {
            if (!c.done && c.spawnedIds.contains(id)) {
                o = c;
                break;
            }
        }
        if (o == null) return;
        boolean isTarget = o.type == Objective.Type.KILL_TARGET && id.toString().equals(o.target);
        // a quest enemy counts for its owner/party whoever (or whatever) killed it, so objectives can't soft-lock;
        // only the per-kill rewards (killer argument) need a party member to land the blow
        boolean memberKill = killer != null && Quests.isMember(server, key, killer);
        ServerPlayer credit = memberKill ? killer : null;
        o.spawnedIds.remove(id);
        switch (o.type) {
            case KILL_TARGET -> {
                if (isTarget) {
                    o.sneakKill = memberKill && killer.isCrouching() && Faction.DARK_BROTHERHOOD.id.equals(q.faction);
                    Quests.completeObjective(server, key, q, o, credit);
                } else {
                    Quests.changed(server, key);
                }
            }
            case KILL_TYPE_COUNT, CLEAR_AREA -> Quests.addProgress(server, key, q, o, 1, credit);
            default -> Quests.changed(server, key);
        }
    }

    /** Quests that still need their giver fail when the giver dies. */
    private static void failGiverQuests(MinecraftServer server, QuestStore store, LivingEntity victim) {
        UUID id = victim.getUUID();
        for (Map.Entry<String, List<Quest>> e : new ArrayList<>(store.all().entrySet())) {
            for (Quest q : new ArrayList<>(e.getValue())) {
                if (!q.isActive() || !id.equals(q.giver)) continue;
                boolean needsGiver = false;
                for (Objective o : q.objectives) if (!o.done && id.equals(o.npc)) needsGiver = true;
                if (needsGiver) Quests.fail(server, e.getKey(), q, Component.translatable("quest.skycraft.giver_died", q.giverName));
            }
        }
    }

    static boolean matchesType(LivingEntity victim, String target) {
        if (target.isEmpty()) return false;
        String id = EntityType.getKey(victim.getType()).toString();
        for (String token : target.split(",")) {
            String t = token.trim();
            if (t.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(t.substring(1));
                if (tag != null && victim.getType().is(TagKey.create(Registries.ENTITY_TYPE, tag))) return true;
            } else if (t.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inArea(LivingEntity victim, Objective o) {
        if (!o.hasPos) return true;
        if (!victim.level().dimension().location().toString().equals(o.dim)) return false;
        return o.radius <= 0 || Locate.horizontalDist(victim.blockPosition(), o.pos) <= o.radius;
    }

    /** Companions' Beast Blood: +5% damage. */
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer p && SkyData.get(p).module("quest").getBoolean("beast_blood")) {
            event.setAmount(event.getAmount() * 1.05f);
        }
    }

    // ------------------------------------------------------------------ per-player checks

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        int t = player.tickCount + player.getId();
        if (t % 20 != 0) return;
        MinecraftServer server = player.server;
        if (!(player.level() instanceof ServerLevel level)) return;
        String dim = level.dimension().location().toString();

        for (Quests.Ref ref : Quests.active(server, player.getUUID())) {
            Quest q = ref.quest();
            String key = ref.key();
            if (q.extra.contains("deadline") && level.getGameTime() > q.extra.getLong("deadline")) {
                Quests.fail(server, key, q, Component.translatable("quest.skycraft.timed_out"));
                continue;
            }
            Objective o = q.current();
            if (o == null) continue;
            if (o.type == Objective.Type.GO_TO) {
                if (reached(player, level, dim, o)) Quests.completeObjective(server, key, q, o, player);
                continue;
            }
            if (o.type == Objective.Type.CONDITION) {
                checkCondition(server, key, q, o);
                continue;
            }
            if (!o.spawns.isEmpty() && !o.spawned && o.hasPos && o.dim.equals(dim)
                    && Locate.horizontalDist(player.blockPosition(), o.pos) <= QuestConfig.SPAWN_DISTANCE.get()
                    && level.isLoaded(o.pos)) {
                QuestSpawner.spawnFor(level, key, q, o);
                Quests.changed(server, key);
                continue;
            }
            if (o.type == Objective.Type.KILL_TARGET && o.spawned && o.dim.equals(dim)) trackTarget(server, level, player, key, q, o);
            else if ((o.type == Objective.Type.CLEAR_AREA || o.type == Objective.Type.KILL_TYPE_COUNT) && o.spawned
                    && o.dim.equals(dim)) trackGroup(server, level, player, key, q, o);
            if ((o.type == Objective.Type.TALK_TO || o.type == Objective.Type.FETCH) && o.npc != null) followNpc(server, level, dim, key, q, o);
        }

        if ((t / 20) % 5 == 0) {
            MainQuest.tick(player);
            Factions.tickWhisper(player);
        }
    }

    private static boolean reached(ServerPlayer player, ServerLevel level, String dim, Objective o) {
        if (!o.dim.equals(dim)) return false;
        if (!o.hasPos) {
            if ("near:villager".equals(o.target)) {
                return !level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(24)).isEmpty();
            }
            return true;
        }
        return Locate.horizontalDist(player.blockPosition(), o.pos) <= Math.max(4, o.radius);
    }

    private static void checkCondition(MinecraftServer server, String key, Quest q, Objective o) {
        List<ServerPlayer> online = Quests.onlineMembers(server, key);
        if ("word".equals(o.target)) {
            for (ServerPlayer m : online) {
                CompoundTag words = SkyData.get(m).module("magic").getCompound("words");
                for (String shout : words.getAllKeys()) {
                    if (words.getInt(shout) >= 1) {
                        Quests.completeObjective(server, key, q, o, m);
                        return;
                    }
                }
            }
            return;
        }
        if (o.target.startsWith("crime:")) {
            String stat = o.target.substring("crime:".length());
            CompoundTag base = o.data.getCompound("baseline");
            int gained = 0;
            boolean dirty = false;
            for (ServerPlayer m : online) {
                int now = SkyData.get(m).module("crime").getInt(stat);
                String id = m.getStringUUID();
                if (!base.contains(id)) {
                    base.putInt(id, now);
                    dirty = true;
                    continue;
                }
                int last = base.getInt(id);
                if (now != last) {
                    if (now > last) gained += now - last;
                    base.putInt(id, now);
                    dirty = true;
                }
            }
            o.data.put("baseline", base);
            if (gained > 0) {
                Quests.addProgress(server, key, q, o, gained, null);
            } else if (dirty) {
                QuestStore.get(server).setDirty();
            }
        }
    }

    /** Whether "not found" near {@code pos} means something: the chunk and its entities are loaded (they load later). */
    private static boolean entitiesLoaded(ServerLevel level, net.minecraft.core.BlockPos pos) {
        return level.isLoaded(pos) && level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(pos));
    }

    private static void trackTarget(MinecraftServer server, ServerLevel level, ServerPlayer player, String key, Quest q, Objective o) {
        UUID id;
        try {
            id = UUID.fromString(o.target);
        } catch (IllegalArgumentException e) {
            return;
        }
        Entity e = level.getEntity(id);
        if (e != null && e.isAlive()) {
            o.missing = 0;
            if (e.blockPosition().distSqr(o.pos) > 64) {
                o.pos = e.blockPosition();
                Quests.changed(server, key);
            }
            if (QuestSpawner.isBossObjective(o)) QuestSpawner.BossBars.track(e);
        } else if (Locate.horizontalDist(player.blockPosition(), o.pos) < 24 && entitiesLoaded(level, o.pos)) {
            if (++o.missing >= 30) {
                // the target vanished (despawned, removed...): only the target comes back, its group stays as it is
                o.missing = 0;
                o.spawnedIds.remove(id);
                if (QuestSpawner.respawnTarget(level, key, q, o)) {
                    Quests.changed(server, key);
                } else {
                    // can't recreate it: don't leave the quest stuck
                    Quests.completeObjective(server, key, q, o, null);
                }
            }
        }
    }

    /**
     * Spawned enemies of a CLEAR_AREA/KILL_TYPE_COUNT objective that vanished without dying (removed by another mod,
     * despawned) are counted as dealt with after 30 s, so the objective can't soft-lock. Entities that are merely in
     * unloaded chunks are waited for.
     */
    private static void trackGroup(MinecraftServer server, ServerLevel level, ServerPlayer player, String key, Quest q, Objective o) {
        if (o.spawnedIds.isEmpty() || !o.hasPos || Locate.horizontalDist(player.blockPosition(), o.pos) >= 32
                || !entitiesLoaded(level, o.pos)) return;
        List<UUID> gone = new ArrayList<>();
        for (UUID id : o.spawnedIds) {
            Entity e = level.getEntity(id);
            if (e == null || !e.isAlive()) gone.add(id);
        }
        if (gone.isEmpty()) {
            o.missing = 0;
            return;
        }
        if (++o.missing < 30) return;
        o.missing = 0;
        o.spawnedIds.removeAll(gone);
        Quests.addProgress(server, key, q, o, gone.size(), null);
    }

    /** Quest bosses get their boss bar back when they are (re)loaded. */
    @SubscribeEvent
    public static void onEntityJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity().getTags().contains(QuestSpawner.BOSS_TAG)) {
            QuestSpawner.BossBars.track(event.getEntity());
        }
    }

    private static void followNpc(MinecraftServer server, ServerLevel level, String dim, String key, Quest q, Objective o) {
        Entity e = level.getEntity(o.npc);
        if (e == null || !e.isAlive()) return;
        if (!o.dim.equals(dim) || e.blockPosition().distSqr(o.pos) > 256) {
            o.pos = e.blockPosition();
            o.dim = dim;
            o.hasPos = true;
            if (o.npc.equals(q.giver)) {
                q.giverPos = o.pos;
                q.giverDim = dim;
            }
            Quests.changed(server, key);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        Quests.flush(server);
        int t = server.getTickCount();
        if (t % 20 == 0) Parties.tick(server);
        if (t % 10 == 5) QuestSpawner.BossBars.tick(server);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Parties.onLogin(player);
        Quests.sync(player);
        Quests.updateMarkers(player);
        Quests.deliverPending(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Parties.onLogout(player);
        QuestDialogue.forget(player.getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) Quests.markPlayerDirty(player.getUUID());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        QuestSpawner.BossBars.clear();
        Parties.reset();
        Quests.reset();
        QuestDialogue.reset();
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        QuestCommands.register(event.getDispatcher());
    }
}
