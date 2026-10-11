package com.skycraft.atmosphere.weather;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Server-authoritative weather per region. Vanilla weather is global, so every region near a player gets its own
 * "spell" of a {@link WeatherKind} rolled from the region's {@link WeatherProfile}; the table is synced to clients
 * ({@link WeatherPackets}), which render local precipitation, fog and sky from it. Regions nobody has visited for a
 * while are dropped. Vanilla's global rain flag follows the weather where the players are (for crops, fire,
 * fishing, lightning), and a {@code /weather} command is adopted into the players' regions instead of fought.
 * Frozen while the {@code doWeatherCycle} game rule is off.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class RegionalWeather {
    /** Sample offsets around each player, so the client can blend weather across a nearby border. */
    private static final int[][] SAMPLES = {{0, 0}, {96, 0}, {-96, 0}, {0, 96}, {0, -96}};
    /** Drop a region's spell this long after the last player left it. */
    private static final long FORGET_TICKS = 6000;
    private static final long VANILLA_REFRESH_TICKS = 6000;

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
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return WeatherKind.CLEAR;
        return kindOf(level.getServer(), Regions.at(level, pos).id());
    }

    /**
     * The wind of the region at {@code pos}: the direction it blows towards in degrees (yaw convention, as the client
     * draws it), or -1 if the region has no weather yet. Used by sailing.
     */
    public static int windAt(ServerLevel level, BlockPos pos) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return -1;
        Spell s = Data.get(level.getServer()).spells.get(Regions.at(level, pos).id());
        return s == null ? -1 : s.wind;
    }

    /** Remaining ticks of the region's current spell (0 if none). */
    public static long remaining(MinecraftServer server, String region) {
        Spell s = Data.get(server).spells.get(region);
        return s == null ? 0 : Math.max(0, s.until - server.overworld().getGameTime());
    }

    /** Forces a region's weather (commands, quests). {@code ticks <= 0} uses a profile-length spell. */
    public static void set(MinecraftServer server, Region region, WeatherKind kind, int ticks) {
        ServerLevel ow = server.overworld();
        Data data = Data.get(server);
        long now = ow.getGameTime();
        WeatherProfile profile = WeatherProfile.of(region);
        int len = ticks > 0 ? ticks : profile.duration(ow.random);
        Spell s = data.spells.get(region.id());
        if (s == null) {
            s = new Spell(kind, now + len, ow.random.nextInt(360), now);
            data.spells.put(region.id(), s);
        } else {
            s.kind = kind;
            s.until = now + len;
        }
        data.setDirty();
        SkyNetwork.sendToAll(new WeatherPackets.Sync(false, List.of(entry(region.id(), s))));
        applied = false; // re-evaluate vanilla's flag on the next pass without treating it as an external change
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

        // make sure every region around a player has weather
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (ServerPlayer p : ow.players()) {
            for (int[] off : SAMPLES) {
                probe.set(p.getBlockX() + off[0], p.getBlockY(), p.getBlockZ() + off[1]);
                Region region = Regions.at(ow, probe);
                WeatherProfile profile = WeatherProfile.of(region);
                if (profile == WeatherProfile.NONE) continue;
                Spell s = data.spells.get(region.id());
                if (s == null) {
                    s = new Spell(profile.roll(ow.random, null), now + profile.duration(ow.random), ow.random.nextInt(360), now);
                    data.spells.put(region.id(), s);
                    changed.add(entry(region.id(), s));
                    data.setDirty();
                }
                s.lastSeen = now;
            }
        }

        // expire spells: roll the next one where players are, forget abandoned regions
        if (cycle) {
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
                    s.kind = profile.roll(ow.random, s.kind);
                    s.until = now + Math.max(600, (long) (profile.duration(ow.random) * scale));
                    // wind veers a little between spells
                    s.wind = Math.floorMod(s.wind + ow.random.nextInt(91) - 45, 360);
                    changed.add(entry(e.getKey(), s));
                }
                data.setDirty();
            }
        }
        if (!changed.isEmpty()) SkyNetwork.sendToAll(new WeatherPackets.Sync(false, changed));

        if (ticks % 100 == 0 && cycle && AtmosphereConfig.DRIVE_VANILLA_WEATHER.get()) driveVanilla(ow, data, now);
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
                s.until = now + profile.duration(ow.random);
                changed.add(entry(region.id(), s));
            }
            data.setDirty();
            if (!changed.isEmpty()) SkyNetwork.sendToAll(new WeatherPackets.Sync(false, changed));
            lastRaining = rain;
            lastThundering = thunder;
            return;
        }

        int wet = 0;
        int storm = 0;
        for (ServerPlayer p : players) {
            Spell s = data.spells.get(Regions.at(ow, p.blockPosition()).id());
            if (s == null) continue;
            if (s.kind.wet()) wet++;
            if (s.kind.thunder > 0) storm++;
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
        // an empty table tells the client to leave vanilla weather alone
        if (AtmosphereConfig.REGIONAL_WEATHER.get()) Data.get(server).spells.forEach((id, s) -> list.add(entry(id, s)));
        return new WeatherPackets.Sync(true, list);
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

    /** Region id -> spell. Stored with the overworld ({@code data/skycraft_weather.dat}). */
    static final class Data extends SavedData {
        private static final String NAME = "skycraft_weather";
        final Map<String, Spell> spells = new HashMap<>();

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
            return tag;
        }
    }
}
