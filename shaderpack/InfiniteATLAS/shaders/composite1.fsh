#version 330 compatibility
// After composite.fsh: the light shafts' last step, bloom and the eye, into colortex5 (HDR) for final.fsh.
//  * light shafts (SHAFT_MARCH): composite.fsh marched them into colortex4's alpha (signed: lit dust gains the key
//    light, shadowed fog loses the sun share the fog had); smoothed here over the 4 x 4 block its per-pixel step
//    offsets interleave over, so few steps read as many
//  * bloom: the scene's mip chain (Iris builds it for colortex4) above a knee placed where the eye is adapted, a few
//    widths summed, so lava, fire, lamps, magic and the sun glow
//  * the eye (lib/exposure.glsl): texel 0 of colortex8 measures the scene and adapts, frame to frame
// The scene must not pass through colortex0 (the G-buffer's RGBA8 albedo): it would clip every value over 1.0 (the
// whole daylit sky and the clouds) before the tone curve.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/quality.glsl"
#include "/lib/exposure.glsl"

uniform sampler2D colortex4;
uniform sampler2D colortex8;
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
uniform float frameTime;
uniform float viewWidth;
uniform float viewHeight;

#include "/lib/keylight.glsl"

in vec2 texcoord;

/*
const bool colortex4MipmapEnabled = true;
*/

/* RENDERTARGETS: 5,8 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outExposure;

// what of a colour shows as glow, by its brightness where the eye is (e: linear exposure; 0.8 = the old fixed one)
vec3 knee(vec3 c, float e) {
    float l = max(c.r, max(c.g, c.b)) * e / 0.8;
    return c * smoothstep(1.2, 4.0, l);
}

void main() {
    vec3 c = finite(texture(colortex4, texcoord).rgb);
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
