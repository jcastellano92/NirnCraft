#!/usr/bin/env python3
"""
Patches ComplementaryReimagined_r5.9.3.zip and its .txt option files so that:
1. Oculus binds `skycraft:textures/environment/weather_field.png` to `uniform sampler2D skycraftWeatherTex;`
   (updated dynamically in-game by `WeatherFieldTexture.java` from `WeatherPhysics`, `RegionalWeather`,
   `WeatherFront`, and `DistantStorms`).
2. `mainClouds.glsl` preserves Distant Horizons (`dhRenderDistance`) depth occlusion so volumetric clouds,
   cumulonimbus towers, and 3D rain curtains wrap around and behind Distant Horizons mountains.
3. `unboundClouds.glsl` samples `skycraftWeatherTex` in world coordinates (`8192x8192` toroidal window)
   to drive:
   - Landscape-grounded cloud coverage (windward orographic lift, coastal/river moisture, leeward Foehn clearing),
   - Towering convective cumulonimbus & rotating mesocyclone supercell anvils + lowered dark storm bases,
   - Regional aerosol scattering (desert sandstorms/haboobs & Morrowind volcanic ash/blight),
   - Live volumetric internal lightning illumination synchronized with `DistantStorms` C2C/IC/CG bolts,
   - 3D raymarched sloping sub-cloud rain/snow/sand/ash bands and precipitation curtains occluded by terrain & DH.
4. `cloudShadows.glsl` modulates ground cloud shadows by the live `skycraftWeatherTex` coverage and storm intensity.
"""

from __future__ import annotations

import pathlib
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
AURORA_STYLE_DEFINE=0
NIGHT_NEBULAE=0
WEATHER_TEX_OPACITY=100
"""

UNBOUND_CLOUDS_GLSL = """#if CLOUD_QUALITY == 1 || !defined DEFERRED1
    const float cloudStretchRaw = 11.0;
#elif CLOUD_QUALITY == 2
    const float cloudStretchRaw = 16.0;
#elif CLOUD_QUALITY == 3
    const float cloudStretchRaw = 18.0;
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

float GetCloudNoise(vec3 tracePos, int cloudAltitude, float lTracePosXZ, float cloudPlayerPosY, float skyCov, float skyTower, out float localLower, out float localHigher) {
    localLower  = float(cloudAltitude) - cloudStretch * (0.90 + 0.75 * skyTower);
    localHigher = float(cloudAltitude) + cloudStretch * (0.92 + 0.38 * skyCov + 1.55 * skyTower);
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
        float persistance = 0.5;
        float noiseMult = 1.14;
    #elif CLOUD_QUALITY == 3
        int sampleCount = 4;
        float persistance = 0.5;
        float noiseMult = 1.0;
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

        tracePosM *= 3.0;
        wind *= 0.5;
        currentPersist *= persistance;
    }
    noise = pow2(noise / total);

    #define CLOUD_BASE_ADD 0.8
    #define CLOUD_ABOVE_ADD 0.1

    float height01 = clamp01((tracePos.y - localLower) / max(12.0, localHigher - localLower));
    float anvilShelf = smoothstep(0.58, 0.82, height01) * smoothstep(1.0, 0.82, height01) * smoothstep(0.42, 0.88, skyTower);
    float skycraftBaseAdd = mix(0.46, 1.18, smoothstep(0.08, 0.92, skyCov)) + 0.22 * skyTower + 0.14 * anvilShelf;

    noiseMult *= mix(CLOUD_BASE_ADD + CLOUD_UNBOUND_RAIN_ADD * rainFactor, skycraftBaseAdd, 0.88)
                + CLOUD_ABOVE_ADD * clamp01(-cloudPlayerPosY / cloudTallness);
    noise *= noiseMult * CLOUD_UNBOUND_AMOUNT;

    float localMid  = 0.5 * (localLower + localHigher);
    float localHalf = max(8.0, 0.5 * (localHigher - localLower));
    float threshold = clamp(abs(tracePos.y - localMid) / localHalf, 0.001, 0.999);
    threshold = pow2(pow2(pow2(threshold)));
    return noise - (threshold * 0.22 + 0.25);
}

vec4 GetSkycraftRainCurtains(int cloudAltitude, float distanceThreshold, float skyFade, float skyMult0, vec3 cameraPos, vec3 nPlayerPos, float lViewPosM, float VdotS, float VdotU, float dither) {
    if (nPlayerPos.y > 0.38 || nPlayerPos.y < -0.35) return vec4(0.0);

    float maxDist = min(min(lViewPosM, distanceThreshold * 0.72), 2600.0);
    float minDist = 170.0;
    if (maxDist <= minDist + 40.0) return vec4(0.0);

    float curtainBotY = 58.0;
    float curtainTopY = float(cloudAltitude) - cloudStretch * 0.80;
    vec4 accum = vec4(0.0);

    vec3 cloudSkyColor = GetSky(VdotU, VdotS, dither, isEyeInWater == 0, false);
    #ifdef ATM_COLOR_MULTS
        cloudSkyColor *= sqrtAtmColorMult;
    #endif

    const int CURTAIN_STEPS = 6;
    for (int s = 0; s < CURTAIN_STEPS; s++) {
        float t = (float(s) + dither) / float(CURTAIN_STEPS);
        float d = mix(minDist, maxDist, t * t * (0.65 + 0.35 * t));
        if (d > lViewPosM) break;

        vec3 rPos = cameraPos + nPlayerPos * d;
        if (rPos.y < curtainBotY || rPos.y > curtainTopY + 16.0) continue;

        float fallDist = max(0.0, curtainTopY - rPos.y);
        vec2 windSlant = vec2(0.30, 0.14) * fallDist;
        vec4 skyW = SampleSkycraftWeather((rPos.xz - cameraPos.xz) + windSlant);
        float curtain = skyW.b;
        if (curtain < 0.10) continue;

        float skyTower = skyW.g;
        float skyAerosolRaw = skyW.a * 4.0;
        int skyAerosol = clamp(int(floor(skyAerosolRaw)), 0, 3);
        float skyFlash = clamp((fract(skyAerosolRaw) - 0.08) / 0.84, 0.0, 1.0);

        vec2 worldXZ = GetSkycraftWorldXZ((rPos.xz - cameraPos.xz) + windSlant);
        vec3 streakPos = vec3(worldXZ.x * 0.0014, rPos.y * 0.00035 - syncedTime * 0.012, worldXZ.y * 0.0014);
        #if CLOUD_QUALITY >= 2
            float streak = Noise3D(streakPos) * 0.65 + Noise3D(streakPos * 2.5) * 0.35;
        #else
            float streak = texture2DLod(noisetex, streakPos.xy, 0.0).b;
        #endif

        float shaft = smoothstep(0.10, 0.78, curtain) * smoothstep(0.32, 0.70, streak);
        float vFade = smoothstep(curtainBotY, curtainBotY + 22.0, rPos.y)
                    * smoothstep(curtainTopY + 16.0, curtainTopY - 18.0, rPos.y);
        float dFade = smoothstep(minDist, 420.0, d) * smoothstep(2600.0, 850.0, d);
        float stepAlpha = clamp(shaft * vFade * dFade * (0.22 + 0.18 * skyTower), 0.0, 0.38);
        if (stepAlpha < 0.004) continue;

        vec3 baseLit = cloudAmbientColor * 0.62 + cloudLightColor * 0.22;
        vec3 curtainCol = mix(cloudSkyColor, baseLit * vec3(0.68, 0.74, 0.82), 0.68);
        if (skyAerosol == 1) {
            curtainCol = mix(cloudSkyColor, (cloudAmbientColor + cloudLightColor * 0.35) * vec3(0.92, 0.96, 1.02), 0.76);
        } else if (skyAerosol == 2) {
            curtainCol = mix(cloudSkyColor, (cloudAmbientColor + cloudLightColor * 0.40) * vec3(0.96, 0.76, 0.50), 0.82);
        } else if (skyAerosol == 3) {
            curtainCol = mix(cloudSkyColor, cloudAmbientColor * vec3(0.44, 0.36, 0.34), 0.84);
        }

        if (skyFlash > 0.01) {
            vec3 flashTint = (skyAerosol == 3) ? vec3(1.0, 0.45, 0.30) : vec3(0.65, 0.78, 1.05);
            curtainCol += flashTint * (skyFlash * 0.85 * max(0.35, length(cloudAmbientColor)));
        }

        curtainCol *= pow2(1.0 - maxBlindnessDarkness);
        accum.rgb = mix(accum.rgb, curtainCol, 1.0 - min1(accum.a));
        accum.a = min(0.78, accum.a + stepAlpha * (1.0 - accum.a));
    }

    return accum;
}

vec4 GetVolumetricClouds(int cloudAltitude, float distanceThreshold, inout float cloudLinearDepth, float skyFade, float skyMult0, vec3 cameraPos, vec3 nPlayerPos, float lViewPosM, float VdotS, float VdotU, float dither) {
    vec4 volumetricClouds = vec4(0.0);

    vec4 rainCurtains = GetSkycraftRainCurtains(cloudAltitude, distanceThreshold, skyFade, skyMult0, cameraPos, nPlayerPos, lViewPosM, VdotS, VdotU, dither);

    float higherPlaneAltitude = cloudAltitude + cloudStretch * 2.85;
    float lowerPlaneAltitude  = cloudAltitude - cloudStretch * 1.65;

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
        float stepMult = 32.0;
    #elif CLOUD_QUALITY == 3
        float stepMult = 16.0;
    #endif

    #if CLOUD_UNBOUND_SIZE_MULT > 100
        stepMult = stepMult / sqrt(float(CLOUD_UNBOUND_SIZE_MULT_M));
    #endif

    int sampleCount = int(planeDistanceDif / stepMult + dither + 1);
    sampleCount = min(sampleCount, 48);
    vec3 traceAdd = nPlayerPos * stepMult;
    vec3 tracePos = cameraPos + minPlaneDistance * nPlayerPos;
    tracePos += traceAdd * dither;
    tracePos.y -= traceAdd.y;

    float firstHitPos = 0.0;
    float VdotSM1 = max0(sunVisibility > 0.5 ? VdotS : - VdotS);
    float VdotSM1M = VdotSM1 * invRainFactor;
    float VdotSM2 = pow2(VdotSM1) * abs(sunVisibility - 0.5) * 2.0;
    float VdotSM3 = VdotSM2 * (2.5 + rainFactor) + 1.5 * rainFactor;
    float VdotSM4 = pow(VdotSM1M, 100.0) * sunVisibility;

    #ifdef FIX_AMD_REFLECTION_CRASH
        sampleCount = min(sampleCount, 30); //BFARC
    #endif

    for (int i = 0; i < sampleCount; i++) {
        tracePos += traceAdd;

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
        float cloudNoise = GetCloudNoise(tracePos, cloudAltitude, lTracePosXZ, cloudPlayerPos.y, skyCov, skyTower, localLower, localHigher);

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

            float opacityFactor = min1(cloudNoise * 8.0);

            float cloudHeight01 = clamp01((tracePos.y - localLower) / max(12.0, localHigher - localLower));
            float cloudShading = pow(cloudHeight01, 1.0 + 0.65 * skyTower);
            cloudShading *= 1.0 - 0.36 * skyCurtain * (1.0 - cloudHeight01);
            cloudShading *= 1.0 + 0.2 * VdotSM3 * (1.0 - opacityFactor) + VdotSM4;

            vec3 colorSample = cloudAmbientColor * (0.38 + 0.62 * cloudShading) + cloudLightColor * cloudShading;

            float skyAerosolRaw = skyW.a * 4.0;
            int skyAerosol = clamp(int(floor(skyAerosolRaw)), 0, 3);
            float skyFlash = clamp((fract(skyAerosolRaw) - 0.08) / 0.84, 0.0, 1.0);

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

            vec3 cloudSkyColor = GetSky(VdotU, VdotS, dither, isEyeInWater == 0, false);
            #ifdef ATM_COLOR_MULTS
                cloudSkyColor *= sqrtAtmColorMult; // C72380KD - Reduced atmColorMult impact on some things
            #endif
            float distanceRatio = (distanceThreshold - lTracePosXZ) / distanceThreshold;
            float cloudDistanceFactor = clamp(distanceRatio, 0.0, 0.8) * 1.25;
            float cloudFogFactor = pow2(pow1_5(clamp(distanceRatio, 0.0, 1.0))) * 0.69;
            cloudFogFactor = mix(1.0, cloudFogFactor, skyFadeM);
            colorSample *= 1.0 - 0.2 * (1.0 - skyFadeM) * max(sunVisibility2, nightFactor);
            colorSample = mix(cloudSkyColor, colorSample, cloudFogFactor * 0.69);
            colorSample *= pow2(1.0 - maxBlindnessDarkness);

            volumetricClouds.rgb = mix(volumetricClouds.rgb, colorSample, 1.0 - min1(volumetricClouds.a));
            volumetricClouds.a += opacityFactor * pow(cloudDistanceFactor, 0.5 + 10.0 * pow(abs(VdotSM1M), 90.0)) * cloudMult;

            if (volumetricClouds.a > 0.9) {
                volumetricClouds.a = 1.0;
                break;
            }
        }
    }

    if (volumetricClouds.a > 0.5) cloudLinearDepth = sqrt(firstHitPos / renderDistance);

    volumetricClouds.a *= 0.5 + 0.5 * skyFadeM;

    if (rainCurtains.a > 0.001) {
        vec3 combinedRgb = mix(rainCurtains.rgb, volumetricClouds.rgb, volumetricClouds.a);
        float combinedA = clamp01(volumetricClouds.a + rainCurtains.a * (1.0 - volumetricClouds.a));
        volumetricClouds = vec4(combinedRgb, combinedA);
    }

    return volumetricClouds;
}
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


def patch_cloud_shadows_glsl(text: str) -> str:
    if "skycraftWeatherTex" in text:
        return text
    old_tail = "            cloudShadow = smoothstep1(pow2(min1(cloudSample * 0.2)));"
    new_tail = (
        "            cloudShadow = smoothstep1(pow2(min1(cloudSample * 0.2)));\r\n"
        "            vec4 skyWShadow = texture2DLod(skycraftWeatherTex, fract(worldPos.xz / 8192.0), 0.0);\r\n"
        "            float skyCovShadow = clamp(skyWShadow.r * 1.35, 0.08, 1.0);\r\n"
        "            cloudShadow = mix(1.0, cloudShadow * (1.0 - 0.30 * skyWShadow.g), skyCovShadow);"
    )
    if old_tail not in text:
        raise RuntimeError("Could not find `cloudShadow = smoothstep1` in cloudShadows.glsl")
    return text.replace(old_tail, new_tail, 1)


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
                    raw = patch_cloud_shadows_glsl(raw.decode("utf-8")).encode("utf-8")
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

