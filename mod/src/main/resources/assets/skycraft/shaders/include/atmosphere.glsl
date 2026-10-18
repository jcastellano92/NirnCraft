#ifndef SKYCRAFT_ATMOSPHERIC_SPECTRUM
#define SKYCRAFT_ATMOSPHERIC_SPECTRUM
#include "/lib/atmospherics/skycraftLightning.glsl"
#include "/lib/atmospherics/skycraftInterior.glsl"
float SkycraftDecodeFlash(float alpha) {
    return clamp((fract(alpha * 4.0) - 0.08) / 0.84, 0.0, 1.0);
}

float SkycraftFilteredFlash(sampler2D field, vec2 uv) {
    vec2 cell = uv * 128.0 - 0.5;
    vec2 base = floor(cell), f = fract(cell);
    // Decode each category-packed texel before interpolation. Interpolating packed
    // alpha itself manufactures lightning at a snow/rain/dust boundary.
    float a = SkycraftDecodeFlash(texture2DLod(field, (base + vec2(0.5, 0.5)) / 128.0, 0.0).a);
    float b = SkycraftDecodeFlash(texture2DLod(field, (base + vec2(1.5, 0.5)) / 128.0, 0.0).a);
    float c = SkycraftDecodeFlash(texture2DLod(field, (base + vec2(0.5, 1.5)) / 128.0, 0.0).a);
    float d = SkycraftDecodeFlash(texture2DLod(field, (base + vec2(1.5, 1.5)) / 128.0, 0.0).a);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float SkycraftFlashAt(vec2 playerXZ) {
    vec2 world = playerXZ + cameraPosition.xz;
    if (length(playerXZ) < 2900.0) {
        vec2 uv = fract(world / 8192.0);
        float nearest = SkycraftDecodeFlash(texture2DLod(skycraftWeatherTex, (floor(uv * 128.0) + 0.5) / 128.0, 0.0).a);
        return nearest > 0.005 ? SkycraftFilteredFlash(skycraftWeatherTex, uv) : 0.0;
    }
    vec2 uv = fract(world / 65536.0);
    float nearest = SkycraftDecodeFlash(texture2DLod(skycraftWeatherFarTex, (floor(uv * 128.0) + 0.5) / 128.0, 0.0).a);
    return nearest > 0.005 ? SkycraftFilteredFlash(skycraftWeatherFarTex, uv) : 0.0;
}
// Offline, curved-atmosphere RGB Rayleigh/Mie optical integration. Fourth-power
// encoding retains dark horizon transmission in a compact filtered texture.
vec3 SkycraftSourceTransmission(float worldHeight, float cosineElevation) {
    vec2 uv = vec2((clamp(cosineElevation, -0.32, 1.0) + 0.32) / 1.32,
                   clamp((worldHeight - 63.0) * 8.0 / 24000.0, 0.0, 1.0));
    uv = (uv * vec2(255.0, 63.0) + 0.5) / vec2(256.0, 64.0);
    vec3 transmission = texture2DLod(skycraftAtmosphereTex, uv, 0.0).rgb;
    return transmission * transmission * transmission * transmission;
}

float SkycraftHaze() {
    vec4 local = texture2DLod(skycraftWeatherTex, fract(cameraPosition.xz / 8192.0), 0.0);
    vec4 moist = texture2DLod(skycraftDynamicsTex, fract(cameraPosition.xz / 8192.0), 0.0);
    return clamp(moist.a * 0.6 + local.b * 0.4 + local.g * 0.15, 0.0, 1.0);
}

// A view ray is clipped by Minecraft/DH geometry, not the LUT's reference planet.
// A smooth positive grazing angle retains atmospheric reddening without stamping
// the reference sphere's binary ground mask across an oversized fictional moon.
vec3 SkycraftViewTransmission(float worldHeight, float cosineElevation) {
    float grazing = 0.5 * (cosineElevation + sqrt(cosineElevation * cosineElevation + 0.0025));
    return SkycraftSourceTransmission(worldHeight, grazing);
}

vec3 SkycraftAuroraIrradiance(vec3 worldNormal) {
    vec4 ambient = texelFetch(skycraftSmokeTex, ivec2(28, 1), 0);
    if (ambient.a < 0.002) return vec3(0.0);
    vec3 c0 = ambient.rgb;
    vec3 cx = texelFetch(skycraftSmokeTex, ivec2(29, 1), 0).rgb * 2.0 - 1.0;
    vec3 cy = texelFetch(skycraftSmokeTex, ivec2(30, 1), 0).rgb * 2.0 - 1.0;
    vec3 cz = texelFetch(skycraftSmokeTex, ivec2(31, 1), 0).rgb * 2.0 - 1.0;
    vec3 irradiance = max(vec3(0.0), c0 + cx * worldNormal.x + cy * worldNormal.y + cz * worldNormal.z) * 0.02;
    // Native sceneLighting and cloud colours are perceptual until composite1.
    return pow(irradiance, vec3(1.0 / 2.2));
}

vec3 SkycraftLunarRadiance(float worldHeight) {
    float masserY = skycraftMasserDirection.y, secundaY = skycraftSecundaDirection.y;
    // Masser reflects warm light; Secunda supplies a smaller, cooler contribution.
    vec3 source = SkycraftSourceTransmission(worldHeight, masserY) * vec3(1.0, 0.72, 0.78) * skycraftMoonWeights.x * 0.80
                + SkycraftSourceTransmission(worldHeight, secundaY) * vec3(0.78, 0.88, 1.0) * skycraftMoonWeights.y * 0.20;
    return source * 0.14;
}

vec3 SkycraftKeyRadiance(float worldHeight) {
    vec3 solar = SkycraftSourceTransmission(worldHeight, SdotU) / vec3(0.95, 0.89, 0.77);
    solar = pow(max(solar, vec3(0.0)), vec3(1.0 + SkycraftHaze() * 0.30));
    return solar * 1.23 + SkycraftLunarRadiance(worldHeight) * (1.0 - sunVisibility2);
}

vec3 SkycraftTwilight() {
    // A compact diffuse twilight approximation supplements direct-source transmission;
    // this is not the full multiple-scattering solver from Bruneton or Hillaire.
    float twilight = smoothstep(-0.309, -0.005, SdotU) * (1.0 - smoothstep(-0.015, 0.13, SdotU));
    vec3 tint = mix(vec3(0.055, 0.10, 0.27), vec3(0.70, 0.22, 0.065), smoothstep(-0.12, 0.005, SdotU));
    return tint * twilight;
}

vec3 SkycraftDiffuseRadiance(vec3 daytime) {
    float day = smoothstep(-0.10, 0.10, SdotU);
    vec3 night = vec3(0.008, 0.012, 0.022) + SkycraftLunarRadiance(cameraPosition.y) * vec3(0.55, 0.65, 0.90);
    return mix(night, daytime, day) + SkycraftTwilight() * 0.14;
}
#endif
