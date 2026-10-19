#ifndef NIRNCRAFT_CELESTIAL
#define NIRNCRAFT_CELESTIAL
#include "/lib/atmospherics/nirncraftAtmosphere.glsl"

float NirnCraftSkyClock(ivec2 pixel) {
    vec4 bytes = floor(texelFetch(nirncraftSmokeTex, pixel, 0) * 255.0 + 0.5);
    return dot(bytes, vec4(1.0, 256.0, 65536.0, 16777216.0));
}

float NirnCraftSkyTime() {
    return (NirnCraftSkyClock(ivec2(13, 1)) + texelFetch(nirncraftSmokeTex, ivec2(11, 1), 0).r) * 0.05;
}

vec3 NirnCraftRotateZ(vec3 p, float angle) {
    float c = cos(angle), s = sin(angle);
    return vec3(c * p.x - s * p.y, s * p.x + c * p.y, p.z);
}

float NirnCraftStarHash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float NirnCraftMoonCycle(bool secunda) {
    float days = (NirnCraftSkyClock(ivec2(1, 1)) + texelFetch(nirncraftSmokeTex, ivec2(11, 1), 0).r) / 24000.0;
    return fract((days - 0.75) / (secunda ? 9.0 : 8.0) + (secunda ? 0.375 : 0.0));
}

vec4 NirnCraftMoonDisc(sampler2D atlas, vec3 ray, vec3 moon, float angularSize, vec3 solar, vec3 tint) {
    float forward = dot(ray, moon);
    if (forward < 0.96) return vec4(0.0);
    vec3 reference = abs(moon.y) < 0.96 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    vec3 right = normalize(cross(reference, moon));
    vec3 projectedSun = solar - moon * dot(solar, moon);
    if (dot(projectedSun, projectedSun) > 0.0001) right = normalize(projectedSun);
    vec3 up = cross(moon, right);
    vec2 disc = vec2(dot(ray, right), dot(ray, up)) / (forward * angularSize);
    if (max(abs(disc.x), abs(disc.y)) >= 1.0) return vec4(0.0);
    float cycle = acos(clamp(-dot(solar, moon), -1.0, 1.0)) / 6.2831853;
    float phase = cycle * 16.0;
    float p0 = floor(phase), p1 = mod(p0 + 1.0, 16.0);
    vec2 uv = vec2(disc.x, -disc.y) * 0.5 + 0.5;
    vec2 tile0 = vec2(mod(p0, 4.0), floor(p0 / 4.0));
    vec2 tile1 = vec2(mod(p1, 4.0), floor(p1 / 4.0));
    vec4 texel = mix(texture2DLod(atlas, (tile0 + uv) * 0.25, 0.0),
                     texture2DLod(atlas, (tile1 + uv) * 0.25, 0.0), fract(phase));
    vec3 surface = pow(texel.rgb, vec3(2.2)) * 2.6;
    surface = surface / (vec3(1.0) + surface) * 1.45;
    float illumination = 0.5 + 0.5 * cos(cycle * 6.2831853);
    // Faint reflected Nirn light reveals the dark limb; it is strongest near new moon.
    vec3 earthlitTerrain = pow(texture2DLod(atlas, uv * 0.25, 0.0).rgb, vec3(2.2));
    surface += earthlitTerrain * vec3(0.005, 0.009, 0.016) * (1.0 - illumination);
    texel.rgb = surface * tint * NirnCraftViewTransmission(cameraPosition.y, ray.y);
    texel.a *= smoothstep(-0.035, 0.055, moon.y);
    return texel;
}

// Shared by the direct sky and the water reflection sky; clouds/fog composite in front later.
vec4 NirnCraftCelestialLayer(vec3 ray) {
    ray = normalize(ray);
    vec4 exposure = texelFetch(nirncraftSmokeTex, ivec2(3, 1), 0);
    vec4 options = texelFetch(nirncraftSmokeTex, ivec2(7, 1), 0);
    float visibility = exposure.r / max(0.04, 1.0 - 0.80 * pow(0.80 * exposure.b + 0.20 * exposure.a, 0.65));
    exposure.ba = nirncraftMoonWeights;
    visibility *= 1.0 - 0.80 * pow(dot(nirncraftMoonWeights, vec2(0.80, 0.20)), 0.65);
    float days = (NirnCraftSkyClock(ivec2(1, 1)) + texelFetch(nirncraftSmokeTex, ivec2(11, 1), 0).r) / 24000.0;
    float solar = fract(days - 0.25);
    solar = (solar * 2.0 + 0.5 - 0.5 * cos(solar * 3.14159265)) / 3.0;
    vec3 starsDir = NirnCraftRotateZ(ray, -solar * 6.2831853);
    vec3 solarDirection = nirncraftWorldSun;
    vec3 moon = nirncraftMasserDirection;
    vec3 secunda = nirncraftSecundaDirection;
    float glareM = exp(-max(0.0, 1.0 - dot(ray, moon)) * 20.0) * exposure.b;
    float glareS = exp(-max(0.0, 1.0 - dot(ray, secunda)) * 75.0) * exposure.a * options.b;
    float localVisibility = visibility * exp(-4.5 * glareM - 2.5 * glareS) * smoothstep(-0.025, 0.14, ray.y);
    vec3 radiance = vec3(0.0);

    if (localVisibility > 0.001 && options.r > 0.5) {
        vec3 a = abs(starsDir);
        vec2 uv; float face;
        if (a.x >= a.y && a.x >= a.z) { uv = starsDir.yz / a.x; face = starsDir.x > 0.0 ? 1.0 : 2.0; }
        else if (a.y >= a.z) { uv = starsDir.xz / a.y; face = starsDir.y > 0.0 ? 3.0 : 4.0; }
        else { uv = starsDir.xy / a.z; face = starsDir.z > 0.0 ? 5.0 : 6.0; }
        vec2 grid = uv * 330.0;
        // Derivatives must be evaluated before the divergent per-star branch.
        float aa = max(0.025, length(fwidth(grid)) * 0.45);
        vec2 cell = floor(grid), within = fract(grid);
        float seed = NirnCraftStarHash(vec3(cell, face));
        float threshold = 0.989 - 0.055 * visibility;
        if (seed > threshold) {
            vec2 centre = vec2(NirnCraftStarHash(vec3(cell + 0.7, face)), NirnCraftStarHash(vec3(cell + 2.7, face)));
            float strength = pow(clamp((seed - threshold) / (1.0 - threshold), 0.0, 1.0), 5.0);
            float radius = 0.065 + 0.075 * strength;
            float star = (1.0 - smoothstep(radius, radius + aa, length(within - centre)))
                       * radius * radius / max(aa * aa, radius * radius);
            float hue = NirnCraftStarHash(vec3(cell + 4.1, face));
            vec3 color = hue < 0.14 ? vec3(0.76, 0.85, 1.0) : hue > 0.90 ? vec3(1.0, 0.83, 0.65) : vec3(1.0, 0.98, 0.95);
            float twinkle = 1.0 + (0.10 + 0.12 * pow(1.0 - max(ray.y, 0.0), 2.0))
                    * sin(NirnCraftSkyTime() * (1.4 + hue * 2.5) + seed * 100.0);
            // Keep stellar peaks below the pack's broad HDR bloom response. More faint
            // points should appear on a dark night, rather than hundreds of large glowing spots.
            float flux = min(0.20, star * (0.08 + strength * 1.5));
            radiance += color * flux * twinkle * localVisibility;
        }
    }
    if (localVisibility > 0.001 && options.g > 0.5) {
        vec3 normal = normalize(vec3(0.28, 0.42, 0.86));
        vec3 axis = normalize(cross(normal, vec3(0.0, 1.0, 0.0)));
        vec3 second = cross(normal, axis);
        vec2 uv = vec2(atan(dot(starsDir, axis), dot(starsDir, second)) / 6.2831853 + 0.5,
                       dot(starsDir, normal) / 1.10 + 0.5);
        if (uv.y > 0.0 && uv.y < 1.0) {
            vec4 galaxy = texture2DLod(nirncraftGalaxyTex, uv, 0.0);
            float feather = smoothstep(0.0, 0.22, uv.y) * (1.0 - smoothstep(0.78, 1.0, uv.y));
            radiance += pow(galaxy.rgb, vec3(2.2)) * galaxy.a * feather * 0.11 * pow(localVisibility, 2.2);
        }
    }
    float atmosphericGlare = (1.0 - sunVisibility2) * 0.008;
    radiance *= NirnCraftViewTransmission(cameraPosition.y, ray.y);
    radiance += (vec3(0.85, 0.68, 0.82) * glareM + vec3(0.75, 0.83, 1.0) * glareS) * atmosphericGlare;
    vec4 masser = NirnCraftMoonDisc(nirncraftMasserTex, ray, moon, 0.20, solarDirection, vec3(1.08, 0.77, 0.83));
    vec4 small = options.b > 0.5 ? NirnCraftMoonDisc(nirncraftSecundaTex, ray, secunda, 0.075, solarDirection, vec3(0.85, 0.93, 1.08)) : vec4(0.0);
    radiance = mix(radiance, masser.rgb, masser.a);
    radiance = mix(radiance, small.rgb, small.a);
    // Complementary stores perceptual scene colour until composite1 linearizes it.
    // Our lighting calculations are linear: encode exactly once at that boundary.
    return vec4(pow(max(radiance, vec3(0.0)), vec3(1.0 / 2.2)), max(masser.a, small.a));
}
#endif
