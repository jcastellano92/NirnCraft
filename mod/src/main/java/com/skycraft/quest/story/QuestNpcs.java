package com.skycraft.quest.story;

import com.skycraft.Skycraft;
import com.skycraft.quest.Locate;
import com.skycraft.quest.QuestSpawner;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Named, unique quest NPCs (Hadvar, Jarl Balgruuf, Arngeir, Delphine...). Each one is spawned once per world at its
 * anchor (a {@link QuestPlaces place} plus an offset, or wherever a quest moved it) when a player comes near and
 * the area is loaded; an NPC already standing there with the same name (spawned by a settlement) is adopted
 * instead. Their UUIDs live in world data, so a missing NPC is noticed and respawned, and a stale copy that loads
 * later is removed: there is never more than one. Essential NPCs can't be killed (see {@link StoryEvents}).
 */
public final class QuestNpcs {
    /** Entity persistent-data key holding the quest NPC id. */
    public static final String TAG = "skycraft_quest_npc";
    /** Players closer than this to an NPC's anchor make it exist. */
    private static final int RESIDENT_RANGE = 72;
    /** An NPC whose last known spot is unloaded is respawned at its anchor after this long. */
    private static final long MISSING_TICKS = 20L * 120;

    private static final Map<String, Long> MISSING_SINCE = new HashMap<>();

    private QuestNpcs() {}

    // ------------------------------------------------------------------ saved data

    static final class Rec {
        @Nullable UUID uuid;
        String dim = "minecraft:overworld";
        @Nullable BlockPos last;
        @Nullable BlockPos home;
        String homeDim = "";
        /** Gone for good (a quest killed or exiled them): never respawned. */
        boolean retired;
    }

    public static final class Store extends SavedData {
        private static final String NAME = "skycraft_quest_npcs";
        final Map<String, Rec> recs = new HashMap<>();

        static Store get(MinecraftServer server) {
            return com.skycraft.platform.SavedDataTypes.get(server.overworld(), NAME, Store::load, Store::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
        }

        Rec rec(String id) {
            return recs.computeIfAbsent(id, k -> new Rec());
        }

        static Store load(CompoundTag tag) {
            Store s = new Store();
            for (String id : tag.keySet()) {
                CompoundTag t = tag.getCompoundOrEmpty(id);
                Rec r = new Rec();
                if (t.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent()) r.uuid = t.read("uuid", net.minecraft.core.UUIDUtil.CODEC).orElseThrow();
                r.dim = t.getStringOr("dim","");
                if (t.contains("last")) r.last = com.skycraft.platform.NbtPositions.read(t.getCompoundOrEmpty("last"));
                if (t.contains("home")) r.home = com.skycraft.platform.NbtPositions.read(t.getCompoundOrEmpty("home"));
                r.homeDim = t.getStringOr("homeDim","");
                r.retired = t.getBooleanOr("retired",false);
                s.recs.put(id, r);
            }
            return s;
        }

        public CompoundTag save(CompoundTag tag) {
            recs.forEach((id, r) -> {
                CompoundTag t = new CompoundTag();
                if (r.uuid != null) t.store("uuid", net.minecraft.core.UUIDUtil.CODEC,  r.uuid);
                t.putString("dim", r.dim);
                if (r.last != null) t.put("last", com.skycraft.platform.NbtPositions.write(r.last));
                if (r.home != null) t.put("home", com.skycraft.platform.NbtPositions.write(r.home));
                t.putString("homeDim", r.homeDim);
                if (r.retired) t.putBoolean("retired", true);
                tag.put(id, t);
            });
            return tag;
        }
    }

    // ------------------------------------------------------------------ identity

    /** The quest NPC id of an entity, or empty. */
    public static String idOf(Entity e) {
        return e.getPersistentData().getStringOr(TAG, "");
    }

    /** Whether the entity is one of the NPCs in {@code spec} ({@code "hadvar|ralof"}). */
    public static boolean matches(Entity e, String spec) {
        String id = idOf(e);
        if (id.isEmpty() || spec.isEmpty()) return false;
        for (String s : spec.split("\\|")) if (s.equals(id)) return true;
        return false;
    }

    // ------------------------------------------------------------------ anchors

    /** Where the NPC lives: a quest may have moved it, otherwise its place plus offset. */
    @Nullable
    public static BlockPos anchor(MinecraftServer server, String id) {
        Rec r = Store.get(server).recs.get(id);
        if (r != null && r.home != null) return r.home;
        StoryData.NpcDef def = StoryData.npc(id);
        if (def == null) return null;
        // the head of a guild hall stands at the hall's desk once the hall exists
        if (!def.hall.isEmpty()) {
            BlockPos hall = com.skycraft.quest.faction.FactionStaff.hallSpot(server, def.hall, def.place);
            if (hall != null) return hall;
        }
        Optional<QuestPlaces.Spot> spot = QuestPlaces.find(def.place);
        if (spot.isEmpty()) return null;
        int x = spot.get().x() + def.dx;
        int z = spot.get().z() + def.dz;
        ServerLevel level = level(server, anchorDim(server, id));
        if (level != null && level.hasChunk(x >> 4, z >> 4)) {
            return com.skycraft.society.NpcPlacement.safeSurfaceAt(level, x, z);
        }
        int y = level == null ? 64 : Locate.surfaceY(level, x, z);
        return new BlockPos(x, y, z);
    }

    public static String anchorDim(MinecraftServer server, String id) {
        Rec r = Store.get(server).recs.get(id);
        if (r != null && r.home != null && !r.homeDim.isEmpty()) return r.homeDim;
        StoryData.NpcDef def = StoryData.npc(id);
        if (def != null) {
            Optional<QuestPlaces.Spot> spot = QuestPlaces.find(def.place);
            if (spot.isPresent() && !"minecraft:overworld".equals(spot.get().dim())) return spot.get().dim();
            return def.dim;
        }
        return "minecraft:overworld";
    }

    /** A quest moves the NPC for good (Delphine to Sky Haven Temple...). */
    public static void relocate(MinecraftServer server, String id, BlockPos home, String dim) {
        Store s = Store.get(server);
        Rec r = s.rec(id);
        r.home = home.immutable();
        r.homeDim = dim;
        s.setDirty();
        Entity e = find(server, id);
        if (e == null) return;
        if (e.level().dimension().identifier().toString().equals(dim) && e.blockPosition().distSqr(home) < 64 * 64) {
            if (e instanceof NpcEntity npc) npc.setHome(home, 10);
        } else {
            // they set off for the new home: they'll be there (respawned at it) when someone arrives
            e.discard();
            r.uuid = null;
            r.last = null;
        }
    }

    @Nullable
    static ServerLevel level(MinecraftServer server, String dim) {
        Identifier rl = Identifier.tryParse(dim);
        return rl == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    // ------------------------------------------------------------------ lookup

    /** The loaded NPC entity, or null when it's unloaded or gone. */
    @Nullable
    public static Entity find(MinecraftServer server, String id) {
        Rec r = Store.get(server).recs.get(id);
        if (r == null || r.uuid == null) return null;
        ServerLevel level = level(server, r.dim);
        Entity e = level == null ? null : level.getEntity(r.uuid);
        return e != null && e.isAlive() ? e : null;
    }

    /** Last known position of the NPC (live, recorded, or its anchor). */
    @Nullable
    public static BlockPos lastPos(MinecraftServer server, String id) {
        Entity e = find(server, id);
        if (e != null) return e.blockPosition();
        Rec r = Store.get(server).recs.get(id);
        if (r != null && r.last != null) return r.last;
        return anchor(server, id);
    }

    // ------------------------------------------------------------------ presence

    /** Once a second per player: the quest NPCs living near them exist. */
    public static void tickPlayer(ServerPlayer player) {
        ServerLevel level = player.level();
        String dim = level.dimension().identifier().toString();
        MinecraftServer server = player.level().getServer();
        Store store = Store.get(server);
        int px = player.getBlockX();
        int pz = player.getBlockZ();
        for (StoryData.NpcDef def : StoryData.npcs()) {
            // cheap x/z test first: the full anchor needs a surface height
            Rec r = store.recs.get(def.id);
            int ax, az;
            BlockPos hall = r != null && r.home != null || def.hall.isEmpty() ? null
                    : com.skycraft.quest.faction.FactionStaff.hallSpot(server, def.hall, def.place);
            if (r != null && r.home != null) {
                ax = r.home.getX();
                az = r.home.getZ();
            } else if (hall != null) {
                // the hall now exists: the NPC moves in for good (spawned earlier at the town anchor? fetched over)
                relocate(server, def.id, hall, "minecraft:overworld");
                ax = hall.getX();
                az = hall.getZ();
            } else {
                Optional<QuestPlaces.Spot> spot = QuestPlaces.find(def.place);
                if (spot.isEmpty()) continue;
                ax = spot.get().x() + def.dx;
                az = spot.get().z() + def.dz;
            }
            long dx = ax - px;
            long dz = az - pz;
            if (dx * dx + dz * dz > (long) RESIDENT_RANGE * RESIDENT_RANGE) continue;
            if (!dim.equals(anchorDim(server, def.id))) continue;
            BlockPos a = anchor(server, def.id);
            if (a != null) ensure(level, def.id, a, false);
        }
    }

    /**
     * Makes sure the NPC exists. When it's missing, it is adopted (same name near {@code at}) or spawned at
     * {@code at}. With {@code urgent} (an escort lost its charge) an NPC whose last spot is unloaded is respawned
     * right away instead of after {@link #MISSING_TICKS}.
     */
    @Nullable
    public static Entity ensure(ServerLevel level, String id, BlockPos at, boolean urgent) {
        StoryData.NpcDef def = StoryData.npc(id);
        if (def == null || !entitiesLoaded(level, at)) return null;
        MinecraftServer server = level.getServer();
        Store store = Store.get(server);
        Rec r = store.rec(id);
        if (r.retired) return null;
        Entity e = find(server, id);
        if (e != null) {
            MISSING_SINCE.remove(id);
            if (r.last == null || r.last.distSqr(e.blockPosition()) > 64) {
                r.last = e.blockPosition();
                r.dim = e.level().dimension().identifier().toString();
                store.setDirty();
            }
            return e;
        }
        if (r.uuid != null && r.last != null && !urgent) {
            ServerLevel lastLevel = level(server, r.dim);
            boolean lastLoaded = lastLevel != null && entitiesLoaded(lastLevel, r.last);
            if (!lastLoaded) {
                // somewhere unloaded: give it a while before replacing it
                long now = server.overworld().getGameTime();
                long since = MISSING_SINCE.computeIfAbsent(id, k -> now);
                if (now - since < MISSING_TICKS) return null;
            }
        }
        MISSING_SINCE.remove(id);
        LivingEntity adopted = adopt(level, def, at);
        Entity spawned = adopted;
        if (spawned == null) {
            LivingEntity fresh = create(level, def, at);
            if (fresh == null) return null;
            // on record before it joins the level, so it isn't mistaken for a stale copy
            UUID previous = r.uuid;
            r.uuid = fresh.getUUID();
            if (!level.addFreshEntity(fresh)) {
                r.uuid = previous;
                Skycraft.LOGGER.warn("Couldn't spawn quest NPC {}", def.id);
                return null;
            }
            spawned = fresh;
        }
        r.uuid = spawned.getUUID();
        r.dim = level.dimension().identifier().toString();
        r.last = spawned.blockPosition();
        store.setDirty();
        return spawned;
    }

    private static boolean entitiesLoaded(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.areEntitiesLoaded(ChunkPos.pack(pos));
    }

    /** An NPC of the same name already standing near the anchor (a settlement's Jarl...) becomes the quest NPC. */
    @Nullable
    private static LivingEntity adopt(ServerLevel level, StoryData.NpcDef def, BlockPos at) {
        String name = def.name.c().getString();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at).inflate(48))) {
            if (!e.isAlive() || e.getPersistentData().contains(QuestSpawner.TAG_QUEST)) continue; // never a quest enemy
            String tag = idOf(e);
            boolean same = def.id.equals(tag) || (tag.isEmpty() && e.hasCustomName() && name.equals(e.getCustomName().getString()));
            if (same) {
                mark(e, def);
                return e;
            }
        }
        return null;
    }

    /** Creates (but doesn't add) the NPC entity at the surface of {@code at}. */
    @Nullable
    private static LivingEntity create(ServerLevel level, StoryData.NpcDef def, BlockPos at) {
        // an indoor spot with room to stand (a cell, a hall's desk) is used as is; outdoors, the safe ground near it
        BlockPos pos = !level.canSeeSky(at) && standable(level, at)
                ? at
                : com.skycraft.society.NpcPlacement.safeSurfaceAt(level, at.getX(), at.getZ());
        LivingEntity entity = null;
        if (!def.entity.isEmpty()) {
            Optional<EntityType<?>> type = com.skycraft.worldgen.RegistryTypes.entity(def.entity);
            if (type.isPresent() && type.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT) instanceof LivingEntity le) {
                le.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
                if (le instanceof Mob mob) mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
                entity = le;
            }
        }
        if (entity == null) {
            NpcRole role = NpcRole.byId(def.role);
            if (role == null) role = NpcRole.ADVENTURER;
            NpcEntity npc = Npcs.create(level, pos, role);
            if (npc == null) return null;
            npc.setSkin(def.female ? 1 : 0);
            npc.setHome(pos, 10);
            entity = npc;
        }
        entity.setCustomName(def.name.c());
        mark(entity, def);
        return entity;
    }

    private static void mark(LivingEntity e, StoryData.NpcDef def) {
        e.getPersistentData().putString(TAG, def.id);
        if (!def.hall.isEmpty()) {
            // a guild hall's head: the faction's services (joining, work, penance) are theirs to give
            e.getPersistentData().putString(com.skycraft.quest.faction.FactionStaff.TAG, def.hall);
            e.getPersistentData().putString(com.skycraft.quest.faction.FactionStaff.JOB, "leader");
        }
        e.setCustomName(def.name.c());
        e.setCustomNameVisible(false);
        if (e instanceof Mob mob) mob.setPersistenceRequired();
    }

    /** Spawns (or fetches) the NPC right next to a player: escorts that lost their charge, scripted arrivals. */
    @Nullable
    public static Entity summonNear(ServerPlayer player, String id) {
        Entity e = find(player.level().getServer(), id);
        ServerLevel level = player.level();
        BlockPos near = player.blockPosition().offset(level.getRandom().nextInt(5) - 2, 0, level.getRandom().nextInt(5) - 2);
        // indoors (a jail cell, a hall): beside the player on their own floor rather than up on the roof
        boolean indoors = !level.canSeeSky(player.blockPosition());
        if (indoors) near = freeBeside(level, player.blockPosition());
        if (e != null) {
            if (e.level() == level) {
                BlockPos s = indoors ? near : QuestSpawner.surface(level, near.getX(), near.getZ());
                e.teleportTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
                return e;
            }
            e.discard();
        }
        Store store = Store.get(player.level().getServer());
        store.rec(id).uuid = null;
        return ensure(level, id, near, true);
    }

    private static boolean standable(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
    }

    /** A free spot next to {@code p} on the same floor (or {@code p} itself). */
    private static BlockPos freeBeside(ServerLevel level, BlockPos p) {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            BlockPos q = p.offset(d[0], 0, d[1]);
            if (standable(level, q)) return q;
        }
        return p;
    }

    /** The NPC leaves the story for good (killed in a quest, exiled): removed and never respawned. */
    public static void retire(MinecraftServer server, String id) {
        Entity e = find(server, id);
        if (e != null) e.discard();
        Store s = Store.get(server);
        s.rec(id).retired = true;
        s.setDirty();
    }

    /** A quest NPC entity joined a level: a stale copy (not the one on record) is removed. */
    public static boolean isStaleCopy(MinecraftServer server, Entity e) {
        String id = idOf(e);
        if (id.isEmpty()) return false;
        Rec r = Store.get(server).recs.get(id);
        return r != null && r.uuid != null && !r.uuid.equals(e.getUUID());
    }

    /** Whether the entity is an essential (unkillable) quest NPC. */
    public static boolean isEssential(Entity e) {
        String id = idOf(e);
        if (id.isEmpty()) return false;
        StoryData.NpcDef def = StoryData.npc(id);
        return def != null && def.essential;
    }

    public static boolean inDimension(Entity e, ResourceKey<Level> dim) {
        return e.level().dimension() == dim;
    }
}
