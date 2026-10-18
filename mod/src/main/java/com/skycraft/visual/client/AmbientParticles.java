package com.skycraft.visual.client;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.client.ClientWeather;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.visual.VisualConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.Tags;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Spawns the atmospheric particles around the camera every client tick: regional precipitation vanilla can't draw
 * (snow where vanilla would rain, ash, sand), frost breath, drifting snow, falling leaves, fireflies, embers over
 * lava, marsh wisps and dungeon dust. Every spawn checks the particle budget ({@link AtmosParticle#live}), scales
 * with Video Settings > Particles, and only looks at a handful of blocks per tick (lava is scanned every two
 * seconds into a fixed-size cache). Nothing here allocates except the particles themselves.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class AmbientParticles {
    static final SpriteSet[] SPRITES = new SpriteSet[AtmosParticle.Kind.values().length];
    private static final TagKey<EntityType<?>> BREATHERS = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Skycraft.MODID, "frost_breath"));

    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final int MAX_LAVA = 24;
    private static final int[] LAVA = new int[MAX_LAVA * 3];
    private static int lavaCount;
    private static ClientLevel lastLevel;
    private static int ticks;

    // context refreshed twice a second
    private static boolean underground;
    private static boolean coldHere;
    private static boolean warmForest;
    private static boolean swampHere;
    private static boolean snowyGround;

    private AmbientParticles() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.client.event.ClientTickEvent.Post)) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            // the particle engine was cleared along with the old level
            lastLevel = level;
            AtmosParticle.live = 0;
            lavaCount = 0;
        }
        if (level == null || mc.player == null || mc.isPaused()) return;
        if (!VisualConfig.PARTICLES.get() || SPRITES[0] == null) return;
        ParticleStatus status = mc.options.particles().get();
        if (status == ParticleStatus.MINIMAL) return;
        float scale = status == ParticleStatus.DECREASED ? 0.5f : 1.0f;
        int range = VisualConfig.PARTICLE_RANGE.get();
        AtmosParticle.maxDistSqr = (double) range * range;
        int budget = (int) (VisualConfig.PARTICLE_BUDGET.get() * scale);
        if (budget <= 0) return;

        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 cam = camera.position();
        RandomSource rnd = level.getRandom();
        ticks++;
        if (ticks % 10 == 0) refreshContext(level, mc.player);
        if (ticks % 40 == 0 && VisualConfig.EMBERS.get()) scanLava(level, mc.player.blockPosition());

        boolean overworld = level.dimension() == Level.OVERWORLD;
        boolean weather = overworld && ClientWeather.active() && AtmosphereConfig.WEATHER_PARTICLES.get();
        WeatherProfile profile = ClientWeather.profile();
        float windStrength = weather ? ClientWeather.wind() : 0.2f;
        float clouds = weather ? ClientWeather.clouds() : (level.getRainLevel(1f) > 0.1f ? 0.85f : 0.25f);
        float wx = ClientWeather.windX() * windStrength * 0.22f;
        float wz = ClientWeather.windZ() * windStrength * 0.22f;

        // ---- regional precipitation vanilla can't draw (gated by cloud cover so it NEVER precipitates in bright sun)
        if (weather && clouds >= 0.56f) {
            float cloudGate = Mth.clamp((clouds - 0.56f) / 0.22f, 0f, 1f);
            float snow = ClientWeather.snow() * cloudGate;
            if (snow > 0.02f) precipitation(level, rnd, cam, AtmosParticle.Kind.SNOW, snow * 20f * scale, budget, wx * 1.8f, wz * 1.8f);
            float ash = ClientWeather.ash() * cloudGate;
            if (ash > 0.03f && VisualConfig.ASH.get()) precipitation(level, rnd, cam, AtmosParticle.Kind.ASH, ash * 12f * scale, budget, wx * 1.5f, wz * 1.5f);
            float sand = ClientWeather.sand();
            if (sand > 0.03f && VisualConfig.SAND.get()) blownSand(level, rnd, cam, sand * 16f * scale, budget, wx * 2.4f, wz * 2.4f);
        }

        // ---- low ground-skimming sand/snow only during strong wind + active weather
        if (overworld && !underground && profile.ambient == WeatherProfile.Ambient.SAND && VisualConfig.SAND.get() && windStrength > 0.52f && clouds > 0.45f) {
            blownSand(level, rnd, cam, (windStrength - 0.45f) * 3f * scale, budget, wx * 2.4f, wz * 2.4f);
        }
        if (overworld && !underground && snowyGround && VisualConfig.DRIFTING_SNOW.get() && windStrength > 0.52f && (ClientWeather.snow() > 0.05f || clouds > 0.62f)) {
            driftingSnow(level, rnd, cam, (windStrength - 0.45f) * 4f * scale, budget, wx * 2.6f, wz * 2.6f);
        }
        if (VisualConfig.FALLING_LEAVES.get() && !underground) fallingLeaves(mc, level, rnd, cam, scale, budget, wx, wz, windStrength);
        if (VisualConfig.FIREFLIES.get() && overworld && warmForest && !underground) fireflies(level, rnd, cam, scale, budget);
        if (VisualConfig.MARSH_WISPS.get() && overworld && !underground && (swampHere || profile.ambient == WeatherProfile.Ambient.MARSH)) {
            marshWisps(level, rnd, cam, scale, budget, wx, wz);
        }
        // A dark house is not a dungeon. Authored media bounds select cave dust;
        // weather still spawns in exposed columns visible through a window/opening.
        if (VisualConfig.DUST_MOTES.get() && com.skycraft.atmosphere.client.InteriorAtmosphere.profileAt(cam) >= 0)
            dustMotes(level, rnd, cam, scale, budget);
        if (VisualConfig.EMBERS.get() && lavaCount > 0) embers(level, rnd, scale, budget, wx, wz);
        if (VisualConfig.FROST_BREATH.get()) frostBreath(mc, level, rnd, cam, budget);
    }

    // ------------------------------------------------------------------ context

    private static void refreshContext(ClientLevel level, Player player) {
        BlockPos p = player.blockPosition();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
        underground = level.getBrightness(LightLayer.SKY, p) <= 2 && p.getY() < surface - 4;
        Biome biome = level.getBiome(p).value();
        WeatherProfile profile = ClientWeather.profile();
        coldHere = !biome.warmEnoughToRain(p, level.getSeaLevel()) || profile == WeatherProfile.SKYRIM_COLD || profile == WeatherProfile.FROZEN
                || profile.snowLine < p.getY() || ClientWeather.snow() > 0.2f;
        float temp = biome.getBaseTemperature();
        var holder = level.getBiome(p);
        swampHere = holder.is(Tags.Biomes.IS_SWAMP);
        warmForest = temp >= 0.5f && temp <= 1.1f && (holder.is(net.minecraft.tags.BiomeTags.IS_FOREST) || holder.is(Tags.Biomes.IS_PLAINS)
                || holder.is(net.minecraft.tags.BiomeTags.IS_JUNGLE) || swampHere);
        BlockState ground = level.getBlockState(POS.set(p.getX(), surface - 1, p.getZ()));
        BlockState top = level.getBlockState(POS.set(p.getX(), surface, p.getZ()));
        snowyGround = top.is(Blocks.SNOW) || ground.is(BlockTags.SNOW) || ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.POWDER_SNOW);
    }

    private static boolean room(int budget) {
        return AtmosParticle.live < budget;
    }

    /** Spawns {@code rate} particles per tick on average (fractions roll). */
    private static int count(RandomSource rnd, float rate) {
        int n = (int) rate;
        if (rnd.nextFloat() < rate - n) n++;
        return n;
    }

    private static AtmosParticle spawn(ClientLevel level, AtmosParticle.Kind kind, double x, double y, double z, double vx, double vy, double vz) {
        SpriteSet sprites = SPRITES[kind.ordinal()];
        if (sprites == null) return null;
        AtmosParticle p = new AtmosParticle(level, x, y, z, vx, vy, vz, kind, sprites);
        Minecraft.getInstance().particleEngine.add(p);
        return p;
    }

    // ------------------------------------------------------------------ precipitation

    /** Flakes falling from above, only where the sky is open, spawned upwind so they blow across the view. */
    private static void precipitation(ClientLevel level, RandomSource rnd, Vec3 cam, AtmosParticle.Kind kind, float rate,
                                      int budget, float wx, float wz) {
        int n = count(rnd, rate);
        for (int i = 0; i < n && room(budget); i++) {
            double x = cam.x + (rnd.nextDouble() - 0.5) * 28 - wx * 30;
            double z = cam.z + (rnd.nextDouble() - 0.5) * 28 - wz * 30;
            double y = cam.y + 4 + rnd.nextDouble() * 10;
            int sky = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
            if (y < sky) continue;
            AtmosParticle p = spawn(level, kind, x, y, z, wx, -0.05 - rnd.nextDouble() * 0.05, wz);
            if (p != null) p.wind(wx, wz);
        }
    }

    /** Sand and dust streaming low over the ground with the wind. */
    private static void blownSand(ClientLevel level, RandomSource rnd, Vec3 cam, float rate, int budget, float wx, float wz) {
        int n = count(rnd, rate);
        float len = Mth.sqrt(wx * wx + wz * wz);
        if (len < 1.0E-3f) {
            wx = 0.2f;
            len = 0.2f;
        }
        for (int i = 0; i < n && room(budget); i++) {
            double side = (rnd.nextDouble() - 0.5) * 30;
            double back = 6 + rnd.nextDouble() * 14;
            double x = cam.x - wx / len * back - wz / len * side;
            double z = cam.z - wz / len * back + wx / len * side;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
            if (ground > cam.y + 6) continue;
            double y = ground + rnd.nextDouble() * 3.5;
            AtmosParticle p = spawn(level, AtmosParticle.Kind.SAND, x, y, z, wx, 0, wz);
            if (p != null) p.wind(wx, wz);
        }
    }

    /** Snow skimming along snowy ground in the wind. */
    private static void driftingSnow(ClientLevel level, RandomSource rnd, Vec3 cam, float rate, int budget, float wx, float wz) {
        int n = count(rnd, rate);
        for (int i = 0; i < n && room(budget); i++) {
            double x = cam.x + (rnd.nextDouble() - 0.5) * 24 - wx * 20;
            double z = cam.z + (rnd.nextDouble() - 0.5) * 24 - wz * 20;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
            if (Math.abs(ground - cam.y) > 8) continue;
            AtmosParticle p = spawn(level, AtmosParticle.Kind.SNOW, x, ground + 0.1 + rnd.nextDouble() * 0.6, z, wx, 0.01, wz);
            if (p != null) p.wind(wx, wz).size(0.7f);
        }
    }

    // ------------------------------------------------------------------ nature

    /** Probes a few columns near the camera for leaves with air below and lets a leaf go now and then, more often in wind. */
    private static void fallingLeaves(Minecraft mc, ClientLevel level, RandomSource rnd, Vec3 cam, float scale, int budget, float wx, float wz, float windStrength) {
        int tries = scale >= 1.0f ? (windStrength > 0.55f ? 5 : 3) : 2;
        float dropChance = 0.14f + 0.28f * windStrength;
        int cx = Mth.floor(cam.x), cy = Mth.floor(cam.y), cz = Mth.floor(cam.z);
        for (int t = 0; t < tries && room(budget); t++) {
            int x = cx + rnd.nextInt(25) - 12;
            int z = cz + rnd.nextInt(25) - 12;
            for (int y = cy + 14; y > cy - 3; y--) {
                BlockState s = level.getBlockState(POS.set(x, y, z));
                if (!s.is(BlockTags.LEAVES)) continue;
                if (!level.getBlockState(POS.set(x, y - 1, z)).isAir()) break;
                if (rnd.nextFloat() > dropChance) break;
                var tint = mc.getBlockColors().getTintSource(s, 0);
                int color = tint == null ? -1 : tint.colorInWorld(s, level, POS.set(x, y, z));
                if (color == -1) color = s.getMapColor(level, POS).col;
                AtmosParticle p = spawn(level, AtmosParticle.Kind.LEAF, x + rnd.nextDouble(), y - 0.05, z + rnd.nextDouble(), wx * 0.5, -0.03, wz * 0.5);
                if (p != null) p.wind(wx, wz).color(shade(color, 0.85f + rnd.nextFloat() * 0.25f));
                break;
            }
        }
    }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return r << 16 | g << 8 | b;
    }

    /** Fireflies drift up from the undergrowth on clear warm nights. */
    private static void fireflies(ClientLevel level, RandomSource rnd, Vec3 cam, float scale, int budget) {
        long time = level.getDefaultClockTime() % 24000L;
        if (time < 13000L || time > 23000L) return;
        if (level.getRainLevel(1f) > 0.2f) return;
        if (rnd.nextFloat() > 0.35f * scale || !room(budget)) return;
        double x = cam.x + (rnd.nextDouble() - 0.5) * 32;
        double z = cam.z + (rnd.nextDouble() - 0.5) * 32;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        if (Math.abs(ground - cam.y) > 10) return;
        if (!level.getFluidState(POS.set(Mth.floor(x), ground - 1, Mth.floor(z))).isEmpty()) return;
        spawn(level, AtmosParticle.Kind.FIREFLY, x, ground + 0.4 + rnd.nextDouble() * 2.2, z, 0, 0, 0);
    }

    /** Low wisps of fog over marsh ground and water, mostly at dawn, dusk and night. */
    private static void marshWisps(ClientLevel level, RandomSource rnd, Vec3 cam, float scale, int budget, float wx, float wz) {
        long time = level.getDefaultClockTime() % 24000L;
        boolean dim = time > 11500L || time < 1500L;
        float rate = (dim ? 0.12f : 0.04f) + ClientWeather.fog() * 0.15f;
        if (rnd.nextFloat() > rate * scale || !room(budget)) return;
        double x = cam.x + (rnd.nextDouble() - 0.5) * 36;
        double z = cam.z + (rnd.nextDouble() - 0.5) * 36;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        if (Math.abs(ground - cam.y) > 12) return;
        AtmosParticle p = spawn(level, AtmosParticle.Kind.WISP, x, ground + 0.2 + rnd.nextDouble() * 0.8, z, wx * 0.2, 0, wz * 0.2);
        if (p != null) p.wind(wx, wz);
    }

    /** Dust hanging in the still air of caves and dungeons. */
    private static void dustMotes(ClientLevel level, RandomSource rnd, Vec3 cam, float scale, int budget) {
        if (rnd.nextFloat() > 0.5f * scale || !room(budget)) return;
        double x = cam.x + (rnd.nextDouble() - 0.5) * 12;
        double y = cam.y + (rnd.nextDouble() - 0.5) * 5;
        double z = cam.z + (rnd.nextDouble() - 0.5) * 12;
        if (!level.getBlockState(POS.set(Mth.floor(x), Mth.floor(y), Mth.floor(z))).isAir()) return;
        int profile = com.skycraft.atmosphere.client.InteriorAtmosphere.profileAt(new Vec3(x,y,z));
        if (profile < 0) return;
        AtmosParticle mote = spawn(level, AtmosParticle.Kind.MOTE, x, y, z, 0, 0, 0);
        if (mote != null) mote.color(profile == 2 ? 0xD4E9FF : profile == 3 ? 0xA5E0CF : 0xD2C7B2);
    }

    /** Records up to {@link #MAX_LAVA} lava blocks with air above near the player (every two seconds). */
    private static void scanLava(ClientLevel level, BlockPos center) {
        lavaCount = 0;
        for (int dx = -10; dx <= 10 && lavaCount < MAX_LAVA; dx += 2) {
            for (int dz = -10; dz <= 10 && lavaCount < MAX_LAVA; dz += 2) {
                for (int dy = -6; dy <= 4; dy++) {
                    FluidState f = level.getFluidState(POS.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz));
                    if (!f.is(net.minecraft.tags.FluidTags.LAVA)) continue;
                    if (!level.getBlockState(POS.setY(POS.getY() + 1)).isAir()) continue;
                    int i = lavaCount++ * 3;
                    LAVA[i] = POS.getX();
                    LAVA[i + 1] = POS.getY() - 1;
                    LAVA[i + 2] = POS.getZ();
                    break;
                }
            }
        }
    }

    private static void embers(ClientLevel level, RandomSource rnd, float scale, int budget, float wx, float wz) {
        int n = count(rnd, lavaCount * 0.06f * scale);
        for (int k = 0; k < n && room(budget); k++) {
            int i = rnd.nextInt(lavaCount) * 3;
            double x = LAVA[i] + rnd.nextDouble() * 2 - 0.5;
            double z = LAVA[i + 2] + rnd.nextDouble() * 2 - 0.5;
            AtmosParticle p = spawn(level, AtmosParticle.Kind.EMBER, x, LAVA[i + 1] + 1.0, z,
                    (rnd.nextDouble() - 0.5) * 0.04, 0.04 + rnd.nextDouble() * 0.05, (rnd.nextDouble() - 0.5) * 0.04);
            if (p != null) p.wind(wx, wz);
        }
    }

    // ------------------------------------------------------------------ frost breath

    /** People and beasts near the camera breathe out a small cloud every couple of seconds in the cold. */
    private static void frostBreath(Minecraft mc, ClientLevel level, RandomSource rnd, Vec3 cam, int budget) {
        long time = level.getGameTime();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isUnderWater() || le.isInvisible()) continue;
            int cadence = 46 + (e.getId() & 15);
            if ((time + e.getId() * 7L) % cadence != 0) continue;
            if (e.distanceToSqr(cam) > 24 * 24) continue;
            if (!(e instanceof Player || e instanceof Npc || e instanceof AbstractIllager || e.getType().builtInRegistryHolder().is(BREATHERS))) continue;
            if (!isCold(level, e)) continue;
            boolean self = e == mc.player && mc.options.getCameraType().isFirstPerson();
            Vec3 look = e.getViewVector(1f);
            // in first person the breath appears low and a little ahead, faint, so it doesn't fog the view
            double ex = e.getX() + look.x * (self ? 0.85 : 0.3);
            double ey = e.getEyeY() - (self ? 0.45 : 0.12);
            double ez = e.getZ() + look.z * (self ? 0.85 : 0.3);
            int puffs = self ? 1 + rnd.nextInt(2) : 2 + rnd.nextInt(2);
            for (int i = 0; i < puffs && room(budget); i++) {
                AtmosParticle p = spawn(level, AtmosParticle.Kind.BREATH, ex + (rnd.nextDouble() - 0.5) * 0.05, ey, ez + (rnd.nextDouble() - 0.5) * 0.05,
                        look.x * 0.035 + (rnd.nextDouble() - 0.5) * 0.01, 0.004, look.z * 0.035 + (rnd.nextDouble() - 0.5) * 0.01);
                if (p != null && self) p.opacity(0.5f).size(0.7f);
            }
        }
    }

    private static boolean isCold(ClientLevel level, Entity e) {
        if (e == Minecraft.getInstance().player) return coldHere;
        BlockPos p = e.blockPosition();
        return !level.getBiome(p).value().warmEnoughToRain(p, level.getSeaLevel()) || coldHere && e.distanceToSqr(Minecraft.getInstance().player) < 48 * 48;
    }
}
