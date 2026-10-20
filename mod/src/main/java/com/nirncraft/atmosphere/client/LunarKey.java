package com.nirncraft.atmosphere.client;

import com.nirncraft.atmosphere.AtmosphereConfig;
import com.nirncraft.atmosphere.NightSkyMath;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The night's key light (atlas_set_moon, AtlasAir's moon): the brighter of Masser and Secunda, in its own colour
 * (NightSkyMath.moonlights: the moons' places on the sun's path, their phases, the hand-over where they are nearly
 * equal). The other moon is the fill light, in the sky's light (AtlasSky.nightLight). Both fields and the sky's discs
 * take one model.
 */
public final class LunarKey {
    private LunarKey() {}

    /** {dirX, dirY, dirZ, radR, radG, radB} towards the key moon (radiance: its irradiance over pi); sun the unit vector
     *  towards the sun. */
    public static float[] compute(ClientLevel level, float[] sun, float sunPathRotationDeg, float partialTick) {
        double days = (level.getOverworldClockTime() + partialTick) / 24000.0;
        double[] s = {sun[0], sun[1], sun[2]};
        double[] obs = NightSky.observer();  // (the camera's place: the moons' parallax)
        var masser = NightSkyMath.moon(false, days, s, sunPathRotationDeg, obs);
        var secunda = NightSkyMath.moon(true, days, s, sunPathRotationDeg, obs);
        double[] ml = NightSkyMath.moonlights(masser, secunda, AtmosphereConfig.SECUNDA.get());
        return new float[] {(float) ml[0], (float) ml[1], (float) ml[2],
                (float) (ml[3] / Math.PI), (float) (ml[4] / Math.PI), (float) (ml[5] / Math.PI)};
    }
}
