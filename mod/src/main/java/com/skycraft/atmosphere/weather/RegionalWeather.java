package com.skycraft.atmosphere.weather;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.AtmosphereMath;
import com.skycraft.atmosphere.WeatherFront;
import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Server-authoritative weather per region and moving synoptic {@link WeatherFront} cells. Vanilla weather is global,
 * so every region near a player gets its own "spell" of a {@link WeatherKind} rolled from the region's
 * {@link WeatherProfile}, while large synoptic {@link WeatherFront}s drift along prevailing wind vectors across region
 * and sea-to-shore boundaries; both are synced to clients ({@link WeatherPackets}), which render local precipitation,
 * approaching horizon storm walls, fog and sky from them.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class RegionalWeather {
    /** Sample offsets around each player, so the client can blend weather across a nearby border. */
    private static final int[][] SAMPLES = {
            {0, 0}, {96, 0}, {-96, 0}, {0, 96}, {0, -96},
            {96, 96}, {96, -96}, {-96, 96}, {-96, -96}
    };
    /**
     * Rings of samples out to the horizon (checked every five seconds), so the regions a player can see get weather
     * too and clients can draw distant storms, squalls and sandstorms over them.
     */
    private static final int[] FAR_RINGS = {450, 1000, 1600};
    private static final int FAR_DIRECTIONS = 8;
    /** Drop a region's spell this long after the last player left it. */
    private static final long FORGET_TICKS = 6000;
    private static final long VANILLA_REFRESH_TICKS = 6000;
    private static final int TARGET_SYNOPTIC_FRONTS = 6;

    private static int ticks;
    private static boolean applied;
    private static boolean lastRaining;
    private static boolean lastThundering;
    private static long lastVanillaApply;

    private RegionalWeather() {}

    /** One region's current weather. */
    static final class Spell {
        WeatherKind kind;
        long until;
        int wind;
        long lastSeen;

        Spell(WeatherKind kind, long until, int wind, long lastSeen) {
            this.kind = kind;
            this.until = until;
            this.wind = wind;
            this.lastSeen = lastSeen;
        }
    }

    // ------------------------------------------------------------------ queries

    /** The weather of {@code region} right now, or {@link WeatherKind#CLEAR} if it has none. */
    public static WeatherKind kindOf(MinecraftServer server, String region) {
        Spell s = Data.get(server).spells.get(region);
        return s == null ? WeatherKind.CLEAR : s.kind;
    }

    public static WeatherKind kindAt(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) return WeatherKind.CLEAR;
        Data data = Data.get(level.getServer());
        WeatherFront front = sampleFront(data.fronts, pos.getX(), pos.getZ());
        if (front != null && front.intensityAt(pos.getX(), pos.getZ()) >= 0.42f) {
            return front.kind();
        }
        return kindOf(level.getServer(), Regions.at(level, pos).id());
    }

    /** Alias for {@link #kindAt(ServerLevel, BlockPos)} accepting any {@link Level}. */
    public static WeatherKind at(Level level, BlockPos pos) {
        if (level instanceof ServerLevel sl) return kindAt(sl, pos);
        return WeatherKind.CLEAR;
    }

    /** True if {@code kind} is a severe outdoor storm (blizzard, sandstorm, ash storm, blight). */
    public static boolean isSevere(WeatherKind kind) {
        return kind != null && kind.severe();
    }

    /** True if the weather at {@code pos} is currently a severe outdoor storm. */
    public static boolean isSevereAt(Level level, BlockPos pos) {
        return isSevere(at(level, pos));
    }

    /**
     * The wind of the region at {@code pos}: the direction it blows towards in degrees (yaw convention, as the client
     * draws it), or -1 if the region has no weather yet. Used by sailing.
     */
    public static int windAt(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) return -1;
        Data data = Data.get(level.getServer());
        WeatherFront front = sampleFront(data.fronts, pos.getX(), pos.getZ());
        if (front != null && front.intensityAt(pos.getX(), pos.getZ()) >= 0.42f) {
            return front.windDegrees();
        }
        Spell s = data.spells.get(Regions.at(level, pos).id());
        return s == null ? -1 : s.wind;
    }

    /** Remaining ticks of the region's current spell (0 if none). */
    public static long remaining(MinecraftServer server, String region) {
        Spell s = Data.get(server).spells.get(region);
        return s == null ? 0 : Math.max(0, s.until - server.overworld().getGameTime());
    }

    /** Snapshot of all active synoptic weather fronts on the server. */
    public static List<WeatherFront> fronts(MinecraftServer server) {
        Data data = Data.get(server);
        List<WeatherFront> out = new ArrayList<>(data.fronts.size());
        for (WeatherFront f : data.fronts) out.add(f.copy());
        return out;
    }

    /**
     * Samples the active {@link WeatherFront} with the strongest influence (or nearest horizon approach within 1,600
     * blocks) at world coordinates {@code (x, z)} on the server.
     */
    public static WeatherFront sampleFront(MinecraftServer server, double x, double z) {
        return sampleFront(Data.get(server).fronts, x, z);
    }

    /**
     * Pure static selector for the strongest covering or approaching {@link WeatherFront} at {@code (x, z)}.
     * Safe to call on any thread or in Tier 0 unit tests.
     */
    public static WeatherFront sampleFront(Collection<WeatherFront> fronts, double x, double z) {
        if (fronts == null || fronts.isEmpty()) return null;
        WeatherFront bestInside = null;
        float bestInsideScore = 0.0f;
        WeatherFront bestApproach = null;
        float bestApproachScore = 0.0f;
        for (WeatherFront f : fronts) {
            if (f == null || f.expired()) continue;
            float inside = f.intensityAt(x, z);
            if (inside > bestInsideScore) {
                bestInsideScore = inside;
                bestInside = f;
            } else if (bestInside == null) {
                float app = f.approachFactor(x, z);
                if (app > bestApproachScore) {
                    bestApproachScore = app;
                    bestApproach = f;
                }
            }
        }
        return bestInside != null ? bestInside : bestApproach;
    }

    /**
     * Deterministically generates a synoptic set of moving {@link WeatherFront}s across Tamriel's climate belts.
     */
    public static List<WeatherFront> seedInitialFronts(long seed) {
        Random rng = new Random(seed ^ 0x5F3759DF9E3779B9L);
        List<WeatherFront> list = new ArrayList<>(TARGET_SYNOPTIC_FRONTS);
        // 1. Sea of Ghosts / Northern Skyrim / Atmora blizzard front moving SSE
        list.add(new WeatherFront(1,
                -600f + rng.nextFloat() * 1800f,
                -3600f + rng.nextFloat() * 900f,
                1100f + rng.nextFloat() * 600f,
                0.08f + rng.nextFloat() * 0.10f,
                0.18f + rng.nextFloat() * 0.14f,
                WeatherKind.BLIZZARD,
                0.82f + rng.nextFloat() * 0.18f,
                6000 + rng.nextInt(6000)));
        // 2. Alik'r Desert / Hammerfell sandstorm front moving ENE
        list.add(new WeatherFront(2,
                -5400f + rng.nextFloat() * 1400f,
                900f + rng.nextFloat() * 1200f,
                950f + rng.nextFloat() * 550f,
                0.22f + rng.nextFloat() * 0.14f,
                -0.06f + rng.nextFloat() * 0.12f,
                WeatherKind.SANDSTORM,
                0.85f + rng.nextFloat() * 0.15f,
                5400 + rng.nextInt(5400)));
        // 3. Red Mountain / Vvardenfell ashstorm front moving SW/W
        list.add(new WeatherFront(3,
                4800f + rng.nextFloat() * 1200f,
                -1400f + rng.nextFloat() * 1000f,
                900f + rng.nextFloat() * 500f,
                -0.18f - rng.nextFloat() * 0.10f,
                0.10f + rng.nextFloat() * 0.12f,
                WeatherKind.ASH_STORM,
                0.84f + rng.nextFloat() * 0.16f,
                5400 + rng.nextInt(6000)));
        // 4. Abecean / High Rock / Gold Coast sea squall moving inland (eastward)
        list.add(new WeatherFront(4,
                -6200f + rng.nextFloat() * 1600f,
                -800f + rng.nextFloat() * 2400f,
                1200f + rng.nextFloat() * 700f,
                0.24f + rng.nextFloat() * 0.12f,
                0.08f + rng.nextFloat() * 0.10f,
                WeatherKind.STORM,
                0.78f + rng.nextFloat() * 0.22f,
                6000 + rng.nextInt(6000)));
        // 5. Cyrodiil / Nibenay / Black Marsh rain & fog front
        list.add(new WeatherFront(5,
                600f + rng.nextFloat() * 1800f,
                2200f + rng.nextFloat() * 2000f,
                1000f + rng.nextFloat() * 700f,
                -0.10f + rng.nextFloat() * 0.20f,
                -0.16f - rng.nextFloat() * 0.10f,
                rng.nextBoolean() ? WeatherKind.RAIN : WeatherKind.FOG,
                0.72f + rng.nextFloat() * 0.20f,
                6000 + rng.nextInt(6000)));
        // 6. Central Skyrim / Jerall snow & gale front
        list.add(new WeatherFront(6,
                -400f + rng.nextFloat() * 1000f,
                -900f + rng.nextFloat() * 900f,
                850f + rng.nextFloat() * 550f,
                0.14f + rng.nextFloat() * 0.10f,
                0.15f + rng.nextFloat() * 0.10f,
                WeatherKind.SNOW,
                0.75f + rng.nextFloat() * 0.20f,
                5400 + rng.nextInt(5400)));
        return list;
    }

    /** Adds or replaces an active {@link WeatherFront} on the server and syncs it to clients. */
    public static void addFront(MinecraftServer server, WeatherFront front) {
        if (server == null || front == null) return;
        Data data = Data.get(server);
        data.fronts.removeIf(f -> f.id == front.id);
        data.fronts.add(front);
        data.nextFrontId = Math.max(data.nextFrontId, front.id + 1);
        data.setDirty();
        SkyNetwork.sendToAll(new WeatherPackets.Sync(false, List.of(), data.fronts));
    }

    /** Forces a region's weather (commands, quests). {@code ticks <= 0} uses a profile-length spell. */
    public static void set(MinecraftServer server, Region region, WeatherKind kind, int ticks) {
        ServerLevel ow = server.overworld();
        Data data = Data.get(server);
        long now = ow.getGameTime();
        WeatherProfile profile = WeatherProfile.of(region);
        int len = ticks > 0 ? ticks : profile.duration(ow.random, kind);
        Spell s = data.spells.get(region.id());
        if (s == null) {
            s = new Spell(kind, now + len, ow.random.nextInt(360), now);
            data.spells.put(region.id(), s);
        } else {
            s.kind = kind;
            s.until = now + len;
        }
        data.setDirty();
        SkyNetwork.sendToAll(new WeatherPackets.Sync(false, List.of(entry(region.id(), s)), data.fronts));
        applied = false; // re-evaluate vanilla's flag on the next pass without treating it as an external change
    }

    // ------------------------------------------------------------------ continuous latitude helpers

    public static float latitudeFactor(double blockZ) {
        return AtmosphereMath.latitudeFactor(blockZ);
    }

    public static float auroraElevationMinDeg(double blockZ) {
        return AtmosphereMath.auroraElevationMinDeg(blockZ);
    }

    public static float auroraElevationMaxDeg(double blockZ) {
        return AtmosphereMath.auroraElevationMaxDeg(blockZ);
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
        if (!AtmosphereConfig.REGIONAL_WEATHER.get()) return;
        MinecraftServer server = event.getServer();
        ServerLevel ow = server.overworld();
        if (ow == null) return;
        Data data = Data.get(server);
        long now = ow.getGameTime();
        boolean cycle = ow.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
        List<WeatherPackets.Entry> changed = new ArrayList<>();
        boolean frontsChanged = false;

        if (data.fronts.isEmpty()) {
            List<WeatherFront> initial = seedInitialFronts(ow.getSeed() ^ now);
            data.fronts.addAll(initial);
            for (WeatherFront f : initial) {
                data.nextFrontId = Math.max(data.nextFrontId, f.id + 1);
            }
            frontsChanged = true;
            data.setDirty();
        }

        // make sure every region around a player (and, every five seconds, out to the horizon) has weather
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean far = ticks % 100 == 0;
        float mist = WeatherProfile.mistHour(ow.getDayTime());
        for (ServerPlayer p : ow.players()) {
            for (int[] off : SAMPLES) {
                probe.set(p.getBlockX() + off[0], p.getBlockY(), p.getBlockZ() + off[1]);
                touch(ow, data, probe, now, mist, changed);
            }
            if (!far) continue;
            for (int ring : FAR_RINGS) {
                for (int d = 0; d < FAR_DIRECTIONS; d++) {
                    double a = Math.PI * 2 * (d + (ring / 450 % 2) * 0.5) / FAR_DIRECTIONS;
                    probe.set(p.getBlockX() + (int) (Math.cos(a) * ring), p.getBlockY(), p.getBlockZ() + (int) (Math.sin(a) * ring));
                    touch(ow, data, probe, now, mist, changed);
                }
            }
        }

        // advect synoptic weather fronts and expire/roll regional spells
        if (cycle) {
            for (Iterator<WeatherFront> fit = data.fronts.iterator(); fit.hasNext(); ) {
                WeatherFront f = fit.next();
                f.advect(20);
                if (f.expired()) {
                    fit.remove();
                    frontsChanged = true;
                }
            }
            if (data.fronts.size() < TARGET_SYNOPTIC_FRONTS) {
                List<WeatherFront> replenished = seedInitialFronts(ow.getSeed() + now * 31L + data.nextFrontId);
                for (WeatherFront nf : replenished) {
                    if (data.fronts.size() >= TARGET_SYNOPTIC_FRONTS) break;
                    data.fronts.add(new WeatherFront(
                            data.nextFrontId++, nf.x, nf.z, nf.radius, nf.vx, nf.vz, nf.kind, nf.intensity, nf.ticksLeft));
                    frontsChanged = true;
                }
            }
            if (far) frontsChanged = true; // periodic position sync for active fronts every 5 seconds
            data.setDirty();

            double scale = AtmosphereConfig.WEATHER_DURATION_SCALE.get();
            for (Iterator<Map.Entry<String, Spell>> it = data.spells.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Spell> e = it.next();
                Spell s = e.getValue();
                if (now < s.until) continue;
                if (now - s.lastSeen > FORGET_TICKS) {
                    it.remove();
                    changed.add(new WeatherPackets.Entry(e.getKey(), -1, 0));
                } else {
                    WeatherProfile profile = Regions.byId(e.getKey()).map(WeatherProfile::of)
                            .orElseGet(() -> profileFor(ow, e.getKey()));
                    s.kind = profile.roll(ow.random, s.kind, mist);
                    s.until = now + Math.max(600, (long) (profile.duration(ow.random, s.kind) * scale));
                    // wind veers a little between spells
                    s.wind = Math.floorMod(s.wind + ow.random.nextInt(91) - 45, 360);
                    changed.add(entry(e.getKey(), s));
                }
                data.setDirty();
            }
        }
        if (!changed.isEmpty() || frontsChanged) {
            SkyNetwork.sendToAll(new WeatherPackets.Sync(false, changed, frontsChanged ? data.fronts : null));
        }

        if (ticks % 100 == 0 && cycle && AtmosphereConfig.DRIVE_VANILLA_WEATHER.get()) driveVanilla(ow, data, now);
    }

    /** Gives the region at {@code pos} a spell if it has none, and marks it as seen. */
    private static void touch(ServerLevel ow, Data data, BlockPos pos, long now, float mist, List<WeatherPackets.Entry> changed) {
        Region region = Regions.at(ow, pos);
        WeatherProfile profile = WeatherProfile.of(region);
        if (profile == WeatherProfile.NONE) return;
        Spell s = data.spells.get(region.id());
        if (s == null) {
            WeatherKind kind = profile.roll(ow.random, null, mist);
            s = new Spell(kind, now + profile.duration(ow.random, kind), ow.random.nextInt(360), now);
            data.spells.put(region.id(), s);
            changed.add(entry(region.id(), s));
            data.setDirty();
        }
        s.lastSeen = now;
    }

    /** Generated regions aren't in {@link Regions#byId}: find the profile from a player standing in it. */
    private static WeatherProfile profileFor(ServerLevel ow, String regionId) {
        for (ServerPlayer p : ow.players()) {
            Region r = Regions.at(ow, p.blockPosition());
            if (r.id().equals(regionId)) return WeatherProfile.of(r);
        }
        return WeatherProfile.TEMPERATE;
    }

    /** Vanilla's global rain/thunder follow the weather where (most of) the players are. */
    private static void driveVanilla(ServerLevel ow, Data data, long now) {
        List<ServerPlayer> players = ow.players();
        if (players.isEmpty()) return;

        // someone ran /weather (or another mod changed it): adopt it into the players' regions
        if (applied && (ow.isRaining() != lastRaining || ow.isThundering() != lastThundering)) {
            boolean rain = ow.isRaining();
            boolean thunder = ow.isThundering();
            List<WeatherPackets.Entry> changed = new ArrayList<>();
            for (ServerPlayer p : players) {
                Region region = Regions.at(ow, p.blockPosition());
                WeatherProfile profile = WeatherProfile.of(region);
                if (profile == WeatherProfile.NONE) continue;
                WeatherKind kind = rain ? profile.wetKind(thunder) : WeatherKind.CLEAR;
                Spell s = data.spells.get(region.id());
                if (s == null) {
                    s = new Spell(kind, 0, ow.random.nextInt(360), now);
                    data.spells.put(region.id(), s);
                }
                s.kind = kind;
                s.until = now + profile.duration(ow.random, kind);
                changed.add(entry(region.id(), s));
            }
            data.setDirty();
            if (!changed.isEmpty()) SkyNetwork.sendToAll(new WeatherPackets.Sync(false, changed, data.fronts));
            lastRaining = rain;
            lastThundering = thunder;
            return;
        }

        int wet = 0;
        int storm = 0;
        for (ServerPlayer p : players) {
            WeatherKind k = kindAt(ow, p.blockPosition());
            if (k.wet()) wet++;
            if (k.thunder > 0) storm++;
        }
        boolean raining = wet > 0 && wet * 2 >= players.size();
        boolean thundering = raining && storm > 0 && storm * 2 >= players.size();
        if (!applied || raining != ow.isRaining() || thundering != ow.isThundering() || now - lastVanillaApply > VANILLA_REFRESH_TICKS) {
            ow.setWeatherParameters(raining ? 0 : 24000, raining ? 24000 : 0, raining, thundering);
            lastVanillaApply = now;
        }
        lastRaining = raining;
        lastThundering = thundering;
        applied = true;
    }

    // ------------------------------------------------------------------ sync

    private static WeatherPackets.Entry entry(String region, Spell s) {
        return new WeatherPackets.Entry(region, s.kind.ordinal(), s.wind);
    }

    private static WeatherPackets.Sync full(MinecraftServer server) {
        List<WeatherPackets.Entry> list = new ArrayList<>();
        Data data = Data.get(server);
        if (AtmosphereConfig.REGIONAL_WEATHER.get()) {
            data.spells.forEach((id, s) -> list.add(entry(id, s)));
        }
        return new WeatherPackets.Sync(true, list, AtmosphereConfig.REGIONAL_WEATHER.get() ? data.fronts : List.of());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) SkyNetwork.sendToPlayer(sp, full(sp.server));
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) SkyNetwork.sendToPlayer(sp, full(sp.server));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) SkyNetwork.sendToPlayer(sp, full(sp.server));
    }

    /** Forget the vanilla bookkeeping between worlds (singleplayer can open several in one session). */
    @SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        applied = false;
        ticks = 0;
    }

    // ------------------------------------------------------------------ saved data

    /** Region id -> spell, plus active moving fronts. Stored with the overworld ({@code data/skycraft_weather.dat}). */
    static final class Data extends SavedData {
        private static final String NAME = "skycraft_weather";
        final Map<String, Spell> spells = new HashMap<>();
        final List<WeatherFront> fronts = new ArrayList<>();
        int nextFrontId = 1;

        static Data get(MinecraftServer server) {
            if (server == null) server = ServerLifecycleHooks.getCurrentServer();
            return server.overworld().getDataStorage().computeIfAbsent(Data::load, Data::new, NAME);
        }

        static Data load(CompoundTag tag) {
            Data data = new Data();
            CompoundTag spells = tag.getCompound("spells");
            for (String id : spells.getAllKeys()) {
                CompoundTag s = spells.getCompound(id);
                WeatherKind kind = WeatherKind.byId(s.getString("kind"));
                if (kind == null) continue;
                data.spells.put(id, new Spell(kind, s.getLong("until"), s.getInt("wind"), s.getLong("seen")));
            }
            if (tag.contains("fronts", Tag.TAG_LIST)) {
                ListTag list = tag.getList("fronts", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    WeatherFront f = WeatherFront.load(list.getCompound(i));
                    if (!f.expired()) {
                        data.fronts.add(f);
                        data.nextFrontId = Math.max(data.nextFrontId, f.id + 1);
                    }
                }
            }
            if (tag.contains("nextFrontId", Tag.TAG_INT)) {
                data.nextFrontId = Math.max(data.nextFrontId, tag.getInt("nextFrontId"));
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag spells = new CompoundTag();
            this.spells.forEach((id, s) -> {
                CompoundTag t = new CompoundTag();
                t.putString("kind", s.kind.id());
                t.putLong("until", s.until);
                t.putInt("wind", s.wind);
                t.putLong("seen", s.lastSeen);
                spells.put(id, t);
            });
            tag.put("spells", spells);
            ListTag frontList = new ListTag();
            for (WeatherFront f : this.fronts) {
                if (!f.expired()) frontList.add(f.save());
            }
            tag.put("fronts", frontList);
            tag.putInt("nextFrontId", this.nextFrontId);
            return tag;
        }
    }
}
