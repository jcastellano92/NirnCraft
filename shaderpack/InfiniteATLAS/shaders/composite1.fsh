#version 330 compatibility
// After composite.fsh: the light shafts' last step, bloom and the eye, into colortex5 (HDR) for final.fsh.
//  * light shafts (SHAFT_MARCH): composite.fsh marched them into colortex4's alpha (signed: lit dust gains the key
//    light, shadowed fog loses the sun share the fog had); smoothed here over the 4 x 4 block its per-pixel step
//    offsets interleave over, so few steps read as many
//  * bloom: the scene's mip chain (Iris builds it for colortex4) above a knee placed where the eye is adapted, a few
//    widths summed, so lava, fire, lamps, magic and the sun glow
//  * the eye (lib/exposure.glsl): texel 0 of colortex8 measures the scene and adapts, frame to frame
//  * TAA (lib/taa.glsl): the jittered scene resolved against its history (colortex9) before shafts and bloom
// The scene must not pass through colortex0 (the G-buffer's RGBA8 albedo): it would clip every value over 1.0 (the
// whole daylit sky and the clouds) before the tone curve.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/quality.glsl"
#include "/lib/exposure.glsl"
#include "/lib/taa.glsl"
#include "/lib/atlas.glsl"

uniform sampler2D colortex4;
uniform sampler2D colortex8;
uniform sampler2D colortex9;  // TAA history: last frame's resolved scene
uniform sampler2D depthtex0;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferPreviousProjection;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform vec3 sunPosition;
uniform float frameTime;
uniform float viewWidth;
uniform float viewHeight;

#include "/lib/keylight.glsl"

in vec2 texcoord;

/*
const bool colortex4MipmapEnabled = true;
*/

/* RENDERTARGETS: 5,8,9 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outExposure;
layout(location = 2) out vec4 outHistory;

// where the point seen through this pixel was on screen last frame: the near field from its depth (unjittered), the
// far field from atlas's distance along the ray, the sky by direction alone; the hand moves with the camera
vec2 previousUv(vec2 uv) {
    float d = texture(depthtex0, uv).r;
    if (d < 0.56) return uv;
    vec2 j = taaJitter() / vec2(viewWidth, viewHeight);
    vec4 v = gbufferProjectionInverse * vec4((uv - j) * 2.0 - 1.0, min(d, 1.0) * 2.0 - 1.0, 1.0);
    v.xyz /= v.w;
    vec4 c;
    if (d >= 1.0) {
        vec3 dir = normalize(mat3(gbufferModelViewInverse) * v.xyz);
        float far = atlasDepthAt(uv);
        if (far < 1e29) {
            vec3 p = dir * far + cameraPosition - previousCameraPosition;
            c = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(p, 1.0));
        } else {
            c = gbufferPreviousProjection * vec4(mat3(gbufferPreviousModelView) * dir, 1.0);
        }
    } else {
        vec3 p = (gbufferModelViewInverse * vec4(v.xyz, 1.0)).xyz + cameraPosition - previousCameraPosition;
        c = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(p, 1.0));
    }
    if (c.w <= 0.0) return vec2(-1.0);
    // last frame's pixel, where this frame's jitter put this one (without it a still view wobbled by the jitter)
    return c.xy / c.w * 0.5 + 0.5 + j;
}

// colours are blended tonemapped (a lone bright pixel does not dominate) and clipped in YCoCg (luma apart from chroma)
vec3 taaTm(vec3 c) { return c / (1.0 + max(c.r, max(c.g, c.b))); }
vec3 taaItm(vec3 c) { return c / max(1.0 - max(c.r, max(c.g, c.b)), 1e-4); }
vec3 toYCoCg(vec3 c) { return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b); }
vec3 fromYCoCg(vec3 c) { return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z); }

// the history at uv, Catmull-Rom from 5 bilinear taps: sharp, where bilinear would soften it every frame
vec3 historyAt(vec2 uv) {
    vec2 size = vec2(viewWidth, viewHeight);
    vec2 p = uv * size;
    vec2 t1 = floor(p - 0.5) + 0.5, f = p - t1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f)), w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f)), w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 t0 = (t1 - 1.0) / size, t3 = (t1 + 2.0) / size, t12 = (t1 + w2 / w12) / size;
    vec3 c = textureLod(colortex9, vec2(t12.x, t0.y), 0.0).rgb * (w12.x * w0.y)
           + textureLod(colortex9, vec2(t0.x, t12.y), 0.0).rgb * (w0.x * w12.y)
           + textureLod(colortex9, t12, 0.0).rgb * (w12.x * w12.y)
           + textureLod(colortex9, vec2(t3.x, t12.y), 0.0).rgb * (w3.x * w12.y)
           + textureLod(colortex9, vec2(t12.x, t3.y), 0.0).rgb * (w12.x * w3.y);
    float w = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(c / w, vec3(0.0));
}

vec3 taaResolve(vec2 uv, vec3 cur) {
    vec2 px = 1.0 / vec2(viewWidth, viewHeight);
    vec3 m1 = vec3(0.0), m2 = vec3(0.0);
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
            vec3 s = toYCoCg(taaTm(finite(textureLod(colortex4, uv + vec2(x, y) * px, 0.0).rgb)));
            m1 += s;
            m2 += s * s;
        }
    vec3 mean = m1 / 9.0, sigma = sqrt(max(m2 / 9.0 - mean * mean, 0.0));
    vec2 prev = previousUv(uv);
    if (any(lessThan(prev, vec2(0.0))) || any(greaterThan(prev, vec2(1.0)))) return cur;  // new on screen
    vec3 h = toYCoCg(taaTm(historyAt(prev)));
    // variance clip: towards the neighbourhood's mean, onto the box mean +- 1.25 sigma
    vec3 e = sigma * 1.25 + 1e-5, v = h - mean, a = abs(v / e);
    float m = max(a.x, max(a.y, a.z));
    if (m > 1.0) h = mean + v / m;
    // an eighth of this frame (8 jitter phases), more while the view moves fast (less trailing)
    float speed = length((uv - prev) * vec2(viewWidth, viewHeight));
    float alpha = mix(0.125, 0.3, clamp(speed / 24.0, 0.0, 1.0));
    return taaItm(fromYCoCg(mix(h, toYCoCg(taaTm(cur)), alpha)));
}

// what of a colour shows as glow, by its brightness where the eye is (e: linear exposure; 0.8 = the old fixed one)
vec3 knee(vec3 c, float e) {
    float l = max(c.r, max(c.g, c.b)) * e / 0.8;
    return c * smoothstep(1.2, 4.0, l);
}

void main() {
    vec3 c = finite(texture(colortex4, texcoord).rgb);
#ifdef TAA
    c = taaResolve(texcoord, c);
#endif
    outHistory = vec4(c, 1.0);
#if defined VOLUMETRIC_LIGHT && defined SHAFT_MARCH && !defined NIRNCRAFT_REALM
    // four bilinear taps on pixel corners: the mean of the 4 x 4 block around the pixel (colortex4's alpha: the
    // shafts as a multiple of the key light)
    vec2 px = 1.0 / vec2(viewWidth, viewHeight);
    float k = textureLod(colortex4, texcoord + vec2(-0.5, -0.5) * px, 0.0).a + textureLod(colortex4, texcoord + vec2(1.5, -0.5) * px, 0.0).a
            + textureLod(colortex4, texcoord + vec2(-0.5, 1.5) * px, 0.0).a + textureLod(colortex4, texcoord + vec2(1.5, 1.5) * px, 0.0).a;
    if (k != 0.0) c = max(c + keyLightRadiance(worldSunDir(gbufferModelViewInverse, sunPosition)) * k * 0.25, vec3(0.0));
#endif
    float prev = exposureStored(colortex8);
    float e = exp2(exposureNow(colortex8));
#ifdef BLOOM
    float strength = qualityKnob(8, 0, 1.0);  // adaptive quality: the minimal tier turns the glow off
    if (strength > 0.0) {
    vec3 b = vec3(0.0);
    b += knee(textureLod(colortex4, texcoord, 2.0).rgb, e) * 0.35;
    b += knee(textureLod(colortex4, texcoord, 3.5).rgb, e) * 0.3;
    b += knee(textureLod(colortex4, texcoord, 5.0).rgb, e) * 0.25;
    b += knee(textureLod(colortex4, texcoord, 6.5).rgb, e) * 0.2;
    c += b * 0.12 * strength;
    }
#endif
    outColor = vec4(c, 1.0);
    // the eye: only texel 0 keeps it (the rest of colortex8 is never read)
    float ev = 0.0;
#ifdef AUTO_EXPOSURE
    if (gl_FragCoord.x < 1.0 && gl_FragCoord.y < 1.0)
        ev = 64.0 + exposureAdapt(prev, exposureTarget(sceneLogLuminance(colortex4, vec2(viewWidth, viewHeight))), frameTime);
#endif
    outExposure = vec4(ev, 0.0, 0.0, 1.0);
}
