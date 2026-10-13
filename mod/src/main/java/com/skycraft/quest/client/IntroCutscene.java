package com.skycraft.quest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.skycraft.audio.AudioSounds;
import com.skycraft.creatures.ModEntities;
import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.quest.story.IntroPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The dragon attack as this player alone sees it. Nothing here exists on the server: the dragon is an entity object
 * created on the client and never added to the level (drawn by hand each frame), the fire, smoke, debris and embers are
 * client particles, and every sound is played locally at a position, so other players in the cottage, or anywhere,
 * neither see nor hear any of it. The timeline is a pure function of the tick counter and the server's seed.
 *
 * <p>Timeline (scaled by the configured length; 48 s by default): bars and zoom in; a low rumble; the dragon rises
 * over the ridge and begins circling Helgen; it breathes fire on the town six times, each blast sweeping from one
 * point to the next and leaving it burning; roars with a pitch shift as it passes, bells, screams, collapsing
 * timber; the dragon climbs away and the town smokes.</p>
 *
 * <p>Shake and explosions are tied to what the player sees: the camera shakes only while a fire-breath burst visibly
 * lands (a short, light tremor scaled by its distance), and one explosion (flash, sound, a harder jolt) happens at most
 * every {@link #EXPLOSION_GAP} ticks, again only when the breath is in view. Ambient fire does not shake the camera.</p>
 */
final class IntroCutscene {
    private final IntroPackets.Cutscene p;
    private final int total;
    private final double scale;
    private final RandomSource rng;
    private final Vec3 eye;
    private final Vec3 town;
    private final Vec3 side;
    private final Vec3 away;
    private final Vec3[] burn;
    private final int[] litAt;
    private final int[] order;
    private final int[] breathStart;
    private static final int BREATHS = 6;
    private static final int BREATH_LEN = 62;
    /** At most one explosion this often (ticks): 12 seconds. */
    private static final int EXPLOSION_GAP = 240;

    private int t;
    private DragonEntity dragon;
    private boolean dragonFailed;
    private Vec3 pos = Vec3.ZERO, prevPos = Vec3.ZERO;
    private float yaw;
    private double shake;
    private float camYaw, camPitch, prevCamYaw, prevCamPitch;
    private double lastDist = -1;
    private Vec3 mouth = Vec3.ZERO;
    private int lastRoar = -999;
    private int lastExplosion = -EXPLOSION_GAP;

    IntroCutscene(IntroPackets.Cutscene p) {
        this.p = p;
        this.total = Math.max(400, p.ticks());
        this.scale = total / 960.0;
        this.rng = RandomSource.create(p.seed());
        this.eye = new Vec3(p.ex(), p.ey(), p.ez());
        this.town = new Vec3(p.tx(), p.ty(), p.tz());
        Vec3 u = new Vec3(town.x - eye.x, 0, town.z - eye.z);
        u = u.lengthSqr() < 1 ? new Vec3(0, 0, 1) : u.normalize();
        this.away = u;
        this.side = new Vec3(-u.z, 0, u.x);
        int n = p.burn().length / 3;
        this.burn = new Vec3[n];
        for (int i = 0; i < n; i++) burn[i] = new Vec3(p.burn()[i * 3], p.burn()[i * 3 + 1], p.burn()[i * 3 + 2]);
        this.litAt = new int[n];
        java.util.Arrays.fill(litAt, -1);
        // a shuffled visiting order of the burning points
        this.order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        this.breathStart = new int[BREATHS];
        for (int i = 0; i < BREATHS; i++) breathStart[i] = (int) ((140 + i * 118) * scale);
        this.camYaw = this.prevCamYaw = p.yaw();
        this.camPitch = this.prevCamPitch = p.pitch();
        this.pos = this.prevPos = entryPoint();
    }

    // ------------------------------------------------------------------ queries (the renderer and the events read these)

    int ticks() {
        return t;
    }

    boolean finished() {
        return t > total + 40;
    }

    /** Letterbox bar height as a fraction of the screen (each of the two bars). */
    float bars(float partial) {
        float tt = t + partial;
        float in = Mth.clamp(tt / 40f, 0f, 1f);
        float out = Mth.clamp((total - tt) / 30f, 0f, 1f);
        float e = Math.min(in, out);
        return 0.115f * (e * e * (3 - 2 * e));
    }

    /** Field of view multiplier: a slow zoom in toward the town. */
    float zoom(float partial) {
        float tt = t + partial;
        float in = Mth.clamp(tt / 90f, 0f, 1f);
        float slow = Mth.clamp(tt / total, 0f, 1f);
        float out = Mth.clamp((total - tt) / 30f, 0f, 1f);
        float z = 1f - 0.2f * (in * in * (3 - 2 * in)) - 0.06f * slow;
        return Mth.lerp(1f - out, z, 1f);
    }

    float yaw(float partial) {
        return prevCamYaw + Mth.wrapDegrees(camYaw - prevCamYaw) * partial;
    }

    float pitch(float partial) {
        return Mth.lerp(partial, prevCamPitch, camPitch);
    }

    /** Camera shake: roll and pitch jitter in degrees for this frame. */
    float[] shakeNow(float partial) {
        double tt = (t + partial);
        double a = shake * 0.5;
        double roll = a * (Math.sin(tt * 1.7) * 0.9 + Math.sin(tt * 3.9 + 1.3) * 0.5 + Math.sin(tt * 7.1) * 0.25);
        double pit = a * 0.6 * (Math.sin(tt * 2.3 + 0.7) + 0.5 * Math.sin(tt * 5.3));
        double yw = a * 0.5 * Math.sin(tt * 2.9 + 2.1);
        return new float[]{(float) roll, (float) pit, (float) yw};
    }

    // ------------------------------------------------------------------ the path of the dragon

    private double groundY() {
        return town.y;
    }

    private Vec3 entryPoint() {
        return town.add(side.scale(-250)).add(away.scale(-30)).add(0, 96, 0);
    }

    private Vec3 exitPoint() {
        return town.add(side.scale(260)).add(away.scale(150)).add(0, 150, 0);
    }

    private Vec3 circlePoint(double tt) {
        double radius = 62 + 6 * Math.sin(tt * 0.011);
        double ang = 0.8 + tt * 0.0245;
        Vec3 c = town.add(away.scale(-18));
        double y = groundY() + 50 + 5 * Math.sin(tt * 0.021);
        return new Vec3(c.x + Math.cos(ang) * radius, y, c.z + Math.sin(ang) * radius);
    }

    private Vec3 dragonAt(double tt) {
        double enter0 = 40 * scale, enter1 = 175 * scale;
        double exit0 = total - 230 * scale, exit1 = total + 10;
        Vec3 c = circlePoint(tt);
        if (tt < enter1) {
            double k = smooth((tt - enter0) / (enter1 - enter0));
            return entryPoint().lerp(c, k);
        }
        if (tt > exit0) {
            double k = smooth((tt - exit0) / (exit1 - exit0));
            k = k * k;
            return c.lerp(exitPoint(), k);
        }
        return c;
    }

    private static double smooth(double k) {
        k = Mth.clamp(k, 0, 1);
        return k * k * (3 - 2 * k);
    }

    // ------------------------------------------------------------------ the tick

    void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) return;
        t++;
        ensureDragon(level);
        prevPos = pos;
        pos = dragonAt(t);
        Vec3 vel = pos.subtract(prevPos);
        if (vel.horizontalDistanceSqr() > 1e-6) yaw = (float) Math.toDegrees(Math.atan2(vel.z, vel.x)) - 90f;
        updateDragon(vel);
        double dist = pos.distanceTo(eye);
        double vr = lastDist < 0 ? 0 : dist - lastDist;
        lastDist = dist;

        // the first rumble, as if the hill itself spoke
        if (t == (int) (30 * scale)) {
            play(level, SoundEvents.LIGHTNING_BOLT_THUNDER, eye.add(away.scale(60)), 0.5f, 0.5f);
        }
        // a tremor is short: it dies away in about half a second (no sustained trembling under the fire)
        shake *= 0.88;

        dragonSounds(level, dist, vr);
        breaths(level);
        town(level);
        camera();
    }

    private void camera() {
        prevCamYaw = camYaw;
        prevCamPitch = camPitch;
        // look at the town; once the dragon is up, drift toward it (never more than halfway)
        Vec3 target = town.add(0, 12, 0);
        if (t > 100 * scale && t < total - 60 * scale) {
            double k = 0.45 * smooth((t - 100 * scale) / (80 * scale));
            target = target.lerp(pos, k);
        }
        double dx = target.x - eye.x, dz = target.z - eye.z, dy = target.y - eye.y;
        float wantYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        wantPitch = Mth.clamp(wantPitch, -28f, 35f);
        camYaw += Mth.wrapDegrees(wantYaw - camYaw) * 0.05f;
        camPitch += (wantPitch - camPitch) * 0.05f;
    }

    private void addShake(double e) {
        shake = Math.min(1.2, shake + e);
    }

    /** True when {@code p} is inside the view cone of the camera (about the screen), as the scene's own camera aims. */
    private boolean inView(Vec3 p) {
        Vec3 d = p.subtract(eye);
        double len = d.length();
        if (len < 1) return true;
        double yawR = Math.toRadians(camYaw), pitR = Math.toRadians(camPitch);
        double fx = -Math.sin(yawR) * Math.cos(pitR), fy = -Math.sin(pitR), fz = Math.cos(yawR) * Math.cos(pitR);
        double cos = (d.x * fx + d.y * fy + d.z * fz) / len;
        return cos > 0.6; // within about 53 degrees of the centre of the view
    }

    /** 1 for a hit at the window, falling with distance (a far impact is felt, not thrown at). */
    private double nearness(Vec3 p) {
        return Mth.clamp(1.0 - p.distanceTo(eye) / 320.0, 0.15, 1.0);
    }

    // ------------------------------------------------------------------ the dragon entity

    private void ensureDragon(ClientLevel level) {
        if (dragon != null || dragonFailed) return;
        try {
            DragonEntity d = ModEntities.DRAGON.get().create(level);
            if (d == null) {
                dragonFailed = true;
                return;
            }
            d.applyVariant(DragonEntity.ALDUIN);
            d.setFlying(true);
            d.setNoGravity(true);
            d.setCustomNameVisible(false);
            d.setPos(pos.x, pos.y, pos.z);
            dragon = d;
        } catch (RuntimeException | LinkageError e) {
            dragonFailed = true;
            com.skycraft.Skycraft.LOGGER.warn("Intro cutscene: the client dragon could not be created", e);
        }
    }

    private void updateDragon(Vec3 vel) {
        if (dragon == null) return;
        dragon.xo = dragon.xOld = prevPos.x;
        dragon.yo = dragon.yOld = prevPos.y;
        dragon.zo = dragon.zOld = prevPos.z;
        dragon.setPos(pos.x, pos.y, pos.z);
        dragon.yRotO = dragon.getYRot();
        dragon.yBodyRotO = dragon.yBodyRot;
        dragon.yHeadRotO = dragon.yHeadRot;
        dragon.xRotO = dragon.getXRot();
        float pitch = (float) -Math.toDegrees(Math.atan2(vel.y, Math.max(0.01, Math.sqrt(vel.x * vel.x + vel.z * vel.z)))) * 0.6f;
        dragon.setYRot(yaw);
        dragon.yBodyRot = yaw;
        dragon.yHeadRot = yaw;
        dragon.setXRot(pitch);
        dragon.tickCount++;
        dragon.walkAnimation.update((float) Mth.clamp(vel.length() * 0.6, 0.0, 1.0), 0.4f);
        Vec3 fwd = Vec3.directionFromRotation(0, yaw);
        mouth = pos.add(fwd.scale(7.0)).add(0, 1.6, 0);
    }

    /** Draws the dragon (called from the level render stage, with the camera-relative pose stack). */
    void render(Minecraft mc, PoseStack pose, Vec3 cam, float partial) {
        if (dragon == null || t < 2) return;
        double x = Mth.lerp(partial, prevPos.x, pos.x) - cam.x;
        double y = Mth.lerp(partial, prevPos.y, pos.y) - cam.y;
        double z = Mth.lerp(partial, prevPos.z, pos.z) - cam.z;
        var buffers = mc.renderBuffers().bufferSource();
        mc.getEntityRenderDispatcher().render(dragon, x, y, z, yaw, partial, pose, buffers, 15728880);
        buffers.endBatch();
    }

    // ------------------------------------------------------------------ sounds

    /**
     * Plays a sound at a position for this player only. {@code loud} (0..1) is the loudness heard at the listener; the
     * volume is raised with the distance so that far events are still heard, but quieter the farther they are.
     */
    private void play(ClientLevel level, SoundEvent sound, Vec3 at, float loud, float pitch) {
        // the engine clamps the volume to 1 and then falls off linearly over 16 x volume blocks: to be heard at a gain
        // k at distance d the volume is d / (16 (1 - k))
        double d = at.distanceTo(eye);
        double k = Mth.clamp(loud * Mth.clamp(1.15 - d / 420.0, 0.18, 1.0), 0.05, 0.92);
        float vol = (float) Math.max(1.0, d / (16.0 * (1.0 - k)));
        level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.HOSTILE, vol, pitch, false);
    }

    private void dragonSounds(ClientLevel level, double dist, double vr) {
        float doppler = (float) Mth.clamp(-vr * 0.35, -0.18, 0.18);
        // roars: as it rises over the ridge, then every ~9 s
        if (t == (int) (95 * scale) || (t > 150 * scale && t - lastRoar > 190 * scale && t < total - 120 * scale)) {
            lastRoar = t;
            play(level, AudioSounds.dragon("alduin", "roar"), pos, 1.0f, 0.88f + doppler);
            play(level, SoundEvents.ENDER_DRAGON_GROWL, pos, 0.35f, 0.55f + doppler);
        }
        if (t % 26 == 0 && t > 60) {
            play(level, AudioSounds.dragon("alduin", "wings"), pos, 0.55f, 0.85f + doppler * 0.5f);
        }
        // the town's alarm: bells in the first half minute
        if (t > 60 * scale && t < 700 * scale && t % 34 == 0) {
            Vec3 b = burn.length > 0 ? burn[rng.nextInt(burn.length)] : town;
            play(level, SoundEvents.BELL_BLOCK, b.add(0, 8, 0), 0.8f, 0.62f + rng.nextFloat() * 0.12f);
        }
    }

    // ------------------------------------------------------------------ the breath and the burning town

    private void particle(ClientLevel level, ParticleOptions o, double x, double y, double z, double vx, double vy, double vz) {
        level.addParticle(o, true, x, y, z, vx, vy, vz);
    }

    private void breaths(ClientLevel level) {
        if (burn.length == 0) return;
        for (int i = 0; i < BREATHS; i++) {
            int s = breathStart[i];
            if (t < s || t >= s + BREATH_LEN) continue;
            int k = t - s;
            Vec3 a = burn[order[(i * 2) % burn.length]], b = burn[order[(i * 2 + 1) % burn.length]];
            double sweep = smooth(k / (double) BREATH_LEN);
            Vec3 target = a.lerp(b, sweep).add(0, 2, 0);
            if (k == 0) {
                play(level, AudioSounds.dragon("alduin", "breath_fire"), mouth, 1.0f, 0.9f);
                if (dragon != null) {
                    dragon.anims().state(AnimSlot.SHOUT).start(dragon.tickCount);
                }
            }
            // the blast: a dense stream of flame from the mouth to the target, widening with the distance
            Vec3 dir = target.subtract(mouth);
            double len = dir.length();
            if (len < 1) continue;
            Vec3 d = dir.scale(1.0 / len);
            for (int n = 0; n < 40; n++) {
                double f = rng.nextDouble();
                double spread = 0.4 + 0.045 * f * len;
                Vec3 at = mouth.add(d.scale(f * len)).add((rng.nextDouble() - 0.5) * spread, (rng.nextDouble() - 0.5) * spread, (rng.nextDouble() - 0.5) * spread);
                ParticleOptions kind = n % 9 == 0 ? ParticleTypes.LAVA : n % 4 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME;
                particle(level, kind, at.x, at.y, at.z, d.x * 0.5, d.y * 0.5 - 0.02, d.z * 0.5);
            }
            // where it lands: fire, smoke, flying timber and stone
            for (int n = 0; n < 7; n++) {
                particle(level, ParticleTypes.FLAME, target.x + rng.nextGaussian() * 2.5, target.y + rng.nextDouble() * 2, target.z + rng.nextGaussian() * 2.5,
                        rng.nextGaussian() * 0.12, 0.25 + rng.nextDouble() * 0.3, rng.nextGaussian() * 0.12);
            }
            for (int n = 0; n < 4; n++) {
                particle(level, ParticleTypes.LARGE_SMOKE, target.x + rng.nextGaussian() * 3, target.y + 1 + rng.nextDouble() * 3, target.z + rng.nextGaussian() * 3,
                        rng.nextGaussian() * 0.06, 0.18 + rng.nextDouble() * 0.2, rng.nextGaussian() * 0.06);
            }
            for (int n = 0; n < 6; n++) {
                ParticleOptions deb = new BlockParticleOption(ParticleTypes.BLOCK,
                        (n & 1) == 0 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
                particle(level, deb, target.x + rng.nextGaussian(), target.y + 1, target.z + rng.nextGaussian(),
                        rng.nextGaussian() * 0.5, 0.5 + rng.nextDouble() * 0.8, rng.nextGaussian() * 0.5);
            }
            boolean seen = inView(target);
            // the fire landing is felt, lightly, only while it is in sight: a short tremor every few ticks, by distance
            if (seen && k >= 8 && k < BREATH_LEN - 8 && k % 6 == 0) addShake(0.10 * nearness(target));
            // one explosion at most every twelve seconds, and only when the breath is in view
            if (k == BREATH_LEN / 2 && seen && t - lastExplosion >= EXPLOSION_GAP) {
                lastExplosion = t;
                particle(level, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 2, target.z, 0, 0, 0);
                play(level, SoundEvents.GENERIC_EXPLODE, target, 0.6f, 0.55f + rng.nextFloat() * 0.15f);
                addShake(0.45 * nearness(target));
            }
            if (k == BREATH_LEN / 2 || k == BREATH_LEN - 1) {
                int which = order[(i * 2 + (k == BREATH_LEN - 1 ? 1 : 0)) % burn.length];
                if (litAt[which] < 0) litAt[which] = t;
            }
        }
    }

    /** The town itself: fire, smoke columns, embers and the noise of a place coming down, for every lit point. */
    private void town(ClientLevel level) {
        // before the first blast there is only a faint haze over the walls
        for (int i = 0; i < burn.length; i++) {
            if (litAt[i] < 0) continue;
            Vec3 b = burn[i];
            int age = t - litAt[i];
            // fire
            for (int n = 0; n < 3; n++) {
                particle(level, ParticleTypes.FLAME, b.x + rng.nextGaussian() * 2.2, b.y + 1 + rng.nextDouble() * 2.5, b.z + rng.nextGaussian() * 2.2,
                        rng.nextGaussian() * 0.02, 0.08 + rng.nextDouble() * 0.1, rng.nextGaussian() * 0.02);
            }
            particle(level, ParticleTypes.LARGE_SMOKE, b.x + rng.nextGaussian() * 1.6, b.y + 3, b.z + rng.nextGaussian() * 1.6,
                    rng.nextGaussian() * 0.03, 0.12 + rng.nextDouble() * 0.08, rng.nextGaussian() * 0.03);
            if (t % 2 == 0) {
                particle(level, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, b.x + rng.nextGaussian() * 0.8, b.y + 2, b.z + rng.nextGaussian() * 0.8,
                        0.0, 0.07 + rng.nextDouble() * 0.04, 0.0);
            }
            if (t % 5 == 0) {
                particle(level, ParticleTypes.LAVA, b.x + rng.nextGaussian() * 3, b.y + 3 + rng.nextDouble() * 4, b.z + rng.nextGaussian() * 3, 0, 0, 0);
            }
            if (t % 9 == 0) {
                particle(level, ParticleTypes.ASH, b.x + rng.nextGaussian() * 6, b.y + 6 + rng.nextDouble() * 6, b.z + rng.nextGaussian() * 6,
                        0.02, 0.01, 0.02);
            }
            // the noise of it: crackle, a roof coming down, a scream or a shout, distance-attenuated
            if ((t + i * 7) % 30 == 0) play(level, SoundEvents.FIRE_AMBIENT, b, 0.35f, 0.7f + rng.nextFloat() * 0.3f);
            if ((t + i * 13) % 97 == 0 && age > 10) {
                play(level, rng.nextBoolean() ? SoundEvents.WOOD_BREAK : SoundEvents.STONE_BREAK, b, 0.5f, 0.45f + rng.nextFloat() * 0.25f);
                for (int n = 0; n < 8; n++) {
                    ParticleOptions deb = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SPRUCE_PLANKS.defaultBlockState());
                    particle(level, deb, b.x + rng.nextGaussian() * 2, b.y + 4, b.z + rng.nextGaussian() * 2, rng.nextGaussian() * 0.15, -0.05, rng.nextGaussian() * 0.15);
                }
            }
            if ((t + i * 19) % 83 == 0 && age > 20) {
                SoundEvent s = switch (rng.nextInt(4)) {
                    case 0 -> SoundEvents.VILLAGER_HURT;
                    case 1 -> SoundEvents.VILLAGER_DEATH;
                    case 2 -> SoundEvents.PILLAGER_HURT;
                    default -> SoundEvents.VINDICATOR_HURT;
                };
                play(level, s, b.add(rng.nextGaussian() * 4, 1, rng.nextGaussian() * 4), 0.7f, 0.65f + rng.nextFloat() * 0.5f);
            }
        }
        // fire is also the faint low rumble under everything: a deep cave rumble every few seconds once something burns
        boolean any = false;
        for (int v : litAt) any |= v >= 0;
        if (any && t % 90 == 0) play(level, SoundEvents.AMBIENT_BASALT_DELTAS_MOOD.value(), town, 0.25f, 0.6f);
    }

    /** Position of the dragon for the tests of the cutscene geometry. */
    Vec3 dragonPos() {
        return pos;
    }
}
