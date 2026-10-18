#version 330 compatibility
// Deferred lighting of the opaque near field (after terrain and entities, before translucents), and the sky.
//  * key light (sun / moon): soft shadow map x aether's sun map (mountains, clouds) x the light map's openness
//  * GGX highlights and a sky reflection by smoothness / F0 (labPBR or material defaults), widened with distance
//  * light through leaves and grass, ambient from the sky with AO (labPBR AO x screen-space AO), warm block light
//  * emitters (lava, fire, lamps, crystals, portals) glow; snow glints
//  * sky pixels: the atmosphere, stars, galaxy and aurora, and the vanilla moon drawn into the G-buffer
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/materials.glsl"
#include "/lib/pbr.glsl"
#include "/lib/quality.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
#ifdef SKYCRAFT_REALM
uniform sampler2D colortex7;  // the realm's sky (gbuffers_skybasic / skytextured), premultiplied
#endif
uniform sampler2D depthtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferProjection;
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frameTimeCounter;
uniform float temperature;  // Iris: the biome's temperature at the camera (cold: < 0.3)
uniform float viewWidth;
uniform float viewHeight;

#include "/lib/lighting.glsl"
#include "/lib/sky.glsl"
#include "/lib/clouds.glsl"
#include "/lib/lightning.glsl"
#include "/lib/celestial.glsl"

float airClock() { return haveAir() ? airTime() : frameTimeCounter; }

in vec2 texcoord;

/*
const int colortex0Format = RGBA8;
const int colortex1Format = RGBA16;
const int colortex2Format = RGBA8;
const int colortex3Format = RGBA8;
const int colortex4Format = RGBA16F;
const int colortex5Format = RGBA16F;
const int colortex6Format = RGBA16;
const int colortex7Format = RGBA16F;
const vec4 colortex0ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex3ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex5ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex6ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex7ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const bool colortex4Clear = false;
const int colortex8Format = R32F;
const bool colortex8Clear = false;
const vec4 colortex8ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
*/

/* RENDERTARGETS: 4 */
layout(location = 0) out vec4 outColor;

vec3 viewAt(vec2 uv, float d) {
    vec4 v = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

// a few depth taps on a disk around the pixel, scaled to the view distance: crevices and contact darken
float screenAO(vec3 vp, vec3 vn, vec2 px) {
#ifdef SSAO
    // adaptive quality: the sample count (fewer far away, where the 0.6-block radius covers a pixel or two); none at 0
    float qa = qualityKnob(2, 0, QUALITY_FALLBACK);
    if (qa <= 0.0) return 1.0;
    int count = qualityCount(SSAO_SAMPLES, qa * qualityDistance(length(vp)), qualityDither(px), 1);
    float occ = 0.0;
    float radius = 0.6;
    float rot = ign(px, 0.0) * 6.2831853;
    for (int i = 0; i < SSAO_SAMPLES; i++) {
        if (i >= count) break;
        float r = (float(i) + 0.5) / float(count);
        float a = float(i) * 2.39996 + rot;
        vec3 o = vec3(cos(a), sin(a), 0.6) * r * radius;
        // into the hemisphere of the normal
        o = o - vn * min(dot(o, vn), 0.0) * 2.0;
        vec3 s = vp + o;
        vec4 clip = gbufferProjection * vec4(s, 1.0);
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) continue;
        vec3 hit = viewAt(uv, texture(depthtex0, uv).r);
        float dz = hit.z - s.z;  // view space: z is negative forward
        occ += (dz > 0.02 && dz < radius * 2.0) ? 1.0 : 0.0;
    }
    return 1.0 - occ / float(count) * 0.7 * qualityFade(qa, 0.2);  // fades in and out with the knob
#else
    return 1.0;
#endif
}

void main() {
    float d = texture(depthtex0, texcoord).r;
    vec3 vp = viewAt(texcoord, d);
    vec3 playerPos = mat3(gbufferModelViewInverse) * vp;
    vec3 dir = normalize(playerPos);
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    vec4 mtl = texture(colortex3, texcoord);

    if (d >= 1.0) {
#ifdef SKYCRAFT_REALM
        // a realm: its own dome and sprites over its fog colour (the Nether draws none: all fog)
        vec4 rs = texture(colortex7, texcoord);
        outColor = vec4(rs.rgb + realmFog() * (1.0 - rs.a), 1.0);
        return;
#endif
        // the sky: the atmosphere, the night sky over it, and whatever the vanilla sky drew (the moon)
        vec3 c = skyColor(dir, sun);
#ifdef WEATHER_SKY
        // the dome greys to slate under the weather over the camera (a blue sky stays blue over a dust storm)
        if (haveWeather()) {
            vec4 wc = weatherSample(weatherField, cameraPosition.xz);
            int band = clamp(int(floor(weatherTexel(weatherField, ivec2(floor(cameraPosition.xz / 64.0))).a * 4.0)), 0, 3);
            float over = band == 2 ? 0.1 * wc.b : clamp(smoothstep(0.6, 0.9, wc.r) * 0.8 + wc.b * 0.5, 0.0, 0.85);
            float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(c, vec3(lum) * vec3(0.92, 0.95, 1.0) * mix(1.0, 0.7, wc.g), over);
        }
#endif
        bool modSky = haveModSky();
        vec4 skyOpt = modSkyOptions();
        float veil = 0.0;
        vec4 moonsC = vec4(0.0);
#ifdef MOD_SKY
        // Nirn's moons (the vanilla moon is left out: there are two) and the mod's aurora
        if (modSky) moonsC = modMoons(dir, sun, moons(sun), veil);
#endif
#ifdef STARS
        if (skyOpt.r > 0.5) c += stars(dir, sun, frameTimeCounter, rainStrength) * (1.0 - veil) * modNightSky().r;
#endif
#ifdef AURORA
#ifdef MOD_SKY
        if (modSky) c += modAurora(dir, sun, skySeconds());
        else
#endif
        {
            float cold = smoothstep(0.35, -0.1, temperature) * (1.0 - rainStrength);
            c += aurora(dir, frameTimeCounter, cold, sun);
        }
#endif
        c = c * (1.0 - moonsC.a) + moonsC.rgb;
        if (mtl.r > 0.99 && !modSky) c += srgbToLinear(texture(colortex0, texcoord).rgb) * 1.4;
        if (!aetherPresent()) {
            // no far field to hide it: the sun sets behind the mountains on the horizon (the mod's sun_horizon, from
            // the camera) instead of shining through them
            float hs = sunHorizonSin();
            if (hs > 0.0 && sun.y < hs + 0.01 && dir.y < hs && dot(dir, sun) > 0.99995)
                c -= sunRadiance(sun) * 18.0 * 1.6 * (1.0 - smoothstep(hs - 0.006, hs, dir.y));
            c = max(c, vec3(0.0));
        }
        // aether draws the clouds (and composite lays them over this sky); without it, the pack's own deck
        if (!aetherPresent()) {
            vec4 cl = fallbackClouds(dir, sun, cameraPosition, frameTimeCounter, rainStrength);
            c = c * (1.0 - cl.a) + cl.rgb;
        }
        outColor = vec4(c, 1.0);
        return;
    }

    vec3 albedo = srgbToLinear(texture(colortex0, texcoord).rgb);
    vec4 nl = texture(colortex1, texcoord);
    vec3 n = octDecode(nl.xy);
    vec2 lm = nl.zw;
    vec4 spec = texture(colortex2, texcoord);
    int mat = int(mtl.r * 255.0 + 0.5);
    float ao = mtl.g * screenAO(vp, normalize(mat3(transpose(gbufferModelViewInverse)) * n), gl_FragCoord.xy);
    float metal = mtl.b;
    float dist = length(playerPos);
    vec3 v = -dir;

    vec3 L = keyLightDir(sun);
    vec3 rad = keyLightRadiance(sun);
    float vis = keyVisibility(playerPos, n, L, gl_FragCoord.xy);
    float open = smoothstep(0.05, 0.35, lm.y);
    float ndl = max(dot(n, L), 0.0);
    vec3 diffuse = albedo * (1.0 - metal) * rad * ndl * vis * open;
#ifdef FOLIAGE_SSS
    if (spec.z > 0.0) {
        // light through thin leaves: strongest looking towards the sun through them, softly wrapped otherwise
        float back = pow(max(dot(-v, L), 0.0), 4.0) * 0.8 + 0.25;
        diffuse += albedo * rad * vis * open * spec.z * back * 0.5;
    }
#endif
    vec3 specular = vec3(0.0);
#ifdef SPECULAR
    float rough = roughnessFrom(spec.x, dist);
    vec3 f0 = mix(vec3(spec.y), albedo, metal);
    specular = rad * vis * open * ndl * ggxSpecular(n, v, L, rough, f0);
    // the sky in smooth surfaces (wet stone, polished metal, ice)
    vec3 r = reflect(-v, n);
    vec3 fr = fresnelSchlick(max(dot(n, v), 0.0), f0) * spec.x * spec.x;
    specular += skyColor(normalize(vec3(r.x, max(r.y, 0.02), r.z)), sun) * fr * lm.y * lm.y;
#endif
    vec3 skyAmb = skyAmbient(n, sun);
#ifdef WEATHER_SKY
    // under a storm cell or a closed deck the sky light is dimmer (the key light already has the clouds' shadow)
    if (haveWeather()) {
        vec4 wr = weatherSample(weatherField, (playerPos + cameraPosition).xz);
        float shade = clamp(smoothstep(0.62, 0.92, wr.r) * 0.45 + wr.g * 0.35 + wr.b * 0.45, 0.0, 0.85);
        skyAmb *= 1.0 - 0.35 * shade;
    }
#endif
    vec3 ambient = albedo * ((skyAmb + auroraIrradiance(n)) * lm.y * lm.y + vec3(0.012, 0.012, 0.016)) * ao;
    vec3 wp = playerPos + cameraPosition;
    vec3 torch = albedo * blockLightAt(lm.x, wp, cameraPosition, airClock()) * mix(0.6, 1.0, ao);
    // the mod's point lights (spells, atronachs, burning creatures) and storm channels light what faces them
    loadAir(cameraPosition);
    if (gLights > 0) torch += albedo * localLights(playerPos, n, true, cameraPosition);
    torch += albedo * lightningSurface(playerPos, n, lm.y, outdoorExposure(wp, cameraPosition));
    vec3 emit = vec3(0.0);
    if (spec.w > 0.0) {
        float flicker = 1.0;
        if (mat == MAT_FIRE || mat == MAT_LAVA)
            flicker = 0.85 + 0.15 * valueNoise(vec2(frameTimeCounter * 6.0, dot(playerPos + cameraPosition, vec3(0.3))));
        if (mat == MAT_PORTAL) flicker = 0.75 + 0.25 * sin(frameTimeCounter * 2.0 + dot(playerPos + cameraPosition, vec3(0.7)));
        emit = albedo * spec.w * emissionStrength(mat) * flicker;
    }
    if (mat == MAT_SNOW) {
        // glints: a sparse scatter of facets catching the key light
        float g = hash13(floor((playerPos + cameraPosition) * 12.0));
        specular += rad * vis * open * step(0.994, g) * pow(max(dot(reflect(-L, n), v), 0.0), 8.0) * 2.0;
    }
    outColor = vec4(diffuse + specular + ambient + torch + emit, 1.0);
}
