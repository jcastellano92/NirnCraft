#version 330 compatibility
// After the opaque scene is lit (deferred.fsh): the far field, fog, translucents and light shafts.
//  * aether's far field under the near field (premultiplied; its depth keeps nearer near pixels on top), the same
//    aerial perspective on near pixels, and the ground-fog layer over both
//  * water: refraction with absorption by depth, screen-space reflections falling back to aether's far terrain and
//    the sky, Fresnel, a sun glint, and foam lapping wherever the water is shallow (any shore, any pond)
//  * glass and ice: refraction and tint; under water: absorption fog and caustics on the bed
//  * light shafts: the key light marched along the view ray through the air with real occlusion (lib/shafts.glsl:
//    shadow map, aether's sun map), into colortex4's alpha for composite1.fsh; LOW (no SHAFT_MARCH): screen-space
//    scattering towards the sun (or moon) through gaps in terrain, trees and aether's clouds
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/materials.glsl"
#include "/lib/pbr.glsl"
#include "/lib/fog.glsl"
#include "/lib/noise.glsl"
#include "/lib/weather.glsl"
#include "/lib/waves.glsl"
#include "/lib/aether.glsl"
#include "/lib/media.glsl"
#include "/lib/plumes.glsl"
#include "/lib/flow.glsl"
#include "/lib/quality.glsl"

uniform sampler2D colortex4;
uniform sampler2D colortex5;
uniform sampler2D colortex6;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferProjection;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferModelView;
uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frameTimeCounter;
uniform float far;
uniform float viewWidth;
uniform float viewHeight;
uniform int isEyeInWater;
uniform ivec2 eyeBrightnessSmooth;
uniform float wetness;

#ifdef SHAFT_MARCH
#include "/lib/shafts.glsl"
#endif

in vec2 texcoord;

/* RENDERTARGETS: 4 */
layout(location = 0) out vec4 outColor;


// the world clock the local air drifts by (the mod's game time when present: the same in every pass)
float airClock() { return haveAir() ? airTime() : frameTimeCounter; }

vec3 viewAt(vec2 uv, float d) {
    vec4 v = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

vec2 toScreen(vec3 view) {
    vec4 c = gbufferProjection * vec4(view, 1.0);
    return c.xy / c.w * 0.5 + 0.5;
}

// the opaque scene at uv: lit near field, aerial perspective, the far field under it, ground fog over both
vec3 sceneAt(vec2 uv, vec3 sun, bool marched) {
    vec3 col = texture(colortex4, uv).rgb;
    float d = texture(depthtex1, uv).r;
    vec3 vp = viewAt(uv, d);
    vec3 dir = normalize(mat3(gbufferModelViewInverse) * vp);
    bool sky = d >= 1.0;
    float dist = sky ? 1e30 : length(vp);
    if (!sky) col = applyAerial(col, dir, sun, dist, cameraPosition.y, rainStrength);
    float fogDist = dist;
#ifdef AETHER_FAR_FIELD
    if (aetherPresent()) {
    vec4 farc = aetherColorAt(uv);
    float farDist = aetherDepthAt(uv);
    vec3 over = col * (1.0 - farc.a) + farc.rgb;
    float handEnd = far - 16.0, handStart = handEnd - AETHER_HANDOFF_BAND;
    if (dist >= handStart && dist < handEnd) col = mix(col, over, smoothstep(handStart, handEnd, dist));
    else if (sky || dist >= farDist) {
        col = over;
        fogDist = min(dist, farDist);
    }
    }
#endif
    if (marched && isEyeInWater == 0) {
        // smoke columns (the volcano; fires with SMOKE_FIRE_VOLUMES) in front of whatever is behind them
        vec4 pl = plumesAlong(cameraPosition, dir, fogDist, sun, airClock(), ign(gl_FragCoord.xy, 1.0), rainStrength);
        col = col * pl.a + pl.rgb;
    }
    if (sky && fogDist >= 1e29) col += lightningSky(dir, outdoorExposure(cameraPosition, cameraPosition));
    else if (fogDist >= far) col += lightningSky(dir, outdoorExposure(cameraPosition, cameraPosition)) * 0.35;  // on the far field
    if (isEyeInWater == 0)
        col = applyMedia(col, dir, fogDist, sun, cameraPosition, wetness, rainStrength, airClock(), ign(gl_FragCoord.xy, 0.0), marched);
    return col;
}

// reflection of direction r from view-space point vp: screen-space march over the opaque depth, then aether's
// distant terrain where the ray leaves the screen towards it, then the sky
vec3 reflectionAt(vec3 vp, vec3 rWorld, vec3 sun, float skyLight) {
    vec3 rView = mat3(gbufferModelView) * rWorld;
    vec3 sky = skyColor(normalize(vec3(rWorld.x, max(rWorld.y, 0.01), rWorld.z)), sun) * mix(0.2, 1.0, skyLight);
#ifdef SSR
    // adaptive quality: the march's steps (it reaches less far with fewer: beyond it the far field and sky mirror)
    float qr = qualityKnob(3, 0, QUALITY_FALLBACK);
    int steps = qr > 0.0 ? qualityCount(SSR_STEPS, qr * qualityDistance(length(vp)), qualityDither(gl_FragCoord.xy), 1) : 0;
    if (rView.z < 0.0 && steps > 0) {
        float stepLen = max(0.5, -vp.z * 0.06);
        vec3 p = vp;
        float jitter = ign(gl_FragCoord.xy, frameTimeCounter * 60.0);
        p += rView * stepLen * jitter;
        for (int i = 0; i < SSR_STEPS; i++) {
            if (i >= steps) break;
            p += rView * stepLen;
            stepLen *= 1.25;
            vec2 uv = toScreen(p);
            if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) break;
            float d = texture(depthtex1, uv).r;
            if (d >= 1.0) continue;
            vec3 hit = viewAt(uv, d);
            float dz = hit.z - p.z;
            if (dz > 0.0 && dz < stepLen * 2.5) {
                float edge = smoothstep(0.0, 0.08, min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y)));
                return mix(sky, sceneAt(uv, sun, false), edge * qualityFade(qr, 0.2));
            }
        }
    }
#endif
#ifdef AETHER_FAR_FIELD
    // far terrain mirrored in lakes: where the reflected ray points on screen, if aether drew land there
    vec2 uvF = toScreen(vp + rView * 2000.0);
    if (aetherPresent() && all(greaterThan(uvF, vec2(0.0))) && all(lessThan(uvF, vec2(1.0)))) {
        vec4 fc = aetherColorAt(uvF);
        // fade towards the bare sky near the screen's edge, where the mirrored far field runs out
        float edge = smoothstep(0.0, 0.12, min(min(uvF.x, 1.0 - uvF.x), min(uvF.y, 1.0 - uvF.y)));
        if (fc.a > 0.01) return mix(sky, sky * (1.0 - fc.a) + fc.rgb, edge);
    }
#endif
    return sky;
}

#if defined WATER_FLOW && defined FALLS_MIST
// where a fall meets the water below: on a water top, a fall (vertical water) just above on screen and at about the
// same distance; on a fall, the water top just below. Up to four taps over ~2.5 blocks; 0 elsewhere
float fallsSpray(vec2 uv, vec3 n, float dist) {
    bool top = n.y > 0.5;
    if (!top && abs(n.y) > 0.3) return 0.0;
    float span = 2.5 * viewHeight / (1.4 * max(dist, 1.0)) / viewHeight;  // ~2.5 blocks in uv
    float best = 0.0;
    int taps = qualityCount(4, qualityKnob(5, 3, 1.0), 0.5, 0);  // adaptive quality: 0 drops the spray
    for (int k = 1; k <= 4; k++) {
        if (k > taps) break;
        vec2 q = uv + vec2(0.0, (top ? 1.0 : -1.0) * span * float(k) / 4.0);
        if (q.y < 0.0 || q.y > 1.0) break;
        vec4 inf = texture(colortex6, q);
        if (int(inf.b * 255.0 + 0.5) != MAT_WATER) continue;
        vec3 nq = octDecode(inf.rg);
        bool other = top ? abs(nq.y) < 0.3 : nq.y > 0.5;
        if (!other) continue;
        float dq = length(viewAt(q, texture(depthtex0, q).r));
        if (abs(dq - dist) > 3.0) continue;
        best = max(best, 1.0 - float(k - 1) / 4.0);
    }
    return best;
}
#endif

// screen-space light shafts (without SHAFT_MARCH): march towards the light's screen position, collecting how much of
// the way is open sky
vec3 lightShafts(vec2 uv, vec3 sun, float dist) {
#if defined VOLUMETRIC_LIGHT && !defined SHAFT_MARCH && !defined SKYCRAFT_REALM
    vec3 L = sun.y > -0.05 ? sun : -sun;
    vec3 lightView = mat3(gbufferModelView) * L;
    if (lightView.z > -0.05) return vec3(0.0);  // behind the camera
    vec2 lp = toScreen(lightView * 1000.0);
    // adaptive quality: fewer, longer steps over the same path (coarser, noisier shafts); none at 0
    float qv = qualityKnob(4, 0, QUALITY_FALLBACK);
    if (qv <= 0.0) return vec3(0.0);
    int samples = qualityCount(VL_SAMPLES, qv, qualityDither(gl_FragCoord.xy), 1);
    vec2 delta = (lp - uv) / float(samples);
    float jitter = ign(gl_FragCoord.xy, frameTimeCounter * 60.0);
    vec2 p = uv + delta * jitter;
    float open = 0.0, w = 1.0, wsum = 0.0, decay = pow(0.96, 16.0 / float(samples));  // 0.96^16 over the path
    for (int i = 0; i < VL_SAMPLES; i++) {
        if (i >= samples) break;
        p += delta;
        if (any(lessThan(p, vec2(0.0))) || any(greaterThan(p, vec2(1.0)))) break;
        float s = texture(depthtex1, p).r >= 1.0 ? 1.0 : 0.0;
#ifdef AETHER_FAR_FIELD
        s *= 1.0 - aetherColorAt(p).a;  // far terrain and clouds block the light too
#endif
        open += s * w;
        wsum += w;
        w *= decay;  // the same falloff along the path whatever the count
    }
    open /= max(wsum, 1e-3);
    // without the far field on screen, the low sun behind the horizon's mountains (the mod's sun_horizon) casts none
    if (!aetherPresent() && sun.y > -0.05) open *= smoothstep(-0.015, 0.015, sun.y - sunHorizonSin());
    vec3 dir = normalize(mat3(gbufferModelViewInverse) * viewAt(uv, 1.0));
    float mu = max(dot(dir, L), 0.0);
    float phase = 0.25 + 1.5 * pow(mu, 8.0);
    // more shafts in fog and rain; none deep underground
    float air = 0.05 + 0.4 * rainStrength + groundFogDensity(sun, wetness, rainStrength) * 40.0;
    float outdoors = smoothstep(2.0, 10.0, float(eyeBrightnessSmooth.y) / 16.0);
    vec3 rad = sun.y > -0.05 ? sunRadiance(sun) : vec3(0.035, 0.05, 0.09) * smoothstep(-0.05, -0.2, sun.y);
    return rad * open * phase * air * outdoors * min(dist, 400.0) / 400.0 * 0.5 * qualityFade(qv, 0.15);
#else
    return vec3(0.0);
#endif
}

void main() {
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    float d0 = texture(depthtex0, texcoord).r, d1 = texture(depthtex1, texcoord).r;
    vec3 vp0 = viewAt(texcoord, d0);
    float dist0 = d0 >= 1.0 ? 1e30 : length(vp0);
    vec3 dir = normalize(mat3(gbufferModelViewInverse) * vp0);
    vec4 tr = texture(colortex5, texcoord);
    vec4 info = texture(colortex6, texcoord);
    int mat = int(info.b * 255.0 + 0.5);
    vec3 col;

    if (tr.a > 0.001 && d0 < d1 && (mat == MAT_WATER || mat == MAT_GLASS)) {
        vec3 n = octDecode(info.rg);
        vec2 skyFoam = unpackSkyFoam(info.a);
        float skyLight = skyFoam.x;
        float foam = skyFoam.y;
        // refraction: displace by the surface's slope, but only onto what really lies behind it
        vec2 uvR = texcoord;
#ifdef REFRACTION
        vec2 off = (mat3(gbufferModelView) * n).xy * (mat == MAT_WATER ? 0.03 : 0.015);
        vec2 cand = texcoord + off / max(1.0, dist0 * 0.05);
        if (texture(depthtex1, cand).r > d0) uvR = cand;
#endif
        vec3 behind = sceneAt(uvR, sun, false);
        float thick = max(length(viewAt(uvR, texture(depthtex1, uvR).r)) - dist0, 0.0);
        if (texture(depthtex1, uvR).r >= 1.0) thick = 60.0;
        if (mat == MAT_WATER && isEyeInWater == 0) {
            vec3 tint = tr.rgb / tr.a;  // the lit water colour (biome tinted)
            vec3 absorb = exp(-thick * vec3(0.30, 0.09, 0.06));
            vec3 body = behind * absorb + tint * (1.0 - absorb) * 0.6;
#ifdef CAUSTICS
            if (qualityKnob(5, 2, 1.0) > 0.5)
            body *= 1.0 + 0.5 * caustics((cameraPosition + mat3(gbufferModelViewInverse) * viewAt(uvR, texture(depthtex1, uvR).r)).xz,
                                         frameTimeCounter) * exp(-thick * 0.3) * skyLight;
#endif
            vec3 v = -dir;
            vec3 r = reflect(dir, n);
            // at grazing angles a wave can tilt the mirror ray below the horizon; it would then "find" the bed under
            // the water. Keep it skimming upwards (aether does the same for far water)
            r = normalize(vec3(r.x, max(r.y, 0.01), r.z));
            vec3 refl = reflectionAt(vp0, r, sun, skyLight);
            float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
            col = mix(body, refl, fres);
            // the sun on the waves
            vec3 L = sun.y > -0.05 ? sun : -sun;
            vec3 rad = sun.y > -0.05 ? sunRadiance(sun) : vec3(0.035, 0.05, 0.09);
            vec2 wxz0 = (cameraPosition + mat3(gbufferModelViewInverse) * vp0).xz;
            float rough = waveRoughness(smoothstep(0.6, 1.0, skyLight), waterWind(wxz0, rainStrength), dist0);
            col += rad * ggxSpecular(n, v, L, rough, vec3(0.02)) * max(dot(n, L), 0.0) * skyLight;
#ifdef SHORE_FOAM
            // foam in the shallows, lapping in and out with the swell
            float shallow = smoothstep(1.4, 0.0, thick);
            vec2 wp = (cameraPosition + mat3(gbufferModelViewInverse) * vp0).xz;
            float lap = 0.5 + 0.5 * sin(frameTimeCounter * 1.3 - thick * 5.0 + valueNoise(wp * 0.3) * 4.0);
            float shore = shallow * lap * smoothstep(0.35, 0.75, fbm2(wp * 1.5 + frameTimeCounter * 0.2, 3));
            col = mix(col, skyAmbient(vec3(0, 1, 0), sun) * 1.3 + rad * 0.25, shore * 0.85);
#endif
#ifdef WATER_FLOW
            // white water of rapids and falls (from gbuffers), and the spray where a fall meets the water below
            float spray = 0.0;
#ifdef FALLS_MIST
            spray = fallsSpray(texcoord, n, dist0);
#endif
            float white = clamp(max(foam, spray * 0.85), 0.0, 1.0);
            if (white > 0.0 || spray > 0.0) {
                vec3 foamLit = (skyAmbient(vec3(0, 1, 0), sun) * 1.3 + rad * 0.3 * (0.4 + 0.6 * max(dot(n, L), 0.0)))
                             * mix(0.15, 1.0, skyLight) + vec3(0.012);
                col = mix(col, foamLit, white * 0.9);
                col += foamLit * spray * 0.3;  // the bright mist hanging over the pool
            }
#endif
        } else {
            // glass and ice (and water seen from below): the tinted surface over what it refracts
            col = behind * (1.0 - tr.a) + tr.rgb;
        }
        col = applyAerial(col, dir, sun, dist0, cameraPosition.y, rainStrength);
        if (isEyeInWater == 0)
            col = applyMedia(col, dir, dist0, sun, cameraPosition, wetness, rainStrength, airClock(), ign(gl_FragCoord.xy, 0.0), true);
    } else {
        col = sceneAt(texcoord, sun, true);
        // weather, particles, smoke, lines and the rest (shaded and fogged forward, premultiplied; additive light such
        // as flames and lightning channels carries no coverage)
        if (max(tr.a, max(tr.r, max(tr.g, tr.b))) > 1e-4) col = col * (1.0 - tr.a) + tr.rgb;
    }

    if (isEyeInWater == 1) {
        // under water: light fades and turns blue-green with distance; the surface shimmers above
        vec3 fogc = vec3(0.02, 0.08, 0.10) * (skyAmbient(vec3(0, 1, 0), sun) * 3.0 + 0.05);
        col = mix(fogc, col, exp(-min(dist0, 200.0) * vec3(0.12, 0.06, 0.05)));
    } else if (isEyeInWater == 2) {
        col = mix(vec3(1.2, 0.35, 0.05), col, exp(-min(dist0, 50.0) * 0.8));  // lava
    }
    col += lightShafts(texcoord, sun, dist0);
    // the marched shafts (composite1.fsh smooths and adds them): none seen from under water
    float shafts = 0.0;
#if defined VOLUMETRIC_LIGHT && defined SHAFT_MARCH
    if (isEyeInWater == 0) shafts = shaftsMarch(dir, dist0, sun, gl_FragCoord.xy, wetness, rainStrength);
#endif
    outColor = vec4(col, shafts);
}
