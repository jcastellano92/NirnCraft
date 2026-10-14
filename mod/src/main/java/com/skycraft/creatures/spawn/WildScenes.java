package com.skycraft.creatures.spawn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.creatures.CreaturesConfig;
import com.skycraft.creatures.Leveling;
import com.skycraft.society.Encounters;
import com.skycraft.society.Npcs;
import com.skycraft.society.NpcRole;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A small table of scripted-ish wandering scenes, driven by {@code skycraft/creatures/scenes.json}: a caravan beset
 * by wolves, hunters tracking an elk, a lone pilgrim, two thugs arguing over loot, a hunters' camp. Complements the
 * society module's {@link Encounters} (which owns couriers, escorts, vampires...) with an ecology-minded layer:
 * low frequency (one roll per player per {@code cooldownSeconds}), a daylight bias, the shared hostile pacing rules
 * ({@link SpawnPacing}), and entirely made from existing NPC roles and creatures. Opposed groups then also fight
 * through {@link com.skycraft.creatures.FactionWars}. A scene is a moment: its mobs are tagged, and removed once the
 * player has moved on (never kept across a restart).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class WildScenes {
    private static final String TAG = "skycraft_scene";

    private record MemberDef(String npcRole, EntityType<?> type, int min, int max, String as, String target, String leader,
                             boolean past, boolean hold, int dx, int dz, String nameKey) {}

    private record SceneDef(String id, int weight, SpawnTables.Time time, SpawnTables.Weather weather, int minLevel, int maxLevel,
                            Set<BiomeFamily> families, Set<String> regions, Set<String> provinces, SpawnTables.RoadRule road,
                            int townMin, int townMax, boolean hostile, boolean campfire, int calmAfter, List<MemberDef> members) {}

    /** A live scene: its mobs, when it ends, when mutual fights break off, and a campfire to put out. */
    private static final class Live {
        final long id;
        final ServerLevel level;
        final List<Mob> mobs = new ArrayList<>();
        final long end;
        final long calmAt;
        final List<Mob> fighters = new ArrayList<>();
        BlockPos campfire;

        Live(long id, ServerLevel level, long end, long calmAt) {
            this.id = id;
            this.level = level;
            this.end = end;
            this.calmAt = calmAt;
        }
    }

    private static boolean loaded;
    private static List<SceneDef> scenes = List.of();
    private static int cooldownMin = 900;
    private static int cooldownMax = 1500;
    private static int firstMin = 240;
    private static int firstMax = 480;
    private static double nightFactor = 0.3;
    private static int minDist = 45;
    private static int maxDist = 85;
    private static int lifetime = 900;

    private static final Map<UUID, Long> NEXT = new HashMap<>();
    private static final List<Live> LIVE = new ArrayList<>();
    private static final Set<Long> ACTIVE = new HashSet<>();

    private WildScenes() {}

    // ------------------------------------------------------------------ data

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = WildScenes.class.getResourceAsStream("/skycraft/creatures/scenes.json")) {
            if (in == null) return;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            int[] cd = range(root, "cooldownSeconds", 900, 1500);
            cooldownMin = cd[0];
            cooldownMax = Math.max(cd[0], cd[1]);
            int[] first = range(root, "firstSeconds", 240, 480);
            firstMin = first[0];
            firstMax = Math.max(first[0], first[1]);
            int[] dist = range(root, "distance", 45, 85);
            minDist = dist[0];
            maxDist = Math.max(dist[0], dist[1]);
            if (root.has("nightFactor")) nightFactor = root.get("nightFactor").getAsDouble();
            if (root.has("lifetimeSeconds")) lifetime = root.get("lifetimeSeconds").getAsInt();
            List<SceneDef> out = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("scenes")) {
                try {
                    out.add(parseScene(e.getAsJsonObject()));
                } catch (RuntimeException ex) {
                    Skycraft.LOGGER.warn("Bad wild scene: {}", ex.toString());
                }
            }
            scenes = List.copyOf(out);
            Skycraft.LOGGER.info("Loaded {} wild scenes", scenes.size());
        } catch (Exception ex) {
            Skycraft.LOGGER.warn("Wild scenes failed to load: {}", ex.toString());
        }
    }

    private static int[] range(JsonObject o, String key, int defMin, int defMax) {
        if (!o.has(key)) return new int[]{defMin, defMax};
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) return new int[]{e.getAsInt(), e.getAsInt()};
        JsonArray a = e.getAsJsonArray();
        return new int[]{a.get(0).getAsInt(), a.get(a.size() - 1).getAsInt()};
    }

    private static Set<String> strings(JsonObject o, String key) {
        Set<String> s = new HashSet<>();
        if (o.has(key)) for (JsonElement e : o.getAsJsonArray(key)) s.add(e.getAsString());
        return s;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) ? o.get(key).getAsString() : def;
    }

    private static SceneDef parseScene(JsonObject o) {
        EnumSet<BiomeFamily> fams = EnumSet.noneOf(BiomeFamily.class);
        for (String f : strings(o, "families")) fams.add(BiomeFamily.byId(f));
        int[] lv = range(o, "levels", 1, 999);
        List<MemberDef> members = new ArrayList<>();
        for (JsonElement me : o.getAsJsonArray("members")) {
            JsonObject m = me.getAsJsonObject();
            String npc = m.has("npc") ? m.get("npc").getAsString() : null;
            EntityType<?> type = null;
            if (npc != null) {
                if (NpcRole.byId(npc) == null) throw new IllegalArgumentException("unknown npc role " + npc);
            } else {
                net.minecraft.resources.ResourceLocation id = new net.minecraft.resources.ResourceLocation(m.get("entity").getAsString());
                if (!ForgeRegistries.ENTITY_TYPES.containsKey(id)) throw new IllegalArgumentException("unknown entity " + id);
                type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            }
            int[] c = range(m, "count", 1, 1);
            members.add(new MemberDef(npc, type, c[0], Math.max(c[0], c[1]), str(m, "as", ""), str(m, "target", ""), str(m, "leader", ""),
                    m.has("dest") && "past".equals(m.get("dest").getAsString()), m.has("hold") && m.get("hold").getAsBoolean(),
                    m.has("dx") ? m.get("dx").getAsInt() : 0, m.has("dz") ? m.get("dz").getAsInt() : 0, str(m, "name", "")));
        }
        return new SceneDef(o.get("id").getAsString(), Math.max(1, o.has("weight") ? o.get("weight").getAsInt() : 5),
                SpawnTables.Time.valueOf(str(o, "time", "day").toUpperCase(Locale.ROOT)),
                SpawnTables.Weather.valueOf(str(o, "weather", "any").toUpperCase(Locale.ROOT)), lv[0], lv[1], fams,
                strings(o, "regions"), strings(o, "provinces"),
                SpawnTables.RoadRule.valueOf(str(o, "road", "any").toUpperCase(Locale.ROOT)),
                o.has("townMin") ? o.get("townMin").getAsInt() : 0, o.has("townMax") ? o.get("townMax").getAsInt() : 0,
                o.has("hostile") && o.get("hostile").getAsBoolean(), o.has("campfire") && o.get("campfire").getAsBoolean(),
                o.has("calmAfter") ? o.get("calmAfter").getAsInt() : 0, List.copyOf(members));
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        NEXT.remove(event.getEntity().getUUID());
    }

    /** A scene's mobs that come back from a save (no live scene) are gone: scenes never survive a restart. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (event.getLevel().isClientSide || !e.getPersistentData().contains(TAG)) return;
        if (!ACTIVE.contains(e.getPersistentData().getLong(TAG))) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if ((level.getGameTime() + player.getId() * 13L) % 100 != 0) return;
        if (level.dimension() != Level.OVERWORLD || player.isSpectator() || !player.isAlive()) return;
        if (!CreaturesConfig.REGION_SPAWNING.get() || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) return;
        if (!loaded) load();
        if (scenes.isEmpty()) return;
        RandomSource random = level.getRandom();
        long now = level.getGameTime();
        Long next = NEXT.get(player.getUUID());
        if (next == null) {
            NEXT.put(player.getUUID(), now + 20L * (firstMin + random.nextInt(Math.max(1, firstMax - firstMin + 1))));
            return;
        }
        if (now < next) return;
        long wait = 20L * (cooldownMin + random.nextInt(Math.max(1, cooldownMax - cooldownMin + 1)));
        NEXT.put(player.getUUID(), now + wait);
        try {
            if (!roll(level, player, random)) NEXT.put(player.getUUID(), now + 20L * 60); // nothing fit: look again in a minute
        } catch (RuntimeException ex) {
            Skycraft.LOGGER.warn("Wild scene failed", ex);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || LIVE.isEmpty()) return;
        long now = com.skycraft.world.RestManager.liveTime(level);
        if (now % 40 != 11) return;
        for (Iterator<Live> it = LIVE.iterator(); it.hasNext(); ) {
            Live s = it.next();
            if (s.level != level) continue;
            if (s.calmAt > 0 && now >= s.calmAt && !s.fighters.isEmpty()) {
                // an argument, not a war: they stop fighting each other
                for (Mob m : s.fighters) {
                    if (m.isAlive() && m.getTarget() != null && !(m.getTarget() instanceof net.minecraft.world.entity.player.Player)) m.setTarget(null);
                }
                s.fighters.clear();
            }
            boolean allGone = true;
            boolean watched = false;
            boolean near = false;
            for (Mob m : s.mobs) {
                if (m.isRemoved() || !m.isAlive()) continue;
                allGone = false;
                // adopted: named by a name tag, leashed or made persistent by the player
                if (m.isLeashed() || m.getPersistentData().contains("skycraft_keep")) {
                    m.getPersistentData().remove(TAG);
                    continue;
                }
                if (level.getNearestPlayer(m, 128) != null) watched = true;
                if (level.getNearestPlayer(m, 48) != null) near = true;
            }
            boolean over = allGone || !watched || now > s.end && !near;
            if (!over) continue;
            for (Mob m : s.mobs) {
                if (!m.isRemoved() && m.getPersistentData().contains(TAG)) m.discard();
            }
            if (s.campfire != null && level.getBlockState(s.campfire).is(Blocks.CAMPFIRE)) level.removeBlock(s.campfire, false);
            ACTIVE.remove(s.id);
            it.remove();
        }
    }

    // ------------------------------------------------------------------ one roll

    private static boolean roll(ServerLevel level, ServerPlayer player, RandomSource random) {
        boolean night = level.isNight();
        if (night && random.nextDouble() > nightFactor) return true; // a quiet night
        // wilderness only: the surface, outside settlements
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.getBlockX(), player.getBlockZ());
        if (surface - player.getBlockY() > 8 || RegionSpawner.inSettlement(level, player.blockPosition())) return false;
        // one scene, and no society encounter, near the player at a time
        for (Live s : LIVE) {
            if (s.level == level && s.mobs.stream().anyMatch(m -> !m.isRemoved() && m.distanceToSqr(player) < 160 * 160)) return true;
        }
        if (!level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(128, 64, 128), Encounters::isTagged).isEmpty()) return true;
        boolean peaceful = level.getDifficulty() == Difficulty.PEACEFUL;

        BlockPos spot = pickSpot(level, player, random);
        if (spot == null) return false;
        Region region = Regions.at(level, spot);
        BiomeFamily family = BiomeFamily.of(level.getBiome(spot), spot);
        int regionLevel = Leveling.regionLevel(level, spot);
        double fromStart = SpawnPacing.distFromStart(level, spot.getX(), spot.getZ());
        if (fromStart < 900) regionLevel = Math.min(regionLevel, 2 + (int) (fromStart / 90));
        boolean nearStart = SpawnPacing.nearStart(level, spot.getX(), spot.getZ());
        boolean onRoad = SpawnPacing.onRoad(level, spot);
        long dayTime = level.getDayTime() % 24000L;
        SpawnTables.Weather weather = level.isThundering() ? SpawnTables.Weather.STORM
                : level.isRaining() ? SpawnTables.Weather.RAIN : SpawnTables.Weather.CLEAR;

        List<SceneDef> ok = new ArrayList<>();
        int total = 0;
        for (SceneDef s : scenes) {
            if (s.hostile() && (peaceful || nearStart || !SpawnPacing.hostileReady(player) || SpawnPacing.calm(level, spot))) continue;
            if (regionLevel < s.minLevel() || regionLevel > s.maxLevel()) continue;
            if (!s.regions().isEmpty() && !s.regions().contains(region.id())) continue;
            if (!s.provinces().isEmpty() && !s.provinces().contains(region.province())) continue;
            if (!s.families().isEmpty() && !s.families().contains(family)) continue;
            switch (s.time()) {
                case DAY -> { if (night) continue; }
                case NIGHT -> { if (!night) continue; }
                case DAWN -> { if (night || !(dayTime < 3500 || dayTime > 22800)) continue; }
                case DUSK -> { if (night || !(dayTime > 10000 && dayTime < 13000)) continue; }
                default -> { }
            }
            if (s.weather() != SpawnTables.Weather.ANY && s.weather() != weather
                    && !(s.weather() == SpawnTables.Weather.RAIN && weather == SpawnTables.Weather.STORM)) continue;
            if (s.road() == SpawnTables.RoadRule.ON && !onRoad || s.road() == SpawnTables.RoadRule.OFF && onRoad) continue;
            if (s.townMin() > 0 && SpawnPacing.settlementWithin(level, spot, s.townMin())) continue;
            if (s.townMax() > 0 && !SpawnPacing.settlementWithin(level, spot, s.townMax())) continue;
            ok.add(s);
            total += s.weight();
        }
        if (total <= 0) return false;
        int r = random.nextInt(total);
        SceneDef chosen = ok.get(0);
        for (SceneDef s : ok) {
            r -= s.weight();
            if (r < 0) {
                chosen = s;
                break;
            }
        }
        boolean spawned = spawn(level, player, spot, chosen, random);
        if (spawned && chosen.hostile()) SpawnPacing.noteHostile(player);
        return spawned;
    }

    /** A loaded, ticking surface spot {@code minDist..maxDist} from the player, out of their view, away from towns. */
    private static BlockPos pickSpot(ServerLevel level, ServerPlayer player, RandomSource random) {
        Vec3 look = player.getViewVector(1f).multiply(1, 0, 1);
        look = look.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : look.normalize();
        for (int i = 0; i < 16; i++) {
            double ang = random.nextDouble() * Math.PI * 2;
            Vec3 dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            if (dir.dot(look) > 0.3) continue;
            double dist = minDist + random.nextDouble() * (maxDist - minDist);
            int x = Mth.floor(player.getX() + dir.x * dist);
            int z = Mth.floor(player.getZ() + dir.z * dist);
            BlockPos ground = Encounters.groundAt(level, x, z);
            if (ground == null || !level.isPositionEntityTicking(ground)) continue;
            if (Math.abs(ground.getY() - player.getBlockY()) > 25) continue;
            if (RegionSpawner.inSettlement(level, ground)) continue;
            return ground;
        }
        return null;
    }

    private static BlockPos around(ServerLevel level, BlockPos center, int radius, RandomSource random) {
        for (int i = 0; i < 6; i++) {
            BlockPos p = Encounters.groundAt(level, center.getX() + random.nextInt(radius * 2 + 1) - radius,
                    center.getZ() + random.nextInt(radius * 2 + 1) - radius);
            if (p != null && Math.abs(p.getY() - center.getY()) <= 3) return p;
        }
        return center;
    }

    // ------------------------------------------------------------------ spawning a scene

    private static boolean spawn(ServerLevel level, ServerPlayer player, BlockPos center, SceneDef def, RandomSource random) {
        long id = level.getGameTime() * 31L + random.nextInt(1 << 20) + 1;
        long now = com.skycraft.world.RestManager.liveTime(level);
        Live live = new Live(id, level, now + 20L * lifetime, def.calmAfter() > 0 ? now + 20L * def.calmAfter() : 0);
        Map<String, List<Mob>> byRole = new HashMap<>();
        // away from the player on the line spot -> player, so travelers walk past them
        double dx = player.getX() - center.getX();
        double dz = player.getZ() - center.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        BlockPos past = new BlockPos(Mth.floor(player.getX() + dx / len * 60 - dz / len * (random.nextDouble() - 0.5) * 16),
                player.getBlockY(), Mth.floor(player.getZ() + dz / len * 60 + dx / len * (random.nextDouble() - 0.5) * 16));
        if (def.campfire()) {
            BlockPos fire = center;
            if (level.getBlockState(fire).isAir() && level.getBlockState(fire.below()).isFaceSturdy(level, fire.below(), net.minecraft.core.Direction.UP)) {
                level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), 3);
                live.campfire = fire;
            }
        }
        ACTIVE.add(id);
        for (MemberDef m : def.members()) {
            int n = m.min() + (m.max() > m.min() ? random.nextInt(m.max() - m.min() + 1) : 0);
            for (int i = 0; i < n; i++) {
                BlockPos at = around(level, center.offset(m.dx(), 0, m.dz()), 3, random);
                Mob mob = create(level, m, at);
                if (mob == null) continue;
                mob.getPersistentData().putLong(TAG, id);
                if (m.hold() && mob instanceof PathfinderMob pm) pm.restrictTo(center, 5);
                if (m.past() && mob instanceof NpcEntity npc) npc.setDestination(past);
                if (!m.nameKey().isEmpty()) mob.setCustomName(Component.translatable(m.nameKey()));
                if (!m.leader().isEmpty() && mob instanceof NpcEntity npc) {
                    List<Mob> lead = byRole.get(m.leader());
                    if (lead != null && !lead.isEmpty()) npc.setLeader(lead.get(0).getUUID());
                }
                if (!m.target().isEmpty()) {
                    List<Mob> victims = byRole.get(m.target());
                    if (victims != null && !victims.isEmpty()) {
                        Mob victim = victims.get(random.nextInt(victims.size()));
                        mob.setTarget(victim);
                        live.fighters.add(mob);
                        // the victim defends itself too
                        if (victim.getTarget() == null) {
                            victim.setTarget(mob);
                            live.fighters.add(victim);
                        }
                    }
                }
                live.mobs.add(mob);
                if (!m.as().isEmpty()) byRole.computeIfAbsent(m.as(), k -> new ArrayList<>()).add(mob);
            }
        }
        if (live.mobs.isEmpty()) {
            ACTIVE.remove(id);
            if (live.campfire != null) level.removeBlock(live.campfire, false);
            return false;
        }
        LIVE.add(live);
        return true;
    }

    private static Mob create(ServerLevel level, MemberDef m, BlockPos at) {
        if (m.npcRole() != null) {
            NpcEntity npc = Npcs.create(level, at, NpcRole.byId(m.npcRole()));
            if (npc == null) return null;
            return level.addFreshEntity(npc) ? npc : null;
        }
        Entity e = m.type().create(level);
        if (!(e instanceof Mob mob)) {
            if (e != null) e.discard();
            return null;
        }
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        if (!level.noCollision(mob)) return null;
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);
        return level.addFreshEntity(mob) ? mob : null;
    }
}
