package com.nirncraft.atmosphere.client;

import com.nirncraft.atmosphere.NightSkyMath;
import com.nirncraft.atmosphere.NirnCelestial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The celestial sky's state each frame (com.nirncraft.atlas.AtlasSky draws it: shaders/include/atlas_celestial.glsl),
 * from the world: the clock (the stars' frame, the planets' places, the comet's), the camera's place on Tamriel's map
 * (its latitude: what stands above the horizon); and their light on the land (the planets and a bright comet, as points: added to the
 * night's light by AtlasSky.nightLight beside the meteors').
 *
 * <p>The comet: one in about six years per world ({@link NirnCelestial#yearComet}, from the world's seed when this
 * client runs the world, else from the server's address), seen for weeks round its perihelion. The dev hook:
 * {@code -Dnirncraft.comet=now} brings a great comet to its brightest the night after the first one it is asked for
 * (then waning over the weeks; {@code now-7}: its brightest a week later, waxing till then);
 * {@code -Dnirncraft.comet=<day>} puts its brightest night on that day (days since the world began: day time / 24000);
 * {@code -Dnirncraft.comet=off} hides comets.</p>
 */
public final class CelestialSky {
    private CelestialSky() {}

    private static float[] light = new float[16];
    private static double[] arkay = {0, 1, 0};
    private static double forcedAt = Double.NaN;
    private static NirnCelestial.Comet forced;  // (the dev hook's: its orbit searched once, not each frame)
    private static double forcedFor = Double.NaN;
    private static long worldKey;
    private static ClientLevel keyLevel;

    /** CelestialInfo for this frame (atlas_celestial.glsl). sunAngle the sky's (radians), sunY the game's sun's height,
     *  camera in world blocks. */
    public static float[] frame(Minecraft mc, float sunAngle, float sunY, double camX, double camZ, float partialTick) {
        ClientLevel level = mc.level;
        double dayTime = level != null ? level.getOverworldClockTime() + partialTick : 18000.0;
        double days = dayTime / 24000.0;
        NirnCelestial.Comet comet = comet(mc, days);
        var f = NirnCelestial.frame(sunAngle, days, camX, camZ, comet, 1.0);
        // the land's light from them: only while the sky shows its lights, lifted as the sky's other emitters
        boolean shown = NightSky.drawn();
        double gain = NightSkyMath.nightGain(sunY);
        float[] l = new float[16];
        if (shown) for (int i = 0; i < 16; i++) l[i] = (float) (f.light()[i] * gain);
        light = l;
        arkay = f.arkayWorld();
        return f.block();
    }

    /** The planets' and a comet's light this frame as SH L1 (4 x rgb + pad: NightSky.meteorLight's layout). */
    public static float[] light() {
        return light.clone();
    }

    /** Arkay's direction (world) in the last frame: the Necromancer's Moon's target (the moons' agent). */
    public static double[] arkayDirection() {
        return arkay.clone();
    }

    private static NirnCelestial.Comet comet(Minecraft mc, double days) {
        String hook = System.getProperty("nirncraft.comet", "");
        if (hook.equalsIgnoreCase("off")) return null;
        if (hook.toLowerCase(java.util.Locale.ROOT).startsWith("now")) {
            if (Double.isNaN(forcedAt)) {
                double ahead = 1.0;
                try {
                    if (hook.length() > 3) ahead = -Double.parseDouble(hook.substring(3));
                } catch (NumberFormatException ignored) {
                    // (now: its brightest tomorrow night)
                }
                forcedAt = Math.floor(days) + ahead;
            }
            if (forced == null || forcedFor != forcedAt) {
                forced = NirnCelestial.forcedComet(forcedAt);
                forcedFor = forcedAt;
            }
            return forced;
        }
        if (!hook.isEmpty()) {
            try {
                double at = Double.parseDouble(hook);
                if (forced == null || forcedFor != at) {
                    forced = NirnCelestial.forcedComet(at);
                    forcedFor = at;
                }
                return forced;
            } catch (NumberFormatException ignored) {
                // (an unreadable hook: the world's own comets)
            }
        }
        return NirnCelestial.cometFor(worldKey(mc), days);
    }

    /** The world's key for its comets: its seed when this client runs it, else the server's address (one per world
     *  for everyone joining it the same way). */
    private static long worldKey(Minecraft mc) {
        if (mc.level != keyLevel) {
            keyLevel = mc.level;
            long k = 0x4E49524EL;
            var server = mc.getSingleplayerServer();
            if (server != null) k = server.overworld().getSeed();
            else if (mc.getCurrentServer() != null) k = mc.getCurrentServer().ip.hashCode() * 0x9E3779B97F4A7C15L;
            worldKey = k;
        }
        return worldKey;
    }
}
