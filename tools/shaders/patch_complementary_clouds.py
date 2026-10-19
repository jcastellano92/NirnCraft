#!/usr/bin/env python3
"""
Patches ComplementaryReimagined_r5.9.3.zip and its .txt option files so that:
1. Oculus binds `nirncraft:textures/environment/weather_field.png` to `uniform sampler2D nirncraftWeatherTex;`
   (updated dynamically in-game by `WeatherFieldTexture.java` from `WeatherPhysics`, `RegionalWeather`,
   `WeatherFront`, and `DistantStorms`).
2. `mainClouds.glsl` preserves Distant Horizons (`dhRenderDistance`) depth occlusion so volumetric clouds,
   cumulonimbus towers, and 3D rain/light curtains wrap around and behind Distant Horizons mountains.
3. `unboundClouds.glsl` samples `nirncraftWeatherTex` in world coordinates (`8192x8192` toroidal window)
   to drive:
   - Puffy fair-weather light clouds (`skyCov < 0.52`) vs heavy, dark slate storm/snow clouds (`skyCov >= 0.62`),
   - Cohesive overcast cloud decks during snow, blizzards, rain, and storms (with additive overcast floor and
     no sun-hole punching so clouds genuinely occlude the sun),
   - Strong solar forward-scattering (silver lining) on cloud fringes and Beer-Powder self-shadowing,
   - RDR2-style distant sub-cloud crepuscular light shafts (`GetNirnCraftRainCurtains`) that stream down ONLY
     where the 3D cloud field along `worldLightVec` has an actual sunlit gap next to cloud mass, alongside
     slanted 3D rain/snow/sand/ash precipitation bands.
4. Visible clouds and projected shadows include `nirncraftDensity.glsl`, sharing the same billows, wind,
   height profile and weather thresholds. Shadows approximate optical depth through the layer.
5. `volumetricLight.glsl` drives crepuscular rays strictly from `GetCloudShadow(scenePos)` when broken clouds
   are present, never creating random rays in cloudless skies or sealed blizzards.
6. `lightAndAmbientColors.glsl`, `cloudColors.glsl`, `skyColors.glsl`, and `mainLighting.glsl` respond to
   `nirncraftWeatherTex` cloud density and rain bands so overworld sunlight and ambient light darken under
   snow, overcast, dark cloud cells, and distant rain bands even when vanilla `rainFactor` is 0.
7. `gbuffers_skytextured.glsl`, `gbuffers_skybasic.glsl`, and `sky.glsl` render an analytical round solar disk
   that is properly attenuated by `nirncraftWeatherTex` overcast/snow cover and occluded by 3D clouds.
8. `wavingBlocks.glsl` and `block.properties` drive dynamic wind sway on all foliage and tree leaves
   (including in snowy biomes) proportional to `nirncraftWeatherTex` wind/storm intensity.
9. Cloud rays cover the full visible layer with bounded quality-dependent sample counts; cloud and weather
   volumes use transmittance-weighted compositing so thin samples do not paint opaque color bands.
"""

from __future__ import annotations

import pathlib
import hashlib
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SUPPORTED_UPSTREAM_SHA256 = "fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38"
PATCH_MANIFEST = "shaders/nirncraft-patch-manifest.json"


def validate_shader_input(path: pathlib.Path) -> None:
    """Pin fresh upstream packs; validate previous output before an in-place upgrade."""
    with zipfile.ZipFile(path) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError("Shader pack contains duplicate paths")
        if PATCH_MANIFEST in archive.namelist():
            manifest = json.loads(archive.read(PATCH_MANIFEST))
            if manifest.get("upstream_sha256") != SUPPORTED_UPSTREAM_SHA256 or manifest.get("schema") != 1:
                raise ValueError("Unsupported NirnCraft shader patch provenance")
            hashes = manifest.get("checked_outputs", {})
            if not hashes:
                raise ValueError("Shader patch manifest has no checked outputs")
            for name, digest in hashes.items():
                if name not in archive.namelist() or hashlib.sha256(archive.read(name)).hexdigest() != digest:
                    raise ValueError("Previously patched shader changed outside its declared pipeline: " + name)
            return
    if hashlib.sha256(path.read_bytes()).hexdigest() != SUPPORTED_UPSTREAM_SHA256:
        raise ValueError("Unsupported upstream shader pack. Rebuild from the pinned Complementary Reimagined r5.9.3 cache.")


PATCH_POSTCONDITIONS = {
    "shaders/lib/uniforms.glsl": ("uniform sampler2D nirncraftFogRadianceTex;", "uniform sampler2D nirncraftSmokeTex;"),
    "shaders/lib/lighting/mainLighting.glsl": ("NIRNCRAFT_FINITE_DARK_HIGHLIGHT", "NIRNCRAFT_LOCAL_AIR_LIGHT", "NIRNCRAFT_CHANNEL_RECEIVER"),
    "shaders/lib/atmospherics/fog/mainFog.glsl": ("NIRNCRAFT_SHARED_AIR", "NIRNCRAFT_SHELTERED_STOCK_FOG"),
    "shaders/lib/materials/materialMethods/wavingBlocks.glsl": ("NIRNCRAFT_ROOTED_WIND",),
    "shaders/lib/atmospherics/clouds/unboundClouds.glsl": ("NIRNCRAFT_BOUNDED_FIRE_RAYS", "inout float firstHitPos"),
    "shaders/lib/atmospherics/volumetricLight/volumetricLight.glsl": ("NIRNCRAFT_VOLUME_DEPTH_CLIP",),
}


def validate_shader_output(archive: zipfile.ZipFile) -> dict[str, str]:
    for name, markers in PATCH_POSTCONDITIONS.items():
        text = archive.read(name).decode("utf-8")
        for marker in markers:
            if text.count(marker) != 1:
                raise ValueError(f"Required shader postcondition {name}: expected one {marker!r}, found {text.count(marker)}")
    # The companion solar patch adds its sampler to uniforms/properties after
    # this stage; all other GLSL outputs must stay byte-identical until upgrade.
    return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in sorted(archive.namelist())
            if name.endswith(".glsl") and name != "shaders/lib/uniforms.glsl"}

CELESTIAL_ASSETS = {
    "nirncraftMasserTex": "masser_phases", "nirncraftSecundaTex": "secunda_phases",
    "nirncraftGalaxyTex": "galaxy", "nirncraftAtmosphereTex": "atmosphere_transmittance",
}

DEFAULT_ZIPS = [
    pathlib.Path(
        r"C:\Users\Jeremy\AppData\Roaming\PrismLauncher\instances\NirnCraft-client-instance\.minecraft\shaderpacks\ComplementaryReimagined_r5.9.3.zip"
    ),
    ROOT / "pack" / "shaderpacks" / "ComplementaryReimagined_r5.9.3.zip",
]

TXT_CONFIG_PATHS = [
    ROOT / "pack" / "shaderpacks" / "ComplementaryReimagined_r5.9.3.zip.txt",
    ROOT / "pack" / "config" / "ComplementaryReimagined_r5.9.3.zip.txt",
    pathlib.Path(
        r"C:\Users\Jeremy\AppData\Roaming\PrismLauncher\instances\NirnCraft-client-instance\.minecraft\shaderpacks\ComplementaryReimagined_r5.9.3.zip.txt"
    ),
]

SHADER_TXT_CONTENT = """#NirnCraft Complementary Reimagined profile - Landscape-driven 3D volumetric weather & Distant Horizons
CLOUD_STYLE_DEFINE=3
SUN_ANGLE=0
CLOUD_ALT1=232
CLOUD_SHADOWS=true
LIGHTSHAFT_QUALI_DEFINE=2
AURORA_STYLE_DEFINE=2
AURORA_CONDITION=0
MOON_PHASE_INF_LIGHT=true
MOON_PHASE_INF_ATMOSPHERE=true
NIGHT_NEBULAE=0
WEATHER_TEX_OPACITY=100
"""

NIRNCRAFT_WAVING_FOLIAGE = (
    "nirncraft:scathecraw nirncraft:ash_grass nirncraft:swamp_reeds:half=lower "
    "nirncraft:nightshade nirncraft:deathbell nirncraft:nirnroot nirncraft:crimson_nirnroot "
    "nirncraft:frost_mirriam nirncraft:elves_ear "
    "nirncraft:jazbay_grapes nirncraft:juniper_berries "
    "nirncraft:thistle_branch nirncraft:giant_lichen nirncraft:poison_bloom "
    "nirncraft:emperor_parasol_moss "
    "nirncraft:ashen_grass_pod nirncraft:comberry nirncraft:marshmerrow "
    "nirncraft:saltrice nirncraft:bittergreen_petals nirncraft:gold_kanet "
    "nirncraft:moon_sugar "
    "nirncraft:kresh_fiber nirncraft:coda_flower "
    "nirncraft:hackle_lo nirncraft:desert_rose nirncraft:cactus_pulp "
    "nirncraft:snowberries"
)

NIRNCRAFT_WAVING_LEAVES_FALSE = (
    "nirncraft:golden_aspen_leaves:waterlogged=false nirncraft:orange_aspen_leaves:waterlogged=false "
    "nirncraft:snowy_spruce_leaves:waterlogged=false nirncraft:graht_oak_leaves:waterlogged=false "
    "nirncraft:palm_fronds:waterlogged=false nirncraft:hist_leaves:waterlogged=false"
)

NIRNCRAFT_WAVING_LEAVES_TRUE = (
    "nirncraft:golden_aspen_leaves:waterlogged=true nirncraft:orange_aspen_leaves:waterlogged=true "
    "nirncraft:snowy_spruce_leaves:waterlogged=true nirncraft:graht_oak_leaves:waterlogged=true "
    "nirncraft:palm_fronds:waterlogged=true nirncraft:hist_leaves:waterlogged=true"
)

CLOUD_DENSITY_GLSL = """#ifndef NIRNCRAFT_CLOUD_DENSITY
#define NIRNCRAFT_CLOUD_DENSITY
#include "/lib/atmospherics/nirncraftInterior.glsl"
#include "/lib/atmospherics/nirncraftLightning.glsl"

#if CLOUD_UNBOUND_SIZE_MULT != 100 && !defined CLOUD_UNBOUND_SIZE_MULT_M
    #define CLOUD_UNBOUND_SIZE_MULT_M (CLOUD_UNBOUND_SIZE_MULT * 0.01)
#endif

// Quality changes sampling cost, not cloud altitude or physical thickness.
const float cloudStretchRaw = 24.0;
#if CLOUD_UNBOUND_SIZE_MULT <= 100
    const float cloudStretch = cloudStretchRaw;
#else
    const float cloudStretch = cloudStretchRaw / float(CLOUD_UNBOUND_SIZE_MULT_M);
#endif

const float cloudNarrowness = 0.00010;

const float cloudTallness = cloudStretch * 2.0;

float NirnCraftWeatherTime() {
    vec4 clockBytes = floor(texelFetch(nirncraftSmokeTex, ivec2(13, 1), 0) * 255.0 + 0.5);
    float partialTick = texelFetch(nirncraftSmokeTex, ivec2(11, 1), 0).r;
    return (dot(clockBytes, vec4(1.0, 256.0, 65536.0, 16777216.0)) + partialTick) * 0.05;
}

float NirnCraftRenderBudget() {
    return clamp(texelFetch(nirncraftSmokeTex, ivec2(11, 1), 0).g, 0.55, 1.0);
}

// Isotropic, smooth lattice noise. The pack's generic Noise3D interpolates raw
// slices along one axis, which produces stretched sheets in a thick volume.
float NirnCraftCloudHash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float NirnCraftCloudNoise(vec3 p) {
    // Original baked 3D gradient/cellular fBm; two filtered atlas lookups replace
    // eight lattice hashes per octave. Gutters prevent adjacent slice bleeding.
    vec3 voxel = fract(p / 16.0) * 64.0;
    float z0 = floor(voxel.z), z1 = mod(z0 + 1.0, 64.0);
    vec2 tile0 = vec2(mod(z0, 8.0), floor(z0 / 8.0));
    vec2 tile1 = vec2(mod(z1, 8.0), floor(z1 / 8.0));
    vec4 a = texture2DLod(nirncraftCloudNoiseTex, (tile0 * 66.0 + voxel.xy + 1.5) / 528.0, 0.0);
    vec4 b = texture2DLod(nirncraftCloudNoiseTex, (tile1 * 66.0 + voxel.xy + 1.5) / 528.0, 0.0);
    vec4 noise = mix(a, b, fract(voxel.z));
    return mix(noise.b, noise.r, 0.30);
}

#include "/lib/atmospherics/nirncraftInteriorDensity.glsl"

vec2 NirnCraftWind(vec4 dynamics) {
    float angle = dynamics.b * 6.2831853;
    return vec2(cos(angle), sin(angle));
}

vec3 NirnCraftFogRadiance(vec3 playerPos) {
    if (max(abs(playerPos.x), abs(playerPos.z)) > 26.0 || abs(playerPos.y) > 26.0) return vec3(0.0);
    vec3 cellPos = (playerPos + cameraPosition) / 4.0 - 0.5;
    vec3 base = floor(cellPos), f = fract(cellPos);
    vec3 radiance = vec3(0.0);
    for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
        vec3 c = mod(base + vec3(x, y, z), 16.0);
        vec2 uv = (vec2(mod(c.y, 4.0), floor(c.y / 4.0)) * 16.0 + c.xz + vec2(64.5,0.5)) / vec2(128.0);
        vec3 w = mix(1.0 - f, f, vec3(x, y, z));
        vec3 cellLight=texture2DLod(nirncraftFogRadianceTex, uv, 0.0).rgb;
        radiance += cellLight * w.x * w.y * w.z;
    }
    // Preserve source hue while converting Minecraft's light levels to radiance.
    return radiance * max(radiance.r,max(radiance.g,radiance.b)) * 1.8;
}

// Outdoor weather cover in the spare G/B/A channels of the near light cache.
// Camera-independent sampling keeps outside fog visible through a clear room's window.
float NirnCraftWeatherExposure(vec3 playerPos) {
    if (max(abs(playerPos.x), abs(playerPos.z)) > 58.0) return 1.0;
    vec2 world = cameraPosition.xz + playerPos.xz;
    ivec2 cell = ivec2(mod(floor(world / 2.0), 64.0));
    vec4 data = floor(texelFetch(nirncraftFogRadianceTex, cell, 0) * 255.0 + 0.5);
    if (data.a < 128.0) return 1.0; // Unknown columns are not invented as solid roofs.
    float roof = data.g + data.b * 256.0 - 64.0;
    return smoothstep(roof + 0.05, roof + 0.75, cameraPosition.y + playerPos.y);
}

vec2 GetNirnCraftWorldXZ(vec2 playerOffsetXZ);
vec4 SampleNirnCraftWeather(vec2 playerOffsetXZ);
vec4 SampleNirnCraftDynamics(vec2 playerOffsetXZ);

struct NirnCraftPlume { vec2 xz; vec2 wind; float y; float height; float radius; float strength; };
NirnCraftPlume nirncraftPlumes[24];
bool nirncraftPlumesLoaded = false;
bool nirncraftVolcanoLoaded = false;
NirnCraftPlume nirncraftVolcano=NirnCraftPlume(vec2(0.0),vec2(0.0),0.0,0.0,0.0,0.0);
int nirncraftPlumeCount = 0;
int nirncraftRayCount = 0;
int nirncraftRaySource[23];
vec2 nirncraftRayInterval[23];
NirnCraftPlume NirnCraftReadPlume(int source) {
    int row=source<8?0:2, slot=source<8?source:source-8;
    vec4 state=floor(texelFetch(nirncraftSmokeTex,ivec2(slot*2+1,row),0)*255.0+0.5);
    NirnCraftPlume plume=NirnCraftPlume(vec2(0.0),vec2(0.0),0.0,0.0,0.0,state.b/100.0);
    if(state.b<1.0) return plume;
    vec4 xy=floor(texelFetch(nirncraftSmokeTex,ivec2(slot*2,row),0)*255.0+0.5);
    vec4 shape=floor(texelFetch(nirncraftSmokeTex,ivec2(slot*2,row+1),0)*255.0+0.5);
    plume.xz=vec2(xy.r+256.0*xy.g,xy.b+256.0*xy.a)-32768.0;
    plume.wind=NirnCraftWind(SampleNirnCraftDynamics(plume.xz-GetNirnCraftWorldXZ(vec2(0.0))));
    plume.y=state.r+256.0*state.g-64.0;
    plume.height=shape.r+256.0*shape.g; plume.radius=shape.b;
    return plume;
}
void NirnCraftLoadPlumes() {
    if (nirncraftPlumesLoaded) return;
    nirncraftPlumesLoaded = true;
    nirncraftPlumeCount = clamp(int(texelFetch(nirncraftSmokeTex,ivec2(31,3),0).r*255.0+0.5),0,24);
    nirncraftVolcano=NirnCraftReadPlume(0);
    for (int source = 1; source < nirncraftPlumeCount; source++) nirncraftPlumes[source]=NirnCraftReadPlume(source);
    nirncraftVolcanoLoaded=true;
}

float NirnCraftSourceDensity(NirnCraftPlume plume, vec3 tracePos) {
        if (plume.strength < 0.01) return 0.0;
        vec2 worldXZ = GetNirnCraftWorldXZ(tracePos.xz-cameraPosition.xz);
        float sourceY = plume.y, height = plume.height;
        float h = tracePos.y - sourceY;
        if (h <= 0.0 || h >= height) return 0.0;
        float rise = h / height;
        float radius = plume.radius * (0.25 + 1.6 * sqrt(rise));
        vec2 offset = worldXZ - plume.xz - plume.wind * h * (0.18 + 0.45 * rise);
        if (dot(offset, offset) > radius * radius * 2.0) return 0.0;
        vec3 noisePos = vec3(offset.x, h - NirnCraftWeatherTime() * 3.5, offset.y) / max(10.0, radius * 0.6);
        float billow = 0.65 * NirnCraftCloudNoise(noisePos) + 0.35 * NirnCraftCloudNoise(noisePos * 2.4);
        float radial = 1.0 - smoothstep(0.0, radius * (0.7 + billow * 0.6), length(offset));
        return radial * smoothstep(0.0, 0.05, rise) * (1.0 - smoothstep(0.7, 1.0, rise)) * plume.strength * (0.10 + 0.30 * billow);
}

float NirnCraftPlumeDensity(vec3 tracePos, vec2 playerOffsetXZ) {
    if(!nirncraftVolcanoLoaded) { nirncraftVolcano=NirnCraftReadPlume(0); nirncraftVolcanoLoaded=true; }
    // Local fires have a bounded, clipped integral of their own. Sampling them
    // only at kilometre-scale cloud steps misses narrow distant columns.
    return NirnCraftSourceDensity(nirncraftVolcano,tracePos);
}

void NirnCraftInitFireRay(vec3 origin,vec3 direction,float limit) {
    NirnCraftLoadPlumes(); nirncraftRayCount=0;
    origin.xz=GetNirnCraftWorldXZ(vec2(0.0));
    vec3 safeDirection=mix(vec3(0.000001),direction,greaterThan(abs(direction),vec3(0.000001)));
    for(int i=1;i<nirncraftPlumeCount;i++) {
        NirnCraftPlume plume=nirncraftPlumes[i];
        if(plume.strength<0.01) continue;
        float radius=plume.radius*2.4;
        vec2 tip=plume.xz+plume.wind*plume.height*0.63;
        vec3 low=vec3(min(plume.xz,tip)-radius,plume.y).xzy;
        vec3 high=vec3(max(plume.xz,tip)+radius,plume.y+plume.height).xzy;
        vec3 a=(low-origin)/safeDirection,b=(high-origin)/safeDirection;
        vec3 lo=min(a,b),hi=max(a,b);
        float enter=max(0.0,max(lo.x,max(lo.y,lo.z)));
        float leave=min(limit,min(hi.x,min(hi.y,hi.z)));
        if(leave<=enter) continue;
        nirncraftRaySource[nirncraftRayCount]=i;
        nirncraftRayInterval[nirncraftRayCount++]=vec2(enter,leave);
    }
}

float NirnCraftHighCloudDensity(vec3 tracePos, vec4 dynamics, float coverage, float convection) {
    float h = (tracePos.y - 1020.0) / 180.0;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    // Thin ice cloud above the boundary layer; taller storm anvils remain part of
    // the main volume. Faster upper wind shears these into elongated wisps.
    vec2 wind = NirnCraftWind(dynamics);
    // NIRNCRAFT_STABLE_ICE_FRAME: rotating absolute XZ by a spatially varying
    // wind field folds the wisps into origin-centred arcs. Wind may deform a
    // local parcel by metres; it must not rotate kilometre-scale coordinates.
    vec2 along = vec2(0.8, 0.6), across = vec2(-0.6, 0.8);
    vec2 xz = tracePos.xz - along * NirnCraftWeatherTime() * 2.2;
    xz -= (wind - along) * (32.0 + 56.0 * h);
    vec3 p = vec3(dot(xz, along) / 600.0, tracePos.y / 90.0, dot(xz, across) / 85.0);
    float noise = NirnCraftCloudNoise(p) * 0.7 + NirnCraftCloudNoise(p * 2.7) * 0.3;
    float veil = smoothstep(0.65, 0.95, coverage) * (1.0 - 0.75 * convection);
    float ripples = NirnCraftCloudNoise(vec3(xz / 65.0, tracePos.y / 55.0));
    float cellular = smoothstep(0.30, 0.65, coverage) * (1.0 - smoothstep(0.30, 0.70, convection));
    float wisps = max(0.0, noise - 0.48) * 0.10;
    float icePuffs = max(0.0, ripples - 0.57) * cellular * 0.12;
    float sheet = veil * (0.003 + noise * 0.007);
    return (wisps + icePuffs + sheet) * smoothstep(0.0, 0.22, h) * (1.0 - smoothstep(0.48, 1.0, h));
}

float NirnCraftMiddleCloudDensity(vec3 tracePos, vec4 weather) {
    float h = (tracePos.y - 650.0) / 200.0;
    if (h <= 0.0 || h >= 1.0) return 0.0;
    float moisture = smoothstep(0.42, 0.85, weather.r);
    float stratiform = (1.0 - smoothstep(0.30, 0.80, weather.g)) * moisture;
    if (stratiform < 0.01) return 0.0;
    vec3 p = (tracePos - vec3(0.8, 0.0, 0.6) * NirnCraftWeatherTime() * 1.4) / vec3(110.0, 65.0, 110.0);
    float n = 0.65 * NirnCraftCloudNoise(p) + 0.35 * NirnCraftCloudNoise(p * 2.5);
    float sheet = smoothstep(0.70, 0.96, weather.r) * (0.015 + weather.b * 0.05);
    float puffs = max(0.0, n - mix(0.65, 0.42, moisture)) * 0.22;
    return (puffs + sheet) * stratiform * smoothstep(0.0, 0.20, h) * (1.0 - smoothstep(0.50, 1.0, h));
}

// NirnCraft landscape-driven weather field helpers (8192x8192 block toroidal world window)
vec2 GetNirnCraftWorldXZ(vec2 playerOffsetXZ) {
    #if IRIS_VERSION >= 10800
        return vec2(cameraPositionInt.xz) + cameraPositionBestFract.xz + playerOffsetXZ;
    #else
        return cameraPosition.xz + playerOffsetXZ;
    #endif
}

vec4 SampleNirnCraftWeather(vec2 playerOffsetXZ) {
    vec2 worldXZ = GetNirnCraftWorldXZ(playerOffsetXZ);
    vec2 uv = fract(worldXZ / 8192.0);
    vec4 weather = texture2DLod(nirncraftWeatherTex, uv, 0.0);
    // Alpha packs a categorical aerosol band and lightning. Filtering between rain and snow
    // would manufacture a flash, so only coverage/towers/curtains are bilinearly blended.
    vec2 categoryUV = (floor(uv * 128.0) + 0.5) / 128.0;
    weather.a = texture2DLod(nirncraftWeatherTex, categoryUV, 0.0).a;
    float farBlend = smoothstep(2200.0, 3600.0, length(playerOffsetXZ));
    if (farBlend > 0.0) {
        vec2 farUV = fract(worldXZ / 65536.0);
        vec4 distant = texture2DLod(nirncraftWeatherFarTex, farUV, 0.0);
        distant.a = texture2DLod(nirncraftWeatherFarTex, (floor(farUV * 128.0) + 0.5) / 128.0, 0.0).a;
        weather.rgb = mix(weather.rgb, distant.rgb, farBlend);
        if (farBlend > 0.5) weather.a = distant.a;
    }
    return weather;
}

#include "/lib/atmospherics/nirncraftWind.glsl"

vec4 SampleNirnCraftDynamics(vec2 playerOffsetXZ) {
    vec2 worldXZ = GetNirnCraftWorldXZ(playerOffsetXZ);
    vec4 local = NirnCraftSampleDynamics(nirncraftDynamicsTex,worldXZ / 8192.0);
    float farBlend = smoothstep(2200.0, 3600.0, length(playerOffsetXZ));
    if (farBlend > 0.0) {
        vec4 distant=NirnCraftSampleDynamics(nirncraftDynamicsFarTex,worldXZ / 65536.0);
        float angle=NirnCraftMixWindAngle(local.b,distant.b,farBlend);
        local=mix(local,distant,farBlend);
        local.b=angle;
    }
    return local;
}

float NirnCraftCloudThickness(vec4 weather) {
    return 72.0 + 120.0 * weather.r + 300.0 * weather.g;
}

float NirnCraftTerrainTransmission(vec3 playerPos, vec3 worldLightDir) {
    // Near geometry uses the normal shadow map. The shared continent height field
    // extends mountain occlusion through DH range, from each receiver rather than
    // shifting the solar clock for the entire image when the camera enters a valley.
    float horizonWeight = 1.0 - smoothstep(0.20, 0.36, worldLightDir.y);
    if (horizonWeight <= 0.0 || worldLightDir.y <= 0.0) return 1.0;
    float horizontal = max(length(worldLightDir.xz), 0.001);
    vec2 direction = worldLightDir.xz / horizontal;
    float slope = worldLightDir.y / horizontal;
    float transmission = 1.0;
    float distanceM = 160.0;
    for (int h = 0; h < 9; h++) {
        float ground = SampleNirnCraftDynamics(playerPos.xz + direction * distanceM).g * 1024.0;
        float rayHeight = cameraPosition.y + playerPos.y + slope * distanceM;
        transmission = min(transmission, smoothstep(-0.006, 0.006, (rayHeight - ground) / distanceM));
        distanceM *= 1.85;
    }
    return mix(1.0, transmission, horizonWeight);
}

float GetCloudNoise(vec3 tracePos, int cloudAltitude, float lTracePosXZ, float cloudPlayerPosY, vec2 playerOffsetXZ, float skyCov, float skyTower, float skyCurtain, out float localLower, out float localHigher) {
    float effectiveCov = max(skyCov, skyCurtain * 0.68);
    vec4 dynamics = SampleNirnCraftDynamics(playerOffsetXZ);
    // Rolling asperitas/mammatus base undulation under storm/overcast decks (Ref 2)
    float baseRoll = 0.0;
    #if CLOUD_QUALITY >= 2
        if (effectiveCov > 0.48 || skyTower > 0.30) {
            float rollStrength = clamp01(max(effectiveCov - 0.45, skyTower * 0.85));
            baseRoll = (NirnCraftCloudNoise(tracePos.xyz / 180.0) - 0.5) * 14.0 * rollStrength;
        }
    #endif
    localLower = dynamics.r * 1024.0 + baseRoll;
    // Towering cumulonimbus supercells shoot high into the tropopause (Ref 1)
    localHigher = localLower + NirnCraftCloudThickness(vec4(skyCov, skyTower, skyCurtain, 0.0));
    if (tracePos.y < localLower || tracePos.y > localHigher) {
        float ice = NirnCraftHighCloudDensity(tracePos, dynamics, skyCov, skyTower);
        float middle = NirnCraftMiddleCloudDensity(tracePos, vec4(skyCov, skyTower, skyCurtain, 0.0));
        float upper = max(ice, middle);
        float smoke = NirnCraftPlumeDensity(tracePos, playerOffsetXZ);
        if (ice > 0.0) { localLower = 1020.0; localHigher = 1200.0; }
        else if (middle > 0.0) { localLower = 650.0; localHigher = 850.0; }
        return max(upper, smoke);
    }

    float height01 = clamp01((tracePos.y - localLower) / max(12.0, localHigher - localLower));
    // Glaciated fibrous anvil shelf flaring horizontally near the tropopause (height01 ~ 0.64..0.96, Ref 1)
    float anvilShelf = smoothstep(0.60, 0.82, height01) * (1.0 - smoothstep(0.88, 1.0, height01)) * smoothstep(0.30, 0.78, skyTower);

    vec3 tracePosM = tracePos.xyz / 150.0;
    // Shape the anvil through density, never scale absolute world XZ with height:
    // that shears distant cells into long funnels around the world origin.

    // Rounded overlapping parcels give the bank actual lobes in all three axes.
    // Eroding a thin horizontal noise slab alone makes layered sheets and funnels.
    vec2 bulkWind = vec2(0.8, 0.6);
    vec2 parcelXZ = tracePos.xz - bulkWind * NirnCraftWeatherTime() * 0.9 * (CLOUD_SPEED_MULT * 0.01)
            - (NirnCraftWind(dynamics) - bulkWind) * 24.0;
    // Bounded altitude shear deforms the turret without ever folding the weather
    // field around the origin. Fine turbulence advects faster at the cloud top.
    vec2 shear = vec2(sin(NirnCraftWeatherTime() * 0.002), cos(NirnCraftWeatherTime() * 0.0017 + 1.2));
    parcelXZ -= shear * pow(height01, 2.0) * (25.0 + skyTower * 55.0);
    vec2 parcelCell = floor(parcelXZ / 256.0);
    float parcelShape = -1.0;
    float thickness = localHigher - localLower;
    for (int px = -1; px <= 1; px++) for (int pz = -1; pz <= 1; pz++) {
        vec2 cell = parcelCell + vec2(px, pz);
        float r0 = NirnCraftCloudHash(vec3(cell.x, 7.0, cell.y));
        float r1 = NirnCraftCloudHash(vec3(cell.x, 13.0, cell.y));
        vec2 centreXZ = (cell + 0.5 + (vec2(r0, r1) - 0.5) * 0.55) * 256.0;
        float radius = mix(76.0, 230.0, effectiveCov) * (0.75 + r0 * 0.45);
        float growth = 0.88 + 0.12 * sin(NirnCraftWeatherTime() * 0.002 + r1 * 6.2831853);
        radius *= growth;
        vec2 deltaXZ = parcelXZ - centreXZ;
        if (max(abs(deltaXZ.x), abs(deltaXZ.y)) > radius * 1.15) continue;
        float centreY = localLower + thickness * (0.38 + r1 * 0.16);
        float radiusY = thickness * (0.38 + r0 * 0.08) * growth;
        float lobe = 1.0 - length(vec3(deltaXZ / radius, (tracePos.y - centreY) / radiusY));
        for (int bud = 0; bud < 3; bud++) {
            float angle = (r0 + float(bud) * 0.333333) * 6.2831853;
            vec2 budXZ = centreXZ + vec2(cos(angle), sin(angle)) * radius * 0.53;
            float budY = centreY + radiusY * (bud == 0 ? 0.45 + 0.15 * r1 : (bud == 1 ? 0.08 : -0.32));
            float budShape = 1.0 - length(vec3((parcelXZ - budXZ) / (radius * 0.61),
                    (tracePos.y - budY) / (radiusY * 0.62)));
            float budMix = clamp01(0.5 + 0.5 * (lobe - budShape) / 0.13);
            lobe = mix(budShape, lobe, budMix) + 0.13 * budMix * (1.0 - budMix);
        }
        float h = clamp01(0.5 + 0.5 * (parcelShape - lobe) / 0.16);
        parcelShape = mix(lobe, parcelShape, h) + 0.16 * h * (1.0 - h);
    }
    if (parcelShape < -0.10) return NirnCraftPlumeDensity(tracePos, playerOffsetXZ);

    float wind = 0.006;
    float noise = 0.0;
    float currentPersist = 1.0;
    float total = 0.0;

    #define CLOUD_SPEED_MULT_M CLOUD_SPEED_MULT * 0.01
    wind *= NirnCraftWeatherTime() * CLOUD_SPEED_MULT_M * (1.0 + 0.85 * height01);
    #if CLOUD_UNBOUND_SIZE_MULT != 100
        tracePosM *= CLOUD_UNBOUND_SIZE_MULT_M;
        wind *= CLOUD_UNBOUND_SIZE_MULT_M;
    #endif

    #if CLOUD_QUALITY <= 1
        int sampleCount = 2;
        float persistance = 0.6;
        float noiseMult = 0.95;
        wind *= 0.5;
    #elif CLOUD_QUALITY == 2
        int sampleCount = 3;
        float persistance = 0.53;
        float noiseMult = 1.15;
    #elif CLOUD_QUALITY == 3
        int sampleCount = 4;
        float persistance = 0.53;
        float noiseMult = 1.03;
    #endif

    // Cauliflower turret crispness on lower/mid convective domes (Ref 1 & Ref 3)
    float turretCrisp = (1.0 - anvilShelf * 0.75) * (0.34 + 0.26 * skyTower);

    for (int i = 0; i < sampleCount; i++) {
        // Keep real vertical structure at every quality. A 2D XZ lookup extrudes each texel
        // into a slab with square edges, especially obvious beneath a fair-weather deck.
        // A shared bulk translation plus bounded terrain deflection. Multiplying a
        // spatially varying wind by an ever-growing clock folds the density field.
        vec2 windDir = vec2(0.8, 0.6);
        vec2 terrainFlow = (NirnCraftWind(dynamics) - windDir) * 0.16;
        float nOct = NirnCraftCloudNoise(tracePosM - vec3(windDir.x * wind + terrainFlow.x, 0.0, windDir.y * wind + terrainFlow.y));
        if (i >= 1) {
            nOct = mix(nOct, sqrt(max0(nOct)), turretCrisp * 0.35);
        }
        noise += nOct * currentPersist;
        total += currentPersist;

        tracePosM *= 2.6;
        wind *= 2.6;
        currentPersist *= persistance;
    }
    noise = noise / total;

    #define CLOUD_BASE_ADD 0.82
    #define CLOUD_ABOVE_ADD 0.1

    float nirncraftBaseAdd = mix(0.68, 1.34, smoothstep(0.06, 0.86, effectiveCov))
                          + 0.28 * skyTower
                          + 0.28 * anvilShelf
                          + 0.14 * skyCurtain;

    noise *= CLOUD_UNBOUND_AMOUNT;

    // Vertical profile: flat condensation base at height01 ~ 0.14, cauliflower turrets in mid-levels, flat glaciated anvil cap at top
    float baseFlat = smoothstep(0.0, 0.15, height01);
    float domeRound = 1.0 - smoothstep(0.26, 1.0, height01);
    float anvilCap = 1.0 - smoothstep(0.89, 1.0, height01);
    float verticalShape = mix(mix(domeRound, sqrt(max0(domeRound)), 0.55 + 0.35 * skyTower), anvilCap, anvilShelf * 0.82);
    float cumulusProfile = baseFlat * verticalShape;

    // Additive overcast floor seals the cloud deck during heavy storms and blizzards while keeping textured crevices
    float stableSheet = smoothstep(0.68, 0.94, effectiveCov) * (1.0 - smoothstep(0.25, 0.65, skyTower));
    float overcastFloor = (smoothstep(0.86, 0.98, effectiveCov) * 0.035 + stableSheet * 0.065) * cumulusProfile;
    float edgeThreshold = mix(0.72, 0.24, effectiveCov) + (1.0 - cumulusProfile) * 0.28;
    // Extinction must vanish at both layer bounds. Threshold shaping alone leaves
    // dense samples opaque right up to the clipping planes, producing flat cut faces.
    float erodedParcel = parcelShape + (noise - 0.5) * 0.75 - 0.05;
    float closeDetail = (1.0 - smoothstep(900.0, 2600.0, lTracePosXZ));
    if (erodedParcel > -0.15 && erodedParcel < 0.40 && closeDetail > 0.01) {
        vec3 edgePos = (tracePos - vec3(0.8, 0.0, 0.6) * NirnCraftWeatherTime() * 0.9) / 26.0;
        float erosion = 1.0 - NirnCraftCloudNoise(edgePos);
        erodedParcel -= erosion * 0.20 * closeDetail;
    }
    float parcelDensity = max0(erodedParcel) * cumulusProfile * 0.48 + overcastFloor;
    return max(parcelDensity, NirnCraftPlumeDensity(tracePos, playerOffsetXZ));
}

// The strongest discharge gets a short density-shadow ray. This retains dark
// foreground billows instead of painting a bright sphere over the whole cloud.
vec3 NirnCraftLightningCloud(vec3 playerPos, int altitude) {
    vec3 radiance = NirnCraftLightningVolume(playerPos);
    if (dot(radiance, radiance) < 0.0004) return radiance;
    float best = 0.0;
    vec3 lightDelta = vec3(0.0);
    for (int s = 0; s < nirncraftLightningCount; s++) {
        vec3 delta = NirnCraftNearestChannel(playerPos, s) - playerPos;
        float score = nirncraftDischarge[s].r / (625.0 + dot(delta, delta));
        if (score > best) { best = score; lightDelta = delta; }
    }
    float distanceM = length(lightDelta);
    if (distanceM < 2.0 || distanceM > 1800.0) return radiance;
    // Budget bounds the light ray independently of the primary view march.
    #if CLOUD_QUALITY >= 2
        int probes = NirnCraftRenderBudget() > 0.80 ? 2 : 1;
    #else
        int probes = 1;
    #endif
    float opticalDepth = 0.0;
    for (int p = 0; p < probes; p++) {
        float t = (float(p) + 0.5) / float(probes);
        vec3 lightPos = playerPos + lightDelta * t;
        vec3 worldPos = lightPos + cameraPosition;
        vec4 weather = SampleNirnCraftWeather(lightPos.xz);
        float lower, upper;
        float density = max0(GetCloudNoise(worldPos, altitude, length(lightPos.xz), lightPos.y, lightPos.xz,
                                           weather.r, weather.g, weather.b, lower, upper));
        opticalDepth += density * distanceM / float(probes) * 0.18;
    }
    // A bounded diffuse scattering approximation preserves a weaker broad glow.
    return radiance * (0.12 + 0.88 * exp(-opticalDepth));
}

float NirnCraftMistDensity(vec3 worldPos, vec4 dynamics, vec4 weather, out vec4 room) {
    float exposure = NirnCraftWeatherExposure(worldPos - cameraPosition);
    room = NirnCraftInteriorMedium(worldPos - cameraPosition);
    float interior = room.a;
    if (exposure < 0.001) return interior;
    // Air volumes start at the water surface over oceans, never at the seabed.
    float height = worldPos.y - max(63.0, dynamics.g * 1024.0);
    if (height < -2.0) return interior;
    int aerosol = clamp(int(floor(weather.a * 4.0)), 0, 3);
    float rainMist = aerosol == 0 && worldPos.y < dynamics.r * 1024.0
            ? 0.025 * pow(smoothstep(0.20, 0.75, weather.b), 3.0) * weather.g : 0.0;
    if (height > 60.0 || dynamics.a < 0.02) return rainMist * exposure + interior;
    float profile = 1.0 - smoothstep(4.0, 60.0, height);
    float noise = NirnCraftCloudNoise(worldPos * vec3(0.018, 0.035, 0.018)
            - vec3(NirnCraftWeatherTime() * 0.008, 0.0, NirnCraftWeatherTime() * 0.004));
    return (rainMist + (0.0005 * dynamics.a + 0.075 * pow(dynamics.a, 3.0)) * profile * (0.75 + 0.25 * noise)) * exposure + interior;
}

float NirnCraftMistDensity(vec3 worldPos, vec4 dynamics, vec4 weather) {
    vec4 room;
    return NirnCraftMistDensity(worldPos, dynamics, weather, room);
}

#endif
"""

UNBOUND_CLOUDS_GLSL = """#include "/lib/atmospherics/clouds/nirncraftDensity.glsl"

void AccumulateNirnCraftVolume(inout vec4 volume, vec3 color, float alpha) {
    float weight = (1.0 - volume.a) * clamp01(alpha);
    volume.rgb += color * weight;
    volume.a += weight;
}

// NIRNCRAFT_BOUNDED_FIRE_RAYS: intersect source bounds once, then integrate only
// overlapping subintervals. Every source keeps its actual flame origin and depth.
void NirnCraftIntegrateFire(inout vec4 volume,inout float firstHitPos,vec3 origin,vec3 direction,float from,float to) {
    float opticalDepth=0.0; vec3 scattering=vec3(0.0);
    for(int r=0;r<nirncraftRayCount;r++) {
        vec2 interval=nirncraftRayInterval[r];
        float a=max(from,interval.x),b=min(to,interval.y);
        if(b<=a) continue;
        NirnCraftPlume plume=nirncraftPlumes[nirncraftRaySource[r]];
        float stepLength=(b-a)*0.25;
        for(int k=0;k<4;k++) {
            vec3 point=origin+direction*(a+(float(k)+0.5)*stepLength);
            float sigma=NirnCraftSourceDensity(plume,point)*0.18;
            if(sigma<=0.00001) continue;
            vec3 player=point-cameraPosition;
            sigma*=NirnCraftWeatherExposure(player);
            if(sigma<=0.0) continue;
            float hitDistance=a+(float(k)+0.5)*stepLength;
            firstHitPos=firstHitPos<0.0?hitDistance:min(firstHitPos,hitDistance);
            float height=point.y-plume.y;
            vec3 soot=cloudAmbientColor*vec3(0.26,0.24,0.22)+NirnCraftKeyRadiance(point.y)*0.12;
            soot+=vec3(1.0,0.24,0.045)*plume.strength*0.10*exp(-height/8.0);
            soot+=NirnCraftLightningVolume(player)*0.25;
            float tau=sigma*stepLength;
            opticalDepth+=tau; scattering+=soot*tau;
        }
    }
    if(opticalDepth>0.00001)
        AccumulateNirnCraftVolume(volume,scattering/opticalDepth,1.0-exp(-opticalDepth));
}

float NirnCraftFogProfileIntegral(float h) {
    if (h <= 0.0) return 0.0;
    if (h <= 4.0) return h;
    float u = clamp((h - 4.0) / 56.0, 0.0, 1.0);
    return 4.0 + 56.0 * (u - u * u * u + 0.5 * u * u * u * u);
}

vec4 GetNirnCraftRainCurtains(int cloudAltitude, float distanceThreshold, float skyFade, float skyMult0, vec3 cameraPos, vec3 nPlayerPos, float lViewPosM, float VdotS, float VdotU, float dither, inout float cloudLinearDepth) {
    // Let the volume's smooth height/distance profile bound every ray. An angular early-out
    // cuts a hard circular seam through snow fog and dust when the camera tilts.
    vec4 camW = SampleNirnCraftWeather(vec2(0.0));
    int camAerosol = clamp(int(floor(camW.a * 4.0)), 0, 3);
    // Allow close-range 3D snow-fog banks in blizzards (Ref 5) and low-level haboob dust walls in deserts (Ref 4)
    float minDist = 1.0;
    float maxDist = min(min(lViewPosM, distanceThreshold * 0.90), 14000.0);
    if (maxDist <= minDist) return vec4(0.0);

    float curtainBotY = 48.0;
    float curtainTopY = float(cloudAltitude) - cloudStretch * 0.55;
    vec4 accum = vec4(0.0);

    // NIRNCRAFT_NEAR_MIST: metre-scale integration resolves local extinction and
    // propagated lamp light independently of kilometre-scale rain-band samples.
    #if CLOUD_QUALITY <= 1
        const int MIST_STEPS = 12;
    #else
        const int MIST_STEPS = 16;
    #endif
    vec3 nearAurora = NirnCraftAuroraIrradiance(vec3(0.0, 1.0, 0.0));
    float mistEnd = min(maxDist, 128.0);
    for (int m = 0; m < MIST_STEPS; m++) {
        float a = float(m) / float(MIST_STEPS);
        float b = float(m + 1) / float(MIST_STEPS);
        // Fixed integration intervals: a moving depth edge only clips the final
        // interval instead of repositioning every sample through the room fog.
        float start = 128.0 * a * a;
        float end = min(mistEnd, 128.0 * b * b);
        if (end <= start) break;
        float distanceM = (start + end) * 0.5;
        vec3 playerPos = nPlayerPos * distanceM;
        vec3 worldPos = cameraPos + playerPos;
        vec4 dynamics = SampleNirnCraftDynamics(playerPos.xz);
        vec4 mistWeather = SampleNirnCraftWeather(playerPos.xz);
        vec4 room;
        float sigma = NirnCraftMistDensity(worldPos, dynamics, mistWeather, room);
        if (sigma > 0.00001) {
            vec3 lamps = NirnCraftFogRadiance(playerPos);
            vec3 outdoorColor = cloudAmbientColor * 0.55 + lamps;
            vec3 indoorColor = outdoorColor;
            if (room.a > 0.0) indoorColor = room.rgb * (cloudAmbientColor * 0.04 + lamps
                    + NirnCraftLocalLight(playerPos, vec3(0.0), false));
            vec3 mistColor = mix(outdoorColor, indoorColor, clamp01(room.a / max(0.00001, sigma)));
            mistColor += nearAurora * (0.20 - 0.14 * mistWeather.r)
                       * NirnCraftOutdoorLightExposure(playerPos);
            mistColor += NirnCraftLightningVolume(playerPos);
            AccumulateNirnCraftVolume(accum, mistColor, 1.0 - exp(-sigma * (end - start)));
            if (accum.a > 0.98) cloudLinearDepth = min(cloudLinearDepth, sqrt(end / NIRNCRAFT_CLOUD_DEPTH_RANGE));
        }
    }

    // No distant interval exists behind this scene depth. Keep identical near integration.
    if (maxDist <= 128.0) {
        accum.rgb /= max(accum.a, 0.0001);
        return accum;
    }
    vec3 cloudSkyColor = GetSky(VdotU, VdotS, dither, isEyeInWater == 0, false);
    #ifdef ATM_COLOR_MULTS
        cloudSkyColor *= sqrtAtmColorMult;
    #endif

    vec3 worldLightVec = normalize(mat3(gbufferModelViewInverse) * lightVec);
    float sunShaftGate = smoothstep(0.05, 0.20, worldLightVec.y) * sunVisibility * smoothstep(-0.15, 0.85, VdotS);

    #if CLOUD_QUALITY <= 1
        const int CURTAIN_STEPS = 12;
    #else
        const int CURTAIN_STEPS = 18;
    #endif
    for (int s = 0; s < CURTAIN_STEPS; s++) {
        // World-fixed intervals: rescaling all samples to each pixel's DH depth
        // made neighbouring LOD tiles sample different volumes, exposing rectangular seams.
        float t0 = float(s) / float(CURTAIN_STEPS);
        float t1 = float(s + 1) / float(CURTAIN_STEPS);
        float marchRange = min(distanceThreshold * 0.90, 14000.0);
        float segmentStart = mix(128.0, marchRange, t0 * t0);
        float segmentEnd = min(maxDist, mix(128.0, marchRange, t1 * t1));
        if (segmentStart >= maxDist) break;
        float d = mix(segmentStart, segmentEnd, 0.35 + 0.30 * dither);

        vec3 rPos = cameraPos + nPlayerPos * d;
        if (accum.a > 0.98) cloudLinearDepth = min(cloudLinearDepth, sqrt(d / NIRNCRAFT_CLOUD_DEPTH_RANGE));
        vec2 quickXZ = rPos.xz - cameraPos.xz;
        vec4 rawW = SampleNirnCraftWeather(quickXZ);
        vec4 dynamics = SampleNirnCraftDynamics(quickXZ);
        float surfaceY = max(63.0, dynamics.g * 1024.0);
        float condensationY = dynamics.r * 1024.0;
        int stepAerosol = clamp(int(floor(rawW.a * 4.0)), 0, 3);

        // Dew-point fog occupies the moist surface layer and clears above it.
        float fogDrive = dynamics.a;
        float fogHeight = rPos.y - surfaceY;
        if (fogDrive > 0.02) {
            float h0 = cameraPos.y + nPlayerPos.y * segmentStart - surfaceY;
            float h1 = cameraPos.y + nPlayerPos.y * segmentEnd - surfaceY;
            float integratedProfile = abs(nPlayerPos.y) > 0.0001
                ? abs(NirnCraftFogProfileIntegral(h1) - NirnCraftFogProfileIntegral(h0)) / abs(nPlayerPos.y)
                : (segmentEnd - segmentStart) * (1.0 - smoothstep(4.0, 60.0, fogHeight)) * step(0.0, fogHeight);
            float fogAlpha = 1.0 - exp(-(0.0005 * fogDrive + 0.075 * pow(fogDrive, 3.0)) * integratedProfile * 0.85);
            vec3 fogAirColor = mix(cloudAmbientColor * 0.55, cloudSkyColor, 1.0 - exp(-d * 0.0004));
            fogAirColor += NirnCraftAuroraIrradiance(vec3(0.0, 1.0, 0.0)) * (0.20 - 0.14 * rawW.r);
            AccumulateNirnCraftVolume(accum, fogAirColor, fogAlpha);
        }

        // Determine ceiling of the sub-cloud / boundary-layer phenomenon for this aerosol regime
        float localTopY = (stepAerosol == 2)
                ? (surfaceY + 80.0 + 115.0 * clamp01(rawW.b * 0.75 + rawW.g * 0.55))
                : condensationY;
        curtainBotY = surfaceY;
        if (abs(nPlayerPos.y) > 0.0001) {
            float a = (curtainBotY - cameraPos.y) / nPlayerPos.y;
            float b = (localTopY + 22.0 - cameraPos.y) / nPlayerPos.y;
            segmentStart = max(segmentStart, min(a, b));
            segmentEnd = min(segmentEnd, max(a, b));
            if (segmentEnd <= segmentStart) continue;
            d = mix(segmentStart, segmentEnd, 0.35 + 0.30 * dither);
            rPos = cameraPos + nPlayerPos * d;
            quickXZ = rPos.xz - cameraPos.xz;
        }
        if (rPos.y < curtainBotY || rPos.y > localTopY + 22.0) continue;

        float fallDist = max(0.0, localTopY - rPos.y);
        vec2 windSlant = NirnCraftWind(dynamics) * fallDist * ((stepAerosol == 2) ? 0.04 : 0.12);
        vec2 relXZ = quickXZ + windSlant;
        vec4 skyW = SampleNirnCraftWeather(relXZ);
        float skyCov = skyW.r;
        float skyTower = skyW.g;
        float curtain = skyW.b * NirnCraftWeatherExposure(rPos - cameraPos);
        float skyAerosolRaw = skyW.a * 4.0;
        int skyAerosol = clamp(int(floor(skyAerosolRaw)), 0, 3);
        float skyFlash = NirnCraftFlashAt(quickXZ);

        float vFade = smoothstep(curtainBotY, curtainBotY + 18.0, rPos.y)
                    * (1.0 - smoothstep(localTopY - 14.0, localTopY + 22.0, rPos.y));

        if (curtain < 0.06) continue;
        vec2 worldXZ = GetNirnCraftWorldXZ(relXZ);

        // 2A. Towering Desert Haboob / Rolling Sandstorm Wall (skyAerosol == 2, Ref 4)
        if (skyAerosol == 2) {
            float hHeight01 = clamp01((rPos.y - curtainBotY) / max(24.0, localTopY - curtainBotY));
            vec3 haboobPos = vec3(worldXZ.x * 0.0016 - NirnCraftWeatherTime() * 0.0035, rPos.y * 0.0022, worldXZ.y * 0.0016 - NirnCraftWeatherTime() * 0.0020);
            // All quality levels retain depth variation; a 2D extrusion creates rigid vertical curtains.
                float n1 = NirnCraftCloudNoise(haboobPos * 4.0);
                float n2 = NirnCraftCloudNoise(haboobPos * 10.6 + vec3(0.0, -NirnCraftWeatherTime() * 0.016, 0.0));
                // Cauliflower billow lobes & vertical clefts along the haboob front
                float billow1 = 1.0 - abs(n1 * 2.0 - 1.0);
                float billow2 = 1.0 - abs(n2 * 2.0 - 1.0);
                float lobeField = mix(n1, billow1, 0.62) * 0.65 + mix(n2, billow2, 0.55) * 0.35;
            // Dense lower roll with billowy cauliflower crest at top (hHeight01 ~ 0.65..1.0)
            float crestProfile = smoothstep(0.0, 0.08, hHeight01) * (1.0 - smoothstep(0.58 + 0.32 * lobeField, 1.02, hHeight01));
            float dHabFade = smoothstep(38.0, 135.0, d) * (1.0 - smoothstep(880.0, 2900.0, d));
            float wallDensity = smoothstep(0.06, 0.62, curtain) * smoothstep(0.24, 0.64, lobeField * crestProfile);
            float stepAlpha = 1.0 - exp(-wallDensity * dHabFade * (0.002 + 0.004 * skyTower) * (segmentEnd - segmentStart));
            if (stepAlpha < 0.004) continue;

            // Self-shadowed terracotta/ochre/tan cauliflower billows with dark umber clefts (Ref 4)
            float lobeLight = clamp01(0.28 + 0.52 * hHeight01 + 0.38 * (lobeField - 0.45));
            vec3 dayLit = cloudAmbientColor * 0.72 + cloudLightColor * 0.58;
            vec3 haboobShadowCol = dayLit * vec3(0.54, 0.39, 0.27);
            vec3 haboobSunCol    = dayLit * vec3(1.06, 0.79, 0.53);
            vec3 curtainCol = mix(haboobShadowCol, haboobSunCol, lobeLight) + NirnCraftFogRadiance(rPos - cameraPos);
            curtainCol = mix(cloudSkyColor, curtainCol, 0.90 * exp(-d * 0.0004));
            curtainCol *= pow2(1.0 - maxBlindnessDarkness);
            AccumulateNirnCraftVolume(accum, curtainCol, stepAlpha);
            continue;
        }

        // 2B. Alpine Blizzard Whiteout & Ground-Hugging Snow Mist (skyAerosol == 1, Ref 5)
        if (skyAerosol == 1) {
            float sHeight01 = clamp01((rPos.y - curtainBotY) / max(24.0, localTopY - curtainBotY));
            vec3 snowPos = vec3(worldXZ.x * 0.0018 - NirnCraftWeatherTime() * 0.004, rPos.y * 0.0012 - NirnCraftWeatherTime() * 0.006, worldXZ.y * 0.0018);
            float snowMist = NirnCraftCloudNoise(snowPos * 4.0) * 0.62 + NirnCraftCloudNoise(snowPos * 9.6) * 0.38;
            // Thickest near the snowy slopes and pine trees (sHeight01 < 0.55), blending into the whiteout sky above
            float slopeHug = mix(1.0, 0.68, smoothstep(0.15, 0.75, sHeight01));
            float dSnowFade = smoothstep(16.0, 72.0, d) * (1.0 - smoothstep(750.0, 2600.0, d));
            float mistShaft = smoothstep(0.08, 0.68, curtain) * smoothstep(0.24, 0.66, snowMist * slopeHug);
            float stepAlpha = 1.0 - exp(-mistShaft * vFade * dSnowFade * (0.003 + 0.006 * skyCov) * (segmentEnd - segmentStart));
            if (stepAlpha < 0.004) continue;

            vec3 whiteoutLit = (cloudAmbientColor * 1.12 + cloudLightColor * 0.42) * vec3(0.95, 0.97, 1.01);
            vec3 curtainCol = mix(cloudSkyColor, whiteoutLit, 0.88 * exp(-d * 0.0004)) + NirnCraftFogRadiance(rPos - cameraPos);
            curtainCol *= pow2(1.0 - maxBlindnessDarkness);
            AccumulateNirnCraftVolume(accum, curtainCol, stepAlpha);
            continue;
        }

        // 2C. Distinct Vertical Rain Shafts / Virga Curtains & Volcanic Ash Plumes (skyAerosol == 0 or 3, Ref 3)
        float dRainFade = smoothstep(85.0, 260.0, d) * (1.0 - smoothstep(10000.0, 14000.0, d));
        // Broad advected rain volumes, with gentle falling structure rather than high-contrast poles.
        // The old low-quality XY lookup ignored world Z and extruded straight stripes across the landscape.
        vec3 streakPos = vec3(worldXZ.x * 0.012, rPos.y * 0.006 - NirnCraftWeatherTime() * 0.055, worldXZ.y * 0.012);
        streakPos.xz -= NirnCraftWind(dynamics) * NirnCraftWeatherTime() * 0.006;
        float macroShaft = curtain;
        float streak = NirnCraftCloudNoise(streakPos);
        float shaftCore = smoothstep(0.32, 0.68, macroShaft) * (0.65 + 0.35 * smoothstep(0.18, 0.82, streak));
        float shaftHaze = smoothstep(0.20, 0.52, macroShaft) * (1.0 - 0.65 * shaftCore);
        float curtainDrive = smoothstep(0.08, 0.72, curtain);
        float rainSegment = segmentEnd - segmentStart;
        float rainExtinction = (shaftCore * 0.0018 + shaftHaze * 0.0005) * curtainDrive * vFade * dRainFade * (0.75 + 1.8 * skyTower);
        float stepAlpha = 1.0 - exp(-rainExtinction * rainSegment);
        if (stepAlpha < 0.004) continue;

        float stormDark = smoothstep(0.30, 0.85, skyTower) * curtainDrive;
        vec3 baseLit = cloudAmbientColor * mix(0.52, 0.30, stormDark) + cloudLightColor * (0.08 * (1.0 - stormDark));
        // Dark grey-blue rain curtain core contrasted against warm sunlit atmospheric mist in the gaps between shafts (Ref 3)
        vec3 coreCol = mix(cloudSkyColor, baseLit * vec3(0.48, 0.54, 0.63), 0.82);
        vec3 gapSunCol = mix(cloudSkyColor, baseLit, 0.85);
        float sunGapMix = clamp01(shaftHaze * (1.0 - shaftCore) * (0.45 + 0.55 * sunVisibility));
        vec3 curtainCol = mix(coreCol, gapSunCol, sunGapMix);

        if (skyAerosol == 3) {
            curtainCol = mix(cloudSkyColor, cloudAmbientColor * vec3(0.42, 0.34, 0.32), 0.86);
        }

        curtainCol += NirnCraftLightningVolume(rPos - cameraPos) * 0.65;

        curtainCol = mix(cloudSkyColor, curtainCol, exp(-d * 0.0004));

        curtainCol *= pow2(1.0 - maxBlindnessDarkness);
        AccumulateNirnCraftVolume(accum, curtainCol, stepAlpha);
    }

    accum.rgb /= max(accum.a, 0.0001);
    return accum;
}

vec4 GetVolumetricClouds(int cloudAltitude, float distanceThreshold, inout float cloudLinearDepth, float skyFade, float skyMult0, vec3 cameraPos, vec3 nPlayerPos, float lViewPosM, float VdotS, float VdotU, float dither) {
    vec4 volumetricClouds = vec4(0.0);

    vec4 rainCurtains = GetNirnCraftRainCurtains(cloudAltitude, distanceThreshold, skyFade, skyMult0, cameraPos, nPlayerPos, lViewPosM, VdotS, VdotU, dither, cloudLinearDepth);
    // Dense snow/dust/near mist already transmits less than 0.05 percent.
    // Preserve the entire near volume and skip only its obscured cloud background.
    int nearAerosol = clamp(int(floor(SampleNirnCraftWeather(vec2(0.0)).a * 4.0)), 0, 3);
    if (rainCurtains.a > 0.9995 && (nearAerosol == 1 || nearAerosol == 2 || SampleNirnCraftDynamics(vec2(0.0)).a > 0.02))
        return rainCurtains;

    // Bounds include local condensation layers, elevated mountain caps and convective towers.
    float higherPlaneAltitude = 2048.0;
    float lowerPlaneAltitude = 32.0;

    // Keep near-horizontal rays finite, and march the entire visible interval even at the step cap.
    float rayY = abs(nPlayerPos.y) < 0.0001 ? (nPlayerPos.y < 0.0 ? -0.0001 : 0.0001) : nPlayerPos.y;
    float lowerPlaneDistance  = (lowerPlaneAltitude - cameraPos.y) / rayY;
    float higherPlaneDistance = (higherPlaneAltitude - cameraPos.y) / rayY;
    float minPlaneDistance = min(lowerPlaneDistance, higherPlaneDistance);
          minPlaneDistance = max(minPlaneDistance, 0.0);
    float maxPlaneDistance = max(lowerPlaneDistance, higherPlaneDistance);
    maxPlaneDistance = min(maxPlaneDistance, distanceThreshold / max(length(nPlayerPos.xz), 0.001));
    maxPlaneDistance = min(maxPlaneDistance, lViewPosM);
    if (maxPlaneDistance < 0.0) return rainCurtains;
    float planeDistanceDif = maxPlaneDistance - minPlaneDistance;
    if (planeDistanceDif <= 0.0) return rainCurtains;
    NirnCraftInitFireRay(cameraPos,nPlayerPos,lViewPosM);

    vec3 auroraTopIrradiance = NirnCraftAuroraIrradiance(vec3(0.0, 1.0, 0.0));
    float aboveClouds = clamp01((cameraPos.y - lowerPlaneAltitude) * 0.01);
    float skyFadeM = mix(skyFade, 1.0, 0.4 * max(aboveClouds, rainFactor2));

    #ifndef DEFERRED1
        float stepMult = 64.0;
    #elif CLOUD_QUALITY == 1
        float stepMult = 16.0;
    #elif CLOUD_QUALITY == 2
        float stepMult = 26.0;
    #elif CLOUD_QUALITY == 3
        float stepMult = 16.0;
    #endif

    #if CLOUD_UNBOUND_SIZE_MULT > 100
        stepMult = stepMult / sqrt(float(CLOUD_UNBOUND_SIZE_MULT_M));
    #endif

    int sampleCount;
    #if CLOUD_QUALITY <= 1
        sampleCount = 128;
    #elif CLOUD_QUALITY == 2
        sampleCount = 192;
    #else
        sampleCount = 256;
    #endif
    #ifdef FIX_AMD_REFLECTION_CRASH
        sampleCount = min(sampleCount, 30);
    #endif
    float stepLength = 0.0;
    float renderBudget = NirnCraftRenderBudget();
    sampleCount = int(float(sampleCount) * renderBudget);
    vec3 tracePos = cameraPos;
    float traceDistance = minPlaneDistance;

    float firstHitPos = -1.0;
    float VdotSM1 = max0(dot(nPlayerPos, nirncraftKeyDirection));
    float VdotSM1M = VdotSM1 * invRainFactor;
    float VdotSM2 = pow2(VdotSM1) * abs(sunVisibility - 0.5) * 2.0;
    float VdotSM3 = VdotSM2 * (2.8 + rainFactor) + 1.5 * rainFactor;
    float VdotSM4 = pow(VdotSM1M, 80.0) * sunVisibility;

    vec3 skyLightDir = normalize(mat3(gbufferModelViewInverse) * lightVec);
    // View-ray invariants: evaluating sky colour for each occupied cloud sample
    // repeated the same atmospheric work hundreds of times per pixel.
    vec3 cloudSkyColor = GetSky(VdotU, VdotS, dither, isEyeInWater == 0, false);
    #ifdef ATM_COLOR_MULTS
        cloudSkyColor *= sqrtAtmColorMult;
    #endif
    vec3 airColor = mix(nightMiddleSkyColor * 0.75, dayMiddleSkyColor * 0.85, sunVisibility2);
    float cachedSunTransmission = 1.0;
    // NIRNCRAFT_COMPLETE_CLOUD_INTERVAL: spend the bounded sample count over the
    // entire clipped interval. A fixed detail step exhausted the loop near the
    // camera and cut distant upper clouds into a circular footprint.
    float intervalGrowth = exp(log(1.0 + planeDistanceDif / 96.0) / float(sampleCount));
    float intervalStep = 96.0 * (intervalGrowth - 1.0);
    for (int i = 0; i < sampleCount; i++) {
        if (traceDistance >= maxPlaneDistance) break;
        // The slab intersection already clips the ray. Testing the exact entry
        // height again can reject downward rays from above due to float rounding.
        float intervalEnd = i == sampleCount - 1 ? maxPlaneDistance
                : min(maxPlaneDistance, traceDistance + intervalStep);
        stepLength = max(0.0, intervalEnd - traceDistance);
        NirnCraftIntegrateFire(volumetricClouds,firstHitPos,cameraPos,nPlayerPos,traceDistance,intervalEnd);
        float withinStep = fract(dither + float(i) * 0.61803398875);
        tracePos = cameraPos + nPlayerPos * (traceDistance + stepLength * withinStep);
        traceDistance = intervalEnd;
        intervalStep *= intervalGrowth;
        vec3 cloudPlayerPos = tracePos - cameraPos;
        float lTracePos = length(cloudPlayerPos);
        float lTracePosXZ = length(cloudPlayerPos.xz);
        float cloudMult = 1.0;
        vec4 skyW = SampleNirnCraftWeather(cloudPlayerPos.xz);
        float skyCov = skyW.r;
        float skyTower = skyW.g;
        float skyCurtain = skyW.b;
        float localLower, localHigher;
        float cloudNoise = GetCloudNoise(tracePos, cloudAltitude, lTracePosXZ, cloudPlayerPos.y, cloudPlayerPos.xz,
                skyCov, skyTower, skyCurtain, localLower, localHigher);
        cloudNoise *= NirnCraftWeatherExposure(cloudPlayerPos);

        if (cloudNoise > 0.00001) {
            // A mountain shadow attenuates illumination, never density. The pack's
            // closed-area skip carved triangular holes through distant clouds.

            firstHitPos = firstHitPos < 0.0 ? lTracePos : min(firstHitPos,lTracePos);

            float effectiveCov = max(skyCov, skyCurtain * 0.68);
            float skyAerosolRaw = skyW.a * 4.0;
            int skyAerosol = clamp(int(floor(skyAerosolRaw)), 0, 3);
            float skyFlash = clamp((fract(skyAerosolRaw) - 0.08) / 0.84, 0.0, 1.0);

            float cloudHeight01 = clamp01((tracePos.y - localLower) / max(12.0, localHigher - localLower));
            float opacityFactor = clamp01(cloudNoise * 8.0);
            // Evaluate the same 3D density toward the sun/moon at each view sample.
            { // Evaluate at every sample: reusing four discrete light values creates contour bands.
                float opticalDepth = 0.0;
                #if CLOUD_QUALITY <= 1
                    const int LIGHT_STEPS = 2;
                #else
                    const int LIGHT_STEPS = 4;
                #endif
                float previousDistance = 0.0;
                for (int l = 0; l < LIGHT_STEPS; l++) {
                    float lightDistance = 32.0 * pow(3.0, float(l));
                    vec3 lightPos = tracePos + skyLightDir * lightDistance;
                    vec4 lightW = SampleNirnCraftWeather(lightPos.xz - cameraPos.xz);
                    float lightLow, lightHigh;
                    float lightDensity = max0(GetCloudNoise(lightPos, cloudAltitude, lTracePosXZ, lightPos.y - cameraPos.y, lightPos.xz - cameraPos.xz,
                            lightW.r, lightW.g, lightW.b, lightLow, lightHigh));
                    opticalDepth += lightDensity * (lightDistance - previousDistance) * 0.18;
                    previousDistance = lightDistance;
                }
                cachedSunTransmission = exp(-opticalDepth) * NirnCraftTerrainTransmission(cloudPlayerPos, skyLightDir);
            }
            float phase = 0.65 + 0.35 * pow(VdotSM1, 8.0);
            float ambientFill = 0.16 + 0.25 * cloudHeight01 + 0.14 * cachedSunTransmission;
            vec3 colorSample = cloudAmbientColor * ambientFill
                    + NirnCraftKeyRadiance(tracePos.y) * (cachedSunTransmission * phase * 0.81);
            // Shared transient auroral emission, shielded through the cloud depth.
            colorSample += auroraTopIrradiance * exp(-(1.0 - cloudHeight01) * (1.0 - cloudHeight01) * 4.0);
            float smokeDensity = NirnCraftPlumeDensity(tracePos, cloudPlayerPos.xz);
            if (smokeDensity > 0.01)
                colorSample *= mix(vec3(1.0), vec3(0.25, 0.23, 0.22), clamp01(smokeDensity * 3.0));
            colorSample += NirnCraftFogRadiance(cloudPlayerPos);
            float sunlitTurret = cachedSunTransmission * cloudHeight01;

            if (skyAerosol == 2) {
                colorSample = mix(colorSample, colorSample * vec3(1.12, 0.88, 0.62), clamp01(skyTower * 0.85));
            } else if (skyAerosol == 3) {
                colorSample = mix(colorSample, colorSample * vec3(0.58, 0.46, 0.42), clamp01(skyTower * 0.90));
            }

            // Shared 3D channel sources reveal illuminated lobes behind denser cloud.
            colorSample += NirnCraftLightningCloud(cloudPlayerPos, cloudAltitude) * (0.35 + 0.65 * opacityFactor);

            float distanceRatio = clamp((distanceThreshold - lTracePosXZ) / distanceThreshold, 0.0, 1.0);
            float cloudDistanceFactor = smoothstep(0.0, 0.82, distanceRatio);
        // Match the scale of the DH atmospheric veil instead of leaving opaque
        // dark clouds pasted over a nearly white distant landscape.
            float cloudFogFactor = exp(-lTracePosXZ * (0.00016 + 0.00032 * skyCurtain));
            colorSample *= 1.0 - 0.18 * (1.0 - skyFadeM) * max(sunVisibility2, nightFactor);
            colorSample = mix(airColor, colorSample, cloudFogFactor);
            colorSample *= pow2(1.0 - maxBlindnessDarkness);

            float sampleAlpha = (1.0 - exp(-cloudNoise * stepLength * 0.18))
                    * pow(cloudDistanceFactor, 0.45) * cloudMult;
            float sampleWeight = (1.0 - volumetricClouds.a) * clamp01(sampleAlpha);
            volumetricClouds.rgb += colorSample * sampleWeight;
            volumetricClouds.a += sampleWeight;

        }
        // All participating media share this accumulator. Stop only when its
        // remaining float weight is exactly zero, preserving bright HDR tails.
        if (volumetricClouds.a >= 1.0) break;
    }

    if (volumetricClouds.a > 0.98) cloudLinearDepth = min(cloudLinearDepth, sqrt(firstHitPos / NIRNCRAFT_CLOUD_DEPTH_RANGE));

    volumetricClouds.rgb /= max(volumetricClouds.a, 0.0001);
    // Geometry depth clips the ray; terrain visibility must not reduce extinction.
    // Multiplying by skyFade leaves every mountain visible through opaque clouds.

    if (rainCurtains.a > 0.001) {
        // Ground-hugging haboob walls and snow fog sit in front of the high cloud deck.
        // Keep the order across every viewing angle to avoid a circular compositing seam.
        vec4 camWNow = SampleNirnCraftWeather(vec2(0.0));
        int camAerNow = clamp(int(floor(camWNow.a * 4.0)), 0, 3);
        if (camAerNow == 1 || camAerNow == 2 || SampleNirnCraftDynamics(vec2(0.0)).a > 0.02) {
            float combinedA = clamp01(volumetricClouds.a + rainCurtains.a * (1.0 - volumetricClouds.a));
            vec3 combinedRgb = rainCurtains.rgb * rainCurtains.a
                    + volumetricClouds.rgb * volumetricClouds.a * (1.0 - rainCurtains.a);
            volumetricClouds = vec4(combinedRgb / max(combinedA, 0.0001), combinedA);
        } else {
            float combinedA = clamp01(volumetricClouds.a + rainCurtains.a * (1.0 - volumetricClouds.a));
            vec3 combinedRgb = volumetricClouds.rgb * volumetricClouds.a
                    + rainCurtains.rgb * rainCurtains.a * (1.0 - volumetricClouds.a);
            volumetricClouds = vec4(combinedRgb / max(combinedA, 0.0001), combinedA);
        }
    }

    return volumetricClouds;
}
"""

CLOUD_SHADOWS_GLSL = """#ifndef INCLUDE_CLOUD_SHADOWS
    #define INCLUDE_CLOUD_SHADOWS

    #ifdef CLOUDS_REIMAGINED
        #include "/lib/atmospherics/clouds/cloudCoord.glsl"
    #else
        #include "/lib/atmospherics/clouds/nirncraftDensity.glsl"
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
            // Project to the same density field used by the visible cloud raymarcher.
            vec3 worldLightVec = normalize(mat3(gbufferModelViewInverse) * lightVec);
            vec3 cameraPos = cameraPosition;
            cameraPos.xz = GetNirnCraftWorldXZ(vec2(0.0));
            if (worldLightVec.y <= 0.015) return 1.0;
            float lightY = max(worldLightVec.y, 0.08);
            vec3 receiver = cameraPos + playerPos;
            vec4 dynamics = SampleNirnCraftDynamics(playerPos.xz);
            vec4 weather = SampleNirnCraftWeather(playerPos.xz);
            float opticalDepth = 0.0;
            // Refine the projection with the condensation height at the cloud, then
            // integrate the same extinction used by the visible volume along the light.
            float middleDistance = max(0.0, (dynamics.r * 1024.0 + NirnCraftCloudThickness(weather) * 0.5 - receiver.y) / lightY);
            vec2 projectedXZ = playerPos.xz + worldLightVec.xz * min(middleDistance, 24000.0);
            dynamics = SampleNirnCraftDynamics(projectedXZ);
            weather = SampleNirnCraftWeather(projectedXZ);
            float thickness = NirnCraftCloudThickness(weather);
            float lowDistance = max(0.0, (dynamics.r * 1024.0 - receiver.y) / lightY);
            float highDistance = max(0.0, (dynamics.r * 1024.0 + thickness - receiver.y) / lightY);
            float segmentLength = min(12000.0, highDistance - lowDistance) * 0.5;
            for (int s = 0; s < 2; s++) {
                float distToCloud = lowDistance + (float(s) + 0.5) * segmentLength;
                vec3 tracePos = receiver + worldLightVec * distToCloud;
                vec2 offsetXZ = tracePos.xz - cameraPos.xz;
                vec4 skyW = SampleNirnCraftWeather(offsetXZ);
                float localLower, localHigher;
                float density = GetCloudNoise(tracePos, cloudAlt1i, distToCloud, tracePos.y - cameraPos.y, offsetXZ,
                        skyW.r, skyW.g, skyW.b, localLower, localHigher);
                opticalDepth += max(0.0, density) * segmentLength * 0.18;
            }
            // Middle water clouds and high ice clouds attenuate the same light.
            for (int layer = 0; layer < 2; layer++) {
                float altitude = layer == 0 ? 740.0 : 1100.0;
                float distanceM = (altitude - receiver.y) / lightY;
                if (distanceM <= 0.0 || distanceM > 30000.0) continue;
                vec3 point = receiver + worldLightVec * distanceM;
                vec2 offset = point.xz - cameraPos.xz;
                vec4 w = SampleNirnCraftWeather(offset);
                float density = layer == 0 ? NirnCraftMiddleCloudDensity(point, w)
                        : NirnCraftHighCloudDensity(point, SampleNirnCraftDynamics(offset), w.r, w.g);
                opticalDepth += density * (180.0 / lightY) * 0.18;
            }
            float transmittance = exp(-opticalDepth);
            float sunElevFade = smoothstep(0.02, 0.14, abs(worldLightVec.y));
            cloudShadow = mix(1.0, transmittance, sunElevFade);
        #endif

        #ifndef CLOUDS_REIMAGINED
            cloudShadow *= NirnCraftTerrainTransmission(playerPos, normalize(mat3(gbufferModelViewInverse) * lightVec));
        #endif
        return cloudShadow;
    }

#endif
"""


def patch_uniforms_glsl(text: str) -> str:
    if "NIRNCRAFT_CLOUD_DEPTH_RANGE" not in text:
        text += '\n#ifdef CLOUDS_UNBOUND\n#define NIRNCRAFT_CLOUD_DEPTH_RANGE 30000.0\n#else\n#define NIRNCRAFT_CLOUD_DEPTH_RANGE renderDistance\n#endif\n'
    for name in ("nirncraftDynamicsTex", "nirncraftDynamicsFarTex", "nirncraftFogRadianceTex", "nirncraftLocalOcclusionTex", "nirncraftSmokeTex", "nirncraftCloudNoiseTex", "nirncraftMasserTex", "nirncraftSecundaTex", "nirncraftGalaxyTex", "nirncraftAtmosphereTex", "nirncraftLightningTex"):
        if name not in text:
            text += "\r\nuniform sampler2D " + name + ";\r\n"
    if "nirncraftWeatherFarTex" not in text and "uniform sampler2D nirncraftWeatherTex;" in text:
        text = text.replace("uniform sampler2D nirncraftWeatherTex;", "uniform sampler2D nirncraftWeatherTex;\r\nuniform sampler2D nirncraftWeatherFarTex;", 1)
    if "nirncraftWeatherTex" in text:
        return text
    target = "uniform sampler2D noisetex;"
    replacement = target + "\r\nuniform sampler2D nirncraftWeatherTex;\r\nuniform sampler2D nirncraftWeatherFarTex;"
    if target not in text:
        raise RuntimeError("Could not find `uniform sampler2D noisetex;` in uniforms.glsl")
    return text.replace(target, replacement, 1)


def patch_shaders_properties(text: str) -> str:
    # Remove the rejected lighting-cache experiment when upgrading a preview.
    text=re.sub(r"(?m)^size\.buffer\.colortex9\s*=.*(?:\n|$)", "", text)
    if "nirncraftAtmosphereTex" not in text:
        text += "\ncustomTexture.nirncraftAtmosphereTex = nirncraft:textures/environment/atmosphere_transmittance.png\n"
    for name, resource in (("nirncraftLightningTex", "lightning_sources"), ("nirncraftDynamicsTex", "weather_dynamics"), ("nirncraftDynamicsFarTex", "weather_dynamics_far"), ("nirncraftFogRadianceTex", "fog_radiance"), ("nirncraftLocalOcclusionTex", "local_occlusion"), ("nirncraftSmokeTex", "smoke_sources"), ("nirncraftCloudNoiseTex", "cloud_noise_volume"), ("nirncraftMasserTex", "masser_phases"), ("nirncraftSecundaTex", "secunda_phases"), ("nirncraftGalaxyTex", "galaxy")):
        if name not in text:
            text += "\r\ncustomTexture." + name + " = nirncraft:textures/environment/" + resource + ".png\r\n"
    for name, resource in CELESTIAL_ASSETS.items():
        # Static LUT/material data lives inside the shader ZIP too, so visual iteration
        # and standalone shader loading do not retain a stale resource-manager texture.
        text = re.sub(r"customTexture\." + name + r"\s*=\s*[^\r\n]+",
                      "customTexture." + name + " = lib/textures/nirncraft_" + resource + ".png", text)
    for name, resource in (("nirncraftWeatherTex", "weather_field"), ("nirncraftWeatherFarTex", "weather_field_far")):
        if "customTexture." + name not in text:
            text += "\ncustomTexture." + name + " = nirncraft:textures/environment/" + resource + ".png\n"
    text = re.sub(r"(?m)^([ \t]*shadow\.culling[ \t]*=)[^\r\n]*", r"\1 false", text)
    if "NIRNCRAFT_LUNAR_CULLING" not in text:
        text += "\n# NIRNCRAFT_LUNAR_CULLING: retain casters for rotated night maps.\nshadow.culling = false\n"
    return text


def patch_main_clouds_glsl(text: str) -> str:
    if "NIRNCRAFT_ABSOLUTE_PARCELS" not in text:
        text = text.replace("    cameraPos += cameraPosOffset;",
            "    // NIRNCRAFT_ABSOLUTE_PARCELS: procedural parcels do not repeat at the old noise texture's wrap.\n"
            "    #ifdef CLOUDS_UNBOUND\n"
            "        cameraPos.xz = GetNirnCraftWorldXZ(vec2(0.0));\n"
            "    #endif\n    cameraPos += cameraPosOffset;", 1)
    if "NIRNCRAFT_GEOMETRY_DEPTH" not in text:
        depth_anchor = "    float skyMult0 ="
        depth = ("    // NIRNCRAFT_GEOMETRY_DEPTH: fog never makes mountains transparent to clouds.\n"
                 "    #ifdef DEFERRED1\n"
                 "        float skyGeometryDepth = texelFetch(depthtex0, ivec2(gl_FragCoord.xy), 0).r;\n"
                 "        #ifdef DISTANT_HORIZONS\n"
                 "            skyGeometryDepth = min(skyGeometryDepth, texelFetch(dhDepthTex, ivec2(gl_FragCoord.xy), 0).r);\n"
                 "        #endif\n"
                 "        lViewPosM = skyGeometryDepth < 1.0 ? lViewPos - 1.0 : 1000000000.0;\n"
                 "    #endif\n")
        # Insert after the existing upgrade's distance declaration below.
    if "NIRNCRAFT_WEATHER_HORIZON" not in text:
        anchor = "    //float thresholdMix"
        text = text.replace(anchor, "    // NIRNCRAFT_WEATHER_HORIZON: coarse weather covers the DH vista without wrapping the local field.\r\n    #ifdef DISTANT_HORIZONS\r\n        thresholdF = min(30000.0, max(thresholdF, float(dhRenderDistance)));\r\n    #endif\r\n" + anchor, 1)
    old_block = "    float lViewPosM = lViewPos < renderDistance * 1.5 ? lViewPos - 1.0 : 1000000000.0;"
    new_block = (
        "    #ifdef DISTANT_HORIZONS\r\n"
        "        float maxNirnCraftTerrainDist = max(float(dhRenderDistance), renderDistance * 1.5);\r\n"
        "        float lViewPosM = (skyFade < 0.995 && lViewPos < maxNirnCraftTerrainDist) ? lViewPos - 1.0 : 1000000000.0;\r\n"
        "    #else\r\n"
        "        float lViewPosM = lViewPos < renderDistance * 1.5 ? lViewPos - 1.0 : 1000000000.0;\r\n"
        "    #endif"
    )
    if "maxNirnCraftTerrainDist" not in text:
        if old_block not in text:
            raise RuntimeError("Could not find `lViewPosM` line in mainClouds.glsl")
        text = text.replace(old_block, new_block, 1)
    if "NIRNCRAFT_GEOMETRY_DEPTH" not in text:
        text = text.replace(depth_anchor, depth + depth_anchor, 1)
    return text


def patch_volumetric_light_glsl(text: str) -> str:
    old_lunar_density = "vlSceneIntensity = 0.35 * (0.08 + 0.92 * pow(abs(float(moonPhase) - 4.0) * 0.25, 2.0));"
    previous_density = "vlSceneIntensity = 0.35 * dot(texelFetch(nirncraftSmokeTex, ivec2(3, 1), 0).ba, vec2(0.80, 0.20));"
    lunar_density = "vlSceneIntensity = 0.35 * dot(nirncraftMoonWeights, vec2(0.80, 0.20));"
    text = text.replace(previous_density, lunar_density)
    text = text.replace(old_lunar_density, lunar_density)
    text = text.replace("vlSceneIntensity = 0.0;", lunar_density)
    text = text.replace("vlSceneIntensity = 0.35 * moonPhaseInfluence;", lunar_density)
    # Upgrade the earlier camera-gated shaft effect to per-sample transmission.
    # Diffuse airlight belongs to the fog pass; blocked direct shafts contribute zero.
    text = re.sub(r"float cloudMod =[^;]+;", "float cloudMod = cloudTrans;", text)
    text = text.replace("localDensity *= cloudMod * overcastDim;", "localDensity *= cloudMod;")
    # Support both fresh and previously patched volumetricLight.glsl
    if "NIRNCRAFT_CREPUSCULAR_SHAFTS" not in text:
        header_target = "vec4 DistortShadow(vec4 shadowpos, float distortFactor) {"
        header_repl = (
            '// NIRNCRAFT_CREPUSCULAR_SHAFTS\r\n'
            '#include "/lib/lighting/cloudShadows.glsl"\r\n\r\n'
            + header_target
        )
        if header_target not in text:
            raise RuntimeError("Could not find `DistortShadow` in volumetricLight.glsl")
        text = text.replace(header_target, header_repl, 1)

    new_scene_block = (
        "        vec4 skyCamW = texture2DLod(nirncraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        int skyCamAer = clamp(int(floor(skyCamW.a * 4.0)), 0, 3);\r\n"
        "        float skyCrepuscularDrive = smoothstep(0.20, 0.46, skyCamW.r) * (1.0 - 0.82 * smoothstep(0.76, 0.92, skyCamW.r));\r\n"
        "        float skyBlizzardFogDrive = (skyCamAer == 1) ? smoothstep(0.12, 0.65, skyCamW.b) * 0.88 : 0.0;\r\n"
        "        float vlSceneIntensity = isEyeInWater != 1 ? max(vlFactor, max(0.62 * skyCrepuscularDrive, skyBlizzardFogDrive)) : 1.0;"
    )
    old_scene_v2 = (
        "        vec4 skyCamW = texture2DLod(nirncraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        float skyCrepuscularDrive = smoothstep(0.20, 0.46, skyCamW.r) * (1.0 - 0.82 * smoothstep(0.76, 0.92, skyCamW.r));\r\n"
        "        float vlSceneIntensity = isEyeInWater != 1 ? max(vlFactor, 0.62 * skyCrepuscularDrive) : 1.0;"
    )
    old_scene_v1 = (
        "        vec4 skyCamW = texture2DLod(nirncraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        float skyCrepuscularDrive = smoothstep(0.04, 0.42, skyCamW.r) * (1.0 - 0.38 * smoothstep(0.88, 0.99, skyCamW.r));\r\n"
        "        float vlSceneIntensity = isEyeInWater != 1 ? max(vlFactor, 0.52 + 0.42 * skyCrepuscularDrive) : 1.0;"
    )
    orig_scene = "        float vlSceneIntensity = isEyeInWater != 1 ? vlFactor : 1.0;"
    for cand in (old_scene_v2, old_scene_v2.replace("\r\n", "\n"), old_scene_v1, old_scene_v1.replace("\r\n", "\n"), orig_scene):
        if cand in text:
            text = text.replace(cand, new_scene_block, 1)
            break

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
        "                float overcastDim = (skyCamAer == 1) ? (0.85 + 0.45 * skyBlizzardFogDrive) : (1.0 - 0.78 * smoothstep(0.75, 0.92, skyCamW.r));\r\n"
        "                float shaftContrast = smoothstep(0.24, 0.88, cloudTrans);\r\n"
        "                float distBoost = smoothstep(85.0, 380.0, nextDistance);\r\n"
        "                float cloudMod = cloudTrans;\r\n"
        "                localDensity *= cloudMod;\r\n"
        "            }\r\n"
        "        #endif\r\n\r\n"
    )
    old_crep_v2 = (
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
    for cand in (old_crep_v2, old_crep_v2.replace("\r\n", "\n"), old_crep_v1, old_crep_v1.replace("\r\n", "\n")):
        if cand in text:
            text = text.replace(cand, new_crep_block, 1)
            break
    else:
        if "float cloudTrans = GetCloudShadow(scenePos);" not in text and smoke_anchor in text:
            text = text.replace(smoke_anchor, new_crep_block + smoke_anchor, 1)

    if "NIRNCRAFT_SHAFT_EXTINCTION" not in text:
        # A normalized fog curve reaches full brightness at the configured render
        # distance regardless of how little aerosol is present. Integrate actual
        # path extinction instead, keeping native geometry/colored shadow tests.
        target = "float sliceWeight   = nextWeight - currentWeight;"
        replacement = (target + '\n        #ifdef OVERWORLD\n'
            '        if (isEyeInWater == 0) {\n'
            '            // NIRNCRAFT_SHAFT_EXTINCTION: same clear-air scale as DH fog.\n'
            '            vec4 air = SampleNirnCraftWeather(scenePos.xz);\n'
            '            float sigma = 0.00016 + 0.00032 * air.b;\n'
            '            sliceWeight = max(0.0, exp(-lastDistance * sigma) - exp(-nextDistance * sigma)) * 0.35;\n'
            '        }\n        #endif')
        if target not in text:
            raise RuntimeError("Could not find volumetric slice integration")
        text = text.replace(target, replacement, 1)
        text = re.sub(r"vlColor = pow\(vlColor, vec3\(0\.5 .*?;", "// Preserve the sun/moon spectrum used on receivers.", text)
        text = re.sub(r"vlColor \*= 1\.0 - \(0\.3 \+ 0\.3 \* noonFactor\).*?;", "// No additional sunset brightness boost.", text)
    if "NIRNCRAFT_VOLUME_DEPTH_CLIP" not in text:
        target = "float rayEnd = !sky ? min(lViewPos1, maxDistance) : maxDistance;"
        text = text.replace(target, target + '''
    #if defined OVERWORLD && defined CLOUDS_UNBOUND && defined COMPOSITE1
        // NIRNCRAFT_VOLUME_DEPTH_CLIP: never integrate shafts behind opaque cloud/fog.
        float skyVolumeDepth = texelFetch(colortex5, ivec2(gl_FragCoord.xy), 0).a;
        if (skyVolumeDepth > 0.0 && skyVolumeDepth < 0.999)
            rayEnd = min(rayEnd, pow2(skyVolumeDepth) * NIRNCRAFT_CLOUD_DEPTH_RANGE);
    #endif
''', 1)
    # Source colour already contains phase, brightness and atmospheric extinction.
    text = re.sub(r"vlColor = normalize\(pow\(vlColor,[^;]+;", "// Keep the actual lunar source spectrum.", text)
    text = text.replace("vlColor *= 0.0766 + 0.0766 * vsBrightness;", "// Lunar radiance is already calibrated in the shared source.")
    text = text.replace("vlMult *= vlMultNightModifier;", "// Air extinction and actual source radiance determine night visibility.")
    text = text.replace("float vlTime = min(abs(SdotU) - 0.05, 0.15) / 0.15;", "float vlTime = smoothstep(-0.01, 0.10, nirncraftKeyDirection.y);")
    text = text.replace("float sigma = 0.00016 + 0.00032 * air.b;", "float sigma = 0.00016 + 0.00032 * air.b + NirnCraftMistDensity(cameraPosition + scenePos, SampleNirnCraftDynamics(scenePos.xz), air);")
    return text


def patch_lunar_key_frame(name: str, text: str) -> str:
    if name == "shaders/lib/common.glsl" and "nirncraftCelestialFrame.glsl" not in text:
        text += '\n#include "/lib/atmospherics/nirncraftCelestialFrame.glsl"\n'
    if name.startswith("shaders/program/"):
        text = text.replace("vec3 lightVec = sunVec * ((timeAngle < 0.5325 || timeAngle > 0.9675) ? 1.0 : -1.0);",
                            "vec3 lightVec = NirnCraftLightVector(); // NIRNCRAFT_LUNAR_KEY")
    if name == "shaders/lib/util/spaceConversion.glsl":
        if "NIRNCRAFT_LUNAR_SHADOW_RECEIVER" not in text:
            text = text.replace("vec3 shadowpos = mat3(shadowModelView) * pos + shadowModelView[3].xyz;", '''
    #ifdef OVERWORLD
        // NIRNCRAFT_LUNAR_SHADOW_RECEIVER: same rotation as the shadow caster pass.
        pos = nirncraftShadowRotation * pos;
    #endif
    vec3 shadowpos = mat3(shadowModelView) * pos + shadowModelView[3].xyz;''', 1)
            text = text.replace("return mat3(shadowModelViewInverse) * pos;", '''
    #ifdef OVERWORLD
        return transpose(nirncraftShadowRotation) * mat3(shadowModelViewInverse) * pos;
    #else
        return mat3(shadowModelViewInverse) * pos;
    #endif''', 1)
    if name == "shaders/program/shadow.glsl" and "NIRNCRAFT_LUNAR_SHADOW_CASTER" not in text:
        text = text.replace("gl_Position = shadowProjection * shadowModelView * position;", '''
    // NIRNCRAFT_LUNAR_SHADOW_CASTER: actual geometry casts from the selected lunar direction.
    vec4 nirncraftShadowPosition = position;
    #ifdef OVERWORLD
        nirncraftShadowPosition.xyz = nirncraftShadowRotation * position.xyz;
    #endif
    gl_Position = shadowProjection * shadowModelView * nirncraftShadowPosition;''', 1)
    return text


def patch_gbuffers_skytextured_glsl(text: str) -> str:
    if "NIRNCRAFT_ROUND_SUN" in text:
        return text
    target = "            if (isSun) {"
    repl = (
        "            // NIRNCRAFT_ROUND_SUN: discard vanilla square sun quad; rendered analytically in gbuffers_skybasic.glsl\r\n"
        "            if (isSun) {\r\n"
        "                discard;\r\n"
    )
    if target not in text:
        raise RuntimeError("Could not find `if (isSun) {` in gbuffers_skytextured.glsl")
    return text.replace(target, repl, 1)


def patch_gbuffers_skybasic_glsl(text: str) -> str:
    text = re.sub(r"\s*// NIRNCRAFT_CELESTIAL_COMPOSITE:.*?(?:color\.rgb = color\.rgb \* \(1\.0 - celestial\.a\) \+ celestial\.rgb;|color\.rgb \+= celestial\.rgb;)",
                  "", text, flags=re.S)
    # Remove any previous NIRNCRAFT_ROUND_SUN_DISK block first so we can upgrade cleanly
    text = re.sub(
        r"\s*// NIRNCRAFT_ROUND_SUN_DISK:.*?\n        \}",
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
        "        // NIRNCRAFT_ROUND_SUN_DISK: Analytical round solar disk with limb darkening, granulation & overcast/cloud occlusion\r\n"
        "        if (VdotS > 0.0 && sunVisibility > 0.001) {\r\n"
        "            vec4 skySunW = texture2DLod(nirncraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "            float skyOvercast = clamp(max(rainFactor, smoothstep(0.55, 0.85, skySunW.r) * 0.92 + skySunW.g * 0.55 + skySunW.b * 0.65), 0.0, 1.0);\r\n"
        "            float sunHoriz = GetHorizonFactor(SdotU);\r\n"
        "            float sunRainAtten = 1.0; // Actual per-ray cloud extinction is composited later.\r\n"
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
        "                float diskEdge = 1.0 - smoothstep(0.78, 1.0, r01);\r\n"
        "                vec2 granUv = nViewPos.xy * 18.0 + vec2(syncedTime * 0.002, -syncedTime * 0.0015);\r\n"
        "                float gran = 0.92 + 0.16 * texture2DLod(noisetex, granUv, 0.0).r;\r\n"
        "                vec3 diskCol = mix(sunLimbCol, sunCoreCol, mu) * limbDarkening * gran * 12.0;\r\n"
        "                color.rgb = mix(color.rgb, diskCol, diskEdge * sunVisibility * sunHoriz * sunRainAtten);\r\n"
        "            }\r\n"
        "        }"
    )
    if target not in text:
        raise RuntimeError("Could not find `GetStars` anchor in gbuffers_skybasic.glsl")
    text = text.replace(target, solar_disk_glsl, 1)
    if "NIRNCRAFT_CELESTIAL_COMPOSITE" not in text:
        anchor = "color.rgb += GetStars(starCoord, VdotU, VdotS);"
        text = text.replace(anchor, anchor + '''
        // NIRNCRAFT_CELESTIAL_COMPOSITE: moons mask only extraterrestrial stars/galaxy
        // inside the celestial layer. Atmospheric in-scattering stays in front.
        vec4 celestial = NirnCraftCelestialLayer(normalize(mat3(gbufferModelViewInverse) * nViewPos));
        color.rgb += celestial.rgb;''', 1)
    text = text.replace("if (absVdotS > sunSizeFactor1)", "if (VdotS > sunSizeFactor1)")
    return text


def patch_sky_glsl(text: str) -> str:
    text = text.replace("vec3(0.035, 0.065, 0.13)", "vec3(0.22, 0.42, 0.80)")
    text = text.replace("vec3 darkSky = vec3(0.001, 0.0023, 0.0055)",
                        "vec3 darkSky = vec3(0.028, 0.045, 0.075)")
    text = text.replace("vec3 darkSky = vec3(0.00035, 0.00065, 0.0014)",
                        "vec3 darkSky = vec3(0.028, 0.045, 0.075)")
    if "NIRNCRAFT_TWILIGHT_SKY" not in text:
        anchor = "        return finalSky;"
        extra = '''        // NIRNCRAFT_TWILIGHT_SKY: directional afterglow persists after the ground enters shadow.
        vec3 darkSky = vec3(0.028, 0.045, 0.075) + NirnCraftLunarRadiance(cameraPosition.y) * vec3(0.22, 0.42, 0.80);
        vec3 twilightSky = darkSky + NirnCraftTwilight() * (0.06 + 0.40 * pow(max(VdotS, 0.0), 8.0))
                        * exp(-abs(VdotU) * (3.0 - NirnCraftHaze()));
        finalSky = mix(twilightSky, finalSky, smoothstep(-0.07, 0.05, SdotU));
'''
        text = text.replace(anchor, extra + anchor)
    old_glare = "                glare *= 0.7;"
    new_glare = "                glare *= 0.46; // NIRNCRAFT_GLARE_BALANCE"
    if "NIRNCRAFT_GLARE_BALANCE" in text:
        return text
    if old_glare in text:
        return text.replace(old_glare, new_glare, 1)
    return text


def patch_light_and_ambient_colors_glsl(text: str) -> str:
    if "nirncraftAtmosphere.glsl" not in text:
        text = text.replace("#if defined OVERWORLD", '#if defined OVERWORLD\n        #include "/lib/atmospherics/nirncraftAtmosphere.glsl"', 1)
    text = re.sub(r"vec3 nightClearAmbientColor\s*=[^;]+;",
                  "vec3 nightClearAmbientColor = vec3(0.035, 0.038, 0.050) * (0.8 + vsBrightness * 0.3);", text, count=1)
    text = text.replace("vec3 noonClearLightColor = vec3(0.65, 0.55, 0.375) * 2.05;",
                        "vec3 noonClearLightColor = vec3(0.60, 0.60, 0.56) * 2.05;")
    text = text.replace("texture2DLod(nirncraftSmokeTex, vec2(15.5 / 16.0, 0.75), 0.0).rgb", "vec3(0.0)")
    if "NIRNCRAFT_AURORA_AMBIENT" not in text:
        text = re.sub(r"(        vec3 clearAmbientColor\s*=[^;]+;)",
                      lambda m: m[1][:-1] + " + vec3(0.0); // NIRNCRAFT_AURORA_AMBIENT", text, count=1)
    text = re.sub(r";\s*// NIRNCRAFT_AURORA_AMBIENT:[^\n]*\n\s*clearAmbientColor \+= ([^;]+);", r" + \1; // NIRNCRAFT_AURORA_AMBIENT", text, count=1)
    if "NIRNCRAFT_CELESTIAL_PALETTE" not in text:
        # Stock pack used a blue shaft palette while lighting ground/clouds amber.
        # Masser's warm reflection is mixed with the smaller pale Secunda, sharing
        # the moon phase multiplier already applied by the pack to all passes.
        text = re.sub(r"        #ifndef COMPOSITE1\s*vec3 noonClearLightColor.*?#endif",
                      "        // NIRNCRAFT_CELESTIAL_PALETTE\n        vec3 noonClearLightColor = vec3(0.60, 0.60, 0.56) * 2.05;", text, count=1, flags=re.S)
        text = re.sub(r"        #ifndef COMPOSITE1\s*vec3 sunsetClearLightColor.*?#endif",
                      "        vec3 sunsetClearLightColor = pow(vec3(0.64, 0.45, 0.3), vec3(1.5 + invNoonFactor)) * 5.0;", text, count=1, flags=re.S)
        text = re.sub(r"        #if !defined COMPOSITE1 && !defined DEFERRED1\s*vec3 nightClearLightColor.*?#endif",
                      "        vec3 nightClearLightColor = vec3(0.145, 0.100, 0.092) * (0.4 + vsBrightness * 0.4);", text, count=1, flags=re.S)
        text = re.sub(r"        vec3 nightClearAmbientColor\s*=[^;]+;",
                      "        vec3 nightClearAmbientColor = vec3(0.035, 0.038, 0.050) * (0.8 + vsBrightness * 0.3);", text, count=1)
        text = re.sub(r"        #ifndef COMPOSITE1\s*float noonFactorDM.*?#endif",
                      "        float noonFactorDM = noonFactor;", text, count=1, flags=re.S)
    # Direct light has one solar/lunar colour across the scene. Cloud extinction is
    # evaluated at each receiver; camera weather must not tint an entire DH landscape.
    regional = ("        // NIRNCRAFT_RECEIVER_LIGHTING\n"
                "        vec3 lightColor = NirnCraftKeyRadiance(cameraPosition.y);\n"
                "        vec3 ambientColor = NirnCraftDiffuseRadiance(dayAmbientColor) + vec3(0.0);")
    text = text.replace("vec3 lightColor = clearLightColor;", "vec3 lightColor = NirnCraftKeyRadiance(cameraPosition.y);")
    text = text.replace("vec3 ambientColor = clearAmbientColor;", "vec3 ambientColor = NirnCraftDiffuseRadiance(dayAmbientColor) + vec3(0.0);")
    text = re.sub(r"        // NIRNCRAFT_OVERCAST_LIGHTING:[^\n]*\n.*?vec3 ambientColor =[^;]+;",
                  lambda _: regional, text, count=1, flags=re.S)
    if "NIRNCRAFT_RECEIVER_LIGHTING" in text:
        return text
    text = re.sub(r"        vec3 lightColor\s*= mix\(clearLightColor, rainLightColor, rainFactor\);\s*vec3 ambientColor = mix\(clearAmbientColor, rainAmbientColor, rainFactor\);",
                  lambda _: regional, text, count=1)
    if "NIRNCRAFT_RECEIVER_LIGHTING" in text:
        return text
    return text


def patch_cloud_colors_glsl(text: str) -> str:
    # Keep incident cloud illumination consistent with ground lighting. Local volume
    # self-shadowing supplies the storm darkness instead of camera-wide colour changes.
    if "NIRNCRAFT_CLOUD_COLORS" in text:
        text = re.sub(r"float skyCloudRainF =[^;]+;", "float skyCloudRainF = 0.0;", text, count=1)
        return text
    text = text.replace("cloudRainColor * 0.5, rainFactor", "cloudRainColor * 0.5, 0.0")
    text = text.replace("noonFactor * rainFactor", "noonFactor * 0.0")
    return text


def patch_sky_colors_glsl(text: str) -> str:
    old_v1 = (
        "        // NIRNCRAFT_SKY_OVERCAST: Desaturate sky dome into overcast slate during snow/overcast/storms\r\n"
        "        vec4 skyDomeW = texture2DLod(nirncraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        float skyEffRain = clamp(max(rainStrength, smoothstep(0.58, 0.88, skyDomeW.r) * 0.88 + skyDomeW.g * 0.45 + skyDomeW.b * 0.55), 0.0, 1.0);\r\n"
        "        float invRainStrength2 = (1.0 - skyEffRain) * (1.0 - skyEffRain);"
    )
    target = "        float invRainStrength2 = (1.0 - rainStrength) * (1.0 - rainStrength);"
    repl = (
        "        // NIRNCRAFT_SKY_OVERCAST: Keep upper sky blue over haboobs (Ref 4) & supercells (Ref 1), slate in storms (Ref 2), whiteout in blizzards (Ref 5)\r\n"
        "        vec4 skyDomeW = texture2DLod(nirncraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);\r\n"
        "        int skyDomeAer = clamp(int(floor(skyDomeW.a * 4.0)), 0, 3);\r\n"
        "        float skyEffRain = (skyDomeAer == 2) ? (rainStrength * 0.15) : clamp(max(rainStrength, smoothstep(0.60, 0.90, skyDomeW.r) * 0.88 + skyDomeW.b * 0.50), 0.0, 1.0);\r\n"
        "        float invRainStrength2 = (1.0 - skyEffRain) * (1.0 - skyEffRain);"
    )
    for cand in (old_v1, old_v1.replace("\r\n", "\n"), target):
        if cand in text:
            text = text.replace(cand, repl, 1)
            break
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
    # A physically extinguished sun and two set moons can make the key exactly
    # zero. Stock highlight normalization then poisons even independent lamps.
    text = text.replace("vec3 highlightColor = normalize(pow(lightColor, vec3(0.37)))",
                        "// NIRNCRAFT_FINITE_DARK_HIGHLIGHT\n"
                        "vec3 nirncraftHighlightSpectrum = pow(max(lightColor, vec3(0.0)), vec3(0.37));\n"
                        "vec3 highlightColor = nirncraftHighlightSpectrum / max(length(nirncraftHighlightSpectrum), 0.00001)")
    if "NIRNCRAFT_RECEIVER_AMBIENT" in text:
        return patch_receiver_flash(text)
    text = re.sub(r"        // NIRNCRAFT_REGIONAL_LIGHT_ATTEN:[^\n]*\n.*?ambientMult =[^;]+;",
                  "        ambientMult = mix(lightmapYM, pow2(lightmapYM) * lightmapYM, rainFactor);", text, count=1, flags=re.S)
    target = "        ambientMult = mix(lightmapYM, pow2(lightmapYM) * lightmapYM, rainFactor);"
    repl = (
        "        // NIRNCRAFT_RECEIVER_AMBIENT: Local and continent weather at the receiving surface\r\n"
        "        vec2 skyWorldLightXZ = playerPos.xz + cameraPosition.xz;\r\n"
        "        vec4 skyLocalLightW = mix(texture2DLod(nirncraftWeatherTex, fract(skyWorldLightXZ / 8192.0), 0.0), texture2DLod(nirncraftWeatherFarTex, fract(skyWorldLightXZ / 65536.0), 0.0), smoothstep(2200.0, 3600.0, length(playerPos.xz)));\r\n"
        "        float localStormShade = clamp(smoothstep(0.62, 0.92, skyLocalLightW.r) * 0.45 + skyLocalLightW.g * 0.35 + skyLocalLightW.b * 0.45, 0.0, 0.85);\r\n"
        "        float effRainLight = localStormShade;\r\n"
        "        ambientMult = mix(lightmapYM, pow2(lightmapYM) * lightmapYM, effRainLight) * (1.0 - 0.24 * localStormShade * lightmapYM);"
    )
    if target in text:
        return patch_receiver_flash(text.replace(target, repl, 1))
    return text


def patch_receiver_flash(text: str) -> str:
    if "NIRNCRAFT_SKY_OCCLUSION" not in text:
        text = text.replace("float effRainLight = localStormShade;", """float effRainLight = localStormShade;
        // NIRNCRAFT_SKY_OCCLUSION: a sealed night deck blocks diffuse lunar/starlight too.
        ambientColorM *= mix(1.0, mix(0.70, 0.14, 1.0 - sunVisibility2), localStormShade);""", 1)
    # Electrical emission is independent of lunar phase. Remove the older ambient injection.
    text = text.replace("        ambientColorM += vec3(0.68, 0.80, 1.12) * skySurfaceFlash * 2.5 * lightmapYM;", "")
    target = "ambientMult = mix(lightmapYM, pow2(lightmapYM) * lightmapYM, effRainLight) * (1.0 - 0.24 * localStormShade * lightmapYM);"
    flash = '''
        // NIRNCRAFT_FLASH_IRRADIANCE: same flash illuminating the actual cloud bank.
        vec2 flashUV = fract(skyWorldLightXZ / 8192.0);
        vec2 flashFarUV = fract(skyWorldLightXZ / 65536.0);
        float flashCode = length(playerPos.xz) < 2900.0
            ? texture2DLod(nirncraftWeatherTex, (floor(flashUV * 128.0) + 0.5) / 128.0, 0.0).a
            : texture2DLod(nirncraftWeatherFarTex, (floor(flashFarUV * 128.0) + 0.5) / 128.0, 0.0).a;
        float skySurfaceFlash = NirnCraftFlashAt(playerPos.xz);
'''
    if "NIRNCRAFT_FLASH_IRRADIANCE" not in text:
        text = text.replace(target, target + flash, 1)
    if "NIRNCRAFT_PHASE_INDEPENDENT_FLASH" not in text:
        text = re.sub(r"(sceneLighting \*= moonPhaseInfluence;\s*#endif)", lambda m: m[1] + '''
    #ifdef OVERWORLD
        // NIRNCRAFT_PHASE_INDEPENDENT_FLASH: same flash footprint on native and DH receivers.
        sceneLighting += vec3(0.68, 0.80, 1.12) * skySurfaceFlash * 3.0
                       * lightmapYM * (0.30 + 0.70 * NdotUmax0);
    #endif''', text, count=1)
    if "NIRNCRAFT_AURORA_RECEIVER" not in text:
        text = text.replace("* lightmapYM * (0.30 + 0.70 * NdotUmax0);", """* lightmapYM * (0.30 + 0.70 * NdotUmax0);
        // NIRNCRAFT_AURORA_RECEIVER: emission from the actual transient curtains.
        sceneLighting += NirnCraftAuroraIrradiance(vec3(0.0, 1.0, 0.0))
                       * lightmapYM * (0.25 + 0.75 * NdotUmax0) * (1.0 - localStormShade);""", 1)
    if "NIRNCRAFT_CHANNEL_RECEIVER" not in text:
        text = text.replace("sceneLighting += vec3(0.68, 0.80, 1.12) * skySurfaceFlash * 3.0\n                       * lightmapYM * (0.30 + 0.70 * NdotUmax0);",
            "// NIRNCRAFT_CHANNEL_RECEIVER: geometry/contact lighting independent of sun/moon phase.\n"
            "        sceneLighting += NirnCraftLightningSurface(playerPos, normalize(mat3(gbufferModelViewInverse) * normalM), lightmapYM);\n"
            "        // NIRNCRAFT_LOCAL_AIR_LIGHT: the same source illuminates fog and nearby geometry.\n"
            "        sceneLighting += NirnCraftLocalLight(playerPos, normalize(mat3(gbufferModelViewInverse) * normalM), true);")
    if "NIRNCRAFT_LOCAL_AIR_LIGHT" not in text:
        text = text.replace("sceneLighting += NirnCraftLightningSurface(playerPos, normalize(mat3(gbufferModelViewInverse) * normalM), lightmapYM);",
            "sceneLighting += NirnCraftLightningSurface(playerPos, normalize(mat3(gbufferModelViewInverse) * normalM), lightmapYM);\n"
            "        // NIRNCRAFT_LOCAL_AIR_LIGHT: the same source illuminates fog and nearby geometry.\n"
            "        sceneLighting += NirnCraftLocalLight(playerPos, normalize(mat3(gbufferModelViewInverse) * normalM), true);")
    return text


def patch_sheltered_fog(text: str) -> str:
    if "NIRNCRAFT_SHELTERED_STOCK_FOG" in text:
        return text
    start = text.find("void DoAtmosphericFog(")
    end = text.find("void DoWaterFog(", start)
    if start >= 0 and end > start:
        body = text[start:end]
        target = "color = mix(color, vec4(fogColorM, 0.0), fog);"
        body = body.replace(target, "#ifdef OVERWORLD\n"
                            "                // NIRNCRAFT_SHELTERED_STOCK_FOG: outdoor airlight stays outside sealed rooms.\n"
                            "                fog *= NirnCraftOutdoorLightExposure(playerPos);\n"
                            "            #endif\n            " + target, 1)
        text = text[:start] + body + text[end:]
    target = "DoBorderFog(color, skyFade, max(length(playerPos.xz), abs(playerPos.y)), VdotU, VdotS, dither);"
    text = text.replace(target, "#ifdef OVERWORLD\n"
                        "            if (NirnCraftOutdoorLightExposure(playerPos) > 0.0)\n"
                        "        #endif\n        " + target, 1)
    return text


def patch_main_fog_glsl(text: str) -> str:
    if "NIRNCRAFT_SHARED_AIR" in text:
        return patch_sheltered_fog(text)
    text = '#ifdef OVERWORLD\n#include "/lib/atmospherics/clouds/nirncraftDensity.glsl"\n#endif\n' + text
    text = re.sub(r"vec3 GetAtmFogColor\(float altitudeFactorRaw, float VdotS\) \{.*?\n        \}",
                  "vec3 GetAtmFogColor(float altitudeFactorRaw, float VdotS) {\n"
                  "            // NIRNCRAFT_SHARED_AIR: the same airlight as the cloud volume.\n"
                  "            return mix(nightMiddleSkyColor * 0.75, dayMiddleSkyColor * 0.85, sunVisibility2);\n        }",
                  text, count=1, flags=re.S)
    old = "float fog = 1.0 - exp2(-max0(lViewPos - 40.0) * (0.4 + 0.4 * rainFactor) / ATM_FOG_DISTANCE);"
    new = ("vec4 skyAirW = SampleNirnCraftWeather(playerPos.xz * 0.5);\n"
           "            float skyAirDensity = 0.00016 + 0.00032 * skyAirW.b;\n"
           "            float fog = 1.0 - exp(-max0(lViewPos - 40.0) * skyAirDensity);")
    # This branch is shared with Voxy; the weather coupling is Overworld only.
    new = "#ifdef OVERWORLD\n            " + new + "\n            #else\n            " + old + "\n            #endif"
    text = text.replace(old, new, 1)
    old_near = "float fog = 1.0 - exp(-pow(lViewPos * (0.001 - 0.0007 * rainFactor), 2.0 - rainFactor2) * lViewPos * renDisFactor);"
    near = ("#ifdef OVERWORLD\n"
            "            vec4 skyAirW = SampleNirnCraftWeather(playerPos.xz * 0.5);\n"
            "            float fog = 1.0 - exp(-max0(lViewPos - 40.0) * (0.00016 + 0.00032 * skyAirW.b));\n"
            "            #else\n            " + old_near + "\n            #endif")
    text = text.replace(old_near, near, 1)
    return patch_sheltered_fog(text)


def patch_aurora_glsl(text: str) -> str:
    return '#include "/lib/atmospherics/nirncraftCelestial.glsl"\n' + (ROOT / "mod/src/main/resources/assets/nirncraft/shaders/include/aurora.glsl").read_text(encoding="utf-8")


def patch_stars_glsl(text: str) -> str:
    return '''#include "/lib/atmospherics/nirncraftCelestial.glsl"
vec3 nirncraftReflectedStarRay;
vec2 GetStarCoord(vec3 viewPos, float sphereness) {
    nirncraftReflectedStarRay = normalize(mat3(gbufferModelViewInverse) * viewPos);
    return nirncraftReflectedStarRay.xz;
}
vec3 GetStars(vec2 starCoord, float VdotU, float VdotS) {
    #ifdef GBUFFERS_SKYBASIC
        return vec3(0.0); // Composited once, with opaque moon limbs, in the direct sky.
    #else
        return NirnCraftCelestialLayer(nirncraftReflectedStarRay).rgb;
    #endif
}
'''


def patch_waving_blocks_glsl(text: str) -> str:
    if "NIRNCRAFT_ROOTED_WIND" in text:
        if '#include "/lib/atmospherics/nirncraftWind.glsl"' not in text:
            text = '#include "/lib/atmospherics/nirncraftWind.glsl"\n' + text
        text = text.replace("texture2DLod(nirncraftDynamicsTex, fract(pos.xz / 8192.0), 0.0)",
                            "NirnCraftSampleDynamics(nirncraftDynamicsTex,pos.xz / 8192.0)")
        return text
    text = text.replace("\r\n", "\n").replace("\r", "")
    helper = '''#include "/lib/atmospherics/nirncraftWind.glsl"
// NIRNCRAFT_ROOTED_WIND: the same world clock/direction as the atmosphere.
void NirnCraftSurfaceGust(vec3 pos, out vec2 direction, out float strength, out float gust, out float time) {
    vec4 dynamics = NirnCraftSampleDynamics(nirncraftDynamicsTex,pos.xz / 8192.0);
    vec4 header = texelFetch(nirncraftSmokeTex, ivec2(11, 1), 0);
    vec4 bytes = floor(texelFetch(nirncraftSmokeTex, ivec2(13, 1), 0) * 255.0 + 0.5);
    time = (dot(bytes, vec4(1.0, 256.0, 65536.0, 16777216.0)) + header.r) * 0.05 * WAVING_SPEED;
    float angle = dynamics.b * 6.2831853;
    direction = vec2(cos(angle), sin(angle));
    strength = clamp(header.b, 0.0, 1.0);
    float along = dot(pos.xz, direction), across = dot(pos.xz, vec2(-direction.y, direction.x));
    float broad = sin(along * 0.018 - time * (0.24 + strength * 0.65) + sin(across * 0.025) * 1.2);
    float bands = sin(along * 0.065 - time * (0.65 + strength * 1.4) + sin(across * 0.045) * 0.6);
    gust = 0.50 + 0.30 * broad + 0.20 * bands;
}

'''
    text = helper + text
    foliage = '''void DoWave_Foliage(inout vec3 playerPos, vec3 worldPos, float waveMult) {
    vec2 direction;
    float strength, gust, time;
    NirnCraftSurfaceGust(worldPos, direction, strength, gust, time);
    float tip = gl_MultiTexCoord0.t < mc_midTexCoord.t ? 1.0 : 0.0;
    float stemHeight = tip + (waveMult > 1.5 ? 1.0 : 0.0);
    float bend = stemHeight * stemHeight;
    float flutter = sin(worldPos.x * 3.7 + worldPos.z * 4.3 + time * (4.0 + strength * 9.0));
    vec2 offset = direction * ((0.025 + 0.20 * strength) * gust * bend)
            + vec2(-direction.y, direction.x) * (flutter * 0.012 * strength * bend);
    #ifdef NO_WAVING_INDOORS
        offset *= clamp((lmCoord.y - 0.5) * 2.0, 0.0, 1.0);
    #endif
    offset *= WAVING_I * min(waveMult, 1.0);
    playerPos.xz += offset;
    playerPos.y -= dot(offset, offset) * 0.35;
}'''
    leaves = '''void DoWave_Leaves(inout vec3 playerPos, vec3 worldPos, float waveMult) {
    vec2 direction;
    float strength, gust, time;
    NirnCraftSurfaceGust(worldPos, direction, strength, gust, time);
    vec3 centre = floor(worldPos) + 0.5;
    float branch = sin(dot(centre.xz, direction) * 0.035 + time * (0.8 + strength));
    float rustle = sin(dot(worldPos, vec3(4.1, 3.7, 2.9)) + time * (5.0 + strength * 11.0));
    vec2 offset = direction * branch * gust * (0.025 + 0.09 * strength)
            + vec2(-direction.y, direction.x) * rustle * (0.003 + 0.012 * strength);
    float shelter = clamp((lmCoord.y - 0.4) / 0.5, 0.15, 1.0);
    playerPos.xz += offset * WAVING_I * waveMult * shelter * (1.0 - 0.3 * inSnowy);
    playerPos.y += branch * 0.008 * strength * WAVING_I * waveMult;
}'''
    text, count = re.subn(r"void DoWave_Foliage\(.*?\n\}", lambda _: foliage, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError("Could not find foliage waving body")
    text, count = re.subn(r"void DoWave_Leaves\(.*?\n\}", lambda _: leaves, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError("Could not find leaf waving body")
    text = re.sub(r"(else if \(mat == 10021\) \{ // Upper Layer Foliage\s+DoWave_Foliage\(playerPos.xyz, worldPos, )1\.0(\);)",
                  r"\g<1>1.65\2", text, count=1)
    old_wave = "    float wavingIntensity = WAVING_I * mix(1.0, WAVING_I_RAIN_MULT_M, rainFactor);"
    new_wave = (
        "    // NIRNCRAFT_WIND_WAVING: Drive plant and leaf sway from live NirnCraft weather & wind\r\n"
        "    vec4 skyWindW = texture2DLod(nirncraftWeatherTex, fract(pos.xz / 8192.0), 0.0);\r\n"
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
    if "nirncraft:nirnroot" in text:
        return text
    foliage_anchor = " cactus_flower closed_eyeblossom"
    if foliage_anchor in text:
        text = text.replace(foliage_anchor, foliage_anchor + " " + NIRNCRAFT_WAVING_FOLIAGE, 1)

    leaves_f_anchor = "pale_oak_leaves:waterlogged=false"
    if leaves_f_anchor in text:
        text = text.replace(leaves_f_anchor, leaves_f_anchor + " " + NIRNCRAFT_WAVING_LEAVES_FALSE, 1)

    leaves_t_anchor = "pale_oak_leaves:waterlogged=true"
    if leaves_t_anchor in text:
        text = text.replace(leaves_t_anchor, leaves_t_anchor + " " + NIRNCRAFT_WAVING_LEAVES_TRUE, 1)

    hanging_anchor = "cobweb wildflowers"
    if hanging_anchor in text:
        text = text.replace(hanging_anchor, hanging_anchor + " nirncraft:hanging_moss", 1)

    upper_anchor = "large_fern:half=upper"
    if upper_anchor in text:
        text = text.replace(upper_anchor, upper_anchor + " nirncraft:swamp_reeds:half=upper", 1)

    return text


def patch_shaderpack_zip(zip_path: pathlib.Path) -> None:
    if not zip_path.exists():
        return
    validate_shader_input(zip_path)
    upstream=zip_path if hashlib.sha256(zip_path.read_bytes()).hexdigest()==SUPPORTED_UPSTREAM_SHA256 else next(
        (p for p in (ROOT/"dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip")
         if hashlib.sha256(p.read_bytes()).hexdigest()==SUPPORTED_UPSTREAM_SHA256),None)
    if upstream is None:
        raise ValueError("Pinned original required to rebuild isolated realm shaders; run the instance builder")
    realm_spec=importlib.util.spec_from_file_location("nirncraft_realm_pipeline",pathlib.Path(__file__).with_name("realm_pipeline.py"))
    realm_pipeline=importlib.util.module_from_spec(realm_spec)
    realm_spec.loader.exec_module(realm_pipeline)
    realm_files=realm_pipeline.build(upstream)
    with tempfile.NamedTemporaryFile(delete=False, suffix=".zip", dir=str(zip_path.parent)) as tmp:
        tmp_path = pathlib.Path(tmp.name)

    try:
        with zipfile.ZipFile(zip_path, "r") as zin, zipfile.ZipFile(
            tmp_path, "w", compression=zipfile.ZIP_DEFLATED
        ) as zout:
            for item in zin.infolist():
                if item.filename == PATCH_MANIFEST: continue
                if item.filename in realm_files or item.filename.startswith("shaders/nirncraftRealm/"): continue
                if item.filename in ("shaders/lib/atmospherics/nirncraftLocalOcclusion.glsl", "shaders/lib/atmospherics/nirncraftVolumeNoise.glsl",
                                     "shaders/lib/atmospherics/nirncraftFogLight.glsl", "shaders/lib/atmospherics/nirncraftLocalMedia.glsl"): continue
                if item.filename == "shaders/lib/atmospherics/clouds/nirncraftLightCache.glsl": continue
                if item.filename in ("shaders/world0/prepare.fsh","shaders/world0/prepare.vsh"):
                    if "NIRNCRAFT_LIGHT_CACHE_WRITE" in zin.read("shaders/world0/prepare.fsh").decode(errors="replace"): continue
                if item.filename in ("shaders/lib/atmospherics/clouds/nirncraftDensity.glsl", "shaders/lib/atmospherics/nirncraftCelestial.glsl", "shaders/lib/atmospherics/nirncraftCelestialFrame.glsl", "shaders/lib/atmospherics/nirncraftAtmosphere.glsl", "shaders/lib/atmospherics/nirncraftLightning.glsl", "shaders/lib/atmospherics/nirncraftInterior.glsl", "shaders/lib/atmospherics/nirncraftInteriorDensity.glsl", "shaders/lib/atmospherics/nirncraftWind.glsl") or item.filename.startswith("shaders/lib/textures/nirncraft_"):
                    continue  # replaced below when upgrading an already patched pack
                raw = zin.read(item.filename)
                if item.filename == "shaders/lib/uniforms.glsl":
                    raw = patch_uniforms_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/shaders.properties":
                    raw = realm_pipeline.program_properties(patch_shaders_properties(raw.decode("utf-8"))).encode("utf-8")
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
                elif item.filename == "shaders/lib/atmospherics/fog/mainFog.glsl":
                    raw = patch_main_fog_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/atmospherics/auroraBorealis.glsl":
                    raw = patch_aurora_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/atmospherics/stars.glsl":
                    raw = patch_stars_glsl(raw.decode("utf-8")).encode("utf-8")
                elif item.filename == "shaders/lib/colors/moonPhaseInfluence.glsl":
                    raw = b'''#ifndef INCLUDE_MOON_PHASE_INF
#define INCLUDE_MOON_PHASE_INF
#ifdef OVERWORLD
vec4 skyMoonPhase = texelFetch(nirncraftSmokeTex, ivec2(3, 1), 0);
float moonPhaseInfluence = mix(1.0, 0.045 + 0.955 * (0.80 * skyMoonPhase.b + 0.20 * skyMoonPhase.a), 1.0 - sunVisibility2);
#else
float moonPhaseInfluence = 1.0;
#endif
#endif
'''
                if item.filename.endswith(".glsl"):
                    # Consumers of the shared depth attachment must use the same
                    # continent range; the stock encoding saturates at near chunks.
                    shader = patch_lunar_key_frame(item.filename, raw.decode("utf-8"))
                    if item.filename in ("shaders/program/gbuffers_skybasic.glsl", "shaders/lib/atmospherics/clouds/mainClouds.glsl"):
                        shader = shader.replace("color.rgb *= moonPhaseInfluence;", "// Lunar phase already enters the source radiance.")
                        shader = shader.replace("clouds.rgb *= moonPhaseInfluence;", "// Lunar phase already enters the source radiance.")
                    if item.filename == "shaders/lib/lighting/mainLighting.glsl":
                        shader = re.sub(r"(?m)^(\s*)sceneLighting \*= moonPhaseInfluence;", r"\1// sceneLighting *= moonPhaseInfluence; // Already included in the source radiance.", shader)
                    shader = re.sub(r"(pow2\(cloudLinearDepth(?: \+ OSIEBCA \* dither)?\) \* )renderDistance( < min\(lViewPos1?, )renderDistance(\))",
                                    r"\1NIRNCRAFT_CLOUD_DEPTH_RANGE\2NIRNCRAFT_CLOUD_DEPTH_RANGE\3", shader)
                    shader = shader.replace("lViewPos1 *= cloudDisMult;", "lViewPos1 = min(lViewPos1, cloudDisMult * NIRNCRAFT_CLOUD_DEPTH_RANGE);")
                    raw = shader.encode("utf-8")
                zout.writestr(item, raw)
            zout.writestr("shaders/lib/atmospherics/clouds/nirncraftDensity.glsl", CLOUD_DENSITY_GLSL)
            zout.writestr("shaders/lib/atmospherics/nirncraftCelestial.glsl", (ROOT / "mod/src/main/resources/assets/nirncraft/shaders/include/celestial.glsl").read_bytes())
            zout.writestr("shaders/lib/atmospherics/nirncraftLightning.glsl", (ROOT / "mod/src/main/resources/assets/nirncraft/shaders/include/lightning.glsl").read_bytes())
            zout.writestr("shaders/lib/atmospherics/nirncraftAtmosphere.glsl", (ROOT / "mod/src/main/resources/assets/nirncraft/shaders/include/atmosphere.glsl").read_bytes())
            zout.writestr("shaders/lib/atmospherics/nirncraftCelestialFrame.glsl", (ROOT / "mod/src/main/resources/assets/nirncraft/shaders/include/celestial_frame.glsl").read_bytes())
            for shader_name, asset in (("nirncraftInterior", "interior"), ("nirncraftInteriorDensity", "interior_density"), ("nirncraftWind", "wind"), ("nirncraftLocalOcclusion", "local_occlusion"),
                                       ("nirncraftVolumeNoise","volume_noise"),("nirncraftFogLight","fog_light"),("nirncraftLocalMedia","local_media")):
                zout.writestr("shaders/lib/atmospherics/" + shader_name + ".glsl",
                              (ROOT / "mod/src/main/resources/assets/nirncraft/shaders/include" / (asset + ".glsl")).read_bytes())
            for resource in CELESTIAL_ASSETS.values():
                zout.writestr("shaders/lib/textures/nirncraft_" + resource + ".png",
                              (ROOT / "mod/src/main/resources/assets/nirncraft/textures/environment" / (resource + ".png")).read_bytes())
            for name,raw in realm_files.items(): zout.writestr(name,raw)
        with zipfile.ZipFile(tmp_path) as checked:
            hashes = validate_shader_output(checked)
        with zipfile.ZipFile(tmp_path, "a", compression=zipfile.ZIP_DEFLATED) as checked:
            checked.writestr(PATCH_MANIFEST, json.dumps({"schema": 1, "upstream_sha256": SUPPORTED_UPSTREAM_SHA256,
                "checked_outputs": hashes}, sort_keys=True, indent=2) + "\n")
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
