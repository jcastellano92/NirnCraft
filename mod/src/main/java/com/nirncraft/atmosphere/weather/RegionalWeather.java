package com.nirncraft.atmosphere.weather;

import com.nirncraft.NirnCraft;
import com.nirncraft.atmosphere.AtmosphereConfig;
import com.nirncraft.atmosphere.AtmosphereMath;
import com.nirncraft.atmosphere.WeatherFront;
import com.nirncraft.atmosphere.WeatherPhysics;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
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
     * Deterministically generates a synoptic set of moving {@link WeatherFront}s across Tamriel's hydrological and
     * topographic cyclogenesis basins.
     */
    public static List<WeatherFront> seedInitialFronts(long seed) {
        Random rng = new Random(seed ^ 0x5F3759DF9E3779B9L);
        List<WeatherFront> list = new ArrayList<>(TARGET_SYNOPTIC_FRONTS);
        for (int i = 0; i < TARGET_SYNOPTIC_FRONTS; i++) {
            list.add(WeatherPhysics.spawnPhysicalFront(i + 1, i, rng));
        }
        return list;
    }

    /** Adds or replaces an active {@link WeatherFront} on the server and syncs it to clients. */
    /** Removes every weather front (tests: a scene's weather never inherits a front an earlier scene added). */
    public static int clearFronts(MinecraftServer server) {
        if (server == null) return 0;
        Data data = Data.get(server);
        int n = data.fronts.size();
        data.fronts.clear();
        data.setDirty();
        SkyNetwork.sendToAll(new WeatherPackets.Sync(false, List.of(), data.fronts));
        return n;
    }

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
        int len = ticks > 0 ? ticks : profile.duration(ow.getRandom(), kind);
        Spell s = data.spells.get(region.id());
        if (s == null) {
            s = new Spell(kind, now + len, ow.getRandom().nextInt(360), now);
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
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || ++ticks % 20 != 0) return;
        if (!AtmosphereConfig.REGIONAL_WEATHER.get()) return;
        MinecraftServer server = event.getServer();
        ServerLevel ow = server.overworld();
        if (ow == null) return;
        Data data = Data.get(server);
        long now = ow.getGameTime();
        boolean cycle = ow.getGameRules().get(GameRules.ADVANCE_WEATHER);
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
        float mist = WeatherProfile.mistHour(ow.getOverworldClockTime());
        // DH can show provinces far beyond loaded chunks. Keep their small regional weather
        // records alive too; this reads map data and never loads/generates distant terrain.
        if (far || ticks == 20) {
            for (Region region : Regions.all()) {
                var capital = region.capital() == null ? java.util.Optional.<com.nirncraft.tamriel.Place>empty() : Places.byId(region.capital());
                probe.set(capital.map(com.nirncraft.tamriel.Place::x).orElse(0), 80,
                        capital.map(com.nirncraft.tamriel.Place::z).orElse(0));
                touch(ow, data, region, probe, now, mist, changed);
            }
        }
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

        // advect and physically evolve synoptic weather fronts over water & mountain relief; expire/roll regional spells
        if (cycle) {
            for (Iterator<WeatherFront> fit = data.fronts.iterator(); fit.hasNext(); ) {
                WeatherFront f = fit.next();
                WeatherPhysics.advectAndEvolveFront(f, 20);
                if (f.expired()) {
                    fit.remove();
                    frontsChanged = true;
                }
            }
            if (data.fronts.size() < TARGET_SYNOPTIC_FRONTS) {
                Random r = new Random(ow.getSeed() + now * 31L + data.nextFrontId);
                while (data.fronts.size() < TARGET_SYNOPTIC_FRONTS) {
                    int id = data.nextFrontId++;
                    data.fronts.add(WeatherPhysics.spawnPhysicalFront(id, id, r));
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
                    s.kind = profile.roll(ow.getRandom(), s.kind, mist);
                    s.until = now + Math.max(600, (long) (profile.duration(ow.getRandom(), s.kind) * scale));
                    // wind veers a little between spells
                    s.wind = Math.floorMod(s.wind + ow.getRandom().nextInt(91) - 45, 360);
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
        touch(ow, data, region, pos, now, mist, changed);
    }

    private static void touch(ServerLevel ow, Data data, Region region, BlockPos pos, long now, float mist, List<WeatherPackets.Entry> changed) {
        WeatherProfile profile = WeatherProfile.of(region);
        if (profile == WeatherProfile.NONE) return;
        Spell s = data.spells.get(region.id());
        if (s == null) {
            WeatherKind kind = profile.roll(ow.getRandom(), null, mist);
            float[] wv = new float[2];
            WeatherPhysics.prevailingWind(pos.getX(), pos.getZ(), -1f, false, wv);
            int baseDeg = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(-wv[0], wv[1]))), 360);
            int windDeg = Math.floorMod(baseDeg + ow.getRandom().nextInt(51) - 25, 360);
            s = new Spell(kind, now + profile.duration(ow.getRandom(), kind), windDeg, now);
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
                    s = new Spell(kind, 0, ow.getRandom().nextInt(360), now);
                    data.spells.put(region.id(), s);
                }
                s.kind = kind;
                s.until = now + profile.duration(ow.getRandom(), kind);
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
            ow.getServer().setWeatherParameters(raining ? 0 : 24000, raining ? 24000 : 0, raining, thundering);
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
        if (event.getEntity() instanceof ServerPlayer sp) SkyNetwork.sendToPlayer(sp, full(sp.level().getServer()));
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) SkyNetwork.sendToPlayer(sp, full(sp.level().getServer()));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) SkyNetwork.sendToPlayer(sp, full(sp.level().getServer()));
    }

    /** Forget the vanilla bookkeeping between worlds (singleplayer can open several in one session). */
    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        applied = false;
        ticks = 0;
    }

    // ------------------------------------------------------------------ saved data

    /** Region id -> spell, plus active moving fronts. Stored with the overworld ({@code data/nirncraft_weather.dat}). */
    static final class Data extends SavedData {
        private static final String NAME = "nirncraft_weather";
        final Map<String, Spell> spells = new HashMap<>();
        final List<WeatherFront> fronts = new ArrayList<>();
        int nextFrontId = 1;

        static Data get(MinecraftServer server) {
            if (server == null) server = ServerLifecycleHooks.getCurrentServer();
            return com.nirncraft.platform.SavedDataTypes.get(server.overworld(), NAME, Data::load, Data::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
        }

        static Data load(CompoundTag tag) {
            Data data = new Data();
            CompoundTag spells = tag.getCompoundOrEmpty("spells");
            for (String id : spells.keySet()) {
                CompoundTag s = spells.getCompoundOrEmpty(id);
                WeatherKind kind = WeatherKind.byId(s.getStringOr("kind",""));
                if (kind == null) continue;
                data.spells.put(id, new Spell(kind, s.getLongOr("until",0L), s.getIntOr("wind",0), s.getLongOr("seen",0L)));
            }
            if (com.nirncraft.platform.NbtData.hasType(tag, "fronts", Tag.TAG_LIST)) {
                ListTag list = tag.getListOrEmpty("fronts");
                for (int i = 0; i < list.size(); i++) {
                    WeatherFront f = WeatherFront.load(list.getCompoundOrEmpty(i));
                    if (!f.expired()) {
                        data.fronts.add(f);
                        data.nextFrontId = Math.max(data.nextFrontId, f.id + 1);
                    }
                }
            }
            if (com.nirncraft.platform.NbtData.hasType(tag, "nextFrontId", Tag.TAG_INT)) {
                data.nextFrontId = Math.max(data.nextFrontId, tag.getIntOr("nextFrontId",0));
            }
            return data;
        }

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
