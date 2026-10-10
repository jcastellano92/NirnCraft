package com.skycraft.visual.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One atmospheric particle. All kinds share this class; {@link Kind} picks the motion (falling and fluttering,
 * rising, wind-blown, wandering, expanding) and look. {@link #live} counts the particles alive so the spawner can
 * keep to its budget; anything that drifts out of range is removed early.
 */
public final class AtmosParticle extends TextureSheetParticle {
    public enum Kind { SNOW, ASH, EMBER, SAND, LEAF, FIREFLY, BREATH, WISP, MOTE }

    /** Particles of this class currently alive (reset when the level changes, see {@link AmbientParticles}). */
    static int live;
    /** Squared removal distance from the camera, refreshed by the spawner. */
    static double maxDistSqr = 40 * 40;

    private final Kind kind;
    private final SpriteSet sprites;
    private float baseAlpha;
    private final float baseSize;
    private final float phase;
    private final float spin;
    private float windX, windZ;

    AtmosParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, Kind kind, SpriteSet sprites) {
        super(level, x, y, z);
        this.kind = kind;
        this.sprites = sprites;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.phase = random.nextFloat() * Mth.TWO_PI;
        this.spin = (random.nextFloat() - 0.5f) * 0.2f;
        this.hasPhysics = true;
        this.friction = 1.0f;
        this.gravity = 0f;
        switch (kind) {
            case SNOW -> {
                lifetime = 80 + random.nextInt(80);
                quadSize = 0.08f + random.nextFloat() * 0.08f;
                alpha = 0.9f;
                gravity = 0.004f;
            }
            case ASH -> {
                lifetime = 100 + random.nextInt(80);
                quadSize = 0.045f + random.nextFloat() * 0.05f;
                alpha = 0.85f;
                gravity = 0.002f;
                float g = 0.20f + random.nextFloat() * 0.25f;
                setColor(g, g * 0.95f, g * 0.9f);
            }
            case EMBER -> {
                lifetime = 25 + random.nextInt(40);
                quadSize = 0.035f + random.nextFloat() * 0.035f;
                alpha = 1.0f;
                gravity = -0.012f;
                setColor(1.0f, 0.45f + random.nextFloat() * 0.3f, 0.1f);
                hasPhysics = false;
            }
            case SAND -> {
                lifetime = 25 + random.nextInt(25);
                quadSize = 0.035f + random.nextFloat() * 0.05f;
                alpha = 0.55f;
                gravity = 0.003f;
                setColor(0.86f, 0.72f, 0.50f);
            }
            case LEAF -> {
                lifetime = 140 + random.nextInt(80);
                quadSize = 0.07f + random.nextFloat() * 0.05f;
                alpha = 1.0f;
                gravity = 0.0035f;
            }
            case FIREFLY -> {
                lifetime = 120 + random.nextInt(140);
                quadSize = 0.05f + random.nextFloat() * 0.03f;
                alpha = 0f;
                setColor(0.85f, 1.0f, 0.45f);
                hasPhysics = false;
            }
            case BREATH -> {
                lifetime = 18 + random.nextInt(10);
                quadSize = 0.06f + random.nextFloat() * 0.04f;
                alpha = 0.32f;
                gravity = -0.0015f;
                friction = 0.92f;
                hasPhysics = false;
            }
            case WISP -> {
                lifetime = 160 + random.nextInt(120);
                quadSize = 1.4f + random.nextFloat() * 1.6f;
                alpha = 0f;
                setColor(0.80f, 0.86f, 0.82f);
                hasPhysics = false;
            }
            case MOTE -> {
                lifetime = 120 + random.nextInt(120);
                quadSize = 0.012f + random.nextFloat() * 0.014f;
                alpha = 0f;
                setColor(0.95f, 0.88f, 0.72f);
                hasPhysics = false;
            }
        }
        this.baseAlpha = kind == Kind.FIREFLY ? 0.95f : kind == Kind.WISP ? 0.16f : kind == Kind.MOTE ? 0.55f : alpha;
        this.baseSize = quadSize;
        this.roll = random.nextFloat() * Mth.TWO_PI;
        this.oRoll = roll;
        pickSprite(sprites);
        live++;
    }

    /** Wind the particle keeps feeling every tick (blocks per tick). */
    AtmosParticle wind(float wx, float wz) {
        this.windX = wx;
        this.windZ = wz;
        return this;
    }

    AtmosParticle color(int rgb) {
        setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
        return this;
    }

    AtmosParticle opacity(float scale) {
        baseAlpha *= scale;
        alpha *= scale;
        return this;
    }

    AtmosParticle size(float scale) {
        quadSize *= scale;
        return this;
    }

    @Override
    public void remove() {
        if (!removed) live = Math.max(0, live - 1);
        super.remove();
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        oRoll = roll;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        if (dx * dx + dy * dy + dz * dz > maxDistSqr) {
            remove();
            return;
        }
        float life = age / (float) lifetime;
        float t = age + phase * 10f;
        switch (kind) {
            case SNOW, ASH -> {
                xd += (windX - xd) * 0.05 + Mth.sin(t * 0.13f) * 0.004;
                zd += (windZ - zd) * 0.05 + Mth.cos(t * 0.11f) * 0.004;
                yd -= gravity;
                yd *= 0.94;
                roll += spin;
                alpha = baseAlpha * fadeOut(life, 0.85f);
                if (onGround) lifetime = Math.min(lifetime, age + 8);
            }
            case SAND -> {
                xd += (windX - xd) * 0.12;
                zd += (windZ - zd) * 0.12;
                yd = yd * 0.9 - gravity + Mth.sin(t * 0.3f) * 0.006;
                alpha = baseAlpha * fadeIn(life, 0.1f) * fadeOut(life, 0.7f);
            }
            case LEAF -> {
                // falling leaves swing from side to side and slow down as they swing up
                float swing = Mth.sin(t * 0.09f);
                xd = windX * 0.6 + swing * 0.035;
                zd = windZ * 0.6 + Mth.cos(t * 0.07f) * 0.02;
                yd = -0.035 - 0.02 * Math.abs(swing);
                roll = Mth.sin(t * 0.09f) * 0.8f + phase;
                if (onGround) {
                    xd = 0;
                    zd = 0;
                    lifetime = Math.min(lifetime, age + 30);
                }
                alpha = baseAlpha * fadeOut(life, 0.85f);
            }
            case EMBER -> {
                xd += (random.nextFloat() - 0.5f) * 0.01 + windX * 0.02;
                zd += (random.nextFloat() - 0.5f) * 0.01 + windZ * 0.02;
                yd -= gravity;
                yd *= 0.96;
                xd *= 0.96;
                zd *= 0.96;
                alpha = fadeOut(life, 0.5f) * (0.7f + 0.3f * Mth.sin(t * 0.8f));
                gCol = Mth.clamp(gCol - 0.006f, 0.15f, 1f);
                quadSize = baseSize * (1f - 0.5f * life);
            }
            case FIREFLY -> {
                xd = xd * 0.9 + (random.nextFloat() - 0.5f) * 0.012;
                yd = yd * 0.9 + (random.nextFloat() - 0.5f) * 0.008;
                zd = zd * 0.9 + (random.nextFloat() - 0.5f) * 0.012;
                float pulse = Mth.clamp(Mth.sin(t * 0.07f) * 1.6f - 0.3f, 0f, 1f);
                alpha = baseAlpha * pulse * fadeIn(life, 0.05f) * fadeOut(life, 0.85f);
            }
            case BREATH -> {
                quadSize = baseSize * (1f + life * 3.5f);
                alpha = baseAlpha * (1f - life) * (1f - life);
                xd *= friction;
                zd *= friction;
                yd = yd * friction - gravity;
            }
            case WISP -> {
                xd = xd * 0.98 + windX * 0.01 + Mth.sin(t * 0.01f) * 0.0015;
                zd = zd * 0.98 + windZ * 0.01 + Mth.cos(t * 0.012f) * 0.0015;
                yd = Mth.sin(t * 0.02f) * 0.002;
                roll += spin * 0.05f;
                alpha = baseAlpha * fadeIn(life, 0.25f) * fadeOut(life, 0.65f);
            }
            case MOTE -> {
                xd = xd * 0.95 + (random.nextFloat() - 0.5f) * 0.0016;
                yd = yd * 0.95 + (random.nextFloat() - 0.5f) * 0.0012 - 0.0002;
                zd = zd * 0.95 + (random.nextFloat() - 0.5f) * 0.0016;
                alpha = baseAlpha * fadeIn(life, 0.2f) * fadeOut(life, 0.7f);
            }
        }
        move(xd, yd, zd);
    }

    private static float fadeIn(float life, float until) {
        return Mth.clamp(life / until, 0f, 1f);
    }

    private static float fadeOut(float life, float from) {
        return Mth.clamp((1f - life) / (1f - from), 0f, 1f);
    }

    @Override
    protected int getLightColor(float partialTick) {
        if (kind == Kind.EMBER || kind == Kind.FIREFLY) return 0xF000F0;
        int packed = super.getLightColor(partialTick);
        // dust motes catch a little light even in the dark
        if (kind == Kind.MOTE) return Math.max(packed & 0xFFFF, 0x50) | (packed & 0xFFFF0000);
        return packed;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    /** Provider for {@code /particle} and server-side spawns: default motion of the kind, no wind. */
    public record Provider(Kind kind, SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            return new AtmosParticle(level, x, y, z, vx, vy, vz, kind, sprites);
        }
    }
}
