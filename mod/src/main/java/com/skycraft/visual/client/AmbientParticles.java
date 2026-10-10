package com.skycraft.visual.client;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.client.ClientWeather;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.visual.VisualConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.AbstractIllager;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Spawns the atmospheric particles around the camera every client tick: regional precipitation vanilla can't draw
 * (snow where vanilla would rain, ash, sand), frost breath, drifting snow, falling leaves, fireflies, embers over
 * lava, marsh wisps and dungeon dust. Every spawn checks the particle budget ({@link AtmosParticle#live}), scales
 * with Video Settings > Particles, and only looks at a handful of blocks per tick (lava is scanned every two
 * seconds into a fixed-size cache). Nothing here allocates except the particles themselves.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class AmbientParticles {
    static final SpriteSet[] SPRITES = new SpriteSet[AtmosParticle.Kind.values().length];
    private static final TagKey<EntityType<?>> BREATHERS = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "frost_breath"));

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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        RandomSource rnd = level.random;
        ticks++;
        if (ticks % 10 == 0) refreshContext(level, mc.player);
        if (ticks % 40 == 0 && VisualConfig.EMBERS.get()) scanLava(level, mc.player.blockPosition());

        boolean overworld = level.dimension() == Level.OVERWORLD;
        boolean weather = overworld && ClientWeather.active() && AtmosphereConfig.WEATHER_PARTICLES.get();
        WeatherProfile profile = ClientWeather.profile();
        float windStrength = weather ? ClientWeather.wind() : 0.2f;
        float wx = ClientWeather.windX() * windStrength * 0.18f;
        float wz = ClientWeather.windZ() * windStrength * 0.18f;

        // ---- regional precipitation vanilla can't draw
        if (weather && !underground) {
            float snow = ClientWeather.snow();
            if (snow > 0.02f) precipitation(level, rnd, cam, AtmosParticle.Kind.SNOW, snow * 20f * scale, budget, wx * 1.6f, wz * 1.6f);
            float ash = ClientWeather.ash();
            if (ash > 0.02f && VisualConfig.ASH.get()) precipitation(level, rnd, cam, AtmosParticle.Kind.ASH, ash * 12f * scale, budget, wx * 1.4f, wz * 1.4f);
            float sand = ClientWeather.sand();
            if (sand > 0.02f && VisualConfig.SAND.get()) blownSand(level, rnd, cam, sand * 16f * scale, budget, wx * 2.2f, wz * 2.2f);
        }

        // ---- the climate's light ambient particles, even in clear weather
        if (overworld && !underground) {
            switch (profile.ambient) {
                case ASH -> {
                    if (VisualConfig.ASH.get()) precipitation(level, rnd, cam, AtmosParticle.Kind.ASH, 0.6f * scale, budget, wx, wz);
                }
                case SAND -> {
                    if (VisualConfig.SAND.get() && windStrength > 0.3f) blownSand(level, rnd, cam, windStrength * 2f * scale, budget, wx * 2.2f, wz * 2.2f);
                }
                default -> {}
            }
        }
        if (overworld && !underground && snowyGround && VisualConfig.DRIFTING_SNOW.get() && windStrength > 0.25f) {
            driftingSnow(level, rnd, cam, windStrength * 3f * scale, budget, wx * 2.5f, wz * 2.5f);
        }
        if (VisualConfig.FALLING_LEAVES.get() && !underground) fallingLeaves(mc, level, rnd, cam, scale, budget, wx, wz);
        if (VisualConfig.FIREFLIES.get() && overworld && warmForest && !underground) fireflies(level, rnd, cam, scale, budget);
        if (VisualConfig.MARSH_WISPS.get() && overworld && !underground && (swampHere || profile.ambient == WeatherProfile.Ambient.MARSH)) {
            marshWisps(level, rnd, cam, scale, budget, wx, wz);
        }
        if (VisualConfig.DUST_MOTES.get() && underground) dustMotes(level, rnd, cam, scale, budget);
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
        coldHere = !biome.warmEnoughToRain(p) || profile == WeatherProfile.SKYRIM_COLD || profile == WeatherProfile.FROZEN
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

    /** Probes a few columns near the camera for leaves with air below and lets a leaf go now and then. */
    private static void fallingLeaves(Minecraft mc, ClientLevel level, RandomSource rnd, Vec3 cam, float scale, int budget, float wx, float wz) {
        int tries = scale >= 1.0f ? 3 : 2;
        int cx = Mth.floor(cam.x), cy = Mth.floor(cam.y), cz = Mth.floor(cam.z);
        for (int t = 0; t < tries && room(budget); t++) {
            int x = cx + rnd.nextInt(25) - 12;
            int z = cz + rnd.nextInt(25) - 12;
            for (int y = cy + 14; y > cy - 3; y--) {
                BlockState s = level.getBlockState(POS.set(x, y, z));
                if (!s.is(BlockTags.LEAVES)) continue;
                if (!level.getBlockState(POS.set(x, y - 1, z)).isAir()) break;
                if (rnd.nextFloat() > 0.18f) break;
                int color = mc.getBlockColors().getColor(s, level, POS.set(x, y, z), 0);
                if (color == -1) color = s.getMapColor(level, POS).col;
                AtmosParticle p = spawn(level, AtmosParticle.Kind.LEAF, x + rnd.nextDouble(), y - 0.05, z + rnd.nextDouble(), 0, -0.03, 0);
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
        long time = level.getDayTime() % 24000L;
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
        long time = level.getDayTime() % 24000L;
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
        spawn(level, AtmosParticle.Kind.MOTE, x, y, z, 0, 0, 0);
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
            if (!(e instanceof Player || e instanceof Npc || e instanceof AbstractIllager || e.getType().is(BREATHERS))) continue;
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
        return !level.getBiome(p).value().warmEnoughToRain(p) || coldHere && e.distanceToSqr(Minecraft.getInstance().player) < 48 * 48;
    }
}
