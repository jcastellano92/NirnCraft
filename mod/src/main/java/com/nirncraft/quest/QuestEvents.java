package com.nirncraft.quest;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.SkyData;
import com.nirncraft.dialogue.Dialogue;
import com.nirncraft.quest.party.Parties;
import com.nirncraft.quest.story.CivilWar;
import com.nirncraft.quest.story.QuestNpcs;
import com.nirncraft.quest.story.Story;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side quest progress: kills, travel, conditions, lazy spawning, logins and the per-tick sync flush. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
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

        if (victim instanceof AbstractVillager || Dialogue.canTalk(victim) || victim.getType().builtInRegistryHolder().is(QuestDialogue.GUARDS)) {
            failGiverQuests(server, store, victim);
        }

        CompoundTag pd = victim.getPersistentData();
        if (pd.contains(QuestSpawner.TAG_QUEST)) {
            onTaggedDeath(server, store, victim, killer, pd.getStringOr(QuestSpawner.TAG_OWNER, ""), pd.getStringOr(QuestSpawner.TAG_QUEST, ""));
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
                    if (candidate.id.equals(questId) && owner.equals(candidate.extra.getStringOr("formerOwner",""))) {
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
                if (needsGiver) Quests.fail(server, e.getKey(), q, Component.translatable("quest.nirncraft.giver_died", q.giverName));
            }
        }
    }

    static boolean matchesType(LivingEntity victim, String target) {
        if (target.isEmpty()) return false;
        String id = EntityType.getKey(victim.getType()).toString();
        for (String token : target.split(",")) {
            String t = token.trim();
            if (t.startsWith("#")) {
                Identifier tag = Identifier.tryParse(t.substring(1));
                if (tag != null && victim.getType().builtInRegistryHolder().is(TagKey.create(Registries.ENTITY_TYPE, tag))) return true;
            } else if (t.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inArea(LivingEntity victim, Objective o) {
        if (!o.hasPos) return true;
        if (!victim.level().dimension().identifier().toString().equals(o.dim)) return false;
        return o.radius <= 0 || Locate.horizontalDist(victim.blockPosition(), o.pos) <= o.radius;
    }

    /** Companions' Beast Blood: +5% damage. */
    @SubscribeEvent
    public static void onHurt(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer p && SkyData.get(p).module("quest").getBooleanOr("beast_blood",false)) {
            event.setAmount(event.getAmount() * 1.05f);
        }
    }

    // ------------------------------------------------------------------ per-player checks

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        int t = player.tickCount + player.getId();
        if (t % 20 != 0) return;
        MinecraftServer server = player.level().getServer();
        if (!(player.level() instanceof ServerLevel level)) return;
        String dim = level.dimension().identifier().toString();

        for (Quests.Ref ref : Quests.active(server, player.getUUID())) {
            Quest q = ref.quest();
            String key = ref.key();
            if (q.extra.contains("deadline") && level.getGameTime() > q.extra.getLongOr("deadline",0L)) {
                Quests.fail(server, key, q, Component.translatable("quest.nirncraft.timed_out"));
                continue;
            }
            Objective o = q.current();
            if (o == null) continue;
            if (!o.hasPos && o.data.getBooleanOr(Story.AT_PLAYER, false) && o.dim.equals(dim)) {
                // "somewhere in Oblivion": the step happens where the party first stands in that realm
                double angle = level.getRandom().nextDouble() * Math.PI * 2;
                int d = 14 + level.getRandom().nextInt(8);
                o.at(player.blockPosition().offset((int) (Math.cos(angle) * d), 0, (int) (Math.sin(angle) * d)), dim, Math.max(o.radius, 16));
                o.placement("near");
                Quests.changed(server, key);
            }
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
                    && level.isLoaded(o.pos) && !Story.partnerSpawned(server, key, q, o)) {
                boolean any = QuestSpawner.spawnFor(level, key, q, o);
                Quests.changed(server, key);
                if (!any && (o.type == Objective.Type.KILL_TARGET || o.type == Objective.Type.CLEAR_AREA)) {
                    // nothing could be spawned (unknown entities, every branch excluded): nothing left to fight
                    Quests.completeObjective(server, key, q, o, player);
                }
                continue;
            }
            if (o.type == Objective.Type.KILL_TARGET && o.spawned && o.dim.equals(dim)) trackTarget(server, level, player, key, q, o);
            else if ((o.type == Objective.Type.CLEAR_AREA || o.type == Objective.Type.KILL_TYPE_COUNT
                    || o.type == Objective.Type.RETRIEVE) && o.spawned && o.dim.equals(dim)) trackGroup(server, level, player, key, q, o);
            if (o.type == Objective.Type.RETRIEVE) retrieve(server, level, player, dim, key, q, o);
            if (o.type == Objective.Type.ESCORT) escort(server, level, player, dim, key, q, o);
            if ((o.type == Objective.Type.TALK_TO || o.type == Objective.Type.FETCH) && o.npc != null) followNpc(server, level, dim, key, q, o);
            else if ((o.type == Objective.Type.TALK_TO || o.type == Objective.Type.FETCH) && o.data.contains(Story.NPC)) followStoryNpc(server, key, o);
        }

        QuestNpcs.tickPlayer(player);
        if ((t / 20) % 5 == 0) {
            MainQuest.tick(player);
            Story.tickPlayer(player);
            Factions.tickWhisper(player);
            CivilWar.dressGuards(player);
            com.nirncraft.quest.story.ProvinceRadiant.tickPlayer(player);
            com.nirncraft.roads.Signposts.refreshNearbyRoadSigns(player);
        }
    }

    /** RETRIEVE: once the guardians are dealt with, reaching the spot takes the relic. */
    private static void retrieve(MinecraftServer server, ServerLevel level, ServerPlayer player, String dim, String key, Quest q, Objective o) {
        if (!o.hasPos || !o.dim.equals(dim) || Locate.horizontalDist(player.blockPosition(), o.pos) > Math.max(4, o.radius)) return;
        if (!o.spawns.isEmpty() && (!o.spawned || !o.spawnedIds.isEmpty())) return;
        String relic = o.data.getStringOr(Story.RELIC, "");
        String give = !relic.isEmpty() ? "relic:" + relic : !o.target.isEmpty() ? "give:" + o.target : "";
        if (!give.isEmpty()) Story.runActions(server, key, q, give, player);
        Quests.completeObjective(server, key, q, o, player);
    }

    /**
     * ESCORT: the NPC follows the nearest party member (teleporting along if it falls far behind) until it stands
     * at the destination, which becomes its new home. A lost NPC is brought back next to the party after 30 s.
     */
    private static void escort(MinecraftServer server, ServerLevel level, ServerPlayer player, String dim, String key, Quest q, Objective o) {
        String id = o.data.getStringOr(Story.NPC, "");
        if (id.isEmpty()) return;
        Entity e = QuestNpcs.find(server, id);
        if (e == null || e.level() != level) {
            if (++o.missing >= 30) {
                o.missing = 0;
                QuestNpcs.summonNear(player, id);
            }
            return;
        }
        o.missing = 0;
        if (e instanceof com.nirncraft.society.entity.NpcEntity npc) {
            if (npc.getLeader() == null || !npc.getLeader().equals(player.getUUID())) {
                ServerPlayer leader = npc.getLeader() == null ? null : server.getPlayerList().getPlayer(npc.getLeader());
                if (leader == null || leader.level() != level || leader.distanceToSqr(npc) > player.distanceToSqr(npc)) npc.setLeader(player.getUUID());
            }
            npc.clearHome();
        }
        if (e.distanceToSqr(player) > 40 * 40) {
            net.minecraft.core.BlockPos s = QuestSpawner.surface(level, player.getBlockX() + 2, player.getBlockZ() + 2);
            e.teleportTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
        }
        if (o.hasPos && o.dim.equals(dim) && Locate.horizontalDist(e.blockPosition(), o.pos) <= Math.max(6, o.radius)) {
            if (e instanceof com.nirncraft.society.entity.NpcEntity npc) npc.setLeader(null);
            QuestNpcs.relocate(server, id, o.pos, dim);
            Quests.completeObjective(server, key, q, o, player);
        }
    }

    /** A story NPC wandered or was moved: the marker follows it. */
    private static void followStoryNpc(MinecraftServer server, String key, Objective o) {
        String first = o.data.getStringOr(Story.NPC, "").split("\\|")[0];
        Entity e = QuestNpcs.find(server, first);
        if (e == null) return;
        String edim = e.level().dimension().identifier().toString();
        if (!o.dim.equals(edim) || !o.hasPos || e.blockPosition().distSqr(o.pos) > 256 || !o.yKnown) {
            o.at(e.blockPosition(), edim, o.radius);
            o.yKnown = true;
            Quests.changed(server, key);
        }
    }

    private static boolean reached(ServerPlayer player, ServerLevel level, String dim, Objective o) {
        if (!o.dim.equals(dim)) return false;
        if (!o.hasPos) {
            if ("near:villager".equals(o.target)) {
                return !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, player.getBoundingBox().inflate(24),
                        QuestDialogue::isTownsperson).isEmpty();
            }
            return true;
        }
        return Locate.horizontalDist(player.blockPosition(), o.pos) <= Math.max(4, o.radius);
    }

    private static void checkCondition(MinecraftServer server, String key, Quest q, Objective o) {
        List<ServerPlayer> online = Quests.onlineMembers(server, key);
        if (o.target.startsWith("shout:")) return; // completed by the shout hook (Story#onShout)
        if (o.target.startsWith("story:")) {
            String cond = o.target.substring("story:".length());
            for (ServerPlayer m : online) {
                if (Story.test(m, cond)) {
                    Quests.completeObjective(server, key, q, o, m);
                    return;
                }
            }
            return;
        }
        if (o.target.startsWith("wait:")) {
            long ticks;
            try {
                ticks = Long.parseLong(o.target.substring("wait:".length()));
            } catch (NumberFormatException e) {
                ticks = 1000;
            }
            if (server.overworld().getGameTime() - o.data.getLongOr(Story.BEGIN, 0L) >= ticks) Quests.completeObjective(server, key, q, o, null);
            return;
        }
        if ("word".equals(o.target)) {
            for (ServerPlayer m : online) {
                CompoundTag words = SkyData.get(m).module("magic").getCompoundOrEmpty("words");
                for (String shout : words.keySet()) {
                    if (words.getIntOr(shout, 0) >= 1) {
                        Quests.completeObjective(server, key, q, o, m);
                        return;
                    }
                }
            }
            return;
        }
        if (o.target.startsWith("crime:")) {
            String stat = o.target.substring("crime:".length());
            CompoundTag base = o.data.getCompoundOrEmpty("baseline");
            int gained = 0;
            boolean dirty = false;
            for (ServerPlayer m : online) {
                int now = SkyData.get(m).module("crime").getIntOr(stat, 0);
                String id = m.getStringUUID();
                if (!base.contains(id)) {
                    base.putInt(id, now);
                    dirty = true;
                    continue;
                }
                int last = base.getIntOr(id, 0);
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
        return level.isLoaded(pos) && level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(pos));
    }

    private static void trackTarget(MinecraftServer server, ServerLevel level, ServerPlayer player, String key, Quest q, Objective o) {
        UUID id;
        try {
            id = UUID.fromString(o.target);
        } catch (IllegalArgumentException e) {
            return;
        }
        Entity e = level.getEntity(id);
        if (e != null && e.isAlive() && o.data.contains(Story.FLEE) && e instanceof LivingEntity le
                && le.getHealth() <= le.getMaxHealth() * o.data.getFloatOr(Story.FLEE, 0f)) {
            // beaten, not killed: Alduin flees the Throat of the World, Odahviing is caught in the yoke
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, e.getX(), e.getY() + 1, e.getZ(), 60, 2, 2, 2, 0.05);
            level.playSound(null, e.blockPosition(), net.minecraft.sounds.SoundEvents.ENDER_DRAGON_GROWL,
                    net.minecraft.sounds.SoundSource.HOSTILE, 5f, 0.6f);
            o.spawnedIds.remove(id);
            if (!o.data.getBooleanOr("stay",false)) e.discard();
            Quests.completeObjective(server, key, q, o, player);
            return;
        }
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
        if (o.type == Objective.Type.RETRIEVE) Quests.changed(server, key); // guardians gone: the relic is free
        else Quests.addProgress(server, key, q, o, gone.size(), null);
    }

    /** Quest bosses get their boss bar back when they are (re)loaded. */
    @SubscribeEvent
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity().entityTags().contains(QuestSpawner.BOSS_TAG)) {
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
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.overworld() == null) return;
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
    public static void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isShiftKeyDown()) return;
        net.minecraft.core.BlockPos pos = event.getPos();
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) return;
        if (NoticeBoards.isNoticeBoardSign(level, pos, sign)) {
            NoticeBoards.nearestBoardSettlement(level, pos, 2500).ifPresent(settlement -> NoticeBoards.showBoard(player, settlement));
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            event.setCanceled(true);
        } else if (com.nirncraft.roads.Signposts.isDirectionalSign(level, pos, sign)) {
            com.nirncraft.roads.Signposts.onUseSign(player, pos, sign);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        QuestCommands.register(event.getDispatcher());
    }
}
