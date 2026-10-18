package com.skycraft.roads;

import com.skycraft.Skycraft;
import com.skycraft.society.NpcNames;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ambient road traffic. Every traveller is a society NPC (or a guard / bandit / pack animal) tagged with {@link #TAG};
 * only tagged mobs are ever despawned by this class. The mix (owner, playtest 4: "too many merchants, mix it up"):
 * merchants (Khajiit caravans, tinkers) about {@link RoadsConfig#TRAVELER_MERCHANT_PERCENT} per cent, the rest
 * couriers, pilgrims, hunters, wandering bards, farmers with a donkey, refugees, adventurers, tax collectors with an
 * escort, soldiers and guard patrols, and rare bandit ambushes. Travelling merchants keep no shop hours
 * ({@code skycraft_always_open}). A traveller's stay at a settlement is limited ({@link #LINGER}); nobody lives on the
 * road for more than {@link #LIFETIME}.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Travelers {
    public static final String TAG = "skycraft_traveler";
    public static final String CARAVAN = "caravan";
    public static final String COURIER = "courier";
    public static final String PATROL = "patrol";
    public static final String AMBUSH = "ambush";
    public static final String PILGRIM = "pilgrim";
    public static final String HUNTER = "hunter";
    public static final String BARD = "bard";
    public static final String SOLDIERS = "soldiers";
    public static final String TINKER = "tinker";
    public static final String FARMER = "farmer";
    public static final String REFUGEES = "refugees";
    public static final String ADVENTURER = "adventurer";
    public static final String TAX = "tax";
    static final String LLAMA = "llama";
    static final String BEAST = "beast";
    public static final String[] KINDS = {CARAVAN, COURIER, PATROL, AMBUSH, PILGRIM, HUNTER, BARD, SOLDIERS, TINKER, FARMER,
            REFUGEES, ADVENTURER, TAX};

    private static final Identifier GUARD = Identifier.fromNamespaceAndPath(Skycraft.MODID, "guard");
    private static final Identifier BANDIT = Identifier.fromNamespaceAndPath(Skycraft.MODID, "bandit");
    private static final int MIN_SPAWN = 40, MAX_SPAWN = 100;
    private static final double CAP_RADIUS = 128;
    /** A group is gone for good this many ticks after it appeared (when nobody is looking). */
    private static final long LIFETIME = 20 * 60 * 8L;
    /** How long travellers stay once they have reached their destination before they move on. */
    private static final long LINGER = 20 * 75L;

    /** Loaded travelers by UUID (maintained by join/leave events). */
    private static final Map<UUID, Mob> TRAVELERS = new HashMap<>();
    /** Seconds until each player's next spawn attempt. */
    private static final Map<UUID, Integer> NEXT = new HashMap<>();

    private Travelers() {}

    @Nullable
    static CompoundTag tag(Entity entity) {
        CompoundTag pd = entity.getPersistentData();
        return com.skycraft.platform.NbtData.hasType(pd, TAG, Tag.TAG_COMPOUND) ? pd.getCompoundOrEmpty(TAG) : null;
    }

    static int loadedCount() {
        return TRAVELERS.size();
    }

    static void clear() {
        TRAVELERS.clear();
        NEXT.clear();
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        CompoundTag t = tag(mob);
        if (t == null) return;
        TRAVELERS.put(mob.getUUID(), mob);
        String kind = t.getStringOr("kind", "");
        if (t.getBooleanOr("static", false) || LLAMA.equals(kind)) return;
        if (PATROL.equals(kind) && mob instanceof PathfinderMob pm) pm.clearHome(); // don't get pulled home
        for (WrappedGoal g : mob.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof TravelerGoal) return;
        }
        mob.goalSelector.addGoal(2, new TravelerGoal(mob));
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        TRAVELERS.remove(e.getUUID(), e);
    }

    /** Naming or leashing a traveler adopts it: it is no longer managed (or despawned) by the roads module. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        Entity target = event.getTarget();
        if (tag(target) == null) return;
        if (event.getItemStack().is(Items.NAME_TAG) || event.getItemStack().is(Items.LEAD)) {
            target.getPersistentData().remove(TAG);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        NEXT.remove(event.getEntity().getUUID());
    }

    // ------------------------------------------------------------------ tick

    static void tick(ServerLevel level, RoadsData data, long tick) {
        if (tick % 100 == 50) despawnPass(level);
        if (tick % 20 != 0 || !RoadsConfig.TRAVELERS.get()) return;
        if (data.roads.isEmpty() || !level.getGameRules().get(GameRules.SPAWN_MOBS)) return;
        int interval = RoadsConfig.TRAVELER_INTERVAL.get();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            UUID id = player.getUUID();
            int left = NEXT.getOrDefault(id, 5 + level.getRandom().nextInt(interval)) - 1;
            if (left > 0) {
                NEXT.put(id, left);
                continue;
            }
            NEXT.put(id, interval);
            try {
                trySpawn(level, data, player, null);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Skycraft roads: traveler spawn failed", e);
            }
        }
    }

    private static void despawnPass(ServerLevel level) {
        if (TRAVELERS.isEmpty()) return;
        double far = RoadsConfig.TRAVELER_DESPAWN_DISTANCE.get();
        List<Mob> gone = new ArrayList<>();
        for (Mob m : TRAVELERS.values()) {
            if (m.isRemoved() || m.level() != level) continue;
            CompoundTag t = tag(m);
            if (t == null) continue;
            if ((m instanceof AbstractHorse h && h.isTamed()) || m.getLeashHolder() instanceof Player) {
                m.getPersistentData().remove(TAG); // adopted by a player
                continue;
            }
            if (level.getNearestPlayer(m, far) == null) {
                gone.add(m);
                continue;
            }
            if (t.read("leader", net.minecraft.core.UUIDUtil.CODEC).isPresent()) continue; // group members leave with their leader (below)
            long now = level.getGameTime();
            if (!t.contains("born")) t.putLong("born", now);
            long born = t.getLongOr("born", 0L);
            boolean arrived = t.getBooleanOr("arrived", false) || t.getIntOr("stuck", 0) > 60;
            if (arrived && !t.contains("arrivedAt")) t.putLong("arrivedAt", now);
            boolean watched = level.getNearestPlayer(m, 24) != null;
            if (arrived && now - t.getLongOr("arrivedAt", 0L) > LINGER) {
                // the visit is over: out of sight they vanish, in sight they turn round and walk away
                if (!watched) gone.add(m);
                else if (!t.getBooleanOr("leaving", false)) leave(t);
            } else if (t.getBooleanOr("leaving", false) && level.getNearestPlayer(m, 40) == null) {
                gone.add(m);
            } else if (now - born > LIFETIME && level.getNearestPlayer(m, 40) == null) {
                gone.add(m);
            } else if (now - born > LIFETIME * 2 && !t.getBooleanOr("leaving", false)) {
                leave(t); // a lifetime is a lifetime: they start for the horizon even with someone watching
            }
        }
        if (gone.isEmpty()) return;
        // group members (llamas, second guard, ambushers) leave with their leader
        List<Mob> followers = new ArrayList<>();
        for (Mob m : TRAVELERS.values()) {
            CompoundTag t = tag(m);
            if (t == null || !t.read("leader", net.minecraft.core.UUIDUtil.CODEC).isPresent() || gone.contains(m) || m.isRemoved()) continue;
            UUID leader = t.read("leader", net.minecraft.core.UUIDUtil.CODEC).orElseThrow();
            for (Mob g : gone) {
                if (g.getUUID().equals(leader)) {
                    followers.add(m);
                    break;
                }
            }
        }
        gone.addAll(followers);
        for (Mob m : gone) {
            if (m.isLeashed()) m.removeLeash();
        }
        for (Mob m : gone) m.discard();
    }

    /** Sends a traveller back down the road it came along (no further legs); it vanishes once nobody is near. */
    private static void leave(CompoundTag t) {
        t.putInt("dir", t.getIntOr("dir", 0) >= 0 ? -1 : 1);
        t.putBoolean("arrived", false);
        t.putBoolean("leaving", true);
        t.putInt("legs", 99);
        t.putInt("stuck", 0);
        t.remove("arrivedAt");
    }

    // ------------------------------------------------------------------ steering

    private static double speed(Mob mob) {
        CompoundTag t = tag(mob);
        String kind = t == null ? "" : t.getStringOr("kind", "");
        return switch (kind) {
            case PILGRIM, REFUGEES -> 0.42;
            case FARMER, CARAVAN -> 0.5;
            case TINKER, BARD, TAX -> 0.55;
            case HUNTER -> 0.6;
            case ADVENTURER, SOLDIERS, PATROL -> 0.65;
            case COURIER -> 0.8;
            default -> mob instanceof Villager || mob instanceof WanderingTrader ? 0.5 : 0.7;
        };
    }

    /** Kinds that stop for a rest at the roadside now and then (the bard plays, the pilgrim prays). */
    private static boolean rests(String kind) {
        return switch (kind) {
            case PILGRIM, BARD, HUNTER, FARMER, TINKER, REFUGEES, CARAVAN -> true;
            default -> false;
        };
    }

    private static void moveTo(Mob mob, double x, double y, double z, double speed) {
        if (mob instanceof Villager v) {
            // villagers are brain-driven: give the brain a walk target instead of fighting it for the navigation
            v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(BlockPos.containing(x, y, z), (float) speed, 1));
        } else {
            mob.getNavigation().moveTo(x, y, z, speed);
        }
    }

    /** Called about once a second by {@link TravelerGoal}. */
    static void steer(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        CompoundTag t = tag(mob);
        if (t == null) return;
        if (t.read("leader", net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
            Entity leader = level.getEntity(t.read("leader", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            if (leader instanceof Mob lm && lm.isAlive() && lm.distanceToSqr(mob) < 48 * 48) {
                t.putInt("lost", 0);
                CompoundTag lt = tag(lm);
                if (lt != null) {
                    t.putInt("idx", lt.getIntOr("idx", 0));
                    t.putBoolean("arrived", lt.getBooleanOr("arrived", false));
                }
                if (mob.distanceToSqr(lm) > 9) moveTo(mob, lm.getX(), lm.getY(), lm.getZ(), speed(mob) * 1.15);
                return;
            }
            int lost = t.getIntOr("lost", 0) + 1;
            t.putInt("lost", lost);
            if (lost < 5) return;
            t.remove("leader"); // the leader is gone: lead the rest of the way
        }
        if (!RoadNetwork.isRoadLevel(level)) return;
        // a rest at the roadside: the bard plays a tune, the pilgrim stands in prayer, the rest sit about
        int rest = t.getIntOr("rest", 0);
        if (rest > 0) {
            t.putInt("rest", rest - 1);
            mob.getNavigation().stop();
            if (rest == 1 && mob instanceof NpcEntity n) n.setPlaying(false);
            return;
        }
        String kind = t.getStringOr("kind", "");
        if (rests(kind) && !t.getBooleanOr("leaving", false) && level.getRandom().nextInt(110) == 0) {
            t.putInt("rest", 12 + level.getRandom().nextInt(26));
            mob.getNavigation().stop();
            if (mob instanceof NpcEntity n && kind.equals(BARD)) n.setPlaying(true);
            return;
        }
        Road road = RoadsData.get(level).road(t.getIntOr("road", 0));
        if (road == null) {
            t.putBoolean("arrived", true);
            return;
        }
        int dir = t.getIntOr("dir", 0) >= 0 ? 1 : -1;
        int idx = road.clamp(t.getIntOr("idx", 0));
        int best = idx;
        double bestD = Double.MAX_VALUE;
        for (int k = -4; k <= 24; k++) {
            int i = idx + k * dir;
            if (i < 0 || i >= road.size()) continue;
            double dx = road.xs[i] + 0.5 - mob.getX(), dz = road.zs[i] + 0.5 - mob.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        idx = best;
        int end = dir > 0 ? road.size() - 1 : 0;
        if (Math.abs(end - idx) <= 3) {
            t.putInt("idx", idx);
            if (!continueJourney(level, mob, t, road, dir > 0 ? road.b : road.a)) t.putBoolean("arrived", true);
            return;
        }
        double mx = mob.getX() - t.getDoubleOr("lx", 0d), mz = mob.getZ() - t.getDoubleOr("lz", 0d);
        t.putDouble("lx", mob.getX());
        t.putDouble("lz", mob.getZ());
        int stuck = mx * mx + mz * mz < 0.25 ? t.getIntOr("stuck", 0) + 1 : 0;
        t.putInt("stuck", stuck);
        if (stuck > 0 && stuck % 6 == 0) idx = road.clamp(idx + dir * 4); // try a point further ahead
        t.putInt("idx", idx);

        int target = road.clamp(idx + dir * 6); // ~12 blocks ahead
        int x = road.xs[target], z = road.zs[target];
        if (!level.isLoaded(new BlockPos(x, mob.getBlockY(), z))) return;
        BlockPos dest = com.skycraft.society.NpcPlacement.safeSurfaceAt(level, x, z);
        moveTo(mob, dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, speed(mob));
    }

    /**
     * At the end of a road, travelers often go on along another road from that settlement (caravans and couriers
     * crossing the province, patrols walking their beat), up to a few legs; group members follow their leader.
     */
    private static boolean continueJourney(ServerLevel level, Mob mob, CompoundTag t, Road road, int at) {
        int legs = t.getIntOr("legs", 0);
        if (legs >= 4 || level.getRandom().nextInt(100) >= 65) return false;
        RoadsData data = RoadsData.get(level);
        it.unimi.dsi.fastutil.ints.IntArrayList options = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int id : data.roadsAt(at)) {
            Road r = data.road(id);
            if (r == null || r.id == road.id || r.isTrail() || r.size() < 40) continue;
            options.add(id);
        }
        if (options.isEmpty()) return false;
        Road next = data.road(options.getInt(level.getRandom().nextInt(options.size())));
        if (next == null) return false;
        int dir = next.a == at ? 1 : -1;
        t.putInt("road", next.id);
        t.putInt("idx", next.endAt(at));
        t.putInt("dir", dir);
        t.putInt("dest", next.other(at));
        t.putInt("legs", legs + 1);
        t.putInt("stuck", 0);
        return true;
    }

    // ------------------------------------------------------------------ spawning

    private static int groupsNear(Player player) {
        int n = 0;
        for (Mob m : TRAVELERS.values()) {
            if (m.isRemoved() || m.level() != player.level()) continue;
            CompoundTag t = tag(m);
            if (t == null || t.read("leader", net.minecraft.core.UUIDUtil.CODEC).isPresent()) continue;
            if (m.distanceToSqr(player) < CAP_RADIUS * CAP_RADIUS) n++;
        }
        return n;
    }

    static boolean kindAvailable(ServerLevel level, String kind) {
        return switch (kind) {
            case PATROL -> false; // no hold guards on the roads (owner, playtest 5): the watch stays in its settlement
            case AMBUSH -> RoadsConfig.BANDIT_AMBUSHES.get() && level.getDifficulty() != Difficulty.PEACEFUL
                    && BuiltInRegistries.ENTITY_TYPE.containsKey(BANDIT);
            case CARAVAN, COURIER, PILGRIM, HUNTER, BARD, SOLDIERS, TINKER, FARMER, REFUGEES, ADVENTURER, TAX -> true;
            default -> false;
        };
    }

    /** Whether a kind sells things (they keep no shop hours and share the merchant quota). */
    static boolean merchantKind(String kind) {
        return CARAVAN.equals(kind) || TINKER.equals(kind);
    }

    /**
     * The next kind of traveller: the other kinds at fixed weights, merchants (caravans and tinkers) added so that they
     * are {@link RoadsConfig#TRAVELER_MERCHANT_PERCENT} per cent of all. After dark only the watch (patrols, soldiers)
     * and bandits are about: everybody else is in camp.
     */
    static String pickKind(ServerLevel level, RandomSource random) {
        return pickKind(level, random, com.skycraft.society.Npcs.isNight(level));
    }

    /** The mix over {@code n} picks (the self-test and the dev census read it). */
    public static java.util.Map<String, Integer> sampleMix(ServerLevel level, int n, boolean night) {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        RandomSource random = RandomSource.create(8128);
        for (int i = 0; i < n; i++) out.merge(pickKind(level, random, night), 1, Integer::sum);
        return out;
    }

    private static String pickKind(ServerLevel level, RandomSource random, boolean night) {
        java.util.LinkedHashMap<String, Integer> w = new java.util.LinkedHashMap<>();
        if (!night) {
            w.put(COURIER, 11);
            w.put(PILGRIM, 11);
            w.put(HUNTER, 9);
            w.put(BARD, 8);
            w.put(FARMER, 7);
            w.put(REFUGEES, 6);
            w.put(ADVENTURER, 7);
            w.put(TAX, 3);
        }
        w.put(SOLDIERS, 8);
        if (kindAvailable(level, PATROL)) w.put(PATROL, 9);
        if (kindAvailable(level, AMBUSH)) w.put(AMBUSH, 3);
        int others = 0;
        for (int v : w.values()) others += v;
        int pct = Math.max(0, Math.min(60, RoadsConfig.TRAVELER_MERCHANT_PERCENT.get()));
        if (!night && pct > 0) {
            int merchants = Math.max(2, Math.round(others * pct / (float) (100 - pct)));
            w.put(CARAVAN, Math.max(1, merchants * 3 / 5));
            w.put(TINKER, Math.max(1, merchants - merchants * 3 / 5));
            others += merchants;
        }
        int roll = random.nextInt(Math.max(1, others));
        for (var e : w.entrySet()) {
            if ((roll -= e.getValue()) < 0) return e.getKey();
        }
        return COURIER;
    }

    /** Dev: forces one group of {@code kind} near the player (the census harness). Returns the kind spawned, or null. */
    public static String devSpawn(ServerLevel level, ServerPlayer player, String kind) {
        return trySpawn(level, RoadsData.get(level), player, kind);
    }

    /**
     * Spawns one traveler group on a road 40-100 blocks from the player, out of their sight.
     *
     * @param forcedKind a kind from {@link #KINDS} (admin command), or null for a random one respecting the cap
     * @return the spawned kind, or null
     */
    @Nullable
    static String trySpawn(ServerLevel level, RoadsData data, ServerPlayer player, @Nullable String forcedKind) {
        if (forcedKind == null && groupsNear(player) >= RoadsConfig.TRAVELER_CAP.get()) return null;
        if (forcedKind != null && !kindAvailable(level, forcedKind)) return null;
        int pcx = player.getBlockX() >> 4, pcz = player.getBlockZ() >> 4;
        int r = (MAX_SPAWN >> 4) + 1;
        LongArrayList candidates = new LongArrayList();
        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                LongArrayList entries = data.chunkIndex.get(ChunkPos.pack(cx, cz));
                if (entries == null) continue;
                for (int k = 0; k < entries.size(); k++) {
                    long e = entries.getLong(k);
                    Road road = data.road(RoadsData.entryRoad(e));
                    int i = RoadsData.entryIndex(e);
                    if (road == null || i < 0 || i >= road.size() || road.isTrail()) continue; // traffic keeps to the roads
                    int x = road.xs[i], z = road.zs[i];
                    if ((x >> 4) != cx || (z >> 4) != cz) continue; // each point once
                    double dx = x + 0.5 - player.getX(), dz = z + 0.5 - player.getZ();
                    double d = dx * dx + dz * dz;
                    if (d < MIN_SPAWN * MIN_SPAWN || d > MAX_SPAWN * MAX_SPAWN) continue;
                    candidates.add(e);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 10; attempt++) {
            long e = candidates.getLong(random.nextInt(candidates.size()));
            Road road = data.road(RoadsData.entryRoad(e));
            int idx = RoadsData.entryIndex(e);
            if (road == null) continue;
            int x = road.xs[idx], z = road.zs[idx];
            if (data.anyZoneAt(x, z) != null) continue;
            BlockPos pos = groundAt(level, x, z);
            if (pos == null || !level.isPositionEntityTicking(pos)) continue;
            if (visible(level, player, pos)) continue;
            String kind = forcedKind != null ? forcedKind : pickKind(level, random);
            int dir = random.nextBoolean() ? 1 : -1;
            int remaining = dir > 0 ? road.size() - 1 - idx : idx;
            if (remaining < 40) dir = -dir;
            if (spawnGroup(level, kind, road, idx, dir, random)) return kind;
        }
        return null;
    }

    @Nullable
    private static BlockPos groundAt(ServerLevel level, int x, int z) {
        if (!level.isLoaded(new BlockPos(x, level.getSeaLevel(), z))) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        BlockPos below = pos.below();
        BlockState s = level.getBlockState(below);
        if (!s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES)) return null;
        if (com.skycraft.society.NpcPlacement.hasRoomBelow(level, pos)
                || com.skycraft.society.NpcPlacement.isFireHazard(level, pos)
                || com.skycraft.society.NpcPlacement.isFurnitureHazard(level, pos)) return null;
        return pos;
    }

    /** True if the player could plausibly see a mob standing at {@code pos}. */
    private static boolean visible(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5);
        Vec3 to = target.subtract(eye).normalize();
        if (player.getViewVector(1.0f).dot(to) < 0.3) return false; // outside the field of view
        HitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** A group member after the first: {@code role} walking behind the leader. */
    private static boolean follower(ServerLevel level, NpcRole role, String kind, Road road, int idx, int dir, int dest, Mob leader,
                                    int back, int side) {
        return spawnNpc(level, role, kind, road, road.clamp(idx - dir * back), dir, side, newTag(kind, road, idx, dir, dest, leader.getUUID())) != null;
    }

    private static NpcRole soldierRole(ServerLevel level, BlockPos at) {
        // the holds that stand with Ulfric send Stormcloak patrols, everywhere else the Legion walks the roads
        String region = com.skycraft.society.Cultures.regionAt(level, at).id();
        return region.equals("skyrim/eastmarch") || region.equals("skyrim/the_pale") || region.equals("skyrim/winterhold")
                ? NpcRole.STORMCLOAK_SOLDIER : NpcRole.IMPERIAL_SOLDIER;
    }

    private static boolean spawnGroup(ServerLevel level, String kind, Road road, int idx, int dir, RandomSource random) {
        int dest = dir > 0 ? road.b : road.a;
        BlockPos here = new BlockPos(road.xs[idx], level.getSeaLevel(), road.zs[idx]);
        return switch (kind) {
            case CARAVAN -> {
                Mob trader = spawnNpc(level, NpcRole.CARAVAN_TRADER, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (trader == null) yield false;
                int llamas = 1 + random.nextInt(2);
                for (int i = 1; i <= llamas; i++) {
                    Mob llama = spawn(level, net.minecraft.world.entity.EntityTypes.TRADER_LLAMA, road, road.clamp(idx - dir * (i + 1)), dir, 0,
                            newTag(LLAMA, road, idx, dir, dest, trader.getUUID()));
                    if (llama != null) llama.setLeashedTo(trader, true);
                }
                yield true;
            }
            case TINKER -> {
                Mob tinker = spawnNpc(level, NpcRole.MERCHANT, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (tinker == null) yield false;
                Mob beast = spawn(level, net.minecraft.world.entity.EntityTypes.DONKEY, road, road.clamp(idx - dir * 2), dir, 0,
                        newTag(BEAST, road, idx, dir, dest, tinker.getUUID()));
                if (beast != null) beast.setLeashedTo(tinker, true);
                yield true;
            }
            case FARMER -> {
                Mob farmer = spawnNpc(level, NpcRole.FARMER, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (farmer == null) yield false;
                Mob cart = spawn(level, net.minecraft.world.entity.EntityTypes.DONKEY, road, road.clamp(idx - dir * 2), dir, 0,
                        newTag(BEAST, road, idx, dir, dest, farmer.getUUID()));
                if (cart != null) cart.setLeashedTo(farmer, true);
                yield true;
            }
            case COURIER -> spawnNpc(level, NpcRole.COURIER, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null)) != null;
            case HUNTER -> spawnNpc(level, NpcRole.HUNTER, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null)) != null;
            case BARD -> spawnNpc(level, NpcRole.BARD, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null)) != null;
            case PILGRIM -> {
                Mob first = spawnNpc(level, NpcRole.PRIEST, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (first == null) yield false;
                if (random.nextInt(3) == 0) follower(level, NpcRole.CITIZEN, kind, road, idx, dir, dest, first, 2, 1);
                yield true;
            }
            case ADVENTURER -> {
                Mob first = spawnNpc(level, NpcRole.ADVENTURER, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (first == null) yield false;
                if (random.nextInt(5) < 2) follower(level, NpcRole.ADVENTURER, kind, road, idx, dir, dest, first, 2, 1);
                yield true;
            }
            case REFUGEES -> {
                Mob first = spawnNpc(level, NpcRole.CITIZEN, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (first == null) yield false;
                follower(level, random.nextBoolean() ? NpcRole.CHILD : NpcRole.BEGGAR, kind, road, idx, dir, dest, first, 2, 1);
                if (random.nextBoolean()) follower(level, NpcRole.CITIZEN, kind, road, idx, dir, dest, first, 3, -1);
                yield true;
            }
            case SOLDIERS -> {
                NpcRole soldier = soldierRole(level, here);
                Mob first = spawnNpc(level, soldier, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (first == null) yield false;
                follower(level, soldier, kind, road, idx, dir, dest, first, 1, 1);
                if (random.nextInt(3) == 0) follower(level, soldier, kind, road, idx, dir, dest, first, 2, -1);
                yield true;
            }
            case TAX -> {
                // a collector of the Jarl's with two soldiers of the same colours behind him
                Mob first = spawnNpc(level, NpcRole.COURIER, kind, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (first == null) yield false;
                NpcRole soldier = soldierRole(level, here);
                follower(level, soldier, kind, road, idx, dir, dest, first, 2, 1);
                follower(level, soldier, kind, road, idx, dir, dest, first, 2, -1);
                yield true;
            }
            case PATROL -> {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(GUARD);
                if (type == null) yield false;
                Mob first = spawn(level, type, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (first == null) yield false;
                spawn(level, type, road, road.clamp(idx - dir), dir, 1, newTag(kind, road, idx, dir, dest, first.getUUID()));
                yield true;
            }
            case AMBUSH -> {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(BANDIT);
                if (type == null) yield false;
                int side = random.nextBoolean() ? 1 : -1;
                int count = 2 + random.nextInt(2);
                Mob first = null;
                for (int i = 0; i < count; i++) {
                    CompoundTag t = newTag(kind, road, idx, dir, dest, first == null ? null : first.getUUID());
                    t.putBoolean("static", true);
                    Mob bandit = spawn(level, type, road, road.clamp(idx + i * 2), dir, side * (5 + (i & 1)), t);
                    if (first == null) first = bandit;
                }
                yield first != null;
            }
            default -> false;
        };
    }

    private static CompoundTag newTag(String kind, Road road, int idx, int dir, int dest, @Nullable UUID leader) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        t.putInt("road", road.id);
        t.putInt("idx", idx);
        t.putInt("dir", dir);
        t.putInt("dest", dest);
        if (leader != null) t.store("leader", net.minecraft.core.UUIDUtil.CODEC, leader);
        return t;
    }

    /** Creates, positions and adds one mob at road point {@code idx}, {@code sideOffset} blocks to the right. */
    @Nullable
    private static Mob spawn(ServerLevel level, EntityType<?> type, Road road, int idx, int dir, int sideOffset, CompoundTag tag) {
        int x = road.xs[idx], z = road.zs[idx];
        if (sideOffset != 0) {
            double[] right = RoadLayout.right(road, idx);
            x = (int) Math.round(x + right[0] * sideOffset);
            z = (int) Math.round(z + right[1] * sideOffset);
        }
        BlockPos pos = groundAt(level, x, z);
        if (pos == null) return null;
        Entity entity = type.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        if (!(entity instanceof Mob mob)) return null;
        int ahead = road.clamp(idx + dir * 3);
        float yaw = (float) (Mth.atan2(road.zs[ahead] - road.zs[idx], road.xs[ahead] - road.xs[idx]) * (180.0 / Math.PI)) - 90.0f;
        mob.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0f);
        mob.setYHeadRot(yaw);
        if (!level.noCollision(mob)) return null;
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
        tag.putLong("born", level.getGameTime());
        mob.getPersistentData().put(TAG, tag);
        if (!level.addFreshEntity(mob)) return null;
        return mob;
    }

    /**
     * Creates, positions and adds one traveller of a society role at road point {@code idx}. Made directly as a society
     * NPC (never as a vanilla villager, which the villager conversion would cancel and redo): looks and race follow the
     * region, the title names the kind, merchants keep no shop hours.
     */
    @Nullable
    private static NpcEntity spawnNpc(ServerLevel level, NpcRole role, String kind, Road road, int idx, int dir, int sideOffset,
                                      CompoundTag tag) {
        int x = road.xs[idx], z = road.zs[idx];
        if (sideOffset != 0) {
            double[] right = RoadLayout.right(road, idx);
            x = (int) Math.round(x + right[0] * sideOffset);
            z = (int) Math.round(z + right[1] * sideOffset);
        }
        BlockPos pos = groundAt(level, x, z);
        if (pos == null) return null;
        NpcEntity npc = Npcs.create(level, pos, role);
        if (npc == null) return null;
        int ahead = road.clamp(idx + dir * 3);
        float yaw = (float) (Mth.atan2(road.zs[ahead] - road.zs[idx], road.xs[ahead] - road.xs[idx]) * (180.0 / Math.PI)) - 90.0f;
        npc.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0f);
        npc.setYHeadRot(yaw);
        if (!level.noCollision(npc)) return null;
        tag.putLong("born", level.getGameTime());
        npc.getPersistentData().put(TAG, tag);
        // the title names the kind of traveller ("Orla the Pilgrim"); soldiers keep their own
        if (role.civilian && role != NpcRole.CARAVAN_TRADER) {
            String first = NpcNames.generate(npc.race(), npc.isFemale(), level.getRandom());
            npc.setCustomName(Component.translatable("roads.skycraft.title." + kind, first));
        }
        if (merchantKind(kind)) npc.getPersistentData().putBoolean("skycraft_always_open", true);
        if (!level.addFreshEntity(npc)) return null;
        return npc;
    }
}
