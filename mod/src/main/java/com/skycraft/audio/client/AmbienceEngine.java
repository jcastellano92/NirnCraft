package com.skycraft.audio.client;

import com.skycraft.audio.AudioConfig;
import com.skycraft.audio.AudioSounds;
import com.skycraft.tamriel.Regions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ambience engine: looping environment beds mixed by where you are, plus random positional calls around you.
 *
 * <p>Twice a second it reads the surroundings (the biome under you and in a ring around you, by vanilla, Forge and
 * Skycraft biome tags and Skycraft biome names; time of day; rain, snow and thunder; altitude; caves and their depth,
 * buildings, water) into a target level for each bed. Beds are streamed looping sounds on the Ambient slider that fade
 * towards their targets; at most {@link #MAX_BEDS} play at once. Indoors and underground the outdoor beds drop and are
 * low-passed by {@link Acoustics}. Calls (birds by day, owls and wolves at night, frogs, gulls, cave drips...) are
 * placed in 3D at random spots around the listener.</p>
 */
public final class AmbienceEngine {
    private static final int MAX_BEDS = 5;
    private static final RandomSource RANDOM = RandomSource.create();

    private static final Map<String, Bed> PLAYING = new HashMap<>();
    private static final Map<String, Float> TARGET = new HashMap<>();
    private static int ticks;
    private static final Habitat HAB = new Habitat();

    private AmbienceEngine() {}

    // ------------------------------------------------------------------ habitat

    /** The surroundings as weights 0..1. */
    static final class Habitat {
        float forest, plains, marsh, jungle, desert, ash, snowy, mountain, coast, ocean, river, glow;
        float night, rain, thunder, altitude;
        boolean surface, cave, nether, end, skyrim, onWater;
        float caveDepth;
    }

    private static final String[] FOREST_WORDS = {"forest", "taiga", "pine", "birch", "grove", "wood", "graht", "autumn", "aspen",
            "falkreath", "weald", "glenumbra", "stormhaven", "rivenspire", "greenshade", "malabal", "reaper"};
    private static final String[] MARSH_WORDS = {"marsh", "swamp", "mire", "bog", "fen", "bitter_coast", "hjaalmarch", "shadowfen",
            "murkmire", "blackwood", "mangrove", "hist"};
    private static final String[] JUNGLE_WORDS = {"jungle", "rainforest", "tropical", "pellitine", "tsaesci", "pyandone", "valenwood"};
    private static final String[] DESERT_WORDS = {"desert", "dune", "badlands", "alikr", "anequina", "mesa", "canyon", "savanna",
            "steppe", "sand", "yokuda", "craglorn"};
    private static final String[] ASH_WORDS = {"ash", "volcan", "red_mountain", "caldera", "molag", "lava", "basalt"};
    private static final String[] SNOW_WORDS = {"snow", "glacier", "ice", "frozen", "frost", "pale", "winterhold", "atmora", "kamal",
            "tundra_snow", "peak", "throat"};
    private static final String[] GLOW_WORDS = {"glow", "blackreach", "falmer", "lush", "crystal", "mushroom", "dwemer"};

    private static boolean has(String id, String[] words) {
        for (String w : words) if (id.contains(w)) return true;
        return false;
    }

    private static String id(Holder<Biome> b) {
        return b.unwrapKey().map(k -> k.location().getPath()).orElse("").toLowerCase(Locale.ROOT);
    }

    private static void sense(Minecraft mc, Vec3 eye) {
        Level level = mc.level;
        Habitat h = HAB;
        BlockPos at = BlockPos.containing(eye);
        Holder<Biome> here = level.getBiome(at);
        String id = id(here);
        String dim = level.dimension().location().getPath();
        h.nether = level.dimension() == Level.NETHER || dim.contains("oblivion");
        h.end = level.dimension() == Level.END || dim.contains("sovngarde");

        // ring of eight biome samples 24 blocks out: shores, rivers and forest edges fade in gradually
        float forest = 0, plains = 0, marsh = 0, jungle = 0, desert = 0, ash = 0, snowy = 0, mountain = 0, ocean = 0, river = 0, glow = 0;
        int n = 0;
        for (int i = -1; i < 8; i++) {
            BlockPos p = i < 0 ? at : at.offset((int) (Math.cos(i * Math.PI / 4) * 24), 0, (int) (Math.sin(i * Math.PI / 4) * 24));
            Holder<Biome> b = i < 0 ? here : level.getBiome(p);
            String bid = i < 0 ? id : id(b);
            float w = i < 0 ? 3f : 1f;
            n += (int) w;
            boolean isOcean = b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN) || bid.contains("ocean") || bid.contains("sea_of");
            if (isOcean) ocean += w;
            if (b.is(BiomeTags.IS_RIVER) || bid.contains("river")) river += w;
            if (b.is(Tags.Biomes.IS_SWAMP) || has(bid, MARSH_WORDS)) marsh += w;
            else if (b.is(BiomeTags.IS_JUNGLE) || has(bid, JUNGLE_WORDS)) jungle += w;
            else if (b.is(Tags.Biomes.IS_DESERT) || b.is(BiomeTags.IS_BADLANDS) || b.is(BiomeTags.IS_SAVANNA) || has(bid, DESERT_WORDS)) desert += w;
            else if (has(bid, ASH_WORDS)) ash += w;
            else if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA) || b.is(Tags.Biomes.IS_DENSE) || has(bid, FOREST_WORDS)) forest += w;
            else if (!isOcean) plains += w;
            if (b.is(Tags.Biomes.IS_SNOWY) || b.value().coldEnoughToSnow(p) || has(bid, SNOW_WORDS)) snowy += w;
            if (b.is(BiomeTags.IS_MOUNTAIN) || b.is(Tags.Biomes.IS_PEAK) || b.is(Tags.Biomes.IS_SLOPE)) mountain += w;
            if (b.is(Tags.Biomes.IS_LUSH) || b.is(Tags.Biomes.IS_CAVE) || has(bid, GLOW_WORDS)) glow += w;
        }
        h.forest = forest / n;
        h.plains = plains / n;
        h.marsh = marsh / n;
        h.jungle = jungle / n;
        h.desert = desert / n;
        h.ash = ash / n;
        h.snowy = snowy / n;
        h.mountain = mountain / n;
        h.ocean = ocean / n;
        h.river = river / n;
        h.glow = glow / n;
        // waves: strongest right at the shore (part ocean, part land)
        h.coast = h.ocean > 0.05f && h.ocean < 0.95f ? 1f - Math.abs(h.ocean - 0.5f) * 1.6f : 0f;
        h.onWater = mc.player.isInWater() || mc.player.getVehicle() != null && mc.player.getVehicle().isInWater()
                || level.getFluidState(at.below()).isSource();

        long time = Math.floorMod(level.getDayTime(), 24000L);
        // smooth day/night: dusk 12000..13500, dawn 22500..24000
        float night;
        if (time < 12000) night = 0f;
        else if (time < 13500) night = (time - 12000) / 1500f;
        else if (time < 22500) night = 1f;
        else night = 1f - (time - 22500) / 1500f;
        h.night = night;
        h.rain = level.getRainLevel(1f);
        h.thunder = level.getThunderLevel(1f);
        h.altitude = Mth.clamp((float) (eye.y - 95.0) / 140f, 0f, 1f);
        h.cave = ListenerEnvironment.underground;
        h.caveDepth = Mth.clamp((float) (40.0 - eye.y) / 80f, 0f, 1f);
        h.surface = !h.cave && !h.nether && !h.end;
        try {
            h.skyrim = "skyrim".equals(Regions.at(level, at).province());
        } catch (RuntimeException e) {
            h.skyrim = true;
        }
    }

    /** Target level of every bed for the current habitat. */
    private static void targets() {
        Habitat h = HAB;
        TARGET.clear();
        if (h.nether) {
            TARGET.put("oblivion", 0.9f);
            return;
        }
        if (h.end) {
            TARGET.put("sovngarde", 0.8f);
            return;
        }
        if (h.cave) {
            float glow = Mth.clamp(h.glow * 1.5f, 0f, 1f);
            TARGET.put("cave", 0.6f * (1f - h.caveDepth) * (1f - glow * 0.5f));
            TARGET.put("cave_deep", 0.7f * h.caveDepth * (1f - glow * 0.5f));
            TARGET.put("glow_cave", 0.75f * glow);
            return;
        }
        float day = 1f - h.night;
        float wet = 1f - 0.8f * h.rain;        // rain silences birds and insects
        float open = 1f - h.forest * 0.6f;      // trees break the wind
        float cold = h.snowy;

        TARGET.put("wind_light", Mth.clamp((0.25f + 0.25f * h.rain + 0.3f * h.plains + 0.2f * cold) * open * (1f - h.altitude), 0f, 0.7f));
        TARGET.put("wind_strong", Mth.clamp((h.altitude * 1.1f + h.mountain * 0.35f + h.thunder * 0.4f) * open, 0f, 1f));
        TARGET.put("blizzard", Mth.clamp(cold * h.rain * 1.1f, 0f, 1f));
        TARGET.put("forest_day", h.forest * day * wet * (1f - cold * 0.5f) * 0.9f);
        TARGET.put("forest_night", h.forest * h.night * wet * (1f - cold * 0.7f) * 0.8f);
        TARGET.put("plains_day", h.plains * day * wet * (1f - cold * 0.8f) * 0.7f);
        TARGET.put("plains_night", h.plains * h.night * wet * (1f - cold * 0.9f) * 0.7f);
        TARGET.put("marsh_day", h.marsh * day * (1f - 0.4f * h.rain) * 0.9f);
        TARGET.put("marsh_night", h.marsh * h.night * (1f - 0.3f * h.rain) * 0.9f);
        TARGET.put("jungle_day", h.jungle * day * (1f - 0.4f * h.rain) * 0.9f);
        TARGET.put("jungle_night", h.jungle * h.night * (1f - 0.3f * h.rain) * 0.9f);
        TARGET.put("desert_wind", h.desert * (0.6f + 0.4f * h.rain) * 0.8f);
        TARGET.put("ash_storm", h.ash * (0.45f + 0.55f * h.rain));
        TARGET.put("coast_waves", h.coast * (h.onWater ? 0.5f : 1f) * 0.85f);
        TARGET.put("open_ocean", h.ocean > 0.9f && h.onWater ? 0.8f : h.ocean * 0.3f * (h.onWater ? 1f : 0f));
        TARGET.put("river", Mth.clamp(h.river * 1.4f, 0f, 0.7f));
    }

    // ------------------------------------------------------------------ tick

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            if (!PLAYING.isEmpty()) stopAll(mc);
            return;
        }
        if (!AudioConfig.AMBIENCE.get() && !PLAYING.isEmpty()) stopAll(mc);
        if (mc.isPaused()) return;
        ticks++;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.isInitialized() ? camera.getPosition() : mc.player.getEyePosition();
        if (ticks % 10 == 0) {
            sense(mc, eye);
            if (AudioConfig.AMBIENCE.get()) mixBeds(mc);
        }
        if (ticks % 20 == 5 && AudioConfig.AMBIENT_CALLS.get()) calls(mc, eye);
    }

    private static void mixBeds(Minecraft mc) {
        targets();
        float vol = AudioConfig.AMBIENCE_VOLUME.get().floatValue();
        // indoors the outdoor beds fall away (and are low-passed by Acoustics); underwater they nearly vanish
        float muffle = HAB.cave || HAB.nether || HAB.end ? 0f : ListenerEnvironment.muffle;
        float outdoor = 1f - 0.65f * muffle;
        if (ListenerEnvironment.underwater) outdoor *= 0.3f;
        List<Map.Entry<String, Float>> order = new ArrayList<>(TARGET.entrySet());
        order.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        Map<String, Float> want = new HashMap<>();
        for (int i = 0; i < order.size() && want.size() < MAX_BEDS; i++) {
            float t = order.get(i).getValue();
            if (t > 0.03f) want.put(order.get(i).getKey(), Mth.clamp(t * outdoor * vol, 0f, 1f));
        }
        for (Map.Entry<String, Bed> e : PLAYING.entrySet()) {
            e.getValue().target = want.getOrDefault(e.getKey(), 0f);
        }
        PLAYING.entrySet().removeIf(e -> {
            Bed b = e.getValue();
            boolean gone = b.isStopped() || !mc.getSoundManager().isActive(b) && b.started;
            if (gone) Acoustics.forgetBed(b);
            return gone;
        });
        for (Map.Entry<String, Float> e : want.entrySet()) {
            if (PLAYING.containsKey(e.getKey())) continue;
            Bed bed = new Bed(e.getKey(), AudioSounds.get("ambient.bed." + e.getKey()));
            bed.target = e.getValue();
            PLAYING.put(e.getKey(), bed);
            mc.getSoundManager().play(bed);
            bed.started = true;
        }
    }

    // ------------------------------------------------------------------ calls

    private record Call(String event, float perMinute, float minDist, float maxDist, float up, float volume) {}

    private static void calls(Minecraft mc, Vec3 eye) {
        Habitat h = HAB;
        List<Call> pool = new ArrayList<>();
        float day = 1f - h.night;
        float dry = 1f - 0.85f * h.rain;
        if (h.nether) {
            pool.add(new Call("oblivion_wail", 1.2f, 24, 64, 4, 0.8f));
        } else if (h.end) {
            pool.add(new Call("sovngarde_horn", 0.6f, 60, 120, 20, 1f));
        } else if (h.cave) {
            pool.add(new Call("cave_drip", 9f, 3, 14, 3, 0.7f));
            pool.add(new Call("cave_rumble", 0.5f + h.caveDepth, 20, 48, 0, 0.9f));
            pool.add(new Call("rock_fall", 0.6f, 6, 20, 2, 0.6f));
            if (h.glow > 0.2f) pool.add(new Call("glow_chime", 3f * h.glow, 4, 16, 3, 0.6f));
        } else {
            float out = 1f - ListenerEnvironment.muffle * 0.8f;
            if (out < 0.25f) return;
            pool.add(new Call("bird_song", 6f * day * dry * (h.forest + 0.5f * h.plains) * (1f - h.snowy * 0.7f), 8, 28, 6, 0.7f));
            pool.add(new Call("crow", 1.2f * day * dry * (h.forest + h.plains), 16, 40, 8, 0.7f));
            pool.add(new Call("hawk", 1.2f * day * dry * (h.mountain + h.altitude + h.desert * 0.5f), 30, 70, 30, 0.8f));
            pool.add(new Call("tree_creak", 1.5f * h.forest * (0.4f + h.rain + h.thunder), 6, 18, 4, 0.6f));
            pool.add(new Call("owl", 2f * h.night * dry * h.forest, 12, 32, 8, 0.7f));
            pool.add(new Call("cricket", 4f * h.night * dry * (h.plains + h.forest * 0.5f) * (1f - h.snowy), 4, 14, 0, 0.5f));
            pool.add(new Call("wolf_howl", 0.6f * h.night * (h.forest + h.snowy + h.plains * 0.5f) * (h.skyrim ? 1f : 0.4f), 60, 120, 6, 1f));
            pool.add(new Call("frog", 6f * h.marsh * (0.4f + h.night), 5, 20, 0, 0.7f));
            pool.add(new Call("insect", 3f * day * dry * (h.marsh + h.jungle + h.plains * 0.3f) * (1f - h.snowy), 2, 7, 1, 0.5f));
            pool.add(new Call("loon", 1f * (h.river + h.marsh * 0.6f) * (0.4f + h.night) * (1f - h.desert), 40, 90, 2, 0.8f));
            pool.add(new Call("jungle_bird", 5f * h.jungle * (0.3f + day) * dry, 10, 32, 8, 0.8f));
            pool.add(new Call("gull", 3f * (h.coast + h.ocean * 0.6f) * day * dry, 12, 36, 10, 0.8f));
            pool.add(new Call("wave_crash", 3f * h.coast, 10, 28, 0, 0.8f));
            pool.add(new Call("whale", h.ocean > 0.9f && h.onWater ? 0.4f : 0f, 50, 120, -10, 1f));
            pool.add(new Call("sand_hiss", 2f * h.desert * (0.5f + h.rain), 4, 14, 0, 0.6f));
            pool.add(new Call("wind_gust", 1.5f * (h.mountain + h.altitude + h.snowy * 0.5f + h.desert * 0.5f) * (0.5f + h.rain), 6, 20, 4, 0.7f));
            pool.add(new Call("volcano", 0.7f * h.ash, 80, 160, 0, 1f));
            if (h.skyrim && AudioConfig.DISTANT_DRAGONS.get() && ListenerEnvironment.muffle < 0.3f) {
                pool.add(new Call("distant_dragon", 0.05f, 150, 250, 40, 1f));
            }
        }
        for (Call c : pool) {
            if (c.perMinute <= 0f || RANDOM.nextFloat() >= c.perMinute / 60f) continue;
            play(mc, eye, c);
        }
    }

    private static void play(Minecraft mc, Vec3 eye, Call c) {
        String name = c.event.equals("wolf_howl") ? "entity.wolf.howl" : "ambient." + c.event;
        SoundEvent ev = AudioSounds.get(name);
        double ang = RANDOM.nextDouble() * Math.PI * 2;
        double d = c.minDist + RANDOM.nextDouble() * (c.maxDist - c.minDist);
        double x = eye.x + Math.cos(ang) * d;
        double z = eye.z + Math.sin(ang) * d;
        double y = eye.y + c.up * (0.5 + RANDOM.nextDouble());
        float pitch = 0.92f + RANDOM.nextFloat() * 0.16f;
        // the event's attenuation_distance decides how far it carries; volume above 1 only widens that range
        mc.getSoundManager().play(new SimpleSoundInstance(ev, SoundSource.AMBIENT, c.volume * (0.7f + 0.3f * RANDOM.nextFloat()), pitch,
                SoundInstance.createUnseededRandom(), x, y, z));
    }

    /** Out on open water (for the sailing music): mostly ocean around and the listener on or in the water. */
    public static boolean atSea() {
        return HAB.surface && HAB.ocean > 0.85f && HAB.onWater;
    }

    /** Underground among glowing fungus / crystal (Blackreach-like caverns). */
    public static boolean inGlowCave() {
        return HAB.cave && HAB.glow > 0.3f;
    }

    public static void stopAll(Minecraft mc) {
        for (Bed b : PLAYING.values()) {
            mc.getSoundManager().stop(b);
            Acoustics.forgetBed(b);
        }
        PLAYING.clear();
    }

    /** Names of the beds playing now and their levels, for the debug overlay / logs. */
    public static String debug() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Bed> e : PLAYING.entrySet()) {
            sb.append(e.getKey()).append(' ').append(String.format(Locale.ROOT, "%.2f", e.getValue().level)).append("  ");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ the looping bed

    /** One looping ambience bed: non-positional, fades towards {@link #target}, stops itself after fading out. */
    public static final class Bed extends AbstractTickableSoundInstance {
        final String name;
        float target;
        float level;
        boolean started;
        private int silentTicks;

        Bed(String name, SoundEvent event) {
            super(event, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.name = name;
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.volume = 0.0001f;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            // about four seconds for a full fade
            level += Mth.clamp(target - level, -0.012f, 0.012f);
            volume = Math.max(0.0001f, level);
            if (target <= 0f && level <= 0.002f) {
                if (++silentTicks > 40) stop();
            } else {
                silentTicks = 0;
            }
        }
    }
}
