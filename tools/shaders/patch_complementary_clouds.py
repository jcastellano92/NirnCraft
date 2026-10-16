#!/usr/bin/env python3
"""
Patches ComplementaryReimagined_r5.9.3.zip and its .txt option files so that:
1. Oculus binds `skycraft:textures/environment/weather_field.png` to `uniform sampler2D skycraftWeatherTex;`
   (updated dynamically in-game by `WeatherFieldTexture.java` from `WeatherPhysics`, `RegionalWeather`,
   `WeatherFront`, and `DistantStorms`).
2. `mainClouds.glsl` preserves Distant Horizons (`dhRenderDistance`) depth occlusion so volumetric clouds,
   cumulonimbus towers, and 3D rain/light curtains wrap around and behind Distant Horizons mountains.
3. `unboundClouds.glsl` samples `skycraftWeatherTex` in world coordinates (`8192x8192` toroidal window)
   to drive:
   - Puffy fair-weather light clouds (`skyCov < 0.52`) vs heavy, dark slate storm/snow clouds (`skyCov >= 0.62`),
   - Cohesive overcast cloud decks during snow, blizzards, rain, and storms (with additive overcast floor and
     no sun-hole punching so clouds genuinely occlude the sun),
   - Strong solar forward-scattering (silver lining) on cloud fringes and Beer-Powder self-shadowing,
   - RDR2-style distant sub-cloud crepuscular light shafts (`GetSkycraftRainCurtains`) that stream down ONLY
     where the 3D cloud field along `worldLightVec` has an actual sunlit gap next to cloud mass, alongside
     slanted 3D rain/snow/sand/ash precipitation bands.
4. `cloudShadows.glsl` projects along `worldLightVec` to `cloudAlt1i` using the EXACT same `cameraPositionBIM`
   wrapping and `Noise3D(tracePosM - vec3(0.0, 0.0, wind))` 3D FBM + `skycraftWeatherTex` threshold as
   `GetCloudNoise` in `unboundClouds.glsl`, so ground shadows and volumetric god-rays align 100% with the
   actual 3D clouds overhead.
5. `volumetricLight.glsl` drives crepuscular rays strictly from `GetCloudShadow(scenePos)` when broken clouds
   are present, never creating random rays in cloudless skies or sealed blizzards.
6. `lightAndAmbientColors.glsl`, `cloudColors.glsl`, `skyColors.glsl`, and `mainLighting.glsl` respond to
   `skycraftWeatherTex` cloud density and rain bands so overworld sunlight and ambient light darken under
   snow, overcast, dark cloud cells, and distant rain bands even when vanilla `rainFactor` is 0.
7. `gbuffers_skytextured.glsl`, `gbuffers_skybasic.glsl`, and `sky.glsl` render an analytical round solar disk
   that is properly attenuated by `skycraftWeatherTex` overcast/snow cover and occluded by 3D clouds.
8. `wavingBlocks.glsl` and `block.properties` drive dynamic wind sway on all foliage and tree leaves
   (including in snowy biomes) proportional to `skycraftWeatherTex` wind/storm intensity.
"""

from __future__ import annotations

import pathlib
import re
import shutil
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]

DEFAULT_ZIPS = [
    pathlib.Path(
        r"C:\Users\Jeremy\AppData\Roaming\PrismLauncher\instances\Skycraft-client-instance\.minecraft\shaderpacks\ComplementaryReimagined_r5.9.3.zip"
    ),
    ROOT / "pack" / "shaderpacks" / "ComplementaryReimagined_r5.9.3.zip",
]

TXT_CONFIG_PATHS = [
    ROOT / "pack" / "shaderpacks" / "ComplementaryReimagined_r5.9.3.zip.txt",
    ROOT / "pack" / "config" / "ComplementaryReimagined_r5.9.3.zip.txt",
    pathlib.Path(
        r"C:\Users\Jeremy\AppData\Roaming\PrismLauncher\instances\Skycraft-client-instance\.minecraft\shaderpacks\ComplementaryReimagined_r5.9.3.zip.txt"
    ),
]

SHADER_TXT_CONTENT = """#Skycraft Complementary Reimagined profile - Landscape-driven 3D volumetric weather & Distant Horizons
CLOUD_STYLE_DEFINE=3
CLOUD_ALT1=232
CLOUD_SHADOWS=true
LIGHTSHAFT_QUALI_DEFINE=2
AURORA_STYLE_DEFINE=0
NIGHT_NEBULAE=0
WEATHER_TEX_OPACITY=100
"""

SKYCRAFT_WAVING_FOLIAGE = (
    "skycraft:scathecraw skycraft:ash_grass skycraft:swamp_reeds:half=lower "
    "skycraft:nightshade skycraft:deathbell skycraft:nirnroot skycraft:crimson_nirnroot "
    "skycraft:frost_mirriam skycraft:elves_ear "
    "skycraft:jazbay_grapes skycraft:juniper_berries "
    "skycraft:thistle_branch skycraft:giant_lichen skycraft:poison_bloom "
    "skycraft:emperor_parasol_moss "
    "skycraft:ashen_grass_pod skycraft:comberry skycraft:marshmerrow "
    "skycraft:saltrice skycraft:bittergreen_petals skycraft:gold_kanet "
    "skycraft:moon_sugar "
    "skycraft:kresh_fiber skycraft:coda_flower "
    "skycraft:hackle_lo skycraft:desert_rose skycraft:cactus_pulp "
    "skycraft:snowberries"
)

SKYCRAFT_WAVING_LEAVES_FALSE = (
    "skycraft:golden_aspen_leaves:waterlogged=false skycraft:orange_aspen_leaves:waterlogged=false "
    "skycraft:snowy_spruce_leaves:waterlogged=false skycraft:graht_oak_leaves:waterlogged=false "
    "skycraft:palm_fronds:waterlogged=false skycraft:hist_leaves:waterlogged=false"
)

SKYCRAFT_WAVING_LEAVES_TRUE = (
    "skycraft:golden_aspen_leaves:waterlogged=true skycraft:orange_aspen_leaves:waterlogged=true "
    "skycraft:snowy_spruce_leaves:waterlogged=true skycraft:graht_oak_leaves:waterlogged=true "
    "skycraft:palm_fronds:waterlogged=true skycraft:hist_leaves:waterlogged=true"
)

UNBOUND_CLOUDS_GLSL = """#if CLOUD_QUALITY == 1 || !defined DEFERRED1
    const float cloudStretchRaw = 12.0;
#elif CLOUD_QUALITY == 2
    const float cloudStretchRaw = 17.5;
#elif CLOUD_QUALITY == 3
    const float cloudStretchRaw = 19.5;
#endif
#if CLOUD_UNBOUND_SIZE_MULT <= 100
    const float cloudStretch = cloudStretchRaw;
#else
    const float cloudStretch = cloudStretchRaw / float(CLOUD_UNBOUND_SIZE_MULT_M);
#endif

#if CLOUD_QUALITY > 1
    const float cloudNarrowness = 0.00012;
#else
    const float cloudNarrowness = 0.00006;
#endif

const float cloudTallness = cloudStretch * 2.0;

// Skycraft landscape-driven weather field helpers (8192x8192 block toroidal world window)
vec2 GetSkycraftWorldXZ(vec2 playerOffsetXZ) {
    #if IRIS_VERSION >= 10800
        return vec2(cameraPositionInt.xz) + cameraPositionBestFract.xz + playerOffsetXZ;
    #else
        return cameraPosition.xz + playerOffsetXZ;
    #endif
}

vec4 SampleSkycraftWeather(vec2 playerOffsetXZ) {
    vec2 worldXZ = GetSkycraftWorldXZ(playerOffsetXZ);
    return texture2DLod(skycraftWeatherTex, fract(worldXZ / 8192.0), 0.0);
}

float GetCloudNoise(vec3 tracePos, int cloudAltitude, float lTracePosXZ, float cloudPlayerPosY, float skyCov, float skyTower, float skyCurtain, out float localLower, out float localHigher) {
    float effectiveCov = max(skyCov, max(rainFactor * 0.86, skyCurtain * 0.80));
    localLower  = float(cloudAltitude) - cloudStretch * (0.95 + 0.78 * skyTower + 0.22 * skyCurtain);
    localHigher = float(cloudAltitude) + cloudStretch * (1.05 + 0.45 * effectiveCov + 1.55 * skyTower);
    if (tracePos.y < localLower || tracePos.y > localHigher) {
        return -1.0;
    }

    vec3 tracePosM = tracePos.xyz * cloudNarrowness;
    float wind = 0.0006;
    float noise = 0.0;
    float currentPersist = 1.0;
    float total = 0.0;

    #if CLOUD_SPEED_MULT == 100
        #define CLOUD_SPEED_MULT_M CLOUD_SPEED_MULT * 0.01
        wind *= syncedTime;
    #else
        #define CLOUD_SPEED_MULT_M CLOUD_SPEED_MULT * 0.01
        wind *= frameTimeCounter * CLOUD_SPEED_MULT_M;
    #endif
    #if CLOUD_UNBOUND_SIZE_MULT != 100
        tracePosM *= CLOUD_UNBOUND_SIZE_MULT_M;
        wind *= CLOUD_UNBOUND_SIZE_MULT_M;
    #endif

    #if CLOUD_QUALITY == 1
        int sampleCount = 2;
        float persistance = 0.6;
        float noiseMult = 0.95;
        wind *= 0.5;
    #elif CLOUD_QUALITY == 2 || !defined DEFERRED1
        int sampleCount = 4;
        float persistance = 0.52;
        float noiseMult = 1.15;
    #elif CLOUD_QUALITY == 3
        int sampleCount = 4;
        float persistance = 0.52;
        float noiseMult = 1.02;
    #endif

    #ifndef DEFERRED1
        noiseMult *= 1.2;
    #endif

    for (int i = 0; i < sampleCount; i++) {
        #if CLOUD_QUALITY >= 2
            noise += Noise3D(tracePosM - vec3(0.0, 0.0, wind)) * currentPersist;
        #else
            noise += texture2DLod(noisetex, tracePosM.xz - vec2(0.0, wind), 0.0).b * currentPersist;
        #endif
        total += currentPersist;

        tracePosM *= 2.95;
        wind *= 0.5;
        currentPersist *= persistance;
    }
    noise = pow2(noise / total);

    #define CLOUD_BASE_ADD 0.82
    #define CLOUD_ABOVE_ADD 0.1

    float height01 = clamp01((tracePos.y - localLower) / max(12.0, localHigher - localLower));
    float anvilShelf = smoothstep(0.56, 0.82, height01) * smoothstep(1.0, 0.82, height01) * smoothstep(0.38, 0.86, skyTower);
    float skycraftBaseAdd = mix(0.68, 1.32, smoothstep(0.06, 0.86, effectiveCov))
                          + 0.25 * skyTower
                          + 0.14 * anvilShelf
                          + 0.14 * skyCurtain;

    noiseMult *= mix(CLOUD_BASE_ADD + CLOUD_UNBOUND_RAIN_ADD * rainFactor, skycraftBaseAdd, 0.90)
                + CLOUD_ABOVE_ADD * clamp01(-cloudPlayerPosY / cloudTallness);
    noise *= noiseMult * CLOUD_UNBOUND_AMOUNT;

    // Puffy cumulus vertical profile: flat condensation base at height01 ~ 0.18, rounded billowy domes above
    float baseFlat = smoothstep(0.0, 0.18, height01);
    float domeRound = smoothstep(1.0, 0.28, height01);
    float cumulusProfile = baseFlat * mix(domeRound, sqrt(max0(domeRound)), 0.55 + 0.35 * skyTower);
    // Additive overcast floor seals the cloud deck during snow, rain, and overcast weather so no bright blue holes remain
    float overcastFloor = smoothstep(0.62, 0.90, effectiveCov) * cumulusProfile * 0.27;
    float edgeThreshold = (1.0 - cumulusProfile) * 0.24 + 0.235;
    return (noise + overcastFloor) - edgeThreshold;
}

vec4 GetSkycraftRainCurtains(int cloudAltitude, float distanceThreshold, float skyFade, float skyMult0, vec3 cameraPos, vec3 nPlayerPos, float lViewPosM, float VdotS, float VdotU, float dither) {
    if (nPlayerPos.y > 0.42 || nPlayerPos.y < -0.35) return vec4(0.0);

    float maxDist = min(min(lViewPosM, distanceThreshold * 0.72), 2600.0);
    float minDist = 175.0;
    if (maxDist <= minDist + 40.0) return vec4(0.0);

    float curtainBotY = 56.0;
    float curtainTopY = float(cloudAltitude) - cloudStretch * 0.75;
    vec4 accum = vec4(0.0);

    vec3 cloudSkyColor = GetSky(VdotU, VdotS, dither, isEyeInWater == 0, false);
    #ifdef ATM_COLOR_MULTS
        cloudSkyColor *= sqrtAtmColorMult;
    #endif

    vec3 worldLightVec = normalize(mat3(gbufferModelViewInverse) * lightVec);
    float sunShaftGate = smoothstep(0.06, 0.22, worldLightVec.y) * sunVisibility * smoothstep(-0.10, 0.85, VdotS);

    const int CURTAIN_STEPS = 7;
    for (int s = 0; s < CURTAIN_STEPS; s++) {
        float t = (float(s) + dither) / float(CURTAIN_STEPS);
        float d = mix(minDist, maxDist, t * t * (0.62 + 0.38 * t));
        if (d > lViewPosM) break;

        vec3 rPos = cameraPos + nPlayerPos * d;
        if (rPos.y < curtainBotY || rPos.y > curtainTopY + 18.0) continue;

        float fallDist = max(0.0, curtainTopY - rPos.y);
        vec2 windSlant = vec2(0.28, 0.14) * fallDist;
        vec2 relXZ = (rPos.xz - cameraPos.xz) + windSlant;
        vec4 skyW = SampleSkycraftWeather(relXZ);
        float skyCov = skyW.r;
        float skyTower = skyW.g;
        float curtain = skyW.b;

        float vFade = smoothstep(curtainBotY, curtainBotY + 22.0, rPos.y)
                    * smoothstep(curtainTopY + 18.0, curtainTopY - 16.0, rPos.y);
        float dFade = smoothstep(minDist, 420.0, d) * smoothstep(2600.0, 820.0, d);

        // 1. Distant RDR2-style sub-cloud crepuscular god-rays peeking ONLY through true 3D cloud gaps
        if (sunShaftGate > 0.01 && skyCov > 0.32 && skyCov < 0.86 && curtain < 0.45) {
            float toCloudDist = (float(cloudAltitude) - rPos.y) / max(worldLightVec.y, 0.14);
            vec3 sunCloudPos = rPos + worldLightVec * toCloudDist;
            float dummyLow, dummyHigh;
            float cAtGap = GetCloudNoise(sunCloudPos, cloudAltitude, d, sunCloudPos.y - cameraPos.y, skyCov, skyTower, curtain, dummyLow, dummyHigh);
            float cNearMass1 = GetCloudNoise(sunCloudPos + vec3(85.0, 0.0, 55.0), cloudAltitude, d, sunCloudPos.y - cameraPos.y, skyCov, skyTower, curtain, dummyLow, dummyHigh);
            float cNearMass2 = GetCloudNoise(sunCloudPos - vec3(75.0, 0.0, 50.0), cloudAltitude, d, sunCloudPos.y - cameraPos.y, skyCov, skyTower, curtain, dummyLow, dummyHigh);
            // A ray exists ONLY if the sun vector passes through a cloud gap (cAtGap < 0) bordered by actual 3D cloud mass
            float isGap = smoothstep(0.01, -0.04, cAtGap);
            float nearCloud = smoothstep(-0.01, 0.07, max(cNearMass1, cNearMass2)) * smoothstep(-0.06, 0.03, min(cNearMass1, cNearMass2));
            float brokenDeckFactor = smoothstep(0.32, 0.46, skyCov) * smoothstep(0.86, 0.68, skyCov);
            float rayStrength = isGap * nearCloud * brokenDeckFactor * sunShaftGate * vFade * dFade;
            if (rayStrength > 0.004) {
                float rayAlpha = clamp(rayStrength * (0.22 + 0.30 * pow(max0(VdotS), 3.0)), 0.0, 0.32);
                float twilightWarm = clamp01(invNoonFactor * sunVisibility);
                vec3 shaftCol = mix(cloudLightColor * vec3(1.12, 1.04, 0.90), cloudLightColor * vec3(1.32, 0.88, 0.58), twilightWarm * 0.65);
                shaftCol *= pow2(1.0 - maxBlindnessDarkness);
                accum.rgb = mix(accum.rgb, shaftCol, (1.0 - min1(accum.a)) * 0.85);
                accum.a = min(0.75, accum.a + rayAlpha * (1.0 - accum.a));
            }
        }

        // 2. Distant rain/snow/sand/ash bands slanting down from dark storm cloud bases
        if (curtain < 0.10) continue;

        float skyAerosolRaw = skyW.a * 4.0;
        int skyAerosol = clamp(int(floor(skyAerosolRaw)), 0, 3);
        float skyFlash = clamp((fract(skyAerosolRaw) - 0.08) / 0.84, 0.0, 1.0);

        vec2 worldXZ = GetSkycraftWorldXZ(relXZ);
        vec3 streakPos = vec3(worldXZ.x * 0.0014, rPos.y * 0.00035 - syncedTime * 0.012, worldXZ.y * 0.0014);
        #if CLOUD_QUALITY >= 2
            float streak = Noise3D(streakPos) * 0.65 + Noise3D(streakPos * 2.5) * 0.35;
        #else
            float streak = texture2DLod(noisetex, streakPos.xy, 0.0).b;
        #endif

        float shaft = smoothstep(0.10, 0.76, curtain) * smoothstep(0.30, 0.68, streak);
        float stepAlpha = clamp(shaft * vFade * dFade * (0.24 + 0.20 * skyTower), 0.0, 0.42);
        if (stepAlpha < 0.004) continue;

        vec3 baseLit = cloudAmbientColor * 0.54 + cloudLightColor * 0.16;
        vec3 curtainCol = mix(cloudSkyColor, baseLit * vec3(0.62, 0.68, 0.76), 0.74);
        if (skyAerosol == 1) {
            curtainCol = mix(cloudSkyColor, (cloudAmbientColor + cloudLightColor * 0.30) * vec3(0.88, 0.92, 0.98), 0.78);
        } else if (skyAerosol == 2) {
            curtainCol = mix(cloudSkyColor, (cloudAmbientColor + cloudLightColor * 0.35) * vec3(0.94, 0.74, 0.48), 0.82);
        } else if (skyAerosol == 3) {
            curtainCol = mix(cloudSkyColor, cloudAmbientColor * vec3(0.42, 0.34, 0.32), 0.85);
        }

        if (skyFlash > 0.01) {
            vec3 flashTint = (skyAerosol == 3) ? vec3(1.0, 0.45, 0.30) : vec3(0.65, 0.78, 1.05);
            curtainCol += flashTint * (skyFlash * 0.85 * max(0.35, length(cloudAmbientColor)));
        }

        curtainCol *= pow2(1.0 - maxBlindnessDarkness);
        accum.rgb = mix(accum.rgb, curtainCol, 1.0 - min1(accum.a));
        accum.a = min(0.80, accum.a + stepAlpha * (1.0 - accum.a));
    }

    return accum;
}

vec4 GetVolumetricClouds(int cloudAltitude, float distanceThreshold, inout float cloudLinearDepth, float skyFade, float skyMult0, vec3 cameraPos, vec3 nPlayerPos, float lViewPosM, float VdotS, float VdotU, float dither) {
    vec4 volumetricClouds = vec4(0.0);

    vec4 rainCurtains = GetSkycraftRainCurtains(cloudAltitude, distanceThreshold, skyFade, skyMult0, cameraPos, nPlayerPos, lViewPosM, VdotS, VdotU, dither);

    float higherPlaneAltitude = cloudAltitude + cloudStretch * 3.05;
    float lowerPlaneAltitude  = cloudAltitude - cloudStretch * 1.75;

    float lowerPlaneDistance  = (lowerPlaneAltitude - cameraPos.y) / nPlayerPos.y;
    float higherPlaneDistance = (higherPlaneAltitude - cameraPos.y) / nPlayerPos.y;
    float minPlaneDistance = min(lowerPlaneDistance, higherPlaneDistance);
          minPlaneDistance = max(minPlaneDistance, 0.0);
    float maxPlaneDistance = max(lowerPlaneDistance, higherPlaneDistance);
    if (maxPlaneDistance < 0.0) return rainCurtains;
    float planeDistanceDif = maxPlaneDistance - minPlaneDistance;

    float aboveClouds = clamp01((cameraPos.y - lowerPlaneAltitude) * 0.01);
    float skyFadeM = mix(skyFade, 1.0, 0.4 * max(aboveClouds, rainFactor2));

    #ifndef DEFERRED1
        float stepMult = 64.0;
    #elif CLOUD_QUALITY == 1
        float stepMult = 16.0;
    #elif CLOUD_QUALITY == 2
        float stepMult = 28.0;
    #elif CLOUD_QUALITY == 3
        float stepMult = 16.0;
    #endif

    #if CLOUD_UNBOUND_SIZE_MULT > 100
        stepMult = stepMult / sqrt(float(CLOUD_UNBOUND_SIZE_MULT_M));
    #endif

    int sampleCount = int(planeDistanceDif / stepMult + dither + 1);
    sampleCount = min(sampleCount, 52);
    vec3 traceAdd = nPlayerPos * stepMult;
    vec3 tracePos = cameraPos + minPlaneDistance * nPlayerPos;
    tracePos += traceAdd * dither;
    tracePos.y -= traceAdd.y;

    float firstHitPos = 0.0;
    float VdotSM1 = max0(sunVisibility > 0.5 ? VdotS : -VdotS);
    float VdotSM1M = VdotSM1 * invRainFactor;
    float VdotSM2 = pow2(VdotSM1) * abs(sunVisibility - 0.5) * 2.0;
    float VdotSM3 = VdotSM2 * (2.8 + rainFactor) + 1.5 * rainFactor;
    float VdotSM4 = pow(VdotSM1M, 80.0) * sunVisibility;

    #ifdef FIX_AMD_REFLECTION_CRASH
        sampleCount = min(sampleCount, 30); //BFARC
    #endif

    for (int i = 0; i < sampleCount; i++) {
        tracePos += traceAdd * (1.0 + 0.018 * float(i));

        if (tracePos.y < lowerPlaneAltitude || tracePos.y > higherPlaneAltitude) break;

        vec3 cloudPlayerPos = tracePos - cameraPos;
        float lTracePos = length(cloudPlayerPos);
        float lTracePosXZ = length(cloudPlayerPos.xz);
        float cloudMult = 1.0;
        if (lTracePosXZ > distanceThreshold) break;
        if (lTracePos > lViewPosM) {
            if (skyFade < 0.7) continue;
            else cloudMult = skyMult0;
        }

        vec4 skyW = SampleSkycraftWeather(cloudPlayerPos.xz);
        float skyCov = skyW.r;
        float skyTower = skyW.g;
        float skyCurtain = skyW.b;

        float localLower = lowerPlaneAltitude;
        float localHigher = higherPlaneAltitude;
        float cloudNoise = GetCloudNoise(tracePos, cloudAltitude, lTracePosXZ, cloudPlayerPos.y, skyCov, skyTower, skyCurtain, localLower, localHigher);

        if (cloudNoise > 0.00001) {
            #if defined CLOUD_CLOSED_AREA_CHECK && SHADOW_QUALITY > -1 && !defined VOXY_PATCH
                float shadowLength = shadowDistance * 0.9166667; //consistent08JJ622
                if (shadowLength < lTracePos)
                if (GetShadowOnCloud(tracePos, cameraPos, cloudAltitude, lowerPlaneAltitude, higherPlaneAltitude)) {
                    if (eyeBrightness.y != 240) continue;
                }
            #endif

            if (firstHitPos < 1.0) {
                firstHitPos = lTracePos;
                #if CLOUD_QUALITY == 1 && defined DEFERRED1
                    tracePos.y += 4.0 * (texture2DLod(noisetex, tracePos.xz * cloudNarrowness * 16.0, 0.0).r - 0.5);
                #endif
            }

            float effectiveCov = max(skyCov, max(rainFactor * 0.86, skyCurtain * 0.80));
            // Contrast light fair-weather cumulus vs dark storm/snow/overcast clouds
            float stormDarkness = clamp01(smoothstep(0.50, 0.86, effectiveCov) * 0.65 + skyTower * 0.65 + skyCurtain * 0.45);

            float opacityFactor = min1(cloudNoise * (7.8 + 4.5 * stormDarkness));
            // Beer-Powder self-shadowing gives puffy cumulus edges crisp silver linings and sculpted 3D volume
            float beerPowder = 1.0 - 0.42 * exp(-cloudNoise * 18.0) * (1.0 - opacityFactor);

            float cloudHeight01 = clamp01((tracePos.y - localLower) / max(12.0, localHigher - localLower));
            float cloudShading = pow(cloudHeight01, 0.82 + 0.65 * stormDarkness) * beerPowder;
            cloudShading *= 1.0 - 0.32 * stormDarkness * (1.0 - cloudHeight01);
            // Strong Mie forward-scattering toward the sun/moon + silver lining on translucent cloud fringes
            float silverAtten = 1.0 - 0.65 * stormDarkness;
            float silverLining = ((0.34 * VdotSM3 + 0.68 * pow(VdotSM1, 6.0)) * (1.0 - 0.72 * opacityFactor) + VdotSM4 * 1.15 * (1.0 - opacityFactor)) * silverAtten;
            cloudShading *= 1.0 + silverLining;

            vec3 cloudSkyColor = GetSky(VdotU, VdotS, dither, isEyeInWater == 0, false);
            #ifdef ATM_COLOR_MULTS
                cloudSkyColor *= sqrtAtmColorMult;
            #endif

            float skyAerosolRaw = skyW.a * 4.0;
            int skyAerosol = clamp(int(floor(skyAerosolRaw)), 0, 3);
            float skyFlash = clamp((fract(skyAerosolRaw) - 0.08) / 0.84, 0.0, 1.0);

            // React richly to sun angle, twilight warmth, and dark vs light cloud state
            float twilightFactor = clamp01(invNoonFactor * sunVisibility);
            vec3 sunRimColor = mix(cloudLightColor, cloudLightColor * vec3(1.18, 0.92, 0.76), twilightFactor * 0.55);
            vec3 fairBaseColor = mix(cloudAmbientColor * 0.88, cloudSkyColor * 0.64 + cloudAmbientColor * 0.36, 0.38);
            vec3 darkTint = (skyAerosol == 1) ? mix(vec3(0.62, 0.66, 0.74), vec3(0.46, 0.50, 0.58), skyTower)
                                              : mix(vec3(0.54, 0.58, 0.65), vec3(0.38, 0.42, 0.49), skyTower);
            vec3 darkStormBaseColor = fairBaseColor * darkTint;
            vec3 baseShadowColor = mix(fairBaseColor, darkStormBaseColor, stormDarkness * (1.0 - 0.35 * cloudHeight01));
            float directSunScale = 1.0 - 0.52 * stormDarkness;
            vec3 colorSample = baseShadowColor * (0.42 + 0.58 * cloudShading)
                             + sunRimColor * (cloudShading * (1.08 + 0.35 * VdotSM2) * directSunScale + 0.24 * silverLining);

            if (skyAerosol == 2) {
                colorSample = mix(colorSample, colorSample * vec3(1.12, 0.88, 0.62), clamp01(skyTower * 0.85));
            } else if (skyAerosol == 3) {
                colorSample = mix(colorSample, colorSample * vec3(0.58, 0.46, 0.42), clamp01(skyTower * 0.90));
            }

            if (skyFlash > 0.005) {
                vec3 flashTint = (skyAerosol == 3) ? vec3(1.05, 0.45, 0.30) : vec3(0.68, 0.80, 1.12);
                float internalGlow = (0.45 + 0.55 * (1.0 - abs(cloudHeight01 - 0.55) * 1.5)) * (0.45 + 0.55 * opacityFactor);
                float lumScale = max(0.38, length(cloudAmbientColor) + length(cloudLightColor) * 0.45);
                colorSample += flashTint * (skyFlash * internalGlow * 2.5 * lumScale);
            }

            float distanceRatio = clamp((distanceThreshold - lTracePosXZ) / distanceThreshold, 0.0, 1.0);
            float cloudDistanceFactor = smoothstep(0.0, 0.82, distanceRatio);
            float cloudFogFactor = pow2(pow1_5(distanceRatio)) * 0.74;
            cloudFogFactor = mix(1.0, cloudFogFactor, skyFadeM);
            colorSample *= 1.0 - 0.18 * (1.0 - skyFadeM) * max(sunVisibility2, nightFactor);
            colorSample = mix(cloudSkyColor, colorSample, cloudFogFactor * 0.76);
            colorSample *= pow2(1.0 - maxBlindnessDarkness);

            volumetricClouds.rgb = mix(volumetricClouds.rgb, colorSample, 1.0 - min1(volumetricClouds.a));
            // Solid cloud opacity without artificial sun-hole punching so clouds truly block the sun disk
            volumetricClouds.a += opacityFactor * pow(cloudDistanceFactor, 0.45) * cloudMult;

            if (volumetricClouds.a > 0.94) {
                volumetricClouds.a = 1.0;
                break;
            }
        }
    }

    if (volumetricClouds.a > 0.5) cloudLinearDepth = sqrt(firstHitPos / renderDistance);

    volumetricClouds.a *= 0.55 + 0.45 * skyFadeM;

    if (rainCurtains.a > 0.001) {
        vec3 combinedRgb = mix(rainCurtains.rgb, volumetricClouds.rgb, volumetricClouds.a);
        float combinedA = clamp01(volumetricClouds.a + rainCurtains.a * (1.0 - volumetricClouds.a));
        volumetricClouds = vec4(combinedRgb, combinedA);
    }

    return volumetricClouds;
}
"""

CLOUD_SHADOWS_GLSL = """#ifndef INCLUDE_CLOUD_SHADOWS
    #define INCLUDE_CLOUD_SHADOWS

    #ifdef CLOUDS_REIMAGINED
        #include "/lib/atmospherics/clouds/cloudCoord.glsl"
    #endif

    float GetCloudShadow(vec3 playerPos) {
        #ifndef OVERWORLD
            return 1.0;
        #endif

        float cloudShadow = 1.0;

        #ifdef CLOUDS_REIMAGINED
            vec3 worldPos = playerPos + cameraPosition;
            float EdotL = dot(eastVec, lightVec);
            float EdotLM = tan(acos(EdotL));

            #if SUN_ANGLE != 0
                float NVdotLM = tan(acos(dot(northVec, lightVec)));
            #endif

            float distToCloudLayer1 = cloudAlt1i - worldPos.y;
            vec3 cloudOffset1 = vec3(distToCloudLayer1 / EdotLM, 0.0, 0.0);
            #if SUN_ANGLE != 0
                cloudOffset1.z += distToCloudLayer1 / NVdotLM;
            #endif
            vec2 cloudPos1 = GetRoundedCloudCoord(ModifyTracePos(worldPos + cloudOffset1, cloudAlt1i).xz, 0.35);

            #ifndef COMPOSITE
                float cloudSample = texture2D(gaux4, cloudPos1).b;
            #else
                float cloudSample = texture2D(cloudWaterTex, cloudPos1).b;
            #endif
            cloudSample *= clamp(distToCloudLayer1 * 0.1, 0.0, 1.0);
            cloudSample *= sqrt3(1.0 - abs(EdotL));
            cloudShadow = 1.0 - 0.85 * cloudSample;
        #else
            // Use the EXACT same cameraPos wrapping and Noise3D FBM as mainClouds.glsl + unboundClouds.glsl
            const float csNarrowness = 0.00012;
            #if IRIS_VERSION >= 10800
                #if CLOUD_UNBOUND_SIZE_MULT == 100
                    float modFactor = 1.0 / csNarrowness;
                #else
                    #define CLOUD_UNBOUND_SIZE_MULT_M CLOUD_UNBOUND_SIZE_MULT * 0.01
                    float modFactor = 1.0 / (csNarrowness * CLOUD_UNBOUND_SIZE_MULT_M);
                #endif
                int modFactorM = int(modFactor);
                vec2 cameraPositionBIM = cameraPositionInt.xz - modFactorM * (cameraPositionInt.xz / modFactorM);
                vec3 cameraPos = vec3(
                    cameraPositionBIM.x + cameraPositionBestFract.x,
                    cameraPosition.y,
                    cameraPositionBIM.y + cameraPositionBestFract.z
                );
                vec2 worldCamXZ = vec2(cameraPositionInt.xz) + cameraPositionBestFract.xz;
            #else
                vec3 cameraPos = cameraPosition;
                vec2 worldCamXZ = cameraPosition.xz;
            #endif

            vec3 worldLightVec = normalize(mat3(gbufferModelViewInverse) * lightVec);
            float distToCloud = (float(cloudAlt1i) - (cameraPos.y + playerPos.y)) / max(abs(worldLightVec.y), 0.12);
            distToCloud = clamp(distToCloud, -1200.0, 2800.0);

            vec2 hitOffsetXZ = playerPos.xz + worldLightVec.xz * distToCloud;
            vec2 hitWorldXZ = worldCamXZ + hitOffsetXZ;
            vec4 skyWShadow = texture2DLod(skycraftWeatherTex, fract(hitWorldXZ / 8192.0), 0.0);
            float skyCov = skyWShadow.r;
            float skyTower = skyWShadow.g;
            float skyCurtain = skyWShadow.b;
            float effectiveCov = max(skyCov, max(rainFactor * 0.86, skyCurtain * 0.80));

            if (effectiveCov <= 0.06) {
                return 1.0;
            }

            vec3 tracePos = vec3(cameraPos.x + hitOffsetXZ.x, float(cloudAlt1i), cameraPos.z + hitOffsetXZ.y);
            vec3 tracePosM = tracePos * csNarrowness;
            float wind = 0.0006;
            #if CLOUD_SPEED_MULT == 100
                #define CLOUD_SPEED_MULT_M CLOUD_SPEED_MULT * 0.01
                wind *= syncedTime;
            #else
                #define CLOUD_SPEED_MULT_M CLOUD_SPEED_MULT * 0.01
                wind *= frameTimeCounter * CLOUD_SPEED_MULT_M;
            #endif
            #if CLOUD_UNBOUND_SIZE_MULT != 100
                #define CLOUD_UNBOUND_SIZE_MULT_M CLOUD_UNBOUND_SIZE_MULT * 0.01
                tracePosM *= CLOUD_UNBOUND_SIZE_MULT_M;
                wind *= CLOUD_UNBOUND_SIZE_MULT_M;
            #endif

            float noise = 0.0;
            float currentPersist = 1.0;
            float total = 0.0;
            for (int i = 0; i < 3; i++) {
                noise += Noise3D(tracePosM - vec3(0.0, 0.0, wind)) * currentPersist;
                total += currentPersist;
                tracePosM *= 2.95;
                wind *= 0.5;
                currentPersist *= 0.52;
            }
            noise = pow2(noise / total);

            float skycraftBaseAdd = mix(0.68, 1.32, smoothstep(0.06, 0.86, effectiveCov))
                                  + 0.25 * skyTower
                                  + 0.14 * skyCurtain;
            float noiseMult = 1.08 * mix(0.82 + CLOUD_UNBOUND_RAIN_ADD * rainFactor, skycraftBaseAdd, 0.90);
            noise *= noiseMult * CLOUD_UNBOUND_AMOUNT;

            float overcastFloor = smoothstep(0.62, 0.90, effectiveCov) * 0.24;
            float cDensity = (noise + overcastFloor) - 0.245;
            float opticalDepth = clamp01(cDensity * (5.2 + 3.2 * skyTower));

            // Sub-cloud rain band attenuation over the ground directly beneath a precipitation curtain
            vec4 localW = texture2DLod(skycraftWeatherTex, fract((worldCamXZ + playerPos.xz) / 8192.0), 0.0);
            float curtainShade = smoothstep(0.12, 0.75, localW.b) * 0.45;
            opticalDepth = clamp01(max(opticalDepth, curtainShade));

            float covFade = smoothstep(0.06, 0.20, effectiveCov);
            float sunElevFade = smoothstep(0.02, 0.14, abs(worldLightVec.y));
            float maxShade = mix(0.72, 0.92, clamp01(effectiveCov * 0.65 + skyTower * 0.65 + skyCurtain * 0.45)) * sunElevFade * covFade;
            cloudShadow = 1.0 - maxShade * smoothstep(0.0, 0.62, opticalDepth);
        #endif

        return cloudShadow;
    }

#endif
"""


def patch_uniforms_glsl(text: str) -> str:
    if "skycraftWeatherTex" in text:
        return text
    target = "uniform sampler2D noisetex;"
    replacement = target + "\r\nuniform sampler2D skycraftWeatherTex;"
    if target not in text:
        raise RuntimeError("Could not find `uniform sampler2D noisetex;` in uniforms.glsl")
    return text.replace(target, replacement, 1)


def patch_shaders_properties(text: str) -> str:
    if "skycraftWeatherTex" in text:
        return text
    target = "    texture.noise=lib/textures/noise.png"
    replacement = (
        target
        + "\r\n    customTexture.skycraftWeatherTex = skycraft:textures/environment/weather_field.png"
    )
    if target not in text:
        raise RuntimeError("Could not find `texture.noise=lib/textures/noise.png` in shaders.properties")
    return text.replace(target, replacement, 1)


def patch_main_clouds_glsl(text: str) -> str:
    old_block = "    float lViewPosM = lViewPos < renderDistance * 1.5 ? lViewPos - 1.0 : 1000000000.0;"
    new_block = (
        "    #ifdef DISTANT_HORIZONS\r\n"
        "        float maxSkycraftTerrainDist = max(float(dhRenderDistance), renderDistance * 1.5);\r\n"
        "        float lViewPosM = (skyFade < 0.995 && lViewPos < maxSkycraftTerrainDist) ? lViewPos - 1.0 : 1000000000.0;\r\n"
        "    #else\r\n"
        "        float lViewPosM = lViewPos < renderDistance * 1.5 ? lViewPos - 1.0 : 1000000000.0;\r\n"
        "    #endif"
    )
    if "maxSkycraftTerrainDist" in text:
        return text
    if old_block not in text:
        raise RuntimeError("Could not find `lViewPosM` line in mainClouds.glsl")
    return text.replace(old_block, new_block, 1)


def patch_volumetric_light_glsl(text: str) -> str:
    # Support both fresh and previously patched volumetricLight.glsl
    if "SKYCRAFT_CREPUSCULAR_SHAFTS" not in text:
        header_target = "vec4 DistortShadow(vec4 shadowpos, float distortFactor) {"
        header_repl = (
            '// SKYCRAFT_CREPUSCULAR_SHAFTS\r\n'
            '#include "/lib/lighting/cloudShadows.glsl"\r\n\r\n'
            + header_target
        )
        if header_target not in text:
            raise RuntimeError("Could not find `DistortShadow` in volumetricLight.glsl")
        text = text.replace(header_target, header_repl, 1)

    new_scene_block = (
        "        vec4 skyCamW = texture2DLod(skycraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        float skyCrepuscularDrive = smoothstep(0.20, 0.46, skyCamW.r) * (1.0 - 0.82 * smoothstep(0.76, 0.92, skyCamW.r));\r\n"
        "        float vlSceneIntensity = isEyeInWater != 1 ? max(vlFactor, 0.62 * skyCrepuscularDrive) : 1.0;"
    )
    old_scene_v1 = (
        "        vec4 skyCamW = texture2DLod(skycraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        float skyCrepuscularDrive = smoothstep(0.04, 0.42, skyCamW.r) * (1.0 - 0.38 * smoothstep(0.88, 0.99, skyCamW.r));\r\n"
        "        float vlSceneIntensity = isEyeInWater != 1 ? max(vlFactor, 0.52 + 0.42 * skyCrepuscularDrive) : 1.0;"
    )
    orig_scene = "        float vlSceneIntensity = isEyeInWater != 1 ? vlFactor : 1.0;"
    if old_scene_v1 in text:
        text = text.replace(old_scene_v1, new_scene_block, 1)
    elif old_scene_v1.replace("\r\n", "\n") in text:
        text = text.replace(old_scene_v1.replace("\r\n", "\n"), new_scene_block, 1)
    elif orig_scene in text:
        text = text.replace(orig_scene, new_scene_block, 1)

    far_target = "        int farSamples = 1;"
    far_repl = "        int farSamples = 7;"
    if far_target in text:
        text = text.replace(far_target, far_repl, 1)

    dh_target = "            maxDistance = renderDistance * 0.5; // Covers lod chunks"
    dh_repl = "            maxDistance = max(min(float(dhRenderDistance) * 0.65, 1800.0), renderDistance * 0.75); // Covers DH lod chunks for distant cloud rays"
    if dh_target in text:
        text = text.replace(dh_target, dh_repl, 1)

    new_crep_block = (
        "        #ifdef OVERWORLD\r\n"
        "            if (isEyeInWater == 0) {\r\n"
        "                float cloudTrans = GetCloudShadow(scenePos);\r\n"
        "                float cloudActive = smoothstep(0.18, 0.38, skyCamW.r);\r\n"
        "                float overcastDim = 1.0 - 0.78 * smoothstep(0.75, 0.92, skyCamW.r);\r\n"
        "                float shaftContrast = smoothstep(0.24, 0.88, cloudTrans);\r\n"
        "                float distBoost = smoothstep(85.0, 380.0, nextDistance);\r\n"
        "                float cloudMod = mix(1.0, (0.08 + 0.92 * shaftContrast) * (0.95 + 0.55 * distBoost * shaftContrast), cloudActive);\r\n"
        "                localDensity *= cloudMod * overcastDim;\r\n"
        "            }\r\n"
        "        #endif\r\n\r\n"
    )
    old_crep_v1 = (
        "        #ifdef OVERWORLD\r\n"
        "            if (isEyeInWater == 0) {\r\n"
        "                float cloudTrans = GetCloudShadow(scenePos);\r\n"
        "                float shaftContrast = smoothstep(0.16, 0.86, cloudTrans);\r\n"
        "                float distBoost = smoothstep(40.0, 260.0, nextDistance);\r\n"
        "                localDensity *= mix(0.22 + 0.78 * shaftContrast, shaftContrast * (1.18 + 0.42 * distBoost), 0.85);\r\n"
        "            }\r\n"
        "        #endif\r\n\r\n"
    )
    smoke_anchor = "        #ifdef LIGHTSHAFT_SMOKE"
    if old_crep_v1 in text:
        text = text.replace(old_crep_v1, new_crep_block, 1)
    elif old_crep_v1.replace("\r\n", "\n") in text:
        text = text.replace(old_crep_v1.replace("\r\n", "\n"), new_crep_block, 1)
    elif "float cloudTrans = GetCloudShadow(scenePos);" not in text and smoke_anchor in text:
        text = text.replace(smoke_anchor, new_crep_block + smoke_anchor, 1)

    return text


def patch_gbuffers_skytextured_glsl(text: str) -> str:
    if "SKYCRAFT_ROUND_SUN" in text:
        return text
    target = "            if (isSun) {"
    repl = (
        "            // SKYCRAFT_ROUND_SUN: discard vanilla square sun quad; rendered analytically in gbuffers_skybasic.glsl\r\n"
        "            if (isSun) {\r\n"
        "                discard;\r\n"
    )
    if target not in text:
        raise RuntimeError("Could not find `if (isSun) {` in gbuffers_skytextured.glsl")
    return text.replace(target, repl, 1)


def patch_gbuffers_skybasic_glsl(text: str) -> str:
    # Remove any previous SKYCRAFT_ROUND_SUN_DISK block first so we can upgrade cleanly
    text = re.sub(
        r"\s*// SKYCRAFT_ROUND_SUN_DISK:.*?\n        \}",
        "",
        text,
        flags=re.DOTALL,
    )
    target = "        vec2 starCoord = GetStarCoord(viewPos.xyz, 0.5);\r\n        color.rgb += GetStars(starCoord, VdotU, VdotS);"
    if target not in text:
        target = "        vec2 starCoord = GetStarCoord(viewPos.xyz, 0.5);\n        color.rgb += GetStars(starCoord, VdotU, VdotS);"
    solar_disk_glsl = (
        target
        + "\r\n\r\n"
        "        // SKYCRAFT_ROUND_SUN_DISK: Analytical round solar disk with limb darkening, granulation & overcast/cloud occlusion\r\n"
        "        if (VdotS > 0.0 && sunVisibility > 0.001) {\r\n"
        "            vec4 skySunW = texture2DLod(skycraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "            float skyOvercast = clamp(max(rainFactor, smoothstep(0.55, 0.85, skySunW.r) * 0.92 + skySunW.g * 0.55 + skySunW.b * 0.65), 0.0, 1.0);\r\n"
        "            float sunHoriz = GetHorizonFactor(SdotU);\r\n"
        "            float sunRainAtten = (1.0 - 0.92 * skyOvercast) * (1.0 - 0.88 * rainFactor);\r\n"
        "            float cosRadius = 0.99915;\r\n"
        "            float coronaInner = pow(max0(VdotS), 180.0) * 1.25;\r\n"
        "            float coronaOuter = pow(max0(VdotS), 28.0) * 0.24;\r\n"
        "            vec3 sunCoreCol = mix(vec3(1.0, 0.48, 0.16), vec3(1.0, 0.95, 0.84), smoothstep(0.0, 0.45, SdotU));\r\n"
        "            vec3 sunLimbCol = mix(vec3(0.95, 0.25, 0.06), vec3(1.0, 0.72, 0.36), smoothstep(0.0, 0.45, SdotU));\r\n"
        "            color.rgb += sunLimbCol * (coronaInner + coronaOuter) * sunVisibility * sunHoriz * sunRainAtten;\r\n"
        "            if (VdotS > cosRadius) {\r\n"
        "                float r01 = clamp01((1.0 - VdotS) / (1.0 - cosRadius));\r\n"
        "                float mu = sqrt(max0(1.0 - r01));\r\n"
        "                float limbDarkening = 0.35 + 0.65 * mu;\r\n"
        "                float diskEdge = smoothstep(1.0, 0.78, r01);\r\n"
        "                vec2 granUv = nViewPos.xy * 18.0 + vec2(syncedTime * 0.002, -syncedTime * 0.0015);\r\n"
        "                float gran = 0.92 + 0.16 * texture2DLod(noisetex, granUv, 0.0).r;\r\n"
        "                vec3 diskCol = mix(sunLimbCol, sunCoreCol, mu) * limbDarkening * gran * 12.0;\r\n"
        "                color.rgb = mix(color.rgb, diskCol, diskEdge * sunVisibility * sunHoriz * sunRainAtten);\r\n"
        "            }\r\n"
        "        }"
    )
    if target not in text:
        raise RuntimeError("Could not find `GetStars` anchor in gbuffers_skybasic.glsl")
    return text.replace(target, solar_disk_glsl, 1)


def patch_sky_glsl(text: str) -> str:
    old_glare = "                glare *= 0.7;"
    new_glare = "                glare *= 0.46; // SKYCRAFT_GLARE_BALANCE"
    if "SKYCRAFT_GLARE_BALANCE" in text:
        return text
    if old_glare in text:
        return text.replace(old_glare, new_glare, 1)
    return text


def patch_light_and_ambient_colors_glsl(text: str) -> str:
    if "SKYCRAFT_OVERCAST_LIGHTING" in text:
        return text
    target = (
        "        vec3 lightColor   = mix(clearLightColor, rainLightColor, rainFactor);\r\n"
        "        vec3 ambientColor = mix(clearAmbientColor, rainAmbientColor, rainFactor);"
    )
    if target not in text:
        target = target.replace("\r\n", "\n")
    repl = (
        "        // SKYCRAFT_OVERCAST_LIGHTING: Attenuate overworld sunlight and transition to overcast ambient during snow/overcast/storms\r\n"
        "        vec4 skyLightW = texture2DLod(skycraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        float skyOvercastFactor = clamp(max(rainFactor, smoothstep(0.56, 0.88, skyLightW.r) * 0.86 + skyLightW.g * 0.45 + skyLightW.b * 0.55), 0.0, 1.0);\r\n"
        "        vec3 lightColor   = mix(clearLightColor, rainLightColor, skyOvercastFactor);\r\n"
        "        vec3 ambientColor = mix(clearAmbientColor, rainAmbientColor, skyOvercastFactor);"
    )
    if target in text:
        return text.replace(target, repl, 1)
    return text


def patch_cloud_colors_glsl(text: str) -> str:
    if "SKYCRAFT_CLOUD_COLORS" in text:
        return text
    target = "vec3 cloudRainColor = mix(nightMiddleSkyColor, dayMiddleSkyColor, sunFactor);"
    repl = (
        "// SKYCRAFT_CLOUD_COLORS\r\n"
        "vec4 skyCloudColW = texture2DLod(skycraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "float skyCloudRainF = clamp(max(rainFactor, smoothstep(0.58, 0.88, skyCloudColW.r) * 0.85 + skyCloudColW.g * 0.45 + skyCloudColW.b * 0.55), 0.0, 1.0);\r\n"
        "vec3 cloudRainColor = mix(nightMiddleSkyColor, dayMiddleSkyColor, sunFactor);"
    )
    if target in text:
        text = text.replace(target, repl, 1)
        text = text.replace("cloudRainColor * 0.5, rainFactor", "cloudRainColor * 0.5, skyCloudRainF")
        text = text.replace("noonFactor * rainFactor", "noonFactor * skyCloudRainF")
    return text


def patch_sky_colors_glsl(text: str) -> str:
    if "SKYCRAFT_SKY_OVERCAST" in text:
        return text
    target = "        float invRainStrength2 = (1.0 - rainStrength) * (1.0 - rainStrength);"
    repl = (
        "        // SKYCRAFT_SKY_OVERCAST: Desaturate sky dome into overcast slate during snow/overcast/storms\r\n"
        "        vec4 skyDomeW = texture2DLod(skycraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        float skyEffRain = clamp(max(rainStrength, smoothstep(0.58, 0.88, skyDomeW.r) * 0.88 + skyDomeW.g * 0.45 + skyDomeW.b * 0.55), 0.0, 1.0);\r\n"
        "        float invRainStrength2 = (1.0 - skyEffRain) * (1.0 - skyEffRain);"
    )
    if target in text:
        text = text.replace(target, repl, 1)
        text = text.replace(
            "vec3 noonUpSkyColor     = pow(skyColorM, vec3(2.9)) * (vec3(0.85, 0.92, 0.81) + rainFactor * nuscWeatherM);",
            "vec3 noonUpSkyColor     = pow(skyColorM, vec3(2.9)) * (vec3(0.85, 0.92, 0.81) + max(rainFactor, skyEffRain) * nuscWeatherM);",
        )
        text = text.replace(
            "vec3 noonMiddleSkyColor = skyColorM * (vec3(1.4, 1.3, 1.3) + rainFactor * (nmscWeatherM + nmscRainM + nmscSnowM + nmscDryM))",
            "vec3 noonMiddleSkyColor = skyColorM * (vec3(1.4, 1.3, 1.3) + max(rainFactor, skyEffRain) * (nmscWeatherM + nmscRainM + nmscSnowM + nmscDryM))",
        )
        text = text.replace(
            "vec3 noonDownSkyColor   = skyColorM * (vec3(0.9) + rainFactor * (ndscWeatherM + ndscRainM + ndscSnowM + ndscDryM))",
            "vec3 noonDownSkyColor   = skyColorM * (vec3(0.9) + max(rainFactor, skyEffRain) * (ndscWeatherM + ndscRainM + ndscSnowM + ndscDryM))",
        )
    return text


def patch_main_lighting_glsl(text: str) -> str:
    if "SKYCRAFT_REGIONAL_LIGHT_ATTEN" in text:
        return text
    target = "        ambientMult = mix(lightmapYM, pow2(lightmapYM) * lightmapYM, rainFactor);"
    repl = (
        "        // SKYCRAFT_REGIONAL_LIGHT_ATTEN: Impact overworld light beneath dense clouds and distant rain bands\r\n"
        "        vec4 skyLocalLightW = texture2DLod(skycraftWeatherTex, fract((playerPos.xz + cameraPosition.xz) / 8192.0), 0.0);\r\n"
        "        float localStormShade = clamp(smoothstep(0.62, 0.92, skyLocalLightW.r) * 0.45 + skyLocalLightW.g * 0.35 + skyLocalLightW.b * 0.45, 0.0, 0.85);\r\n"
        "        float effRainLight = max(rainFactor, localStormShade);\r\n"
        "        ambientMult = mix(lightmapYM, pow2(lightmapYM) * lightmapYM, effRainLight) * (1.0 - 0.24 * localStormShade * lightmapYM);"
    )
    if target in text:
        return text.replace(target, repl, 1)
    return text


def patch_waving_blocks_glsl(text: str) -> str:
    if "SKYCRAFT_WIND_WAVING" in text:
        return text
    old_wave = "    float wavingIntensity = WAVING_I * mix(1.0, WAVING_I_RAIN_MULT_M, rainFactor);"
    new_wave = (
        "    // SKYCRAFT_WIND_WAVING: Drive plant and leaf sway from live Skycraft weather & wind\r\n"
        "    vec4 skyWindW = texture2DLod(skycraftWeatherTex, fract(pos.xz / 8192.0), 0.0);\r\n"
        "    float skyWindFactor = clamp(max(rainFactor, skyWindW.r * 0.55 + skyWindW.g * 0.85 + skyWindW.b * 0.70), 0.0, 1.0);\r\n"
        "    vec3 windGust = GetRawWave(pos * 0.65, frameTimeCounter * 235.0 * WAVING_SPEED) * (0.55 * skyWindFactor);\r\n"
        "    wave += windGust;\r\n"
        "    float wavingIntensity = WAVING_I * (1.28 + 0.95 * skyWindFactor) * mix(1.0, WAVING_I_RAIN_MULT_M, max(rainFactor, skyWindFactor * 0.65));"
    )
    if old_wave in text:
        text = text.replace(old_wave, new_wave, 1)
    # Allow leaves in snowy biomes to sway with the wind instead of freezing completely
    text = text.replace(
        "    wave *= 1.0 - inSnowy; // Leaves with snow on top look wrong",
        "    wave *= 1.0 - 0.30 * inSnowy; // Keep snowy spruce & pine leaves swaying in Skyrim wind",
    )
    return text


def patch_block_properties(text: str) -> str:
    if "skycraft:nirnroot" in text:
        return text
    foliage_anchor = " cactus_flower closed_eyeblossom"
    if foliage_anchor in text:
        text = text.replace(foliage_anchor, foliage_anchor + " " + SKYCRAFT_WAVING_FOLIAGE, 1)

    leaves_f_anchor = "pale_oak_leaves:waterlogged=false"
    if leaves_f_anchor in text:
        text = text.replace(leaves_f_anchor, leaves_f_anchor + " " + SKYCRAFT_WAVING_LEAVES_FALSE, 1)

    leaves_t_anchor = "pale_oak_leaves:waterlogged=true"
    if leaves_t_anchor in text:
        text = text.replace(leaves_t_anchor, leaves_t_anchor + " " + SKYCRAFT_WAVING_LEAVES_TRUE, 1)

    hanging_anchor = "cobweb wildflowers"
    if hanging_anchor in text:
        text = text.replace(hanging_anchor, hanging_anchor + " skycraft:hanging_moss", 1)

    upper_anchor = "large_fern:half=upper"
    if upper_anchor in text:
        text = text.replace(upper_anchor, upper_anchor + " skycraft:swamp_reeds:half=upper", 1)

    return text


def patch_shaderpack_zip(zip_path: pathlib.Path) -> None:
    if not zip_path.exists():
        return
    with tempfile.NamedTemporaryFile(delete=False, suffix=".zip", dir=str(zip_path.parent)) as tmp:
        tmp_path = pathlib.Path(tmp.name)

    try:
        with zipfile.ZipFile(zip_path, "r") as zin, zipfile.ZipFile(
            tmp_path, "w", compression=zipfile.ZIP_DEFLATED
        ) as zout:
            for item in zin.infolist():
                raw = zin.read(item.filename)
                if item.filename == "shaders/lib/uniforms.glsl":
                    raw = patch_uniforms_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/shaders.properties":
                    raw = patch_shaders_properties(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/atmospherics/clouds/mainClouds.glsl":
                    raw = patch_main_clouds_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/atmospherics/clouds/unboundClouds.glsl":
                    raw = UNBOUND_CLOUDS_GLSL.replace("\n", "\r\n").encode("utf-8")
                elif item.filename == "shaders/lib/lighting/cloudShadows.glsl":
                    raw = CLOUD_SHADOWS_GLSL.replace("\n", "\r\n").encode("utf-8")
                elif item.filename == "shaders/lib/atmospherics/volumetricLight/volumetricLight.glsl":
                    raw = patch_volumetric_light_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/program/gbuffers_skytextured.glsl":
                    raw = patch_gbuffers_skytextured_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/program/gbuffers_skybasic.glsl":
                    raw = patch_gbuffers_skybasic_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/atmospherics/sky.glsl":
                    raw = patch_sky_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/colors/lightAndAmbientColors.glsl":
                    raw = patch_light_and_ambient_colors_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/colors/cloudColors.glsl":
                    raw = patch_cloud_colors_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/colors/skyColors.glsl":
                    raw = patch_sky_colors_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/lighting/mainLighting.glsl":
                    raw = patch_main_lighting_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/materials/materialMethods/wavingBlocks.glsl":
                    raw = patch_waving_blocks_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/block.properties":
                    raw = patch_block_properties(raw.decode("utf-8")).encode("utf-8")
                zout.writestr(item, raw)
        shutil.move(str(tmp_path), str(zip_path))
        print(f"Patched shaderpack zip: {zip_path}")
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)


def write_txt_configs() -> None:
    for p in TXT_CONFIG_PATHS:
        if p.parent.exists():
            p.write_text(SHADER_TXT_CONTENT, encoding="utf-8")
            print(f"Updated shader config: {p}")


def main(argv: list[str]) -> int:
    targets = [pathlib.Path(a) for a in argv[1:]] if len(argv) > 1 else DEFAULT_ZIPS
    for zpath in targets:
        patch_shaderpack_zip(zpath)
    write_txt_configs()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
