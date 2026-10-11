package com.skycraft.roads;

import com.skycraft.Skycraft;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ambient road traffic: Khajiit caravans (wandering trader + trader llamas), lone travelers and couriers (villagers),
 * guard patrols ({@code skycraft:guard}) and rare bandit ambushes ({@code skycraft:bandit}). Travelers are ordinary
 * mobs tagged with {@link #TAG} in their persistent data; only tagged mobs are ever despawned by this class.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Travelers {
    public static final String TAG = "skycraft_traveler";
    public static final String CARAVAN = "caravan";
    public static final String COURIER = "courier";
    public static final String PATROL = "patrol";
    public static final String AMBUSH = "ambush";
    static final String LLAMA = "llama";
    public static final String[] KINDS = {CARAVAN, COURIER, PATROL, AMBUSH};

    private static final ResourceLocation GUARD = new ResourceLocation(Skycraft.MODID, "guard");
    private static final ResourceLocation BANDIT = new ResourceLocation(Skycraft.MODID, "bandit");
    private static final int MIN_SPAWN = 40, MAX_SPAWN = 100;
    private static final double CAP_RADIUS = 128;

    private static final String[] FIRST_NAMES = {
            "Lucan", "Faendal", "Sven", "Hilde", "Alvor", "Gerdur", "Hod", "Embry", "Bjorn", "Erik", "Hulda", "Ysolda",
            "Brenuin", "Jon", "Olfina", "Idolaf", "Lod", "Temba", "Wilhelm", "Narri", "Gemma", "Fastred", "Klimmek",
            "Mjoll", "Uthgerd", "Brina", "Sigrid", "Dorthe", "Torolf", "Frida", "Lars", "Camilla", "Adrianne", "Ralis",
            "Annekke", "Gunding", "Hroki", "Leifnarr", "Svana", "Grelka"
    };
    private static final String[] TITLES = {"traveler", "pilgrim", "peddler", "bard", "hunter", "farmhand", "courier"};
    private static final String[] PATRONS = {
            "Hrongar", "Balgruuf", "Siddgeir", "Idgrod", "Skald", "Laila", "Elisif", "Igmund", "Ulfric", "Korir", "Dengeir"
    };
    private static final VillagerProfession[] PROFESSIONS = {
            VillagerProfession.FARMER, VillagerProfession.FISHERMAN, VillagerProfession.SHEPHERD,
            VillagerProfession.FLETCHER, VillagerProfession.LEATHERWORKER, VillagerProfession.CARTOGRAPHER,
            VillagerProfession.MASON, VillagerProfession.TOOLSMITH, VillagerProfession.BUTCHER,
            VillagerProfession.LIBRARIAN, VillagerProfession.CLERIC, VillagerProfession.ARMORER
    };

    /** Loaded travelers by UUID (maintained by join/leave events). */
    private static final Map<UUID, Mob> TRAVELERS = new HashMap<>();
    /** Seconds until each player's next spawn attempt. */
    private static final Map<UUID, Integer> NEXT = new HashMap<>();

    private Travelers() {}

    @Nullable
    static CompoundTag tag(Entity entity) {
        CompoundTag pd = entity.getPersistentData();
        return pd.contains(TAG, Tag.TAG_COMPOUND) ? pd.getCompound(TAG) : null;
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
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof Mob mob)) return;
        CompoundTag t = tag(mob);
        if (t == null) return;
        TRAVELERS.put(mob.getUUID(), mob);
        String kind = t.getString("kind");
        if (t.getBoolean("static") || LLAMA.equals(kind)) return;
        if (PATROL.equals(kind) && mob instanceof PathfinderMob pm) pm.clearRestriction(); // don't get pulled home
        for (WrappedGoal g : mob.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof TravelerGoal) return;
        }
        mob.goalSelector.addGoal(2, new TravelerGoal(mob));
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        Entity e = event.getEntity();
        TRAVELERS.remove(e.getUUID(), e);
    }

    /** Naming or leashing a traveler adopts it: it is no longer managed (or despawned) by the roads module. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
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
        if (data.roads.isEmpty() || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) return;
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
            boolean done = t.getBoolean("arrived") || t.getInt("stuck") > 60;
            if (done && !t.hasUUID("leader") && level.getNearestPlayer(m, 32) == null) gone.add(m);
        }
        if (gone.isEmpty()) return;
        // group members (llamas, second guard, ambushers) leave with their leader
        List<Mob> followers = new ArrayList<>();
        for (Mob m : TRAVELERS.values()) {
            CompoundTag t = tag(m);
            if (t == null || !t.hasUUID("leader") || gone.contains(m) || m.isRemoved()) continue;
            UUID leader = t.getUUID("leader");
            for (Mob g : gone) {
                if (g.getUUID().equals(leader)) {
                    followers.add(m);
                    break;
                }
            }
        }
        gone.addAll(followers);
        for (Mob m : gone) {
            if (m.isLeashed()) m.dropLeash(true, false);
        }
        for (Mob m : gone) m.discard();
    }

    // ------------------------------------------------------------------ steering

    private static double speed(Mob mob) {
        if (mob instanceof Villager) return 0.5;
        if (mob instanceof WanderingTrader) return 0.5;
        return 0.7;
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
        if (t.hasUUID("leader")) {
            Entity leader = level.getEntity(t.getUUID("leader"));
            if (leader instanceof Mob lm && lm.isAlive() && lm.distanceToSqr(mob) < 48 * 48) {
                t.putInt("lost", 0);
                CompoundTag lt = tag(lm);
                if (lt != null) {
                    t.putInt("idx", lt.getInt("idx"));
                    if (lt.getBoolean("arrived")) t.putBoolean("arrived", true);
                }
                if (mob.distanceToSqr(lm) > 9) moveTo(mob, lm.getX(), lm.getY(), lm.getZ(), speed(mob) * 1.15);
                return;
            }
            int lost = t.getInt("lost") + 1;
            t.putInt("lost", lost);
            if (lost < 5) return;
            t.remove("leader"); // the leader is gone: lead the rest of the way
        }
        if (!RoadNetwork.isRoadLevel(level)) return;
        Road road = RoadsData.get(level).road(t.getInt("road"));
        if (road == null) {
            t.putBoolean("arrived", true);
            return;
        }
        int dir = t.getInt("dir") >= 0 ? 1 : -1;
        int idx = road.clamp(t.getInt("idx"));
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
        double mx = mob.getX() - t.getDouble("lx"), mz = mob.getZ() - t.getDouble("lz");
        t.putDouble("lx", mob.getX());
        t.putDouble("lz", mob.getZ());
        int stuck = mx * mx + mz * mz < 0.25 ? t.getInt("stuck") + 1 : 0;
        t.putInt("stuck", stuck);
        if (stuck > 0 && stuck % 6 == 0) idx = road.clamp(idx + dir * 4); // try a point further ahead
        t.putInt("idx", idx);

        int target = road.clamp(idx + dir * 6); // ~12 blocks ahead
        int x = road.xs[target], z = road.zs[target];
        if (!level.isLoaded(new BlockPos(x, mob.getBlockY(), z))) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        moveTo(mob, x + 0.5, y, z + 0.5, speed(mob));
    }

    /**
     * At the end of a road, travelers often go on along another road from that settlement (caravans and couriers
     * crossing the province, patrols walking their beat), up to a few legs; group members follow their leader.
     */
    private static boolean continueJourney(ServerLevel level, Mob mob, CompoundTag t, Road road, int at) {
        int legs = t.getInt("legs");
        if (legs >= 4 || level.getRandom().nextInt(100) >= 65) return false;
        RoadsData data = RoadsData.get(level);
        it.unimi.dsi.fastutil.ints.IntArrayList options = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int id : data.roadsAt(at)) {
            Road r = data.road(id);
            if (r == null || r.id == road.id || r.kind == Road.TRAIL || r.size() < 40) continue;
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
            if (t == null || t.hasUUID("leader")) continue;
            if (m.distanceToSqr(player) < CAP_RADIUS * CAP_RADIUS) n++;
        }
        return n;
    }

    static boolean kindAvailable(ServerLevel level, String kind) {
        return switch (kind) {
            case CARAVAN, COURIER -> true;
            case PATROL -> ForgeRegistries.ENTITY_TYPES.containsKey(GUARD);
            case AMBUSH -> RoadsConfig.BANDIT_AMBUSHES.get() && level.getDifficulty() != Difficulty.PEACEFUL
                    && ForgeRegistries.ENTITY_TYPES.containsKey(BANDIT);
            default -> false;
        };
    }

    private static String pickKind(ServerLevel level, RandomSource random) {
        int caravan = 3, courier = 4;
        int patrol = kindAvailable(level, PATROL) ? 3 : 0;
        int ambush = kindAvailable(level, AMBUSH) ? 1 : 0;
        int roll = random.nextInt(caravan + courier + patrol + ambush);
        if ((roll -= caravan) < 0) return CARAVAN;
        if ((roll -= courier) < 0) return COURIER;
        if ((roll -= patrol) < 0) return PATROL;
        return AMBUSH;
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
                LongArrayList entries = data.chunkIndex.get(ChunkPos.asLong(cx, cz));
                if (entries == null) continue;
                for (int k = 0; k < entries.size(); k++) {
                    long e = entries.getLong(k);
                    Road road = data.road(RoadsData.entryRoad(e));
                    int i = RoadsData.entryIndex(e);
                    if (road == null || i < 0 || i >= road.size() || road.kind == Road.TRAIL) continue; // traffic keeps to the roads
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
        BlockPos below = new BlockPos(x, y - 1, z);
        BlockState s = level.getBlockState(below);
        if (!s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES)) return null;
        return new BlockPos(x, y, z);
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

    private static boolean spawnGroup(ServerLevel level, String kind, Road road, int idx, int dir, RandomSource random) {
        int dest = dir > 0 ? road.b : road.a;
        return switch (kind) {
            case CARAVAN -> {
                Mob trader = spawn(level, EntityType.WANDERING_TRADER, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (trader == null) yield false;
                int llamas = 1 + random.nextInt(2);
                for (int i = 1; i <= llamas; i++) {
                    Mob llama = spawn(level, EntityType.TRADER_LLAMA, road, road.clamp(idx - dir * (i + 1)), dir, 0,
                            newTag(LLAMA, road, idx, dir, dest, trader.getUUID()));
                    if (llama != null) llama.setLeashedTo(trader, true);
                }
                yield true;
            }
            case COURIER -> {
                Mob mob = spawn(level, EntityType.VILLAGER, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                yield mob != null;
            }
            case PATROL -> {
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(GUARD);
                if (type == null) yield false;
                Mob first = spawn(level, type, road, idx, dir, 0, newTag(kind, road, idx, dir, dest, null));
                if (first == null) yield false;
                spawn(level, type, road, road.clamp(idx - dir), dir, 1, newTag(kind, road, idx, dir, dest, first.getUUID()));
                yield true;
            }
            case AMBUSH -> {
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(BANDIT);
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
        if (leader != null) t.putUUID("leader", leader);
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
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) return null;
        int ahead = road.clamp(idx + dir * 3);
        float yaw = (float) (Mth.atan2(road.zs[ahead] - road.zs[idx], road.xs[ahead] - road.xs[idx]) * (180.0 / Math.PI)) - 90.0f;
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0f);
        mob.setYHeadRot(yaw);
        if (!level.noCollision(mob)) return null;
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
        if (mob instanceof Villager villager) dressVillager(level, villager, pos, level.getRandom());
        mob.getPersistentData().put(TAG, tag);
        if (!level.addFreshEntity(mob)) return null;
        return mob;
    }

    private static void dressVillager(ServerLevel level, Villager villager, BlockPos pos, RandomSource random) {
        VillagerProfession profession = random.nextInt(3) == 0 ? VillagerProfession.NITWIT : PROFESSIONS[random.nextInt(PROFESSIONS.length)];
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.byBiome(level.getBiome(pos)))
                .setProfession(profession));
        if (profession != VillagerProfession.NITWIT) villager.setVillagerXp(1); // keeps the profession without a job site
        Component name;
        if (random.nextInt(4) == 0) {
            name = Component.translatable("roads.skycraft.title.courier_of", PATRONS[random.nextInt(PATRONS.length)]);
        } else {
            name = Component.translatable("roads.skycraft.title." + TITLES[random.nextInt(TITLES.length)],
                    FIRST_NAMES[random.nextInt(FIRST_NAMES.length)]);
        }
        villager.setCustomName(name);
    }
}
