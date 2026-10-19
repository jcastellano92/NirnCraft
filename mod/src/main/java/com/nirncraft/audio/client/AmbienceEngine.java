package com.nirncraft.audio.client;

import com.nirncraft.audio.AudioConfig;
import com.nirncraft.audio.AudioSounds;
import com.nirncraft.tamriel.Regions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ambience engine: looping environment beds mixed by where you are, plus random positional calls around you.
 *
 * <p>Twice a second it reads the surroundings (the biome under you and in a ring around you, as the soundscape families
 * of {@link Soundscapes}: every NirnCraft biome is mapped, others go by their tags; the kind of cave biome you are in;
 * time of day; rain, snow and thunder; altitude; caves and their depth, buildings, water; the province) into a target
 * level for each bed. Beds are streamed looping sounds on the Ambient slider that fade
 * towards their targets; at most {@link #MAX_BEDS} play at once. Indoors and underground the outdoor beds drop and are
 * low-passed by {@link Acoustics}. Calls (birds by day, owls and wolves at night, frogs, gulls, cave drips...) are
 * placed in 3D at random spots around the listener.</p>
 */
public final class AmbienceEngine {
    private static final int MAX_BEDS = 3;
    public static final Set<String> SILENCED_BEDS = Set.of();
    public static final Set<String> SILENCED_CALLS = Set.of();
    /**
     * Foliage and grass beds carry warm low-passed breeze and reed/leaf chorus modulation: their level is
     * balanced so they blend naturally with regional exploration music.
     */
    private static final Map<String, Float> BED_CAP = Map.ofEntries(
            Map.entry("forest_day", 0.45f),
            Map.entry("forest_night", 0.22f),
            Map.entry("plains_day", 0.4f),
            Map.entry("plains_night", 0.20f),
            Map.entry("wind_light", 0.4f),
            Map.entry("savanna_day", 0.45f),
            Map.entry("jungle_day", 0.45f),
            Map.entry("jungle_night", 0.28f),
            Map.entry("marsh_day", 0.45f),
            Map.entry("marsh_night", 0.26f),
            Map.entry("swamp_night", 0.26f)
    );
    /** Game ticks since the last positional call started, and calls started in the last few seconds. */
    private static int lastCallTick = -100;
    private static final int CALL_COOLDOWN_TICKS = 30;
    private static final RandomSource RANDOM = RandomSource.create();

    private static final Map<String, Bed> PLAYING = new HashMap<>();
    private static final Map<String, Float> TARGET = new HashMap<>();
    private static int ticks;
    private static float movementDuck = 1.0f;
    private static final Habitat HAB = new Habitat();

    private AmbienceEngine() {}

    // ------------------------------------------------------------------ habitat

    /** The surroundings as weights 0..1. */
    static final class Habitat {
        float forest, pine, plains, marsh, swamp, jungle, desert, savanna, ash, volcanic, snowy, glacier, mountain, mushroom,
                crystal, hotSpring, coast, ocean, river;
        float night, rain, thunder, wind, snow, altitude;
        boolean surface, cave, nether, end, skyrim, morrowind, onWater;
        float caveDepth;
        Soundscapes.Cave caveKind = Soundscapes.Cave.NONE;
    }

    private static void sense(Minecraft mc, Vec3 eye) {
        Level level = mc.level;
        Habitat h = HAB;
        BlockPos at = BlockPos.containing(eye);
        Holder<Biome> here = level.getBiome(at);
        String id = Soundscapes.id(here);
        String dim = level.dimension().identifier().getPath();
        // the realms: fire and wails in the Deadlands, Coldharbour and the Soul Cairn; Sovngarde's horns
        h.nether = level.dimension() == Level.NETHER || dim.contains("oblivion") || dim.contains("coldharbour") || dim.contains("soul_cairn");
        h.end = level.dimension() == Level.END || dim.contains("sovngarde");

        // the biome here (weight 3) and a ring of eight samples 24 blocks out: shores, rivers and forest edges fade in
        // gradually. Every NirnCraft biome has its soundscape families in Soundscapes; others are classified by tags.
        float[] sum = new float[Soundscapes.N];
        float snowyAround = 0;
        int n = 0;
        for (int i = -1; i < 8; i++) {
            BlockPos p = i < 0 ? at : at.offset((int) (Math.cos(i * Math.PI / 4) * 24), 0, (int) (Math.sin(i * Math.PI / 4) * 24));
            Holder<Biome> b = i < 0 ? here : level.getBiome(p);
            String bid = i < 0 ? id : Soundscapes.id(b);
            // cave biomes are 3D: a ring sample that lands in rock below the surface says nothing about the open air
            if (!h.nether && !h.end && Soundscapes.cave(bid) != Soundscapes.Cave.NONE) continue;
            float w = i < 0 ? 3f : 1f;
            n += (int) w;
            float[] f = Soundscapes.of(b, bid);
            for (int k = 0; k < sum.length; k++) sum[k] += f[k] * w;
            if (b.value().coldEnoughToSnow(p,mc.level.getSeaLevel())) snowyAround += w;
        }
        float inv = n == 0 ? 0f : 1f / n;
        h.forest = sum[Soundscapes.F.FOREST.ordinal()] * inv;
        h.pine = sum[Soundscapes.F.PINE.ordinal()] * inv;
        h.plains = sum[Soundscapes.F.PLAINS.ordinal()] * inv;
        h.marsh = sum[Soundscapes.F.MARSH.ordinal()] * inv;
        h.swamp = sum[Soundscapes.F.SWAMP.ordinal()] * inv;
        h.jungle = sum[Soundscapes.F.JUNGLE.ordinal()] * inv;
        h.desert = sum[Soundscapes.F.DESERT.ordinal()] * inv;
        h.savanna = sum[Soundscapes.F.SAVANNA.ordinal()] * inv;
        h.ash = sum[Soundscapes.F.ASH.ordinal()] * inv;
        h.volcanic = sum[Soundscapes.F.VOLCANIC.ordinal()] * inv;
        h.snowy = Math.max(sum[Soundscapes.F.SNOWY.ordinal()], snowyAround) * inv;
        h.glacier = sum[Soundscapes.F.GLACIER.ordinal()] * inv;
        h.mountain = sum[Soundscapes.F.MOUNTAIN.ordinal()] * inv;
        h.mushroom = sum[Soundscapes.F.MUSHROOM.ordinal()] * inv;
        h.crystal = sum[Soundscapes.F.CRYSTAL.ordinal()] * inv;
        h.hotSpring = sum[Soundscapes.F.HOT_SPRING.ordinal()] * inv;
        h.ocean = sum[Soundscapes.F.OCEAN.ordinal()] * inv;
        h.river = sum[Soundscapes.F.RIVER.ordinal()] * inv;
        float beach = sum[Soundscapes.F.BEACH.ordinal()] * inv;
        // waves: strongest right at the shore (part ocean, part land), and on beach biomes
        float edge = h.ocean > 0.05f && h.ocean < 0.95f ? 1f - Math.abs(h.ocean - 0.5f) * 1.6f : 0f;
        h.coast = Mth.clamp(Math.max(edge, beach * (0.5f + h.ocean)), 0f, 1f);
        h.onWater = mc.player.isInWater() || mc.player.getVehicle() != null && mc.player.getVehicle().isInWater()
                || level.getFluidState(at.below()).isSource();

        long time = Math.floorMod(level.getOverworldClockTime(), 24000L);
        // smooth day/night: dusk 12000..13500, dawn 22500..24000
        float night;
        if (time < 12000) night = 0f;
        else if (time < 13500) night = (time - 12000) / 1500f;
        else if (time < 22500) night = 1f;
        else night = 1f - (time - 22500) / 1500f;
        h.night = night;
        boolean cwActive = level.dimension() == Level.OVERWORLD && com.nirncraft.atmosphere.client.ClientWeather.active();
        h.snow = cwActive ? com.nirncraft.atmosphere.client.ClientWeather.snow() : 0f;
        h.rain = Math.max(level.getRainLevel(1f), h.snow);
        h.thunder = level.getThunderLevel(1f);
        h.wind = cwActive ? com.nirncraft.atmosphere.client.ClientWeather.wind() : Mth.clamp(0.18f + 0.45f * h.rain + 0.35f * h.thunder, 0f, 1f);
        h.altitude = Mth.clamp((float) (eye.y - 95.0) / 140f, 0f, 1f);
        h.caveKind = h.nether || h.end ? Soundscapes.Cave.NONE : Soundscapes.cave(id);
        h.cave = ListenerEnvironment.underground
                || h.caveKind != Soundscapes.Cave.NONE && level.getBrightness(LightLayer.SKY, at) < 8;
        h.caveDepth = Mth.clamp((float) (40.0 - eye.y) / 80f, 0f, 1f);
        h.surface = !h.cave && !h.nether && !h.end;
        try {
            String province = Regions.at(level, at).province();
            h.skyrim = "skyrim".equals(province);
            h.morrowind = "morrowind".equals(province);
        } catch (RuntimeException e) {
            h.skyrim = true;
            h.morrowind = false;
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
            switch (h.caveKind) {
                case DWEMER -> {
                    TARGET.put("dwemer_machinery", 0.85f);
                    TARGET.put("cave", 0.35f);
                }
                case FALMER -> {
                    TARGET.put("glow_cave", 0.75f);
                    TARGET.put("cave_deep", 0.35f);
                }
                case ICE -> {
                    TARGET.put("ice_cave", 0.85f);
                    TARGET.put("cave", 0.2f);
                }
                case LAVA -> {
                    TARGET.put("lava_cave", 0.85f);
                    TARGET.put("volcanic_rumble", 0.45f);
                }
                case GROTTO -> {
                    TARGET.put("mushroom_grotto", 0.8f);
                    TARGET.put("glow_cave", 0.2f);
                }
                case LUSH -> {
                    TARGET.put("mushroom_grotto", 0.6f);
                    TARGET.put("glow_cave", 0.35f);
                }
                case DEEP_DARK -> TARGET.put("cave_deep", 0.85f);
                default -> {
                    TARGET.put("cave", 0.6f * (1f - h.caveDepth));
                    TARGET.put("cave_deep", 0.7f * h.caveDepth);
                }
            }
            return;
        }
        float day = 1f - h.night;
        float wet = 1f - 0.8f * h.rain;        // rain silences birds and insects
        float open = 1f - h.forest * 0.45f - h.jungle * 0.4f;   // trees partially shelter the wind
        float cold = Math.max(h.snowy, h.snow > 0.1f ? 0.8f : 0f);
        float warmSwamp = h.swamp;
        float storm = Math.max(Math.max(h.rain, h.thunder), Math.max(0f, h.wind - 0.5f));
        float nightInsectAtten = (1f - cold * 0.92f) * (1f - h.altitude * 0.75f) * (1f - h.mountain * 0.65f) * (1f - storm * 0.85f);

        // Warm low-passed breeze & tonal biome beds (night insect beds attenuated by altitude, cold, mountain & storm)
        TARGET.put("forest_day", h.forest * day * wet * (1f - cold * 0.6f) * 0.7f);
        TARGET.put("forest_night", h.forest * h.night * wet * nightInsectAtten * 0.20f);
        TARGET.put("plains_day", h.plains * day * wet * (1f - cold * 0.7f) * 0.6f);
        TARGET.put("plains_night", h.plains * h.night * wet * nightInsectAtten * 0.18f);
        TARGET.put("savanna_day", h.savanna * day * wet * 0.65f);
        TARGET.put("marsh_day", h.marsh * day * wet * 0.65f);
        TARGET.put("marsh_night", h.marsh * h.night * wet * (1f - cold * 0.85f) * (1f - h.altitude * 0.65f) * 0.24f);
        TARGET.put("swamp_night", warmSwamp * h.night * wet * (1f - cold * 0.85f) * (1f - h.altitude * 0.65f) * 0.26f);
        TARGET.put("jungle_day", h.jungle * day * (1f - 0.5f * h.rain) * 0.7f);
        TARGET.put("jungle_night", h.jungle * h.night * (1f - 0.5f * h.rain) * (1f - h.altitude * 0.5f) * 0.28f);

        // Prevent double-stacking of forest_night and plains_night at biome borders
        float fn = TARGET.getOrDefault("forest_night", 0f);
        float pn = TARGET.getOrDefault("plains_night", 0f);
        if (fn > 0f && pn > 0f && fn + pn > 0.22f) {
            float scale = 0.22f / (fn + pn);
            TARGET.put("forest_night", fn * scale);
            TARGET.put("plains_night", pn * scale);
        }

        float windBreeze = Mth.clamp((0.20f + 0.55f * h.wind + 0.20f * h.plains + 0.15f * cold) * open * (1f - 0.5f * h.altitude), 0f, 0.75f);
        float windGale = Mth.clamp((Math.max(0f, h.wind - 0.42f) * 1.45f + h.altitude * 0.85f + h.mountain * 0.30f + h.glacier * 0.25f + h.thunder * 0.35f) * open, 0f, 1f);
        TARGET.put("wind_light", windBreeze);
        TARGET.put("wind_strong", windGale);
        TARGET.put("blizzard", Mth.clamp(cold * Math.max(h.rain, h.snow) * (0.65f + 0.65f * h.wind), 0f, 1f));
        TARGET.put("desert_wind", h.desert * (0.45f + 0.55f * h.wind) * 0.85f);
        TARGET.put("ash_storm", h.ash * (0.40f + 0.60f * Math.max(h.rain, h.wind)));
        TARGET.put("volcanic_rumble", h.volcanic * 0.7f);
        TARGET.put("hot_springs", h.hotSpring * 0.8f);
        TARGET.put("crystal_shimmer", h.crystal * 0.75f);
        TARGET.put("mushroom_grotto", h.mushroom * 0.4f);
        TARGET.put("ice_cave", h.glacier * 0.35f);
        TARGET.put("coast_waves", h.coast * (h.onWater ? 0.5f : 1f) * (0.75f + 0.25f * h.wind));
        TARGET.put("open_ocean", h.ocean > 0.9f && h.onWater ? 0.8f : h.ocean * 0.3f * (h.onWater ? 1f : 0f));
        TARGET.put("river", Mth.clamp(h.river * 1.4f, 0f, 0.7f));
        SILENCED_BEDS.forEach(TARGET::remove);
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
        boolean movingFast = mc.player.isSprinting() || mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.022;
        float targetDuck = movingFast ? 0.55f : 1.0f;
        movementDuck += (targetDuck - movementDuck) * 0.08f;

        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 eye = camera.isInitialized() ? camera.position() : mc.player.getEyePosition();
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
            if (t > 0.02f) want.put(order.get(i).getKey(), Math.min(BED_CAP.getOrDefault(order.get(i).getKey(), 1f),
                    Mth.clamp(t * outdoor * vol, 0f, 1f)));
        }
        // Ensure combined forest_night + plains_night never exceeds a single night bed (<= 0.22f)
        if (want.containsKey("forest_night") && want.containsKey("plains_night")) {
            float fn = want.get("forest_night");
            float pn = want.get("plains_night");
            if (fn + pn > 0.22f) {
                float scale = 0.22f / (fn + pn);
                want.put("forest_night", fn * scale);
                want.put("plains_night", pn * scale);
            }
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
            Soundscapes.Cave k = h.caveKind;
            pool.add(new Call("cave_drip", k == Soundscapes.Cave.LAVA ? 1f : 9f, 3, 14, 3, 0.7f));
            pool.add(new Call("cave_rumble", 0.5f + h.caveDepth + (k == Soundscapes.Cave.LAVA ? 1f : 0f), 20, 48, 0, 0.9f));
            pool.add(new Call("rock_fall", 0.6f, 6, 20, 2, 0.6f));
            boolean glow = k == Soundscapes.Cave.FALMER || k == Soundscapes.Cave.GROTTO || k == Soundscapes.Cave.LUSH;
            if (glow) pool.add(new Call("glow_chime", 3f, 4, 16, 3, 0.6f));
            if (k == Soundscapes.Cave.DWEMER) pool.add(new Call("machinery", 3f, 12, 40, 2, 0.9f));
            if (k == Soundscapes.Cave.FALMER) pool.add(new Call("distant_screech", 0.8f, 30, 70, 0, 1f));
            if (k == Soundscapes.Cave.ICE) pool.add(new Call("ice_creak", 3f, 8, 30, 3, 0.8f));
            if (k == Soundscapes.Cave.LAVA) pool.add(new Call("lava_bubble", 5f, 4, 16, -2, 0.8f));
        } else {
            float out = 1f - ListenerEnvironment.muffle * 0.8f;
            if (out < 0.25f) return;
            float wetland = h.marsh + h.swamp;
            float altAtten = (1f - h.altitude * 0.75f) * (1f - h.mountain * 0.65f);
            pool.add(new Call("bird_song", 6f * day * dry * (h.forest + 0.5f * h.plains + 0.3f * h.savanna) * (1f - h.snowy * 0.7f), 8, 28, 6, 0.7f));
            pool.add(new Call("crow", 1.2f * day * dry * (h.forest + h.plains), 16, 40, 8, 0.7f));
            pool.add(new Call("hawk", 1.2f * day * dry * (h.mountain + h.altitude + h.desert * 0.5f + h.savanna * 0.5f), 30, 70, 30, 0.8f));
            pool.add(new Call("tree_creak", 1.8f * (h.forest + h.jungle * 0.5f) * (0.3f + 0.8f * h.wind + 0.5f * h.rain + 0.5f * h.thunder), 6, 18, 4, 0.65f));
            pool.add(new Call("owl", 2f * h.night * dry * h.forest, 12, 32, 8, 0.7f));
            pool.add(new Call("wolf_howl", 0.6f * h.night * (h.forest + h.snowy + h.plains * 0.5f) * (h.skyrim ? 1f : 0.4f), 60, 120, 6, 1f));
            pool.add(new Call("frog", 6f * wetland * (0.4f + h.night), 5, 20, 0, 0.7f));
            pool.add(new Call("cricket", 1.1f * h.night * dry * (h.forest + h.plains + 0.5f * wetland) * (1f - h.snowy * 0.95f) * altAtten, 10, 24, 0, 0.16f));
            pool.add(new Call("insect", 0.9f * dry * (0.6f * day + 0.4f * h.night) * (h.marsh + h.swamp + 0.5f * h.jungle) * altAtten, 9, 22, 1, 0.14f));
            pool.add(new Call("cicada", 1.8f * day * dry * (h.savanna + 0.7f * h.jungle + 0.3f * h.forest * (1f - h.snowy)) * altAtten, 10, 26, 5, 0.24f));
            pool.add(new Call("loon", 1f * (h.river + h.marsh * 0.6f) * (0.4f + h.night) * (1f - h.desert), 40, 90, 2, 0.8f));
            pool.add(new Call("jungle_bird", 5f * h.jungle * (0.3f + day) * dry, 10, 32, 8, 0.8f));
            pool.add(new Call("gull", 3f * (h.coast + h.ocean * 0.6f) * day * dry, 12, 36, 10, 0.8f));
            pool.add(new Call("wave_crash", 3f * h.coast, 10, 28, 0, 0.8f));
            pool.add(new Call("whale", h.ocean > 0.9f && h.onWater ? 0.4f : 0f, 50, 120, -10, 1f));
            pool.add(new Call("sand_hiss", 2f * h.desert * (0.4f + 0.6f * h.wind), 4, 14, 0, 0.6f));
            pool.add(new Call("wind_gust", 2.0f * (0.35f * Math.max(0f, h.wind - 0.32f) + h.mountain + h.altitude + h.snowy * 0.5f + h.desert * 0.5f + h.ash * 0.5f) * (0.4f + 0.8f * h.wind + 0.4f * h.rain), 6, 20, 4, 0.75f));
            pool.add(new Call("volcano", 0.7f * h.ash + 1.2f * h.volcanic, 80, 160, 0, 1f));
            pool.add(new Call("lava_bubble", 1.5f * h.volcanic + 2f * h.hotSpring, 6, 20, -1, 0.6f));
            pool.add(new Call("ice_creak", 1.2f * h.glacier, 20, 60, -2, 0.9f));
            pool.add(new Call("glow_chime", 2f * h.crystal + 0.5f * h.mushroom, 6, 20, 3, 0.6f));
            // Vvardenfell's skies: cliff racers screeching (ever-present, alas) and netch drifting far off
            if (h.morrowind) {
                pool.add(new Call("@entity.cliff_racer.ambient", 0.8f * day * dry * (h.ash + h.plains * 0.5f + h.savanna * 0.5f), 40, 90, 25, 1f));
                pool.add(new Call("@entity.netch.ambient", 0.4f * (h.ash * 0.5f + h.plains + h.savanna), 50, 110, 10, 1f));
            }
            if (h.skyrim && AudioConfig.DISTANT_DRAGONS.get() && ListenerEnvironment.muffle < 0.3f) {
                pool.add(new Call("distant_dragon", 0.05f, 150, 250, 40, 1f));
            }
        }
        for (Call c : pool) {
            if (SILENCED_CALLS.contains(c.event)) continue;
            // master cooldown: never more than one call every 1.5 s, however busy the habitat
            if (ticks - lastCallTick < CALL_COOLDOWN_TICKS) break;
            if (c.perMinute <= 0f || RANDOM.nextFloat() >= c.perMinute / 60f) continue;
            lastCallTick = ticks;
            play(mc, eye, c);
        }
    }

    private static void play(Minecraft mc, Vec3 eye, Call c) {
        String name = c.event.equals("wolf_howl") ? "entity.wolf.howl" : c.event.startsWith("@") ? c.event.substring(1) : "ambient." + c.event;
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

    /** Underground among glowing fungus / crystal (Blackreach-like caverns: Falmer glow caves, mushroom grottos, lush caves). */
    public static boolean inGlowCave() {
        Soundscapes.Cave k = HAB.caveKind;
        return HAB.cave && (k == Soundscapes.Cave.FALMER || k == Soundscapes.Cave.GROTTO || k == Soundscapes.Cave.LUSH);
    }

    /** Underground in the Dwemer caverns (for the Dwemer dungeon music). */
    public static boolean inDwemerCaverns() {
        return HAB.cave && HAB.caveKind == Soundscapes.Cave.DWEMER;
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

    private static boolean isNightInsectBed(String name) {
        return "forest_night".equals(name) || "plains_night".equals(name)
                || "marsh_night".equals(name) || "swamp_night".equals(name)
                || "jungle_night".equals(name);
    }

    /** One looping ambience bed: non-positional, fades towards {@link #target}, stops itself after fading out. */
    public static final class Bed extends AbstractTickableSoundInstance {
        final String name;
        final boolean nightInsects;
        float target;
        float level;
        boolean started;
        private int silentTicks;

        Bed(String name, SoundEvent event) {
            super(event, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.name = name;
            this.nightInsects = isNightInsectBed(name);
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
            float effective = level;
            if (nightInsects) {
                float chorus = 0.45f + 0.55f * (0.5f + 0.5f * Mth.sin(ticks * 0.015f)) * (0.6f + 0.4f * Mth.sin(ticks * 0.004f + 1.3f));
                effective *= chorus * movementDuck;
            }
            volume = Math.max(0.0001f, effective);
            if (target <= 0f && level <= 0.002f) {
                if (++silentTicks > 40) stop();
            } else {
                silentTicks = 0;
            }
        }
    }
}
