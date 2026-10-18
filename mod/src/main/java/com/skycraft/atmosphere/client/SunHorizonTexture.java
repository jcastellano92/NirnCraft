package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.render.client.DataTexture;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;


/**
 * One texel telling the shader pack how high the terrain stands on the horizon in the direction of the sun, so the sun
 * rises late and sets early behind far mountains instead of following the flat-world clock.
 *
 * <p>Bound at {@code skycraft:textures/environment/sun_horizon.png} ({@code uniform sampler2D skycraftSunHorizonTex}
 * in Complementary Reimagined). The sun path is the vanilla one (east to west through the zenith), so the only
 * horizon that matters is straight east when the sun is in the east half of the sky and straight west otherwise. The
 * Tamriel height map is marched from {@link #NEAR} blocks out to {@link #FAR}; the steepest rise is the horizon angle.
 * Red channel = sine of that angle / {@link #MAX_SIN}. Nearby terrain and buildings are left to the shader's own
 * shadow map.</p>
 */
public final class SunHorizonTexture implements AutoCloseable {
    public static final Identifier LOCATION = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/environment/sun_horizon.png");
    /** Horizon sines above this saturate (about 30 degrees). */
    static final float MAX_SIN = 0.5f;
    private static final double NEAR = 192.0, FAR = 14000.0;
    private static final int UPDATE_TICKS = 4;

    private static SunHorizonTexture instance;
    private static final MapSample SAMPLE = new MapSample();

    private final NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
    private ClientLevel trackedLevel;
    private float eased;
    private boolean primed;
    private int lastTick = Integer.MIN_VALUE;

    private SunHorizonTexture() {
        write(0f);
    }

    public static SunHorizonTexture get() {
        if (instance == null) instance = new SunHorizonTexture();
        return instance;
    }

    public static void ensureRegistered() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) return;
        SunHorizonTexture tex = get();
        mc.getTextureManager().getTexture(LOCATION);
    }

    /** Client tick: refreshes the horizon every few ticks. */
    public static void tick(ClientLevel level, Vec3 cam) {
        ensureRegistered();
        get().update(level, cam);
    }

    /** The sine of the terrain's elevation angle toward the sun's side of the sky from (x, eyeY, z); 0 on flat ground. */
    public static float horizonSin(double x, double eyeY, double z, int dirX) {
        double best = 0.0;
        for (double d = NEAR; d <= FAR; d *= 1.16) {
            int bx = (int) Math.floor(x + dirX * d);
            TamrielMap.sample(bx, (int) Math.floor(z), SAMPLE);
            double h = Math.max(SAMPLE.elevation, TamrielMap.SEA_LEVEL);
            double tan = (h - eyeY) / d;
            if (tan > best) best = tan;
        }
        return (float) Mth.clamp(best / Math.sqrt(1.0 + best * best), 0.0, MAX_SIN);
    }

    private void update(ClientLevel level, Vec3 cam) {
        if (trackedLevel != level) { trackedLevel = level; primed = false; lastTick = Integer.MIN_VALUE; eased = 0f; }
        int now = (int) level.getGameTime();
        if (primed && Math.abs(now - lastTick) < UPDATE_TICKS) return;
        lastTick = now;
        // vanilla sun direction: (-sin(t), cos(t), 0) with t the time-of-day angle, so +x (east) while rising
        double angle = com.skycraft.atmosphere.client.SkyEnvironment.sunCycle(level, 1.0f) * Math.PI * 2.0;
        int dirX = -Math.sin(angle) >= 0.0 ? 1 : -1;
        float target = horizonSin(cam.x, cam.y, cam.z, dirX);
        if (!primed) {
            eased = target;
            primed = true;
        } else {
            eased += (target - eased) * 0.10f;
        }
        write(eased);
        uploadAllTargets();
    }

    private void write(float sin) {
        int r = Math.max(0, Math.min(255, Math.round(Mth.clamp(sin / MAX_SIN, 0f, 1f) * 255f)));
        image.setPixelABGR(0, 0, (255 << 24) | r);
    }

    private void uploadAllTargets() {
        try (var uploadState = TextureUploadState.preserve()) { DataTexture.write(LOCATION, image); }
    }

    @Override
    public void close() {
        image.close();
        trackedLevel = null;
        if (instance == this) instance = null;
    }
}

