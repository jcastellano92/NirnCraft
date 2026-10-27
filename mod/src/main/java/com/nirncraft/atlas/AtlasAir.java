package com.nirncraft.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nirncraft.atmosphere.client.ClientWeather;
import com.nirncraft.atmosphere.client.WeatherFieldTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;

/**
 * The air Infinite ATLAS draws in: one physical atmosphere for the sky behind everything (AtlasSky), the far field's
 * haze and sky light, and the key light that lights near and far (the sun's light through the air: a low sun warm,
 * dimmer, its shadows long). The model is native (native/src/air.hpp; atlas.h AtlasAir); this class gives it the climate
 * and hands its answers to the GL side.
 *
 * <p>The climate's handle is what is in the air, never other physics (atlas.h AtlasClimate, mapped by
 * atlas_air_from_climate, the one mapping, checked offline with atlas_render air=clean|humid|dusty|smoke|snow|salt):
 * <ul>
 *   <li>temperature and relative humidity: the eye's column of the weather model (WeatherPhysics.ColumnState, as
 *       WeatherFieldTexture last sampled it): molecules follow p / T, water-loving aerosols swell with humidity
 *       (more haze, whiter, a wider glow);</li>
 *   <li>the region's background aerosol: that column's aerosol band (water: continental haze, snow: clean cold air
 *       over bright ground, sand: desert dust, pale and tinted, ash: volcanic haze reddening the sun), eased here over
 *       seconds as the player crosses regions;</li>
 *   <li>what the weather adds: dust storms, ash and smoke, falling snow, mist (ClientWeather's eased presentation, the
 *       column's ground fog), rain (the level's), sea salt by the coast (ClientWeather.coastDistance);</li>
 *   <li>pressure: standard (the weather model carries none yet).</li>
 * </ul>
 * The weather work replaces these inputs (and gives the far field per-cell air); the interface stays as it is.</p>
 */
final class AtlasAir {
    // AtlasClimate: temperatureC, relativeHumidity, pressure, background[4] (water, snow, sand, ash), dust, ash, snow,
    // rain, fog, coast
    private static final float[] climate = {15f, 0.5f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    private static boolean eased;
    private static float[] pushed;  // the air the far field and the sky draw in (set again when it moved enough)
    private static long pushedAt;
    private static int version;
    private static Object pushedTo;  // the context it was given to (a new context starts with the default air)
    private static float[] coeffs;  // atlas_air_coeffs(pushed): atlas_air.glsl's AirRayleigh .. AirOzone
    private static float[] msPending;  // atlas_air_multiscatter(pushed), uploaded on the render thread
    private static GpuTexture msTexture;
    private static GpuTextureView msView;
    private static float[] moonDir, moonRad;  // the lunar key AtlasClient hands the core
    private static final float[] lightSun = new float[3];
    private static float lightY = Float.NaN;
    private static int lightVersion = -1;
    private static float[] light;

    private AtlasAir() {}

    static void closeAtShutdown() {
        if (msView != null) { msView.close(); msView = null; }
        if (msTexture != null) { msTexture.close(); msTexture = null; }
    }

    /** Once a frame on the render thread: the climate at the eye, eased; the air from it, set when it moved enough. */
    static void update(Minecraft mc, AtlasNative lib, AtlasNative.Context ctx) {
        if (lib == null || !lib.hasAir()) return;
        float[] target = climateTarget(mc);
        for (int i = 0; i < climate.length; i++) climate[i] = eased ? climate[i] + (target[i] - climate[i]) * 0.02f : target[i];
        eased = true;
        float[] air = lib.airFromClimate(climate);
        long now = System.currentTimeMillis();
        // a new air costs its multiple-scattering LUT (~10 ms on all threads): only when it changed by 3 %, every 3 s
        if (pushed == null || (now - pushedAt > 3000 && differs(air, pushed, 0.03f))) push(lib, air, now);
        if (ctx != null && pushedTo != ctx) {
            ctx.setAir(pushed);
            pushedTo = ctx;
        }
    }

    private static void push(AtlasNative lib, float[] air, long now) {
        pushed = air.clone();
        pushedAt = now;
        version++;
        pushedTo = null;
        coeffs = lib.airCoeffs(pushed);
        msPending = lib.airMultiscatter(pushed);
    }

    /** The default air until the climate is known (a frame drawn before the first update). */
    private static boolean ready() {
        if (pushed != null) return true;
        AtlasNative lib = AtlasClient.library();
        if (lib == null || !lib.hasAir()) return false;
        push(lib, lib.airFromClimate(climate), System.currentTimeMillis());
        return true;
    }

    private static boolean differs(float[] a, float[] b, float tol) {
        for (int i = 0; i < a.length; i++)
            if (Math.abs(a[i] - b[i]) > tol * Math.max(Math.abs(b[i]), 0.05f)) return true;
        return false;
    }

    /** The climate the air follows, at the eye now (AtlasClimate's floats). */
    static float[] climateTarget(Minecraft mc) {
        float[] c = new float[13];
        var col = WeatherFieldTexture.eyeColumn();
        c[0] = col != null ? col.temperatureC() : 15f;
        c[1] = col != null ? col.relativeHumidity() : 0.5f;
        c[2] = 1f;
        c[3 + (col != null ? WeatherFieldTexture.aerosolBand(col.aerosolCode()) : 0)] = 1f;
        boolean weather = ClientWeather.active() && !WeatherFieldTexture.isOff();
        if (weather) {
            c[7] = ClientWeather.sand();
            c[8] = ClientWeather.ash();
            c[9] = ClientWeather.snow();
            c[11] = Math.max(ClientWeather.fog(), col != null ? col.groundFog() : 0f);
            c[12] = 1f - smooth(60f, 600f, ClientWeather.coastDistance());
        }
        if (!WeatherFieldTexture.isOff()) c[10] = mc.level != null ? mc.level.getRainLevel(1f) : 0f;
        return c;
    }

    /** The night's key light the core was given (AtlasClient: LunarKey), for the light the host draws with. */
    static void moon(float[] dir, float[] rad) {
        moonDir = dir.clone();
        moonRad = rad.clone();
    }

    /**
     * {key dir xyz, key radiance rgb, sky up rgb, ground rgb} at world height eyeY: the far field's own light
     * (atlas_air_light), integrated again when the sun, the eye's height or the air moved; null without the air.
     */
    static float[] light(float[] sun, float eyeY) {
        if (!ready()) return null;
        AtlasNative lib = AtlasClient.library();
        boolean same = light != null && lightVersion == version && Math.abs(lightY - eyeY) < 4f;
        for (int i = 0; i < 3 && same; i++) same = Math.abs(lightSun[i] - sun[i]) < 2e-4f;
        if (!same && lib != null) {
            light = lib.airLight(pushed, sun, moonDir, moonRad, eyeY);
            System.arraycopy(sun, 0, lightSun, 0, 3);
            lightY = eyeY;
            lightVersion = version;
        }
        return light;
    }

    /** atlas_air.glsl's AirRayleigh .. AirOzone (16 floats), or null without the air. */
    static float[] coeffs() {
        return ready() ? coeffs : null;
    }

    /** The moon's direction and its irradiance above the air for this sun (air.hpp lightsOf: pi x radiance, faded out
     *  while the sun is up), as atlas_air.glsl's AirMoon / AirMoonE take them; the default moonlight opposite the sun. */
    static float[] moonFor(float[] sun) {
        float[] d = moonDir != null ? moonDir : new float[] {-sun[0], -sun[1], -sun[2]};
        float[] r = moonRad != null ? moonRad : new float[] {8.7e-6f, 1.25e-5f, 2.2e-5f};  // (physical: ~1/400000 of the sun)
        float fade = (float) Math.PI * smooth(0.1f, 0f, sun[1]);
        return new float[] {d[0], d[1], d[2], r[0] * fade, r[1] * fade, r[2] * fade};
    }

    /** The multiple-scattering LUT as a 32 x 32 RGBA16F texture (uploaded here when the air changed), or null. */
    static GpuTextureView multiscatter() {
        if (!ready()) return null;
        float[] ms = msPending;
        if (ms != null) {
            var device = RenderSystem.getDevice();
            if (msTexture == null || msTexture.isClosed()) {
                msTexture = device.createTexture("atlas air multiscatter", GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA16_FLOAT, 32, 32, 1, 1);
                msView = device.createTextureView(msTexture);
            }
            ByteBuffer buf = ByteBuffer.allocateDirect(32 * 32 * 8).order(ByteOrder.nativeOrder());
            for (float v : ms) buf.putShort(Float.floatToFloat16(v));
            buf.flip();
            device.createCommandEncoder().writeToTexture(msTexture, buf, 0, 0, 0, 0, 32, 32);
            msPending = null;
        }
        return msView;
    }

    /** The current air's version (bumps when it is set again). */
    static int version() {
        return version;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
