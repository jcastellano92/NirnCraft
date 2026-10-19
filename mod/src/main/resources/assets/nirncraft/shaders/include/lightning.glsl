#ifndef NIRNCRAFT_LIGHTNING_SOURCES
#define NIRNCRAFT_LIGHTNING_SOURCES
// Four live sources, decoded once per fragment and reused by every volume step.
// Header: pulse, leader progress, CG, strength. Coordinates are camera-relative.
bool nirncraftLightningLoaded = false;
int nirncraftLightningCount = 0;
vec4 nirncraftDischarge[4];
vec3 nirncraftChannel[24];

// NIRNCRAFT_STORM_SHELTER: outdoor electrical radiance cannot cross a sealed ceiling.
// The loaded 2m roof field resolves real vertical openings near the viewer. Outside
// that cache, the coarse terrain field conservatively rejects deep underground air.
float NirnCraftOutdoorLightExposure(vec3 playerPos) {
    vec3 world = cameraPosition + playerPos;
    if (max(abs(playerPos.x), abs(playerPos.z)) <= 58.0) {
        ivec2 cell = ivec2(mod(floor(world.xz / 2.0), 64.0));
        vec4 data = floor(texelFetch(nirncraftFogRadianceTex, cell, 0) * 255.0 + 0.5);
        if (data.a >= 128.0) {
            float roof = data.g + data.b * 256.0 - 64.0;
            // Keep the top face itself lit, while excluding air below the ceiling.
            return 1.0 - smoothstep(0.75, 2.0, roof - world.y);
        }
    }
    vec4 terrain = texture2DLod(nirncraftDynamicsTex, fract(world.xz / 8192.0), 0.0);
    if (length(playerPos.xz) > 2900.0)
        terrain = texture2DLod(nirncraftDynamicsFarTex, fract(world.xz / 65536.0), 0.0);
    return 1.0 - smoothstep(4.0, 12.0, terrain.g * 1024.0 - world.y);
}

vec3 NirnCraftLightningPoint(int row, int point) {
    vec4 a = floor(texelFetch(nirncraftLightningTex, ivec2(point * 2 + 1, row), 0) * 255.0 + 0.5);
    vec2 b = floor(texelFetch(nirncraftLightningTex, ivec2(point * 2 + 2, row), 0).rg * 255.0 + 0.5);
    return vec3(a.r + a.g * 256.0, a.b + a.a * 256.0, b.r + b.g * 256.0) - 32768.0;
}
void NirnCraftLoadLightning() {
    if (nirncraftLightningLoaded) return;
    nirncraftLightningLoaded = true;
    nirncraftLightningCount = int(texelFetch(nirncraftLightningTex, ivec2(31, 0), 0).r * 255.0 + 0.5);
    nirncraftLightningCount = min(nirncraftLightningCount, 4);
    for (int s = 0; s < nirncraftLightningCount; s++) {
        nirncraftDischarge[s] = texelFetch(nirncraftLightningTex, ivec2(0, s), 0);
        for (int p = 0; p < 6; p++) nirncraftChannel[s * 6 + p] = NirnCraftLightningPoint(s, p);
    }
}
vec3 NirnCraftNearestChannel(vec3 position, int strike) {
    int offset = strike * 6;
    float progress = nirncraftDischarge[strike].g * 3.0;
    vec3 nearest = nirncraftChannel[offset];
    float best = dot(nearest - position, nearest - position);
    for (int p = 0; p < 3; p++) {
        float reached = clamp(progress - float(p), 0.0, 1.0);
        if (reached <= 0.0) break;
        vec3 a = nirncraftChannel[offset + p];
        vec3 ab = nirncraftChannel[offset + p + 1] - a;
        vec3 samplePos = a + ab * clamp(dot(position - a, ab) / max(dot(ab, ab), 1.0), 0.0, reached);
        float distance2 = dot(samplePos - position, samplePos - position);
        if (distance2 < best) { best = distance2; nearest = samplePos; }
    }
    if (progress > 1.5) {
        vec3 a = nirncraftChannel[offset + 4], ab = nirncraftChannel[offset + 5] - a;
        vec3 samplePos = a + ab * clamp(dot(position - a, ab) / max(dot(ab, ab), 1.0), 0.0, clamp((progress - 1.5) / 1.5, 0.0, 1.0));
        if (dot(samplePos - position, samplePos - position) < best) nearest = samplePos;
    }
    return nearest;
}

// Source radiance, not a camera flash: density/integration supplies the visible shape.
vec3 NirnCraftLightningVolume(vec3 playerPos) {
    float exposure = NirnCraftOutdoorLightExposure(playerPos);
    if (exposure <= 0.0) return vec3(0.0);
    NirnCraftLoadLightning();
    float energy = 0.0;
    for (int s = 0; s < nirncraftLightningCount; s++) {
        vec3 delta = NirnCraftNearestChannel(playerPos, s) - playerPos;
        float distance2 = dot(delta, delta);
        float distanceM = sqrt(distance2);
        float pulse = nirncraftDischarge[s].r * mix(0.65, 1.25, nirncraftDischarge[s].a);
        // Hot narrow core, broad scattered shoulder; miles of weaker underbelly light.
        energy += pulse * (22000.0 / (625.0 + distance2) + 0.65 / (1.0 + distance2 / 360000.0))
                    * exp(-distanceM * 0.00065);
    }
    return vec3(0.72, 0.83, 1.12) * min(energy, 30.0) * exposure;
}

vec3 NirnCraftLightningSurface(vec3 playerPos, vec3 worldNormal, float skyExposure) {
    float exposure = NirnCraftOutdoorLightExposure(playerPos);
    if (exposure <= 0.0) return vec3(0.0);
    NirnCraftLoadLightning();
    float energy = 0.0;
    for (int s = 0; s < nirncraftLightningCount; s++) {
        vec4 state = nirncraftDischarge[s];
        float pulse = state.r * mix(0.65, 1.25, state.a);
        vec3 nearest = NirnCraftNearestChannel(playerPos, s);
        vec3 delta = nearest - playerPos;
        float distance2 = dot(delta, delta);
        float facing = max(0.0, dot(worldNormal, delta * inversesqrt(max(1.0, distance2))));
        energy += pulse * 20000.0 / (3600.0 + distance2) * (0.12 + 0.88 * facing);
        if (state.b > 0.5 && state.g > 0.995) {
            // Raise the point within the plasma column so flat terrain receives direct light.
            delta = nirncraftChannel[s * 6 + 3] + vec3(0.0, 12.0, 0.0) - playerPos;
            distance2 = dot(delta, delta);
            facing = max(0.0, dot(worldNormal, delta * inversesqrt(max(1.0, distance2))));
            energy += pulse * 110000.0 / (900.0 + distance2) * (0.18 + 0.82 * facing);
        }
    }
    // Sky exposure suppresses leakage through sealed roofs. Local point shadow maps
    // are not available here; this is not a substitute for interior wall visibility.
    return vec3(0.72, 0.83, 1.12) * min(energy, 24.0) * smoothstep(0.0, 0.65, skyExposure) * exposure;
}
#endif
