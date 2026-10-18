// Lighting for the near field: the same sun/sky radiance aether shades the far field with, Minecraft's light map
// (sky light for openness, block light for torches), soft sun shadows from the shadow map, and aether's sun map for
// what lies beyond it (mountains over valleys, clouds). keyVisibility() is shared by the forward path
// (translucents, particles) and deferred.fsh.
#ifndef SKYCRAFT_LIGHTING
#define SKYCRAFT_LIGHTING

#include "/lib/atmosphere.glsl"
#include "/lib/noise.glsl"
#include "/lib/aether.glsl"
#include "/lib/clouds.glsl"
#include "/lib/air.glsl"
#include "/lib/keylight.glsl"

#ifdef SHADOWS
uniform sampler2DShadow shadowtex0;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
const bool shadowHardwareFiltering = true;

vec3 distortShadow(vec3 p) {
    float f = (1.0 - SHADOW_DISTORT) + length(p.xy) * SHADOW_DISTORT;
    return vec3(p.xy / f, p.z * 0.25);
}

// playerPos: camera-relative world position (before curvature); n: world normal; toLight: world direction;
// px: the pixel (rotates the sample disk per pixel so a few taps read as a soft edge)
float sunShadow(vec3 playerPos, vec3 n, vec3 toLight, vec2 px) {
    float ndl = dot(n, toLight);
    if (ndl <= 0.0) return 0.0;
    float dist = length(playerPos);
    if (dist > shadowDistance) return 1.0;
    // normal offset grows with distance (texel size) and grazing angle
    vec3 p = playerPos + n * (0.06 + dist * 0.004) * (1.5 - ndl);
    p = shadowKeyRotation(toLight) * p;  // into the frame the map was rendered in (the moon that lights the night)
    vec4 sp = shadowProjection * (shadowModelView * vec4(p, 1.0));
    vec3 s = distortShadow(sp.xyz / sp.w) * 0.5 + 0.5;
    float texel = 1.0 / float(shadowMapResolution);
    float radius = texel * SHADOW_SOFTNESS * (1.5 + dist * 0.02);
    float rot = ign(px, 0.0) * 6.2831853;
    float lit = 0.0;
    for (int i = 0; i < SHADOW_TAPS; i++) {
        // golden-angle spiral
        float r = sqrt((float(i) + 0.5) / float(SHADOW_TAPS));
        float a = float(i) * 2.39996 + rot;
        lit += texture(shadowtex0, vec3(s.xy + vec2(cos(a), sin(a)) * r * radius, s.z - 0.0004));
    }
    float fade = smoothstep(shadowDistance * 0.8, shadowDistance, dist);
    return mix(lit / float(SHADOW_TAPS), 1.0, fade);
}
#endif

#ifdef AETHER_FAR_FIELD
uniform sampler2D aetherSunMap;

bool aetherSunMapValid() {
    ivec2 sz = textureSize(aetherSunMap, 0);
    return sz.x >= 2 && sz.y == sz.x + 1 && texelFetch(aetherSunMap, ivec2(6, sz.x), 0).g == 1.0;
}

// aether's sun map (docs: native/include/aether/aether.h, aether_set_sun_map): the first occluder along the key light
// over ~4 km around the camera, traced through the far field, plus the clouds' transmittance. Returns (lit 0..1 by
// distant terrain: mountains over valleys, beyond the shadow map's reach; cloud transmittance). (1, 1) without a map.
vec2 aetherSun(vec3 playerPos) {
    ivec2 sz = textureSize(aetherSunMap, 0);
    int S = sz.x;
    if (S < 2 || sz.y != S + 1) return vec2(1.0);
    vec2 q[7];
    for (int k = 0; k < 7; k++) q[k] = texelFetch(aetherSunMap, ivec2(k, S), 0).rg;
    if (q[6].y != 1.0) return vec2(1.0);
    vec3 c = vec3(q[0].x, q[0].y, q[1].x);
    float he = q[1].y;
    vec3 X = vec3(q[2].x, q[2].y, q[3].x), Y = vec3(q[3].y, q[4].x, q[4].y), L = vec3(q[5].x, q[5].y, q[6].x);
    vec3 r = playerPos + cameraPosition - c;
    vec2 uv = vec2(dot(r, X), dot(r, Y)) / he * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec2(1.0);
    float s = dot(r, L);
    float texel = 2.0 * he / float(S);
    float bias = 2.0 * texel + 1.5;  // the map is coarse: only occluders well above count (the shadow map has the rest)
    vec2 p = uv * float(S) - 0.5;
    ivec2 i0 = ivec2(floor(p));
    vec2 f = p - vec2(i0);
    float lit = 0.0, cloud = 0.0;
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1);
        vec2 t = texelFetch(aetherSunMap, clamp(i0 + o, ivec2(0), ivec2(S - 1)), 0).rg;
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        lit += w * (t.r > s + bias ? 0.0 : 1.0);
        cloud += w * t.g;
    }
    return vec2(lit, cloud);
}
#endif

#if SUN_HORIZON_TAPS > 0
// how much of the low sun clears the terrain towards it (the mod's surface heights), 1 when the sun is high
float terrainTransmission(vec3 wp, vec3 toLight) {
    if (!haveWeather() || toLight.y <= 0.0) return 1.0;
    float weight = 1.0 - smoothstep(0.20, 0.36, toLight.y);
    float horiz = max(length(toLight.xz), 1e-3);
    vec2 d = toLight.xz / horiz;
    float slope = toLight.y / horiz;
    float tr = 1.0, dist = 160.0;
    for (int i = 0; i < SUN_HORIZON_TAPS; i++) {
        float ground = surfaceHeight(wp.xz + d * dist);
        tr = min(tr, smoothstep(-0.006, 0.006, (wp.y + slope * dist - ground) / dist));
        dist *= 1.85;
    }
    return mix(1.0, tr, weight);
}
#endif


// how much of the key light (sun, or moon at night) reaches this point: shadow map, distant terrain, clouds
float keyVisibility(vec3 playerPos, vec3 n, vec3 toLight, vec2 px) {
    float vis = 1.0;
#ifdef SHADOWS
    vis = sunShadow(playerPos, n, toLight, px);
#else
    vis = clamp(dot(n, toLight) * 4.0, 0.0, 1.0);
#endif
#ifdef AETHER_FAR_FIELD
    vec2 far = aetherSun(playerPos);
    vis *= far.x * mix(0.25, 1.0, far.y);
#endif
#if FALLBACK_CLOUDS > 0
    // no aether: the pack's own deck shades the ground
    if (!aetherPresent() && vis > 0.0) vis *= fallbackCloudShadow(playerPos + cameraPosition, toLight, frameTimeCounter, rainStrength);
#endif
#if SUN_HORIZON_TAPS > 0
    // no sun map (aether off): distant mountains still take the low sun from the valleys, traced from each receiver
    // over the mod's terrain surface (weather dynamics, 64-block cells, out to ~3.5 km)
    if (vis > 0.0 && toLight.y < 0.36
#ifdef AETHER_FAR_FIELD
        && !aetherSunMapValid()
#endif
        ) vis *= terrainTransmission(playerPos + cameraPosition, toLight);
#endif
    return vis;
}

// the light map's block light as colour: warm firelight
vec3 blockLight(float bl) {
    return vec3(1.0, 0.62, 0.32) * (pow(bl, 3.2) * 1.8 + pow(bl, 12.0) * 2.0);
}

// block light at a world position: the colour of the emitter that lights it (the mod's coloured block-light cache,
// within ~26 blocks: soul lanterns blue, lava orange, crystals cyan) and the flicker of flames. Flicker is coherent
// over a few blocks (one torch lights its wall as a whole) and scaled by how flame-like the light is (warm colours
// flicker, crystal light holds steady). time: seconds
vec3 blockLightAt(float bl, vec3 worldPos, vec3 camPos, float time) {
    vec3 c = blockLight(bl);
    if (bl <= 0.0) return c;
    float flame = 1.0;
#ifdef COLORED_LIGHT
    vec3 tint = airLampRadiance(worldPos, camPos);
    float m = max(tint.r, max(tint.g, tint.b));
    if (m > 1e-4) {
        vec3 hue = tint / m;
        float conf = smoothstep(0.0, 0.05, m);
        // the emitter's hue at the brightness the light map gives
        float lc = dot(c, vec3(0.2126, 0.7152, 0.0722)), lh = max(dot(hue, vec3(0.2126, 0.7152, 0.0722)), 0.05);
        c = mix(c, hue * (lc / lh), conf);
        flame = clamp((hue.r - hue.b) * 1.4, 0.0, 1.0);
    }
#endif
#if BLOCKLIGHT_FLICKER > 0
    vec3 q = floor(worldPos / 3.0);
    float h = hash13(q);
    float f = valueNoise(vec2(time * (5.0 + 3.0 * h), h * 97.0)) * 0.65 + valueNoise(vec2(time * 13.0, h * 41.0 + 7.0)) * 0.35;
    c *= 1.0 + (f - 0.5) * 0.22 * float(BLOCKLIGHT_FLICKER) * flame * smoothstep(0.2, 0.6, bl);
#endif
    return c;
}

// forward shading (translucents, particles). albedo linear; lm = light map (block, sky) 0..1
vec3 shadeForward(vec3 albedo, vec3 n, vec2 lm, vec3 playerPos, vec3 sun, float emissive) {
    float sky = lm.y * lm.y;
    vec3 toLight = keyLightDir(sun);
    float vis = keyVisibility(playerPos, n, toLight, gl_FragCoord.xy);
    // no sun where the sky cannot reach (caves): sky light is the light map's own occlusion
    float open = smoothstep(0.05, 0.35, lm.y);
    vec3 direct = keyLightRadiance(sun) * max(dot(n, toLight), 0.0) * vis * open;
    vec3 ambient = skyAmbient(n, sun) * sky + vec3(0.012, 0.012, 0.016);
    return albedo * (direct + ambient + blockLight(lm.x)) + albedo * emissive * 3.0;
}

#endif
