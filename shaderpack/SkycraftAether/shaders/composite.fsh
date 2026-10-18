#version 330 compatibility
// Near field + aether's far field + one aerial perspective for both.
//  * near pixels get the same aerial perspective aether gives the far field
//  * aether's colour is linear and premultiplied over what is behind it: far terrain (opaque), clouds, actors;
//    aether's depth is the distance to the nearest thing it drew, so near geometry in front of it stays on top
//  * across AETHER_HANDOFF_BAND blocks before the end of the render distance the near field fades into the far one
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
#ifdef AETHER_FAR_FIELD
uniform sampler2D aetherColor;  // customTexture (shaders.properties): RGBA16F linear, premultiplied
uniform sampler2D aetherDepth;  // customTexture: R32F view distance of the nearest thing aether drew
#endif
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float far;

in vec2 texcoord;

/*
const int colortex0Format = RGBA16F;
*/

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
    vec3 col = texture(colortex0, texcoord).rgb;
    float d = texture(depthtex0, texcoord).r;
    vec4 vp = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vp /= vp.w;
    vec3 dir = normalize(mat3(gbufferModelViewInverse) * vp.xyz);
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    bool sky = d >= 1.0;
    float dist = sky ? 1e30 : length(vp.xyz);
    if (!sky) col = applyAerial(col, dir, sun, dist, cameraPosition.y, rainStrength);
#ifdef AETHER_FAR_FIELD
    vec4 farc = texture(aetherColor, texcoord);
    float farDist = texture(aetherDepth, texcoord).r;
    vec3 over = col * (1.0 - farc.a) + farc.rgb;
    float handEnd = far - 16.0, handStart = handEnd - AETHER_HANDOFF_BAND;
    if (dist >= handStart && dist < handEnd) col = mix(col, over, smoothstep(handStart, handEnd, dist));
    else if (sky || dist >= farDist) col = over;  // otherwise the near pixel is in front of everything aether drew
#endif
    outColor = vec4(col, 1.0);
}
